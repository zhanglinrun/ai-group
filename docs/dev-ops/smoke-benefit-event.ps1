# Benefit event E2E: register -> publish GROUP_BUY_COMPLETED -> verify Pro tier
param(
    [string]$Gateway = "http://127.0.0.1:8080",
    [string]$RabbitMgmt = "http://127.0.0.1:15672",
    [string]$RabbitUser = "admin",
    [string]$RabbitPass = "admin",
    [string]$Username = "benefit_smoke_$(Get-Random -Maximum 99999)",
    [string]$Password = "Smoke@123456"
)

$ErrorActionPreference = "Stop"

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

Write-Host "==> Register $Username"
Invoke-Api POST "/api/auth/register" @{
    username = $Username
    password = $Password
    email    = "$Username@test.local"
} | Out-Null

Write-Host "==> Login"
$login = Invoke-Api POST "/api/auth/login" @{ username = $Username; password = $Password }
$token = $login.data.accessToken
$userId = $login.data.user.id
if (-not $userId) { throw "login failed: no user id" }

Write-Host "==> Baseline summary (FREE)"
$before = Invoke-Api GET "/api/bff/account/summary" $null $token
if ($before.data.tier -ne "FREE") { throw "expected FREE before event, got $($before.data.tier)" }

$orderId = "smoke-order-$(Get-Random -Maximum 999999)"
$event = @{
    eventId     = [guid]::NewGuid().ToString()
    eventType   = "GROUP_BUY_COMPLETED"
    userId      = $userId
    orderId     = $orderId
    productCode = "PRO_MONTH"
    paidAmount  = 39.00
    occurredAt  = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:sszzz")
} | ConvertTo-Json -Compress

Write-Host "==> Publish benefit event orderId=$orderId userId=$userId"
Publish-BenefitEvent $event
Start-Sleep -Seconds 3

Write-Host "==> Verify Pro tier"
$after = Invoke-Api GET "/api/bff/account/summary" $null $token
if ($after.data.tier -ne "PRO") { throw "expected PRO after event, got $($after.data.tier)" }
if ($after.data.periodQuotaBalance -lt 500) {
    throw "expected >=500 period quota after Pro grant, got $($after.data.periodQuotaBalance)"
}

Write-Host "BENEFIT EVENT SMOKE OK (tier=$($after.data.tier), quota=$($after.data.periodQuotaBalance))"
