package com.wled.tv.ui

import com.wled.tv.model.ColorCalibration
import com.wled.tv.model.DeviceType
import com.wled.tv.model.StreamingProtocol
import com.wled.tv.model.WledDevice
import com.wled.tv.network.DefaultWledControllerClient
import com.wled.tv.network.FakeWledControllerClient
import com.wled.tv.processing.ScreenColorProcessor
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * Dedicated test suite verifying UI test pattern and calibration preview color pipelines:
 * - Hardware channel order permutation (GRB, RGB, etc.)
 * - Zero-allocation pre-allocated buffer management in UI loops
 * - Canonical preview buffer preservation (canonical 3-channel RGB unmodified for preview)
 * - Protocol-safe RGBW encoding: only DDP emits 4-channel RGBW; DRGB and WARLS remain 3-channel RGB
 */
class UiColorPipelineTest {

    private lateinit var fakeClient: FakeWledControllerClient

    @Before
    fun setUp() {
        fakeClient = FakeWledControllerClient()
        DefaultWledControllerClient.setInstanceForTesting(fakeClient)
    }

    @After
    fun tearDown() {
        DefaultWledControllerClient.setInstanceForTesting(null)
    }

    @Test
    fun mainActivity_testPattern_wireEncodesGrbBeforeTransmissionAndPreservesCanonicalPreview() {
        val grbDevice = WledDevice(
            id = "grb-strip",
            name = "GRB Test Strip",
            ip = "192.168.1.60",
            protocol = StreamingProtocol.DDP,
            type = DeviceType.LEFT_AMBIENT,
            ledCount = 3,
            calibration = ColorCalibration(colorOrder = "GRB", isRgbw = false)
        )

        val leds = grbDevice.totalLeds
        assertEquals(3, leds)
        assertEquals(3, grbDevice.wireBytesPerLed)

        val testBuffer = ByteArray(leds * 3)
        // LED 0: Red (255, 0, 0), LED 1: Green (0, 255, 0), LED 2: Blue (0, 0, 255)
        testBuffer[0] = 255.toByte(); testBuffer[1] = 0; testBuffer[2] = 0
        testBuffer[3] = 0; testBuffer[4] = 255.toByte(); testBuffer[5] = 0
        testBuffer[6] = 0; testBuffer[7] = 0; testBuffer[8] = 255.toByte()

        val wireBuffer = ByteArray(leds * grbDevice.wireBytesPerLed)
        val written = ScreenColorProcessor.encodeWireFrame(grbDevice, testBuffer, wireBuffer)
        assertEquals(9, written)

        fakeClient.sendFrame(device = grbDevice, wirePayload = wireBuffer, ledCount = leds)

        // Verify sent wire payload is encoded in GRB order:
        // LED 0: G=0, R=255, B=0
        // LED 1: G=255, R=0, B=0
        // LED 2: G=0, R=0, B=255
        val sent = fakeClient.sentFrames.last()
        assertEquals(9, sent.wirePayload.size)
        assertEquals(0.toByte(), sent.wirePayload[0])
        assertEquals(255.toByte(), sent.wirePayload[1])
        assertEquals(0.toByte(), sent.wirePayload[2])

        assertEquals(255.toByte(), sent.wirePayload[3])
        assertEquals(0.toByte(), sent.wirePayload[4])
        assertEquals(0.toByte(), sent.wirePayload[5])

        assertEquals(0.toByte(), sent.wirePayload[6])
        assertEquals(0.toByte(), sent.wirePayload[7])
        assertEquals(255.toByte(), sent.wirePayload[8])

        // Verify original testBuffer remains unchanged canonical RGB for preview
        assertEquals(255.toByte(), testBuffer[0])
        assertEquals(0.toByte(), testBuffer[1])
        assertEquals(0.toByte(), testBuffer[2])
    }

