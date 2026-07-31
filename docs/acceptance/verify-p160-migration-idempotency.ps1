#Requires -Version 7.2
param(
    [string]$MysqlImage = "mysql:8.4"
)

$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $false
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$containerName = "researchpilot-p160-mysql-" + [guid]::NewGuid().ToString("N").Substring(0, 12)
$mysqlPassword = [Convert]::ToBase64String([guid]::NewGuid().ToByteArray()) + "P160!"

function Invoke-Docker([string[]]$Arguments) {
    & docker @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "docker command failed: $($Arguments -join ' ')"
    }
}

function Invoke-MySqlFile([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "SQL file missing: $Path"
    }
    Get-Content -LiteralPath $Path -Raw |
        & docker exec -i -e "MYSQL_PWD=$mysqlPassword" $containerName mysql "--user=root" | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "SQL failed: $Path"
    }
}

function Invoke-MySql([string]$Sql) {
    $Sql | & docker exec -i -e "MYSQL_PWD=$mysqlPassword" $containerName mysql "--user=root"
    if ($LASTEXITCODE -ne 0) {
        throw "MySQL probe failed"
    }
}

function Wait-MySqlReady {
    for ($attempt = 0; $attempt -lt 90; $attempt++) {
        "SELECT 1;" |
            & docker exec -i -e "MYSQL_PWD=$mysqlPassword" $containerName mysql "--user=root" *> $null
        if ($LASTEXITCODE -eq 0) {
            return
        }
        Start-Sleep -Seconds 1
    }
    throw "Ephemeral MySQL did not become ready: $containerName"
}

function Test-AgentAndMemberMigrations {
    $memberMigrations = Get-ChildItem -LiteralPath (Join-Path $root "docs/dev-ops/mysql/sql/member_db") -File -Filter "*.sql" |
        Sort-Object Name
    $agentMigrations = Get-ChildItem -LiteralPath (Join-Path $root "docs/dev-ops/mysql/sql/agent_db") -File -Filter "*.sql" |
        Where-Object { $_.Name -ne "02-dev-seed.sql" } |
        Sort-Object Name

    for ($pass = 1; $pass -le 2; $pass++) {
        Invoke-MySqlFile (Join-Path $root "auth-service/src/main/resources/schema.sql")
        Invoke-MySqlFile (Join-Path $root "docs/dev-ops/mysql/sql/auth_db/02-local-admin-seed.sql")
        Invoke-MySqlFile (Join-Path $root "member-service/src/main/resources/schema.sql")
        foreach ($migration in $memberMigrations) {
            Invoke-MySqlFile $migration.FullName
        }
        foreach ($migration in $agentMigrations) {
            Invoke-MySqlFile $migration.FullName
        }
        Invoke-MySqlFile (Join-Path $root "docs/dev-ops/mysql/sql/agent_db/02-dev-seed.sql")
        Invoke-MySqlFile (Join-Path $root "docs/dev-ops/mysql/sql/xxl_job/01-xxl_job.sql")
        Write-Host "Agent/Member migration pass $pass completed"
    }
    Invoke-MySql @"
SELECT COUNT(*) AS required_agent_tables
FROM information_schema.tables
WHERE table_schema = 'agent_db'
  AND table_name IN ('dialogue_run_ledger', 'tool_outbox', 'evidence_record');
SELECT COUNT(*) AS required_member_tables
FROM information_schema.tables
WHERE table_schema = 'member_db'
  AND table_name IN ('quota_freeze', 'quota_ledger');
"@
}

function Test-FrozenGroupAndPayMigrations {
    $groupRoot = Join-Path $root "group/docs/dev-ops/mysql/sql"
    $payRoot = Join-Path $root "s-pay-mall-ddd-market/docs/dev-ops/mysql/sql"
    Invoke-MySqlFile (Join-Path $groupRoot "2-29-group_buy_market.sql")
    Invoke-MySqlFile (Join-Path $payRoot "s-pay-mall-ddd-market.sql")
    $groupMigrations = Get-ChildItem -LiteralPath $groupRoot -File -Filter "*.sql" |
        Where-Object { $_.Name -ne "2-29-group_buy_market.sql" } |
        Sort-Object Name
    $payMigrations = Get-ChildItem -LiteralPath $payRoot -File -Filter "*.sql" |
        Where-Object { $_.Name -ne "s-pay-mall-ddd-market.sql" } |
        Sort-Object Name

    for ($pass = 1; $pass -le 2; $pass++) {
        foreach ($migration in $groupMigrations) {
            Invoke-MySqlFile $migration.FullName
        }
        foreach ($migration in $payMigrations) {
            Invoke-MySqlFile $migration.FullName
        }
        Invoke-MySqlFile (Join-Path $root "docs/dev-ops/mysql/sql/pay_db/01-benefit-event-bonus-migrate.sql")
        Write-Host "Frozen Group/Pay migration pass $pass completed"
    }
    Invoke-MySql @"
SELECT COUNT(*) AS required_group_tables
FROM information_schema.tables
WHERE table_schema = 'group_buy_market'
  AND table_name IN ('group_buy_order', 'group_buy_activity');
SELECT COUNT(*) AS required_pay_tables
FROM information_schema.tables
WHERE table_schema = 's_pay_mall_ddd_market'
  AND table_name IN ('pay_order', 'benefit_event');
"@
}

try {
    Invoke-Docker @("run", "-d", "--rm", "--name", $containerName, "-e", "MYSQL_ROOT_PASSWORD=$mysqlPassword", $MysqlImage)
    Wait-MySqlReady
    Test-AgentAndMemberMigrations
    Test-FrozenGroupAndPayMigrations
    Write-Host "P160 migration idempotency passed: all launcher migrations completed twice; frozen base dumps ran once."
} finally {
    & docker rm -f $containerName *> $null
}
