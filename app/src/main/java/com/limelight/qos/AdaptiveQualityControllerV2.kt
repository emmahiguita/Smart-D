package com.limelight.qos

import com.limelight.LimeLog
import com.limelight.nvstream.jni.MoonBridge
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * AdaptiveQualityControllerV2 — Controlador adaptativo de calidad multi-factor (SOLID).
 * Gestiona degradación/recuperación de calidad basándose en estado de red, estado térmico,
 * perfil de contenido y nivel de zoom (Zoom Quality Boost).
 */
class AdaptiveQualityControllerV2(
    private val originalWidth: Int,
    private val originalHeight: Int,
    private val originalBitrate: Int,
    val isEnabled: Boolean
) {

    fun interface OnQualityAdjustmentListener {
        fun onQualityAdjustmentRequested(
            newLevel: Int,
            targetWidth: Int,
            targetHeight: Int,
            targetBitrate: Int,
            reason: String
        )
    }

    var currentLevel = 0
        private set

    private var poorCount = 0
    private var windowStartTime: Long = 0
    private var lastOkayTime: Long = 0
    private var isThermalThrottled = false
    private var currentZoomScale = 1.0f

    var listener: OnQualityAdjustmentListener? = null

    fun notifyZoomScale(scale: Float) {
        this.currentZoomScale = scale
    }

    fun notifyThermalState(isThrottled: Boolean) {
        this.isThermalThrottled = isThrottled
        if (isThrottled && currentLevel < ADAPTIVE_RESOLUTION_SCALE.size - 1) {
            triggerAdjustment(currentLevel + 1, "Estrés Térmico del Dispositivo")
        }
    }

    /**
     * Procesa eventos de estado de conexión reportados por MoonBridge.
     */
    fun onConnectionStatusChanged(connectionStatus: Int, now: Long) {
        if (!isEnabled) return

        if (connectionStatus == MoonBridge.CONN_STATUS_POOR) {
            if (now - windowStartTime > ADAPTIVE_QUALITY_WINDOW_MS) {
                poorCount = 0
                windowStartTime = now
            }
            poorCount++
            lastOkayTime = 0

            if (poorCount >= ADAPTIVE_QUALITY_POOR_THRESHOLD && currentLevel < ADAPTIVE_RESOLUTION_SCALE.size - 1) {
                poorCount = 0
                windowStartTime = now
                triggerAdjustment(currentLevel + 1, "Degradación de Red (Packet Loss / Jitter)")
            }
        } else if (connectionStatus == MoonBridge.CONN_STATUS_OKAY) {
            poorCount = 0
            windowStartTime = now

            if (currentLevel > 0 && !isThermalThrottled) {
                if (lastOkayTime == 0L) {
                    lastOkayTime = now
                } else if (now - lastOkayTime >= RECOVERY_INTERVAL_MS) {
                    lastOkayTime = 0
                    triggerAdjustment(currentLevel - 1, "Recuperación de Red Estable")
                }
            }
        }
    }

    private fun triggerAdjustment(targetLevel: Int, reason: String) {
        this.currentLevel = targetLevel
        val res = getResolutionForLevel(targetLevel)
        val bitrate = getBitrateForLevel(targetLevel)

        LimeLog.info("AdaptiveQualityV2: Ajuste a nivel $targetLevel (${res[0]}x${res[1]} @ $bitrate kbps) por $reason")

        listener?.onQualityAdjustmentRequested(targetLevel, res[0], res[1], bitrate, reason)
    }

    /**
     * Calcula la resolución adaptativa para un nivel dado, preservando el aspect ratio y alineando a par.
     * Si el usuario tiene Zoom > 1.4x (Smart Zoom Quality Boost), se intenta preservar mayor resolución.
     */
    fun getResolutionForLevel(level: Int): IntArray {
        val idx = min(level, ADAPTIVE_RESOLUTION_SCALE.size - 1)
        var scale = ADAPTIVE_RESOLUTION_SCALE[idx]

        // Zoom Quality Boost: si el usuario está en zoom alto, evitamos degradar resolución por debajo del 75%
        if (currentZoomScale > 1.4f && scale < 0.75f) {
            scale = 0.75f
        }

        var w = max((originalWidth * scale).toInt() and 1.inv(), 640)
        val h = max((originalHeight * scale).toInt() and 1.inv(), 360)

        val targetRatio = originalWidth.toFloat() / originalHeight
        val actualRatio = w.toFloat() / h
        if (abs(actualRatio - targetRatio) > 0.01f) {
            w = (h * targetRatio).toInt() and 1.inv()
        }
        return intArrayOf(w, h)
    }

    /**
     * Calcula el bitrate adaptativo para un nivel dado.
     */
    fun getBitrateForLevel(level: Int): Int {
        val idx = min(level, ADAPTIVE_BITRATE_STEPS.size - 1)
        return max(originalBitrate * ADAPTIVE_BITRATE_STEPS[idx] / 100, 5000)
    }

    companion object {
        private val ADAPTIVE_RESOLUTION_SCALE = floatArrayOf(1.0f, 0.75f, 0.5f)
        private val ADAPTIVE_BITRATE_STEPS = intArrayOf(100, 60, 35)
        private const val ADAPTIVE_QUALITY_WINDOW_MS = 10_000L
        private const val ADAPTIVE_QUALITY_POOR_THRESHOLD = 3
        private const val RECOVERY_INTERVAL_MS = 15_000L
    }
}
