#Requires -Version 7.0
param(
    [int[]]$ConcurrencyLevels = @(20, 50, 100, 200),
    [int]$WarmupSeconds = 120,
    [int]$SteadySeconds = 600,
    [int]$Repeats = 3,
    [int]$SampleIntervalSeconds = 10,
    [string]$GroupBase = "http://127.0.0.1:8091",
    [string]$InternalToken = $env:AI_GROUP_INTERNAL_TOKEN,
    [string]$MysqlContainer = "ai-group-mysql",
    [string]$MysqlPassword = $(if ($env:MYSQL_ROOT_PASSWORD) { $env:MYSQL_ROOT_PASSWORD } else { "123456" }),
    [string]$RedisContainer = "ai-group-redis",
    [int]$SourceActivityId = 100201,
    [int]$LoadActivityId = 199901,
    [string]$GoodsId = "9890002",
    [decimal]$OrderPrice = 12.00,
    [int]$LockMaxAttempts = 3,
    [int]$RetryBackoffMillis = 100,
    [int]$RequestTimeoutSeconds = 30,
    [string]$K6Image = "grafana/k6:latest",
    [string]$ReportName = "group-load-benchmark.json"
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$reports = Join-Path $PSScriptRoot "reports"
$k6Scripts = Join-Path $PSScriptRoot "k6"
$reportPath = Join-Path $reports $ReportName
$loggerName = "com.aigroup.groupbuy"

function Import-DotEnv([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { return }
    Get-Content -LiteralPath $Path | ForEach-Object {
        if ($_ -match '^\s*#' -or $_ -notmatch '=') { return }
        $key, $value = $_ -split '=', 2
        $key = $key.Trim()
        $value = $value.Trim().Trim('"')
        if ($key -and -not (Test-Path "env:$key")) {
            Set-Item -Path "env:$key" -Value $value
        }
    }
}

Import-DotEnv (Join-Path $root ".env")
if (-not $InternalToken) { $InternalToken = $env:AI_GROUP_INTERNAL_TOKEN }
if (-not $InternalToken) { throw "AI_GROUP_INTERNAL_TOKEN is required" }
if ($WarmupSeconds -lt 1 -or $SteadySeconds -lt 1 -or $Repeats -lt 1) {
    throw "WarmupSeconds, SteadySeconds, and Repeats must all be positive"
}
if ($ConcurrencyLevels.Count -eq 0 -or @($ConcurrencyLevels | Where-Object { $_ -lt 1 }).Count -gt 0) {
    throw "ConcurrencyLevels must contain positive integers"
}
if ($LockMaxAttempts -lt 1 -or $LockMaxAttempts -gt 10 -or $RetryBackoffMillis -lt 0 -or $RetryBackoffMillis -gt 2000) {
    throw "LockMaxAttempts must be 1..10 and RetryBackoffMillis must be 0..2000"
}
if ($RequestTimeoutSeconds -lt 1 -or $RequestTimeoutSeconds -gt 120) {
    throw "RequestTimeoutSeconds must be 1..120"
}
if ($OrderPrice -le 0) { throw "OrderPrice must be positive" }

New-Item -ItemType Directory -Path $reports -Force | Out-Null

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
    if ($mysqlExitCode -ne 0) { throw "mysql statement failed with exit code $mysqlExitCode" }
    return @($output)
}

function Initialize-LoadActivity {
    $sql = @"
INSERT INTO group_buy_market.group_buy_activity
    (activity_id, activity_name, discount_id, group_type, activity_type, take_limit_count,
     target, valid_time, status, start_time, end_time, tag_id, tag_scope)
SELECT $LoadActivityId, CONCAT(activity_name, ' load benchmark'), discount_id, group_type,
       activity_type, take_limit_count, target, valid_time, 1, start_time, end_time, NULL, NULL
FROM group_buy_market.group_buy_activity
WHERE activity_id = $SourceActivityId
ON DUPLICATE KEY UPDATE
    activity_name = VALUES(activity_name), discount_id = VALUES(discount_id),
    group_type = VALUES(group_type), activity_type = VALUES(activity_type),
    take_limit_count = VALUES(take_limit_count), target = VALUES(target),
    valid_time = VALUES(valid_time), status = 1,
    start_time = VALUES(start_time), end_time = VALUES(end_time),
    tag_id = NULL, tag_scope = NULL;
"@
    Invoke-Mysql $sql | Out-Null
    $count = [int](@(Invoke-Mysql "SELECT COUNT(*) FROM group_buy_market.group_buy_activity WHERE activity_id=$LoadActivityId AND status=1;")[0])
    if ($count -ne 1) { throw "unable to initialize dedicated load activity $LoadActivityId" }
    docker exec $RedisContainer redis-cli DEL "group_buy_market_com.aigroup.groupbuy.infrastructure.dao.po.GroupBuyActivity_$LoadActivityId" | Out-Null
}

function Remove-LoadData {
    Invoke-Mysql "DELETE FROM group_buy_market.notify_task WHERE activity_id=$LoadActivityId; DELETE FROM group_buy_market.group_buy_order_list WHERE activity_id=$LoadActivityId; DELETE FROM group_buy_market.group_buy_order WHERE activity_id=$LoadActivityId;" | Out-Null
    $pattern = "group_buy_market_team_stock_key_${LoadActivityId}_*"
    docker exec $RedisContainer sh -c "redis-cli --scan --pattern '$pattern' | xargs -r -n 500 redis-cli UNLINK >/dev/null" | Out-Null
}

function Get-StatusMap([string[]]$Names) {
    $quoted = ($Names | ForEach-Object { "'$_'" }) -join ','
    $lines = @(Invoke-Mysql "SHOW GLOBAL STATUS WHERE Variable_name IN ($quoted);")
    $map = [ordered]@{}
    foreach ($line in $lines) {
        $columns = $line.Split("`t")
        if ($columns.Length -eq 2) { $map[$columns[0]] = [double]$columns[1] }
    }
    return [pscustomobject]$map
}

function Get-RedisInfoMap {
    $lines = @(docker exec $RedisContainer redis-cli INFO stats cpu memory)
    $map = [ordered]@{}
    foreach ($line in $lines) {
        if ($line -match '^([^#][^:]+):(.+)$') {
            $name = $Matches[1]
            $value = $Matches[2].Trim()
            $number = 0.0
            if ([double]::TryParse($value, [Globalization.NumberStyles]::Float,
                    [Globalization.CultureInfo]::InvariantCulture, [ref]$number)) {
                $map[$name] = $number
            }
        }
    }
    return [pscustomobject]$map
}

function Get-Delta($After, $Before, [string]$Name) {
    if ($After.PSObject.Properties.Name -notcontains $Name -or $Before.PSObject.Properties.Name -notcontains $Name) {
        return $null
    }
    return [Math]::Round([double]$After.$Name - [double]$Before.$Name, 3)
}

function Get-PrometheusValue([string]$Text, [string]$MetricName, [ValidateSet("First", "Sum", "Max")] [string]$Mode = "First") {
    $matches = [regex]::Matches($Text, "(?m)^$([regex]::Escape($MetricName))(?:\{[^}]*\})?\s+([-+0-9.eE]+)\s*$")
    if ($matches.Count -eq 0) { return $null }
    $values = @($matches | ForEach-Object {
            [double]::Parse($_.Groups[1].Value, [Globalization.CultureInfo]::InvariantCulture)
        })
    if ($Mode -eq "Sum") { return [double](($values | Measure-Object -Sum).Sum) }
    if ($Mode -eq "Max") { return [double](($values | Measure-Object -Maximum).Maximum) }
    return [double]$values[0]
}

function Convert-ContainerMemoryToBytes([string]$Value) {
    if (-not $Value) { return $null }
    $used = ($Value -split '/')[0].Trim()
    if ($used -notmatch '^([0-9.]+)([KMGTP]i?B)$') { return $null }
    $number = [double]$Matches[1]
    $factor = switch ($Matches[2]) {
        "KiB" { 1KB }
        "MiB" { 1MB }
        "GiB" { 1GB }
        "TiB" { 1TB }
        "KB" { 1000 }
        "MB" { 1000000 }
        "GB" { 1000000000 }
        "TB" { 1000000000000 }
        default { 1 }
    }
    return [long]($number * $factor)
}

function Get-ContainerSamples {
    $rows = @(docker stats --no-stream --format '{{json .}}' $MysqlContainer $RedisContainer)
    $result = [ordered]@{}
    foreach ($row in $rows) {
        $item = $row | ConvertFrom-Json
        $cpu = [double]($item.CPUPerc.TrimEnd('%'))
        $result[$item.Name] = [pscustomobject]@{
            cpuPct = $cpu
            memoryBytes = Convert-ContainerMemoryToBytes $item.MemUsage
        }
    }
    return $result
}

function Get-ResourceSample {
    $prometheus = (Invoke-WebRequest -UseBasicParsing "$GroupBase/actuator/prometheus" -TimeoutSec 10).Content
    $containers = Get-ContainerSamples
    $processId = (Get-NetTCPConnection -State Listen -LocalPort 8091 -ErrorAction SilentlyContinue | Select-Object -First 1).OwningProcess
    $process = if ($processId) { Get-Process -Id $processId -ErrorAction SilentlyContinue } else { $null }
    $mysql = $containers[$MysqlContainer]
    $redis = $containers[$RedisContainer]
    return [pscustomobject]@{
        at = (Get-Date).ToUniversalTime().ToString("o")
        appProcessCpuPct = Get-PrometheusValue $prometheus "process_cpu_usage"
        systemCpuPct = Get-PrometheusValue $prometheus "system_cpu_usage"
        appWorkingSetBytes = if ($process) { [long]$process.WorkingSet64 } else { $null }
        appThreadCount = if ($process) { [int]$process.Threads.Count } else { $null }
        hikariActive = Get-PrometheusValue $prometheus "hikaricp_connections_active" "Sum"
        hikariPending = Get-PrometheusValue $prometheus "hikaricp_connections_pending" "Sum"
        hikariTimeoutCount = Get-PrometheusValue $prometheus "hikaricp_connections_timeout_total" "Sum"
        tomcatBusyThreads = Get-PrometheusValue $prometheus "tomcat_threads_busy_threads" "Sum"
        gcPauseCount = Get-PrometheusValue $prometheus "jvm_gc_pause_seconds_count" "Sum"
        gcPauseSeconds = Get-PrometheusValue $prometheus "jvm_gc_pause_seconds_sum" "Sum"
        mysqlCpuPct = if ($mysql) { $mysql.cpuPct } else { $null }
        mysqlMemoryBytes = if ($mysql) { $mysql.memoryBytes } else { $null }
        redisCpuPct = if ($redis) { $redis.cpuPct } else { $null }
        redisMemoryBytes = if ($redis) { $redis.memoryBytes } else { $null }
    }
}

function Get-MetricValues($Summary, [string]$Name) {
    $property = $Summary.metrics.PSObject.Properties[$Name]
    if (-not $property) { return $null }
    return $property.Value.values
}

function Get-Value($Object, [string]$Name, $Default = $null) {
    if ($null -ne $Object -and $Object.PSObject.Properties.Name -contains $Name) { return $Object.$Name }
    return $Default
}

function Get-Average($Values, [int]$Digits = 2) {
    $items = @($Values | Where-Object { $null -ne $_ })
    if ($items.Count -eq 0) { return $null }
    return [Math]::Round([double](($items | Measure-Object -Average).Average), $Digits)
}

function Get-Maximum($Values, [int]$Digits = 2) {
    $items = @($Values | Where-Object { $null -ne $_ })
    if ($items.Count -eq 0) { return $null }
    return [Math]::Round([double](($items | Measure-Object -Maximum).Maximum), $Digits)
}

function Get-StandardDeviation($Values, [int]$Digits = 2) {
    $items = @($Values | Where-Object { $null -ne $_ } | ForEach-Object { [double]$_ })
    if ($items.Count -lt 2) { return 0.0 }
    $mean = ($items | Measure-Object -Average).Average
    $sum = 0.0
    foreach ($value in $items) { $sum += [Math]::Pow($value - $mean, 2) }
    return [Math]::Round([Math]::Sqrt($sum / ($items.Count - 1)), $Digits)
}

function Get-RunId([int]$Concurrency, [int]$Repeat) {
    $random = [guid]::NewGuid().ToString("N").Substring(0, 3)
    return ("{0:x2}{1}{2}" -f [Math]::Min($Concurrency, 255), $Repeat, $random).Substring(0, 6)
}

function Invoke-LoadRun([int]$Concurrency, [int]$Repeat) {
    Remove-LoadData
    $runId = Get-RunId $Concurrency $Repeat
    $source = "p$runId"
    $summaryName = "group-load-k6-c${Concurrency}-r${Repeat}-${runId}.json"
    $samplesName = "group-load-resources-c${Concurrency}-r${Repeat}-${runId}.json"
    $stdoutPath = Join-Path $reports "group-load-k6-c${Concurrency}-r${Repeat}-${runId}.stdout.log"
    $stderrPath = Join-Path $reports "group-load-k6-c${Concurrency}-r${Repeat}-${runId}.stderr.log"
    $summaryPath = Join-Path $reports $summaryName
    $samplesPath = Join-Path $reports $samplesName
    $mysqlNames = @("Questions", "Com_commit", "Com_rollback", "Threads_created", "Aborted_connects", "Innodb_row_lock_waits", "Innodb_row_lock_time")
    $mysqlBefore = Get-StatusMap $mysqlNames
    $redisBefore = Get-RedisInfoMap
    $startedAt = (Get-Date).ToUniversalTime()

    $env:INTERNAL_TOKEN = $InternalToken
    $orderPriceInvariant = $OrderPrice.ToString([Globalization.CultureInfo]::InvariantCulture)
    $dockerArgs = @(
        "run", "--rm",
        "-e", "INTERNAL_TOKEN",
        "-e", "BASE_URL=http://host.docker.internal:8091",
        "-e", "RUN_ID=$runId",
        "-e", "CONCURRENCY=$Concurrency",
        "-e", "WARMUP_SECONDS=$WarmupSeconds",
        "-e", "STEADY_SECONDS=$SteadySeconds",
        "-e", "ACTIVITY_ID=$LoadActivityId",
        "-e", "GOODS_ID=$GoodsId",
        "-e", "ORDER_PRICE=$orderPriceInvariant",
        "-e", "LOCK_MAX_ATTEMPTS=$LockMaxAttempts",
        "-e", "RETRY_BACKOFF_MILLIS=$RetryBackoffMillis",
        "-e", "REQUEST_TIMEOUT_SECONDS=$RequestTimeoutSeconds",
        "-e", "REPORT_FILE=$summaryName",
        "-v", "${k6Scripts}:/scripts:ro",
        "-v", "${reports}:/results",
        $K6Image, "run", "--quiet", "--no-color", "--log-output=none", "/scripts/group-successful-lock.js"
    )
    Write-Host ("[{0}] start concurrency={1} repeat={2}, warmup={3}s steady={4}s" -f `
            (Get-Date -Format "HH:mm:ss"), $Concurrency, $Repeat, $WarmupSeconds, $SteadySeconds)
    $process = Start-Process -FilePath "docker" -ArgumentList $dockerArgs -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
    $samples = [System.Collections.Generic.List[object]]::new()
    $sampleNo = 0
    $sampleFailures = 0
    while (-not $process.HasExited) {
        try {
            $sample = Get-ResourceSample
            $samples.Add($sample)
            $sampleNo++
            if ($sampleNo % [Math]::Max(1, [int](60 / $SampleIntervalSeconds)) -eq 0) {
                Write-Host ("  elapsed={0:n0}s appCPU={1:p1} mysqlCPU={2:n1}% hikari={3}/{4} tomcatBusy={5}" -f `
                        ((Get-Date).ToUniversalTime() - $startedAt).TotalSeconds,
                        $sample.appProcessCpuPct, $sample.mysqlCpuPct, $sample.hikariActive,
                        $sample.hikariPending, $sample.tomcatBusyThreads)
            }
        } catch {
            $sampleFailures++
            Write-Warning "resource sample failed: $($_.Exception.Message)"
        }
        Start-Sleep -Seconds $SampleIntervalSeconds
        $process.Refresh()
    }
    $process.WaitForExit()
    try { $samples.Add((Get-ResourceSample)) } catch {
        $sampleFailures++
        Write-Warning "final resource sample failed"
    }
    $finishedAt = (Get-Date).ToUniversalTime()
    $samples | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $samplesPath -Encoding utf8
    if (-not (Test-Path -LiteralPath $summaryPath)) {
        throw "k6 did not create summary $summaryPath (exit=$($process.ExitCode))"
    }

    $summary = Get-Content -Raw -LiteralPath $summaryPath | ConvertFrom-Json
    $steadyDuration = Get-MetricValues $summary "successful_lock_duration{phase:steady}"
    $steadySuccess = Get-MetricValues $summary "lock_business_success{phase:steady}"
    $steadyBusinessFailure = Get-MetricValues $summary "lock_business_failure{phase:steady}"
    $steadyTransportFailure = Get-MetricValues $summary "lock_transport_failure{phase:steady}"
    $steadyConnectionRefused = Get-MetricValues $summary "lock_transport_connection_refused{phase:steady}"
    $steadyTimeout = Get-MetricValues $summary "lock_transport_timeout{phase:steady}"
    $steadyHttpError = Get-MetricValues $summary "lock_transport_http_error{phase:steady}"
    $steadyOtherTransportError = Get-MetricValues $summary "lock_transport_other_error{phase:steady}"
    $steadyLockAttempts = Get-MetricValues $summary "lock_attempts{phase:steady}"
    $steadyQueryAttempts = Get-MetricValues $summary "lock_result_query_attempts{phase:steady}"
    $steadyAmbiguousOutcomes = Get-MetricValues $summary "lock_ambiguous_outcomes{phase:steady}"
    $steadyQueryRecoveries = Get-MetricValues $summary "lock_query_recoveries{phase:steady}"
    $steadyRetryRecoveries = Get-MetricValues $summary "lock_retry_recoveries{phase:steady}"
    $steadyQueryTransportFailures = Get-MetricValues $summary "lock_result_query_transport_failures{phase:steady}"
    $steadyLocks = Get-MetricValues $summary "successful_locks{phase:steady}"
    $allLocks = Get-MetricValues $summary "successful_locks"
    $allLockAttempts = Get-MetricValues $summary "lock_attempts"
    $allQueryAttempts = Get-MetricValues $summary "lock_result_query_attempts"
    $allQueryRecoveries = Get-MetricValues $summary "lock_query_recoveries"
    $allRetryRecoveries = Get-MetricValues $summary "lock_retry_recoveries"
    if (-not $steadyDuration -or -not $steadySuccess -or -not $steadyLocks -or -not $steadyLockAttempts) {
        throw "k6 summary is missing steady-state tagged metrics"
    }

    $totalSuccesses = [long](Get-Value $allLocks "count" 0)
    $steadySuccesses = [long](Get-Value $steadyLocks "count" 0)
    $warmupSuccesses = $totalSuccesses - $steadySuccesses
    $dbLine = @(Invoke-Mysql "SELECT COUNT(*),COUNT(DISTINCT team_id),COUNT(DISTINCT order_id),COUNT(DISTINCT out_trade_no) FROM group_buy_market.group_buy_order_list WHERE activity_id=$LoadActivityId AND source='$source';")[0]
    $dbColumns = $dbLine.Split("`t")
    $dbRows = [long]$dbColumns[0]
    $distinctTeams = [long]$dbColumns[1]
    $distinctOrders = [long]$dbColumns[2]
    $distinctTrades = [long]$dbColumns[3]
    $teamRows = [long](@(Invoke-Mysql "SELECT COUNT(*) FROM group_buy_market.group_buy_order WHERE activity_id=$LoadActivityId AND source='$source';")[0])
    $commitOutcomeDelta = $dbRows - $totalSuccesses
    $clientDatabaseReconciled = $commitOutcomeDelta -eq 0
    $internalUniqueState = $distinctTeams -eq $dbRows -and $distinctOrders -eq $dbRows `
        -and $distinctTrades -eq $dbRows -and $teamRows -eq $dbRows
    $uniqueState = $clientDatabaseReconciled -and $internalUniqueState
    if (-not $clientDatabaseReconciled) {
        Write-Warning "client/database outcome mismatch run=$runId successes=$totalSuccesses rows=$dbRows delta=$commitOutcomeDelta"
    }
    if (-not $internalUniqueState) {
        Write-Warning "database internal uniqueness mismatch run=$runId rows=$dbRows teams=$distinctTeams orders=$distinctOrders trades=$distinctTrades teamRows=$teamRows"
    }

    $mysqlAfter = Get-StatusMap $mysqlNames
    $redisAfter = Get-RedisInfoMap
    $gcCountFirst = if ($samples.Count) { $samples[0].gcPauseCount } else { $null }
    $gcCountLast = if ($samples.Count) { $samples[-1].gcPauseCount } else { $null }
    $gcSecondsFirst = if ($samples.Count) { $samples[0].gcPauseSeconds } else { $null }
    $gcSecondsLast = if ($samples.Count) { $samples[-1].gcPauseSeconds } else { $null }
    $run = [ordered]@{
        runId = $runId
        concurrency = $Concurrency
        repeat = $Repeat
        startedAt = $startedAt.ToString("o")
        finishedAt = $finishedAt.ToString("o")
        wallClockSeconds = [Math]::Round(($finishedAt - $startedAt).TotalSeconds, 1)
        warmupSeconds = $WarmupSeconds
        steadySeconds = $SteadySeconds
        k6ExitCode = $process.ExitCode
        warmupSuccessfulLocks = $warmupSuccesses
        steadySuccessfulLocks = $steadySuccesses
        steadySuccessfulLockQps = [Math]::Round($steadySuccesses / [double]$SteadySeconds, 2)
        steadyBusinessSuccessRatePct = [Math]::Round(100.0 * [double](Get-Value $steadySuccess "rate" 0), 4)
        steadyBusinessFailureRatePct = [Math]::Round(100.0 * [double](Get-Value $steadyBusinessFailure "rate" 0), 4)
        steadyTransportFailureRatePct = [Math]::Round(100.0 * [double](Get-Value $steadyTransportFailure "rate" 0), 4)
        recovery = [ordered]@{
            totalLockAttempts = [long](Get-Value $allLockAttempts "count" 0)
            steadyLockAttempts = [long](Get-Value $steadyLockAttempts "count" 0)
            totalQueryAttempts = [long](Get-Value $allQueryAttempts "count" 0)
            steadyQueryAttempts = [long](Get-Value $steadyQueryAttempts "count" 0)
            steadyAmbiguousOutcomes = [long](Get-Value $steadyAmbiguousOutcomes "count" 0)
            totalQueryRecoveries = [long](Get-Value $allQueryRecoveries "count" 0)
            steadyQueryRecoveries = [long](Get-Value $steadyQueryRecoveries "count" 0)
            totalRetryRecoveries = [long](Get-Value $allRetryRecoveries "count" 0)
            steadyRetryRecoveries = [long](Get-Value $steadyRetryRecoveries "count" 0)
            steadyQueryTransportFailures = [long](Get-Value $steadyQueryTransportFailures "count" 0)
        }
        latencyMs = [ordered]@{
            average = [Math]::Round([double](Get-Value $steadyDuration "avg" 0), 2)
            p50 = [Math]::Round([double](Get-Value $steadyDuration "med" 0), 2)
            p90 = [Math]::Round([double](Get-Value $steadyDuration "p(90)" 0), 2)
            p95 = [Math]::Round([double](Get-Value $steadyDuration "p(95)" 0), 2)
            p99 = [Math]::Round([double](Get-Value $steadyDuration "p(99)" 0), 2)
            max = [Math]::Round([double](Get-Value $steadyDuration "max" 0), 2)
        }
        database = [ordered]@{
            rows = $dbRows
            clientConfirmedSuccesses = $totalSuccesses
            clientDatabaseReconciled = $clientDatabaseReconciled
            commitOutcomeDelta = $commitOutcomeDelta
            unconfirmedCommittedRows = [Math]::Max(0, $commitOutcomeDelta)
            missingCommittedRows = [Math]::Max(0, -$commitOutcomeDelta)
            distinctTeams = $distinctTeams
            distinctOrders = $distinctOrders
            distinctTrades = $distinctTrades
            internalUniqueState = $internalUniqueState
            uniqueState = $uniqueState
            questions = Get-Delta $mysqlAfter $mysqlBefore "Questions"
            commits = Get-Delta $mysqlAfter $mysqlBefore "Com_commit"
            rollbacks = Get-Delta $mysqlAfter $mysqlBefore "Com_rollback"
            threadsCreated = Get-Delta $mysqlAfter $mysqlBefore "Threads_created"
            abortedConnects = Get-Delta $mysqlAfter $mysqlBefore "Aborted_connects"
            rowLockWaits = Get-Delta $mysqlAfter $mysqlBefore "Innodb_row_lock_waits"
            rowLockTimeMs = Get-Delta $mysqlAfter $mysqlBefore "Innodb_row_lock_time"
        }
        redis = [ordered]@{
            commands = Get-Delta $redisAfter $redisBefore "total_commands_processed"
            rejectedConnections = Get-Delta $redisAfter $redisBefore "rejected_connections"
            keyspaceHits = Get-Delta $redisAfter $redisBefore "keyspace_hits"
            keyspaceMisses = Get-Delta $redisAfter $redisBefore "keyspace_misses"
        }
        resources = [ordered]@{
            samples = $samples.Count
            sampleFailures = $sampleFailures
            appCpuAveragePct = [Math]::Round(100.0 * [double](Get-Average @($samples.appProcessCpuPct) 4), 2)
            appCpuMaxPct = [Math]::Round(100.0 * [double](Get-Maximum @($samples.appProcessCpuPct) 4), 2)
            systemCpuAveragePct = [Math]::Round(100.0 * [double](Get-Average @($samples.systemCpuPct) 4), 2)
            systemCpuMaxPct = [Math]::Round(100.0 * [double](Get-Maximum @($samples.systemCpuPct) 4), 2)
            appWorkingSetMaxBytes = [long](Get-Maximum @($samples.appWorkingSetBytes) 0)
            appThreadCountMax = [int](Get-Maximum @($samples.appThreadCount) 0)
            hikariActiveMax = Get-Maximum @($samples.hikariActive) 0
            hikariPendingMax = Get-Maximum @($samples.hikariPending) 0
            tomcatBusyThreadsMax = Get-Maximum @($samples.tomcatBusyThreads) 0
            mysqlCpuAveragePct = Get-Average @($samples.mysqlCpuPct) 2
            mysqlCpuMaxPct = Get-Maximum @($samples.mysqlCpuPct) 2
            mysqlMemoryMaxBytes = [long](Get-Maximum @($samples.mysqlMemoryBytes) 0)
            redisCpuAveragePct = Get-Average @($samples.redisCpuPct) 2
            redisCpuMaxPct = Get-Maximum @($samples.redisCpuPct) 2
            redisMemoryMaxBytes = [long](Get-Maximum @($samples.redisMemoryBytes) 0)
            gcPauseCount = if ($null -ne $gcCountFirst -and $null -ne $gcCountLast) { [long]($gcCountLast - $gcCountFirst) } else { $null }
            gcPauseTimeMs = if ($null -ne $gcSecondsFirst -and $null -ne $gcSecondsLast) { [Math]::Round(1000.0 * ($gcSecondsLast - $gcSecondsFirst), 2) } else { $null }
        }
        transportFailureCounts = [ordered]@{
            connectionRefused = [long](Get-Value $steadyConnectionRefused "count" 0)
            timeout = [long](Get-Value $steadyTimeout "count" 0)
            httpError = [long](Get-Value $steadyHttpError "count" 0)
            other = [long](Get-Value $steadyOtherTransportError "count" 0)
        }
        artifacts = [ordered]@{
            k6Summary = $summaryName
            resourceSamples = $samplesName
            stdout = [IO.Path]::GetFileName($stdoutPath)
            stderr = [IO.Path]::GetFileName($stderrPath)
        }
    }
    Write-Host ("  result success={0} QPS={1} P95={2}ms P99={3}ms queryRecovery={4} retryRecovery={5} businessError={6}% transportError={7}% dbReconciled={8} internalUnique={9}" -f `
            $steadySuccesses, $run.steadySuccessfulLockQps, $run.latencyMs.p95,
            $run.latencyMs.p99, $run.recovery.steadyQueryRecoveries,
            $run.recovery.steadyRetryRecoveries, $run.steadyBusinessFailureRatePct,
            $run.steadyTransportFailureRatePct, $clientDatabaseReconciled, $internalUniqueState)
    Remove-LoadData
    Start-Sleep -Seconds ([Math]::Min(30, [Math]::Max(2, $SampleIntervalSeconds)))
    return [pscustomobject]$run
}

