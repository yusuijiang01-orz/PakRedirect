"""Agent scoping, quota accounting, and referral rewards for RYLUX V1."""

import secrets
from datetime import timedelta

from pathlib import Path

from fastapi import APIRouter, Header, HTTPException, Request
from fastapi.responses import Response
from pydantic import BaseModel, Field

from admin_v2 import generate_key, key_hash, key_hint, log_action, request_ip, require_csrf, require_ready
from user_v1 import (
    VIP_PRESETS, iso, open_db, parse_iso, require_user, serialize_user, utc_now,
)

router = APIRouter()
MAX_REWARD_DAYS = 365
WEB_DIR = Path(__file__).with_name("agent_web")


class AgentConfig(BaseModel):
    can_manage_users: bool = False
    can_issue_cards: bool = False
    can_extend_vip: bool = False
    quota_days: int = Field(ge=0, le=1_000_000)


class AssignUser(BaseModel):
    agent_id: int | None = None


class IssueCards(BaseModel):
    days: int
    quantity: int = Field(ge=1, le=100)
    label: str = Field(default="", max_length=80)
    paid: bool = True


class ExtendUser(BaseModel):
    days: int


class ToggleUser(BaseModel):
    enabled: bool


def init_agent_referral() -> None:
    with open_db() as db:
        user_columns = {r["name"] for r in db.execute("PRAGMA table_info(app_users)")}
        if "owner_agent_id" not in user_columns:
            db.execute("ALTER TABLE app_users ADD COLUMN owner_agent_id INTEGER")
        if "invite_code" not in user_columns:
            db.execute("ALTER TABLE app_users ADD COLUMN invite_code TEXT")
        db.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_users_invite_code ON app_users(invite_code)")
        db.execute("CREATE INDEX IF NOT EXISTS idx_users_agent ON app_users(owner_agent_id)")
        db.execute("UPDATE app_users SET role='user' WHERE role NOT IN ('user','admin','agent')")
        license_columns = {r["name"] for r in db.execute("PRAGMA table_info(licenses)")}
        if "is_paid" not in license_columns:
            db.execute("ALTER TABLE licenses ADD COLUMN is_paid INTEGER NOT NULL DEFAULT 0")
        if "issued_by_agent_id" not in license_columns:
            db.execute("ALTER TABLE licenses ADD COLUMN issued_by_agent_id INTEGER")
        db.executescript("""
            CREATE TABLE IF NOT EXISTS agents (
                user_id INTEGER PRIMARY KEY REFERENCES app_users(id) ON DELETE CASCADE,
                can_manage_users INTEGER NOT NULL DEFAULT 0,
                can_issue_cards INTEGER NOT NULL DEFAULT 0,
                can_extend_vip INTEGER NOT NULL DEFAULT 0,
                quota_days INTEGER NOT NULL DEFAULT 0 CHECK(quota_days>=0),
                updated_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS agent_quota_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                agent_id INTEGER NOT NULL REFERENCES app_users(id),
                delta_days INTEGER NOT NULL,
                reason TEXT NOT NULL,
                created_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS referrals (
                invited_user_id INTEGER PRIMARY KEY REFERENCES app_users(id) ON DELETE CASCADE,
                inviter_user_id INTEGER NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
                valid INTEGER NOT NULL,
                reason TEXT NOT NULL,
                created_at TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_referrals_inviter ON referrals(inviter_user_id,valid);
            CREATE TABLE IF NOT EXISTS referral_rewards (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                inviter_user_id INTEGER NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
                source TEXT NOT NULL,
                reference TEXT NOT NULL UNIQUE,
                days INTEGER NOT NULL CHECK(days>0),
                created_at TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_referral_rewards_user ON referral_rewards(inviter_user_id);
        """)
        db.commit()


def invite_code(db, user_id: int) -> str:
    row = db.execute("SELECT invite_code FROM app_users WHERE id=?", (user_id,)).fetchone()
    if row is None:
        raise HTTPException(404, "用户不存在")
    if row["invite_code"]:
        return row["invite_code"]
    code = "RX-" + secrets.token_hex(6).upper()
    db.execute("UPDATE app_users SET invite_code=? WHERE id=?", (code, user_id))
    return code


def reward_days_used(db, user_id: int) -> int:
    return int(db.execute(
        "SELECT COALESCE(SUM(days),0) AS n FROM referral_rewards WHERE inviter_user_id=?",
        (user_id,),
    ).fetchone()["n"])


