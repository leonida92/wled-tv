package com.wled.tv.data

import android.content.Context
import android.content.SharedPreferences
import com.wled.tv.geometry.NormalizedRect
import com.wled.tv.model.CaptureSettings
import com.wled.tv.model.ColorCalibration
import com.wled.tv.model.Corner
import com.wled.tv.model.DeviceType
import com.wled.tv.model.Direction
import com.wled.tv.model.PerimeterConfig
import com.wled.tv.model.PresetProfile
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
import java.io.File
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
 * Result of exporting a configuration backup to disk.
 */
data class BackupExportResult(
    val file: File,
    val json: String
)

/**
 * Observable, thread-safe configuration repository exposing active lighting settings
 * as a reactive StateFlow with asynchronous disk persistence, schema migration,
 * and JSON backup I/O.
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

    suspend fun applyPreset(preset: PresetProfile): WledConfig =
        updateConfig { it.applyPreset(preset) }

    suspend fun updateDeviceCalibration(
        deviceId: String? = null,
        transform: (ColorCalibration) -> ColorCalibration
    ): WledConfig = updateConfig { it.updateDeviceCalibration(deviceId, transform) }

    suspend fun updateCaptureSettings(
        transform: (CaptureSettings) -> CaptureSettings
    ): WledConfig = updateConfig { it.updateCaptureSettings(transform) }

    fun updateConfigBlocking(transform: (WledConfig) -> WledConfig): WledConfig = runBlocking {
        updateConfig(transform)
    }

    suspend fun exportConfig(): String = withContext(ioDispatcher) {
        exportConfigJson(_configFlow.value)
    }

    suspend fun importConfig(jsonStr: String): Boolean = withContext(ioDispatcher) {
        try {
            val imported = importConfigJson(jsonStr)
            updateConfig { imported }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Exports the current configuration as a versioned JSON backup file, preferring
     * [primaryDir] when writable and falling back to [fallbackDir].
     */
    suspend fun exportBackup(primaryDir: File?, fallbackDir: File): BackupExportResult = withContext(ioDispatcher) {
        val json = exportConfigJson(_configFlow.value)
        val candidateDirs = listOfNotNull(primaryDir, fallbackDir)
        var lastError: Exception? = null
        for (dir in candidateDirs) {
            try {
                if (dir.exists() || dir.mkdirs()) {
                    val targetFile = File(dir, BACKUP_FILENAME)
                    targetFile.writeText(json)
                    return@withContext BackupExportResult(file = targetFile, json = json)
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        val fallbackFile = File(fallbackDir, BACKUP_FILENAME)
        try {
            fallbackFile.parentFile?.mkdirs()
            fallbackFile.writeText(json)
            BackupExportResult(file = fallbackFile, json = json)
        } catch (e: Exception) {
            throw lastError ?: e
        }
    }

    /**
     * Locates an existing backup file in [primaryDir] or [fallbackDir], returning null if none exists.
     */
    suspend fun findBackupFile(primaryDir: File?, fallbackDir: File): File? = withContext(ioDispatcher) {
        listOfNotNull(primaryDir, fallbackDir)
            .map { File(it, BACKUP_FILENAME) }
            .firstOrNull { it.exists() && it.isFile }
    }

    /**
     * Reads [file] on [ioDispatcher] and imports its JSON configuration into the repository.
     */
    suspend fun importBackupFromFile(file: File): Boolean = withContext(ioDispatcher) {
        try {
            if (!file.exists() || !file.isFile) return@withContext false
            val json = file.readText()
            importConfig(json)
        } catch (_: Exception) {
            false
        }
    }

    private fun loadInitialConfig(): WledConfig {
        migrateIfNeeded(storage)
        val autoStart = storage.getBoolean(KEY_AUTO_START, false)
        val activeProfileId = storage.getString(KEY_ACTIVE_PROFILE_ID, PresetProfile.DEFAULT.id)
            ?: PresetProfile.DEFAULT.id

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
            smoothingFactor = storage.getFloat(KEY_SMOOTHING, 0.40f)
        )

        val captureSettings = CaptureSettings(
            fps = storage.getInt(KEY_FPS, 60),
            captureWidth = storage.getInt(KEY_CAPTURE_WIDTH, 320),
            captureHeight = storage.getInt(KEY_CAPTURE_HEIGHT, 180)
        )

        val fallbackColorOrder = storage.getString(KEY_COLOR_ORDER, "RGB") ?: "RGB"
        val fallbackIsRgbw = storage.getBoolean(KEY_RGBW, false)
        val fallbackRgbwMode = storage.getString(KEY_RGBW_MODE, "AUTO_SUBTRACT") ?: "AUTO_SUBTRACT"

        val devicesJson = storage.getString(KEY_DEVICES_JSON, null)
        val devices = if (!devicesJson.isNullOrBlank()) {
            parseDevicesJson(
                jsonStr = devicesJson,
                fallbackCalibration = globalCalibration,
                fallbackColorOrder = fallbackColorOrder,
                fallbackIsRgbw = fallbackIsRgbw,
                fallbackRgbwMode = fallbackRgbwMode
            )
        } else {
            // Migrate legacy single-device configuration
            listOf(
                loadLegacyPrimaryDevice(
                    globalCalibration = globalCalibration,
                    colorOrder = fallbackColorOrder,
                    isRgbw = fallbackIsRgbw,
                    rgbwMode = fallbackRgbwMode
                )
            )
        }

        val resolvedDevices = if (devices.isEmpty()) {
            listOf(
                loadLegacyPrimaryDevice(
                    globalCalibration = globalCalibration,
                    colorOrder = fallbackColorOrder,
                    isRgbw = fallbackIsRgbw,
                    rgbwMode = fallbackRgbwMode
                )
            )
        } else {
            devices
        }

        return WledConfig(
            devices = resolvedDevices,
            calibration = globalCalibration,
            capture = captureSettings,
            autoStartOnBoot = autoStart,
            activeProfileId = activeProfileId
        )
    }

    private fun loadLegacyPrimaryDevice(
        globalCalibration: ColorCalibration,
        colorOrder: String,
        isRgbw: Boolean,
        rgbwMode: String
    ): WledDevice {
        val ip = storage.getString(KEY_IP, "192.168.1.66") ?: "192.168.1.66"
        val port = storage.getInt(KEY_PORT, 21324)
        val protocolStr = storage.getString(
            KEY_PROTOCOL,
            if (port == 4048) StreamingProtocol.DDP.name else StreamingProtocol.DRGB.name
        )
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
            } catch (_: Exception) {
                Corner.BOTTOM_LEFT
            },
            direction = try {
                Direction.valueOf(storage.getString(KEY_DIRECTION, Direction.CLOCKWISE.name) ?: Direction.CLOCKWISE.name)
            } catch (_: Exception) {
                Direction.CLOCKWISE
            },
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
            calibration = globalCalibration,
            colorOrder = colorOrder,
            isRgbw = isRgbw,
            rgbwMode = rgbwMode
        )
    }

    private fun parseDevicesJson(
        jsonStr: String,
        fallbackCalibration: ColorCalibration,
        fallbackColorOrder: String,
        fallbackIsRgbw: Boolean,
        fallbackRgbwMode: String
    ): List<WledDevice> {
        return try {
            val array = JSONArray(jsonStr)
            deserializeDevices(
                array = array,
                fallbackCalibration = fallbackCalibration,
                fallbackColorOrder = fallbackColorOrder,
                fallbackIsRgbw = fallbackIsRgbw,
                fallbackRgbwMode = fallbackRgbwMode
            )
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun persistToStorage(config: WledConfig) {
        val primary = config.primaryDevice
        val array = serializeDevices(config.devices)

        val editor = storage.edit()
        editor.putInt(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION)
        editor.putString(KEY_ACTIVE_PROFILE_ID, config.activeProfileId)
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
        editor.putFloat(KEY_SMOOTHING, primary.calibration.smoothingFactor)

        editor.putString(KEY_COLOR_ORDER, primary.colorOrder)
        editor.putBoolean(KEY_RGBW, primary.isRgbw)
        editor.putString(KEY_RGBW_MODE, primary.rgbwMode)

        editor.putInt(KEY_FPS, config.capture.fps)
        editor.putInt(KEY_CAPTURE_WIDTH, config.capture.captureWidth)
        editor.putInt(KEY_CAPTURE_HEIGHT, config.capture.captureHeight)

        editor.apply()
    }

    companion object {
        const val PREFS_NAME = "wled_tv_prefs"
        const val CURRENT_SCHEMA_VERSION = 1
        const val KEY_SCHEMA_VERSION = "config_schema_version"
        const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"
        const val APP_SIGNATURE = "wled-tv"
        const val BACKUP_FILENAME = "wled_tv_backup.json"

        @Volatile
        private var instance: ConfigRepository? = null

        fun getInstance(context: Context): ConfigRepository =
            instance ?: synchronized(this) {
                instance ?: ConfigRepository(context.applicationContext).also { instance = it }
            }

        fun setInstanceForTesting(repository: ConfigRepository?) {
            instance = repository
        }

        /** Serializes a single [WledDevice] using the canonical device schema for Intent/draft passing. */
        fun encodeDeviceJson(device: WledDevice): String = serializeDevice(device).toString()

        /** Deserializes a single [WledDevice] previously encoded by [encodeDeviceJson]. */
        fun decodeDeviceJson(json: String): WledDevice =
            deserializeDevice(JSONObject(json), fallbackCalibration = ColorCalibration())

        internal fun migrateIfNeeded(storage: ConfigStorage): Int {
            var version = storage.getInt(KEY_SCHEMA_VERSION, 0)
            if (version < 1) {
                val editor = storage.edit()
                val existingProfile = storage.getString(KEY_ACTIVE_PROFILE_ID, null)
                if (existingProfile.isNullOrBlank()) {
                    editor.putString(KEY_ACTIVE_PROFILE_ID, PresetProfile.DEFAULT.id)
                }
                editor.putInt(KEY_SCHEMA_VERSION, 1)
                editor.commit()
                version = 1
            }
            return version
        }

        internal fun exportConfigJson(
            config: WledConfig,
            timestamp: Long = System.currentTimeMillis()
        ): String {
            val root = JSONObject().apply {
                put("app", APP_SIGNATURE)
                put("schemaVersion", CURRENT_SCHEMA_VERSION)
                put("timestamp", timestamp)
                put("config", serializeConfig(config))
            }
            return root.toString(2)
        }

        internal fun importConfigJson(jsonStr: String): WledConfig {
            val root = try {
                JSONObject(jsonStr)
            } catch (e: Exception) {
                throw IllegalArgumentException("Malformed JSON string: ${e.message}", e)
            }

            val app = root.optString("app", "")
            if (app != APP_SIGNATURE) {
                throw IllegalArgumentException("Invalid app signature: '$app' (expected '$APP_SIGNATURE')")
            }

            if (!root.has("schemaVersion")) {
                throw IllegalArgumentException("Missing 'schemaVersion' field")
            }
            val schemaVersion = root.getInt("schemaVersion")
            if (schemaVersion > CURRENT_SCHEMA_VERSION) {
                throw IllegalArgumentException(
                    "Unsupported future schema version: $schemaVersion (max supported: $CURRENT_SCHEMA_VERSION)"
                )
            }
            if (schemaVersion < 0) {
                throw IllegalArgumentException("Invalid schema version: $schemaVersion")
            }

            val configObj = root.optJSONObject("config")
                ?: throw IllegalArgumentException("Missing 'config' object in export payload")

            return deserializeConfig(configObj)
        }

        private fun serializeConfig(config: WledConfig): JSONObject = JSONObject().apply {
            put("activeProfileId", config.activeProfileId)
            put("autoStartOnBoot", config.autoStartOnBoot)
            put("capture", serializeCaptureSettings(config.capture))
            put("calibration", serializeCalibration(config.calibration))
            put("devices", serializeDevices(config.devices))
        }

        private fun deserializeConfig(configObj: JSONObject): WledConfig {
            val activeProfileId = configObj.optString("activeProfileId", PresetProfile.DEFAULT.id)
                .ifBlank { PresetProfile.DEFAULT.id }
            val autoStartOnBoot = configObj.optBoolean("autoStartOnBoot", false)

            val calObj = configObj.optJSONObject("calibration")
            val calibration = if (calObj != null) {
                deserializeCalibration(calObj)
            } else {
                ColorCalibration()
            }

            val captureObj = configObj.optJSONObject("capture")
            val capture = when {
                captureObj != null -> deserializeCaptureSettings(captureObj)
                calObj != null -> deserializeCaptureSettings(calObj)
                else -> CaptureSettings()
            }

            val fallbackColorOrder = calObj?.optString("colorOrder", "RGB")?.ifBlank { "RGB" } ?: "RGB"
            val fallbackIsRgbw = calObj?.optBoolean("isRgbw", false) ?: false
            val fallbackRgbwMode = calObj?.optString("rgbwMode", "AUTO_SUBTRACT")?.ifBlank { "AUTO_SUBTRACT" }
                ?: "AUTO_SUBTRACT"

            val devArray = configObj.optJSONArray("devices")
            val devices = if (devArray != null) {
                deserializeDevices(
                    array = devArray,
                    fallbackCalibration = calibration,
                    fallbackColorOrder = fallbackColorOrder,
                    fallbackIsRgbw = fallbackIsRgbw,
                    fallbackRgbwMode = fallbackRgbwMode
                )
            } else {
                emptyList()
            }

            return WledConfig(
                devices = if (devices.isEmpty()) {
                    listOf(
                        WledDevice(
                            calibration = calibration,
                            colorOrder = fallbackColorOrder,
                            isRgbw = fallbackIsRgbw,
                            rgbwMode = fallbackRgbwMode
                        )
                    )
                } else {
                    devices
                },
                calibration = calibration,
                capture = capture,
                autoStartOnBoot = autoStartOnBoot,
                activeProfileId = activeProfileId
            )
        }

        private fun serializeCaptureSettings(capture: CaptureSettings): JSONObject = JSONObject().apply {
            put("fps", capture.fps)
            put("captureWidth", capture.captureWidth)
            put("captureHeight", capture.captureHeight)
        }

        private fun deserializeCaptureSettings(obj: JSONObject): CaptureSettings {
            val default = CaptureSettings()
            return CaptureSettings(
                fps = obj.optInt("fps", default.fps),
                captureWidth = obj.optInt("captureWidth", default.captureWidth),
                captureHeight = obj.optInt("captureHeight", default.captureHeight)
            )
        }

        private fun serializeCalibration(cal: ColorCalibration): JSONObject = JSONObject().apply {
            put("saturation", cal.saturation.toDouble())
            put("contrast", cal.contrast.toDouble())
            put("maxBrightness", cal.maxBrightness)
            put("blackThreshold", cal.blackThreshold)
            put("gainR", cal.gainR.toDouble())
            put("gainG", cal.gainG.toDouble())
            put("gainB", cal.gainB.toDouble())
            put("gammaR", cal.gammaR.toDouble())
            put("gammaG", cal.gammaG.toDouble())
            put("gammaB", cal.gammaB.toDouble())
            put("smoothingFactor", cal.smoothingFactor.toDouble())
        }

        private fun deserializeCalibration(
            obj: JSONObject,
            fallback: ColorCalibration = ColorCalibration()
        ): ColorCalibration {
            return ColorCalibration(
                saturation = obj.optDouble("saturation", fallback.saturation.toDouble()).toFloat(),
                contrast = obj.optDouble("contrast", fallback.contrast.toDouble()).toFloat(),
                maxBrightness = obj.optInt("maxBrightness", fallback.maxBrightness),
                blackThreshold = obj.optInt("blackThreshold", fallback.blackThreshold),
                gainR = obj.optDouble("gainR", fallback.gainR.toDouble()).toFloat(),
                gainG = obj.optDouble("gainG", fallback.gainG.toDouble()).toFloat(),
                gainB = obj.optDouble("gainB", fallback.gainB.toDouble()).toFloat(),
                gammaR = obj.optDouble("gammaR", fallback.gammaR.toDouble()).toFloat(),
                gammaG = obj.optDouble("gammaG", fallback.gammaG.toDouble()).toFloat(),
                gammaB = obj.optDouble("gammaB", fallback.gammaB.toDouble()).toFloat(),
                smoothingFactor = obj.optDouble("smoothingFactor", fallback.smoothingFactor.toDouble()).toFloat()
            )
        }

        private fun deserializeLegacyDeviceCalibration(
            obj: JSONObject,
            fallback: ColorCalibration
        ): ColorCalibration {
            return ColorCalibration(
                saturation = obj.optDouble("cal_sat", fallback.saturation.toDouble()).toFloat(),
                contrast = obj.optDouble("cal_con", fallback.contrast.toDouble()).toFloat(),
                maxBrightness = obj.optInt("cal_bri", fallback.maxBrightness),
                blackThreshold = obj.optInt("cal_blk", fallback.blackThreshold),
                gainR = obj.optDouble("cal_gr", fallback.gainR.toDouble()).toFloat(),
                gainG = obj.optDouble("cal_gg", fallback.gainG.toDouble()).toFloat(),
                gainB = obj.optDouble("cal_gb", fallback.gainB.toDouble()).toFloat(),
                gammaR = obj.optDouble("cal_gamr", fallback.gammaR.toDouble()).toFloat(),
                gammaG = obj.optDouble("cal_gamg", fallback.gammaG.toDouble()).toFloat(),
                gammaB = obj.optDouble("cal_gamb", fallback.gammaB.toDouble()).toFloat(),
                smoothingFactor = obj.optDouble("cal_sm", fallback.smoothingFactor.toDouble()).toFloat()
            )
        }

        private fun serializeDevices(devices: List<WledDevice>): JSONArray = JSONArray().apply {
            for (dev in devices) {
                put(serializeDevice(dev))
            }
        }

        private fun serializeDevice(dev: WledDevice): JSONObject = JSONObject().apply {
            put("id", dev.id)
            put("name", dev.name)
            put("ip", dev.ip)
            put("port", dev.port)
            put("protocol", dev.protocol.name)
            put("enabled", dev.enabled)
            put("type", dev.type.name)
            put("ledCount", dev.ledCount)

            put("colorOrder", dev.colorOrder)
            put("isRgbw", dev.isRgbw)
            put("rgbwMode", dev.rgbwMode)

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

            put("calibration", serializeCalibration(dev.calibration))
        }

        private fun deserializeDevices(
            array: JSONArray,
            fallbackCalibration: ColorCalibration,
            fallbackColorOrder: String = "RGB",
            fallbackIsRgbw: Boolean = false,
            fallbackRgbwMode: String = "AUTO_SUBTRACT"
        ): List<WledDevice> {
            val list = mutableListOf<WledDevice>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                list.add(
                    deserializeDevice(
                        obj = obj,
                        fallbackCalibration = fallbackCalibration,
                        index = i,
                        fallbackColorOrder = fallbackColorOrder,
                        fallbackIsRgbw = fallbackIsRgbw,
                        fallbackRgbwMode = fallbackRgbwMode
                    )
                )
            }
            return list
        }

        private fun deserializeDevice(
            obj: JSONObject,
            fallbackCalibration: ColorCalibration,
            index: Int = 0,
            fallbackColorOrder: String = "RGB",
            fallbackIsRgbw: Boolean = false,
            fallbackRgbwMode: String = "AUTO_SUBTRACT"
        ): WledDevice {
            val id = obj.optString("id", UUID.randomUUID().toString())
            val name = obj.optString("name", "Light $index")
            val ip = obj.optString("ip", "192.168.1.66")
            val port = obj.optInt("port", 21324)
            val protocolStr = obj.optString(
                "protocol",
                if (port == 4048) StreamingProtocol.DDP.name else StreamingProtocol.DRGB.name
            )
            val protocol = try {
                StreamingProtocol.valueOf(protocolStr)
            } catch (_: Exception) {
                if (port == 4048) StreamingProtocol.DDP else StreamingProtocol.DRGB
            }
            val enabled = obj.optBoolean("enabled", true)
            val typeStr = obj.optString("type", DeviceType.PERIMETER.name)
            val type = try {
                DeviceType.valueOf(typeStr)
            } catch (_: Exception) {
                DeviceType.PERIMETER
            }
            val ledCount = obj.optInt("ledCount", 1)

            val colorOrder = obj.optString("colorOrder", fallbackColorOrder).ifBlank { fallbackColorOrder }
            val isRgbw = when {
                obj.has("isRgbw") -> obj.optBoolean("isRgbw", fallbackIsRgbw)
                obj.has("cal_rgbw") -> obj.optBoolean("cal_rgbw", fallbackIsRgbw)
                else -> fallbackIsRgbw
            }
            val rgbwMode = when {
                obj.has("rgbwMode") -> obj.optString("rgbwMode", fallbackRgbwMode).ifBlank { fallbackRgbwMode }
                obj.has("cal_rgbwm") -> obj.optString("cal_rgbwm", fallbackRgbwMode).ifBlank { fallbackRgbwMode }
                else -> fallbackRgbwMode
            }

            val perimeter = PerimeterConfig(
                topLeds = obj.optInt("topLeds", 54),
                rightLeds = obj.optInt("rightLeds", 30),
                bottomLeds = obj.optInt("bottomLeds", 54),
                leftLeds = obj.optInt("leftLeds", 30),
                startCorner = try {
                    Corner.valueOf(obj.optString("startCorner", Corner.BOTTOM_LEFT.name))
                } catch (_: Exception) {
                    Corner.BOTTOM_LEFT
                },
                direction = try {
                    Direction.valueOf(obj.optString("direction", Direction.CLOCKWISE.name))
                } catch (_: Exception) {
                    Direction.CLOCKWISE
                },
                topCrop = obj.optDouble("topCrop", 0.0).toFloat(),
                bottomCrop = obj.optDouble("bottomCrop", 0.0).toFloat(),
                leftCrop = obj.optDouble("leftCrop", 0.0).toFloat(),
                rightCrop = obj.optDouble("rightCrop", 0.0).toFloat(),
                depth = obj.optDouble("depth", 0.12).toFloat(),
                autoLetterbox = obj.optBoolean("autoLetterbox", true)
            )

            val customRect = NormalizedRect(
                left = obj.optDouble("rectL", 0.0).toFloat(),
                top = obj.optDouble("rectT", 0.0).toFloat(),
                right = obj.optDouble("rectR", 1.0).toFloat(),
                bottom = obj.optDouble("rectB", 1.0).toFloat()
            )

            val devCalObj = obj.optJSONObject("calibration")
            val devCalibration = when {
                devCalObj != null -> deserializeCalibration(devCalObj, fallbackCalibration)
                obj.has("cal_sat") -> deserializeLegacyDeviceCalibration(obj, fallbackCalibration)
                else -> fallbackCalibration
            }

            return WledDevice(
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
                customRect = customRect,
                colorOrder = colorOrder,
                isRgbw = isRgbw,
                rgbwMode = rgbwMode
            )
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
