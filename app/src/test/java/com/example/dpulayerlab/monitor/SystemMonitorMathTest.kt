package com.example.dpulayerlab.monitor

import com.example.dpulayerlab.model.Gauge
import com.example.dpulayerlab.model.MetricQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemMonitorMathTest {
    @Test
    fun forcedCompositionProbeBypassesFreshCacheAndCadence() {
        val evidence = CompositionEvidence(
            snapshot = CompositionSnapshot(
                deviceLayers = 2,
                clientLayers = 0,
                source = "sf",
            ),
            completedMonotonicMs = 1_000L,
        )

        assertFalse(
            shouldProbeComposition(
                forceCompositionProbe = false,
                samplesUntilProbe = 2,
                evidence = evidence,
                observedMonotonicMs = 1_100L,
                maxAgeMs = HWC_COMPOSITION_EVIDENCE_MAX_AGE_MS,
            ),
        )
        assertTrue(
            shouldProbeComposition(
                forceCompositionProbe = true,
                samplesUntilProbe = 2,
                evidence = evidence,
                observedMonotonicMs = 1_100L,
                maxAgeMs = HWC_COMPOSITION_EVIDENCE_MAX_AGE_MS,
            ),
        )
        assertTrue(
            shouldProbeComposition(
                forceCompositionProbe = false,
                samplesUntilProbe = 0,
                evidence = evidence,
                observedMonotonicMs = 1_100L,
                maxAgeMs = HWC_COMPOSITION_EVIDENCE_MAX_AGE_MS,
            ),
        )
    }

    @Test
    fun activeSurfaceFlingerIsSuppressedExceptForPlanCalibrationOneShot() {
        val staleEvidence = CompositionEvidence(
            snapshot = CompositionSnapshot(
                deviceLayers = 1,
                clientLayers = 0,
                source = "SurfaceFlinger",
            ),
            completedMonotonicMs = 1_000L,
        )

        assertFalse(
            shouldRunSurfaceFlingerCompositionProbe(
                policy = SurfaceFlingerProbePolicy.SUPPRESS_DURING_LOAD,
                samplesUntilProbe = 0,
                evidence = staleEvidence,
                observedMonotonicMs = 10_000L,
                maxAgeMs = HWC_COMPOSITION_EVIDENCE_MAX_AGE_MS,
                vendorCompositionPathReady = false,
            ),
        )
        assertFalse(
            shouldRunSurfaceFlingerCompositionProbe(
                policy = SurfaceFlingerProbePolicy.TYPED_BOUNDARY,
                samplesUntilProbe = 2,
                evidence = staleEvidence,
                observedMonotonicMs = 10_000L,
                maxAgeMs = HWC_COMPOSITION_EVIDENCE_MAX_AGE_MS,
                vendorCompositionPathReady = false,
            ),
        )
        assertTrue(
            shouldRunSurfaceFlingerCompositionProbe(
                policy = SurfaceFlingerProbePolicy.CALIBRATION_ONESHOT,
                samplesUntilProbe = 2,
                evidence = staleEvidence,
                observedMonotonicMs = 10_000L,
                maxAgeMs = HWC_COMPOSITION_EVIDENCE_MAX_AGE_MS,
                vendorCompositionPathReady = false,
            ),
        )
        assertTrue(
            shouldDiscardCachedSurfaceFlingerEvidence(
                SurfaceFlingerProbePolicy.TYPED_BOUNDARY,
            ),
        )
        assertTrue(
            shouldDiscardCachedSurfaceFlingerEvidence(
                SurfaceFlingerProbePolicy.SUPPRESS_DURING_LOAD,
            ),
        )
        assertFalse(
            shouldDiscardCachedSurfaceFlingerEvidence(
                SurfaceFlingerProbePolicy.CALIBRATION_ONESHOT,
            ),
        )
    }

    @Test
    fun verifiedCurrentVendorCompositionAvoidsRedundantSurfaceFlingerProbe() {
        assertTrue(
            vendorCompositionPathCanReplaceSurfaceFlinger(
                verifiedVendorCompositionSession = 7L,
                currentVendorServiceSession = 7L,
            ),
        )
        assertFalse(
            shouldRunSurfaceFlingerCompositionProbe(
                policy = SurfaceFlingerProbePolicy.TYPED_BOUNDARY,
                samplesUntilProbe = 0,
                evidence = null,
                observedMonotonicMs = 1_000L,
                maxAgeMs = HWC_COMPOSITION_EVIDENCE_MAX_AGE_MS,
                vendorCompositionPathReady = true,
            ),
        )
        assertTrue(
            shouldRunSurfaceFlingerCompositionProbe(
                policy = SurfaceFlingerProbePolicy.CALIBRATION_ONESHOT,
                samplesUntilProbe = 2,
                evidence = null,
                observedMonotonicMs = 1_000L,
                maxAgeMs = HWC_COMPOSITION_EVIDENCE_MAX_AGE_MS,
                vendorCompositionPathReady = true,
            ),
        )
        assertFalse(
            vendorCompositionPathCanReplaceSurfaceFlinger(
                verifiedVendorCompositionSession = 7L,
                currentVendorServiceSession = 8L,
            ),
        )
        assertFalse(
            vendorCompositionPathCanReplaceSurfaceFlinger(
                verifiedVendorCompositionSession = 7L,
                currentVendorServiceSession = null,
            ),
        )
        assertFalse(
            vendorCompositionPathCanReplaceSurfaceFlinger(
                verifiedVendorCompositionSession = -1L,
                currentVendorServiceSession = -1L,
            ),
        )
    }

    @Test
    fun vendorCompositionShortcutRequiresOneCompletePairFromCurrentSession() {
        assertEquals(
            9L,
            nextVerifiedVendorCompositionSession(
                snapshotServiceSession = 9L,
                currentServiceSession = 9L,
                deviceLayers = 4,
                clientLayers = 0,
            ),
        )
        listOf(
            nextVerifiedVendorCompositionSession(9L, 10L, 4, 0),
            nextVerifiedVendorCompositionSession(9L, 9L, null, 0),
            nextVerifiedVendorCompositionSession(9L, 9L, 4, null),
            nextVerifiedVendorCompositionSession(9L, 9L, -1, 0),
            nextVerifiedVendorCompositionSession(null, 9L, 4, 0),
            nextVerifiedVendorCompositionSession(-1L, -1L, 4, 0),
        ).forEach { session -> assertNull(session) }
        assertThrows(IllegalArgumentException::class.java) {
            shouldRunSurfaceFlingerCompositionProbe(
                policy = SurfaceFlingerProbePolicy.SUPPRESS_DURING_LOAD,
                samplesUntilProbe = 0,
                evidence = null,
                observedMonotonicMs = -1L,
                maxAgeMs = HWC_COMPOSITION_EVIDENCE_MAX_AGE_MS,
                vendorCompositionPathReady = true,
            )
        }
    }

    @Test
    fun hwcCompositionPairPrefersCompleteVendorEvidenceAtomically() {
        val surface = CompositionEvidenceProjection(
            snapshot = CompositionSnapshot(
                deviceLayers = 2,
                clientLayers = 1,
                source = "SurfaceFlinger",
            ),
            completedMonotonicMs = 900L,
            ageMs = 100L,
        )

        val selected = selectHwcCompositionEvidence(
            vendorDeviceLayers = 5,
            vendorClientLayers = 0,
            vendorSource = "vendor session=7",
            vendorCompletedMonotonicMs = 950L,
            surfaceFlinger = surface,
            observedMonotonicMs = 1_000L,
            maxAgeMs = HWC_COMPOSITION_EVIDENCE_MAX_AGE_MS,
        )

        assertEquals(5, selected.deviceLayers)
        assertEquals(0, selected.clientLayers)
        assertEquals(MetricQuality.HARDWARE_COUNTER, selected.quality)
        assertEquals("vendor session=7", selected.source)
        assertEquals(950L, selected.completedMonotonicMs)
        assertEquals(50L, selected.ageMs)
    }

    @Test
    fun partialVendorCompositionFallsBackToCompleteSurfaceFlingerPairWithoutMixing() {
        val surface = CompositionEvidenceProjection(
            snapshot = CompositionSnapshot(
                deviceLayers = 3,
                clientLayers = 2,
                source = "SurfaceFlinger",
            ),
            completedMonotonicMs = 900L,
            ageMs = 100L,
        )

        listOf(
            7 to null,
            null to 4,
            -1 to 4,
            7 to -1,
        ).forEach { (vendorDevice, vendorClient) ->
            val selected = selectHwcCompositionEvidence(
                vendorDeviceLayers = vendorDevice,
                vendorClientLayers = vendorClient,
                vendorSource = "vendor",
                vendorCompletedMonotonicMs = 950L,
                surfaceFlinger = surface,
                observedMonotonicMs = 1_000L,
                maxAgeMs = HWC_COMPOSITION_EVIDENCE_MAX_AGE_MS,
            )

            assertEquals(3, selected.deviceLayers)
            assertEquals(2, selected.clientLayers)
            assertEquals(MetricQuality.SYSTEM_SERVICE, selected.quality)
            assertEquals("SurfaceFlinger", selected.source)
            assertEquals(900L, selected.completedMonotonicMs)
            assertEquals(100L, selected.ageMs)
        }
    }

    @Test
    fun staleOrUnprovenVendorCompositionFallsBackToOneFreshSurfaceFlingerPair() {
        val surface = CompositionEvidenceProjection(
            snapshot = CompositionSnapshot(
                deviceLayers = 4,
                clientLayers = 1,
                source = "SurfaceFlinger",
            ),
            completedMonotonicMs = 3_500L,
            ageMs = 500L,
        )

        listOf(
            "stale" to 1_000L,
            "future" to 4_001L,
            "" to 3_900L,
        ).forEach { (vendorSource, vendorCompletedMs) ->
            val selected = selectHwcCompositionEvidence(
                vendorDeviceLayers = 8,
                vendorClientLayers = 0,
                vendorSource = vendorSource,
                vendorCompletedMonotonicMs = vendorCompletedMs,
                surfaceFlinger = surface,
                observedMonotonicMs = 4_000L,
                maxAgeMs = HWC_COMPOSITION_EVIDENCE_MAX_AGE_MS,
            )

            assertEquals(4, selected.deviceLayers)
            assertEquals(1, selected.clientLayers)
            assertEquals(MetricQuality.SYSTEM_SERVICE, selected.quality)
            assertEquals("SurfaceFlinger", selected.source)
            assertEquals(3_500L, selected.completedMonotonicMs)
            assertEquals(500L, selected.ageMs)
        }
    }

    @Test
    fun incompleteOrStaleCompositionPairsRemainUnavailableInsteadOfZero() {
        val partialSurface = CompositionEvidenceProjection(
            snapshot = CompositionSnapshot(
                deviceLayers = 3,
                clientLayers = null,
                source = "SurfaceFlinger",
            ),
            completedMonotonicMs = 900L,
            ageMs = 100L,
        )
        val unavailable = selectHwcCompositionEvidence(
            vendorDeviceLayers = 7,
            vendorClientLayers = null,
            vendorSource = "vendor",
            vendorCompletedMonotonicMs = 950L,
            surfaceFlinger = partialSurface,
            observedMonotonicMs = 1_000L,
            maxAgeMs = HWC_COMPOSITION_EVIDENCE_MAX_AGE_MS,
        )

        assertNull(unavailable.deviceLayers)
        assertNull(unavailable.clientLayers)
        assertEquals(MetricQuality.UNAVAILABLE, unavailable.quality)
        assertEquals("", unavailable.source)
        assertNull(unavailable.completedMonotonicMs)
        assertNull(unavailable.ageMs)

        val stale = selectHwcCompositionEvidence(
            vendorDeviceLayers = 0,
            vendorClientLayers = 0,
            vendorSource = "vendor",
            vendorCompletedMonotonicMs = 1_000L,
            surfaceFlinger = partialSurface,
            observedMonotonicMs = 4_000L,
            maxAgeMs = HWC_COMPOSITION_EVIDENCE_MAX_AGE_MS,
        )
        assertNull(stale.deviceLayers)
        assertNull(stale.clientLayers)
    }

    @Test
    fun surfaceFlingerEvidenceHasIndependentAgeAndBecomesGapBeforeFourSeconds() {
        val evidence = CompositionEvidence(
            snapshot = CompositionSnapshot(
                deviceLayers = 3,
                clientLayers = 1,
                hwcMissedFrames = 7L,
                source = "dumpsys SurfaceFlinger --hwclayers",
            ),
            completedMonotonicMs = 1_000L,
        )
        val fresh = projectCompositionEvidence(
            evidence = evidence,
            observedMonotonicMs = 1_000L + SURFACE_FLINGER_EVIDENCE_MAX_AGE_MS,
            maxAgeMs = SURFACE_FLINGER_EVIDENCE_MAX_AGE_MS,
        )

        assertEquals(3, fresh.snapshot.deviceLayers)
        assertEquals(7L, fresh.snapshot.hwcMissedFrames)
        assertEquals(1_000L, fresh.completedMonotonicMs)
        assertEquals(SURFACE_FLINGER_EVIDENCE_MAX_AGE_MS, fresh.ageMs)
        assertTrue(
            !compositionEvidenceNeedsRefresh(
                evidence = evidence,
                observedMonotonicMs = 1_000L + SURFACE_FLINGER_EVIDENCE_MAX_AGE_MS,
                maxAgeMs = SURFACE_FLINGER_EVIDENCE_MAX_AGE_MS,
            ),
        )

        val stale = projectCompositionEvidence(
            evidence = evidence,
            observedMonotonicMs = 1_001L + SURFACE_FLINGER_EVIDENCE_MAX_AGE_MS,
            maxAgeMs = SURFACE_FLINGER_EVIDENCE_MAX_AGE_MS,
        )
        assertNull(stale.snapshot.deviceLayers)
        assertNull(stale.snapshot.clientLayers)
        assertNull(stale.snapshot.hwcMissedFrames)
        assertEquals(1_000L, stale.completedMonotonicMs)
        assertEquals(SURFACE_FLINGER_EVIDENCE_MAX_AGE_MS + 1L, stale.ageMs)
        assertTrue(
            compositionEvidenceNeedsRefresh(
                evidence = evidence,
                observedMonotonicMs = 1_001L + SURFACE_FLINGER_EVIDENCE_MAX_AGE_MS,
                maxAgeMs = SURFACE_FLINGER_EVIDENCE_MAX_AGE_MS,
            ),
        )
        assertTrue(SURFACE_FLINGER_EVIDENCE_MAX_AGE_MS < 4_000L)
    }

    @Test
    fun surfaceFlingerClockRegressionAndMissingEvidenceStayUnavailable() {
        val futureEvidence = CompositionEvidence(
            snapshot = CompositionSnapshot(deviceLayers = 4),
            completedMonotonicMs = 2_000L,
        )
        val regressed = projectCompositionEvidence(
            evidence = futureEvidence,
            observedMonotonicMs = 1_999L,
            maxAgeMs = SURFACE_FLINGER_EVIDENCE_MAX_AGE_MS,
        )

        assertNull(regressed.snapshot.deviceLayers)
        assertNull(regressed.ageMs)
        assertNull(
            projectCompositionEvidence(
                evidence = null,
                observedMonotonicMs = 2_000L,
                maxAgeMs = SURFACE_FLINGER_EVIDENCE_MAX_AGE_MS,
            ).completedMonotonicMs,
        )
        assertThrows(IllegalArgumentException::class.java) {
            projectCompositionEvidence(
                evidence = null,
                observedMonotonicMs = -1L,
                maxAgeMs = SURFACE_FLINGER_EVIDENCE_MAX_AGE_MS,
            )
        }
    }

    @Test
    fun cpuSourceTransitionsResetBaselineAndEmitOneUnavailableInterval() {
        var interval = evaluateCpuCounterInterval(
            source = CpuCounterSource.HARDWARE_PROPERTIES,
            current = CpuTimes(idle = 40L, total = 100L, participantCount = 4),
            previous = null,
        )
        assertNull(interval.percent)

        interval = evaluateCpuCounterInterval(
            source = CpuCounterSource.HARDWARE_PROPERTIES,
            current = CpuTimes(idle = 60L, total = 200L, participantCount = 4),
            previous = interval.nextBaseline,
        )
        assertEquals(80f, interval.percent!!, 0.0001f)

        interval = evaluateCpuCounterInterval(
            source = CpuCounterSource.PROC_STAT,
            current = CpuTimes(idle = 500L, total = 1_000L),
            previous = interval.nextBaseline,
        )
        assertNull(interval.percent)
        assertEquals(CpuCounterSource.PROC_STAT, interval.nextBaseline?.source)

        interval = evaluateCpuCounterInterval(
            source = CpuCounterSource.PROC_STAT,
            current = CpuTimes(idle = 550L, total = 1_100L),
            previous = interval.nextBaseline,
        )
        assertEquals(50f, interval.percent!!, 0.0001f)

        interval = evaluateCpuCounterInterval(
            source = CpuCounterSource.HARDWARE_PROPERTIES,
            current = CpuTimes(idle = 80L, total = 300L, participantCount = 4),
            previous = interval.nextBaseline,
        )
        assertNull(interval.percent)
        assertEquals(CpuCounterSource.HARDWARE_PROPERTIES, interval.nextBaseline?.source)
    }

    @Test
    fun unavailableCpuReadClearsBaselineBeforeRecovery() {
        val baseline = CpuCounterBaseline(
            source = CpuCounterSource.PROC_STAT,
            times = CpuTimes(idle = 100L, total = 200L),
        )
        val gap = evaluateCpuCounterInterval(
            source = CpuCounterSource.PROC_STAT,
            current = null,
            previous = baseline,
        )
        assertNull(gap.percent)
        assertNull(gap.nextBaseline)

        val recovered = evaluateCpuCounterInterval(
            source = CpuCounterSource.PROC_STAT,
            current = CpuTimes(idle = 200L, total = 400L),
            previous = gap.nextBaseline,
        )
        assertNull(recovered.percent)
        assertEquals(CpuCounterSource.PROC_STAT, recovered.nextBaseline?.source)
    }

    @Test
    fun completedEvidenceTimestampUsesTerminalClockAndRejectsInvalidInput() {
        assertEquals(
            4_000L,
            completedEvidenceMonotonicMs(
                sampleStartedMs = 1_000L,
                evidenceCompletedMs = 4_000L,
            ),
        )
        // A defensive clamp preserves monotonic ordering if a hostile/test clock regresses.
        assertEquals(
            4_000L,
            completedEvidenceMonotonicMs(
                sampleStartedMs = 4_000L,
                evidenceCompletedMs = 3_999L,
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            completedEvidenceMonotonicMs(
                sampleStartedMs = -1L,
                evidenceCompletedMs = 0L,
            )
        }
    }

    @Test
    fun vendorFrequencyIsConvertedFromExplicitHzWithoutMagnitudeGuessing() {
        assertEquals(
            850f,
            normalizedVendorFrequencyMhz(850_000_000L)!!,
            0.0001f,
        )
        assertEquals(0f, normalizedVendorFrequencyMhz(0L)!!, 0f)
        assertNull(normalizedVendorFrequencyMhz(-1L))
        assertNull(normalizedVendorFrequencyMhz(Long.MAX_VALUE))
    }

    @Test
    fun validVendorHardwareGaugeWinsAndInvalidVendorDataFallsBack() {
        val kernel = Gauge(
            value = 25f,
            unit = "%",
            quality = MetricQuality.KERNEL,
            source = "/sys/gpu",
        )
        val source = vendorServiceSource(apiVersion = 2, serviceSession = 9L)
        val hardware = preferHardwareCounterGauge(
            value = 75f,
            unit = "%",
            source = source,
            fallback = kernel,
        )

        assertEquals(75f, hardware.value!!, 0f)
        assertEquals(MetricQuality.HARDWARE_COUNTER, hardware.quality)
        assertEquals(source, hardware.source)
        assertTrue(hardware.source.contains("v2"))
        assertTrue(hardware.source.contains("session=9"))
        assertEquals(
            kernel,
            preferHardwareCounterGauge(
                value = Float.NaN,
                unit = "%",
                source = source,
                fallback = kernel,
            ),
        )
        assertEquals(
            kernel,
            preferHardwareCounterGauge(
                value = 75f,
                unit = "%",
                source = "",
                fallback = kernel,
            ),
        )
    }

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
