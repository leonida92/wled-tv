package com.wled.tv.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.wled.tv.geometry.ActiveBorder
import com.wled.tv.geometry.FixtureGeometryEngine
import com.wled.tv.geometry.ZoneCanvasLayout
import com.wled.tv.model.PerimeterConfig

class ZoneCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val geometryEngine = FixtureGeometryEngine()
    private var config = PerimeterConfig()
    private var activeBorder: ActiveBorder = ActiveBorder.NONE
    private var layout: ZoneCanvasLayout? = null

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

    private val reusableBadgeRect = RectF()

    fun setConfig(perimeterConfig: PerimeterConfig, border: ActiveBorder) {
        this.config = perimeterConfig
        this.activeBorder = border
        recomputeLayout()
        invalidate()
    }

    fun setActiveBorder(border: ActiveBorder) {
        this.activeBorder = border
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
            layout = geometryEngine.calculateZoneCanvasLayout(w, h, config, activeBorder)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val l = layout ?: return

        // 1. Draw TV Screen base
        canvas.drawRect(l.screenRect.left, l.screenRect.top, l.screenRect.right, l.screenRect.bottom, screenBgPaint)
        canvas.drawRect(l.screenRect.left, l.screenRect.top, l.screenRect.right, l.screenRect.bottom, screenBorderPaint)

        // 2. Draw shaded overlays over cropped letterbox areas
        l.topLetterbox?.let { canvas.drawRect(it.left, it.top, it.right, it.bottom, letterboxShadePaint) }
        l.bottomLetterbox?.let { canvas.drawRect(it.left, it.top, it.right, it.bottom, letterboxShadePaint) }
        l.leftPillarbox?.let { canvas.drawRect(it.left, it.top, it.right, it.bottom, letterboxShadePaint) }
        l.rightPillarbox?.let { canvas.drawRect(it.left, it.top, it.right, it.bottom, letterboxShadePaint) }

        // 3. Draw all LED sampling zones (zero allocation)
        for (i in l.zones.indices) {
            val z = l.zones[i]
            canvas.drawRect(z.left, z.top, z.right, z.bottom, zoneBoxFillPaint)
            canvas.drawRect(z.left, z.top, z.right, z.bottom, zoneBoxStrokePaint)
        }

        // 4. Draw 4 perimeter border lines with active selection highlight
        for (i in l.borderLines.indices) {
            val b = l.borderLines[i]
            val paint = if (b.isActive) activeBorderPaint else inactiveBorderPaint
            canvas.drawLine(b.x1, b.y1, b.x2, b.y2, paint)
        }

        // 5. Draw aspect ratio badge in the center
        reusableBadgeRect.set(l.badgeRect.left, l.badgeRect.top, l.badgeRect.right, l.badgeRect.bottom)
        canvas.drawRoundRect(reusableBadgeRect, 27f, 27f, aspectBadgeBgPaint)
        canvas.drawRoundRect(reusableBadgeRect, 27f, 27f, inactiveBorderPaint)
        canvas.drawText(l.badgeText, l.badgeCx, l.badgeCy + 10f, aspectBadgeTextPaint)
    }
}
