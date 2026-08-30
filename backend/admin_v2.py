import base64
import csv
import hashlib
import hmac
import io
import os
import secrets
import sqlite3
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import FileResponse, JSONResponse, RedirectResponse, Response
from pydantic import BaseModel, Field

DB_PATH = Path(os.environ.get("PAKREDIRECT_LICENSE_DB", "./data/licenses.db")).resolve()
WEB_DIR = Path(__file__).with_name("admin_web")
COOKIE = "pakredirect_admin"
SESSION_SECONDS = 8 * 60 * 60
MAX_BATCH = 1000
PRESETS = (1, 7, 30, 90, 180, 360)
PBKDF2_ITERATIONS = 310_000
DEFAULT_ADMIN_USER = "admin"
DEFAULT_ADMIN_PASSWORD_HASH = (
    "pbkdf2_sha256$310000$UGFrUmVkaXJlY3RCb290c3RyYXA$"
    "ir6hc4X3vpfLe1L9df89hGhcTbVnbcCQWrksMxcv248"
)

router = APIRouter()


class LoginPayload(BaseModel):
    username: str = Field(min_length=1, max_length=64)
    password: str = Field(min_length=1, max_length=256)


class CredentialPayload(BaseModel):
    current_password: str = Field(min_length=1, max_length=256)
    username: str = Field(min_length=3, max_length=32)
    new_password: str = Field(min_length=10, max_length=256)
    confirm_password: str = Field(min_length=10, max_length=256)


class GeneratePayload(BaseModel):
    days: int
    quantity: int = Field(default=1, ge=1, le=MAX_BATCH)
    label: str = Field(default="", max_length=80)


class ExtendPayload(BaseModel):
    days: int


