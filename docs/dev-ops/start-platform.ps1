param(
    [switch]$StopPort8080Conflict,
    [switch]$KeepRunning,
    [ValidateRange(1, 65535)]
    [int]$MemberPort = 18082
)

$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $true
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$envFile = Join-Path $root ".env"
. (Join-Path $PSScriptRoot "mysql-init.ps1")

function Import-DotEnv($path) {
    if (-not (Test-Path $path)) { return }
    Get-Content -LiteralPath $path -Encoding UTF8 | ForEach-Object {
        if ($_ -match '^\s*#' -or $_ -notmatch '=') { return }
        $k, $v = $_ -split '=', 2
        $k = $k.Trim()
        $v = $v.Trim().Trim('"')
        if ($k -and -not [Environment]::GetEnvironmentVariable($k, "Process")) {
            Set-Item -Path "env:$k" -Value $v
        }
    }
}

Import-DotEnv $envFile
function Require-Secret([string]$name, [int]$minimumLength = 32) {
    $value = [Environment]::GetEnvironmentVariable($name, "Process")
    if (-not $value -or $value.Length -lt $minimumLength -or $value -match '(?i)change-me|replace-me|your-') {
        throw "$name must be set to a random value of at least $minimumLength characters in .env or the process environment"
    }
    return $value
}
function New-RandomSecret() {
    $bytes = New-Object byte[] 32
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    return [Convert]::ToBase64String($bytes)
}
$env:JWT_SECRET = Require-Secret "JWT_SECRET"
$env:AI_GROUP_INTERNAL_TOKEN = Require-Secret "AI_GROUP_INTERNAL_TOKEN"
$env:XXL_JOB_ACCESS_TOKEN = Require-Secret "XXL_JOB_ACCESS_TOKEN"
$env:XXL_JOB_ADMIN_PORT = if ($env:XXL_JOB_ADMIN_PORT) { $env:XXL_JOB_ADMIN_PORT } else { "18081" }
$env:XXL_JOB_ADMIN_ADDRESSES = if ($env:XXL_JOB_ADMIN_ADDRESSES) { $env:XXL_JOB_ADMIN_ADDRESSES } else { "http://127.0.0.1:$($env:XXL_JOB_ADMIN_PORT)" }
$env:AGENT_GROUP_REACTOR_TOOL_TOKEN = if ($env:AGENT_GROUP_REACTOR_TOOL_TOKEN) { $env:AGENT_GROUP_REACTOR_TOOL_TOKEN } else { New-RandomSecret }
$env:REACTOR_TOOL_TOKEN = $env:AGENT_GROUP_REACTOR_TOOL_TOKEN
$env:MYSQL_HOST = if ($env:MYSQL_HOST) { $env:MYSQL_HOST } else { "127.0.0.1" }
$env:MYSQL_PORT = if ($env:MYSQL_PORT) { $env:MYSQL_PORT } else { "13306" }
$env:MYSQL_USER = if ($env:MYSQL_USER) { $env:MYSQL_USER } else { "root" }
$env:MYSQL_ROOT_PASSWORD = Require-Secret "MYSQL_ROOT_PASSWORD"
$env:REDIS_HOST = if ($env:REDIS_HOST) { $env:REDIS_HOST } else { "127.0.0.1" }
$env:REDIS_PORT = if ($env:REDIS_PORT) { $env:REDIS_PORT } else { "16379" }
$env:REDIS_PASSWORD = Require-Secret "REDIS_PASSWORD"
$env:POSTGRES_PASSWORD = Require-Secret "POSTGRES_PASSWORD"
$env:MINIO_PORT = if ($env:MINIO_PORT) { $env:MINIO_PORT } else { "9000" }
$env:MINIO_CONSOLE_PORT = if ($env:MINIO_CONSOLE_PORT) { $env:MINIO_CONSOLE_PORT } else { "9001" }
$env:MINIO_ROOT_USER = if ($env:MINIO_ROOT_USER) { $env:MINIO_ROOT_USER } else { "agent" }
$env:MINIO_ROOT_PASSWORD = Require-Secret "MINIO_ROOT_PASSWORD"
$env:KAFKA_BOOTSTRAP_SERVERS = if ($env:KAFKA_BOOTSTRAP_SERVERS) { $env:KAFKA_BOOTSTRAP_SERVERS } else { "127.0.0.1:9092" }
$env:NACOS_HOST = if ($env:NACOS_HOST) { $env:NACOS_HOST } else { "127.0.0.1" }
$env:NACOS_PORT = if ($env:NACOS_PORT) { $env:NACOS_PORT } else { "8848" }
$env:NACOS_USERNAME = if ($env:NACOS_USERNAME) { $env:NACOS_USERNAME } else { "nacos" }
$env:NACOS_PASSWORD = if ($env:NACOS_PASSWORD) { $env:NACOS_PASSWORD } else { "nacos" }
$env:NACOS_AUTH_TOKEN = if ($env:NACOS_AUTH_TOKEN) { $env:NACOS_AUTH_TOKEN } else { New-RandomSecret }
$env:NACOS_AUTH_IDENTITY_KEY = if ($env:NACOS_AUTH_IDENTITY_KEY) { $env:NACOS_AUTH_IDENTITY_KEY } else { New-RandomSecret }
$env:NACOS_AUTH_IDENTITY_VALUE = if ($env:NACOS_AUTH_IDENTITY_VALUE) { $env:NACOS_AUTH_IDENTITY_VALUE } else { New-RandomSecret }
$env:SPRING_PROFILES_ACTIVE = if ($env:SPRING_PROFILES_ACTIVE) { $env:SPRING_PROFILES_ACTIVE } else { "dev" }

