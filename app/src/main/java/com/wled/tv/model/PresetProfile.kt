package com.wled.tv.model

/**
 * A cohesive, named optical configuration bundling calibration curves,
 * temporal smoothing speeds, and luminance thresholds for specific viewing moods.
 */
data class PresetProfile(
    val id: String,
    val name: String,
    val description: String,
    val calibration: ColorCalibration
) {
    companion object {
        val DEFAULT = PresetProfile(
            id = "default",
            name = "Standard",
            description = "Balanced natural reproduction for daily viewing",
            calibration = ColorCalibration()
        )

        val CINEMA = PresetProfile(
            id = "cinema",
            name = "Cinema",
            description = "Warm contrast and gentle smoothing for movies",
            calibration = ColorCalibration(
                saturation = 1.4f,
                contrast = 1.05f,
                smoothingFactor = 0.35f,
                blackThreshold = 12
            )
        )

        val GAMING = PresetProfile(
            id = "gaming",
            name = "Gaming",
            description = "Instant response and punchy saturation for gaming",
            calibration = ColorCalibration(
                saturation = 1.8f,
                contrast = 1.0f,
                smoothingFactor = 0.75f,
                blackThreshold = 6
            )
        )

        val VIVID = PresetProfile(
            id = "vivid",
            name = "Vivid",
            description = "High saturation and contrast for bright ambient rooms",
            calibration = ColorCalibration(
                saturation = 2.2f,
                contrast = 1.15f,
                smoothingFactor = 0.45f,
                blackThreshold = 4
            )
        )

        val EYE_CARE = PresetProfile(
            id = "eye_care",
            name = "Eye Care",
            description = "Reduced blue spectrum and lower peak brightness for night viewing",
            calibration = ColorCalibration(
                saturation = 1.2f,
                contrast = 0.9f,
                maxBrightness = 180,
                smoothingFactor = 0.25f,
                gainB = 0.85f
            )
        )

        val CUSTOM = PresetProfile(
            id = "custom",
            name = "Custom",
            description = "User-defined custom color calibration",
            calibration = ColorCalibration()
        )

        val BUILT_INS = listOf(DEFAULT, CINEMA, GAMING, VIVID, EYE_CARE, CUSTOM)

        fun fromId(id: String): PresetProfile =
            BUILT_INS.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: DEFAULT
    }
}