    @Test
    fun mainActivity_testPattern_wireEncodesRgbwBeforeTransmissionAndPreservesCanonicalPreview() {
        val rgbwDevice = WledDevice(
            id = "rgbw-strip",
            name = "RGBW Test Strip",
            ip = "192.168.1.61",
            protocol = StreamingProtocol.DDP,
            type = DeviceType.RIGHT_AMBIENT,
            ledCount = 2,
            calibration = ColorCalibration(
                colorOrder = "RGB",
                isRgbw = true,
                rgbwMode = "AUTO_SUBTRACT"
            )
        )

        val leds = rgbwDevice.totalLeds
        assertEquals(2, leds)
        assertEquals(4, rgbwDevice.wireBytesPerLed)

        val testBuffer = ByteArray(leds * 3)
        // LED 0: Pure white (255, 255, 255)
        testBuffer[0] = 255.toByte(); testBuffer[1] = 255.toByte(); testBuffer[2] = 255.toByte()
        // LED 1: Saturated red (255, 0, 0)
        testBuffer[3] = 255.toByte(); testBuffer[4] = 0; testBuffer[5] = 0

        val wireBuffer = ByteArray(leds * rgbwDevice.wireBytesPerLed)
        val written = ScreenColorProcessor.encodeWireFrame(rgbwDevice, testBuffer, wireBuffer)
        assertEquals(8, written)

        fakeClient.sendFrame(device = rgbwDevice, wirePayload = wireBuffer, ledCount = leds)

        val sent = fakeClient.sentFrames.last()
        assertEquals(8, sent.wirePayload.size)
        // LED 0 with AUTO_SUBTRACT: R'=0, G'=0, B'=0, W=255
        assertEquals(0.toByte(), sent.wirePayload[0])
        assertEquals(0.toByte(), sent.wirePayload[1])
        assertEquals(0.toByte(), sent.wirePayload[2])
        assertEquals(255.toByte(), sent.wirePayload[3])

        // LED 1 with AUTO_SUBTRACT: R'=255, G'=0, B'=0, W=0
        assertEquals(255.toByte(), sent.wirePayload[4])
        assertEquals(0.toByte(), sent.wirePayload[5])
        assertEquals(0.toByte(), sent.wirePayload[6])
        assertEquals(0.toByte(), sent.wirePayload[7])

        // Canonical preview buffer has 6 bytes (3 per LED) and is unmodified
        assertEquals(6, testBuffer.size)
        assertEquals(255.toByte(), testBuffer[0])
        assertEquals(255.toByte(), testBuffer[1])
        assertEquals(255.toByte(), testBuffer[2])
    }

    @Test
    fun mainActivity_testPattern_protocolSafeRgbw_drgbAndWarlsNeverEmits4ChannelPayload() {
        val drgbDevice = WledDevice(
            id = "drgb-strip",
            name = "DRGB Strip",
            ip = "192.168.1.62",
            protocol = StreamingProtocol.DRGB,
            type = DeviceType.LEFT_AMBIENT,
            ledCount = 2,
            calibration = ColorCalibration(
                colorOrder = "GRB",
                isRgbw = true, // Configured as RGBW, but DRGB protocol only supports 3 channels
                rgbwMode = "AUTO_SUBTRACT"
            )
        )

        val warlsDevice = WledDevice(
            id = "warls-strip",
            name = "WARLS Strip",
            ip = "192.168.1.63",
            protocol = StreamingProtocol.WARLS,
            type = DeviceType.RIGHT_AMBIENT,
            ledCount = 2,
            calibration = ColorCalibration(
                colorOrder = "RGB",
                isRgbw = true, // Configured as RGBW, but WARLS protocol only supports 3 channels
                rgbwMode = "AUTO_SUBTRACT"
            )
        )

        // wireBytesPerLed must be 3 for non-DDP protocols regardless of isRgbw
        assertEquals(3, drgbDevice.wireBytesPerLed)
        assertEquals(3, warlsDevice.wireBytesPerLed)

        val source = byteArrayOf(
            255.toByte(), 255.toByte(), 255.toByte(),
            255.toByte(), 0, 0
        )

        val drgbWire = ByteArray(drgbDevice.totalLeds * drgbDevice.wireBytesPerLed)
        val drgbWritten = ScreenColorProcessor.encodeWireFrame(drgbDevice, source, drgbWire)
        assertEquals(6, drgbWritten)
        assertEquals(6, drgbWire.size)
        // GRB order, 3 bytes: G=255, R=255, B=255
        assertEquals(255.toByte(), drgbWire[0])
        assertEquals(255.toByte(), drgbWire[1])
        assertEquals(255.toByte(), drgbWire[2])

        val warlsWire = ByteArray(warlsDevice.totalLeds * warlsDevice.wireBytesPerLed)
        val warlsWritten = ScreenColorProcessor.encodeWireFrame(warlsDevice, source, warlsWire)
        assertEquals(6, warlsWritten)
        assertEquals(6, warlsWire.size)
        // RGB order, 3 bytes: R=255, G=255, B=255
        assertEquals(255.toByte(), warlsWire[0])
        assertEquals(255.toByte(), warlsWire[1])
        assertEquals(255.toByte(), warlsWire[2])
    }

