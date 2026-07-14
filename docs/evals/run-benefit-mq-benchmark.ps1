#Requires -Version 7.0
param(
    [string]$MysqlContainer = "ai-group-mysql",
    [string]$MysqlPassword = $(if ($env:MYSQL_ROOT_PASSWORD) { $env:MYSQL_ROOT_PASSWORD } else { "123456" }),
    [string]$BenchmarkDatabase = "member_benefit_resume_benchmark",
    [string]$ReportName = "benefit-mq-benchmark.json"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$memberRoot = Join-Path $root "member-service"
$reports = Join-Path $PSScriptRoot "reports"
$targetReport = Join-Path $memberRoot "target/resume-evals/benefit-mq-benchmark.json"
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
        mvn test "-Dtest=com.aigroup.member.benchmark.BenefitMqBenchmarkIT"
        if ($LASTEXITCODE -ne 0) {
            throw "benefit MQ benchmark failed with exit code $LASTEXITCODE"
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
    throw "benefit MQ benchmark did not produce $targetReport"
}

Copy-Item -LiteralPath $targetReport -Destination $finalReport -Force
$report = Get-Content -LiteralPath $finalReport -Raw | ConvertFrom-Json

Write-Host "Benefit MQ benchmark report: $finalReport"
Write-Host ("Delivery: {0}/{1} events, success={2}%, P95={3} ms" -f `
        $report.results.grantEventRows,
        $report.dataset.uniqueBenefitEvents,
        $report.results.benefitDeliverySuccessRatePct,
        $report.results.benefitDeliveryLatencyP95Ms)
Write-Host ("Duplicates: {0} deliveries, duplicate grant effects={1}" -f `
        $report.dataset.duplicateDeliveries,
        $report.results.duplicateGrantEffects)
Write-Host ("Fault injection: poison message DLQ={0}, configured attempts={1}" -f `
        $report.results.poisonMessageDeadLettered,
        $report.dataset.configuredMaxAttempts)
