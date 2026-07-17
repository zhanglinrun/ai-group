# 一键启动：Docker 基础设施 + 微服务 + group/pay + ai-agent + reactor-tool + 前端
param(
    [switch]$StopPort8080Conflict,
    [switch]$IncludeObservability,
    [ValidateRange(1, 65535)]
    [int]$MemberPort = 18082
)

$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $true
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$envFile = Join-Path $root ".env"
. (Join-Path $PSScriptRoot "mysql-init.ps1")

function Import-DotEnv($path) {
    if (-not (Test-Path $path)) { return }
    Get-Content -LiteralPath $path -Encoding UTF8 | ForEach-Object {
        if ($_ -match '^\s*#' -or $_ -notmatch '=') { return }
        $k, $v = $_ -split '=', 2
        $k = $k.Trim()
        $v = $v.Trim().Trim('"')
        if ($k) { Set-Item -Path "env:$k" -Value $v }
    }
}

Import-DotEnv $envFile
$env:JWT_SECRET = if ($env:JWT_SECRET) { $env:JWT_SECRET } else { "change-me-to-a-long-random-secret" }
$env:AI_GROUP_INTERNAL_TOKEN = if ($env:AI_GROUP_INTERNAL_TOKEN) { $env:AI_GROUP_INTERNAL_TOKEN } else { "change-me-to-a-long-random-internal-token" }
$env:AI_GROUP_INTERNAL_AUTH_ENABLED = if ($env:AI_GROUP_INTERNAL_AUTH_ENABLED) { $env:AI_GROUP_INTERNAL_AUTH_ENABLED } else { "true" }
$env:ALIPAY_ENABLED = if ($env:ALIPAY_ENABLED) { $env:ALIPAY_ENABLED } else { "false" }
$env:AI_GROUP_DEMO_PAYMENT_ENABLED = if ($env:AI_GROUP_DEMO_PAYMENT_ENABLED) { $env:AI_GROUP_DEMO_PAYMENT_ENABLED } else { "false" }
$canonicalReactorToolToken = if ($env:AGENT_GROUP_REACTOR_TOOL_TOKEN) {
    $env:AGENT_GROUP_REACTOR_TOOL_TOKEN
} elseif ($env:REACTOR_TOOL_TOKEN) {
    $env:REACTOR_TOOL_TOKEN
} else {
    $env:AI_GROUP_INTERNAL_TOKEN
}
$env:AGENT_GROUP_REACTOR_TOOL_TOKEN = $canonicalReactorToolToken
$env:REACTOR_TOOL_TOKEN = $canonicalReactorToolToken
$env:AGENT_GROUP_LLM_API_KEY = if ($env:AGENT_GROUP_LLM_API_KEY) { $env:AGENT_GROUP_LLM_API_KEY } else { $env:DASHSCOPE_API_KEY }
$env:AGENT_GROUP_LLM_BASE_URL = if ($env:AGENT_GROUP_LLM_BASE_URL) { $env:AGENT_GROUP_LLM_BASE_URL } else { "https://dashscope.aliyuncs.com/compatible-mode/v1" }
$env:AGENT_GROUP_LLM_CHAT_MODEL = if ($env:AGENT_GROUP_LLM_CHAT_MODEL) { $env:AGENT_GROUP_LLM_CHAT_MODEL } else { "qwen-plus" }
$env:REPORT_MODEL = if ($env:REPORT_MODEL) { $env:REPORT_MODEL } else { $env:AGENT_GROUP_LLM_CHAT_MODEL }
$env:AGENT_GROUP_LLM_EMBEDDING_MODEL = if ($env:AGENT_GROUP_LLM_EMBEDDING_MODEL) { $env:AGENT_GROUP_LLM_EMBEDDING_MODEL } else { "text-embedding-v3" }
$env:AGENT_GROUP_VECTOR_DIMENSION = if ($env:AGENT_GROUP_VECTOR_DIMENSION) { $env:AGENT_GROUP_VECTOR_DIMENSION } else { "1024" }
$env:AGENT_GROUP_REACTOR_TOOL_BASE_URL = if ($env:AGENT_GROUP_REACTOR_TOOL_BASE_URL) { $env:AGENT_GROUP_REACTOR_TOOL_BASE_URL } else { "http://127.0.0.1:1601" }
$env:REACTOR_TOOL_ENV = if ($env:REACTOR_TOOL_ENV) { $env:REACTOR_TOOL_ENV } else { "local" }
$env:REACTOR_TOOL_HOST = if ($env:REACTOR_TOOL_HOST) { $env:REACTOR_TOOL_HOST } else { "127.0.0.1" }
$env:REACTOR_TOOL_CORS_ORIGINS = if ($env:REACTOR_TOOL_CORS_ORIGINS) { $env:REACTOR_TOOL_CORS_ORIGINS } else { "http://localhost:5173,http://127.0.0.1:5173" }
$env:SKILL_ALLOWED_RUNTIMES = if ($env:SKILL_ALLOWED_RUNTIMES) { $env:SKILL_ALLOWED_RUNTIMES } else { "python" }
$env:MYSQL_ROOT_PASSWORD = if ($env:MYSQL_ROOT_PASSWORD) { $env:MYSQL_ROOT_PASSWORD } else { "123456" }
$env:RABBITMQ_USER = if ($env:RABBITMQ_USER) { $env:RABBITMQ_USER } else { "admin" }
$env:RABBITMQ_PASSWORD = if ($env:RABBITMQ_PASSWORD) { $env:RABBITMQ_PASSWORD } else { "admin" }
$env:MINIO_ROOT_USER = if ($env:MINIO_ROOT_USER) { $env:MINIO_ROOT_USER } else { "minioadmin" }
$env:MINIO_ROOT_PASSWORD = if ($env:MINIO_ROOT_PASSWORD) { $env:MINIO_ROOT_PASSWORD } else { "minioadmin" }
$env:MINIO_ENDPOINT = if ($env:MINIO_ENDPOINT) { $env:MINIO_ENDPOINT } else { "http://127.0.0.1:9000" }
$env:MINIO_ACCESS_KEY = if ($env:MINIO_ACCESS_KEY) { $env:MINIO_ACCESS_KEY } else { $env:MINIO_ROOT_USER }
$env:MINIO_SECRET_KEY = if ($env:MINIO_SECRET_KEY) { $env:MINIO_SECRET_KEY } else { $env:MINIO_ROOT_PASSWORD }
$env:MINIO_BUCKET_NAME = if ($env:MINIO_BUCKET_NAME) { $env:MINIO_BUCKET_NAME } else { "ai-group" }
$env:REACTOR_TOOL_PUBLIC_BASE_URL = if ($env:REACTOR_TOOL_PUBLIC_BASE_URL) { $env:REACTOR_TOOL_PUBLIC_BASE_URL } else { "http://127.0.0.1:1601/v1/storage" }
$env:VECTOR_STORE_TYPE = if ($env:VECTOR_STORE_TYPE) { $env:VECTOR_STORE_TYPE } else { "qdrant" }
$env:QDRANT_URL = if ($env:QDRANT_URL) { $env:QDRANT_URL } else { "http://127.0.0.1:6333" }
$env:QDRANT_PORT = if ($env:QDRANT_PORT) { $env:QDRANT_PORT } else { "6334" }
$env:QDRANT_PREFER_GRPC = if ($env:QDRANT_PREFER_GRPC) { $env:QDRANT_PREFER_GRPC } else { "false" }
$env:AGENT_MEMORY_LONGTERM_ENABLED = if ($env:AGENT_MEMORY_LONGTERM_ENABLED) { $env:AGENT_MEMORY_LONGTERM_ENABLED } else { "true" }
$env:AGENT_MEMORY_LONGTERM_COLLECTION = if ($env:AGENT_MEMORY_LONGTERM_COLLECTION) { $env:AGENT_MEMORY_LONGTERM_COLLECTION } else { "agent_conversation_memory" }
$env:SPRING_AI_OPENAI_API_KEY = $env:AGENT_GROUP_LLM_API_KEY
$env:SPRING_PROFILES_ACTIVE = "dev"
$env:AI_AGENT_AUTO_CONFIG_ENABLED = if ($env:AI_AGENT_AUTO_CONFIG_ENABLED) { $env:AI_AGENT_AUTO_CONFIG_ENABLED } else { "true" }

