param(
    [int]$Threads = 20,
    [int]$Duration = 60,
    [int]$RampUp = 1,
    [string]$HostName = "127.0.0.1",
    [int]$Port = 8091
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

$envFile = Join-Path $projectRoot ".env"
if (Test-Path $envFile) {
    Get-Content $envFile | ForEach-Object {
        if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)\s*$') {
            $name, $value = $Matches[1], $Matches[2]
            if (-not [Environment]::GetEnvironmentVariable($name, "Process")) {
                [Environment]::SetEnvironmentVariable($name, $value.Trim('"').Trim("'"), "Process")
            }
        }
    }
}
if (-not $env:AI_GROUP_IDENTITY_SIGNING_SECRET -or -not $env:AI_GROUP_INTERNAL_TOKEN) {
    throw "AI_GROUP_IDENTITY_SIGNING_SECRET and AI_GROUP_INTERNAL_TOKEN must be set."
}

function Find-JMeter {
    if ($env:JMETER_HOME) {
        $candidate = Join-Path $env:JMETER_HOME "bin\jmeter.bat"
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    }
    foreach ($commandName in @("jmeter", "jmeter.bat")) {
        $command = Get-Command $commandName -ErrorAction SilentlyContinue
        if ($command) { return $command.Source }
    }
    foreach ($path in @(
        "C:\apache-jmeter\bin\jmeter.bat",
        "C:\apache-jmeter-5.6.3\bin\jmeter.bat",
        "C:\Program Files\apache-jmeter\bin\jmeter.bat"
    )) {
        if (Test-Path -LiteralPath $path) { return $path }
    }
    return $null
}

$jmeter = Find-JMeter
if (-not $jmeter) { throw "JMeter was not found. Set JMETER_HOME or put jmeter on PATH." }
if (-not (Test-NetConnection -ComputerName $HostName -Port $Port -InformationLevel Quiet -WarningAction SilentlyContinue)) {
    throw "Group service is not listening at ${HostName}:$Port."
}

$plansDir = Join-Path $projectRoot "dev-ops\jmeter\plans"
& python (Join-Path $plansDir "generate_group_lock_jmx.py")
if ($LASTEXITCODE -ne 0) { throw "failed to generate group-lock-only.jmx" }

$plan = Join-Path $plansDir "group-lock-only.jmx"
$runId = Get-Date -Format "yyyyMMddHHmmssfff"
$outputDir = Join-Path $projectRoot "dev-ops\jmeter\reports\group-lock-only\$runId"
$htmlDir = Join-Path $outputDir "html"
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$jtl = Join-Path $outputDir "results.jtl"
$log = Join-Path $outputDir "jmeter.log"

& $jmeter -n -t $plan -l $jtl -j $log -e -o $htmlDir `
    "-Jthreads=$Threads" `
    "-Jduration=$Duration" `
    "-Jrampup=$RampUp" `
    "-Jhost=$HostName" `
    "-Jport=$Port"
if ($LASTEXITCODE -ne 0) { throw "JMeter exited with code $LASTEXITCODE. See $log" }
if (-not (Test-Path $jtl)) { throw "JMeter did not create $jtl" }

$summary = Join-Path $outputDir "summary.json"
& python (Join-Path $plansDir "summarize_group_lock_jtl.py") `
    --jtl $jtl `
    --report $summary `
    --threads $Threads `
    --duration $Duration `
    --rampup $RampUp `
    --base-url "http://${HostName}:$Port"
if ($LASTEXITCODE -ne 0) { throw "The benchmark had failed assertions. See $summary and $jtl" }
Write-Output "GROUP_LOCK_ONLY_OUTPUT=$outputDir"
