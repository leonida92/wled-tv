package com.wled.tv.data

import com.wled.tv.geometry.NormalizedRect
import com.wled.tv.model.CaptureSettings
import com.wled.tv.model.ColorCalibration
import com.wled.tv.model.Corner
import com.wled.tv.model.DeviceType
import com.wled.tv.model.Direction
import com.wled.tv.model.PerimeterConfig
import com.wled.tv.model.PresetProfile
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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
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
        assertEquals(PresetProfile.DEFAULT.id, config.activeProfileId)
        assertEquals(PresetProfile.DEFAULT, config.activeProfile)

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
        assertEquals("RGB", primary.colorOrder)
        assertFalse(primary.isRgbw)
        assertEquals("AUTO_SUBTRACT", primary.rgbwMode)
        assertEquals(60, config.capture.fps)
        assertEquals(320, config.capture.captureWidth)
        assertEquals(180, config.capture.captureHeight)
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
        assertEquals(PresetProfile.DEFAULT.id, config.activeProfileId)

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
        assertEquals("GRB", primary.colorOrder)
        assertTrue(primary.isRgbw)
        assertEquals("ACCURATE", primary.rgbwMode)
        assertEquals(0.55f, config.calibration.smoothingFactor, 0.001f)
        assertEquals(30, config.capture.fps)
        assertEquals(480, config.capture.captureWidth)
        assertEquals(270, config.capture.captureHeight)
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
                    calibration = updated.calibration.copy(maxBrightness = 180),
                    capture = updated.capture.copy(fps = 45)
                )
            }
        }

        testScheduler.advanceUntilIdle()

        // Verify storage persisted correctly
        assertTrue(storage.getBoolean("auto_start", false))
        assertEquals(45, storage.getInt("capture_fps", 60))
        assertEquals(180, storage.getInt("max_brightness", 255))
        assertEquals(1, storage.getInt("config_schema_version", 0))

        val devicesJson = storage.getString("wled_devices_json", null)
        assertNotNull(devicesJson)
        val jsonArray = JSONArray(devicesJson!!)
        assertEquals(2, jsonArray.length())

        // Recreate repository from the same storage
        val reloadedRepo = ConfigRepository(storage, ioDispatcher = testDispatcher)
        val reloadedConfig = reloadedRepo.currentConfig

        assertTrue(reloadedConfig.autoStartOnBoot)
        assertEquals(2, reloadedConfig.devices.size)
        assertEquals(45, reloadedConfig.capture.fps)
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
            repo.configFlow.collect { collector1.add(it.capture.fps) }
        }
        val job2 = launch(testDispatcher) {
            repo.configFlow.collect { collector2.add(it.capture.fps) }
        }
        val job3 = launch(testDispatcher) {
            repo.configFlow.collect { collector3.add(it.capture.fps) }
        }

        testScheduler.advanceUntilIdle()

        repo.updateCaptureSettings { it.copy(fps = 30) }
        testScheduler.advanceUntilIdle()

        repo.updateCaptureSettings { it.copy(fps = 45) }
        testScheduler.advanceUntilIdle()

        repo.updateCaptureSettings { it.copy(fps = 60) }
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(60, 30, 45, 60), collector1)
        assertEquals(listOf(60, 30, 45, 60), collector2)
        assertEquals(listOf(60, 30, 45, 60), collector3)

        job1.cancel()
        job2.cancel()
        job3.cancel()
    }

    // --- Pass 5 Preset Profiles & Schema Migration Tests ---

    @Test
    fun initialLoading_defaultPresetProfile_isDefault() {
        val storage = createEmptyStorage()
        val repo = ConfigRepository(storage)

        assertEquals(PresetProfile.DEFAULT.id, repo.currentConfig.activeProfileId)
        assertEquals(PresetProfile.DEFAULT, repo.currentConfig.activeProfile)
    }

    @Test
    fun updateConfig_updatingActiveProfile_persistsAndUpdatesFlow() = runTest {
        val storage = createEmptyStorage()
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val repo = ConfigRepository(storage, ioDispatcher = testDispatcher)

        assertEquals("default", repo.currentConfig.activeProfileId)

        // Switch to CINEMA preset
        repo.updateConfig { current ->
            current.applyPreset(PresetProfile.CINEMA)
        }
        testScheduler.advanceUntilIdle()

        assertEquals(PresetProfile.CINEMA.id, repo.currentConfig.activeProfileId)
        assertEquals(PresetProfile.CINEMA, repo.currentConfig.activeProfile)
        assertEquals(1.4f, repo.currentConfig.calibration.saturation, 0.001f)
        assertEquals(1.05f, repo.currentConfig.calibration.contrast, 0.001f)
        assertEquals(0.35f, repo.currentConfig.calibration.smoothingFactor, 0.001f)
        assertEquals(12, repo.currentConfig.calibration.blackThreshold)

        // Verify storage persisted active_profile_id
        assertEquals("cinema", storage.getString(ConfigRepository.KEY_ACTIVE_PROFILE_ID, null))

        // Recreate repository from the same storage
        val reloadedRepo = ConfigRepository(storage, ioDispatcher = testDispatcher)
        assertEquals(PresetProfile.CINEMA.id, reloadedRepo.currentConfig.activeProfileId)
        assertEquals(PresetProfile.CINEMA, reloadedRepo.currentConfig.activeProfile)
        assertEquals(1.4f, reloadedRepo.currentConfig.calibration.saturation, 0.001f)
    }

    @Test
    fun migration_v0ToV1_setsActiveProfileAndSchemaVersion() {
        // v0 storage with no schema version and no active_profile_id
        val storage = InMemoryConfigStorage(
            mapOf(
                "wled_ip" to "192.168.1.80",
                "saturation" to 1.7f
            )
        )
        assertEquals(0, storage.getInt(ConfigRepository.KEY_SCHEMA_VERSION, 0))

        val repo = ConfigRepository(storage)

        // Migrator should have updated storage to version 1 and activeProfileId to default
        assertEquals(1, storage.getInt(ConfigRepository.KEY_SCHEMA_VERSION, 0))
        assertEquals(PresetProfile.DEFAULT.id, storage.getString(ConfigRepository.KEY_ACTIVE_PROFILE_ID, null))
        assertEquals(PresetProfile.DEFAULT.id, repo.currentConfig.activeProfileId)
        assertEquals("192.168.1.80", repo.currentConfig.ip)
        assertEquals(1.7f, repo.currentConfig.calibration.saturation, 0.001f)
    }

    @Test
    fun exportConfig_and_importConfig_roundtripPreservesAllSettings() = runTest {
        val storage = createEmptyStorage()
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val repo = ConfigRepository(storage, ioDispatcher = testDispatcher)

        val customSpot = WledDevice(
            id = "lamp-1",
            name = "Right Lamp",
            ip = "192.168.1.150",
            port = 4048,
            protocol = StreamingProtocol.DDP,
            enabled = true,
            type = DeviceType.RIGHT_AMBIENT,
            ledCount = 16,
            customRect = NormalizedRect(0.7f, 0.2f, 1.0f, 0.8f)
        )

        repo.updateConfig { current ->
            current.applyPreset(PresetProfile.GAMING).copy(
                autoStartOnBoot = true,
                devices = current.devices + customSpot
            )
        }
        testScheduler.advanceUntilIdle()

        val exportedJson = repo.exportConfig()
        assertNotNull(exportedJson)

        // Verify JSON envelope metadata
        val rootObj = JSONObject(exportedJson)
        assertEquals("wled-tv", rootObj.getString("app"))
        assertEquals(1, rootObj.getInt("schemaVersion"))
        assertTrue(rootObj.has("timestamp"))
        assertTrue(rootObj.has("config"))

        // Create fresh repository and import
        val freshStorage = createEmptyStorage()
        val freshRepo = ConfigRepository(freshStorage, ioDispatcher = testDispatcher)

        val importResult = freshRepo.importConfig(exportedJson)
        testScheduler.advanceUntilIdle()

        assertTrue(importResult)
        val importedConfig = freshRepo.currentConfig

        assertEquals(repo.currentConfig.activeProfileId, importedConfig.activeProfileId)
        assertEquals(PresetProfile.GAMING.id, importedConfig.activeProfileId)
        assertEquals(true, importedConfig.autoStartOnBoot)
        assertEquals(repo.currentConfig.calibration.saturation, importedConfig.calibration.saturation, 0.001f)
        assertEquals(repo.currentConfig.calibration.smoothingFactor, importedConfig.calibration.smoothingFactor, 0.001f)
        assertEquals(2, importedConfig.devices.size)

        val importedSpot = importedConfig.devices.first { it.id == "lamp-1" }
        assertEquals("Right Lamp", importedSpot.name)
        assertEquals("192.168.1.150", importedSpot.ip)
        assertEquals(DeviceType.RIGHT_AMBIENT, importedSpot.type)
        assertEquals(16, importedSpot.ledCount)
        assertEquals(0.7f, importedSpot.customRect.left, 0.001f)
    }

    @Test
    fun importConfig_invalidSignature_rejected() = runTest {
        val storage = createEmptyStorage()
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val repo = ConfigRepository(storage, ioDispatcher = testDispatcher)

        val invalidJson = JSONObject().apply {
            put("app", "unauthorized-app")
            put("schemaVersion", 1)
            put("timestamp", System.currentTimeMillis())
            put("config", JSONObject())
        }.toString()

        // Direct parser call should throw IllegalArgumentException
        assertThrows(IllegalArgumentException::class.java) {
            ConfigRepository.importConfigJson(invalidJson)
        }

        // Repository importConfig should return false main-safely
        val success = repo.importConfig(invalidJson)
        testScheduler.advanceUntilIdle()

        assertFalse(success)
        assertEquals(PresetProfile.DEFAULT.id, repo.currentConfig.activeProfileId)
    }

    @Test
    fun importConfig_unsupportedFutureSchemaVersion_rejected() = runTest {
        val storage = createEmptyStorage()
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val repo = ConfigRepository(storage, ioDispatcher = testDispatcher)

        val futureJson = JSONObject().apply {
            put("app", "wled-tv")
            put("schemaVersion", 999)
            put("timestamp", System.currentTimeMillis())
            put("config", JSONObject())
        }.toString()

        // Direct parser call should throw IllegalArgumentException
        assertThrows(IllegalArgumentException::class.java) {
            ConfigRepository.importConfigJson(futureJson)
        }

        // Repository importConfig should return false main-safely
        val success = repo.importConfig(futureJson)
        testScheduler.advanceUntilIdle()

        assertFalse(success)
        assertEquals(PresetProfile.DEFAULT.id, repo.currentConfig.activeProfileId)
    }

    @Test
    fun importConfig_malformedJson_rejected() = runTest {
        val storage = createEmptyStorage()
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val repo = ConfigRepository(storage, ioDispatcher = testDispatcher)

        val badJson = "{ not a json }"

        assertThrows(IllegalArgumentException::class.java) {
            ConfigRepository.importConfigJson(badJson)
        }

        val success = repo.importConfig(badJson)
        testScheduler.advanceUntilIdle()

        assertFalse(success)
    }

    @Test
    fun importConfig_legacySchemaVersionZero_acceptedAndMigrated() = runTest {
        val storage = createEmptyStorage()
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val repo = ConfigRepository(storage, ioDispatcher = testDispatcher)

        val legacyJson = JSONObject().apply {
            put("app", "wled-tv")
            put("schemaVersion", 0)
            put("timestamp", System.currentTimeMillis())
            put("config", JSONObject().apply {
                put("autoStartOnBoot", true)
                put("calibration", JSONObject().apply {
                    put("saturation", 1.9)
                    put("fps", 30)
                    put("captureWidth", 480)
                    put("captureHeight", 270)
                    put("colorOrder", "GRB")
                    put("isRgbw", true)
                    put("rgbwMode", "AUTO_ADD")
                })
            })
        }.toString()

        val migrated = ConfigRepository.importConfigJson(legacyJson)
        assertEquals(PresetProfile.DEFAULT.id, migrated.activeProfileId)
        assertTrue(migrated.autoStartOnBoot)
        assertEquals(1.9f, migrated.calibration.saturation, 0.01f)
        assertEquals(30, migrated.capture.fps)
        assertEquals(480, migrated.capture.captureWidth)
        assertEquals(270, migrated.capture.captureHeight)
        assertEquals("GRB", migrated.primaryDevice.colorOrder)
        assertTrue(migrated.primaryDevice.isRgbw)
        assertEquals("AUTO_ADD", migrated.primaryDevice.rgbwMode)

        val success = repo.importConfig(legacyJson)
        testScheduler.advanceUntilIdle()

        assertTrue(success)
        assertEquals(1.9f, repo.currentConfig.calibration.saturation, 0.01f)
        assertEquals(30, repo.currentConfig.capture.fps)
    }

    // --- Wave 1 Invariant & Backup I/O Tests ---

    @Test
    fun applyPreset_preservesCaptureSettingsAndPerDeviceHardwareFormatOnAllDevices() = runTest {
        val storage = createEmptyStorage()
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val repo = ConfigRepository(storage, ioDispatcher = testDispatcher)

        val perimeterGrbRgbw = WledDevice(
            id = "perimeter-1",
            name = "Main TV",
            ip = "192.168.1.50",
            port = 4048,
            protocol = StreamingProtocol.DDP,
            type = DeviceType.PERIMETER,
            colorOrder = "GRB",
            isRgbw = true,
            rgbwMode = "AUTO_ADD"
        )
        val lampBgr = WledDevice(
            id = "lamp-left",
            name = "Left Lamp",
            ip = "192.168.1.51",
            port = 21324,
            protocol = StreamingProtocol.DRGB,
            type = DeviceType.LEFT_AMBIENT,
            ledCount = 12,
            colorOrder = "BGR",
            isRgbw = false,
            rgbwMode = "OFF"
        )

        repo.updateConfig {
            WledConfig(
                devices = listOf(perimeterGrbRgbw, lampBgr),
                capture = CaptureSettings(fps = 30, captureWidth = 480, captureHeight = 270)
            )
        }
        testScheduler.advanceUntilIdle()

        val updated = repo.applyPreset(PresetProfile.VIVID)
        testScheduler.advanceUntilIdle()

        assertEquals(PresetProfile.VIVID.id, updated.activeProfileId)
        assertEquals(PresetProfile.VIVID.calibration, updated.calibration)
        // Capture settings preserved
        assertEquals(30, updated.capture.fps)
        assertEquals(480, updated.capture.captureWidth)
        assertEquals(270, updated.capture.captureHeight)

        // Both PERIMETER and non-PERIMETER devices get preset optical calibration while keeping hardware strip format
        val updatedPerimeter = updated.devices.first { it.id == "perimeter-1" }
        assertEquals(PresetProfile.VIVID.calibration, updatedPerimeter.calibration)
        assertEquals("GRB", updatedPerimeter.colorOrder)
        assertTrue(updatedPerimeter.isRgbw)
        assertEquals("AUTO_ADD", updatedPerimeter.rgbwMode)

        val updatedLamp = updated.devices.first { it.id == "lamp-left" }
        assertEquals(PresetProfile.VIVID.calibration, updatedLamp.calibration)
        assertEquals("BGR", updatedLamp.colorOrder)
        assertFalse(updatedLamp.isRgbw)
        assertEquals("OFF", updatedLamp.rgbwMode)
    }

    @Test
    fun updateDeviceCalibration_autoTransitionsActiveProfileToCustom() = runTest {
        val storage = createEmptyStorage()
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val repo = ConfigRepository(storage, ioDispatcher = testDispatcher)

        val perimeter = WledDevice(
            id = "dev-perim",
            type = DeviceType.PERIMETER,
            colorOrder = "GRB"
        )
        val spot = WledDevice(
            id = "dev-spot",
            type = DeviceType.RIGHT_AMBIENT,
            ledCount = 5,
            colorOrder = "BRG"
        )
        repo.updateConfig { WledConfig(devices = listOf(perimeter, spot)).applyPreset(PresetProfile.CINEMA) }
        testScheduler.advanceUntilIdle()
        assertEquals(PresetProfile.CINEMA.id, repo.currentConfig.activeProfileId)

        // Editing non-perimeter device transitions activeProfileId to CUSTOM without clobbering global calibration
        val afterSpot = repo.updateDeviceCalibration("dev-spot") { it.copy(saturation = 2.4f) }
        testScheduler.advanceUntilIdle()
        assertEquals(PresetProfile.CUSTOM.id, afterSpot.activeProfileId)
        assertEquals(2.4f, afterSpot.devices.first { it.id == "dev-spot" }.calibration.saturation, 0.001f)
        assertEquals(PresetProfile.CINEMA.calibration.saturation, afterSpot.calibration.saturation, 0.001f)
        assertEquals("BRG", afterSpot.devices.first { it.id == "dev-spot" }.colorOrder)

        // Reset to GAMING, then edit PERIMETER device -> transitions to CUSTOM and updates global calibration
        repo.applyPreset(PresetProfile.GAMING)
        testScheduler.advanceUntilIdle()
        assertEquals(PresetProfile.GAMING.id, repo.currentConfig.activeProfileId)

        val afterPerim = repo.updateDeviceCalibration(null) { it.copy(contrast = 1.35f) }
        testScheduler.advanceUntilIdle()
        assertEquals(PresetProfile.CUSTOM.id, afterPerim.activeProfileId)
        assertEquals(1.35f, afterPerim.primaryDevice.calibration.contrast, 0.001f)
        assertEquals(1.35f, afterPerim.calibration.contrast, 0.001f)
        assertEquals("GRB", afterPerim.primaryDevice.colorOrder)
    }

    @Test
    fun backupFileExportAndImport_worksOnTempDirectoriesWithFallback() = runTest {
        val storage = createEmptyStorage()
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val repo = ConfigRepository(storage, ioDispatcher = testDispatcher)

        repo.updateConfig { cur ->
            cur.applyPreset(PresetProfile.EYE_CARE)
                .updatePrimaryIp("192.168.1.210")
                .updateCaptureSettings { it.copy(fps = 30, captureWidth = 160, captureHeight = 90) }
        }
        testScheduler.advanceUntilIdle()

        val tempRoot = Files.createTempDirectory("wled_backup_test").toFile()
        try {
            val primaryDir = File(tempRoot, "downloads")
            val fallbackDir = File(tempRoot, "app_files")

            // Initially no backup exists
            assertEquals(null, repo.findBackupFile(primaryDir, fallbackDir))

            // Export to primaryDir
            val exportResult = repo.exportBackup(primaryDir, fallbackDir)
            assertEquals(File(primaryDir, ConfigRepository.BACKUP_FILENAME), exportResult.file)
            assertTrue(exportResult.file.exists())
            assertFalse(exportResult.json.contains("cal_sat"))

            // findBackupFile locates primary file
            val found = repo.findBackupFile(primaryDir, fallbackDir)
            assertEquals(exportResult.file, found)

            // Import into a fresh repository from file
            val freshRepo = ConfigRepository(createEmptyStorage(), ioDispatcher = testDispatcher)
            val imported = freshRepo.importBackupFromFile(found!!)
            testScheduler.advanceUntilIdle()

            assertTrue(imported)
            assertEquals(PresetProfile.EYE_CARE.id, freshRepo.currentConfig.activeProfileId)
            assertEquals("192.168.1.210", freshRepo.currentConfig.ip)
            assertEquals(30, freshRepo.currentConfig.capture.fps)
            assertEquals(160, freshRepo.currentConfig.capture.captureWidth)
            assertEquals(90, freshRepo.currentConfig.capture.captureHeight)

            // When primaryDir is null, exportBackup writes to fallbackDir and findBackupFile finds it
            val fallbackResult = repo.exportBackup(null, fallbackDir)
            assertEquals(File(fallbackDir, ConfigRepository.BACKUP_FILENAME), fallbackResult.file)
            assertTrue(fallbackResult.file.exists())
            assertEquals(fallbackResult.file, repo.findBackupFile(null, fallbackDir))

            // Non-existent file returns false
            assertFalse(freshRepo.importBackupFromFile(File(tempRoot, "missing.json")))
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun importConfig_legacyAbbreviatedDeviceKeys_migratesAccuratelyAndReExportsCanonicalSchema() = runTest {
        val storage = createEmptyStorage()
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val repo = ConfigRepository(storage, ioDispatcher = testDispatcher)

        val legacyCalKeysExport = JSONObject().apply {
            put("app", "wled-tv")
            put("schemaVersion", 1)
            put("timestamp", 1700000000000L)
            put("config", JSONObject().apply {
                put("activeProfileId", "custom")
                put("autoStartOnBoot", true)
                put("calibration", JSONObject().apply {
                    put("saturation", 1.85)
                    put("fps", 30)
                    put("captureWidth", 480)
                    put("captureHeight", 270)
                })
                put("devices", JSONArray().apply {
                    put(JSONObject().apply {
                        put("id", "legacy-dev-1")
                        put("name", "Legacy RGBW Strip")
                        put("ip", "192.168.1.77")
                        put("port", 4048)
                        put("protocol", "DDP")
                        put("enabled", true)
                        put("type", "PERIMETER")
                        put("colorOrder", "BGR")
                        put("cal_rgbw", true)
                        put("cal_rgbwm", "AUTO_ADD")
                        put("cal_sat", 2.15)
                        put("cal_con", 1.15)
                        put("cal_bri", 210)
                        put("cal_blk", 14)
                        put("cal_gr", 1.1)
                        put("cal_gg", 0.9)
                        put("cal_gb", 0.85)
                        put("cal_gamr", 1.3)
                        put("cal_gamg", 1.2)
                        put("cal_gamb", 1.1)
                        put("cal_sm", 0.65)
                        put("cal_fps", 30)
                        put("cal_cw", 480)
                        put("cal_ch", 270)
                    })
                })
            })
        }.toString()

        val success = repo.importConfig(legacyCalKeysExport)
        testScheduler.advanceUntilIdle()
        assertTrue(success)

        val loaded = repo.currentConfig
        assertEquals("custom", loaded.activeProfileId)
        assertTrue(loaded.autoStartOnBoot)
        assertEquals(30, loaded.capture.fps)
        assertEquals(480, loaded.capture.captureWidth)
        assertEquals(270, loaded.capture.captureHeight)

        val dev = loaded.devices.single()
        assertEquals("legacy-dev-1", dev.id)
        assertEquals("BGR", dev.colorOrder)
        assertTrue(dev.isRgbw)
        assertEquals("AUTO_ADD", dev.rgbwMode)
        assertEquals(2.15f, dev.calibration.saturation, 0.001f)
        assertEquals(1.15f, dev.calibration.contrast, 0.001f)
        assertEquals(210, dev.calibration.maxBrightness)
        assertEquals(14, dev.calibration.blackThreshold)
        assertEquals(1.1f, dev.calibration.gainR, 0.001f)
        assertEquals(0.9f, dev.calibration.gainG, 0.001f)
        assertEquals(0.85f, dev.calibration.gainB, 0.001f)
        assertEquals(1.3f, dev.calibration.gammaR, 0.001f)
        assertEquals(1.2f, dev.calibration.gammaG, 0.001f)
        assertEquals(1.1f, dev.calibration.gammaB, 0.001f)
        assertEquals(0.65f, dev.calibration.smoothingFactor, 0.001f)

        // Canonical export must emit unified nested calibration & top-level capture, with no cal_* keys
        val canonicalJson = repo.exportConfig()
        assertFalse(canonicalJson.contains("\"cal_sat\""))
        assertFalse(canonicalJson.contains("\"cal_rgbw\""))
        val root = JSONObject(canonicalJson)
        val cfgObj = root.getJSONObject("config")
        assertTrue(cfgObj.has("capture"))
        val devObj = cfgObj.getJSONArray("devices").getJSONObject(0)
        assertTrue(devObj.has("isRgbw"))
        assertTrue(devObj.has("rgbwMode"))
        assertTrue(devObj.has("calibration"))
    }
}
