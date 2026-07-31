#Requires -Version 7.0
# Layered evaluation over an existing eval report. This builder never re-runs the
# Agent and never invents a measurement: every number is derived from the frozen
# report, its raw SSE capture, or a human annotation file. Metrics that require
# human labelling stay explicitly pending until that file exists.
param(
    [Parameter(Mandatory = $true)][string]$ReportName,
    [string]$AnnotationFile = "",
    [string]$OutputName = ""
)

$ErrorActionPreference = "Stop"
$baseName = [System.IO.Path]::GetFileNameWithoutExtension($ReportName)
$isM0Baseline = ($ReportName -ieq "m0-baseline.json")
$reportDir = Join-Path $PSScriptRoot $(if ($isM0Baseline) { "baselines" } else { "reports" })
$reportPath = Join-Path $reportDir $ReportName
$rawPath = Join-Path $reportDir "$baseName.raw.jsonl"
if (-not (Test-Path -LiteralPath $reportPath)) { throw "Report not found: $reportPath" }
if (-not $OutputName) { $OutputName = "$baseName.layered.json" }
$outputPath = Join-Path $reportDir $OutputName
if (-not $AnnotationFile) { $AnnotationFile = "annotations/$baseName.annotations.jsonl" }
$annotationPath = if ([System.IO.Path]::IsPathRooted($AnnotationFile)) {
    $AnnotationFile
} else {
    Join-Path $PSScriptRoot $AnnotationFile
}

$report = Get-Content -LiteralPath $reportPath -Raw -Encoding UTF8 | ConvertFrom-Json
$cases = @($report.cases)
if ($cases.Count -eq 0) { throw "Report has no case results: $reportPath" }

function Get-Percentile([double[]]$Values, [double]$Quantile) {
    if (-not $Values -or $Values.Count -eq 0) { return $null }
    $sorted = @($Values | Sort-Object)
    $index = [Math]::Min($sorted.Count - 1, [Math]::Max(0, [Math]::Ceiling($Quantile * $sorted.Count) - 1))
    return $sorted[$index]
}

function New-Pending([string]$Reason) {
    return [ordered]@{ status = "pending-human-annotation"; value = $null; reason = $Reason }
}

function New-NotMeasured([string]$Reason) {
    return [ordered]@{ status = "not-measured-by-this-builder"; value = $null; reason = $Reason }
}

function Get-Ratio([int]$Numerator, [int]$Denominator) {
    if ($Denominator -le 0) { return $null }
    return [Math]::Round(100.0 * $Numerator / $Denominator, 1)
}

# ---------- raw SSE facts: the DEEP report frame carries the runtime's own quality numbers ----------
$deepFacts = @{}
if (Test-Path -LiteralPath $rawPath) {
    foreach ($rawLine in [System.IO.File]::ReadLines($rawPath)) {
        if (-not $rawLine.Trim()) { continue }
        $record = $rawLine | ConvertFrom-Json
        foreach ($entry in @($record.rawSseLines)) {
            $text = [string]$entry.line
            if (-not $text -or $text -notmatch 'deep_research_report') { continue }
            $payloadText = $text -replace '^data:\s*', ''
            try { $frame = $payloadText | ConvertFrom-Json } catch { continue }
            if ($frame.outputType -ne 'deep_research_report' -or $null -eq $frame.payload) { continue }
            $key = "$($record.caseId)#$($record.trial)"
            $deepFacts[$key] = [ordered]@{
                qualityStatus = [string]$frame.payload.qualityStatus
                citationCoverage = [double]$frame.payload.citationCoverage
                sourceCount = [int]$frame.payload.sourceCount
                evidenceCount = [int]$frame.payload.evidenceCount
                charCount = [int]$frame.payload.charCount
            }
        }
    }
}

# ---------- optional human annotations ----------
$annotations = @{}
if (Test-Path -LiteralPath $annotationPath) {
    foreach ($line in [System.IO.File]::ReadLines($annotationPath)) {
        if (-not $line.Trim() -or $line.Trim().StartsWith('#')) { continue }
        $record = $line | ConvertFrom-Json
        $trial = if ($null -ne $record.trial) { $record.trial } else { 1 }
        $annotations["$($record.caseId)#$trial"] = $record
    }
}
$annotationPresent = $annotations.Count -gt 0

