package com.wled.tv.engine

import com.wled.tv.geometry.FixtureGeometryEngine
import com.wled.tv.model.ColorCalibration
import com.wled.tv.model.PresetProfile
import com.wled.tv.model.WledConfig
import com.wled.tv.model.WledDevice
import com.wled.tv.network.FakeWledControllerClient
import com.wled.tv.processing.ScreenColorProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class AmbientStreamingEngineFlickerReproTest {

    @Test
    fun repro1_darkCenterWithEdgeLighting_mustNotBeBlankedByBlackFrameCheck() {
        val fakeClient = FakeWledControllerClient()
        val dev = WledDevice(
            id = "tv_strip",
            name = "TV",
            ip = "192.168.1.10",
            port = 21324,
            enabled = true,
            ledCount = 10
        )
        val config = WledConfig(devices = listOf(dev))
        val engine = AmbientStreamingEngine(
            initialConfig = config,
            controllerClient = fakeClient
        )

        // 320x180 buffer: center 5x5 grid is completely black (0,0,0),
        // but the edge perimeter (e.g. x in [0..20]) has bright lighting (200, 150, 100)
        val width = 320
        val height = 180
        val rowStride = width * 4
        val pixelStride = 4
        val buffer = ByteBuffer.allocateDirect(rowStride * height)

        // Paint only the left margin (x in 0..15) with bright color
        for (y in 0 until height) {
            val rowOffset = y * rowStride
            for (x in 0..15) {
                val offset = rowOffset + x * pixelStride
                buffer.put(offset, 200.toByte())     // R
                buffer.put(offset + 1, 150.toByte()) // G
                buffer.put(offset + 2, 100.toByte()) // B
                buffer.put(offset + 3, 255.toByte()) // A
            }
        }

        val processed = engine.onFrameAvailable(
            buffer = buffer,
            width = width,
            height = height,
            rowStride = rowStride,
            pixelStride = pixelStride
        )
        assertTrue(processed)

        val rawBuf = engine.getRawBuffer("tv_strip")
        requireNotNull(rawBuf)

        // With edge lighting present, the raw buffer must NOT be zeroed out
        var hasNonZero = false
        for (b in rawBuf) {
            if ((b.toInt() and 0xFF) > 0) {
                hasNonZero = true
                break
            }
        }
        assertTrue(
            "Frame with edge lighting must not be falsely treated as completely black and wiped to 0",
            hasNonZero
        )
    }

    @Test
    fun repro2_oneFrameBlackCut_mustNotViolentlyFlickerUnderGentleSmoothing() {
        val processor = ScreenColorProcessor()
        val cinemaFactor = PresetProfile.CINEMA.calibration.smoothingFactor // 0.35f
        val output = ByteArray(3)

        // Frame 1: Scene A (warm bright interior: RGB 140, 110, 80)
        val sceneA = byteArrayOf(140.toByte(), 110.toByte(), 80.toByte())
        processor.applySmoothing("tv", sceneA, 1, cinemaFactor, output)
        val rAfterSceneA = output[0].toInt() and 0xFF
        assertEquals(140, rAfterSceneA)

        // Frame 2: 1-frame cut to black between scenes (RGB 0, 0, 0)
        val blackCut = byteArrayOf(0.toByte(), 0.toByte(), 0.toByte())
        processor.applySmoothing("tv", blackCut, 1, cinemaFactor, output)
        val rAfterBlackCut = output[0].toInt() and 0xFF

        // If alpha snapped to 0.90f, rAfterBlackCut plunges to 14 (a 90% plunge in 16ms = severe flicker!)
        // Under cinema smoothing (0.35f), output should smoothly decay (e.g. 140 * 0.65 = ~91)
        // rather than collapsing to near-zero in a single 16ms tick.
        assertTrue(
            "Single-frame cut to black under Cinema preset (0.35) must not collapse output to black ($rAfterBlackCut) causing flicker",
            rAfterBlackCut >= 60
        )
    }
}
