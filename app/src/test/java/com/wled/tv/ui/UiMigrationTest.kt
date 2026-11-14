package com.wled.tv.ui

import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import com.wled.tv.data.ConfigRepository
import com.wled.tv.data.InMemoryConfigStorage
import com.wled.tv.engine.AmbientStreamingEngine
import com.wled.tv.engine.DiagnosticStreamCoordinator
import com.wled.tv.model.ColorCalibration
import com.wled.tv.model.ThroughputStats
import com.wled.tv.model.WledConfig
import com.wled.tv.model.WledDevice
import com.wled.tv.network.DefaultWledControllerClient
import com.wled.tv.data.FleetCoordinator
import com.wled.tv.network.FakeWledControllerClient
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
    fun diagnosticTestPattern_setsDiagnosticOverrideOnActiveEngine() = kotlinx.coroutines.test.runTest {
        val fakeClient = FakeWledControllerClient()
        val engine = AmbientStreamingEngine(initialConfig = WledConfig(), controllerClient = fakeClient)
        AmbientCaptureService.setActiveEngineForTesting(engine)

        val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        val coordinator = DiagnosticStreamCoordinator(
            initialConfig = WledConfig(),
            controllerClient = fakeClient,
            engineProvider = { AmbientCaptureService.activeEngine.value },
            ioDispatcher = dispatcher
        )
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + dispatcher)

        assertFalse(engine.isDiagnosticActive)

        coordinator.startSolidColor(scope, 255, 255, 255, null)
        assertTrue(engine.isDiagnosticActive)

        coordinator.stop()
        assertFalse(engine.isDiagnosticActive)
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun activities_fieldVerification_useSharedCoordinators() {
        val mainFields = MainActivity::class.java.declaredFields.map { it.name }
        assertFalse("MainActivity must not declare httpClient", mainFields.contains("httpClient"))
        assertFalse("MainActivity must not declare udpSender", mainFields.contains("udpSender"))
        assertFalse("MainActivity must not declare controllerClient", mainFields.contains("controllerClient"))
        assertTrue("MainActivity must declare diagnosticCoordinator", mainFields.contains("diagnosticCoordinator"))
        assertTrue("MainActivity must declare fleetCoordinator", mainFields.contains("fleetCoordinator"))
        val mainDiagField = MainActivity::class.java.getDeclaredField("diagnosticCoordinator")
        assertTrue(DiagnosticStreamCoordinator::class.java.isAssignableFrom(mainDiagField.type))
        val mainFleetField = MainActivity::class.java.getDeclaredField("fleetCoordinator")
        assertTrue(FleetCoordinator::class.java.isAssignableFrom(mainFleetField.type))

        val editFields = EditDeviceActivity::class.java.declaredFields.map { it.name }
        assertTrue("EditDeviceActivity must declare fleetCoordinator", editFields.contains("fleetCoordinator"))
        val editMethods = EditDeviceActivity::class.java.declaredMethods.map { it.name }
        assertFalse("EditDeviceActivity must not declare commitDraftChanges", editMethods.contains("commitDraftChanges"))

        val calibFields = CalibrationActivity::class.java.declaredFields.map { it.name }
        assertFalse("CalibrationActivity must not declare udpSender", calibFields.contains("udpSender"))
        assertFalse("CalibrationActivity must not declare controllerClient", calibFields.contains("controllerClient"))
        assertTrue("CalibrationActivity must declare diagnosticCoordinator", calibFields.contains("diagnosticCoordinator"))
        val calibCoordinatorField = CalibrationActivity::class.java.getDeclaredField("diagnosticCoordinator")
        assertTrue(DiagnosticStreamCoordinator::class.java.isAssignableFrom(calibCoordinatorField.type))
    }

    @Test
    fun activities_injectSharedControllerClientFromSingleton() {
        val fakeClient = FakeWledControllerClient()
        DefaultWledControllerClient.setInstanceForTesting(fakeClient)
        try {
            val innerClientField = DiagnosticStreamCoordinator::class.java.getDeclaredField("controllerClient").apply { isAccessible = true }

            val mainActivity = MainActivity()
            val mainDiagField = MainActivity::class.java.getDeclaredField("diagnosticCoordinator").apply { isAccessible = true }
            val mainCoordinator = mainDiagField.get(mainActivity) as DiagnosticStreamCoordinator
            assertSame(fakeClient, innerClientField.get(mainCoordinator))

            val calibActivity = CalibrationActivity()
            val calibCoordField = CalibrationActivity::class.java.getDeclaredField("diagnosticCoordinator").apply { isAccessible = true }
            val coordinator = calibCoordField.get(calibActivity) as DiagnosticStreamCoordinator
            assertSame(fakeClient, innerClientField.get(coordinator))
        } finally {
            DefaultWledControllerClient.setInstanceForTesting(null)
        }
    }

    @Test
    fun configRepository_deviceDraftCodec_roundtripsWithoutMutatingRepository() {
        val initialDeviceCount = repo.currentConfig.devices.size
        val draft = WledDevice(
            id = "draft-device-1",
            name = "Draft Accent Strip",
            ip = "192.168.1.210",
            port = 4048,
            protocol = com.wled.tv.model.StreamingProtocol.DDP,
            enabled = true,
            type = com.wled.tv.model.DeviceType.LEFT_AMBIENT,
            ledCount = 24,
            calibration = ColorCalibration(saturation = 1.9f, maxBrightness = 210, smoothingFactor = 0.25f),
            colorOrder = "GRB",
            isRgbw = true,
            rgbwMode = "AUTO_ADD"
        )
        val encoded = ConfigRepository.encodeDeviceJson(draft)
        val decoded = ConfigRepository.decodeDeviceJson(encoded)

        assertEquals(draft, decoded)
        assertEquals(initialDeviceCount, repo.currentConfig.devices.size)
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
    fun ui_presetSwitchingAndCustomAutoTransition_updatesConfig() = runBlocking {
        val initialConfig = repo.currentConfig
        assertEquals(com.wled.tv.model.PresetProfile.DEFAULT.id, initialConfig.activeProfileId)

        // Select cinema preset
        val cinema = com.wled.tv.model.PresetProfile.CINEMA
        val updated = repo.updateConfig { it.applyPreset(cinema) }
        assertEquals(cinema.id, updated.activeProfileId)
        assertEquals(cinema.calibration.saturation, updated.calibration.saturation, 0.01f)

        // Tweak slider -> auto transition to custom
        val customConfig = repo.updateConfig { cur ->
            cur.copy(
                activeProfileId = com.wled.tv.model.PresetProfile.CUSTOM.id,
                calibration = cur.calibration.copy(saturation = 2.5f)
            )
        }
        assertEquals(com.wled.tv.model.PresetProfile.CUSTOM.id, customConfig.activeProfileId)
        assertEquals(2.5f, customConfig.calibration.saturation, 0.01f)
    }

    @Test
    fun ui_deviceDiscoveryViaControllerClient_delegatesProperly() = runBlocking {
        val fakeClient = FakeWledControllerClient()
        DefaultWledControllerClient.setInstanceForTesting(fakeClient)

        val discoveredList = mutableListOf<com.wled.tv.network.DiscoveredWled>()
        val job = launch(Dispatchers.Unconfined) {
            fakeClient.discoverDevices().collect { discoveredList.add(it) }
        }

        fakeClient.discoveryFlow.emit(com.wled.tv.network.DiscoveredWled("WLED-Lamp", "192.168.1.188", 4048))
        assertEquals(1, discoveredList.size)
        assertEquals("WLED-Lamp", discoveredList[0].name)
        assertEquals("192.168.1.188", discoveredList[0].ip)
        job.cancel()
    }
}