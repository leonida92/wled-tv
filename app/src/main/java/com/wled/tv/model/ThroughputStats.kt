package com.wled.tv.model

data class ThroughputHistoryPoint(
    val timestamp: Long,
    val fps: Int,
    val pps: Int
)

data class DeviceThroughputStats(
    val deviceId: String,
    val deviceName: String,
    val ip: String,
    val pps: Int,
    val totalPackets: Long
)

data class ThroughputStats(
    val fps: Int,
    val targetFps: Int,
    val pps: Int,
    val totalPackets: Long,
    val deviceStats: List<DeviceThroughputStats> = emptyList()
)
