package com.example.dpulayerlab.model

import org.junit.Assert.assertEquals
import org.junit.Test

class LoadShapeEvaluatorTest {
    @Test
    fun pulseHasDeterministicTwoSecondEdges() {
        assertEquals(1f, LoadShapeEvaluator.factorAt(LoadShape.PULSE, 0L), 0f)
        assertEquals(1f, LoadShapeEvaluator.factorAt(LoadShape.PULSE, 1_999L), 0f)
        assertEquals(0f, LoadShapeEvaluator.factorAt(LoadShape.PULSE, 2_000L), 0f)
        assertEquals(1f, LoadShapeEvaluator.factorAt(LoadShape.PULSE, 4_000L), 0f)
    }

    @Test
    fun rampAndSawStayInsideUnitRangeAtCycleEdges() {
        assertEquals(0f, LoadShapeEvaluator.factorAt(LoadShape.RAMP, -1L), 0f)
        assertEquals(
            5_999f / 6_000f,
            LoadShapeEvaluator.factorAt(LoadShape.RAMP, 5_999L),
            0.000_001f,
        )
        assertEquals(0f, LoadShapeEvaluator.factorAt(LoadShape.RAMP, 6_000L), 0f)
        assertEquals(1f, LoadShapeEvaluator.factorAt(LoadShape.SAW, 4_000L), 0f)
        assertEquals(0f, LoadShapeEvaluator.factorAt(LoadShape.SAW, 8_000L), 0f)
    }

    @Test
    fun intensityClampsMalformedBaseWithoutChangingWaveform() {
        assertEquals(
            0f,
            LoadShapeEvaluator.intensityAt(Float.NaN, LoadShape.STEADY, 0L),
            0f,
        )
        assertEquals(
            0f,
            LoadShapeEvaluator.intensityAt(-2f, LoadShape.STEADY, 0L),
            0f,
        )
        assertEquals(
            1f,
            LoadShapeEvaluator.intensityAt(4f, LoadShape.STEADY, 0L),
            0f,
        )
        assertEquals(
            0.125f,
            LoadShapeEvaluator.intensityAt(0.5f, LoadShape.SAW, 1_000L),
            0.000_001f,
        )
    }
}
