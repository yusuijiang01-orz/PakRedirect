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

## Package format

The package is a ZIP/Zip64 archive, preferably stored without compression. All files must be at the archive root. `mirror.json` is required:

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
      "revision": 1
    }
  ]
}
```

RYLUX verifies file name, size and SHA-256 before committing the imported mirror.

At game launch, a mirror PAK is redirected to `127.0.0.1:18480` only when its `revision` still matches the current `linkspak.txt`. If the official revision changes, RYLUX leaves that item on the official CDN instead of serving a stale mirror.

## Build helper

From the repository root:

```bash
python tools/build-rylux-mirror.py --source /path/to/official-paks --output RYLUX-Official-v1.ryluxmirror
```

The helper validates the downloaded files against the current `pak/linkspak.txt`, calculates SHA-256, writes `mirror.json`, and uses ZIP Store for faster local import.
