# 支付宝和微信 VIP 自助充值

此功能采用支付宝 App 支付和微信 App 支付（普通商户直连）。Android 账号中心的“在线充值 VIP”支持下单、官方 SDK 收银台、返回后查询，以及最近 30 笔订单查询。充值记录在服务器保存，卸载 App 后重新登录仍可查询。接口和 SDK 代码就绪不代表商户产品已经开通或已完成真实支付验收。

## 固定套餐

| plan_code | 名称 | 时长 | 金额 | amount_fen |
| --- | --- | --- | --- | --- |
| 30d | 月卡 | 30 天 | ¥20 | 2000 |
| 90d | 季卡 | 90 天 | ¥48 | 4800 |
| 180d | 半年卡 | 180 天 | ¥78 | 7800 |
| 365d | 年卡 | 365 天 | ¥118 | 11800 |

金额和时长取自 `payment_v1.PLANS` 并写入订单快照。客户端只能传套餐代码和幂等编号；额外的金额、天数字段会被拒绝。VIP 从 `max(当前到期时间, 入账时间)` 顺延。原有试用剩余时长也保留。付费邀请奖励沿用原规则，受累计 365 天上限约束。

## 商户侧准备

1. 支付宝开放平台建立对应移动应用，签约 **App 支付**，配置 RSA2 应用公钥，取得 APP_ID、收款 SELLER_ID 和支付宝公钥。本实现使用公钥模式，不使用证书模式或服务商代商户模式。应用私钥必须是 PEM 格式 RSA 2048 位以上；支付宝公钥也是 PEM 格式。
2. 微信开放平台建立并审核移动应用，包名为 `com.example.pakredirect`，填写实际发布 APK 的应用签名 MD5，取得移动应用 AppID。微信商户平台开通 **App 支付**，完成该 AppID 与商户号绑定。设置 APIv3 密钥，下载商户 API 证书对应私钥并记录证书序列号。
3. 微信使用 **微信支付公钥模式**：从商户平台下载微信支付公钥 PEM 并记录其 `PUB_KEY_ID_...` 标识。不要把商户 API 证书、商户公钥、微信支付公钥混用。当前实现不自动下载/更新平台证书；更换微信支付公钥时同步替换 PEM 和 ID 后重启服务。
4. 回调域名必须能从公网通过有效 HTTPS 证书访问；禁止登录页、验证码或网页防火墙挑战。VPS 时间要通过 NTP 同步。支付回调不需要用户 Bearer Token，必须通过支付平台签名校验。

不要把应用私钥、APIv3 密钥或完整支付参数发到聊天、提交到 Git、打进 APK 或写入访问日志。Android 从服务端取得签过名的单笔支付参数；无需把商户密钥配置进 Gradle。

微信回调需要把移动应用 AppID 配置为 APK 的 URL scheme。构建 Android APK 时传入同一个 AppID，例如 `gradlew assembleRelease -PryluxWechatAppId=wx1234567890abcdef`；该值不是密钥。不要使用 Gradle 中的示例占位值发布。支付宝不要求此 Gradle 参数。

## 后台页面配置

支付功能部署到 VPS 后，管理员进入 **后台 → 支付配置**，填入 HTTPS 回调域名、支付宝/微信 AppID、商户信息与 PEM 密钥，勾选需要开放的渠道并点击“保存支付配置”。保存后立即生效，不需要 SSH、手工编辑环境文件或重启服务。页面不会回显已保存的密钥；密钥留空表示保留原值，勾选清除才会删除。只允许 HTTPS 访问后台。

配置保存在数据库同目录的 `payment_settings.json`（权限 `0600`），PEM 文件保存在 `payment_keys/`（目录 `0700`、文件 `0600`）。systemd 服务已允许写入 `/opt/pakredirect-license/data`。管理页面兼容已有的 `payments.env` 配置；页面保存后，以页面设置覆盖对应渠道。

微信 AppID 还必须用于构建 APK 的 URL scheme，并与服务端后台填写的微信 AppID 相同。商户侧仍需开通 App 支付并完成移动应用和商户号绑定；页面配置不能替代商户平台审核。

## VPS 环境变量兼容配置

常规配置请使用后台页面。以下方式只用于没有管理页面或故障恢复时；如使用环境文件，服务重启后读取。