function Build-Aggregates($Runs) {
    $rows = @()
    foreach ($group in @($Runs | Group-Object concurrency | Sort-Object { [int]$_.Name })) {
        $items = @($group.Group)
        $qps = @($items.steadySuccessfulLockQps)
        $p95 = @($items | ForEach-Object { $_.latencyMs.p95 })
        $p99 = @($items | ForEach-Object { $_.latencyMs.p99 })
        $rows += [pscustomobject][ordered]@{
            concurrency = [int]$group.Name
            repeats = $items.Count
            steadySuccessfulLocks = [long](($items | Measure-Object steadySuccessfulLocks -Sum).Sum)
            qpsAverage = Get-Average $qps 2
            qpsMin = [Math]::Round([double](($qps | Measure-Object -Minimum).Minimum), 2)
            qpsMax = [Math]::Round([double](($qps | Measure-Object -Maximum).Maximum), 2)
            qpsStdDev = Get-StandardDeviation $qps 2
            qpsCoefficientOfVariationPct = if ((Get-Average $qps 4) -gt 0) {
                [Math]::Round(100.0 * (Get-StandardDeviation $qps 4) / (Get-Average $qps 4), 2)
            } else { $null }
            p95AverageMs = Get-Average $p95 2
            p95WorstMs = Get-Maximum $p95 2
            p99AverageMs = Get-Average $p99 2
            p99WorstMs = Get-Maximum $p99 2
            businessSuccessRateMinPct = [Math]::Round([double](($items.steadyBusinessSuccessRatePct | Measure-Object -Minimum).Minimum), 4)
            transportFailureRateMaxPct = [Math]::Round([double](($items.steadyTransportFailureRatePct | Measure-Object -Maximum).Maximum), 4)
            transportConnectionRefused = [long]((@($items | ForEach-Object { $_.transportFailureCounts.connectionRefused }) | Measure-Object -Sum).Sum)
            transportTimeouts = [long]((@($items | ForEach-Object { $_.transportFailureCounts.timeout }) | Measure-Object -Sum).Sum)
            transportHttpErrors = [long]((@($items | ForEach-Object { $_.transportFailureCounts.httpError }) | Measure-Object -Sum).Sum)
            transportOtherErrors = [long]((@($items | ForEach-Object { $_.transportFailureCounts.other }) | Measure-Object -Sum).Sum)
            lockAttempts = [long]((@($items | ForEach-Object { $_.recovery.steadyLockAttempts }) | Measure-Object -Sum).Sum)
            resultQueryAttempts = [long]((@($items | ForEach-Object { $_.recovery.steadyQueryAttempts }) | Measure-Object -Sum).Sum)
            ambiguousOutcomes = [long]((@($items | ForEach-Object { $_.recovery.steadyAmbiguousOutcomes }) | Measure-Object -Sum).Sum)
            queryRecoveries = [long]((@($items | ForEach-Object { $_.recovery.steadyQueryRecoveries }) | Measure-Object -Sum).Sum)
            retryRecoveries = [long]((@($items | ForEach-Object { $_.recovery.steadyRetryRecoveries }) | Measure-Object -Sum).Sum)
            resultQueryTransportFailures = [long]((@($items | ForEach-Object { $_.recovery.steadyQueryTransportFailures }) | Measure-Object -Sum).Sum)
            appCpuAveragePct = Get-Average @($items.resources.appCpuAveragePct) 2
            appCpuPeakPct = Get-Maximum @($items.resources.appCpuMaxPct) 2
            mysqlCpuAveragePct = Get-Average @($items.resources.mysqlCpuAveragePct) 2
            mysqlCpuPeakPct = Get-Maximum @($items.resources.mysqlCpuMaxPct) 2
            hikariActivePeak = Get-Maximum @($items.resources.hikariActiveMax) 0
            hikariPendingPeak = Get-Maximum @($items.resources.hikariPendingMax) 0
            tomcatBusyThreadsPeak = Get-Maximum @($items.resources.tomcatBusyThreadsMax) 0
            gcPauseCount = [long]((@($items | ForEach-Object { $_.resources.gcPauseCount }) | Measure-Object -Sum).Sum)
            gcPauseTimeMs = [Math]::Round([double]((@($items | ForEach-Object { $_.resources.gcPauseTimeMs }) | Measure-Object -Sum).Sum), 2)
            resourceSampleFailures = [long]((@($items | ForEach-Object { $_.resources.sampleFailures }) | Measure-Object -Sum).Sum)
            rowLockWaits = [long]((@($items | ForEach-Object { $_.database.rowLockWaits }) | Measure-Object -Sum).Sum)
            databaseUniqueStateAllRuns = @($items.database | Where-Object { -not $_.uniqueState }).Count -eq 0
            databaseInternalUniqueStateAllRuns = @($items.database | Where-Object { -not $_.internalUniqueState }).Count -eq 0
            clientDatabaseReconciledAllRuns = @($items.database | Where-Object { -not $_.clientDatabaseReconciled }).Count -eq 0
            unconfirmedCommittedRows = [long]((@($items | ForEach-Object { $_.database.unconfirmedCommittedRows }) | Measure-Object -Sum).Sum)
            missingCommittedRows = [long]((@($items | ForEach-Object { $_.database.missingCommittedRows }) | Measure-Object -Sum).Sum)
        }
    }
    return $rows
}

