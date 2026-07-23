package com.example.dpulayerlab.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityScannerTest {
    @Test
    fun sanitizerKeepsOneExactSupportedFormatCombination() {
        val request = sanitizeVideoDecoderRequest(
            mime = "VIDEO/VP9",
            width = 7_680,
            height = 4_320,
            framesPerSecond = 59.94f,
            requiredProfile = 2,
            requiredLevel = 512,
            bitRate = 120_000_000,
        )

        requireNotNull(request)
        assertEquals("video/vp9", request.mime)
        assertEquals(7_680, request.width)
        assertEquals(4_320, request.height)
        assertEquals(59.94f, request.framesPerSecond)
        assertEquals(2, request.requiredProfile)
        assertEquals(512, request.requiredLevel)
        assertEquals(120_000_000, request.bitRate)
    }

    @Test
    fun sanitizerAllowsGenericFormatWithoutOptionalMetadata() {
        val request = sanitizeVideoDecoderRequest(
            mime = "video/avc",
            width = 3_840,
            height = 2_160,
            framesPerSecond = 60f,
            requiredProfile = null,
            requiredLevel = null,
            bitRate = null,
        )

        requireNotNull(request)
        assertNull(request.requiredProfile)
        assertNull(request.requiredLevel)
        assertNull(request.bitRate)
    }

    @Test
    fun sanitizerRejectsMalformedOrOversizedMime() {
        val invalidMimes = listOf(
            "audio/avc",
            "video/",
            " video/avc",
            "video/avc ",
            "video/avc\n",
            "video/${"a".repeat(122)}",
        )

        invalidMimes.forEach { mime ->
            assertNull(validRequest(mime = mime))
        }
    }

    @Test
    fun sanitizerRejectsUnboundedGeometryAndRate() {
        assertNull(validRequest(width = 0))
        assertNull(validRequest(width = 16_385))
        assertNull(validRequest(width = 16_384, height = 16_385))
        assertNull(validRequest(framesPerSecond = 0f))
        assertNull(validRequest(framesPerSecond = Float.NaN))
        assertNull(validRequest(framesPerSecond = Float.POSITIVE_INFINITY))
        assertNull(validRequest(framesPerSecond = 1_001f))
    }

    @Test
    fun levelIsAcceptedOnlyAsPartOfAProfileCombination() {
        assertNull(validRequest(requiredProfile = null, requiredLevel = 1))
        assertNull(validRequest(requiredProfile = 2, requiredLevel = 0))
        assertNull(validRequest(requiredProfile = 0, requiredLevel = null))
        assertTrue(validRequest(requiredProfile = 2, requiredLevel = 1) != null)
        assertTrue(validRequest(requiredProfile = 2, requiredLevel = Int.MAX_VALUE) != null)
    }

    @Test
    fun bitrateMustBePositiveWhenPresent() {
        assertNull(validRequest(bitRate = 0))
        assertNull(validRequest(bitRate = -1))
        assertTrue(validRequest(bitRate = Int.MAX_VALUE) != null)
    }

    @Test
    fun candidateMustBeConcreteClearHardwareDecoder() {
        assertTrue(
            decoderCandidateEligible(
                isEncoder = false,
                isHardwareAccelerated = true,
                isAlias = false,
                requiresSecurePlayback = false,
            ),
        )
        assertFalse(
            decoderCandidateEligible(
                isEncoder = true,
                isHardwareAccelerated = true,
                isAlias = false,
                requiresSecurePlayback = false,
            ),
        )
        assertFalse(
            decoderCandidateEligible(
                isEncoder = false,
                isHardwareAccelerated = false,
                isAlias = false,
                requiresSecurePlayback = false,
            ),
        )
        assertFalse(
            decoderCandidateEligible(
                isEncoder = false,
                isHardwareAccelerated = true,
                isAlias = true,
                requiresSecurePlayback = false,
            ),
        )
        assertFalse(
            decoderCandidateEligible(
                isEncoder = false,
                isHardwareAccelerated = true,
                isAlias = false,
                requiresSecurePlayback = true,
            ),
        )
    }

    private fun validRequest(
        mime: String = "video/avc",
        width: Int = 1_920,
        height: Int = 1_080,
        framesPerSecond: Float = 60f,
        requiredProfile: Int? = null,
        requiredLevel: Int? = null,
        bitRate: Int? = null,
    ): SanitizedVideoDecoderRequest? = sanitizeVideoDecoderRequest(
        mime = mime,
        width = width,
        height = height,
        framesPerSecond = framesPerSecond,
        requiredProfile = requiredProfile,
        requiredLevel = requiredLevel,
        bitRate = bitRate,
    )
}
