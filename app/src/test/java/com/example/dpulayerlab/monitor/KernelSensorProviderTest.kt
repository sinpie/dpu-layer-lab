package com.example.dpulayerlab.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KernelSensorProviderTest {
    @Test
    fun exactUnderrunDefaultsNeverUseAnUnscopedDrmDeviceCounter() {
        assertEquals(
            listOf(
                "/sys/class/dpu/dpu0/underrun_count",
                "/sys/class/dpu/dpu0/underrun_cnt",
            ),
            KernelSensorProvider.DEFAULT_DPU_UNDERRUN_PROBES,
        )
        assertFalse(
            KernelSensorProvider.DEFAULT_DPU_UNDERRUN_PROBES.any {
                it.startsWith("/sys/class/drm/")
            },
        )
    }

    @Test
    fun customProbeConfigIsConfinedToKeySpecificSysfsNamespaces() {
        val parsed = parseCustomProbeConfig(
            listOf(
                "gpu_busy_percent=/sys/class/drm/card1/device/gpu_busy_percent",
                "bus_busy=/sys/class/devfreq/dmc/load",
                "dpu_underrun=/sys/class/dpu/dpu0/underrun_count",
                "gpu_frequency_index_khz=/sys/kernel/debug/ged/hal/current_freqency",
                "gpu_busy_window=/proc/uptime",
                "bus_busy=/sys/class/kgsl/kgsl-3d0/gpubusy",
                "dpu_busy=/sys/devices/platform/unsafe/readable_scalar",
                "gpu_frequency_hz=/sys/class/kgsl/../secret",
            ),
        )

        assertEquals(
            "/sys/class/drm/card1/device/gpu_busy_percent",
            parsed["gpu_busy_percent"],
        )
        assertEquals("/sys/class/devfreq/dmc/load", parsed["bus_busy"])
        assertEquals(
            "/sys/class/dpu/dpu0/underrun_count",
            parsed["dpu_underrun"],
        )
        assertEquals(
            "/sys/kernel/debug/ged/hal/current_freqency",
            parsed["gpu_frequency_index_khz"],
        )
        assertNull(parsed["gpu_busy_window"])
        assertNull(parsed["dpu_busy"])
        assertNull(parsed["gpu_frequency_hz"])
    }

    @Test
    fun directBusyPercentAcceptsDecimalButNotCounterPairs() {
        assertEquals(37.5f, parseDirectUtilizationPercent("37.5 %")!!, 0.0001f)
        assertEquals(0f, parseDirectUtilizationPercent("0")!!, 0.0001f)
        assertNull(parseDirectUtilizationPercent("23 100"))
        assertNull(parseDirectUtilizationPercent("-1"))
        assertNull(parseDirectUtilizationPercent("101"))
        assertNull(parseDirectUtilizationPercent("NaN"))
        assertNull(parseDirectUtilizationPercent("busy 20"))
        assertNull(parseDirectUtilizationPercent("1.5 20"))
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
    fun failureBeforeKernelReadDropsEveryPreviouslyPublishedCumulativeBaseline() {
        val baselines = KernelBusyCounterBaselines().apply {
            gpu = BusyCounterState(100L, 200L, "gpu")
            bus = BusyCounterState(40L, 100L, "bus")
            dpu = BusyCounterState(20L, 100L, "dpu")
        }

        // SystemMonitor observes a failed outer sample before KernelSensorProvider.sample().
        baselines.reset()

        assertNull(parseBusyPercent("150 300", "gpu", baselines.gpu)!!.percent)
        assertNull(parseBusyPercent("60 140", "bus", baselines.bus)!!.percent)
        assertNull(parseBusyPercent("30 140", "dpu", baselines.dpu)!!.percent)
    }

    @Test
    fun failureAfterKernelReadDropsTheUnpublishedCumulativeAdvance() {
        val baselines = KernelBusyCounterBaselines().apply {
            gpu = BusyCounterState(100L, 200L, "gpu")
            bus = BusyCounterState(40L, 100L, "bus")
            dpu = BusyCounterState(20L, 100L, "dpu")
        }

        // The failed sample advanced private state but never published its TelemetrySnapshot.
        baselines.gpu = parseBusyPercent("150 300", "gpu", baselines.gpu)!!.nextCounter
        baselines.bus = parseBusyPercent("60 140", "bus", baselines.bus)!!.nextCounter
        baselines.dpu = parseBusyPercent("30 140", "dpu", baselines.dpu)!!.nextCounter
        baselines.reset()

        // The next accepted sample is a new baseline, not an average across the invisible read.
        assertNull(parseBusyPercent("175 350", "gpu", baselines.gpu)!!.percent)
        assertNull(parseBusyPercent("70 160", "bus", baselines.bus)!!.percent)
        assertNull(parseBusyPercent("35 160", "dpu", baselines.dpu)!!.percent)
    }

    @Test
    fun kgslWindowBusyTotalIsCalculatedImmediatelyWithoutCumulativeBaseline() {
        assertEquals(25f, parseWindowBusyTotalPercent("25 100")!!, 0.0001f)
        assertEquals(50f, parseWindowBusyTotalPercent("1 2")!!, 0.0001f)
        assertEquals(0f, parseWindowBusyTotalPercent("0 20")!!, 0.0001f)
        assertEquals(0f, parseWindowBusyTotalPercent("0 0")!!, 0f)

        // The legacy cumulative parser intentionally needs a prior sample. This guards
        // against accidentally routing KGSL gpubusy through that parser again.
        assertNull(parseBusyPercent("25 100", "/sys/kgsl/gpubusy", null)?.percent)
    }

    @Test
    fun kgslWindowBusyTotalRejectsMalformedOrAmbiguousValues() {
        assertNull(parseWindowBusyTotalPercent("1 0"))
        assertNull(parseWindowBusyTotalPercent("11 10"))
        assertNull(parseWindowBusyTotalPercent("-1 10"))
        assertNull(parseWindowBusyTotalPercent("1 -10"))
        assertNull(parseWindowBusyTotalPercent("1"))
        assertNull(parseWindowBusyTotalPercent("1 2 3"))
        assertNull(parseWindowBusyTotalPercent("busy=1 total=2"))
        assertNull(parseWindowBusyTotalPercent("1.0 2.0"))
        assertNull(parseWindowBusyTotalPercent("9223372036854775808 9223372036854775809"))
    }

    @Test
    fun mtkGedUtilizationUsesLoadingAndValidatesEveryField() {
        assertEquals(73f, parseMtkGpuUtilizationPercent("73 9 27")!!, 0f)
        // The three GED fields are independent; requiring a guessed sum would reject
        // valid product implementations.
        assertEquals(40f, parseMtkGpuUtilizationPercent("40 80 20")!!, 0f)
        assertEquals(0f, parseMtkGpuUtilizationPercent("0 0 100")!!, 0f)

        assertNull(parseMtkGpuUtilizationPercent("73 27"))
        assertNull(parseMtkGpuUtilizationPercent("73 9 27 0"))
        assertNull(parseMtkGpuUtilizationPercent("101 0 0"))
        assertNull(parseMtkGpuUtilizationPercent("1 -1 100"))
        assertNull(parseMtkGpuUtilizationPercent("loading 73 blocking 9 idle 27"))
        assertNull(parseMtkGpuUtilizationPercent("73.0 9 27"))
        assertNull(parseMtkGpuUtilizationPercent("9223372036854775808 0 0"))
    }

    @Test
    fun mtkGedIndexedFrequencySelectsSecondKiloHertzField() {
        assertEquals(850_000L, parseIndexedGpuFrequency("3 850000"))
        assertEquals(0L, parseIndexedGpuFrequency("0 0"))

        assertNull(parseIndexedGpuFrequency("-1 850000"))
        assertNull(parseIndexedGpuFrequency("3 -1"))
        assertNull(parseIndexedGpuFrequency("850000"))
        assertNull(parseIndexedGpuFrequency("3 850000 99"))
        assertNull(parseIndexedGpuFrequency("index=3 frequency=850000"))
        assertNull(parseIndexedGpuFrequency("3 850.0"))
        assertNull(parseIndexedGpuFrequency("3 9223372036854775808"))
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
        assertNull(parseBusyPercent("busy=42", "/sys/gpu", null))
        assertNull(parseBusyPercent("1.5 20", "/sys/gpu", null))
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
                .endsWith("[input=KHZ,format=SCALAR]"),
        )
        assertTrue(
            frequencyProbeSource(
                "/sys/kernel/ged/hal/current_freqency",
                ProbeFrequencyUnit.KHZ,
                GpuFrequencyProbeFormat.INDEX_AND_FREQUENCY,
            ).endsWith("[input=KHZ,format=INDEX_AND_FREQUENCY]"),
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
        assertEquals(GpuFrequencyProbeFormat.SCALAR, legacy.single().format)

        val indexed = configuredGpuFrequencyProbes(
            mapOf(
                "gpu_frequency_index_khz" to
                    "/sys/kernel/ged/hal/current_freqency",
            ),
        )
        assertEquals(ProbeFrequencyUnit.KHZ, indexed.single().unit)
        assertEquals(
            GpuFrequencyProbeFormat.INDEX_AND_FREQUENCY,
            indexed.single().format,
        )

        val conflict = configuredGpuFrequencyProbes(
            mapOf(
                "gpu_frequency_hz" to "/sys/vendor/gpu_clock",
                "gpu_frequency_khz" to "/sys/vendor/gpu_clock",
            ),
        )
        assertEquals(2, conflict.size)
    }

    @Test
    fun configuredGpuBusyKeysKeepTheirTypedMeaningsAndProvenance() {
        val window = configuredGpuBusyProbes(
            mapOf("gpu_busy_window" to "/sys/class/kgsl/kgsl-3d0/gpubusy"),
        ).single()
        assertEquals(GpuBusyProbeFormat.WINDOW_BUSY_TOTAL, window.format)
        assertTrue(
            busyProbeSource(window.path, window.format)
                .endsWith("[format=WINDOW_BUSY_TOTAL]"),
        )

        val mtk = configuredGpuBusyProbes(
            mapOf(
                "gpu_busy_mtk_triplet" to
                    "/sys/kernel/ged/hal/gpu_utilization",
            ),
        ).single()
        assertEquals(GpuBusyProbeFormat.MTK_LOADING_BLOCKING_IDLE, mtk.format)

        val legacy = configuredGpuBusyProbes(
            mapOf("gpu_busy" to "/sys/vendor/gpu_busy"),
        ).single()
        assertEquals(GpuBusyProbeFormat.LEGACY_DIRECT_OR_CUMULATIVE, legacy.format)

        val contradictory = configuredGpuBusyProbes(
            mapOf(
                "gpu_busy" to "/sys/vendor/gpu_busy",
                "gpu_busy_window" to "/sys/vendor/gpu_busy",
            ),
        )
        assertEquals(2, contradictory.size)
    }

    @Test
    fun explicitGenericProbePathIsAuthoritativeAndNeverAppendsDefaults() {
        assertEquals(
            listOf("/sys/product/dpu_busy"),
            authoritativeProbePaths(
                explicit = "/sys/product/dpu_busy",
                defaults = listOf("/sys/default/dpu0", "/sys/default/dpu1"),
            ),
        )
        assertEquals(
            listOf("/sys/default/dpu0", "/sys/default/dpu1"),
            authoritativeProbePaths(
                explicit = null,
                defaults = listOf("/sys/default/dpu0", "/sys/default/dpu1"),
            ),
        )
    }

    @Test
    fun unavailableProvenanceKeepsExplicitTypedPathAndConflictsVisible() {
        val xclipsePath = "/sys/class/drm/card0/device/gpu_busy_percent"
        val busySource = gpuBusyUnavailableSource(
            mapOf("gpu_busy_percent" to xclipsePath),
        )
        assertTrue(busySource.contains(xclipsePath))
        assertTrue(busySource.contains("DIRECT_PERCENT"))
        assertTrue(busySource.contains("unavailable-or-malformed"))

        val frequencySource = gpuFrequencyUnavailableSource(
            mapOf(
                "gpu_frequency_hz" to "/sys/vendor/gpu_clock",
                "gpu_frequency_khz" to "/sys/vendor/gpu_clock",
            ),
        )
        assertTrue(frequencySource.contains("conflicting"))
        assertTrue(frequencySource.contains("input=HZ"))
        assertTrue(frequencySource.contains("input=KHZ"))

        assertTrue(
            unavailableExplicitProbeSource(
                label = "DPU busy",
                explicitPath = "/sys/vendor/dpu_busy",
                genericSource = "generic",
            ).contains("/sys/vendor/dpu_busy"),
        )
    }

    @Test
    fun explicitGpuProbesAreAuthoritativeAndConflictsFailClosed() {
        val busyDefault = GpuBusyProbe(
            "/sys/default/gpu_busy",
            GpuBusyProbeFormat.DIRECT_PERCENT,
        )
        val busyExplicit = GpuBusyProbe(
            "/sys/product/gpubusy",
            GpuBusyProbeFormat.WINDOW_BUSY_TOTAL,
        )
        assertEquals(
            listOf(busyDefault),
            selectGpuBusyProbes(emptyList(), listOf(busyDefault)),
        )
        assertEquals(
            listOf(busyExplicit),
            selectGpuBusyProbes(listOf(busyExplicit), listOf(busyDefault)),
        )
        assertNull(
            selectGpuBusyProbes(
                listOf(busyExplicit, busyExplicit.copy(path = "/sys/product/other")),
                listOf(busyDefault),
            ),
        )

        val frequencyDefault = GpuFrequencyProbe(
            "/sys/default/gpu_clock",
            ProbeFrequencyUnit.MHZ,
            GpuFrequencyProbeFormat.SCALAR,
        )
        val frequencyExplicit = ConfiguredGpuFrequencyProbe(
            "/sys/product/current_freqency",
            ProbeFrequencyUnit.KHZ,
            GpuFrequencyProbeFormat.INDEX_AND_FREQUENCY,
        )
        assertEquals(
            listOf(frequencyDefault),
            selectGpuFrequencyProbes(emptyList(), listOf(frequencyDefault)),
        )
        assertEquals(
            listOf(frequencyExplicit.asProbe()),
            selectGpuFrequencyProbes(
                listOf(frequencyExplicit),
                listOf(frequencyDefault),
            ),
        )
        assertNull(
            selectGpuFrequencyProbes(
                listOf(frequencyExplicit, frequencyExplicit),
                listOf(frequencyDefault),
            ),
        )
    }

    @Test
    fun customConfigIsAllowlistedAndBounded() {
        val parsed = parseCustomProbeConfig(
            listOf(
                "gpu_busy=/sys/class/kgsl/kgsl-3d0/gpubusy",
                "gpu_busy_percent=/sys/class/drm/card0/device/gpu_busy_percent",
                "gpu_busy_window=/sys/class/kgsl/kgsl-3d0/gpubusy",
                "gpu_busy_mtk_triplet=/sys/kernel/ged/hal/gpu_utilization",
                "gpu_frequency_khz=/sys/vendor/gpu_clock",
                "gpu_frequency_index_khz=/sys/kernel/ged/hal/current_freqency",
                "dpu_busy=/proc/vendor/dpu_busy",
                "dpu_frequency_hz=/sys/vendor/dpu/cur_freq",
                "unknown=/sys/secret",
                "bus_busy=/sys/../data/not-allowed",
            ),
        )

        assertEquals(
            mapOf(
                "gpu_busy" to "/sys/class/kgsl/kgsl-3d0/gpubusy",
                "gpu_busy_percent" to "/sys/class/drm/card0/device/gpu_busy_percent",
                "gpu_busy_window" to "/sys/class/kgsl/kgsl-3d0/gpubusy",
                "gpu_busy_mtk_triplet" to "/sys/kernel/ged/hal/gpu_utilization",
                "gpu_frequency_index_khz" to "/sys/kernel/ged/hal/current_freqency",
            ),
            parsed,
        )
        assertTrue(parseCustomProbeConfig(List(129) { "# $it" }).isEmpty())
        assertTrue(parseCustomProbeConfig(listOf("x".repeat(1_025))).isEmpty())
    }

    @Test
    fun defaultGpuProbesPreferArchitectureSpecificXclipseAndGedBeforeGenericSki() {
        val paths = KernelSensorProvider.DEFAULT_GPU_BUSY_PROBES.map(GpuBusyProbe::path)
        val xclipse = paths.indexOf("/sys/class/drm/card0/device/gpu_busy_percent")
        val mtk = paths.indexOf("/sys/module/ged/parameters/gpu_loading")
        val legacyMali = paths.indexOf("/sys/class/misc/mali0/device/utilization")
        val genericSki = paths.indexOf("/sys/kernel/gpu/gpu_busy")

        assertTrue(xclipse >= 0)
        assertTrue(mtk > xclipse)
        assertTrue(legacyMali > xclipse)
        assertTrue(genericSki > mtk)
        assertTrue(genericSki > legacyMali)
    }

    @Test
    fun legacyMaliFrequencyRetainsExplicitHzContractBeforeGenericMhzNode() {
        val probes = KernelSensorProvider.DEFAULT_GPU_FREQUENCY_PROBES
        val maliIndex = probes.indexOfFirst {
            it.path == "/sys/class/misc/mali0/device/devfreq/devfreq0/cur_freq"
        }
        val genericIndex = probes.indexOfFirst {
            it.path == "/sys/kernel/gpu/gpu_clock"
        }

        assertTrue(maliIndex >= 0)
        assertTrue(genericIndex > maliIndex)
        assertEquals(ProbeFrequencyUnit.HZ, probes[maliIndex].unit)
        assertEquals(ProbeFrequencyUnit.MHZ, probes[genericIndex].unit)
    }

    @Test
    fun legacyBusyObservedEncodingChangesProvenanceAndInvalidatesCounterBaseline() {
        val path = "/sys/vendor/gpu_busy"
        val directSource = legacyObservedBusyProbeSource(
            path,
            BusyObservedEncoding.DIRECT_PERCENT,
        )
        val cumulativeSource = legacyObservedBusyProbeSource(
            path,
            BusyObservedEncoding.CUMULATIVE_BUSY_TOTAL,
        )
        assertTrue(directSource != cumulativeSource)
        assertTrue(directSource.contains("observed=DIRECT_PERCENT"))
        assertTrue(cumulativeSource.contains("observed=CUMULATIVE_BUSY_TOTAL"))

        val previous = BusyCounterState(10L, 20L, directSource)
        val changedEncoding = parseBusyPercent("20 40", cumulativeSource, previous)!!
        assertNull(changedEncoding.percent)
        assertEquals(cumulativeSource, changedEncoding.nextCounter?.source)
    }
}
