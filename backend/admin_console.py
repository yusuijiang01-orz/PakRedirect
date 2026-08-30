import base64
import hashlib
import hmac
import html
import os
import secrets
import sqlite3
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.parse import parse_qs

from fastapi import APIRouter, Request
from fastapi.responses import HTMLResponse, RedirectResponse

DB_PATH = Path(os.environ.get("PAKREDIRECT_LICENSE_DB", "./data/licenses.db")).resolve()
COOKIE = "pakredirect_admin"
SESSION_SECONDS = 8 * 60 * 60
MAX_BATCH = 200
PRESETS = (1, 7, 30, 90, 180, 360)
PBKDF2_ITERATIONS = 310_000
DEFAULT_ADMIN_USER = "admin"
# Hash for the temporary first-login password: PakRedirect@2026!
DEFAULT_ADMIN_PASSWORD_HASH = "pbkdf2_sha256$310000$UGFrUmVkaXJlY3RCb290c3RyYXA$ir6hc4X3vpfLe1L9df89hGhcTbVnbcCQWrksMxcv248"
router = APIRouter()


def utc_now():
    return datetime.now(timezone.utc)


def iso(dt):
    return dt.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def normalize_key(value):
    return value.strip().upper()


def key_hash(value):
    return hashlib.sha256(normalize_key(value).encode()).hexdigest()


def key_hint(value):
    compact = normalize_key(value).replace("-", "")
    return compact[-6:] if len(compact) >= 6 else compact


def generate_key():
    raw = secrets.token_hex(10).upper()
    return "PR-" + "-".join(raw[i:i + 4] for i in range(0, len(raw), 4))


def open_db():
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    db = sqlite3.connect(DB_PATH, timeout=5)
    db.row_factory = sqlite3.Row
    db.execute("PRAGMA journal_mode=WAL")
    db.execute("PRAGMA busy_timeout=5000")
    return db


def init_admin_indexes():
    with open_db() as db:
        db.execute("CREATE INDEX IF NOT EXISTS idx_licenses_key_hint ON licenses(key_hint)")
        db.execute("CREATE INDEX IF NOT EXISTS idx_licenses_last_seen_ip ON licenses(last_seen_ip)")
        db.execute(
            """
            CREATE TABLE IF NOT EXISTS admin_settings (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                username TEXT NOT NULL,
                password_hash TEXT NOT NULL,
                must_change_password INTEGER NOT NULL DEFAULT 1,
                session_secret TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """
        )
        row = db.execute("SELECT id FROM admin_settings WHERE id=1").fetchone()
        if row is None:
            db.execute(
                """
                INSERT INTO admin_settings
                    (id, username, password_hash, must_change_password, session_secret, updated_at)
                VALUES (1, ?, ?, 1, ?, ?)
                """,
                (
                    DEFAULT_ADMIN_USER,
                    DEFAULT_ADMIN_PASSWORD_HASH,
                    secrets.token_urlsafe(48),
                    iso(utc_now()),
                ),
            )
        db.commit()


def admin_config():
    with open_db() as db:
        return db.execute(
            "SELECT username,password_hash,must_change_password,session_secret,updated_at FROM admin_settings WHERE id=1"
        ).fetchone()


def configured():
    try:
        row = admin_config()
        return bool(row and row["username"] and row["password_hash"] and len(row["session_secret"] or "") >= 32)
    except sqlite3.OperationalError:
        return False


def b64e(value):
    return base64.urlsafe_b64encode(value).decode().rstrip("=")


def b64d(value):
    value += "=" * ((4 - len(value) % 4) % 4)
    return base64.urlsafe_b64decode(value.encode())


def make_password_hash(password):
    if len(password) < 10:
        raise ValueError("新密码至少 10 个字符")
    salt = secrets.token_bytes(18)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, PBKDF2_ITERATIONS)
    return f"pbkdf2_sha256${PBKDF2_ITERATIONS}${b64e(salt)}${b64e(digest)}"


