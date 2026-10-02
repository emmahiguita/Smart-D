package com.limelight.smartdisplay.bus

import android.os.Handler
import android.os.Looper
import com.limelight.LimeLog
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * SmartDisplay AI - Capa 2: WebSocket Bidirectional Bus
 *
 * Canal de baja latencia Android <-> PC.
 * Soporta comandos de portapapeles, archivos, control de energia,
 * window motion y telemetria.
 */
class SmartDisplayBus(
    private val host: String,
    private val listener: MessageListener,
    private val pin: String = ""
) : com.limelight.smartdisplay.motion.WindowMotionTransport {
    companion object {
        private const val TAG  = "SD_Bus"
        private const val PORT = 47991
    }

    interface MessageListener {
        fun onMessageReceived(json: JSONObject)
        fun onConnected()
        fun onDisconnected()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var isRunning = false
    private var reconnectAttempts = 0

    @Synchronized
    fun start() {
        if (isRunning) return
        isRunning = true
        reconnectAttempts = 0
        connect()
    }

    @Synchronized
    fun stop() {
        if (!isRunning) return
        isRunning = false
        mainHandler.removeCallbacksAndMessages(null)
        webSocket?.close(1000, "Normal closure")
        webSocket = null
    }

    private fun connect() {
        if (!isRunning) return
        val url = "ws://$host:$PORT"
        LimeLog.info("$TAG: Conectando a $url")
        val builder = Request.Builder().url(url)
        if (pin.isNotEmpty()) builder.header("Authorization", "Bearer $pin")
        val request = builder.build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                LimeLog.info("$TAG: WebSocket abierto con exito")
                reconnectAttempts = 0
                mainHandler.post { listener.onConnected() }
            }
            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    mainHandler.post { listener.onMessageReceived(json) }
                } catch (e: Exception) {
                    LimeLog.warning("$TAG: Error al parsear JSON: ${e.message}")
                }
            }
            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                LimeLog.info("$TAG: WebSocket cerrandose: $reason")
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                LimeLog.info("$TAG: WebSocket cerrado: $reason")
                mainHandler.post { listener.onDisconnected() }
                triggerReconnect()
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                LimeLog.warning("$TAG: Fallo en WebSocket: ${t.message}")
                mainHandler.post { listener.onDisconnected() }
                triggerReconnect()
            }
        })
    }

    private fun triggerReconnect() {
        if (!isRunning) return
        webSocket = null
        reconnectAttempts++
        val delay = minOf(2000L * (1L shl (reconnectAttempts - 1).coerceAtMost(14)), 30000L)
        LimeLog.info("$TAG: Reconexion en ${delay}ms (intento $reconnectAttempts)")
        mainHandler.postDelayed({
            synchronized(this) { if (isRunning) connect() }
        }, delay)
    }

    @Synchronized
    fun sendMessage(json: JSONObject): Boolean =
        webSocket?.send(json.toString()) ?: false

    // =========================================================================
    // Window Motion Engine — helpers de envio
    // =========================================================================

    /**
     * Mueve/redimensiona una ventana PC via WindowMotionEngine.
     * @param mode "drag" (ease 0.65) | "snap" (ease 0.35)
     */
    override fun sendMoveWindow(hwnd: Int, x: Int, y: Int, w: Int, h: Int,
                                mode: String): Boolean =
        sendMessage(JSONObject().apply {
            put("type","move_window")
            put("hwnd", hwnd); put("x", x); put("y", y)
            put("w", w);       put("h", h); put("mode", mode)
        })

    /** Senala fin de drag: el PC cambia ease a snap para aterrizaje suave. */
    override fun sendWindowDragEnd(hwnd: Int): Boolean =
        sendMessage(JSONObject().apply {
            put("type","window_drag_end"); put("hwnd", hwnd)
        })

    /**
     * Snap de una ventana a zona predefinida del monitor.
     * @param zone left_half | right_half | top_half | bottom_half |
     *             top_left_q | top_right_q | bottom_left_q | bottom_right_q |
     *             maximize | restore
     */
    override fun sendSnapWindow(hwnd: Int, zone: String): Boolean =
        sendMessage(JSONObject().apply {
            put("type","snap_window"); put("hwnd", hwnd); put("zone", zone)
        })

    /** Intercambia posicion de multiples ventanas simultaneamente. */
    override fun sendSwapWindows(windows: List<com.limelight.smartdisplay.motion.WindowRectDto>): Boolean {
        val arr = JSONArray()
        for (wr in windows) arr.put(JSONObject().apply {
            put("hwnd", wr.hwnd)
            put("x", wr.x); put("y", wr.y)
            put("w", wr.w); put("h", wr.h)
        })
        return sendMessage(JSONObject().apply {
            put("type","swap_windows"); put("windows", arr)
        })
    }

    /** Cancela la animacion de una ventana en el PC inmediatamente. */
    override fun sendCancelWindowMotion(hwnd: Int): Boolean =
        sendMessage(JSONObject().apply {
            put("type","cancel_window_motion"); put("hwnd", hwnd)
        })

    /**
     * Pregunta al PC que hwnd hay bajo las coordenadas dadas.
     * Respuesta: "window_at_point_result" en MessageListener.
     */
    override fun sendWindowAtPoint(pcX: Int, pcY: Int): Boolean =
        sendMessage(JSONObject().apply {
            put("type","window_at_point"); put("x", pcX); put("y", pcY)
        })
}

/** Modelo de datos para sendSwapWindows (alias de WindowRectDto para retrocompatibilidad). */
typealias WindowRect = com.limelight.smartdisplay.motion.WindowRectDto
