#Requires -Version 7.2
# Real cross-session memory smoke:
# isolated users -> explicit memory -> Qdrant persistence -> cross-session recall -> owner isolation -> cleanup.
[CmdletBinding()]
param(
    [string]$Gateway = "http://127.0.0.1:8080",
    [string]$QdrantUrl = "",
    [string]$QdrantApiKey = $env:QDRANT_API_KEY,
    [string]$Collection = "",
    [ValidateRange(30, 600)]
    [int]$SseTimeoutSeconds = 180,
    [ValidateRange(5, 180)]
    [int]$PersistenceTimeoutSeconds = 60,
    [ValidateRange(100, 5000)]
    [int]$PollIntervalMilliseconds = 1000
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($QdrantUrl)) {
    $QdrantUrl = if ([string]::IsNullOrWhiteSpace($env:QDRANT_URL)) {
        "http://127.0.0.1:6333"
    } else {
        $env:QDRANT_URL
    }
}
if ([string]::IsNullOrWhiteSpace($Collection)) {
    $Collection = if ([string]::IsNullOrWhiteSpace($env:AGENT_MEMORY_LONGTERM_COLLECTION)) {
        "agent_conversation_memory"
    } else {
        $env:AGENT_MEMORY_LONGTERM_COLLECTION
    }
}
$Gateway = $Gateway.TrimEnd('/')
$QdrantUrl = $QdrantUrl.TrimEnd('/')

function Invoke-JsonApi {
    param(
        [Parameter(Mandatory)]
        [ValidateSet('GET', 'POST')]
        [string]$Method,
        [Parameter(Mandatory)]
        [string]$Path,
        [object]$Body = $null,
        [string]$Token = $null
    )

    $headers = @{ 'Content-Type' = 'application/json' }
    if (-not [string]::IsNullOrWhiteSpace($Token)) {
        $headers.Authorization = "Bearer $Token"
    }
    $parameters = @{
        Method     = $Method
        Uri        = "$Gateway$Path"
        Headers    = $headers
        TimeoutSec = 30
    }
    if ($null -ne $Body) {
        $parameters.Body = $Body | ConvertTo-Json -Compress -Depth 20
    }
    return Invoke-RestMethod @parameters
}

function Register-IsolatedUser {
    param(
        [Parameter(Mandatory)]
        [string]$Username,
        [Parameter(Mandatory)]
        [string]$Password
    )

    $register = Invoke-JsonApi -Method POST -Path '/api/auth/register' -Body @{
        username = $Username
        password = $Password
        email    = "$Username@example.test"
    }
    if ([string]$register.code -ne '200' -or $null -eq $register.data.id) {
        throw "isolated user registration failed"
    }

    $login = Invoke-JsonApi -Method POST -Path '/api/auth/login' -Body @{
        username = $Username
        password = $Password
    }
    $token = [string]$login.data.accessToken
    $ownerId = [string]$login.data.user.id
    if ([string]$login.code -ne '200' -or
        [string]::IsNullOrWhiteSpace($token) -or
        [string]::IsNullOrWhiteSpace($ownerId) -or
        $ownerId -ne [string]$register.data.id) {
        throw "isolated user login failed"
    }

    # Never print or embed this object in diagnostic output: it contains the access token.
    return [pscustomobject]@{
        OwnerId = $ownerId
        Token   = $token
    }
}

