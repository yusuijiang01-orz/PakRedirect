# RYLUX V1 后端

RYLUX V1 后端继续运行在现有 `verify.lovenom.eu.org`，使用 FastAPI + SQLite，并在现有卡密数据库上增量加入用户、VIP、会话、套餐和模块权限。

支付宝/微信自助充值的商户配置、VPS 环境变量、部署步骤和接口说明见 [PAYMENTS.md](PAYMENTS.md)。在线套餐为月卡 ¥20、季卡 ¥48、半年卡 ¥78、年卡 ¥118；未配置的渠道默认关闭。

## V1 数据

新增：

- `app_users`：账号、密码哈希、状态、VIP 到期、72h 体验、最后登录/IP；
- `app_sessions`：登录 Token 摘要、会话到期、设备哈希；
- `vip_events`：体验、兑换、管理员续期流水；
- `plans`：7 / 30 / 90 / 180 / 365 天套餐；
- `modules`：游戏模块；
- `module_access_logs`：模块启动授权日志。

`registration_guard_v1.py` 会给 `app_users` 增量增加 `registration_ip_hash`。同一设备和同一 IP 在滚动 48 小时内各最多领取 3 次试用；任一额度用尽后仍可注册，但不会领取试用。设备 ID 为空时只按 IP 额度判定。明文注册 IP 不新增持久化字段；原有最后登录 IP 字段继续按既有逻辑使用。

现有 `licenses` 表继续保留，并增量加入：

- `duration_days`
- `redeemed_by_user_id`
- `redeemed_at`

因此升级不会删除原有卡密数据。

## 用户接口

```text
GET  /api/v1/auth/captcha
POST /api/v1/auth/register
POST /api/v1/auth/login
POST /api/v1/auth/logout
GET  /api/v1/me
GET  /api/v1/plans
POST /api/v1/redeem
GET  /api/v1/modules
POST /api/v1/modules/sg_localization/authorize
```

注册前先请求 `GET /api/v1/auth/captcha`，返回 3 分钟有效的一次性 PNG 验证码挑战。注册请求必须提交 `captcha_id` 与 `captcha_code`；验证码最多尝试 5 次，且绑定签发时的 IP。同一设备、同一 IP 在滚动 48 小时内各最多领取 3 次试用。设备 ID 为空时按 IP 额度判定；若设备 ID 与 IP 均不可用则不发试用。未获试用的账号仍可注册。登录会返回 Bearer Token；服务端数据库只保存 Token 的 SHA-256 摘要。旧版客户端未提交验证码字段时无法注册，需要更新客户端。

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

管理员在 `/admin` 的“代理人”页把已注册用户设为代理人，并分别授予“管理所属用户”“发卡”“VIP 续期”权限。代理人使用原账号登录 `/agent`。管理员可将普通用户指派给代理人；代理人邀请码注册的用户，以及兑换代理人卡密的未归属用户，会自动归属该代理人。管理员列表仍可查看全部用户。代理人只能读取和操作 `owner_agent_id` 属于自己的普通用户，不能调用管理员接口。

代理人额度以人民币计，采用分为单位存储，管理员可充值或扣减并查看余额流水。当前代理套餐价格为：月卡 30 天 ¥20，季卡 90 天 ¥48，半年卡 180 天 ¥78，年卡 365 天 ¥118。发卡和续期按套餐价格扣除代理余额；余额检查、余额流水和对应发卡/VIP 操作在同一 SQLite 事务中完成，余额不足返回 409。已发出的卡不会因为后续余额调整而失效。旧版 `quota_days` 和 `agent_quota_events` 保留作历史记录，不会自动折算成人民币，新余额默认 ¥0。

代理后台提供所属用户与 VIP 管理、自助添加用户、批量续期、登录设备查看与解绑、用户启用/停用及删除、本人卡密管理/导出、余额与操作流水。每项操作都按已授予的权限限制；发卡权限只能查看本人生成的卡，管理用户或续期只能作用于自己的所属用户。管理员仍可查看和管理全体用户、所有卡密及代理人。

用户调用 `GET /api/v1/referrals/me` 获取邀请码及邀请统计。注册请求可传 `invite_code`。只有获批 72 小时试用、设备 ID 非空、且设备/IP 摘要与邀请者不同的注册计为有效；注册后立即过期的账号不计入。每位有效新用户为邀请者增加 **7 天 VIP**，累计邀请奖励最多 **365 天**。邀请关系在注册时固定，不能事后更换。

支付宝/微信在线支付确认成功后视为付费购买，按购买天数奖励邀请者。管理员发卡时勾选“已收款”，或代理人发卡时标记 `paid=true`，该卡兑换也视为付费购买；被邀请人兑换后，邀请者获得相同天数。代理人使用余额直接为所属用户续期也视为付费购买，并按续期天数奖励邀请者。普通/赠送卡、体验期和管理员人工续期不触发购买奖励。邀请奖励累计上限 **365 天**，包括有效注册奖励和付费购买奖励；超过上限的部分不再发放。在线支付的邀请奖励与订单入账在同一事务中完成，重复回调不会重复发放。

新增接口：

