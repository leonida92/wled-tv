package com.wled.tv.ui

import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import com.wled.tv.data.ConfigRepository
import com.wled.tv.data.InMemoryConfigStorage
import com.wled.tv.model.ColorCalibration
import com.wled.tv.model.PresetProfile
import com.wled.tv.model.WledConfig
import com.wled.tv.model.WledDevice
import com.wled.tv.network.DefaultWledControllerClient
import com.wled.tv.network.DiscoveredWled
import com.wled.tv.network.FakeWledControllerClient
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
import java.io.File

class UiPresetAndBackupTest {

    private lateinit var storage: InMemoryConfigStorage
    private lateinit var repo: ConfigRepository
    private lateinit var fakeClient: FakeWledControllerClient

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
        fakeClient = FakeWledControllerClient()
        DefaultWledControllerClient.setInstanceForTesting(fakeClient)
    }

    @After
    fun tearDown() {
        ArchTaskExecutor.getInstance().setDelegate(null)
        ConfigRepository.setInstanceForTesting(null)
        DefaultWledControllerClient.setInstanceForTesting(null)
    }

    @Test
    fun presetSelection_appliesPresetParametersAndUpdatesActiveProfileId() = runBlocking {
        // Initially default
        assertEquals("default", repo.currentConfig.activeProfileId)
        assertEquals(PresetProfile.DEFAULT, repo.currentConfig.activeProfile)

        // Select Cinema
        repo.updateConfig { it.applyPreset(PresetProfile.CINEMA) }
        val cinemaConfig = repo.currentConfig
        assertEquals("cinema", cinemaConfig.activeProfileId)
        assertEquals("Cinema", cinemaConfig.activeProfile.name)
        assertEquals(1.4f, cinemaConfig.primaryDevice.calibration.saturation, 0.001f)
        assertEquals(1.05f, cinemaConfig.primaryDevice.calibration.contrast, 0.001f)
        assertEquals(0.35f, cinemaConfig.primaryDevice.calibration.smoothingFactor, 0.001f)
        assertEquals(12, cinemaConfig.primaryDevice.calibration.blackThreshold)

        // Select Gaming
        repo.updateConfig { it.applyPreset(PresetProfile.GAMING) }
        val gamingConfig = repo.currentConfig
        assertEquals("gaming", gamingConfig.activeProfileId)
        assertEquals(1.8f, gamingConfig.primaryDevice.calibration.saturation, 0.001f)
        assertEquals(0.75f, gamingConfig.primaryDevice.calibration.smoothingFactor, 0.001f)

        // Select Vivid
        repo.updateConfig { it.applyPreset(PresetProfile.VIVID) }
        val vividConfig = repo.currentConfig
        assertEquals("vivid", vividConfig.activeProfileId)
        assertEquals(2.2f, vividConfig.primaryDevice.calibration.saturation, 0.001f)
        assertEquals(1.15f, vividConfig.primaryDevice.calibration.contrast, 0.001f)

        // Select Eye Care
        repo.updateConfig { it.applyPreset(PresetProfile.EYE_CARE) }
        val eyeCareConfig = repo.currentConfig
        assertEquals("eye_care", eyeCareConfig.activeProfileId)
        assertEquals(180, eyeCareConfig.primaryDevice.calibration.maxBrightness)
        assertEquals(0.85f, eyeCareConfig.primaryDevice.calibration.gainB, 0.001f)

        // Select Standard (Default)
        repo.updateConfig { it.applyPreset(PresetProfile.DEFAULT) }
        val defaultConfig = repo.currentConfig
        assertEquals("default", defaultConfig.activeProfileId)
        assertEquals("Standard", defaultConfig.activeProfile.name)
    }

    @Test
    fun sliderModification_autoTransitionsActiveProfileToCustom() = runBlocking {
        // Start from Cinema
        repo.updateConfig { it.applyPreset(PresetProfile.CINEMA) }
        assertEquals("cinema", repo.currentConfig.activeProfileId)

        // Simulate slider adjustment in CalibrationActivity (e.g. saturation) via repository helper
        repo.updateDeviceCalibration { it.copy(saturation = 2.0f) }

        val customConfig = repo.currentConfig
        assertEquals(PresetProfile.CUSTOM.id, customConfig.activeProfileId)
        assertEquals("Custom", customConfig.activeProfile.name)
        assertEquals(2.0f, customConfig.primaryDevice.calibration.saturation, 0.001f)

        // Simulate another slider adjustment (e.g. contrast)
        repo.updateDeviceCalibration { it.copy(contrast = 1.2f) }

        assertEquals(PresetProfile.CUSTOM.id, repo.currentConfig.activeProfileId)
        assertEquals(1.2f, repo.currentConfig.primaryDevice.calibration.contrast, 0.001f)
    }

    @Test
    fun resetToPreset_restoresFactoryPresetCalibration() = runBlocking {
        // Pick Cinema preset
        repo.updateConfig { it.applyPreset(PresetProfile.CINEMA) }
        assertEquals("cinema", repo.currentConfig.activeProfileId)

        // User tweaks sliders -> activeProfileId becomes custom
        repo.updateConfig { cur ->
            val dev = cur.primaryDevice
            val updatedDev = dev.copy(calibration = dev.calibration.copy(saturation = 2.8f, contrast = 0.6f))
            cur.updateDevice(updatedDev).copy(
                activeProfileId = PresetProfile.CUSTOM.id,
                calibration = updatedDev.calibration
            )
        }
        assertEquals("custom", repo.currentConfig.activeProfileId)
        assertEquals(2.8f, repo.currentConfig.primaryDevice.calibration.saturation, 0.001f)

        // User clicks "Reset to Preset" (Cinema)
        repo.updateConfig { it.applyPreset(PresetProfile.CINEMA) }
        val restored = repo.currentConfig
        assertEquals("cinema", restored.activeProfileId)
        assertEquals(PresetProfile.CINEMA.calibration.saturation, restored.primaryDevice.calibration.saturation, 0.001f)
        assertEquals(PresetProfile.CINEMA.calibration.contrast, restored.primaryDevice.calibration.contrast, 0.001f)
        assertEquals(PresetProfile.CINEMA.calibration.smoothingFactor, restored.primaryDevice.calibration.smoothingFactor, 0.001f)
        assertEquals(PresetProfile.CINEMA.calibration.blackThreshold, restored.primaryDevice.calibration.blackThreshold)
    }

    @Test
    fun deviceManager_usesControllerClientDiscovery() = runBlocking {
        // Verify DeviceManagerActivity does not declare WledDiscovery class fields
        val fields = DeviceManagerActivity::class.java.declaredFields.map { it.type.name }
        assertFalse(
            "DeviceManagerActivity must not declare direct WledDiscovery field",
            fields.any { it.contains("WledDiscovery") }
        )

        // Verify discovery flow emitted via controllerClient
        val discoveredDevice = DiscoveredWled(
            name = "WLED-LivingRoom",
            ip = "192.168.1.188",
            port = 21324
        )

        val flow = fakeClient.discoverDevices()
        val emitted = mutableListOf<DiscoveredWled>()
        val job = launch(Dispatchers.Unconfined) {
            flow.collect { emitted.add(it) }
        }

        fakeClient.discoveryFlow.emit(discoveredDevice)
        job.cancel()

        assertEquals(1, emitted.size)
        assertEquals("WLED-LivingRoom", emitted[0].name)
        assertEquals("192.168.1.188", emitted[0].ip)
    }

    @Test
    fun configExportAndImport_roundTripIntegrity() = runBlocking {
        // Set up configuration with Cinema preset and custom IP
        repo.updateConfig { cur ->
            cur.applyPreset(PresetProfile.CINEMA).updatePrimaryIp("192.168.1.99")
        }

        // Export JSON
        val exportedJson = repo.exportConfig()
        assertNotNull(exportedJson)
        assertTrue(exportedJson.contains("\"app\": \"wled-tv\""))
        assertTrue(exportedJson.contains("\"schemaVersion\": 1"))
        assertTrue(exportedJson.contains("\"activeProfileId\": \"cinema\""))
        assertTrue(exportedJson.contains("192.168.1.99"))

        // Reset configuration to default
        repo.updateConfig { WledConfig() }
        assertEquals("default", repo.currentConfig.activeProfileId)
        assertEquals("192.168.1.66", repo.currentConfig.ip)

        // Import the exported JSON
        val importSuccess = repo.importConfig(exportedJson)
        assertTrue("Import must succeed for valid exported JSON", importSuccess)

        val restored = repo.currentConfig
        assertEquals("cinema", restored.activeProfileId)
        assertEquals("192.168.1.99", restored.ip)
        assertEquals(PresetProfile.CINEMA.calibration.saturation, restored.primaryDevice.calibration.saturation, 0.001f)

        // Importing invalid JSON should fail gracefully without altering current state
        val invalidSuccess = repo.importConfig("{ invalid json")
        assertFalse("Import must fail for invalid JSON", invalidSuccess)
        assertEquals("cinema", repo.currentConfig.activeProfileId)
    }

    @Test
    fun layoutXml_declaresAllRequiredPresetAndBackupViewIds() {
        fun resolveFile(relPath: String): File =
            listOf(File(relPath), File("app/$relPath")).firstOrNull { it.exists() }
                ?: error("Could not find $relPath")

        val mainXml = resolveFile("src/main/res/layout/activity_main.xml").readText()
        assertTrue("activity_main.xml must declare btnPresetPill", mainXml.contains("android:id=\"@+id/btnPresetPill\""))
        assertTrue("activity_main.xml must declare tvPresetText", mainXml.contains("android:id=\"@+id/tvPresetText\""))

        val calibXml = resolveFile("src/main/res/layout/activity_calibration.xml").readText()
        assertTrue("activity_calibration.xml must declare btnPresetSelector", calibXml.contains("android:id=\"@+id/btnPresetSelector\""))
        assertTrue("activity_calibration.xml must declare btnResetPreset", calibXml.contains("android:id=\"@+id/btnResetPreset\""))
        assertTrue("activity_calibration.xml must declare tvPresetDescription", calibXml.contains("android:id=\"@+id/tvPresetDescription\""))

        val settingsXml = resolveFile("src/main/res/layout/activity_system_settings.xml").readText()
        assertTrue("activity_system_settings.xml must declare itemExportConfig", settingsXml.contains("android:id=\"@+id/itemExportConfig\""))
        assertTrue("activity_system_settings.xml must declare itemImportConfig", settingsXml.contains("android:id=\"@+id/itemImportConfig\""))
    }
}
