# RYLUX Game Agent 0.1

这是 RYLUX BlackBox 游戏沙箱的本机智能体试验工具。当前使用连接到雷电模拟器的 ADB 作为观察和触控适配器，先验证“看画面 → 记忆示范 → 提出动作 → 人工批准执行”的闭环。它不需要 Root，也不会联网上传截图或轨迹。

首版采用保守的示范记忆策略：每个游戏分别保存画面指纹和用户指定的触控；只有分辨率一致、画面相似且同一状态至少有 3 次示范时才提出动作。未知画面保持等待。智能体不会自行处理登录、支付、兑换、删除或其他账号敏感操作。

## 安装

需要 Python 3.10+、Pillow 和可用的 ADB。Windows PowerShell：

```powershell
cd game-agent
py -m venv .venv
.venv\Scripts\Activate.ps1
python -m pip install -e .
```

若 ADB 不在 PATH 中，可使用全路径 `--adb "E:\leidian\LDPlayer14\adb.exe"`。先确认模拟器已连接：

```powershell
rylux-agent devices
```

## 试用

在 BlackBox 沙箱中启动已授权的测试游戏后，只读采集 5 帧：

```powershell
rylux-agent --adb "E:\leidian\LDPlayer14\adb.exe" observe `
  --serial emulator-5554 --game com.tepaylink.tamgioiphantranhmobile `
  --count 5 --interval 2 --output .\first-frame.png
```

记录你明确指定的一次演示触控时，先确认游戏位于前台，再加 `--confirm` 发送该次点击：

```powershell
rylux-agent --adb "E:\leidian\LDPlayer14\adb.exe" teach `
  --serial emulator-5554 --game com.tepaylink.tamgioiphantranhmobile `
  --x 500 --y 700 --confirm
```

生成建议默认不会触控；只有你再次明确添加 `--approve` 才会执行这一次动作：

```powershell
rylux-agent --adb "E:\leidian\LDPlayer14\adb.exe" suggest `
  --serial emulator-5554 --game com.tepaylink.tamgioiphantranhmobile
```

## 当前边界

这是受监督的控制与记忆原型，不是已经训练好的通用游戏模型。像素平均哈希对动画、镜头移动和画面缩放较敏感；首版适合验证数据采集和安全动作边界，还不能理解任务目标、规划长序列或自主判断胜负。AI 视觉编码器、奖励信号、跨游戏迁移、安卓端 MediaProjection/Accessibility 输入桥，以及手机 GPU 推理均记录在项目路线中。

所有数据保存在 `game-agent/data/`，数据库与截图已加入 Git 忽略规则。只在你有权测试的游戏或模拟环境中使用。
