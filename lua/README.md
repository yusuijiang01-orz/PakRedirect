# RYLUX remote Lua bundles

`manifest.json` and `bundle.zip` are fetched from the `lua/` directory on the `main` branch of `yusuijiang01-orz/PakRedirect` when a user starts a script in RYLUX.

## Publish a script version

1. Edit the Lua modules or image templates under `app/src/main/assets/script_modules/`.
2. Run `python tools/package-lua-release.py <new-version>` using a new immutable version, for example `2026.11.08.2`.
3. Review both generated files, then push `lua/manifest.json` and `lua/bundle.zip` together to GitHub `main`.

RYLUX checks the manifest and the bundle SHA-256, extracts into an app-private staging directory, then switches versions atomically. It keeps the previous verified bundle for rollback. A failed or unavailable update keeps the last good bundle, or falls back to APK assets. The package step blanks account and password assignments before making the public bundle.

Remote Lua updates do not update the APK or native libraries. The APK must include `LuaScriptUpdateManager` before it can consume these files.