function Test-PortListening($port) {
    return [bool](netstat -ano | Select-String "LISTENING" | Select-String ":$port ")
}

function Stop-PortListener($port) {
    $line = netstat -ano | Select-String "LISTENING" | Select-String ":$port " | Select-Object -First 1
    if ($line -match "\s(\d+)\s*$") {
        $processId = [int]$Matches[1]
        Write-Host "Stopping process on :$port (PID=$processId)"
        Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
        Start-Sleep -Seconds 2
    }
}

if ($StopPort8080Conflict) {
    Stop-PortListener 8080
}

Write-Host "==> Docker infra"
$opsRoot = Join-Path $root "docs/dev-ops"
Start-DockerInfra -OpsRoot $opsRoot
Wait-MysqlReady
Wait-RedisReady

Write-Host "==> Init DB (idempotent)"
Invoke-Mysql "$root/auth-service/src/main/resources/schema.sql"
if ($env:SPRING_PROFILES_ACTIVE -in @("local", "dev")) {
    Invoke-Mysql "$root/docs/dev-ops/mysql/sql/auth_db/02-local-admin-seed.sql"
}
Invoke-Mysql "$root/member-service/src/main/resources/schema.sql"
Invoke-Mysql "$root/docs/dev-ops/mysql/sql/member_db/02-platform-schema-migrate.sql"
# CREATE TABLE IF NOT EXISTS 不会升级既有 quota_freeze，必须显式执行 durable settlement 增量迁移。
Invoke-Mysql "$root/docs/dev-ops/mysql/sql/member_db/03-durable-quota-settlement.sql"

Write-Host "==> Build services"
Push-Location "$root"
mvn clean install -DskipTests -q
Pop-Location

$services = @(
    @{ Name = "gateway-service"; Path = "$root/gateway-service"; Port = 8080 },
    @{ Name = "auth-service"; Path = "$root/auth-service"; Port = 8081 },
    @{ Name = "member-service"; Path = "$root/member-service"; Port = $MemberPort },
    @{ Name = "bff-service"; Path = "$root/bff-service"; Port = 8083 }
)

foreach ($svc in $services) {
    if (Test-PortListening $svc.Port) {
        if ($KeepRunning) {
            Write-Host "Skip $($svc.Name), port $($svc.Port) already in use"
            continue
        }
        throw "Port :$($svc.Port) is already in use. Stop the existing listener or use -KeepRunning to keep it."
    }
    Write-Host "Start $($svc.Name) on :$($svc.Port)"
    $serviceEnvironment = @{
        SERVER_PORT             = [string]$svc.Port
        JWT_SECRET              = [string]$env:JWT_SECRET
        AI_GROUP_INTERNAL_TOKEN = [string]$env:AI_GROUP_INTERNAL_TOKEN
        MYSQL_HOST              = [string]$env:MYSQL_HOST
        MYSQL_PORT              = [string]$env:MYSQL_PORT
        MYSQL_USER              = [string]$env:MYSQL_USER
        MYSQL_ROOT_PASSWORD     = [string]$env:MYSQL_ROOT_PASSWORD
        REDIS_HOST              = [string]$env:REDIS_HOST
        REDIS_PORT              = [string]$env:REDIS_PORT
        REDIS_PASSWORD          = [string]$env:REDIS_PASSWORD
        KAFKA_BOOTSTRAP_SERVERS  = [string]$env:KAFKA_BOOTSTRAP_SERVERS
        NACOS_HOST              = [string]$env:NACOS_HOST
        NACOS_PORT              = [string]$env:NACOS_PORT
        NACOS_USERNAME          = [string]$env:NACOS_USERNAME
        NACOS_PASSWORD          = [string]$env:NACOS_PASSWORD
        XXL_JOB_ADMIN_ADDRESSES = [string]$env:XXL_JOB_ADMIN_ADDRESSES
        XXL_JOB_ACCESS_TOKEN    = [string]$env:XXL_JOB_ACCESS_TOKEN
        SPRING_PROFILES_ACTIVE  = [string]$env:SPRING_PROFILES_ACTIVE
    }
    Start-Process pwsh `
        -ArgumentList "-NoProfile", "-Command", "`$ErrorActionPreference = 'Stop'; mvn spring-boot:run -q" `
        -WorkingDirectory $svc.Path `
        -Environment $serviceEnvironment `
        -WindowStyle Hidden
    Start-Sleep -Seconds 8
}

Write-Host "==> Smoke test (waiting for services)"
$ready = $false
for ($i = 0; $i -lt 40; $i++) {
    try {
        $h = Invoke-RestMethod -Method GET -Uri "http://127.0.0.1:8080/actuator/health" -TimeoutSec 3
        if ($h.status -eq "UP") { $ready = $true; break }
    } catch {}
    Start-Sleep -Seconds 3
}
if (-not $ready) { Write-Host "WARN: gateway health not UP yet, running smoke anyway" }
& pwsh -NoProfile -File "$root/docs/dev-ops/smoke-test.ps1"
& pwsh -NoProfile -File "$root/docs/dev-ops/smoke-benefit-event.ps1"
& pwsh -NoProfile -File "$root/docs/dev-ops/smoke-benefit-revoke.ps1"
if (Test-PortListening 8070) {
    & pwsh -NoProfile -File "$root/docs/dev-ops/smoke-security.ps1"
} else {
    Write-Host "Skip smoke-security.ps1 (pay :8070 not running)"
}
