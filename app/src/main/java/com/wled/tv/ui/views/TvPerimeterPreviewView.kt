package com.wled.tv.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.wled.tv.geometry.FixtureGeometryEngine
import com.wled.tv.geometry.TvPreviewLayout
import com.wled.tv.model.PerimeterConfig
import com.wled.tv.model.WledDevice
import kotlin.math.min

class TvPerimeterPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val geometryEngine = FixtureGeometryEngine()
    private var devices: List<WledDevice> = emptyList()
    private val defaultDeviceList = listOf(WledDevice())
    private var layout: TvPreviewLayout? = null

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

    private val reusableHsv = FloatArray(3).apply {
        this[1] = 0.85f
        this[2] = 0.95f
    }
    private val reusableCustomBoxRect = RectF()

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

    fun updateDevices(devices: List<WledDevice>) {
        this.devices = devices
        recomputeLayout()
        invalidate()
    }

    fun updateConfig(config: PerimeterConfig) {
        if (devices.isEmpty()) {
            this.devices = listOf(WledDevice(perimeter = config))
        } else {
            this.devices = listOf(devices[0].copy(perimeter = config)) + devices.drop(1)
        }
        recomputeLayout()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeLayout()
    }

    private fun recomputeLayout() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w > 0f && h > 0f) {
            val displayDevices = if (devices.isEmpty()) defaultDeviceList else devices
            layout = geometryEngine.calculatePreviewLayout(w, h, displayDevices)
        }
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

        val l = layout ?: return
        val displayDevices = if (devices.isEmpty()) defaultDeviceList else devices

        // 1. Draw TV chassis & screen
        reusableTvRect.set(l.tvRect.left, l.tvRect.top, l.tvRect.right, l.tvRect.bottom)
        canvas.drawRoundRect(reusableTvRect, 14f, 14f, tvBodyPaint)
        canvas.drawRoundRect(reusableTvRect, 14f, 14f, tvBezelPaint)

        reusableScreenRect.set(l.screenRect.left, l.screenRect.top, l.screenRect.right, l.screenRect.bottom)
        canvas.drawRoundRect(reusableScreenRect, 10f, 10f, tvScreenPaint)
        canvas.drawRoundRect(reusableScreenRect, 10f, 10f, tvScreenInnerPaint)

        // TV Brand Text
        tvLogoTextPaint.textSize = (l.tvRect.height * 0.08f).coerceIn(11f, 18f)
        canvas.drawText("WLED AMBIENT SYNC", l.tvRect.centerX, l.tvRect.centerY + tvLogoTextPaint.textSize * 0.35f, tvLogoTextPaint)

        // 2. Full-Screen ambient devices
        for (devIdx in l.fullScreenDeviceIndices) {
            val dev = displayDevices.getOrNull(devIdx) ?: continue
            if (dev.enabled) {
                val color = getDeviceLedColor(dev, 0, 1, devIdx)
                zoneFillPaint.color = color
                zoneFillPaint.alpha = 40
                canvas.drawRoundRect(reusableScreenRect, 10f, 10f, zoneFillPaint)
            }
        }

        // 3. Custom Box screen regions
        for (customBox in l.customBoxes) {
            val dev = displayDevices.getOrNull(customBox.deviceIndex) ?: continue
            val r = customBox.rect
            reusableCustomBoxRect.set(r.left, r.top, r.right, r.bottom)
            val color = getDeviceLedColor(dev, 0, 1, customBox.deviceIndex)
            zoneBoxPaint.color = color
            zoneFillPaint.color = color
            zoneFillPaint.alpha = 50
            canvas.drawRoundRect(reusableCustomBoxRect, 6f, 6f, zoneFillPaint)
            canvas.drawRoundRect(reusableCustomBoxRect, 6f, 6f, zoneBoxPaint)
        }

        // 4. Pre-computed diode vectors
        for (i in l.diodes.indices) {
            val d = l.diodes[i]
            val dev = displayDevices.getOrNull(d.deviceIndex) ?: continue
            drawDiode(canvas, dev, d.x, d.y, d.ledIndex, d.totalLeds, d.radius, d.deviceIndex)
        }

        // 5. Subtle start corner ring
        l.startCornerRing?.let { ring ->
            canvas.drawCircle(ring.cx, ring.cy, ring.radius, cornerIndicatorPaint)
        }
    }

    private fun getDeviceLedColor(device: WledDevice, index: Int, total: Int, deviceIndex: Int): Int {
        if (!device.enabled) {
            return Color.parseColor("#272733")
        }
        val colors = deviceColors[device.id]
        val liveCount = deviceCounts[device.id] ?: 0
        val hasLiveColors = isLiveActive && colors != null && liveCount > 0
        if (hasLiveColors && index < liveCount && (index * 3 + 2) < colors!!.size) {
            val r = colors[index * 3].toInt() and 0xFF
            val g = colors[index * 3 + 1].toInt() and 0xFF
            val b = colors[index * 3 + 2].toInt() and 0xFF
            return Color.rgb(r, g, b)
        }
        val hue = if (total > 1) (index.toFloat() / total) * 360f else (deviceIndex * 70f) % 360f
        reusableHsv[0] = hue
        return Color.HSVToColor(reusableHsv)
    }

    private fun drawDiode(
        canvas: Canvas,
        device: WledDevice,
        x: Float,
        y: Float,
        index: Int,
        total: Int,
        radius: Float,
        deviceIndex: Int
    ) {
        val diodeColor = getDeviceLedColor(device, index, total, deviceIndex)

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
}
