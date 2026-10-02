package com.limelight.smartdisplay.voice

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.math.sqrt

/**
 * SmartDisplay AI - Capa 2: VoiceCaptureManager
 * Captura el audio del micrófono del dispositivo y lo envía vía UDP (Out-Of-Band)
 * al PC Host sin interferir con el motor principal de Moonlight.
 */
class VoiceCaptureManager @JvmOverloads constructor(
    private val hostIp: String,
    private val pin: String = ""
) {

    private var audioRecord: AudioRecord? = null
    @Volatile
    private var recording = false
    private var recordingThread: Thread? = null

    private var audioTrack: AudioTrack? = null
    @Volatile
    private var isPlaying = false
    private var playbackThread: Thread? = null

    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var gainControl: AutomaticGainControl? = null

    private var authPrefix = ByteArray(0)
    private var socket: DatagramSocket? = null
    private var hostAddress: InetAddress? = null

    /** Prefijo de auth para UDP: primeros 8 bytes del SHA-256 del PIN. */
    private fun computeAuthPrefix(): ByteArray {
        if (pin.isEmpty()) return ByteArray(0)
        return try {
            val full = MessageDigest.getInstance("SHA-256")
                .digest(pin.toByteArray(StandardCharsets.UTF_8))
            val prefix = ByteArray(8)
            System.arraycopy(full, 0, prefix, 0, 8)
            prefix
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo calcular el prefijo de auth de voz", e)
            ByteArray(0)
        }
    }

    fun checkPermissions(activity: Activity, requestCode: Int): Boolean {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                requestCode
            )
            return false
        }
        return true
    }

    fun start() {
        if (recording) return

        try {
            val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, minBufferSize * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "Fallo al inicializar AudioRecord")
                return
            }

            val sessionId = audioRecord!!.audioSessionId
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply {
                    enabled = true
                    Log.i(TAG, "NoiseSuppressor activado.")
                }
            } else {
                Log.w(TAG, "NoiseSuppressor NO soportado por este hardware.")
            }

            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply {
                    enabled = true
                    Log.i(TAG, "AcousticEchoCanceler activado.")
                }
            } else {
                Log.w(TAG, "AcousticEchoCanceler NO soportado por este hardware.")
            }

            if (AutomaticGainControl.isAvailable()) {
                gainControl = AutomaticGainControl.create(sessionId)?.apply {
                    enabled = false
                    Log.i(TAG, "AutomaticGainControl DESACTIVADO para evitar siseo.")
                }
            }

            socket = DatagramSocket()
            hostAddress = InetAddress.getByName(hostIp)
            authPrefix = computeAuthPrefix()

            audioRecord?.startRecording()
            recording = true

            recordingThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                captureAndSend(minBufferSize)
            }, "VoiceCaptureThread").apply { start() }

            startPlayback()

            Log.i(TAG, "Transmisión de voz iniciada hacia $hostIp:$HOST_PORT")
        } catch (se: SecurityException) {
            Log.e(TAG, "Falta permiso de micrófono", se)
        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando captura de voz", e)
            stop()
        }
    }

    private fun captureAndSend(bufferSize: Int) {
        val pre = authPrefix.size
        val chunkSize = 960
        val buffer = ByteArray(pre + chunkSize)
        if (pre > 0) System.arraycopy(authPrefix, 0, buffer, 0, pre)

        while (recording && !Thread.interrupted()) {
            val record = audioRecord ?: break
            val readSize = record.read(buffer, pre, chunkSize)
            if (readSize > 0) {
                var sum: Long = 0
                var i = pre
                while (i < pre + readSize - 1) {
                    val sample = ((buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)).toShort()
                    sum += sample.toLong() * sample
                    i += 2
                }
                val rms = sqrt(sum / (readSize / 2.0))

                if (rms < 150) {
                    for (k in pre until (pre + readSize)) {
                        buffer[k] = 0
                    }
                }

                try {
                    val packet = DatagramPacket(buffer, 0, pre + readSize, hostAddress, HOST_PORT)
                    socket?.send(packet)
                } catch (e: Exception) {
                    Log.e(TAG, "Error enviando paquete de voz", e)
                }
            }
        }
    }

    private fun startPlayback() {
        try {
            val minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AUDIO_FORMAT)
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AUDIO_FORMAT)
                        .build()
                )
                .setBufferSizeInBytes(minBufferSize * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            audioTrack?.play()
            isPlaying = true

            playbackThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                receiveAndPlay()
            }, "VoicePlaybackThread").apply { start() }
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo iniciar la reproducción del retorno de voz", e)
        }
    }

    private fun receiveAndPlay() {
        val pre = authPrefix.size
        val buf = ByteArray(2048)
        val packet = DatagramPacket(buf, buf.size)
        while (isPlaying && !Thread.interrupted()) {
            try {
                val currentSocket = socket ?: break
                currentSocket.receive(packet)
                val len = packet.length
                val track = audioTrack ?: break
                if (pre > 0) {
                    if (len <= pre) continue
                    track.write(packet.data, packet.offset + pre, len - pre)
                } else {
                    track.write(packet.data, packet.offset, len)
                }
            } catch (e: Exception) {
                if (isPlaying) {
                    Log.w(TAG, "Retorno de voz interrumpido: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        recording = false
        isPlaying = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (ignored: Exception) {}
        audioRecord = null

        try { noiseSuppressor?.release() } catch (ignored: Exception) {}
        noiseSuppressor = null
        try { echoCanceler?.release() } catch (ignored: Exception) {}
        echoCanceler = null
        try { gainControl?.release() } catch (ignored: Exception) {}
        gainControl = null

        try { socket?.close() } catch (ignored: Exception) {}
        socket = null

        recordingThread?.interrupt()
        recordingThread = null

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (ignored: Exception) {}
        audioTrack = null

        playbackThread?.interrupt()
        playbackThread = null

        Log.i(TAG, "Transmisión de voz detenida")
    }

    fun isRecording(): Boolean = recording

    companion object {
        private const val TAG = "SD_VoiceCapture"
        private const val SAMPLE_RATE = 48000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val HOST_PORT = 48999
    }
}
