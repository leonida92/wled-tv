package com.wled.tv.engine

import com.wled.tv.model.ColorCalibration
import com.wled.tv.model.DeviceType
import com.wled.tv.model.StreamingProtocol
import com.wled.tv.model.ThroughputHistoryPoint
import com.wled.tv.model.ThroughputStats
import com.wled.tv.model.WledConfig
import com.wled.tv.model.WledDevice
import com.wled.tv.network.FakeWledControllerClient
import com.wled.tv.processing.ScreenColorProcessor
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

class AmbientStreamingEngineTest {

    private lateinit var clock: TestClock
    private lateinit var fakeControllerClient: FakeWledControllerClient
    private lateinit var colorProcessor: ScreenColorProcessor
    private var isAudioActiveFlag = false

    @Before
    fun setUp() {
        clock = TestClock(currentTime = 1000L, uptime = 1000L)
        fakeControllerClient = FakeWledControllerClient()
        colorProcessor = ScreenColorProcessor()
        isAudioActiveFlag = false
    }

    private fun createDefaultConfig(ledCount: Int = 30, fps: Int = 60): WledConfig {
        val device = WledDevice(
            id = "dev1",
            name = "Main TV",
            ip = "192.168.1.50",
            port = 4048,
            protocol = StreamingProtocol.DDP,
            enabled = true,
            type = DeviceType.FULL_SCREEN,
            ledCount = ledCount,
            calibration = ColorCalibration(fps = fps)
        )
        return WledConfig(
            devices = listOf(device),
            calibration = ColorCalibration(fps = fps)
        )
    }

