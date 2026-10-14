package com.wled.tv.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.roundToInt

data class DeviceOverlayData(
    val deviceId: String,
    val deviceName: String,
    val zones: List<RectF>,
    val rgb: ByteArray,
    val ledCount: Int
)

class SamplingOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val dotRadius = 4f * density
    private val strokeWidth = 1f * density

    // Subtle, elegant wireframe paints
    private val wireframePerimeterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4000E5FF") // 25% Subtle Cyan
        style = Paint.Style.STROKE
        strokeWidth = this@SamplingOverlayView.strokeWidth
    }

    private val wireframeAmbientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#40F59E0B") // 25% Subtle Amber
        style = Paint.Style.STROKE
        strokeWidth = this@SamplingOverlayView.strokeWidth
    }

    private val dotBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#99000000") // Soft shadow ring for contrast
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }

    private val dotFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // Telemetry HUD Pill (clean, compact glass style)
    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC0B1329") // 80% Dark slate glass
        style = Paint.Style.FILL
    }

    private val badgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3300E5FF") // Subtle 20% cyan accent border
        style = Paint.Style.STROKE
        strokeWidth = 0.8f * density
    }

    private val badgeDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.FILL
    }

    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E2E8F0")
        textSize = 11.5f * density
        isFakeBoldText = true
    }

    // Reusable structures for zero-allocation drawing
    private val tempRect = RectF()
    private val badgeRect = RectF()

    // Live state
    @Volatile
    private var devicesData: List<DeviceOverlayData> = emptyList()

    @Volatile
    private var topCrop: Float = 0.0f

    @Volatile
    private var bottomCrop: Float = 0.0f

    @Volatile
    private var leftCrop: Float = 0.0f

    @Volatile
    private var rightCrop: Float = 0.0f

    @Volatile
    private var isDrmActive: Boolean = false

    @Volatile
    private var liveFps: Float = 0.0f

    @Volatile
    private var livePps: Int = 0

    @Volatile
    private var cachedHudContent: String = ""

    @Volatile
    private var cachedTextWidth: Float = 0f

    init {
        setWillNotDraw(false)
    }

    fun updateTelemetry(
        devices: List<DeviceOverlayData>,
        currentTopCrop: Float,
        currentBottomCrop: Float,
        currentLeftCrop: Float = 0.0f,
        currentRightCrop: Float = 0.0f,
        fps: Float,
        pps: Int,
        isDrmActive: Boolean = false
    ) {
        this.devicesData = devices
        this.topCrop = currentTopCrop
        this.bottomCrop = currentBottomCrop
        this.leftCrop = currentLeftCrop
        this.rightCrop = currentRightCrop
        this.liveFps = fps
        this.livePps = pps
        this.isDrmActive = isDrmActive

        val totalLeds = devices.sumOf { it.ledCount }
        val fpsStr = "${fps.roundToInt()} FPS"
        val cropPct = (currentTopCrop * 100).roundToInt()
        val pillarPct = (currentLeftCrop * 100).roundToInt()
        val cropInfo = when {
            cropPct > 2 && pillarPct > 2 -> " • V:$cropPct% H:$pillarPct%"
            cropPct > 2 -> " • Crop $cropPct%"
            pillarPct > 2 -> " • 4:3 Pillar $pillarPct%"
            else -> ""
        }
        val devInfo = if (devices.size > 1) " • ${devices.size} Devs" else ""
        val drmInfo = if (isDrmActive) " • ⚠ Widevine DRM" else ""
        val content = "$fpsStr$cropInfo$drmInfo$devInfo • $totalLeds LEDs"
        this.cachedHudContent = content
        this.cachedTextWidth = badgeTextPaint.measureText(content)

        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val currentDevices = devicesData
        val currentTop = topCrop

        var totalLeds = 0
        var maxTopBoxBottom = currentTop * h

        // 1. Draw sampling wireframe boxes and glowing inner color dots
        for (d in currentDevices.indices) {
            val dev = currentDevices[d]
            val zones = dev.zones
            val rgb = dev.rgb
            val ledCount = dev.ledCount
            totalLeds += ledCount

            val isPerimeter = dev.deviceName.contains("Perimeter", ignoreCase = true) ||
                    dev.deviceName.contains("Backlight", ignoreCase = true) ||
                    zones.size > 8

            val wireframePaint = if (isPerimeter) wireframePerimeterPaint else wireframeAmbientPaint

            val count = minOf(zones.size, ledCount)
            for (i in 0 until count) {
                val z = zones[i]
                val l = z.left * w
                val t = z.top * h
                val r = z.right * w
                val b = z.bottom * h

                if (t <= (currentTop + 0.05f) * h && b > maxTopBoxBottom) {
                    maxTopBoxBottom = b
                }

                // Draw thin subtle wireframe box
                tempRect.set(l, t, r, b)
                canvas.drawRect(tempRect, wireframePaint)

                // Draw inner color dot
                val offset = i * 3
                if (rgb.size >= offset + 3) {
                    val cr = rgb[offset].toInt() and 0xFF
                    val cg = rgb[offset + 1].toInt() and 0xFF
                    val cb = rgb[offset + 2].toInt() and 0xFF

                    val cx = (l + r) / 2f
                    val cy = (t + b) / 2f
                    val dotX = cx
                    val dotY = cy

                    dotFillPaint.color = Color.rgb(cr, cg, cb)

                    // Draw subtle border ring then filled color dot
                    canvas.drawCircle(dotX, dotY, dotRadius + (1.2f * density), dotBorderPaint)
                    canvas.drawCircle(dotX, dotY, dotRadius, dotFillPaint)
                }
            }
        }

        // 2. Draw Sleek Telemetry Pill positioned safely below the top boxes (never intersects dots)
        val hudContent = cachedHudContent
        if (hudContent.isNotEmpty()) {
            val textWidth = cachedTextWidth
            val pillHeight = 26f * density
            val pillWidth = textWidth + (36f * density)

            // Center horizontally; place comfortably below top boxes and dots
            val pillX = (w - pillWidth) / 2f
            val pillY = maxOf(28f * density, maxTopBoxBottom + (24f * density))

            badgeRect.set(pillX, pillY, pillX + pillWidth, pillY + pillHeight)

            // Rounded glass pill
            canvas.drawRoundRect(badgeRect, pillHeight / 2f, pillHeight / 2f, badgeBgPaint)
            canvas.drawRoundRect(badgeRect, pillHeight / 2f, pillHeight / 2f, badgeBorderPaint)

            // Status indicator dot (amber for DRM warning, cyan for normal)
            badgeDotPaint.color = if (isDrmActive) Color.parseColor("#F59E0B") else Color.parseColor("#00E5FF")
            val dotCx = pillX + (12f * density)
            val dotCy = pillY + (pillHeight / 2f)
            canvas.drawCircle(dotCx, dotCy, 3f * density, badgeDotPaint)

            // Text
            val textY = pillY + (pillHeight / 2f) + (4f * density)
            canvas.drawText(hudContent, dotCx + (8f * density), textY, badgeTextPaint)
        }
    }
}
