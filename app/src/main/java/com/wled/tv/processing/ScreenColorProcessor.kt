package com.wled.tv.processing

import android.graphics.RectF
import android.media.Image
import com.wled.tv.model.ColorCalibration
import com.wled.tv.model.DeviceType
import com.wled.tv.model.HomeAssistantLight
import com.wled.tv.model.HomeAssistantZoneType
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
    private val haSampleBuffer = ByteArray(3)

    // Dynamic Letterbox Auto-Detection state
    private var detectedTopCrop: Float = 0.0f
    private var detectedBottomCrop: Float = 0.0f
    private var candidateTopCrop: Float = 0.0f
    private var candidateBottomCrop: Float = 0.0f
    private var stableFrameCount: Int = 0
    private var cachedDynamicZones: List<RectF>? = null
    private var lastPerimeterConfig: PerimeterConfig? = null
    private var lastAppliedTopCrop: Float = -1f
    private var lastAppliedBottomCrop: Float = -1f

    fun reset() {
        smoothingFilters.clear()
        detectedTopCrop = 0.0f
        detectedBottomCrop = 0.0f
        candidateTopCrop = 0.0f
        candidateBottomCrop = 0.0f
        stableFrameCount = 0
        cachedDynamicZones = null
        lastPerimeterConfig = null
        lastAppliedTopCrop = -1f
        lastAppliedBottomCrop = -1f
    }

    /**
     * Runs real-time letterbox scan once per frame across the image buffer.
     */
    fun scanLetterbox(image: Image) {
        val planes = image.planes
        if (planes.isEmpty()) return
        val plane = planes[0]
        val buffer = plane.buffer ?: return
        detectLetterbox(buffer, image.width, image.height, plane.rowStride, plane.pixelStride)
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

    /**
     * Extracts colors for a specific WLED device based on its configured type and region.
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

        return if (device.type == DeviceType.PERIMETER || (device.perimeter.totalLeds > 0 && device.perimeter.totalLeds == device.totalLeds)) {
            processPerimeter(buffer, width, height, rowStride, pixelStride, device, calibration, outputRgb)
        } else {
            processAmbientRegion(buffer, width, height, rowStride, pixelStride, device, calibration, outputRgb)
        }
    }

    private fun processPerimeter(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        device: WledDevice,
        calibration: ColorCalibration,
        outputRgb: ByteArray
    ): Boolean {
        val perimeter = device.perimeter

        val topCrop = if (perimeter.autoLetterbox || detectedTopCrop > 0.005f) detectedTopCrop else perimeter.topCrop
        val bottomCrop = if (perimeter.autoLetterbox || detectedBottomCrop > 0.005f) detectedBottomCrop else perimeter.bottomCrop

        val activeZones = if (cachedDynamicZones != null &&
            lastPerimeterConfig == perimeter &&
            topCrop == lastAppliedTopCrop &&
            bottomCrop == lastAppliedBottomCrop
        ) {
            cachedDynamicZones!!
        } else {
            val dynamicPerimeter = perimeter.copy(
                topCrop = topCrop,
                bottomCrop = bottomCrop
            )
            val computed = dynamicPerimeter.computeLedZones()
            cachedDynamicZones = computed
            lastPerimeterConfig = perimeter
            lastAppliedTopCrop = topCrop
            lastAppliedBottomCrop = bottomCrop
            computed
        }

        val ledCount = activeZones.size
        if (ledCount == 0) return false

        val requiredRawSize = ledCount * 3
        if (rawRgbBuffer.size < requiredRawSize) {
            rawRgbBuffer = ByteArray(requiredRawSize)
        }

        for (i in 0 until ledCount) {
            sampleChromaWeightedBox(
                buffer = buffer,
                width = width,
                height = height,
                rowStride = rowStride,
                pixelStride = pixelStride,
                zone = activeZones[i],
                calibration = calibration,
                outBuffer = rawRgbBuffer,
                outOffset = i * 3
            )
        }

        val filter = smoothingFilters.getOrPut(device.id) { ColorSmoothingFilter() }
        filter.apply(rawRgbBuffer, ledCount, calibration.smoothingFactor, outputRgb)
        return true
    }

    private fun processAmbientRegion(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        device: WledDevice,
        calibration: ColorCalibration,
        outputRgb: ByteArray
    ): Boolean {
        val ledCount = device.totalLeds.coerceAtLeast(1)
        val requiredRawSize = ledCount * 3
        if (rawRgbBuffer.size < requiredRawSize) {
            rawRgbBuffer = ByteArray(requiredRawSize)
        }

        val topCrop = if (device.perimeter.autoLetterbox || detectedTopCrop > 0.005f) detectedTopCrop else device.perimeter.topCrop
        val bottomCrop = if (device.perimeter.autoLetterbox || detectedBottomCrop > 0.005f) detectedBottomCrop else device.perimeter.bottomCrop
        val activeTop = topCrop.coerceIn(0f, 0.40f)
        val activeBottom = (1.0f - bottomCrop).coerceIn(0.60f, 1.0f)
        val activeHeight = (activeBottom - activeTop).coerceAtLeast(0.1f)

        val region = when (device.type) {
            DeviceType.PERIMETER -> RectF(0f, activeTop, 1f, activeBottom)
            DeviceType.LEFT_AMBIENT -> RectF(0f, activeTop, 0.25f, activeBottom)
            DeviceType.RIGHT_AMBIENT -> RectF(0.75f, activeTop, 1f, activeBottom)
            DeviceType.TOP_AMBIENT -> RectF(0f, activeTop, 1f, activeTop + 0.25f * activeHeight)
            DeviceType.BOTTOM_AMBIENT -> RectF(0f, activeBottom - 0.25f * activeHeight, 1f, activeBottom)
            DeviceType.FULL_SCREEN -> RectF(0f, activeTop, 1f, activeBottom)
            DeviceType.CUSTOM_BOX -> device.customRect
        }

        if (ledCount == 1) {
            // Single spot light / bulb
            sampleChromaWeightedBox(
                buffer = buffer,
                width = width,
                height = height,
                rowStride = rowStride,
                pixelStride = pixelStride,
                zone = region,
                calibration = calibration,
                outBuffer = rawRgbBuffer,
                outOffset = 0
            )
        } else {
            // Multi-LED Strip / Lightbar: Spatially slice the region across all LEDs
            for (i in 0 until ledCount) {
                val subZone = when (device.type) {
                    DeviceType.LEFT_AMBIENT -> {
                        val step = (region.bottom - region.top) / ledCount
                        val t = region.top + (i * step)
                        val b = t + step
                        RectF(region.left, t.coerceAtLeast(region.top), region.right, b.coerceAtMost(region.bottom))
                    }
                    DeviceType.RIGHT_AMBIENT -> {
                        val step = (region.bottom - region.top) / ledCount
                        val t = region.top + (i * step)
                        val b = t + step
                        RectF(region.left, t.coerceAtLeast(region.top), region.right, b.coerceAtMost(region.bottom))
                    }
                    DeviceType.TOP_AMBIENT -> {
                        val step = (region.right - region.left) / ledCount
                        val l = region.left + (i * step)
                        val r = l + step
                        RectF(l.coerceAtLeast(region.left), region.top, r.coerceAtMost(region.right), region.bottom)
                    }
                    DeviceType.BOTTOM_AMBIENT -> {
                        val step = (region.right - region.left) / ledCount
                        val l = region.left + (i * step)
                        val r = l + step
                        RectF(l.coerceAtLeast(region.left), region.top, r.coerceAtMost(region.right), region.bottom)
                    }
                    else -> {
                        val step = (region.right - region.left) / ledCount
                        val l = region.left + (i * step)
                        val r = l + step
                        RectF(l.coerceAtLeast(region.left), region.top, r.coerceAtMost(region.right), region.bottom)
                    }
                }

                sampleChromaWeightedBox(
                    buffer = buffer,
                    width = width,
                    height = height,
                    rowStride = rowStride,
                    pixelStride = pixelStride,
                    zone = subZone,
                    calibration = calibration,
                    outBuffer = rawRgbBuffer,
                    outOffset = i * 3
                )
            }
        }

        val filter = smoothingFilters.getOrPut(device.id) { ColorSmoothingFilter() }
        filter.apply(rawRgbBuffer, ledCount, calibration.smoothingFactor, outputRgb)
        return true
    }

    /**
     * Extracts color and brightness for a Home Assistant light based on its configured zone or custom bounding box.
     * Returns IntArray [R, G, B, Brightness (0-255)].
     */
    fun processHaLight(
        image: Image,
        light: HomeAssistantLight,
        calibration: ColorCalibration
    ): IntArray? {
        val planes = image.planes
        if (planes.isEmpty()) return null
        val plane = planes[0]
        val buffer = plane.buffer ?: return null

        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        val topCrop = if (detectedTopCrop > 0.005f) detectedTopCrop else 0f
        val bottomCrop = if (detectedBottomCrop > 0.005f) detectedBottomCrop else 0f
        val activeTop = topCrop.coerceIn(0f, 0.40f)
        val activeBottom = (1.0f - bottomCrop).coerceIn(0.60f, 1.0f)
        val activeHeight = (activeBottom - activeTop).coerceAtLeast(0.1f)

        val targetRect = when (light.zoneType) {
            HomeAssistantZoneType.FULL_SCREEN_AVERAGE -> RectF(0f, activeTop, 1f, activeBottom)
            HomeAssistantZoneType.LEFT_AMBIENT -> RectF(0f, activeTop, 0.25f, activeBottom)
            HomeAssistantZoneType.RIGHT_AMBIENT -> RectF(0.75f, activeTop, 1f, activeBottom)
            HomeAssistantZoneType.TOP_AMBIENT -> RectF(0f, activeTop, 1f, activeTop + 0.25f * activeHeight)
            HomeAssistantZoneType.BOTTOM_AMBIENT -> RectF(0f, activeBottom - 0.25f * activeHeight, 1f, activeBottom)
            HomeAssistantZoneType.CUSTOM_RECT -> light.customRect
        }

        sampleChromaWeightedBox(
            buffer = buffer,
            width = width,
            height = height,
            rowStride = rowStride,
            pixelStride = pixelStride,
            zone = targetRect,
            calibration = calibration,
            outBuffer = haSampleBuffer,
            outOffset = 0
        )
        val r = haSampleBuffer[0].toInt() and 0xFF
        val g = haSampleBuffer[1].toInt() and 0xFF
        val b = haSampleBuffer[2].toInt() and 0xFF
        val bri = max(r, max(g, b))
        return intArrayOf(r, g, b, bri)
    }

    private fun sampleChromaWeightedBox(
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
                val gammaR = calibration.gammaR.coerceIn(0.5f, 2.5f)
                val gammaG = calibration.gammaG.coerceIn(0.5f, 2.5f)
                val gammaB = calibration.gammaB.coerceIn(0.5f, 2.5f)
                val contrast = calibration.contrast.coerceIn(0.5f, 1.5f)
                val sat = calibration.saturation.coerceIn(0.5f, 3.0f)
                val gR = calibration.gainR.coerceIn(0.2f, 2.5f)
                val gG = calibration.gainG.coerceIn(0.2f, 2.5f)
                val gB = calibration.gainB.coerceIn(0.2f, 2.5f)
                val maxBrightness = calibration.maxBrightness.coerceIn(0, 255)

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

        outBuffer[outOffset] = outR.roundToInt().coerceIn(0, 255).toByte()
        outBuffer[outOffset + 1] = outG.roundToInt().coerceIn(0, 255).toByte()
        outBuffer[outOffset + 2] = outB.roundToInt().coerceIn(0, 255).toByte()
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

        val maxScanRows = (height * 0.30f).toInt()
        var foundTop = 0
        var foundBottom = 0

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

        if (foundTop == 0 && foundBottom == 0 && topCropPct == 0f && bottomCropPct == 0f) {
            val centerOffset = (height / 2) * rowStride + (width / 2) * pixelStride
            if (!isPixelNonBlack(buffer, centerOffset)) {
                return
            }
        }

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
        return (r > 16 || g > 16 || b > 16)
    }
}
