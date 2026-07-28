#Requires -Version 7.0
param(
    [string]$GroupBase = "http://127.0.0.1:8091",
    [string]$InternalToken = $env:AI_GROUP_INTERNAL_TOKEN,
    [string]$MysqlContainer = "ai-group-mysql",
    [string]$MysqlPassword = $env:MYSQL_ROOT_PASSWORD,
    [string]$RedisContainer = "ai-group-redis",
    [string]$RedisPassword = $env:REDIS_PASSWORD,
    [int]$ActivityId = 100201,
    [string]$GoodsId = "9890002",
    [int]$JoinRequests = 100,
    [int]$Concurrency = 20,
    [string]$ReportName = "group-benchmark.json"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$reports = Join-Path $PSScriptRoot "reports"
$reportPath = Join-Path $reports $ReportName

if (-not $InternalToken) {
    $envFile = Join-Path $root ".env"
    if (Test-Path $envFile) {
        $tokenLine = Get-Content -LiteralPath $envFile | Where-Object { $_ -match '^AI_GROUP_INTERNAL_TOKEN=' } | Select-Object -First 1
        if ($tokenLine) {
            $InternalToken = ($tokenLine -split '=', 2)[1].Trim().Trim('"')
        }
    }
}
if (-not $InternalToken) {
    throw "AI_GROUP_INTERNAL_TOKEN or -InternalToken is required"
}
if (-not $MysqlPassword) { throw "MYSQL_ROOT_PASSWORD or -MysqlPassword is required" }
if (-not $RedisPassword) { throw "REDIS_PASSWORD or -RedisPassword is required" }

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

function Get-Percentile([long[]]$Values, [int]$Percentile) {
    $sorted = @($Values | Sort-Object)
    if ($sorted.Count -eq 0) { return 0 }
    $index = [Math]::Ceiling($Percentile / 100.0 * $sorted.Count) - 1
    return [long]$sorted[[Math]::Max(0, [Math]::Min($index, $sorted.Count - 1))]
}

function Invoke-ConcurrentPost([object[]]$Requests, [string]$Path, [int]$ThrottleLimit) {
    $base = $GroupBase
    $token = $InternalToken
    $started = [System.Diagnostics.Stopwatch]::StartNew()
    $results = @($Requests | ForEach-Object -Parallel {
        $request = $_
        $watch = [System.Diagnostics.Stopwatch]::StartNew()
        try {
            $response = Invoke-RestMethod `
                -Method POST `
                -Uri "$using:base$using:Path" `
                -Headers @{ "X-Internal-Token" = $using:token; "Content-Type" = "application/json" } `
                -Body ($request.body | ConvertTo-Json -Depth 6 -Compress) `
                -TimeoutSec 30
            $watch.Stop()
            [pscustomobject]@{
                index = $request.index
                transportOk = $true
                businessCode = [string]$response.code
                teamId = if ($response.data) { [string]$response.data.teamId } else { $null }
                orderId = if ($response.data) { [string]$response.data.orderId } else { $null }
                latencyMs = [long]$watch.ElapsedMilliseconds
                error = $null
            }
        } catch {
            $watch.Stop()
            [pscustomobject]@{
                index = $request.index
                transportOk = $false
                businessCode = $null
                teamId = $null
                orderId = $null
                latencyMs = [long]$watch.ElapsedMilliseconds
                error = $_.Exception.Message
            }
        }
    } -ThrottleLimit $ThrottleLimit)
    $started.Stop()
    return [pscustomobject]@{
        results = $results
        durationMs = [long]$started.ElapsedMilliseconds
        throughputQps = if ($started.ElapsedMilliseconds -gt 0) {
            [Math]::Round(1000.0 * $Requests.Count / $started.ElapsedMilliseconds, 1)
        } else { 0 }
    }
}

function Remove-BenchmarkData([string]$TeamId) {
    if (-not $TeamId -or $TeamId -notmatch '^[0-9]{8}$') { return }
    Invoke-Mysql "DELETE FROM group_buy_market.notify_task WHERE team_id = '$TeamId'; DELETE FROM group_buy_market.group_buy_order_list WHERE team_id = '$TeamId'; DELETE FROM group_buy_market.group_buy_order WHERE team_id = '$TeamId';" | Out-Null
    $pattern = "group_buy_market_team_stock_key_${ActivityId}_${TeamId}*"
    $keys = @(docker exec -e REDISCLI_AUTH=$RedisPassword $RedisContainer redis-cli --scan --pattern $pattern)
    foreach ($key in $keys) {
        if ($key -and $key.StartsWith("group_buy_market_team_stock_key_${ActivityId}_${TeamId}")) {
            docker exec -e REDISCLI_AUTH=$RedisPassword $RedisContainer redis-cli DEL $key | Out-Null
        }
    }
}

try {
    $health = Invoke-RestMethod -Method GET -Uri "$GroupBase/actuator/health" -TimeoutSec 5
    if ($health.status -ne "UP") { throw "group service health is not UP" }
} catch {
    throw "group service is not ready at $GroupBase`: $($_.Exception.Message)"
}

$activity = @(Invoke-Mysql "SELECT target FROM group_buy_market.group_buy_activity WHERE activity_id = $ActivityId AND status = 1 LIMIT 1;")
if ($activity.Count -ne 1) { throw "active group-buy activity $ActivityId not found" }
$targetCount = [int]$activity[0]
$runPrefix = "{0:D6}" -f (Get-Random -Minimum 100000 -Maximum 999999)
$teamId = $null
$source = "s01"
$channel = "c01"
$notifyUrl = "$GroupBase/api/v1/test/group_buy_notify"

New-Item -ItemType Directory -Path $reports -Force | Out-Null

try {
    $createBody = @{
        userId = "gb$($runPrefix)000"
        teamId = $null
        activityId = $ActivityId
        goodsId = $GoodsId
        source = $source
        channel = $channel
        outTradeNo = "{0}{1:D6}" -f $runPrefix, 0
        notifyConfigVO = @{ notifyType = "HTTP"; notifyUrl = $notifyUrl }
    }
    $createResponse = Invoke-RestMethod `
        -Method POST `
        -Uri "$GroupBase/api/v1/gbm/trade/lock_market_pay_order" `
        -Headers @{ "X-Internal-Token" = $InternalToken; "Content-Type" = "application/json" } `
        -Body ($createBody | ConvertTo-Json -Depth 6 -Compress) `
        -TimeoutSec 30
    if ([string]$createResponse.code -ne "0000" -or -not $createResponse.data.teamId) {
        throw "failed to create benchmark team: code=$($createResponse.code) info=$($createResponse.info)"
    }
    $teamId = [string]$createResponse.data.teamId

    $joinWork = @()
    for ($index = 1; $index -le $JoinRequests; $index++) {
        $joinWork += [pscustomobject]@{
            index = $index
            body = @{
                userId = "gb$runPrefix$('{0:D3}' -f $index)"
                teamId = $teamId
                activityId = $ActivityId
                goodsId = $GoodsId
                source = $source
                channel = $channel
                outTradeNo = "{0}{1:D6}" -f $runPrefix, $index
                notifyConfigVO = @{ notifyType = "HTTP"; notifyUrl = $notifyUrl }
            }
        }
    }
    $lockRun = Invoke-ConcurrentPost $joinWork "/api/v1/gbm/trade/lock_market_pay_order" $Concurrency
    $transportFailures = @($lockRun.results | Where-Object { -not $_.transportOk }).Count
    $successfulJoins = @($lockRun.results | Where-Object { $_.transportOk -and $_.businessCode -eq "0000" }).Count
    $businessRejections = @($lockRun.results | Where-Object { $_.transportOk -and $_.businessCode -ne "0000" }).Count
    $businessCodeDistribution = @($lockRun.results |
        Where-Object transportOk |
        Group-Object businessCode |
        Sort-Object Name |
        ForEach-Object { [ordered]@{ code = $_.Name; count = $_.Count } })

    $teamColumns = (@(Invoke-Mysql "SELECT target_count,complete_count,lock_count,status FROM group_buy_market.group_buy_order WHERE team_id = '$teamId';")[0]).Split("`t")
    $dbTarget = [int]$teamColumns[0]
    $completeBeforeSettlement = [int]$teamColumns[1]
    $lockCount = [int]$teamColumns[2]
    $teamRows = [int](@(Invoke-Mysql "SELECT COUNT(*) FROM group_buy_market.group_buy_order WHERE team_id = '$teamId';")[0])
    $orderRows = [int](@(Invoke-Mysql "SELECT COUNT(*) FROM group_buy_market.group_buy_order_list WHERE team_id = '$teamId';")[0])
    $distinctOrders = [int](@(Invoke-Mysql "SELECT COUNT(DISTINCT order_id) FROM group_buy_market.group_buy_order_list WHERE team_id = '$teamId';")[0])
    $distinctTrades = [int](@(Invoke-Mysql "SELECT COUNT(DISTINCT out_trade_no) FROM group_buy_market.group_buy_order_list WHERE team_id = '$teamId';")[0])

    if ($transportFailures -ne 0) { throw "lock phase had $transportFailures transport failures" }
    if ($lockCount -gt $dbTarget) { throw "oversell detected: lock=$lockCount target=$dbTarget" }
    if ($orderRows -ne $lockCount -or $distinctOrders -ne $orderRows -or $distinctTrades -ne $orderRows) {
        throw "lock/order uniqueness mismatch for team $teamId"
    }

    $orderLines = @(Invoke-Mysql "SELECT user_id,out_trade_no FROM group_buy_market.group_buy_order_list WHERE team_id = '$teamId' ORDER BY id;")
    $settlementWork = @()
    $settlementIndex = 0
    foreach ($line in $orderLines) {
        $columns = $line.Split("`t")
        for ($duplicate = 0; $duplicate -lt 2; $duplicate++) {
            $settlementIndex++
            $settlementWork += [pscustomobject]@{
                index = $settlementIndex
                body = @{
                    source = $source
                    channel = $channel
                    userId = $columns[0]
                    outTradeNo = $columns[1]
                    outTradeTime = (Get-Date).ToString("yyyy-MM-ddTHH:mm:ss.fffzzz")
                }
            }
        }
    }
    $settlementRun = Invoke-ConcurrentPost $settlementWork "/api/v1/gbm/trade/settlement_market_pay_order" $Concurrency
    $settlementTransportFailures = @($settlementRun.results | Where-Object { -not $_.transportOk }).Count
    if ($settlementTransportFailures -ne 0) {
        throw "settlement phase had $settlementTransportFailures transport failures"
    }

    $finalColumns = (@(Invoke-Mysql "SELECT target_count,complete_count,lock_count,status FROM group_buy_market.group_buy_order WHERE team_id = '$teamId';")[0]).Split("`t")
    $finalTarget = [int]$finalColumns[0]
    $finalComplete = [int]$finalColumns[1]
    $finalLock = [int]$finalColumns[2]
    $finalStatus = [int]$finalColumns[3]
    $completedOrderRows = [int](@(Invoke-Mysql "SELECT COUNT(*) FROM group_buy_market.group_buy_order_list WHERE team_id = '$teamId' AND status = 1;")[0])
    $oversoldUnits = [Math]::Max(0, $finalLock - $finalTarget)
    $duplicateTeamRows = [Math]::Max(0, $teamRows - 1)
    $duplicateSettlementEffects = [Math]::Max(0, $finalComplete - $completedOrderRows)
    $consistent = $finalLock -eq $orderRows `
        -and $finalComplete -eq $completedOrderRows `
        -and $finalComplete -le $finalLock `
        -and (($finalComplete -eq $finalTarget -and $finalStatus -eq 1) -or ($finalComplete -lt $finalTarget -and $finalStatus -eq 0))
    if (-not $consistent) { throw "final group state is inconsistent for team $teamId" }
    if ($oversoldUnits -ne 0 -or $duplicateTeamRows -ne 0 -or $duplicateSettlementEffects -ne 0) {
        throw "group invariant failed after duplicate settlement"
    }

    $machine = Get-CimInstance Win32_ComputerSystem -ErrorAction SilentlyContinue
    $processor = Get-CimInstance Win32_Processor -ErrorAction SilentlyContinue | Select-Object -First 1
    $report = [ordered]@{
        schemaVersion = 2
        generatedAt = (Get-Date).ToUniversalTime().ToString("o")
        benchmarkType = "local-single-host-concurrency-correctness-smoke"
        environment = [ordered]@{
            os = [System.Environment]::OSVersion.VersionString
            powershell = $PSVersionTable.PSVersion.ToString()
            processorModel = if ($processor) { $processor.Name.Trim() } else { $null }
            logicalProcessors = [System.Environment]::ProcessorCount
            totalPhysicalMemoryBytes = if ($machine) { [long]$machine.TotalPhysicalMemory } else { $null }
            mysql = "MySQL " + @(Invoke-Mysql "SELECT VERSION();")[0]
            redis = (docker exec -e REDISCLI_AUTH=$RedisPassword $RedisContainer redis-cli INFO server | Select-String '^redis_version:' | ForEach-Object { $_.Line.Trim() })
            groupBase = $GroupBase
            loadGeneratorPlacement = "same host as group-service"
            serviceTopology = "single group-service process with local Docker MySQL and Redis"
        }
        dataset = [ordered]@{
            activityId = $ActivityId
            targetCount = $targetCount
            joinRequests = $JoinRequests
            concurrency = $Concurrency
            loadModel = "closed-loop bounded-concurrency finite burst"
            warmupSeconds = 0
            steadyStateSeconds = 0
            repeatCount = 1
            settlementRequests = $settlementWork.Count
            duplicateSettlementAttemptsPerOrder = 2
        }
        results = [ordered]@{
            lockTransportSuccessRatePct = [Math]::Round(100.0 * ($JoinRequests - $transportFailures) / $JoinRequests, 1)
            lockTransportErrorRatePct = [Math]::Round(100.0 * $transportFailures / $JoinRequests, 1)
            successfulJoinResponses = $successfulJoins
            businessRejectedResponses = $businessRejections
            businessJoinSuccessRatePct = [Math]::Round(100.0 * $successfulJoins / $JoinRequests, 1)
            businessCodeDistribution = $businessCodeDistribution
            lockAttemptDurationMs = $lockRun.durationMs
            lockAttemptThroughputQps = $lockRun.throughputQps
            lockLatencyP50Ms = Get-Percentile @($lockRun.results.latencyMs) 50
            lockLatencyP95Ms = Get-Percentile @($lockRun.results.latencyMs) 95
            lockLatencyP99Ms = Get-Percentile @($lockRun.results.latencyMs) 99
            databaseLockCount = $finalLock
            oversoldUnits = $oversoldUnits
            distinctOrderRows = $distinctOrders
            distinctOutTradeNos = $distinctTrades
            duplicateTeamRows = $duplicateTeamRows
            settlementTransportSuccessRatePct = [Math]::Round(100.0 * ($settlementWork.Count - $settlementTransportFailures) / $settlementWork.Count, 1)
            settlementLatencyP99Ms = Get-Percentile @($settlementRun.results.latencyMs) 99
            finalCompleteCount = $finalComplete
            completedOrderRows = $completedOrderRows
            duplicateSettlementEffects = $duplicateSettlementEffects
            finalStateConsistent = $consistent
            terminalConsistencyRatePct = 100.0
        }
        methodology = "Create one finite-capacity team, issue one same-host bounded-concurrency burst with unique users/trade numbers, then submit every settlement twice concurrently. Verify Redis-gated lock capacity and MySQL team/order invariants. Attempt throughput and latency include business rejections after the team is full."
        claimBoundary = "Concurrency-correctness smoke only. Use oversell, uniqueness, idempotency, and terminal-consistency results; do not present attempt QPS or latency as service capacity or production performance."
        measurementLimitations = @(
            "Only successfulJoinResponses requests execute the successful lock path; remaining transport-success responses are business rejections.",
            "The load generator, service, MySQL, and Redis share one machine.",
            "There is no warmup, steady-state measurement window, independent load generator, repeated trial, or fixed-arrival-rate phase.",
            "CPU, GC, connection-pool, MySQL, and Redis utilization are not sampled."
        )
    }
    $report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $reportPath -Encoding utf8

    Write-Host "Group benchmark report: $reportPath"
    Write-Host ("Lock contention smoke: {0} attempts at concurrency {1}, success={2}, rejected={3}" -f `
            $JoinRequests, $Concurrency, $successfulJoins, $businessRejections)
    Write-Host ("All-attempt burst only: duration={0} ms, {1} attempts/s, P50/P95/P99={2}/{3}/{4} ms" -f `
            $report.results.lockAttemptDurationMs, $report.results.lockAttemptThroughputQps,
            $report.results.lockLatencyP50Ms, $report.results.lockLatencyP95Ms, $report.results.lockLatencyP99Ms)
    Write-Host ("Team: lock={0}/{1}, oversold={2}, duplicate teams={3}" -f `
            $finalLock, $finalTarget, $oversoldUnits, $duplicateTeamRows)
    Write-Host ("Settlement: {0} duplicate attempts, complete={1}, duplicate effects={2}, consistent={3}" -f `
            $settlementWork.Count, $finalComplete, $duplicateSettlementEffects, $consistent)
} finally {
    Remove-BenchmarkData $teamId
}
