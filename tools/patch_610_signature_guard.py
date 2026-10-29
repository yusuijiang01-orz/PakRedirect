#!/usr/bin/env python3
"""Port the required RYLUX localization hooks from the working 587 build to 610.

The 610 native initSystemInfo() function rejects the RYLUX signing certificate
and calls exit() from JNI_OnLoad. The shipped 587 RYLUX APK has an immediate
return at the same function entry. This script replaces that function in each
ABI with a runtime route selector: it probes the RYLUX localhost PAK server
during JNI_OnLoad, uses localhost only when the server is listening, and
otherwise leaves the official CDN linkspak URL intact.

Requires keystone-engine (pip install -r tools/requirements-patch-610.txt).
"""

from __future__ import annotations

import argparse
import hashlib
import os
import struct
import tempfile
import zipfile
from pathlib import Path

try:
    from capstone import Cs, CS_ARCH_ARM, CS_ARCH_X86, CS_MODE_32, CS_MODE_ARM, CS_MODE_THUMB, CS_MODE_LITTLE_ENDIAN
    from keystone import Ks, KS_ARCH_ARM, KS_ARCH_ARM64, KS_ARCH_X86, KS_MODE_32, KS_MODE_LITTLE_ENDIAN, KS_MODE_THUMB
except ImportError as exc:  # pragma: no cover - exercised by the CLI error below
    raise SystemExit(
        "Missing assembler/disassembler dependencies. Install them with: "
        "python -m pip install -r tools/requirements-patch-610.txt"
    ) from exc


SYMBOL = b"_Z14initSystemInfoP7_JNIEnv"
EXPECTED_ABIS = {"arm64-v8a", "armeabi-v7a", "x86"}
OFFICIAL_MANIFEST_URL = b"https://cdn.tamgioipt.vn/linkspak.txt"
LOCAL_MANIFEST_URL = b"http://127.0.0.1:18480/linkspak.txt"
PATCHES = {
    183: ("arm64-v8a", bytes.fromhex("f50f1df8"), bytes.fromhex("c0035fd6")),
    40: ("armeabi-v7a", bytes.fromhex("f0b5"), bytes.fromhex("7047")),
    3: ("x86", bytes.fromhex("5589e553"), bytes.fromhex("c3")),
}
SOCKADDR = bytes((2, 0, 0x48, 0x30, 127, 0, 0, 1, *([0] * 8)))
ROUTE_DATA = LOCAL_MANIFEST_URL + b"\0" * (len(OFFICIAL_MANIFEST_URL) - len(LOCAL_MANIFEST_URL))


def read_elf_layout(data: bytes) -> tuple[int, int, int, int, list[tuple[int, int, int, int]]]:
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
                segments.append((vaddr, fileoff, filesz, flags))
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
                segments.append((vaddr, fileoff, filesz, flags))
    else:
        raise ValueError(f"Unsupported ELF class {elf_class}")

    found: dict[int, int] = {}
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
                found[value] = size

    if len(found) != 1:
        raise ValueError(f"Expected one initSystemInfo symbol value; found {sorted(hex(x) for x in found)}")
    symbol_value, symbol_size = next(iter(found.items()))
    va = symbol_value & ~1  # ARM Thumb function symbols have the low bit set.
    for segment_va, segment_off, segment_size, _flags in segments:
        if segment_va <= va < segment_va + segment_size:
            return machine, symbol_value, segment_off + va - segment_va, symbol_size, segments
    raise ValueError(f"initSystemInfo address {va:#x} is not backed by a PT_LOAD file range")


def _byte_directive(data: bytes) -> str:
    return ".byte " + ",".join(f"0x{value:02x}" for value in data)


