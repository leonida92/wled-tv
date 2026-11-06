package com.wled.tv.service

import com.wled.tv.engine.AmbientStreamingEngine
import com.wled.tv.model.ThroughputHistoryPoint
import com.wled.tv.model.ThroughputStats
import com.wled.tv.model.WledConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientCaptureServiceAdapterTest {

    @Test
    fun companionDelegators_whenEngineNull_handleDefaultsGracefully() {
        AmbientCaptureService.setActiveEngineForTesting(null)

        assertNull(AmbientCaptureService.activeEngine.value)
        assertNull(AmbientCaptureService.latestStats)
        assertFalse(AmbientCaptureService.isDrmActive)
        assertTrue(AmbientCaptureService.getHistorySnapshot().isEmpty())

        AmbientCaptureService.isTestingOverride = true
        assertTrue(AmbientCaptureService.isTestingOverride)
        AmbientCaptureService.isTestingOverride = false
        assertFalse(AmbientCaptureService.isTestingOverride)
    }

    @Test
    fun companionDelegators_whenEngineActive_forwardToEngine() {
        val engine = AmbientStreamingEngine(initialConfig = WledConfig())
        try {
            AmbientCaptureService.setActiveEngineForTesting(engine)
            assertEquals(engine, AmbientCaptureService.activeEngine.value)

            // Testing override
            AmbientCaptureService.isTestingOverride = true
            assertTrue(engine.isTestingOverride)
            assertTrue(AmbientCaptureService.isTestingOverride)

            AmbientCaptureService.isTestingOverride = false
            assertFalse(engine.isTestingOverride)
            assertFalse(AmbientCaptureService.isTestingOverride)

            // History snapshot forwarding
            val now = System.currentTimeMillis()
            AmbientCaptureService.recordHistoryPoint(ThroughputHistoryPoint(timestamp = now, fps = 60, pps = 120))
            val snapshot = AmbientCaptureService.getHistorySnapshot()
            assertEquals(1, snapshot.size)
            assertEquals(60, snapshot[0].fps)

            AmbientCaptureService.clearHistory()
            assertTrue(AmbientCaptureService.getHistorySnapshot().isEmpty())

            // Listener forwarding
            var liveFrameCalled = false
            AmbientCaptureService.liveFrameListener = AmbientCaptureService.LiveFrameListener { _, _, _ ->
                liveFrameCalled = true
            }
            engine.liveFrameListener?.onDeviceFrameProcessed("dev1", byteArrayOf(0, 0, 0), 1)
            assertTrue(liveFrameCalled)

            var throughputCalled = false
            AmbientCaptureService.throughputListener = AmbientCaptureService.ThroughputListener { _ ->
                throughputCalled = true
            }
            engine.throughputListener?.onThroughputStatsUpdated(
                ThroughputStats(fps = 60, targetFps = 60, pps = 120, totalPackets = 120L, deviceStats = emptyList())
            )
            assertTrue(throughputCalled)

        } finally {
            AmbientCaptureService.setActiveEngineForTesting(null)
            AmbientCaptureService.liveFrameListener = null
            AmbientCaptureService.throughputListener = null
            AmbientCaptureService.isTestingOverride = false
        }
    }
}
