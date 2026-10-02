package com.limelight.binding.input.virtual_controller

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import androidx.core.content.ContextCompat
import java.util.ArrayList

/**
 * This is a digital button on screen element. It is used to get click and double click user input.
 */
open class DigitalButton(
    controller: VirtualController,
    elementId: Int,
    var layer: Int,
    context: Context
) : VirtualControllerElement(controller, context, elementId) {

    interface DigitalButtonListener {
        fun onClick()
        fun onLongClick()
        fun onRelease()
    }

    private val listeners: MutableList<DigitalButtonListener> = ArrayList()
    private var text = ""
    private var icon = -1
    private val timerLongClickTimeout = 3000L
    private val longClickRunnable = Runnable {
        onLongClickCallback()
    }

    private val paint = Paint()
    private val rect = RectF()

    private var movingButton: DigitalButton? = null

    fun inRange(x: Float, y: Float): Boolean {
        return (this.x < x && this.x + width > x) &&
                (this.y < y && this.y + height > y)
    }

    fun checkMovement(x: Float, y: Float, movingButton: DigitalButton): Boolean {
        if (movingButton.layer != this.layer) {
            return false
        }

        val wasPressed = isPressed

        if ((this.movingButton == null || movingButton == this.movingButton) && this.inRange(x, y)) {
            if (this.isPressed != movingButton.isPressed) {
                this.isPressed = movingButton.isPressed
            }
        } else if (movingButton == this.movingButton) {
            this.isPressed = false
        }

        if (wasPressed != isPressed) {
            if (isPressed) {
                this.movingButton = movingButton
                onClickCallback()
            } else {
                this.movingButton = null
                onReleaseCallback()
            }

            invalidate()
            return true
        }

        return false
    }

    private fun checkMovementForAllButtons(x: Float, y: Float) {
        for (element in virtualController.elements) {
            if (element !== this && element is DigitalButton) {
                element.checkMovement(x, y, this)
            }
        }
    }

    fun addDigitalButtonListener(listener: DigitalButtonListener) {
        listeners.add(listener)
    }

    fun setText(text: String) {
        this.text = text
        invalidate()
    }

    fun setIcon(id: Int) {
        this.icon = id
        invalidate()
    }

    override fun onElementDraw(canvas: Canvas) {
        canvas.drawColor(Color.TRANSPARENT)

        paint.textSize = getPercent(width.toFloat(), 25f)
        paint.textAlign = Paint.Align.CENTER
        paint.strokeWidth = getDefaultStrokeWidth().toFloat()

        paint.color = if (isPressed) pressedColor else getDefaultColor()
        paint.style = Paint.Style.STROKE

        rect.top = paint.strokeWidth
        rect.left = rect.top
        rect.right = width - rect.left
        rect.bottom = height - rect.top

        canvas.drawOval(rect, paint)

        if (icon != -1) {
            val d = ContextCompat.getDrawable(context, icon)
            if (d != null) {
                d.setBounds(5, 5, width - 5, height - 5)
                d.draw(canvas)
            }
        } else {
            paint.style = Paint.Style.FILL_AND_STROKE
            paint.strokeWidth = (getDefaultStrokeWidth() / 2).toFloat()
            canvas.drawText(text, getPercent(width.toFloat(), 50f), getPercent(height.toFloat(), 63f), paint)
        }
    }

    private fun onClickCallback() {
        _DBG("clicked")
        for (listener in listeners) {
            listener.onClick()
        }

        virtualController.handler.removeCallbacks(longClickRunnable)
        virtualController.handler.postDelayed(longClickRunnable, timerLongClickTimeout)
    }

    private fun onLongClickCallback() {
        _DBG("long click")
        for (listener in listeners) {
            listener.onLongClick()
        }
    }

    private fun onReleaseCallback() {
        _DBG("released")
        for (listener in listeners) {
            listener.onRelease()
        }

        virtualController.handler.removeCallbacks(longClickRunnable)
    }

    override fun onElementTouchEvent(event: MotionEvent): Boolean {
        val x = this.x + event.x
        val y = this.y + event.y
        val action = event.actionMasked

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                movingButton = null
                isPressed = true
                onClickCallback()
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                checkMovementForAllButtons(x, y)
                return true
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                isPressed = false
                onReleaseCallback()
                checkMovementForAllButtons(x, y)
                invalidate()
                return true
            }
        }
        return true
    }
}
