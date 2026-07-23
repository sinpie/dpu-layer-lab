package com.example.dpulayerlab

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityMathTest {
    @Test
    fun orientationAxisSwapKeepsTheSameDisplaySafetyEnvelope() {
        val portrait = DisplayEnvelopeIdentity(
            displayId = 0,
            shortEdgePx = 1440,
            longEdgePx = 3120,
        )
        val sameNormalizedLandscape = DisplayEnvelopeIdentity(
            displayId = 0,
            shortEdgePx = 1440,
            longEdgePx = 3120,
        )

        assertFalse(displaySafetyEnvelopeChanged(portrait, sameNormalizedLandscape))
    }

    @Test
    fun physicalSizeIdentityOrAvailabilityChangeInvalidatesTheEnvelope() {
        val cover = DisplayEnvelopeIdentity(0, 904, 2316)

        assertTrue(
            displaySafetyEnvelopeChanged(
                cover,
                DisplayEnvelopeIdentity(0, 1812, 2176),
            ),
        )
        assertTrue(
            displaySafetyEnvelopeChanged(
                cover,
                DisplayEnvelopeIdentity(2, 904, 2316),
            ),
        )
        assertTrue(displaySafetyEnvelopeChanged(cover, null))
        assertTrue(displaySafetyEnvelopeChanged(null, cover))
        assertFalse(displaySafetyEnvelopeChanged(null, null))
    }
}
