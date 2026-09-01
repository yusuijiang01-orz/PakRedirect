# RYLUX 2.3 Protected Localization Content

RYLUX 2.3 delivers localization as authenticated encrypted content instead of public plaintext PAK downloads.

## Security model

- `settings.pak`, `ui.pak`, and `updatefs.pak` are encrypted offline with AES-256-GCM.
- Encryption uses independent authenticated chunks so the localhost server can decrypt HTTP Range requests without writing a plaintext PAK into RYLUX storage.
- The protected manifest is authenticated with HMAC-SHA256 using the same 256-bit content key.
- The AES content key is **not** embedded in the APK and is **not** stored in GitHub.
- Every RYLUX installation creates a non-exportable RSA key in Android Keystore.
- After VIP/trial authorization, the VPS wraps the AES key to that device RSA public key using RSA-OAEP (SHA-256, MGF1-SHA1 for Android compatibility).
- RYLUX stores only encrypted `.rpe` files plus the device-wrapped AES key. The target game receives decrypted bytes only through `127.0.0.1:18480` and writes them with its own UID.
- Official-resource `.rmp` mirror packs stay separate and are not encrypted by this system.

This raises extraction cost substantially, but client-side content can never be made impossible to recover on a rooted/hooked/instrumented device.

## Delivery architecture

Large encrypted files are hosted by GitHub, not by the VPS.

```text
RYLUX login
  -> VPS verifies account / trial / VIP
  -> VPS downloads the tiny public protected manifest from GitHub
  -> VPS verifies manifest HMAC using the private content key
  -> VPS wraps the AES key to the current Android Keystore RSA public key
  <- authenticated manifest + device-wrapped AES key

RYLUX content download
  -> GitHub raw content
       linkspak.txt
       settings.pak.rpe
       ui.pak.rpe
       updatefs.pak.rpe
  -> verify signed manifest metadata and encrypted SHA-256
  -> decrypt every AES-GCM chunk once for plaintext SHA-256 validation
  -> keep only encrypted RPE files at rest
  -> localhost Range requests decrypt on demand
```

The VPS is therefore the **authorization/control plane**. GitHub is the **encrypted-content data plane**. Large RPE payload bytes do not need to be stored on or streamed through the VPS.

For compatibility with the first RYLUX 2.3 client implementation, `/api/v1/content/{module}/files/{name}` remains available as a small HTTP redirect to GitHub. Android then downloads the response body from GitHub. This compatibility endpoint does not protect the ciphertext itself; possession of the wrapped AES key remains membership-gated by the manifest authorization endpoint.

## Offline encryptor output

Use `tools/rylux-patch-encryptor.py` or the standalone Windows bundle.

Input directory must contain:

- `settings.pak`
- `ui.pak`
- `updatefs.pak`

The tool also needs the matching `linkspak.txt` and a local `.key` file.

The deployment output contains exactly:

```text
manifest.json
linkspak.txt
settings.pak.rpe
ui.pak.rpe
updatefs.pak.rpe
```

The `.key` file is deliberately outside that directory. Never upload it to GitHub, never put it in the APK, and never place it under a web-served directory.

The tool automatically assigns new monotonic 32-bit-safe PAK revisions and rewrites only the deployment copy of `linkspak.txt`.

## GitHub layout

Upload the five deployment files to:

```text
PakRedirect/pak/
```

The default public URLs are:

```text
https://raw.githubusercontent.com/yusuijiang01-orz/PakRedirect/main/pak/manifest.json
https://raw.githubusercontent.com/yusuijiang01-orz/PakRedirect/main/pak/linkspak.txt
https://raw.githubusercontent.com/yusuijiang01-orz/PakRedirect/main/pak/settings.pak.rpe
https://raw.githubusercontent.com/yusuijiang01-orz/PakRedirect/main/pak/ui.pak.rpe
https://raw.githubusercontent.com/yusuijiang01-orz/PakRedirect/main/pak/updatefs.pak.rpe
```

The GitHub Actions workflow `pak-manifest.yml` is validation-only for protected content. It must **not** regenerate or rewrite `manifest.json` or `linkspak.txt`, because those two files are produced together by the encryption tool and are authenticated as one package.

The validation workflow checks the protected manifest shape, required file set, RPE headers, byte sizes and SHA-256 metadata without needing the secret content key. HMAC/GCM verification remains on the VPS/client where the key is available.

## VPS private key configuration

The VPS does **not** need a protected-content file directory.

The backend reads the AES content key from:

```text
/etc/pakredirect-license/content.env
```

with exactly one line:

```text
RYLUX_SG_LOCALIZATION_KEY_B64=<base64 content key>
```

Recommended permissions:

```bash
sudo install -d -m 0750 -o root -g paklicense /etc/pakredirect-license
sudo chmod 0640 /etc/pakredirect-license/content.env
sudo chown root:paklicense /etc/pakredirect-license/content.env
```

The systemd unit loads that file with `EnvironmentFile=`. The key is never returned in plaintext by the API.

The default service configuration uses:

```text
RYLUX_PROTECTED_MANIFEST_URL=https://raw.githubusercontent.com/yusuijiang01-orz/PakRedirect/main/pak/manifest.json
RYLUX_PROTECTED_DOWNLOAD_BASE_URL=https://raw.githubusercontent.com/yusuijiang01-orz/PakRedirect/main/pak/
```

## API flow

```text
POST /api/v1/content/sg_localization/manifest
  Authorization: Bearer <session>
  device_public_key: <Android Keystore RSA public key>

VPS:
  1. verifies account membership
  2. downloads manifest.json from GitHub
  3. verifies key_id + HMAC-SHA256 with the private AES key
  4. wraps the AES key to the submitted device RSA public key

Response:
  authenticated payload_b64
  manifest_hmac
  key_id
  wrapped_key
  version
  download_base_url
```

The encrypted `.rpe` files are public ciphertext. Authorization protects the **decryption capability**, not the ability to download ciphertext.

If the authorization service is temporarily unreachable and a previously verified protected set is already installed, RYLUX can continue using the installed encrypted set. A fresh installation requires the authorization service at least once.

## Update procedure

For each new localization release:

1. Keep using the same production `.key` unless intentionally rotating keys.
2. Run the encryptor against the new three plaintext PAK files and matching source `linkspak.txt`.
3. Let the tool complete its full verification.
4. Upload the newly generated five deployment files to `pak/` in one coherent update.
5. Confirm the GitHub protected-content validation workflow passes.
6. No RPE upload to the VPS is required.
7. No VPS restart is required for ordinary content updates when the AES key is unchanged.

If the AES content key is intentionally rotated, update `/etc/pakredirect-license/content.env` to the new key and restart `pakredirect-license` after the new GitHub package has been uploaded.

## Migration note

Historical plaintext PAK versions that were previously committed to the public GitHub repository remain obtainable from Git history. Removing them from the current branch does not erase old Git objects. A full history rewrite is a separate and disruptive operation.
