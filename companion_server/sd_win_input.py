"""
============================================================================
ARCHIVO: sd_win_input.py
QUÉ HACE: Inyecta eventos reales de ratón, scroll, teclado Unicode, atajos
          y portapapeles en Windows desde iOS y Android.
CÓMO FUNCIONA: Adjunta cada operación al `InputDesktop` activo mediante
               `OpenInputDesktop` + `SetThreadDesktop` y habilita DPI awareness
               para que `SetCursorPos` y `SendInput` funcionen 1:1.
POR QUÉ EXISTE: Sin `OpenInputDesktop`, procesos en segundo plano fallan
                silenciosamente al intentar mover o pulsar el ratón en Windows.
============================================================================
"""

import ctypes
import os
import time
from ctypes import wintypes
from sd_config import last_pointer, log


def with_input_desktop(fn):
    """Adjunta el hilo actual al InputDesktop activo de Windows antes de inyectar entrada."""
    if os.name != "nt":
        return fn()
    user32 = ctypes.windll.user32
    user32.SetProcessDPIAware()
    hdesk = user32.OpenInputDesktop(0, False, 0x01FF)
    if hdesk:
        user32.SetThreadDesktop(hdesk)
    try:
        return fn()
    finally:
        if hdesk:
            user32.CloseDesktop(hdesk)


def handle_normalized_pointer(data: dict, click: bool):
    """Posiciona el cursor en coordenadas normalizadas (0..1) y opcionalmente pulsa."""
    try:
        nx = max(0.0, min(1.0, float(data.get("x", 0.5))))
        ny = max(0.0, min(1.0, float(data.get("y", 0.5))))
        last_pointer["x"], last_pointer["y"] = int(nx * 1280), int(ny * 720)
    except Exception:
        return
    if os.name != "nt":
        return

    def _exec():
        u = ctypes.windll.user32
        sw, sh = u.GetSystemMetrics(0) or 1920, u.GetSystemMetrics(1) or 1080
        px, py = int(nx * sw), int(ny * sh)
        u.SetCursorPos(px, py)
        u.mouse_event(0x8000 | 0x0001, int(nx * 65535), int(ny * 65535), 0, 0)
        if click:
            btn = data.get("button", "left")
            log.info(f"Input click ({btn}) en ({px}, {py})")
            down_f, up_f = (0x0008, 0x0010) if btn == "right" else (0x0002, 0x0004)
            u.mouse_event(down_f, 0, 0, 0, 0)
            u.mouse_event(up_f, 0, 0, 0, 0)

    try: with_input_desktop(_exec)
    except Exception as ex: log.warning(f"Error en handle_normalized_pointer: {ex}")


def handle_double_click(data: dict):
    """Ejecuta un doble clic izquierdo rápido en las coordenadas normalizadas."""
    handle_normalized_pointer(data, click=True); time.sleep(0.04); handle_normalized_pointer(data, click=True)


def handle_mouse_button(data: dict, down: bool):
    """Presiona o libera el botón del ratón para operaciones de arrastre y selección."""
    if os.name != "nt":
        return
    def _exec():
        u = ctypes.windll.user32
        nx = max(0.0, min(1.0, float(data.get("x", 0.5))))
        ny = max(0.0, min(1.0, float(data.get("y", 0.5))))
        u.SetCursorPos(int(nx * (u.GetSystemMetrics(0) or 1920)), int(ny * (u.GetSystemMetrics(1) or 1080)))
        btn = data.get("button", "left")
        u.mouse_event((0x0008 if down else 0x0010) if btn == "right" else (0x0002 if down else 0x0004), 0, 0, 0, 0)
    try: with_input_desktop(_exec)
    except Exception: pass


def handle_scroll(data: dict):
    """Simula desplazamiento vertical de la rueda del ratón en Windows."""
    if os.name != "nt":
        return
    try:
        dy = int(data.get("delta_y", 0))
        if dy != 0:
            with_input_desktop(lambda: ctypes.windll.user32.mouse_event(0x0800, 0, 0, dy, 0))
    except Exception:
        pass


