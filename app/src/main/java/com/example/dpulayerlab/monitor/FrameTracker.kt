package com.example.dpulayerlab.monitor

import android.os.SystemClock
import android.view.Choreographer
import com.example.dpulayerlab.model.AppProducerKind
import com.example.dpulayerlab.model.AppProducerTopology
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Portable proxy only. Missed UI vsync deadlines are never labelled as exact DPU underruns.
 */
class FrameTracker : Choreographer.FrameCallback {
    private val running = AtomicBoolean(false)
    private val produced = AtomicLong(0)
    private val totalProduced = AtomicLong(0)
    private val totalPhysicalProduced = AtomicLong(0)
    private val missed = AtomicLong(0)
    private val deadlineLock = Any()
    private val producerGeneration = ProducerGenerationGate()

    // Accessed by both the Choreographer thread and the monitor's IO dispatcher.
    // Keep both fields under one lock so a refresh-rate change and its baseline reset are atomic.
    private var lastFrameNanos = 0L

    private var expectedRefreshHz = 60f

    fun start() {
        resetDeadlineBaseline()
        if (!running.compareAndSet(false, true)) return
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() {
        running.set(false)
        Choreographer.getInstance().removeFrameCallback(this)
        resetDeadlineBaseline()
    }

    /**
     * Starts a new producer-observation generation. Frames carrying an older token are ignored,
     * so a draining warm-up/previous Surface cannot satisfy the next phase's startup guard.
     */
    fun beginProducerGeneration(): Long =
        producerGeneration.begin(SystemClock.elapsedRealtime())

    fun expectProducers(generation: Long, producerIds: Set<Long>) {
        producerGeneration.expect(
            candidate = generation,
            producerIds = producerIds,
            nowMs = SystemClock.elapsedRealtime(),
        )
    }

    fun expectProducerTopology(topology: AppProducerTopology) {
        producerGeneration.expect(
            topology = topology,
            nowMs = SystemClock.elapsedRealtime(),
        )
    }

    fun requestLayerGeometry(
        generation: Long,
        revision: Long,
        profileOrdinal: Int,
    ) {
        producerGeneration.requestLayerGeometry(
            candidate = generation,
            revision = revision,
            profileOrdinal = profileOrdinal,
            nowMs = SystemClock.elapsedRealtime(),
        )
    }

    fun acknowledgeLayerGeometry(
        generation: Long,
        revision: Long,
        profileOrdinal: Int,
        coverageBit: Int,
    ) {
        producerGeneration.acknowledgeLayerGeometry(
            candidate = generation,
            revision = revision,
            profileOrdinal = profileOrdinal,
            coverageBit = coverageBit,
        )
    }

    /**
     * Arms an exact renderer-control publication. A later readiness snapshot becomes ready only
     * after every producer in the committed topology posts a frame carrying this same revision.
     */
    fun requestProducerControlRevision(
        generation: Long,
        revision: Long,
    ): Boolean =
        producerGeneration.requestProducerControlRevision(
            candidate = generation,
            revision = revision,
            nowMs = SystemClock.elapsedRealtime(),
        )

    fun recordEquivalentLayerGeometryCoverage(
        generation: Long,
        profileOrdinal: Int,
        coverageBit: Int,
    ) {
        producerGeneration.recordEquivalentLayerGeometryCoverage(
            candidate = generation,
            profileOrdinal = profileOrdinal,
            coverageBit = coverageBit,
        )
    }

    fun markProducerTopologyPending(generation: Long): Boolean =
        producerGeneration.markTopologyPending(generation)

    fun activateProducerGeneration(generation: Long): Boolean =
        producerGeneration.activate(
            candidate = generation,
            nowMs = SystemClock.elapsedRealtime(),
        )

    fun restartProducerObservation(generation: Long): Boolean =
        producerGeneration.restartObservation(
            candidate = generation,
            nowMs = SystemClock.elapsedRealtime(),
        )

    fun onProducerBufferProduced(
        generation: Long,
        producerId: Long,
        primary: Boolean,
        controlRevision: Long = 0L,
    ) {
        if (
            !producerGeneration.accept(
                candidate = generation,
                producerId = producerId,
                primary = primary,
                nowMs = SystemClock.elapsedRealtime(),
                controlRevision = controlRevision,
            )
        ) {
            return
        }
        totalPhysicalProduced.incrementAndGet()
        if (primary) {
            produced.incrementAndGet()
            totalProduced.incrementAndGet()
        }
    }

    fun producerReadiness(generation: Long): ProducerReadiness =
        producerGeneration.readiness(generation, SystemClock.elapsedRealtime())

    fun markProducerTeardownFailure(generation: Long) {
        producerGeneration.markTeardownFailure(generation)
    }

    fun markProducerRuntimeFailure(generation: Long, reason: String): Boolean =
        producerGeneration.markRuntimeFailure(generation, reason)

    fun markProducerTeardownComplete(generation: Long) {
        producerGeneration.markTeardownComplete(generation)
    }

    fun updateExpectedRefresh(hz: Float) {
        if (!hz.isFinite() || hz <= 1f || hz > MAX_REASONABLE_REFRESH_HZ) return
        synchronized(deadlineLock) {
            if (abs(expectedRefreshHz - hz) >= REFRESH_CHANGE_EPSILON_HZ) {
                expectedRefreshHz = hz
                lastFrameNanos = 0L
            }
        }
    }

    fun sampleProducedFrames(): Long = produced.getAndSet(0)

    fun totalProducedFrames(): Long = totalProduced.get()

    /** All generation-accepted physical producers, including overlay and GL-tail buffers. */
    fun totalPhysicalProducedFrames(): Long = totalPhysicalProduced.get()

    fun totalMissedFrames(): Long = missed.get()

    override fun doFrame(frameTimeNanos: Long) {
        if (!running.get()) return
        val lost = synchronized(deadlineLock) {
            val previousFrameNanos = lastFrameNanos
            lastFrameNanos = frameTimeNanos
            if (previousFrameNanos <= 0L || frameTimeNanos <= previousFrameNanos) {
                0L
            } else {
                val expected = NANOS_PER_SECOND / expectedRefreshHz.toDouble()
                val delta = (frameTimeNanos - previousFrameNanos).toDouble()
                if (delta > expected * MISSED_DEADLINE_FACTOR) {
                    (delta / expected).roundToLong().minus(1).coerceAtLeast(1L)
                } else {
                    0L
                }
            }
        }
        if (lost > 0L) missed.addAndGet(lost)
        if (running.get()) Choreographer.getInstance().postFrameCallback(this)
    }

    private fun resetDeadlineBaseline() {
        synchronized(deadlineLock) {
            lastFrameNanos = 0L
        }
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val MISSED_DEADLINE_FACTOR = 1.55
        const val REFRESH_CHANGE_EPSILON_HZ = 0.05f
        const val MAX_REASONABLE_REFRESH_HZ = 1_000f
    }
}

/**
 * Small synchronized gate used by every physical-producer callback (at most the bounded aggregate
 * producer rate). Keeping generation, per-producer heartbeat, and exact control revision under one
 * lock avoids losing the first frame in begin/publish-vs-callback races.
 */
internal class ProducerGenerationGate {
    private var generation = 0L
    private var expectedProducerIds = LongArray(0)
    private var expectedProducerKindOrdinals = IntArray(0)
    private var expectedProducerPrimary = BooleanArray(0)
    private var committedTopology: AppProducerTopology? = null
    private var topologyDeclared = false
    private var topologyPending = false
    private var activated = false
    private var topologyPublishedMs = -1L
    private var topologyRevision = 0L
    private var topologyDiscontinuitySerial = 0L
    private var geometryRequestedRevision = 0L
    private var geometryAppliedRevision = 0L
    private var geometryRequestedProfileOrdinal = -1
    private var geometryAppliedProfileOrdinal = -1
    private val geometryCoverageMasks = IntArray(MAX_LAYER_SIZE_PROFILE_COUNT)
    private var geometryRequestedMs = -1L
    private var producerControlRequestedRevision = 0L
    private var producerControlRequestedMs = -1L
    // Reserve the documented 20-producer ceiling up front. First endpoint evidence must not grow
    // primitive maps from inside the measured frame callback.
    private val producerControlAppliedRevisions =
        LongTimestampMap(MAX_TRACKED_PHYSICAL_PRODUCERS)
    private val expectedSinceMs = LongTimestampMap(MAX_TRACKED_PHYSICAL_PRODUCERS)
    private val lastObservedMs = LongTimestampMap(MAX_TRACKED_PHYSICAL_PRODUCERS)
    private var generationStartedMs = 0L
    private var topologyMissed = false
    private var teardownFailed = false
    private var teardownCompleted = false
    private var runtimeFailureReason: String? = null
    private var decoderGenerationFrameCount = 0L
    private var decoderObservationFrameCount = 0L
    private var decoderLastFrameMs = -1L
    private var decoderLastControlRevision = 0L

