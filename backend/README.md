# RYLUX V1 后端

RYLUX V1 后端继续运行在现有 `verify.lovenom.eu.org`，使用 FastAPI + SQLite，并在现有卡密数据库上增量加入用户、VIP、会话、套餐和模块权限。

## V1 数据

新增：

- `app_users`：账号、密码哈希、状态、VIP 到期、24h 体验、最后登录/IP；
- `app_sessions`：登录 Token 摘要、会话到期、设备哈希；
- `vip_events`：体验、兑换、管理员续期流水；
- `plans`：7 / 30 / 90 / 180 / 365 天套餐；
- `modules`：游戏模块；
- `module_access_logs`：模块启动授权日志。

现有 `licenses` 表继续保留，并增量加入：

- `duration_days`
- `redeemed_by_user_id`
- `redeemed_at`

因此升级不会删除原有卡密数据。

## 用户接口

```text
POST /api/v1/auth/register
POST /api/v1/auth/login
POST /api/v1/auth/logout
GET  /api/v1/me
GET  /api/v1/plans
POST /api/v1/redeem
GET  /api/v1/modules
POST /api/v1/modules/sg_localization/authorize
```

注册默认赠送 24 小时体验。登录会返回 Bearer Token；服务端数据库只保存 Token 的 SHA-256 摘要。

旧接口仍保留：

```text
POST /api/v1/license/verify
```

用于旧版 APK 过渡。

## 管理后台

```text
https://verify.lovenom.eu.org/admin
```

V1 增加：

- 用户 / VIP 列表；
- 用户名 / 最后 IP 搜索；
- 有效、到期、禁用筛选；
- 管理员禁用 / 启用用户；
- 管理员给用户 +1 / 7 / 30 / 90 / 180 / 365 天；
- 兑换码生成、完整值显示/隐藏/复制；
- 兑换码套餐天数；
- 已兑换兑换码禁止重新启用。

管理员登录体系、PBKDF2-SHA256、Secure + HttpOnly Cookie、CSRF 和登录限流继续保留。

## 已有 VPS 升级

数据库文件：

```text
/opt/pakredirect-license/data/licenses.db
```

不会删除。

```bash
set -e

rm -rf /tmp/RYLUX-v1
git clone --depth 1 https://github.com/yusuijiang01-orz/PakRedirect.git /tmp/RYLUX-v1

cp /tmp/RYLUX-v1/backend/app.py /opt/pakredirect-license/
cp /tmp/RYLUX-v1/backend/admin_v2.py /opt/pakredirect-license/
cp /tmp/RYLUX-v1/backend/admin_key_access.py /opt/pakredirect-license/
cp /tmp/RYLUX-v1/backend/admin_code_v1.py /opt/pakredirect-license/
cp /tmp/RYLUX-v1/backend/user_v1.py /opt/pakredirect-license/
cp /tmp/RYLUX-v1/backend/manage.py /opt/pakredirect-license/
cp /tmp/RYLUX-v1/backend/requirements.txt /opt/pakredirect-license/
cp /tmp/RYLUX-v1/backend/pakredirect-license.service /etc/systemd/system/pakredirect-license.service
cp /tmp/RYLUX-v1/backend/nginx-pakredirect-license.conf /etc/nginx/sites-available/pakredirect-license

rm -rf /opt/pakredirect-license/admin_web
cp -a /tmp/RYLUX-v1/backend/admin_web /opt/pakredirect-license/

chown -R paklicense:paklicense /opt/pakredirect-license
/opt/pakredirect-license/.venv/bin/pip install -r /opt/pakredirect-license/requirements.txt

systemctl daemon-reload
systemctl restart pakredirect-license

nginx -t
systemctl reload nginx
```

检查：

```bash
curl -sS https://verify.lovenom.eu.org/healthz
```

预期包含：

```json
{"ok":true,"product":"RYLUX","api":"v1"}
```

## 注册测试

```bash
curl -sS \
  -H 'Content-Type: application/json' \
  -d '{"username":"testuser","password":"test123456","device_id":"manual-test"}' \
  https://verify.lovenom.eu.org/api/v1/auth/register
```

返回 Token 后：

```bash
curl -sS \
  -H 'Authorization: Bearer 这里填写Token' \
  https://verify.lovenom.eu.org/api/v1/me
```

## V1 支付边界

V1 只建立套餐模型，不处理真实支付：

```text
7 / 30 / 90 / 180 / 365 天
```

用户通过兑换码充值。订单、支付回调、退款和补单放到后续版本。

## Android 模块链路

用户登录 -> VIP 授权 -> 本机启动：

```text
http://127.0.0.1:18480
```

游戏继续读取：

```text
http://127.0.0.1:18480/linkspak.txt
```

因此用户系统的升级不改变已经验证过的 localhost PAK 推送机制。
