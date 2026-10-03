"""Agent scoping, RMB balance accounting, and referral rewards for RYLUX V1."""

import csv
import io
import re
import secrets
import sqlite3
from datetime import timedelta

from pathlib import Path

from fastapi import APIRouter, Header, HTTPException, Request
from fastapi.responses import Response
from pydantic import BaseModel, Field

from admin_v2 import generate_key, key_hash, key_hint, log_action, request_ip, require_csrf, require_ready
from admin_key_access import serialize_license
from user_v1 import (
    TRIAL_HOURS, bearer_token, create_session, digest_token, iso, open_db, parse_iso, password_hash,
    require_user, serialize_user, username_key, validate_username, verify_password, utc_now,
)

router = APIRouter()
MAX_REWARD_DAYS = 365
WEB_DIR = Path(__file__).with_name("agent_web")
PLAN_PRICES = ((30, "月卡", 2000), (90, "季卡", 4800), (180, "半年卡", 7800), (365, "年卡", 11800))
PRICE_BY_DAYS = {days: cents for days, _, cents in PLAN_PRICES}


class AgentConfig(BaseModel):
    can_manage_users: bool = False
    can_issue_cards: bool = False
    can_extend_vip: bool = False


class AgentLogin(BaseModel):
    username: str = Field(min_length=1, max_length=32)
    password: str = Field(min_length=1, max_length=128)


class BalanceAdjustment(BaseModel):
    amount_yuan: str
    reason: str = Field(default="管理员调整", max_length=80)


class CreateOwnedUser(BaseModel):
    username: str = Field(min_length=3, max_length=32)
    password: str = Field(min_length=6, max_length=128)


class ChangePassword(BaseModel):
    current_password: str
    new_password: str = Field(min_length=10, max_length=128)


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


class ToggleCard(BaseModel):
    enabled: bool


class BatchRenew(BaseModel):
    user_ids: list[int] = Field(min_length=1, max_length=100)
    days: int


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
        if "agent_price_cents" not in license_columns:
            db.execute("ALTER TABLE licenses ADD COLUMN agent_price_cents INTEGER")
        db.execute("CREATE INDEX IF NOT EXISTS idx_licenses_issued_agent ON licenses(issued_by_agent_id)")
        db.executescript("""
            CREATE TABLE IF NOT EXISTS agents (
                user_id INTEGER PRIMARY KEY REFERENCES app_users(id) ON DELETE CASCADE,
                can_manage_users INTEGER NOT NULL DEFAULT 0,
                can_issue_cards INTEGER NOT NULL DEFAULT 0,
                can_extend_vip INTEGER NOT NULL DEFAULT 0,
                quota_days INTEGER NOT NULL DEFAULT 0 CHECK(quota_days>=0),
                balance_cents INTEGER NOT NULL DEFAULT 0 CHECK(balance_cents>=0),
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
            CREATE TABLE IF NOT EXISTS agent_balance_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                agent_id INTEGER NOT NULL REFERENCES app_users(id),
                delta_cents INTEGER NOT NULL,
                balance_after_cents INTEGER NOT NULL,
                reason TEXT NOT NULL,
                reference TEXT NOT NULL DEFAULT '',
                created_at TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_agent_balance_events_agent ON agent_balance_events(agent_id,id);
            CREATE TABLE IF NOT EXISTS agent_activity (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                agent_id INTEGER NOT NULL REFERENCES app_users(id),
                action TEXT NOT NULL,
                target TEXT NOT NULL DEFAULT '',
                details TEXT NOT NULL DEFAULT '',
                ip_address TEXT NOT NULL DEFAULT '',
                created_at TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_agent_activity_agent ON agent_activity(agent_id,id);
        """)
        agent_columns = {r["name"] for r in db.execute("PRAGMA table_info(agents)")}
        if "balance_cents" not in agent_columns:
            # Old day quotas cannot be valued as money. Keep quota_days as legacy history.
            db.execute("ALTER TABLE agents ADD COLUMN balance_cents INTEGER NOT NULL DEFAULT 0 CHECK(balance_cents>=0)")
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
        grant_reward(db, inviter["id"], 7, "signup", f"invite:{inviter['id']}:{user_id}", now)


def reward_paid_purchase(db, buyer_id: int, days: int, reference: str, now) -> int:
    referral = db.execute("SELECT inviter_user_id,valid FROM referrals WHERE invited_user_id=?",
                          (buyer_id,)).fetchone()
    if referral is None or not referral["valid"]:
        return 0
    return grant_reward(db, referral["inviter_user_id"], days, "purchase", reference, now)


def reward_paid_redeem(db, buyer_id: int, license_id: int, days: int, now) -> int:
    return reward_paid_purchase(db, buyer_id, days, f"license:{license_id}", now)


