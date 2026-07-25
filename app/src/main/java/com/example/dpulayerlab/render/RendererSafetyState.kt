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
    private val lifecycleStageOwnerLock = Any()
    private val stickyCleanupFailure = AtomicReference<String?>()
    private val nextLifecycleOwner = AtomicLong(0L)

    fun createLifecycleStageOwner(): Long =
        safetyStateOperation(LIFECYCLE_OWNER_FAILURE) {
            nextLifecycleOwner.updateAndGet { current ->
                if (current == Long.MAX_VALUE) {
                    throw IllegalStateException(LIFECYCLE_OWNER_EXHAUSTED)
                }
                current + 1L
            }
        }

    fun markLifecycleStageRemovalPending(owner: Long) {
        if (owner > 0L) {
            safetyStateOperation(LIFECYCLE_PENDING_FAILURE) {
                synchronized(lifecycleStageOwnerLock) {
                    if (
                        !pendingLifecycleStageOwners.contains(owner) &&
                        pendingLifecycleStageOwners.size >=
                        MAX_PENDING_LIFECYCLE_STAGE_OWNERS
                    ) {
                        throw IllegalStateException(LIFECYCLE_PENDING_CAPACITY_EXCEEDED)
                    }
                    pendingLifecycleStageOwners.add(owner)
                }
            }
        }
    }

    fun markLifecycleStageRemoved(owner: Long) {
        if (owner > 0L) {
            safetyStateOperation(LIFECYCLE_COMPLETION_FAILURE) {
                synchronized(lifecycleStageOwnerLock) {
                    pendingLifecycleStageOwners.remove(owner)
                }
            }
        }
    }

    fun trackUnconfirmed(thread: Thread) {
        val registered = safetyStateOperation(PRODUCER_REGISTRATION_FAILURE) {
            thread.isAlive && unconfirmedThreads.add(thread)
        }
        if (!registered) return
        val watcher = try {
            Thread(
                { awaitTrackedProducerTermination(thread) },
                "DpuLab-ProducerCleanupWatch",
            ).also { it.isDaemon = true }
        } catch (error: Throwable) {
            // The live producer remains registered. Preserve that fail-closed lease and make the
            // missing watcher sticky before propagating a fatal VM failure.
            throw recordStickyFallback(error, PRODUCER_WATCHER_FAILURE)
        }
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
        if (stickyCleanupFailure.get() != null) return
        try {
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
        } catch (error: Throwable) {
            // Formatting is diagnostic work and may itself fail under allocation pressure. The
            // preallocated fallback keeps the process blocked, then the original VM fatal escapes.
            throw recordStickyFallback(error, CLEANUP_REPORT_FAILURE)
        }
    }

    fun hasUnconfirmedTeardown(): Boolean =
        safetyStateOperation(LEASE_OBSERVATION_FAILURE) {
            if (unconfirmedThreads.isEmpty()) {
                stickyCleanupFailure.get() != null ||
                    pendingLifecycleStageOwners.isNotEmpty() ||
                    // Close the registration race without paying removeIf/iterator cost on the
                    // overwhelmingly common empty-set path.
                    unconfirmedThreads.isNotEmpty()
            } else {
                unconfirmedThreads.removeIf { !it.isAlive }
                stickyCleanupFailure.get() != null ||
                    pendingLifecycleStageOwners.isNotEmpty() ||
                    unconfirmedThreads.isNotEmpty()
            }
        }

    fun cleanupFailureReason(): String? = stickyCleanupFailure.get()

    internal fun resetStickyCleanupFailureForTests() {
        check(unconfirmedThreads.none { it.isAlive })
        unconfirmedThreads.clear()
        synchronized(lifecycleStageOwnerLock) {
            pendingLifecycleStageOwners.clear()
            nextLifecycleOwner.set(0L)
        }
        stickyCleanupFailure.set(null)
    }

    internal fun setNextLifecycleStageOwnerForTests(owner: Long) {
        require(owner >= 0L)
        check(unconfirmedThreads.none { it.isAlive })
        synchronized(lifecycleStageOwnerLock) {
            check(pendingLifecycleStageOwners.isEmpty())
            nextLifecycleOwner.set(owner)
        }
    }

    internal fun throwUnexpectedWatcherFailure(error: Throwable): Nothing {
        throw recordStickyFallback(error, PRODUCER_WATCHER_RUNTIME_FAILURE)
    }

    private fun awaitTrackedProducerTermination(thread: Thread) {
        var terminalFailure: Throwable? = null
        try {
            thread.join()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Throwable) {
            terminalFailure = error
        }

        try {
            if (!thread.isAlive) {
                safetyStateOperation(PRODUCER_COMPLETION_FAILURE) {
                    unconfirmedThreads.remove(thread)
                }
            }
        } catch (completionError: Throwable) {
            terminalFailure = terminalFailure?.let { first ->
                prioritizeRendererFailure(first, completionError)
            } ?: completionError
        }
        val unexpectedFailure = terminalFailure
        if (unexpectedFailure != null) {
            throwUnexpectedWatcherFailure(unexpectedFailure)
        }
    }

    private inline fun <Result> safetyStateOperation(
        fallbackReason: String,
        operation: () -> Result,
    ): Result = try {
        operation()
    } catch (error: Throwable) {
        throw recordStickyFallback(error, fallbackReason)
    }

    private fun recordStickyFallback(
        error: Throwable,
        fallbackReason: String,
    ): Throwable {
        var terminalFailure = error
        try {
            stickyCleanupFailure.compareAndSet(null, fallbackReason)
        } catch (recordError: Throwable) {
            terminalFailure = prioritizeRendererFailure(error, recordError)
        }
        return terminalFailure
    }

    private const val MAX_CLEANUP_FAILURE_CHARS = 240
    internal const val MAX_PENDING_LIFECYCLE_STAGE_OWNERS = 64
    private const val LIFECYCLE_OWNER_FAILURE = "renderer lifecycle owner allocation failed"
    private const val LIFECYCLE_OWNER_EXHAUSTED = "renderer lifecycle owner space exhausted"
    private const val LIFECYCLE_PENDING_FAILURE = "renderer lifecycle removal registration failed"
    private const val LIFECYCLE_PENDING_CAPACITY_EXCEEDED =
        "renderer lifecycle removal registration capacity exceeded"
    private const val LIFECYCLE_COMPLETION_FAILURE = "renderer lifecycle removal completion failed"
    private const val PRODUCER_REGISTRATION_FAILURE = "renderer producer lease registration failed"
    private const val PRODUCER_WATCHER_FAILURE = "renderer producer cleanup watcher creation failed"
    private const val PRODUCER_WATCHER_RUNTIME_FAILURE =
        "renderer producer cleanup watcher failed"
    private const val PRODUCER_COMPLETION_FAILURE = "renderer producer lease completion failed"
    private const val CLEANUP_REPORT_FAILURE = "renderer cleanup failure reporting failed"
    private const val LEASE_OBSERVATION_FAILURE = "renderer producer lease observation failed"
}

