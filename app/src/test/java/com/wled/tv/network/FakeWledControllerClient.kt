package com.wled.tv.network

import com.wled.tv.model.WledDevice
import kotlinx.coroutines.delay

/**
 * In-memory test double of [WledControllerClient] for fast JVM unit testing.
 * Records all UDP frames, power operations, blackout invocations, and allows
 * configuring reachability and device info responses with zero network I/O.
 */
class FakeWledControllerClient : WledControllerClient {

    data class SentFrameRecord(
        val device: WledDevice,
        val rgb: ByteArray,
        val ledCount: Int
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as SentFrameRecord
            return device == other.device && ledCount == other.ledCount && rgb.contentEquals(other.rgb)
        }

        override fun hashCode(): Int {
            var result = device.hashCode()
            result = 31 * result + ledCount
            result = 31 * result + rgb.contentHashCode()
            return result
        }
    }

    private val _sentFrames = mutableListOf<SentFrameRecord>()
    val sentFrames: List<SentFrameRecord>
        get() = synchronized(_sentFrames) { _sentFrames.toList() }

    val powerState = mutableMapOf<String, Boolean>()
    var blackoutCalls = 0
        private set
    val blackoutDevices = mutableListOf<List<WledDevice>>()

    var connectionCheckResult: Boolean = true
    val connectionCheckMap = mutableMapOf<String, Boolean>()

    var deviceInfoResult: WledDeviceInfo? = null
    val deviceInfoMap = mutableMapOf<String, WledDeviceInfo?>()

    val flashedDevices = mutableListOf<WledDevice>()

    var isClosed = false
        private set

    var simulateFlashDelay = false

    override fun sendFrame(device: WledDevice, rgb: ByteArray, ledCount: Int): Int {
        synchronized(_sentFrames) {
            _sentFrames.add(SentFrameRecord(device, rgb.copyOf(), ledCount))
        }
        return 1
    }

    fun sendFrame(device: WledDevice, rgb: ByteArray): Int {
        return sendFrame(device, rgb, device.totalLeds)
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
        return connectionCheckMap[ip] ?: connectionCheckResult
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
        repeat(3) {
            for (device in devices) {
                val leds = device.totalLeds
                if (leds > 0) {
                    sendFrame(device, ByteArray(leds * 3), leds)
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
            val white = ByteArray(leds * 3) { 255.toByte() }
            val black = ByteArray(leds * 3)
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

    override fun close() {
        isClosed = true
    }

    fun clear() {
        synchronized(_sentFrames) {
            _sentFrames.clear()
        }
        powerState.clear()
        blackoutCalls = 0
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
        isClosed = false
    }
}
