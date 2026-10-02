"""Direct-merchant App Pay. Only authenticated provider evidence grants VIP.

Alipay RSA2 and WeChat APIv3 wire formats follow the official documentation
linked in README.md. No payment secret or SDK result is trusted from a client.
"""
import base64
import json
import logging
import os
import re
import secrets
import tempfile
import threading
import time
from datetime import timedelta, timezone
from decimal import Decimal, InvalidOperation
from pathlib import Path
from urllib.parse import parse_qsl, quote, urlencode, urlsplit
from urllib.request import Request as UrlRequest, urlopen

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from fastapi import APIRouter, Header, HTTPException, Request, Response
from fastapi.concurrency import run_in_threadpool
from pydantic import BaseModel, Field

from admin_v2 import log_action, request_ip, require_csrf, require_ready
from user_v1 import iso, open_db, parse_iso, require_user, utc_now

router = APIRouter()
log = logging.getLogger(__name__)
CONFIG_LOCK = threading.Lock()
PAYMENT_SETTINGS_PATH = Path(os.environ.get("PAKREDIRECT_LICENSE_DB", "./data/licenses.db")).resolve().parent / "payment_settings.json"
PAYMENT_KEY_DIR = PAYMENT_SETTINGS_PATH.parent / "payment_keys"
DEFAULT_PAYMENT_BASE_URL = "https://verify.lovenom.eu.org"
PLANS = {
    "30d": {"name": "月卡", "days": 30, "amount_fen": 2000},
    "90d": {"name": "季卡", "days": 90, "amount_fen": 4800},
    "180d": {"name": "半年卡", "days": 180, "amount_fen": 7800},
    "365d": {"name": "年卡", "days": 365, "amount_fen": 11800},
}
CONFIG_FIELDS = {
    "alipay": ("APP_ID", "SELLER_ID", "PRIVATE_KEY_FILE", "PUBLIC_KEY_FILE"),
    "wechat": ("APP_ID", "MCH_ID", "CERT_SERIAL", "PRIVATE_KEY_FILE",
               "PUBLIC_KEY_ID", "PUBLIC_KEY_FILE", "API_V3_KEY"),
}


class OrderPayload(BaseModel):
    class Config:
        extra = "forbid"

    plan_code: str = Field(min_length=1, max_length=16)
    request_id: str = Field(min_length=16, max_length=64)


class PaymentSettingsPayload(BaseModel):
    class Config:
        extra = "forbid"

    payment_base_url: str = Field(default=DEFAULT_PAYMENT_BASE_URL, max_length=256)
    alipay_enabled: bool = False
    alipay_app_id: str = Field(default="", max_length=128)
    alipay_seller_id: str = Field(default="", max_length=128)
    alipay_private_key_pem: str = Field(default="", max_length=16384)
    alipay_public_key_pem: str = Field(default="", max_length=16384)
    clear_alipay_private_key: bool = False
    clear_alipay_public_key: bool = False
    wechat_enabled: bool = False
    wechat_app_id: str = Field(default="", max_length=128)
    wechat_mch_id: str = Field(default="", max_length=64)
    wechat_cert_serial: str = Field(default="", max_length=128)
    wechat_private_key_pem: str = Field(default="", max_length=16384)
    wechat_public_key_id: str = Field(default="", max_length=128)
    wechat_public_key_pem: str = Field(default="", max_length=16384)
    wechat_api_v3_key: str = Field(default="", max_length=128)
    clear_wechat_private_key: bool = False
    clear_wechat_public_key: bool = False
    clear_wechat_api_v3_key: bool = False


def stored_settings():
    try:
        value = json.loads(PAYMENT_SETTINGS_PATH.read_text(encoding="utf-8"))
        return value if isinstance(value, dict) else {}
    except FileNotFoundError:
        return {}
    except (OSError, ValueError):
        log.exception("Unable to read payment settings")
        raise HTTPException(503, "支付配置文件无法读取")


