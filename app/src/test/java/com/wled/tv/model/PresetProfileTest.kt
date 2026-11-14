package com.wled.tv.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetProfileTest {

    @Test
    fun builtInPresets_containAllExpectedProfiles() {
        val builtIns = PresetProfile.BUILT_INS
        assertEquals(6, builtIns.size)

        val ids = builtIns.map { it.id }.toSet()
        assertEquals(setOf("default", "cinema", "gaming", "vivid", "eye_care", "custom"), ids)
    }

    @Test
    fun builtInPresets_haveExpectedCalibrationValues() {
        // DEFAULT: standard baseline, default ColorCalibration()
        val defaultProfile = PresetProfile.DEFAULT
        assertEquals("default", defaultProfile.id)
        assertEquals("Standard", defaultProfile.name)
        assertEquals(1.6f, defaultProfile.calibration.saturation, 0.001f)
        assertEquals(1.0f, defaultProfile.calibration.contrast, 0.001f)
        assertEquals(0.40f, defaultProfile.calibration.smoothingFactor, 0.001f)
        assertEquals(8, defaultProfile.calibration.blackThreshold)
        assertEquals(255, defaultProfile.calibration.maxBrightness)

        // CINEMA: saturation 1.4f, contrast 1.05f, smoothingFactor 0.35f, blackThreshold 12
        val cinema = PresetProfile.CINEMA
        assertEquals("cinema", cinema.id)
        assertEquals(1.4f, cinema.calibration.saturation, 0.001f)
        assertEquals(1.05f, cinema.calibration.contrast, 0.001f)
        assertEquals(0.35f, cinema.calibration.smoothingFactor, 0.001f)
        assertEquals(12, cinema.calibration.blackThreshold)

        // GAMING: saturation 1.8f, contrast 1.0f, smoothingFactor 0.75f, blackThreshold 6
        val gaming = PresetProfile.GAMING
        assertEquals("gaming", gaming.id)
        assertEquals(1.8f, gaming.calibration.saturation, 0.001f)
        assertEquals(1.0f, gaming.calibration.contrast, 0.001f)
        assertEquals(0.75f, gaming.calibration.smoothingFactor, 0.001f)
        assertEquals(6, gaming.calibration.blackThreshold)

        // VIVID: saturation 2.2f, contrast 1.15f, smoothingFactor 0.45f, blackThreshold 4
        val vivid = PresetProfile.VIVID
        assertEquals("vivid", vivid.id)
        assertEquals(2.2f, vivid.calibration.saturation, 0.001f)
        assertEquals(1.15f, vivid.calibration.contrast, 0.001f)
        assertEquals(0.45f, vivid.calibration.smoothingFactor, 0.001f)
        assertEquals(4, vivid.calibration.blackThreshold)

        // EYE_CARE: saturation 1.2f, contrast 0.9f, maxBrightness 180, smoothingFactor 0.25f, gainB 0.85f
        val eyeCare = PresetProfile.EYE_CARE
        assertEquals("eye_care", eyeCare.id)
        assertEquals(1.2f, eyeCare.calibration.saturation, 0.001f)
        assertEquals(0.9f, eyeCare.calibration.contrast, 0.001f)
        assertEquals(180, eyeCare.calibration.maxBrightness)
        assertEquals(0.25f, eyeCare.calibration.smoothingFactor, 0.001f)
        assertEquals(0.85f, eyeCare.calibration.gainB, 0.001f)

        // CUSTOM: id = "custom", name = "Custom"
        val custom = PresetProfile.CUSTOM
        assertEquals("custom", custom.id)
        assertEquals("Custom", custom.name)
    }

    @Test
    fun fromId_matchesCaseInsensitive() {
        assertEquals(PresetProfile.DEFAULT, PresetProfile.fromId("default"))
        assertEquals(PresetProfile.DEFAULT, PresetProfile.fromId("DEFAULT"))
        assertEquals(PresetProfile.CINEMA, PresetProfile.fromId("cinema"))
        assertEquals(PresetProfile.CINEMA, PresetProfile.fromId("Cinema"))
        assertEquals(PresetProfile.GAMING, PresetProfile.fromId("gaming"))
        assertEquals(PresetProfile.VIVID, PresetProfile.fromId("vivid"))
        assertEquals(PresetProfile.EYE_CARE, PresetProfile.fromId("eye_care"))
        assertEquals(PresetProfile.CUSTOM, PresetProfile.fromId("custom"))
    }

    @Test
    fun fromId_unknownId_fallsBackToDefault() {
        assertEquals(PresetProfile.DEFAULT, PresetProfile.fromId("unknown_profile"))
        assertEquals(PresetProfile.DEFAULT, PresetProfile.fromId(""))
    }
}
