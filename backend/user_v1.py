import base64
import hashlib
import hmac
import secrets
import sqlite3
from datetime import datetime, timedelta, timezone
from pathlib import Path
import os

from fastapi import APIRouter, Header, HTTPException, Request
from pydantic import BaseModel, Field

from admin_v2 import log_action, request_ip, require_csrf, require_ready

DB_PATH = Path(os.environ.get("PAKREDIRECT_LICENSE_DB", "./data/licenses.db")).resolve()
router = APIRouter()

PASSWORD_ITERATIONS = 310_000
SESSION_DAYS = 30
TRIAL_HOURS = 24
VIP_PRESETS = (1, 7, 30, 90, 180, 365)
TARGET_PACKAGE = "com.tepaylink.tamgioiphantranhmobile"


class RegisterPayload(BaseModel):
    username: str = Field(min_length=3, max_length=32)
    password: str = Field(min_length=6, max_length=128)
    device_id: str = Field(default="", max_length=256)


class LoginPayload(BaseModel):
    username: str = Field(min_length=1, max_length=32)
    password: str = Field(min_length=1, max_length=128)
    device_id: str = Field(default="", max_length=256)


class RedeemPayload(BaseModel):
    code: str = Field(min_length=4, max_length=128)


class UserTogglePayload(BaseModel):
    enabled: bool


