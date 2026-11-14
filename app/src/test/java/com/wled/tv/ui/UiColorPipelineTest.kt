package com.wled.tv.ui

import com.wled.tv.engine.AmbientStreamingEngine
import com.wled.tv.engine.DiagnosticPattern
import com.wled.tv.engine.DiagnosticStreamCoordinator
import com.wled.tv.engine.TestClock
import com.wled.tv.model.ColorCalibration
import com.wled.tv.model.DeviceType
import com.wled.tv.model.StreamingProtocol
import com.wled.tv.model.WledConfig
import com.wled.tv.model.WledDevice
import com.wled.tv.network.DefaultWledControllerClient
import com.wled.tv.network.FakeWledControllerClient
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * Dedicated test suite verifying UI diagnostic and calibration preview color pipelines
 * routed through [DiagnosticStreamCoordinator]:
 * - Hardware channel order permutation (GRB, RGB, etc.)
 * - Zero-allocation pre-allocated buffer management
 * - Canonical preview buffer preservation (canonical 3-channel RGB unmodified for preview)
 * - Protocol-safe RGBW encoding: only DDP emits 4-channel RGBW; DRGB and WARLS remain 3-channel RGB
 */
class UiColorPipelineTest {

    private lateinit var fakeClient: FakeWledControllerClient
    private lateinit var clock: TestClock

    @Before
    fun setUp() {
        fakeClient = FakeWledControllerClient()
        clock = TestClock(currentTime = 1000L, uptime = 1000L)
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
            colorOrder = "GRB",
            isRgbw = false
        )

        val coordinator = DiagnosticStreamCoordinator(
            initialConfig = WledConfig(devices = listOf(grbDevice)),
            controllerClient = fakeClient,
            clock = clock
        )

        var previewRgb: ByteArray? = null
        coordinator.liveFrameListener = AmbientStreamingEngine.LiveFrameListener { _, rgb, _ ->
            previewRgb = rgb.copyOf()
        }

        // Step 0, 3 LEDs -> Red (255, 0, 0), Green (0, 255, 0), Blue (0, 0, 255)
        coordinator.tick(DiagnosticPattern.Rainbow)

        val sent = fakeClient.sentFrames.last()
        assertEquals(9, sent.wirePayload.size)
        // Verify sent wire payload is encoded in GRB order:
        // LED 0: G=0, R=255, B=0
        assertEquals(0.toByte(), sent.wirePayload[0])
        assertEquals(255.toByte(), sent.wirePayload[1])
        assertEquals(0.toByte(), sent.wirePayload[2])
        // LED 1: G=255, R=0, B=0
        assertEquals(255.toByte(), sent.wirePayload[3])
        assertEquals(0.toByte(), sent.wirePayload[4])
        assertEquals(0.toByte(), sent.wirePayload[5])
        // LED 2: G=0, R=0, B=255
        assertEquals(0.toByte(), sent.wirePayload[6])
        assertEquals(0.toByte(), sent.wirePayload[7])
        assertEquals(255.toByte(), sent.wirePayload[8])

