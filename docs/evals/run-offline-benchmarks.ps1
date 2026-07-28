param(
    [string]$ReportName = "memory-skills-benchmark.json",
    [string]$HarnessReportName = "offline-harness-report.json"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$agentRoot = Join-Path $root "ai-agent"
$app = Join-Path $agentRoot "ai-agent-app"
$reports = Join-Path $PSScriptRoot "reports"
$targetReport = Join-Path $app "target/agent-harness-evals/memory-skills-benchmark.json"
$targetHarnessReport = Join-Path $app "target/agent-harness-evals/offline-harness-report.json"
$finalReport = Join-Path $reports $ReportName
$finalHarnessReport = Join-Path $reports $HarnessReportName
$gitHead = (& git -C $root rev-parse HEAD).Trim()
$gitStatus = @(& git -C $root status --porcelain)
$runnerSha256 = (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash.ToLowerInvariant()
$memoryTestSha256 = (Get-FileHash -LiteralPath (Join-Path $app "src/test/java/com/linrun/agent/test/eval/HarnessMemoryOfflineBenchmarkTest.java") -Algorithm SHA256).Hash.ToLowerInvariant()
$harnessTestSha256 = (Get-FileHash -LiteralPath (Join-Path $app "src/test/java/com/linrun/agent/test/eval/AgentHarnessOfflineEvalTest.java") -Algorithm SHA256).Hash.ToLowerInvariant()

function Add-ReportProvenance([string]$Path, [string]$DatasetSha256) {
    $content = Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
    $content | Add-Member -NotePropertyName generatedAt -NotePropertyValue ((Get-Date).ToUniversalTime().ToString("o")) -Force
    $content | Add-Member -NotePropertyName provenance -NotePropertyValue ([ordered]@{
            gitHead = $gitHead
            gitDirty = $gitStatus.Count -gt 0
            gitChangedPathCount = $gitStatus.Count
            datasetSha256 = $DatasetSha256
            runnerSha256 = $runnerSha256
        }) -Force
    $content | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $Path -Encoding utf8
}

New-Item -ItemType Directory -Path $reports -Force | Out-Null

Push-Location $agentRoot
try {
    mvn test `
        "-Dtest=com.linrun.agent.test.eval.HarnessMemoryOfflineBenchmarkTest,com.linrun.agent.test.eval.AgentHarnessOfflineEvalTest" `
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
if (-not (Test-Path $targetHarnessReport)) {
    throw "offline benchmark did not produce $targetHarnessReport"
}

Copy-Item -LiteralPath $targetReport -Destination $finalReport -Force
Copy-Item -LiteralPath $targetHarnessReport -Destination $finalHarnessReport -Force
Add-ReportProvenance $finalReport $memoryTestSha256
Add-ReportProvenance $finalHarnessReport $harnessTestSha256
$report = Get-Content -LiteralPath $finalReport -Raw | ConvertFrom-Json
$harness = Get-Content -LiteralPath $finalHarnessReport -Raw | ConvertFrom-Json

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
Write-Host ("Offline Harness pass@3 / pass^3: {0}% / {1}%" -f `
        $harness.metrics.'pass@3Pct',
        $harness.metrics.'pass^3Pct')
Write-Host "Offline Harness report: $finalHarnessReport"
