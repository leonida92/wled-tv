package com.wled.tv.data

import android.content.Context
import android.content.SharedPreferences
import android.graphics.RectF
import com.wled.tv.model.ColorCalibration
import com.wled.tv.model.Corner
import com.wled.tv.model.DeviceType
import com.wled.tv.model.Direction
import com.wled.tv.model.PerimeterConfig
import com.wled.tv.model.StreamingProtocol
import com.wled.tv.model.WledConfig
import com.wled.tv.model.WledDevice
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Storage abstraction allowing JVM unit testing without Android SDK framework dependencies.
 */
interface ConfigStorage {
    fun getString(key: String, defValue: String? = null): String?
    fun getInt(key: String, defValue: Int): Int
    fun getFloat(key: String, defValue: Float): Float
    fun getBoolean(key: String, defValue: Boolean): Boolean

    fun edit(): Editor

    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putInt(key: String, value: Int): Editor
        fun putFloat(key: String, value: Float): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun apply()
        fun commit(): Boolean
    }
}

/**
 * Production implementation of [ConfigStorage] backed by Android [SharedPreferences].
 */
class SharedPreferencesConfigStorage(private val prefs: SharedPreferences) : ConfigStorage {
    override fun getString(key: String, defValue: String?): String? =
        prefs.getString(key, defValue)

    override fun getInt(key: String, defValue: Int): Int =
        prefs.getInt(key, defValue)

    override fun getFloat(key: String, defValue: Float): Float =
        prefs.getFloat(key, defValue)

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        prefs.getBoolean(key, defValue)

    override fun edit(): ConfigStorage.Editor = EditorImpl(prefs.edit())

    private class EditorImpl(private val editor: SharedPreferences.Editor) : ConfigStorage.Editor {
        override fun putString(key: String, value: String?): ConfigStorage.Editor = apply {
            editor.putString(key, value)
        }

        override fun putInt(key: String, value: Int): ConfigStorage.Editor = apply {
            editor.putInt(key, value)
        }

        override fun putFloat(key: String, value: Float): ConfigStorage.Editor = apply {
            editor.putFloat(key, value)
        }

        override fun putBoolean(key: String, value: Boolean): ConfigStorage.Editor = apply {
            editor.putBoolean(key, value)
        }

        override fun apply() {
            editor.apply()
        }

        override fun commit(): Boolean = editor.commit()
    }
}

/**
 * Thread-safe in-memory implementation of [ConfigStorage] for JVM unit testing.
 */
class InMemoryConfigStorage(
    initialData: Map<String, Any> = emptyMap()
) : ConfigStorage {
    private val data = ConcurrentHashMap<String, Any>(initialData)

    override fun getString(key: String, defValue: String?): String? =
        data[key] as? String ?: defValue

    override fun getInt(key: String, defValue: Int): Int =
        (data[key] as? Number)?.toInt() ?: defValue

    override fun getFloat(key: String, defValue: Float): Float =
        (data[key] as? Number)?.toFloat() ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        data[key] as? Boolean ?: defValue

    override fun edit(): ConfigStorage.Editor = object : ConfigStorage.Editor {
        private val pending = mutableMapOf<String, Any?>()

        override fun putString(key: String, value: String?): ConfigStorage.Editor = apply {
            pending[key] = value
        }

        override fun putInt(key: String, value: Int): ConfigStorage.Editor = apply {
            pending[key] = value
        }

        override fun putFloat(key: String, value: Float): ConfigStorage.Editor = apply {
            pending[key] = value
        }

        override fun putBoolean(key: String, value: Boolean): ConfigStorage.Editor = apply {
            pending[key] = value
        }

        override fun apply() {
            commit()
        }

        override fun commit(): Boolean {
            for ((k, v) in pending) {
                if (v == null) {
                    data.remove(k)
                } else {
                    data[k] = v
                }
            }
            return true
        }
    }
}

/**
 * Observable, thread-safe configuration repository exposing active lighting settings
 * as a reactive StateFlow with asynchronous disk persistence.
 */
