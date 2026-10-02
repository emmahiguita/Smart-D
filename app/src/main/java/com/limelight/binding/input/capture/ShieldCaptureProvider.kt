package com.limelight.binding.input.capture

import android.content.Context
import android.hardware.input.InputManager
import android.view.MotionEvent
import java.lang.reflect.Field
import java.lang.reflect.Method

class ShieldCaptureProvider(private val context: Context) : InputCaptureProvider() {

    companion object {
        private var nvExtensionSupported = false
        private var methodSetCursorVisibility: Method? = null
        private var AXIS_RELATIVE_X = 0
        private var AXIS_RELATIVE_Y = 0

        init {
            try {
                methodSetCursorVisibility = InputManager::class.java.getMethod("setCursorVisibility", Boolean::class.javaPrimitiveType)
                val fieldRelX: Field = MotionEvent::class.java.getField("AXIS_RELATIVE_X")
                val fieldRelY: Field = MotionEvent::class.java.getField("AXIS_RELATIVE_Y")

                AXIS_RELATIVE_X = fieldRelX.get(null) as Int
                AXIS_RELATIVE_Y = fieldRelY.get(null) as Int

                nvExtensionSupported = true
            } catch (e: Exception) {
                nvExtensionSupported = false
            }
        }

        @JvmStatic
        fun isCaptureProviderSupported(): Boolean = nvExtensionSupported
    }

    private fun setCursorVisibility(visible: Boolean): Boolean {
        return try {
            methodSetCursorVisibility?.invoke(context.getSystemService(Context.INPUT_SERVICE), visible)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override fun hideCursor() {
        super.hideCursor()
        setCursorVisibility(false)
    }

    override fun showCursor() {
        super.showCursor()
        setCursorVisibility(true)
    }

    override fun eventHasRelativeMouseAxes(event: MotionEvent): Boolean {
        return event.pointerCount == 1 && event.actionIndex == 0 &&
                event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE
    }

    override fun getRelativeAxisX(event: MotionEvent): Float {
        return event.getAxisValue(AXIS_RELATIVE_X)
    }

    override fun getRelativeAxisY(event: MotionEvent): Float {
        return event.getAxisValue(AXIS_RELATIVE_Y)
    }
}
