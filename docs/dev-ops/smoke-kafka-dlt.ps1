#Requires -Version 7.2
param(
    [string]$Container = "ai-group-kafka",
    [string]$Topic = "member.benefit.completed",
    [string]$DltTopic = "",
    [string]$ConsumerGroup = "member-service",
    [ValidateRange(10, 120)]
    [int]$TimeoutSeconds = 30
)

$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $true
if (-not $DltTopic) { $DltTopic = "$Topic.dlt" }

function Get-TopicOffsets([string]$Name) {
    $lines = & docker exec $Container /opt/kafka/bin/kafka-get-offsets.sh `
        --bootstrap-server localhost:9092 --topic $Name
    $offsets = @{}
    foreach ($line in $lines) {
        if ($line -match '^.+:(\d+):(\d+)$') {
            $offsets[[int]$Matches[1]] = [long]$Matches[2]
        }
    }
    if ($offsets.Count -eq 0) { throw "No offsets returned for topic '$Name'." }
    return $offsets
}

function Get-ConsumerOffset([int]$Partition) {
    $lines = & docker exec $Container /opt/kafka/bin/kafka-consumer-groups.sh `
        --bootstrap-server localhost:9092 --describe --group $ConsumerGroup
    foreach ($line in $lines) {
        $fields = $line.Trim() -split '\s+'
        if ($fields.Count -ge 6 -and $fields[0] -eq $ConsumerGroup -and
                $fields[1] -eq $Topic -and [int]$fields[2] -eq $Partition -and
                $fields[3] -ne '-') {
            return [long]$fields[3]
        }
    }
    return -1
}

$beforeSource = Get-TopicOffsets $Topic
$beforeDlt = Get-TopicOffsets $DltTopic
$marker = [guid]::NewGuid().ToString('N').Substring(0, 12)
$payload = '{"poison":"' + $marker + '"'
$payload | & docker exec -i $Container /opt/kafka/bin/kafka-console-producer.sh `
    --bootstrap-server localhost:9092 --topic $Topic

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$partition = $null
do {
    Start-Sleep -Milliseconds 500
    $source = Get-TopicOffsets $Topic
    $changed = @($source.Keys | Where-Object { $source[$_] -eq $beforeSource[$_] + 1 })
    if ($changed.Count -eq 1) { $partition = [int]$changed[0] }
} while ($null -eq $partition -and (Get-Date) -lt $deadline)
if ($null -eq $partition) { throw "Poison record did not advance exactly one '$Topic' partition." }

do {
    Start-Sleep -Milliseconds 500
    $dlt = Get-TopicOffsets $DltTopic
    $consumerOffset = Get-ConsumerOffset $partition
    $dltAdvanced = $dlt[$partition] -eq $beforeDlt[$partition] + 1
    $sourceAdvanced = $consumerOffset -eq $source[$partition]
} while ((-not $dltAdvanced -or -not $sourceAdvanced) -and (Get-Date) -lt $deadline)

if (-not $dltAdvanced) { throw "Poison record did not reach '$DltTopic' partition $partition." }
if (-not $sourceAdvanced) {
    throw "Consumer '$ConsumerGroup' did not advance '$Topic' partition $partition to $($source[$partition])."
}

Write-Host "KAFKA DLT SMOKE OK (partition=$partition, source=$($source[$partition]), dlt=$($dlt[$partition]), marker=$marker)"
