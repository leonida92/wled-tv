package com.wled.tv.network

import android.util.Log
import com.wled.tv.model.HomeAssistantConfig
import com.wled.tv.model.HomeAssistantLight
import com.wled.tv.model.LightCapability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class HomeAssistantWebSocketClient {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val messageId = AtomicInteger(1)
    private val isConnecting = AtomicBoolean(false)
    private val isAuthenticated = AtomicBoolean(false)
    private var currentConfig: HomeAssistantConfig? = null
    private var eventListener: ((String) -> Unit)? = null
    private val clientScope = CoroutineScope(Dispatchers.IO)
    private var reconnectJob: Job? = null
    private var shouldReconnect = false

    fun connect(config: HomeAssistantConfig, onEventAction: ((String) -> Unit)? = null) {
        if (!config.enabled || !config.isConfigured) {
            disconnect()
            return
        }

        currentConfig = config
        eventListener = onEventAction
        shouldReconnect = true

        if (webSocket != null && (isAuthenticated.get() || isConnecting.get())) {
            return
        }

        startWebSocketConnection()
    }

    private fun startWebSocketConnection() {
        val config = currentConfig ?: return
        if (isConnecting.getAndSet(true)) return

        try {
            val request = Request.Builder()
                .url(config.wsUrl)
                .build()

            webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.i(TAG, "WebSocket connected to ${config.wsUrl}")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleIncomingMessage(webSocket, text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "WebSocket closing: $code / $reason")
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "WebSocket closed: $code / $reason")
                    handleDisconnection()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "WebSocket error: ${t.message}")
                    handleDisconnection()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start WebSocket", e)
            handleDisconnection()
        }
    }

    private fun handleIncomingMessage(ws: WebSocket, text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type")

            when (type) {
                "auth_required" -> {
                    val token = currentConfig?.token ?: ""
                    val authMsg = JSONObject().apply {
                        put("type", "auth")
                        put("access_token", token)
                    }
                    ws.send(authMsg.toString())
                }
                "auth_ok" -> {
                    isAuthenticated.set(true)
                    isConnecting.set(false)
                    Log.i(TAG, "Home Assistant authenticated successfully")
                    subscribeToAutomations(ws)
                }
                "auth_invalid" -> {
                    isAuthenticated.set(false)
                    isConnecting.set(false)
                    shouldReconnect = false
                    Log.e(TAG, "Home Assistant authentication failed: ${json.optString("message")}")
                }
                "event" -> {
                    handleAutomationEvent(json)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message", e)
        }
    }

    private fun subscribeToAutomations(ws: WebSocket) {
        val config = currentConfig ?: return
        if (!config.subscribeAutomations) return

        try {
            val subMsg = JSONObject().apply {
                put("id", messageId.getAndIncrement())
                put("type", "subscribe_events")
                put("event_type", "wled_tv_control")
            }
            ws.send(subMsg.toString())
            Log.i(TAG, "Subscribed to wled_tv_control events")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to subscribe to events", e)
        }
    }

    private fun handleAutomationEvent(json: JSONObject) {
        val event = json.optJSONObject("event") ?: return
        val data = event.optJSONObject("data") ?: return
        val action = data.optString("action").lowercase().trim()
        if (action.isNotBlank()) {
            Log.i(TAG, "Received Home Assistant automation command: $action")
            eventListener?.invoke(action)
        }
    }

    private fun handleDisconnection() {
        isAuthenticated.set(false)
        isConnecting.set(false)
        webSocket = null

        if (shouldReconnect) {
            reconnectJob?.cancel()
            reconnectJob = clientScope.launch {
                delay(3000L)
                if (shouldReconnect) {
                    startWebSocketConnection()
                }
            }
        }
    }

    fun sendLightUpdate(
        entityIds: List<String>,
        rgb: IntArray?,
        brightness: Int,
        transitionSeconds: Float
    ) {
        val ws = webSocket ?: return
        if (!isAuthenticated.get() || entityIds.isEmpty()) return

        try {
            val serviceData = JSONObject().apply {
                put("entity_id", JSONArray(entityIds))
                if (rgb != null && rgb.size >= 3) {
                    val rgbArray = JSONArray().apply {
                        put(rgb[0].coerceIn(0, 255))
                        put(rgb[1].coerceIn(0, 255))
                        put(rgb[2].coerceIn(0, 255))
                    }
                    put("rgb_color", rgbArray)
                    if (transitionSeconds > 0f) {
                        put("transition", transitionSeconds.toDouble())
                    }
                }
                put("brightness", brightness.coerceIn(1, 255))
            }

            val msg = JSONObject().apply {
                put("id", messageId.getAndIncrement())
                put("type", "call_service")
                put("domain", "light")
                put("service", "turn_on")
                put("service_data", serviceData)
            }

            ws.send(msg.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error sending light update", e)
        }
    }

    fun sendTurnOff(entityIds: List<String>, transitionSeconds: Float = 0.3f) {
        val ws = webSocket ?: return
        if (!isAuthenticated.get() || entityIds.isEmpty()) return

        try {
            val serviceData = JSONObject().apply {
                put("entity_id", JSONArray(entityIds))
                if (transitionSeconds > 0f) {
                    put("transition", transitionSeconds.toDouble())
                }
            }

            val msg = JSONObject().apply {
                put("id", messageId.getAndIncrement())
                put("type", "call_service")
                put("domain", "light")
                put("service", "turn_off")
                put("service_data", serviceData)
            }

            ws.send(msg.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error sending turn_off", e)
        }
    }

    suspend fun testConnection(config: HomeAssistantConfig): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${config.httpUrl}/api/")
                .addHeader("Authorization", "Bearer ${config.token}")
                .build()

            val response = httpClient.newCall(request).execute()
            response.isSuccessful
        } catch (_: Exception) {
            false
        }
    }

    suspend fun fetchLights(config: HomeAssistantConfig): List<HomeAssistantLight> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${config.httpUrl}/api/states")
            .addHeader("Authorization", "Bearer ${config.token}")
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IOException("Home Assistant returned HTTP ${response.code}")
        }

        val jsonStr = response.body?.string() ?: throw IOException("Empty response")
        val array = JSONArray(jsonStr)
        val result = mutableListOf<HomeAssistantLight>()

        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val entityId = item.optString("entity_id")
            if (!entityId.startsWith("light.")) continue

            val attrs = item.optJSONObject("attributes") ?: JSONObject()
            val friendlyName = attrs.optString("friendly_name").takeIf { it.isNotBlank() }
                ?: entityId.removePrefix("light.")

            val colorModes = mutableListOf<String>()
            val modesArray = attrs.optJSONArray("supported_color_modes")
            if (modesArray != null) {
                for (m in 0 until modesArray.length()) {
                    colorModes.add(modesArray.optString(m))
                }
            }

            val supportedFeatures = attrs.optInt("supported_features", 0)
            val hasColorMode = colorModes.any { it.lowercase().trim() in setOf("rgb", "rgbw", "rgbww", "hs", "xy") }

            var capability = LightCapability.fromSupportedModes(colorModes)

            // If a light reports brightness only but has supported_features == 0 and no color modes,
            // it is a step-only or dummy fixture (like Xiaomi/Yeelight mono6) that cannot accept absolute brightness
            if (!hasColorMode && supportedFeatures == 0) {
                capability = LightCapability.UNSUPPORTED
            } else if (capability == LightCapability.UNSUPPORTED) {
                // In HA, SUPPORT_BRIGHTNESS = 1
                if ((supportedFeatures and 1) != 0 && !colorModes.contains("onoff")) {
                    capability = LightCapability.BRIGHTNESS_ONLY
                }
            }

            // Exclude unsupported fixtures (e.g. on/off relays or step-only dimmers without brightness)
            if (capability != LightCapability.UNSUPPORTED) {
                result.add(
                    HomeAssistantLight(
                        entityId = entityId,
                        name = friendlyName,
                        capability = capability,
                        enabled = true
                    )
                )
            }
        }

        result.sortedBy { it.name.lowercase() }
    }

    fun disconnect() {
        shouldReconnect = false
        reconnectJob?.cancel()
        reconnectJob = null
        try {
            webSocket?.close(1000, "Client stopped")
        } catch (_: Exception) {}
        webSocket = null
        isAuthenticated.set(false)
        isConnecting.set(false)
    }

    companion object {
        private const val TAG = "HAWebSocketClient"
    }
}