class UserExtendPayload(BaseModel):
    days: int


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def iso(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def parse_iso(value: str | None):
    if not value:
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except Exception:
        return None


def open_db() -> sqlite3.Connection:
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    db = sqlite3.connect(DB_PATH, timeout=5)
    db.row_factory = sqlite3.Row
    db.execute("PRAGMA journal_mode=WAL")
    db.execute("PRAGMA busy_timeout=5000")
    db.execute("PRAGMA foreign_keys=ON")
    return db


def username_key(value: str) -> str:
    return value.strip().casefold()


def validate_username(value: str) -> str:
    value = value.strip()
    if not 3 <= len(value) <= 32:
        raise ValueError("用户名长度需为 3-32 个字符")
    if any(ch.isspace() for ch in value):
        raise ValueError("用户名不能包含空格")
    if any(ch in "/\\<>\"'`" for ch in value):
        raise ValueError("用户名包含不允许的字符")
    return value


def b64e(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).decode("ascii").rstrip("=")


def b64d(value: str) -> bytes:
    value += "=" * ((4 - len(value) % 4) % 4)
    return base64.urlsafe_b64decode(value.encode("ascii"))


def password_hash(password: str) -> str:
    if len(password) < 6:
        raise ValueError("密码至少 6 个字符")
    salt = secrets.token_bytes(18)
    digest = hashlib.pbkdf2_hmac(
        "sha256", password.encode("utf-8"), salt, PASSWORD_ITERATIONS
    )
    return f"pbkdf2_sha256${PASSWORD_ITERATIONS}${b64e(salt)}${b64e(digest)}"


def verify_password(encoded: str, password: str) -> bool:
    try:
        scheme, rounds_text, salt_text, digest_text = encoded.split("$", 3)
        rounds = int(rounds_text)
        if scheme != "pbkdf2_sha256" or not 100_000 <= rounds <= 2_000_000:
            return False
        expected = b64d(digest_text)
        actual = hashlib.pbkdf2_hmac(
            "sha256", password.encode("utf-8"), b64d(salt_text), rounds
        )
        return hmac.compare_digest(expected, actual)
    except Exception:
        return False


def digest_token(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def digest_device(value: str) -> str:
    value = (value or "").strip()
    if not value:
        return ""
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def digest_code(value: str) -> str:
    return hashlib.sha256(value.strip().upper().encode("utf-8")).hexdigest()


def init_user_v1() -> None:
    now = iso(utc_now())
    with open_db() as db:
        db.executescript(
            """
            CREATE TABLE IF NOT EXISTS app_users (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                username TEXT NOT NULL,
                username_key TEXT NOT NULL UNIQUE,
                password_hash TEXT NOT NULL,
                status INTEGER NOT NULL DEFAULT 1,
                vip_level INTEGER NOT NULL DEFAULT 1,
                vip_expires_at TEXT NOT NULL,
                trial_started_at TEXT NOT NULL,
                trial_expires_at TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                last_login_at TEXT,
                last_login_ip TEXT,
                last_device_hash TEXT,
                login_count INTEGER NOT NULL DEFAULT 0
            );

            CREATE TABLE IF NOT EXISTS app_sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id INTEGER NOT NULL,
                token_hash TEXT NOT NULL UNIQUE,
                created_at TEXT NOT NULL,
                expires_at TEXT NOT NULL,
                last_seen_at TEXT NOT NULL,
                ip_address TEXT NOT NULL DEFAULT '',
                device_hash TEXT NOT NULL DEFAULT '',
                revoked INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY (user_id) REFERENCES app_users(id) ON DELETE CASCADE
            );

            CREATE TABLE IF NOT EXISTS vip_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id INTEGER NOT NULL,
                source TEXT NOT NULL,
                duration_seconds INTEGER NOT NULL,
                reference TEXT NOT NULL DEFAULT '',
                old_expires_at TEXT,
                new_expires_at TEXT NOT NULL,
                created_at TEXT NOT NULL,
                FOREIGN KEY (user_id) REFERENCES app_users(id) ON DELETE CASCADE
            );

            CREATE TABLE IF NOT EXISTS module_access_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id INTEGER NOT NULL,
                module_code TEXT NOT NULL,
                allowed INTEGER NOT NULL,
                ip_address TEXT NOT NULL DEFAULT '',
                created_at TEXT NOT NULL,
                FOREIGN KEY (user_id) REFERENCES app_users(id) ON DELETE CASCADE
            );

            CREATE TABLE IF NOT EXISTS plans (
                code TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                days INTEGER NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 1,
                sort_order INTEGER NOT NULL DEFAULT 0
            );

            CREATE TABLE IF NOT EXISTS modules (
                code TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                package_name TEXT NOT NULL DEFAULT '',
                enabled INTEGER NOT NULL DEFAULT 1
            );

            CREATE INDEX IF NOT EXISTS idx_app_users_vip_expires ON app_users(vip_expires_at);
            CREATE INDEX IF NOT EXISTS idx_app_users_last_login_ip ON app_users(last_login_ip);
            CREATE INDEX IF NOT EXISTS idx_app_sessions_user ON app_sessions(user_id);
            CREATE INDEX IF NOT EXISTS idx_app_sessions_expires ON app_sessions(expires_at);
            CREATE INDEX IF NOT EXISTS idx_vip_events_user ON vip_events(user_id);
            CREATE INDEX IF NOT EXISTS idx_module_access_user ON module_access_logs(user_id);
            """
        )

        license_columns = {
            row["name"] for row in db.execute("PRAGMA table_info(licenses)").fetchall()
        }
        if "duration_days" not in license_columns:
            db.execute("ALTER TABLE licenses ADD COLUMN duration_days INTEGER")
        if "redeemed_by_user_id" not in license_columns:
            db.execute("ALTER TABLE licenses ADD COLUMN redeemed_by_user_id INTEGER")
        if "redeemed_at" not in license_columns:
            db.execute("ALTER TABLE licenses ADD COLUMN redeemed_at TEXT")

        plans = [
            ("7d", "7 天", 7, 1, 10),
            ("30d", "30 天", 30, 1, 20),
            ("90d", "90 天", 90, 1, 30),
            ("180d", "180 天", 180, 1, 40),
            ("365d", "365 天", 365, 1, 50),
        ]
        db.executemany(
            "INSERT OR IGNORE INTO plans(code,name,days,enabled,sort_order) VALUES(?,?,?,?,?)",
            plans,
        )
        db.execute(
            """
            INSERT OR IGNORE INTO modules(code,name,description,package_name,enabled)
            VALUES('sg_localization','三国汉化','RYLUX V1 本地汉化模块',?,1)
            """,
            (TARGET_PACKAGE,),
        )
        db.execute(
            "DELETE FROM app_sessions WHERE expires_at<=? OR revoked=1",
            (now,),
        )
        db.commit()


def create_session(db: sqlite3.Connection, user_id: int, ip: str, device_hash: str):
    token = secrets.token_urlsafe(36)
    now = utc_now()
    expires = now + timedelta(days=SESSION_DAYS)
    db.execute(
        """
        INSERT INTO app_sessions
            (user_id,token_hash,created_at,expires_at,last_seen_at,ip_address,device_hash,revoked)
        VALUES(?,?,?,?,?,?,?,0)
        """,
        (user_id, digest_token(token), iso(now), iso(expires), iso(now), ip[:64], device_hash),
    )
    return token, iso(expires)


def bearer_token(authorization: str | None) -> str:
    if not authorization:
        raise HTTPException(status_code=401, detail="请先登录")
    parts = authorization.strip().split(" ", 1)
    if len(parts) != 2 or parts[0].lower() != "bearer" or not parts[1].strip():
        raise HTTPException(status_code=401, detail="登录状态无效")
    return parts[1].strip()


def require_user(authorization: str | None):
    token = bearer_token(authorization)
    now = iso(utc_now())
    token_hash = digest_token(token)
    with open_db() as db:
        row = db.execute(
            """
            SELECT s.id AS session_id,s.user_id,s.expires_at,
                   u.username,u.status,u.vip_level,u.vip_expires_at,u.trial_expires_at,
                   u.last_login_at,u.last_login_ip
            FROM app_sessions s
            JOIN app_users u ON u.id=s.user_id
            WHERE s.token_hash=? AND s.revoked=0 AND s.expires_at>?
            LIMIT 1
            """,
            (token_hash, now),
        ).fetchone()
        if row is None:
            raise HTTPException(status_code=401, detail="登录已过期，请重新登录")
        if int(row["status"]) != 1:
            raise HTTPException(status_code=403, detail="账号已停用")
        db.execute(
            "UPDATE app_sessions SET last_seen_at=? WHERE id=?",
            (now, row["session_id"]),
        )
        db.commit()
        return dict(row), token_hash


def membership(user) -> dict:
    now = utc_now()
    vip_exp = parse_iso(user["vip_expires_at"])
    trial_exp = parse_iso(user["trial_expires_at"])
    active = bool(vip_exp and vip_exp > now)
    if active and trial_exp and vip_exp <= trial_exp + timedelta(seconds=1):
        kind = "trial"
    elif active:
        kind = "vip"
    else:
        kind = "expired"
    return {
        "active": active,
        "kind": kind,
        "vip_level": int(user.get("vip_level", 1) or 1),
        "expires_at": user["vip_expires_at"],
        "trial_expires_at": user["trial_expires_at"],
    }


def serialize_user(row) -> dict:
    user = dict(row)
    m = membership(user)
    return {
        "id": int(user["id"]),
        "username": user["username"],
        "enabled": bool(user["status"]),
        "membership": m,
        "created_at": user["created_at"],
        "last_login_at": user["last_login_at"],
        "last_login_ip": user["last_login_ip"] or "",
        "login_count": int(user["login_count"] or 0),
    }


def redeem_duration_days(row) -> int:
    if row["duration_days"] is not None:
        days = int(row["duration_days"])
        if days > 0:
            return days
    created = parse_iso(row["created_at"])
    expires = parse_iso(row["expires_at"])
    if not created or not expires or expires <= created:
        return 0
    seconds = (expires - created).total_seconds()
    days = max(1, int(round(seconds / 86400.0)))
    return days


@router.post("/api/v1/auth/register")
def register(payload: RegisterPayload, request: Request):
    try:
        username = validate_username(payload.username)
        encoded = password_hash(payload.password)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))

    now = utc_now()
    trial_expires = now + timedelta(hours=TRIAL_HOURS)
    ip = request_ip(request)
    device_hash = digest_device(payload.device_id)

    with open_db() as db:
        try:
            cur = db.execute(
                """
                INSERT INTO app_users
                    (username,username_key,password_hash,status,vip_level,vip_expires_at,
                     trial_started_at,trial_expires_at,created_at,updated_at,
                     last_login_at,last_login_ip,last_device_hash,login_count)
                VALUES(?,?,?,1,1,?,?,?,?,?,?,?,?,1)
                """,
                (
                    username,
                    username_key(username),
                    encoded,
                    iso(trial_expires),
                    iso(now),
                    iso(trial_expires),
                    iso(now),
                    iso(now),
                    iso(now),
                    ip[:64],
                    device_hash,
                ),
            )
        except sqlite3.IntegrityError:
            raise HTTPException(status_code=409, detail="用户名已存在")

        user_id = int(cur.lastrowid)
        db.execute(
            """
            INSERT INTO vip_events
                (user_id,source,duration_seconds,reference,old_expires_at,new_expires_at,created_at)
            VALUES(?,?,?,?,?,?,?)
            """,
            (user_id, "trial", TRIAL_HOURS * 3600, "new-user", None, iso(trial_expires), iso(now)),
        )
        token, session_expires = create_session(db, user_id, ip, device_hash)
        db.commit()

    return {
        "ok": True,
        "token": token,
        "session_expires_at": session_expires,
        "user": {
            "id": user_id,
            "username": username,
            "membership": {
                "active": True,
                "kind": "trial",
                "vip_level": 1,
                "expires_at": iso(trial_expires),
                "trial_expires_at": iso(trial_expires),
            },
        },
        "message": "注册成功，已获得 24 小时体验时间",
    }


