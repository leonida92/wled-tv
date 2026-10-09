package com.wled.tv.network

import org.junit.Assert.assertTrue
import org.junit.Test

class WledUdpSenderTest {

    @Test
    fun sendDrgbFrame_withBlankIp_doesNotThrow() {
        val sender = WledUdpSender()
        val rgb = byteArrayOf(255.toByte(), 0, 0)
        sender.sendDrgbFrame(
            ip = "",
            port = 21324,
            rgb = rgb,
            ledCount = 1,
            colorOrder = "RGB"
        )
        sender.close()
    }

    @Test
    fun sendDrgbFrame_withZeroLeds_doesNotThrow() {
        val sender = WledUdpSender()
        val rgb = byteArrayOf(255.toByte(), 0, 0)
        sender.sendDrgbFrame(
            ip = "127.0.0.1",
            port = 21324,
            rgb = rgb,
            ledCount = 0,
            colorOrder = "RGB"
        )
        sender.close()
    }

    @Test
    fun close_multipleTimes_isSafe() {
        val sender = WledUdpSender()
        sender.close()
        sender.close()
        assertTrue(true)
    }
}
