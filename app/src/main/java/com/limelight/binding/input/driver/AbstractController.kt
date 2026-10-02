package com.limelight.binding.input.driver

abstract class AbstractController(
    val controllerId: Int,
    private val listener: UsbDriverListener?,
    val vendorId: Int,
    val productId: Int
) {
    protected var buttonFlags: Int = 0
    var supportedButtonFlags: Int = 0
        protected set
    protected var leftTrigger: Float = 0f
    protected var rightTrigger: Float = 0f
    protected var rightStickX: Float = 0f
    protected var rightStickY: Float = 0f
    protected var leftStickX: Float = 0f
    protected var leftStickY: Float = 0f
    var capabilities: Short = 0
        protected set
    var type: Byte = 0
        protected set

    protected fun setButtonFlag(buttonFlag: Int, data: Int) {
        if (data != 0) {
            buttonFlags = buttonFlags or buttonFlag
        } else {
            buttonFlags = buttonFlags and buttonFlag.inv()
        }
    }

    protected fun reportInput() {
        listener?.reportControllerState(
            controllerId, buttonFlags, leftStickX, leftStickY,
            rightStickX, rightStickY, leftTrigger, rightTrigger
        )
    }

    abstract fun start(): Boolean
    abstract fun stop()
    abstract fun rumble(lowFreqMotor: Short, highFreqMotor: Short)
    abstract fun rumbleTriggers(leftTrigger: Short, rightTrigger: Short)

    protected fun notifyDeviceRemoved() {
        listener?.deviceRemoved(this)
    }

    protected fun notifyDeviceAdded() {
        listener?.deviceAdded(this)
    }
}