$health = Invoke-RestMethod -Method GET -Uri "$GroupBase/actuator/health" -TimeoutSec 5
if ($health.status -ne "UP") { throw "group service is not healthy at $GroupBase" }
docker image inspect $K6Image | Out-Null
if ($LASTEXITCODE -ne 0) { throw "k6 image is unavailable: $K6Image" }

$machine = Get-CimInstance Win32_ComputerSystem -ErrorAction SilentlyContinue
$processor = Get-CimInstance Win32_Processor -ErrorAction SilentlyContinue | Select-Object -First 1
$groupPid = (Get-NetTCPConnection -State Listen -LocalPort 8091 | Select-Object -First 1).OwningProcess
$groupProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$groupPid" -ErrorAction SilentlyContinue
$originalLogger = Invoke-RestMethod -Method GET -Uri "$GroupBase/actuator/loggers/$loggerName"
$runs = [System.Collections.Generic.List[object]]::new()

try {
    Initialize-LoadActivity
    Remove-LoadData
    Invoke-RestMethod -Method POST -Uri "$GroupBase/actuator/loggers/$loggerName" `
        -ContentType "application/json" -Body '{"configuredLevel":"WARN"}' | Out-Null
    foreach ($concurrency in $ConcurrencyLevels) {
        for ($repeat = 1; $repeat -le $Repeats; $repeat++) {
            $runs.Add((Invoke-LoadRun $concurrency $repeat))
        }
    }
} finally {
    try { Remove-LoadData } catch { Write-Warning "final load-data cleanup failed: $($_.Exception.Message)" }
    $restoreLevel = if ($originalLogger.configuredLevel) { $originalLogger.configuredLevel } else { $null }
    $restoreBody = @{ configuredLevel = $restoreLevel } | ConvertTo-Json -Compress
    try {
        Invoke-RestMethod -Method POST -Uri "$GroupBase/actuator/loggers/$loggerName" `
            -ContentType "application/json" -Body $restoreBody | Out-Null
    } catch { Write-Warning "unable to restore logger level" }
}

