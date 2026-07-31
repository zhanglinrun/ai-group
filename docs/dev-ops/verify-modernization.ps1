# Static modernization gate. It intentionally excludes docs and tests.
$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $false
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $root

function Assert-NoMatches([string]$Name, [string]$Pattern, [string[]]$Paths) {
    $matches = & rg -n -i -U -P -- $Pattern @Paths
    if ($LASTEXITCODE -eq 0) {
        throw "$Name must not appear in production sources:`n$($matches -join "`n")"
    }
    if ($LASTEXITCODE -ne 1) {
        throw "rg failed while checking $Name (exit $LASTEXITCODE)"
    }
}

function Assert-Set([string]$Name, [string[]]$Actual, [string[]]$Expected) {
    $difference = Compare-Object -ReferenceObject $Expected -DifferenceObject $Actual
    if ($difference) {
        throw "$Name mismatch: $($difference | Out-String)"
    }
}

$productionRoots = @(
    Get-ChildItem -Path $root -Recurse -Directory -Filter main |
        Where-Object { $_.FullName -match '[\\/]src[\\/]main$' } |
        ForEach-Object FullName
) + @(
    (Join-Path $root 'ai-agent/runtime/tools/reactor_tool'),
    (Join-Path $root 'web/src')
)

foreach ($forbidden in @(
        @{ Name = 'Qdrant'; Pattern = 'qdrant' },
        @{ Name = 'fastjson'; Pattern = 'fastjson' },
        @{ Name = 'RabbitMQ/AMQP'; Pattern = 'rabbitmq|amqp' },
        @{ Name = 'Spring @Scheduled'; Pattern = '@Scheduled' },
        @{ Name = 'PlanExecute'; Pattern = 'PlanExecute|Plan-and-Execute' },
        @{ Name = 'UserBoundaryCompactor'; Pattern = 'UserBoundaryCompactor' })) {
    Assert-NoMatches $forbidden.Name $forbidden.Pattern $productionRoots
}

$agentRoots = @(
    (Join-Path $root 'ai-agent/ai-agent-domain/src/main'),
    (Join-Path $root 'ai-agent/ai-agent-trigger/src/main')
)
Assert-NoMatches 'legacy Printer.send overload' '(?s)(?:\bprinter|\.getPrinter\(\))\.send\(\s*(?!new\s+AgentStreamEvent)[\s\S]{0,160},' $agentRoots

$printer = Get-Content -Raw -LiteralPath (Join-Path $root 'ai-agent/ai-agent-domain/src/main/java/com/linrun/agent/domain/agent/runtime/printer/Printer.java')
if ($printer -notmatch 'void\s+send\(AgentStreamEvent\s+event\);') {
    throw 'Printer must expose the canonical AgentStreamEvent sender.'
}

$events = Get-Content -Raw -LiteralPath (Join-Path $root 'contracts/agent-stream-events.json') | ConvertFrom-Json
$expectedEvents = @('agent_start', 'thinking', 'text', 'tool_start', 'tool_end', 'todo_progress', 'paused', 'resume_start', 'stage_output', 'error', 'complete')
if (@($events).Count -ne $expectedEvents.Count) {
    throw "Expected $($expectedEvents.Count) SSE fixture events, got $(@($events).Count)."
}
Assert-Set 'SSE event types' @($events.type | Sort-Object -Unique) $expectedEvents
$requiredFields = @{
    agent_start = @('runId', 'ownerId', 'conversationId', 'agentName', 'modelId')
    thinking = @('runId', 'content')
    text = @('runId', 'delta')
    tool_start = @('runId', 'toolCallId', 'toolName', 'argumentsPreview')
    tool_end = @('runId', 'toolCallId', 'toolName', 'resultPreview', 'durationMillis')
    todo_progress = @('runId', 'items')
    paused = @('runId', 'approvalId', 'toolCallId', 'toolName', 'argumentsPreview', 'estimatedMicrocredits', 'expiresAt')
    resume_start = @('runId', 'approvalId', 'toolCallId', 'decision')
    stage_output = @('runId', 'toolCallId', 'outputType', 'payload', 'artifactRefs', 'isFinal')
    error = @('runId', 'code', 'message')
    complete = @('runId', 'summary', 'totalDurationMillis', 'microcreditsConsumed')
}
foreach ($event in $events) {
    $fields = @($event.PSObject.Properties.Name)
    foreach ($field in $requiredFields[$event.type]) {
        if ($fields -notcontains $field) {
            throw "SSE fixture event '$($event.type)' is missing '$field'."
        }
    }
}

$compose = Get-Content -Raw -LiteralPath (Join-Path $root 'docs/dev-ops/docker-compose-platform.yml')
if ($compose -notmatch 'KAFKA_AUTO_CREATE_TOPICS_ENABLE:\s*"false"') {
    throw 'Kafka topic auto-creation must be disabled.'
}
$expectedTopics = @(
    'group.team_success', 'group.team_success.dlt',
    'group.team_refund', 'group.team_refund.dlt',
    'pay.order_pay_success', 'pay.order_pay_success.dlt',
    'member.benefit.completed', 'member.benefit.completed.dlt'
)
foreach ($topic in $expectedTopics) {
    if ($compose -notmatch [regex]::Escape($topic)) {
        throw "Kafka initializer is missing topic '$topic'."
    }
}

$kafkaProducerConfigs = @(
    'member-service/src/main/resources/application.yml',
    'group/group-buy-market-app/src/main/resources/application.yml',
    'group/group-buy-market-app/src/main/resources/application-dev.yml',
    'group/group-buy-market-app/src/main/resources/application-prod.yml',
    's-pay-mall-ddd-market/s-pay-mall-ddd-app/src/main/resources/application.yml'
)
foreach ($config in $kafkaProducerConfigs) {
    $content = Get-Content -Raw -LiteralPath (Join-Path $root $config)
    $requestTimeout = [int]([regex]::Match($content, 'request\.timeout\.ms:\s*(\d+)').Groups[1].Value)
    $deliveryTimeout = [int]([regex]::Match($content, 'delivery\.timeout\.ms:\s*(\d+)').Groups[1].Value)
    $lingerMatch = [regex]::Match($content, 'linger\.ms:\s*(\d+)')
    $linger = if ($lingerMatch.Success) { [int]$lingerMatch.Groups[1].Value } else { 0 }
    if ($requestTimeout -le 0 -or $deliveryTimeout -lt $requestTimeout + $linger) {
        throw "Kafka producer '$config' must set delivery.timeout.ms >= request.timeout.ms + linger.ms."
    }
}

$xxlClientConfigs = @(
    'member-service/src/main/resources/application.yml',
    'group/group-buy-market-app/src/main/resources/application-dev.yml',
    's-pay-mall-ddd-market/s-pay-mall-ddd-app/src/main/resources/application-dev.yml',
    'ai-agent/ai-agent-app/src/main/resources/application.yml'
)
foreach ($config in $xxlClientConfigs) {
    $content = Get-Content -Raw -LiteralPath (Join-Path $root $config)
    if ($content -match 'XXL_JOB_ADMIN_ADDRESSES:http://127\.0\.0\.1:18081/xxl-job-admin') {
        throw "XXL client '$config' must use the Admin root URL, not /xxl-job-admin."
    }
    if ($content -notmatch 'XXL_JOB_ADMIN_ADDRESSES:http://127\.0\.0\.1:18081}') {
        throw "XXL client '$config' must define the verified local Admin root URL."
    }
}

foreach ($launcher in @('docs/dev-ops/start-platform.ps1', 'docs/dev-ops/start-full-stack.ps1')) {
    $content = Get-Content -Raw -LiteralPath (Join-Path $root $launcher)
    if ($content -notmatch '\$env:XXL_JOB_ACCESS_TOKEN\s*=\s*Require-Secret\s+"XXL_JOB_ACCESS_TOKEN"') {
        throw "Launcher '$launcher' must require XXL_JOB_ACCESS_TOKEN."
    }
    if ($content -notmatch 'XXL_JOB_ADMIN_ADDRESSES\s*=\s*\[string\]\$env:XXL_JOB_ADMIN_ADDRESSES' -or
            $content -notmatch 'XXL_JOB_ACCESS_TOKEN\s*=\s*\[string\]\$env:XXL_JOB_ACCESS_TOKEN') {
        throw "Launcher '$launcher' must pass the XXL Admin address and token to services."
    }
}

$dockerBootstrap = Get-Content -Raw -LiteralPath (Join-Path $root 'docs/dev-ops/mysql-init.ps1')
if ($dockerBootstrap -match '--remove-orphans') {
    throw 'Startup scripts must not remove user-owned legacy containers implicitly.'
}

$approvalDdl = Get-Content -Raw -LiteralPath (Join-Path $root 'docs/dev-ops/mysql/sql/agent_db/09-tool-approval.sql')
if ($approvalDdl -notmatch '(?im)^USE\s+agent_db\s*;') {
    throw 'Tool approval migration must target the canonical agent_db database.'
}

foreach ($quotaDdl in @(
        'docs/dev-ops/mysql/sql/agent_db/01-agent_db.sql',
        'docs/dev-ops/mysql/sql/agent_db/07-quota-settlement-command.sql')) {
    $content = Get-Content -Raw -LiteralPath (Join-Path $root $quotaDdl)
    if ($content -notmatch 'usage_source\s+varchar\(32\)') {
        throw "Quota DDL '$quotaDdl' must fit the PROVIDER_NOT_STARTED audit value."
    }
}

# P160 migration parity: the full launcher is the only supported Agent database
# upgrade path. Every numbered migration must be explicitly invoked, otherwise a
# fresh baseline hides missing ALTER/CREATE steps until an old environment starts.
$fullStackLauncher = Join-Path $root 'docs/dev-ops/start-full-stack.ps1'
$fullStackContent = Get-Content -Raw -LiteralPath $fullStackLauncher
$agentMigrationDirectory = Join-Path $root 'docs/dev-ops/mysql/sql/agent_db'
$expectedAgentMigrations = @(
    Get-ChildItem -LiteralPath $agentMigrationDirectory -File -Filter '*.sql' |
        Where-Object { $_.Name -ne '02-dev-seed.sql' } |
        ForEach-Object Name |
        Sort-Object
)
$referencedAgentMigrations = @(
    [regex]::Matches($fullStackContent, 'agent_db[\\/]([^"''\s]+\.sql)') |
        ForEach-Object { $_.Groups[1].Value } |
        Where-Object { $_ -ne '02-dev-seed.sql' } |
        Sort-Object -Unique
)
Assert-Set 'start-full-stack agent_db migrations' $referencedAgentMigrations $expectedAgentMigrations

# P160 frozen-boundary launch guard: Group/Pay source stays immutable. Full-stack
# startup may read their SQL but must execute only externally recovered artifacts;
# demo-lite intentionally leaves both services out of scope.
if ($fullStackContent -notmatch '\[switch\]\$DemoLite' -or
        $fullStackContent -notmatch 'function\s+Start-RecoveredJar' -or
        $fullStackContent -notmatch 'Start-RecoveredJar\s+"group-buy-market"' -or
        $fullStackContent -notmatch 'Start-RecoveredJar\s+"pay-service"') {
    throw 'start-full-stack must provide recovered Group/Pay jars and a demo-lite mode.'
}
foreach ($requiredExecutorSetting in @(
        '"member-service"\s*=\s*@\{\s*AppName\s*=\s*"member";\s*Port\s*=\s*9997\s*\}',
        '"pay-service"\s*=\s*@\{\s*AppName\s*=\s*"pay";\s*Port\s*=\s*9998\s*\}',
        '"group-buy-market"\s*=\s*@\{\s*AppName\s*=\s*"group";\s*Port\s*=\s*9999\s*\}',
        '"ai-agent"\s*=\s*@\{\s*AppName\s*=\s*"ai-agent";\s*Port\s*=\s*9996\s*\}',
        'XXL_JOB_EXECUTOR_ADDRESS',
        'host\.docker\.internal',
        'XXL_JOB_EXECUTOR_IP')) {
    if ($fullStackContent -notmatch $requiredExecutorSetting) {
        throw "start-full-stack must configure a Docker-reachable XXL executor address ('$requiredExecutorSetting')."
    }
}
if ($fullStackContent -notmatch 'RedirectStandardOutput\s+\$stdoutLog' -or
        $fullStackContent -notmatch 'RedirectStandardError\s+\$stderrLog') {
    throw 'Recovered Group/Pay JAR startup must redirect stdout and stderr outside frozen source directories.'
}
$preflightGateIndex = $fullStackContent.IndexOf('$preflightPassed = Invoke-StartupPreflight', [System.StringComparison]::Ordinal)
$firstRuntimeInitializationIndex = $fullStackContent.IndexOf('Ensure-RsaKeyPair "AUTH_JWT_PRIVATE_KEY_BASE64"', [System.StringComparison]::Ordinal)
if ($preflightGateIndex -lt 0 -or $firstRuntimeInitializationIndex -lt 0 -or
        $preflightGateIndex -ge $firstRuntimeInitializationIndex) {
    throw 'start-full-stack must run startup preflight before any runtime initialization.'
}
if ($fullStackContent -notmatch 'function\s+Get-RunningComposeConfigDrift' -or
        $fullStackContent -notmatch 'config\s+--hash\s+''\*''' -or
        $fullStackContent -notmatch 'running Docker infra config drift') {
    throw 'start-full-stack preflight must block accidental recreation when running Docker infrastructure drifts from .env.'
}
foreach ($frozenMavenPattern in @(
        '(?s)Push-Location\s+"\$root/group"\s*\r?\n\s*mvn\s+',
        '(?s)Push-Location\s+"\$root/s-pay-mall-ddd-market"\s*\r?\n\s*mvn\s+')) {
    if ($fullStackContent -match $frozenMavenPattern) {
        throw 'start-full-stack must not execute Maven inside frozen Group/Pay source directories.'
    }
}
$acceptanceScript = Get-Content -Raw -LiteralPath (Join-Path $root 'docs/dev-ops/verify-acceptance.ps1')
if ($acceptanceScript -notmatch 'verify-frozen-manifest\.ps1' -or
        $acceptanceScript -notmatch 'function\s+Assert-RecoveredJar' -or
        $acceptanceScript -match '(?m)^Push-Location\s+(group|s-pay-mall-ddd-market)') {
    throw 'verify-acceptance must verify the frozen manifest/recovered artifacts without testing inside frozen source directories.'
}
foreach ($runtimeAcceptanceRequirement in @(
        'function\s+Invoke-RuntimeAcceptance',
        'smoke-test\.ps1',
        'Assert-XxlOutboxPublisherReady',
        'xxl-pay-outbox-publisher',
        'smoke-agent-sse\.ps1',
        'verify-deep-research-acceptance\.ps1',
        'DurableToolRecoveryTest',
        'run-p110-deep-trace\.ps1',
        'run-ai-agent-eval\.ps1',
        '\[switch\]\$SkipRuntime')) {
    if ($acceptanceScript -notmatch $runtimeAcceptanceRequirement) {
        throw "verify-acceptance runtime coverage is missing '$runtimeAcceptanceRequirement'."
    }
}
$skipBuildIndex = $acceptanceScript.IndexOf('if ($SkipBuild)', [System.StringComparison]::Ordinal)
$runtimeDecisionIndex = $acceptanceScript.IndexOf('if ($SkipRuntime)', [System.StringComparison]::Ordinal)
if ($skipBuildIndex -lt 0 -or $runtimeDecisionIndex -lt 0 -or $skipBuildIndex -ge $runtimeDecisionIndex -or
        $acceptanceScript.Substring($skipBuildIndex, $runtimeDecisionIndex - $skipBuildIndex) -match '(?m)^\s*exit\s+0\s*$') {
    throw 'verify-acceptance -SkipBuild must still permit explicit runtime acceptance.'
}
$sseSmoke = Get-Content -Raw -LiteralPath (Join-Path $root 'docs/dev-ops/smoke-agent-sse.ps1')
if ($sseSmoke -notmatch '\[switch\]\$RequireSseCursorReplay' -or
        $sseSmoke -notmatch 'Last-Event-ID') {
    throw 'Agent SSE smoke must verify durable Last-Event-ID replay when requested.'
}
$deepAcceptance = Get-Content -Raw -LiteralPath (Join-Path $root 'docs/dev-ops/verify-deep-research-acceptance.ps1')
if ($deepAcceptance -notmatch 'requireSseCursorReplay' -or
        $deepAcceptance -notmatch 'RequireSseCursorReplay\s*=\s*\$true') {
    throw 'DEEP reconnect acceptance must invoke the durable Last-Event-ID replay probe.'
}

$expectedMemberMigrations = @(
    'schema.sql'
) + @(
    Get-ChildItem -LiteralPath (Join-Path $root 'docs/dev-ops/mysql/sql/member_db') -File -Filter '*.sql' |
        ForEach-Object Name |
        Sort-Object
)
foreach ($launcher in @('docs/dev-ops/start-platform.ps1', 'docs/dev-ops/start-full-stack.ps1')) {
    $content = Get-Content -Raw -LiteralPath (Join-Path $root $launcher)
    $referencedMemberMigrations = @()
    if ($content -match 'member-service/src/main/resources/schema\.sql') {
        $referencedMemberMigrations += 'schema.sql'
    }
    $referencedMemberMigrations += @(
        [regex]::Matches($content, 'member_db[\\/]([^"''\s]+\.sql)') |
            ForEach-Object { $_.Groups[1].Value }
    )
    Assert-Set "$launcher member_db migrations" @($referencedMemberMigrations | Sort-Object -Unique) $expectedMemberMigrations
}

$seed = Get-Content -Raw -LiteralPath (Join-Path $root 'docs/dev-ops/mysql/sql/xxl_job/01-xxl_job.sql')
$handlers = @([regex]::Matches($seed, "'FIRST','([^']+)'") | ForEach-Object { $_.Groups[1].Value })
$expectedHandlers = @(
    'groupBuyNotifyJob', 'timeoutRefundJob',
    'outboxEventPublishJob', 'timeoutCloseOrderJob', 'noPayNotifyOrderJob', 'waitRefundCompensateJob', 'marketSettlementCompensateJob',
    'expiredFreezeReleaseJob', 'monthlyQuotaGrantJob', 'agentTaskRefreshJob', 'agentTaskCleanupJob'
)
Assert-Set 'XXL handler seed' @($handlers | Sort-Object -Unique) $expectedHandlers
if ($handlers.Count -ne $expectedHandlers.Count) {
    throw "Expected $($expectedHandlers.Count) XXL handler rows, got $($handlers.Count)."
}

Write-Host 'Modernization static gate passed.'