    @Synchronized
    fun begin(nowMs: Long = 0L): Long {
        generation = if (generation == Long.MAX_VALUE) 1L else generation + 1L
        expectedProducerIds = LongArray(0)
        expectedProducerKindOrdinals = IntArray(0)
        expectedProducerPrimary = BooleanArray(0)
        committedTopology = null
        topologyDeclared = false
        topologyPending = false
        activated = false
        topologyPublishedMs = -1L
        topologyRevision = 0L
        topologyDiscontinuitySerial = 0L
        geometryRequestedRevision = 0L
        geometryAppliedRevision = 0L
        geometryRequestedProfileOrdinal = -1
        geometryAppliedProfileOrdinal = -1
        geometryCoverageMasks.fill(0)
        geometryRequestedMs = -1L
        producerControlRequestedRevision = 0L
        producerControlRequestedMs = -1L
        producerControlAppliedRevisions.clear()
        expectedSinceMs.clear()
        lastObservedMs.clear()
        generationStartedMs = nowMs.coerceAtLeast(0L)
        topologyMissed = false
        teardownFailed = false
        teardownCompleted = false
        runtimeFailureReason = null
        decoderGenerationFrameCount = 0L
        resetDecoderObservation()
        return generation
    }

    @Synchronized
    fun expect(
        candidate: Long,
        producerIds: Set<Long>,
        nowMs: Long,
    ): Boolean {
        if (
            candidate != generation ||
            producerIds.isEmpty() ||
            producerIds.size > MAX_TRACKED_PHYSICAL_PRODUCERS
        ) {
            return false
        }
        val normalizedBuffer = LongArray(producerIds.size)
        var normalizedCount = 0
        for (id in producerIds) {
            if (id >= 0L) normalizedBuffer[normalizedCount++] = id
        }
        if (normalizedCount == 0) return false
        val normalized = if (normalizedCount == normalizedBuffer.size) {
            normalizedBuffer
        } else {
            normalizedBuffer.copyOf(normalizedCount)
        }
        normalized.sort()
        return commitExpectedTopology(
            candidate = candidate,
            normalizedIds = normalized,
            kindOrdinals = IntArray(normalized.size) { AppProducerKind.UNKNOWN.ordinal },
            primaryFlags = BooleanArray(normalized.size),
            typedTopology = null,
            nowMs = nowMs,
        )
    }