function Invoke-AgentSse {
    param(
        [Parameter(Mandatory)]
        [string]$Token,
        [Parameter(Mandatory)]
        [string]$SessionId,
        [Parameter(Mandatory)]
        [string]$RequestId,
        [Parameter(Mandatory)]
        [string]$Query,
        [Parameter(Mandatory)]
        [string]$DeviceId
    )

    $requestBody = @{
        sessionId   = $SessionId
        requestId   = $RequestId
        query       = $Query
        executionMode = 'STANDARD'
        outputStyle = 'chat'
    } | ConvertTo-Json -Compress

    $client = [System.Net.Http.HttpClient]::new()
    $client.Timeout = [System.Threading.Timeout]::InfiniteTimeSpan
    $request = [System.Net.Http.HttpRequestMessage]::new(
        [System.Net.Http.HttpMethod]::Post,
        "$Gateway/web/api/v1/gpt/queryAgentStreamIncr"
    )
    $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $Token)
    $request.Headers.Accept.Add(
        [System.Net.Http.Headers.MediaTypeWithQualityHeaderValue]::new('text/event-stream')
    )
    $request.Headers.Add('X-Device-Id', $DeviceId)
    $request.Content = [System.Net.Http.StringContent]::new(
        $requestBody,
        [System.Text.Encoding]::UTF8,
        'application/json'
    )
    $cancellation = [System.Threading.CancellationTokenSource]::new(
        [TimeSpan]::FromSeconds($SseTimeoutSeconds)
    )
    $response = $null
    $frameCount = 0
    $terminalResult = $null
    $eventTypes = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::OrdinalIgnoreCase
    )

    try {
        $response = $client.SendAsync(
            $request,
            [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead,
            $cancellation.Token
        ).GetAwaiter().GetResult()
        $response.EnsureSuccessStatusCode() | Out-Null
        if ($null -eq $response.Content.Headers.ContentType -or
            $response.Content.Headers.ContentType.MediaType -ne 'text/event-stream') {
            throw "unexpected Agent response content-type"
        }

        $stream = $response.Content.ReadAsStreamAsync($cancellation.Token).GetAwaiter().GetResult()
        $reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::UTF8)
        $deadline = [DateTime]::UtcNow.AddSeconds($SseTimeoutSeconds)
        try {
            while ([DateTime]::UtcNow -lt $deadline) {
                $remaining = $deadline - [DateTime]::UtcNow
                $line = $reader.ReadLineAsync().WaitAsync(
                    $remaining,
                    $cancellation.Token
                ).GetAwaiter().GetResult()
                if ($null -eq $line) { break }
                if (-not $line.StartsWith('data:')) { continue }

                $data = $line.Substring(5).Trim()
                if ([string]::IsNullOrWhiteSpace($data) -or $data -eq '[DONE]') { continue }
                if (-not $data.StartsWith('{') -and -not $data.StartsWith('[')) { continue }

                $frame = $data | ConvertFrom-Json -Depth 50
                $frameCount++
                $eventPayload = $frame.resultMap.eventData.resultMap
                if ($null -eq $eventPayload -and $frame.messageType) {
                    $eventPayload = $frame
                }
                if ($null -eq $eventPayload) { continue }

                $messageType = [string]$eventPayload.messageType
                $eventResult = [string]$eventPayload.result
                if (-not [string]::IsNullOrWhiteSpace($messageType)) {
                    $eventTypes.Add($messageType) | Out-Null
                }
                if ($messageType -eq 'result' -and
                    ($frame.finished -eq $true -or $eventPayload.finish -eq $true)) {
                    $terminalResult = if (-not [string]::IsNullOrWhiteSpace($eventResult)) {
                        $eventResult
                    } else {
                        [string]$frame.response
                    }
                    break
                }
            }
        } finally {
            $reader.Dispose()
        }
    } finally {
        if ($null -ne $response) { $response.Dispose() }
        $request.Dispose()
        $client.Dispose()
        $cancellation.Dispose()
    }

    if ([string]::IsNullOrWhiteSpace($terminalResult)) {
        throw "Agent SSE did not produce a non-empty terminal result"
    }
    return [pscustomobject]@{
        Result     = $terminalResult
        FrameCount = $frameCount
        EventTypes = @($eventTypes)
    }
}

function Invoke-QdrantJson {
    param(
        [Parameter(Mandatory)]
        [ValidateSet('GET', 'POST')]
        [string]$Method,
        [Parameter(Mandatory)]
        [string]$Path,
        [object]$Body = $null
    )

    $headers = @{ 'Content-Type' = 'application/json' }
    if (-not [string]::IsNullOrWhiteSpace($QdrantApiKey)) {
        $headers['api-key'] = $QdrantApiKey
    }
    $parameters = @{
        Method     = $Method
        Uri        = "$QdrantUrl$Path"
        Headers    = $headers
        TimeoutSec = 20
    }
    if ($null -ne $Body) {
        $parameters.Body = $Body | ConvertTo-Json -Compress -Depth 20
    }
    return Invoke-RestMethod @parameters
}

function Test-IsNotFoundResponse {
    param([Parameter(Mandatory)]$ErrorRecord)

    return $null -ne $ErrorRecord.Exception.Response -and
        [int]$ErrorRecord.Exception.Response.StatusCode -eq 404
}

