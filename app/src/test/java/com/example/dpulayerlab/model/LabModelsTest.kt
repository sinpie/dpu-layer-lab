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
    fun gaugeUnavailableDoesNotInventAValue() {
        assertEquals("N/A", Gauge().display())
        assertEquals("N/A", Gauge(Float.NaN, "%").display())
        assertEquals("60.0 Hz", Gauge(60f, " Hz").display(1))
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
}
