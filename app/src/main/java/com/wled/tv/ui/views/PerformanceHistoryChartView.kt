package com.wled.tv.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.wled.tv.model.ThroughputHistoryPoint
import kotlin.math.max

data class HistorySummaryStats(
    val avgFps: Float,
    val peakFps: Int,
    val avgPps: Float,
    val peakPps: Int,
    val sampleCount: Int
)

class PerformanceHistoryChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var points: List<ThroughputHistoryPoint> = emptyList()
    private var windowSeconds: Int = 300
    private var targetFps: Int = 60
    private var numDevices: Int = 1

    private val bgRect = RectF()
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0A0A0E")
        style = Paint.Style.FILL
    }
    private val bgBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#222230")
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1C1C26")
        style = Paint.Style.STROKE
        strokeWidth = dp(0.75f)
        pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f)
    }

    private val fpsLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        textSize = sp(10f)
        textAlign = Paint.Align.RIGHT
    }

    private val ppsLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F59E0B")
        textSize = sp(10f)
        textAlign = Paint.Align.LEFT
    }

    private val timeLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#64748B")
        textSize = sp(10f)
        textAlign = Paint.Align.CENTER
    }

    private val emptyTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#64748B")
        textSize = sp(12f)
        textAlign = Paint.Align.CENTER
    }

    private val fpsLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fpsFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val ppsLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F59E0B")
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val ppsFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val fpsLinePath = Path()
    private val fpsFillPath = Path()
    private val ppsLinePath = Path()
    private val ppsFillPath = Path()

    fun setData(
        historyPoints: List<ThroughputHistoryPoint>,
        windowSec: Int,
        targetFrameRate: Int,
        deviceCount: Int
    ) {
        points = historyPoints
        windowSeconds = windowSec.coerceAtLeast(30)
        targetFps = targetFrameRate.coerceAtLeast(15)
        numDevices = deviceCount.coerceAtLeast(1)
        invalidate()
    }

    fun getSummaryStats(): HistorySummaryStats {
        if (points.isEmpty()) {
            return HistorySummaryStats(0f, 0, 0f, 0, 0)
        }
        var totalFps = 0L
        var maxFps = 0
        var totalPps = 0L
        var maxPps = 0

        for (p in points) {
            totalFps += p.fps
            if (p.fps > maxFps) maxFps = p.fps
            totalPps += p.pps
            if (p.pps > maxPps) maxPps = p.pps
        }

        val count = points.size
        return HistorySummaryStats(
            avgFps = totalFps.toFloat() / count.toFloat(),
            peakFps = maxFps,
            avgPps = totalPps.toFloat() / count.toFloat(),
            peakPps = maxPps,
            sampleCount = count
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        bgRect.set(dp(2f), dp(2f), w - dp(2f), h - dp(2f))
        canvas.drawRoundRect(bgRect, dp(12f), dp(12f), bgPaint)
        canvas.drawRoundRect(bgRect, dp(12f), dp(12f), bgBorderPaint)

        val padLeft = dp(38f)
        val padRight = dp(42f)
        val padTop = dp(16f)
        val padBottom = dp(22f)

        val chartLeft = padLeft
        val chartRight = w - padRight
        val chartTop = padTop
        val chartBottom = h - padBottom

        val chartWidth = chartRight - chartLeft
        val chartHeight = chartBottom - chartTop
        if (chartWidth <= 0f || chartHeight <= 0f) return

        val maxObservedFps = points.maxOfOrNull { it.fps } ?: 0
        val maxObservedPps = points.maxOfOrNull { it.pps } ?: 0

        val maxFpsScale = max(targetFps, maxObservedFps).coerceAtLeast(30)
        val expectedPps = targetFps * numDevices
        val maxPpsScale = max(expectedPps, maxObservedPps).coerceAtLeast(30)

        // Draw horizontal grid lines (100%, 50%, 0%)
        val steps = 2
        for (i in 0..steps) {
            val y = chartBottom - (chartHeight * (i.toFloat() / steps.toFloat()))
            canvas.drawLine(chartLeft, y, chartRight, y, gridPaint)

            // Left FPS Labels
            val fpsVal = ((maxFpsScale * i) / steps)
            canvas.drawText("$fpsVal", chartLeft - dp(6f), y + dp(3.5f), fpsLabelPaint)

            // Right PPS Labels
            val ppsVal = ((maxPpsScale * i) / steps)
            canvas.drawText("$ppsVal", chartRight + dp(6f), y + dp(3.5f), ppsLabelPaint)
        }

        // Draw time labels at bottom
        val timeNowStr = "Now"
        val timeStartStr = formatTimeOffset(windowSeconds)
        val timeMidStr = formatTimeOffset(windowSeconds / 2)

        val labelY = h - dp(6f)
        timeLabelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(timeStartStr, chartLeft, labelY, timeLabelPaint)

        timeLabelPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(timeMidStr, chartLeft + chartWidth / 2f, labelY, timeLabelPaint)

        timeLabelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(timeNowStr, chartRight, labelY, timeLabelPaint)

        if (points.isEmpty()) {
            val emptyMsg = "Waiting for stream telemetry... Play video to visualize capture rate."
            canvas.drawText(emptyMsg, w / 2f, chartTop + chartHeight / 2f + dp(4f), emptyTextPaint)
            return
        }

        val now = System.currentTimeMillis()
        val startTime = now - (windowSeconds * 1000L)
        val duration = (windowSeconds * 1000L).toFloat()

        fpsLinePath.reset()
        fpsFillPath.reset()
        ppsLinePath.reset()
        ppsFillPath.reset()

        var firstPoint = true
        var firstX = chartLeft
        var lastX = chartLeft

        for (p in points) {
            val t = (p.timestamp - startTime).toFloat() / duration
            if (t < -0.05f) continue // Skip points older than window

            val x = (chartLeft + chartWidth * t).coerceIn(chartLeft, chartRight)
            val yFps = chartTop + chartHeight * (1f - (p.fps.toFloat() / maxFpsScale.toFloat()).coerceIn(0f, 1f))
            val yPps = chartTop + chartHeight * (1f - (p.pps.toFloat() / maxPpsScale.toFloat()).coerceIn(0f, 1f))

            if (firstPoint) {
                firstX = x
                fpsLinePath.moveTo(x, yFps)
                fpsFillPath.moveTo(x, chartBottom)
                fpsFillPath.lineTo(x, yFps)

                ppsLinePath.moveTo(x, yPps)
                ppsFillPath.moveTo(x, chartBottom)
                ppsFillPath.lineTo(x, yPps)
                firstPoint = false
            } else {
                fpsLinePath.lineTo(x, yFps)
                fpsFillPath.lineTo(x, yFps)

                ppsLinePath.lineTo(x, yPps)
                ppsFillPath.lineTo(x, yPps)
            }
            lastX = x
        }

        if (!firstPoint) {
            // Close fill paths down to chartBottom
            fpsFillPath.lineTo(lastX, chartBottom)
            fpsFillPath.lineTo(firstX, chartBottom)
            fpsFillPath.close()

            ppsFillPath.lineTo(lastX, chartBottom)
            ppsFillPath.lineTo(firstX, chartBottom)
            ppsFillPath.close()

            // Setup shaders
            fpsFillPaint.shader = LinearGradient(
                0f, chartTop, 0f, chartBottom,
                Color.parseColor("#1C00E5FF"), Color.parseColor("#0000E5FF"),
                Shader.TileMode.CLAMP
            )
            ppsFillPaint.shader = LinearGradient(
                0f, chartTop, 0f, chartBottom,
                Color.parseColor("#18F59E0B"), Color.parseColor("#00F59E0B"),
                Shader.TileMode.CLAMP
            )

            // Draw fills and lines (PPS underneath, FPS on top)
            canvas.drawPath(ppsFillPath, ppsFillPaint)
            canvas.drawPath(ppsLinePath, ppsLinePaint)

            canvas.drawPath(fpsFillPath, fpsFillPaint)
            canvas.drawPath(fpsLinePath, fpsLinePaint)
        }
    }

    private fun formatTimeOffset(sec: Int): String {
        if (sec <= 0) return "Now"
        return when {
            sec >= 3600 -> {
                val hours = sec / 3600f
                if (sec % 3600 == 0) "-${sec / 3600}h" else "-%.1fh".format(hours)
            }
            sec >= 60 -> {
                val mins = sec / 60f
                if (sec % 60 == 0) "-${sec / 60}m" else "-%.1fm".format(mins)
            }
            else -> "-${sec}s"
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
    private fun sp(value: Float): Float = android.util.TypedValue.applyDimension(
        android.util.TypedValue.COMPLEX_UNIT_SP,
        value,
        resources.displayMetrics
    )
}
