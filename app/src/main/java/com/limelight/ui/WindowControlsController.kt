package com.limelight.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import com.limelight.R
import com.limelight.nvstream.NvConnection
import com.limelight.nvstream.input.KeyboardPacket
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * WindowControlsController â€” SmartDisplay AI Premium
 * â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
 * Controla la barra de ventanas Windows flotante (cÃ¡psula).
 * Soporta arrastre (draggable), snap magnÃ©tico, persistencia y auto-shrink.
 */
class WindowControlsController(
    private val barView: View,
    private val context: Context
) {
    companion object {
        private const val GFE_EXT: Short = 0x8000.toShort()
        private const val VK_LWIN: Short = (0x8000 or 0x5B).toShort()
        private const val VK_MENU: Short = (0x8000 or 0x12).toShort() // Alt
        private const val VK_CONTROL: Short = (0x8000 or 0x11).toShort() // Ctrl
        private const val VK_TAB: Short = (0x8000 or 0x09).toShort()
        private const val VK_F4: Short = (0x8000 or 0x73).toShort()
        private const val VK_UP: Short = (0x8000 or 0x26).toShort()
        private const val VK_DOWN: Short = (0x8000 or 0x28).toShort()
        private const val VK_T: Short = (0x8000 or 0x54).toShort()
        private const val VK_W: Short = (0x8000 or 0x57).toShort()
        private const val VK_D: Short = (0x8000 or 0x44).toShort()

        private const val SHRINK_DELAY_MS = 4000L
        private const val SHRINK_SCALE = 0.38f
        private const val SHRINK_ALPHA = 0.55f
        private const val SHRINK_DUR_MS = 350L
        private const val WAKE_DUR_MS = 220L
    }

    private var isShrunk = false
    private var toolsExpanded = false
    private var vertical = false

    private val macroLock = Any()
    private val capsuleContent: View? = barView.findViewById(R.id.windowControlsContent)
    private val prefs: SharedPreferences = context.getSharedPreferences("smartdisplay_wc", Context.MODE_PRIVATE)
    private var conn: NvConnection? = null
    private var streamTransformCtrl: StreamViewTransformController? = null

    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var wrapperStartX = 0f
    private var wrapperStartY = 0f
    private var dragging = false
    private val density: Float = context.resources.displayMetrics.density

    private val shrinkHandler = Handler(Looper.getMainLooper())
    private val shrinkRunnable = Runnable { shrinkCapsule() }

    init {
        vertical = prefs.getBoolean("wc_vertical", false)

        barView.post {
            if (vertical) applyOrientation(true, false)
            adjustCarouselWidth()
            restorePosition()
            resetShrinkTimer()
        }

        barView.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if ((r - l) != (or - ol) || (b - t) != (ob - ot)) {
                clampIntoParent(false)
            }
        }

        bindButtons()
        setupDrag()
    }

    fun setConnection(conn: NvConnection?) {
        this.conn = conn
    }

    fun setStreamTransformController(ctrl: StreamViewTransformController?) {
        this.streamTransformCtrl = ctrl
        ctrl?.addOnMoveModeChangedListener { active ->
            updateMoveButtonState(active)
        }
    }

    fun updateMoveButtonState(active: Boolean) {
        val btn = barView.findViewById<android.widget.ImageView>(R.id.btnVpMove) ?: return
        if (active) {
            btn.setColorFilter(com.limelight.utils.ThemeManager.accentColor(context))
            btn.animate().scaleX(1.15f).scaleY(1.15f).setDuration(150).start()
        } else {
            btn.setColorFilter(0xFFFFFFFF.toInt())
            btn.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
        }
    }

    fun onTouchEvent(event: MotionEvent): Boolean = false

    fun isVisible(): Boolean = barView.visibility == View.VISIBLE

    fun release() {
        shrinkHandler.removeCallbacksAndMessages(null)
    }

    fun onOrientationChanged() {
        barView.post {
            adjustCarouselWidth()
            clampIntoParent(true)
            if (isShrunk) wakeCapsule()
            resetShrinkTimer()
        }
    }

    private fun toggleOrientation() {
        applyOrientation(!vertical, true)
        wakeCapsule()
        resetShrinkTimer()
    }

    private fun applyOrientation(v: Boolean, save: Boolean) {
        vertical = v
        val o = if (v) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL

        val content = capsuleContent
        if (content is LinearLayout) {
            content.orientation = o
            val clp = content.layoutParams
            clp.width = ViewGroup.LayoutParams.WRAP_CONTENT
            clp.height = if (v) ViewGroup.LayoutParams.WRAP_CONTENT else (48 * density).toInt()
            content.layoutParams = clp
        }

        val toolsV = barView.findViewById<View>(R.id.windowControlsTools)
        if (toolsV is LinearLayout) toolsV.orientation = o

        val scroll = barView.findViewById<View>(R.id.windowControlsScroll)
        if (scroll != null) {
            val slp = scroll.layoutParams
            slp.width = ViewGroup.LayoutParams.WRAP_CONTENT
            slp.height = if (v) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT
            scroll.layoutParams = slp
        }

        val side = (48 * density).toInt()
        val ids = intArrayOf(
            R.id.windowControlsGrip, R.id.btnWinMinimize, R.id.btnWinMaximize,
            R.id.btnWinClose, R.id.btnWinAltTab, R.id.btnWinCtrlTab,
            R.id.btnVpZoomIn, R.id.btnVpZoomOut, R.id.btnVpFit,
            R.id.btnVpMove, R.id.btnVpCenter,
            R.id.btnWinNewTab, R.id.btnWinCloseTab, R.id.btnWinDesktop,
            R.id.btnWinTaskView, R.id.btnWinAddTools
        )
        for (id in ids) {
            val b = barView.findViewById<View>(id) ?: continue
            val blp = b.layoutParams
            blp.width = side
            blp.height = if (v) side else ViewGroup.LayoutParams.MATCH_PARENT
            b.layoutParams = blp
        }

        if (save) prefs.edit().putBoolean("wc_vertical", v).apply()
        barView.post {
            if (!v) adjustCarouselWidth()
            clampIntoParent(true)
        }
    }

    private fun clampIntoParent(save: Boolean) {
        val parent = barView.parent as? View ?: return
        if (parent.width == 0) return

        val maxX = max(0f, (parent.width - barView.width).toFloat())
        val maxY = max(0f, (parent.height - barView.height).toFloat())

        var nx = max(0f, min(barView.x, maxX))
        var ny = max(0f, min(barView.y, maxY))

        val avoidW = 80 * density
        val avoidH = 80 * density
        if (nx < avoidW && ny < avoidH) {
            if (maxX - avoidW > avoidW) {
                nx = avoidW
            } else {
                ny = avoidH
            }
        }

        if (nx != barView.x || ny != barView.y) {
            barView.x = nx
            barView.y = ny
            if (save) savePosition()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrag() {
        var dragHandle = barView.findViewById<View>(R.id.windowControlsGrip)
        if (dragHandle == null) dragHandle = capsuleContent
        if (dragHandle == null) return

        dragHandle.setOnLongClickListener {
            toggleOrientation()
            true
        }

        dragHandle.setOnTouchListener { _, event ->
            if (isShrunk) {
                wakeCapsule()
            }
            resetShrinkTimer()

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartRawX = event.rawX
                    dragStartRawY = event.rawY
                    wrapperStartX = barView.x
                    wrapperStartY = barView.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - dragStartRawX
                    val dy = event.rawY - dragStartRawY
                    if (!dragging && (abs(dx) > 10 || abs(dy) > 10)) {
                        dragging = true
                    }
                    if (dragging) {
                        barView.x = wrapperStartX + dx
                        barView.y = wrapperStartY + dy
                    }
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        snapToEdges()
                    }
                }
            }
            false
        }
    }

    private fun adjustCarouselWidth() {
        val scroll = barView.findViewById<View>(R.id.windowControlsScroll) ?: return
        val tools = barView.findViewById<View>(R.id.windowControlsTools) ?: return
        val parent = barView.parent as? View ?: return
        if (parent.width == 0) return

        val reserve = (96 * density).toInt()
        val maxW = max((120 * density).toInt(), parent.width - reserve)
        val contentW = tools.width
        val lp = scroll.layoutParams
        val desired = if (contentW > maxW) maxW else ViewGroup.LayoutParams.WRAP_CONTENT
        if (lp.width != desired) {
            lp.width = desired
            scroll.layoutParams = lp
        }
    }

    private fun snapToEdges() {
        val parent = barView.parent as? View ?: return

        val snapDistance = 32 * density
        val x = barView.x
        val y = barView.y
        var targetX = x
        var targetY = y

        val parentW = parent.width.toFloat()
        val parentH = parent.height.toFloat()
        val w = barView.width.toFloat()
        val h = barView.height.toFloat()

        if (x < snapDistance) targetX = 16 * density
        else if (x + w > parentW - snapDistance) targetX = parentW - w - (16 * density)
        else if (abs(x + w / 2 - parentW / 2) < snapDistance * 2) targetX = parentW / 2 - w / 2

        if (y < snapDistance) targetY = 16 * density
        else if (y + h > parentH - snapDistance) targetY = parentH - h - (16 * density)

        val avoidW = 80 * density
        val avoidH = 80 * density
        if (targetX < avoidW && targetY < avoidH) {
            targetX = avoidW
        }

        targetX = max(0f, min(targetX, parentW - w))
        targetY = max(0f, min(targetY, parentH - h))

        barView.animate()
            .x(targetX)
            .y(targetY)
            .setDuration(280)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { savePosition() }
            .start()
    }

    private fun savePosition() {
        prefs.edit()
            .putFloat("wc_x", barView.x)
            .putFloat("wc_y", barView.y)
            .apply()
    }

    private fun restorePosition() {
        val savedX = prefs.getFloat("wc_x", -1f)
        val savedY = prefs.getFloat("wc_y", -1f)
        if (savedX >= 0 && savedY >= 0) {
            barView.x = savedX
            barView.y = savedY
            clampIntoParent(true)
        } else {
            barView.post {
                val parent = barView.parent as? View
                if (parent != null && parent.width > 0) {
                    val defaultX = max(0f, (parent.width - barView.width) / 2f)
                    val defaultY = 16 * density
                    barView.x = defaultX
                    barView.y = defaultY
                    savePosition()
                }
            }
        }
    }

    private fun resetShrinkTimer() {
        shrinkHandler.removeCallbacks(shrinkRunnable)
        shrinkHandler.postDelayed(shrinkRunnable, SHRINK_DELAY_MS)
    }

    private fun shrinkCapsule() {
        if (isShrunk) return
        isShrunk = true
        barView.pivotX = barView.width / 2f
        barView.pivotY = barView.height / 2f
        barView.animate()
            .scaleX(SHRINK_SCALE)
            .scaleY(SHRINK_SCALE)
            .alpha(SHRINK_ALPHA)
            .setDuration(SHRINK_DUR_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun wakeCapsule() {
        if (!isShrunk) return
        isShrunk = false
        barView.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(WAKE_DUR_MS)
            .setInterpolator(OvershootInterpolator(1.5f))
            .start()
    }

    private fun bindButtons() {
        val btnMin = barView.findViewById<View>(R.id.btnWinMinimize)
        val btnMax = barView.findViewById<View>(R.id.btnWinMaximize)
        val btnClose = barView.findViewById<View>(R.id.btnWinClose)
        val btnAltTab = barView.findViewById<View>(R.id.btnWinAltTab)
        val btnCtrlTab = barView.findViewById<View>(R.id.btnWinCtrlTab)
        val btnVpZoomIn = barView.findViewById<View>(R.id.btnVpZoomIn)
        val btnVpZoomOut = barView.findViewById<View>(R.id.btnVpZoomOut)
        val btnVpFit = barView.findViewById<View>(R.id.btnVpFit)
        val btnVpMove = barView.findViewById<View>(R.id.btnVpMove)
        val btnVpCenter = barView.findViewById<View>(R.id.btnVpCenter)
        val btnAddTools = barView.findViewById<View>(R.id.btnWinAddTools)
        val btnNewTab = barView.findViewById<View>(R.id.btnWinNewTab)
        val btnCloseTab = barView.findViewById<View>(R.id.btnWinCloseTab)
        val btnDesktop = barView.findViewById<View>(R.id.btnWinDesktop)
        val btnTaskView = barView.findViewById<View>(R.id.btnWinTaskView)

        val listener = View.OnClickListener { v ->
            if (isShrunk) {
                wakeCapsule()
                resetShrinkTimer()
                return@OnClickListener
            }
            if (dragging) return@OnClickListener
            animateButton(v)
            resetShrinkTimer()

            when (v.id) {
                R.id.btnWinMinimize -> sendMacro(VK_LWIN, VK_DOWN)
                R.id.btnWinMaximize -> sendMacro(VK_LWIN, VK_UP)
                R.id.btnWinClose -> sendMacro(VK_MENU, VK_F4)
                R.id.btnWinAltTab -> sendMacro(VK_MENU, VK_TAB)
                R.id.btnWinCtrlTab -> sendMacro(VK_CONTROL, VK_TAB)
                R.id.btnVpZoomIn -> {
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    streamTransformCtrl?.zoomIn()
                }
                R.id.btnVpZoomOut -> {
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    streamTransformCtrl?.zoomOut()
                }
                R.id.btnVpFit -> {
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    streamTransformCtrl?.resetViewport()
                }
                R.id.btnVpMove -> {
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                    val active = streamTransformCtrl?.toggleMoveMode() ?: false
                    updateMoveButtonState(active)
                }
                R.id.btnVpCenter -> {
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    streamTransformCtrl?.centerViewport()
                }
                R.id.btnWinNewTab -> sendMacro(VK_CONTROL, VK_T)
                R.id.btnWinCloseTab -> sendMacro(VK_CONTROL, VK_W)
                R.id.btnWinDesktop -> sendMacro(VK_LWIN, VK_D)
                R.id.btnWinTaskView -> sendMacro(VK_LWIN, VK_TAB)
            }
        }

        btnMin?.setOnClickListener(listener)
        btnMax?.setOnClickListener(listener)
        btnClose?.setOnClickListener(listener)
        btnAltTab?.setOnClickListener(listener)
        btnCtrlTab?.setOnClickListener(listener)
        btnVpZoomIn?.setOnClickListener(listener)
        btnVpZoomOut?.setOnClickListener(listener)
        btnVpFit?.setOnClickListener(listener)
        btnVpMove?.setOnClickListener(listener)
        btnVpCenter?.setOnClickListener(listener)
        btnNewTab?.setOnClickListener(listener)
        btnCloseTab?.setOnClickListener(listener)
        btnDesktop?.setOnClickListener(listener)
        btnTaskView?.setOnClickListener(listener)

        btnAddTools?.setOnClickListener { v ->
            if (isShrunk) {
                wakeCapsule()
                resetShrinkTimer()
                return@setOnClickListener
            }
            if (dragging) return@setOnClickListener
            animateButton(v)
            resetShrinkTimer()
            toolsExpanded = !toolsExpanded
            val vis = if (toolsExpanded) View.VISIBLE else View.GONE
            btnNewTab?.visibility = vis
            btnCloseTab?.visibility = vis
            btnDesktop?.visibility = vis
            btnTaskView?.visibility = vis
            barView.post {
                adjustCarouselWidth()
                barView.post { snapToEdges() }
            }
        }
    }

    private fun sendMacro(modKey: Short, mainKey: Short) {
        val c = conn ?: return
        synchronized(macroLock) {
            try {
                c.sendKeyboardInput(modKey, KeyboardPacket.KEY_DOWN, 0.toByte(), 0.toByte())
                c.sendKeyboardInput(mainKey, KeyboardPacket.KEY_DOWN, 0.toByte(), 0.toByte())
                c.sendKeyboardInput(mainKey, KeyboardPacket.KEY_UP, 0.toByte(), 0.toByte())
                c.sendKeyboardInput(modKey, KeyboardPacket.KEY_UP, 0.toByte(), 0.toByte())
            } catch (ignored: Exception) {}
        }
    }

    private fun animateButton(v: View) {
        v.animate()
            .scaleX(0.85f).scaleY(0.85f)
            .setDuration(80)
            .withEndAction {
                v.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(120)
                    .setInterpolator(OvershootInterpolator(2f))
                    .start()
            }
            .start()
    }
}

