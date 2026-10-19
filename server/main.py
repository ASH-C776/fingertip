"""指间（Fingertip）电脑端入口。

启动流程：
1. 启动 WebSocket Server（后台线程）
2. 打开 pywebview 主窗口（无边框圆角 + 系统毛玻璃）
3. 关闭=隐藏到托盘，仅托盘『退出』真正退出

运行：
    .venv\\Scripts\\python main.py
"""
from __future__ import annotations

import base64
import ctypes
import faulthandler
import html
import json
import logging
import os
import socket
import sys
import threading
import time
import traceback
import winreg

# ---- 崩溃日志：windowed 打包后 console 不可见，必须落文件才能排查 ----
_CRASH_LOG = (
    os.path.join(os.path.dirname(os.path.abspath(sys.executable)), "指间-crash.log")
    if getattr(sys, "frozen", False)
    else os.path.join(os.path.dirname(os.path.abspath(__file__)), "指间-crash.log")
)


def _write_crash(title: str, detail: str) -> None:
    """追加写崩溃详情；显式 UTF-8，避免中文 Windows 默认 GBK 乱码。"""
    try:
        with open(_CRASH_LOG, "a", encoding="utf-8") as fh:
            fh.write("\n===== %s %s =====\n%s\n" % (title, time.strftime("%Y-%m-%d %H:%M:%S"), detail))
    except Exception:
        pass


def _on_uncaught(exc_type, exc_value, exc_tb) -> None:
    """主线程未捕获异常：先落日志，再按默认行为处理（保持原有退出语义）。"""
    _write_crash("UNCAUGHT EXCEPTION", "".join(traceback.format_exception(exc_type, exc_value, exc_tb)))
    sys.__excepthook__(exc_type, exc_value, exc_tb)


def _on_thread_crash(args) -> None:
    """子线程未捕获异常（WebSocket 服务跑在子线程，崩了原本完全静默）。"""
    _write_crash(
        "THREAD EXCEPTION (%s)" % getattr(args, "thread", None),
        "".join(traceback.format_exception(args.exc_type, args.exc_value, args.exc_tb)),
    )


sys.excepthook = _on_uncaught
threading.excepthook = _on_thread_crash
try:
    _crash_fh = open(_CRASH_LOG, "a", encoding="utf-8")
    _crash_fh.write("\n===== SESSION START %s =====\n" % time.strftime("%Y-%m-%d %H:%M:%S"))
    _crash_fh.flush()
    faulthandler.enable(_crash_fh)  # 段错误等硬崩溃也能留痕
except Exception:
    pass


def _watchdog_exit(delay: float = 4.0) -> None:
    """退出兜底：用户确认退出后，若 delay 秒内进程仍未结束（WebView2 销毁
    挂起、消息循环卡死等任何原因），强制结束进程，杜绝「点了退出退不掉」。"""
    time.sleep(delay)
    _write_crash("EXIT WATCHDOG", "退出流程 %.1f 秒未完成，强制结束进程" % delay)
    os._exit(0)

import webview

from ft_server import DiscoveryService, FingertipServer, get_lan_ip

logging.basicConfig(level=logging.INFO, format="[%(asctime)s] %(levelname)s %(message)s")
log = logging.getLogger("fingertip.main")

APP_NAME = "指间"
VERSION = "v1.1.5"
WINDOW_W, WINDOW_H = 360, 360

# 标题栏内嵌的小 logo（base64）
_ICON_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "appicon64.png")
try:
    ICON_B64 = base64.b64encode(open(_ICON_PATH, "rb").read()).decode("ascii")
except OSError:
    ICON_B64 = ""

RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
RUN_VALUE = "Fingertip"

# ---- 系统毛玻璃（Win11 Mica/Acrylic，Win10 Acrylic）----

# DwmSetWindowAttribute 属性
DWMWA_SYSTEMBACKDROP_TYPE = 38
DWMWA_WINDOW_CORNER_PREFERENCE = 33
DWMWA_USE_IMMERSIVE_DARK_MODE = 20
BACKDROP_AUTO = 0
BACKDROP_MICA = 2
BACKDROP_ACRYLIC = 3
CORNER_ROUND = 2


class ACCENT_POLICY(ctypes.Structure):
    _fields_ = [
        ("AccentState", ctypes.c_uint),
        ("AccentFlags", ctypes.c_uint),
        ("GradientColor", ctypes.c_uint),
        ("AnimationId", ctypes.c_uint),
    ]


