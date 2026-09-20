param(
    [ValidateRange(1, 168)][int]$WindowHours = 24,
    [ValidateRange(1, 500)][int]$Limit = 100
)

$ErrorActionPreference = "Stop"
$runner = Join-Path $PSScriptRoot "audit.py"
& python $runner --window-hours $WindowHours --limit $Limit
exit $LASTEXITCODE
