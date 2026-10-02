package com.limelight.binding.input.capture

import android.view.MotionEvent

abstract class InputCaptureProvider {
    @JvmField
    protected var isCapturing: Boolean = false
    @JvmField
    protected var isCursorVisible: Boolean = false

    open fun enableCapture() {
        isCapturing = true
        hideCursor()
    }

    open fun disableCapture() {
        isCapturing = false
        showCursor()
    }

    open fun destroy() {}

    open val isCapturingEnabled: Boolean
        get() = isCapturing

    open val isCapturingActive: Boolean
        get() = isCapturing

    open fun showCursor() {
        isCursorVisible = true
    }

    open fun hideCursor() {
        isCursorVisible = false
    }

    open fun eventHasRelativeMouseAxes(event: MotionEvent): Boolean = false

    open fun getRelativeAxisX(event: MotionEvent): Float = 0f

    open fun getRelativeAxisY(event: MotionEvent): Float = 0f

    open fun onWindowFocusChanged(focusActive: Boolean) {}
}