$aggregates = @(Build-Aggregates $runs)
$totalSteady = [long](($runs | Measure-Object steadySuccessfulLocks -Sum).Sum)
$report = [ordered]@{
    schemaVersion = 3
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    benchmarkType = "local-k6-steady-successful-new-team-lock"
    environment = [ordered]@{
        os = [Environment]::OSVersion.VersionString
        processorModel = if ($processor) { $processor.Name.Trim() } else { $null }
        logicalProcessors = [Environment]::ProcessorCount
        totalPhysicalMemoryBytes = if ($machine) { [long]$machine.TotalPhysicalMemory } else { $null }
        groupServicePid = $groupPid
        groupServiceCommand = if ($groupProcess) { $groupProcess.CommandLine } else { $null }
        groupServiceInstances = 1
        groupBase = $GroupBase
        mysql = "MySQL " + @(Invoke-Mysql "SELECT VERSION();")[0]
        redis = (docker exec $RedisContainer redis-cli INFO server | Select-String '^redis_version:' | ForEach-Object { $_.Line.Trim() })
        mysqlContainerLimits = "no explicit CPU or memory limit"
        redisContainerLimits = "no explicit CPU or memory limit"
        loadGenerator = "$K6Image in Docker Desktop on the same physical host"
        loggingDuringLoad = "$loggerName=WARN"
    }
    profile = [ordered]@{
        endpoint = "/api/v1/gbm/trade/lock_market_pay_order"
        workload = "Every logical request creates a new team. Ambiguous lock outcomes are resolved by querying the committed result before a bounded same-key retry. Only client-confirmed results with business code 0000 and teamId count as success."
        loadModel = "closed-loop constant virtual users"
        concurrencyLevels = $ConcurrencyLevels
        repeatsPerLevel = $Repeats
        warmupSecondsPerRun = $WarmupSeconds
        steadySecondsPerRun = $SteadySeconds
        resourceSampleIntervalSeconds = $SampleIntervalSeconds
        lockMaxAttempts = $LockMaxAttempts
        retryBackoffMillis = $RetryBackoffMillis
        requestTimeoutSeconds = $RequestTimeoutSeconds
        dedicatedActivityId = $LoadActivityId
        goodsId = $GoodsId
        orderPrice = $OrderPrice
        totalSteadySuccessfulLocks = $totalSteady
    }
    results = $aggregates
    runs = $runs
    metricSemantics = [ordered]@{
        qps = "Steady-phase client-confirmed logical results with business code 0000 and non-empty teamId divided by configured steady seconds."
        latency = "k6 client wall-clock duration for the complete logical lock operation, including result queries, retry backoff, and same-key retries."
        error = "Final logical transport failures and non-0000 business responses are reported separately and excluded from successful-lock QPS. Raw HTTP attempt failures can still be recovered by result query or same-key retry."
        recovery = "lockAttempts counts outbound lock calls; resultQueryAttempts counts post-ambiguity lookups; queryRecoveries and retryRecoveries identify which mechanism confirmed the committed result."
        consistency = "clientDatabaseReconciled compares all client-confirmed successes with committed rows. Positive commitOutcomeDelta means the server committed transactions whose responses the client did not confirm. internalUniqueState checks team/order/trade uniqueness independently."
        resources = "10-second samples from Spring Boot Prometheus metrics and Docker stats; averages are sampled averages, peaks are observed sample maxima."
    }
    limitations = @(
        "The k6 container, group-service, MySQL, and Redis share one physical Windows host, so this is a local single-host benchmark rather than a production capacity test.",
        "The workload measures successful new-team lock transactions; hot-team join contention and the complete payment-to-benefit workflow are separate tests.",
        "Closed-loop virtual users apply coordinated omission; use an independent fixed-arrival-rate test for an external capacity/SLO claim.",
        "Spring metrics share the business listener on port 8091; resource sample failures are reported because monitoring requests can be rejected at saturation."
    )
}
$report | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $reportPath -Encoding utf8

Write-Host ""
Write-Host "================ GROUP LOAD BENCHMARK ================"
foreach ($row in $aggregates) {
    Write-Host ("c={0}: QPS avg={1} sd={2} CV={3}% P95 avg/worst={4}/{5}ms P99 avg/worst={6}/{7}ms success min={8}% transport max={9}% reconciled={10}" -f `
            $row.concurrency, $row.qpsAverage, $row.qpsStdDev, $row.qpsCoefficientOfVariationPct,
            $row.p95AverageMs, $row.p95WorstMs, $row.p99AverageMs, $row.p99WorstMs,
            $row.businessSuccessRateMinPct, $row.transportFailureRateMaxPct,
            $row.clientDatabaseReconciledAllRuns)
}
Write-Host "steady successful locks: $totalSteady"
Write-Host "report: $reportPath"
Write-Host "======================================================"
