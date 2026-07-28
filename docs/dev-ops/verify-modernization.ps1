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