class WINDOWCOMPOSITIONATTRIBDATA(ctypes.Structure):
    _fields_ = [
        ("Attrib", ctypes.c_int),
        ("Data", ctypes.c_void_p),
        ("SizeOfData", ctypes.c_size_t),
    ]


def _set_dwm_attr(hwnd: int, attr: int, value: int) -> bool:
    try:
        result = ctypes.windll.dwmapi.DwmSetWindowAttribute(
            hwnd, attr, ctypes.byref(ctypes.c_int(value)), ctypes.sizeof(ctypes.c_int)
        )
        return result == 0
    except Exception:
        return False


def _enable_win10_acrylic(hwnd: int) -> bool:
    """Win10 1703+ 使用未文档化 API 开启 Acrylic。"""
    try:
        accent = ACCENT_POLICY(3, 2, 0xCC000000, 0)  # ACCENT_ENABLE_ACRYLICBLURBEHIND
        data = WINDOWCOMPOSITIONATTRIBDATA(
            19,  # WCA_ACCENT_POLICY
            ctypes.addressof(accent),
            ctypes.sizeof(accent),
        )
        result = ctypes.windll.user32.SetWindowCompositionAttribute(hwnd, ctypes.byref(data))
        return bool(result)
    except Exception:
        return False


def _window_hwnd(window):
    """跨线程获取窗口句柄。

    不能在后台线程读 WinForms 的 native.Handle（会返回 IntPtr.Zero 或在错误线程
    创建句柄），改用 Win32 FindWindow 按标题查找，跨线程安全。
    """
    user32 = ctypes.windll.user32
    user32.FindWindowW.restype = ctypes.c_void_p  # 64 位下必须显式声明，否则句柄高位被截断
    hwnd = user32.FindWindowW(None, f"{APP_NAME} {VERSION}")
    if hwnd:
        return int(hwnd)
    return None


def _force_show_window(hwnd: int) -> None:
    """跨线程可靠地还原并置前窗口。

    两个坑（v1.1.2 及以前「托盘点了没反应」的根因）：
    1. 托盘线程不能调 pywebview 的 window.show()/restore()——WinForms/WebView2
       跨线程调用会静默失败；必须改用 Win32 直接操作窗口句柄。
    2. 裸调 SetForegroundWindow 会被系统拒绝（只有前台线程能设置前台窗口）；
       用 AttachThreadInput 临时与前台线程挂接输入队列来绕过该限制。
    """
    user32 = ctypes.windll.user32
    kernel32 = ctypes.windll.kernel32
    user32.GetForegroundWindow.restype = ctypes.c_void_p
    user32.GetWindowThreadProcessId.restype = ctypes.c_ulong
    kernel32.GetCurrentThreadId.restype = ctypes.c_ulong
    SW_RESTORE = 9
    # 1) 还原（窗口可能被最小化或已 hide 到托盘）
    user32.ShowWindow(hwnd, SW_RESTORE)
    # 2) 置前：与当前前台线程临时挂接输入队列，绕过前台限制
    h_fore = user32.GetForegroundWindow()
    if h_fore and int(h_fore) != hwnd:
        tid_fore = user32.GetWindowThreadProcessId(h_fore, None)
        tid_self = kernel32.GetCurrentThreadId()
        if tid_fore and tid_fore != tid_self:
            user32.AttachThreadInput(tid_self, tid_fore, True)
            try:
                user32.SetForegroundWindow(hwnd)
                user32.BringWindowToTop(hwnd)
            finally:
                user32.AttachThreadInput(tid_self, tid_fore, False)
            return
    user32.SetForegroundWindow(hwnd)
    user32.BringWindowToTop(hwnd)