# ---------- layer 1: schema and rule conformance ----------
$typedTerminalRuns = 0
$canonicalRuns = 0
$typedQuotaRuns = 0
$completionGateRuns = 0
foreach ($case in $cases) {
    $hasTypedTerminal = ([string]$case.terminalStatus) -or ([string]$case.stopReason)
    if ($hasTypedTerminal) { $typedTerminalRuns += 1 }
    if ($case.canonicalLifecyclePassed -eq $true) { $canonicalRuns += 1 }
    if ([string]$case.quotaStatus) { $typedQuotaRuns += 1 }
    if ($case.completionGatePassed -eq $true -or [string]$case.stopReason) { $completionGateRuns += 1 }
}
$schemaRuleLayer = [ordered]@{
    totalRuns = $cases.Count
    typedTerminalStateRuns = $typedTerminalRuns
    typedTerminalStateRatePct = Get-Ratio $typedTerminalRuns $cases.Count
    canonicalLifecycleRuns = $canonicalRuns
    canonicalLifecycleRatePct = Get-Ratio $canonicalRuns $cases.Count
    typedQuotaStateRuns = $typedQuotaRuns
    typedQuotaStateRatePct = Get-Ratio $typedQuotaRuns $cases.Count
    completionGateDecidedRuns = $completionGateRuns
    completionGateDecidedRatePct = Get-Ratio $completionGateRuns $cases.Count
    meaning = "Every run must end in a typed terminal state with a typed quota state. A run whose completion gate never ran because admission rejected it counts as decided only when it carries a typed stop reason."
}

# ---------- layer 2: tool selection, execution and repetition ----------
$toolUsedRuns = 0
$repeatedToolNameRuns = 0
$toolAttemptPassed = 0
$toolSuccessPassed = 0
foreach ($case in $cases) {
    if ($case.toolAttemptPassed -eq $true) { $toolAttemptPassed += 1 }
    if ($case.toolSuccessPassed -eq $true) { $toolSuccessPassed += 1 }
    $invocations = @($case.ledgerSnapshot.toolInvocations)
    if ($invocations.Count -eq 0) { continue }
    $toolUsedRuns += 1
    $names = @($invocations | ForEach-Object { [string]$_.toolName })
    $distinct = @($names | Sort-Object -Unique)
    if ($names.Count -gt $distinct.Count) { $repeatedToolNameRuns += 1 }
}
$toolLayer = [ordered]@{
    runsWithLedgeredToolCalls = $toolUsedRuns
    expectedToolAttemptRatePct = Get-Ratio $toolAttemptPassed $cases.Count
    expectedToolSuccessRatePct = Get-Ratio $toolSuccessPassed $cases.Count
    repeatedToolNameRuns = $repeatedToolNameRuns
    repeatedToolNameRatePct = Get-Ratio $repeatedToolNameRuns $toolUsedRuns
    toolArgumentCorrectness = if ($annotationPresent) {
        $labelled = @($annotations.Values | Where-Object { $null -ne $_.toolArgumentsCorrect })
        if ($labelled.Count -eq 0) {
            New-Pending "No annotation record carries toolArgumentsCorrect."
        } else {
            [ordered]@{
                status = "annotated"
                labelledRuns = $labelled.Count
                correctRuns = @($labelled | Where-Object { $_.toolArgumentsCorrect -eq $true }).Count
            }
        }
    } else {
        New-Pending "Tool-argument correctness is a human judgement; add $AnnotationFile to score it."
    }
    meaning = "repeatedToolNameRate counts runs where one tool name ran more than once. The ledger snapshot in this report does not carry tool inputs, so this is repetition of a tool, not proven duplicate identical calls."
}

