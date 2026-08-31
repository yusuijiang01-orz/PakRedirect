# RYLUX V1

RYLUX 是从原 PakRedirect 演进而来的账号 / VIP 驱动游戏模块启动器。V1 保留已经验证过的本机 PAK 直供能力，同时把产品入口从“卡密直接登录”升级为“用户账号 + 使用时间 + 游戏模块”。

> V1 为保持 Android 升级兼容，应用包名暂时仍是 `com.example.pakredirect`；用户可见名称已经改为 `RYLUX`。

## V1 功能

- 用户注册 / 账号密码登录；
- 新注册用户自动获得 24 小时体验时间；
- 账号使用时间统一由 `vip_expires_at` 管理；
- 套餐模型：7 / 30 / 90 / 180 / 365 天；
- V1 暂不接在线支付，使用兑换码给账号充值时间；
- 管理后台可查看用户、VIP 到期时间、最后登录时间与 IP；
- 管理员可禁用 / 启用用户并直接续期；
- 兑换码支持批量生成、隐藏 / 显示、复制、CSV / TXT 导出；
- 首个游戏模块为“三国汉化”；
- 点击启动前由服务端检查账号与 VIP 权限；
- 授权通过后启动本机 `127.0.0.1:18480` PAK 服务并自动启动目标游戏；
- 旧 `/api/v1/license/verify` 继续保留，方便旧 APK 过渡。

## 用户 API

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

用户密码使用 PBKDF2-SHA256 保存。登录 Token 只把 SHA-256 摘要写入数据库；Android 端 Token 使用 Android Keystore + AES-GCM 保存。

## 24 小时体验

注册成功时：

```text
trial_started_at = 注册时间
trial_expires_at = 注册时间 + 24小时
vip_expires_at = trial_expires_at
```

兑换或管理员续期时：

```text
当前仍有效 -> 从原到期时间继续累加
已经过期   -> 从当前时间重新计算
```

## 兑换码

原“卡密系统”在 RYLUX V1 中继续保留，但主要用途改为账号充值码。

新生成兑换码记录充值天数 `duration_days`，用户兑换成功后：

1. 对账号 `vip_expires_at` 增加对应天数；
2. 记录兑换用户与兑换时间；
3. 立即停用该兑换码，防止重复使用。

旧版直接卡密登录接口仍保留用于兼容过渡。

## 本地游戏模块

RYLUX 通过本机回环地址监听：

```text
http://127.0.0.1:18480
```

提供：

- `GET /health`
- `GET` / `HEAD /linkspak.txt`
- `GET` / `HEAD /pak/<文件名>`
- PAK Range / HTTP 200 / 206

目标游戏仍由自己的 UID 下载并写入自己的私有目录，所以这个推荐模式不依赖 Root。

启动链路：

```text
RYLUX 登录
  -> 服务端检查账号 / VIP
  -> 授权 sg_localization
  -> 启动 127.0.0.1:18480
  -> 提供 linkspak.txt + PAK
  -> 启动 com.tepaylink.tamgioiphantranhmobile
```

## 管理后台

```text
https://verify.lovenom.eu.org/admin
```

页面包括：

- 数据概览
- 用户 / VIP
- 兑换码
- 操作日志
- 系统设置

后台仍复用现有 HTTPS 域名、SQLite 数据库和管理员登录体系。

## 编译

Android SDK 35 + JDK 17：

```text
gradle :app:assembleDebug
```

输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 服务端

部署与升级说明见：

```text
backend/README.md
```
