package com.example.dpulayerlab.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch

class RendererRecoveryPolicyTest {
    @Test
    fun invalidSurfaceCancelsWithoutStartingOrFailing() {
        assertEquals(
            ProducerRecoveryDecision.CANCEL,
            producerRecoveryDecision(
                targetValid = false,
                processLeaseActive = true,
                elapsedMs = Long.MAX_VALUE,
                timeoutMs = 5_000L,
            ),
        )
    }

    @Test
    fun transientLeaseWaitsAndClearWinsAtDeadline() {
        assertEquals(
            ProducerRecoveryDecision.WAIT,
            producerRecoveryDecision(
                targetValid = true,
                processLeaseActive = true,
                elapsedMs = 4_999L,
                timeoutMs = 5_000L,
            ),
        )
        assertEquals(
            ProducerRecoveryDecision.START,
            producerRecoveryDecision(
                targetValid = true,
                processLeaseActive = false,
                elapsedMs = 5_000L,
                timeoutMs = 5_000L,
            ),
        )
    }

    @Test
    fun liveLeaseAtDeadlineFailsClosed() {
        assertEquals(
            ProducerRecoveryDecision.FAIL,
            producerRecoveryDecision(
                targetValid = true,
                processLeaseActive = true,
                elapsedMs = 5_000L,
                timeoutMs = 5_000L,
            ),
        )
    }

    @Test
    fun processLeaseClearsOnlyAfterEveryTrackedProducerExits() {
        val firstRelease = CountDownLatch(1)
        val secondRelease = CountDownLatch(1)
        val first = Thread({ firstRelease.await() }, "renderer-lease-test-1")
        val second = Thread({ secondRelease.await() }, "renderer-lease-test-2")
        first.start()
        second.start()
        try {
            RendererSafetyState.trackUnconfirmed(first)
            RendererSafetyState.trackUnconfirmed(first)
            RendererSafetyState.trackUnconfirmed(second)
            assertTrue(RendererSafetyState.hasUnconfirmedTeardown())

            firstRelease.countDown()
            first.join(1_000L)
            assertFalse(first.isAlive)
            assertTrue(RendererSafetyState.hasUnconfirmedTeardown())

            secondRelease.countDown()
            second.join(1_000L)
            assertFalse(second.isAlive)
            assertFalse(RendererSafetyState.hasUnconfirmedTeardown())
        } finally {
            firstRelease.countDown()
            secondRelease.countDown()
            first.join(1_000L)
            second.join(1_000L)
        }
    }
}
