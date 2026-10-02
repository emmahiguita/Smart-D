package com.limelight.ui

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.PointF
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import com.limelight.ui.viewport.DesktopViewportEngine
import com.limelight.ui.viewport.KeyboardAvoidanceEngine
import com.limelight.ui.viewport.OverlayLayoutCoordinator
import com.limelight.ui.viewport.ViewportGestureHandler
import com.limelight.ui.viewport.ZoomIndicatorView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * StreamViewTransformController - Fachada SOLID en Kotlin.
 *
 * Esta clase delega a componentes especializados:
 *   - DesktopViewportEngine    -> transformacion, zoom focal, modos
 *   - ViewportGestureHandler   -> deteccion de gestos
 *   - OverlayLayoutCoordinator -> zonas ocupadas por overlays
 *   - KeyboardAvoidanceEngine  -> pan inteligente por teclado
 */
class StreamViewTransformController(
    private val context: Context,
    private val targetView: View,
    interceptView: View? = null
) {
    companion object {
        private const val AUTO_MAX_ZOOM: Float = 2.5f
        private const val LEGIBLE_FIELD_DP: Float = 48f
        private const val MAX_FILL_W: Float = 0.90f
        private const val MAX_FILL_H: Float = 0.50f
        private const val FIELD_TARGET_Y: Float = 0.35f
        private const val KB_RESERVE_PORT: Float = 0.45f
        private const val KB_RESERVE_LAND: Float = 0.55f
    }

    val engine: DesktopViewportEngine = DesktopViewportEngine(targetView)
    val gestureHandler: ViewportGestureHandler = ViewportGestureHandler(context, engine)
    val coordinator: OverlayLayoutCoordinator = OverlayLayoutCoordinator()
    private val avoidanceEngine: KeyboardAvoidanceEngine = KeyboardAvoidanceEngine(
        context.resources.displayMetrics.density
    )

    private var autoZoomActive: Boolean = false
    var zoomIndicator: ZoomIndicatorView? = null

    init {
        coordinator.registerOverlay(
            OverlayLayoutCoordinator.OVERLAY_KEYBOARD,
            OverlayLayoutCoordinator.Gravity.BOTTOM
        )
        coordinator.registerOverlay(
            OverlayLayoutCoordinator.OVERLAY_FAB,
            OverlayLayoutCoordinator.Gravity.FLOATING
        )
        coordinator.registerOverlay(
            OverlayLayoutCoordinator.OVERLAY_TASKBAR,
            OverlayLayoutCoordinator.Gravity.BOTTOM
        )
        coordinator.registerOverlay(
            OverlayLayoutCoordinator.OVERLAY_DOCK,
            OverlayLayoutCoordinator.Gravity.RIGHT
        )

        coordinator.setOnViewportChangedListener { available ->
            engine.setAvailableViewportRect(available)
        }

        engine.addListener { scale, mode ->
            zoomIndicator?.showLevel(scale, mode)
        }

        targetView.post {
            val parent = targetView.parent as? View
            if (parent != null) {
                coordinator.setScreenSize(parent.width, parent.height)
            }
        }
    }

    fun setStreamResolution(width: Int, height: Int) {
        engine.setStreamResolution(width, height)
    }

        private val moveModeListeners = mutableListOf<(Boolean) -> Unit>()
    fun addOnMoveModeChangedListener(listener: (Boolean) -> Unit) {
        moveModeListeners.add(listener)
    }

    var onMoveModeChangedListener: ((Boolean) -> Unit)? = null
        set(value) {
            field = value
            if (value != null) {
                moveModeListeners.add(value)
            }
        }
    fun updateScreenSize(width: Int, height: Int) {
        coordinator.setScreenSize(width, height)
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        return gestureHandler.onTouchEvent(event)
    }

    val scale: Float get() = engine.scale
    val translateX: Float get() = engine.translateX
    val translateY: Float get() = engine.translateY

    @Deprecated("Use onKeyboardShown instead")
    fun setKeyboardOffset(offsetY: Float) {
        if (offsetY < 0) {
            val parent = targetView.parent as? View
            val screenH = parent?.height ?: 1080
            val kbHeight = (abs(offsetY) / 0.85f).roundToInt()
            coordinator.onOverlayShown(
                OverlayLayoutCoordinator.OVERLAY_KEYBOARD,
                Rect(0, screenH - kbHeight, parent?.width ?: 1920, screenH)
            )
        } else {
            coordinator.onOverlayHidden(OverlayLayoutCoordinator.OVERLAY_KEYBOARD)
        }
    }

    @Deprecated("Returns 0f")
    fun getKeyboardOffsetY(): Float = 0f

    fun onKeyboardShown(keyboardBounds: Rect, cursorPos: PointF?) {
        coordinator.onOverlayShown(OverlayLayoutCoordinator.OVERLAY_KEYBOARD, keyboardBounds)

        val parent = targetView.parent as? View
        val screenW = parent?.width ?: 1920
        val screenH = parent?.height ?: 1080
        val viewport = Rect(0, 0, screenW, screenH)

        val result = avoidanceEngine.calculateAvoidance(
            keyboardBounds, cursorPos, viewport, engine.scale
        )

        if (result.needsPan) {
            engine.panBy(0f, result.panY)
        }
    }

    fun onKeyboardHidden() {
        coordinator.onOverlayHidden(OverlayLayoutCoordinator.OVERLAY_KEYBOARD)
        if (engine.isAtDefaultScale()) {
            engine.setViewportMode(DesktopViewportEngine.ViewportMode.FIT)
        }
    }

    fun animateReset() {
        autoZoomActive = false
        gestureHandler.isMoveModeActive = false
        moveModeListeners.forEach { it(false) }
        engine.resetViewport()
    }

        fun toggleMoveMode(): Boolean {
        val active = gestureHandler.toggleMoveMode()
        moveModeListeners.forEach { it(active) }
        return active
    }

    fun setMoveMode(active: Boolean) {
        gestureHandler.isMoveModeActive = active
        moveModeListeners.forEach { it(active) }
    }

    fun isMoveModeActive(): Boolean = gestureHandler.isMoveModeActive

    fun zoomIn() {
        engine.zoomIn()
    }

    fun zoomOut() {
        engine.zoomOut()
    }

    fun resetViewport() {
        animateReset()
    }

    fun centerViewport() {
        engine.centerViewport()
    }

    fun setViewportMode(mode: DesktopViewportEngine.ViewportMode) {
        engine.setViewportMode(mode)
    }

    fun zoomToFocusedRect(
        localX: Float, localY: Float, localW: Float, localH: Float,
        keyboardVisible: Boolean
    ) {
        if (localW <= 0 || localH <= 0) return
        if (!autoZoomActive && engine.scale > DesktopViewportEngine.MIN_SCALE + 0.05f) return

        val viewW = getViewWidth()
        val viewH = getViewHeight()
        if (viewW <= 0 || viewH <= 0) return

        val density = context.resources.displayMetrics.density
        val portrait = context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val reserve = if (keyboardVisible) (if (portrait) KB_RESERVE_PORT else KB_RESERVE_LAND) else 0f
        val availableH = viewH * (1f - reserve)

        val pivotX = viewW / 2f
        val pivotY = viewH / 2f
        val fieldCx = localX + localW / 2f
        val fieldCy = localY + localH / 2f
        val minLegiblePx = LEGIBLE_FIELD_DP * density

        val curFieldScreenH = localH * engine.scale
        val curFieldScreenY = pivotY + (fieldCy - pivotY) * engine.scale + engine.translateY
        val alreadyLegible = curFieldScreenH >= minLegiblePx * 0.85f
        val inSafeArea = curFieldScreenY > minLegiblePx * 0.5f && curFieldScreenY < availableH * 0.95f
        if (alreadyLegible && inSafeArea) return

        val legibilityScale = minLegiblePx / localH
        val fitScale = min((viewW * MAX_FILL_W) / localW, (availableH * MAX_FILL_H) / localH)
        var targetScale = min(legibilityScale, fitScale)
        targetScale = max(DesktopViewportEngine.MIN_SCALE, min(AUTO_MAX_ZOOM, targetScale))

        if (targetScale < DesktopViewportEngine.MIN_SCALE + 0.08f) return

        val targetScreenX = viewW * 0.5f
        val targetScreenY = availableH * FIELD_TARGET_Y

        val targetTx = targetScreenX - pivotX - (fieldCx - pivotX) * targetScale
        val targetTy = targetScreenY - pivotY - (fieldCy - pivotY) * targetScale

        autoZoomActive = true
        gestureHandler.isMoveModeActive = true
        engine.animateToViewport(targetScale, targetTx, targetTy)
    }

    fun zoomToRightClick(localX: Float, localY: Float) {
        if (!autoZoomActive && engine.scale > DesktopViewportEngine.MIN_SCALE + 0.05f) return

        val viewW = getViewWidth()
        val viewH = getViewHeight()
        if (viewW <= 0 || viewH <= 0) return

        val targetScale = min(AUTO_MAX_ZOOM, DesktopViewportEngine.MIN_SCALE * 2.5f)
        val pivotX = viewW / 2f
        val pivotY = viewH / 2f

        val targetTx = viewW * 0.35f - pivotX - (localX - pivotX) * targetScale
        val targetTy = viewH * 0.35f - pivotY - (localY - pivotY) * targetScale

        autoZoomActive = true
        gestureHandler.isMoveModeActive = true
        engine.animateToViewport(targetScale, targetTx, targetTy)
    }

    fun clearAutoZoom() {
        if (autoZoomActive) {
            autoZoomActive = false
            animateReset()
        }
    }

    fun saveViewport(slot: Int) {
        val prefs = context.getSharedPreferences("smartdisplay_viewports", Context.MODE_PRIVATE)
        prefs.edit()
            .putFloat("vp_scale_$slot", engine.scale)
            .putFloat("vp_tx_$slot", engine.translateX)
            .putFloat("vp_ty_$slot", engine.translateY)
            .apply()
        Toast.makeText(context, "Monitor $slot guardado", Toast.LENGTH_SHORT).show()
    }

    fun restoreViewport(slot: Int) {
        val prefs = context.getSharedPreferences("smartdisplay_viewports", Context.MODE_PRIVATE)
        if (!prefs.contains("vp_scale_$slot")) {
            Toast.makeText(context, "Monitor $slot vacÃ­o (MantÃ©n pulsado para guardar)", Toast.LENGTH_SHORT).show()
            return
        }
        val targetScale = prefs.getFloat("vp_scale_$slot", DesktopViewportEngine.MIN_SCALE)
        val targetTx = prefs.getFloat("vp_tx_$slot", 0f)
        val targetTy = prefs.getFloat("vp_ty_$slot", 0f)

        gestureHandler.isMoveModeActive = true
        engine.animateToViewport(targetScale, targetTx, targetTy)
    }

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
}