function Test-PortListening($port) {
    return [bool](netstat -ano | Select-String "LISTENING" | Select-String ":$port ")
}

function Wait-HttpReady($name, $uri, [int]$timeoutSec = 60) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    do {
        try {
            $response = Invoke-WebRequest -Method GET -Uri $uri -TimeoutSec 3
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 300) {
                Write-Host "$name is ready: $uri"
                return
            }
        } catch {}
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)

    throw "$name not ready after ${timeoutSec}s: $uri"
}

function Stop-PortListener($port) {
    $line = netstat -ano | Select-String "LISTENING" | Select-String ":$port " | Select-Object -First 1
    if ($line -match "\s(\d+)\s*$") {
        $processId = [int]$Matches[1]
        Write-Host "Stopping process on :$port (PID=$processId)"
        Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
        Start-Sleep -Seconds 2
    }
}

function Start-ServiceWindow($name, $path, $port, $extraEnv = @{}) {
    if (Test-PortListening $port) {
        throw "Port :$port is already in use. Stop the existing listener before starting $name."
    }
    $serviceEnvironment = @{
        SERVER_PORT                   = [string]$port
        JWT_SECRET                    = [string]$env:JWT_SECRET
        AI_GROUP_INTERNAL_TOKEN       = [string]$env:AI_GROUP_INTERNAL_TOKEN
        AI_GROUP_INTERNAL_AUTH_ENABLED = [string]$env:AI_GROUP_INTERNAL_AUTH_ENABLED
        SPRING_PROFILES_ACTIVE        = "dev"
    }
    foreach ($key in $extraEnv.Keys) {
        $serviceEnvironment[$key] = [string]$extraEnv[$key]
    }
    Write-Host "Start $name on :$port"
    Start-Process pwsh `
        -ArgumentList "-NoProfile", "-Command", "`$ErrorActionPreference = 'Stop'; mvn spring-boot:run -q" `
        -WorkingDirectory $path `
        -Environment $serviceEnvironment `
        -WindowStyle Hidden
    Start-Sleep -Seconds 12
}

