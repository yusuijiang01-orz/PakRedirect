#!/usr/bin/env python3
"""Port the known RYLUX signature-check bypass from the working 587 build to 610.

The 610 native initSystemInfo() function rejects the RYLUX signing certificate
and calls exit() from JNI_OnLoad. The shipped 587 RYLUX APK has an immediate
return at the same function entry. This script applies the architecture-native
return instruction to each 610 libcocos2dcpp.so in the rebuilt, unsigned APK.
"""

from __future__ import annotations

import argparse
import hashlib
import os
import struct
import tempfile
import zipfile
from pathlib import Path


SYMBOL = b"_Z14initSystemInfoP7_JNIEnv"
EXPECTED_ABIS = {"arm64-v8a", "armeabi-v7a", "x86"}
PATCHES = {
    183: ("arm64-v8a", bytes.fromhex("f50f1df8"), bytes.fromhex("c0035fd6")),
    40: ("armeabi-v7a", bytes.fromhex("f0b5"), bytes.fromhex("7047")),
    3: ("x86", bytes.fromhex("5589e553"), bytes.fromhex("c3")),
}


def read_elf_layout(data: bytes) -> tuple[int, list[tuple[int, int, int]]]:
    if len(data) < 52 or data[:4] != b"\x7fELF":
        raise ValueError("Not an ELF shared library")
    elf_class, encoding = data[4], data[5]
    if encoding != 1:
        raise ValueError("Only little-endian Android ELF libraries are supported")
    if elf_class == 2:
        machine = struct.unpack_from("<H", data, 18)[0]
        phoff = struct.unpack_from("<Q", data, 32)[0]
        shoff = struct.unpack_from("<Q", data, 40)[0]
        phentsize, phnum = struct.unpack_from("<HH", data, 54)
        shentsize, shnum = struct.unpack_from("<HH", data, 58)
        if shnum == 0 or shnum == 0xFFFF or phnum == 0xFFFF:
            raise ValueError("Extended ELF section/program numbering is unsupported")
        sections = []
        for i in range(shnum):
            off = shoff + i * shentsize
            name, stype, flags, addr, fileoff, size, link, info, align, entsize = struct.unpack_from("<IIQQQQIIQQ", data, off)
            sections.append((stype, fileoff, size, link, entsize))
        segments = []
        for i in range(phnum):
            off = phoff + i * phentsize
            ptype, flags, fileoff, vaddr, paddr, filesz, memsz, align = struct.unpack_from("<IIQQQQQQ", data, off)
            if ptype == 1:
                segments.append((vaddr, fileoff, filesz))
    elif elf_class == 1:
        machine = struct.unpack_from("<H", data, 18)[0]
        phoff = struct.unpack_from("<I", data, 28)[0]
        shoff = struct.unpack_from("<I", data, 32)[0]
        phentsize, phnum = struct.unpack_from("<HH", data, 42)
        shentsize, shnum = struct.unpack_from("<HH", data, 46)
        if shnum == 0 or shnum == 0xFFFF or phnum == 0xFFFF:
            raise ValueError("Extended ELF section/program numbering is unsupported")
        sections = []
        for i in range(shnum):
            off = shoff + i * shentsize
            name, stype, flags, addr, fileoff, size, link, info, align, entsize = struct.unpack_from("<IIIIIIIIII", data, off)
            sections.append((stype, fileoff, size, link, entsize))
        segments = []
        for i in range(phnum):
            off = phoff + i * phentsize
            ptype, fileoff, vaddr, paddr, filesz, memsz, flags, align = struct.unpack_from("<IIIIIIII", data, off)
            if ptype == 1:
                segments.append((vaddr, fileoff, filesz))
    else:
        raise ValueError(f"Unsupported ELF class {elf_class}")

    found: set[int] = set()
    for stype, symoff, symsize, link, entsize in sections:
        if stype not in (2, 11):  # SHT_SYMTAB or SHT_DYNSYM
            continue
        if link >= len(sections):
            raise ValueError("ELF symbol table has an invalid string table link")
        _, stroff, strsize, _, _ = sections[link]
        strings = data[stroff : stroff + strsize]
        entry_size = entsize or (24 if elf_class == 2 else 16)
        for off in range(symoff, symoff + symsize, entry_size):
            if elf_class == 2:
                nameoff, info, other, shndx, value, size = struct.unpack_from("<IBBHQQ", data, off)
            else:
                nameoff, value, size, info, other, shndx = struct.unpack_from("<IIIBBH", data, off)
            if nameoff >= len(strings):
                continue
            end = strings.find(b"\0", nameoff)
            if end >= 0 and strings[nameoff:end] == SYMBOL and value:
                found.add(value)

    if len(found) != 1:
        raise ValueError(f"Expected one initSystemInfo symbol value; found {sorted(hex(x) for x in found)}")
    symbol_value = found.pop()
    va = symbol_value & ~1  # ARM Thumb function symbols have the low bit set.
    for segment_va, segment_off, segment_size in segments:
        if segment_va <= va < segment_va + segment_size:
            return machine, [(symbol_value, segment_off + va - segment_va, segment_size - (va - segment_va))]
    raise ValueError(f"initSystemInfo address {va:#x} is not backed by a PT_LOAD file range")


