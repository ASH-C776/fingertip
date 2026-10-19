"""指间（Fingertip）电脑端核心服务。

职责：
- 启动 WebSocket Server，接收手机端 JSON 指令
- 将指令映射为 pyautogui 鼠标/键盘动作
- 管理连接状态与配对（配对在阶段 6 完善）

协议见《开发指南.md》4.4 节。
"""
from __future__ import annotations

import asyncio
import ctypes
import json
import logging
import socket
import threading
from typing import Any, Callable, Optional

import pyautogui
import websockets

log = logging.getLogger("fingertip.server")

# 关键性能优化：pyautogui 默认 PAUSE=0.1s，每次指令后强制停 100ms，
# 会导致触控板移动延迟极大（实测单次 moveRel 100.6ms）。归零后约 1ms。
pyautogui.PAUSE = 0
# 关闭失败保护（每次移动都读取鼠标位置检查），进一步降低单次调用开销。
pyautogui.FAILSAFE = False

# ---- SendInput Unicode 直接注入（文字上屏主通道）----
# 不经过剪贴板：上屏内容 100% 是手机输入的文字，且不破坏用户剪贴板。

_INPUT_KEYBOARD = 1
_KEYEVENTF_UNICODE = 0x0004
_KEYEVENTF_KEYUP = 0x0002


class _KEYBDINPUT(ctypes.Structure):
    _fields_ = [
        ("wVk", ctypes.c_ushort),
        ("wScan", ctypes.c_ushort),
        ("dwFlags", ctypes.c_ulong),
        ("time", ctypes.c_ulong),
        ("dwExtraInfo", ctypes.POINTER(ctypes.c_ulong)),
    ]


class _MOUSEINPUT(ctypes.Structure):
    # 仅用于撑大 union，保证 64 位下 INPUT 结构 size == 40（Windows 硬性要求）
    _fields_ = [
        ("dx", ctypes.c_long),
        ("dy", ctypes.c_long),
        ("mouseData", ctypes.c_ulong),
        ("dwFlags", ctypes.c_ulong),
        ("time", ctypes.c_ulong),
        ("dwExtraInfo", ctypes.POINTER(ctypes.c_ulong)),
    ]


class _INPUTUNION(ctypes.Union):
    _fields_ = [("mi", _MOUSEINPUT), ("ki", _KEYBDINPUT)]


class _INPUT(ctypes.Structure):
    _fields_ = [("type", ctypes.c_ulong), ("_input", _INPUTUNION)]


def send_unicode(text: str) -> None:
    """用 SendInput + KEYEVENTF_UNICODE 向焦点窗口直接注入文本（含中文）。"""
    for ch in text:
        inp = _INPUT()
        inp.type = _INPUT_KEYBOARD
        inp._input.ki.wScan = ord(ch)
        inp._input.ki.dwFlags = _KEYEVENTF_UNICODE
        ret = ctypes.windll.user32.SendInput(1, ctypes.byref(inp), ctypes.sizeof(_INPUT))
        if ret != 1:
            raise OSError(f"SendInput down 失败 ret={ret}")
        inp._input.ki.dwFlags = _KEYEVENTF_UNICODE | _KEYEVENTF_KEYUP
        ret = ctypes.windll.user32.SendInput(1, ctypes.byref(inp), ctypes.sizeof(_INPUT))
        if ret != 1:
            raise OSError(f"SendInput up 失败 ret={ret}")

DEFAULT_PORT = 8765
# 发现服务：mDNS 服务类型 + UDP 广播端口（与 TCP 端口独立，无冲突）
MDNS_SERVICE_TYPE = "_fingertip._tcp.local."
UDP_DISCOVERY_PORT = DEFAULT_PORT
UDP_BROADCAST_INTERVAL = 3.0


def get_lan_ip() -> str:
    """获取本机局域网 IP（通过 UDP 连接技巧，不发真实数据包）。"""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


# ---- 设备发现（阶段 6：mDNS + UDP 广播 + 探测单播回复）----

