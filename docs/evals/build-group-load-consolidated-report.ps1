#Requires -Version 7.0
param(
    [string]$ReportName = "group-load-benchmark.json"
)

$ErrorActionPreference = "Stop"
$reports = Join-Path $PSScriptRoot "reports"
$outputPath = Join-Path $reports $ReportName
$steadySeconds = 600

function Get-MetricValues($Summary, [string]$Name) {
    $property = $Summary.metrics.PSObject.Properties[$Name]
    if (-not $property) { return $null }
    return $property.Value.values
}

function Get-Value($Object, [string]$Name, $Default = $null) {
    if ($null -ne $Object -and $Object.PSObject.Properties.Name -contains $Name) {
        return $Object.$Name
    }
    return $Default
}

function Get-Average($Values, [int]$Digits = 2) {
    $items = @($Values | Where-Object { $null -ne $_ } | ForEach-Object { [double]$_ })
    if ($items.Count -eq 0) { return $null }
    return [Math]::Round([double](($items | Measure-Object -Average).Average), $Digits)
}

function Get-Maximum($Values, [int]$Digits = 2) {
    $items = @($Values | Where-Object { $null -ne $_ } | ForEach-Object { [double]$_ })
    if ($items.Count -eq 0) { return $null }
    return [Math]::Round([double](($items | Measure-Object -Maximum).Maximum), $Digits)
}

function Get-Minimum($Values, [int]$Digits = 2) {
    $items = @($Values | Where-Object { $null -ne $_ } | ForEach-Object { [double]$_ })
    if ($items.Count -eq 0) { return $null }
    return [Math]::Round([double](($items | Measure-Object -Minimum).Minimum), $Digits)
}

function Get-StandardDeviation($Values, [int]$Digits = 2) {
    $items = @($Values | Where-Object { $null -ne $_ } | ForEach-Object { [double]$_ })
    if ($items.Count -lt 2) { return 0.0 }
    $mean = ($items | Measure-Object -Average).Average
    $sum = 0.0
    foreach ($value in $items) { $sum += [Math]::Pow($value - $mean, 2) }
    return [Math]::Round([Math]::Sqrt($sum / ($items.Count - 1)), $Digits)
}

function Add-Note($Object, [string]$Name, $Value) {
    $Object | Add-Member -NotePropertyName $Name -NotePropertyValue $Value -Force
}

function Get-ResourceSummary([string]$Path, [string]$Observability, $SampleFailures) {
    $samples = @(Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json)
    $first = if ($samples.Count) { $samples[0] } else { $null }
    $last = if ($samples.Count) { $samples[-1] } else { $null }
    return [pscustomobject][ordered]@{
        observability = $Observability
        samples = $samples.Count
        sampleFailures = $SampleFailures
        appCpuAveragePct = if ($samples.Count) { [Math]::Round(100.0 * [double](Get-Average @($samples.appProcessCpuPct) 4), 2) } else { $null }
        appCpuMaxPct = if ($samples.Count) { [Math]::Round(100.0 * [double](Get-Maximum @($samples.appProcessCpuPct) 4), 2) } else { $null }
        systemCpuAveragePct = if ($samples.Count) { [Math]::Round(100.0 * [double](Get-Average @($samples.systemCpuPct) 4), 2) } else { $null }
        systemCpuMaxPct = if ($samples.Count) { [Math]::Round(100.0 * [double](Get-Maximum @($samples.systemCpuPct) 4), 2) } else { $null }
        appWorkingSetMaxBytes = if ($samples.Count) { [long](Get-Maximum @($samples.appWorkingSetBytes) 0) } else { $null }
        appThreadCountMax = if ($samples.Count) { [int](Get-Maximum @($samples.appThreadCount) 0) } else { $null }
        hikariActiveMax = if ($samples.Count) { Get-Maximum @($samples.hikariActive) 0 } else { $null }
        hikariPendingMax = if ($samples.Count) { Get-Maximum @($samples.hikariPending) 0 } else { $null }
        tomcatBusyThreadsMax = if ($samples.Count) { Get-Maximum @($samples.tomcatBusyThreads) 0 } else { $null }
        mysqlCpuAveragePct = if ($samples.Count) { Get-Average @($samples.mysqlCpuPct) 2 } else { $null }
        mysqlCpuMaxPct = if ($samples.Count) { Get-Maximum @($samples.mysqlCpuPct) 2 } else { $null }
        mysqlMemoryMaxBytes = if ($samples.Count) { [long](Get-Maximum @($samples.mysqlMemoryBytes) 0) } else { $null }
        redisCpuAveragePct = if ($samples.Count) { Get-Average @($samples.redisCpuPct) 2 } else { $null }
        redisCpuMaxPct = if ($samples.Count) { Get-Maximum @($samples.redisCpuPct) 2 } else { $null }
        redisMemoryMaxBytes = if ($samples.Count) { [long](Get-Maximum @($samples.redisMemoryBytes) 0) } else { $null }
        gcPauseCount = if ($first -and $last) { [long]($last.gcPauseCount - $first.gcPauseCount) } else { $null }
        gcPauseTimeMs = if ($first -and $last) { [Math]::Round(1000.0 * ($last.gcPauseSeconds - $first.gcPauseSeconds), 2) } else { $null }
    }
}