function Patch-AgentApiKey() {
    if (-not $env:AGENT_GROUP_LLM_API_KEY) { return }
    $key = $env:AGENT_GROUP_LLM_API_KEY -replace "'", "''"
    $sql = "UPDATE agent_db.ai_client_api SET api_key='$key' WHERE api_id='dev_api_001';"
    Invoke-MysqlStatement $sql
    Write-Host "Patched agent_db dev_api_001 api_key from .env"
}

function Sync-ReactorToolEnv() {
    $rtEnv = Join-Path $root "ai-agent\reactor-tool\.env"
    $lines = @(
        "OPENAI_API_KEY=$($env:AGENT_GROUP_LLM_API_KEY)",
        "OPENAI_BASE_URL=$($env:AGENT_GROUP_LLM_BASE_URL)",
        "DEFAULT_MODEL=$($env:AGENT_GROUP_LLM_CHAT_MODEL)",
        "REPORT_MODEL=$($env:REPORT_MODEL)",
        "TEXT_EMBEDDING_TYPE=openai",
        "TEXT_EMBEDDING_API_KEY=$($env:AGENT_GROUP_LLM_API_KEY)",
        "TEXT_EMBEDDING_BASE_URL=$($env:AGENT_GROUP_LLM_BASE_URL)",
        "TEXT_EMBEDDING_MODEL_NAME=$($env:AGENT_GROUP_LLM_EMBEDDING_MODEL)",
        "TEXT_EMBEDDING_DIMENSION=$($env:AGENT_GROUP_VECTOR_DIMENSION)",
        "DASHSCOPE_API_KEY=$($env:DASHSCOPE_API_KEY)",
        "DASHSCOPE_API_BASE=$($env:AGENT_GROUP_LLM_BASE_URL)",
        "MINIO_ENDPOINT=$($env:MINIO_ENDPOINT)",
        "MINIO_ACCESS_KEY=$($env:MINIO_ACCESS_KEY)",
        "MINIO_SECRET_KEY=$($env:MINIO_SECRET_KEY)",
        "MINIO_BUCKET_NAME=$($env:MINIO_BUCKET_NAME)",
        "REACTOR_TOOL_PUBLIC_BASE_URL=$($env:REACTOR_TOOL_PUBLIC_BASE_URL)",
        "MINIO_PUBLIC_ENDPOINT=$($env:MINIO_PUBLIC_ENDPOINT)",
        "VECTOR_STORE_TYPE=$($env:VECTOR_STORE_TYPE)",
        "QDRANT_URL=$($env:QDRANT_URL)",
        "QDRANT_PORT=$($env:QDRANT_PORT)",
        "QDRANT_API_KEY=$($env:QDRANT_API_KEY)",
        "QDRANT_PREFER_GRPC=$($env:QDRANT_PREFER_GRPC)",
        "TAVILY_API_KEY=$($env:TAVILY_API_KEY)",
        "MINERU_API_KEY=$($env:MINERU_API_KEY)",
        "IMAGE_GENERATION_BASE_URL=$($env:IMAGE_GENERATION_BASE_URL)",
        "IMAGE_GENERATION_API_KEY=$($env:IMAGE_GENERATION_API_KEY)",
        "IMAGE_GENERATION_MODEL=$($env:IMAGE_GENERATION_MODEL)",
        "AI_GROUP_INTERNAL_TOKEN=$($env:AI_GROUP_INTERNAL_TOKEN)",
        "REACTOR_TOOL_TOKEN=$($env:REACTOR_TOOL_TOKEN)",
        "REACTOR_TOOL_ENV=$($env:REACTOR_TOOL_ENV)",
        "REACTOR_TOOL_HOST=$($env:REACTOR_TOOL_HOST)",
        "REACTOR_TOOL_CORS_ORIGINS=$($env:REACTOR_TOOL_CORS_ORIGINS)",
        "SKILL_ALLOWED_RUNTIMES=$($env:SKILL_ALLOWED_RUNTIMES)",
        "SKILL_MAX_CONCURRENT_PROCESSES=2"
        # Deep search 必须服从 Agent run budget；搜索引擎不可达时快速降级，
        # 不能把 SSE 请求拖到客户端断开。
        "SEARCH_TIMEOUT=20"
        "SEARCH_PARSER_TIMEOUT=10"
        "DEEPSEARCH_TOTAL_TIMEOUT_SECONDS=150"
        "SEARCH_THREAD_NUM=4"
    )
    Set-Content -Path $rtEnv -Value ($lines -join "`n") -Encoding UTF8
    Write-Host "Synced reactor-tool/.env"
}

