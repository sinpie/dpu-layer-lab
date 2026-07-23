package com.example.dpulayerlab.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KernelSensorProviderTest {
    @Test
    fun directBusyPercentAcceptsDecimalButNotCounterPairs() {
        assertEquals(37.5f, parseDirectUtilizationPercent("37.5 %")!!, 0.0001f)
        assertEquals(0f, parseDirectUtilizationPercent("0")!!, 0.0001f)
        assertNull(parseDirectUtilizationPercent("23 100"))
        assertNull(parseDirectUtilizationPercent("-1"))
        assertNull(parseDirectUtilizationPercent("101"))
        assertNull(parseDirectUtilizationPercent("NaN"))
    }

    @Test
    fun scalarCounterParserRejectsAmbiguousAndOverflowingLines() {
        assertEquals(42L, parseSingleLongToken(" 42\n"))
        assertEquals(42L, parseSingleLongToken("+42"))
        assertEquals(-1L, parseSingleLongToken("-1"))
        assertNull(parseSingleLongToken("underruns: 42"))
        assertNull(parseSingleLongToken("error 22"))
        assertNull(parseSingleLongToken("42 43"))
        assertNull(parseSingleLongToken("1.5"))
        assertNull(parseSingleLongToken("9223372036854775808"))
    }

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
    fun dpuFrequencyValidationRejectsNegativeAndImplausibleValues() {
        assertEquals(0L, 0L.validDpuFrequencyHz())
        assertEquals(2_000_000_000L, 2_000_000_000L.validDpuFrequencyHz())
        assertNull((-1L).validDpuFrequencyHz())
        assertNull(Long.MAX_VALUE.validDpuFrequencyHz())
    }

    @Test
    fun gpuFrequencyNormalizationUsesExplicitUnitsAndRejectsImplausibleValues() {
        assertEquals(
            850f,
            normalizeGpuFrequencyMhz(850_000_000L, ProbeFrequencyUnit.HZ)!!,
            0.0001f,
        )
        assertEquals(
            800f,
            normalizeGpuFrequencyMhz(800_000L, ProbeFrequencyUnit.KHZ)!!,
            0.0001f,
        )
        assertEquals(
            850f,
            normalizeGpuFrequencyMhz(850L, ProbeFrequencyUnit.MHZ)!!,
            0f,
        )
        assertNull(normalizeGpuFrequencyMhz(Long.MAX_VALUE, ProbeFrequencyUnit.HZ))
        assertNull(normalizeGpuFrequencyMhz(20_001L, ProbeFrequencyUnit.MHZ))
        assertNull(normalizeGpuFrequencyMhz(-1L, ProbeFrequencyUnit.KHZ))
        assertTrue(
            frequencyProbeSource("/sys/vendor/gpu_clock", ProbeFrequencyUnit.KHZ)
                .endsWith("[input=KHZ]"),
        )
    }

    @Test
    fun configuredGpuFrequencyKeysAreTypedAndConflictsRemainVisible() {
        val typed = configuredGpuFrequencyProbes(
            mapOf("gpu_frequency_khz" to "/sys/vendor/gpu_clock"),
        )
        assertEquals(
            listOf(
                ConfiguredGpuFrequencyProbe(
                    path = "/sys/vendor/gpu_clock",
                    unit = ProbeFrequencyUnit.KHZ,
                ),
            ),
            typed,
        )

        val legacy = configuredGpuFrequencyProbes(
            mapOf("gpu_frequency" to "/sys/vendor/legacy_clock"),
        )
        assertEquals(ProbeFrequencyUnit.HZ, legacy.single().unit)

        val conflict = configuredGpuFrequencyProbes(
            mapOf(
                "gpu_frequency_hz" to "/sys/vendor/gpu_clock",
                "gpu_frequency_khz" to "/sys/vendor/gpu_clock",
            ),
        )
        assertEquals(2, conflict.size)
    }

    @Test
    fun customConfigIsAllowlistedAndBounded() {
        val parsed = parseCustomProbeConfig(
            listOf(
                "gpu_busy=/sys/class/kgsl/kgsl-3d0/gpubusy",
                "gpu_frequency_khz=/sys/vendor/gpu_clock",
                "dpu_busy=/proc/vendor/dpu_busy",
                "dpu_frequency_hz=/sys/vendor/dpu/cur_freq",
                "unknown=/sys/secret",
                "bus_busy=/sys/../data/not-allowed",
            ),
        )

        assertEquals(
            mapOf(
                "gpu_busy" to "/sys/class/kgsl/kgsl-3d0/gpubusy",
                "gpu_frequency_khz" to "/sys/vendor/gpu_clock",
                "dpu_busy" to "/proc/vendor/dpu_busy",
                "dpu_frequency_hz" to "/sys/vendor/dpu/cur_freq",
            ),
            parsed,
        )
        assertTrue(parseCustomProbeConfig(List(129) { "# $it" }).isEmpty())
        assertTrue(parseCustomProbeConfig(listOf("x".repeat(1_025))).isEmpty())
    }
}
