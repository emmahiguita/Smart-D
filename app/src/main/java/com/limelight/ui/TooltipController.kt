package com.limelight.ui

import android.view.View
import android.widget.TextView

/**
 * TooltipController — Gestiona el Tooltip dinámico y flotante para los botones radiales.
 */
class TooltipController(private val tooltipView: TextView?) {
    private val density: Float = tooltipView?.context?.resources?.displayMetrics?.density ?: 1f

    /**
     * Muestra el tooltip encima del botón objetivo.
     */
    fun showTooltip(targetView: View?, labelText: String?) {
        val tv = tooltipView ?: return
        if (targetView == null || labelText.isNullOrEmpty()) return

        tv.text = labelText
        tv.visibility = View.VISIBLE
        tv.alpha = 0f

        // Medir el texto para obtener dimensiones dinámicas
        tv.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val tw = tv.measuredWidth
        val th = tv.measuredHeight

        // Calcular posición centrada sobre el botón radial
        val targetCenterX = targetView.x + targetView.width / 2f
        val targetTopY = targetView.y

        tv.x = targetCenterX - tw / 2f
        tv.y = targetTopY - th - (6 * density) // 6dp de separación

        // Animación suave de aparición
        tv.animate().cancel()
        tv.animate()
            .alpha(1f)
            .setDuration(140)
            .start()
    }

    /**
     * Oculta el tooltip con una animación de desvanecimiento rápida.
     */
    fun hideTooltip() {
        val tv = tooltipView ?: return
        if (tv.visibility != View.VISIBLE) return

        tv.animate().cancel()
        tv.animate()
            .alpha(0f)
            .setDuration(100)
            .withEndAction { tv.visibility = View.GONE }
            .start()
    }

    /**
     * Libera recursos y cancela animaciones en curso.
     */
    fun release() {
        tooltipView?.let { tv ->
            tv.animate().cancel()
            tv.visibility = View.GONE
        }
    }
}
