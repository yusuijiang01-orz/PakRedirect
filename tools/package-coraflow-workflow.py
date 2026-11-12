#!/usr/bin/env python3
"""Package a CoraFlow graph and its referenced images as a RYLUXE01 payload."""

from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import os
import re
import secrets
import struct
import tempfile
import time
import zipfile
from pathlib import Path, PurePosixPath

try:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
except ImportError as exc:
    raise SystemExit("缺少 cryptography；请运行 py -m pip install cryptography") from exc


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CORAFLOW = Path(r"H:\APP\CoraFlow")
DEFAULT_WORKFLOW = DEFAULT_CORAFLOW / "scripts" / "RYLUX_登录清包补药回挂机_完整流程_v3.31.json"
DEFAULT_KEY = ROOT.parent / "VPS" / "pakredirect-license" / "secrets" / "content.env"
DEFAULT_OUTPUT = ROOT.parent / "workflow-release" / "coraflow-v3.31"
KEY_ENV_NAME = "RYLUX_SG_LOCALIZATION_KEY_B64"
MODULE = "sg_localization"
RPE_NAME = "coraflow_workflow.zip"
OUTPUT_BUNDLE = "workflow.rpe"
MAGIC = b"RYLUXE01"
CHUNK_SIZE = 1024 * 1024
MAX_REVISION = 2_147_483_647


def target(node_id: str, path: str, transform: dict | None = None) -> dict:
    result = {"node_id": node_id, "path": path}
    if transform:
        result["transform"] = transform
    return result


# Only values with real graph targets are exposed to the RYLUX workflow panel.
# The app applies these bindings to a private graph copy before execution.
VARIABLES = [
    {
        "id": "inventory.freeSlotsAtOrBelow",
        "label": "剩余格子达到此数时清包",
        "type": "integer",
        "default": 4,
        "min": 0,
        "max": 35,
        "targets": [
            target("node_14", "data.config.number_value"),
            target("node_280", "data.config.number_value"),
        ],
    },
    {
        "id": "inventory.remainingWeightAtOrBelow",
        "label": "剩余负重达到此数时清包",
        "type": "integer",
        "default": 28,
        "min": 0,
        "max": 200,
        "targets": [
            target("node_15", "data.config.number_value", {"op": "subtract_from", "base": 200}),
            target("node_281", "data.config.number_value", {"op": "subtract_from", "base": 200}),
        ],
    },
    {
        "id": "equipment.sellWhite",
        "label": "出售白色装备",
        "type": "boolean",
        "default": True,
        "targets": [target("node_111", "data.config.sell_white")],
    },
    {
        "id": "equipment.sellBlue",
        "label": "出售蓝色装备",
        "type": "boolean",
        "default": True,
        "targets": [target("node_111", "data.config.sell_blue")],
    },
    {
        "id": "equipment.keepBlueRecovery",
        "label": "保留带回血/回内词条的蓝装",
        "type": "boolean",
        "default": True,
        "targets": [target("node_111", "data.config.preserve_blue_recovery")],
    },
    {
        "id": "medicine.refillBelow",
        "label": "红蓝药低于此数量时补充",
        "type": "integer",
        "default": 2,
        "min": 0,
        "max": 20,
        "targets": [
            *[target(node_id, "data.config.number_value") for node_id in ("node_16", "node_51", "node_310")],
            *[target(node_id, "data.config.number_value") for node_id in ("node_17", "node_66", "node_311")],
            *[target(node_id, "data.config.number_value") for node_id in ("node_65", "node_134", "node_81", "node_140")],
            target("node_111", "data.config.medicine_min"),
        ],
    },
    {
        "id": "medicine.maxTotal",
        "label": "红蓝药目标上限",
        "type": "integer",
        "default": 20,
        "min": 2,
        "max": 20,
        "targets": [
            target("node_111", "data.config.medicine_max"),
            target("node_v328_red_batch_cap", "data.config.number_value", {"op": "max_minus_batch_plus_one", "batch_variable": "medicine.batchMax"}),
            target("node_v328_blue_batch_cap", "data.config.number_value", {"op": "max_minus_batch_plus_one", "batch_variable": "medicine.batchMax"}),
        ],
    },
    {
        "id": "medicine.batchMax",
        "label": "单次最多购买数量",
        "type": "integer",
        "default": 5,
        "min": 1,
        "max": 5,
        "targets": [
            target("node_111", "data.config.medicine_purchase_batch_max"),
            target("node_129", "data.config.count", {"op": "subtract", "value": 1}),
            target("node_135", "data.config.count", {"op": "subtract", "value": 1}),
            target("node_131", "data.config.number_value"),
            target("node_137", "data.config.number_value"),
        ],
    },
]


