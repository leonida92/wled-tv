package com.wled.tv.processing

import android.graphics.RectF
import android.media.Image
import com.wled.tv.model.ColorCalibration
import com.wled.tv.model.DeviceType
import com.wled.tv.model.PerimeterConfig
import com.wled.tv.model.WledDevice
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

class ScreenColorProcessor {

    private val smoothingFilters = HashMap<String, ColorSmoothingFilter>()
    private var rawRgbBuffer = ByteArray(0)

    // Dynamic Letterbox Auto-Detection state
    private var detectedTopCrop: Float = 0.0f
    private var detectedBottomCrop: Float = 0.0f
    private var candidateTopCrop: Float = 0.0f
    private var candidateBottomCrop: Float = 0.0f
    private var stableFrameCount: Int = 0

    // Dynamic Pillarbox (4:3) Auto-Detection state
    private var detectedLeftCrop: Float = 0.0f
    private var detectedRightCrop: Float = 0.0f
    private var candidateLeftCrop: Float = 0.0f
    private var candidateRightCrop: Float = 0.0f
    private var stablePillarFrameCount: Int = 0

    val currentTopCrop: Float get() = detectedTopCrop
    val currentBottomCrop: Float get() = detectedBottomCrop
    val currentLeftCrop: Float get() = detectedLeftCrop
    val currentRightCrop: Float get() = detectedRightCrop

    @Volatile
    var isOverlayActive: Boolean = false

    // Zero-allocation caches
    private val cachedPerimeterZones = HashMap<String, Pair<PerimeterConfig, List<RectF>>>()
    private val cachedAmbientSubZones = HashMap<String, Pair<RectF, List<RectF>>>()
    private val cachedLuts = HashMap<ColorCalibration, CalibrationLut>()
    private var cachedProbeWidth: Int = -1
    private var cachedProbeX: IntArray = IntArray(0)
    private var cachedOuterIndices: IntArray = IntArray(0)
    private var cachedProbeHeight: Int = -1
    private var cachedProbeY: IntArray = IntArray(0)

    fun reset() {
        smoothingFilters.clear()
        detectedTopCrop = 0.0f
        detectedBottomCrop = 0.0f
        candidateTopCrop = 0.0f
        candidateBottomCrop = 0.0f
        stableFrameCount = 0

        detectedLeftCrop = 0.0f
        detectedRightCrop = 0.0f
        candidateLeftCrop = 0.0f
        candidateRightCrop = 0.0f
        stablePillarFrameCount = 0

        cachedPerimeterZones.clear()
        cachedAmbientSubZones.clear()
        cachedLuts.clear()
        cachedProbeWidth = -1
        cachedProbeX = IntArray(0)
        cachedOuterIndices = IntArray(0)
        cachedProbeHeight = -1
        cachedProbeY = IntArray(0)
        isOverlayActive = false
    }

    /**
     * Extracts chroma-weighted colors from an Image for a specific WLED device.
     */
    fun processDevice(
        image: Image,
        device: WledDevice,
        calibration: ColorCalibration,
        outputRgb: ByteArray
    ): Boolean {
        val planes = image.planes
        if (planes.isEmpty()) return false

        val plane = planes[0]
        val buffer = plane.buffer ?: return false
        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        return processDevice(
            buffer = buffer,
            width = width,
            height = height,
            rowStride = rowStride,
            pixelStride = pixelStride,
            device = device,
            calibration = calibration,
            outputRgb = outputRgb
        )
    }

    fun scanLetterbox(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int) {
        detectLetterbox(buffer, width, height, rowStride, pixelStride)
        detectPillarbox(buffer, width, height, rowStride, pixelStride)
    }

    /**
     * Extracts colors for a specific WLED device based on its configured type and region,
     * executing both spatial sampling and temporal smoothing.
     */
    fun processDevice(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        device: WledDevice,
        calibration: ColorCalibration,
        outputRgb: ByteArray
    ): Boolean {
        if (width <= 0 || height <= 0) return false
        val ledCount = device.totalLeds.coerceAtLeast(1)
        val requiredRawSize = ledCount * 3
        if (rawRgbBuffer.size < requiredRawSize) {
            rawRgbBuffer = ByteArray(requiredRawSize)
        }

        val success = extractRawColors(
            buffer = buffer,
            width = width,
            height = height,
            rowStride = rowStride,
            pixelStride = pixelStride,
            device = device,
            calibration = calibration,
            outputRawRgb = rawRgbBuffer
        )
        if (!success) return false

        applySmoothing(
            deviceId = device.id,
            rawRgb = rawRgbBuffer,
            ledCount = ledCount,
            smoothingFactor = calibration.smoothingFactor,
            outputRgb = outputRgb
        )
        return true
    }