    @Test
    fun calibrationActivity_realTimePreview_wireEncodesGrbFrameBeforeTransmission() {
        val dev = WledDevice(
            id = "grb-calib",
            name = "GRB Calibration Strip",
            ip = "192.168.1.70",
            protocol = StreamingProtocol.DDP,
            type = DeviceType.LEFT_AMBIENT,
            ledCount = 2,
            calibration = ColorCalibration(
                colorOrder = "GRB",
                isRgbw = false,
                gainR = 1.0f,
                gainG = 1.0f,
                gainB = 1.0f,
                maxBrightness = 255
            )
        )

        val leds = dev.totalLeds
        assertEquals(2, leds)
        assertEquals(3, dev.wireBytesPerLed)

        val activeTestColor = Triple(255, 128, 64)
        val c = dev.calibration
        val briScale = c.maxBrightness / 255f

        val r = (activeTestColor.first * c.gainR * briScale).roundToInt().coerceIn(0, 255).toByte()
        val g = (activeTestColor.second * c.gainG * briScale).roundToInt().coerceIn(0, 255).toByte()
        val b = (activeTestColor.third * c.gainB * briScale).roundToInt().coerceIn(0, 255).toByte()

        val frame = ByteArray(leds * 3)
        for (i in 0 until leds) {
            val idx = i * 3
            frame[idx] = r
            frame[idx + 1] = g
            frame[idx + 2] = b
        }

        val wireBuffer = ByteArray(leds * dev.wireBytesPerLed)
        val written = ScreenColorProcessor.encodeWireFrame(dev, frame, wireBuffer)
        assertEquals(6, written)

        fakeClient.sendFrame(device = dev, wirePayload = wireBuffer, ledCount = leds)

        val sent = fakeClient.sentFrames.last()
        assertEquals(6, sent.wirePayload.size)
        // GRB order: G (128), R (255), B (64)
        assertEquals(128.toByte(), sent.wirePayload[0])
        assertEquals(255.toByte(), sent.wirePayload[1])
        assertEquals(64.toByte(), sent.wirePayload[2])
    }

    @Test
    fun calibrationActivity_realTimePreview_wireEncodesRgbwFrameWithAutoSubtract() {
        val dev = WledDevice(
            id = "rgbw-calib",
            name = "RGBW Calibration Strip",
            ip = "192.168.1.71",
            protocol = StreamingProtocol.DDP,
            type = DeviceType.RIGHT_AMBIENT,
            ledCount = 2,
            calibration = ColorCalibration(
                colorOrder = "GRB",
                isRgbw = true,
                rgbwMode = "AUTO_SUBTRACT",
                gainR = 1.0f,
                gainG = 1.0f,
                gainB = 1.0f,
                maxBrightness = 255
            )
        )

        val leds = dev.totalLeds
        assertEquals(2, leds)
        assertEquals(4, dev.wireBytesPerLed)

        val activeTestColor = Triple(200, 200, 200)
        val c = dev.calibration
        val briScale = c.maxBrightness / 255f

        val r = (activeTestColor.first * c.gainR * briScale).roundToInt().coerceIn(0, 255).toByte()
        val g = (activeTestColor.second * c.gainG * briScale).roundToInt().coerceIn(0, 255).toByte()
        val b = (activeTestColor.third * c.gainB * briScale).roundToInt().coerceIn(0, 255).toByte()

        val frame = ByteArray(leds * 3)
        for (i in 0 until leds) {
            val idx = i * 3
            frame[idx] = r
            frame[idx + 1] = g
            frame[idx + 2] = b
        }

        val wireBuffer = ByteArray(leds * dev.wireBytesPerLed)
        val written = ScreenColorProcessor.encodeWireFrame(dev, frame, wireBuffer)
        assertEquals(8, written)

        fakeClient.sendFrame(device = dev, wirePayload = wireBuffer, ledCount = leds)

        val sent = fakeClient.sentFrames.last()
        assertEquals(8, sent.wirePayload.size)
        // With AUTO_SUBTRACT for (200, 200, 200):
        // w = 200, r'=0, g'=0, b'=0
        // Wire order is GRB + W: G=0, R=0, B=0, W=200
        assertEquals(0.toByte(), sent.wirePayload[0])
        assertEquals(0.toByte(), sent.wirePayload[1])
        assertEquals(0.toByte(), sent.wirePayload[2])
        assertEquals(200.toByte(), sent.wirePayload[3])
    }

