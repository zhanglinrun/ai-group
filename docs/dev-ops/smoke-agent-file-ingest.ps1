#Requires -Version 7.2
param(
    [string]$Gateway = "http://127.0.0.1:8080",
    [ValidateRange(60, 600)]
    [int]$TimeoutSeconds = 300
)

$ErrorActionPreference = "Stop"
$suffix = [guid]::NewGuid().ToString("N").Substring(0, 12)
$username = "ingest_$suffix"
$password = "AgentIngest!2026"
$smallMarker = "SMALL_DIRECT_$suffix"
$largeMarker = "LARGE_CHUNK_$suffix"
$smallPath = Join-Path ([IO.Path]::GetTempPath()) "ai-group-$suffix-small.txt"
$largePath = Join-Path ([IO.Path]::GetTempPath()) "ai-group-$suffix-large.txt"
$imagePath = Join-Path $PSScriptRoot "..\..\ai-agent\ai-agent-app\src\test\resources\data\dog.png"

function Invoke-JsonApi($Method, $Path, $Body = $null, $Token = $null) {
    $headers = @{ "Content-Type" = "application/json" }
    if ($Token) { $headers.Authorization = "Bearer $Token" }
    $params = @{ Method = $Method; Uri = "$Gateway$Path"; Headers = $headers; TimeoutSec = 30 }
    if ($null -ne $Body) { $params.Body = $Body | ConvertTo-Json -Compress -Depth 20 }
    Invoke-RestMethod @params
}

function Assert-SuccessResponse($Response, [string]$Operation) {
    if ([string]$Response.code -notin @("0000", "200")) {
        throw "$Operation failed: code=$($Response.code) info=$($Response.info)"
    }
}

