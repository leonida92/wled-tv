package com.wled.tv.processing

import com.wled.tv.model.ColorCalibration
import com.wled.tv.model.DeviceType
import com.wled.tv.model.StreamingProtocol
import com.wled.tv.model.WledDevice
import org.junit.Assert.assertArrayEquals
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

    @Test
    fun applySmoothing_instantFactor_copiesValuesDirectly() {
        val processor = ScreenColorProcessor()
        val raw = byteArrayOf(100.toByte(), 150.toByte(), 200.toByte())
        val output = ByteArray(3)

        // Factor 1.0 = instant transition
        processor.applySmoothing("dev_instant", raw, 1, 1.0f, output)

        assertEquals(100, output[0].toInt() and 0xFF)
        assertEquals(150, output[1].toInt() and 0xFF)
        assertEquals(200, output[2].toInt() and 0xFF)
    }

    @Test
    fun applySmoothing_subtleChange_usesBaseSmoothing() {
        val processor = ScreenColorProcessor()
        val initial = byteArrayOf(100.toByte(), 100.toByte(), 100.toByte())
        val output = ByteArray(3)

        // Frame 1 initializes state
        processor.applySmoothing("dev_subtle", initial, 1, 0.2f, output)

        // Frame 2: Subtle delta (R changes from 100 to 110, dist = 10 < 30)
        val subtle = byteArrayOf(110.toByte(), 100.toByte(), 100.toByte())
        processor.applySmoothing("dev_subtle", subtle, 1, 0.2f, output)

        // With baseAlpha = 0.2: 100 * 0.8 + 110 * 0.2 = 102
        assertEquals(102, output[0].toInt() and 0xFF)
        assertEquals(100, output[1].toInt() and 0xFF)
        assertEquals(100, output[2].toInt() and 0xFF)
    }

    @Test
    fun reset_clearsSmoothingFilterState_restartsFilter() {
        val processor = ScreenColorProcessor()
        val initial = byteArrayOf(100.toByte(), 150.toByte(), 200.toByte())
        val output = ByteArray(3)

        processor.applySmoothing("dev_reset", initial, 1, 0.5f, output)
        processor.reset()

        val raw2 = byteArrayOf(50.toByte(), 50.toByte(), 50.toByte())
        processor.applySmoothing("dev_reset", raw2, 1, 1.0f, output)

        assertEquals(50, output[0].toInt() and 0xFF)
        assertEquals(50, output[1].toInt() and 0xFF)
        assertEquals(50, output[2].toInt() and 0xFF)
    }

    @Test
    fun encodeWireFrame_rgb_all6ColorOrders() {
        // 2 LEDs: LED 0 = RGB(10, 20, 30), LED 1 = RGB(40, 50, 60)
        val source = byteArrayOf(
            10.toByte(), 20.toByte(), 30.toByte(),
            40.toByte(), 50.toByte(), 60.toByte()
        )
        val outWire = ByteArray(6)

        // 1. ORDER_RGB
        val bytesRgb = ScreenColorProcessor.encodeWireFrame(source, 2, "RGB", false, "OFF", outWire)
        assertEquals(6, bytesRgb)
        assertArrayEquals(
            byteArrayOf(10, 20, 30, 40, 50, 60),
            outWire
        )

        // 2. ORDER_GRB
        val bytesGrb = ScreenColorProcessor.encodeWireFrame(source, 2, "GRB", false, "OFF", outWire)
        assertEquals(6, bytesGrb)
        assertArrayEquals(
            byteArrayOf(20, 10, 30, 50, 40, 60),
            outWire
        )

        // 3. ORDER_BGR
        val bytesBgr = ScreenColorProcessor.encodeWireFrame(source, 2, "BGR", false, "OFF", outWire)
        assertEquals(6, bytesBgr)
        assertArrayEquals(
            byteArrayOf(30, 20, 10, 60, 50, 40),
            outWire
        )

        // 4. ORDER_BRG
        val bytesBrg = ScreenColorProcessor.encodeWireFrame(source, 2, "BRG", false, "OFF", outWire)
        assertEquals(6, bytesBrg)
        assertArrayEquals(
            byteArrayOf(30, 10, 20, 60, 40, 50),
            outWire
        )

        // 5. ORDER_RBG
        val bytesRbg = ScreenColorProcessor.encodeWireFrame(source, 2, "RBG", false, "OFF", outWire)
        assertEquals(6, bytesRbg)
        assertArrayEquals(
            byteArrayOf(10, 30, 20, 40, 60, 50),
            outWire
        )

        // 6. ORDER_GBR
        val bytesGbr = ScreenColorProcessor.encodeWireFrame(source, 2, "GBR", false, "OFF", outWire)
        assertEquals(6, bytesGbr)
        assertArrayEquals(
            byteArrayOf(20, 30, 10, 50, 60, 40),
            outWire
        )
    }

    @Test
    fun encodeWireFrame_rgbw_autoSubtract() {
        // LED 0 = RGB(200, 100, 50) -> min=50, subtracted R'=150, G'=50, B'=0, W=50
        val source = byteArrayOf(200.toByte(), 100.toByte(), 50.toByte())
        val outWire = ByteArray(4)

        // RGB
        val bytesRgb = ScreenColorProcessor.encodeWireFrame(source, 1, "RGB", true, "AUTO_SUBTRACT", outWire)
        assertEquals(4, bytesRgb)
        assertEquals(150, outWire[0].toInt() and 0xFF)
        assertEquals(50, outWire[1].toInt() and 0xFF)
        assertEquals(0, outWire[2].toInt() and 0xFF)
        assertEquals(50, outWire[3].toInt() and 0xFF)

        // GRB
        ScreenColorProcessor.encodeWireFrame(source, 1, "GRB", true, "AUTO_SUBTRACT", outWire)
        assertEquals(50, outWire[0].toInt() and 0xFF)
        assertEquals(150, outWire[1].toInt() and 0xFF)
        assertEquals(0, outWire[2].toInt() and 0xFF)
        assertEquals(50, outWire[3].toInt() and 0xFF)

        // BGR
        ScreenColorProcessor.encodeWireFrame(source, 1, "BGR", true, "AUTO_SUBTRACT", outWire)
        assertEquals(0, outWire[0].toInt() and 0xFF)
        assertEquals(50, outWire[1].toInt() and 0xFF)
        assertEquals(150, outWire[2].toInt() and 0xFF)
        assertEquals(50, outWire[3].toInt() and 0xFF)

        // BRG
        ScreenColorProcessor.encodeWireFrame(source, 1, "BRG", true, "AUTO_SUBTRACT", outWire)
        assertEquals(0, outWire[0].toInt() and 0xFF)
        assertEquals(150, outWire[1].toInt() and 0xFF)
        assertEquals(50, outWire[2].toInt() and 0xFF)
        assertEquals(50, outWire[3].toInt() and 0xFF)

        // RBG
        ScreenColorProcessor.encodeWireFrame(source, 1, "RBG", true, "AUTO_SUBTRACT", outWire)
        assertEquals(150, outWire[0].toInt() and 0xFF)
        assertEquals(0, outWire[1].toInt() and 0xFF)
        assertEquals(50, outWire[2].toInt() and 0xFF)
        assertEquals(50, outWire[3].toInt() and 0xFF)

        // GBR
        ScreenColorProcessor.encodeWireFrame(source, 1, "GBR", true, "AUTO_SUBTRACT", outWire)
        assertEquals(50, outWire[0].toInt() and 0xFF)
        assertEquals(0, outWire[1].toInt() and 0xFF)
        assertEquals(150, outWire[2].toInt() and 0xFF)
        assertEquals(50, outWire[3].toInt() and 0xFF)
    }

    @Test
    fun encodeWireFrame_rgbw_autoAdd() {
        // LED 0 = RGB(200, 100, 50) -> min=50, AUTO_ADD keeps RGB unchanged and adds W=50
        val source = byteArrayOf(200.toByte(), 100.toByte(), 50.toByte())
        val outWire = ByteArray(4)

        val bytes = ScreenColorProcessor.encodeWireFrame(source, 1, "RGB", true, "AUTO_ADD", outWire)
        assertEquals(4, bytes)
        assertEquals(200, outWire[0].toInt() and 0xFF)
        assertEquals(100, outWire[1].toInt() and 0xFF)
        assertEquals(50, outWire[2].toInt() and 0xFF)
        assertEquals(50, outWire[3].toInt() and 0xFF)
    }

    @Test
    fun encodeWireFrame_rgbw_off() {
        // LED 0 = RGB(200, 100, 50) -> OFF leaves RGB unchanged and sets W=0
        val source = byteArrayOf(200.toByte(), 100.toByte(), 50.toByte())
        val outWire = ByteArray(4)

        val bytes = ScreenColorProcessor.encodeWireFrame(source, 1, "RGB", true, "OFF", outWire)
        assertEquals(4, bytes)
        assertEquals(200, outWire[0].toInt() and 0xFF)
        assertEquals(100, outWire[1].toInt() and 0xFF)
        assertEquals(50, outWire[2].toInt() and 0xFF)
        assertEquals(0, outWire[3].toInt() and 0xFF)
    }

    @Test
    fun encodeWireFrame_deviceOverload_matchesCompanion() {
        val processor = ScreenColorProcessor()
        val device = WledDevice(
            id = "test_dev",
            type = DeviceType.LEFT_AMBIENT,
            ledCount = 2,
            calibration = ColorCalibration(
                colorOrder = "GRB",
                isRgbw = true,
                rgbwMode = "AUTO_SUBTRACT"
            )
        )
        val source = byteArrayOf(
            200.toByte(), 100.toByte(), 50.toByte(),
            80.toByte(), 90.toByte(), 100.toByte()
        )
        val outDevice = ByteArray(8)
        val outCompanion = ByteArray(8)

        val writtenDev = processor.encodeWireFrame(device, source, outDevice)
        val writtenComp = ScreenColorProcessor.encodeWireFrame(
            sourceRgb = source,
            ledCount = 2,
            colorOrder = "GRB",
            isRgbw = true,
            rgbwMode = "AUTO_SUBTRACT",
            outputWire = outCompanion
        )

        assertEquals(8, writtenDev)
        assertEquals(8, writtenComp)
        assertArrayEquals(outCompanion, outDevice)
    }

    @Test
    fun encodeWireFrame_deviceOverload_respectsProtocolSafety() {
        val processor = ScreenColorProcessor()
        val drgbDevice = WledDevice(
            id = "test_drgb",
            type = DeviceType.LEFT_AMBIENT,
            protocol = StreamingProtocol.DRGB,
            ledCount = 1,
            calibration = ColorCalibration(isRgbw = true, colorOrder = "RGB")
        )
        val ddpDevice = WledDevice(
            id = "test_ddp",
            type = DeviceType.LEFT_AMBIENT,
            protocol = StreamingProtocol.DDP,
            ledCount = 1,
            calibration = ColorCalibration(isRgbw = true, colorOrder = "RGB")
        )
        val source = byteArrayOf(255.toByte(), 255.toByte(), 255.toByte())
        val outDrgb = ByteArray(3)
        val outDdp = ByteArray(4)

        val writtenDrgb = processor.encodeWireFrame(drgbDevice, source, outDrgb)
        val writtenDdp = processor.encodeWireFrame(ddpDevice, source, outDdp)

        assertEquals(3, writtenDrgb)
        assertEquals(4, writtenDdp)
    }

    @Test
    fun encodeWireFrame_invalidInputs_returnsZero() {
        val source = byteArrayOf(10, 20, 30)
        val outWire = ByteArray(4)

        // Negative or zero ledCount
        assertEquals(0, ScreenColorProcessor.encodeWireFrame(source, 0, "RGB", false, "OFF", outWire))
        assertEquals(0, ScreenColorProcessor.encodeWireFrame(source, -1, "RGB", false, "OFF", outWire))

        // Undersized source
        assertEquals(0, ScreenColorProcessor.encodeWireFrame(source, 2, "RGB", false, "OFF", ByteArray(6)))

        // Undersized outputWire
        assertEquals(0, ScreenColorProcessor.encodeWireFrame(source, 1, "RGB", false, "OFF", ByteArray(2)))
        assertEquals(0, ScreenColorProcessor.encodeWireFrame(source, 1, "RGB", true, "OFF", ByteArray(3)))
    }

    @Test
    fun parseColorOrder_handlesCaseAndWhitespace() {
        assertEquals(ScreenColorProcessor.ORDER_RGB, ScreenColorProcessor.parseColorOrder("RGB"))
        assertEquals(ScreenColorProcessor.ORDER_GRB, ScreenColorProcessor.parseColorOrder("grb"))
        assertEquals(ScreenColorProcessor.ORDER_BGR, ScreenColorProcessor.parseColorOrder("  bgr  "))
        assertEquals(ScreenColorProcessor.ORDER_BRG, ScreenColorProcessor.parseColorOrder("BrG"))
        assertEquals(ScreenColorProcessor.ORDER_RBG, ScreenColorProcessor.parseColorOrder("rbg"))
        assertEquals(ScreenColorProcessor.ORDER_GBR, ScreenColorProcessor.parseColorOrder("gbr"))
        assertEquals(ScreenColorProcessor.ORDER_RGB, ScreenColorProcessor.parseColorOrder("unknown"))
        assertEquals(ScreenColorProcessor.ORDER_RGB, ScreenColorProcessor.parseColorOrder(null))
    }

    @Test
    fun encodeWireFrame_zeroAllocationSafety() {
        val source = ByteArray(300) { (it % 256).toByte() }
        val dest = ByteArray(400)

        // Warm up JIT
        repeat(5000) {
            ScreenColorProcessor.encodeWireFrame(source, 100, "GRB", true, "AUTO_SUBTRACT", dest)
        }

        val memoryBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        repeat(10000) {
            ScreenColorProcessor.encodeWireFrame(source, 100, "GRB", true, "AUTO_SUBTRACT", dest)
        }
        val memoryAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

        // Allocations over 10,000 iterations should be negligible (< 64KB)
        val diff = memoryAfter - memoryBefore
        assertTrue("Allocated memory delta ($diff bytes) should be minimal (< 65536 bytes)", diff < 65536)
    }
}