def verify_hash(encoded, password):
    try:
        scheme, iterations_text, salt_text, digest_text = encoded.split("$", 3)
        iterations = int(iterations_text)
        if scheme != "pbkdf2_sha256" or not 100000 <= iterations <= 2000000:
            return False
        salt, expected = b64d(salt_text), b64d(digest_text)
        actual = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, iterations)
        return hmac.compare_digest(actual, expected)
    except Exception:
        return False


def verify_password(password):
    row = admin_config()
    return bool(row and verify_hash(row["password_hash"], password))


def new_session():
    row = admin_config()
    if not row:
        raise RuntimeError("管理员配置不存在")
    expires = int(time.time()) + SESSION_SECONDS
    payload = b64e(f"{row['username']}|{expires}|{secrets.token_hex(12)}".encode())
    sig = b64e(hmac.new(row["session_secret"].encode(), payload.encode(), hashlib.sha256).digest())
    return payload + "." + sig


def session_user(token):
    if not token:
        return None
    row = admin_config()
    if not row:
        return None
    try:
        payload, sig = token.split(".", 1)
        expected = hmac.new(row["session_secret"].encode(), payload.encode(), hashlib.sha256).digest()
        if not hmac.compare_digest(expected, b64d(sig)):
            return None
        username, expires, _nonce = b64d(payload).decode().split("|", 2)
        if username != row["username"] or int(expires) < int(time.time()):
            return None
        return username
    except Exception:
        return None


def must_change_password():
    row = admin_config()
    return bool(row and int(row["must_change_password"]) == 1)


def csrf(token):
    row = admin_config()
    if not row:
        return ""
    return hmac.new(row["session_secret"].encode(), ("csrf|" + token).encode(), hashlib.sha256).hexdigest()


async def form_data(request):
    body = await request.body()
    if len(body) > 65536:
        raise ValueError("表单数据过大")
    parsed = parse_qs(body.decode(), keep_blank_values=True, max_num_fields=64)
    return {k: (v[-1] if v else "") for k, v in parsed.items()}


def esc(value):
    return html.escape("" if value is None else str(value), quote=True)


STYLE = """<style>
:root{--bg:#f4f6f8;--p:#fff;--t:#17202a;--m:#6b7280;--l:#e5e7eb;--b:#2563eb}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--t);font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif}
.w{max-width:1180px;margin:auto;padding:24px}.top,.row,.acts,.quick{display:flex;gap:10px;align-items:center;flex-wrap:wrap}.top{justify-content:space-between}
.p{background:var(--p);border:1px solid var(--l);border-radius:14px;padding:18px;margin:16px 0}.grid{display:grid;grid-template-columns:repeat(4,1fr);gap:10px}
.s{border:1px solid var(--l);border-radius:10px;padding:12px}.s b{display:block;font-size:24px}.m{color:var(--m)}input,select{height:40px;border:1px solid #d1d5db;border-radius:8px;padding:0 10px}
input{min-width:180px}button,.btn{display:inline-flex;align-items:center;justify-content:center;height:38px;border:0;border-radius:8px;padding:0 13px;background:var(--b);color:#fff;font-weight:600;cursor:pointer;text-decoration:none;font-size:13px}
.sec{background:#eef2ff;color:#1e40af}.bad{background:#fee2e2;color:#991b1b}.good{background:#dcfce7;color:#166534}
table{width:100%;border-collapse:collapse;font-size:13px}th,td{padding:10px 7px;border-bottom:1px solid var(--l);text-align:left;vertical-align:middle}
th{color:var(--m)}.badge{padding:3px 7px;border-radius:999px;font-size:12px;font-weight:600}.ok{background:#dcfce7;color:#166534}.off{background:#fee2e2;color:#991b1b}.exp{background:#fef3c7;color:#92400e}
.acts form{display:flex;gap:5px}.acts select,.acts button{height:31px;font-size:12px}.created{background:#ecfdf5}.created textarea{width:100%;height:150px}
.notice{padding:10px;border-radius:8px;background:#fff7ed;color:#9a3412}.login{max-width:440px;margin:80px auto}.login input,.login button{width:100%;margin-top:8px}
.search{display:grid;grid-template-columns:1fr 150px auto;gap:8px}.search input{width:100%}label{font-size:13px;color:var(--m)}
@media(max-width:850px){.grid{grid-template-columns:repeat(2,1fr)}.search{grid-template-columns:1fr}.w{padding:12px}table{display:block;overflow-x:auto;white-space:nowrap}}
</style>"""


