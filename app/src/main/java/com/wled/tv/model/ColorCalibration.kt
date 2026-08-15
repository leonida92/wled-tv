package com.wled.tv.model

data class ColorCalibration(
    val saturation: Float = 1.6f,        // Saturation boost multiplier (0.5x to 3.0x)
    val contrast: Float = 1.0f,          // Contrast multiplier (0.5x to 1.5x)
    val maxBrightness: Int = 255,        // Max brightness scale (0 to 255)
    val blackThreshold: Int = 8,         // Black level cutoff intensity (0 to 50): values <= this turn completely OFF
    val gainR: Float = 1.0f,             // Red gain multiplier (0.2 to 2.5)
    val gainG: Float = 1.0f,             // Green gain multiplier (0.2 to 2.5)
    val gainB: Float = 1.0f,             // Blue gain multiplier (0.2 to 2.5)
    val gammaR: Float = 1.0f,            // Red gamma curve (0.5 to 2.5)
    val gammaG: Float = 1.0f,            // Green gamma curve (0.5 to 2.5)
    val gammaB: Float = 1.0f,            // Blue gamma curve (0.5 to 2.5)
    val colorOrder: String = "RGB",      // Byte layout: RGB, GRB, BRG, BGR, RBG, GBR
    val smoothingFactor: Float = 0.40f,  // EMA smoothing factor (0.1 = heavy smooth, 1.0 = instant)
    val fps: Int = 60                    // Target capture frame rate (15, 30, 60)
)
