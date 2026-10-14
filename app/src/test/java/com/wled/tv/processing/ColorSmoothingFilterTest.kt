package com.wled.tv.processing

import org.junit.Assert.assertEquals
import org.junit.Test

class ColorSmoothingFilterTest {

    @Test
    fun apply_instantFactor_copiesValuesDirectly() {
        val filter = ColorSmoothingFilter()
        val raw = byteArrayOf(100.toByte(), 150.toByte(), 200.toByte())
        val output = ByteArray(3)

        // Factor 1.0 = instant transition
        filter.apply(raw, 1, 1.0f, output)

        assertEquals(100, output[0].toInt() and 0xFF)
        assertEquals(150, output[1].toInt() and 0xFF)
        assertEquals(200, output[2].toInt() and 0xFF)
    }

    @Test
    fun reset_clearsInternalState() {
        val filter = ColorSmoothingFilter()
        val raw = byteArrayOf(100.toByte(), 150.toByte(), 200.toByte())
        val output = ByteArray(3)

        filter.apply(raw, 1, 0.5f, output)
        filter.reset()

        val raw2 = byteArrayOf(50.toByte(), 50.toByte(), 50.toByte())
        filter.apply(raw2, 1, 1.0f, output)

        assertEquals(50, output[0].toInt() and 0xFF)
        assertEquals(50, output[1].toInt() and 0xFF)
        assertEquals(50, output[2].toInt() and 0xFF)
    }

    @Test
    fun apply_subtleChange_usesBaseSmoothing() {
        val filter = ColorSmoothingFilter()
        val initial = byteArrayOf(100.toByte(), 100.toByte(), 100.toByte())
        val output = ByteArray(3)
        // Frame 1 initializes state
        filter.apply(initial, 1, 0.2f, output)

        // Frame 2: Subtle delta (R changes from 100 to 110, dist = 10 < 30)
        val subtle = byteArrayOf(110.toByte(), 100.toByte(), 100.toByte())
        filter.apply(subtle, 1, 0.2f, output)

        // With baseAlpha = 0.2: 100 * 0.8 + 110 * 0.2 = 102
        assertEquals(102, output[0].toInt() and 0xFF)
        assertEquals(100, output[1].toInt() and 0xFF)
        assertEquals(100, output[2].toInt() and 0xFF)
    }

    @Test
    fun apply_sceneCut_snapsInstantlyWithHighAlpha() {
        val filter = ColorSmoothingFilter()
        val black = byteArrayOf(0.toByte(), 0.toByte(), 0.toByte())
        val output = ByteArray(3)
        // Frame 1 initializes state at black
        filter.apply(black, 1, 0.2f, output)

        // Frame 2: Sudden scene cut / explosion to bright white (255, 255, 255, dist = 441.6 >> 60)
        val white = byteArrayOf(255.toByte(), 255.toByte(), 255.toByte())
        filter.apply(white, 1, 0.2f, output)

        // Without adaptive EMA (baseAlpha=0.2), output would lag at 51 (20%).
        // With adaptive EMA, alpha snaps to 0.90: 0 * 0.10 + 255 * 0.90 = ~230 (90%)!
        val r = output[0].toInt() and 0xFF
        val g = output[1].toInt() and 0xFF
        val b = output[2].toInt() and 0xFF
        assertEquals(230, r)
        assertEquals(230, g)
        assertEquals(230, b)
    }
}
