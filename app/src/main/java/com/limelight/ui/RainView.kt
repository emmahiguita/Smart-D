package com.limelight.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import java.util.Random

/**
 * Animación de lluvia ligera dibujada sobre el fondo en Kotlin.
 */
class RainView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val DROP_COUNT = 90
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val random = Random()

    private var x: FloatArray? = null
    private var y: FloatArray? = null
    private var speed: FloatArray? = null
    private var length: FloatArray? = null
    private var initialized = false
    private var running = false

    init {
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.argb(120, 125, 211, 252)
        paint.strokeWidth = dp(1.4f)
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    private fun seed(w: Int, h: Int) {
        val xs = FloatArray(DROP_COUNT)
        val ys = FloatArray(DROP_COUNT)
        val speeds = FloatArray(DROP_COUNT)
        val lengths = FloatArray(DROP_COUNT)
        x = xs
        y = ys
        speed = speeds
        length = lengths

        for (i in 0 until DROP_COUNT) {
            respawn(i, w, h, true)
        }
        initialized = true
    }

    private fun respawn(i: Int, w: Int, h: Int, anywhere: Boolean) {
        val xs = x ?: return
        val ys = y ?: return
        val speeds = speed ?: return
        val lengths = length ?: return

        xs[i] = random.nextFloat() * w
        ys[i] = if (anywhere) random.nextFloat() * h else -random.nextFloat() * dp(60f)
        speeds[i] = dp(9f) + random.nextFloat() * dp(16f)
        lengths[i] = dp(10f) + random.nextFloat() * dp(22f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            seed(w, h)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!initialized || !running) {
            return
        }
        val xs = x ?: return
        val ys = y ?: return
        val lengths = length ?: return
        val speeds = speed ?: return

        val w = width
        val h = height
        for (i in 0 until DROP_COUNT) {
            canvas.drawLine(xs[i], ys[i], xs[i], ys[i] + lengths[i], paint)
            ys[i] += speeds[i]
            if (ys[i] > h) {
                respawn(i, w, h, false)
            }
        }
        postInvalidateOnAnimation()
    }

    fun start() {
        if (!running) {
            running = true
            postInvalidateOnAnimation()
        }
    }

    fun stop() {
        running = false
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun dp(value: Float): Float {
        return value * resources.displayMetrics.density
    }
}