    @Synchronized
    fun expect(
        topology: AppProducerTopology,
        nowMs: Long,
    ): Boolean {
        if (
            topology.generation != generation ||
            topology.producers.isEmpty() ||
            topology.producers.size > MAX_TRACKED_PHYSICAL_PRODUCERS
        ) {
            return false
        }
        val ids = LongArray(topology.producers.size)
        val kinds = IntArray(topology.producers.size)
        val primaryFlags = BooleanArray(topology.producers.size)
        topology.producers.forEachIndexed { index, descriptor ->
            ids[index] = descriptor.producerId
            kinds[index] = descriptor.kind.ordinal
            primaryFlags[index] = descriptor.primary
        }
        return commitExpectedTopology(
            candidate = topology.generation,
            normalizedIds = ids,
            kindOrdinals = kinds,
            primaryFlags = primaryFlags,
            typedTopology = topology,
            nowMs = nowMs,
        )
    }

    private fun commitExpectedTopology(
        candidate: Long,
        normalizedIds: LongArray,
        kindOrdinals: IntArray,
        primaryFlags: BooleanArray,
        typedTopology: AppProducerTopology?,
        nowMs: Long,
    ): Boolean {
        if (
            candidate != generation ||
            normalizedIds.isEmpty() ||
            normalizedIds.size != kindOrdinals.size ||
            normalizedIds.size != primaryFlags.size
        ) {
            return false
        }
        val wasTopologyPending = topologyPending
        if (!topologyDeclared) {
            topologyPublishedMs = nowMs.coerceAtLeast(0L)
        }
        topologyDeclared = true
        topologyPending = false
        // A stage can detach and later rebuild in the same controller generation (for example a
        // lifecycle stop/start race). Any newly published producer topology revokes the earlier
        // stage-removal acknowledgement; only a later terminal removal may complete the barrier.
        teardownCompleted = false
        val idsChanged = !normalizedIds.contentEquals(expectedProducerIds)
        val sameProducerMembership =
            normalizedIds.size == expectedProducerIds.size &&
                normalizedIds.all { id -> expectedProducerIds.containsId(id) }
        val descriptorsChanged =
            idsChanged ||
                !kindOrdinals.contentEquals(expectedProducerKindOrdinals) ||
                !primaryFlags.contentEquals(expectedProducerPrimary)
        if (descriptorsChanged) {
            topologyRevision =
                if (topologyRevision == Long.MAX_VALUE) 1L else topologyRevision + 1L
            for (id in expectedProducerIds) {
                if (!normalizedIds.containsId(id) && !lastObservedMs.contains(id)) {
                    topologyMissed = true
                    break
                }
            }
            expectedSinceMs.retainAll(normalizedIds)
            lastObservedMs.retainAll(normalizedIds)
            producerControlAppliedRevisions.retainAll(normalizedIds)
            val normalizedNow = nowMs.coerceAtLeast(0L)
            for (id in normalizedIds) {
                expectedSinceMs.putIfAbsent(id, normalizedNow)
            }
            expectedProducerIds = normalizedIds
            expectedProducerKindOrdinals = kindOrdinals
            expectedProducerPrimary = primaryFlags
            if (sameProducerMembership) {
                // Reordering the same IDs or changing their typed roles is a fresh topology
                // contract. Old buffers cannot satisfy the new layer-index/primary mapping.
                resetObservationWindow(nowMs)
            }
        }
        committedTopology = typedTopology
        if (wasTopologyPending) {
            // A physical Surface/BufferQueue can be rebuilt without changing its relay producer
            // ID. Once that lifecycle boundary has been published, pre-boundary heartbeats must
            // never satisfy readiness for the replacement producer.
            resetObservationWindow(nowMs)
        }
        return true
    }

