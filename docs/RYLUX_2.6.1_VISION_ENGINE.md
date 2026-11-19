# RYLUX 2.6.1 — Vision Engine 2.0 脚本模块改造记录

> 状态：独立测试分支，**不等于生产稳定版**。本文件是新窗口继续开发、测试和回滚的入口。

## 版本、仓库与安全边界

- 基线分支：`codex/rylux-2546-vision-stability`，原 HEAD `cdb745dbc6516449cec04becbff1dafc349bb607`
- 新分支：`codex/rylux-261-vision-engine`
- Android：`versionName=2.6.1`，`versionCode=182`（2.6.1 首轮实机故障修正构建）
- 真实使用的脚本执行器：`app/src/main/java/com/example/pakredirect/CoraFlowWorkflowRuntime.java`
- Lua 兼容执行器：`LegacyLuaRuntime.java` 仍保留，尚未切换其旧接口的底层实现。
- OpenCV：`org.opencv:opencv:4.14.0`（官方 Android AAR；内部封装 C++ 原生库）；当前项目仍使用原有 NDK `Android.mk` 构建别的模块，不添加第二份不受管理的 OpenCV .so。
- 限定范围：脚本视觉识别、截图与画布校准、背包识别、状态恢复，以及为它们所需的 Android 构建依赖。**不改动**账号/VIP、微信、授权、汉化资源密钥、PAK 分发、远端工作流内容或游戏安装逻辑。
- 构建/签名仍使用 `.github/workflows/build-apk.yml` 原来的 GitHub Actions Secrets（RYLUX_KEYSTORE_B64 等），`publish_release=false`。只上传 Actions 测试 Artifact，**不创建正式 Release，也不同步 Gitee**。

## 真正实现的 2.6.1 功能（非占位）

### Screen Capture Layer
- 继续使用 `ScriptGameAdapter.captureDisplay()` 获取 Android 游戏窗口或经现有安全回退获取屏幕帧。
- `vision/VisionFrameCache.java` 对单张屏幕 Bitmap 生成一次 OpenCV RGB Mat，跨本帧多个模板识别复用。替换帧时释放旧 Mat。
- `vision/VisionViewport.java` 提供归一化和参考坐标映射。
- `vision/VisionViewportDetector.java` 使用对称黑边双边证据检测游戏画布；为了不让未校准布局导致点击偏移，**本版只记录候选视口**。实际点击仍用原有 1920×1080 参考坐标；有明确锚点之后再切换坐标变换。

### UI / Object Vision
- `vision/VisionBridge.java` 使用 `OpenCVLoader.initLocal()` 初始化官方原生引擎；失败时禁用 OpenCV 并保留旧版匹配器。
- `countInventoryIcon()` 在确认背包标题后关闭详情并重新截图，最多恢复 2 次，不再关闭后立即抛出异常。
- `executeEquipmentSale()` 的 `Bán` 按钮搜索 ROI 已扩展到 `Rect(1180,420,1620,620)`，覆盖实机截图中的按钮位置。
- 每格装备处理的 `finally` 都尝试安全清理物品详情，避免识别异常时把弹窗留在屏幕上。
- `vision/VisionMatcher.java` 使用 `Imgproc.matchTemplate()` 在传入的有效 ROI 中做原生模板搜索，支持 100%、94%、106% 三种候选缩放，并保留透明区域及药瓶颜色 mask。
- **已连接生产执行路径**：`CoraFlowWorkflowRuntime.findTemplate()` 首先使用 OpenCV 找候选位置；只在候选对应的像素满足旧版节点原有 RGB 严格阈值时才接受。失败再回退旧版匹配器。任何 OpenCV 内部异常只会禁用本次工作流的 OpenCV 分支，不会盲点屏幕。
- 日志包含 OpenCV 初始化状态、识别成功次数、旧引擎回退次数。

