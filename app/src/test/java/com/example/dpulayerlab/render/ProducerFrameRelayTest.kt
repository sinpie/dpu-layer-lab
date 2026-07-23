package com.example.dpulayerlab.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ProducerFrameRelayTest {
    @Test
    fun physicalProducerRelayPreservesIdentityAndPrimaryAttribution() {
        val events = mutableListOf<Triple<Long, Long, Boolean>>()
        val relay = ProducerFrameRelay(
            producerId = 42L,
            generation = 7L,
            primary = false,
        ) { generation, producerId, primary ->
            events += Triple(generation, producerId, primary)
        }

        relay.emit()
        relay.update(8L) { generation, producerId, primary ->
            events += Triple(generation, producerId, primary)
        }
        relay.emit()
        relay.disable()
        relay.emit()

        assertEquals(
            listOf(
                Triple(7L, 42L, false),
                Triple(8L, 42L, false),
            ),
            events,
        )
    }

    @Test
    fun frameCapturedBeforeGenerationRebindStaysInOldGeneration() {
        val generations = mutableListOf<Long>()
        val relay = ProducerFrameRelay(
            producerId = 9L,
            generation = 100L,
            primary = false,
        ) { generation, _, _ -> generations += generation }

        val oldFrameCommit = relay.captureCallback()
        relay.update(101L) { generation, _, _ -> generations += generation }
        oldFrameCommit?.invoke()
        relay.captureCallback()?.invoke()

        assertEquals(listOf(100L, 101L), generations)
    }

    @Test
    fun captureReusesImmutableTokenUntilUpdateAndDisable() {
        val events = mutableListOf<Triple<Long, Long, Boolean>>()
        val relay = ProducerFrameRelay(
            producerId = 17L,
            generation = 200L,
            primary = true,
        ) { generation, producerId, primary ->
            events += Triple(generation, producerId, primary)
        }

        val firstCapture = relay.captureCallback()
        assertSame(firstCapture, relay.captureCallback())

        relay.update(201L) { generation, producerId, primary ->
            events += Triple(generation, producerId, primary)
        }
        val secondCapture = relay.captureCallback()
        assertNotSame(firstCapture, secondCapture)
        assertSame(secondCapture, relay.captureCallback())

        relay.disable()
        assertNull(relay.captureCallback())
        firstCapture?.invoke()
        secondCapture?.invoke()

        assertEquals(
            listOf(
                Triple(200L, 17L, true),
                Triple(201L, 17L, true),
            ),
            events,
        )
    }

    @Test
    fun reusedProducerRebindsAndReplacedProducerDisables() {
        var first = 0
        var second = 0
        val relay = ProducerFrameRelay(
            producerId = 1L,
            generation = 1L,
            primary = true,
        ) { _, _, _ -> first++ }

        relay.emit()
        relay.update(generation = 2L) { _, _, _ -> second++ }
        relay.emit()
        relay.disable()
        relay.emit()

        assertEquals(1, first)
        assertEquals(1, second)
    }

    @Test
    fun queuedOldProducerEventCannotReachNewGenerationRelay() {
        var oldGeneration = 0
        var newGeneration = 0
        val oldRelay = ProducerFrameRelay(
            producerId = 1L,
            generation = 1L,
            primary = true,
        ) { _, _, _ -> oldGeneration++ }
        val newRelay = ProducerFrameRelay(
            producerId = 2L,
            generation = 2L,
            primary = true,
        ) { _, _, _ -> newGeneration++ }

        oldRelay.disable()
        // Models a MediaCodec callback that was queued before the producer was replaced.
        oldRelay.emit()
        newRelay.emit()

        assertEquals(0, oldGeneration)
        assertEquals(1, newGeneration)
    }

    @Test
    fun removedProducerLateTeardownCannotPoisonTheNextGeneration() {
        val failures = mutableListOf<Long>()
        val removedRelay = ProducerFrameRelay(
            producerId = 21L,
            generation = 4L,
            primary = false,
        ) { _, _, _ -> Unit }
        val reportRemovedFailure = {
            removedRelay.activeGenerationForFailure()?.let(failures::add)
        }

        removedRelay.disable()
        // Models a delayed SurfaceView.surfaceDestroyed callback after the stage has already
        // advanced to generation 5. A removed relay must not read or poison that mutable stage
        // generation.
        reportRemovedFailure()

        assertEquals(emptyList<Long>(), failures)

        val reusedRelay = ProducerFrameRelay(
            producerId = 22L,
            generation = 4L,
            primary = false,
        ) { _, _, _ -> Unit }
        reusedRelay.update(5L) { _, _, _ -> Unit }
        reusedRelay.activeGenerationForFailure()?.let(failures::add)

        assertEquals(listOf(5L), failures)
    }
}
