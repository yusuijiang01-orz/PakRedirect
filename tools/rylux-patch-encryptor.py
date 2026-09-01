#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""RYLUX localization patch encryptor.

Creates authenticated chunk-encrypted .rpe files and a protected manifest for
RYLUX 2.3+. The AES-256 content key stays in a separate local .key file and must
never be uploaded to public GitHub or the protected-content download directory.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import os
import secrets
import struct
import sys
from datetime import datetime
from pathlib import Path

try:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
except ImportError as exc:
    raise SystemExit(
        "缺少 cryptography。请先运行 setup.bat，或执行: py -m pip install cryptography==45.0.6"
    ) from exc

MAGIC = b"RYLUXE01"
MODULE = "sg_localization"
PROTECTED = ("settings.pak", "ui.pak", "updatefs.pak")
DEFAULT_CHUNK = 1024 * 1024
MAX_REVISION = 2_147_483_647


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        while True:
            chunk = f.read(1024 * 1024)
            if not chunk:
                return h.hexdigest()
            h.update(chunk)


def b64(value: bytes) -> str:
    return base64.b64encode(value).decode("ascii")


def key_id(key: bytes) -> str:
    return hashlib.sha256(key).hexdigest()[:16]


def load_key(path: Path) -> bytes:
    try:
        obj = json.loads(path.read_text(encoding="utf-8"))
        raw = base64.b64decode(obj["key_b64"], validate=True)
    except Exception as exc:
        raise SystemExit(f"密钥文件无效: {path}") from exc
    if len(raw) != 32:
        raise SystemExit("内容密钥必须为 32 字节")
    if obj.get("key_id") != key_id(raw):
        raise SystemExit("密钥文件 key_id 校验失败")
    return raw


def create_key(path: Path, force: bool = False) -> bytes:
    if path.exists() and not force:
        raise SystemExit(f"密钥文件已存在，拒绝覆盖: {path}")
    path.parent.mkdir(parents=True, exist_ok=True)
    key = secrets.token_bytes(32)
    obj = {
        "schema": 1,
        "algorithm": "AES-256-GCM",
        "module": MODULE,
        "key_id": key_id(key),
        "key_b64": b64(key),
    }
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2), encoding="utf-8")
    return key


def parse_linkspak(path: Path):
    lines = path.read_text(encoding="utf-8").splitlines()
    records = {}
    for idx, line in enumerate(lines):
        fields = line.split(",")
        if len(fields) < 6:
            continue
        name = fields[2].strip().lower()
        if name not in PROTECTED:
            continue
        try:
            size = int(fields[3].strip())
            revision = int(fields[5].strip())
        except ValueError:
            raise SystemExit(f"linkspak 数值无效: {name}")
        records[name] = (idx, fields, size, revision)
    missing = [name for name in PROTECTED if name not in records]
    if missing:
        raise SystemExit("linkspak 缺少: " + ", ".join(missing))
    return lines, records


def next_revisions(records) -> dict[str, int]:
    now = datetime.now()
    candidate = int(now.strftime("%Y%m%d%H"))
    highest = max(records[name][3] for name in PROTECTED)
    base = max(candidate, highest + 1)
    if base + len(PROTECTED) >= MAX_REVISION:
        raise SystemExit("revision 已接近 32 位有符号整数上限")
    return {name: base + i for i, name in enumerate(PROTECTED)}


def aad(name: str, revision: int, index: int, plain_size: int, chunk_size: int, kid: str) -> bytes:
    value = (
        f"RYLUXE01|{MODULE}|{name}|{revision}|{index}|"
        f"{plain_size}|{chunk_size}|{kid}"
    )
    return value.encode("utf-8")


