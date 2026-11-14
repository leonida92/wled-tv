package com.wled.tv.network

import com.wled.tv.model.WledDevice
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory test double of [WledControllerClient] for fast JVM unit testing.
 * Records all UDP frames, power operations, blackout invocations, and allows
 * configuring reachability and device info responses with zero network I/O.
 */
class FakeWledControllerClient : WledControllerClient {

    data class SentFrameRecord(
        val device: WledDevice,
        val wirePayload: ByteArray,
        val ledCount: Int
    ) {
        val rgb: ByteArray
            get() = wirePayload

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as SentFrameRecord
            return device == other.device && ledCount == other.ledCount && wirePayload.contentEquals(other.wirePayload)
        }

        override fun hashCode(): Int {
            var result = device.hashCode()
            result = 31 * result + ledCount
            result = 31 * result + wirePayload.contentHashCode()
            return result
        }
    }

    private val _reachabilityFlow = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    override val reachabilityFlow: StateFlow<Map<String, Boolean>> = _reachabilityFlow.asStateFlow()

    private val _sentFrames = mutableListOf<SentFrameRecord>()
    val sentFrames: List<SentFrameRecord>
        get() = synchronized(_sentFrames) { _sentFrames.toList() }

    val powerState = mutableMapOf<String, Boolean>()
    var blackoutCalls = 0
        private set
    val blackoutDevices = mutableListOf<List<WledDevice>>()

    var probeReachabilityCalls = 0
        private set

    val discoveryFlow = MutableSharedFlow<DiscoveredWled>()

    var connectionCheckResult: Boolean = true
    val connectionCheckMap = mutableMapOf<String, Boolean>()

    var deviceInfoResult: WledDeviceInfo? = null
    val deviceInfoMap = mutableMapOf<String, WledDeviceInfo?>()

    val flashedDevices = mutableListOf<WledDevice>()

    var isClosed = false
        private set

    var simulateFlashDelay = false

    override fun sendFrame(device: WledDevice, wirePayload: ByteArray, ledCount: Int): Int {
        synchronized(_sentFrames) {
            _sentFrames.add(SentFrameRecord(device, wirePayload.copyOf(), ledCount))
        }
        return 1
    }

    fun sendFrame(device: WledDevice, wirePayload: ByteArray): Int {
        return sendFrame(device, wirePayload, device.totalLeds)
    }

    override suspend fun wakeAndSetBrightness(device: WledDevice): Boolean {
        powerState[device.ip] = true
        return true
    }

    override suspend fun wakeAndSetBrightness(ip: String, brightness: Int): Boolean {
        powerState[ip] = true
        return true
    }

    override suspend fun turnOff(ip: String): Boolean {
        powerState[ip] = false
        return true
    }

    override suspend fun checkConnection(ip: String): Boolean {
        val cleanIp = ip.trim()
        val reachable = connectionCheckMap[cleanIp] ?: connectionCheckMap[ip] ?: connectionCheckResult
        if (cleanIp.isNotEmpty()) {
            _reachabilityFlow.update { current -> current + (cleanIp to reachable) }
        }
        return reachable
    }

    override suspend fun probeReachability(devices: List<WledDevice>): Map<String, Boolean> {
        probeReachabilityCalls++
        val uniqueIps = devices.map { it.ip.trim() }.filter { it.isNotEmpty() }.distinct()
        val results = uniqueIps.associateWith { ip ->
            connectionCheckMap[ip] ?: connectionCheckResult
        }
        _reachabilityFlow.value = results
        return results
    }

    override suspend fun fetchDeviceInfo(ip: String): WledDeviceInfo? {
        return if (deviceInfoMap.containsKey(ip)) {
            deviceInfoMap[ip]
        } else {
            deviceInfoResult
        }
    }

    override suspend fun blackoutFleet(devices: List<WledDevice>, sendPowerOff: Boolean) {
        blackoutCalls++
        synchronized(blackoutDevices) {
            blackoutDevices.add(devices)
        }
        for (device in devices) {
            val leds = device.totalLeds
            if (leds > 0) {
                val blackBuf = ByteArray(leds * device.wireBytesPerLed)
                repeat(3) {
                    sendFrame(device, blackBuf, leds)
                }
            }
        }
        if (sendPowerOff) {
            for (device in devices) {
                turnOff(device.ip)
            }
        }
    }

    suspend fun blackoutFleet(devices: List<WledDevice>) {
        blackoutFleet(devices, sendPowerOff = true)
    }

    override suspend fun flashDevice(device: WledDevice, durationMs: Long) {
        synchronized(flashedDevices) {
            flashedDevices.add(device)
        }
        val leds = device.totalLeds
        if (leds > 0) {
            val frameSize = leds * device.wireBytesPerLed
            val white = ByteArray(frameSize) { 255.toByte() }
            val black = ByteArray(frameSize)
            sendFrame(device, white, leds)
            if (simulateFlashDelay && durationMs > 0) {
                delay(durationMs)
            }
            sendFrame(device, black, leds)
        }
    }

    suspend fun flashDevice(device: WledDevice) {
        flashDevice(device, durationMs = 500L)
    }

    override fun discoverDevices(): Flow<DiscoveredWled> = discoveryFlow

    override fun close() {
        isClosed = true
    }

    fun clear() {
        synchronized(_sentFrames) {
            _sentFrames.clear()
        }
        powerState.clear()
        blackoutCalls = 0
        probeReachabilityCalls = 0
        synchronized(blackoutDevices) {
            blackoutDevices.clear()
        }
        synchronized(flashedDevices) {
            flashedDevices.clear()
        }
        connectionCheckMap.clear()
        deviceInfoMap.clear()
        connectionCheckResult = true
        deviceInfoResult = null
        _reachabilityFlow.value = emptyMap()
        isClosed = false
    }
}
