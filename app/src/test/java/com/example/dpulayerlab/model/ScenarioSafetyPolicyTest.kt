package com.example.dpulayerlab.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenarioSafetyPolicyTest {
    @Test
    fun emptyAndExcessivePhaseListsAreRejected() {
        assertRejected(scenario(phases = emptyList()))

        val excessive = List(ScenarioSafetyPolicy.MAX_PHASE_COUNT + 1) { index ->
            phase(id = "phase-$index")
        }
        assertRejected(scenario(phases = excessive))
    }

    @Test
    fun blankAndDuplicateIdsAreRejectedAfterTrimming() {
        assertRejected(scenario(id = " "))
        assertRejected(scenario(phases = listOf(phase(id = " "))))
        assertRejected(
            scenario(
                phases = listOf(
                    phase(id = "duplicate"),
                    phase(id = " duplicate "),
                ),
            ),
        )
    }

    @Test
    fun oversizedScenarioAndPhaseMetadataAreRejected() {
        assertRejected(scenario(id = "x".repeat(ScenarioSafetyPolicy.MAX_ID_CHARS + 1)))
        assertRejected(
            scenario(
                phases = listOf(
                    phase(id = "x".repeat(ScenarioSafetyPolicy.MAX_ID_CHARS + 1)),
                ),
            ),
        )
    }

    @Test
    fun invalidDurationLayerFpsAndHzAreRejected() {
        assertRejected(scenario(phases = listOf(phase(durationMs = 0))))
        assertRejected(scenario(phases = listOf(phase(activeLayers = 0))))
        assertRejected(scenario(phases = listOf(phase(producerFps = Float.NaN))))
        assertRejected(scenario(phases = listOf(phase(producerFps = Float.POSITIVE_INFINITY))))
        assertRejected(scenario(phases = listOf(phase(requestedDisplayHz = -1f))))
        assertRejected(scenario(phases = listOf(phase(requestedDisplayHz = Float.NEGATIVE_INFINITY))))
    }

    @Test
    fun nonFiniteWorkloadsAreRejected() {
        assertRejected(
            scenario(
                phases = listOf(
                    phase(workloads = LoadSetpoints(cpu = Float.NaN)),
                ),
            ),
        )
        assertRejected(
            scenario(
                phases = listOf(
                    phase(workloads = LoadSetpoints(gpu = Float.POSITIVE_INFINITY)),
                ),
            ),
        )
    }

    @Test
    fun negativeWorkloadsAreRejectedInsteadOfSilentlyClampedToIdle() {
        listOf(
            LoadSetpoints(cpu = -0.1f),
            LoadSetpoints(memory = -0.1f),
            LoadSetpoints(gpu = -0.1f),
            LoadSetpoints(npu = -0.1f),
        ).forEach { workloads ->
            assertRejected(
                scenario(
                    phases = listOf(phase(workloads = workloads)),
                ),
            )
        }
    }

    @Test
    fun subEffectivePositiveWorkloadIsRejectedInsteadOfReportedAsActive() {
        assertAccepted(
            ScenarioSafetyPolicy.evaluate(
                scenario(phases = listOf(phase(workloads = LoadSetpoints(cpu = 0f)))),
                limits(),
            ),
        )
        assertRejected(
            scenario(
                phases = listOf(
                    phase(workloads = LoadSetpoints(cpu = MIN_EFFECTIVE_LOAD)),
                ),
            ),
        )
        assertAccepted(
            ScenarioSafetyPolicy.evaluate(
                scenario(
                    phases = listOf(
                        phase(
                            workloads = LoadSetpoints(
                                cpu = java.lang.Math.nextUp(MIN_EFFECTIVE_LOAD),
                            ),
                        ),
                    ),
                ),
                limits(),
            ),
        )
    }

    @Test
    fun malformedTransitionParametersAreRejected() {
        assertRejected(
            scenario(
                phases = listOf(
                    phase(
                        transition = TransitionSpec(
                            transitionDurationMs = -1L,
                        ),
                    ),
                ),
            ),
        )
        assertRejected(
            scenario(
                phases = listOf(
                    phase(
                        transition = TransitionSpec(cycleMs = 0L),
                    ),
                ),
            ),
        )
        assertRejected(
            scenario(
                phases = listOf(
                    phase(
                        transition = TransitionSpec(stepCount = 0),
                    ),
                ),
            ),
        )
        assertRejected(
            scenario(
                phases = listOf(
                    phase(
                        transition = TransitionSpec(dutyCycle = Float.NaN),
                    ),
                ),
            ),
        )
        assertRejected(
            scenario(
                phases = listOf(
                    phase(
                        transition = TransitionSpec(floor = Float.POSITIVE_INFINITY),
                    ),
                ),
            ),
        )
    }

    @Test
    fun hwcCompositionExpectationRequiresStableStepTarget() {
        HwcCompositionExpectation.entries
            .filterNot { it == HwcCompositionExpectation.NONE }
            .forEach { expectation ->
                val minimumDurationMs =
                    ScenarioSafetyPolicy.minimumHwcExpectationPhaseDurationMs(expectation)
                assertAccepted(
                    ScenarioSafetyPolicy.evaluate(
                        scenario(
                            phases = listOf(
                                phase(
                                    durationMs = minimumDurationMs,
                                    hwcCompositionExpectation = expectation,
                                ),
                            ),
                        ),
                        limits(),
                    ),
                )
                assertRejected(
                    scenario(
                        phases = listOf(
                            phase(
                                durationMs = minimumDurationMs,
                                transition = TransitionSpec(
                                    mode = TransitionMode.LINEAR_RAMP,
                                    transitionDurationMs = 500L,
                                ),
                                hwcCompositionExpectation = expectation,
                            ),
                        ),
                    ),
                )
                assertRejected(
                    scenario(
                        phases = listOf(
                            phase(
                                durationMs = minimumDurationMs,
                                layerSizeProfile =
                                    LayerSizeProfile.ABRUPT_SMALL_FULL,
                                hwcCompositionExpectation = expectation,
                            ),
                        ),
                    ),
                )
            }
    }

    @Test
    fun transitionParametersAreBoundedToTheEffectivePhase() {
        val decision = ScenarioSafetyPolicy.evaluate(
            scenario(
                phases = listOf(
                    phase(
                        durationMs = 2_000L,
                        transition = TransitionSpec(
                            mode = TransitionMode.LINEAR_RAMP,
                            transitionDurationMs = 10_000L,
                            cycleMs = 1L,
                            stepCount = 100,
                            dutyCycle = 0.99f,
                            floor = -1f,
                        ),
                    ),
                ),
            ),
            limits(
                maxPhaseDurationMs = 500L,
                maxScenarioDurationMs = 500L,
            ),
        )

        val transition = assertAccepted(decision).phases.single().transition
        assertEquals(500L, transition.transitionDurationMs)
        assertEquals(TransitionSpec.MIN_CYCLE_MS, transition.cycleMs)
        assertEquals(TransitionSpec.MAX_STEP_COUNT, transition.stepCount)
        assertEquals(TransitionSpec.MAX_DUTY_CYCLE, transition.dutyCycle)
        assertEquals(0f, transition.floor)
        assertTrue(decision.adjustments.any { it.contains("transition") })
    }

    @Test
    fun reducingPhaseDurationScalesRampAndPreservesTargetHold() {
        val decision = ScenarioSafetyPolicy.evaluate(
            scenario(
                phases = listOf(
                    phase(
                        durationMs = 10_000L,
                        transition = TransitionSpec(
                            mode = TransitionMode.LINEAR_RAMP,
                            transitionDurationMs = 8_000L,
                        ),
                    ),
                ),
            ),
            limits(
                maxPhaseDurationMs = 5_000L,
                maxScenarioDurationMs = 5_000L,
            ),
        )

        val effective = assertAccepted(decision).phases.single()
        assertEquals(5_000L, effective.durationMs)
        assertEquals(4_000L, effective.transition.transitionDurationMs)
    }

    @Test
    fun totalDurationCapIsProportionalAndKeepsRecoveryObservable() {
        val decision = ScenarioSafetyPolicy.evaluate(
            scenario(
                phases = listOf(
                    phase(id = "settle", durationMs = 10_000L),
                    phase(id = "stress", durationMs = 10_000L),
                    phase(id = "recover", durationMs = 10_000L),
                ),
            ),
            limits(maxScenarioDurationMs = 15_000L),
        )

        assertEquals(
            listOf(5_000L, 5_000L, 5_000L),
            assertAccepted(decision).phases.map { it.durationMs },
        )
    }

    @Test
    fun totalDurationCapScalesCyclicPeriodToPreserveCycleCount() {
        val decision = ScenarioSafetyPolicy.evaluate(
            scenario(
                phases = listOf(
                    phase(
                        id = "pulse",
                        durationMs = 12_000L,
                        transition = TransitionSpec(
                            mode = TransitionMode.PULSE_BURST,
                            cycleMs = 2_000L,
                        ),
                    ),
                    phase(id = "recover", durationMs = 12_000L),
                ),
            ),
            limits(maxScenarioDurationMs = 12_000L),
        )

        val effective = assertAccepted(decision).phases.first()
        assertEquals(6_000L, effective.durationMs)
        assertEquals(1_000L, effective.transition.cycleMs)
    }

    @Test
    fun cyclicPhaseShorterThanSafeCycleIsRejectedInsteadOfBecomingSteady() {
        val decision = ScenarioSafetyPolicy.evaluate(
            scenario(
                phases = listOf(
                    phase(
                        durationMs = 400L,
                        transition = TransitionSpec(
                            mode = TransitionMode.PULSE_BURST,
                            cycleMs = 500L,
                        ),
                    ),
                ),
            ),
            limits(
                maxPhaseDurationMs = 400L,
                maxScenarioDurationMs = 400L,
            ),
        )

        assertRejected(decision)
        assertTrue(decision.rejectionReason!!.contains("transition cycle"))
    }

    @Test
    fun controlCadenceRejectsUnobservableStepRampStaircaseAndSoak() {
        val transitions = listOf(
            TransitionSpec(mode = TransitionMode.STEP),
            TransitionSpec(mode = TransitionMode.LINEAR_RAMP),
            TransitionSpec(mode = TransitionMode.STAIRCASE, stepCount = 4),
            TransitionSpec(mode = TransitionMode.SOAK_RECOVERY),
        )

        transitions.forEach { transition ->
            assertRejected(
                ScenarioSafetyPolicy.evaluate(
                    scenario(
                        phases = listOf(
                            phase(durationMs = 100L, transition = transition),
                        ),
                    ),
                    limits(
                        maxPhaseDurationMs = 100L,
                        maxScenarioDurationMs = 100L,
                    ),
                ),
            )
        }
    }

    @Test
    fun hwcCompositionExpectationRejectsSafetyMutationOfItsTargetContract() {
        val target = phase(
            durationMs = 16_000L,
            activeLayers = 4,
            producerFps = 120f,
            requestedDisplayHz = 120f,
            backend = LayerBackend.MIXED_SURFACE_TEXTURE,
            workloads = LoadSetpoints(gpu = 0.8f),
            includeGlLayer = true,
            hwcCompositionExpectation = HwcCompositionExpectation.CLIENT_REQUIRED,
        )
        val constrainedDecisions = listOf(
            ScenarioSafetyPolicy.evaluate(
                scenario(phases = listOf(target)),
                limits(maxLayers = 3),
            ),
            ScenarioSafetyPolicy.evaluate(
                scenario(phases = listOf(target)),
                limits(maxProducerFps = 90f),
            ),
            ScenarioSafetyPolicy.evaluate(
                scenario(phases = listOf(target)),
                limits(maxGraphicsBytes = 360_000L),
            ),
            ScenarioSafetyPolicy.evaluate(
                scenario(phases = listOf(target)),
                limits(maxGpuLoad = 0.4f),
            ),
            ScenarioSafetyPolicy.evaluate(
                scenario(
                    phases = listOf(
                        target.copy(requestedDisplayHz = 300f),
                    ),
                ),
                limits(),
            ),
            ScenarioSafetyPolicy.evaluate(
                scenario(
                    phases = listOf(
                        target.copy(
                            activeLayers = 1,
                            backend = LayerBackend.FLATTENED_TEXTURE,
                        ),
                    ),
                ),
                limits(),
            ),
        )

        constrainedDecisions.forEach { decision ->
            assertRejected(decision)
            assertTrue(decision.rejectionReason!!.contains("HWC composition expectation"))
        }
    }

    @Test
    fun hwcCompositionExpectationKeepsBoundedProbeAndPostTargetDuration() {
        assertEquals(
            0L,
            ScenarioSafetyPolicy.minimumHwcExpectationPhaseDurationMs(
                HwcCompositionExpectation.NONE,
            ),
        )
        listOf(
            HwcCompositionExpectation.DEVICE_ONLY to 12_000L,
            HwcCompositionExpectation.CLIENT_REQUIRED to 16_000L,
        ).forEach { (expectation, minimumDurationMs) ->
            assertEquals(
                minimumDurationMs,
                ScenarioSafetyPolicy.minimumHwcExpectationPhaseDurationMs(expectation),
            )
            val target = phase(
                durationMs = minimumDurationMs + 1_000L,
                hwcCompositionExpectation = expectation,
            )
            val acceptedAtBoundary = ScenarioSafetyPolicy.evaluate(
                scenario(phases = listOf(target)),
                limits(
                    maxPhaseDurationMs = minimumDurationMs,
                    maxScenarioDurationMs = minimumDurationMs,
                ),
            )
            val rejectedBelowBoundary = ScenarioSafetyPolicy.evaluate(
                scenario(phases = listOf(target)),
                limits(
                    maxPhaseDurationMs = minimumDurationMs - 1L,
                    maxScenarioDurationMs = minimumDurationMs - 1L,
                ),
            )

            assertEquals(
                minimumDurationMs,
                assertAccepted(acceptedAtBoundary).phases.single().durationMs,
            )
            assertRejected(rejectedBelowBoundary)
            assertTrue(rejectedBelowBoundary.rejectionReason!!.contains("fresh HWC evidence"))
        }
    }

    @Test
    fun totalDurationCapCannotShrinkTypedHwcPhaseBelowProbeWindow() {
        listOf(
            HwcCompositionExpectation.DEVICE_ONLY to 12_000L,
            HwcCompositionExpectation.CLIENT_REQUIRED to 16_000L,
        ).forEach { (expectation, minimumDurationMs) ->
            val phases = listOf(
                phase(id = "plain", durationMs = minimumDurationMs),
                phase(
                    id = "typed",
                    durationMs = minimumDurationMs,
                    hwcCompositionExpectation = expectation,
                ),
            )
            val accepted = ScenarioSafetyPolicy.evaluate(
                scenario(phases = phases),
                limits(maxScenarioDurationMs = minimumDurationMs * 2L),
            )
            val rejected = ScenarioSafetyPolicy.evaluate(
                scenario(phases = phases),
                limits(maxScenarioDurationMs = minimumDurationMs * 2L - 1L),
            )

            assertEquals(
                listOf(minimumDurationMs, minimumDurationMs),
                assertAccepted(accepted).phases.map { it.durationMs },
            )
            assertRejected(rejected)
            assertTrue(rejected.rejectionReason!!.contains("fresh HWC evidence"))
        }
    }

    @Test
    fun soakEdgeIsBoundedToLeaveAHoldSegment() {
        val decision = ScenarioSafetyPolicy.evaluate(
            scenario(
                phases = listOf(
                    phase(
                        durationMs = 9_000L,
                        transition = TransitionSpec(
                            mode = TransitionMode.SOAK_RECOVERY,
                            transitionDurationMs = 9_000L,
                        ),
                    ),
                ),
            ),
            limits(),
        )

        val effective = assertAccepted(decision).phases.single()
        assertEquals(4_450L, effective.transition.transitionDurationMs)
        assertTrue(decision.adjustments.any { it.contains("transition") })
    }

    @Test
    fun nonStepTransitionWithoutDynamicRangeIsRejected() {
        val decision = ScenarioSafetyPolicy.evaluate(
            scenario(
                phases = listOf(
                    phase(
                        transition = TransitionSpec(
                            mode = TransitionMode.TRIANGLE_WAVE,
                            floor = 1f,
                        ),
                    ),
                ),
            ),
            limits(),
        )

        assertRejected(decision)
        assertTrue(decision.rejectionReason!!.contains("dynamic range"))
    }

    @Test
    fun nonCyclicTransitionCannotCarryAHiddenLoadFloor() {
        val decision = ScenarioSafetyPolicy.evaluate(
            scenario(
                phases = listOf(
                    phase(
                        transition = TransitionSpec(
                            mode = TransitionMode.LINEAR_RAMP,
                            transitionDurationMs = 2_000L,
                            floor = 0.4f,
                        ),
                    ),
                ),
            ),
            limits(),
        )

        assertRejected(decision)
        assertTrue(decision.rejectionReason!!.contains("floor"))
    }

    @Test
    fun layersFpsHzDurationAndLoadsAreCapped() {
        val decision = ScenarioSafetyPolicy.evaluate(
            scenario(
                phases = listOf(
                    phase(
                        activeLayers = Int.MAX_VALUE,
                        producerFps = 1_000f,
                        requestedDisplayHz = 1_000f,
                        durationMs = Long.MAX_VALUE,
                        workloads = LoadSetpoints(
                            cpu = 2f,
                            memory = 2f,
                            gpu = 0.9f,
                            npu = 0.8f,
                        ),
                        includeGlLayer = true,
                    ),
                ),
            ),
            limits(
                maxLayers = 100,
                maxProducerFps = 500f,
                maxPhaseDurationMs = 5_000,
                maxScenarioDurationMs = 5_000,
                maxCpuLoad = 0.5f,
                maxMemoryLoad = 0.5f,
                maxGpuLoad = 0.6f,
                maxNpuLoad = 0.7f,
            ),
        )

        val effective = assertAccepted(decision)
        val actual = effective.phases.single()
        assertEquals(20, actual.activeLayers)
        assertEquals(120f, actual.producerFps)
        assertEquals(240f, actual.requestedDisplayHz)
        assertEquals(5_000L, actual.durationMs)
        assertEquals(LoadSetpoints(0.5f, 0.5f, 0.6f, 0.7f), actual.workloads)
        assertTrue(decision.adjustments.isNotEmpty())
    }

    @Test
    fun independentLayersAreClampedToGraphicsBudget() {
        // 100 * 100 * RGBA(4) * triple-buffer(3) = 120,000 bytes per producer.
        val decision = ScenarioSafetyPolicy.evaluate(
            scenario(phases = listOf(phase(activeLayers = 10))),
            limits(maxGraphicsBytes = 360_000),
        )

        val effective = assertAccepted(decision)
        assertEquals(3, effective.phases.single().activeLayers)
        assertTrue(decision.adjustments.any { it.contains("layers") })
    }

    @Test
    fun singleGlProducerIncludesConservativeDepthAttachment() {
        val rejected = ScenarioSafetyPolicy.evaluate(
            scenario(
                phases = listOf(
                    phase(activeLayers = 1, includeGlLayer = true),
                ),
            ),
            // 120,000 color + 120,000 conservative depth, both triple buffered.
            limits(maxGraphicsBytes = 239_999),
        )
        val accepted = ScenarioSafetyPolicy.evaluate(
            scenario(
                phases = listOf(
                    phase(activeLayers = 1, includeGlLayer = true),
                ),
            ),
            limits(maxGraphicsBytes = 240_000),
        )

        assertRejected(rejected)
        assertEquals(1, assertAccepted(accepted).phases.single().activeLayers)
    }

    @Test
    fun glTailDepthIsAddedAfterAllColorBuffers() {
        val decision = ScenarioSafetyPolicy.evaluate(
            scenario(
                phases = listOf(
                    phase(activeLayers = 2, includeGlLayer = true),
                ),
            ),
            // Two color producers use 240,000; the GL tail depth needs another 120,000.
            limits(maxGraphicsBytes = 359_999),
        )

        val effective = assertAccepted(decision).phases.single()
        assertEquals(1, effective.activeLayers)
        assertTrue(!effective.includeGlLayer)
    }

    @Test
    fun requestedPrimaryResolutionIsCountedBeforeDisplayOverlays() {
        val fourKTripleBuffered = 3_840L * 2_160L * 4L * 3L
        val displayOverlay = 100L * 100L * 4L * 3L
        val decision = ScenarioSafetyPolicy.evaluate(
            scenario(
                phases = listOf(
                    phase(activeLayers = 5, bufferSize = BufferSize.UHD_4K),
                ),
            ),
            limits(maxGraphicsBytes = fourKTripleBuffered + displayOverlay),
        )

        assertEquals(2, assertAccepted(decision).phases.single().activeLayers)
    }

    @Test
    fun selectedDecoderUsesActualTrackSizeForGraphicsBudget() {
        // Decoder: 200 * 100 * RGBA(4) * triple-buffer(3) = 240,000 bytes.
        // Overlay: 100 * 100 * RGBA(4) * triple-buffer(3) = 120,000 bytes.
        val decision = ScenarioSafetyPolicy.evaluate(
            scenario(
                phases = listOf(
                    phase(
                        activeLayers = 5,
                        pixelRoute = PixelRoute.YUV_420,
                        bufferSize = BufferSize.DISPLAY,
                    ),
                ),
            ),
            limits(maxGraphicsBytes = 360_000),
            selectedDecoderBuffer = SelectedDecoderBuffer(
                widthPx = 200,
                heightPx = 100,
            ),
        )

        val effective = assertAccepted(decision)
        assertEquals(2, effective.phases.single().activeLayers)
        assertTrue(decision.adjustments.any { it.contains("layers") })
    }

    @Test
    fun requiredSbwcSelectedMediaUsesActualDecoderTrackSize() {
        val decision = ScenarioSafetyPolicy.evaluate(
            scenario(
                phases = listOf(
                    phase(
                        pixelRoute = PixelRoute.SBWC_REQUIRED,
                        bufferSize = BufferSize.UHD_4K,
                    ),
                ),
            ),
            limits(maxGraphicsBytes = 200L * 100L * 4L * 3L),
            selectedDecoderBuffer = SelectedDecoderBuffer(
                widthPx = 200,
                heightPx = 100,
            ),
        )

        assertEquals(
            BufferSize.UHD_4K,
            assertAccepted(decision).phases.single().bufferSize,
        )
    }

    @Test
    fun clampingMultiLayerGlTailPreservesDecoderPrimary() {
        val decision = ScenarioSafetyPolicy.evaluate(
            scenario(
                phases = listOf(
                    phase(
                        activeLayers = 2,
                        pixelRoute = PixelRoute.YUV_420,
                        includeGlLayer = true,
                    ),
                ),
            ),
            // Exactly enough for the 200x100 decoder primary, but not its display-sized GL tail.
            limits(maxGraphicsBytes = 200L * 100L * 4L * 3L),
            selectedDecoderBuffer = SelectedDecoderBuffer(
                widthPx = 200,
                heightPx = 100,
            ),
        )

        val effective = assertAccepted(decision).phases.single()
        assertEquals(1, effective.activeLayers)
        assertTrue(!effective.includeGlLayer)
        assertTrue(decision.adjustments.any { it.contains("GL tail") })
    }

    @Test
    fun requiredGpuWorkIsRejectedWhenOnlyTheDedicatedPrimaryFits() {
        val decision = ScenarioSafetyPolicy.evaluate(
            scenario(
                phases = listOf(
                    phase(
                        activeLayers = 2,
                        pixelRoute = PixelRoute.YUV_420,
                        includeGlLayer = true,
                        workloads = LoadSetpoints(gpu = 0.5f),
                    ),
                ),
            ),
            limits(maxGraphicsBytes = 200L * 100L * 4L * 3L),
            selectedDecoderBuffer = SelectedDecoderBuffer(
                widthPx = 200,
                heightPx = 100,
            ),
        )

        assertRejected(decision)
        assertTrue(decision.rejectionReason!!.contains("GL producer required"))
    }

    @Test
    fun gpuLoadWithoutAnyGlProducerIsRejected() {
        val decision = ScenarioSafetyPolicy.evaluate(
            scenario(
                phases = listOf(
                    phase(
                        workloads = LoadSetpoints(gpu = 0.5f),
                        includeGlLayer = false,
                    ),
                ),
            ),
            limits(),
        )

        assertRejected(decision)
        assertTrue(decision.rejectionReason!!.contains("GPU load requires"))
    }

    @Test
    fun flattenedProducerCanCarryGpuLoadWithoutGlTailMarker() {
        val decision = ScenarioSafetyPolicy.evaluate(
            scenario(
                phases = listOf(
                    phase(
                        backend = LayerBackend.FLATTENED_TEXTURE,
                        workloads = LoadSetpoints(gpu = 0.5f),
                        includeGlLayer = false,
                    ),
                ),
            ),
            limits(),
        )

        val effective = assertAccepted(decision).phases.single()
        assertEquals(LayerBackend.FLATTENED_TEXTURE, effective.backend)
        assertTrue(!effective.includeGlLayer)
        assertEquals(0.5f, effective.workloads.gpu)
    }

    @Test
    fun oneRgbGlPrimaryCanCarryGpuLoad() {
        val decision = ScenarioSafetyPolicy.evaluate(
            scenario(
                phases = listOf(
                    phase(
                        activeLayers = 1,
                        includeGlLayer = true,
                        workloads = LoadSetpoints(gpu = 0.5f),
                    ),
                ),
            ),
            limits(),
        )

        val effective = assertAccepted(decision).phases.single()
        assertEquals(1, effective.activeLayers)
        assertTrue(effective.includeGlLayer)
        assertEquals(0.5f, effective.workloads.gpu)
    }

    @Test
    fun cyclicTransitionAcrossPhysicalTopologiesIsRejected() {
        val decision = ScenarioSafetyPolicy.evaluate(
            scenario(
                phases = listOf(
                    phase(
                        id = "gl-origin",
                        activeLayers = 2,
                        includeGlLayer = true,
                        workloads = LoadSetpoints(gpu = 0.5f),
                    ),
                    phase(
                        id = "no-gl-cycle",
                        activeLayers = 1,
                        includeGlLayer = false,
                        transition = TransitionSpec(
                            mode = TransitionMode.PULSE_BURST,
                            cycleMs = 500L,
                            dutyCycle = 0.5f,
                        ),
                    ),
                ),
            ),
            limits(),
        )

        assertRejected(decision)
        assertTrue(decision.rejectionReason!!.contains("physical producer topologies"))
    }

    @Test
    fun cyclicTransitionCannotAlternateOnlyThePhysicalLayerCount() {
        val decision = ScenarioSafetyPolicy.evaluate(
            scenario(
                phases = listOf(
                    phase(id = "one-layer-origin", activeLayers = 1),
                    phase(
                        id = "two-layer-cycle",
                        activeLayers = 2,
                        transition = TransitionSpec(
                            mode = TransitionMode.TRIANGLE_WAVE,
                            cycleMs = 500L,
                            floor = 0.25f,
                        ),
                    ),
                ),
            ),
            limits(),
        )

        assertRejected(decision)
        assertTrue(decision.rejectionReason!!.contains("physical producer topologies"))
    }

    @Test
    fun gradualGpuReleaseCannotSilentlyRemoveItsProducerButStepCan() {
        val gradualTransitions = listOf(
            TransitionSpec(
                mode = TransitionMode.LINEAR_RAMP,
                transitionDurationMs = 1_000L,
            ),
            TransitionSpec(
                mode = TransitionMode.STAIRCASE,
                transitionDurationMs = 1_000L,
                stepCount = 4,
            ),
            TransitionSpec(
                mode = TransitionMode.SOAK_RECOVERY,
                transitionDurationMs = 1_000L,
            ),
        )
        gradualTransitions.forEachIndexed { index, transition ->
            val decision = ScenarioSafetyPolicy.evaluate(
                scenario(
                    phases = listOf(
                        phase(
                            id = "gpu-origin-$index",
                            durationMs = 5_000L,
                            includeGlLayer = true,
                            workloads = LoadSetpoints(gpu = 0.8f),
                        ),
                        phase(
                            id = "remove-gl-$index",
                            durationMs = 5_000L,
                            transition = transition,
                        ),
                    ),
                ),
                limits(),
            )

            assertRejected(decision)
            assertTrue(decision.rejectionReason!!.contains("physical GPU load producer"))
        }

        val step = ScenarioSafetyPolicy.evaluate(
            scenario(
                phases = listOf(
                    phase(
                        id = "gpu-origin",
                        includeGlLayer = true,
                        workloads = LoadSetpoints(gpu = 0.8f),
                    ),
                    phase(
                        id = "explicit-step-release",
                        transition = TransitionSpec(mode = TransitionMode.STEP),
                    ),
                ),
            ),
            limits(),
        )
        assertAccepted(step)
    }

    @Test
    fun everyDecoderRouteRequiresASelectedVerifiedBuffer() {
        listOf(
            PixelRoute.YUV_420,
            PixelRoute.P010,
            PixelRoute.SBWC_AUTO,
            PixelRoute.SBWC_REQUIRED,
        ).forEach { route ->
            val decision = ScenarioSafetyPolicy.evaluate(
                scenario(phases = listOf(phase(pixelRoute = route))),
                limits(),
                selectedDecoderBuffer = null,
            )

            assertRejected(decision)
            assertTrue(decision.rejectionReason!!.contains("selected"))
        }
    }

    @Test
    fun selectedDecoderWithUnknownTrackSizeFailsClosed() {
        val decision = ScenarioSafetyPolicy.evaluate(
            scenario(
                phases = listOf(
                    phase(pixelRoute = PixelRoute.P010),
                ),
            ),
            limits(),
            selectedDecoderBuffer = SelectedDecoderBuffer(
                widthPx = null,
                heightPx = 100,
            ),
        )

        assertRejected(decision)
    }

    @Test
    fun selectedDecoderOverBudgetIsRejectedEvenWhenDeclaredDisplayBufferFits() {
        val decision = ScenarioSafetyPolicy.evaluate(
            scenario(
                phases = listOf(
                    phase(pixelRoute = PixelRoute.YUV_420),
                ),
            ),
            // The declared 100x100 display buffer fits exactly, but the selected 200x100
            // decoder output needs 240,000 bytes under the conservative triple-buffer model.
            limits(maxGraphicsBytes = 120_000),
            selectedDecoderBuffer = SelectedDecoderBuffer(
                widthPx = 200,
                heightPx = 100,
            ),
        )

        assertRejected(decision)
    }

    @Test
    fun selectedMediaSizeDoesNotAffectNonDecoderPrimary() {
        val decision = ScenarioSafetyPolicy.evaluate(
            scenario(
                phases = listOf(
                    phase(pixelRoute = PixelRoute.RGB_8888),
                ),
            ),
            limits(maxGraphicsBytes = 120_000),
            selectedDecoderBuffer = SelectedDecoderBuffer(
                widthPx = Int.MAX_VALUE,
                heightPx = Int.MAX_VALUE,
            ),
        )

        assertEquals(1, assertAccepted(decision).phases.single().activeLayers)
    }

    @Test
    fun oneProducerOverBudgetIsRejected() {
        val decision = ScenarioSafetyPolicy.evaluate(
            scenario(phases = listOf(phase())),
            limits(maxGraphicsBytes = 119_999),
        )

        assertRejected(decision)
    }

    @Test
    fun eightKFitCropAndRotationKeepTheSameFullAllocationBudget() {
        val requiredBytes =
            BufferSize.UHD_8K.width.toLong() *
                BufferSize.UHD_8K.height.toLong() *
                4L *
                3L
        listOf(
            BufferPresentation.FIT to LayerOrientation.ROTATION_0,
            BufferPresentation.FIT to LayerOrientation.ROTATION_90,
            BufferPresentation.PIXEL_1_TO_1_CROP to LayerOrientation.ROTATION_0,
            BufferPresentation.PIXEL_1_TO_1_CROP to LayerOrientation.ROTATION_90,
        ).forEach { (presentation, orientation) ->
            val requested = phase(bufferSize = BufferSize.UHD_8K).copy(
                bufferPresentation = presentation,
                layerOrientation = orientation,
                layerSizeProfile = LayerSizeProfile.FULL_SCREEN,
                motion = MotionProfile.STATIC,
            )
            assertAccepted(
                ScenarioSafetyPolicy.evaluate(
                    scenario(phases = listOf(requested)),
                    limits(maxGraphicsBytes = requiredBytes),
                ),
            )
            assertRejected(
                ScenarioSafetyPolicy.evaluate(
                    scenario(phases = listOf(requested)),
                    limits(maxGraphicsBytes = requiredBytes - 1L),
                ),
            )
        }
    }

    @Test
    fun capacityTilesRejectPresentationOrOrientationOverrides() {
        listOf(
            phase().copy(
                motion = MotionProfile.CAPACITY_TILES,
                bufferPresentation = BufferPresentation.PIXEL_1_TO_1_CROP,
            ),
            phase().copy(
                motion = MotionProfile.CAPACITY_TILES,
                layerOrientation = LayerOrientation.ROTATION_90,
            ),
        ).forEach { invalid ->
            val decision = ScenarioSafetyPolicy.evaluate(
                scenario(phases = listOf(invalid)),
                limits(maxGraphicsBytes = Long.MAX_VALUE),
            )
            assertRejected(decision)
            assertTrue(decision.rejectionReason!!.contains("capacity tiles"))
        }
    }

    @Test
    fun pixelOneToOneRejectsAdditionalScalingButAllowsTranslationAndRotation() {
        listOf(
            phase().copy(
                bufferPresentation = BufferPresentation.PIXEL_1_TO_1_CROP,
                layerSizeProfile = LayerSizeProfile.SMALL_UNIFORM,
            ),
            phase().copy(
                bufferPresentation = BufferPresentation.PIXEL_1_TO_1_CROP,
                motion = MotionProfile.ZOOM_PAN,
            ),
            phase().copy(
                bufferPresentation = BufferPresentation.PIXEL_1_TO_1_CROP,
                motion = MotionProfile.TRANSFORM_STORM,
            ),
        ).forEach { invalid ->
            val decision = ScenarioSafetyPolicy.evaluate(
                scenario(phases = listOf(invalid)),
                limits(maxGraphicsBytes = Long.MAX_VALUE),
            )
            assertRejected(decision)
            assertTrue(decision.rejectionReason!!.contains("1:1 crop"))
        }

        listOf(
            MotionProfile.STATIC,
            MotionProfile.SCROLL,
            MotionProfile.PARALLAX,
            MotionProfile.ROTATE,
        ).forEach { motion ->
            assertAccepted(
                ScenarioSafetyPolicy.evaluate(
                    scenario(
                        phases = listOf(
                            phase().copy(
                                bufferPresentation =
                                    BufferPresentation.PIXEL_1_TO_1_CROP,
                                layerOrientation = LayerOrientation.ROTATION_90,
                                layerSizeProfile = LayerSizeProfile.FULL_SCREEN,
                                motion = motion,
                            ),
                        ),
                    ),
                    limits(maxGraphicsBytes = Long.MAX_VALUE),
                ),
            )
        }
    }

    @Test
    fun flattenedBackendUsesOneDisplaySizedProducerForAnyLogicalLayerCount() {
        val decision = ScenarioSafetyPolicy.evaluate(
            scenario(
                phases = listOf(
                    phase(
                        activeLayers = 20,
                        backend = LayerBackend.FLATTENED_TEXTURE,
                    ),
                ),
            ),
            limits(maxGraphicsBytes = 120_000),
        )

        assertEquals(20, assertAccepted(decision).phases.single().activeLayers)
    }

    @Test
    fun flattenedBackendCannotClaimDecoderOrExplicitProducerSize() {
        listOf(
            phase(
                backend = LayerBackend.FLATTENED_TEXTURE,
                bufferSize = BufferSize.UHD_8K,
            ),
            phase(
                backend = LayerBackend.FLATTENED_TEXTURE,
                pixelRoute = PixelRoute.YUV_420,
            ),
        ).forEach { invalid ->
            val decision = ScenarioSafetyPolicy.evaluate(
                scenario(phases = listOf(invalid)),
                limits(maxGraphicsBytes = Long.MAX_VALUE),
            )
            assertRejected(decision)
            assertTrue(decision.rejectionReason!!.contains("display-sized RGB_8888"))
        }
    }

    @Test
    fun singleNonFlattenedGlLayerCannotReplaceDecoderPrimary() {
        val decision = ScenarioSafetyPolicy.evaluate(
            scenario(
                phases = listOf(
                    phase(
                        activeLayers = 1,
                        includeGlLayer = true,
                        pixelRoute = PixelRoute.YUV_420,
                    ),
                ),
            ),
            limits(maxGraphicsBytes = 120_000),
            selectedDecoderBuffer = SelectedDecoderBuffer(
                widthPx = null,
                heightPx = null,
            ),
        )

        assertRejected(decision)
        assertTrue(decision.rejectionReason!!.contains("at least two layers"))
    }

    @Test
    fun singleNonFlattenedGlLayerCannotReplaceExplicitSizePrimary() {
        val decision = ScenarioSafetyPolicy.evaluate(
            scenario(
                phases = listOf(
                    phase(
                        activeLayers = 1,
                        bufferSize = BufferSize.UHD_8K,
                        includeGlLayer = true,
                        pixelRoute = PixelRoute.RGB_8888,
                    ),
                ),
            ),
            limits(maxGraphicsBytes = Long.MAX_VALUE),
        )

        assertRejected(decision)
        assertTrue(decision.rejectionReason!!.contains("at least two layers"))
    }

    @Test
    fun singleDisplayRgbGlLayerRemainsValid() {
        val decision = ScenarioSafetyPolicy.evaluate(
            scenario(
                phases = listOf(
                    phase(
                        activeLayers = 1,
                        bufferSize = BufferSize.DISPLAY,
                        includeGlLayer = true,
                        pixelRoute = PixelRoute.RGB_8888,
                    ),
                ),
            ),
            limits(maxGraphicsBytes = 240_000),
        )

        val effective = assertAccepted(decision).phases.single()
        assertEquals(1, effective.activeLayers)
        assertTrue(effective.includeGlLayer)
    }

    @Test
    fun flattenedSingleGlLayerDoesNotRequireDedicatedTail() {
        val decision = ScenarioSafetyPolicy.evaluate(
            scenario(
                phases = listOf(
                    phase(
                        activeLayers = 1,
                        backend = LayerBackend.FLATTENED_TEXTURE,
                        includeGlLayer = true,
                    ),
                ),
            ),
            limits(maxGraphicsBytes = 120_000),
        )

        val effective = assertAccepted(decision).phases.single()
        assertEquals(1, effective.activeLayers)
        assertTrue(!effective.includeGlLayer)
        assertTrue(decision.adjustments.any { it.contains("ignored GL-tail marker") })
    }

    @Test
    fun multiplicationOverflowIsSafelyRejectedEvenAtLongMaxBudget() {
        val decision = ScenarioSafetyPolicy.evaluate(
            scenario(
                phases = listOf(
                    phase(backend = LayerBackend.FLATTENED_TEXTURE),
                ),
            ),
            limits(
                displayWidthPx = Int.MAX_VALUE,
                displayHeightPx = Int.MAX_VALUE,
                maxGraphicsBytes = Long.MAX_VALUE,
            ),
        )

        assertRejected(decision)
    }

    @Test
    fun totalDurationCapRejectsPhasesTooShortForControlSemantics() {
        val decision = ScenarioSafetyPolicy.evaluate(
            scenario(
                phases = listOf(
                    phase(id = "one", durationMs = Long.MAX_VALUE),
                    phase(id = "two", durationMs = Long.MAX_VALUE),
                    phase(id = "three", durationMs = Long.MAX_VALUE),
                ),
            ),
            limits(
                maxPhaseDurationMs = Long.MAX_VALUE,
                maxScenarioDurationMs = 100,
            ),
        )

        assertRejected(decision)
        assertTrue(decision.rejectionReason!!.contains("too short"))
    }

    @Test
    fun transitionWindowMustRemainObservableAtControllerCadence() {
        val shortRamp = ScenarioSafetyPolicy.evaluate(
            scenario(
                phases = listOf(
                    phase(
                        durationMs = 10_000L,
                        transition = TransitionSpec(
                            mode = TransitionMode.LINEAR_RAMP,
                            transitionDurationMs = 1L,
                        ),
                    ),
                ),
            ),
            limits(),
        )
        val undersampledStaircase = ScenarioSafetyPolicy.evaluate(
            scenario(
                phases = listOf(
                    phase(
                        durationMs = 10_000L,
                        transition = TransitionSpec(
                            mode = TransitionMode.STAIRCASE,
                            transitionDurationMs = 500L,
                            stepCount = 20,
                        ),
                    ),
                ),
            ),
            limits(),
        )

        assertRejected(shortRamp)
        assertTrue(shortRamp.rejectionReason!!.contains("ramp window"))
        assertRejected(undersampledStaircase)
        assertTrue(undersampledStaircase.rejectionReason!!.contains("staircase window"))
    }

    @Test
    fun dynamicLayerSizeProfilesMustRemainObservableAfterDurationCaps() {
        val gradualTooShort = ScenarioSafetyPolicy.evaluate(
            scenario(
                phases = listOf(
                    phase(
                        durationMs = LOAD_CONTROL_CADENCE_MS * 2L - 1L,
                        layerSizeProfile = LayerSizeProfile.GRADUAL_SMALL_TO_FULL,
                    ),
                ),
            ),
            limits(),
        )
        val abruptTooShort = ScenarioSafetyPolicy.evaluate(
            scenario(
                phases = listOf(
                    phase(
                        durationMs =
                            LOAD_CONTROL_CADENCE_MS *
                                ABRUPT_LAYER_SIZE_PROFILE_STEPS - 1L,
                        layerSizeProfile = LayerSizeProfile.ABRUPT_SMALL_FULL,
                    ),
                ),
            ),
            limits(),
        )
        val abruptAtBoundary = ScenarioSafetyPolicy.evaluate(
            scenario(
                phases = listOf(
                    phase(
                        durationMs =
                            LOAD_CONTROL_CADENCE_MS *
                                ABRUPT_LAYER_SIZE_PROFILE_STEPS,
                        layerSizeProfile = LayerSizeProfile.ABRUPT_SMALL_FULL,
                    ),
                ),
            ),
            limits(),
        )
        val cappedBelowBoundary = ScenarioSafetyPolicy.evaluate(
            scenario(
                phases = listOf(
                    phase(
                        durationMs = 10_000L,
                        layerSizeProfile = LayerSizeProfile.ABRUPT_SMALL_FULL,
                    ),
                ),
            ),
            limits(
                maxPhaseDurationMs =
                    LOAD_CONTROL_CADENCE_MS *
                        ABRUPT_LAYER_SIZE_PROFILE_STEPS - 1L,
            ),
        )

        listOf(gradualTooShort, abruptTooShort, cappedBelowBoundary).forEach { decision ->
            assertRejected(decision)
            assertTrue(decision.rejectionReason!!.contains("layer-size waveform"))
        }
        assertAccepted(abruptAtBoundary)
    }

    @Test
    fun pulseOnAndOffWindowsMustEachSpanAControlTick() {
        val undersampled = ScenarioSafetyPolicy.evaluate(
            scenario(
                phases = listOf(
                    phase(
                        durationMs = 500L,
                        transition = TransitionSpec(
                            mode = TransitionMode.PULSE_BURST,
                            cycleMs = 500L,
                            dutyCycle = 0.1f,
                        ),
                    ),
                ),
            ),
            limits(),
        )
        val observable = ScenarioSafetyPolicy.evaluate(
            scenario(
                phases = listOf(
                    phase(
                        durationMs = 1_000L,
                        transition = TransitionSpec(
                            mode = TransitionMode.PULSE_BURST,
                            cycleMs = 1_000L,
                            dutyCycle = 0.1f,
                        ),
                    ),
                ),
            ),
            limits(),
        )

        assertRejected(undersampled)
        assertTrue(undersampled.rejectionReason!!.contains("ON/OFF"))
        assertAccepted(observable)
    }

    @Test
    fun scenarioBudgetSmallerThanPhaseCountIsRejected() {
        val decision = ScenarioSafetyPolicy.evaluate(
            scenario(
                phases = listOf(
                    phase(id = "one"),
                    phase(id = "two"),
                ),
            ),
            limits(maxScenarioDurationMs = 1),
        )

        assertRejected(decision)
    }

    private fun assertAccepted(decision: ScenarioSafetyDecision): ScenarioSpec {
        assertNull(decision.rejectionReason)
        return assertNotNull(decision.effectiveScenario).let {
            requireNotNull(decision.effectiveScenario)
        }
    }

    private fun assertRejected(decision: ScenarioSafetyDecision) {
        assertNull(decision.effectiveScenario)
        assertNotNull(decision.rejectionReason)
    }

    private fun assertRejected(spec: ScenarioSpec) {
        assertRejected(ScenarioSafetyPolicy.evaluate(spec, limits()))
    }

    private fun scenario(
        id: String = "scenario",
        phases: List<PhaseSpec> = listOf(phase()),
    ) = ScenarioSpec(
        id = id,
        name = "Scenario",
        description = "Safety policy test",
        category = ScenarioCategory.MIXED,
        risk = RiskLevel.MEDIUM,
        tags = emptySet(),
        phases = phases,
    )

    private fun phase(
        id: String = "phase",
        durationMs: Long = 1_000,
        activeLayers: Int = 1,
        producerFps: Float = 60f,
        requestedDisplayHz: Float = 60f,
        backend: LayerBackend = LayerBackend.INDEPENDENT_SURFACES,
        bufferSize: BufferSize = BufferSize.DISPLAY,
        workloads: LoadSetpoints = LoadSetpoints(),
        includeGlLayer: Boolean = false,
        transition: TransitionSpec = TransitionSpec(),
        pixelRoute: PixelRoute = PixelRoute.RGB_8888,
        layerSizeProfile: LayerSizeProfile = LayerSizeProfile.FULL_SCREEN,
        hwcCompositionExpectation: HwcCompositionExpectation =
            HwcCompositionExpectation.NONE,
    ) = PhaseSpec(
        id = id,
        label = id,
        durationMs = durationMs,
        activeLayers = activeLayers,
        producerFps = producerFps,
        requestedDisplayHz = requestedDisplayHz,
        backend = backend,
        pixelRoute = pixelRoute,
        bufferSize = bufferSize,
        motion = MotionProfile.STATIC,
        layerSizeProfile = layerSizeProfile,
        workloads = workloads,
        includeGlLayer = includeGlLayer,
        transition = transition,
        hwcCompositionExpectation = hwcCompositionExpectation,
    )

    private fun limits(
        displayWidthPx: Int = 100,
        displayHeightPx: Int = 100,
        maxLayers: Int = 20,
        maxProducerFps: Float = 120f,
        maxPhaseDurationMs: Long = 60_000,
        maxScenarioDurationMs: Long = 600_000,
        maxGraphicsBytes: Long = 1_000_000_000,
        maxCpuLoad: Float = 1f,
        maxMemoryLoad: Float = 1f,
        maxGpuLoad: Float = 1f,
        maxNpuLoad: Float = 1f,
    ) = RenderSafetyLimits(
        displayWidthPx = displayWidthPx,
        displayHeightPx = displayHeightPx,
        maxLayers = maxLayers,
        maxProducerFps = maxProducerFps,
        maxPhaseDurationMs = maxPhaseDurationMs,
        maxScenarioDurationMs = maxScenarioDurationMs,
        maxGraphicsBytes = maxGraphicsBytes,
        maxCpuLoad = maxCpuLoad,
        maxMemoryLoad = maxMemoryLoad,
        maxGpuLoad = maxGpuLoad,
        maxNpuLoad = maxNpuLoad,
    )
}
