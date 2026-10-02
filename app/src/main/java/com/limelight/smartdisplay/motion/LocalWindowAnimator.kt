package com.limelight.smartdisplay.motion

import android.graphics.RectF
import android.view.Choreographer
import java.util.concurrent.ConcurrentHashMap

/**
 * LocalWindowAnimator — Smart-D Window Motion Engine
 *
 * Choreographer.FrameCallback a 60 FPS que anima la posición local optimista
 * de ventanas del PC mientras el stream (~25 ms LAN) llega.
 *
 * SOLID: Responsabilidad única (gestión de frame-loop e interpolación local).
 */
class LocalWindowAnimator(
    private val onFrame: (Map<Int, RectF>) -> Unit
) : Choreographer.FrameCallback {

    private val windows       = ConcurrentHashMap<Int, WindowMotionTarget>()
    private val choreographer = Choreographer.getInstance()
    @Volatile private var running = false

    // ── API pública ───────────────────────────────────────────────────────

    /** Define o actualiza el target de una ventana. */
    fun setTarget(hwnd: Int, x: Float, y: Float, w: Float, h: Float,
                  ease: Float = WindowMotionTarget.EASE_SNAP,
                  initFromCurrent: Boolean = true) {
        val existing = windows[hwnd]
        if (existing != null && initFromCurrent) {
            existing.tx = x; existing.ty = y
            existing.tw = w; existing.th = h
            existing.ease = ease
        } else {
            windows[hwnd] = WindowMotionTarget(
                hwnd = hwnd,
                cx = x, cy = y, cw = w, ch = h,
                tx = x, ty = y, tw = w, th = h,
                ease = ease
            )
        }
        ensureRunning()
    }

    /** Cambia el ease de una ventana activa (ej. de drag a snap al soltar). */
    fun setEase(hwnd: Int, ease: Float) {
        windows[hwnd]?.ease = ease
    }

    /** Aplica ACK "window_moved" del PC para reconciliar la posición real. */
    fun reconcile(hwnd: Int, x: Float, y: Float, w: Float, h: Float) {
        windows[hwnd]?.reconcile(x, y, w, h)
        ensureRunning()
    }

    /** Detiene y elimina la animación de una ventana específica. */
    fun remove(hwnd: Int) { windows.remove(hwnd) }

    /** Limpia todo y detiene el animator. */
    fun clear() { windows.clear(); running = false }

    // ── Choreographer.FrameCallback ───────────────────────────────────────

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        var anyActive = false
        val rects = HashMap<Int, RectF>(windows.size)
        for ((hwnd, wt) in windows) {
            val moving = wt.tick()
            if (moving) anyActive = true
            rects[hwnd] = RectF(wt.cx, wt.cy, wt.cx + wt.cw, wt.cy + wt.ch)
        }
        onFrame(rects)
        if (anyActive) choreographer.postFrameCallback(this)
        else           running = false
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private fun ensureRunning() {
        if (!running) { running = true; choreographer.postFrameCallback(this) }
    }
}
