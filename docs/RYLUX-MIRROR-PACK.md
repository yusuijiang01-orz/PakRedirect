# RYLUX Mirror Pack

RYLUX mirror packs are optional local acceleration packages for **unmodified official game PAKs**. They do not replace the localization PAK update pipeline.

## Allowed files

A mirror pack may contain any subset of:

- `resource.pak`
- `spr.pak`
- `script.pak`
- `fs2008.pak`
- `maps.pak`
- `update.pak`
- `blaze.pak`

`settings.pak`, `ui.pak`, and `updatefs.pak` are intentionally forbidden because RYLUX manages those localization resources separately.

## Direct-mount design

The mirror is a single `.ryluxmirror` file designed for random access. RYLUX does **not** extract a second multi-GB copy into its private storage. After the user chooses the file with Android's document picker, RYLUX persists read access to that file, verifies all listed PAKs once, and later serves byte ranges directly from the selected mirror through `127.0.0.1:18480`.

The target game still writes the final PAK into its own sandbox with its own UID, so this requires no root and no cross-app private-directory write.

For reliable random access, keep the mirror package on local device storage such as the Downloads folder. Do not move or delete the selected file afterward; otherwise select it again in RYLUX.

## Container format

Binary layout:

```text
8 bytes   ASCII magic: RYLUXM01
4 bytes   big-endian unsigned JSON header length
N bytes   UTF-8 JSON header
...       raw PAK payloads concatenated without compression
```

Offsets in the JSON header are relative to the first byte after the JSON header:

```json
{
  "schema": 1,
  "module": "sg_localization",
  "name": "RYLUX 官方资源镜像",
  "files": [
    {
      "name": "resource.pak",
      "size": 50178358,
      "sha256": "<64 hex characters>",
      "revision": 1,
      "offset": 0
    }
  ]
}
```

RYLUX validates the container structure, allowed file names, byte ranges, file sizes and SHA-256 values before remembering the mirror.

At game launch, a mirror PAK is redirected to `127.0.0.1:18480` only when its `revision` still matches the current `linkspak.txt`. If the official revision changes, RYLUX leaves that item on the official CDN instead of serving a stale mirror.

## Build helper

From the repository root:

```bash
python tools/build-rylux-mirror.py --source /path/to/official-paks --output RYLUX-Official-v1.ryluxmirror
```

Windows example:

```bat
py tools\build-rylux-mirror.py --source D:\RYLUX-Mirror-Source --output D:\RYLUX-Official-v1.ryluxmirror
```

The helper validates each downloaded official PAK against the current `pak/linkspak.txt`, calculates SHA-256, records the current revision and byte offset, and appends the PAK bytes verbatim with no compression.