def encrypt_file(
    source: Path,
    output: Path,
    key: bytes,
    name: str,
    revision: int,
    chunk_size: int = DEFAULT_CHUNK,
    progress=None,
) -> dict:
    if chunk_size < 64 * 1024 or chunk_size > 4 * 1024 * 1024:
        raise SystemExit("chunk_size 必须在 64KB 到 4MB 之间")
    plain_size = source.stat().st_size
    if plain_size <= 0:
        raise SystemExit(f"PAK 为空: {name}")
    plain_sha = sha256_file(source)
    kid = key_id(key)
    nonce_prefix = secrets.token_bytes(8)

    header = {
        "schema": 1,
        "module": MODULE,
        "name": name,
        "plain_size": plain_size,
        "plain_sha256": plain_sha,
        "revision": revision,
        "chunk_size": chunk_size,
        "nonce_prefix": b64(nonce_prefix),
        "key_id": kid,
    }
    header_bytes = json.dumps(
        header, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    if len(header_bytes) > 128 * 1024:
        raise SystemExit("RPE 头部异常过大")

    output.parent.mkdir(parents=True, exist_ok=True)
    temp = output.with_name(output.name + ".tmp")
    if temp.exists():
        temp.unlink()

    aes = AESGCM(key)
    done = 0
    index = 0
    try:
        with source.open("rb") as src, temp.open("wb") as dst:
            dst.write(MAGIC)
            dst.write(struct.pack(">I", len(header_bytes)))
            dst.write(header_bytes)
            while True:
                plain = src.read(chunk_size)
                if not plain:
                    break
                nonce = nonce_prefix + struct.pack(">I", index)
                encrypted = aes.encrypt(
                    nonce,
                    plain,
                    aad(name, revision, index, plain_size, chunk_size, kid),
                )
                dst.write(encrypted)
                done += len(plain)
                index += 1
                if progress:
                    progress(name, done, plain_size)
            dst.flush()
            os.fsync(dst.fileno())
        temp.replace(output)
    except BaseException:
        try:
            temp.unlink()
        except FileNotFoundError:
            pass
        raise

    return {
        "name": name,
        "revision": revision,
        "plain_size": plain_size,
        "plain_sha256": plain_sha,
        "encrypted_name": output.name,
        "encrypted_size": output.stat().st_size,
        "encrypted_sha256": sha256_file(output),
        "format": "RYLUXE01",
    }


def read_rpe_header(path: Path):
    with path.open("rb") as f:
        if f.read(8) != MAGIC:
            raise SystemExit(f"不是有效 RPE 文件: {path}")
        raw = f.read(4)
        if len(raw) != 4:
            raise SystemExit("RPE 头部损坏")
        length = struct.unpack(">I", raw)[0]
        if not 0 < length <= 128 * 1024:
            raise SystemExit("RPE 头部长度无效")
        header_bytes = f.read(length)
        if len(header_bytes) != length:
            raise SystemExit("RPE 头部读取失败")
        return 12 + length, json.loads(header_bytes.decode("utf-8"))


def decrypt_file(source: Path, output: Path, key: bytes, progress=None) -> None:
    payload_start, header = read_rpe_header(source)
    if header.get("module") != MODULE:
        raise SystemExit("RPE 模块不匹配")
    name = str(header["name"]).lower()
    plain_size = int(header["plain_size"])
    revision = int(header["revision"])
    chunk_size = int(header["chunk_size"])
    kid = str(header["key_id"]).lower()
    nonce_prefix = base64.b64decode(header["nonce_prefix"], validate=True)
    if kid != key_id(key) or len(nonce_prefix) != 8:
        raise SystemExit("解密密钥不匹配")

    aes = AESGCM(key)
    output.parent.mkdir(parents=True, exist_ok=True)
    temp = output.with_name(output.name + ".tmp")
    digest = hashlib.sha256()
    done = 0
    index = 0
    with source.open("rb") as src, temp.open("wb") as dst:
        src.seek(payload_start)
        while done < plain_size:
            plain_len = min(chunk_size, plain_size - done)
            encrypted = src.read(plain_len + 16)
            if len(encrypted) != plain_len + 16:
                temp.unlink(missing_ok=True)
                raise SystemExit("RPE 密文长度不足")
            nonce = nonce_prefix + struct.pack(">I", index)
            try:
                plain = aes.decrypt(
                    nonce,
                    encrypted,
                    aad(name, revision, index, plain_size, chunk_size, kid),
                )
            except Exception as exc:
                temp.unlink(missing_ok=True)
                raise SystemExit("RPE GCM 校验失败，文件或密钥不正确") from exc
            dst.write(plain)
            digest.update(plain)
            done += len(plain)
            index += 1
            if progress:
                progress(name, done, plain_size)
        dst.flush()
        os.fsync(dst.fileno())

    expected = str(header["plain_sha256"]).lower()
    if digest.hexdigest() != expected:
        temp.unlink(missing_ok=True)
        raise SystemExit("解密后的 PAK SHA-256 不匹配")
    temp.replace(output)


def build_package(source_dir: Path, linkspak: Path, output_dir: Path, key_file: Path, chunk_size=DEFAULT_CHUNK, progress=None):
    key = load_key(key_file)
    lines, records = parse_linkspak(linkspak)
    revisions = next_revisions(records)

    for name in PROTECTED:
        source = source_dir / name
        if not source.is_file():
            raise SystemExit(f"缺少 PAK: {source}")

    output_dir.mkdir(parents=True, exist_ok=True)
    file_items = []
    for name in PROTECTED:
        source = source_dir / name
        actual_size = source.stat().st_size
        idx, fields, _, _ = records[name]
        revision = revisions[name]
        fields[3] = str(actual_size)
        fields[5] = str(revision)
        lines[idx] = ",".join(fields)
        encrypted_name = name + ".rpe"
        item = encrypt_file(
            source,
            output_dir / encrypted_name,
            key,
            name,
            revision,
            chunk_size,
            progress,
        )
        file_items.append(item)

    link_bytes = ("\n".join(lines) + "\n").encode("utf-8")
    link_out = output_dir / "linkspak.txt"
    link_out.write_bytes(link_bytes)

    link_item = {
        "stored_name": "linkspak.txt",
        "size": len(link_bytes),
        "sha256": hashlib.sha256(link_bytes).hexdigest(),
    }
    kid = key_id(key)
    seed = "|".join(
        [kid, link_item["sha256"]]
        + [item["plain_sha256"] + ":" + str(item["revision"]) for item in file_items]
    ).encode("utf-8")
    version = hashlib.sha256(seed).hexdigest()[:12]

    payload = {
        "schema": 1,
        "module": MODULE,
        "version": version,
        "key_id": kid,
        "linkspak": link_item,
        "files": file_items,
    }
    payload_bytes = json.dumps(
        payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    manifest = {
        "schema": 1,
        "module": MODULE,
        "key_id": kid,
        "payload_b64": b64(payload_bytes),
        "manifest_hmac": hmac.new(key, payload_bytes, hashlib.sha256).hexdigest(),
    }
    (output_dir / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    return {
        "version": version,
        "key_id": kid,
        "key_b64": b64(key),
        "revisions": revisions,
        "files": file_items,
        "output": str(output_dir),
    }


def verify_package(output_dir: Path, key_file: Path) -> None:
    key = load_key(key_file)
    manifest = json.loads((output_dir / "manifest.json").read_text(encoding="utf-8"))
    payload_bytes = base64.b64decode(manifest["payload_b64"], validate=True)
    expected_hmac = hmac.new(key, payload_bytes, hashlib.sha256).hexdigest()
    if not hmac.compare_digest(expected_hmac, manifest["manifest_hmac"]):
        raise SystemExit("manifest HMAC 校验失败")
    payload = json.loads(payload_bytes.decode("utf-8"))
    if payload["key_id"] != key_id(key):
        raise SystemExit("manifest key_id 不匹配")
    for item in payload["files"]:
        path = output_dir / item["encrypted_name"]
        if path.stat().st_size != item["encrypted_size"]:
            raise SystemExit(f"密文大小不匹配: {item['name']}")
        if sha256_file(path) != item["encrypted_sha256"]:
            raise SystemExit(f"密文 SHA-256 不匹配: {item['name']}")
        temp = output_dir / (item["name"] + ".verify.tmp")
        try:
            decrypt_file(path, temp, key)
            if temp.stat().st_size != item["plain_size"] or sha256_file(temp) != item["plain_sha256"]:
                raise SystemExit(f"明文验证失败: {item['name']}")
        finally:
            temp.unlink(missing_ok=True)


def cli(argv=None) -> int:
    parser = argparse.ArgumentParser(description="RYLUX 汉化补丁 AES-256-GCM 加密工具")
    sub = parser.add_subparsers(dest="command", required=True)

    p_key = sub.add_parser("keygen", help="生成新的内容密钥")
    p_key.add_argument("--key", required=True, type=Path)
    p_key.add_argument("--force", action="store_true")

    p_enc = sub.add_parser("encrypt", help="加密 settings/ui/updatefs")
    p_enc.add_argument("--source", required=True, type=Path)
    p_enc.add_argument("--linkspak", required=True, type=Path)
    p_enc.add_argument("--output", required=True, type=Path)
    p_enc.add_argument("--key", required=True, type=Path)
    p_enc.add_argument("--chunk-size", type=int, default=DEFAULT_CHUNK)

    p_verify = sub.add_parser("verify", help="完整验证已生成的部署目录")
    p_verify.add_argument("--output", required=True, type=Path)
    p_verify.add_argument("--key", required=True, type=Path)

    p_dec = sub.add_parser("decrypt", help="管理员解密单个 RPE 文件")
    p_dec.add_argument("--input", required=True, type=Path)
    p_dec.add_argument("--output", required=True, type=Path)
    p_dec.add_argument("--key", required=True, type=Path)

    p_env = sub.add_parser("show-env", help="显示服务器需要配置的环境变量")
    p_env.add_argument("--key", required=True, type=Path)

    args = parser.parse_args(argv)

    def progress(name, done, total):
        percent = int(done * 100 / total) if total else 100
        print(f"\r{name}: {done / 1024 / 1024:.1f} MB / {total / 1024 / 1024:.1f} MB ({percent}%)", end="", flush=True)
        if done >= total:
            print()

    if args.command == "keygen":
        key = create_key(args.key, args.force)
        print(f"已生成密钥: {args.key}")
        print(f"key_id: {key_id(key)}")
    elif args.command == "encrypt":
        result = build_package(args.source, args.linkspak, args.output, args.key, args.chunk_size, progress)
        print(f"完成: {result['output']}")
        print(f"version: {result['version']}")
        print(f"key_id: {result['key_id']}")
        for name, rev in result["revisions"].items():
            print(f"{name}: revision {rev}")
    elif args.command == "verify":
        verify_package(args.output, args.key)
        print("完整验证通过")
    elif args.command == "decrypt":
        decrypt_file(args.input, args.output, load_key(args.key), progress)
        print(f"已解密: {args.output}")
    elif args.command == "show-env":
        key = load_key(args.key)
        print("RYLUX_SG_LOCALIZATION_KEY_B64=" + b64(key))
    return 0


def gui() -> int:
    import tkinter as tk
    from tkinter import filedialog, messagebox, ttk
    import threading

    root = tk.Tk()
    root.title("RYLUX 汉化补丁加密工具")
    root.geometry("720x580")
    root.minsize(680, 540)

    source_var = tk.StringVar()
    link_var = tk.StringVar()
    output_var = tk.StringVar()
    key_var = tk.StringVar()
    status_var = tk.StringVar(value="请选择 PAK 目录、linkspak.txt、输出目录和密钥文件。")
    progress_var = tk.DoubleVar(value=0)

    frame = ttk.Frame(root, padding=18)
    frame.pack(fill="both", expand=True)

    ttk.Label(frame, text="RYLUX 汉化补丁加密工具", font=("", 17, "bold")).pack(anchor="w")
    ttk.Label(frame, text="AES-256-GCM 分块加密 · HMAC 清单 · 密钥与部署文件分离").pack(anchor="w", pady=(3, 16))

    def path_row(label, var, mode):
        row = ttk.Frame(frame)
        row.pack(fill="x", pady=5)
        ttk.Label(row, text=label, width=14).pack(side="left")
        ttk.Entry(row, textvariable=var).pack(side="left", fill="x", expand=True, padx=(0, 8))
        def choose():
            if mode == "dir":
                value = filedialog.askdirectory()
            elif mode == "save-key":
                value = filedialog.asksaveasfilename(defaultextension=".key", filetypes=[("RYLUX Key", "*.key"), ("All", "*.*")])
            else:
                value = filedialog.askopenfilename()
            if value:
                var.set(value)
        ttk.Button(row, text="选择", command=choose, width=8).pack(side="right")

    path_row("PAK 目录", source_var, "dir")
    path_row("linkspak.txt", link_var, "file")
    path_row("输出目录", output_var, "dir")
    path_row("内容密钥", key_var, "save-key")

    buttons = ttk.Frame(frame)
    buttons.pack(fill="x", pady=(12, 8))

    def generate_key():
        try:
            path = Path(key_var.get().strip())
            if not str(path):
                raise SystemExit("请先选择密钥文件位置")
            if path.exists() and not messagebox.askyesno("确认", "密钥文件已存在。确定要生成新密钥并覆盖吗？\n\n更换密钥后必须重新部署服务器密钥和全部加密 PAK。"):
                return
            key = create_key(path, force=path.exists())
            status_var.set(f"新密钥已生成 · key_id {key_id(key)}")
        except BaseException as exc:
            messagebox.showerror("失败", str(exc))

    def copy_env():
        try:
            key = load_key(Path(key_var.get().strip()))
            value = "RYLUX_SG_LOCALIZATION_KEY_B64=" + b64(key)
            root.clipboard_clear()
            root.clipboard_append(value)
            status_var.set("服务器环境变量已复制到剪贴板。请勿发送到公开位置。")
        except BaseException as exc:
            messagebox.showerror("失败", str(exc))

    ttk.Button(buttons, text="生成/更换密钥", command=generate_key).pack(side="left")
    ttk.Button(buttons, text="复制服务器密钥变量", command=copy_env).pack(side="left", padx=8)

    bar = ttk.Progressbar(frame, maximum=100, variable=progress_var)
    bar.pack(fill="x", pady=(8, 6))
    ttk.Label(frame, textvariable=status_var, wraplength=660).pack(anchor="w")

    log = tk.Text(frame, height=12, wrap="word")
    log.pack(fill="both", expand=True, pady=(12, 8))
    log.configure(state="disabled")

    def log_line(text):
        log.configure(state="normal")
        log.insert("end", text + "\n")
        log.see("end")
        log.configure(state="disabled")

    def start_encrypt():
        def worker():
            try:
                source = Path(source_var.get().strip())
                link = Path(link_var.get().strip())
                out = Path(output_var.get().strip())
                key = Path(key_var.get().strip())
                if not source.is_dir():
                    raise SystemExit("PAK 目录无效")
                if not link.is_file():
                    raise SystemExit("linkspak.txt 无效")
                if not key.is_file():
                    raise SystemExit("请先生成或选择内容密钥")
                if not str(out):
                    raise SystemExit("请选择输出目录")

                def progress(name, done, total):
                    percent = int(done * 100 / total) if total else 100
                    root.after(0, lambda: progress_var.set(percent))
                    root.after(0, lambda: status_var.set(f"正在加密 {name} · {percent}%"))

                root.after(0, lambda: encrypt_btn.configure(state="disabled"))
                result = build_package(source, link, out, key, DEFAULT_CHUNK, progress)
                verify_package(out, key)
                root.after(0, lambda: progress_var.set(100))
                root.after(0, lambda: status_var.set(f"完成 · version {result['version']} · key_id {result['key_id']}"))
                root.after(0, lambda: log_line("部署目录: " + result["output"]))
                for name, rev in result["revisions"].items():
                    root.after(0, lambda n=name, r=rev: log_line(f"{n}: revision {r}"))
                root.after(0, lambda: log_line("完整解密验证通过。只上传输出目录，不要上传 .key 密钥文件。"))
                root.after(0, lambda: messagebox.showinfo("完成", "加密与完整验证已完成。\n\n请只把输出目录部署到 VPS protected-content/sg_localization。"))
            except BaseException as exc:
                root.after(0, lambda: messagebox.showerror("失败", str(exc)))
                root.after(0, lambda: status_var.set("加密失败"))
            finally:
                root.after(0, lambda: encrypt_btn.configure(state="normal"))

        threading.Thread(target=worker, daemon=True).start()

    encrypt_btn = ttk.Button(frame, text="加密并完整验证", command=start_encrypt)
    encrypt_btn.pack(fill="x", ipady=6, pady=(4, 0))

    root.mainloop()
    return 0


def main() -> int:
    if len(sys.argv) == 1:
        return gui()
    return cli()


if __name__ == "__main__":
    raise SystemExit(main())