    /**
     * Returns the active normalized RectF zones for a device based on current letterbox & pillarbox detection.
     */
    fun getActiveZones(device: WledDevice): List<RectF> {
        val topCrop = if (device.perimeter.autoLetterbox) detectedTopCrop else device.perimeter.topCrop
        val bottomCrop = if (device.perimeter.autoLetterbox) detectedBottomCrop else device.perimeter.bottomCrop
        val leftCrop = if (device.perimeter.autoLetterbox) detectedLeftCrop else device.perimeter.leftCrop
        val rightCrop = if (device.perimeter.autoLetterbox) detectedRightCrop else device.perimeter.rightCrop

        if (device.type == DeviceType.PERIMETER || (device.perimeter.totalLeds > 0 && device.perimeter.totalLeds == device.totalLeds)) {
            val dynamicPerimeter = device.perimeter.copy(
                topCrop = topCrop,
                bottomCrop = bottomCrop,
                leftCrop = leftCrop,
                rightCrop = rightCrop
            )
            val cached = cachedPerimeterZones[device.id]
            return if (cached != null && cached.first == dynamicPerimeter) {
                cached.second
            } else {
                val computed = dynamicPerimeter.computeLedZones()
                cachedPerimeterZones[device.id] = Pair(dynamicPerimeter, computed)
                computed
            }
        } else {
            val activeTop = topCrop.coerceIn(0f, 0.40f)
            val activeBottom = (1.0f - bottomCrop).coerceIn(0.60f, 1.0f)
            val activeLeft = leftCrop.coerceIn(0f, 0.40f)
            val activeRight = (1.0f - rightCrop).coerceIn(0.60f, 1.0f)
            val activeHeight = (activeBottom - activeTop).coerceAtLeast(0.1f)
            val activeWidth = (activeRight - activeLeft).coerceAtLeast(0.1f)

            val region = when (device.type) {
                DeviceType.PERIMETER -> rect(activeLeft, activeTop, activeRight, activeBottom)
                DeviceType.LEFT_AMBIENT -> rect(activeLeft, activeTop, activeLeft + 0.25f * activeWidth, activeBottom)
                DeviceType.RIGHT_AMBIENT -> rect(activeRight - 0.25f * activeWidth, activeTop, activeRight, activeBottom)
                DeviceType.TOP_AMBIENT -> rect(activeLeft, activeTop, activeRight, activeTop + 0.25f * activeHeight)
                DeviceType.BOTTOM_AMBIENT -> rect(activeLeft, activeBottom - 0.25f * activeHeight, activeRight, activeBottom)
                DeviceType.FULL_SCREEN -> rect(activeLeft, activeTop, activeRight, activeBottom)
                DeviceType.CUSTOM_BOX -> device.customRect
            }

            val ledCount = device.totalLeds.coerceAtLeast(1)
            if (ledCount == 1) {
                return listOf(region)
            } else {
                val cached = cachedAmbientSubZones[device.id]
                return if (cached != null && cached.first == region && cached.second.size == ledCount) {
                    cached.second
                } else {
                    val computed = computeAmbientSubZones(device.type, region, ledCount)
                    cachedAmbientSubZones[device.id] = Pair(region, computed)
                    computed
                }
            }
        }
    }

    /**
     * Extracts raw chroma-weighted RGB colors for a specific WLED device into outputRawRgb without temporal smoothing.
     * Used by the decoupled frame acquisition pipeline to sample target colors when a new video frame arrives.
     */
    fun extractRawColors(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        device: WledDevice,
        calibration: ColorCalibration,
        outputRawRgb: ByteArray
    ): Boolean {
        if (width <= 0 || height <= 0) return false
        val activeZones = getActiveZones(device)
        val ledCount = activeZones.size
        if (ledCount == 0 || outputRawRgb.size < ledCount * 3) return false

        for (i in 0 until ledCount) {
            sampleChromaWeightedBox(
                buffer = buffer,
                width = width,
                height = height,
                rowStride = rowStride,
                pixelStride = pixelStride,
                zone = activeZones[i],
                calibration = calibration,
                outBuffer = outputRawRgb,
                outOffset = i * 3
            )
        }
        return true
    }

