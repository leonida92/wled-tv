package com.wled.tv.ui.views

import android.graphics.RectF
import com.wled.tv.geometry.NormalizedRect

/**
 * Android UI View boundary adapters for [NormalizedRect].
 */
fun NormalizedRect.toRectF(): RectF = RectF(left, top, right, bottom)

fun RectF.toNormalizedRect(): NormalizedRect = NormalizedRect(left, top, right, bottom)
