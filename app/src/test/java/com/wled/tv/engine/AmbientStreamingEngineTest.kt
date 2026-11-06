package com.wled.tv.engine

import com.wled.tv.model.ColorCalibration
import com.wled.tv.model.DeviceType
import com.wled.tv.model.StreamingProtocol
import com.wled.tv.model.ThroughputHistoryPoint
import com.wled.tv.model.ThroughputStats
import com.wled.tv.model.WledConfig
import com.wled.tv.model.WledDevice
import com.wled.tv.network.WledHttpClient
import com.wled.tv.network.WledUdpSender
import com.wled.tv.processing.ScreenColorProcessor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer

class AmbientStreamingEngineTest {

    private lateinit var clock: TestClock
    private lateinit var fakeUdpSender: FakeUdpSender
    private lateinit var fakeHttpClient: FakeHttpClient
    private lateinit var colorProcessor: ScreenColorProcessor
    private var isAudioActiveFlag = false

    private class FakeUdpSender : WledUdpSender() {
        var sendFrameCallCount = 0
        val sentFrames = mutableListOf<SentFrame>()

        data class SentFrame(
            val ip: String,
            val port: Int,
            val protocol: StreamingProtocol,
            val rgbCopy: ByteArray,
            val ledCount: Int
        )

        override fun sendFrame(
            ip: String,
            port: Int,
            protocol: StreamingProtocol,
            rgb: ByteArray,
            ledCount: Int,
            colorOrder: String,
            isRgbw: Boolean,
            rgbwMode: String,
            timeoutSeconds: Byte
        ): Int {
            sendFrameCallCount++
            sentFrames.add(SentFrame(ip, port, protocol, rgb.copyOf(ledCount * 3), ledCount))
            return 1
        }
    }

    private class FakeHttpClient : WledHttpClient() {
        var turnOffCallCount = 0
        val turnedOffIps = mutableListOf<String>()

        override suspend fun turnOff(ip: String): Boolean {
            turnOffCallCount++
            turnedOffIps.add(ip)
            return true
        }
    }

    @Before
    fun setUp() {
        clock = TestClock(currentTime = 1000L, uptime = 1000L)
        fakeUdpSender = FakeUdpSender()
        fakeHttpClient = FakeHttpClient()
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
            udpSender = fakeUdpSender,
            httpClient = fakeHttpClient,
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
            udpSender = fakeUdpSender,
            httpClient = fakeHttpClient,
            clock = clock,
            isAudioActive = { isAudioActiveFlag }
        )

        val initialRaw = engine.getRawBuffer("dev1")
        val initialDevice = engine.getDeviceBuffer("dev1")
        assertNotNull("Raw buffer should be pre-allocated", initialRaw)
        assertNotNull("Device buffer should be pre-allocated", initialDevice)
        assertEquals(90, initialRaw?.size)
        assertEquals(90, initialDevice?.size)

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

