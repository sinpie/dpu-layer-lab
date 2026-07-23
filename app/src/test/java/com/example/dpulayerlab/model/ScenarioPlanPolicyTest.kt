package com.example.dpulayerlab.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenarioPlanPolicyTest {
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
        val acceptedQueue = List(4) { scenario("same") }
        val accepted = ScenarioRunPlan(
            acceptedQueue,
            repeatCount = ScenarioPlanPolicy.MAX_REPEAT_COUNT,
        )
        assertEquals(ScenarioPlanPolicy.MAX_TOTAL_PLAN_RUNS, accepted.totalRuns)
        assertNull(ScenarioPlanPolicy.validate(accepted))

        val rejected = ScenarioRunPlan(
            acceptedQueue + scenario("extra"),
            repeatCount = ScenarioPlanPolicy.MAX_REPEAT_COUNT,
        )
        assertTrue(
            ScenarioPlanPolicy.validate(rejected)
                ?.contains("maximum") == true,
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
