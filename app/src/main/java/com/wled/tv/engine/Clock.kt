package com.wled.tv.engine

/**
 * Clock abstraction to allow deterministic time and uptime control in tests.
 */
interface Clock {
    /**
     * Standard wall-clock time in milliseconds since Unix epoch (equivalent to System.currentTimeMillis()).
     */
    fun currentTimeMillis(): Long

    /**
     * Monotonic execution time in milliseconds since system boot (equivalent to SystemClock.uptimeMillis()).
     */
    fun uptimeMillis(): Long
}

/**
 * Production implementation backed by Android's SystemClock and System.currentTimeMillis().
 */
class SystemClock : Clock {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()

    override fun uptimeMillis(): Long {
        return try {
            android.os.SystemClock.uptimeMillis()
        } catch (_: Throwable) {
            System.nanoTime() / 1_000_000L
        }
    }
}

/**
 * Controllable clock implementation for fast, deterministic unit testing.
 */
class TestClock(
    private var currentTime: Long = 0L,
    private var uptime: Long = 0L
) : Clock {
    override fun currentTimeMillis(): Long = currentTime
    override fun uptimeMillis(): Long = uptime

    fun advance(ms: Long) {
        currentTime += ms
        uptime += ms
    }

    fun setCurrentTime(ms: Long) {
        currentTime = ms
    }

    fun setUptime(ms: Long) {
        uptime = ms
    }
}
