#Requires -Version 7.2
param(
    [string]$Gateway = "http://127.0.0.1:8080",
    [ValidateRange(60, 900)]
    [int]$TimeoutSeconds = 420
)

$ErrorActionPreference = "Stop"
$suffix = [guid]::NewGuid().ToString("N").Substring(0, 12)
$username = "deep_$suffix"
$password = "AgentDeep!2026"

function Invoke-JsonApi($Method, $Path, $Body = $null, $Token = $null) {
    $headers = @{ "Content-Type" = "application/json" }
    if ($Token) { $headers.Authorization = "Bearer $Token" }
    $params = @{
        Method = $Method
        Uri = "$Gateway$Path"
        Headers = $headers
        TimeoutSec = 30
    }
    if ($null -ne $Body) {
        $params.Body = $Body | ConvertTo-Json -Compress -Depth 20
    }
    Invoke-RestMethod @params
}

function Assert-SuccessResponse($Response, [string]$Operation) {
    if ([string]$Response.code -notin @("0000", "200")) {
        throw "$Operation failed: code=$($Response.code) info=$($Response.info)"
    }
}

$register = Invoke-JsonApi POST "/api/auth/register" @{
    username = $username
    password = $password
    email = "$username@example.test"
}
Assert-SuccessResponse $register "register"
$login = Invoke-JsonApi POST "/api/auth/login" @{ username = $username; password = $password }
Assert-SuccessResponse $login "login"
$accessToken = [string]$login.data.accessToken
if ([string]::IsNullOrWhiteSpace($accessToken)) { throw "login returned no access token" }

$before = Invoke-JsonApi GET "/api/member/summary" $null $accessToken
Assert-SuccessResponse $before "quota summary before run"
$beforeAvailable = [long]$before.data.availableQuota

$requestBody = @{
    sessionId = "deep-session-$suffix"
    requestId = "deep-request-$suffix"
    query = "Create a concise 2026 enterprise AI Agent trend report. First create a Todo, then call deep_search for current sources, and finally call report_tool to create a Markdown report with an executive summary, three trends, risks, and sources."
    executionMode = "DEEP"
    online = $true
    outputStyle = "docs"
} | ConvertTo-Json -Compress

$client = [System.Net.Http.HttpClient]::new()
$client.Timeout = [System.Threading.Timeout]::InfiniteTimeSpan
$request = [System.Net.Http.HttpRequestMessage]::new(
    [System.Net.Http.HttpMethod]::Post,
    "$Gateway/web/api/v1/gpt/queryAgentStreamIncr"
)
$request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new("Bearer", $accessToken)
$request.Headers.Accept.Add([System.Net.Http.Headers.MediaTypeWithQualityHeaderValue]::new("text/event-stream"))
$request.Headers.Add("X-Device-Id", "deep-smoke-$suffix")
$request.Content = [System.Net.Http.StringContent]::new(
    $requestBody,
    [System.Text.Encoding]::UTF8,
    "application/json"
)
$cancellation = [System.Threading.CancellationTokenSource]::new([TimeSpan]::FromSeconds($TimeoutSeconds))
$response = $null
$eventName = $null
$completeEvent = $null
$frameCount = 0
$pausedCount = 0
$resumeCount = 0
$todoCount = 0
$eventTypes = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
$startedTools = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
$successfulTools = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
$artifactRefs = [System.Collections.Generic.List[object]]::new()