def payment_base_url():
    saved = stored_settings().get("PAYMENT_BASE_URL")
    return (saved if isinstance(saved, str) else os.environ.get("RYLUX_PAYMENT_BASE_URL", DEFAULT_PAYMENT_BASE_URL)).strip().rstrip("/")


def current_channel_values(channel):
    prefix = "RYLUX_" + channel.upper() + "_"
    values = {key: os.environ.get(prefix + key, "").strip() for key in CONFIG_FIELDS[channel]}
    values["ENABLED"] = os.environ.get(prefix + "ENABLED", "0").strip()
    saved = stored_settings().get(channel, {})
    if isinstance(saved, dict):
        values.update({key: value for key, value in saved.items() if key in (*CONFIG_FIELDS[channel], "ENABLED")})
    return values


def admin_payment_settings():
    result = {"alipay": {}, "wechat": {}}
    for channel in CONFIG_FIELDS:
        values = current_channel_values(channel)
        try:
            config(channel)
            configured = True
        except HTTPException:
            configured = False
        result[channel] = {
            "enabled": values.get("ENABLED") == "1",
            "configured": configured,
            "app_id": values.get("APP_ID", ""),
            "merchant_id": values.get("SELLER_ID" if channel == "alipay" else "MCH_ID", ""),
            "cert_serial": values.get("CERT_SERIAL", "") if channel == "wechat" else "",
            "public_key_id": values.get("PUBLIC_KEY_ID", "") if channel == "wechat" else "",
            "has_private_key": bool(values.get("PRIVATE_KEY_FILE") and Path(values["PRIVATE_KEY_FILE"]).is_file()),
            "has_public_key": bool(values.get("PUBLIC_KEY_FILE") and Path(values["PUBLIC_KEY_FILE"]).is_file()),
            "has_api_v3_key": bool(values.get("API_V3_KEY")) if channel == "wechat" else False,
        }
    return result


def _key_bytes(value, private):
    if not value.strip():
        return b""
    data = value.strip().encode("utf-8") + b"\n"
    try:
        key = (serialization.load_pem_private_key(data, password=None) if private
               else serialization.load_pem_public_key(data))
    except Exception as exc:
        raise HTTPException(400, "PEM 密钥格式无效或私钥设置了密码") from exc
    if not isinstance(key, rsa.RSAPrivateKey if private else rsa.RSAPublicKey) or key.key_size < 2048:
        raise HTTPException(400, "密钥必须是至少 2048 位的 RSA PEM 格式")
    return data


def _write_private_file(path, content):
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    if path.parent == PAYMENT_KEY_DIR:
        os.chmod(path.parent, 0o700)
    fd, temp_path = tempfile.mkstemp(prefix=".payment-key-", dir=str(path.parent))
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "wb") as output:
            output.write(content)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temp_path, path)
        os.chmod(path, 0o600)
    finally:
        if os.path.exists(temp_path):
            os.unlink(temp_path)


