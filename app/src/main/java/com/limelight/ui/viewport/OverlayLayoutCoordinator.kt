package com.limelight.ui.viewport

import android.graphics.Rect
import androidx.annotation.UiThread

/**
 * OverlayLayoutCoordinator - Autoridad central de zonas ocupadas.
 *
 * Responsabilidad UNICA: mantener un registro de que regiones de la pantalla
 * estan ocupadas por overlays y calcular el availableViewportRect.
 */
@UiThread
class OverlayLayoutCoordinator {

    companion object {
        const val OVERLAY_KEYBOARD: String = "keyboard"
        const val OVERLAY_FAB: String = "fab"
        const val OVERLAY_TASKBAR: String = "taskbar"
        const val OVERLAY_WINDOW_CONTROLS: String = "windowControls"
        const val OVERLAY_DOCK: String = "dock"
        const val OVERLAY_TYPING_BAR: String = "typingBar"
    }

    enum class Gravity {
        TOP,
        BOTTOM,
        LEFT,
        RIGHT,
        FLOATING
    }

    private class OverlayRegion(
        val id: String,
        val gravity: Gravity
    ) {
        var active: Boolean = false
        val bounds: Rect = Rect()
    }

    private val overlays: MutableMap<String, OverlayRegion> = LinkedHashMap()
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private val availableRect: Rect = Rect()

    fun interface OnViewportChangedListener {
        fun onAvailableViewportChanged(available: Rect)
    }

    private var listener: OnViewportChangedListener? = null

    fun setOnViewportChangedListener(l: OnViewportChangedListener?) {
        this.listener = l
    }

    fun setScreenSize(width: Int, height: Int) {
        this.screenWidth = width
        this.screenHeight = height
        recalculate()
    }

    fun registerOverlay(id: String, gravity: Gravity) {
        if (!overlays.containsKey(id)) {
            overlays[id] = OverlayRegion(id, gravity)
        }
    }

    fun onOverlayShown(id: String, bounds: Rect) {
        val region = getOrCreate(id, Gravity.FLOATING)
        region.active = true
        region.bounds.set(bounds)
        recalculate()
    }

    fun onOverlayShown(id: String, width: Int, height: Int) {
        val region = getOrCreate(id, Gravity.BOTTOM)
        region.active = true

        when (region.gravity) {
            Gravity.BOTTOM -> region.bounds.set(0, screenHeight - height, screenWidth, screenHeight)
            Gravity.TOP -> region.bounds.set(0, 0, screenWidth, height)
            Gravity.LEFT -> region.bounds.set(0, 0, width, screenHeight)
            Gravity.RIGHT -> region.bounds.set(screenWidth - width, 0, screenWidth, screenHeight)
            Gravity.FLOATING -> {}
        }
        recalculate()
    }

    fun onOverlayHidden(id: String) {
        val region = overlays[id]
        if (region != null) {
            region.active = false
            region.bounds.setEmpty()
            recalculate()
        }
    }

    fun onOverlayBoundsChanged(id: String, newBounds: Rect) {
        val region = overlays[id]
        if (region != null && region.active) {
            region.bounds.set(newBounds)
            recalculate()
        }
    }

    fun getAvailableViewportRect(): Rect = Rect(availableRect)

    fun isOverlayActive(id: String): Boolean {
        val region = overlays[id]
        return region != null && region.active
    }

    fun getOverlayBounds(id: String): Rect? {
        val region = overlays[id]
        return if (region != null && region.active) Rect(region.bounds) else null
    }

    private fun recalculate() {
        if (screenWidth <= 0 || screenHeight <= 0) return

        var left = 0
        var top = 0
        var right = screenWidth
        var bottom = screenHeight

        for (region in overlays.values) {
            if (!region.active || region.gravity == Gravity.FLOATING) {
                continue
            }

            when (region.gravity) {
                Gravity.BOTTOM -> {
                    val newBottom = region.bounds.top
                    if (newBottom in 1 until bottom) {
                        bottom = newBottom
                    }
                }
                Gravity.TOP -> {
                    val newTop = region.bounds.bottom
                    if (newTop > top) {
                        top = newTop
                    }
                }
                Gravity.LEFT -> {
                    val newLeft = region.bounds.right
                    if (newLeft > left) {
                        left = newLeft
                    }
                }
                Gravity.RIGHT -> {
                    val newRight = region.bounds.left
                    if (newRight in 1 until right) {
                        right = newRight
                    }
                }
                Gravity.FLOATING -> {}
            }
        }

        if (right <= left) right = left + 1
        if (bottom <= top) bottom = top + 1

        val newRect = Rect(left, top, right, bottom)

        if (availableRect != newRect) {
            availableRect.set(newRect)
            listener?.onAvailableViewportChanged(Rect(availableRect))
        }
    }

    private fun getOrCreate(id: String, defaultGravity: Gravity): OverlayRegion {
        var region = overlays[id]
        if (region == null) {
            region = OverlayRegion(id, defaultGravity)
            overlays[id] = region
        }
        return region
    }
}