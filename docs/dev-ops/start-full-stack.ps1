# 一键启动：Docker 基础设施 + 微服务 + group/pay + ai-agent + reactor-tool + 前端
param(
    [switch]$StopPort8080Conflict
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$envFile = Join-Path $root ".env"
. (Join-Path $PSScriptRoot "mysql-init.ps1")

function Import-DotEnv($path) {
    if (-not (Test-Path $path)) { return }
    Get-Content $path | ForEach-Object {
        if ($_ -match '^\s*#' -or $_ -notmatch '=') { return }
        $k, $v = $_ -split '=', 2
        $k = $k.Trim()
        $v = $v.Trim().Trim('"')
        if ($k) { Set-Item -Path "env:$k" -Value $v }
    }
}

Import-DotEnv $envFile
$env:JWT_SECRET = if ($env:JWT_SECRET) { $env:JWT_SECRET } else { "ai-group-dev-jwt-secret-2026-k8m3p9x2v7n4q1w6-change-in-prod" }
$env:AI_GROUP_INTERNAL_TOKEN = if ($env:AI_GROUP_INTERNAL_TOKEN) { $env:AI_GROUP_INTERNAL_TOKEN } else { "ai-group-dev-internal-token-change-in-prod" }
$env:AGENT_GROUP_LLM_API_KEY = if ($env:AGENT_GROUP_LLM_API_KEY) { $env:AGENT_GROUP_LLM_API_KEY } else { $env:DASHSCOPE_API_KEY }
$env:AGENT_GROUP_LLM_BASE_URL = if ($env:AGENT_GROUP_LLM_BASE_URL) { $env:AGENT_GROUP_LLM_BASE_URL } else { "https://dashscope.aliyuncs.com/compatible-mode/v1" }
$env:AGENT_GROUP_LLM_CHAT_MODEL = if ($env:AGENT_GROUP_LLM_CHAT_MODEL) { $env:AGENT_GROUP_LLM_CHAT_MODEL } else { "qwen-plus" }
$env:AGENT_GROUP_LLM_EMBEDDING_MODEL = if ($env:AGENT_GROUP_LLM_EMBEDDING_MODEL) { $env:AGENT_GROUP_LLM_EMBEDDING_MODEL } else { "text-embedding-v3" }
$env:AGENT_GROUP_VECTOR_DIMENSION = if ($env:AGENT_GROUP_VECTOR_DIMENSION) { $env:AGENT_GROUP_VECTOR_DIMENSION } else { "1024" }
$env:AGENT_GROUP_REACTOR_TOOL_BASE_URL = if ($env:AGENT_GROUP_REACTOR_TOOL_BASE_URL) { $env:AGENT_GROUP_REACTOR_TOOL_BASE_URL } else { "http://127.0.0.1:1601" }
$env:SPRING_AI_OPENAI_API_KEY = $env:AGENT_GROUP_LLM_API_KEY
$env:SPRING_PROFILES_ACTIVE = "dev"
$env:REACTOR_AGENT_AUTO_CONFIG_ENABLED = "true"

function Test-PortListening($port) {
    return [bool](netstat -ano | Select-String "LISTENING" | Select-String ":$port ")
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
    Stop-PortListener $port
    $envLines = @(
        "Remove-Item Env:SERVER_PORT -ErrorAction SilentlyContinue",
        "`$env:JWT_SECRET='$env:JWT_SECRET'",
        "`$env:AI_GROUP_INTERNAL_TOKEN='$($env:AI_GROUP_INTERNAL_TOKEN)'",
        "`$env:SPRING_PROFILES_ACTIVE='dev'"
    )
    foreach ($key in $extraEnv.Keys) {
        $val = $extraEnv[$key] -replace "'", "''"
        $envLines += "`$env:$key='$val'"
    }
    $envPrefix = $envLines -join "; "
    Write-Host "Start $name on :$port"
    Start-Process powershell -ArgumentList "-NoExit", "-Command", "$envPrefix; cd '$path'; mvn spring-boot:run -q" -WindowStyle Minimized
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
        "TEXT_EMBEDDING_TYPE=openai",
        "TEXT_EMBEDDING_API_KEY=$($env:AGENT_GROUP_LLM_API_KEY)",
        "TEXT_EMBEDDING_BASE_URL=$($env:AGENT_GROUP_LLM_BASE_URL)",
        "TEXT_EMBEDDING_MODEL_NAME=$($env:AGENT_GROUP_LLM_EMBEDDING_MODEL)",
        "TEXT_EMBEDDING_DIMENSION=$($env:AGENT_GROUP_VECTOR_DIMENSION)",
        "DASHSCOPE_API_KEY=$($env:DASHSCOPE_API_KEY)",
        "DASHSCOPE_API_BASE=$($env:AGENT_GROUP_LLM_BASE_URL)",
        "S3_ENDPOINT=$($env:S3_ENDPOINT)",
        "S3_ACCESS_KEY=$($env:S3_ACCESS_KEY)",
        "S3_SECRET_KEY=$($env:S3_SECRET_KEY)",
        "S3_BUCKET_NAME=$($env:S3_BUCKET_NAME)",
        "OSS_SERVER_BASE_URL=$($env:OSS_SERVER_BASE_URL)",
        "VECTOR_STORE_TYPE=$($env:VECTOR_STORE_TYPE)",
        "QDRANT_URL=$($env:QDRANT_URL)",
        "QDRANT_PORT=$($env:QDRANT_PORT)",
        "TAVILY_API_KEY=$($env:TAVILY_API_KEY)",
        "MINERU_API_KEY=$($env:MINERU_API_KEY)"
    )
    Set-Content -Path $rtEnv -Value ($lines -join "`n") -Encoding UTF8
    Write-Host "Synced reactor-tool/.env"
}

