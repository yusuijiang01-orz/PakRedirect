import base64
import hashlib
import hmac
import json
import os
import urllib.error
import urllib.parse
import urllib.request

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from fastapi import APIRouter, Header, HTTPException, Request
from fastapi.responses import RedirectResponse
from pydantic import BaseModel, Field

from admin_v2 import request_ip
from user_v1 import iso, membership, open_db, require_user, utc_now

router = APIRouter()

MODULE_CODE = "sg_localization"
KEY_ENV = "RYLUX_SG_LOCALIZATION_KEY_B64"
MANIFEST_URL = os.environ.get(
    "RYLUX_PROTECTED_MANIFEST_URL",
    "https://raw.githubusercontent.com/yusuijiang01-orz/PakRedirect/main/pak/manifest.json",
).strip()
DOWNLOAD_BASE_URL = os.environ.get(
    "RYLUX_PROTECTED_DOWNLOAD_BASE_URL",
    "https://raw.githubusercontent.com/yusuijiang01-orz/PakRedirect/main/pak/",
).strip()
MAX_MANIFEST_BYTES = 1024 * 1024
ALLOWED_CONTENT_HOSTS = {"raw.githubusercontent.com"}
PUBLIC_FILES = {
    "linkspak.txt",
    "settings.pak.rpe",
    "ui.pak.rpe",
    "updatefs.pak.rpe",
}


class ContentManifestRequest(BaseModel):
    device_public_key: str = Field(min_length=64, max_length=8192)


def _module_guard(module_code: str) -> None:
    if module_code != MODULE_CODE:
        raise HTTPException(status_code=404, detail="模块不存在")


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


def _validated_https_url(value: str, *, require_trailing_slash: bool = False) -> str:
    try:
        parsed = urllib.parse.urlparse(value)
    except Exception as exc:
        raise HTTPException(status_code=503, detail="内容地址配置无效") from exc
    if (
        parsed.scheme.lower() != "https"
        or not parsed.hostname
        or parsed.hostname.lower() not in ALLOWED_CONTENT_HOSTS
        or parsed.username is not None
        or parsed.password is not None
        or parsed.fragment
    ):
        raise HTTPException(status_code=503, detail="内容地址配置无效")
    if require_trailing_slash and not value.endswith("/"):
        raise HTTPException(status_code=503, detail="内容下载地址必须以 / 结尾")
    return value


def _fetch_manifest_bytes() -> bytes:
    url = _validated_https_url(MANIFEST_URL)
    request = urllib.request.Request(
        url,
        method="GET",
        headers={
            "Accept": "application/json",
            "Cache-Control": "no-cache",
            "Pragma": "no-cache",
            "User-Agent": "RYLUX-License/2.3",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=12) as response:
            final_url = response.geturl()
            _validated_https_url(final_url)
            length = response.headers.get("Content-Length")
            if length:
                try:
                    if int(length) > MAX_MANIFEST_BYTES:
                        raise HTTPException(status_code=503, detail="内容清单过大")
                except ValueError:
                    pass
            data = response.read(MAX_MANIFEST_BYTES + 1)
    except HTTPException:
        raise
    except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError, OSError) as exc:
        raise HTTPException(status_code=503, detail="无法从 GitHub 获取内容清单") from exc
    if not data or len(data) > MAX_MANIFEST_BYTES:
        raise HTTPException(status_code=503, detail="内容清单长度无效")
    return data


def _load_manifest(module_code: str) -> tuple[dict, dict, bytes]:
    _module_guard(module_code)
    raw = _fetch_manifest_bytes()
    try:
        outer = json.loads(raw.decode("utf-8"))
        if int(outer.get("schema", 0)) != 1:
            raise ValueError("schema")
        if outer.get("module") != module_code:
            raise ValueError("module")
        payload_bytes = base64.b64decode(
            str(outer["payload_b64"]).encode("ascii"), validate=True
        )
        if not payload_bytes or len(payload_bytes) > MAX_MANIFEST_BYTES:
            raise ValueError("payload size")
        payload = json.loads(payload_bytes.decode("utf-8"))
    except Exception as exc:
        raise HTTPException(status_code=503, detail="GitHub 内容清单无效") from exc
    if int(payload.get("schema", 0)) != 1 or payload.get("module") != module_code:
        raise HTTPException(status_code=503, detail="内容清单模块无效")
    return outer, payload, payload_bytes


def _validate_manifest_key(outer: dict, payload_bytes: bytes, key: bytes) -> None:
    expected_key_id = hashlib.sha256(key).hexdigest()[:16]
    if str(outer.get("key_id", "")).strip().lower() != expected_key_id:
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
    _module_guard(module_code)
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
    allowed: set[str] = set()
    linkspak = payload.get("linkspak")
    if isinstance(linkspak, dict):
        name = str(linkspak.get("stored_name", "linkspak.txt"))
        if name == "linkspak.txt":
            allowed.add(name)
    for item in payload.get("files") or []:
        if not isinstance(item, dict):
            continue
        name = str(item.get("encrypted_name", "")).strip()
        if name in PUBLIC_FILES:
            allowed.add(name)
    return allowed


def _public_download_url(file_name: str) -> str:
    base = _validated_https_url(DOWNLOAD_BASE_URL, require_trailing_slash=True)
    return base + urllib.parse.quote(file_name, safe="")


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

    allowed = _allowed_downloads(manifest_payload)
    if allowed != PUBLIC_FILES:
        raise HTTPException(status_code=503, detail="内容清单文件集合无效")

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
        "download_base_url": _validated_https_url(
            DOWNLOAD_BASE_URL, require_trailing_slash=True
        ),
    }


@router.get("/api/v1/content/{module_code}/files/{file_name}")
def protected_file(module_code: str, file_name: str):
    """Compatibility redirect for RYLUX 2.3 clients.

    The encrypted file is public ciphertext, so this endpoint intentionally does
    not gate the bytes with membership. Membership gates only the wrapped AES key
    returned by protected_manifest(). The response body itself comes directly
    from GitHub after Android follows this redirect, avoiding VPS content traffic.
    """
    _module_guard(module_code)
    if file_name not in PUBLIC_FILES:
        raise HTTPException(status_code=404, detail="资源不存在")
    response = RedirectResponse(_public_download_url(file_name), status_code=302)
    response.headers["Cache-Control"] = "no-store, max-age=0"
    response.headers["X-Content-Type-Options"] = "nosniff"
    return response
