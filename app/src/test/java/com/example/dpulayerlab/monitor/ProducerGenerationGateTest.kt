package com.example.dpulayerlab.monitor

import com.example.dpulayerlab.model.LayerSizeProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProducerGenerationGateTest {
    @Test
    fun geometryRevisionRequiresMatchingPostFrameAckAndIsGenerationScoped() {
        val gate = ProducerGenerationGate()
        val generation = gate.begin(nowMs = 100L)
        val profile = LayerSizeProfile.MIXED_SIZES.ordinal

        assertFalse(
            gate.requestLayerGeometry(
                candidate = generation + 1L,
                revision = 1L,
                profileOrdinal = profile,
                nowMs = 110L,
            ),
        )
        assertTrue(
            gate.requestLayerGeometry(
                candidate = generation,
                revision = 1L,
                profileOrdinal = profile,
                nowMs = 120L,
            ),
        )
        val pending = gate.readiness(generation, nowMs = 150L)
        assertFalse(pending.geometryReady)
        assertEquals(30L, pending.geometryPendingForMs)
        assertEquals(1L, pending.geometryRequestedRevision)
        assertEquals(0L, pending.geometryAppliedRevision)

        assertFalse(
            gate.acknowledgeLayerGeometry(
                candidate = generation,
                revision = 2L,
                profileOrdinal = profile,
                coverageBit = 1,
            ),
        )
        assertTrue(
            gate.acknowledgeLayerGeometry(
                candidate = generation,
                revision = 1L,
                profileOrdinal = profile,
                coverageBit = 1,
            ),
        )
        val applied = gate.readiness(generation, nowMs = 151L)
        assertTrue(applied.geometryReady)
        assertEquals(profile, applied.geometryAppliedProfileOrdinal)
        assertEquals(1, applied.geometryCoverageMask)

        val next = gate.begin(nowMs = 200L)
        assertFalse(gate.readiness(next, nowMs = 201L).geometryReady)
        assertEquals(0, gate.readiness(next, nowMs = 201L).geometryCoverageMask)
        assertFalse(
            gate.acknowledgeLayerGeometry(
                candidate = generation,
                revision = 1L,
                profileOrdinal = profile,
                coverageBit = 1,
            ),
        )
    }

    @Test
    fun geometryCoverageIsRetainedPerProfileAcrossRecoveryPreparation() {
        val gate = ProducerGenerationGate()
        val generation = gate.begin(nowMs = 0L)
        val dynamic = LayerSizeProfile.GRADUAL_SMALL_TO_FULL.ordinal
        val preparation = LayerSizeProfile.SMALL_UNIFORM.ordinal

        assertTrue(
            gate.recordEquivalentLayerGeometryCoverage(
                candidate = generation,
                profileOrdinal = dynamic,
                coverageBit = 0b001,
            ),
        )
        assertTrue(gate.requestLayerGeometry(generation, 1L, dynamic, nowMs = 1L))
        assertTrue(gate.acknowledgeLayerGeometry(generation, 1L, dynamic, 0b010))
        assertEquals(0b011, gate.readiness(generation, nowMs = 3L).geometryCoverageMask)

        assertTrue(gate.requestLayerGeometry(generation, 2L, preparation, nowMs = 4L))
        assertTrue(gate.acknowledgeLayerGeometry(generation, 2L, preparation, 0b001))
        assertEquals(0b001, gate.readiness(generation, nowMs = 5L).geometryCoverageMask)

        assertTrue(gate.requestLayerGeometry(generation, 3L, dynamic, nowMs = 6L))
        assertTrue(gate.acknowledgeLayerGeometry(generation, 3L, dynamic, 0b100))
        val resumed = gate.readiness(generation, nowMs = 7L)
        assertTrue(resumed.geometryReady)
        assertEquals(0b111, resumed.geometryCoverageMask)
    }

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
        val profile = LayerSizeProfile.FULL_SCREEN.ordinal
        assertTrue(gate.expect(generation, setOf(1L, 2L), nowMs = 10L))
        assertTrue(gate.requestLayerGeometry(generation, 1L, profile, nowMs = 11L))
        assertTrue(gate.acknowledgeLayerGeometry(generation, 1L, profile, coverageBit = 1))
        assertTrue(gate.activate(generation, nowMs = 12L))
        assertTrue(gate.accept(generation, 1L, nowMs = 20L))
        assertTrue(gate.expect(generation, setOf(1L), nowMs = 100L))

        val missed = gate.readiness(generation, nowMs = 110L)
        assertTrue(missed.topologyMissed)
        assertFalse(missed.ready)
        assertFalse(missed.geometryReady)
        assertEquals(0, missed.geometryCoverageMask)
        assertFalse(gate.activate(generation, nowMs = 111L))
        assertFalse(gate.accept(generation, 1L, nowMs = 112L))
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
    fun teardownFailureMakesTheGenerationTerminalAndUnready() {
        val gate = ProducerGenerationGate()
        val generation = gate.begin(nowMs = 0L)
        val profile = LayerSizeProfile.FULL_SCREEN.ordinal
        assertTrue(gate.expect(generation, setOf(7L), nowMs = 10L))
        assertTrue(gate.requestLayerGeometry(generation, 1L, profile, nowMs = 11L))
        assertTrue(gate.acknowledgeLayerGeometry(generation, 1L, profile, coverageBit = 1))
        assertTrue(gate.activate(generation, nowMs = 12L))
        assertTrue(gate.accept(generation, 7L, nowMs = 20L))
        assertTrue(gate.markTeardownFailure(generation))

        val readiness = gate.readiness(generation, nowMs = 3_020L)
        assertFalse(readiness.ready)
        assertTrue(readiness.oldestFrameAgeMs == 3_000L)
        assertEquals(0, readiness.observedCount)
        assertFalse(readiness.geometryReady)
        assertEquals(0, readiness.geometryCoverageMask)
        assertTrue(readiness.teardownFailed)
        assertFalse(gate.activate(generation, nowMs = 3_021L))
        assertFalse(gate.accept(generation, 7L, nowMs = 3_022L))
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
    fun detachedStageEvidenceCannotRecoverBeforeRepublishActivationAndFreshBuffer() {
        val gate = ProducerGenerationGate()
        val generation = gate.begin(nowMs = 0L)
        val profile = LayerSizeProfile.MIXED_SIZES.ordinal
        assertTrue(gate.expect(generation, setOf(7L), nowMs = 1L))
        assertTrue(gate.requestLayerGeometry(generation, 1L, profile, nowMs = 2L))
        assertTrue(gate.acknowledgeLayerGeometry(generation, 1L, profile, coverageBit = 1))
        assertTrue(gate.activate(generation, nowMs = 3L))
        assertTrue(gate.accept(generation, 7L, nowMs = 4L))
        val attached = gate.readiness(generation, nowMs = 5L)
        assertTrue(attached.ready)
        assertTrue(attached.geometryReady)

        assertTrue(gate.markTeardownComplete(generation))
        val detached = gate.readiness(generation, nowMs = 6L)
        assertTrue(detached.teardownCompleted)
        assertFalse(detached.ready)
        assertEquals(0, detached.observedCount)
        assertFalse(detached.geometryReady)
        assertEquals(0, detached.geometryCoverageMask)
        assertFalse(gate.accept(generation, 7L, nowMs = 7L))
        assertFalse(gate.requestLayerGeometry(generation, 2L, profile, nowMs = 7L))
        assertFalse(gate.acknowledgeLayerGeometry(generation, 1L, profile, coverageBit = 1))

        // A replacement stage first announces a pending transaction and prepares fresh geometry.
        assertTrue(gate.markTopologyPending(generation))
        assertTrue(gate.requestLayerGeometry(generation, 2L, profile, nowMs = 8L))
        assertTrue(gate.acknowledgeLayerGeometry(generation, 2L, profile, coverageBit = 1))
        assertTrue(gate.expect(generation, setOf(99L), nowMs = 9L))

        val republished = gate.readiness(generation, nowMs = 10L)
        assertFalse(republished.teardownCompleted)
        assertFalse(republished.ready)
        assertTrue(republished.geometryReady)
        assertEquals(0, republished.observedCount)
        assertFalse(gate.accept(generation, 7L, nowMs = 11L))

        assertTrue(gate.activate(generation, nowMs = 12L))
        assertFalse(gate.readiness(generation, nowMs = 13L).ready)
        assertTrue(gate.accept(generation, 99L, nowMs = 14L))
        val recovered = gate.readiness(generation, nowMs = 15L)
        assertTrue(recovered.ready)
        assertTrue(recovered.geometryReady)
        assertEquals(1, recovered.observedCount)
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
