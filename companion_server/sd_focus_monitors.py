"""
============================================================================
ARCHIVO: sd_focus_monitors.py
QUÉ HACE: Hilos ligeros de sondeo para detectar campos de texto (UIA/Win32),
          ventana en primer plano, posición del cursor y cambios de portapapeles.
CÓMO FUNCIONA: Corre en hilos daemon controlados por `threading.Event` para
               cierre limpio sin procesos zombi, despachando eventos al loop asyncio.
POR QUÉ EXISTE: Mantiene sincronizados teclado inteligente, cursor y portapapeles
                entre el PC y el dispositivo móvil.
============================================================================
"""

import asyncio
import threading
import time
from sd_config import (
    FOREGROUND_POLL_INTERVAL,
    POLL_INTERVAL,
    TEXT_CONTROL_TYPES,
    last_clip,
    last_foreground,
    log,
)
from sd_win_input import get_pc_clipboard

try:
    import comtypes
    import comtypes.client
    comtypes.client.GetModule("UIAutomationCore.dll")
    from comtypes.gen import UIAutomationClient as UIA
    HAS_UIA = True
    log.info("UIAutomation disponible")
except Exception as e:
    HAS_UIA = False
    log.warning(f"UIAutomation no disponible: {e}. Usando fallback win32.")

try:
    import psutil
    import win32gui
    import win32process
    HAS_WIN32 = True
except ImportError:
    HAS_WIN32 = False
    log.warning("win32gui/psutil no disponibles.")


class FocusMonitor:
    """Monitoriza el foco de Windows y detecta campos de texto editables."""

    def __init__(self, on_text_field, on_cleared):
        self.on_text_field = on_text_field
        self.on_cleared = on_cleared
        self._last_hwnd = None
        self._in_text = False
        self._running = False

    def start(self):
        self._running = True
        threading.Thread(target=self._poll_loop, daemon=True).start()

    def stop(self):
        self._running = False

    def _poll_loop(self):
        while self._running:
            try:
                if HAS_UIA:
                    self._check_focus_uia()
                elif HAS_WIN32:
                    self._check_focus_win32()
            except Exception as e:
                log.debug(f"Focus check error: {e}")
            time.sleep(POLL_INTERVAL)

    def _check_focus_uia(self):
        try:
            auto = comtypes.client.CreateObject("{ff48dba4-60ef-4201-aa87-54103eef594e}", interface=UIA.IUIAutomation)
            focused = auto.GetFocusedElement()
            if focused is None:
                self._signal_cleared(); return
            if focused.CurrentControlType in TEXT_CONTROL_TYPES:
                r = focused.CurrentBoundingRectangle
                pid = focused.CurrentProcessId
                try: app = psutil.Process(pid).name()
                except Exception: app = str(pid)
                self._signal_text(r.left, r.top, r.right - r.left, r.bottom - r.top, app)
            else:
                self._signal_cleared()
        except Exception:
            self._signal_cleared()

    def _check_focus_win32(self):
        hwnd = win32gui.GetForegroundWindow()
        if hwnd == self._last_hwnd:
            return
        self._last_hwnd = hwnd
        cls = win32gui.GetClassName(hwnd)
        if cls in {"Edit", "RichEdit20W", "Scintilla", "Chrome_RenderWidgetHostHWND"}:
            rect = win32gui.GetWindowRect(hwnd)
            try:
                _, pid = win32process.GetWindowThreadProcessId(hwnd)
                app = psutil.Process(pid).name()
            except Exception:
                app = "unknown"
            self._signal_text(rect[0], rect[1], rect[2] - rect[0], rect[3] - rect[1], app)
        else:
            self._signal_cleared()

    def _signal_text(self, x, y, w, h, app):
        if not self._in_text:
            self._in_text = True
            self.on_text_field(x, y, w, h, app)

    def _signal_cleared(self):
        if self._in_text:
            self._in_text = False
            self.on_cleared()


def foreground_loop(stop_event: threading.Event, loop, bus_broadcast_coro):
    """Vigila la ventana activa y notifica cambios de proceso/título por el bus."""
    if not HAS_WIN32:
        return
    while not stop_event.is_set():
        try:
            hwnd = win32gui.GetForegroundWindow()
            if hwnd and hwnd != last_foreground["hwnd"]:
                try:
                    _, pid = win32process.GetWindowThreadProcessId(hwnd)
                    proc = psutil.Process(pid).name()
                except Exception:
                    proc = "unknown"
                try: title = win32gui.GetWindowText(hwnd) or ""
                except Exception: title = ""
                if proc != last_foreground["process"] or title != last_foreground["title"]:
                    last_foreground.update({"process": proc, "title": title, "hwnd": hwnd})
                    asyncio.run_coroutine_threadsafe(
                        bus_broadcast_coro({"type": "foreground_app", "process": proc, "title": title}), loop
                    )
                else:
                    last_foreground["hwnd"] = hwnd
        except Exception as e:
            log.debug(f"foreground_loop: {e}")
        time.sleep(FOREGROUND_POLL_INTERVAL)


def cursor_loop(stop_event: threading.Event, loop, broadcast_coro):
    """Envía la posición real del cursor de Windows (~60 Hz) al canal de foco."""
    if not HAS_WIN32:
        return
    try:
        import win32api
    except Exception:
        return
    last = (None, None)
    while not stop_event.is_set():
        try:
            pos = win32api.GetCursorPos()
            if pos != last:
                last = pos
                asyncio.run_coroutine_threadsafe(broadcast_coro({"type": "cursor_pos", "x": pos[0], "y": pos[1]}), loop)
        except Exception:
            pass
        time.sleep(0.016)


def clipboard_loop(stop_event: threading.Event, loop, bus_broadcast_coro):
    """Sincroniza cambios del portapapeles del PC hacia los clientes móviles."""
    while not stop_event.is_set():
        try:
            text = get_pc_clipboard()
            if text and text != last_clip["text"]:
                last_clip["text"] = text
                asyncio.run_coroutine_threadsafe(bus_broadcast_coro({"type": "clipboard", "text": text}), loop)
        except Exception:
            pass
        time.sleep(0.6)