class DiscoveryService:
    """向局域网广播「指间」服务，供手机端自动发现。

    三通道互补：
    1. mDNS：注册 _fingertip._tcp.local. 服务（zeroconf）
    2. UDP 广播：周期向 255.255.255.255 与子网定向广播地址广播 JSON
       （兜底，兼容无 mDNS 环境）
    3. UDP 探测回复：监听手机发来的 probe 包，立即向来源 IP 单播回复
       （单播穿透性最强，路由器 AP 隔离/防火墙拦广播时仍可用）
    """

    def __init__(self, port: int, device_name: str = "") -> None:
        self.port = port
        self.device_name = device_name or socket.gethostname()
        self._zc = None
        self._stop = threading.Event()
        self._threads: list[threading.Thread] = []

    def start(self) -> None:
        try:
            from zeroconf import ServiceInfo, Zeroconf

            ip = get_lan_ip()
            info = ServiceInfo(
                MDNS_SERVICE_TYPE,
                f"{self.device_name}.{MDNS_SERVICE_TYPE}",
                addresses=[socket.inet_aton(ip)],
                port=self.port,
                properties={"name": self.device_name.encode("utf-8")},
            )
            self._zc = Zeroconf()
            self._zc.register_service(info)
            log.info("mDNS 已注册: %s:%d (%s)", ip, self.port, MDNS_SERVICE_TYPE)
        except Exception as e:
            log.warning("mDNS 注册失败: %s", e)
        t1 = threading.Thread(target=self._udp_broadcast_loop, name="ft-udp-broadcast", daemon=True)
        t2 = threading.Thread(target=self._udp_probe_loop, name="ft-udp-probe", daemon=True)
        t1.start()
        t2.start()
        self._threads = [t1, t2]

    def stop(self) -> None:
        self._stop.set()
        try:
            if self._zc:
                self._zc.close()
        except Exception:
            pass

    def _advertise_payload(self) -> bytes:
        return json.dumps(
            {
                "type": "fingertip",
                "ip": get_lan_ip(),
                "port": self.port,
                "name": self.device_name,
            },
            ensure_ascii=False,
        ).encode("utf-8")

    def _udp_broadcast_loop(self) -> None:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            targets = {"255.255.255.255"}
            try:
                ip = get_lan_ip()
                if "." in ip:
                    targets.add(ip.rsplit(".", 1)[0] + ".255")
            except Exception:
                pass
            while not self._stop.is_set():
                payload = self._advertise_payload()
                for t in targets:
                    try:
                        sock.sendto(payload, (t, UDP_DISCOVERY_PORT))
                    except Exception:
                        pass
                self._stop.wait(UDP_BROADCAST_INTERVAL)
        finally:
            sock.close()

    def _udp_probe_loop(self) -> None:
        """监听手机 probe 包并单播回复（兼容广播被隔离的环境）。"""
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.bind(("", UDP_DISCOVERY_PORT))
            sock.settimeout(1.0)
            while not self._stop.is_set():
                try:
                    data, addr = sock.recvfrom(1024)
                except socket.timeout:
                    continue
                except OSError:
                    break
                try:
                    msg = json.loads(data.decode("utf-8", "replace"))
                except Exception:
                    continue
                if msg.get("type") == "probe":
                    sock.sendto(self._advertise_payload(), addr)
                    log.info("UDP 探测回复 -> %s", addr[0])
        finally:
            sock.close()


