# RYLUX 2.5.47 Vision Stability RC1 — 测试指南

## 基线与安全边界

- 基线 APK 源码：`428f2b8f363c9f8d37d4d6cdfe3a1495ec06dfb8`，对应 GitHub Actions 成功构建的 `RYLUX-v2.5.46-workflow-version-fix`（versionCode 170）。
- 本次测试分支：`codex/rylux-2546-vision-stability`。
- 候选 APK：`2.5.47-vision-stability-rc1`（versionCode 171），仅作为 GitHub Actions 测试 artifact；不自动发布正式 Release、不修改 `main`。
- 签名：继续使用 `build-apk.yml` 已配置的 `RYLUX_KEYSTORE_B64`、`RYLUX_KEYSTORE_PASSWORD`、`RYLUX_KEY_ALIAS`、`RYLUX_KEY_PASSWORD` Actions Secrets。不生成或提交签名私钥。
- 线上 `workflow/` 仍是独立维护的加密工作流；本 APK 修改不会自动修复或替换线上 JSON。

## 改动（第一阶段，不是完整的 Vision Engine 2.0）

1. `CoraFlowWorkflowRuntime` 对同一 Bitmap 的 35 格逐格模板匹配，复用屏幕 RGBA 像素读取结果，并缓存缩放模板像素；避免每次模板检测重复分配整屏 int[]。在工作流结束时清空缓存。
2. 实现原先在 JSON 中配置却未真正执行的 `ocr_expected_consensus`：购买数量节点必须在**两张独立截图**中精确读出同一预期整数（例如 1 或 5），且两次达到最低置信度，才能允许下一步。每轮最多有界重试，未通过则沿已有恢复边处理，不能被当成数量 0，也不自动确认交易。
3. 识别到背包详情遮挡药品数量时，只有同时确认背包标题才尝试关闭详情（沿用运行器既有坐标），然后由原有 OCR 有界重试重新截图；未能校准或关闭则保留失败保护。
4. 新增 `CoraFlowNumberConsensusTest` 单元测试，覆盖匹配、误读、低置信度、缺失与部分数字。

**不更改：**用户设置的买入数量、出售白蓝装备/保护药品的规则、已加载加密工作流、密钥、授权、线上发布内容。未确认购买数量不允许点击确认；未确认药瓶和背包状态不允许盲目卖出。

## 为什么这不是“永久不停止”的保证

- 游戏 Canvas 不同分辨率布局仍需要锚点与素材校准；现阶段复用现有按比例匹配机制。
- OCR 引擎仍为随 APK 提供的 Tesseract WASM；本次只是降低图像识别内存压力、补齐购买数量的真实双帧安全校验和遮挡恢复。
- 真正的运行稳定性需要实机连续运行验证；GitHub 构建及单元测试通过不等于实机验证通过。
- 如果上线工作流仍存在指向安全停止的逻辑边，不能简单删除该边或把 UNKNOWN 当作空背包格。

## GitHub 构建

`.github/workflows/build-apk.yml` 使用测试分支 push 触发，自动：
1. GitHub Actions Secrets 还原现有签名密钥。
2. `gradle testDebugUnitTest --stacktrace`。
3. `gradle assembleRelease`，以指定版本参数覆盖 `build.gradle` 默认版本。
4. `apksigner verify --verbose --print-certs`。
5. 上传非公开发布的 **Actions Artifact**，不创建正式 Release 或同步 Gitee。

在 Actions 的 `Build RYLUX APK (release optional)` 中查找分支 `codex/rylux-2546-vision-stability` 的成功 Run，下载 `RYLUX-v2.5.47-vision-stability-rc1-test-<commit>`，解压得到 APK。只有 **Run conclusion = success** 才使用该 APK。

## 用户实机验收（先在测试设备）

1. 备份当前 2.5.46 APK 的安装信息及设置；确认测试版包名、签名证书兼容及 `versionCode=171`，避免丢失业务数据。
2. 先完成 10 次进入商店、打开数量框和取消测试。确认“数量不一致/没读到数字”时没有自动点击买入。
3. 反复打开/关闭背包详情，检查遮挡恢复及药瓶保护日志。
4. 进行至少 30 分钟连续挂机，再进行 2 小时或更长时间测试。
5. 记录每次停止前 30–50 行日志，尤其是 `已加载脚本版本`、`OCR`、`背包详情`、`数量双帧`、`第 N/35 格` 和异常堆栈。
6. 重点观察平均识别耗时、内存与 GC、购买量准确性、重复交易次数、可恢复失败率和意外退出。

**回滚**：保留 2.5.46 原签名安装包及设置备份；版本号降级可能被 Android 拒绝直接覆盖安装，不能在未备份数据时随意卸载测试 APK。线上加密工作流保持不变。

## 第二阶段待办

- 针对背包空格、药瓶和确认按钮建立锚点校准与多尺度模板库。
- 为商品详情、商店、地图和背包建立显式状态机与 UNKNOWN 传播。
- 在真实设备截图回放上测量准确率、扫描性能和多分辨率表现。
- 只有在现有 LuaJ/Bitmap 接口不足时，才考虑添加 OpenCV 或 JNI。
