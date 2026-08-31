import hashlib
import sqlite3
from datetime import timedelta

from fastapi import APIRouter, HTTPException, Request

from admin_v2 import request_ip
from user_v1 import (
    RegisterPayload,
    TRIAL_HOURS,
    create_session,
    digest_device,
    iso,
    open_db,
    password_hash,
    username_key,
    utc_now,
    validate_username,
)

router = APIRouter()

# Device identity is the primary anti-abuse signal. IP is only an auxiliary
# risk signal so shared Wi-Fi / carrier NAT does not block account creation.
IP_TRIAL_WINDOW_HOURS = 48
IP_TRIAL_MAX_AWARDS = 3


def digest_ip(value: str) -> str:
    value = (value or "").strip()
    if not value:
        return ""
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def init_registration_guard_v1() -> None:
    with open_db() as db:
        columns = {row["name"] for row in db.execute("PRAGMA table_info(app_users)").fetchall()}
        if "registration_ip_hash" not in columns:
            db.execute("ALTER TABLE app_users ADD COLUMN registration_ip_hash TEXT")
        db.execute(
            "CREATE INDEX IF NOT EXISTS idx_app_users_registration_ip_hash ON app_users(registration_ip_hash)"
        )

        db.executescript(
            """
            CREATE TABLE IF NOT EXISTS trial_claims (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id INTEGER NOT NULL UNIQUE,
                device_hash TEXT NOT NULL DEFAULT '',
                ip_hash TEXT NOT NULL DEFAULT '',
                awarded INTEGER NOT NULL DEFAULT 0,
                created_at TEXT NOT NULL,
                FOREIGN KEY (user_id) REFERENCES app_users(id) ON DELETE CASCADE
            );
            CREATE INDEX IF NOT EXISTS idx_trial_claims_device_awarded
                ON trial_claims(device_hash, awarded);
            CREATE INDEX IF NOT EXISTS idx_trial_claims_ip_created
                ON trial_claims(ip_hash, created_at, awarded);
            """
        )

        # Preserve old registrations when upgrading: an account that previously
        # received trial time is treated as an already-used device trial where
        # a device hash is available. This prevents an upgrade from reopening
        # free-trial eligibility for existing devices.
        rows = db.execute(
            """
            SELECT id,last_device_hash,registration_ip_hash,
                   trial_started_at,trial_expires_at,created_at
            FROM app_users
            WHERE id NOT IN (SELECT user_id FROM trial_claims)
            """
        ).fetchall()
        for row in rows:
            trial_awarded = int(
                bool(row["trial_expires_at"])
                and bool(row["trial_started_at"])
                and row["trial_expires_at"] > row["trial_started_at"]
            )
            db.execute(
                """
                INSERT OR IGNORE INTO trial_claims
                    (user_id,device_hash,ip_hash,awarded,created_at)
                VALUES(?,?,?,?,?)
                """,
                (
                    row["id"],
                    (row["last_device_hash"] or "").strip(),
                    (row["registration_ip_hash"] or "").strip(),
                    trial_awarded,
                    row["created_at"],
                ),
            )

        db.execute(
            """
            UPDATE modules
            SET name='封神榜汉化', description='RYLUX 本地汉化模块'
            WHERE code='sg_localization'
            """
        )
        db.commit()


def _trial_allowed(db, device_hash: str, ip_hash: str, cutoff_text: str) -> bool:
    # A missing device identifier is not trusted for free-trial issuance, but
    # it never blocks account registration.
    if not device_hash:
        return False

    used_device = db.execute(
        """
        SELECT 1 FROM trial_claims
        WHERE device_hash=? AND awarded=1
        LIMIT 1
        """,
        (device_hash,),
    ).fetchone()
    if used_device is not None:
        return False

    # IP is auxiliary only. Several legitimate devices may share one public IP,
    # so a single prior registration must not deny a new user's trial.
    if ip_hash:
        recent_ip_awards = db.execute(
            """
            SELECT COUNT(*) AS n FROM trial_claims
            WHERE ip_hash=? AND awarded=1 AND created_at>?
            """,
            (ip_hash, cutoff_text),
        ).fetchone()
        if recent_ip_awards is not None and int(recent_ip_awards["n"] or 0) >= IP_TRIAL_MAX_AWARDS:
            return False

    return True


@router.post("/api/v1/auth/register")
def guarded_register(payload: RegisterPayload, request: Request):
    try:
        username = validate_username(payload.username)
        encoded = password_hash(payload.password)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))

    now = utc_now()
    cutoff = now - timedelta(hours=IP_TRIAL_WINDOW_HOURS)
    ip = request_ip(request).strip()
    ip_hash = digest_ip(ip)
    device_hash = digest_device(payload.device_id)

    with open_db() as db:
        try:
            db.execute("BEGIN IMMEDIATE")
            trial_allowed = _trial_allowed(db, device_hash, ip_hash, iso(cutoff))
            trial_expires = now + timedelta(hours=TRIAL_HOURS) if trial_allowed else now

            cur = db.execute(
                """
                INSERT INTO app_users
                    (username,username_key,password_hash,status,vip_level,vip_expires_at,
                     trial_started_at,trial_expires_at,created_at,updated_at,
                     last_login_at,last_login_ip,last_device_hash,login_count,registration_ip_hash)
                VALUES(?,?,?,1,1,?,?,?,?,?,?,?,?,1,?)
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
                    ip_hash,
                ),
            )
        except sqlite3.IntegrityError:
            db.rollback()
            raise HTTPException(status_code=409, detail="用户名已存在")

        user_id = int(cur.lastrowid)
        db.execute(
            """
            INSERT INTO trial_claims
                (user_id,device_hash,ip_hash,awarded,created_at)
            VALUES(?,?,?,?,?)
            """,
            (user_id, device_hash, ip_hash, 1 if trial_allowed else 0, iso(now)),
        )

        if trial_allowed:
            db.execute(
                """
                INSERT INTO vip_events
                    (user_id,source,duration_seconds,reference,old_expires_at,new_expires_at,created_at)
                VALUES(?,?,?,?,?,?,?)
                """,
                (
                    user_id,
                    "trial",
                    TRIAL_HOURS * 3600,
                    "new-user",
                    None,
                    iso(trial_expires),
                    iso(now),
                ),
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
                "active": trial_allowed,
                "kind": "trial" if trial_allowed else "expired",
                "vip_level": 1,
                "expires_at": iso(trial_expires),
                "trial_expires_at": iso(trial_expires),
            },
        },
        "message": (
            "注册成功，已获得 24 小时体验时间"
            if trial_allowed
            else "注册成功，请登录后查看账号状态"
        ),
    }
