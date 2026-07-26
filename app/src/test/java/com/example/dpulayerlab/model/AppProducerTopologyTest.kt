package com.example.dpulayerlab.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AppProducerTopologyTest {
    @Test
    fun plannerMatchesRendererBranchPrecedence() {
        assertEquals(
            listOf(
                AppProducerKind.VIDEO_DECODER,
                AppProducerKind.CANVAS_SURFACE,
                AppProducerKind.CANVAS_TEXTURE,
                AppProducerKind.CANVAS_SURFACE,
                AppProducerKind.CANVAS_SURFACE,
                AppProducerKind.CANVAS_TEXTURE,
                AppProducerKind.CANVAS_SURFACE,
                AppProducerKind.GPU_GL,
            ),
            plannedAppProducerKinds(
                phase(
                    activeLayers = 8,
                    backend = LayerBackend.MIXED_SURFACE_TEXTURE,
                    pixelRoute = PixelRoute.P010,
                    includeGlLayer = true,
                ),
            ),
        )
        assertEquals(
            listOf(AppProducerKind.GPU_GL),
            plannedAppProducerKinds(
                phase(
                    activeLayers = 1,
                    pixelRoute = PixelRoute.YUV_420,
                    includeGlLayer = true,
                ),
            ),
        )
        assertEquals(
            listOf(AppProducerKind.FLATTENED_CANVAS),
            plannedAppProducerKinds(
                phase(
                    activeLayers = 20,
                    backend = LayerBackend.FLATTENED_TEXTURE,
                    pixelRoute = PixelRoute.YUV_420,
                    includeGlLayer = true,
                ),
            ),
        )
        assertEquals(
            20,
            plannedAppProducerKinds(phase(activeLayers = 20)).size,
        )
    }

    @Test
    fun topologyRequiresOrderedUniqueBoundedDescriptors() {
        val valid = AppProducerTopology(
            generation = 4L,
            producers = listOf(
                AppProducerDescriptor(
                    producerId = 10L,
                    layerIndex = 0,
                    kind = AppProducerKind.VIDEO_DECODER,
                    primary = true,
                ),
                AppProducerDescriptor(
                    producerId = 11L,
                    layerIndex = 1,
                    kind = AppProducerKind.CANVAS_SURFACE,
                    primary = false,
                ),
            ),
        )

        assertEquals(2, valid.producers.size)
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(
                producers = valid.producers.reversed(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(
                producers = valid.producers.map { it.copy(producerId = 10L) },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(
                producers = valid.producers.map { it.copy(primary = true) },
            )
        }
    }

    @Test
    fun topologySnapshotsAndProtectsCallerOwnedMutableList() {
        val callerOwned = mutableListOf(
            AppProducerDescriptor(
                producerId = 1L,
                layerIndex = 0,
                kind = AppProducerKind.VIDEO_DECODER,
                primary = true,
            ),
        )
        val topology = AppProducerTopology(
            generation = 1L,
            producers = callerOwned,
        )

        callerOwned.clear()

        assertEquals(1, topology.producers.size)
        assertEquals(AppProducerKind.VIDEO_DECODER, topology.producers.single().kind)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (topology.producers as MutableList<AppProducerDescriptor>).clear()
        }
    }

    private fun phase(
        activeLayers: Int,
        backend: LayerBackend = LayerBackend.INDEPENDENT_SURFACES,
        pixelRoute: PixelRoute = PixelRoute.RGB_8888,
        includeGlLayer: Boolean = false,
    ) = PhaseSpec(
        id = "phase",
        label = "phase",
        durationMs = 1_000L,
        activeLayers = activeLayers,
        producerFps = 60f,
        requestedDisplayHz = 60f,
        backend = backend,
        pixelRoute = pixelRoute,
        bufferSize = BufferSize.UHD_4K,
        motion = MotionProfile.STATIC,
        includeGlLayer = includeGlLayer,
    )
}
