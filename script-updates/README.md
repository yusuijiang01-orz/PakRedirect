# RYLUX Lua updates

The client checks `manifest.json` when the user starts the script. It downloads only listed Lua modules, validates UTF-8, byte size, and SHA-256, stages the complete release, then switches the active version atomically. If the network or manifest is unavailable, the client keeps using its last verified Lua version or the Lua assets packaged in the APK.

## Release rules

- Use an immutable release directory: `releases/<version>/`.
- Increment `version` for every change. Never replace files under an already published version.
- List only top-level Lua modules from the runtime allowlist. Omitted modules continue using the APK-bundled version.
- Update `manifest.json` only after every release file is present and its byte count and SHA-256 have been checked.
- The client retains the active release, its previous release, and several recent cached versions.

## Rollback

To roll back, point `manifest.json` at the previous immutable release by restoring its version and file list. This is a normal Git commit; clients switch on their next script start. Do not delete old release directories. The APK contains a fallback copy if no verified remote version is available.
