# Android WebRTC Remote v3.0.0-alpha1

雷电模拟器内运行的局域网远控 APK。画面由 Android MediaProjection 采集，使用原生 WebRTC H.264/VP8 实时发送；触控通过 WebRTC DataChannel 送入用户手动启用的 AccessibilityService。

## 使用

1. 在雷电模拟器内安装并登录 Tailscale，确认获得 `100.x` 地址。
2. 安装 Actions 产出的 `app-release.apk`。
3. 打开 APK，点击“开启远控辅助服务”并启用本应用。
4. 返回 APK，点击“授权录屏并启动”，在系统弹窗中允许。
5. iPhone 连接同一 Tailnet，Safari 打开 APK 显示的地址并输入 PIN。

## 安全边界

- 不需要 ROOT，不修改 IMEI、MAC、设备身份或其他 App 数据。
- HTTP/信令服务绑定到模拟器自己的 Tailscale `100.x` 地址。
- WebRTC 不配置公共 STUN/TURN；候选地址来自设备本地网络。
- 每次服务启动生成随机六位 PIN。
- 无障碍服务声明 `canRetrieveWindowContent=false`，只负责手势及系统导航。

## Alpha 限制

- Android 系统要求每次新录屏会话由用户确认；无 ROOT 应用不能绕过。
- 需要在雷电模拟器内部运行 Tailscale；只在 Windows 主机运行 Tailscale 不足以直连 APK。
- 当前触控实现为轻触和单段滑动，键盘、音量和旋转将在实机链路确认后补齐。
- release 产物暂用 debug key 签名，便于直接侧载测试，不适合商店发布。

## 第三方组件

- `io.github.webrtc-sdk:android:150.7871.01`，BSD-3-Clause。
- NanoHTTPD 2.3.1，BSD-3-Clause。
- AndroidX / Material Components，Apache-2.0。
