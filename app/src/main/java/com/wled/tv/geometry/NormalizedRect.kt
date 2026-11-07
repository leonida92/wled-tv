package com.wled.tv.geometry

/**
 * Pure Kotlin immutable rectangle representing normalized coordinates [0.0..1.0]
 * or absolute pixel coordinates in UI layout vectors.
 *
 * Free of Android framework dependencies, enabling 100% JVM unit testability.
 */
data class NormalizedRect(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun coerceIn(min: Float = 0f, max: Float = 1f): NormalizedRect {
        return NormalizedRect(
            left = left.coerceIn(min, max),
            top = top.coerceIn(min, max),
            right = right.coerceIn(min, max),
            bottom = bottom.coerceIn(min, max)
        )
    }

    companion object {
        val ZERO = NormalizedRect(0f, 0f, 0f, 0f)
        val FULL = NormalizedRect(0f, 0f, 1f, 1f)
    }
}
