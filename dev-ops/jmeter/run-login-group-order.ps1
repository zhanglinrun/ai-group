param(
    [int]$Threads = $(if ($env:JMETER_THREADS) { [int]$env:JMETER_THREADS } else { 20 }),
    [int]$Loops = $(if ($env:JMETER_LOOPS) { [int]$env:JMETER_LOOPS } else { 1 }),
    [int]$Rampup = $(if ($env:JMETER_RAMPUP) { [int]$env:JMETER_RAMPUP } else { 10 })
)

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
    $usual = @(
        'C:\apache-jmeter\bin\jmeter.bat',
        'C:\apache-jmeter-5.6.3\bin\jmeter.bat',
        'C:\Program Files\apache-jmeter\bin\jmeter.bat'
    )
    foreach ($path in $usual) {
        if (Test-Path -LiteralPath $path) { return $path }
    }
    return $null
}

Import-DotEnv (Join-Path $Root '.env')

if ($env:ALIPAY_ENABLED -and $env:ALIPAY_ENABLED.Trim().ToLowerInvariant() -eq 'true') {
    throw 'Refusing to run: ALIPAY_ENABLED=true. This test creates only local pending orders; start the full stack with ALIPAY_ENABLED=false.'
}
if ($Threads -lt 1 -or $Loops -lt 1 -or $Rampup -lt 0) {
    throw 'Threads and Loops must be positive; Rampup must be zero or positive.'
}

$HostName = if ($env:GATEWAY_HOST) { $env:GATEWAY_HOST } else { '127.0.0.1' }
$Port = if ($env:GATEWAY_PORT) { $env:GATEWAY_PORT } else { '8080' }
$tcp = Test-NetConnection -ComputerName $HostName -Port ([int]$Port) -WarningAction SilentlyContinue
if (-not $tcp.TcpTestSucceeded) {
    throw "gateway-service is not listening on ${HostName}:${Port}. Start the local full stack with ALIPAY_ENABLED=false first."
}

$JMeter = Find-JMeter
if (-not $JMeter) {
    throw 'JMeter not found. Set JMETER_HOME or put jmeter.bat on PATH.'
}

$plansDir = Join-Path $Root 'dev-ops\jmeter\plans'
python (Join-Path $plansDir 'generate_login_group_order_jmx.py')
if ($LASTEXITCODE -ne 0) { throw 'failed to generate login-group-order.jmx' }

$runId = Get-Date -Format yyyyMMddHHmmssfff
$reportDir = Join-Path $Root "dev-ops\jmeter\reports\login-group-order\$runId"
New-Item -ItemType Directory -Force -Path $reportDir | Out-Null
$jmx = Join-Path $plansDir 'login-group-order.jmx'
$jtl = Join-Path $reportDir 'login-group-order.jtl'
$html = Join-Path $reportDir 'html'
$log = Join-Path $reportDir 'jmeter.log'
$json = Join-Path $reportDir 'summary.json'
if (Test-Path -LiteralPath $jtl) { Remove-Item -LiteralPath $jtl -Force }
if (Test-Path -LiteralPath $html) { Remove-Item -LiteralPath $html -Recurse -Force }

Write-Output "Running non-GUI JMeter: users=$Threads loops=$Loops ramp-up=${Rampup}s target=${HostName}:${Port}"
& $JMeter -n -t $jmx -l $jtl -j $log -e -o $html `
    "-Jhost=$HostName" `
    "-Jport=$Port" `
    "-Jthreads=$Threads" `
    "-Jloops=$Loops" `
    "-Jrampup=$Rampup" `
    "-JrunId=$runId"
if ($LASTEXITCODE -ne 0) { throw "JMeter exited with $LASTEXITCODE. See $log" }
if (-not (Test-Path -LiteralPath $jtl)) {
    throw "JMeter did not create a JTL result file. See $log"
}

python (Join-Path $plansDir 'summarize_login_group_order_jtl.py') `
    --jtl $jtl `
    --report $json `
    --threads $Threads `
    --loops $Loops `
    --rampup $Rampup `
    --base-url "http://${HostName}:${Port}"
if ($LASTEXITCODE -ne 0) { throw "login/group-order test contains failed transactions. See $jtl and $log" }

Write-Output "JMETER_HTML=$html"
Write-Output "LOGIN_GROUP_ORDER_REPORT=$json"
