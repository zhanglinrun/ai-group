# AI Group platform verification script (PowerShell)
# Usage: pwsh docs/dev-ops/verify-build.ps1

$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $true
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $root

Write-Host "==> Java version"
java -version

Write-Host "==> Build platform microservices"
mvn clean install -DskipTests

Write-Host "==> Test group"
Push-Location group
mvn test -q
Pop-Location

Write-Host "==> Test pay"
Push-Location s-pay-mall-ddd-market
mvn test -q
Pop-Location

Write-Host "==> Test ai-agent"
Push-Location ai-agent
mvn test -q
Pop-Location

Write-Host "==> Build frontend"
Push-Location ai-agent/ui
pnpm install
pnpm build
Pop-Location

Write-Host "All verification steps completed."