    /**
     * Applies temporal smoothing (Adaptive Dual-Speed EMA) to pre-sampled raw RGB colors.
     * Used by the steady-cadence (e.g. 60Hz) streaming ticker.
     */
    fun applySmoothing(
        deviceId: String,
        rawRgb: ByteArray,
        ledCount: Int,
        smoothingFactor: Float,
        outputRgb: ByteArray
    ) {
        val filter = smoothingFilters.getOrPut(deviceId) { ColorSmoothingFilter() }
        filter.apply(rawRgb, ledCount, smoothingFactor, outputRgb)
    }

    private fun rect(l: Float, t: Float, r: Float, b: Float): RectF = RectF(l, t, r, b).apply {
        left = l
        top = t
        right = r
        bottom = b
    }

    private fun computeAmbientSubZones(type: DeviceType, region: RectF, ledCount: Int): List<RectF> {
        val list = ArrayList<RectF>(ledCount)
        for (i in 0 until ledCount) {
            val subZone = when (type) {
                DeviceType.LEFT_AMBIENT -> {
                    val step = (region.bottom - region.top) / ledCount
                    val b = region.bottom - (i * step)
                    val t = b - step
                    rect(region.left, t.coerceAtLeast(region.top), region.right, b.coerceAtMost(region.bottom))
                }
                DeviceType.RIGHT_AMBIENT -> {
                    val step = (region.bottom - region.top) / ledCount
                    val b = region.bottom - (i * step)
                    val t = b - step
                    rect(region.left, t.coerceAtLeast(region.top), region.right, b.coerceAtMost(region.bottom))
                }
                DeviceType.TOP_AMBIENT -> {
                    val step = (region.right - region.left) / ledCount
                    val l = region.left + (i * step)
                    val r = l + step
                    rect(l.coerceAtLeast(region.left), region.top, r.coerceAtMost(region.right), region.bottom)
                }
                DeviceType.BOTTOM_AMBIENT -> {
                    val step = (region.right - region.left) / ledCount
                    val l = region.left + (i * step)
                    val r = l + step
                    rect(l.coerceAtLeast(region.left), region.top, r.coerceAtMost(region.right), region.bottom)
                }
                else -> {
                    val step = (region.right - region.left) / ledCount
                    val l = region.left + (i * step)
                    val r = l + step
                    rect(l.coerceAtLeast(region.left), region.top, r.coerceAtMost(region.right), region.bottom)
                }
            }
            list.add(subZone)
        }
        return list
    }

    fun sampleChromaWeightedBox(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        zone: RectF,
        calibration: ColorCalibration
    ): ByteArray {
        val rgb = ByteArray(3)
        sampleChromaWeightedBox(buffer, width, height, rowStride, pixelStride, zone, calibration, rgb, 0)
        return rgb
    }

