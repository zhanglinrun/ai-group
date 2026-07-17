#Requires -Version 7.0
# Live unified Agent Loop evaluation through Gateway SSE. This runner keeps
# keyword/tool assertions, canonical lifecycle verification, ledger terminal
# state, and provider-reported token usage as separate metrics.
param(
    [string]$Gateway = "http://127.0.0.1:8080",
    [int]$Limit = 0,
    [int]$TimeoutSec = 180,
    [switch]$Judge,
    [string]$CasesFile = "cases.jsonl",
    [long]$BenchmarkQuota = 5000000,
    [string]$MysqlContainer = "ai-group-mysql",
    [string]$MysqlPassword = $(if ($env:MYSQL_ROOT_PASSWORD) { $env:MYSQL_ROOT_PASSWORD } else { "123456" }),
    [string]$ReportName = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$casesPath = if ([System.IO.Path]::IsPathRooted($CasesFile)) { $CasesFile } else { Join-Path $PSScriptRoot $CasesFile }
$reports = Join-Path $PSScriptRoot "reports"
if (-not $ReportName) {
    $ReportName = "agent-$([System.IO.Path]::GetFileNameWithoutExtension($casesPath))-benchmark.json"
}
$reportPath = Join-Path $reports $ReportName

function Get-PropertyValue($Object, [string]$Name, $Default = $null) {
    if ($null -ne $Object -and $Object.PSObject.Properties.Name -contains $Name) {
        return $Object.$Name
    }
    return $Default
}

function Escape-Sql([string]$Value) {
    return $Value.Replace("'", "''")
}

function Invoke-Mysql([string]$Sql) {
    $previousMysqlPassword = $env:MYSQL_PWD
    try {
        $env:MYSQL_PWD = $MysqlPassword
        $output = $Sql | docker exec -i -e MYSQL_PWD $MysqlContainer mysql -uroot -N -B
        $mysqlExitCode = $LASTEXITCODE
    } finally {
        if ($null -eq $previousMysqlPassword) {
            Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
        } else {
            $env:MYSQL_PWD = $previousMysqlPassword
        }
    }
    if ($mysqlExitCode -ne 0) {
        throw "mysql statement failed with exit code $mysqlExitCode"
    }
    return @($output)
}

function Invoke-Json($Method, $Path, $Body, $Token) {
    $headers = @{ "Content-Type" = "application/json" }
    if ($Token) { $headers["Authorization"] = "Bearer $Token" }
    $parameters = @{
        Method = $Method
        Uri = "$Gateway$Path"
        Headers = $headers
    }
    if ($null -ne $Body) {
        $parameters.Body = $Body | ConvertTo-Json -Depth 8 -Compress
    }
    return Invoke-RestMethod @parameters
}

function Invoke-AgentSse($Token, $Query, $Mode, $SessionId, $RequestId, $ExecutionMode) {
    $body = @{
        query = $Query
        sessionId = $SessionId
        requestId = $RequestId
        executionMode = $ExecutionMode
        outputStyle = $Mode
    } | ConvertTo-Json -Depth 8 -Compress
    $request = [System.Net.Http.HttpRequestMessage]::new(
        [System.Net.Http.HttpMethod]::Post,
        "$Gateway/web/api/v1/gpt/queryAgentStreamIncr"
    )
    $request.Headers.Add("Authorization", "Bearer $Token")
    $request.Headers.Add("Accept", "text/event-stream")
    $request.Content = [System.Net.Http.StringContent]::new(
        $body,
        [System.Text.Encoding]::UTF8,
        "application/json"
    )
    $client = [System.Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromSeconds($TimeoutSec)
    $streamAnswer = [System.Text.StringBuilder]::new()
    $terminalAnswer = ""
    $verificationStarted = [System.Collections.Generic.List[object]]::new()
    $verificationResults = [System.Collections.Generic.List[object]]::new()
    $completionBlocked = [System.Collections.Generic.List[object]]::new()
    $eventTypes = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::OrdinalIgnoreCase
    )
    $runFinished = $null
    $runFinishedBeforeResult = $false
    $terminalResultMap = $null
    $finalMetrics = $null
    $finalSeen = $false
    $response = $null
    $reader = $null
    try {
        $response = $client.Send($request, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead)
        if (-not $response.IsSuccessStatusCode) {
            $errorBody = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            throw "SSE request failed: HTTP $([int]$response.StatusCode) $errorBody"
        }
        $reader = [System.IO.StreamReader]::new($response.Content.ReadAsStream())
        while (-not $reader.EndOfStream) {
            $line = $reader.ReadLine()
            if (-not $line -or -not $line.StartsWith("data:")) { continue }
            $json = $line.Substring(5).Trim()
            if (-not $json -or $json -eq "[DONE]" -or $json.StartsWith("heartbeat")) { continue }
            try {
                $event = $json | ConvertFrom-Json
            } catch {
                continue
            }

            # Gateway wraps the canonical Agent event in eventData/resultMap;
            # direct Agent endpoints may return the AgentResponse itself.
            $payload = $event
            if ($event.resultMap -and $event.resultMap.eventData) {
                $payload = $event.resultMap.eventData
                if ($payload.resultMap -and (Get-PropertyValue $payload.resultMap "messageType")) {
                    $payload = $payload.resultMap
                }
            }
            $logicalType = Get-PropertyValue $payload "messageType" ""
            $innerResult = Get-PropertyValue $payload "result" ""
            $logicalResultMap = Get-PropertyValue $payload "resultMap"
            if ($logicalType) {
                [void]$eventTypes.Add([string]$logicalType)
            }

            if ($logicalType -eq "agent_stream" -and $innerResult) {
                [void]$streamAnswer.Append([string]$innerResult)
            }

            switch ($logicalType) {
                "verification_started" {
                    if ($logicalResultMap) { [void]$verificationStarted.Add($logicalResultMap) }
                }
                "verification_result" {
                    if ($logicalResultMap) { [void]$verificationResults.Add($logicalResultMap) }
                }
                "completion_blocked" {
                    if ($logicalResultMap) { [void]$completionBlocked.Add($logicalResultMap) }
                }
                "run_finished" {
                    $runFinished = $logicalResultMap
                }
            }
            if ($logicalType -eq "result") {
                $finalSeen = $true
                $runFinishedBeforeResult = $null -ne $runFinished
                $terminalResultMap = $logicalResultMap
                if ($innerResult) {
                    $terminalAnswer = [string]$innerResult
                } elseif ($logicalResultMap) {
                    $terminalAnswer = [string](Get-PropertyValue $logicalResultMap "taskSummary" "")
                } elseif ($event.response) {
                    $terminalAnswer = [string]$event.response
                }
                $finalMetrics = Get-PropertyValue $logicalResultMap "metrics"
                break
            }
        }
    } finally {
        if ($reader) { $reader.Dispose() }
        if ($response) { $response.Dispose() }
        $request.Dispose()
        $client.Dispose()
    }
    return [pscustomobject]@{
        text = if ([string]::IsNullOrWhiteSpace($terminalAnswer)) { $streamAnswer.ToString() } else { $terminalAnswer }
        eventTypes = @($eventTypes)
        verificationStarted = @($verificationStarted)
        verificationResults = @($verificationResults)
        completionBlocked = @($completionBlocked)
        runFinished = $runFinished
        runFinishedBeforeResult = $runFinishedBeforeResult
        terminalResultMap = $terminalResultMap
        finalMetrics = $finalMetrics
        finalSeen = $finalSeen
    }
}

function Get-RunMetrics([string]$SessionId) {
    $safeSessionId = Escape-Sql $SessionId
    $query = @"
SELECT r.id,
       IFNULL(r.run_uid,''),
       IFNULL(r.entry_agent,''),
       r.llm_call_count,
       r.tool_call_count,
       r.artifact_count,
       r.prompt_tokens_total,
       r.completion_tokens_total,
       r.total_tokens_total,
       r.status,
       IFNULL(r.duration_ms,0),
       IFNULL(r.error_code,''),
       IFNULL((SELECT GROUP_CONCAT(DISTINCT l.model_name ORDER BY l.model_name SEPARATOR ',')
               FROM agent_db.llm_invocation l WHERE l.run_id = r.id),''),
       (SELECT COUNT(*) FROM agent_db.llm_invocation l
        WHERE l.run_id = r.id AND l.total_tokens > 0)
FROM agent_db.dialogue_run r
WHERE r.session_id = '$safeSessionId'
ORDER BY r.id DESC
LIMIT 1;
"@
    $output = @(Invoke-Mysql $query)
    if ($output.Count -eq 0 -or -not $output[0]) { return $null }
    $columns = $output[0].ToString().Split("`t")
    if ($columns.Length -lt 14) { return $null }
    return [pscustomobject]@{
        id = [long]$columns[0]
        runUid = $columns[1]
        entryAgent = $columns[2]
        llm = [int]$columns[3]
        tool = [int]$columns[4]
        artifact = [int]$columns[5]
        promptTokens = [int]$columns[6]
        completionTokens = [int]$columns[7]
        tokens = [int]$columns[8]
        status = [int]$columns[9]
        durationMs = [long]$columns[10]
        errorCode = $columns[11]
        modelNames = $columns[12]
        providerUsageCalls = [int]$columns[13]
    }
}

function Wait-RunMetrics([string]$SessionId) {
    $metrics = $null
    for ($attempt = 0; $attempt -lt 24; $attempt++) {
        $metrics = Get-RunMetrics $SessionId
        if ($metrics -and $metrics.status -ne 0) { return $metrics }
        Start-Sleep -Milliseconds 250
    }
    return $metrics
}

function Get-RunToolNames($RunId) {
    if (-not $RunId) { return @() }
    $query = "SELECT DISTINCT tool_name FROM agent_db.tool_invocation WHERE run_id = $RunId ORDER BY tool_name;"
    return @(Invoke-Mysql $query | Where-Object { $_ -and $_.Trim() })
}

function Get-Percentile($Values, [int]$Percentile) {
    $sorted = @($Values | Where-Object { $null -ne $_ } | Sort-Object)
    if ($sorted.Count -eq 0) { return $null }
    $index = [Math]::Ceiling($Percentile / 100.0 * $sorted.Count) - 1
    return $sorted[[Math]::Max(0, [Math]::Min($index, $sorted.Count - 1))]
}

function Get-Average($Values, [int]$Digits = 1) {
    $items = @($Values | Where-Object { $null -ne $_ })
    if ($items.Count -eq 0) { return $null }
    return [Math]::Round(($items | Measure-Object -Average).Average, $Digits)
}

function Invoke-Judge($Token, $Query, $Answer, $Expect) {
    $prompt = "你是严格的评测员。根据问题和参考要点，对答复的正确性与相关性打分。" +
        "只输出 0 到 5 的一个整数。`n问题：$Query`n参考要点：$($Expect -join '、')" +
        "`n答复：$Answer`n分数："
    try {
        $judgeSse = Invoke-AgentSse $Token $prompt "chat" "eval-judge-$([guid]::NewGuid().ToString('N'))" `
            "req-judge-$([guid]::NewGuid().ToString('N'))" "STANDARD"
        $match = [regex]::Match($judgeSse.text, '(?<!\d)[0-5](?!\d)')
        if ($match.Success) { return [int]$match.Value }
    } catch {
        Write-Warning "LLM judge unavailable: $($_.Exception.Message)"
    }
    return $null
}

if (-not (Test-Path $casesPath)) {
    throw "cases file not found: $casesPath"
}
try {
    $health = Invoke-RestMethod -Method GET -Uri "$Gateway/actuator/health" -TimeoutSec 5
    if ($health.status -ne "UP") { throw "status=$($health.status)" }
} catch {
    throw "Gateway is not ready at $Gateway`: $($_.Exception.Message)"
}

$username = "eval_user_$([guid]::NewGuid().ToString('N').Substring(0, 10))"
$password = "Eval@123456"
Invoke-Json POST "/api/auth/register" @{
    username = $username
    password = $password
    email = "$username@test.local"
} $null | Out-Null
$login = Invoke-Json POST "/api/auth/login" @{ username = $username; password = $password } $null
$token = $login.data.accessToken
$userId = [long]$login.data.user.id
if (-not $token -or $userId -le 0) { throw "benchmark login failed" }

$quotaRows = @(Invoke-Mysql "SELECT COUNT(*) FROM member_db.quota_account WHERE user_id = $userId;")
if ($quotaRows.Count -ne 1 -or [int]$quotaRows[0] -ne 1) {
    throw "benchmark member quota account was not initialized for userId=$userId"
}
Invoke-Mysql "UPDATE member_db.quota_account SET free_quota_balance = $BenchmarkQuota, paid_quota_balance = 0, frozen_balance = 0 WHERE user_id = $userId;" | Out-Null
Write-Host "Benchmark user: $username (userId=$userId, isolated quota=$BenchmarkQuota)"

$cases = @(Get-Content -LiteralPath $casesPath | Where-Object { $_.Trim() } | ForEach-Object { $_ | ConvertFrom-Json })
if ($Limit -gt 0) { $cases = @($cases | Select-Object -First $Limit) }
if ($cases.Count -eq 0) { throw "no benchmark cases selected" }

$results = @()
$latencies = @()
$judgeScores = @()
foreach ($case in $cases) {
    $sessionId = "eval-$($case.id)-$([guid]::NewGuid().ToString('N').Substring(0, 8))"
    $requestId = "req-$([guid]::NewGuid().ToString('N'))"
    $executionMode = [string](Get-PropertyValue $case "executionMode" "STANDARD")
    $executionMode = $executionMode.Trim().ToUpperInvariant()
    if (@("AUTO", "STANDARD", "DEEP") -notcontains $executionMode) {
        throw "case '$($case.id)' has invalid executionMode '$executionMode'"
    }
    $suite = [string](Get-PropertyValue $case "suite" $(if ($casesPath -like '*tools*') { "tool" } else { "deterministic" }))
    $text = ""
    $sse = $null
    $assertionPassed = $false
    $failReason = $null
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $sse = Invoke-AgentSse $token $case.query $case.mode $sessionId $requestId $executionMode
        $text = $sse.text
        $expected = @(Get-PropertyValue $case "expect" @())
        $expectedAll = @(Get-PropertyValue $case "expectAll" @())
        if ($expectedAll.Count -gt 0) {
            $missingKeywords = @($expectedAll | Where-Object {
                    $text -notmatch [regex]::Escape([string]$_)
                })
            $assertionPassed = $missingKeywords.Count -eq 0
            if (-not $assertionPassed) { $failReason = "keyword-all-miss" }
        } elseif ($expected.Count -gt 0) {
            foreach ($keyword in $expected) {
                if ($text -match [regex]::Escape([string]$keyword)) {
                    $assertionPassed = $true
                    break
                }
            }
            if (-not $assertionPassed) { $failReason = "keyword-miss" }
        } else {
            $assertionPassed = -not [string]::IsNullOrWhiteSpace($text)
            if (-not $assertionPassed) { $failReason = "empty-answer" }
        }
    } catch {
        $failReason = "transport-error"
        Write-Host "  [$($case.id)] ERROR: $($_.Exception.Message)"
    }
    $watch.Stop()
    $latencyMs = [long]$watch.ElapsedMilliseconds
    $latencies += $latencyMs

    $requiredEvents = @("run_started", "verification_started", "verification_result", "run_finished", "result")
    $missingCanonicalEvents = if ($sse) {
        @($requiredEvents | Where-Object { $sse.eventTypes -notcontains $_ })
    } else {
        $requiredEvents
    }
    $canonicalLifecyclePassed = [bool]($sse -and $sse.finalSeen -and
        $sse.runFinishedBeforeResult -and $missingCanonicalEvents.Count -eq 0)
    if (-not $canonicalLifecyclePassed) {
        $assertionPassed = $false
        if (-not $failReason) { $failReason = "canonical-lifecycle-miss" }
    }

    $runMetrics = Wait-RunMetrics $sessionId
    $toolNames = if ($runMetrics) { @(Get-RunToolNames $runMetrics.id) } else { @() }
    $expectTools = @(Get-PropertyValue $case "expectTools" @())
    if ($expectTools.Count -gt 0) {
        $toolHit = $false
        foreach ($tool in $expectTools) {
            if ($toolNames -contains [string]$tool) { $toolHit = $true; break }
        }
        if (-not $toolHit) {
            $assertionPassed = $false
            if (-not $failReason) { $failReason = "tool-trajectory-miss" }
        }
    }
    $expectToolsAll = @(Get-PropertyValue $case "expectToolsAll" @())
    if ($expectToolsAll.Count -gt 0) {
        $missingTools = @($expectToolsAll | Where-Object { $toolNames -notcontains [string]$_ })
        if ($missingTools.Count -gt 0) {
            $assertionPassed = $false
            if (-not $failReason) { $failReason = "tool-trajectory-miss" }
        }
    }
    $minimumToolCalls = [int](Get-PropertyValue $case "minToolCalls" 0)
    if ($minimumToolCalls -gt 0 -and (-not $runMetrics -or $runMetrics.tool -lt $minimumToolCalls)) {
        $assertionPassed = $false
        if (-not $failReason) { $failReason = "tool-count-miss" }
    }

    $ledgerSucceeded = [bool]($runMetrics -and $runMetrics.status -eq 1)
    if (-not $runMetrics -and -not $failReason) { $failReason = "ledger-missing" }
    if ($runMetrics -and $runMetrics.status -ne 1 -and -not $failReason) { $failReason = "ledger-non-success" }

    $verificationStartedFrames = if ($sse) { @($sse.verificationStarted) } else { @() }
    $verificationResultFrames = if ($sse) { @($sse.verificationResults) } else { @() }
    $completionBlockedFrames = if ($sse) { @($sse.completionBlocked) } else { @() }
    $finalMetrics = if ($sse) { $sse.finalMetrics } else { $null }
    $completionAttempts = [int](Get-PropertyValue $finalMetrics "completionAttempts" $verificationStartedFrames.Count)
    $completionBlockedCount = [int](Get-PropertyValue $finalMetrics "completionBlocked" $completionBlockedFrames.Count)
    $finalVerifierCount = [int](Get-PropertyValue $finalMetrics "finalVerifierCount" 0)
    $lastVerification = if ($verificationResultFrames.Count -gt 0) { $verificationResultFrames[-1] } else { $null }
    $verificationAccepted = if ($lastVerification) {
        [bool](Get-PropertyValue $lastVerification "accepted" $false)
    } else { $null }
    $verifierExecuted = if ($lastVerification) {
        [bool](Get-PropertyValue $lastVerification "verifierExecuted" $false)
    } else { $null }
    $terminalResultMap = if ($sse) { $sse.terminalResultMap } else { $null }
    $runFinished = if ($sse) { $sse.runFinished } else { $null }
    $terminalStatus = [string](Get-PropertyValue $terminalResultMap "runStatus" "")
    if (-not $terminalStatus) { $terminalStatus = [string](Get-PropertyValue $terminalResultMap "status" "") }
    if (-not $terminalStatus) { $terminalStatus = [string](Get-PropertyValue $runFinished "runStatus" "") }
    if (-not $terminalStatus) { $terminalStatus = [string](Get-PropertyValue $runFinished "status" "") }
    $completionGateValue = Get-PropertyValue $terminalResultMap "completionGatePassed"
    if ($null -eq $completionGateValue) {
        $completionGateValue = Get-PropertyValue $runFinished "completionGatePassed"
    }
    $completionGatePassed = if ($null -eq $completionGateValue) { $null } else { [bool]$completionGateValue }
    $stopReason = [string](Get-PropertyValue $terminalResultMap "stopReason" "")
    if (-not $stopReason) { $stopReason = [string](Get-PropertyValue $runFinished "stopReason" "") }
    if (-not $stopReason -and $runMetrics) { $stopReason = [string]$runMetrics.errorCode }

    if ($completionGatePassed -ne $true -and -not $failReason) { $failReason = "completion-gate-failed" }
    if ($terminalStatus -ne "SUCCESS" -and -not $failReason) { $failReason = "terminal-status-failed" }
    if ($stopReason -ne "COMPLETED" -and -not $failReason) { $failReason = "stop-reason-non-completed" }
    $endToEndPassed = [bool]($assertionPassed -and $ledgerSucceeded -and
        $canonicalLifecyclePassed -and $completionGatePassed -eq $true -and
        $terminalStatus -eq "SUCCESS" -and $stopReason -eq "COMPLETED")

    $judgeScore = $null
    if ($Judge) {
        $judgeScore = Invoke-Judge $token $case.query $text @(Get-PropertyValue $case "expect" @())
        if ($null -ne $judgeScore) { $judgeScores += $judgeScore }
    }

    $results += [pscustomobject]@{
        id = $case.id
        suite = $suite
        mode = $case.mode
        executionMode = $executionMode
        query = $case.query
        expected = @(Get-PropertyValue $case "expect" @())
        expectedAll = @(Get-PropertyValue $case "expectAll" @())
        answer = $text
        finalFrameSeen = [bool]($sse -and $sse.finalSeen)
        canonicalLifecyclePassed = $canonicalLifecyclePassed
        missingCanonicalEvents = $missingCanonicalEvents
        eventTypes = if ($sse) { @($sse.eventTypes) } else { @() }
        assertionPassed = $assertionPassed
        ledgerSucceeded = $ledgerSucceeded
        endToEndPassed = $endToEndPassed
        pass = $endToEndPassed
        failReason = $failReason
        runId = if ($runMetrics) { $runMetrics.runUid } else { $null }
        entryAgent = if ($runMetrics) { $runMetrics.entryAgent } else { $null }
        modelNames = if ($runMetrics) { $runMetrics.modelNames } else { Get-PropertyValue $finalMetrics "modelName" }
        llmCalls = if ($runMetrics) { $runMetrics.llm } else { $null }
        toolCalls = if ($runMetrics) { $runMetrics.tool } else { $null }
        artifactCount = if ($runMetrics) { $runMetrics.artifact } else { $null }
        promptTokens = if ($runMetrics -and $runMetrics.providerUsageCalls -gt 0) { $runMetrics.promptTokens } else { $null }
        completionTokens = if ($runMetrics -and $runMetrics.providerUsageCalls -gt 0) { $runMetrics.completionTokens } else { $null }
        providerReportedTokens = if ($runMetrics -and $runMetrics.providerUsageCalls -gt 0) { $runMetrics.tokens } else { $null }
        providerUsageCalls = if ($runMetrics) { $runMetrics.providerUsageCalls } else { 0 }
        ledgerStatus = if ($runMetrics) { $runMetrics.status } else { $null }
        ledgerErrorCode = if ($runMetrics) { $runMetrics.errorCode } else { $null }
        latencyMs = $latencyMs
        ledgerDurationMs = if ($runMetrics) { $runMetrics.durationMs } else { $null }
        tools = $toolNames
        terminalStatus = $terminalStatus
        completionGatePassed = $completionGatePassed
        stopReason = $stopReason
        verificationStartedCount = $verificationStartedFrames.Count
        verificationResultCount = $verificationResultFrames.Count
        verificationAccepted = $verificationAccepted
        verifierExecuted = $verifierExecuted
        completionAttempts = $completionAttempts
        completionBlocked = $completionBlockedCount
        finalVerifierCount = $finalVerifierCount
        verificationFailureReasons = if ($lastVerification) { @(Get-PropertyValue $lastVerification "failureReasons" @()) } else { @() }
        requiredActions = if ($lastVerification) { @(Get-PropertyValue $lastVerification "requiredActions" @()) } else { @() }
        judgeScore = $judgeScore
    }
    $tag = if ($endToEndPassed) { "PASS" } else { "FAIL" }
    $tokenText = if ($runMetrics -and $runMetrics.providerUsageCalls -gt 0) { $runMetrics.tokens } else { "n/a" }
    Write-Host ("  [{0}] {1} suite={2} status={3} tools={4} tokens={5} latencyMs={6}" -f `
            $case.id, $tag, $suite, $(if ($runMetrics) { $runMetrics.status } else { "-" }),
            $toolNames.Count, $tokenText, $latencyMs)
}

$total = $results.Count
$assertionPassed = @($results | Where-Object assertionPassed).Count
$endToEndPassed = @($results | Where-Object endToEndPassed).Count
$withLedger = @($results | Where-Object { $null -ne $_.ledgerStatus })
$ledgerSuccesses = @($withLedger | Where-Object { $_.ledgerStatus -eq 1 }).Count
$providerUsageRuns = @($results | Where-Object { $null -ne $_.providerReportedTokens })
$toolRuns = @($results | Where-Object { $_.toolCalls -gt 0 }).Count
$canonicalLifecyclePasses = @($results | Where-Object canonicalLifecyclePassed).Count
$verificationRuns = @($results | Where-Object { $_.verificationResultCount -gt 0 })
$verificationFramesTotal = ($verificationRuns | Measure-Object -Property verificationResultCount -Sum).Sum
$acceptedVerifications = @($verificationRuns | Where-Object { $_.verificationAccepted -eq $true }).Count
$completionGatePasses = @($results | Where-Object { $_.completionGatePassed -eq $true }).Count
$completionBlockedRuns = @($results | Where-Object { $_.completionBlocked -gt 0 })
$completionBlockedTotal = ($completionBlockedRuns | Measure-Object -Property completionBlocked -Sum).Sum
$verifierExecutedRuns = @($results | Where-Object { $_.verifierExecuted -eq $true }).Count
$failureBreakdown = @($results | Where-Object { -not $_.endToEndPassed -and $_.failReason } |
    Group-Object failReason | ForEach-Object { [ordered]@{ reason = $_.Name; count = $_.Count } })
$stopReasonBreakdown = @($results | Where-Object { $_.stopReason } |
    Group-Object stopReason | ForEach-Object { [ordered]@{ reason = $_.Name; count = $_.Count } })
$modelNames = @($results.modelNames | Where-Object { $_ } | ForEach-Object { $_ -split ',' } |
    ForEach-Object { $_.Trim() } | Where-Object { $_ } | Sort-Object -Unique)

$report = [ordered]@{
    schemaVersion = 3
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    benchmarkType = "live-agent-loop-gateway-sse"
    environment = [ordered]@{
        os = [System.Environment]::OSVersion.VersionString
        powershell = $PSVersionTable.PSVersion.ToString()
        processors = [System.Environment]::ProcessorCount
        gateway = $Gateway
        modelNames = $modelNames
    }
    dataset = [ordered]@{
        casesFile = [System.IO.Path]::GetFileName($casesPath)
        totalCases = $total
        deterministicCases = @($results | Where-Object { $_.suite -eq "deterministic" }).Count
        toolCases = @($results | Where-Object { $_.suite -eq "tool" }).Count
        deepAgentLoopCases = @($results | Where-Object { $_.suite -eq "deep-agent-loop" }).Count
        standardModeCases = @($results | Where-Object { $_.executionMode -eq "STANDARD" }).Count
        autoModeCases = @($results | Where-Object { $_.executionMode -eq "AUTO" }).Count
        deepModeCases = @($results | Where-Object { $_.executionMode -eq "DEEP" }).Count
        llmJudgeEnabled = [bool]$Judge
        benchmarkQuota = $BenchmarkQuota
    }
    results = [ordered]@{
        keywordAndToolAssertionPassRatePct = [Math]::Round(100.0 * $assertionPassed / $total, 1)
        assertionPassed = $assertionPassed
        endToEndTaskSuccessRatePct = [Math]::Round(100.0 * $endToEndPassed / $total, 1)
        endToEndPassed = $endToEndPassed
        ledgerTerminalSuccessRatePct = if ($withLedger.Count -gt 0) { [Math]::Round(100.0 * $ledgerSuccesses / $withLedger.Count, 1) } else { $null }
        ledgerSuccessfulRuns = $ledgerSuccesses
        ledgerObservedRuns = $withLedger.Count
        averageLlmCalls = Get-Average @($withLedger.llmCalls) 2
        averageProviderReportedTokens = Get-Average @($providerUsageRuns.providerReportedTokens) 0
        providerUsageObservedRuns = $providerUsageRuns.Count
        latencyP50Ms = Get-Percentile $latencies 50
        latencyP95Ms = Get-Percentile $latencies 95
        toolUsedRuns = $toolRuns
        canonicalLifecyclePassRatePct = [Math]::Round(100.0 * $canonicalLifecyclePasses / $total, 1)
        canonicalLifecyclePassedRuns = $canonicalLifecyclePasses
        verificationRuns = $verificationRuns.Count
        verificationFrames = if ($null -eq $verificationFramesTotal) { 0 } else { [int]$verificationFramesTotal }
        verificationFinalAcceptanceRatePct = if ($verificationRuns.Count -gt 0) { [Math]::Round(100.0 * $acceptedVerifications / $verificationRuns.Count, 1) } else { $null }
        completionGatePassRatePct = [Math]::Round(100.0 * $completionGatePasses / $total, 1)
        completionGatePassedRuns = $completionGatePasses
        completionBlockedRuns = $completionBlockedRuns.Count
        completionBlockedFrames = if ($null -eq $completionBlockedTotal) { 0 } else { [int]$completionBlockedTotal }
        averageCompletionAttempts = Get-Average @($results.completionAttempts) 2
        finalVerifierExecutedRuns = $verifierExecutedRuns
        averageFinalVerifierCount = Get-Average @($results.finalVerifierCount) 2
        averageLlmJudgeScore = Get-Average $judgeScores 2
        failureBreakdown = $failureBreakdown
        stopReasonBreakdown = $stopReasonBreakdown
    }
    metricSemantics = [ordered]@{
        assertions = "Declared any/all keyword policy plus tool trajectory/count checks; this is a deterministic correctness proxy, not human semantic grading."
        endToEndSuccess = "Assertions pass, the canonical Agent Loop lifecycle is complete, CompletionGate passes with stopReason=COMPLETED, and dialogue_run reaches STATUS_SUCCESS."
        verification = "verification_started/result are CompletionGate lifecycle events. verifierExecuted distinguishes an independent final verifier from deterministic gate checks."
        completionBlocked = "Number of same-loop completion rejections that returned required actions to the model before another turn."
        stopReason = "Typed terminal reason emitted by run_finished/result; successful runs must end with COMPLETED."
        tokens = "Provider-reported usage persisted from Spring AI response metadata; runs without provider usage are excluded, never replaced by TokenCounter.countText()."
        latency = "Client wall-clock time from Gateway request start through the terminal SSE frame."
    }
    cases = $results
}
New-Item -ItemType Directory -Path $reports -Force | Out-Null
$report | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $reportPath -Encoding utf8

Write-Host ""
Write-Host "==================== EVAL SUMMARY ===================="
Write-Host ("assertion pass rate       : {0}/{1} ({2}%)" -f $assertionPassed, $total, $report.results.keywordAndToolAssertionPassRatePct)
Write-Host ("end-to-end success        : {0}/{1} ({2}%)" -f $endToEndPassed, $total, $report.results.endToEndTaskSuccessRatePct)
Write-Host ("ledger terminal success   : {0}/{1} ({2}%)" -f $ledgerSuccesses, $withLedger.Count, $report.results.ledgerTerminalSuccessRatePct)
Write-Host ("provider token samples     : {0}/{1}; average={2}" -f $providerUsageRuns.Count, $total, $report.results.averageProviderReportedTokens)
Write-Host ("latency p50 / p95 (ms)     : {0} / {1}" -f $report.results.latencyP50Ms, $report.results.latencyP95Ms)
Write-Host ("canonical lifecycle       : {0}/{1} ({2}%)" -f $canonicalLifecyclePasses, $total, $report.results.canonicalLifecyclePassRatePct)
Write-Host ("completion gate passed    : {0}/{1} ({2}%); blocked runs={3}" -f `
        $completionGatePasses, $total, $report.results.completionGatePassRatePct, $completionBlockedRuns.Count)
Write-Host "report written: $reportPath"
Write-Host "======================================================"

if ($endToEndPassed -lt $total) { exit 1 }
