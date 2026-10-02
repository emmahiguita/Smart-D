package com.limelight.smartdisplay.motion

import android.view.View

/**
 * CoordinateTransformer — Single Responsibility Principle (SOLID)
 *
 * Responsable exclusivamente del mapeo bidireccional de coordenadas
 * entre la vista de renderizado de Android y el espacio de resolución del PC.
 */
interface CoordinateTransformer {
    fun androidToPc(x: Float, y: Float): Pair<Float, Float>
    fun pcToAndroid(x: Float, y: Float): Pair<Float, Float>
    fun updatePcResolution(width: Int, height: Int)
}

/**
 * Implementación desacoplada basada en una [View] de referencia y las dimensiones del PC.
 */
class ViewCoordinateTransformer(
    private val view: View,
    private var pcWidth: Int = 1920,
    private var pcHeight: Int = 1080
) : CoordinateTransformer {

    override fun updatePcResolution(width: Int, height: Int) {
        pcWidth = width.coerceAtLeast(1)
        pcHeight = height.coerceAtLeast(1)
    }

    override fun androidToPc(x: Float, y: Float): Pair<Float, Float> {
        val sw = view.width.toFloat().coerceAtLeast(1f)
        val sh = view.height.toFloat().coerceAtLeast(1f)
        return Pair(x / sw * pcWidth, y / sh * pcHeight)
    }

    override fun pcToAndroid(x: Float, y: Float): Pair<Float, Float> {
        val sw = view.width.toFloat().coerceAtLeast(1f)
        val sh = view.height.toFloat().coerceAtLeast(1f)
        return Pair(x / pcWidth * sw, y / pcHeight * sh)
    }
}