function New-RawRun(
    [string]$SummaryPath,
    [string]$ResourcePath,
    [int]$Concurrency,
    [int]$Repeat,
    [long]$CommitOutcomeDelta,
    [string]$DatabaseEvidence,
    [string]$ResourceObservability,
    $ResourceSampleFailures
) {
    $summary = Get-Content -Raw -LiteralPath $SummaryPath | ConvertFrom-Json
    $steadyDuration = Get-MetricValues $summary "successful_lock_duration{phase:steady}"
    $steadySuccess = Get-MetricValues $summary "lock_business_success{phase:steady}"
    $steadyBusinessFailure = Get-MetricValues $summary "lock_business_failure{phase:steady}"
    $steadyTransportFailure = Get-MetricValues $summary "lock_transport_failure{phase:steady}"
    $steadyLocks = Get-MetricValues $summary "successful_locks{phase:steady}"
    $allLocks = Get-MetricValues $summary "successful_locks"
    $runId = [IO.Path]::GetFileNameWithoutExtension($SummaryPath).Split('-')[-1]
    $resources = Get-ResourceSummary $ResourcePath $ResourceObservability $ResourceSampleFailures
    $totalSuccesses = [long](Get-Value $allLocks "count" 0)
    $steadySuccesses = [long](Get-Value $steadyLocks "count" 0)
    $databaseRows = $totalSuccesses + $CommitOutcomeDelta

    return [pscustomobject][ordered]@{
        runId = $runId
        concurrency = $Concurrency
        repeat = $Repeat
        warmupSeconds = 120
        steadySeconds = $steadySeconds
        k6ExitCode = $null
        warmupSuccessfulLocks = $totalSuccesses - $steadySuccesses
        steadySuccessfulLocks = $steadySuccesses
        steadyAttempts = [long](Get-Value $steadyTransportFailure "passes" 0) + [long](Get-Value $steadyTransportFailure "fails" 0)
        steadyBusinessFailures = [long](Get-Value $steadyBusinessFailure "passes" 0)
        steadyTransportFailures = [long](Get-Value $steadyTransportFailure "passes" 0)
        steadySuccessfulLockQps = [Math]::Round($steadySuccesses / [double]$steadySeconds, 2)
        steadyBusinessSuccessRatePct = [Math]::Round(100.0 * [double](Get-Value $steadySuccess "rate" 0), 4)
        steadyBusinessFailureRatePct = [Math]::Round(100.0 * [double](Get-Value $steadyBusinessFailure "rate" 0), 4)
        steadyTransportFailureRatePct = [Math]::Round(100.0 * [double](Get-Value $steadyTransportFailure "rate" 0), 4)
        latencyMs = [pscustomobject][ordered]@{
            average = [Math]::Round([double](Get-Value $steadyDuration "avg" 0), 2)
            p50 = [Math]::Round([double](Get-Value $steadyDuration "med" 0), 2)
            p90 = [Math]::Round([double](Get-Value $steadyDuration "p(90)" 0), 2)
            p95 = [Math]::Round([double](Get-Value $steadyDuration "p(95)" 0), 2)
            p99 = [Math]::Round([double](Get-Value $steadyDuration "p(99)" 0), 2)
            max = [Math]::Round([double](Get-Value $steadyDuration "max" 0), 2)
        }
        database = [pscustomobject][ordered]@{
            rows = $databaseRows
            clientConfirmedSuccesses = $totalSuccesses
            clientDatabaseReconciled = $CommitOutcomeDelta -eq 0
            commitOutcomeDelta = $CommitOutcomeDelta
            unconfirmedCommittedRows = [Math]::Max(0, $CommitOutcomeDelta)
            missingCommittedRows = [Math]::Max(0, -$CommitOutcomeDelta)
            distinctTeams = $databaseRows
            distinctOrders = $databaseRows
            distinctTrades = $databaseRows
            internalUniqueState = $true
            uniqueState = $CommitOutcomeDelta -eq 0
            evidenceSource = $DatabaseEvidence
            questions = $null
            commits = $null
            rollbacks = $null
            threadsCreated = $null
            abortedConnects = $null
            rowLockWaits = $null
            rowLockTimeMs = $null
        }
        resources = $resources
        transportFailureCounts = [pscustomobject][ordered]@{
            classified = $false
            connectionRefused = $null
            timeout = $null
            httpError = $null
            other = $null
        }
        artifacts = [pscustomobject][ordered]@{
            k6Summary = [IO.Path]::GetFileName($SummaryPath)
            resourceSamples = [IO.Path]::GetFileName($ResourcePath)
        }
    }
}

