import sys
from pathlib import Path
from unittest.mock import patch

import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parent))


@pytest.fixture
def setup_backend(monkeypatch, tmp_path):
    monkeypatch.setenv("PAKREDIRECT_LICENSE_DB", str(tmp_path / "licenses.db"))
    for name in ("app", "agent_referral", "registration_guard_v1", "user_v1", "admin_v2", "admin_key_access", "admin_code_v1", "admin_user_controls", "protected_content"):
        sys.modules.pop(name, None)
    import app
    import admin_v2
    import agent_referral
    with TestClient(app.app, base_url="https://testserver") as client:
        with admin_v2.open_db() as db:
            db.execute("UPDATE admin_settings SET password_hash=?,must_change_password=0 WHERE id=1",
                       (admin_v2.make_password_hash("test-admin-password"),))
            db.commit()
        yield client, admin_v2, agent_referral, app


def register(client, name, device, ip, invite_code=""):
    headers = {"x-real-ip": ip}
    with patch("registration_guard_v1.secrets.choice", return_value="A"):
        captcha = client.get("/api/v1/auth/captcha", headers=headers)
    assert captcha.status_code == 200, captcha.text
    response = client.post(
        "/api/v1/auth/register",
        json={
            "username": name,
            "password": "password123",
            "device_id": device,
            "invite_code": invite_code,
            "captcha_id": captcha.json()["challenge_id"],
            "captcha_code": "AAAA",
        },
        headers=headers,
    )
    assert response.status_code == 200, response.text
    return response


def admin_headers(client):
    response = client.post("/admin/api/login", json={"username": "admin", "password": "test-admin-password"})
    assert response.status_code == 200, response.text
    me = client.get("/admin/api/me")
    return {"x-csrf-token": me.json()["csrf"]}


def bearer(response):
    assert response.status_code == 200, response.text
    return {"Authorization": "Bearer " + response.json()["token"]}