@router.post("/api/v1/auth/login")
def login(payload: LoginPayload, request: Request):
    key = username_key(payload.username)
    ip = request_ip(request)
    device_hash = digest_device(payload.device_id)
    now = iso(utc_now())

    with open_db() as db:
        row = db.execute(
            "SELECT * FROM app_users WHERE username_key=? LIMIT 1",
            (key,),
        ).fetchone()
        if row is None or not verify_password(row["password_hash"], payload.password):
            raise HTTPException(status_code=401, detail="账号或密码错误")
        if int(row["status"]) != 1:
            raise HTTPException(status_code=403, detail="账号已停用")

        db.execute(
            """
            UPDATE app_users
            SET last_login_at=?,last_login_ip=?,last_device_hash=?,login_count=login_count+1,updated_at=?
            WHERE id=?
            """,
            (now, ip[:64], device_hash, now, row["id"]),
        )
        token, session_expires = create_session(db, int(row["id"]), ip, device_hash)
        db.commit()
        updated = db.execute("SELECT * FROM app_users WHERE id=?", (row["id"],)).fetchone()

    return {
        "ok": True,
        "token": token,
        "session_expires_at": session_expires,
        "user": serialize_user(updated),
        "message": "登录成功",
    }


@router.post("/api/v1/auth/logout")
def logout(authorization: str | None = Header(default=None)):
    token = bearer_token(authorization)
    with open_db() as db:
        db.execute(
            "UPDATE app_sessions SET revoked=1 WHERE token_hash=?",
            (digest_token(token),),
        )
        db.commit()
    return {"ok": True}


