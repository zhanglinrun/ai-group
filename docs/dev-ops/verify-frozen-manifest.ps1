#Requires -Version 7.2
param(
    [switch]$WriteBaseline
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$manifestPath = Join-Path $root "docs\acceptance\frozen-group-pay-manifest.json"
$frozenDirectories = @("group", "s-pay-mall-ddd-market")
$excludedPathGlobs = @("**/target/**", "**/data/log/**")

function Get-RelativePath([string]$fullPath) {
    return [System.IO.Path]::GetRelativePath($root, $fullPath).Replace("\\", "/")
}

function Test-ExcludedPath([string]$relativePath) {
    return $relativePath -match "(^|/)target(/|$)" -or $relativePath -match "(^|/)data/log(/|$)"
}

function Get-SharedSha256([string]$path) {
    $stream = $null
    $sha256 = $null
    try {
        $stream = [System.IO.File]::Open(
            $path,
            [System.IO.FileMode]::Open,
            [System.IO.FileAccess]::Read,
            [System.IO.FileShare]::ReadWrite -bor [System.IO.FileShare]::Delete
        )
        $sha256 = [System.Security.Cryptography.SHA256]::Create()
        return ($sha256.ComputeHash($stream) | ForEach-Object { $_.ToString("x2") }) -join ""
    } finally {
        if ($sha256) { $sha256.Dispose() }
        if ($stream) { $stream.Dispose() }
    }
}

function Get-FrozenFiles {
    $files = foreach ($directory in $frozenDirectories) {
        $absoluteDirectory = Join-Path $root $directory
        Get-ChildItem -LiteralPath $absoluteDirectory -Recurse -File -Force |
            ForEach-Object {
                $relativePath = Get-RelativePath $_.FullName
                if (-not (Test-ExcludedPath $relativePath)) {
                    [ordered]@{
                        path = $relativePath
                        sizeBytes = [Int64]$_.Length
                        sha256 = Get-SharedSha256 $_.FullName
                    }
                }
            }
    }
    return @($files | Sort-Object path)
}

$currentFiles = Get-FrozenFiles

if ($WriteBaseline) {
    $manifest = [ordered]@{
        formatVersion = 2
        generatedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
        workspace = $root
        frozenDirectories = $frozenDirectories
        excludedPathGlobs = $excludedPathGlobs
        fileCount = $currentFiles.Count
        files = $currentFiles
    }
    $manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $manifestPath -Encoding utf8
    Write-Host "Frozen manifest baseline written: $($currentFiles.Count) static files"
    exit 0
}

if (-not (Test-Path -LiteralPath $manifestPath)) {
    throw "Frozen manifest does not exist: $manifestPath"
}

$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
if ([int]$manifest.formatVersion -ne 2) {
    throw "Unsupported frozen manifest format: $($manifest.formatVersion). Run with -WriteBaseline only after an authorized recovery."
}

$expected = @{}
foreach ($entry in @($manifest.files)) {
    $expected[[string]$entry.path] = [pscustomobject]@{
        sizeBytes = [Int64]$entry.sizeBytes
        sha256 = [string]$entry.sha256
    }
}

$actual = @{}
foreach ($entry in $currentFiles) {
    $actual[[string]$entry.path] = $entry
}

$missing = @($expected.Keys | Where-Object { -not $actual.ContainsKey($_) })
$unexpected = @($actual.Keys | Where-Object { -not $expected.ContainsKey($_) })
$changed = @($expected.Keys | Where-Object {
    $actual.ContainsKey($_) -and (
        $actual[$_].sizeBytes -ne $expected[$_].sizeBytes -or
        $actual[$_].sha256 -ne $expected[$_].sha256
    )
})

$result = [ordered]@{
    manifestPath = $manifestPath
    formatVersion = [int]$manifest.formatVersion
    expectedFileCount = $expected.Count
    actualFileCount = $actual.Count
    missingCount = $missing.Count
    changedCount = $changed.Count
    unexpectedCount = $unexpected.Count
    missing = @($missing | Sort-Object)
    changed = @($changed | Sort-Object)
    unexpected = @($unexpected | Sort-Object)
}

$result | ConvertTo-Json -Depth 4
if ($missing.Count -gt 0 -or $changed.Count -gt 0 -or $unexpected.Count -gt 0) {
    exit 1
}
