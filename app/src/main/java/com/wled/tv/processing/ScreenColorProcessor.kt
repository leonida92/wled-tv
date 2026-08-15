package com.wled.tv.processing

import android.graphics.RectF
import android.media.Image
import com.wled.tv.model.ColorCalibration
import com.wled.tv.model.PerimeterConfig
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

class ScreenColorProcessor {

    private val smoothingFilter = ColorSmoothingFilter()
    private var rawRgbBuffer = ByteArray(0)

    // Dynamic Letterbox Auto-Detection state
    private var detectedTopCrop: Float = 0.0f
    private var detectedBottomCrop: Float = 0.0f
    private var candidateTopCrop: Float = 0.0f
    private var candidateBottomCrop: Float = 0.0f
    private var stableFrameCount: Int = 0
    private var cachedDynamicZones: List<RectF>? = null
    private var lastAppliedTopCrop: Float = -1f
    private var lastAppliedBottomCrop: Float = -1f

    fun reset() {
        smoothingFilter.reset()
        detectedTopCrop = 0.0f
        detectedBottomCrop = 0.0f
        candidateTopCrop = 0.0f
        candidateBottomCrop = 0.0f
        stableFrameCount = 0
        cachedDynamicZones = null
        lastAppliedTopCrop = -1f
        lastAppliedBottomCrop = -1f
    }

    /**
     * Extracts chroma-weighted edge RGB colors from an Android MediaProjection Image.
     */
    fun processImage(
        image: Image,
        zones: List<RectF>,
        calibration: ColorCalibration,
        perimeterConfig: PerimeterConfig? = null,
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

        return processImage(
            buffer = buffer,
            width = width,
            height = height,
            rowStride = rowStride,
            pixelStride = pixelStride,
            zones = zones,
            calibration = calibration,
            perimeterConfig = perimeterConfig,
            outputRgb = outputRgb
        )
    }

    /**
     * Processes a downsampled screen buffer and extracts the chroma-weighted average RGB
     * for each LED along the screen's 4-sided perimeter with advanced color grading.
     */
    fun processImage(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        zones: List<RectF>,
        calibration: ColorCalibration,
        perimeterConfig: PerimeterConfig? = null,
        outputRgb: ByteArray
    ): Boolean {
        if (width <= 0 || height <= 0) return false

        // Determine active zones (dynamic if autoLetterbox is enabled, else static zones)
        val activeZones: List<RectF>
        if (perimeterConfig?.autoLetterbox == true) {
            detectLetterbox(buffer, width, height, rowStride, pixelStride)
            if (cachedDynamicZones == null || detectedTopCrop != lastAppliedTopCrop || detectedBottomCrop != lastAppliedBottomCrop) {
                cachedDynamicZones = perimeterConfig.copy(
                    topCrop = detectedTopCrop,
                    bottomCrop = detectedBottomCrop
                ).computeLedZones()
                lastAppliedTopCrop = detectedTopCrop
                lastAppliedBottomCrop = detectedBottomCrop
            }
            activeZones = cachedDynamicZones ?: zones
        } else {
            activeZones = zones
        }

        val ledCount = activeZones.size
        if (ledCount == 0) return false

        val requiredRawSize = ledCount * 3
        if (rawRgbBuffer.size != requiredRawSize) {
            rawRgbBuffer = ByteArray(requiredRawSize)
        }

        val sat = calibration.saturation.coerceIn(0.5f, 3.0f)
        val contrast = calibration.contrast.coerceIn(0.5f, 1.5f)
        val gR = calibration.gainR.coerceIn(0.2f, 2.5f)
        val gG = calibration.gainG.coerceIn(0.2f, 2.5f)
        val gB = calibration.gainB.coerceIn(0.2f, 2.5f)
        val gammaR = calibration.gammaR.coerceIn(0.5f, 2.5f)
        val gammaG = calibration.gammaG.coerceIn(0.5f, 2.5f)
        val gammaB = calibration.gammaB.coerceIn(0.5f, 2.5f)
        val maxBrightness = calibration.maxBrightness.coerceIn(0, 255)
        val blackCutoff = calibration.blackThreshold.coerceIn(0, 50).toFloat()

        for (i in 0 until ledCount) {
            val zone = activeZones[i]
            val x0 = (zone.left * width).toInt().coerceIn(0, width - 1)
            val x1 = (zone.right * width).toInt().coerceIn(x0 + 1, width)
            val y0 = (zone.top * height).toInt().coerceIn(0, height - 1)
            val y1 = (zone.bottom * height).toInt().coerceIn(y0 + 1, height)

            val zoneW = max(1, x1 - x0)
            val zoneH = max(1, y1 - y0)
            val stepX = max(1, zoneW / 8)
            val stepY = max(1, zoneH / 8)

            var sumR = 0.0
            var sumG = 0.0
            var sumB = 0.0
            var weightSum = 0.0

            for (y in y0 until y1 step stepY) {
                val rowOffset = y * rowStride
                for (x in x0 until x1 step stepX) {
                    val pixelOffset = rowOffset + x * pixelStride
                    if (pixelOffset + 3 > buffer.limit()) continue

                    val r = buffer.get(pixelOffset).toInt() and 0xFF
                    val g = buffer.get(pixelOffset + 1).toInt() and 0xFF
                    val b = buffer.get(pixelOffset + 2).toInt() and 0xFF

                    val maxVal = max(r, max(g, b))
                    if (maxVal < 4) continue // ignore absolute zero noise

                    val minVal = min(r, min(g, b))
                    val chroma = (maxVal - minVal) / 255.0f
                    val luma = (0.299f * r + 0.587f * g + 0.114f * b) / 255.0f

                    // Chroma-weighted importance formula
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
                    // 1. Apply Gamma Curves
                    if (gammaR != 1.0f) outR = 255f * (outR / 255f).pow(gammaR)
                    if (gammaG != 1.0f) outG = 255f * (outG / 255f).pow(gammaG)
                    if (gammaB != 1.0f) outB = 255f * (outB / 255f).pow(gammaB)

                    // 2. Contrast adjustment (around midpoint 128)
                    if (contrast != 1.0f) {
                        outR = 128f + (outR - 128f) * contrast
                        outG = 128f + (outG - 128f) * contrast
                        outB = 128f + (outB - 128f) * contrast
                    }

                    // 3. Saturation boost
                    if (sat != 1.0f) {
                        val gray = 0.299f * outR + 0.587f * outG + 0.114f * outB
                        outR = gray + (outR - gray) * sat
                        outG = gray + (outG - gray) * sat
                        outB = gray + (outB - gray) * sat
                    }

                    // 4. Channel RGB gains
                    outR *= gR
                    outG *= gG
                    outB *= gB

                    // 5. Master brightness scaling
                    if (maxBrightness < 255) {
                        val scale = maxBrightness / 255f
                        outR *= scale
                        outG *= scale
                        outB *= scale
                    }
                }
            }

            val outIdx = i * 3
            rawRgbBuffer[outIdx] = outR.roundToInt().coerceIn(0, 255).toByte()
            rawRgbBuffer[outIdx + 1] = outG.roundToInt().coerceIn(0, 255).toByte()
            rawRgbBuffer[outIdx + 2] = outB.roundToInt().coerceIn(0, 255).toByte()
        }

        // Apply temporal EMA smoothing
        smoothingFilter.apply(
            rawRgbBuffer,
            ledCount,
            calibration.smoothingFactor,
            outputRgb
        )

        return true
    }