try {
    $response = $client.SendAsync(
        $request,
        [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead,
        $cancellation.Token
    ).GetAwaiter().GetResult()
    $response.EnsureSuccessStatusCode() | Out-Null
    if ($response.Content.Headers.ContentType.MediaType -ne "text/event-stream") {
        throw "unexpected content type: $($response.Content.Headers.ContentType)"
    }

    $stream = $response.Content.ReadAsStreamAsync($cancellation.Token).GetAwaiter().GetResult()
    $reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::UTF8)
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    try {
        while ([DateTime]::UtcNow -lt $deadline) {
            $line = $reader.ReadLineAsync().WaitAsync($deadline - [DateTime]::UtcNow, $cancellation.Token).GetAwaiter().GetResult()
            if ($null -eq $line) { break }
            if ($line.StartsWith("event:")) {
                $eventName = $line.Substring(6).Trim()
                continue
            }
            if (-not $line.StartsWith("data:")) { continue }
            $data = $line.Substring(5).Trim()
            if ([string]::IsNullOrWhiteSpace($data) -or $data -eq "[DONE]" -or -not $data.StartsWith("{")) { continue }

            $frame = $data | ConvertFrom-Json -Depth 50
            $frameCount++
            $eventType = [string]$frame.type
            if ([string]::IsNullOrWhiteSpace($eventType)) { throw "SSE data is missing canonical type" }
            if ($eventName -and $eventName -ne $eventType) {
                throw "SSE event name '$eventName' does not match data type '$eventType'"
            }
            $eventName = $null
            $eventTypes.Add($eventType) | Out-Null

            switch ($eventType) {
                "todo_progress" { $todoCount++ }
                "tool_start" { $startedTools.Add([string]$frame.toolName) | Out-Null }
                "tool_end" {
                    if ($frame.success -eq $true -and [long]$frame.durationMillis -ge 0) {
                        $successfulTools.Add([string]$frame.toolName) | Out-Null
                    }
                }
                "stage_output" {
                    foreach ($artifact in @($frame.artifactRefs)) {
                        if ($null -ne $artifact) { $artifactRefs.Add($artifact) }
                    }
                }
                "paused" {
                    $pausedCount++
                    if ([string]$frame.toolName -ne "deep_search" -or [long]$frame.estimatedMicrocredits -lt 200000) {
                        throw "unexpected approval request: tool=$($frame.toolName) estimate=$($frame.estimatedMicrocredits)"
                    }
                    $pending = Invoke-JsonApi GET "/api/v1/agent/runs/$($frame.runId)/approvals/pending" $null $accessToken
                    Assert-SuccessResponse $pending "pending approval query"
                    $matching = @($pending.data) | Where-Object { [string]$_.id -eq [string]$frame.approvalId }
                    if ($matching.Count -ne 1) { throw "paused approval is missing from owner-scoped pending query" }
                    $decision = Invoke-JsonApi POST "/api/v1/agent/approvals/$($frame.approvalId)/decision" @{ decision = "APPROVED" } $accessToken
                    Assert-SuccessResponse $decision "approval decision"
                    if ($decision.data -ne $true) { throw "approval decision was not accepted" }
                }
                "resume_start" { $resumeCount++ }
                "error" { throw "Agent failed: $($frame.code) $($frame.message)" }
                "complete" {
                    $completeEvent = $frame
                    break
                }
            }
            if ($null -ne $completeEvent) { break }
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

foreach ($requiredType in @("agent_start", "todo_progress", "paused", "resume_start", "tool_start", "tool_end", "stage_output", "complete")) {
    if (-not $eventTypes.Contains($requiredType)) {
        throw "missing canonical event '$requiredType' (types=$($eventTypes -join ','))"
    }
}
foreach ($requiredTool in @("deep_search", "report_tool")) {
    if (-not $startedTools.Contains($requiredTool) -or -not $successfulTools.Contains($requiredTool)) {
        throw "required tool did not finish successfully: $requiredTool (started=$($startedTools -join ','), successful=$($successfulTools -join ','))"
    }
}
if ($todoCount -lt 1 -or $pausedCount -lt 1 -or $resumeCount -lt 1) {
    throw "DEEP lifecycle incomplete: todo=$todoCount paused=$pausedCount resumed=$resumeCount"
}
if ($null -eq $completeEvent -or [string]::IsNullOrWhiteSpace([string]$completeEvent.summary)) {
    throw "DEEP run did not return a non-empty complete event"
}
if ($artifactRefs.Count -lt 1) { throw "report_tool returned no artifact references" }

$after = Invoke-JsonApi GET "/api/member/summary" $null $accessToken
Assert-SuccessResponse $after "quota summary after run"
$afterAvailable = [long]$after.data.availableQuota
$afterFrozen = [long]$after.data.frozenBalance
if ($afterFrozen -ne 0) { throw "quota reservation was not settled: frozen=$afterFrozen" }
if ($afterAvailable -ge $beforeAvailable) { throw "DEEP run did not consume quota" }

Write-Host "AGENT DEEP HITL SMOKE OK (frames=$frameCount, todos=$todoCount, approvals=$pausedCount, tools=$($successfulTools -join ','), artifacts=$($artifactRefs.Count), quota=$beforeAvailable->$afterAvailable)"