def page(title, body, status=200):
    doc = f"<!doctype html><html lang='zh-CN'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>{esc(title)}</title>{STYLE}</head><body>{body}</body></html>"
    r = HTMLResponse(doc, status_code=status)
    r.headers.update({
        "Cache-Control": "no-store, no-cache, must-revalidate",
        "Pragma": "no-cache",
        "X-Frame-Options": "DENY",
        "X-Content-Type-Options": "nosniff",
        "Referrer-Policy": "same-origin",
        "Content-Security-Policy": "default-src 'self';style-src 'self' 'unsafe-inline';script-src 'self' 'unsafe-inline';form-action 'self';frame-ancestors 'none';base-uri 'none'",
    })
    return r


def login_page(error="", status=200):
    err = f"<p style='color:#b91c1c'>{esc(error)}</p>" if error else ""
    first = "<div class='notice'>首次登录请使用默认账号 admin。登录后会强制修改密码。</div>" if must_change_password() else ""
    return page("PakRedirect 管理后台", f"""
    <div class='w login'><div class='p'><h1>PakRedirect</h1><p class='m'>卡密管理后台</p>{first}
    <form method='post' action='/admin/login'>
    <label>管理员账号</label><input name='username' autocomplete='username' required>
    <label>管理员密码</label><input name='password' type='password' autocomplete='current-password' required>
    <button>登录</button></form>{err}</div></div>""", status)


def auth(request):
    token = request.cookies.get(COOKIE)
    return token if session_user(token) else None


def need_login():
    return RedirectResponse("/admin/login", status_code=303)


def parse_iso(value):
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except Exception:
        return None


def row_status(row):
    if int(row["enabled"]) != 1:
        return "已禁用", "off"
    exp = parse_iso(row["expires_at"])
    if exp is None or exp <= utc_now():
        return "已到期", "exp"
    return "有效", "ok"


def load_rows(query, state):
    cond, params = [], []
    q = query.strip()
    if q:
        like = f"%{q}%"
        parts = ["label LIKE ? COLLATE NOCASE", "key_hint LIKE ? COLLATE NOCASE", "COALESCE(last_seen_ip,'') LIKE ? COLLATE NOCASE"]
        params += [like, like, like]
        if normalize_key(q).startswith("PR-"):
            parts.append("key_hash = ?")
            params.append(key_hash(q))
        cond.append("(" + " OR ".join(parts) + ")")
    now = iso(utc_now())
    if state == "enabled":
        cond.append("enabled=1 AND expires_at>?"); params.append(now)
    elif state == "disabled":
        cond.append("enabled=0")
    elif state == "expired":
        cond.append("expires_at<=?"); params.append(now)
    where = (" WHERE " + " AND ".join(cond)) if cond else ""
    with open_db() as db:
        rows = db.execute("""SELECT id,key_hint,label,expires_at,enabled,created_at,last_seen_at,last_seen_ip
            FROM licenses""" + where + " ORDER BY id DESC LIMIT 300", tuple(params)).fetchall()
        s = db.execute("""SELECT COUNT(*) total,
            SUM(CASE WHEN enabled=1 AND expires_at>? THEN 1 ELSE 0 END) active,
            SUM(CASE WHEN enabled=0 THEN 1 ELSE 0 END) disabled,
            SUM(CASE WHEN expires_at<=? THEN 1 ELSE 0 END) expired FROM licenses""", (now, now)).fetchone()
    return rows, {k: int(s[k] or 0) for k in ("total", "active", "disabled", "expired")}


