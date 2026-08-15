package com.wled.tv.processing

class ColorSmoothingFilter {

    private var smoothedRgb = FloatArray(0)

    fun reset() {
        smoothedRgb = FloatArray(0)
    }

    /**
     * Applies Exponential Moving Average (EMA) smoothing to RGB LED values.
     *
     * @param rawRgb Incoming target colors [R0, G0, B0, ...]
     * @param ledCount Number of LEDs
     * @param factor Smoothing factor alpha in [0.1..1.0] (1.0 = instant, 0.1 = heavy smooth)
     * @param output Destination ByteArray
     */
    fun apply(
        rawRgb: ByteArray,
        ledCount: Int,
        factor: Float,
        output: ByteArray
    ) {
        val totalBytes = ledCount * 3
        if (smoothedRgb.size != totalBytes) {
            smoothedRgb = FloatArray(totalBytes)
            for (i in 0 until totalBytes) {
                smoothedRgb[i] = (rawRgb[i].toInt() and 0xFF).toFloat()
            }
        }

        val alpha = factor.coerceIn(0.05f, 1.0f)
        val oneMinusAlpha = 1.0f - alpha

        for (i in 0 until totalBytes) {
            val target = (rawRgb[i].toInt() and 0xFF).toFloat()
            smoothedRgb[i] = (smoothedRgb[i] * oneMinusAlpha) + (target * alpha)
            output[i] = (smoothedRgb[i] + 0.5f).toInt().coerceIn(0, 255).toByte()
        }
    }
}