@router.get("/api/v1/me")
def me(authorization: str | None = Header(default=None)):
    auth, _ = require_user(authorization)
    with open_db() as db:
        row = db.execute("SELECT * FROM app_users WHERE id=?", (auth["user_id"],)).fetchone()
    return {"user": serialize_user(row)}


@router.get("/api/v1/plans")
def plans():
    with open_db() as db:
        rows = db.execute(
            "SELECT code,name,days FROM plans WHERE enabled=1 ORDER BY sort_order,id",
        ).fetchall()
    return {
        "purchase_enabled": False,
        "message": "V1 暂未开放在线支付，可使用兑换码充值",
        "plans": [dict(row) for row in rows],
    }


@router.get("/api/v1/modules")
def modules(authorization: str | None = Header(default=None)):
    auth, _ = require_user(authorization)
    m = membership(auth)
    with open_db() as db:
        rows = db.execute(
            "SELECT code,name,description,package_name FROM modules WHERE enabled=1 ORDER BY code"
        ).fetchall()
    return {
        "items": [
            {
                **dict(row),
                "authorized": bool(m["active"]),
                "expires_at": m["expires_at"],
            }
            for row in rows
        ]
    }


@router.post("/api/v1/modules/{module_code}/authorize")
def authorize_module(
    module_code: str,
    request: Request,
    authorization: str | None = Header(default=None),
):
    auth, _ = require_user(authorization)
    with open_db() as db:
        module = db.execute(
            "SELECT code,name,package_name,enabled FROM modules WHERE code=? LIMIT 1",
            (module_code,),
        ).fetchone()
        if module is None or int(module["enabled"]) != 1:
            raise HTTPException(status_code=404, detail="模块不存在或已停用")

        m = membership(auth)
        allowed = bool(m["active"])
        db.execute(
            """
            INSERT INTO module_access_logs(user_id,module_code,allowed,ip_address,created_at)
            VALUES(?,?,?,?,?)
            """,
            (auth["user_id"], module_code, 1 if allowed else 0, request_ip(request), iso(utc_now())),
        )
        db.commit()

    if not allowed:
        raise HTTPException(status_code=403, detail="体验或 VIP 已到期，请续费后使用")
    return {
        "allowed": True,
        "module": dict(module),
        "expires_at": m["expires_at"],
    }


