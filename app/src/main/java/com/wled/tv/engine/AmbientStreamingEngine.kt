package com.wled.tv.engine

import android.graphics.RectF
import com.wled.tv.model.DeviceThroughputStats
import com.wled.tv.model.WledDevice
import com.wled.tv.model.ThroughputHistoryPoint
import com.wled.tv.model.ThroughputStats
import com.wled.tv.model.WledConfig
import com.wled.tv.network.WledHttpClient
import com.wled.tv.network.WledUdpSender
import com.wled.tv.processing.ScreenColorProcessor
import kotlinx.coroutines.delay
import java.nio.ByteBuffer

data class DeviceOverlaySnapshot(
    val deviceId: String,
    val deviceName: String,
    val zones: List<RectF>,
    val rgb: ByteArray,
    val ledCount: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DeviceOverlaySnapshot
        if (deviceId != other.deviceId) return false
        if (deviceName != other.deviceName) return false
        if (zones != other.zones) return false
        if (!rgb.contentEquals(other.rgb)) return false
        if (ledCount != other.ledCount) return false
        return true
    }

    override fun hashCode(): Int {
        var result = deviceId.hashCode()
        result = 31 * result + deviceName.hashCode()
        result = 31 * result + zones.hashCode()
        result = 31 * result + rgb.contentHashCode()
        result = 31 * result + ledCount
        return result
    }
}

data class EngineOverlaySnapshot(
    val devices: List<DeviceOverlaySnapshot>,
    val topCrop: Float,
    val bottomCrop: Float,
    val leftCrop: Float,
    val rightCrop: Float,
    val fps: Float,
    val pps: Int,
    val isDrmActive: Boolean
)

/**
 * Deterministic, in-process engine coordinating video frame color sampling,
 * temporal smoothing step dispatch, multi-protocol UDP transmission,
 * Widevine L1 DRM blackout detection, and 3-hour throughput telemetry tracking.
 *
 * Guarantees zero buffer allocations in the steady 60Hz streaming loop.
 */
