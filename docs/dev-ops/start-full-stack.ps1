# 一键启动：Docker 基础设施 + 微服务 + group/pay + ai-agent + runtime/tools + 前端
param(
    [switch]$StopPort8080Conflict,
    [switch]$IncludeObservability,
    [switch]$EphemeralLlmCredentials,
    [switch]$DemoLite,
    [switch]$Preflight,
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
        if ($k -and -not [Environment]::GetEnvironmentVariable($k, "Process")) {
            Set-Item -Path "env:$k" -Value $v
        }
    }
}

Import-DotEnv $envFile

function Test-UsableSecret([string]$name, [int]$minimumLength = 32) {
    $value = [Environment]::GetEnvironmentVariable($name, "Process")
    return -not [string]::IsNullOrWhiteSpace($value) -and $value.Length -ge $minimumLength -and
        $value -notmatch '(?i)change-me|replace-me|your-'
}

function Resolve-PreflightArtifactRoot() {
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
    return $null
}

function Get-RunningComposeConfigDrift() {
    $composeFile = Join-Path $PSScriptRoot "docker-compose-platform.yml"
    if (-not (Test-Path -LiteralPath $composeFile -PathType Leaf)) {
        throw "platform Compose file is missing: $composeFile"
    }
    $expectedByService = @{}
    $hashLines = & docker compose --env-file $envFile -f $composeFile config --hash '*' 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose config hash failed"
    }
    foreach ($line in $hashLines) {
        if ($line -match '^([^\s]+)\s+([0-9a-f]+)$') {
            $expectedByService[$Matches[1]] = $Matches[2]
        }
    }
    if ($expectedByService.Count -eq 0) {
        throw "docker compose config hash returned no service hashes"
    }
    $containerLines = & docker compose --env-file $envFile -f $composeFile ps -a --format json 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose ps failed"
    }
    $drifted = [System.Collections.Generic.List[string]]::new()
    foreach ($line in $containerLines) {
        if ([string]::IsNullOrWhiteSpace([string]$line)) { continue }
        try {
            $container = $line | ConvertFrom-Json -ErrorAction Stop
        } catch {
            throw "docker compose ps returned invalid JSON"
        }
        if ([string]$container.State -ne "running" -or -not $expectedByService.ContainsKey([string]$container.Service)) {
            continue
        }
        $inspect = & docker inspect $container.ID | ConvertFrom-Json -ErrorAction Stop
        $actualHash = [string]$inspect[0].Config.Labels.'com.docker.compose.config-hash'
        if ($actualHash -ne $expectedByService[[string]$container.Service]) {
            $drifted.Add([string]$container.Service) | Out-Null
        }
    }
    return $drifted.ToArray()
}

function Invoke-StartupPreflight {
    $failures = [System.Collections.Generic.List[string]]::new()
    foreach ($command in @("docker", "mvn", "java", "jar", "uv", "pnpm", "node")) {
        if (-not (Get-Command $command -ErrorAction SilentlyContinue)) {
            $failures.Add("required command is unavailable: $command") | Out-Null
        }
    }
    foreach ($secret in @(
            "AI_GROUP_INTERNAL_TOKEN", "XXL_JOB_ACCESS_TOKEN", "MYSQL_ROOT_PASSWORD",
            "REDIS_PASSWORD", "POSTGRES_PASSWORD", "MINIO_ROOT_PASSWORD")) {
        if (-not (Test-UsableSecret $secret)) {
            $failures.Add("missing or placeholder secret: $secret") | Out-Null
        }
    }
    if (-not (Test-UsableSecret "AGENT_GROUP_LLM_API_KEY") -and -not (Test-UsableSecret "DASHSCOPE_API_KEY")) {
        $failures.Add("a real LLM credential is required: AGENT_GROUP_LLM_API_KEY or DASHSCOPE_API_KEY") | Out-Null
    }
    if ($IncludeObservability) {
        foreach ($secret in @("GRAFANA_ADMIN_PASSWORD", "REDIS_ADMIN_PASSWORD")) {
            if (-not (Test-UsableSecret $secret)) {
                $failures.Add("observability requires secret: $secret") | Out-Null
            }
        }
    }
    if (-not $DemoLite) {
        $artifactRoot = Resolve-PreflightArtifactRoot
        if ([string]::IsNullOrWhiteSpace($artifactRoot)) {
            $failures.Add("full-stack requires AI_GROUP_FROZEN_ARTIFACT_ROOT or a sibling ai-group-generated-recovery/p130-generated-* directory") | Out-Null
        } else {
            foreach ($relativeArtifact in @(
                    "group/group-buy-market-app/target/group-buy-market-app.jar",
                    "s-pay-mall-ddd-market/s-pay-mall-ddd-app/target/s-pay-mall-ddd-app.jar")) {
                if (-not (Test-Path -LiteralPath (Join-Path $artifactRoot $relativeArtifact) -PathType Leaf)) {
                    $failures.Add("recovered frozen artifact missing: $(Join-Path $artifactRoot $relativeArtifact)") | Out-Null
                }
            }
        }
    }
    if (Get-Command docker -ErrorAction SilentlyContinue) {
        try {
            $driftedServices = Get-RunningComposeConfigDrift
            if ($driftedServices.Count -gt 0) {
                $failures.Add("running Docker infra config drift: $($driftedServices -join ', '); reconcile these services explicitly before startup") | Out-Null
            }
        } catch {
            $failures.Add("unable to verify running Docker infra config drift: $($_.Exception.Message)") | Out-Null
        }
    }
    try {
        & pwsh -NoProfile -File (Join-Path $PSScriptRoot "verify-frozen-manifest.ps1") | Out-Host
        if ($LASTEXITCODE -ne 0) { throw "frozen manifest verifier exit $LASTEXITCODE" }
    } catch {
        $failures.Add("frozen Manifest verification failed: $($_.Exception.Message)") | Out-Null
    }
    if ($failures.Count -gt 0) {
        Write-Host "P160 startup preflight failed:" -ForegroundColor Red
        foreach ($failure in $failures) {
            Write-Host " - $failure" -ForegroundColor Red
        }
        return $false
    }
    Write-Host "P160 startup preflight passed (mode=$(if ($DemoLite) { 'demo-lite' } else { 'full-stack' }))."
    return $true
}

$preflightPassed = Invoke-StartupPreflight
if (-not $preflightPassed) {
    exit 1
}
if ($Preflight) {
    exit 0
}

