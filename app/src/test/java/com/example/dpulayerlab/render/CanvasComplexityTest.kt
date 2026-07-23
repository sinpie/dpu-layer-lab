package com.example.dpulayerlab.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasComplexityTest {
    @Test
    fun flattenedSingleLayerGpuIntensityChangesBoundedHardwareCanvasWork() {
        assertEquals(0, flattenedSingleLayerExtraPasses(0f))
        assertEquals(1, flattenedSingleLayerExtraPasses(0.01f))
        assertTrue(
            flattenedSingleLayerExtraPasses(0.5f) >
                flattenedSingleLayerExtraPasses(0.01f),
        )
        assertEquals(8, flattenedSingleLayerExtraPasses(1f))
    }

    @Test
    fun invalidFlattenedComplexityFailsClosedAndFiniteValuesAreClamped() {
        assertEquals(0, flattenedSingleLayerExtraPasses(Float.NaN))
        assertEquals(0, flattenedSingleLayerExtraPasses(Float.NEGATIVE_INFINITY))
        assertEquals(0, flattenedSingleLayerExtraPasses(-1f))
        assertEquals(0, flattenedSingleLayerExtraPasses(Float.POSITIVE_INFINITY))
        assertEquals(8, flattenedSingleLayerExtraPasses(2f))
    }
}
