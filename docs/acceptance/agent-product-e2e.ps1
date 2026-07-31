#Requires -Version 7.2
param(
    [string]$Gateway = "http://127.0.0.1:8080",
    [ValidateRange(60, 900)]
    [int]$TimeoutSeconds = 300,
    [string]$EvidencePath = "",
    [switch]$FreshStack,
    [switch]$RequireRealCitations,
    [switch]$RequireRecovery,
    [switch]$RequireDiagnostics
)

$ErrorActionPreference = "Stop"
$scriptRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($EvidencePath)) {
    $EvidencePath = Join-Path $PSScriptRoot ("agent-product-" + (Get-Date -Format "yyyyMMdd-HHmmss") + ".json")
}

function Invoke-Api([string]$Method, [string]$Path, $Body = $null, [string]$Token = "") {
    $headers = @{ "Content-Type" = "application/json" }
    if ($Token) { $headers.Authorization = "Bearer $Token" }
    $parameters = @{ Method = $Method; Uri = "$Gateway$Path"; Headers = $headers; TimeoutSec = 30 }
    if ($null -ne $Body) { $parameters.Body = $Body | ConvertTo-Json -Depth 20 -Compress }
    return Invoke-RestMethod @parameters
}

function Assert-Ok($Response, [string]$Operation) {
    if ($null -eq $Response -or [string]$Response.code -notin @("0000", "200")) {
        throw "$Operation failed: code=$($Response.code) info=$($Response.info)"
    }
}

function Resolve-CanonicalRequestId([string]$SessionId, [string]$RequestId) {
    $candidate = "$SessionId`:$RequestId"
    if ($candidate.Length -le 64) { return $candidate }
    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = $sha256.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($candidate))
        return -join ($bytes | ForEach-Object { $_.ToString("x2") })
    } finally {
        $sha256.Dispose()
    }
}

function Invoke-AgentRun([string]$Token, [string]$SessionId, [string]$RequestId,
                         [string]$Query, [string]$OutputStyle, [bool]$Online,
                         [ValidateSet("STANDARD", "DEEP")][string]$ExecutionMode = "DEEP") {
    $body = @{
        sessionId = $SessionId
        requestId = $RequestId
        query = $Query
        executionMode = $ExecutionMode
        outputStyle = $OutputStyle
        online = $Online
    } | ConvertTo-Json -Depth 20 -Compress
    $client = [System.Net.Http.HttpClient]::new()
    $client.Timeout = [System.Threading.Timeout]::InfiniteTimeSpan
    $request = [System.Net.Http.HttpRequestMessage]::new(
        [System.Net.Http.HttpMethod]::Post, "$Gateway/web/api/v1/gpt/queryAgentStreamIncr")
    $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new("Bearer", $Token)
    $request.Headers.Accept.Add([System.Net.Http.Headers.MediaTypeWithQualityHeaderValue]::new("text/event-stream"))
    $request.Content = [System.Net.Http.StringContent]::new($body, [System.Text.Encoding]::UTF8, "application/json")
    $cancellation = [System.Threading.CancellationTokenSource]::new([TimeSpan]::FromSeconds($TimeoutSeconds))
    $response = $null
    $eventTypes = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
    $eventIds = [System.Collections.Generic.List[string]]::new()
    $text = [System.Text.StringBuilder]::new()
    $deepReport = $null
    $complete = $null
    $error = $null
    $eventName = ""
    $canonicalRequestId = ""
    try {
        $response = $client.SendAsync($request, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead,
            $cancellation.Token).GetAwaiter().GetResult()
        $response.EnsureSuccessStatusCode() | Out-Null
        if ($response.Content.Headers.ContentType.MediaType -ne "text/event-stream") {
            throw "Agent response is not SSE: $($response.Content.Headers.ContentType)"
        }
        $reader = [System.IO.StreamReader]::new($response.Content.ReadAsStreamAsync($cancellation.Token).GetAwaiter().GetResult())
        try {
            while (-not $cancellation.IsCancellationRequested) {
                $line = $reader.ReadLineAsync().WaitAsync($cancellation.Token).GetAwaiter().GetResult()
                if ($null -eq $line) { break }
                if ($line.StartsWith("id:")) { $eventIds.Add($line.Substring(3).Trim()) | Out-Null; continue }
                if ($line.StartsWith("event:")) { $eventName = $line.Substring(6).Trim(); continue }
                if (-not $line.StartsWith("data:")) { continue }
                $data = $line.Substring(5).Trim()
                if ([string]::IsNullOrWhiteSpace($data) -or $data -eq "[DONE]" -or -not $data.StartsWith("{")) { continue }
                $event = $data | ConvertFrom-Json -Depth 50
                if ($event.PSObject.Properties.Name -contains "requestId" -and $event.requestId) {
                    $canonicalRequestId = [string]$event.requestId
                }
                $type = [string]$event.type
                if ([string]::IsNullOrWhiteSpace($type)) { throw "SSE frame has no canonical type" }
                if ($eventName -and $eventName -ne $type) { throw "SSE event name '$eventName' does not match '$type'" }
                $eventName = ""
                $eventTypes.Add($type) | Out-Null
                switch ($type) {
                    "text" { $text.Append([string]$event.delta) | Out-Null }
                    "stage_output" {
                        if ([string]$event.outputType -eq "deep_research_report") { $deepReport = $event }
                    }
                    "error" { $error = $event; break }
                    "complete" { $complete = $event; break }
                }
                if ($null -ne $error -or $null -ne $complete) { break }
            }
        } finally {
            $reader.Dispose()
        }
    } finally {
        if ($response) { $response.Dispose() }
        $request.Dispose()
        $client.Dispose()
        $cancellation.Dispose()
    }
    if ($null -ne $error) { throw "Agent error: $($error.code) $($error.message)" }
    if ($null -eq $complete -or -not $eventTypes.Contains("agent_start")) {
        throw "Agent run did not reach a canonical completion (types=$($eventTypes -join ','))"
    }
    $terminalText = if ($text.Length -gt 0) { $text.ToString() } else { [string]$complete.summary }
    return [pscustomobject]@{
        requestId = $RequestId
        canonicalRequestId = if ($canonicalRequestId) { $canonicalRequestId } else {
            Resolve-CanonicalRequestId $SessionId $RequestId
        }
        sessionId = $SessionId
        outputStyle = $OutputStyle
        eventTypes = @($eventTypes)
        eventIds = @($eventIds)
        text = $terminalText
        deepReport = $deepReport
        complete = $complete
    }
}