def _assemble_arm64(function_va: int, url_va: int) -> bytes:
    source = f"""
        stp x19, x20, [sp, #-32]!
        stp x21, x30, [sp, #16]
        mov x0, #2
        mov x1, #1
        mov x2, #0
        mov x8, #198
        svc #0
        cmp x0, #0
        b.lt done
        mov x19, x0
        adr x1, sockaddr
        mov x2, #16
        mov x8, #203
        svc #0
        cmp x0, #0
        b.lt close_fd
        adrp x20, {url_va & ~0xfff:#x}
        add x20, x20, #{url_va & 0xfff}
        and x0, x20, #0xfffffffffffff000
        mov x1, #4096
        mov x2, #3
        mov x8, #226
        svc #0
        cmp x0, #0
        b.lt close_fd
        adr x21, route_data
        mov x9, #{len(ROUTE_DATA)}
    copy_loop:
        ldrb w10, [x21], #1
        strb w10, [x20], #1
        subs x9, x9, #1
        b.ne copy_loop
        adrp x20, {url_va & ~0xfff:#x}
        add x20, x20, #{url_va & 0xfff}
        and x0, x20, #0xfffffffffffff000
        mov x1, #4096
        mov x2, #5
        mov x8, #226
        svc #0
    close_fd:
        mov x0, x19
        mov x8, #57
        svc #0
    done:
        ldp x21, x30, [sp, #16]
        ldp x19, x20, [sp], #32
        ret
    sockaddr:
        {_byte_directive(SOCKADDR)}
    route_data:
        {_byte_directive(ROUTE_DATA)}
        .align 2
    """
    encoding, _ = Ks(KS_ARCH_ARM64, KS_MODE_LITTLE_ENDIAN).asm(source, function_va)
    return bytes(encoding)


def _assemble_thumb(function_va: int, url_va: int, restore_protection: int) -> bytes:
    source = f"""
        push {{r4-r7, lr}}
        sub sp, #4
        movs r0, #2
        movs r1, #1
        movs r2, #0
        movw r7, #281
        svc #0
        cmp r0, #0
        blt done
        mov r6, r0
        adr r1, sockaddr
        movs r2, #16
        movw r7, #283
        svc #0
        cmp r0, #0
        blt close_fd
        mov r5, pc
        movw r3, #0xaaaa
        movt r3, #0xbbbb
        add r5, r3
        mov r0, r5
        lsrs r0, r0, #12
        lsls r0, r0, #12
        movw r1, #4096
        movs r2, #3
        movs r7, #125
        svc #0
        cmp r0, #0
        blt close_fd
        adr r4, route_data
        mov r5, pc
        movw r3, #0xcccc
        movt r3, #0xdddd
        add r5, r3
        movs r2, #{len(ROUTE_DATA)}
    copy_loop:
        ldrb r3, [r4]
        adds r4, #1
        strb r3, [r5]
        adds r5, #1
        subs r2, #1
        bne copy_loop
        mov r5, pc
        movw r3, #0xeeee
        movt r3, #0xffff
        add r5, r3
        mov r0, r5
        lsrs r0, r0, #12
        lsls r0, r0, #12
        movw r1, #4096
        movs r2, #{restore_protection}
        movs r7, #125
        svc #0
    close_fd:
        mov r0, r6
        movs r7, #6
        svc #0
    done:
        add sp, #4
        pop {{r4-r7, pc}}
    sockaddr:
        {_byte_directive(SOCKADDR)}
    route_data:
        {_byte_directive(ROUTE_DATA)}
        .align 2
    """
    encoding, _ = Ks(KS_ARCH_ARM, KS_MODE_THUMB | KS_MODE_LITTLE_ENDIAN).asm(source, function_va)
    code = bytearray(encoding)
    disasm = Cs(CS_ARCH_ARM, CS_MODE_THUMB | CS_MODE_LITTLE_ENDIAN)
    instructions = list(disasm.disasm(bytes(code), function_va))
    pc_anchors = [
        insn.address for insn in instructions
        if insn.mnemonic == "mov" and insn.op_str == "r5, pc"
    ]
    if len(pc_anchors) != 3:
        raise ValueError(f"Expected three Thumb PC anchors, found {len(pc_anchors)}")
    targets = [url_va & ~0xfff, url_va, url_va]
    pairs: list[list[int]] = []
    for index, insn in enumerate(instructions):
        if insn.mnemonic == "movw" and insn.op_str.startswith("r3, #"):
            pairs.append([index])
        elif insn.mnemonic == "movt" and insn.op_str.startswith("r3, #"):
            if not pairs or len(pairs[-1]) != 1:
                raise ValueError("Malformed Thumb MOVW/MOVT fixup pair")
            pairs[-1].append(index)
    if len(pairs) != 3 or any(len(pair) != 2 for pair in pairs):
        raise ValueError(f"Expected three Thumb MOVW/MOVT fixup pairs, found {pairs}")
    for anchor, target, pair in zip(pc_anchors, targets, pairs):
        pc_value = (anchor + 4) & ~3
        delta = (target - pc_value) & 0xffffffff
        for insn_index, immediate in zip(pair, (delta & 0xffff, delta >> 16)):
            insn = instructions[insn_index]
            replacement, _ = Ks(KS_ARCH_ARM, KS_MODE_THUMB | KS_MODE_LITTLE_ENDIAN).asm(
                f"{insn.mnemonic} r3, #{immediate}", insn.address
            )
            code[insn.address - function_va : insn.address - function_va + 4] = bytes(replacement)
    return bytes(code)


