#Requires -Version 7.2
param(
    [ValidateRange(60, 900)]
    [int]$TimeoutSeconds = 300
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$runner = Join-Path $PSScriptRoot "agent-product-e2e.ps1"
if (-not (Test-Path -LiteralPath $runner)) { throw "Missing Agent product runner: $runner" }

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$stdout = Join-Path $PSScriptRoot "agent-product-e2e-$timestamp.out.log"
$stderr = Join-Path $PSScriptRoot "agent-product-e2e-$timestamp.err.log"
$process = Start-Process -FilePath (Get-Command pwsh -ErrorAction Stop).Source `
    -ArgumentList @("-NoProfile", "-File", $runner, "-FreshStack", "-RequireRealCitations", "-RequireRecovery", "-RequireDiagnostics", "-TimeoutSeconds", "$TimeoutSeconds") `
    -WorkingDirectory $root `
    -RedirectStandardOutput $stdout `
    -RedirectStandardError $stderr `
    -WindowStyle Hidden `
    -PassThru

[pscustomobject]@{
    pid = $process.Id
    stdout = $stdout
    stderr = $stderr
    evidenceDirectory = $PSScriptRoot
} | ConvertTo-Json -Compress
