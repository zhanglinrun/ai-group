# AI-Group 最小 Agent 评测集运行器
# 经 Gateway 跑 SSE 对话，断言最终结果命中关键词，并从 agent_db.dialogue_run 统计通过率/步数/token。
#
# 用法：
#   pwsh docs/evals/run-evals.ps1                # 跑全部用例
#   pwsh docs/evals/run-evals.ps1 -Limit 3       # 只跑前 3 条（快速冒烟）
param(
    [string]$Gateway = "http://127.0.0.1:8080",
    [int]$Limit = 0,
    [int]$TimeoutSec = 120
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$casesPath = Join-Path $PSScriptRoot "cases.jsonl"

function Invoke-Json($Method, $Path, $Body, $Token) {
    $headers = @{ "Content-Type" = "application/json" }
    if ($Token) { $headers["Authorization"] = "Bearer $Token" }
    return Invoke-RestMethod -Method $Method -Uri "$Gateway$Path" -Headers $headers -Body ($Body | ConvertTo-Json -Depth 6)
}

# 收集一次 SSE 对话的全部文本（agent_stream 增量 + 最终 result）
function Invoke-AgentSse($Token, $Query, $Mode, $SessionId, $RequestId) {
    $body = @{ query = $Query; sessionId = $SessionId; requestId = $RequestId; deepThink = 0; outputStyle = $Mode } | ConvertTo-Json
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

function Get-RunMetrics($SessionId) {
    $q = "SELECT llm_call_count, tool_call_count, total_tokens_total, status FROM agent_db.dialogue_run WHERE session_id = '$SessionId' ORDER BY id DESC LIMIT 1;"
    $out = $q | docker exec -i -e MYSQL_PWD=123456 ai-group-mysql mysql -uroot -N -B 2>$null
    if (-not $out) { return $null }
    $cols = ($out | Select-Object -Last 1).ToString().Split("`t")
    if ($cols.Length -lt 4) { return $null }
    return [pscustomobject]@{ llm = [int]$cols[0]; tool = [int]$cols[1]; tokens = [int]$cols[2]; status = [int]$cols[3] }
}

# 载入 .env 取内部账户口令基线（复用 smoke 注册流程）
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
foreach ($c in $cases) {
    $sid = "eval-$($c.id)-$(Get-Random)"
    $rid = "req-$(Get-Random)"
    $text = ""
    $ok = $false
    try {
        $text = Invoke-AgentSse $token $c.query $c.mode $sid $rid
        foreach ($kw in $c.expect) { if ($text -match [regex]::Escape($kw)) { $ok = $true; break } }
    } catch {
        Write-Host "  [$($c.id)] ERROR: $($_.Exception.Message)"
    }
    if ($ok) { $pass++ }
    Start-Sleep -Milliseconds 300
    $m = Get-RunMetrics $sid
    $results += [pscustomobject]@{
        id = $c.id; mode = $c.mode; pass = $ok
        llmCalls = if ($m) { $m.llm } else { $null }
        tokens = if ($m) { $m.tokens } else { $null }
    }
    $tag = if ($ok) { "PASS" } else { "FAIL" }
    Write-Host ("  [{0}] {1} mode={2} tokens={3}" -f $c.id, $tag, $c.mode, ($(if ($m) { $m.tokens } else { "-" })))
}

$total = $cases.Count
$withMetrics = $results | Where-Object { $_.tokens -ne $null }
$avgTokens = if ($withMetrics) { [math]::Round(($withMetrics | Measure-Object -Property tokens -Average).Average, 0) } else { 0 }
$avgLlm = if ($withMetrics) { [math]::Round(($withMetrics | Measure-Object -Property llmCalls -Average).Average, 2) } else { 0 }

Write-Host ""
Write-Host "==================== EVAL SUMMARY ===================="
Write-Host ("pass rate : {0}/{1} ({2}%)" -f $pass, $total, [math]::Round(100.0 * $pass / $total, 1))
Write-Host ("avg LLM calls (react ledger): {0}" -f $avgLlm)
Write-Host ("avg tokens    (react ledger): {0}" -f $avgTokens)
Write-Host "====================================================="

$report = [pscustomobject]@{
    timestamp = (Get-Date).ToString("s")
    total = $total; passed = $pass
    passRate = [math]::Round(100.0 * $pass / $total, 1)
    avgLlmCalls = $avgLlm; avgTokens = $avgTokens
    cases = $results
}
$reportPath = Join-Path $PSScriptRoot "last-report.json"
$report | ConvertTo-Json -Depth 6 | Set-Content -Path $reportPath -Encoding UTF8
Write-Host "report written: $reportPath"
if ($pass -lt $total) { exit 1 }
