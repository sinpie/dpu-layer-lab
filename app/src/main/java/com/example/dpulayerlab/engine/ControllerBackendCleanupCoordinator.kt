package com.example.dpulayerlab.engine

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Process state for the Activity-independent backend owner.
 *
 * [FAILED] is deliberately sticky. A new Activity must not create another backend after an old
 * worker, Binder lane, or native resource missed its cleanup contract.
 */
internal enum class ControllerBackendCleanupPhase {
    IDLE,
    ACTIVE,
    CLEANING,
    FAILED,
}

@JvmInline
internal value class ControllerBackendOwnerToken internal constructor(
    val value: Long,
)

internal data class ControllerBackendCleanupSnapshot(
    val phase: ControllerBackendCleanupPhase,
    val ownerToken: ControllerBackendOwnerToken? = null,
    val failureReason: String? = null,
)

internal enum class ControllerBackendCleanupStart {
    STARTED,
    DUPLICATE,
    STALE_OWNER,
    FAILED_STICKY,
    SCHEDULING_FAILED,
}

internal data class ControllerBackendCleanupOutcome(
    val confirmed: Boolean,
    val detail: String = "",
) {
    companion object {
        fun confirmed(detail: String = "") =
            ControllerBackendCleanupOutcome(confirmed = true, detail = detail)

        fun failed(detail: String) =
            ControllerBackendCleanupOutcome(confirmed = false, detail = detail)
    }
}

/**
 * Activity-free signal which can be completed from a coroutine/Job completion callback without
 * handing that Job (or its captured Activity) to the process coordinator.
 */
internal class ControllerBackendCompletionBarrier(
    initiallyComplete: Boolean = false,
) {
    private val completion = CountDownLatch(if (initiallyComplete) 0 else 1)

    fun complete() {
        completion.countDown()
    }

    fun isComplete(): Boolean = completion.count == 0L

    internal fun awaitUntil(deadlineNanos: Long): Boolean {
        if (isComplete()) return true
        val remainingNanos = deadlineNanos - System.nanoTime()
        if (remainingNanos <= 0L) return false
        return completion.await(remainingNanos, TimeUnit.NANOSECONDS)
    }
}

/**
 * The operation receives the remaining coordinator budget. Implementations must keep their own
 * Binder joins and worker waits within it and must contain backend resources only; capturing an
 * Activity, Controller, Window, or coroutine Job would violate this ownership contract.
 */
internal fun interface ControllerBackendCleanupOperation {
    fun cleanup(remainingBudgetMs: Long): ControllerBackendCleanupOutcome
}

/**
 * Serializes process backend ownership without retaining Activity/Controller/Job objects.
 *
 * Only one owner exists, and only one cleanup task can be active or handed to the one-slot lane.
 * A watchdog changes the public state to sticky [ControllerBackendCleanupPhase.FAILED] at the
 * deadline even if broken native code ignores interruption. The cleanup lane itself is never
 * reused after that failure because new ownership remains blocked.
 */