if ($StopPort8080Conflict) {
    Stop-PortListener 8080
}

Write-Host "==> Docker infra"
$opsRoot = Join-Path $root "docs/dev-ops"
Start-DockerInfra -OpsRoot $opsRoot -IncludeObservability:$IncludeObservability
Wait-MysqlReady
Wait-RedisReady

Write-Host "==> Init DB (idempotent)"
Invoke-Mysql "$root/auth-service/src/main/resources/schema.sql"
Invoke-Mysql "$root/member-service/src/main/resources/schema.sql"
Invoke-Mysql "$root/docs/dev-ops/mysql/sql/member_db/02-platform-schema-migrate.sql"
# Durable Agent 额度结算依赖请求指纹、真实终态查询与托管冻结标记；老库必须在 member 02 后增量升级。
Invoke-Mysql "$root/docs/dev-ops/mysql/sql/member_db/03-durable-quota-settlement.sql"
Invoke-Mysql "$root/docs/dev-ops/mysql/sql/agent_db/01-agent_db.sql"
# 独立增量脚本同样纳入一键启动，统一迁移到 Agent Loop 与 todo_write 存储结构。
Invoke-Mysql "$root/docs/dev-ops/mysql/sql/agent_db/03-agent-loop-migrate.sql"
# Work 任务图、固定角色快照、durable run claim 与 quota settlement command 都是当前运行时真实依赖；
# 增量脚本均可幂等执行，必须在 seed 前补齐，保证全新数据库和已有旧库使用同一启动路径。
Invoke-Mysql "$root/docs/dev-ops/mysql/sql/agent_db/04-task-graph.sql"
Invoke-Mysql "$root/docs/dev-ops/mysql/sql/agent_db/05-dialogue-run-role.sql"
Invoke-Mysql "$root/docs/dev-ops/mysql/sql/agent_db/06-dialogue-run-claim-hardening.sql"
Invoke-Mysql "$root/docs/dev-ops/mysql/sql/agent_db/07-quota-settlement-command.sql"
# 工具结果与模型 observation 分离持久化，保证结构化工具的实时展示和历史回放一致。
Invoke-Mysql "$root/docs/dev-ops/mysql/sql/agent_db/08-tool-result-replay.sql"
Invoke-Mysql "$root/docs/dev-ops/mysql/sql/agent_db/02-dev-seed.sql"
# group/pay 为全量转储（DROP+重灌）：仅首次初始化执行，已存在则跳过以保留订单/拼团数据
Invoke-MysqlDumpOnce "$root/group/docs/dev-ops/mysql/sql/2-29-group_buy_market.sql" -Schema "group_buy_market" -MarkerTable "group_buy_order"
# 每个额度包使用独立拼团链（goods + discount + activity）：幂等迁移，老库也会补齐
Invoke-Mysql "$root/group/docs/dev-ops/mysql/sql/3-01-per-sku-groupbuy-migrate.sql"
# 阶梯拼团：档位表 + activity_type + 档位种子（3-02）；容量=最高档人数(10)（3-03，须在 3-01 之后覆盖 target）
Invoke-Mysql "$root/group/docs/dev-ops/mysql/sql/3-02-groupbuy-tier-migrate.sql"
Invoke-Mysql "$root/group/docs/dev-ops/mysql/sql/3-03-groupbuy-tier-settlement-migrate.sql"
Invoke-MysqlDumpOnce "$root/s-pay-mall-ddd-market/docs/dev-ops/mysql/sql/s-pay-mall-ddd-market.sql" -Schema "s_pay_mall_ddd_market" -MarkerTable "pay_order" -PayBase
Invoke-Mysql "$root/s-pay-mall-ddd-market/docs/dev-ops/mysql/sql/V3_benefit_event.sql"
Invoke-Mysql "$root/s-pay-mall-ddd-market/docs/dev-ops/mysql/sql/V4_settlement_notified.sql"
# 支付交易统一 outbox：履约/权益事件共用本地消息表，补齐老库索引并回填可能丢失的支付成功事件。
Invoke-Mysql "$root/s-pay-mall-ddd-market/docs/dev-ops/mysql/sql/V5_transactional_outbox.sql"
# 支付下单 durable 幂等键、规范化载荷指纹及拼团路径快照。
Invoke-Mysql "$root/s-pay-mall-ddd-market/docs/dev-ops/mysql/sql/V6_pay_order_idempotency.sql"
# 阶梯拼团：benefit_event.bonus_quota（加赠额度随权益事件透传给 member）
Invoke-Mysql "$root/docs/dev-ops/mysql/sql/pay_db/01-benefit-event-bonus-migrate.sql"
Invoke-Mysql "$root/docs/dev-ops/mysql/sql/xxl_job/01-xxl_job.sql"
Patch-AgentApiKey

