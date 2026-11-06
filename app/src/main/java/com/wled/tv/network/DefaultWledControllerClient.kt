package com.wled.tv.network

import android.util.Log
import com.wled.tv.model.WledDevice
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Production implementation of [WledControllerClient].
 *
 * Holds shared, connection-pooled socket and HTTP client delegates, encapsulates
 * UDP protocol formatting and HTTP device control, executes concurrent fleet blackouts,
 * and manages an application-scoped singleton lifecycle with a test-injection seam.
 */
class DefaultWledControllerClient internal constructor(
    private val udpSender: WledUdpSender = WledUdpSender(),
    private val httpClient: WledHttpClient = WledHttpClient()
) : WledControllerClient {

    override fun sendFrame(device: WledDevice, rgb: ByteArray, ledCount: Int): Int {
        return udpSender.sendDeviceFrame(device, rgb, ledCount)
    }

    override suspend fun wakeAndSetBrightness(device: WledDevice): Boolean {
        return wakeAndSetBrightness(device.ip, device.calibration.maxBrightness)
    }

    override suspend fun wakeAndSetBrightness(ip: String, brightness: Int): Boolean {
        return httpClient.wakeAndSetBrightness(ip, brightness)
    }

    override suspend fun turnOff(ip: String): Boolean {
        return httpClient.turnOff(ip)
    }

    override suspend fun checkConnection(ip: String): Boolean {
        return httpClient.checkConnection(ip)
    }

    override suspend fun fetchDeviceInfo(ip: String): WledDeviceInfo? {
        return httpClient.fetchDeviceInfo(ip)
    }

    override suspend fun blackoutFleet(devices: List<WledDevice>, sendPowerOff: Boolean) {
        // 1. Immediately send zeroed UDP bursts sequentially to all devices
        for (device in devices) {
            val leds = device.totalLeds
            if (leds > 0) {
                val blackBuf = ByteArray(leds * 3)
                repeat(3) {
                    sendFrame(device, blackBuf, leds)
                }
            }
        }

        // 2. Concurrently dispatch HTTP turnOff requests with timeout safeguards
        if (sendPowerOff) {
            val distinctIps = devices.map { it.ip.trim() }.filter { it.isNotBlank() }.distinct()
            if (distinctIps.isNotEmpty()) {
                coroutineScope {
                    distinctIps.map { ip ->
                        async {
                            try {
                                withTimeoutOrNull(HTTP_TIMEOUT_MS) {
                                    httpClient.turnOff(ip)
                                } ?: false
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed or timed out turning off WLED fixture at $ip: ${e.message}")
                                false
                            }
                        }
                    }.awaitAll()
                }
            }
        }
    }

    override suspend fun flashDevice(device: WledDevice, durationMs: Long) {
        val leds = device.totalLeds
        if (leds <= 0) return

        val whiteFrame = ByteArray(leds * 3) { 255.toByte() }
        val blackFrame = ByteArray(leds * 3)

        sendFrame(device, whiteFrame, leds)
        delay(durationMs)
        sendFrame(device, blackFrame, leds)
    }

    override fun close() {
        udpSender.close()
    }

    companion object {
        private const val TAG = "DefaultWledClient"
        private const val HTTP_TIMEOUT_MS = 2000L

        @Volatile
        private var instance: WledControllerClient? = null

        fun getInstance(): WledControllerClient {
            return instance ?: synchronized(this) {
                instance ?: DefaultWledControllerClient().also { instance = it }
            }
        }

        fun setInstanceForTesting(client: WledControllerClient?) {
            synchronized(this) {
                instance = client
            }
        }
    }
}
