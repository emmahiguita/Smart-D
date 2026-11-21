"""
============================================================================
ARCHIVO: sd_file_http.py
QUÉ HACE: Explorador HTTP del PC (puerto 8766) para listar/descargar archivos
          desde el móvil y receptor asíncrono de archivos enviados al PC.
CÓMO FUNCIONA: Levanta `ThreadingHTTPServer` en `0.0.0.0:8766` con validación
               de token `?token=<PIN>`, y descarga archivos ofertados por el
               bus hacia `~/Downloads/SmartDisplay`.
POR QUÉ EXISTE: Permite transferencia bidireccional sin cables ni servicios externos.
============================================================================
"""

import asyncio
import json
import os
import re
import string
import threading
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlparse
from sd_config import DOWNLOAD_DIR, FILE_PORT, auth_state, log


def _sanitize(name: str) -> str:
    return re.sub(r'[\\/:*?"<>|\r\n]', "_", name or "archivo")


def _unique_path(path: str) -> str:
    if not os.path.exists(path):
        return path
    base, ext = os.path.splitext(path)
    i = 1
    while os.path.exists(f"{base}({i}){ext}"):
        i += 1
    return f"{base}({i}){ext}"


def _download(url: str, dest: str):
    with urllib.request.urlopen(url, timeout=30) as r, open(dest, "wb") as out:
        while True:
            chunk = r.read(65536)
            if not chunk:
                break
            out.write(chunk)


async def handle_files_offer(data: dict):
    """Descarga archivos ofertados desde iOS/Android hacia DOWNLOAD_DIR."""
    files = data.get("files", [])
    if not files:
        return
    os.makedirs(DOWNLOAD_DIR, exist_ok=True)
    for f in files:
        url = f.get("url")
        if not url:
            continue
        dest = _unique_path(os.path.join(DOWNLOAD_DIR, _sanitize(f.get("name", "archivo"))))
        try:
            await asyncio.to_thread(_download, url, dest)
            log.info(f"Archivo recibido del móvil: {dest}")
        except Exception as e:
            log.warning(f"No se pudo descargar {url}: {e}")


def file_http_loop(stop_event: threading.Event):
    """Servidor HTTP en puerto 8766 para explorar y descargar archivos del PC."""
    def roots():
        home = os.path.expanduser("~")
        items = [{"name": "🏠 " + os.path.basename(home), "dir": True, "size": 0, "path": home}]
        if os.name == "nt":
            for d in string.ascii_uppercase:
                drive = f"{d}:\\"
                if os.path.exists(drive):
                    items.append({"name": drive, "dir": True, "size": 0, "path": drive})
        else:
            items.append({"name": "/", "dir": True, "size": 0, "path": "/"})
        return items

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *a): pass

        def do_GET(self):
            parsed = urlparse(self.path)
            q = parse_qs(parsed.query)
            pin = auth_state["pin"]
            if pin and q.get("token", [""])[0] != pin:
                self.send_response(401); self.end_headers(); return
            path = unquote(q.get("path", [""])[0])
            try:
                if parsed.path == "/list": self._do_list(path)
                elif parsed.path == "/get": self._do_get(path)
                else: self.send_response(404); self.end_headers()
            except Exception:
                try: self.send_response(500); self.end_headers()
                except Exception: pass

        def _do_list(self, path):
            if not path:
                entries, parent = roots(), ""
            else:
                entries = []
                try:
                    for name in sorted(os.listdir(path), key=str.lower):
                        full = os.path.join(path, name)
                        try:
                            is_dir = os.path.isdir(full)
                            size = 0 if is_dir else os.path.getsize(full)
                        except Exception:
                            is_dir, size = False, 0
                        entries.append({"name": name, "dir": is_dir, "size": size, "path": full})
                except Exception:
                    pass
                entries.sort(key=lambda e: (not e["dir"], e["name"].lower()))
                parent = os.path.dirname(path.rstrip("\\/")) if path != "/" else ""
            body = json.dumps({"path": path, "parent": parent, "entries": entries}).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _do_get(self, path):
            if not path or not os.path.isfile(path):
                self.send_response(404); self.end_headers(); return
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(os.path.getsize(path)))
            self.send_header("Content-Disposition", f'attachment; filename="{os.path.basename(path)}"')
            self.end_headers()
            with open(path, "rb") as f:
                while chunk := f.read(65536):
                    self.wfile.write(chunk)

    try:
        httpd = ThreadingHTTPServer(("0.0.0.0", FILE_PORT), Handler)
    except Exception as e:
        log.warning(f"Explorador de archivos del PC desactivado: {e}"); return
    log.info(f"Explorador de archivos del PC en http://0.0.0.0:{FILE_PORT} (list/get)")
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    stop_event.wait()
    try: httpd.shutdown()
    except Exception: pass