def _get_hwnd_blocking(window, timeout: float = 6.0):
    """轮询等待窗口句柄就绪。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        hwnd = _window_hwnd(window)
        if hwnd:
            return hwnd
        time.sleep(0.2)
    return None


def apply_window_icon(hwnd: int) -> None:
    """给窗口/任务栏/Alt+Tab 设置指尖图标（pywebview 6.x 无 icon 参数）。

    同时设置 WM_SETICON（窗口运行时图标，任务栏/Alt+Tab 优先用）
    与 SetClassLongPtr（窗口类图标，作为回退）。
    """
    ico_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fingertip.ico")
    if not os.path.exists(ico_path):
        return
    try:
        user32 = ctypes.windll.user32
        # 64 位必须声明指针宽度，否则句柄被截断
        user32.SetClassLongPtrW.restype = ctypes.c_void_p
        user32.SetClassLongPtrW.argtypes = [ctypes.wintypes.HWND, ctypes.c_int, ctypes.c_void_p]
        IMAGE_ICON = 1
        LR_LOADFROMFILE = 0x00000010
        WM_SETICON = 0x0080
        GCLP_HICON, GCLP_HICONSM = -14, -34
        ICON_SMALL, ICON_BIG = 0, 1
        # ICON_SMALL 取 32px：任务栏实际显示 24-32px，16px 源被放大即模糊（v1.1.5 修复）
        hicon_small = user32.LoadImageW(None, ico_path, IMAGE_ICON, 32, 32, LR_LOADFROMFILE)
        hicon_big = user32.LoadImageW(None, ico_path, IMAGE_ICON, 256, 256, LR_LOADFROMFILE)
        if hicon_big:
            user32.SendMessageW(hwnd, WM_SETICON, ICON_BIG, hicon_big)
            user32.SetClassLongPtrW(hwnd, GCLP_HICON, hicon_big)
        if hicon_small:
            user32.SendMessageW(hwnd, WM_SETICON, ICON_SMALL, hicon_small)
            user32.SetClassLongPtrW(hwnd, GCLP_HICONSM, hicon_small)
        log.info("窗口图标已设置")
    except Exception as e:
        log.warning("窗口图标设置失败: %s", e)


def apply_system_backdrop(hwnd: int) -> None:
    """给窗口设置系统毛玻璃 + 圆角。Win11 优先 Acrylic，Win10 用兼容 API。"""
    # Win11 22000+：Acrylic（回退 Mica）
    if not _set_dwm_attr(hwnd, DWMWA_SYSTEMBACKDROP_TYPE, BACKDROP_ACRYLIC):
        _set_dwm_attr(hwnd, DWMWA_SYSTEMBACKDROP_TYPE, BACKDROP_MICA)
    # 圆角（Win11 支持，Win10 忽略）
    _set_dwm_attr(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, CORNER_ROUND)
    # Win10 兜底 Acrylic
    _enable_win10_acrylic(hwnd)
    log.info("已应用系统毛玻璃背景")


# ---- 开机启动（注册表 HKCU Run）----

def _autostart_command() -> str:
    return f'"{sys.executable}" "{os.path.abspath(__file__)}" --silent'


def get_autostart() -> bool:
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as k:
            winreg.QueryValueEx(k, RUN_VALUE)
            return True
    except FileNotFoundError:
        return False


def set_autostart(enabled: bool) -> bool:
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as k:
            if enabled:
                winreg.SetValueEx(k, RUN_VALUE, 0, winreg.REG_SZ, _autostart_command())
            else:
                try:
                    winreg.DeleteValue(k, RUN_VALUE)
                except FileNotFoundError:
                    pass
        return True
    except OSError:
        return False


# ---- 窗口 HTML ----

def build_html(ip: str, port: int, device_name: str) -> str:
    """阶段 2/9：完整毛玻璃 UI（卡片化、统一字号与对齐）。"""
    safe_name = html.escape(device_name)
    safe_ip = html.escape(ip)
    return f"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<style>
  :root {{
    --bg: rgba(242,243,245,0.42);
    --card: rgba(255,255,255,0.72);
    --text: #1A1D21;
    --sub: #6E6E73;
    --accent: #0B99FB;
    --divider: rgba(26,29,33,0.08);
    --green: #1FA971;
  }}
  @media (prefers-color-scheme: dark) {{
    :root {{
      --bg: rgba(20,21,25,0.46);
      --card: rgba(44,46,52,0.62);
      --text: #F2F4F8;
      --sub: #98989D;
      --accent: #4AB0FF;
      --divider: rgba(242,244,248,0.1);
      --green: #30D158;
    }}
  }}
  * {{ margin: 0; padding: 0; box-sizing: border-box; }}
  html, body {{ height: 100%; overflow: hidden; }}
  body {{
    font-family: "SF Pro Display", "PingFang SC", "Microsoft YaHei", sans-serif;
    font-size: 13px;
    background: var(--bg);
    color: var(--text);
    border-radius: 12px;
    user-select: none;
    display: flex; flex-direction: column;
  }}
  /* 字号体系：11 说明 / 12 小标签 / 13 正文 / 14 强调 / 15 地址 */
  .fs11 {{ font-size: 11px; }} .fs12 {{ font-size: 12px; }}
  .fs13 {{ font-size: 13px; }} .fs14 {{ font-size: 14px; }} .fs15 {{ font-size: 15px; }}

  /* ---- 标题栏 ---- */
  .titlebar {{
    -webkit-app-region: drag;
    height: 44px; flex: 0 0 44px;
    display: flex; align-items: center;
    padding: 0 12px 0 18px;
  }}
  .titlebar .appicon {{ width: 18px; height: 18px; border-radius: 5px; margin-right: 8px; flex: 0 0 18px; }}
  .titlebar .app {{ font-size: 13px; font-weight: 600; letter-spacing: .3px; }}
  .titlebar .ver {{ font-size: 11px; color: var(--sub); margin-left: 6px; }}
  .titlebar .spacer {{ flex: 1; }}
  .tb-btn {{
    -webkit-app-region: no-drag;
    width: 28px; height: 28px; border: none; cursor: pointer;
    background: transparent; color: var(--sub);
    font-size: 14px; border-radius: 6px;
    display: flex; align-items: center; justify-content: center;
    transition: background .12s, color .12s;
  }}
  .tb-btn:hover {{ background: rgba(0,0,0,0.06); color: var(--text); }}
  .tb-btn.close:hover {{ background: #E81123; color: #fff; }}

  /* ---- 内容区：卡片化布局 ---- */
  .content {{ flex: 1; padding: 2px 20px 20px; display: flex; flex-direction: column; gap: 12px; }}
  .hint {{ text-align: center; font-size: 12px; color: var(--sub); }}

  .card {{
    background: var(--card);
    border: 1px solid var(--divider);
    border-radius: 12px;
    padding: 12px 16px;
    box-shadow:
      0 1px 2px rgba(20,28,44,.05),
      0 6px 16px -4px rgba(20,28,44,.08),
      0 16px 32px -12px rgba(20,28,44,.10);
  }}
  .card-title {{
    font-size: 11px; font-weight: 600; color: var(--sub);
    letter-spacing: .4px;
    margin-bottom: 6px;
  }}
  .card-sub {{ font-size: 11px; color: var(--sub); margin-top: 6px; line-height: 1.5; }}
  .card-sub.center {{ text-align: center; }}

  /* 连接卡：地址整行居中，复制按钮在标题行右侧 */
  .addr-head {{ display: flex; align-items: center; }}
  .addr-head .copy-btn {{ margin-left: auto; }}
  .addr-row {{ display: flex; justify-content: center; margin-top: 4px; }}
  .addr {{
    font-family: Consolas, "SF Mono", monospace;
    font-size: 15px; font-weight: 600; letter-spacing: .3px;
    overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
    max-width: 100%; min-width: 0;
  }}
  .copy-btn {{
    flex: 0 0 auto;
    border: 1px solid var(--divider); cursor: pointer;
    font-size: 11px; color: var(--accent); background: transparent;
    padding: 3px 10px; border-radius: 6px;
    transition: background .12s;
  }}
  .copy-btn:hover {{ background: rgba(0,122,255,0.12); }}

  /* 状态卡 */
  .status-card {{ padding: 14px 16px; }}
  .status-row {{ display: flex; align-items: center; justify-content: center; gap: 8px; }}
  .status-row .txt {{ font-size: 14px; font-weight: 600; }}
  .dot {{ width: 10px; height: 10px; border-radius: 50%; background: #8E8E93; flex: 0 0 10px; }}
  .dot.on {{ background: var(--green); box-shadow: 0 0 0 0 rgba(52,199,89,.5); animation: pulse 2s infinite; }}
  @keyframes pulse {{
    0% {{ box-shadow: 0 0 0 0 rgba(52,199,89,.45); }}
    70% {{ box-shadow: 0 0 0 7px rgba(52,199,89,0); }}
    100% {{ box-shadow: 0 0 0 0 rgba(52,199,89,0); }}
  }}

  /* 设置卡 */
  .setting {{ display: flex; align-items: center; }}
  .setting .left {{ flex: 1; }}
  .setting-label {{ font-size: 13px; font-weight: 500; }}
  .setting-sub {{ font-size: 11px; color: var(--sub); margin-top: 3px; }}
  .toggle {{
    position: relative; width: 46px; height: 28px; border-radius: 14px;
    background: #E9E9EB; cursor: pointer; transition: background .2s; flex: 0 0 46px;
  }}
  @media (prefers-color-scheme: dark) {{ .toggle {{ background: #3A3A3C; }} }}
  .toggle .knob {{
    position: absolute; top: 2px; left: 2px; width: 24px; height: 24px;
    border-radius: 50%; background: #fff; box-shadow: 0 2px 5px rgba(0,0,0,.25);
    transition: left .2s;
  }}
  .toggle.on {{ background: var(--accent); }}
  .toggle.on .knob {{ left: 20px; }}

  .toast {{
    position: fixed; left: 50%; bottom: 60px; transform: translateX(-50%) translateY(8px);
    background: rgba(0,0,0,0.75); color: #fff; font-size: 12px;
    padding: 6px 14px; border-radius: 20px; opacity: 0; pointer-events: none;
    transition: opacity .15s, transform .15s;
  }}
  .toast.show {{ opacity: 1; transform: translateX(-50%) translateY(0); }}
</style>
</head>
<body>
  <div class="titlebar">
    {f'<img class="appicon" src="data:image/png;base64,{ICON_B64}">' if ICON_B64 else ''}
    <span class="app">{APP_NAME}</span>
    <span class="ver">{VERSION}</span>
    <span class="spacer"></span>
    <button class="tb-btn close" onclick="pywebview.api.hide_to_tray()" title="最小化到托盘">✕</button>
  </div>
  <div class="content">
    <div class="hint">内网打开 App 可发现设备</div>

    <div class="card">
      <div class="addr-head">
        <span class="card-title">连接地址</span>
        <button class="copy-btn" onclick="copyAddr()">复制</button>
      </div>
      <div class="addr-row">
        <span class="addr">{safe_ip} : {port}</span>
      </div>
    </div>

    <div class="card status-card">
      <div class="status-row">
        <span class="dot" id="dot"></span>
        <span class="txt" id="statusText">等待连接…</span>
      </div>
      <div class="card-sub center" id="statusSub">等待手机接入</div>
    </div>

    <div class="card">
      <div class="setting">
        <div class="left">
          <div class="setting-label">开机启动</div>
          <div class="setting-sub">开机后自动在后台运行，不显示窗口</div>
        </div>
        <div class="toggle" id="autostartToggle" onclick="toggleAutostart()">
          <span class="knob"></span>
        </div>
      </div>
    </div>
  </div>

  <div class="toast" id="toast">已复制</div>

<script>
  function showToast(text) {{
    const t = document.getElementById('toast');
    t.textContent = text;
    t.classList.add('show');
    clearTimeout(t._timer);
    t._timer = setTimeout(() => t.classList.remove('show'), 1500);
  }}

  async function copyAddr() {{
    try {{
      await navigator.clipboard.writeText('{safe_ip}:{port}');
    }} catch (e) {{
      const s = '{safe_ip}:{port}';
      const ta = document.createElement('textarea');
      ta.value = s; document.body.appendChild(ta); ta.select();
      document.execCommand('copy'); ta.remove();
    }}
    showToast('已复制');
  }}

  async function refreshStatus() {{
    try {{
      const s = await pywebview.api.get_status();
      applyStatus(s);
    }} catch (e) {{}}
  }}

  // 服务端通过 evaluate_js 调用此函数主动推送连接/断开事件，取代 1.5s 轮询
  window.__onFtStatus = function(s) {{
    applyStatus(s);
  }};

  function applyStatus(s) {{
    const dot = document.getElementById('dot');
    const txt = document.getElementById('statusText');
    const sub = document.getElementById('statusSub');
    if (s.connected) {{
      dot.classList.add('on');
      txt.textContent = '已连接：' + s.device;
      txt.style.color = 'var(--green)';
      sub.textContent = '手机已接入，可开始操控';
      sub.style.color = 'var(--sub)';
    }} else {{
      dot.classList.remove('on');
      txt.textContent = '等待连接…';
      txt.style.color = 'var(--sub)';
      sub.textContent = '等待手机接入';
    }}
  }}

  refreshStatus();

  async function initAutostart() {{
    try {{
      const on = await pywebview.api.get_autostart();
      document.getElementById('autostartToggle').classList.toggle('on', on);
    }} catch (e) {{}}
  }}
  initAutostart();

  async function toggleAutostart() {{
    const el = document.getElementById('autostartToggle');
    const next = !el.classList.contains('on');
    try {{
      const ok = await pywebview.api.set_autostart(next);
      if (ok) {{
        el.classList.toggle('on', next);
        showToast(next ? '已开启开机启动' : '已关闭开机启动');
      }}
    }} catch (e) {{}}
  }}
</script>
</body>
</html>"""


