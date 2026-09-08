package com.wled.tv.model

import android.graphics.RectF
import java.util.UUID

enum class DeviceType(val displayName: String) {
    PERIMETER("TV Backlight (4-Sided Perimeter)"),
    LEFT_AMBIENT("Left Side Lamp / Lightbar"),
    RIGHT_AMBIENT("Right Side Lamp / Lightbar"),
    TOP_AMBIENT("Top / Ceiling Uplight"),
    BOTTOM_AMBIENT("Bottom / Floor Light"),
    FULL_SCREEN("Full Screen Ambient (Flood)"),
    CUSTOM_BOX("Custom Screen Region")
}

data class WledDevice(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "TV Backlight",
    val ip: String = "192.168.1.66",
    val port: Int = 21324,
    val enabled: Boolean = true,
    val type: DeviceType = DeviceType.PERIMETER,
    val ledCount: Int = 1,                              // For spot lights / ambient fixtures
    val perimeter: PerimeterConfig = PerimeterConfig(), // For 4-sided perimeter strips
    val calibration: ColorCalibration = ColorCalibration(), // Per-device optical/color calibration
    val customRect: RectF = RectF(0f, 0f, 1f, 1f)       // For custom boxes
) {
    val colorOrder: String
        get() = calibration.colorOrder

    val totalLeds: Int
        get() = when (type) {
            DeviceType.PERIMETER -> perimeter.totalLeds
            else -> ledCount.coerceAtLeast(1)
        }

    fun getEffectivePerimeter(): PerimeterConfig {
        return when (type) {
            DeviceType.PERIMETER -> perimeter
            DeviceType.BOTTOM_AMBIENT -> PerimeterConfig(topLeds = 0, rightLeds = 0, bottomLeds = ledCount.coerceAtLeast(1), leftLeds = 0)
            DeviceType.TOP_AMBIENT -> PerimeterConfig(topLeds = ledCount.coerceAtLeast(1), rightLeds = 0, bottomLeds = 0, leftLeds = 0)
            DeviceType.LEFT_AMBIENT -> PerimeterConfig(topLeds = 0, rightLeds = 0, bottomLeds = 0, leftLeds = ledCount.coerceAtLeast(1))
            DeviceType.RIGHT_AMBIENT -> PerimeterConfig(topLeds = 0, rightLeds = ledCount.coerceAtLeast(1), bottomLeds = 0, leftLeds = 0)
            DeviceType.FULL_SCREEN -> PerimeterConfig(topLeds = 0, rightLeds = 0, bottomLeds = ledCount.coerceAtLeast(1), leftLeds = 0)
            DeviceType.CUSTOM_BOX -> PerimeterConfig(topLeds = 0, rightLeds = 0, bottomLeds = ledCount.coerceAtLeast(1), leftLeds = 0)
        }
    }

    fun getSampleRegion(): RectF {
        return when (type) {
            DeviceType.PERIMETER -> RectF(0f, 0f, 1f, 1f)
            DeviceType.LEFT_AMBIENT -> RectF(0f, 0f, 0.25f, 1f)
            DeviceType.RIGHT_AMBIENT -> RectF(0.75f, 0f, 1f, 1f)
            DeviceType.TOP_AMBIENT -> RectF(0f, 0f, 1f, 0.25f)
            DeviceType.BOTTOM_AMBIENT -> RectF(0f, 0.75f, 1f, 1f)
            DeviceType.FULL_SCREEN -> RectF(0f, 0f, 1f, 1f)
            DeviceType.CUSTOM_BOX -> customRect
        }
    }
}
