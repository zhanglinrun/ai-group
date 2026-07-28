#Requires -Version 7.2
# Real Agent smoke: register -> quota init -> authenticated SSE -> LLM result -> quota settlement.
param(
    [string]$Gateway = "http://127.0.0.1:8080",
    [string]$Query = "请只回复 AGENT_SSE_OK，不要调用任何工具。",
    [string]$OutputStyle = "chat",
    [ValidateSet("AUTO", "STANDARD", "DEEP")]
    [string]$ExecutionMode = "STANDARD",
    [ValidateSet("SUCCESS", "MODEL_FAILURE_NO_CHARGE", "QUOTA_FAILURE")]
    [string]$ExpectedOutcome = "SUCCESS",
    [ValidateSet("", "PASSED", "DEGRADED")]
    [string]$ExpectedQualityStatus = "",
    [int]$MinSourceCount = 0,
    [int]$MinCharCount = 0,
    [switch]$RequireDeepArtifact,
    [switch]$RequireHistoryReplay,
    [string]$UploadFile = "",
    [long]$OverrideFreeQuotaBalance = -1,
    [ValidateRange(30, 600)]
    [int]$TimeoutSeconds = 180
)

$ErrorActionPreference = "Stop"

$suffix = [guid]::NewGuid().ToString("N").Substring(0, 12)
$username = "sse_$suffix"
$password = "AgentSmoke!2026"

function Invoke-JsonApi($Method, $Path, $Body = $null, $Token = $null) {
    $headers = @{ "Content-Type" = "application/json" }
    if ($Token) { $headers.Authorization = "Bearer $Token" }
    $params = @{
        Method = $Method
        Uri = "$Gateway$Path"
        Headers = $headers
        TimeoutSec = 20
    }
    if ($null -ne $Body) {
        $params.Body = $Body | ConvertTo-Json -Compress -Depth 10
    }
    return Invoke-RestMethod @params
}

function Assert-SuccessResponse($Response, [string]$Operation) {
    if ([string]$Response.code -notin @("0000", "200")) {
        throw "$Operation failed: code=$($Response.code) info=$($Response.info)"
    }
}

function Invoke-MemberMysql($Sql) {
    $container = (docker ps --format "{{.Names}}" | Where-Object { $_ -match "mysql|mariadb" } | Select-Object -First 1)
    if ([string]::IsNullOrWhiteSpace($container)) { throw "mysql container not found for quota override" }
    $password = $env:MYSQL_ROOT_PASSWORD
    if ([string]::IsNullOrWhiteSpace($password)) {
        $password = (docker inspect $container --format '{{range .Config.Env}}{{println .}}{{end}}' |
            Where-Object { $_ -like "MYSQL_ROOT_PASSWORD=*" } |
            Select-Object -First 1) -replace "^MYSQL_ROOT_PASSWORD=", ""
    }
    if ([string]::IsNullOrWhiteSpace($password)) { throw "MYSQL_ROOT_PASSWORD unavailable for quota override" }
    $Sql | docker exec -i $container mysql -uroot "-p$password" member_db | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "member_db quota override failed" }
}

