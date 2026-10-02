package com.limelight.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.limelight.R
import kotlin.math.abs

/**
 * Centraliza las notificaciones de estado de SmartDisplay (equipo en línea,
 * emparejamiento requerido, conexión perdida y transferencia de archivos).
 * Independiente de la notificación de servicio en primer plano que usa la
 * captura de pantalla.
 */
object NotificationHelper {

    private const val CHANNEL_ID = "smartdisplay_status"

    // IDs base por tipo (para no pisarse entre sí).
    private const val ID_ONLINE = 2001
    private const val ID_PAIRING = 2002
    private const val ID_LOST = 2003
    private const val ID_TRANSFER = 2004

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(NotificationManager::class.java)
            if (mgr != null && mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notif_channel_status),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = context.getString(R.string.notif_channel_status_desc)
                }
                mgr.createNotificationChannel(channel)
            }
        }
    }

    private fun post(context: Context, id: Int, title: String, text: String) {
        ensureChannel(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_computer)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
        try {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        } catch (ignored: SecurityException) {
            // El usuario no concedió POST_NOTIFICATIONS; se ignora silenciosamente.
        }
    }

    @JvmStatic
    fun notifyPcOnline(context: Context, pcName: String?) {
        post(
            context, ID_ONLINE + safeOffset(pcName),
            context.getString(R.string.notif_pc_online_title),
            context.getString(R.string.notif_pc_online_text, pcName)
        )
    }

    @JvmStatic
    fun notifyPairingRequired(context: Context, pcName: String?) {
        post(
            context, ID_PAIRING + safeOffset(pcName),
            context.getString(R.string.notif_pairing_title),
            context.getString(R.string.notif_pairing_text, pcName)
        )
    }

    @JvmStatic
    fun notifyConnectionLost(context: Context, pcName: String?) {
        post(
            context, ID_LOST + safeOffset(pcName),
            context.getString(R.string.notif_lost_title),
            context.getString(R.string.notif_lost_text, pcName)
        )
    }

    @JvmStatic
    fun notifyFileTransfer(context: Context, fileName: String?, sent: Boolean) {
        post(
            context, ID_TRANSFER,
            context.getString(if (sent) R.string.notif_file_sent_title else R.string.notif_file_recv_title),
            context.getString(R.string.notif_file_text, fileName)
        )
    }

    /** Desplazamiento estable por nombre para que cada PC tenga su propia notificación. */
    private fun safeOffset(name: String?): Int {
        if (name == null) {
            return 0
        }
        return abs(name.hashCode() % 500)
    }
}
