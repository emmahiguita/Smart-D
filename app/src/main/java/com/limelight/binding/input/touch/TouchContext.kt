package com.limelight.binding.input.touch

interface TouchContext {
    val actionIndex: Int
    fun setPointerCount(pointerCount: Int)
    fun touchDownEvent(eventX: Int, eventY: Int, eventTime: Long, isNewFinger: Boolean): Boolean
    fun touchMoveEvent(eventX: Int, eventY: Int, eventTime: Long): Boolean
    fun touchUpEvent(eventX: Int, eventY: Int, eventTime: Long)
    fun cancelTouch()
    val isCancelled: Boolean

    fun interface OnRightClickListener {
        fun onRightClick(x: Float, y: Float)
    }
    fun setOnRightClickListener(listener: OnRightClickListener?)

    fun interface OnLeftClickListener {
        fun onLeftClick(x: Float, y: Float)
    }
    fun setOnLeftClickListener(listener: OnLeftClickListener?)
}
