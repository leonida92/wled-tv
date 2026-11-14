package com.wled.tv.network

import com.wled.tv.model.WledDevice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive

/**
 * Unified network controller client for WLED fixtures.
 *
 * Encapsulates:
 * - High-frequency synchronous UDP frame streaming (DDP, DRGB, WARLS) operating directly
 *   on [WledDevice] domain models for deterministic 60Hz loop pacing without coroutine dispatch jitter.
 * - Asynchronous HTTP controller state management (power, brightness, reachability, device info).
 * - Instance-scoped reactive reachability tracking and periodic polling.
 * - Multi-device fleet orchestration (zeroed blackout UDP bursts with parallel HTTP turnOff,
 *   and non-allocating identification flash routines).
 * - Local network fixture discovery via mDNS / DNS-SD.
 */
interface WledControllerClient {

    /**
     * Instance-scoped map of trimmed device IP -> latest known reachability status.
     */
    val reachabilityFlow: StateFlow<Map<String, Boolean>>

    /**
     * Synchronous frame dispatch for a configured [WledDevice].
     * High-frequency hot path for 60Hz loop pacing without coroutine dispatch jitter.
     * Accepts a pre-formatted [wirePayload] (pre-ordered wire channels, 3 bytes per LED for RGB,
     * 4 bytes per LED for RGBW) and transmits directly to the fixture.
     *
     * @return Number of UDP datagrams successfully sent (0 on failure).
     */
    fun sendFrame(device: WledDevice, wirePayload: ByteArray, ledCount: Int = device.totalLeds): Int

    /**
     * Wakes the device and sets its configured brightness via HTTP REST.
     */
    suspend fun wakeAndSetBrightness(device: WledDevice): Boolean

    /**
     * Wakes the device at the given IP and sets its brightness via HTTP REST.
     */
    suspend fun wakeAndSetBrightness(ip: String, brightness: Int = 255): Boolean

    /**
     * Powers off the device at the given IP via HTTP REST.
     */
    suspend fun turnOff(ip: String): Boolean

    /**
     * Performs an HTTP reachability check against the device at the given IP
     * and updates its entry in [reachabilityFlow].
     */
    suspend fun checkConnection(ip: String): Boolean

    /**
     * Concurrently probes unique non-blank IPs from [devices], updates [reachabilityFlow]
     * while pruning removed IPs, and returns the resulting IP -> reachability map.
     */
    suspend fun probeReachability(devices: List<WledDevice>): Map<String, Boolean>

    /**
     * Observes [devicesFlow] and periodically probes unique device IPs every [intervalMs]
     * while collected, updating [reachabilityFlow] and emitting each snapshot.
     * Cancels cleanly when the collector stops.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeReachability(
        devicesFlow: Flow<List<WledDevice>>,
        intervalMs: Long = DEFAULT_REACHABILITY_POLL_INTERVAL_MS
    ): Flow<Map<String, Boolean>> = devicesFlow
        .map { devices ->
            devices.filter { it.ip.isNotBlank() }.distinctBy { it.ip.trim() }
        }
        .distinctUntilChanged { old, new ->
            old.map { it.ip.trim() } == new.map { it.ip.trim() }
        }
        .flatMapLatest { devices ->
            flow {
                if (devices.isEmpty()) {
                    emit(probeReachability(emptyList()))
                } else {
                    while (currentCoroutineContext().isActive) {
                        emit(probeReachability(devices))
                        delay(intervalMs)
                    }
                }
            }
        }

    /**
     * Fetches detailed device information (name, version, led count, rgbw capability, max power) via HTTP.
     */
    suspend fun fetchDeviceInfo(ip: String): WledDeviceInfo?

    /**
     * Sends zeroed UDP blackout bursts immediately to all devices sequentially,
     * and optionally powers off fixtures via parallel HTTP requests with timeout safeguards.
     */
    suspend fun blackoutFleet(devices: List<WledDevice>, sendPowerOff: Boolean = true)

    /**
     * Pulses a device with a full-white UDP frame, waits for [durationMs], and then sends a full-black UDP frame.
     */
    suspend fun flashDevice(device: WledDevice, durationMs: Long = 500L)

    /**
     * Discovers WLED instances on the local network via mDNS / DNS-SD.
     * Emits discovered fixtures as they are identified and resolved.
     */
    fun discoverDevices(): Flow<DiscoveredWled>

    /**
     * Releases underlying socket and network resources.
     */
    fun close()

    companion object {
        const val DEFAULT_REACHABILITY_POLL_INTERVAL_MS = 5000L
    }
}
