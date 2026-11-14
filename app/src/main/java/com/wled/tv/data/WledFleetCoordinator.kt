package com.wled.tv.data

import com.wled.tv.model.WledDevice
import com.wled.tv.network.DiscoveredWled
import com.wled.tv.network.WledControllerClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Outcome of toggling a fixture's enabled state in the fleet.
 */
sealed interface DeviceToggleResult {
    data class Toggled(
        val device: WledDevice,
        val isReachable: Boolean? = null,
        val powerCommandSucceeded: Boolean = true
    ) : DeviceToggleResult

    data class NotFound(val deviceId: String) : DeviceToggleResult
}

/**
 * Outcome of attempting to remove a fixture from the fleet.
 */
sealed interface DeviceRemoveResult {
    data class Removed(
        val device: WledDevice,
        val powerOffSucceeded: Boolean = true
    ) : DeviceRemoveResult

    data class RejectedLastDevice(val device: WledDevice) : DeviceRemoveResult

    data class NotFound(val deviceId: String) : DeviceRemoveResult
}

/**
 * Seam coordinating multi-fixture lifecycle operations, reachability polling,
 * identification, and LAN discovery across [ConfigRepository] and [WledControllerClient].
 */
interface FleetCoordinator {
    val reachabilityFlow: StateFlow<Map<String, Boolean>>

    fun startReachabilityPolling(
        scope: CoroutineScope,
        intervalMs: Long = WledControllerClient.DEFAULT_REACHABILITY_POLL_INTERVAL_MS
    ): Job

    suspend fun toggleDeviceEnabled(deviceId: String, isStreamingActive: Boolean): DeviceToggleResult

    suspend fun removeDevice(deviceId: String): DeviceRemoveResult

    suspend fun identifyDevice(device: WledDevice)

    suspend fun discoverDevices(timeoutMs: Long = DEFAULT_DISCOVERY_TIMEOUT_MS): List<DiscoveredWled>

    companion object {
        const val DEFAULT_DISCOVERY_TIMEOUT_MS = 2500L
    }
}

/**
 * Default implementation of [FleetCoordinator] backed by [ConfigRepository] and [WledControllerClient].
 */
class WledFleetCoordinator(
    private val configRepo: ConfigRepository,
    private val controllerClient: WledControllerClient
) : FleetCoordinator {

    override val reachabilityFlow: StateFlow<Map<String, Boolean>>
        get() = controllerClient.reachabilityFlow

    override fun startReachabilityPolling(scope: CoroutineScope, intervalMs: Long): Job {
        return controllerClient
            .observeReachability(
                devicesFlow = configRepo.configFlow.map { it.devices },
                intervalMs = intervalMs
            )
            .launchIn(scope)
    }

    override suspend fun toggleDeviceEnabled(
        deviceId: String,
        isStreamingActive: Boolean
    ): DeviceToggleResult {
        var toggledDevice: WledDevice? = null
        configRepo.updateConfig { current ->
            toggledDevice = null
            val target = current.devices.firstOrNull { it.id == deviceId } ?: return@updateConfig current
            val updated = target.copy(enabled = !target.enabled)
            toggledDevice = updated
            current.updateDevice(updated)
        }

        val updated = toggledDevice ?: return DeviceToggleResult.NotFound(deviceId)
        return if (updated.enabled) {
            val reachable = controllerClient.checkConnection(updated.ip)
            val woken = if (isStreamingActive) {
                controllerClient.wakeAndSetBrightness(updated)
            } else {
                true
            }
            DeviceToggleResult.Toggled(
                device = updated,
                isReachable = reachable,
                powerCommandSucceeded = woken
            )
        } else {
            controllerClient.blackoutFleet(listOf(updated), sendPowerOff = true)
            DeviceToggleResult.Toggled(
                device = updated,
                isReachable = null,
                powerCommandSucceeded = true
            )
        }
    }

    override suspend fun removeDevice(deviceId: String): DeviceRemoveResult {
        var removedDevice: WledDevice? = null
        var rejection: DeviceRemoveResult? = null

        configRepo.updateConfig { current ->
            removedDevice = null
            rejection = null
            val target = current.devices.firstOrNull { it.id == deviceId }
            when {
                target == null -> {
                    rejection = DeviceRemoveResult.NotFound(deviceId)
                    current
                }
                current.devices.size <= 1 -> {
                    rejection = DeviceRemoveResult.RejectedLastDevice(target)
                    current
                }
                else -> {
                    removedDevice = target
                    current.copy(devices = current.devices.filterNot { it.id == deviceId })
                }
            }
        }

        val removed = removedDevice ?: return rejection ?: DeviceRemoveResult.NotFound(deviceId)
        val turnedOff = controllerClient.turnOff(removed.ip)
        return DeviceRemoveResult.Removed(
            device = removed,
            powerOffSucceeded = turnedOff
        )
    }

    override suspend fun identifyDevice(device: WledDevice) {
        controllerClient.flashDevice(device)
        if (device.enabled) {
            controllerClient.wakeAndSetBrightness(device)
        } else {
            controllerClient.turnOff(device.ip)
        }
    }

    override suspend fun discoverDevices(timeoutMs: Long): List<DiscoveredWled> {
        val found = LinkedHashMap<String, DiscoveredWled>()
        withTimeoutOrNull(timeoutMs) {
            controllerClient.discoverDevices().collect { device ->
                val cleanIp = device.ip.trim()
                if (cleanIp.isNotEmpty() && !found.containsKey(cleanIp)) {
                    found[cleanIp] = device
                }
            }
        }
        return found.values.toList()
    }
}
