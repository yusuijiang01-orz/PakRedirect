from __future__ import annotations

import base64
import json
import os
import re
import sys
import threading
import time
import webbrowser
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "game-agent" / "src"))

from game_agent.adb import AdbDevice, AdbError  # noqa: E402
from game_agent.vision import GameObserver  # noqa: E402

GAME_PACKAGE = "com.tepaylink.tamgioiphantranhmobile"
HOST = "127.0.0.1"
PORT = int(os.environ.get("RYLUX_TEST_PANEL_PORT", "8766"))
ADB = os.environ.get("RYLUX_ADB_EXECUTABLE", r"E:\leidian\LDPlayer14\adb.exe")
SERIAL = os.environ.get("RYLUX_ADB_DEVICE", "emulator-5554")
EVIDENCE = ROOT / "work" / "html-game-test-panel" / datetime.now().strftime("%Y%m%d-%H%M%S")
MATERIALS = ROOT / "app" / "src" / "main" / "assets" / "script_modules" / "脚本" / "素材库"
TEMPLATES = [
    "汉化条款同意按钮.png", "汉化服务器确认按钮.png", "汉化登录按钮.png",
    "汉化角色确认按钮.png", "汉化登录返回按钮.png",
]
NAV_TARGETS = {
    "背包": (0.58, 0.80, 0.80),
    "角色": (0.72, 0.90, 0.80),
    "技能": (0.82, 0.96, 0.80),
}
# Bottom HUD positions were calibrated from and exercised against the live
# 1600x900 BlackBox game view.
FAST_NAV_TARGETS = {
    "背包": (1124, 875),
    "角色": (1272, 868),
    "技能": (1391, 868),
}
# Only this localized map row and these 1600x900 controls have route calibration.
# The map names are in the narrow left column; the map artwork starts near x=500.
ROUTE_MAP_ROWS = {"崇城野外": (80, 412)}
ROUTE_MAP_TOGGLE = (1480, 120)
ROUTE_MAP_TAB = (295, 54)
ROUTE_MAP_CLOSE = (1548, 54)
ROUTE_X_FIELD = (790, 820)
ROUTE_Y_FIELD = (950, 820)
ROUTE_CONFIRM = (1065, 820)
LOCK = threading.RLock()
DEVICE = AdbDevice(SERIAL, ADB)
OBSERVER = GameObserver()


class PanelActionError(RuntimeError):
    def __init__(self, message, touch_sent=False):
        super().__init__(message)
        self.touch_sent = touch_sent


def _require_game_window() -> str:
    DEVICE.require_game_foreground(GAME_PACKAGE)
    windows = DEVICE._run("shell", "dumpsys", "window").decode("utf-8", errors="replace")
    guest = re.search(r"mCurrentFocus=Window\{[^\r\n]*\scom\.tepaylink\.tamgioiphantranhmobile/", windows)
    proxy = re.search(r"mFocusedApp=ActivityRecord\{[^\r\n]*\scom\.example\.pakredirect/top\.niunaijun\.blackbox\.proxy\.ProxyActivity\$P\d+\s", windows)
    if not guest or not proxy:
        raise AdbError("BlackBox 沙箱游戏没有同时处于前台；本次未发送触控。")
    return "BlackBox 沙箱中的汉化版封神榜处于前台"


def _require_game_window_fast() -> str:
    """Validate the game and display in one ADB shell round trip."""
    command = (
        "dumpsys activity activities; printf '\\n__RYLUX_PS__\\n'; ps -A; "
        "printf '\\n__RYLUX_WINDOW__\\n'; dumpsys window; "
        "printf '\\n__RYLUX_DISPLAY__\\n'; dumpsys display"
    )
    output = DEVICE._run("shell", "sh", "-c", command, timeout=20).decode(
        "utf-8", errors="replace"
    )
    guest_component = re.search(
        r"topResumedActivity=.*?\bu\d+\s+com\.example\.pakredirect/"
        r"top\.niunaijun\.blackbox\.proxy\.ProxyActivity\$P\d+",
        output,
    )
    guest_process = re.search(
        rf"(?m)^\S+\s+\d+\s+.*\s+{re.escape(GAME_PACKAGE)}(?::\S+)?\s*$",
        output,
    )
    focused_window = re.search(
        r"mCurrentFocus=Window\{[^\r\n]*\scom\.tepaylink\.tamgioiphantranhmobile/",
        output,
    )
    proxy_focus = re.search(
        r"mFocusedApp=ActivityRecord\{[^\r\n]*\scom\.example\.pakredirect/"
        r"top\.niunaijun\.blackbox\.proxy\.ProxyActivity\$P\d+\s",
        output,
    )
    if not all((guest_component, guest_process, focused_window, proxy_focus)):
        raise AdbError("BlackBox 沙箱游戏没有同时处于前台；本次未发送触控。")
    if not re.search(r"logicalFrame=Rect\(0,\s*0\s*-\s*1600,\s*900\)", output):
        raise AdbError("背包快速点按只校准过 1600x900 横屏；当前输入显示尺寸不匹配，未发送触控。")
    return "BlackBox 沙箱中的汉化版封神榜处于前台（快速校验）"


