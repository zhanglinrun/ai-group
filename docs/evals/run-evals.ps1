# AI-Group 最小 Agent 评测集运行器
# 经 Gateway 跑 SSE 对话，断言最终结果命中关键词 / 工具轨迹，并从 agent_db.dialogue_run 统计
# 通过率、任务成功率(账本终态)、平均步数/token、p50/p95 时延、工具使用率与失败原因分类。
#
# 用法：
#   pwsh docs/evals/run-evals.ps1                # 跑全部用例
#   pwsh docs/evals/run-evals.ps1 -Limit 3       # 只跑前 3 条（快速冒烟）
#   pwsh docs/evals/run-evals.ps1 -Judge         # 额外启用 LLM-as-judge 打分（需可用 LLM）
param(
    [string]$Gateway = "http://127.0.0.1:8080",
    [int]$Limit = 0,
    [int]$TimeoutSec = 120,
    [switch]$Judge,
    # 用例文件（默认确定性核心集；跑工具轨迹集：-CasesFile cases-tools.jsonl）
    [string]$CasesFile = "cases.jsonl"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$casesPath = if ([System.IO.Path]::IsPathRooted($CasesFile)) { $CasesFile } else { Join-Path $PSScriptRoot $CasesFile }

function Invoke-Json($Method, $Path, $Body, $Token) {
    $headers = @{ "Content-Type" = "application/json" }
    if ($Token) { $headers["Authorization"] = "Bearer $Token" }
    return Invoke-RestMethod -Method $Method -Uri "$Gateway$Path" -Headers $headers -Body ($Body | ConvertTo-Json -Depth 6)
}

# 收集一次 SSE 对话的全部文本（agent_stream 增量 + 最终 result）
function Invoke-AgentSse($Token, $Query, $Mode, $SessionId, $RequestId, $DeepThink) {
    $body = @{ query = $Query; sessionId = $SessionId; requestId = $RequestId; deepThink = $DeepThink; outputStyle = $Mode } | ConvertTo-Json
    $req = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, "$Gateway/web/api/v1/gpt/queryAgentStreamIncr")
    $req.Headers.Add("Authorization", "Bearer $Token")
    $req.Headers.Add("Accept", "text/event-stream")
    $req.Content = [System.Net.Http.StringContent]::new($body, [System.Text.Encoding]::UTF8, "application/json")
    $client = [System.Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromSeconds($TimeoutSec)
    $sb = [System.Text.StringBuilder]::new()
    try {
        $resp = $client.Send($req, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead)
        $reader = [System.IO.StreamReader]::new($resp.Content.ReadAsStream())
        $deadline = (Get-Date).AddSeconds($TimeoutSec)
        while (-not $reader.EndOfStream -and (Get-Date) -lt $deadline) {
            $line = $reader.ReadLine()
            if (-not $line -or -not $line.StartsWith("data:")) { continue }
            $json = $line.Substring(5)
            try {
                $evt = $json | ConvertFrom-Json
                if ($evt.result) { [void]$sb.Append([string]$evt.result) }
                if ($evt.toolThought) { [void]$sb.Append([string]$evt.toolThought) }
            } catch { }
        }
        $reader.Close()
    } finally { $client.Dispose() }
    return $sb.ToString()
}

# 读取该 session 最近一次 run 的账本指标（步数/工具数/token/终态/耗时）
function Get-RunMetrics($SessionId) {
    $q = "SELECT llm_call_count, tool_call_count, total_tokens_total, status, IFNULL(duration_ms,0) FROM agent_db.dialogue_run WHERE session_id = '$SessionId' ORDER BY id DESC LIMIT 1;"
    $out = $q | docker exec -i -e MYSQL_PWD=123456 ai-group-mysql mysql -uroot -N -B 2>$null
    if (-not $out) { return $null }
    $cols = ($out | Select-Object -Last 1).ToString().Split("`t")
    if ($cols.Length -lt 5) { return $null }
    return [pscustomobject]@{ llm = [int]$cols[0]; tool = [int]$cols[1]; tokens = [int]$cols[2]; status = [int]$cols[3]; durationMs = [int]$cols[4] }
}

# 读取该 session 最近一次 run 使用过的工具名集合（工具轨迹断言）
function Get-RunToolNames($SessionId) {
    $q = "SELECT DISTINCT t.tool_name FROM agent_db.tool_invocation t JOIN agent_db.dialogue_run r ON t.run_id = r.id WHERE r.session_id = '$SessionId' ORDER BY r.id DESC;"
    $out = $q | docker exec -i -e MYSQL_PWD=123456 ai-group-mysql mysql -uroot -N -B 2>$null
    if (-not $out) { return @() }
    return @($out | Where-Object { $_.Trim() })
}

function Get-Percentile($values, $p) {
    $sorted = @($values | Where-Object { $_ -ne $null } | Sort-Object)
    if ($sorted.Count -eq 0) { return 0 }
    $rank = [math]::Ceiling($p / 100.0 * $sorted.Count) - 1
    if ($rank -lt 0) { $rank = 0 }
    if ($rank -ge $sorted.Count) { $rank = $sorted.Count - 1 }
    return $sorted[$rank]
}

# LLM-as-judge：让模型对答复相对预期做 0-5 忠实度/相关性打分（可选）
function Invoke-Judge($Token, $Query, $Answer, $Expect) {
    $prompt = "你是严格的评测员。请根据【问题】和【参考要点】，对【答复】的正确性与相关性打分，" +
        "只输出 0 到 5 的一个整数（5=完全正确且相关，0=完全错误或无关）。`n" +
        "【问题】$Query`n【参考要点】$($Expect -join '、')`n【答复】$Answer`n只输出一个整数分数："
    $sid = "eval-judge-$(Get-Random)"
    $rid = "req-judge-$(Get-Random)"
    try {
        $text = Invoke-AgentSse $Token $prompt "chat" $sid $rid 0
        $m = [regex]::Match($text, '[0-5]')
        if ($m.Success) { return [int]$m.Value }
    } catch { }
    return $null
}

$user = "eval_user_$(Get-Random -Maximum 99999)"
$pass = "Eval@123456"
Invoke-Json POST "/api/auth/register" @{ username = $user; password = $pass; email = "$user@test.local" } $null | Out-Null
$login = Invoke-Json POST "/api/auth/login" @{ username = $user; password = $pass } $null
$token = $login.data.accessToken
if (-not $token) { throw "login failed" }
Write-Host "eval user: $user"

$cases = Get-Content $casesPath | Where-Object { $_.Trim() } | ForEach-Object { $_ | ConvertFrom-Json }
if ($Limit -gt 0) { $cases = $cases | Select-Object -First $Limit }

$results = @()
$pass = 0
$latencies = @()
$judgeScores = @()
foreach ($c in $cases) {
    $sid = "eval-$($c.id)-$(Get-Random)"
    $rid = "req-$(Get-Random)"
    $deepThink = if ($c.PSObject.Properties.Name -contains 'deepThink') { [int]$c.deepThink } else { 0 }
    $text = ""
    $ok = $false
    $failReason = $null
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $text = Invoke-AgentSse $token $c.query $c.mode $sid $rid $deepThink
        if ($c.expect) {
            foreach ($kw in $c.expect) { if ($text -match [regex]::Escape($kw)) { $ok = $true; break } }
            if (-not $ok) { $failReason = "keyword-miss" }
        } else {
            $ok = -not [string]::IsNullOrWhiteSpace($text)
            if (-not $ok) { $failReason = "empty-answer" }
        }
    } catch {
        $failReason = "error"
        Write-Host "  [$($c.id)] ERROR: $($_.Exception.Message)"
    }
    $sw.Stop()
    $latencyMs = [int]$sw.ElapsedMilliseconds
    $latencies += $latencyMs

    Start-Sleep -Milliseconds 300
    $m = Get-RunMetrics $sid

    # 工具轨迹断言：用例声明 expectTools / minToolCalls 时校验实际工具使用
    $toolNames = @()
    if (($c.PSObject.Properties.Name -contains 'expectTools') -or ($c.PSObject.Properties.Name -contains 'minToolCalls')) {
        $toolNames = Get-RunToolNames $sid
        if ($c.expectTools) {
            $hit = $false
            foreach ($t in $c.expectTools) { if ($toolNames -contains $t) { $hit = $true; break } }
            if (-not $hit) { $ok = $false; if (-not $failReason) { $failReason = "tool-trajectory-miss" } }
        }
        if ($c.minToolCalls -and $m -and $m.tool -lt [int]$c.minToolCalls) {
            $ok = $false; if (-not $failReason) { $failReason = "tool-count-miss" }
        }
    }

    $judge = $null
    if ($Judge -and $c.mode -ne 'chat') {
        $judge = Invoke-Judge $token $c.query $text $c.expect
        if ($judge -ne $null) { $judgeScores += $judge }
    }

    if ($ok) { $pass++ }
    $results += [pscustomobject]@{
        id = $c.id; mode = $c.mode; pass = $ok; failReason = $failReason
        llmCalls = if ($m) { $m.llm } else { $null }
        toolCalls = if ($m) { $m.tool } else { $null }
        tokens = if ($m) { $m.tokens } else { $null }
        status = if ($m) { $m.status } else { $null }
        latencyMs = $latencyMs
        tools = $toolNames
        judge = $judge
    }
    $tag = if ($ok) { "PASS" } else { "FAIL" }
    Write-Host ("  [{0}] {1} mode={2} tokens={3} latencyMs={4}" -f $c.id, $tag, $c.mode, ($(if ($m) { $m.tokens } else { "-" })), $latencyMs)
}

