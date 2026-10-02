package com.limelight.smartdisplay.motion

import android.os.SystemClock
import android.view.MotionEvent

/**
 * WindowDragGestureHandler — Single Responsibility & Dependency Inversion (SOLID)
 *
 * Responsable únicamente del reconocimiento del gesto de arrastre táctil
 * y el despacho con throttling (~30 Hz) al [WindowMotionTransport].
 *
 * No tiene dependencias de vistas directas ni del protocolo de red concreto.
 */
class WindowDragGestureHandler(
    private val transport:   WindowMotionTransport,
    private val transformer: CoordinateTransformer,
    private val animator:    LocalWindowAnimator
) {
    companion object {
        private const val MIN_SEND_MS   = 33L    // ~30 Hz hacia el host
        private const val DRAG_SLOP_PX  = 8f     // Umbral de inicio de arrastre
        private const val SNAP_EDGE_PX  = 40f    // Zona sensible en bordes de pantalla
    }

    // Estado interno del gesto
    private var activeHwnd   = 0
    private var dragging     = false
    private var downX        = 0f
    private var downY        = 0f
    private var windowStartX = 0f
    private var windowStartY = 0f
    private var currentW     = 0f
    private var currentH     = 0f
    private var lastSendAt   = 0L

    /**
     * Inicia el modo de arrastre sobre una ventana con sus dimensiones de origen (en px del PC).
     */
    fun startDrag(hwnd: Int, winX: Float, winY: Float, winW: Float, winH: Float) {
        activeHwnd   = hwnd
        windowStartX = winX
        windowStartY = winY
        currentW     = winW
        currentH     = winH
        dragging     = false
    }

    /** Procesa un evento táctil. Retorna true si fue consumido por este handler. */
    fun onTouchEvent(event: MotionEvent, viewWidth: Float, viewHeight: Float): Boolean {
        if (activeHwnd == 0) return false

        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY

                if (!dragging) {
                    if (dx * dx + dy * dy > DRAG_SLOP_PX * DRAG_SLOP_PX) {
                        dragging = true
                    } else return false
                }

                val (pcX, pcY) = transformer.androidToPc(event.x, event.y)
                val (downPcX, downPcY) = transformer.androidToPc(downX, downY)
                val targetPcX = windowStartX + (pcX - downPcX)
                val targetPcY = windowStartY + (pcY - downPcY)

                // Actualizar la representación local optimista inmediatamente (ease drag: 0.65)
                val (lx, ly) = transformer.pcToAndroid(targetPcX, targetPcY)
                val (lw, lh) = transformer.pcToAndroid(currentW, currentH)
                animator.setTarget(activeHwnd, lx, ly, lw, lh, WindowMotionTarget.EASE_DRAG)

                // Enviar coordenadas al host PC con limitador de tasa (~30 Hz)
                val now = SystemClock.elapsedRealtime()
                if (now - lastSendAt >= MIN_SEND_MS) {
                    lastSendAt = now
                    transport.sendMoveWindow(
                        activeHwnd,
                        targetPcX.toInt(), targetPcY.toInt(),
                        currentW.toInt(), currentH.toInt(),
                        "drag"
                    )
                }
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    val snapZone = detectSnapZone(event.x, event.y, viewWidth, viewHeight)
                    if (snapZone != null) {
                        transport.sendSnapWindow(activeHwnd, snapZone)
                    } else {
                        transport.sendWindowDragEnd(activeHwnd)
                    }
                    animator.setEase(activeHwnd, WindowMotionTarget.EASE_SNAP)
                }
                cancel()
                true
            }
            else -> false
        }
    }

    fun cancel() {
        activeHwnd = 0
        dragging   = false
    }

    val isDragging: Boolean get() = dragging

    private fun detectSnapZone(x: Float, y: Float, width: Float, height: Float): String? {
        val edge = SNAP_EDGE_PX
        return when {
            x < edge && y < edge               -> "top_left_q"
            x > width - edge && y < edge       -> "top_right_q"
            x < edge && y > height - edge      -> "bottom_left_q"
            x > width - edge && y > height - edge -> "bottom_right_q"
            x < edge                           -> "left_half"
            x > width - edge                   -> "right_half"
            y < edge                           -> "maximize"
            else                               -> null
        }
    }
}