def _capture():
    _require_game_window()
    # The foreground was just checked above. Avoid repeating the same two ADB
    # process checks inside AdbDevice.capture().
    frame = DEVICE.capture()
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    name = datetime.now().strftime("frame-%H%M%S-%f.png")
    path = EVIDENCE / name
    path.write_bytes(frame.png)
    return frame, path


def _inspect_game_frame(frame):
    """Return (HUD visible, close point) using only small pixel regions."""
    import cv2
    import numpy as np

    if (frame.width, frame.height) != (1600, 900):
        return False, None
    image = cv2.imdecode(np.frombuffer(frame.png, np.uint8), cv2.IMREAD_COLOR)
    if image is None:
        return False, None
    # The three localized nav labels are visible both on the world screen and
    # behind feature panels, but absent from login/role selection screens.
    nav_visible = True
    for left, right in ((1080, 1180), (1230, 1330), (1350, 1450)):
        region = image[850:900, left:right]
        values = region.astype(np.int16)
        brightness = values.mean(axis=2)
        chroma = values.max(axis=2) - values.min(axis=2)
        nav_visible &= int(((brightness > 215) & (chroma < 65)).sum()) >= 12

    # Only inspect the two calibrated close-button zones. A full-frame HSV
    # conversion and connected-component pass is unnecessary for this action.
    matches = []
    for left, right in ((120, 220), (1440, 1590)):
        crop = image[90:230, left:right]
        hsv = cv2.cvtColor(crop, cv2.COLOR_BGR2HSV)
        mask = cv2.inRange(hsv, np.array([78, 45, 90]), np.array([110, 255, 255]))
        count, _, stats, centers = cv2.connectedComponentsWithStats(mask)
        for index in range(1, count):
            x, y, width, height, area = map(int, stats[index])
            local_x, local_y = centers[index]
            center_x, center_y = left + local_x, 90 + local_y
            if (area >= 800 and 35 <= width <= 110 and 35 <= height <= 110
                    and 0.65 <= width / height <= 1.45):
                matches.append((float(center_x), float(center_y)))
    if not matches:
        return nav_visible, None
    # If a split panel exposes more than one X, the rightmost one closes the
    # complete panel stack (verified on the localized inventory screen).
    center_x, center_y = max(matches)
    return nav_visible, (round(center_x), round(center_y))


def _serialize_frame(frame, path):
    return {
        "width": frame.width,
        "height": frame.height,
        "image": "data:image/png;base64," + base64.b64encode(frame.png).decode("ascii"),
        "evidence": str(path),
    }


def _ocr(frame):
    spans = OBSERVER.read(frame)
    return [
        {"text": span.text, "confidence": round(span.confidence, 4),
         "x": span.center[0], "y": span.center[1], "box": list(span.box)}
        for span in spans
    ]


def _match_templates(frame):
    import cv2
    import numpy as np

    screen = cv2.imdecode(np.frombuffer(frame.png, np.uint8), cv2.IMREAD_GRAYSCALE)
    matches = []
    for filename in TEMPLATES:
        path = MATERIALS / filename
        if not path.is_file():
            matches.append({"name": filename, "available": False})
            continue
        template = cv2.imdecode(np.frombuffer(path.read_bytes(), np.uint8), cv2.IMREAD_GRAYSCALE)
        best = (0.0, 0, 0, 1.0)
        for scale in (0.70, 0.80, 0.90, 1.00, 1.10, 1.20, 1.30):
            width, height = int(template.shape[1] * scale), int(template.shape[0] * scale)
            if width < 8 or height < 8 or width > screen.shape[1] or height > screen.shape[0]:
                continue
            scaled = cv2.resize(template, (width, height), interpolation=cv2.INTER_AREA if scale < 1 else cv2.INTER_CUBIC)
            result = cv2.matchTemplate(screen, scaled, cv2.TM_CCOEFF_NORMED)
            _, score, _, point = cv2.minMaxLoc(result)
            if score > best[0]:
                best = (float(score), point[0], point[1], scale)
        matches.append({"name": filename, "available": True, "score": round(best[0], 4),
                        "x": best[1], "y": best[2], "scale": best[3],
                        "matched": best[0] >= 0.88})
    return matches


