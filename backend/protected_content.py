import base64
import hashlib
import hmac
import json
import os
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from fastapi import APIRouter, Header, HTTPException, Request
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field

from admin_v2 import request_ip
from user_v1 import iso, membership, open_db, require_user, utc_now

router = APIRouter()

MODULE_CODE = "sg_localization"
CONTENT_ROOT = Path(
    os.environ.get("RYLUX_PROTECTED_CONTENT_DIR", "./protected-content")
).resolve()
KEY_ENV = "RYLUX_SG_LOCALIZATION_KEY_B64"
MANIFEST_NAME = "manifest.json"


class ContentManifestRequest(BaseModel):
    device_public_key: str = Field(min_length=64, max_length=8192)


def _module_dir(module_code: str) -> Path:
    if module_code != MODULE_CODE:
        raise HTTPException(status_code=404, detail="模块不存在")
    return CONTENT_ROOT / module_code


def _decode_b64(value: str) -> bytes:
    try:
        return base64.b64decode(value.encode("ascii"), validate=True)
    except Exception as exc:
        raise HTTPException(status_code=503, detail="内容密钥配置无效") from exc


def _content_key() -> bytes:
    raw = os.environ.get(KEY_ENV, "").strip()
    if not raw:
        raise HTTPException(status_code=503, detail="内容服务尚未配置")
    key = _decode_b64(raw)
    if len(key) != 32:
        raise HTTPException(status_code=503, detail="内容密钥长度无效")
    return key


def _load_manifest(module_code: str) -> tuple[dict, dict, bytes]:
    path = _module_dir(module_code) / MANIFEST_NAME
    if not path.is_file():
        raise HTTPException(status_code=503, detail="内容清单尚未部署")
    try:
        outer = json.loads(path.read_text(encoding="utf-8"))
        if int(outer.get("schema", 0)) != 1:
            raise ValueError("schema")
        if outer.get("module") != module_code:
            raise ValueError("module")
        payload_bytes = base64.b64decode(outer["payload_b64"].encode("ascii"), validate=True)
        if len(payload_bytes) > 1024 * 1024:
            raise ValueError("payload too large")
        payload = json.loads(payload_bytes.decode("utf-8"))
    except Exception as exc:
        raise HTTPException(status_code=503, detail="内容清单无效") from exc
    if int(payload.get("schema", 0)) != 1 or payload.get("module") != module_code:
        raise HTTPException(status_code=503, detail="内容清单模块无效")
    return outer, payload, payload_bytes


def _validate_manifest_key(outer: dict, payload_bytes: bytes, key: bytes) -> None:
    expected_key_id = hashlib.sha256(key).hexdigest()[:16]
    if outer.get("key_id") != expected_key_id:
        raise HTTPException(status_code=503, detail="内容密钥与清单不匹配")
    expected_hmac = hmac.new(key, payload_bytes, hashlib.sha256).hexdigest()
    actual_hmac = str(outer.get("manifest_hmac", "")).strip().lower()
    if not hmac.compare_digest(expected_hmac, actual_hmac):
        raise HTTPException(status_code=503, detail="内容清单签名校验失败")


def _require_module_access(
    module_code: str,
    request: Request,
    authorization: str | None,
    log_access: bool,
):
    auth, _ = require_user(authorization)
    m = membership(auth)
    with open_db() as db:
        module = db.execute(
            "SELECT code,name,package_name,enabled FROM modules WHERE code=? LIMIT 1",
            (module_code,),
        ).fetchone()
        if module is None or int(module["enabled"]) != 1:
            raise HTTPException(status_code=404, detail="模块不存在或已停用")
        allowed = bool(m["active"])
        if log_access:
            db.execute(
                """
                INSERT INTO module_access_logs(user_id,module_code,allowed,ip_address,created_at)
                VALUES(?,?,?,?,?)
                """,
                (
                    auth["user_id"],
                    module_code,
                    1 if allowed else 0,
                    request_ip(request),
                    iso(utc_now()),
                ),
            )
            db.commit()
    if not allowed:
        raise HTTPException(status_code=403, detail="体验或 VIP 已到期，请续费后使用")
    return auth, m


def _load_public_key(value: str):
    try:
        der = base64.b64decode(value.encode("ascii"), validate=True)
        public_key = serialization.load_der_public_key(der)
    except Exception as exc:
        raise HTTPException(status_code=400, detail="设备公钥无效") from exc
    if not isinstance(public_key, rsa.RSAPublicKey) or public_key.key_size < 2048:
        raise HTTPException(status_code=400, detail="设备公钥规格不支持")
    return public_key


def _wrap_key(public_key, key: bytes) -> str:
    wrapped = public_key.encrypt(
        key,
        padding.OAEP(
            mgf=padding.MGF1(algorithm=hashes.SHA1()),
            algorithm=hashes.SHA256(),
            label=None,
        ),
    )
    return base64.b64encode(wrapped).decode("ascii")


def _allowed_downloads(payload: dict) -> set[str]:
    allowed = set()
    linkspak = payload.get("linkspak")
    if isinstance(linkspak, dict):
        name = str(linkspak.get("stored_name", "linkspak.txt"))
        if name == "linkspak.txt":
            allowed.add(name)
    for item in payload.get("files") or []:
        if not isinstance(item, dict):
            continue
        name = str(item.get("encrypted_name", "")).strip()
        if name and "/" not in name and "\\" not in name and ".." not in name:
            allowed.add(name)
    return allowed


@router.post("/api/v1/content/{module_code}/manifest")
def protected_manifest(
    module_code: str,
    payload: ContentManifestRequest,
    request: Request,
    authorization: str | None = Header(default=None),
):
    _require_module_access(module_code, request, authorization, True)
    key = _content_key()
    outer, manifest_payload, payload_bytes = _load_manifest(module_code)
    _validate_manifest_key(outer, payload_bytes, key)
    public_key = _load_public_key(payload.device_public_key)
    wrapped_key = _wrap_key(public_key, key)

    return {
        "schema": 1,
        "module": module_code,
        "key_id": outer["key_id"],
        "payload_b64": outer["payload_b64"],
        "manifest_hmac": outer["manifest_hmac"],
        "wrapped_key": wrapped_key,
        "version": manifest_payload.get("version", ""),
    }


@router.get("/api/v1/content/{module_code}/files/{file_name}")
def protected_file(
    module_code: str,
    file_name: str,
    request: Request,
    authorization: str | None = Header(default=None),
):
    _require_module_access(module_code, request, authorization, False)
    key = _content_key()
    outer, payload, payload_bytes = _load_manifest(module_code)
    _validate_manifest_key(outer, payload_bytes, key)

    if (
        not file_name
        or "/" in file_name
        or "\\" in file_name
        or ".." in file_name
        or file_name not in _allowed_downloads(payload)
    ):
        raise HTTPException(status_code=404, detail="资源不存在")

    path = _module_dir(module_code) / file_name
    if not path.is_file():
        raise HTTPException(status_code=404, detail="资源尚未部署")

    response = FileResponse(
        path,
        media_type="application/octet-stream",
        filename=file_name,
    )
    response.headers["Cache-Control"] = "private, no-store, max-age=0"
    response.headers["X-Content-Type-Options"] = "nosniff"
    return response
