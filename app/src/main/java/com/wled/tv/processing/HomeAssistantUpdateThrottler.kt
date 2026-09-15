package com.wled.tv.processing

import android.util.Log
import com.wled.tv.model.HomeAssistantConfig
import com.wled.tv.model.LightCapability
import com.wled.tv.network.HomeAssistantWebSocketClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

class HomeAssistantUpdateThrottler(
    private val client: HomeAssistantWebSocketClient,
    private val scope: CoroutineScope
) {

    // Non-blocking conflated channel: Producer never blocks, newest frame always overwrites
    private val colorChannel = Channel<Map<String, IntArray>>(Channel.CONFLATED)
    private var workerJob: Job? = null

    private val lastSentRgb = HashMap<String, IntArray>()
    private val lastSentBri = HashMap<String, Int>()
    private val isLightOff = HashMap<String, Boolean>()
    private var lastSentTime = 0L

    fun start(config: HomeAssistantConfig) {
        stop()
        workerJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val colorMap = try {
                    colorChannel.receive()
                } catch (_: Exception) {
                    break
                }

                val now = System.currentTimeMillis()
                val elapsed = now - lastSentTime
                if (elapsed < config.updateIntervalMs) {
                    delay(config.updateIntervalMs - elapsed)
                }

                dispatchUpdates(colorMap, config)
                lastSentTime = System.currentTimeMillis()
            }
        }
    }

    /**
     * Non-blocking call from capture thread. Returns immediately (< 0.01 ms).
     * values in colorMap: IntArray of size 4 [R, G, B, Brightness]
     */
    fun postLightColors(colorMap: Map<String, IntArray>) {
        colorChannel.trySend(colorMap)
    }

    private fun dispatchUpdates(colorMap: Map<String, IntArray>, config: HomeAssistantConfig) {
        val lightsToTurnOff = mutableListOf<String>()
        val updatesColorAndBri = mutableListOf<Triple<String, IntArray, Int>>()
        val updatesBriOnly = mutableListOf<Pair<String, Int>>()

        val enabledLights = config.enabledLights
        for (light in enabledLights) {
            val data = colorMap[light.entityId] ?: continue
            if (data.size < 4) continue

            val r = data[0]
            val g = data[1]
            val b = data[2]
            val bri = (data[3] * (light.maxBrightness / 255f)).toInt().coerceIn(0, 255)
            val luma = max(r, max(g, b))

            val currentlyOff = isLightOff[light.entityId] ?: false

            if (config.darkCutoffEnabled && luma <= config.darkThreshold) {
                if (!currentlyOff) {
                    lightsToTurnOff.add(light.entityId)
                    isLightOff[light.entityId] = true
                    lastSentRgb.remove(light.entityId)
                    lastSentBri.remove(light.entityId)
                }
                continue
            }

            // Light is active
            if (currentlyOff) {
                isLightOff[light.entityId] = false
            }

            // Check delta threshold
            val lastRgb = lastSentRgb[light.entityId]
            val lastB = lastSentBri[light.entityId] ?: -1

            val rgbDelta = if (lastRgb != null) {
                max(abs(lastRgb[0] - r), max(abs(lastRgb[1] - g), abs(lastRgb[2] - b)))
            } else {
                Int.MAX_VALUE
            }
            val briDelta = abs(lastB - bri)

            if (rgbDelta < config.changeThreshold && briDelta < 6) {
                // Insignificant visual change, skip to save network bandwidth
                continue
            }

            lastSentRgb[light.entityId] = intArrayOf(r, g, b)
            lastSentBri[light.entityId] = bri

            when (light.capability) {
                LightCapability.COLOR_AND_BRIGHTNESS -> {
                    updatesColorAndBri.add(Triple(light.entityId, intArrayOf(r, g, b), bri))
                }
                LightCapability.BRIGHTNESS_ONLY -> {
                    updatesBriOnly.add(Pair(light.entityId, bri))
                }
                LightCapability.UNSUPPORTED -> {}
            }
        }

        if (lightsToTurnOff.isNotEmpty()) {
            client.sendTurnOff(lightsToTurnOff, config.transitionSeconds)
        }

        for ((entityId, rgb, bri) in updatesColorAndBri) {
            client.sendLightUpdate(
                entityIds = listOf(entityId),
                rgb = rgb,
                brightness = bri,
                transitionSeconds = config.transitionSeconds
            )
        }

        for ((entityId, bri) in updatesBriOnly) {
            client.sendLightUpdate(
                entityIds = listOf(entityId),
                rgb = null,
                brightness = bri,
                transitionSeconds = config.transitionSeconds
            )
        }
    }

    fun turnOffAll(config: HomeAssistantConfig) {
        val allEntities = config.enabledLights.map { it.entityId }
        if (allEntities.isNotEmpty()) {
            client.sendTurnOff(allEntities, 0.5f)
        }
        lastSentRgb.clear()
        lastSentBri.clear()
        isLightOff.clear()
    }

    fun stop() {
        workerJob?.cancel()
        workerJob = null
        lastSentRgb.clear()
        lastSentBri.clear()
        isLightOff.clear()
    }

    companion object {
        private const val TAG = "HAUpdateThrottler"
    }
}
