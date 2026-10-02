package com.limelight.ui.viewport

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateInterpolator
import androidx.appcompat.widget.AppCompatTextView
import kotlin.math.roundToInt

/**
 * ZoomIndicatorView — Indicador efímero del nivel de zoom.
 *
 * Muestra brevemente el nivel de zoom actual (e.g. 150%, 1:1, FIT)
 * con un fade-out automático. No es interactivo.
 */
class ZoomIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    companion object {
        private const val DISPLAY_DURATION_MS = 700L
        private const val FADE_DURATION_MS = 300L
    }

    private val fadeOutRunnable = Runnable {
        animate()
            .alpha(0f)
            .setDuration(FADE_DURATION_MS)
            .setInterpolator(AccelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    visibility = View.GONE
                }
            })
            .start()
    }

    init {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        alpha = 0f
        visibility = View.GONE

        // Fondo pill semi-transparente
        setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
        background = createPillBackground()
    }

    /**
     * Muestra el nivel de zoom actual con auto-fade.
     */
    fun showLevel(scale: Float, mode: DesktopViewportEngine.ViewportMode) {
        // Cancelar animaciones pendientes
        animate().cancel()
        removeCallbacks(fadeOutRunnable)

        // Formatear texto
        val label = when (mode) {
            DesktopViewportEngine.ViewportMode.FIT -> "FIT"
            DesktopViewportEngine.ViewportMode.PIXEL_PERFECT -> "1:1"
            DesktopViewportEngine.ViewportMode.FILL -> "FILL"
            else -> "${(scale * 100).roundToInt()}%"
        }
        text = label

        // Mostrar con fade-in
        visibility = View.VISIBLE
        animate()
            .alpha(1f)
            .setDuration(100)
            .setListener(null)
            .start()

        // Programar fade-out
        postDelayed(fadeOutRunnable, DISPLAY_DURATION_MS)
    }

    private fun createPillBackground(): GradientDrawable {
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.RECTANGLE
        bg.setColor(0x99000000.toInt()) // Negro 60% opacidad
        bg.cornerRadius = dp(20f).toFloat()
        return bg
    }

    private fun dp(value: Float): Int {
        return (value * context.resources.displayMetrics.density).roundToInt()
    }
}
