param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$InternalToken = '',
    [int]$TimeoutSec = 15
)

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')
if ([string]::IsNullOrWhiteSpace($InternalToken)) {
    $InternalToken = $env:AI_GROUP_INTERNAL_TOKEN
}
if ([string]::IsNullOrWhiteSpace($InternalToken)) {
    $envPath = Join-Path (Split-Path -Parent $PSScriptRoot) '.env'
    if (Test-Path -LiteralPath $envPath) {
        $line = Get-Content -LiteralPath $envPath | Where-Object { $_ -match '^AI_GROUP_INTERNAL_TOKEN=' } | Select-Object -First 1
        if ($line) {
            $InternalToken = ($line -split '=', 2)[1]
        }
    }
}
if ([string]::IsNullOrWhiteSpace($InternalToken)) {
    $InternalToken = 'change-me-internal'
}

function Invoke-Probe {
    param(
        [string]$Path,
        [ValidateSet('GET', 'POST')][string]$Method = 'GET',
        [hashtable]$Headers = @{},
        [string]$Body
    )

    $arguments = @(
        '--noproxy', '*',
        '--silent', '--show-error',
        '--max-time', [string]$TimeoutSec,
        '--request', $Method,
        '--output', 'NUL',
        '--write-out', '%{http_code}'
    )
    foreach ($header in $Headers.GetEnumerator()) {
        $arguments += @('--header', "$($header.Key): $($header.Value)")
    }
    if (-not [string]::IsNullOrEmpty($Body)) {
        $arguments += @('--header', 'Content-Type: application/json', '--data-raw', $Body)
    }
    $arguments += "$BaseUrl$Path"
    $result = (& curl.exe $arguments 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) {
        throw "HTTP probe failed for $Method ${Path}: $result"
    }
    if ($result -notmatch '^[0-9]{3}$') {
        throw "HTTP probe returned an invalid status for $Method ${Path}: $result"
    }
    return [pscustomobject]@{ Status = [int]$result }
}

$health = Invoke-Probe -Path '/actuator/health' -Headers @{ 'X-Internal-Token' = $InternalToken }
if ($health.Status -ne 200) {
    throw "Gateway health check failed: HTTP $($health.Status). Check Compose and InternalToken."
}
Write-Output "[pass] Gateway health: HTTP $($health.Status)"

$anonymous = Invoke-Probe -Path '/api/bff/home'
if ($anonymous.Status -notin @(401, 403)) {
    throw "Anonymous protection check failed: expected HTTP 401/403, got $($anonymous.Status)."
}
Write-Output "[pass] anonymous protection: HTTP $($anonymous.Status)"

$login = Invoke-Probe -Path '/api/auth/login' -Method POST -Body '{"username":"__smoke_probe__","password":"invalid"}'
if ($login.Status -eq 404) {
    throw 'Public auth route returned 404; Gateway or Auth routing is not connected.'
}
if ($login.Status -ge 500) {
    throw "Public auth route returned a server error: HTTP $($login.Status)."
}
Write-Output "[pass] public auth route: HTTP $($login.Status)"
Write-Output 'HTTP smoke checks passed.'
