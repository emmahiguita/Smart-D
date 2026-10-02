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
 * TextFocusWatcher — SmartDisplay AI Premium · Componente 3
 * ──────────────────────────────────────────────────────────
 * Cliente WebSocket que se conecta al Companion Server de Windows.
 * El servidor (Python/Node.js) monitoriza via UI Automation qué
 * control tiene el foco y si es un campo de texto.
 */
class TextFocusWatcher @JvmOverloads constructor(
    private val serverUrl: String,
    private val callback: Callback?,
    private val pin: String = ""
) {

    // ── Interfaz de callbacks ──────────────────────────────────────────────
    interface Callback {
        /** Se llama cuando el foco de Windows cae en un campo de texto */
        fun onTextFieldFocused(x: Float, y: Float, w: Float, h: Float, app: String)

        /** Se llama cuando el foco sale del campo de texto */
        fun onTextFieldCleared()

        /** Estado de la conexión WebSocket */
        fun onConnectionStatus(connected: Boolean)

        /**
         * Hover sobre un elemento genérico de la UI remota (botón, link, IDE…).
         * [element]: none | text_input | button | link | ide_workspace.
         * [snapX]/[snapY] solo válidos si [hasSnap] es true.
         */
        fun onHoverElement(
            element: String,
            x: Float,
            y: Float,
            w: Float,
            h: Float,
            snapX: Float,
            snapY: Float,
            hasSnap: Boolean,
            app: String
        ) {}

        /**
         * Posición real del cursor del PC (coordenadas de Windows). Permite dibujar
         * el cursor gigante de Android exactamente donde apunta el del PC.
         */
        fun onCursorPos(x: Float, y: Float) {}
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var client: OkHttpClient? = null
    private var webSocket: WebSocket? = null

    var isConnected: Boolean = false
        private set

    private var shouldConnect = false
    private var retryCount = 0

    // ── API Pública ───────────────────────────────────────────────────────

    fun connect() {
        shouldConnect = true
        retryCount = 0
        doConnect()
    }

    fun disconnect() {
        shouldConnect = false
        webSocket?.let {
            it.close(1000, "App closing")
            webSocket = null
        }
        client?.let {
            it.dispatcher.executorService.shutdown()
            client = null
        }
        isConnected = false
    }

    // ── Implementación interna ────────────────────────────────────────────

    private fun doConnect() {
        if (!shouldConnect) return

        if (client == null) {
            client = OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()
        }

        val builder = Request.Builder().url(serverUrl)
        if (pin.isNotEmpty()) {
            builder.header("Authorization", "Bearer $pin")
        }
        val request = builder.build()

        webSocket = client?.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                isConnected = true
                retryCount = 0
                mainHandler.post {
                    callback?.onConnectionStatus(true)
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                parseMessage(text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                mainHandler.post {
                    callback?.onConnectionStatus(false)
                }
                scheduleReconnect()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                isConnected = false
                mainHandler.post {
                    callback?.onConnectionStatus(false)
                }
                if (code != 1000) {
                    scheduleReconnect()
                }
            }
        })
    }

    private fun parseMessage(json: String) {
        try {
            val msg = JSONObject(json)
            val type = msg.optString("type", "")

            if ("focus_changed" == type && msg.optBoolean("is_text_field", false)) {
                val rect = msg.optJSONObject("rect")
                var x = 0f
                var y = 0f
                var w = 0f
                var h = 0f
                if (rect != null) {
                    x = rect.optDouble("x", 0.0).toFloat()
                    y = rect.optDouble("y", 0.0).toFloat()
                    w = rect.optDouble("w", 0.0).toFloat()
                    h = rect.optDouble("h", 0.0).toFloat()
                }
                val app = msg.optString("app", "")
                mainHandler.post {
                    callback?.onTextFieldFocused(x, y, w, h, app)
                }
            } else if ("cursor_pos" == type) {
                val cx = msg.optDouble("x", 0.0).toFloat()
                val cy = msg.optDouble("y", 0.0).toFloat()
                mainHandler.post {
                    callback?.onCursorPos(cx, cy)
                }
            } else if ("focus_cleared" == type) {
                mainHandler.post {
                    callback?.onTextFieldCleared()
                }
            } else if ("hover_element" == type) {
                val element = msg.optString("element", "none")
                val rect = msg.optJSONObject("rect")
                var x = 0f
                var y = 0f
                var w = 0f
                var h = 0f
                if (rect != null) {
                    x = rect.optDouble("x", 0.0).toFloat()
                    y = rect.optDouble("y", 0.0).toFloat()
                    w = rect.optDouble("w", 0.0).toFloat()
                    h = rect.optDouble("h", 0.0).toFloat()
                }
                val snap = msg.optJSONObject("snap")
                val hasSnap = snap != null
                val sx = if (hasSnap) snap!!.optDouble("x", 0.0).toFloat() else 0f
                val sy = if (hasSnap) snap!!.optDouble("y", 0.0).toFloat() else 0f
                val app = msg.optString("app", "")

                mainHandler.post {
                    callback?.onHoverElement(element, x, y, w, h, sx, sy, hasSnap, app)
                }
            }
        } catch (ignored: Exception) {
        }
    }

    private fun scheduleReconnect() {
        if (!shouldConnect || retryCount >= MAX_RETRIES) return
        val delay = RECONNECT_DELAY_MS * (1L shl retryCount)
        retryCount++
        mainHandler.postDelayed({ doConnect() }, delay)
    }

    companion object {
        const val DEFAULT_PORT = 8765
        private const val RECONNECT_DELAY_MS = 5000L
        private const val MAX_RETRIES = 10
    }
}