def test_agent_scope_balance_and_paid_referrals(setup_backend):
    client, admin, referral, app = setup_backend
    admin_auth = admin_headers(client)
    assert "代理人权限与人民币余额" in client.get("/admin").text
    assert "RYLUX 代理后台" in client.get("/agent").text
    agent = register(client, "agent-one", "device-agent", "10.0.0.1")
    agent_token = bearer(agent)
    client.cookies.clear()
    assert client.get("/admin/api/users", headers=agent_token).status_code == 401
    admin_auth = admin_headers(client)
    agent_id = agent.json()["user"]["id"]
    config = {"can_manage_users": True, "can_issue_cards": True, "can_extend_vip": True}
    response = client.put(f"/admin/api/agents/{agent_id}", json=config, headers=admin_auth)
    assert response.status_code == 200, response.text
    funding = client.post(f"/admin/api/agents/{agent_id}/balance",
                          json={"amount_yuan": "40.00", "reason": "测试充值"}, headers=admin_auth)
    assert funding.status_code == 200, funding.text
    assert funding.json()["balance_cents"] == 4000
    assert client.get("/agent/api/me", headers=agent_token).json()["balance_cents"] == 4000
    code = client.get("/api/v1/referrals/me", headers=agent_token).json()["invite_code"]

    first = register(client, "invitee-one", "device-one", "10.0.0.2", code)
    invalid = register(client, "invitee-fake", "device-agent", "10.0.0.3", code)
    second = register(client, "invitee-two", "device-two", "10.0.0.4", code)
    assert first.status_code == invalid.status_code == second.status_code == 200
    assert invalid.json()["user"]["membership"]["active"] is True
    with referral.open_db() as db:
        referral_row = db.execute(
            "SELECT valid FROM referrals WHERE invited_user_id=?",
            (invalid.json()["user"]["id"],),
        ).fetchone()
    assert referral_row is not None and referral_row["valid"] == 0
    stats = client.get("/api/v1/referrals/me", headers=agent_token).json()
    assert (stats["total_invited"], stats["valid_invited"], stats["reward_days"]) == (3, 2, 1)
    owned = client.get("/agent/api/users", headers=agent_token).json()
    assert owned["total"] == 3

    outsider = register(client, "outsider", "device-outside", "10.0.0.5")
    outsider_id = outsider.json()["user"]["id"]
    denied = client.post(f"/agent/api/users/{outsider_id}/extend", json={"days": 30}, headers=agent_token)
    assert denied.status_code == 404
    assert client.get("/admin/api/users", headers=admin_auth).json()["total"] == 5

    cards = client.post("/agent/api/licenses/generate", json={"days": 30, "quantity": 2, "paid": True}, headers=agent_token)
    assert cards.status_code == 200, cards.text
    assert cards.json()["balance_cents"] == 0
    assert cards.json()["debit_cents"] == 4000
    own_cards = client.get("/agent/api/licenses?reveal=1", headers=agent_token).json()
    assert own_cards["total"] == 2
    assert {r["key_value"] for r in own_cards["items"]} == set(cards.json()["keys"])
    assert all(r["agent_price_cents"] == 2000 for r in own_cards["items"])
    unused_card = next(row for row in own_cards["items"] if row["key_value"] == cards.json()["keys"][1])
    assert client.post(f"/agent/api/licenses/{unused_card['id']}/toggle", json={"enabled": False}, headers=agent_token).status_code == 200
    exported = client.get("/agent/api/licenses/export.csv", headers=agent_token)
    assert exported.status_code == 200 and cards.json()["keys"][0] in exported.text
    balance_events = client.get("/agent/api/balance-events", headers=agent_token).json()["items"]
    assert balance_events[0]["delta_cents"] == -4000
    assert client.post("/agent/api/licenses/generate", json={"days": 30, "quantity": 1}, headers=agent_token).status_code == 409
    buyer_token = bearer(first)
    redeemed = client.post("/api/v1/redeem", json={"code": cards.json()["keys"][0]}, headers=buyer_token)
    assert redeemed.status_code == 200, redeemed.text
    assert client.get("/api/v1/referrals/me", headers=agent_token).json()["reward_days"] == 31
    assert client.post("/api/v1/redeem", json={"code": cards.json()["keys"][0]}, headers=buyer_token).status_code == 409

    # An invalid registration cannot trigger the paid-card referral bonus.
    bad_token = bearer(invalid)
    assert client.post("/api/v1/redeem", json={"code": cards.json()["keys"][1]}, headers=bad_token).status_code == 400
    assert client.post(f"/agent/api/licenses/{unused_card['id']}/toggle", json={"enabled": True}, headers=agent_token).status_code == 200
    assert client.post("/api/v1/redeem", json={"code": cards.json()["keys"][1]}, headers=bad_token).status_code == 200
    assert client.get("/api/v1/referrals/me", headers=agent_token).json()["reward_days"] == 31


