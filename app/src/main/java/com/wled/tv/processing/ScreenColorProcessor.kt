package com.wled.tv.processing

import android.media.Image
import com.wled.tv.geometry.NormalizedRect
import com.wled.tv.model.ColorCalibration
import com.wled.tv.model.StreamingProtocol
import com.wled.tv.model.WledDevice
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Optical color processing engine executing:
 * 1. Letterbox & pillarbox video row/column probing.
 * 2. Spatial box sampling with chroma-weighted color extraction from normalized screen zones.
 * 3. Calibration LUT application (gamma, contrast, saturation, channel gains).
 * 4. Adaptive dual-speed EMA temporal smoothing.
 * 5. SK6812 RGBW 4-channel gamut mapping and hardware wire-channel permutation.
 *
 * Fully decoupled from physical fixture coordinate calculations and device geometry.
 */
class ScreenColorProcessor {

    private val smoothingFilters = HashMap<String, SmoothingFilter>()

    // Instantaneous optical probe detection metrics (zero allocation)
    var lastDetectedTopCropPct: Float = -1f
        private set
    var lastDetectedBottomCropPct: Float = -1f
        private set
    var lastDetectedLeftCropPct: Float = -1f
        private set
    var lastDetectedRightCropPct: Float = -1f
        private set

    @Volatile
    var isOverlayActive: Boolean = false

    // Zero-allocation caches
    private val cachedLuts = HashMap<ColorCalibration, CalibrationLut>()
    private var cachedProbeWidth: Int = -1
    private var cachedProbeX: IntArray = IntArray(0)
    private var cachedOuterIndices: IntArray = IntArray(0)
    private var cachedProbeHeight: Int = -1
    private var cachedProbeY: IntArray = IntArray(0)

    fun reset() {
        smoothingFilters.clear()
        cachedLuts.clear()
        cachedProbeWidth = -1
        cachedProbeX = IntArray(0)
        cachedOuterIndices = IntArray(0)
        cachedProbeHeight = -1
        cachedProbeY = IntArray(0)
        lastDetectedTopCropPct = -1f
        lastDetectedBottomCropPct = -1f
        lastDetectedLeftCropPct = -1f
        lastDetectedRightCropPct = -1f
        isOverlayActive = false
    }

    /**
     * Scans video frame margins for non-black content boundaries with zero heap allocation.
     * Results are updated in [lastDetectedTopCropPct], [lastDetectedBottomCropPct],
     * [lastDetectedLeftCropPct], and [lastDetectedRightCropPct].
     */
    fun scanBorderProbes(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int) {
        detectLetterboxProbes(buffer, width, height, rowStride, pixelStride)
        detectPillarboxProbes(buffer, width, height, rowStride, pixelStride)
    }