class TogglePayload(BaseModel):
    enabled: bool


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def iso(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def normalize_key(value: str) -> str:
    return value.strip().upper()


def key_hash(value: str) -> str:
    return hashlib.sha256(normalize_key(value).encode("utf-8")).hexdigest()


def key_hint(value: str) -> str:
    compact = normalize_key(value).replace("-", "")
    return compact[-6:] if len(compact) >= 6 else compact


def generate_key() -> str:
    raw = secrets.token_hex(10).upper()
    return "PR-" + "-".join(raw[i:i + 4] for i in range(0, len(raw), 4))


def open_db() -> sqlite3.Connection:
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    db = sqlite3.connect(DB_PATH, timeout=5)
    db.row_factory = sqlite3.Row
    db.execute("PRAGMA journal_mode=WAL")
    db.execute("PRAGMA busy_timeout=5000")
    db.execute("PRAGMA foreign_keys=ON")
    return db


def init_admin() -> None:
    with open_db() as db:
        db.execute("CREATE INDEX IF NOT EXISTS idx_licenses_key_hint ON licenses(key_hint)")
        db.execute("CREATE INDEX IF NOT EXISTS idx_licenses_last_seen_ip ON licenses(last_seen_ip)")
        db.execute(
            """
            CREATE TABLE IF NOT EXISTS admin_settings (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                username TEXT NOT NULL,
                password_hash TEXT NOT NULL,
                must_change_password INTEGER NOT NULL DEFAULT 1,
                session_secret TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """
        )
        db.execute(
            """
            CREATE TABLE IF NOT EXISTS admin_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                action TEXT NOT NULL,
                target TEXT NOT NULL DEFAULT '',
                details TEXT NOT NULL DEFAULT '',
                ip_address TEXT NOT NULL DEFAULT '',
                created_at TEXT NOT NULL
            )
            """
        )
        db.execute(
            "CREATE INDEX IF NOT EXISTS idx_admin_logs_created_at ON admin_logs(created_at)"
        )
        row = db.execute("SELECT id FROM admin_settings WHERE id=1").fetchone()
        if row is None:
            db.execute(
                """
                INSERT INTO admin_settings
                    (id, username, password_hash, must_change_password, session_secret, updated_at)
                VALUES (1, ?, ?, 1, ?, ?)
                """,
                (
                    DEFAULT_ADMIN_USER,
                    DEFAULT_ADMIN_PASSWORD_HASH,
                    secrets.token_urlsafe(48),
                    iso(utc_now()),
                ),
            )
        db.commit()


def admin_config():
    with open_db() as db:
        return db.execute(
            """
            SELECT username,password_hash,must_change_password,session_secret,updated_at
            FROM admin_settings WHERE id=1
            """
        ).fetchone()


def b64e(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).decode("ascii").rstrip("=")


def b64d(value: str) -> bytes:
    value += "=" * ((4 - len(value) % 4) % 4)
    return base64.urlsafe_b64decode(value.encode("ascii"))


def make_password_hash(password: str) -> str:
    if len(password) < 10:
        raise ValueError("新密码至少 10 个字符")
    salt = secrets.token_bytes(18)
    digest = hashlib.pbkdf2_hmac(
        "sha256", password.encode("utf-8"), salt, PBKDF2_ITERATIONS
    )
    return f"pbkdf2_sha256${PBKDF2_ITERATIONS}${b64e(salt)}${b64e(digest)}"


def verify_hash(encoded: str, password: str) -> bool:
    try:
        scheme, iterations_text, salt_text, digest_text = encoded.split("$", 3)
        iterations = int(iterations_text)
        if scheme != "pbkdf2_sha256" or not 100000 <= iterations <= 2000000:
            return False
        salt, expected = b64d(salt_text), b64d(digest_text)
        actual = hashlib.pbkdf2_hmac(
            "sha256", password.encode("utf-8"), salt, iterations
        )
        return hmac.compare_digest(actual, expected)
    except Exception:
        return False


def verify_password(password: str) -> bool:
    row = admin_config()
    return bool(row and verify_hash(row["password_hash"], password))


def new_session() -> str:
    row = admin_config()
    if not row:
        raise RuntimeError("管理员配置不存在")
    expires = int(time.time()) + SESSION_SECONDS
    payload = b64e(
        f"{row['username']}|{expires}|{secrets.token_hex(12)}".encode("utf-8")
    )
    sig = b64e(
        hmac.new(
            row["session_secret"].encode("utf-8"),
            payload.encode("ascii"),
            hashlib.sha256,
        ).digest()
    )
    return payload + "." + sig


def session_user(token: str | None):
    if not token:
        return None
    row = admin_config()
    if not row:
        return None
    try:
        payload, sig = token.split(".", 1)
        expected = hmac.new(
            row["session_secret"].encode("utf-8"),
            payload.encode("ascii"),
            hashlib.sha256,
        ).digest()
        if not hmac.compare_digest(expected, b64d(sig)):
            return None
        username, expires, _nonce = b64d(payload).decode("utf-8").split("|", 2)
        if username != row["username"] or int(expires) < int(time.time()):
            return None
        return username
    except Exception:
        return None


def csrf(token: str) -> str:
    row = admin_config()
    if not row:
        return ""
    return hmac.new(
        row["session_secret"].encode("utf-8"),
        ("csrf|" + token).encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()


def request_ip(request: Request) -> str:
    return (
        request.headers.get("x-real-ip")
        or (request.client.host if request.client else "")
        or ""
    )[:64]


def log_action(action: str, target: str = "", details: str = "", ip: str = "") -> None:
    with open_db() as db:
        db.execute(
            """
            INSERT INTO admin_logs(action,target,details,ip_address,created_at)
            VALUES(?,?,?,?,?)
            """,
            (action[:80], target[:120], details[:500], ip[:64], iso(utc_now())),
        )
        db.commit()


def auth_token(request: Request) -> str | None:
    token = request.cookies.get(COOKIE)
    return token if session_user(token) else None


def require_auth(request: Request) -> str:
    token = auth_token(request)
    if not token:
        raise HTTPException(status_code=401, detail="未登录或登录已过期")
    return token


def require_csrf(request: Request, token: str) -> None:
    provided = request.headers.get("x-csrf-token", "")
    if not provided or not hmac.compare_digest(csrf(token), provided):
        raise HTTPException(status_code=403, detail="CSRF 校验失败")


def must_change_password() -> bool:
    row = admin_config()
    return bool(row and int(row["must_change_password"]) == 1)


def parse_iso(value: str | None):
    if not value:
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None


def license_status(row) -> str:
    if int(row["enabled"]) != 1:
        return "disabled"
    exp = parse_iso(row["expires_at"])
    if exp is None or exp <= utc_now():
        return "expired"
    return "active"


def serialize_license(row):
    return {
        "id": int(row["id"]),
        "key_hint": row["key_hint"],
        "label": row["label"] or "",
        "expires_at": row["expires_at"],
        "enabled": bool(row["enabled"]),
        "status": license_status(row),
        "created_at": row["created_at"] if "created_at" in row.keys() else None,
        "last_seen_at": row["last_seen_at"],
        "last_seen_ip": row["last_seen_ip"] or "",
    }


def stats_snapshot():
    now = iso(utc_now())
    today = utc_now().date().isoformat()
    with open_db() as db:
        row = db.execute(
            """
            SELECT
              COUNT(*) AS total,
              SUM(CASE WHEN enabled=1 AND expires_at>? THEN 1 ELSE 0 END) AS active,
              SUM(CASE WHEN enabled=0 THEN 1 ELSE 0 END) AS disabled,
              SUM(CASE WHEN expires_at<=? THEN 1 ELSE 0 END) AS expired,
              SUM(CASE WHEN last_seen_at LIKE ? THEN 1 ELSE 0 END) AS verified_today
            FROM licenses
            """,
            (now, now, today + "%"),
        ).fetchone()
        recent = db.execute(
            """
            SELECT id,key_hint,label,expires_at,enabled,created_at,last_seen_at,last_seen_ip
            FROM licenses
            WHERE last_seen_at IS NOT NULL
            ORDER BY last_seen_at DESC
            LIMIT 8
            """
        ).fetchall()
    stats = {
        key: int(row[key] or 0)
        for key in ("total", "active", "disabled", "expired", "verified_today")
    }
    return stats, [serialize_license(r) for r in recent]


def list_licenses(query: str, state: str, page: int, page_size: int):
    cond, params = [], []
    q = query.strip()
    if q:
        like = f"%{q}%"
        parts = [
            "label LIKE ? COLLATE NOCASE",
            "key_hint LIKE ? COLLATE NOCASE",
            "COALESCE(last_seen_ip,'') LIKE ? COLLATE NOCASE",
        ]
        params.extend([like, like, like])
        if normalize_key(q).startswith("PR-"):
            parts.append("key_hash = ?")
            params.append(key_hash(q))
        cond.append("(" + " OR ".join(parts) + ")")
    now = iso(utc_now())
    if state == "active":
        cond.append("enabled=1 AND expires_at>?")
        params.append(now)
    elif state == "disabled":
        cond.append("enabled=0")
    elif state == "expired":
        cond.append("expires_at<=?")
        params.append(now)

    where = (" WHERE " + " AND ".join(cond)) if cond else ""
    offset = (page - 1) * page_size
    with open_db() as db:
        total = db.execute(
            "SELECT COUNT(*) AS c FROM licenses" + where, tuple(params)
        ).fetchone()["c"]
        rows = db.execute(
            """
            SELECT id,key_hint,label,expires_at,enabled,created_at,last_seen_at,last_seen_ip
            FROM licenses
            """
            + where
            + " ORDER BY id DESC LIMIT ? OFFSET ?",
            tuple(params + [page_size, offset]),
        ).fetchall()
    return [serialize_license(r) for r in rows], int(total)


def create_licenses(days: int, quantity: int, label: str):
    if days not in PRESETS:
        raise ValueError("有效期不在允许范围")
    if not 1 <= quantity <= MAX_BATCH:
        raise ValueError(f"生成数量必须在 1 到 {MAX_BATCH} 之间")
    now = utc_now()
    expires = now + timedelta(days=days)
    made = []
    with open_db() as db:
        for _ in range(quantity):
            for _try in range(8):
                value = generate_key()
                try:
                    db.execute(
                        """
                        INSERT INTO licenses
                            (key_hash,key_hint,label,expires_at,enabled,created_at)
                        VALUES(?,?,?,?,1,?)
                        """,
                        (
                            key_hash(value),
                            key_hint(value),
                            label.strip()[:80],
                            iso(expires),
                            iso(now),
                        ),
                    )
                    made.append(value)
                    break
                except sqlite3.IntegrityError:
                    continue
            else:
                db.rollback()
                raise RuntimeError("生成唯一卡密失败")
        db.commit()
    return made, iso(expires)


def no_store(response: Response) -> Response:
    response.headers["Cache-Control"] = "no-store, no-cache, must-revalidate"
    response.headers["Pragma"] = "no-cache"
    response.headers["X-Frame-Options"] = "DENY"
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["Referrer-Policy"] = "same-origin"
    response.headers["Content-Security-Policy"] = (
        "default-src 'self'; "
        "style-src 'self'; script-src 'self'; img-src 'self' data:; "
        "connect-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'"
    )
    return response


@router.get("/admin/login")
def admin_login_page(request: Request):
    if auth_token(request):
        return RedirectResponse("/admin", status_code=303)
    return no_store(FileResponse(WEB_DIR / "login.html"))


@router.get("/admin")
def admin_page(request: Request):
    if not auth_token(request):
        return RedirectResponse("/admin/login", status_code=303)
    return no_store(FileResponse(WEB_DIR / "index.html"))


@router.get("/admin/assets/{name}")
def admin_asset(name: str):
    allowed = {"styles.css", "app.js", "login.js"}
    if name not in allowed:
        raise HTTPException(status_code=404)
    path = WEB_DIR / name
    if not path.exists():
        raise HTTPException(status_code=404)
    media = "text/css" if name.endswith(".css") else "application/javascript"
    return Response(path.read_bytes(), media_type=media, headers={"Cache-Control": "no-store"})


@router.post("/admin/api/login")
def admin_login(payload: LoginPayload, request: Request):
    row = admin_config()
    if not row:
        raise HTTPException(status_code=503, detail="后台初始化失败")
    user_ok = hmac.compare_digest(
        payload.username.encode("utf-8"), row["username"].encode("utf-8")
    )
    if not (user_ok and verify_password(payload.password)):
        log_action("login_failed", payload.username, "账号或密码错误", request_ip(request))
        raise HTTPException(status_code=401, detail="账号或密码错误")

    token = new_session()
    log_action("login_success", row["username"], "", request_ip(request))
    response = JSONResponse(
        {
            "ok": True,
            "must_change_password": must_change_password(),
            "redirect": "/admin",
        }
    )
    response.set_cookie(
        COOKIE,
        token,
        max_age=SESSION_SECONDS,
        httponly=True,
        secure=True,
        samesite="lax",
        path="/admin",
    )
    return no_store(response)


@router.post("/admin/api/logout")
def admin_logout(request: Request):
    token = require_auth(request)
    require_csrf(request, token)
    user = session_user(token) or ""
    log_action("logout", user, "", request_ip(request))
    response = JSONResponse({"ok": True})
    response.delete_cookie(COOKIE, path="/admin")
    return no_store(response)


@router.get("/admin/api/me")
def admin_me(request: Request):
    token = require_auth(request)
    row = admin_config()
    return {
        "username": row["username"],
        "must_change_password": bool(row["must_change_password"]),
        "csrf": csrf(token),
        "session_expires_seconds": SESSION_SECONDS,
    }


@router.post("/admin/api/credentials")
def admin_credentials(payload: CredentialPayload, request: Request):
    token = require_auth(request)
    require_csrf(request, token)
    if not verify_password(payload.current_password):
        raise HTTPException(status_code=400, detail="当前密码错误")
    username = payload.username.strip()
    if not 3 <= len(username) <= 32 or any(ch.isspace() for ch in username):
        raise HTTPException(status_code=400, detail="管理员账号需为3-32字符且不能包含空格")
    if payload.new_password != payload.confirm_password:
        raise HTTPException(status_code=400, detail="两次输入的新密码不一致")

    password_hash = make_password_hash(payload.new_password)
    new_secret = secrets.token_urlsafe(48)
    with open_db() as db:
        db.execute(
            """
            UPDATE admin_settings
            SET username=?,password_hash=?,must_change_password=0,session_secret=?,updated_at=?
            WHERE id=1
            """,
            (username, password_hash, new_secret, iso(utc_now())),
        )
        db.commit()
    log_action("credentials_changed", username, "管理员凭据已修改", request_ip(request))

    new_token = new_session()
    response = JSONResponse({"ok": True, "username": username})
    response.set_cookie(
        COOKIE,
        new_token,
        max_age=SESSION_SECONDS,
        httponly=True,
        secure=True,
        samesite="lax",
        path="/admin",
    )
    return no_store(response)


def require_ready(request: Request):
    token = require_auth(request)
    if must_change_password():
        raise HTTPException(status_code=428, detail="首次登录请先修改管理员账号和密码")
    return token


@router.get("/admin/api/overview")
def admin_overview(request: Request):
    require_ready(request)
    stats, recent = stats_snapshot()
    return {"stats": stats, "recent": recent}


@router.get("/admin/api/licenses")
def admin_licenses(
    request: Request,
    q: str = "",
    status: str = "",
    page: int = 1,
    page_size: int = 30,
):
    require_ready(request)
    if status not in ("", "active", "disabled", "expired"):
        status = ""
    page = max(1, min(page, 100000))
    page_size = max(10, min(page_size, 100))
    rows, total = list_licenses(q[:128], status, page, page_size)
    return {
        "items": rows,
        "total": total,
        "page": page,
        "page_size": page_size,
        "pages": max(1, (total + page_size - 1) // page_size),
    }


@router.post("/admin/api/licenses/generate")
def admin_generate(payload: GeneratePayload, request: Request):
    token = require_ready(request)
    require_csrf(request, token)
    try:
        keys, expires_at = create_licenses(payload.days, payload.quantity, payload.label)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))
    log_action(
        "licenses_generated",
        f"{payload.quantity} 张",
        f"{payload.days}天; 标签={payload.label[:80]}",
        request_ip(request),
    )
    return {"ok": True, "keys": keys, "expires_at": expires_at}


@router.post("/admin/api/licenses/{license_id}/toggle")
def admin_toggle(license_id: int, payload: TogglePayload, request: Request):
    token = require_ready(request)
    require_csrf(request, token)
    with open_db() as db:
        cur = db.execute(
            "UPDATE licenses SET enabled=? WHERE id=?",
            (1 if payload.enabled else 0, license_id),
        )
        db.commit()
    if cur.rowcount != 1:
        raise HTTPException(status_code=404, detail="未找到卡密")
    log_action(
        "license_enabled" if payload.enabled else "license_disabled",
        f"license:{license_id}",
        "",
        request_ip(request),
    )
    return {"ok": True}


@router.post("/admin/api/licenses/{license_id}/extend")
def admin_extend(license_id: int, payload: ExtendPayload, request: Request):
    token = require_ready(request)
    require_csrf(request, token)
    if payload.days not in PRESETS:
        raise HTTPException(status_code=400, detail="续期天数不在允许范围")
    with open_db() as db:
        row = db.execute(
            "SELECT expires_at FROM licenses WHERE id=?", (license_id,)
        ).fetchone()
        if row is None:
            raise HTTPException(status_code=404, detail="未找到卡密")
        current = parse_iso(row["expires_at"])
        if current is None:
            raise HTTPException(status_code=500, detail="卡密到期时间异常")
        new_expiry = max(current, utc_now()) + timedelta(days=payload.days)
        db.execute(
            "UPDATE licenses SET expires_at=? WHERE id=?",
            (iso(new_expiry), license_id),
        )
        db.commit()
    log_action(
        "license_extended",
        f"license:{license_id}",
        f"+{payload.days}天",
        request_ip(request),
    )
    return {"ok": True, "expires_at": iso(new_expiry)}


@router.get("/admin/api/logs")
def admin_logs(request: Request, page: int = 1, page_size: int = 40):
    require_ready(request)
    page = max(1, page)
    page_size = max(10, min(page_size, 100))
    offset = (page - 1) * page_size
    with open_db() as db:
        total = db.execute("SELECT COUNT(*) AS c FROM admin_logs").fetchone()["c"]
        rows = db.execute(
            """
            SELECT id,action,target,details,ip_address,created_at
            FROM admin_logs
            ORDER BY id DESC
            LIMIT ? OFFSET ?
            """,
            (page_size, offset),
        ).fetchall()
    return {
        "items": [dict(r) for r in rows],
        "total": int(total),
        "page": page,
        "page_size": page_size,
        "pages": max(1, (int(total) + page_size - 1) // page_size),
    }


@router.get("/admin/api/licenses/export.csv")
def admin_export_csv(request: Request, q: str = "", status: str = ""):
    require_ready(request)
    if status not in ("", "active", "disabled", "expired"):
        status = ""
    rows, _ = list_licenses(q[:128], status, 1, 100000)
    stream = io.StringIO()
    writer = csv.writer(stream)
    writer.writerow(
        ["ID", "卡密后6位", "标签", "状态", "到期时间", "最后验证", "最后IP"]
    )
    for row in rows:
        writer.writerow(
            [
                row["id"],
                row["key_hint"],
                row["label"],
                row["status"],
                row["expires_at"],
                row["last_seen_at"] or "",
                row["last_seen_ip"],
            ]
        )
    data = "\ufeff" + stream.getvalue()
    return Response(
        data,
        media_type="text/csv; charset=utf-8",
        headers={
            "Content-Disposition": 'attachment; filename="pakredirect-licenses.csv"',
            "Cache-Control": "no-store",
        },
    )
