from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

from .adb import AdbDevice, AdbError, connected_devices
from .memory import MemoryStore
from .policy import DemonstrationPolicy


def _device(args: argparse.Namespace) -> AdbDevice:
    return AdbDevice(args.serial, args.adb)


def _game(args: argparse.Namespace, device: AdbDevice) -> None:
    device.require_game_foreground(args.game)


def command_devices(args: argparse.Namespace) -> int:
    devices = connected_devices(args.adb)
    if not devices:
        print("没有可用的 ADB 设备。请连接已授权的雷电模拟器。")
        return 1
    for serial, details in devices:
        print(f"{serial}\t{details}")
    return 0


def command_observe(args: argparse.Namespace) -> int:
    device = _device(args)
    memory = MemoryStore()
    for index in range(args.count):
        _game(args, device)
        frame = device.capture()
        image_path = memory.save_frame(args.game, frame)
        frames, demos = memory.counts(args.game)
        print(
            f"观察 {index + 1}/{args.count}: {frame.width}x{frame.height} "
            f"hash={frame.average_hash} · 本地帧 {frames} · 示范 {demos}"
        )
        if args.output and index == 0:
            output = Path(args.output).expanduser().resolve()
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_bytes(frame.png)
            print(f"截图已保存：{output}")
        elif args.count == 1:
            print(f"截图已记录：{image_path}")
        if index + 1 < args.count:
            time.sleep(args.interval)
    return 0


def command_teach(args: argparse.Namespace) -> int:
    device = _device(args)
    _game(args, device)
    before = device.capture()
    if args.x >= before.width or args.y >= before.height:
        raise AdbError(f"坐标超出当前画面 {before.width}x{before.height}，未发送触控。")
    print(f"即将按你指定的坐标轻触 ({args.x}, {args.y})；按 Ctrl+C 可取消。")
    if not args.confirm:
        print("为保护游戏状态，未触控。确认后加上 --confirm 再运行。")
        return 2
    device.tap(args.x, args.y)
    time.sleep(args.wait)
    _game(args, device)
    after = device.capture()
    memory = MemoryStore()
    memory.save_frame(args.game, before)
    memory.save_frame(args.game, after)
    memory.add_demonstration(args.game, before, args.x, args.y, after)
    _, demos = memory.counts(args.game)
    print(f"已记录一次示范。该游戏当前有 {demos} 次示范；至少 3 次同状态示范后才会建议回放。")
    return 0


def command_suggest(args: argparse.Namespace) -> int:
    device = _device(args)
    _game(args, device)
    frame = device.capture()
    policy = DemonstrationPolicy(MemoryStore(), max_distance=args.max_distance)
    suggestion = policy.suggest(args.game, frame)
    if suggestion is None:
        print("没有足够可靠的相似示范；智能体保持等待，没有触控。")
        return 0
    print(
        f"建议轻触 ({suggestion.x}, {suggestion.y}) · 置信度 {suggestion.confidence:.2f} "
        f"· 相似示范 {suggestion.samples} 次 · 画面差异 {suggestion.distance}/64"
    )
    if not args.approve:
        print("当前只生成建议。若你确认要执行这一次动作，请添加 --approve。")
        return 0
    if suggestion.confidence < args.min_confidence:
        print(f"置信度低于门槛 {args.min_confidence:.2f}，未触控。")
        return 2
    if suggestion.x >= frame.width or suggestion.y >= frame.height:
        print("记忆坐标超出当前画面，未触控。")
        return 2
    _game(args, device)
    device.tap(suggestion.x, suggestion.y)
    print("已执行这一次经你批准的触控。")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="RYLUX 本机游戏智能体原型（ADB 测试桥）")
    parser.add_argument("--adb", default="adb", help="ADB 可执行文件路径")
    subparsers = parser.add_subparsers(dest="command", required=True)

    devices = subparsers.add_parser("devices", help="列出已连接设备")
    devices.set_defaults(handler=command_devices)

    def add_game_args(command: argparse.ArgumentParser) -> None:
        command.add_argument("--serial", required=True, help="ADB 设备序列号")
        command.add_argument("--game", required=True, help="目标游戏包名，用于前台安全校验")

    observe = subparsers.add_parser("observe", help="只读采集游戏画面")
    add_game_args(observe)
    observe.add_argument("--count", type=int, default=1)
    observe.add_argument("--interval", type=float, default=2.0)
    observe.add_argument("--output", help="可选的截图输出路径")
    observe.set_defaults(handler=command_observe)

    teach = subparsers.add_parser("teach", help="记录你明确指定的一次触控示范")
    add_game_args(teach)
    teach.add_argument("--x", type=int, required=True)
    teach.add_argument("--y", type=int, required=True)
    teach.add_argument("--wait", type=float, default=1.0)
    teach.add_argument("--confirm", action="store_true", help="确认实际发送本次触控")
    teach.set_defaults(handler=command_teach)

    suggest = subparsers.add_parser("suggest", help="根据本地示范生成建议；默认不操作游戏")
    add_game_args(suggest)
    suggest.add_argument("--max-distance", type=int, default=8)
    suggest.add_argument("--min-confidence", type=float, default=0.80)
    suggest.add_argument("--approve", action="store_true", help="确认执行本次建议动作")
    suggest.set_defaults(handler=command_suggest)
    return parser


def main() -> None:
    parser = build_parser()
    args = parser.parse_args()
    try:
        result = args.handler(args)
    except AdbError as error:
        print(f"操作已停止：{error}", file=sys.stderr)
        result = 2
    raise SystemExit(result)