function Upload-SessionFile([string]$SessionId, [string]$Path, [string]$Token) {
    if (-not (Test-Path -LiteralPath $Path)) { throw "upload file not found: $Path" }
    $response = Invoke-RestMethod -Method POST -Uri "$Gateway/api/agent/file/upload" `
        -Headers @{ Authorization = "Bearer $Token" } `
        -Form @{ sessionId = $SessionId; file = Get-Item -LiteralPath $Path } `
        -TimeoutSec 300
    Assert-SuccessResponse $response "upload $(Split-Path -Leaf $Path)"
    return @{
        fileName = [string]$response.data.name
        ossUrl = [string]$response.data.downloadUrl
        domainUrl = [string]$response.data.previewUrl
        fileSize = [long]$response.data.size
        fileType = [string]$response.data.type
        resourceKey = [string]$response.data.resourceKey
        mimeType = $response.data.mimeType
        originFileName = [string]$response.data.originFileName
    }
}

Write-Host "==> Register isolated Agent smoke user"
$register = Invoke-JsonApi POST "/api/auth/register" @{
    username = $username
    password = $password
    email = "$username@example.test"
}
if ([int]$register.code -ne 200) { throw "register failed: code=$($register.code)" }

$login = Invoke-JsonApi POST "/api/auth/login" @{ username = $username; password = $password }
$accessToken = [string]$login.data.accessToken
$ownerId = [string]$login.data.user.id
if ([int]$login.code -ne 200 -or [string]::IsNullOrWhiteSpace($accessToken)) {
    throw "login failed or accessToken missing"
}
if ($OverrideFreeQuotaBalance -ge 0) {
    if ($ownerId -notmatch "^\d+$") { throw "unsafe owner id for quota override: $ownerId" }
    Invoke-MemberMysql "UPDATE quota_account SET free_quota_balance=$OverrideFreeQuotaBalance, paid_quota_balance=0, frozen_balance=0 WHERE user_id=$ownerId;"
}

$before = Invoke-JsonApi GET "/api/member/summary" $null $accessToken
$expectedFreeQuota = if ($OverrideFreeQuotaBalance -ge 0) { $OverrideFreeQuotaBalance } else { 5000000L }
if ([int]$before.code -ne 200 -or [long]$before.data.freeQuotaBalance -ne $expectedFreeQuota) {
    throw "free quota init failed: $($before.data.freeQuotaBalance)"
}
$beforeAvailable = [long]$before.data.availableQuota

$sessionId = "s-$suffix"
$requestId = "r-$suffix"
$sessionFiles = @()
if (-not [string]::IsNullOrWhiteSpace($UploadFile)) {
    $sessionFiles += Upload-SessionFile $sessionId $UploadFile $accessToken
}

$bodyObject = @{
    sessionId = $sessionId
    requestId = $requestId
    query = $Query
    executionMode = $ExecutionMode
    outputStyle = $OutputStyle
}
if ($sessionFiles.Count -gt 0) {
    $bodyObject.sessionFiles = $sessionFiles
}
$requestBody = $bodyObject | ConvertTo-Json -Compress -Depth 20

Write-Host "==> Call authenticated Agent SSE through Gateway"
$client = [System.Net.Http.HttpClient]::new()
$client.Timeout = [System.Threading.Timeout]::InfiniteTimeSpan
$request = [System.Net.Http.HttpRequestMessage]::new(
    [System.Net.Http.HttpMethod]::Post,
    "$Gateway/web/api/v1/gpt/queryAgentStreamIncr"
)
$request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new("Bearer", $accessToken)
$request.Headers.Accept.Add([System.Net.Http.Headers.MediaTypeWithQualityHeaderValue]::new("text/event-stream"))
$request.Headers.Add("X-Device-Id", "cli-$suffix")
$request.Content = [System.Net.Http.StringContent]::new(
    $requestBody,
    [System.Text.Encoding]::UTF8,
    "application/json"
)
$cts = [System.Threading.CancellationTokenSource]::new([TimeSpan]::FromSeconds($TimeoutSeconds))
$response = $null
$terminalResult = $null
$terminalEvent = $null
$errorEvent = $null
$frameCount = 0
$eventName = $null
$textResult = [System.Text.StringBuilder]::new()
$eventTypes = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
$deepReportEvent = $null

try {
    $response = $client.SendAsync(
        $request,
        [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead,
        $cts.Token
    ).GetAwaiter().GetResult()
    $response.EnsureSuccessStatusCode() | Out-Null
    if ($response.Content.Headers.ContentType.MediaType -ne "text/event-stream") {
        throw "unexpected content-type: $($response.Content.Headers.ContentType)"
    }

    $stream = $response.Content.ReadAsStreamAsync($cts.Token).GetAwaiter().GetResult()
    $reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::UTF8)
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    try {
        while ([DateTime]::UtcNow -lt $deadline) {
            $remaining = $deadline - [DateTime]::UtcNow
            $line = $reader.ReadLineAsync().WaitAsync($remaining, $cts.Token).GetAwaiter().GetResult()
            if ($null -eq $line) { break }
            if ($line.StartsWith("event:")) {
                $eventName = $line.Substring(6).Trim()
                continue
            }
            if (-not $line.StartsWith("data:")) { continue }
            $data = $line.Substring(5).Trim()
            if ([string]::IsNullOrWhiteSpace($data) -or $data -eq "[DONE]") { continue }
            if (-not $data.StartsWith("{")) { continue }

            $frame = $data | ConvertFrom-Json -Depth 50
            $frameCount++
            $eventType = [string]$frame.type
            if ([string]::IsNullOrWhiteSpace($eventType)) {
                throw "SSE data is missing canonical type"
            }
            if (-not [string]::IsNullOrWhiteSpace($eventName) -and $eventName -ne $eventType) {
                throw "SSE event name '$eventName' does not match data type '$eventType'"
            }
            $eventName = $null
            $null = $eventTypes.Add($eventType)
            if ($eventType -eq "text") {
                $textResult.Append([string]$frame.delta) | Out-Null
                continue
            }
            if ($eventType -eq "stage_output" -and [string]$frame.outputType -eq "deep_research_report") {
                $deepReportEvent = $frame
            }
            if ($eventType -eq "error") {
                $errorEvent = $frame
                $terminalResult = [string]$frame.message
                break
            }
            if ($eventType -eq "complete") {
                $terminalEvent = $frame
                $terminalResult = if ($textResult.Length -gt 0) {
                    $textResult.ToString()
                } else {
                    [string]$frame.summary
                }
                break
            }
        }
    } finally {
        $reader.Dispose()
    }
} finally {
    if ($response) { $response.Dispose() }
    $request.Dispose()
    $client.Dispose()
    $cts.Dispose()
}

foreach ($requiredType in @("agent_start")) {
    if (-not $eventTypes.Contains($requiredType)) {
        throw "missing canonical Agent event '$requiredType' (types=$($eventTypes -join ','))"
    }
}
if ([string]::IsNullOrWhiteSpace($terminalResult)) { throw "no non-empty terminal result frame" }

$after = Invoke-JsonApi GET "/api/member/summary" $null $accessToken
if ([int]$after.code -ne 200 -or $null -eq $after.data) {
    throw "quota summary after Agent run failed: code=$($after.code)"
}
foreach ($field in @("availableQuota", "frozenBalance")) {
    if ($after.data.PSObject.Properties.Name -notcontains $field) {
        throw "quota summary after Agent run is missing field: $field"
    }
}
$afterAvailable = [long]$after.data.availableQuota
$afterFrozen = [long]$after.data.frozenBalance
if ($afterFrozen -ne 0L) { throw "quota reservation was not settled: frozen=$afterFrozen" }
if ($ExpectedOutcome -eq "QUOTA_FAILURE") {
    if ($null -eq $errorEvent) {
        throw "expected quota failure, got canonical events=$($eventTypes -join ',')"
    }
    $quotaErrorText = "$([string]$errorEvent.code) $([string]$errorEvent.message)"
    if ($quotaErrorText -notmatch "QUOTA|quota|额度|余额|insufficient|不足") {
        throw "expected quota-related failure, got: $quotaErrorText"
    }
    if ($afterAvailable -ne $beforeAvailable) {
        throw "quota failure changed quota: before=$beforeAvailable after=$afterAvailable"
    }

    Write-Host "AGENT SSE SMOKE OK (outcome=QUOTA_FAILURE, mode=$ExecutionMode, frames=$frameCount, types=$($eventTypes -join ','), quota=$beforeAvailable->$afterAvailable)"
    exit 0
}
if ($ExpectedOutcome -eq "SUCCESS") {
    if ($null -ne $errorEvent -or $null -eq $terminalEvent -or -not $eventTypes.Contains("complete")) {
        throw "Agent Loop did not complete successfully (types=$($eventTypes -join ','), errorCode=$([string]$errorEvent.code))"
    }
    if ($afterAvailable -ge $beforeAvailable) {
        throw "successful LLM run did not consume quota: before=$beforeAvailable after=$afterAvailable"
    }
    if ($ExpectedQualityStatus -or $MinSourceCount -gt 0 -or $MinCharCount -gt 0 -or $RequireDeepArtifact) {
        if ($null -eq $deepReportEvent) { throw "DEEP run did not emit deep_research_report stage output" }
        $payload = $deepReportEvent.payload
        if ($ExpectedQualityStatus -and [string]$payload.qualityStatus -ne $ExpectedQualityStatus) {
            throw "unexpected DEEP quality: expected=$ExpectedQualityStatus actual=$($payload.qualityStatus)"
        }
        if ($MinSourceCount -gt 0 -and [int]$payload.sourceCount -lt $MinSourceCount) {
            throw "DEEP source count too low: expected>=$MinSourceCount actual=$($payload.sourceCount)"
        }
        if ($MinCharCount -gt 0 -and [int]$payload.charCount -lt $MinCharCount) {
            throw "DEEP char count too low: expected>=$MinCharCount actual=$($payload.charCount)"
        }
        if ($RequireDeepArtifact -and @($deepReportEvent.artifactRefs).Count -lt 1) {
            throw "DEEP report emitted no artifact refs"
        }
    }
    if ($RequireHistoryReplay) {
        $history = Invoke-JsonApi GET "/api/agent/conversation/sessions/$sessionId" $null $accessToken
        Assert-SuccessResponse $history "conversation history replay"
        $expectedHistoryMode = if ($ExecutionMode -eq "AUTO") { "STANDARD" } else { $ExecutionMode }
        if ([string]$history.data.executionMode -ne $expectedHistoryMode) {
            throw "history replay executionMode mismatch: $($history.data.executionMode)"
        }
        $canonicalRequestId = "${sessionId}:$requestId"
        $matchingRun = @($history.data.runs) | Where-Object {
            $historyRequestId = [string]$_.requestId
            $historyRequestId -eq $requestId -or
                $historyRequestId -eq $canonicalRequestId -or
                $historyRequestId.EndsWith(":$requestId", [System.StringComparison]::Ordinal)
        } | Select-Object -First 1
        if ($null -eq $matchingRun) {
            throw "history replay did not include run for request $requestId"
        }
        if (@($matchingRun.replayFrames).Count -lt 1) {
            throw "history replay run $requestId has no replay frames"
        }
        $replayJson = $matchingRun.replayFrames | ConvertTo-Json -Compress -Depth 80
        if ($replayJson -notlike "*deep_research_report*") {
            throw "history replay did not include DEEP report frames for request $requestId"
        }
        if ($RequireDeepArtifact -and $replayJson -notlike "*artifactRefs*" -and $replayJson -notlike "*reportArtifactId*") {
            throw "history replay did not include DEEP report artifact metadata for request $requestId"
        }
    }

    Write-Host "AGENT SSE SMOKE OK (outcome=SUCCESS, mode=$ExecutionMode, frames=$frameCount, types=$($eventTypes -join ','), resultChars=$($terminalResult.Length), quota=$beforeAvailable->$afterAvailable)"
    exit 0
}

if ($null -eq $errorEvent -or [string]$errorEvent.code -ne "MODEL_ERROR") {
    throw "expected MODEL_ERROR failure, got canonical events=$($eventTypes -join ',') errorCode=$([string]$errorEvent.code)"
}
if ($afterAvailable -ne $beforeAvailable) {
    throw "failed provider call changed quota: before=$beforeAvailable after=$afterAvailable"
}

Write-Host "AGENT SSE SMOKE OK (outcome=MODEL_FAILURE_NO_CHARGE, mode=$ExecutionMode, frames=$frameCount, types=$($eventTypes -join ','), resultChars=$($terminalResult.Length), quota=$beforeAvailable->$afterAvailable)"
