param(
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $true
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $root

& "$PSScriptRoot/verify-modernization.ps1"
if ($SkipBuild) {
    Write-Host 'Static acceptance gate passed; build verification skipped.'
    exit 0
}

& mvn test

Push-Location group
try { & mvn test } finally { Pop-Location }

Push-Location s-pay-mall-ddd-market
try { & mvn test } finally { Pop-Location }

Push-Location ai-agent
try { & mvn test } finally { Pop-Location }

Push-Location ai-agent/runtime/tools
try { & uv run pytest } finally { Pop-Location }

Push-Location web
try {
    & pnpm test -- --run
    & pnpm lint
    & pnpm build
} finally { Pop-Location }

$commit = (& git rev-parse HEAD).Trim()
Write-Host "Build acceptance gate passed at commit $commit."
Write-Host 'Run the clean-volume infrastructure smoke before marking Trellis completed.'
