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

    private val reusableTvRect = RectF()
    private val reusableScreenRect = RectF()
    private val defaultDeviceList = listOf(WledDevice())
    private var perimeterCoords = FloatArray(1024)

    fun updateDevices(devices: List<WledDevice>) {
        this.devices = devices
        invalidate()
    }

    fun updateConfig(config: PerimeterConfig) {
        if (devices.isEmpty()) {
            this.devices = listOf(WledDevice(perimeter = config))
        } else {
            this.devices = listOf(devices[0].copy(perimeter = config)) + devices.drop(1)
        }
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val displayDevices = if (devices.isEmpty()) defaultDeviceList else devices

        // 1. Calculate layer counts per edge to guarantee no preset is ever pushed off canvas
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

        // Dynamic diode radius and layer spacing
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
        reusableTvRect.set(left, top, left + tvWidth, top + tvHeight)
        val tvRect = reusableTvRect

        // 2. Draw TV chassis & screen
        canvas.drawRoundRect(tvRect, 14f, 14f, tvBodyPaint)
        canvas.drawRoundRect(tvRect, 14f, 14f, tvBezelPaint)

        val screenInset = 6f
        reusableScreenRect.set(
            tvRect.left + screenInset,
            tvRect.top + screenInset,
            tvRect.right - screenInset,
            tvRect.bottom - screenInset
        )
        val screenRect = reusableScreenRect
        canvas.drawRoundRect(screenRect, 10f, 10f, tvScreenPaint)
        canvas.drawRoundRect(screenRect, 10f, 10f, tvScreenInnerPaint)

        // TV Brand Text
        tvLogoTextPaint.textSize = (tvHeight * 0.08f).coerceIn(11f, 18f)
        canvas.drawText("WLED AMBIENT SYNC", tvRect.centerX(), tvRect.centerY() + tvLogoTextPaint.textSize * 0.35f, tvLogoTextPaint)

        // 3. Draw All Device Presets
        var curTopLayer = 0
        var curRightLayer = 0
        var curBottomLayer = 0
        var curLeftLayer = 0

        for ((deviceIndex, device) in displayDevices.withIndex()) {
            val colors = deviceColors[device.id]
            val liveCount = deviceCounts[device.id] ?: 0
            val hasLiveColors = isLiveActive && colors != null && liveCount > 0

            fun getDeviceLedColor(index: Int, total: Int): Int {
                if (!device.enabled) {
                    return Color.parseColor("#272733")
                }
                if (hasLiveColors && index < liveCount && (index * 3 + 2) < colors!!.size) {
                    val r = colors[index * 3].toInt() and 0xFF
                    val g = colors[index * 3 + 1].toInt() and 0xFF
                    val b = colors[index * 3 + 2].toInt() and 0xFF
                    return Color.rgb(r, g, b)
                }
                // Idle multi-chroma ambient gradient
                val hue = if (total > 1) (index.toFloat() / total) * 360f else (deviceIndex * 70f) % 360f
                return Color.HSVToColor(floatArrayOf(hue, 0.85f, 0.95f))
            }

            fun drawDiode(x: Float, y: Float, index: Int, total: Int, radius: Float) {
                val diodeColor = getDeviceLedColor(index, total)

                if (device.enabled) {
                    diodeGlowPaint.color = diodeColor
                    diodeGlowPaint.alpha = 50
                    canvas.drawCircle(x, y, radius * 1.55f, diodeGlowPaint)
                }

                canvas.drawCircle(x, y, radius, diodeRimPaint)

                diodeCorePaint.color = diodeColor
                canvas.drawCircle(x, y, radius * 0.8f, diodeCorePaint)

                if (device.enabled) {
                    canvas.drawCircle(x, y, radius * 0.35f, diodeHotspotPaint)
                }
            }

            when (device.type) {
                DeviceType.PERIMETER -> {
                    val p = device.perimeter
                    val totalLeds = p.totalLeds
                    if (totalLeds <= 0) continue

                    if (perimeterCoords.size < totalLeds * 2) {
                        perimeterCoords = FloatArray(totalLeds * 2)
                    }

                    val topOffset = (baseRadius * 1.8f) + (curTopLayer++ * layerSpacing)
                    val rightOffset = (baseRadius * 1.8f) + (curRightLayer++ * layerSpacing)
                    val bottomOffset = (baseRadius * 1.8f) + (curBottomLayer++ * layerSpacing)
                    val leftOffset = (baseRadius * 1.8f) + (curLeftLayer++ * layerSpacing)

                    fun fillEdge(edge: Int, offset: Int): Int {
                        var cur = offset
                        when (edge) {
                            0 -> { // LEFT
                                if (p.leftLeds > 0) {
                                    val cx = tvRect.left - leftOffset
                                    val step = (tvRect.height() - 8f) / p.leftLeds
                                    for (i in 0 until p.leftLeds) {
                                        perimeterCoords[cur++] = cx
                                        perimeterCoords[cur++] = tvRect.bottom - 4f - (i + 0.5f) * step
                                    }
                                }
                            }
                            1 -> { // TOP
                                if (p.topLeds > 0) {
                                    val cy = tvRect.top - topOffset
                                    val step = (tvRect.width() - 8f) / p.topLeds
                                    for (i in 0 until p.topLeds) {
                                        perimeterCoords[cur++] = tvRect.left + 4f + (i + 0.5f) * step
                                        perimeterCoords[cur++] = cy
                                    }
                                }
                            }
                            2 -> { // RIGHT
                                if (p.rightLeds > 0) {
                                    val cx = tvRect.right + rightOffset
                                    val step = (tvRect.height() - 8f) / p.rightLeds
                                    for (i in 0 until p.rightLeds) {
                                        perimeterCoords[cur++] = cx
                                        perimeterCoords[cur++] = tvRect.top + 4f + (i + 0.5f) * step
                                    }
                                }
                            }
                            3 -> { // BOTTOM
                                if (p.bottomLeds > 0) {
                                    val cy = tvRect.bottom + bottomOffset
                                    val step = (tvRect.width() - 8f) / p.bottomLeds
                                    for (i in 0 until p.bottomLeds) {
                                        perimeterCoords[cur++] = tvRect.right - 4f - (i + 0.5f) * step
                                        perimeterCoords[cur++] = cy
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
                        val px = perimeterCoords[srcIdx * 2]
                        val py = perimeterCoords[srcIdx * 2 + 1]
                        drawDiode(px, py, i, totalLeds, baseRadius)
                    }

                    // Subtle start corner ring
                    if (deviceIndex == 0 && totalLeds > 1) {
                        val cornerX = when (p.startCorner) {
                            Corner.TOP_LEFT, Corner.BOTTOM_LEFT -> tvRect.left - leftOffset
                            Corner.TOP_RIGHT, Corner.BOTTOM_RIGHT -> tvRect.right + rightOffset
                        }
                        val cornerY = when (p.startCorner) {
                            Corner.TOP_LEFT, Corner.TOP_RIGHT -> tvRect.top - topOffset
                            Corner.BOTTOM_LEFT, Corner.BOTTOM_RIGHT -> tvRect.bottom + bottomOffset
                        }
                        canvas.drawCircle(cornerX, cornerY, baseRadius * 1.3f, cornerIndicatorPaint)
                    }
                }

                DeviceType.LEFT_AMBIENT -> {
                    val count = device.totalLeds
                    val offset = (baseRadius * 1.8f) + (curLeftLayer++ * layerSpacing)
                    val cx = tvRect.left - offset
                    val radius = if (count == 1) baseRadius * 1.3f else baseRadius
                    if (count == 1) {
                        drawDiode(cx, tvRect.centerY(), 0, 1, radius)
                    } else {
                        val step = (tvRect.height() * 0.7f) / count
                        val startY = tvRect.centerY() - (step * (count - 1) / 2f)
                        for (i in 0 until count) {
                            drawDiode(cx, startY + (i * step), i, count, radius)
                        }
                    }
                }

                DeviceType.RIGHT_AMBIENT -> {
                    val count = device.totalLeds
                    val offset = (baseRadius * 1.8f) + (curRightLayer++ * layerSpacing)
                    val cx = tvRect.right + offset
                    val radius = if (count == 1) baseRadius * 1.3f else baseRadius
                    if (count == 1) {
                        drawDiode(cx, tvRect.centerY(), 0, 1, radius)
                    } else {
                        val step = (tvRect.height() * 0.7f) / count
                        val startY = tvRect.centerY() - (step * (count - 1) / 2f)
                        for (i in 0 until count) {
                            drawDiode(cx, startY + (i * step), i, count, radius)
                        }
                    }
                }

                DeviceType.TOP_AMBIENT -> {
                    val count = device.totalLeds
                    val offset = (baseRadius * 1.8f) + (curTopLayer++ * layerSpacing)
                    val cy = tvRect.top - offset
                    val radius = if (count == 1) baseRadius * 1.3f else baseRadius
                    if (count == 1) {
                        drawDiode(tvRect.centerX(), cy, 0, 1, radius)
                    } else {
                        val step = (tvRect.width() * 0.7f) / count
                        val startX = tvRect.centerX() - (step * (count - 1) / 2f)
                        for (i in 0 until count) {
                            drawDiode(startX + (i * step), cy, i, count, radius)
                        }
                    }
                }

                DeviceType.BOTTOM_AMBIENT -> {
                    val count = device.totalLeds
                    val offset = (baseRadius * 1.8f) + (curBottomLayer++ * layerSpacing)
                    val cy = tvRect.bottom + offset
                    val radius = if (count == 1) baseRadius * 1.3f else baseRadius
                    if (count == 1) {
                        drawDiode(tvRect.centerX(), cy, 0, 1, radius)
                    } else {
                        val step = (tvRect.width() * 0.7f) / count
                        val startX = tvRect.centerX() - (step * (count - 1) / 2f)
                        for (i in 0 until count) {
                            drawDiode(startX + (i * step), cy, i, count, radius)
                        }
                    }
                }

                DeviceType.FULL_SCREEN -> {
                    if (device.enabled) {
                        val color = getDeviceLedColor(0, 1)
                        zoneFillPaint.color = color
                        zoneFillPaint.alpha = 40
                        canvas.drawRoundRect(screenRect, 10f, 10f, zoneFillPaint)
                    }
                }

                DeviceType.CUSTOM_BOX -> {
                    val region = device.customRect
                    val boxRect = RectF(
                        screenRect.left + (region.left * screenRect.width()),
                        screenRect.top + (region.top * screenRect.height()),
                        screenRect.left + (region.right * screenRect.width()),
                        screenRect.top + (region.bottom * screenRect.height())
                    )
                    val color = getDeviceLedColor(0, 1)
                    zoneBoxPaint.color = color
                    zoneFillPaint.color = color
                    zoneFillPaint.alpha = 50
                    canvas.drawRoundRect(boxRect, 6f, 6f, zoneFillPaint)
                    canvas.drawRoundRect(boxRect, 6f, 6f, zoneBoxPaint)
                }
            }
        }
    }
}
