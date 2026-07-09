function Wait-RedisReady {
    param([int]$TimeoutSec = 60)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $prev = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        $pong = docker exec ai-group-redis redis-cli ping 2>&1
        $ErrorActionPreference = $prev
        if ($pong -match "PONG") {
            Write-Host "Redis is ready"
            return
        }
        Start-Sleep -Seconds 2
    }
    throw "Redis not ready after ${TimeoutSec}s"
}

function Wait-MysqlReady {
    param([int]$TimeoutSec = 120)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $prev = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        docker exec -e MYSQL_PWD=123456 ai-group-mysql mysqladmin ping -uroot -h 127.0.0.1 2>&1 | Out-Null
        $pingExit = $LASTEXITCODE
        $ErrorActionPreference = $prev
        if ($pingExit -eq 0) {
            Write-Host "MySQL is ready"
            return
        }
        Start-Sleep -Seconds 3
    }
    throw "MySQL not ready after ${TimeoutSec}s"
}

function Invoke-MysqlPipe {
    param(
        [Parameter(Mandatory = $true)][string]$InputText,
        [string]$Label = "mysql",
        [int]$MaxAttempts = 3
    )

    $lastExit = 1
    $lastDetail = ""

    for ($attempt = 1; $attempt -le $MaxAttempts; $attempt++) {
        $prev = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            $output = $InputText | docker exec -i -e MYSQL_PWD=123456 ai-group-mysql mysql -uroot --default-character-set=utf8mb4 2>&1
            $lastExit = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $prev
        }

        if ($lastExit -eq 0) {
            return
        }

        $lastDetail = ($output | Out-String).Trim()
        if ($lastDetail) {
            Write-Warning "[$Label] attempt ${attempt}/${MaxAttempts} failed (exit ${lastExit}): ${lastDetail}"
        } else {
            Write-Warning "[$Label] attempt ${attempt}/${MaxAttempts} failed (exit ${lastExit})"
        }

        if ($attempt -lt $MaxAttempts) {
            Start-Sleep -Seconds ([Math]::Min(5 * $attempt, 15))
            Wait-MysqlReady -TimeoutSec 30
        }
    }

    if ($lastDetail) {
        throw "[$Label] mysql command failed after ${MaxAttempts} attempts (exit ${lastExit}): ${lastDetail}"
    }
    throw "[$Label] mysql command failed after ${MaxAttempts} attempts (exit ${lastExit})"
}

function Invoke-Mysql {
    param([Parameter(Mandatory = $true)][string]$SqlPath)
    if (-not (Test-Path $SqlPath)) {
        Write-Host "Skip missing SQL: $SqlPath"
        return
    }
    $label = Split-Path $SqlPath -Leaf
    Write-Host "Apply SQL: $SqlPath"
    Invoke-MysqlPipe -InputText (Get-Content $SqlPath -Encoding UTF8 -Raw) -Label $label
}

function Invoke-MysqlPayBase {
    param([Parameter(Mandatory = $true)][string]$SqlPath)
    if (-not (Test-Path $SqlPath)) {
        Write-Host "Skip missing SQL: $SqlPath"
        return
    }
    Write-Host "Apply pay base SQL: $SqlPath"
    $sql = (Get-Content $SqlPath -Encoding UTF8 -Raw) -replace "s-pay-mall-ddd-market", "s_pay_mall_ddd_market"
    Invoke-MysqlPipe -InputText $sql -Label "s-pay-mall-ddd-market.sql"
}

function Invoke-MysqlStatement {
    param([Parameter(Mandatory = $true)][string]$Sql)
    Invoke-MysqlPipe -InputText $Sql -Label "sql-statement"
}

# 判断某个 schema.table 是否已存在（用于跳过会 DROP 重灌数据的全量转储脚本）。
function Test-MysqlSchemaInitialized {
    param(
        [Parameter(Mandatory = $true)][string]$Schema,
        [Parameter(Mandatory = $true)][string]$Table
    )
    $query = "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '$Schema' AND table_name = '$Table';"
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $output = $query | docker exec -i -e MYSQL_PWD=123456 ai-group-mysql mysql -uroot -N -B 2>$null
    $ErrorActionPreference = $prev
    $count = 0
    if ($output) {
        $line = ($output | Select-Object -Last 1).ToString().Trim()
        [int]::TryParse($line, [ref]$count) | Out-Null
    }
    return $count -gt 0
}

# group/pay 的建表脚本是 Sequel Ace 全量转储（DROP TABLE + 重灌演示数据）。
# 首次初始化时执行；若目标表已存在则跳过，避免每次重启清空拼团/订单数据。
function Invoke-MysqlDumpOnce {
    param(
        [Parameter(Mandatory = $true)][string]$SqlPath,
        [Parameter(Mandatory = $true)][string]$Schema,
        [Parameter(Mandatory = $true)][string]$MarkerTable,
        [switch]$PayBase
    )
    if (-not (Test-Path $SqlPath)) {
        Write-Host "Skip missing SQL: $SqlPath"
        return
    }
    if (Test-MysqlSchemaInitialized -Schema $Schema -Table $MarkerTable) {
        Write-Host "Skip destructive dump (already initialized): $Schema.$MarkerTable -> $SqlPath"
        return
    }
    if ($PayBase) {
        Invoke-MysqlPayBase $SqlPath
    } else {
        Invoke-Mysql $SqlPath
    }
}

function Start-DockerInfra {
    param(
        [Parameter(Mandatory = $true)][string]$OpsRoot,
        [switch]$IncludeObservability
    )
    Push-Location $OpsRoot
    $composeArgs = @("compose", "-f", "docker-compose-platform.yml")
    if ($IncludeObservability) {
        $composeArgs += @("-f", "docker-compose-observability.yml")
    }
    $composeArgs += @("up", "-d", "--remove-orphans")
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & docker @composeArgs 2>&1 | ForEach-Object { Write-Host $_ }
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = $prev
    Pop-Location
    if ($exitCode -ne 0) {
        throw "docker compose failed (exit $exitCode)"
    }
}
