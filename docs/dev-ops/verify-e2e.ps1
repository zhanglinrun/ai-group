# E2E verify: register -> create quota-package order -> group settlement -> paid quota granted
param(
    [string]$Gateway = "http://127.0.0.1:8080",
    [string]$PayInternalCallback = "/api/v1/alipay/group_buy_notify",
    [string]$Username = "e2e_user_$(Get-Random -Maximum 99999)",
    [string]$Password = "Smoke@123456",
    [string]$SkuCode = "QUOTA_LIGHT",
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
        if ($key -in @('MYSQL_ROOT_PASSWORD', 'AI_GROUP_INTERNAL_TOKEN') -and -not (Test-Path "env:$key")) {
            Set-Item -Path "env:$key" -Value $value.Trim().Trim('"')
        }
    }
}
if (-not $env:MYSQL_ROOT_PASSWORD) { $env:MYSQL_ROOT_PASSWORD = "123456" }
if (-not $env:AI_GROUP_INTERNAL_TOKEN) {
    $env:AI_GROUP_INTERNAL_TOKEN = "change-me-to-a-long-random-internal-token"
}

function Invoke-Api($Method, $Path, $Body = $null, $Token = $null, $ExtraHeaders = @{}) {
    # 注意：PowerShell 变量名不区分大小写，局部变量不能叫 $headers（会覆盖同名参数）
    $requestHeaders = @{ "Content-Type" = "application/json" }
    if ($Token) { $requestHeaders["Authorization"] = "Bearer $Token" }
    foreach ($k in @($ExtraHeaders.Keys)) { $requestHeaders[$k] = $ExtraHeaders[$k] }
    $uri = "$Gateway$Path"
    if ($Body) {
        return Invoke-RestMethod -Method $Method -Uri $uri -Headers $requestHeaders -Body ($Body | ConvertTo-Json -Depth 10)
    }
    return Invoke-RestMethod -Method $Method -Uri $uri -Headers $requestHeaders
}

function Wait-ForPaidQuota([string]$AccessToken, [long]$ExpectedMinimum, [int]$TimeoutSeconds = 30) {
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

Write-Host "==> Register $Username"
Invoke-Api POST "/api/auth/register" @{ username = $Username; password = $Password; email = "$Username@test.local" } | Out-Null

Write-Host "==> Login"
$login = Invoke-Api POST "/api/auth/login" @{ username = $Username; password = $Password }
$token = $login.data.accessToken
if (-not $token) { throw "login failed: no accessToken" }

Write-Host "==> Pricing"
$pricing = Invoke-Api GET "/api/bff/pricing" $null $token
$sku = @($pricing.data.skus) | Where-Object { $_.code -eq $SkuCode } | Select-Object -First 1
if (-not $sku) { throw "pricing missing enabled SKU: $SkuCode" }
$activityId = $sku.groupActivityId
$goodsId = $sku.groupGoodsId
$baseQuota = [long]$sku.baseQuota
if (-not $activityId -or -not $goodsId -or $baseQuota -le 0L) {
    throw "pricing SKU $SkuCode missing groupActivityId/groupGoodsId/baseQuota"
}

Write-Host "==> Baseline quota account"
$before = Invoke-Api GET "/api/bff/account/summary" $null $token
$beforeFree = [long]$before.data.freeQuotaBalance
$beforePaid = [long]$before.data.paidQuotaBalance
if ($beforeFree -ne 5000000L) {
    throw "expected 5000000 free microcredits after register, got $beforeFree"
}

Write-Host "==> Create pay order (via gateway)"
$create = Invoke-Api POST "/api/v1/alipay/create_pay_order" @{
    userId = "$($login.data.user.id)"
    productId = "$goodsId"
    productCode = $SkuCode
    activityId = [long]$activityId
    marketType = 1
} $token
if ($create.code -ne "0000" -and $create.code -ne 200) {
    throw "create_pay_order failed: code=$($create.code) info=$($create.info) message=$($create.message)"
}

Write-Host "==> Query newest order from BFF"
$orders = Invoke-Api GET "/api/bff/orders" $null $token
$items = $orders.data.items
if (-not $items -or $items.Count -lt 1) { throw "no orders returned after create_pay_order" }
$orderId = $items[$items.Count - 1].orderId
if (-not $orderId) { throw "orderId not found in bff orders" }
Write-Host "OrderId=$orderId"

# The automated local E2E cannot complete an interactive Alipay sandbox QR payment.
# Advance only this newly-created fixture to the state produced by a verified Alipay
# callback. The following group callback must still enforce PAY_SUCCESS -> MARKET,
# so an unpaid order cannot receive benefits.
$mysqlPassword = $env:MYSQL_ROOT_PASSWORD
Write-Host "==> Simulate verified Alipay payment state (local fixture only)"
$sql = "update s_pay_mall_ddd_market.pay_order set status='PAY_SUCCESS', pay_time=now() where order_id='$orderId' and status='PAY_WAIT'; select row_count();"
$hadMysqlPwd = Test-Path Env:MYSQL_PWD
$previousMysqlPwd = $env:MYSQL_PWD
$env:MYSQL_PWD = $mysqlPassword
try {
    $updated = ($sql | docker exec -i -e MYSQL_PWD $MysqlContainer mysql -uroot -N -B 2>$null | Select-Object -Last 1).Trim()
    $mysqlExitCode = $LASTEXITCODE
} finally {
    if ($hadMysqlPwd) { $env:MYSQL_PWD = $previousMysqlPwd } else { Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue }
}
if ($mysqlExitCode -ne 0 -or $updated -ne "1") {
    throw "failed to advance local payment fixture to PAY_SUCCESS (updated=$updated)"
}

Write-Host "==> Trigger group settlement notify (internal callback)"
$internal = $env:AI_GROUP_INTERNAL_TOKEN
if (-not $internal) { throw "AI_GROUP_INTERNAL_TOKEN env var not set (start-full-stack.ps1 normally sets it)" }
$notifyResp = Invoke-Api POST $PayInternalCallback @{ teamId = "e2e-team"; outTradeNoList = @($orderId) } $null @{
    "X-Internal-Token" = $internal
}
if ($notifyResp -ne "success") { throw "group_buy_notify failed: $notifyResp" }

Write-Host "==> Wait benefit propagation"
$expectedPaid = $beforePaid + ($baseQuota * 1000000L)
$summary = Wait-ForPaidQuota $token $expectedPaid

Write-Host "==> Verify snapshotted package quota granted"
$afterPaid = [long]$summary.data.paidQuotaBalance
if ($afterPaid -lt $expectedPaid) {
    throw "expected paid quota increase of at least $($baseQuota * 1000000L) microcredits, before=$beforePaid after=$afterPaid"
}

Write-Host "E2E OK (sku=$SkuCode, paid microcredits: $beforePaid -> $afterPaid)"