def read_key(path: Path) -> bytes:
    try:
        text = path.read_text(encoding="utf-8")
        encoded = next(
            line.split("=", 1)[1].strip().strip("\"'")
            for line in text.splitlines()
            if line.strip().startswith(KEY_ENV_NAME + "=")
        )
        key = base64.b64decode(encoded, validate=True)
    except Exception as exc:
        raise SystemExit(f"PAK 内容密钥文件无效或不可读：{path}") from exc
    if len(key) != 32:
        raise SystemExit("PAK 内容密钥长度无效")
    return key


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def key_id(key: bytes) -> str:
    return hashlib.sha256(key).hexdigest()[:16]


def aad(revision: int, index: int, plain_size: int, chunk_size: int, kid: str) -> bytes:
    return f"RYLUXE01|{MODULE}|{RPE_NAME}|{revision}|{index}|{plain_size}|{chunk_size}|{kid}".encode("utf-8")


def set_path(root: dict, dotted_path: str, value: object) -> None:
    parts = dotted_path.split(".")
    current = root
    for part in parts[:-1]:
        if part not in current or not isinstance(current[part], dict):
            raise ValueError(f"invalid binding path: {dotted_path}")
        current = current[part]
    if parts[-1] not in current:
        raise ValueError(f"binding target path does not exist: {dotted_path}")
    current[parts[-1]] = value


def apply_binding_defaults(graph: dict) -> None:
    nodes = {node["id"]: node for node in graph["nodes"]}
    values = {item["id"]: item["default"] for item in VARIABLES}
    for item in VARIABLES:
        value = values[item["id"]]
        for binding in item["targets"]:
            if binding["node_id"] not in nodes:
                raise ValueError(f"missing binding node: {binding['node_id']}")
            transform = binding.get("transform", {})
            if transform.get("op") == "subtract_from":
                resolved = int(transform["base"]) - int(value)
            elif transform.get("op") == "subtract":
                resolved = int(value) - int(transform["value"])
            elif transform.get("op") == "max_minus_batch_plus_one":
                resolved = int(value) - int(values[transform["batch_variable"]]) + 1
            else:
                resolved = value
            set_path(nodes[binding["node_id"]]["data"], binding["path"].removeprefix("data."), resolved)


def patch_latest_route_settings(graph: dict) -> None:
    """Apply the user's confirmed first map and 100,200 coordinates to this release copy."""
    nodes = {node["id"]: node for node in graph["nodes"]}
    for node_id, digit in zip(("node_223", "node_224", "node_225"), "100"):
        nodes[node_id]["data"]["config"]["key"] = digit
    for node_id, digit in zip(("node_227", "node_228", "node_229"), "200"):
        nodes[node_id]["data"]["config"]["key"] = digit
    for node_id in ("node_232", "node_234", "node_236", "node_260"):
        nodes[node_id]["data"]["config"]["number_value"] = 100
        nodes[node_id]["data"]["name"] = nodes[node_id]["data"]["name"].replace("225", "100")
    for node_id in ("node_261", "node_262", "node_263", "node_264", "node_265", "node_266"):
        nodes[node_id]["data"]["config"]["number_value"] = 200
        nodes[node_id]["data"]["name"] = nodes[node_id]["data"]["name"].replace("202", "200")
    for node_id in ("node_214", "node_250"):
        nodes[node_id]["data"]["name"] = nodes[node_id]["data"]["name"].replace("Lua mapId 5", "地图索引 0")
    for node_id in ("node_230", "node_237", "node_239", "node_243", "node_245"):
        data = nodes[node_id]["data"]
        data["name"] = data["name"].replace("1682,3056", "100,200").replace("mapId 5", "地图索引 0")
        config = data.get("config", {})
        for field in ("message",):
            if isinstance(config.get(field), str):
                config[field] = config[field].replace("1682,3056", "100,200").replace("225/202", "100/200").replace("mapId 5", "地图索引 0")
    graph["version"] = "3.31.1"
    graph["name"] = graph["name"].replace("v3.31", "v3.31.1")
    graph["description"] = graph.get("description", "") + " 本发布副本按用户确认修正为地图索引0（崇城野外），目标坐标100,200；可配置项由 workflow_bindings.json 绑定到节点。"


