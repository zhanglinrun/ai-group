param(
    [switch]$StopPort8080Conflict,
    [switch]$KeepRunning
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$envFile = Join-Path $root ".env"
. (Join-Path $PSScriptRoot "mysql-init.ps1")

function Import-DotEnv($path) {
    if (-not (Test-Path $path)) { return }
    Get-Content $path | ForEach-Object {
        if ($_ -match '^\s*#' -or $_ -notmatch '=') { return }
        $k, $v = $_ -split '=', 2
        $k = $k.Trim()
        $v = $v.Trim().Trim('"')
        if ($k) { Set-Item -Path "env:$k" -Value $v }
    }
}

Import-DotEnv $envFile
$env:JWT_SECRET = if ($env:JWT_SECRET) { $env:JWT_SECRET } else { "ai-group-dev-jwt-secret-2026-k8m3p9x2v7n4q1w6-change-in-prod" }
$env:AI_GROUP_INTERNAL_TOKEN = if ($env:AI_GROUP_INTERNAL_TOKEN) { $env:AI_GROUP_INTERNAL_TOKEN } else { "ai-group-dev-internal-token-change-in-prod" }

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

Write-Host "==> Build services"
Push-Location "$root"
mvn clean install -DskipTests -q
Pop-Location

$services = @(
    @{ Name = "gateway-service"; Path = "$root/gateway-service"; Port = 8080 },
    @{ Name = "auth-service"; Path = "$root/auth-service"; Port = 8081 },
    @{ Name = "member-service"; Path = "$root/member-service"; Port = 8082 },
    @{ Name = "bff-service"; Path = "$root/bff-service"; Port = 8083 }
)

foreach ($svc in $services) {
    if (-not $KeepRunning) {
        Stop-PortListener $svc.Port
    } elseif (Test-PortListening $svc.Port) {
        Write-Host "Skip $($svc.Name), port $($svc.Port) already in use"
        continue
    }
    Write-Host "Start $($svc.Name) on :$($svc.Port)"
    $cmd = "Remove-Item Env:SERVER_PORT -ErrorAction SilentlyContinue; cd '$($svc.Path)'; `$env:JWT_SECRET='$env:JWT_SECRET'; `$env:AI_GROUP_INTERNAL_TOKEN='$env:AI_GROUP_INTERNAL_TOKEN'; mvn spring-boot:run -q"
    Start-Process powershell -ArgumentList "-NoExit", "-Command", $cmd -WindowStyle Minimized
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
powershell -File "$root/docs/dev-ops/smoke-test.ps1"
powershell -File "$root/docs/dev-ops/smoke-benefit-event.ps1"
powershell -File "$root/docs/dev-ops/smoke-benefit-revoke.ps1"
if (Test-PortListening 8070) {
    powershell -File "$root/docs/dev-ops/smoke-security.ps1"
} else {
    Write-Host "Skip smoke-security.ps1 (pay :8070 not running)"
}
