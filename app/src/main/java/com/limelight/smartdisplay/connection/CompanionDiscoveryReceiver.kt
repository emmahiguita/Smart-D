package com.limelight.smartdisplay.connection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.limelight.LimeLog
import com.limelight.smartdisplay.CompanionConfig

/**
 * CompanionDiscoveryReceiver — Single Responsibility Principle (SOLID)
 *
 * Escucha anuncios emitidos por el companion del PC por ADB ("START_SCREEN_CAPTURE", "STOP_SCREEN_CAPTURE"),
 * aprendiendo y guardando automáticamente la IP y Token de sesión en [CompanionConfig].
 */
class CompanionDiscoveryReceiver(
    private val context: Context,
    private val onStopRequested: () -> Unit
) {
    companion object {
        const val ACTION_START = "com.limelight.smartdisplay.START_SCREEN_CAPTURE"
        const val ACTION_STOP  = "com.limelight.smartdisplay.STOP_SCREEN_CAPTURE"
    }

    private var receiver: BroadcastReceiver? = null

    /** Registra el BroadcastReceiver exportado para recibir los intents del PC. */
    fun register() {
        if (receiver != null) return
        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent == null) return
                when (intent.action) {
                    ACTION_START -> {
                        val ts = intent.getStringExtra("TAILSCALE_IP")
                        val pc = intent.getStringExtra("PC_IP")
                        val lan = intent.getStringExtra("LAN_IP")
                        val token = intent.getStringExtra("TOKEN")

                        val chosen = if (isUsableIp(ts)) ts else (if (isUsableIp(pc)) pc else (if (isUsableIp(lan)) lan else null))
                        val config = CompanionConfig()
                        if (chosen != null) {
                            config.setHost(chosen)
                            LimeLog.info("Companion host aprendido del PC: $chosen")
                        }
                        if (!token.isNullOrEmpty() && token != "null") {
                            config.setToken(token)
                            LimeLog.info("Token de sesión guardado")
                        }
                    }
                    ACTION_STOP -> {
                        LimeLog.info("Deteniendo captura por broadcast del PC")
                        onStopRequested()
                    }
                }
            }
        }
        val filter = IntentFilter(ACTION_START).apply {
            addAction(ACTION_STOP)
        }
        ContextCompat.registerReceiver(context, receiver!!, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    /** Desregistra el receiver de forma segura. */
    fun unregister() {
        receiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: Exception) {}
            receiver = null
        }
    }

    private fun isUsableIp(s: String?): Boolean =
        !s.isNullOrEmpty() && s != "null"
}
