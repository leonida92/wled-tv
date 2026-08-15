package com.wled.tv.data

import android.content.Context
import android.content.SharedPreferences
import com.wled.tv.model.ColorCalibration
import com.wled.tv.model.Corner
import com.wled.tv.model.Direction
import com.wled.tv.model.PerimeterConfig
import com.wled.tv.model.WledConfig

class PreferencesRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("wled_tv_prefs", Context.MODE_PRIVATE)

    fun loadConfig(): WledConfig {
        val ip = prefs.getString(KEY_IP, "192.168.1.66") ?: "192.168.1.66"
        val port = prefs.getInt(KEY_PORT, 21324)
        val autoStart = prefs.getBoolean(KEY_AUTO_START, false)

        val perimeter = PerimeterConfig(
            topLeds = prefs.getInt(KEY_TOP_LEDS, 54),
            rightLeds = prefs.getInt(KEY_RIGHT_LEDS, 30),
            bottomLeds = prefs.getInt(KEY_BOTTOM_LEDS, 54),
            leftLeds = prefs.getInt(KEY_LEFT_LEDS, 30),
            startCorner = Corner.valueOf(
                prefs.getString(KEY_START_CORNER, Corner.BOTTOM_LEFT.name) ?: Corner.BOTTOM_LEFT.name
            ),
            direction = Direction.valueOf(
                prefs.getString(KEY_DIRECTION, Direction.CLOCKWISE.name) ?: Direction.CLOCKWISE.name
            ),
            topCrop = prefs.getFloat(KEY_TOP_CROP, 0.0f),
            bottomCrop = prefs.getFloat(KEY_BOTTOM_CROP, 0.0f),
            leftCrop = prefs.getFloat(KEY_LEFT_CROP, 0.0f),
            rightCrop = prefs.getFloat(KEY_RIGHT_CROP, 0.0f),
            autoLetterbox = prefs.getBoolean(KEY_AUTO_LETTERBOX, false)
        )

        val calibration = ColorCalibration(
            saturation = prefs.getFloat(KEY_SATURATION, 1.6f),
            contrast = prefs.getFloat(KEY_CONTRAST, 1.0f),
            maxBrightness = prefs.getInt(KEY_MAX_BRIGHTNESS, 255),
            blackThreshold = prefs.getInt(KEY_BLACK_THRESHOLD, 8),
            gainR = prefs.getFloat(KEY_GAIN_R, 1.0f),
            gainG = prefs.getFloat(KEY_GAIN_G, 1.0f),
            gainB = prefs.getFloat(KEY_GAIN_B, 1.0f),
            gammaR = prefs.getFloat(KEY_GAMMA_R, 1.0f),
            gammaG = prefs.getFloat(KEY_GAMMA_G, 1.0f),
            gammaB = prefs.getFloat(KEY_GAMMA_B, 1.0f),
            colorOrder = prefs.getString(KEY_COLOR_ORDER, "RGB") ?: "RGB",
            smoothingFactor = prefs.getFloat(KEY_SMOOTHING, 0.40f),
            fps = prefs.getInt(KEY_FPS, 60)
        )

        return WledConfig(
            ip = ip,
            port = port,
            perimeter = perimeter,
            calibration = calibration,
            autoStartOnBoot = autoStart
        )
    }

    fun saveConfig(config: WledConfig) {
        prefs.edit().apply {
            putString(KEY_IP, config.ip)
            putInt(KEY_PORT, config.port)
            putBoolean(KEY_AUTO_START, config.autoStartOnBoot)

            putInt(KEY_TOP_LEDS, config.perimeter.topLeds)
            putInt(KEY_RIGHT_LEDS, config.perimeter.rightLeds)
            putInt(KEY_BOTTOM_LEDS, config.perimeter.bottomLeds)
            putInt(KEY_LEFT_LEDS, config.perimeter.leftLeds)
            putString(KEY_START_CORNER, config.perimeter.startCorner.name)
            putString(KEY_DIRECTION, config.perimeter.direction.name)
            putFloat(KEY_TOP_CROP, config.perimeter.topCrop)
            putFloat(KEY_BOTTOM_CROP, config.perimeter.bottomCrop)
            putFloat(KEY_LEFT_CROP, config.perimeter.leftCrop)
            putFloat(KEY_RIGHT_CROP, config.perimeter.rightCrop)
            putBoolean(KEY_AUTO_LETTERBOX, config.perimeter.autoLetterbox)

            putFloat(KEY_SATURATION, config.calibration.saturation)
            putFloat(KEY_CONTRAST, config.calibration.contrast)
            putInt(KEY_MAX_BRIGHTNESS, config.calibration.maxBrightness)
            putInt(KEY_BLACK_THRESHOLD, config.calibration.blackThreshold)
            putFloat(KEY_GAIN_R, config.calibration.gainR)
            putFloat(KEY_GAIN_G, config.calibration.gainG)
            putFloat(KEY_GAIN_B, config.calibration.gainB)
            putFloat(KEY_GAMMA_R, config.calibration.gammaR)
            putFloat(KEY_GAMMA_G, config.calibration.gammaG)
            putFloat(KEY_GAMMA_B, config.calibration.gammaB)
            putString(KEY_COLOR_ORDER, config.calibration.colorOrder)
            putFloat(KEY_SMOOTHING, config.calibration.smoothingFactor)
            putInt(KEY_FPS, config.calibration.fps)
            apply()
        }
    }

    companion object {
        private const val KEY_IP = "wled_ip"
        private const val KEY_PORT = "wled_port"
        private const val KEY_AUTO_START = "auto_start"

        private const val KEY_TOP_LEDS = "top_leds"
        private const val KEY_RIGHT_LEDS = "right_leds"
        private const val KEY_BOTTOM_LEDS = "bottom_leds"
        private const val KEY_LEFT_LEDS = "left_leds"
        private const val KEY_START_CORNER = "start_corner"
        private const val KEY_DIRECTION = "direction"
        private const val KEY_TOP_CROP = "top_crop"
        private const val KEY_BOTTOM_CROP = "bottom_crop"
        private const val KEY_LEFT_CROP = "left_crop"
        private const val KEY_RIGHT_CROP = "right_crop"
        private const val KEY_AUTO_LETTERBOX = "auto_letterbox"

        private const val KEY_SATURATION = "saturation"
        private const val KEY_CONTRAST = "contrast"
        private const val KEY_MAX_BRIGHTNESS = "max_brightness"
        private const val KEY_BLACK_THRESHOLD = "black_threshold"
        private const val KEY_GAIN_R = "gain_r"
        private const val KEY_GAIN_G = "gain_g"
        private const val KEY_GAIN_B = "gain_b"
        private const val KEY_GAMMA_R = "gamma_r"
        private const val KEY_GAMMA_G = "gamma_g"
        private const val KEY_GAMMA_B = "gamma_b"
        private const val KEY_COLOR_ORDER = "color_order"
        private const val KEY_SMOOTHING = "smoothing"
        private const val KEY_FPS = "fps"
    }
}
