$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$names = @('target', 'dist', 'build', 'node_modules', '.venv', '__pycache__', '.pytest_cache')
Get-ChildItem -LiteralPath $root -Recurse -Directory -Force -ErrorAction SilentlyContinue |
    Where-Object { $names -contains $_.Name } |
    Sort-Object FullName -Descending |
    ForEach-Object { Remove-Item -LiteralPath $_.FullName -Recurse -Force }

# These are workspace-local outputs that do not have a conventional build
# directory name. Keep the list explicit so .git metadata and source folders
# cannot be touched by the cleanup command.
$explicitPaths = @(
    (Join-Path $root 'agent-service/test.db'),
    (Join-Path $root 'group-service/.idea'),
    (Join-Path $root 'group-service/.codegraph'),
    (Join-Path $root 'pay-service/.codegraph'),
    (Join-Path $root 'member-service/logs'),
    (Join-Path $root 'group-service/group-service-app/logs'),
    (Join-Path $root 'pay-service/pay-service-app/logs')
)
foreach ($path in $explicitPaths) {
    if (Test-Path -LiteralPath $path) {
        Remove-Item -LiteralPath $path -Recurse -Force
    }
}

Get-ChildItem -LiteralPath $root -Filter '*.log' -File -Force -ErrorAction SilentlyContinue |
    ForEach-Object { Remove-Item -LiteralPath $_.FullName -Force }

Write-Output 'Build and local runtime artifacts removed from the workspace.'
