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

The relay accepts only these fixed game endpoints; it does not accept an
arbitrary destination and must not be changed into an open proxy:

- `/rylux-game` → `103.206.217.41:6664` (legacy endpoint)
- `/rylux-game/target-2` → `103.206.217.28:6662` (ADB-observed endpoint)
- `/rylux-game/target-3` → `103.206.217.28:5622` (ADB-observed game login endpoint)

## Smoke test

A normal HTTPS request to either approved path should return `426 WebSocket upgrade required`. An
unknown path below `/rylux-game/` should return `404`. A WebSocket client that
sends a valid short-lived backend credential should receive `101 Switching Protocols`.
A successful handshake only proves the relay is reachable; latency still needs
to be measured from the emulator.
