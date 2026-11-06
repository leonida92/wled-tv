package com.wled.tv.data

import android.graphics.RectF
import com.wled.tv.model.ColorCalibration
import com.wled.tv.model.Corner
import com.wled.tv.model.DeviceType
import com.wled.tv.model.Direction
import com.wled.tv.model.PerimeterConfig
import com.wled.tv.model.StreamingProtocol
import com.wled.tv.model.WledConfig
import com.wled.tv.model.WledDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

@OptIn(ExperimentalCoroutinesApi::class)
class ConfigRepositoryTest {

    private fun createEmptyStorage(): InMemoryConfigStorage = InMemoryConfigStorage()

    @Test
    fun initialLoading_emptyStorage_loadsDefaultConfig() {
        val storage = createEmptyStorage()
        val repo = ConfigRepository(storage)

        val config = repo.currentConfig
        assertEquals(repo.configFlow.value, config)

        assertFalse(config.autoStartOnBoot)
        assertEquals(1, config.devices.size)

        val primary = config.primaryDevice
        assertEquals("192.168.1.66", primary.ip)
        assertEquals(21324, primary.port)
        assertEquals(StreamingProtocol.DRGB, primary.protocol)
        assertEquals(DeviceType.PERIMETER, primary.type)
        assertTrue(primary.enabled)

        assertEquals(54, primary.perimeter.topLeds)
        assertEquals(30, primary.perimeter.rightLeds)
        assertEquals(54, primary.perimeter.bottomLeds)
        assertEquals(30, primary.perimeter.leftLeds)
        assertEquals(Corner.BOTTOM_LEFT, primary.perimeter.startCorner)
        assertEquals(Direction.CLOCKWISE, primary.perimeter.direction)
        assertEquals(168, primary.totalLeds)

        assertEquals(1.6f, config.calibration.saturation, 0.001f)
        assertEquals(1.0f, config.calibration.contrast, 0.001f)
        assertEquals(255, config.calibration.maxBrightness)
        assertEquals(8, config.calibration.blackThreshold)
        assertEquals("RGB", config.calibration.colorOrder)
        assertEquals(60, config.calibration.fps)
        assertEquals(320, config.calibration.captureWidth)
        assertEquals(180, config.calibration.captureHeight)
    }

    @Test
    fun initialLoading_legacyFlatKeysMigration_loadsAndMigratesSuccessfully() {
        val storage = InMemoryConfigStorage(
            mapOf(
                "auto_start" to true,
                "wled_ip" to "10.0.0.50",
                "wled_port" to 4048,
                "wled_protocol" to "DDP",
                "top_leds" to 70,
                "right_leds" to 40,
                "bottom_leds" to 70,
                "left_leds" to 40,
                "start_corner" to "TOP_LEFT",
                "direction" to "COUNTER_CLOCKWISE",
                "top_crop" to 0.05f,
                "bottom_crop" to 0.05f,
                "left_crop" to 0.02f,
                "right_crop" to 0.02f,
                "perimeter_depth" to 0.15f,
                "auto_letterbox" to false,
                "saturation" to 1.9f,
                "contrast" to 1.3f,
                "max_brightness" to 220,
                "black_threshold" to 12,
                "gain_r" to 0.95f,
                "gain_g" to 1.05f,
                "gain_b" to 1.10f,
                "gamma_r" to 1.20f,
                "gamma_g" to 1.10f,
                "gamma_b" to 0.90f,
                "color_order" to "GRB",
                "rgbw_enabled" to true,
                "rgbw_mode" to "ACCURATE",
                "smoothing" to 0.55f,
                "capture_fps" to 30,
                "capture_width" to 480,
                "capture_height" to 270
            )
        )

        val repo = ConfigRepository(storage)
        val config = repo.currentConfig

        assertTrue(config.autoStartOnBoot)
        assertEquals(1, config.devices.size)

        val primary = config.primaryDevice
        assertEquals("10.0.0.50", primary.ip)
        assertEquals(4048, primary.port)
        assertEquals(StreamingProtocol.DDP, primary.protocol)
        assertEquals(DeviceType.PERIMETER, primary.type)
        assertEquals(70, primary.perimeter.topLeds)
        assertEquals(40, primary.perimeter.rightLeds)
        assertEquals(70, primary.perimeter.bottomLeds)
        assertEquals(40, primary.perimeter.leftLeds)
        assertEquals(Corner.TOP_LEFT, primary.perimeter.startCorner)
        assertEquals(Direction.COUNTER_CLOCKWISE, primary.perimeter.direction)
        assertEquals(0.05f, primary.perimeter.topCrop, 0.001f)
        assertEquals(0.05f, primary.perimeter.bottomCrop, 0.001f)
        assertEquals(0.02f, primary.perimeter.leftCrop, 0.001f)
        assertEquals(0.02f, primary.perimeter.rightCrop, 0.001f)
        assertEquals(0.15f, primary.perimeter.depth, 0.001f)
        assertFalse(primary.perimeter.autoLetterbox)

        assertEquals(1.9f, config.calibration.saturation, 0.001f)
        assertEquals(1.3f, config.calibration.contrast, 0.001f)
        assertEquals(220, config.calibration.maxBrightness)
        assertEquals(12, config.calibration.blackThreshold)
        assertEquals(0.95f, config.calibration.gainR, 0.001f)
        assertEquals(1.05f, config.calibration.gainG, 0.001f)
        assertEquals(1.10f, config.calibration.gainB, 0.001f)
        assertEquals(1.20f, config.calibration.gammaR, 0.001f)
        assertEquals(1.10f, config.calibration.gammaG, 0.001f)
        assertEquals(0.90f, config.calibration.gammaB, 0.001f)
        assertEquals("GRB", config.calibration.colorOrder)
        assertTrue(config.calibration.isRgbw)
        assertEquals("ACCURATE", config.calibration.rgbwMode)
        assertEquals(0.55f, config.calibration.smoothingFactor, 0.001f)
        assertEquals(30, config.calibration.fps)
        assertEquals(480, config.calibration.captureWidth)
        assertEquals(270, config.calibration.captureHeight)
    }

