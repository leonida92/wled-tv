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
import kotlin.math.min

class TvPerimeterPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var perimeterConfig = PerimeterConfig()
    private var liveLedColors: ByteArray? = null
    private var liveLedCount: Int = 0

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

    fun updateConfig(config: PerimeterConfig) {
        this.perimeterConfig = config
        invalidate()
    }

    fun updateLiveColors(rgb: ByteArray, count: Int) {
        if (this.liveLedColors == null || this.liveLedColors!!.size != rgb.size) {
            this.liveLedColors = ByteArray(rgb.size)
        }
        System.arraycopy(rgb, 0, this.liveLedColors!!, 0, min(rgb.size, count * 3))
        this.liveLedCount = count
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val padX = w * 0.08f
        val padY = h * 0.08f

        val tvRect = RectF(padX, padY, w - padX, h - padY)
        val tvWidth = tvRect.width()
        val tvHeight = tvRect.height()

        // Draw TV chassis / screen
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

        // Draw LED perimeter dots around the outer border
        val topLeds = perimeterConfig.topLeds
        val rightLeds = perimeterConfig.rightLeds
        val bottomLeds = perimeterConfig.bottomLeds
        val leftLeds = perimeterConfig.leftLeds
        val totalLeds = perimeterConfig.totalLeds

        if (totalLeds <= 0) return

        val ledRadius = min(tvWidth, tvHeight) * 0.016f

        fun getLedColor(index: Int): Int {
            val colors = liveLedColors
            if (colors != null && index < liveLedCount && (index * 3 + 2) < colors.size) {
                val r = colors[index * 3].toInt() and 0xFF
                val g = colors[index * 3 + 1].toInt() and 0xFF
                val b = colors[index * 3 + 2].toInt() and 0xFF
                return Color.rgb(r, g, b)
            }
            return Color.parseColor("#3B82F6")
        }

        // 1. Calculate physical dot coordinates for each edge (ordered clockwise)
        val topPoints = ArrayList<PointF>(topLeds)
        if (topLeds > 0) {
            val step = (tvRect.width() - (ledRadius * 4)) / topLeds
            for (i in 0 until topLeds) {
                val cx = tvRect.left + (ledRadius * 2) + (i * step) + (step / 2)
                val cy = tvRect.top - (ledRadius * 1.5f)
                topPoints.add(PointF(cx, cy))
            }
        }

        val rightPoints = ArrayList<PointF>(rightLeds)
        if (rightLeds > 0) {
            val step = (tvRect.height() - (ledRadius * 4)) / rightLeds
            for (i in 0 until rightLeds) {
                val cx = tvRect.right + (ledRadius * 1.5f)
                val cy = tvRect.top + (ledRadius * 2) + (i * step) + (step / 2)
                rightPoints.add(PointF(cx, cy))
            }
        }

        val bottomPoints = ArrayList<PointF>(bottomLeds)
        if (bottomLeds > 0) {
            val step = (tvRect.width() - (ledRadius * 4)) / bottomLeds
            for (i in 0 until bottomLeds) {
                val cx = tvRect.right - (ledRadius * 2) - (i * step) - (step / 2)
                val cy = tvRect.bottom + (ledRadius * 1.5f)
                bottomPoints.add(PointF(cx, cy))
            }
        }

        val leftPoints = ArrayList<PointF>(leftLeds)
        if (leftLeds > 0) {
            val step = (tvRect.height() - (ledRadius * 4)) / leftLeds
            for (i in 0 until leftLeds) {
                val cx = tvRect.left - (ledRadius * 1.5f)
                val cy = tvRect.bottom - (ledRadius * 2) - (i * step) - (step / 2)
                leftPoints.add(PointF(cx, cy))
            }
        }

        // 2. Assemble the ordered LED points matching the user's startCorner
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

        // 3. Reverse if counter-clockwise to match physical wiring
        val finalPoints = if (perimeterConfig.direction == Direction.CLOCKWISE) {
            clockwisePoints
        } else {
            clockwisePoints.reversed()
        }

        // 4. Draw each preview dot at its true mapped index
        for (i in 0 until finalPoints.size) {
            val pt = finalPoints[i]
            ledPaint.color = getLedColor(i)
            canvas.drawCircle(pt.x, pt.y, ledRadius, ledPaint)
            canvas.drawCircle(pt.x, pt.y, ledRadius, ledBorderPaint)
        }

        // Start corner marker
        val cornerX = when (perimeterConfig.startCorner) {
            Corner.TOP_LEFT, Corner.BOTTOM_LEFT -> tvRect.left
            Corner.TOP_RIGHT, Corner.BOTTOM_RIGHT -> tvRect.right
        }
        val cornerY = when (perimeterConfig.startCorner) {
            Corner.TOP_LEFT, Corner.TOP_RIGHT -> tvRect.top
            Corner.BOTTOM_LEFT, Corner.BOTTOM_RIGHT -> tvRect.bottom
        }
        canvas.drawCircle(cornerX, cornerY, ledRadius * 1.8f, cornerIndicatorPaint)
    }
}
