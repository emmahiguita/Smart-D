package com.limelight.ui

import android.content.Context
import android.view.View
import android.view.animation.PathInterpolator

/**
 * OverlayKeyboardController — Gestiona la elevación y reposicionamiento del FAB para evitar la oclusión del teclado.
 */
class OverlayKeyboardController(private val fabMainWrapperView: View?) {
    companion object {
        private const val PREF_NAME = "fab_overlay_v5"
        private const val KEY_FAB_X = "fab_x"
        private const val KEY_FAB_Y = "fab_y"

        private val EASING_EMPHASIZED_DECELERATE = PathInterpolator(0.05f, 0.7f, 0.1f, 1.0f)
        private val EASING_EMPHASIZED_ACCELERATE = PathInterpolator(0.3f, 0.0f, 0.8f, 0.15f)
    }

    private val context: Context? = fabMainWrapperView?.context
    private val density: Float = context?.resources?.displayMetrics?.density ?: 1f

    var isKeyboardVisible: Boolean = false
        private set

    var keyboardTopY: Int = 0
        private set

    /**
     * Reacciona ante los cambios de visibilidad del teclado reubicando y elevando el FAB.
     */
    fun onKeyboardVisibilityChanged(visible: Boolean, keyboardTopY: Int, onPositionSynced: Runnable?) {
        val wrapper = fabMainWrapperView ?: return
        this.isKeyboardVisible = visible
        this.keyboardTopY = keyboardTopY

        if (visible) {
            wrapper.bringToFront()
            wrapper.requestLayout()

            // Elevación adicional de seguridad para quedar encima del teclado (cardElevation = 18dp)
            wrapper.translationZ = 50f * density

            val fabH = if (wrapper.height > 0) wrapper.height else (56f * density).toInt()
            val fabBottom = wrapper.y + fabH
            val safeY = maxOf(10f * density, keyboardTopY - fabH - (16f * density))

            if (fabBottom > keyboardTopY || wrapper.y > safeY) {
                wrapper.animate().cancel()
                val anim = wrapper.animate()
                    .y(safeY)
                    .setDuration(300)
                    .setInterpolator(EASING_EMPHASIZED_DECELERATE)
                if (onPositionSynced != null) {
                    anim.withEndAction(onPositionSynced)
                }
                anim.start()
            }
        } else {
            // Restaurar elevación y regresar a la posición guardada
            wrapper.translationZ = 0f

            val prefs = context?.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val savedX = prefs?.getFloat(KEY_FAB_X, wrapper.x) ?: wrapper.x
            var savedY = prefs?.getFloat(KEY_FAB_Y, wrapper.y) ?: wrapper.y

            val parent = wrapper.parent as? View
            if (parent != null && parent.height > 0) {
                val fabH = if (wrapper.height > 0) wrapper.height else (56f * density).toInt()
                savedY = maxOf(10f * density, minOf(savedY, parent.height - fabH - (10f * density)))
            }

            wrapper.animate().cancel()
            val anim = wrapper.animate()
                .x(savedX)
                .y(savedY)
                .setDuration(300)
                .setInterpolator(EASING_EMPHASIZED_ACCELERATE)
            if (onPositionSynced != null) {
                anim.withEndAction(onPositionSynced)
            }
            anim.start()
        }
    }

    /**
     * Libera recursos del controlador.
     */
    fun release() {
        fabMainWrapperView?.animate()?.cancel()
    }
}
