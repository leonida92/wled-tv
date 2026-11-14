package com.wled.tv.engine

/**
 * Pure-Kotlin diagnostic light patterns emitted by [DiagnosticStreamCoordinator].
 */
sealed class DiagnosticPattern {
    /**
     * Target cadence interval in milliseconds for this diagnostic pattern.
     */
    abstract val tickIntervalMs: Long

    /**
     * Solid calibration reference color targeted at a specific fixture (or primary fixture when [deviceId] is null).
     * Automatically applies the target fixture's gain and max-brightness calibration on each tick.
     */
    data class SolidColor(
        val r: Int,
        val g: Int,
        val b: Int,
        val deviceId: String? = null
    ) : DiagnosticPattern() {
        override val tickIntervalMs: Long = 100L // 10 Hz
    }

    /**
     * 30 Hz animated HSV rainbow sweep across all enabled fixtures.
     */
    data object Rainbow : DiagnosticPattern() {
        override val tickIntervalMs: Long = 33L // ~30 Hz
    }
}
