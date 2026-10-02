package com.limelight.ui.viewport

import android.graphics.PointF
import android.graphics.Rect
import kotlin.math.abs

/**
 * KeyboardAvoidanceEngine — Decide cuánto panear el viewport cuando
 * aparece un teclado o un overlay inferior.
 */
class KeyboardAvoidanceEngine(private val density: Float) {

    // ─── Resultado ──────────────────────────────────────────────────────────
    class AvoidanceResult private constructor(
        /** true si se necesita panear el viewport */
        @JvmField val needsPan: Boolean,
        /** Cantidad de pixels a panear en Y (negativo = hacia arriba) */
        @JvmField val panY: Float,
        /** Margen de seguridad mantenido sobre el teclado */
        @JvmField val safeMargin: Float
    ) {
        companion object {
            /** No se necesita panear */
            @JvmStatic
            fun noPan(): AvoidanceResult {
                return AvoidanceResult(false, 0f, 0f)
            }

            /** Panear hacia arriba la cantidad especificada */
            @JvmStatic
            fun panUp(amount: Float, margin: Float): AvoidanceResult {
                return AvoidanceResult(true, -abs(amount), margin)
            }
        }
    }

    companion object {
        /** Margen de seguridad sobre el teclado en dp */
        private const val SAFE_MARGIN_DP = 32f
        /** Si no tenemos posición de cursor, usar un porcentaje conservador */
        private const val CONSERVATIVE_RATIO = 0.35f
    }

    val safeMarginPx: Float = SAFE_MARGIN_DP * density

    /**
     * Calcula cuánto panear el viewport para evitar que el cursor quede
     * oculto bajo el teclado.
     */
    fun calculateAvoidance(
        keyboardBounds: Rect?,
        cursorScreenPos: PointF?,
        viewportRect: Rect,
        currentScale: Float
    ): AvoidanceResult {
        if (keyboardBounds == null || keyboardBounds.isEmpty) {
            return AvoidanceResult.noPan()
        }

        val keyboardTop = keyboardBounds.top.toFloat()
        val safeBottom = keyboardTop - safeMarginPx

        // ─── Sin posición de cursor conocida ────────────────────────────
        if (cursorScreenPos == null) {
            val viewportHeight = viewportRect.height().toFloat()
            val keyboardHeight = keyboardBounds.height().toFloat()

            // Solo panear si el teclado ocupa más del 25% de la pantalla
            if (keyboardHeight < viewportHeight * 0.25f) {
                return AvoidanceResult.noPan()
            }

            // Panear una fracción conservadora
            val conservativePan = keyboardHeight * CONSERVATIVE_RATIO
            return AvoidanceResult.panUp(conservativePan, safeMarginPx)
        }

        // ─── Con posición de cursor conocida ────────────────────────────
        val cursorY = cursorScreenPos.y

        // Caso A: el cursor está en la zona segura (arriba del teclado)
        if (cursorY < safeBottom) {
            return AvoidanceResult.noPan()
        }

        // Caso B: el cursor está en la zona ocluida → panear lo mínimo
        val requiredPan = cursorY - safeBottom + safeMarginPx
        return AvoidanceResult.panUp(requiredPan, safeMarginPx)
    }

    /**
     * Calcula la restauración cuando el teclado se oculta.
     */
    fun calculateRestore(): AvoidanceResult {
        return AvoidanceResult.noPan()
    }
}