def agent_auth(authorization: str | None, permission: str | None = None):
    auth, _ = require_user(authorization)
    if auth["role"] != "agent":
        raise HTTPException(403, "仅代理人可使用")
    with open_db() as db:
        agent = db.execute("SELECT * FROM agents WHERE user_id=?", (auth["user_id"],)).fetchone()
    if agent is None or (permission and not agent[permission]):
        raise HTTPException(403, "代理人没有此权限")
    return auth, dict(agent)


def parse_yuan(value: str) -> int:
    value = value.strip()
    if not re.fullmatch(r"-?(?:0|[1-9][0-9]{0,6})(?:\.[0-9]{1,2})?", value):
        raise HTTPException(400, "金额格式无效，最多保留两位小数")
    sign = -1 if value.startswith("-") else 1
    whole, _, fraction = value.lstrip("-").partition(".")
    cents = sign * (int(whole) * 100 + int(fraction.ljust(2, "0") or "0"))
    if cents == 0:
        raise HTTPException(400, "调整金额不能为零")
    return cents


def change_balance(db, agent_id: int, delta_cents: int, reason: str,
                   reference: str = "", permission: str | None = None) -> int:
    if permission not in (None, "can_issue_cards", "can_extend_vip"):
        raise ValueError("Unknown agent permission")
    permission_clause = f" AND {permission}=1" if permission else ""
    row = db.execute(
        """UPDATE agents SET balance_cents=balance_cents+?,updated_at=?
           WHERE user_id=? AND balance_cents+?>=0""" + permission_clause + " RETURNING balance_cents",
        (delta_cents, iso(utc_now()), agent_id, delta_cents),
    ).fetchone()
    if row is None:
        raise HTTPException(409, "代理人余额不足或代理人不存在")
    balance = int(row["balance_cents"])
    db.execute(
        "INSERT INTO agent_balance_events(agent_id,delta_cents,balance_after_cents,reason,reference,created_at) VALUES(?,?,?,?,?,?)",
        (agent_id, delta_cents, balance, reason, reference, iso(utc_now())),
    )
    return balance


def record_agent_action(db, agent_id: int, action: str, target: str, details: str, ip: str) -> None:
    db.execute(
        "INSERT INTO agent_activity(agent_id,action,target,details,ip_address,created_at) VALUES(?,?,?,?,?,?)",
        (agent_id, action, target, details, ip, iso(utc_now())),
    )


def plan_price(days: int) -> int:
    price = PRICE_BY_DAYS.get(days)
    if price is None:
        raise HTTPException(400, "请选择月卡、季卡、半年卡或年卡")
    return price


@router.get("/agent")
def agent_page():
    return Response((WEB_DIR / "index.html").read_text(encoding="utf-8"),
                    media_type="text/html; charset=utf-8", headers={"Cache-Control": "no-store"})


@router.get("/agent/app.js")
def agent_script():
    return Response((WEB_DIR / "app.js").read_text(encoding="utf-8"),
                    media_type="application/javascript", headers={"Cache-Control": "no-store"})


@router.post("/agent/api/auth/login")
def agent_login(payload: AgentLogin, request: Request):
    key = username_key(payload.username)
    ip = request_ip(request)
    now = iso(utc_now())

    with open_db() as lookup_db:
        account = lookup_db.execute(
            "SELECT id,password_hash FROM app_users WHERE username_key=? LIMIT 1",
            (key,),
        ).fetchone()
    if account is None or not verify_password(account["password_hash"], payload.password):
        raise HTTPException(status_code=401, detail="账号或密码错误")

    with open_db() as db:
        db.execute("BEGIN IMMEDIATE")
        row = db.execute(
            "SELECT id,password_hash,status,role FROM app_users WHERE id=?",
            (account["id"],),
        ).fetchone()
        if row is None or row["password_hash"] != account["password_hash"]:
            raise HTTPException(status_code=401, detail="账号或密码错误")
        if int(row["status"]) != 1:
            raise HTTPException(status_code=403, detail="账号已停用")
        if row["role"] != "agent" or db.execute(
            "SELECT 1 FROM agents WHERE user_id=?", (row["id"],)
        ).fetchone() is None:
            raise HTTPException(status_code=403, detail="仅代理人可登录")

        db.execute(
            "UPDATE app_users SET last_login_at=?,last_login_ip=?,login_count=login_count+1,updated_at=? WHERE id=?",
            (now, ip[:64], now, row["id"]),
        )
        token, session_expires = create_session(
            db, int(row["id"]), ip, f"agent-portal:{int(row['id'])}"
        )
        db.commit()

    return {"token": token, "session_expires_at": session_expires, "message": "登录成功"}


