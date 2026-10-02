package com.limelight.binding.input.driver

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import com.limelight.LimeLog
import com.limelight.nvstream.input.ControllerPacket
import java.nio.ByteBuffer

class Xbox360Controller(
    device: UsbDevice,
    connection: UsbDeviceConnection,
    deviceId: Int,
    listener: UsbDriverListener
) : AbstractXboxController(device, connection, deviceId, listener) {

    companion object {
        private const val XB360_IFACE_SUBCLASS = 93
        private const val XB360_IFACE_PROTOCOL = 1 // Wired only

        private val SUPPORTED_VENDORS = intArrayOf(
            0x0079, // GPD Win 2
            0x044f, // Thrustmaster
            0x045e, // Microsoft
            0x046d, // Logitech
            0x056e, // Elecom
            0x06a3, // Saitek
            0x0738, // Mad Catz
            0x07ff, // Mad Catz
            0x0e6f, // Unknown
            0x0f0d, // Hori
            0x1038, // SteelSeries
            0x11c9, // Nacon
            0x1209, // Ardwiino
            0x12ab, // Unknown
            0x1430, // RedOctane
            0x146b, // BigBen
            0x1532, // Razer Sabertooth
            0x15e4, // Numark
            0x162e, // Joytech
            0x1689, // Razer Onza
            0x1949, // Lab126 (Amazon Luna)
            0x1bad, // Harmonix
            0x20d6, // PowerA
            0x24c6, // PowerA
            0x2f24, // GameSir
            0x2dc8 // 8BitDo
        )

        @JvmStatic
        fun canClaimDevice(device: UsbDevice): Boolean {
            for (supportedVid in SUPPORTED_VENDORS) {
                if (device.vendorId == supportedVid &&
                    device.interfaceCount >= 1 &&
                    device.getInterface(0).interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC &&
                    device.getInterface(0).interfaceSubclass == XB360_IFACE_SUBCLASS &&
                    device.getInterface(0).interfaceProtocol == XB360_IFACE_PROTOCOL
                ) {
                    return true
                }
            }
            return false
        }
    }

    private fun unsignByte(b: Byte): Int {
        return if (b < 0) b + 256 else b.toInt()
    }

    override fun handleRead(buffer: ByteBuffer): Boolean {
        if (buffer.remaining() < 14) {
            LimeLog.severe("Read too small: " + buffer.remaining())
            return false
        }

        buffer.position(buffer.position() + 2)

        var b = buffer.get()
        setButtonFlag(ControllerPacket.LEFT_FLAG, (b.toInt() and 0x04))
        setButtonFlag(ControllerPacket.RIGHT_FLAG, (b.toInt() and 0x08))
        setButtonFlag(ControllerPacket.UP_FLAG, (b.toInt() and 0x01))
        setButtonFlag(ControllerPacket.DOWN_FLAG, (b.toInt() and 0x02))

        setButtonFlag(ControllerPacket.PLAY_FLAG, (b.toInt() and 0x10))
        setButtonFlag(ControllerPacket.BACK_FLAG, (b.toInt() and 0x20))

        setButtonFlag(ControllerPacket.LS_CLK_FLAG, (b.toInt() and 0x40))
        setButtonFlag(ControllerPacket.RS_CLK_FLAG, (b.toInt() and 0x80))

        b = buffer.get()
        setButtonFlag(ControllerPacket.A_FLAG, (b.toInt() and 0x10))
        setButtonFlag(ControllerPacket.B_FLAG, (b.toInt() and 0x20))
        setButtonFlag(ControllerPacket.X_FLAG, (b.toInt() and 0x40))
        setButtonFlag(ControllerPacket.Y_FLAG, (b.toInt() and 0x80))

        setButtonFlag(ControllerPacket.LB_FLAG, (b.toInt() and 0x01))
        setButtonFlag(ControllerPacket.RB_FLAG, (b.toInt() and 0x02))

        setButtonFlag(ControllerPacket.SPECIAL_BUTTON_FLAG, (b.toInt() and 0x04))

        leftTrigger = unsignByte(buffer.get()) / 255.0f
        rightTrigger = unsignByte(buffer.get()) / 255.0f

        leftStickX = buffer.short / 32767.0f
        leftStickY = (buffer.short.toInt().inv()).toShort() / 32767.0f

        rightStickX = buffer.short / 32767.0f
        rightStickY = (buffer.short.toInt().inv()).toShort() / 32767.0f

        return true
    }

    private fun sendLedCommand(command: Byte): Boolean {
        val commandBuffer = byteArrayOf(0x01, 0x03, command)
        val res = connection.bulkTransfer(outEndpt, commandBuffer, commandBuffer.size, 3000)
        if (res != commandBuffer.size) {
            LimeLog.warning("LED set transfer failed: $res")
            return false
        }
        return true
    }

    override fun doInit(): Boolean {
        sendLedCommand((2 + (controllerId % 4)).toByte())
        return true
    }

    override fun rumble(lowFreqMotor: Short, highFreqMotor: Short) {
        val data = byteArrayOf(
            0x00, 0x08, 0x00,
            (lowFreqMotor.toInt() shr 8).toByte(), (highFreqMotor.toInt() shr 8).toByte(),
            0x00, 0x00, 0x00
        )
        val res = connection.bulkTransfer(outEndpt, data, data.size, 100)
        if (res != data.size) {
            LimeLog.warning("Rumble transfer failed: $res")
        }
    }

    override fun rumbleTriggers(leftTrigger: Short, rightTrigger: Short) {
        // Trigger motors not present on Xbox 360 controllers
    }
}
