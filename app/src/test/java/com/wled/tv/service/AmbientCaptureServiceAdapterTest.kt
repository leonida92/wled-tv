package com.wled.tv.service

import com.wled.tv.engine.AmbientStreamingEngine
import com.wled.tv.engine.TestClock
import com.wled.tv.model.ColorCalibration
import com.wled.tv.model.WledConfig
import com.wled.tv.model.WledDevice
import com.wled.tv.network.DefaultWledControllerClient
import com.wled.tv.network.FakeWledControllerClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
        val fakeClient = FakeWledControllerClient()
        val engine = AmbientStreamingEngine(initialConfig = WledConfig(), controllerClient = fakeClient)
        try {
            AmbientCaptureService.setActiveEngineForTesting(engine)
            assertEquals(engine, AmbientCaptureService.activeEngine.value)

            // Diagnostic override sessions are acquired directly on the active engine instance
            assertFalse(engine.isDiagnosticActive)
            val session = AmbientCaptureService.activeEngine.value?.acquireDiagnosticOverride()
            assertTrue(AmbientCaptureService.activeEngine.value?.isDiagnosticActive == true)

            session?.close()
            assertFalse(AmbientCaptureService.activeEngine.value?.isDiagnosticActive == true)

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
    fun isRunning_stateFlowTracksServiceLifecycle() {
        assertFalse(AmbientCaptureService.isRunning.value)
        AmbientCaptureService.setRunningForTesting(true)
        assertTrue(AmbientCaptureService.isRunning.value)
        AmbientCaptureService.setRunningForTesting(false)
        assertFalse(AmbientCaptureService.isRunning.value)
    }

    @Test
    fun shouldStartCapture_validatesResultCodeAndIntent() {
        val service = AmbientCaptureService()
        AmbientCaptureService.setRunningForTesting(true)
        try {
            val dummyIntent = android.content.Intent()
            assertTrue(
                "Service must start capture when captureSession is inactive",
                service.shouldStartCapture(resultCode = -1, resultData = dummyIntent)
            )
            assertFalse(
                "Service must reject start if resultCode is 0",
                service.shouldStartCapture(resultCode = 0, resultData = dummyIntent)
            )
            assertFalse(
                "Service must reject start if resultData is null",
                service.shouldStartCapture(resultCode = -1, resultData = null)
            )
        } finally {
            AmbientCaptureService.setRunningForTesting(false)
        }
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

        // Per-device calibration and colorOrder change: should NOT trigger fleet parameter change
        val devCalibChange = baseConfig.copy(
            devices = listOf(dev1.copy(colorOrder = "GRB", calibration = dev1.calibration.copy(gainR = 1.8f)))
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

    @Test
    fun wakeFixtures_withEngineActive_delegatesToEngineControllerClient() = runBlocking {
        val service = AmbientCaptureService()
        val fakeClient = FakeWledControllerClient()
        val dev = WledDevice(id = "dev1", name = "TV", ip = "192.168.1.10", port = 21324, enabled = true)
        val config = WledConfig(devices = listOf(dev))
        val engine = AmbientStreamingEngine(initialConfig = config, controllerClient = fakeClient)
        service.engine = engine

        service.wakeFixtures()
        var count = 0
        while (fakeClient.powerState["192.168.1.10"] != true && count++ < 50) {
            delay(20)
        }
        assertEquals(true, fakeClient.powerState["192.168.1.10"])
    }

    @Test
    fun wakeFixtures_withoutEngine_delegatesToFallbackControllerClient() = runBlocking {
        val service = AmbientCaptureService()
        val fakeClient = FakeWledControllerClient()
        service.fallbackControllerClient = fakeClient
        val dev = WledDevice(id = "dev2", name = "Bar", ip = "192.168.1.20", port = 21324, enabled = true)
        service.config = WledConfig(devices = listOf(dev))
        service.engine = null

        service.wakeFixtures()
        var count = 0
        while (fakeClient.powerState["192.168.1.20"] != true && count++ < 50) {
            delay(20)
        }
        assertEquals(true, fakeClient.powerState["192.168.1.20"])
    }

    @Test
    fun fallbackControllerClient_blackoutFleet_zeroesFramesAndTurnsOff() = runBlocking {
        val service = AmbientCaptureService()
        val fakeClient = FakeWledControllerClient()
        service.fallbackControllerClient = fakeClient
        val dev = WledDevice(id = "dev1", name = "TV", ip = "192.168.1.10", port = 21324, enabled = true, ledCount = 10)
        service.config = WledConfig(devices = listOf(dev))
        service.engine = null

        service.fallbackControllerClient.blackoutFleet(service.config.enabledDevices, sendPowerOff = true)
        assertEquals(1, fakeClient.blackoutCalls)
        assertEquals(3, fakeClient.sentFrames.size)
        assertEquals(false, fakeClient.powerState["192.168.1.10"])
    }

    @Test
    fun defaultWledControllerClient_testSeam_allowsInjectingFakeClient() {
        val fakeClient = FakeWledControllerClient()
        DefaultWledControllerClient.setInstanceForTesting(fakeClient)
        try {
            val resolved = DefaultWledControllerClient.getInstance()
            assertSame(fakeClient, resolved)
        } finally {
            DefaultWledControllerClient.setInstanceForTesting(null)
        }
    }

    @Test
    fun isAudioActive_whenAudioManagerNull_returnsFalse() {
        val service = AmbientCaptureService()
        service.audioManager = null
        assertFalse(service.isAudioActive())
    }

    @Test
    fun displayPowerTransitions_areIdempotentOnServiceAdapter() {
        val service = AmbientCaptureService()
        assertFalse(service.isScreenOffState)

        // Screen OFF transition
        assertTrue(service.onScreenOffDetected())
        assertTrue(service.isScreenOffState)

        // Idempotent: calling onScreenOffDetected again does not re-trigger
        assertFalse(service.onScreenOffDetected())
        assertTrue(service.isScreenOffState)

        // Screen ON transition
        assertTrue(service.onScreenOnDetected())
        assertFalse(service.isScreenOffState)

        // Idempotent: calling onScreenOnDetected again does not re-trigger
        assertFalse(service.onScreenOnDetected())
        assertFalse(service.isScreenOffState)
    }

    @Test
    fun overlaySnapshot_preservesDeviceTypeWithoutNameHeuristic() {
        val fakeClient = FakeWledControllerClient()
        val perimeter = WledDevice(
            id = "p1",
            name = "Living Room Accent", // Intentionally lacks "Perimeter"/"Backlight"
            type = com.wled.tv.model.DeviceType.PERIMETER
        )
        val lamp = WledDevice(
            id = "l1",
            name = "Backlight Perimeter", // Intentionally has misleading name
            type = com.wled.tv.model.DeviceType.LEFT_AMBIENT,
            ledCount = 12
        )
        val engine = AmbientStreamingEngine(
            initialConfig = WledConfig(devices = listOf(perimeter, lamp)),
            controllerClient = fakeClient
        )
        val frame = java.nio.ByteBuffer.allocateDirect(320 * 180 * 4)
        engine.onFrameAvailable(frame, 320, 180, 320 * 4, 4)
        engine.tickStreaming()

        val snapshot = engine.getOverlaySnapshot()
        assertEquals(2, snapshot.devices.size)
        val pSnap = snapshot.devices.first { it.deviceId == "p1" }
        val lSnap = snapshot.devices.first { it.deviceId == "l1" }
        assertEquals(com.wled.tv.model.DeviceType.PERIMETER, pSnap.deviceType)
        assertEquals(com.wled.tv.model.DeviceType.LEFT_AMBIENT, lSnap.deviceType)
    }

    @Test
    fun samplingOverlayManager_initialStateAndVisibilityReporting() {
        val service = AmbientCaptureService()
        var visibilityReported = false
        val manager = SamplingOverlayManager(
            context = service,
            activeEngineFlow = AmbientCaptureService.activeEngine,
            onVisibilityChanged = { visibilityReported = it }
        )
        assertFalse(manager.isOverlayVisible)
        assertFalse(visibilityReported)
        manager.release()
        assertFalse(manager.isOverlayVisible)
    }

    @Test
    fun drmBlackoutDetection_withAudioActive_transitionsState() {
        val clock = TestClock(currentTime = 1000L, uptime = 1000L)
        var audioPlaying = false
        val fakeClient = FakeWledControllerClient()
        val engine = AmbientStreamingEngine(
            initialConfig = WledConfig(),
            controllerClient = fakeClient,
            clock = clock,
            isAudioActive = { audioPlaying }
        )
        assertFalse(engine.isDrmActive)

        // Audio playing during all-black frames indicates Widevine DRM protected playback
        audioPlaying = true
        val blackFrame = java.nio.ByteBuffer.allocateDirect(16 * 16 * 4)
        engine.onFrameAvailable(blackFrame, 16, 16, 16 * 4, 4)
        assertFalse("DRM should not trigger before 5 full seconds", engine.isDrmActive)

        // Advance past 5 seconds of black frames with active audio
        clock.advance(5100L)
        blackFrame.rewind()
        engine.onFrameAvailable(blackFrame, 16, 16, 16 * 4, 4)
        assertTrue("DRM blackout should trigger after 5 seconds of black frames with active audio", engine.isDrmActive)

        // If audio ceases and at least 500ms elapse (audio polling interval), DRM state clears
        audioPlaying = false
        clock.advance(600L)
        blackFrame.rewind()
        engine.onFrameAvailable(blackFrame, 16, 16, 16 * 4, 4)
        assertFalse("DRM should clear when audio is no longer active", engine.isDrmActive)
    }
}
