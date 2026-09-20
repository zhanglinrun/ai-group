param(
    [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string]$TeamId,
    [Parameter(Mandatory = $true)][ValidateRange(1, [long]::MaxValue)][long]$ActivityId,
    [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string]$GoodsId,
    [Parameter(Mandatory = $true)][ValidateRange(1, 100000)][int]$ExpectedFreeSlots,
    [ValidateRange(1, 100000)][int]$Threads = 2000,
    [ValidateRange(0, 3600)][int]$RampUp = 0,
    [string]$HostName = "127.0.0.1",
    [ValidateRange(1, 65535)][int]$Port = 8091
)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($TeamId) -or [string]::IsNullOrWhiteSpace($GoodsId)) {
    throw "TeamId and GoodsId must be nonempty."
}
if ($RampUp -ne 0) { throw "Fixed-team spike requires RampUp=0; use a separate run for stepped load." }
if ($ExpectedFreeSlots -gt $Threads) { throw "ExpectedFreeSlots cannot exceed Threads." }
if ($HostName -notin @("127.0.0.1", "localhost")) { throw "Fixed-team gate only supports the local ai-group-bench Compose stack." }
if (-not $env:AI_GROUP_IDENTITY_SIGNING_SECRET -or -not $env:AI_GROUP_INTERNAL_TOKEN) {
    throw "Set AI_GROUP_IDENTITY_SIGNING_SECRET and AI_GROUP_INTERNAL_TOKEN in the process environment."
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

$plansDir = Join-Path $PSScriptRoot "plans"
& python (Join-Path $plansDir "generate_group_lock_jmx.py") --fixed-team
if ($LASTEXITCODE -ne 0) { throw "Failed to generate group-lock-fixed-team.jmx" }

$plan = Join-Path $plansDir "group-lock-fixed-team.jmx"
$runId = Get-Date -Format "yyyyMMddHHmmssfff"
$outputDir = Join-Path $PSScriptRoot "reports\group-lock-fixed-team\$runId"
if (Test-Path -LiteralPath $outputDir) { throw "Run ID collision at $outputDir; retry the run." }
New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
$jtl = Join-Path $outputDir "results.jtl"
$log = Join-Path $outputDir "jmeter.log"
$gate = Join-Path $plansDir "verify_group_lock_fixed_team.py"
$gateArgs = @("--output-dir", $outputDir, "--team-id", $TeamId, "--activity-id", "$ActivityId", "--goods-id", $GoodsId, "--run-id", $runId, "--expected-free-slots", "$ExpectedFreeSlots", "--port", "$Port")
& python $gate --phase pre @gateArgs
if ($LASTEXITCODE -ne 0) { throw "Preflight failed. See $outputDir\verification.json and db-pre.json; no load was sent." }

& $jmeter -n -t $plan -l $jtl -j $log `
    "-Jthreads=$Threads" `
    "-Jrampup=$RampUp" `
    "-Jhost=$HostName" `
    "-Jport=$Port" `
    "-JteamId=$TeamId" `
    "-JactivityId=$ActivityId" `
    "-JgoodsId=$GoodsId" `
    "-JrunId=$runId" `
    "-Jsource=s01" `
    "-Jchannel=c01" `
    "-JorderPrice=12.00" `
    "-Jsample_variables=businessCode,outTradeNo" `
    "-Jjmeter.save.saveservice.output_format=csv" `
    "-Jjmeter.save.saveservice.print_field_names=true" `
    "-Jjmeter.save.saveservice.timestamp_format=ms"
$jmeterExitCode = $LASTEXITCODE
if (-not (Test-Path $jtl)) { Write-Warning "JMeter exited with $jmeterExitCode without creating JTL. See $log" }

$summary = Join-Path $outputDir "summary.json"
$summaryExitCode = 1
if (Test-Path $jtl) {
    & python (Join-Path $plansDir "summarize_group_lock_fixed_team_jtl.py") `
        --jtl $jtl --report $summary --threads $Threads --rampup $RampUp `
        --team-id $TeamId --activity-id $ActivityId --goods-id $GoodsId --run-id $runId `
        --expected-free-slots $ExpectedFreeSlots --base-url "http://${HostName}:$Port"
    $summaryExitCode = $LASTEXITCODE
}
& python $gate --phase post @gateArgs
$verificationExitCode = $LASTEXITCODE
Write-Output "GROUP_LOCK_FIXED_TEAM_OUTPUT=$outputDir"
if ($jmeterExitCode -ne 0 -or $summaryExitCode -ne 0 -or $verificationExitCode -ne 0) {
    throw "Spike failed (JMeter $jmeterExitCode, summary $summaryExitCode, DB verification $verificationExitCode). See $outputDir"
}
