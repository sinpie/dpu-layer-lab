package com.example.dpulayerlab.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenarioPlanPolicyTest {
    @Test
    fun restoredRepeatCountIsAlwaysNormalizedToBothLimits() {
        assertEquals(10, ScenarioPlanPolicy.normalizeRepeatCount(queueSize = 0, requested = 99))
        assertEquals(10, ScenarioPlanPolicy.normalizeRepeatCount(queueSize = 1, requested = 11))
        assertEquals(10, ScenarioPlanPolicy.normalizeRepeatCount(queueSize = 10, requested = 10))
        assertEquals(1, ScenarioPlanPolicy.normalizeRepeatCount(queueSize = 40, requested = 0))
        assertEquals(
            4,
            ScenarioPlanPolicy.normalizeRepeatCount(
                queueSize = 10,
                requested = 10,
                source = PlanSource.EXTERNAL_INTENT,
            ),
        )
    }

    @Test
    fun emptyQueueAndInvalidRepeatCountsAreRejected() {
        assertTrue(
            ScenarioPlanPolicy.validate(ScenarioRunPlan(emptyList()))
                ?.contains("must not be empty") == true,
        )
        assertTrue(
            ScenarioPlanPolicy.validate(ScenarioRunPlan(listOf(scenario()), repeatCount = 0))
                ?.contains("repeat count") == true,
        )
        assertTrue(
            ScenarioPlanPolicy.validate(
                ScenarioRunPlan(
                    listOf(scenario()),
                    repeatCount = ScenarioPlanPolicy.MAX_REPEAT_COUNT + 1,
                ),
            )?.contains("repeat count") == true,
        )
    }

    @Test
    fun duplicateScenariosAreAllowedAndOrderIsPreserved() {
        val first = scenario("A")
        val second = scenario("B")
        val queue = listOf(first, second, first)
        val plan = ScenarioRunPlan(queue, repeatCount = 2)

        assertTrue(ScenarioPlanPolicy.ALLOW_DUPLICATE_SCENARIOS)
        assertNull(ScenarioPlanPolicy.validate(plan))
        assertEquals(listOf("A", "B", "A"), plan.scenarios.map { it.id })
        assertEquals(6, plan.totalRuns)
    }

    @Test
    fun expandedRunCountIsBoundedWithoutIntegerOverflow() {
        val acceptedQueue = List(ScenarioPlanPolicy.MAX_QUEUE_ENTRIES) { scenario("same") }
        val accepted = ScenarioRunPlan(
            acceptedQueue,
            repeatCount = ScenarioPlanPolicy.MAX_REPEAT_COUNT,
            source = PlanSource.USER_SELECTION,
        )
        assertEquals(ScenarioPlanPolicy.MAX_USER_TOTAL_PLAN_RUNS, accepted.totalRuns)
        assertNull(ScenarioPlanPolicy.validate(accepted))

        val rejected = ScenarioRunPlan(
            List(5) { scenario("external") },
            repeatCount = ScenarioPlanPolicy.MAX_REPEAT_COUNT,
            source = PlanSource.EXTERNAL_INTENT,
        )
        assertTrue(
            ScenarioPlanPolicy.validate(rejected)
                ?.contains("maximum") == true,
        )
    }

    @Test
    fun durationMultiplierScalesEveryPhaseAndTransitionWindowOnce() {
        val base = scenario().copy(
            phases = listOf(
                scenario().phases.single().copy(
                    durationMs = 2_000L,
                    transition = TransitionSpec(
                        mode = TransitionMode.TRIANGLE_WAVE,
                        transitionDurationMs = 500L,
                        cycleMs = 1_000L,
                    ),
                ),
            ),
        )
        val requested = ScenarioRunPlan(
            scenarios = listOf(base),
            repeatCount = 2,
            durationMultiplier = 10,
        )
        assertNull(ScenarioPlanPolicy.validate(requested))
        assertEquals(40_000L, requested.estimatedDurationMs)

        val materialized = requested.materializeDurationMultiplier()
        val phase = materialized.scenarios.single().phases.single()
        assertEquals(20_000L, phase.durationMs)
        assertEquals(5_000L, phase.transition.transitionDurationMs)
        assertEquals(10_000L, phase.transition.cycleMs)
        assertEquals(1, materialized.durationMultiplier)
        assertEquals(2_000L, base.phases.single().durationMs)
        assertEquals(500L, base.phases.single().transition.transitionDurationMs)
        assertEquals(1_000L, base.phases.single().transition.cycleMs)
        assertTrue(materialized.scenarios.single() !== base)
        assertTrue(materialized.scenarios.single().phases.single() !== base.phases.single())
    }

    @Test
    fun hundredTimesMaterializationStillPassesThroughExistingDurationSafetyCaps() {
        val base = scenario(durationMs = 20_000L).copy(
            phases = listOf(
                scenario(durationMs = 20_000L).phases.single().copy(
                    transition = TransitionSpec(
                        mode = TransitionMode.LINEAR_RAMP,
                        transitionDurationMs = 4_000L,
                        cycleMs = 1_000L,
                    ),
                ),
            ),
        )
        val materialized = ScenarioRunPlan(
            scenarios = listOf(base),
            durationMultiplier = 100,
        ).materializeDurationMultiplier()
        val decision = ScenarioSafetyPolicy.evaluate(
            materialized.scenarios.single(),
            RenderSafetyLimits(
                displayWidthPx = 1_920,
                displayHeightPx = 1_080,
                maxLayers = 20,
                maxProducerFps = 120f,
                maxPhaseDurationMs = 600_000L,
                maxScenarioDurationMs = 1_800_000L,
                maxGraphicsBytes = Long.MAX_VALUE,
                maxCpuLoad = 1f,
                maxMemoryLoad = 1f,
                maxGpuLoad = 1f,
                maxNpuLoad = 1f,
            ),
        )

        assertNull(decision.rejectionReason)
        val effective = requireNotNull(decision.effectiveScenario).phases.single()
        assertEquals(600_000L, effective.durationMs)
        assertEquals(120_000L, effective.transition.transitionDurationMs)
        assertEquals(100_000L, effective.transition.cycleMs)
        assertTrue(decision.adjustments.any { it.contains("duration") })
    }

    @Test
    fun cyclicDurationSafetyCapScalesTheMaterializedCycle() {
        val base = scenario(durationMs = 20_000L).copy(
            phases = listOf(
                scenario(durationMs = 20_000L).phases.single().copy(
                    transition = TransitionSpec(
                        mode = TransitionMode.TRIANGLE_WAVE,
                        cycleMs = 1_000L,
                    ),
                ),
            ),
        )
        val materialized = ScenarioRunPlan(
            scenarios = listOf(base),
            durationMultiplier = 100,
        ).materializeDurationMultiplier()
        val decision = ScenarioSafetyPolicy.evaluate(
            materialized.scenarios.single(),
            RenderSafetyLimits(
                displayWidthPx = 1_920,
                displayHeightPx = 1_080,
                maxLayers = 20,
                maxProducerFps = 120f,
                maxPhaseDurationMs = 600_000L,
                maxScenarioDurationMs = 1_800_000L,
                maxGraphicsBytes = Long.MAX_VALUE,
                maxCpuLoad = 1f,
                maxMemoryLoad = 1f,
                maxGpuLoad = 1f,
                maxNpuLoad = 1f,
            ),
        )

        assertNull(decision.rejectionReason)
        val effective = requireNotNull(decision.effectiveScenario).phases.single()
        assertEquals(600_000L, effective.durationMs)
        assertEquals(30_000L, effective.transition.cycleMs)
    }

    @Test
    fun durationMultiplierRejectsUnsupportedAndOverflowingValues() {
        assertTrue(
            ScenarioPlanPolicy.validate(
                ScenarioRunPlan(listOf(scenario()), durationMultiplier = 3),
            )?.contains("duration multiplier") == true,
        )
        val hostile = scenario().copy(
            phases = listOf(
                scenario().phases.single().copy(
                    transition = TransitionSpec(cycleMs = Long.MAX_VALUE),
                ),
            ),
        )
        assertTrue(
            ScenarioPlanPolicy.validate(
                ScenarioRunPlan(listOf(hostile), durationMultiplier = 100),
            )?.contains("safely scale") == true,
        )
    }

    @Test
    fun estimatedDurationSaturatesInsteadOfWrapping() {
        val huge = scenario("huge", durationMs = Long.MAX_VALUE)
        val plan = ScenarioRunPlan(listOf(huge, huge), repeatCount = 2)

        assertEquals(Long.MAX_VALUE, plan.estimatedDurationMs)
        assertTrue(plan.estimatedDurationMs >= 0L)
    }

    @Test
    fun planProgressUsesCompletedRunsPlusCurrentScenarioFraction() {
        val current = scenario()
        val running = PlanProgress(
            state = PlanState.RUNNING,
            source = PlanSource.USER_SELECTION,
            repeatIndex = 1,
            repeatCount = 3,
            queueIndex = 2,
            queueSize = 4,
            completedRuns = 5,
            totalRuns = 12,
            currentScenario = current,
            currentRunFraction = 0.5f,
        )

        assertTrue(running.active)
        assertEquals(2, running.currentRepeat)
        assertEquals(3, running.currentQueuePosition)
        assertEquals(5.5f / 12f, running.overallFraction)

        val malformed = running.copy(currentRunFraction = Float.NaN)
        assertEquals(5f / 12f, malformed.overallFraction)

        val complete = running.copy(
            state = PlanState.COMPLETE,
            completedRuns = 12,
            currentScenario = null,
            currentRunFraction = 0f,
        )
        assertEquals(1f, complete.overallFraction)
    }

    private fun scenario(
        id: String = "scenario",
        durationMs: Long = 1_000L,
    ) = ScenarioSpec(
        id = id,
        name = id,
        description = "",
        category = ScenarioCategory.MIXED,
        risk = RiskLevel.LOW,
        tags = emptySet(),
        phases = listOf(
            PhaseSpec(
                id = "phase",
                label = "phase",
                durationMs = durationMs,
                activeLayers = 1,
                producerFps = 60f,
                requestedDisplayHz = 60f,
                backend = LayerBackend.INDEPENDENT_SURFACES,
                pixelRoute = PixelRoute.RGB_8888,
                bufferSize = BufferSize.DISPLAY,
                motion = MotionProfile.STATIC,
            ),
        ),
    )
}
