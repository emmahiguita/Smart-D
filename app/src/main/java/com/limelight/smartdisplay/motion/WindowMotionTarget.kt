package com.limelight.smartdisplay.motion

import kotlin.math.abs

/**
 * WindowMotionTarget — Smart-D Window Motion Engine
 *
 * Posición local optimista de una ventana del PC.
 * [LocalWindowAnimator] la consume frame a frame para animar el overlay
 * mientras el stream de Sunshine aún no propagó el movimiento real.
 *
 * Fórmula: current += (target - current) × ease
 *   ease 0.65 → drag (pegado al dedo)
 *   ease 0.35 → snap/aterrizaje suave
 */
data class WindowMotionTarget(
    val hwnd: Int,
    var cx: Float, var cy: Float, var cw: Float, var ch: Float,  // current
    var tx: Float, var ty: Float, var tw: Float, var th: Float,  // target
    var ease: Float = EASE_SNAP,
    var isDragging: Boolean = false
) {
    companion object {
        const val EASE_DRAG   = 0.65f
        const val EASE_SNAP   = 0.35f
        const val STOP_THRESH = 0.5f
    }

    /** Avanza un frame. @return true si sigue en movimiento. */
    fun tick(): Boolean {
        cx += (tx - cx) * ease
        cy += (ty - cy) * ease
        cw += (tw - cw) * ease
        ch += (th - ch) * ease
        val converged = abs(tx - cx) < STOP_THRESH &&
                        abs(ty - cy) < STOP_THRESH &&
                        abs(tw - cw) < STOP_THRESH &&
                        abs(th - ch) < STOP_THRESH
        if (converged) { cx = tx; cy = ty; cw = tw; ch = th }
        return !converged
    }

    /**
     * Reconciliación desde ACK "window_moved" del PC.
     * Si el delta es pequeño, fuerza convergencia para evitar micro-jitter.
     */
    fun reconcile(sx: Float, sy: Float, sw: Float, sh: Float) {
        tx = sx; ty = sy; tw = sw; th = sh
        if (abs(cx - sx) < 4f && abs(cy - sy) < 4f &&
            abs(cw - sw) < 4f && abs(ch - sh) < 4f) {
            cx = sx; cy = sy; cw = sw; ch = sh
        }
    }
}
