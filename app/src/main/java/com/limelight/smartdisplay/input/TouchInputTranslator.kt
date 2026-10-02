package com.limelight.smartdisplay.input

import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import com.limelight.nvstream.NvConnection
import com.limelight.nvstream.jni.MoonBridge
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * TouchInputTranslator — Single Responsibility Principle (SRP)
 *
 * Responsabilidad única: Normalizar y traducir eventos de puntero táctil y lápiz óptico
 * (Stylus/Pen) de Android hacia los paquetes de protocolo Moonlight (`sendTouchEvent`, `sendPenEvent`).
 */
object TouchInputTranslator {

    fun getLiTouchTypeFromEvent(event: MotionEvent): Byte {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> MoonBridge.LI_TOUCH_EVENT_DOWN

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP -> {
                if ((event.flags and MotionEvent.FLAG_CANCELED) != 0) {
                    MoonBridge.LI_TOUCH_EVENT_CANCEL
                } else {
                    MoonBridge.LI_TOUCH_EVENT_UP
                }
            }

            MotionEvent.ACTION_MOVE -> MoonBridge.LI_TOUCH_EVENT_MOVE

            MotionEvent.ACTION_CANCEL -> MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL

            MotionEvent.ACTION_HOVER_ENTER,
            MotionEvent.ACTION_HOVER_MOVE -> MoonBridge.LI_TOUCH_EVENT_HOVER

            MotionEvent.ACTION_HOVER_EXIT -> MoonBridge.LI_TOUCH_EVENT_HOVER_LEAVE

            MotionEvent.ACTION_BUTTON_PRESS,
            MotionEvent.ACTION_BUTTON_RELEASE -> MoonBridge.LI_TOUCH_EVENT_BUTTON_ONLY

            else -> -1
        }
    }

    fun getStreamViewRelativeNormalizedXY(
        view: View,
        event: MotionEvent,
        pointerIndex: Int,
        streamView: View
    ): FloatArray {
        var normalizedX = event.getX(pointerIndex)
        var normalizedY = event.getY(pointerIndex)

        if (view !== streamView) {
            normalizedX -= streamView.x
            normalizedY -= streamView.y
        }

        normalizedX = normalizedX.coerceIn(0.0f, streamView.width.toFloat())
        normalizedY = normalizedY.coerceIn(0.0f, streamView.height.toFloat())

        normalizedX /= streamView.width.toFloat().coerceAtLeast(1.0f)
        normalizedY /= streamView.height.toFloat().coerceAtLeast(1.0f)

        return floatArrayOf(normalizedX, normalizedY)
    }

    private fun normalizeValueInRange(value: Float, range: InputDevice.MotionRange): Float {
        return (value - range.min) / range.range
    }

    fun getPressureOrDistance(event: MotionEvent, pointerIndex: Int): Float {
        val dev = event.device
        return when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER,
            MotionEvent.ACTION_HOVER_MOVE,
            MotionEvent.ACTION_HOVER_EXIT -> {
                if (dev != null) {
                    val distanceRange = dev.getMotionRange(MotionEvent.AXIS_DISTANCE, event.source)
                    if (distanceRange != null) {
                        return normalizeValueInRange(event.getAxisValue(MotionEvent.AXIS_DISTANCE, pointerIndex), distanceRange)
                    }
                }
                0.0f
            }
            else -> event.getPressure(pointerIndex)
        }
    }

    fun getRotationDegrees(event: MotionEvent, pointerIndex: Int): Short {
        val dev = event.device
        if (dev != null) {
            if (dev.getMotionRange(MotionEvent.AXIS_ORIENTATION, event.source) != null) {
                var rotationDegrees = Math.toDegrees(event.getOrientation(pointerIndex).toDouble()).toInt().toShort()
                if (rotationDegrees < 0) {
                    rotationDegrees = (rotationDegrees + 360).toShort()
                }
                return rotationDegrees
            }
        }
        return MoonBridge.LI_ROT_UNKNOWN
    }

    private fun polarToCartesian(r: Float, theta: Float): FloatArray {
        return floatArrayOf((r * cos(theta.toDouble())).toFloat(), (r * sin(theta.toDouble())).toFloat())
    }

    private fun cartesianToR(point: FloatArray): Float {
        return sqrt((point[0] * point[0] + point[1] * point[1]).toDouble()).toFloat()
    }

    fun getStreamViewNormalizedContactArea(
        event: MotionEvent,
        pointerIndex: Int,
        streamView: View
    ): FloatArray {
        val orientation = if (event.device == null || event.device.getMotionRange(MotionEvent.AXIS_ORIENTATION, event.source) == null) {
            (Math.PI / 4).toFloat()
        } else {
            event.getOrientation(pointerIndex)
        }

        val contactAreaMajor: Float
        val contactAreaMinor: Float
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER,
            MotionEvent.ACTION_HOVER_MOVE,
            MotionEvent.ACTION_HOVER_EXIT -> {
                contactAreaMajor = event.getToolMajor(pointerIndex)
                contactAreaMinor = event.getToolMinor(pointerIndex)
            }
            else -> {
                contactAreaMajor = event.getTouchMajor(pointerIndex)
                contactAreaMinor = event.getTouchMinor(pointerIndex)
            }
        }

        val contactAreaMajorCartesian = polarToCartesian(contactAreaMajor, orientation)
        val contactAreaMinorCartesian = polarToCartesian(contactAreaMinor, (orientation + (Math.PI / 2)).toFloat())

        val svWidth = streamView.width.toFloat().coerceAtLeast(1.0f)
        val svHeight = streamView.height.toFloat().coerceAtLeast(1.0f)

        contactAreaMajorCartesian[0] = abs(contactAreaMajorCartesian[0]).coerceAtMost(svWidth) / svWidth
        contactAreaMinorCartesian[0] = abs(contactAreaMinorCartesian[0]).coerceAtMost(svWidth) / svWidth
        contactAreaMajorCartesian[1] = abs(contactAreaMajorCartesian[1]).coerceAtMost(svHeight) / svHeight
        contactAreaMinorCartesian[1] = abs(contactAreaMinorCartesian[1]).coerceAtMost(svHeight) / svHeight

        return floatArrayOf(cartesianToR(contactAreaMajorCartesian), cartesianToR(contactAreaMinorCartesian))
    }

    fun convertToolTypeToStylusToolType(event: MotionEvent, pointerIndex: Int): Byte {
        return when (event.getToolType(pointerIndex)) {
            MotionEvent.TOOL_TYPE_ERASER -> MoonBridge.LI_TOOL_TYPE_ERASER
            MotionEvent.TOOL_TYPE_STYLUS -> MoonBridge.LI_TOOL_TYPE_PEN
            else -> MoonBridge.LI_TOOL_TYPE_UNKNOWN
        }
    }

    fun sendPenEventForPointer(
        view: View,
        event: MotionEvent,
        eventType: Byte,
        toolType: Byte,
        pointerIndex: Int,
        conn: NvConnection,
        streamView: View
    ): Boolean {
        var penButtons: Byte = 0
        if ((event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0) {
            penButtons = (penButtons.toInt() or MoonBridge.LI_PEN_BUTTON_PRIMARY.toInt()).toByte()
        }
        if ((event.buttonState and MotionEvent.BUTTON_STYLUS_SECONDARY) != 0) {
            penButtons = (penButtons.toInt() or MoonBridge.LI_PEN_BUTTON_SECONDARY.toInt()).toByte()
        }

        var tiltDegrees = MoonBridge.LI_TILT_UNKNOWN
        val dev = event.device
        if (dev != null) {
            if (dev.getMotionRange(MotionEvent.AXIS_TILT, event.source) != null) {
                tiltDegrees = Math.toDegrees(event.getAxisValue(MotionEvent.AXIS_TILT, pointerIndex).toDouble()).toInt().toByte()
            }
        }

        val normalizedCoords = getStreamViewRelativeNormalizedXY(view, event, pointerIndex, streamView)
        val normalizedContactArea = getStreamViewNormalizedContactArea(event, pointerIndex, streamView)
        return conn.sendPenEvent(
            eventType, toolType, penButtons,
            normalizedCoords[0], normalizedCoords[1],
            getPressureOrDistance(event, pointerIndex),
            normalizedContactArea[0], normalizedContactArea[1],
            getRotationDegrees(event, pointerIndex), tiltDegrees
        ) != MoonBridge.LI_ERR_UNSUPPORTED
    }

    fun trySendPenEvent(
        view: View,
        event: MotionEvent,
        conn: NvConnection?,
        streamView: View
    ): Boolean {
        if (conn == null) return false
        val eventType = getLiTouchTypeFromEvent(event)
        if (eventType < 0) return false

        return when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                var handledStylusEvent = false
                for (i in 0 until event.pointerCount) {
                    val toolType = convertToolTypeToStylusToolType(event, i)
                    if (toolType == MoonBridge.LI_TOOL_TYPE_UNKNOWN) continue
                    handledStylusEvent = true
                    if (!sendPenEventForPointer(view, event, eventType, toolType, i, conn, streamView)) {
                        return false
                    }
                }
                handledStylusEvent
            }
            MotionEvent.ACTION_CANCEL -> {
                conn.sendPenEvent(
                    MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL, MoonBridge.LI_TOOL_TYPE_UNKNOWN, 0.toByte(),
                    0f, 0f, 0f, 0f, 0f,
                    MoonBridge.LI_ROT_UNKNOWN, MoonBridge.LI_TILT_UNKNOWN
                ) != MoonBridge.LI_ERR_UNSUPPORTED
            }
            else -> {
                val toolType = convertToolTypeToStylusToolType(event, event.actionIndex)
                if (toolType == MoonBridge.LI_TOOL_TYPE_UNKNOWN) return false
                sendPenEventForPointer(view, event, eventType, toolType, event.actionIndex, conn, streamView)
            }
        }
    }

    fun sendTouchEventForPointer(
        view: View,
        event: MotionEvent,
        eventType: Byte,
        pointerIndex: Int,
        conn: NvConnection,
        streamView: View
    ): Boolean {
        val normalizedCoords = getStreamViewRelativeNormalizedXY(view, event, pointerIndex, streamView)
        val normalizedContactArea = getStreamViewNormalizedContactArea(event, pointerIndex, streamView)
        return conn.sendTouchEvent(
            eventType, event.getPointerId(pointerIndex),
            normalizedCoords[0], normalizedCoords[1],
            getPressureOrDistance(event, pointerIndex),
            normalizedContactArea[0], normalizedContactArea[1],
            getRotationDegrees(event, pointerIndex)
        ) != MoonBridge.LI_ERR_UNSUPPORTED
    }

    fun trySendTouchEvent(
        view: View,
        event: MotionEvent,
        conn: NvConnection?,
        streamView: View
    ): Boolean {
        if (conn == null) return false
        val eventType = getLiTouchTypeFromEvent(event)
        if (eventType < 0) return false

        return when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    if (!sendTouchEventForPointer(view, event, eventType, i, conn, streamView)) {
                        return false
                    }
                }
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                conn.sendTouchEvent(
                    MoonBridge.LI_TOUCH_EVENT_CANCEL_ALL, 0,
                    0f, 0f, 0f, 0f, 0f,
                    MoonBridge.LI_ROT_UNKNOWN
                ) != MoonBridge.LI_ERR_UNSUPPORTED
            }
            else -> {
                sendTouchEventForPointer(view, event, eventType, event.actionIndex, conn, streamView)
            }
        }
    }
}