Write-Host "==> Build platform"
Push-Location $root
mvn clean install -DskipTests -q
Push-Location "$root/group"
mvn clean install -DskipTests -q
Pop-Location
Push-Location "$root/s-pay-mall-ddd-market"
mvn clean install -DskipTests -q
Pop-Location
# ai-agent 是独立聚合工程（不在根 pom 的 modules 里）；干净 .m2 下必须先 install，
# 否则后面直接在 ai-agent-app 子模块 spring-boot:run 会因缺兄弟 SNAPSHOT 依赖失败。
Push-Location "$root/ai-agent"
mvn clean install -DskipTests -q
Pop-Location
Pop-Location

# ai-agent 会在 ApplicationReadyEvent 中预热所有 status=1 的 STDIO MCP。
# 因此必须先同步 reactor-tool 的锁定依赖，否则干净 checkout 会在 MCP 子进程启动时缺少 Python SDK。
Write-Host "==> Prepare reactor-tool runtime"
Sync-ReactorToolEnv
Push-Location "$root/ai-agent/reactor-tool"
try {
    uv sync --frozen
} finally {
    Pop-Location
}

$payEnv = @{
    ALIPAY_ENABLED              = $env:ALIPAY_ENABLED
    AI_GROUP_DEMO_PAYMENT_ENABLED = $env:AI_GROUP_DEMO_PAYMENT_ENABLED
    ALIPAY_APP_ID               = $env:ALIPAY_APP_ID
    ALIPAY_MERCHANT_PRIVATE_KEY = $env:ALIPAY_MERCHANT_PRIVATE_KEY
    ALIPAY_PUBLIC_KEY           = $env:ALIPAY_PUBLIC_KEY
    ALIPAY_NOTIFY_URL           = $env:ALIPAY_NOTIFY_URL
    ALIPAY_RETURN_URL           = $env:ALIPAY_RETURN_URL
    ALIPAY_GATEWAY_URL          = $env:ALIPAY_GATEWAY_URL
    # 沙箱实收金额：默认按原价扣款；如需演示小额可在 .env 设 AI_GROUP_PAY_SANDBOX_AMOUNT=0.01
    AI_GROUP_PAY_SANDBOX_AMOUNT = if ($env:AI_GROUP_PAY_SANDBOX_AMOUNT) { $env:AI_GROUP_PAY_SANDBOX_AMOUNT } else { "0" }
    MEMBER_SERVICE_URL          = "http://127.0.0.1:$MemberPort"
}