    fun sampleChromaWeightedBox(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        zone: RectF,
        calibration: ColorCalibration,
        outBuffer: ByteArray,
        outOffset: Int
    ) {
        val x0 = (zone.left * width).toInt().coerceIn(0, width - 1)
        val x1 = (zone.right * width).toInt().coerceIn(x0 + 1, width)
        val y0 = (zone.top * height).toInt().coerceIn(0, height - 1)
        val y1 = (zone.bottom * height).toInt().coerceIn(y0 + 1, height)

        val zoneW = max(1, x1 - x0)
        val zoneH = max(1, y1 - y0)

        // Inset by 1 pixel to strictly avoid wireframe borders on edges
        val sampleX0 = if (zoneW >= 4) x0 + 1 else x0
        val sampleX1 = if (zoneW >= 4) x1 - 1 else x1
        val sampleY0 = if (zoneH >= 4) y0 + 1 else y0
        val sampleY1 = if (zoneH >= 4) y1 - 1 else y1

        val sampleW = max(1, sampleX1 - sampleX0)
        val sampleH = max(1, sampleY1 - sampleY0)
        val stepX = max(1, sampleW / 8)
        val stepY = max(1, sampleH / 8)

        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var weightSum = 0.0

        for (y in sampleY0 until sampleY1 step stepY) {
            val rowOffset = y * rowStride
            for (x in sampleX0 until sampleX1 step stepX) {
                val pixelOffset = rowOffset + x * pixelStride
                if (pixelOffset + 3 > buffer.limit()) continue

                val r = buffer.get(pixelOffset).toInt() and 0xFF
                val g = buffer.get(pixelOffset + 1).toInt() and 0xFF
                val b = buffer.get(pixelOffset + 2).toInt() and 0xFF

                // Overlay rejection: only active when sampling overlay HUD is visible,
                // and tightly matched to the synthetic cyan wireframe (#00E5FF) to prevent rejecting
                // real ocean/teal/blue movie content.
                if (isOverlayActive && r < 18 && g > 25 && b > 25 && kotlin.math.abs(g - b) <= 40) {
                    weightSum += 0.35
                    continue
                }

                val maxVal = max(r, max(g, b))
                val minVal = min(r, min(g, b))
                val chroma = (maxVal - minVal) / 255.0f
                val luma = (0.299f * r + 0.587f * g + 0.114f * b) / 255.0f

                // Chroma-weighted importance formula: base floor ensures black pixels contribute to averaging
                val weight = 0.35 + luma + (chroma * 2.0)

                sumR += r * weight
                sumG += g * weight
                sumB += b * weight
                weightSum += weight
            }
        }

        var outR: Float
        var outG: Float
        var outB: Float

        val blackCutoff = calibration.blackThreshold.coerceIn(0, 50).toFloat()

        if (weightSum < 0.001) {
            outR = 0f
            outG = 0f
            outB = 0f
        } else {
            outR = (sumR / weightSum).toFloat()
            outG = (sumG / weightSum).toFloat()
            outB = (sumB / weightSum).toFloat()

            // Check black cutoff threshold
            val rawIntensity = max(outR, max(outG, outB))
            if (rawIntensity <= blackCutoff) {
                outR = 0f
                outG = 0f
                outB = 0f
            } else {
                val lut = getCalibrationLut(calibration)
                val idxR = outR.roundToInt().coerceIn(0, 255)
                val idxG = outG.roundToInt().coerceIn(0, 255)
                val idxB = outB.roundToInt().coerceIn(0, 255)

                // 1 & 2. Apply Gamma Curves & Contrast via O(1) 1D LUT lookup (0 pow() calls per frame)
                outR = lut.lutR[idxR]
                outG = lut.lutG[idxG]
                outB = lut.lutB[idxB]

                // 3. Saturation boost: smoothly fade in above black cutoff to avoid amplifying dark noise
                val sat = calibration.saturation.coerceIn(0.5f, 3.0f)
                if (sat != 1.0f) {
                    val satScale = ((rawIntensity - blackCutoff) / 20f).coerceIn(0f, 1f)
                    val effectiveSat = 1.0f + (sat - 1.0f) * satScale
                    val gray = 0.299f * outR + 0.587f * outG + 0.114f * outB
                    outR = gray + (outR - gray) * effectiveSat
                    outG = gray + (outG - gray) * effectiveSat
                    outB = gray + (outB - gray) * effectiveSat
                }

                // 4 & 5. Channel RGB gains & master brightness scaling (combined into single pre-scaled factor)
                outR *= lut.gainScaleR
                outG *= lut.gainScaleG
                outB *= lut.gainScaleB
            }
        }

        outBuffer[outOffset] = outR.roundToInt().coerceIn(0, 255).toByte()
        outBuffer[outOffset + 1] = outG.roundToInt().coerceIn(0, 255).toByte()
        outBuffer[outOffset + 2] = outB.roundToInt().coerceIn(0, 255).toByte()
    }