    @Synchronized
    fun requestProducerControlRevision(
        candidate: Long,
        revision: Long,
        nowMs: Long,
    ): Boolean {
        if (
            candidate != generation ||
            activeEvidenceTerminal() ||
            runtimeFailureReason != null ||
            !topologyDeclared ||
            topologyPending ||
            !activated ||
            expectedProducerIds.isEmpty() ||
            revision <= 0L ||
            revision < producerControlRequestedRevision
        ) {
            return false
        }
        if (revision == producerControlRequestedRevision) return true
        producerControlRequestedRevision = revision
        producerControlRequestedMs = nowMs.coerceAtLeast(0L)
        producerControlAppliedRevisions.clear()
        return true
    }

    @Synchronized
    fun requestLayerGeometry(
        candidate: Long,
        revision: Long,
        profileOrdinal: Int,
        nowMs: Long,
    ): Boolean {
        if (
            candidate != generation ||
            activeEvidenceTerminal() ||
            revision <= 0L ||
            profileOrdinal !in geometryCoverageMasks.indices ||
            revision < geometryRequestedRevision
        ) {
            return false
        }
        if (
            revision == geometryRequestedRevision &&
            profileOrdinal != geometryRequestedProfileOrdinal
        ) {
            return false
        }
        geometryRequestedRevision = revision
        geometryRequestedProfileOrdinal = profileOrdinal
        geometryRequestedMs = nowMs.coerceAtLeast(0L)
        return true
    }

    @Synchronized
    fun acknowledgeLayerGeometry(
        candidate: Long,
        revision: Long,
        profileOrdinal: Int,
        coverageBit: Int,
    ): Boolean {
        if (
            candidate != generation ||
            activeEvidenceTerminal() ||
            revision <= 0L ||
            revision > geometryRequestedRevision ||
            revision < geometryAppliedRevision ||
            profileOrdinal !in geometryCoverageMasks.indices
        ) {
            return false
        }
        if (
            revision == geometryRequestedRevision &&
            profileOrdinal != geometryRequestedProfileOrdinal
        ) {
            return false
        }
        geometryAppliedRevision = revision
        geometryAppliedProfileOrdinal = profileOrdinal
        if (coverageBit > 0) {
            geometryCoverageMasks[profileOrdinal] =
                geometryCoverageMasks[profileOrdinal] or coverageBit
        }
        return true
    }

