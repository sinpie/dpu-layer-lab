package com.example.dpulayerlab.render

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoDecoderSelectionTest {

    @Test
    fun exactIntegerRejectsWrappingFractionalAndNonFiniteValues() {
        assertEquals(3_840, exactMediaIntegerOrNull(3_840))
        assertEquals(Int.MAX_VALUE, exactMediaIntegerOrNull(Int.MAX_VALUE.toLong()))
        assertNull(exactMediaIntegerOrNull(Int.MAX_VALUE.toLong() + 1L))
        assertNull(exactMediaIntegerOrNull(4_294_971_136L))
        assertNull(exactMediaIntegerOrNull(59.94))
        assertNull(exactMediaIntegerOrNull(Double.NaN))
        assertNull(exactMediaIntegerOrNull(Double.POSITIVE_INFINITY))
    }

    @Test
    fun allocationCeilingIsAlignedWithoutOverflow() {
        assertEquals(1_088, videoDimensionCeiling(1_080))
        assertEquals(3_840, videoDimensionCeiling(3_840))
        assertNull(videoDimensionCeiling(Int.MAX_VALUE))
        assertNull(videoDimensionCeiling(0))
        assertNull(videoDimensionCeiling(100, alignmentPx = 0))
    }

    @Test
    fun fixedVideoPolicyRejectsPartialSmallerAndLargerAdaptiveBounds() {
        assertTrue(fixedVideoMaximumDimensionsMatch(3_840, 2_160, null, null))
        assertTrue(fixedVideoMaximumDimensionsMatch(3_840, 2_160, 3_840, 2_160))
        assertFalse(fixedVideoMaximumDimensionsMatch(3_840, 2_160, 3_840, null))
        assertFalse(fixedVideoMaximumDimensionsMatch(3_840, 2_160, 1_920, 1_080))
        assertFalse(fixedVideoMaximumDimensionsMatch(3_840, 2_160, 7_680, 4_320))
        assertFalse(fixedVideoMaximumDimensionsMatch(0, 2_160, null, null))
    }

    @Test
    fun absentCropUsesEncodedDimensions() {
        assertEquals(
            VideoDimensions(1_920, 1_088),
            visibleVideoDimensions(1_920, 1_088),
        )
    }

    @Test
    fun inclusiveCropProducesVisibleDimensions() {
        assertEquals(
            VideoDimensions(1_920, 1_080),
            visibleVideoDimensions(
                encodedWidthPx = 1_920,
                encodedHeightPx = 1_088,
                cropLeft = 0,
                cropRight = 1_919,
                cropTop = 0,
                cropBottom = 1_079,
            ),
        )
    }

    @Test
    fun cropPairsAreIndependentButLoneKeysFailClosed() {
        assertEquals(
            VideoDimensions(1_920, 1_080),
            visibleVideoDimensions(
                encodedWidthPx = 1_920,
                encodedHeightPx = 1_080,
                cropLeft = 0,
                cropRight = 1_919,
            ),
        )
        assertEquals(
            VideoDimensions(1_920, 1_072),
            visibleVideoDimensions(
                encodedWidthPx = 1_920,
                encodedHeightPx = 1_088,
                cropTop = 8,
                cropBottom = 1_079,
            ),
        )
        assertNull(
            visibleVideoDimensions(
                encodedWidthPx = 1_920,
                encodedHeightPx = 1_080,
                cropRight = 1_919,
            ),
        )
    }

    @Test
    fun outOfRangeOrReversedCropFailsClosed() {
        assertNull(
            visibleVideoDimensions(
                encodedWidthPx = 1_920,
                encodedHeightPx = 1_080,
                cropLeft = 0,
                cropRight = 1_920,
                cropTop = 0,
                cropBottom = 1_079,
            ),
        )
        assertNull(
            visibleVideoDimensions(
                encodedWidthPx = 1_920,
                encodedHeightPx = 1_080,
                cropLeft = 4,
                cropRight = 3,
                cropTop = 0,
                cropBottom = 1_079,
            ),
        )
    }

    @Test
    fun exactDimensionComparisonAllowsRotationButNotResize() {
        assertTrue(sameVideoDimensions(3_840, 2_160, 3_840, 2_160))
        assertTrue(sameVideoDimensions(2_160, 3_840, 3_840, 2_160))
        assertFalse(sameVideoDimensions(3_840, 2_159, 3_840, 2_160))
        assertFalse(sameVideoDimensions(0, 2_160, 3_840, 2_160))
    }

    @Test
    fun outputCeilingAllowsAlignmentAndRotationButRejectsGrowth() {
        assertTrue(videoDimensionsFitWithin(1_920, 1_088, 1_920, 1_088))
        assertTrue(videoDimensionsFitWithin(1_088, 1_920, 1_920, 1_088))
        assertTrue(videoDimensionsFitWithin(1_280, 720, 1_920, 1_088))
        assertFalse(videoDimensionsFitWithin(1_921, 1_080, 1_920, 1_088))
        assertFalse(videoDimensionsFitWithin(1_920, 1_089, 1_920, 1_088))
    }

    @Test
    fun frameRateFingerprintUsesBoundedTolerance() {
        assertTrue(videoFrameRatesMatch(59.94f, 60f))
        assertTrue(videoFrameRatesMatch(119.5f, 120f))
        assertFalse(videoFrameRatesMatch(119.49f, 120f))
        assertFalse(videoFrameRatesMatch(null, 60f))
        assertFalse(videoFrameRatesMatch(Float.NaN, 60f))
        assertFalse(videoFrameRatesMatch(60f, 60f, tolerance = Float.NaN))
    }

    @Test
    fun presentationDimensionsApplyOnlyCanonicalQuarterTurns() {
        val landscape = VideoDimensions(3_840, 2_160)

        assertEquals(landscape, presentationVideoDimensions(landscape, 0))
        assertEquals(landscape, presentationVideoDimensions(landscape, 180))
        assertEquals(
            VideoDimensions(2_160, 3_840),
            presentationVideoDimensions(landscape, 90),
        )
        assertNull(presentationVideoDimensions(landscape, 45))
    }

    @Test
    fun codecConfigFingerprintIsDeterministicAndPreservesBufferPositions() {
        val first = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4)).apply { position(1) }
        val second = ByteBuffer.wrap(byteArrayOf(8, 9))

        val forward = boundedCodecConfigFingerprint(
            listOf("csd-0" to first, "csd-1" to second),
        )
        val reversed = boundedCodecConfigFingerprint(
            listOf("csd-1" to second, "csd-0" to first),
        )

        assertEquals(forward, reversed)
        assertEquals(64, forward?.length)
        assertEquals(1, first.position())
        assertEquals(0, second.position())
    }

    @Test
    fun codecConfigFingerprintRejectsDuplicateInvalidAndOversizedEntries() {
        assertNull(
            boundedCodecConfigFingerprint(
                listOf(
                    "csd-0" to ByteBuffer.wrap(byteArrayOf(1)),
                    "csd-0" to ByteBuffer.wrap(byteArrayOf(2)),
                ),
            ),
        )
        assertNull(
            boundedCodecConfigFingerprint(
                listOf("not-csd" to ByteBuffer.wrap(byteArrayOf(1))),
            ),
        )
        assertNull(
            boundedCodecConfigFingerprint(
                listOf("csd-0" to ByteBuffer.allocate(1024 * 1024 + 1)),
            ),
        )
        assertEquals(64, boundedCodecConfigFingerprint(emptyList())?.length)
    }
}