function Get-RunDiagnostics([string]$Token, [string]$CanonicalRequestId) {
    foreach ($candidate in @($CanonicalRequestId)) {
        try {
            $value = Invoke-Api GET "/api/agent/runs/$([uri]::EscapeDataString($candidate))/diagnostics" $null $Token
            Assert-Ok $value "run diagnostics"
            if ($null -ne $value.data) { return $value.data }
        } catch {
            continue
        }
    }
    throw "owner-scoped diagnostics were not available for requestId=$CanonicalRequestId"
}

function Test-SseCursorReplay([string]$Token, [string]$CanonicalRequestId) {
    $candidates = @($CanonicalRequestId)
    foreach ($candidate in $candidates) {
        $client = [System.Net.Http.HttpClient]::new()
        $response = $null
        $request = [System.Net.Http.HttpRequestMessage]::new(
            [System.Net.Http.HttpMethod]::Get,
            "$Gateway/api/agent/runs/$([uri]::EscapeDataString($candidate))/events?cursor=0")
        $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new("Bearer", $Token)
        $request.Headers.Add("Last-Event-ID", "0")
        try {
            $response = $client.Send($request, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead)
            if (-not $response.IsSuccessStatusCode) { continue }
            $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            if ($body -match '(?m)^id:\s*\d+' -and $body -match '(?m)^data:\s*\{') {
                return $true
            }
        } catch {
            continue
        } finally {
            if ($response) { $response.Dispose() }
            $request.Dispose()
            $client.Dispose()
        }
    }
    return $false
}

function Save-Evidence($Payload) {
    $directory = Split-Path -Parent $EvidencePath
    if (-not (Test-Path -LiteralPath $directory)) { New-Item -ItemType Directory -Path $directory -Force | Out-Null }
    $Payload | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $EvidencePath -Encoding utf8
}

function Get-GitProvenance() {
    try {
        $head = (& git -C $scriptRoot rev-parse HEAD 2>$null)
        $porcelain = @(& git -C $scriptRoot status --porcelain -uall 2>$null)
        return [ordered]@{
            gitHead = [string]$head
            gitDirty = ($porcelain.Count -gt 0)
            gitChangedPathCount = $porcelain.Count
        }
    } catch {
        return [ordered]@{ gitHead = $null; gitDirty = $null; gitChangedPathCount = $null }
    }
}

