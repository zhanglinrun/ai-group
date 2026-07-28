#Requires -Version 7.0
param(
    [string]$ReportName = "payment-fault-regression.json"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$payRoot = Join-Path $root "s-pay-mall-ddd-market"
$appRoot = Join-Path $payRoot "s-pay-mall-ddd-app"
$surefireRoot = Join-Path $appRoot "target/surefire-reports"
$reports = Join-Path $PSScriptRoot "reports"
$reportPath = Join-Path $reports $ReportName

$testClasses = @(
    "com.aigroup.paymall.test.trigger.TimeoutCloseOrderJobTest",
    "com.aigroup.paymall.test.trigger.RefundSuccessTopicListenerTest",
    "com.aigroup.paymall.test.domain.OrderServiceMarketSettlementTest",
    "com.aigroup.paymall.test.domain.OrderServiceCreateRecoveryTest",
    "com.aigroup.paymall.test.domain.BenefitEventServiceTest",
    "com.aigroup.paymall.test.domain.OrderServiceRefundTest"
)

$categories = [ordered]@{
    timeoutCloseReconciliation = [ordered]@{
        testClass = $testClasses[0]
        expectedScenarios = 4
    }
    refundMqRetryAndAck = [ordered]@{
        testClass = $testClasses[1]
        expectedScenarios = 3
    }
    unpaidOrderBenefitGuard = [ordered]@{
        testClass = $testClasses[2]
        expectedScenarios = 2
    }
    createOrderRecovery = [ordered]@{
        testClass = $testClasses[3]
        expectedScenarios = 2
    }
    benefitOutboxAndRepublish = [ordered]@{
        testClass = $testClasses[4]
        expectedScenarios = 3
    }
    refundStateMachine = [ordered]@{
        testClass = $testClasses[5]
        expectedScenarios = 6
    }
}

New-Item -ItemType Directory -Path $reports -Force | Out-Null
$selector = $testClasses -join ","
$watch = [System.Diagnostics.Stopwatch]::StartNew()
Push-Location $payRoot
try {
    mvn -pl s-pay-mall-ddd-app -am test "-Dtest=$selector" "-Dsurefire.failIfNoSpecifiedTests=false"
    if ($LASTEXITCODE -ne 0) {
        throw "payment fault regression failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
    $watch.Stop()
}

$total = 0
$failures = 0
$errors = 0
$skipped = 0
$categoryResults = [ordered]@{}
foreach ($entry in $categories.GetEnumerator()) {
    $className = [string]$entry.Value.testClass
    $xmlPath = Join-Path $surefireRoot "TEST-$className.xml"
    if (-not (Test-Path $xmlPath)) {
        throw "missing Surefire report for $className"
    }
    [xml]$xml = Get-Content -LiteralPath $xmlPath -Raw
    $suite = $xml.testsuite
    $tests = [int]$suite.tests
    $classFailures = [int]$suite.failures
    $classErrors = [int]$suite.errors
    $classSkipped = [int]$suite.skipped
    if ($tests -ne [int]$entry.Value.expectedScenarios) {
        throw "$className reported $tests scenarios, expected $($entry.Value.expectedScenarios)"
    }
    $total += $tests
    $failures += $classFailures
    $errors += $classErrors
    $skipped += $classSkipped
    $categoryResults[$entry.Key] = [ordered]@{
        scenarios = $tests
        passed = $tests - $classFailures - $classErrors - $classSkipped
        failures = $classFailures
        errors = $classErrors
        skipped = $classSkipped
        durationSec = [Math]::Round([double]$suite.time, 3)
    }
}

$passed = $total - $failures - $errors - $skipped
$passRate = if ($total -gt 0) { [Math]::Round(100.0 * $passed / $total, 1) } else { 0 }
$report = [ordered]@{
    schemaVersion = 1
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    benchmarkType = "offline-mocked-fault-regression"
    environment = [ordered]@{
        os = [System.Environment]::OSVersion.VersionString
        powershell = $PSVersionTable.PSVersion.ToString()
        java = (& java -version 2>&1 | Select-Object -First 1).ToString()
        mavenModule = "s-pay-mall-ddd-app"
    }
    dataset = [ordered]@{
        testClasses = $testClasses.Count
        regressionScenarios = $total
        infrastructure = "Mockito stubs; no live Alipay, MySQL, Kafka, or HTTP"
    }
    results = [ordered]@{
        passed = $passed
        failures = $failures
        errors = $errors
        skipped = $skipped
        regressionPassRatePct = $passRate
        wallClockDurationMs = [long]$watch.ElapsedMilliseconds
        categories = $categoryResults
    }
    methodology = "Runs 20 deterministic unit-level fault and state-guard scenarios against production domain/job/listener classes. Assertions cover reconciliation-before-close, unknown-state fail-safe behavior, refund retry/ack semantics, unpaid-order benefit prevention, group lock recovery, benefit outbox republish, and refund state transitions. This is not a live payment-provider or broker chaos test."
}
$report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $reportPath -Encoding utf8

Write-Host "Payment fault regression report: $reportPath"
Write-Host ("Scenarios: {0}/{1} passed ({2}%), failures={3}, errors={4}, skipped={5}" -f `
        $passed, $total, $passRate, $failures, $errors, $skipped)
