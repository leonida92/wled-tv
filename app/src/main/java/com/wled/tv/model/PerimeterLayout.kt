package com.wled.tv.model

import android.graphics.RectF

enum class Corner {
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    TOP_LEFT,
    TOP_RIGHT
}

enum class Direction {
    CLOCKWISE,
    COUNTER_CLOCKWISE
}

enum class AspectRatioPreset(
    val displayName: String,
    val topCrop: Float,
    val bottomCrop: Float,
    val leftCrop: Float,
    val rightCrop: Float
) {
    FULL_16_9("16:9 Fullscreen", 0f, 0f, 0f, 0f),
    CINEMASCOPE_2_39("2.39:1 Cinemascope", 0.130f, 0.130f, 0f, 0f),
    WIDESCREEN_2_35("2.35:1 Widescreen", 0.121f, 0.121f, 0f, 0f),
    UNIVISIUM_2_00("2.00:1 Univisium (Netflix)", 0.055f, 0.055f, 0f, 0f),
    THEATRICAL_1_85("1.85:1 Flat", 0.020f, 0.020f, 0f, 0f),
    PILLARBOX_4_3("4:3 Classic", 0f, 0f, 0.125f, 0.125f),
    CUSTOM("Custom", 0f, 0f, 0f, 0f)
}

data class PerimeterConfig(
    val topLeds: Int = 54,
    val rightLeds: Int = 30,
    val bottomLeds: Int = 54,
    val leftLeds: Int = 30,
    val startCorner: Corner = Corner.BOTTOM_LEFT,
    val direction: Direction = Direction.CLOCKWISE,
    val topCrop: Float = 0.0f,
    val bottomCrop: Float = 0.0f,
    val leftCrop: Float = 0.0f,
    val rightCrop: Float = 0.0f,
    val autoLetterbox: Boolean = false
) {
    val totalLeds: Int
        get() = topLeds + rightLeds + bottomLeds + leftLeds

    fun getActiveAspectRatioPreset(): AspectRatioPreset {
        for (preset in AspectRatioPreset.values()) {
            if (preset == AspectRatioPreset.CUSTOM) continue
            val diffT = Math.abs(topCrop - preset.topCrop)
            val diffB = Math.abs(bottomCrop - preset.bottomCrop)
            val diffL = Math.abs(leftCrop - preset.leftCrop)
            val diffR = Math.abs(rightCrop - preset.rightCrop)
            if (diffT < 0.005f && diffB < 0.005f && diffL < 0.005f && diffR < 0.005f) {
                return preset
            }
        }
        return AspectRatioPreset.CUSTOM
    }

    /**
     * Generates a normalized RectF [0..1] sampling bounding box for each LED around the perimeter.
     * When letterbox cropping is active (e.g. 2.39:1 movie bars), left and right edges are
     * squeezed to span only between topCrop and (1 - bottomCrop) so no black bars are sampled.
     */
    fun computeLedZones(): List<RectF> {
        val yTop = topCrop.coerceIn(0f, 0.40f)
        val yBottom = (1.0f - bottomCrop).coerceIn(0.60f, 1.0f)
        val xLeft = leftCrop.coerceIn(0f, 0.40f)
        val xRight = (1.0f - rightCrop).coerceIn(0.60f, 1.0f)

        val activeH = (yBottom - yTop).coerceAtLeast(0.1f)
        val activeW = (xRight - xLeft).coerceAtLeast(0.1f)

        val depthY = 0.12f * activeH
        val depthX = 0.12f * activeW

        // 1. Compute physical edge arrays (each ordered clockwise)
        val topZones = ArrayList<RectF>(topLeds)
        if (topLeds > 0) {
            val step = activeW / topLeds
            for (i in 0 until topLeds) {
                val left = xLeft + (i * step)
                topZones.add(RectF(left, yTop, left + step, yTop + depthY))
            }
        }

        val rightZones = ArrayList<RectF>(rightLeds)
        if (rightLeds > 0) {
            val step = activeH / rightLeds
            for (i in 0 until rightLeds) {
                val top = yTop + (i * step)
                rightZones.add(RectF(xRight - depthX, top, xRight, top + step))
            }
        }

        val bottomZones = ArrayList<RectF>(bottomLeds)
        if (bottomLeds > 0) {
            val step = activeW / bottomLeds
            for (i in 0 until bottomLeds) {
                val right = xRight - (i * step)
                bottomZones.add(RectF(right - step, yBottom - depthY, right, yBottom))
            }
        }

        val leftZones = ArrayList<RectF>(leftLeds)
        if (leftLeds > 0) {
            val step = activeH / leftLeds
            for (i in 0 until leftLeds) {
                val bottom = yBottom - (i * step)
                leftZones.add(RectF(xLeft, bottom - step, xLeft + depthX, bottom))
            }
        }

        // 2. Assemble in clockwise order starting from the chosen corner
        val clockwiseList = ArrayList<RectF>(totalLeds)
        when (startCorner) {
            Corner.BOTTOM_LEFT -> {
                clockwiseList.addAll(leftZones)
                clockwiseList.addAll(topZones)
                clockwiseList.addAll(rightZones)
                clockwiseList.addAll(bottomZones)
            }
            Corner.TOP_LEFT -> {
                clockwiseList.addAll(topZones)
                clockwiseList.addAll(rightZones)
                clockwiseList.addAll(bottomZones)
                clockwiseList.addAll(leftZones)
            }
            Corner.TOP_RIGHT -> {
                clockwiseList.addAll(rightZones)
                clockwiseList.addAll(bottomZones)
                clockwiseList.addAll(leftZones)
                clockwiseList.addAll(topZones)
            }
            Corner.BOTTOM_RIGHT -> {
                clockwiseList.addAll(bottomZones)
                clockwiseList.addAll(leftZones)
                clockwiseList.addAll(topZones)
                clockwiseList.addAll(rightZones)
            }
        }

        // 3. If counter-clockwise, reverse sequence
        return if (direction == Direction.CLOCKWISE) {
            clockwiseList
        } else {
            clockwiseList.reversed()
        }
    }
}