def image_references(graph: dict) -> list[str]:
    refs: set[str] = set()

    def visit(value: object) -> None:
        if isinstance(value, dict):
            for child in value.values():
                visit(child)
        elif isinstance(value, list):
            for child in value:
                visit(child)
        elif isinstance(value, str):
            normalized = value.replace("\\", "/")
            if normalized.startswith("captures/") and PurePosixPath(normalized).suffix.lower() in {".png", ".jpg", ".jpeg", ".webp", ".bmp"}:
                path = PurePosixPath(normalized)
                if ".." in path.parts:
                    raise ValueError(f"unsafe asset path: {value}")
                refs.add(path.as_posix())

    visit(graph)
    return sorted(refs)


def resolve_asset(coraflow_root: Path, relative: str) -> Path:
    candidates = [coraflow_root / relative, coraflow_root / "electron" / relative]
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    raise FileNotFoundError(f"workflow image is missing: {relative}")


def create_zip(workflow_path: Path, coraflow_root: Path, output: Path) -> tuple[int, str, int]:
    graph = json.loads(workflow_path.read_text(encoding="utf-8-sig"))
    if not isinstance(graph, dict) or not isinstance(graph.get("nodes"), list) or not isinstance(graph.get("edges"), list):
        raise ValueError("input is not a CoraFlow workflow JSON")
    ids = [node.get("id") for node in graph["nodes"]]
    if len(ids) != len(set(ids)):
        raise ValueError("workflow contains duplicate node ids")

    patch_latest_route_settings(graph)
    apply_binding_defaults(graph)
    node_index = {node["id"]: node for node in graph["nodes"]}
    for item in VARIABLES:
        for binding in item["targets"]:
            if binding["node_id"] not in node_index:
                raise ValueError(f"missing binding node: {binding['node_id']}")

    bindings = {
        "schema": 1,
        "workflow_id": graph.get("id", ""),
        "workflow_version": graph["version"],
        "variables": VARIABLES,
        "fixed": {
            "route.map_index": 0,
            "route.map_name": "崇城野外",
            "route.x": 100,
            "route.y": 200,
            "medicine.sell_surplus": False,
        },
    }
    refs = image_references(graph)
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=7) as archive:
        archive.writestr("workflow.json", json.dumps(graph, ensure_ascii=False, indent=2) + "\n")
        archive.writestr("workflow_bindings.json", json.dumps(bindings, ensure_ascii=False, indent=2) + "\n")
        for relative in refs:
            archive.write(resolve_asset(coraflow_root, relative), relative)
    with zipfile.ZipFile(output) as archive:
        if archive.testzip() is not None:
            raise ValueError("workflow ZIP CRC verification failed")
    return output.stat().st_size, sha256_file(output), len(refs)


