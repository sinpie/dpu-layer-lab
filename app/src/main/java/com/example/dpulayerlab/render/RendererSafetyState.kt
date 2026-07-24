package com.example.dpulayerlab.render

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-wide lease registry for producers that exceeded the bounded UI teardown deadline.
 *
 * A background watcher removes each lease only after its producer thread has actually terminated.
 * New plans are rejected while any lease remains, preventing codec/EGL/Canvas overlap across
 * queue items or Activity recreation without ever blocking the main thread indefinitely.
 */
object RendererSafetyState {
    private val unconfirmedThreads = ConcurrentHashMap.newKeySet<Thread>()
    private val pendingLifecycleStageOwners = ConcurrentHashMap.newKeySet<Long>()
    private val stickyCleanupFailure = AtomicReference<String?>()
    private val nextLifecycleOwner = AtomicLong(0L)

    fun createLifecycleStageOwner(): Long =
        nextLifecycleOwner.updateAndGet { current ->
            if (current == Long.MAX_VALUE) 1L else current + 1L
        }

    fun markLifecycleStageRemovalPending(owner: Long) {
        if (owner > 0L) pendingLifecycleStageOwners.add(owner)
    }

    fun markLifecycleStageRemoved(owner: Long) {
        if (owner > 0L) pendingLifecycleStageOwners.remove(owner)
    }

    fun trackUnconfirmed(thread: Thread) {
        if (!thread.isAlive || !unconfirmedThreads.add(thread)) return
        val watcher = Thread(
            {
                try {
                    thread.join()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } finally {
                    if (!thread.isAlive) unconfirmedThreads.remove(thread)
                }
            },
            "DpuLab-ProducerCleanupWatch",
        )
        watcher.isDaemon = true
        val watcherStarted = startRendererThread(watcher) { error ->
            markCleanupFailure(
                component = "producer cleanup watcher",
                detail = error.javaClass.simpleName,
            )
        }
        if (!watcherStarted) {
            // Keep the producer in the set. hasUnconfirmedTeardown() still removes it after a
            // later poll observes termination, while the sticky failure blocks unsafe reuse.
            return
        }
    }

    fun markCleanupFailure(component: String, detail: String? = null) {
        val boundedComponent = component
            .trim()
            .ifEmpty { "renderer cleanup" }
            .take(MAX_CLEANUP_FAILURE_CHARS)
        val boundedDetail = detail
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.take(MAX_CLEANUP_FAILURE_CHARS)
        stickyCleanupFailure.compareAndSet(
            null,
            listOfNotNull(boundedComponent, boundedDetail)
                .joinToString(": ")
                .take(MAX_CLEANUP_FAILURE_CHARS),
        )
    }

    fun hasUnconfirmedTeardown(): Boolean {
        unconfirmedThreads.removeIf { !it.isAlive }
        return stickyCleanupFailure.get() != null ||
            pendingLifecycleStageOwners.isNotEmpty() ||
            unconfirmedThreads.isNotEmpty()
    }

    fun cleanupFailureReason(): String? = stickyCleanupFailure.get()

    internal fun resetStickyCleanupFailureForTests() {
        check(unconfirmedThreads.none { it.isAlive })
        unconfirmedThreads.clear()
        pendingLifecycleStageOwners.clear()
        stickyCleanupFailure.set(null)
    }

    private const val MAX_CLEANUP_FAILURE_CHARS = 240
}

/**
 * Starts a renderer-owned thread without allowing allocation pressure or a reused Thread object
 * to crash the main thread. Callers must roll back their published session in [onFailure].
 */
internal inline fun startRendererThread(
    thread: Thread,
    onFailure: (Throwable) -> Unit,
): Boolean = try {
    thread.start()
    true
} catch (error: Throwable) {
    if (error is ThreadDeath) throw error
    onFailure(error)
    false
}

internal enum class ProducerRecoveryDecision {
    CANCEL,
    START,
    WAIT,
    FAIL,
}

/**
 * Shared boundary policy for Canvas, codec, TextureView, and EGL lifecycle recreation.
 * Lease-clear wins at the exact deadline; only a still-active lease past the bound fails.
 */
internal fun producerRecoveryDecision(
    targetValid: Boolean,
    processLeaseActive: Boolean,
    elapsedMs: Long,
    timeoutMs: Long,
): ProducerRecoveryDecision = when {
    !targetValid -> ProducerRecoveryDecision.CANCEL
    !processLeaseActive -> ProducerRecoveryDecision.START
    elapsedMs >= timeoutMs.coerceAtLeast(0L) -> ProducerRecoveryDecision.FAIL
    else -> ProducerRecoveryDecision.WAIT
}
