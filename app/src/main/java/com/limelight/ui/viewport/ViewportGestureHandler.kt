package com.limelight.ui.viewport

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector

/**
 * ViewportGestureHandler - Deteccion de gestos para el viewport en Kotlin.
 *
 * Responsabilidad UNICA: interpretar eventos tactiles y traducirlos a
 * operaciones sobre el DesktopViewportEngine.
 */
class ViewportGestureHandler(
    context: Context,
    private val engine: DesktopViewportEngine
) {
    interface GestureCallback {
        fun onScaleGestureStarted()
        fun onScaleGestureEnded()
        fun onDoubleTapToggle()
        fun onTripleTap()
    }

    companion object {
        private const val TRIPLE_TAP_TIMEOUT_MS: Long = 400L
    }

    private val scaleDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector

    private var multiTouchActive = false
    private var panActive = false
    var isMoveModeActive: Boolean = false

    private var lastCentroidX = 0f
    private var lastCentroidY = 0f
    private var lastPointerCount = 0

    private var tapCount = 0
    private var lastTapTimeMs = 0L

    var gestureCallback: GestureCallback? = null

    private val handler = Handler(Looper.getMainLooper())

    init {
        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                engine.cancelAnimation()
                multiTouchActive = true
                gestureCallback?.onScaleGestureStarted()
                return true
            }

            override fun onScale(d: ScaleGestureDetector): Boolean {
                engine.cancelAnimation()
                multiTouchActive = true

                val newScale = engine.scale * d.scaleFactor
                engine.applyFocalScale(newScale, d.focusX, d.focusY)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                val currentScale = engine.scale
                if (currentScale in 0.96f..DesktopViewportEngine.SNAP_THRESHOLD) {
                    engine.setViewportMode(DesktopViewportEngine.ViewportMode.FIT)
                }
                gestureCallback?.onScaleGestureEnded()
            }
        })
        scaleDetector.isQuickScaleEnabled = false

        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                handleTap()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                return false
            }
        })
        gestureDetector.setIsLongpressEnabled(false)
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val pointerCount = event.pointerCount

        if (action == MotionEvent.ACTION_DOWN) {
            engine.cancelAnimation()
            multiTouchActive = false
        }

        scaleDetector.onTouchEvent(event)

        if (pointerCount >= 2) {
            multiTouchActive = true
        }

        gestureDetector.onTouchEvent(event)
        processPan(event)

        val isMultiFinger = pointerCount >= 2
        val isScaling = scaleDetector.isInProgress
        val isFinishingMulti = multiTouchActive &&
                (action == MotionEvent.ACTION_MOVE ||
                 action == MotionEvent.ACTION_POINTER_UP ||
                 action == MotionEvent.ACTION_UP)

        val consume = isMultiFinger || isScaling || isFinishingMulti || isMoveModeActive

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            multiTouchActive = false
            panActive = false
        }

        return consume
    }

    private fun processPan(event: MotionEvent) {
        val action = event.actionMasked
        val pointerCount = event.pointerCount

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                lastCentroidX = event.getX(0)
                lastCentroidY = event.getY(0)
                lastPointerCount = 1
                panActive = isMoveModeActive
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                lastCentroidX = centroidX(event, -1)
                lastCentroidY = centroidY(event, -1)
                lastPointerCount = pointerCount
                panActive = true
                multiTouchActive = true
            }

            MotionEvent.ACTION_MOVE -> {
                val cx = centroidX(event, -1)
                val cy = centroidY(event, -1)

                if ((pointerCount >= 2 || isMoveModeActive) && panActive) {
                    if (!scaleDetector.isInProgress && pointerCount == lastPointerCount) {
                        val dx = cx - lastCentroidX
                        val dy = cy - lastCentroidY
                        engine.panBy(dx, dy)
                    }
                }

                lastCentroidX = cx
                lastCentroidY = cy
                lastPointerCount = pointerCount
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val liftedIdx = event.actionIndex
                lastCentroidX = centroidX(event, liftedIdx)
                lastCentroidY = centroidY(event, liftedIdx)
                lastPointerCount = pointerCount - 1
                if (lastPointerCount < 2 && !isMoveModeActive) {
                    panActive = false
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                panActive = false
                multiTouchActive = false
                lastPointerCount = 0
            }
        }
    }

    private fun centroidX(event: MotionEvent, excludeIndex: Int): Float {
        var sum = 0f
        var count = 0
        for (i in 0 until event.pointerCount) {
            if (i == excludeIndex) continue
            sum += event.getX(i)
            count++
        }
        return if (count > 0) sum / count else event.getX(0)
    }

    private fun centroidY(event: MotionEvent, excludeIndex: Int): Float {
        var sum = 0f
        var count = 0
        for (i in 0 until event.pointerCount) {
            if (i == excludeIndex) continue
            sum += event.getY(i)
            count++
        }
        return if (count > 0) sum / count else event.getY(0)
    }

    private fun handleTap() {
        val now = System.currentTimeMillis()

        if (now - lastTapTimeMs < TRIPLE_TAP_TIMEOUT_MS) {
            tapCount++
        } else {
            tapCount = 1
        }
        lastTapTimeMs = now

        if (tapCount >= 3) {
            tapCount = 0
            engine.setViewportMode(DesktopViewportEngine.ViewportMode.PIXEL_PERFECT)
            gestureCallback?.onTripleTap()
        } else if (tapCount == 2) {
            handler.postDelayed({
                if (tapCount == 2) {
                    tapCount = 0
                    engine.toggleFitZoom()
                    gestureCallback?.onDoubleTapToggle()
                }
            }, TRIPLE_TAP_TIMEOUT_MS)
        }
    }

    fun toggleMoveMode(): Boolean {
        isMoveModeActive = !isMoveModeActive
        return isMoveModeActive
    }
}