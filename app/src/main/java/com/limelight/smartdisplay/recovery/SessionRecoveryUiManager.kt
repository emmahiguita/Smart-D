package com.limelight.smartdisplay.recovery

import android.app.Activity
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.limelight.R
import com.limelight.utils.Dialog

/**
 * SessionRecoveryUiManager — Single Responsibility Principle (SRP)
 *
 * Encapsula la gestión visual de los estados de reconexión y Wake-on-LAN:
 * - Desenfoque suave (Blur RenderEffect en Android 12+) sobre la Surface de video.
 * - Animaciones fade-in / fade-out del overlay.
 * - Mensajes de progreso y diálogos de fallo de sesión.
 */
class SessionRecoveryUiManager(
    private val activity: Activity,
    private val streamView: View?,
    private val reconnectOverlayView: View?,
    private val reconnectStatusText: TextView?
) {

    fun showReconnectingUI(attempt: Int, maxAttempts: Int) {
        activity.runOnUiThread {
            reconnectOverlayView?.apply {
                alpha = 0f
                visibility = View.VISIBLE
                animate().alpha(1f).setDuration(300).start()
            }
            reconnectStatusText?.text = activity.getString(R.string.reconnect_status, attempt, maxAttempts)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                streamView?.setRenderEffect(RenderEffect.createBlurEffect(15f, 15f, Shader.TileMode.CLAMP))
            }
        }
    }

    fun hideReconnectingUI() {
        activity.runOnUiThread {
            reconnectOverlayView?.animate()?.alpha(0f)?.setDuration(200)?.withEndAction {
                reconnectOverlayView.visibility = View.GONE
            }?.start()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                streamView?.setRenderEffect(null)
            }

            // Reconexión exitosa: asegurar pantalla encendida
            activity.window?.addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }

    fun showWakeOnLanUI(elapsedSeconds: Int, maxSeconds: Int) {
        activity.runOnUiThread {
            reconnectOverlayView?.apply {
                alpha = 0f
                visibility = View.VISIBLE
                animate().alpha(1f).setDuration(300).start()
            }
            reconnectStatusText?.text = activity.getString(R.string.wol_waking_status, elapsedSeconds, maxSeconds)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                streamView?.setRenderEffect(RenderEffect.createBlurEffect(15f, 15f, Shader.TileMode.CLAMP))
            }
        }
    }

    fun hideWakeOnLanUI() {
        hideReconnectingUI()
    }

    fun onReconnectFailed(onDialogDismissed: () -> Unit) {
        activity.runOnUiThread {
            reconnectOverlayView?.visibility = View.GONE
            Dialog.displayDialog(
                activity,
                activity.resources.getString(R.string.wol_conn_terminated_title),
                activity.getString(R.string.reconnect_failed),
                true
            )
            onDialogDismissed()
        }
    }
}