function Require-Secret([string]$name, [int]$minimumLength = 32) {
    $value = [Environment]::GetEnvironmentVariable($name, "Process")
    if (-not $value -or $value.Length -lt $minimumLength -or $value -match '(?i)change-me|replace-me|your-') {
        throw "$name must be set to a random value of at least $minimumLength characters in .env or the process environment"
    }
    return $value
}
function New-RandomSecret() {
    $bytes = New-Object byte[] 32
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    return [Convert]::ToBase64String($bytes)
}
function Ensure-RsaKeyPair([string]$privateName, [string]$publicName) {
    $privateKey = [Environment]::GetEnvironmentVariable($privateName, "Process")
    $publicKey = [Environment]::GetEnvironmentVariable($publicName, "Process")
    if ($privateKey -or $publicKey) {
        if (-not $privateKey -or -not $publicKey) {
            throw "$privateName and $publicName must be configured together"
        }
        return
    }
    $rsa = [System.Security.Cryptography.RSA]::Create(2048)
    try {
        Set-Item -Path "env:$privateName" -Value ([Convert]::ToBase64String($rsa.ExportPkcs8PrivateKey()))
        Set-Item -Path "env:$publicName" -Value ([Convert]::ToBase64String($rsa.ExportSubjectPublicKeyInfo()))
    } finally {
        $rsa.Dispose()
    }
}
Ensure-RsaKeyPair "AUTH_JWT_PRIVATE_KEY_BASE64" "AUTH_JWT_PUBLIC_KEY_BASE64"
Ensure-RsaKeyPair "GATEWAY_IDENTITY_PRIVATE_KEY_BASE64" "GATEWAY_IDENTITY_PUBLIC_KEY_BASE64"
$env:JWT_ISSUER = if ($env:JWT_ISSUER) { $env:JWT_ISSUER } else { "ai-group-auth" }
$env:JWT_AUDIENCE = if ($env:JWT_AUDIENCE) { $env:JWT_AUDIENCE } else { "ai-group-api" }
$env:AUTH_JWT_KEY_ID = if ($env:AUTH_JWT_KEY_ID) { $env:AUTH_JWT_KEY_ID } else { "auth-rsa-current" }
$env:GATEWAY_IDENTITY_ISSUER = if ($env:GATEWAY_IDENTITY_ISSUER) { $env:GATEWAY_IDENTITY_ISSUER } else { "ai-group-gateway" }
$env:GATEWAY_IDENTITY_AUDIENCE = if ($env:GATEWAY_IDENTITY_AUDIENCE) { $env:GATEWAY_IDENTITY_AUDIENCE } else { "ai-group-downstream" }
$env:GATEWAY_IDENTITY_KEY_ID = if ($env:GATEWAY_IDENTITY_KEY_ID) { $env:GATEWAY_IDENTITY_KEY_ID } else { "gateway-rsa-current" }
$env:GATEWAY_IDENTITY_TTL_SECONDS = if ($env:GATEWAY_IDENTITY_TTL_SECONDS) { $env:GATEWAY_IDENTITY_TTL_SECONDS } else { "60" }
$env:BFF_SERVICE_CLIENT_ID = if ($env:BFF_SERVICE_CLIENT_ID) { $env:BFF_SERVICE_CLIENT_ID } else { "bff-service" }
$env:AGENT_SERVICE_CLIENT_ID = if ($env:AGENT_SERVICE_CLIENT_ID) { $env:AGENT_SERVICE_CLIENT_ID } else { "ai-agent" }
$env:AI_GROUP_INTERNAL_TOKEN = Require-Secret "AI_GROUP_INTERNAL_TOKEN"
$env:AI_GROUP_LEGACY_INTERNAL_TOKEN_ENABLED = if ($env:AI_GROUP_LEGACY_INTERNAL_TOKEN_ENABLED) { $env:AI_GROUP_LEGACY_INTERNAL_TOKEN_ENABLED } else { "true" }
$env:JWT_JWK_SET_URI = if ($env:JWT_JWK_SET_URI) { $env:JWT_JWK_SET_URI } else { "http://127.0.0.1:8081/.well-known/jwks.json" }
$env:AI_GROUP_AUTH_SERVICE_TOKEN_ENDPOINT = if ($env:AI_GROUP_AUTH_SERVICE_TOKEN_ENDPOINT) { $env:AI_GROUP_AUTH_SERVICE_TOKEN_ENDPOINT } else { "http://127.0.0.1:8081/api/auth/service-token" }
$env:BFF_SERVICE_CLIENT_SECRET = if ($env:BFF_SERVICE_CLIENT_SECRET) { Require-Secret "BFF_SERVICE_CLIENT_SECRET" } else { New-RandomSecret }
$env:AGENT_SERVICE_CLIENT_SECRET = if ($env:AGENT_SERVICE_CLIENT_SECRET) { Require-Secret "AGENT_SERVICE_CLIENT_SECRET" } else { New-RandomSecret }
$env:XXL_JOB_ACCESS_TOKEN = Require-Secret "XXL_JOB_ACCESS_TOKEN"
$env:XXL_JOB_ADMIN_PORT = if ($env:XXL_JOB_ADMIN_PORT) { $env:XXL_JOB_ADMIN_PORT } else { "18081" }
$env:XXL_JOB_ADMIN_ADDRESSES = if ($env:XXL_JOB_ADMIN_ADDRESSES) { $env:XXL_JOB_ADMIN_ADDRESSES } else { "http://127.0.0.1:$($env:XXL_JOB_ADMIN_PORT)" }
$env:AI_GROUP_INTERNAL_AUTH_ENABLED = if ($env:AI_GROUP_INTERNAL_AUTH_ENABLED) { $env:AI_GROUP_INTERNAL_AUTH_ENABLED } else { "true" }
$env:ALIPAY_ENABLED = if ($env:ALIPAY_ENABLED) { $env:ALIPAY_ENABLED } else { "false" }
$env:AI_GROUP_DEMO_PAYMENT_ENABLED = if ($env:AI_GROUP_DEMO_PAYMENT_ENABLED) { $env:AI_GROUP_DEMO_PAYMENT_ENABLED } else { "false" }
$canonicalReactorToolToken = if ($env:AGENT_GROUP_REACTOR_TOOL_TOKEN) {
    $env:AGENT_GROUP_REACTOR_TOOL_TOKEN
} elseif ($env:REACTOR_TOOL_TOKEN) {
    $env:REACTOR_TOOL_TOKEN
} else {
    New-RandomSecret
}
$env:AGENT_GROUP_REACTOR_TOOL_TOKEN = $canonicalReactorToolToken
$env:REACTOR_TOOL_TOKEN = $canonicalReactorToolToken
$env:AGENT_GROUP_LLM_API_KEY = if ($env:AGENT_GROUP_LLM_API_KEY) { $env:AGENT_GROUP_LLM_API_KEY } else { $env:DASHSCOPE_API_KEY }
$env:AGENT_GROUP_LLM_BASE_URL = if ($env:AGENT_GROUP_LLM_BASE_URL) { $env:AGENT_GROUP_LLM_BASE_URL } else { "https://dashscope.aliyuncs.com/compatible-mode/v1" }
$env:AGENT_GROUP_LLM_CHAT_MODEL = if ($env:AGENT_GROUP_LLM_CHAT_MODEL) { $env:AGENT_GROUP_LLM_CHAT_MODEL } else { "qwen-plus" }
$env:AGENT_GROUP_VISION_MODEL = if ($env:AGENT_GROUP_VISION_MODEL) { $env:AGENT_GROUP_VISION_MODEL } else { $env:AGENT_GROUP_LLM_CHAT_MODEL }
$env:AGENT_GROUP_MEMORY_SUMMARY_MODEL = if ($env:AGENT_GROUP_MEMORY_SUMMARY_MODEL) { $env:AGENT_GROUP_MEMORY_SUMMARY_MODEL } else { $env:AGENT_GROUP_LLM_CHAT_MODEL }
$env:REPORT_MODEL = if ($env:REPORT_MODEL) { $env:REPORT_MODEL } else { $env:AGENT_GROUP_LLM_CHAT_MODEL }
$env:AGENT_GROUP_EMBEDDING_API_KEY = if ($env:AGENT_GROUP_EMBEDDING_API_KEY) {
    $env:AGENT_GROUP_EMBEDDING_API_KEY
} elseif ($env:DASHSCOPE_API_KEY) {
    $env:DASHSCOPE_API_KEY
} else {
    $env:AGENT_GROUP_LLM_API_KEY
}
$env:AGENT_GROUP_EMBEDDING_BASE_URL = if ($env:AGENT_GROUP_EMBEDDING_BASE_URL) {
    $env:AGENT_GROUP_EMBEDDING_BASE_URL
} elseif ($env:DASHSCOPE_API_BASE) {
    $env:DASHSCOPE_API_BASE
} else {
    $env:AGENT_GROUP_LLM_BASE_URL
}
$env:AGENT_GROUP_LLM_EMBEDDING_MODEL = if ($env:AGENT_GROUP_LLM_EMBEDDING_MODEL) { $env:AGENT_GROUP_LLM_EMBEDDING_MODEL } else { "text-embedding-v3" }
$env:AGENT_GROUP_VECTOR_DIMENSION = if ($env:AGENT_GROUP_VECTOR_DIMENSION) { $env:AGENT_GROUP_VECTOR_DIMENSION } else { "1024" }
$env:AGENT_GROUP_REACTOR_TOOL_BASE_URL = if ($env:AGENT_GROUP_REACTOR_TOOL_BASE_URL) { $env:AGENT_GROUP_REACTOR_TOOL_BASE_URL } else { "http://127.0.0.1:1601" }
$env:REACTOR_TOOL_ENV = if ($env:REACTOR_TOOL_ENV) { $env:REACTOR_TOOL_ENV } else { "local" }
$env:REACTOR_TOOL_HOST = if ($env:REACTOR_TOOL_HOST) { $env:REACTOR_TOOL_HOST } else { "127.0.0.1" }
$env:REACTOR_TOOL_CORS_ORIGINS = if ($env:REACTOR_TOOL_CORS_ORIGINS) { $env:REACTOR_TOOL_CORS_ORIGINS } else { "http://localhost:5173,http://127.0.0.1:5173" }
$env:SKILL_ALLOWED_RUNTIMES = if ($env:SKILL_ALLOWED_RUNTIMES) { $env:SKILL_ALLOWED_RUNTIMES } else { "python" }
$env:MYSQL_HOST = if ($env:MYSQL_HOST) { $env:MYSQL_HOST } else { "127.0.0.1" }
$env:MYSQL_PORT = if ($env:MYSQL_PORT) { $env:MYSQL_PORT } else { "13306" }
$env:MYSQL_USER = if ($env:MYSQL_USER) { $env:MYSQL_USER } else { "root" }
$env:MYSQL_ROOT_PASSWORD = Require-Secret "MYSQL_ROOT_PASSWORD"
$env:REDIS_HOST = if ($env:REDIS_HOST) { $env:REDIS_HOST } else { "127.0.0.1" }
$env:REDIS_PORT = if ($env:REDIS_PORT) { $env:REDIS_PORT } else { "16379" }
$env:REDIS_PASSWORD = Require-Secret "REDIS_PASSWORD"
$env:KAFKA_BOOTSTRAP_SERVERS = if ($env:KAFKA_BOOTSTRAP_SERVERS) { $env:KAFKA_BOOTSTRAP_SERVERS } else { "127.0.0.1:9092" }
$env:POSTGRES_HOST = if ($env:POSTGRES_HOST) { $env:POSTGRES_HOST } else { "127.0.0.1" }
$env:POSTGRES_PORT = if ($env:POSTGRES_PORT) { $env:POSTGRES_PORT } else { "15432" }
$env:POSTGRES_DB = if ($env:POSTGRES_DB) { $env:POSTGRES_DB } else { "agent_memory" }
$env:POSTGRES_USER = if ($env:POSTGRES_USER) { $env:POSTGRES_USER } else { "agent" }
$env:POSTGRES_PASSWORD = Require-Secret "POSTGRES_PASSWORD"
$env:MINIO_PORT = if ($env:MINIO_PORT) { $env:MINIO_PORT } else { "9000" }
$env:MINIO_CONSOLE_PORT = if ($env:MINIO_CONSOLE_PORT) { $env:MINIO_CONSOLE_PORT } else { "9001" }
$env:MINIO_ENDPOINT = if ($env:MINIO_ENDPOINT) { $env:MINIO_ENDPOINT } else { "http://127.0.0.1:$($env:MINIO_PORT)" }
$env:MINIO_ROOT_USER = if ($env:MINIO_ROOT_USER) { $env:MINIO_ROOT_USER } else { "agent" }
$env:MINIO_ROOT_PASSWORD = Require-Secret "MINIO_ROOT_PASSWORD"
$env:MINIO_BUCKET_NAME = if ($env:MINIO_BUCKET_NAME) { $env:MINIO_BUCKET_NAME } else { "ai-group-files" }
if ([bool]$env:MINIO_ACCESS_KEY -xor [bool]$env:MINIO_SECRET_KEY) {
    throw "MINIO_ACCESS_KEY and MINIO_SECRET_KEY must be configured together"
}
$minioAccessKey = if ($env:MINIO_ACCESS_KEY) { $env:MINIO_ACCESS_KEY } else { $env:MINIO_ROOT_USER }
$minioSecretKey = if ($env:MINIO_SECRET_KEY) { $env:MINIO_SECRET_KEY } else { $env:MINIO_ROOT_PASSWORD }
$env:NACOS_HOST = if ($env:NACOS_HOST) { $env:NACOS_HOST } else { "127.0.0.1" }
$env:NACOS_PORT = if ($env:NACOS_PORT) { $env:NACOS_PORT } else { "8848" }
$env:NACOS_USERNAME = if ($env:NACOS_USERNAME) { $env:NACOS_USERNAME } else { "nacos" }
$env:NACOS_PASSWORD = if ($env:NACOS_PASSWORD) { $env:NACOS_PASSWORD } else { "nacos" }
$env:NACOS_AUTH_TOKEN = if ($env:NACOS_AUTH_TOKEN) { $env:NACOS_AUTH_TOKEN } else { New-RandomSecret }
$env:NACOS_AUTH_IDENTITY_KEY = if ($env:NACOS_AUTH_IDENTITY_KEY) { $env:NACOS_AUTH_IDENTITY_KEY } else { New-RandomSecret }
$env:NACOS_AUTH_IDENTITY_VALUE = if ($env:NACOS_AUTH_IDENTITY_VALUE) { $env:NACOS_AUTH_IDENTITY_VALUE } else { New-RandomSecret }
$env:GRAFANA_ADMIN_PASSWORD = if ($IncludeObservability) { Require-Secret "GRAFANA_ADMIN_PASSWORD" } else { $env:GRAFANA_ADMIN_PASSWORD }
$env:REDIS_ADMIN_PASSWORD = if ($IncludeObservability) { Require-Secret "REDIS_ADMIN_PASSWORD" } else { $env:REDIS_ADMIN_PASSWORD }
$env:FILE_SAVE_PATH = if ($env:FILE_SAVE_PATH) { $env:FILE_SAVE_PATH } else { "skilloutput" }
$env:FILE_SERVER_URL = if ($env:FILE_SERVER_URL) { $env:FILE_SERVER_URL } else { "http://127.0.0.1:1601/v1/file_tool" }
$env:FILE_MAX_SIZE_MB = if ($env:FILE_MAX_SIZE_MB) { $env:FILE_MAX_SIZE_MB } else { "100" }
$env:AGENT_MEMORY_LONGTERM_ENABLED = if ($env:AGENT_MEMORY_LONGTERM_ENABLED) { $env:AGENT_MEMORY_LONGTERM_ENABLED } else { "true" }
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

