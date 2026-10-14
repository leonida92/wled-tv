package com.wled.tv.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PerimeterCalculatorTest {

    @Test
    fun calculateFromCorners_clockwiseStartingBottomLeft() {
        // 168 LEDs total: Left=30, Top=54, Right=30, Bottom=54
        // Physical corners along strip: BL=0, TL=30, TR=84, BR=114
        val result = PerimeterCalculator.calculateFromCorners(
            totalLeds = 168,
            tl = 30,
            tr = 84,
            br = 114,
            bl = 0
        )

        assertNotNull(result)
        result!!
        assertEquals(Direction.CLOCKWISE, result.direction)
        assertEquals(Corner.BOTTOM_LEFT, result.startCorner)
        assertEquals(54, result.topLeds)
        assertEquals(30, result.rightLeds)
        assertEquals(54, result.bottomLeds)
        assertEquals(30, result.leftLeds)
        assertEquals(168, result.totalLeds)
    }

    @Test
    fun calculateFromCorners_clockwiseStartingTopLeft() {
        // Physical corners along strip: TL=0, TR=54, BR=84, BL=138
        val result = PerimeterCalculator.calculateFromCorners(
            totalLeds = 168,
            tl = 0,
            tr = 54,
            br = 84,
            bl = 138
        )

        assertNotNull(result)
        result!!
        assertEquals(Direction.CLOCKWISE, result.direction)
        assertEquals(Corner.TOP_LEFT, result.startCorner)
        assertEquals(54, result.topLeds)
        assertEquals(30, result.rightLeds)
        assertEquals(54, result.bottomLeds)
        assertEquals(30, result.leftLeds)
    }

    @Test
    fun calculateFromCorners_counterClockwiseStartingBottomLeft() {
        // Physical corners: BL=0, BR=54, TR=84, TL=138
        val result = PerimeterCalculator.calculateFromCorners(
            totalLeds = 168,
            tl = 138,
            tr = 84,
            br = 54,
            bl = 0
        )

        assertNotNull(result)
        result!!
        assertEquals(Direction.COUNTER_CLOCKWISE, result.direction)
        assertEquals(Corner.BOTTOM_LEFT, result.startCorner)
        assertEquals(54, result.topLeds)
        assertEquals(30, result.rightLeds)
        assertEquals(54, result.bottomLeds)
        assertEquals(30, result.leftLeds)
    }

    @Test
    fun calculateFromCorners_counterClockwiseStartingTopLeft() {
        // Physical corners: TL=0, BL=30, BR=84, TR=114
        val result = PerimeterCalculator.calculateFromCorners(
            totalLeds = 168,
            tl = 0,
            tr = 114,
            br = 84,
            bl = 30
        )

        assertNotNull(result)
        result!!
        assertEquals(Direction.COUNTER_CLOCKWISE, result.direction)
        assertEquals(Corner.TOP_LEFT, result.startCorner)
        assertEquals(54, result.topLeds)
        assertEquals(30, result.rightLeds)
        assertEquals(54, result.bottomLeds)
        assertEquals(30, result.leftLeds)
    }

    @Test
    fun calculateFromCorners_invalidCornersReturnNull() {
        // Duplicate corners
        assertNull(PerimeterCalculator.calculateFromCorners(100, 10, 10, 50, 80))

        // Out of bounds
        assertNull(PerimeterCalculator.calculateFromCorners(100, -1, 30, 60, 90))
        assertNull(PerimeterCalculator.calculateFromCorners(100, 10, 30, 60, 100))

        // Non-monotonic scrambled corner indices
        assertNull(PerimeterCalculator.calculateFromCorners(100, 10, 80, 20, 60))
    }
}