def _route_map_is_open(frame) -> bool:
    """Recognize the calibrated map overlay by its cyan close control."""
    import cv2
    import numpy as np

    if (frame.width, frame.height) != (1600, 900):
        return False
    image = cv2.imdecode(np.frombuffer(frame.png, np.uint8), cv2.IMREAD_COLOR)
    if image is None:
        return False
    # The map overlay's close icon is calibrated at (1548,54); require a
    # matching cyan circular component there, rather than trusting the map
    # toggle location or tapping blindly on an unrelated screen.
    left, top = ROUTE_MAP_CLOSE[0] - 30, ROUTE_MAP_CLOSE[1] - 30
    crop = image[top:top + 60, left:left + 60]
    hsv = cv2.cvtColor(crop, cv2.COLOR_BGR2HSV)
    mask = cv2.inRange(hsv, np.array([78, 45, 90]), np.array([110, 255, 255]))
    count, _, stats, centers = cv2.connectedComponentsWithStats(mask)
    for index in range(1, count):
        x, y, width, height, area = map(int, stats[index])
        cx, cy = centers[index]
        if (area >= 500 and 30 <= width <= 65 and 30 <= height <= 65
                and abs(left + cx - ROUTE_MAP_CLOSE[0]) <= 20
                and abs(top + cy - ROUTE_MAP_CLOSE[1]) <= 20
                and 0.65 <= width / height <= 1.45):
            return True
    return False


def _route_map_row_selected(frame, map_name: str) -> bool:
    """Confirm the calibrated map row's localized green selected state."""
    import cv2
    import numpy as np

    if map_name != "崇城野外" or (frame.width, frame.height) != (1600, 900):
        return False
    image = cv2.imdecode(np.frombuffer(frame.png, np.uint8), cv2.IMREAD_COLOR)
    if image is None:
        return False
    # In the map list, this row changes from orange to bright green when
    # selected. Check only its label crop; no OCR model or full-frame scan.
    crop = image[385:440, 10:140]
    blue, green, red = cv2.split(crop)
    selected_pixels = ((green > 120) & (green > red * 1.25) & (green > blue * 1.15))
    return int(np.count_nonzero(selected_pixels)) >= 50


def _route_capture():
    _require_game_window_fast()
    frame = DEVICE.capture()
    if (frame.width, frame.height) != (1600, 900):
        raise AdbError("挂机路线仅校准过 1600×900 横屏；当前未发送路线确认。")
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    path = EVIDENCE / ("route-" + datetime.now().strftime("%H%M%S-%f") + ".png")
    path.write_bytes(frame.png)
    return frame, path


def _route_tap(point, step_name):
    _require_game_window_fast()
    DEVICE.tap(*point)
    return step_name


def _route_enter_number(point, value, field_name):
    """Replace a numeric input using only validated decimal characters."""
    _require_game_window_fast()
    DEVICE.tap(*point)
    # The game input is numeric. Delete a bounded 12-character old value,
    # type only validated digits, then submit the keyboard action.
    script = "input keyevent 123 || exit 1; "
    script += " ".join(["input keyevent 67 || exit 1;"] * 12)
    script += f" input text {value} || exit 1; input keyevent 66 || exit 1"
    DEVICE._run("shell", "sh", "-c", script, timeout=10)
    return f"输入{field_name}={value}"


