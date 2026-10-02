package com.limelight.binding.input.virtual_controller

import android.app.Activity
import android.content.Context
import com.limelight.nvstream.input.ControllerPacket
import com.limelight.preferences.PreferenceConfiguration
import org.json.JSONException
import org.json.JSONObject

object VirtualControllerConfigurationLoader {
    const val OSC_PREFERENCE = "OSC"

    private fun getPercent(percent: Int, total: Int): Int {
        return ((total.toFloat() / 100f) * percent.toFloat()).toInt()
    }

    // The default controls are specified using a grid of 128*72 cells at 16:9
    private fun screenScale(units: Int, height: Int): Int {
        return ((height.toFloat() / 72f) * units.toFloat()).toInt()
    }

    private fun createDigitalPad(
        controller: VirtualController,
        context: Context
    ): DigitalPad {
        val digitalPad = DigitalPad(controller, context)
        digitalPad.addDigitalPadListener(object : DigitalPad.DigitalPadListener {
            override fun onDirectionChange(direction: Int) {
                val inputContext = controller.controllerInputContext

                var map = inputContext.inputMap.toInt()

                map = if ((direction and DigitalPad.DIGITAL_PAD_DIRECTION_LEFT) != 0) {
                    map or ControllerPacket.LEFT_FLAG
                } else {
                    map and ControllerPacket.LEFT_FLAG.inv()
                }

                map = if ((direction and DigitalPad.DIGITAL_PAD_DIRECTION_RIGHT) != 0) {
                    map or ControllerPacket.RIGHT_FLAG
                } else {
                    map and ControllerPacket.RIGHT_FLAG.inv()
                }

                map = if ((direction and DigitalPad.DIGITAL_PAD_DIRECTION_UP) != 0) {
                    map or ControllerPacket.UP_FLAG
                } else {
                    map and ControllerPacket.UP_FLAG.inv()
                }

                map = if ((direction and DigitalPad.DIGITAL_PAD_DIRECTION_DOWN) != 0) {
                    map or ControllerPacket.DOWN_FLAG
                } else {
                    map and ControllerPacket.DOWN_FLAG.inv()
                }

                inputContext.inputMap = map.toShort()
                controller.sendControllerInputContext()
            }
        })

        return digitalPad
    }

    private fun createDigitalButton(
        elementId: Int,
        keyShort: Int,
        keyLong: Int,
        layer: Int,
        text: String,
        icon: Int,
        controller: VirtualController,
        context: Context
    ): DigitalButton {
        val button = DigitalButton(controller, elementId, layer, context)
        button.setText(text)
        button.setIcon(icon)

        button.addDigitalButtonListener(object : DigitalButton.DigitalButtonListener {
            override fun onClick() {
                val inputContext = controller.controllerInputContext
                inputContext.inputMap = (inputContext.inputMap.toInt() or keyShort).toShort()
                controller.sendControllerInputContext()
            }

            override fun onLongClick() {
                val inputContext = controller.controllerInputContext
                inputContext.inputMap = (inputContext.inputMap.toInt() or keyLong).toShort()
                controller.sendControllerInputContext()
            }

            override fun onRelease() {
                val inputContext = controller.controllerInputContext
                var map = inputContext.inputMap.toInt()
                map = map and keyShort.inv()
                map = map and keyLong.inv()
                inputContext.inputMap = map.toShort()
                controller.sendControllerInputContext()
            }
        })

        return button
    }

    private fun createLeftTrigger(
        layer: Int,
        text: String,
        icon: Int,
        controller: VirtualController,
        context: Context
    ): DigitalButton {
        val button = LeftTrigger(controller, layer, context)
        button.setText(text)
        button.setIcon(icon)
        return button
    }

    private fun createRightTrigger(
        layer: Int,
        text: String,
        icon: Int,
        controller: VirtualController,
        context: Context
    ): DigitalButton {
        val button = RightTrigger(controller, layer, context)
        button.setText(text)
        button.setIcon(icon)
        return button
    }

    private fun createLeftStick(
        controller: VirtualController,
        context: Context
    ): AnalogStick {
        return LeftAnalogStick(controller, context)
    }

    private fun createRightStick(
        controller: VirtualController,
        context: Context
    ): AnalogStick {
        return RightAnalogStick(controller, context)
    }

    private const val TRIGGER_L_BASE_X = 1
    private const val TRIGGER_R_BASE_X = 92
    private const val TRIGGER_DISTANCE = 23
    private const val TRIGGER_BASE_Y = 31
    private const val TRIGGER_WIDTH = 12
    private const val TRIGGER_HEIGHT = 9

    // Face buttons are defined based on the Y button (button number 9)
    private const val BUTTON_BASE_X = 106
    private const val BUTTON_BASE_Y = 1
    private const val BUTTON_SIZE = 10

