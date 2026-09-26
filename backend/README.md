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

`registration_guard_v1.py` 会给 `app_users` 增量增加 `registration_ip_hash`。有设备 ID 且该设备未领过试用时，最多允许同一 IP 在 48 小时内领取 3 次试用；其余账号仍可注册，但不会领取试用。明文注册 IP 不新增持久化字段；原有最后登录 IP 字段继续按既有逻辑使用。

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

注册在满足设备/IP 领取规则时赠送 24 小时体验；不满足时仍可注册，但体验立即到期。登录会返回 Bearer Token；服务端数据库只保存 Token 的 SHA-256 摘要。

首个模块的用户可见名称为“封神榜汉化”；内部模块代码仍保持 `sg_localization`，避免破坏已有客户端接口。

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

## 代理人与邀请活动

管理员在 `/admin` 的“代理人”页输入已注册用户 ID，设置“管理所属用户”“发卡”“续期”三项权限与**剩余额度（天）**。代理人使用原账号登录 `/agent`。管理员可将普通用户指派给代理人；代理人邀请码注册的普通用户也自动归属该代理人。管理员列表仍可查看全部用户。代理人 `/agent/api/users` 只能列出和操作 `owner_agent_id` 为自己的普通用户，不能调用管理员接口。

代理人发卡或为所属用户续期，会按 `套餐天数 × 数量` 从额度扣除。扣除、发卡、VIP 流水在同一个 SQLite 事务中完成；余额不足返回 409。已发出的卡不会因为代理人额度调整而失效。管理员可在代理人页调整剩余额度；`agent_quota_events` 保存变动记录。代理人只能查看自己发出的卡。

用户调用 `GET /api/v1/referrals/me` 获取邀请码及邀请统计。注册请求可传 `invite_code`。只有获批 24 小时试用、设备 ID 非空、且设备/IP 摘要与邀请者不同的注册计为有效；注册后立即过期的账号不计入。每累计 **2 个有效邀请**，邀请者获得 **1 天 VIP**。邀请关系在注册时固定，不能事后更换。

V1 尚未接在线支付。管理员发卡时勾选“已收款”，或代理人发卡时标记 `paid=true`，该卡兑换才视为付费购买；被邀请人兑换后，邀请者获得相同天数。普通/赠送卡、体验期和管理员续期均不触发购买奖励。邀请奖励累计上限 **365 天**，包括有效注册奖励和付费兑换奖励；超过上限的部分不再发放。流水写入 `referral_rewards` 和 `vip_events`，每张付费卡只能兑换一次，防止重复奖励。

新增接口：

```text
GET  /api/v1/referrals/me                 Bearer 登录
POST /api/v1/auth/register                可选 invite_code
GET  /agent/api/me                        Bearer 代理人登录
GET  /agent/api/users                     查看所属用户（需管理权限）
POST /agent/api/users/{id}/toggle         启用/停用所属用户（需管理权限）
POST /agent/api/users/{id}/extend         续期所属用户（需续期权限，扣额度）
GET  /agent/api/licenses                  查看自己发出的卡（需发卡权限）
POST /agent/api/licenses/generate         发卡（需发卡权限，扣额度）
GET  /admin/api/agents                    管理员列出代理人
PUT  /admin/api/agents/{id}               管理员设置权限和剩余额度
PUT  /admin/api/users/{id}/agent          管理员分配用户归属
```

上述管理员写接口沿用 Secure Cookie 与 CSRF。应用客户端需要在注册界面收集邀请码，并把它作为 `invite_code` 发给注册接口，用户才能在软件内参与活动。数据库迁移在服务启动时自动执行；部署时需同步 `agent_referral.py` 和 `agent_web/`。

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
cp /tmp/RYLUX-v1/backend/registration_guard_v1.py /opt/pakredirect-license/
cp /tmp/RYLUX-v1/backend/agent_referral.py /opt/pakredirect-license/
cp -a /tmp/RYLUX-v1/backend/agent_web /opt/pakredirect-license/
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

`/agent` 和 `/agent/` 页面需要由 Nginx 转发到本机 FastAPI 的 18888 端口；升级时请同步 `nginx-pakredirect-license.conf`，并在 `nginx -t` 通过后 reload。

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

同一设备再次注册仍返回成功，但 `membership.active=false`，不会计为有效邀请。

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
