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

    fun reset() {
        smoothingFilter.reset()
        detectedTopCrop = 0.0f
        detectedBottomCrop = 0.0f
        stableFrameCount = 0
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
        val ledCount = zones.size
        if (ledCount == 0 || width <= 0 || height <= 0) return false

        val requiredRawSize = ledCount * 3
        if (rawRgbBuffer.size != requiredRawSize) {
            rawRgbBuffer = ByteArray(requiredRawSize)
        }

        // Check dynamic letterbox if enabled
        if (perimeterConfig?.autoLetterbox == true) {
            detectLetterbox(buffer, width, height, rowStride, pixelStride)
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
            val zone = zones[i]
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
     * Fast 3-probe line analysis to detect letterbox bars.
     */
    private fun detectLetterbox(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int
    ) {
        val probeX1 = (width * 0.25f).toInt()
        val probeX2 = (width * 0.50f).toInt()
        val probeX3 = (width * 0.75f).toInt()

        val maxScanRows = (height * 0.25f).toInt() // Scan up to 25% height for bars
        var foundTop = 0
        var foundBottom = 0

        // Scan from top down
        for (y in 0 until maxScanRows) {
            val offset1 = y * rowStride + probeX1 * pixelStride
            val offset2 = y * rowStride + probeX2 * pixelStride
            val offset3 = y * rowStride + probeX3 * pixelStride

            if (isPixelNonBlack(buffer, offset1) || isPixelNonBlack(buffer, offset2) || isPixelNonBlack(buffer, offset3)) {
                foundTop = y
                break
            }
        }

        // Scan from bottom up
        for (y in (height - 1) downTo (height - maxScanRows)) {
            val offset1 = y * rowStride + probeX1 * pixelStride
            val offset2 = y * rowStride + probeX2 * pixelStride
            val offset3 = y * rowStride + probeX3 * pixelStride

            if (isPixelNonBlack(buffer, offset1) || isPixelNonBlack(buffer, offset2) || isPixelNonBlack(buffer, offset3)) {
                foundBottom = height - 1 - y
                break
            }
        }

        val topCropPct = (foundTop.toFloat() / height).coerceIn(0f, 0.25f)
        val bottomCropPct = (foundBottom.toFloat() / height).coerceIn(0f, 0.25f)

        // Stability filtering (5 consecutive frames)
        if (Math.abs(topCropPct - candidateTopCrop) < 0.015f && Math.abs(bottomCropPct - candidateBottomCrop) < 0.015f) {
            stableFrameCount++
            if (stableFrameCount >= 5) {
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
        return (r > 18 || g > 18 || b > 18)
    }
}