def send_unicode_char(text: str):
    """Escribe caracteres Unicode directamente en la ventana activa con SendInput."""
    if os.name != "nt" or not text:
        return

    class KEYBDINPUT(ctypes.Structure):
        _fields_ = [("wVk", wintypes.WORD), ("wScan", wintypes.WORD),
                    ("dwFlags", wintypes.DWORD), ("time", wintypes.DWORD),
                    ("dwExtraInfo", ctypes.c_ulonglong)]

    class INPUT(ctypes.Structure):
        class _INPUT(ctypes.Union):
            _fields_ = [("ki", KEYBDINPUT)]
        _anonymous_ = ("_input",)
        _fields_ = [("type", wintypes.DWORD), ("_input", _INPUT)]

    def _exec():
        u = ctypes.windll.user32
        for ch in text:
            code = ord(ch)
            for flags in (0x0004, 0x0004 | 0x0002):
                inp = INPUT(type=1)
                inp.ki = KEYBDINPUT(wVk=0, wScan=code, dwFlags=flags, time=0, dwExtraInfo=0)
                u.SendInput(1, ctypes.byref(inp), ctypes.sizeof(INPUT))

    try:
        with_input_desktop(_exec)
    except Exception:
        pass


def send_special_key(key_name: str):
    """Envía teclas especiales y atajos de Windows (Ctrl+C/V/A/Z/Y/S, Win+D, Alt+Tab)."""
    if os.name != "nt" or not key_name:
        return
    k = key_name.lower()
    combos = {
        "ctrl_c": (0x11, 0x43), "ctrl_v": (0x11, 0x56), "ctrl_a": (0x11, 0x41),
        "ctrl_z": (0x11, 0x5A), "ctrl_y": (0x11, 0x59), "ctrl_s": (0x11, 0x53),
        "win_d": (0x5B, 0x44), "alt_tab": (0x12, 0x09),
    }
    vk_map = {
        "escape": 0x1B, "tab": 0x09, "backspace": 0x08, "enter": 0x0D, "win": 0x5B,
        "space": 0x20, "up": 0x26, "down": 0x28, "left": 0x25, "right": 0x27,
        "delete": 0x2E, "del": 0x2E, "alt": 0x12, "home": 0x24, "end": 0x23,
        "pageup": 0x21, "pagedown": 0x22,
    }

    def _exec():
        u = ctypes.windll.user32
        if k in combos:
            mod, key = combos[k]
            u.keybd_event(mod, 0, 0, 0); u.keybd_event(key, 0, 0, 0)
            u.keybd_event(key, 0, 0x0002, 0); u.keybd_event(mod, 0, 0x0002, 0)
        elif k in vk_map:
            vk = vk_map[k]
            u.keybd_event(vk, 0, 0, 0); u.keybd_event(vk, 0, 0x0002, 0)

    try:
        with_input_desktop(_exec)
    except Exception:
        pass


def get_pc_clipboard():
    """Lee texto Unicode del portapapeles de Windows."""
    try:
        import win32clipboard, win32con
        win32clipboard.OpenClipboard()
        try:
            return win32clipboard.GetClipboardData(win32con.CF_UNICODETEXT) if win32clipboard.IsClipboardFormatAvailable(win32con.CF_UNICODETEXT) else None
        finally:
            win32clipboard.CloseClipboard()
    except Exception:
        return None


def set_pc_clipboard(text: str):
    """Escribe texto Unicode en el portapapeles de Windows."""
    try:
        import win32clipboard, win32con
        win32clipboard.OpenClipboard()
        try:
            win32clipboard.EmptyClipboard(); win32clipboard.SetClipboardData(win32con.CF_UNICODETEXT, text)
        finally:
            win32clipboard.CloseClipboard()
    except Exception as e:
        log.debug(f"set clipboard error: {e}")


def restore_pc_cursor():
    """Restaura todos los cursores del sistema mediante SPI_SETCURSORS (0x0057)."""
    if os.name == "nt":
        try:
            ctypes.windll.user32.SystemParametersInfoW(0x0057, 0, None, 3)
            log.info("Cursor del PC restaurado")
        except Exception as e:
            log.warning(f"No se pudo restaurar el cursor: {e}")
