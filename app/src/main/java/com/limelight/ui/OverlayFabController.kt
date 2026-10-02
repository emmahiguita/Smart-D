package com.limelight.ui

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.limelight.R

/**
 * OverlayFabController v6.0 - Fachada SOLID en Kotlin.
 *
 * Esta clase actúa únicamente como coordinadora de ciclo de vida y punto de
 * entrada para Game.java / Game.kt. Toda la lógica reside en los sub-controladores:
 *
 *   - FABController              -> visual del FAB principal
 *   - RadialMenuController       -> pool de ítems y caché de geometría
 *   - OverlayAnimationController -> todas las animaciones
 *   - OverlayGestureController   -> drag, snap, gestos táctiles
 *   - OverlayKeyboardController  -> ajuste de posición con teclado
 *   - TooltipController          -> etiquetas flotantes on-demand
 */
class OverlayFabController(rootView: View) {

    interface OnActionListener {
        fun onKeyboardToggle()
        fun onMoveToggle()
        fun onItemAction(itemIndex: Int) {}
        fun onItemLongAction(itemIndex: Int) {}
        fun onMouseModeToggle() {}
    }

    companion object {
        private val ITEM_IDS = intArrayOf(
            R.id.fabItem0, R.id.fabItem2, R.id.fabItem3,
            R.id.fabItem6, R.id.fabItem4, R.id.fabItem5,
            R.id.fabItem7, R.id.fabItem8
        )
        private val ICON_IDS = intArrayOf(
            R.id.fabIcon0, R.id.fabIcon2, R.id.fabIcon3,
            R.id.fabIcon6, R.id.fabIcon4, R.id.fabIcon5,
            R.id.fabIcon7, R.id.fabIcon8
        )

        const val A_KEYBOARD: Int = RadialMenuController.A_KEYBOARD
        const val A_ZOOM: Int = RadialMenuController.A_ZOOM
        const val A_MOUSE: Int = RadialMenuController.A_MOUSE
        const val A_FILES: Int = RadialMenuController.A_FILES
        const val A_VOICE: Int = RadialMenuController.A_VOICE
        const val A_PIP: Int = RadialMenuController.A_PIP
        const val A_SCREEN: Int = RadialMenuController.A_SCREEN
        const val A_EXIT: Int = RadialMenuController.A_EXIT
        const val A_ZOOM_IN: Int = RadialMenuController.A_ZOOM_IN
        const val A_ZOOM_OUT: Int = RadialMenuController.A_ZOOM_OUT
        const val A_ZOOM_FIT: Int = RadialMenuController.A_ZOOM_FIT
        const val A_CENTER: Int = RadialMenuController.A_CENTER
        const val A_MOVE_MODE: Int = RadialMenuController.A_MOVE_MODE

        private val MAIN_ENTRIES = arrayOf(
            RadialMenuController.Entry(R.drawable.ic_move_overlay, "Zoom", RadialMenuController.A_ZOOM),
            RadialMenuController.Entry(R.drawable.ic_tools_overlay, "Herramientas", RadialMenuController.A_TOOLS_OPEN),
            RadialMenuController.Entry(R.drawable.ic_files_overlay, "Archivos", RadialMenuController.A_FILES),
            RadialMenuController.Entry(R.drawable.ic_mic_overlay, "Voz", RadialMenuController.A_VOICE),
            RadialMenuController.Entry(R.drawable.ic_keyboard_overlay, "Teclado", RadialMenuController.A_KEYBOARD)
        )

        private val TOOLS_ENTRIES = arrayOf(
            RadialMenuController.Entry(R.drawable.ic_zoom_in, "Ampliar", RadialMenuController.A_ZOOM_IN),
            RadialMenuController.Entry(R.drawable.ic_zoom_out, "Minimizar", RadialMenuController.A_ZOOM_OUT),
            RadialMenuController.Entry(R.drawable.ic_fit_screen, "Ajustar", RadialMenuController.A_ZOOM_FIT),
            RadialMenuController.Entry(R.drawable.ic_center_focus, "Centrar", RadialMenuController.A_CENTER),
            RadialMenuController.Entry(R.drawable.ic_move_overlay, "Mover Pantalla", RadialMenuController.A_MOVE_MODE),
            RadialMenuController.Entry(R.drawable.ic_mouse_overlay, "Modo Mouse", RadialMenuController.A_MOUSE),
            RadialMenuController.Entry(R.drawable.ic_pip_overlay, "PiP", RadialMenuController.A_PIP)
        )
    }

    private val overlayRoot: View = rootView
    private val fabMain: FloatingActionButton? = rootView.findViewById(R.id.fabMain)
    private val fabMainWrapper: View? = rootView.findViewById(R.id.fabMainWrapper)
    private val radialItems: Array<View?> = arrayOfNulls(ITEM_IDS.size)
    private val iconViews: Array<ImageView?> = arrayOfNulls(ITEM_IDS.size)

