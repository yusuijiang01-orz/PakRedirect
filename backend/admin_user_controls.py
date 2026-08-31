from datetime import timezone
from pathlib import Path

from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import RedirectResponse, Response
from pydantic import BaseModel, Field

from admin_v2 import (
    auth_token,
    log_action,
    no_store,
    request_ip,
    require_csrf,
    require_ready,
)
from user_v1 import iso, open_db, parse_iso, utc_now

router = APIRouter()
WEB_DIR = Path(__file__).with_name("admin_web")


class SetExpiryPayload(BaseModel):
    expires_at: str = Field(min_length=10, max_length=64)


@router.get("/admin")
def enhanced_admin_page(request: Request):
    if not auth_token(request):
        return RedirectResponse("/admin/login", status_code=303)
    html = (WEB_DIR / "index.html").read_text(encoding="utf-8")
    marker = '<script src="/admin/assets/app.js"></script>'
    addon = marker + '\n<script src="/admin/assets/admin-users.js"></script>'
    if marker not in html:
        raise HTTPException(status_code=500, detail="后台页面资源异常")
    html = html.replace(marker, addon, 1)
    return no_store(Response(html, media_type="text/html; charset=utf-8"))


@router.get("/admin/assets/admin-users.js")
def enhanced_admin_users_asset():
    path = WEB_DIR / "admin-users.js"
    if not path.exists():
        raise HTTPException(status_code=404)
    return Response(
        path.read_bytes(),
        media_type="application/javascript",
        headers={"Cache-Control": "no-store"},
    )


@router.post("/admin/api/users/{user_id}/set-expiry")
def admin_user_set_expiry(user_id: int, payload: SetExpiryPayload, request: Request):
    token = require_ready(request)
    require_csrf(request, token)

    new_expiry = parse_iso(payload.expires_at.strip())
    if new_expiry is None or new_expiry.tzinfo is None:
        raise HTTPException(status_code=400, detail="到期时间格式无效")
    new_expiry = new_expiry.astimezone(timezone.utc)

    now = utc_now()
    with open_db() as db:
        row = db.execute(
            "SELECT id,username,vip_expires_at FROM app_users WHERE id=?",
            (user_id,),
        ).fetchone()
        if row is None:
            raise HTTPException(status_code=404, detail="用户不存在")

        old_expiry = parse_iso(row["vip_expires_at"])
        old_text = row["vip_expires_at"]
        new_text = iso(new_expiry)
        delta_seconds = 0
        if old_expiry is not None:
            delta_seconds = int((new_expiry - old_expiry).total_seconds())

        db.execute(
            "UPDATE app_users SET vip_expires_at=?,updated_at=? WHERE id=?",
            (new_text, iso(now), user_id),
        )
        db.execute(
            """
            INSERT INTO vip_events
                (user_id,source,duration_seconds,reference,old_expires_at,new_expires_at,created_at)
            VALUES(?,?,?,?,?,?,?)
            """,
            (
                user_id,
                "admin_set",
                delta_seconds,
                "admin-console",
                old_text,
                new_text,
                iso(now),
            ),
        )
        db.commit()

    log_action(
        "user_vip_set",
        f"user:{user_id}",
        f"{old_text} -> {new_text}",
        request_ip(request),
    )
    return {"ok": True, "expires_at": new_text}


@router.post("/admin/api/users/{user_id}/unbind-device")
def admin_user_unbind_device(user_id: int, request: Request):
    token = require_ready(request)
    require_csrf(request, token)
    now_text = iso(utc_now())

    with open_db() as db:
        row = db.execute(
            "SELECT id,username,last_device_hash FROM app_users WHERE id=?",
            (user_id,),
        ).fetchone()
        if row is None:
            raise HTTPException(status_code=404, detail="用户不存在")

        session_count = int(
            db.execute(
                "SELECT COUNT(*) AS c FROM app_sessions WHERE user_id=? AND revoked=0",
                (user_id,),
            ).fetchone()["c"]
            or 0
        )
        db.execute(
            "UPDATE app_sessions SET revoked=1,last_seen_at=? WHERE user_id=? AND revoked=0",
            (now_text, user_id),
        )
        db.execute(
            "UPDATE app_users SET last_device_hash='',updated_at=? WHERE id=?",
            (now_text, user_id),
        )
        db.commit()

    log_action(
        "user_device_unbound",
        f"user:{user_id}",
        f"revoked_sessions={session_count}",
        request_ip(request),
    )
    return {"ok": True, "revoked_sessions": session_count}


@router.get("/admin/api/users/{user_id}/sessions")
def admin_user_sessions(user_id: int, request: Request, limit: int = 20):
    require_ready(request)
    limit = max(1, min(limit, 50))

    with open_db() as db:
        user = db.execute(
            "SELECT id,username,last_login_at,last_login_ip,last_device_hash FROM app_users WHERE id=?",
            (user_id,),
        ).fetchone()
        if user is None:
            raise HTTPException(status_code=404, detail="用户不存在")
        rows = db.execute(
            """
            SELECT id,created_at,last_seen_at,expires_at,ip_address,device_hash,revoked
            FROM app_sessions
            WHERE user_id=?
            ORDER BY id DESC
            LIMIT ?
            """,
            (user_id, limit),
        ).fetchall()

    def device_hint(value: str | None) -> str:
        value = (value or "").strip()
        if not value:
            return ""
        return value[:8] + "…" + value[-8:]

    return {
        "user": {
            "id": int(user["id"]),
            "username": user["username"],
            "last_login_at": user["last_login_at"],
            "last_login_ip": user["last_login_ip"] or "",
            "device_hint": device_hint(user["last_device_hash"]),
        },
        "items": [
            {
                "id": int(row["id"]),
                "created_at": row["created_at"],
                "last_seen_at": row["last_seen_at"],
                "expires_at": row["expires_at"],
                "ip_address": row["ip_address"] or "",
                "device_hint": device_hint(row["device_hash"]),
                "revoked": bool(row["revoked"]),
            }
            for row in rows
        ],
    }


@router.delete("/admin/api/users/{user_id}")
def admin_user_delete(user_id: int, request: Request):
    token = require_ready(request)
    require_csrf(request, token)

    with open_db() as db:
        row = db.execute(
            "SELECT id,username,last_login_ip FROM app_users WHERE id=?",
            (user_id,),
        ).fetchone()
        if row is None:
            raise HTTPException(status_code=404, detail="用户不存在")
        username = row["username"]
        db.execute("DELETE FROM app_users WHERE id=?", (user_id,))
        db.commit()

    log_action(
        "user_deleted",
        f"user:{user_id}",
        f"username={username}",
        request_ip(request),
    )
    return {"ok": True, "username": username}