def dashboard(token, query="", state="", created=None, message=""):
    rows, stats = load_rows(query, state)
    c = csrf(token)
    created_html = ""
    if created:
        created_html = f"""<div class='p created'><b>新生成卡密（仅本次显示明文）</b>
        <p class='m'>请立即复制保存，数据库仍只保存 SHA-256 哈希。</p>
        <textarea id='keys' readonly>{esc(chr(10).join(created))}</textarea><br>
        <button class='sec' type='button' onclick="navigator.clipboard.writeText(document.getElementById('keys').value)">复制全部</button></div>"""
    msg = f"<div class='notice'>{esc(message)}</div>" if message else ""
    tr = []
    for r in rows:
        st, cl = row_status(r)
        next_enabled = "0" if int(r["enabled"]) else "1"
        toggle = "禁用" if int(r["enabled"]) else "启用"
        klass = "bad" if int(r["enabled"]) else "good"
        opts = "".join(f"<option value='{d}' {'selected' if d==30 else ''}>+{d}天</option>" for d in PRESETS)
        tr.append(f"""<tr><td>#{r['id']}</td><td><code>***{esc(r['key_hint'])}</code></td><td>{esc(r['label'] or '-')}</td>
        <td><span class='badge {cl}'>{st}</span></td><td class='lt' data-v='{esc(r['expires_at'])}'>{esc(r['expires_at'])}</td>
        <td class='lt' data-v='{esc(r['last_seen_at'] or '')}'>{esc(r['last_seen_at'] or '-')}</td><td>{esc(r['last_seen_ip'] or '-')}</td>
        <td class='acts'><form method='post' action='/admin/license/{r['id']}/toggle'><input type='hidden' name='csrf' value='{c}'>
        <input type='hidden' name='enabled' value='{next_enabled}'><button class='{klass}'>{toggle}</button></form>
        <form method='post' action='/admin/license/{r['id']}/extend'><input type='hidden' name='csrf' value='{c}'><select name='days'>{opts}</select>
        <button class='sec'>续期</button></form></td></tr>""")
    if not tr:
        tr = ["<tr><td colspan='8' class='m' style='text-align:center;padding:24px'>暂无匹配卡密</td></tr>"]
    quick = "".join(f"<button name='days' value='{d}'>{d} 天</button>" for d in PRESETS)
    states = [("", "全部状态"), ("enabled", "有效"), ("disabled", "禁用"), ("expired", "到期")]
    options = "".join(f"<option value='{v}' {'selected' if state==v else ''}>{label}</option>" for v, label in states)
    return page("PakRedirect 卡密管理", f"""
    <div class='w'><div class='top'><div><h1>PakRedirect 卡密管理</h1><div class='m'>verify.lovenom.eu.org/admin</div></div>
    <div class='row'><a class='btn sec' href='/admin/change-password'>修改账号/密码</a>
    <form method='post' action='/admin/logout'><input type='hidden' name='csrf' value='{c}'><button class='sec'>退出登录</button></form></div></div>
    {msg}{created_html}
    <div class='p grid'><div class='s'><span class='m'>全部</span><b>{stats['total']}</b></div><div class='s'><span class='m'>有效</span><b>{stats['active']}</b></div>
    <div class='s'><span class='m'>禁用</span><b>{stats['disabled']}</b></div><div class='s'><span class='m'>到期</span><b>{stats['expired']}</b></div></div>
    <div class='p'><h3>创建卡密</h3><form method='post' action='/admin/license/create'><input type='hidden' name='csrf' value='{c}'>
    <div class='row'><div><label>标签（可选）</label><br><input name='label' maxlength='80' placeholder='客户A / 测试'></div>
    <div><label>生成数量</label><br><input name='quantity' type='number' min='1' max='{MAX_BATCH}' value='1' style='width:130px'></div></div>
    <p class='m'>有效期</p><div class='quick'>{quick}</div></form><p class='m'>一次最多 {MAX_BATCH} 张；完整卡密只显示一次。</p></div>
    <div class='p'><form method='get' class='search'><input name='q' value='{esc(query)}' placeholder='搜索：完整卡密 / 后6位 / 标签 / 最后登录 IP'>
    <select name='status'>{options}</select><button>搜索</button></form></div>
    <div class='p' style='overflow:hidden'><table><thead><tr><th>ID</th><th>卡密</th><th>标签</th><th>状态</th><th>到期时间</th><th>最后登录</th><th>最后 IP</th><th>操作</th></tr></thead>
    <tbody>{''.join(tr)}</tbody></table></div></div>
    <script>document.querySelectorAll('.lt').forEach(function(x){{var v=x.dataset.v;if(!v)return;var d=new Date(v);if(!isNaN(d))x.textContent=d.toLocaleString('zh-CN',{{hour12:false}})}})</script>""")