class FingertipServer:
    """WebSocket 服务端：接收手机指令并执行鼠标键盘动作。"""

    def __init__(
        self,
        on_status_change: Optional[Callable[[dict], None]] = None,
    ) -> None:
        self.host = "0.0.0.0"
        self.port = DEFAULT_PORT
        self.on_status_change = on_status_change
        self._connected_clients: set[websockets.WebSocketServerProtocol] = set()
        self._server: Optional[websockets.WebSocketServer] = None
        self._thread: Optional[threading.Thread] = None
        self._loop: Optional[asyncio.AbstractEventLoop] = None
        # 鼠标按键映射：手机端按钮名 -> pyautogui 按钮名
        self.button_map = {"left": "left", "right": "right", "middle": "middle"}

    # ---- 生命周期 ----

    @property
    def is_connected(self) -> bool:
        return bool(self._connected_clients)

    @property
    def connected_device(self) -> str:
        if not self._connected_clients:
            return ""
        addr = next(iter(self._connected_clients)).remote_address
        return f"{addr[0]}:{addr[1]}"

    def start(self) -> None:
        """在后台线程启动 asyncio 事件循环与 WebSocket Server。"""
        self._thread = threading.Thread(target=self._run_loop, name="ft-ws-server", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        if self._loop and self._server:
            self._loop.call_soon_threadsafe(self._server.close)

    def _run_loop(self) -> None:
        self._loop = asyncio.new_event_loop()
        asyncio.set_event_loop(self._loop)
        try:
            self._loop.run_until_complete(self._serve())
            self._loop.run_forever()
        finally:
            self._loop.close()

    async def _serve(self) -> None:
        """监听端口，被占用则自动 +1 直到可用。"""
        for port in range(self.port, self.port + 100):
            try:
                self._server = await websockets.serve(
                    self._handler,
                    self.host,
                    port,
                    max_size=2 * 1024 * 1024,
                )
                self.port = port
                log.info("WebSocket 监听 0.0.0.0:%d", port)
                if self.on_status_change:
                    self.on_status_change({"type": "server", "port": port, "ip": get_lan_ip()})
                return
            except OSError:
                log.warning("端口 %d 被占用，尝试 %d", port, port + 1)
        raise RuntimeError("找不到可用端口（8765-8864 全部被占用）")

    # ---- 连接管理 ----

    def _notify_status(self, payload: dict) -> None:
        if self.on_status_change:
            try:
                self.on_status_change(payload)
            except Exception:
                log.exception("状态回调失败")

    async def _handler(self, ws: websockets.WebSocketServerProtocol) -> None:
        self._connected_clients.add(ws)
        device_name = ws.remote_address
        self._notify_status({"type": "connected", "device": str(device_name)})
        log.info("客户端接入: %s", device_name)
        try:
            async for raw in ws:
                try:
                    msg = json.loads(raw)
                except json.JSONDecodeError:
                    log.warning("非法 JSON: %r", raw[:200])
                    continue
                await self._dispatch(ws, msg)
        except websockets.ConnectionClosed:
            log.info("客户端断开: %s", device_name)
        finally:
            self._connected_clients.discard(ws)
            self._notify_status({"type": "disconnected"})

    async def _dispatch(self, ws: websockets.WebSocketServerProtocol, msg: dict) -> None:
        """根据消息 type 分发处理。"""
        mtype = msg.get("type")
        handler = getattr(self, f"_on_{mtype}", None)
        if handler is None:
            log.warning("未知消息类型: %s", mtype)
            return
        try:
            await handler(ws, msg)
        except Exception:
            log.exception("处理 %s 消息失败", mtype)

    # ---- 消息处理（阶段 1：最小可用） ----

    async def _on_auth(self, ws: websockets.WebSocketServerProtocol, msg: dict) -> None:
        """配对鉴权。

        阶段 1：先直接放行（完整配对码校验在阶段 6 实现）。
        返回 connected 消息，携带电脑设备名 / IP / 端口，手机端据此确认连接成功。
        """
        device_id = msg.get("deviceId", "")
        if device_id:
            log.info("设备接入 deviceId=%s", device_id)
        reply = {
            "type": "connected",
            "deviceName": socket.gethostname(),
            "ip": get_lan_ip(),
            "port": self.port,
        }
        await ws.send(json.dumps(reply, ensure_ascii=False))

    async def _on_mouse_move(self, ws: websockets.WebSocketServerProtocol, msg: dict) -> None:
        dx = msg.get("dx", 0)
        dy = msg.get("dy", 0)
        if dx or dy:
            pyautogui.moveRel(int(dx), int(dy))

    async def _on_mouse_click(self, ws: websockets.WebSocketServerProtocol, msg: dict) -> None:
        button = self.button_map.get(msg.get("button", "left"), "left")
        pyautogui.click(button=button)

    async def _on_mouse_scroll(self, ws: websockets.WebSocketServerProtocol, msg: dict) -> None:
        delta = msg.get("delta", 0)
        pyautogui.scroll(int(delta))

    async def _on_mouse_down(self, ws: websockets.WebSocketServerProtocol, msg: dict) -> None:
        button = self.button_map.get(msg.get("button", "left"), "left")
        pyautogui.mouseDown(button=button)

    async def _on_mouse_up(self, ws: websockets.WebSocketServerProtocol, msg: dict) -> None:
        button = self.button_map.get(msg.get("button", "left"), "left")
        pyautogui.mouseUp(button=button)

    async def _on_key(self, ws: websockets.WebSocketServerProtocol, msg: dict) -> None:
        """按键消息。

        key: copy/paste/select_all/enter/backspace
        times: 重复次数（退格删除用），最多 100
        """
        key = msg.get("key")
        times = min(int(msg.get("times", 1) or 1), 100)
        key_map = {
            "copy": ("ctrl", "c"),
            "paste": ("ctrl", "v"),
            "select_all": ("ctrl", "a"),
            "enter": ("enter",),
            "backspace": ("backspace",),
        }
        combo = key_map.get(key)
        if not combo:
            return
        if len(combo) == 2:
            pyautogui.hotkey(*combo)
        else:
            for _ in range(times):
                pyautogui.press(combo[0])

    async def _on_text(self, ws: websockets.WebSocketServerProtocol, msg: dict) -> None:
        """文字上屏。

        ASCII 快路径：直接 typewrite，避免剪贴板读写（高频增量输入流畅）。
        非 ASCII（中文等）：写剪贴板 -> Ctrl+V -> 恢复原剪贴板。
        """
        content = msg.get("content", "")
        if not content:
            return
        self._paste_text(content)

    @staticmethod
    def _paste_text(content: str) -> None:
        """文字上屏：SendInput Unicode 直接注入（不经过剪贴板，稳定且不破坏剪贴板）。

        若 SendInput 失败（个别窗口不支持 Unicode 注入），回退剪贴板 + Ctrl+V。
        """
        try:
            send_unicode(content)
            return
        except Exception:
            log.debug("SendInput 注入失败，回退剪贴板", exc_info=True)

        import pyperclip  # pyautogui 依赖中已包含

        original = pyperclip.paste()
        try:
            pyperclip.copy(content)
            pyautogui.hotkey("ctrl", "v")
        finally:
            # 恢复原剪贴板
            try:
                pyperclip.copy(original)
            except Exception:
                pass
