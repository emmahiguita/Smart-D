"""
============================================================================
ARCHIVO: sd_motion.py
QUÉ HACE: Motor de interpolación exponencial de ventanas a ~60 FPS.
CÓMO FUNCIONA: Aplica suavizado `current += (target - current) * ease` en un
               hilo dedicado y transmite confirmaciones `window_moved` a ~10 Hz.
POR QUÉ EXISTE: Permite arrastrar y acoplar ventanas de Windows desde el móvil
                con fluidez sin saltos ni bloqueos del hilo de red.
============================================================================
"""

import asyncio
import ctypes
import threading
import time
from ctypes import wintypes
from sd_config import log
from sd_win_system import move_window


class WindowMotionEngine:
    """Motor de animación de ventanas a 60 FPS con easing exponencial."""

    TICK_S = 1 / 60
    EASE_DRAG = 0.65
    EASE_SNAP = 0.35
    STOP_THRESH = 0.5
    ACK_INTERVAL = 0.10

    def __init__(self, event_loop, broadcast_coro):
        self._loop = event_loop
        self._broadcast_coro = broadcast_coro
        self._states = {}
        self._lock = threading.Lock()
        self._running = False
        self._thread = None
        self._last_ack = {}

    def start(self):
        self._running = True
        self._thread = threading.Thread(target=self._run, daemon=True, name="WindowMotionEngine")
        self._thread.start()
        log.info("WindowMotionEngine iniciado (60 FPS)")

    def stop(self):
        self._running = False

    def set_target(self, hwnd: int, x: float, y: float, w: float, h: float, mode: str = "snap"):
        ease = self.EASE_DRAG if mode == "drag" else self.EASE_SNAP
        with self._lock:
            if hwnd not in self._states:
                try:
                    wr = wintypes.RECT()
                    ctypes.windll.user32.GetWindowRect(hwnd, ctypes.byref(wr))
                    cx, cy = float(wr.left), float(wr.top)
                    cw, ch = float(wr.right - wr.left), float(wr.bottom - wr.top)
                except Exception:
                    cx, cy, cw, ch = x, y, w, h
                self._states[hwnd] = {"cx": cx, "cy": cy, "cw": cw, "ch": ch,
                                      "tx": x, "ty": y, "tw": w, "th": h, "ease": ease, "active": True}
            else:
                s = self._states[hwnd]
                s["tx"], s["ty"], s["tw"], s["th"] = x, y, w, h
                s["ease"], s["active"] = ease, True

    def set_ease(self, hwnd: int, ease: float):
        with self._lock:
            if hwnd in self._states:
                self._states[hwnd]["ease"] = ease

    def cancel(self, hwnd: int):
        with self._lock:
            if hwnd in self._states:
                self._states[hwnd]["active"] = False

    def _run(self):
        while self._running:
            t0, now = time.perf_counter(), time.monotonic()
            with self._lock:
                hwnds = list(self._states.items())

            for hwnd, s in hwnds:
                if not s["active"]:
                    continue
                e = s["ease"]
                s["cx"] += (s["tx"] - s["cx"]) * e
                s["cy"] += (s["ty"] - s["cy"]) * e
                s["cw"] += (s["tw"] - s["cw"]) * e
                s["ch"] += (s["th"] - s["ch"]) * e

                if (abs(s["tx"] - s["cx"]) < self.STOP_THRESH and abs(s["ty"] - s["cy"]) < self.STOP_THRESH and
                        abs(s["tw"] - s["cw"]) < self.STOP_THRESH and abs(s["th"] - s["ch"]) < self.STOP_THRESH):
                    s["cx"], s["cy"], s["cw"], s["ch"] = s["tx"], s["ty"], s["tw"], s["th"]
                    s["active"] = False

                move_window(hwnd, int(s["cx"]), int(s["cy"]), int(s["cw"]), int(s["ch"]))

                if now - self._last_ack.get(hwnd, 0) >= self.ACK_INTERVAL:
                    self._last_ack[hwnd] = now
                    msg = {"type": "window_moved", "hwnd": hwnd,
                           "x": int(s["cx"]), "y": int(s["cy"]), "w": int(s["cw"]), "h": int(s["ch"])}
                    asyncio.run_coroutine_threadsafe(self._broadcast_coro(msg), self._loop)

            time.sleep(max(0.0, self.TICK_S - (time.perf_counter() - t0)))
