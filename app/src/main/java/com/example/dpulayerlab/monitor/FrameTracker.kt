package com.example.dpulayerlab.monitor

import android.os.SystemClock
import android.view.Choreographer
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
    ) {
        if (
            !producerGeneration.accept(
                candidate = generation,
                producerId = producerId,
                nowMs = SystemClock.elapsedRealtime(),
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
 * Small synchronized gate used only at primary-buffer callbacks (at most the bounded producer
 * rate). Keeping generation and count under one lock avoids losing the first frame in a
 * begin-vs-callback race.
 */
internal class ProducerGenerationGate {
    private var generation = 0L
    private var expectedProducerIds = LongArray(0)
    private var topologyDeclared = false
    private var topologyPending = false
    private var activated = false
    private var topologyPublishedMs = -1L
    private var topologyRevision = 0L
    private val expectedSinceMs = LongTimestampMap()
    private val lastObservedMs = LongTimestampMap()
    private var generationStartedMs = 0L
    private var topologyMissed = false
    private var teardownFailed = false
    private var teardownCompleted = false
    private var runtimeFailureReason: String? = null

    @Synchronized
    fun begin(nowMs: Long = 0L): Long {
        generation = if (generation == Long.MAX_VALUE) 1L else generation + 1L
        expectedProducerIds = LongArray(0)
        topologyDeclared = false
        topologyPending = false
        activated = false
        topologyPublishedMs = -1L
        topologyRevision = 0L
        expectedSinceMs.clear()
        lastObservedMs.clear()
        generationStartedMs = nowMs.coerceAtLeast(0L)
        topologyMissed = false
        teardownFailed = false
        teardownCompleted = false
        runtimeFailureReason = null
        return generation
    }

    @Synchronized
    fun expect(
        candidate: Long,
        producerIds: Set<Long>,
        nowMs: Long,
    ): Boolean {
        if (candidate != generation || producerIds.isEmpty()) return false
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
        if (!topologyDeclared) {
            topologyPublishedMs = nowMs.coerceAtLeast(0L)
        }
        topologyDeclared = true
        topologyPending = false
        // A stage can detach and later rebuild in the same controller generation (for example a
        // lifecycle stop/start race). Any newly published producer topology revokes the earlier
        // stage-removal acknowledgement; only a later terminal removal may complete the barrier.
        teardownCompleted = false
        if (!normalized.contentEquals(expectedProducerIds)) {
            topologyRevision =
                if (topologyRevision == Long.MAX_VALUE) 1L else topologyRevision + 1L
            for (id in expectedProducerIds) {
                if (!normalized.containsId(id) && !lastObservedMs.contains(id)) {
                    topologyMissed = true
                    break
                }
            }
            expectedSinceMs.retainAll(normalized)
            lastObservedMs.retainAll(normalized)
            val normalizedNow = nowMs.coerceAtLeast(0L)
            for (id in normalized) {
                expectedSinceMs.putIfAbsent(id, normalizedNow)
            }
            expectedProducerIds = normalized
        }
        return true
    }

    @Synchronized
    fun markTopologyPending(candidate: Long): Boolean {
        if (candidate != generation) return false
        topologyPending = true
        teardownCompleted = false
        return true
    }

    /**
     * Starts first-buffer observation only after the controller has seen the committed topology.
     * Buffers posted while Compose is still publishing the stage are deliberately discarded, so
     * renderer preparation time cannot satisfy the active phase's startup guard.
     */
    @Synchronized
    fun activate(candidate: Long, nowMs: Long): Boolean {
        if (candidate != generation || !topologyDeclared || topologyPending) return false
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
        if (candidate != generation || !activated || !topologyDeclared || topologyPending) {
            return false
        }
        resetObservationWindow(nowMs)
        return true
    }

    @Synchronized
    fun accept(
        candidate: Long,
        producerId: Long = 0L,
        nowMs: Long = 0L,
    ): Boolean {
        if (candidate != generation || !activated || !topologyDeclared || topologyPending) {
            return false
        }
        if (expectedProducerIds.isNotEmpty() && !expectedProducerIds.containsId(producerId)) {
            return false
        }
        lastObservedMs.put(producerId, nowMs.coerceAtLeast(0L))
        return true
    }

    @Synchronized
    fun markTeardownFailure(candidate: Long): Boolean {
        if (candidate != generation) return false
        teardownFailed = true
        return true
    }

    @Synchronized
    fun markTeardownComplete(candidate: Long): Boolean {
        if (candidate != generation) return false
        teardownCompleted = true
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
        }
        val ready = topologyDeclared &&
            !topologyPending &&
            activated &&
            expected.isNotEmpty() &&
            everObservedCount == expected.size &&
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
            observedCount = observedCount,
            everObservedCount = everObservedCount,
            ready = ready,
            unreadyForMs = missingForMs,
            oldestFrameAgeMs = oldestFrameAgeMs,
            topologyPublished = topologyDeclared,
            topologyPending = topologyPending,
            topologyPublishedAtMs = topologyPublishedMs.takeIf { it >= 0L },
            topologyRevision = topologyRevision,
            topologyMissed = topologyMissed,
            teardownFailed = teardownFailed,
            teardownCompleted = teardownCompleted,
            runtimeFailureReason = runtimeFailureReason,
        )
    }

    private fun resetObservationWindow(nowMs: Long) {
        val normalizedNow = nowMs.coerceAtLeast(0L)
        lastObservedMs.clear()
        expectedSinceMs.clear()
        for (id in expectedProducerIds) {
            expectedSinceMs.put(id, normalizedNow)
        }
        generationStartedMs = normalizedNow
    }

    private companion object {
        const val PRODUCER_FRESHNESS_WINDOW_MS = 3_000L
        const val MAX_RUNTIME_FAILURE_REASON_CHARS = 240
    }
}

/**
 * Tiny primitive map for the bounded physical-producer set. The hot frame callback updates a
 * timestamp without boxing Long keys/values, so renderer telemetry does not add GC/DRAM noise to
 * the experiment it is measuring.
 */
private class LongTimestampMap(initialCapacity: Int = 8) {
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
        ids = ids.copyOf(newSize)
        timestamps = timestamps.copyOf(newSize)
    }
}

private fun LongArray.containsId(id: Long): Boolean {
    for (candidate in this) {
        if (candidate == id) return true
    }
    return false
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
    val topologyMissed: Boolean = false,
    val teardownFailed: Boolean = false,
    val teardownCompleted: Boolean = false,
    val runtimeFailureReason: String? = null,
)
