param(
    [switch]$SkipBuild,
    [switch]$SkipRuntime,
    [switch]$SkipMigrationIdempotency,
    [switch]$DemoLite,
    [ValidateRange(1, 65535)]
    [int]$MemberPort = 18082
)

$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $true
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $root

function Resolve-FrozenArtifactRoot() {
    if ($env:AI_GROUP_FROZEN_ARTIFACT_ROOT) {
        return [System.IO.Path]::GetFullPath($env:AI_GROUP_FROZEN_ARTIFACT_ROOT)
    }
    $recoveryParent = Join-Path (Split-Path -Parent $root) "ai-group-generated-recovery"
    if (Test-Path -LiteralPath $recoveryParent -PathType Container) {
        $candidate = Get-ChildItem -LiteralPath $recoveryParent -Directory |
            Where-Object { $_.Name -like "p130-generated-*" } |
            Sort-Object LastWriteTimeUtc -Descending |
            Select-Object -First 1
        if ($candidate) {
            return $candidate.FullName
        }
    }
    throw "Full acceptance requires recovered Group/Pay jars outside the repository. Set AI_GROUP_FROZEN_ARTIFACT_ROOT or use -DemoLite."
}

function Assert-RecoveredJar([string]$name, [string]$path) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Recovered artifact is missing for ${name}: $path"
    }
    $entries = & jar tf $path
    if ($LASTEXITCODE -ne 0 -or $entries -notcontains "META-INF/MANIFEST.MF" -or
            -not ($entries | Where-Object { $_ -like "BOOT-INF/classes/*" } | Select-Object -First 1)) {
        throw "Recovered artifact is not a runnable Spring Boot jar for ${name}: $path"
    }
    Write-Host "Recovered $name artifact verified: $path"
}

function Assert-ServiceReachable([string]$name, [string]$uri) {
    try {
        $response = Invoke-WebRequest -Method GET -Uri $uri -TimeoutSec 8 -UseBasicParsing
        Write-Host "$name is reachable: $uri (HTTP $($response.StatusCode))"
        return
    } catch {
        $statusCode = 0
        if ($_.Exception.Response) {
            $statusCode = [int]$_.Exception.Response.StatusCode
        }
        # Auth/BFF/Member actuator endpoints are intentionally protected in some profiles.
        if ($statusCode -in @(401, 403, 404, 405)) {
            Write-Host "$name is reachable: $uri (HTTP $statusCode; protected or route probe)"
            return
        }
        throw "$name is not reachable at ${uri}: $($_.Exception.Message)"
    }
}

function Get-LocalSecret([string]$name) {
    $processValue = [Environment]::GetEnvironmentVariable($name, "Process")
    if (-not [string]::IsNullOrWhiteSpace($processValue)) {
        return $processValue
    }
    $envFile = Join-Path $root ".env"
    if (-not (Test-Path -LiteralPath $envFile -PathType Leaf)) {
        throw "${name} is required for the XXL runtime probe and .env is missing."
    }
    $line = Get-Content -LiteralPath $envFile -Encoding UTF8 |
        Where-Object { $_ -match "^$([regex]::Escape($name))=" } |
        Select-Object -First 1
    if (-not $line) {
        throw "${name} is required for the XXL runtime probe."
    }
    $value = ($line -split "=", 2)[1].Trim().Trim('"')
    if ([string]::IsNullOrWhiteSpace($value)) {
        throw "${name} is blank for the XXL runtime probe."
    }
    return $value
}

function Invoke-XxlMysqlQuery([string]$sql) {
    $mysqlRootPassword = Get-LocalSecret "MYSQL_ROOT_PASSWORD"
    $hadMysqlPwd = Test-Path Env:MYSQL_PWD
    $previousMysqlPwd = $env:MYSQL_PWD
    $env:MYSQL_PWD = $mysqlRootPassword
    try {
        $output = $sql | docker exec -i -e MYSQL_PWD ai-group-mysql mysql -uroot -N -B 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw "XXL MySQL probe failed (exit $LASTEXITCODE): $($output | Out-String)"
        }
        return @($output | ForEach-Object { $_.ToString().Trim() } | Where-Object { $_ })
    } finally {
        if ($hadMysqlPwd) { $env:MYSQL_PWD = $previousMysqlPwd } else { Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue }
    }
}

