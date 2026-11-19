# RYLUX 2.5.49 — Gitee 清单故障安全恢复测试版（RC3）

## 故障现象及根因

2.5.47 测试版启动脚本时弹出「目标游戏启动失败：启动失败：无法从 Gitee 获取内容清单」，发生在汉化模块启动阶段，不在 CoraFlow OCR 运行阶段。

调用链：ScriptToolsActivity → LocalizationLaunchPreparation → InterceptService → ProtectedContentManager.fetchManifest（已认证的服务端内容授权清单）。

历史代码把服务端返回的 HTTP 503 与 401/403/404 归为同一类不可恢复错误，令已经完整验证过的本地加密资源不能在临时上游故障时作为安全回退。

## RC3 修复

1. 401、403、404 继续严格拒绝启动，不能用旧缓存绕过授权/配置异常。
2. 503 和其他临时服务器/网络错误走 NetworkException 分支。
3. 只在本机存在设备授权可解封、清单 HMAC 校验通过、linkspak SHA-256 有效，且**所有加密 PAK 完整 SHA-256、长度、加密头字段全部验证通过**时，才允许离线回退。
4. 无有效缓存仍须停止，显示「汉化内容服务暂不可用，且本机没有完整的已验证加密资源」。
5. 保留 2.5.47 的 OCR 双帧购买数量确认、背包遮挡恢复、截图像素缓存优化。
6. 正式服务端仍需修复读取 Gitee 清单的上游故障；此 APK 不能伪造授权清单。

## 测试安装及构建

- 代码分支：codex/rylux-2546-vision-stability
- 版本：2.5.49-manifest-recovery-rc3
- Android versionCode：173
- GitHub Actions：.github/workflows/build-apk.yml，publish_release=false
- 签名仍由原来的 RYLUX_KEYSTORE_B64、RYLUX_KEYSTORE_PASSWORD、RYLUX_KEY_ALIAS、RYLUX_KEY_PASSWORD 四个 GitHub Secrets 提供。
- **覆盖安装，不要先卸载、清除应用数据或删除本地汉化资源**。设备原有已验证缓存是临时服务故障时的安全回退来源。
- GitHub Actions 必须同时通过单元测试、assembleRelease、apksigner 校验，下载 Artifact ZIP 后解压安装 APK。

## 验收

1. 模拟服务器 HTTP 503 且完整本地加密缓存存在：日志显示继续使用已验证资源并成功启动本地 PAK 服务。
2. 模拟 HTTP 503 且缓存缺失/内容 SHA-256 不匹配：必须停止，不得绕过。
3. 模拟 401、403、404：必须停止，不得离线回退。
4. 正常联网：正常获取远端清单与资源更新；不退化成仅使用缓存。
5. 若启动成功，继续验证加密 CoraFlow 工作流加载版本、药瓶识别、购买确认和连续挂机。
6. 仍失败时导出启动阶段错误日志，记录服务端 HTTP 状态与缓存校验结果。

**注意**：此次代码编译成功不等于服务器已经恢复，也不保证全新安装（没有完整加密缓存）时能脱离授权服务器启动。服务端 Gitee 清单故障需要另行修复。

上一版测试说明：docs/RYLUX_2.5.48_manifest_recovery_rc2.md。