@router.post("/api/v1/redeem")
def redeem(
    payload: RedeemPayload,
    request: Request,
    authorization: str | None = Header(default=None),
):
    auth, _ = require_user(authorization)
    now = utc_now()
    code_hash = digest_code(payload.code)

    with open_db() as db:
        try:
            db.execute("BEGIN IMMEDIATE")
            code = db.execute(
                """
                SELECT id,key_hash,key_hint,key_value,label,expires_at,enabled,created_at,
                       duration_days,redeemed_by_user_id,redeemed_at
                FROM licenses WHERE key_hash=? LIMIT 1
                """,
                (code_hash,),
            ).fetchone()
            if code is None:
                raise HTTPException(status_code=404, detail="兑换码无效")
            if int(code["enabled"]) != 1:
                if code["redeemed_at"]:
                    raise HTTPException(status_code=409, detail="兑换码已被使用")
                raise HTTPException(status_code=400, detail="兑换码已停用")
            if code["redeemed_at"] or code["redeemed_by_user_id"]:
                raise HTTPException(status_code=409, detail="兑换码已被使用")
            code_expires = parse_iso(code["expires_at"])
            if code_expires and code_expires <= now:
                raise HTTPException(status_code=400, detail="兑换码已过期")

            days = redeem_duration_days(code)
            if days <= 0:
                raise HTTPException(status_code=400, detail="兑换码没有可用的充值时长")

            user = db.execute(
                "SELECT * FROM app_users WHERE id=? LIMIT 1",
                (auth["user_id"],),
            ).fetchone()
            old_exp = parse_iso(user["vip_expires_at"])
            base = old_exp if old_exp and old_exp > now else now
            new_exp = base + timedelta(days=days)

            db.execute(
                "UPDATE app_users SET vip_expires_at=?,vip_level=1,updated_at=? WHERE id=?",
                (iso(new_exp), iso(now), auth["user_id"]),
            )
            db.execute(
                """
                UPDATE licenses
                SET duration_days=?,redeemed_by_user_id=?,redeemed_at=?,enabled=0
                WHERE id=? AND redeemed_at IS NULL
                """,
                (days, auth["user_id"], iso(now), code["id"]),
            )
            db.execute(
                """
                INSERT INTO vip_events
                    (user_id,source,duration_seconds,reference,old_expires_at,new_expires_at,created_at)
                VALUES(?,?,?,?,?,?,?)
                """,
                (
                    auth["user_id"],
                    "redeem",
                    days * 86400,
                    f"license:{code['id']}",
                    user["vip_expires_at"],
                    iso(new_exp),
                    iso(now),
                ),
            )
            db.commit()
        except HTTPException:
            db.rollback()
            raise
        except Exception:
            db.rollback()
            raise HTTPException(status_code=500, detail="兑换失败，请稍后重试")

    return {
        "ok": True,
        "days": days,
        "expires_at": iso(new_exp),
        "message": f"兑换成功，已增加 {days} 天使用时间",
    }