function Wait-PortReady($name, $port, [int]$timeoutSec = 60) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    do {
        if (Test-PortListening $port) {
            Write-Host "$name is listening on :$port"
            return
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)

    throw "$name did not listen on :$port after ${timeoutSec}s"
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

function New-ServiceEnvironment($name, $port, $extraEnv = @{}) {
    $serviceEnvironment = @{
        PATH                           = [string]$env:PATH
        SYSTEMROOT                     = [string]$env:SYSTEMROOT
        TEMP                           = [string]$env:TEMP
        TMP                            = [string]$env:TMP
        SERVER_PORT                   = [string]$port
        JWT_ISSUER                    = [string]$env:JWT_ISSUER
        JWT_AUDIENCE                  = [string]$env:JWT_AUDIENCE
        JWT_JWK_SET_URI               = [string]$env:JWT_JWK_SET_URI
        AUTH_JWT_KEY_ID               = [string]$env:AUTH_JWT_KEY_ID
        GATEWAY_IDENTITY_ISSUER       = [string]$env:GATEWAY_IDENTITY_ISSUER
        GATEWAY_IDENTITY_AUDIENCE     = [string]$env:GATEWAY_IDENTITY_AUDIENCE
        GATEWAY_IDENTITY_KEY_ID       = [string]$env:GATEWAY_IDENTITY_KEY_ID
        GATEWAY_IDENTITY_TTL_SECONDS  = [string]$env:GATEWAY_IDENTITY_TTL_SECONDS
        AI_GROUP_AUTH_SERVICE_TOKEN_ENDPOINT = [string]$env:AI_GROUP_AUTH_SERVICE_TOKEN_ENDPOINT
        AI_GROUP_INTERNAL_AUTH_ENABLED = [string]$env:AI_GROUP_INTERNAL_AUTH_ENABLED
        MYSQL_HOST                    = [string]$env:MYSQL_HOST
        MYSQL_PORT                    = [string]$env:MYSQL_PORT
        MYSQL_USER                    = [string]$env:MYSQL_USER
        MYSQL_ROOT_PASSWORD           = [string]$env:MYSQL_ROOT_PASSWORD
        REDIS_HOST                    = [string]$env:REDIS_HOST
        REDIS_PORT                    = [string]$env:REDIS_PORT
        REDIS_PASSWORD                = [string]$env:REDIS_PASSWORD
        KAFKA_BOOTSTRAP_SERVERS       = [string]$env:KAFKA_BOOTSTRAP_SERVERS
        NACOS_HOST                    = [string]$env:NACOS_HOST
        NACOS_PORT                    = [string]$env:NACOS_PORT
        NACOS_USERNAME                = [string]$env:NACOS_USERNAME
        NACOS_PASSWORD                = [string]$env:NACOS_PASSWORD
        XXL_JOB_ADMIN_ADDRESSES       = [string]$env:XXL_JOB_ADMIN_ADDRESSES
        XXL_JOB_ACCESS_TOKEN          = [string]$env:XXL_JOB_ACCESS_TOKEN
        SPRING_PROFILES_ACTIVE        = "dev"
    }
    if ($name -eq "auth-service") {
        $serviceEnvironment["AUTH_JWT_PRIVATE_KEY_BASE64"] = [string]$env:AUTH_JWT_PRIVATE_KEY_BASE64
        $serviceEnvironment["AUTH_JWT_PUBLIC_KEY_BASE64"] = [string]$env:AUTH_JWT_PUBLIC_KEY_BASE64
        $serviceEnvironment["GATEWAY_IDENTITY_PUBLIC_KEY_BASE64"] = [string]$env:GATEWAY_IDENTITY_PUBLIC_KEY_BASE64
        $serviceEnvironment["BFF_SERVICE_CLIENT_ID"] = [string]$env:BFF_SERVICE_CLIENT_ID
        $serviceEnvironment["BFF_SERVICE_CLIENT_SECRET"] = [string]$env:BFF_SERVICE_CLIENT_SECRET
        $serviceEnvironment["AGENT_SERVICE_CLIENT_ID"] = [string]$env:AGENT_SERVICE_CLIENT_ID
        $serviceEnvironment["AGENT_SERVICE_CLIENT_SECRET"] = [string]$env:AGENT_SERVICE_CLIENT_SECRET
    } elseif ($name -eq "gateway-service") {
        $serviceEnvironment["AI_GROUP_INTERNAL_TOKEN"] = [string]$env:AI_GROUP_INTERNAL_TOKEN
        $serviceEnvironment["AI_GROUP_LEGACY_INTERNAL_TOKEN_ENABLED"] = [string]$env:AI_GROUP_LEGACY_INTERNAL_TOKEN_ENABLED
        $serviceEnvironment["AUTH_JWT_PUBLIC_KEY_BASE64"] = [string]$env:AUTH_JWT_PUBLIC_KEY_BASE64
        $serviceEnvironment["GATEWAY_IDENTITY_PRIVATE_KEY_BASE64"] = [string]$env:GATEWAY_IDENTITY_PRIVATE_KEY_BASE64
        $serviceEnvironment["GATEWAY_IDENTITY_PUBLIC_KEY_BASE64"] = [string]$env:GATEWAY_IDENTITY_PUBLIC_KEY_BASE64
    } elseif ($name -in @("member-service", "bff-service", "ai-agent")) {
        $serviceEnvironment["AUTH_JWT_PUBLIC_KEY_BASE64"] = [string]$env:AUTH_JWT_PUBLIC_KEY_BASE64
        $serviceEnvironment["GATEWAY_IDENTITY_PUBLIC_KEY_BASE64"] = [string]$env:GATEWAY_IDENTITY_PUBLIC_KEY_BASE64
        if ($name -eq "bff-service") {
            $serviceEnvironment["BFF_SERVICE_CLIENT_ID"] = [string]$env:BFF_SERVICE_CLIENT_ID
            $serviceEnvironment["BFF_SERVICE_CLIENT_SECRET"] = [string]$env:BFF_SERVICE_CLIENT_SECRET
        }
        if ($name -eq "ai-agent") {
            $serviceEnvironment["AGENT_SERVICE_CLIENT_ID"] = [string]$env:AGENT_SERVICE_CLIENT_ID
            $serviceEnvironment["AGENT_SERVICE_CLIENT_SECRET"] = [string]$env:AGENT_SERVICE_CLIENT_SECRET
        }
    }
    if ($name -in @("group-buy-market", "pay-service")) {
        $serviceEnvironment["AI_GROUP_INTERNAL_TOKEN"] = [string]$env:AI_GROUP_INTERNAL_TOKEN
    }
    # XXL Admin runs in Docker while executors run on the Windows host. Auto-detected
    # link-local addresses are not routable from the Admin container, so advertise a
    # Docker Desktop host alias and bind the embedded executor on all local interfaces.
    # These ports are independent of the HTTP service ports above.
    $xxlExecutors = @{
        "member-service"   = @{ AppName = "member"; Port = 9997 }
        "pay-service"      = @{ AppName = "pay"; Port = 9998 }
        "group-buy-market" = @{ AppName = "group"; Port = 9999 }
        "ai-agent"         = @{ AppName = "ai-agent"; Port = 9996 }
    }
    if ($xxlExecutors.ContainsKey($name)) {
        $executor = $xxlExecutors[$name]
        $advertiseHost = if ($env:XXL_JOB_EXECUTOR_ADVERTISE_HOST) {
            [string]$env:XXL_JOB_EXECUTOR_ADVERTISE_HOST
        } else {
            "host.docker.internal"
        }
        $serviceEnvironment["XXL_JOB_EXECUTOR_APPNAME"] = [string]$executor.AppName
        $serviceEnvironment["XXL_JOB_EXECUTOR_PORT"] = [string]$executor.Port
        $serviceEnvironment["XXL_JOB_EXECUTOR_IP"] = "0.0.0.0"
        $serviceEnvironment["XXL_JOB_EXECUTOR_ADDRESS"] = "http://${advertiseHost}:$($executor.Port)/"
        $serviceEnvironment["XXL_JOB_LOGPATH"] = Join-Path $runtimeDataRoot "logs\xxl-job\$($executor.AppName)"
    }
    foreach ($key in $extraEnv.Keys) {
        $serviceEnvironment[$key] = [string]$extraEnv[$key]
    }
    return $serviceEnvironment
}

function Start-ServiceWindow($name, $path, $port, $extraEnv = @{}) {
    if (Test-PortListening $port) {
        throw "Port :$port is already in use. Stop the existing listener before starting $name."
    }
    $serviceEnvironment = New-ServiceEnvironment $name $port $extraEnv
    $serviceLogDirectory = Join-Path $runtimeDataRoot "logs"
    New-Item -ItemType Directory -Path $serviceLogDirectory -Force | Out-Null
    $stdoutLog = Join-Path $serviceLogDirectory "$name.stdout.log"
    $stderrLog = Join-Path $serviceLogDirectory "$name.stderr.log"
    Remove-Item -LiteralPath $stdoutLog, $stderrLog -Force -ErrorAction SilentlyContinue
    Write-Host "Start $name on :$port"
    Start-Process pwsh `
        -ArgumentList "-NoProfile", "-Command", "`$ErrorActionPreference = 'Stop'; mvn spring-boot:run -q" `
        -WorkingDirectory $path `
        -Environment $serviceEnvironment `
        -RedirectStandardOutput $stdoutLog `
        -RedirectStandardError $stderrLog `
        -WindowStyle Hidden
    Start-Sleep -Seconds 12
}

function Start-RecoveredJar($name, $jarPath, $port, $extraEnv = @{}) {
    if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) {
        throw "Recovered frozen service artifact is missing for ${name}: $jarPath"
    }
    if (Test-PortListening $port) {
        throw "Port :$port is already in use. Stop the existing listener before starting $name."
    }
    $workingDirectory = Join-Path $runtimeDataRoot $name
    New-Item -ItemType Directory -Path $workingDirectory -Force | Out-Null
    $serviceLogDirectory = Join-Path $runtimeDataRoot "logs"
    New-Item -ItemType Directory -Path $serviceLogDirectory -Force | Out-Null
    $stdoutLog = Join-Path $serviceLogDirectory "$name.stdout.log"
    $stderrLog = Join-Path $serviceLogDirectory "$name.stderr.log"
    Remove-Item -LiteralPath $stdoutLog, $stderrLog -Force -ErrorAction SilentlyContinue
    $serviceEnvironment = New-ServiceEnvironment $name $port $extraEnv
    $serviceEnvironment["AI_GROUP_RUNTIME_DATA_ROOT"] = [string]$runtimeDataRoot
    $javaExecutable = (Get-Command java -ErrorAction Stop).Source
    Write-Host "Start $name from recovered frozen artifact on :$port"
    Start-Process `
        -FilePath $javaExecutable `
        -ArgumentList @("-jar", $jarPath) `
        -WorkingDirectory $workingDirectory `
        -Environment $serviceEnvironment `
        -RedirectStandardOutput $stdoutLog `
        -RedirectStandardError $stderrLog `
        -WindowStyle Hidden | Out-Null
    Start-Sleep -Seconds 12
}

function Sync-ReactorToolEnv() {
    $rtEnv = Join-Path $root "ai-agent\runtime\tools\.env"
    $lines = @(
        "OPENAI_BASE_URL=$($env:AGENT_GROUP_LLM_BASE_URL)",
        "DEFAULT_MODEL=$($env:AGENT_GROUP_LLM_CHAT_MODEL)",
        "REPORT_MODEL=$($env:REPORT_MODEL)",
        "TEXT_EMBEDDING_TYPE=openai",
        "TEXT_EMBEDDING_BASE_URL=$($env:AGENT_GROUP_EMBEDDING_BASE_URL)",
        "TEXT_EMBEDDING_MODEL_NAME=$($env:AGENT_GROUP_LLM_EMBEDDING_MODEL)",
        "TEXT_EMBEDDING_DIMENSION=$($env:AGENT_GROUP_VECTOR_DIMENSION)",
        "DASHSCOPE_API_BASE=$($env:DASHSCOPE_API_BASE)",
        "FILE_SAVE_PATH=$($env:FILE_SAVE_PATH)",
        "FILE_SERVER_URL=$($env:FILE_SERVER_URL)",
        "FILE_MAX_SIZE_MB=$($env:FILE_MAX_SIZE_MB)",
        "MINIO_ENDPOINT=$($env:MINIO_ENDPOINT)",
        "MINIO_ACCESS_KEY=$minioAccessKey",
        "MINIO_BUCKET_NAME=$($env:MINIO_BUCKET_NAME)",
        "IMAGE_GENERATION_BASE_URL=$($env:IMAGE_GENERATION_BASE_URL)",
        "IMAGE_GENERATION_MODEL=$($env:IMAGE_GENERATION_MODEL)",
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
    Write-Host "Synced non-secret runtime/tools/.env; credentials remain process-only"
}

function Assert-OutsideFrozenSource([string]$path, [string]$name) {
    $fullPath = [System.IO.Path]::GetFullPath($path).TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar)
    foreach ($frozen in @(
            (Join-Path $root "group"),
            (Join-Path $root "s-pay-mall-ddd-market"))) {
        $frozenPath = [System.IO.Path]::GetFullPath($frozen).TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar)
        if ($fullPath.Equals($frozenPath, [System.StringComparison]::OrdinalIgnoreCase) -or
                $fullPath.StartsWith($frozenPath + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "$name must stay outside frozen source directory: $frozenPath"
        }
    }
}

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
    throw "Full-stack mode requires recovered Group/Pay jars outside the repository. Set AI_GROUP_FROZEN_ARTIFACT_ROOT or run with -DemoLite."
}

$runtimeDataRoot = if ($env:AI_GROUP_RUNTIME_DATA_ROOT) {
    [System.IO.Path]::GetFullPath($env:AI_GROUP_RUNTIME_DATA_ROOT)
} else {
    Join-Path (Split-Path -Parent $root) "ai-group-runtime-data"
}
Assert-OutsideFrozenSource $runtimeDataRoot "AI_GROUP_RUNTIME_DATA_ROOT"
New-Item -ItemType Directory -Path $runtimeDataRoot -Force | Out-Null

$frozenArtifactRoot = $null
$groupRecoveryJar = $null
$payRecoveryJar = $null
if ($DemoLite) {
    Write-Host "Demo-lite mode: Group/Pay migrations, recovered jars and Group/Pay smoke checks are skipped."
} else {
    $frozenArtifactRoot = Resolve-FrozenArtifactRoot
    Assert-OutsideFrozenSource $frozenArtifactRoot "AI_GROUP_FROZEN_ARTIFACT_ROOT"
    $groupRecoveryJar = Join-Path $frozenArtifactRoot "group\group-buy-market-app\target\group-buy-market-app.jar"
    $payRecoveryJar = Join-Path $frozenArtifactRoot "s-pay-mall-ddd-market\s-pay-mall-ddd-app\target\s-pay-mall-ddd-app.jar"
    foreach ($artifact in @($groupRecoveryJar, $payRecoveryJar)) {
        if (-not (Test-Path -LiteralPath $artifact -PathType Leaf)) {
            throw "Recovered frozen service artifact is missing: $artifact"
        }
    }
    Write-Host "Using recovered Group/Pay artifacts from $frozenArtifactRoot"
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
if ($env:SPRING_PROFILES_ACTIVE -in @("local", "dev")) {
    Invoke-Mysql "$root/docs/dev-ops/mysql/sql/auth_db/02-local-admin-seed.sql"
}
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
# Existing databases need the lease/fencing/cancel columns explicitly; the base DDL alone
# cannot upgrade a P30 database in place.
Invoke-Mysql "$root/docs/dev-ops/mysql/sql/agent_db/07-durable-run-lease-cancel.sql"
Invoke-Mysql "$root/docs/dev-ops/mysql/sql/agent_db/07-quota-settlement-command.sql"
# LLM snapshot and ordered run events are durable replay prerequisites. Apply them before the
# tool-result projection so recovered runs always have their parent ledger structures.
Invoke-Mysql "$root/docs/dev-ops/mysql/sql/agent_db/08-llm-invocation-snapshot.sql"
Invoke-Mysql "$root/docs/dev-ops/mysql/sql/agent_db/08-run-event-hardening.sql"
# 工具结果与模型 observation 分离持久化，保证结构化工具的实时展示和历史回放一致。
Invoke-Mysql "$root/docs/dev-ops/mysql/sql/agent_db/08-tool-result-replay.sql"
Invoke-Mysql "$root/docs/dev-ops/mysql/sql/agent_db/09-tool-approval.sql"
# Native deep-research recovery, durable worker, registry governance, context snapshots and the P90
# evidence ledger are all runtime dependencies. Apply every idempotent increment before seed data.
Invoke-Mysql "$root/docs/dev-ops/mysql/sql/agent_db/10-deep-research-langgraph.sql"
Invoke-Mysql "$root/docs/dev-ops/mysql/sql/agent_db/11-deep-research-checkpoint-order-index.sql"
Invoke-Mysql "$root/docs/dev-ops/mysql/sql/agent_db/12-saa-deep-research-checkpoint.sql"
Invoke-Mysql "$root/docs/dev-ops/mysql/sql/agent_db/13-durable-tool-outbox.sql"
Invoke-Mysql "$root/docs/dev-ops/mysql/sql/agent_db/14-mcp-registry-governance.sql"
Invoke-Mysql "$root/docs/dev-ops/mysql/sql/agent_db/15-context-snapshot.sql"
Invoke-Mysql "$root/docs/dev-ops/mysql/sql/agent_db/16-evidence-ledger.sql"
# Keep the trace correlation migration in the launcher even though a fresh baseline may
# already contain the column: it is required for old agent_db upgrades and is idempotent.
Invoke-Mysql "$root/docs/dev-ops/mysql/sql/agent_db/16-quota-trace-correlation.sql"
Invoke-Mysql "$root/docs/dev-ops/mysql/sql/agent_db/02-dev-seed.sql"
if (-not $DemoLite) {
    # Frozen Group/Pay SQL is read-only input; their generated artifacts are never created in source directories.
    # group/pay 为全量转储（DROP+重灌）：仅首次初始化执行，已存在则跳过以保留订单/拼团数据
    Invoke-MysqlDumpOnce "$root/group/docs/dev-ops/mysql/sql/2-29-group_buy_market.sql" -Schema "group_buy_market" -MarkerTable "group_buy_order"
    # 每个额度包使用独立拼团链（goods + discount + activity）：幂等迁移，老库也会补齐
    Invoke-Mysql "$root/group/docs/dev-ops/mysql/sql/3-01-per-sku-groupbuy-migrate.sql"
    # 阶梯拼团：档位表 + activity_type + 档位种子（3-02）；容量=最高档人数(10)（3-03，须在 3-01 之后覆盖 target）
    Invoke-Mysql "$root/group/docs/dev-ops/mysql/sql/3-02-groupbuy-tier-migrate.sql"
    Invoke-Mysql "$root/group/docs/dev-ops/mysql/sql/3-03-groupbuy-tier-settlement-migrate.sql"
    Invoke-Mysql "$root/group/docs/dev-ops/mysql/sql/3-04-identifier-width-migrate.sql"
    Invoke-MysqlDumpOnce "$root/s-pay-mall-ddd-market/docs/dev-ops/mysql/sql/s-pay-mall-ddd-market.sql" -Schema "s_pay_mall_ddd_market" -MarkerTable "pay_order" -PayBase
    Invoke-Mysql "$root/s-pay-mall-ddd-market/docs/dev-ops/mysql/sql/V3_benefit_event.sql"
    Invoke-Mysql "$root/s-pay-mall-ddd-market/docs/dev-ops/mysql/sql/V4_settlement_notified.sql"
    # 支付交易统一 outbox：履约/权益事件共用本地消息表，补齐老库索引并回填可能丢失的支付成功事件。
    Invoke-Mysql "$root/s-pay-mall-ddd-market/docs/dev-ops/mysql/sql/V5_transactional_outbox.sql"
    # 支付下单 durable 幂等键、规范化载荷指纹及拼团路径快照。
    Invoke-Mysql "$root/s-pay-mall-ddd-market/docs/dev-ops/mysql/sql/V6_pay_order_idempotency.sql"
    Invoke-Mysql "$root/s-pay-mall-ddd-market/docs/dev-ops/mysql/sql/V7-order-identifier-width.sql"
    # 阶梯拼团：benefit_event.bonus_quota（加赠额度随权益事件透传给 member）
    Invoke-Mysql "$root/docs/dev-ops/mysql/sql/pay_db/01-benefit-event-bonus-migrate.sql"
}
Invoke-Mysql "$root/docs/dev-ops/mysql/sql/xxl_job/01-xxl_job.sql"
Write-Host "==> Build platform"
Push-Location $root
mvn clean install -DskipTests -q
# ai-agent 是独立聚合工程（不在根 pom 的 modules 里）；干净 .m2 下必须先 install，
# 否则后面直接在 ai-agent-app 子模块 spring-boot:run 会因缺兄弟 SNAPSHOT 依赖失败。
Push-Location "$root/ai-agent"
mvn clean install -DskipTests -q
Pop-Location
Pop-Location

# ai-agent 会在 ApplicationReadyEvent 中预热所有 status=1 的 STDIO MCP。
# 因此必须先同步 runtime/tools 的锁定依赖，否则干净 checkout 会在 MCP 子进程启动时缺少 Python SDK。
Write-Host "==> Prepare runtime/tools"
if (-not $EphemeralLlmCredentials) {
    Sync-ReactorToolEnv
} else {
    Remove-Item -LiteralPath (Join-Path $root "ai-agent\runtime\tools\.env") -Force -ErrorAction SilentlyContinue
    Write-Host "Removed stale runtime/tools/.env for process-only LLM credentials"
}
Push-Location "$root/ai-agent/runtime/tools"
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
# The launcher uses the dev profile globally. BFF must still target the locally started
# Group/Pay/Member processes instead of attempting Nacos discovery for recovered artifacts.
Start-ServiceWindow "bff-service" "$root/bff-service" 8083 @{
    AI_GROUP_GROUP_URL  = "http://127.0.0.1:8091"
    AI_GROUP_PAY_URL    = "http://127.0.0.1:8070"
    AI_GROUP_MEMBER_URL = "http://127.0.0.1:$MemberPort"
}
if (-not $DemoLite) {
    Start-RecoveredJar "group-buy-market" $groupRecoveryJar 8091 @{
        AI_GROUP_DEMO_PAYMENT_ENABLED = $env:AI_GROUP_DEMO_PAYMENT_ENABLED
    }
    Start-RecoveredJar "pay-service" $payRecoveryJar 8070 $payEnv
    Wait-HttpReady "group-buy-market" "http://127.0.0.1:8091/actuator/health" 120
    Wait-PortReady "pay-service" 8070 120
}
Start-ServiceWindow "ai-agent" "$root/ai-agent/ai-agent-app" 8090 @{
    AGENT_GROUP_LLM_API_KEY       = $env:AGENT_GROUP_LLM_API_KEY
    DASHSCOPE_API_KEY             = $env:DASHSCOPE_API_KEY
    SPRING_AI_OPENAI_API_KEY      = $env:SPRING_AI_OPENAI_API_KEY
    AGENT_GROUP_LLM_BASE_URL      = $env:AGENT_GROUP_LLM_BASE_URL
    AGENT_GROUP_LLM_CHAT_MODEL    = $env:AGENT_GROUP_LLM_CHAT_MODEL
    AGENT_GROUP_VISION_MODEL      = $env:AGENT_GROUP_VISION_MODEL
    AGENT_GROUP_MEMORY_SUMMARY_MODEL = $env:AGENT_GROUP_MEMORY_SUMMARY_MODEL
    AGENT_GROUP_LLM_EMBEDDING_MODEL = $env:AGENT_GROUP_LLM_EMBEDDING_MODEL
    AGENT_GROUP_EMBEDDING_API_KEY = $env:AGENT_GROUP_EMBEDDING_API_KEY
    AGENT_GROUP_EMBEDDING_BASE_URL = $env:AGENT_GROUP_EMBEDDING_BASE_URL
    AGENT_GROUP_VECTOR_DIMENSION  = $env:AGENT_GROUP_VECTOR_DIMENSION
    AGENT_GROUP_REACTOR_TOOL_BASE_URL = $env:AGENT_GROUP_REACTOR_TOOL_BASE_URL
    AGENT_GROUP_REACTOR_TOOL_TOKEN = $env:AGENT_GROUP_REACTOR_TOOL_TOKEN
    MEMBER_SERVICE_BASE_URL       = "http://127.0.0.1:$MemberPort"
    AI_AGENT_AUTO_CONFIG_ENABLED = $env:AI_AGENT_AUTO_CONFIG_ENABLED
    TAVILY_API_KEY                = $env:TAVILY_API_KEY
    MINERU_API_KEY                = $env:MINERU_API_KEY
    POSTGRES_HOST                 = $env:POSTGRES_HOST
    POSTGRES_PORT                 = $env:POSTGRES_PORT
    POSTGRES_DB                   = $env:POSTGRES_DB
    POSTGRES_USER                 = $env:POSTGRES_USER
    POSTGRES_PASSWORD             = $env:POSTGRES_PASSWORD
    AGENT_MEMORY_LONGTERM_ENABLED = $env:AGENT_MEMORY_LONGTERM_ENABLED
}
Wait-HttpReady "ai-agent" "http://127.0.0.1:8090/web/health" 120

if (-not (Test-PortListening 1601)) {
    Write-Host "Start runtime/tools on :1601 (init sqlite db + server.py)"
    # Python 依赖已在 ai-agent 启动前按 uv.lock 同步；此处只初始化 SQLite 并启动 HTTP 工具服务。
    Push-Location "$root/ai-agent/runtime/tools"
    try {
        uv run --frozen python -m reactor_tool.db.db_engine
        $runtimeEnvironment = @{
            PATH = [string]$env:PATH
            SYSTEMROOT = [string]$env:SYSTEMROOT
            USERPROFILE = [string]$env:USERPROFILE
            TEMP = [string]$env:TEMP
            TMP = [string]$env:TMP
            LOCALAPPDATA = [string]$env:LOCALAPPDATA
            APPDATA = [string]$env:APPDATA
            OPENAI_API_KEY = [string]$env:AGENT_GROUP_LLM_API_KEY
            OPENAI_BASE_URL = [string]$env:AGENT_GROUP_LLM_BASE_URL
            DEFAULT_MODEL = [string]$env:AGENT_GROUP_LLM_CHAT_MODEL
            REPORT_MODEL = [string]$env:REPORT_MODEL
            TEXT_EMBEDDING_TYPE = "openai"
            TEXT_EMBEDDING_API_KEY = [string]$env:AGENT_GROUP_EMBEDDING_API_KEY
            TEXT_EMBEDDING_BASE_URL = [string]$env:AGENT_GROUP_EMBEDDING_BASE_URL
            TEXT_EMBEDDING_MODEL_NAME = [string]$env:AGENT_GROUP_LLM_EMBEDDING_MODEL
            TEXT_EMBEDDING_DIMENSION = [string]$env:AGENT_GROUP_VECTOR_DIMENSION
            DASHSCOPE_API_KEY = [string]$env:DASHSCOPE_API_KEY
            DASHSCOPE_API_BASE = [string]$env:DASHSCOPE_API_BASE
            FILE_SAVE_PATH = [string]$env:FILE_SAVE_PATH
            FILE_SERVER_URL = [string]$env:FILE_SERVER_URL
            FILE_MAX_SIZE_MB = [string]$env:FILE_MAX_SIZE_MB
            MINIO_ENDPOINT = [string]$env:MINIO_ENDPOINT
            MINIO_ACCESS_KEY = [string]$minioAccessKey
            MINIO_SECRET_KEY = [string]$minioSecretKey
            MINIO_BUCKET_NAME = [string]$env:MINIO_BUCKET_NAME
            TAVILY_API_KEY = [string]$env:TAVILY_API_KEY
            MINERU_API_KEY = [string]$env:MINERU_API_KEY
            IMAGE_GENERATION_BASE_URL = [string]$env:IMAGE_GENERATION_BASE_URL
            IMAGE_GENERATION_API_KEY = [string]$env:IMAGE_GENERATION_API_KEY
            IMAGE_GENERATION_MODEL = [string]$env:IMAGE_GENERATION_MODEL
            GRSAI_NANOBANANA_API_KEY = [string]$env:GRSAI_NANOBANANA_API_KEY
            REACTOR_TOOL_TOKEN = [string]$env:REACTOR_TOOL_TOKEN
            REACTOR_TOOL_ENV = [string]$env:REACTOR_TOOL_ENV
            REACTOR_TOOL_HOST = [string]$env:REACTOR_TOOL_HOST
            REACTOR_TOOL_CORS_ORIGINS = [string]$env:REACTOR_TOOL_CORS_ORIGINS
            SKILL_ALLOWED_RUNTIMES = [string]$env:SKILL_ALLOWED_RUNTIMES
            SKILL_MAX_CONCURRENT_PROCESSES = "2"
            SEARCH_TIMEOUT = "20"
            SEARCH_PARSER_TIMEOUT = "10"
            DEEPSEARCH_TOTAL_TIMEOUT_SECONDS = "150"
            SEARCH_THREAD_NUM = "4"
            JWT_SECRET = ""
            AI_GROUP_INTERNAL_TOKEN = ""
            MYSQL_ROOT_PASSWORD = ""
            REDIS_PASSWORD = ""
            KAFKA_BOOTSTRAP_SERVERS = "127.0.0.1:9092"
            NACOS_AUTH_TOKEN = ""
            NACOS_AUTH_IDENTITY_KEY = ""
            NACOS_AUTH_IDENTITY_VALUE = ""
            ALIPAY_MERCHANT_PRIVATE_KEY = ""
            ALIPAY_PUBLIC_KEY = ""
        }
        Start-Process pwsh `
            -ArgumentList "-NoProfile", "-File", (Join-Path (Get-Location) "start.ps1"), "-Detached" `
            -WorkingDirectory (Get-Location) `
            -Environment $runtimeEnvironment `
            -WindowStyle Hidden | Out-Null
    } finally {
        Pop-Location
    }
}
Wait-HttpReady "runtime/tools" "http://127.0.0.1:1601/health" 60

if (-not (Test-PortListening 5173)) {
    Write-Host "Start frontend on http://localhost:5173"
    # 干净 checkout 没有 node_modules，必须先 pnpm install 再 pnpm dev，否则窗口会因缺 vite 直接退出。
    Push-Location "$root/web"
    try {
        pnpm install --frozen-lockfile
        $nodeExecutable = (Get-Command node -ErrorAction Stop).Source
        $viteEntry = Join-Path (Get-Location) "node_modules\vite\bin\vite.js"
        if (-not (Test-Path -LiteralPath $viteEntry)) {
            throw "Vite entry not found after pnpm install: $viteEntry"
        }
        # Vite plugins are third-party code: give the dev server only the values
        # it needs instead of inheriting database, JWT, payment and LLM secrets.
        $frontendEnvironment = @{
            PATH = [string]$env:PATH
            VITE_API_BASE_URL = [string]$env:VITE_API_BASE_URL
            VITE_API_TARGET = [string]$env:VITE_API_TARGET
            SERVICE_BASE_URL = [string]$env:SERVICE_BASE_URL
            REACTOR_TOOL_BASE_URL = [string]$env:REACTOR_TOOL_BASE_URL
            AGENT_GROUP_REACTOR_TOOL_TOKEN = [string]$env:AGENT_GROUP_REACTOR_TOOL_TOKEN
            NODE_ENV = "development"
            JWT_SECRET = ""
            AI_GROUP_INTERNAL_TOKEN = ""
            MYSQL_ROOT_PASSWORD = ""
            REDIS_PASSWORD = ""
            KAFKA_BOOTSTRAP_SERVERS = "127.0.0.1:9092"
            NACOS_AUTH_TOKEN = ""
            NACOS_AUTH_IDENTITY_KEY = ""
            NACOS_AUTH_IDENTITY_VALUE = ""
            AGENT_GROUP_LLM_API_KEY = ""
            AGENT_GROUP_EMBEDDING_API_KEY = ""
            DASHSCOPE_API_KEY = ""
            OPENAI_API_KEY = ""
            IMAGE_GENERATION_API_KEY = ""
            TAVILY_API_KEY = ""
            MINERU_API_KEY = ""
            ALIPAY_MERCHANT_PRIVATE_KEY = ""
            ALIPAY_PUBLIC_KEY = ""
        }
        Start-Process `
            -FilePath $nodeExecutable `
            -ArgumentList $viteEntry `
            -WorkingDirectory (Get-Location) `
            -Environment $frontendEnvironment `
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
if (-not $DemoLite) {
    & pwsh -NoProfile -File "$root/docs/dev-ops/smoke-benefit-event.ps1"
    & pwsh -NoProfile -File "$root/docs/dev-ops/smoke-benefit-revoke.ps1"
    if (Test-PortListening 8070) {
        & pwsh -NoProfile -File "$root/docs/dev-ops/smoke-security.ps1"
    } else {
        throw "pay-service is not listening on :8070 after full-stack startup"
    }
} else {
    Write-Host "Demo-lite smoke complete: Group/Pay benefit and security checks are intentionally skipped."
}

Write-Host ""
Write-Host "========================================"
Write-Host "  Frontend : http://localhost:5173/login"
Write-Host "  Gateway  : http://localhost:8080"
if (-not $DemoLite) {
    Write-Host "  group    : http://localhost:8091 (recovered jar)"
    Write-Host "  pay      : http://localhost:8070 (recovered jar)"
} else {
    Write-Host "  Group/Pay: skipped (demo-lite)"
}
Write-Host "  ai-agent : http://localhost:8090"
Write-Host "  reactor  : http://localhost:1601"
Write-Host "========================================"
