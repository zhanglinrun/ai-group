# Benefit revoke E2E: grant quota -> revoke -> verify manual-review status and no silent clawback
param(
    [string]$Gateway = "http://127.0.0.1:8080",
    [string]$RabbitMgmt = "http://127.0.0.1:15672",
    [string]$RabbitUser = "admin",
    [string]$RabbitPass = "admin",
    [string]$Username = "revoke_smoke_$(Get-Random -Maximum 99999)",
    [string]$Password = "Smoke@123456",
    [string]$MysqlContainer = "ai-group-mysql"
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$envFile = Join-Path $repoRoot ".env"
if (Test-Path -LiteralPath $envFile) {
    foreach ($line in Get-Content -LiteralPath $envFile -Encoding UTF8) {
        if ($line -match '^\s*#' -or $line -notmatch '=') { continue }
        $key, $value = $line -split '=', 2
        $key = $key.Trim()
        if ($key -in @('MYSQL_ROOT_PASSWORD', 'RABBITMQ_USER', 'RABBITMQ_PASSWORD') -and -not (Test-Path "env:$key")) {
            Set-Item -Path "env:$key" -Value $value.Trim().Trim('"')
        }
    }
}
if (-not $env:MYSQL_ROOT_PASSWORD) { $env:MYSQL_ROOT_PASSWORD = "123456" }
if (-not $PSBoundParameters.ContainsKey('RabbitUser') -and $env:RABBITMQ_USER) {
    $RabbitUser = $env:RABBITMQ_USER
}
if (-not $PSBoundParameters.ContainsKey('RabbitPass') -and $env:RABBITMQ_PASSWORD) {
    $RabbitPass = $env:RABBITMQ_PASSWORD
}

function Invoke-Api($Method, $Path, $Body = $null, $Token = $null) {
    $headers = @{ "Content-Type" = "application/json" }
    if ($Token) { $headers["Authorization"] = "Bearer $Token" }
    $uri = "$Gateway$Path"
    if ($Body) {
        return Invoke-RestMethod -Method $Method -Uri $uri -Headers $headers -Body ($Body | ConvertTo-Json)
    }
    return Invoke-RestMethod -Method $Method -Uri $uri -Headers $headers
}

function Publish-BenefitEvent($PayloadJson) {
    $cred = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${RabbitUser}:${RabbitPass}"))
    $body = @{
        properties       = @{}
        routing_key      = "member.benefit.completed"
        payload          = $PayloadJson
        payload_encoding = "string"
    } | ConvertTo-Json -Depth 5
    $uri = "$RabbitMgmt/api/exchanges/%2F/member.benefit.exchange/publish"
    $resp = Invoke-RestMethod -Method POST -Uri $uri -Headers @{
        Authorization = "Basic $cred"
        "Content-Type" = "application/json"
    } -Body $body
    if (-not $resp.routed) { throw "RabbitMQ publish not routed" }
}

function Wait-ForPaidQuota([string]$AccessToken, [long]$ExpectedMinimum, [int]$TimeoutSeconds = 20) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $current = Invoke-Api GET "/api/bff/account/summary" $null $AccessToken
        if ([long]$current.data.paidQuotaBalance -ge $ExpectedMinimum) {
            return $current
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)

    throw "paid quota did not reach $ExpectedMinimum microcredits within ${TimeoutSeconds}s"
}

function Get-RevokeStatus([string]$OrderId) {
    $mysqlPassword = $env:MYSQL_ROOT_PASSWORD
    $safeOrderId = $OrderId.Replace("'", "''")
    $sql = "SELECT status FROM member_db.benefit_grant_event WHERE order_id='$safeOrderId' AND event_type='GROUP_BUY_REVOKED' ORDER BY id DESC LIMIT 1;"
    $hadMysqlPwd = Test-Path Env:MYSQL_PWD
    $previousMysqlPwd = $env:MYSQL_PWD
    $env:MYSQL_PWD = $mysqlPassword
    try {
        $output = $sql | docker exec -i -e MYSQL_PWD $MysqlContainer mysql -uroot -N -B 2>$null
        $mysqlExitCode = $LASTEXITCODE
    } finally {
        if ($hadMysqlPwd) { $env:MYSQL_PWD = $previousMysqlPwd } else { Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue }
    }
    if ($mysqlExitCode -ne 0) { throw "failed to query revoke status from member_db" }
    $line = $output | Select-Object -Last 1
    if ($null -eq $line) { return $null }
    return ([string]$line).Trim()
}

function Wait-ForRevokeStatus([string]$OrderId, [string]$ExpectedStatus, [int]$TimeoutSeconds = 20) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $status = Get-RevokeStatus $OrderId
        if ($status -eq $ExpectedStatus) { return $status }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)

    throw "revoke event did not reach status $ExpectedStatus within ${TimeoutSeconds}s (last status=$status)"
}

Write-Host "==> Register $Username"
Invoke-Api POST "/api/auth/register" @{
    username = $Username
    password = $Password
    email    = "$Username@test.local"
} | Out-Null

$login = Invoke-Api POST "/api/auth/login" @{ username = $Username; password = $Password }
$token = $login.data.accessToken
$userId = $login.data.user.id
if (-not $token -or -not $userId) { throw "login failed: missing access token or user id" }
$baseline = Invoke-Api GET "/api/bff/account/summary" $null $token
$beforePaid = [long]$baseline.data.paidQuotaBalance
if ([long]$baseline.data.freeQuotaBalance -ne 5000000L) {
    throw "expected 5000000 free microcredits after register, got $($baseline.data.freeQuotaBalance)"
}
$orderId = "smoke-revoke-$(Get-Random -Maximum 999999)"

Publish-BenefitEvent (@{
    eventId     = [guid]::NewGuid().ToString()
    eventType   = "GROUP_BUY_COMPLETED"
    userId      = $userId
    orderId     = $orderId
    productCode = "QUOTA_LIGHT"
    baseQuota   = 60
    bonusQuota  = 0
} | ConvertTo-Json -Compress)

$granted = Wait-ForPaidQuota $token ($beforePaid + 60000000L)
$paidAfterGrant = [long]$granted.data.paidQuotaBalance

Publish-BenefitEvent (@{
    eventId     = [guid]::NewGuid().ToString()
    eventType   = "GROUP_BUY_REVOKED"
    userId      = $userId
    orderId     = $orderId
    productCode = "QUOTA_LIGHT"
    baseQuota   = 60
} | ConvertTo-Json -Compress)

$revokeStatus = Wait-ForRevokeStatus $orderId "REJECTED_GRANTED"
$afterRevoke = Invoke-Api GET "/api/bff/account/summary" $null $token
$paidAfterRevoke = [long]$afterRevoke.data.paidQuotaBalance
if ($paidAfterRevoke -ne $paidAfterGrant) {
    throw "automatic revoke silently changed paid quota: before=$paidAfterGrant after=$paidAfterRevoke"
}

Write-Host "REVOKE SMOKE OK (status=$revokeStatus, paid microcredits preserved=$paidAfterRevoke)"