def grant_reward(db, inviter_id: int, days: int, source: str, reference: str, now) -> int:
    award = min(days, MAX_REWARD_DAYS - reward_days_used(db, inviter_id))
    if award <= 0:
        return 0
    row = db.execute("SELECT vip_expires_at,status FROM app_users WHERE id=?", (inviter_id,)).fetchone()
    if row is None or not row["status"]:
        return 0
    old = parse_iso(row["vip_expires_at"])
    new = max(old, now) + timedelta(days=award) if old else now + timedelta(days=award)
    db.execute("UPDATE app_users SET vip_expires_at=?,updated_at=? WHERE id=?", (iso(new), iso(now), inviter_id))
    db.execute("INSERT INTO referral_rewards(inviter_user_id,source,reference,days,created_at) VALUES(?,?,?,?,?)",
               (inviter_id, source, reference, award, iso(now)))
    db.execute("INSERT INTO vip_events(user_id,source,duration_seconds,reference,old_expires_at,new_expires_at,created_at) VALUES(?,?,?,?,?,?,?)",
               (inviter_id, "referral_" + source, award * 86400, reference, row["vip_expires_at"], iso(new), iso(now)))
    return award


def record_registration(db, user_id: int, code: str, trial_allowed: bool, device_hash: str, ip_hash: str, now) -> None:
    if not code:
        return
    inviter = db.execute("SELECT u.id,u.role,u.status,u.registration_ip_hash,t.device_hash AS registration_device_hash FROM app_users u LEFT JOIN trial_claims t ON t.user_id=u.id WHERE u.invite_code=?",
                         (code.strip().upper(),)).fetchone()
    if inviter is None or not inviter["status"]:
        raise HTTPException(400, "邀请码无效")
    valid = bool(trial_allowed and device_hash and ip_hash and
                 device_hash != (inviter["registration_device_hash"] or "") and
                 ip_hash != (inviter["registration_ip_hash"] or ""))
    reason = "valid" if valid else "trial_or_identity_invalid"
    db.execute("INSERT INTO referrals(invited_user_id,inviter_user_id,valid,reason,created_at) VALUES(?,?,?,?,?)",
               (user_id, inviter["id"], int(valid), reason, iso(now)))
    if inviter["role"] == "agent":
        db.execute("UPDATE app_users SET owner_agent_id=? WHERE id=?", (inviter["id"], user_id))
    if valid:
        count = int(db.execute("SELECT COUNT(*) AS n FROM referrals WHERE inviter_user_id=? AND valid=1",
                               (inviter["id"],)).fetchone()["n"])
        if count % 2 == 0:
            grant_reward(db, inviter["id"], 1, "signup", f"pair:{inviter['id']}:{count // 2}", now)


def reward_paid_redeem(db, buyer_id: int, license_id: int, days: int, now) -> int:
    referral = db.execute("SELECT inviter_user_id,valid FROM referrals WHERE invited_user_id=?",
                          (buyer_id,)).fetchone()
    if referral is None or not referral["valid"]:
        return 0
    return grant_reward(db, referral["inviter_user_id"], days, "purchase", f"license:{license_id}", now)


def agent_auth(authorization: str | None, permission: str | None = None):
    auth, _ = require_user(authorization)
    if auth["role"] != "agent":
        raise HTTPException(403, "仅代理人可使用")
    with open_db() as db:
        agent = db.execute("SELECT * FROM agents WHERE user_id=?", (auth["user_id"],)).fetchone()
    if agent is None or (permission and not agent[permission]):
        raise HTTPException(403, "代理人没有此权限")
    return auth, dict(agent)


@router.get("/agent")
def agent_page():
    return Response((WEB_DIR / "index.html").read_text(encoding="utf-8"),
                    media_type="text/html; charset=utf-8", headers={"Cache-Control": "no-store"})


@router.get("/agent/app.js")
def agent_script():
    return Response((WEB_DIR / "app.js").read_text(encoding="utf-8"),
                    media_type="application/javascript", headers={"Cache-Control": "no-store"})


@router.get("/api/v1/referrals/me")
def referral_me(authorization: str | None = Header(default=None)):
    auth, _ = require_user(authorization)
    with open_db() as db:
        code = invite_code(db, auth["user_id"])
        db.commit()
        counts = db.execute("SELECT COUNT(*) AS total,COALESCE(SUM(valid),0) AS valid FROM referrals WHERE inviter_user_id=?",
                            (auth["user_id"],)).fetchone()
        awarded = reward_days_used(db, auth["user_id"])
    return {"invite_code": code, "total_invited": counts["total"], "valid_invited": counts["valid"],
            "reward_days": awarded, "reward_cap_days": MAX_REWARD_DAYS}


@router.get("/admin/api/agents")
def admin_agents(request: Request):
    require_ready(request)
    with open_db() as db:
        rows = db.execute("SELECT u.id,u.username,u.status,a.can_manage_users,a.can_issue_cards,a.can_extend_vip,a.quota_days FROM agents a JOIN app_users u ON u.id=a.user_id WHERE u.role='agent' ORDER BY u.id DESC").fetchall()
    return {"items": [dict(r) for r in rows]}


