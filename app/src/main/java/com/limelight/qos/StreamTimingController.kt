package com.limelight.qos

import com.limelight.qos.DesktopContentClassifier.ContentProfile

/**
 * StreamTimingController — Gestiona políticas de timing, jitter buffer y pacing de fotogramas.
 * Aplica SOLID delegando la toma de decisiones de cadencia de renderizado según el perfil de contenido.
 */
class StreamTimingController {

    private var activeProfile: ContentProfile = ContentProfile.BALANCED
    private var lastFrameTimeNs: Long = 0
    var averageFrameIntervalMs: Float = 16.6f
        private set

    fun updateContentProfile(profile: ContentProfile?) {
        if (profile != null) {
            this.activeProfile = profile
        }
    }

    /**
     * Retorna el retraso recomendado de jitter buffer en milisegundos.
     * En STATIC_TEXT se busca 0ms de retraso para interactividad inmediata.
     * En VIDEO se permite un pequeño buffer para suavidad impecable.
     */
    fun getRecommendedJitterBufferMs(): Int {
        return when (activeProfile) {
            ContentProfile.STATIC_TEXT -> 0 // Ultra-baja latencia para texto/código
            ContentProfile.SCROLLING -> 4 // Mínimo buffer para evitar micro-stutter en scroll
            ContentProfile.VIDEO -> 16 // Suavidad cinematográfica
            ContentProfile.BALANCED -> 8
        }
    }

    /**
     * Determina si se debe omitir el renderizado de un frame si llega tarde.
     */
    fun shouldDropLateFrame(frameAgeMs: Long): Boolean {
        return when (activeProfile) {
            ContentProfile.STATIC_TEXT -> frameAgeMs > 50
            ContentProfile.SCROLLING -> frameAgeMs > 25
            else -> frameAgeMs > 100
        }
    }

    /**
     * Registra la presentación de un fotograma para estadísticas de telemetría.
     */
    fun onFramePresented(frameTimestampNs: Long) {
        if (lastFrameTimeNs > 0) {
            val deltaNs = frameTimestampNs - lastFrameTimeNs
            val deltaMs = deltaNs / 1_000_000.0f
            averageFrameIntervalMs = (averageFrameIntervalMs * 0.9f) + (deltaMs * 0.1f)
        }
        lastFrameTimeNs = frameTimestampNs
    }
}