if ($StopPort8080Conflict) {
    $line = netstat -ano | Select-String "LISTENING" | Select-String ":8080 " | Select-Object -First 1
    if ($line -match "\s(\d+)\s*$") {
        Stop-Process -Id $Matches[1] -Force -ErrorAction SilentlyContinue
        Start-Sleep -Seconds 2
    }
}

Write-Host "==> Docker infra"
$opsRoot = Join-Path $root "docs/dev-ops"
Start-DockerInfra -OpsRoot $opsRoot -IncludeObservability
Wait-MysqlReady
Wait-RedisReady

Write-Host "==> Init DB (idempotent)"
Invoke-Mysql "$root/auth-service/src/main/resources/schema.sql"
Invoke-Mysql "$root/member-service/src/main/resources/schema.sql"
Invoke-Mysql "$root/docs/dev-ops/mysql/sql/member_db/02-platform-schema-migrate.sql"
Invoke-Mysql "$root/docs/dev-ops/mysql/sql/agent_db/01-agent_db.sql"
Invoke-Mysql "$root/docs/dev-ops/mysql/sql/agent_db/02-dev-seed.sql"
# group/pay 为全量转储（DROP+重灌）：仅首次初始化执行，已存在则跳过以保留订单/拼团数据
Invoke-MysqlDumpOnce "$root/group/docs/dev-ops/mysql/sql/2-29-group_buy_market.sql" -Schema "group_buy_market" -MarkerTable "group_buy_order"
Invoke-MysqlDumpOnce "$root/s-pay-mall-ddd-market/docs/dev-ops/mysql/sql/s-pay-mall-ddd-market.sql" -Schema "s_pay_mall_ddd_market" -MarkerTable "pay_order" -PayBase
Invoke-Mysql "$root/s-pay-mall-ddd-market/docs/dev-ops/mysql/sql/V3_benefit_event.sql"
Invoke-Mysql "$root/s-pay-mall-ddd-market/docs/dev-ops/mysql/sql/V4_settlement_notified.sql"
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
Pop-Location

$payEnv = @{
    ALIPAY_ENABLED              = $env:ALIPAY_ENABLED
    ALIPAY_APP_ID               = $env:ALIPAY_APP_ID
    ALIPAY_MERCHANT_PRIVATE_KEY = $env:ALIPAY_MERCHANT_PRIVATE_KEY
    ALIPAY_PUBLIC_KEY           = $env:ALIPAY_PUBLIC_KEY
    ALIPAY_NOTIFY_URL           = $env:ALIPAY_NOTIFY_URL
    ALIPAY_RETURN_URL           = $env:ALIPAY_RETURN_URL
    ALIPAY_GATEWAY_URL          = $env:ALIPAY_GATEWAY_URL
}