internal class ControllerBackendCleanupCoordinator(
    cleanupTimeoutMs: Long = DEFAULT_CLEANUP_TIMEOUT_MS,
) : AutoCloseable {
    private val cleanupTimeoutMs =
        cleanupTimeoutMs.coerceIn(MIN_CLEANUP_TIMEOUT_MS, MAX_CLEANUP_TIMEOUT_MS)
    private val lock = Any()
    private val closed = AtomicBoolean(false)
    private var nextOwnerToken = 0L
    private var state = ControllerBackendCleanupSnapshot(
        phase = ControllerBackendCleanupPhase.IDLE,
    )
    private var activeTask: CleanupTask? = null
    private var activeWatchdog: ScheduledFuture<*>? = null

    private val cleanupExecutor = ThreadPoolExecutor(
        1,
        1,
        CLEANUP_THREAD_KEEP_ALIVE_MS,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(1),
        { runnable ->
            Thread(runnable, CLEANUP_THREAD_NAME).apply { isDaemon = true }
        },
        ThreadPoolExecutor.AbortPolicy(),
    ).apply {
        allowCoreThreadTimeOut(true)
    }

    private val watchdogExecutor = ScheduledThreadPoolExecutor(
        1,
        { runnable ->
            Thread(runnable, WATCHDOG_THREAD_NAME).apply { isDaemon = true }
        },
        ThreadPoolExecutor.AbortPolicy(),
    ).apply {
        removeOnCancelPolicy = true
        executeExistingDelayedTasksAfterShutdownPolicy = false
        continueExistingPeriodicTasksAfterShutdownPolicy = false
    }

    fun tryAcquireOwner(): ControllerBackendOwnerToken? = synchronized(lock) {
        if (closed.get() || state.phase != ControllerBackendCleanupPhase.IDLE) {
            return@synchronized null
        }
        nextOwnerToken = nextOwnerToken(nextOwnerToken)
        ControllerBackendOwnerToken(nextOwnerToken).also { token ->
            state = ControllerBackendCleanupSnapshot(
                phase = ControllerBackendCleanupPhase.ACTIVE,
                ownerToken = token,
            )
        }
    }

    fun snapshot(): ControllerBackendCleanupSnapshot = synchronized(lock) { state }

    /**
     * Fails closed when construction after owner acquisition cannot return a cleanup-capable
     * backend object. Process restart is then the only safe way to release partially initialized
     * framework/vendor state.
     */
    fun failOwner(
        ownerToken: ControllerBackendOwnerToken,
        reason: String,
    ): Boolean = synchronized(lock) {
        if (
            closed.get() ||
            state.phase != ControllerBackendCleanupPhase.ACTIVE ||
            state.ownerToken != ownerToken
        ) {
            return@synchronized false
        }
        state = state.copy(
            phase = ControllerBackendCleanupPhase.FAILED,
            failureReason = boundedFailureReason(reason),
        )
        true
    }

    fun beginCleanup(
        ownerToken: ControllerBackendOwnerToken,
        runCompletion: ControllerBackendCompletionBarrier,
        monitorCompletion: ControllerBackendCompletionBarrier,
        operation: ControllerBackendCleanupOperation,
    ): ControllerBackendCleanupStart {
        val task = synchronized(lock) {
            when {
                closed.get() || state.phase == ControllerBackendCleanupPhase.FAILED ->
                    return ControllerBackendCleanupStart.FAILED_STICKY
                state.ownerToken != ownerToken ->
                    return ControllerBackendCleanupStart.STALE_OWNER
                state.phase == ControllerBackendCleanupPhase.CLEANING ->
                    return ControllerBackendCleanupStart.DUPLICATE
                state.phase != ControllerBackendCleanupPhase.ACTIVE ->
                    return ControllerBackendCleanupStart.STALE_OWNER
            }
            state = state.copy(
                phase = ControllerBackendCleanupPhase.CLEANING,
                failureReason = null,
            )
            CleanupTask(
                ownerToken = ownerToken,
                runCompletion = runCompletion,
                monitorCompletion = monitorCompletion,
                operation = operation,
            ).also { activeTask = it }
        }

        val watchdog = try {
            watchdogExecutor.schedule(
                { task.timeout() },
                cleanupTimeoutMs,
                TimeUnit.MILLISECONDS,
            )
        } catch (error: RejectedExecutionException) {
            task.schedulingFailed("cleanup watchdog rejected")
            return ControllerBackendCleanupStart.SCHEDULING_FAILED
        } catch (error: RuntimeException) {
            task.schedulingFailed(
                "cleanup watchdog failed: ${error.javaClass.simpleName}",
            )
            return ControllerBackendCleanupStart.SCHEDULING_FAILED
        }
        synchronized(lock) {
            if (
                state.phase == ControllerBackendCleanupPhase.CLEANING &&
                state.ownerToken == ownerToken &&
                activeTask === task
            ) {
                activeWatchdog = watchdog
            } else {
                watchdog.cancel(false)
            }
        }

        return try {
            cleanupExecutor.execute(task)
            ControllerBackendCleanupStart.STARTED
        } catch (error: RejectedExecutionException) {
            watchdog.cancel(false)
            task.schedulingFailed("cleanup lane rejected")
            ControllerBackendCleanupStart.SCHEDULING_FAILED
        } catch (error: RuntimeException) {
            watchdog.cancel(false)
            task.schedulingFailed(
                "cleanup lane failed: ${error.javaClass.simpleName}",
            )
            ControllerBackendCleanupStart.SCHEDULING_FAILED
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val task = synchronized(lock) {
            activeWatchdog?.cancel(false)
            activeWatchdog = null
            if (state.phase in setOf(
                    ControllerBackendCleanupPhase.ACTIVE,
                    ControllerBackendCleanupPhase.CLEANING,
                )
            ) {
                state = state.copy(
                    phase = ControllerBackendCleanupPhase.FAILED,
                    failureReason = "cleanup coordinator closed",
                )
            }
            activeTask
        }
        task?.cancel()
        val neverStarted = cleanupExecutor.shutdownNow()
        neverStarted.filterIsInstance<CleanupTask>().forEach(CleanupTask::neverStarted)
        watchdogExecutor.shutdownNow()
        awaitTerminationBounded(cleanupExecutor)
        awaitTerminationBounded(watchdogExecutor)
    }

    private fun finishSuccess(task: CleanupTask) {
        synchronized(lock) {
            if (
                state.phase != ControllerBackendCleanupPhase.CLEANING ||
                state.ownerToken != task.ownerToken ||
                activeTask !== task
            ) {
                return
            }
            activeWatchdog?.cancel(false)
            activeWatchdog = null
            state = ControllerBackendCleanupSnapshot(
                phase = ControllerBackendCleanupPhase.IDLE,
            )
        }
    }

    private fun finishFailure(task: CleanupTask, reason: String): Boolean =
        synchronized(lock) {
            if (
                state.phase != ControllerBackendCleanupPhase.CLEANING ||
                state.ownerToken != task.ownerToken ||
                activeTask !== task
            ) {
                return@synchronized false
            }
            activeWatchdog?.cancel(false)
            activeWatchdog = null
            state = state.copy(
                phase = ControllerBackendCleanupPhase.FAILED,
                failureReason = boundedFailureReason(reason),
            )
            true
        }

    private fun mayInvokeCleanup(task: CleanupTask): Boolean = synchronized(lock) {
        state.phase == ControllerBackendCleanupPhase.CLEANING &&
            state.ownerToken == task.ownerToken &&
            activeTask === task &&
            task.cleanupClaimed.compareAndSet(false, true)
    }

    private fun taskExited(task: CleanupTask) {
        synchronized(lock) {
            if (activeTask === task) activeTask = null
        }
    }

    private fun awaitTerminationBounded(executor: java.util.concurrent.ExecutorService) {
        try {
            executor.awaitTermination(COORDINATOR_CLOSE_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private inner class CleanupTask(
        val ownerToken: ControllerBackendOwnerToken,
        private val runCompletion: ControllerBackendCompletionBarrier,
        private val monitorCompletion: ControllerBackendCompletionBarrier,
        private val operation: ControllerBackendCleanupOperation,
    ) : Runnable {
        val cleanupClaimed = AtomicBoolean(false)
        private val exited = AtomicBoolean(false)
        private val cancelled = AtomicBoolean(false)
        private val runner = AtomicReference<Thread?>()
        private val deadlineNanos = saturatingDeadlineNanos(cleanupTimeoutMs)

        override fun run() {
            if (exited.get()) return
            runner.set(Thread.currentThread())
            var threadDeath: ThreadDeath? = null
            try {
                if (!awaitBarrier(runCompletion, "run completion")) return
                if (!awaitBarrier(monitorCompletion, "monitor completion")) return
                if (!mayInvokeCleanup(this)) return
                val remainingMs = remainingBudgetMs()
                if (remainingMs <= 0L) {
                    finishFailure(this, "cleanup deadline elapsed before backend close")
                    return
                }
                val outcome = operation.cleanup(remainingMs)
                if (System.nanoTime() >= deadlineNanos) {
                    finishFailure(this, "backend cleanup exceeded its deadline")
                } else if (outcome.confirmed) {
                    finishSuccess(this)
                } else {
                    finishFailure(
                        this,
                        outcome.detail.ifBlank { "backend cleanup was not confirmed" },
                    )
                }
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                finishFailure(this, "backend cleanup interrupted")
            } catch (error: Throwable) {
                if (error is ThreadDeath) threadDeath = error
                finishFailure(
                    this,
                    "backend cleanup failed: ${error.javaClass.simpleName}",
                )
            } finally {
                runner.set(null)
                publishExit()
            }
            threadDeath?.let { throw it }
        }

        fun timeout() {
            if (!finishFailure(this, "backend cleanup deadline exceeded")) return
            cancel()
            if (cleanupExecutor.remove(this)) neverStarted()
        }

        fun schedulingFailed(reason: String) {
            finishFailure(this, reason)
            neverStarted()
        }

        fun cancel() {
            cancelled.set(true)
            runner.get()?.interrupt()
        }

        fun neverStarted() {
            cancelled.set(true)
            publishExit()
        }

        private fun awaitBarrier(
            barrier: ControllerBackendCompletionBarrier,
            label: String,
        ): Boolean {
            if (cancelled.get()) return false
            val completed = barrier.awaitUntil(deadlineNanos)
            if (!completed) {
                finishFailure(this, "$label barrier timed out")
                return false
            }
            return !cancelled.get()
        }

        private fun remainingBudgetMs(): Long {
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0L) return 0L
            return TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1L)
        }

        private fun publishExit() {
            if (!exited.compareAndSet(false, true)) return
            taskExited(this)
        }
    }

    private companion object {
        const val DEFAULT_CLEANUP_TIMEOUT_MS = 30_000L
        const val MIN_CLEANUP_TIMEOUT_MS = 10L
        const val MAX_CLEANUP_TIMEOUT_MS = 30_000L
        const val CLEANUP_THREAD_KEEP_ALIVE_MS = 5_000L
        const val COORDINATOR_CLOSE_WAIT_MS = 100L
        const val MAX_FAILURE_REASON_CHARS = 240
        const val CLEANUP_THREAD_NAME = "DpuLab-BackendCleanup"
        const val WATCHDOG_THREAD_NAME = "DpuLab-BackendCleanupWatchdog"

        fun nextOwnerToken(current: Long): Long =
            if (current == Long.MAX_VALUE) 1L else current + 1L

        fun boundedFailureReason(reason: String): String =
            reason.trim().ifBlank { "backend cleanup failed" }.take(MAX_FAILURE_REASON_CHARS)

        fun saturatingDeadlineNanos(timeoutMs: Long): Long {
            val now = System.nanoTime()
            val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            return if (now > Long.MAX_VALUE - timeoutNanos) Long.MAX_VALUE
            else now + timeoutNanos
        }
    }
}

/**
 * Canonical process instance. Tests instantiate [ControllerBackendCleanupCoordinator] directly so
 * sticky failure never needs a production reset escape hatch.
 */
internal object ProcessControllerBackendCleanupCoordinator {
    private val delegate = ControllerBackendCleanupCoordinator()

    fun tryAcquireOwner(): ControllerBackendOwnerToken? = delegate.tryAcquireOwner()

    fun snapshot(): ControllerBackendCleanupSnapshot = delegate.snapshot()

    fun failOwner(
        ownerToken: ControllerBackendOwnerToken,
        reason: String,
    ): Boolean = delegate.failOwner(ownerToken, reason)

    fun beginCleanup(
        ownerToken: ControllerBackendOwnerToken,
        runCompletion: ControllerBackendCompletionBarrier,
        monitorCompletion: ControllerBackendCompletionBarrier,
        operation: ControllerBackendCleanupOperation,
    ): ControllerBackendCleanupStart = delegate.beginCleanup(
        ownerToken = ownerToken,
        runCompletion = runCompletion,
        monitorCompletion = monitorCompletion,
        operation = operation,
    )
}