    /**
     * Comprehensive multi-column row scanning to reliably detect black letterbox bars.
     * Features noise-floor rejection (ITU-R BT.709 studio black tolerance), multi-probe consensus,
     * subtitle exclusion, consecutive row confirmation, and aspect ratio snapping.
     */
    private fun detectLetterbox(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int
    ) {
        if (cachedProbeWidth != width || cachedProbeX.isEmpty()) {
            cachedProbeWidth = width
            val probes = IntArray(32)
            // Left quadrant: 16 probes from 12% to 35% width (completely avoids center HUD pill & subtitles)
            val leftMin = (width * 0.12f).toInt()
            val leftMax = (width * 0.35f).toInt()
            val leftStep = (leftMax - leftMin).toFloat() / 15f
            for (i in 0 until 16) {
                probes[i] = (leftMin + i * leftStep).toInt().coerceIn(0, width - 1)
            }
            // Right quadrant: 16 probes from 65% to 88% width
            val rightMin = (width * 0.65f).toInt()
            val rightMax = (width * 0.88f).toInt()
            val rightStep = (rightMax - rightMin).toFloat() / 15f
            for (i in 0 until 16) {
                probes[16 + i] = (rightMin + i * rightStep).toInt().coerceIn(0, width - 1)
            }
            cachedProbeX = probes
            cachedOuterIndices = intArrayOf(0, 1, 30, 31)
        }
        val probeX = cachedProbeX
        val outerIndices = cachedOuterIndices

        val maxScanRows = (height * 0.22f).toInt()

        // 1. Scan from top downwards
        var foundTop = -1
        for (y in 0 until maxScanRows) {
            val rowOffset = y * rowStride
            var leftNonBlack = 0
            var rightNonBlack = 0

            for (i in 0 until 16) {
                val offset = rowOffset + probeX[i] * pixelStride
                if (isPixelNonBlack(buffer, offset)) leftNonBlack++
            }
            for (i in 16 until 32) {
                val offset = rowOffset + probeX[i] * pixelStride
                if (isPixelNonBlack(buffer, offset)) rightNonBlack++
            }
            val totalNonBlack = leftNonBlack + rightNonBlack

            // Channel Bug / Static Watermark Immunity (Quorum & Spatial Span Check):
            // Real scene content spans across the screen (both quadrants have light),
            // OR has broad spatial content (>= 4 probes) in a single quadrant.
            // Localized corner bugs (e.g. 1..3 probes strictly in one quadrant with the other quadrant dark)
            // are rejected as static watermarks / letterbox overlay.
            val isSceneContent = (leftNonBlack >= 2 && rightNonBlack >= 2) || totalNonBlack >= 4
            if (isSceneContent) {
                foundTop = y
                break
            }
        }

        // 2. Scan from bottom upwards (with subtitle exclusion strictly inside the letterbox bar)
        val subtitleMaxDist = (height * 0.125f).toInt()
        var foundBottom = -1
        for (y in (height - 1) downTo (height - maxScanRows)) {
            val distFromBottom = height - 1 - y
            val rowOffset = y * rowStride
            var leftNonBlack = 0
            var rightNonBlack = 0
            var maxVal = 0

            for (px in probeX) {
                val offset = rowOffset + px * pixelStride
                val r = buffer.get(offset).toInt() and 0xFF
                val g = buffer.get(offset + 1).toInt() and 0xFF
                val b = buffer.get(offset + 2).toInt() and 0xFF
                val maxC = maxOf(r, maxOf(g, b))
                if (maxC > maxVal) maxVal = maxC
            }

            for (i in 0 until 16) {
                val offset = rowOffset + probeX[i] * pixelStride
                if (isPixelNonBlack(buffer, offset)) leftNonBlack++
            }
            for (i in 16 until 32) {
                val offset = rowOffset + probeX[i] * pixelStride
                if (isPixelNonBlack(buffer, offset)) rightNonBlack++
            }
            val totalNonBlack = leftNonBlack + rightNonBlack

            var hasOuterLight = false
            for (oi in outerIndices) {
                val offset = rowOffset + probeX[oi] * pixelStride
                if (isPixelNonBlack(buffer, offset)) {
                    hasOuterLight = true
                    break
                }
            }

            // Subtitle protection: ONLY applies inside the letterbox area (< 12.5% from bottom).
            // Subtitles are bright text (maxVal > 40) located in the center with dark outer edges,
            // and narrow probe footprint (totalNonBlack <= 6).
            // Once we reach distFromBottom > 12.5%, or if light spans broad probes (> 6), it is active scene content!
            val isSubtitle = (distFromBottom <= subtitleMaxDist) && (!hasOuterLight && maxVal > 40 && totalNonBlack <= 6)
            val isSceneContent = (leftNonBlack >= 2 && rightNonBlack >= 2) || totalNonBlack >= 4

            if (isSceneContent && !isSubtitle) {
                foundBottom = distFromBottom
                break
            }
        }

        val topCropPct = if (foundTop >= 0) foundTop.toFloat() / height else -1f
        val bottomCropPct = if (foundBottom >= 0) foundBottom.toFloat() / height else -1f

        val isTopEdgeContent = topCropPct in 0.0f..0.014f
        val isBottomEdgeContent = bottomCropPct in 0.0f..0.014f

        // Symmetrical letterbox decision logic:
        // Real film letterbox bars are ALWAYS vertically symmetric (both top and bottom are padded).
        // If active content touches either the top or bottom screen edge (crop < 1.4%), it is 16:9 fullscreen!
        val snappedCrop = when {
            // Either top or bottom has picture right at the screen edge -> definitively 16:9 Fullscreen!
            isTopEdgeContent || isBottomEdgeContent -> 0.0f

            // Both borders detected as letterboxed (>= 1.4%):
            topCropPct >= 0.014f && bottomCropPct >= 0.014f -> {
                // True 2.55:1 Ultrawide Cinema REQUIREMENT:
                // BOTH top and bottom MUST be >= 14.5% simultaneously!
                // If either side has picture at < 14.5% (e.g. 12.8% on standard 2.39:1 content), it is 2.39:1 Cinemascope!
                if (topCropPct >= 0.145f && bottomCropPct >= 0.145f) {
                    0.153f // 2.55:1 Ultrawide Cinema / Anime
                } else {
                    // For standard films (up to 2.39:1 Cinemascope):
                    // If both are in widescreen range (>= 11%), use the shallower bar (minOf) to reject dark ceiling shadows.
                    // For subtitles in the lower letterbox bar (< 11%), top remains definitive.
                    val chosen = if (topCropPct >= 0.110f && bottomCropPct >= 0.110f) {
                        minOf(topCropPct, bottomCropPct)
                    } else {
                        topCropPct
                    }
                    when {
                        chosen >= 0.112f -> 0.130f // 2.39:1 / 2.40:1 Cinemascope (Standard maximum)
                        chosen >= 0.075f -> 0.095f // 2.20:1 70mm Large Format
                        chosen >= 0.035f -> 0.055f // 2.00:1 Univisium
                        chosen >= 0.012f -> 0.020f // 1.85:1 Flat
                        else             -> 0.0f   // 16:9 Fullscreen
                    }
                }
            }

            // Single-side fallback when starting uninitialized or when one side is dark:
            // Hyperion "Hold on Darkness" principle: If we already have an established crop (> 0),
            // a dark frame / single-side blackout (night sky, ceiling shadow) HOLDS the current crop!
            // If completely uninitialized (detectedTopCrop == 0.0f) and one side detects standard letterbox,
            // we can snap to standard aspect ratios (capped strictly at 13.0% Cinemascope).
            detectedTopCrop == 0.0f && (topCropPct >= 0.014f || bottomCropPct >= 0.014f) -> {
                val singleCrop = if (topCropPct >= 0.014f) topCropPct else bottomCropPct
                when {
                    singleCrop >= 0.112f -> 0.130f // 2.39:1 Cinemascope MAX
                    singleCrop >= 0.075f -> 0.095f // 2.20:1 70mm Large Format
                    singleCrop >= 0.035f -> 0.055f // 2.00:1 Univisium
                    singleCrop >= 0.012f -> 0.020f // 1.85:1 Flat
                    else                 -> 0.0f   // 16:9 Fullscreen
                }
            }

            // Both sides are dark, or established movie has a dark cut -> HOLD current crop!
            else -> return
        }

        // Asymmetric Temporal Filter:
        // Expanding to a larger picture (e.g. 13% -> Fullscreen 0%): 15 frames (~500ms)
        // Shrinking to a smaller picture / deeper letterbox (e.g. 0% -> 13%): 30 frames (~1.0s)
        val requiredFrames = if (snappedCrop < detectedTopCrop) 15 else 30

        if (snappedCrop == candidateTopCrop) {
            stableFrameCount++
            if (stableFrameCount >= requiredFrames) {
                detectedTopCrop = snappedCrop
                detectedBottomCrop = snappedCrop
            }
        } else {
            candidateTopCrop = snappedCrop
            candidateBottomCrop = snappedCrop
            stableFrameCount = 1
        }
    }

