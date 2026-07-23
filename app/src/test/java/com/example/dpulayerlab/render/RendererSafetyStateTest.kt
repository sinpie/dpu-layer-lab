package com.example.dpulayerlab.render

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererSafetyStateTest {
    @Test
    fun liveProducerBlocksUntilItsThreadActuallyTerminates() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val producer = Thread(
            {
                started.countDown()
                release.await()
            },
            "RendererSafetyStateTest-producer",
        )
        producer.isDaemon = true
        producer.start()
        assertTrue(started.await(1, TimeUnit.SECONDS))

        try {
            RendererSafetyState.trackUnconfirmed(producer)
            assertTrue(RendererSafetyState.hasUnconfirmedTeardown())
        } finally {
            release.countDown()
            producer.join(1_000L)
        }
        assertFalse(producer.isAlive)
        assertFalse(RendererSafetyState.hasUnconfirmedTeardown())
    }

    @Test
    fun alreadyStoppedThreadDoesNotCreateAFalseLease() {
        val producer = Thread { Unit }
        producer.start()
        producer.join(1_000L)
        assertFalse(producer.isAlive)

        RendererSafetyState.trackUnconfirmed(producer)

        assertFalse(RendererSafetyState.hasUnconfirmedTeardown())
    }
}
