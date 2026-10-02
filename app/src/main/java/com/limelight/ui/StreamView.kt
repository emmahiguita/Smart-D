package com.limelight.ui

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.SurfaceView

class StreamView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : SurfaceView(context, attrs, defStyleAttr, defStyleRes) {

    var desiredAspectRatio: Double = 0.0
    var inputCallbacks: InputCallbacks? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (desiredAspectRatio == 0.0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        val measuredHeight: Int
        val measuredWidth: Int
        if (widthSize > heightSize * desiredAspectRatio) {
            measuredHeight = heightSize
            measuredWidth = (measuredHeight * desiredAspectRatio).toInt()
        } else {
            measuredWidth = widthSize
            measuredHeight = (measuredWidth / desiredAspectRatio).toInt()
        }

        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        val callbacks = inputCallbacks
        if (callbacks != null) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (callbacks.handleKeyDown(event)) {
                    return true
                }
            } else if (event.action == KeyEvent.ACTION_UP) {
                if (callbacks.handleKeyUp(event)) {
                    return true
                }
            }
        }

        return super.onKeyPreIme(keyCode, event)
    }

    interface InputCallbacks {
        fun handleKeyUp(event: KeyEvent): Boolean
        fun handleKeyDown(event: KeyEvent): Boolean
    }
}