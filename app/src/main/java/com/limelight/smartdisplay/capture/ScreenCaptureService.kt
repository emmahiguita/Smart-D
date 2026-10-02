package com.limelight.smartdisplay.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.limelight.LimeLog
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * SmartDisplay — Screen Capture Service
 *
 * Captures the device screen via MediaProjection API, encodes it to H.264
 * using MediaCodec, and streams the encoded frames over a WebSocket connection
 * to the Electron companion (port 3002) for real-time display on the desktop
 * StreamPanel.
 */
class ScreenCaptureService : Service() {

    private var projectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null

    private var webSocket: WebSocket? = null
    private var httpClient: OkHttpClient? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    @Volatile
    private var isRunning = false

    // ── Service lifecycle ────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        httpClient = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        LimeLog.info("$TAG: Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            LimeLog.warning("$TAG: null intent — stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra("code", -1)
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("data")
        }
        val wsHost = intent.getStringExtra("ws_host")
        val token = intent.getStringExtra("token")

        if (resultCode == -1 || data == null || wsHost == null) {
            LimeLog.warning("$TAG: Missing required extras (code, data, or ws_host)")
            stopSelf()
            return START_NOT_STICKY
        }

        startCapture(resultCode, data, wsHost, token)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCapture()
        LimeLog.info("$TAG: Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Capture start / stop ─────────────────────────────────────────────────

    private fun startCapture(resultCode: Int, data: Intent, wsHost: String, token: String?) {
        if (isRunning) return
        isRunning = true

        mediaProjection = projectionManager?.getMediaProjection(resultCode, data)
        if (mediaProjection == null) {
            LimeLog.warning("$TAG: Failed to obtain MediaProjection")
            isRunning = false
            stopSelf()
            return
        }

        captureThread = HandlerThread("ScreenCapture").apply {
            start()
            captureHandler = Handler(looper)
        }

        captureHandler?.post {
            if (!initEncoder()) {
                LimeLog.warning("$TAG: Encoder initialization failed")
                isRunning = false
                stopSelf()
                return@post
            }
            connectWebSocket(wsHost, token)
        }
    }

    @Synchronized
    private fun stopCapture() {
        if (!isRunning) return
        isRunning = false

        LimeLog.info("$TAG: Stopping capture")

        webSocket?.close(1000, "Capture stopped")
        webSocket = null

        virtualDisplay?.release()
        virtualDisplay = null

        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (ignored: Exception) {}
        mediaCodec = null

        mediaProjection?.stop()
        mediaProjection = null

        captureThread?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                it.quitSafely()
            } else {
                it.quit()
            }
        }
        captureThread = null
        captureHandler = null
    }

    // ── MediaCodec H.264 encoder ─────────────────────────────────────────────

    private fun initEncoder(): Boolean {
        return try {
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, CAPTURE_WIDTH, CAPTURE_HEIGHT
            ).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileMain)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel32)
            }

            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = createInputSurface()
                start()
            }

            LimeLog.info("$TAG: Encoder initialized (${CAPTURE_WIDTH}x$CAPTURE_HEIGHT)")
            true
        } catch (e: Exception) {
            LimeLog.warning("$TAG: Encoder init error: ${e.message}")
            false
        }
    }

    private fun startVirtualDisplay() {
        val proj = mediaProjection ?: return
        val surface = inputSurface ?: return

        virtualDisplay = proj.createVirtualDisplay(
            "SmartDisplay-ScreenCapture",
            CAPTURE_WIDTH,
            CAPTURE_HEIGHT,
            CAPTURE_DPI,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null
        )

        LimeLog.info("$TAG: VirtualDisplay created")
        captureHandler?.post(::encoderLoop)
    }

    private fun encoderLoop() {
        if (!isRunning) return
        val codec = mediaCodec ?: return

        val info = MediaCodec.BufferInfo()

        try {
            val outputIndex = codec.dequeueOutputBuffer(info, 10_000)

            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                captureHandler?.post(::encoderLoop)
                return
            }

            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val newFormat = codec.outputFormat
                val csd0 = newFormat.getByteBuffer("csd-0")
                val csd1 = newFormat.getByteBuffer("csd-1")
                if (csd0 != null) sendBinary(csd0)
                if (csd1 != null) sendBinary(csd1)
                LimeLog.info("$TAG: Codec config sent")
                captureHandler?.post(::encoderLoop)
                return
            }

            if (outputIndex >= 0) {
                val outputBuffer = codec.getOutputBuffer(outputIndex)
                if (outputBuffer != null && info.size > 0) {
                    outputBuffer.position(info.offset)
                    outputBuffer.limit(info.offset + info.size)
                    val copy = ByteBuffer.allocate(info.size)
                    copy.put(outputBuffer)
                    copy.flip()
                    sendBinary(copy)
                }
                codec.releaseOutputBuffer(outputIndex, false)
            }
        } catch (e: Exception) {
            LimeLog.warning("$TAG: Encoder loop error: ${e.message}")
        }

        if (isRunning) {
            captureHandler?.post(::encoderLoop)
        }
    }

    // ── WebSocket client (OkHttp) ────────────────────────────────────────────

    private fun connectWebSocket(host: String, token: String?) {
        val url = "ws://$host:$WS_PORT?token=${token ?: ""}"
        LimeLog.info("$TAG: Connecting to $url")

        val request = Request.Builder().url(url).build()

        httpClient?.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                webSocket = ws
                LimeLog.info("$TAG: WebSocket connected")

                try {
                    val reg = JSONObject().apply {
                        put("type", "register")
                        put("client", "android-stream")
                    }
                    ws.send(reg.toString())
                    LimeLog.info("$TAG: Registered as android-stream")
                } catch (e: Exception) {
                    LimeLog.warning("$TAG: Registration error: ${e.message}")
                }

                startVirtualDisplay()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val msg = JSONObject(text)
                    if ("stop" == msg.optString("type", "")) {
                        LimeLog.info("$TAG: Received stop command from desktop")
                        stopSelf()
                    }
                } catch (ignored: Exception) {}
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                LimeLog.info("$TAG: WebSocket closing: $reason")
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                LimeLog.info("$TAG: WebSocket closed: $reason")
                webSocket = null
                stopSelf()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                LimeLog.warning("$TAG: WebSocket failure: ${t.message}")
                webSocket = null
                if (isRunning) {
                    captureHandler?.postDelayed({
                        if (isRunning) connectWebSocket(host, token)
                    }, 3000)
                }
            }
        })
    }

    @Synchronized
    private fun sendBinary(data: ByteBuffer) {
        val ws = webSocket ?: return
        if (!isRunning) return
        try {
            val pos = data.position()
            val bytes = ByteArray(data.remaining())
            data.get(bytes)
            data.position(pos)
            ws.send(ByteString.of(*bytes))
        } catch (e: Exception) {
            LimeLog.warning("$TAG: Send error: ${e.message}")
        }
    }

    // ── Foreground notification ──────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Capture",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notification for screen capture service"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SmartDisplay")
            .setContentText("Screen capture active — streaming to desktop")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "ScreenCaptureSvc"
        private const val CHANNEL_ID = "screen_capture_channel"
        private const val NOTIFICATION_ID = 1001

        private const val CAPTURE_WIDTH = 720
        private const val CAPTURE_HEIGHT = 1280
        private const val CAPTURE_DPI = 320
        private const val BITRATE = 2_000_000
        private const val FRAME_RATE = 30
        private const val I_FRAME_INTERVAL = 1

        private const val WS_PORT = 3002
    }
}