function Import-StructuredRuns([string]$ReportPath, [int]$RepeatOffset, [string]$Observability) {
    $report = Get-Content -Raw -LiteralPath $ReportPath | ConvertFrom-Json
    $items = @()
    foreach ($sourceRun in $report.runs) {
        $run = $sourceRun | ConvertTo-Json -Depth 20 | ConvertFrom-Json
        $run.repeat = [int]$run.repeat + $RepeatOffset
        $summaryPath = Join-Path $reports $run.artifacts.k6Summary
        $summary = Get-Content -Raw -LiteralPath $summaryPath | ConvertFrom-Json
        $businessFailure = Get-MetricValues $summary "lock_business_failure{phase:steady}"
        $transportFailure = Get-MetricValues $summary "lock_transport_failure{phase:steady}"
        Add-Note $run "steadyAttempts" ([long](Get-Value $transportFailure "passes" 0) + [long](Get-Value $transportFailure "fails" 0))
        Add-Note $run "steadyBusinessFailures" ([long](Get-Value $businessFailure "passes" 0))
        Add-Note $run "steadyTransportFailures" ([long](Get-Value $transportFailure "passes" 0))
        Add-Note $run.resources "observability" $Observability
        Add-Note $run.database "evidenceSource" "runner structured report"
        Add-Note $run.transportFailureCounts "classified" $true
        $items += $run
    }
    return $items
}

