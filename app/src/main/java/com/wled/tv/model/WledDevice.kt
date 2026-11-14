package com.wled.tv.model

import com.wled.tv.geometry.NormalizedRect
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
    val port: Int = 4048,
    val protocol: StreamingProtocol = StreamingProtocol.DDP,
    val enabled: Boolean = true,
    val type: DeviceType = DeviceType.PERIMETER,
    val ledCount: Int = 1,                              // For spot lights / ambient fixtures
    val perimeter: PerimeterConfig = PerimeterConfig(), // For 4-sided perimeter strips
    val calibration: ColorCalibration = ColorCalibration(), // Per-device optical/color calibration
    val customRect: NormalizedRect = NormalizedRect.FULL, // For custom boxes
    val colorOrder: String = "RGB",                     // Byte layout: RGB, GRB, BRG, BGR, RBG, GBR
    val isRgbw: Boolean = false,                        // True if physical strip is SK6812 RGBW (4 channels)
    val rgbwMode: String = "AUTO_SUBTRACT"              // White gamut mode: AUTO_SUBTRACT, AUTO_ADD, OFF
) {
    val wireBytesPerLed: Int
        get() = if (isRgbw && protocol == StreamingProtocol.DDP) 4 else 3

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
}