def test_admin_paid_flag_and_permission_revocation(setup_backend):
    client, admin, referral, app = setup_backend
    admin_auth = admin_headers(client)
    agent = register(client, "seller", "seller-device", "10.2.0.1")
    token = bearer(agent)
    agent_id = agent.json()["user"]["id"]
    config = {"can_manage_users": False, "can_issue_cards": True, "can_extend_vip": True}
    assert client.put(f"/admin/api/agents/{agent_id}", json=config, headers=admin_auth).status_code == 200
    assert client.post(f"/admin/api/agents/{agent_id}/balance", json={"amount_yuan": "20.00"}, headers=admin_auth).status_code == 200
    owned = register(client, "owned-user", "owned-device", "10.2.0.2")
    owned_id = owned.json()["user"]["id"]
    assert client.put(f"/admin/api/users/{owned_id}/agent", json={"agent_id": agent_id}, headers=admin_auth).status_code == 200
    assert client.get("/agent/api/users", headers=token).json()["total"] == 1
    assert client.post(f"/agent/api/users/{owned_id}/toggle", json={"enabled": False}, headers=token).status_code == 403
    renewed = client.post(f"/agent/api/users/{owned_id}/extend", json={"days": 30}, headers=token)
    assert renewed.status_code == 200 and renewed.json()["balance_cents"] == 0
    assert client.post(f"/agent/api/users/{owned_id}/extend", json={"days": 30}, headers=token).status_code == 409
    config["can_issue_cards"] = False
    assert client.put(f"/admin/api/agents/{agent_id}", json=config, headers=admin_auth).status_code == 200
    assert client.post("/agent/api/licenses/generate", json={"days": 30, "quantity": 1}, headers=token).status_code == 403
    assert client.get("/agent/api/licenses", headers=token).status_code == 403
    assert client.get("/agent/api/licenses/export.csv", headers=token).status_code == 403

    inviter = register(client, "paid-inviter", "inviter-device", "10.2.1.1")
    inviter_token = bearer(inviter)
    code = client.get("/api/v1/referrals/me", headers=inviter_token).json()["invite_code"]
    buyer = register(client, "paid-buyer", "buyer-device", "10.2.1.2", code)
    buyer_token = bearer(buyer)
    free = client.post("/admin/api/licenses/generate", json={"days": 30, "quantity": 1, "paid": False}, headers=admin_auth)
    paid = client.post("/admin/api/licenses/generate", json={"days": 30, "quantity": 1, "paid": True}, headers=admin_auth)
    assert free.status_code == paid.status_code == 200
    assert client.post("/api/v1/redeem", json={"code": free.json()["keys"][0]}, headers=buyer_token).status_code == 200
    assert client.get("/api/v1/referrals/me", headers=inviter_token).json()["reward_days"] == 0
    assert client.post("/api/v1/redeem", json={"code": paid.json()["keys"][0]}, headers=buyer_token).status_code == 200
    assert client.get("/api/v1/referrals/me", headers=inviter_token).json()["reward_days"] == 30

    # A direct paid agent renewal is also a purchase and rewards the invite owner.
    buyer2 = register(client, "paid-buyer-two", "buyer-device-two", "10.2.1.3", code)
    buyer2_id = buyer2.json()["user"]["id"]
    assert client.put(f"/admin/api/users/{buyer2_id}/agent", json={"agent_id": agent_id}, headers=admin_auth).status_code == 200
    assert client.post(f"/admin/api/agents/{agent_id}/balance", json={"amount_yuan": "20.00"}, headers=admin_auth).status_code == 200
    direct_purchase = client.post(f"/agent/api/users/{buyer2_id}/extend", json={"days": 30}, headers=token)
    assert direct_purchase.status_code == 200, direct_purchase.text
    assert direct_purchase.json()["referral_reward_days"] == 30
    assert client.get("/api/v1/referrals/me", headers=inviter_token).json()["reward_days"] == 61
def test_reward_cap_and_migration(setup_backend):
    client, admin, referral, app = setup_backend
    inviter = register(client, "inviter", "device-i", "10.1.0.1")
    user_id = inviter.json()["user"]["id"]
    with referral.open_db() as db:
        now = referral.utc_now()
        assert referral.grant_reward(db, user_id, 360, "purchase", "license:test-1", now) == 360
        assert referral.grant_reward(db, user_id, 30, "purchase", "license:test-2", now) == 5
        assert referral.grant_reward(db, user_id, 30, "purchase", "license:test-3", now) == 0
        db.commit()
    app.init_db()
    with referral.open_db() as db:
        assert referral.reward_days_used(db, user_id) == 365


