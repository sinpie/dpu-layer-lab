package com.example.dpulayerlab.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadTransitionEvaluatorTest {
    @Test
    fun stepKeepsOriginAtPreflightThenSwitchesOnFirstActiveTick() {
        val spec = TransitionSpec(mode = TransitionMode.STEP)

        assertEquals(0f, LoadTransitionEvaluator.factorAt(spec, 0L, 1_000L))
        assertEquals(1f, LoadTransitionEvaluator.factorAt(spec, 1L, 1_000L))
    }

    @Test
    fun linearRampIsBoundedAndHoldsAtTarget() {
        val spec = TransitionSpec(
            mode = TransitionMode.LINEAR_RAMP,
            transitionDurationMs = 4_000L,
        )

        assertEquals(0f, LoadTransitionEvaluator.factorAt(spec, -1L, 8_000L))
        assertEquals(0.5f, LoadTransitionEvaluator.factorAt(spec, 2_000L, 8_000L))
        assertEquals(1f, LoadTransitionEvaluator.factorAt(spec, 4_000L, 8_000L))
        assertEquals(1f, LoadTransitionEvaluator.factorAt(spec, Long.MAX_VALUE, 8_000L))
    }

    @Test
    fun staircaseUsesOnlyConfiguredNumberOfSteps() {
        val spec = TransitionSpec(
            mode = TransitionMode.STAIRCASE,
            transitionDurationMs = 8_000L,
            stepCount = 4,
        )

        assertEquals(0f, LoadTransitionEvaluator.factorAt(spec, 1_999L, 10_000L))
        assertEquals(
            1f / 3f,
            LoadTransitionEvaluator.factorAt(spec, 2_000L, 10_000L),
        )
        assertEquals(1f, LoadTransitionEvaluator.factorAt(spec, 6_000L, 10_000L))
        assertEquals(1f, LoadTransitionEvaluator.factorAt(spec, 7_999L, 10_000L))
        assertEquals(1f, LoadTransitionEvaluator.factorAt(spec, 8_000L, 10_000L))
    }

    @Test
    fun pulseAndTriangleAreDeterministicAtCycleBoundaries() {
        val pulse = TransitionSpec(
            mode = TransitionMode.PULSE_BURST,
            cycleMs = 2_000L,
            dutyCycle = 0.25f,
        )
        assertEquals(1f, LoadTransitionEvaluator.factorAt(pulse, 0L, 8_000L))
        assertEquals(0f, LoadTransitionEvaluator.factorAt(pulse, 500L, 8_000L))
        assertEquals(1f, LoadTransitionEvaluator.factorAt(pulse, 2_000L, 8_000L))

        val triangle = TransitionSpec(
            mode = TransitionMode.TRIANGLE_WAVE,
            cycleMs = 4_000L,
        )
        assertEquals(0f, LoadTransitionEvaluator.factorAt(triangle, 0L, 8_000L))
        assertEquals(0.5f, LoadTransitionEvaluator.factorAt(triangle, 1_000L, 8_000L))
        assertEquals(1f, LoadTransitionEvaluator.factorAt(triangle, 2_000L, 8_000L))
        assertEquals(0.5f, LoadTransitionEvaluator.factorAt(triangle, 3_000L, 8_000L))
        assertEquals(0f, LoadTransitionEvaluator.factorAt(triangle, 4_000L, 8_000L))
    }

    @Test
    fun soakHasAttackHoldAndFullRecovery() {
        val spec = TransitionSpec(
            mode = TransitionMode.SOAK_RECOVERY,
            transitionDurationMs = 2_000L,
        )

        assertEquals(0f, LoadTransitionEvaluator.factorAt(spec, 0L, 10_000L))
        assertEquals(0.5f, LoadTransitionEvaluator.factorAt(spec, 1_000L, 10_000L))
        assertEquals(1f, LoadTransitionEvaluator.factorAt(spec, 5_000L, 10_000L))
        assertEquals(0.5f, LoadTransitionEvaluator.factorAt(spec, 9_000L, 10_000L))
        assertEquals(0f, LoadTransitionEvaluator.factorAt(spec, 10_000L, 10_000L))
    }

    @Test
    fun malformedConfigurationStillProducesFiniteUnitFactors() {
        val malformed = TransitionSpec(
            mode = TransitionMode.TRIANGLE_WAVE,
            transitionDurationMs = Long.MAX_VALUE,
            cycleMs = Long.MIN_VALUE,
            stepCount = Int.MAX_VALUE,
            dutyCycle = Float.NaN,
            floor = Float.POSITIVE_INFINITY,
        )

        listOf(Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE).forEach { elapsed ->
            val factor = LoadTransitionEvaluator.factorAt(
                malformed,
                elapsed,
                phaseDurationMs = 10_000L,
            )
            assertTrue(factor.isFinite())
            assertTrue(factor in 0f..1f)
        }
        assertEquals(
            1f,
            LoadTransitionEvaluator.factorAt(malformed, 1L, phaseDurationMs = 0L),
        )
    }

    @Test
    fun workloadInterpolationSupportsRampDownAndPreservesTargetShape() {
        val previous = LoadSetpoints(
            cpu = 1f,
            memory = 0.8f,
            gpu = 0.6f,
            npu = 0.4f,
            shape = LoadShape.PULSE,
        )
        val target = LoadSetpoints(shape = LoadShape.STEADY)

        val halfway = LoadTransitionEvaluator.interpolate(previous, target, 0.5f)

        assertEquals(0.5f, halfway.cpu)
        assertEquals(0.4f, halfway.memory)
        assertEquals(0.3f, halfway.gpu)
        assertEquals(0.2f, halfway.npu)
        assertEquals(LoadShape.STEADY, halfway.shape)
    }

    @Test
    fun phaseInterpolationRoundsLayersAndPreservesSafeOriginAtZero() {
        val previous = phase(
            layers = 12,
            fps = 120f,
            loads = LoadSetpoints(memory = 0.8f),
        )
        val sameTopology = phase(
            layers = 3,
            fps = 60f,
            loads = LoadSetpoints(),
            transition = TransitionSpec(TransitionMode.LINEAR_RAMP),
        )

        val halfway = LoadTransitionEvaluator.interpolate(previous, sameTopology, 0.5f)
        assertEquals(8, halfway.activeLayers)
        assertEquals(90f, halfway.producerFps)
        assertEquals(0.4f, halfway.workloads.memory)

        val differentTopology = sameTopology.copy(
            activeLayers = 1,
            bufferSize = BufferSize.UHD_8K,
            layerSizeProfile = LayerSizeProfile.SMALL_UNIFORM,
        )
        val safeTopologyChange = LoadTransitionEvaluator.interpolate(
            previous,
            differentTopology,
            0f,
        )
        assertEquals(previous.activeLayers, safeTopologyChange.activeLayers)
        assertEquals(previous.bufferSize, safeTopologyChange.bufferSize)
        assertEquals(previous.backend, safeTopologyChange.backend)
        assertEquals(previous.layerSizeProfile, safeTopologyChange.layerSizeProfile)

        val switchedTopology = LoadTransitionEvaluator.interpolate(
            previous,
            differentTopology,
            0.001f,
        )
        assertEquals(1, switchedTopology.activeLayers)
        assertEquals(BufferSize.UHD_8K, switchedTopology.bufferSize)
        assertEquals(LayerSizeProfile.SMALL_UNIFORM, switchedTopology.layerSizeProfile)

        val malformedStep = differentTopology.copy(
            activeLayers = Int.MIN_VALUE,
            transition = TransitionSpec(TransitionMode.STEP),
        )
        assertEquals(
            previous.activeLayers,
            LoadTransitionEvaluator.interpolate(previous, malformedStep, Float.NaN).activeLayers,
        )
    }

    @Test
    fun removingGlTopologyDropsGpuLoadBeforeTheNewTopologyRuns() {
        val previous = phase(
            layers = 2,
            fps = 60f,
            loads = LoadSetpoints(gpu = 0.8f),
        ).copy(includeGlLayer = true)
        val target = phase(
            layers = 1,
            fps = 60f,
            loads = LoadSetpoints(),
            transition = TransitionSpec(TransitionMode.LINEAR_RAMP),
        )

        val origin = LoadTransitionEvaluator.interpolate(previous, target, 0f)
        val switched = LoadTransitionEvaluator.interpolate(previous, target, 0.01f)

        assertTrue(origin.hasGpuLoadProducer())
        assertEquals(0.8f, origin.workloads.gpu)
        assertTrue(!switched.hasGpuLoadProducer())
        assertEquals(0f, switched.workloads.gpu)
    }

    @Test
    fun floorKeepsRecoveryAboveConfiguredMinimum() {
        val spec = TransitionSpec(
            mode = TransitionMode.TRIANGLE_WAVE,
            cycleMs = 4_000L,
            floor = 0.2f,
        )

        assertEquals(0.2f, LoadTransitionEvaluator.factorAt(spec, 0L, 8_000L))
        assertEquals(1f, LoadTransitionEvaluator.factorAt(spec, 2_000L, 8_000L))
    }

    @Test
    fun nonCyclicFloorCannotSkipTheMeasuredOrigin() {
        listOf(
            TransitionMode.STEP,
            TransitionMode.LINEAR_RAMP,
            TransitionMode.STAIRCASE,
            TransitionMode.SOAK_RECOVERY,
        ).forEach { mode ->
            val spec = TransitionSpec(
                mode = mode,
                transitionDurationMs = 2_000L,
                floor = 1f,
            )

            assertEquals(
                "mode=$mode",
                0f,
                LoadTransitionEvaluator.factorAt(spec, 0L, 8_000L),
            )
            assertEquals("mode=$mode", 0f, spec.boundedFor(8_000L).floor)
        }
    }

    @Test
    fun phaseInterpolationDoesNotOverflowBetweenFiniteExtremeFpsValues() {
        val previous = phase(
            layers = 1,
            fps = Float.MAX_VALUE,
            loads = LoadSetpoints(),
        )
        val target = phase(
            layers = 1,
            fps = -Float.MAX_VALUE,
            loads = LoadSetpoints(),
            transition = TransitionSpec(TransitionMode.LINEAR_RAMP),
        )

        val halfway = LoadTransitionEvaluator.interpolate(previous, target, 0.5f)

        assertTrue(halfway.producerFps.isFinite())
        assertTrue(halfway.requestedDisplayHz.isFinite())
        assertEquals(0f, halfway.producerFps, 0f)
        assertEquals(0f, halfway.requestedDisplayHz, 0f)
    }

    private fun phase(
        layers: Int,
        fps: Float,
        loads: LoadSetpoints,
        transition: TransitionSpec = TransitionSpec(),
    ) = PhaseSpec(
        id = "phase",
        label = "phase",
        durationMs = 10_000L,
        activeLayers = layers,
        producerFps = fps,
        requestedDisplayHz = fps,
        backend = LayerBackend.INDEPENDENT_SURFACES,
        pixelRoute = PixelRoute.RGB_8888,
        bufferSize = BufferSize.DISPLAY,
        motion = MotionProfile.STATIC,
        workloads = loads,
        transition = transition,
    )
}
