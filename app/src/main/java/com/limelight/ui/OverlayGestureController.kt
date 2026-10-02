package com.limelight.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * OverlayGestureController — Responsabilidad Única: gestión de gestos táctiles sobre el FAB.
 *
 * Controla:
 *  - Arrastre libre (drag) del FAB.
 *  - Snap magnético al borde lateral más cercano.
 *  - Detección de tap, doble tap y long press vía GestureDetector.
 *  - Clamping dentro de los límites de pantalla.
 */
class OverlayGestureController @SuppressLint("ClickableViewAccessibility") constructor(
    private val fabMain: View,
    private val fabMainWrapper: View?,
    private val overlayRoot: View,
    private val listener: GestureListener?
) {
    companion object {
        private const val PREF_NAME = "fab_overlay_v5"
        private const val KEY_FAB_X = "fab_x"
        private const val KEY_FAB_Y = "fab_y"
 
        private const val DRAG_THRESHOLD_PX = 12f
        private const val TOP_MARGIN_DP     = 24f
        private const val EDGE_MARGIN_DP    = 16f
 
        private val EASING_EMPHASIZED_DECELERATE = PathInterpolator(0.05f, 0.7f, 0.1f, 1.0f)
    }

    interface GestureListener {
        fun onSingleTap()
        fun onDoubleTap()
        fun onLongPress(isDragging: Boolean)
        fun onDragStart()
        fun onDragEnd()
    }

    private val context: Context = fabMain.context
    private val density: Float = context.resources.displayMetrics.density
    private val gestureDetector: GestureDetector

    private var dragStartRawX: Float = 0f
    private var dragStartRawY: Float = 0f
    private var wrapperStartX: Float = 0f
    private var wrapperStartY: Float = 0f
    var isDragging: Boolean = false
        private set

    // Callback para notificar fin del snap (para invalidar cach de geometría)
    var onSnapFinished: Runnable? = null

    init {
        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                listener?.onSingleTap()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                listener?.onDoubleTap()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                listener?.onLongPress(isDragging)
            }
        })

        setupTouchListener()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListener() {
        fabMain.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartRawX = event.rawX
                    dragStartRawY = event.rawY
                    fabMainWrapper?.let { wrapper ->
                        wrapperStartX = wrapper.x
                        wrapperStartY = wrapper.y

                        // Estado: Presionado
                        wrapper.animate()
                            .scaleX(0.95f).scaleY(0.95f)
                            .translationZ(4f * density)
                            .setDuration(90)
                            .setInterpolator(EASING_EMPHASIZED_DECELERATE)
                            .start()
                    }
                    isDragging = false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - dragStartRawX
                    val dy = event.rawY - dragStartRawY
                    if (!isDragging && (abs(dx) > DRAG_THRESHOLD_PX || abs(dy) > DRAG_THRESHOLD_PX)) {
                        isDragging = true
                        listener?.onDragStart()

                        // Estado: Arrastrar
                        fabMainWrapper?.animate()
                            ?.scaleX(1.08f)?.scaleY(1.08f)
                            ?.translationZ(10f * density)
                            ?.setDuration(150)
                            ?.setInterpolator(EASING_EMPHASIZED_DECELERATE)
                            ?.start()
                    }
                    if (isDragging && fabMainWrapper != null) {
                        fabMainWrapper.x = clampX(wrapperStartX + dx)
                        fabMainWrapper.y = clampY(wrapperStartY + dy)
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        clampToScreen()
                        snapToNearestEdge()
                        listener?.onDragEnd()
                    }

                    // Estado: Reposo
                    fabMainWrapper?.animate()
                        ?.scaleX(1f)?.scaleY(1f)
                        ?.translationZ(0f)
                        ?.setDuration(200)
                        ?.setInterpolator(EASING_EMPHASIZED_DECELERATE)
                        ?.start()

                    isDragging = false
                }
            }
            true
        }
    }

    /** Guarda la posición final exactamente donde el usuario la soltó. */
    private fun snapToNearestEdge() {
        val wrapper = fabMainWrapper ?: return

        val targetX = clampX(wrapper.x)
        val targetY = clampY(wrapper.y)

        wrapper.animate()
            .x(targetX)
            .y(targetY)
            .setDuration(120)
            .setInterpolator(EASING_EMPHASIZED_DECELERATE)
            .withEndAction {
                saveFabPosition()
                onSnapFinished?.run()
            }
            .start()
    }

    private fun clampToScreen() {
        val wrapper = fabMainWrapper ?: return
        wrapper.x = clampX(wrapper.x)
        wrapper.y = clampY(wrapper.y)
    }

    private fun clampX(x: Float): Float {
        val wrapper = fabMainWrapper ?: return x
        if (overlayRoot.width == 0) return x
        val margin = EDGE_MARGIN_DP * density
        val wrapperWidth = if (wrapper.width > 0) wrapper.width.toFloat() else 56f * density
        return max(margin, min(overlayRoot.width - wrapperWidth - margin, x))
    }

    private fun clampY(y: Float): Float {
        val wrapper = fabMainWrapper ?: return y
        if (overlayRoot.height == 0) return y
        val topMargin = TOP_MARGIN_DP * density
        val bottomMargin = EDGE_MARGIN_DP * density
        val wrapperHeight = if (wrapper.height > 0) wrapper.height.toFloat() else 56f * density
        return max(topMargin, min(overlayRoot.height - wrapperHeight - bottomMargin, y))
    }

    fun restorePosition() {
        val wrapper = fabMainWrapper ?: return
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val savedX = prefs.getFloat(KEY_FAB_X, -1f)
        val savedY = prefs.getFloat(KEY_FAB_Y, -1f)
        wrapper.post {
            val margin = EDGE_MARGIN_DP * density
            val parentWidth = overlayRoot.width
            val parentHeight = overlayRoot.height
            val wrapperWidth = wrapper.width
            val wrapperHeight = wrapper.height


            if (parentWidth <= 0 || parentHeight <= 0 || wrapperWidth <= 0 || wrapperHeight <= 0) {
                wrapper.postDelayed({ restorePosition() }, 60)
                return@post
            }

            if (savedX >= 0 && savedY >= 0) {
                wrapper.x = clampX(savedX)
                wrapper.y = clampY(savedY)
            } else {
                wrapper.x = (parentWidth - wrapperWidth - margin)
                wrapper.y = (parentHeight - wrapperHeight - margin * 3f)
            }
        }
    }

    fun saveFabPosition() {
        val wrapper = fabMainWrapper ?: return
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_FAB_X, wrapper.x)
            .putFloat(KEY_FAB_Y, wrapper.y)
            .apply()
    }

    /**
     * Re-clampa la posición del FAB dentro de los límites actuales de la pantalla.
     * Útil tras rotación del dispositivo.
     */
    fun reclampPosition() {
        val wrapper = fabMainWrapper ?: return
        if (overlayRoot.width <= 0 || overlayRoot.height <= 0 || wrapper.width <= 0 || wrapper.height <= 0) {
            wrapper.postDelayed({ reclampPosition() }, 50)
            return
        }
        val clampedX = clampX(wrapper.x)
        val clampedY = clampY(wrapper.y)
        wrapper.x = clampedX
        wrapper.y = clampedY
    }

    fun release() {
        fabMain.setOnTouchListener(null)
    }
}
