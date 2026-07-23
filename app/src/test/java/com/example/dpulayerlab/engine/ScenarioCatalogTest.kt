package com.example.dpulayerlab.engine

import com.example.dpulayerlab.model.BufferSize
import com.example.dpulayerlab.model.LayerBackend
import com.example.dpulayerlab.model.LoadSetpoints
import com.example.dpulayerlab.model.MotionProfile
import com.example.dpulayerlab.model.PixelRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenarioCatalogTest {
    @Test
    fun presetIdsAreUniqueAndPhasesAreRunnable() {
        val presets = ScenarioCatalog.presets
        assertEquals(presets.size, presets.map { it.id }.distinct().size)
        assertTrue(presets.size >= 10)
        presets.forEach { scenario ->
            assertTrue("${scenario.id} must have phases", scenario.phases.isNotEmpty())
            assertTrue("${scenario.id} duration", scenario.durationMs > 0)
            scenario.phases.forEach { phase ->
                assertTrue("${phase.id} duration", phase.durationMs > 0)
                assertTrue("${phase.id} layers", phase.activeLayers in 1..20)
                assertTrue("${phase.id} fps", phase.producerFps in 1f..240f)
                assertTrue("${phase.id} display", phase.requestedDisplayHz in 1f..240f)
            }
        }
    }

    @Test
    fun stressPresetsEndWithRecoveryOrCooldown() {
        val ids = setOf(
            "plane-staircase",
            "transform-storm",
            "resource-pulse",
            "adaptive-underrun-hunt",
            "mixed-soak",
        )
        ScenarioCatalog.presets.filter { it.id in ids }.forEach { scenario ->
            val last = scenario.phases.last()
            assertTrue("${scenario.id} must release CPU", last.workloads.cpu == 0f)
            assertTrue("${scenario.id} must release memory", last.workloads.memory == 0f)
            assertTrue("${scenario.id} recovery layer count", last.activeLayers <= 4)
        }
    }

    @Test
    fun customScenarioPreservesControls() {
        val custom = ScenarioCatalog.custom(
            layers = 11,
            durationSeconds = 42,
            producerFps = 90f,
            requestedHz = 120f,
            backend = LayerBackend.MIXED_SURFACE_TEXTURE,
            pixelRoute = PixelRoute.YUV_420,
            bufferSize = BufferSize.UHD_4K,
            motion = MotionProfile.ROTATE,
            loads = LoadSetpoints(cpu = 0.5f, memory = 0.8f, gpu = 0.3f),
        )
        val phase = custom.phases.single()
        assertTrue(custom.isCustom)
        assertEquals(11, phase.activeLayers)
        assertEquals(42_000L, phase.durationMs)
        assertEquals(90f, phase.producerFps)
        assertEquals(BufferSize.UHD_4K, phase.bufferSize)
        assertEquals(PixelRoute.YUV_420, phase.pixelRoute)
    }
}
