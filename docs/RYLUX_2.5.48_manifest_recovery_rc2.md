# RYLUX 2.5.48：汉化内容清单服务不可用时的安全启动修复（RC2）

## 复现现象

模拟器安装 2.5.47 后，进入「脚本工作台」点击启动，出现：

- 「目标游戏启动失败」
- 「启动失败：无法从 Gitee 获取内容清单」

**这不是 OCR / Lua 脚本识别报错**。实际调用链：

`ScriptToolsActivity.prepareLocalizationThenStartRuntime`
→ `LocalizationLaunchPreparation.prepare`
→ `InterceptService.startServerLocked`
→ `ProtectedContentManager.checkAndApply`
→ `fetchManifest` 向 `verify.lovenom.eu.org/api/v1/content/sg_localization/manifest` 获取**按当前设备授权加密并校验的汉化资源清单**。

服务器的 Gitee 清单上游异常可能通过 HTTP 503 + `detail` 消息传回客户端。旧实现把 503 与 401/403/404 同样视为不可恢复错误，导致本机即使已有完整、可验证的加密 PAK 缓存也不会进入回退逻辑。

## RC2 修复

- 保持 HTTP 401/403/404 授权/配置失败直接阻止启动。
- HTTP 503，以及其他非永久授权错误，由现有 `NetworkException` 路径处理。
- **只有**本机存在可解开设备密钥、HMAC 正确、清单版本及所有本地 PAK 完整性检查通过的资源时，才恢复启动。
- 如果没有有效本地缓存，则继续安全停止，并显示缺少完整已验证资源的提示。
- 增加启动失败分类回归测试 `ContentManifestFailurePolicyTest`。
- 不修改服务器签名密钥、客户端授权机制、加密格式或生产 `workflow/manifest.json`。

## 构建与安装

- 分支：`codex/rylux-2546-vision-stability`
- 测试版本：`2.5.48-manifest-recovery-rc2`，Android versionCode 172。
- GitHub Actions：`.github/workflows/build-apk.yml`，`publish_release=false`。
- 使用既有 GitHub Actions Secrets：`RYLUX_KEYSTORE_B64`、`RYLUX_KEYSTORE_PASSWORD`、`RYLUX_KEY_ALIAS`、`RYLUX_KEY_PASSWORD`。
- 下载 Actions Artifact，解压 APK。请**覆盖安装**，不要先卸载 2.5.46/2.5.47，否则设备已验证的本地资源和设置可能丢失。
- 必须先确认单元测试、Release 编译、APK 签名验证均通过，再使用测试 APK。

## 测试方案

1. 先确认游戏在原机上安装且本地汉化资源曾成功验证，并备份配置。
2. 在汉化内容服务正常时启动，确认仍可正常更新资源并成功加载。
3. 在服务端模拟 HTTP 503、同时保持既有资源缓存完整的情况下，应该记录「内容服务暂不可用，继续使用已验证资源」，并允许启动。
4. 在 HTTP 503 且本机没有已验证的完整资源时，应该停止，不可从明文未签名包绕过。
5. 在 HTTP 401/403/404 时，无论缓存是否存在都禁止进入服务失联回退路径。
6. 检查游戏启动完成后是否加载线上 CoraFlow 工作流；日志中明确区分「汉化资源」与「工作流更新」。
7. 最后做 30 分钟和 2 小时挂机稳定性测试，收集失败前 30–50 行日志。

## 还需要服务端跟进

该 RC2 是客户端**已有缓存的可靠性修复**，不是修复远端 Gitee 仓库的服务端故障。若本机从未下载过完整汉化资源、服务端持续无法读取 Gitee 清单，则无法安全启动；需要在授权后端实现 Gitee/GitHub 多源读取、超时及状态监控。客户端不能自行生成、伪造或替代设备授权清单。

前一个版本的视觉稳定性测试说明见 `docs/RYLUX_2.5.47_vision_stability_rc1.md`。
