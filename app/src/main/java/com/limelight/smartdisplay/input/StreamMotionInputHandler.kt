package com.limelight.smartdisplay.input

import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import com.limelight.binding.input.ControllerHandler
import com.limelight.binding.input.capture.InputCaptureProvider
import com.limelight.binding.input.touch.AbsoluteTouchContext
import com.limelight.binding.input.touch.RelativeTouchContext
import com.limelight.binding.input.touch.TouchContext
import com.limelight.binding.input.virtual_controller.VirtualController
import com.limelight.nvstream.NvConnection
import com.limelight.nvstream.input.MouseButtonPacket
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.qos.DesktopContentClassifier
import com.limelight.smartdisplay.motion.WindowMotionCoordinator
import com.limelight.ui.AdaptiveCursorView
import com.limelight.ui.StreamViewTransformController
import com.limelight.ui.WindowControlsController
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Clean Architecture handler for pointer, mouse, stylus, and touch interactions.
 * Consolidates coordinate transformations, gesture detection, and Moonlight input packet translation.
 */
class StreamMotionInputHandler(
    private val prefConfig: PreferenceConfiguration,
    private val inputCaptureProvider: InputCaptureProvider,
    private val onToggleKeyboard: () -> Unit
) {

    companion object {
        const val REFERENCE_HORIZ_RES = 1280
        const val REFERENCE_VERT_RES = 720
        const val THREE_FINGER_TAP_THRESHOLD = 300L
        const val STYLUS_UP_DEAD_ZONE_DELAY = 250L
        const val STYLUS_UP_DEAD_ZONE_RADIUS = 50.0
        const val STYLUS_DOWN_DEAD_ZONE_DELAY = 100L
        const val STYLUS_DOWN_DEAD_ZONE_RADIUS = 30.0
    }

    private val touchContextMap = arrayOfNulls<TouchContext>(2)

    var connection: NvConnection? = null
    var controllerHandler: ControllerHandler? = null
    var streamTransformCtrl: StreamViewTransformController? = null
    var adaptiveCursor: AdaptiveCursorView? = null
    var windowControlsCtrl: WindowControlsController? = null
    var windowMotionCoordinator: WindowMotionCoordinator? = null
    var desktopContentClassifier: DesktopContentClassifier? = null
    var virtualController: VirtualController? = null
    var streamView: View? = null

    var isInputGrabbed: Boolean = true
    var lastCursorPosMs: Long = 0

    private var lastButtonState: Int = 0
    private var threeFingerDownTime: Long = 0
    private var lastAbsTouchDownTime: Long = 0
    private var lastAbsTouchDownX: Float = 0f
    private var lastAbsTouchDownY: Float = 0f
    private var lastAbsTouchUpTime: Long = 0
    private var lastAbsTouchUpX: Float = 0f
    private var lastAbsTouchUpY: Float = 0f

    /**
     * Initializes or reconfigures touch contexts according to trackpad mode preference.
     * Eliminates code duplication across session starts and mode toggles.
     */
    fun setupTouchContexts(conn: NvConnection?, streamView: View?) {
        this.connection = conn
        this.streamView = streamView
        if (conn == null || streamView == null) return

        for (i in touchContextMap.indices) {
            touchContextMap[i] = if (!prefConfig.touchscreenTrackpad) {
                AbsoluteTouchContext(conn, i, streamView)
            } else {
                RelativeTouchContext(
                    conn, i,
                    REFERENCE_HORIZ_RES, REFERENCE_VERT_RES,
                    streamView, prefConfig
                )
            }

            touchContextMap[i]?.setOnRightClickListener { x, y ->
                streamTransformCtrl?.zoomToRightClick(x, y)
                adaptiveCursor?.fireRightClick(x.toFloat(), y.toFloat())
            }

            touchContextMap[i]?.setOnLeftClickListener { x, y ->
                adaptiveCursor?.fireLeftClick(x.toFloat(), y.toFloat())
            }
        }
    }

    /**
     * Cancels all active touches across tracked touch contexts.
     */
    fun cancelAllTouches() {
        for (context in touchContextMap) {
            context?.cancelTouch()
            context?.setPointerCount(0)
        }
    }

    private fun getTouchContext(actionIndex: Int): TouchContext? {
        return if (actionIndex in touchContextMap.indices) touchContextMap[actionIndex] else null
    }

    // ── Adaptive Coordinates Translation ─────────────────────────────────

    fun getAdaptiveX(view: View?, event: MotionEvent, actionIndex: Int): Int {
        return getAdaptiveMappedValue(view, event.getX(actionIndex), true)
    }

    fun getAdaptiveY(view: View?, event: MotionEvent, actionIndex: Int): Int {
        return getAdaptiveMappedValue(view, event.getY(actionIndex), false)
    }

    fun getAdaptiveHistoricalX(view: View?, event: MotionEvent, actionIndex: Int, pos: Int): Int {
        return getAdaptiveMappedValue(view, event.getHistoricalX(actionIndex, pos), true)
    }

    fun getAdaptiveHistoricalY(view: View?, event: MotionEvent, actionIndex: Int, pos: Int): Int {
        return getAdaptiveMappedValue(view, event.getHistoricalY(actionIndex, pos), false)
    }

    private fun getAdaptiveMappedValue(view: View?, rawVal: Float, isX: Boolean): Int {
        if (view != null && view == streamView) {
            return rawVal.toInt()
        }
        var scale = 1.0f
        var translate = 0f
        var pivot = 0f
        val transformCtrl = streamTransformCtrl
        if (transformCtrl != null) {
            scale = transformCtrl.scale
            translate = if (isX) transformCtrl.translateX else transformCtrl.translateY
            val streamWrapper = view?.rootView?.findViewById<View>(com.limelight.R.id.streamTransformWrapper)
            if (streamWrapper != null) {
                pivot = if (isX) streamWrapper.width / 2f else streamWrapper.height / 2f
            }
        }
        var unscaledVal = pivot + (rawVal - translate - pivot) / scale
        val sView = streamView
        if (sView != null && !prefConfig.touchscreenTrackpad) {
            unscaledVal -= if (isX) sView.x else sView.y
        }
        return unscaledVal.toInt()
    }

    // ── Motion & Mouse Event Processing ───────────────────────────────────

    fun handleMotionEvent(view: View?, event: MotionEvent): Boolean {
        if (!isInputGrabbed) {
            return false
        }

        val eventSource = event.source
        val deviceSources = event.device?.sources ?: 0
        val conn = connection

        if ((eventSource and InputDevice.SOURCE_CLASS_JOYSTICK) != 0) {
            if (controllerHandler?.handleMotionEvent(event) == true) {
                return true
            }
        } else if ((deviceSources and InputDevice.SOURCE_CLASS_JOYSTICK) != 0 &&
            controllerHandler?.tryHandleTouchpadEvent(event) == true) {
            return true
        } else if ((eventSource and InputDevice.SOURCE_CLASS_POINTER) != 0 ||
            (eventSource and InputDevice.SOURCE_CLASS_POSITION) != 0 ||
            eventSource == InputDevice.SOURCE_MOUSE_RELATIVE) {

            if (eventSource == InputDevice.SOURCE_MOUSE ||
                (eventSource and InputDevice.SOURCE_CLASS_POSITION) != 0 ||
                eventSource == InputDevice.SOURCE_MOUSE_RELATIVE ||
                (event.pointerCount >= 1 && (
                    event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE ||
                    event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS ||
                    event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
                )) ||
                eventSource == 12290) { // DeX desktop mouse

                var buttonState = event.buttonState
                var changedButtons = buttonState xor lastButtonState

                // DeX touchpad fixup
                if (eventSource == 12290) {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> buttonState = buttonState or MotionEvent.BUTTON_PRIMARY
                        MotionEvent.ACTION_UP -> buttonState = buttonState and MotionEvent.BUTTON_PRIMARY.inv()
                        else -> buttonState = buttonState or (lastButtonState and MotionEvent.BUTTON_PRIMARY)
                    }
                    changedButtons = buttonState xor lastButtonState
                }

                if (!inputCaptureProvider.isCapturingActive) {
                    return true
                }

                val currentStreamView = streamView
                if (inputCaptureProvider.eventHasRelativeMouseAxes(event)) {
                    val deltaX = inputCaptureProvider.getRelativeAxisX(event).toInt().toShort()
                    val deltaY = inputCaptureProvider.getRelativeAxisY(event).toInt().toShort()

                    if (deltaX.toInt() != 0 || deltaY.toInt() != 0) {
                        if (prefConfig.absoluteMouseMode && currentStreamView != null) {
                            conn?.sendMouseMoveAsMousePosition(
                                deltaX, deltaY,
                                currentStreamView.width.toShort(), currentStreamView.height.toShort()
                            )
                        } else {
                            conn?.sendMouseMove(deltaX, deltaY)
                        }
                    }
                } else if ((eventSource and InputDevice.SOURCE_CLASS_POSITION) != 0) {
                    val device = event.device
                    if (device != null) {
                        val xRange = device.getMotionRange(MotionEvent.AXIS_X, eventSource)
                        val yRange = device.getMotionRange(MotionEvent.AXIS_Y, eventSource)
                        if (xRange != null && yRange != null && xRange.min == 0f && yRange.min == 0f) {
                            val xMax = xRange.max.toInt()
                            val yMax = yRange.max.toInt()
                            if (xMax <= Short.MAX_VALUE && yMax <= Short.MAX_VALUE) {
                                conn?.sendMousePosition(
                                    event.x.toInt().toShort(), event.y.toInt().toShort(),
                                    xMax.toShort(), yMax.toShort()
                                )
                            }
                        }
                    }
                } else if (view != null && currentStreamView != null &&
                    TouchInputTranslator.trySendPenEvent(view, event, conn, currentStreamView)) {
                    return true
                } else if (view != null) {
                    updateMousePosition(view, event)
                }

                if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
                    val vScroll = (event.getAxisValue(MotionEvent.AXIS_VSCROLL) * 120).toInt().toShort()
                    val hScroll = (event.getAxisValue(MotionEvent.AXIS_HSCROLL) * 120).toInt().toShort()
                    conn?.sendMouseHighResScroll(vScroll)
                    conn?.sendMouseHighResHScroll(hScroll)
                }

                if ((changedButtons and MotionEvent.BUTTON_PRIMARY) != 0) {
                    if ((buttonState and MotionEvent.BUTTON_PRIMARY) != 0) {
                        conn?.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT)
                    } else {
                        conn?.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT)
                    }
                }

                if ((changedButtons and (MotionEvent.BUTTON_SECONDARY or MotionEvent.BUTTON_STYLUS_PRIMARY)) != 0) {
                    if ((buttonState and (MotionEvent.BUTTON_SECONDARY or MotionEvent.BUTTON_STYLUS_PRIMARY)) != 0) {
                        conn?.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT)
                    } else {
                        conn?.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT)
                    }
                }

                if ((changedButtons and (MotionEvent.BUTTON_TERTIARY or MotionEvent.BUTTON_STYLUS_SECONDARY)) != 0) {
                    if ((buttonState and (MotionEvent.BUTTON_TERTIARY or MotionEvent.BUTTON_STYLUS_SECONDARY)) != 0) {
                        conn?.sendMouseButtonDown(MouseButtonPacket.BUTTON_MIDDLE)
                    } else {
                        conn?.sendMouseButtonUp(MouseButtonPacket.BUTTON_MIDDLE)
                    }
                }

                if (prefConfig.mouseNavButtons) {
                    if ((changedButtons and MotionEvent.BUTTON_BACK) != 0) {
                        if ((buttonState and MotionEvent.BUTTON_BACK) != 0) {
                            conn?.sendMouseButtonDown(MouseButtonPacket.BUTTON_X1)
                        } else {
                            conn?.sendMouseButtonUp(MouseButtonPacket.BUTTON_X1)
                        }
                    }
                    if ((changedButtons and MotionEvent.BUTTON_FORWARD) != 0) {
                        if ((buttonState and MotionEvent.BUTTON_FORWARD) != 0) {
                            conn?.sendMouseButtonDown(MouseButtonPacket.BUTTON_X2)
                        } else {
                            conn?.sendMouseButtonUp(MouseButtonPacket.BUTTON_X2)
                        }
                    }
                }

                // Handle stylus presses
                if (event.pointerCount == 1 && event.actionIndex == 0) {
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
                            lastAbsTouchDownTime = event.eventTime
                            lastAbsTouchDownX = event.getX(0)
                            lastAbsTouchDownY = event.getY(0)
                            conn?.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT)
                        } else if (event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER) {
                            lastAbsTouchDownTime = event.eventTime
                            lastAbsTouchDownX = event.getX(0)
                            lastAbsTouchDownY = event.getY(0)
                            conn?.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT)
                        }
                    } else if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
                            lastAbsTouchUpTime = event.eventTime
                            lastAbsTouchUpX = event.getX(0)
                            lastAbsTouchUpY = event.getY(0)
                            conn?.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT)
                        } else if (event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER) {
                            lastAbsTouchUpTime = event.eventTime
                            lastAbsTouchUpX = event.getX(0)
                            lastAbsTouchUpY = event.getY(0)
                            conn?.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT)
                        }
                    }
                }

                lastButtonState = buttonState
            } else {
                // Fingers / Touchscreen
                val vCtrl = virtualController
                if (vCtrl != null && (
                    vCtrl.controllerMode == VirtualController.ControllerMode.MoveButtons ||
                    vCtrl.controllerMode == VirtualController.ControllerMode.ResizeButtons
                )) {
                    return true
                }

                val actionIndex = event.actionIndex
                val eventX = getAdaptiveX(view, event, actionIndex)
                val eventY = getAdaptiveY(view, event, actionIndex)

                // 3-finger gesture
                if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && event.pointerCount == 3) {
                    threeFingerDownTime = event.eventTime
                    cancelAllTouches()
                    return true
                }

                val context = getTouchContext(actionIndex) ?: return false

                when (event.actionMasked) {
                    MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_DOWN -> {
                        for (tc in touchContextMap) {
                            tc?.setPointerCount(event.pointerCount)
                        }
                        context.touchDownEvent(eventX, eventY, event.eventTime, true)
                    }

                    MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                        if (event.pointerCount == 1 &&
                            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || (event.flags and MotionEvent.FLAG_CANCELED) == 0)) {
                            if (event.eventTime - threeFingerDownTime < THREE_FINGER_TAP_THRESHOLD) {
                                onToggleKeyboard()
                                return true
                            }
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && (event.flags and MotionEvent.FLAG_CANCELED) != 0) {
                            context.cancelTouch()
                        } else {
                            context.touchUpEvent(eventX, eventY, event.eventTime)
                        }

                        for (tc in touchContextMap) {
                            tc?.setPointerCount(event.pointerCount - 1)
                        }
                        if (actionIndex == 0 && event.pointerCount > 1 && !context.isCancelled) {
                            context.touchDownEvent(
                                getAdaptiveX(view, event, 1),
                                getAdaptiveY(view, event, 1),
                                event.eventTime, false
                            )
                        }
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val historySize = event.historySize
                        for (i in 0 until historySize) {
                            for (tc in touchContextMap) {
                                if (tc != null && tc.actionIndex < event.pointerCount) {
                                    tc.touchMoveEvent(
                                        getAdaptiveHistoricalX(view, event, tc.actionIndex, i),
                                        getAdaptiveHistoricalY(view, event, tc.actionIndex, i),
                                        event.getHistoricalEventTime(i)
                                    )
                                }
                            }
                        }
                        for (tc in touchContextMap) {
                            if (tc != null && tc.actionIndex < event.pointerCount) {
                                tc.touchMoveEvent(
                                    getAdaptiveX(view, event, tc.actionIndex),
                                    getAdaptiveY(view, event, tc.actionIndex),
                                    event.eventTime
                                )
                            }
                        }
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        cancelAllTouches()
                    }

                    else -> return false
                }
            }
            return true
        }

        return false
    }

    fun updateMousePosition(touchedView: View, event: MotionEvent) {
        val sView = streamView ?: return
        val conn = connection ?: return

        var eventX = if (touchedView == sView) {
            event.getX(0)
        } else {
            event.getX(0) - sView.x
        }
        var eventY = if (touchedView == sView) {
            event.getY(0)
        } else {
            event.getY(0) - sView.y
        }

        if (event.pointerCount == 1 && event.actionIndex == 0 && (
            event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER ||
            event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
        )) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_HOVER_ENTER,
                MotionEvent.ACTION_HOVER_EXIT,
                MotionEvent.ACTION_HOVER_MOVE -> {
                    if (event.eventTime - lastAbsTouchUpTime <= STYLUS_UP_DEAD_ZONE_DELAY &&
                        sqrt((eventX - lastAbsTouchUpX).toDouble().pow(2.0) + (eventY - lastAbsTouchUpY).toDouble().pow(2.0)) <= STYLUS_UP_DEAD_ZONE_RADIUS) {
                        return
                    }
                }

                MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_UP -> {
                    if (event.eventTime - lastAbsTouchDownTime <= STYLUS_DOWN_DEAD_ZONE_DELAY &&
                        sqrt((eventX - lastAbsTouchDownX).toDouble().pow(2.0) + (eventY - lastAbsTouchDownY).toDouble().pow(2.0)) <= STYLUS_DOWN_DEAD_ZONE_RADIUS) {
                        return
                    }
                }
            }
        }

        val clampedX = eventX.coerceIn(0f, sView.width.toFloat()).toInt().toShort()
        val clampedY = eventY.coerceIn(0f, sView.height.toFloat()).toInt().toShort()

        conn.sendMousePosition(clampedX, clampedY, sView.width.toShort(), sView.height.toShort())
    }

    fun onTouch(view: View, event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            view.requestUnbufferedDispatch(event)
        }

        val cursor = adaptiveCursor
        if (cursor != null) {
            val mirrorActive = (SystemClock.elapsedRealtime() - lastCursorPosMs) < 1500
            if (!mirrorActive) {
                val action = event.actionMasked
                if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_POINTER_DOWN) {
                    cursor.moveTo(event.getX(0), event.getY(0))
                }
            }
        }

        if (windowControlsCtrl?.onTouchEvent(event) == true) {
            return true
        }

        if (windowMotionCoordinator?.onTouchEvent(event) == true) {
            return true
        }

        val classifier = desktopContentClassifier
        if (classifier != null) {
            val interaction = if (event.pointerCount > 1) {
                DesktopContentClassifier.INTERACTION_SCROLL
            } else {
                DesktopContentClassifier.INTERACTION_MOUSE
            }
            classifier.onInteraction(interaction)
        }

        if (streamTransformCtrl?.onTouchEvent(event) == true) {
            cancelAllTouches()
            return true
        }

        return handleMotionEvent(view, event)
    }

    fun onGenericMotion(view: View?, event: MotionEvent): Boolean {
        return handleMotionEvent(view, event)
    }



}