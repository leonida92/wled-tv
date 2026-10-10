package com.wled.tv.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ThroughputStatsTest {

    @Test
    fun testThroughputStatsValues() {
        val stats = ThroughputStats(fps = 60, targetFps = 60, pps = 120, totalPackets = 1200L)
        assertEquals(60, stats.fps)
        assertEquals(60, stats.targetFps)
        assertEquals(120, stats.pps)
        assertEquals(1200L, stats.totalPackets)
    }

    @Test
    fun testThroughputHistoryPoint() {
        val now = System.currentTimeMillis()
        val point = ThroughputHistoryPoint(timestamp = now, fps = 58, pps = 116)
        assertEquals(now, point.timestamp)
        assertEquals(58, point.fps)
        assertEquals(116, point.pps)
    }

    @Test
    fun testDeviceBreakdownStats() {
        val deviceList = listOf(
            DeviceThroughputStats(deviceId = "dev1", deviceName = "Back TV", ip = "192.168.1.100", pps = 60, totalPackets = 600L),
            DeviceThroughputStats(deviceId = "dev2", deviceName = "Underglow", ip = "192.168.1.101", pps = 60, totalPackets = 600L)
        )
        val stats = ThroughputStats(fps = 60, targetFps = 60, pps = 120, totalPackets = 1200L, deviceStats = deviceList)

        assertEquals(2, stats.deviceStats.size)
        assertEquals("Back TV", stats.deviceStats[0].deviceName)
        assertEquals(60, stats.deviceStats[0].pps)
        assertEquals("Underglow", stats.deviceStats[1].deviceName)
        assertEquals(60, stats.deviceStats[1].pps)
        assertEquals(120, stats.pps)
        assertEquals(1200L, stats.totalPackets)
    }
}
