package com.limelight.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * RadialRingView — Vista transparente que dibuja sobre el Canvas:
 *  1. Dos anillos concéntricos azul-cian alrededor del FAB (diseño de referencia).
 *  2. Líneas spoke tenues desde el centro hacia cada botón radial.
 *
 * No interfiere con eventos táctiles (importantForAccessibility=no, clickable=false).
 */
class RadialRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ── Pintura anillos ───────────────────────────────────────────────────────
    private val paintRingOuter = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintRingInner = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintSpoke = Paint(Paint.ANTI_ALIAS_FLAG)

    // ── Estado ────────────────────────────────────────────────────────────────
    private var cx: Float = 0f
    private var cy: Float = 0f
    private var radiusOuter: Float = 0f
    private var radiusInner: Float = 0f
    private var spokeAngles: DoubleArray? = null
    private var isRingVisible: Boolean = false

    init {
        val density = resources.displayMetrics.density

        // Anillo exterior: borde fino azul eléctrico semitransparente
        paintRingOuter.style = Paint.Style.STROKE
        paintRingOuter.strokeWidth = 1.2f * density
        paintRingOuter.color = 0x4000C6FF.toInt()   // cian eléctrico ~25% opacidad

        // Anillo interior: ligeramente más brillante
        paintRingInner.style = Paint.Style.STROKE
        paintRingInner.strokeWidth = 0.8f * density
        paintRingInner.color = 0x2A0088CC.toInt()   // azul oscuro ~16% opacidad

        // Spokes: líneas punteadas muy tenues
        paintSpoke.style = Paint.Style.STROKE
        paintSpoke.strokeWidth = 0.6f * density
        paintSpoke.color = 0x1800AAEE.toInt()       // azul sutil ~10% opacidad
        paintSpoke.pathEffect = DashPathEffect(floatArrayOf(4 * density, 6 * density), 0f)

        // Esta vista NO consume eventos táctiles
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /**
     * Actualiza los parámetros de dibujado. Llamar desde OverlayAnimationController
     * cuando el menú se abre/cierra o el FAB cambia de posición.
     */
    fun update(cx: Float, cy: Float, innerRadius: Float, spokeAngles: DoubleArray?, show: Boolean) {
        this.cx = cx
        this.cy = cy
        this.radiusInner = innerRadius
        this.radiusOuter = innerRadius * 1.18f
        this.spokeAngles = spokeAngles?.clone()
        this.isRingVisible = show
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!isRingVisible) return

        // ── Anillo exterior ───────────────────────────────────────────────────
        canvas.drawCircle(cx, cy, radiusOuter, paintRingOuter)

        // ── Anillo interior (radio de los botones) ────────────────────────────
        canvas.drawCircle(cx, cy, radiusInner, paintRingInner)

        // ── Spokes (líneas radiales) ──────────────────────────────────────────
        val angles = spokeAngles ?: return
        val density = resources.displayMetrics.density
        val fabR = radiusInner * 0.28f
        for (angleDeg in angles) {
            val rad = Math.toRadians(angleDeg)
            val c = cos(rad).toFloat()
            val s = sin(rad).toFloat()
            canvas.drawLine(
                cx + c * fabR,
                cy + s * fabR,
                cx + c * (radiusInner - 24 * density),
                cy + s * (radiusInner - 24 * density),
                paintSpoke
            )
        }
    }
}
