package com.example.dpulayerlab.render

import org.junit.Assert.assertEquals
import org.junit.Test

class CodecLoopBackoffTest {
    @Test
    fun noProgressOnBothPortsUsesBoundedDefaultPark() {
        assertEquals(
            500_000L,
            codecNoProgressParkNanos(
                inputProgress = false,
                outputProgress = false,
            ),
        )
    }

    @Test
    fun progressOnEitherPortDoesNotDelayTheCodecLoop() {
        assertEquals(
            0L,
            codecNoProgressParkNanos(
                inputProgress = true,
                outputProgress = false,
            ),
        )
        assertEquals(
            0L,
            codecNoProgressParkNanos(
                inputProgress = false,
                outputProgress = true,
            ),
        )
    }

    @Test
    fun hostileRequestedParkIsClampedToQuarterToOneMillisecond() {
        assertEquals(
            250_000L,
            codecNoProgressParkNanos(
                inputProgress = false,
                outputProgress = false,
                requestedParkNanos = Long.MIN_VALUE,
            ),
        )
        assertEquals(
            1_000_000L,
            codecNoProgressParkNanos(
                inputProgress = false,
                outputProgress = false,
                requestedParkNanos = Long.MAX_VALUE,
            ),
        )
    }
}