    private const val DPAD_BASE_X = 4
    private const val DPAD_BASE_Y = 41
    private const val DPAD_SIZE = 30

    private const val ANALOG_L_BASE_X = 6
    private const val ANALOG_L_BASE_Y = 4
    private const val ANALOG_R_BASE_X = 98
    private const val ANALOG_R_BASE_Y = 42
    private const val ANALOG_SIZE = 26

    private const val L3_R3_BASE_Y = 60

    private const val START_X = 83
    private const val BACK_X = 34
    private const val START_BACK_Y = 64
    private const val START_BACK_WIDTH = 12
    private const val START_BACK_HEIGHT = 7

    // Make the Guide Menu be in the center of START and BACK menu
    private const val GUIDE_X = START_X - BACK_X
    private const val GUIDE_Y = START_BACK_Y

    @JvmStatic
    fun createDefaultLayout(controller: VirtualController, context: Context) {
        val screen = context.resources.displayMetrics
        val config = PreferenceConfiguration.readPreferences(context)

        // Displace controls on the right by this amount of pixels to account for different aspect ratios
        val rightDisplacement = screen.widthPixels - screen.heightPixels * 16 / 9
        val height = screen.heightPixels

        if (!config.onlyL3R3) {
            controller.addElement(
                createDigitalPad(controller, context),
                screenScale(DPAD_BASE_X, height),
                screenScale(DPAD_BASE_Y, height),
                screenScale(DPAD_SIZE, height),
                screenScale(DPAD_SIZE, height)
            )

            controller.addElement(
                createDigitalButton(
                    VirtualControllerElement.EID_A,
                    if (!config.flipFaceButtons) ControllerPacket.A_FLAG else ControllerPacket.B_FLAG, 0, 1,
                    if (!config.flipFaceButtons) "A" else "B", -1, controller, context
                ),
                screenScale(BUTTON_BASE_X, height) + rightDisplacement,
                screenScale(BUTTON_BASE_Y + 2 * BUTTON_SIZE, height),
                screenScale(BUTTON_SIZE, height),
                screenScale(BUTTON_SIZE, height)
            )

            controller.addElement(
                createDigitalButton(
                    VirtualControllerElement.EID_B,
                    if (config.flipFaceButtons) ControllerPacket.A_FLAG else ControllerPacket.B_FLAG, 0, 1,
                    if (config.flipFaceButtons) "A" else "B", -1, controller, context
                ),
                screenScale(BUTTON_BASE_X + BUTTON_SIZE, height) + rightDisplacement,
                screenScale(BUTTON_BASE_Y + BUTTON_SIZE, height),
                screenScale(BUTTON_SIZE, height),
                screenScale(BUTTON_SIZE, height)
            )

            controller.addElement(
                createDigitalButton(
                    VirtualControllerElement.EID_X,
                    if (!config.flipFaceButtons) ControllerPacket.X_FLAG else ControllerPacket.Y_FLAG, 0, 1,
                    if (!config.flipFaceButtons) "X" else "Y", -1, controller, context
                ),
                screenScale(BUTTON_BASE_X - BUTTON_SIZE, height) + rightDisplacement,
                screenScale(BUTTON_BASE_Y + BUTTON_SIZE, height),
                screenScale(BUTTON_SIZE, height),
                screenScale(BUTTON_SIZE, height)
            )

            controller.addElement(
                createDigitalButton(
                    VirtualControllerElement.EID_Y,
                    if (config.flipFaceButtons) ControllerPacket.X_FLAG else ControllerPacket.Y_FLAG, 0, 1,
                    if (config.flipFaceButtons) "X" else "Y", -1, controller, context
                ),
                screenScale(BUTTON_BASE_X, height) + rightDisplacement,
                screenScale(BUTTON_BASE_Y, height),
                screenScale(BUTTON_SIZE, height),
                screenScale(BUTTON_SIZE, height)
            )

            controller.addElement(
                createLeftTrigger(1, "LT", -1, controller, context),
                screenScale(TRIGGER_L_BASE_X, height),
                screenScale(TRIGGER_BASE_Y, height),
                screenScale(TRIGGER_WIDTH, height),
                screenScale(TRIGGER_HEIGHT, height)
            )

            controller.addElement(
                createRightTrigger(1, "RT", -1, controller, context),
                screenScale(TRIGGER_R_BASE_X + TRIGGER_DISTANCE, height) + rightDisplacement,
                screenScale(TRIGGER_BASE_Y, height),
                screenScale(TRIGGER_WIDTH, height),
                screenScale(TRIGGER_HEIGHT, height)
            )

            controller.addElement(
                createDigitalButton(
                    VirtualControllerElement.EID_LB,
                    ControllerPacket.LB_FLAG, 0, 1, "LB", -1, controller, context
                ),
                screenScale(TRIGGER_L_BASE_X + TRIGGER_DISTANCE, height),
                screenScale(TRIGGER_BASE_Y, height),
                screenScale(TRIGGER_WIDTH, height),
                screenScale(TRIGGER_HEIGHT, height)
            )

            controller.addElement(
                createDigitalButton(
                    VirtualControllerElement.EID_RB,
                    ControllerPacket.RB_FLAG, 0, 1, "RB", -1, controller, context
                ),
                screenScale(TRIGGER_R_BASE_X, height) + rightDisplacement,
                screenScale(TRIGGER_BASE_Y, height),
                screenScale(TRIGGER_WIDTH, height),
                screenScale(TRIGGER_HEIGHT, height)
            )

            controller.addElement(
                createLeftStick(controller, context),
                screenScale(ANALOG_L_BASE_X, height),
                screenScale(ANALOG_L_BASE_Y, height),
                screenScale(ANALOG_SIZE, height),
                screenScale(ANALOG_SIZE, height)
            )

            controller.addElement(
                createRightStick(controller, context),
                screenScale(ANALOG_R_BASE_X, height) + rightDisplacement,
                screenScale(ANALOG_R_BASE_Y, height),
                screenScale(ANALOG_SIZE, height),
                screenScale(ANALOG_SIZE, height)
            )

            controller.addElement(
                createDigitalButton(
                    VirtualControllerElement.EID_BACK,
                    ControllerPacket.BACK_FLAG, 0, 2, "BACK", -1, controller, context
                ),
                screenScale(BACK_X, height),
                screenScale(START_BACK_Y, height),
                screenScale(START_BACK_WIDTH, height),
                screenScale(START_BACK_HEIGHT, height)
            )

            controller.addElement(
                createDigitalButton(
                    VirtualControllerElement.EID_START,
                    ControllerPacket.PLAY_FLAG, 0, 3, "START", -1, controller, context
                ),
                screenScale(START_X, height) + rightDisplacement,
                screenScale(START_BACK_Y, height),
                screenScale(START_BACK_WIDTH, height),
                screenScale(START_BACK_HEIGHT, height)
            )
        } else {
            controller.addElement(
                createDigitalButton(
                    VirtualControllerElement.EID_LSB,
                    ControllerPacket.LS_CLK_FLAG, 0, 1, "L3", -1, controller, context
                ),
                screenScale(TRIGGER_L_BASE_X, height),
                screenScale(L3_R3_BASE_Y, height),
                screenScale(TRIGGER_WIDTH, height),
                screenScale(TRIGGER_HEIGHT, height)
            )

            controller.addElement(
                createDigitalButton(
                    VirtualControllerElement.EID_RSB,
                    ControllerPacket.RS_CLK_FLAG, 0, 1, "R3", -1, controller, context
                ),
                screenScale(TRIGGER_R_BASE_X + TRIGGER_DISTANCE, height) + rightDisplacement,
                screenScale(L3_R3_BASE_Y, height),
                screenScale(TRIGGER_WIDTH, height),
                screenScale(TRIGGER_HEIGHT, height)
            )
        }

        if (config.showGuideButton) {
            controller.addElement(
                createDigitalButton(
                    VirtualControllerElement.EID_GDB,
                    ControllerPacket.SPECIAL_BUTTON_FLAG, 0, 1, "GUIDE", -1, controller, context
                ),
                screenScale(GUIDE_X, height) + rightDisplacement,
                screenScale(GUIDE_Y, height),
                screenScale(START_BACK_WIDTH, height),
                screenScale(START_BACK_HEIGHT, height)
            )
        }

        controller.setOpacity(config.oscOpacity)
    }

    @JvmStatic
    fun saveProfile(controller: VirtualController, context: Context) {
        val prefEditor = context.getSharedPreferences(OSC_PREFERENCE, Activity.MODE_PRIVATE).edit()

        for (element in controller.elements) {
            val prefKey = "${element.elementId}"
            try {
                prefEditor.putString(prefKey, element.getConfiguration().toString())
            } catch (e: JSONException) {
                e.printStackTrace()
            }
        }

        prefEditor.apply()
    }

    @JvmStatic
    fun loadFromPreferences(controller: VirtualController, context: Context) {
        val pref = context.getSharedPreferences(OSC_PREFERENCE, Activity.MODE_PRIVATE)

        for (element in controller.elements) {
            val prefKey = "${element.elementId}"
            val jsonConfig = pref.getString(prefKey, null)
            if (jsonConfig != null) {
                try {
                    element.loadConfiguration(JSONObject(jsonConfig))
                } catch (e: JSONException) {
                    e.printStackTrace()
                    pref.edit().remove(prefKey).apply()
                }
            }
        }
    }
}
