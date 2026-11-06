package com.wled.tv.ui

import com.wled.tv.data.ConfigRepository
import com.wled.tv.data.InMemoryConfigStorage
import com.wled.tv.engine.AmbientStreamingEngine
import com.wled.tv.model.ColorCalibration
import com.wled.tv.model.ThroughputStats
import com.wled.tv.model.WledConfig
import com.wled.tv.model.WledDevice
import com.wled.tv.service.AmbientCaptureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UiMigrationTest {

    private lateinit var storage: InMemoryConfigStorage
    private lateinit var repo: ConfigRepository

    @Before
    fun setUp() {
        storage = InMemoryConfigStorage()
        repo = ConfigRepository(storage, Dispatchers.Unconfined)
        ConfigRepository.setInstanceForTesting(repo)
    }

    @After
    fun tearDown() {
        ConfigRepository.setInstanceForTesting(null)
        AmbientCaptureService.setActiveEngineForTesting(null)
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
        repo.updateConfigBlocking { cur ->
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
}
