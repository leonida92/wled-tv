package com.wled.tv.geometry

/**
 * Immutable vector point representing an individual LED diode in the TV preview canvas.
 */
data class PreviewDiode(
    val x: Float,
    val y: Float,
    val radius: Float,
    val deviceIndex: Int,
    val ledIndex: Int,
    val totalLeds: Int
)

/**
 * Ring indicator for physical perimeter strip starting corner.
 */
data class CornerRing(
    val cx: Float,
    val cy: Float,
    val radius: Float
)

/**
 * Custom box screen region in preview canvas coordinates.
 */
data class CustomBoxZone(
    val rect: NormalizedRect,
    val deviceIndex: Int
)

/**
 * Solved TV Chassis & Multi-Device Preview Layout for [TvPerimeterPreviewView].
 */
data class TvPreviewLayout(
    val tvRect: NormalizedRect,
    val screenRect: NormalizedRect,
    val baseRadius: Float,
    val startCornerRing: CornerRing?,
    val diodes: List<PreviewDiode>,
    val fullScreenDeviceIndices: List<Int>,
    val customBoxes: List<CustomBoxZone>
)

/**
 * Perimeter border line segment with active selection state for [ZoneCanvasView].
 */
data class PerimeterBorderLine(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val isActive: Boolean
)

/**
 * Solved Zone Canvas layout vector model for [ZoneCanvasView].
 */
data class ZoneCanvasLayout(
    val screenRect: NormalizedRect,
    val topLetterbox: NormalizedRect?,
    val bottomLetterbox: NormalizedRect?,
    val leftPillarbox: NormalizedRect?,
    val rightPillarbox: NormalizedRect?,
    val zones: List<NormalizedRect>,
    val borderLines: List<PerimeterBorderLine>,
    val badgeRect: NormalizedRect,
    val badgeText: String,
    val badgeCx: Float,
    val badgeCy: Float
)

/**
 * Raw instantaneous border detection metrics from optical scanning.
 * Negative value indicates no picture edge detected (e.g. black bar or uninitialized).
 */
data class RawBorderDetection(
    val topCropPct: Float = -1f,
    val bottomCropPct: Float = -1f,
    val leftCropPct: Float = -1f,
    val rightCropPct: Float = -1f
)
