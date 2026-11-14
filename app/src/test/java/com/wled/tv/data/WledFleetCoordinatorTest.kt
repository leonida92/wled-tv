package com.wled.tv.data

import com.wled.tv.model.DeviceType
import com.wled.tv.model.WledConfig
import com.wled.tv.model.WledDevice
import com.wled.tv.network.DiscoveredWled
import com.wled.tv.network.FakeWledControllerClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WledFleetCoordinatorTest {

    private lateinit var storage: InMemoryConfigStorage
    private lateinit var repo: ConfigRepository
    private lateinit var fakeClient: FakeWledControllerClient
    private lateinit var coordinator: WledFleetCoordinator

    private val primaryDevice = WledDevice(
        id = "dev-primary",
        name = "TV Backlight",
        ip = "192.168.1.50",
        enabled = true,
        type = DeviceType.PERIMETER,
        ledCount = 60
    )

    private val secondaryDevice = WledDevice(
        id = "dev-lamp",
        name = "Side Lamp",
        ip = "192.168.1.51",
        enabled = false,
        type = DeviceType.LEFT_AMBIENT,
        ledCount = 24
    )

    @Before
    fun setUp() {
        storage = InMemoryConfigStorage()
        repo = ConfigRepository(storage, Dispatchers.Unconfined)
        fakeClient = FakeWledControllerClient()
        coordinator = WledFleetCoordinator(repo, fakeClient)
    }

    @Test
    fun toggleDeviceEnabled_enableWhileStreaming_wakesFixtureAndUpdatesConfig() = runTest {
        repo.updateConfig { WledConfig(devices = listOf(primaryDevice, secondaryDevice)) }
        fakeClient.connectionCheckMap[secondaryDevice.ip] = true

        val result = coordinator.toggleDeviceEnabled(secondaryDevice.id, isStreamingActive = true)

        assertTrue(result is DeviceToggleResult.Toggled)
        val toggled = result as DeviceToggleResult.Toggled
        assertTrue(toggled.device.enabled)
        assertEquals(true, toggled.isReachable)
        assertTrue(toggled.powerCommandSucceeded)

        val stored = repo.currentConfig.devices.first { it.id == secondaryDevice.id }
        assertTrue(stored.enabled)
        assertEquals(true, fakeClient.powerState[secondaryDevice.ip])
        assertEquals(true, coordinator.reachabilityFlow.value[secondaryDevice.ip])
    }

    @Test
    fun toggleDeviceEnabled_enableWhileNotStreaming_updatesConfigWithoutWakingFixture() = runTest {
        repo.updateConfig { WledConfig(devices = listOf(primaryDevice, secondaryDevice)) }
        fakeClient.connectionCheckMap[secondaryDevice.ip] = true

        val result = coordinator.toggleDeviceEnabled(secondaryDevice.id, isStreamingActive = false)

        assertTrue(result is DeviceToggleResult.Toggled)
        val toggled = result as DeviceToggleResult.Toggled
        assertTrue(toggled.device.enabled)
        assertEquals(true, toggled.isReachable)

        val stored = repo.currentConfig.devices.first { it.id == secondaryDevice.id }
        assertTrue(stored.enabled)
        assertNull("Fixture must not be woken when streaming is inactive", fakeClient.powerState[secondaryDevice.ip])
        assertEquals(true, coordinator.reachabilityFlow.value[secondaryDevice.ip])
    }

    @Test
    fun toggleDeviceEnabled_disable_blackoutsAndPowersOffAndUpdatesConfig() = runTest {
        repo.updateConfig { WledConfig(devices = listOf(primaryDevice, secondaryDevice)) }
        fakeClient.powerState[primaryDevice.ip] = true

        val result = coordinator.toggleDeviceEnabled(primaryDevice.id, isStreamingActive = true)

        assertTrue(result is DeviceToggleResult.Toggled)
        val toggled = result as DeviceToggleResult.Toggled
        assertFalse(toggled.device.enabled)
        assertNull(toggled.isReachable)

        val stored = repo.currentConfig.devices.first { it.id == primaryDevice.id }
        assertFalse(stored.enabled)
        assertEquals(1, fakeClient.blackoutCalls)
        assertEquals(3, fakeClient.sentFrames.size)
        assertEquals(false, fakeClient.powerState[primaryDevice.ip])
    }

    @Test
    fun toggleDeviceEnabled_unknownDevice_returnsNotFoundWithoutMutatingConfig() = runTest {
        repo.updateConfig { WledConfig(devices = listOf(primaryDevice)) }
        val before = repo.currentConfig

        val result = coordinator.toggleDeviceEnabled("missing-id", isStreamingActive = true)

        assertEquals(DeviceToggleResult.NotFound("missing-id"), result)
        assertEquals(before, repo.currentConfig)
        assertTrue(fakeClient.powerState.isEmpty())
        assertEquals(0, fakeClient.blackoutCalls)
    }

    @Test
    fun removeDevice_lastRemainingDevice_rejectedWithoutMutatingConfigOrCallingTurnOff() = runTest {
        repo.updateConfig { WledConfig(devices = listOf(primaryDevice)) }
        fakeClient.powerState[primaryDevice.ip] = true

        val result = coordinator.removeDevice(primaryDevice.id)

        assertTrue(result is DeviceRemoveResult.RejectedLastDevice)
        assertEquals(primaryDevice, (result as DeviceRemoveResult.RejectedLastDevice).device)
        assertEquals(listOf(primaryDevice), repo.currentConfig.devices)
        assertEquals(true, fakeClient.powerState[primaryDevice.ip])
    }

    @Test
    fun removeDevice_secondaryDevice_callsTurnOffAndUpdatesConfig() = runTest {
        val enabledSecondary = secondaryDevice.copy(enabled = true)
        repo.updateConfig { WledConfig(devices = listOf(primaryDevice, enabledSecondary)) }
        fakeClient.powerState[enabledSecondary.ip] = true

        val result = coordinator.removeDevice(enabledSecondary.id)

        assertTrue(result is DeviceRemoveResult.Removed)
        val removed = result as DeviceRemoveResult.Removed
        assertEquals(enabledSecondary, removed.device)
        assertTrue(removed.powerOffSucceeded)
        assertEquals(listOf(primaryDevice), repo.currentConfig.devices)
        assertEquals(false, fakeClient.powerState[enabledSecondary.ip])
    }

    @Test
    fun removeDevice_unknownDevice_returnsNotFoundWithoutMutatingConfig() = runTest {
        repo.updateConfig { WledConfig(devices = listOf(primaryDevice, secondaryDevice)) }
        val before = repo.currentConfig

        val result = coordinator.removeDevice("non-existent")

        assertEquals(DeviceRemoveResult.NotFound("non-existent"), result)
        assertEquals(before, repo.currentConfig)
        assertTrue(fakeClient.powerState.isEmpty())
    }

    @Test
    fun identifyDevice_enabledAndDisabledDevices_flashesAndRestoresPowerState() = runTest {
        coordinator.identifyDevice(primaryDevice)
        assertEquals(listOf(primaryDevice), fakeClient.flashedDevices)
        assertEquals(true, fakeClient.powerState[primaryDevice.ip])

        coordinator.identifyDevice(secondaryDevice)
        assertEquals(listOf(primaryDevice, secondaryDevice), fakeClient.flashedDevices)
        assertEquals(false, fakeClient.powerState[secondaryDevice.ip])
    }

    @Test
    fun startReachabilityPolling_updatesReachabilityFlow_andPrunesStaleEntries() = runTest {
        val enabledSecondary = secondaryDevice.copy(enabled = true)
        repo.updateConfig { WledConfig(devices = listOf(primaryDevice, enabledSecondary)) }
        fakeClient.connectionCheckMap[primaryDevice.ip] = true
        fakeClient.connectionCheckMap[enabledSecondary.ip] = false

        val pollJob = coordinator.startReachabilityPolling(this, intervalMs = 5000L)
        runCurrent()

        assertEquals(
            mapOf(
                primaryDevice.ip to true,
                enabledSecondary.ip to false
            ),
            coordinator.reachabilityFlow.value
        )

        // Next poll interval reflects updated reachability
        fakeClient.connectionCheckMap[enabledSecondary.ip] = true
        advanceTimeBy(5000L)
        runCurrent()
        assertEquals(
            mapOf(
                primaryDevice.ip to true,
                enabledSecondary.ip to true
            ),
            coordinator.reachabilityFlow.value
        )

        // Removing secondaryDevice prunes its IP from reachabilityFlow
        coordinator.removeDevice(enabledSecondary.id)
        runCurrent()
        assertEquals(
            mapOf(primaryDevice.ip to true),
            coordinator.reachabilityFlow.value
        )

        pollJob.cancel()
        val callsAfterCancel = fakeClient.probeReachabilityCalls
        advanceTimeBy(15000L)
        runCurrent()
        assertEquals(callsAfterCancel, fakeClient.probeReachabilityCalls)
    }

    @Test
    fun discoverDevices_deduplicatesByIpAndIgnoresBlankEntries() = runTest {
        val emitter = launch(UnconfinedTestDispatcher(testScheduler)) {
            delay(50L)
            fakeClient.discoveryFlow.emit(DiscoveredWled("WLED-TV", "192.168.1.50", 80))
            fakeClient.discoveryFlow.emit(DiscoveredWled("WLED-TV-Dup", "192.168.1.50", 80))
            fakeClient.discoveryFlow.emit(DiscoveredWled("WLED-Blank", "   ", 80))
            fakeClient.discoveryFlow.emit(DiscoveredWled("WLED-Lamp", "192.168.1.60", 80))
        }

        val discovered = coordinator.discoverDevices(timeoutMs = 200L)
        emitter.join()

        assertEquals(
            listOf(
                DiscoveredWled("WLED-TV", "192.168.1.50", 80),
                DiscoveredWled("WLED-Lamp", "192.168.1.60", 80)
            ),
            discovered
        )
    }
}
