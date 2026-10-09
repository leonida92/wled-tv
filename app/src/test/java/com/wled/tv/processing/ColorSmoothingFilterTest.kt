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
}
