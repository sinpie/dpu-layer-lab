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
                        bufferSize = BufferSize.UHD_8K,
                    ),
                ),
            ),
            limits(maxGraphicsBytes = 120_000),
        )

        assertEquals(20, assertAccepted(decision).phases.single().activeLayers)
    }

    @Test
    fun singleGlLayerUsesDisplayBufferInsteadOfRequestedPrimarySize() {
        val decision = ScenarioSafetyPolicy.evaluate(
            scenario(
                phases = listOf(
                    phase(
                        activeLayers = 1,
                        bufferSize = BufferSize.UHD_8K,
                        includeGlLayer = true,
                    ),
                ),
            ),
            limits(maxGraphicsBytes = 120_000),
        )

        assertEquals(1, assertAccepted(decision).phases.single().activeLayers)
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
    fun totalDurationIsCappedWithoutCreatingZeroLengthPhases() {
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

        val durations = assertAccepted(decision).phases.map { it.durationMs }
        assertEquals(100L, durations.fold(0L, Long::plus))
        assertTrue(durations.all { it > 0L })
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
    ) = PhaseSpec(
        id = id,
        label = id,
        durationMs = durationMs,
        activeLayers = activeLayers,
        producerFps = producerFps,
        requestedDisplayHz = requestedDisplayHz,
        backend = backend,
        pixelRoute = PixelRoute.RGB_8888,
        bufferSize = bufferSize,
        motion = MotionProfile.STATIC,
        workloads = workloads,
        includeGlLayer = includeGlLayer,
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
