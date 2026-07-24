package com.example.dpulayerlab.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenarioClassifierTest {
    @Test
    fun emptySelectionKeepsInputOrderAndIdentity() {
        val scenarios = listOf(
            scenario("a", phase("a")),
            scenario("b", phase("b")),
        )

        val filtered = ScenarioClassifier.filter(scenarios, ScenarioSelectionFilter())

        assertTrue(filtered === scenarios)
    }

    @Test
    fun facetsUseOrWithinDimensionAndAcrossDimensions() {
        val gradualGpu = scenario(
            id = "gradual-gpu",
            phase = phase(
                id = "gradual",
                loads = LoadSetpoints(gpu = 0.8f),
                transition = TransitionSpec(
                    mode = TransitionMode.LINEAR_RAMP,
                    transitionDurationMs = 4_000L,
                ),
            ),
        )
        val instantMemory = scenario(
            id = "instant-memory",
            phase = phase(
                id = "instant",
                loads = LoadSetpoints(memory = 0.8f, shape = LoadShape.PULSE),
            ),
        )
        val selection = ScenarioSelectionFilter(
            categories = setOf(ScenarioCategory.TRANSITION),
            patterns = setOf(ScenarioChangePattern.GRADUAL),
            loadBands = setOf(ScenarioLoadBand.HIGH, ScenarioLoadBand.VERY_HIGH),
            conditions = setOf(ScenarioCondition.GPU, ScenarioCondition.NPU),
        )

        assertEquals(
            listOf(gradualGpu),
            ScenarioClassifier.filter(listOf(gradualGpu, instantMemory), selection),
        )
    }

    @Test
    fun classifierSeparatesInstantGradualCyclicAndDisplayOnly() {
        val instant = scenario(
            "instant",
            phase(
                "instant",
                loads = LoadSetpoints(memory = 0.7f, shape = LoadShape.PULSE),
            ),
        )
        val gradual = scenario(
            "gradual",
            phase(
                "gradual",
                loads = LoadSetpoints(gpu = 0.7f),
                transition = TransitionSpec(
                    mode = TransitionMode.SOAK_RECOVERY,
                    transitionDurationMs = 2_000L,
                ),
            ),
        )
        val displayOnly = scenario("display", phase("display"))

        assertTrue(ScenarioChangePattern.INSTANT in ScenarioClassifier.changePatterns(instant))
        assertTrue(ScenarioChangePattern.CYCLIC in ScenarioClassifier.changePatterns(instant))
        assertTrue(ScenarioChangePattern.GRADUAL in ScenarioClassifier.changePatterns(gradual))
        assertEquals(
            setOf(ScenarioChangePattern.STEADY),
            ScenarioClassifier.changePatterns(displayOnly),
        )
        assertTrue(
            ScenarioCondition.DISPLAY_ONLY in ScenarioClassifier.conditions(displayOnly),
        )
        assertFalse(ScenarioCondition.DISPLAY_ONLY in ScenarioClassifier.conditions(instant))
    }

    @Test
    fun smallFramePacingStepIsStillAnInstantControlChange() {
        val first = phase("60-fps").copy(
            producerFps = 60f,
            requestedDisplayHz = 120f,
            transition = TransitionSpec(mode = TransitionMode.STEP),
        )
        val pacingSteps = scenario("pacing", first).copy(
            phases = listOf(
                first,
                first.copy(id = "72-fps", producerFps = 72f),
            ),
        )

        val patterns = ScenarioClassifier.changePatterns(pacingSteps)

        assertTrue(ScenarioChangePattern.INSTANT in patterns)
        assertFalse(ScenarioChangePattern.STEADY in patterns)
    }

    @Test
    fun routeResolutionMotionAndRefreshConditionsAreDiscoverable() {
        val sbwc8k = scenario(
            "sbwc-8k",
            phase("sbwc-8k").copy(
                pixelRoute = PixelRoute.SBWC_REQUIRED,
                bufferSize = BufferSize.UHD_8K,
                motion = MotionProfile.ROTATE,
                producerFps = 120f,
                requestedDisplayHz = 120f,
            ),
        )
        val conditions = ScenarioClassifier.conditions(sbwc8k)

        assertTrue(ScenarioCondition.SBWC in conditions)
        assertTrue(ScenarioCondition.EIGHT_K in conditions)
        assertTrue(ScenarioCondition.TRANSFORM in conditions)
        assertTrue(ScenarioCondition.HIGH_REFRESH in conditions)
        assertTrue(ScenarioCondition.VIDEO in conditions)
        assertFalse(ScenarioCondition.RGB in conditions)
    }

    @Test
    fun rgbResolutionDoesNotPretendToUseVideoDecoder() {
        val rgb4k = scenario(
            "rgb-4k",
            phase("rgb-4k").copy(
                pixelRoute = PixelRoute.RGB_8888,
                bufferSize = BufferSize.UHD_4K,
            ),
        )

        val conditions = ScenarioClassifier.conditions(rgb4k)

        assertTrue(ScenarioCondition.RGB in conditions)
        assertTrue(ScenarioCondition.FOUR_K in conditions)
        assertFalse(ScenarioCondition.VIDEO in conditions)
    }

    @Test
    fun adaptiveCategoryAloneDoesNotPretendToExerciseDvfsBoundary() {
        val midLoad = ScenarioSpec(
            id = "mid-load",
            name = "mid-load",
            description = "mid-load",
            category = ScenarioCategory.ADAPTIVE,
            risk = RiskLevel.MEDIUM,
            tags = setOf("mid load", "paired reference"),
            phases = listOf(phase("mid-load")),
        )
        val clockRamp = midLoad.copy(tags = setOf("clock ramp"))

        assertFalse(ScenarioCondition.DVFS in ScenarioClassifier.conditions(midLoad))
        assertTrue(ScenarioCondition.DVFS in ScenarioClassifier.conditions(clockRamp))
    }

    @Test
    fun nonFiniteAndHostilePhaseValuesProduceBoundedScore() {
        val score = ScenarioClassifier.intensityScore(
            phase("hostile").copy(
                activeLayers = Int.MAX_VALUE,
                producerFps = Float.NaN,
                requestedDisplayHz = Float.POSITIVE_INFINITY,
                workloads = LoadSetpoints(
                    cpu = Float.NaN,
                    memory = Float.POSITIVE_INFINITY,
                    gpu = Float.NEGATIVE_INFINITY,
                    npu = 3f,
                ),
            ),
        )

        assertTrue(score in 0..100)
        assertEquals(ScenarioLoadBand.HIGH, ScenarioLoadBand.fromScore(score))
    }

    @Test
    fun loadBandsCoverEveryBoundedScoreWithoutGaps() {
        (0..100).forEach { score ->
            val matches = ScenarioLoadBand.entries.count { it.contains(score) }
            assertEquals("score=$score", 1, matches)
        }
    }

    private fun scenario(id: String, phase: PhaseSpec) = ScenarioSpec(
        id = id,
        name = id,
        description = id,
        category = ScenarioCategory.TRANSITION,
        risk = RiskLevel.MEDIUM,
        tags = emptySet(),
        phases = listOf(phase),
    )

    private fun phase(
        id: String,
        loads: LoadSetpoints = LoadSetpoints(),
        transition: TransitionSpec = TransitionSpec(),
    ) = PhaseSpec(
        id = id,
        label = id,
        durationMs = 10_000L,
        activeLayers = 8,
        producerFps = 90f,
        requestedDisplayHz = 120f,
        backend = LayerBackend.MIXED_SURFACE_TEXTURE,
        pixelRoute = PixelRoute.RGB_8888,
        bufferSize = BufferSize.DISPLAY,
        motion = MotionProfile.PARALLAX,
        workloads = loads,
        alphaOverlap = true,
        includeGlLayer = loads.gpu > 0f,
        transition = transition,
    )
}
