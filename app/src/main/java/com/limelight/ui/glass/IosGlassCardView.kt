package com.limelight.ui.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

/**
 * IosGlassCardView — Tarjeta de Cristal Zafiro Translúcido con Bisel Especular estilo iOS 18.
 *
 * Características técnicas:
 *  • Principios SOLID: Implementa [GlassInteractiveComponent] con separación estricta de responsabilidades.
 *  • Zero allocation en onDraw: Shaders, gradientes y RectF precalculados en onSizeChanged.
 *  • Bisel reflectante superior (Specular Bevel) con gradiente de luz direccional.
 *  • Físicas de resorte Apple Spring (damping = 0.78, stiffness = 650) con hápticos.
 */
class IosGlassCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr), GlassInteractiveComponent {

    private val density = resources.displayMetrics.density
    private var cornerRadius = 20f * density
    private var strokeWidth = 1.2f * density

    // Geometría y Pinturas precalculadas
    private val boundsRect = RectF()
    private val strokeRect = RectF()

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@IosGlassCardView.strokeWidth
    }

    private val innerSheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.8f * density
    }

    // Físicas Spring de rebote
    private var scaleSpringX: SpringAnimation? = null
    private var scaleSpringY: SpringAnimation? = null

    init {
        setWillNotDraw(false)
        isClickable = true
        isFocusable = true

        scaleSpringX = SpringAnimation(this, DynamicAnimation.SCALE_X).apply {
            spring = SpringForce(1f).apply {
                dampingRatio = 0.78f
                stiffness = 650f
            }
        }
        scaleSpringY = SpringAnimation(this, DynamicAnimation.SCALE_Y).apply {
            spring = SpringForce(1f).apply {
                dampingRatio = 0.78f
                stiffness = 650f
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        val halfStroke = strokeWidth / 2f
        boundsRect.set(0f, 0f, w.toFloat(), h.toFloat())
        strokeRect.set(halfStroke, halfStroke, w - halfStroke, h - halfStroke)

        // 1. Superficie de cristal ahumado translúcido
        bodyPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(0x52202B3E, 0x38141C2B, 0x240E1420),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )

        // 2. Bisel especular superior (blanco reflectante que se desvanece)
        strokePaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(0x66FFFFFF, 0x29FFFFFF, 0x1A78A6D8),
            floatArrayOf(0f, 0.35f, 1f),
            Shader.TileMode.CLAMP
        )

        // 3. Destello interior etéreo inyectado con el tema activo real
        val primary = com.limelight.utils.ThemeManager.accentColor(context)
        val secondary = com.limelight.utils.ThemeManager.secondaryAccentColor(context)
        val c1 = (0x30 shl 24) or (primary and 0x00FFFFFF)
        val c2 = (0x0C shl 24) or (secondary and 0x00FFFFFF)
        val c3 = (0x22 shl 24) or (primary and 0x00FFFFFF)
        innerSheenPaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            intArrayOf(c1, c2, c3),
            null,
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        // Fondo de cristal translúcido
        canvas.drawRoundRect(boundsRect, cornerRadius, cornerRadius, bodyPaint)
        // Bisel exterior especular
        canvas.drawRoundRect(strokeRect, cornerRadius, cornerRadius, strokePaint)
        // Borde interior sutil
        canvas.drawRoundRect(strokeRect, cornerRadius, cornerRadius, innerSheenPaint)
        super.onDraw(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onPointerDown(event.x, event.y)
            }
            MotionEvent.ACTION_UP -> {
                onPointerUp()
            }
            MotionEvent.ACTION_CANCEL -> {
                onPointerCancel()
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onPointerDown(x: Float, y: Float) {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        scaleSpringX?.animateToFinalPosition(0.965f)
        scaleSpringY?.animateToFinalPosition(0.965f)
    }

    override fun onPointerUp() {
        scaleSpringX?.animateToFinalPosition(1f)
        scaleSpringY?.animateToFinalPosition(1f)
    }

    override fun onPointerCancel() {
        scaleSpringX?.animateToFinalPosition(1f)
        scaleSpringY?.animateToFinalPosition(1f)
    }
}
