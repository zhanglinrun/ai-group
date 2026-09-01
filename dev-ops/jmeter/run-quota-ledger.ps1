$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $Root

function Import-DotEnv {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return }
    Get-Content -LiteralPath $Path | ForEach-Object {
        $line = $_.Trim()
        if ($line -eq '' -or $line.StartsWith('#') -or $line -notmatch '=') { return }
        $pair = $line.Split('=', 2)
        $name = $pair[0].Trim()
        $value = $pair[1].Trim().Trim('"').Trim("'")
        if (-not [string]::IsNullOrWhiteSpace($name) -and -not (Test-Path "Env:$name")) {
            Set-Item -Path "Env:$name" -Value $value
        }
    }
}

function Find-JMeter {
    if ($env:JMETER_HOME) {
        $candidate = Join-Path $env:JMETER_HOME 'bin\jmeter.bat'
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    }
    foreach ($commandName in @('jmeter', 'jmeter.bat')) {
        $command = Get-Command $commandName -ErrorAction SilentlyContinue
        if ($command) { return $command.Source }
    }
    foreach ($path in @(
        'C:\apache-jmeter\bin\jmeter.bat',
        'C:\apache-jmeter-5.6.3\bin\jmeter.bat',
        'C:\Program Files\apache-jmeter\bin\jmeter.bat'
    )) {
        if (Test-Path -LiteralPath $path) { return $path }
    }
    return $null
}

Import-DotEnv (Join-Path $Root '.env')

$HostName = if ($env:MEMBER_HOST) { $env:MEMBER_HOST } else { '127.0.0.1' }
$Port = if ($env:MEMBER_PORT) { $env:MEMBER_PORT } else { '18082' }
$Threads = if ($env:JMETER_THREADS) { $env:JMETER_THREADS } else { '50' }
$Duration = if ($env:JMETER_DURATION) { $env:JMETER_DURATION } else { '60' }
$Rampup = if ($env:JMETER_RAMPUP) { $env:JMETER_RAMPUP } else { '10' }
$Users = $Threads
$UserBase = if ($env:JMETER_USER_BASE) { $env:JMETER_USER_BASE } else { [string](1800000000 + (Get-Random -Maximum 90000)) }
$Token = $env:AI_GROUP_INTERNAL_TOKEN
if ([string]::IsNullOrWhiteSpace($Token)) {
    throw 'AI_GROUP_INTERNAL_TOKEN is empty. Copy .env.example to .env first.'
}

$tcp = Test-NetConnection -ComputerName $HostName -Port ([int]$Port) -WarningAction SilentlyContinue
if (-not $tcp.TcpTestSucceeded) {
    throw "member-service is not listening on ${HostName}:${Port}. Publish member on this port before running the quota ledger plan."
}

$plansDir = Join-Path $Root 'dev-ops\jmeter\plans'
python (Join-Path $plansDir 'generate_quota_ledger_jmx.py')
if ($LASTEXITCODE -ne 0) {
    throw 'failed to generate quota-ledger.jmx'
}

$JMeter = Find-JMeter
if (-not $JMeter) {
    throw 'JMeter not found. Install Apache JMeter 5.6+ and set JMETER_HOME, or put jmeter.bat on PATH.'
}

$runId = Get-Date -Format yyyyMMddHHmmssfff
$reportDir = Join-Path $Root "dev-ops\jmeter\reports\quota-ledger\$runId"
New-Item -ItemType Directory -Force -Path $reportDir | Out-Null
$jmx = Join-Path $plansDir 'quota-ledger.jmx'
$jtl = Join-Path $reportDir 'quota-ledger.jtl'
$html = Join-Path $reportDir 'html'
$log = Join-Path $reportDir 'jmeter.log'
$json = Join-Path $reportDir 'summary.json'
if (Test-Path -LiteralPath $jtl) { Remove-Item -LiteralPath $jtl -Force }
if (Test-Path -LiteralPath $html) { Remove-Item -LiteralPath $html -Recurse -Force }

Write-Output "Running JMeter non-GUI: threads=$Threads duration=${Duration}s target=${HostName}:${Port}"
& $JMeter -n -t $jmx -l $jtl -j $log -e -o $html `
    "-Jhost=$HostName" `
    "-Jport=$Port" `
    "-Jtoken=$Token" `
    "-Jthreads=$Threads" `
    "-Jusers=$Users" `
    "-JuserBase=$UserBase" `
    "-Jduration=$Duration" `
    "-Jrampup=$Rampup" `
    "-Jjtl=$jtl" `
    "-JrunId=$runId"
if ($LASTEXITCODE -ne 0) {
    throw "JMeter exited with $LASTEXITCODE. See $log"
}

python (Join-Path $plansDir 'summarize_jtl.py') `
    --jtl $jtl `
    --report $json `
    --threads $Threads `
    --duration $Duration `
    --base-url "http://${HostName}:${Port}"
if ($LASTEXITCODE -ne 0) {
    throw "JMeter finished but error rate is too high. See $json and $jtl"
}

Write-Output "JMETER_HTML=$html"
Write-Output "QUOTA_LEDGER_REPORT=$json"
