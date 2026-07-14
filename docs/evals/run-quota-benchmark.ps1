param(
    [string]$MysqlContainer = "ai-group-mysql",
    [string]$MysqlPassword = $(if ($env:MYSQL_ROOT_PASSWORD) { $env:MYSQL_ROOT_PASSWORD } else { "123456" }),
    [string]$BenchmarkDatabase = "member_resume_benchmark",
    [string]$ReportName = "quota-benchmark.json"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$memberRoot = Join-Path $root "member-service"
$reports = Join-Path $PSScriptRoot "reports"
$targetReport = Join-Path $memberRoot "target/resume-evals/quota-benchmark.json"
$finalReport = Join-Path $reports $ReportName

if ($BenchmarkDatabase -notmatch '^[a-zA-Z0-9_]+$') {
    throw "BenchmarkDatabase must contain only letters, digits, and underscores"
}

function Invoke-MysqlStatement([string]$Sql) {
    $output = $Sql | docker exec -i -e "MYSQL_PWD=$MysqlPassword" $MysqlContainer mysql -uroot -N -B
    if ($LASTEXITCODE -ne 0) {
        throw "mysql statement failed with exit code $LASTEXITCODE"
    }
    return $output
}

New-Item -ItemType Directory -Path $reports -Force | Out-Null
Invoke-MysqlStatement "DROP DATABASE IF EXISTS $BenchmarkDatabase; CREATE DATABASE $BenchmarkDatabase CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;" | Out-Null

$previousUrl = $env:SPRING_DATASOURCE_URL
try {
    $env:SPRING_DATASOURCE_URL = "jdbc:mysql://127.0.0.1:13306/$BenchmarkDatabase`?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"
    Push-Location $memberRoot
    try {
        mvn test "-Dtest=com.aigroup.member.benchmark.QuotaConcurrencyBenchmarkIT"
        if ($LASTEXITCODE -ne 0) {
            throw "quota benchmark failed with exit code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
} finally {
    if ($null -eq $previousUrl) {
        Remove-Item Env:SPRING_DATASOURCE_URL -ErrorAction SilentlyContinue
    } else {
        $env:SPRING_DATASOURCE_URL = $previousUrl
    }
    Invoke-MysqlStatement "DROP DATABASE IF EXISTS $BenchmarkDatabase;" | Out-Null
}

if (-not (Test-Path $targetReport)) {
    throw "quota benchmark did not produce $targetReport"
}

Copy-Item -LiteralPath $targetReport -Destination $finalReport -Force
$report = Get-Content -LiteralPath $finalReport -Raw | ConvertFrom-Json

Write-Host "Quota benchmark report: $finalReport"
Write-Host ("Unique freezes: {0} requests, P99={1} ms" -f `
        $report.dataset.concurrentUniqueFreezeRequests,
        $report.results.uniqueFreezeLatencyP99Ms)
Write-Host ("Duplicate freezes/confirms: {0}/{1}, duplicate deductions={2}" -f `
        $report.dataset.concurrentDuplicateFreezeRequests,
        $report.dataset.concurrentDuplicateConfirmRequests,
        $report.results.duplicateDeductions)
Write-Host ("Abandoned freeze recovery: {0} faults, success={1}%" -f `
        $report.dataset.abandonedFreezeFaults,
        $report.results.expiredFreezeReleaseSuccessRatePct)
