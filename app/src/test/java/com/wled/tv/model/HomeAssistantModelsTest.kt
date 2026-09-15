package com.wled.tv.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeAssistantModelsTest {

    @Test
    fun lightCapability_colorModes_mapsToColorAndBrightness() {
        assertEquals(LightCapability.COLOR_AND_BRIGHTNESS, LightCapability.fromSupportedModes(listOf("rgb")))
        assertEquals(LightCapability.COLOR_AND_BRIGHTNESS, LightCapability.fromSupportedModes(listOf("rgbw", "color_temp")))
        assertEquals(LightCapability.COLOR_AND_BRIGHTNESS, LightCapability.fromSupportedModes(listOf("hs")))
        assertEquals(LightCapability.COLOR_AND_BRIGHTNESS, LightCapability.fromSupportedModes(listOf("xy")))
    }

    @Test
    fun lightCapability_dimmerOnly_mapsToBrightnessOnly() {
        assertEquals(LightCapability.BRIGHTNESS_ONLY, LightCapability.fromSupportedModes(listOf("brightness")))
        assertEquals(LightCapability.BRIGHTNESS_ONLY, LightCapability.fromSupportedModes(listOf("color_temp")))
        assertEquals(LightCapability.BRIGHTNESS_ONLY, LightCapability.fromSupportedModes(listOf("white")))
    }

    @Test
    fun lightCapability_noDimOrColor_mapsToUnsupported() {
        assertEquals(LightCapability.UNSUPPORTED, LightCapability.fromSupportedModes(listOf("onoff")))
        assertEquals(LightCapability.UNSUPPORTED, LightCapability.fromSupportedModes(emptyList()))
    }

    @Test
    fun homeAssistantConfig_cleanHost_normalizesProperly() {
        val config1 = HomeAssistantConfig(host = "http://192.168.1.100:8123/")
        assertEquals("192.168.1.100:8123", config1.cleanHost)

        val config2 = HomeAssistantConfig(host = "https://homeassistant.local/")
        assertEquals("homeassistant.local", config2.cleanHost)

        val config3 = HomeAssistantConfig(host = "ws://192.168.1.50")
        assertEquals("192.168.1.50", config3.cleanHost)
    }

    @Test
    fun homeAssistantConfig_urls_generateCorrectSchemes() {
        val plain = HomeAssistantConfig(host = "192.168.1.100", port = 8123, useSsl = false)
        assertEquals("ws://192.168.1.100:8123/api/websocket", plain.wsUrl)
        assertEquals("http://192.168.1.100:8123", plain.httpUrl)

        val ssl = HomeAssistantConfig(host = "ha.mydomain.com", port = 443, useSsl = true)
        assertEquals("wss://ha.mydomain.com:443/api/websocket", ssl.wsUrl)
        assertEquals("https://ha.mydomain.com:443", ssl.httpUrl)
    }

    @Test
    fun homeAssistantConfig_isConfigured_validatesHostAndToken() {
        val empty = HomeAssistantConfig()
        assertFalse(empty.isConfigured)

        val noToken = HomeAssistantConfig(host = "192.168.1.100")
        assertFalse(noToken.isConfigured)

        val valid = HomeAssistantConfig(host = "192.168.1.100", token = "eyJhbGciOi...")
        assertTrue(valid.isConfigured)
    }

    @Test
    fun homeAssistantConfig_enabledLights_filtersProperly() {
        val l1 = HomeAssistantLight("light.rgb", "RGB Light", LightCapability.COLOR_AND_BRIGHTNESS, enabled = true)
        val l2 = HomeAssistantLight("light.dimmer", "Dimmer", LightCapability.BRIGHTNESS_ONLY, enabled = true)
        val l3 = HomeAssistantLight("light.off", "Disabled Light", LightCapability.COLOR_AND_BRIGHTNESS, enabled = false)
        val l4 = HomeAssistantLight("light.switch", "Relay Light", LightCapability.UNSUPPORTED, enabled = true)

        val config = HomeAssistantConfig(lights = listOf(l1, l2, l3, l4))
        val active = config.enabledLights

        assertEquals(2, active.size)
        assertTrue(active.contains(l1))
        assertTrue(active.contains(l2))
        assertFalse(active.contains(l3))
        assertFalse(active.contains(l4))
    }
}
