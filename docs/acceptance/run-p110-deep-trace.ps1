#Requires -Version 7.2
param(
    [string]$Gateway = "http://127.0.0.1:8080",
    [ValidateRange(60, 600)]
    [int]$TimeoutSeconds = 360,
    [string]$Query = "深度调研 Java 虚拟线程的 Java 21 状态。必须给出至少一个 OpenJDK 或 JEP 的真实 URL，并调用报告工具生成一份短 Markdown 文档。",
    [string]$EvidencePath = ""
)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($EvidencePath)) {
    $EvidencePath = Join-Path $PSScriptRoot "p110-real-deep-run.json"
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

function Invoke-DeepTraceRun([string]$Token, [string]$SessionId, [string]$RequestId) {
    $body = @{
        sessionId = $SessionId
        requestId = $RequestId
        query = $Query
        executionMode = "DEEP"
        outputStyle = "docs"
        online = $true
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
    $canonicalRequestId = ""
    $runId = ""
    $deepReport = $null
    $complete = $null
    $error = $null
    $eventName = ""
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
                if ($event.requestId) { $canonicalRequestId = [string]$event.requestId }
                if ($event.runId) { $runId = [string]$event.runId }
                $type = [string]$event.type
                if ([string]::IsNullOrWhiteSpace($type)) { throw "SSE frame has no canonical type" }
                if ($eventName -and $eventName -ne $type) { throw "SSE event name '$eventName' does not match '$type'" }
                $eventName = ""
                $eventTypes.Add($type) | Out-Null
                if ($type -eq "stage_output" -and [string]$event.outputType -eq "deep_research_report") { $deepReport = $event }
                if ($type -eq "error") { $error = $event; break }
                if ($type -eq "complete") { $complete = $event; break }
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
        throw "Agent run did not reach canonical completion (types=$($eventTypes -join ','))"
    }
    if ($null -eq $deepReport -or @($deepReport.artifactRefs).Count -lt 1) {
        throw "DEEP run did not emit a deep_research_report artifact"
    }
    return [pscustomobject]@{
        canonicalRequestId = if ($canonicalRequestId) { $canonicalRequestId } else { Resolve-CanonicalRequestId $SessionId $RequestId }
        runId = $runId
        eventTypes = @($eventTypes | Sort-Object)
        eventIds = @($eventIds)
        deepArtifactCount = @($deepReport.artifactRefs).Count
    }
}

$evidence = [ordered]@{
    schemaVersion = 1
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    gateway = $Gateway
    status = "RUNNING"
    failure = $null
}
$token = ""
try {
    $health = Invoke-RestMethod -Method GET -Uri "$Gateway/actuator/health" -TimeoutSec 5
    if ($health.status -ne "UP") { throw "Gateway health is $($health.status)" }
    $suffix = [guid]::NewGuid().ToString("N").Substring(0, 12)
    $username = "p110_trace_$suffix"
    $password = "P110Trace!2026"
    $register = Invoke-Api POST "/api/auth/register" @{ username = $username; password = $password; email = "$username@example.test" }
    Assert-Ok $register "register"
    $login = Invoke-Api POST "/api/auth/login" @{ username = $username; password = $password }
    Assert-Ok $login "login"
    $token = [string]$login.data.accessToken
    if (-not $token) { throw "login did not return an access token" }
    $before = Invoke-Api GET "/api/member/summary" $null $token
    Assert-Ok $before "free quota summary"
    if ([long]$before.data.availableQuota -le 0) { throw "new user has no free quota" }
    $sessionId = "p110-trace-$suffix"
    $requestId = "p110-trace-$suffix"
    $result = Invoke-DeepTraceRun $token $sessionId $requestId
    $ledger = Invoke-Api GET "/api/member/quota-ledger" $null $token
    Assert-Ok $ledger "quota ledger"
    $after = Invoke-Api GET "/api/member/summary" $null $token
    Assert-Ok $after "post-run quota summary"
    $evidence.sessionId = $sessionId
    $evidence.requestId = $requestId
    $evidence.canonicalRequestId = $result.canonicalRequestId
    $evidence.runId = $result.runId
    $evidence.sseEventTypes = $result.eventTypes
    $evidence.sseEventCount = $result.eventIds.Count
    $evidence.deepArtifactCount = $result.deepArtifactCount
    $evidence.quotaLedgerEntryCount = @($ledger.data).Count
    $evidence.availableQuotaBefore = [long]$before.data.availableQuota
    $evidence.availableQuotaAfter = [long]$after.data.availableQuota
    $evidence.status = "PASSED"
} catch {
    $evidence.status = "FAILED"
    $evidence.failure = $_.Exception.Message
    throw
} finally {
    $evidence | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $EvidencePath -Encoding UTF8
    $token = ""
    Write-Host "P110 DEEP trace evidence: $EvidencePath"
}