以下路径与仓库的 `pakredirect-license.service` 一致。创建配置目录后，在 VPS 上使用编辑器配置真实值；不要保留示例值。

```bash
sudo install -d -m 0750 -o root -g paklicense /etc/pakredirect-license/payments
sudo install -m 0600 -o root -g root /dev/null /etc/pakredirect-license/payments.env
sudoedit /etc/pakredirect-license/payments.env
```

`payments.env` 内容（systemd EnvironmentFile 格式，无 `export`）：

```ini
RYLUX_PAYMENT_BASE_URL=https://verify.lovenom.eu.org

RYLUX_ALIPAY_ENABLED=0
RYLUX_ALIPAY_APP_ID=填写支付宝应用ID
RYLUX_ALIPAY_SELLER_ID=填写收款支付宝用户ID
RYLUX_ALIPAY_PRIVATE_KEY_FILE=/etc/pakredirect-license/payments/alipay_private.pem
RYLUX_ALIPAY_PUBLIC_KEY_FILE=/etc/pakredirect-license/payments/alipay_public.pem

RYLUX_WECHAT_ENABLED=0
RYLUX_WECHAT_APP_ID=填写微信移动应用AppID
RYLUX_WECHAT_MCH_ID=填写微信商户号
RYLUX_WECHAT_CERT_SERIAL=填写商户API证书序列号
RYLUX_WECHAT_PRIVATE_KEY_FILE=/etc/pakredirect-license/payments/wechat_private.pem
RYLUX_WECHAT_PUBLIC_KEY_ID=填写PUB_KEY_ID_开头的微信支付公钥ID
RYLUX_WECHAT_PUBLIC_KEY_FILE=/etc/pakredirect-license/payments/wechat_public.pem
RYLUX_WECHAT_API_V3_KEY=填写32字节APIv3密钥
```

将四个 PEM 文件安全上传到指定目录，执行：

```bash
sudo chown root:paklicense /etc/pakredirect-license/payments/*.pem
sudo chmod 0640 /etc/pakredirect-license/payments/*.pem
sudo install -d -m 0755 /etc/systemd/system/pakredirect-license.service.d
sudo tee /etc/systemd/system/pakredirect-license.service.d/payments.conf >/dev/null <<'EOF'
[Service]
EnvironmentFile=-/etc/pakredirect-license/payments.env
EOF
sudo systemctl daemon-reload
```

只启用已配置的渠道：完成该渠道配置后，把它的 `ENABLED` 改为 `1` 并重启服务。`0` 会停止新下单，已存在订单的回调和查询仍保留；不要删旧密钥、旧商户信息或更换商户身份，直到待处理订单全部完成。

## 一键部署脚本

代码发布到 GitHub `main` 后，在 VPS 执行：

```bash
curl -fsSLo /tmp/deploy-vip-payments.sh https://raw.githubusercontent.com/yusuijiang01-orz/PakRedirect/main/tools/deploy-vip-payments.sh && sudo bash /tmp/deploy-vip-payments.sh
```

脚本固定拉取 GitHub `main` 的提交，先校验文件与提交 SHA 一致、Python 语法及现有加密依赖，再备份即将覆盖的 7 个后端/后台文件、安装并重启服务。它会检查 `/healthz`、4 个固定套餐的接口金额和后台页面资源；检查失败时自动还原这些文件并重启旧服务。备份位于 `/var/backups/pakredirect-license/vip-payments/`。脚本不覆盖数据库、商户配置、Nginx 或其他文件；首次需要更新依赖时，应先按变更文档处理。

如果代码尚未推送到 GitHub `main`，此下载地址暂不可用；不要运行旧的 `deploy-trial-72h.sh` 来部署支付功能。已创建支付订单后不要用旧数据库覆盖新数据库，否则会丢失支付入账和订单。`payment_orders` 是增量表，回滚代码时保留即可。

Nginx 现有 `/api/v1/` 代理可以转发支付接口。将仓库 `nginx-pakredirect-license.conf` 新增的两段精确回调 `location` 合并进 VPS 现有 HTTPS server 块，执行 `sudo nginx -t && sudo systemctl reload nginx`。不要覆盖生产环境其他自定义配置。精确回调规则避免多个用户的支付通知共享平台 IP 时触发普通用户限流。回调 URL：

