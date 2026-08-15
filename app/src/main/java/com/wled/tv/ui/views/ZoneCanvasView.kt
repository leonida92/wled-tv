package com.wled.tv.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.wled.tv.model.AspectRatioPreset
import com.wled.tv.model.PerimeterConfig

enum class ActiveBorder {
    TOP,
    BOTTOM,
    LEFT,
    RIGHT,
    NONE
}

class ZoneCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var config = PerimeterConfig()
    private var activeBorder: ActiveBorder = ActiveBorder.NONE

    private val screenBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#334155")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val screenBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0B1329")
        style = Paint.Style.FILL
    }

    private val activeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val inactiveBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3B82F6")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }

    private val zoneBoxFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2600E5FF") // 15% Cyan
        style = Paint.Style.FILL
    }

    private val zoneBoxStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6600E5FF") // 40% Cyan
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    private val letterboxShadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B3000000") // 70% Dark Shadow overlay
        style = Paint.Style.FILL
    }

    private val aspectBadgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC1E293B")
        style = Paint.Style.FILL
    }

    private val aspectBadgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        textSize = 28f
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    fun setConfig(perimeterConfig: PerimeterConfig, border: ActiveBorder) {
        this.config = perimeterConfig
        this.activeBorder = border
        invalidate()
    }

    fun setActiveBorder(border: ActiveBorder) {
        this.activeBorder = border
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // 1. Draw TV Screen base
        canvas.drawRect(0f, 0f, w, h, screenBgPaint)
        canvas.drawRect(0f, 0f, w, h, screenBorderPaint)

        // 2. Compute border crop lines
        val yTop = config.topCrop * h
        val yBottom = (1.0f - config.bottomCrop) * h
        val xLeft = config.leftCrop * w
        val xRight = (1.0f - config.rightCrop) * w

        // 3. Draw shaded overlays over cropped letterbox areas
        if (config.topCrop > 0.001f) {
            canvas.drawRect(0f, 0f, w, yTop, letterboxShadePaint)
        }
        if (config.bottomCrop > 0.001f) {
            canvas.drawRect(0f, yBottom, w, h, letterboxShadePaint)
        }
        if (config.leftCrop > 0.001f) {
            canvas.drawRect(0f, yTop, xLeft, yBottom, letterboxShadePaint)
        }
        if (config.rightCrop > 0.001f) {
            canvas.drawRect(xRight, yTop, w, yBottom, letterboxShadePaint)
        }

        // 4. Draw all LED sampling zones
        val zones = config.computeLedZones()
        for (z in zones) {
            val zRect = RectF(
                z.left * w,
                z.top * h,
                z.right * w,
                z.bottom * h
            )
            canvas.drawRect(zRect, zoneBoxFillPaint)
            canvas.drawRect(zRect, zoneBoxStrokePaint)
        }

        // 5. Draw 4 perimeter border lines with active selection highlight
        // Top
        val pTop = if (activeBorder == ActiveBorder.TOP) activeBorderPaint else inactiveBorderPaint
        canvas.drawLine(xLeft, yTop, xRight, yTop, pTop)

        // Bottom
        val pBottom = if (activeBorder == ActiveBorder.BOTTOM) activeBorderPaint else inactiveBorderPaint
        canvas.drawLine(xLeft, yBottom, xRight, yBottom, pBottom)

        // Left
        val pLeft = if (activeBorder == ActiveBorder.LEFT) activeBorderPaint else inactiveBorderPaint
        canvas.drawLine(xLeft, yTop, xLeft, yBottom, pLeft)

        // Right
        val pRight = if (activeBorder == ActiveBorder.RIGHT) activeBorderPaint else inactiveBorderPaint
        canvas.drawLine(xRight, yTop, xRight, yBottom, pRight)

        // 6. Draw aspect ratio badge in the center
        val activePreset = config.getActiveAspectRatioPreset()
        val badgeText = activePreset.displayName
        val badgeW = 340f
        val badgeH = 54f
        val badgeCx = w / 2f
        val badgeCy = (yTop + yBottom) / 2f
        val badgeRect = RectF(badgeCx - badgeW / 2f, badgeCy - badgeH / 2f, badgeCx + badgeW / 2f, badgeCy + badgeH / 2f)

        canvas.drawRoundRect(badgeRect, 27f, 27f, aspectBadgeBgPaint)
        canvas.drawRoundRect(badgeRect, 27f, 27f, inactiveBorderPaint)
        canvas.drawText(badgeText, badgeCx, badgeCy + 10f, aspectBadgeTextPaint)
    }
}