def encrypt_zip(plain_zip: Path, bundle_path: Path, manifest_path: Path, key: bytes, version: str, revision: int) -> None:
    if revision <= 0 or revision > MAX_REVISION:
        raise ValueError("revision is outside the supported RYLUXE01 range")
    plain_size = plain_zip.stat().st_size
    plain_sha = sha256_file(plain_zip)
    kid = key_id(key)
    nonce_prefix = secrets.token_bytes(8)
    header = {
        "schema": 1,
        "module": MODULE,
        "name": RPE_NAME,
        "plain_size": plain_size,
        "plain_sha256": plain_sha,
        "revision": revision,
        "chunk_size": CHUNK_SIZE,
        "nonce_prefix": base64.b64encode(nonce_prefix).decode("ascii"),
        "key_id": kid,
    }
    header_bytes = json.dumps(header, sort_keys=True, separators=(",", ":")).encode("utf-8")
    bundle_path.parent.mkdir(parents=True, exist_ok=True)
    temporary = bundle_path.with_name(bundle_path.name + ".tmp")
    cipher = AESGCM(key)
    try:
        with plain_zip.open("rb") as source, temporary.open("wb") as destination:
            destination.write(MAGIC)
            destination.write(struct.pack(">I", len(header_bytes)))
            destination.write(header_bytes)
            index = 0
            while plain := source.read(CHUNK_SIZE):
                nonce = nonce_prefix + struct.pack(">I", index)
                destination.write(cipher.encrypt(nonce, plain, aad(revision, index, plain_size, CHUNK_SIZE, kid)))
                index += 1
            destination.flush()
            os.fsync(destination.fileno())
        temporary.replace(bundle_path)
    except BaseException:
        temporary.unlink(missing_ok=True)
        raise

    encryption = {
        "format": "RYLUXE01",
        "name": RPE_NAME,
        "revision": revision,
        "plain_size": plain_size,
        "plain_sha256": plain_sha,
        "key_id": kid,
    }
    payload = {
        "schema": 2,
        "module": MODULE,
        "version": version,
        "bundle": OUTPUT_BUNDLE,
        "size": bundle_path.stat().st_size,
        "sha256": sha256_file(bundle_path),
        "encryption": encryption,
        "key_id": kid,
    }
    payload_bytes = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    outer = {
        "schema": 1,
        "module": MODULE,
        "key_id": kid,
        "payload_b64": base64.b64encode(payload_bytes).decode("ascii"),
        "manifest_hmac": hmac.new(key, payload_bytes, hashlib.sha256).hexdigest(),
    }
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    temporary_manifest = manifest_path.with_name(manifest_path.name + ".tmp")
    temporary_manifest.write_text(json.dumps(outer, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary_manifest.replace(manifest_path)

    # Verify every GCM tag and the decrypted byte digest before considering the package publishable.
    digest = hashlib.sha256()
    total = 0
    with bundle_path.open("rb") as source:
        magic = source.read(len(MAGIC))
        header_len = struct.unpack(">I", source.read(4))[0]
        parsed_header = json.loads(source.read(header_len).decode("utf-8"))
        if magic != MAGIC or parsed_header != header:
            raise ValueError("encrypted workflow header verification failed")
        index = 0
        while encrypted := source.read(CHUNK_SIZE + 16):
            nonce = nonce_prefix + struct.pack(">I", index)
            plain = cipher.decrypt(nonce, encrypted, aad(revision, index, plain_size, CHUNK_SIZE, kid))
            digest.update(plain)
            total += len(plain)
            index += 1
    if total != plain_size or digest.hexdigest() != plain_sha:
        raise ValueError("encrypted workflow round-trip verification failed")
    verified_payload = json.loads(base64.b64decode(outer["payload_b64"]).decode("utf-8"))
    if not hmac.compare_digest(outer["manifest_hmac"], hmac.new(key, payload_bytes, hashlib.sha256).hexdigest()):
        raise ValueError("workflow manifest HMAC verification failed")
    if verified_payload["sha256"] != sha256_file(bundle_path):
        raise ValueError("workflow manifest bundle digest verification failed")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--workflow", type=Path, default=DEFAULT_WORKFLOW)
    parser.add_argument("--coraflow-root", type=Path, default=DEFAULT_CORAFLOW)
    parser.add_argument("--key-file", type=Path, default=DEFAULT_KEY, help="existing local PAK content.env; never publish this file")
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--version", default="coraflow-3.31-rylux-json-1")
    parser.add_argument("--revision", type=int, default=int(time.time()))
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9._-]{1,64}", args.version):
        raise SystemExit("invalid release version")
    key = read_key(args.key_file)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="rylux-coraflow-") as temporary_dir:
        plain_zip = Path(temporary_dir) / "workflow.zip"
        _, _, assets = create_zip(args.workflow, args.coraflow_root, plain_zip)
        encrypt_zip(plain_zip, args.output_dir / OUTPUT_BUNDLE, args.output_dir / "manifest.json", key, args.version, args.revision)
    manifest = json.loads((args.output_dir / "manifest.json").read_text(encoding="utf-8"))
    payload = json.loads(base64.b64decode(manifest["payload_b64"]).decode("utf-8"))
    print(f"Created encrypted workflow {args.output_dir / OUTPUT_BUNDLE} ({payload['size']} bytes)")
    print(f"Created signed manifest {args.output_dir / 'manifest.json'}")
    print(f"Version {args.version}; referenced images {assets}; encrypted SHA-256 {payload['sha256']}")


if __name__ == "__main__":
    main()
