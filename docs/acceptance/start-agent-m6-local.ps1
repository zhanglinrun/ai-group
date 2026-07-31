#Requires -Version 7.2
param(
    [string]$PostgresContainer = "ai-group-postgres",
    [ValidateRange(10, 300)]
    [int]$ReadyTimeoutSeconds = 180
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$envFile = Join-Path $root ".env"
$jar = Join-Path $root "ai-agent\ai-agent-app\target\ai-agent-app.jar"

if (-not (Test-Path -LiteralPath $envFile)) { throw "Missing local environment file: $envFile" }
if (-not (Test-Path -LiteralPath $jar)) { throw "Missing packaged Agent jar: $jar" }
if (Get-NetTCPConnection -LocalPort 8090 -State Listen -ErrorAction SilentlyContinue) {
    throw "Port 8090 is already in use; stop only the verified Agent listener before starting M6."
}

Get-Content -LiteralPath $envFile -Encoding UTF8 | ForEach-Object {
    if ($_ -match '^\s*#' -or $_ -notmatch '=') { return }
    $name, $value = $_ -split '=', 2
    $name = $name.Trim()
    if ($name) { Set-Item -Path "env:$name" -Value $value.Trim().Trim('"') }
}

# The password exists only in this launcher and its Agent child process; it is never persisted to the repo.
$runtimePostgresPassword = [guid]::NewGuid().ToString("N")
& docker exec $PostgresContainer psql -v ON_ERROR_STOP=1 -U agent -d agent_memory `
    -c "ALTER ROLE agent WITH PASSWORD '$runtimePostgresPassword';" | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Could not prepare the local Agent PostgreSQL role in $PostgresContainer" }

$env:POSTGRES_HOST = "127.0.0.1"
$env:POSTGRES_PORT = "15432"
$env:POSTGRES_DB = "agent_memory"
$env:POSTGRES_USER = "agent"
$env:POSTGRES_PASSWORD = $runtimePostgresPassword
$env:SPRING_PROFILES_ACTIVE = "dev"
$env:SPRING_AI_OPENAI_API_KEY = $env:AGENT_GROUP_LLM_API_KEY
$env:AGENT_GROUP_VISION_MODEL = if ($env:AGENT_GROUP_VISION_MODEL) { $env:AGENT_GROUP_VISION_MODEL } else { $env:AGENT_GROUP_LLM_CHAT_MODEL }
$env:AGENT_GROUP_MEMORY_SUMMARY_MODEL = if ($env:AGENT_GROUP_MEMORY_SUMMARY_MODEL) { $env:AGENT_GROUP_MEMORY_SUMMARY_MODEL } else { $env:AGENT_GROUP_LLM_CHAT_MODEL }

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$target = Join-Path $root "ai-agent\ai-agent-app\target"
$outLog = Join-Path $target "m6-live-agent-$timestamp.out.log"
$errLog = Join-Path $target "m6-live-agent-$timestamp.err.log"
$process = Start-Process -FilePath (Get-Command java -ErrorAction Stop).Source `
    -ArgumentList @("-jar", $jar) `
    -WorkingDirectory $root `
    -RedirectStandardOutput $outLog `
    -RedirectStandardError $errLog `
    -WindowStyle Hidden `
    -PassThru

$deadline = (Get-Date).AddSeconds($ReadyTimeoutSeconds)
while ((Get-Date) -lt $deadline -and -not $process.HasExited) {
    # Actuator is intentionally internal-token protected. The post-start armory loader is the
    # first point where the Agent runtime, its model catalog, and the persisted MCP registry are usable.
    if (Select-String -LiteralPath $outLog -Pattern "AI Agent 自动装配完成" -Quiet -ErrorAction SilentlyContinue) {
        [pscustomobject]@{ pid = $process.Id; stdout = $outLog; stderr = $errLog; status = "READY" }
        exit 0
    }
    Start-Sleep -Seconds 1
}

throw "Updated Agent did not become ready; pid=$($process.Id), stdout=$outLog, stderr=$errLog"
