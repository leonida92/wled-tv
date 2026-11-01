package com.wled.tv.service

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import java.util.Locale
import kotlin.math.roundToInt

class SystemStatsCollector(private val context: Context) {

    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    private val memoryInfo = ActivityManager.MemoryInfo()
    private val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    private var lastCpuTimeMs: Long = 0L
    private var lastWallTimeMs: Long = 0L
    private var cachedStats: String = ""

    /**
     * Samples CPU, RAM, and Disk metrics.
     * Guaranteed to execute safely without throwing exceptions on any Android version.
     */
    fun sample(): String {
        try {
            // 1. CPU Usage (Process CPU percentage across available cores)
            val currentCpuTime = Process.getElapsedCpuTime()
            val currentWallTime = SystemClock.uptimeMillis()

            val cpuPct = if (lastWallTimeMs > 0L) {
                val deltaCpu = currentCpuTime - lastCpuTimeMs
                val deltaWall = currentWallTime - lastWallTimeMs
                if (deltaWall > 0L) {
                    ((deltaCpu.toDouble() / (deltaWall * cores)) * 100.0).coerceIn(0.0, 100.0).roundToInt()
                } else 0
            } else 0

            lastCpuTimeMs = currentCpuTime
            lastWallTimeMs = currentWallTime

            // 2. RAM Usage (System-wide physical memory)
            var usedRamGb = 0.0
            var totalRamGb = 0.0
            if (activityManager != null) {
                activityManager.getMemoryInfo(memoryInfo)
                val totalMem = memoryInfo.totalMem
                val availMem = memoryInfo.availMem
                val usedMem = (totalMem - availMem).coerceAtLeast(0L)
                totalRamGb = totalMem.toDouble() / (1024.0 * 1024.0 * 1024.0)
                usedRamGb = usedMem.toDouble() / (1024.0 * 1024.0 * 1024.0)
            }

            // 3. Disk Usage (Internal data directory storage)
            var usedDiskGb = 0.0
            var totalDiskGb = 0.0
            try {
                val statFs = StatFs(Environment.getDataDirectory().path)
                val totalBytes = statFs.totalBytes
                val availBytes = statFs.availableBytes
                val usedBytes = (totalBytes - availBytes).coerceAtLeast(0L)
                totalDiskGb = totalBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
                usedDiskGb = usedBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
            } catch (_: Exception) {
            }

            cachedStats = formatStats(cpuPct, usedRamGb, totalRamGb, usedDiskGb, totalDiskGb)
        } catch (_: Exception) {
            // In case of unexpected environment errors, retain previous cached stats
        }
        return cachedStats
    }

    fun getLatestStats(): String = cachedStats

    fun reset() {
        lastCpuTimeMs = 0L
        lastWallTimeMs = 0L
        cachedStats = ""
    }

    companion object {
        fun calculateCpuPercent(
            currentCpuTime: Long,
            lastCpuTime: Long,
            currentWallTime: Long,
            lastWallTime: Long,
            cores: Int
        ): Int {
            if (lastWallTime <= 0L) return 0
            val deltaCpu = currentCpuTime - lastCpuTime
            val deltaWall = currentWallTime - lastWallTime
            val safeCores = cores.coerceAtLeast(1)
            if (deltaWall <= 0L) return 0
            return ((deltaCpu.toDouble() / (deltaWall * safeCores)) * 100.0)
                .coerceIn(0.0, 100.0)
                .roundToInt()
        }

        fun formatStats(
            cpuPct: Int,
            usedRamGb: Double,
            totalRamGb: Double,
            usedDiskGb: Double,
            totalDiskGb: Double
        ): String {
            return String.format(
                Locale.US,
                "CPU %d%% • RAM %.1f/%.1f GB • Disk %.1f/%.0f GB",
                cpuPct,
                usedRamGb,
                totalRamGb,
                usedDiskGb,
                totalDiskGb
            )
        }
    }
}