function Get-QdrantOwnerPoints {
    param([Parameter(Mandatory)][string]$OwnerId)

    $encodedCollection = [Uri]::EscapeDataString($Collection)
    try {
        $scrollParameters = @{
            Method = 'POST'
            Path   = "/collections/$encodedCollection/points/scroll"
            Body   = @{
                filter       = @{
                    must = @(
                        @{
                            key   = 'ownerId'
                            match = @{ value = $OwnerId }
                        }
                    )
                }
                limit        = 100
                with_payload = $true
                with_vector  = $false
            }
        }
        $response = Invoke-QdrantJson @scrollParameters
    } catch {
        if (Test-IsNotFoundResponse -ErrorRecord $_) {
            return @()
        }
        throw
    }
    return @($response.result.points)
}

function Test-ContainsOrdinalIgnoreCase {
    param(
        [AllowNull()]
        [string]$Text,
        [Parameter(Mandatory)]
        [string]$Expected
    )

    return -not [string]::IsNullOrEmpty($Text) -and
        $Text.IndexOf($Expected, [StringComparison]::OrdinalIgnoreCase) -ge 0
}

function Wait-QdrantMemory {
    param(
        [Parameter(Mandatory)]
        [string]$OwnerId,
        [Parameter(Mandatory)]
        [string]$ExpectedText
    )

    $deadline = [DateTime]::UtcNow.AddSeconds($PersistenceTimeoutSeconds)
    do {
        foreach ($point in @(Get-QdrantOwnerPoints -OwnerId $OwnerId)) {
            if (Test-ContainsOrdinalIgnoreCase -Text ([string]$point.payload.text) -Expected $ExpectedText) {
                return $point
            }
        }
        Start-Sleep -Milliseconds $PollIntervalMilliseconds
    } while ([DateTime]::UtcNow -lt $deadline)

    throw "long-term memory was not persisted to Qdrant before timeout; verify AGENT_MEMORY_LONGTERM_ENABLED=true"
}

function Get-MemoryInspection {
    param(
        [Parameter(Mandatory)]
        [string]$Token,
        [Parameter(Mandatory)]
        [string]$SessionId,
        [Parameter(Mandatory)]
        [string]$Query
    )

    $encodedSessionId = [Uri]::EscapeDataString($SessionId)
    $encodedQuery = [Uri]::EscapeDataString($Query)
    $inspectionParameters = @{
        Method = 'GET'
        Path   = "/api/agent/memory/inspect?sessionId=$encodedSessionId&query=$encodedQuery"
        Token  = $Token
    }
    $inspection = Invoke-JsonApi @inspectionParameters
    if ([string]$inspection.code -ne '0000' -or $null -eq $inspection.data) {
        throw "memory inspection failed"
    }
    return [string]$inspection.data.memoryBlock
}

