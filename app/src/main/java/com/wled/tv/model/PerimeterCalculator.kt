package com.wled.tv.model

/**
 * Result of calculating perimeter configuration from four physical corner indices.
 */
data class CalculatedPerimeter(
    val topLeds: Int,
    val rightLeds: Int,
    val bottomLeds: Int,
    val leftLeds: Int,
    val startCorner: Corner,
    val direction: Direction,
    val totalLeds: Int
)

/**
 * Calculates perimeter segment counts, starting corner, and wiring direction from 4 corner indices.
 */
object PerimeterCalculator {

    /**
     * Given total LEDs and the 4 corner indices (Top-Left, Top-Right, Bottom-Right, Bottom-Left),
     * computes the segment counts and validates that the corners form a valid closed loop.
     *
     * @return CalculatedPerimeter if valid, or null if corners are invalid or non-monotonic.
     */
    fun calculateFromCorners(
        totalLeds: Int,
        tl: Int,
        tr: Int,
        br: Int,
        bl: Int
    ): CalculatedPerimeter? {
        if (totalLeds < 4) return null
        val corners = listOf(tl, tr, br, bl)
        if (corners.any { it !in 0 until totalLeds }) return null
        if (corners.distinct().size != 4) return null

        fun dist(from: Int, to: Int): Int = (to - from + totalLeds) % totalLeds

        // Clockwise distance sum: TL -> TR -> BR -> BL -> TL
        val cwTop = dist(tl, tr)
        val cwRight = dist(tr, br)
        val cwBottom = dist(br, bl)
        val cwLeft = dist(bl, tl)
        val cwSum = cwTop + cwRight + cwBottom + cwLeft

        // Counter-clockwise distance sum: TL -> BL -> BR -> TR -> TL
        val ccwLeft = dist(tl, bl)
        val ccwBottom = dist(bl, br)
        val ccwRight = dist(br, tr)
        val ccwTop = dist(tr, tl)
        val ccwSum = ccwLeft + ccwBottom + ccwRight + ccwTop

        val (direction, top, right, bottom, left) = when {
            cwSum == totalLeds -> {
                Tuple5(Direction.CLOCKWISE, cwTop, cwRight, cwBottom, cwLeft)
            }
            ccwSum == totalLeds -> {
                Tuple5(Direction.COUNTER_CLOCKWISE, ccwTop, ccwRight, ccwBottom, ccwLeft)
            }
            else -> return null
        }

        // The corner with the lowest index along the physical strip is the starting corner
        val cornerMap = listOf(
            Corner.TOP_LEFT to tl,
            Corner.TOP_RIGHT to tr,
            Corner.BOTTOM_RIGHT to br,
            Corner.BOTTOM_LEFT to bl
        )
        val startCorner = cornerMap.minByOrNull { it.second }!!.first

        return CalculatedPerimeter(
            topLeds = top,
            rightLeds = right,
            bottomLeds = bottom,
            leftLeds = left,
            startCorner = startCorner,
            direction = direction,
            totalLeds = totalLeds
        )
    }

    private data class Tuple5<A, B, C, D, E>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
        val fifth: E
    )
}