    @Test
    fun zeroAllocation_uiBuffersPreallocatedAndReusedAcrossIterations() {
        val dev1 = WledDevice(id = "dev1", type = DeviceType.LEFT_AMBIENT, ledCount = 10, protocol = StreamingProtocol.DDP)
        val dev2 = WledDevice(id = "dev2", type = DeviceType.RIGHT_AMBIENT, ledCount = 20, protocol = StreamingProtocol.DRGB, calibration = ColorCalibration(isRgbw = true))

        val devices = listOf(dev1, dev2)
        val testBuffers = HashMap<String, ByteArray>()
        val wireBuffers = HashMap<String, ByteArray>()

        for (dev in devices) {
            val leds = dev.totalLeds
            if (leds > 0) {
                testBuffers[dev.id] = ByteArray(leds * 3)
                wireBuffers[dev.id] = ByteArray(leds * dev.wireBytesPerLed)
            }
        }

        // dev1: 10 LEDs * 3 wire bytes = 30
        assertEquals(30, wireBuffers["dev1"]?.size)
        // dev2: 20 LEDs * 3 wire bytes (DRGB protocol ignores RGBW) = 60
        assertEquals(60, wireBuffers["dev2"]?.size)

        val initialDev1Test = testBuffers["dev1"]
        val initialDev1Wire = wireBuffers["dev1"]

        // Simulate loop iteration
        repeat(5) {
            for (dev in devices) {
                val tBuf = testBuffers[dev.id]!!
                val wBuf = wireBuffers[dev.id]!!
                assertSame(initialDev1Test, testBuffers["dev1"])
                assertSame(initialDev1Wire, wireBuffers["dev1"])
                ScreenColorProcessor.encodeWireFrame(dev, tBuf, wBuf)
            }
        }
    }

    @Test
    fun controllerClient_sizingUsesWireBytesPerLed() = runBlocking {
        val ddpRgbw = WledDevice(
            id = "ddp-rgbw",
            type = DeviceType.LEFT_AMBIENT,
            ledCount = 10,
            protocol = StreamingProtocol.DDP,
            calibration = ColorCalibration(isRgbw = true)
        )
        val drgbRgbw = WledDevice(
            id = "drgb-rgbw",
            type = DeviceType.RIGHT_AMBIENT,
            ledCount = 10,
            protocol = StreamingProtocol.DRGB,
            calibration = ColorCalibration(isRgbw = true)
        )

        // Test blackout sizing
        fakeClient.blackoutFleet(listOf(ddpRgbw, drgbRgbw), sendPowerOff = false)
        val frames = fakeClient.sentFrames
        assertEquals(6, frames.size) // 3 bursts for each of 2 devices

        val ddpBursts = frames.filter { it.device.id == "ddp-rgbw" }
        assertEquals(3, ddpBursts.size)
        // DDP RGBW = 10 * 4 = 40 bytes
        assertEquals(40, ddpBursts[0].wirePayload.size)

        val drgbBursts = frames.filter { it.device.id == "drgb-rgbw" }
        assertEquals(3, drgbBursts.size)
        // DRGB RGBW = 10 * 3 = 30 bytes (protocol safe)
        assertEquals(30, drgbBursts[0].wirePayload.size)

        // Test flash sizing
        fakeClient.clear()
        fakeClient.flashDevice(drgbRgbw, durationMs = 0L)
        assertEquals(2, fakeClient.sentFrames.size) // white + black
        assertEquals(30, fakeClient.sentFrames[0].wirePayload.size)
        assertEquals(30, fakeClient.sentFrames[1].wirePayload.size)
    }
}
