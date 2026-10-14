package com.wled.tv.network

import com.wled.tv.model.ColorCalibration
import com.wled.tv.model.DeviceType
import com.wled.tv.model.StreamingProtocol
import com.wled.tv.model.WledDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

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
    fun sendDdpFrame_withBlankIp_doesNotThrow() {
        val sender = WledUdpSender()
        val rgb = byteArrayOf(255.toByte(), 0, 0)
        sender.sendDdpFrame(
            ip = "",
            port = 4048,
            rgb = rgb,
            ledCount = 1
        )
        sender.close()
    }

    @Test
    fun sendDdpFrame_withZeroLeds_doesNotThrow() {
        val sender = WledUdpSender()
        val rgb = byteArrayOf(255.toByte(), 0, 0)
        sender.sendDdpFrame(
            ip = "127.0.0.1",
            port = 4048,
            rgb = rgb,
            ledCount = 0
        )
        sender.close()
    }

    @Test
    fun sendDdpFrame_singlePacket_correctHeaderAndPayload() {
        val receiver = DatagramSocket(0, InetAddress.getByName("127.0.0.1")).apply {
            soTimeout = 2000
        }
        val port = receiver.localPort
        val sender = WledUdpSender()

        try {
            val rgb = byteArrayOf(
                255.toByte(), 0.toByte(), 128.toByte(),
                10.toByte(), 20.toByte(), 30.toByte()
            )
            sender.sendDdpFrame(
                ip = "127.0.0.1",
                port = port,
                rgb = rgb,
                ledCount = 2,
                colorOrder = "RGB",
                isRgbw = false
            )

            val buffer = ByteArray(2048)
            val packet = DatagramPacket(buffer, buffer.size)
            receiver.receive(packet)

            assertEquals(10 + 6, packet.length)
            // Header verification
            // Byte 0: Flags (0x40 version 1 | 0x01 push) = 0x41
            assertEquals(0x41.toByte(), buffer[0])
            // Byte 1: Sequence (1)
            assertEquals(1.toByte(), buffer[1])
            // Byte 2: Data type (0x01 = RGB)
            assertEquals(0x01.toByte(), buffer[2])
            // Byte 3: ID (0x01)
            assertEquals(0x01.toByte(), buffer[3])
            // Byte 4..7: Offset (0)
            assertEquals(0.toByte(), buffer[4])
            assertEquals(0.toByte(), buffer[5])
            assertEquals(0.toByte(), buffer[6])
            assertEquals(0.toByte(), buffer[7])
            // Byte 8..9: Length (6)
            assertEquals(0.toByte(), buffer[8])
            assertEquals(6.toByte(), buffer[9])

            // Payload verification
            assertEquals(255.toByte(), buffer[10])
            assertEquals(0.toByte(), buffer[11])
            assertEquals(128.toByte(), buffer[12])
            assertEquals(10.toByte(), buffer[13])
            assertEquals(20.toByte(), buffer[14])
            assertEquals(30.toByte(), buffer[15])
        } finally {
            sender.close()
            receiver.close()
        }
    }

    @Test
    fun sendDdpFrame_sequenceCyclesProperly() {
        val receiver = DatagramSocket(0, InetAddress.getByName("127.0.0.1")).apply {
            soTimeout = 2000
        }
        val port = receiver.localPort
        val sender = WledUdpSender()

        try {
            val rgb = byteArrayOf(10, 20, 30)
            val buffer = ByteArray(256)

            for (expectedSeq in 1..15) {
                sender.sendDdpFrame("127.0.0.1", port, rgb, 1)
                val packet = DatagramPacket(buffer, buffer.size)
                receiver.receive(packet)
                assertEquals(expectedSeq.toByte(), buffer[1])
            }

            // Sequence rolls over from 15 to 1
            sender.sendDdpFrame("127.0.0.1", port, rgb, 1)
            val packet = DatagramPacket(buffer, buffer.size)
            receiver.receive(packet)
            assertEquals(1.toByte(), buffer[1])
        } finally {
            sender.close()
            receiver.close()
        }
    }

    @Test
    fun sendDdpFrame_multiPacketChunking_setsPushFlagOnlyOnFinalPacket() {
        val receiver = DatagramSocket(0, InetAddress.getByName("127.0.0.1")).apply {
            soTimeout = 2000
        }
        val port = receiver.localPort
        val sender = WledUdpSender()

        try {
            // 500 RGB LEDs = 1500 channels.
            // Chunk 1: 1440 channels (480 LEDs). Chunk 2: 60 channels (20 LEDs).
            val ledCount = 500
            val rgb = ByteArray(ledCount * 3) { (it % 256).toByte() }

            sender.sendDdpFrame("127.0.0.1", port, rgb, ledCount)

            val buf1 = ByteArray(2048)
            val packet1 = DatagramPacket(buf1, buf1.size)
            receiver.receive(packet1)

            // Packet 1: Push flag 0x01 should NOT be set (flags = 0x40)
            assertEquals(10 + 1440, packet1.length)
            assertEquals(0x40.toByte(), buf1[0])
            assertEquals(0, (buf1[4].toInt() shl 24) or (buf1[5].toInt() shl 16) or (buf1[6].toInt() shl 8) or (buf1[7].toInt() and 0xFF))
            val len1 = ((buf1[8].toInt() and 0xFF) shl 8) or (buf1[9].toInt() and 0xFF)
            assertEquals(1440, len1)

            val buf2 = ByteArray(2048)
            val packet2 = DatagramPacket(buf2, buf2.size)
            receiver.receive(packet2)

            // Packet 2: Push flag 0x01 MUST be set (flags = 0x41)
            assertEquals(10 + 60, packet2.length)
            assertEquals(0x41.toByte(), buf2[0])
            val offset2 = ((buf2[4].toInt() and 0xFF) shl 24) or ((buf2[5].toInt() and 0xFF) shl 16) or ((buf2[6].toInt() and 0xFF) shl 8) or (buf2[7].toInt() and 0xFF)
            assertEquals(1440, offset2)
            val len2 = ((buf2[8].toInt() and 0xFF) shl 8) or (buf2[9].toInt() and 0xFF)
            assertEquals(60, len2)
        } finally {
            sender.close()
            receiver.close()
        }
    }

    @Test
    fun sendDdpFrame_rgbwAutoSubtract_correctGamutMapping() {
        val receiver = DatagramSocket(0, InetAddress.getByName("127.0.0.1")).apply {
            soTimeout = 2000
        }
        val port = receiver.localPort
        val sender = WledUdpSender()

        try {
            // Test pixel: R=255, G=128, B=64.
            // Min(255, 128, 64) = 64.
            // AUTO_SUBTRACT:
            // R' = 255 - 64 = 191
            // G' = 128 - 64 = 64
            // B' = 64 - 64 = 0
            // W' = 64
            val rgb = byteArrayOf(255.toByte(), 128.toByte(), 64.toByte())

            sender.sendDdpFrame(
                ip = "127.0.0.1",
                port = port,
                rgb = rgb,
                ledCount = 1,
                colorOrder = "RGB",
                isRgbw = true,
                rgbwMode = "AUTO_SUBTRACT"
            )

            val buffer = ByteArray(256)
            val packet = DatagramPacket(buffer, buffer.size)
            receiver.receive(packet)

            assertEquals(10 + 4, packet.length)
            // Byte 2: Data type (0x02 = RGBW)
            assertEquals(0x02.toByte(), buffer[2])

            // RGBW payload
            assertEquals(191.toByte(), buffer[10]) // R'
            assertEquals(64.toByte(), buffer[11])  // G'
            assertEquals(0.toByte(), buffer[12])   // B'
            assertEquals(64.toByte(), buffer[13])  // W'
        } finally {
            sender.close()
            receiver.close()
        }
    }

    @Test
    fun sendDdpFrame_rgbwAutoAdd_keepsRgbAndAddsWhite() {
        val receiver = DatagramSocket(0, InetAddress.getByName("127.0.0.1")).apply {
            soTimeout = 2000
        }
        val port = receiver.localPort
        val sender = WledUdpSender()

        try {
            // Test pixel: R=200, G=100, B=50. Min = 50.
            // AUTO_ADD: R'=200, G'=100, B'=50, W'=50
            val rgb = byteArrayOf(200.toByte(), 100.toByte(), 50.toByte())

            sender.sendDdpFrame(
                ip = "127.0.0.1",
                port = port,
                rgb = rgb,
                ledCount = 1,
                colorOrder = "RGB",
                isRgbw = true,
                rgbwMode = "AUTO_ADD"
            )

            val buffer = ByteArray(256)
            val packet = DatagramPacket(buffer, buffer.size)
            receiver.receive(packet)

            assertEquals(10 + 4, packet.length)
            assertEquals(0x02.toByte(), buffer[2]) // RGBW data type
            assertEquals(200.toByte(), buffer[10])
            assertEquals(100.toByte(), buffer[11])
            assertEquals(50.toByte(), buffer[12])
            assertEquals(50.toByte(), buffer[13])
        } finally {
            sender.close()
            receiver.close()
        }
    }

    @Test
    fun sendDeviceFrame_dispatchesCorrectProtocol() {
        val receiver = DatagramSocket(0, InetAddress.getByName("127.0.0.1")).apply {
            soTimeout = 2000
        }
        val port = receiver.localPort
        val sender = WledUdpSender()

        try {
            val ddpDev = WledDevice(
                id = "ddp1",
                name = "DDP Dev",
                ip = "127.0.0.1",
                port = port,
                protocol = StreamingProtocol.DDP,
                type = DeviceType.LEFT_AMBIENT,
                ledCount = 1
            )
            val drgbDev = WledDevice(
                id = "drgb1",
                name = "DRGB Dev",
                ip = "127.0.0.1",
                port = port,
                protocol = StreamingProtocol.DRGB,
                type = DeviceType.LEFT_AMBIENT,
                ledCount = 1
            )
            val warlsDev = WledDevice(
                id = "warls1",
                name = "WARLS Dev",
                ip = "127.0.0.1",
                port = port,
                protocol = StreamingProtocol.WARLS,
                type = DeviceType.LEFT_AMBIENT,
                ledCount = 1
            )

            val rgb = byteArrayOf(10, 20, 30)
            val buffer = ByteArray(256)

            // Test DDP dispatch
            sender.sendDeviceFrame(ddpDev, rgb, 1)
            var packet = DatagramPacket(buffer, buffer.size)
            receiver.receive(packet)
            assertEquals(0x41.toByte(), buffer[0]) // DDP push flag

            // Test DRGB dispatch
            sender.sendDeviceFrame(drgbDev, rgb, 1)
            packet = DatagramPacket(buffer, buffer.size)
            receiver.receive(packet)
            assertEquals(0x02.toByte(), buffer[0]) // DRGB identifier

            // Test WARLS dispatch
            sender.sendDeviceFrame(warlsDev, rgb, 1)
            packet = DatagramPacket(buffer, buffer.size)
            receiver.receive(packet)
            assertEquals(0x01.toByte(), buffer[0]) // WARLS identifier
        } finally {
            sender.close()
            receiver.close()
        }
    }

    @Test
    fun close_multipleTimes_isSafe() {
        val sender = WledUdpSender()
        sender.close()
        sender.close()
        assertTrue(true)
    }
}

