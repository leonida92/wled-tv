package com.wled.tv.engine

import com.wled.tv.model.WledConfig
import com.wled.tv.model.WledDevice
import com.wled.tv.network.DefaultWledControllerClient
import com.wled.tv.network.WledControllerClient
import com.wled.tv.processing.ScreenColorProcessor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure-Kotlin coordinator for diagnostic LED streaming (solid calibration preview and 30Hz rainbow sweep).
 *
 * Encapsulates:
 * - Pre-allocated, zero-allocation per-device canonical RGB and wire-protocol buffers.
 * - Live capture suppression via [AmbientStreamingEngine.acquireDiagnosticOverride] when an engine is active,
 *   while remaining fully functional standalone when the capture service is stopped.
 * - Real-time reflection of configuration/calibration mutations on active diagnostic streams.
 * - Canonical 3-channel RGB emission via [AmbientStreamingEngine.LiveFrameListener].
 */
class DiagnosticStreamCoordinator(
    initialConfig: WledConfig = WledConfig(),
    private val controllerClient: WledControllerClient = DefaultWledControllerClient.getInstance(),
    private val engineProvider: () -> AmbientStreamingEngine? = { null },
    private val clock: Clock = SystemClock(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    var liveFrameListener: AmbientStreamingEngine.LiveFrameListener? = null

    @Volatile
    private var currentConfig: WledConfig = initialConfig

    @Volatile
    var activePattern: DiagnosticPattern? = null
        private set

    val isRunning: Boolean
        get() = activePattern != null

    private val stateLock = Any()
    private var diagnosticJob: Job? = null
    private var sessionGeneration: Long = 0L
    private var overrideEngine: AmbientStreamingEngine? = null
    private var overrideSession: AmbientStreamingEngine.DiagnosticOverrideSession? = null

    private var rainbowStep: Int = 0
    private val rgbBuffers = HashMap<String, ByteArray>()
    private val wireBuffers = HashMap<String, ByteArray>()

    internal fun getRgbBuffer(deviceId: String): ByteArray? = rgbBuffers[deviceId]
    internal fun getWireBuffer(deviceId: String): ByteArray? = wireBuffers[deviceId]

    /**
     * Atomically updates the configuration used on subsequent diagnostic ticks.
     * Any active solid-color or rainbow stream immediately adopts updated calibration,
     * color order, RGBW mode, or fixture sizing on its next frame.
     */
    fun updateConfig(newConfig: WledConfig) {
        currentConfig = newConfig
    }

    /**
     * Starts streaming [pattern] on [scope] using [ioDispatcher], suppressing live engine capture
     * if an [AmbientStreamingEngine] is currently active.
     */
    fun start(scope: CoroutineScope, pattern: DiagnosticPattern) {
        val myGen: Long
        synchronized(stateLock) {
            myGen = ++sessionGeneration
            diagnosticJob?.cancel()
            activePattern = pattern
            if (pattern is DiagnosticPattern.Rainbow) {
                rainbowStep = 0
            }
            syncEngineOverrideLocked(active = true)
        }

        val job = scope.launch(ioDispatcher) {
            try {
                while (isActive) {
                    val runningPattern = activePattern ?: break
                    syncEngineOverrideIfCurrent(myGen)
                    val delayMs = tick(runningPattern)
                    delay(delayMs)
                }
            } finally {
                synchronized(stateLock) {
                    if (sessionGeneration == myGen) {
                        activePattern = null
                        diagnosticJob = null
                        syncEngineOverrideLocked(active = false)
                    }
                }
            }
        }

        synchronized(stateLock) {
            if (sessionGeneration == myGen) {
                diagnosticJob = job
            } else {
                job.cancel()
            }
        }
    }

    /**
     * Convenience helper to start a 10Hz solid calibration color stream for [deviceId] (or primary fixture).
     */
    fun startSolidColor(
        scope: CoroutineScope,
        r: Int,
        g: Int,
        b: Int,
        deviceId: String? = null
    ) {
        start(scope, DiagnosticPattern.SolidColor(r = r, g = g, b = b, deviceId = deviceId))
    }

    /**
     * Convenience helper to start the 30Hz animated HSV rainbow sweep across enabled fixtures.
     */
    fun startRainbow(scope: CoroutineScope) {
        start(scope, DiagnosticPattern.Rainbow)
    }

    /**
     * Stops any active diagnostic stream and immediately releases the engine diagnostic override session.
     */
    fun stop() {
        synchronized(stateLock) {
            sessionGeneration++
            diagnosticJob?.cancel()
            diagnosticJob = null
            activePattern = null
            syncEngineOverrideLocked(active = false)
        }
    }

    /**
     * Stops any active diagnostic stream and transmits blackout frames to all enabled fixtures.
     */
    suspend fun stopAndBlackout(sendPowerOff: Boolean = false) {
        stop()
        val devices = currentConfig.enabledDevices
        val engine = engineProvider()
        for (i in devices.indices) {
            val dev = devices[i]
            val rgbBuf = rgbBuffers[dev.id]
            rgbBuf?.fill(0)
            wireBuffers[dev.id]?.fill(0)
            if (engine != null && rgbBuf != null) {
                engine.mirrorDiagnosticFrame(dev.id, rgbBuf, dev.totalLeds)
            }
        }
        controllerClient.blackoutFleet(devices, sendPowerOff = sendPowerOff)
    }

    /**
     * Executes a single synchronous diagnostic frame render and UDP dispatch for [patternOverride]
     * (defaults to [activePattern]). Returns the recommended delay in milliseconds until the next tick.
     */
    fun tick(
        patternOverride: DiagnosticPattern? = activePattern
    ): Long {
        val pattern = patternOverride ?: return 100L
        val startUptime = clock.uptimeMillis()
        val cfg = currentConfig
        val mirrorEngine = synchronized(stateLock) { overrideEngine } ?: engineProvider()

        when (pattern) {
            is DiagnosticPattern.SolidColor -> renderSolidColor(cfg, pattern, mirrorEngine)
            is DiagnosticPattern.Rainbow -> renderRainbow(cfg, mirrorEngine)
        }

        val elapsed = clock.uptimeMillis() - startUptime
        return (pattern.tickIntervalMs - elapsed).coerceAtLeast(1L)
    }

    private fun renderSolidColor(
        cfg: WledConfig,
        pattern: DiagnosticPattern.SolidColor,
        mirrorEngine: AmbientStreamingEngine?
    ) {
        val dev = resolveTargetDevice(cfg, pattern.deviceId)
        val leds = dev.totalLeds
        if (leds <= 0) return

        val rgbBuf = ensureRgbBuffer(dev)
        val wireBuf = ensureWireBuffer(dev)

        val c = dev.calibration
        val briScale = c.maxBrightness.coerceIn(0, 255) / 255f
        val rByte = (pattern.r * c.gainR * briScale).roundToInt().coerceIn(0, 255).toByte()
        val gByte = (pattern.g * c.gainG * briScale).roundToInt().coerceIn(0, 255).toByte()
        val bByte = (pattern.b * c.gainB * briScale).roundToInt().coerceIn(0, 255).toByte()

        for (i in 0 until leds) {
            val idx = i * 3
            rgbBuf[idx] = rByte
            rgbBuf[idx + 1] = gByte
            rgbBuf[idx + 2] = bByte
        }

        encodeAndSend(dev, leds, rgbBuf, wireBuf, mirrorEngine)
    }

    private fun renderRainbow(
        cfg: WledConfig,
        mirrorEngine: AmbientStreamingEngine?
    ) {
        val step = rainbowStep
        val devices = cfg.devices
        for (dIdx in devices.indices) {
            val dev = devices[dIdx]
            if (!dev.enabled || dev.ip.isBlank()) continue
            val leds = dev.totalLeds
            if (leds <= 0) continue

            val rgbBuf = ensureRgbBuffer(dev)
            val wireBuf = ensureWireBuffer(dev)

            for (i in 0 until leds) {
                val hue = ((step * 4 + (i * 360 / leds)) % 360).toFloat()
                writeHueRgb(hue, rgbBuf, i * 3)
            }

            encodeAndSend(dev, leds, rgbBuf, wireBuf, mirrorEngine)
        }
        rainbowStep = (step + 1) % 360
    }

    private fun encodeAndSend(
        dev: WledDevice,
        leds: Int,
        rgbBuf: ByteArray,
        wireBuf: ByteArray,
        mirrorEngine: AmbientStreamingEngine?
    ) {
        ScreenColorProcessor.encodeWireFrame(dev, rgbBuf, wireBuf)
        controllerClient.sendFrame(device = dev, wirePayload = wireBuf, ledCount = leds)
        mirrorEngine?.mirrorDiagnosticFrame(dev.id, rgbBuf, leds)
        liveFrameListener?.onDeviceFrameProcessed(dev.id, rgbBuf, leds)
    }

    private fun resolveTargetDevice(cfg: WledConfig, deviceId: String?): WledDevice {
        if (deviceId != null) {
            val devices = cfg.devices
            for (i in devices.indices) {
                val dev = devices[i]
                if (dev.id == deviceId) return dev
            }
        }
        return cfg.primaryDevice
    }

    private fun ensureRgbBuffer(dev: WledDevice): ByteArray {
        val reqSize = dev.totalLeds * 3
        val existing = rgbBuffers[dev.id]
        if (existing != null && existing.size == reqSize) {
            return existing
        }
        val created = ByteArray(reqSize)
        rgbBuffers[dev.id] = created
        return created
    }

    private fun ensureWireBuffer(dev: WledDevice): ByteArray {
        val reqSize = dev.totalLeds * dev.wireBytesPerLed
        val existing = wireBuffers[dev.id]
        if (existing != null && existing.size == reqSize) {
            return existing
        }
        val created = ByteArray(reqSize)
        wireBuffers[dev.id] = created
        return created
    }

    private fun syncEngineOverrideIfCurrent(expectedGen: Long) {
        synchronized(stateLock) {
            if (sessionGeneration == expectedGen) {
                syncEngineOverrideLocked(active = true)
            }
        }
    }

    private fun syncEngineOverrideLocked(active: Boolean) {
        if (!active) {
            overrideSession?.close()
            overrideSession = null
            overrideEngine = null
            return
        }
        val currentEngine = engineProvider()
        if (currentEngine !== overrideEngine) {
            overrideSession?.close()
            overrideEngine = currentEngine
            overrideSession = currentEngine?.acquireDiagnosticOverride()
        }
    }

    companion object {
        /**
         * Pure-Kotlin full-saturation, full-value HSV (S=1, V=1) to canonical 8-bit RGB conversion.
         */
        internal fun writeHueRgb(hueDegrees: Float, outRgb: ByteArray, offset: Int) {
            val normalizedHue = ((hueDegrees % 360f) + 360f) % 360f
            val hPrime = normalizedHue / 60f
            val sector = hPrime.toInt()
            val x = 1f - abs((hPrime % 2f) - 1f)

            val rF: Float
            val gF: Float
            val bF: Float
            when (sector) {
                0 -> { rF = 1f; gF = x; bF = 0f }
                1 -> { rF = x; gF = 1f; bF = 0f }
                2 -> { rF = 0f; gF = 1f; bF = x }
                3 -> { rF = 0f; gF = x; bF = 1f }
                4 -> { rF = x; gF = 0f; bF = 1f }
                else -> { rF = 1f; gF = 0f; bF = x }
            }

            outRgb[offset] = (rF * 255f).roundToInt().coerceIn(0, 255).toByte()
            outRgb[offset + 1] = (gF * 255f).roundToInt().coerceIn(0, 255).toByte()
            outRgb[offset + 2] = (bF * 255f).roundToInt().coerceIn(0, 255).toByte()
        }
    }
}
