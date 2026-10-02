package com.limelight.ui.viewport

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Rect
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.annotation.UiThread
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * DesktopViewportEngine - Motor central del viewport de escritorio en Kotlin.
 *
 * Responsabilidad UNICA: gestionar la transformacion visual (scale + translate)
 * del wrapper del stream, teniendo en cuenta el area disponible despues de
 * descontar overlays (teclado, taskbar, FAB, etc.).
 */
@UiThread
class DesktopViewportEngine(private val targetView: View) {

    companion object {
        const val MIN_SCALE: Float = 0.5f
        const val MAX_SCALE: Float = 5.0f
        const val SNAP_THRESHOLD: Float = 1.05f
        private const val ANIM_DURATION: Long = 330L
    }

    enum class ViewportMode { FIT, FILL, PIXEL_PERFECT, CUSTOM }

    var scale: Float = 1.0f
        private set
    var translateX: Float = 0f
        private set
    var translateY: Float = 0f
        private set

    var currentMode: ViewportMode = ViewportMode.FIT
        private set

    private var streamWidth: Int = 1920
    private var streamHeight: Int = 1080

    private var availableViewportRect: Rect? = null
    private var activeAnimator: ValueAnimator? = null

    fun interface OnViewportChangedListener {
        fun onScaleChanged(scale: Float, mode: ViewportMode)
    }

    private val listeners = CopyOnWriteArrayList<OnViewportChangedListener>()

