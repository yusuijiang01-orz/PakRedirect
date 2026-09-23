# RYLUX Game Relay Android Module

The game launch flow now requests Android VPN consent, starts a per-app VPN,
and launches the game only after the relay health probe succeeds.

```text
game package
  -> Android VpnService (only com.tepaylink.tamgioiphantranhmobile)
  -> Hev tun2socks
  -> loopback SOCKS5 bridge (TCP CONNECT only)
  -> wss://relay.lovenom.eu.org/rylux-game[/target-N]
  -> Cloudflare Worker
  -> fixed allowlist: 103.206.217.28:5622 / :6662, 103.206.217.41:6664
```

The Android bridge deliberately rejects destinations outside the fixed target
allowlist. The relay token is loaded from `AuthStorage` at runtime and is never
put in an Intent, resource, log, or repository file.

The tun2socks layer is provided by the pinned Maven AAR
`com.zaneschepke:hevtunnel:1.0.0`, which bundles the Hev SOCKS5 tunnel native
library for `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64`. The wrapper exposes
the native `TProxyService` API used by `RelayVpnService`.

The current relay protocol is TCP-only. UDP is intentionally not claimed as
working because the Cloudflare Worker exposes a WebSocket-to-TCP endpoint.
The service stops after all observed game sessions close and remain idle for
90 seconds, or when Android revokes VPN access.
