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
| 2. Android sandbox and host | Launch the localized game through the sandbox. Prove foreground recognition and actual Android host screenshot success, foreground rejection outside the game, and UI pause/stop command delivery. ADB screenshots alone cannot prove the app's capture API. | Passed (current device/APK; see evidence below) |
| 3. Localized login | Actual Lua handles known terms/login/server/character pages, closes a recognized stalled login dialog after 10 seconds, retries, and reaches the game world. Stop before other gameplay tasks. Record screens, decisions and post-action states. | In progress |
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

## Stage 2 result (2026-10-21)

- Command: `.\vendor\BlackBoxReborn\gradlew.bat -p tools/lua-regression stage2Test --no-daemon`.
- JUnit: 37 tests, 0 failures, 0 errors, 0 skipped; reports saved under
  `work/lua-acceptance-20261021/stage2/junit/`.
- The live sandbox page had localization enabled (`01-sandbox.png`). Its launch
  button opened the localized terms page (`03-launch-observe.png`), with Android
  top `com.example.pakredirect/...ProxyActivity$P0` and focused guest window
  `com.tepaylink.tamgioiphantranhmobile/com.jxclient.release.jxclient`.
- Started the existing APK's real runtime service over root ADB, then paused it
  before accepting terms. The host thread trace (`04-paused-host-threads.txt`)
  is inside the startup OCR call, after the startup display-capture check.
  Logcat records ML Kit bitmap processing and `OCR process succeeded via
  visionkit pipeline` at 14:19:19.616. This establishes actual host capture
  returned a bitmap; it is not inferred solely from an ADB screenshot.
- Overlay Pause was clicked on-device (`06-ui-pause-screen.png`: PAUSED), and
  its Stop button terminated the paused runtime service; saved services dump
  `06-ui-stop-services.txt` contains no runtime ServiceRecord. Terms remained
  on screen throughout; login was not exercised in this stage.
- Negative check: with the launcher active but the virtual game still alive,
  the runtime rejected capture/input. `07-outside-game-diagnostic.xml` records
  `activeWindow=com.android.launcher3/Launcher3` and the background virtual top.
  The FAILED overlay in `07-outside-game.png` is the expected negative result,
  not an active-game foreground failure. The overlay was then closed.
- Evidence root: `work/lua-acceptance-20261021/stage2/`. Workbench-driven automatic
  game launch is exercised next as part of the full login entry path. No new
  APK or production code change was needed for this stage.

## Stage 3 attempts (still in progress; not accepted)

- The isolated startup/login fixture task passed 13 tests with no failures or
  skips after adding the observed `正在连接服务器` dialog to the same bounded
  timeout handler as `正在登录游戏`.
- The first live run (`stage3/live-01/`) accepted the real terms screen and
  entered server selection, then failed because full-frame RapidOCR exceeded
  the 12-second harness read timeout. Saved-frame benchmarking led to a 2/1
  ONNX thread configuration (three requests: 1.905, 8.269, 7.972 seconds).
- The next live run (`stage3/live-03/`) used the current Lua source, actual
  emulator screenshots and ADB touch. It accepted terms, selected the server,
  reached saved-account login and character selection, then timed out in two
  `正在登录游戏` dialogs. The Lua recognized and tapped the on-screen `返回`
  button after each 10-second wait, but the game returned to the login page
  instead of the world. The 3-minute harness deadline expired; stage 3 is not
  passed. Screens, OCR rows and action transcript are under
  `work/lua-acceptance-20261021/stage3/live-03/`.
- A separate single ADB tap from the account page reached character selection;
  a single ADB tap on that role's 确认 button returned to the account page after
  12 seconds. This confirms the screen controls were reached, but does not
  identify whether the game's login service, account/session, or client response
  caused the return. Android logcat contained no matching game login/network
  exception in the sampled window.
- A test APK is being prepared at the user's request so they can compare on
  their own session. This does not change the failed live acceptance result and
  is not the final APK.
