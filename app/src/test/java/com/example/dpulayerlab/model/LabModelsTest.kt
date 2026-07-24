package com.example.dpulayerlab.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabModelsTest {
    @Test
    fun loadSetpointsAreClamped() {
        val normalized = LoadSetpoints(cpu = -1f, memory = 1.7f, gpu = 0.5f, npu = 4f).normalized()
        assertEquals(0f, normalized.cpu)
        assertEquals(1f, normalized.memory)
        assertEquals(0.5f, normalized.gpu)
        assertEquals(1f, normalized.npu)
    }

    @Test
    fun nonFiniteLoadSetpointsBecomeZero() {
        val normalized = LoadSetpoints(
            cpu = Float.NaN,
            memory = Float.POSITIVE_INFINITY,
            gpu = Float.NEGATIVE_INFINITY,
        ).normalized()

        assertEquals(0f, normalized.cpu)
        assertEquals(0f, normalized.memory)
        assertEquals(0f, normalized.gpu)
    }

    @Test
    fun executionNormalizationMakesWorkerCutoffExplicit() {
        val effective = LoadSetpoints(
            cpu = MIN_EFFECTIVE_LOAD,
            memory = Math.nextDown(MIN_EFFECTIVE_LOAD),
            gpu = Math.nextUp(MIN_EFFECTIVE_LOAD),
            npu = Float.NaN,
        ).normalizedForExecution()

        assertEquals(0f, effective.cpu)
        assertEquals(0f, effective.memory)
        assertEquals(Math.nextUp(MIN_EFFECTIVE_LOAD), effective.gpu)
        assertEquals(0f, effective.npu)
    }

    @Test
    fun gaugeUnavailableDoesNotInventAValue() {
        assertEquals("N/A", Gauge().display())
        assertEquals("N/A", Gauge(Float.NaN, "%").display())
        assertEquals("60.0 Hz", Gauge(60f, " Hz").display(1))
    }

    @Test
    fun selectedMediaDecoderRoutesIncludeRequiredSbwcButExcludeRgb() {
        val decoderRoutes = PixelRoute.entries.filter { it.usesSelectedMediaDecoder() }

        assertEquals(
            setOf(
                PixelRoute.YUV_420,
                PixelRoute.P010,
                PixelRoute.SBWC_AUTO,
                PixelRoute.SBWC_REQUIRED,
            ),
            decoderRoutes.toSet(),
        )
        assertTrue(!PixelRoute.RGB_8888.usesSelectedMediaDecoder())
        assertTrue(!PixelRoute.RGB_565.usesSelectedMediaDecoder())
    }

    @Test
    fun zOrderMotionIsTypedAsAClientProxyAndNeverPhysicalHwcEvidence() {
        val motion = MotionProfile.Z_ORDER_SWAP

        assertEquals(MotionSemantics.VIEW_CLIENT_Z_ORDER_PROXY, motion.semantics)
        assertTrue(!motion.semantics.changesPhysicalHwcZOrder)
        assertTrue(motion.label.contains("proxy", ignoreCase = true))
    }

    @Test
    fun runProgressIsBounded() {
        val scenario = ScenarioSpec(
            id = "test",
            name = "test",
            description = "",
            category = ScenarioCategory.MIXED,
            risk = RiskLevel.LOW,
            tags = emptySet(),
            phases = listOf(
                PhaseSpec(
                    id = "p",
                    label = "p",
                    durationMs = 1_000,
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
        assertEquals(1f, RunProgress(scenario = scenario, elapsedMs = 2_000).overallFraction)
        assertTrue(RunProgress().overallFraction == 0f)
    }

    @Test
    fun transitionProgressAndTargetFallbackAreSafeForUi() {
        val target = PhaseSpec(
            id = "target",
            label = "target",
            durationMs = 1_000,
            activeLayers = 4,
            producerFps = 120f,
            requestedDisplayHz = 120f,
            backend = LayerBackend.INDEPENDENT_SURFACES,
            pixelRoute = PixelRoute.RGB_8888,
            bufferSize = BufferSize.DISPLAY,
            motion = MotionProfile.STATIC,
        )

        val invalid = RunProgress(
            phase = target,
            transitionFraction = Float.NaN,
        )
        assertEquals(0f, invalid.boundedTransitionFraction)
        assertEquals(target, invalid.displayedTargetPhase)

        val explicit = RunProgress(
            phase = target.copy(activeLayers = 2),
            targetPhase = target,
            transitionFraction = 2f,
        )
        assertEquals(1f, explicit.boundedTransitionFraction)
        assertEquals(target, explicit.displayedTargetPhase)
    }

    @Test
    fun producerTeardownFailureIsAVisibleTerminalReason() {
        assertEquals(
            "generation=7 teardown timeout",
            terminalRunReason(
                listOf(
                    RunEvent(1L, "PHASE_END", "done"),
                    RunEvent(
                        2L,
                        "PRODUCER_TEARDOWN_UNCONFIRMED",
                        "generation=7 teardown timeout",
                    ),
                ),
            ),
        )
    }
}
