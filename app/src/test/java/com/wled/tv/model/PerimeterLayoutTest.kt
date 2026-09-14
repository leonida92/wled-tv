package com.wled.tv.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PerimeterLayoutTest {

    @Test
    fun defaultDepth_isTwelvePercent() {
        val config = PerimeterConfig()
        assertEquals(0.12f, config.depth, 0.001f)
        assertEquals(0.12f, config.getEffectiveDepth(), 0.001f)
    }

    @Test
    fun getEffectiveDepth_clampsProperly() {
        val configMin = PerimeterConfig(depth = 0.01f)
        assertEquals(0.03f, configMin.getEffectiveDepth(), 0.001f)

        val configMax = PerimeterConfig(depth = 0.85f)
        assertEquals(0.30f, configMax.getEffectiveDepth(), 0.001f)

        val configCustom = PerimeterConfig(depth = 0.22f)
        assertEquals(0.22f, configCustom.getEffectiveDepth(), 0.001f)
    }

    @Test
    fun copyWithNewDepth_preservesOtherProperties() {
        val config = PerimeterConfig(topLeds = 60, depth = 0.15f)
        val updated = config.copy(depth = 0.20f)
        assertEquals(60, updated.topLeds)
        assertEquals(0.20f, updated.depth, 0.001f)
    }
}