Start-ServiceWindow "gateway-service" "$root/gateway-service" 8080
Start-ServiceWindow "auth-service" "$root/auth-service" 8081
Start-ServiceWindow "member-service" "$root/member-service" $MemberPort
Start-ServiceWindow "bff-service" "$root/bff-service" 8083
Start-ServiceWindow "group-buy-market" "$root/group/group-buy-market-app" 8091 @{
    AI_GROUP_DEMO_PAYMENT_ENABLED = $env:AI_GROUP_DEMO_PAYMENT_ENABLED
}
Start-ServiceWindow "pay-service" "$root/s-pay-mall-ddd-market/s-pay-mall-ddd-app" 8070 $payEnv
Start-ServiceWindow "ai-agent" "$root/ai-agent/ai-agent-app" 8090 @{
    AGENT_GROUP_LLM_API_KEY       = $env:AGENT_GROUP_LLM_API_KEY
    DASHSCOPE_API_KEY             = $env:DASHSCOPE_API_KEY
    SPRING_AI_OPENAI_API_KEY      = $env:SPRING_AI_OPENAI_API_KEY
    AGENT_GROUP_LLM_BASE_URL      = $env:AGENT_GROUP_LLM_BASE_URL
    AGENT_GROUP_LLM_CHAT_MODEL    = $env:AGENT_GROUP_LLM_CHAT_MODEL
    AGENT_GROUP_LLM_EMBEDDING_MODEL = $env:AGENT_GROUP_LLM_EMBEDDING_MODEL
    AGENT_GROUP_VECTOR_DIMENSION  = $env:AGENT_GROUP_VECTOR_DIMENSION
    AGENT_GROUP_REACTOR_TOOL_BASE_URL = $env:AGENT_GROUP_REACTOR_TOOL_BASE_URL
    AGENT_GROUP_REACTOR_TOOL_TOKEN = $env:AGENT_GROUP_REACTOR_TOOL_TOKEN
    AGENT_GROUP_QDRANT_ENABLED    = if ($env:AGENT_GROUP_QDRANT_ENABLED) { $env:AGENT_GROUP_QDRANT_ENABLED } else { "false" }
    AGENT_GROUP_EMBEDDING_URL     = if ($env:AGENT_GROUP_EMBEDDING_URL) { $env:AGENT_GROUP_EMBEDDING_URL } else { "http://127.0.0.1:1601/v1/tool/embedding/text" }
    MEMBER_SERVICE_BASE_URL       = "http://127.0.0.1:$MemberPort"
    AI_AGENT_AUTO_CONFIG_ENABLED = $env:AI_AGENT_AUTO_CONFIG_ENABLED
    TAVILY_API_KEY                = $env:TAVILY_API_KEY
    MINERU_API_KEY                = $env:MINERU_API_KEY
    QDRANT_URL                    = $env:QDRANT_URL
    QDRANT_PORT                   = if ($env:QDRANT_PORT) { $env:QDRANT_PORT } else { "6334" }
    QDRANT_PREFER_GRPC           = if ($env:QDRANT_PREFER_GRPC) { $env:QDRANT_PREFER_GRPC } else { "false" }
    AGENT_MEMORY_LONGTERM_ENABLED = $env:AGENT_MEMORY_LONGTERM_ENABLED
    AGENT_MEMORY_LONGTERM_COLLECTION = $env:AGENT_MEMORY_LONGTERM_COLLECTION
}
Wait-HttpReady "ai-agent" "http://127.0.0.1:8090/web/health" 120