@router.put("/admin/api/agents/{user_id}")
def admin_configure_agent(user_id: int, payload: AgentConfig, request: Request):
    token = require_ready(request)
    require_csrf(request, token)
    with open_db() as db:
        db.execute("BEGIN IMMEDIATE")
        user = db.execute("SELECT role FROM app_users WHERE id=?", (user_id,)).fetchone()
        if user is None or user["role"] == "admin":
            raise HTTPException(400, "只能将普通用户设为代理人")
        prior = db.execute("SELECT quota_days FROM agents WHERE user_id=?", (user_id,)).fetchone()
        old = int(prior["quota_days"]) if prior else 0
        db.execute("UPDATE app_users SET role='agent',updated_at=? WHERE id=?", (iso(utc_now()), user_id))
        db.execute("INSERT INTO agents(user_id,can_manage_users,can_issue_cards,can_extend_vip,quota_days,updated_at) VALUES(?,?,?,?,?,?) ON CONFLICT(user_id) DO UPDATE SET can_manage_users=excluded.can_manage_users,can_issue_cards=excluded.can_issue_cards,can_extend_vip=excluded.can_extend_vip,quota_days=excluded.quota_days,updated_at=excluded.updated_at",
                   (user_id, int(payload.can_manage_users), int(payload.can_issue_cards), int(payload.can_extend_vip), payload.quota_days, iso(utc_now())))
        db.execute("INSERT INTO agent_quota_events(agent_id,delta_days,reason,created_at) VALUES(?,?,?,?)",
                   (user_id, payload.quota_days - old, "admin_adjustment", iso(utc_now())))
        db.commit()
    log_action("agent_configured", f"user:{user_id}", f"quota={payload.quota_days}", request_ip(request))
    return {"ok": True}


@router.put("/admin/api/users/{user_id}/agent")
def admin_assign_agent(user_id: int, payload: AssignUser, request: Request):
    token = require_ready(request)
    require_csrf(request, token)
    with open_db() as db:
        user = db.execute("SELECT role FROM app_users WHERE id=?", (user_id,)).fetchone()
        if user is None or user["role"] != "user":
            raise HTTPException(400, "只能分配普通用户")
        if payload.agent_id is not None:
            agent = db.execute("SELECT 1 FROM agents a JOIN app_users u ON u.id=a.user_id WHERE a.user_id=? AND u.role='agent' AND u.status=1", (payload.agent_id,)).fetchone()
            if agent is None:
                raise HTTPException(400, "代理人不存在或已停用")
        db.execute("UPDATE app_users SET owner_agent_id=?,updated_at=? WHERE id=?", (payload.agent_id, iso(utc_now()), user_id))
        db.commit()
    log_action("user_agent_assigned", f"user:{user_id}", f"agent={payload.agent_id}", request_ip(request))
    return {"ok": True}


@router.get("/agent/api/me")
def agent_me(authorization: str | None = Header(default=None)):
    auth, agent = agent_auth(authorization)
    return {"id": auth["user_id"], "username": auth["username"], **agent}


@router.get("/agent/api/users")
def agent_users(page: int = 1, page_size: int = 30, authorization: str | None = Header(default=None)):
    auth, agent = agent_auth(authorization)
    if not (agent["can_manage_users"] or agent["can_extend_vip"]):
        raise HTTPException(403, "代理人没有查看用户权限")
    page, page_size = max(1, page), max(1, min(page_size, 100))
    with open_db() as db:
        total = db.execute("SELECT COUNT(*) AS n FROM app_users WHERE owner_agent_id=? AND role='user'", (auth["user_id"],)).fetchone()["n"]
        rows = db.execute("SELECT * FROM app_users WHERE owner_agent_id=? AND role='user' ORDER BY id DESC LIMIT ? OFFSET ?",
                          (auth["user_id"], page_size, (page - 1) * page_size)).fetchall()
    return {"items": [serialize_user(r) for r in rows], "total": total}


@router.get("/agent/api/licenses")
def agent_licenses(page: int = 1, page_size: int = 30, authorization: str | None = Header(default=None)):
    auth, _ = agent_auth(authorization, "can_issue_cards")
    page, page_size = max(1, page), max(1, min(page_size, 100))
    with open_db() as db:
        total = db.execute("SELECT COUNT(*) AS n FROM licenses WHERE issued_by_agent_id=?", (auth["user_id"],)).fetchone()["n"]
        rows = db.execute("SELECT id,key_value,key_hint,label,duration_days,is_paid,enabled,expires_at,redeemed_at FROM licenses WHERE issued_by_agent_id=? ORDER BY id DESC LIMIT ? OFFSET ?",
                          (auth["user_id"], page_size, (page - 1) * page_size)).fetchall()
    return {"items": [dict(r) for r in rows], "total": total}