    @Synchronized
    fun recordEquivalentLayerGeometryCoverage(
        candidate: Long,
        profileOrdinal: Int,
        coverageBit: Int,
    ): Boolean {
        if (
            candidate != generation ||
            activeEvidenceTerminal() ||
            profileOrdinal !in geometryCoverageMasks.indices ||
            coverageBit <= 0
        ) {
            return false
        }
        geometryCoverageMasks[profileOrdinal] =
            geometryCoverageMasks[profileOrdinal] or coverageBit
        return true
    }

    @Synchronized
    fun markTopologyPending(candidate: Long): Boolean {
        if (candidate != generation) return false
        if (!topologyPending) advanceTopologyDiscontinuitySerial()
        topologyPending = true
        teardownCompleted = false
        producerControlAppliedRevisions.clear()
        resetDecoderObservation()
        return true
    }

    /**
     * Starts first-buffer observation only after the controller has seen the committed topology.
     * Buffers posted while Compose is still publishing the stage are deliberately discarded, so
     * renderer preparation time cannot satisfy the active phase's startup guard.
     */
    @Synchronized
    fun activate(candidate: Long, nowMs: Long): Boolean {
        if (
            candidate != generation ||
            !topologyDeclared ||
            topologyPending ||
            activeEvidenceTerminal()
        ) {
            return false
        }
        activated = true
        resetObservationWindow(nowMs)
        return true
    }

    /**
     * A transient process-wide producer drain pauses the phase clock. Once it clears, require a
     * fresh buffer from every committed producer instead of treating pre-drain heartbeats as live.
     */
    @Synchronized
    fun restartObservation(candidate: Long, nowMs: Long): Boolean {
        if (
            candidate != generation ||
            !activated ||
            !topologyDeclared ||
            topologyPending ||
            activeEvidenceTerminal() ||
            runtimeFailureReason != null
        ) {
            return false
        }
        advanceTopologyDiscontinuitySerial()
        resetObservationWindow(nowMs)
        return true
    }

    @Synchronized
    fun accept(
        candidate: Long,
        producerId: Long = 0L,
        primary: Boolean = false,
        nowMs: Long = 0L,
        controlRevision: Long = 0L,
    ): Boolean {
        if (
            candidate != generation ||
            !activated ||
            !topologyDeclared ||
            topologyPending ||
            runtimeFailureReason != null ||
            activeEvidenceTerminal()
        ) {
            return false
        }
        val producerIndex = expectedProducerIds.indexOfId(producerId)
        if (expectedProducerIds.isNotEmpty() && producerIndex < 0) {
            return false
        }
        if (
            producerIndex >= 0 &&
            expectedProducerKindOrdinals[producerIndex] != AppProducerKind.UNKNOWN.ordinal &&
            expectedProducerPrimary[producerIndex] != primary
        ) {
            return false
        }
        lastObservedMs.put(producerId, nowMs.coerceAtLeast(0L))
        if (
            producerControlRequestedRevision > 0L &&
            controlRevision == producerControlRequestedRevision
        ) {
            producerControlAppliedRevisions.put(producerId, controlRevision)
        }
        if (
            producerIndex >= 0 &&
            expectedProducerKindOrdinals[producerIndex] == AppProducerKind.VIDEO_DECODER.ordinal
        ) {
            if (decoderGenerationFrameCount < Long.MAX_VALUE) decoderGenerationFrameCount++
            if (decoderObservationFrameCount < Long.MAX_VALUE) decoderObservationFrameCount++
            decoderLastFrameMs = nowMs.coerceAtLeast(0L)
            decoderLastControlRevision = controlRevision.coerceAtLeast(0L)
        }
        return true
    }

    @Synchronized
    fun markTeardownFailure(candidate: Long): Boolean {
        if (candidate != generation) return false
        if (!teardownFailed) advanceTopologyDiscontinuitySerial()
        teardownFailed = true
        activated = false
        resetDecoderObservation()
        invalidateGeometryEvidence()
        return true
    }

    @Synchronized
    fun markTeardownComplete(candidate: Long): Boolean {
        if (candidate != generation) return false
        if (!teardownCompleted) advanceTopologyDiscontinuitySerial()
        teardownCompleted = true
        activated = false
        resetDecoderObservation()
        invalidateGeometryEvidence()
        return true
    }

