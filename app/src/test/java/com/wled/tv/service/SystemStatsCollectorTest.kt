package com.wled.tv.service

import org.junit.Assert.assertEquals
import org.junit.Test

class SystemStatsCollectorTest {

    @Test
    fun calculateCpuPercent_withNormalUsage_returnsExpectedPercent() {
        // Delta CPU = 200ms across 1000ms wall time on a 4-core SoC:
        // (200 / (1000 * 4)) * 100 = 5%
        val percent = SystemStatsCollector.calculateCpuPercent(
            currentCpuTime = 1200L,
            lastCpuTime = 1000L,
            currentWallTime = 5000L,
            lastWallTime = 4000L,
            cores = 4
        )
        assertEquals(5, percent)
    }

    @Test
    fun calculateCpuPercent_withFullCoreUsage_returnsOneHundredPercent() {
        // Delta CPU = 4000ms across 1000ms wall time on 4 cores = 100%
        val percent = SystemStatsCollector.calculateCpuPercent(
            currentCpuTime = 5000L,
            lastCpuTime = 1000L,
            currentWallTime = 2000L,
            lastWallTime = 1000L,
            cores = 4
        )
        assertEquals(100, percent)
    }

    @Test
    fun calculateCpuPercent_withZeroDeltaWall_returnsZero() {
        val percent = SystemStatsCollector.calculateCpuPercent(
            currentCpuTime = 1000L,
            lastCpuTime = 1000L,
            currentWallTime = 2000L,
            lastWallTime = 2000L,
            cores = 4
        )
        assertEquals(0, percent)
    }

    @Test
    fun calculateCpuPercent_withFirstSample_returnsZero() {
        val percent = SystemStatsCollector.calculateCpuPercent(
            currentCpuTime = 1000L,
            lastCpuTime = 0L,
            currentWallTime = 2000L,
            lastWallTime = 0L,
            cores = 4
        )
        assertEquals(0, percent)
    }

    @Test
    fun formatStats_producesExpectedMinimalisticCompactString() {
        val formatted = SystemStatsCollector.formatStats(
            cpuPct = 8,
            usedRamGb = 1.2,
            totalRamGb = 2.0,
            usedDiskGb = 4.5,
            totalDiskGb = 16.0
        )
        assertEquals("CPU 8% • RAM 1.2/2.0 GB • Disk 4.5/16 GB", formatted)
    }
}
