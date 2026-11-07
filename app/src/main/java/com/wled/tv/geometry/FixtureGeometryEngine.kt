package com.wled.tv.geometry

import com.wled.tv.model.AspectRatioPreset
import com.wled.tv.model.Corner
import com.wled.tv.model.DeviceType
import com.wled.tv.model.Direction
import com.wled.tv.model.PerimeterConfig
import com.wled.tv.model.WledDevice
import kotlin.math.min

enum class ActiveBorder {
    TOP,
    BOTTOM,
    LEFT,
    RIGHT,
    NONE
}

/**
 * Single-source-of-truth domain engine consolidating:
 * 1. Physical LED coordinate mapping and edge winding sequences.
 * 2. Ambient fixture spatial sub-zone partitioning.
 * 3. Dynamic letterbox & pillarbox auto-crop state, temporal hysteresis, and aspect ratio snapping.
 * 4. Zero-allocation active sampling zone caching with mutation-driven invalidation.
 * 5. Deterministic UI canvas layout vector generation for preview views.
 */
class FixtureGeometryEngine {

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

    // Zero-allocation cache: populated lazily, returned directly during steady-state 60Hz loops
    private val deviceZonesCache = HashMap<String, List<NormalizedRect>>()

    /**
     * Resets letterbox detection state and clears all zone caches.
     */
    fun reset() {
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

        deviceZonesCache.clear()
    }

    /**
     * Explicit mutation-driven invalidation: call when device configuration changes.
     */
    fun invalidateAll() {
        deviceZonesCache.clear()
    }

    /**
     * Explicit mutation-driven invalidation for an individual device.
     */
    fun invalidateDevice(deviceId: String) {
        deviceZonesCache.remove(deviceId)
    }

    /**
     * Generates normalized [0..1] sampling bounding boxes for each LED around the TV perimeter.
     * When letterbox cropping is active, left and right edges are scaled to span only between
     * topCrop and (1 - bottomCrop) so no black bars are sampled.
     */
    fun computeLedZones(config: PerimeterConfig): List<NormalizedRect> {
        val yTop = config.topCrop.coerceIn(0f, 0.40f)
        val yBottom = (1.0f - config.bottomCrop).coerceIn(0.60f, 1.0f)
        val xLeft = config.leftCrop.coerceIn(0f, 0.40f)
        val xRight = (1.0f - config.rightCrop).coerceIn(0.60f, 1.0f)

        val activeH = (yBottom - yTop).coerceAtLeast(0.1f)
        val activeW = (xRight - xLeft).coerceAtLeast(0.1f)

        val effectiveDepth = config.getEffectiveDepth()
        val depthY = effectiveDepth * activeH
        // Aspect ratio correction (16:9): ensure equal pixel sampling depth in width and height
        val depthX = (depthY * (9.0f / 16.0f)).coerceIn(0.02f, activeW * 0.40f)

        val topLeds = config.topLeds
        val rightLeds = config.rightLeds
        val bottomLeds = config.bottomLeds
        val leftLeds = config.leftLeds

        // 1. Compute physical edge arrays (each ordered clockwise)
        val topZones = ArrayList<NormalizedRect>(topLeds)
        if (topLeds > 0) {
            val step = activeW / topLeds
            for (i in 0 until topLeds) {
                val left = xLeft + (i * step)
                topZones.add(NormalizedRect(left, yTop, left + step, yTop + depthY))
            }
        }

        val rightZones = ArrayList<NormalizedRect>(rightLeds)
        if (rightLeds > 0) {
            val step = activeH / rightLeds
            for (i in 0 until rightLeds) {
                val top = yTop + (i * step)
                rightZones.add(NormalizedRect(xRight - depthX, top, xRight, top + step))
            }
        }

        val bottomZones = ArrayList<NormalizedRect>(bottomLeds)
        if (bottomLeds > 0) {
            val step = activeW / bottomLeds
            for (i in 0 until bottomLeds) {
                val right = xRight - (i * step)
                bottomZones.add(NormalizedRect(right - step, yBottom - depthY, right, yBottom))
            }
        }

        val leftZones = ArrayList<NormalizedRect>(leftLeds)
        if (leftLeds > 0) {
            val step = activeH / leftLeds
            for (i in 0 until leftLeds) {
                val bottom = yBottom - (i * step)
                leftZones.add(NormalizedRect(xLeft, bottom - step, xLeft + depthX, bottom))
            }
        }

        // 2. Assemble in clockwise order starting from the chosen corner
        val clockwiseList = ArrayList<NormalizedRect>(config.totalLeds)
        when (config.startCorner) {
            Corner.BOTTOM_LEFT -> {
                clockwiseList.addAll(leftZones)
                clockwiseList.addAll(topZones)
                clockwiseList.addAll(rightZones)
                clockwiseList.addAll(bottomZones)
            }
            Corner.TOP_LEFT -> {
                clockwiseList.addAll(topZones)
                clockwiseList.addAll(rightZones)
                clockwiseList.addAll(bottomZones)
                clockwiseList.addAll(leftZones)
            }
            Corner.TOP_RIGHT -> {
                clockwiseList.addAll(rightZones)
                clockwiseList.addAll(bottomZones)
                clockwiseList.addAll(leftZones)
                clockwiseList.addAll(topZones)
            }
            Corner.BOTTOM_RIGHT -> {
                clockwiseList.addAll(bottomZones)
                clockwiseList.addAll(leftZones)
                clockwiseList.addAll(topZones)
                clockwiseList.addAll(rightZones)
            }
        }

        // 3. If counter-clockwise, reverse sequence
        return if (config.direction == Direction.CLOCKWISE) {
            clockwiseList
        } else {
            clockwiseList.reversed()
        }
    }