if (-not (Test-PortListening 1601)) {
    Write-Host "Start reactor-tool on :1601 (init sqlite db + server.py)"
    # Python 依赖已在 ai-agent 启动前按 uv.lock 同步；此处只初始化 SQLite 并启动 HTTP 工具服务。
    Push-Location "$root/ai-agent/reactor-tool"
    try {
        uv run --frozen python -m reactor_tool.db.db_engine
        & .\start.ps1 -Detached
    } finally {
        Pop-Location
    }
}
Wait-HttpReady "reactor-tool" "http://127.0.0.1:1601/health" 60

if (-not (Test-PortListening 5173)) {
    Write-Host "Start frontend on http://localhost:5173"
    # 干净 checkout 没有 node_modules，必须先 pnpm install 再 pnpm dev，否则窗口会因缺 vite 直接退出。
    Push-Location "$root/ai-agent/ui"
    try {
        pnpm install
        $nodeExecutable = (Get-Command node -ErrorAction Stop).Source
        $viteEntry = Join-Path (Get-Location) "node_modules\vite\bin\vite.js"
        if (-not (Test-Path -LiteralPath $viteEntry)) {
            throw "Vite entry not found after pnpm install: $viteEntry"
        }
        Start-Process `
            -FilePath $nodeExecutable `
            -ArgumentList $viteEntry `
            -WorkingDirectory (Get-Location) `
            -WindowStyle Hidden | Out-Null
    } finally {
        Pop-Location
    }
}
Wait-HttpReady "frontend" "http://127.0.0.1:5173/login" 60

Write-Host "==> Smoke tests"
$ready = $false
for ($i = 0; $i -lt 40; $i++) {
    try {
        $h = Invoke-RestMethod -Method GET -Uri "http://127.0.0.1:8080/actuator/health" -TimeoutSec 3
        if ($h.status -eq "UP") { $ready = $true; break }
    } catch {}
    Start-Sleep -Seconds 3
}
if (-not $ready) { Write-Host "WARN: gateway health not UP yet, running smoke anyway" }
& pwsh -NoProfile -File "$root/docs/dev-ops/smoke-test.ps1"
& pwsh -NoProfile -File "$root/docs/dev-ops/smoke-benefit-event.ps1"
& pwsh -NoProfile -File "$root/docs/dev-ops/smoke-benefit-revoke.ps1"
if (Test-PortListening 8070) {
    & pwsh -NoProfile -File "$root/docs/dev-ops/smoke-security.ps1"
} else {
    Write-Host "Skip smoke-security.ps1 (pay :8070 not running)"
}

Write-Host ""
Write-Host "========================================"
Write-Host "  Frontend : http://localhost:5173/login"
Write-Host "  Gateway  : http://localhost:8080"
Write-Host "  group    : http://localhost:8091"
Write-Host "  pay      : http://localhost:8070"
Write-Host "  ai-agent : http://localhost:8090"
Write-Host "  reactor  : http://localhost:1601"
Write-Host "========================================"
