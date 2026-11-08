#!/usr/bin/env python3
"""Package the LuaJ script modules as a versioned RYLUX remote bundle."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import tempfile
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app" / "src" / "main" / "assets" / "script_modules"
OUTPUT = ROOT / "lua"
EXTENSIONS = {".lua", ".png", ".jpg", ".jpeg", ".webp", ".bmp"}
VERSION_PATTERN = re.compile(r"^[A-Za-z0-9._-]{1,64}$")


def package(version: str) -> tuple[Path, Path, int, str]:
    if not VERSION_PATTERN.fullmatch(version):
        raise ValueError("version must contain only letters, digits, dot, underscore, or hyphen")
    if not SOURCE.is_dir():
        raise FileNotFoundError(f"Lua asset directory not found: {SOURCE}")
    OUTPUT.mkdir(parents=True, exist_ok=True)

    bundle_path = OUTPUT / "bundle.zip"
    manifest_path = OUTPUT / "manifest.json"
    entries = sorted(path for path in SOURCE.rglob("*") if path.is_file() and path.suffix.lower() in EXTENSIONS)
    if not entries:
        raise ValueError("no Lua or image assets found")
    if not any(path.relative_to(SOURCE).as_posix() == "脚本/封神榜.lua" for path in entries):
        raise ValueError("main Lua script is missing")

    with tempfile.NamedTemporaryFile(dir=OUTPUT, suffix=".zip.tmp", delete=False) as temp:
        temporary_bundle = Path(temp.name)
    try:
        with zipfile.ZipFile(temporary_bundle, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=7) as archive:
            for source_path in entries:
                relative = source_path.relative_to(SOURCE).as_posix()
                payload = source_path.read_bytes()
                if relative.endswith(".lua"):
                    source_text = payload.decode("utf-8")
                    source_text = re.sub(r'(_ENV\["账号"\]\s*=\s*)"[^"\r\n]*"', r'\1""', source_text)
                    source_text = re.sub(r'(_ENV\["密码"\]\s*=\s*)"[^"\r\n]*"', r'\1""', source_text)
                    payload = source_text.encode("utf-8")
                archive.writestr(relative, payload)
        digest = hashlib.sha256(temporary_bundle.read_bytes()).hexdigest()
        size = temporary_bundle.stat().st_size
        temporary_bundle.replace(bundle_path)
    finally:
        temporary_bundle.unlink(missing_ok=True)

    manifest = {
        "schema": 1,
        "version": version,
        "bundle": "bundle.zip",
        "size": size,
        "sha256": digest,
    }
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=OUTPUT, suffix=".json.tmp", delete=False) as temp:
        json.dump(manifest, temp, ensure_ascii=False, indent=2)
        temp.write("\n")
        temporary_manifest = Path(temp.name)
    temporary_manifest.replace(manifest_path)
    return bundle_path, manifest_path, size, digest


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("version", help="immutable Lua bundle version, for example 2026.11.08.2")
    args = parser.parse_args()
    bundle, manifest, size, digest = package(args.version)
    print(f"Created {bundle.relative_to(ROOT)} ({size} bytes)")
    print(f"Created {manifest.relative_to(ROOT)}")
    print(f"SHA-256 {digest}")


if __name__ == "__main__":
    main()