    @Synchronized
    fun markRuntimeFailure(candidate: Long, reason: String): Boolean {
        if (candidate != generation) return false
        if (runtimeFailureReason == null) {
            runtimeFailureReason = reason
                .trim()
                .ifEmpty { "Producer runtime failure" }
                .take(MAX_RUNTIME_FAILURE_REASON_CHARS)
            resetDecoderObservation()
        }
        return true
    }

    @Synchronized
    fun readiness(candidate: Long, nowMs: Long): ProducerReadiness {
        if (candidate != generation) return ProducerReadiness()
        val expected = expectedProducerIds
        val normalizedNow = nowMs.coerceAtLeast(0L)
        val effectiveExpected = if (expected.isEmpty()) lastObservedMs.idsCopy() else expected
        var everObservedCount = 0
        var observedCount = 0
        var oldestFrameAgeMs = 0L
        var longestMissingMs = 0L
        var producerControlAppliedCount = 0
        for (id in effectiveExpected) {
            if (lastObservedMs.contains(id)) {
                everObservedCount++
                val ageMs = (
                    normalizedNow - lastObservedMs.valueOr(id, normalizedNow)
                    ).coerceAtLeast(0L)
                if (ageMs <= PRODUCER_FRESHNESS_WINDOW_MS) observedCount++
                if (ageMs > oldestFrameAgeMs) oldestFrameAgeMs = ageMs
            } else {
                val missingMs = (
                    normalizedNow - expectedSinceMs.valueOr(id, normalizedNow)
                    ).coerceAtLeast(0L)
                if (missingMs > longestMissingMs) longestMissingMs = missingMs
            }
            if (
                producerControlRequestedRevision > 0L &&
                producerControlAppliedRevisions.valueOr(id, 0L) ==
                producerControlRequestedRevision
            ) {
                producerControlAppliedCount++
            }
        }
        val evidenceTerminal = activeEvidenceTerminal()
        val decoderExpected =
            !evidenceTerminal &&
                runtimeFailureReason == null &&
                !topologyPending &&
                expectedProducerKindOrdinals.any {
                    it == AppProducerKind.VIDEO_DECODER.ordinal
                }
        val decoderLastFrameAgeMs = decoderLastFrameMs
            .takeIf { decoderExpected && it >= 0L }
            ?.let { (normalizedNow - it).coerceAtLeast(0L) }
        val ready = topologyDeclared &&
            !topologyPending &&
            activated &&
            expected.isNotEmpty() &&
            everObservedCount == expected.size &&
            !evidenceTerminal &&
            runtimeFailureReason == null
        val producerControlReady =
            producerControlRequestedRevision > 0L &&
                topologyDeclared &&
                !topologyPending &&
                activated &&
                expected.isNotEmpty() &&
                producerControlAppliedCount == expected.size &&
                !evidenceTerminal &&
                runtimeFailureReason == null
        val missingForMs = if (ready) {
            0L
        } else if (!topologyDeclared || !activated || effectiveExpected.isEmpty()) {
            (normalizedNow - generationStartedMs).coerceAtLeast(0L)
        } else {
            longestMissingMs
        }
        return ProducerReadiness(
            expectedCount = expected.size,
            observedCount = if (activated) observedCount else 0,
            everObservedCount = if (activated) everObservedCount else 0,
            ready = ready,
            unreadyForMs = missingForMs,
            oldestFrameAgeMs = oldestFrameAgeMs,
            topologyPublished = topologyDeclared,
            topologyPending = topologyPending,
            topologyPublishedAtMs = topologyPublishedMs.takeIf { it >= 0L },
            topologyRevision = topologyRevision,
            topologyDiscontinuitySerial = topologyDiscontinuitySerial,
            geometryRequestedRevision = geometryRequestedRevision,
            geometryAppliedRevision = geometryAppliedRevision,
            geometryRequestedProfileOrdinal = geometryRequestedProfileOrdinal,
            geometryAppliedProfileOrdinal = geometryAppliedProfileOrdinal,
            geometryCoverageMask = if (evidenceTerminal) {
                0
            } else {
                geometryRequestedProfileOrdinal
                    .takeIf { it in geometryCoverageMasks.indices }
                    ?.let { geometryCoverageMasks[it] }
                    ?: 0
            },
            geometryReady =
                !evidenceTerminal &&
                    geometryRequestedRevision > 0L &&
                    geometryAppliedRevision == geometryRequestedRevision &&
                    geometryAppliedProfileOrdinal == geometryRequestedProfileOrdinal,
            geometryPendingForMs = if (
                !evidenceTerminal &&
                geometryRequestedRevision > 0L &&
                geometryAppliedRevision != geometryRequestedRevision
            ) {
                (normalizedNow - geometryRequestedMs.coerceAtLeast(0L)).coerceAtLeast(0L)
            } else {
                0L
            },
            producerControlRequestedRevision = producerControlRequestedRevision,
            producerControlAppliedRevision =
                producerControlRequestedRevision.takeIf { producerControlReady } ?: 0L,
            producerControlAppliedCount = producerControlAppliedCount,
            producerControlReady = producerControlReady,
            producerControlPendingForMs = if (
                producerControlRequestedRevision > 0L &&
                !producerControlReady
            ) {
                (normalizedNow - producerControlRequestedMs.coerceAtLeast(0L))
                    .coerceAtLeast(0L)
            } else {
                0L
            },
            topologyMissed = topologyMissed,
            teardownFailed = teardownFailed,
            teardownCompleted = teardownCompleted,
            runtimeFailureReason = runtimeFailureReason,
            committedTopology = committedTopology.takeIf {
                topologyDeclared &&
                    !topologyPending &&
                    !evidenceTerminal &&
                    runtimeFailureReason == null
            },
            decoderExpected = decoderExpected,
            decoderGenerationFrameCount = decoderGenerationFrameCount,
            decoderObservationFrameCount = decoderObservationFrameCount,
            decoderLastFrameAgeMs = decoderLastFrameAgeMs,
            decoderLastControlRevision = decoderLastControlRevision,
            decoderControlReady =
                decoderExpected &&
                    decoderLastFrameAgeMs != null &&
                    decoderLastFrameAgeMs <= PRODUCER_FRESHNESS_WINDOW_MS &&
                    (
                        producerControlRequestedRevision == 0L ||
                            decoderLastControlRevision == producerControlRequestedRevision
                        ),
        )
    }

