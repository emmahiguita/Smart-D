package com.limelight.binding.input.touch

import android.os.Handler
import android.os.Looper
import android.view.View
import com.limelight.nvstream.NvConnection
import com.limelight.nvstream.input.MouseButtonPacket
import kotlin.math.pow
import kotlin.math.sqrt

class AbsoluteTouchContext(
    private val conn: NvConnection,
    override val actionIndex: Int,
    private val targetView: View
) : TouchContext {

    private var lastTouchDownX = 0
    private var lastTouchDownY = 0
    private var lastTouchDownTime = 0L
    private var lastTouchUpX = 0
    private var lastTouchUpY = 0
    private var lastTouchUpTime = 0L
    private var lastTouchLocationX = 0
    private var lastTouchLocationY = 0
    private var cancelled = false
    private var confirmedLongPress = false
    private var confirmedTap = false
    private var onRightClickListener: TouchContext.OnRightClickListener? = null
    private var onLeftClickListener: TouchContext.OnLeftClickListener? = null

    private val handler = Handler(Looper.getMainLooper())

    private val longPressRunnable = Runnable {
        cancelTapDownTimer()
        confirmedLongPress = true
        if (confirmedTap) {
            conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT)
        }
        onRightClickListener?.onRightClick(lastTouchDownX.toFloat(), lastTouchDownY.toFloat())
        conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT)
    }

    private val tapDownRunnable = Runnable {
        tapConfirmed()
    }

    private val leftButtonUpRunnable = Runnable {
        conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT)
    }

    companion object {
        private const val SCROLL_SPEED_FACTOR = 3
        private const val LONG_PRESS_TIME_THRESHOLD = 650L
        private const val LONG_PRESS_DISTANCE_THRESHOLD = 30.0
        private const val DOUBLE_TAP_TIME_THRESHOLD = 250L
        private const val DOUBLE_TAP_DISTANCE_THRESHOLD = 60.0
        private const val TOUCH_DOWN_DEAD_ZONE_TIME_THRESHOLD = 100L
        private const val TOUCH_DOWN_DEAD_ZONE_DISTANCE_THRESHOLD = 20.0
    }

    override fun setOnRightClickListener(listener: TouchContext.OnRightClickListener?) {
        this.onRightClickListener = listener
    }

    override fun setOnLeftClickListener(listener: TouchContext.OnLeftClickListener?) {
        this.onLeftClickListener = listener
    }

    override fun touchDownEvent(eventX: Int, eventY: Int, eventTime: Long, isNewFinger: Boolean): Boolean {
        if (!isNewFinger) {
            return true
        }

        lastTouchDownX = eventX
        lastTouchLocationX = eventX
        lastTouchDownY = eventY
        lastTouchLocationY = eventY
        lastTouchDownTime = eventTime
        cancelled = false
        confirmedTap = false
        confirmedLongPress = false

        if (actionIndex == 0) {
            startTapDownTimer()
            startLongPressTimer()
        }

        return true
    }

    private fun distanceExceeds(deltaX: Int, deltaY: Int, limit: Double): Boolean {
        return sqrt(deltaX.toDouble().pow(2.0) + deltaY.toDouble().pow(2.0)) > limit
    }

    private fun updatePosition(eventXParam: Int, eventYParam: Int) {
        val eventX = Math.min(Math.max(eventXParam, 0), targetView.width)
        val eventY = Math.min(Math.max(eventYParam, 0), targetView.height)

        conn.sendMousePosition(eventX.toShort(), eventY.toShort(), targetView.width.toShort(), targetView.height.toShort())
    }

    override fun touchUpEvent(eventX: Int, eventY: Int, eventTime: Long) {
        if (cancelled) {
            return
        }

        if (actionIndex == 0) {
            cancelLongPressTimer()
            cancelTapDownTimer()

            if (confirmedLongPress) {
                conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT)
            } else if (confirmedTap) {
                conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT)
            } else {
                tapConfirmed()
                handler.removeCallbacks(leftButtonUpRunnable)
                handler.postDelayed(leftButtonUpRunnable, 100)
            }
        }

        lastTouchUpX = eventX
        lastTouchLocationX = eventX
        lastTouchUpY = eventY
        lastTouchLocationY = eventY
        lastTouchUpTime = eventTime
    }

    private fun startLongPressTimer() {
        cancelLongPressTimer()
        handler.postDelayed(longPressRunnable, LONG_PRESS_TIME_THRESHOLD)
    }

    private fun cancelLongPressTimer() {
        handler.removeCallbacks(longPressRunnable)
    }

    private fun startTapDownTimer() {
        cancelTapDownTimer()
        handler.postDelayed(tapDownRunnable, TOUCH_DOWN_DEAD_ZONE_TIME_THRESHOLD)
    }

    private fun cancelTapDownTimer() {
        handler.removeCallbacks(tapDownRunnable)
    }

    private fun tapConfirmed() {
        if (confirmedTap || confirmedLongPress) {
            return
        }

        confirmedTap = true
        cancelTapDownTimer()

        if (lastTouchDownTime - lastTouchUpTime > DOUBLE_TAP_TIME_THRESHOLD ||
            distanceExceeds(lastTouchDownX - lastTouchUpX, lastTouchDownY - lastTouchUpY, DOUBLE_TAP_DISTANCE_THRESHOLD)
        ) {
            updatePosition(lastTouchDownX, lastTouchDownY)
        }

        onLeftClickListener?.onLeftClick(lastTouchDownX.toFloat(), lastTouchDownY.toFloat())
        conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT)
    }

    override fun touchMoveEvent(eventX: Int, eventY: Int, eventTime: Long): Boolean {
        if (cancelled) {
            return true
        }

        if (actionIndex == 0) {
            if (distanceExceeds(eventX - lastTouchDownX, eventY - lastTouchDownY, LONG_PRESS_DISTANCE_THRESHOLD)) {
                cancelLongPressTimer()
            }

            if (confirmedTap || distanceExceeds(eventX - lastTouchDownX, eventY - lastTouchDownY, TOUCH_DOWN_DEAD_ZONE_DISTANCE_THRESHOLD)) {
                tapConfirmed()
                updatePosition(eventX, eventY)
            }
        } else if (actionIndex == 1) {
            conn.sendMouseHighResScroll(((eventY - lastTouchLocationY) * SCROLL_SPEED_FACTOR).toShort())
        }

        lastTouchLocationX = eventX
        lastTouchLocationY = eventY

        return true
    }

    override fun cancelTouch() {
        cancelled = true
        cancelLongPressTimer()
        cancelTapDownTimer()

        if (confirmedLongPress) {
            conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT)
        } else if (confirmedTap) {
            conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT)
        }
    }

    override val isCancelled: Boolean
        get() = cancelled

    override fun setPointerCount(pointerCount: Int) {
        if (actionIndex == 0 && pointerCount > 1) {
            cancelTouch()
        }
    }
}
