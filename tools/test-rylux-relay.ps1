param(
    [string]$Url = "wss://relay.lovenom.eu.org/rylux-game"
)

$secure = Read-Host "Enter RYLUX_RELAY_TOKEN" -AsSecureString
$ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try {
    $token = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
}

$socket = [System.Net.WebSockets.ClientWebSocket]::new()
$socket.Options.SetRequestHeader("Authorization", "Bearer $token")
$cts = [Threading.CancellationTokenSource]::new(15000)

try {
    $socket.ConnectAsync([Uri]$Url, $cts.Token).GetAwaiter().GetResult()
    Write-Host ("WebSocket state: " + $socket.State)
    if ($socket.State -eq [System.Net.WebSockets.WebSocketState]::Open) {
        Write-Host "Relay handshake succeeded; target TCP is reachable."
        $socket.CloseAsync(
            [System.Net.WebSockets.WebSocketCloseStatus]::NormalClosure,
            "smoke test",
            [Threading.CancellationToken]::None
        ).GetAwaiter().GetResult()
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
