package com.wled.tv.model

enum class Corner {
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    TOP_LEFT,
    TOP_RIGHT
}

enum class Direction {
    CLOCKWISE,
    COUNTER_CLOCKWISE
}

enum class AspectRatioPreset(
    val displayName: String,
    val topCrop: Float,
    val bottomCrop: Float,
    val leftCrop: Float,
    val rightCrop: Float
) {
    FULL_16_9("16:9 Fullscreen", 0f, 0f, 0f, 0f),
    CINEMASCOPE_2_39("2.39:1 Cinemascope", 0.130f, 0.130f, 0f, 0f),
    WIDESCREEN_2_35("2.35:1 Widescreen", 0.121f, 0.121f, 0f, 0f),
    UNIVISIUM_2_00("2.00:1 Univisium (Netflix)", 0.055f, 0.055f, 0f, 0f),
    THEATRICAL_1_85("1.85:1 Flat", 0.020f, 0.020f, 0f, 0f),
    PILLARBOX_4_3("4:3 Classic", 0f, 0f, 0.125f, 0.125f),
    CUSTOM("Custom", 0f, 0f, 0f, 0f)
}

data class PerimeterConfig(
    val topLeds: Int = 54,
    val rightLeds: Int = 30,
    val bottomLeds: Int = 54,
    val leftLeds: Int = 30,
    val startCorner: Corner = Corner.BOTTOM_LEFT,
    val direction: Direction = Direction.CLOCKWISE,
    val topCrop: Float = 0.0f,
    val bottomCrop: Float = 0.0f,
    val leftCrop: Float = 0.0f,
    val rightCrop: Float = 0.0f,
    val depth: Float = 0.12f,
    val autoLetterbox: Boolean = true
) {
    val totalLeds: Int
        get() = topLeds + rightLeds + bottomLeds + leftLeds

    fun getActiveAspectRatioPreset(): AspectRatioPreset {
        for (preset in AspectRatioPreset.entries) {
            if (preset == AspectRatioPreset.CUSTOM) continue
            val diffT = Math.abs(topCrop - preset.topCrop)
            val diffB = Math.abs(bottomCrop - preset.bottomCrop)
            val diffL = Math.abs(leftCrop - preset.leftCrop)
            val diffR = Math.abs(rightCrop - preset.rightCrop)
            if (diffT < 0.005f && diffB < 0.005f && diffL < 0.005f && diffR < 0.005f) {
                return preset
            }
        }
        return AspectRatioPreset.CUSTOM
    }

    fun getEffectiveDepth(): Float = depth.coerceIn(0.03f, 0.30f)
}
