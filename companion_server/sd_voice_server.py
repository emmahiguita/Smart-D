"""
============================================================================
ARCHIVO: sd_voice_server.py
QUÉ HACE: Servidor UDP bidireccional de voz (puerto 48999) entre móvil y PC.
CÓMO FUNCIONA: Recibe paquetes PCM mono 48 kHz del móvil con Jitter Buffer
               adaptativo + Packet Loss Concealment (PLC) y devuelve el
               micrófono del PC mientras la llamada está activa.
POR QUÉ EXISTE: Desacopla la lógica de audio de baja latencia del servidor WS.
============================================================================
"""

import socket
import threading
import time
from sd_audio_dsp import AudioProcessor, HAS_NP
from sd_config import AUDIO_FRAME_SIZE, VOICE_PORT, VOICE_SAMPLE_RATE, auth_state, log


def voice_loop(stop_event: threading.Event):
    """Recibe PCM del móvil por UDP 48999 y reproduce con DSP + retorno de micrófono."""
    try:
        import sounddevice as sd
    except Exception as e:
        log.warning(f"Voz móvil→PC desactivada (instala 'sounddevice'): {e}")
        return

    import numpy as np
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("0.0.0.0", VOICE_PORT))
    sock.settimeout(0.5)

    dsp = AudioProcessor() if HAS_NP else None
    buf_lock, audio_buf = threading.Lock(), bytearray()
    max_buf = int(VOICE_SAMPLE_RATE * 2 * 0.08)
    prebuf = int(VOICE_SAMPLE_RATE * 2 * 0.02)
    started, plc_fade = False, 0.0
    last_good = np.zeros(AUDIO_FRAME_SIZE, dtype=np.int16)
    packets, underruns, last_log = 0, 0, time.monotonic()

    def audio_cb(outdata, frames, _ti, _st):
        nonlocal started, last_good, plc_fade, underruns
        needed = frames * 2
        with buf_lock:
            if not started:
                if len(audio_buf) >= prebuf:
                    started = True
                else:
                    outdata.fill(0); return
            if len(audio_buf) >= needed:
                chunk = bytes(audio_buf[:needed]); del audio_buf[:needed]
                if dsp: chunk = dsp.process(chunk)
                s = np.frombuffer(chunk, dtype=np.int16).copy()
                last_good = s[-AUDIO_FRAME_SIZE:] if len(s) >= AUDIO_FRAME_SIZE else s
                plc_fade = 0.0
                outdata[:len(s)] = s.reshape(-1, 1)
                if len(s) < frames: outdata[len(s):] = 0
            else:
                underruns += 1
                plc_fade = min(1.0, plc_fade + 0.1)
                fade = np.linspace(1.0 - plc_fade, 0, frames, dtype=np.float32)
                outdata[:, 0] = (last_good[:frames].astype(np.float32) * fade).astype(np.int16)
                started = False

    try:
        stream = sd.OutputStream(samplerate=VOICE_SAMPLE_RATE, channels=1, dtype="int16",
                                 callback=audio_cb, blocksize=AUDIO_FRAME_SIZE)
        stream.start()
    except Exception as e:
        log.error(f"Voz móvil→PC: fallo al abrir salida a {VOICE_SAMPLE_RATE} Hz: {e}")
        sock.close(); return

    log.info(f"Voz móvil→PC activa en UDP {VOICE_PORT} | DSP={'SÍ' if dsp else 'NO'}")
    peer_lock, peer_state = threading.Lock(), {"addr": None, "last": 0.0}
    ret_holder = {"stream": None}

    def _mic_cb(indata, _f, _ti, _st):
        with peer_lock:
            addr, fresh = peer_state["addr"], (time.monotonic() - peer_state["last"]) < 6.0
        if not addr or not fresh:
            return
        try:
            payload = auth_state["voice_prefix"] + indata.copy().tobytes()
            sock.sendto(payload, addr)
        except Exception:
            pass

    def _ensure_return():
        if ret_holder["stream"] is not None:
            return
        try:
            s = sd.InputStream(samplerate=VOICE_SAMPLE_RATE, channels=1, dtype="int16",
                               callback=_mic_cb, blocksize=AUDIO_FRAME_SIZE)
            s.start(); ret_holder["stream"] = s
            log.info("Voz PC→móvil: micrófono del PC activado.")
        except Exception as e:
            log.warning(f"Voz PC→móvil desactivada: {e}")

    def _maybe_stop_return():
        s = ret_holder["stream"]
        if s is None: return
        with peer_lock:
            if (time.monotonic() - peer_state["last"]) < 6.0: return
        try: s.stop(); s.close()
        except Exception: pass
        ret_holder["stream"] = None

    try:
        while not stop_event.is_set():
            try: data, addr = sock.recvfrom(65536)
            except socket.timeout: _maybe_stop_return(); continue
            except Exception: continue
            if not data: continue
            vp = auth_state["voice_prefix"]
            if vp:
                if len(data) <= 8 or data[:8] != vp: continue
                data = data[8:]
            packets += 1
            with peer_lock:
                peer_state["addr"], peer_state["last"] = addr, time.monotonic()
            _ensure_return()
            with buf_lock:
                audio_buf.extend(data)
                if len(audio_buf) > max_buf:
                    del audio_buf[:len(audio_buf) - max_buf]
                    started = True
            if time.monotonic() - last_log > 30:
                log.info(f"Voz: {packets} pkts, underruns={underruns}")
                last_log = time.monotonic()
    finally:
        for s in (stream, ret_holder["stream"]):
            if s:
                try: s.stop(); s.close()
                except Exception: pass
        sock.close()
