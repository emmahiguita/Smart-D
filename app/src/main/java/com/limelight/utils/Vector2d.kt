package com.limelight.utils

import kotlin.math.hypot

class Vector2d(x: Float = 0f, y: Float = 0f) {
    var x: Float = 0f
        private set
    var y: Float = 0f
        private set
    var magnitude: Double = 0.0
        private set

    init {
        initialize(x, y)
    }

    fun initialize(x: Float, y: Float) {
        this.x = x
        this.y = y
        this.magnitude = hypot(x.toDouble(), y.toDouble())
    }

    fun getNormalized(vector: Vector2d) {
        vector.initialize((x / magnitude).toFloat(), (y / magnitude).toFloat())
    }

    fun scalarMultiply(factor: Double) {
        initialize((x * factor).toFloat(), (y * factor).toFloat())
    }

    fun setX(x: Float) {
        initialize(x, this.y)
    }

    fun setY(y: Float) {
        initialize(this.x, y)
    }

    companion object {
        @JvmField
        val ZERO = Vector2d()
    }
}
