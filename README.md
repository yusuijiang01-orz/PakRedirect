# RYLUX 2.1

RYLUX 是账号 / VIP 驱动的游戏模块启动器。当前首个模块为“封神榜汉化”，保留 `127.0.0.1:18480` + `linkspak.txt` + PAK Range 的本机直供链路。

> 为保持覆盖升级兼容，Android 包名暂时仍是 `com.example.pakredirect`。

## 账号与使用时间

- 用户注册 / 账号密码登录；
- 新注册用户自动获得 24 小时体验；
- 同一公网 IP 48 小时内最多成功注册 1 个账号；
- Token、记住密码均使用 Android Keystore + AES-GCM 保存；
- 套餐模型：7 / 30 / 90 / 180 / 365 天；
- 当前通过兑换码为账号增加 VIP 使用时间；
- 管理后台支持用户、VIP、兑换码、日志和管理员设置。

## 封神榜模块

启动链路：

```text
RYLUX 登录
  -> 服务端检查账号 / VIP
  -> 授权 sg_localization
  -> 检查 GitHub 内容清单
  -> 下载有变化的 PAK / linkspak.txt
  -> SHA-256 + 文件大小校验
  -> 校验成功后原子替换本地资源
  -> 启动 127.0.0.1:18480
  -> 游戏读取 linkspak.txt + PAK
  -> 启动 com.tepaylink.tamgioiphantranhmobile
```

如果网络不可用或更新检查失败，RYLUX 会继续使用最近一次校验成功的资源；本机没有热更新资源时回退到 APK 内置 PAK。

## PAK 热更新

资源清单：

```text
pak/manifest.json
```

当 `main` 分支中的以下文件发生变化：

```text
pak/*.pak
pak/linkspak.txt
```

GitHub Actions `Publish RYLUX content manifest` 会自动重新计算：

- 文件大小；
- SHA-256；
- 下载地址；
- 内容指纹版本。

APP 启动封神榜模块前读取：

```text
https://raw.githubusercontent.com/yusuijiang01-orz/PakRedirect/main/pak/manifest.json
```

只有通过 SHA-256 和大小校验的新文件才会替换旧资源。

## APP 更新

RYLUX 启动时会检查 GitHub 最新 Release：

```text
https://github.com/yusuijiang01-orz/PakRedirect/releases/latest
```

发现更高版本后提示用户下载 APK。Android 原生代码仍通过正常 APK 覆盖升级，不使用动态 DEX 热修复。

## 固定正式签名

正式 Release 不再使用 GitHub Runner 临时生成的 debug keystore。`.github/workflows/build-apk.yml` 使用同一套持久化 Release Keystore。

在仓库 GitHub Actions Secrets 中配置：

```text
RYLUX_KEYSTORE_B64
RYLUX_KEYSTORE_PASSWORD
RYLUX_KEY_ALIAS
RYLUX_KEY_PASSWORD
```

其中 `RYLUX_KEYSTORE_B64` 是正式 `.jks` 文件的 Base64 内容。不要把 `.jks` 或密码提交进公开仓库。

第一次从历史 Debug 签名切换到正式 Release 签名时，Android 仍需要卸载旧 Debug 版一次。安装第一个正式签名版之后，只要 `applicationId` 不变、签名证书不变且 `versionCode` 递增，后续版本即可直接覆盖更新。

Build and Release workflow 需要输入：

```text
version_name，例如 2.1.0
version_code，例如 7
```

生成：

```text
RYLUX-v2.1.0.apk
```

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

## 本地 HTTP 服务

```text
http://127.0.0.1:18480
```

提供：

- `GET /health`
- `GET` / `HEAD /linkspak.txt`
- `GET` / `HEAD /pak/<文件名>`
- PAK Range / HTTP 200 / 206

目标游戏使用自身 UID 下载并写入自己的私有目录，因此推荐模式不依赖 Root。

## 管理后台

```text
https://verify.lovenom.eu.org/admin
```

## 开发构建

Android SDK 35 + JDK 17：

```text
gradle :app:assembleDebug
```

正式发布统一使用 GitHub Actions 的 `Build and Release RYLUX APK`。

后端部署说明见 `backend/README.md`。
