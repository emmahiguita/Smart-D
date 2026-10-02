package com.limelight.binding.audio

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.audiofx.AudioEffect
import android.os.Build
import com.limelight.LimeLog
import com.limelight.nvstream.av.audio.AudioRenderer
import com.limelight.nvstream.jni.MoonBridge

class AndroidAudioRenderer(
    private val context: Context,
    private val enableAudioFx: Boolean
) : AudioRenderer {

    private var track: AudioTrack? = null

    // SmartDisplay: silencia la salida del audio del PC en el dispositivo sin
    // tocar el pipeline de Moonlight. Se usa al activar la voz (walkie-talkie)
    // para evitar que el altavoz alimente el micrófono y reenvíe eco al PC.
    @Volatile
    private var muted = false

    fun setMuted(muted: Boolean) {
        this.muted = muted
        val t = track
        if (t != null) {
            try {
                t.setVolume(if (muted) 0.0f else 1.0f)
            } catch (ignored: Exception) {}
        }
    }

    private fun createAudioTrack(channelConfig: Int, sampleRate: Int, bufferSize: Int, lowLatency: Boolean): AudioTrack {
        val attributesBuilder = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelConfig)
            .build()

        if (lowLatency) {
            attributesBuilder.setFlags(AudioAttributes.FLAG_LOW_LATENCY)
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val trackBuilder = AudioTrack.Builder()
                .setAudioFormat(format)
                .setAudioAttributes(attributesBuilder.build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferSize)

            if (lowLatency) {
                trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }

            trackBuilder.build()
        } else {
            AudioTrack(
                attributesBuilder.build(),
                format,
                bufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
        }
    }

    override fun setup(audioConfiguration: MoonBridge.AudioConfiguration, sampleRate: Int, samplesPerFrame: Int): Int {
        val channelConfig: Int = when (audioConfiguration.channelCount) {
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            4 -> AudioFormat.CHANNEL_OUT_QUAD
            6 -> AudioFormat.CHANNEL_OUT_5POINT1
            8 -> 0x000018fc // AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
            else -> {
                LimeLog.severe("Decoder returned unhandled channel count")
                return -1
            }
        }

        LimeLog.info("Audio channel config: " + String.format("0x%X", channelConfig))

        val bytesPerFrame = audioConfiguration.channelCount * samplesPerFrame * 2

        for (i in 0 until 4) {
            val lowLatency = when (i) {
                0, 1 -> true
                2, 3 -> false
                else -> throw IllegalStateException()
            }

            val bufferSize = when (i) {
                0, 2 -> bytesPerFrame * 2
                1, 3 -> {
                    val minBuf = Math.max(
                        AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT),
                        bytesPerFrame * 2
                    )
                    ((minBuf + (bytesPerFrame - 1)) / bytesPerFrame) * bytesPerFrame
                }
                else -> throw IllegalStateException()
            }

            if (AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC) != sampleRate && lowLatency) {
                continue
            }

            if (enableAudioFx && lowLatency) {
                continue
            }

            try {
                track = createAudioTrack(channelConfig, sampleRate, bufferSize, lowLatency)
                track?.play()

                LimeLog.info("Audio track configuration: $bufferSize $lowLatency")
                break
            } catch (e: Exception) {
                e.printStackTrace()
                try {
                    track?.release()
                    track = null
                } catch (ignored: Exception) {}
            }
        }

        val activeTrack = track ?: return -2

        if (muted) {
            try {
                activeTrack.setVolume(0.0f)
            } catch (ignored: Exception) {}
        }

        return 0
    }

    override fun playDecodedAudio(audioData: ShortArray) {
        if (MoonBridge.getPendingAudioDuration() < 250) {
            track?.write(audioData, 0, audioData.size)
        } else {
            LimeLog.info("Too much pending audio data: " + MoonBridge.getPendingAudioDuration() + " ms (Drop)")
        }
    }

    override fun start() {
        if (enableAudioFx) {
            val t = track ?: return
            val i = Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, t.audioSessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_GAME)
            }
            context.sendBroadcast(i)
        }
    }

    override fun stop() {
        if (enableAudioFx) {
            val t = track ?: return
            val i = Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, t.audioSessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
            }
            context.sendBroadcast(i)
        }
    }

    override fun cleanup() {
        val t = track ?: return
        t.pause()
        t.flush()
        t.release()
        track = null
    }
}
