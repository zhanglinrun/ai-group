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
        if ($k) { Set-Item -Path "env:$k" -Value $v }
    }
}

Import-DotEnv $envFile
$env:JWT_SECRET = if ($env:JWT_SECRET) { $env:JWT_SECRET } else { "change-me-to-a-long-random-secret" }
$env:AI_GROUP_INTERNAL_TOKEN = if ($env:AI_GROUP_INTERNAL_TOKEN) { $env:AI_GROUP_INTERNAL_TOKEN } else { "change-me-to-a-long-random-internal-token" }

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
