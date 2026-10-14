package com.wled.tv.processing

import kotlin.math.sqrt

class ColorSmoothingFilter {

    private var smoothedRgb = FloatArray(0)

    fun reset() {
        smoothedRgb = FloatArray(0)
    }

    /**
     * Applies Adaptive Dual-Speed Exponential Moving Average (EMA) smoothing to RGB LED values.
     * Detects scene cuts and abrupt lighting changes (explosions, flashes, camera cuts)
     * and dynamically snaps alpha to instant response (0.90), while maintaining smooth,
     * jitter-free transitions during subtle panning or static scenes.
     *
     * @param rawRgb Incoming target colors [R0, G0, B0, ...]
     * @param ledCount Number of LEDs
     * @param factor Base smoothing factor alpha in [0.05..1.0] (1.0 = instant, 0.1 = heavy smooth)
     * @param output Destination ByteArray
     */
    fun apply(
        rawRgb: ByteArray,
        ledCount: Int,
        factor: Float,
        output: ByteArray
    ) {
        val totalBytes = ledCount * 3
        if (totalBytes <= 0) return

        if (smoothedRgb.size != totalBytes) {
            smoothedRgb = FloatArray(totalBytes)
            for (i in 0 until totalBytes) {
                smoothedRgb[i] = (rawRgb[i].toInt() and 0xFF).toFloat()
            }
        }

        // 1. Measure average Euclidean color distance per LED between target and smoothed state
        var sumSquaredDist = 0.0
        for (i in 0 until ledCount) {
            val idx = i * 3
            val rTarget = (rawRgb[idx].toInt() and 0xFF).toFloat()
            val gTarget = (rawRgb[idx + 1].toInt() and 0xFF).toFloat()
            val bTarget = (rawRgb[idx + 2].toInt() and 0xFF).toFloat()

            val rDiff = rTarget - smoothedRgb[idx]
            val gDiff = gTarget - smoothedRgb[idx + 1]
            val bDiff = bTarget - smoothedRgb[idx + 2]

            sumSquaredDist += (rDiff * rDiff + gDiff * gDiff + bDiff * bDiff)
        }

        val avgDistance = sqrt(sumSquaredDist / ledCount)

        // 2. Adaptive alpha selection:
        // Scene cut threshold: avgDistance >= 60 (on 0..441 Euclidean RGB scale)
        // If distance is large (scene cut / strobe / explosion), snap alpha to 0.90f
        // If distance is moderate (camera pan), interpolate smoothly towards base factor
        val baseAlpha = factor.coerceIn(0.05f, 1.0f)
        val alpha = when {
            baseAlpha >= 0.90f -> baseAlpha
            avgDistance >= 60.0 -> 0.90f
            avgDistance >= 30.0 -> {
                val t = ((avgDistance - 30.0) / 30.0).toFloat()
                baseAlpha + t * (0.90f - baseAlpha)
            }
            else -> baseAlpha
        }

        val oneMinusAlpha = 1.0f - alpha

        // 3. Apply smoothing step
        for (i in 0 until totalBytes) {
            val target = (rawRgb[i].toInt() and 0xFF).toFloat()
            smoothedRgb[i] = (smoothedRgb[i] * oneMinusAlpha) + (target * alpha)
            output[i] = (smoothedRgb[i] + 0.5f).toInt().coerceIn(0, 255).toByte()
        }
    }
}
