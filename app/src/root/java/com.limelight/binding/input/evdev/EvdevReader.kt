package com.limelight.binding.input.evdev

import com.limelight.LimeLog
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object EvdevReader {
    @Throws(IOException::class)
    private fun readAll(input: InputStream, bb: ByteBuffer) {
        val buf = bb.array()
        var offset = 0

        while (offset < buf.size) {
            val ret = input.read(buf, offset, buf.size - offset)
            if (ret <= 0) {
                throw IOException("Read failed: ${ret}")
            }
            offset += ret
        }
    }

    @Throws(IOException::class)
    @JvmStatic
    fun read(input: InputStream): EvdevEvent? {
        var bb = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder())
        readAll(input, bb)
        val packetLength = bb.int

        if (packetLength < EvdevEvent.EVDEV_MIN_EVENT_SIZE) {
            LimeLog.warning("Short read: ${packetLength}")
            return null
        }

        bb = ByteBuffer.allocate(packetLength).order(ByteOrder.nativeOrder())
        readAll(input, bb)

        if (packetLength == EvdevEvent.EVDEV_MAX_EVENT_SIZE) {
            bb.long
            bb.long
        } else {
            bb.int
            bb.int
        }

        return EvdevEvent(bb.short, bb.short, bb.int)
    }
}
