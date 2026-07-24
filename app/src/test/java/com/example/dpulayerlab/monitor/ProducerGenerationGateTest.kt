package com.example.dpulayerlab.monitor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProducerGenerationGateTest {
    @Test
    fun staleProducerCannotSatisfyNewGeneration() {
        val gate = ProducerGenerationGate()
        val warmup = gate.begin()
        assertTrue(gate.expect(warmup, setOf(0L), nowMs = 0L))
        assertTrue(gate.activate(warmup, nowMs = 0L))
        assertTrue(gate.accept(warmup))
        assertTrue(gate.readiness(warmup, nowMs = 0L).ready)

        val target = gate.begin()
        assertFalse(gate.accept(warmup))
        assertFalse(gate.readiness(target, nowMs = 0L).ready)
        assertTrue(gate.expect(target, setOf(0L), nowMs = 0L))
        assertTrue(gate.activate(target, nowMs = 0L))
        assertTrue(gate.accept(target))
        assertTrue(gate.readiness(target, nowMs = 0L).ready)
    }

    @Test
    fun frameBeforeTopologyPublicationCannotPrematurelySatisfyGeneration() {
        val gate = ProducerGenerationGate()
        val generation = gate.begin(nowMs = 100L)

        // Preparation buffers are ignored until the complete topology is published and the
        // controller explicitly activates first-buffer observation.
        assertFalse(gate.accept(generation, producerId = 10L, nowMs = 110L))
        val undeclared = gate.readiness(generation, nowMs = 3_100L)
        assertFalse(undeclared.ready)
        assertEquals(0, undeclared.expectedCount)
        assertTrue(undeclared.unreadyForMs == 3_000L)

        assertTrue(gate.expect(generation, setOf(10L, 20L), nowMs = 120L))
        assertTrue(gate.activate(generation, nowMs = 120L))
        assertFalse(gate.readiness(generation, nowMs = 121L).ready)
        assertTrue(gate.accept(generation, producerId = 10L, nowMs = 125L))
        assertTrue(gate.accept(generation, producerId = 20L, nowMs = 130L))
        assertTrue(gate.readiness(generation, nowMs = 131L).ready)
    }

    @Test
    fun everyExpectedPhysicalProducerMustPost() {
        val gate = ProducerGenerationGate()
        val generation = gate.begin(nowMs = 100L)
        assertTrue(gate.expect(generation, setOf(10L, 20L, 30L), nowMs = 120L))
        assertTrue(gate.activate(generation, nowMs = 120L))

        assertTrue(gate.accept(generation, 10L, nowMs = 200L))
        assertTrue(gate.accept(generation, 20L, nowMs = 200L))
        val partial = gate.readiness(generation, nowMs = 3_119L)
        assertFalse(partial.ready)
        assertTrue(partial.expectedCount == 3)
        assertTrue(partial.observedCount == 2)
        assertTrue(partial.unreadyForMs == 2_999L)

        assertTrue(gate.accept(generation, 30L, nowMs = 3_120L))
        assertTrue(gate.readiness(generation, nowMs = 3_120L).ready)
    }

    @Test
    fun changingTopologyRestartsOnlyTheMissingProducerWindow() {
        val gate = ProducerGenerationGate()
        val generation = gate.begin(nowMs = 0L)
        assertTrue(gate.expect(generation, setOf(1L), nowMs = 10L))
        assertTrue(gate.activate(generation, nowMs = 10L))
        assertTrue(gate.accept(generation, 1L, nowMs = 20L))
        assertTrue(gate.readiness(generation, nowMs = 100L).ready)

        assertTrue(gate.expect(generation, setOf(1L, 2L), nowMs = 200L))
        val expanded = gate.readiness(generation, nowMs = 250L)
        assertFalse(expanded.ready)
        assertTrue(expanded.observedCount == 1)
        assertTrue(expanded.unreadyForMs == 50L)
    }

    @Test
    fun addingAnotherProducerDoesNotResetOlderMissingDeadline() {
        val gate = ProducerGenerationGate()
        val generation = gate.begin(nowMs = 0L)
        assertTrue(gate.expect(generation, setOf(1L), nowMs = 100L))
        assertTrue(gate.activate(generation, nowMs = 100L))
        assertTrue(gate.expect(generation, setOf(1L, 2L), nowMs = 2_000L))

        val readiness = gate.readiness(generation, nowMs = 3_100L)
        assertFalse(readiness.ready)
        assertTrue(readiness.unreadyForMs == 3_000L)
    }

    @Test
    fun removingNeverProducedPeakMarksTopologyMissed() {
        val gate = ProducerGenerationGate()
        val generation = gate.begin(nowMs = 0L)
        assertTrue(gate.expect(generation, setOf(1L, 2L), nowMs = 10L))
        assertTrue(gate.activate(generation, nowMs = 10L))
        assertTrue(gate.accept(generation, 1L, nowMs = 20L))
        assertTrue(gate.expect(generation, setOf(1L), nowMs = 100L))

        assertTrue(gate.readiness(generation, nowMs = 110L).topologyMissed)
    }

    @Test
    fun observedOriginCanBeReplacedByMeasuredStepWithoutFalseMiss() {
        val gate = ProducerGenerationGate()
        val generation = gate.begin(nowMs = 0L)
        assertTrue(gate.expect(generation, setOf(1L), nowMs = 10L))
        assertTrue(gate.activate(generation, nowMs = 10L))
        assertTrue(gate.accept(generation, 1L, nowMs = 20L))

        assertTrue(gate.expect(generation, setOf(2L), nowMs = 100L))

        assertFalse(gate.readiness(generation, nowMs = 101L).topologyMissed)
    }

    @Test
    fun producerHeartbeatAndTeardownFailureRemainVisible() {
        val gate = ProducerGenerationGate()
        val generation = gate.begin(nowMs = 0L)
        assertTrue(gate.expect(generation, setOf(7L), nowMs = 10L))
        assertTrue(gate.activate(generation, nowMs = 10L))
        assertTrue(gate.accept(generation, 7L, nowMs = 20L))
        assertTrue(gate.markTeardownFailure(generation))

        val readiness = gate.readiness(generation, nowMs = 3_020L)
        assertTrue(readiness.ready)
        assertTrue(readiness.oldestFrameAgeMs == 3_000L)
        assertTrue(readiness.observedCount == 1)
        assertTrue(readiness.teardownFailed)
    }

    @Test
    fun teardownCompletionIsGenerationScopedAndDoesNotEraseFailure() {
        val gate = ProducerGenerationGate()
        val generation = gate.begin(nowMs = 0L)
        assertFalse(gate.markTeardownComplete(generation + 1L))
        assertTrue(gate.markTeardownFailure(generation))
        assertTrue(gate.markTeardownComplete(generation))

        val readiness = gate.readiness(generation, nowMs = 10L)
        assertTrue(readiness.teardownFailed)
        assertTrue(readiness.teardownCompleted)

        val next = gate.begin(nowMs = 20L)
        assertFalse(gate.markTeardownFailure(generation))
        assertFalse(gate.readiness(next, nowMs = 20L).teardownFailed)
        assertFalse(gate.readiness(next, nowMs = 20L).teardownCompleted)
    }

    @Test
    fun producerReattachRevokesEarlierStageRemovalAcknowledgement() {
        val gate = ProducerGenerationGate()
        val generation = gate.begin(nowMs = 0L)
        assertTrue(gate.markTeardownComplete(generation))
        assertTrue(gate.readiness(generation, nowMs = 1L).teardownCompleted)

        assertTrue(gate.expect(generation, setOf(99L), nowMs = 2L))

        assertFalse(gate.readiness(generation, nowMs = 3L).teardownCompleted)
    }

    @Test
    fun highProducerIdsUseStableSetSemanticsAndRefreshHeartbeat() {
        val gate = ProducerGenerationGate()
        val generation = gate.begin(nowMs = 0L)
        assertTrue(
            gate.expect(
                generation,
                linkedSetOf(10_000L, 20_000L),
                nowMs = 10L,
            ),
        )
        assertTrue(gate.activate(generation, nowMs = 10L))
        assertTrue(gate.accept(generation, 10_000L, nowMs = 20L))
        assertTrue(gate.accept(generation, 20_000L, nowMs = 30L))

        // Reordering an otherwise identical topology must not look like a producer removal.
        assertTrue(
            gate.expect(
                generation,
                linkedSetOf(20_000L, 10_000L),
                nowMs = 100L,
            ),
        )
        assertTrue(gate.accept(generation, 10_000L, nowMs = 4_000L))
        assertTrue(gate.accept(generation, 20_000L, nowMs = 4_010L))

        val readiness = gate.readiness(generation, nowMs = 4_011L)
        assertTrue(readiness.ready)
        assertTrue(readiness.observedCount == 2)
        assertTrue(readiness.oldestFrameAgeMs == 11L)
        assertFalse(readiness.topologyMissed)
    }

    @Test
    fun pendingTopologyPublishesNoFakeExpectedSetAndCommitsOnceReady() {
        val gate = ProducerGenerationGate()
        val generation = gate.begin(nowMs = 100L)

        assertTrue(gate.markTopologyPending(generation))
        val pending = gate.readiness(generation, nowMs = 4_000L)
        assertFalse(pending.topologyPublished)
        assertTrue(pending.topologyPending)
        assertEquals(0, pending.expectedCount)
        assertFalse(pending.topologyMissed)
        assertFalse(gate.activate(generation, nowMs = 4_000L))

        assertTrue(gate.expect(generation, setOf(11L, 12L), nowMs = 4_100L))
        val published = gate.readiness(generation, nowMs = 4_101L)
        assertTrue(published.topologyPublished)
        assertFalse(published.topologyPending)
        assertTrue(published.topologyPublishedAtMs == 4_100L)
        assertTrue(gate.activate(generation, nowMs = 4_200L))
    }

    @Test
    fun repeatedExpectedTopologyDoesNotResetFirstPublicationEpoch() {
        val gate = ProducerGenerationGate()
        val generation = gate.begin(nowMs = 0L)

        assertTrue(gate.expect(generation, setOf(5L), nowMs = 2_300L))
        assertTrue(gate.expect(generation, setOf(5L), nowMs = 4_000L))

        assertTrue(
            gate.readiness(generation, nowMs = 4_001L).topologyPublishedAtMs == 2_300L,
        )
        assertEquals(1L, gate.readiness(generation, nowMs = 4_001L).topologyRevision)
    }

    @Test
    fun topologyRevisionChangesOnlyWhenTheCommittedProducerSetChanges() {
        val gate = ProducerGenerationGate()
        val generation = gate.begin(nowMs = 0L)

        assertTrue(gate.expect(generation, setOf(5L), nowMs = 10L))
        assertEquals(1L, gate.readiness(generation, nowMs = 11L).topologyRevision)
        assertTrue(gate.expect(generation, setOf(5L), nowMs = 20L))
        assertEquals(1L, gate.readiness(generation, nowMs = 21L).topologyRevision)
        assertTrue(gate.expect(generation, setOf(6L), nowMs = 30L))
        assertEquals(2L, gate.readiness(generation, nowMs = 31L).topologyRevision)
    }

    @Test
    fun recoveryRestartRequiresFreshBuffersWithoutChangingPublicationEpoch() {
        val gate = ProducerGenerationGate()
        val generation = gate.begin(nowMs = 0L)
        assertTrue(gate.expect(generation, setOf(1L, 2L), nowMs = 10L))
        assertTrue(gate.activate(generation, nowMs = 20L))
        assertTrue(gate.accept(generation, 1L, nowMs = 30L))
        assertTrue(gate.accept(generation, 2L, nowMs = 30L))
        assertTrue(gate.readiness(generation, nowMs = 40L).ready)

        assertTrue(gate.restartObservation(generation, nowMs = 2_500L))
        val restarted = gate.readiness(generation, nowMs = 2_501L)
        assertFalse(restarted.ready)
        assertTrue(restarted.topologyPublishedAtMs == 10L)
        assertTrue(gate.accept(generation, 1L, nowMs = 2_510L))
        assertTrue(gate.accept(generation, 2L, nowMs = 2_520L))
        assertTrue(gate.readiness(generation, nowMs = 2_521L).ready)
    }

    @Test
    fun runtimeFailureIsBoundedPreservesFirstReasonAndMakesGenerationUnready() {
        val gate = ProducerGenerationGate()
        val generation = gate.begin(nowMs = 0L)
        assertTrue(gate.expect(generation, setOf(1L), nowMs = 1L))
        assertTrue(gate.activate(generation, nowMs = 2L))
        assertTrue(gate.accept(generation, 1L, nowMs = 3L))
        assertTrue(gate.readiness(generation, nowMs = 4L).ready)

        assertTrue(gate.markRuntimeFailure(generation, "  codec failed  "))
        assertTrue(gate.markRuntimeFailure(generation, "later failure"))
        val failed = gate.readiness(generation, nowMs = 5L)

        assertFalse(failed.ready)
        assertEquals("codec failed", failed.runtimeFailureReason)
    }

    @Test
    fun runtimeFailureIsGenerationScopedAndResetByBegin() {
        val gate = ProducerGenerationGate()
        val oldGeneration = gate.begin(nowMs = 0L)
        val currentGeneration = gate.begin(nowMs = 10L)

        assertFalse(gate.markRuntimeFailure(oldGeneration, "stale"))
        assertNull(gate.readiness(currentGeneration, nowMs = 11L).runtimeFailureReason)
        assertTrue(gate.markRuntimeFailure(currentGeneration, "x".repeat(500)))
        assertEquals(
            240,
            gate.readiness(currentGeneration, nowMs = 12L)
                .runtimeFailureReason
                ?.length,
        )

        val nextGeneration = gate.begin(nowMs = 20L)
        assertNull(gate.readiness(nextGeneration, nowMs = 21L).runtimeFailureReason)
    }
}
