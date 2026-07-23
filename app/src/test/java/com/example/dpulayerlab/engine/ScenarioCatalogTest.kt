package com.example.dpulayerlab.engine

import com.example.dpulayerlab.model.BufferSize
import com.example.dpulayerlab.model.LayerBackend
import com.example.dpulayerlab.model.LoadSetpoints
import com.example.dpulayerlab.model.LoadShape
import com.example.dpulayerlab.model.MotionProfile
import com.example.dpulayerlab.model.PixelRoute
import com.example.dpulayerlab.model.RenderSafetyLimits
import com.example.dpulayerlab.model.ScenarioCategory
import com.example.dpulayerlab.model.ScenarioSafetyPolicy
import com.example.dpulayerlab.model.SelectedDecoderBuffer
import com.example.dpulayerlab.model.TransitionMode
import com.example.dpulayerlab.model.TransitionSpec
import com.example.dpulayerlab.model.usesSelectedMediaDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenarioCatalogTest {
    @Test
    fun presetIdsAreUniqueAndPhasesAreRunnable() {
        val presets = ScenarioCatalog.presets
        assertEquals(presets.size, presets.map { it.id }.distinct().size)
        assertTrue(presets.size >= 13)
        presets.forEach { scenario ->
            assertTrue("${scenario.id} must have phases", scenario.phases.isNotEmpty())
            assertTrue("${scenario.id} duration", scenario.durationMs > 0)
            scenario.phases.forEach { phase ->
                assertTrue("${phase.id} duration", phase.durationMs > 0)
                assertTrue("${phase.id} layers", phase.activeLayers in 1..20)
                assertTrue("${phase.id} fps", phase.producerFps in 1f..120f)
                assertTrue("${phase.id} display", phase.requestedDisplayHz in 1f..240f)
                val glWouldReplaceDedicatedPrimary =
                    phase.backend != LayerBackend.FLATTENED_TEXTURE &&
                        phase.activeLayers == 1 &&
                        phase.includeGlLayer &&
                        (
                            phase.pixelRoute.usesSelectedMediaDecoder() ||
                                phase.bufferSize != BufferSize.DISPLAY
                            )
                assertTrue(
                    "${scenario.id}/${phase.id} must preserve its primary before adding GL",
                    !glWouldReplaceDedicatedPrimary,
                )
                if (
                    phase.workloads.gpu > 0f &&
                    phase.backend != LayerBackend.FLATTENED_TEXTURE
                ) {
                    assertTrue(
                        "${scenario.id}/${phase.id} GPU load needs a GL producer",
                        phase.includeGlLayer,
                    )
                }
            }
        }
    }

    @Test
    fun allPresetsRemainRunnableThroughFullCapabilitySafetyPolicy() {
        val limits = RenderSafetyLimits(
            displayWidthPx = 1_920,
            displayHeightPx = 1_080,
            maxLayers = 20,
            maxProducerFps = 120f,
            maxPhaseDurationMs = 60_000L,
            maxScenarioDurationMs = 600_000L,
            maxGraphicsBytes = 2_000_000_000L,
            maxCpuLoad = 1f,
            maxMemoryLoad = 1f,
            maxGpuLoad = 1f,
            maxNpuLoad = 1f,
        )
        ScenarioCatalog.presets.forEach { scenario ->
            val decision = ScenarioSafetyPolicy.evaluate(
                scenario = scenario,
                limits = limits,
                selectedDecoderBuffer = SelectedDecoderBuffer(
                    widthPx = 7_680,
                    heightPx = 4_320,
                ),
            )
            assertTrue(
                "${scenario.id}: ${decision.rejectionReason}",
                decision.effectiveScenario != null,
            )
        }
    }

    @Test
    fun stressPresetsEndWithRecoveryOrCooldown() {
        val ids = setOf(
            "plane-staircase",
            "transform-storm",
            "resource-pulse",
            "adaptive-underrun-hunt",
            "dvfs-single-layer-wake",
            "dvfs-composition-shock",
            "mid-load-perturbation",
            "dvfs-video-shock",
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
    fun dvfsPresetsSettleThenIncreaseLoadAndRecover() {
        val dvfsIds = setOf(
            "dvfs-single-layer-wake",
            "dvfs-composition-shock",
            "dvfs-video-shock",
        )
        val scenarios = ScenarioCatalog.presets.filter { it.id in dvfsIds }
        assertEquals(dvfsIds, scenarios.mapTo(mutableSetOf()) { it.id })

        scenarios.forEach { scenario ->
            val settle = scenario.phases.first()
            val recovery = scenario.phases.last()
            assertEquals("${scenario.id} settle layer", 1, settle.activeLayers)
            assertTrue("${scenario.id} settle fps", settle.producerFps <= 30f)
            assertTrue(
                "${scenario.id} must contain a post-settle load edge",
                scenario.phases.drop(1).any {
                    it.activeLayers > settle.activeLayers ||
                        it.producerFps > settle.producerFps ||
                        it.workloads != settle.workloads
                },
            )
            assertTrue("${scenario.id} recovery layers", recovery.activeLayers <= 4)
            assertEquals("${scenario.id} recovery workloads", LoadSetpoints(), recovery.workloads)
        }

        val composition = ScenarioCatalog.byId("dvfs-composition-shock")!!
        assertTrue(composition.phases.any { it.alphaOverlap })
        assertTrue(composition.phases.any { it.includeGlLayer && it.workloads.memory > 0f })

        val video = ScenarioCatalog.byId("dvfs-video-shock")!!
        assertTrue(
            video.phases.any {
                it.pixelRoute == PixelRoute.YUV_420 &&
                    it.bufferSize == BufferSize.UHD_4K
            },
        )
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
            transition = TransitionSpec(
                mode = TransitionMode.LINEAR_RAMP,
                transitionDurationMs = 5_000L,
            ),
        )
        val phase = custom.phases.single()
        assertTrue(custom.isCustom)
        assertEquals(11, phase.activeLayers)
        assertEquals(42_000L, phase.durationMs)
        assertEquals(90f, phase.producerFps)
        assertEquals(BufferSize.UHD_4K, phase.bufferSize)
        assertEquals(PixelRoute.YUV_420, phase.pixelRoute)
        assertEquals(TransitionMode.LINEAR_RAMP, phase.transition.mode)
    }

    @Test
    fun rapidlyCreatedCustomScenariosHaveDistinctIds() {
        fun create() = ScenarioCatalog.custom(
            layers = 1,
            durationSeconds = 1,
            producerFps = 60f,
            requestedHz = 60f,
            backend = LayerBackend.INDEPENDENT_SURFACES,
            pixelRoute = PixelRoute.RGB_8888,
            bufferSize = BufferSize.DISPLAY,
            motion = MotionProfile.STATIC,
            loads = LoadSetpoints(),
        )

        val ids = List(100) { create().id }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun customSingleLayerGpuKeepsDedicatedPrimaryWithExplicitGlTail() {
        listOf(
            PixelRoute.YUV_420 to BufferSize.DISPLAY,
            PixelRoute.RGB_8888 to BufferSize.UHD_8K,
        ).forEach { (pixelRoute, bufferSize) ->
            val custom = ScenarioCatalog.custom(
                layers = 1,
                durationSeconds = 10,
                producerFps = 60f,
                requestedHz = 60f,
                backend = LayerBackend.INDEPENDENT_SURFACES,
                pixelRoute = pixelRoute,
                bufferSize = bufferSize,
                motion = MotionProfile.ZOOM_PAN,
                loads = LoadSetpoints(gpu = 0.5f),
            )

            val phase = custom.phases.single()
            assertEquals(2, phase.activeLayers)
            assertTrue(phase.includeGlLayer)
            assertTrue(phase.label.contains("requested 1L"))
            assertTrue(custom.description.contains("2L(primary + GL tail)"))
            assertTrue("requested 1L" in custom.tags)
            assertTrue("2L" in custom.tags)
        }
    }

    @Test
    fun customSmallNonZeroGpuRequestIsNotSilentlyDropped() {
        val custom = ScenarioCatalog.custom(
            layers = 1,
            durationSeconds = 10,
            producerFps = 60f,
            requestedHz = 60f,
            backend = LayerBackend.INDEPENDENT_SURFACES,
            pixelRoute = PixelRoute.YUV_420,
            bufferSize = BufferSize.DISPLAY,
            motion = MotionProfile.STATIC,
            loads = LoadSetpoints(gpu = 0.001f),
        )

        val phase = custom.phases.single()
        assertEquals(2, phase.activeLayers)
        assertTrue(phase.includeGlLayer)
        assertEquals(0.001f, phase.workloads.gpu)
    }

    @Test
    fun customSingleDisplayRgbGlOutputDoesNotInventAnotherLayer() {
        val custom = ScenarioCatalog.custom(
            layers = 1,
            durationSeconds = 10,
            producerFps = 60f,
            requestedHz = 60f,
            backend = LayerBackend.INDEPENDENT_SURFACES,
            pixelRoute = PixelRoute.RGB_8888,
            bufferSize = BufferSize.DISPLAY,
            motion = MotionProfile.STATIC,
            loads = LoadSetpoints(gpu = 0.5f),
        )

        val phase = custom.phases.single()
        assertEquals(1, phase.activeLayers)
        assertTrue(phase.includeGlLayer)
        assertTrue("requested 1L" !in custom.tags)
    }

    @Test
    fun customFlattenedBackendNormalizesUnsupportedDecoderAndExplicitSizeClaims() {
        val custom = ScenarioCatalog.custom(
            layers = 8,
            durationSeconds = 10,
            producerFps = 60f,
            requestedHz = 120f,
            backend = LayerBackend.FLATTENED_TEXTURE,
            pixelRoute = PixelRoute.P010,
            bufferSize = BufferSize.UHD_8K,
            motion = MotionProfile.TRANSFORM_STORM,
            loads = LoadSetpoints(gpu = 0.5f),
        )

        val phase = custom.phases.single()
        assertEquals(PixelRoute.RGB_8888, phase.pixelRoute)
        assertEquals(BufferSize.DISPLAY, phase.bufferSize)
        assertTrue(!phase.includeGlLayer)
        assertTrue(custom.description.contains("decoder 또는 4K/8K BufferQueue 부하가 아닙니다"))
        assertTrue("input normalized" in custom.tags)
        assertTrue(phase.label.contains("normalization"))
    }

    @Test
    fun eightKThirtyAndSixtyPresetsHaveIndependentMediaRequirements() {
        val thirty = checkNotNull(ScenarioCatalog.byId("8k-decoder-pressure"))
        val sixty = checkNotNull(ScenarioCatalog.byId("8k60-p010-pressure"))
        val thirtyDecoderPhases =
            thirty.phases.filter { it.pixelRoute.usesSelectedMediaDecoder() }
        val sixtyDecoderPhases =
            sixty.phases.filter { it.pixelRoute.usesSelectedMediaDecoder() }

        assertTrue(thirtyDecoderPhases.isNotEmpty())
        assertTrue(
            thirtyDecoderPhases.all {
                it.pixelRoute == PixelRoute.YUV_420 &&
                    it.bufferSize == BufferSize.UHD_8K &&
                    it.producerFps == 30f
            },
        )
        assertEquals(setOf("8K30 decoder", "8K 30fps local media"), thirty.requirements)

        assertTrue(sixtyDecoderPhases.isNotEmpty())
        assertTrue(
            sixtyDecoderPhases.all {
                it.pixelRoute == PixelRoute.P010 &&
                    it.bufferSize == BufferSize.UHD_8K &&
                    it.producerFps == 60f
            },
        )
        val sixtyPressure = sixtyDecoderPhases.single()
        assertTrue(sixtyPressure.includeGlLayer)
        assertEquals(
            "decoder primary + six overlays + GL tail",
            8,
            sixtyPressure.activeLayers,
        )
        assertEquals(
            setOf("8K60 10-bit decoder", "8K 60fps 10-bit local media"),
            sixty.requirements,
        )
        listOf(thirty, sixty).forEach { scenario ->
            assertEquals("release", scenario.phases.last().id)
            assertEquals(LoadSetpoints(), scenario.phases.last().workloads)
        }
    }

    @Test
    fun transitionPresetsCoverFastGradualCyclicAndRecoveryConditions() {
        val scenarios = ScenarioCatalog.presets.filter {
            it.category == ScenarioCategory.TRANSITION
        }
        assertEquals(3, scenarios.size)
        val modes = scenarios
            .flatMap { it.phases }
            .map { it.transition.mode }
            .toSet()
        assertTrue(TransitionMode.entries.all { it in modes })

        scenarios.forEach { scenario ->
            assertEquals(LoadSetpoints(), scenario.phases.first().workloads)
            val last = scenario.phases.last()
            assertEquals(LoadSetpoints(), last.workloads)
            assertTrue(last.activeLayers <= 4)
            scenario.phases
                .filter { it.transition.mode != TransitionMode.STEP }
                .forEach { phase ->
                    assertEquals(
                        "${scenario.id}/${phase.id} must avoid a second waveform",
                        LoadShape.STEADY,
                        phase.workloads.shape,
                    )
                    val bounded = phase.transition.boundedFor(phase.durationMs)
                    assertEquals(phase.transition, bounded)
                }
        }
    }

    @Test
    fun adaptiveAndSbwcPresetsKeepTheirExecutionContracts() {
        val adaptive = checkNotNull(ScenarioCatalog.byId("adaptive-underrun-hunt"))
        assertEquals(1, adaptive.phases.count { it.id == "hunt-recover" })
        assertEquals("hunt-recover", adaptive.phases.last().id)
        val hunt = adaptive.phases.dropLast(1)
        assertTrue(
            "adaptive layer pressure must increase strictly",
            hunt.zipWithNext().all { (before, after) ->
                after.activeLayers > before.activeLayers
            },
        )
        assertTrue(
            "adaptive memory envelope must increase strictly",
            hunt.zipWithNext().all { (before, after) ->
                after.workloads.memory > before.workloads.memory
            },
        )

        val sbwc = checkNotNull(ScenarioCatalog.byId("sbwc-matrix"))
        val selectedMediaComparisonRoutes = sbwc.phases
            .filter { it.id == "yuv" || it.id == "sbwc" }
            .map { it.pixelRoute }
        assertEquals(
            listOf(PixelRoute.YUV_420, PixelRoute.SBWC_REQUIRED),
            selectedMediaComparisonRoutes,
        )
        assertTrue(selectedMediaComparisonRoutes.all { it.usesSelectedMediaDecoder() })
    }

    @Test
    fun catalogCoversCompositionTransformsVideoAndIndependentBusRelease() {
        val pivot = checkNotNull(ScenarioCatalog.byId("composition-pivot"))
        assertTrue(pivot.phases.any { it.backend == LayerBackend.INDEPENDENT_SURFACES })
        assertTrue(pivot.phases.any { it.backend == LayerBackend.MIXED_SURFACE_TEXTURE })
        assertTrue(pivot.phases.any { it.backend == LayerBackend.FLATTENED_TEXTURE })

        val motions = ScenarioCatalog.presets
            .flatMap { it.phases }
            .mapTo(mutableSetOf()) { it.motion }
        assertTrue(MotionProfile.entries.all { it in motions })

        val routes = ScenarioCatalog.presets
            .flatMap { it.phases }
            .mapTo(mutableSetOf()) { it.pixelRoute }
        assertTrue(PixelRoute.YUV_420 in routes)
        assertTrue(PixelRoute.P010 in routes)
        assertTrue(PixelRoute.SBWC_REQUIRED in routes)

        val resourceScenarios = listOf(
            checkNotNull(ScenarioCatalog.byId("resource-pulse")),
            checkNotNull(ScenarioCatalog.byId("npu-cross-load")),
        )
        assertTrue(resourceScenarios.flatMap { it.phases }.any { it.workloads.cpu > 0f })
        assertTrue(resourceScenarios.flatMap { it.phases }.any { it.workloads.memory > 0f })
        assertTrue(resourceScenarios.flatMap { it.phases }.any { it.workloads.gpu > 0f })
        assertTrue(resourceScenarios.flatMap { it.phases }.any { it.workloads.npu > 0f })
        resourceScenarios.forEach { scenario ->
            assertEquals(LoadSetpoints(), scenario.phases.last().workloads)
        }
    }

    @Test
    fun catalogTransitionsHaveObservableCyclesAndTargetOrHoldWindows() {
        ScenarioCatalog.presets.flatMap { scenario ->
            scenario.phases.map { scenario.id to it }
        }.forEach { (scenarioId, phase) ->
            when (phase.transition.mode) {
                TransitionMode.LINEAR_RAMP,
                TransitionMode.STAIRCASE,
                -> if (phase.transition.transitionDurationMs > 0L) {
                    assertTrue(
                        "$scenarioId/${phase.id} must leave a target hold window",
                        phase.transition.transitionDurationMs < phase.durationMs,
                    )
                }

                TransitionMode.PULSE_BURST,
                TransitionMode.TRIANGLE_WAVE,
                -> {
                    assertTrue(
                        "$scenarioId/${phase.id} must complete a cycle",
                        phase.transition.cycleMs <= phase.durationMs,
                    )
                    assertEquals(
                        "$scenarioId/${phase.id} should use complete cycles",
                        0L,
                        phase.durationMs % phase.transition.cycleMs,
                    )
                }

                TransitionMode.SOAK_RECOVERY -> {
                    val edgeMs = phase.transition.transitionDurationMs
                    assertTrue(edgeMs > 0L)
                    assertTrue(
                        "$scenarioId/${phase.id} must include a hold window",
                        edgeMs * 2L < phase.durationMs,
                    )
                }

                TransitionMode.STEP -> Unit
            }
        }
    }
}
