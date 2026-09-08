package com.wled.tv.data

import android.content.Context
import android.content.SharedPreferences
import android.graphics.RectF
import com.wled.tv.model.ColorCalibration
import com.wled.tv.model.Corner
import com.wled.tv.model.DeviceType
import com.wled.tv.model.Direction
import com.wled.tv.model.PerimeterConfig
import com.wled.tv.model.WledConfig
import com.wled.tv.model.WledDevice
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class PreferencesRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("wled_tv_prefs", Context.MODE_PRIVATE)

    fun loadConfig(): WledConfig {
        val autoStart = prefs.getBoolean(KEY_AUTO_START, false)

        val globalCalibration = ColorCalibration(
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

        val devicesJson = prefs.getString(KEY_DEVICES_JSON, null)
        val devices = if (!devicesJson.isNullOrBlank()) {
            parseDevicesJson(devicesJson, globalCalibration)
        } else {
            // Migrate legacy single-device configuration
            listOf(loadLegacyPrimaryDevice(globalCalibration))
        }

        return WledConfig(
            devices = if (devices.isEmpty()) listOf(loadLegacyPrimaryDevice(globalCalibration)) else devices,
            calibration = globalCalibration,
            autoStartOnBoot = autoStart
        )
    }

    private fun loadLegacyPrimaryDevice(globalCalibration: ColorCalibration): WledDevice {
        val ip = prefs.getString(KEY_IP, "192.168.1.66") ?: "192.168.1.66"
        val port = prefs.getInt(KEY_PORT, 21324)

        val perimeter = PerimeterConfig(
            topLeds = prefs.getInt(KEY_TOP_LEDS, 54),
            rightLeds = prefs.getInt(KEY_RIGHT_LEDS, 30),
            bottomLeds = prefs.getInt(KEY_BOTTOM_LEDS, 54),
            leftLeds = prefs.getInt(KEY_LEFT_LEDS, 30),
            startCorner = try {
                Corner.valueOf(prefs.getString(KEY_START_CORNER, Corner.BOTTOM_LEFT.name) ?: Corner.BOTTOM_LEFT.name)
            } catch (_: Exception) { Corner.BOTTOM_LEFT },
            direction = try {
                Direction.valueOf(prefs.getString(KEY_DIRECTION, Direction.CLOCKWISE.name) ?: Direction.CLOCKWISE.name)
            } catch (_: Exception) { Direction.CLOCKWISE },
            topCrop = prefs.getFloat(KEY_TOP_CROP, 0.0f),
            bottomCrop = prefs.getFloat(KEY_BOTTOM_CROP, 0.0f),
            leftCrop = prefs.getFloat(KEY_LEFT_CROP, 0.0f),
            rightCrop = prefs.getFloat(KEY_RIGHT_CROP, 0.0f),
            autoLetterbox = prefs.getBoolean(KEY_AUTO_LETTERBOX, true)
        )

        return WledDevice(
            id = "primary_tv_backlight",
            name = "TV Backlight",
            ip = ip,
            port = port,
            enabled = true,
            type = DeviceType.PERIMETER,
            ledCount = perimeter.totalLeds,
            perimeter = perimeter,
            calibration = globalCalibration
        )
    }

    private fun parseDevicesJson(jsonStr: String, fallbackCalibration: ColorCalibration): List<WledDevice> {
        val list = mutableListOf<WledDevice>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.optString("id", UUID.randomUUID().toString())
                val name = obj.optString("name", "Light $i")
                val ip = obj.optString("ip", "192.168.1.66")
                val port = obj.optInt("port", 21324)
                val enabled = obj.optBoolean("enabled", true)
                val typeStr = obj.optString("type", DeviceType.PERIMETER.name)
                val type = try { DeviceType.valueOf(typeStr) } catch (_: Exception) { DeviceType.PERIMETER }
                val colorOrder = obj.optString("colorOrder", fallbackCalibration.colorOrder)
                val ledCount = obj.optInt("ledCount", 1)

                val perimeter = PerimeterConfig(
                    topLeds = obj.optInt("topLeds", 54),
                    rightLeds = obj.optInt("rightLeds", 30),
                    bottomLeds = obj.optInt("bottomLeds", 54),
                    leftLeds = obj.optInt("leftLeds", 30),
                    startCorner = try { Corner.valueOf(obj.optString("startCorner", Corner.BOTTOM_LEFT.name)) } catch (_: Exception) { Corner.BOTTOM_LEFT },
                    direction = try { Direction.valueOf(obj.optString("direction", Direction.CLOCKWISE.name)) } catch (_: Exception) { Direction.CLOCKWISE },
                    topCrop = obj.optDouble("topCrop", 0.0).toFloat(),
                    bottomCrop = obj.optDouble("bottomCrop", 0.0).toFloat(),
                    leftCrop = obj.optDouble("leftCrop", 0.0).toFloat(),
                    rightCrop = obj.optDouble("rightCrop", 0.0).toFloat(),
                    autoLetterbox = obj.optBoolean("autoLetterbox", true)
                )

                val customRect = RectF(
                    obj.optDouble("rectL", 0.0).toFloat(),
                    obj.optDouble("rectT", 0.0).toFloat(),
                    obj.optDouble("rectR", 1.0).toFloat(),
                    obj.optDouble("rectB", 1.0).toFloat()
                )

                // Per-device calibration parsing
                val devCalibration = if (obj.has("cal_sat")) {
                    ColorCalibration(
                        saturation = obj.optDouble("cal_sat", fallbackCalibration.saturation.toDouble()).toFloat(),
                        contrast = obj.optDouble("cal_con", fallbackCalibration.contrast.toDouble()).toFloat(),
                        maxBrightness = obj.optInt("cal_bri", fallbackCalibration.maxBrightness),
                        blackThreshold = obj.optInt("cal_blk", fallbackCalibration.blackThreshold),
                        gainR = obj.optDouble("cal_gr", fallbackCalibration.gainR.toDouble()).toFloat(),
                        gainG = obj.optDouble("cal_gg", fallbackCalibration.gainG.toDouble()).toFloat(),
                        gainB = obj.optDouble("cal_gb", fallbackCalibration.gainB.toDouble()).toFloat(),
                        gammaR = obj.optDouble("cal_gamr", fallbackCalibration.gammaR.toDouble()).toFloat(),
                        gammaG = obj.optDouble("cal_gamg", fallbackCalibration.gammaG.toDouble()).toFloat(),
                        gammaB = obj.optDouble("cal_gamb", fallbackCalibration.gammaB.toDouble()).toFloat(),
                        colorOrder = colorOrder,
                        smoothingFactor = obj.optDouble("cal_sm", fallbackCalibration.smoothingFactor.toDouble()).toFloat(),
                        fps = fallbackCalibration.fps
                    )
                } else {
                    fallbackCalibration.copy(colorOrder = colorOrder)
                }

                list.add(
                    WledDevice(
                        id = id,
                        name = name,
                        ip = ip,
                        port = port,
                        enabled = enabled,
                        type = type,
                        ledCount = ledCount,
                        perimeter = perimeter,
                        calibration = devCalibration,
                        customRect = customRect
                    )
                )
            }
        } catch (_: Exception) {
            return emptyList()
        }
        return list
    }

    fun saveConfig(config: WledConfig) {
        val primary = config.primaryDevice
        val array = JSONArray()

        for (dev in config.devices) {
            val obj = JSONObject().apply {
                put("id", dev.id)
                put("name", dev.name)
                put("ip", dev.ip)
                put("port", dev.port)
                put("enabled", dev.enabled)
                put("type", dev.type.name)
                put("colorOrder", dev.colorOrder)
                put("ledCount", dev.ledCount)

                put("topLeds", dev.perimeter.topLeds)
                put("rightLeds", dev.perimeter.rightLeds)
                put("bottomLeds", dev.perimeter.bottomLeds)
                put("leftLeds", dev.perimeter.leftLeds)
                put("startCorner", dev.perimeter.startCorner.name)
                put("direction", dev.perimeter.direction.name)
                put("topCrop", dev.perimeter.topCrop)
                put("bottomCrop", dev.perimeter.bottomCrop)
                put("leftCrop", dev.perimeter.leftCrop)
                put("rightCrop", dev.perimeter.rightCrop)
                put("autoLetterbox", dev.perimeter.autoLetterbox)

                put("rectL", dev.customRect.left)
                put("rectT", dev.customRect.top)
                put("rectR", dev.customRect.right)
                put("rectB", dev.customRect.bottom)

                // Save per-device calibration parameters
                put("cal_sat", dev.calibration.saturation)
                put("cal_con", dev.calibration.contrast)
                put("cal_bri", dev.calibration.maxBrightness)
                put("cal_blk", dev.calibration.blackThreshold)
                put("cal_gr", dev.calibration.gainR)
                put("cal_gg", dev.calibration.gainG)
                put("cal_gb", dev.calibration.gainB)
                put("cal_gamr", dev.calibration.gammaR)
                put("cal_gamg", dev.calibration.gammaG)
                put("cal_gamb", dev.calibration.gammaB)
                put("cal_sm", dev.calibration.smoothingFactor)
            }
            array.put(obj)
        }

        prefs.edit().apply {
            putString(KEY_DEVICES_JSON, array.toString())

            // Maintain legacy keys for primary device
            putString(KEY_IP, primary.ip)
            putInt(KEY_PORT, primary.port)
            putBoolean(KEY_AUTO_START, config.autoStartOnBoot)

            putInt(KEY_TOP_LEDS, primary.perimeter.topLeds)
            putInt(KEY_RIGHT_LEDS, primary.perimeter.rightLeds)
            putInt(KEY_BOTTOM_LEDS, primary.perimeter.bottomLeds)
            putInt(KEY_LEFT_LEDS, primary.perimeter.leftLeds)
            putString(KEY_START_CORNER, primary.perimeter.startCorner.name)
            putString(KEY_DIRECTION, primary.perimeter.direction.name)
            putFloat(KEY_TOP_CROP, primary.perimeter.topCrop)
            putFloat(KEY_BOTTOM_CROP, primary.perimeter.bottomCrop)
            putFloat(KEY_LEFT_CROP, primary.perimeter.leftCrop)
            putFloat(KEY_RIGHT_CROP, primary.perimeter.rightCrop)
            putBoolean(KEY_AUTO_LETTERBOX, primary.perimeter.autoLetterbox)

            putFloat(KEY_SATURATION, primary.calibration.saturation)
            putFloat(KEY_CONTRAST, primary.calibration.contrast)
            putInt(KEY_MAX_BRIGHTNESS, primary.calibration.maxBrightness)
            putInt(KEY_BLACK_THRESHOLD, primary.calibration.blackThreshold)
            putFloat(KEY_GAIN_R, primary.calibration.gainR)
            putFloat(KEY_GAIN_G, primary.calibration.gainG)
            putFloat(KEY_GAIN_B, primary.calibration.gainB)
            putFloat(KEY_GAMMA_R, primary.calibration.gammaR)
            putFloat(KEY_GAMMA_G, primary.calibration.gammaG)
            putFloat(KEY_GAMMA_B, primary.calibration.gammaB)
            putString(KEY_COLOR_ORDER, primary.calibration.colorOrder)
            putFloat(KEY_SMOOTHING, primary.calibration.smoothingFactor)
            putInt(KEY_FPS, config.calibration.fps)
            apply()
        }
    }

    companion object {
        private const val KEY_DEVICES_JSON = "wled_devices_json"

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
        private const val KEY_FPS = "capture_fps"
    }
}