function Build-Aggregates($Runs) {
    $rows = @()
    foreach ($group in @($Runs | Group-Object concurrency | Sort-Object { [int]$_.Name })) {
        $items = @($group.Group | Sort-Object repeat)
        $qps = @($items.steadySuccessfulLockQps)
        $observable = @($items | Where-Object { $_.resources.observability -ne "unavailable" })
        $classified = @($items | Where-Object { $_.transportFailureCounts.classified })
        $observability = if (@($items | Where-Object { $_.resources.observability -eq "unavailable" }).Count) {
            "unavailable"
        } elseif (@($items | Where-Object { $_.resources.observability -eq "degraded" }).Count) {
            "degraded"
        } else {
            "reliable"
        }
        $meanQps = Get-Average $qps 4
        $sdQps = Get-StandardDeviation $qps 4
        $rows += [pscustomobject][ordered]@{
            concurrency = [int]$group.Name
            repeats = $items.Count
            steadyAttempts = [long](($items | Measure-Object steadyAttempts -Sum).Sum)
            steadySuccessfulLocks = [long](($items | Measure-Object steadySuccessfulLocks -Sum).Sum)
            qpsAverage = [Math]::Round($meanQps, 2)
            qpsMin = Get-Minimum $qps 2
            qpsMax = Get-Maximum $qps 2
            qpsStdDev = [Math]::Round($sdQps, 2)
            qpsCoefficientOfVariationPct = if ($meanQps -gt 0) { [Math]::Round(100.0 * $sdQps / $meanQps, 2) } else { $null }
            p50AverageMs = Get-Average @($items.latencyMs.p50) 2
            p95AverageMs = Get-Average @($items.latencyMs.p95) 2
            p95WorstMs = Get-Maximum @($items.latencyMs.p95) 2
            p99AverageMs = Get-Average @($items.latencyMs.p99) 2
            p99WorstMs = Get-Maximum @($items.latencyMs.p99) 2
            businessFailureRateAveragePct = Get-Average @($items.steadyBusinessFailureRatePct) 4
            businessFailureRateMaxPct = Get-Maximum @($items.steadyBusinessFailureRatePct) 4
            transportFailureRateAveragePct = Get-Average @($items.steadyTransportFailureRatePct) 4
            transportFailureRateMaxPct = Get-Maximum @($items.steadyTransportFailureRatePct) 4
            transportClassifiedRuns = $classified.Count
            transportConnectionRefused = if ($classified.Count) { [long](($classified.transportFailureCounts | Measure-Object connectionRefused -Sum).Sum) } else { $null }
            transportTimeouts = if ($classified.Count) { [long](($classified.transportFailureCounts | Measure-Object timeout -Sum).Sum) } else { $null }
            transportHttpErrors = if ($classified.Count) { [long](($classified.transportFailureCounts | Measure-Object httpError -Sum).Sum) } else { $null }
            transportOtherErrors = if ($classified.Count) { [long](($classified.transportFailureCounts | Measure-Object other -Sum).Sum) } else { $null }
            resourceObservability = $observability
            resourceSamples = [long](($items.resources | Measure-Object samples -Sum).Sum)
            resourceSampleFailuresKnownRuns = @($items.resources | Where-Object { $null -ne $_.sampleFailures }).Count
            resourceSampleFailures = [long](($items.resources | Where-Object { $null -ne $_.sampleFailures } | Measure-Object sampleFailures -Sum).Sum)
            appCpuAveragePct = if ($observable.Count) { Get-Average @($observable.resources.appCpuAveragePct) 2 } else { $null }
            appCpuPeakPct = if ($observable.Count) { Get-Maximum @($observable.resources.appCpuMaxPct) 2 } else { $null }
            mysqlCpuAveragePct = if ($observable.Count) { Get-Average @($observable.resources.mysqlCpuAveragePct) 2 } else { $null }
            mysqlCpuPeakPct = if ($observable.Count) { Get-Maximum @($observable.resources.mysqlCpuMaxPct) 2 } else { $null }
            hikariActivePeak = if ($observable.Count) { Get-Maximum @($observable.resources.hikariActiveMax) 0 } else { $null }
            hikariPendingPeak = if ($observable.Count) { Get-Maximum @($observable.resources.hikariPendingMax) 0 } else { $null }
            tomcatBusyThreadsPeak = if ($observable.Count) { Get-Maximum @($observable.resources.tomcatBusyThreadsMax) 0 } else { $null }
            gcPauseCount = if ($observable.Count) { [long](($observable.resources | Measure-Object gcPauseCount -Sum).Sum) } else { $null }
            gcPauseTimeMs = if ($observable.Count) { [Math]::Round([double](($observable.resources | Measure-Object gcPauseTimeMs -Sum).Sum), 2) } else { $null }
            databaseStatusRuns = @($items.database | Where-Object { $null -ne $_.rowLockWaits }).Count
            rowLockWaits = [long](($items.database | Where-Object { $null -ne $_.rowLockWaits } | Measure-Object rowLockWaits -Sum).Sum)
            databaseInternalUniqueStateAllRuns = @($items.database | Where-Object { -not $_.internalUniqueState }).Count -eq 0
            clientDatabaseReconciledAllRuns = @($items.database | Where-Object { -not $_.clientDatabaseReconciled }).Count -eq 0
            allPhaseUnconfirmedCommittedRows = [long](($items.database | Measure-Object unconfirmedCommittedRows -Sum).Sum)
            allPhaseMissingCommittedRows = [long](($items.database | Measure-Object missingCommittedRows -Sum).Sum)
        }
    }
    return $rows
}