def save_payment_settings(payload):
    with CONFIG_LOCK:
        settings = stored_settings()
        base_url = payload.payment_base_url.strip().rstrip("/")
        parsed_base = urlsplit(base_url)
        if (parsed_base.scheme != "https" or not parsed_base.hostname or parsed_base.username
                or parsed_base.password or parsed_base.path not in ("", "/")
                or parsed_base.query or parsed_base.fragment):
            raise HTTPException(400, "回调域名必须是有效的 HTTPS 地址，不能带查询参数或片段")
        settings["PAYMENT_BASE_URL"] = base_url
        for channel, prefix, key_specs, scalar_specs in (
            ("alipay", "alipay", (("private_key_pem", "PRIVATE_KEY_FILE", True),
                                    ("public_key_pem", "PUBLIC_KEY_FILE", False)),
             (("app_id", "APP_ID"), ("seller_id", "SELLER_ID"))),
            ("wechat", "wechat", (("private_key_pem", "PRIVATE_KEY_FILE", True),
                                   ("public_key_pem", "PUBLIC_KEY_FILE", False)),
             (("app_id", "APP_ID"), ("mch_id", "MCH_ID"), ("cert_serial", "CERT_SERIAL"),
              ("public_key_id", "PUBLIC_KEY_ID"), ("api_v3_key", "API_V3_KEY"))),
        ):
            values = current_channel_values(channel)
            overrides = dict(settings.get(channel, {}))
            for form_name, config_name in scalar_specs:
                new_value = getattr(payload, prefix + "_" + form_name).strip()
                if getattr(payload, "clear_" + prefix + "_" + form_name, False):
                    overrides[config_name] = ""
                elif new_value:
                    overrides[config_name] = new_value
                elif config_name not in overrides and values.get(config_name):
                    # Keep legacy environment-file values until explicitly replaced in the UI.
                    overrides[config_name] = values[config_name]
            for form_name, config_name, private in key_specs:
                form_value = getattr(payload, prefix + "_" + form_name)
                clear = getattr(payload, "clear_" + prefix + "_" + form_name.replace("_pem", ""))
                existing_path = overrides.get(config_name) or values.get(config_name)
                if form_value.strip():
                    content = _key_bytes(form_value, private)
                    path = PAYMENT_KEY_DIR / f"{channel}_{'private' if private else 'public'}_{secrets.token_hex(8)}.pem"
                    _write_private_file(path, content)
                    overrides[config_name] = str(path)
                elif clear:
                    overrides[config_name] = ""
                elif existing_path:
                    overrides[config_name] = existing_path
            overrides["ENABLED"] = "1" if getattr(payload, prefix + "_enabled") else "0"
            if channel == "wechat" and overrides.get("API_V3_KEY") and len(overrides["API_V3_KEY"].encode("utf-8")) != 32:
                if payload.wechat_api_v3_key:
                    raise HTTPException(400, "微信 APIv3 密钥必须是 32 字节")
            settings[channel] = overrides

        if payload.alipay_enabled:
            _validate_channel_settings("alipay", settings["alipay"])
        if payload.wechat_enabled:
            _validate_channel_settings("wechat", settings["wechat"])
        serialized = json.dumps(settings, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        _write_private_file(PAYMENT_SETTINGS_PATH, serialized)
        referenced = {str(settings.get(channel, {}).get(key, ""))
                      for channel in CONFIG_FIELDS for key in ("PRIVATE_KEY_FILE", "PUBLIC_KEY_FILE")}
        if PAYMENT_KEY_DIR.exists():
            for path in PAYMENT_KEY_DIR.glob("*.pem"):
                if str(path) not in referenced:
                    try:
                        path.unlink()
                    except OSError:
                        log.warning("Unable to remove retired payment key file")


def _validate_channel_settings(channel, values):
    if not all(values.get(key) for key in CONFIG_FIELDS[channel]):
        raise HTTPException(400, ("支付宝" if channel == "alipay" else "微信") + "配置还不完整")
    for name in ("PRIVATE_KEY_FILE", "PUBLIC_KEY_FILE"):
        if not Path(values[name]).is_file():
            raise HTTPException(400, ("支付宝" if channel == "alipay" else "微信") + "密钥文件缺失")
    for name, private in (("PRIVATE_KEY_FILE", True), ("PUBLIC_KEY_FILE", False)):
        try:
            data = Path(values[name]).read_bytes()
            key = serialization.load_pem_private_key(data, password=None) if private else serialization.load_pem_public_key(data)
            if not isinstance(key, rsa.RSAPrivateKey if private else rsa.RSAPublicKey) or key.key_size < 2048:
                raise ValueError("weak key")
        except Exception as exc:
            raise HTTPException(400, "密钥文件不是有效的 RSA PEM") from exc
    if channel == "wechat" and len(values["API_V3_KEY"].encode("utf-8")) != 32:
        raise HTTPException(400, "微信 APIv3 密钥必须是 32 字节")


def config(channel, require_enabled=False):
    values = current_channel_values(channel)
    base = payment_base_url()
    if ((require_enabled and values.get("ENABLED") != "1")
            or not all(values.get(key) for key in CONFIG_FIELDS[channel])
            or not base.startswith("https://") or "?" in base or "#" in base):
        raise HTTPException(503, "此支付方式尚未开放")
    values["NOTIFY_URL"] = base + "/api/v1/payments/" + channel + "/notify"
    for field in ("PRIVATE_KEY_FILE", "PUBLIC_KEY_FILE"):
        if not Path(values[field]).is_file():
            raise HTTPException(503, "支付证书尚未配置")
    if channel == "wechat" and len(values["API_V3_KEY"].encode()) != 32:
        raise HTTPException(503, "支付配置异常")
    return values


def payment_catalog():
    channels = []
    for channel in CONFIG_FIELDS:
        try:
            config(channel, require_enabled=True)
            channels.append(channel)
        except HTTPException:
            pass
    return {"purchase_enabled": bool(channels), "channels": channels,
            "currency": "CNY", "message": "" if channels else "在线支付暂未开放，可使用兑换码充值",
            "plans": [{"code": code, **plan} for code, plan in PLANS.items()]}


@router.get("/admin/api/payments")
def get_admin_payment_settings(request: Request, response: Response):
    require_ready(request)
    from admin_v2 import no_store
    no_store(response)
    return {"channels": admin_payment_settings(),
            "callback_base_url": payment_base_url()}


@router.put("/admin/api/payments")
def put_admin_payment_settings(payload: PaymentSettingsPayload, request: Request):
    token = require_ready(request)
    require_csrf(request, token)
    save_payment_settings(payload)
    log_action("payment_config_updated", "payments",
               f"alipay_enabled={int(payload.alipay_enabled)};wechat_enabled={int(payload.wechat_enabled)}",
               request_ip(request))
    return {"ok": True, "channels": admin_payment_settings()}


def init_payments():
    with open_db() as db:
        db.executescript("""
            CREATE TABLE IF NOT EXISTS payment_orders (
                order_id TEXT PRIMARY KEY,
                user_id INTEGER NOT NULL REFERENCES app_users(id),
                request_id TEXT NOT NULL,
                channel TEXT NOT NULL,
                plan_code TEXT NOT NULL,
                plan_name TEXT NOT NULL,
                days INTEGER NOT NULL CHECK(days>0),
                amount_fen INTEGER NOT NULL CHECK(amount_fen>0),
                status TEXT NOT NULL DEFAULT 'pending',
                app_id TEXT NOT NULL,
                merchant_id TEXT NOT NULL,
                provider_trade_id TEXT,
                prepay_data TEXT,
                created_at TEXT NOT NULL,
                expires_at TEXT NOT NULL,
                paid_at TEXT,
                vip_expires_at TEXT,
                last_query_at INTEGER NOT NULL DEFAULT 0,
                UNIQUE(user_id,request_id),
                UNIQUE(channel,provider_trade_id)
            );
            CREATE INDEX IF NOT EXISTS idx_payment_orders_user
                ON payment_orders(user_id,created_at);
        """)


def sign(message, path):
    key = serialization.load_pem_private_key(Path(path).read_bytes(), password=None)
    if not isinstance(key, rsa.RSAPrivateKey) or key.key_size < 2048:
        raise ValueError("RSA key must be at least 2048 bits")
    return base64.b64encode(key.sign(message, padding.PKCS1v15(), hashes.SHA256())).decode()


def verify(message, signature, path):
    key = serialization.load_pem_public_key(Path(path).read_bytes())
    if not isinstance(key, rsa.RSAPublicKey) or key.key_size < 2048:
        raise ValueError("Invalid RSA public key")
    key.verify(base64.b64decode(signature, validate=True), message,
               padding.PKCS1v15(), hashes.SHA256())


def alipay_params(method, biz, cfg, notify=False):
    params = {"app_id": cfg["APP_ID"], "method": method, "format": "JSON",
              "charset": "utf-8", "sign_type": "RSA2", "version": "1.0",
              "timestamp": utc_now().astimezone(timezone(timedelta(hours=8))).strftime("%Y-%m-%d %H:%M:%S"),
              "biz_content": json.dumps(biz, ensure_ascii=False, separators=(",", ":"))}
    if notify:
        params["notify_url"] = cfg["NOTIFY_URL"]
    canonical = "&".join(f"{key}={params[key]}" for key in sorted(params))
    params["sign"] = sign(canonical.encode(), cfg["PRIVATE_KEY_FILE"])
    return urlencode(params, quote_via=quote)


def alipay_query(order, cfg):
    body = alipay_params("alipay.trade.query", {"out_trade_no": order["order_id"]}, cfg).encode()
    request = UrlRequest("https://openapi.alipay.com/gateway.do", data=body,
                         headers={"Content-Type": "application/x-www-form-urlencoded;charset=utf-8"})
    with urlopen(request, timeout=12) as response:
        raw = response.read(262145).decode("utf-8")
    envelope = json.loads(raw)
    # Verify the exact response object bytes; reserializing JSON changes the signature.
    match = re.search(r'"alipay_trade_query_response"\s*:\s*', raw)
    if not match:
        raise ValueError("Missing query response")
    result, length = json.JSONDecoder().raw_decode(raw[match.end():])
    verify(raw[match.end():match.end() + length].encode(), envelope.get("sign", ""), cfg["PUBLIC_KEY_FILE"])
    if result.get("code") != "10000":
        if result.get("sub_code") == "ACQ.TRADE_NOT_EXIST":
            return
        raise ValueError("Alipay query failed")
    if result.get("trade_status") in ("TRADE_SUCCESS", "TRADE_FINISHED"):
        if result.get("out_trade_no") != order["order_id"]:
            raise ValueError("Query order mismatch")
        # Query is authenticated by this app's signed request and platform response.
        settle("alipay", result["out_trade_no"], result["trade_no"],
               amount_fen(result["total_amount"]), cfg["APP_ID"], result.get("seller_id") or cfg["SELLER_ID"])
    elif result.get("trade_status") == "TRADE_CLOSED":
        close_order(order["order_id"])


def wechat_verify(raw, headers, cfg):
    stamp = headers.get("Wechatpay-Timestamp", "")
    nonce = headers.get("Wechatpay-Nonce", "")
    if (headers.get("Wechatpay-Serial") != cfg["PUBLIC_KEY_ID"]
            or not stamp.isdigit() or abs(time.time() - int(stamp)) > 300 or not nonce):
        raise ValueError("Untrusted WeChat signature metadata")
    verify(stamp.encode() + b"\n" + nonce.encode() + b"\n" + raw + b"\n",
           headers.get("Wechatpay-Signature", ""), cfg["PUBLIC_KEY_FILE"])


def wechat_request(method, path, cfg, payload=None):
    body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")) if payload is not None else ""
    stamp, nonce = str(int(time.time())), secrets.token_hex(16)
    signature = sign(f"{method}\n{path}\n{stamp}\n{nonce}\n{body}\n".encode(), cfg["PRIVATE_KEY_FILE"])
    authorization = (f'WECHATPAY2-SHA256-RSA2048 mchid="{cfg["MCH_ID"]}",'
                     f'nonce_str="{nonce}",timestamp="{stamp}",'
                     f'serial_no="{cfg["CERT_SERIAL"]}",signature="{signature}"')
    request = UrlRequest("https://api.mch.weixin.qq.com" + path,
                         data=body.encode() if payload is not None else None, method=method,
                         headers={"Authorization": authorization, "Accept": "application/json",
                                  "Content-Type": "application/json", "User-Agent": "RYLUX-payments/1",
                                  "Wechatpay-Serial": cfg["PUBLIC_KEY_ID"]})
    with urlopen(request, timeout=12) as response:
        raw = response.read(262145)
        wechat_verify(raw, response.headers, cfg)
    return json.loads(raw)


def wechat_settle(data):
    amount = data.get("amount", {})
    if data.get("trade_state") != "SUCCESS":
        return
    if amount.get("currency") != "CNY" or type(amount.get("total")) is not int:
        raise ValueError("Invalid currency/amount")
    settle("wechat", data["out_trade_no"], data["transaction_id"], amount["total"],
           data.get("appid", ""), data.get("mchid", ""))


def amount_fen(value):
    try:
        amount = Decimal(str(value)) * 100
        if not amount.is_finite() or amount <= 0 or amount != amount.to_integral_value():
            raise ValueError("Invalid amount")
        return int(amount)
    except InvalidOperation as exc:
        raise ValueError("Invalid amount") from exc


def settle(channel, order_id, trade_id, total, app_id, merchant_id):
    if not trade_id or len(trade_id) > 128:
        raise ValueError("Invalid transaction id")
    with open_db() as db:
        db.execute("BEGIN IMMEDIATE")
        order = db.execute("SELECT * FROM payment_orders WHERE order_id=?", (order_id,)).fetchone()
        if (order is None or order["channel"] != channel or order["amount_fen"] != total
                or order["app_id"] != app_id or order["merchant_id"] != merchant_id):
            raise ValueError("Payment does not match order")
        if order["status"] == "paid":
            if order["provider_trade_id"] != trade_id:
                raise ValueError("Transaction mismatch")
            return
        user = db.execute("SELECT vip_expires_at FROM app_users WHERE id=?", (order["user_id"],)).fetchone()
        now = utc_now()
        old = parse_iso(user["vip_expires_at"])
        expiry = iso(max(old, now) + timedelta(days=order["days"])) if old else iso(now + timedelta(days=order["days"]))
        # The unique provider transaction and the row lock protect all delivery paths.
        db.execute("UPDATE payment_orders SET status='paid',provider_trade_id=?,paid_at=?,vip_expires_at=? WHERE order_id=?",
                   (trade_id, iso(now), expiry, order_id))
        db.execute("UPDATE app_users SET vip_expires_at=?,vip_level=1,updated_at=? WHERE id=?",
                   (expiry, iso(now), order["user_id"]))
        db.execute("""INSERT INTO vip_events
            (user_id,source,duration_seconds,reference,old_expires_at,new_expires_at,created_at)
            VALUES(?,?,?,?,?,?,?)""", (order["user_id"], "payment_" + channel, order["days"] * 86400,
                                      "payment:" + order_id, user["vip_expires_at"], expiry, iso(now)))
        from agent_referral import reward_paid_purchase
        reward_paid_purchase(db, order["user_id"], order["days"], "payment:" + order_id, now)


def close_order(order_id):
    with open_db() as db:
        db.execute("UPDATE payment_orders SET status='closed' WHERE order_id=? AND status='pending'", (order_id,))


def public_order(order):
    return {key: order[key] for key in ("order_id", "channel", "plan_code", "plan_name", "days", "amount_fen",
                                        "status", "created_at", "expires_at", "paid_at", "vip_expires_at")}


def create_order(channel, payload, authorization):
    auth, _ = require_user(authorization)
    if not re.fullmatch(r"[A-Za-z0-9_-]{16,64}", payload.request_id):
        raise HTTPException(400, "无效请求编号")
    cfg = config(channel, require_enabled=True)
    plan = PLANS.get(payload.plan_code)
    if plan is None:
        raise HTTPException(400, "无效套餐")
    merchant_id = cfg["SELLER_ID" if channel == "alipay" else "MCH_ID"]
    with open_db() as db:
        db.execute("BEGIN IMMEDIATE")
        order = db.execute("SELECT * FROM payment_orders WHERE user_id=? AND request_id=?",
                           (auth["user_id"], payload.request_id)).fetchone()
        if order:
            if order["channel"] != channel or order["plan_code"] != payload.plan_code:
                raise HTTPException(409, "请求编号已用于另一笔订单")
            if order["status"] != "pending":
                return {"order": public_order(order)}
            if parse_iso(order["expires_at"]) <= utc_now():
                raise HTTPException(409, "订单支付时间已结束，请查询结果后重新下单")
            if order["app_id"] != cfg["APP_ID"] or order["merchant_id"] != merchant_id:
                raise HTTPException(409, "商户配置已更改，请联系管理员处理旧订单")
        else:
            since = iso(utc_now() - timedelta(hours=1))
            count = db.execute("SELECT COUNT(*) FROM payment_orders WHERE user_id=? AND created_at>?",
                               (auth["user_id"], since)).fetchone()[0]
            if count >= 20:
                raise HTTPException(429, "下单过于频繁，请稍后再试")
            now = utc_now()
            order_id = "R" + secrets.token_hex(15)
            db.execute("""INSERT INTO payment_orders
                (order_id,user_id,request_id,channel,plan_code,plan_name,days,amount_fen,app_id,merchant_id,created_at,expires_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""", (order_id, auth["user_id"], payload.request_id, channel,
                payload.plan_code, plan["name"], plan["days"], plan["amount_fen"], cfg["APP_ID"], merchant_id,
                iso(now), iso(now + timedelta(minutes=30))))
            order = db.execute("SELECT * FROM payment_orders WHERE order_id=?", (order_id,)).fetchone()
        order = dict(order)
    try:
        if channel == "alipay":
            payment = {"order_string": alipay_params("alipay.trade.app.pay", {
                "out_trade_no": order["order_id"], "total_amount": f'{order["amount_fen"] / 100:.2f}',
                "subject": "RYLUX VIP " + order["plan_name"], "seller_id": merchant_id,
                "product_code": "QUICK_MSECURITY_PAY",
                "time_expire": parse_iso(order["expires_at"]).astimezone(timezone(timedelta(hours=8))).strftime("%Y-%m-%d %H:%M:%S"),
            }, cfg, notify=True)}
        else:
            prepay = json.loads(order["prepay_data"]) if order["prepay_data"] else wechat_request("POST", "/v3/pay/transactions/app", cfg, {
                "appid": cfg["APP_ID"], "mchid": merchant_id, "description": "RYLUX VIP " + order["plan_name"],
                "out_trade_no": order["order_id"], "notify_url": cfg["NOTIFY_URL"], "time_expire": order["expires_at"],
                "amount": {"total": order["amount_fen"], "currency": "CNY"}})
            prepay_id = prepay["prepay_id"]
            with open_db() as db:
                db.execute("UPDATE payment_orders SET prepay_data=? WHERE order_id=?", (json.dumps({"prepay_id": prepay_id}), order["order_id"]))
            stamp, nonce = str(int(time.time())), secrets.token_hex(16)
            payment = {"appid": cfg["APP_ID"], "partnerid": merchant_id, "prepayid": prepay_id,
                       "package": "Sign=WXPay", "noncestr": nonce, "timestamp": stamp,
                       "sign": sign(f'{cfg["APP_ID"]}\n{stamp}\n{nonce}\n{prepay_id}\n'.encode(), cfg["PRIVATE_KEY_FILE"])}
        return {"order": public_order(order), "payment": payment}
    except Exception as exc:
        log.warning("Payment setup failed for %s (%s)", order["order_id"], type(exc).__name__)
        raise HTTPException(502, "支付下单暂未成功，请使用同一请求编号重试或在充值记录查询") from exc


@router.post("/api/v1/payments/alipay/orders")
def create_alipay(payload: OrderPayload, authorization: str | None = Header(default=None)):
    return create_order("alipay", payload, authorization)


@router.post("/api/v1/payments/wechat/orders")
def create_wechat(payload: OrderPayload, authorization: str | None = Header(default=None)):
    return create_order("wechat", payload, authorization)


@router.get("/api/v1/payments/orders")
def list_orders(authorization: str | None = Header(default=None)):
    auth, _ = require_user(authorization)
    with open_db() as db:
        rows = db.execute("SELECT * FROM payment_orders WHERE user_id=? ORDER BY created_at DESC LIMIT 30",
                          (auth["user_id"],)).fetchall()
    return {"orders": [public_order(row) for row in rows]}


@router.get("/api/v1/payments/orders/{order_id}")
def get_order(order_id: str, authorization: str | None = Header(default=None)):
    auth, _ = require_user(authorization)
    with open_db() as db:
        order = db.execute("SELECT * FROM payment_orders WHERE order_id=? AND user_id=?",
                           (order_id, auth["user_id"])).fetchone()
        if order is None:
            raise HTTPException(404, "订单不存在")
        claim = db.execute("UPDATE payment_orders SET last_query_at=? WHERE order_id=? AND status='pending' AND last_query_at<?",
                           (int(time.time()), order_id, int(time.time()) - 5)).rowcount
    sync_ok = True
    if claim:
        try:
            cfg = config(order["channel"])
            if order["channel"] == "alipay":
                alipay_query(order, cfg)
            else:
                data = wechat_request("GET", "/v3/pay/transactions/out-trade-no/" + order_id
                                      + "?mchid=" + quote(cfg["MCH_ID"]), cfg)
                if data.get("out_trade_no") != order_id:
                    raise ValueError("Query order mismatch")
                wechat_settle(data)
                if data.get("trade_state") in ("CLOSED", "REVOKED", "PAYERROR"):
                    close_order(order_id)
        except Exception as exc:
            sync_ok = False
            log.warning("Payment query failed for %s (%s)", order_id, type(exc).__name__)
    with open_db() as db:
        order = db.execute("SELECT * FROM payment_orders WHERE order_id=?", (order_id,)).fetchone()
    return {"order": public_order(order), "sync_ok": sync_ok}


async def callback_body(request):
    body = bytearray()
    async for part in request.stream():
        body.extend(part)
        if len(body) > 65536:
            raise HTTPException(413, "Callback too large")
    return bytes(body)


def handle_alipay(raw):
    cfg = config("alipay")
    pairs = parse_qsl(raw.decode("utf-8"), keep_blank_values=True, max_num_fields=100)
    data = dict(pairs)
    if len(data) != len(pairs) or data.get("sign_type") != "RSA2":
        raise ValueError("Invalid callback encoding")
    canonical = "&".join(f"{key}={data[key]}" for key in sorted(data)
                         if key not in ("sign", "sign_type") and data[key] != "")
    verify(canonical.encode(), data.get("sign", ""), cfg["PUBLIC_KEY_FILE"])
    if data.get("app_id") != cfg["APP_ID"] or data.get("seller_id") != cfg["SELLER_ID"]:
        raise ValueError("Incorrect merchant")
    if data.get("trade_status") in ("TRADE_SUCCESS", "TRADE_FINISHED"):
        settle("alipay", data["out_trade_no"], data["trade_no"], amount_fen(data["total_amount"]), data["app_id"], data["seller_id"])


@router.post("/api/v1/payments/alipay/notify")
async def alipay_notify(request: Request):
    raw = await callback_body(request)
    try:
        await run_in_threadpool(handle_alipay, raw)
    except Exception as exc:
        log.warning("Alipay callback rejected (%s)", type(exc).__name__)
        return Response("failure", status_code=400, media_type="text/plain")
    return Response("success", media_type="text/plain")


def handle_wechat(raw, headers):
    cfg = config("wechat")
    wechat_verify(raw, headers, cfg)
    event = json.loads(raw)
    if event.get("event_type") != "TRANSACTION.SUCCESS":
        raise ValueError("Unsupported notification")
    resource = event["resource"]
    if resource.get("algorithm") != "AEAD_AES_256_GCM":
        raise ValueError("Unsupported encryption")
    plaintext = AESGCM(cfg["API_V3_KEY"].encode()).decrypt(resource["nonce"].encode(),
        base64.b64decode(resource["ciphertext"], validate=True), resource.get("associated_data", "").encode())
    data = json.loads(plaintext)
    if (data.get("appid") != cfg["APP_ID"] or data.get("mchid") != cfg["MCH_ID"]
            or data.get("trade_state") != "SUCCESS"):
        raise ValueError("Incorrect merchant/state")
    wechat_settle(data)


@router.post("/api/v1/payments/wechat/notify")
async def wechat_notify(request: Request):
    raw = await callback_body(request)
    try:
        await run_in_threadpool(handle_wechat, raw, request.headers)
    except Exception as exc:
        log.warning("WeChat callback rejected (%s)", type(exc).__name__)
        return Response('{"code":"FAIL","message":"Verification failed"}', status_code=400, media_type="application/json")
    return Response(status_code=204)