def change_password_page(token, error="", message=""):
    row = admin_config()
    c = csrf(token)
    forced = must_change_password()
    title = "首次登录：请修改管理员账号和密码" if forced else "修改管理员账号和密码"
    note = "<div class='notice'>默认密码仅用于首次登录。完成修改后默认密码立即失效。</div>" if forced else ""
    err = f"<p style='color:#b91c1c'>{esc(error)}</p>" if error else ""
    msg = f"<p style='color:#166534'>{esc(message)}</p>" if message else ""
    return page(title, f"""
    <div class='w login'><div class='p'><h2>{esc(title)}</h2>{note}{err}{msg}
    <form method='post' action='/admin/change-password'>
    <input type='hidden' name='csrf' value='{c}'>
    <label>当前密码</label><input name='current_password' type='password' autocomplete='current-password' required>
    <label>管理员账号</label><input name='username' value='{esc(row['username'] if row else DEFAULT_ADMIN_USER)}' minlength='3' maxlength='32' required>
    <label>新密码</label><input name='new_password' type='password' autocomplete='new-password' minlength='10' required>
    <label>确认新密码</label><input name='confirm_password' type='password' autocomplete='new-password' minlength='10' required>
    <button>保存并继续</button></form>
    {'' if forced else "<p><a href='/admin'>返回后台</a></p>"}</div></div>""")


def create_licenses(days, quantity, label):
    if days not in PRESETS:
        raise ValueError("有效期不在允许范围")
    if not 1 <= quantity <= MAX_BATCH:
        raise ValueError(f"生成数量必须在 1 到 {MAX_BATCH} 之间")
    now, expires = utc_now(), utc_now() + timedelta(days=days)
    made = []
    with open_db() as db:
        for _ in range(quantity):
            for _try in range(5):
                value = generate_key()
                try:
                    db.execute("""INSERT INTO licenses(key_hash,key_hint,label,expires_at,enabled,created_at)
                        VALUES(?,?,?,?,1,?)""", (key_hash(value), key_hint(value), label.strip()[:80], iso(expires), iso(now)))
                    made.append(value)
                    break
                except sqlite3.IntegrityError:
                    pass
            else:
                db.rollback()
                raise RuntimeError("生成唯一卡密失败")
        db.commit()
    return made


@router.get("/admin/login", response_class=HTMLResponse)
def login_get(request: Request):
    return RedirectResponse("/admin", 303) if auth(request) else login_page()


@router.post("/admin/login", response_class=HTMLResponse)
async def login_post(request: Request):
    try:
        f = await form_data(request)
    except ValueError as exc:
        return login_page(str(exc), 400)
    row = admin_config()
    if not row:
        return login_page("后台初始化失败", 503)
    user_ok = hmac.compare_digest(f.get("username", "").encode(), row["username"].encode())
    if not (user_ok and verify_password(f.get("password", ""))):
        return login_page("账号或密码错误", 401)
    token = new_session()
    target = "/admin/change-password" if must_change_password() else "/admin"
    r = RedirectResponse(target, 303)
    r.set_cookie(COOKIE, token, max_age=SESSION_SECONDS, httponly=True, secure=True, samesite="lax", path="/admin")
    return r


@router.get("/admin", response_class=HTMLResponse)
def admin_get(request: Request, q: str = "", status: str = ""):
    token = auth(request)
    if not token:
        return need_login()
    if must_change_password():
        return RedirectResponse("/admin/change-password", 303)
    if status not in ("", "enabled", "disabled", "expired"):
        status = ""
    return dashboard(token, q[:128], status)


@router.get("/admin/change-password", response_class=HTMLResponse)
def change_password_get(request: Request):
    token = auth(request)
    if not token:
        return need_login()
    return change_password_page(token)


