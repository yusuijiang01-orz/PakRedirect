param(
    [string]$Url = "wss://relay.lovenom.eu.org/rylux-game"
)

$Url = $Url -replace '\\+://', '://'
$secure = Read-Host "Enter RYLUX_RELAY_TOKEN" -AsSecureString
$ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try {
    $token = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
}

Write-Host "Testing relay: $Url"

$curlArgs = @(
    "--dump-header", "-", "--output", "NUL", "--http1.1", "--max-time", "12", $Url,
    "-H", "Authorization: Bearer $token",
    "-H", "Connection: Upgrade",
    "-H", "Upgrade: websocket",
    "-H", "Sec-WebSocket-Version: 13",
    "-H", "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ=="
)
Write-Host "--- HTTP handshake ---"
& curl.exe @curlArgs
Write-Host "--- WebSocket client ---"

$socket = [System.Net.WebSockets.ClientWebSocket]::new()
$socket.Options.SetRequestHeader("Authorization", "Bearer $token")
$cts = [Threading.CancellationTokenSource]::new(15000)

try {
    $socket.ConnectAsync([Uri]$Url, $cts.Token).GetAwaiter().GetResult()
    Write-Host ("WebSocket state: " + $socket.State)
    if ($socket.State -eq [System.Net.WebSockets.WebSocketState]::Open) {
        Write-Host "Relay handshake succeeded; target TCP is reachable."
        # The target may send binary data immediately after the 101 response.
        # CloseOutputAsync avoids waiting for a close frame while that data is in flight.
        try {
            $socket.CloseOutputAsync(
                [System.Net.WebSockets.WebSocketCloseStatus]::NormalClosure,
                "smoke test",
                [Threading.CancellationToken]::None
            ).GetAwaiter().GetResult()
        } catch {
            Write-Host "WebSocket closed after successful handshake."
        }
        exit 0
    }
    exit 1
} catch {
    Write-Error ("Relay handshake failed: " + $_.Exception.Message)
    exit 1
} finally {
    $socket.Dispose()
    $token = $null
}