class ConfigRepository(
    private val storage: ConfigStorage,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    constructor(context: Context, ioDispatcher: CoroutineDispatcher = Dispatchers.IO) : this(
        SharedPreferencesConfigStorage(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        ),
        ioDispatcher
    )

    private val stateMutex = Mutex()
    private val writeMutex = Mutex()

    private val _configFlow = MutableStateFlow(loadInitialConfig())
    val configFlow: StateFlow<WledConfig> = _configFlow.asStateFlow()

    val currentConfig: WledConfig
        get() = _configFlow.value

    suspend fun updateConfig(transform: (WledConfig) -> WledConfig): WledConfig {
        val updated = stateMutex.withLock {
            val next = transform(_configFlow.value)
            _configFlow.value = next
            next
        }
        withContext(ioDispatcher) {
            writeMutex.withLock {
                persistToStorage(updated)
            }
        }
        return updated
    }

    fun updateConfigBlocking(transform: (WledConfig) -> WledConfig): WledConfig = runBlocking {
        updateConfig(transform)
    }

    private fun loadInitialConfig(): WledConfig {
        val autoStart = storage.getBoolean(KEY_AUTO_START, false)

        val globalCalibration = ColorCalibration(
            saturation = storage.getFloat(KEY_SATURATION, 1.6f),
            contrast = storage.getFloat(KEY_CONTRAST, 1.0f),
            maxBrightness = storage.getInt(KEY_MAX_BRIGHTNESS, 255),
            blackThreshold = storage.getInt(KEY_BLACK_THRESHOLD, 8),
            gainR = storage.getFloat(KEY_GAIN_R, 1.0f),
            gainG = storage.getFloat(KEY_GAIN_G, 1.0f),
            gainB = storage.getFloat(KEY_GAIN_B, 1.0f),
            gammaR = storage.getFloat(KEY_GAMMA_R, 1.0f),
            gammaG = storage.getFloat(KEY_GAMMA_G, 1.0f),
            gammaB = storage.getFloat(KEY_GAMMA_B, 1.0f),
            colorOrder = storage.getString(KEY_COLOR_ORDER, "RGB") ?: "RGB",
            isRgbw = storage.getBoolean(KEY_RGBW, false),
            rgbwMode = storage.getString(KEY_RGBW_MODE, "AUTO_SUBTRACT") ?: "AUTO_SUBTRACT",
            smoothingFactor = storage.getFloat(KEY_SMOOTHING, 0.40f),
            fps = storage.getInt(KEY_FPS, 60),
            captureWidth = storage.getInt(KEY_CAPTURE_WIDTH, 320),
            captureHeight = storage.getInt(KEY_CAPTURE_HEIGHT, 180)
        )

        val devicesJson = storage.getString(KEY_DEVICES_JSON, null)
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
        val ip = storage.getString(KEY_IP, "192.168.1.66") ?: "192.168.1.66"
        val port = storage.getInt(KEY_PORT, 21324)
        val protocolStr = storage.getString(KEY_PROTOCOL, if (port == 4048) StreamingProtocol.DDP.name else StreamingProtocol.DRGB.name)
        val protocol = try {
            StreamingProtocol.valueOf(protocolStr ?: StreamingProtocol.DRGB.name)
        } catch (_: Exception) {
            if (port == 4048) StreamingProtocol.DDP else StreamingProtocol.DRGB
        }

        val perimeter = PerimeterConfig(
            topLeds = storage.getInt(KEY_TOP_LEDS, 54),
            rightLeds = storage.getInt(KEY_RIGHT_LEDS, 30),
            bottomLeds = storage.getInt(KEY_BOTTOM_LEDS, 54),
            leftLeds = storage.getInt(KEY_LEFT_LEDS, 30),
            startCorner = try {
                Corner.valueOf(storage.getString(KEY_START_CORNER, Corner.BOTTOM_LEFT.name) ?: Corner.BOTTOM_LEFT.name)
            } catch (_: Exception) { Corner.BOTTOM_LEFT },
            direction = try {
                Direction.valueOf(storage.getString(KEY_DIRECTION, Direction.CLOCKWISE.name) ?: Direction.CLOCKWISE.name)
            } catch (_: Exception) { Direction.CLOCKWISE },
            topCrop = storage.getFloat(KEY_TOP_CROP, 0.0f),
            bottomCrop = storage.getFloat(KEY_BOTTOM_CROP, 0.0f),
            leftCrop = storage.getFloat(KEY_LEFT_CROP, 0.0f),
            rightCrop = storage.getFloat(KEY_RIGHT_CROP, 0.0f),
            depth = storage.getFloat(KEY_DEPTH, 0.12f),
            autoLetterbox = storage.getBoolean(KEY_AUTO_LETTERBOX, true)
        )

        return WledDevice(
            id = "primary_tv_backlight",
            name = "TV Backlight",
            ip = ip,
            port = port,
            protocol = protocol,
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
                val protocolStr = obj.optString("protocol", if (port == 4048) StreamingProtocol.DDP.name else StreamingProtocol.DRGB.name)
                val protocol = try {
                    StreamingProtocol.valueOf(protocolStr)
                } catch (_: Exception) {
                    if (port == 4048) StreamingProtocol.DDP else StreamingProtocol.DRGB
                }
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
                    depth = obj.optDouble("depth", 0.12).toFloat(),
                    autoLetterbox = obj.optBoolean("autoLetterbox", true)
                )

                val customRect = RectF().apply {
                    left = obj.optDouble("rectL", 0.0).toFloat()
                    top = obj.optDouble("rectT", 0.0).toFloat()
                    right = obj.optDouble("rectR", 1.0).toFloat()
                    bottom = obj.optDouble("rectB", 1.0).toFloat()
                }

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
                        isRgbw = obj.optBoolean("cal_rgbw", fallbackCalibration.isRgbw),
                        rgbwMode = obj.optString("cal_rgbwm", fallbackCalibration.rgbwMode),
                        smoothingFactor = obj.optDouble("cal_sm", fallbackCalibration.smoothingFactor.toDouble()).toFloat(),
                        fps = fallbackCalibration.fps,
                        captureWidth = fallbackCalibration.captureWidth,
                        captureHeight = fallbackCalibration.captureHeight
                    )
                } else {
                    fallbackCalibration.copy(
                        colorOrder = colorOrder,
                        isRgbw = obj.optBoolean("cal_rgbw", fallbackCalibration.isRgbw),
                        rgbwMode = obj.optString("cal_rgbwm", fallbackCalibration.rgbwMode)
                    )
                }

                list.add(
                    WledDevice(
                        id = id,
                        name = name,
                        ip = ip,
                        port = port,
                        protocol = protocol,
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

    private fun persistToStorage(config: WledConfig) {
        val primary = config.primaryDevice
        val array = JSONArray()

        for (dev in config.devices) {
            val obj = JSONObject().apply {
                put("id", dev.id)
                put("name", dev.name)
                put("ip", dev.ip)
                put("port", dev.port)
                put("protocol", dev.protocol.name)
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
                put("topCrop", dev.perimeter.topCrop.toDouble())
                put("bottomCrop", dev.perimeter.bottomCrop.toDouble())
                put("leftCrop", dev.perimeter.leftCrop.toDouble())
                put("rightCrop", dev.perimeter.rightCrop.toDouble())
                put("depth", dev.perimeter.depth.toDouble())
                put("autoLetterbox", dev.perimeter.autoLetterbox)

                put("rectL", dev.customRect.left.toDouble())
                put("rectT", dev.customRect.top.toDouble())
                put("rectR", dev.customRect.right.toDouble())
                put("rectB", dev.customRect.bottom.toDouble())

                put("cal_sat", dev.calibration.saturation.toDouble())
                put("cal_con", dev.calibration.contrast.toDouble())
                put("cal_bri", dev.calibration.maxBrightness)
                put("cal_blk", dev.calibration.blackThreshold)
                put("cal_gr", dev.calibration.gainR.toDouble())
                put("cal_gg", dev.calibration.gainG.toDouble())
                put("cal_gb", dev.calibration.gainB.toDouble())
                put("cal_gamr", dev.calibration.gammaR.toDouble())
                put("cal_gamg", dev.calibration.gammaG.toDouble())
                put("cal_gamb", dev.calibration.gammaB.toDouble())
                put("cal_rgbw", dev.calibration.isRgbw)
                put("cal_rgbwm", dev.calibration.rgbwMode)
                put("cal_sm", dev.calibration.smoothingFactor.toDouble())
            }
            array.put(obj)
        }

        val editor = storage.edit()
        editor.putString(KEY_DEVICES_JSON, array.toString())

        editor.putString(KEY_IP, primary.ip)
        editor.putInt(KEY_PORT, primary.port)
        editor.putString(KEY_PROTOCOL, primary.protocol.name)
        editor.putBoolean(KEY_AUTO_START, config.autoStartOnBoot)

        editor.putInt(KEY_TOP_LEDS, primary.perimeter.topLeds)
        editor.putInt(KEY_RIGHT_LEDS, primary.perimeter.rightLeds)
        editor.putInt(KEY_BOTTOM_LEDS, primary.perimeter.bottomLeds)
        editor.putInt(KEY_LEFT_LEDS, primary.perimeter.leftLeds)
        editor.putString(KEY_START_CORNER, primary.perimeter.startCorner.name)
        editor.putString(KEY_DIRECTION, primary.perimeter.direction.name)
        editor.putFloat(KEY_TOP_CROP, primary.perimeter.topCrop)
        editor.putFloat(KEY_BOTTOM_CROP, primary.perimeter.bottomCrop)
        editor.putFloat(KEY_LEFT_CROP, primary.perimeter.leftCrop)
        editor.putFloat(KEY_RIGHT_CROP, primary.perimeter.rightCrop)
        editor.putFloat(KEY_DEPTH, primary.perimeter.depth)
        editor.putBoolean(KEY_AUTO_LETTERBOX, primary.perimeter.autoLetterbox)

        editor.putFloat(KEY_SATURATION, primary.calibration.saturation)
        editor.putFloat(KEY_CONTRAST, primary.calibration.contrast)
        editor.putInt(KEY_MAX_BRIGHTNESS, primary.calibration.maxBrightness)
        editor.putInt(KEY_BLACK_THRESHOLD, primary.calibration.blackThreshold)
        editor.putFloat(KEY_GAIN_R, primary.calibration.gainR)
        editor.putFloat(KEY_GAIN_G, primary.calibration.gainG)
        editor.putFloat(KEY_GAIN_B, primary.calibration.gainB)
        editor.putFloat(KEY_GAMMA_R, primary.calibration.gammaR)
        editor.putFloat(KEY_GAMMA_G, primary.calibration.gammaG)
        editor.putFloat(KEY_GAMMA_B, primary.calibration.gammaB)
        editor.putString(KEY_COLOR_ORDER, primary.calibration.colorOrder)
        editor.putBoolean(KEY_RGBW, primary.calibration.isRgbw)
        editor.putString(KEY_RGBW_MODE, primary.calibration.rgbwMode)
        editor.putFloat(KEY_SMOOTHING, primary.calibration.smoothingFactor)
        editor.putInt(KEY_FPS, config.calibration.fps)
        editor.putInt(KEY_CAPTURE_WIDTH, config.calibration.captureWidth)
        editor.putInt(KEY_CAPTURE_HEIGHT, config.calibration.captureHeight)

        editor.apply()
    }

    companion object {
        const val PREFS_NAME = "wled_tv_prefs"

        @Volatile
        private var instance: ConfigRepository? = null

        fun getInstance(context: Context): ConfigRepository =
            instance ?: synchronized(this) {
                instance ?: ConfigRepository(context.applicationContext).also { instance = it }
            }

        fun setInstanceForTesting(repository: ConfigRepository?) {
            instance = repository
        }

        private const val KEY_DEVICES_JSON = "wled_devices_json"

        private const val KEY_IP = "wled_ip"
        private const val KEY_PORT = "wled_port"
        private const val KEY_PROTOCOL = "wled_protocol"
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
        private const val KEY_DEPTH = "perimeter_depth"
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
        private const val KEY_RGBW = "rgbw_enabled"
        private const val KEY_RGBW_MODE = "rgbw_mode"
        private const val KEY_SMOOTHING = "smoothing"
        private const val KEY_FPS = "capture_fps"
        private const val KEY_CAPTURE_WIDTH = "capture_width"
        private const val KEY_CAPTURE_HEIGHT = "capture_height"
    }
}