### Vision Fusion / State Machine
- `vision/VisionTemporalConsensus.java` 明确区分 `CONFIRMED`、`ABSENT`、`UNKNOWN`；不一致的结果不能作为交易凭据。
- 三处背包详情代码已合并为统一 `captureCleanInventory()`：检查背包标题和详情模板，最多有限次数点击已知详情关闭位置，恢复后等待并做**两张独立截图的一致性确认**。
- 恢复过程中不使用可能意外关闭商店的全局 Android Back；确认不了背包界面仍会安全停止。
- 背包 35 格独立扫描、药瓶格保护、白蓝装备判断、出售数量和药瓶补货策略均保留原有交易保护。
- 此前 `CoraFlowNumberConsensus` 的购买数字两帧校验继续保留。

## 明确未完成（不能说已经达到“100% OCR 替代”）

- 通用 OCR 仍使用 CoraFlow Tesseract WASM，文字和数字识别尚不能完全取消。
- 专用数字分类模型、地图标志物检测、多设备 UI 锚点自动标定、跨会话回放记录、完整 Lua Vision API 仍需真实截图素材和实机测试。
- OpenCV 对现场截图和不同设备的表现尚无实机验收。CI 构建通过不等于脚本连续运行 2 小时通过。
- 用户 2.5.49 日志中的物品详情遮挡问题，2.6.1 采用状态机重构，但需要模拟器实测关闭位置是否符合当前游戏 UI。

## 开发验收与测试流程

1. GitHub Actions Test：`testDebugUnitTest` 包括 `VisionViewportTest`、`VisionTemporalConsensusTest`、此前购买数字一致性和恢复策略回归。
2. GitHub Release Build：`assembleRelease`、`apksigner verify --verbose --print-certs`。
3. APK Artifact：选择最新成功的 `codex/rylux-261-vision-engine` Run，下载测试 ZIP 并解压 APK。
4. **覆盖安装，不能先卸载或清数据**：原版授权、已验证汉化资源缓存和设置需保留。旧 APK 单独备份以便回滚；降级 Android versionCode 可能需要预先备份数据后另行操作。
5. 检查日志应出现 `RYLUX 2.6.1 Vision Engine: OpenCV native ready`。若显示 `OpenCV fallback`，属于原生库加载失败，先检查 ABI 和 logcat，不应误报成“OpenCV 已生效”。
6. 测试启动、进入大夫商店、背包 35 格、遮挡恢复、药瓶保护、红蓝药数量确认和出售结果核验。
7. 制造窗口切换、商店动画迟到、背包详情遗留、截图不一致等故障；要求能恢复的自动恢复，不能确认的禁止交易，不得把 UNKNOWN 当作 EMPTY。
8. 30 分钟、2 小时和延长持续挂机，记录中断前 50 行日志、当前脚本版本、视口尺寸、帧率/耗时、CV 命中与回退计数；对照上一版测量实际改善，再决定是否进入正式发布。

## 新窗口续作指令

> 请先阅读 PakRedirect 的 docs/RYLUX_2.6.1_VISION_ENGINE.md。2.6.1 的独立测试分支是 codex/rylux-261-vision-engine。只修脚本视觉和状态恢复，保留账号授权、汉化、PAK、GitHub 签名。检查 GitHub Actions 构建及签名结果，并根据真实模拟器日志优先修复 OpenCV ROI 多尺度匹配、背包 35 格 UNKNOWN 与物品详情状态恢复、数字 OCR 一致性。当前 OpenCV 已通过 VisionMatcher 接入 CoraFlowWorkflowRuntime.findTemplate，但尚未完成专用数字识别和全面实机稳定性验收；不得宣称替代 OCR 达 100%、不得直接发布 main。


## 2.6.1 首轮实机故障修正（versionCode 182）

- 实机截图显示背包“火羽”详情弹窗仍打开，底部错误消息被 UI 截断，不能单凭截图确定根异常。
- 源码审计发现出售按钮 ROI 左边界 1450 高于截图按钮实际约 x=1305 的参考坐标，导致模板区域裁掉按钮左侧；已扩大 ROI。
- 药瓶计数遇到详情时，旧代码点击关闭后仍无条件抛出异常；已改为关闭、重新截图并有界重试。
- 单格识别/出售流程异常退出时新增详情清理，保留原异常日志。
- 以上修复尚待模拟器验证；不得将源码推断当作已确认的实机根因。
