package com.limelight.binding.input.virtual_controller

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import com.limelight.LimeLog
import com.limelight.R
import com.limelight.binding.input.ControllerHandler
import java.util.ArrayList

class VirtualController(
    private val controllerHandler: ControllerHandler?,
    private val frameLayout: FrameLayout,
    private val context: Context
) {
    class ControllerInputContext {
        @JvmField var inputMap: Short = 0x0000
        @JvmField var leftTrigger: Byte = 0x00
        @JvmField var rightTrigger: Byte = 0x00
        @JvmField var rightStickX: Short = 0x0000
        @JvmField var rightStickY: Short = 0x0000
        @JvmField var leftStickX: Short = 0x0000
        @JvmField var leftStickY: Short = 0x0000
    }

    enum class ControllerMode {
        Active,
        MoveButtons,
        ResizeButtons
    }

    val handler = Handler(Looper.getMainLooper())

    private val delayedRetransmitRunnable = Runnable {
        sendControllerInputContextInternal()
    }

    var controllerMode = ControllerMode.Active
    val controllerInputContext = ControllerInputContext()

    private val buttonConfigure: Button = Button(context).apply {
        alpha = 0.25f
        isFocusable = false
        setBackgroundResource(R.drawable.ic_settings)
        setOnClickListener {
            val message: String
            if (controllerMode == ControllerMode.Active) {
                controllerMode = ControllerMode.MoveButtons
                message = "Entering configuration mode (Move buttons)"
            } else if (controllerMode == ControllerMode.MoveButtons) {
                controllerMode = ControllerMode.ResizeButtons
                message = "Entering configuration mode (Resize buttons)"
            } else {
                controllerMode = ControllerMode.Active
                VirtualControllerConfigurationLoader.saveProfile(this@VirtualController, context)
                message = "Exiting configuration mode"
            }

            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            invalidate()

            for (element in elements) {
                element.invalidate()
            }
        }
    }

    val elements: MutableList<VirtualControllerElement> = ArrayList()

    fun hide() {
        for (element in elements) {
            element.visibility = View.INVISIBLE
        }
        buttonConfigure.visibility = View.INVISIBLE
    }

    fun show() {
        for (element in elements) {
            element.visibility = View.VISIBLE
        }
        buttonConfigure.visibility = View.VISIBLE
    }

    fun removeElements() {
        for (element in elements) {
            frameLayout.removeView(element)
        }
        elements.clear()
        frameLayout.removeView(buttonConfigure)
    }

    fun setOpacity(opacity: Int) {
        for (element in elements) {
            element.setOpacity(opacity)
        }
    }

    fun addElement(element: VirtualControllerElement, x: Int, y: Int, width: Int, height: Int) {
        elements.add(element)
        val layoutParams = FrameLayout.LayoutParams(width, height)
        layoutParams.setMargins(x, y, 0, 0)
        frameLayout.addView(element, layoutParams)
    }

    fun refreshLayout() {
        removeElements()

        val screen = context.resources.displayMetrics
        val buttonSize = (screen.heightPixels * 0.06f).toInt()
        val params = FrameLayout.LayoutParams(buttonSize, buttonSize).apply {
            leftMargin = 15
            topMargin = 15
        }
        frameLayout.addView(buttonConfigure, params)

        // Start with the default layout
        VirtualControllerConfigurationLoader.createDefaultLayout(this, context)

        // Apply user preferences onto the default layout
        VirtualControllerConfigurationLoader.loadFromPreferences(this, context)
    }

    private fun sendControllerInputContextInternal() {
        _DBG("INPUT_MAP + ${controllerInputContext.inputMap}")
        _DBG("LEFT_TRIGGER ${controllerInputContext.leftTrigger}")
        _DBG("RIGHT_TRIGGER ${controllerInputContext.rightTrigger}")
        _DBG("LEFT STICK X: ${controllerInputContext.leftStickX} Y: ${controllerInputContext.leftStickY}")
        _DBG("RIGHT STICK X: ${controllerInputContext.rightStickX} Y: ${controllerInputContext.rightStickY}")

        controllerHandler?.reportOscState(
            controllerInputContext.inputMap.toInt(),
            controllerInputContext.leftStickX,
            controllerInputContext.leftStickY,
            controllerInputContext.rightStickX,
            controllerInputContext.rightStickY,
            controllerInputContext.leftTrigger,
            controllerInputContext.rightTrigger
        )
    }

    fun sendControllerInputContext() {
        handler.removeCallbacks(delayedRetransmitRunnable)
        sendControllerInputContextInternal()

        handler.postDelayed(delayedRetransmitRunnable, 25)
        handler.postDelayed(delayedRetransmitRunnable, 50)
        handler.postDelayed(delayedRetransmitRunnable, 75)
    }

    companion object {
        private const val _PRINT_DEBUG_INFORMATION = false

        private fun _DBG(text: String) {
            if (_PRINT_DEBUG_INFORMATION) {
                LimeLog.info("VirtualController: $text")
            }
        }
    }
}