        // Exact object references must match: zero allocations in hot path
        assertSame("Raw buffer instance must not be re-allocated in steady state", initialRaw, afterRaw)
        assertSame("Device buffer instance must not be re-allocated in steady state", initialDevice, afterDevice)
    }

    @Test
    fun testBufferResizingOnlyOccursWhenLedCountsChange() {
        val config = createDefaultConfig(ledCount = 30)
        val engine = AmbientStreamingEngine(
            initialConfig = config,
            colorProcessor = colorProcessor,
            udpSender = fakeUdpSender,
            httpClient = fakeHttpClient,
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
            udpSender = fakeUdpSender,
            httpClient = fakeHttpClient,
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
        fakeUdpSender.sendFrameCallCount = 0
        engine.tickStreaming()
        assertEquals("First DRM tick should send a keepalive packet", 1, fakeUdpSender.sendFrameCallCount)

        // Ticks within next 1500ms should be suppressed
        clock.advance(500L)
        engine.tickStreaming()
        assertEquals("Subsequent ticks within 1500ms should be suppressed", 1, fakeUdpSender.sendFrameCallCount)

        clock.advance(900L) // 1400ms since last keepalive
        engine.tickStreaming()
        assertEquals("Subsequent ticks within 1500ms should be suppressed", 1, fakeUdpSender.sendFrameCallCount)

        // At 1500ms, another keepalive is transmitted
        clock.advance(100L) // 1500ms since last keepalive
        engine.tickStreaming()
        assertEquals("Keepalive packet sent when 1500ms elapsed", 2, fakeUdpSender.sendFrameCallCount)

        // Immediate recovery when active video resumes
        clock.advance(16L)
        engine.onFrameAvailable(activeFrame, width, height, rowStride, pixelStride)
        assertFalse("DRM should immediately clear when active video resumes", engine.isDrmActive)

        // Normal 60Hz streaming resumes without 1500ms throttling
        clock.advance(16L)
        engine.tickStreaming()
        assertEquals("Streaming should immediately resume at 60Hz", 3, fakeUdpSender.sendFrameCallCount)
    }

    @Test
    fun testWidevineDrmDoesNotTriggerWithoutAudio() {
        isAudioActiveFlag = false
        val config = createDefaultConfig()
        val engine = AmbientStreamingEngine(
            initialConfig = config,
            colorProcessor = colorProcessor,
            udpSender = fakeUdpSender,
            httpClient = fakeHttpClient,
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
            udpSender = fakeUdpSender,
            httpClient = fakeHttpClient,
            clock = clock
        )

        // Populate buffers with non-zero colors
        val frame = createSolidColorFrame(r = 255, g = 255, b = 255)
        engine.onFrameAvailable(frame, 320, 180, 320 * 4, 4)
        engine.tickStreaming()

        fakeUdpSender.sendFrameCallCount = 0
        fakeHttpClient.turnOffCallCount = 0

        // Execute blackout with sendPowerOff = true
        engine.blackout(sendPowerOff = true)

        // 3 black frame burst packets sent
        assertEquals(3, fakeUdpSender.sendFrameCallCount)
        assertEquals(1, fakeHttpClient.turnOffCallCount)
        assertEquals("192.168.1.50", fakeHttpClient.turnedOffIps[0])

        // Buffers should be zeroed
        val devBuf = engine.getDeviceBuffer("dev1")
        assertNotNull(devBuf)
        assertTrue(devBuf!!.all { it == 0.toByte() })

        // Blackout with sendPowerOff = false should not call HTTP turnOff
        fakeUdpSender.sendFrameCallCount = 0
        fakeHttpClient.turnOffCallCount = 0
        engine.blackout(sendPowerOff = false)
        assertEquals(3, fakeUdpSender.sendFrameCallCount)
        assertEquals(0, fakeHttpClient.turnOffCallCount)
    }

    @Test
    fun testTestingOverride_suppressesVideoExtractionAndTransmission() {
        val config = createDefaultConfig(ledCount = 10)
        val engine = AmbientStreamingEngine(
            initialConfig = config,
            colorProcessor = colorProcessor,
            udpSender = fakeUdpSender,
            httpClient = fakeHttpClient,
            clock = clock
        )

        engine.isTestingOverride = true

        val frame = createSolidColorFrame(r = 200, g = 100, b = 50)
        val accepted = engine.onFrameAvailable(frame, 320, 180, 320 * 4, 4)
        assertFalse("Testing override should suppress frame intake", accepted)

        fakeUdpSender.sendFrameCallCount = 0
        engine.tickStreaming()
        assertEquals("Testing override should suppress UDP transmission", 0, fakeUdpSender.sendFrameCallCount)

        engine.isTestingOverride = false
        clock.advance(20L)
        val acceptedNow = engine.onFrameAvailable(frame, 320, 180, 320 * 4, 4)
        assertTrue("Disabling testing override restores frame intake", acceptedNow)

        clock.advance(16L)
        engine.tickStreaming()
        assertEquals("Disabling testing override restores UDP transmission", 1, fakeUdpSender.sendFrameCallCount)
    }

    @Test
    fun testThroughputStatsCalculationAndHistoryRingBuffer() {
        val config = createDefaultConfig(ledCount = 10, fps = 60)
        val engine = AmbientStreamingEngine(
            initialConfig = config,
            colorProcessor = colorProcessor,
            udpSender = fakeUdpSender,
            httpClient = fakeHttpClient,
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
            udpSender = fakeUdpSender,
            httpClient = fakeHttpClient,
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
}