@router.post("/admin/change-password", response_class=HTMLResponse)
async def change_password_post(request: Request):
    token = auth(request)
    if not token:
        return need_login()
    try:
        f = await form_data(request)
        if not hmac.compare_digest(csrf(token), f.get("csrf", "")):
            raise PermissionError("CSRF 校验失败")
        if not verify_password(f.get("current_password", "")):
            raise ValueError("当前密码错误")
        username = f.get("username", "").strip()
        if not 3 <= len(username) <= 32 or any(ch.isspace() for ch in username):
            raise ValueError("管理员账号需为 3-32 个字符且不能包含空格")
        new_password = f.get("new_password", "")
        if new_password != f.get("confirm_password", ""):
            raise ValueError("两次输入的新密码不一致")
        password_hash = make_password_hash(new_password)
        new_secret = secrets.token_urlsafe(48)
        with open_db() as db:
            db.execute(
                "UPDATE admin_settings SET username=?,password_hash=?,must_change_password=0,session_secret=?,updated_at=? WHERE id=1",
                (username, password_hash, new_secret, iso(utc_now())),
            )
            db.commit()
        new_token = new_session()
        r = RedirectResponse("/admin", 303)
        r.set_cookie(COOKIE, new_token, max_age=SESSION_SECONDS, httponly=True, secure=True, samesite="lax", path="/admin")
        return r
    except Exception as exc:
        return change_password_page(token, error=str(exc))


@router.post("/admin/logout")
async def logout(request: Request):
    token = auth(request)
    if not token:
        return need_login()
    f = await form_data(request)
    if not hmac.compare_digest(csrf(token), f.get("csrf", "")):
        return page("请求无效", "<div class='w notice'>CSRF 校验失败</div>", 403)
    r = RedirectResponse("/admin/login", 303)
    r.delete_cookie(COOKIE, path="/admin")
    return r


@router.post("/admin/license/create", response_class=HTMLResponse)
async def create_post(request: Request):
    token = auth(request)
    if not token:
        return need_login()
    if must_change_password():
        return RedirectResponse("/admin/change-password", 303)
    try:
        f = await form_data(request)
        if not hmac.compare_digest(csrf(token), f.get("csrf", "")):
            raise PermissionError("CSRF 校验失败")
        keys = create_licenses(int(f.get("days", "0")), int(f.get("quantity", "1")), f.get("label", ""))
        return dashboard(token, created=keys)
    except Exception as exc:
        return dashboard(token, message=f"创建失败：{exc}")


@router.post("/admin/license/{license_id}/toggle", response_class=HTMLResponse)
async def toggle_post(license_id: int, request: Request):
    token = auth(request)
    if not token:
        return need_login()
    if must_change_password():
        return RedirectResponse("/admin/change-password", 303)
    f = await form_data(request)
    if not hmac.compare_digest(csrf(token), f.get("csrf", "")):
        return dashboard(token, message="CSRF 校验失败")
    enabled = 1 if f.get("enabled") == "1" else 0
    with open_db() as db:
        cur = db.execute("UPDATE licenses SET enabled=? WHERE id=?", (enabled, license_id))
        db.commit()
    return dashboard(token, message="状态已更新" if cur.rowcount == 1 else "未找到卡密")


@router.post("/admin/license/{license_id}/extend", response_class=HTMLResponse)
async def extend_post(license_id: int, request: Request):
    token = auth(request)
    if not token:
        return need_login()
    if must_change_password():
        return RedirectResponse("/admin/change-password", 303)
    try:
        f = await form_data(request)
        if not hmac.compare_digest(csrf(token), f.get("csrf", "")):
            raise PermissionError("CSRF 校验失败")
        days = int(f.get("days", "0"))
        if days not in PRESETS:
            raise ValueError("续期天数不在允许范围")
        with open_db() as db:
            row = db.execute("SELECT expires_at FROM licenses WHERE id=?", (license_id,)).fetchone()
            if row is None:
                raise ValueError("未找到卡密")
            current = parse_iso(row["expires_at"])
            if current is None:
                raise ValueError("卡密到期时间异常")
            db.execute("UPDATE licenses SET expires_at=? WHERE id=?", (iso(max(current, utc_now()) + timedelta(days=days)), license_id))
            db.commit()
        return dashboard(token, message=f"已续期 {days} 天")
    except Exception as exc:
        return dashboard(token, message=f"续期失败：{exc}")
