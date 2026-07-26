package com.example.dpulayerlab.ui

import com.example.dpulayerlab.engine.ScenarioCatalog
import com.example.dpulayerlab.model.Gauge
import com.example.dpulayerlab.model.HwcCompositionExpectation
import com.example.dpulayerlab.model.LayerSizeProfile
import com.example.dpulayerlab.model.LayerTrafficEstimate
import com.example.dpulayerlab.model.MetricQuality
import com.example.dpulayerlab.model.PlanState
import com.example.dpulayerlab.model.RunnerStage
import com.example.dpulayerlab.model.ScenarioCategory
import com.example.dpulayerlab.model.ScenarioClassifier
import com.example.dpulayerlab.model.ScenarioCondition
import com.example.dpulayerlab.model.ScenarioLoadBand
import com.example.dpulayerlab.model.ScenarioPlanPolicy
import com.example.dpulayerlab.model.ScenarioQueueEditor
import com.example.dpulayerlab.model.ScenarioSelectionFilter
import com.example.dpulayerlab.model.TelemetrySnapshot
import com.example.dpulayerlab.monitor.HWC_COMPOSITION_EVIDENCE_MAX_AGE_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DpuLayerLabAppMathTest {
    @Test
    fun visibleVersionKeepsSourceCandidateTimestampAndVariantSuffix() {
        assertEquals(
            "BUILD 20991231_235959-debug",
            visibleAppVersion("20991231_235959-debug"),
        )
    }

    @Test
    fun visibleVersionIsBoundedAndNeverBlank() {
        assertEquals("BUILD N/A", visibleAppVersion("   "))
        assertEquals("BUILD ${"v".repeat(64)}", visibleAppVersion("v".repeat(128)))
    }

    @Test
    fun configurationRestoreKeepsOnlyUserNavigationSections() {
        assertEquals(AppSection.CATALOG, restorableUserSection(AppSection.CATALOG))
        assertEquals(AppSection.SYSTEM, restorableUserSection(AppSection.SYSTEM))
        assertEquals(AppSection.DASHBOARD, restorableUserSection(AppSection.RUN))
        assertEquals(AppSection.DASHBOARD, restorableUserSection(AppSection.RESULT))
        assertEquals(AppSection.DASHBOARD, restorableUserSection(null))
    }

    @Test
    fun controllerStateOverridesRestoredNavigationWithoutRestoringRunAsUserState() {
        assertEquals(
            AppSection.RUN,
            sectionForRunnerState(
                AppSection.CATALOG,
                RunnerStage.PRECHECK,
                PlanState.IDLE,
            ),
        )
        assertEquals(
            AppSection.RUN,
            sectionForRunnerState(
                AppSection.CATALOG,
                RunnerStage.IDLE,
                PlanState.RUNNING,
            ),
        )
        assertEquals(
            AppSection.RESULT,
            sectionForRunnerState(
                AppSection.CATALOG,
                RunnerStage.COMPLETE,
                PlanState.COMPLETE,
            ),
        )
        assertEquals(
            AppSection.CATALOG,
            sectionForRunnerState(
                AppSection.CATALOG,
                RunnerStage.IDLE,
                PlanState.REJECTED,
            ),
        )
    }

    @Test
    fun producerCountShowsPendingUntilExpectedTopologyIsCommitted() {
        assertEquals("0/\u2014P", producerCountDisplay(observed = 0, expected = 0))
        assertEquals("2/\u2014P", producerCountDisplay(observed = 2, expected = 0))
        assertEquals("2/4P", producerCountDisplay(observed = 2, expected = 4))
    }

    @Test
    fun producerCountDoesNotExposeInvalidNegativeCounts() {
        assertEquals("0/\u2014P", producerCountDisplay(observed = -1, expected = -1))
    }

    @Test
    fun flattenedLogicalLayersDoNotInflateCommittedPhysicalHudValue() {
        assertEquals("LOGICAL 20L", logicalLayerHudLabel(logicalLayers = 20))
        assertEquals(1f, committedPhysicalProducerHudValue(expected = 1))
        assertEquals("1/1P", producerCountDisplay(observed = 1, expected = 1))
    }

    @Test
    fun pendingPhysicalTopologyCreatesNullValueAndHistoryGap() {
        val expectedCounts = listOf(4, 0, 4)

        assertEquals(
            listOf(4f, null, 4f),
            expectedCounts.map { committedPhysicalProducerHudValue(expected = it) },
        )
        assertEquals(null, committedPhysicalProducerHudValue(expected = 0))
        assertEquals("2/\u2014P", producerCountDisplay(observed = 2, expected = 0))
    }

    @Test
    fun gaugeHistoryConnectsOnlyMatchingProvenance() {
        val samples = listOf(
            Gauge(10f, "%", MetricQuality.HARDWARE_COUNTER, "vendor dpu"),
            Gauge(20f, "%", MetricQuality.HARDWARE_COUNTER, "vendor dpu"),
            Gauge(30f, "%", MetricQuality.KERNEL, "sysfs busy"),
            Gauge(40f, "%", MetricQuality.KERNEL, "sysfs busy"),
        )

        assertEquals(listOf(10f, 20f, null, 40f), segmentedGaugeHistory(samples))
    }

    @Test
    fun gaugeHistoryPreservesUnavailableGapAndTimeAxis() {
        val samples = listOf(
            Gauge(10f, "%", MetricQuality.SYSTEM_SERVICE, "cpu service"),
            Gauge(null, "%", MetricQuality.UNAVAILABLE, ""),
            Gauge(30f, "%", MetricQuality.SYSTEM_SERVICE, "cpu service"),
        )

        assertEquals(listOf(10f, null, 30f), segmentedGaugeHistory(samples))
    }

    @Test
    fun gaugeProvenanceLabelIsBoundedAndExplainsUnavailableSourceWhenKnown() {
        assertEquals("N/A", gaugeProvenanceLabel(Gauge()))
        assertEquals(
            "N/A · kernel probe missi",
            gaugeProvenanceLabel(
                Gauge(source = "kernel probe missing"),
            ),
        )
        assertEquals(
            "HW · ${"x".repeat(18)}",
            gaugeProvenanceLabel(
                Gauge(
                    value = 1f,
                    quality = MetricQuality.HARDWARE_COUNTER,
                    source = "x".repeat(64),
                ),
            ),
        )
    }

    @Test
    fun purposeFiltersUseTypedClassifierConditionsAndPreserveCatalogOrder() {
        val scenarios = ScenarioCatalog.presets
        val device = scenariosForCatalogPurpose(scenarios, CatalogPurpose.DEVICE_STABLE)
        val client = scenariosForCatalogPurpose(scenarios, CatalogPurpose.CLIENT_TRANSITION)
        val burst = scenariosForCatalogPurpose(scenarios, CatalogPurpose.DPU_BURST)

        assertTrue(device.any { it.id == "dpu-device-envelope-burst" })
        assertTrue(client.any { it.id == "dpu-client-fallback-burst" })
        assertTrue(burst.any { it.id == "dpu-only-repeat-shock" })
        assertEquals(
            listOf(
                "dpu-device-envelope-burst",
                "layer-size-device-candidate",
            ),
            device.map { it.id },
        )
        assertEquals(
            listOf(
                "dpu-client-fallback-burst",
                "layer-size-client-pressure",
            ),
            client.map { it.id },
        )
        assertFalse(device.any { candidate ->
            candidate.phases.any {
                it.hwcCompositionExpectation == HwcCompositionExpectation.CLIENT_REQUIRED
            }
        })
        assertTrue(
            device.all {
                ScenarioCondition.HWC_DEVICE_ONLY in
                    com.example.dpulayerlab.model.ScenarioClassifier.conditions(it)
            },
        )
        assertTrue(
            client.all {
                ScenarioCondition.HWC_CLIENT_REQUIRED in
                    com.example.dpulayerlab.model.ScenarioClassifier.conditions(it)
            },
        )
        assertEquals(
            scenarios.filter { it in burst },
            burst,
        )
    }

    @Test
    fun purposeAndFacetRowsComposeWithAndWhileEachFacetRowUsesOr() {
        val device = checkNotNull(ScenarioCatalog.byId("dpu-device-envelope-burst"))
        val client = checkNotNull(ScenarioCatalog.byId("dpu-client-fallback-burst"))
        val clientOnly = client.copy(
            id = "client-only-test",
            phases = client.phases.filter {
                it.hwcCompositionExpectation == HwcCompositionExpectation.CLIENT_REQUIRED
            },
        )
        val wrongCategoryDevice = device.copy(
            id = "wrong-category-device",
            category = ScenarioCategory.TRANSITION,
        )
        val facetMatches = ScenarioClassifier.filter(
            listOf(device, clientOnly, wrongCategoryDevice),
            ScenarioSelectionFilter(
                categories = setOf(ScenarioCategory.LAYER_HWC),
                conditions = setOf(
                    ScenarioCondition.HWC_DEVICE_ONLY,
                    ScenarioCondition.HWC_CLIENT_REQUIRED,
                ),
            ),
        )

        assertEquals(listOf(device, clientOnly), facetMatches)
        assertEquals(
            listOf(device),
            scenariosForCatalogPurpose(facetMatches, CatalogPurpose.DEVICE_STABLE),
        )
        assertEquals(
            listOf(clientOnly),
            scenariosForCatalogPurpose(facetMatches, CatalogPurpose.CLIENT_TRANSITION),
        )
    }

    @Test
    fun dashboardQuickRunUsesPurposeRepresentativesAndTypedHwcPresets() {
        val entries = dashboardPurposeScenarios(ScenarioCatalog.presets)

        assertEquals(
            listOf("급격한 DPU 부하", "DEVICE 후보 유지", "CLIENT 전환 목표"),
            CatalogPurpose.entries.map { it.title },
        )
        assertEquals(
            listOf(
                CatalogPurpose.DPU_BURST,
                CatalogPurpose.DEVICE_STABLE,
                CatalogPurpose.CLIENT_TRANSITION,
            ),
            entries.map { it.purpose },
        )
        assertEquals(
            listOf(
                "dpu-only-repeat-shock",
                "dpu-device-envelope-burst",
                "dpu-client-fallback-burst",
            ),
            entries.map { it.scenario.id },
        )
    }

    @Test
    fun queuePreviewKeepsDuplicatesAndUsesRangesWithoutImplyingDirection() {
        val device = checkNotNull(ScenarioCatalog.byId("dpu-device-envelope-burst"))
        val client = checkNotNull(ScenarioCatalog.byId("dpu-client-fallback-burst"))

        val preview = scenarioSelectionPreview(listOf(device, client, device))

        assertEquals(3, preview.queueEntries)
        assertEquals(2, preview.uniqueScenarios)
        assertEquals(1, preview.duplicateEntries)
        assertTrue(preview.inputChange.contains("1–20L"))
        assertFalse(preview.inputChange.contains("1→20L"))
        assertEquals(
            "DEVICE 유지 검증 ↔ CLIENT 전환 검증",
            preview.compositionTarget,
        )
    }

    @Test
    fun queuePreviewDoesNotInferHwcPathWithoutTypedExpectation() {
        val baseline = checkNotNull(ScenarioCatalog.byId("baseline-display-modes"))

        val preview = scenarioSelectionPreview(listOf(baseline))

        assertEquals("HWC 자동 배정/검증 목표 없음", preview.compositionTarget)
    }

    @Test
    fun layerSizeLabelsKeepFullScreenDefaultAndBoundMixedQueueSummary() {
        assertEquals(
            "Full screen (기본)",
            layerSizeProfileUiLabel(LayerSizeProfile.FULL_SCREEN),
        )
        assertEquals(
            "Full 기본",
            layerSizeProfileUiLabel(LayerSizeProfile.FULL_SCREEN, compact = true),
        )
        assertEquals(
            "Full 기본 / Small / Mixed S/M/L +2",
            layerSizeProfileSummary(LayerSizeProfile.entries),
        )
        assertEquals("N/A", layerSizeProfileSummary(emptyList()))
    }

    @Test
    fun sizeProfileFootprintSummaryIsSeparateFromTrafficAndDisclosesScope() {
        val estimate = LayerTrafficEstimate(
            logicalLayerCount = 4,
            producerLayerCount = 4,
            bytesPerFrame = 10.0,
            dpuReadBytesPerSecond = 20.0,
            producerWriteBytesPerSecond = 30.0,
            scanoutFps = 60f,
            formatLabel = "RGBA",
            resolutionLabel = "display",
            compressionRatioExcluded = false,
            destinationFootprintScreenEquivalents = 1.375,
            destinationFootprintAveragePercent = 34.375,
            destinationFootprintLabel =
                "Mixed sizes · destination only; overlap/crop excluded",
        )

        assertEquals(
            "SIZE PROFILE FOOTPRINT 1.38× screen · avg 34%/producer · " +
                "Mixed sizes · destination only; overlap/crop excluded · traffic과 별도",
            sizeProfileFootprintSummary(estimate),
        )
        assertEquals(
            "SIZE PROFILE FOOTPRINT N/A · base scale only · traffic과 별도",
            sizeProfileFootprintSummary(null),
        )
        assertEquals(
            "SIZE PROFILE FOOTPRINT N/A · base scale only · traffic과 별도",
            sizeProfileFootprintSummary(
                estimate.copy(destinationFootprintScreenEquivalents = Double.NaN),
            ),
        )
    }

    @Test
    fun queuePreviewMakesLayerSizeProfilesVisible() {
        val gradual = checkNotNull(
            ScenarioCatalog.byId("gradual-layer-size-expansion"),
        )

        val preview = scenarioSelectionPreview(listOf(gradual))

        assertTrue(preview.inputChange.contains("크기"))
        assertTrue(preview.inputChange.contains("Small→Full"))
        assertTrue(preview.inputChange.contains("Full 기본"))
    }

    @Test
    fun collapsedAdvancedFilterSummaryKeepsActiveFacetMeaningVisible() {
        val summary = advancedFilterSummary(
            purpose = CatalogPurpose.DPU_BURST,
            categoryKeys = setOf(ScenarioCategory.LAYER_HWC.name),
            patternKeys = emptySet(),
            loadBandKeys = setOf(ScenarioLoadBand.HIGH.name, ScenarioLoadBand.VERY_HIGH.name),
            conditionKeys = setOf(ScenarioCondition.CPU.name, ScenarioCondition.MEMORY.name),
        )

        assertTrue(summary.contains("목적 급격한 DPU 부하"))
        assertTrue(summary.contains("카테고리 Layer / HWC"))
        assertTrue(summary.contains("강도 높음 OR 매우 높음"))
        assertTrue(summary.contains("조건 CPU OR Memory"))
    }

    @Test
    fun queuePreviewIsBoundedUntilTheUserExplicitlyExpandsIt() {
        assertEquals(0, queuePreviewEntryCount(queueSize = -1, expanded = false))
        assertEquals(3, queuePreviewEntryCount(queueSize = 3, expanded = false))
        assertEquals(
            COLLAPSED_QUEUE_ENTRY_LIMIT,
            queuePreviewEntryCount(queueSize = 40, expanded = false),
        )
        assertEquals(40, queuePreviewEntryCount(queueSize = 40, expanded = true))
    }

    @Test
    fun emptyCatalogQueueAlwaysResetsHiddenRepeatToOne() {
        assertEquals(1, normalizedCatalogRepeatCount(queueSize = 0, requested = 10))
        assertEquals(1, normalizedCatalogRepeatCount(queueSize = -1, requested = 4))
        assertEquals(4, normalizedCatalogRepeatCount(queueSize = 2, requested = 4))
        assertEquals(10, normalizedCatalogRepeatCount(queueSize = 20, requested = 10))
    }

    @Test
    fun stalePositionEditsCannotDeleteOrMoveAnotherQueueOccurrence() {
        val rendered = listOf("a", "b", "a")
        val afterFirstDelete = removeQueueAtIfCurrent(
            currentQueue = rendered,
            expectedQueue = rendered,
            index = 0,
        )
        assertEquals(listOf("b", "a"), afterFirstDelete)
        assertEquals(
            afterFirstDelete,
            removeQueueAtIfCurrent(
                currentQueue = afterFirstDelete,
                expectedQueue = rendered,
                index = 0,
            ),
        )
        assertEquals(
            afterFirstDelete,
            moveQueueItemIfCurrent(
                currentQueue = afterFirstDelete,
                expectedQueue = rendered,
                fromIndex = 0,
                toIndex = 1,
            ),
        )
        assertEquals(
            listOf("a"),
            removeQueueAtIfCurrent(
                currentQueue = afterFirstDelete,
                expectedQueue = afterFirstDelete,
                index = 0,
            ),
        )
    }

    @Test
    fun startSnapshotUsesLatestKnownQueueAndPreservesWholeQueueLoop() {
        val scenario = checkNotNull(ScenarioCatalog.byId("baseline-display-modes"))
        val knownIds = ScenarioCatalog.presets.mapTo(LinkedHashSet()) { it.id }
        val plan = checkNotNull(
            catalogRunPlanSnapshot(
                rawQueueIds = listOf("unknown") + List(5) { scenario.id },
                knownScenarioIds = knownIds,
                requestedRepeat = 10,
                requestedDurationMultiplier = 100,
            ),
        )

        assertEquals(List(5) { scenario.id }, plan.scenarios.map { it.id })
        assertEquals(10, plan.repeatCount)
        assertEquals(50, plan.totalRuns)
        assertEquals(100, plan.durationMultiplier)
        assertEquals(
            null,
            catalogRunPlanSnapshot(
                rawQueueIds = listOf("unknown"),
                knownScenarioIds = knownIds,
                requestedRepeat = 10,
            ),
        )
    }

    @Test
    fun allCatalogScenariosCanRepeatAsOneWholeQueue() {
        val knownIds = ScenarioCatalog.presets.mapTo(LinkedHashSet()) { it.id }
        val plan = checkNotNull(
            catalogRunPlanSnapshot(
                rawQueueIds = ScenarioCatalog.presets.map { it.id },
                knownScenarioIds = knownIds,
                requestedRepeat = 10,
                requestedDurationMultiplier = 10,
            ),
        )

        assertEquals(ScenarioCatalog.presets.size, plan.scenarios.size)
        assertEquals(10, plan.repeatCount)
        assertEquals(360, plan.totalRuns)
        assertEquals(10, plan.durationMultiplier)
        assertEquals(null, ScenarioPlanPolicy.validate(plan))
        assertEquals(
            1,
            checkNotNull(
                catalogRunPlanSnapshot(
                    rawQueueIds = listOf(ScenarioCatalog.presets.first().id),
                    knownScenarioIds = knownIds,
                    requestedRepeat = 1,
                    requestedDurationMultiplier = 3,
                ),
            ).durationMultiplier,
        )
    }

    @Test
    fun durationFormattingStaysReadableForLongPlans() {
        assertEquals("59s", formatDuration(59_000L))
        assertEquals("1m 1s", formatDuration(61_000L))
        assertEquals("2h 5m", formatDuration(7_500_000L))
        assertEquals("2d 3h", formatDuration(183_600_000L))
    }

    @Test
    fun mediaPickerIsRequiredOnlyWhenTheSelectedQueueUsesDecoderRoutes() {
        val rgb = checkNotNull(ScenarioCatalog.byId("baseline-display-modes"))
        val decoder = checkNotNull(ScenarioCatalog.byId("dvfs-video-shock"))

        assertFalse(scenariosRequireSelectedMedia(emptyList()))
        assertFalse(scenariosRequireSelectedMedia(listOf(rgb)))
        assertTrue(scenariosRequireSelectedMedia(listOf(decoder)))
        assertTrue(scenariosRequireSelectedMedia(listOf(rgb, decoder, rgb)))
    }

    @Test
    fun typedHwcExpectationBadgesAreExplicit() {
        assertEquals(
            "DEVICE 유지 검증",
            HwcCompositionExpectation.DEVICE_ONLY.validationBadge(),
        )
        assertEquals(
            "CLIENT 전환 검증",
            HwcCompositionExpectation.CLIENT_REQUIRED.validationBadge(),
        )
        assertEquals(
            "HWC 자동 배정",
            HwcCompositionExpectation.NONE.validationBadge(),
        )
    }

    @Test
    fun runningHwcSummaryKeepsUnavailableValuesAgeAndSourceVisible() {
        val summary = hwcExpectationLiveSummary(
            expectation = HwcCompositionExpectation.DEVICE_ONLY,
            telemetry = TelemetrySnapshot(
                hwcDeviceLayers = null,
                hwcDeviceLayersSource = "kernel probe missing",
                hwcClientLayers = null,
                hwcClientLayersSource = "",
                hwcCompositionEvidenceAgeMs = null,
            ),
        )

        assertTrue(summary.contains("HWC APP RAW"))
        assertTrue(summary.contains("RAW N/A"))
        assertTrue(summary.contains("D N/A/C N/A"))
        assertTrue(summary.contains("AGE N/A"))
        assertTrue(summary.contains("SRC D N/A:kernel pro"))
        assertTrue(summary.contains("C N/A:source N/A"))
        assertTrue(summary.contains("target/횟수는 controller 최종 판정"))
    }

    @Test
    fun runningHwcCountSummaryShowsOnlyAtomicPairAndUnseparatedScope() {
        val summary = hwcLayerCountLiveSummary(
            telemetry = atomicHwcTelemetry(device = 4, client = 2),
        )

        assertTrue(summary.contains("HWC APP RAW"))
        assertTrue(summary.contains("D 4/C 2/T 6"))
        assertTrue(summary.contains("SCOPE 미분리"))
        assertTrue(summary.contains("control/root 보정 없음"))
        assertTrue(summary.contains("HUD extra Surface 0"))
    }

    @Test
    fun typedRunningHwcSummaryKeepsRawStateAuxiliaryToControllerVerdict() {
        val match = hwcExpectationLiveSummary(
            expectation = HwcCompositionExpectation.DEVICE_ONLY,
            telemetry = atomicHwcTelemetry(device = 4, client = 0),
        )
        val wait = hwcExpectationLiveSummary(
            expectation = HwcCompositionExpectation.CLIENT_REQUIRED,
            telemetry = atomicHwcTelemetry(device = 4, client = 0),
        )

        assertTrue(match.contains("RAW MATCH"))
        assertTrue(wait.contains("RAW WAIT"))
        assertTrue(match.contains("controller 최종 판정"))
        assertTrue(wait.contains("controller 최종 판정"))
    }

    @Test
    fun rawHwcStateMatchesOnlyAtomicFreshPairAtExactAgeBoundary() {
        val telemetry = atomicHwcTelemetry(
            device = 4,
            client = 0,
            evidenceAgeMs = HWC_COMPOSITION_EVIDENCE_MAX_AGE_MS,
        )

        assertEquals(
            RawHwcExpectationState.MATCH,
            rawHwcExpectationState(HwcCompositionExpectation.DEVICE_ONLY, telemetry),
        )
        assertEquals(
            RawHwcExpectationState.WAIT,
            rawHwcExpectationState(HwcCompositionExpectation.CLIENT_REQUIRED, telemetry),
        )
    }

    @Test
    fun rawHwcStateRejectsStaleOrInconsistentTimestamp() {
        val stale = atomicHwcTelemetry(
            device = 4,
            client = 0,
            evidenceAgeMs = HWC_COMPOSITION_EVIDENCE_MAX_AGE_MS + 1L,
        )
        val inconsistent = atomicHwcTelemetry(device = 4, client = 0).copy(
            hwcCompositionEvidenceAgeMs = 999L,
        )
        val future = atomicHwcTelemetry(device = 4, client = 0).copy(
            hwcCompositionEvidenceMonotonicMs = 10_001L,
            hwcCompositionEvidenceAgeMs = 0L,
        )

        assertEquals(
            RawHwcExpectationState.N_A,
            rawHwcExpectationState(HwcCompositionExpectation.DEVICE_ONLY, stale),
        )
        assertEquals(
            RawHwcExpectationState.N_A,
            rawHwcExpectationState(HwcCompositionExpectation.DEVICE_ONLY, inconsistent),
        )
        assertEquals(
            RawHwcExpectationState.N_A,
            rawHwcExpectationState(HwcCompositionExpectation.DEVICE_ONLY, future),
        )
    }

    @Test
    fun rawHwcStateExpiresAgainstLiveClockWhenTelemetryStops() {
        val telemetry = atomicHwcTelemetry(
            device = 4,
            client = 0,
            evidenceAgeMs = 1_000L,
        )

        assertEquals(
            RawHwcExpectationState.MATCH,
            rawHwcExpectationState(
                expectation = HwcCompositionExpectation.DEVICE_ONLY,
                telemetry = telemetry,
                nowMonotonicMs = 11_500L,
            ),
        )
        assertEquals(
            RawHwcExpectationState.N_A,
            rawHwcExpectationState(
                expectation = HwcCompositionExpectation.DEVICE_ONLY,
                telemetry = telemetry,
                nowMonotonicMs = 11_501L,
            ),
        )
    }

    @Test
    fun rawHwcStateRejectsMixedSourceOrQualityAndDistinguishesWait() {
        val clientMatch = atomicHwcTelemetry(device = 2, client = 3)
        val mixedSource = clientMatch.copy(hwcClientLayersSource = "vendor display")
        val mixedQuality = clientMatch.copy(
            hwcClientLayersQuality = MetricQuality.HARDWARE_COUNTER,
        )

        assertEquals(
            RawHwcExpectationState.MATCH,
            rawHwcExpectationState(HwcCompositionExpectation.CLIENT_REQUIRED, clientMatch),
        )
        assertEquals(
            RawHwcExpectationState.WAIT,
            rawHwcExpectationState(HwcCompositionExpectation.DEVICE_ONLY, clientMatch),
        )
        assertEquals(
            RawHwcExpectationState.N_A,
            rawHwcExpectationState(HwcCompositionExpectation.CLIENT_REQUIRED, mixedSource),
        )
        assertEquals(
            RawHwcExpectationState.N_A,
            rawHwcExpectationState(HwcCompositionExpectation.CLIENT_REQUIRED, mixedQuality),
        )
        assertEquals(
            RawHwcExpectationState.N_A,
            rawHwcExpectationState(HwcCompositionExpectation.NONE, clientMatch),
        )
    }

    @Test
    fun peakHwcCompositionKeepsOneAtomicTupleInsteadOfCombiningIndependentMaxima() {
        val lowerTotalHigherDevice = atomicHwcTelemetry(device = 5, client = 0)
        val higherTotal = atomicHwcTelemetry(device = 2, client = 6).copy(
            monotonicMs = 11_000L,
            hwcCompositionEvidenceMonotonicMs = 10_000L,
        )

        val peak = checkNotNull(
            atomicHwcCompositionPeak(listOf(lowerTotalHigherDevice, higherTotal)),
        )

        assertEquals(2, peak.deviceLayers)
        assertEquals(6, peak.clientLayers)
        assertEquals(8L, peak.totalLayers)
        assertNull(
            atomicHwcCompositionPeak(
                listOf(
                    lowerTotalHigherDevice,
                    higherTotal.copy(
                        hwcDeviceLayersSource = "other",
                        hwcClientLayersSource = "other",
                    ),
                ),
            ),
        )
    }

    @Test
    fun runningHudRefreshBucketCapsDynamicControlUiAtOneHertz() {
        assertEquals(0L, runningHudRefreshBucket(monotonicMs = 999L))
        assertEquals(1L, runningHudRefreshBucket(monotonicMs = 1_000L))
        assertEquals(2L, runningHudRefreshBucket(monotonicMs = 2_999L))
        assertEquals(0L, runningHudRefreshBucket(monotonicMs = -1L))
    }

    @Test
    fun quickPurposeAppendKeepsQueueDuplicatesCatalogOrderAndHardCap() {
        val purposeIds = scenariosForCatalogPurpose(
            ScenarioCatalog.presets,
            CatalogPurpose.DPU_BURST,
        ).map { it.id }
        val duplicate = checkNotNull(purposeIds.firstOrNull())
        val existing = listOf(duplicate, duplicate)
        val appended = ScenarioQueueEditor.appendAll(existing, purposeIds)

        assertEquals(existing, appended.take(existing.size))
        assertEquals(purposeIds, appended.drop(existing.size))

        val almostFull = List(ScenarioPlanPolicy.MAX_TOTAL_PLAN_RUNS - 1) { duplicate }
        val capped = ScenarioQueueEditor.appendAll(almostFull, purposeIds)
        assertEquals(ScenarioPlanPolicy.MAX_TOTAL_PLAN_RUNS, capped.size)
        assertEquals(almostFull, capped.take(almostFull.size))
        assertEquals(purposeIds.first(), capped.last())
    }

    private fun atomicHwcTelemetry(
        device: Int,
        client: Int,
        evidenceAgeMs: Long = 1_000L,
    ): TelemetrySnapshot {
        val observedMs = 10_000L
        return TelemetrySnapshot(
            monotonicMs = observedMs,
            hwcDeviceLayers = device,
            hwcDeviceLayersQuality = MetricQuality.SYSTEM_SERVICE,
            hwcDeviceLayersSource = "SurfaceFlinger display 0",
            hwcClientLayers = client,
            hwcClientLayersQuality = MetricQuality.SYSTEM_SERVICE,
            hwcClientLayersSource = "SurfaceFlinger display 0",
            hwcCompositionEvidenceMonotonicMs = observedMs - evidenceAgeMs,
            hwcCompositionEvidenceAgeMs = evidenceAgeMs,
        )
    }
}
