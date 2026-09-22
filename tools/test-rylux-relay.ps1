param(
    [string]$Url = "wss://relay.lovenom.eu.org/rylux-game"
)

$secure = Read-Host "输入 RYLUX_RELAY_TOKEN" -AsSecureString
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
    Write-Host ("WebSocket 状态: " + $socket.State)
    if ($socket.State -eq [System.Net.WebSockets.WebSocketState]::Open) {
        Write-Host "Relay 握手成功，目标 TCP 已接通。"
        $socket.CloseAsync(
            [System.Net.WebSockets.WebSocketCloseStatus]::NormalClosure,
            "smoke test",
            [Threading.CancellationToken]::None
        ).GetAwaiter().GetResult()
        exit 0
    }
    exit 1
} catch {
    Write-Error ("Relay 握手失败: " + $_.Exception.Message)
    exit 1
} finally {
    $socket.Dispose()
    $token = $null
}