    fun addListener(listener: OnViewportChangedListener?) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: OnViewportChangedListener?) {
        if (listener != null) {
            listeners.remove(listener)
        }
    }

    @Deprecated("Use addListener instead")
    fun setOnViewportChangedListener(l: OnViewportChangedListener?) {
        if (l != null) {
            addListener(l)
        }
    }

    fun setStreamResolution(width: Int, height: Int) {
        this.streamWidth = width
        this.streamHeight = height
    }

    fun setAvailableViewportRect(rect: Rect?) {
        this.availableViewportRect = rect?.let { Rect(it) }
        clampTranslation()
        applyTransform()
    }

    /** Aplica un nuevo factor de escala manteniendo el punto focal fijo. */
    fun applyFocalScale(newScale: Float, focalX: Float, focalY: Float) {
        cancelAnimation()

        val prevScale = scale
        scale = max(MIN_SCALE, min(MAX_SCALE, newScale))

        if (prevScale > 0.001f) {
            val viewW = getViewWidth()
            val viewH = getViewHeight()
            val pivotX = viewW / 2f
            val pivotY = viewH / 2f

            val scaleRatio = scale / prevScale
            translateX = focalX - pivotX - (focalX - pivotX - translateX) * scaleRatio
            translateY = focalY - pivotY - (focalY - pivotY - translateY) * scaleRatio
        }

        currentMode = ViewportMode.CUSTOM
        clampTranslation()
        applyTransform()
        notifyScaleChanged()
    }

    /** Desplaza el viewport por delta (dx, dy). */
    fun panBy(dx: Float, dy: Float) {
        translateX += dx
        translateY += dy
        clampTranslation()
        applyTransform()
    }

    /** Establece un modo de viewport predefinido con animacion. */
    fun setViewportMode(mode: ViewportMode) {
        when (mode) {
            ViewportMode.FIT -> animateToViewport(1.0f, 0f, 0f)
            ViewportMode.PIXEL_PERFECT -> {
                val ppScale = calculatePixelPerfectScale()
                animateToViewport(ppScale, 0f, 0f)
            }
            ViewportMode.FILL -> {
                val fillScale = calculateFillScale()
                animateToViewport(fillScale, 0f, 0f)
            }
            ViewportMode.CUSTOM -> {}
        }
        currentMode = mode
    }

    /** AmpliaciÃ³n suave centrada del viewport. */
    fun zoomIn(factor: Float = 1.25f) {
        val targetScale = max(MIN_SCALE, min(MAX_SCALE, scale * factor))
        animateToViewport(targetScale, translateX, translateY)
        currentMode = ViewportMode.CUSTOM
    }

    /** ReducciÃ³n suave centrada del viewport. */
    fun zoomOut(factor: Float = 1.25f) {
        val targetScale = max(MIN_SCALE, min(MAX_SCALE, scale / factor))
        animateToViewport(targetScale, translateX, translateY)
        currentMode = ViewportMode.CUSTOM
    }

    /** Restablece la vista a escala natural 1.0x centrada. */
    fun resetViewport() {
        currentMode = ViewportMode.FIT
        animateToViewport(1.0f, 0f, 0f)
    }

    /** Centra la posiciÃ³n de la pantalla manteniendo el nivel de zoom actual. */
    fun centerViewport() {
        animateToViewport(scale, 0f, 0f)
    }

    /** Toggle rapido entre FIT y 150% (para doble-tap). */
    fun toggleFitZoom() {
        if (isAtDefaultScale()) {
            currentMode = ViewportMode.CUSTOM
            animateToViewport(1.5f, 0f, 0f)
        } else {
            setViewportMode(ViewportMode.FIT)
        }
    }

    /** Asegura que un punto sea visible en pantalla. */
    fun ensurePointVisible(desktopX: Float, desktopY: Float, marginPx: Float) {
        val viewW = getViewWidth()
        val viewH = getViewHeight()
        if (viewW <= 0 || viewH <= 0) return

        val pivotX = viewW / 2f
        val pivotY = viewH / 2f

        val screenX = pivotX + (desktopX - pivotX) * scale + translateX
        val screenY = pivotY + (desktopY - pivotY) * scale + translateY

        val left: Float
        val top: Float
        val right: Float
        val bottom: Float

        val avail = availableViewportRect
        if (avail != null) {
            left = avail.left + marginPx
            top = avail.top + marginPx
            right = avail.right - marginPx
            bottom = avail.bottom - marginPx
        } else {
            left = marginPx
            top = marginPx
            right = viewW - marginPx
            bottom = viewH - marginPx
        }

        var panX = 0f
        var panY = 0f

        if (screenX < left) panX = left - screenX
        else if (screenX > right) panX = right - screenX

        if (screenY < top) panY = top - screenY
        else if (screenY > bottom) panY = bottom - screenY

        if (panX != 0f || panY != 0f) {
            translateX += panX
            translateY += panY
            clampTranslation()
            applyTransform()
        }
    }

    /** Anima suavemente a un viewport objetivo. */
    fun animateToViewport(targetScale: Float, targetTx: Float, targetTy: Float) {
        cancelAnimation()

        val fromScale = scale
        val fromTx = translateX
        val fromTy = translateY
        val toScale = max(MIN_SCALE, min(MAX_SCALE, targetScale))

        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = ANIM_DURATION
        animator.interpolator = DecelerateInterpolator(1.8f)
        animator.addUpdateListener { anim ->
            val t = anim.animatedValue as Float
            scale = fromScale + (toScale - fromScale) * t
            translateX = fromTx + (targetTx - fromTx) * t
            translateY = fromTy + (targetTy - fromTy) * t
            clampTranslation()
            applyTransform()
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                scale = toScale
                translateX = targetTx
                translateY = targetTy
                clampTranslation()
                applyTransform()
                notifyScaleChanged()
            }
        })
        activeAnimator = animator
        animator.start()
    }

    fun cancelAnimation() {
        activeAnimator?.let {
            if (it.isRunning) it.cancel()
            activeAnimator = null
        }
    }

    private fun clampTranslation() {
        val w = getViewWidth()
        val h = getViewHeight()
        if (w <= 0 || h <= 0) return

        var maxTx: Float
        var maxTy: Float

        if (scale > 1.0f) {
            maxTx = max(w * 0.75f, w * (scale - 1f) / 2f + w * 0.35f)
            maxTy = max(h * 0.75f, h * (scale - 1f) / 2f + h * 0.35f)
        } else {
            maxTx = w * 0.85f
            maxTy = h * 0.85f
        }

        availableViewportRect?.let { avail ->
            val overlayBottom = h - avail.bottom
            if (overlayBottom > 0) {
                maxTy += overlayBottom
            }
        }

        translateX = max(-maxTx, min(maxTx, translateX))
        translateY = max(-maxTy, min(maxTy, translateY))
    }

    private fun applyTransform() {
        targetView.scaleX = scale
        targetView.scaleY = scale
        targetView.translationX = translateX
        targetView.translationY = translateY
    }

    private fun calculatePixelPerfectScale(): Float {
        val viewW = getViewWidth()
        val viewH = getViewHeight()
        if (viewW <= 0 || viewH <= 0 || streamWidth <= 0 || streamHeight <= 0) {
            return 1.0f
        }
        val scaleW = streamWidth.toFloat() / viewW
        val scaleH = streamHeight.toFloat() / viewH
        val ppScale = max(scaleW, scaleH)
        return max(MIN_SCALE, min(MAX_SCALE, ppScale))
    }

    private fun calculateFillScale(): Float {
        val viewW = getViewWidth()
        val viewH = getViewHeight()
        if (viewW <= 0 || viewH <= 0) return 1.0f

        var targetW = viewW.toFloat()
        var targetH = viewH.toFloat()
        availableViewportRect?.let { avail ->
            targetW = avail.width().toFloat()
            targetH = avail.height().toFloat()
        }

        val fitW = targetW / viewW
        val fitH = targetH / viewH
        val fillScale = max(fitW, fitH)
        return max(MIN_SCALE, min(MAX_SCALE, fillScale))
    }

    fun isAtDefaultScale(): Boolean = abs(scale - 1.0f) <= 0.05f

    fun isAnimating(): Boolean = activeAnimator?.isRunning == true

    private fun getViewWidth(): Int {
        var w = targetView.width
        if (w == 0) {
            val parent = targetView.parent as? View
            if (parent != null) w = parent.width
        }
        return w
    }

    private fun getViewHeight(): Int {
        var h = targetView.height
        if (h == 0) {
            val parent = targetView.parent as? View
            if (parent != null) h = parent.height
        }
        return h
    }

    private fun notifyScaleChanged() {
        for (listener in listeners) {
            try {
                listener.onScaleChanged(scale, currentMode)
            } catch (e: Exception) {
                // Ignore listener exceptions
            }
        }
    }
}
