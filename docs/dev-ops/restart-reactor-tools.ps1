<#
Restart the local runtime/tools service with the same root `.env` authority
used by the Agent. The script deliberately maps the Agent-facing token and
the local MinIO root credentials to runtime/tools' compatibility variables,
without writing any secret back to disk or emitting it to the terminal.
#>
[CmdletBinding()]
param(
    [ValidateRange(1, 65535)]
    [int]$Port = 1601,
    [switch]$Wait
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$envFile = Join-Path $root ".env"
if (-not (Test-Path -LiteralPath $envFile -PathType Leaf)) {
    throw "Missing local environment file: $envFile"
}

$runtimeEnvironment = @{
    PATH = [string]$env:PATH
    SYSTEMROOT = [string]$env:SYSTEMROOT
    USERPROFILE = [string]$env:USERPROFILE
    LOCALAPPDATA = [string]$env:LOCALAPPDATA
    APPDATA = [string]$env:APPDATA
    TEMP = [string]$env:TEMP
    TMP = [string]$env:TMP
}
Get-Content -LiteralPath $envFile -Encoding UTF8 | ForEach-Object {
    if ($_ -match '^\s*#' -or $_ -notmatch '=') { return }
    $key, $value = $_ -split '=', 2
    $key = $key.Trim()
    if ($key) {
        $runtimeEnvironment[$key] = $value.Trim().Trim('"')
    }
}

$toolToken = $runtimeEnvironment["AGENT_GROUP_REACTOR_TOOL_TOKEN"]
if (-not $toolToken) { $toolToken = $runtimeEnvironment["AI_GROUP_INTERNAL_TOKEN"] }
if (-not $toolToken) { $toolToken = $runtimeEnvironment["REACTOR_TOOL_TOKEN"] }
if (-not $toolToken) { throw "Missing runtime/tools internal token in $envFile" }
$runtimeEnvironment["REACTOR_TOOL_TOKEN"] = $toolToken
$runtimeEnvironment["MINIO_ACCESS_KEY"] = $runtimeEnvironment["MINIO_ROOT_USER"]
$runtimeEnvironment["MINIO_SECRET_KEY"] = $runtimeEnvironment["MINIO_ROOT_PASSWORD"]

$listeners = @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
foreach ($listener in $listeners) {
    $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId = $($listener.OwningProcess)"
    if (-not $processInfo -or $processInfo.CommandLine -notmatch "server\.py") {
        throw "Port :$Port is occupied by a process that is not the local runtime/tools server."
    }
    $parentInfo = Get-CimInstance Win32_Process -Filter "ProcessId = $($processInfo.ParentProcessId)"
    if ($parentInfo -and $parentInfo.CommandLine -match "server\.py") {
        Stop-Process -Id $parentInfo.ProcessId -Force -ErrorAction SilentlyContinue
    }
    Stop-Process -Id $processInfo.ProcessId -Force -ErrorAction SilentlyContinue
}

# Uvicorn may use a parent/worker pair.  Do not start a replacement until the
# previous listener has actually released the socket; otherwise the health
# probe can accidentally validate the old worker while the new process exits
# with an address-in-use error.
$releaseDeadline = (Get-Date).AddSeconds(30)
do {
    $remainingListeners = @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
    if ($remainingListeners.Count -eq 0) {
        break
    }
    Start-Sleep -Milliseconds 250
} while ((Get-Date) -lt $releaseDeadline)
if ($remainingListeners.Count -gt 0) {
    $owners = ($remainingListeners | ForEach-Object { $_.OwningProcess } | Sort-Object -Unique) -join ","
    throw "The previous runtime/tools listener on :$Port did not stop (PID(s): $owners)."
}

$runtimeRoot = if ($runtimeEnvironment["AI_GROUP_RUNTIME_DATA_ROOT"]) {
    [System.IO.Path]::GetFullPath($runtimeEnvironment["AI_GROUP_RUNTIME_DATA_ROOT"])
} else {
    Join-Path (Split-Path -Parent $root) "ai-group-runtime-data"
}
$logDirectory = Join-Path $runtimeRoot "logs"
New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$stdout = Join-Path $logDirectory "reactor-tools-restart-$stamp.stdout.log"
$stderr = Join-Path $logDirectory "reactor-tools-restart-$stamp.stderr.log"
$toolDirectory = Join-Path $root "ai-agent\runtime\tools"
$python = Join-Path $toolDirectory ".venv\Scripts\python.exe"

$process = Start-Process $python -ArgumentList "server.py", "--workers", "1" `
    -WorkingDirectory $toolDirectory -Environment $runtimeEnvironment `
    -RedirectStandardOutput $stdout -RedirectStandardError $stderr -WindowStyle Hidden -PassThru

if ($Wait) {
    $deadline = (Get-Date).AddMinutes(1)
    while ((Get-Date) -lt $deadline) {
        try {
            $health = Invoke-RestMethod -Method Get -Uri "http://127.0.0.1:$Port/health" -TimeoutSec 3
            if ($health.status -eq "UP") { break }
        } catch {
            Start-Sleep -Seconds 1
        }
    }
    if (-not (Test-NetConnection -ComputerName 127.0.0.1 -Port $Port -InformationLevel Quiet)) {
        throw "runtime/tools did not become ready; inspect $stdout and $stderr"
    }
}

Write-Host "runtime/tools started (launcher PID $($process.Id))"
Write-Host "stdout: $stdout"
Write-Host "stderr: $stderr"
