package com.example.dpulayerlab.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        )

        assertEquals(9_600.0, estimate.bytesPerFrame!!, 0.001)
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

    private fun phase(
        activeLayers: Int = 1,
        backend: LayerBackend = LayerBackend.INDEPENDENT_SURFACES,
        pixelRoute: PixelRoute = PixelRoute.RGB_8888,
        bufferSize: BufferSize = BufferSize.DISPLAY,
        includeGlLayer: Boolean = false,
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
        motion = MotionProfile.STATIC,
        includeGlLayer = includeGlLayer,
    )
}
