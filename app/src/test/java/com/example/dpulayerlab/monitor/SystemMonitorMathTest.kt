package com.example.dpulayerlab.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SystemMonitorMathTest {
    @Test
    fun ratesUseActualSampleIntervalAndRequireBaseline() {
        assertNull(normalizedPerSecond(count = 60L, elapsedMs = null))
        assertNull(normalizedPerSecond(count = 60L, elapsedMs = 0L))
        assertEquals(60f, normalizedPerSecond(count = 90L, elapsedMs = 1_500L)!!, 0.0001f)
        assertEquals(
            1f,
            normalizedGigabitsPerSecond(bytes = 125_000_000L, elapsedMs = 1_000L)!!,
            0.0001f,
        )
    }

    @Test
    fun ratesRejectCounterOverflowSignals() {
        assertNull(normalizedPerSecond(count = -1L, elapsedMs = 1_000L))
        assertNull(normalizedGigabitsPerSecond(bytes = -1L, elapsedMs = 1_000L))
    }

    @Test
    fun cpuDeltaRequiresMonotonicInternallyConsistentCounters() {
        val baseline = CpuTimes(idle = 100L, total = 200L)

        assertNull(baseline.percentSince(null))
        assertEquals(
            70f,
            CpuTimes(idle = 130L, total = 300L).percentSince(baseline)!!,
            0.0001f,
        )
        assertNull(CpuTimes(idle = 90L, total = 300L).percentSince(baseline))
        assertNull(CpuTimes(idle = 250L, total = 300L).percentSince(baseline))
        assertNull(CpuTimes(idle = 400L, total = 300L).percentSince(baseline))
    }
}