    /**
     * Splits an ambient device's active screen region into individual LED sub-zones.
     */
    fun computeAmbientSubZones(type: DeviceType, region: NormalizedRect, ledCount: Int): List<NormalizedRect> {
        val list = ArrayList<NormalizedRect>(ledCount)
        for (i in 0 until ledCount) {
            val subZone = when (type) {
                DeviceType.LEFT_AMBIENT, DeviceType.RIGHT_AMBIENT -> {
                    val step = (region.bottom - region.top) / ledCount
                    val b = region.bottom - (i * step)
                    val t = b - step
                    NormalizedRect(
                        region.left,
                        t.coerceAtLeast(region.top),
                        region.right,
                        b.coerceAtMost(region.bottom)
                    )
                }
                else -> { // TOP_AMBIENT, BOTTOM_AMBIENT, FULL_SCREEN, CUSTOM_BOX
                    val step = (region.right - region.left) / ledCount
                    val l = region.left + (i * step)
                    val r = l + step
                    NormalizedRect(
                        l.coerceAtLeast(region.left),
                        region.top,
                        r.coerceAtMost(region.right),
                        region.bottom
                    )
                }
            }
            list.add(subZone)
        }
        return list
    }

    /**
     * Returns the active normalized sampling zones for a device based on current letterbox & pillarbox state.
     * Guarantees zero heap allocations during steady-state 60Hz streaming via mutation-driven caching.
     */
    fun getActiveZones(device: WledDevice): List<NormalizedRect> {
        deviceZonesCache[device.id]?.let { return it }
        val computed = computeActiveZonesForDevice(device)
        deviceZonesCache[device.id] = computed
        return computed
    }

