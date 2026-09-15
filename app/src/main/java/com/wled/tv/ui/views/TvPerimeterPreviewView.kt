package com.wled.tv.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.wled.tv.model.Corner
import com.wled.tv.model.DeviceType
import com.wled.tv.model.Direction
import com.wled.tv.model.PerimeterConfig
import com.wled.tv.model.WledDevice
import kotlin.math.min

class TvPerimeterPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var devices: List<WledDevice> = emptyList()
    private val deviceColors = HashMap<String, ByteArray>()
    private val deviceCounts = HashMap<String, Int>()

    private val tvBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0E0E14")
        style = Paint.Style.FILL
    }

    private val tvBezelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#22222E")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    private val tvScreenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#05050A")
        style = Paint.Style.FILL
    }

    private val tvScreenInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#12121A")
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private val tvLogoTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2A2A38")
        textSize = 13f
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.2f
    }

    private val diodeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val diodeRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1C1C24")
        style = Paint.Style.FILL
    }

    private val diodeCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val diodeHotspotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = 110
        style = Paint.Style.FILL
    }

    private val cornerIndicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val zoneBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#38BDF8")
    }

    private val zoneFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#1A38BDF8")
    }

    private class DeviceRenderGeometry(
        val deviceId: String,
        val enabled: Boolean,
        val type: DeviceType,
        val pointCoords: FloatArray,
        val pointCount: Int,
        val radius: Float,
        val idleColors: IntArray,
        val hasCornerIndicator: Boolean = false,
        val cornerX: Float = 0f,
        val cornerY: Float = 0f,
        val customBoxRect: RectF? = null
    )

    private var geometryDirty: Boolean = true
    private var lastW: Float = 0f
    private var lastH: Float = 0f
    private val cachedTvRect = RectF()
    private val cachedScreenRect = RectF()
    private val cachedGeometries = ArrayList<DeviceRenderGeometry>()
    private val disabledDiodeColor = Color.parseColor("#272733")

    fun updateDevices(devices: List<WledDevice>) {
        this.devices = devices
        geometryDirty = true
        invalidate()
    }

    fun updateConfig(config: PerimeterConfig) {
        if (devices.isEmpty()) {
            this.devices = listOf(WledDevice(perimeter = config))
        } else {
            this.devices = listOf(devices[0].copy(perimeter = config)) + devices.drop(1)
        }
        geometryDirty = true
        invalidate()
    }

    private var liveActive: Boolean = false

    val isLiveActive: Boolean
        get() = liveActive

    fun setLiveActive(active: Boolean) {
        liveActive = active
        if (!active) {
            deviceColors.clear()
            deviceCounts.clear()
            postInvalidate()
        }
    }

    fun updateDeviceLiveColors(deviceId: String, rgb: ByteArray, count: Int) {
        if (!isLiveActive) return
        var buf = deviceColors[deviceId]
        if (buf == null || buf.size != rgb.size) {
            buf = ByteArray(rgb.size)
            deviceColors[deviceId] = buf
        }
        System.arraycopy(rgb, 0, buf, 0, min(rgb.size, count * 3))
        deviceCounts[deviceId] = count
        postInvalidateOnAnimation()
    }

    fun updateLiveColors(rgb: ByteArray, count: Int) {
        val primaryId = devices.firstOrNull()?.id ?: "primary"
        updateDeviceLiveColors(primaryId, rgb, count)
    }

    fun clearLiveColors() {
        liveActive = false
        deviceColors.clear()
        deviceCounts.clear()
        postInvalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        geometryDirty = true
    }

    private fun rebuildGeometry(w: Float, h: Float) {
        if (w <= 0f || h <= 0f) return
        lastW = w
        lastH = h
        geometryDirty = false
        cachedGeometries.clear()

        val displayDevices = devices.ifEmpty { listOf(WledDevice()) }

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
                DeviceType.FULL_SCREEN, DeviceType.CUSTOM_BOX -> { /* rendered inside screen */ }
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
        cachedTvRect.set(left, top, left + tvWidth, top + tvHeight)

        val screenInset = 6f
        cachedScreenRect.set(
            cachedTvRect.left + screenInset,
            cachedTvRect.top + screenInset,
            cachedTvRect.right - screenInset,
            cachedTvRect.bottom - screenInset
        )

        var curTopLayer = 0
        var curRightLayer = 0
        var curBottomLayer = 0
        var curLeftLayer = 0

        val hsvTemp = floatArrayOf(0f, 0.85f, 0.95f)

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

                    val topPoints = ArrayList<PointF>(p.topLeds)
                    if (p.topLeds > 0) {
                        val cy = cachedTvRect.top - topOffset
                        val step = (cachedTvRect.width() - 8f) / p.topLeds
                        for (i in 0 until p.topLeds) {
                            topPoints.add(PointF(cachedTvRect.left + 4f + (i + 0.5f) * step, cy))
                        }
                    }

                    val rightPoints = ArrayList<PointF>(p.rightLeds)
                    if (p.rightLeds > 0) {
                        val cx = cachedTvRect.right + rightOffset
                        val step = (cachedTvRect.height() - 8f) / p.rightLeds
                        for (i in 0 until p.rightLeds) {
                            rightPoints.add(PointF(cx, cachedTvRect.top + 4f + (i + 0.5f) * step))
                        }
                    }

                    val bottomPoints = ArrayList<PointF>(p.bottomLeds)
                    if (p.bottomLeds > 0) {
                        val cy = cachedTvRect.bottom + bottomOffset
                        val step = (cachedTvRect.width() - 8f) / p.bottomLeds
                        for (i in 0 until p.bottomLeds) {
                            bottomPoints.add(PointF(cachedTvRect.right - 4f - (i + 0.5f) * step, cy))
                        }
                    }

                    val leftPoints = ArrayList<PointF>(p.leftLeds)
                    if (p.leftLeds > 0) {
                        val cx = cachedTvRect.left - leftOffset
                        val step = (cachedTvRect.height() - 8f) / p.leftLeds
                        for (i in 0 until p.leftLeds) {
                            leftPoints.add(PointF(cx, cachedTvRect.bottom - 4f - (i + 0.5f) * step))
                        }
                    }

                    val clockwisePoints = ArrayList<PointF>(totalLeds)
                    when (p.startCorner) {
                        Corner.BOTTOM_LEFT -> {
                            clockwisePoints.addAll(leftPoints)
                            clockwisePoints.addAll(topPoints)
                            clockwisePoints.addAll(rightPoints)
                            clockwisePoints.addAll(bottomPoints)
                        }
                        Corner.TOP_LEFT -> {
                            clockwisePoints.addAll(topPoints)
                            clockwisePoints.addAll(rightPoints)
                            clockwisePoints.addAll(bottomPoints)
                            clockwisePoints.addAll(leftPoints)
                        }
                        Corner.TOP_RIGHT -> {
                            clockwisePoints.addAll(rightPoints)
                            clockwisePoints.addAll(bottomPoints)
                            clockwisePoints.addAll(leftPoints)
                            clockwisePoints.addAll(topPoints)
                        }
                        Corner.BOTTOM_RIGHT -> {
                            clockwisePoints.addAll(bottomPoints)
                            clockwisePoints.addAll(leftPoints)
                            clockwisePoints.addAll(topPoints)
                            clockwisePoints.addAll(rightPoints)
                        }
                    }

                    val finalPoints = if (p.direction == Direction.CLOCKWISE) clockwisePoints else clockwisePoints.reversed()
                    val pointCoords = FloatArray(finalPoints.size * 2)
                    val idleColors = IntArray(finalPoints.size)
                    for (i in finalPoints.indices) {
                        val pt = finalPoints[i]
                        pointCoords[i * 2] = pt.x
                        pointCoords[i * 2 + 1] = pt.y
                        hsvTemp[0] = if (finalPoints.size > 1) (i.toFloat() / finalPoints.size) * 360f else (deviceIndex * 70f) % 360f
                        idleColors[i] = Color.HSVToColor(hsvTemp)
                    }

                    var hasCorner = false
                    var cX = 0f
                    var cY = 0f
                    if (deviceIndex == 0 && totalLeds > 1) {
                        hasCorner = true
                        cX = when (p.startCorner) {
                            Corner.TOP_LEFT, Corner.BOTTOM_LEFT -> cachedTvRect.left - leftOffset
                            Corner.TOP_RIGHT, Corner.BOTTOM_RIGHT -> cachedTvRect.right + rightOffset
                        }
                        cY = when (p.startCorner) {
                            Corner.TOP_LEFT, Corner.TOP_RIGHT -> cachedTvRect.top - topOffset
                            Corner.BOTTOM_LEFT, Corner.BOTTOM_RIGHT -> cachedTvRect.bottom + bottomOffset
                        }
                    }

                    cachedGeometries.add(
                        DeviceRenderGeometry(
                            deviceId = device.id,
                            enabled = device.enabled,
                            type = device.type,
                            pointCoords = pointCoords,
                            pointCount = finalPoints.size,
                            radius = baseRadius,
                            idleColors = idleColors,
                            hasCornerIndicator = hasCorner,
                            cornerX = cX,
                            cornerY = cY
                        )
                    )
                }

                DeviceType.LEFT_AMBIENT -> {
                    val count = device.totalLeds
                    val offset = (baseRadius * 1.8f) + (curLeftLayer++ * layerSpacing)
                    val cx = cachedTvRect.left - offset
                    val coords = FloatArray(count * 2)
                    val idleColors = IntArray(count)
                    if (count == 1) {
                        coords[0] = cx
                        coords[1] = cachedTvRect.centerY()
                        hsvTemp[0] = (deviceIndex * 70f) % 360f
                        idleColors[0] = Color.HSVToColor(hsvTemp)
                    } else {
                        val step = (cachedTvRect.height() * 0.7f) / count
                        val startY = cachedTvRect.centerY() - (step * (count - 1) / 2f)
                        for (i in 0 until count) {
                            coords[i * 2] = cx
                            coords[i * 2 + 1] = startY + (i * step)
                            hsvTemp[0] = (i.toFloat() / count) * 360f
                            idleColors[i] = Color.HSVToColor(hsvTemp)
                        }
                    }
                    cachedGeometries.add(
                        DeviceRenderGeometry(
                            deviceId = device.id,
                            enabled = device.enabled,
                            type = device.type,
                            pointCoords = coords,
                            pointCount = count,
                            radius = if (count == 1) baseRadius * 1.3f else baseRadius,
                            idleColors = idleColors
                        )
                    )
                }

                DeviceType.RIGHT_AMBIENT -> {
                    val count = device.totalLeds
                    val offset = (baseRadius * 1.8f) + (curRightLayer++ * layerSpacing)
                    val cx = cachedTvRect.right + offset
                    val coords = FloatArray(count * 2)
                    val idleColors = IntArray(count)
                    if (count == 1) {
                        coords[0] = cx
                        coords[1] = cachedTvRect.centerY()
                        hsvTemp[0] = (deviceIndex * 70f) % 360f
                        idleColors[0] = Color.HSVToColor(hsvTemp)
                    } else {
                        val step = (cachedTvRect.height() * 0.7f) / count
                        val startY = cachedTvRect.centerY() - (step * (count - 1) / 2f)
                        for (i in 0 until count) {
                            coords[i * 2] = cx
                            coords[i * 2 + 1] = startY + (i * step)
                            hsvTemp[0] = (i.toFloat() / count) * 360f
                            idleColors[i] = Color.HSVToColor(hsvTemp)
                        }
                    }
                    cachedGeometries.add(
                        DeviceRenderGeometry(
                            deviceId = device.id,
                            enabled = device.enabled,
                            type = device.type,
                            pointCoords = coords,
                            pointCount = count,
                            radius = if (count == 1) baseRadius * 1.3f else baseRadius,
                            idleColors = idleColors
                        )
                    )
                }

                DeviceType.TOP_AMBIENT -> {
                    val count = device.totalLeds
                    val offset = (baseRadius * 1.8f) + (curTopLayer++ * layerSpacing)
                    val cy = cachedTvRect.top - offset
                    val coords = FloatArray(count * 2)
                    val idleColors = IntArray(count)
                    if (count == 1) {
                        coords[0] = cachedTvRect.centerX()
                        coords[1] = cy
                        hsvTemp[0] = (deviceIndex * 70f) % 360f
                        idleColors[0] = Color.HSVToColor(hsvTemp)
                    } else {
                        val step = (cachedTvRect.width() * 0.7f) / count
                        val startX = cachedTvRect.centerX() - (step * (count - 1) / 2f)
                        for (i in 0 until count) {
                            coords[i * 2] = startX + (i * step)
                            coords[i * 2 + 1] = cy
                            hsvTemp[0] = (i.toFloat() / count) * 360f
                            idleColors[i] = Color.HSVToColor(hsvTemp)
                        }
                    }
                    cachedGeometries.add(
                        DeviceRenderGeometry(
                            deviceId = device.id,
                            enabled = device.enabled,
                            type = device.type,
                            pointCoords = coords,
                            pointCount = count,
                            radius = if (count == 1) baseRadius * 1.3f else baseRadius,
                            idleColors = idleColors
                        )
                    )
                }

                DeviceType.BOTTOM_AMBIENT -> {
                    val count = device.totalLeds
                    val offset = (baseRadius * 1.8f) + (curBottomLayer++ * layerSpacing)
                    val cy = cachedTvRect.bottom + offset
                    val coords = FloatArray(count * 2)
                    val idleColors = IntArray(count)
                    if (count == 1) {
                        coords[0] = cachedTvRect.centerX()
                        coords[1] = cy
                        hsvTemp[0] = (deviceIndex * 70f) % 360f
                        idleColors[0] = Color.HSVToColor(hsvTemp)
                    } else {
                        val step = (cachedTvRect.width() * 0.7f) / count
                        val startX = cachedTvRect.centerX() - (step * (count - 1) / 2f)
                        for (i in 0 until count) {
                            coords[i * 2] = startX + (i * step)
                            coords[i * 2 + 1] = cy
                            hsvTemp[0] = (i.toFloat() / count) * 360f
                            idleColors[i] = Color.HSVToColor(hsvTemp)
                        }
                    }
                    cachedGeometries.add(
                        DeviceRenderGeometry(
                            deviceId = device.id,
                            enabled = device.enabled,
                            type = device.type,
                            pointCoords = coords,
                            pointCount = count,
                            radius = if (count == 1) baseRadius * 1.3f else baseRadius,
                            idleColors = idleColors
                        )
                    )
                }

                DeviceType.FULL_SCREEN -> {
                    hsvTemp[0] = (deviceIndex * 70f) % 360f
                    val idleColors = intArrayOf(Color.HSVToColor(hsvTemp))
                    cachedGeometries.add(
                        DeviceRenderGeometry(
                            deviceId = device.id,
                            enabled = device.enabled,
                            type = device.type,
                            pointCoords = FloatArray(0),
                            pointCount = 0,
                            radius = 0f,
                            idleColors = idleColors
                        )
                    )
                }

                DeviceType.CUSTOM_BOX -> {
                    val region = device.customRect
                    val boxRect = RectF(
                        cachedScreenRect.left + (region.left * cachedScreenRect.width()),
                        cachedScreenRect.top + (region.top * cachedScreenRect.height()),
                        cachedScreenRect.left + (region.right * cachedScreenRect.width()),
                        cachedScreenRect.top + (region.bottom * cachedScreenRect.height())
                    )
                    hsvTemp[0] = (deviceIndex * 70f) % 360f
                    val idleColors = intArrayOf(Color.HSVToColor(hsvTemp))
                    cachedGeometries.add(
                        DeviceRenderGeometry(
                            deviceId = device.id,
                            enabled = device.enabled,
                            type = device.type,
                            pointCoords = FloatArray(0),
                            pointCount = 0,
                            radius = 0f,
                            idleColors = idleColors,
                            customBoxRect = boxRect
                        )
                    )
                }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        if (geometryDirty || w != lastW || h != lastH || cachedGeometries.isEmpty()) {
            rebuildGeometry(w, h)
        }

        // Draw TV chassis & screen
        canvas.drawRoundRect(cachedTvRect, 14f, 14f, tvBodyPaint)
        canvas.drawRoundRect(cachedTvRect, 14f, 14f, tvBezelPaint)
        canvas.drawRoundRect(cachedScreenRect, 10f, 10f, tvScreenPaint)
        canvas.drawRoundRect(cachedScreenRect, 10f, 10f, tvScreenInnerPaint)

        // TV Brand Text
        tvLogoTextPaint.textSize = (cachedTvRect.height() * 0.08f).coerceIn(11f, 18f)
        canvas.drawText(
            "WLED AMBIENT SYNC",
            cachedTvRect.centerX(),
            cachedTvRect.centerY() + tvLogoTextPaint.textSize * 0.35f,
            tvLogoTextPaint
        )

        // Draw All Device Presets
        for (geom in cachedGeometries) {
            val colors = deviceColors[geom.deviceId]
            val liveCount = deviceCounts[geom.deviceId] ?: 0
            val hasLiveColors = isLiveActive && colors != null && liveCount > 0

            when (geom.type) {
                DeviceType.FULL_SCREEN -> {
                    if (geom.enabled) {
                        val color = if (hasLiveColors && liveCount > 0 && colors!!.size >= 3) {
                            Color.rgb(colors[0].toInt() and 0xFF, colors[1].toInt() and 0xFF, colors[2].toInt() and 0xFF)
                        } else {
                            geom.idleColors[0]
                        }
                        zoneFillPaint.color = color
                        zoneFillPaint.alpha = 40
                        canvas.drawRoundRect(cachedScreenRect, 10f, 10f, zoneFillPaint)
                    }
                }

                DeviceType.CUSTOM_BOX -> {
                    val boxRect = geom.customBoxRect
                    if (boxRect != null) {
                        val color = if (hasLiveColors && liveCount > 0 && colors!!.size >= 3) {
                            Color.rgb(colors[0].toInt() and 0xFF, colors[1].toInt() and 0xFF, colors[2].toInt() and 0xFF)
                        } else {
                            geom.idleColors[0]
                        }
                        zoneBoxPaint.color = color
                        zoneFillPaint.color = color
                        zoneFillPaint.alpha = 50
                        canvas.drawRoundRect(boxRect, 6f, 6f, zoneFillPaint)
                        canvas.drawRoundRect(boxRect, 6f, 6f, zoneBoxPaint)
                    }
                }

                else -> {
                    val coords = geom.pointCoords
                    val count = geom.pointCount
                    val radius = geom.radius
                    val enabled = geom.enabled

                    for (i in 0 until count) {
                        val px = coords[i * 2]
                        val py = coords[i * 2 + 1]
                        val diodeColor = if (!enabled) {
                            disabledDiodeColor
                        } else if (hasLiveColors && i < liveCount && (i * 3 + 2) < colors!!.size) {
                            val r = colors[i * 3].toInt() and 0xFF
                            val g = colors[i * 3 + 1].toInt() and 0xFF
                            val b = colors[i * 3 + 2].toInt() and 0xFF
                            Color.rgb(r, g, b)
                        } else {
                            geom.idleColors[i]
                        }

                        if (enabled) {
                            diodeGlowPaint.color = diodeColor
                            diodeGlowPaint.alpha = 50
                            canvas.drawCircle(px, py, radius * 1.55f, diodeGlowPaint)
                        }

                        canvas.drawCircle(px, py, radius, diodeRimPaint)

                        diodeCorePaint.color = diodeColor
                        canvas.drawCircle(px, py, radius * 0.8f, diodeCorePaint)

                        if (enabled) {
                            canvas.drawCircle(px, py, radius * 0.35f, diodeHotspotPaint)
                        }
                    }

                    if (geom.hasCornerIndicator) {
                        canvas.drawCircle(geom.cornerX, geom.cornerY, radius * 1.3f, cornerIndicatorPaint)
                    }
                }
            }
        }
    }
}
