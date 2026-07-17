# E2E verify: two users register -> open/join group -> pay separately -> finalize -> both receive quota
param(
    [string]$Gateway = "http://127.0.0.1:8080",
    [string]$UsernamePrefix = "e2e_group_$(Get-Random -Maximum 99999)",
    [string]$Password = "Smoke@123456",
    [string]$SkuCode = "QUOTA_LIGHT"
)

$ErrorActionPreference = "Stop"

function Invoke-Api($Method, $Path, $Body = $null, $Token = $null) {
    $requestHeaders = @{ "Content-Type" = "application/json" }
    if ($Token) { $requestHeaders["Authorization"] = "Bearer $Token" }
    $uri = "$Gateway$Path"
    if ($null -ne $Body) {
        return Invoke-RestMethod -Method $Method -Uri $uri -Headers $requestHeaders `
            -Body ($Body | ConvertTo-Json -Depth 10)
    }
    return Invoke-RestMethod -Method $Method -Uri $uri -Headers $requestHeaders
}

function Register-And-Login([string]$Username) {
    Write-Host "==> Register $Username"
    Invoke-Api POST "/api/auth/register" @{
        username = $Username
        password = $Password
        email = "$Username@test.local"
    } | Out-Null

    $login = Invoke-Api POST "/api/auth/login" @{ username = $Username; password = $Password }
    if (-not $login.data.accessToken -or -not $login.data.user.id) {
        throw "login failed for ${Username}: missing accessToken or user id"
    }
    return $login.data
}

function Get-Newest-OrderId([string]$AccessToken) {
    $orders = Invoke-Api GET "/api/bff/orders" $null $AccessToken
    $items = @($orders.data.items)
    if ($items.Count -lt 1 -or -not $items[0].orderId) {
        throw "no order returned after create_pay_order"
    }
    return [string]$items[0].orderId
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

function Wait-For-New-Team([long]$ActivityId, [string]$OwnerUserId,
                          [string[]]$ExistingTeamIds, [string]$AccessToken,
                          [int]$TimeoutSeconds = 15) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $group = Invoke-Api GET "/api/bff/group-buy/$ActivityId" $null $AccessToken
        $team = @($group.data.groupBuy.teamList) | Where-Object {
            [string]$_.userId -eq $OwnerUserId -and [string]$_.teamId -notin $ExistingTeamIds
        } | Select-Object -First 1
        if ($team -and $team.teamId) { return $team }
        Start-Sleep -Milliseconds 300
    } while ((Get-Date) -lt $deadline)
    throw "new team for owner $OwnerUserId did not appear"
}

function Wait-For-TeamClosed([long]$ActivityId, [string]$TeamId,
                             [string]$AccessToken, [int]$TimeoutSeconds = 15) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $group = Invoke-Api GET "/api/bff/group-buy/$ActivityId" $null $AccessToken
        $stillActive = @($group.data.groupBuy.teamList) | Where-Object { [string]$_.teamId -eq $TeamId }
        if (-not $stillActive) { return }
        Start-Sleep -Milliseconds 300
    } while ((Get-Date) -lt $deadline)
    throw "finalized team $TeamId is still returned as active"
}

$usernameA = "${UsernamePrefix}_a"
$usernameB = "${UsernamePrefix}_b"
$accountA = Register-And-Login $usernameA
$accountB = Register-And-Login $usernameB
$tokenA = [string]$accountA.accessToken
$tokenB = [string]$accountB.accessToken
$userIdA = [string]$accountA.user.id
$userIdB = [string]$accountB.user.id

Write-Host "==> Verify gateway -> agent identity contract"
$agentIdentity = Invoke-Api GET "/api/agent/conversation/sessions?limit=1" $null $tokenA
if ($agentIdentity.code -ne "0000" -and $agentIdentity.code -ne 200) {
    throw "gateway -> agent identity contract failed: code=$($agentIdentity.code) info=$($agentIdentity.info)"
}

Write-Host "==> Load pricing and quota baselines"
$pricing = Invoke-Api GET "/api/bff/pricing" $null $tokenA
$sku = @($pricing.data.skus) | Where-Object { $_.code -eq $SkuCode } | Select-Object -First 1
if (-not $sku) { throw "pricing missing enabled SKU: $SkuCode" }
$activityId = [long]$sku.groupActivityId
$goodsId = [string]$sku.groupGoodsId
$baseQuotaMicro = [long]$sku.baseQuota * 1000000L
if ($activityId -le 0 -or -not $goodsId -or $baseQuotaMicro -le 0L) {
    throw "pricing SKU $SkuCode missing groupActivityId/groupGoodsId/baseQuota"
}

$beforeA = Invoke-Api GET "/api/bff/account/summary" $null $tokenA
$beforeB = Invoke-Api GET "/api/bff/account/summary" $null $tokenB
if ([long]$beforeA.data.freeQuotaBalance -ne 5000000L -or
    [long]$beforeB.data.freeQuotaBalance -ne 5000000L) {
    throw "new users must each start with 5000000 free microcredits"
}
$beforePaidA = [long]$beforeA.data.paidQuotaBalance
$beforePaidB = [long]$beforeB.data.paidQuotaBalance

$groupBefore = Invoke-Api GET "/api/bff/group-buy/$activityId" $null $tokenA
$existingTeamIds = @($groupBefore.data.groupBuy.teamList | ForEach-Object { [string]$_.teamId })

Write-Host "==> User A opens a group"
$createA = Invoke-Api POST "/api/v1/alipay/create_pay_order" @{
    requestId = "e2e-open-$([guid]::NewGuid().ToString('N'))"
    userId = $userIdA
    productId = $goodsId
    productCode = $SkuCode
    activityId = $activityId
    marketType = 1
} $tokenA
if ($createA.code -ne "0000" -and $createA.code -ne 200) {
    throw "user A create_pay_order failed: code=$($createA.code) info=$($createA.info)"
}
$orderA = Get-Newest-OrderId $tokenA
Write-Host "==> User A pays but leaves the team open"
$paidA = Invoke-Api POST "/api/v1/alipay/demo_mark_paid?outTradeNo=$orderA" $null $tokenA
if ($paidA.data -ne "GROUP_WAITING") {
    throw "user A demo_mark_paid expected GROUP_WAITING, got code=$($paidA.code) data=$($paidA.data) info=$($paidA.info)"
}
$team = Wait-For-New-Team $activityId $userIdA $existingTeamIds $tokenA
$teamId = [string]$team.teamId
Write-Host "User A order=$orderA team=$teamId"

$openGroup = Invoke-Api GET "/api/bff/group-buy/$activityId" $null $tokenB
$openTeam = @($openGroup.data.groupBuy.teamList) | Where-Object { [string]$_.teamId -eq $teamId }
if (-not $openTeam) { throw "team disappeared after first member paid" }

Write-Host "==> User B joins the same group"
$createB = Invoke-Api POST "/api/v1/alipay/create_pay_order" @{
    requestId = "e2e-join-$([guid]::NewGuid().ToString('N'))"
    userId = $userIdB
    productId = $goodsId
    productCode = $SkuCode
    teamId = $teamId
    activityId = $activityId
    marketType = 1
} $tokenB
if ($createB.code -ne "0000" -and $createB.code -ne 200) {
    throw "user B create_pay_order failed: code=$($createB.code) info=$($createB.info)"
}
$orderB = Get-Newest-OrderId $tokenB
Write-Host "User B order=$orderB"

Write-Host "==> User B pays, then explicitly finalizes the group"
$paidB = Invoke-Api POST "/api/v1/alipay/demo_mark_paid?outTradeNo=$orderB" $null $tokenB
if ($paidB.data -ne "GROUP_WAITING") {
    throw "user B demo_mark_paid expected GROUP_WAITING, got code=$($paidB.code) data=$($paidB.data) info=$($paidB.info)"
}
$finalized = Invoke-Api POST "/api/v1/alipay/demo_finalize_group?outTradeNo=$orderB" $null $tokenB
if ($finalized.data -ne "GROUP_FINALIZED") {
    throw "demo_finalize_group expected GROUP_FINALIZED, got code=$($finalized.code) data=$($finalized.data) info=$($finalized.info)"
}

Write-Host "==> Wait for MQ benefit propagation to both members"
$afterA = Wait-ForPaidQuota $tokenA ($beforePaidA + $baseQuotaMicro)
$afterB = Wait-ForPaidQuota $tokenB ($beforePaidB + $baseQuotaMicro)
Wait-For-TeamClosed $activityId $teamId $tokenA

$paidQuotaA = [long]$afterA.data.paidQuotaBalance
$paidQuotaB = [long]$afterB.data.paidQuotaBalance
Write-Host "E2E OK (team=$teamId, A=$beforePaidA->$paidQuotaA, B=$beforePaidB->$paidQuotaB microcredits)"
