# RYLUX Live Lua Bundle

The APK checks `manifest.json` at script startup. The manifest selects one immutable bundle under `versions/<version>/`.

- Add every new Lua bundle under a new version directory; do not edit an existing version directory.
- Update `manifest.json` only after the whole bundle is uploaded and its sizes and Git blob SHA-1 values are verified.
- Roll back by changing the manifest to point at a previously published version. The APK retains its last active bundle as a fallback.
- The versioned paths are consumed by the RYLUX app and do not trigger an APK build workflow.
