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
        color = Color.parseColor("#0F172A")
        style = Paint.Style.FILL
    }

    private val tvBezelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#334155")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val tvScreenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#020617")
        style = Paint.Style.FILL
    }

    private val ledPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val ledBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#475569")
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private val cornerIndicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.FILL
    }

    fun updateDevices(devices: List<WledDevice>) {
        this.devices = devices
        invalidate()
    }

    fun updateConfig(config: PerimeterConfig) {
        // Legacy single-config support
        if (devices.isEmpty()) {
            this.devices = listOf(WledDevice(perimeter = config))
        } else {
            this.devices = listOf(devices[0].copy(perimeter = config)) + devices.drop(1)
        }
        invalidate()
    }

    fun updateDeviceLiveColors(deviceId: String, rgb: ByteArray, count: Int) {
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val activeDevices = devices.filter { it.enabled }.ifEmpty { devices.take(1) }
        val deviceCount = activeDevices.size.coerceAtLeast(1)

        // Scale padding based on number of devices so all offset strips fit comfortably
        val paddingScale = (0.84f - (deviceCount - 1) * 0.05f).coerceIn(0.68f, 0.86f)
        val availW = w * paddingScale
        val availH = h * paddingScale

        val targetRatio = 16f / 9f
        val currentRatio = availW / availH

        val tvWidth: Float
        val tvHeight: Float

        if (currentRatio > targetRatio) {
            tvHeight = availH
            tvWidth = tvHeight * targetRatio
        } else {
            tvWidth = availW
            tvHeight = tvWidth / targetRatio
        }

        val left = (w - tvWidth) / 2f
        val top = (h - tvHeight) / 2f
        val tvRect = RectF(left, top, left + tvWidth, top + tvHeight)

        // Draw TV chassis / screen (16:9)
        canvas.drawRoundRect(tvRect, 12f, 12f, tvBodyPaint)
        canvas.drawRoundRect(tvRect, 12f, 12f, tvBezelPaint)

        val screenInset = 8f
        val screenRect = RectF(
            tvRect.left + screenInset,
            tvRect.top + screenInset,
            tvRect.right - screenInset,
            tvRect.bottom - screenInset
        )
        canvas.drawRoundRect(screenRect, 8f, 8f, tvScreenPaint)

        val ledRadius = min(tvWidth, tvHeight) * 0.015f

        // Draw each device's LED strip with outward offset for secondary devices
        for ((deviceIndex, device) in activeDevices.withIndex()) {
            val perimeterConfig = device.getEffectivePerimeter()
            val totalLeds = perimeterConfig.totalLeds
            if (totalLeds <= 0) continue

            val colors = deviceColors[device.id]
            val liveCount = deviceCounts[device.id] ?: 0

            fun getLedColor(index: Int): Int {
                if (colors != null && index < liveCount && (index * 3 + 2) < colors.size) {
                    val r = colors[index * 3].toInt() and 0xFF
                    val g = colors[index * 3 + 1].toInt() and 0xFF
                    val b = colors[index * 3 + 2].toInt() and 0xFF
                    return Color.rgb(r, g, b)
                }
                return if (deviceIndex == 0) Color.parseColor("#3B82F6") else Color.parseColor("#00E5FF")
            }

            val offset = deviceIndex * (ledRadius * 3.5f)

            val topLeds = perimeterConfig.topLeds
            val rightLeds = perimeterConfig.rightLeds
            val bottomLeds = perimeterConfig.bottomLeds
            val leftLeds = perimeterConfig.leftLeds

            // 1. Calculate physical dot coordinates for each edge with device offset
            val topPoints = ArrayList<PointF>(topLeds)
            if (topLeds > 0) {
                val cy = tvRect.top - (ledRadius * 1.6f) - offset
                if (topLeds == 1) {
                    topPoints.add(PointF(tvRect.centerX(), cy))
                } else {
                    val step = (tvRect.width() - (ledRadius * 4)) / topLeds
                    for (i in 0 until topLeds) {
                        val cx = tvRect.left + (ledRadius * 2) + (i * step) + (step / 2)
                        topPoints.add(PointF(cx, cy))
                    }
                }
            }

            val rightPoints = ArrayList<PointF>(rightLeds)
            if (rightLeds > 0) {
                val cx = tvRect.right + (ledRadius * 1.6f) + offset
                if (rightLeds == 1) {
                    rightPoints.add(PointF(cx, tvRect.centerY()))
                } else {
                    val step = (tvRect.height() - (ledRadius * 4)) / rightLeds
                    for (i in 0 until rightLeds) {
                        val cy = tvRect.top + (ledRadius * 2) + (i * step) + (step / 2)
                        rightPoints.add(PointF(cx, cy))
                    }
                }
            }

            val bottomPoints = ArrayList<PointF>(bottomLeds)
            if (bottomLeds > 0) {
                val cy = tvRect.bottom + (ledRadius * 1.6f) + offset
                if (bottomLeds == 1) {
                    bottomPoints.add(PointF(tvRect.centerX(), cy))
                } else {
                    val step = (tvRect.width() - (ledRadius * 4)) / bottomLeds
                    for (i in 0 until bottomLeds) {
                        val cx = tvRect.right - (ledRadius * 2) - (i * step) - (step / 2)
                        bottomPoints.add(PointF(cx, cy))
                    }
                }
            }

            val leftPoints = ArrayList<PointF>(leftLeds)
            if (leftLeds > 0) {
                val cx = tvRect.left - (ledRadius * 1.6f) - offset
                if (leftLeds == 1) {
                    leftPoints.add(PointF(cx, tvRect.centerY()))
                } else {
                    val step = (tvRect.height() - (ledRadius * 4)) / leftLeds
                    for (i in 0 until leftLeds) {
                        val cy = tvRect.bottom - (ledRadius * 2) - (i * step) - (step / 2)
                        leftPoints.add(PointF(cx, cy))
                    }
                }
            }

            // 2. Assemble ordered points matching device's startCorner
            val clockwisePoints = ArrayList<PointF>(totalLeds)
            when (perimeterConfig.startCorner) {
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

            val finalPoints = if (perimeterConfig.direction == Direction.CLOCKWISE) {
                clockwisePoints
            } else {
                clockwisePoints.reversed()
            }

            // 3. Draw each LED dot with its live color
            for (i in 0 until finalPoints.size) {
                val pt = finalPoints[i]
                ledPaint.color = getLedColor(i)
                canvas.drawCircle(pt.x, pt.y, ledRadius, ledPaint)
                canvas.drawCircle(pt.x, pt.y, ledRadius, ledBorderPaint)
            }

            // Draw start corner marker for primary device or multi-edge strips
            if (deviceIndex == 0 && totalLeds > 1) {
                val cornerX = when (perimeterConfig.startCorner) {
                    Corner.TOP_LEFT, Corner.BOTTOM_LEFT -> tvRect.left
                    Corner.TOP_RIGHT, Corner.BOTTOM_RIGHT -> tvRect.right
                }
                val cornerY = when (perimeterConfig.startCorner) {
                    Corner.TOP_LEFT, Corner.TOP_RIGHT -> tvRect.top
                    Corner.BOTTOM_LEFT, Corner.BOTTOM_RIGHT -> tvRect.bottom
                }
                canvas.drawCircle(cornerX, cornerY, ledRadius * 1.6f, cornerIndicatorPaint)
            }
        }
    }
}