@router.get("/api/v1/referrals/me")
def referral_me(authorization: str | None = Header(default=None)):
    auth, _ = require_user(authorization)
    with open_db() as db:
        code = invite_code(db, auth["user_id"])
        db.commit()
        counts = db.execute("SELECT COUNT(*) AS total,COALESCE(SUM(valid),0) AS valid FROM referrals WHERE inviter_user_id=?",
                            (auth["user_id"],)).fetchone()
        invited = db.execute(
            "SELECT u.username,r.created_at,r.valid FROM referrals r "
            "JOIN app_users u ON u.id=r.invited_user_id "
            "WHERE r.inviter_user_id=? ORDER BY r.created_at DESC,r.invited_user_id DESC",
            (auth["user_id"],)).fetchall()
        awarded = reward_days_used(db, auth["user_id"])
    return {"invite_code": code, "total_invited": counts["total"], "valid_invited": counts["valid"],
            "reward_days": awarded, "reward_cap_days": MAX_REWARD_DAYS,
            "invited_users": [{"username": row["username"], "created_at": row["created_at"],
                               "valid": bool(row["valid"])} for row in invited]}


@router.get("/admin/api/agents")
def admin_agents(request: Request):
    require_ready(request)
    with open_db() as db:
        rows = db.execute("SELECT u.id,u.username,u.status,a.can_manage_users,a.can_issue_cards,a.can_extend_vip,a.quota_days AS legacy_quota_days,a.balance_cents FROM agents a JOIN app_users u ON u.id=a.user_id WHERE u.role='agent' ORDER BY u.id DESC").fetchall()
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
        db.execute("UPDATE app_users SET role='agent',updated_at=? WHERE id=?", (iso(utc_now()), user_id))
        db.execute("INSERT INTO agents(user_id,can_manage_users,can_issue_cards,can_extend_vip,updated_at) VALUES(?,?,?,?,?) ON CONFLICT(user_id) DO UPDATE SET can_manage_users=excluded.can_manage_users,can_issue_cards=excluded.can_issue_cards,can_extend_vip=excluded.can_extend_vip,updated_at=excluded.updated_at",
                   (user_id, int(payload.can_manage_users), int(payload.can_issue_cards), int(payload.can_extend_vip), iso(utc_now())))
        db.commit()
    log_action("agent_configured", f"user:{user_id}", "permissions updated", request_ip(request))
    return {"ok": True}


@router.post("/admin/api/agents/{user_id}/balance")
def admin_adjust_balance(user_id: int, payload: BalanceAdjustment, request: Request):
    token = require_ready(request)
    require_csrf(request, token)
    delta_cents = parse_yuan(payload.amount_yuan)
    with open_db() as db:
        db.execute("BEGIN IMMEDIATE")
        agent = db.execute("SELECT 1 FROM agents a JOIN app_users u ON u.id=a.user_id WHERE a.user_id=? AND u.role='agent'", (user_id,)).fetchone()
        if agent is None:
            raise HTTPException(404, "代理人不存在")
        balance = change_balance(db, user_id, delta_cents, payload.reason.strip() or "管理员调整", "admin_adjustment")
        db.commit()
    log_action("agent_balance_adjusted", f"agent:{user_id}", f"delta_cents={delta_cents};balance_cents={balance}", request_ip(request))
    return {"ok": True, "balance_cents": balance}