function Assert-XxlOutboxPublisherReady {
    $deadline = (Get-Date).AddSeconds(45)
    $lastFailure = "Pay XXL executor has not registered yet."
    do {
        # Force collection semantics: PowerShell unwraps a one-row function result into
        # a scalar string, for which [0] is the first character instead of the first row.
        $registry = @(Invoke-XxlMysqlQuery @"
SELECT registry_value
FROM xxl_job.xxl_job_registry
WHERE registry_key = 'pay'
ORDER BY update_time DESC
LIMIT 1;
"@)
        if ($registry.Count -eq 1 -and $registry[0] -match '^http://host\.docker\.internal:9998/$') {
            $jobResult = @(Invoke-XxlMysqlQuery @"
SELECT CONCAT(trigger_code, ':', handle_code)
FROM xxl_job.xxl_job_log
WHERE job_id = (
    SELECT id FROM xxl_job.xxl_job_info
    WHERE executor_handler = 'outboxEventPublishJob'
    LIMIT 1
)
ORDER BY id DESC
LIMIT 1;
"@)
            if ($jobResult.Count -eq 1 -and $jobResult[0] -eq '200:200') {
                Write-Host "XXL Pay outbox publisher is registered and last execution succeeded: $($registry[0])"
                return
            }
            $lastFailure = "Pay XXL executor is registered but the latest outboxEventPublishJob result is '$($jobResult -join ',')'."
        } else {
            $lastFailure = "Pay XXL executor registration is missing or not Docker-reachable: '$($registry -join ',')'."
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw $lastFailure
}

function Invoke-RuntimeAcceptance {
    $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $runtimeEvidenceDirectory = Join-Path $root "docs/acceptance/p160-runtime-$timestamp"
    New-Item -ItemType Directory -Path $runtimeEvidenceDirectory -Force | Out-Null
    $summary = [ordered]@{
        generatedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
        mode = if ($DemoLite) { "demo-lite" } else { "full-stack" }
        health = @()
        checks = @()
        status = "RUNNING"
    }

    try {
        $healthChecks = @(
            @{ name = "gateway"; uri = "http://127.0.0.1:8080/actuator/health" },
            @{ name = "auth-service"; uri = "http://127.0.0.1:8081/actuator/health" },
            @{ name = "member-service"; uri = "http://127.0.0.1:$MemberPort/actuator/health" },
            @{ name = "bff-service"; uri = "http://127.0.0.1:8083/actuator/health" },
            @{ name = "ai-agent"; uri = "http://127.0.0.1:8090/web/health" },
            @{ name = "runtime-tools"; uri = "http://127.0.0.1:1601/health" },
            @{ name = "frontend"; uri = "http://127.0.0.1:5173/login" }
        )
        if (-not $DemoLite) {
            $healthChecks += @(
                @{ name = "group-buy-market"; uri = "http://127.0.0.1:8091/actuator/health" },
                @{ name = "pay-service"; uri = "http://127.0.0.1:8070/api/v1/alipay/create_pay_order" }
            )
        }
        foreach ($check in $healthChecks) {
            Assert-ServiceReachable $check.name $check.uri
            $summary.health += $check
        }

        if (-not $DemoLite) {
            Assert-XxlOutboxPublisherReady
            $summary.checks += "xxl-pay-outbox-publisher"
        }

        & "$PSScriptRoot/smoke-test.ps1"
        $summary.checks += "login-and-quota"
        if (-not $DemoLite) {
            & "$PSScriptRoot/smoke-benefit-event.ps1"
            & "$PSScriptRoot/smoke-benefit-revoke.ps1"
            & "$PSScriptRoot/smoke-security.ps1"
            & "$PSScriptRoot/verify-e2e.ps1"
            $summary.checks += "group-pay-member-quota-e2e"
        }

        & "$PSScriptRoot/smoke-agent-sse.ps1" `
            -Gateway "http://127.0.0.1:8080" `
            -ExecutionMode STANDARD `
            -ExpectedOutcome SUCCESS `
            -TimeoutSeconds 180
        $summary.checks += "standard-sse-quota-settlement"

        $deepEvidencePath = Join-Path $runtimeEvidenceDirectory "deep-research-reconnect.json"
        & "$PSScriptRoot/verify-deep-research-acceptance.ps1" `
            -Run `
            -Only reconnect `
            -Gateway "http://127.0.0.1:8080" `
            -EvidencePath $deepEvidencePath `
            -TimeoutSeconds 600
        $summary.checks += "deep-sse-last-event-id-reconnect"

        Push-Location (Join-Path $root "ai-agent")
        try {
            & mvn -pl ai-agent-app -am `
                '-Dtest=DurableToolRecoveryTest' `
                '-Dsurefire.failIfNoSpecifiedTests=false' test
            if ($LASTEXITCODE -ne 0) {
                throw "durable worker failure regression test failed (exit $LASTEXITCODE)"
            }
        } finally {
            Pop-Location
        }
        $summary.checks += "durable-worker-failure"

        $traceEvidencePath = Join-Path $runtimeEvidenceDirectory "deep-trace.json"
        & (Join-Path $root "docs/acceptance/run-p110-deep-trace.ps1") `
            -Gateway "http://127.0.0.1:8080" `
            -EvidencePath $traceEvidencePath
        $summary.checks += "trace-ledger-correlation"

        $evalOutput = Join-Path $root "docs/evals/reports/p160-runtime-$timestamp"
        & (Join-Path $root "docs/evals/run-ai-agent-eval.ps1") -OutputDirectory $evalOutput
        $summary.checks += "golden-eval"
        $summary.status = "PASSED"
    } catch {
        $summary.status = "FAILED"
        $summary.failure = $_.Exception.Message
        throw
    } finally {
        $summary | ConvertTo-Json -Depth 12 |
            Set-Content -LiteralPath (Join-Path $runtimeEvidenceDirectory "runtime-acceptance-summary.json") -Encoding UTF8
        Write-Host "Runtime acceptance evidence: $runtimeEvidenceDirectory"
    }
}

& "$PSScriptRoot/verify-modernization.ps1"
& "$PSScriptRoot/verify-frozen-manifest.ps1" | Out-Host
if ($DemoLite) {
    Write-Host 'Demo-lite acceptance: recovered Group/Pay artifact checks are intentionally skipped.'
} else {
    $frozenArtifactRoot = Resolve-FrozenArtifactRoot
    Assert-RecoveredJar "group-buy-market" (Join-Path $frozenArtifactRoot "group\group-buy-market-app\target\group-buy-market-app.jar")
    Assert-RecoveredJar "pay-service" (Join-Path $frozenArtifactRoot "s-pay-mall-ddd-market\s-pay-mall-ddd-app\target\s-pay-mall-ddd-app.jar")
}
if ($SkipBuild) {
    Write-Host 'Static acceptance gate passed; build verification skipped.'
} else {
    if (-not $SkipMigrationIdempotency) {
        & (Join-Path $root "docs/acceptance/verify-p160-migration-idempotency.ps1")
    }

    & mvn test

    Push-Location ai-agent
    try { & mvn test } finally { Pop-Location }

    Push-Location ai-agent/runtime/tools
    try { & uv run pytest } finally { Pop-Location }

    Push-Location web
    try {
        & pnpm test -- --run
        & pnpm lint
        & pnpm build
    } finally { Pop-Location }

    $commit = (& git rev-parse HEAD).Trim()
    Write-Host "Build acceptance gate passed at commit $commit."
}
if ($SkipRuntime) {
    Write-Host 'Static/build acceptance passed; runtime smoke was explicitly skipped.'
} else {
    Invoke-RuntimeAcceptance
    Write-Host 'Build and runtime acceptance gate passed.'
}
