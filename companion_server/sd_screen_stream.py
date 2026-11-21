"""
============================================================================
ARCHIVO: sd_screen_stream.py
QUÉ HACE: Captura el escritorio de Windows en tiempo real y envía frames JPEG
          con la flecha del cursor dibujada por el WebSocket Bus (47991).
CÓMO FUNCIONA: Adjunta el hilo de captura mediante `OpenInputDesktop`, usa
               `PIL.ImageGrab.grab()`, dibuja el cursor con `GetCursorPos` y
               escala a 1280x720 antes de enviar bytes binarios al cliente.
POR QUÉ EXISTE: Provee proyección directa en iOS/Android sin depender de Sunshine.
============================================================================
"""

import asyncio
import ctypes
import io
import os
from ctypes import wintypes
from PIL import Image, ImageDraw, ImageGrab
from sd_config import last_clip, last_foreground, last_pointer, log
from sd_win_system import get_system_stats


def draw_cursor_arrow(shot, user32):
    """Dibuja la flecha del cursor real de Windows sobre el frame capturado."""
    if user32 is None:
        return
    try:
        hdesk = user32.OpenInputDesktop(0, False, 0x01FF)
        if hdesk:
            user32.SetThreadDesktop(hdesk)
        pt = wintypes.POINT()
        ok = user32.GetCursorPos(ctypes.byref(pt))
        if hdesk:
            user32.CloseDesktop(hdesk)
        if not ok:
            return
        s = max(1.0, shot.size[1] / 1080.0) * 1.25
        cx, cy = pt.x, pt.y
        if not (0 <= cx < shot.size[0] and 0 <= cy < shot.size[1]):
            return
        base = [(0, 0), (0, 26), (6, 20), (12, 32), (18, 29), (11, 18), (20, 18)]
        draw = ImageDraw.Draw(shot)
        draw.polygon([(cx + (x + 2) * s, cy + (y + 2) * s) for x, y in base], fill=(0, 0, 0))
        draw.polygon([(cx + x * s, cy + y * s) for x, y in base], fill=(255, 255, 255), outline=(0, 0, 0), width=2)
    except Exception as e:
        log.debug(f"draw_cursor_arrow: {e}")


async def stream_desktop_loop(websocket, bus_clients: set):
    """Captura el escritorio continuamente y envía frames JPEG por WebSocket."""
    user32 = ctypes.windll.user32 if os.name == "nt" else None
    seq = 0
    log.info(f"Iniciando streaming de escritorio en vivo hacia {websocket.remote_address}")

    while getattr(websocket, "_sd_streaming", False) and websocket in bus_clients:
        try:
            seq += 1

            def _grab_jpeg(frame_idx: int) -> bytes:
                shot = None
                if user32:
                    try:
                        hdesk = user32.OpenInputDesktop(0, False, 0x01FF)
                        if hdesk:
                            user32.SetThreadDesktop(hdesk)
                            try:
                                shot = ImageGrab.grab()
                            finally:
                                user32.CloseDesktop(hdesk)
                    except Exception:
                        pass
                if shot is None:
                    try:
                        shot = ImageGrab.grab()
                    except Exception:
                        pass
                if shot is None:
                    shot = Image.new("RGB", (1280, 720), (14, 17, 22))
                    draw = ImageDraw.Draw(shot)
                    stats = get_system_stats()
                    draw.rectangle([40, 40, 1240, 680], outline=(59, 85, 120), width=2)
                    draw.text((80, 90), "SMARTDISPLAY AI | SESION REMOTA EN VIVO", fill=(236, 239, 242))
                    draw.text((80, 150), f"CPU: {stats.get('cpu', 0)}% | RAM: {stats.get('ram', 0)}% | Frame #{frame_idx}", fill=(148, 168, 196))
                    draw.text((80, 210), f"Ventana: {last_foreground.get('process') or 'Explorer'}", fill=(180, 190, 202))
                    if last_clip.get("text"):
                        draw.text((80, 270), f"Portapapeles: {last_clip['text'][:70]}", fill=(99, 158, 130))
                    cx, cy = last_pointer.get("x", 640), last_pointer.get("y", 360)
                    draw.ellipse([cx - 12, cy - 12, cx + 12, cy + 12], outline=(236, 239, 242), width=3)
                else:
                    draw_cursor_arrow(shot, user32)
                    if shot.size != (1280, 720):
                        shot.thumbnail((1280, 720), Image.Resampling.BILINEAR)
                buf = io.BytesIO()
                shot.save(buf, format="JPEG", quality=75, optimize=False)
                return buf.getvalue()

            await websocket.send(await asyncio.to_thread(_grab_jpeg, seq))
            await asyncio.sleep(0.04)
        except Exception as ex:
            log.warning(f"Error en stream_desktop_loop: {ex}")
            break
