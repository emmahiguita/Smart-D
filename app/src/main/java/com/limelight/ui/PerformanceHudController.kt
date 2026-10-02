package com.limelight.ui

import android.app.Activity
import android.os.SystemClock
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.TextView
import android.widget.Toast
import com.limelight.binding.video.PerfOverlayListener
import com.limelight.preferences.PreferenceConfiguration

/**
 * PerformanceHudController — Encapsula el HUD de estadísticas de rendimiento:
 *  - Implementa [PerfOverlayListener] para recibir métricas del decodificador de video
 *  - Throttle de actualizaciones UI (1 segundo / cambio de texto) para no saturar setText()
 *  - Animaciones de entrada/salida suaves con PathInterpolator
 *  - Manejo de transiciones PiP y guardado de preferencias
 */
class PerformanceHudController(
    private val activity: Activity,
    private val overlayView: TextView?
) : PerfOverlayListener {

    private var lastPerfText: String = ""
    private var lastPerfUpdateMs: Long = 0L

    fun setup(enablePerfOverlay: Boolean) {
        overlayView?.visibility = if (enablePerfOverlay) View.VISIBLE else View.GONE
    }

    override fun onPerfUpdate(text: String) {
        if (overlayView == null) return
        val now = SystemClock.elapsedRealtime()
        if (text == lastPerfText || now - lastPerfUpdateMs < 1000L) {
            return
        }
        lastPerfText = text
        lastPerfUpdateMs = now
        activity.runOnUiThread {
            overlayView.text = text
        }
    }

    fun toggle(prefConfig: PreferenceConfiguration?) {
        if (overlayView == null || prefConfig == null) return
        val show = overlayView.visibility != View.VISIBLE
        prefConfig.enablePerfOverlay = show
        val dy = 16f * activity.resources.displayMetrics.density

        if (show) {
            overlayView.text = "Midiendo rendimiento…"
            overlayView.alpha = 0f
            overlayView.translationY = -dy
            overlayView.visibility = View.VISIBLE
            overlayView.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(260)
                .setInterpolator(PathInterpolator(0.05f, 0.7f, 0.1f, 1f))
                .start()
        } else {
            overlayView.animate()
                .alpha(0f)
                .translationY(-dy)
                .setDuration(180)
                .setInterpolator(PathInterpolator(0.3f, 0f, 0.8f, 0.15f))
                .withEndAction { overlayView.visibility = View.GONE }
                .start()
        }

        Toast.makeText(
            activity,
            if (show) "HUD de rendimiento: activado" else "HUD de rendimiento: desactivado",
            Toast.LENGTH_SHORT
        ).show()
    }

    fun onPipModeChanged(isInPip: Boolean, enablePerfOverlay: Boolean) {
        if (overlayView == null) return
        if (isInPip) {
            overlayView.visibility = View.GONE
        } else if (enablePerfOverlay) {
            overlayView.visibility = View.VISIBLE
        }
    }
}