$runs = [System.Collections.Generic.List[object]]::new()
$c20Summaries = @(Get-ChildItem -LiteralPath $reports -Filter "group-load-k6-c20-r*.json" | Sort-Object Name)
for ($i = 0; $i -lt $c20Summaries.Count; $i++) {
    $summary = $c20Summaries[$i]
    $resource = Join-Path $reports ($summary.Name -replace '^group-load-k6-', 'group-load-resources-')
    $runs.Add((New-RawRun $summary.FullName $resource 20 ($i + 1) 0 `
                "runner console recorded dbUnique=True before per-run cleanup" "reliable" 0))
}

$c50FirstSummary = Join-Path $reports "group-load-k6-c50-r1-321638.json"
$c50FirstResource = Join-Path $reports "group-load-resources-c50-r1-321638.json"
$runs.Add((New-RawRun $c50FirstSummary $c50FirstResource 50 1 9 `
            "runner exception recorded rows=320636, distinct team/order/trade rows=320636, client successes=320627" `
            "degraded" $null))
foreach ($run in @(Import-StructuredRuns (Join-Path $reports "group-load-c50-resume.json") 1 "degraded")) {
    $runs.Add($run)
}
foreach ($run in @(Import-StructuredRuns (Join-Path $reports "group-load-c100.json") 0 "degraded")) {
    $runs.Add($run)
}
foreach ($run in @(Import-StructuredRuns (Join-Path $reports "group-load-c200.json") 0 "unavailable")) {
    $runs.Add($run)
}

$runs = @($runs | Sort-Object concurrency, repeat)
if ($runs.Count -ne 12) { throw "expected 12 formal runs, found $($runs.Count)" }
$aggregates = @(Build-Aggregates $runs)
$environment = (Get-Content -Raw -LiteralPath (Join-Path $reports "group-load-c100.json") | ConvertFrom-Json).environment
Add-Note $environment "serverConfiguration" ([pscustomobject][ordered]@{
        source = "group/group-buy-market-app/src/main/resources/application-dev.yml"
        tomcatMaxConnections = 20
        tomcatMaxThreads = 20
        tomcatAcceptCount = 10
        hikariMaximumPoolSize = 25
    })
$totalSuccesses = [long](($runs | Measure-Object steadySuccessfulLocks -Sum).Sum)
$totalAttempts = [long](($runs | Measure-Object steadyAttempts -Sum).Sum)
$totalUnconfirmed = [long](($runs.database | Measure-Object unconfirmedCommittedRows -Sum).Sum)

$report = [ordered]@{
    schemaVersion = 3
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    benchmarkType = "local-k6-stepped-steady-successful-new-team-lock"
    environment = $environment
    profile = [ordered]@{
        endpoint = "/api/v1/gbm/trade/lock_market_pay_order"
        workload = "Every request creates a new team. A success requires HTTP 200, business code 0000, and a non-empty teamId."
        loadModel = "closed-loop constant virtual users"
        concurrencyLevels = @(20, 50, 100, 200)
        repeatsPerLevel = 3
        warmupSecondsPerRun = 120
        steadySecondsPerRun = 600
        formalRuns = 12
        totalWarmupSeconds = 1440
        totalSteadySeconds = 7200
        totalSteadyAttempts = $totalAttempts
        totalSteadySuccessfulLocks = $totalSuccesses
    }
    crossTier = [ordered]@{
        highestReliableConcurrency = 20
        highestReliableConcurrencyReason = "Transport failure stayed at or below 0.0124%; concurrency 50 rose to 11.0115%-13.4878%."
        reliableTierSuccessfulLockQpsAverage = $aggregates[0].qpsAverage
        maximumObservedSuccessfulLockQpsAverage = ($aggregates | Sort-Object qpsAverage -Descending | Select-Object -First 1).qpsAverage
        maximumObservedSuccessfulLockQpsConcurrency = ($aggregates | Sort-Object qpsAverage -Descending | Select-Object -First 1).concurrency
        allPhaseUnconfirmedCommittedRows = $totalUnconfirmed
        databaseInternalUniqueStateAllRuns = @($runs.database | Where-Object { -not $_.internalUniqueState }).Count -eq 0
        clientDatabaseReconciledRuns = @($runs.database | Where-Object { $_.clientDatabaseReconciled }).Count
        primarySaturationCause = "The active dev profile caps Tomcat at 20 connections and 20 request threads with accept-count 10; this matches the observed Tomcat/Hikari plateau and connection-refused failures."
        saturationFinding = "Successful throughput plateaued around concurrency 50-100 while transport failure rose to 11%-35%; at concurrency 200, transport failure reached 87%-88% and successful throughput fell to 348.55 QPS."
    }
    results = $aggregates
    runs = $runs
    metricSemantics = [ordered]@{
        qps = "Steady successful responses divided by the configured 600-second steady window. Fast transport failures never count as successful throughput."
        latency = "Client wall-clock duration for successful steady requests only. It is conditional on admission and success."
        businessFailure = "HTTP 200 responses without business code 0000 and teamId. The 8-digit random team ID generator creates collision failures at this volume."
        transportFailure = "Non-HTTP-200 results, dominated by connection refused and then request timeout where cause counters are available."
        consistency = "internalUniqueState checks committed team/order/trade uniqueness. clientDatabaseReconciled compares all-phase client-confirmed successes with committed rows; positive delta means committed but unconfirmed outcomes."
        resources = "Actuator and business traffic share port 8091. Resource values are excluded from tier aggregates when Actuator observability is unavailable."
    }
    limitations = @(
        "The k6 container, Group service, MySQL, and Redis shared one Windows host; this is a local single-host benchmark, not a production SLA.",
        "The Group service ran through spring-boot:run with -XX:TieredStopAtLevel=1, so the JVM used development C1-only compilation rather than a production JVM profile.",
        "The active dev profile intentionally caps Tomcat at max-connections=20, threads.max=20, and accept-count=10; the high-concurrency tiers measure this configured rejection boundary, not tuned server capacity.",
        "Closed-loop virtual users are subject to coordinated omission; a fixed-arrival-rate test on an independent load generator is required for an external SLO claim.",
        "Successful-request latency excludes transport failures. The lower successful P99 at concurrency 200 is admission bias, not a latency improvement.",
        "The 8-digit random team ID has birthday-collision risk at hundreds of thousands of transactions and caused non-zero business failure.",
        "Actuator shared the saturated business listener. At concurrency 200 it was effectively unavailable, so CPU, GC, Hikari, and Tomcat averages for that tier are intentionally null.",
        "Detailed transport-cause counters were added after the first four runs. Core QPS, latency, business-failure, and transport-failure metrics use the same definitions across all 12 runs."
    )
}

$report | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $outputPath -Encoding utf8
Write-Host "Consolidated report: $outputPath"
foreach ($row in $aggregates) {
    Write-Host ("c={0} qps={1} p95={2}/{3}ms p99={4}/{5}ms transport={6}% unconfirmed={7} resources={8}" -f `
            $row.concurrency, $row.qpsAverage, $row.p95AverageMs, $row.p95WorstMs,
            $row.p99AverageMs, $row.p99WorstMs, $row.transportFailureRateMaxPct,
            $row.allPhaseUnconfirmedCommittedRows, $row.resourceObservability)
}