/**
 * Starts a renderer-owned thread and converts ordinary start failures into `false`. Callers must
 * roll back their published session in [onFailure]. `false` is returned only after that callback
 * completes; any callback failure is propagated after VM-fatal precedence and suppression are
 * resolved.
 */
internal fun startRendererThread(
    thread: Thread,
    onFailure: (Throwable) -> Unit,
): Boolean = try {
    thread.start()
    true
} catch (error: Throwable) {
    var terminalFailure = error
    try {
        onFailure(error)
    } catch (rollbackError: Throwable) {
        terminalFailure = prioritizeRendererFailure(error, rollbackError)
        throw terminalFailure
    }
    if (terminalFailure.isRendererVmFatal()) throw terminalFailure
    false
}

/**
 * Preallocates [resourceCapacity] plus one overflow-ownership slot before [build] starts, then
 * registers every renderer resource before the caller performs the next fallible install step.
 * If construction, View attachment, or runtime-control initialization throws, the complete owned
 * prefix is handed to one rollback callback before the original throwable is propagated. This
 * includes [OutOfMemoryError]. A VM-fatal rollback failure supersedes an earlier ordinary build
 * failure only after the owned prefix has been made explicit. The first resource beyond the
 * declared capacity is included in that same rollback prefix before the capacity error is raised.
 */
internal fun <Resource, Result> buildRendererTransaction(
    resourceCapacity: Int,
    build: (register: (Resource) -> Unit) -> Result,
    rollback: (List<Resource>) -> Unit,
): Result {
    require(resourceCapacity in 0..MAX_RENDERER_TRANSACTION_RESOURCE_CAPACITY) {
        "Renderer transaction resourceCapacity must be in " +
            "0..$MAX_RENDERER_TRANSACTION_RESOURCE_CAPACITY"
    }
    // Reserve one additional slot before build starts. A producer is already constructed when it
    // crosses the register callback, so the first over-capacity candidate must also become rollback
    // owned before the deterministic capacity failure is raised.
    val owned = ArrayList<Resource>(resourceCapacity + 1)
    return try {
        val result = build { resource ->
            check(owned.size <= resourceCapacity) {
                "Renderer transaction registration continued after capacity failure"
            }
            owned += resource
            check(owned.size <= resourceCapacity) {
                "Renderer transaction resource capacity $resourceCapacity exceeded"
            }
        }
        check(owned.size <= resourceCapacity) {
            "Renderer transaction resource capacity $resourceCapacity exceeded"
        }
        result
    } catch (error: Throwable) {
        var terminalFailure = error
        try {
            rollback(owned)
        } catch (rollbackError: Throwable) {
            terminalFailure = prioritizeRendererFailure(error, rollbackError)
        }
        throw terminalFailure
    }
}

internal const val MAX_RENDERER_TRANSACTION_RESOURCE_CAPACITY = 64

/**
 * VM-fatal failures always dominate ordinary cleanup failures. Among failures of the same class,
 * first-wins remains deterministic. Suppression is best effort because even Throwable bookkeeping
 * can allocate while an [OutOfMemoryError] is active.
 */
private fun prioritizeRendererFailure(
    first: Throwable,
    second: Throwable,
): Throwable {
    if (first === second) return first
    val terminal = if (!first.isRendererVmFatal() && second.isRendererVmFatal()) second else first
    val evidence = if (terminal === first) second else first
    return attachSuppressedBestEffort(terminal, evidence)
}

private fun Throwable.isRendererVmFatal(): Boolean =
    this is ThreadDeath || this is VirtualMachineError

private fun attachSuppressedBestEffort(
    terminal: Throwable,
    evidence: Throwable,
): Throwable {
    try {
        terminal.addSuppressed(evidence)
        return terminal
    } catch (attachmentError: Throwable) {
        if (terminal.isRendererVmFatal() || !attachmentError.isRendererVmFatal()) {
            return terminal
        }
        // A new fatal allocation failure while attaching to an ordinary error becomes terminal.
        // Preserve both earlier failures where the failing VM still permits it.
        attachWithoutEscalation(attachmentError, terminal)
        attachWithoutEscalation(attachmentError, evidence)
        return attachmentError
    }
}

private fun attachWithoutEscalation(
    terminal: Throwable,
    evidence: Throwable,
) {
    if (terminal === evidence) return
    try {
        terminal.addSuppressed(evidence)
    } catch (_: Throwable) {
        // The chosen fatal identity must not be replaced by diagnostic bookkeeping.
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
