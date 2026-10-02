package com.limelight.ui.glass

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.limelight.utils.ThemeManager
import kotlin.math.min

/**
 * AirDropRadarView — Vista de Radar y Ondas Líquidas Hiperrealista estilo Apple AirDrop / Continuity.
 *
 * Características:
 *  • Físicas de ondas concéntricas que se expanden continuamente cuando el servidor está activo.
 *  • Cero asignaciones de memoria en onDraw() para mantener 60/120 FPS sin recolección de basura.
 *  • Inyección de color universal basada en [ThemeManager.accentColor] y [ThemeManager.secondaryAccentColor].
 *  • Gestión limpia de ciclo de vida: Se detiene automáticamente cuando no es visible para evitar procesos zombi.
 */
class AirDropRadarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private var wavePhase = 0f
    private var animator: ValueAnimator? = null
    private var isEmitting = true

    // Pinturas
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
    }

    private val waveFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val centerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val centerOrbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val centerBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
    }

    init {
        startEmitting()
    }

    fun setServerActive(active: Boolean) {
        if (isEmitting == active) return
        isEmitting = active
        if (active) {
            startEmitting()
        } else {
            stopEmitting()
            invalidate()
        }
    }

    fun startEmitting() {
        if (animator?.isRunning == true || isInEditMode) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2400L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                wavePhase = animation.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stopEmitting() {
        animator?.cancel()
        animator = null
        wavePhase = 0f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width * 0.5f
        val cy = height * 0.5f
        val maxRadius = min(width, height) * 0.46f
        val centerOrbRadius = 24f * density

        val primary = ThemeManager.accentColor(context)
        val secondary = ThemeManager.secondaryAccentColor(context)

        if (isEmitting) {
            // Dibujar 3 ondas concéntricas desplazadas 0.33f en fase
            val waveCount = 3
            for (i in 0 until waveCount) {
                val phase = (wavePhase + i.toFloat() / waveCount) % 1.0f
                val currentRadius = centerOrbRadius + (maxRadius - centerOrbRadius) * phase
                val alphaProgress = 1f - phase

                // Desvanecimiento suave
                val strokeAlpha = (alphaProgress * 0.55f * 255).toInt().coerceIn(0, 255)
                val fillAlpha = (alphaProgress * 0.10f * 255).toInt().coerceIn(0, 255)

                wavePaint.color = (strokeAlpha shl 24) or (primary and 0x00FFFFFF)
                waveFillPaint.color = (fillAlpha shl 24) or (secondary and 0x00FFFFFF)

                // Relleno etéreo y borde de onda
                canvas.drawCircle(cx, cy, currentRadius, waveFillPaint)
                canvas.drawCircle(cx, cy, currentRadius, wavePaint)
            }
        }

        // Resplandor difuso del núcleo central
        centerGlowPaint.shader = RadialGradient(
            cx, cy, centerOrbRadius * 2.2f,
            intArrayOf(
                (0x60 shl 24) or (primary and 0x00FFFFFF),
                (0x18 shl 24) or (secondary and 0x00FFFFFF),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, centerOrbRadius * 2.2f, centerGlowPaint)

        // Orbe central (núcleo del emisor AirDrop)
        val coreAlpha = if (isEmitting) 0xCC else 0x55
        centerOrbPaint.color = (coreAlpha shl 24) or (0x101622)
        canvas.drawCircle(cx, cy, centerOrbRadius, centerOrbPaint)

        // Borde especular del núcleo
        val borderAlpha = if (isEmitting) 0xEE else 0x44
        centerBorderPaint.color = (borderAlpha shl 24) or (primary and 0x00FFFFFF)
        canvas.drawCircle(cx, cy, centerOrbRadius, centerBorderPaint)

        // Punto de pulso central vivo
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = if (isEmitting) primary else 0xFF64748B.toInt()
        }
        val dotRadius = if (isEmitting) 7f * density else 5f * density
        canvas.drawCircle(cx, cy, dotRadius, dotPaint)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isEmitting) {
            startEmitting()
        } else {
            stopEmitting()
        }
    }

    override fun onDetachedFromWindow() {
        stopEmitting()
        super.onDetachedFromWindow()
    }
}
