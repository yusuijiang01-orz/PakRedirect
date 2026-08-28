# PakRedirect 1.2

Android 本地 PAK 直供工具。推荐模式不依赖 Root、系统 CA、hosts 或 iptables，适合配合已修改下载入口的测试版游戏客户端。

## 推荐模式：本地 HTTP 直供

PakRedirect 在设备本机监听：

```text
http://127.0.0.1:18480
```

提供以下接口：

- `GET /health`：服务状态。
- `GET` / `HEAD /linkspak.txt`：动态清单。
- `GET` / `HEAD /pak/<文件名>`：本地 PAK，支持单段 Range、HTTP 200/206。

启动时会读取远端 `https://cdn.tamgioipt.vn/linkspak.txt`；读取失败时使用内置模板。对于所选目录内存在的同名 `.pak`，工具会同时修改清单中的下载 URL 和文件大小。例如：

```text
https://cdn-tgpt.tepaylink.vn/production/updatefs.pak?ver=403,data/,updatefs.pak,38101521,0,403
```

会变为：

```text
http://127.0.0.1:18480/pak/updatefs.pak,data/,updatefs.pak,38101521,0,403
```

修改版游戏客户端只需把清单入口改为：

```text
http://127.0.0.1:18480/linkspak.txt
```

登录、支付、服务器列表等其他 HTTPS 接口不会经过 PakRedirect。

## 使用步骤

1. 安装并打开 PakRedirect。
2. 选择包含本地 `.pak` 的目录。
3. 点击“启动本地直供（无需 Root / CA）”。
4. 确认日志显示本地 HTTP 服务监听于 `127.0.0.1:18480`。
5. 启动已修改清单地址的游戏客户端。
6. 测试结束后点击“停止并清理”。

## 旧版 HTTPS 兼容模式

应用仍保留旧版 HTTPS MITM 模式和 CA 安装入口。该模式需要可用的 root、系统信任库、hosts 与 iptables。在 Android 14/15 上，Conscrypt APEX 和挂载命名空间可能导致系统 CA 或 hosts 修改不生效，因此不再推荐。

Android 15 的 ADB 托管脚本仍保留：

```powershell
.\android15-adb-start.ps1 -Adb "D:\path\to\adb.exe"
.\android15-adb-stop.ps1 -Adb "D:\path\to\adb.exe"
```

## 编译

需要 Android SDK 35 与 JDK 17+：

```text
gradle :app:assembleDebug
```

输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

项目无 AndroidX、OkHttp、BouncyCastle 等第三方运行时依赖。