class AmbientStreamingEngine(
    initialConfig: WledConfig = WledConfig(),
    val colorProcessor: ScreenColorProcessor = ScreenColorProcessor(),
    val udpSender: WledUdpSender = WledUdpSender(),
    val httpClient: WledHttpClient = WledHttpClient(),
    private val clock: Clock = SystemClock(),
    private val isAudioActive: () -> Boolean = { false }
) {

    fun interface LiveFrameListener {
        fun onDeviceFrameProcessed(deviceId: String, rgb: ByteArray, count: Int)
    }

    fun interface ThroughputListener {
        fun onThroughputStatsUpdated(stats: ThroughputStats)
    }

    var liveFrameListener: LiveFrameListener? = null
    var throughputListener: ThroughputListener? = null

    var config: WledConfig = initialConfig
        private set

    @Volatile
    var isTestingOverride: Boolean = false

    @Volatile
    var isDrmActive: Boolean = false
        private set

    @Volatile
    var latestStats: ThroughputStats? = null
        private set

    internal val rawTargetBuffers = HashMap<String, ByteArray>()
    internal val deviceBuffers = HashMap<String, ByteArray>()

    fun getRawBuffer(deviceId: String): ByteArray? = rawTargetBuffers[deviceId]
    fun getDeviceBuffer(deviceId: String): ByteArray? = deviceBuffers[deviceId]

    /**
     * Extracts an immutable snapshot of a device's active sampling zones and smoothed color buffer
     * for telemetry HUD and wireframe visualizers without exposing internal processor components.
     */
    fun getOverlayData(device: WledDevice): DeviceOverlaySnapshot? {
        val leds = device.totalLeds
        val buf = deviceBuffers[device.id] ?: return null
        if (leds <= 0) return null
        val zones = colorProcessor.getActiveZones(device)
        val rgbCopy = buf.copyOf(leds * 3)
        return DeviceOverlaySnapshot(
            deviceId = device.id,
            deviceName = device.name,
            zones = zones,
            rgb = rgbCopy,
            ledCount = leds
        )
    }

    /**
     * Captures a unified overlay snapshot for all enabled fixtures and active letterbox / crop metrics.
     */
    fun getOverlaySnapshot(): EngineOverlaySnapshot {
        val snapshots = config.enabledDevices.mapNotNull { getOverlayData(it) }
        val fps = latestStats?.fps?.toFloat() ?: 0f
        val pps = latestStats?.pps ?: 0
        return EngineOverlaySnapshot(
            devices = snapshots,
            topCrop = colorProcessor.currentTopCrop,
            bottomCrop = colorProcessor.currentBottomCrop,
            leftCrop = colorProcessor.currentLeftCrop,
            rightCrop = colorProcessor.currentRightCrop,
            fps = fps,
            pps = pps,
            isDrmActive = isDrmActive
        )
    }

    private var lastFrameTime = 0L
    private var lastSendTime = 0L
    private var lastLivePreviewTime = 0L
    private var blackWithAudioStartTime = 0L
    private var lastAudioCheckTime = 0L
    private var cachedIsAudioPlaying = false
    private var lastDrmKeepaliveTime = 0L

    private var statsFramesInCurrentSecond = 0
    private var statsPacketsInCurrentSecond = 0
    private var statsTotalPackets = 0L
    private val statsPerDevicePacketsInSecond = HashMap<String, Int>()
    private val statsPerDeviceTotalPackets = HashMap<String, Long>()
    private var lastStatsCalculationTime = 0L

    private val historyLock = Any()
    private val historyBuffer = ArrayDeque<ThroughputHistoryPoint>(MAX_HISTORY_SECONDS)

    companion object {
        const val MAX_HISTORY_SECONDS = 10800 // 3 hours
    }

    init {
        updateConfig(initialConfig)
    }

    /**
     * Atomically synchronizes active fixtures with the engine, allocating or resizing
     * color buffers only when fixture LED counts change.
     */
    fun updateConfig(newConfig: WledConfig) {
        this.config = newConfig
        val enabledIds = newConfig.enabledDevices.map { it.id }.toSet()
        rawTargetBuffers.keys.retainAll(enabledIds)
        deviceBuffers.keys.retainAll(enabledIds)

        for (dev in newConfig.enabledDevices) {
            val reqSize = dev.totalLeds * 3
            if (reqSize > 0) {
                val curRaw = rawTargetBuffers[dev.id]
                if (curRaw == null || curRaw.size != reqSize) {
                    rawTargetBuffers[dev.id] = ByteArray(reqSize)
                }
                val curSmooth = deviceBuffers[dev.id]
                if (curSmooth == null || curSmooth.size != reqSize) {
                    deviceBuffers[dev.id] = ByteArray(reqSize)
                }
            }
        }
    }

    /**
     * Intake for a video graphic buffer from ImageReader or virtual display.
     * Regulates frame intake to target FPS (with 3ms jitter tolerance), evaluates
     * Widevine DRM blackouts, and updates pre-allocated raw color buffers.
     *
     * Returns true if the frame was processed, or false if dropped/suppressed.
     */
    fun onFrameAvailable(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        nowMs: Long = clock.currentTimeMillis()
    ): Boolean {
        if (isTestingOverride) {
            return false
        }

        val targetFps = config.calibration.fps.coerceIn(15, 60)
        val minFrameInterval = (1000L / targetFps) - 3L
        if (lastFrameTime != 0L && nowMs - lastFrameTime < minFrameInterval) {
            return false
        }
        lastFrameTime = nowMs
        statsFramesInCurrentSecond++

        val isFrameBlack = colorProcessor.isFrameCompletelyBlack(buffer, width, height, rowStride, pixelStride)

        if (isFrameBlack) {
            val isAudioPlaying = if (lastAudioCheckTime == 0L || nowMs - lastAudioCheckTime >= 500L) {
                lastAudioCheckTime = nowMs
                val active = isAudioActive()
                cachedIsAudioPlaying = active
                active
            } else {
                cachedIsAudioPlaying
            }

            if (isAudioPlaying) {
                if (blackWithAudioStartTime == 0L) {
                    blackWithAudioStartTime = nowMs
                } else if (nowMs - blackWithAudioStartTime >= 5000L) {
                    isDrmActive = true
                }
            } else {
                blackWithAudioStartTime = 0L
                if (isDrmActive) isDrmActive = false
            }

            val devices = config.enabledDevices
            for (device in devices) {
                val leds = device.totalLeds
                if (leds <= 0) continue
                val requiredSize = leds * 3
                var rawBuf = rawTargetBuffers[device.id]
                if (rawBuf == null || rawBuf.size != requiredSize) {
                    rawBuf = ByteArray(requiredSize)
                    rawTargetBuffers[device.id] = rawBuf
                } else {
                    rawBuf.fill(0)
                }
            }
        } else {
            blackWithAudioStartTime = 0L
            if (isDrmActive) {
                isDrmActive = false
                lastDrmKeepaliveTime = 0L
            }

            val anyAutoLetterbox = config.enabledDevices.any { it.perimeter.autoLetterbox }
            if (anyAutoLetterbox) {
                colorProcessor.scanLetterbox(buffer, width, height, rowStride, pixelStride)
            }

            val devices = config.enabledDevices
            for (device in devices) {
                val leds = device.totalLeds
                if (leds <= 0) continue

                val requiredSize = leds * 3
                var rawBuf = rawTargetBuffers[device.id]
                if (rawBuf == null || rawBuf.size != requiredSize) {
                    rawBuf = ByteArray(requiredSize)
                    rawTargetBuffers[device.id] = rawBuf
                }

                colorProcessor.extractRawColors(
                    buffer = buffer,
                    width = width,
                    height = height,
                    rowStride = rowStride,
                    pixelStride = pixelStride,
                    device = device,
                    calibration = device.calibration,
                    outputRawRgb = rawBuf
                )
            }
        }
        return true
    }

    /**
     * Executes one streaming tick: applies temporal smoothing filters,
     * transmits UDP packets to active fixtures, handles DRM keepalive throttling (1500ms),
     * and evaluates throughput metrics.
     *
     * Returns the recommended delay in milliseconds until the next tick.
     */
    fun tickStreaming(nowMs: Long = clock.currentTimeMillis()): Long {
        val targetFps = config.calibration.fps.coerceIn(15, 60)
        val tickIntervalMs = (1000L / targetFps).coerceAtLeast(10L)
        val startUptime = clock.uptimeMillis()

        if (!isTestingOverride) {
            val devices = config.enabledDevices
            val shouldSendPreview = liveFrameListener != null && (nowMs - lastLivePreviewTime >= 50L)

            val isDrmKeepaliveDue = isDrmActive && (lastDrmKeepaliveTime == 0L || nowMs - lastDrmKeepaliveTime >= 1500L)
            val skipDrmFrame = isDrmActive && !isDrmKeepaliveDue

            if (!skipDrmFrame) {
                if (isDrmActive && isDrmKeepaliveDue) {
                    lastDrmKeepaliveTime = nowMs
                }

                for (device in devices) {
                    val leds = device.totalLeds
                    if (leds <= 0) continue

                    val requiredSize = leds * 3
                    val rawBuf = rawTargetBuffers[device.id] ?: continue
                    if (rawBuf.size < requiredSize) continue

                    var smoothBuf = deviceBuffers[device.id]
                    if (smoothBuf == null || smoothBuf.size != requiredSize) {
                        smoothBuf = ByteArray(requiredSize)
                        deviceBuffers[device.id] = smoothBuf
                    }

                    // Step temporal smoothing filter towards target colors
                    colorProcessor.applySmoothing(
                        deviceId = device.id,
                        rawRgb = rawBuf,
                        ledCount = leds,
                        smoothingFactor = device.calibration.smoothingFactor,
                        outputRgb = smoothBuf
                    )

                    // Transmit smoothed frame to WLED via configured protocol (DDP / DRGB / WARLS)
                    val packetsSent = udpSender.sendFrame(
                        ip = device.ip,
                        port = device.port,
                        protocol = device.protocol,
                        rgb = smoothBuf,
                        ledCount = leds,
                        colorOrder = device.calibration.colorOrder,
                        isRgbw = device.calibration.isRgbw,
                        rgbwMode = device.calibration.rgbwMode,
                        timeoutSeconds = 5
                    )
                    if (packetsSent > 0) {
                        recordPacketSent(device.id, packetsSent)
                    }

                    if (shouldSendPreview) {
                        val previewCopy = smoothBuf.copyOf(leds * 3)
                        liveFrameListener?.onDeviceFrameProcessed(device.id, previewCopy, leds)
                    }
                }
                if (shouldSendPreview) {
                    lastLivePreviewTime = nowMs
                }
                lastSendTime = nowMs
            }
        }

        checkAndEmitThroughputStats(nowMs)

        val elapsed = clock.uptimeMillis() - startUptime
        return (tickIntervalMs - elapsed).coerceAtLeast(1L)
    }

    private fun recordPacketSent(deviceId: String, count: Int = 1) {
        statsPacketsInCurrentSecond += count
        statsTotalPackets += count
        statsPerDevicePacketsInSecond[deviceId] = (statsPerDevicePacketsInSecond[deviceId] ?: 0) + count
        statsPerDeviceTotalPackets[deviceId] = (statsPerDeviceTotalPackets[deviceId] ?: 0L) + count.toLong()
    }

    /**
     * Evaluates FPS and PPS throughput counters over 1-second interval boundaries.
     */
    fun checkAndEmitThroughputStats(now: Long = clock.currentTimeMillis()) {
        if (lastStatsCalculationTime == 0L) {
            lastStatsCalculationTime = now
            return
        }
        val elapsed = now - lastStatsCalculationTime
        if (elapsed >= 1000L) {
            val actualFps = ((statsFramesInCurrentSecond * 1000L) / elapsed).toInt()
            val actualPps = ((statsPacketsInCurrentSecond * 1000L) / elapsed).toInt()
            val targetFps = config.calibration.fps.coerceIn(15, 60)

            val deviceStatsList = config.enabledDevices.map { dev ->
                val devPps = (((statsPerDevicePacketsInSecond[dev.id] ?: 0) * 1000L) / elapsed).toInt()
                val devTotal = statsPerDeviceTotalPackets[dev.id] ?: 0L
                DeviceThroughputStats(
                    deviceId = dev.id,
                    deviceName = dev.name,
                    ip = dev.ip,
                    pps = devPps,
                    totalPackets = devTotal
                )
            }

            val stats = ThroughputStats(
                fps = actualFps,
                targetFps = targetFps,
                pps = actualPps,
                totalPackets = statsTotalPackets,
                deviceStats = deviceStatsList
            )
            latestStats = stats

            recordHistoryPoint(ThroughputHistoryPoint(timestamp = now, fps = actualFps, pps = actualPps))
            throughputListener?.onThroughputStatsUpdated(stats)

            statsFramesInCurrentSecond = 0
            statsPacketsInCurrentSecond = 0
            statsPerDevicePacketsInSecond.clear()
            lastStatsCalculationTime = now
        }
    }

    /**
     * Appends a throughput telemetry point to the 3-hour FIFO ring buffer.
     */
    fun recordHistoryPoint(point: ThroughputHistoryPoint) {
        synchronized(historyLock) {
            if (historyBuffer.size >= MAX_HISTORY_SECONDS) {
                historyBuffer.removeFirst()
            }
            historyBuffer.addLast(point)
        }
    }

    /**
     * Returns a snapshot of throughput history points within the specified window in seconds.
     */
    fun getHistorySnapshot(windowSeconds: Int = MAX_HISTORY_SECONDS): List<ThroughputHistoryPoint> {
        val cutoff = clock.currentTimeMillis() - (windowSeconds * 1000L)
        return synchronized(historyLock) {
            historyBuffer.filter { it.timestamp >= cutoff }
        }
    }

    /**
     * Clears all throughput history points.
     */
    fun clearHistory() {
        synchronized(historyLock) {
            historyBuffer.clear()
        }
    }

    /**
     * Resets throughput counters and telemetry calculations.
     */
    fun resetStats() {
        statsFramesInCurrentSecond = 0
        statsPacketsInCurrentSecond = 0
        statsTotalPackets = 0L
        statsPerDevicePacketsInSecond.clear()
        statsPerDeviceTotalPackets.clear()
        lastStatsCalculationTime = 0L
        latestStats = null
    }

    /**
     * Transmits a burst of zeroed black frames to all enabled devices,
     * resets smoothing filters and letterbox states, and optionally powers off WLED fixtures via HTTP.
     */
    suspend fun blackout(sendPowerOff: Boolean = true) {
        val devices = config.enabledDevices
        for (device in devices) {
            deviceBuffers[device.id]?.fill(0)
            rawTargetBuffers[device.id]?.fill(0)
        }
        colorProcessor.reset()

        for (device in devices) {
            val leds = device.totalLeds
            if (leds > 0) {
                val blackBuf = deviceBuffers[device.id] ?: ByteArray(leds * 3)
                for (i in 0..2) {
                    udpSender.sendFrame(
                        ip = device.ip,
                        port = device.port,
                        protocol = device.protocol,
                        rgb = blackBuf,
                        ledCount = leds,
                        colorOrder = device.calibration.colorOrder,
                        isRgbw = device.calibration.isRgbw,
                        rgbwMode = device.calibration.rgbwMode,
                        timeoutSeconds = 1
                    )
                    delay(30L)
                }
            }
            if (sendPowerOff) {
                httpClient.turnOff(device.ip)
            }
        }
    }

    /**
     * Closes underlying network sockets and resources.
     */
    fun close() {
        udpSender.close()
    }
}
