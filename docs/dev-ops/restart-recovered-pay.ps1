# Restart only the externally recovered Pay JAR. This is a local recovery helper;
# it deliberately never builds, writes, or changes files under frozen source.
param(
    [string]$ArtifactRoot = $env:AI_GROUP_FROZEN_ARTIFACT_ROOT
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$envFile = Join-Path $root ".env"

function Import-DotEnv([string]$path) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return }
    Get-Content -LiteralPath $path -Encoding UTF8 | ForEach-Object {
        if ($_ -match '^\s*#' -or $_ -notmatch '=') { return }
        $key, $value = $_ -split '=', 2
        $key = $key.Trim()
        if ($key -and -not [Environment]::GetEnvironmentVariable($key, "Process")) {
            Set-Item -Path "env:$key" -Value $value.Trim().Trim('"')
        }
    }
}

function Resolve-ArtifactRoot([string]$configuredRoot) {
    if ($configuredRoot) {
        return [System.IO.Path]::GetFullPath($configuredRoot)
    }
    $recoveryParent = Join-Path (Split-Path -Parent $root) "ai-group-generated-recovery"
    $candidate = Get-ChildItem -LiteralPath $recoveryParent -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like "p130-generated-*" } |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1
    if (-not $candidate) {
        throw "No recovered Group/Pay artifact directory was found outside the repository."
    }
    return $candidate.FullName
}

Import-DotEnv $envFile
$artifactRoot = Resolve-ArtifactRoot $ArtifactRoot
$jarPath = Join-Path $artifactRoot "s-pay-mall-ddd-market\s-pay-mall-ddd-app\target\s-pay-mall-ddd-app.jar"
if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) {
    throw "Recovered Pay JAR is missing: $jarPath"
}

$runtimeRoot = if ($env:AI_GROUP_RUNTIME_DATA_ROOT) {
    [System.IO.Path]::GetFullPath($env:AI_GROUP_RUNTIME_DATA_ROOT)
} else {
    Join-Path (Split-Path -Parent $root) "ai-group-runtime-data"
}
$logs = Join-Path $runtimeRoot "logs"
$workingDirectory = Join-Path $runtimeRoot "pay-service"
New-Item -ItemType Directory -Path $logs, $workingDirectory -Force | Out-Null

$listener = Get-NetTCPConnection -State Listen -LocalPort 8070 -ErrorAction SilentlyContinue |
    Select-Object -First 1
if ($listener) {
    $running = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
    if (-not $running -or $running.CommandLine -notmatch 's-pay-mall-ddd-app\.jar') {
        throw "Port 8070 is occupied by a non-recovered-Pay process; refusing to stop it."
    }
    Stop-Process -Id $listener.OwningProcess -ErrorAction Stop
    $deadline = (Get-Date).AddSeconds(30)
    while ((Get-Date) -lt $deadline) {
        if (-not (Get-NetTCPConnection -State Listen -LocalPort 8070 -ErrorAction SilentlyContinue)) { break }
        Start-Sleep -Milliseconds 250
    }
    if (Get-NetTCPConnection -State Listen -LocalPort 8070 -ErrorAction SilentlyContinue) {
        throw "The verified Pay process did not release port 8070."
    }
}

$env:SERVER_PORT = "8070"
$env:SPRING_PROFILES_ACTIVE = "dev"
$env:AI_GROUP_RUNTIME_DATA_ROOT = $runtimeRoot
$env:XXL_JOB_EXECUTOR_APPNAME = "pay"
$env:XXL_JOB_EXECUTOR_PORT = "9998"
$env:XXL_JOB_EXECUTOR_IP = "0.0.0.0"
$advertiseHost = if ($env:XXL_JOB_EXECUTOR_ADVERTISE_HOST) {
    [string]$env:XXL_JOB_EXECUTOR_ADVERTISE_HOST
} else {
    "host.docker.internal"
}
$env:XXL_JOB_EXECUTOR_ADDRESS = "http://${advertiseHost}:9998/"
$env:XXL_JOB_LOGPATH = Join-Path $runtimeRoot "logs\xxl-job\pay"
New-Item -ItemType Directory -Path $env:XXL_JOB_LOGPATH -Force | Out-Null

$stdoutLog = Join-Path $logs "pay-service.stdout.log"
$stderrLog = Join-Path $logs "pay-service.stderr.log"
Remove-Item -LiteralPath $stdoutLog, $stderrLog -Force -ErrorAction SilentlyContinue
$java = (Get-Command java -ErrorAction Stop).Source
$process = Start-Process -FilePath $java -ArgumentList @("-jar", $jarPath) `
    -WorkingDirectory $workingDirectory -RedirectStandardOutput $stdoutLog `
    -RedirectStandardError $stderrLog -WindowStyle Hidden -PassThru

$deadline = (Get-Date).AddSeconds(90)
while ((Get-Date) -lt $deadline) {
    try {
        Invoke-WebRequest -Method GET -Uri "http://127.0.0.1:8070/api/v1/alipay/create_pay_order" `
            -TimeoutSec 3 -UseBasicParsing | Out-Null
    } catch {
        if ($_.Exception.Response -and [int]$_.Exception.Response.StatusCode -in @(401, 403, 405)) {
            Write-Host "Recovered Pay restarted (PID=$($process.Id)); XXL executor=$($env:XXL_JOB_EXECUTOR_ADDRESS)"
            Write-Host "Logs: $stdoutLog / $stderrLog"
            exit 0
        }
    }
    Start-Sleep -Seconds 2
}
throw "Recovered Pay did not become reachable; inspect $stdoutLog and $stderrLog"
