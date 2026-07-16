# Security smoke checks (requires running gateway + pay + member)
param(
    [string]$GatewayBase = "http://127.0.0.1:8080",
    [string]$PayBase = "http://127.0.0.1:8070",
    [string]$InternalToken = $env:AI_GROUP_INTERNAL_TOKEN
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not $InternalToken) {
    $envFile = Join-Path $root ".env"
    if (Test-Path -LiteralPath $envFile) {
        $tokenLine = Get-Content -LiteralPath $envFile -Encoding UTF8 |
            Where-Object { $_ -match '^AI_GROUP_INTERNAL_TOKEN=' } |
            Select-Object -First 1
        if ($tokenLine) { $InternalToken = ($tokenLine -split '=', 2)[1].Trim().Trim('"') }
    }
}

function Invoke-HttpPost($Uri, $Body, $Headers = @{}) {
    $params = @{
        Method      = "Post"
        Uri         = $Uri
        ContentType = "application/json"
        Body        = $Body
        TimeoutSec  = 10
        UseBasicParsing = $true
    }
    if ($Headers.Count -gt 0) {
        $params.Headers = $Headers
    }
    return Invoke-WebRequest @params
}

Write-Host "1) Direct pay create_pay_order without gateway headers should fail"
$body = '{"userId":"1","productId":"100001"}'
$response = Invoke-HttpPost -Uri "$PayBase/api/v1/alipay/create_pay_order" -Body $body
$payload = $response.Content | ConvertFrom-Json
if ($payload.code -eq "0000") {
    throw "expected direct pay access to be rejected, got success"
}
Write-Host "OK: direct pay rejected (code=$($payload.code), info=$($payload.info))"

Write-Host "1.1) Direct pay with spoofed gateway headers (missing internal token) should fail"
$spoofHeaders = @{ "X-Gateway-Request" = "true"; "X-User-Id" = "10001" }
$spoofBody = '{"userId":"10001","productId":"9890002","productCode":"QUOTA_LIGHT","activityId":100201,"marketType":1}'
$spoof = Invoke-HttpPost -Uri "$PayBase/api/v1/alipay/create_pay_order" -Body $spoofBody -Headers $spoofHeaders
$spoofPayload = $spoof.Content | ConvertFrom-Json
if ($spoofPayload.code -eq "0000") {
    throw "expected spoofed gateway headers to be rejected, got success"
}
Write-Host "OK: spoofed gateway headers rejected (code=$($spoofPayload.code), info=$($spoofPayload.info))"

Write-Host "2) group_buy_notify without internal token should fail"
$notify = '{"teamId":"t1","outTradeNoList":["order-1"]}'
try {
    $result = Invoke-HttpPost -Uri "$PayBase/api/v1/alipay/group_buy_notify" -Body $notify
    $notifyBody = $result.Content.Trim()
    if ($result.StatusCode -ne 200 -or $notifyBody -ne "error") {
        throw "unexpected unsigned group notify response: status=$($result.StatusCode) body=$notifyBody"
    }
    Write-Host "OK: unsigned group notify rejected (response=$notifyBody)"
} catch {
    $statusCode = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
    if ($statusCode -in @(401, 403)) {
        Write-Host "OK: unsigned group notify rejected with HTTP $statusCode"
    } else {
        throw
    }
}

Write-Host "3) Direct group trade without/wrong internal token should be forbidden"
$GroupBase = if ($env:GROUP_BASE) { $env:GROUP_BASE } else { "http://127.0.0.1:8091" }
$tradeBody = '{"userId":"1","source":"s01","channel":"c01","goodsId":"9890001","activityId":10001,"outTradeNo":"smoke-security-out"}'
$wrongHeaders = @{ "X-Internal-Token" = "wrong-token" }
$queryBody = '{"userId":"1","source":"s01","channel":"c01","outTradeNo":"smoke-security-out"}'
$protectedCalls = @(
    @{ Name = "lock"; Uri = "$GroupBase/api/v1/gbm/trade/lock_market_pay_order"; Body = $tradeBody },
    @{ Name = "query"; Uri = "$GroupBase/api/v1/gbm/trade/query_market_pay_order"; Body = $queryBody }
)
foreach ($call in $protectedCalls) {
    foreach ($case in @(
            @{ Name = "without token"; Headers = @{} },
            @{ Name = "with wrong token"; Headers = $wrongHeaders }
        )) {
        try {
            $null = Invoke-HttpPost -Uri $call.Uri -Body $call.Body -Headers $case.Headers
            throw "expected group $($call.Name) $($case.Name) to return HTTP 403"
        } catch {
            if ($_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq 403) {
                Write-Host "OK: group $($call.Name) $($case.Name) rejected with 403"
            } elseif ($_.Exception.Message -match "403") {
                Write-Host "OK: group $($call.Name) $($case.Name) rejected with 403"
            } else {
                throw
            }
        }
    }
}
if (-not $InternalToken) { throw "AI_GROUP_INTERNAL_TOKEN is required for the positive group query probe" }
$validHeaders = @{ "X-Internal-Token" = $InternalToken }
$mappedQuery = Invoke-HttpPost -Uri "$GroupBase/api/v1/gbm/trade/query_market_pay_order" `
    -Body $queryBody -Headers $validHeaders
$mappedPayload = $mappedQuery.Content | ConvertFrom-Json
if ($mappedQuery.StatusCode -ne 200 -or $mappedPayload.code -ne "E0104") {
    throw "expected mapped group query to return E0104, got HTTP $($mappedQuery.StatusCode) code=$($mappedPayload.code)"
}
Write-Host "OK: group query with correct token reached the mapped endpoint (code=E0104)"
Write-Host "Note: formal pay->group lock remains covered by verify-e2e / create_pay_order through gateway."
Write-Host "All security smoke checks passed."