# ---- JS API ----

class WindowApi:
    """pywebview JS API 桥。"""

    def __init__(self, server: FingertipServer) -> None:
        self.server = server
        self._window = None

    def set_window(self, window) -> None:
        self._window = window

    def hide_to_tray(self) -> None:
        """窗口 ✕ / 关闭：隐藏到托盘，不退出。"""
        if self._window:
            self._window.hide()

    def get_server_info(self) -> dict:
        return {"ip": get_lan_ip(), "port": self.server.port}

    def get_status(self) -> dict:
        return {
            "connected": self.server.is_connected,
            "device": self.server.connected_device,
        }

    def get_autostart(self) -> bool:
        return get_autostart()

    def set_autostart(self, enabled: bool) -> bool:
        return set_autostart(enabled)


# ---- 阶段 8：托盘常驻 ----

def build_tray(window, server, exiting: threading.Event):
    """创建系统托盘图标：显示窗口 / 开机启动 / 退出。"""
    from pystray import Icon, Menu, MenuItem
    from PIL import Image

    icon_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "trayicon64.png")
    if os.path.exists(icon_path):
        tray_img = Image.open(icon_path)
    else:
        tray_img = Image.new("RGBA", (64, 64), (251, 132, 48, 255))

    def _show(_icon=None, _item=None):
        # 本回调运行在托盘线程：绝不能调 pywebview 的 window.show()/restore()
        # （WinForms/WebView2 跨线程调用静默失败，v1.1.2 的 bug），全部走 Win32。
        hwnd = _window_hwnd(window)
        if hwnd:
            _force_show_window(hwnd)
        else:
            # 兜底：窗口尚未创建（极端时序），退回 pywebview API
            try:
                window.restore()
                window.show()
            except Exception:
                pass

    def _toggle_autostart(_icon, _item):
        set_autostart(not get_autostart())

    def _quit(_icon, _item):
        # 退出二次确认（父窗口挂主窗口，避免确认框被其他窗口遮挡点不到）
        MB_YESNO = 0x0004
        MB_ICONQUESTION = 0x0020
        IDYES = 6
        hwnd = _window_hwnd(window)
        try:
            ret = ctypes.windll.user32.MessageBoxW(
                hwnd, "确定要退出指间吗？", "指间", MB_YESNO | MB_ICONQUESTION
            )
        except Exception:
            ret = ctypes.windll.user32.MessageBoxW(
                None, "确定要退出指间吗？", "指间", MB_YESNO | MB_ICONQUESTION
            )
        _write_crash("EXIT CONFIRMED" if ret == IDYES else "EXIT CANCELLED", "托盘退出确认，ret=%d" % ret)
        if ret != IDYES:
            return
        exiting.set()
        # 关键：不能在本线程（托盘线程）直接 window.destroy()——WebView2 必须在
        # 创建线程关闭，跨线程调用会挂起，导致「退出卡死、退不掉」（v1.1.2 的 bug）。
        # 改为给主窗口发 WM_CLOSE，让 GUI 线程走标准 closing 流程（on_closing
        # 看到 exiting 已置位即放行关闭）；再由 watchdog 兜底强杀。
        threading.Thread(target=_watchdog_exit, name="ft-exit-watchdog", daemon=True).start()
        if hwnd:
            ctypes.windll.user32.PostMessageW(hwnd, 0x0010, 0, 0)  # WM_CLOSE
        try:
            _icon.stop()
        except Exception:
            pass

    menu = Menu(
        # default=True：双击托盘图标直接显示主界面（pystray win32 双击激活默认项）
        MenuItem("显示主界面", _show, default=True),
        MenuItem("开机启动", _toggle_autostart, checked=lambda item: get_autostart()),
        MenuItem("退出", _quit),
    )
    return Icon("fingertip", tray_img, "指间 Fingertip", menu)


