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
REGISTRATION_WINDOW_HOURS = 48


def digest_ip(value: str) -> str:
    return hashlib.sha256(value.strip().encode("utf-8")).hexdigest()


def init_registration_guard_v1() -> None:
    cutoff = iso(utc_now() - timedelta(hours=REGISTRATION_WINDOW_HOURS))
    with open_db() as db:
        columns = {row["name"] for row in db.execute("PRAGMA table_info(app_users)").fetchall()}
        if "registration_ip_hash" not in columns:
            db.execute("ALTER TABLE app_users ADD COLUMN registration_ip_hash TEXT")
        db.execute(
            "CREATE INDEX IF NOT EXISTS idx_app_users_registration_ip_hash ON app_users(registration_ip_hash)"
        )

        # Backfill recent V1 users from their registration-time last-login IP so
        # upgrading an existing server cannot immediately grant another trial.
        rows = db.execute(
            """
            SELECT id,last_login_ip FROM app_users
            WHERE registration_ip_hash IS NULL
              AND created_at>=?
              AND COALESCE(last_login_ip,'')<>''
            """,
            (cutoff,),
        ).fetchall()
        for row in rows:
            db.execute(
                "UPDATE app_users SET registration_ip_hash=? WHERE id=?",
                (digest_ip(row["last_login_ip"]), row["id"]),
            )

        db.execute(
            """
            UPDATE modules
            SET name='封神榜汉化', description='RYLUX 本地汉化模块'
            WHERE code='sg_localization'
            """
        )
        db.commit()


@router.post("/api/v1/auth/register")
def guarded_register(payload: RegisterPayload, request: Request):
    try:
        username = validate_username(payload.username)
        encoded = password_hash(payload.password)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))

    now = utc_now()
    trial_expires = now + timedelta(hours=TRIAL_HOURS)
    cutoff = now - timedelta(hours=REGISTRATION_WINDOW_HOURS)
    ip = request_ip(request).strip()
    if not ip:
        raise HTTPException(status_code=400, detail="无法识别当前网络地址，请稍后重试")
    ip_hash = digest_ip(ip)
    device_hash = digest_device(payload.device_id)

    with open_db() as db:
        try:
            db.execute("BEGIN IMMEDIATE")
            recent = db.execute(
                """
                SELECT id,created_at FROM app_users
                WHERE registration_ip_hash=? AND created_at>?
                ORDER BY created_at DESC LIMIT 1
                """,
                (ip_hash, iso(cutoff)),
            ).fetchone()
            if recent is not None:
                raise HTTPException(
                    status_code=429,
                    detail="当前网络 48 小时内已注册过账号，请稍后再试",
                )

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
            raise HTTPException(status_code=409, detail="用户名已存在")

        user_id = int(cur.lastrowid)
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
                "active": True,
                "kind": "trial",
                "vip_level": 1,
                "expires_at": iso(trial_expires),
                "trial_expires_at": iso(trial_expires),
            },
        },
        "message": "注册成功，已获得 24 小时体验时间",
    }
