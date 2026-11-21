#!/usr/bin/env python3
"""
============================================================================
ARCHIVO: companion_server.py
QUÉ HACE: Punto de entrada principal del Companion Server de SmartDisplay AI
          para Windows (WebSockets 8765/47991, HTTP 8766, UDP Voz 48999).
CÓMO FUNCIONA: Inicializa configuración, hilos de monitoreo, motor de ventanas
               60 FPS, servidores WebSocket y el icono de bandeja (System Tray).
POR QUÉ EXISTE: Orquesta todos los módulos manteniendo un archivo limpio < 200 líneas.
============================================================================
"""

import argparse
import asyncio
import atexit
import hashlib
import json
import os
import random
import threading
import time
import pystray
from PIL import Image, ImageDraw

from sd_bus_handler import bus_broadcast, bus_handler, motion_engine_ref
from sd_hub_agent import hub_agent_loop
from sd_config import BUS_PORT, DOWNLOAD_DIR, auth_state, load_or_create_identity, load_pin, log, save_pin, ws_auth_ok
from sd_file_http import file_http_loop
from sd_focus_monitors import FocusMonitor, clipboard_loop, cursor_loop, foreground_loop
from sd_motion import WindowMotionEngine
from sd_voice_server import voice_loop
from sd_win_input import restore_pc_cursor

connected_clients = set()
loop_ref = {"loop": None}


async def focus_handler(websocket, path="/"):
    """Maneja clientes suscritos al canal de foco de texto y posición del cursor (8765)."""
    if not ws_auth_ok(websocket):
        await websocket.close(1008, "unauthorized"); return
    connected_clients.add(websocket)
    log.info(f"Cliente conectado (foco): {websocket.remote_address}")
    try:
        async for _ in websocket:
            pass
    except Exception:
        pass
    finally:
        connected_clients.discard(websocket)
        log.info(f"Cliente desconectado (foco): {websocket.remote_address}")


async def broadcast(msg: dict):
    """Envía eventos JSON a todos los clientes del canal de foco."""
    if not connected_clients:
        return
    payload, dead = json.dumps(msg), set()
    for ws in list(connected_clients):
        try:
            await ws.send(payload)
        except Exception:
            dead.add(ws)
    connected_clients.difference_update(dead)


async def ping_loop():
    """Envía keepalive cada 15s para detectar desconexiones silenciosas."""
    while True:
        await asyncio.sleep(15)
        await broadcast({"type": "ping", "ts": int(time.time())})


def on_text_field(x, y, w, h, app):
    if loop_ref["loop"]:
        asyncio.run_coroutine_threadsafe(
            broadcast({"type": "focus_changed", "is_text_field": True,
                       "rect": {"x": x, "y": y, "w": w, "h": h}, "app": app}),
            loop_ref["loop"],
        )


def on_cleared():
    if loop_ref["loop"]:
        asyncio.run_coroutine_threadsafe(broadcast({"type": "focus_cleared"}), loop_ref["loop"])


async def main(host: str, port: int, hub_url=None):
    """Inicia servidores WebSocket, monitores de sistema, audio UDP y HTTP."""
    import websockets

    pin = load_pin()
    auth_state["pin"] = pin
    auth_state["voice_prefix"] = hashlib.sha256(pin.encode("utf-8")).digest()[:8] if pin else b""
    log.info(f"PIN activo: {'SÍ' if pin else 'NO (auth desactivada)'}")
    log.info(f"SmartDisplay Companion Server iniciando en ws://{host}:{port}")

    identity = load_or_create_identity()
    engine = WindowMotionEngine(asyncio.get_event_loop(), bus_broadcast)
    engine.start()
    motion_engine_ref["engine"] = engine

    monitor = FocusMonitor(on_text_field, on_cleared)
    monitor.start()
    asyncio.create_task(ping_loop())
    if hub_url:
        asyncio.create_task(hub_agent_loop(hub_url, identity))

    stop_ev = threading.Event()
    ev_loop = asyncio.get_event_loop()
    for target, args in (
        (voice_loop, (stop_ev,)),
        (clipboard_loop, (stop_ev, ev_loop, bus_broadcast)),
        (foreground_loop, (stop_ev, ev_loop, bus_broadcast)),
        (cursor_loop, (stop_ev, ev_loop, broadcast)),
        (file_http_loop, (stop_ev,)),
    ):
        threading.Thread(target=target, args=args, daemon=True).start()

    async with websockets.serve(focus_handler, host, port), websockets.serve(bus_handler, host, BUS_PORT):
        log.info(f"Foco: ws://{host}:{port} | Bus: ws://{host}:{BUS_PORT} (descargas → {DOWNLOAD_DIR})")
        try:
            await asyncio.Future()
        finally:
            stop_ev.set()
            monitor.stop()
            engine.stop()


def _create_tray_image():
    img = Image.new("RGB", (64, 64), "#22D3EE")
    ImageDraw.Draw(img).rectangle([16, 16, 48, 48], fill="#8B5CF6")
    return img


def _on_open_gui(_icon, _item):
    if os.name != "nt":
        return
    try:
        import ctypes, subprocess, win32gui
        hwnd = win32gui.FindWindow(None, "SmartDisplay AI")
        if hwnd:
            ctypes.windll.user32.ShowWindow(hwnd, 9)
            ctypes.windll.user32.SetForegroundWindow(hwnd)
        else:
            root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
            bat = os.path.join(root, "Escritorio", "Iniciar_SmartDisplay.bat")
            if os.path.exists(bat):
                subprocess.Popen([bat], cwd=os.path.dirname(bat), creationflags=subprocess.CREATE_NEW_CONSOLE)
    except Exception as e:
        log.warning(f"Error al abrir interfaz: {e}")


atexit.register(restore_pc_cursor)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="SmartDisplay Companion Server")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--hub-url", default=None)
    parser.add_argument("--gen-pin", action="store_true")
    parser.add_argument("--set-pin", metavar="PIN")
    args = parser.parse_args()

    if args.gen_pin:
        save_pin(f"{random.randint(0, 999999):06d}"); raise SystemExit(0)
    if args.set_pin:
        save_pin(str(args.set_pin).strip()); raise SystemExit(0)

    loop = asyncio.new_event_loop()
    loop_ref["loop"] = loop

    def _run_loop():
        asyncio.set_event_loop(loop)
        try:
            loop.run_until_complete(main(args.host, args.port, args.hub_url))
        except Exception as e:
            log.error(f"Error en servidor asyncio: {e}")

    threading.Thread(target=_run_loop, daemon=True).start()

    def _on_quit(icon_obj, _item):
        icon_obj.stop()
        loop.call_soon_threadsafe(loop.stop)
        os._exit(0)

    menu = pystray.Menu(
        pystray.MenuItem("SmartDisplay Companion", None, enabled=False),
        pystray.MenuItem("Abrir Panel de Control", _on_open_gui, default=True),
        pystray.MenuItem("Cerrar servidor", _on_quit),
    )
    pystray.Icon("SmartDisplay", _create_tray_image(), "SmartDisplay Companion", menu).run()
