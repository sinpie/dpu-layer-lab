package com.example.dpulayerlab.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayerStageViewMathTest {
    @Test
    fun capacityTilesKeepEveryTwentyLayerCandidateVisibleWithoutOverlap() {
        val tiles = (0 until 20).map { index ->
            checkNotNull(
                capacityTileBounds(
                    width = 1_000,
                    height = 800,
                    layerCount = 20,
                    layerIndex = index,
                ),
            )
        }

        assertEquals(20, tiles.distinct().size)
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
            1_000L * 800L,
            tiles.sumOf { tile ->
                (tile.right - tile.left).toLong() *
                    (tile.bottom - tile.top).toLong()
            },
        )
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
                expectedTopologyDirty = true,
                forceRepublish = false,
                sameAsLastPublication = true,
            ),
        )
        assertTrue(
            shouldPublishExpectedProducerSet(
                expectedTopologyDirty = false,
                forceRepublish = true,
                sameAsLastPublication = true,
            ),
        )
        assertTrue(
            shouldPublishExpectedProducerSet(
                expectedTopologyDirty = true,
                forceRepublish = false,
                sameAsLastPublication = false,
            ),
        )
    }
}