@router.get("/admin/api/users")
def admin_users(
    request: Request,
    q: str = "",
    status: str = "",
    page: int = 1,
    page_size: int = 30,
):
    require_ready(request)
    page = max(1, min(page, 100000))
    page_size = max(10, min(page_size, 100))
    status = status if status in ("", "active", "expired", "disabled") else ""
    cond = []
    params = []
    if q.strip():
        like = f"%{q.strip()}%"
        cond.append("(username LIKE ? COLLATE NOCASE OR COALESCE(last_login_ip,'') LIKE ? COLLATE NOCASE)")
        params.extend([like, like])
    now = iso(utc_now())
    if status == "active":
        cond.append("status=1 AND vip_expires_at>?")
        params.append(now)
    elif status == "expired":
        cond.append("vip_expires_at<=?")
        params.append(now)
    elif status == "disabled":
        cond.append("status=0")
    where = (" WHERE " + " AND ".join(cond)) if cond else ""
    offset = (page - 1) * page_size
    with open_db() as db:
        total = int(db.execute("SELECT COUNT(*) AS c FROM app_users" + where, tuple(params)).fetchone()["c"])
        rows = db.execute(
            "SELECT * FROM app_users" + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
            tuple(params + [page_size, offset]),
        ).fetchall()
    return {
        "items": [serialize_user(row) for row in rows],
        "total": total,
        "page": page,
        "page_size": page_size,
        "pages": max(1, (total + page_size - 1) // page_size),
    }


@router.get("/admin/api/user-stats")
def admin_user_stats(request: Request):
    require_ready(request)
    now = iso(utc_now())
    today = utc_now().date().isoformat() + "%"
    with open_db() as db:
        row = db.execute(
            """
            SELECT COUNT(*) AS total,
                   SUM(CASE WHEN status=1 AND vip_expires_at>? THEN 1 ELSE 0 END) AS active,
                   SUM(CASE WHEN vip_expires_at<=? THEN 1 ELSE 0 END) AS expired,
                   SUM(CASE WHEN status=0 THEN 1 ELSE 0 END) AS disabled,
                   SUM(CASE WHEN created_at LIKE ? THEN 1 ELSE 0 END) AS registered_today
            FROM app_users
            """,
            (now, now, today),
        ).fetchone()
    return {key: int(row[key] or 0) for key in ("total", "active", "expired", "disabled", "registered_today")}


@router.post("/admin/api/users/{user_id}/toggle")
def admin_user_toggle(user_id: int, payload: UserTogglePayload, request: Request):
    token = require_ready(request)
    require_csrf(request, token)
    with open_db() as db:
        cur = db.execute(
            "UPDATE app_users SET status=?,updated_at=? WHERE id=?",
            (1 if payload.enabled else 0, iso(utc_now()), user_id),
        )
        db.commit()
    if cur.rowcount != 1:
        raise HTTPException(status_code=404, detail="用户不存在")
    log_action(
        "user_enabled" if payload.enabled else "user_disabled",
        f"user:{user_id}",
        "",
        request_ip(request),
    )
    return {"ok": True}


@router.post("/admin/api/users/{user_id}/extend")
def admin_user_extend(user_id: int, payload: UserExtendPayload, request: Request):
    token = require_ready(request)
    require_csrf(request, token)
    if payload.days not in VIP_PRESETS:
        raise HTTPException(status_code=400, detail="续期天数不在允许范围")
    now = utc_now()
    with open_db() as db:
        row = db.execute("SELECT * FROM app_users WHERE id=?", (user_id,)).fetchone()
        if row is None:
            raise HTTPException(status_code=404, detail="用户不存在")
        old_exp = parse_iso(row["vip_expires_at"])
        base = old_exp if old_exp and old_exp > now else now
        new_exp = base + timedelta(days=payload.days)
        db.execute(
            "UPDATE app_users SET vip_expires_at=?,updated_at=? WHERE id=?",
            (iso(new_exp), iso(now), user_id),
        )
        db.execute(
            """
            INSERT INTO vip_events
                (user_id,source,duration_seconds,reference,old_expires_at,new_expires_at,created_at)
            VALUES(?,?,?,?,?,?,?)
            """,
            (user_id, "admin", payload.days * 86400, "admin-console", row["vip_expires_at"], iso(new_exp), iso(now)),
        )
        db.commit()
    log_action(
        "user_vip_extended",
        f"user:{user_id}",
        f"+{payload.days}天",
        request_ip(request),
    )
    return {"ok": True, "expires_at": iso(new_exp)}
