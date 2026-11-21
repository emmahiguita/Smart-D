package com.limelight.smartdisplay.bus

import android.os.Handler
import android.os.Looper
import com.limelight.LimeLog
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * SmartDisplayBus — Capa de Red Auxiliar (SOLID: Responsabilidad Única)
 *
 * Qué hace:
 *   Mantiene un canal WebSocket bidireccional de baja latencia Android <-> PC (puerto 47991)
 *   para sincronizar portapapeles, archivos, ventanas activas y telemetría.
 *
 * Cómo funciona:
 *   - Usa `pingInterval(15s)` en OkHttp para detectar cortes silenciosos (half-open TCP).
 *   - Protege callbacks con identidad de socket (`ws !== webSocket`) para que sockets viejos
 *     nunca cierren ni dupliquen reconexiones de una instancia nueva.
 *   - Aplica backoff exponencial acotado a 30s y libera el pool HTTP en [stop] sin dejar hilos zombi.
 */
class SmartDisplayBus(
    private val host: String,
    private val listener: MessageListener,
    private val pin: String = ""
) : com.limelight.smartdisplay.motion.WindowMotionTransport {

    companion object {
        private const val TAG = "SD_Bus"
        private const val PORT = 47991
        private const val PING_INTERVAL_SEC = 15L
        private const val CONNECT_TIMEOUT_SEC = 5L
        private const val MAX_BACKOFF_MS = 30_000L
    }

    /** Contrato de eventos del bus hacia el gestor de sesión. */
    interface MessageListener {
        fun onMessageReceived(json: JSONObject)
        fun onConnected()
        fun onDisconnected()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .pingInterval(PING_INTERVAL_SEC, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    @Volatile private var isRunning = false
    private var reconnectAttempts = 0

    /** Inicia la conexión WebSocket si aún no está activa. */
    @Synchronized
    fun start() {
        if (isRunning) return
        isRunning = true
        reconnectAttempts = 0
        connect()
    }

    /**
     * Fuerza un reintento inmediato (por ejemplo, cuando [NetworkMonitor] confirma
     * que volvió el Wi-Fi) sin esperar el resto del backoff exponencial.
     */
    @Synchronized
    fun reconnectNow() {
        if (!isRunning || webSocket != null) return
        mainHandler.removeCallbacksAndMessages(null)
        reconnectAttempts = 0
        connect()
    }

    /** Detiene el bus, cierra el WebSocket y libera el pool de OkHttp (cero hilos zombi). */
    @Synchronized
    fun stop() {
        if (!isRunning) return
        isRunning = false
        mainHandler.removeCallbacksAndMessages(null)
        val ws = webSocket
        webSocket = null
        ws?.close(1000, "Normal closure")
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    /** Formatea la URL WebSocket de forma segura para IPv4 e IPv6. */
    private fun buildWsUrl(): String {
        val cleanHost = host.trim()
        val formattedHost = if (cleanHost.contains(":") && !cleanHost.startsWith("[")) {
            "[$cleanHost]"
        } else {
            cleanHost
        }
        return "ws://$formattedHost:$PORT"
    }

    /** Abre un nuevo WebSocket y vincula el listener validando identidad del socket activo. */
    @Synchronized
    private fun connect() {
        if (!isRunning) return
        val url = buildWsUrl()
        LimeLog.info("$TAG: Conectando a $url")
        val builder = Request.Builder().url(url)
        if (pin.isNotEmpty()) builder.header("Authorization", "Bearer $pin")

        webSocket = client.newWebSocket(builder.build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                synchronized(this@SmartDisplayBus) {
                    if (ws !== webSocket || !isRunning) {
                        ws.close(1000, "Stale socket")
                        return
                    }
                    reconnectAttempts = 0
                }
                LimeLog.info("$TAG: WebSocket abierto con éxito")
                mainHandler.post { if (isRunning) listener.onConnected() }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                if (!isRunning) return
                try {
                    val json = JSONObject(text)
                    mainHandler.post { if (isRunning) listener.onMessageReceived(json) }
                } catch (e: Exception) {
                    LimeLog.warning("$TAG: JSON inválido recibido: ${e.message}")
                }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                LimeLog.info("$TAG: WebSocket cerrándose ($code): $reason")
                ws.close(1000, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                LimeLog.info("$TAG: WebSocket cerrado ($code): $reason")
                handleSocketDrop(ws)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                LimeLog.warning("$TAG: Fallo en WebSocket: ${t.message}")
                handleSocketDrop(ws)
            }
        })
    }

    /** Maneja caídas solo del socket activo actual y programa reconexión con backoff exponencial. */
    @Synchronized
    private fun handleSocketDrop(ws: WebSocket) {
        if (ws !== webSocket) return
        webSocket = null
        mainHandler.post { listener.onDisconnected() }
        if (!isRunning) return

        mainHandler.removeCallbacksAndMessages(null)
        reconnectAttempts++
        val delay = minOf(2000L * (1L shl (reconnectAttempts - 1).coerceAtMost(4)), MAX_BACKOFF_MS)
        LimeLog.info("$TAG: Reconexión en ${delay}ms (intento $reconnectAttempts)")
        mainHandler.postDelayed({
            synchronized(this@SmartDisplayBus) { if (isRunning && webSocket == null) connect() }
        }, delay)
    }

    /** Envía un mensaje JSON por el WebSocket activo de forma segura entre hilos. */
    @Synchronized
    fun sendMessage(json: JSONObject): Boolean = webSocket?.send(json.toString()) ?: false

    private inline fun sendCmd(type: String, block: JSONObject.() -> Unit) =
        sendMessage(JSONObject().apply { put("type", type); block() })

    // ── Window Motion Engine: comandos de ventanas hacia el PC ──
    override fun sendMoveWindow(hwnd: Int, x: Int, y: Int, w: Int, h: Int, mode: String) =
        sendCmd("move_window") { put("hwnd", hwnd); put("x", x); put("y", y); put("w", w); put("h", h); put("mode", mode) }

    override fun sendWindowDragEnd(hwnd: Int) = sendCmd("window_drag_end") { put("hwnd", hwnd) }
    override fun sendSnapWindow(hwnd: Int, zone: String) = sendCmd("snap_window") { put("hwnd", hwnd); put("zone", zone) }
    override fun sendCancelWindowMotion(hwnd: Int) = sendCmd("cancel_window_motion") { put("hwnd", hwnd) }
    override fun sendWindowAtPoint(pcX: Int, pcY: Int) = sendCmd("window_at_point") { put("x", pcX); put("y", pcY) }

    override fun sendSwapWindows(windows: List<com.limelight.smartdisplay.motion.WindowRectDto>): Boolean {
        val arr = JSONArray()
        for (wr in windows) arr.put(JSONObject().apply { put("hwnd", wr.hwnd); put("x", wr.x); put("y", wr.y); put("w", wr.w); put("h", wr.h) })
        return sendCmd("swap_windows") { put("windows", arr) }
    }
}
