package com.limelight.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import com.limelight.R
import com.limelight.utils.ThemeManager
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Smart Taskbar - replica VIVA de la barra de tareas del PC en Kotlin.
 */
class SmartTaskbarController(
    private val root: View,
    private val actions: Actions
) {
    interface Actions {
        fun requestWindows()
        fun focus(hwnd: Int)
        fun closeWindow(hwnd: Int)
        fun minimize(hwnd: Int)
        fun snap(hwnd: Int, zone: String)
        fun startDrag(hwnd: Int, winX: Float, winY: Float, winW: Float, winH: Float)
    }

    companion object {
        private const val POLL_MS: Long = 2500L
    }

    private val density: Float = root.resources.displayMetrics.density
    private val accent: Int = ThemeManager.accentColor(root.context)
    private val chips: LinearLayout? = root.findViewById(R.id.taskbarChips)
    private val emptyLabel: View? = root.findViewById(R.id.taskbarEmpty)
    private val body: View? = root.findViewById(R.id.taskbarBody)
    private val minimizeBtn: ImageView? = root.findViewById(R.id.taskbarMinimize)
    private val prefs: SharedPreferences = root.context.getSharedPreferences("smartdisplay_taskbar", Context.MODE_PRIVATE)
    private val handler: Handler = Handler(Looper.getMainLooper())

        var isVisible: Boolean = false
        private set
    private var minimized: Boolean = false
    private var lastSignature: String? = null

    private var dragStartRawX: Float = 0f
    private var dragStartRawY: Float = 0f
    private var viewStartX: Float = 0f
    private var viewStartY: Float = 0f
    private var dragging: Boolean = false
    private var positionRestored: Boolean = false

    private val poll: Runnable = object : Runnable {
        override fun run() {
            if (!isVisible || minimized) return
            actions.requestWindows()
            handler.postDelayed(this, POLL_MS)
        }
    }

    init {
        val close: View? = root.findViewById(R.id.taskbarClose)
        close?.setOnClickListener { hide() }
        minimizeBtn?.setOnClickListener { toggleMinimize() }
        applyAccentToPanel()
        setupDrag()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrag() {
        val grip: View? = root.findViewById(R.id.taskbarGrip) ?: return
        grip?.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartRawX = event.rawX
                    dragStartRawY = event.rawY
                    viewStartX = root.x
                    viewStartY = root.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - dragStartRawX
                    val dy = event.rawY - dragStartRawY
                    if (!dragging && (abs(dx) > 8 || abs(dy) > 8)) dragging = true
                    if (dragging) {
                        root.x = clamp(viewStartX + dx, true)
                        root.y = clamp(viewStartY + dy, false)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) savePosition()
                    true
                }
                else -> false
            }
        }
    }

    private fun clamp(value: Float, horizontal: Boolean): Float {
        val parent = root.parent as? View ?: return value
        val maxBound = if (horizontal) {
            max(0, parent.width - root.width).toFloat()
        } else {
            max(0, parent.height - root.height).toFloat()
        }
        return max(0f, min(value, maxBound))
    }

    private fun savePosition() {
        prefs.edit().putFloat("tb_x", root.x).putFloat("tb_y", root.y).apply()
    }

    private fun restorePosition() {
        val x = prefs.getFloat("tb_x", -1f)
        val y = prefs.getFloat("tb_y", -1f)
        if (x >= 0 && y >= 0) {
            root.post {
                root.x = clamp(x, true)
                root.y = clamp(y, false)
            }
        }
    }

    private fun applyAccentToPanel() {
        val bg = root.background
        if (bg is LayerDrawable) {
            val base = bg.getDrawable(0)
            if (base is GradientDrawable) {
                val soft = (accent and 0x00FFFFFF) or 0x66000000
                (base.mutate() as GradientDrawable).setStroke((1f * density).roundToInt(), soft)
            }
        }
    }

    fun toggle() {
        if (isVisible) hide() else show()
    }

    private fun toggleMinimize() {
        val b = body ?: return
        minimized = !minimized
        minimizeBtn?.setImageResource(if (minimized) R.drawable.ic_expand_panel else R.drawable.ic_minimize)

        if (minimized) {
            handler.removeCallbacks(poll)
            b.pivotY = 0f
            b.animate().alpha(0f).scaleY(0.85f).setDuration(150)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction { b.visibility = View.GONE }
                .start()
        } else {
            b.visibility = View.VISIBLE
            b.pivotY = 0f
            b.alpha = 0f
            b.scaleY = 0.85f
            b.animate().alpha(1f).scaleY(1f).setDuration(190)
                .setInterpolator(DecelerateInterpolator())
                .start()
            actions.requestWindows()
            handler.removeCallbacks(poll)
            handler.postDelayed(poll, POLL_MS)
        }
    }

    fun show() {
        isVisible = true
        if (minimized) {
            minimized = false
            body?.apply {
                visibility = View.VISIBLE
                alpha = 1f
                scaleY = 1f
            }
            minimizeBtn?.setImageResource(R.drawable.ic_minimize)
        }
        root.visibility = View.VISIBLE
        root.alpha = 0f
        root.scaleX = 0.92f
        root.scaleY = 0.92f
        root.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(220)
            .setInterpolator(DecelerateInterpolator())
            .start()

        if (!positionRestored) {
            positionRestored = true
            restorePosition()
        }
        actions.requestWindows()
        handler.removeCallbacks(poll)
        handler.postDelayed(poll, POLL_MS)
    }

    fun hide() {
        isVisible = false
        handler.removeCallbacks(poll)
        root.animate().alpha(0f).scaleX(0.92f).scaleY(0.92f)
            .setDuration(160)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                root.visibility = View.GONE
                root.alpha = 1f
                root.scaleX = 1f
                root.scaleY = 1f
            }
            .start()
    }

    fun release() {
        handler.removeCallbacks(poll)
    }

    fun render(windows: JSONArray?) {
        if (!isVisible || minimized) return
        val count = windows?.length() ?: 0

        val signature = buildSignature(windows, count)
        if (signature == lastSignature) return
        lastSignature = signature

        chips?.removeAllViews()
        emptyLabel?.visibility = if (count == 0) View.VISIBLE else View.GONE
        for (i in 0 until count) {
            val w = windows?.optJSONObject(i) ?: continue
            val hwnd = w.optInt("hwnd", 0)
            val title = w.optString("title", "")
            val isWinMinimized = w.optBoolean("minimized", false)
            val rect = w.optJSONObject("rect")
            if (hwnd == 0 || title.isEmpty()) continue
            chips?.addView(buildChip(hwnd, title, isWinMinimized, rect))
        }
    }

    private fun buildSignature(windows: JSONArray?, count: Int): String {
        if (count == 0 || windows == null) return ""
        val sb = StringBuilder(count * 24)
        for (i in 0 until count) {
            val w = windows.optJSONObject(i) ?: continue
            sb.append(w.optInt("hwnd", 0)).append(':')
                .append(if (w.optBoolean("minimized", false)) '1' else '0').append(':')
                .append(w.optString("title", "")).append('|')
        }
        return sb.toString()
    }

    private fun buildChip(hwnd: Int, title: String, minimized: Boolean, rect: JSONObject?): TextView {
        val chip = TextView(root.context)
        chip.text = if (title.length > 22) title.substring(0, 21) + "â€¦" else title
        chip.isSingleLine = true
        chip.setTextColor(-0x1)
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        chip.gravity = Gravity.CENTER_VERTICAL
        chip.setBackgroundResource(R.drawable.taskbar_chip_bg)

        val cbg = chip.background
        if (cbg is GradientDrawable) {
            (cbg.mutate() as GradientDrawable).setStroke((1.5f * density).roundToInt(), accent)
        }
        val ph = dp(12f)
        val pv = dp(8f)
        chip.setPadding(ph, pv, ph, pv)
        chip.alpha = if (minimized) 0.5f else 1f

        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.marginEnd = dp(8f)
        chip.layoutParams = lp

        chip.setOnClickListener { actions.focus(hwnd) }
        chip.setOnLongClickListener { v ->
            val menu = PopupMenu(root.context, v)
            menu.menu.add(0, 10, 0, "ðŸ”² Maximizar")
            menu.menu.add(0, 11, 1, "â—§ Acoplar a la izquierda")
            menu.menu.add(0, 12, 2, "â—¨ Acoplar a la derecha")
            if (rect != null) {
                menu.menu.add(0, 13, 3, "ðŸ–ï¸ Mover con gesto tÃ¡ctil")
            }
            menu.menu.add(0, 1, 4, R.string.taskbar_minimize)
            menu.menu.add(0, 2, 5, R.string.taskbar_close_win)
            menu.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> actions.minimize(hwnd)
                    2 -> actions.closeWindow(hwnd)
                    10 -> actions.snap(hwnd, "maximize")
                    11 -> actions.snap(hwnd, "left_half")
                    12 -> actions.snap(hwnd, "right_half")
                    13 -> if (rect != null) {
                        val rx = rect.optDouble("x", 0.0).toFloat()
                        val ry = rect.optDouble("y", 0.0).toFloat()
                        val rw = rect.optDouble("w", 800.0).toFloat()
                        val rh = rect.optDouble("h", 600.0).toFloat()
                        actions.startDrag(hwnd, rx, ry, rw, rh)
                        hide()
                    }
                }
                true
            }
            menu.show()
            true
        }
        return chip
    }

    private var preKeyboardY: Float = -1f

    fun onKeyboardVisibilityChanged(visible: Boolean, keyboardTopY: Int) {
        if (!isVisible) return
        if (visible && keyboardTopY > 0) {
            val rootH = if (root.height > 0) root.height else (48 * density).toInt()
            val currentY = root.y
            if (currentY + rootH > keyboardTopY) {
                if (preKeyboardY < 0f) preKeyboardY = currentY
                val targetY = max(16 * density, (keyboardTopY - rootH - (12 * density)))
                root.animate()
                    .y(targetY)
                    .setDuration(240)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        } else if (!visible && preKeyboardY >= 0f) {
            root.animate()
                .y(preKeyboardY)
                .setDuration(220)
                .setInterpolator(DecelerateInterpolator())
                .start()
            preKeyboardY = -1f
        }
    }

    private fun dp(v: Float): Int = (v * density).roundToInt()
}
