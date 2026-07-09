# Benefit revoke E2E: register -> grant completed -> publish revoked -> verify FREE tier
param(
    [string]$Gateway = "http://127.0.0.1:8080",
    [string]$RabbitMgmt = "http://127.0.0.1:15672",
    [string]$RabbitUser = "admin",
    [string]$RabbitPass = "admin",
    [string]$Username = "revoke_smoke_$(Get-Random -Maximum 99999)",
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

$login = Invoke-Api POST "/api/auth/login" @{ username = $Username; password = $Password }
$token = $login.data.accessToken
$userId = $login.data.user.id
$orderId = "smoke-revoke-$(Get-Random -Maximum 999999)"

Publish-BenefitEvent (@{
    eventId     = [guid]::NewGuid().ToString()
    eventType   = "GROUP_BUY_COMPLETED"
    userId      = $userId
    orderId     = $orderId
    productCode = "PRO_MONTH"
} | ConvertTo-Json -Compress)

Start-Sleep -Seconds 2
$pro = Invoke-Api GET "/api/bff/account/summary" $null $token
if ($pro.data.tier -ne "PRO") { throw "expected PRO before revoke, got $($pro.data.tier)" }

Publish-BenefitEvent (@{
    eventId     = [guid]::NewGuid().ToString()
    eventType   = "GROUP_BUY_REVOKED"
    userId      = $userId
    orderId     = $orderId
    productCode = "PRO_MONTH"
} | ConvertTo-Json -Compress)

Start-Sleep -Seconds 2
$free = Invoke-Api GET "/api/bff/account/summary" $null $token
if ($free.data.tier -ne "FREE") { throw "expected FREE after revoke, got $($free.data.tier)" }
if ($free.data.periodQuotaBalance -ne 20) { throw "expected 20 quota after revoke, got $($free.data.periodQuotaBalance)" }

Write-Host "REVOKE SMOKE OK"
