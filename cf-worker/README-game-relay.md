# RYLUX Game Relay

This Worker keeps the existing GitHub CDN proxy and adds a restricted WebSocket
relay for the game TCP endpoint.

## Cloudflare setup

1. Deploy `cf-worker/worker.js` to the `rylux-cdn` Worker.
2. Keep the route:
   `relay.lovenom.eu.org/rylux-game*`
3. Add a Worker secret named `RYLUX_RELAY_TOKEN`.
4. Configure the backend with the same secret. The backend signs short-lived
   relay credentials for authorized Android clients; the long-lived secret is
   never embedded in the APK.

The relay only connects to `103.206.217.41:6664`; it does not accept an
arbitrary destination and must not be changed into an open proxy.

## Smoke test

A normal HTTPS request should return `426 WebSocket upgrade required`. A
WebSocket client that sends either the configured secret (diagnostics only) or
a valid short-lived backend credential should receive `101 Switching Protocols`.
A successful handshake only proves the relay is reachable; latency still needs
to be measured from the emulator.