def owned_user(db, agent_id: int, user_id: int):
    row = db.execute("SELECT * FROM app_users WHERE id=? AND owner_agent_id=? AND role='user'", (user_id, agent_id)).fetchone()
    if row is None:
        raise HTTPException(404, "用户不属于该代理人")
    return row


@router.post("/agent/api/users/{user_id}/toggle")
def agent_toggle_user(user_id: int, payload: ToggleUser, request: Request, authorization: str | None = Header(default=None)):
    auth, _ = agent_auth(authorization, "can_manage_users")
    with open_db() as db:
        owned_user(db, auth["user_id"], user_id)
        db.execute("UPDATE app_users SET status=?,updated_at=? WHERE id=?", (int(payload.enabled), iso(utc_now()), user_id))
        db.commit()
    log_action("agent_user_toggled", f"user:{user_id}", f"agent={auth['user_id']};enabled={payload.enabled}", request_ip(request))
    return {"ok": True}


@router.post("/agent/api/users/{user_id}/extend")
def agent_extend_user(user_id: int, payload: ExtendUser, request: Request, authorization: str | None = Header(default=None)):
    auth, _ = agent_auth(authorization, "can_extend_vip")
    if payload.days not in VIP_PRESETS:
        raise HTTPException(400, "续期天数无效")
    now = utc_now()
    with open_db() as db:
        db.execute("BEGIN IMMEDIATE")
        row = owned_user(db, auth["user_id"], user_id)
        quota = db.execute("UPDATE agents SET quota_days=quota_days-? WHERE user_id=? AND can_extend_vip=1 AND quota_days>=? RETURNING quota_days",
                           (payload.days, auth["user_id"], payload.days)).fetchone()
        if quota is None:
            raise HTTPException(409, "代理人额度不足")
        old = parse_iso(row["vip_expires_at"])
        new = max(old, now) + timedelta(days=payload.days) if old else now + timedelta(days=payload.days)
        db.execute("UPDATE app_users SET vip_expires_at=?,updated_at=? WHERE id=?", (iso(new), iso(now), user_id))
        db.execute("INSERT INTO vip_events(user_id,source,duration_seconds,reference,old_expires_at,new_expires_at,created_at) VALUES(?,?,?,?,?,?,?)",
                   (user_id, "agent", payload.days * 86400, f"agent:{auth['user_id']}", row["vip_expires_at"], iso(new), iso(now)))
        db.execute("INSERT INTO agent_quota_events(agent_id,delta_days,reason,created_at) VALUES(?,?,?,?)",
                   (auth["user_id"], -payload.days, f"extend:user:{user_id}", iso(now)))
        db.commit()
    log_action("agent_user_extended", f"user:{user_id}", f"agent={auth['user_id']};days={payload.days}", request_ip(request))
    return {"ok": True, "expires_at": iso(new), "quota_days": quota["quota_days"]}


@router.post("/agent/api/licenses/generate")
def agent_issue_cards(payload: IssueCards, request: Request, authorization: str | None = Header(default=None)):
    auth, _ = agent_auth(authorization, "can_issue_cards")
    if payload.days not in VIP_PRESETS:
        raise HTTPException(400, "发卡天数无效")
    debit = payload.days * payload.quantity
    now = utc_now()
    expiry = iso(now + timedelta(days=365))
    keys = []
    with open_db() as db:
        db.execute("BEGIN IMMEDIATE")
        quota = db.execute("UPDATE agents SET quota_days=quota_days-? WHERE user_id=? AND can_issue_cards=1 AND quota_days>=? RETURNING quota_days",
                           (debit, auth["user_id"], debit)).fetchone()
        if quota is None:
            raise HTTPException(409, "代理人额度不足")
        for _ in range(payload.quantity):
            value = generate_key()
            db.execute("INSERT INTO licenses(key_hash,key_hint,key_value,label,expires_at,enabled,created_at,duration_days,is_paid,issued_by_agent_id) VALUES(?,?,?,?,?,1,?,?,?,?)",
                       (key_hash(value), key_hint(value), value, payload.label, expiry, iso(now), payload.days,
                        int(payload.paid), auth["user_id"]))
            keys.append(value)
        db.execute("INSERT INTO agent_quota_events(agent_id,delta_days,reason,created_at) VALUES(?,?,?,?)",
                   (auth["user_id"], -debit, f"cards:{payload.days}x{payload.quantity}", iso(now)))
        db.commit()
    log_action("agent_cards_generated", f"agent:{auth['user_id']}", f"{payload.days}x{payload.quantity};paid={payload.paid}", request_ip(request))
    return {"ok": True, "keys": keys, "quota_days": quota["quota_days"], "expires_at": expiry}
