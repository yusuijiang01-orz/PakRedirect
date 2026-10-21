# Lua sequential acceptance

This is the acceptance record for the active Lua goal. A green JVM fixture test
does not establish that Android capture/OCR/input or an in-game task passed.
Do not start a later stage until the current stage's requirements are satisfied.
Run a passed stage again only if a subsequent change invalidates its evidence.
Build the final APK only after all stages pass.

## Current candidate

- Source baseline: `5ce642a7a595fc5ef20e56370be3218308ebdb6e`.
- Device checked 2026-10-21: `emulator-5554`, RYLUX `2.4.54-test`, versionCode `75`.
- Device accessibility adapter is enabled. This is readiness, not screenshot proof.
- Existing APK is available for tests; no new APK is required to start acceptance.

## Gates

| Stage | Required evidence | State |
| --- | --- | --- |
| 1. Lua loading and execution control | Real recovered entry and worker execute using the production Lua instruction gate and execution control. Progress freezes on pause, resumes, and all runners end promptly on stop, including a paused run. Existing busy-loop, worker cancellation and host-sleep regressions also pass. | Passed (JVM; device service routing remains stage 2) |
| 2. Android sandbox and host | Launch the localized game through the sandbox. Prove foreground recognition and actual Android host screenshot success, foreground rejection outside the game, and UI pause/stop command delivery. ADB screenshots alone cannot prove the app's capture API. | In progress |
| 3. Localized login | Actual Lua handles known terms/login/server/character pages, closes a recognized stalled login dialog after 10 seconds, retries, and reaches the game world. Stop before other gameplay tasks. Record screens, decisions and post-action states. | Not started |
| 4. OCR/image/coordinate adapters | Current localized game samples pass actual-device OCR and image matching; detected coordinates correspond to the visible controls and input reaches them. Preserve negative/ambiguous/low-confidence checks. | Not started |
| 5. Route and pathfinding | Lua selects the configured map, submits coordinates and reaches the destination. Failed pathfinding returns on its bounded timeout. | Not started |
| 6. Activity and party recovery | Lua closes the actual activity and party panels. Creating a party confirms the resulting state and does not repeat indefinitely. Verify leader/member and unreadable-name behavior. | Not started |
| 7. Combat and potion supply | Actual auto-combat state changes and configured low-resource potion use are observed. Empty or unknown slots do not trigger medicine input. | Not started |
| 8. Backpack and merchant | Scan actual bag counts/weight/slots; complete an allowlisted purchase with verified quantity. Validate configured sale protections, exact sale item/count and confirmation; do not claim fixture transactions are device transactions. | Not started |
| 9. Recovery and integrated run | Execute the full configured sequence with observable progress; recover a known transient/stall; pause/resume/stop during the task; no endless retries or continued input after stop. All Lua regression cases run with no skipped required device tests. | Not started |
| Final artifact | Complete regression evidence, Android checks, signed final APK, source/artifact identity and APK inspection. | Not started |

## Evidence rules

- Save timestamped device screens, logs and command/test reports under `work/`;
  link their exact paths here after a stage passes or fails.
- Record which source and installed APK each result exercised. JVM execution of
  current source cannot prove that an older installed APK contains a later fix.
- A skipped live test is incomplete, never passed. Tests must assert the page
  and post-action result; arbitrary pixel difference is insufficient.
- An unrecognized screen or host API failure is a failed current stage. Repair
  or explain the concrete external requirement before proceeding.

## Stage 1 result (2026-10-21)

- Command: `.\vendor\BlackBoxReborn\gradlew.bat -p tools/lua-regression stage1Test --no-daemon`.
- Final JUnit results: 29 tests, 0 failures, 0 errors, 0 skipped (seven classes).
- Preserved reports: `work/lua-acceptance-20261021/stage1/junit/`.
- New tests run the actual recovered entry, monitor, game worker and HUD worker
  with the production instruction gate/control. They check progress, freeze it
  during pause, resume it, and join every thread after stop. A second case stops
  the recovered runtime while paused.
- `LuaSourceCompatTest` was previously absent from the Lua runner and is now
  included. Stage 1 is an isolated Gradle task, not a dependency on the full suite.
- Host APIs remain controlled callbacks in this phase. Android service commands,
  accessibility capture and real game actions are not covered by this result.