    @Test
    fun initialLoading_jsonDevices_loadsMultiDeviceFleet() {
        val devicesJson = JSONArray().apply {
            put(
                JSONObject().apply {
                    put("id", "dev-perimeter")
                    put("name", "TV Strip")
                    put("ip", "192.168.1.101")
                    put("port", 4048)
                    put("protocol", "DDP")
                    put("enabled", true)
                    put("type", "PERIMETER")
                    put("topLeds", 60)
                    put("rightLeds", 34)
                    put("bottomLeds", 60)
                    put("leftLeds", 34)
                    put("startCorner", "BOTTOM_LEFT")
                    put("direction", "CLOCKWISE")
                    put("cal_sat", 1.75)
                    put("cal_bri", 240)
                }
            )
            put(
                JSONObject().apply {
                    put("id", "dev-left-lamp")
                    put("name", "Left Side Lamp")
                    put("ip", "192.168.1.102")
                    put("port", 21324)
                    put("protocol", "DRGB")
                    put("enabled", false)
                    put("type", "LEFT_AMBIENT")
                    put("ledCount", 12)
                    put("rectL", 0.0)
                    put("rectT", 0.2)
                    put("rectR", 0.3)
                    put("rectB", 0.8)
                }
            )
        }.toString()

        val storage = InMemoryConfigStorage(
            mapOf(
                "wled_devices_json" to devicesJson,
                "saturation" to 1.5f,
                "max_brightness" to 200
            )
        )

        val repo = ConfigRepository(storage)
        val config = repo.currentConfig

        assertEquals(2, config.devices.size)

        val dev1 = config.devices[0]
        assertEquals("dev-perimeter", dev1.id)
        assertEquals("TV Strip", dev1.name)
        assertEquals("192.168.1.101", dev1.ip)
        assertEquals(4048, dev1.port)
        assertEquals(StreamingProtocol.DDP, dev1.protocol)
        assertTrue(dev1.enabled)
        assertEquals(DeviceType.PERIMETER, dev1.type)
        assertEquals(188, dev1.totalLeds)
        assertEquals(1.75f, dev1.calibration.saturation, 0.001f)
        assertEquals(240, dev1.calibration.maxBrightness)

        val dev2 = config.devices[1]
        assertEquals("dev-left-lamp", dev2.id)
        assertEquals("Left Side Lamp", dev2.name)
        assertEquals("192.168.1.102", dev2.ip)
        assertEquals(21324, dev2.port)
        assertEquals(StreamingProtocol.DRGB, dev2.protocol)
        assertFalse(dev2.enabled)
        assertEquals(DeviceType.LEFT_AMBIENT, dev2.type)
        assertEquals(12, dev2.ledCount)
        assertEquals(12, dev2.totalLeds)
        assertEquals(0.0f, dev2.customRect.left, 0.001f)
        assertEquals(0.2f, dev2.customRect.top, 0.001f)
        assertEquals(0.3f, dev2.customRect.right, 0.001f)
        assertEquals(0.8f, dev2.customRect.bottom, 0.001f)
    }

    @Test
    fun updateConfig_mutatesInMemoryStateAndEmitsToStateFlow() = runTest {
        val storage = createEmptyStorage()
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val repo = ConfigRepository(storage, ioDispatcher = testDispatcher)

        assertEquals(false, repo.currentConfig.autoStartOnBoot)

        val emissions = mutableListOf<WledConfig>()
        val job = launch(testDispatcher) {
            repo.configFlow.collect { emissions.add(it) }
        }
        testScheduler.advanceUntilIdle()

        // Apply mutation
        val updated = repo.updateConfig { current ->
            current.copy(
                autoStartOnBoot = true,
                calibration = current.calibration.copy(saturation = 2.0f)
            )
        }

        testScheduler.advanceUntilIdle()

        assertEquals(true, updated.autoStartOnBoot)
        assertEquals(2.0f, updated.calibration.saturation, 0.001f)
        assertEquals(updated, repo.currentConfig)
        assertEquals(updated, repo.configFlow.value)

        assertTrue("Emissions should contain at least initial and updated snapshots", emissions.size >= 2)
        assertEquals(false, emissions.first().autoStartOnBoot)
        assertEquals(true, emissions.last().autoStartOnBoot)
        assertEquals(2.0f, emissions.last().calibration.saturation, 0.001f)

        job.cancel()
    }