    private fun computeActiveZonesForDevice(device: WledDevice): List<NormalizedRect> {
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
            return computeLedZones(dynamicPerimeter)
        } else {
            val activeTop = topCrop.coerceIn(0f, 0.40f)
            val activeBottom = (1.0f - bottomCrop).coerceIn(0.60f, 1.0f)
            val activeLeft = leftCrop.coerceIn(0f, 0.40f)
            val activeRight = (1.0f - rightCrop).coerceIn(0.60f, 1.0f)
            val activeHeight = (activeBottom - activeTop).coerceAtLeast(0.1f)
            val activeWidth = (activeRight - activeLeft).coerceAtLeast(0.1f)

            val region = when (device.type) {
                DeviceType.PERIMETER -> NormalizedRect(activeLeft, activeTop, activeRight, activeBottom)
                DeviceType.LEFT_AMBIENT -> NormalizedRect(activeLeft, activeTop, activeLeft + 0.25f * activeWidth, activeBottom)
                DeviceType.RIGHT_AMBIENT -> NormalizedRect(activeRight - 0.25f * activeWidth, activeTop, activeRight, activeBottom)
                DeviceType.TOP_AMBIENT -> NormalizedRect(activeLeft, activeTop, activeRight, activeTop + 0.25f * activeHeight)
                DeviceType.BOTTOM_AMBIENT -> NormalizedRect(activeLeft, activeBottom - 0.25f * activeHeight, activeRight, activeBottom)
                DeviceType.FULL_SCREEN -> NormalizedRect(activeLeft, activeTop, activeRight, activeBottom)
                DeviceType.CUSTOM_BOX -> device.customRect
            }

            val ledCount = device.totalLeds.coerceAtLeast(1)
            return if (ledCount == 1) {
                listOf(region)
            } else {
                computeAmbientSubZones(device.type, region, ledCount)
            }
        }
    }

    /**
     * Consumes raw border detection candidates with zero heap allocation.
     */
    fun updateDetectedBorders(topCropPct: Float, bottomCropPct: Float, leftCropPct: Float, rightCropPct: Float) {
        updateLetterboxCrop(topCropPct, bottomCropPct)
        updatePillarboxCrop(leftCropPct, rightCropPct)
    }

    fun updateDetectedBorders(detection: RawBorderDetection) {
        updateDetectedBorders(detection.topCropPct, detection.bottomCropPct, detection.leftCropPct, detection.rightCropPct)
    }

    private fun updateLetterboxCrop(topCropPct: Float, bottomCropPct: Float) {
        val isTopEdgeContent = topCropPct in 0.0f..0.014f
        val isBottomEdgeContent = bottomCropPct in 0.0f..0.014f

        val snappedCrop: Float = when {
            isTopEdgeContent || isBottomEdgeContent -> 0.0f
            topCropPct >= 0.014f && bottomCropPct >= 0.014f -> {
                if (topCropPct >= 0.145f && bottomCropPct >= 0.145f) {
                    0.153f // 2.55:1 Ultrawide Cinema / Anime
                } else {
                    val chosen = if (topCropPct >= 0.110f && bottomCropPct >= 0.110f) {
                        minOf(topCropPct, bottomCropPct)
                    } else {
                        topCropPct
                    }
                    when {
                        chosen >= 0.112f -> 0.130f // 2.39:1 Cinemascope
                        chosen >= 0.075f -> 0.095f // 2.20:1 70mm Large Format
                        chosen >= 0.035f -> 0.055f // 2.00:1 Univisium
                        chosen >= 0.012f -> 0.020f // 1.85:1 Flat
                        else             -> 0.0f   // 16:9 Fullscreen
                    }
                }
            }
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
            else -> return // Hold on darkness
        }

        val requiredFrames = if (snappedCrop < detectedTopCrop) 15 else 30
        if (snappedCrop == candidateTopCrop) {
            stableFrameCount++
            if (stableFrameCount >= requiredFrames) {
                if (detectedTopCrop != snappedCrop || detectedBottomCrop != snappedCrop) {
                    detectedTopCrop = snappedCrop
                    detectedBottomCrop = snappedCrop
                    deviceZonesCache.clear()
                }
                stableFrameCount = 0
            }
        } else {
            candidateTopCrop = snappedCrop
            candidateBottomCrop = snappedCrop
            stableFrameCount = 1
        }
    }

    private fun updatePillarboxCrop(leftCropPct: Float, rightCropPct: Float) {
        val isLeftEdgeContent = leftCropPct in 0.0f..0.020f
        val isRightEdgeContent = rightCropPct in 0.0f..0.020f

        val snappedPillar: Float = when {
            isLeftEdgeContent || isRightEdgeContent -> 0.0f
            leftCropPct >= 0.020f && rightCropPct >= 0.020f -> {
                val chosen = minOf(leftCropPct, rightCropPct)
                when {
                    chosen >= 0.095f -> 0.125f // 4:3 Classic Pillarbox
                    chosen >= 0.045f -> 0.065f // 1.66:1 European Widescreen
                    else             -> 0.0f   // 16:9 Fullscreen
                }
            }
            detectedLeftCrop == 0.0f && (leftCropPct >= 0.020f || rightCropPct >= 0.020f) -> {
                val single = if (leftCropPct >= 0.020f) leftCropPct else rightCropPct
                when {
                    single >= 0.095f -> 0.125f
                    single >= 0.045f -> 0.065f
                    else             -> 0.0f
                }
            }
            else -> return // Hold
        }

        val requiredFrames = if (snappedPillar < detectedLeftCrop) 15 else 30
        if (snappedPillar == candidateLeftCrop) {
            stablePillarFrameCount++
            if (stablePillarFrameCount >= requiredFrames) {
                if (detectedLeftCrop != snappedPillar || detectedRightCrop != snappedPillar) {
                    detectedLeftCrop = snappedPillar
                    detectedRightCrop = snappedPillar
                    deviceZonesCache.clear()
                }
                stablePillarFrameCount = 0
            }
        } else {
            candidateLeftCrop = snappedPillar
            candidateRightCrop = snappedPillar
            stablePillarFrameCount = 1
        }
    }

    /**
     * Solves the complete TV chassis, bezel, screen, and diode placement vectors for [TvPerimeterPreviewView].
     */
    fun calculatePreviewLayout(
        w: Float,
        h: Float,
        devices: List<WledDevice>
    ): TvPreviewLayout {
        if (w <= 0f || h <= 0f) {
            return TvPreviewLayout(
                tvRect = NormalizedRect.ZERO,
                screenRect = NormalizedRect.ZERO,
                baseRadius = 4f,
                startCornerRing = null,
                diodes = emptyList(),
                fullScreenDeviceIndices = emptyList(),
                customBoxes = emptyList()
            )
        }

        val displayDevices = if (devices.isEmpty()) listOf(WledDevice()) else devices

        // 1. Calculate layer counts per edge
        var leftLayers = 0
        var rightLayers = 0
        var topLayers = 0
        var bottomLayers = 0

        for (device in displayDevices) {
            when (device.type) {
                DeviceType.PERIMETER -> {
                    val p = device.perimeter
                    if (p.leftLeds > 0) leftLayers++
                    if (p.rightLeds > 0) rightLayers++
                    if (p.topLeds > 0) topLayers++
                    if (p.bottomLeds > 0) bottomLayers++
                }
                DeviceType.LEFT_AMBIENT -> leftLayers++
                DeviceType.RIGHT_AMBIENT -> rightLayers++
                DeviceType.TOP_AMBIENT -> topLayers++
                DeviceType.BOTTOM_AMBIENT -> bottomLayers++
                DeviceType.FULL_SCREEN, DeviceType.CUSTOM_BOX -> { /* inside screen */ }
            }
        }

        leftLayers = leftLayers.coerceAtLeast(1)
        rightLayers = rightLayers.coerceAtLeast(1)
        topLayers = topLayers.coerceAtLeast(1)
        bottomLayers = bottomLayers.coerceAtLeast(1)

        val baseRadius = (min(w, h) * 0.019f).coerceIn(3.0f, 7.5f)
        val layerSpacing = baseRadius * 3.0f

        val padLeft = (baseRadius * 2.2f) + (leftLayers * layerSpacing)
        val padRight = (baseRadius * 2.2f) + (rightLayers * layerSpacing)
        val padTop = (baseRadius * 2.2f) + (topLayers * layerSpacing)
        val padBottom = (baseRadius * 2.2f) + (bottomLayers * layerSpacing)

        val availW = (w - padLeft - padRight).coerceAtLeast(80f)
        val availH = (h - padTop - padBottom).coerceAtLeast(45f)

        val targetRatio = 16f / 9f
        val tvWidth: Float
        val tvHeight: Float

        if (availW / availH > targetRatio) {
            tvHeight = availH
            tvWidth = tvHeight * targetRatio
        } else {
            tvWidth = availW
            tvHeight = tvWidth / targetRatio
        }

        val left = padLeft + (availW - tvWidth) / 2f
        val top = padTop + (availH - tvHeight) / 2f
        val tvRect = NormalizedRect(left, top, left + tvWidth, top + tvHeight)

        val screenInset = 6f
        val screenRect = NormalizedRect(
            tvRect.left + screenInset,
            tvRect.top + screenInset,
            tvRect.right - screenInset,
            tvRect.bottom - screenInset
        )

        val diodes = ArrayList<PreviewDiode>()
        val fullScreenDeviceIndices = ArrayList<Int>()
        val customBoxes = ArrayList<CustomBoxZone>()
        var startCornerRing: CornerRing? = null

        var curTopLayer = 0
        var curRightLayer = 0
        var curBottomLayer = 0
        var curLeftLayer = 0

        for ((deviceIndex, device) in displayDevices.withIndex()) {
            when (device.type) {
                DeviceType.PERIMETER -> {
                    val p = device.perimeter
                    val totalLeds = p.totalLeds
                    if (totalLeds <= 0) continue

                    val topOffset = (baseRadius * 1.8f) + (curTopLayer++ * layerSpacing)
                    val rightOffset = (baseRadius * 1.8f) + (curRightLayer++ * layerSpacing)
                    val bottomOffset = (baseRadius * 1.8f) + (curBottomLayer++ * layerSpacing)
                    val leftOffset = (baseRadius * 1.8f) + (curLeftLayer++ * layerSpacing)

                    val coords = FloatArray(totalLeds * 2)

                    fun fillEdge(edge: Int, offset: Int): Int {
                        var cur = offset
                        when (edge) {
                            0 -> { // LEFT
                                if (p.leftLeds > 0) {
                                    val cx = tvRect.left - leftOffset
                                    val step = (tvRect.height - 8f) / p.leftLeds
                                    for (i in 0 until p.leftLeds) {
                                        coords[cur++] = cx
                                        coords[cur++] = tvRect.bottom - 4f - (i + 0.5f) * step
                                    }
                                }
                            }
                            1 -> { // TOP
                                if (p.topLeds > 0) {
                                    val cy = tvRect.top - topOffset
                                    val step = (tvRect.width - 8f) / p.topLeds
                                    for (i in 0 until p.topLeds) {
                                        coords[cur++] = tvRect.left + 4f + (i + 0.5f) * step
                                        coords[cur++] = cy
                                    }
                                }
                            }
                            2 -> { // RIGHT
                                if (p.rightLeds > 0) {
                                    val cx = tvRect.right + rightOffset
                                    val step = (tvRect.height - 8f) / p.rightLeds
                                    for (i in 0 until p.rightLeds) {
                                        coords[cur++] = cx
                                        coords[cur++] = tvRect.top + 4f + (i + 0.5f) * step
                                    }
                                }
                            }
                            3 -> { // BOTTOM
                                if (p.bottomLeds > 0) {
                                    val cy = tvRect.bottom + bottomOffset
                                    val step = (tvRect.width - 8f) / p.bottomLeds
                                    for (i in 0 until p.bottomLeds) {
                                        coords[cur++] = tvRect.right - 4f - (i + 0.5f) * step
                                        coords[cur++] = cy
                                    }
                                }
                            }
                        }
                        return cur
                    }

                    var ptr = 0
                    when (p.startCorner) {
                        Corner.BOTTOM_LEFT -> {
                            ptr = fillEdge(0, ptr) // Left
                            ptr = fillEdge(1, ptr) // Top
                            ptr = fillEdge(2, ptr) // Right
                            fillEdge(3, ptr) // Bottom
                        }
                        Corner.TOP_LEFT -> {
                            ptr = fillEdge(1, ptr) // Top
                            ptr = fillEdge(2, ptr) // Right
                            ptr = fillEdge(3, ptr) // Bottom
                            fillEdge(0, ptr) // Left
                        }
                        Corner.TOP_RIGHT -> {
                            ptr = fillEdge(2, ptr) // Right
                            ptr = fillEdge(3, ptr) // Bottom
                            ptr = fillEdge(0, ptr) // Left
                            fillEdge(1, ptr) // Top
                        }
                        Corner.BOTTOM_RIGHT -> {
                            ptr = fillEdge(3, ptr) // Bottom
                            ptr = fillEdge(0, ptr) // Left
                            ptr = fillEdge(1, ptr) // Top
                            fillEdge(2, ptr) // Right
                        }
                    }

                    val isClockwise = (p.direction == Direction.CLOCKWISE)
                    for (i in 0 until totalLeds) {
                        val srcIdx = if (isClockwise) i else (totalLeds - 1 - i)
                        val px = coords[srcIdx * 2]
                        val py = coords[srcIdx * 2 + 1]
                        diodes.add(PreviewDiode(px, py, baseRadius, deviceIndex, i, totalLeds))
                    }

                    if (deviceIndex == 0 && totalLeds > 1 && startCornerRing == null) {
                        val cornerX = when (p.startCorner) {
                            Corner.TOP_LEFT, Corner.BOTTOM_LEFT -> tvRect.left - leftOffset
                            Corner.TOP_RIGHT, Corner.BOTTOM_RIGHT -> tvRect.right + rightOffset
                        }
                        val cornerY = when (p.startCorner) {
                            Corner.TOP_LEFT, Corner.TOP_RIGHT -> tvRect.top - topOffset
                            Corner.BOTTOM_LEFT, Corner.BOTTOM_RIGHT -> tvRect.bottom + bottomOffset
                        }
                        startCornerRing = CornerRing(cornerX, cornerY, baseRadius * 1.3f)
                    }
                }

                DeviceType.LEFT_AMBIENT -> {
                    val count = device.totalLeds
                    val offset = (baseRadius * 1.8f) + (curLeftLayer++ * layerSpacing)
                    val cx = tvRect.left - offset
                    val radius = if (count == 1) baseRadius * 1.3f else baseRadius
                    if (count == 1) {
                        diodes.add(PreviewDiode(cx, tvRect.centerY, radius, deviceIndex, 0, 1))
                    } else {
                        val step = (tvRect.height * 0.7f) / count
                        val startY = tvRect.centerY - (step * (count - 1) / 2f)
                        for (i in 0 until count) {
                            diodes.add(PreviewDiode(cx, startY + (i * step), radius, deviceIndex, i, count))
                        }
                    }
                }

                DeviceType.RIGHT_AMBIENT -> {
                    val count = device.totalLeds
                    val offset = (baseRadius * 1.8f) + (curRightLayer++ * layerSpacing)
                    val cx = tvRect.right + offset
                    val radius = if (count == 1) baseRadius * 1.3f else baseRadius
                    if (count == 1) {
                        diodes.add(PreviewDiode(cx, tvRect.centerY, radius, deviceIndex, 0, 1))
                    } else {
                        val step = (tvRect.height * 0.7f) / count
                        val startY = tvRect.centerY - (step * (count - 1) / 2f)
                        for (i in 0 until count) {
                            diodes.add(PreviewDiode(cx, startY + (i * step), radius, deviceIndex, i, count))
                        }
                    }
                }

                DeviceType.TOP_AMBIENT -> {
                    val count = device.totalLeds
                    val offset = (baseRadius * 1.8f) + (curTopLayer++ * layerSpacing)
                    val cy = tvRect.top - offset
                    val radius = if (count == 1) baseRadius * 1.3f else baseRadius
                    if (count == 1) {
                        diodes.add(PreviewDiode(tvRect.centerX, cy, radius, deviceIndex, 0, 1))
                    } else {
                        val step = (tvRect.width * 0.7f) / count
                        val startX = tvRect.centerX - (step * (count - 1) / 2f)
                        for (i in 0 until count) {
                            diodes.add(PreviewDiode(startX + (i * step), cy, radius, deviceIndex, i, count))
                        }
                    }
                }

                DeviceType.BOTTOM_AMBIENT -> {
                    val count = device.totalLeds
                    val offset = (baseRadius * 1.8f) + (curBottomLayer++ * layerSpacing)
                    val cy = tvRect.bottom + offset
                    val radius = if (count == 1) baseRadius * 1.3f else baseRadius
                    if (count == 1) {
                        diodes.add(PreviewDiode(tvRect.centerX, cy, radius, deviceIndex, 0, 1))
                    } else {
                        val step = (tvRect.width * 0.7f) / count
                        val startX = tvRect.centerX - (step * (count - 1) / 2f)
                        for (i in 0 until count) {
                            diodes.add(PreviewDiode(startX + (i * step), cy, radius, deviceIndex, i, count))
                        }
                    }
                }

                DeviceType.FULL_SCREEN -> {
                    fullScreenDeviceIndices.add(deviceIndex)
                }

                DeviceType.CUSTOM_BOX -> {
                    val region = device.customRect
                    val boxRect = NormalizedRect(
                        screenRect.left + (region.left * screenRect.width),
                        screenRect.top + (region.top * screenRect.height),
                        screenRect.left + (region.right * screenRect.width),
                        screenRect.top + (region.bottom * screenRect.height)
                    )
                    customBoxes.add(CustomBoxZone(boxRect, deviceIndex))
                }
            }
        }

        return TvPreviewLayout(
            tvRect = tvRect,
            screenRect = screenRect,
            baseRadius = baseRadius,
            startCornerRing = startCornerRing,
            diodes = diodes,
            fullScreenDeviceIndices = fullScreenDeviceIndices,
            customBoxes = customBoxes
        )
    }

    /**
     * Solves the complete zone canvas layout vectors for [ZoneCanvasView].
     */
    fun calculateZoneCanvasLayout(
        w: Float,
        h: Float,
        config: PerimeterConfig,
        activeBorder: ActiveBorder
    ): ZoneCanvasLayout {
        if (w <= 0f || h <= 0f) {
            return ZoneCanvasLayout(
                screenRect = NormalizedRect.ZERO,
                topLetterbox = null,
                bottomLetterbox = null,
                leftPillarbox = null,
                rightPillarbox = null,
                zones = emptyList(),
                borderLines = emptyList(),
                badgeRect = NormalizedRect.ZERO,
                badgeText = "",
                badgeCx = 0f,
                badgeCy = 0f
            )
        }

        val screenRect = NormalizedRect(0f, 0f, w, h)

        val yTop = config.topCrop * h
        val yBottom = (1.0f - config.bottomCrop) * h
        val xLeft = config.leftCrop * w
        val xRight = (1.0f - config.rightCrop) * w

        val topLetterbox = if (config.topCrop > 0.001f) NormalizedRect(0f, 0f, w, yTop) else null
        val bottomLetterbox = if (config.bottomCrop > 0.001f) NormalizedRect(0f, yBottom, w, h) else null
        val leftPillarbox = if (config.leftCrop > 0.001f) NormalizedRect(0f, yTop, xLeft, yBottom) else null
        val rightPillarbox = if (config.rightCrop > 0.001f) NormalizedRect(xRight, yTop, w, yBottom) else null

        val normZones = computeLedZones(config)
        val pixelZones = normZones.map { z ->
            NormalizedRect(z.left * w, z.top * h, z.right * w, z.bottom * h)
        }

        val borderLines = listOf(
            PerimeterBorderLine(xLeft, yTop, xRight, yTop, activeBorder == ActiveBorder.TOP),
            PerimeterBorderLine(xLeft, yBottom, xRight, yBottom, activeBorder == ActiveBorder.BOTTOM),
            PerimeterBorderLine(xLeft, yTop, xLeft, yBottom, activeBorder == ActiveBorder.LEFT),
            PerimeterBorderLine(xRight, yTop, xRight, yBottom, activeBorder == ActiveBorder.RIGHT)
        )

        val activePreset = config.getActiveAspectRatioPreset()
        val badgeText = activePreset.displayName
        val badgeW = 340f
        val badgeH = 54f
        val badgeCx = w / 2f
        val badgeCy = (yTop + yBottom) / 2f
        val badgeRect = NormalizedRect(
            badgeCx - badgeW / 2f,
            badgeCy - badgeH / 2f,
            badgeCx + badgeW / 2f,
            badgeCy + badgeH / 2f
        )

        return ZoneCanvasLayout(
            screenRect = screenRect,
            topLetterbox = topLetterbox,
            bottomLetterbox = bottomLetterbox,
            leftPillarbox = leftPillarbox,
            rightPillarbox = rightPillarbox,
            zones = pixelZones,
            borderLines = borderLines,
            badgeRect = badgeRect,
            badgeText = badgeText,
            badgeCx = badgeCx,
            badgeCy = badgeCy
        )
    }
}