    private fun advanceTopologyDiscontinuitySerial() {
        topologyDiscontinuitySerial =
            if (topologyDiscontinuitySerial == Long.MAX_VALUE) {
                1L
            } else {
                topologyDiscontinuitySerial + 1L
            }
    }

    private fun resetObservationWindow(nowMs: Long) {
        val normalizedNow = nowMs.coerceAtLeast(0L)
        lastObservedMs.clear()
        expectedSinceMs.clear()
        for (id in expectedProducerIds) {
            expectedSinceMs.put(id, normalizedNow)
        }
        producerControlAppliedRevisions.clear()
        generationStartedMs = normalizedNow
        resetDecoderObservation()
    }

    private fun resetDecoderObservation() {
        decoderObservationFrameCount = 0L
        decoderLastFrameMs = -1L
        decoderLastControlRevision = 0L
    }

    private fun activeEvidenceTerminal(): Boolean =
        topologyMissed || teardownFailed || teardownCompleted

    private fun invalidateGeometryEvidence() {
        geometryRequestedRevision = 0L
        geometryAppliedRevision = 0L
        geometryRequestedProfileOrdinal = -1
        geometryAppliedProfileOrdinal = -1
        geometryCoverageMasks.fill(0)
        geometryRequestedMs = -1L
        producerControlAppliedRevisions.clear()
    }