$evidence = [ordered]@{
    schemaVersion = 2
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    gateway = $Gateway
    freshStackRequested = [bool]$FreshStack
    provenance = Get-GitProvenance
    freeQuotaPath = [ordered]@{ status = "PENDING" }
    scenarios = @()
    status = "RUNNING"
    failure = $null
}
$token = ""
try {
    $health = Invoke-RestMethod -Method GET -Uri "$Gateway/actuator/health" -TimeoutSec 5
    if ($health.status -ne "UP") { throw "Gateway health is $($health.status)" }

    $suffix = [guid]::NewGuid().ToString("N").Substring(0, 12)
    $username = "agent_product_$suffix"
    $password = "AgentProduct!2026"
    $register = Invoke-Api POST "/api/auth/register" @{ username = $username; password = $password; email = "$username@example.test" }
    Assert-Ok $register "register"
    $login = Invoke-Api POST "/api/auth/login" @{ username = $username; password = $password }
    Assert-Ok $login "login"
    $token = [string]$login.data.accessToken
    if (-not $token) { throw "login did not return an access token" }

    $before = Invoke-Api GET "/api/member/summary" $null $token
    Assert-Ok $before "free quota summary"
    if ([long]$before.data.availableQuota -le 0) { throw "new user has no free quota" }
    $evidence.freeQuotaPath = [ordered]@{
        status = "OBSERVED"
        registeredNewUser = $true
        availableQuotaBefore = [long]$before.data.availableQuota
        frozenBalanceBefore = [long]$before.data.frozenBalance
    }

    $standardSession = "agent-product-standard-$suffix"
    $standard = Invoke-AgentRun $token $standardSession "standard-$suffix" "直接回答，不调用工具：AGENT_PRODUCT_STANDARD_OK" "chat" $false "STANDARD"
    if ($standard.text -notmatch "AGENT_PRODUCT_STANDARD_OK") { throw "STANDARD result did not contain its requested marker" }
    $evidence.scenarios += [ordered]@{ id = "standard"; requestId = $standard.requestId; sessionId = $standard.sessionId; status = "PASSED" }

    $artifactSpecs = @(
        [ordered]@{ id = "deep-document"; style = "docs"; online = $true; query = "深度调研 Java 虚拟线程的 Java 21 状态。必须给出至少一个 OpenJDK 或 JEP 的真实 URL，并调用报告工具生成一份短 Markdown 文档。" },
        [ordered]@{ id = "deep-ppt"; style = "ppt"; expectedExtensions = @(".pptx"); online = $false; query = "生成三页 PPTX：Agent 请求、工具执行、CompletionGate；不要编造来源。" },
        [ordered]@{ id = "deep-web"; style = "html"; expectedExtensions = @(".html", ".htm"); online = $false; query = "生成一个短 HTML 网页，展示 Agent 的 RUNNING、SUCCESS、FAILED 三个终态。" },
        [ordered]@{ id = "deep-table"; style = "table"; expectedExtensions = @(".csv", ".xlsx"); online = $false; query = "生成一个表格产物，列出 STANDARD、DEEP、工具、诊断四项及其最小验收内容。" }
    )
    foreach ($spec in $artifactSpecs) {
        $sessionId = "agent-product-$($spec.id)-$suffix"
        $requestId = "$($spec.id)-$suffix"
        $result = Invoke-AgentRun $token $sessionId $requestId $spec.query $spec.style ([bool]$spec.online) "DEEP"
        $payload = if ($null -ne $result.deepReport) { $result.deepReport.payload } else { $null }
        $scenario = [ordered]@{
            id = $spec.id
            requestId = $requestId
            canonicalRequestId = $result.canonicalRequestId
            sessionId = $sessionId
            status = "OBSERVED"
            artifactCount = if ($null -ne $result.deepReport) { @($result.deepReport.artifactRefs).Count } else { 0 }
            qualityStatus = if ($null -ne $payload) { [string]$payload.qualityStatus } else { $null }
            sourceCount = if ($null -ne $payload) { [int]$payload.sourceCount } else { $null }
            citationCoverage = if ($null -ne $payload) { [double]$payload.citationCoverage } else { $null }
            previewContainsHttp = if ($null -ne $payload) { [string]$payload.previewMarkdown -match 'https?://' } else { $false }
            artifactNames = if ($null -ne $result.deepReport) {
                @($result.deepReport.artifactRefs | ForEach-Object { [string]$_.fileName })
            } else { @() }
        }
        $evidence.scenarios += $scenario
        if ($null -eq $result.deepReport -or @($result.deepReport.artifactRefs).Count -lt 1) {
            throw "$($spec.id) did not emit a deep_research_report artifact"
        }
        if ($spec.Contains("expectedExtensions")) {
            $hasExpectedArtifact = @($scenario.artifactNames | Where-Object {
                $name = [string]$_
                $spec.expectedExtensions | Where-Object { $name.EndsWith([string]$_, [System.StringComparison]::OrdinalIgnoreCase) }
            }).Count -gt 0
            if (-not $hasExpectedArtifact) {
                throw "$($spec.id) did not emit the expected artifact type ($($spec.expectedExtensions -join ', ')); observed=$($scenario.artifactNames -join ', ')"
            }
        }
        if ($spec.id -eq "deep-document" -and $RequireRealCitations) {
            if ([int]$payload.sourceCount -lt 1 -or [double]$payload.citationCoverage -lt 1D -or
                [string]$payload.qualityStatus -ne "PASSED") {
                throw "DEEP document did not meet the real-source citation contract"
            }
        }
        $scenario.status = "PASSED"
        if ($RequireDiagnostics) {
            $diagnostics = Get-RunDiagnostics $token $result.canonicalRequestId
            if ($null -eq $diagnostics.modelInvocations -or $diagnostics.PSObject.Properties.Name -contains "responseText") {
                throw "$($spec.id) diagnostics did not expose redacted model invocation facts"
            }
            $scenario.diagnostics = [ordered]@{ modelInvocations = @($diagnostics.modelInvocations).Count; toolInvocations = @($diagnostics.toolInvocations).Count }
        }
        if ($spec.id -eq "deep-document" -and $RequireRecovery) {
            if (-not (Test-SseCursorReplay $token $result.canonicalRequestId)) {
                throw "SSE cursor replay did not return durable event ids for $($result.canonicalRequestId)"
            }
            $scenario.sseCursorReplay = "PASSED"
        }
    }

    $history = Invoke-Api GET "/api/agent/conversation/sessions/$standardSession" $null $token
    Assert-Ok $history "conversation history"
    $ledger = Invoke-Api GET "/api/member/quota-ledger" $null $token
    Assert-Ok $ledger "quota ledger"
    if (@($ledger.data).Count -lt 1) { throw "quota ledger has no Agent consumption entry" }
    $after = Invoke-Api GET "/api/member/summary" $null $token
    Assert-Ok $after "post-run quota summary"
    if ([long]$after.data.availableQuota -ge [long]$before.data.availableQuota -or [long]$after.data.frozenBalance -ne 0) {
        throw "Agent consumption or settlement was not visible in the free quota account"
    }
    $memoryPreference = Invoke-Api GET "/api/agent/memories/preference" $null $token
    Assert-Ok $memoryPreference "memory preference"
    if ($memoryPreference.data.enabled -ne $false) { throw "new user long-term memory must default to disabled" }
    $evidence.freeQuotaPath.historyRunCount = @($history.data.runs | Where-Object { $null -ne $_ }).Count
    $evidence.freeQuotaPath.quotaLedgerEntryCount = @($ledger.data).Count
    $evidence.freeQuotaPath.availableQuotaAfter = [long]$after.data.availableQuota
    $evidence.freeQuotaPath.frozenBalanceAfter = [long]$after.data.frozenBalance
    $evidence.freeQuotaPath.consumedQuota = [long]$before.data.availableQuota - [long]$after.data.availableQuota
    $evidence.freeQuotaPath.longTermMemoryDefaultDisabled = ($memoryPreference.data.enabled -eq $false)
    $evidence.freeQuotaPath.status = "PASSED"
    $evidence.status = "PASSED"
} catch {
    $evidence.status = "FAILED"
    $evidence.failure = $_.Exception.Message
    throw
} finally {
    Save-Evidence $evidence
    $token = ""
    Write-Host "Agent product evidence: $EvidencePath"
}
