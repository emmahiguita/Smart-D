"""
============================================================================
ARCHIVO: sd_win_system.py
QUÉ HACE: Provee telemetría (CPU/RAM), gestión de ventanas Win32, comandos
          de energía, restauración de audio y tareas Gradle del proyecto.
CÓMO FUNCIONA: Utiliza ctypes sobre `kernel32`, `user32` y `PowrProf` para
               evitar dependencias pesadas y operar de forma determinista.
POR QUÉ EXISTE: Separa las operaciones de sistema operativo del servidor WS.
============================================================================
"""

import ctypes
import os
import subprocess
import time
from ctypes import wintypes
from sd_config import BASE_DIR, log


def ram_percent() -> int:
    """Devuelve % de RAM en uso mediante GlobalMemoryStatusEx."""
    try:
        class MEMORYSTATUSEX(ctypes.Structure):
            _fields_ = [
                ("dwLength", ctypes.c_ulong), ("dwMemoryLoad", ctypes.c_ulong),
                ("ullTotalPhys", ctypes.c_ulonglong), ("ullAvailPhys", ctypes.c_ulonglong),
                ("ullTotalPageFile", ctypes.c_ulonglong), ("ullAvailPageFile", ctypes.c_ulonglong),
                ("ullTotalVirtual", ctypes.c_ulonglong), ("ullAvailVirtual", ctypes.c_ulonglong),
                ("ullAvailExtendedVirtual", ctypes.c_ulonglong),
            ]
        stat = MEMORYSTATUSEX()
        stat.dwLength = ctypes.sizeof(MEMORYSTATUSEX)
        ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(stat))
        return int(stat.dwMemoryLoad)
    except Exception:
        return -1


def cpu_percent() -> int:
    """Devuelve % de CPU comparando GetSystemTimes en una ventana de 250ms."""
    try:
        class FILETIME(ctypes.Structure):
            _fields_ = [("low", ctypes.c_uint32), ("high", ctypes.c_uint32)]

        def sample():
            idle, kernel, user = FILETIME(), FILETIME(), FILETIME()
            ctypes.windll.kernel32.GetSystemTimes(ctypes.byref(idle), ctypes.byref(kernel), ctypes.byref(user))
            to64 = lambda ft: (ft.high << 32) | ft.low
            return to64(idle), to64(kernel), to64(user)

        i0, k0, u0 = sample(); time.sleep(0.25); i1, k1, u1 = sample()
        total = (k1 - k0) + (u1 - u0)
        return max(0, min(100, int(round((1 - (i1 - i0) / total) * 100)))) if total > 0 else 0
    except Exception:
        return -1


def get_system_stats() -> dict:
    return {"cpu": cpu_percent(), "ram": ram_percent()} if os.name == "nt" else {"cpu": -1, "ram": -1}


def do_power(action: str):
    """Ejecuta acciones de energía (suspend/restart/shutdown) en Windows."""
    if os.name != "nt":
        return
    try:
        if action == "shutdown": os.system("shutdown /s /t 0")
        elif action == "restart": os.system("shutdown /r /t 0")
        elif action == "suspend": ctypes.windll.PowrProf.SetSuspendState(0, 1, 0)
        else: log.warning(f"Acción de energía desconocida: {action}")
    except Exception as e:
        log.warning(f"No se pudo ejecutar '{action}': {e}")


def run_gradle(action: str) -> dict:
    """Ejecuta una de las tareas permitidas (sdClean/sdBuild/sdRun)."""
    task = {"clean": "sdClean", "build": "sdBuild", "run": "sdRun"}.get(action)
    if not task:
        return {"ok": False, "tail": f"accion invalida: {action}"}
    root = os.path.dirname(BASE_DIR)
    gradlew = os.path.join(root, "gradlew.bat" if os.name == "nt" else "gradlew")
    if not os.path.exists(gradlew):
        return {"ok": False, "tail": f"no se encontro gradlew en {root}"}
    try:
        p = subprocess.run([gradlew, task], cwd=root, capture_output=True, text=True, timeout=1800)
        out = ((p.stdout or "") + "\n" + (p.stderr or "")).strip()
        return {"ok": p.returncode == 0, "tail": out[-600:]}
    except Exception as e:
        return {"ok": False, "tail": str(e)}


