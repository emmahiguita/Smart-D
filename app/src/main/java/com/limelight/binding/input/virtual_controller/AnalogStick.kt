package com.limelight.binding.input.virtual_controller

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import java.util.ArrayList
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * This is an analog stick on screen element. It is used to get 2-Axis user input.
 */
open class AnalogStick(
    controller: VirtualController,
    context: Context,
    elementId: Int
) : VirtualControllerElement(controller, context, elementId) {

    interface AnalogStickListener {
        fun onMovement(x: Float, y: Float)
        fun onClick()
        fun onDoubleClick()
        fun onRevoke()
    }

    private enum class StickState {
        NO_MOVEMENT,
        MOVED_IN_DEAD_ZONE,
        MOVED_ACTIVE
    }

    private enum class ClickState {
        SINGLE,
        DOUBLE
    }

    private var circleStick = true

    private var radiusComplete = 0f
    private var radiusAnalogStick = 0f
    private var radiusDeadZone = 0f

    private var relativeX = 0f
    private var relativeY = 0f

    private var movementRadius = 0.0
    private var movementAngle = 0.0

    private var positionStickX = 0f
    private var positionStickY = 0f

    private val paint = Paint()

    private var stickState = StickState.NO_MOVEMENT
    private var clickState = ClickState.SINGLE

    private val listeners: MutableList<AnalogStickListener> = ArrayList()
    private var timeLastClick: Long = 0

    init {
        positionStickX = (width / 2).toFloat()
        positionStickY = (height / 2).toFloat()
    }

    fun addAnalogStickListener(listener: AnalogStickListener) {
        listeners.add(listener)
    }

    private fun notifyOnMovement(x: Float, y: Float) {
        _DBG("movement x: $x movement y: $y")
        for (listener in listeners) {
            listener.onMovement(x, y)
        }
    }

    private fun notifyOnClick() {
        _DBG("click")
        for (listener in listeners) {
            listener.onClick()
        }
    }

    private fun notifyOnDoubleClick() {
        _DBG("double click")
        for (listener in listeners) {
            listener.onDoubleClick()
        }
    }

    private fun notifyOnRevoke() {
        _DBG("revoke")
        for (listener in listeners) {
            listener.onRevoke()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        radiusComplete = getPercent((getCorrectWidth() / 2).toFloat(), 100f) - 2 * getDefaultStrokeWidth()
        radiusDeadZone = getPercent((getCorrectWidth() / 2).toFloat(), 30f)
        radiusAnalogStick = getPercent((getCorrectWidth() / 2).toFloat(), 20f)

        super.onSizeChanged(w, h, oldw, oldh)
    }

    override fun onElementDraw(canvas: Canvas) {
        canvas.drawColor(Color.TRANSPARENT)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = getDefaultStrokeWidth().toFloat()

        if (!isPressed || clickState == ClickState.SINGLE) {
            paint.color = getDefaultColor()
        } else {
            paint.color = pressedColor
        }
        canvas.drawCircle((width / 2).toFloat(), (height / 2).toFloat(), radiusComplete, paint)

        paint.color = getDefaultColor()
        canvas.drawCircle((width / 2).toFloat(), (height / 2).toFloat(), radiusDeadZone, paint)

        when (stickState) {
            StickState.NO_MOVEMENT -> {
                paint.color = getDefaultColor()
                canvas.drawCircle((width / 2).toFloat(), (height / 2).toFloat(), radiusAnalogStick, paint)
            }
            StickState.MOVED_IN_DEAD_ZONE, StickState.MOVED_ACTIVE -> {
                paint.color = pressedColor
                canvas.drawCircle(positionStickX, positionStickY, radiusAnalogStick, paint)
            }
        }
    }

    private fun updatePosition(eventTime: Long) {
        val complete = radiusComplete - radiusAnalogStick

        val correlatedY = (sin(Math.PI / 2 - movementAngle) * movementRadius).toFloat()
        val correlatedX = (cos(Math.PI / 2 - movementAngle) * movementRadius).toFloat()

        positionStickX = width / 2f - correlatedX
        positionStickY = height / 2f - correlatedY

        stickState = if (stickState == StickState.MOVED_ACTIVE ||
            eventTime - timeLastClick > TIMEOUT_DEADZONE ||
            movementRadius > radiusDeadZone
        ) {
            StickState.MOVED_ACTIVE
        } else {
            StickState.MOVED_IN_DEAD_ZONE
        }

        if (stickState == StickState.MOVED_ACTIVE) {
            notifyOnMovement(-correlatedX / complete, correlatedY / complete)
        }
    }

    override fun onElementTouchEvent(event: MotionEvent): Boolean {
        val lastClickState = clickState

        relativeX = -(width / 2f - event.x)
        relativeY = -(height / 2f - event.y)

        movementRadius = getMovementRadius(relativeX, relativeY)
        movementAngle = getAngle(relativeX, relativeY)

        if (movementRadius > radiusComplete && !isPressed) {
            return false
        }

        if (movementRadius > (radiusComplete - radiusAnalogStick)) {
            movementRadius = (radiusComplete - radiusAnalogStick).toDouble()
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stickState = StickState.MOVED_IN_DEAD_ZONE
                if (lastClickState == ClickState.SINGLE &&
                    event.eventTime - timeLastClick <= TIMEOUT_DOUBLE_CLICK
                ) {
                    clickState = ClickState.DOUBLE
                    notifyOnDoubleClick()
                } else {
                    clickState = ClickState.SINGLE
                    notifyOnClick()
                }
                timeLastClick = event.eventTime
                isPressed = true
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                isPressed = false
            }
        }

        if (isPressed) {
            updatePosition(event.eventTime)
        } else {
            stickState = StickState.NO_MOVEMENT
            notifyOnRevoke()
            notifyOnMovement(0f, 0f)
        }

        invalidate()
        return true
    }

    companion object {
        const val SIZE_RADIUS_COMPLETE = 90
        const val SIZE_RADIUS_ANALOG_STICK = 90
        const val SIZE_RADIUS_DEADZONE = 90
        const val TIMEOUT_DOUBLE_CLICK = 350L
        const val TIMEOUT_DEADZONE = 150L

        private fun getMovementRadius(x: Float, y: Float): Double {
            return sqrt((x * x + y * y).toDouble())
        }

        private fun getAngle(wayX: Float, wayY: Float): Double {
            if (wayX == 0f) {
                return if (wayY < 0) Math.PI else 0.0
            } else if (wayY == 0f) {
                if (wayX > 0) {
                    return Math.PI * 3 / 2
                } else if (wayX < 0) {
                    return Math.PI * 1 / 2
                }
            }

            return if (wayX > 0) {
                if (wayY < 0) {
                    3 * Math.PI / 2 + atan((-wayY / wayX).toDouble())
                } else {
                    Math.PI + atan((wayX / wayY).toDouble())
                }
            } else {
                if (wayY > 0) {
                    Math.PI / 2 + atan((wayY / -wayX).toDouble())
                } else {
                    0.0 + atan((-wayX / -wayY).toDouble())
                }
            }
        }
    }
}
