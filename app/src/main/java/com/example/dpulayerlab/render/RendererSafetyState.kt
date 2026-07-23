package com.example.dpulayerlab.render

import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide lease registry for producers that exceeded the bounded UI teardown deadline.
 *
 * A background watcher removes each lease only after its producer thread has actually terminated.
 * New plans are rejected while any lease remains, preventing codec/EGL/Canvas overlap across
 * queue items or Activity recreation without ever blocking the main thread indefinitely.
 */
object RendererSafetyState {
    private val unconfirmedThreads = ConcurrentHashMap.newKeySet<Thread>()

    fun trackUnconfirmed(thread: Thread) {
        if (!thread.isAlive || !unconfirmedThreads.add(thread)) return
        Thread(
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
        ).apply {
            isDaemon = true
            start()
        }
    }

    fun hasUnconfirmedTeardown(): Boolean {
        unconfirmedThreads.removeIf { !it.isAlive }
        return unconfirmedThreads.isNotEmpty()
    }
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