def test_owned_users_card_buyer_and_legacy_quota(setup_backend):
    client, admin, referral, app = setup_backend
    admin_auth = admin_headers(client)
    a = register(client, "agent-a", "agent-a-device", "10.4.0.1")
    b = register(client, "agent-b", "agent-b-device", "10.4.0.2")
    token_a, token_b = bearer(a), bearer(b)
    id_a, id_b = a.json()["user"]["id"], b.json()["user"]["id"]
    flags = {"can_manage_users": True, "can_issue_cards": True, "can_extend_vip": True}
    for user_id in (id_a, id_b):
        assert client.put(f"/admin/api/agents/{user_id}", json=flags, headers=admin_auth).status_code == 200
    with referral.open_db() as db:
        db.execute("UPDATE agents SET quota_days=365 WHERE user_id=?", (id_a,))
        db.commit()
    app.init_db()
    listed = client.get("/admin/api/agents").json()["items"]
    row_a = next(row for row in listed if row["id"] == id_a)
    assert row_a["legacy_quota_days"] == 365 and row_a["balance_cents"] == 0

    created = client.post("/agent/api/users", json={"username": "owned-by-a", "password": "initial-pass-123"}, headers=token_a)
    assert created.status_code == 200, created.text
    owned_id = created.json()["user_id"]
    assert client.get("/agent/api/users", headers=token_a).json()["total"] == 1
    assert client.get("/agent/api/users", headers=token_b).json()["total"] == 0
    assert client.post(f"/agent/api/users/{owned_id}/toggle", json={"enabled": False}, headers=token_b).status_code == 404
    assert client.post(f"/agent/api/users/{owned_id}/extend", json={"days": 30}, headers=token_b).status_code == 404

    # A paid agent card binds an unowned buyer to its issuer when redeemed.
    buyer = register(client, "unowned-buyer", "buyer-device", "10.4.0.3")
    assert client.post(f"/admin/api/agents/{id_a}/balance", json={"amount_yuan": "20"}, headers=admin_auth).status_code == 200
    card = client.post("/agent/api/licenses/generate", json={"days": 30, "quantity": 1}, headers=token_a)
    assert card.status_code == 200, card.text
    assert client.get("/agent/api/licenses", headers=token_b).json()["total"] == 0
    assert client.post("/api/v1/redeem", json={"code": card.json()["keys"][0]}, headers=bearer(buyer)).status_code == 200
    assert client.get("/agent/api/users", headers=token_a).json()["total"] == 2
    own_card = client.get("/agent/api/licenses", headers=token_a).json()["items"][0]
    assert own_card["redeemed_username"] == "unowned-buyer"
    assert own_card["agent_price_cents"] == 2000


def test_balance_atomicity_and_batch_scope(setup_backend):
    client, admin, referral, _ = setup_backend
    admin_auth = admin_headers(client)
    agent = register(client, "batch-agent", "batch-agent-device", "10.5.0.1")
    token = bearer(agent)
    agent_id = agent.json()["user"]["id"]
    flags = {"can_manage_users": True, "can_issue_cards": True, "can_extend_vip": True}
    assert client.put(f"/admin/api/agents/{agent_id}", json=flags, headers=admin_auth).status_code == 200
    assert client.post(f"/admin/api/agents/{agent_id}/balance", json={"amount_yuan": "20.00"}, headers=admin_auth).status_code == 200
    own = client.post("/agent/api/users", json={"username": "batch-own", "password": "password123"}, headers=token).json()["user_id"]
    other = register(client, "batch-other", "other-device", "10.5.0.2").json()["user"]["id"]
    denied = client.post("/agent/api/users/batch-renew", json={"user_ids": [own, other], "days": 30}, headers=token)
    assert denied.status_code == 404
    assert client.get("/agent/api/me", headers=token).json()["balance_cents"] == 2000
    assert client.post("/agent/api/licenses/generate", json={"days": 30, "quantity": 2}, headers=token).status_code == 409
    assert client.get("/agent/api/licenses", headers=token).json()["total"] == 0
    success = client.post("/agent/api/users/batch-renew", json={"user_ids": [own, own], "days": 30}, headers=token)
    assert success.status_code == 200 and success.json()["debit_cents"] == 2000
    assert client.get("/agent/api/me", headers=token).json()["balance_cents"] == 0
    sessions = client.get(f"/agent/api/users/{own}/sessions", headers=token)
    assert sessions.status_code == 200 and sessions.json()["items"] == []
    assert client.post(f"/agent/api/users/{own}/unbind-device", json={}, headers=token).status_code == 200
    assert client.get(f"/agent/api/users/{other}/sessions", headers=token).status_code == 404
    assert client.delete(f"/agent/api/users/{own}", headers=token).status_code == 200
    assert client.get("/agent/api/users", headers=token).json()["total"] == 0
