package com.limelight.smartdisplay.motion

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.content.Context
import com.limelight.smartdisplay.AppContainer

/**
 * WindowOverlayView — Smart-D Window Motion Engine
 *
 * View transparente dibujada sobre StreamView. Muestra el borde y relleno
 * de acento del tema durante el drag/snap activo de ventanas.
 * Desaparece (fade-out) cuando el ACK del PC confirma la posicion real.
 *
 * Tipicamente anadida como overlay en el FrameLayout que contiene StreamView.
 */
class WindowOverlayView(context: Context) : View(context) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(38, 34, 211, 238)  // cian 15% alpha por defecto
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style  = Paint.Style.STROKE
        strokeWidth = 2.5f * resources.displayMetrics.density
        color  = Color.argb(200, 34, 211, 238) // cian 78%
    }

    private val cornerRadius = 8f * resources.displayMetrics.density

    // Rects actuales a dibujar (actualizado desde el animator)
    @Volatile private var currentRects: Map<Int, RectF> = emptyMap()

    // Control de visibilidad global del overlay
    @Volatile var overlayActive: Boolean = false
        set(value) {
            field = value
            if (!value) currentRects = emptyMap()
            postInvalidate()
        }

    init {
        // Fondo completamente transparente
        setBackgroundColor(Color.TRANSPARENT)
        setLayerType(LAYER_TYPE_HARDWARE, null)
        // Usar el color de acento del tema actual
        try {
            val accent = AppContainer.instance.themeManager.accentColor()
            val r = (accent shr 16) and 0xFF
            val g = (accent shr 8)  and 0xFF
            val b =  accent         and 0xFF
            fillPaint.color   = Color.argb(38,  r, g, b)
            strokePaint.color = Color.argb(200, r, g, b)
        } catch (_: Exception) { /* mantener cian por defecto */ }
    }

    /**
     * Llamado desde [LocalWindowAnimator.onFrame] en el hilo principal.
     * @param rects Mapa hwnd → RectF en coordenadas de esta View.
     */
    fun update(rects: Map<Int, RectF>) {
        currentRects = rects
        overlayActive = rects.isNotEmpty()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!overlayActive) return
        for ((_, rect) in currentRects) {
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, fillPaint)
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, strokePaint)
        }
    }
}
