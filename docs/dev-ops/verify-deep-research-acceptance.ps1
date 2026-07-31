#Requires -Version 7.2
param(
    [string]$Gateway = "http://127.0.0.1:8080",
    [string]$EvidencePath = "",
    [switch]$Run,
    [string]$Only = "all",
    [ValidateRange(60, 600)]
    [int]$TimeoutSeconds = 600
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($EvidencePath)) {
    $EvidencePath = Join-Path $PSScriptRoot ("../acceptance/deep-research-" + (Get-Date -Format "yyyyMMdd-HHmmss") + ".json")
}

function New-Scenario($Id, $Name, $Mode, $Query, $Runnable = $true, $Notes = "",
                       $UploadFile = "", $RequireDeepArtifact = $false,
                       $RequireHistoryReplay = $false, $ExpectedOutcome = "SUCCESS",
                       $ExpectedQualityStatus = "", $MinSourceCount = 0,
                       $MinCharCount = 0, $OverrideFreeQuotaBalance = -1,
                       $DeterministicTest = "", $RequireSseCursorReplay = $false) {
    [pscustomobject]@{
        id = $Id
        name = $Name
        executionMode = $Mode
        outputStyle = if ($Mode -eq "DEEP") { "markdown" } else { "chat" }
        query = $Query
        runnable = $Runnable
        notes = $Notes
        uploadFile = $UploadFile
        requireDeepArtifact = $RequireDeepArtifact
        requireHistoryReplay = $RequireHistoryReplay
        requireSseCursorReplay = $RequireSseCursorReplay
        expectedOutcome = $ExpectedOutcome
        expectedQualityStatus = $ExpectedQualityStatus
        minSourceCount = $MinSourceCount
        minCharCount = $MinCharCount
        overrideFreeQuotaBalance = $OverrideFreeQuotaBalance
        deterministicTest = $DeterministicTest
    }
}

$csvFixture = Join-Path $PSScriptRoot "../../ai-agent/demo/fixtures/synthetic-sales-2025.csv"
$pdfFixture = Join-Path $PSScriptRoot "../../ai-agent/runtime/tools/.venv/Lib/site-packages/matplotlib/mpl-data/images/filesave.pdf"
$csvRunnable = Test-Path -LiteralPath $csvFixture
$pdfRunnable = Test-Path -LiteralPath $pdfFixture
$csvNotes = if ($csvRunnable) { "" } else { "CSV fixture missing: $csvFixture" }
$pdfNotes = if ($pdfRunnable) { "" } else { "PDF fixture missing: $pdfFixture" }

$scenarios = @(
    New-Scenario "standard-qa" "Standard question" "STANDARD" "Reply with AGENT_STANDARD_OK only."
    New-Scenario "industry-research" "Industry research" "DEEP" "Deeply research the 2026 enterprise AI agent platform market." $true "" "" $true $false "SUCCESS" "PASSED" 20 15000
    New-Scenario "competitor-analysis" "Competitor analysis" "DEEP" "Compare Dify, Coze, LangGraph, and OpenAI AgentKit for enterprise agent adoption." $true "" "" $true $false "SUCCESS" "PASSED" 20 15000
    New-Scenario "tech-research" "Technology research" "DEEP" "Research LangGraph4j versus Spring AI workflows for Java agent orchestration." $true "" "" $true $false "SUCCESS" "PASSED" 20 15000
    New-Scenario "pdf-synthesis" "PDF synthesis" "DEEP" "Synthesize the uploaded PDF with current public sources." $pdfRunnable $pdfNotes $pdfFixture $true $false "SUCCESS" "PASSED" 20 15000
    New-Scenario "csv-web-research" "CSV plus web research" "DEEP" "Analyze the uploaded CSV and enrich the findings with current web sources." $csvRunnable $csvNotes $csvFixture $true $false "SUCCESS" "PASSED" 20 15000
    New-Scenario "cancel" "Cancellation" "DEEP" "Start a long deep research run, cancel after researcher progress, and verify no final success." $false "Validated by deterministic downstream-abort graph test." "" $false $false "SUCCESS" "" 0 0 -1 "com.linrun.agent.domain.agent.runtime.deepresearch.DeepResearchGraphRunnerTest#shouldStopBeforeResearchersWhenDownstreamAbortsAfterPlanner"
    # P160 proves delivery/replay durability. P120 owns the stricter report-quality gate.
    New-Scenario "reconnect" "SSE reconnect" "DEEP" "Start deep research, then verify stage, artifact and Last-Event-ID replay." $true "" "" $true $true "SUCCESS" "" 0 0 -1 "" $true
    New-Scenario "resume" "Checkpoint resume" "DEEP" "Restart the agent service mid-run and verify checkpoint resume without duplicate branches." $false "Validated by deterministic checkpoint resume test." "" $false $false "SUCCESS" "" 0 0 -1 "com.linrun.agent.domain.agent.runtime.deepresearch.DeepResearchGraphRunnerTest#shouldResumeCompletedCheckpointWithoutRepeatingResearchers"
    New-Scenario "branch-failure" "Branch failure" "DEEP" "Force one researcher branch to fail and verify degraded/repair behavior." $false "Validated by deterministic injected branch failure test." "" $false $false "SUCCESS" "" 0 0 -1 "com.linrun.agent.domain.agent.runtime.deepresearch.DeepResearchGraphRunnerTest#shouldDegradeAndRepairWhenOneResearchBranchFails"
    New-Scenario "low-evidence" "Insufficient evidence" "DEEP" "Research a deliberately obscure private topic and verify DEGRADED output." $true "" "" $true $false "SUCCESS" "DEGRADED"
    New-Scenario "quota-insufficient" "Quota insufficient" "DEEP" "Run deep research with an account whose quota cannot cover reservation." $true "" "" $false $false "QUOTA_FAILURE" "" 0 0 0
)