Start-ServiceWindow "gateway-service" "$root/gateway-service" 8080
Start-ServiceWindow "auth-service" "$root/auth-service" 8081
Start-ServiceWindow "member-service" "$root/member-service" 8082
Start-ServiceWindow "bff-service" "$root/bff-service" 8083
Start-ServiceWindow "group-buy-market" "$root/group/group-buy-market-app" 8091
Start-ServiceWindow "pay-service" "$root/s-pay-mall-ddd-market/s-pay-mall-ddd-app" 8070 $payEnv
Start-ServiceWindow "ai-agent" "$root/ai-agent/Reactor-agent-app" 8090 @{
    AGENT_GROUP_LLM_API_KEY       = $env:AGENT_GROUP_LLM_API_KEY
    DASHSCOPE_API_KEY             = $env:DASHSCOPE_API_KEY
    SPRING_AI_OPENAI_API_KEY      = $env:SPRING_AI_OPENAI_API_KEY
    AGENT_GROUP_LLM_BASE_URL      = $env:AGENT_GROUP_LLM_BASE_URL
    AGENT_GROUP_LLM_CHAT_MODEL    = $env:AGENT_GROUP_LLM_CHAT_MODEL
    AGENT_GROUP_LLM_EMBEDDING_MODEL = $env:AGENT_GROUP_LLM_EMBEDDING_MODEL
    AGENT_GROUP_VECTOR_DIMENSION  = $env:AGENT_GROUP_VECTOR_DIMENSION
    AGENT_GROUP_REACTOR_TOOL_BASE_URL = $env:AGENT_GROUP_REACTOR_TOOL_BASE_URL
    AGENT_GROUP_AGENT_DISPATCH_URL = "http://127.0.0.1:8090/AutoAgent"
    AGENT_GROUP_QDRANT_ENABLED    = if ($env:AGENT_GROUP_QDRANT_ENABLED) { $env:AGENT_GROUP_QDRANT_ENABLED } else { "false" }
    AGENT_GROUP_EMBEDDING_URL     = if ($env:AGENT_GROUP_EMBEDDING_URL) { $env:AGENT_GROUP_EMBEDDING_URL } else { "http://127.0.0.1:1601/v1/tool/embedding/text" }
    REACTOR_AGENT_AUTO_CONFIG_ENABLED = "true"
    TAVILY_API_KEY                = $env:TAVILY_API_KEY
    MINERU_API_KEY                = $env:MINERU_API_KEY
    S3_ENDPOINT                   = $env:S3_ENDPOINT
    S3_ACCESS_KEY                 = $env:S3_ACCESS_KEY
    S3_SECRET_KEY                 = $env:S3_SECRET_KEY
    S3_BUCKET_NAME                = $env:S3_BUCKET_NAME
    QDRANT_URL                    = $env:QDRANT_URL
    QDRANT_PORT                   = if ($env:QDRANT_PORT) { $env:QDRANT_PORT } else { "6334" }
    QDRANT_PREFER_GRPC           = if ($env:QDRANT_PREFER_GRPC) { $env:QDRANT_PREFER_GRPC } else { "false" }
}

Sync-ReactorToolEnv
if (-not (Test-PortListening 1601)) {
    Write-Host "Start reactor-tool on :1601"
    Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd '$root/ai-agent/reactor-tool'; uv run python -m reactor_tool.main" -WindowStyle Minimized
    Start-Sleep -Seconds 8
}

if (-not (Test-PortListening 5173)) {
    Write-Host "Start frontend on http://localhost:5173"
    Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd '$root/ai-agent/ui'; pnpm dev" -WindowStyle Minimized
    Start-Sleep -Seconds 5
}

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
powershell -File "$root/docs/dev-ops/smoke-test.ps1"
powershell -File "$root/docs/dev-ops/smoke-benefit-event.ps1"
powershell -File "$root/docs/dev-ops/smoke-benefit-revoke.ps1"
if (Test-PortListening 8070) {
    powershell -File "$root/docs/dev-ops/smoke-security.ps1"
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