```text
https://verify.lovenom.eu.org/api/v1/payments/alipay/notify
https://verify.lovenom.eu.org/api/v1/payments/wechat/notify
```

部署后还需构建、签名并发布包含新充值入口的 Android APK。后端更新不会给旧 APK 增加原生收银台入口。首次上线分别以真实商户完成下单、取消、成功支付、延迟通知/查单和重复通知验收，再开放充值。当前代码没有自动退款、退款撤销 VIP 或财务对账任务；退款需要在商户后台处理并人工核对会员时长和邀请奖励。

## 接口

```text
GET  /api/v1/plans                     套餐、金额（分）、已开放渠道
POST /api/v1/payments/alipay/orders    Bearer 用户登录；支付宝下单
POST /api/v1/payments/wechat/orders    Bearer 用户登录；微信下单
GET  /api/v1/payments/orders           Bearer 用户登录；本人最近 30 笔订单
GET  /api/v1/payments/orders/{id}      Bearer 用户登录；本人订单并主动向平台查单
POST /api/v1/payments/alipay/notify    支付宝签名回调
POST /api/v1/payments/wechat/notify    微信签名加密回调
GET  /admin/api/payments               管理员会话；返回非敏感配置状态
PUT  /admin/api/payments               管理员会话 + CSRF；保存/启停渠道
```

下单请求示例（两个渠道格式相同）：

```json
{"plan_code":"30d","request_id":"由客户端生成并持久化的UUID"}
```

实际 `request_id` 必须是 16–64 位字母、数字、下划线或连字符。相同账号和请求编号重复请求返回同一订单；不能更换套餐/渠道。网络超时要保留编号重试，不能每次创建新编号。支付窗口 30 分钟，每账号每小时最多创建 20 笔。服务端返回 `order` 和 `payment`：支付宝 `payment.order_string` 交给 `PayTask.payV2`；微信 `payment` 字段交给 `PayReq`。已完成的重复下单返回 `order` 即可，不再调起收银台。

查询返回 `order.status` 为 `pending`、`paid` 或 `closed`，以 `paid` 为唯一成功依据。`sync_ok=false` 表示本次平台查单失败，可稍后重试。过了本地支付窗口也不自行把订单判为未支付，防止漏掉延迟回调；微信/支付宝确认关闭后才标记 `closed`。同一订单的主动查单最多每 5 秒一次。

下单快照、平台交易号、VIP 到期时间和 `vip_events` 在 SQLite `BEGIN IMMEDIATE` 事务内核对/入账。平台交易号唯一；重复通知和查单并发不重复开通。验签、商户身份、订单号或金额不匹配时不发会员。微信 APIv3 响应也验签，回调验证时间戳后使用 AES-256-GCM 解密。支付宝 `TRADE_SUCCESS` / `TRADE_FINISHED` 入账，微信 `SUCCESS` 入账。

数据库中的 `payment_orders` 为财务关联记录，不应直接删除或级联删除。售后定位优先使用订单号；服务日志仅记录订单号/异常类型，不记录签名、原始回调或密钥。没有后台定时对账任务时，支付通知是主通道，用户查询是补偿通道；日常应在两家商户平台核对未入账订单。

## 官方资料与依赖

- [支付宝 App 支付参数说明](https://developer.alibaba.com/docs/doc.htm?articleId=105465&docType=1&treeId=193)
- [支付宝 Android SDK 接入](https://opendocs.alipay.com/open/54/104509)
- [微信 App 支付开发指引](https://pay.wechatpay.cn/doc/v3/merchant/4013070176)
- [微信 APIv3 签名与验签](https://pay.wechatpay.cn/doc/v3/merchant/4012365342)
- [微信 App 调起支付签名](https://pay.wechatpay.cn/doc/v3/merchant/4012365340)
- [微信支付公钥验签](https://pay.wechatpay.cn/doc/v3/merchant/4013053249)

Android 使用官方 `com.alipay.sdk:alipay-sdk-android:15.8.09` 和 `com.tencent.mm.opensdk:wechat-sdk-android:6.8.40`。Python 使用已有 `cryptography` 实现官方 RSA2/APIv3 协议；请求校验兼容 FastAPI 支持的 Pydantic 1/2，不新增后端依赖。公钥验签模式、商户 App 产品和 APK 发布签名必须相互匹配。
