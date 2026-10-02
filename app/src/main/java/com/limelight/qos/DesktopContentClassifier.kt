package com.limelight.qos

import android.os.Handler
import android.os.Looper
import com.limelight.LimeLog
import java.util.concurrent.CopyOnWriteArrayList

/**
 * DesktopContentClassifier — Clasificador de contenido de escritorio en tiempo real.
 * Aplica el principio de Responsabilidad Única (SRP) analizando la cadencia de interacción,
 * zoom y eventos de entrada para determinar el perfil óptimo de visualización.
 */
class DesktopContentClassifier {

    enum class ContentProfile(
        val displayName: String,
        val targetFps: Int,
        val targetJitterFrames: Int
    ) {
        STATIC_TEXT("Texto Estático / Código", 15, 0),
        SCROLLING("Desplazamiento Rápido", 60, 1),
        VIDEO("Video / Movimiento Continuo", 60, 2),
        BALANCED("Escritorio General", 30, 1)
    }

    fun interface OnProfileChangeListener {
        fun onProfileChanged(newProfile: ContentProfile, oldProfile: ContentProfile)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<OnProfileChangeListener>()

    var currentProfile: ContentProfile = ContentProfile.BALANCED
        private set

    private var isKeyboardActive = false
    private var currentZoomScale = 1.0f
    private var lastInteractionTime: Long = 0
    private var activeInteractionType = INTERACTION_IDLE

    private val evaluateIdleRunnable = Runnable { evaluateProfile() }

    init {
        scheduleNextEvaluation(STATIC_TEXT_ENTRY_DELAY_MS)
    }

    fun addListener(listener: OnProfileChangeListener?) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: OnProfileChangeListener?) {
        listeners.remove(listener)
    }

    fun notifyKeyboardState(active: Boolean) {
        this.isKeyboardActive = active
        onInteraction(INTERACTION_TYPING)
    }

    fun notifyZoomScale(scale: Float) {
        this.currentZoomScale = scale
        evaluateProfile()
    }

    fun onInteraction(type: Int) {
        this.activeInteractionType = type
        this.lastInteractionTime = System.currentTimeMillis()

        if (type == INTERACTION_SCROLL) {
            setProfile(ContentProfile.SCROLLING)
            scheduleNextEvaluation(SCROLL_TIMEOUT_MS)
        } else if (type == INTERACTION_TYPING || isKeyboardActive) {
            setProfile(ContentProfile.STATIC_TEXT)
            scheduleNextEvaluation(STATIC_TEXT_ENTRY_DELAY_MS)
        } else {
            scheduleNextEvaluation(STATIC_TEXT_ENTRY_DELAY_MS)
        }
    }

    private fun scheduleNextEvaluation(delayMs: Long) {
        mainHandler.removeCallbacks(evaluateIdleRunnable)
        mainHandler.postDelayed(evaluateIdleRunnable, delayMs)
    }

    private fun evaluateProfile() {
        val elapsed = System.currentTimeMillis() - lastInteractionTime

        if (isKeyboardActive || currentZoomScale >= 1.4f) {
            setProfile(ContentProfile.STATIC_TEXT)
        } else if (elapsed > STATIC_TEXT_ENTRY_DELAY_MS) {
            setProfile(ContentProfile.STATIC_TEXT)
        } else if (elapsed > SCROLL_TIMEOUT_MS && currentProfile == ContentProfile.SCROLLING) {
            setProfile(ContentProfile.BALANCED)
        }
    }

    private fun setProfile(newProfile: ContentProfile) {
        if (this.currentProfile != newProfile) {
            val old = this.currentProfile
            this.currentProfile = newProfile
            LimeLog.info("DesktopContentClassifier: Perfil cambiado de $old a $newProfile")
            for (l in listeners) {
                try {
                    l.onProfileChanged(newProfile, old)
                } catch (e: Exception) {
                    LimeLog.warning("Error notificando cambio de perfil: ${e.message}")
                }
            }
        }
    }

    fun release() {
        mainHandler.removeCallbacksAndMessages(null)
        listeners.clear()
    }

    companion object {
        const val INTERACTION_IDLE = 0
        const val INTERACTION_TYPING = 1
        const val INTERACTION_SCROLL = 2
        const val INTERACTION_MOUSE = 3

        private const val SCROLL_TIMEOUT_MS = 600L
        private const val STATIC_TEXT_ENTRY_DELAY_MS = 1500L
    }
}
