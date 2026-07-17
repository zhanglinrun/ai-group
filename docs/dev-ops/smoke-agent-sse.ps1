#Requires -Version 7.2
# Real Agent smoke: register -> quota init -> authenticated SSE -> LLM result -> quota settlement.
param(
    [string]$Gateway = "http://127.0.0.1:8080",
    [string]$Query = "请只回复 AGENT_SSE_OK，不要调用任何工具。",
    [string]$OutputStyle = "chat",
    [ValidateSet("AUTO", "STANDARD", "DEEP")]
    [string]$ExecutionMode = "STANDARD",
    [ValidateSet("SUCCESS", "MODEL_FAILURE_NO_CHARGE")]
    [string]$ExpectedOutcome = "SUCCESS",
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

Write-Host "==> Register isolated Agent smoke user"
$register = Invoke-JsonApi POST "/api/auth/register" @{
    username = $username
    password = $password
    email = "$username@example.test"
}
if ([int]$register.code -ne 200) { throw "register failed: code=$($register.code)" }

$login = Invoke-JsonApi POST "/api/auth/login" @{ username = $username; password = $password }
$accessToken = [string]$login.data.accessToken
if ([int]$login.code -ne 200 -or [string]::IsNullOrWhiteSpace($accessToken)) {
    throw "login failed or accessToken missing"
}

$before = Invoke-JsonApi GET "/api/member/summary" $null $accessToken
if ([int]$before.code -ne 200 -or [long]$before.data.freeQuotaBalance -ne 5000000L) {
    throw "free quota init failed: $($before.data.freeQuotaBalance)"
}
$beforeAvailable = [long]$before.data.availableQuota

$requestBody = @{
    sessionId = "s-$suffix"
    requestId = "r-$suffix"
    query = $Query
    executionMode = $ExecutionMode
    outputStyle = $OutputStyle
} | ConvertTo-Json -Compress

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
$sawRunFinished = $false
$runFinishedEvent = $null
$frameCount = 0
$eventTypes = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)

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
            if (-not $line.StartsWith("data:")) { continue }
            $data = $line.Substring(5).Trim()
            if ([string]::IsNullOrWhiteSpace($data) -or $data -eq "[DONE]") { continue }
            if (-not $data.StartsWith("{") -and -not $data.StartsWith("[")) { continue }

            $frame = $data | ConvertFrom-Json -Depth 50
            $frameCount++
            $eventPayload = $frame.resultMap.eventData.resultMap
            if ($null -eq $eventPayload -and $frame.messageType) { $eventPayload = $frame }
            if ($null -eq $eventPayload) { continue }

            $messageType = [string]$eventPayload.messageType
            $eventResult = [string]$eventPayload.result
            if ([string]::IsNullOrWhiteSpace($eventResult)) {
                $eventResult = [string]$eventPayload.taskSummary
            }
            if (-not [string]::IsNullOrWhiteSpace($messageType)) {
                $null = $eventTypes.Add($messageType)
            }
            if ($messageType -eq "run_finished") {
                $sawRunFinished = $true
                $runFinishedEvent = if ($null -ne $eventPayload.resultMap) {
                    $eventPayload.resultMap
                } else {
                    $eventPayload
                }
            }
            if ($messageType -eq "result" -and ($frame.finished -eq $true -or $eventPayload.finish -eq $true)) {
                $terminalResult = if (-not [string]::IsNullOrWhiteSpace($eventResult)) {
                    $eventResult
                } else {
                    [string]$frame.response
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

foreach ($requiredType in @("run_started", "run_finished", "result")) {
    if (-not $eventTypes.Contains($requiredType)) {
        throw "missing canonical Agent Loop event '$requiredType' (types=$($eventTypes -join ','))"
    }
}
if (-not $sawRunFinished) { throw "terminal result arrived before run_finished" }
if ([string]::IsNullOrWhiteSpace($terminalResult)) { throw "no non-empty terminal result frame" }
if ($null -eq $runFinishedEvent) { throw "run_finished payload is missing" }

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
$runStatus = [string]$runFinishedEvent.runStatus
if ([string]::IsNullOrWhiteSpace($runStatus)) {
    $runStatus = [string]$runFinishedEvent.status
}
$stopReason = [string]$runFinishedEvent.stopReason

if ($ExpectedOutcome -eq "SUCCESS") {
    foreach ($requiredType in @("verification_started", "verification_result")) {
        if (-not $eventTypes.Contains($requiredType)) {
            throw "successful Agent Loop is missing '$requiredType' (types=$($eventTypes -join ','), status=$runStatus, stopReason=$stopReason)"
        }
    }
    if ($runStatus -ne "SUCCESS" -or $runFinishedEvent.completionGatePassed -ne $true) {
        throw "Agent Loop did not finish successfully: status=$runStatus stopReason=$stopReason"
    }
    if ($afterAvailable -ge $beforeAvailable) {
        throw "successful LLM run did not consume quota: before=$beforeAvailable after=$afterAvailable"
    }

    Write-Host "AGENT SSE SMOKE OK (outcome=SUCCESS, mode=$ExecutionMode, frames=$frameCount, types=$($eventTypes -join ','), resultChars=$($terminalResult.Length), quota=$beforeAvailable->$afterAvailable)"
    exit 0
}

if ($runStatus -ne "FAILED" -or $stopReason -ne "MODEL_ERROR") {
    throw "expected MODEL_ERROR failure, got status=$runStatus stopReason=$stopReason"
}
if ($afterAvailable -ne $beforeAvailable) {
    throw "failed provider call changed quota: before=$beforeAvailable after=$afterAvailable"
}

Write-Host "AGENT SSE SMOKE OK (outcome=MODEL_FAILURE_NO_CHARGE, mode=$ExecutionMode, frames=$frameCount, types=$($eventTypes -join ','), resultChars=$($terminalResult.Length), quota=$beforeAvailable->$afterAvailable)"
