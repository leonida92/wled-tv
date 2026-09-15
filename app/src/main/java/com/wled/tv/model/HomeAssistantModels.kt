package com.wled.tv.model

import android.graphics.RectF

enum class LightCapability {
    COLOR_AND_BRIGHTNESS,
    BRIGHTNESS_ONLY,
    UNSUPPORTED;

    val label: String
        get() = when (this) {
            COLOR_AND_BRIGHTNESS -> "RGB + Dim"
            BRIGHTNESS_ONLY -> "Dimmer Only"
            UNSUPPORTED -> "Unsupported"
        }

    companion object {
        fun fromSupportedModes(modes: List<String>): LightCapability {
            val lowerModes = modes.map { it.lowercase().trim() }
            val colorModes = setOf("rgb", "rgbw", "rgbww", "hs", "xy")
            if (lowerModes.any { it in colorModes }) {
                return COLOR_AND_BRIGHTNESS
            }
            val dimModes = setOf("brightness", "color_temp", "white")
            if (lowerModes.any { it in dimModes }) {
                return BRIGHTNESS_ONLY
            }
            return UNSUPPORTED
        }
    }
}

enum class HomeAssistantZoneType {
    FULL_SCREEN_AVERAGE,
    LEFT_AMBIENT,
    RIGHT_AMBIENT,
    TOP_AMBIENT,
    BOTTOM_AMBIENT,
    CUSTOM_RECT;

    val displayName: String
        get() = when (this) {
            FULL_SCREEN_AVERAGE -> "Full Screen (Average)"
            LEFT_AMBIENT -> "Left Side"
            RIGHT_AMBIENT -> "Right Side"
            TOP_AMBIENT -> "Top Side"
            BOTTOM_AMBIENT -> "Bottom Side"
            CUSTOM_RECT -> "Custom Bounding Box"
        }
}

data class HomeAssistantLight(
    val entityId: String,
    val name: String,
    val capability: LightCapability = LightCapability.COLOR_AND_BRIGHTNESS,
    val enabled: Boolean = false,
    val zoneType: HomeAssistantZoneType = HomeAssistantZoneType.FULL_SCREEN_AVERAGE,
    val customRect: RectF = RectF(0f, 0f, 1f, 1f),
    val maxBrightness: Int = 255
)

data class HomeAssistantConfig(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = 8123,
    val useSsl: Boolean = false,
    val token: String = "",
    val updateIntervalMs: Long = 300L,
    val changeThreshold: Int = 12,
    val transitionSeconds: Float = 0.3f,
    val darkCutoffEnabled: Boolean = true,
    val darkThreshold: Int = 10,
    val turnOffOnStop: Boolean = true,
    val subscribeAutomations: Boolean = true,
    val lights: List<HomeAssistantLight> = emptyList()
) {
    val cleanHost: String
        get() = host.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .removePrefix("ws://")
            .removePrefix("wss://")
            .trimEnd('/')

    val wsUrl: String
        get() {
            val scheme = if (useSsl) "wss" else "ws"
            return "$scheme://$cleanHost:$port/api/websocket"
        }

    val httpUrl: String
        get() {
            val scheme = if (useSsl) "https" else "http"
            return "$scheme://$cleanHost:$port"
        }

    val isConfigured: Boolean
        get() = cleanHost.isNotBlank() && token.isNotBlank()

    val enabledLights: List<HomeAssistantLight>
        get() = lights.filter { it.enabled && it.capability != LightCapability.UNSUPPORTED }
}