def _run_route(payload):
    map_name = str(payload.get("mapName", ""))
    if map_name not in ROUTE_MAP_ROWS:
        raise ValueError("网页路线执行目前只支持已校准地图“崇城野外”；未发送触控。")
    try:
        x, y = payload.get("x"), payload.get("y")
        if isinstance(x, bool) or isinstance(y, bool):
            raise ValueError
        x, y = int(x), int(y)
    except (TypeError, ValueError):
        raise ValueError("挂机坐标必须为 0–99999 的整数；未发送触控。") from None
    if not (0 <= x <= 99999 and 0 <= y <= 99999):
        raise ValueError("挂机坐标超出 0–99999 的有效范围；未发送触控。")

    steps = []
    touched = False
    try:
        _require_game_window_fast()
        frame = DEVICE.capture()
        if (frame.width, frame.height) != (1600, 900):
            raise AdbError("挂机路线仅校准过 1600×900 横屏；未触控。")
        if not _route_map_is_open(frame):
            hud_visible, close_target = _inspect_game_frame(frame)
            if not hud_visible or close_target:
                raise AdbError("当前既不是已识别的挂机地图，也不是无面板游戏主界面；未触控。")
            steps.append(_route_tap(ROUTE_MAP_TOGGLE, "打开大地图"))
            touched = True
            time.sleep(0.6)
            frame, _ = _route_capture()
            if not _route_map_is_open(frame):
                raise AdbError("已点地图入口，但未检测到校准的大地图关闭按钮；为避免误点已停止。")

        # Reopening the map defaults to the NPC tab. Select the map tab
        # explicitly before touching the only calibrated map-list row.
        steps.append(_route_tap(ROUTE_MAP_TAB, "切换到地图页签"))
        touched = True
        time.sleep(0.25)
        frame, _ = _route_capture()
        if not _route_map_is_open(frame):
            raise AdbError("切换地图页签后大地图面板未保持打开；未选择地图或提交路线。")

        # The only supported row was calibrated from the map list. Do not
        # infer or reuse neighboring rows for any other map.
        steps.append(_route_tap(ROUTE_MAP_ROWS[map_name], f"选择地图“{map_name}”"))
        touched = True
        time.sleep(0.35)
        frame, _ = _route_capture()
        if not _route_map_is_open(frame):
            raise AdbError("选择地图后地图面板已关闭；未继续输入坐标或提交路线。")
        if not _route_map_row_selected(frame, map_name):
            raise AdbError("未确认“崇城野外”已选中；未输入坐标或提交路线。")

        steps.append(_route_enter_number(ROUTE_X_FIELD, x, "X 坐标"))
        steps.append(_route_enter_number(ROUTE_Y_FIELD, y, "Y 坐标"))
        frame, _ = _route_capture()
        if not _route_map_is_open(frame):
            raise AdbError("坐标输入后地图面板不再可见；未点路线确认。")

        # This is the consequential tap that starts in-game travel. It is
        # reached only after target/coordinate validation and map checks.
        steps.append(_route_tap(ROUTE_CONFIRM, "提交前往路线"))
        touched = True
        time.sleep(0.6)
        frame, path = _route_capture()
        if _route_map_is_open(frame):
            result = "已发送路线确认并取得新截图；地图面板仍可见，尚未确认是否开始移动或抵达。"
        else:
            result = "已发送路线确认并取得新截图；游戏画面已变化，是否开始移动或抵达尚未验证。"
        return {
            "command": "run_route",
            "result": result,
            "mapName": map_name,
            "x": x,
            "y": y,
            "steps": steps,
            "target": {"text": "提交前往路线", "x": ROUTE_CONFIRM[0], "y": ROUTE_CONFIRM[1]},
            **_serialize_frame(frame, path),
        }
    except Exception as error:
        if touched:
            raise PanelActionError(str(error), touch_sent=True) from error
        raise


