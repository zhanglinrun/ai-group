<#
Restart the local AI Agent with a clean, explicit environment assembled from
the repository root `.env`. This avoids accidentally inheriting stale
database credentials from an interactive shell.
#>
[CmdletBinding()]
param(
    [ValidateRange(1, 65535)]
    [int]$Port = 8090,
    [switch]$Wait
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$envFile = Join-Path $root ".env"
if (-not (Test-Path -LiteralPath $envFile -PathType Leaf)) {
    throw "Missing local environment file: $envFile"
}

$agentEnvironment = @{
    PATH = [string]$env:PATH
    SYSTEMROOT = [string]$env:SYSTEMROOT
    USERPROFILE = [string]$env:USERPROFILE
    LOCALAPPDATA = [string]$env:LOCALAPPDATA
    APPDATA = [string]$env:APPDATA
    TEMP = [string]$env:TEMP
    TMP = [string]$env:TMP
    JAVA_HOME = [string]$env:JAVA_HOME
    MAVEN_HOME = [string]$env:MAVEN_HOME
}

Get-Content -LiteralPath $envFile -Encoding UTF8 | ForEach-Object {
    if ($_ -match '^\s*#' -or $_ -notmatch '=') { return }
    $key, $value = $_ -split '=', 2
    $key = $key.Trim()
    if ($key) {
        $agentEnvironment[$key] = $value.Trim().Trim('"')
    }
}

$agentEnvironment["SERVER_PORT"] = [string]$Port
$agentEnvironment["SPRING_PROFILES_ACTIVE"] = "dev"
$agentEnvironment["POSTGRES_HOST"] = if ($agentEnvironment["POSTGRES_HOST"]) { $agentEnvironment["POSTGRES_HOST"] } else { "127.0.0.1" }
$agentEnvironment["POSTGRES_PORT"] = if ($agentEnvironment["POSTGRES_PORT"]) { $agentEnvironment["POSTGRES_PORT"] } else { "15432" }
$agentEnvironment["POSTGRES_DB"] = if ($agentEnvironment["POSTGRES_DB"]) { $agentEnvironment["POSTGRES_DB"] } else { "agent_memory" }
$agentEnvironment["POSTGRES_USER"] = if ($agentEnvironment["POSTGRES_USER"]) { $agentEnvironment["POSTGRES_USER"] } else { "agent" }
$agentEnvironment["SPRING_DATASOURCE_POSTGRES_PASSWORD"] = $agentEnvironment["POSTGRES_PASSWORD"]

if (Test-NetConnection -ComputerName 127.0.0.1 -Port $Port -InformationLevel Quiet) {
    throw "Port :$Port is already in use; stop the existing Agent before restarting it."
}

$runtimeRoot = if ($agentEnvironment["AI_GROUP_RUNTIME_DATA_ROOT"]) {
    [System.IO.Path]::GetFullPath($agentEnvironment["AI_GROUP_RUNTIME_DATA_ROOT"])
} else {
    Join-Path (Split-Path -Parent $root) "ai-group-runtime-data"
}
$logDirectory = Join-Path $runtimeRoot "logs"
New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$stdout = Join-Path $logDirectory "ai-agent-restart-$stamp.stdout.log"
$stderr = Join-Path $logDirectory "ai-agent-restart-$stamp.stderr.log"
$agentDirectory = Join-Path $root "ai-agent\ai-agent-app"

$process = Start-Process pwsh `
    -ArgumentList "-NoProfile", "-Command", "`$ErrorActionPreference = 'Stop'; mvn spring-boot:run -q" `
    -WorkingDirectory $agentDirectory `
    -Environment $agentEnvironment `
    -RedirectStandardOutput $stdout `
    -RedirectStandardError $stderr `
    -WindowStyle Hidden `
    -PassThru

if ($Wait) {
    $deadline = (Get-Date).AddMinutes(3)
    while ((Get-Date) -lt $deadline) {
        try {
            Invoke-RestMethod -Method Get -Uri "http://127.0.0.1:$Port/web/health" -TimeoutSec 3 | Out-Null
            break
        } catch {
            Start-Sleep -Seconds 2
        }
    }
    if (-not (Test-NetConnection -ComputerName 127.0.0.1 -Port $Port -InformationLevel Quiet)) {
        throw "AI Agent did not become ready; inspect $stdout and $stderr"
    }
}

Write-Host "AI Agent started with a clean environment (launcher PID $($process.Id))."
Write-Host "stdout: $stdout"
Write-Host "stderr: $stderr"