# ---- 主流程 ----

def main() -> None:
    if "--version" in sys.argv:
        print(f"{APP_NAME} {VERSION}")
        return
    # 单实例保护：已有实例时激活其窗口并退出（防止多实例争用 WebView2 目录导致白屏）
    kernel32 = ctypes.windll.kernel32
    kernel32.CreateMutexW(None, False, "FingertipApp_SingleInstance_Mutex")
    if kernel32.GetLastError() == 183:  # ERROR_ALREADY_EXISTS
        hwnd = ctypes.windll.user32.FindWindowW(None, f"{APP_NAME} {VERSION}")
        if hwnd:
            ctypes.windll.user32.ShowWindow(hwnd, 9)
            ctypes.windll.user32.SetForegroundWindow(hwnd)
        return
    silent = "--silent" in sys.argv

    # 预初始化：_push_status 闭包引用 window，server.start() 早于 create_window，
    # 若这期间就有连接事件，未绑定的自由变量会抛 NameError（同样被 except 吞掉）
    window = None

    def _push_status(payload: dict) -> None:
        """连接/断开状态主动推送给浏览器 UI（替代 1.5s 轮询）。

        闭包捕获 main() 局部变量 window（其赋值在下方 webview.create_window）。
        """
        try:
            if window is None:
                return
            ptype = payload.get("type")
            if ptype == "connected":
                data = {"connected": True, "device": str(payload.get("device", ""))}
            elif ptype == "disconnected":
                data = {"connected": False, "device": ""}
            else:
                return  # 忽略 server 等内部事件，避免误推「断开」
            window.evaluate_js(
                f"window.__onFtStatus && window.__onFtStatus({json.dumps(data, ensure_ascii=False)})"
            )
        except Exception:
            # 落崩溃日志而非裸 log.debug：v1.1.3 及以前「连上了仍显示等待连接」
            # 就是 json 未导入的 NameError 被这里静默吞掉导致的
            _write_crash("STATUS PUSH FAILED", traceback.format_exc())
            log.warning("状态推送失败", exc_info=True)

    server = FingertipServer(on_status_change=_push_status)
    server.start()

    ip = get_lan_ip()
    device_name = socket.gethostname()

    # 阶段 6：启动设备发现（mDNS + UDP 广播），手机端可自动搜到本机
    discovery = DiscoveryService(server.port, device_name)
    discovery.start()

    api = WindowApi(server)
    window = webview.create_window(
        f"{APP_NAME} {VERSION}",
        html=build_html(ip, server.port, device_name),
        width=WINDOW_W,
        height=WINDOW_H,
        resizable=False,
        frameless=True,
        easy_drag=True,
        js_api=api,
    )
    api.set_window(window)

    # 阶段 8：关闭窗口 = 隐藏到托盘（托盘「退出」才真正退出）
    # pywebview closing 事件：handler 无参；返回 False 表示取消关闭
    exiting = threading.Event()

    def on_closing() -> None:
        if not exiting.is_set():
            window.hide()
            return False
        return None

    window.events.closing += on_closing

    def _setup_window() -> None:
        # on_ready 瞬间窗口可能尚未创建，轮询等待句柄
        deadline = time.time() + 8.0
        while time.time() < deadline:
            try:
                hwnd = _window_hwnd(window)
                if hwnd:
                    apply_system_backdrop(hwnd)
                    apply_window_icon(hwnd)
                    return
            except Exception as e:
                log.warning("窗口句柄探测异常: %s", e)
            time.sleep(0.5)
        log.warning("无法获取窗口句柄，毛玻璃/图标未应用")

    def on_ready() -> None:
        threading.Thread(target=_setup_window, daemon=True).start()
        if silent:
            # 静默模式（开机启动）：不显示窗口，托盘常驻
            window.hide()

    # 托盘常驻
    tray = build_tray(window, server, exiting)
    threading.Thread(target=tray.run, name="ft-tray", daemon=True).start()
    log.info("托盘已启动")

    # 让任务栏把本程序作为独立应用分组（图标显示为窗口图标而非 python.exe）
    try:
        ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID("com.fingertip.app.desktop")
    except Exception:
        pass

    webview.start(on_ready)
    server.stop()
    discovery.stop()
    try:
        tray.stop()
    except Exception:
        pass
    log.info("已退出")


if __name__ == "__main__":
    main()
