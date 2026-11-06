package com.wled.tv.service

import com.wled.tv.engine.AmbientStreamingEngine
import com.wled.tv.model.WledConfig
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
}
