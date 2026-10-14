package com.wled.tv.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class WledHttpClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun checkConnection(ip: String): Boolean = withContext(Dispatchers.IO) {
        val cleanIp = ip.trim()
        if (cleanIp.isBlank()) return@withContext false
        try {
            val request = Request.Builder()
                .url("http://$cleanIp/json/state")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.d(TAG, "Connection check to $ip failed: ${e.message}")
            false
        }
    }

    suspend fun wakeAndSetBrightness(ip: String, brightness: Int = 255): Boolean = withContext(Dispatchers.IO) {
        if (ip.isBlank()) return@withContext false
        try {
            val json = JSONObject().apply {
                put("on", true)
                put("bri", brightness.coerceIn(1, 255))
            }
            val body = json.toString().toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url("http://${ip.trim()}/json/state")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to wake WLED at $ip: ${e.message}")
            false
        }
    }

    suspend fun turnOff(ip: String): Boolean = withContext(Dispatchers.IO) {
        if (ip.isBlank()) return@withContext false
        try {
            val json = JSONObject().apply {
                put("on", false)
            }
            val body = json.toString().toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url("http://${ip.trim()}/json/state")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to turn off WLED at $ip: ${e.message}")
            false
        }
    }

    suspend fun getInfo(ip: String): JSONObject? = withContext(Dispatchers.IO) {
        val cleanIp = ip.trim()
        if (cleanIp.isBlank()) return@withContext null
        try {
            val request = Request.Builder()
                .url("http://$cleanIp/json/info")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body != null) JSONObject(body) else null
                } else null
            }
        } catch (e: Exception) {
            Log.d(TAG, "Failed to fetch WLED info from $ip: ${e.message}")
            null
        }
    }

    suspend fun fetchDeviceInfo(ip: String): WledDeviceInfo? = withContext(Dispatchers.IO) {
        val json = getInfo(ip) ?: return@withContext null
        try {
            val name = json.optString("name", "WLED Light")
            val ver = json.optString("ver", "0.14.0")
            val ledsObj = json.optJSONObject("leds")
            val count = ledsObj?.optInt("count", 0) ?: 0
            val rgbw = ledsObj?.optBoolean("rgbw", false) ?: false
            val maxPwr = ledsObj?.optInt("maxpwr", 0) ?: 0
            WledDeviceInfo(name = name, version = ver, ledCount = count, isRgbw = rgbw, maxPowerMa = maxPwr)
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing WLED device info: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "WledHttpClient"
    }
}

data class WledDeviceInfo(
    val name: String,
    val version: String,
    val ledCount: Int,
    val isRgbw: Boolean,
    val maxPowerMa: Int
)

object DeviceReachabilityCache {
    val map = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
}

