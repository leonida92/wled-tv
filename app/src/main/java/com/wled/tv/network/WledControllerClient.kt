package com.wled.tv.network

import com.wled.tv.model.WledDevice

/**
 * Unified network controller client for WLED fixtures.
 *
 * Encapsulates:
 * - High-frequency synchronous UDP frame streaming (DDP, DRGB, WARLS) operating directly
 *   on [WledDevice] domain models for deterministic 60Hz loop pacing without coroutine dispatch jitter.
 * - Asynchronous HTTP controller state management (power, brightness, reachability, device info).
 * - Multi-device fleet orchestration (zeroed blackout UDP bursts with parallel HTTP turnOff,
 *   and non-allocating identification flash routines).
 */
interface WledControllerClient {

    /**
     * Synchronous frame dispatch for a configured [WledDevice].
     * High-frequency hot path for 60Hz loop pacing without coroutine dispatch jitter.
     *
     * @return Number of UDP datagrams successfully sent (0 on failure).
     */
    fun sendFrame(device: WledDevice, rgb: ByteArray, ledCount: Int = device.totalLeds): Int

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
     * Performs an HTTP reachability check against the device at the given IP.
     */
    suspend fun checkConnection(ip: String): Boolean

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
     * Releases underlying socket and network resources.
     */
    fun close()
}