def list_windows() -> list:
    """Lista las ventanas visibles de la barra de tareas con su hwnd, pid y rect."""
    if os.name != "nt":
        return []
    try:
        u = ctypes.windll.user32
        result = []
        EnumProc = ctypes.WINFUNCTYPE(ctypes.c_bool, wintypes.HWND, wintypes.LPARAM)

        def cb(hwnd, _lp):
            if not u.IsWindowVisible(hwnd): return True
            length = u.GetWindowTextLengthW(hwnd)
            if length == 0: return True
            ex = u.GetWindowLongW(hwnd, -20)
            if (ex & 0x00000080) and not (ex & 0x00040000): return True
            buf = ctypes.create_unicode_buffer(length + 1)
            u.GetWindowTextW(hwnd, buf, length + 1)
            if not buf.value: return True
            pid = wintypes.DWORD()
            u.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
            wr = wintypes.RECT(); u.GetWindowRect(hwnd, ctypes.byref(wr))
            result.append({
                "hwnd": int(hwnd), "pid": int(pid.value), "title": buf.value,
                "minimized": bool(u.IsIconic(hwnd)),
                "rect": {"x": int(wr.left), "y": int(wr.top), "w": int(wr.right - wr.left), "h": int(wr.bottom - wr.top)},
            })
            return True

        u.EnumWindows(EnumProc(cb), 0)
        return result
    except Exception as e:
        log.warning(f"No se pudo listar ventanas: {e}"); return []


def focus_window(hwnd: int):
    if os.name == "nt" and hwnd:
        try:
            u = ctypes.windll.user32; u.ShowWindow(hwnd, 9); u.SetForegroundWindow(hwnd)
        except Exception as e:
            log.warning(f"focus_window error: {e}")


def close_window(hwnd: int):
    if os.name == "nt" and hwnd:
        try: ctypes.windll.user32.PostMessageW(hwnd, 0x0010, 0, 0)
        except Exception as e: log.warning(f"close_window error: {e}")


def minimize_window(hwnd: int):
    if os.name == "nt" and hwnd:
        try: ctypes.windll.user32.ShowWindow(hwnd, 6)
        except Exception as e: log.warning(f"minimize_window error: {e}")


def move_window(hwnd: int, x: int, y: int, w: int, h: int):
    """Mueve y redimensiona una ventana sin robar foco ni cambiar z-order."""
    if os.name == "nt" and hwnd:
        try: ctypes.windll.user32.SetWindowPos(hwnd, 0, int(x), int(y), int(w), int(h), 0x0014)
        except Exception as e: log.debug(f"move_window error: {e}")


def snap_rect(zone: str) -> tuple:
    """Calcula coordenadas (x, y, w, h) de snap según resolución del monitor primario."""
    try: mw, mh = ctypes.windll.user32.GetSystemMetrics(0), ctypes.windll.user32.GetSystemMetrics(1)
    except Exception: mw, mh = 1920, 1080
    hw, hh = mw // 2, mh // 2
    zones = {
        "left_half": (0, 0, hw, mh), "right_half": (hw, 0, hw, mh),
        "top_half": (0, 0, mw, hh), "bottom_half": (0, hh, mw, hh),
        "top_left_q": (0, 0, hw, hh), "top_right_q": (hw, 0, hw, hh),
        "bottom_left_q": (0, hh, hw, hh), "bottom_right_q": (hw, hh, hw, hh),
        "maximize": (0, 0, mw, mh), "restore": (mw // 4, mh // 4, hw, hh),
    }
    return zones.get(zone, (0, 0, mw, mh))


def restore_pc_audio():
    """Fuerza la re-enumeración de endpoints de audio de Windows."""
    try:
        ps = (
            'Add-Type -TypeDefinition @"using System;using System.Runtime.InteropServices;'
            'public class AudioRestore{[DllImport("winmm.dll")]public static extern int waveOutGetNumDevs();}"@;'
            '[AudioRestore]::waveOutGetNumDevs()|Out-Null;exit 0'
        )
        subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command", ps],
                       capture_output=True, timeout=8, check=False)
        log.info("Audio del PC restaurado")
    except Exception as e:
        log.warning(f"No se pudo restaurar el audio del PC: {e}")