    private fun createSolidColorFrame(width: Int = 320, height: Int = 180, r: Int = 0, g: Int = 0, b: Int = 0): ByteBuffer {
        val rowStride = width * 4
        val buffer = ByteBuffer.allocateDirect(rowStride * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val offset = y * rowStride + x * 4
                buffer.put(offset, r.toByte())
                buffer.put(offset + 1, g.toByte())
                buffer.put(offset + 2, b.toByte())
                buffer.put(offset + 3, 255.toByte())
            }
        }
        return buffer
    }

    @Test
    fun testFrameIntakeAndCadenceRegulation() {
        val config = createDefaultConfig(fps = 60)
        val engine = AmbientStreamingEngine(
            initialConfig = config,
            colorProcessor = colorProcessor,
            controllerClient = fakeControllerClient,
            clock = clock,
            isAudioActive = { isAudioActiveFlag }
        )

        val frame = createSolidColorFrame(r = 100, g = 100, b = 100)
        val width = 320
        val height = 180
        val rowStride = width * 4
        val pixelStride = 4

        // First frame at t=1000ms should be accepted
        val frame1Accepted = engine.onFrameAvailable(frame, width, height, rowStride, pixelStride)
        assertTrue("First frame should be accepted", frame1Accepted)

        // Frame arriving too quickly at t=1008ms (8ms < 13ms min interval for 60fps) should be dropped
        clock.advance(8L)
        val frame2Accepted = engine.onFrameAvailable(frame, width, height, rowStride, pixelStride)
        assertFalse("Frame arriving before cadence interval should be dropped", frame2Accepted)

        // Frame arriving at t=1015ms (15ms elapsed from frame1 >= 13ms) should be accepted
        clock.advance(7L) // total 15ms
        val frame3Accepted = engine.onFrameAvailable(frame, width, height, rowStride, pixelStride)
        assertTrue("Frame arriving after cadence interval should be accepted", frame3Accepted)

        // tickStreaming returns regulated delayMs
        val delay = engine.tickStreaming()
        assertTrue("Delay should be positive and <= 16ms", delay in 1..16)
    }

    @Test
    fun testZeroBufferAllocationsInSteadyStreamingLoop() {
        val config = createDefaultConfig(ledCount = 30)
        val engine = AmbientStreamingEngine(
            initialConfig = config,
            colorProcessor = colorProcessor,
            controllerClient = fakeControllerClient,
            clock = clock,
            isAudioActive = { isAudioActiveFlag }
        )

        val initialRaw = engine.getRawBuffer("dev1")
        val initialDevice = engine.getDeviceBuffer("dev1")
        val initialWire = engine.getWireBuffer("dev1")
        assertNotNull("Raw buffer should be pre-allocated", initialRaw)
        assertNotNull("Device buffer should be pre-allocated", initialDevice)
        assertNotNull("Wire buffer should be pre-allocated", initialWire)
        assertEquals(90, initialRaw?.size)
        assertEquals(90, initialDevice?.size)
        assertEquals(90, initialWire?.size)

        val frame = createSolidColorFrame(r = 120, g = 80, b = 40)
        val width = 320
        val height = 180
        val rowStride = width * 4
        val pixelStride = 4

        // Process 100 steady-state frames and ticks
        for (i in 1..100) {
            clock.advance(16L)
            engine.onFrameAvailable(frame, width, height, rowStride, pixelStride)
            engine.tickStreaming()
        }

        val afterRaw = engine.getRawBuffer("dev1")
        val afterDevice = engine.getDeviceBuffer("dev1")
        val afterWire = engine.getWireBuffer("dev1")

        // Exact object references must match: zero allocations in hot path
        assertSame("Raw buffer instance must not be re-allocated in steady state", initialRaw, afterRaw)
        assertSame("Device buffer instance must not be re-allocated in steady state", initialDevice, afterDevice)
        assertSame("Wire buffer instance must not be re-allocated in steady state", initialWire, afterWire)
    }

    @Test
    fun testBufferResizingOnlyOccursWhenLedCountsChange() {
        val config = createDefaultConfig(ledCount = 30)
        val engine = AmbientStreamingEngine(
            initialConfig = config,
            colorProcessor = colorProcessor,
            controllerClient = fakeControllerClient,
            clock = clock
        )

        val buf30 = engine.getRawBuffer("dev1")
        assertEquals(90, buf30?.size)

        // Update config with SAME LED count (e.g. changing brightness)
        val configSame = config.copy(
            calibration = config.calibration.copy(maxBrightness = 150)
        )
        engine.updateConfig(configSame)
        assertSame("Buffer must not resize if LED count is unchanged", buf30, engine.getRawBuffer("dev1"))

        // Update config with CHANGED LED count (30 -> 60 LEDs)
        val device60 = config.devices[0].copy(ledCount = 60)
        val config60 = config.copy(devices = listOf(device60))
        engine.updateConfig(config60)

        val buf60 = engine.getRawBuffer("dev1")
        assertNotSame("Buffer must be re-allocated when LED count changes", buf30, buf60)
        assertEquals(180, buf60?.size)
    }

    @Test
    fun testWidevineDrmDetection_triggerThrottlingAndRecovery() {
        isAudioActiveFlag = true
        val config = createDefaultConfig(ledCount = 10)
        val engine = AmbientStreamingEngine(
            initialConfig = config,
            colorProcessor = colorProcessor,
            controllerClient = fakeControllerClient,
            clock = clock,
            isAudioActive = { isAudioActiveFlag }
        )

        val blackFrame = createSolidColorFrame(r = 0, g = 0, b = 0)
        val activeFrame = createSolidColorFrame(r = 200, g = 100, b = 50)
        val width = 320
        val height = 180
        val rowStride = width * 4
        val pixelStride = 4

        // Feed black frames at t=1000ms
        engine.onFrameAvailable(blackFrame, width, height, rowStride, pixelStride)
        assertFalse("DRM should not be active initially", engine.isDrmActive)

        // Advance to 4.9 seconds of black frames
        clock.advance(4900L)
        engine.onFrameAvailable(blackFrame, width, height, rowStride, pixelStride)
        assertFalse("DRM should not trigger before 5 full seconds", engine.isDrmActive)

        // Advance past 5 seconds (5000ms from start)
        clock.advance(100L)
        engine.onFrameAvailable(blackFrame, width, height, rowStride, pixelStride)
        assertTrue("DRM blackout should trigger after 5 seconds of black frames with active audio", engine.isDrmActive)

        // Verify keepalive throttling: first tick in DRM sends keepalive frame
        fakeControllerClient.clear()
        engine.tickStreaming()
        assertEquals("First DRM tick should send a keepalive packet", 1, fakeControllerClient.sentFrames.size)

        // Ticks within next 1500ms should be suppressed
        clock.advance(500L)
        engine.tickStreaming()
        assertEquals("Subsequent ticks within 1500ms should be suppressed", 1, fakeControllerClient.sentFrames.size)

        clock.advance(900L) // 1400ms since last keepalive
        engine.tickStreaming()
        assertEquals("Subsequent ticks within 1500ms should be suppressed", 1, fakeControllerClient.sentFrames.size)

        // At 1500ms, another keepalive is transmitted
        clock.advance(100L) // 1500ms since last keepalive
        engine.tickStreaming()
        assertEquals("Keepalive packet sent when 1500ms elapsed", 2, fakeControllerClient.sentFrames.size)

        // Immediate recovery when active video resumes
        clock.advance(16L)
        engine.onFrameAvailable(activeFrame, width, height, rowStride, pixelStride)
        assertFalse("DRM should immediately clear when active video resumes", engine.isDrmActive)

        // Normal 60Hz streaming resumes without 1500ms throttling
        clock.advance(16L)
        engine.tickStreaming()
        assertEquals("Streaming should immediately resume at 60Hz", 3, fakeControllerClient.sentFrames.size)
    }

    @Test
    fun testWidevineDrmDoesNotTriggerWithoutAudio() {
        isAudioActiveFlag = false
        val config = createDefaultConfig()
        val engine = AmbientStreamingEngine(
            initialConfig = config,
            colorProcessor = colorProcessor,
            controllerClient = fakeControllerClient,
            clock = clock,
            isAudioActive = { isAudioActiveFlag }
        )

        val blackFrame = createSolidColorFrame(r = 0, g = 0, b = 0)
        val width = 320
        val height = 180

        engine.onFrameAvailable(blackFrame, width, height, width * 4, 4)
        clock.advance(6000L)
        engine.onFrameAvailable(blackFrame, width, height, width * 4, 4)

        assertFalse("DRM should NOT trigger if audio is inactive", engine.isDrmActive)
    }

    @Test
    fun testBlackoutRoutine_transmitsBurstAndResetsFilters() = runTest {
        val config = createDefaultConfig(ledCount = 10)
        val engine = AmbientStreamingEngine(
            initialConfig = config,
            colorProcessor = colorProcessor,
            controllerClient = fakeControllerClient,
            clock = clock
        )

        // Populate buffers with non-zero colors
        val frame = createSolidColorFrame(r = 255, g = 255, b = 255)
        engine.onFrameAvailable(frame, 320, 180, 320 * 4, 4)
        engine.tickStreaming()

        fakeControllerClient.clear()

        // Execute blackout with sendPowerOff = true
        engine.blackout(sendPowerOff = true)

        assertEquals(1, fakeControllerClient.blackoutCalls)
        // 3 black frame burst packets sent
        assertEquals(3, fakeControllerClient.sentFrames.size)
        assertEquals(false, fakeControllerClient.powerState["192.168.1.50"])
        assertEquals(listOf(config.enabledDevices), fakeControllerClient.blackoutDevices)

        // Buffers should be zeroed
        val devBuf = engine.getDeviceBuffer("dev1")
        assertNotNull(devBuf)
        assertTrue(devBuf!!.all { it == 0.toByte() })

        // Blackout with sendPowerOff = false should not call HTTP turnOff
        fakeControllerClient.clear()
        engine.blackout(sendPowerOff = false)
        assertEquals(1, fakeControllerClient.blackoutCalls)
        assertEquals(3, fakeControllerClient.sentFrames.size)
        assertNull(fakeControllerClient.powerState["192.168.1.50"])
    }

    @Test
    fun testTestingOverride_suppressesVideoExtractionAndTransmission() {
        val config = createDefaultConfig(ledCount = 10)
        val engine = AmbientStreamingEngine(
            initialConfig = config,
            colorProcessor = colorProcessor,
            controllerClient = fakeControllerClient,
            clock = clock
        )

        engine.isTestingOverride = true

        val frame = createSolidColorFrame(r = 200, g = 100, b = 50)
        val accepted = engine.onFrameAvailable(frame, 320, 180, 320 * 4, 4)
        assertFalse("Testing override should suppress frame intake", accepted)

        fakeControllerClient.clear()
        engine.tickStreaming()
        assertEquals("Testing override should suppress UDP transmission", 0, fakeControllerClient.sentFrames.size)

        engine.isTestingOverride = false
        clock.advance(20L)
        val acceptedNow = engine.onFrameAvailable(frame, 320, 180, 320 * 4, 4)
        assertTrue("Disabling testing override restores frame intake", acceptedNow)

        clock.advance(16L)
        engine.tickStreaming()
        assertEquals("Disabling testing override restores UDP transmission", 1, fakeControllerClient.sentFrames.size)
    }

    @Test
    fun testThroughputStatsCalculationAndHistoryRingBuffer() {
        val config = createDefaultConfig(ledCount = 10, fps = 60)
        val engine = AmbientStreamingEngine(
            initialConfig = config,
            colorProcessor = colorProcessor,
            controllerClient = fakeControllerClient,
            clock = clock
        )

        var emittedStats: ThroughputStats? = null
        engine.throughputListener = AmbientStreamingEngine.ThroughputListener { stats ->
            emittedStats = stats
        }

        val frame = createSolidColorFrame(r = 150, g = 150, b = 150)
        val width = 320
        val height = 180

        // Initialize stats baseline
        engine.tickStreaming()

        // Stream 60 frames and ticks over 1 second (1000ms)
        for (i in 1..60) {
            clock.advance(16L)
            engine.onFrameAvailable(frame, width, height, width * 4, 4)
            engine.tickStreaming()
        }
        // Advance remaining to make exactly 1000ms
        clock.advance(40L)
        engine.tickStreaming()

        assertNotNull("Throughput stats should be emitted after 1 second", emittedStats)
        val stats = engine.latestStats
        assertNotNull(stats)
        assertTrue("FPS should be around 60 (calculated ${stats?.fps})", (stats?.fps ?: 0) >= 55)
        assertTrue("PPS should be around 60 (calculated ${stats?.pps})", (stats?.pps ?: 0) >= 55)
        assertEquals(60, stats?.targetFps)
        assertEquals(1, stats?.deviceStats?.size)
        assertEquals("dev1", stats?.deviceStats?.get(0)?.deviceId)

        // Check history buffer
        val history = engine.getHistorySnapshot()
        assertEquals(1, history.size)
        assertEquals(clock.currentTimeMillis(), history[0].timestamp)
        assertEquals(stats?.fps, history[0].fps)
        assertEquals(stats?.pps, history[0].pps)

        // Test ring buffer eviction: add up to MAX_HISTORY_SECONDS + 5 entries
        engine.clearHistory()
        val now = clock.currentTimeMillis()
        val totalPoints = AmbientStreamingEngine.MAX_HISTORY_SECONDS + 5
        for (i in 1..totalPoints) {
            val ts = now - (totalPoints - i) * 1000L
            engine.recordHistoryPoint(ThroughputHistoryPoint(timestamp = ts, fps = 60, pps = 60))
        }

        val fullHistory = engine.getHistorySnapshot(windowSeconds = AmbientStreamingEngine.MAX_HISTORY_SECONDS)
        assertEquals(
            "Ring buffer should cap strictly at MAX_HISTORY_SECONDS (10800)",
            AmbientStreamingEngine.MAX_HISTORY_SECONDS,
            fullHistory.size
        )

        // Test window filtering (last 10 seconds)
        val recentSnapshot = engine.getHistorySnapshot(windowSeconds = 10)
        assertEquals(11, recentSnapshot.size)

        // Test clear
        engine.clearHistory()
        assertEquals(0, engine.getHistorySnapshot().size)
    }

    @Test
    fun testGetOverlayDataAndSnapshot() {
        val dev = WledDevice(
            id = "overlay_dev_1",
            name = "TV Perimeter",
            ip = "192.168.1.150",
            type = DeviceType.LEFT_AMBIENT,
            ledCount = 10,
            enabled = true
        )
        val config = WledConfig(devices = listOf(dev))
        val engine = AmbientStreamingEngine(
            initialConfig = config,
            colorProcessor = colorProcessor,
            controllerClient = fakeControllerClient,
            clock = clock,
            isAudioActive = { isAudioActiveFlag }
        )

        val overlayData = engine.getOverlayData(dev)
        assertNotNull(overlayData)
        assertEquals("overlay_dev_1", overlayData?.deviceId)
        assertEquals("TV Perimeter", overlayData?.deviceName)
        assertEquals(10, overlayData?.ledCount)
        assertEquals(30, overlayData?.rgb?.size)

        val fullSnapshot = engine.getOverlaySnapshot()
        assertEquals(1, fullSnapshot.devices.size)
        assertEquals("overlay_dev_1", fullSnapshot.devices[0].deviceId)
        assertEquals(0f, fullSnapshot.topCrop)
        assertFalse(fullSnapshot.isDrmActive)
    }

    @Test
    fun testClose_delegatesToControllerClient() {
        val config = createDefaultConfig()
        val engine = AmbientStreamingEngine(
            initialConfig = config,
            colorProcessor = colorProcessor,
            controllerClient = fakeControllerClient,
            clock = clock
        )
        assertFalse(fakeControllerClient.isClosed)
        engine.close()
        assertTrue(fakeControllerClient.isClosed)
    }

    @Test
    fun testThreeStagePipeline_colorOrderGrb_transmitsGrbWhilePreviewAndOverlayStayRgb() {
        val device = WledDevice(
            id = "dev_grb",
            name = "GRB Strip",
            ip = "192.168.1.60",
            port = 4048,
            protocol = StreamingProtocol.DDP,
            enabled = true,
            type = DeviceType.FULL_SCREEN,
            ledCount = 2,
            calibration = ColorCalibration(
                colorOrder = "GRB",
                smoothingFactor = 1.0f
            )
        )
        val config = WledConfig(devices = listOf(device))
        val engine = AmbientStreamingEngine(
            initialConfig = config,
            colorProcessor = colorProcessor,
            controllerClient = fakeControllerClient,
            clock = clock
        )

        var lastPreviewRgb: ByteArray? = null
        var lastPreviewCount = 0
        engine.liveFrameListener = AmbientStreamingEngine.LiveFrameListener { deviceId, rgb, count ->
            if (deviceId == "dev_grb") {
                lastPreviewRgb = rgb
                lastPreviewCount = count
            }
        }

        val rawBuf = engine.getRawBuffer("dev_grb")
        assertNotNull(rawBuf)
        for (i in 0 until 2) {
            rawBuf!![i * 3 + 0] = 200.toByte() // R
            rawBuf[i * 3 + 1] = 100.toByte() // G
            rawBuf[i * 3 + 2] = 50.toByte()  // B
        }

        clock.advance(50L)
        engine.tickStreaming()

        // 1. Controller receives [G, R, B] wire payload
        assertEquals(1, fakeControllerClient.sentFrames.size)
        val sent = fakeControllerClient.sentFrames[0]
        assertEquals(2, sent.ledCount)
        assertEquals(6, sent.wirePayload.size)
        // LED 0
        assertEquals(100.toByte(), sent.wirePayload[0]) // G
        assertEquals(200.toByte(), sent.wirePayload[1]) // R
        assertEquals(50.toByte(), sent.wirePayload[2])  // B
        // LED 1
        assertEquals(100.toByte(), sent.wirePayload[3]) // G
        assertEquals(200.toByte(), sent.wirePayload[4]) // R
        assertEquals(50.toByte(), sent.wirePayload[5])  // B

        // 2. liveFrameListener receives canonical [R, G, B]
        assertNotNull(lastPreviewRgb)
        assertEquals(2, lastPreviewCount)
        assertEquals(6, lastPreviewRgb!!.size)
        assertEquals(200.toByte(), lastPreviewRgb!![0]) // R
        assertEquals(100.toByte(), lastPreviewRgb!![1]) // G
        assertEquals(50.toByte(), lastPreviewRgb!![2])  // B
        assertEquals(200.toByte(), lastPreviewRgb!![3]) // R
        assertEquals(100.toByte(), lastPreviewRgb!![4]) // G
        assertEquals(50.toByte(), lastPreviewRgb!![5])  // B

        // 3. getOverlayData() returns canonical [R, G, B]
        val overlay = engine.getOverlayData(device)
        assertNotNull(overlay)
        assertEquals(2, overlay!!.ledCount)
        assertEquals(6, overlay.rgb.size)
        assertEquals(200.toByte(), overlay.rgb[0]) // R
        assertEquals(100.toByte(), overlay.rgb[1]) // G
        assertEquals(50.toByte(), overlay.rgb[2])  // B
        assertEquals(200.toByte(), overlay.rgb[3]) // R
        assertEquals(100.toByte(), overlay.rgb[4]) // G
        assertEquals(50.toByte(), overlay.rgb[5])  // B
    }

    @Test
    fun testThreeStagePipeline_rgbwAutoSubtract_transmits4BytesWhilePreviewStays3BytesRgb() {
        val device = WledDevice(
            id = "dev_rgbw",
            name = "RGBW Strip",
            ip = "192.168.1.61",
            port = 4048,
            protocol = StreamingProtocol.DDP,
            enabled = true,
            type = DeviceType.FULL_SCREEN,
            ledCount = 1,
            calibration = ColorCalibration(
                colorOrder = "RGB",
                isRgbw = true,
                rgbwMode = "AUTO_SUBTRACT",
                smoothingFactor = 1.0f
            )
        )
        val config = WledConfig(devices = listOf(device))
        val engine = AmbientStreamingEngine(
            initialConfig = config,
            colorProcessor = colorProcessor,
            controllerClient = fakeControllerClient,
            clock = clock
        )

        var lastPreviewRgb: ByteArray? = null
        engine.liveFrameListener = AmbientStreamingEngine.LiveFrameListener { deviceId, rgb, _ ->
            if (deviceId == "dev_rgbw") {
                lastPreviewRgb = rgb
            }
        }

        // Target: R=200, G=150, B=100. Min(R,G,B) = 100 -> W=100.
        // AUTO_SUBTRACT -> R'=100, G'=50, B'=0, W=100
        val rawBuf = engine.getRawBuffer("dev_rgbw")
        assertNotNull(rawBuf)
        rawBuf!![0] = 200.toByte()
        rawBuf[1] = 150.toByte()
        rawBuf[2] = 100.toByte()

        clock.advance(50L)
        engine.tickStreaming()

        // 1. Controller receives 4 bytes: [R', G', B', W] = [100, 50, 0, 100]
        assertEquals(1, fakeControllerClient.sentFrames.size)
        val sent = fakeControllerClient.sentFrames[0]
        assertEquals(1, sent.ledCount)
        assertEquals(4, sent.wirePayload.size)
        assertEquals(100.toByte(), sent.wirePayload[0]) // R'
        assertEquals(50.toByte(), sent.wirePayload[1])  // G'
        assertEquals(0.toByte(), sent.wirePayload[2])   // B'
        assertEquals(100.toByte(), sent.wirePayload[3]) // W

        // 2. liveFrameListener receives 3 bytes: canonical [200, 150, 100]
        assertNotNull(lastPreviewRgb)
        assertEquals(3, lastPreviewRgb!!.size)
        assertEquals(200.toByte(), lastPreviewRgb!![0])
        assertEquals(150.toByte(), lastPreviewRgb!![1])
        assertEquals(100.toByte(), lastPreviewRgb!![2])

        // 3. getOverlayData() returns 3 bytes: canonical [200, 150, 100]
        val overlay = engine.getOverlayData(device)
        assertNotNull(overlay)
        assertEquals(1, overlay!!.ledCount)
        assertEquals(3, overlay.rgb.size)
        assertEquals(200.toByte(), overlay.rgb[0])
        assertEquals(150.toByte(), overlay.rgb[1])
        assertEquals(100.toByte(), overlay.rgb[2])
    }

    @Test
    fun testBufferResizing_adjustsWireBuffersWhenSwitchingBetweenRgbAndRgbw() {
        val devRgb = WledDevice(
            id = "dev_toggle",
            name = "Toggle Strip",
            ip = "192.168.1.62",
            type = DeviceType.FULL_SCREEN,
            enabled = true,
            ledCount = 10,
            calibration = ColorCalibration(isRgbw = false)
        )
        val configRgb = WledConfig(devices = listOf(devRgb))
        val engine = AmbientStreamingEngine(
            initialConfig = configRgb,
            colorProcessor = colorProcessor,
            controllerClient = fakeControllerClient,
            clock = clock
        )

        assertEquals(30, engine.getRawBuffer("dev_toggle")?.size)
        assertEquals(30, engine.getDeviceBuffer("dev_toggle")?.size)
        assertEquals(30, engine.getWireBuffer("dev_toggle")?.size)

        // Switch to RGBW (10 LEDs * 4 bytes = 40 bytes wire buffer)
        val devRgbw = devRgb.copy(calibration = ColorCalibration(isRgbw = true))
        val configRgbw = WledConfig(devices = listOf(devRgbw))
        engine.updateConfig(configRgbw)

        // Canonical buffers remain 30 (10 * 3)
        assertEquals(30, engine.getRawBuffer("dev_toggle")?.size)
        assertEquals(30, engine.getDeviceBuffer("dev_toggle")?.size)
        // Wire buffer resized to 40 (10 * 4)
        assertEquals(40, engine.getWireBuffer("dev_toggle")?.size)

        // Switch back to RGB
        engine.updateConfig(configRgb)
        assertEquals(30, engine.getWireBuffer("dev_toggle")?.size)
    }
}
