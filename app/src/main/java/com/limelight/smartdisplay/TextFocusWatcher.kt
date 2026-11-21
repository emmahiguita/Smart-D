package com.limelight.smartdisplay

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * TextFocusWatcher — Cliente WebSocket de Foco y Cursor (SOLID: Responsabilidad Única)
 *
 * Qué hace:
 *   Conecta con el Companion Server de Windows (puerto 8765) para recibir en tiempo real:
 *   - Campos de texto enfocados (para abrir teclado inteligente automáticamente).
 *   - Posición real del cursor de Windows y elementos bajo hover (Smart Snap).
 *
 * Cómo funciona:
 *   - Emplea `pingInterval(15s)` para detectar desconexiones silenciosas (half-open TCP).
 *   - Valida identidad del socket activo (`ws === webSocket`) para evitar reconexiones duplicadas.
 *   - Aplica backoff exponencial acotado a 30s y limpia todos los callbacks en [disconnect].
 */
class TextFocusWatcher @JvmOverloads constructor(
    private val serverUrl: String,
    private val callback: Callback?,
    private val pin: String = ""
) {
    /** Contrato de eventos visuales y de foco hacia la sesión. */
    interface Callback {
        fun onTextFieldFocused(x: Float, y: Float, w: Float, h: Float, app: String)
        fun onTextFieldCleared()
        fun onConnectionStatus(connected: Boolean)
        fun onHoverElement(
            element: String, x: Float, y: Float, w: Float, h: Float,
            snapX: Float, snapY: Float, hasSnap: Boolean, app: String
        ) {}
        fun onCursorPos(x: Float, y: Float) {}
    }

    companion object {
        const val DEFAULT_PORT = 8765
        private const val BASE_RECONNECT_MS = 3_000L
        private const val MAX_RECONNECT_MS = 30_000L
        private const val PING_INTERVAL_SEC = 15L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var client: OkHttpClient? = null
    private var webSocket: WebSocket? = null

    @Volatile var isConnected: Boolean = false
        private set

    @Volatile private var shouldConnect = false
    private var retryCount = 0

    /** Inicia la conexión WebSocket hacia el Companion de foco. */
    @Synchronized
    fun connect() {
        if (shouldConnect) return
        shouldConnect = true
        retryCount = 0
        doConnect()
    }

    /** Reintenta inmediatamente cuando la red vuelve sin esperar el backoff restante. */
    @Synchronized
    fun reconnectNow() {
        if (!shouldConnect || webSocket != null) return
        mainHandler.removeCallbacksAndMessages(null)
        retryCount = 0
        doConnect()
    }

    /** Cierra el WebSocket, cancela reintentos pendientes y apaga el pool HTTP (cero zombis). */
    @Synchronized
    fun disconnect() {
        shouldConnect = false
        isConnected = false
        mainHandler.removeCallbacksAndMessages(null)
        webSocket?.close(1000, "App closing")
        webSocket = null
        client?.let {
            it.dispatcher.executorService.shutdown()
            it.connectionPool.evictAll()
        }
        client = null
    }

    /** Crea el cliente HTTP si no existe y abre el WebSocket con keepalive ping. */
    @Synchronized
    private fun doConnect() {
        if (!shouldConnect) return

        if (client == null) {
            client = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .pingInterval(PING_INTERVAL_SEC, TimeUnit.SECONDS)
                .build()
        }

        val builder = Request.Builder().url(serverUrl)
        if (pin.isNotEmpty()) builder.header("Authorization", "Bearer $pin")

        webSocket = client?.newWebSocket(builder.build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                synchronized(this@TextFocusWatcher) {
                    if (ws !== webSocket || !shouldConnect) {
                        ws.close(1000, "Stale socket")
                        return
                    }
                    isConnected = true
                    retryCount = 0
                }
                mainHandler.post { if (shouldConnect) callback?.onConnectionStatus(true) }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                if (shouldConnect) parseMessage(text)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(1000, null)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                handleDrop(ws)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                handleDrop(ws)
            }
        })
    }

    /** Gestiona desconexiones del socket activo y programa backoff acotado a 30s. */
    @Synchronized
    private fun handleDrop(ws: WebSocket) {
        if (ws !== webSocket) return
        webSocket = null
        isConnected = false
        mainHandler.post { callback?.onConnectionStatus(false) }
        if (!shouldConnect) return

        mainHandler.removeCallbacksAndMessages(null)
        val delay = minOf(BASE_RECONNECT_MS * (1L shl retryCount.coerceAtMost(4)), MAX_RECONNECT_MS)
        retryCount++
        mainHandler.postDelayed({
            synchronized(this@TextFocusWatcher) {
                if (shouldConnect && webSocket == null) doConnect()
            }
        }, delay)
    }

    private fun JSONObject?.f(k: String): Float = this?.optDouble(k, 0.0)?.toFloat() ?: 0f

    /** Traduce eventos JSON del Companion a callbacks en el hilo principal. */
    private fun parseMessage(json: String) {
        try {
            val msg = JSONObject(json)
            when (msg.optString("type", "")) {
                "focus_changed" -> if (msg.optBoolean("is_text_field", false)) {
                    val rect = msg.optJSONObject("rect")
                    val app = msg.optString("app", "")
                    mainHandler.post { callback?.onTextFieldFocused(rect.f("x"), rect.f("y"), rect.f("w"), rect.f("h"), app) }
                }
                "cursor_pos" -> {
                    val cx = msg.optDouble("x", 0.0).toFloat()
                    val cy = msg.optDouble("y", 0.0).toFloat()
                    mainHandler.post { callback?.onCursorPos(cx, cy) }
                }
                "focus_cleared" -> mainHandler.post { callback?.onTextFieldCleared() }
                "hover_element" -> {
                    val element = msg.optString("element", "none")
                    val rect = msg.optJSONObject("rect")
                    val snap = msg.optJSONObject("snap")
                    val app = msg.optString("app", "")
                    mainHandler.post {
                        callback?.onHoverElement(
                            element, rect.f("x"), rect.f("y"), rect.f("w"), rect.f("h"),
                            snap.f("x"), snap.f("y"), snap != null, app
                        )
                    }
                }
            }
        } catch (_: Exception) {}
    }
}
