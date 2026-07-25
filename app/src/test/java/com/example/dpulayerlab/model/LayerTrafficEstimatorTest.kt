package com.example.dpulayerlab.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayerTrafficEstimatorTest {
    @Test
    fun rgb8888DisplayLayersUseFourBytesPerPixel() {
        val estimate = LayerTrafficEstimator.estimate(
            phase = phase(activeLayers = 3),
            displayWidthPx = 100,
            displayHeightPx = 50,
            measuredDisplayHz = 60f,
        )

        assertEquals(60_000.0, estimate.bytesPerFrame!!, 0.001)
        assertEquals(3_600_000.0, estimate.dpuReadBytesPerSecond!!, 0.001)
        assertEquals(3, estimate.producerLayerCount)
    }

    @Test
    fun yuvVisualProxyUsesRgbaWithoutSelectedMedia() {
        val estimate = LayerTrafficEstimator.estimate(
            phase = phase(
                activeLayers = 2,
                pixelRoute = PixelRoute.YUV_420,
                bufferSize = BufferSize.UHD_4K,
            ),
            displayWidthPx = 100,
            displayHeightPx = 50,
        )

        val expected = (3840.0 * 2160.0 + 100.0 * 50.0) * 4.0
        assertEquals(expected, estimate.bytesPerFrame!!, 0.001)
        assertTrue(estimate.formatLabel.contains("proxy"))
    }

    @Test
    fun selectedP010DecoderPrimaryUsesThreeBytesPerPixel() {
        val estimate = LayerTrafficEstimator.estimate(
            phase = phase(pixelRoute = PixelRoute.P010),
            displayWidthPx = 40,
            displayHeightPx = 20,
            mediaSelected = true,
            mediaWidthPx = 80,
            mediaHeightPx = 40,
            decoderLinearReference = p010Reference,
        )

        assertEquals(9_600.0, estimate.bytesPerFrame!!, 0.001)
    }

    @Test
    fun selectedDecoderFormatTakesPriorityOverSurfaceAlpha() {
        val yuv = LayerTrafficEstimator.estimate(
            phase = phase(
                pixelRoute = PixelRoute.YUV_420,
                alphaOverlap = true,
            ),
            displayWidthPx = 40,
            displayHeightPx = 20,
            mediaSelected = true,
            mediaWidthPx = 80,
            mediaHeightPx = 40,
            decoderLinearReference = yuv420Reference,
        )
        val p010 = LayerTrafficEstimator.estimate(
            phase = phase(
                pixelRoute = PixelRoute.P010,
                alphaOverlap = true,
            ),
            displayWidthPx = 40,
            displayHeightPx = 20,
            mediaSelected = true,
            mediaWidthPx = 80,
            mediaHeightPx = 40,
            decoderLinearReference = p010Reference,
        )

        assertEquals(4_800.0, yuv.bytesPerFrame!!, 0.001)
        assertEquals(9_600.0, p010.bytesPerFrame!!, 0.001)
        assertTrue(yuv.formatLabel.contains("decoder primary YUV420"))
        assertTrue(p010.formatLabel.contains("Surface alpha"))
    }

    @Test
    fun requiredSbwcWithSelectedMediaUsesDecoderDimensions() {
        val estimate = LayerTrafficEstimator.estimate(
            phase = phase(pixelRoute = PixelRoute.SBWC_REQUIRED),
            displayWidthPx = 40,
            displayHeightPx = 20,
            mediaSelected = true,
            mediaWidthPx = 80,
            mediaHeightPx = 40,
            decoderLinearReference = p010Reference,
        )

        assertEquals(9_600.0, estimate.bytesPerFrame!!, 0.001)
        assertTrue(estimate.formatLabel.contains("route SBWC_REQUIRED"))
        assertTrue(estimate.compressionRatioExcluded)
    }

    @Test
    fun flattenedBackendReportsOneRgbaProducer() {
        val estimate = LayerTrafficEstimator.estimate(
            phase = phase(
                activeLayers = 12,
                backend = LayerBackend.FLATTENED_TEXTURE,
                pixelRoute = PixelRoute.YUV_420,
            ),
            displayWidthPx = 100,
            displayHeightPx = 50,
        )

        assertEquals(12, estimate.logicalLayerCount)
        assertEquals(1, estimate.producerLayerCount)
        assertEquals(20_000.0, estimate.bytesPerFrame!!, 0.001)
        assertTrue(estimate.formatLabel.contains("flattened"))
    }

    @Test
    fun destinationSizeDoesNotReduceConservativeFullBufferTraffic() {
        val full = LayerTrafficEstimator.estimate(
            phase = phase(
                activeLayers = 4,
                layerSizeProfile = LayerSizeProfile.FULL_SCREEN,
            ),
            displayWidthPx = 100,
            displayHeightPx = 50,
        )
        val small = LayerTrafficEstimator.estimate(
            phase = phase(
                activeLayers = 4,
                layerSizeProfile = LayerSizeProfile.SMALL_UNIFORM,
            ),
            displayWidthPx = 100,
            displayHeightPx = 50,
        )

        assertEquals(full.bytesPerFrame, small.bytesPerFrame)
        assertEquals(full.dpuReadBytesPerSecond, small.dpuReadBytesPerSecond)
        assertEquals(full.producerWriteBytesPerSecond, small.producerWriteBytesPerSecond)
        assertEquals(4.0, full.destinationFootprintScreenEquivalents, 0.0001)
        assertEquals(100.0, full.destinationFootprintAveragePercent, 0.0001)
        assertEquals(0.36, small.destinationFootprintScreenEquivalents, 0.0001)
        assertEquals(9.0, small.destinationFootprintAveragePercent, 0.0001)
        assertTrue(small.destinationFootprintLabel.contains("base profile only"))
    }

    @Test
    fun mixedDestinationFootprintUsesEveryBoundedSizeVariant() {
        val estimate = LayerTrafficEstimator.estimate(
            phase = phase(
                activeLayers = 5,
                layerSizeProfile = LayerSizeProfile.MIXED_SIZES,
            ),
            displayWidthPx = 100,
            displayHeightPx = 50,
        )
        val expected =
            1.0 +
                0.72 * 0.56 +
                0.56 * 0.72 +
                0.46 * 0.46 +
                0.30 * 0.38

        assertEquals(expected, estimate.destinationFootprintScreenEquivalents, 0.0001)
        assertEquals(expected * 20.0, estimate.destinationFootprintAveragePercent, 0.0001)
    }

    @Test
    fun gradualAndAbruptFootprintsFollowBoundedPhaseProgress() {
        val gradualStart = LayerTrafficEstimator.estimate(
            phase = phase(
                activeLayers = 2,
                layerSizeProfile = LayerSizeProfile.GRADUAL_SMALL_TO_FULL,
            ),
            displayWidthPx = 100,
            displayHeightPx = 50,
            phaseFraction = 0f,
        )
        val gradualEnd = LayerTrafficEstimator.estimate(
            phase = phase(
                activeLayers = 2,
                layerSizeProfile = LayerSizeProfile.GRADUAL_SMALL_TO_FULL,
            ),
            displayWidthPx = 100,
            displayHeightPx = 50,
            phaseFraction = 1f,
        )
        val abruptSmall = LayerTrafficEstimator.estimate(
            phase = phase(
                activeLayers = 2,
                layerSizeProfile = LayerSizeProfile.ABRUPT_SMALL_FULL,
            ),
            displayWidthPx = 100,
            displayHeightPx = 50,
            phaseFraction = 0f,
        )
        val abruptFull = LayerTrafficEstimator.estimate(
            phase = phase(
                activeLayers = 2,
                layerSizeProfile = LayerSizeProfile.ABRUPT_SMALL_FULL,
            ),
            displayWidthPx = 100,
            displayHeightPx = 50,
            phaseFraction = 0.2f,
        )
        val invalidFraction = LayerTrafficEstimator.estimate(
            phase = phase(
                activeLayers = 2,
                layerSizeProfile = LayerSizeProfile.GRADUAL_SMALL_TO_FULL,
            ),
            displayWidthPx = 100,
            displayHeightPx = 50,
            phaseFraction = Float.NaN,
        )

        assertEquals(0.18, gradualStart.destinationFootprintScreenEquivalents, 0.0001)
        assertEquals(2.0, gradualEnd.destinationFootprintScreenEquivalents, 0.0001)
        assertEquals(0.18, abruptSmall.destinationFootprintScreenEquivalents, 0.0001)
        assertEquals(2.0, abruptFull.destinationFootprintScreenEquivalents, 0.0001)
        assertEquals(2.0, invalidFraction.destinationFootprintScreenEquivalents, 0.0001)
    }

    @Test
    fun capacityTilesAlwaysCoverOneScreenEquivalentIncludingPartialRows() {
        listOf(1, 6, 12, 16, 20).forEach { layerCount ->
            val estimate = LayerTrafficEstimator.estimate(
                phase = phase(
                    activeLayers = layerCount,
                    layerSizeProfile = LayerSizeProfile.SMALL_UNIFORM,
                    motion = MotionProfile.CAPACITY_TILES,
                ),
                displayWidthPx = 100,
                displayHeightPx = 50,
            )

            assertEquals(1.0, estimate.destinationFootprintScreenEquivalents, 0.0001)
            assertEquals(
                100.0 / layerCount,
                estimate.destinationFootprintAveragePercent,
                0.0001,
            )
            assertTrue(estimate.destinationFootprintLabel.contains("explicit crop union"))
            assertTrue(estimate.destinationFootprintLabel.contains("profile bypassed"))
        }
    }

    @Test
    fun singleGlLayerUsesDisplaySizeInsteadOfRequestedPrimarySize() {
        val estimate = LayerTrafficEstimator.estimate(
            phase = phase(
                bufferSize = BufferSize.UHD_8K,
                includeGlLayer = true,
            ),
            displayWidthPx = 100,
            displayHeightPx = 50,
        )

        assertEquals(20_000.0, estimate.bytesPerFrame!!, 0.001)
    }

    @Test
    fun sbwcIsClearlyMarkedAsLinearReference() {
        val estimate = LayerTrafficEstimator.estimate(
            phase = phase(pixelRoute = PixelRoute.SBWC_AUTO),
            displayWidthPx = 100,
            displayHeightPx = 50,
        )

        assertTrue(estimate.compressionRatioExcluded)
        assertFalse(estimate.formatLabel.isBlank())
    }

    @Test
    fun unknownDisplaySizeDoesNotInventTraffic() {
        val estimate = LayerTrafficEstimator.estimate(
            phase = phase(),
            displayWidthPx = 0,
            displayHeightPx = 0,
        )

        assertNull(estimate.bytesPerFrame)
        assertNull(estimate.dpuReadBytesPerSecond)
    }

    @Test
    fun decoderRouteDoesNotChangeTheVerifiedLinearReference() {
        val estimates = listOf(
            PixelRoute.YUV_420,
            PixelRoute.P010,
            PixelRoute.SBWC_AUTO,
            PixelRoute.SBWC_REQUIRED,
        ).map { route ->
            LayerTrafficEstimator.estimate(
                phase = phase(pixelRoute = route),
                displayWidthPx = 40,
                displayHeightPx = 20,
                mediaSelected = true,
                mediaWidthPx = 80,
                mediaHeightPx = 40,
                decoderLinearReference = yuv420Reference,
            )
        }

        assertTrue(estimates.all { it.bytesPerFrame == 4_800.0 })
        assertTrue(estimates.all { it.formatLabel.contains("does not force Surface format") })
        assertTrue(estimates.takeLast(2).all { it.compressionRatioExcluded })
    }

    @Test
    fun unknownDecoderLinearReferenceMakesAggregateTrafficUnavailable() {
        val estimate = LayerTrafficEstimator.estimate(
            phase = phase(pixelRoute = PixelRoute.P010, activeLayers = 2),
            displayWidthPx = 40,
            displayHeightPx = 20,
            mediaSelected = true,
            mediaWidthPx = 80,
            mediaHeightPx = 40,
            decoderLinearReference = DecoderLinearReference(
                bytesPerPixel = null,
                label = "decoder linear reference N/A",
                source = "test",
            ),
        )

        assertNull(estimate.bytesPerFrame)
        assertNull(estimate.dpuReadBytesPerSecond)
        assertNull(estimate.producerWriteBytesPerSecond)
        assertTrue(estimate.formatLabel.contains("N/A"))
    }

    @Test
    fun invalidDecoderLinearReferencesFailClosedInsteadOfLeakingNonFiniteTraffic() {
        listOf(
            Double.NaN,
            Double.NEGATIVE_INFINITY,
            Double.POSITIVE_INFINITY,
            -1.0,
            0.0,
            16.0001,
        ).forEach { invalidBytesPerPixel ->
            val estimate = LayerTrafficEstimator.estimate(
                phase = phase(pixelRoute = PixelRoute.P010),
                displayWidthPx = 40,
                displayHeightPx = 20,
                mediaSelected = true,
                mediaWidthPx = 80,
                mediaHeightPx = 40,
                decoderLinearReference = DecoderLinearReference(
                    bytesPerPixel = invalidBytesPerPixel,
                    label = "invalid $invalidBytesPerPixel B/px",
                    source = "untrusted test descriptor",
                ),
            )

            assertNull("B/px=$invalidBytesPerPixel", estimate.bytesPerFrame)
            assertNull("B/px=$invalidBytesPerPixel", estimate.dpuReadBytesPerSecond)
            assertNull("B/px=$invalidBytesPerPixel", estimate.producerWriteBytesPerSecond)
            assertTrue(estimate.formatLabel.contains("N/A"))
        }
    }

    @Test
    fun decoderDimensionsAreAnAtomicPairAndNeverFallBackOneAxisToDisplaySize() {
        listOf<Pair<Int?, Int?>>(
            null to 40,
            80 to null,
        ).forEach { (width, height) ->
            val estimate = decoderEstimate(width, height)

            assertTrafficUnavailable("dimensions=$width x $height", estimate)
            assertEquals("decoder size N/A", estimate.resolutionLabel)
        }
    }

    @Test
    fun nonPositiveDecoderDimensionsMakeTheWholeAggregateUnavailable() {
        listOf(
            0 to 40,
            -1 to 40,
            80 to 0,
            80 to -1,
        ).forEach { (width, height) ->
            val estimate = decoderEstimate(width, height)

            assertTrafficUnavailable("dimensions=$width x $height", estimate)
            assertEquals("decoder size N/A", estimate.resolutionLabel)
        }
    }

    @Test
    fun decoderDimensionAreaIsBoundedBeforeLinearFrameByteCalculation() {
        // At the 16 B/px descriptor ceiling this pair remains within Long.MAX_VALUE.
        val boundary = decoderEstimate(
            width = Int.MAX_VALUE,
            height = 268_435_456,
        )
        // One additional row would make the maximum accepted linear frame exceed Long.MAX_VALUE.
        val overBoundary = decoderEstimate(
            width = Int.MAX_VALUE,
            height = 268_435_457,
        )

        assertNotNull(boundary.bytesPerFrame)
        assertNotNull(boundary.dpuReadBytesPerSecond)
        assertNotNull(boundary.producerWriteBytesPerSecond)
        assertTrafficUnavailable("decoder area over addressable boundary", overBoundary)
        assertEquals("decoder size N/A", overBoundary.resolutionLabel)
    }

    private val yuv420Reference = DecoderLinearReference(
        bytesPerPixel = 1.5,
        label = "YUV420 linear reference · 1.5 B/px",
        source = "test",
    )

    private val p010Reference = DecoderLinearReference(
        bytesPerPixel = 3.0,
        label = "P010 linear reference · 3 B/px",
        source = "test",
    )

    private fun decoderEstimate(
        width: Int?,
        height: Int?,
    ): LayerTrafficEstimate = LayerTrafficEstimator.estimate(
        phase = phase(pixelRoute = PixelRoute.P010, activeLayers = 2),
        displayWidthPx = 40,
        displayHeightPx = 20,
        mediaSelected = true,
        mediaWidthPx = width,
        mediaHeightPx = height,
        decoderLinearReference = p010Reference,
    )

    private fun assertTrafficUnavailable(
        message: String,
        estimate: LayerTrafficEstimate,
    ) {
        assertNull(message, estimate.bytesPerFrame)
        assertNull(message, estimate.dpuReadBytesPerSecond)
        assertNull(message, estimate.producerWriteBytesPerSecond)
    }

    private fun phase(
        activeLayers: Int = 1,
        backend: LayerBackend = LayerBackend.INDEPENDENT_SURFACES,
        pixelRoute: PixelRoute = PixelRoute.RGB_8888,
        bufferSize: BufferSize = BufferSize.DISPLAY,
        includeGlLayer: Boolean = false,
        alphaOverlap: Boolean = false,
        layerSizeProfile: LayerSizeProfile = LayerSizeProfile.FULL_SCREEN,
        motion: MotionProfile = MotionProfile.STATIC,
    ) = PhaseSpec(
        id = "test",
        label = "test",
        durationMs = 1_000,
        activeLayers = activeLayers,
        producerFps = 60f,
        requestedDisplayHz = 60f,
        backend = backend,
        pixelRoute = pixelRoute,
        bufferSize = bufferSize,
        motion = motion,
        includeGlLayer = includeGlLayer,
        alphaOverlap = alphaOverlap,
        layerSizeProfile = layerSizeProfile,
    )
}
