"""
============================================================================
ARCHIVO: sd_bus_handler.py
QUÉ HACE: Enruta mensajes JSON del SmartDisplayBus (puerto 47991) y mantiene
          el registro del agente hacia un Hub Electron opcional.
CÓMO FUNCIONA: Despacha comandos de entrada, ventanas, portapapeles, shell y
               streaming de escritorio sobre conexiones WebSocket autenticadas.
POR QUÉ EXISTE: Aísla el protocolo del bus de comandos de la lógica Win32 y DSP.
============================================================================
"""

import asyncio
import ctypes
import json
import os
import platform
import time
from ctypes import wintypes
from sd_config import last_clip, last_foreground, log, ws_auth_ok
from sd_file_http import handle_files_offer
from sd_screen_stream import stream_desktop_loop
from sd_win_input import (
    handle_double_click,
    handle_mouse_button,
    handle_normalized_pointer,
    handle_scroll,
    restore_pc_cursor,
    send_special_key,
    send_unicode_char,
    set_pc_clipboard,
)
from sd_win_system import (
    close_window,
    do_power,
    focus_window,
    get_system_stats,
    list_windows,
    minimize_window,
    restore_pc_audio,
    run_gradle,
    snap_rect,
)

bus_clients = set()
motion_engine_ref = {"engine": None}


async def bus_broadcast(msg: dict):
    """Envía un evento JSON a todos los clientes conectados al bus 47991."""
    if not bus_clients:
        return
    payload = json.dumps(msg)
    dead = set()
    for ws in list(bus_clients):
        try:
            await ws.send(payload)
        except Exception:
            dead.add(ws)
    bus_clients.difference_update(dead)


async def _dispatch_bus_msg(websocket, data: dict):
    """Procesa un único mensaje JSON recibido del cliente móvil."""
    t = data.get("type")
    eng = motion_engine_ref["engine"]

    if t == "files_offer":
        asyncio.create_task(handle_files_offer(data))
    elif t == "clipboard":
        text = data.get("text", "")
        last_clip["text"] = text
        set_pc_clipboard(text)
    elif t == "power":
        do_power(data.get("action", ""))
    elif t == "get_stats":
        await websocket.send(json.dumps({"type": "stats", **await asyncio.to_thread(get_system_stats)}))
    elif t == "get_windows":
        await websocket.send(json.dumps({"type": "windows", "windows": await asyncio.to_thread(list_windows)}))
    elif t == "dev_gradle":
        action = data.get("action", "")
        await websocket.send(json.dumps({"type": "dev_result", "action": action, "running": True}))
        res = await asyncio.to_thread(run_gradle, action)
        await websocket.send(json.dumps({"type": "dev_result", "action": action, "ok": res["ok"], "tail": res["tail"]}))
    elif t == "focus_window":
        focus_window(int(data.get("hwnd", 0)))
    elif t == "close_window":
        close_window(int(data.get("hwnd", 0)))
    elif t == "minimize_window":
        minimize_window(int(data.get("hwnd", 0)))
    elif t == "restore_audio":
        await asyncio.to_thread(restore_pc_audio)
    elif t == "move_window" and eng:
        eng.set_target(int(data.get("hwnd", 0)), float(data.get("x", 0)), float(data.get("y", 0)),
                       float(data.get("w", 100)), float(data.get("h", 100)), data.get("mode", "snap"))
    elif t == "window_drag_end" and eng:
        eng.set_ease(int(data.get("hwnd", 0)), eng.EASE_SNAP)
    elif t == "snap_window" and eng:
        rx, ry, rw, rh = snap_rect(data.get("zone", "maximize"))
        eng.set_target(int(data.get("hwnd", 0)), rx, ry, rw, rh, "snap")
    elif t == "swap_windows" and eng:
        for e in data.get("windows", []):
            eng.set_target(int(e["hwnd"]), float(e["x"]), float(e["y"]), float(e["w"]), float(e["h"]), "snap")
    elif t == "cancel_window_motion" and eng:
        eng.cancel(int(data.get("hwnd", 0)))
    elif t == "window_at_point" and os.name == "nt":
        pt = wintypes.POINT(int(data.get("x", 0)), int(data.get("y", 0)))
        hwnd = ctypes.windll.user32.WindowFromPoint(pt)
        await websocket.send(json.dumps({"type": "window_at_point_result", "hwnd": int(hwnd), "x": pt.x, "y": pt.y}))
    elif t == "exec_cmd":
        cmd = data.get("cmd", "")
        try:
            proc = await asyncio.create_subprocess_shell(cmd, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
            out, err = await asyncio.wait_for(proc.communicate(), timeout=15.0)
            txt = (out or err or b"").decode("utf-8", errors="replace")
            await websocket.send(json.dumps({"type": "cmd_result", "cmd": cmd, "exit_code": proc.returncode, "output": txt}))
        except Exception as ex:
            await websocket.send(json.dumps({"type": "cmd_result", "cmd": cmd, "exit_code": -1, "output": str(ex)}))
    elif t == "start_stream":
        if not getattr(websocket, "_sd_streaming", False):
            websocket._sd_streaming = True
            asyncio.create_task(stream_desktop_loop(websocket, bus_clients))
    elif t == "stop_stream":
        websocket._sd_streaming = False
    elif t == "input_move":
        handle_normalized_pointer(data, click=False)
    elif t == "input_click":
        handle_normalized_pointer(data, click=True)
    elif t == "input_double_click":
        handle_double_click(data)
    elif t == "input_mouse_down":
        handle_mouse_button(data, down=True)
    elif t == "input_mouse_up":
        handle_mouse_button(data, down=False)
    elif t == "input_text":
        txt = data.get("text", "")
        if txt:
            last_clip["text"] = txt
            set_pc_clipboard(txt)
            send_special_key("ctrl_v")
    elif t == "input_char":
        send_unicode_char(data.get("char", ""))
    elif t == "input_key":
        send_special_key(data.get("key", ""))
    elif t == "input_scroll":
        handle_scroll(data)


async def bus_handler(websocket, path="/"):
    """Manejador principal de conexiones WebSocket en el puerto 47991."""
    if not ws_auth_ok(websocket):
        log.warning("Bus: conexión rechazada (PIN inválido)")
        await websocket.close(1008, "unauthorized"); return

    bus_clients.add(websocket)
    log.info(f"Bus conectado: {websocket.remote_address}")
    websocket._sd_streaming = True
    asyncio.create_task(stream_desktop_loop(websocket, bus_clients))

    if last_foreground["process"]:
        try:
            await websocket.send(json.dumps({"type": "foreground_app", **last_foreground}))
        except Exception:
            pass

    try:
        async for message in websocket:
            try:
                data = json.loads(message)
            except Exception:
                continue
            await _dispatch_bus_msg(websocket, data)
    except Exception as exc:
        log.warning(f"Bus handler error: {exc}")
    finally:
        websocket._sd_streaming = False
        bus_clients.discard(websocket)
        log.info("Bus desconectado")
        if not bus_clients:
            restore_pc_cursor()
            asyncio.create_task(asyncio.to_thread(restore_pc_audio))

