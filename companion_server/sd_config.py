"""
============================================================================
ARCHIVO: sd_config.py
QUÉ HACE: Centraliza puertos, constantes DSP, logging, identidad UUID del PC
          y autenticación por PIN (Bearer en WebSocket y prefijo SHA-256 en UDP).
CÓMO FUNCIONA: Carga `config.json` y `agent-identity.json` junto al script,
               exponiendo `_ws_auth_ok()` compatible con websockets 10..16+.
POR QUÉ EXISTE: Aplica Principio de Responsabilidad Única (SRP) y evita usar
                `log` antes de inicializarlo.
============================================================================
"""

import json
import logging
import os
import socket
import time

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
)
log = logging.getLogger("SmartDisplay")

# ── Puertos y parámetros de red ──────────────────────────────────────────────
BUS_PORT = 47991            # WebSocket de comandos, video y archivos
VOICE_PORT = 48999          # UDP bidireccional de voz (PCM 16-bit mono)
FILE_PORT = 8766            # HTTP explorador de archivos del PC
VOICE_SAMPLE_RATE = 48000   # Debe coincidir con Android/iOS (48 kHz)
POLL_INTERVAL = 0.15
FOREGROUND_POLL_INTERVAL = 0.3

# ── Parámetros del pipeline DSP de audio ─────────────────────────────────────
AUDIO_HPF_CUTOFF = 80
AUDIO_NOISE_GATE = -42
AUDIO_GATE_HYST = 3
AUDIO_AGC_TARGET = -16
AUDIO_AGC_MAX_GAIN = 12
AUDIO_VAD_THRESH = -36
AUDIO_VAD_HANGOVER = 12
AUDIO_AEC_TAIL_MS = 50
AUDIO_AEC_MU = 0.05
AUDIO_FRAME_MS = 10
AUDIO_FRAME_SIZE = int(VOICE_SAMPLE_RATE * AUDIO_FRAME_MS / 1000)
AUDIO_FFT_SIZE = 512
AUDIO_NOISE_FLOOR_ALPHA = 0.02

TEXT_CONTROL_TYPES = {50004, 50020, 50025}
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DOWNLOAD_DIR = os.path.join(os.path.expanduser("~"), "Downloads", "SmartDisplay")
CONFIG_PATH = os.path.join(BASE_DIR, "config.json")
AGENT_IDENTITY_PATH = os.path.join(BASE_DIR, "agent-identity.json")

# Estado mutable de autenticación (se inicializa en main)
auth_state = {"pin": "", "voice_prefix": b""}
last_foreground = {"process": None, "title": None, "hwnd": None}
last_pointer = {"x": 640, "y": 360}
last_clip = {"text": None}


def load_or_create_identity() -> dict:
    """Carga o genera la identidad UUID persistente del PC en agent-identity.json."""
    if os.path.exists(AGENT_IDENTITY_PATH):
        try:
            with open(AGENT_IDENTITY_PATH, "r", encoding="utf-8") as f:
                data = json.load(f)
            log.info(f"Identidad cargada: {data.get('name', '?')} ({data.get('agentId', '?')[:8]}…)")
            return data
        except Exception as e:
            log.warning(f"No se pudo cargar identidad: {e}")

    import uuid
    data = {
        "agentId": str(uuid.uuid4()),
        "name": os.environ.get("COMPUTERNAME", os.environ.get("HOSTNAME", "PC-Unknown")),
        "hostname": socket.gethostname(),
        "os": f"Windows {os.environ.get('OS', 'Unknown')}" if os.name == "nt" else os.uname().sysname,
        "createdAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    }
    try:
        with open(AGENT_IDENTITY_PATH, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2)
    except Exception as e:
        log.warning(f"No se pudo guardar identidad: {e}")
    return data


def load_pin() -> str:
    """Carga el PIN de config.json si existe (vacío = auth desactivada)."""
    try:
        if os.path.exists(CONFIG_PATH):
            with open(CONFIG_PATH, "r", encoding="utf-8") as f:
                return str(json.load(f).get("pin", "")).strip()
    except Exception as e:
        log.warning(f"No se pudo leer config.json ({e}); auth desactivada")
    return ""


def save_pin(pin: str):
    """Guarda el PIN en config.json para habilitar autenticación."""
    with open(CONFIG_PATH, "w", encoding="utf-8") as f:
        json.dump({"pin": pin}, f, indent=2)
    print(f"PIN guardado en {CONFIG_PATH}: {pin}")


def ws_auth_ok(websocket) -> bool:
    """Valida Authorization: Bearer <PIN> compatible con websockets 10..16+."""
    pin = auth_state["pin"]
    if not pin:
        return True
    try:
        req = getattr(websocket, "request", None)
        headers = getattr(req, "headers", None) if req is not None else None
        if headers is None:
            headers = getattr(websocket, "request_headers", None)
        return headers is not None and headers.get("Authorization", "") == f"Bearer {pin}"
    except Exception:
        return False