    private companion object {
        const val PRODUCER_FRESHNESS_WINDOW_MS = 3_000L
        const val MAX_RUNTIME_FAILURE_REASON_CHARS = 240
        const val MAX_LAYER_SIZE_PROFILE_COUNT = 16
        const val MAX_TRACKED_PHYSICAL_PRODUCERS = 20
    }
}

/**
 * Tiny primitive map for the bounded physical-producer set. The hot frame callback updates a
 * timestamp without boxing Long keys/values, so renderer telemetry does not add GC/DRAM noise to
 * the experiment it is measuring.
 */
internal fun interface LongArrayExpansionAllocator {
    fun copyOf(source: LongArray, newSize: Int): LongArray
}

private object DefaultLongArrayExpansionAllocator : LongArrayExpansionAllocator {
    override fun copyOf(source: LongArray, newSize: Int): LongArray = source.copyOf(newSize)
}

internal class LongTimestampMap(
    initialCapacity: Int = 8,
    private val expansionAllocator: LongArrayExpansionAllocator =
        DefaultLongArrayExpansionAllocator,
) {
    private var ids = LongArray(initialCapacity.coerceAtLeast(1))
    private var timestamps = LongArray(ids.size)
    private var size = 0

    fun clear() {
        size = 0
    }

    fun isEmpty(): Boolean = size == 0

    fun contains(id: Long): Boolean = indexOf(id) >= 0

    fun valueOr(id: Long, fallback: Long): Long {
        val index = indexOf(id)
        return if (index >= 0) timestamps[index] else fallback
    }

    fun put(id: Long, timestamp: Long) {
        val index = indexOf(id)
        if (index >= 0) {
            timestamps[index] = timestamp
            return
        }
        ensureCapacity(size + 1)
        ids[size] = id
        timestamps[size] = timestamp
        size++
    }

    fun putIfAbsent(id: Long, timestamp: Long) {
        if (!contains(id)) put(id, timestamp)
    }

    fun retainAll(retainedIds: LongArray) {
        var writeIndex = 0
        for (readIndex in 0 until size) {
            if (!retainedIds.containsId(ids[readIndex])) continue
            if (writeIndex != readIndex) {
                ids[writeIndex] = ids[readIndex]
                timestamps[writeIndex] = timestamps[readIndex]
            }
            writeIndex++
        }
        size = writeIndex
    }

    fun idsCopy(): LongArray = ids.copyOf(size)

    private fun indexOf(id: Long): Int {
        for (index in 0 until size) {
            if (ids[index] == id) return index
        }
        return -1
    }

    private fun ensureCapacity(required: Int) {
        if (required <= ids.size) return
        val newSize = (ids.size * 2).coerceAtLeast(required)
        // Both copies are fallible under the deliberate memory-stress workload. Do not expose the
        // first expansion until the timestamp copy also succeeds; otherwise a second-allocation
        // OOME leaves the two hot-path arrays with different capacities.
        val expandedIds = expansionAllocator.copyOf(ids, newSize)
        val expandedTimestamps = expansionAllocator.copyOf(timestamps, newSize)
        require(expandedIds.size >= newSize && expandedTimestamps.size >= newSize) {
            "LongTimestampMap allocator returned an undersized array"
        }
        ids = expandedIds
        timestamps = expandedTimestamps
    }
}

private fun LongArray.containsId(id: Long): Boolean {
    for (candidate in this) {
        if (candidate == id) return true
    }
    return false
}

private fun LongArray.indexOfId(id: Long): Int {
    for (index in indices) {
        if (this[index] == id) return index
    }
    return -1
}

data class ProducerReadiness(
    val expectedCount: Int = 0,
    val observedCount: Int = 0,
    val everObservedCount: Int = 0,
    val ready: Boolean = false,
    val unreadyForMs: Long = 0L,
    val oldestFrameAgeMs: Long = 0L,
    val topologyPublished: Boolean = false,
    val topologyPending: Boolean = false,
    val topologyPublishedAtMs: Long? = null,
    val topologyRevision: Long = 0L,
    val topologyDiscontinuitySerial: Long = 0L,
    val geometryRequestedRevision: Long = 0L,
    val geometryAppliedRevision: Long = 0L,
    val geometryRequestedProfileOrdinal: Int = -1,
    val geometryAppliedProfileOrdinal: Int = -1,
    val geometryCoverageMask: Int = 0,
    val geometryReady: Boolean = false,
    val geometryPendingForMs: Long = 0L,
    val producerControlRequestedRevision: Long = 0L,
    val producerControlAppliedRevision: Long = 0L,
    val producerControlAppliedCount: Int = 0,
    val producerControlReady: Boolean = false,
    val producerControlPendingForMs: Long = 0L,
    val topologyMissed: Boolean = false,
    val teardownFailed: Boolean = false,
    val teardownCompleted: Boolean = false,
    val runtimeFailureReason: String? = null,
    val committedTopology: AppProducerTopology? = null,
    val decoderExpected: Boolean = false,
    val decoderGenerationFrameCount: Long = 0L,
    val decoderObservationFrameCount: Long = 0L,
    val decoderLastFrameAgeMs: Long? = null,
    val decoderLastControlRevision: Long = 0L,
    val decoderControlReady: Boolean = false,
)
