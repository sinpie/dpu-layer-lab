package com.example.dpulayerlab

import com.example.dpulayerlab.engine.AutomationCommand
import com.example.dpulayerlab.engine.AutomationIntentParseResult
import com.example.dpulayerlab.engine.enqueuePendingAutomation
import java.util.ArrayDeque
import org.junit.Assert.assertEquals
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

    @Test
    fun isolationTokenNeverPublishesZeroAndWrapsAtLongMax() {
        assertTrue(nextIsolationToken(0L) == 1L)
        assertTrue(nextIsolationToken(41L) == 42L)
        assertTrue(nextIsolationToken(Long.MAX_VALUE) == 1L)
    }

    @Test
    fun coldStartAutomationWaitsForAttachAndFirstRootInsets() {
        val start = AutomationIntentParseResult.Accepted(
            AutomationCommand.Start(
                scenarioIds = listOf("baseline-single-layer"),
                repeatCount = 1,
            ),
        )

        assertTrue(
            shouldDeferAutomationUntilWindowReady(
                parsed = start,
                decorAttached = false,
                rootInsetsAvailable = false,
            ),
        )
        assertTrue(
            shouldDeferAutomationUntilWindowReady(
                parsed = start,
                decorAttached = true,
                rootInsetsAvailable = false,
            ),
        )
        assertFalse(
            shouldDeferAutomationUntilWindowReady(
                parsed = start,
                decorAttached = true,
                rootInsetsAvailable = true,
            ),
        )
    }

    @Test
    fun stopNeverWaitsForWindowInsetsReadiness() {
        val stop = AutomationIntentParseResult.Accepted(AutomationCommand.Stop)
        val rejected = AutomationIntentParseResult.Rejected("malformed")

        assertFalse(
            shouldDeferAutomationUntilWindowReady(
                parsed = stop,
                decorAttached = false,
                rootInsetsAvailable = false,
            ),
        )
        assertFalse(
            shouldDeferAutomationUntilWindowReady(
                parsed = rejected,
                decorAttached = false,
                rootInsetsAvailable = false,
            ),
        )
    }

    @Test
    fun stopSupersedesADeferredColdStartAndRemainsImmediatelyDrainable() {
        val start = AutomationIntentParseResult.Accepted(
            AutomationCommand.Start(listOf("baseline-single-layer"), repeatCount = 1),
        )
        val stop = AutomationIntentParseResult.Accepted(AutomationCommand.Stop)
        val queue = ArrayDeque<AutomationIntentParseResult>()

        enqueuePendingAutomation(queue, start, maxPendingCommands = 8)
        assertTrue(
            shouldDeferAutomationUntilWindowReady(
                parsed = queue.first(),
                decorAttached = false,
                rootInsetsAvailable = false,
            ),
        )

        enqueuePendingAutomation(queue, stop, maxPendingCommands = 8)
        assertEquals(1, queue.size)
        assertEquals(stop, queue.first())
        assertFalse(
            shouldDeferAutomationUntilWindowReady(
                parsed = queue.first(),
                decorAttached = false,
                rootInsetsAvailable = false,
            ),
        )
    }

    @Test
    fun runDisplaySnapshotIsImmutableAndNormalizedAxisSwapIsAllowed() {
        val startSnapshot = DisplayEnvelopeIdentity(0, 1440, 3120)
        val currentAtObservation = DisplayEnvelopeIdentity(2, 904, 2316)

        assertEquals(
            startSnapshot,
            displayEnvelopeSnapshotAtRunStart(
                lastObserved = startSnapshot,
                current = currentAtObservation,
            ),
        )
        assertEquals(
            currentAtObservation,
            displayEnvelopeSnapshotAtRunStart(
                lastObserved = null,
                current = currentAtObservation,
            ),
        )
        assertEquals(
            startSnapshot,
            selectRunDisplayEnvelopeSnapshot(
                existingRunSnapshot = startSnapshot,
                lastObserved = currentAtObservation,
                current = currentAtObservation,
            ),
        )
        assertFalse(
            runningDisplaySafetyEnvelopeChanged(
                runSnapshot = startSnapshot,
                current = DisplayEnvelopeIdentity(0, 1440, 3120),
            ),
        )
        assertTrue(
            runningDisplaySafetyEnvelopeChanged(
                runSnapshot = startSnapshot,
                current = DisplayEnvelopeIdentity(2, 1440, 3120),
            ),
        )
        assertTrue(
            runningDisplaySafetyEnvelopeChanged(
                runSnapshot = startSnapshot,
                current = DisplayEnvelopeIdentity(0, 1812, 2176),
            ),
        )
    }
}