    /**
     * Sparse vertical probing to reliably detect 4:3 Pillarbox (vertical black sidebars).
     * Probes 16 vertical heights along screen margins between 15% and 85% height,
     * dodging top/bottom letterboxes, tickers, scoreboards, and corner UI.
     */
    private fun detectPillarbox(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int
    ) {
        if (cachedProbeHeight != height || cachedProbeY.isEmpty()) {
            cachedProbeHeight = height
            val probes = IntArray(16)
            val minH = (height * 0.15f).toInt()
            val maxH = (height * 0.85f).toInt()
            val stepH = (maxH - minH).toFloat() / 15f
            for (i in 0 until 16) {
                probes[i] = (minH + i * stepH).toInt().coerceIn(0, height - 1)
            }
            cachedProbeY = probes
        }
        val probeY = cachedProbeY
        val maxScanCols = (width * 0.20f).toInt() // Up to 20% width (covers 12.5% 4:3 pillarbox)

        // 1. Scan from left margin inwards
        var foundLeft = -1
        for (x in 0 until maxScanCols) {
            var upperNonBlack = 0
            var lowerNonBlack = 0
            for (i in 0 until 8) {
                val offset = probeY[i] * rowStride + x * pixelStride
                if (isPixelNonBlack(buffer, offset)) upperNonBlack++
            }
            for (i in 8 until 16) {
                val offset = probeY[i] * rowStride + x * pixelStride
                if (isPixelNonBlack(buffer, offset)) lowerNonBlack++
            }
            val totalNonBlack = upperNonBlack + lowerNonBlack
            // Watermark/bug immunity in vertical probing:
            // Scene content requires span across upper and lower or broad coverage (>= 4 probes)
            if ((upperNonBlack >= 2 && lowerNonBlack >= 2) || totalNonBlack >= 4) {
                foundLeft = x
                break
            }
        }

        // 2. Scan from right margin inwards
        var foundRight = -1
        for (x in (width - 1) downTo (width - maxScanCols)) {
            val distFromRight = width - 1 - x
            var upperNonBlack = 0
            var lowerNonBlack = 0
            for (i in 0 until 8) {
                val offset = probeY[i] * rowStride + x * pixelStride
                if (isPixelNonBlack(buffer, offset)) upperNonBlack++
            }
            for (i in 8 until 16) {
                val offset = probeY[i] * rowStride + x * pixelStride
                if (isPixelNonBlack(buffer, offset)) lowerNonBlack++
            }
            val totalNonBlack = upperNonBlack + lowerNonBlack
            if ((upperNonBlack >= 2 && lowerNonBlack >= 2) || totalNonBlack >= 4) {
                foundRight = distFromRight
                break
            }
        }

        val leftCropPct = if (foundLeft >= 0) foundLeft.toFloat() / width else -1f
        val rightCropPct = if (foundRight >= 0) foundRight.toFloat() / width else -1f

        val isLeftEdgeContent = leftCropPct in 0.0f..0.020f
        val isRightEdgeContent = rightCropPct in 0.0f..0.020f

        val snappedCrop = when {
            // Either left or right has content touching screen edge -> 16:9 widescreen (no pillarbox)
            isLeftEdgeContent || isRightEdgeContent -> 0.0f

            // Both left and right columns have black bars >= 2%
            leftCropPct >= 0.020f && rightCropPct >= 0.020f -> {
                val chosen = minOf(leftCropPct, rightCropPct)
                when {
                    chosen >= 0.095f -> 0.125f // 4:3 Classic Pillarbox (12.5% on each side)
                    chosen >= 0.045f -> 0.065f // 1.66:1 European Widescreen
                    else             -> 0.0f   // 16:9 Fullscreen
                }
            }

            // Single-side fallback when uninitialized
            detectedLeftCrop == 0.0f && (leftCropPct >= 0.020f || rightCropPct >= 0.020f) -> {
                val single = if (leftCropPct >= 0.020f) leftCropPct else rightCropPct
                when {
                    single >= 0.095f -> 0.125f
                    single >= 0.045f -> 0.065f
                    else             -> 0.0f
                }
            }

            // Both sides dark or single-side dark cut on established pillarbox -> HOLD current crop!
            else -> return
        }

        // Asymmetric Temporal Filter:
        // Expanding to full width (e.g. 12.5% -> 0%): 15 frames (~500ms)
        // Shrinking to 4:3 pillarbox (e.g. 0% -> 12.5%): 30 frames (~1.0s)
        val requiredFrames = if (snappedCrop < detectedLeftCrop) 15 else 30

        if (snappedCrop == candidateLeftCrop) {
            stablePillarFrameCount++
            if (stablePillarFrameCount >= requiredFrames) {
                detectedLeftCrop = snappedCrop
                detectedRightCrop = snappedCrop
            }
        } else {
            candidateLeftCrop = snappedCrop
            candidateRightCrop = snappedCrop
            stablePillarFrameCount = 1
        }
    }