    /**
     * Comprehensive multi-column row scanning to reliably detect black letterbox bars.
     */
    private fun detectLetterbox(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int
    ) {
        // Multi-point probe columns across screen width (10% to 90%)
        val probeX = intArrayOf(
            (width * 0.10f).toInt(),
            (width * 0.20f).toInt(),
            (width * 0.30f).toInt(),
            (width * 0.40f).toInt(),
            (width * 0.50f).toInt(),
            (width * 0.60f).toInt(),
            (width * 0.70f).toInt(),
            (width * 0.80f).toInt(),
            (width * 0.90f).toInt()
        )

        val maxScanRows = (height * 0.30f).toInt() // Scan up to 30% height for bars
        var foundTop = 0
        var foundBottom = 0

        // Scan from top down: A row is black if ALL probe columns are black
        for (y in 0 until maxScanRows) {
            var rowHasPicture = false
            val rowOffset = y * rowStride
            for (px in probeX) {
                val offset = rowOffset + px * pixelStride
                if (isPixelNonBlack(buffer, offset)) {
                    rowHasPicture = true
                    break
                }
            }
            if (rowHasPicture) {
                foundTop = y
                break
            }
        }

        // Scan from bottom up: A row is black if ALL probe columns are black
        for (y in (height - 1) downTo (height - maxScanRows)) {
            var rowHasPicture = false
            val rowOffset = y * rowStride
            for (px in probeX) {
                val offset = rowOffset + px * pixelStride
                if (isPixelNonBlack(buffer, offset)) {
                    rowHasPicture = true
                    break
                }
            }
            if (rowHasPicture) {
                foundBottom = height - 1 - y
                break
            }
        }

        val topCropPct = (foundTop.toFloat() / height).coerceIn(0f, 0.25f)
        val bottomCropPct = (foundBottom.toFloat() / height).coerceIn(0f, 0.25f)

        // Ignore whole-screen blackouts (e.g. scene transitions) where no content was found at all
        if (foundTop == 0 && foundBottom == 0 && topCropPct == 0f && bottomCropPct == 0f) {
            // Check if center of screen is also completely black
            val centerOffset = (height / 2) * rowStride + (width / 2) * pixelStride
            if (!isPixelNonBlack(buffer, centerOffset)) {
                // Entire screen is dark/black; retain current detected crop to avoid bouncing
                return
            }
        }

        // Stability filtering (3 consecutive frames)
        if (Math.abs(topCropPct - candidateTopCrop) < 0.015f && Math.abs(bottomCropPct - candidateBottomCrop) < 0.015f) {
            stableFrameCount++
            if (stableFrameCount >= 3) {
                detectedTopCrop = candidateTopCrop
                detectedBottomCrop = candidateBottomCrop
            }
        } else {
            candidateTopCrop = topCropPct
            candidateBottomCrop = bottomCropPct
            stableFrameCount = 1
        }
    }

    private fun isPixelNonBlack(buffer: ByteBuffer, offset: Int): Boolean {
        if (offset + 3 > buffer.limit()) return false
        val r = buffer.get(offset).toInt() and 0xFF
        val g = buffer.get(offset + 1).toInt() and 0xFF
        val b = buffer.get(offset + 2).toInt() and 0xFF
        // Consider pixel non-black if brightness exceeds threshold 16
        return (r > 16 || g > 16 || b > 16)
    }
}
