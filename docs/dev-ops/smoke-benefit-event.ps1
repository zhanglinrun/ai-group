# Benefit event E2E: register -> publish snapshotted quota package -> verify paid quota grant
param(
    [string]$Gateway = "http://127.0.0.1:8080",
    [string]$KafkaContainer = "ai-group-kafka",
    [string]$KafkaTopic = "member.benefit.completed",
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
    $PayloadJson | & docker exec -i $KafkaContainer /opt/kafka/bin/kafka-console-producer.sh `
        --bootstrap-server localhost:9092 --topic $KafkaTopic
    if ($LASTEXITCODE -ne 0) { throw "kafka publish failed (exit $LASTEXITCODE)" }
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
if (-not $token -or -not $userId) { throw "login failed: missing access token or user id" }

Write-Host "==> Baseline quota account"
$before = Invoke-Api GET "/api/bff/account/summary" $null $token
$beforeFree = [long]$before.data.freeQuotaBalance
$beforePaid = [long]$before.data.paidQuotaBalance
if ($beforeFree -ne 5000000L) {
    throw "expected 5000000 free microcredits before event, got $beforeFree"
}

$orderId = "smoke-order-$(Get-Random -Maximum 999999)"
$event = @{
    eventId     = [guid]::NewGuid().ToString()
    eventType   = "GROUP_BUY_COMPLETED"
    userId      = $userId
    orderId     = $orderId
    productCode = "QUOTA_LIGHT"
    baseQuota   = 60
    bonusQuota  = 0
} | ConvertTo-Json -Compress

Write-Host "==> Publish benefit event orderId=$orderId userId=$userId"
Publish-BenefitEvent $event

Write-Host "==> Verify 60-credit package grant"
$expectedPaid = $beforePaid + 60000000L
$after = Wait-ForPaidQuota $token $expectedPaid
$afterPaid = [long]$after.data.paidQuotaBalance
if ($afterPaid -lt $expectedPaid) {
    throw "expected paid quota to increase by at least 60000000 microcredits, before=$beforePaid after=$afterPaid"
}

Write-Host "BENEFIT EVENT SMOKE OK (paid microcredits: $beforePaid -> $afterPaid)"