# ---------- layer 3: research quality ----------
$deepCases = @($cases | Where-Object { [string]$_.executionMode -eq 'DEEP' })
$observedDeep = @()
foreach ($case in $deepCases) {
    $fact = $deepFacts["$($case.id)#$($case.trial)"]
    if ($fact) { $observedDeep += $fact }
}
$coverageValues = @($observedDeep | ForEach-Object { [double]$_.citationCoverage })
$sourceValues = @($observedDeep | ForEach-Object { [double]$_.sourceCount })
$qualityBreakdown = @{}
foreach ($fact in $observedDeep) {
    $key = if ($fact.qualityStatus) { $fact.qualityStatus } else { "UNKNOWN" }
    $qualityBreakdown[$key] = 1 + [int]$qualityBreakdown[$key]
}
$claimTotal = 0; $claimSupported = 0; $claimCited = 0
$conflictsPresent = 0; $conflictsIdentified = 0
$repairAttempted = 0; $repairSucceeded = 0
foreach ($record in $annotations.Values) {
    foreach ($claim in @($record.claims)) {
        $claimTotal += 1
        if ($claim.citedSourceUrl) { $claimCited += 1 }
        if ($claim.supported -eq $true) { $claimSupported += 1 }
    }
    if ($null -ne $record.conflictsPresent) { $conflictsPresent += [int]$record.conflictsPresent }
    if ($null -ne $record.conflictsIdentified) { $conflictsIdentified += [int]$record.conflictsIdentified }
    if ($record.reviewerRepairAttempted -eq $true) {
        $repairAttempted += 1
        if ($record.reviewerRepairSucceeded -eq $true) { $repairSucceeded += 1 }
    }
}
$researchLayer = [ordered]@{
    deepRuns = $deepCases.Count
    deepRunsWithReportFrame = $observedDeep.Count
    runtimeCitationCoverageAvg = if ($coverageValues.Count -gt 0) {
        [Math]::Round(($coverageValues | Measure-Object -Average).Average, 3)
    } else { $null }
    runtimeSourceCountAvg = if ($sourceValues.Count -gt 0) {
        [Math]::Round(($sourceValues | Measure-Object -Average).Average, 1)
    } else { $null }
    runtimeQualityStatusBreakdown = $qualityBreakdown
    citationPrecision = if ($claimCited -gt 0) {
        [ordered]@{ status = "annotated"; value = [Math]::Round(100.0 * $claimSupported / $claimCited, 1); citedClaims = $claimCited }
    } else {
        New-Pending "Citation precision needs per-claim labels in $AnnotationFile."
    }
    unsupportedClaimRate = if ($claimTotal -gt 0) {
        [ordered]@{ status = "annotated"; value = [Math]::Round(100.0 * ($claimTotal - $claimSupported) / $claimTotal, 1); totalClaims = $claimTotal }
    } else {
        New-Pending "Unsupported-claim rate needs per-claim labels in $AnnotationFile."
    }
    conflictRecall = if ($conflictsPresent -gt 0) {
        [ordered]@{ status = "annotated"; value = [Math]::Round(100.0 * $conflictsIdentified / $conflictsPresent, 1); conflictsPresent = $conflictsPresent }
    } else {
        New-Pending "Conflict recall needs conflictsPresent/conflictsIdentified in $AnnotationFile."
    }
    reviewerRepairSuccessRate = if ($repairAttempted -gt 0) {
        [ordered]@{ status = "annotated"; value = [Math]::Round(100.0 * $repairSucceeded / $repairAttempted, 1); attempts = $repairAttempted }
    } else {
        New-Pending "Reviewer repair success needs reviewerRepairAttempted/Succeeded in $AnnotationFile."
    }
    meaning = "runtimeCitationCoverage is the Agent's own number from the deep_research_report frame. It is a runtime self-report and is deliberately kept separate from the human-labelled precision and unsupported-claim metrics."
}

# ---------- layer 4: engineering metrics ----------
$costValues = @()
$latencyValues = @()
$chargedTotal = 0
foreach ($case in $cases) {
    if ($case.cost.status -eq 'observed' -and $case.cost.value) {
        $costValues += [double]$case.cost.value
        $chargedTotal += [long]$case.cost.value
    }
    if ($case.latencyMs) { $latencyValues += [double]$case.latencyMs }
}
$takeoverRuns = @($cases | Where-Object {
        @($_.requiredActions | Where-Object { $null -ne $_ }).Count -gt 0
    }).Count
