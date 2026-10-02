package com.limelight.binding.input.touch

import android.os.Handler
import android.os.Looper
import android.view.View
import com.limelight.nvstream.NvConnection
import com.limelight.nvstream.input.MouseButtonPacket
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.smartdisplay.profile.ActiveProfile
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

class RelativeTouchContext(
    private val conn: NvConnection,
    override val actionIndex: Int,
    private val referenceWidth: Int,
    private val referenceHeight: Int,
    private val targetView: View,
    private val prefConfig: PreferenceConfiguration
) : TouchContext {

    private var lastTouchX = 0
    private var lastTouchY = 0
    private var onRightClickListener: TouchContext.OnRightClickListener? = null
    private var onLeftClickListener: TouchContext.OnLeftClickListener? = null
    private var originalTouchX = 0
    private var originalTouchY = 0
    private var originalTouchTime = 0L
    private var cancelled = false
    private var confirmedMove = false
    private var confirmedDrag = false
    private var confirmedScroll = false
    private var distanceMoved = 0.0
    private var xFactor = 0.0
    private var yFactor = 0.0
    private var pointerCount = 0
    private var maxPointerCountInGesture = 0

    private val handler = Handler(Looper.getMainLooper())

    private val dragTimerRunnable = Runnable {
        if (confirmedMove) {
            return@Runnable
        }
        if (actionIndex != maxPointerCountInGesture - 1) {
            return@Runnable
        }
        confirmedDrag = true
        conn.sendMouseButtonDown(mouseButtonIndex)
    }

    private val buttonUpRunnables = arrayOf(
        Runnable { conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT) },
        Runnable { conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_MIDDLE) },
        Runnable { conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT) },
        Runnable { conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_X1) },
        Runnable { conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_X2) }
    )

    companion object {
        private const val TAP_MOVEMENT_THRESHOLD = 20
        private const val TAP_DISTANCE_THRESHOLD = 25.0
        private const val TAP_TIME_THRESHOLD = 250L
        private const val DRAG_TIME_THRESHOLD = 650L
        private const val SCROLL_SPEED_FACTOR = 5
    }

    override fun setOnRightClickListener(listener: TouchContext.OnRightClickListener?) {
        this.onRightClickListener = listener
    }

    override fun setOnLeftClickListener(listener: TouchContext.OnLeftClickListener?) {
        this.onLeftClickListener = listener
    }

    private fun isWithinTapBounds(touchX: Int, touchY: Int): Boolean {
        val xDelta = abs(touchX - originalTouchX)
        val yDelta = abs(touchY - originalTouchY)
        return xDelta <= TAP_MOVEMENT_THRESHOLD && yDelta <= TAP_MOVEMENT_THRESHOLD
    }

    private fun isTap(eventTime: Long): Boolean {
        if (confirmedDrag || confirmedMove || confirmedScroll) {
            return false
        }
        if (actionIndex + 1 != maxPointerCountInGesture) {
            return false
        }
        val timeDelta = eventTime - originalTouchTime
        return isWithinTapBounds(lastTouchX, lastTouchY) && timeDelta <= TAP_TIME_THRESHOLD
    }

    private val mouseButtonIndex: Byte
        get() = if (actionIndex == 1) {
            MouseButtonPacket.BUTTON_RIGHT
        } else {
            MouseButtonPacket.BUTTON_LEFT
        }

    override fun touchDownEvent(eventX: Int, eventY: Int, eventTime: Long, isNewFinger: Boolean): Boolean {
        xFactor = referenceWidth / targetView.width.toDouble()
        yFactor = referenceHeight / targetView.height.toDouble()

        lastTouchX = eventX
        originalTouchX = eventX
        lastTouchY = eventY
        originalTouchY = eventY

        if (isNewFinger) {
            maxPointerCountInGesture = pointerCount
            originalTouchTime = eventTime
            cancelled = false
            confirmedScroll = false
            confirmedMove = false
            confirmedDrag = false
            distanceMoved = 0.0

            if (actionIndex == 0) {
                startDragTimer()
            }
        }

        return true
    }

    override fun touchUpEvent(eventX: Int, eventY: Int, eventTime: Long) {
        if (cancelled) {
            return
        }

        cancelDragTimer()
        val buttonIndex = mouseButtonIndex

        if (confirmedDrag) {
            conn.sendMouseButtonUp(buttonIndex)
        } else if (isTap(eventTime)) {
            if (buttonIndex == MouseButtonPacket.BUTTON_RIGHT) {
                onRightClickListener?.onRightClick(lastTouchX.toFloat(), lastTouchY.toFloat())
            } else if (buttonIndex == MouseButtonPacket.BUTTON_LEFT) {
                onLeftClickListener?.onLeftClick(lastTouchX.toFloat(), lastTouchY.toFloat())
            }

            conn.sendMouseButtonDown(buttonIndex)

            val buttonUpRunnable = buttonUpRunnables[buttonIndex - 1]
            handler.removeCallbacks(buttonUpRunnable)
            handler.postDelayed(buttonUpRunnable, 100)
        }
    }

    private fun startDragTimer() {
        cancelDragTimer()
        handler.postDelayed(dragTimerRunnable, DRAG_TIME_THRESHOLD)
    }

    private fun cancelDragTimer() {
        handler.removeCallbacks(dragTimerRunnable)
    }

    private fun checkForConfirmedMove(eventX: Int, eventY: Int) {
        if (confirmedMove || confirmedDrag) {
            return
        }

        if (!isWithinTapBounds(eventX, eventY)) {
            confirmedMove = true
            cancelDragTimer()
            return
        }

        distanceMoved += sqrt((eventX - lastTouchX).toDouble().pow(2.0) + (eventY - lastTouchY).toDouble().pow(2.0))
        if (distanceMoved >= TAP_DISTANCE_THRESHOLD) {
            confirmedMove = true
            cancelDragTimer()
            return
        }
    }

    private fun checkForConfirmedScroll() {
        confirmedScroll = (actionIndex == 0 && pointerCount == 2 && confirmedMove)
    }

    override fun touchMoveEvent(eventX: Int, eventY: Int, eventTime: Long): Boolean {
        if (cancelled) {
            return true
        }

        if (eventX != lastTouchX || eventY != lastTouchY) {
            checkForConfirmedMove(eventX, eventY)
            checkForConfirmedScroll()

            if (actionIndex == 0) {
                var deltaX = eventX - lastTouchX
                var deltaY = eventY - lastTouchY

                deltaX = (abs(deltaX).toDouble() * xFactor).roundToInt()
                deltaY = (abs(deltaY).toDouble() * yFactor).roundToInt()

                if (eventX < lastTouchX) {
                    deltaX = -deltaX
                }
                if (eventY < lastTouchY) {
                    deltaY = -deltaY
                }

                if (pointerCount == 2) {
                    if (confirmedScroll) {
                        val scrollSens = ActiveProfile.scrollSensitivity
                        var scroll = (deltaY.toDouble() * SCROLL_SPEED_FACTOR * scrollSens).roundToInt()
                        if (ActiveProfile.isNaturalScroll) {
                            scroll = -scroll
                        }
                        conn.sendMouseHighResScroll(scroll.toShort())
                    }
                } else {
                    val sens = ActiveProfile.pointerSensitivity
                    val sDeltaX = (deltaX.toDouble() * sens).roundToInt()
                    val sDeltaY = (deltaY.toDouble() * sens).roundToInt()
                    if (prefConfig.absoluteMouseMode) {
                        conn.sendMouseMoveAsMousePosition(
                            sDeltaX.toShort(),
                            sDeltaY.toShort(),
                            targetView.width.toShort(),
                            targetView.height.toShort()
                        )
                    } else {
                        conn.sendMouseMove(sDeltaX.toShort(), sDeltaY.toShort())
                    }
                }

                if (deltaX != 0) {
                    lastTouchX = eventX
                }
                if (deltaY != 0) {
                    lastTouchY = eventY
                }
            } else {
                lastTouchX = eventX
                lastTouchY = eventY
            }
        }

        return true
    }

    override fun cancelTouch() {
        cancelled = true
        cancelDragTimer()

        if (confirmedDrag) {
            conn.sendMouseButtonUp(mouseButtonIndex)
        }
    }

    override val isCancelled: Boolean
        get() = cancelled

    override fun setPointerCount(pointerCount: Int) {
        this.pointerCount = pointerCount
        if (pointerCount > maxPointerCountInGesture) {
            maxPointerCountInGesture = pointerCount
        }
    }
}
