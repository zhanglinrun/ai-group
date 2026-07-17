param(
    [string]$ReportName = "memory-skills-benchmark.json"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$agentRoot = Join-Path $root "ai-agent"
$app = Join-Path $agentRoot "ai-agent-app"
$reports = Join-Path $PSScriptRoot "reports"
$targetReport = Join-Path $app "target/agent-harness-evals/memory-skills-benchmark.json"
$finalReport = Join-Path $reports $ReportName

New-Item -ItemType Directory -Path $reports -Force | Out-Null

Push-Location $agentRoot
try {
    mvn test `
        "-Dtest=com.linrun.agent.test.eval.HarnessMemoryOfflineBenchmarkTest" `
        "-Dsurefire.failIfNoSpecifiedTests=false"
    if ($LASTEXITCODE -ne 0) {
        throw "offline benchmark test failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}

if (-not (Test-Path $targetReport)) {
    throw "offline benchmark did not produce $targetReport"
}

Copy-Item -LiteralPath $targetReport -Destination $finalReport -Force
$report = Get-Content -LiteralPath $finalReport -Raw | ConvertFrom-Json

Write-Host "Agent Harness memory/skills benchmark report: $finalReport"
Write-Host ("Memory recall: {0}% -> {1}%" -f `
        $report.memory.hardTruncationBaseline.averageKeyFactRecallRatePct,
        $report.memory.rollingSummaryStrategy.averageKeyFactRecallRatePct)
Write-Host ("Memory estimated input tokens: {0} -> {1}" -f `
        $report.memory.hardTruncationBaseline.averageEstimatedInputTokens,
        $report.memory.rollingSummaryStrategy.averageEstimatedInputTokens)
Write-Host ("Skills load checks: {0}/{1}; estimated prompt reduction: {2}%" -f `
        $report.skills.loadChecksPassed,
        $report.skills.registeredSkills,
        $report.skills.estimatedInputTokenReductionPct)
