package com.limelight.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.widget.TextView
import com.limelight.R
import com.limelight.binding.video.MediaCodecDecoderRenderer
import com.limelight.nvstream.NvConnection
import com.limelight.nvstream.input.KeyboardPacket
import com.limelight.nvstream.input.MouseButtonPacket
import com.limelight.nvstream.jni.MoonBridge
import kotlin.math.abs

/**
 * PortraitHybridController — SmartDisplay AI Premium · Componente 4
 * ──────────────────────────────────────────────────────────────────
 * Gestiona el Modo Vertical Híbrido de una sola mano.
 * Activo cuando la pantalla está en orientación PORTRAIT.
 */
class PortraitHybridController(
    private val rootView: View,
    private val context: Context
) {
    companion object {
        private const val ACC_THRESHOLD = 8f
        private const val ACC_FACTOR = 2.2f
        private const val SCROLL_SENSITIVITY = 1.5f
        private const val CURSOR_SENSITIVITY = 1.8f
        private const val TWO_FINGER_TAP_MS = 180L

        private const val VK_TAB: Short = 0x09
        private const val VK_ESC: Short = 0x1B
        private const val VK_CTRL: Short = 0x11
        private const val VK_ALT: Short = 0x12
        private const val VK_WIN: Short = 0x5B
        private const val VK_C: Short = 0x43
        private const val VK_V: Short = 0x56
        private const val VK_Z: Short = 0x5A
    }

    private var conn: NvConnection? = null
    private var keyboard: LogicalKeyboardOverlay? = null
    private var cursorView: AdaptiveCursorView? = null

    private var lastX = 0f
    private var lastY = 0f
    private var activePointers = 0
    private var twoFingerTap = false
    private var twoFingerTime = 0L

    private var scrollAccX = 0f
    private var scrollAccY = 0f

    private var velocityTracker: VelocityTracker? = null
    private var inertiaScrollX = 0f
    private var inertiaScrollY = 0f

    private val handler = Handler(Looper.getMainLooper())
    private val inertiaRunnable = object : Runnable {
        override fun run() {
            if (abs(inertiaScrollX) < 0.5f && abs(inertiaScrollY) < 0.5f) {
                return
            }
            sendScroll(inertiaScrollX.toInt(), inertiaScrollY.toInt())
            inertiaScrollX *= 0.85f
            inertiaScrollY *= 0.85f
            handler.postDelayed(this, 16)
        }
    }

    private val gestureIndicator: TextView?
    private val connectionInfo: TextView?

    private var ctrlActive = false
    private var altActive = false

    init {
        val indicatorId = context.resources.getIdentifier("hybridGestureIndicator", "id", context.packageName)
        gestureIndicator = if (indicatorId != 0) rootView.findViewById(indicatorId) else null
        connectionInfo = rootView.findViewById(R.id.hybridConnectionInfo)

        setupSmartBar()
        setupTouchpad()
        hideGestureHint()
    }

    private var isConnectedProvider: (() -> Boolean)? = null
    private var decoderRendererProvider: (() -> MediaCodecDecoderRenderer?)? = null
    private var lastRenderedFrames = 0
    private var lastConnStatsMs = 0L

    private val connStatsRunnable = object : Runnable {
        override fun run() {
            val isConn = isConnectedProvider?.invoke() ?: false
            val renderer = decoderRendererProvider?.invoke()
            if (isConn && renderer != null) {
                val now = android.os.SystemClock.elapsedRealtime()
                val frames = renderer.totalFramesRendered
                val dtMs = now - lastConnStatsMs
                val fps = if (lastConnStatsMs > 0 && dtMs > 0) {
                    Math.round((frames - lastRenderedFrames) * 1000f / dtMs).toInt()
                } else {
                    0
                }
                lastRenderedFrames = frames
                lastConnStatsMs = now
                val pingMs = (MoonBridge.getEstimatedRttInfo() shr 32).toInt()
                updateConnectionInfo(pingMs, maxOf(fps, 0))
            }
            handler.postDelayed(this, 1000)
        }
    }

    /** Actualiza el badge "X ms · Y fps" con métricas reales del stream. */
    fun updateConnectionInfo(pingMs: Int, fps: Int) {
        connectionInfo?.text = "$pingMs ms · $fps fps"
    }

    /**
     * Inicia o reinicia el muestreo de estadísticas de conexión (RTT y FPS) para el modo vertical.
     */
    fun startStatsPolling(isConnected: () -> Boolean, rendererProvider: () -> MediaCodecDecoderRenderer?) {
        this.isConnectedProvider = isConnected
        this.decoderRendererProvider = rendererProvider
        lastRenderedFrames = 0
        lastConnStatsMs = 0L
        stopStatsPolling()
        handler.post(connStatsRunnable)
    }

    /**
     * Detiene el muestreo de estadísticas.
     */
    fun stopStatsPolling() {
        handler.removeCallbacks(connStatsRunnable)
    }

    /**
     * Libera callbacks y tareas en background.
     */
    fun destroy() {
        stopStatsPolling()
        handler.removeCallbacks(inertiaRunnable)
    }

    fun setConnection(conn: NvConnection?) {
        this.conn = conn
    }

    fun setKeyboard(kb: LogicalKeyboardOverlay?) {
        this.keyboard = kb
    }

    fun setCursorView(cv: AdaptiveCursorView?) {
        this.cursorView = cv
    }

    fun isPortraitMode(): Boolean {
        return context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    }

    private fun setupSmartBar() {
        // Redundante con el teclado lógico completo
    }

    fun updateSmartBarForApp(app: String?) {}

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchpad() {
        val touchpadId = context.resources.getIdentifier("hybridTouchpad", "id", context.packageName)
        val touchpad = if (touchpadId != 0) rootView.findViewById<View>(touchpadId) else null

        val leftClickId = context.resources.getIdentifier("hybridLeftClick", "id", context.packageName)
        val leftClick = if (leftClickId != 0) rootView.findViewById<View>(leftClickId) else null

        val rightClickId = context.resources.getIdentifier("hybridRightClick", "id", context.packageName)
        val rightClick = if (rightClickId != 0) rootView.findViewById<View>(rightClickId) else null

        touchpad?.setOnTouchListener { v, event -> handleTouchpadEvent(v, event) }
        leftClick?.setOnClickListener { sendMouseClick(MouseButtonPacket.BUTTON_LEFT) }
        rightClick?.setOnClickListener { sendMouseClick(MouseButtonPacket.BUTTON_RIGHT) }
    }

    private fun handleTouchpadEvent(v: View, event: MotionEvent): Boolean {
        val pointerCount = event.pointerCount
        val action = event.actionMasked

        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain()
        }
        velocityTracker?.addMovement(event)

        when (action) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                activePointers = pointerCount
                lastX = event.getX(0)
                lastY = event.getY(0)
                if (pointerCount == 2) {
                    twoFingerTap = true
                    twoFingerTime = System.currentTimeMillis()
                }
                handler.removeCallbacks(inertiaRunnable)
                showGestureHint(pointerCount)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                var dx = (event.getX(0) - lastX) * CURSOR_SENSITIVITY
                var dy = (event.getY(0) - lastY) * CURSOR_SENSITIVITY
                lastX = event.getX(0)
                lastY = event.getY(0)

                if (pointerCount == 1) {
                    dx = applyAcceleration(dx)
                    dy = applyAcceleration(dy)
                    sendMouseMove(dx.toInt(), dy.toInt())
                } else if (pointerCount == 2) {
                    twoFingerTap = false
                    scrollAccX += dx * SCROLL_SENSITIVITY
                    scrollAccY += dy * SCROLL_SENSITIVITY
                    val scrollX = scrollAccX.toInt()
                    val scrollY = scrollAccY.toInt()
                    if (scrollX != 0 || scrollY != 0) {
                        sendScroll(scrollX, scrollY)
                        scrollAccX -= scrollX
                        scrollAccY -= scrollY
                    }
                }
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                activePointers = pointerCount - 1
                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                val finalCount = activePointers
                activePointers = 0
                scrollAccX = 0f
                scrollAccY = 0f
                hideGestureIndicator()

                if (finalCount == 2 && velocityTracker != null) {
                    velocityTracker?.computeCurrentVelocity(1000)
                    val vx = velocityTracker?.xVelocity ?: 0f
                    val vy = velocityTracker?.yVelocity ?: 0f
                    inertiaScrollX = (vx / 1000f) * SCROLL_SENSITIVITY * 2.5f
                    inertiaScrollY = (vy / 1000f) * SCROLL_SENSITIVITY * 2.5f
                    if (abs(inertiaScrollX) > 1f || abs(inertiaScrollY) > 1f) {
                        handler.post(inertiaRunnable)
                    }
                }

                velocityTracker?.recycle()
                velocityTracker = null

                val elapsed = System.currentTimeMillis() - twoFingerTime
                if (finalCount == 2 && twoFingerTap && elapsed < TWO_FINGER_TAP_MS) {
                    sendMouseClick(MouseButtonPacket.BUTTON_RIGHT)
                    cursorView?.fireRightClick(lastX, lastY)
                } else if (finalCount == 1) {
                    sendMouseClick(MouseButtonPacket.BUTTON_LEFT)
                    cursorView?.fireLeftClick(lastX, lastY)
                } else if (finalCount == 3) {
                    handler.post {
                        keyboard?.let {
                            if (it.isVisible()) it.hide() else it.show()
                        }
                    }
                } else if (finalCount == 4) {
                    handler.post {
                        val barId = context.resources.getIdentifier("hybridSmartBar", "id", context.packageName)
                        val smartBar = if (barId != 0) rootView.findViewById<View>(barId) else null
                        if (smartBar != null) {
                            if (smartBar.visibility == View.VISIBLE) {
                                smartBar.animate().translationY(-smartBar.height.toFloat()).alpha(0f).setDuration(220)
                                    .withEndAction { smartBar.visibility = View.GONE }.start()
                            } else {
                                smartBar.visibility = View.VISIBLE
                                smartBar.translationY = -smartBar.height.toFloat()
                                smartBar.alpha = 0f
                                smartBar.animate().translationY(0f).alpha(1f).setDuration(220).start()
                            }
                        }
                    }
                }
                twoFingerTap = false
                return true
            }
        }
        return false
    }

    private fun applyAcceleration(delta: Float): Float {
        val absDelta = abs(delta)
        if (absDelta < ACC_THRESHOLD) return delta
        val sign = if (delta < 0) -1f else 1f
        val extra = (absDelta - ACC_THRESHOLD) / ACC_THRESHOLD
        return sign * (ACC_THRESHOLD + extra * extra * ACC_FACTOR)
    }

    private fun sendMouseMove(dx: Int, dy: Int) {
        if (conn == null || (dx == 0 && dy == 0)) return
        try {
            conn?.sendMouseMove(dx.toShort(), dy.toShort())
        } catch (ignored: Exception) {}
    }

    private fun sendScroll(dx: Int, dy: Int) {
        if (conn == null) return
        try {
            if (dy != 0) conn?.sendMouseHighResScroll(dy.toShort())
            if (dx != 0) conn?.sendMouseHighResHScroll(dx.toShort())
        } catch (ignored: Exception) {}
    }

    private fun sendMouseClick(button: Byte) {
        if (conn == null) return
        try {
            conn?.sendMouseButtonDown(button)
            handler.postDelayed({
                try {
                    conn?.sendMouseButtonUp(button)
                } catch (ignored: Exception) {}
            }, 40)
        } catch (ignored: Exception) {}
    }

    fun sendKey(vk: Short) {
        if (conn == null) return
        try {
            conn?.sendKeyboardInput(vk, KeyboardPacket.KEY_DOWN, 0.toByte(), 0.toByte())
            handler.postDelayed({
                try {
                    conn?.sendKeyboardInput(vk, KeyboardPacket.KEY_UP, 0.toByte(), 0.toByte())
                } catch (ignored: Exception) {}
            }, 40)
        } catch (ignored: Exception) {}
    }

    fun sendCombo(mod: Short, key: Short) {
        if (conn == null) return
        try {
            conn?.sendKeyboardInput(mod, KeyboardPacket.KEY_DOWN, 0.toByte(), 0.toByte())
            conn?.sendKeyboardInput(key, KeyboardPacket.KEY_DOWN, 0.toByte(), 0.toByte())
            handler.postDelayed({
                try {
                    conn?.sendKeyboardInput(key, KeyboardPacket.KEY_UP, 0.toByte(), 0.toByte())
                    conn?.sendKeyboardInput(mod, KeyboardPacket.KEY_UP, 0.toByte(), 0.toByte())
                } catch (ignored: Exception) {}
            }, 60)
        } catch (ignored: Exception) {}
    }

    fun toggleModifier(vk: Short) {
        val active = if (vk == VK_CTRL) ctrlActive else altActive
        if (!active) {
            try {
                conn?.sendKeyboardInput(vk, KeyboardPacket.KEY_DOWN, 0.toByte(), 0.toByte())
            } catch (ignored: Exception) {}
            if (vk == VK_CTRL) ctrlActive = true else altActive = true
        } else {
            try {
                conn?.sendKeyboardInput(vk, KeyboardPacket.KEY_UP, 0.toByte(), 0.toByte())
            } catch (ignored: Exception) {}
            if (vk == VK_CTRL) ctrlActive = false else altActive = false
        }
        val btnId = context.resources.getIdentifier(if (vk == VK_CTRL) "sbCtrl" else "sbAlt", "id", context.packageName)
        val btn = if (btnId != 0) rootView.findViewById<View>(btnId) else null
        if (btn is TextView) {
            val nowActive = if (vk == VK_CTRL) ctrlActive else altActive
            btn.setTextColor(context.getColor(if (nowActive) android.R.color.holo_blue_light else android.R.color.white))
        }
    }

    private fun showGestureHint(fingers: Int) {
        val indicator = gestureIndicator ?: return
        val icons = arrayOf("", "☝", "✌", "🤟", "✋")
        val icon = if (fingers in 1 until icons.size) icons[fingers] else ""
        indicator.text = icon
        indicator.visibility = View.VISIBLE
        indicator.animate().alpha(0.7f).setDuration(100).start()
    }

    private fun hideGestureIndicator() {
        val indicator = gestureIndicator ?: return
        indicator.animate().alpha(0f).setDuration(200)
            .withEndAction { indicator.visibility = View.INVISIBLE }
            .start()
    }

    private fun hideGestureHint() {
        val hintId = context.resources.getIdentifier("hybridGestureHint", "id", context.packageName)
        val hint = if (hintId != 0) rootView.findViewById<View>(hintId) else null ?: return
        handler.postDelayed({
            hint.animate().alpha(0f).setDuration(600)
                .withEndAction { hint.visibility = View.GONE }.start()
        }, 4000)
    }
}
