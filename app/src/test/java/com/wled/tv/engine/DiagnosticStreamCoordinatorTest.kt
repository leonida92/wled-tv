package com.wled.tv.engine

import com.wled.tv.model.ColorCalibration
import com.wled.tv.model.DeviceType
import com.wled.tv.model.StreamingProtocol
import com.wled.tv.model.WledConfig
import com.wled.tv.model.WledDevice
import com.wled.tv.network.FakeWledControllerClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticStreamCoordinatorTest {

    private lateinit var clock: TestClock
    private lateinit var fakeClient: FakeWledControllerClient

    @Before
    fun setUp() {
        clock = TestClock(currentTime = 1000L, uptime = 1000L)
        fakeClient = FakeWledControllerClient()
    }

    private fun makeDevice(
        id: String = "dev-1",
        ip: String = "192.168.1.50",
        enabled: Boolean = true,
        ledCount: Int = 4,
        protocol: StreamingProtocol = StreamingProtocol.DDP,
        colorOrder: String = "RGB",
        isRgbw: Boolean = false,
        rgbwMode: String = "AUTO_SUBTRACT",
        calibration: ColorCalibration = ColorCalibration()
    ): WledDevice = WledDevice(
        id = id,
        name = "Test Fixture $id",
        ip = ip,
        port = 4048,
        protocol = protocol,
        enabled = enabled,
        type = DeviceType.LEFT_AMBIENT,
        ledCount = ledCount,
        colorOrder = colorOrder,
        isRgbw = isRgbw,
        rgbwMode = rgbwMode,
        calibration = calibration
    )

    @Test
    fun standaloneMode_streamsWithoutActiveEngine() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dev = makeDevice(id = "solo", ledCount = 2, colorOrder = "GRB")
        val coordinator = DiagnosticStreamCoordinator(
            initialConfig = WledConfig(devices = listOf(dev)),
            controllerClient = fakeClient,
            engineProvider = { null },
            clock = clock,
            ioDispatcher = dispatcher
        )

        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        coordinator.startSolidColor(scope, r = 100, g = 200, b = 50, deviceId = "solo")
        assertTrue(coordinator.isRunning)
        assertEquals(DiagnosticPattern.SolidColor(100, 200, 50, "solo"), coordinator.activePattern)

        runCurrent()
        assertTrue("Expected at least one frame emitted in standalone mode", fakeClient.sentFrames.isNotEmpty())
        val sent = fakeClient.sentFrames.last()
        assertEquals(2, sent.ledCount)
        // GRB order: G=200, R=100, B=50
        assertEquals(200.toByte(), sent.wirePayload[0])
        assertEquals(100.toByte(), sent.wirePayload[1])
        assertEquals(50.toByte(), sent.wirePayload[2])

        coordinator.stop()
        assertFalse(coordinator.isRunning)
        assertNull(coordinator.activePattern)
        scope.cancel()
    }

    @Test
    fun activeEngineIntegration_suppressesLiveCaptureAndRestoresOnStop() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dev = makeDevice(id = "main", ledCount = 3)
        val config = WledConfig(devices = listOf(dev))
        val engine = AmbientStreamingEngine(
            initialConfig = config,
            controllerClient = fakeClient,
            clock = clock
        )
        val coordinator = DiagnosticStreamCoordinator(
            initialConfig = config,
            controllerClient = fakeClient,
            engineProvider = { engine },
            clock = clock,
            ioDispatcher = dispatcher
        )

        assertFalse(engine.isDiagnosticActive)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        coordinator.startRainbow(scope)

        // Immediately suppressed synchronously after start()
        assertTrue(engine.isDiagnosticActive)

        val blackOrRedFrame = ByteBuffer.allocateDirect(320 * 180 * 4)
        assertFalse(
            "Live frame intake must be suppressed while diagnostic stream is active",
            engine.onFrameAvailable(blackOrRedFrame, 320, 180, 320 * 4, 4)
        )
        fakeClient.clear()
        engine.tickStreaming()
        assertEquals("Live engine must not emit frames while diagnostic is active", 0, fakeClient.sentFrames.size)

        // Advance coroutine scheduler so diagnostic emits a rainbow frame
        runCurrent()
        assertEquals(1, fakeClient.sentFrames.size)

        // Switching pattern directly must keep override active with no gap
        coordinator.startSolidColor(scope, 255, 0, 0, "main")
        assertTrue(engine.isDiagnosticActive)
        runCurrent()
        assertEquals(DiagnosticPattern.SolidColor(255, 0, 0, "main"), coordinator.activePattern)

        // Stopping immediately restores live engine intake/streaming
        coordinator.stop()
        assertFalse(engine.isDiagnosticActive)

        clock.advance(20L)
        assertTrue(engine.onFrameAvailable(blackOrRedFrame, 320, 180, 320 * 4, 4))
        fakeClient.clear()
        clock.advance(16L)
        engine.tickStreaming()
        assertEquals(1, fakeClient.sentFrames.size)

        scope.cancel()
    }

    @Test
    fun solidColor_realTimeConfigUpdateReflectsCalibrationAndColorOrder() {
        val dev = makeDevice(
            id = "calib",
            ledCount = 2,
            colorOrder = "RGB",
            calibration = ColorCalibration(gainR = 1.0f, gainG = 1.0f, gainB = 1.0f, maxBrightness = 255)
        )
        val coordinator = DiagnosticStreamCoordinator(
            initialConfig = WledConfig(devices = listOf(dev)),
            controllerClient = fakeClient,
            clock = clock
        )

        val pattern = DiagnosticPattern.SolidColor(r = 200, g = 100, b = 50, deviceId = "calib")
        val delayMs = coordinator.tick(pattern)
        assertEquals(100L, delayMs)

        val frame1 = fakeClient.sentFrames.last()
        assertEquals(200.toByte(), frame1.wirePayload[0]) // R
        assertEquals(100.toByte(), frame1.wirePayload[1]) // G
        assertEquals(50.toByte(), frame1.wirePayload[2])  // B

        // Adjust calibration gains, maxBrightness, and colorOrder in real time
        val updatedDev = dev.copy(
            colorOrder = "GRB",
            calibration = ColorCalibration(gainR = 0.5f, gainG = 1.5f, gainB = 2.0f, maxBrightness = 128)
        )
        coordinator.updateConfig(WledConfig(devices = listOf(updatedDev)))
        coordinator.tick(pattern)

        val frame2 = fakeClient.sentFrames.last()
        val briScale = 128 / 255f
        val expR = kotlin.math.round(200 * 0.5f * briScale).toInt().coerceIn(0, 255).toByte()
        val expG = kotlin.math.round(100 * 1.5f * briScale).toInt().coerceIn(0, 255).toByte()
        val expB = kotlin.math.round(50 * 2.0f * briScale).toInt().coerceIn(0, 255).toByte()
        // GRB order
        assertEquals(expG, frame2.wirePayload[0])
        assertEquals(expR, frame2.wirePayload[1])
        assertEquals(expB, frame2.wirePayload[2])
    }

    @Test
    fun rainbowPattern_emitsPureKotlinHsvRgbToListenerAndOverlayWhileWireEncoding() {
        val grbDev = makeDevice(id = "rainbow-grb", ledCount = 3, colorOrder = "GRB", enabled = true)
        val disabledDev = makeDevice(id = "disabled", ledCount = 3, enabled = false)
        val blankIpDev = makeDevice(id = "blank", ip = " ", ledCount = 3, enabled = true)
        val config = WledConfig(devices = listOf(grbDev, disabledDev, blankIpDev))
        val engine = AmbientStreamingEngine(initialConfig = config, controllerClient = fakeClient, clock = clock)

        val coordinator = DiagnosticStreamCoordinator(
            initialConfig = config,
            controllerClient = fakeClient,
            engineProvider = { engine },
            clock = clock
        )

        var previewId: String? = null
        var previewRgb: ByteArray? = null
        var previewCount = 0
        coordinator.liveFrameListener = AmbientStreamingEngine.LiveFrameListener { id, rgb, count ->
            previewId = id
            previewRgb = rgb.copyOf()
            previewCount = count
        }

        // Step 0 with 3 LEDs -> hues 0 (Red), 120 (Green), 240 (Blue)
        val delayMs = coordinator.tick(DiagnosticPattern.Rainbow)
        assertEquals(33L, delayMs)

        // Only enabled device with non-blank IP receives frames
        assertEquals(1, fakeClient.sentFrames.size)
        val sent = fakeClient.sentFrames.last()
        assertEquals("rainbow-grb", sent.device.id)
        // Wire payload is GRB:
        // LED 0 (Red: 255, 0, 0) -> G=0, R=255, B=0
        assertEquals(0.toByte(), sent.wirePayload[0])
        assertEquals(255.toByte(), sent.wirePayload[1])
        assertEquals(0.toByte(), sent.wirePayload[2])
        // LED 1 (Green: 0, 255, 0) -> G=255, R=0, B=0
        assertEquals(255.toByte(), sent.wirePayload[3])
        assertEquals(0.toByte(), sent.wirePayload[4])
        assertEquals(0.toByte(), sent.wirePayload[5])
        // LED 2 (Blue: 0, 0, 255) -> G=0, R=0, B=255
        assertEquals(0.toByte(), sent.wirePayload[6])
        assertEquals(0.toByte(), sent.wirePayload[7])
        assertEquals(255.toByte(), sent.wirePayload[8])

        // LiveFrameListener receives canonical RGB:
        assertEquals("rainbow-grb", previewId)
        assertEquals(3, previewCount)
        assertNotNull(previewRgb)
        assertEquals(255.toByte(), previewRgb!![0])
        assertEquals(0.toByte(), previewRgb!![1])
        assertEquals(0.toByte(), previewRgb!![2])

        // Engine overlay snapshot mirrors canonical RGB:
        val overlay = engine.getOverlayData(grbDev)
        assertNotNull(overlay)
        assertEquals(255.toByte(), overlay!!.rgb[0])
        assertEquals(0.toByte(), overlay.rgb[1])
        assertEquals(0.toByte(), overlay.rgb[2])

        // Next tick advances rainbow step (hue shifts by +4 deg)
        coordinator.tick(DiagnosticPattern.Rainbow)
        assertEquals(2, fakeClient.sentFrames.size)
        assertFalse(fakeClient.sentFrames[0].wirePayload.contentEquals(fakeClient.sentFrames[1].wirePayload))
    }

    @Test
    fun protocolSafeRgbw_ddpUses4ChannelsWhileDrgbAndWarlsStay3Channels() {
        val ddpRgbw = makeDevice(
            id = "ddp-rgbw",
            ledCount = 1,
            protocol = StreamingProtocol.DDP,
            colorOrder = "GRB",
            isRgbw = true,
            rgbwMode = "AUTO_SUBTRACT"
        )
        val drgbRgbw = makeDevice(
            id = "drgb-rgbw",
            ledCount = 1,
            protocol = StreamingProtocol.DRGB,
            colorOrder = "GRB",
            isRgbw = true
        )
        val warlsRgbw = makeDevice(
            id = "warls-rgbw",
            ledCount = 1,
            protocol = StreamingProtocol.WARLS,
            colorOrder = "RGB",
            isRgbw = true
        )

        val coordinator = DiagnosticStreamCoordinator(
            initialConfig = WledConfig(devices = listOf(ddpRgbw, drgbRgbw, warlsRgbw)),
            controllerClient = fakeClient,
            clock = clock
        )

        coordinator.tick(DiagnosticPattern.SolidColor(200, 200, 200, "ddp-rgbw"))
        coordinator.tick(DiagnosticPattern.SolidColor(200, 200, 200, "drgb-rgbw"))
        coordinator.tick(DiagnosticPattern.SolidColor(200, 200, 200, "warls-rgbw"))

        assertEquals(3, fakeClient.sentFrames.size)
        // DDP RGBW AUTO_SUBTRACT: [G'=0, R'=0, B'=0, W=200] (4 bytes)
        assertEquals(4, fakeClient.sentFrames[0].wirePayload.size)
        assertEquals(0.toByte(), fakeClient.sentFrames[0].wirePayload[0])
        assertEquals(200.toByte(), fakeClient.sentFrames[0].wirePayload[3])
        // DRGB & WARLS stay 3 channels
        assertEquals(3, fakeClient.sentFrames[1].wirePayload.size)
        assertEquals(3, fakeClient.sentFrames[2].wirePayload.size)
    }

    @Test
    fun zeroAllocation_reusesBuffersInSteadyStateAndResizesOnlyOnLayoutChange() {
        val dev = makeDevice(id = "reuse", ledCount = 10, protocol = StreamingProtocol.DDP, isRgbw = false)
        val coordinator = DiagnosticStreamCoordinator(
            initialConfig = WledConfig(devices = listOf(dev)),
            controllerClient = fakeClient,
            clock = clock
        )

        coordinator.tick(DiagnosticPattern.Rainbow)
        val initialRgb = coordinator.getRgbBuffer("reuse")
        val initialWire = coordinator.getWireBuffer("reuse")
        assertNotNull(initialRgb)
        assertNotNull(initialWire)
        assertEquals(30, initialRgb!!.size)
        assertEquals(30, initialWire!!.size)

        repeat(25) {
            coordinator.tick(DiagnosticPattern.Rainbow)
            assertSame(initialRgb, coordinator.getRgbBuffer("reuse"))
            assertSame(initialWire, coordinator.getWireBuffer("reuse"))
        }

        // Changing LED count resizes cleanly
        coordinator.updateConfig(WledConfig(devices = listOf(dev.copy(ledCount = 15))))
        coordinator.tick(DiagnosticPattern.Rainbow)
        val resizedRgb = coordinator.getRgbBuffer("reuse")
        val resizedWire = coordinator.getWireBuffer("reuse")
        assertNotSame(initialRgb, resizedRgb)
        assertNotSame(initialWire, resizedWire)
        assertEquals(45, resizedRgb!!.size)
        assertEquals(45, resizedWire!!.size)
    }

    @Test
    fun stopAndBlackout_releasesOverrideAndSendsBlackoutBurst() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dev = makeDevice(id = "blackout-dev", ledCount = 4)
        val config = WledConfig(devices = listOf(dev))
        val engine = AmbientStreamingEngine(initialConfig = config, controllerClient = fakeClient, clock = clock)
        val coordinator = DiagnosticStreamCoordinator(
            initialConfig = config,
            controllerClient = fakeClient,
            engineProvider = { engine },
            clock = clock,
            ioDispatcher = dispatcher
        )

        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        coordinator.startRainbow(scope)
        runCurrent()
        assertTrue(engine.isDiagnosticActive)

        fakeClient.clear()
        coordinator.stopAndBlackout(sendPowerOff = false)

        assertFalse(coordinator.isRunning)
        assertFalse(engine.isDiagnosticActive)
        assertEquals(1, fakeClient.blackoutCalls)
        assertEquals(3, fakeClient.sentFrames.size)
        assertTrue(fakeClient.powerState.isEmpty())
        scope.cancel()
    }
}
