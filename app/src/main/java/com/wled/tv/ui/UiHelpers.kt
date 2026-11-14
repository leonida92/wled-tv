package com.wled.tv.ui

import android.content.res.ColorStateList
import android.graphics.Color

internal const val EXTRA_DEVICE_DRAFT_JSON = "extra_device_draft_json"

internal fun reachabilityDotTint(enabled: Boolean, reachable: Boolean?): ColorStateList {
    val hex = when {
        !enabled -> "#475569"
        reachable == true -> "#00E676"
        reachable == false -> "#EF4444"
        else -> "#64748B"
    }
    return ColorStateList.valueOf(Color.parseColor(hex))
}
