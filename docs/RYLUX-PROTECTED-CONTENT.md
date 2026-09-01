# RYLUX 2.3 Protected Localization Content

RYLUX 2.3 changes the localization patch path from public plaintext PAK downloads to authenticated encrypted content.

## Security model

- `settings.pak`, `ui.pak`, and `updatefs.pak` are encrypted offline with AES-256-GCM.
- Encryption uses independent authenticated chunks so the localhost server can decrypt HTTP Range requests without writing a plaintext PAK into RYLUX storage.
- The protected manifest is authenticated with HMAC-SHA256 using the same 256-bit content key.
- The AES content key is **not** embedded in the APK.
- Every RYLUX installation creates a non-exportable RSA key in Android Keystore.
- After VIP/trial authorization, the server wraps the AES key to that device RSA public key using RSA-OAEP (SHA-256, MGF1-SHA1 for Android compatibility).
- RYLUX stores only encrypted `.rpe` files plus the device-wrapped AES key. The target game receives decrypted bytes only through `127.0.0.1:18480` and writes them with its own UID.
- Official-resource `.rmp` mirror packs stay separate and are not encrypted by this system.

This raises the extraction cost substantially, but it cannot make client-side content impossible to recover on a rooted/hooked device.

## Offline encryptor output

Use `tools/rylux-patch-encryptor.py` or the standalone Windows bundle.

Input directory must contain:

- `settings.pak`
- `ui.pak`
- `updatefs.pak`

The tool also needs the matching `linkspak.txt` and a local `.key` file.

The deployment output contains only:

```text
manifest.json
linkspak.txt
settings.pak.rpe
ui.pak.rpe
updatefs.pak.rpe
```

The `.key` file is deliberately outside that directory. Never upload it to GitHub or place it under a web-served directory.

The tool automatically assigns new monotonic 32-bit-safe PAK revisions and rewrites only the deployment copy of `linkspak.txt`.

## VPS layout

Deploy encrypted output to:

```text
/opt/pakredirect-license/protected-content/sg_localization/
```

The backend reads the AES key from:

```text
/etc/pakredirect-license/content.env
```

with exactly one line:

```text
RYLUX_SG_LOCALIZATION_KEY_B64=<base64 content key>
```

Recommended permissions:

```bash
sudo install -d -m 0750 -o paklicense -g paklicense /opt/pakredirect-license/protected-content/sg_localization
sudo install -d -m 0750 -o root -g paklicense /etc/pakredirect-license
sudo chmod 0640 /etc/pakredirect-license/content.env
sudo chown root:paklicense /etc/pakredirect-license/content.env
```

The systemd unit loads that file with `EnvironmentFile=`. The key is never returned as plaintext by the API.

## API flow

```text
RYLUX login
  -> module authorize
  -> POST /api/v1/content/sg_localization/manifest
       Authorization: Bearer <session>
       device_public_key: <Android Keystore RSA public key>
  <- authenticated manifest + device-wrapped AES key
  -> GET /api/v1/content/sg_localization/files/*.rpe
       Authorization: Bearer <session>
  -> verify encrypted SHA-256
  -> decrypt every AES-GCM chunk once for full plaintext SHA-256 validation
  -> keep only encrypted RPE files at rest
  -> localhost Range requests decrypt on demand
```

If the protected content service is temporarily unreachable and a previously verified protected set is already installed, RYLUX can continue using the installed encrypted set. A fresh installation requires the protected content service at least once.

## Migration note

Historical plaintext PAK versions that were already committed to the public GitHub repository remain obtainable from Git history even after the new system is enabled. RYLUX 2.3 no longer embeds plaintext PAKs in the APK and no longer uses the public GitHub PAK updater during launch. After the 2.3 rollout is confirmed, the tracked plaintext PAKs can be removed from the current branch; removing them from Git history requires a separate history rewrite and force-push decision.
