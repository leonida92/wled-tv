package com.wled.tv.ui

import com.wled.tv.data.ConfigRepository
import com.wled.tv.data.InMemoryConfigStorage
import com.wled.tv.model.DeviceType
import com.wled.tv.model.WledConfig
import com.wled.tv.model.WledDevice
import com.wled.tv.network.DefaultWledControllerClient
import com.wled.tv.network.FakeWledControllerClient
import com.wled.tv.network.WledDeviceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UiControllerClientMigrationTest {

    private lateinit var storage: InMemoryConfigStorage
    private lateinit var repo: ConfigRepository
    private lateinit var fakeClient: FakeWledControllerClient

    private val testDevice = WledDevice(
        id = "dev-test-1",
        name = "Backlight Right",
        ip = "192.168.1.120",
        port = 4048,
        type = DeviceType.RIGHT_AMBIENT,
        ledCount = 60,
        enabled = true
    )

    @Before
    fun setUp() {
        storage = InMemoryConfigStorage()
        repo = ConfigRepository(storage, Dispatchers.Unconfined)
        ConfigRepository.setInstanceForTesting(repo)

        fakeClient = FakeWledControllerClient()
        DefaultWledControllerClient.setInstanceForTesting(fakeClient)
    }

    @After
    fun tearDown() {
        ConfigRepository.setInstanceForTesting(null)
        DefaultWledControllerClient.setInstanceForTesting(null)
    }

    @Test
    fun defaultWledControllerClient_singletonInstanceResolvesFakeWhenInjected() {
        val resolved = DefaultWledControllerClient.getInstance()
        assertEquals(fakeClient, resolved)
    }

    @Test
    fun deviceManager_identifyDevice_flashesDeviceAndRestoresPower() = runBlocking {
        // Simulates DeviceManagerActivity identifyDevice flow
        fakeClient.flashDevice(testDevice)
        if (testDevice.enabled) {
            fakeClient.wakeAndSetBrightness(testDevice)
        } else {
            fakeClient.turnOff(testDevice.ip)
        }

        assertTrue(fakeClient.flashedDevices.contains(testDevice))
        assertEquals(true, fakeClient.powerState[testDevice.ip])
        assertEquals(2, fakeClient.sentFrames.size)

        // Verify white frame then black frame
        val whiteFrame = fakeClient.sentFrames[0]
        assertEquals(testDevice, whiteFrame.device)
        assertEquals(60, whiteFrame.ledCount)
        assertTrue(whiteFrame.rgb.all { it == 255.toByte() })

        val blackFrame = fakeClient.sentFrames[1]
        assertEquals(testDevice, blackFrame.device)
        assertEquals(60, blackFrame.ledCount)
        assertTrue(blackFrame.rgb.all { it == 0.toByte() })
    }

    @Test
    fun deviceManager_toggleDevice_updatesPowerViaControllerClient() = runBlocking {
        // Toggle off: calls blackoutFleet(listOf(updatedDevice), sendPowerOff = true)
        val disabledDevice = testDevice.copy(enabled = false)
        repo.updateConfig { cur -> cur.copy(devices = listOf(disabledDevice)) }
        fakeClient.blackoutFleet(listOf(disabledDevice), sendPowerOff = true)
        assertEquals(false, fakeClient.powerState[disabledDevice.ip])
        assertEquals(1, fakeClient.blackoutCalls)
        assertEquals(3, fakeClient.sentFrames.size)

        // Toggle back on
        val enabledDevice = disabledDevice.copy(enabled = true)
        repo.updateConfig { cur -> cur.copy(devices = listOf(enabledDevice)) }
        val reachable = fakeClient.checkConnection(enabledDevice.ip)
        assertTrue(reachable)
        fakeClient.wakeAndSetBrightness(enabledDevice)
        assertEquals(true, fakeClient.powerState[enabledDevice.ip])
    }

    @Test
    fun deviceManager_deleteDevice_turnsOffViaControllerClient() = runBlocking {
        repo.updateConfig { cur -> cur.copy(devices = listOf(testDevice)) }
        assertEquals(1, repo.currentConfig.devices.size)

        // Deletion flow in DeviceManagerActivity: calls blackoutFleet(listOf(device), sendPowerOff = true)
        repo.updateConfig { cur -> cur.copy(devices = cur.devices.filterNot { it.id == testDevice.id }) }
        fakeClient.blackoutFleet(listOf(testDevice), sendPowerOff = true)

        assertEquals(0, repo.currentConfig.devices.size)
        assertEquals(false, fakeClient.powerState[testDevice.ip])
        assertEquals(1, fakeClient.blackoutCalls)
        assertEquals(3, fakeClient.sentFrames.size)
    }

    @Test
    fun editDevice_deleteDevice_blackoutsViaControllerClient() = runBlocking {
        repo.updateConfig { cur -> cur.copy(devices = listOf(testDevice)) }
        assertEquals(1, repo.currentConfig.devices.size)

        // Deletion flow in EditDeviceActivity: calls blackoutFleet(listOf(deviceToDelete), sendPowerOff = true)
        repo.updateConfig { cur -> cur.copy(devices = cur.devices.filterNot { it.id == testDevice.id }) }
        fakeClient.blackoutFleet(listOf(testDevice), sendPowerOff = true)

        assertEquals(0, repo.currentConfig.devices.size)
        assertEquals(false, fakeClient.powerState[testDevice.ip])
        assertEquals(1, fakeClient.blackoutCalls)
        assertEquals(3, fakeClient.sentFrames.size)
    }

    @Test
    fun mainActivity_turnOffAllLeds_blackoutsEnabledDevicesWithoutPowerOff() = runBlocking {
        val dev1 = testDevice.copy(id = "dev1", ip = "192.168.1.10", enabled = true)
        val dev2 = testDevice.copy(id = "dev2", ip = "192.168.1.11", enabled = true)
        val enabledDevices = listOf(dev1, dev2)

        // In MainActivity: turnOffAllLeds() -> controllerClient.blackoutFleet(config.enabledDevices, sendPowerOff = false)
        fakeClient.blackoutFleet(enabledDevices, sendPowerOff = false)

        assertEquals(1, fakeClient.blackoutCalls)
        assertEquals(6, fakeClient.sentFrames.size)
        // Grouped sequential bursts: dev1, dev1, dev1, dev2, dev2, dev2
        assertEquals("dev1", fakeClient.sentFrames[0].device.id)
        assertEquals("dev1", fakeClient.sentFrames[1].device.id)
        assertEquals("dev1", fakeClient.sentFrames[2].device.id)
        assertEquals("dev2", fakeClient.sentFrames[3].device.id)
        assertEquals("dev2", fakeClient.sentFrames[4].device.id)
        assertEquals("dev2", fakeClient.sentFrames[5].device.id)
        assertTrue(fakeClient.powerState.isEmpty())
    }

    @Test
    fun mainActivity_toggleDeviceDisable_blackoutsDeviceWithPowerOff() = runBlocking {
        val disabledDevice = testDevice.copy(enabled = false)

        // In MainActivity: toggleDeviceEnabled() when !newEnabled -> controllerClient.blackoutFleet(listOf(updatedDevice), sendPowerOff = true)
        fakeClient.blackoutFleet(listOf(disabledDevice), sendPowerOff = true)

        assertEquals(1, fakeClient.blackoutCalls)
        assertEquals(3, fakeClient.sentFrames.size)
        assertEquals(false, fakeClient.powerState[disabledDevice.ip])
    }

    @Test
    fun editDevice_pingReachabilityAndDeviceInfo_interactsWithControllerClient() = runBlocking {
        fakeClient.connectionCheckMap[testDevice.ip] = true
        fakeClient.deviceInfoMap[testDevice.ip] = WledDeviceInfo(
            name = "WLED-Ambience",
            version = "0.14.1",
            ledCount = 90,
            isRgbw = true,
            maxPowerMa = 2500
        )

        // Ping check in EditDeviceActivity
        val info = fakeClient.fetchDeviceInfo(testDevice.ip)
        assertNotNull(info)
        assertEquals("0.14.1", info?.version)
        assertEquals(90, info?.ledCount)
        assertTrue(info?.isRgbw == true)

        val reachable = fakeClient.checkConnection(testDevice.ip)
        assertTrue(reachable)
    }

    @Test
    fun editDevice_identifyAndTurnOff_usesUnifiedClient() = runBlocking {
        fakeClient.flashDevice(testDevice)
        assertTrue(fakeClient.flashedDevices.contains(testDevice))

        fakeClient.turnOff(testDevice.ip)
        assertEquals(false, fakeClient.powerState[testDevice.ip])
    }
}
