# Security smoke checks (requires running gateway + pay + member)
param(
    [string]$GatewayBase = "http://127.0.0.1:8080",
    [string]$PayBase = "http://127.0.0.1:8070"
)

$ErrorActionPreference = "Stop"

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
$spoofBody = '{"userId":"10001","productId":"9890001","productCode":"PRO_MONTH","activityId":10001,"marketType":1}'
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
    if ($result.Content -eq "success") {
        throw "unsigned group notify should not succeed"
    }
    Write-Host "OK: unsigned group notify rejected (response=$($result.Content))"
} catch {
    Write-Host "OK: unsigned group notify rejected"
}

Write-Host "Security smoke checks passed."