if ($Only -ne "all") {
    $scenarios = $scenarios | Where-Object { $_.id -eq $Only }
    if (-not $scenarios) { throw "unknown scenario id: $Only" }
}

function Test-GatewayPort($BaseUrl) {
    $uri = [uri]$BaseUrl
    $port = if ($uri.Port -gt 0) { $uri.Port } elseif ($uri.Scheme -eq "https") { 443 } else { 80 }
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        return $client.ConnectAsync($uri.Host, $port).Wait(3000)
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

function Save-Evidence($Items) {
    $dir = Split-Path -Parent $EvidencePath
    if (-not (Test-Path -LiteralPath $dir)) {
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
    }
    [pscustomobject]@{
        generatedAt = (Get-Date).ToString("o")
        gateway = $Gateway
        runRequested = [bool]$Run
        scenarios = $Items
    } | ConvertTo-Json -Depth 10 | Set-Content -Encoding UTF8 -LiteralPath $EvidencePath
}

function Invoke-DeterministicScenarioTest([string]$TestSpec) {
    if ([string]::IsNullOrWhiteSpace($TestSpec)) { return }
    Push-Location (Join-Path $PSScriptRoot "../../ai-agent")
    try {
        & mvn -pl ai-agent-app -am "-Dtest=$TestSpec" "-Dsurefire.failIfNoSpecifiedTests=false" test
        if ($LASTEXITCODE -ne 0) {
            throw "deterministic test failed: $TestSpec"
        }
    } finally {
        Pop-Location
    }
}

$results = @()
if (-not $Run) {
    foreach ($scenario in $scenarios) {
        $results += [pscustomobject]@{ id = $scenario.id; status = "PLANNED"; notes = $scenario.notes }
    }
    Save-Evidence $results
    Write-Host "Deep research acceptance plan written: $EvidencePath"
    exit 0
}

if (-not (Test-GatewayPort $Gateway)) {
    foreach ($scenario in $scenarios) {
        $results += [pscustomobject]@{ id = $scenario.id; status = "BLOCKED"; notes = "Gateway port is not reachable: $Gateway" }
    }
    Save-Evidence $results
    throw "Gateway port is not reachable: $Gateway"
}

foreach ($scenario in $scenarios) {
    if (-not $scenario.runnable) {
        if (-not [string]::IsNullOrWhiteSpace([string]$scenario.deterministicTest)) {
            try {
                Invoke-DeterministicScenarioTest ([string]$scenario.deterministicTest)
                $results += [pscustomobject]@{ id = $scenario.id; status = "PASSED"; notes = $scenario.notes }
            } catch {
                $results += [pscustomobject]@{ id = $scenario.id; status = "FAILED"; notes = $_.Exception.Message }
            }
        } else {
            $results += [pscustomobject]@{ id = $scenario.id; status = "MANUAL_REQUIRED"; notes = $scenario.notes }
        }
        continue
    }

    try {
        $smokeArgs = @{
            Gateway = $Gateway
            Query = $scenario.query
            ExecutionMode = $scenario.executionMode
            OutputStyle = $scenario.outputStyle
            TimeoutSeconds = $TimeoutSeconds
            ExpectedOutcome = $scenario.expectedOutcome
        }
        if (-not [string]::IsNullOrWhiteSpace([string]$scenario.expectedQualityStatus)) {
            $smokeArgs.ExpectedQualityStatus = $scenario.expectedQualityStatus
        }
        if ([int]$scenario.minSourceCount -gt 0) { $smokeArgs.MinSourceCount = [int]$scenario.minSourceCount }
        if ([int]$scenario.minCharCount -gt 0) { $smokeArgs.MinCharCount = [int]$scenario.minCharCount }
        if ($scenario.requireDeepArtifact) { $smokeArgs.RequireDeepArtifact = $true }
        if ($scenario.requireHistoryReplay) { $smokeArgs.RequireHistoryReplay = $true }
        if ($scenario.requireSseCursorReplay) { $smokeArgs.RequireSseCursorReplay = $true }
        if (-not [string]::IsNullOrWhiteSpace([string]$scenario.uploadFile)) {
            $smokeArgs.UploadFile = $scenario.uploadFile
        }
        if ([long]$scenario.overrideFreeQuotaBalance -ge 0) {
            $smokeArgs.OverrideFreeQuotaBalance = [long]$scenario.overrideFreeQuotaBalance
        }

        & "$PSScriptRoot/smoke-agent-sse.ps1" @smokeArgs
        $results += [pscustomobject]@{ id = $scenario.id; status = "PASSED"; notes = "" }
    } catch {
        $results += [pscustomobject]@{ id = $scenario.id; status = "FAILED"; notes = $_.Exception.Message }
    }
}

Save-Evidence $results
if ($results.status -contains "FAILED") { throw "one or more deep research acceptance scenarios failed; see $EvidencePath" }
if ($results.status -contains "MANUAL_REQUIRED") {
    Write-Host "Automated scenarios finished; manual scenarios remain. Evidence: $EvidencePath"
    exit 3
}

Write-Host "Deep research acceptance passed. Evidence: $EvidencePath"
