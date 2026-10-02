package com.limelight.smartdisplay.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.widget.Toast
import com.limelight.LimeLog
import com.limelight.smartdisplay.connection.CompanionConfig

/**
 * ScreenCaptureManager — Single Responsibility Principle (SOLID)
 *
 * Responsable exclusivo de coordinar el permiso de captura de pantalla (MediaProjection)
 * y el inicio/detención del servicio [ScreenCaptureService].
 */
class ScreenCaptureManager(private val activity: Activity) {

    companion object {
        const val REQ_SCREEN_CAPTURE = 7778
    }

    /** Solicita al usuario el consentimiento para MediaProjection. */
    fun requestScreenCapture() {
        val mpMgr = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager ?: return
        try {
            val captureIntent = mpMgr.createScreenCaptureIntent()
            activity.startActivityForResult(captureIntent, REQ_SCREEN_CAPTURE)
        } catch (e: Exception) {
            LimeLog.warning("Error lanzando consentimiento MediaProjection: ${e.message}")
        }
    }

    /** Inicia el servicio de captura con el resultado concedido por el usuario. */
    fun onActivityResult(resultCode: Int, data: Intent?): Boolean {
        if (resultCode != Activity.RESULT_OK || data == null) {
            LimeLog.warning("Permiso MediaProjection denegado por el usuario")
            Toast.makeText(activity, "Captura de pantalla denegada", Toast.LENGTH_SHORT).show()
            return false
        }

        stopService()

        val config = CompanionConfig()
        val host = config.getHost()
        val token = config.getToken()

        if (host.isNullOrEmpty()) {
            LimeLog.warning("No se puede iniciar captura: sin IP del PC")
            Toast.makeText(activity, "Sin IP del PC — reconéctate al escritorio", Toast.LENGTH_SHORT).show()
            return false
        }

        try {
            val intent = Intent(activity, ScreenCaptureService::class.java).apply {
                putExtra("code", resultCode)
                putExtra("data", data)
                putExtra("ws_host", host)
                putExtra("token", token)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activity.startForegroundService(intent)
            } else {
                activity.startService(intent)
            }
            LimeLog.info("ScreenCaptureService iniciado -> $host")
            return true
        } catch (e: Exception) {
            LimeLog.warning("Error iniciando ScreenCaptureService: ${e.message}")
            return false
        }
    }

    /** Detiene el servicio de captura en ejecución. */
    fun stopService() {
        try {
            activity.stopService(Intent(activity, ScreenCaptureService::class.java))
        } catch (e: Exception) {
            LimeLog.warning("Error deteniendo ScreenCaptureService: ${e.message}")
        }
    }
}