    @Test
    fun updateConfig_persistsToStorageAsynchronouslyAndSurvivesReload() = runTest {
        val storage = createEmptyStorage()
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val repo = ConfigRepository(storage, ioDispatcher = testDispatcher)

        val newDevice = WledDevice(
            id = "secondary_spot",
            name = "Desk Lamp",
            ip = "192.168.1.200",
            port = 4048,
            protocol = StreamingProtocol.DDP,
            type = DeviceType.BOTTOM_AMBIENT,
            ledCount = 8
        )

        val updatedPrimary = repo.currentConfig.primaryDevice.let {
            it.copy(calibration = it.calibration.copy(maxBrightness = 180))
        }

        repo.updateConfig { current ->
            current.updateDevice(updatedPrimary).let { updated ->
                updated.copy(
                    autoStartOnBoot = true,
                    devices = updated.devices + newDevice,
                    calibration = updated.calibration.copy(fps = 45, maxBrightness = 180)
                )
            }
        }

        testScheduler.advanceUntilIdle()

        // Verify storage persisted correctly
        assertTrue(storage.getBoolean("auto_start", false))
        assertEquals(45, storage.getInt("capture_fps", 60))
        assertEquals(180, storage.getInt("max_brightness", 255))

        val devicesJson = storage.getString("wled_devices_json", null)
        assertNotNull(devicesJson)
        val jsonArray = JSONArray(devicesJson!!)
        assertEquals(2, jsonArray.length())

        // Recreate repository from the same storage
        val reloadedRepo = ConfigRepository(storage, ioDispatcher = testDispatcher)
        val reloadedConfig = reloadedRepo.currentConfig

        assertTrue(reloadedConfig.autoStartOnBoot)
        assertEquals(2, reloadedConfig.devices.size)
        assertEquals(45, reloadedConfig.calibration.fps)
        assertEquals(180, reloadedConfig.calibration.maxBrightness)

        val persistedSpot = reloadedConfig.devices.first { it.id == "secondary_spot" }
        assertEquals("Desk Lamp", persistedSpot.name)
        assertEquals("192.168.1.200", persistedSpot.ip)
        assertEquals(8, persistedSpot.ledCount)
        assertEquals(DeviceType.BOTTOM_AMBIENT, persistedSpot.type)
    }

    @Test
    fun updateConfig_concurrentMutationsAreAtomic() = runTest {
        val storage = createEmptyStorage()
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val repo = ConfigRepository(storage, ioDispatcher = testDispatcher)

        val mutationCount = 50
        val jobs = (1..mutationCount).map { _ ->
            async(Dispatchers.Default) {
                repo.updateConfig { current ->
                    current.copy(
                        calibration = current.calibration.copy(
                            maxBrightness = current.calibration.maxBrightness + 1
                        )
                    )
                }
            }
        }

        jobs.awaitAll()
        testScheduler.advanceUntilIdle()

        // Base maxBrightness is 255, incremented 50 times -> 305
        assertEquals(255 + mutationCount, repo.currentConfig.calibration.maxBrightness)
    }

    @Test
    fun multipleCollectors_receiveAllUpdatesInOrder() = runTest {
        val storage = createEmptyStorage()
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val repo = ConfigRepository(storage, ioDispatcher = testDispatcher)

        val collector1 = mutableListOf<Int>()
        val collector2 = mutableListOf<Int>()
        val collector3 = mutableListOf<Int>()

        val job1 = launch(testDispatcher) {
            repo.configFlow.collect { collector1.add(it.calibration.fps) }
        }
        val job2 = launch(testDispatcher) {
            repo.configFlow.collect { collector2.add(it.calibration.fps) }
        }
        val job3 = launch(testDispatcher) {
            repo.configFlow.collect { collector3.add(it.calibration.fps) }
        }

        testScheduler.advanceUntilIdle()

        repo.updateConfig { it.copy(calibration = it.calibration.copy(fps = 30)) }
        testScheduler.advanceUntilIdle()

        repo.updateConfig { it.copy(calibration = it.calibration.copy(fps = 45)) }
        testScheduler.advanceUntilIdle()

        repo.updateConfig { it.copy(calibration = it.calibration.copy(fps = 60)) }
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(60, 30, 45, 60), collector1)
        assertEquals(listOf(60, 30, 45, 60), collector2)
        assertEquals(listOf(60, 30, 45, 60), collector3)

        job1.cancel()
        job2.cancel()
        job3.cancel()
    }
}