def _assemble_x86(function_va: int, url_va: int, restore_protection: int) -> bytes:
    markers = (0x11111111, 0x22222222, 0x33333333, 0x44444444, 0x55555555)
    source = f"""
        push ebx
        push esi
        push edi
        push ebp
        mov eax, 359
        mov ebx, 2
        mov ecx, 1
        xor edx, edx
        int 0x80
        test eax, eax
        js done
        mov ebp, eax
        call sockpc
    sockpc:
        pop edi
        add edi, {markers[0]:#x}
        mov eax, 362
        mov ebx, ebp
        mov ecx, edi
        mov edx, 16
        int 0x80
        test eax, eax
        js close_fd
        call urlpc1
    urlpc1:
        pop edi
        add edi, {markers[1]:#x}
        mov ebx, edi
        and ebx, 0xfffff000
        mov ecx, 4096
        mov edx, 3
        mov eax, 125
        int 0x80
        test eax, eax
        js close_fd
        call routepc
    routepc:
        pop esi
        add esi, {markers[2]:#x}
        call urlpc2
    urlpc2:
        pop edi
        add edi, {markers[3]:#x}
        mov ecx, {len(ROUTE_DATA)}
        rep movsb
        call urlpc3
    urlpc3:
        pop edi
        add edi, {markers[4]:#x}
        mov ebx, edi
        and ebx, 0xfffff000
        mov ecx, 4096
        mov edx, {restore_protection}
        mov eax, 125
        int 0x80
    close_fd:
        mov ebx, ebp
        mov eax, 6
        int 0x80
    done:
        pop ebp
        pop edi
        pop esi
        pop ebx
        ret
    sockaddr:
        {_byte_directive(SOCKADDR)}
    route_data:
        {_byte_directive(ROUTE_DATA)}
    """
    encoding, _ = Ks(KS_ARCH_X86, KS_MODE_32).asm(source, function_va)
    code = bytearray(encoding)
    disasm = Cs(CS_ARCH_X86, CS_MODE_32)
    calls = [insn for insn in disasm.disasm(bytes(code), function_va) if insn.mnemonic == "call"]
    if len(calls) != 5:
        raise ValueError(f"Expected five x86 position-independent anchors, found {len(calls)}")
    targets = [None, url_va & ~0xfff, None, url_va, url_va]
    # The first and third calls address inline sockaddr and route data. Resolve their
    # labels from the immediate delta placeholders, whose source is the following pop.
    # Their labels are found by the unique byte payloads appended after the function.
    sockaddr_offset = bytes(code).find(SOCKADDR)
    route_offset = bytes(code).find(ROUTE_DATA)
    if sockaddr_offset < 0 or route_offset < 0:
        raise ValueError("x86 inline routing data is missing")
    targets[0] = function_va + sockaddr_offset
    targets[2] = function_va + route_offset
    for call, marker in zip(calls, markers):
        marker_bytes = struct.pack("<I", marker)
        immediate_offset = bytes(code).find(marker_bytes)
        if immediate_offset < 0:
            raise ValueError(f"Missing x86 relative-address marker {marker:#x}")
        anchor_va = call.address + call.size
        target_va = targets[markers.index(marker)]
        delta = (target_va - anchor_va) & 0xffffffff
        struct.pack_into("<I", code, immediate_offset, delta)
    return bytes(code)