```text
GET  /api/v1/referrals/me                 Bearer 登录
POST /api/v1/auth/register                可选 invite_code
GET  /agent/api/me                        Bearer 代理人登录
GET  /agent/api/prices                    查看代理套餐价格
GET  /agent/api/overview                  查看所属用户/自有卡密概览
GET  /agent/api/users                     查看所属用户（需管理或续期权限）
POST /agent/api/users                     创建所属用户（需管理权限）
POST /agent/api/users/{id}/toggle         启用/停用所属用户（需管理权限）
POST /agent/api/users/{id}/extend         续期所属用户（扣余额并奖励有效邀请者）
POST /agent/api/users/batch-renew         批量续期所属用户
GET  /agent/api/users/{id}/sessions       查看所属用户会话（需管理权限）
POST /agent/api/users/{id}/unbind-device  解除所属用户设备绑定
DELETE /agent/api/users/{id}              删除所属用户（需管理权限）
GET  /agent/api/licenses                  查看自己发出的卡（需发卡权限）
POST /agent/api/licenses/generate         发卡（扣人民币余额）
POST /agent/api/licenses/{id}/toggle      启用/停用本人未兑换卡密
GET  /agent/api/licenses/export.csv       导出本人卡密
GET  /agent/api/balance-events            查看本人余额流水
GET  /agent/api/logs                      查看本人操作流水
POST /agent/api/change-password           修改代理人密码
GET  /admin/api/agents                    管理员列出代理人
PUT  /admin/api/agents/{id}               管理员设置代理权限
POST /admin/api/agents/{id}/balance       管理员调整人民币余额
GET  /admin/api/agents/{id}/balance-events 管理员查看代理余额流水
PUT  /admin/api/users/{id}/agent          管理员分配用户归属
```

上述管理员写接口沿用 Secure Cookie 与 CSRF。应用客户端需要在注册界面收集邀请码，并把它作为 `invite_code` 发给注册接口，用户才能在软件内参与活动。数据库迁移在服务启动时自动执行；部署时需同步 `agent_referral.py` 和 `agent_web/`。

## 已有 VPS 升级

### 部署 72 小时新用户体验期限

这项后端改动必须先推送到 GitHub `main`。在 VPS SSH 终端运行下面一条命令；脚本默认解析并部署当时 `main` 的最新提交，也可在 `sudo bash` 后追加一个完整 40 位 commit SHA 来固定版本：

```bash
curl -fsSLo /tmp/deploy-trial-72h.sh https://raw.githubusercontent.com/yusuijiang01-orz/PakRedirect/main/tools/deploy-trial-72h.sh && sudo bash /tmp/deploy-trial-72h.sh
```

脚本仅更新 `/opt/pakredirect-license/user_v1.py`、`registration_guard_v1.py`、`admin_web/app.js` 和 `admin_web/index.html`，将文件安装为 `paklicense` 所有，并重启 `pakredirect-license.service`。它会校验源码与固定 commit 一致、检查 Python 语法和本机健康接口；部署前四个文件都会备份到 `/var/backups/pakredirect-license/trial-72h/`。安装、重启或健康检查失败时会恢复全部备份文件，再重启并检查服务。不会修改数据库、配置、Nginx 或依赖。Android 上的体验时长标签需要另行构建并发布 APK。

### 仅部署邀请名单接口变更

账号中心改版的 APK 会读取 `GET /api/v1/referrals/me` 返回的 `invited_users`。如果 VPS 尚未包含该字段，可在 VPS 上通过 SSH 终端粘贴下面一条命令部署。命令从本功能分支获取脚本，脚本则从固定的完整 Git commit SHA 获取后端源码。它会先检查 Python 语法，再备份 VPS 当前的 `agent_referral.py`，原子替换文件、重启 API 并检查本机健康接口；重启或健康检查失败时会恢复备份并再次重启。

```bash
curl -fsSLo /tmp/deploy-referral-list.sh https://raw.githubusercontent.com/yusuijiang01-orz/PakRedirect/rylux-account-center-redesign/tools/deploy-referral-list.sh && sudo bash /tmp/deploy-referral-list.sh b66cc381251d3e4f6b65f313298e74a5fa2656c8
```

备份保存在 `/var/backups/pakredirect-license/referral-list/`。该步骤只替换 `/opt/pakredirect-license/agent_referral.py` 并重启 `pakredirect-license.service`；不会迁移/修改数据库、配置、Nginx 或 Python 依赖。部署成功后再构建并发布对应 APK。若 VPS 无法从 GitHub 拉取仓库提交，需要先解决该 VPS 的 GitHub 网络访问问题。

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

先请求 `GET /api/v1/auth/captcha`，从返回的 `image_base64` 显示验证码图片并读出四位字符。注册时使用返回的 `challenge_id` 和图片中的字符，且两次请求使用同一 IP：

```bash
curl -sS \
  -H 'Content-Type: application/json' \
  -d '{"username":"testuser","password":"test123456","device_id":"manual-test","captcha_id":"上一步返回的challenge_id","captcha_code":"图片中的四位字符"}' \
  https://verify.lovenom.eu.org/api/v1/auth/register
```

返回 Token 后：

```bash
curl -sS \
  -H 'Authorization: Bearer 这里填写Token' \
  https://verify.lovenom.eu.org/api/v1/me
```

在 IP 额度未用尽时，同一设备在滚动 48 小时内前三次可获批试用，第 4 次注册仍返回成功，但 `membership.active=false`。同一 IP 也独立按相同额度限制。使用与邀请者相同的设备或 IP 注册，即使获得试用，也不会计为有效邀请。

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
