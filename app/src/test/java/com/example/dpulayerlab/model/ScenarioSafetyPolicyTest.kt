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
                            memory = -0.5f,
                            gpu = 0.9f,
                            npu = 0.8f,
                        ),
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
        assertEquals(LoadSetpoints(0.5f, 0f, 0.6f, 0.7f), actual.workloads)
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
        workloads = workloads,
        includeGlLayer = includeGlLayer,
        transition = transition,
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
