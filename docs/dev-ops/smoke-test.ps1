# Platform smoke test: register -> login -> profile -> pricing -> member summary
param(
    [string]$Gateway = "http://127.0.0.1:8080",
    [string]$Username = "smoke_user_$(Get-Random -Maximum 99999)",
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

Write-Host "==> Register $Username"
Invoke-Api POST "/api/auth/register" @{ username = $Username; password = $Password; email = "$Username@test.local" } | Out-Null

Write-Host "==> Login"
$login = Invoke-Api POST "/api/auth/login" @{ username = $Username; password = $Password }
$token = $login.data.accessToken
if (-not $token) { throw "login failed: no accessToken" }

Write-Host "==> Profile"
$profile = Invoke-Api GET "/api/auth/profile" $null $token
Write-Host "Profile user: $($profile.data.username)"

Write-Host "==> Pricing"
$pricing = Invoke-Api GET "/api/bff/pricing" $null $token
Write-Host "SKU count: $($pricing.data.skus.Count)"

Write-Host "==> Account summary"
$summary = Invoke-Api GET "/api/bff/account/summary" $null $token
if ($summary.code -ne 200) { throw "account summary failed: $($summary.message)" }
$tier = $summary.data.tier
$quota = $summary.data.periodQuotaBalance
Write-Host "Tier: $tier, period quota: $quota"
if ($tier -ne "FREE") { throw "expected FREE tier after register, got: $tier" }
if ($quota -ne 20) { throw "expected 20 period quota after register, got: $quota" }

Write-Host "SMOKE OK"
