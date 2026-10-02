package com.limelight.smartdisplay.power

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import com.limelight.LimeLog

/**
 * SessionPowerManager — Single Responsibility Principle (SOLID)
 *
 * Responsable exclusivamente de la gestión de bloqueos de energía y conectividad
 * (CPU WakeLock, Wi-Fi High Performance y Wi-Fi Low Latency) durante la sesión.
 */
class SessionPowerManager(context: Context) {

    private val wifiMgr = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val powerMgr = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private var highPerfWifiLock: WifiManager.WifiLock? = null
    private var lowLatencyWifiLock: WifiManager.WifiLock? = null
    private var cpuWakeLock: PowerManager.WakeLock? = null

    /** Adquiere los bloqueos necesarios para garantizar streaming sin latencia ni cortes. */
    fun acquireLocks() {
        // Wi-Fi High Performance Lock
        try {
            wifiMgr?.let {
                highPerfWifiLock = it.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Moonlight High Perf Lock")
                highPerfWifiLock?.setReferenceCounted(false)
                highPerfWifiLock?.acquire()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    lowLatencyWifiLock = it.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "Moonlight Low Latency Lock")
                    lowLatencyWifiLock?.setReferenceCounted(false)
                    lowLatencyWifiLock?.acquire()
                }
            }
        } catch (e: SecurityException) {
            LimeLog.warning("Error adquiriendo Wi-Fi Lock: ${e.message}")
        }

        // CPU WakeLock (mantiene conexión activa incluso con pantalla apagada)
        try {
            powerMgr?.let {
                cpuWakeLock = it.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmartDisplay:CPUKeepAlive")
                cpuWakeLock?.setReferenceCounted(false)
                cpuWakeLock?.acquire()
                LimeLog.info("SmartDisplay: CPU WakeLock adquirido")
            }
        } catch (e: Exception) {
            LimeLog.warning("Error adquiriendo CPU WakeLock: ${e.message}")
        }
    }

    /** Libera limpiamente todos los locks al finalizar la sesión. */
    fun releaseLocks() {
        try {
            if (cpuWakeLock?.isHeld == true) {
                cpuWakeLock?.release()
                LimeLog.info("SmartDisplay: CPU WakeLock liberado")
            }
        } catch (_: Exception) {}
        cpuWakeLock = null

        try {
            if (lowLatencyWifiLock?.isHeld == true) {
                lowLatencyWifiLock?.release()
            }
        } catch (_: Exception) {}
        lowLatencyWifiLock = null

        try {
            if (highPerfWifiLock?.isHeld == true) {
                highPerfWifiLock?.release()
            }
        } catch (_: Exception) {}
        highPerfWifiLock = null
    }
}
