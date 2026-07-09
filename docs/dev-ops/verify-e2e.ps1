# E2E verify: register -> login -> pricing -> create pay order -> group settlement -> benefit -> member upgraded
param(
    [string]$Gateway = "http://127.0.0.1:8080",
    [string]$PayInternalCallback = "/api/v1/alipay/group_buy_notify",
    [string]$Username = "e2e_user_$(Get-Random -Maximum 99999)",
    [string]$Password = "Smoke@123456",
    [string]$SkuCode = "PRO_MONTH"
)

$ErrorActionPreference = "Stop"

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

Write-Host "==> Register $Username"
Invoke-Api POST "/api/auth/register" @{ username = $Username; password = $Password; email = "$Username@test.local" } | Out-Null

Write-Host "==> Login"
$login = Invoke-Api POST "/api/auth/login" @{ username = $Username; password = $Password }
$token = $login.data.accessToken
if (-not $token) { throw "login failed: no accessToken" }

Write-Host "==> Pricing"
$pricing = Invoke-Api GET "/api/bff/pricing" $null $token
$activityId = $pricing.data.groupBuy.activityId
$goodsId = $pricing.data.groupBuy.goods.goodsId
if (-not $activityId -or -not $goodsId) { throw "pricing missing groupBuy activityId/goodsId" }

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

Write-Host "==> Trigger group settlement notify (internal callback)"
$internal = $env:AI_GROUP_INTERNAL_TOKEN
if (-not $internal) { throw "AI_GROUP_INTERNAL_TOKEN env var not set (start-full-stack.ps1 normally sets it)" }
$notifyResp = Invoke-Api POST $PayInternalCallback @{ teamId = "e2e-team"; outTradeNoList = @($orderId) } $null @{
    "X-Internal-Token" = $internal
}
if ($notifyResp -ne "success") { throw "group_buy_notify failed: $notifyResp" }

Write-Host "==> Wait benefit propagation"
Start-Sleep -Seconds 3

Write-Host "==> Verify member upgraded"
$summary = Invoke-Api GET "/api/bff/account/summary" $null $token
if ($summary.data.tier -ne "PRO") {
    throw "expected PRO after settlement, got $($summary.data.tier) (meta.degraded=$($summary.data.meta.degraded))"
}

Write-Host "E2E OK (tier=$($summary.data.tier), periodQuota=$($summary.data.periodQuotaBalance))"

