# PakRedirect 1.1

轻量、无第三方运行时依赖的 Android Root 测试工程，面向 **MuMu Android 12**。

默认完成两件事：

1. 将 `https://cdn-tgpt.tepaylink.vn/production/ui.pak?ver=2` 映射为用户选择的本地 `.pak`。
2. 拦截 `https://cdn.tamgioipt.vn/linkspak.txt`，仅把 `ui.pak` 的文件大小字段动态替换成本地 PAK 的实际字节数。

例如本地 PAK 为 `440448` bytes 时：

```text
https://cdn-tgpt.tepaylink.vn/production/ui.pak?ver=2,data/,ui.pak,429293,0,2
```

会在返回给客户端时变为：

```text
https://cdn-tgpt.tepaylink.vn/production/ui.pak?ver=2,data/,ui.pak,440448,0,2
```

其余 `linkspak.txt` 内容不修改。

## 工作方式

- 启动时先读取远端 `linkspak.txt`，在 hosts 重定向前缓存并修改 `ui.pak` 大小。
- 如果远端读取失败，使用 `assets/linkspak_fallback.txt` 内置模板。
- 将 PAK Host 和 manifest Host 临时写入 `/system/etc/hosts` 指向 `127.0.0.1`。
- 使用 `iptables` 将本机 `127.0.0.1:443/TCP` 重定向到应用 TLS 服务 `127.0.0.1:18443`。
- TLS 证书 SAN 同时包含两个目标 Host。
- PAK 路径按 Host + Path 匹配，**忽略 query string**，因此 `?ver=2`、`?ver=3` 等均可命中。
- PAK 支持 `GET`、`HEAD`、HTTP 200、单段 Range、HTTP 206、`Content-Range`。
- 停止时清理本应用写入的 hosts 与 iptables 规则。

## 首次使用

1. 编译并安装 APK。
2. 打开应用，点击“安装 PakRedirect 系统 CA（Root）”。
3. Root 授权后重启 MuMu 一次。
4. 再次打开应用。
5. 保持或修改目标 `ui.pak` HTTPS URL。
6. 选择本地 `.pak` 文件，例如 `/storage/emulated/0/Download/封神榜/ui.pak`。
7. 点击“启动拦截”。
8. 测试完成后点击“停止并清理规则”。

## 编译

无 AndroidX、OkHttp、BouncyCastle 等第三方运行时依赖。

需要 Android SDK 35 与 JDK 17+：

```bash
gradle :app:assembleDebug
```

输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 限制

- 需要 Root，且 `/system` 可写。
- 目标应用如使用 certificate/public-key pinning、硬编码服务器 IP、独立 DoH 或仅 QUIC/HTTP3，可能绕过或拒绝该方式。
- 这是针对 PAK 测试的轻量工具，不是通用 HTTPS 代理；两个目标 Host 上的其他路径返回 404。
- CA 私钥随 APK 一起提供，仅用于隔离测试环境，不应安装到日常主力设备。