$total = $cases.Count
$withMetrics = $results | Where-Object { $_.tokens -ne $null }
$avgTokens = if ($withMetrics) { [math]::Round(($withMetrics | Measure-Object -Property tokens -Average).Average, 0) } else { 0 }
$avgLlm = if ($withMetrics) { [math]::Round(($withMetrics | Measure-Object -Property llmCalls -Average).Average, 2) } else { 0 }
# 任务成功率：账本终态为成功(1) 的占比
$successRuns = @($withMetrics | Where-Object { $_.status -eq 1 }).Count
$taskSuccessRate = if ($withMetrics.Count -gt 0) { [math]::Round(100.0 * $successRuns / $withMetrics.Count, 1) } else { 0 }
$p50 = Get-Percentile $latencies 50
$p95 = Get-Percentile $latencies 95
$toolUsedRuns = @($withMetrics | Where-Object { $_.toolCalls -gt 0 }).Count
$avgJudge = if ($judgeScores.Count -gt 0) { [math]::Round(($judgeScores | Measure-Object -Average).Average, 2) } else { $null }
$failBreakdown = $results | Where-Object { -not $_.pass -and $_.failReason } | Group-Object failReason | ForEach-Object { "$($_.Name)=$($_.Count)" }

Write-Host ""
Write-Host "==================== EVAL SUMMARY ===================="
Write-Host ("keyword pass rate : {0}/{1} ({2}%)" -f $pass, $total, [math]::Round(100.0 * $pass / $total, 1))
Write-Host ("task success rate (ledger): {0}% ({1}/{2})" -f $taskSuccessRate, $successRuns, $withMetrics.Count)
Write-Host ("avg LLM calls / tokens    : {0} / {1}" -f $avgLlm, $avgTokens)
Write-Host ("latency p50 / p95 (ms)    : {0} / {1}" -f $p50, $p95)
Write-Host ("tool-used runs            : {0}/{1}" -f $toolUsedRuns, $withMetrics.Count)
if ($avgJudge -ne $null) { Write-Host ("avg LLM-as-judge (0-5)    : {0}" -f $avgJudge) }
if ($failBreakdown) { Write-Host ("failure breakdown         : {0}" -f ($failBreakdown -join ', ')) }
Write-Host "====================================================="

$report = [pscustomobject]@{
    timestamp = (Get-Date).ToString("s")
    total = $total; passed = $pass
    passRate = [math]::Round(100.0 * $pass / $total, 1)
    taskSuccessRate = $taskSuccessRate
    avgLlmCalls = $avgLlm; avgTokens = $avgTokens
    latencyP50Ms = $p50; latencyP95Ms = $p95
    toolUsedRuns = $toolUsedRuns
    avgJudgeScore = $avgJudge
    failureBreakdown = ($failBreakdown -join ', ')
    cases = $results
}
$reportPath = Join-Path $PSScriptRoot "last-report.json"
$report | ConvertTo-Json -Depth 6 | Set-Content -Path $reportPath -Encoding UTF8
Write-Host "report written: $reportPath"
if ($pass -lt $total) { exit 1 }