@router.get("/admin/api/agents/{user_id}/balance-events")
def admin_agent_balance_events(user_id: int, request: Request, page: int = 1, page_size: int = 30):
    require_ready(request)
    page, page_size = max(1, page), max(1, min(page_size, 100))
    with open_db() as db:
        agent = db.execute("SELECT 1 FROM agents WHERE user_id=?", (user_id,)).fetchone()
        if agent is None:
            raise HTTPException(404, "代理人不存在")
        total = int(db.execute("SELECT COUNT(*) AS n FROM agent_balance_events WHERE agent_id=?",
                               (user_id,)).fetchone()["n"])
        rows = db.execute("SELECT id,delta_cents,balance_after_cents,reason,reference,created_at FROM agent_balance_events WHERE agent_id=? ORDER BY id DESC LIMIT ? OFFSET ?",
                          (user_id, page_size, (page - 1) * page_size)).fetchall()
    return {"items": [dict(r) for r in rows], "total": total, "page": page,
            "pages": max(1, (total + page_size - 1) // page_size)}


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
    return {
        "id": auth["user_id"], "username": auth["username"],
        "balance_cents": agent["balance_cents"],
        "can_manage_users": bool(agent["can_manage_users"]),
        "can_issue_cards": bool(agent["can_issue_cards"]),
        "can_extend_vip": bool(agent["can_extend_vip"]),
    }


@router.get("/agent/api/prices")
def agent_prices(authorization: str | None = Header(default=None)):
    agent_auth(authorization)
    return {"items": [{"days": days, "name": name, "price_cents": cents} for days, name, cents in PLAN_PRICES]}


@router.get("/agent/api/overview")
def agent_overview(authorization: str | None = Header(default=None)):
    auth, agent = agent_auth(authorization)
    agent_id = auth["user_id"]
    now = iso(utc_now())
    today = utc_now().date().isoformat() + "%"
    with open_db() as db:
        users = db.execute(
            """SELECT COUNT(*) AS total,
                      SUM(CASE WHEN status=1 AND vip_expires_at>? THEN 1 ELSE 0 END) AS active,
                      SUM(CASE WHEN vip_expires_at<=? THEN 1 ELSE 0 END) AS expired,
                      SUM(CASE WHEN status=0 THEN 1 ELSE 0 END) AS disabled,
                      SUM(CASE WHEN created_at LIKE ? THEN 1 ELSE 0 END) AS registered_today
               FROM app_users WHERE owner_agent_id=? AND role='user'""",
            (now, now, today, agent_id),
        ).fetchone()
        licenses = db.execute(
            """SELECT COUNT(*) AS total,
                      SUM(CASE WHEN enabled=1 AND expires_at>? THEN 1 ELSE 0 END) AS active,
                      SUM(CASE WHEN expires_at<=? AND redeemed_at IS NULL THEN 1 ELSE 0 END) AS expired,
                      SUM(CASE WHEN enabled=0 THEN 1 ELSE 0 END) AS disabled
               FROM licenses WHERE issued_by_agent_id=?""",
            (now, now, agent_id),
        ).fetchone()
        recent = db.execute(
            """SELECT l.*,u.username AS redeemed_username FROM licenses l
               LEFT JOIN app_users u ON u.id=l.redeemed_by_user_id
               WHERE l.issued_by_agent_id=? ORDER BY l.id DESC LIMIT 8""",
            (agent_id,),
        ).fetchall()
    return {
        "stats": {key: int(users[key] or 0) for key in ("total", "active", "expired", "disabled", "registered_today")}
                 if agent["can_manage_users"] or agent["can_extend_vip"] else {},
        "licenses": {key: int(licenses[key] or 0) for key in ("total", "active", "expired", "disabled")}
                     if agent["can_issue_cards"] else {},
        "recent": [{**serialize_license(r), "redeemed_username": r["redeemed_username"]} for r in recent]
                  if agent["can_issue_cards"] else [],
    }


@router.get("/agent/api/users")
def agent_users(q: str = "", status: str = "", page: int = 1, page_size: int = 30,
                authorization: str | None = Header(default=None)):
    auth, agent = agent_auth(authorization)
    if not (agent["can_manage_users"] or agent["can_extend_vip"]):
        raise HTTPException(403, "代理人没有查看用户权限")
    page, page_size = max(1, page), max(1, min(page_size, 100))
    conditions = ["owner_agent_id=?", "role='user'"]
    params = [auth["user_id"]]
    if q.strip():
        conditions.append("(username LIKE ? COLLATE NOCASE OR COALESCE(last_login_ip,'') LIKE ? COLLATE NOCASE)")
        params.extend([f"%{q[:128].strip()}%"] * 2)
    now = iso(utc_now())
    if status == "active":
        conditions.append("status=1 AND vip_expires_at>?")
        params.append(now)
    elif status == "expired":
        conditions.append("vip_expires_at<=?")
        params.append(now)
    elif status == "disabled":
        conditions.append("status=0")
    where = " WHERE " + " AND ".join(conditions)
    with open_db() as db:
        total = int(db.execute("SELECT COUNT(*) AS n FROM app_users" + where, params).fetchone()["n"])
        rows = db.execute("SELECT * FROM app_users" + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
                          (*params, page_size, (page - 1) * page_size)).fetchall()
    return {"items": [serialize_user(r) for r in rows], "total": total, "page": page,
            "page_size": page_size, "pages": max(1, (total + page_size - 1) // page_size)}


@router.post("/agent/api/users")
def agent_create_user(payload: CreateOwnedUser, request: Request,
                      authorization: str | None = Header(default=None)):
    auth, _ = agent_auth(authorization, "can_manage_users")
    try:
        username = validate_username(payload.username)
        encoded = password_hash(payload.password)
    except ValueError as exc:
        raise HTTPException(400, str(exc))
    now = iso(utc_now())
    with open_db() as db:
        try:
            cur = db.execute(
                """INSERT INTO app_users
                   (username,username_key,password_hash,status,role,vip_level,vip_expires_at,
                    trial_started_at,trial_expires_at,created_at,updated_at,owner_agent_id)
                   VALUES(?,?,?,1,'user',1,?,?,?,?,?,?)""",
                (username, username_key(username), encoded, now, now, now, now, now, auth["user_id"]),
            )
        except sqlite3.IntegrityError:
            raise HTTPException(409, "用户名已存在")
        user_id = int(cur.lastrowid)
        record_agent_action(db, auth["user_id"], "user_created", f"user:{user_id}", "", request_ip(request))
        db.commit()
    log_action("agent_user_created", f"user:{user_id}", f"agent={auth['user_id']}", request_ip(request))
    return {"ok": True, "user_id": user_id}


def agent_license_filter(agent_id: int, q: str, status: str):
    conditions = ["l.issued_by_agent_id=?"]
    params = [agent_id]
    if q.strip():
        like = f"%{q[:128].strip()}%"
        conditions.append("(l.label LIKE ? COLLATE NOCASE OR l.key_hint LIKE ? COLLATE NOCASE OR COALESCE(l.key_value,'') LIKE ? COLLATE NOCASE OR COALESCE(u.username,'') LIKE ? COLLATE NOCASE)")
        params.extend([like] * 4)
    now = iso(utc_now())
    if status == "active":
        conditions.append("l.enabled=1 AND l.expires_at>?")
        params.append(now)
    elif status == "expired":
        conditions.append("l.expires_at<=? AND l.redeemed_at IS NULL")
        params.append(now)
    elif status == "disabled":
        conditions.append("l.enabled=0")
    return " WHERE " + " AND ".join(conditions), params


def own_license_rows(db, agent_id: int, q: str, status: str, limit: int, offset: int):
    join = " FROM licenses l LEFT JOIN app_users u ON u.id=l.redeemed_by_user_id"
    where, params = agent_license_filter(agent_id, q, status)
    total = int(db.execute("SELECT COUNT(*) AS n" + join + where, params).fetchone()["n"])
    rows = db.execute("SELECT l.*,u.username AS redeemed_username" + join + where +
                      " ORDER BY l.id DESC LIMIT ? OFFSET ?", (*params, limit, offset)).fetchall()
    return rows, total


@router.get("/agent/api/licenses")
def agent_licenses(q: str = "", status: str = "", page: int = 1, page_size: int = 30,
                   reveal: bool = False, authorization: str | None = Header(default=None)):
    auth, _ = agent_auth(authorization, "can_issue_cards")
    page, page_size = max(1, page), max(1, min(page_size, 100))
    with open_db() as db:
        rows, total = own_license_rows(db, auth["user_id"], q, status, page_size, (page - 1) * page_size)
    return {"items": [{**serialize_license(r, reveal), "redeemed_by_user_id": r["redeemed_by_user_id"],
                       "redeemed_username": r["redeemed_username"], "agent_price_cents": r["agent_price_cents"]}
                      for r in rows], "total": total, "page": page, "page_size": page_size,
            "pages": max(1, (total + page_size - 1) // page_size)}


def owned_user(db, agent_id: int, user_id: int):
    row = db.execute("SELECT * FROM app_users WHERE id=? AND owner_agent_id=? AND role='user'", (user_id, agent_id)).fetchone()
    if row is None:
        raise HTTPException(404, "用户不属于该代理人")
    return row


@router.post("/agent/api/users/{user_id}/toggle")
def agent_toggle_user(user_id: int, payload: ToggleUser, request: Request, authorization: str | None = Header(default=None)):
    auth, _ = agent_auth(authorization, "can_manage_users")
    with open_db() as db:
        db.execute("BEGIN IMMEDIATE")
        owned_user(db, auth["user_id"], user_id)
        db.execute("UPDATE app_users SET status=?,updated_at=? WHERE id=? AND owner_agent_id=?",
                   (int(payload.enabled), iso(utc_now()), user_id, auth["user_id"]))
        record_agent_action(db, auth["user_id"], "user_enabled" if payload.enabled else "user_disabled",
                            f"user:{user_id}", "", request_ip(request))
        db.commit()
    log_action("agent_user_toggled", f"user:{user_id}", f"agent={auth['user_id']};enabled={payload.enabled}", request_ip(request))
    return {"ok": True}


@router.post("/agent/api/users/{user_id}/extend")
def agent_extend_user(user_id: int, payload: ExtendUser, request: Request, authorization: str | None = Header(default=None)):
    auth, _ = agent_auth(authorization, "can_extend_vip")
    price_cents = plan_price(payload.days)
    now = utc_now()
    with open_db() as db:
        db.execute("BEGIN IMMEDIATE")
        row = owned_user(db, auth["user_id"], user_id)
        balance = change_balance(db, auth["user_id"], -price_cents,
                                 f"续期 {payload.days} 天", f"user:{user_id}", "can_extend_vip")
        old = parse_iso(row["vip_expires_at"])
        new = max(old, now) + timedelta(days=payload.days) if old else now + timedelta(days=payload.days)
        db.execute("UPDATE app_users SET vip_expires_at=?,vip_level=1,updated_at=? WHERE id=? AND owner_agent_id=?",
                   (iso(new), iso(now), user_id, auth["user_id"]))
        event = db.execute("INSERT INTO vip_events(user_id,source,duration_seconds,reference,old_expires_at,new_expires_at,created_at) VALUES(?,?,?,?,?,?,?)",
                           (user_id, "agent", payload.days * 86400, f"agent:{auth['user_id']}", row["vip_expires_at"], iso(new), iso(now)))
        referral_reward_days = reward_paid_purchase(db, user_id, payload.days,
                                                   f"vip-event:{event.lastrowid}", now)
        record_agent_action(db, auth["user_id"], "user_extended", f"user:{user_id}",
                            f"days={payload.days};price_cents={price_cents}", request_ip(request))
        db.commit()
    log_action("agent_user_extended", f"user:{user_id}", f"agent={auth['user_id']};days={payload.days};price_cents={price_cents}", request_ip(request))
    return {"ok": True, "expires_at": iso(new), "balance_cents": balance,
            "price_cents": price_cents, "referral_reward_days": referral_reward_days}


@router.post("/agent/api/users/batch-renew")
def agent_batch_renew(payload: BatchRenew, request: Request,
                      authorization: str | None = Header(default=None)):
    auth, _ = agent_auth(authorization, "can_extend_vip")
    user_ids = list(dict.fromkeys(payload.user_ids))
    if any(user_id < 1 for user_id in user_ids):
        raise HTTPException(400, "用户 ID 无效")
    price_cents = plan_price(payload.days)
    now = utc_now()
    renewed = []
    with open_db() as db:
        db.execute("BEGIN IMMEDIATE")
        users = [owned_user(db, auth["user_id"], user_id) for user_id in user_ids]
        debit_cents = price_cents * len(users)
        balance = change_balance(db, auth["user_id"], -debit_cents,
                                 f"批量续期 {payload.days} 天 × {len(users)}", "batch-renew",
                                 "can_extend_vip")
        for row in users:
            old = parse_iso(row["vip_expires_at"])
            new = max(old, now) + timedelta(days=payload.days) if old else now + timedelta(days=payload.days)
            db.execute("UPDATE app_users SET vip_expires_at=?,vip_level=1,updated_at=? WHERE id=? AND owner_agent_id=?",
                       (iso(new), iso(now), row["id"], auth["user_id"]))
            event = db.execute("INSERT INTO vip_events(user_id,source,duration_seconds,reference,old_expires_at,new_expires_at,created_at) VALUES(?,?,?,?,?,?,?)",
                               (row["id"], "agent_batch", payload.days * 86400, f"agent:{auth['user_id']}",
                                row["vip_expires_at"], iso(new), iso(now)))
            reward_days = reward_paid_purchase(db, row["id"], payload.days,
                                               f"vip-event:{event.lastrowid}", now)
            renewed.append({"id": row["id"], "username": row["username"],
                            "expires_at": iso(new), "referral_reward_days": reward_days})
        record_agent_action(db, auth["user_id"], "users_batch_renewed", f"count:{len(users)}",
                            f"days={payload.days};debit_cents={debit_cents}", request_ip(request))
        db.commit()
    log_action("agent_users_batch_renewed", f"agent:{auth['user_id']}",
               f"count={len(users)};days={payload.days};debit_cents={debit_cents}", request_ip(request))
    return {"ok": True, "renewed": renewed, "count": len(renewed),
            "balance_cents": balance, "debit_cents": debit_cents}


@router.get("/agent/api/users/{user_id}/sessions")
def agent_user_sessions(user_id: int, limit: int = 20,
                        authorization: str | None = Header(default=None)):
    auth, _ = agent_auth(authorization, "can_manage_users")
    limit = max(1, min(limit, 50))
    with open_db() as db:
        user = owned_user(db, auth["user_id"], user_id)
        rows = db.execute("SELECT id,created_at,last_seen_at,expires_at,ip_address,device_hash,revoked FROM app_sessions WHERE user_id=? ORDER BY id DESC LIMIT ?",
                          (user_id, limit)).fetchall()
    def hint(value):
        value = (value or "").strip()
        return value[:8] + "…" + value[-8:] if value else ""
    return {"user": {"id": user_id, "username": user["username"],
                     "last_login_at": user["last_login_at"], "last_login_ip": user["last_login_ip"] or "",
                     "device_hint": hint(user["last_device_hash"])},
            "items": [{"id": r["id"], "created_at": r["created_at"], "last_seen_at": r["last_seen_at"],
                       "expires_at": r["expires_at"], "ip_address": r["ip_address"] or "",
                       "device_hint": hint(r["device_hash"]), "revoked": bool(r["revoked"])} for r in rows]}


@router.post("/agent/api/users/{user_id}/unbind-device")
def agent_unbind_user(user_id: int, request: Request,
                      authorization: str | None = Header(default=None)):
    auth, _ = agent_auth(authorization, "can_manage_users")
    now = iso(utc_now())
    with open_db() as db:
        db.execute("BEGIN IMMEDIATE")
        owned_user(db, auth["user_id"], user_id)
        revoked = db.execute("UPDATE app_sessions SET revoked=1,last_seen_at=? WHERE user_id=? AND revoked=0",
                             (now, user_id)).rowcount
        db.execute("UPDATE app_users SET last_device_hash='',updated_at=? WHERE id=? AND owner_agent_id=?",
                   (now, user_id, auth["user_id"]))
        db.execute("DELETE FROM app_device_bindings WHERE user_id=?", (user_id,))
        record_agent_action(db, auth["user_id"], "user_device_unbound", f"user:{user_id}",
                            f"revoked_sessions={revoked}", request_ip(request))
        db.commit()
    log_action("agent_user_device_unbound", f"user:{user_id}", f"agent={auth['user_id']}", request_ip(request))
    return {"ok": True, "revoked_sessions": revoked}


@router.delete("/agent/api/users/{user_id}")
def agent_delete_user(user_id: int, request: Request,
                      authorization: str | None = Header(default=None)):
    auth, _ = agent_auth(authorization, "can_manage_users")
    with open_db() as db:
        db.execute("BEGIN IMMEDIATE")
        user = owned_user(db, auth["user_id"], user_id)
        if db.execute("SELECT 1 FROM payment_orders WHERE user_id=? LIMIT 1", (user_id,)).fetchone():
            raise HTTPException(status_code=409, detail="该账号有支付订单，不能删除；请停用账号")
        db.execute("DELETE FROM app_users WHERE id=? AND owner_agent_id=? AND role='user'",
                   (user_id, auth["user_id"]))
        record_agent_action(db, auth["user_id"], "user_deleted", f"user:{user_id}",
                            f"username={user['username']}", request_ip(request))
        db.commit()
    log_action("agent_user_deleted", f"user:{user_id}", f"agent={auth['user_id']}", request_ip(request))
    return {"ok": True, "username": user["username"]}


@router.post("/agent/api/licenses/generate")
def agent_issue_cards(payload: IssueCards, request: Request, authorization: str | None = Header(default=None)):
    auth, _ = agent_auth(authorization, "can_issue_cards")
    price_cents = plan_price(payload.days)
    debit_cents = price_cents * payload.quantity
    now = utc_now()
    expiry = iso(now + timedelta(days=365))
    keys = []
    with open_db() as db:
        db.execute("BEGIN IMMEDIATE")
        balance = change_balance(db, auth["user_id"], -debit_cents,
                                 f"发卡 {payload.days} 天 × {payload.quantity}",
                                 f"cards:{payload.days}x{payload.quantity}", "can_issue_cards")
        for _ in range(payload.quantity):
            value = generate_key()
            db.execute("INSERT INTO licenses(key_hash,key_hint,key_value,label,expires_at,enabled,created_at,duration_days,is_paid,issued_by_agent_id,agent_price_cents) VALUES(?,?,?,?,?,1,?,?,?,?,?)",
                       (key_hash(value), key_hint(value), value, payload.label, expiry, iso(now), payload.days,
                        int(payload.paid), auth["user_id"], price_cents))
            keys.append(value)
        record_agent_action(db, auth["user_id"], "cards_generated", f"agent:{auth['user_id']}",
                            f"days={payload.days};quantity={payload.quantity};debit_cents={debit_cents}", request_ip(request))
        db.commit()
    log_action("agent_cards_generated", f"agent:{auth['user_id']}", f"{payload.days}x{payload.quantity};debit_cents={debit_cents};paid={payload.paid}", request_ip(request))
    return {"ok": True, "keys": keys, "balance_cents": balance, "price_cents": price_cents,
            "debit_cents": debit_cents, "expires_at": expiry}


@router.post("/agent/api/licenses/{license_id}/toggle")
def agent_toggle_card(license_id: int, payload: ToggleCard, request: Request,
                      authorization: str | None = Header(default=None)):
    auth, _ = agent_auth(authorization, "can_issue_cards")
    with open_db() as db:
        db.execute("BEGIN IMMEDIATE")
        card = db.execute("SELECT id,redeemed_at FROM licenses WHERE id=? AND issued_by_agent_id=?",
                          (license_id, auth["user_id"])).fetchone()
        if card is None:
            raise HTTPException(404, "卡密不属于该代理人")
        if card["redeemed_at"]:
            raise HTTPException(409, "已兑换的卡密不能更改状态")
        db.execute("UPDATE licenses SET enabled=? WHERE id=? AND issued_by_agent_id=?",
                   (int(payload.enabled), license_id, auth["user_id"]))
        record_agent_action(db, auth["user_id"], "card_enabled" if payload.enabled else "card_disabled",
                            f"license:{license_id}", "", request_ip(request))
        db.commit()
    log_action("agent_card_enabled" if payload.enabled else "agent_card_disabled",
               f"license:{license_id}", f"agent={auth['user_id']}", request_ip(request))
    return {"ok": True}


@router.get("/agent/api/licenses/export.csv")
def agent_export_cards(q: str = "", status: str = "",
                       authorization: str | None = Header(default=None)):
    auth, _ = agent_auth(authorization, "can_issue_cards")
    with open_db() as db:
        rows, _ = own_license_rows(db, auth["user_id"], q, status, 100000, 0)
    stream = io.StringIO()
    writer = csv.writer(stream)
    writer.writerow(["ID", "兑换码", "套餐天数", "卡片标签", "状态", "兑换用户", "到期时间"])
    for row in rows:
        item = serialize_license(row, True)
        writer.writerow([row["id"], row["key_value"] or row["key_hint"], row["duration_days"],
                         row["label"], item["status"], row["redeemed_username"] or "", row["expires_at"]])
    return Response("\ufeff" + stream.getvalue(), media_type="text/csv; charset=utf-8",
                    headers={"Content-Disposition": 'attachment; filename="rylux-agent-cards.csv"',
                             "Cache-Control": "no-store"})


@router.get("/agent/api/balance-events")
def agent_balance_events(page: int = 1, page_size: int = 30,
                         authorization: str | None = Header(default=None)):
    auth, _ = agent_auth(authorization)
    page, page_size = max(1, page), max(1, min(page_size, 100))
    with open_db() as db:
        total = int(db.execute("SELECT COUNT(*) AS n FROM agent_balance_events WHERE agent_id=?",
                               (auth["user_id"],)).fetchone()["n"])
        rows = db.execute("SELECT id,delta_cents,balance_after_cents,reason,reference,created_at FROM agent_balance_events WHERE agent_id=? ORDER BY id DESC LIMIT ? OFFSET ?",
                          (auth["user_id"], page_size, (page - 1) * page_size)).fetchall()
    return {"items": [dict(r) for r in rows], "total": total, "page": page,
            "pages": max(1, (total + page_size - 1) // page_size)}


@router.get("/agent/api/logs")
def agent_logs(page: int = 1, page_size: int = 40,
               authorization: str | None = Header(default=None)):
    auth, _ = agent_auth(authorization)
    page, page_size = max(1, page), max(1, min(page_size, 100))
    with open_db() as db:
        total = int(db.execute("SELECT COUNT(*) AS n FROM agent_activity WHERE agent_id=?",
                               (auth["user_id"],)).fetchone()["n"])
        rows = db.execute("SELECT id,action,target,details,ip_address,created_at FROM agent_activity WHERE agent_id=? ORDER BY id DESC LIMIT ? OFFSET ?",
                          (auth["user_id"], page_size, (page - 1) * page_size)).fetchall()
    return {"items": [dict(r) for r in rows], "total": total, "page": page,
            "pages": max(1, (total + page_size - 1) // page_size)}


@router.post("/agent/api/change-password")
def agent_change_password(payload: ChangePassword, request: Request,
                          authorization: str | None = Header(default=None)):
    auth, _ = agent_auth(authorization)
    try:
        encoded = password_hash(payload.new_password)
    except ValueError as exc:
        raise HTTPException(400, str(exc))
    with open_db() as db:
        db.execute("BEGIN IMMEDIATE")
        user = db.execute("SELECT password_hash FROM app_users WHERE id=? AND role='agent'",
                          (auth["user_id"],)).fetchone()
        if user is None or not verify_password(user["password_hash"], payload.current_password):
            raise HTTPException(400, "当前密码错误")
        db.execute("UPDATE app_users SET password_hash=?,updated_at=? WHERE id=?",
                   (encoded, iso(utc_now()), auth["user_id"]))
        db.execute("UPDATE app_sessions SET revoked=1 WHERE user_id=? AND token_hash<>?",
                   (auth["user_id"], digest_token(bearer_token(authorization))))
        record_agent_action(db, auth["user_id"], "password_changed", f"user:{auth['user_id']}",
                            "", request_ip(request))
        db.commit()
    log_action("agent_password_changed", f"user:{auth['user_id']}", "", request_ip(request))
    return {"ok": True}
