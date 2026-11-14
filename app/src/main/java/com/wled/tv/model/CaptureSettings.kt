package com.wled.tv.model

/**
 * Global screen capture resolution and target streaming frame rate settings.
 */
data class CaptureSettings(
    val fps: Int = 60,                   // Target capture frame rate (15, 30, 60)
    val captureWidth: Int = 320,         // Capture buffer width (80, 160, 320, 480)
    val captureHeight: Int = 180         // Capture buffer height (45, 90, 180, 270)
) {
    val resolutionLabel: String
        get() = when (captureWidth) {
            80 -> "80x45 (Ultra)"
            160 -> "160x90 (Eco)"
            480 -> "480x270 (High)"
            else -> "320x180 (Std)"
        }
}
