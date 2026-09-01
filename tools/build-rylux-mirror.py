#!/usr/bin/env python3
"""Build a direct-mount RYLUX mirror package from official PAK files.

Example (Windows):
  py tools\build-rylux-mirror.py --source D:\RYLUX-Mirror-Source --output D:\RYLUX-Official-v1.rmp

The source directory may contain any subset of the allowed official PAKs. The
script validates file sizes/revisions against pak/linkspak.txt, calculates
SHA-256, then writes a seekable RYLUXM01 container. PAK payloads are appended
verbatim with no compression, so Android can serve byte ranges directly from
this one file without extracting another multi-GB copy.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import struct
import sys
from pathlib import Path

MAGIC = b"RYLUXM01"
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


def copy_file(source: Path, out, total: int, done_before: int) -> None:
    copied = 0
    with source.open("rb") as f:
        while True:
            chunk = f.read(1024 * 1024)
            if not chunk:
                break
            out.write(chunk)
            copied += len(chunk)
            done = done_before + copied
            percent = int(done * 100 / total) if total else 100
            print(
                f"\rWriting {source.name}: {copied / 1024 / 1024:.1f} MB "
                f"({percent}%)",
                end="",
                flush=True,
            )
    print()


def main() -> int:
    parser = argparse.ArgumentParser(description="Build a RYLUX official-resource mirror package")
    parser.add_argument("--source", required=True, type=Path, help="Directory containing official PAK files")
    parser.add_argument("--output", required=True, type=Path, help="Output .rmp path")
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
    if output.suffix.lower() != ".rmp":
        parser.error("output file must use the .rmp extension")

    official = parse_linkspak(linkspak)
    files: list[dict[str, object]] = []
    selected: list[Path] = []
    offset = 0

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
                "offset": offset,
            }
        )
        selected.append(path)
        offset += actual_size

    if not files:
        raise SystemExit("No allowed PAK files found in source directory")

    meta = {
        "schema": 1,
        "module": "sg_localization",
        "name": args.name,
        "files": files,
    }
    header = json.dumps(meta, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    if len(header) > 512 * 1024:
        raise SystemExit("mirror header is unexpectedly large")

    output.parent.mkdir(parents=True, exist_ok=True)
    temp = output.with_name(output.name + ".tmp")
    if temp.exists():
        temp.unlink()

    total_payload = sum(int(item["size"]) for item in files)
    print(f"Writing direct-mount mirror package: {output}")
    try:
        with temp.open("wb") as out:
            out.write(MAGIC)
            out.write(struct.pack(">I", len(header)))
            out.write(header)
            done = 0
            for path, item in zip(selected, files):
                copy_file(path, out, total_payload, done)
                done += int(item["size"])
            out.flush()
        temp.replace(output)
    except BaseException:
        try:
            temp.unlink()
        except FileNotFoundError:
            pass
        raise

    expected_size = len(MAGIC) + 4 + len(header) + total_payload
    actual_size = output.stat().st_size
    if actual_size != expected_size:
        output.unlink(missing_ok=True)
        raise SystemExit(f"output size mismatch: expected {expected_size}, got {actual_size}")

    print(f"Done: {output}")
    print(f"Files: {len(files)}")
    print(f"PAK bytes: {total_payload:,}")
    print(f"Container bytes: {actual_size:,}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
