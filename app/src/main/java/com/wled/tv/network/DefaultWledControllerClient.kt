package com.wled.tv.network

import android.content.Context
import android.util.Log
import com.wled.tv.model.WledDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Production implementation of [WledControllerClient].
 *
 * Holds shared, connection-pooled socket and HTTP client delegates, encapsulates
 * UDP protocol formatting and HTTP device control, tracks instance-scoped fixture
 * reachability via [reachabilityFlow], executes concurrent fleet blackouts,
 * delegates local network discovery to a pluggable [WledDiscoveryProvider],
 * and manages an application-scoped singleton lifecycle with a test-injection seam.
 */
class DefaultWledControllerClient internal constructor(
    private val udpSender: WledUdpSender = WledUdpSender(),
    private val httpClient: WledHttpClient = WledHttpClient(),
    @Volatile private var discoveryProvider: WledDiscoveryProvider? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : WledControllerClient {

    private val _reachabilityFlow = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    override val reachabilityFlow: StateFlow<Map<String, Boolean>> = _reachabilityFlow.asStateFlow()

    internal fun setDiscoveryProvider(provider: WledDiscoveryProvider) {
        this.discoveryProvider = provider
    }

    internal fun getDiscoveryProvider(): WledDiscoveryProvider? = discoveryProvider

    override fun sendFrame(device: WledDevice, wirePayload: ByteArray, ledCount: Int): Int {
        return udpSender.sendDeviceFrame(device, wirePayload, ledCount)
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
        val cleanIp = ip.trim()
        val reachable = httpClient.checkConnection(cleanIp)
        if (cleanIp.isNotEmpty()) {
            _reachabilityFlow.update { current -> current + (cleanIp to reachable) }
        }
        return reachable
    }

    override suspend fun probeReachability(devices: List<WledDevice>): Map<String, Boolean> = withContext(ioDispatcher) {
        val uniqueIps = devices.map { it.ip.trim() }.filter { it.isNotEmpty() }.distinct()
        val results = if (uniqueIps.isEmpty()) {
            emptyMap()
        } else {
            coroutineScope {
                uniqueIps.map { ip ->
                    async {
                        val reachable = try {
                            httpClient.checkConnection(ip)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            false
                        }
                        ip to reachable
                    }
                }.awaitAll().toMap()
            }
        }
        _reachabilityFlow.value = results
        results
    }

    override suspend fun fetchDeviceInfo(ip: String): WledDeviceInfo? {
        return httpClient.fetchDeviceInfo(ip)
    }

    override fun discoverDevices(): Flow<DiscoveredWled> {
        return discoveryProvider?.discover() ?: emptyFlow()
    }

    override suspend fun blackoutFleet(devices: List<WledDevice>, sendPowerOff: Boolean): Unit = withContext(ioDispatcher) {
        // 1. Immediately send zeroed UDP bursts sequentially to all devices
        for (device in devices) {
            val leds = device.totalLeds
            if (leds > 0) {
                val blackBuf = ByteArray(leds * device.wireBytesPerLed)
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

    override suspend fun flashDevice(device: WledDevice, durationMs: Long): Unit = withContext(ioDispatcher) {
        val leds = device.totalLeds
        if (leds <= 0) return@withContext

        val frameSize = leds * device.wireBytesPerLed
        val whiteFrame = ByteArray(frameSize) { 255.toByte() }
        val blackFrame = ByteArray(frameSize)

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

        /**
         * Returns the application-scoped [WledControllerClient] singleton.
         * When [context] is provided, lazily wires [NsdDiscoveryProvider] once if not already configured.
         */
        fun getInstance(context: Context? = null): WledControllerClient {
            val existing = instance
            if (existing != null && (context == null || existing !is DefaultWledControllerClient || existing.getDiscoveryProvider() != null)) {
                return existing
            }
            return synchronized(this) {
                val current = instance ?: DefaultWledControllerClient(
                    discoveryProvider = context?.applicationContext?.let { NsdDiscoveryProvider(it) }
                ).also { instance = it }
                if (context != null && current is DefaultWledControllerClient && current.getDiscoveryProvider() == null) {
                    current.setDiscoveryProvider(NsdDiscoveryProvider(context.applicationContext))
                }
                current
            }
        }

        fun setInstanceForTesting(client: WledControllerClient?) {
            synchronized(this) {
                instance = client
            }
        }
    }
}
