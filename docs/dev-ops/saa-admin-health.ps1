[CmdletBinding()]
param(
    [int]$TimeoutSeconds = 120,
    [string]$AdminHealthUrl = 'http://127.0.0.1:18090/actuator/health'
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true

$deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
do {
    try {
        $response = Invoke-RestMethod -Uri $AdminHealthUrl -TimeoutSec 5
        if ($response.status -eq 'UP') {
            Write-Output "SAA Admin healthy: $AdminHealthUrl"
            exit 0
        }
    } catch {
        # The image builds from a pinned source revision and can take a while on first start.
    }
    Start-Sleep -Seconds 2
} while ([DateTimeOffset]::UtcNow -lt $deadline)

throw "SAA Admin did not become healthy before timeout: $AdminHealthUrl"
