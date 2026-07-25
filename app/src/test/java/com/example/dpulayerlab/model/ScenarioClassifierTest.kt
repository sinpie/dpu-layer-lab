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
    fun dpuBurstConditionUsesTypedLowToHighStepInsteadOfNamesOrTags() {
        val low = phase("low").copy(
            activeLayers = 1,
            producerFps = 30f,
            requestedDisplayHz = 60f,
            transition = TransitionSpec(mode = TransitionMode.STEP),
        )
        val high = low.copy(
            id = "high",
            activeLayers = 4,
            producerFps = 120f,
            requestedDisplayHz = 120f,
            transition = TransitionSpec(mode = TransitionMode.STEP),
        )
        val burst = scenario("typed-burst", low).copy(
            tags = emptySet(),
            phases = listOf(low, high),
        )
        val misleadingTag = scenario("tag-only", low).copy(
            tags = setOf("DPU low→high burst"),
            phases = listOf(low, low.copy(id = "still-low")),
        )
        val gradual = burst.copy(
            id = "gradual",
            phases = listOf(
                low,
                high.copy(
                    transition = TransitionSpec(
                        mode = TransitionMode.LINEAR_RAMP,
                        transitionDurationMs = 2_000L,
                    ),
                ),
            ),
        )
        val fpsOnly = burst.copy(
            id = "fps-only",
            phases = listOf(low, high.copy(activeLayers = low.activeLayers)),
        )

        assertTrue(ScenarioCondition.DPU_BURST in ScenarioClassifier.conditions(burst))
        assertFalse(
            ScenarioCondition.DPU_BURST in ScenarioClassifier.conditions(misleadingTag),
        )
        assertFalse(ScenarioCondition.DPU_BURST in ScenarioClassifier.conditions(gradual))
        assertFalse(ScenarioCondition.DPU_BURST in ScenarioClassifier.conditions(fpsOnly))
    }

    @Test
    fun dpuBurstRequiresMeaningfulLayerTargetAndDeltaAtTheBoundary() {
        val lowOne = phase("low-one").copy(
            activeLayers = 1,
            producerFps = 30f,
            requestedDisplayHz = 60f,
            transition = TransitionSpec(mode = TransitionMode.STEP),
        )
        val lowTwo = lowOne.copy(id = "low-two", activeLayers = 2)
        fun hasBurst(from: PhaseSpec, targetLayers: Int): Boolean {
            val high = from.copy(
                id = "high-$targetLayers",
                activeLayers = targetLayers,
                producerFps = 90f,
                requestedDisplayHz = 90f,
            )
            val candidate = scenario("candidate-$targetLayers", from).copy(
                phases = listOf(from, high),
            )
            return ScenarioCondition.DPU_BURST in ScenarioClassifier.conditions(candidate)
        }

        assertFalse(hasBurst(lowOne, 2))
        assertFalse(hasBurst(lowOne, 3))
        assertTrue(hasBurst(lowOne, 4))
        assertFalse(hasBurst(lowTwo, 4))
        assertTrue(hasBurst(lowTwo, 5))
    }

    @Test
    fun hwcCompositionConditionsUseTypedExpectationsInsteadOfTags() {
        val base = phase("base")
        val device = scenario(
            "device",
            base.copy(
                hwcCompositionExpectation = HwcCompositionExpectation.DEVICE_ONLY,
            ),
        )
        val client = scenario(
            "client",
            base.copy(
                hwcCompositionExpectation = HwcCompositionExpectation.CLIENT_REQUIRED,
            ),
        )
        val both = device.copy(phases = device.phases + client.phases)
        val tagOnly = scenario("tag-only", base).copy(
            tags = setOf("DEVICE_ONLY", "CLIENT_REQUIRED"),
        )

        assertTrue(
            ScenarioCondition.HWC_DEVICE_ONLY in ScenarioClassifier.conditions(device),
        )
        assertFalse(
            ScenarioCondition.HWC_CLIENT_REQUIRED in ScenarioClassifier.conditions(device),
        )
        assertTrue(
            ScenarioCondition.HWC_CLIENT_REQUIRED in ScenarioClassifier.conditions(client),
        )
        assertFalse(
            ScenarioCondition.HWC_DEVICE_ONLY in ScenarioClassifier.conditions(client),
        )
        assertTrue(
            setOf(
                ScenarioCondition.HWC_DEVICE_ONLY,
                ScenarioCondition.HWC_CLIENT_REQUIRED,
            ).all(ScenarioClassifier.conditions(both)::contains),
        )
        assertFalse(
            ScenarioCondition.HWC_DEVICE_ONLY in ScenarioClassifier.conditions(tagOnly),
        )
        assertFalse(
            ScenarioCondition.HWC_CLIENT_REQUIRED in ScenarioClassifier.conditions(tagOnly),
        )
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
