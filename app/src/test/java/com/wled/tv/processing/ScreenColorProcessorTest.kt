package com.wled.tv.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenColorProcessorTest {

    @Test
    fun applySmoothing_tracksPerDeviceIndependently() {
        val processor = ScreenColorProcessor()

        val rawDev1 = byteArrayOf(100.toByte(), 100.toByte(), 100.toByte())
        val rawDev2 = byteArrayOf(200.toByte(), 200.toByte(), 200.toByte())

        val smoothDev1 = ByteArray(3)
        val smoothDev2 = ByteArray(3)

        // Initial tick initializes both devices independently
        processor.applySmoothing("dev1", rawDev1, 1, 0.2f, smoothDev1)
        processor.applySmoothing("dev2", rawDev2, 1, 0.2f, smoothDev2)

        assertEquals(100, smoothDev1[0].toInt() and 0xFF)
        assertEquals(200, smoothDev2[0].toInt() and 0xFF)

        // Subsequent ticks apply smoothing to each device without cross-contamination
        val newRawDev1 = byteArrayOf(110.toByte(), 100.toByte(), 100.toByte())
        processor.applySmoothing("dev1", newRawDev1, 1, 0.2f, smoothDev1)

        // dev1: 100 * 0.8 + 110 * 0.2 = 102
        assertEquals(102, smoothDev1[0].toInt() and 0xFF)
        // dev2 should remain untouched
        assertEquals(200, smoothDev2[0].toInt() and 0xFF)
    }

    @Test
    fun applySmoothing_convergesOverDecoupledTicks() {
        val processor = ScreenColorProcessor()
        val raw = byteArrayOf(0.toByte(), 0.toByte(), 0.toByte())
        val smooth = ByteArray(3)

        // Initialize at 0
        processor.applySmoothing("dev1", raw, 1, 0.2f, smooth)
        assertEquals(0, smooth[0].toInt() and 0xFF)

        // Raw color steps to 50 (subtle step, Euclidean distance = 50 < 60)
        val target = byteArrayOf(50.toByte(), 0.toByte(), 0.toByte())

        // Simulate 10 decoupled 60Hz ticks
        for (tick in 1..10) {
            processor.applySmoothing("dev1", target, 1, 0.2f, smooth)
        }

        // After 10 ticks with alpha ~0.2-0.5, it should have converged near 50
        val finalR = smooth[0].toInt() and 0xFF
        assertTrue("Expected finalR > 40 after 10 ticks, got $finalR", finalR >= 40)
    }

    @Test
    fun applySmoothing_sceneCutSnapsImmediately() {
        val processor = ScreenColorProcessor()
        val black = byteArrayOf(0.toByte(), 0.toByte(), 0.toByte())
        val smooth = ByteArray(3)

        processor.applySmoothing("dev1", black, 1, 0.2f, smooth)

        // Sudden explosion / scene cut to full white
        val white = byteArrayOf(255.toByte(), 255.toByte(), 255.toByte())
        processor.applySmoothing("dev1", white, 1, 0.2f, smooth)

        // High alpha (0.90) snaps on scene cut
        val r = smooth[0].toInt() and 0xFF
        assertEquals(230, r)
    }

    @Test
    fun reset_clearsAllSmoothingFilters() {
        val processor = ScreenColorProcessor()
        val initial = byteArrayOf(100.toByte(), 100.toByte(), 100.toByte())
        val smooth = ByteArray(3)

        processor.applySmoothing("dev1", initial, 1, 0.2f, smooth)
        processor.reset()

        val next = byteArrayOf(50.toByte(), 50.toByte(), 50.toByte())
        processor.applySmoothing("dev1", next, 1, 1.0f, smooth)

        assertEquals(50, smooth[0].toInt() and 0xFF)
    }

    @Test
    fun scanLetterbox_channelBugImmunity_ignoresCornerWatermark() {
        val processor = ScreenColorProcessor()
        val width = 320
        val height = 180
        val rowStride = width * 4
        val pixelStride = 4
        val buffer = java.nio.ByteBuffer.allocateDirect(rowStride * height)

        // Simulate 2.39:1 Cinemascope content (letterbox rows 0..23 and 156..179 are black).
        // Active video content is between row 24 and 155.
        for (y in 24..155) {
            for (x in 0 until width) {
                val offset = y * rowStride + x * pixelStride
                buffer.put(offset, 150.toByte())     // R
                buffer.put(offset + 1, 150.toByte()) // G
                buffer.put(offset + 2, 150.toByte()) // B
                buffer.put(offset + 3, 255.toByte()) // A
            }
        }

        // Add a bright static channel bug / network logo in top-left letterbox corner:
        // Rows 2..8, X between 38 and 46 (covers only 2 probes in left quadrant, 0 in right quadrant)
        for (y in 2..8) {
            for (x in 38..46) {
                val offset = y * rowStride + x * pixelStride
                buffer.put(offset, 240.toByte())
                buffer.put(offset + 1, 240.toByte())
                buffer.put(offset + 2, 240.toByte())
            }
        }

        // Feed 35 frames to satisfy 30-frame temporal hysteresis
        for (frame in 1..35) {
            processor.scanLetterbox(buffer, width, height, rowStride, pixelStride)
        }

        // The channel logo in row 2..8 should be rejected by quorum thresholding!
        // Top crop should be Cinemascope 0.130f, NOT 0.0f (which would happen if logo triggered 16:9).
        assertEquals(0.130f, processor.currentTopCrop, 0.005f)
        assertEquals(0.130f, processor.currentBottomCrop, 0.005f)
    }

    @Test
    fun scanLetterbox_detects43Pillarbox_andSnapsTo125Percent() {
        val processor = ScreenColorProcessor()
        val width = 320
        val height = 180
        val rowStride = width * 4
        val pixelStride = 4
        val buffer = java.nio.ByteBuffer.allocateDirect(rowStride * height)

        // 4:3 content on 16:9 screen (320x180):
        // 4:3 active picture is centered: width = 180 * (4/3) = 240 pixels.
        // Left pillarbox: columns 0..39 (40 pixels = 12.5% width).
        // Active picture: columns 40..279 (240 pixels).
        // Right pillarbox: columns 280..319 (40 pixels = 12.5% width).
        // Content touches top and bottom (rows 0..179).
        for (y in 0 until height) {
            for (x in 40..279) {
                val offset = y * rowStride + x * pixelStride
                buffer.put(offset, 180.toByte())
                buffer.put(offset + 1, 140.toByte())
                buffer.put(offset + 2, 100.toByte())
                buffer.put(offset + 3, 255.toByte())
            }
        }

        // Feed 35 frames to satisfy 30-frame hysteresis for shrinking
        for (frame in 1..35) {
            processor.scanLetterbox(buffer, width, height, rowStride, pixelStride)
        }

        // Horizontal pillarbox detected at 12.5%
        assertEquals(0.125f, processor.currentLeftCrop, 0.005f)
        assertEquals(0.125f, processor.currentRightCrop, 0.005f)
        // Vertical letterbox should be 0.0f (touches top and bottom)
        assertEquals(0.0f, processor.currentTopCrop, 0.001f)
    }

    @Test
    fun scanLetterbox_fullscreen169_hasZeroCrops() {
        val processor = ScreenColorProcessor()
        val width = 320
        val height = 180
        val rowStride = width * 4
        val pixelStride = 4
        val buffer = java.nio.ByteBuffer.allocateDirect(rowStride * height)

        // Fullscreen 16:9 picture filling all edges
        for (y in 0 until height) {
            for (x in 0 until width) {
                val offset = y * rowStride + x * pixelStride
                buffer.put(offset, 100.toByte())
                buffer.put(offset + 1, 100.toByte())
                buffer.put(offset + 2, 100.toByte())
                buffer.put(offset + 3, 255.toByte())
            }
        }

        for (frame in 1..35) {
            processor.scanLetterbox(buffer, width, height, rowStride, pixelStride)
        }

        assertEquals(0.0f, processor.currentTopCrop, 0.001f)
        assertEquals(0.0f, processor.currentBottomCrop, 0.001f)
        assertEquals(0.0f, processor.currentLeftCrop, 0.001f)
        assertEquals(0.0f, processor.currentRightCrop, 0.001f)
    }

    @Test
    fun getActiveZones_withPillarbox_shiftsPerimeterAndAmbientZones() {
        val processor = ScreenColorProcessor()
        val width = 320
        val height = 180
        val rowStride = width * 4
        val pixelStride = 4
        val buffer = java.nio.ByteBuffer.allocateDirect(rowStride * height)

        // 4:3 Pillarbox frame
        for (y in 0 until height) {
            for (x in 40..279) {
                val offset = y * rowStride + x * pixelStride
                buffer.put(offset, 200.toByte())
                buffer.put(offset + 1, 200.toByte())
                buffer.put(offset + 2, 200.toByte())
            }
        }

        for (frame in 1..35) {
            processor.scanLetterbox(buffer, width, height, rowStride, pixelStride)
        }

        assertEquals(0.125f, processor.currentLeftCrop, 0.005f)
        assertEquals(0.125f, processor.currentRightCrop, 0.005f)

        // Perimeter device: 10 + 5 + 10 + 5 = 30 LEDs
        val perimeterDev = com.wled.tv.model.WledDevice(
            id = "tv_perimeter",
            type = com.wled.tv.model.DeviceType.PERIMETER,
            perimeter = com.wled.tv.model.PerimeterConfig(
                topLeds = 10,
                rightLeds = 5,
                bottomLeds = 10,
                leftLeds = 5,
                autoLetterbox = true
            )
        )

        val zones = processor.getActiveZones(perimeterDev)
        assertEquals(30, zones.size)
        // Verify perimeter left edge shifted inward to pillarbox crop boundary (~0.125f)
        assertTrue("Expected zones.first().left >= 0.12f but was ${zones.first().left}", zones.first().left >= 0.12f)
        val rightEdgeZone = zones.find { it.right > 0.80f }
        assertTrue(rightEdgeZone != null && rightEdgeZone.right <= 0.88f)

        // Left Ambient device should generate 1 active zone shifted inward
        val leftAmbientDev = com.wled.tv.model.WledDevice(
            id = "left_lamp",
            type = com.wled.tv.model.DeviceType.LEFT_AMBIENT,
            ledCount = 1,
            perimeter = com.wled.tv.model.PerimeterConfig(autoLetterbox = true)
        )
        val ambientZones = processor.getActiveZones(leftAmbientDev)
        assertEquals(1, ambientZones.size)
        assertTrue(ambientZones.first().left >= 0.12f)
    }

    @Test
    fun overlayRejection_disabledWhenOverlayNotVisible_preservesTealAndBlueScenes() {
        val processor = ScreenColorProcessor()
        processor.isOverlayActive = false

        val width = 320
        val height = 180
        val rowStride = width * 4
        val pixelStride = 4
        val buffer = java.nio.ByteBuffer.allocateDirect(rowStride * height)

        // Fill with deep ocean teal/blue: RGB(10, 80, 160)
        // (r < 18 && g > 25 && b > 25)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val offset = y * rowStride + x * pixelStride
                buffer.put(offset, 10.toByte())
                buffer.put(offset + 1, 80.toByte())
                buffer.put(offset + 2, 160.toByte())
                buffer.put(offset + 3, 255.toByte())
            }
        }

        // scanLetterbox should NOT consider this dark teal/blue frame as black letterbox
        processor.scanLetterbox(buffer, width, height, rowStride, pixelStride)
        assertEquals(0.0f, processor.currentTopCrop, 0.001f)
        assertEquals(0.0f, processor.currentLeftCrop, 0.001f)

        // Extracting raw colors should NOT reject the teal pixels when isOverlayActive is false
        val device = com.wled.tv.model.WledDevice(
            id = "top_strip",
            type = com.wled.tv.model.DeviceType.PERIMETER,
            perimeter = com.wled.tv.model.PerimeterConfig(topLeds = 4, rightLeds = 0, bottomLeds = 0, leftLeds = 0)
        )
        val output = ByteArray(4 * 3)
        processor.extractRawColors(
            buffer = buffer,
            width = width,
            height = height,
            rowStride = rowStride,
            pixelStride = pixelStride,
            device = device,
            calibration = com.wled.tv.model.ColorCalibration(),
            outputRawRgb = output
        )

        // Blue channel (index 2) must remain vibrant (~160), not darkened toward 0
        val sampledBlue = output[2].toInt() and 0xFF
        assertTrue("Sampled blue should be vibrant (> 100) but was $sampledBlue", sampledBlue > 100)
    }

    @Test
    fun reset_clearsPillarboxState() {
        val processor = ScreenColorProcessor()
        val width = 320
        val height = 180
        val rowStride = width * 4
        val pixelStride = 4
        val buffer = java.nio.ByteBuffer.allocateDirect(rowStride * height)

        for (y in 0 until height) {
            for (x in 40..279) {
                val offset = y * rowStride + x * pixelStride
                buffer.put(offset, 200.toByte())
            }
        }
        for (frame in 1..35) {
            processor.scanLetterbox(buffer, width, height, rowStride, pixelStride)
        }
        assertEquals(0.125f, processor.currentLeftCrop, 0.005f)

        processor.reset()
        assertEquals(0.0f, processor.currentLeftCrop, 0.001f)
        assertEquals(0.0f, processor.currentRightCrop, 0.001f)
    }

    @Test
    fun isFrameCompletelyBlack_identifiesAllBlackFrame() {
        val processor = ScreenColorProcessor()
        val width = 320
        val height = 180
        val rowStride = width * 4
        val pixelStride = 4
        val buffer = java.nio.ByteBuffer.allocateDirect(rowStride * height)

        // All 0s (digital black)
        val isBlack = processor.isFrameCompletelyBlack(buffer, width, height, rowStride, pixelStride)
        assertTrue("Expected 100% black buffer to return true", isBlack)

        // Low noise below noise floor (e.g. RGB = 3)
        for (i in 0 until (rowStride * height)) {
            buffer.put(i, 3.toByte())
        }
        val isNoiseBlack = processor.isFrameCompletelyBlack(buffer, width, height, rowStride, pixelStride)
        assertTrue("Expected noise below threshold to be classified as black", isNoiseBlack)

        // Frame with active content (e.g. at grid probe point)
        val stepX = width / 6
        val stepY = height / 6
        val probeOffset = (3 * stepY) * rowStride + (3 * stepX) * pixelStride
        buffer.put(probeOffset, 150.toByte())
        buffer.put(probeOffset + 1, 150.toByte())
        buffer.put(probeOffset + 2, 150.toByte())

        val isContentBlack = processor.isFrameCompletelyBlack(buffer, width, height, rowStride, pixelStride)
        org.junit.Assert.assertFalse("Expected active content frame to return false", isContentBlack)
    }
}

