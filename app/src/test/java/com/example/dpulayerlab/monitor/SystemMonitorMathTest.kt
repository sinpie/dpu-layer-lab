package com.example.dpulayerlab.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun memoryMetricsRejectImpossibleSystemValuesInsteadOfPublishingNan() {
        val valid = normalizedMemoryMetrics(
            totalBytes = 4L * 1_024L * 1_024L,
            availableBytes = 1L * 1_024L * 1_024L,
            appPssKilobytes = 2_048L,
        )
        assertEquals(75f, valid.usedPercent!!, 0.0001f)
        assertEquals(1f, valid.availableMb!!, 0.0001f)
        assertEquals(2f, valid.appPssMb!!, 0.0001f)

        val impossible = normalizedMemoryMetrics(
            totalBytes = Long.MAX_VALUE,
            availableBytes = Long.MAX_VALUE,
            appPssKilobytes = -1L,
        )
        assertEquals(0f, impossible.usedPercent!!, 0.0001f)
        assertNull(impossible.appPssMb)

        val overReportedAvailable = normalizedMemoryMetrics(
            totalBytes = 1L,
            availableBytes = 2L,
            appPssKilobytes = 0L,
        )
        assertNull(overReportedAvailable.usedPercent)
        assertNull(overReportedAvailable.availableMb)
    }

    @Test
    fun vendorCounterSourceChangesAcrossBinderSessions() {
        val first = vendorServiceSource(apiVersion = 2, serviceSession = 7L)
        val reconnected = vendorServiceSource(apiVersion = 2, serviceSession = 8L)

        assertTrue(first != reconnected)
        assertTrue(first.contains("session=7"))
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

    @Test
    fun hardwareCpuDeltaRejectsChangingParticipantTopology() {
        val twoCores = CpuTimes(idle = 100L, total = 200L, participantCount = 2)
        val fourCores = CpuTimes(idle = 200L, total = 500L, participantCount = 4)
        val stableFourCoreSample = CpuTimes(idle = 260L, total = 700L, participantCount = 4)

        assertNull(fourCores.percentSince(twoCores))
        assertEquals(70f, stableFourCoreSample.percentSince(fourCores)!!, 0.0001f)
    }

    @Test
    fun procStatParserDoesNotDoubleCountGuestTime() {
        // user nice system idle iowait irq softirq steal guest guest_nice
        val parsed = parseProcStatCpuLine("cpu 100 20 30 400 50 10 5 2 70 10")

        assertEquals(450L, parsed?.idle)
        assertEquals(617L, parsed?.total)
    }

    @Test
    fun procStatParserRejectsMalformedNegativeAndOverflowingInput() {
        assertNull(parseProcStatCpuLine(null))
        assertNull(parseProcStatCpuLine("cpu0 1 2 3 4 5"))
        assertNull(parseProcStatCpuLine("cpu 1 2 3 -4 5"))
        assertNull(
            parseProcStatCpuLine(
                "cpu ${Long.MAX_VALUE} 1 1 1 1 1 1 1",
            ),
        )
    }
}
