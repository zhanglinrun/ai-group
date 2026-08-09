$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$contractFiles = Get-ChildItem -LiteralPath (Join-Path $root 'contracts') -Recurse -File |
    Where-Object { $_.Extension -in '.yaml', '.json' }
if ($contractFiles.Count -eq 0) { throw 'No contract files found.' }
foreach ($file in $contractFiles) {
    if ($file.Extension -eq '.json') {
        Get-Content -Raw $file.FullName | ConvertFrom-Json | Out-Null
    }
    if ((Get-Content -Raw $file.FullName).Length -lt 50) { throw "Contract is unexpectedly small: $($file.FullName)" }
}
Write-Output "Validated $($contractFiles.Count) contract files."
