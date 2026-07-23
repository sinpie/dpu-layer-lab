package com.example.dpulayerlab.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KernelSensorProviderTest {
    @Test
    fun cumulativeBusyCountersNeedSameSourceBaseline() {
        val first = parseBusyPercent("100 200", "/sys/gpu-a", previous = null)!!
        assertNull(first.percent)

        val second = parseBusyPercent("125 300", "/sys/gpu-a", first.nextCounter)!!
        assertEquals(25f, second.percent!!, 0.0001f)

        val changedSource = parseBusyPercent("150 400", "/sys/gpu-b", second.nextCounter)!!
        assertNull(changedSource.percent)
        assertEquals("/sys/gpu-b", changedSource.nextCounter?.source)
    }

    @Test
    fun counterResetBecomesNewUnavailableBaseline() {
        val previous = BusyCounterState(busy = 500L, total = 1_000L, source = "/sys/gpu")
        val reset = parseBusyPercent("10 20", "/sys/gpu", previous)!!

        assertNull(reset.percent)
        assertEquals(10L, reset.nextCounter?.busy)
        assertEquals(20L, reset.nextCounter?.total)
    }

    @Test
    fun malformedAndOverflowingNumbersDoNotEscapeParser() {
        assertNull(parseBusyPercent("9223372036854775808 9223372036854775809", "/sys/gpu", null))
        assertNull(parseBusyPercent("1 2 3", "/sys/gpu", null))
        assertEquals(42f, parseBusyPercent("42", "/sys/gpu", null)?.percent!!, 0.0001f)
        assertNull(parseBusyPercent("-1", "/sys/gpu", null)?.percent)
    }

    @Test
    fun utilizationValidationRejectsNonFiniteAndOutOfRangeValues() {
        assertEquals(0f, 0f.validUtilizationPercent()!!, 0f)
        assertEquals(100f, 100f.validUtilizationPercent()!!, 0f)
        assertNull(Float.NaN.validUtilizationPercent())
        assertNull(Float.POSITIVE_INFINITY.validUtilizationPercent())
        assertNull((-0.01f).validUtilizationPercent())
        assertNull(100.01f.validUtilizationPercent())
    }

    @Test
    fun customConfigIsAllowlistedAndBounded() {
        val parsed = parseCustomProbeConfig(
            listOf(
                "gpu_busy=/sys/class/kgsl/kgsl-3d0/gpubusy",
                "dpu_busy=/proc/vendor/dpu_busy",
                "unknown=/sys/secret",
                "bus_busy=/sys/../data/not-allowed",
            ),
        )

        assertEquals(
            mapOf(
                "gpu_busy" to "/sys/class/kgsl/kgsl-3d0/gpubusy",
                "dpu_busy" to "/proc/vendor/dpu_busy",
            ),
            parsed,
        )
        assertTrue(parseCustomProbeConfig(List(129) { "# $it" }).isEmpty())
        assertTrue(parseCustomProbeConfig(listOf("x".repeat(1_025))).isEmpty())
    }
}
