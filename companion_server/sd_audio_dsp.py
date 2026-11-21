"""
============================================================================
ARCHIVO: sd_audio_dsp.py
QUÉ HACE: Pipeline DSP en tiempo real para audio de micrófono móvil → PC.
CÓMO FUNCIONA: Encadena 7 etapas: High-Pass Filter (80 Hz), Supresión
               Espectral adaptativa, VAD, Noise Gate con histéresis, AEC NLMS,
               AGC y Peak Limiter.
POR QUÉ EXISTE: Elimina ruido ambiental, ecos y distorsiones antes de
                reproducir la voz del usuario en el equipo remoto.
============================================================================
"""

from sd_config import (
    AUDIO_AEC_MU,
    AUDIO_AEC_TAIL_MS,
    AUDIO_AGC_MAX_GAIN,
    AUDIO_AGC_TARGET,
    AUDIO_FFT_SIZE,
    AUDIO_FRAME_SIZE,
    AUDIO_GATE_HYST,
    AUDIO_HPF_CUTOFF,
    AUDIO_NOISE_FLOOR_ALPHA,
    AUDIO_NOISE_GATE,
    AUDIO_VAD_HANGOVER,
    AUDIO_VAD_THRESH,
    VOICE_SAMPLE_RATE,
    log,
)

try:
    import numpy as np
    HAS_NP = True
except ImportError:
    HAS_NP = False
    log.warning("numpy no instalado — procesamiento de audio DSP desactivado")

try:
    import scipy.signal as signal
    HAS_SCIPY = True
except ImportError:
    HAS_SCIPY = False


