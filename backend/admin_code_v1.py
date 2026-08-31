from datetime import timedelta

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel

from admin_v2 import iso, log_action, open_db, parse_iso, request_ip, require_csrf, require_ready, utc_now

router = APIRouter()
PRESETS = (1, 7, 30, 90, 180, 365)


class TogglePayload(BaseModel):
    enabled: bool


class ExtendPayload(BaseModel):
    days: int


# This route intentionally registers before user_v1's compatibility route.
@router.get("/api/v1/plans")
def public_plans():
    with open_db() as db:
        rows = db.execute(
            "SELECT code,name,days FROM plans WHERE enabled=1 ORDER BY sort_order,code"
        ).fetchall()
    return {
        "purchase_enabled": False,
        "message": "V1 暂未开放在线支付，可使用兑换码充值",
        "plans": [dict(row) for row in rows],
    }


@router.post("/admin/api/licenses/{license_id}/toggle")
def toggle_code(license_id: int, payload: TogglePayload, request: Request):
    token = require_ready(request)
    require_csrf(request, token)
    with open_db() as db:
        row = db.execute(
            "SELECT redeemed_at FROM licenses WHERE id=?",
            (license_id,),
        ).fetchone()
        if row is None:
            raise HTTPException(status_code=404, detail="未找到兑换码")
        if payload.enabled and row["redeemed_at"]:
            raise HTTPException(status_code=409, detail="已兑换的兑换码不能重新启用")
        db.execute(
            "UPDATE licenses SET enabled=? WHERE id=?",
            (1 if payload.enabled else 0, license_id),
        )
        db.commit()
    log_action(
        "redeem_code_enabled" if payload.enabled else "redeem_code_disabled",
        f"license:{license_id}",
        "",
        request_ip(request),
    )
    return {"ok": True}


@router.post("/admin/api/licenses/{license_id}/extend")
def extend_code(license_id: int, payload: ExtendPayload, request: Request):
    token = require_ready(request)
    require_csrf(request, token)
    if payload.days not in PRESETS:
        raise HTTPException(status_code=400, detail="延长天数不在允许范围")
    with open_db() as db:
        row = db.execute(
            "SELECT expires_at,redeemed_at FROM licenses WHERE id=?",
            (license_id,),
        ).fetchone()
        if row is None:
            raise HTTPException(status_code=404, detail="未找到兑换码")
        current = parse_iso(row["expires_at"])
        if current is None:
            raise HTTPException(status_code=500, detail="兑换码到期时间异常")
        new_expiry = max(current, utc_now()) + timedelta(days=payload.days)
        db.execute(
            "UPDATE licenses SET expires_at=? WHERE id=?",
            (iso(new_expiry), license_id),
        )
        db.commit()
    log_action(
        "redeem_code_validity_extended",
        f"license:{license_id}",
        f"+{payload.days}天",
        request_ip(request),
    )
    return {"ok": True, "expires_at": iso(new_expiry)}