def _run_command(command, payload):
    with LOCK:
        if command == "run_route":
            return _run_route(payload)

        if command == "status":
            note = _require_game_window()
            return {"command": "检查前台", "result": note, "serial": SERIAL}

        if command == "tap_text":
            label = str(payload.get("label", ""))
            if label not in NAV_TARGETS:
                raise ValueError("只允许测试背包、角色或技能这三个可见导航按钮。")
            _require_game_window_fast()
            frame = DEVICE.capture()
            hud_visible, close_target = _inspect_game_frame(frame)
            if not hud_visible:
                raise AdbError("当前画面未识别到游戏底部导航栏；未触控。")
            steps = []
            if close_target:
                DEVICE.tap(*close_target)
                steps.append(f"关闭当前面板 {close_target[0]},{close_target[1]}")
                # The game animates panel dismissal; sending the next tap too
                # early lands on the closing panel and is silently discarded.
                time.sleep(0.45)
            x, y = FAST_NAV_TARGETS[label]
            DEVICE.tap(x, y)
            steps.append(f"打开“{label}” {x},{y}")
            return {
                "command": f'switch_panel("{label}")',
                "result": "已发送顺序点按：" + " → ".join(steps) + "；关闭/主界面判断为快速像素检测，无 OCR。",
                "target": {"text": label, "x": x, "y": y},
                "steps": steps,
            }

        if command == "close_bag":
            _require_game_window_fast()
            frame = DEVICE.capture()
            hud_visible, target = _inspect_game_frame(frame)
            if not hud_visible:
                raise AdbError("当前画面未识别到游戏底部导航栏；未触控。")
            if not target:
                raise ValueError("当前未发现可识别的游戏面板关闭按钮；未触控。")
            x, y = target
            DEVICE.tap(x, y)
            return {"command": f"close_panel({x},{y})",
                    "result": f"已快速发送关闭当前面板点按 ({x},{y})；未运行 OCR/等待回读。",
                    "target": {"text": "关闭当前面板", "x": x, "y": y}}

        frame, path = _capture()
        base = _serialize_frame(frame, path)
        if command == "screenshot":
            return {"command": "获取游戏截图", "result": f"已截取 {frame.width}×{frame.height}", **base}

        rows = _ocr(frame)
        if command == "ocr":
            return {"command": "运行 OCR", "result": f"识别到 {len(rows)} 行文字", "rows": rows, **base}

        if command == "templates":
            return {"command": "扫描汉化素材库", "result": "已扫描当前截图中的登录模板", "matches": _match_templates(frame), **base}

        raise ValueError("未知测试指令。")


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        return

    def _send(self, body, code=200, content_type="application/json; charset=utf-8"):
        data = body if isinstance(body, bytes) else json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("X-Frame-Options", "DENY")
        self.end_headers()
        try:
            self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError):
            # A browser may abandon a long first OCR request; don't emit a traceback.
            pass

    def do_GET(self):
        if self.headers.get("Host") != f"{HOST}:{PORT}":
            return self._send({"error": "invalid host"}, 403)
        if self.path == "/":
            return self._send((ROOT / "tools" / "adb-game-test-panel.html").read_bytes(), content_type="text/html; charset=utf-8")
        if self.path == "/api/status":
            try:
                return self._send({"connected": True, "serial": SERIAL, "message": _require_game_window()})
            except Exception as error:
                return self._send({"connected": False, "serial": SERIAL, "message": str(error)}, 200)
        return self._send({"error": "not found"}, 404)

    def do_POST(self):
        if self.headers.get("Host") != f"{HOST}:{PORT}" or self.headers.get("Origin") != f"http://{HOST}:{PORT}":
            return self._send({"error": "只接受本机测试面板发出的请求。"}, 403)
        if self.path != "/api/command":
            return self._send({"error": "not found"}, 404)
        try:
            started = time.perf_counter()
            size = int(self.headers.get("Content-Length", "0"))
            if size < 1 or size > 4096:
                raise ValueError("请求长度无效。")
            payload = json.loads(self.rfile.read(size))
            if not isinstance(payload, dict):
                raise ValueError("请求格式无效。")
            result = _run_command(str(payload.get("command", "")), payload)
            result["serverDurationMs"] = round((time.perf_counter() - started) * 1000, 1)
            EVIDENCE.mkdir(parents=True, exist_ok=True)
            with (EVIDENCE / "commands.jsonl").open("a", encoding="utf-8") as log_file:
                log_file.write(json.dumps({"time": datetime.now().isoformat(timespec="seconds"),
                                           "command": result.get("command"),
                                           "result": result.get("result"),
                                           "touchSent": result.get("target") is not None},
                                          ensure_ascii=False) + "\n")
            return self._send(result)
        except Exception as error:
            touch_sent = bool(getattr(error, "touch_sent", False))
            if touch_sent:
                with (EVIDENCE / "commands.jsonl").open("a", encoding="utf-8") as log_file:
                    log_file.write(json.dumps({"time": datetime.now().isoformat(timespec="seconds"),
                                               "command": "action", "result": str(error),
                                               "touchSent": True}, ensure_ascii=False) + "\n")
            return self._send({"error": str(error), "touchSent": touch_sent}, 400)


def main():
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    server.daemon_threads = True
    url = f"http://{HOST}:{PORT}/"
    print(f"RYLUX 本机游戏测试面板：{url}")
    print(f"设备：{SERIAL}；证据目录：{EVIDENCE}")
    print("仅绑定本机。导航切换会快速校验前台并先关已打开面板；截图/OCR只用于诊断。停止服务请按 Ctrl+C。")
    if os.environ.get("RYLUX_TEST_PANEL_NO_BROWSER") != "1":
        threading.Thread(target=lambda: (time.sleep(0.6), webbrowser.open(url)), daemon=True).start()
    try:
        server.serve_forever(poll_interval=0.25)
    except KeyboardInterrupt:
        print("\n测试面板已停止。")
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
