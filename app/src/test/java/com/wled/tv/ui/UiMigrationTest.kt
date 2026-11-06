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
import com.wled.tv.network.DefaultWledControllerClient
import com.wled.tv.network.FakeWledControllerClient
import com.wled.tv.network.WledControllerClient
import com.wled.tv.service.AmbientCaptureService
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

        // CalibrationActivity: must use controllerClient and have no raw udpSender field
        val calibFields = CalibrationActivity::class.java.declaredFields.map { it.name }
        assertFalse("CalibrationActivity must not declare udpSender", calibFields.contains("udpSender"))
        assertTrue("CalibrationActivity must declare controllerClient", calibFields.contains("controllerClient"))
        val calibControllerField = CalibrationActivity::class.java.getDeclaredField("controllerClient")
        assertTrue(WledControllerClient::class.java.isAssignableFrom(calibControllerField.type))

        // CornerFinderActivity: must use controllerClient and have no raw udpSender field
        val cornerFields = CornerFinderActivity::class.java.declaredFields.map { it.name }
        assertFalse("CornerFinderActivity must not declare udpSender", cornerFields.contains("udpSender"))
        assertTrue("CornerFinderActivity must declare controllerClient", cornerFields.contains("controllerClient"))
        val cornerControllerField = CornerFinderActivity::class.java.getDeclaredField("controllerClient")
        assertTrue(WledControllerClient::class.java.isAssignableFrom(cornerControllerField.type))
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

            val cornerActivity = CornerFinderActivity()
            val cornerField = CornerFinderActivity::class.java.getDeclaredField("controllerClient").apply { isAccessible = true }
            assertSame(fakeClient, cornerField.get(cornerActivity))
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
}