def patch_library(path: str, original: bytes) -> tuple[bytes, str]:
    machine, locations = read_elf_layout(original)
    if machine not in PATCHES:
        raise ValueError(f"Unsupported native architecture {machine} in {path}")
    abi, expected, replacement = PATCHES[machine]
    symbol_value, file_offset, remaining = locations[0]
    if remaining < len(expected):
        raise ValueError(f"initSystemInfo entry is truncated in {path}")
    actual = original[file_offset : file_offset + len(expected)]
    if original[file_offset : file_offset + len(replacement)] == replacement:
        return original, f"{abi}: already patched at {symbol_value:#x}"
    if actual != expected:
        raise ValueError(
            f"Unexpected {abi} initSystemInfo prologue at {symbol_value:#x}: "
            f"expected {expected.hex()}, found {actual.hex()}"
        )
    patched = bytearray(original)
    patched[file_offset : file_offset + len(replacement)] = replacement
    # Confirm the entry now decodes to an immediate return instruction.
    if patched[file_offset : file_offset + len(replacement)] != replacement:
        raise AssertionError(f"Return patch read-back failed for {path}")
    return bytes(patched), f"{abi}: patched {symbol_value:#x} (file {file_offset:#x})"


def patch_apk(source: Path, output: Path) -> None:
    with zipfile.ZipFile(source, "r") as apk:
        infos = apk.infolist()
        names = [info.filename for info in infos]
        if len(names) != len(set(names)):
            raise ValueError("Input APK has duplicate ZIP entry names")
        lib_paths = {
            name.split("/")[1]: name
            for name in names
            if name.startswith("lib/") and name.endswith("/libcocos2dcpp.so")
        }
        if set(lib_paths) != EXPECTED_ABIS:
            raise ValueError(f"Expected ABIs {sorted(EXPECTED_ABIS)}, found {sorted(lib_paths)}")

        replacements: dict[str, bytes] = {}
        for abi in sorted(EXPECTED_ABIS):
            entry = lib_paths[abi]
            before = apk.read(entry)
            after, note = patch_library(entry, before)
            replacements[entry] = after
            print(f"{note}; SHA-256 {hashlib.sha256(before).hexdigest()} -> {hashlib.sha256(after).hexdigest()}")

        output.parent.mkdir(parents=True, exist_ok=True)
        fd, tmp_name = tempfile.mkstemp(prefix=f".{output.name}.", suffix=".tmp", dir=output.parent)
        os.close(fd)
        try:
            with zipfile.ZipFile(tmp_name, "w") as result:
                for info in infos:
                    result.writestr(info, replacements.get(info.filename, apk.read(info.filename)))
            with zipfile.ZipFile(tmp_name, "r") as check:
                for entry in replacements:
                    _, note = patch_library(entry, check.read(entry))
                    if "already patched" not in note:
                        raise ValueError(f"Signature guard return was not verified in {entry}")
            os.replace(tmp_name, output)
        except BaseException:
            try:
                os.unlink(tmp_name)
            except FileNotFoundError:
                pass
            raise


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input_apk", type=Path)
    parser.add_argument("output_apk", type=Path)
    args = parser.parse_args()
    if not args.input_apk.is_file():
        parser.error(f"input APK not found: {args.input_apk}")
    patch_apk(args.input_apk, args.output_apk)
    print(f"Patched signer guard in all native ABIs: {args.output_apk}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
