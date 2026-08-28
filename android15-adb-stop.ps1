param([string]$Adb = "adb")

$ErrorActionPreference = "Stop"
$zygotePid = ((& $Adb shell pidof zygote64) -join "").Trim()
$cleanup = "while iptables -t nat -D OUTPUT -p tcp -d 127.0.0.1 --dport 443 -j REDIRECT --to-ports 18443 >/dev/null 2>&1; do :; done; while iptables -D OUTPUT -p udp -d 127.0.0.1 --dport 443 -j REJECT >/dev/null 2>&1; do :; done; umount /system/etc/hosts 2>/dev/null || true"
& $Adb shell $cleanup
if ($LASTEXITCODE -ne 0) { throw "Failed to clean PakRedirect rules." }
if ($zygotePid -match "^\d+$") {
    & $Adb shell "nsenter -t $zygotePid -m -- umount /system/etc/hosts 2>/dev/null || true"
}
& $Adb shell am force-stop com.example.pakredirect
Write-Host "PakRedirect stopped and ADB-managed rules removed."
