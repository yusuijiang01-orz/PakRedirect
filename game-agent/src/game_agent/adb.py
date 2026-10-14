from __future__ import annotations

import re
import subprocess
from dataclasses import dataclass

from PIL import Image
from io import BytesIO


class AdbError(RuntimeError):
    pass


@dataclass(frozen=True)
class Frame:
    png: bytes
    width: int
    height: int
    average_hash: str


class AdbDevice:
    def __init__(self, serial: str, adb_path: str = "adb") -> None:
        self.serial = serial
        self.adb_path = adb_path

    def _run(self, *args: str, timeout: float = 20) -> bytes:
        try:
            result = subprocess.run(
                [self.adb_path, "-s", self.serial, *args],
                check=False,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                timeout=timeout,
            )
        except (OSError, subprocess.TimeoutExpired) as error:
            raise AdbError(f"ADB command failed: {error}") from error
        if result.returncode != 0:
            detail = result.stderr.decode("utf-8", errors="replace").strip()
            raise AdbError(detail or f"ADB exited with {result.returncode}")
        return result.stdout

    def capture(self) -> Frame:
        png = self._run("exec-out", "screencap", "-p", timeout=30)
        try:
            image = Image.open(BytesIO(png)).convert("L")
            width, height = image.size
            tiny = image.resize((8, 8), Image.Resampling.LANCZOS)
            pixels = list(tiny.getdata())
            average = sum(pixels) / len(pixels)
            value = sum((1 << index) for index, pixel in enumerate(pixels) if pixel >= average)
        except Exception as error:
            raise AdbError(f"设备返回了无效截图：{error}") from error
        return Frame(png, width, height, f"{value:016x}")

    def foreground_package(self) -> str:
        output = self._run("shell", "dumpsys", "activity", "activities").decode(
            "utf-8", errors="replace"
        )
        match = re.search(r"topResumedActivity=.*?\bu\d+\s+([A-Za-z0-9_.]+)/", output)
        if not match:
            match = re.search(r"mResumedActivity:.*?\bu\d+\s+([A-Za-z0-9_.]+)/", output)
        if not match:
            raise AdbError("无法确认当前前台应用；为避免误触，已停止操作。")
        return match.group(1)

    def require_game_foreground(self, package_name: str) -> None:
        current = self.foreground_package()
        if current != package_name:
            raise AdbError(
                f"前台应用是 {current}，目标游戏是 {package_name}；未发送任何触控。"
            )

    def tap(self, x: int, y: int) -> None:
        if x < 0 or y < 0:
            raise AdbError("触控坐标不能小于 0。")
        self._run("shell", "input", "tap", str(x), str(y))


def connected_devices(adb_path: str = "adb") -> list[tuple[str, str]]:
    try:
        result = subprocess.run(
            [adb_path, "devices", "-l"], check=True, capture_output=True, text=True, timeout=10
        )
    except (OSError, subprocess.SubprocessError) as error:
        raise AdbError(f"无法读取 ADB 设备：{error}") from error
    devices = []
    for line in result.stdout.splitlines()[1:]:
        columns = line.split()
        if len(columns) >= 2 and columns[1] == "device":
            devices.append((columns[0], " ".join(columns[2:])))
    return devices
