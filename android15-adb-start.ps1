param(
    [string]$Adb = "adb",
    [string]$PakUrl = "https://cdn-tgpt.tepaylink.vn/production/updatefs.pak",
    [string]$PakDirectoryUri = "content://com.android.externalstorage.documents/tree/primary%3Apatch"
)

$ErrorActionPreference = "Stop"
$hosts = @("cdn-tgpt.tepaylink.vn", "cdn.tamgioipt.vn")
$caPem = Join-Path $PSScriptRoot "app\src\main\assets\pakredirect_ca.pem"

function Invoke-AdbShell([string]$Command) {
    & $Adb shell $Command
    if ($LASTEXITCODE -ne 0) { throw "adb shell failed: $Command" }
}

$uidLine = (& $Adb shell id) -join "`n"
if ($LASTEXITCODE -ne 0 -or $uidLine -notmatch "uid=0\(root\)") {
    throw "This helper requires an already-root ADB shell."
}

& $Adb shell am force-stop com.example.pakredirect

if (-not (Test-Path $caPem)) { throw "Missing CA file: $caPem" }
& $Adb push $caPem /data/local/tmp/pakredirect_ca.pem
if ($LASTEXITCODE -ne 0) { throw "Failed to push PakRedirect CA." }

$hostCommands = ($hosts | ForEach-Object { "echo '127.0.0.1 $_ # PakRedirect' >> /data/local/tmp/pakredirect_hosts" }) -join "; "
Invoke-AdbShell "grep -v '# PakRedirect`$' /system/etc/hosts > /data/local/tmp/pakredirect_hosts 2>/dev/null || true; $hostCommands; chmod 644 /data/local/tmp/pakredirect_hosts; chcon u:object_r:system_file:s0 /data/local/tmp/pakredirect_hosts 2>/dev/null || true"
Invoke-AdbShell "mkdir -p /data/local/tmp/pakredirect-cacerts; cp /apex/com.android.conscrypt/cacerts/* /data/local/tmp/pakredirect-cacerts/; cp /data/local/tmp/pakredirect_ca.pem /data/local/tmp/pakredirect-cacerts/204d3e6e.0; chmod 755 /data/local/tmp/pakredirect-cacerts; chmod 644 /data/local/tmp/pakredirect-cacerts/*; chown -R root:root /data/local/tmp/pakredirect-cacerts; chcon -R u:object_r:system_security_cacerts_file:s0 /data/local/tmp/pakredirect-cacerts"
Invoke-AdbShell "mountpoint -q /system/etc/hosts && umount /system/etc/hosts 2>/dev/null || true; mount --bind /data/local/tmp/pakredirect_hosts /system/etc/hosts; mountpoint -q /apex/com.android.conscrypt/cacerts && umount /apex/com.android.conscrypt/cacerts 2>/dev/null || true; mount --bind /data/local/tmp/pakredirect-cacerts /apex/com.android.conscrypt/cacerts"

$zygotePid = ((& $Adb shell pidof zygote64) -join "").Trim()
if ($zygotePid -notmatch "^\d+$") { throw "Could not find zygote64 PID." }
Invoke-AdbShell "nsenter -t $zygotePid -m -- sh -c 'mountpoint -q /system/etc/hosts && umount /system/etc/hosts 2>/dev/null || true; mount --bind /data/local/tmp/pakredirect_hosts /system/etc/hosts; mountpoint -q /apex/com.android.conscrypt/cacerts && umount /apex/com.android.conscrypt/cacerts 2>/dev/null || true; mount --bind /data/local/tmp/pakredirect-cacerts /apex/com.android.conscrypt/cacerts'"

Invoke-AdbShell "while iptables -t nat -D OUTPUT -p tcp -d 127.0.0.1 --dport 443 -j REDIRECT --to-ports 18443 >/dev/null 2>&1; do :; done; iptables -t nat -A OUTPUT -p tcp -d 127.0.0.1 --dport 443 -j REDIRECT --to-ports 18443; while iptables -D OUTPUT -p udp -d 127.0.0.1 --dport 443 -j REJECT >/dev/null 2>&1; do :; done; iptables -A OUTPUT -p udp -d 127.0.0.1 --dport 443 -j REJECT"

Invoke-AdbShell "am start-foreground-service -n com.example.pakredirect/.InterceptService -a com.example.pakredirect.START --es url '$PakUrl' --es directory_uri '$PakDirectoryUri' --ez adb_managed true"
Start-Sleep -Seconds 3
Invoke-AdbShell "ss -ltn | grep -q ':18443 '"

Write-Host "PakRedirect Android 15 ADB-managed mode started."
