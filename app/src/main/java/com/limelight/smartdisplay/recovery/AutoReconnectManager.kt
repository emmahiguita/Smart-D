package com.limelight.smartdisplay.recovery

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.limelight.LimeLog

/**
 * AutoReconnectManager — Capa de Recuperación (SOLID: Responsabilidad Única)
 *
 * Qué hace:
 *   Orquesta la reconexión automática del stream cuando ocurre una caída inesperada
 *   de red o del PC anfitrión, aplicando backoff exponencial y Wake-on-LAN si el PC
 *   se suspendió.
 *
 * Cómo funciona:
 *   - Todas las transiciones de estado corren en [mainHandler] para evitar condiciones de carrera
 *     entre hilos nativos de MoonBridge y callbacks de [NetworkMonitor].
 *   - El sondeo TCP previo a Wake-on-LAN se ejecuta en un hilo daemon (nunca en el hilo UI,
 *     evitando NetworkOnMainThreadException).
 *   - Tras reconectar, mantiene una ventana de gracia de 15s antes de reiniciar [retryCount].
 */
class AutoReconnectManager(
    context: Context,
    private val callback: ReconnectCallback
) : NetworkMonitor.NetworkChangeListener {

    companion object {
        private const val TAG = "SD_AutoReconnect"
        const val ML_ERROR_GRACEFUL_TERMINATION = 0

        private const val MAX_RETRY_COUNT = 8
        private const val BASE_RETRY_DELAY_MS = 2_500L
        private const val NETWORK_STABLE_DELAY_MS = 1_200L
        private const val MAX_RETRY_DELAY_MS = 30_000L
        private const val STABLE_CONNECTION_GRACE_MS = 15_000L
        private const val WOL_PROBE_TIMEOUT_MS = 1_500
    }

    private val networkMonitor = NetworkMonitor(context, this)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stableHandler = Handler(Looper.getMainLooper())

    @Volatile private var reconnecting = false
    @Volatile private var stopped = false
    private var networkReady = false
    private var retryCount = 0
    private var wolManager: WakeOnLanManager? = null
    private var wolAttempted = false
    private var stableConfirmToken = 0
    private var probeGeneration = 0

    /** Inicia la observación de conectividad del dispositivo. */
    fun start() {
        stopped = false
        networkReady = networkMonitor.isNetworkAvailable()
        networkMonitor.start()
    }

    /** Libera monitores, timers y procesos WoL al cerrar la sesión (evita procesos zombi). */
    fun stop() {
        stopped = true
        reconnecting = false
        probeGeneration++
        stableConfirmToken++
        networkMonitor.stop()
        cancelPendingReconnect()
        stableHandler.removeCallbacksAndMessages(null)
        wolManager?.cancel()
    }

    /**
     * Evalúa una terminación de sesión de Moonlight.
     * @return true si el manager asume la reconexión; false si es cierre voluntario o agotado.
     */
    fun onConnectionTerminated(errorCode: Int): Boolean {
        if (stopped || errorCode == ML_ERROR_GRACEFUL_TERMINATION) {
            LimeLog.info("$TAG: Terminación voluntaria o manager detenido – no reconectar")
            return false
        }
        if (retryCount >= MAX_RETRY_COUNT) {
            LimeLog.warning("$TAG: Máximo de intentos alcanzado")
            return false
        }

        reconnecting = true
        runOnMain {
            if (stopped) return@runOnMain
            stableConfirmToken++
            LimeLog.info("$TAG: Desconexión inesperada (code=$errorCode) – iniciando reconexión")
            networkReady = networkMonitor.isNetworkAvailable()
            callback.showReconnectingUI(retryCount + 1, MAX_RETRY_COUNT)
            if (networkReady) scheduleReconnectAttempt()
        }
        return true
    }

    /** Notifica que la conexión se restableció; espera ventana de gracia para confirmar estabilidad. */
    fun onConnectionEstablished() {
        runOnMain {
            if (stopped) return@runOnMain
            cancelPendingReconnect()
            callback.hideReconnectingUI()
            val myToken = ++stableConfirmToken
            stableHandler.postDelayed({
                if (!stopped && myToken == stableConfirmToken) {
                    reconnecting = false; retryCount = 0; wolAttempted = false
                }
            }, STABLE_CONNECTION_GRACE_MS)
        }
    }

    /** Registra un fallo en un intento de reconexión y programa el siguiente con backoff. */
    fun onReconnectAttemptFailed(errorCode: Int) {
        if (!reconnecting || stopped) return
        runOnMain {
            if (!reconnecting || stopped) return@runOnMain
            stableConfirmToken++; retryCount++
            if (retryCount >= MAX_RETRY_COUNT) {
                reconnecting = false
                callback.onReconnectFailed()
                return@runOnMain
            }
            callback.showReconnectingUI(retryCount + 1, MAX_RETRY_COUNT)
            networkReady = networkMonitor.isNetworkAvailable()
            if (networkReady) scheduleReconnectAttempt()
        }
    }

    /** Cancela cualquier reconexión en curso (por ejemplo, cuando el usuario decide salir). */
    fun cancelReconnect() {
        reconnecting = false
        probeGeneration++
        runOnMain {
            retryCount = 0; wolAttempted = false; stableConfirmToken++
            cancelPendingReconnect()
            wolManager?.cancel()
        }
    }

    fun isReconnecting(): Boolean = reconnecting
    fun isWakingUp(): Boolean = wolManager?.isWakingUp() == true

    fun setWakeOnLan(manager: WakeOnLanManager?) {
        wolManager = manager
        wolAttempted = false
    }

    override fun onNetworkLost() {
        if (stopped) return
        networkReady = false
        cancelPendingReconnect()
    }

    override fun onNetworkAvailable() {
        if (stopped) return
        networkReady = true
        callback.onNetworkRestored()
        if (reconnecting) {
            cancelPendingReconnect()
            mainHandler.postDelayed({ if (!stopped && reconnecting && networkReady) scheduleReconnectAttempt() }, NETWORK_STABLE_DELAY_MS)
        }
    }

    /** Decide si el PC requiere Wake-on-LAN o si se procede con backoff exponencial. */
    private fun scheduleReconnectAttempt() {
        cancelPendingReconnect()
        val wm = wolManager
        if (retryCount == 0 && !wolAttempted && wm != null && wm.getHost().isNotEmpty()) {
            wolAttempted = true
            val gen = ++probeGeneration
            WolProber.probeAsync(wm.getHost(), wm.getHttpsPort(), WOL_PROBE_TIMEOUT_MS) { reachable ->
                if (stopped || !reconnecting || gen != probeGeneration) return@probeAsync
                if (!reachable) {
                    val started = wm.wakeAndWait(object : WakeOnLanManager.WakeOnLanCallback {
                        override fun onWaitingForPc(elapsed: Int, max: Int) = callback.showWakeOnLanUI(elapsed, max)
                        override fun onPcReady() { callback.hideWakeOnLanUI(); doActualReconnect(true) }
                        override fun onPcUnreachable() { callback.hideWakeOnLanUI(); doActualReconnect(false) }
                    })
                    if (!started) doActualReconnect(false)
                } else doActualReconnect(false)
            }
            return
        }
        doActualReconnect(false)
    }

    /** Programa el reintento real con backoff exponencial acotado a [MAX_RETRY_DELAY_MS]. */
    private fun doActualReconnect(immediate: Boolean) {
        cancelPendingReconnect()
        val delay = if (immediate) 400L else minOf(BASE_RETRY_DELAY_MS * (1L shl retryCount.coerceAtMost(4)), MAX_RETRY_DELAY_MS)
        LimeLog.info("$TAG: Reconectando en ${delay}ms (intento ${retryCount + 1}/$MAX_RETRY_COUNT)")
        mainHandler.postDelayed({ if (!stopped && reconnecting && networkReady) callback.doReconnect() }, delay)
    }

    private fun cancelPendingReconnect() = mainHandler.removeCallbacksAndMessages(null)
    private fun runOnMain(block: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block) }
}
