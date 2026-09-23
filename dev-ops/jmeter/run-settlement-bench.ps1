param(
    [ValidateRange(1, 10000)][int]$Concurrency = 500,
    [ValidateRange(1, 10000)][int]$Orders = 500,
    [ValidateRange(1, 256)][int]$Workers = 64,
    [switch]$SkipIdempotency,
    [switch]$SkipE2e,
    [switch]$SkipSlice
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not (Test-Path -LiteralPath (Join-Path $root ".env"))) {
    throw "Repo root .env not found at $root"
}

$mysqlName = "ai-group-bench-mysql-1"
try {
    $running = docker inspect --format "{{.State.Running}}" $mysqlName 2>$null
} catch {
    throw "Bench MySQL container $mysqlName not found. Start ai-group-bench first."
}
if ($running -ne "true") {
    throw "Bench MySQL container $mysqlName is not running."
}

$pwdLine = Get-Content (Join-Path $root ".env") | Where-Object { $_ -match '^\s*MYSQL_ROOT_PASSWORD\s*=' } | Select-Object -First 1
if (-not $pwdLine) { throw "MYSQL_ROOT_PASSWORD missing from .env" }
$mysqlPassword = ($pwdLine -split '=', 2)[1].Trim().Trim('"').Trim("'")

$plansDir = Join-Path $PSScriptRoot "plans"
$scriptPath = Join-Path $plansDir "bench_settlement_load.py"
$reportsHost = Join-Path $PSScriptRoot "reports\settlement-bench"
New-Item -ItemType Directory -Path $reportsHost -Force | Out-Null

$argsList = @(
    "--concurrency", "$Concurrency",
    "--orders", "$Orders",
    "--workers", "$Workers"
)
if ($SkipIdempotency) { $argsList += "--skip-idempotency" }
if ($SkipE2e) { $argsList += "--skip-e2e" }
if ($SkipSlice) { $argsList += "--skip-slice" }

# Run the producer inside the Compose network so kafka:19092 is reachable without
# docker exec + kafka-console-producer (the previous harness bottleneck).
$pyCmd = @(
    "pip install -q --trusted-host pypi.org --trusted-host files.pythonhosted.org --trusted-host pypi.python.org kafka-python pymysql",
    "python /work/bench_settlement_load.py $($argsList -join ' ')"
) -join " && "

docker run --rm `
    --network ai-group-bench_default `
    -e BENCH_IN_NETWORK=1 `
    -e KAFKA_BOOTSTRAP=kafka:19092 `
    -e MYSQL_HOST=mysql `
    -e MYSQL_PORT=3306 `
    -e MYSQL_USER=root `
    -e MYSQL_ROOT_PASSWORD=$mysqlPassword `
    -e SETTLEMENT_BENCH_REPORT_ROOT=/reports `
    -v "${plansDir}:/work:ro" `
    -v "${reportsHost}:/reports" `
    -w /work `
    python:3.12-slim `
    bash -lc $pyCmd

exit $LASTEXITCODE
