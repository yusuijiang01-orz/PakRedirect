import sys
from pathlib import Path

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
    return client.post("/api/v1/auth/register", json={"username": name, "password": "password123", "device_id": device, "invite_code": invite_code}, headers={"x-real-ip": ip})


def admin_headers(client):
    response = client.post("/admin/api/login", json={"username": "admin", "password": "test-admin-password"})
    assert response.status_code == 200, response.text
    me = client.get("/admin/api/me")
    return {"x-csrf-token": me.json()["csrf"]}


def bearer(response):
    assert response.status_code == 200, response.text
    return {"Authorization": "Bearer " + response.json()["token"]}


def test_agent_scope_quota_and_paid_referrals(setup_backend):
    client, admin, referral, app = setup_backend
    admin_auth = admin_headers(client)
    assert "代理人权限与额度" in client.get("/admin").text
    assert "RYLUX 代理人" in client.get("/agent").text
    agent = register(client, "agent-one", "device-agent", "10.0.0.1")
    agent_token = bearer(agent)
    client.cookies.clear()
    assert client.get("/admin/api/users", headers=agent_token).status_code == 401
    admin_auth = admin_headers(client)
    agent_id = agent.json()["user"]["id"]
    config = {"can_manage_users": True, "can_issue_cards": True, "can_extend_vip": True, "quota_days": 60}
    response = client.put(f"/admin/api/agents/{agent_id}", json=config, headers=admin_auth)
    assert response.status_code == 200, response.text
    code = client.get("/api/v1/referrals/me", headers=agent_token).json()["invite_code"]

    first = register(client, "invitee-one", "device-one", "10.0.0.2", code)
    invalid = register(client, "invitee-fake", "device-one", "10.0.0.3", code)
    second = register(client, "invitee-two", "device-two", "10.0.0.4", code)
    assert first.status_code == invalid.status_code == second.status_code == 200
    assert invalid.json()["user"]["membership"]["active"] is False
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
    assert cards.json()["quota_days"] == 0
    own_cards = client.get("/agent/api/licenses", headers=agent_token).json()
    assert own_cards["total"] == 2
    assert {r["key_value"] for r in own_cards["items"]} == set(cards.json()["keys"])
    assert client.post("/agent/api/licenses/generate", json={"days": 1, "quantity": 1}, headers=agent_token).status_code == 409
    buyer_token = bearer(first)
    redeemed = client.post("/api/v1/redeem", json={"code": cards.json()["keys"][0]}, headers=buyer_token)
    assert redeemed.status_code == 200, redeemed.text
    assert client.get("/api/v1/referrals/me", headers=agent_token).json()["reward_days"] == 31
    assert client.post("/api/v1/redeem", json={"code": cards.json()["keys"][0]}, headers=buyer_token).status_code == 409

    # An invalid registration cannot trigger the paid-card referral bonus.
    bad_token = bearer(invalid)
    assert client.post("/api/v1/redeem", json={"code": cards.json()["keys"][1]}, headers=bad_token).status_code == 200
    assert client.get("/api/v1/referrals/me", headers=agent_token).json()["reward_days"] == 31


def test_admin_paid_flag_and_permission_revocation(setup_backend):
    client, admin, referral, app = setup_backend
    admin_auth = admin_headers(client)
    agent = register(client, "seller", "seller-device", "10.2.0.1")
    token = bearer(agent)
    agent_id = agent.json()["user"]["id"]
    config = {"can_manage_users": False, "can_issue_cards": True, "can_extend_vip": True, "quota_days": 31}
    assert client.put(f"/admin/api/agents/{agent_id}", json=config, headers=admin_auth).status_code == 200
    owned = register(client, "owned-user", "owned-device", "10.2.0.2")
    owned_id = owned.json()["user"]["id"]
    assert client.put(f"/admin/api/users/{owned_id}/agent", json={"agent_id": agent_id}, headers=admin_auth).status_code == 200
    assert client.get("/agent/api/users", headers=token).json()["total"] == 1
    assert client.post(f"/agent/api/users/{owned_id}/toggle", json={"enabled": False}, headers=token).status_code == 403
    assert client.post(f"/agent/api/users/{owned_id}/extend", json={"days": 30}, headers=token).status_code == 200
    assert client.post(f"/agent/api/users/{owned_id}/extend", json={"days": 7}, headers=token).status_code == 409
    config["can_issue_cards"] = False
    assert client.put(f"/admin/api/agents/{agent_id}", json=config, headers=admin_auth).status_code == 200
    assert client.post("/agent/api/licenses/generate", json={"days": 1, "quantity": 1}, headers=token).status_code == 403

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