function Remove-QdrantOwnerPoints {
    param([Parameter(Mandatory)][string]$OwnerId)

    $encodedCollection = [Uri]::EscapeDataString($Collection)
    try {
        $deleteParameters = @{
            Method = 'POST'
            Path   = "/collections/$encodedCollection/points/delete?wait=true"
            Body   = @{
                filter = @{
                    must = @(
                        @{
                            key   = 'ownerId'
                            match = @{ value = $OwnerId }
                        }
                    )
                }
            }
        }
        $deleteResponse = Invoke-QdrantJson @deleteParameters
    } catch {
        if (Test-IsNotFoundResponse -ErrorRecord $_) {
            return
        }
        throw
    }
    if ([string]$deleteResponse.status -ne 'ok') {
        throw "Qdrant owner-scoped cleanup was not acknowledged"
    }

    $deadline = [DateTime]::UtcNow.AddSeconds(10)
    do {
        if (@(Get-QdrantOwnerPoints -OwnerId $OwnerId).Count -eq 0) {
            return
        }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Qdrant owner-scoped cleanup did not remove all test points"
}

$suffix = [Guid]::NewGuid().ToString('N').Substring(0, 12)
$passphrase = "MEMORY_$($suffix.ToUpperInvariant())"
$usernameA = "mem_a_$suffix"
$usernameB = "mem_b_$suffix"
$password = "AgentMemory!2026_$suffix"
$sessionA1 = "mem-a1-$suffix"
$sessionA2 = "mem-a2-$suffix"
$sessionB = "mem-b-$suffix"
$recallQuery = '我的项目验证口令是什么？请只回答口令，不知道就回答“不知道”。'

$ownerA = $null
$ownerB = $null
$accessTokenA = $null
$accessTokenB = $null
$testFailure = $null
$cleanupFailures = [System.Collections.Generic.List[string]]::new()
$totalFrames = 0

try {
    Write-Host '==> Register isolated users A and B'
    $identityA = Register-IsolatedUser -Username $usernameA -Password $password
    $identityB = Register-IsolatedUser -Username $usernameB -Password $password
    $ownerA = [string]$identityA.OwnerId
    $ownerB = [string]$identityB.OwnerId
    $accessTokenA = [string]$identityA.Token
    $accessTokenB = [string]$identityB.Token

    Write-Host '==> User A session 1 explicitly stores a unique long-term fact'
    $storeParameters = @{
        Token     = $accessTokenA
        SessionId = $sessionA1
        RequestId = "mem-store-$suffix"
        Query     = "请记住：我的项目验证口令是 $passphrase。"
        DeviceId  = "memory-smoke-a-$suffix"
    }
    $storeResult = Invoke-AgentSse @storeParameters
    $totalFrames += [int]$storeResult.FrameCount

    Write-Host '==> Poll Qdrant until the owner-scoped point is durable'
    Wait-QdrantMemory -OwnerId $ownerA -ExpectedText $passphrase | Out-Null

    Write-Host '==> User A session 2 recalls the fact and exposes the long-term memory layer'
    $recallAParameters = @{
        Token     = $accessTokenA
        SessionId = $sessionA2
        RequestId = "mem-recall-a-$suffix"
        Query     = $recallQuery
        DeviceId  = "memory-smoke-a-$suffix"
    }
    $recallResultA = Invoke-AgentSse @recallAParameters
    $totalFrames += [int]$recallResultA.FrameCount
    if (-not (Test-ContainsOrdinalIgnoreCase -Text $recallResultA.Result -Expected $passphrase)) {
        throw 'user A cross-session SSE answer did not contain the stored passphrase'
    }

    $inspectAParameters = @{
        Token     = $accessTokenA
        SessionId = $sessionA2
        Query     = $recallQuery
    }
    $memoryBlockA = Get-MemoryInspection @inspectAParameters
    if (-not (Test-ContainsOrdinalIgnoreCase -Text $memoryBlockA -Expected '长期记忆（跨会话）')) {
        throw 'user A memory inspection did not expose the long-term memory layer'
    }
    if (-not (Test-ContainsOrdinalIgnoreCase -Text $memoryBlockA -Expected $passphrase)) {
        throw 'user A memory inspection did not contain the stored passphrase'
    }

    Write-Host '==> User B asks the same question and must remain isolated from user A'
    $recallBParameters = @{
        Token     = $accessTokenB
        SessionId = $sessionB
        RequestId = "mem-recall-b-$suffix"
        Query     = $recallQuery
        DeviceId  = "memory-smoke-b-$suffix"
    }
    $recallResultB = Invoke-AgentSse @recallBParameters
    $totalFrames += [int]$recallResultB.FrameCount
    if (Test-ContainsOrdinalIgnoreCase -Text $recallResultB.Result -Expected $passphrase) {
        throw 'owner isolation failed: user B SSE answer leaked user A memory'
    }

    $inspectBParameters = @{
        Token     = $accessTokenB
        SessionId = $sessionB
        Query     = $recallQuery
    }
    $memoryBlockB = Get-MemoryInspection @inspectBParameters
    if (Test-ContainsOrdinalIgnoreCase -Text $memoryBlockB -Expected $passphrase) {
        throw 'owner isolation failed: user B memory inspection leaked user A memory'
    }
} catch {
    $testFailure = $_
} finally {
    Write-Host '==> Clean Qdrant test vectors with owner-scoped filters'
    foreach ($ownerId in @($ownerA, $ownerB)) {
        if ([string]::IsNullOrWhiteSpace([string]$ownerId)) { continue }
        try {
            Remove-QdrantOwnerPoints -OwnerId ([string]$ownerId)
        } catch {
            $cleanupFailures.Add("owner-scoped cleanup failed for one isolated test user: $($_.Exception.Message)") | Out-Null
        }
    }
    # Reduce the chance of secrets being exposed by an interactive caller inspecting variables.
    $accessTokenA = $null
    $accessTokenB = $null
    $identityA = $null
    $identityB = $null
}

if ($null -ne $testFailure) {
    throw $testFailure
}
if ($cleanupFailures.Count -gt 0) {
    throw ($cleanupFailures -join '; ')
}

Write-Host "AGENT CROSS-SESSION MEMORY SMOKE OK (frames=$totalFrames, ownerIsolation=true, qdrantCleanup=true)"
