package com.example.dpulayerlab.render

import com.example.dpulayerlab.model.BufferSize
import com.example.dpulayerlab.model.BufferPresentation
import com.example.dpulayerlab.model.LayerBackend
import com.example.dpulayerlab.model.LayerSizeProfile
import com.example.dpulayerlab.model.LayerOrientation
import com.example.dpulayerlab.model.MotionProfile
import com.example.dpulayerlab.model.PhaseSpec
import com.example.dpulayerlab.model.PixelRoute
import com.example.dpulayerlab.model.coverageBitAt
import com.example.dpulayerlab.model.normalizedSizeForLayer
import com.example.dpulayerlab.model.requiredCoverageMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayerStageViewMathTest {
    @Test
    fun bufferPresentationMathDistinguishesFitFromOneToOneCrop() {
        val fit = bufferPresentationScalePacked(
            stageWidth = 1_920,
            stageHeight = 1_080,
            sourceWidth = 7_680,
            sourceHeight = 4_320,
            presentation = BufferPresentation.FIT,
            orientation = LayerOrientation.ROTATION_0,
        )
        assertEquals(1f, packedPresentationScaleX(fit), 0.0001f)
        assertEquals(1f, packedPresentationScaleY(fit), 0.0001f)

        val oneToOne = bufferPresentationScalePacked(
            stageWidth = 1_920,
            stageHeight = 1_080,
            sourceWidth = 7_680,
            sourceHeight = 4_320,
            presentation = BufferPresentation.PIXEL_1_TO_1_CROP,
            orientation = LayerOrientation.ROTATION_0,
        )
        assertEquals(4f, packedPresentationScaleX(oneToOne), 0.0001f)
        assertEquals(4f, packedPresentationScaleY(oneToOne), 0.0001f)
    }

    @Test
    fun ninetyDegreeEightKFitPreservesAspectInsidePortraitStage() {
        val packed = bufferPresentationScalePacked(
            stageWidth = 1_080,
            stageHeight = 2_400,
            sourceWidth = 7_680,
            sourceHeight = 4_320,
            presentation = BufferPresentation.FIT,
            orientation = LayerOrientation.ROTATION_90,
        )
        val preRotationWidth =
            1_080f * packedPresentationScaleX(packed)
        val preRotationHeight =
            2_400f * packedPresentationScaleY(packed)
        assertEquals(1_920f, preRotationWidth, 0.01f)
        assertEquals(1_080f, preRotationHeight, 0.01f)
        assertTrue(preRotationHeight <= 1_080f)
        assertTrue(preRotationWidth <= 2_400f)
    }

    @Test
    fun fixedQuarterTurnFitMotionIsClampedToLetterboxSlack() {
        assertEquals(
            0f,
            clampFitTranslation(
                requested = 400f,
                stageExtent = 1_080f,
                contentExtent = 1_080f,
            ),
            0f,
        )
        assertEquals(
            240f,
            clampFitTranslation(
                requested = 400f,
                stageExtent = 2_400f,
                contentExtent = 1_920f,
            ),
            0f,
        )
        assertEquals(
            -240f,
            clampFitTranslation(
                requested = -400f,
                stageExtent = 2_400f,
                contentExtent = 1_920f,
            ),
            0f,
        )
        assertEquals(0f, clampFitTranslation(Float.NaN, 2_400f, 1_920f), 0f)
    }

    @Test
    fun phaseDefaultsToFullScreenLayerGeometry() {
        val phase = PhaseSpec(
            id = "default-size",
            label = "Default size",
            durationMs = 1_000L,
            activeLayers = 20,
            producerFps = 120f,
            requestedDisplayHz = 120f,
            backend = LayerBackend.INDEPENDENT_SURFACES,
            pixelRoute = PixelRoute.RGB_8888,
            bufferSize = BufferSize.DISPLAY,
            motion = MotionProfile.STATIC,
        )

        assertEquals(LayerSizeProfile.FULL_SCREEN, phase.layerSizeProfile)
    }

    @Test
    fun layerSizeProfilesAreFiniteBoundedAndIndexStable() {
        LayerSizeProfile.entries.forEach { profile ->
            (0 until 20).forEach { index ->
                val first = profile.normalizedSizeForLayer(
                    layerIndex = index,
                    layerCount = 20,
                    phaseFraction = 0.375f,
                )
                val repeated = profile.normalizedSizeForLayer(
                    layerIndex = index,
                    layerCount = 20,
                    phaseFraction = 0.375f,
                )

                assertEquals(first, repeated)
                assertTrue(first.widthScale.isFinite())
                assertTrue(first.heightScale.isFinite())
                assertTrue(first.widthScale in 0.25f..1f)
                assertTrue(first.heightScale in 0.25f..1f)
                assertEquals(
                    first.widthScale * first.heightScale,
                    first.areaScale,
                    0.000_001f,
                )
            }
        }
    }

    @Test
    fun layerSizeProfilesPreserveEndpointsAndAbruptEdges() {
        val full = LayerSizeProfile.FULL_SCREEN.normalizedSizeForLayer(0, 20, 0f)
        val small = LayerSizeProfile.SMALL_UNIFORM.normalizedSizeForLayer(19, 20, 1f)
        val gradualStart =
            LayerSizeProfile.GRADUAL_SMALL_TO_FULL.normalizedSizeForLayer(0, 20, -1f)
        val gradualMiddle =
            LayerSizeProfile.GRADUAL_SMALL_TO_FULL.normalizedSizeForLayer(0, 20, 0.5f)
        val gradualEnd =
            LayerSizeProfile.GRADUAL_SMALL_TO_FULL.normalizedSizeForLayer(0, 20, 2f)

        assertEquals(1f, full.widthScale, 0f)
        assertEquals(1f, full.heightScale, 0f)
        assertEquals(0.30f, small.widthScale, 0f)
        assertEquals(0.30f, gradualStart.widthScale, 0f)
        assertEquals(0.65f, gradualMiddle.widthScale, 0.000_001f)
        assertEquals(1f, gradualEnd.widthScale, 0f)

        assertEquals(
            0.30f,
            LayerSizeProfile.ABRUPT_SMALL_FULL
                .normalizedSizeForLayer(0, 20, 0.124f)
                .widthScale,
            0f,
        )
        assertEquals(
            1f,
            LayerSizeProfile.ABRUPT_SMALL_FULL
                .normalizedSizeForLayer(0, 20, 0.125f)
                .widthScale,
            0f,
        )
        assertEquals(
            0.30f,
            LayerSizeProfile.ABRUPT_SMALL_FULL
                .normalizedSizeForLayer(0, 20, 0.25f)
                .widthScale,
            0f,
        )
        assertEquals(
            1f,
            LayerSizeProfile.ABRUPT_SMALL_FULL
                .normalizedSizeForLayer(0, 20, 1f)
                .widthScale,
            0f,
        )
    }

    @Test
    fun invalidLayerSizeInputsFailConservativelyToFullScreen() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { fraction ->
            LayerSizeProfile.entries.forEach { profile ->
                val size = profile.normalizedSizeForLayer(0, 1, fraction)
                assertEquals(1f, size.widthScale, 0f)
                assertEquals(1f, size.heightScale, 0f)
            }
        }

        val invalidIndex =
            LayerSizeProfile.SMALL_UNIFORM.normalizedSizeForLayer(-1, 20, 0f)
        val invalidCount =
            LayerSizeProfile.SMALL_UNIFORM.normalizedSizeForLayer(0, 21, 0f)
        assertEquals(1f, invalidIndex.areaScale, 0f)
        assertEquals(1f, invalidCount.areaScale, 0f)
    }

    @Test
    fun phaseFractionAndFullScreenStaggerStayBounded() {
        assertEquals(0f, normalizedLayerSizePhaseFraction(-1L, 1_000L), 0f)
        assertEquals(0f, normalizedLayerSizePhaseFraction(0L, 1_000L), 0f)
        assertEquals(
            0.5f,
            normalizedLayerSizePhaseFraction(500_000_000L, 1_000L),
            0.000_001f,
        )
        assertEquals(1f, normalizedLayerSizePhaseFraction(Long.MAX_VALUE, 1L), 0f)
        assertEquals(1f, normalizedLayerSizePhaseFraction(1L, 0L), 0f)

        val translations = (0 until 20).map { index ->
            layerProfileBaseTranslationX(
                stageWidth = 1_080,
                layerIndex = index,
                layerCount = 20,
                widthScale = 1f,
            )
        }
        assertEquals(20, translations.distinct().size)
        assertTrue(translations.zipWithNext().all { (left, right) -> right > left })
        assertTrue(translations.all { it in -1_080f..1_080f })
        val narrowSmallTranslations = (0 until 20).map { index ->
            layerProfileBaseTranslationX(
                stageWidth = 320,
                layerIndex = index,
                layerCount = 20,
                widthScale = 0.30f,
            )
        }
        val narrowVisibleLimit = 320f * (1f + 0.30f) * 0.5f - 1f
        assertTrue(
            narrowSmallTranslations.all {
                it in -narrowVisibleLimit..narrowVisibleLimit
            },
        )
        assertEquals(
            0f,
            layerProfileBaseTranslationY(
                stageHeight = 2_400,
                layerIndex = 19,
                layerCount = 20,
                heightScale = 1f,
            ),
            0f,
        )
        assertEquals(
            0f,
            layerProfileBaseTranslationX(
                stageWidth = 1_080,
                layerIndex = 20,
                layerCount = 20,
                widthScale = Float.NaN,
            ),
            0f,
        )
    }

    @Test
    fun controllerElapsedAnchorSurvivesRecoveryAndGenerationRebuild() {
        val resumedElapsed = anchoredLayerSizeElapsedNanos(
            elapsedAtAnchorNanos = 4_000_000_000L,
            anchorNanos = 50_000_000_000L,
            frameTimeNanos = 50_050_000_000L,
            durationMs = 10_000L,
        )
        assertEquals(4_050_000_000L, resumedElapsed)
        assertEquals(
            0.405f,
            normalizedLayerSizePhaseFraction(resumedElapsed, 10_000L),
            0.000_001f,
        )

        // A rebuild may happen much later in wall time; re-anchoring at the controller-owned
        // frozen elapsed must continue from 40.5%, not restart the waveform at zero.
        val afterGenerationRebuild = anchoredLayerSizeElapsedNanos(
            elapsedAtAnchorNanos = resumedElapsed,
            anchorNanos = 500_000_000_000L,
            frameTimeNanos = 500_100_000_000L,
            durationMs = 10_000L,
        )
        assertEquals(4_150_000_000L, afterGenerationRebuild)
        assertEquals(
            0.415f,
            normalizedLayerSizePhaseFraction(afterGenerationRebuild, 10_000L),
            0.000_001f,
        )
        assertEquals(
            10_000_000L,
            anchoredLayerSizeElapsedNanos(
                elapsedAtAnchorNanos = Long.MAX_VALUE,
                anchorNanos = 1L,
                frameTimeNanos = Long.MAX_VALUE,
                durationMs = 10L,
            ),
        )
    }

    @Test
    fun dynamicLayerSizeCadenceIsIndependentOfLowProducerFpsAndForcesFinalSample() {
        assertEquals(
            100_000_000L,
            layerTransformIntervalNanos(producerFps = 1f, dynamicLayerSize = true),
        )
        assertEquals(
            1_000_000_000L,
            layerTransformIntervalNanos(producerFps = 1f, dynamicLayerSize = false),
        )
        assertFalse(
            shouldApplyLayerTransform(
                elapsedSinceLastNanos = 99_999_999L,
                transformIntervalNanos = 100_000_000L,
                dynamicLayerSize = true,
                phaseFraction = 0.5f,
                lastAppliedPhaseFraction = 0.4f,
            ),
        )
        assertTrue(
            shouldApplyLayerTransform(
                elapsedSinceLastNanos = 1L,
                transformIntervalNanos = 100_000_000L,
                dynamicLayerSize = true,
                phaseFraction = 1f,
                lastAppliedPhaseFraction = 0.99f,
            ),
        )
        assertFalse(
            shouldApplyLayerTransform(
                elapsedSinceLastNanos = 1L,
                transformIntervalNanos = 100_000_000L,
                dynamicLayerSize = true,
                phaseFraction = 1f,
                lastAppliedPhaseFraction = 1f,
            ),
        )
    }

    @Test
    fun pendingGeometryRevisionFreezesDynamicSizeAndThenConsumesLatestFraction() {
        assertEquals(
            0.4f,
            layerSizeFractionForGeometryCommit(
                desiredPhaseFraction = 0.8f,
                lastAppliedPhaseFraction = 0.4f,
                dynamicLayerSize = true,
                geometryRevisionPending = true,
            ),
            0f,
        )
        assertEquals(
            0.8f,
            layerSizeFractionForGeometryCommit(
                desiredPhaseFraction = 0.8f,
                lastAppliedPhaseFraction = 0.4f,
                dynamicLayerSize = true,
                geometryRevisionPending = false,
            ),
            0f,
        )
        assertEquals(
            0.8f,
            layerSizeFractionForGeometryCommit(
                desiredPhaseFraction = 0.8f,
                lastAppliedPhaseFraction = 0.4f,
                dynamicLayerSize = false,
                geometryRevisionPending = true,
            ),
            0f,
        )
        assertEquals(
            0.8f,
            layerSizeFractionForGeometryCommit(
                desiredPhaseFraction = 0.8f,
                lastAppliedPhaseFraction = Float.NaN,
                dynamicLayerSize = true,
                geometryRevisionPending = true,
            ),
            0f,
        )
    }

    @Test
    fun minimumGradualWindowRetainsOriginMidAndEndpointAcrossCommonFrameRates() {
        listOf(30f, 60f, 120f).forEach { frameRate ->
            val profile = LayerSizeProfile.GRADUAL_SMALL_TO_FULL
            val durationMs = 200.0
            val frameIntervalMs = 1_000.0 / frameRate
            var elapsedMs = 0.0
            var pendingAckFrames = 0
            var pendingFraction = 0f
            var lastAppliedFraction = -1f
            var lastRequestedKey = -1
            var coverageMask = 0

            while (elapsedMs <= durationMs + 500.0) {
                if (pendingAckFrames > 0) {
                    pendingAckFrames--
                    if (pendingAckFrames == 0) {
                        coverageMask = coverageMask or profile.coverageBitAt(pendingFraction)
                    }
                }

                val desiredFraction = (elapsedMs / durationMs)
                    .coerceIn(0.0, 1.0)
                    .toFloat()
                val appliedFraction = layerSizeFractionForGeometryCommit(
                    desiredPhaseFraction = desiredFraction,
                    lastAppliedPhaseFraction = lastAppliedFraction,
                    dynamicLayerSize = true,
                    geometryRevisionPending = pendingAckFrames > 0,
                )
                val sampleKey = layerGeometrySampleKey(profile, appliedFraction)
                if (pendingAckFrames == 0 && sampleKey != lastRequestedKey) {
                    lastRequestedKey = sampleKey
                    pendingFraction = appliedFraction
                    pendingAckFrames = 2
                }
                lastAppliedFraction = appliedFraction
                elapsedMs += frameIntervalMs
            }

            assertEquals(
                "frameRate=$frameRate",
                profile.requiredCoverageMask(),
                coverageMask and profile.requiredCoverageMask(),
            )
        }
    }

    @Test
    fun geometrySampleKeyIsBoundedAndTracksDynamicSemanticSteps() {
        assertEquals(
            0,
            layerGeometrySampleKey(LayerSizeProfile.FULL_SCREEN, Float.NaN),
        )
        assertEquals(
            1,
            layerGeometrySampleKey(
                LayerSizeProfile.GRADUAL_SMALL_TO_FULL,
                0.5f,
            ),
        )
        assertEquals(
            0,
            layerGeometrySampleKey(
                LayerSizeProfile.GRADUAL_SMALL_TO_FULL,
                0.374f,
            ),
        )
        assertEquals(
            1,
            layerGeometrySampleKey(
                LayerSizeProfile.GRADUAL_SMALL_TO_FULL,
                0.375f,
            ),
        )
        assertEquals(
            1,
            layerGeometrySampleKey(
                LayerSizeProfile.GRADUAL_SMALL_TO_FULL,
                0.999f,
            ),
        )
        assertEquals(
            2,
            layerGeometrySampleKey(
                LayerSizeProfile.GRADUAL_SMALL_TO_FULL,
                1f,
            ),
        )
        assertEquals(
            0,
            layerGeometrySampleKey(
                LayerSizeProfile.GRADUAL_SMALL_TO_FULL,
                Float.POSITIVE_INFINITY,
            ),
        )
        val abruptKeys = (0 until 8).map { step ->
            layerGeometrySampleKey(
                LayerSizeProfile.ABRUPT_SMALL_FULL,
                (step + 0.5f) / 8f,
            )
        }
        assertEquals((0 until 8).toList(), abruptKeys)
    }

    @Test
    fun capacityTilesKeepEverySafetyCandidateVisibleAndCoverTheFullStage() {
        listOf(1, 6, 12, 16, 20).forEach { layerCount ->
            val tiles = (0 until layerCount).map { index ->
                checkNotNull(
                    capacityTileBounds(
                        width = 1_000,
                        height = 800,
                        layerCount = layerCount,
                        layerIndex = index,
                    ),
                )
            }

            assertEquals(layerCount, tiles.distinct().size)
            assertTrue(tiles.all { it.right > it.left && it.bottom > it.top })
            tiles.forEachIndexed { index, tile ->
                tiles.drop(index + 1).forEach { other ->
                    val overlaps =
                        tile.left < other.right &&
                            other.left < tile.right &&
                            tile.top < other.bottom &&
                            other.top < tile.bottom
                    assertFalse(overlaps)
                }
            }
            assertEquals(
                "layerCount=$layerCount",
                1_000L * 800L,
                tiles.sumOf { tile ->
                    (tile.right - tile.left).toLong() *
                        (tile.bottom - tile.top).toLong()
                },
            )
        }
    }

    @Test
    fun capacityTileBoundsRejectInvalidOrSubpixelCandidates() {
        assertNull(capacityTileBounds(0, 100, 1, 0))
        assertNull(capacityTileBounds(100, 100, 0, 0))
        assertNull(capacityTileBounds(100, 100, 21, 0))
        assertNull(capacityTileBounds(100, 100, 1, 1))
        assertNull(capacityTileBounds(4, 4, 20, 0))
        assertNull(capacityTileBounds(1, 1, 20, 0))
    }

    @Test
    fun capacityTileBoundsAdaptToNarrowAndShortStagesWithoutThrowing() {
        val vertical = (0 until 20).map { index ->
            capacityTileBounds(1, 20, 20, index)
        }
        val horizontal = (0 until 20).map { index ->
            capacityTileBounds(20, 1, 20, index)
        }

        assertTrue(vertical.all { it != null })
        assertTrue(horizontal.all { it != null })
        assertEquals(20, vertical.filterNotNull().distinct().size)
        assertEquals(20, horizontal.filterNotNull().distinct().size)
    }

    @Test
    fun capacityGeometryLossForcesOneIdenticalExpectedSetRepublish() {
        assertTrue(
            capacityGeometryRequiresForcedRepublish(
                geometryWasReady = true,
                geometryReady = false,
            ),
        )
        assertFalse(
            capacityGeometryRequiresForcedRepublish(
                geometryWasReady = false,
                geometryReady = false,
            ),
        )
        assertFalse(
            capacityGeometryRequiresForcedRepublish(
                geometryWasReady = true,
                geometryReady = true,
            ),
        )

        assertFalse(
            shouldPublishExpectedProducerSet(
                bindingsCommitted = true,
                expectedTopologyDirty = true,
                forceRepublish = false,
                sameAsLastPublication = true,
            ),
        )
        assertTrue(
            shouldPublishExpectedProducerSet(
                bindingsCommitted = true,
                expectedTopologyDirty = false,
                forceRepublish = true,
                sameAsLastPublication = true,
            ),
        )
        assertTrue(
            shouldPublishExpectedProducerSet(
                bindingsCommitted = true,
                expectedTopologyDirty = true,
                forceRepublish = false,
                sameAsLastPublication = false,
            ),
        )
        assertFalse(
            shouldPublishExpectedProducerSet(
                bindingsCommitted = false,
                expectedTopologyDirty = true,
                forceRepublish = true,
                sameAsLastPublication = false,
            ),
        )
        assertFalse(
            producerControlRevisionIsValid(
                requestedRevision = -1L,
                currentRevision = 4L,
                generationChanged = true,
            ),
        )
        assertFalse(
            producerControlRevisionIsValid(
                requestedRevision = 3L,
                currentRevision = 4L,
                generationChanged = false,
            ),
        )
        assertTrue(
            producerControlRevisionIsValid(
                requestedRevision = 0L,
                currentRevision = 4L,
                generationChanged = true,
            ),
        )
    }
}
