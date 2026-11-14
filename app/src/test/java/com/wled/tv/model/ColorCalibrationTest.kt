package com.wled.tv.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ColorCalibrationTest {

    @Test
    fun defaultCaptureSettings_is320x180StandardAt60Fps() {
        val cap = CaptureSettings()
        assertEquals(60, cap.fps)
        assertEquals(320, cap.captureWidth)
        assertEquals(180, cap.captureHeight)
        assertEquals("320x180 (Std)", cap.resolutionLabel)
    }

    @Test
    fun ecoResolution_labelIsCorrect() {
        val cap = CaptureSettings(captureWidth = 160, captureHeight = 90)
        assertEquals(160, cap.captureWidth)
        assertEquals(90, cap.captureHeight)
        assertEquals("160x90 (Eco)", cap.resolutionLabel)
    }

    @Test
    fun highResolution_labelIsCorrect() {
        val cap = CaptureSettings(captureWidth = 480, captureHeight = 270)
        assertEquals(480, cap.captureWidth)
        assertEquals(270, cap.captureHeight)
        assertEquals("480x270 (High)", cap.resolutionLabel)
    }

    @Test
    fun ultraResolution_labelIsCorrect() {
        val cap = CaptureSettings(captureWidth = 80, captureHeight = 45)
        assertEquals(80, cap.captureWidth)
        assertEquals(45, cap.captureHeight)
        assertEquals("80x45 (Ultra)", cap.resolutionLabel)
    }

    @Test
    fun defaultColorCalibration_hasExpectedOpticalDefaults() {
        val cal = ColorCalibration()
        assertEquals(1.6f, cal.saturation, 0.001f)
        assertEquals(1.0f, cal.contrast, 0.001f)
        assertEquals(255, cal.maxBrightness)
        assertEquals(8, cal.blackThreshold)
        assertEquals(1.0f, cal.gainR, 0.001f)
        assertEquals(1.0f, cal.gainG, 0.001f)
        assertEquals(1.0f, cal.gainB, 0.001f)
        assertEquals(1.0f, cal.gammaR, 0.001f)
        assertEquals(1.0f, cal.gammaG, 0.001f)
        assertEquals(1.0f, cal.gammaB, 0.001f)
        assertEquals(0.40f, cal.smoothingFactor, 0.001f)
    }
}