    private fun isPixelNonBlack(buffer: ByteBuffer, offset: Int): Boolean {
        if (offset + 3 > buffer.limit()) return false
        val r = buffer.get(offset).toInt() and 0xFF
        val g = buffer.get(offset + 1).toInt() and 0xFF
        val b = buffer.get(offset + 2).toInt() and 0xFF

        // Overlay rejection: only active when sampling overlay HUD is visible
        if (isOverlayActive && r < 18 && g > 25 && b > 25 && kotlin.math.abs(g - b) <= 40) {
            return false
        }

        val maxVal = maxOf(r, maxOf(g, b))
        val luma = 0.299f * r + 0.587f * g + 0.114f * b
        // Digital black noise floor: true digital letterbox bars on Android TV streams are pure [0,0,0] (or noise <= 4).
        // Dark scene elements (like dim sauna ceiling wood, dark floor) have maxVal in 6..12 range.
        return (maxVal >= 7 || luma >= 6f)
    }

    /**
     * Rapidly samples a sparse 5x5 grid across the frame to check if the captured surface is completely digital black.
     * When Widevine L1 DRM video is playing, MediaProjection outputs 100% black across the entire display.
     */
    fun isFrameCompletelyBlack(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int
    ): Boolean {
        if (width <= 0 || height <= 0) return true
        val stepX = width / 6
        val stepY = height / 6
        for (gy in 1..5) {
            val y = gy * stepY
            val rowOffset = y * rowStride
            for (gx in 1..5) {
                val x = gx * stepX
                val offset = rowOffset + x * pixelStride
                if (isPixelNonBlack(buffer, offset)) {
                    return false
                }
            }
        }
        return true
    }