function Upload-File([string]$SessionId, [string]$Path, [string]$Token) {
    $response = Invoke-RestMethod -Method POST -Uri "$Gateway/api/agent/file/upload" `
        -Headers @{ Authorization = "Bearer $Token" } `
        -Form @{ sessionId = $SessionId; file = Get-Item -LiteralPath $Path } `
        -TimeoutSec 300
    Assert-SuccessResponse $response "upload $(Split-Path -Leaf $Path)"
    return @{
        fileName = [string]$response.data.name
        ossUrl = [string]$response.data.downloadUrl
        domainUrl = [string]$response.data.previewUrl
        fileSize = [long]$response.data.size
        fileType = [string]$response.data.type
        resourceKey = [string]$response.data.resourceKey
        mimeType = $response.data.mimeType
        originFileName = [string]$response.data.originFileName
    }
}

function Invoke-AgentFileRun([string]$SessionId, [string]$RequestId, [string]$Query,
                             [hashtable]$SessionFile, [string]$Token) {
    $body = @{
        sessionId = $SessionId
        requestId = $RequestId
        query = $Query
        executionMode = "STANDARD"
        online = $false
        outputStyle = "chat"
        sessionFiles = @($SessionFile)
    } | ConvertTo-Json -Compress -Depth 20

    $client = [System.Net.Http.HttpClient]::new()
    $client.Timeout = [System.Threading.Timeout]::InfiniteTimeSpan
    $request = [System.Net.Http.HttpRequestMessage]::new(
        [System.Net.Http.HttpMethod]::Post,
        "$Gateway/web/api/v1/gpt/queryAgentStreamIncr"
    )
    $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new("Bearer", $Token)
    $request.Headers.Accept.Add([System.Net.Http.Headers.MediaTypeWithQualityHeaderValue]::new("text/event-stream"))
    $request.Content = [System.Net.Http.StringContent]::new($body, [Text.Encoding]::UTF8, "application/json")
    $cancellation = [System.Threading.CancellationTokenSource]::new([TimeSpan]::FromSeconds($TimeoutSeconds))
    $response = $null
    $toolStarted = $false
    $toolSucceeded = $false
    $completed = $false
    $text = [Text.StringBuilder]::new()
    try {
        $response = $client.SendAsync(
            $request,
            [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead,
            $cancellation.Token
        ).GetAwaiter().GetResult()
        $response.EnsureSuccessStatusCode() | Out-Null
        $stream = $response.Content.ReadAsStreamAsync($cancellation.Token).GetAwaiter().GetResult()
        $reader = [IO.StreamReader]::new($stream, [Text.Encoding]::UTF8)
        try {
            while (-not $completed) {
                $line = $reader.ReadLineAsync().WaitAsync($cancellation.Token).GetAwaiter().GetResult()
                if ($null -eq $line) { break }
                if (-not $line.StartsWith("data:")) { continue }
                $data = $line.Substring(5).Trim()
                if (-not $data.StartsWith("{")) { continue }
                $event = $data | ConvertFrom-Json -Depth 50
                switch ([string]$event.type) {
                    "tool_start" {
                        if ([string]$event.toolName -eq "analyze_file") { $toolStarted = $true }
                    }
                    "tool_end" {
                        if ([string]$event.toolName -eq "analyze_file" -and $event.success -eq $true) {
                            $toolSucceeded = $true
                        }
                    }
                    "text" { [void]$text.Append([string]$event.delta) }
                    "error" { throw "Agent failed: $($event.code) $($event.message)" }
                    "complete" {
                        [void]$text.Append([string]$event.summary)
                        $completed = $true
                    }
                }
            }
        } finally {
            $reader.Dispose()
        }
    } finally {
        if ($response) { $response.Dispose() }
        $request.Dispose()
        $client.Dispose()
        $cancellation.Dispose()
    }
    if (-not $completed -or -not $toolStarted -or -not $toolSucceeded) {
        throw "analyze_file lifecycle incomplete: completed=$completed started=$toolStarted succeeded=$toolSucceeded"
    }
    return $text.ToString()
}

function Get-MemoryCount([string]$OwnerId, [string]$DocType, [string]$FileName) {
    $safeOwner = $OwnerId.Replace("'", "''")
    $safeType = $DocType.Replace("'", "''")
    $safeFile = $FileName.Replace("'", "''")
    $sql = "SELECT count(*) FROM agent_semantic_memory WHERE owner_id='$safeOwner' AND doc_type='$safeType' AND metadata->>'fileName'='$safeFile';"
    $value = & docker exec ai-group-postgres psql -U agent -d agent_memory -tAc $sql
    if ($LASTEXITCODE -ne 0) { throw "PostgreSQL assertion query failed" }
    return [int]([string]$value).Trim()
}

try {
    Set-Content -LiteralPath $smallPath -Encoding utf8NoBOM -Value "Direct-read marker: $smallMarker"
    $largeLines = 1..160 | ForEach-Object {
        "Section $_ records the enterprise agent validation marker $largeMarker with retrieval evidence and operational context."
    }
    Set-Content -LiteralPath $largePath -Encoding utf8NoBOM -Value $largeLines
    if (-not (Test-Path -LiteralPath $imagePath)) { throw "image fixture missing: $imagePath" }

    $register = Invoke-JsonApi POST "/api/auth/register" @{
        username = $username; password = $password; email = "$username@example.test"
    }
    Assert-SuccessResponse $register "register"
    $login = Invoke-JsonApi POST "/api/auth/login" @{ username = $username; password = $password }
    Assert-SuccessResponse $login "login"
    $token = [string]$login.data.accessToken
    $ownerId = [string]$login.data.user.id
    if (-not $token -or -not $ownerId) { throw "login returned no token or owner id" }

    $smallSession = "ingest-small-$suffix"
    $smallFile = Upload-File $smallSession $smallPath $token
    $smallText = Invoke-AgentFileRun $smallSession "ingest-small-request-$suffix" `
        "Call analyze_file for $($smallFile.fileName) and return the exact marker from the file." $smallFile $token
    if (-not $smallText.Contains($smallMarker)) { throw "small direct-read marker was not returned" }
    if ((Get-MemoryCount $ownerId "file_chunk" $smallFile.fileName) -ne 0) {
        throw "small text unexpectedly wrote file_chunk rows"
    }

    $largeSession = "ingest-large-$suffix"
    $largeFile = Upload-File $largeSession $largePath $token
    $largeText = Invoke-AgentFileRun $largeSession "ingest-large-request-$suffix" `
        "Call analyze_file for $($largeFile.fileName) and return the exact marker $largeMarker." $largeFile $token
    if (-not $largeText.Contains($largeMarker)) { throw "large-file retrieval marker was not returned" }
    $largeChunks = Get-MemoryCount $ownerId "file_chunk" $largeFile.fileName
    if ($largeChunks -lt 1) { throw "large text wrote no file_chunk rows" }

    $imageSession = "ingest-image-$suffix"
    $imageFile = Upload-File $imageSession $imagePath $token
    [void](Invoke-AgentFileRun $imageSession "ingest-image-request-$suffix" `
        "Call analyze_file for $($imageFile.fileName) and briefly identify the image content." $imageFile $token)
    $imageDescriptions = Get-MemoryCount $ownerId "image_description" $imageFile.fileName
    if ($imageDescriptions -lt 1) { throw "image wrote no image_description row" }

    Write-Host "AGENT FILE INGEST SMOKE OK (small=direct, largeChunks=$largeChunks, imageDescriptions=$imageDescriptions)"
} finally {
    Remove-Item -LiteralPath $smallPath,$largePath -Force -ErrorAction SilentlyContinue
}
