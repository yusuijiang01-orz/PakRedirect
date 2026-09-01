#!/usr/bin/env python3
"""Build a verified RYLUX mirror pack from official PAK files.

Example (Windows):
  py tools\build-rylux-mirror.py --source D:\RYLUX-Mirror-Source --output D:\RYLUX-Official-v1.ryluxmirror

The source directory may contain any subset of the allowed official PAKs. The
script validates file sizes/revisions against pak/linkspak.txt, calculates
SHA-256, writes mirror.json, and stores PAKs without compression for fast local
import.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import zipfile
from pathlib import Path

ALLOWED = (
    "resource.pak",
    "spr.pak",
    "script.pak",
    "fs2008.pak",
    "maps.pak",
    "update.pak",
    "blaze.pak",
)


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        while True:
            chunk = f.read(1024 * 1024)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()


def parse_linkspak(path: Path) -> dict[str, tuple[int, int]]:
    result: dict[str, tuple[int, int]] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        fields = raw.split(",")
        if len(fields) < 6:
            continue
        name = fields[2].strip().lower()
        if name not in ALLOWED:
            continue
        try:
            size = int(fields[3].strip())
            revision = int(fields[5].strip())
        except ValueError:
            continue
        if size > 0 and revision > 0:
            result[name] = (size, revision)
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description="Build a RYLUX official-resource mirror pack")
    parser.add_argument("--source", required=True, type=Path, help="Directory containing official PAK files")
    parser.add_argument("--output", required=True, type=Path, help="Output .ryluxmirror/.zip path")
    parser.add_argument("--name", default="RYLUX 官方资源镜像", help="Pack display name")
    parser.add_argument(
        "--linkspak",
        type=Path,
        default=Path(__file__).resolve().parents[1] / "pak" / "linkspak.txt",
        help="Current linkspak.txt used to validate sizes and revisions",
    )
    args = parser.parse_args()

    source = args.source.resolve()
    output = args.output.resolve()
    linkspak = args.linkspak.resolve()
    if not source.is_dir():
        parser.error(f"source directory not found: {source}")
    if not linkspak.is_file():
        parser.error(f"linkspak.txt not found: {linkspak}")

    official = parse_linkspak(linkspak)
    files: list[dict[str, object]] = []
    selected: list[Path] = []

    for name in ALLOWED:
        path = source / name
        if not path.is_file():
            continue
        if name not in official:
            raise SystemExit(f"{name}: not found in current linkspak.txt")
        expected_size, revision = official[name]
        actual_size = path.stat().st_size
        if actual_size != expected_size:
            raise SystemExit(
                f"{name}: size mismatch, expected {expected_size}, got {actual_size}. "
                "Re-download the official file or use the matching linkspak.txt."
            )
        print(f"Hashing {name} ({actual_size:,} bytes)...")
        files.append(
            {
                "name": name,
                "size": actual_size,
                "sha256": sha256(path),
                "revision": revision,
            }
        )
        selected.append(path)

    if not files:
        raise SystemExit("No allowed PAK files found in source directory")

    meta = {
        "schema": 1,
        "module": "sg_localization",
        "name": args.name,
        "files": files,
    }

    output.parent.mkdir(parents=True, exist_ok=True)
    if output.exists():
        output.unlink()

    print(f"Writing {output} with ZIP Store (no compression)...")
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED, allowZip64=True) as zf:
        zf.writestr("mirror.json", json.dumps(meta, ensure_ascii=False, indent=2).encode("utf-8"))
        for path in selected:
            print(f"Adding {path.name}...")
            zf.write(path, arcname=path.name, compress_type=zipfile.ZIP_STORED)

    print(f"Done: {output}")
    print(f"Files: {len(files)}")
    print(f"PAK bytes: {sum(int(item['size']) for item in files):,}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
