package com.wled.tv.service

import com.wled.tv.engine.AmbientStreamingEngine
import com.wled.tv.model.ColorCalibration
import com.wled.tv.model.WledConfig
import com.wled.tv.model.WledDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientCaptureServiceAdapterTest {

    @Test
    fun companionConstants_areDefinedCorrectly() {
        assertEquals("wled_ambient_capture_channel", AmbientCaptureService.CHANNEL_ID)
        assertEquals(1001, AmbientCaptureService.NOTIFICATION_ID)
        assertEquals("com.wled.tv.ACTION_STOP", AmbientCaptureService.ACTION_STOP)
        assertEquals("com.wled.tv.ACTION_RELOAD_CONFIG", AmbientCaptureService.ACTION_RELOAD_CONFIG)
        assertEquals("com.wled.tv.ACTION_TOGGLE_OVERLAY", AmbientCaptureService.ACTION_TOGGLE_OVERLAY)
        assertEquals("com.wled.tv.ACTION_SHOW_OVERLAY", AmbientCaptureService.ACTION_SHOW_OVERLAY)
        assertEquals("com.wled.tv.ACTION_HIDE_OVERLAY", AmbientCaptureService.ACTION_HIDE_OVERLAY)
        assertEquals("extra_result_code", AmbientCaptureService.EXTRA_RESULT_CODE)
        assertEquals("extra_result_data", AmbientCaptureService.EXTRA_RESULT_DATA)
    }

    @Test
    fun activeEngineFlow_whenEngineNull_defaultsToNull() {
        AmbientCaptureService.setActiveEngineForTesting(null)
        assertNull(AmbientCaptureService.activeEngine.value)
    }

    @Test
    fun activeEngineFlow_whenEngineActive_exposesInstanceAndCleanState() {
        val engine = AmbientStreamingEngine(initialConfig = WledConfig())
        try {
            AmbientCaptureService.setActiveEngineForTesting(engine)
            assertEquals(engine, AmbientCaptureService.activeEngine.value)

            // State changes are performed directly on the active engine instance
            assertFalse(engine.isTestingOverride)
            engine.isTestingOverride = true
            assertTrue(AmbientCaptureService.activeEngine.value?.isTestingOverride == true)

            engine.isTestingOverride = false
            assertFalse(AmbientCaptureService.activeEngine.value?.isTestingOverride == true)

            // Listeners are registered directly on the active engine instance
            var frameProcessed = false
            engine.liveFrameListener = AmbientStreamingEngine.LiveFrameListener { _, _, _ ->
                frameProcessed = true
            }
            AmbientCaptureService.activeEngine.value?.liveFrameListener?.onDeviceFrameProcessed(
                "dev1", byteArrayOf(0, 0, 0), 1
            )
            assertTrue(frameProcessed)

            // History methods are on the engine directly
            assertTrue(engine.getHistorySnapshot().isEmpty())
        } finally {
            AmbientCaptureService.setActiveEngineForTesting(null)
            assertNull(AmbientCaptureService.activeEngine.value)
        }
    }

    @Test
    fun isOverlayShowing_stateFlowTracksOverlayVisibility() {
        assertNotNull(AmbientCaptureService.isOverlayShowing)
        assertFalse(AmbientCaptureService.isOverlayShowing.value)
    }

    @Test
    fun isRunning_canBeObservedAndToggled() {
        AmbientCaptureService.isRunning = true
        assertTrue(AmbientCaptureService.isRunning)
        AmbientCaptureService.isRunning = false
        assertFalse(AmbientCaptureService.isRunning)
    }

    @Test
    fun fleetParametersChanged_detectsFleetChangesOnly() {
        val service = AmbientCaptureService()
        val dev1 = WledDevice(id = "dev1", name = "TV", ip = "192.168.1.10", port = 21324, enabled = true)
        val dev2 = WledDevice(id = "dev2", name = "Bar", ip = "192.168.1.11", port = 21324, enabled = true)
        val baseConfig = WledConfig(devices = listOf(dev1))

        // Calibration change (gamma/sliders): should NOT trigger fleet parameter change
        val calibChange = baseConfig.copy(
            calibration = baseConfig.calibration.copy(saturation = 2.0f, contrast = 1.2f, gainR = 1.5f)
        )
        assertFalse("Slider/calibration changes must not register as fleet changes", service.fleetParametersChanged(baseConfig, calibChange))

        // Per-device calibration change (colorOrder, gain): should NOT trigger fleet parameter change
        val devCalibChange = baseConfig.copy(
            devices = listOf(dev1.copy(calibration = dev1.calibration.copy(colorOrder = "GRB", gainR = 1.8f)))
        )
        assertFalse("Device calibration changes must not register as fleet changes", service.fleetParametersChanged(baseConfig, devCalibChange))

        // Device IP changed: should trigger
        val ipChange = baseConfig.copy(devices = listOf(dev1.copy(ip = "192.168.1.200")))
        assertTrue("IP change must register as fleet change", service.fleetParametersChanged(baseConfig, ipChange))

        // Device Port changed: should trigger
        val portChange = baseConfig.copy(devices = listOf(dev1.copy(port = 4048)))
        assertTrue("Port change must register as fleet change", service.fleetParametersChanged(baseConfig, portChange))

        // Device Enabled changed: should trigger
        val enabledChange = baseConfig.copy(devices = listOf(dev1.copy(enabled = false)))
        assertTrue("Enabled state change must register as fleet change", service.fleetParametersChanged(baseConfig, enabledChange))

        // Device Count changed: should trigger
        val countChange = baseConfig.copy(devices = listOf(dev1, dev2))
        assertTrue("Device count change must register as fleet change", service.fleetParametersChanged(baseConfig, countChange))
    }
}
