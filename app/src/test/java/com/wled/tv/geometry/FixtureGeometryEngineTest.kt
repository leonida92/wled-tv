package com.wled.tv.geometry

import com.wled.tv.model.AspectRatioPreset
import com.wled.tv.model.Corner
import com.wled.tv.model.DeviceType
import com.wled.tv.model.Direction
import com.wled.tv.model.PerimeterConfig
import com.wled.tv.model.WledDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FixtureGeometryEngineTest {

    private lateinit var engine: FixtureGeometryEngine

    @Before
    fun setUp() {
        engine = FixtureGeometryEngine()
    }

    // ==========================================
    // 1. NormalizedRect Primitive Tests
    // ==========================================

    @Test
    fun normalizedRect_defaultAndCalculatedProperties() {
        val rect = NormalizedRect(0.1f, 0.2f, 0.7f, 0.8f)
        assertEquals(0.6f, rect.width, 0.001f)
        assertEquals(0.6f, rect.height, 0.001f)
        assertEquals(0.4f, rect.centerX, 0.001f)
        assertEquals(0.5f, rect.centerY, 0.001f)
    }

    @Test
    fun normalizedRect_coerceIn_clampsOutOfBounds() {
        val outOfBounds = NormalizedRect(-0.2f, -0.5f, 1.5f, 1.2f)
        val clamped = outOfBounds.coerceIn(0f, 1f)
        assertEquals(0f, clamped.left, 0.001f)
        assertEquals(0f, clamped.top, 0.001f)
        assertEquals(1f, clamped.right, 0.001f)
        assertEquals(1f, clamped.bottom, 0.001f)
    }

    // ==========================================
    // 2. Perimeter Coordinate & Corner Math Tests
    // ==========================================

    @Test
    fun computeLedZones_clockwiseFromBottomLeft_ordersCorrectly() {
        val config = PerimeterConfig(
            topLeds = 4,
            rightLeds = 2,
            bottomLeds = 4,
            leftLeds = 2,
            startCorner = Corner.BOTTOM_LEFT,
            direction = Direction.CLOCKWISE,
            topCrop = 0f,
            bottomCrop = 0f,
            leftCrop = 0f,
            rightCrop = 0f,
            depth = 0.12f
        )
        val zones = engine.computeLedZones(config)
        assertEquals(12, zones.size)

        // Bottom-left starts with Left edge going upwards:
        // Index 0: first left zone (at bottom of left edge: bottom ~ 1.0)
        val firstLeft = zones[0]
        assertEquals(0f, firstLeft.left, 0.001f)
        assertEquals(1.0f, firstLeft.bottom, 0.001f)

        // Index 2: first top zone (starts at x=0, top=0)
        val firstTop = zones[2]
        assertEquals(0f, firstTop.left, 0.001f)
        assertEquals(0f, firstTop.top, 0.001f)

        // Index 6: first right zone (starts at top of right edge)
        val firstRight = zones[6]
        assertEquals(1.0f, firstRight.right, 0.001f)
        assertEquals(0f, firstRight.top, 0.001f)

        // Index 8: first bottom zone (starts at right of bottom edge)
        val firstBottom = zones[8]
        assertEquals(1.0f, firstBottom.right, 0.001f)
        assertEquals(1.0f, firstBottom.bottom, 0.001f)
    }

    @Test
    fun computeLedZones_counterClockwise_reversesSequence() {
        val configCw = PerimeterConfig(
            topLeds = 4,
            rightLeds = 2,
            bottomLeds = 4,
            leftLeds = 2,
            startCorner = Corner.BOTTOM_LEFT,
            direction = Direction.CLOCKWISE
        )
        val configCcw = configCw.copy(direction = Direction.COUNTER_CLOCKWISE)

        val zonesCw = engine.computeLedZones(configCw)
        val zonesCcw = engine.computeLedZones(configCcw)

        assertEquals(zonesCw.size, zonesCcw.size)
        assertEquals(zonesCw.first(), zonesCcw.last())
        assertEquals(zonesCw.last(), zonesCcw.first())
    }

    @Test
    fun computeLedZones_aspectCorrection_maintainsEqualPixelDepth() {
        val config = PerimeterConfig(
            topLeds = 10,
            rightLeds = 5,
            bottomLeds = 10,
            leftLeds = 5,
            depth = 0.12f
        )
        val zones = engine.computeLedZones(config)

        // Top zone height should be depthY = 0.12
        val topZone = zones.first { it.top == 0f }
        assertEquals(0.12f, topZone.height, 0.001f)

        // Left zone width should be aspect corrected: depthX = 0.12 * (9/16) = 0.0675
        val leftZone = zones.first { it.left == 0f }
        assertEquals(0.12f * (9f / 16f), leftZone.width, 0.001f)
    }

    @Test
    fun computeLedZones_withCinemascopeCrop_squeezesVerticalSpan() {
        val config = PerimeterConfig(
            topLeds = 4,
            rightLeds = 2,
            bottomLeds = 4,
            leftLeds = 2,
            topCrop = 0.130f,
            bottomCrop = 0.130f,
            startCorner = Corner.TOP_LEFT
        )
        val zones = engine.computeLedZones(config)

        // Top zones start at 0.130f (below black bar)
        val topZone = zones[0]
        assertEquals(0.130f, topZone.top, 0.001f)

        // Right edge starts at 0.130f and ends at 1 - 0.130f = 0.870f
        val rightZone = zones[4] // first right zone
        assertEquals(0.130f, rightZone.top, 0.001f)
        val lastRightZone = zones[5]
        assertEquals(0.870f, lastRightZone.bottom, 0.001f)
    }

    // ==========================================
    // 3. Ambient Sub-Zone Partitioning Tests
    // ==========================================

    @Test
    fun computeAmbientSubZones_leftAmbient_subdividesVertically() {
        val region = NormalizedRect(0f, 0f, 0.25f, 1f)
        val zones = engine.computeAmbientSubZones(DeviceType.LEFT_AMBIENT, region, 4)

        assertEquals(4, zones.size)
        // Sub-zones should divide height into 4 equal segments of 0.25 height
        for (i in 0 until 4) {
            val z = zones[i]
            assertEquals(0f, z.left, 0.001f)
            assertEquals(0.25f, z.right, 0.001f)
            assertEquals(0.25f, z.height, 0.001f)
        }
    }

    @Test
    fun computeAmbientSubZones_topAmbient_subdividesHorizontally() {
        val region = NormalizedRect(0f, 0f, 1f, 0.25f)
        val zones = engine.computeAmbientSubZones(DeviceType.TOP_AMBIENT, region, 5)

        assertEquals(5, zones.size)
        for (i in 0 until 5) {
            val z = zones[i]
            assertEquals(0f, z.top, 0.001f)
            assertEquals(0.25f, z.bottom, 0.001f)
            assertEquals(0.20f, z.width, 0.001f)
        }
    }

    // ==========================================
    // 4. Letterbox Snapping & Temporal Hysteresis
    // ==========================================

    @Test
    fun updateDetectedBorders_cinemascope239_snapsAfter30Frames() {
        val rawDetection = RawBorderDetection(
            topCropPct = 0.131f,
            bottomCropPct = 0.129f,
            leftCropPct = 0f,
            rightCropPct = 0f
        )

        // 29 frames: candidate is still accumulating (requires 30)
        for (f in 1..29) {
            engine.updateDetectedBorders(rawDetection)
            assertEquals(0.0f, engine.currentTopCrop, 0.001f)
        }

        // 30th frame: triggers snap to 0.130f (Cinemascope)
        engine.updateDetectedBorders(rawDetection)
        assertEquals(0.130f, engine.currentTopCrop, 0.001f)
        assertEquals(0.130f, engine.currentBottomCrop, 0.001f)
    }

    @Test
    fun updateDetectedBorders_asymmetricExpansion_snapsIn15Frames() {
        // First establish Cinemascope
        val cinemascope = RawBorderDetection(topCropPct = 0.13f, bottomCropPct = 0.13f)
        for (f in 1..30) {
            engine.updateDetectedBorders(cinemascope)
        }
        assertEquals(0.130f, engine.currentTopCrop, 0.001f)

        // Sudden cut to Fullscreen 16:9 (top touched, crop = 0)
        val fullscreen = RawBorderDetection(topCropPct = 0.005f, bottomCropPct = 0.005f)

        // 14 frames: not yet snapped
        for (f in 1..14) {
            engine.updateDetectedBorders(fullscreen)
            assertEquals(0.130f, engine.currentTopCrop, 0.001f)
        }

        // 15th frame: fast expansion snaps back to fullscreen 0.0f!
        engine.updateDetectedBorders(fullscreen)
        assertEquals(0.0f, engine.currentTopCrop, 0.001f)
        assertEquals(0.0f, engine.currentBottomCrop, 0.001f)
    }

    @Test
    fun updateDetectedBorders_pillarbox43_snapsTo125Percent() {
        val pillar43 = RawBorderDetection(
            topCropPct = 0f,
            bottomCropPct = 0f,
            leftCropPct = 0.124f,
            rightCropPct = 0.126f
        )

        for (f in 1..30) {
            engine.updateDetectedBorders(pillar43)
        }

        assertEquals(0.125f, engine.currentLeftCrop, 0.001f)
        assertEquals(0.125f, engine.currentRightCrop, 0.001f)
        assertEquals(0.0f, engine.currentTopCrop, 0.001f)
    }

    // ==========================================
    // 5. Zero-Allocation Cache Stability
    // ==========================================

    @Test
    fun getActiveZones_returnsSameInstanceWhenUnchanged() {
        val device = WledDevice(
            id = "test-perimeter",
            type = DeviceType.PERIMETER,
            perimeter = PerimeterConfig(topLeds = 10, rightLeds = 5, bottomLeds = 10, leftLeds = 5)
        )

        val first = engine.getActiveZones(device)
        val second = engine.getActiveZones(device)

        assertSame("Expected cache hit returning identical instance for zero allocations", first, second)
    }

    @Test
    fun getActiveZones_invalidatesWhenCropChanges() {
        val device = WledDevice(
            id = "test-perimeter",
            type = DeviceType.PERIMETER,
            perimeter = PerimeterConfig(topLeds = 10, rightLeds = 5, bottomLeds = 10, leftLeds = 5, autoLetterbox = true)
        )

        val initial = engine.getActiveZones(device)
        assertEquals(0f, initial.first { it.top == 0f }.top, 0.001f)

        // Trigger Cinemascope
        val cinemascope = RawBorderDetection(topCropPct = 0.13f, bottomCropPct = 0.13f)
        for (f in 1..30) {
            engine.updateDetectedBorders(cinemascope)
        }

        assertEquals(0.130f, engine.currentTopCrop, 0.005f)
        val afterCrop = engine.getActiveZones(device)
        org.junit.Assert.assertNotSame(initial, afterCrop)
        val topZone = afterCrop.first { it.top == engine.currentTopCrop }
        assertEquals(0.130f, topZone.top, 0.001f)
    }

    // ==========================================
    // 6. UI Canvas Layout Solvers Tests
    // ==========================================

    @Test
    fun calculatePreviewLayout_generatesValidChassisAndDiodes() {
        val device = WledDevice(
            id = "primary-tv",
            type = DeviceType.PERIMETER,
            perimeter = PerimeterConfig(topLeds = 10, rightLeds = 5, bottomLeds = 10, leftLeds = 5)
        )
        val layout = engine.calculatePreviewLayout(1920f, 1080f, listOf(device))

        assertTrue(layout.tvRect.width > 1000f)
        assertTrue(layout.tvRect.height > 600f)
        assertEquals(30, layout.diodes.size)
        assertNotNull(layout.startCornerRing)
        assertTrue(layout.screenRect.left > layout.tvRect.left)
    }

    @Test
    fun calculateZoneCanvasLayout_generatesAccurateLinesAndBadges() {
        val config = PerimeterConfig(
            topLeds = 10,
            rightLeds = 5,
            bottomLeds = 10,
            leftLeds = 5,
            topCrop = 0.130f,
            bottomCrop = 0.130f
        )
        val layout = engine.calculateZoneCanvasLayout(800f, 600f, config, ActiveBorder.TOP)

        assertEquals(800f, layout.screenRect.width, 0.001f)
        assertEquals(600f, layout.screenRect.height, 0.001f)
        assertNotNull(layout.topLetterbox)
        assertNotNull(layout.bottomLetterbox)
        assertNull(layout.leftPillarbox)
        assertNull(layout.rightPillarbox)

        assertEquals(30, layout.zones.size)
        assertEquals(4, layout.borderLines.size)
        assertTrue(layout.borderLines.first { it.isActive }.isActive)
        assertEquals("2.39:1 Cinemascope", layout.badgeText)
    }

    @Test
    fun geometryPackage_hasZeroAndroidSdkImports() {
        val candidates = listOf(
            java.io.File("src/main/java/com/wled/tv/geometry"),
            java.io.File("app/src/main/java/com/wled/tv/geometry")
        )
        val geometryDir = candidates.firstOrNull { it.exists() && it.isDirectory }
        assertNotNull("Geometry directory must exist", geometryDir)

        val files = geometryDir!!.listFiles { f -> f.extension == "kt" } ?: emptyArray()
        assertTrue("Expected geometry files to be found", files.isNotEmpty())

        val forbiddenImports = listOf("android.graphics.", "android.view.", "android.content.", "android.app.")
        for (file in files) {
            val lines = file.readLines()
            for ((index, line) in lines.withIndex()) {
                for (forbidden in forbiddenImports) {
                    assertFalse(
                        "File ${file.name}:${index + 1} contains forbidden Android SDK import '$forbidden': $line",
                        line.trim().startsWith("import $forbidden")
                    )
                }
            }
        }
    }
}