$degradedDeepRuns = @($observedDeep | Where-Object { $_.qualityStatus -ne 'PASSED' }).Count
$engineeringLayer = [ordered]@{
    taskSuccessRatePct = $report.results.endToEndTaskSuccessRatePct
    ledgerTerminalSuccessRatePct = $report.results.ledgerTerminalSuccessRatePct
    costP50Microcredits = Get-Percentile $costValues 0.5
    costP95Microcredits = Get-Percentile $costValues 0.95
    agentChargedTotalMicrocredits = $chargedTotal
    latencyP50Ms = Get-Percentile $latencyValues 0.5
    latencyP95Ms = Get-Percentile $latencyValues 0.95
    humanTakeoverRuns = $takeoverRuns
    humanTakeoverRatePct = Get-Ratio $takeoverRuns $cases.Count
    degradedDeepDeliveryRuns = $degradedDeepRuns
    degradedDeepDeliveryRatePct = Get-Ratio $degradedDeepRuns $observedDeep.Count
    recoverySuccessRatePct = New-NotMeasured "Recovery is exercised by the fault-injection tests, not by a live eval pass."
    quotaLedgerDiff = New-NotMeasured "Agent-side charge is recorded above; the member-side comparison is made by docs/acceptance/agent-product-e2e.ps1 on an isolated account."
    meaning = "degradedDeepDelivery counts DEEP runs whose own report frame is not PASSED. Such a run can still pass the keyword and lifecycle gates, so it is reported separately instead of being folded into task success."
}

# ---------- layer 5: fault-injection coverage map ----------
$faultLayer = [ordered]@{
    note = "This builder does not run faults. It records which named fault currently has a covering automated test, so an uncovered fault stays visible instead of being implied as covered."
    faults = @(
        [ordered]@{ fault = "model-timeout"; status = "not-covered"; covering = $null },
        [ordered]@{ fault = "provider-started-then-disconnected"; status = "covered"; covering = "UnifiedModelInvocationIT#leavesProviderStartedFreezeForRecoveryWhenProviderOutcomeIsUnknown" },
        [ordered]@{ fault = "tool-timeout"; status = "not-covered"; covering = $null },
        [ordered]@{ fault = "mcp-redirect"; status = "implemented-not-tested"; covering = "McpClientRuntimeFactory rejects 3xx; no named test asserts it" },
        [ordered]@{ fault = "dns-rebinding"; status = "covered"; covering = "McpExecutionGateTest#shouldRejectAHostThatResolvesToPrivateAddressOnRecheck" },
        [ordered]@{ fault = "sse-disconnect"; status = "covered"; covering = "SseResumeContractTest" },
        [ordered]@{ fault = "restart-while-waiting-for-approval"; status = "covered"; covering = "ToolApprovalRecoveryIT" },
        [ordered]@{ fault = "process-kill-during-run"; status = "covered"; covering = "StandardCheckpointRecoveryIT, DialogueRunRecoveryTest" }
    )
}

$layered = [ordered]@{
    schemaVersion = 1
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    sourceReport = $ReportName
    sourceProvenance = $report.provenance
    sourceDataset = $report.dataset
    annotationFile = $AnnotationFile
    annotationPresent = $annotationPresent
    annotatedRuns = $annotations.Count
    layers = [ordered]@{
        schemaRule = $schemaRuleLayer
        tool = $toolLayer
        researchQuality = $researchLayer
        engineering = $engineeringLayer
        faultInjection = $faultLayer
    }
}

$layered | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $outputPath -Encoding utf8
Write-Host "layered report written: $outputPath"
Write-Host ("  schema/rule typed terminal : {0}/{1}" -f $schemaRuleLayer.typedTerminalStateRuns, $schemaRuleLayer.totalRuns)
Write-Host ("  tool repeated-name runs    : {0}/{1}" -f $toolLayer.repeatedToolNameRuns, $toolLayer.runsWithLedgeredToolCalls)
Write-Host ("  deep runs with report frame: {0}/{1}" -f $researchLayer.deepRunsWithReportFrame, $researchLayer.deepRuns)
Write-Host ("  human annotations          : {0} run(s)" -f $annotations.Count)