class AudioProcessor:
    """Pipeline DSP completo para voz móvil→PC (PCM 16-bit mono)."""

    def __init__(self, rate=VOICE_SAMPLE_RATE):
        self.rate = rate
        self.frame_size = AUDIO_FRAME_SIZE
        self.fft_size = AUDIO_FFT_SIZE
        self._init_hpf()

        self.noise_floor = np.ones(AUDIO_FFT_SIZE // 2 + 1, dtype=np.float32) * 1e-6
        self.noise_alpha = AUDIO_NOISE_FLOOR_ALPHA
        self.spectral_gate_db = -18
        self.vad_hangover = 0
        self.vad_active = False
        self.gate_open = False
        self.gate_threshold = AUDIO_NOISE_GATE
        self.gate_hysteresis = AUDIO_GATE_HYST
        self.agc_gain_db = 0.0
        self.agc_target = AUDIO_AGC_TARGET
        self.agc_max_gain = AUDIO_AGC_MAX_GAIN
        self.agc_attack, self.agc_release = 0.05, 0.01

        taps = int(AUDIO_AEC_TAIL_MS * rate / 1000)
        self.aec_coeffs = np.zeros(max(1, taps), dtype=np.float32)
        self.aec_buf = np.zeros(max(1, taps), dtype=np.float32)
        self.aec_mu = AUDIO_AEC_MU
        self.aec_enabled = taps > 0
        self.limiter_gain = 1.0

        log.info(f"AudioProcessor: {rate}Hz, HPF={AUDIO_HPF_CUTOFF}Hz, AEC_taps={taps}, VAD={AUDIO_VAD_THRESH}dBFS")

    def _init_hpf(self):
        norm_cut = AUDIO_HPF_CUTOFF / (self.rate / 2)
        if not (0 < norm_cut < 1):
            self._hpf_filter = lambda x: x
            return
        try:
            if HAS_SCIPY:
                self.hpf_sos = signal.butter(4, norm_cut, btype="high", output="sos")
                self.hpf_zi = np.zeros((self.hpf_sos.shape[0], 2), dtype=np.float32)
                self._hpf_filter = self._hpf_sos_fn
            else:
                w0 = 2 * np.pi * AUDIO_HPF_CUTOFF / self.rate
                alpha = np.sin(w0) / (2 * 0.707)
                b0, b1, b2 = (1 + np.cos(w0)) / 2, -(1 + np.cos(w0)), (1 + np.cos(w0)) / 2
                a0, a1, a2 = 1 + alpha, -2 * np.cos(w0), 1 - alpha
                self.hpf_b1 = np.array([b0 / a0, b1 / a0, b2 / a0], dtype=np.float32)
                self.hpf_a1 = np.array([1.0, a1 / a0, a2 / a0], dtype=np.float32)
                self.hpf_x1 = np.zeros(2, dtype=np.float32)
                self.hpf_y1 = np.zeros(2, dtype=np.float32)
                self._hpf_filter = self._hpf_iir_fn
        except Exception:
            self._hpf_filter = lambda x: x

    def _hpf_sos_fn(self, samples):
        out, self.hpf_zi = signal.sosfilt(self.hpf_sos, samples, zi=self.hpf_zi)
        return out.astype(np.float32)

    def _hpf_iir_fn(self, samples):
        out = np.zeros_like(samples)
        for i, x in enumerate(samples):
            y = (self.hpf_b1[0] * x + self.hpf_b1[1] * self.hpf_x1[0] + self.hpf_b1[2] * self.hpf_x1[1]
                 - self.hpf_a1[1] * self.hpf_y1[0] - self.hpf_a1[2] * self.hpf_y1[1])
            self.hpf_x1[1], self.hpf_x1[0] = self.hpf_x1[0], x
            self.hpf_y1[1], self.hpf_y1[0] = self.hpf_y1[0], y
            out[i] = y
        return out

    def _spectral_suppress(self, samples):
        if len(samples) < self.fft_size:
            return samples
        hop = self.fft_size // 2
        win = np.hanning(self.fft_size).astype(np.float32)
        out, denorm = np.zeros(len(samples), dtype=np.float32), np.zeros(len(samples), dtype=np.float32)
        for start in range(0, len(samples) - self.fft_size + 1, hop):
            spec = np.fft.rfft(samples[start:start + self.fft_size] * win)
            mag, phase = np.abs(spec).astype(np.float32), np.angle(spec)
            if 20 * np.log10(np.mean(mag) + 1e-10) < AUDIO_VAD_THRESH + 6:
                self.noise_floor = (1 - self.noise_alpha) * self.noise_floor + self.noise_alpha * mag
            mask = np.where(mag < self.noise_floor * (10 ** (self.spectral_gate_db / 20)), 0.15, 1.0)
            out[start:start + self.fft_size] += np.fft.irfft(mag * mask * np.exp(1j * phase)) * win
            denorm[start:start + self.fft_size] += win ** 2
        return out / np.maximum(denorm, 1e-10)

    def _vad(self, samples) -> bool:
        if 20 * np.log10(np.sqrt(np.mean(samples ** 2)) + 1e-10) > AUDIO_VAD_THRESH:
            self.vad_hangover, self.vad_active = AUDIO_VAD_HANGOVER, True
        elif self.vad_hangover > 0:
            self.vad_hangover -= 1
        else:
            self.vad_active = False
        return self.vad_active

    def _noise_gate(self, samples):
        db = 20 * np.log10(np.sqrt(np.mean(samples ** 2)) + 1e-10)
        if not self.gate_open and db > self.gate_threshold: self.gate_open = True
        elif self.gate_open and db < self.gate_threshold - self.gate_hysteresis: self.gate_open = False
        if self.gate_open:
            return samples
        fade = min(len(samples), int(self.rate * 0.005))
        out = np.zeros_like(samples)
        if fade > 0: out[:fade] = samples[:fade] * np.linspace(1, 0, fade, dtype=np.float32)
        return out

    def _aec_process(self, samples):
        if not self.aec_enabled or len(samples) == 0:
            return samples
        out = np.zeros_like(samples)
        for i, s in enumerate(samples):
            self.aec_buf[1:], self.aec_buf[0] = self.aec_buf[:-1], s
            err = s - np.dot(self.aec_coeffs, self.aec_buf)
            norm = np.dot(self.aec_buf, self.aec_buf) + 1e-10
            if norm > 1e-6: self.aec_coeffs += (self.aec_mu * err / norm) * self.aec_buf
            out[i] = np.clip(err, -32768, 32767)
        return (0.7 * out + 0.3 * samples if np.std(out) > 0.1 * np.std(samples) else out).astype(np.float32)

    def _agc(self, samples):
        if len(samples) == 0:
            return samples
        cur_db = 20 * np.log10(np.sqrt(np.mean(samples ** 2)) + 1e-10 / 32768.0)
        delta = np.clip(self.agc_target - cur_db, -6, self.agc_max_gain)
        alpha = self.agc_attack if delta > self.agc_gain_db else self.agc_release
        self.agc_gain_db += alpha * (delta - self.agc_gain_db)
        return np.clip(samples * (10 ** (self.agc_gain_db / 20)), -32768, 32767)

    def _peak_limiter(self, samples):
        peak = np.max(np.abs(samples)) + 1e-10
        if peak > 30000: self.limiter_gain += 0.3 * ((30000.0 / peak) - self.limiter_gain)
        else: self.limiter_gain += 0.05 * (1.0 - self.limiter_gain)
        self.limiter_gain = np.clip(self.limiter_gain, 0.1, 1.0)
        return np.clip(samples * self.limiter_gain, -32768, 32767).astype(np.int16)

    def process(self, raw_bytes: bytes) -> bytes:
        if not HAS_NP:
            return raw_bytes
        samples = np.frombuffer(raw_bytes, dtype=np.int16).astype(np.float32)
        if len(samples) == 0:
            return raw_bytes
        samples = self._noise_gate(self._spectral_suppress(self._hpf_filter(samples)))
        if not self._vad(samples) and not self.gate_open:
            return b"\x00" * len(raw_bytes)
        return self._peak_limiter(self._agc(self._aec_process(samples))).tobytes()