    private val fabController: FABController
    private val menuController: RadialMenuController
    private val animController: OverlayAnimationController
    private val gestureController: OverlayGestureController
    private val keyboardController: OverlayKeyboardController
    private val tooltipController: TooltipController

    var isExpanded: Boolean = false
        private set

    private var listener: OnActionListener? = null
    private var layoutChangeListener: View.OnLayoutChangeListener? = null

    init {
        for (i in ITEM_IDS.indices) {
            radialItems[i] = rootView.findViewById(ITEM_IDS[i])
            iconViews[i] = rootView.findViewById(ICON_IDS[i])
        }

        fabController = FABController(fabMain, fabMainWrapper)

        menuController = RadialMenuController(
            overlayRoot, radialItems, iconViews, MAIN_ENTRIES, TOOLS_ENTRIES
        )

        val fabScrim: View? = rootView.findViewById(R.id.fabScrim)
        val fabHaloPulse: View? = rootView.findViewById(R.id.fabHaloPulse)
        val fabHaloGlow: View? = rootView.findViewById(R.id.fabHaloGlow)
        val fabOrbitRing: View? = rootView.findViewById(R.id.fabOrbitRing)

        animController = OverlayAnimationController(
            overlayRoot, fabMainWrapper, fabScrim, fabHaloPulse, fabHaloGlow, fabOrbitRing,
            fabController.isEffectsEnabled
        )

        animController.setOnCollapseFinished { animController.startHaloPulse() }

        val ringView: RadialRingView? = rootView.findViewById(R.id.fabRadialRings)
        if (ringView != null) animController.radialRingView = ringView

        gestureController = OverlayGestureController(
            fabMain ?: rootView, fabMainWrapper, overlayRoot,
            object : OverlayGestureController.GestureListener {
                override fun onSingleTap() { handleSingleTap() }
                override fun onDoubleTap() { handleDoubleTap() }
                override fun onLongPress(isDragging: Boolean) {
                    if (!isDragging) listener?.onMouseModeToggle()
                }
                override fun onDragStart() {
                    if (isExpanded) collapseInstant()
                    fabController.wake()
                }
                override fun onDragEnd() {}
            }
        )

        gestureController.onSnapFinished = Runnable { menuController.invalidateGeometry() }

        keyboardController = OverlayKeyboardController(fabMainWrapper)

        val tooltipView: TextView? = rootView.findViewById(R.id.fabTooltip)
        tooltipController = TooltipController(tooltipView)

        setupItemInteractions()

        fabScrim?.setOnClickListener { collapseMenu() }

        gestureController.restorePosition()
        fabController.scheduleShrink()
        fabMain?.post { animController.startHaloPulse() }

        layoutChangeListener = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val w = right - left
            val h = bottom - top
            val oldW = oldRight - oldLeft
            val oldH = oldBottom - oldTop
            if (w > 0 && h > 0 && (w != oldW || h != oldH)) {
                gestureController.reclampPosition()
                fabMainWrapper?.postDelayed({
                    menuController.invalidateGeometry()
                    menuController.syncAllToFabCenter(fabMainWrapper)
                }, 150)
            }
        }
        rootView.addOnLayoutChangeListener(layoutChangeListener)
    }

    fun setOnActionListener(l: OnActionListener?) {
        this.listener = l
    }

    fun onOrientationChanged() {
        gestureController.reclampPosition()
        gestureController.saveFabPosition()
        menuController.invalidateGeometry()
        fabMainWrapper?.postDelayed({
            menuController.syncAllToFabCenter(fabMainWrapper)
        }, 200)
    }

    private fun handleSingleTap() {
        fabController.wake()
        if (!isExpanded) {
            menuController.renderMenu(RadialMenuController.MenuState.MAIN)
            expandMenu()
        } else if (menuController.menuState == RadialMenuController.MenuState.TOOLS) {
            switchMenu(RadialMenuController.MenuState.MAIN)
        } else {
            collapseMenu()
        }
    }

    private fun handleDoubleTap() {
        fabController.toggleEffects()
        val fx = fabController.isEffectsEnabled
        animController.effectsEnabled = fx
        if (fx) animController.startHaloPulse()
        else animController.stopHaloPulse()
    }

    private fun expandMenu() {
        isExpanded = true
        fabController.wake()
        animController.stopHaloPulse()

        overlayRoot.bringToFront()
        overlayRoot.requestLayout()

        val active = menuController.activeCount
        val targets = Array(active) { i ->
            menuController.computeExpandedPosition(i, fabMainWrapper)
        }

        val cx = (fabMainWrapper?.x ?: 0f) + (fabMainWrapper?.width ?: 0) / 2f
        val cy = (fabMainWrapper?.y ?: 0f) + (fabMainWrapper?.height ?: 0) / 2f

        menuController.syncAllToFabCenter(fabMainWrapper)
        animController.showScrim(cx.toInt(), cy.toInt())
        animController.animateOrbitRingExpand(
            menuController.getAdaptiveRadius(fabMainWrapper),
            overlayRoot.context.resources.displayMetrics.density
        )
        animController.animateExpansion(
            radialItems, active, targets,
            menuController.getDynamicAngles(fabMainWrapper)
        )

        if (menuController.menuState == RadialMenuController.MenuState.TOOLS) {
            fabController.showBackArrow()
        } else {
            fabController.transitionToExpanded()
        }
    }

    private fun collapseMenu() {
        isExpanded = false
        menuController.renderMenu(RadialMenuController.MenuState.MAIN)

        val cx = (fabMainWrapper?.x ?: 0f) + (fabMainWrapper?.width ?: 0) / 2f
        val cy = (fabMainWrapper?.y ?: 0f) + (fabMainWrapper?.height ?: 0) / 2f

        animController.hideScrim(cx.toInt(), cy.toInt())
        animController.animateOrbitRingCollapse()
        animController.animateCollapse(radialItems, cx, cy)
        fabController.transitionToCollapsed()
        fabController.scheduleShrink()

        tooltipController.hideTooltip()

        if (keyboardController.isKeyboardVisible) {
            fabMainWrapper?.bringToFront()
            fabMainWrapper?.requestLayout()
        }
    }

    private fun collapseInstant() {
        isExpanded = false
        animController.collapseInstant(radialItems)
        val fabScrim: View? = overlayRoot.findViewById(R.id.fabScrim)
        fabScrim?.visibility = View.GONE
        fabController.resetInstant()
        tooltipController.hideTooltip()
    }

    private fun switchMenu(state: RadialMenuController.MenuState) {
        for (item in radialItems) {
            if (item != null) {
                item.animate().cancel()
                item.visibility = View.INVISIBLE
                item.alpha = 0f
            }
        }
        menuController.renderMenu(state)
        updateCenterIcon()
        menuController.syncAllToFabCenter(fabMainWrapper)
        menuController.invalidateGeometry()

        val active = menuController.activeCount
        val targets = Array(active) { i ->
            menuController.computeExpandedPosition(i, fabMainWrapper)
        }
        animController.animateExpansion(
            radialItems, active, targets,
            menuController.getDynamicAngles(fabMainWrapper)
        )
    }

    private fun updateCenterIcon() {
        if (menuController.menuState == RadialMenuController.MenuState.TOOLS) {
            fabController.showBackArrow()
        } else if (isExpanded) {
            fabController.transitionToExpanded()
        } else {
            fabController.transitionToCollapsed()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupItemInteractions() {
        for (i in radialItems.indices) {
            val item = radialItems[i] ?: continue
            val idx = i

            item.setOnClickListener { handleItemClick(idx) }

            item.setOnLongClickListener {
                if (idx < menuController.activeCount) {
                    listener?.onItemLongAction(menuController.currentEntries[idx].action)
                }
                collapseMenu()
                true
            }

            item.setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(70).start()
                        if (idx < menuController.activeCount) {
                            tooltipController.showTooltip(v, menuController.currentEntries[idx].label)
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(90).start()
                        tooltipController.hideTooltip()
                    }
                }
                false
            }
        }
    }

    private fun handleItemClick(index: Int) {
        if (index >= menuController.activeCount) return
        val action = menuController.currentEntries[index].action

        if (action == RadialMenuController.A_TOOLS_OPEN) {
            switchMenu(RadialMenuController.MenuState.TOOLS)
            return
        }

        when (action) {
            A_KEYBOARD -> listener?.onKeyboardToggle()
            A_ZOOM -> listener?.onMoveToggle()
            else -> listener?.onItemAction(action)
        }
        collapseMenu()
    }

    fun collapse() {
        if (isExpanded) collapseMenu()
    }

    fun onKeyboardVisibilityChanged(visible: Boolean, keyboardTopY: Int) {
        if (isExpanded) {
            collapseInstant()
        }
        overlayRoot.bringToFront()
        overlayRoot.requestLayout()
        keyboardController.onKeyboardVisibilityChanged(visible, keyboardTopY) {
            menuController.syncAllToFabCenter(fabMainWrapper)
        }
    }

    fun enterPerformanceMode() {
        animController.performanceMode = true
        animController.stopHaloPulse()
    }

    fun exitPerformanceMode() {
        animController.performanceMode = false
        if (!isExpanded) animController.startHaloPulse()
    }

    fun release() {
        fabController.release()
        animController.release()
        gestureController.release()
        keyboardController.release()
        tooltipController.release()

        layoutChangeListener?.let {
            overlayRoot.removeOnLayoutChangeListener(it)
            layoutChangeListener = null
        }

        fabMainWrapper?.removeCallbacks(null)
    }
}