package com.wled.tv.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.wled.tv.model.HomeAssistantZoneType

class HaZonePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var zoneType: HomeAssistantZoneType = HomeAssistantZoneType.FULL_SCREEN_AVERAGE
    private var customRect: RectF = RectF(0f, 0f, 1f, 1f)

    private val tvBezelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#334155")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val tvBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#080C14")
        style = Paint.Style.FILL
    }

    private val gridLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E293B")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
    }

    private val zoneFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3300E5FF") // 20% Cyan
        style = Paint.Style.FILL
    }

    private val zoneStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E60F172A") // 90% slate-900
        style = Paint.Style.FILL
    }

    private val badgeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38BDF8")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    private val zoneTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        textSize = 24f
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    fun setZone(zone: HomeAssistantZoneType, custom: RectF = RectF(0f, 0f, 1f, 1f)) {
        this.zoneType = zone
        this.customRect = custom
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val height = if (heightMode == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(heightMeasureSpec)
        } else {
            (width * 9 / 16).coerceAtMost(320)
        }
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val padding = 6f
        val screenRect = RectF(padding, padding, w - padding, h - padding)

        // 1. Draw TV Screen Base
        canvas.drawRoundRect(screenRect, 8f, 8f, tvBgPaint)
        canvas.drawRoundRect(screenRect, 8f, 8f, tvBezelPaint)

        val sw = screenRect.width()
        val sh = screenRect.height()

        // 2. Draw subtle 25% guideline grid
        canvas.drawLine(screenRect.left + sw * 0.25f, screenRect.top, screenRect.left + sw * 0.25f, screenRect.bottom, gridLinePaint)
        canvas.drawLine(screenRect.left + sw * 0.50f, screenRect.top, screenRect.left + sw * 0.50f, screenRect.bottom, gridLinePaint)
        canvas.drawLine(screenRect.left + sw * 0.75f, screenRect.top, screenRect.left + sw * 0.75f, screenRect.bottom, gridLinePaint)

        canvas.drawLine(screenRect.left, screenRect.top + sh * 0.25f, screenRect.right, screenRect.top + sh * 0.25f, gridLinePaint)
        canvas.drawLine(screenRect.left, screenRect.top + sh * 0.50f, screenRect.right, screenRect.top + sh * 0.50f, gridLinePaint)
        canvas.drawLine(screenRect.left, screenRect.top + sh * 0.75f, screenRect.right, screenRect.top + sh * 0.75f, gridLinePaint)

        // 3. Compute active sampling box coordinates
        val (nLeft, nTop, nRight, nBottom) = when (zoneType) {
            HomeAssistantZoneType.FULL_SCREEN_AVERAGE -> listOf(0f, 0f, 1f, 1f)
            HomeAssistantZoneType.LEFT_AMBIENT -> listOf(0f, 0f, 0.25f, 1f)
            HomeAssistantZoneType.RIGHT_AMBIENT -> listOf(0.75f, 0f, 1f, 1f)
            HomeAssistantZoneType.TOP_AMBIENT -> listOf(0f, 0f, 1f, 0.25f)
            HomeAssistantZoneType.BOTTOM_AMBIENT -> listOf(0f, 0.75f, 1f, 1f)
            HomeAssistantZoneType.CUSTOM_RECT -> listOf(
                customRect.left.coerceIn(0f, 1f),
                customRect.top.coerceIn(0f, 1f),
                customRect.right.coerceIn(0f, 1f),
                customRect.bottom.coerceIn(0f, 1f)
            )
        }

        val zRect = RectF(
            screenRect.left + nLeft * sw,
            screenRect.top + nTop * sh,
            screenRect.left + nRight * sw,
            screenRect.top + nBottom * sh
        )

        // 4. Fill and stroke active sampling zone
        canvas.drawRoundRect(zRect, 4f, 4f, zoneFillPaint)
        canvas.drawRoundRect(zRect, 4f, 4f, zoneStrokePaint)

        // 5. Draw label centered on the TV screen inside a stylish badge (never clips at edges)
        val label = when (zoneType) {
            HomeAssistantZoneType.FULL_SCREEN_AVERAGE -> "Full Screen (100%)"
            HomeAssistantZoneType.LEFT_AMBIENT -> "Left Side (25%)"
            HomeAssistantZoneType.RIGHT_AMBIENT -> "Right Side (25%)"
            HomeAssistantZoneType.TOP_AMBIENT -> "Top Side (25%)"
            HomeAssistantZoneType.BOTTOM_AMBIENT -> "Bottom Side (25%)"
            HomeAssistantZoneType.CUSTOM_RECT -> {
                val pw = ((nRight - nLeft) * 100).toInt()
                val ph = ((nBottom - nTop) * 100).toInt()
                "Custom ($pw% x $ph%)"
            }
        }

        val textWidth = zoneTextPaint.measureText(label)
        val textHeight = zoneTextPaint.textSize
        val badgePaddingX = 18f
        val badgePaddingY = 8f

        val centerX = screenRect.centerX()
        val centerY = screenRect.centerY()

        val badgeRect = RectF(
            centerX - textWidth / 2f - badgePaddingX,
            centerY - textHeight / 2f - badgePaddingY,
            centerX + textWidth / 2f + badgePaddingX,
            centerY + textHeight / 2f + badgePaddingY
        )

        canvas.drawRoundRect(badgeRect, 12f, 12f, badgeBgPaint)
        canvas.drawRoundRect(badgeRect, 12f, 12f, badgeStrokePaint)

        val textY = centerY - (zoneTextPaint.descent() + zoneTextPaint.ascent()) / 2f
        canvas.drawText(label, centerX, textY, zoneTextPaint)
    }
}