    class CalibrationLut(
        val lutR: FloatArray,
        val lutG: FloatArray,
        val lutB: FloatArray,
        val gainScaleR: Float,
        val gainScaleG: Float,
        val gainScaleB: Float
    )

    private fun getCalibrationLut(calibration: ColorCalibration): CalibrationLut {
        val existing = cachedLuts[calibration]
        if (existing != null) return existing

        val lutR = FloatArray(256)
        val lutG = FloatArray(256)
        val lutB = FloatArray(256)

        val gammaR = calibration.gammaR.coerceIn(0.5f, 2.5f)
        val gammaG = calibration.gammaG.coerceIn(0.5f, 2.5f)
        val gammaB = calibration.gammaB.coerceIn(0.5f, 2.5f)
        val contrast = calibration.contrast.coerceIn(0.5f, 1.5f)
        val gR = calibration.gainR.coerceIn(0.2f, 2.5f)
        val gG = calibration.gainG.coerceIn(0.2f, 2.5f)
        val gB = calibration.gainB.coerceIn(0.2f, 2.5f)
        val maxBrightness = calibration.maxBrightness.coerceIn(0, 255)
        val scale = if (maxBrightness < 255) maxBrightness / 255f else 1.0f

        for (i in 0..255) {
            val norm = i / 255f
            var r = if (gammaR != 1.0f) 255f * norm.pow(gammaR) else i.toFloat()
            var g = if (gammaG != 1.0f) 255f * norm.pow(gammaG) else i.toFloat()
            var b = if (gammaB != 1.0f) 255f * norm.pow(gammaB) else i.toFloat()

            if (contrast != 1.0f) {
                r = (128f + (r - 128f) * contrast).coerceAtLeast(0f)
                g = (128f + (g - 128f) * contrast).coerceAtLeast(0f)
                b = (128f + (b - 128f) * contrast).coerceAtLeast(0f)
            }

            lutR[i] = r
            lutG[i] = g
            lutB[i] = b
        }

        val lut = CalibrationLut(
            lutR = lutR,
            lutG = lutG,
            lutB = lutB,
            gainScaleR = gR * scale,
            gainScaleG = gG * scale,
            gainScaleB = gB * scale
        )

        if (cachedLuts.size > 8) cachedLuts.clear()
        cachedLuts[calibration] = lut
        return lut
    }
}
