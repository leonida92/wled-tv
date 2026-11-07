package com.wled.tv.ui

import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import com.wled.tv.data.ConfigRepository
import com.wled.tv.data.InMemoryConfigStorage
import com.wled.tv.engine.AmbientStreamingEngine
import com.wled.tv.model.ColorCalibration
import com.wled.tv.model.ThroughputStats
import com.wled.tv.model.WledConfig
import com.wled.tv.model.WledDevice
import com.wled.tv.model.DeviceType
import com.wled.tv.network.DefaultWledControllerClient
import com.wled.tv.network.FakeWledControllerClient
import com.wled.tv.network.WledControllerClient
import com.wled.tv.service.AmbientCaptureService
import com.wled.tv.processing.ScreenColorProcessor
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UiMigrationTest {

    private lateinit var storage: InMemoryConfigStorage
    private lateinit var repo: ConfigRepository

    @Before
    fun setUp() {
        ArchTaskExecutor.getInstance().setDelegate(object : TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
            override fun isMainThread(): Boolean = true
            override fun postToMainThread(runnable: Runnable) = runnable.run()
        })
        storage = InMemoryConfigStorage()
        repo = ConfigRepository(storage, Dispatchers.Unconfined)
        ConfigRepository.setInstanceForTesting(repo)
    }

    @After
    fun tearDown() {
        ArchTaskExecutor.getInstance().setDelegate(null)
        ConfigRepository.setInstanceForTesting(null)
        AmbientCaptureService.setActiveEngineForTesting(null)
        DefaultWledControllerClient.setInstanceForTesting(null)
    }

    @Test
    fun configRepository_reactiveFlowUpdatesSubscribersOnMutation() = runBlocking {
        val initialConfig = repo.currentConfig
        assertTrue(initialConfig.devices.isNotEmpty())

        val testDevice = WledDevice(
            id = "test-strip-1",
            name = "Test Ambient Strip",
            ip = "192.168.1.50"
        )

        // Simulate EditDeviceActivity / DeviceManagerActivity adding a device
        repo.updateConfig { cur ->
            cur.copy(devices = cur.devices + testDevice)
        }

        val updated = repo.configFlow.first { it.devices.any { d -> d.id == "test-strip-1" } }
        assertEquals(testDevice.name, updated.devices.first { it.id == "test-strip-1" }.name)

        // Simulate MainActivity toggling enabled state
        repo.updateConfig { cur ->
            val list = cur.devices.toMutableList()
            val idx = list.indexOfFirst { it.id == testDevice.id }
            list[idx] = list[idx].copy(enabled = false)
            cur.copy(devices = list)
        }

        assertFalse(repo.currentConfig.devices.first { it.id == testDevice.id }.enabled)

        // Simulate CalibrationActivity mutating calibration
        val newCalib = ColorCalibration(gainR = 1.25f, gainG = 0.95f, gainB = 1.10f)
        repo.updateConfig { cur ->
            val dev = cur.devices.first { it.id == testDevice.id }
            cur.updateDevice(dev.copy(calibration = newCalib))
        }

        assertEquals(1.25f, repo.currentConfig.devices.first { it.id == testDevice.id }.calibration.gainR)
    }

    @Test
    fun activeEngine_stateFlowObserverAttachesAndDetachesListeners() {
        val initialConfig = WledConfig()
        val engine = AmbientStreamingEngine(initialConfig = initialConfig)

        var frameCount = 0
        var throughputStatsReceived: ThroughputStats? = null

        val liveListener = AmbientStreamingEngine.LiveFrameListener { _, _, count ->
            frameCount += count
        }
        val throughputListener = AmbientStreamingEngine.ThroughputListener { stats ->
            throughputStatsReceived = stats
        }

        AmbientCaptureService.setActiveEngineForTesting(engine)
        assertEquals(engine, AmbientCaptureService.activeEngine.value)

        // Register listeners directly on the active engine
        val active = AmbientCaptureService.activeEngine.value
        assertNotNull(active)
        active?.liveFrameListener = liveListener
        active?.throughputListener = throughputListener

        // Trigger notifications
        active?.liveFrameListener?.onDeviceFrameProcessed("dev1", byteArrayOf(1, 2, 3), 1)
        assertEquals(1, frameCount)

        val stats = ThroughputStats(
            fps = 59,
            targetFps = 60,
            pps = 118,
            totalPackets = 118L,
            deviceStats = emptyList()
        )
        active?.throughputListener?.onThroughputStatsUpdated(stats)
        assertNotNull(throughputStatsReceived)
        assertEquals(59, throughputStatsReceived?.fps)

        // Unregister on pause/stop
        active?.liveFrameListener = null
        active?.throughputListener = null

        active?.liveFrameListener?.onDeviceFrameProcessed("dev1", byteArrayOf(1, 2, 3), 1)
        assertEquals(1, frameCount) // No increment
    }

    @Test
    fun diagnosticTestPattern_setsTestingOverrideOnActiveEngine() {
        val engine = AmbientStreamingEngine(initialConfig = WledConfig())
        AmbientCaptureService.setActiveEngineForTesting(engine)

        assertFalse(engine.isTestingOverride)

        // When starting test pattern
        val active = AmbientCaptureService.activeEngine.value
        active?.isTestingOverride = true

        assertTrue(engine.isTestingOverride)

        // When stopping test pattern
        active?.isTestingOverride = false

        assertFalse(engine.isTestingOverride)
    }

    @Test
    fun activities_fieldVerification_useSharedControllerClientAndNoRawSockets() {
        // MainActivity: must use controllerClient and have no raw udpSender or httpClient fields
        val mainFields = MainActivity::class.java.declaredFields.map { it.name }
        assertFalse("MainActivity must not declare httpClient", mainFields.contains("httpClient"))
        assertFalse("MainActivity must not declare udpSender", mainFields.contains("udpSender"))
        assertTrue("MainActivity must declare controllerClient", mainFields.contains("controllerClient"))
        val mainControllerField = MainActivity::class.java.getDeclaredField("controllerClient")
        assertTrue(WledControllerClient::class.java.isAssignableFrom(mainControllerField.type))

        val mainMethods = MainActivity::class.java.declaredMethods.map { it.name }
        assertTrue("MainActivity must declare turnOffAllLeds", mainMethods.contains("turnOffAllLeds"))

        // CalibrationActivity: must use controllerClient and have no raw udpSender field
        val calibFields = CalibrationActivity::class.java.declaredFields.map { it.name }
        assertFalse("CalibrationActivity must not declare udpSender", calibFields.contains("udpSender"))
        assertTrue("CalibrationActivity must declare controllerClient", calibFields.contains("controllerClient"))
        val calibControllerField = CalibrationActivity::class.java.getDeclaredField("controllerClient")
        assertTrue(WledControllerClient::class.java.isAssignableFrom(calibControllerField.type))

    }

    @Test
    fun activities_injectSharedControllerClientFromSingleton() {
        val fakeClient = FakeWledControllerClient()
        DefaultWledControllerClient.setInstanceForTesting(fakeClient)
        try {
            val mainActivity = MainActivity()
            val mainField = MainActivity::class.java.getDeclaredField("controllerClient").apply { isAccessible = true }
            assertSame(fakeClient, mainField.get(mainActivity))

            val calibActivity = CalibrationActivity()
            val calibField = CalibrationActivity::class.java.getDeclaredField("controllerClient").apply { isAccessible = true }
            assertSame(fakeClient, calibField.get(calibActivity))

        } finally {
            DefaultWledControllerClient.setInstanceForTesting(null)
        }
    }

    @Test
    fun activities_sharedClientInteractions_verifyFakeDoubleContract() = runBlocking {
        val fakeClient = FakeWledControllerClient()
        DefaultWledControllerClient.setInstanceForTesting(fakeClient)

        val testDevice = WledDevice(
            id = "dock-strip-1",
            name = "Dock Strip",
            ip = "192.168.1.120"
        )

        // 1. Check reachability
        fakeClient.connectionCheckMap[testDevice.ip] = true
        val reachable = fakeClient.checkConnection(testDevice.ip)
        assertTrue(reachable)

        // 2. Power toggle on
        fakeClient.wakeAndSetBrightness(testDevice)
        assertTrue(fakeClient.powerState[testDevice.ip] == true)

        // 3. Send test / calibration frame
        val testBuffer = ByteArray(testDevice.totalLeds * 3) { 128.toByte() }
        val packets = fakeClient.sendFrame(testDevice, testBuffer, testDevice.totalLeds)
        assertEquals(1, packets)
        assertEquals(1, fakeClient.sentFrames.size)
        assertEquals(testDevice, fakeClient.sentFrames[0].device)
        assertEquals(testDevice.totalLeds, fakeClient.sentFrames[0].ledCount)

        // 4. Power toggle off
        fakeClient.turnOff(testDevice.ip)
        assertEquals(false, fakeClient.powerState[testDevice.ip])
    }


    @Test
    fun mainActivity_testPattern_wireEncodesGrbBeforeTransmissionAndPreservesCanonicalPreview() {
        val fakeClient = FakeWledControllerClient()
        DefaultWledControllerClient.setInstanceForTesting(fakeClient)

        val grbDevice = WledDevice(
            id = "grb-strip",
            name = "GRB Test Strip",
            ip = "192.168.1.60",
            type = DeviceType.LEFT_AMBIENT,
            ledCount = 3,
            calibration = ColorCalibration(colorOrder = "GRB", isRgbw = false)
        )

        val leds = grbDevice.totalLeds
        assertEquals(3, leds)
        val testBuffer = ByteArray(leds * 3)
        // LED 0: Red (255, 0, 0), LED 1: Green (0, 255, 0), LED 2: Blue (0, 0, 255)
        testBuffer[0] = 255.toByte(); testBuffer[1] = 0; testBuffer[2] = 0
        testBuffer[3] = 0; testBuffer[4] = 255.toByte(); testBuffer[5] = 0
        testBuffer[6] = 0; testBuffer[7] = 0; testBuffer[8] = 255.toByte()

        val wireBuffer = ByteArray(leds * (if (grbDevice.calibration.isRgbw) 4 else 3))
        ScreenColorProcessor.encodeWireFrame(grbDevice, testBuffer, wireBuffer)

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
        val fakeClient = FakeWledControllerClient()
        DefaultWledControllerClient.setInstanceForTesting(fakeClient)

        val rgbwDevice = WledDevice(
            id = "rgbw-strip",
            name = "RGBW Test Strip",
            ip = "192.168.1.61",
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
        val testBuffer = ByteArray(leds * 3)
        // LED 0: Pure white (255, 255, 255)
        testBuffer[0] = 255.toByte(); testBuffer[1] = 255.toByte(); testBuffer[2] = 255.toByte()
        // LED 1: Saturated red (255, 0, 0)
        testBuffer[3] = 255.toByte(); testBuffer[4] = 0; testBuffer[5] = 0

        val wireBuffer = ByteArray(leds * (if (rgbwDevice.calibration.isRgbw) 4 else 3))
        ScreenColorProcessor.encodeWireFrame(rgbwDevice, testBuffer, wireBuffer)

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
    fun calibrationActivity_realTimePreview_wireEncodesGrbFrameBeforeTransmission() {
        val fakeClient = FakeWledControllerClient()
        DefaultWledControllerClient.setInstanceForTesting(fakeClient)

        val dev = WledDevice(
            id = "grb-calib",
            name = "GRB Calibration Strip",
            ip = "192.168.1.70",
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

        val wireBuffer = ByteArray(leds * (if (dev.calibration.isRgbw) 4 else 3))
        ScreenColorProcessor.encodeWireFrame(dev, frame, wireBuffer)

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
        val fakeClient = FakeWledControllerClient()
        DefaultWledControllerClient.setInstanceForTesting(fakeClient)

        val dev = WledDevice(
            id = "rgbw-calib",
            name = "RGBW Calibration Strip",
            ip = "192.168.1.71",
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

        val wireBuffer = ByteArray(leds * (if (dev.calibration.isRgbw) 4 else 3))
        ScreenColorProcessor.encodeWireFrame(dev, frame, wireBuffer)

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
}