def build_selector(machine: int, function_va: int, symbol_size: int, url_va: int, restore_protection: int) -> bytes:
    if machine == 183:
        code = _assemble_arm64(function_va, url_va)
        nop = bytes.fromhex("1f2003d5")
    elif machine == 40:
        code = _assemble_thumb(function_va, url_va, restore_protection)
        nop = bytes.fromhex("00bf")
    elif machine == 3:
        code = _assemble_x86(function_va, url_va, restore_protection)
        nop = b"\x90"
    else:
        raise ValueError(f"Unsupported ELF machine {machine}")
    if len(code) > symbol_size:
        raise ValueError(f"Runtime route selector is {len(code)} bytes; initSystemInfo has only {symbol_size}")
    padding = symbol_size - len(code)
    if machine == 183 and padding % 4:
        raise ValueError("ARM64 function size is not instruction aligned")
    if machine == 40 and padding % 2:
        raise ValueError("Thumb function size is not instruction aligned")
    return code + nop * (padding // len(nop))


def _url_va_from_file_offset(segments: list[tuple[int, int, int, int]], file_offset: int) -> tuple[int, int]:
    for segment_va, segment_off, segment_size, flags in segments:
        if segment_off <= file_offset < segment_off + segment_size:
            protection = (1 if flags & 4 else 0) | (2 if flags & 2 else 0) | (4 if flags & 1 else 0)
            return segment_va + file_offset - segment_off, protection
    raise ValueError(f"linkspak URL file offset {file_offset:#x} is not in a PT_LOAD segment")


def patch_library(path: str, original: bytes) -> tuple[bytes, str]:
    machine, symbol_value, file_offset, symbol_size, segments = read_elf_layout(original)
    if machine not in PATCHES:
        raise ValueError(f"Unsupported native architecture {machine} in {path}")
    abi, expected, replacement = PATCHES[machine]
    function_va = symbol_value & ~1
    if symbol_size < len(expected):
        raise ValueError(f"initSystemInfo entry is truncated in {path}")
    actual = original[file_offset : file_offset + len(expected)]
    old_return_patch = original[file_offset : file_offset + len(replacement)] == replacement
    url_offsets = [
        offset
        for value in (OFFICIAL_MANIFEST_URL, LOCAL_MANIFEST_URL)
        for offset in [original.find(value)]
        if offset >= 0 and not file_offset <= offset < file_offset + symbol_size
    ]
    if len(url_offsets) != 1:
        raise ValueError(f"Expected exactly one official or legacy-local linkspak URL in {path}; found {url_offsets}")
    url_offset = url_offsets[0]
    if original[url_offset : url_offset + len(OFFICIAL_MANIFEST_URL)] not in (OFFICIAL_MANIFEST_URL, ROUTE_DATA):
        raise ValueError(f"linkspak URL allocation is not the expected size in {path}")
    if original[url_offset + len(OFFICIAL_MANIFEST_URL) : url_offset + len(OFFICIAL_MANIFEST_URL) + 1] != b"\0":
        raise ValueError(f"linkspak URL is not null-terminated in {path}")
    url_va, restore_protection = _url_va_from_file_offset(segments, url_offset)
    selector = build_selector(machine, function_va, symbol_size, url_va, restore_protection)
    current_function = original[file_offset : file_offset + symbol_size]
    already_selector = current_function == selector
    if not already_selector and not old_return_patch and actual != expected:
        raise ValueError(
            f"Unexpected {abi} initSystemInfo prologue at {symbol_value:#x}: "
            f"expected stock {expected.hex()}, legacy return {replacement.hex()}, "
            f"or the runtime selector"
        )
    patched = bytearray(original)
    patched[file_offset : file_offset + symbol_size] = selector
    patched[url_offset : url_offset + len(OFFICIAL_MANIFEST_URL)] = OFFICIAL_MANIFEST_URL
    if patched[file_offset : file_offset + symbol_size] != selector:
        raise AssertionError(f"Runtime route selector read-back failed for {path}")
    if patched[url_offset : url_offset + len(OFFICIAL_MANIFEST_URL)] != OFFICIAL_MANIFEST_URL:
        raise AssertionError(f"Official manifest fallback URL read-back failed for {path}")
    state = "runtime selector already installed" if already_selector else (
        "legacy signature patch upgraded" if old_return_patch else "signature guard bypassed"
    )
    note = f"{abi}: {state}; runtime route probe at 127.0.0.1:18480, official CDN fallback retained"
    return bytes(patched), note


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
                    verified, note = patch_library(entry, check.read(entry))
                    if verified != check.read(entry):
                        raise ValueError(f"Runtime route selector verification failed in {entry}: {note}")
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
    print(f"Patched signer guard and runtime official/local manifest routing in all ABIs: {args.output_apk}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