    /**
     * Extracts raw chroma-weighted RGB colors for a list of normalized screen zones without temporal smoothing.
     * Used by the decoupled frame acquisition pipeline to sample target colors upon video frame arrival.
     */
    fun extractRawColors(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        zones: List<NormalizedRect>,
        calibration: ColorCalibration,
        outputRawRgb: ByteArray
    ): Boolean {
        if (width <= 0 || height <= 0) return false
        val ledCount = zones.size
        if (ledCount == 0 || outputRawRgb.size < ledCount * 3) return false

        for (i in 0 until ledCount) {
            sampleChromaWeightedBox(
                buffer = buffer,
                width = width,
                height = height,
                rowStride = rowStride,
                pixelStride = pixelStride,
                zone = zones[i],
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
        val filter = smoothingFilters.getOrPut(deviceId) { SmoothingFilter() }
        filter.apply(rawRgb, ledCount, smoothingFactor, outputRgb)
    }

    fun reset(deviceId: String) {
        smoothingFilters.remove(deviceId)
    }

    /**
     * Encodes canonical RGB pixel buffer into physical LED wire protocol format for a specific WLED device.
     */
    fun encodeWireFrame(device: WledDevice, sourceRgb: ByteArray, outputWire: ByteArray): Int {
        return encodeWireFrame(
            sourceRgb = sourceRgb,
            ledCount = device.totalLeds,
            colorOrder = device.calibration.colorOrder,
            isRgbw = (device.calibration.isRgbw && device.protocol == StreamingProtocol.DDP),
            rgbwMode = device.calibration.rgbwMode,
            outputWire = outputWire
        )
    }

    fun sampleChromaWeightedBox(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        zone: NormalizedRect,
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
        zone: NormalizedRect,
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
     * Multi-column row scanning to detect top and bottom non-black picture boundaries.
     */
    private fun detectLetterboxProbes(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int
    ) {
        if (cachedProbeWidth != width || cachedProbeX.isEmpty()) {
            cachedProbeWidth = width
            val probes = IntArray(32)
            val leftMin = (width * 0.12f).toInt()
            val leftMax = (width * 0.35f).toInt()
            val leftStep = (leftMax - leftMin).toFloat() / 15f
            for (i in 0 until 16) {
                probes[i] = (leftMin + i * leftStep).toInt().coerceIn(0, width - 1)
            }
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

            val isSceneContent = (leftNonBlack >= 2 && rightNonBlack >= 2) || totalNonBlack >= 4
            if (isSceneContent) {
                foundTop = y
                break
            }
        }

        // 2. Scan from bottom upwards (with subtitle exclusion)
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

            val isSubtitle = (distFromBottom <= subtitleMaxDist) && (!hasOuterLight && maxVal > 40 && totalNonBlack <= 6)
            val isSceneContent = (leftNonBlack >= 2 && rightNonBlack >= 2) || totalNonBlack >= 4

            if (isSceneContent && !isSubtitle) {
                foundBottom = distFromBottom
                break
            }
        }

        lastDetectedTopCropPct = if (foundTop >= 0) foundTop.toFloat() / height else -1f
        lastDetectedBottomCropPct = if (foundBottom >= 0) foundBottom.toFloat() / height else -1f
    }

    /**
     * Vertical margin probing to detect left and right non-black pillarbox boundaries.
     */
    private fun detectPillarboxProbes(
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
        val maxScanCols = (width * 0.20f).toInt()

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
            if ((upperNonBlack >= 2 && lowerNonBlack >= 2) || totalNonBlack >= 4) {
                foundLeft = x
                break
            }
        }

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

        lastDetectedLeftCropPct = if (foundLeft >= 0) foundLeft.toFloat() / width else -1f
        lastDetectedRightCropPct = if (foundRight >= 0) foundRight.toFloat() / width else -1f
    }

    private fun isPixelNonBlack(buffer: ByteBuffer, offset: Int): Boolean {
        if (offset + 3 > buffer.limit()) return false
        val r = buffer.get(offset).toInt() and 0xFF
        val g = buffer.get(offset + 1).toInt() and 0xFF
        val b = buffer.get(offset + 2).toInt() and 0xFF

        if (isOverlayActive && r < 18 && g > 25 && b > 25 && kotlin.math.abs(g - b) <= 40) {
            return false
        }

        val maxVal = maxOf(r, maxOf(g, b))
        val luma = 0.299f * r + 0.587f * g + 0.114f * b
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

    private class SmoothingFilter {
        private var smoothedRgb = FloatArray(0)

        fun reset() {
            smoothedRgb = FloatArray(0)
        }

        fun apply(
            rawRgb: ByteArray,
            ledCount: Int,
            factor: Float,
            output: ByteArray
        ) {
            val totalBytes = ledCount * 3
            if (totalBytes <= 0) return

            if (smoothedRgb.size != totalBytes) {
                smoothedRgb = FloatArray(totalBytes)
                for (i in 0 until totalBytes) {
                    smoothedRgb[i] = (rawRgb[i].toInt() and 0xFF).toFloat()
                }
            }

            var sumSquaredDist = 0.0
            for (i in 0 until ledCount) {
                val idx = i * 3
                val rTarget = (rawRgb[idx].toInt() and 0xFF).toFloat()
                val gTarget = (rawRgb[idx + 1].toInt() and 0xFF).toFloat()
                val bTarget = (rawRgb[idx + 2].toInt() and 0xFF).toFloat()

                val rDiff = rTarget - smoothedRgb[idx]
                val gDiff = gTarget - smoothedRgb[idx + 1]
                val bDiff = bTarget - smoothedRgb[idx + 2]

                sumSquaredDist += (rDiff * rDiff + gDiff * gDiff + bDiff * bDiff)
            }

            val avgDistance = kotlin.math.sqrt(sumSquaredDist / ledCount)

            val baseAlpha = factor.coerceIn(0.05f, 1.0f)
            val alpha = when {
                baseAlpha >= 0.90f -> baseAlpha
                avgDistance >= 60.0 -> 0.90f
                avgDistance >= 30.0 -> {
                    val t = ((avgDistance - 30.0) / 30.0).toFloat()
                    baseAlpha + t * (0.90f - baseAlpha)
                }
                else -> baseAlpha
            }

            val oneMinusAlpha = 1.0f - alpha

            for (i in 0 until totalBytes) {
                val target = (rawRgb[i].toInt() and 0xFF).toFloat()
                smoothedRgb[i] = (smoothedRgb[i] * oneMinusAlpha) + (target * alpha)
                output[i] = (smoothedRgb[i] + 0.5f).toInt().coerceIn(0, 255).toByte()
            }
        }
    }

    companion object {
        const val ORDER_RGB = 0
        const val ORDER_GRB = 1
        const val ORDER_BGR = 2
        const val ORDER_BRG = 3
        const val ORDER_RBG = 4
        const val ORDER_GBR = 5

        const val RGBW_MODE_AUTO_SUBTRACT = "AUTO_SUBTRACT"
        const val RGBW_MODE_AUTO_ADD = "AUTO_ADD"
        const val RGBW_MODE_OFF = "OFF"

        private const val MODE_INT_AUTO_SUBTRACT = 0
        private const val MODE_INT_AUTO_ADD = 1
        private const val MODE_INT_OFF = 2

        fun parseColorOrder(colorOrder: String?): Int {
            if (colorOrder == null) return ORDER_RGB
            var start = 0
            while (start < colorOrder.length && colorOrder[start].isWhitespace()) {
                start++
            }
            if (colorOrder.length - start < 3) return ORDER_RGB
            val c0 = colorOrder[start].uppercaseChar()
            val c1 = colorOrder[start + 1].uppercaseChar()
            val c2 = colorOrder[start + 2].uppercaseChar()
            return when {
                c0 == 'R' && c1 == 'G' && c2 == 'B' -> ORDER_RGB
                c0 == 'G' && c1 == 'R' && c2 == 'B' -> ORDER_GRB
                c0 == 'B' && c1 == 'G' && c2 == 'R' -> ORDER_BGR
                c0 == 'B' && c1 == 'R' && c2 == 'G' -> ORDER_BRG
                c0 == 'R' && c1 == 'B' && c2 == 'G' -> ORDER_RBG
                c0 == 'G' && c1 == 'B' && c2 == 'R' -> ORDER_GBR
                else -> ORDER_RGB
            }
        }

        private fun parseRgbwMode(mode: String?): Int {
            if (mode == null) return MODE_INT_AUTO_SUBTRACT
            return when {
                mode.equals(RGBW_MODE_AUTO_SUBTRACT, ignoreCase = true) -> MODE_INT_AUTO_SUBTRACT
                mode.equals(RGBW_MODE_AUTO_ADD, ignoreCase = true) -> MODE_INT_AUTO_ADD
                mode.equals(RGBW_MODE_OFF, ignoreCase = true) -> MODE_INT_OFF
                else -> MODE_INT_AUTO_SUBTRACT
            }
        }

        fun encodeWireFrame(
            device: WledDevice,
            sourceRgb: ByteArray,
            outputWire: ByteArray
        ): Int {
            return encodeWireFrame(
                sourceRgb = sourceRgb,
                ledCount = device.totalLeds,
                colorOrder = device.calibration.colorOrder,
                isRgbw = (device.calibration.isRgbw && device.protocol == StreamingProtocol.DDP),
                rgbwMode = device.calibration.rgbwMode,
                outputWire = outputWire
            )
        }

        fun encodeWireFrame(
            sourceRgb: ByteArray,
            ledCount: Int,
            colorOrder: String,
            isRgbw: Boolean,
            rgbwMode: String,
            outputWire: ByteArray
        ): Int {
            if (ledCount <= 0) return 0
            val requiredSourceBytes = ledCount * 3
            if (sourceRgb.size < requiredSourceBytes) return 0
            val bytesPerLed = if (isRgbw) 4 else 3
            val requiredWireBytes = ledCount * bytesPerLed
            if (outputWire.size < requiredWireBytes) return 0

            val order = parseColorOrder(colorOrder)

            if (!isRgbw) {
                if (order == ORDER_RGB) {
                    System.arraycopy(sourceRgb, 0, outputWire, 0, requiredWireBytes)
                } else {
                    var dst = 0
                    for (i in 0 until ledCount) {
                        val src = i * 3
                        val r = sourceRgb[src]
                        val g = sourceRgb[src + 1]
                        val b = sourceRgb[src + 2]
                        when (order) {
                            ORDER_GRB -> {
                                outputWire[dst++] = g
                                outputWire[dst++] = r
                                outputWire[dst++] = b
                            }
                            ORDER_BGR -> {
                                outputWire[dst++] = b
                                outputWire[dst++] = g
                                outputWire[dst++] = r
                            }
                            ORDER_BRG -> {
                                outputWire[dst++] = b
                                outputWire[dst++] = r
                                outputWire[dst++] = g
                            }
                            ORDER_RBG -> {
                                outputWire[dst++] = r
                                outputWire[dst++] = b
                                outputWire[dst++] = g
                            }
                            ORDER_GBR -> {
                                outputWire[dst++] = g
                                outputWire[dst++] = b
                                outputWire[dst++] = r
                            }
                            else -> {
                                outputWire[dst++] = r
                                outputWire[dst++] = g
                                outputWire[dst++] = b
                            }
                        }
                    }
                }
            } else {
                val parsedMode = parseRgbwMode(rgbwMode)
                var dst = 0
                for (i in 0 until ledCount) {
                    val src = i * 3
                    val rInt = sourceRgb[src].toInt() and 0xFF
                    val gInt = sourceRgb[src + 1].toInt() and 0xFF
                    val bInt = sourceRgb[src + 2].toInt() and 0xFF

                    val wInt = when (parsedMode) {
                        MODE_INT_AUTO_SUBTRACT, MODE_INT_AUTO_ADD -> minOf(rInt, minOf(gInt, bInt))
                        else -> 0
                    }

                    val rOut = if (parsedMode == MODE_INT_AUTO_SUBTRACT) rInt - wInt else rInt
                    val gOut = if (parsedMode == MODE_INT_AUTO_SUBTRACT) gInt - wInt else gInt
                    val bOut = if (parsedMode == MODE_INT_AUTO_SUBTRACT) bInt - wInt else bInt

                    val rByte = rOut.toByte()
                    val gByte = gOut.toByte()
                    val bByte = bOut.toByte()
                    val wByte = wInt.toByte()

                    when (order) {
                        ORDER_GRB -> {
                            outputWire[dst++] = gByte
                            outputWire[dst++] = rByte
                            outputWire[dst++] = bByte
                            outputWire[dst++] = wByte
                        }
                        ORDER_BGR -> {
                            outputWire[dst++] = bByte
                            outputWire[dst++] = gByte
                            outputWire[dst++] = rByte
                            outputWire[dst++] = wByte
                        }
                        ORDER_BRG -> {
                            outputWire[dst++] = bByte
                            outputWire[dst++] = rByte
                            outputWire[dst++] = gByte
                            outputWire[dst++] = wByte
                        }
                        ORDER_RBG -> {
                            outputWire[dst++] = rByte
                            outputWire[dst++] = bByte
                            outputWire[dst++] = gByte
                            outputWire[dst++] = wByte
                        }
                        ORDER_GBR -> {
                            outputWire[dst++] = gByte
                            outputWire[dst++] = bByte
                            outputWire[dst++] = rByte
                            outputWire[dst++] = wByte
                        }
                        else -> { // ORDER_RGB
                            outputWire[dst++] = rByte
                            outputWire[dst++] = gByte
                            outputWire[dst++] = bByte
                            outputWire[dst++] = wByte
                        }
                    }
                }
            }

            return requiredWireBytes
        }
    }
}
