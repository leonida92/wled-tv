package com.wled.tv.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ColorCalibrationTest {

    @Test
    fun defaultResolution_is320x180Standard() {
        val cal = ColorCalibration()
        assertEquals(320, cal.captureWidth)
        assertEquals(180, cal.captureHeight)
        assertEquals("320x180 (Std)", cal.resolutionLabel)
    }

    @Test
    fun ecoResolution_labelIsCorrect() {
        val cal = ColorCalibration(captureWidth = 160, captureHeight = 90)
        assertEquals(160, cal.captureWidth)
        assertEquals(90, cal.captureHeight)
        assertEquals("160x90 (Eco)", cal.resolutionLabel)
    }

    @Test
    fun highResolution_labelIsCorrect() {
        val cal = ColorCalibration(captureWidth = 480, captureHeight = 270)
        assertEquals(480, cal.captureWidth)
        assertEquals(270, cal.captureHeight)
        assertEquals("480x270 (High)", cal.resolutionLabel)
    }
}