        // Verify preview buffer receives canonical RGB (Red first)
        assertNotNull(previewRgb)
        assertEquals(255.toByte(), previewRgb!![0])
        assertEquals(0.toByte(), previewRgb!![1])
        assertEquals(0.toByte(), previewRgb!![2])
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
            colorOrder = "RGB",
            isRgbw = true,
            rgbwMode = "AUTO_SUBTRACT"
        )

        val coordinator = DiagnosticStreamCoordinator(
            initialConfig = WledConfig(devices = listOf(rgbwDevice)),
            controllerClient = fakeClient,
            clock = clock
        )

        var previewRgb: ByteArray? = null
        coordinator.liveFrameListener = AmbientStreamingEngine.LiveFrameListener { _, rgb, _ ->
            previewRgb = rgb.copyOf()
        }

        // Pure white (255, 255, 255) across 2 LEDs
        coordinator.tick(DiagnosticPattern.SolidColor(255, 255, 255, rgbwDevice.id))

        val sent = fakeClient.sentFrames.last()
        assertEquals(8, sent.wirePayload.size)
        // LED 0 with AUTO_SUBTRACT: R'=0, G'=0, B'=0, W=255
        assertEquals(0.toByte(), sent.wirePayload[0])
        assertEquals(0.toByte(), sent.wirePayload[1])
        assertEquals(0.toByte(), sent.wirePayload[2])
        assertEquals(255.toByte(), sent.wirePayload[3])

        // Canonical preview buffer has 6 bytes (3 per LED) and stays canonical RGB
        assertNotNull(previewRgb)
        assertEquals(6, previewRgb!!.size)
        assertEquals(255.toByte(), previewRgb!![0])
        assertEquals(255.toByte(), previewRgb!![1])
        assertEquals(255.toByte(), previewRgb!![2])
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
            colorOrder = "GRB",
            isRgbw = true, // Configured as RGBW, but DRGB protocol only supports 3 channels
            rgbwMode = "AUTO_SUBTRACT"
        )

        val warlsDevice = WledDevice(
            id = "warls-strip",
            name = "WARLS Strip",
            ip = "192.168.1.63",
            protocol = StreamingProtocol.WARLS,
            type = DeviceType.RIGHT_AMBIENT,
            ledCount = 2,
            colorOrder = "RGB",
            isRgbw = true, // Configured as RGBW, but WARLS protocol only supports 3 channels
            rgbwMode = "AUTO_SUBTRACT"
        )

        // wireBytesPerLed must be 3 for non-DDP protocols regardless of isRgbw
        assertEquals(3, drgbDevice.wireBytesPerLed)
        assertEquals(3, warlsDevice.wireBytesPerLed)

        val coordinator = DiagnosticStreamCoordinator(
            initialConfig = WledConfig(devices = listOf(drgbDevice, warlsDevice)),
            controllerClient = fakeClient,
            clock = clock
        )

        coordinator.tick(DiagnosticPattern.SolidColor(255, 255, 255, drgbDevice.id))
        val drgbSent = fakeClient.sentFrames.last()
        assertEquals(6, drgbSent.wirePayload.size)
        assertEquals(255.toByte(), drgbSent.wirePayload[0])
        assertEquals(255.toByte(), drgbSent.wirePayload[1])
        assertEquals(255.toByte(), drgbSent.wirePayload[2])

        coordinator.tick(DiagnosticPattern.SolidColor(255, 255, 255, warlsDevice.id))
        val warlsSent = fakeClient.sentFrames.last()
        assertEquals(6, warlsSent.wirePayload.size)
        assertEquals(255.toByte(), warlsSent.wirePayload[0])
        assertEquals(255.toByte(), warlsSent.wirePayload[1])
        assertEquals(255.toByte(), warlsSent.wirePayload[2])
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
            colorOrder = "GRB",
            isRgbw = false,
            calibration = ColorCalibration(
                gainR = 1.0f,
                gainG = 1.0f,
                gainB = 1.0f,
                maxBrightness = 255
            )
        )

        val coordinator = DiagnosticStreamCoordinator(
            initialConfig = WledConfig(devices = listOf(dev)),
            controllerClient = fakeClient,
            clock = clock
        )

        coordinator.tick(DiagnosticPattern.SolidColor(255, 128, 64, dev.id))

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
            colorOrder = "GRB",
            isRgbw = true,
            rgbwMode = "AUTO_SUBTRACT",
            calibration = ColorCalibration(
                gainR = 0.5f,
                gainG = 0.5f,
                gainB = 0.5f,
                maxBrightness = 255
            )
        )

        val coordinator = DiagnosticStreamCoordinator(
            initialConfig = WledConfig(devices = listOf(dev)),
            controllerClient = fakeClient,
            clock = clock
        )

        coordinator.tick(DiagnosticPattern.SolidColor(200, 200, 200, dev.id))

        val sent = fakeClient.sentFrames.last()
        assertEquals(8, sent.wirePayload.size)
        // 200 * 0.5 = 100 -> AUTO_SUBTRACT: w = 100, r'=0, g'=0, b'=0
        // Wire order is GRB + W: G=0, R=0, B=0, W=100
        val expectedScaled = (200 * 0.5f).roundToInt().toByte()
        assertEquals(0.toByte(), sent.wirePayload[0])
        assertEquals(0.toByte(), sent.wirePayload[1])
        assertEquals(0.toByte(), sent.wirePayload[2])
        assertEquals(expectedScaled, sent.wirePayload[3])
    }

    @Test
    fun zeroAllocation_uiBuffersPreallocatedAndReusedAcrossIterations() {
        val dev1 = WledDevice(id = "dev1", type = DeviceType.LEFT_AMBIENT, ledCount = 10, protocol = StreamingProtocol.DDP)
        val dev2 = WledDevice(id = "dev2", type = DeviceType.RIGHT_AMBIENT, ledCount = 20, protocol = StreamingProtocol.DRGB, isRgbw = true)

        val coordinator = DiagnosticStreamCoordinator(
            initialConfig = WledConfig(devices = listOf(dev1, dev2)),
            controllerClient = fakeClient,
            clock = clock
        )

        coordinator.tick(DiagnosticPattern.Rainbow)

        val initialDev1Rgb = coordinator.getRgbBuffer("dev1")
        val initialDev1Wire = coordinator.getWireBuffer("dev1")
        val initialDev2Wire = coordinator.getWireBuffer("dev2")

        // dev1: 10 LEDs * 3 wire bytes = 30
        assertEquals(30, initialDev1Wire?.size)
        // dev2: 20 LEDs * 3 wire bytes (DRGB protocol ignores RGBW) = 60
        assertEquals(60, initialDev2Wire?.size)

        repeat(5) {
            coordinator.tick(DiagnosticPattern.Rainbow)
            assertSame(initialDev1Rgb, coordinator.getRgbBuffer("dev1"))
            assertSame(initialDev1Wire, coordinator.getWireBuffer("dev1"))
            assertSame(initialDev2Wire, coordinator.getWireBuffer("dev2"))
        }
    }

    @Test
    fun controllerClient_sizingUsesWireBytesPerLed() = runBlocking {
        val ddpRgbw = WledDevice(
            id = "ddp-rgbw",
            type = DeviceType.LEFT_AMBIENT,
            ledCount = 10,
            protocol = StreamingProtocol.DDP,
            isRgbw = true
        )
        val drgbRgbw = WledDevice(
            id = "drgb-rgbw",
            type = DeviceType.RIGHT_AMBIENT,
            ledCount = 10,
            protocol = StreamingProtocol.DRGB,
            isRgbw = true
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
