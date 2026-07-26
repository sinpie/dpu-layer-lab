package com.example.dpulayerlab.monitor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

enum class SurfaceFlingerCompositionAvailability {
    AVAILABLE,
    DUMP_PERMISSION_UNAVAILABLE,
    PAIR_UNAVAILABLE,
    PAIR_INVALID,
    PROBE_FAILED,
    STALE,
    UNKNOWN,
}

data class CompositionSnapshot(
    val deviceLayers: Int? = null,
    val clientLayers: Int? = null,
    val hwcMissedFrames: Long? = null,
    val gpuMissedFrames: Long? = null,
    val source: String = "",
    val detail: String = "",
    val compositionAvailability: SurfaceFlingerCompositionAvailability =
        SurfaceFlingerCompositionAvailability.UNKNOWN,
)

class SurfaceFlingerProbe(context: Context) : AutoCloseable {
    // A timed-out worker can outlive the Activity. Never let that process-owned diagnostic lane
    // retain a Window or its complete View hierarchy.
    private val context = context.applicationContext
    private val laneLease = processProbeLanes.acquire()

    fun hasDumpPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED

    fun sample(): CompositionSnapshot {
        if (!hasDumpPermission()) {
            return CompositionSnapshot(
                detail = "DUMP 권한 없음",
                compositionAvailability =
                    SurfaceFlingerCompositionAvailability.DUMP_PERMISSION_UNAVAILABLE,
            )
        }
        if (!processChildren.isRestartSafe()) {
            return CompositionSnapshot(
                detail = "이전 SurfaceFlinger process 종료 확인 대기 중",
                compositionAvailability = SurfaceFlingerCompositionAvailability.PROBE_FAILED,
            )
        }
        return when (val result = laneLease.execute()) {
            is ProbeLaneResult.Completed -> result.value
            ProbeLaneResult.Busy ->
                unavailableProbeSnapshot("이전 SurfaceFlinger probe 종료 대기 중")
            ProbeLaneResult.Closed ->
                unavailableProbeSnapshot("SurfaceFlinger probe 종료됨")
            ProbeLaneResult.Cancelled ->
                unavailableProbeSnapshot("SurfaceFlinger probe 중단됨")
            ProbeLaneResult.Interrupted ->
                unavailableProbeSnapshot("SurfaceFlinger probe 중단됨")
            ProbeLaneResult.TimedOut ->
                unavailableProbeSnapshot("SurfaceFlinger 응답 시간 초과")
            is ProbeLaneResult.Failed ->
                unavailableProbeSnapshot(
                    "SurfaceFlinger probe 실패: ${result.error.javaClass.simpleName}",
                )
        }
    }

    private fun unavailableProbeSnapshot(detail: String): CompositionSnapshot =
        CompositionSnapshot(
            detail = detail,
            compositionAvailability = SurfaceFlingerCompositionAvailability.PROBE_FAILED,
        )

    /**
     * Waits without closing the shared lane. This is used after a bounded sample timeout so the
     * first scenario cannot overlap either the old worker or its dumpsys child.
     */
    internal fun awaitQuiescent(timeoutMs: Long): Boolean {
        if (timeoutMs < 0L) return false
        val startedNanos = System.nanoTime()
        val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val deadlineNanos = startedNanos + timeoutNanos
        val laneWaitMs = (deadlineNanos - System.nanoTime())
            .takeIf { it > 0L }
            ?.let { remaining -> TimeUnit.NANOSECONDS.toMillis(remaining) }
            ?: 0L
        if (!laneLease.awaitIdle(laneWaitMs)) return false
        while (true) {
            if (processChildren.isRestartSafe()) return true
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0L) return processChildren.isRestartSafe()
            LockSupport.parkNanos(
                minOf(remainingNanos, QUIESCENCE_POLL_NANOS),
            )
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }

    override fun close() {
        closeWithResult()
    }

    internal fun closeWithResult(): Boolean {
        var terminalFailure: Throwable? = null
        val laneStopped = try {
            laneLease.closeWithResult()
        } catch (error: Throwable) {
            terminalFailure = mergeProbeFailurePreservingFatal(terminalFailure, error)
            false
        }
        // Always inspect the child gate even when the lane stop failed. A process which exited
        // after the bounded lane wait can then release its process reference deterministically.
        val childStopped = try {
            processChildren.isRestartSafe()
        } catch (error: Throwable) {
            terminalFailure = mergeProbeFailurePreservingFatal(terminalFailure, error)
            false
        }
        terminalFailure?.let { throw it }
        return laneStopped && childStopped
    }

    private companion object {
        val processChildren = UnconfirmedProcessRegistry()
        val processProbeLanes =
            SharedProbeLaneRegistry {
                SingleFlightProbeLane(
                    threadName = "DpuLab-SurfaceFlinger",
                    timeoutMs = PROBE_TIMEOUT_MS,
                    shutdownTimeoutMs = PROBE_SHUTDOWN_TIMEOUT_MS,
                    operation = ::runProbe,
                )
            }

        fun runProbe(cancellation: ProbeCancellation): CompositionSnapshot {
            if (!processChildren.isRestartSafe()) {
                throw IllegalStateException("previous SurfaceFlinger process is still alive")
            }
            val process =
                ProcessBuilder("/system/bin/dumpsys", "SurfaceFlinger", "--hwclayers")
                    .redirectErrorStream(true)
                    .start()
            var snapshot: CompositionSnapshot? = null
            var terminalFailure: Throwable? = null
            try {
                if (
                    !cancellation.registerCleanup {
                        // Keep the waiting caller's cancellation path non-blocking. The worker
                        // owns stream close/wait in finally after destroy wakes the pipe reader.
                        if (process.isAlive) {
                            try {
                                process.destroyForcibly()
                            } catch (error: Error) {
                                throw error
                            } catch (_: Exception) {
                                // The worker-owned bounded cleanup below remains authoritative.
                            }
                        }
                    }
                ) {
                    throw InterruptedException(
                        "SurfaceFlinger probe cancelled before process registration",
                    )
                }
                val text = process.inputStream.bufferedReader().use {
                    readBoundedText(it, MAX_DUMP_CHARS)
                }
                if (cancellation.isCancellationRequested()) {
                    throw InterruptedException("SurfaceFlinger probe cancelled")
                }
                snapshot = parseSurfaceFlingerDump(text)
            } catch (error: Throwable) {
                terminalFailure = error
            }
            try {
                val cleanupConfirmed = cleanupProbeProcess(
                    process = process,
                    waitMs = PROCESS_CLEANUP_WAIT_MS,
                    registry = processChildren,
                )
                if (!cleanupConfirmed) {
                    val cleanupFailure = IllegalStateException(
                        "SurfaceFlinger process termination was not confirmed",
                    )
                    terminalFailure = mergeProbeFailurePreservingFatal(
                        terminalFailure,
                        cleanupFailure,
                    )
                }
            } catch (cleanupError: Throwable) {
                terminalFailure = mergeProbeFailurePreservingFatal(
                    terminalFailure,
                    cleanupError,
                )
            }
            terminalFailure?.let { throw it }
            return checkNotNull(snapshot) {
                "SurfaceFlinger probe completed without a snapshot"
            }
        }

        const val PROBE_TIMEOUT_MS = 800L
        const val PROCESS_CLEANUP_WAIT_MS = 100L
        const val PROBE_SHUTDOWN_MARGIN_MS = 300L
        const val QUIESCENCE_POLL_NANOS = 1_000_000L
        val PROBE_SHUTDOWN_TIMEOUT_MS = surfaceFlingerProbeShutdownBudgetMs(
            processCleanupWaitMs = PROCESS_CLEANUP_WAIT_MS,
            completionMarginMs = PROBE_SHUTDOWN_MARGIN_MS,
        )
        const val MAX_DUMP_CHARS = 4 * 1_024 * 1_024
    }
}

/**
 * A process/thread cancellation bridge. The registered cleanup action is single-owner and invoked
 * at most once, whether cancellation races registration or normal completion.
 */
internal class ProbeCancellation {
    private val cancellationRequested = AtomicBoolean(false)
    private val cleanupAction = AtomicReference<(() -> Unit)?>(null)

    fun registerCleanup(action: () -> Unit): Boolean {
        check(cleanupAction.compareAndSet(null, action)) {
            "Probe cleanup action is already registered"
        }
        if (cancellationRequested.get()) {
            cleanup()
            return false
        }
        return true
    }

    fun isCancellationRequested(): Boolean = cancellationRequested.get()

    fun cancel() {
        cancellationRequested.set(true)
        cleanup()
    }

    fun cleanup() {
        cleanupAction.getAndSet(null)?.invoke()
    }
}

/** Keeps the first fatal identity while retaining ordinary/later cleanup failures as evidence. */
internal fun mergeProbeFailurePreservingFatal(
    current: Throwable?,
    candidate: Throwable,
): Throwable {
    if (current == null || current === candidate) return current ?: candidate
    val terminal = if (current is Error || candidate !is Error) current else candidate
    val secondary = if (terminal === current) candidate else current
    try {
        terminal.addSuppressed(secondary)
    } catch (_: Throwable) {
        // Preserve an active fatal even when suppressed-state allocation itself is unavailable.
    }
    return terminal
}

internal sealed class ProbeLaneResult<out T> {
    data class Completed<T>(val value: T) : ProbeLaneResult<T>()
    data class Failed(val error: Throwable) : ProbeLaneResult<Nothing>()
    data object Busy : ProbeLaneResult<Nothing>()
    data object Closed : ProbeLaneResult<Nothing>()
    data object Cancelled : ProbeLaneResult<Nothing>()
    data object Interrupted : ProbeLaneResult<Nothing>()
    data object TimedOut : ProbeLaneResult<Nothing>()
}

/**
 * Executes at most one diagnostic operation. The active gate permits only the current hand-off
 * slot, so callers can never accumulate pending probes behind a stuck vendor process. Cancellation
 * completes the waiting caller immediately, while the delegate's active gate remains set until
 * the worker's real `finally` executes.
 */
internal class SingleFlightProbeLane<T>(
    threadName: String,
    timeoutMs: Long,
    shutdownTimeoutMs: Long,
    workerUncaughtExceptionHandler: Thread.UncaughtExceptionHandler? = null,
    operation: (ProbeCancellation) -> T,
) : AutoCloseable {
    private val delegate = SingleFlightInputProbeLane<Unit, T>(
        threadName = threadName,
        timeoutMs = timeoutMs,
        shutdownTimeoutMs = shutdownTimeoutMs,
        workerUncaughtExceptionHandler = workerUncaughtExceptionHandler,
    ) { _, cancellation ->
        operation(cancellation)
    }

    fun execute(): ProbeLaneResult<T> = delegate.execute(Unit)

    internal fun isActive(): Boolean = delegate.isActive()

    internal fun awaitIdle(timeoutMs: Long): Boolean = delegate.awaitIdle(timeoutMs)

    internal fun isRestartSafeAfterClose(): Boolean = delegate.isRestartSafeAfterClose()

    internal fun closeWithResult(): Boolean = delegate.closeWithResult()

    override fun close() {
        closeWithResult()
    }
}

/**
 * Input-carrying bounded diagnostic lane. A request is captured by its one task and never stored
 * in a pending queue. A timeout cancels the waiter immediately but keeps [active] asserted until
 * that exact task publishes its real completion.
 */
internal class SingleFlightInputProbeLane<I : Any, T>(
    threadName: String,
    private val timeoutMs: Long,
    private val shutdownTimeoutMs: Long,
    workerUncaughtExceptionHandler: Thread.UncaughtExceptionHandler? = null,
    private val operation: (I, ProbeCancellation) -> T,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val active = AtomicBoolean(false)
    private val activeTask = AtomicReference<ProbeTask?>()
    private val handoffLock = Any()
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(1),
        { runnable ->
            Thread(runnable, threadName).apply {
                isDaemon = true
                if (workerUncaughtExceptionHandler != null) {
                    uncaughtExceptionHandler = workerUncaughtExceptionHandler
                }
            }
        },
        ThreadPoolExecutor.AbortPolicy(),
    )

    init {
        require(timeoutMs > 0L)
        require(shutdownTimeoutMs >= 0L)
    }

    fun execute(input: I): ProbeLaneResult<T> = execute(input, timeoutMs)

    /**
     * Allows a caller with a tighter absolute deadline to shorten, but never extend, this lane's
     * process-wide hard timeout.
     */
    fun execute(
        input: I,
        callerTimeoutMs: Long,
    ): ProbeLaneResult<T> {
        if (callerTimeoutMs <= 0L) return ProbeLaneResult.TimedOut
        val effectiveTimeoutMs = minOf(timeoutMs, callerTimeoutMs)
        if (closed.get()) return ProbeLaneResult.Closed
        if (!active.compareAndSet(false, true)) return ProbeLaneResult.Busy
        val task = synchronized(handoffLock) {
            if (closed.get()) {
                active.set(false)
                null
            } else {
                val created = ProbeTask(input)
                if (activeTask.compareAndSet(null, created)) {
                    created
                } else {
                    // Defensive recovery for a hostile race/invariant violation. Never leave the
                    // public active gate asserted without an owned task that can clear it.
                    active.set(false)
                    null
                }
            }
        } ?: return if (closed.get()) ProbeLaneResult.Closed else ProbeLaneResult.Busy
        if (closed.get()) {
            task.finishWithoutRun()
            return ProbeLaneResult.Closed
        }

        try {
            executor.execute(task)
        } catch (error: Throwable) {
            var terminal: Throwable = error
            try {
                task.finishWithoutRun()
            } catch (cleanupError: Throwable) {
                terminal = mergeProbeFailurePreservingFatal(terminal, cleanupError)
            }
            if (terminal is Error) throw terminal
            return if (closed.get() || terminal is RejectedExecutionException) {
                if (closed.get()) ProbeLaneResult.Closed else ProbeLaneResult.Busy
            } else {
                ProbeLaneResult.Failed(terminal)
            }
        }

        return try {
            task.result.get(effectiveTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            try {
                task.cancel()
                ProbeLaneResult.TimedOut
            } catch (error: Error) {
                throw error
            } catch (error: Throwable) {
                ProbeLaneResult.Failed(error)
            }
        } catch (_: InterruptedException) {
            var cancellationFailure: Throwable? = null
            try {
                task.cancel()
            } catch (error: Throwable) {
                cancellationFailure = error
            } finally {
                Thread.currentThread().interrupt()
            }
            cancellationFailure?.let { error ->
                if (error is Error) throw error
                return ProbeLaneResult.Failed(error)
            }
            ProbeLaneResult.Interrupted
        } catch (error: ExecutionException) {
            val cause = error.cause ?: error
            if (cause is Error) throw cause
            ProbeLaneResult.Failed(cause)
        }
    }

    internal fun isActive(): Boolean = active.get()

    /**
     * Non-closing barrier used between an abandoned diagnostic sample and measured work. It never
     * opens a queue slot or clears [active]; only the worker's actual `finally` can do that.
     */
    internal fun awaitIdle(timeoutMs: Long): Boolean {
        if (timeoutMs < 0L) return false
        val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val startedNanos = System.nanoTime()
        val deadlineNanos = startedNanos + timeoutNanos
        while (true) {
            val task = synchronized(handoffLock) { activeTask.get() }
            if (task != null) return task.awaitActualCompletion(deadlineNanos)
            if (!active.get()) return true
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0L) return false
            LockSupport.parkNanos(minOf(remainingNanos, IDLE_HANDOFF_POLL_NANOS))
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }

    internal fun isRestartSafeAfterClose(): Boolean =
        closed.get() && !active.get() && executor.isTerminated

    internal fun closeWithResult(): Boolean {
        closed.set(true)
        val deadlineNanos = System.nanoTime() +
            TimeUnit.MILLISECONDS.toNanos(shutdownTimeoutMs)
        val task = synchronized(handoffLock) { activeTask.get() }
        var terminalFailure: Throwable? = null
        try {
            task?.cancel()
        } catch (error: Throwable) {
            terminalFailure = mergeProbeFailurePreservingFatal(terminalFailure, error)
        }
        // A close can race the executor hand-off after execute(task) accepted the Runnable but
        // before the worker called run(). shutdownNow returns that never-started task; publish its
        // actual completion explicitly or the single-flight gate would remain sticky forever.
        val neverStartedTasks = try {
            executor.shutdownNow()
        } catch (error: Throwable) {
            terminalFailure = mergeProbeFailurePreservingFatal(terminalFailure, error)
            emptyList()
        }
        task?.let { pendingTask ->
            if (neverStartedTasks.any { it === pendingTask }) {
                try {
                    pendingTask.finishWithoutRun()
                } catch (error: Throwable) {
                    terminalFailure =
                        mergeProbeFailurePreservingFatal(terminalFailure, error)
                }
            }
        }

        val taskStopped = try {
            task?.awaitActualCompletion(deadlineNanos) ?: !active.get()
        } catch (error: Throwable) {
            terminalFailure = mergeProbeFailurePreservingFatal(terminalFailure, error)
            false
        }
        val executorStopped = try {
            awaitExecutorTermination(deadlineNanos)
        } catch (error: Throwable) {
            terminalFailure = mergeProbeFailurePreservingFatal(terminalFailure, error)
            false
        }
        terminalFailure?.let { throw it }
        return taskStopped && executorStopped && !active.get()
    }

    override fun close() {
        closeWithResult()
    }

    private fun awaitExecutorTermination(deadlineNanos: Long): Boolean {
        if (executor.isTerminated) return true
        val remainingNanos = deadlineNanos - System.nanoTime()
        if (remainingNanos <= 0L) return false
        return try {
            executor.awaitTermination(remainingNanos, TimeUnit.NANOSECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private inner class ProbeTask(
        private val input: I,
    ) : Runnable {
        val result = CompletableFuture<ProbeLaneResult<T>>()
        private val cancellation = ProbeCancellation()
        private val runner = AtomicReference<Thread?>()
        private val actualCompletion = CountDownLatch(1)
        private val actualCompletionPublished = AtomicBoolean(false)

        override fun run() {
            var outcome: ProbeLaneResult<T> = ProbeLaneResult.Cancelled
            var terminalFailure: Throwable? = null
            try {
                runner.set(Thread.currentThread())
                outcome = if (cancellation.isCancellationRequested()) {
                    ProbeLaneResult.Cancelled
                } else {
                    ProbeLaneResult.Completed(operation(input, cancellation))
                }
            } catch (error: Throwable) {
                terminalFailure = error
                outcome = if (
                    cancellation.isCancellationRequested() ||
                    error is InterruptedException
                ) {
                    ProbeLaneResult.Cancelled
                } else {
                    ProbeLaneResult.Failed(error)
                }
            } finally {
                try {
                    cancellation.cleanup()
                } catch (error: Throwable) {
                    terminalFailure =
                        mergeProbeFailurePreservingFatal(terminalFailure, error)
                }
                try {
                    runner.set(null)
                } catch (error: Throwable) {
                    terminalFailure =
                        mergeProbeFailurePreservingFatal(terminalFailure, error)
                }
                try {
                    publishActualCompletion()
                } catch (error: Throwable) {
                    terminalFailure =
                        mergeProbeFailurePreservingFatal(terminalFailure, error)
                }
                val failure = terminalFailure
                if (failure != null && failure !is Error && outcome is ProbeLaneResult.Completed) {
                    outcome = ProbeLaneResult.Failed(failure)
                }
                try {
                    if (failure is Error) {
                        result.completeExceptionally(failure)
                    } else {
                        result.complete(outcome)
                    }
                } catch (error: Throwable) {
                    terminalFailure =
                        mergeProbeFailurePreservingFatal(terminalFailure, error)
                }
            }
            (terminalFailure as? Error)?.let { throw it }
        }

        fun cancel() {
            // Publish cancellation before releasing a blocking resource. Otherwise that resource
            // can wake and race a stale successful result ahead of the close/timeout boundary.
            var terminalFailure: Throwable? = null
            try {
                result.complete(ProbeLaneResult.Cancelled)
            } catch (error: Throwable) {
                terminalFailure = mergeProbeFailurePreservingFatal(terminalFailure, error)
            }
            try {
                cancellation.cancel()
            } catch (error: Throwable) {
                terminalFailure = mergeProbeFailurePreservingFatal(terminalFailure, error)
            }
            try {
                runner.get()?.interrupt()
            } catch (error: Throwable) {
                terminalFailure = mergeProbeFailurePreservingFatal(terminalFailure, error)
            }
            terminalFailure?.let { throw it }
        }

        fun finishWithoutRun() {
            var terminalFailure: Throwable? = null
            try {
                cancel()
            } catch (error: Throwable) {
                terminalFailure = mergeProbeFailurePreservingFatal(terminalFailure, error)
            }
            try {
                publishActualCompletion()
            } catch (error: Throwable) {
                terminalFailure = mergeProbeFailurePreservingFatal(terminalFailure, error)
            }
            terminalFailure?.let { throw it }
        }

        fun awaitActualCompletion(deadlineNanos: Long): Boolean {
            if (actualCompletion.count == 0L) return true
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0L) return false
            return try {
                actualCompletion.await(remainingNanos, TimeUnit.NANOSECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        }

        private fun publishActualCompletion() {
            if (!actualCompletionPublished.compareAndSet(false, true)) return
            synchronized(handoffLock) {
                activeTask.compareAndSet(this, null)
                active.set(false)
            }
            actualCompletion.countDown()
        }
    }

    private companion object {
        const val IDLE_HANDOFF_POLL_NANOS = 1_000_000L
    }
}

/**
 * Reference-counted process lane. Activity recreation shares the same active gate; if the last
 * owner's bounded close cannot prove worker termination, the closed lane remains quarantined and
 * later owners cannot start another dumpsys process.
 */
internal class SharedProbeLaneRegistry<T>(
    private val factory: () -> SingleFlightProbeLane<T>,
) {
    private var lane: SingleFlightProbeLane<T>? = null
    private var owners = 0
    private var closeFailed = false

    @Synchronized
    fun acquire(): Lease<T> {
        val existing = lane
        val current = if (
            existing == null ||
            (
                closeFailed &&
                    owners == 0 &&
                    existing.isRestartSafeAfterClose()
                )
        ) {
            factory().also { created ->
                lane = created
                closeFailed = false
            }
        } else {
            existing
        }
        owners += 1
        return Lease(this, current)
    }

    @Synchronized
    private fun release(target: SingleFlightProbeLane<T>): Boolean {
        if (lane !== target || owners <= 0) return false
        owners -= 1
        if (owners > 0) return true
        if (closeFailed) return false

        val stopped = try {
            target.closeWithResult()
        } catch (error: Throwable) {
            closeFailed = true
            throw error
        }
        if (stopped) {
            lane = null
        } else {
            closeFailed = true
        }
        return stopped
    }

    @Synchronized
    internal fun hasRecoverableQuarantine(): Boolean =
        closeFailed &&
            owners == 0 &&
            lane?.isRestartSafeAfterClose() == true

    internal class Lease<T> internal constructor(
        private val registry: SharedProbeLaneRegistry<T>,
        private val lane: SingleFlightProbeLane<T>,
    ) : AutoCloseable {
        private val releaseStarted = AtomicBoolean(false)
        @Volatile
        private var releaseResult: Boolean? = null

        fun execute(): ProbeLaneResult<T> =
            if (releaseStarted.get()) ProbeLaneResult.Closed else lane.execute()

        internal fun awaitIdle(timeoutMs: Long): Boolean =
            if (releaseStarted.get()) false else lane.awaitIdle(timeoutMs)

        @Synchronized
        fun closeWithResult(): Boolean {
            releaseResult?.let { return it }
            releaseStarted.set(true)
            return registry.release(lane).also { releaseResult = it }
        }

        override fun close() {
            closeWithResult()
        }
    }
}

/**
 * Holds at most one child process that did not satisfy the bounded teardown contract. The
 * reference is dropped automatically once the OS reports that child dead.
 */
internal class UnconfirmedProcessRegistry {
    private val unconfirmed = AtomicReference<Process?>()

    fun record(process: Process) {
        if (process.isAlive) {
            unconfirmed.compareAndSet(null, process)
        }
    }

    fun clear(process: Process) {
        unconfirmed.compareAndSet(process, null)
    }

    fun isRestartSafe(): Boolean {
        while (true) {
            val process = unconfirmed.get() ?: return true
            if (process.isAlive) return false
            if (unconfirmed.compareAndSet(process, null)) return true
        }
    }
}

/**
 * Performs two bounded waits, including one after the final forcible destroy. Returning `true` is
 * therefore an explicit observation that the child is no longer alive, not merely proof that a
 * kill signal was requested.
 */
internal fun cleanupProbeProcess(
    process: Process,
    waitMs: Long,
    registry: UnconfirmedProcessRegistry,
): Boolean {
    require(waitMs >= 0L)
    var terminalFailure: Throwable? = null

    var processAlive = try {
        process.isAlive
    } catch (error: Throwable) {
        terminalFailure = mergeProbeFailurePreservingFatal(terminalFailure, error)
        true
    }
    if (processAlive) {
        try {
            process.destroyForcibly()
        } catch (error: Throwable) {
            terminalFailure = mergeProbeFailurePreservingFatal(terminalFailure, error)
        }
    }
    try {
        process.inputStream.close()
    } catch (error: Throwable) {
        terminalFailure = mergeProbeFailurePreservingFatal(terminalFailure, error)
    }
    try {
        process.errorStream.close()
    } catch (error: Throwable) {
        terminalFailure = mergeProbeFailurePreservingFatal(terminalFailure, error)
    }
    try {
        process.outputStream.close()
    } catch (error: Throwable) {
        terminalFailure = mergeProbeFailurePreservingFatal(terminalFailure, error)
    }
    try {
        process.waitFor(waitMs, TimeUnit.MILLISECONDS)
    } catch (error: Throwable) {
        terminalFailure = mergeProbeFailurePreservingFatal(terminalFailure, error)
    }
    processAlive = try {
        process.isAlive
    } catch (error: Throwable) {
        terminalFailure = mergeProbeFailurePreservingFatal(terminalFailure, error)
        true
    }
    if (processAlive) {
        try {
            process.destroyForcibly()
        } catch (error: Throwable) {
            terminalFailure = mergeProbeFailurePreservingFatal(terminalFailure, error)
        }
        // A second wait is required after the final kill request. Without it the worker can
        // publish completion while the old dumpsys process still owns its pipe/native state.
        try {
            process.waitFor(waitMs, TimeUnit.MILLISECONDS)
        } catch (error: Throwable) {
            terminalFailure = mergeProbeFailurePreservingFatal(terminalFailure, error)
        }
    }
    val processStillAlive = try {
        process.isAlive
    } catch (error: Throwable) {
        terminalFailure = mergeProbeFailurePreservingFatal(terminalFailure, error)
        true
    }
    val restartSafe = if (processStillAlive) {
        val recorded = try {
            registry.record(process)
            true
        } catch (error: Throwable) {
            terminalFailure = mergeProbeFailurePreservingFatal(terminalFailure, error)
            false
        }
        // The process can exit between the final observation and registration. Re-read through
        // the registry so that race is accepted only when death is explicitly observed.
        val observedSafe = try {
            registry.isRestartSafe()
        } catch (error: Throwable) {
            terminalFailure = mergeProbeFailurePreservingFatal(terminalFailure, error)
            false
        }
        recorded && observedSafe
    } else {
        val cleared = try {
            registry.clear(process)
            true
        } catch (error: Throwable) {
            terminalFailure = mergeProbeFailurePreservingFatal(terminalFailure, error)
            false
        }
        val observedSafe = try {
            registry.isRestartSafe()
        } catch (error: Throwable) {
            terminalFailure = mergeProbeFailurePreservingFatal(terminalFailure, error)
            false
        }
        cleared && observedSafe
    }
    (terminalFailure as? Error)?.let { throw it }
    return restartSafe
}

/**
 * The process cleanup path can consume two complete waits. Keep an explicit completion margin for
 * stream close, worker-finally publication and executor termination so a child which dies at the
 * second wait boundary is not reported as an unconfirmed teardown.
 */
internal fun surfaceFlingerProbeShutdownBudgetMs(
    processCleanupWaitMs: Long,
    completionMarginMs: Long,
): Long {
    require(processCleanupWaitMs >= 0L)
    require(completionMarginMs > 0L)
    val waitsBudget = if (processCleanupWaitMs > Long.MAX_VALUE / 2L) {
        Long.MAX_VALUE
    } else {
        processCleanupWaitMs * 2L
    }
    return if (waitsBudget > Long.MAX_VALUE - completionMarginMs) {
        Long.MAX_VALUE
    } else {
        waitsBudget + completionMarginMs
    }
}

internal fun readBoundedText(reader: java.io.Reader, maxChars: Int): String {
    require(maxChars > 0)
    val result = StringBuilder(minOf(maxChars, 16 * 1_024))
    val buffer = CharArray(8 * 1_024)
    while (true) {
        val read = reader.read(buffer)
        if (read < 0) return result.toString()
        // Reader.read(char[], …) with a non-empty buffer must either make progress or reach EOF.
        // Treat a broken vendor pipe/Reader implementation as unavailable instead of allowing a
        // zero-progress busy loop to occupy the diagnostic worker indefinitely.
        if (read == 0) {
            throw IllegalStateException("SurfaceFlinger dump reader made no progress")
        }
        if (result.length > maxChars - read) {
            throw IllegalStateException("SurfaceFlinger dump exceeds $maxChars characters")
        }
        result.append(buffer, 0, read)
    }
}

internal fun parseSurfaceFlingerDump(text: String): CompositionSnapshot {
    val source = "dumpsys SurfaceFlinger --hwclayers"
    val implicitScope = SurfaceFlingerDisplayScope(
        displayLabel = null,
        displayState = null,
    )
    val explicitScopes = mutableListOf<SurfaceFlingerDisplayScope>()
    var currentScope = implicitScope
    var malformedDisplayMarker = false
    var tooManyDisplaySections = false
    var compositionParsingDisabled = false
    var hwcMissed: Long? = null
    var gpuMissed: Long? = null

    // Parse each display independently. A --hwclayers dump can contain several physical displays;
    // summing matching records across them would turn mirroring into fabricated app-layer counts.
    text.lineSequence().forEach { line ->
        val displayHeader = SURFACE_FLINGER_DISPLAY_HEADER_PATTERN.matchEntire(line)
        if (displayHeader != null) {
            currentScope.finish()
            if (explicitScopes.size >= SURFACE_FLINGER_MAX_DISPLAY_SECTIONS) {
                tooManyDisplaySections = true
                compositionParsingDisabled = true
            } else {
                currentScope = SurfaceFlingerDisplayScope(
                    displayLabel = sanitizeSurfaceFlingerDisplayLabel(
                        displayHeader.groupValues[1].trim(),
                    ),
                    displayState = displayHeader.groupValues[2]
                        .takeIf { it.isNotBlank() }
                        ?.lowercase(),
                )
                explicitScopes += currentScope
            }
        } else {
            if (SURFACE_FLINGER_DISPLAY_MARKER_PATTERN.containsMatchIn(line)) {
                // A display boundary which cannot be identified must not be collapsed into the
                // preceding scope.
                malformedDisplayMarker = true
            }
            if (!compositionParsingDisabled) {
                currentScope.accept(line)
            }
        }
        if (hwcMissed == null) {
            hwcMissed = SURFACE_FLINGER_HWC_MISSED_PATTERN
                .find(line)
                ?.groupValues
                ?.getOrNull(1)
                ?.toLongOrNull()
        }
        if (gpuMissed == null) {
            gpuMissed = SURFACE_FLINGER_GPU_MISSED_PATTERN
                .find(line)
                ?.groupValues
                ?.getOrNull(1)
                ?.toLongOrNull()
        }
    }
    currentScope.finish()

    val duplicateDisplayLabels = explicitScopes
        .groupingBy { it.displayLabel.orEmpty().lowercase() }
        .eachCount()
        .any { (_, count) -> count > 1 }
    val explicitAppScopes = explicitScopes.filter { it.appTokenObserved }
    val scopeUnavailableDetail = when {
        tooManyDisplaySections ->
            "display scope unavailable: more than $SURFACE_FLINGER_MAX_DISPLAY_SECTIONS sections"
        malformedDisplayMarker ->
            "display scope unavailable: malformed HWC display marker"
        duplicateDisplayLabels ->
            "display scope unavailable: duplicate HWC display sections"
        explicitScopes.isNotEmpty() && implicitScope.appTokenObserved ->
            "display scope ambiguous: app records exist outside display sections"
        explicitAppScopes.size > 1 ->
            "display scope ambiguous: app records found on ${explicitAppScopes.size} displays"
        else -> null
    }
    val selectedScope = when {
        scopeUnavailableDetail != null -> null
        explicitScopes.isNotEmpty() -> explicitAppScopes.singleOrNull()
        else -> implicitScope
    }
    val classificationAvailable =
        selectedScope != null &&
            selectedScope.appRecordCount > 0 &&
            selectedScope.classificationComplete &&
            !selectedScope.mixedRecordFormats
    val selectedScopeDetail = selectedScope?.detail(
        unscoped = explicitScopes.isEmpty(),
    )
    val detail = scopeUnavailableDetail ?: when {
        selectedScope == null ->
            "no uniquely scoped app HWC layer records"
        classificationAvailable ->
            selectedScopeDetail.orEmpty()
        selectedScope.appTokenObserved ->
            "$selectedScopeDetail; composition classification unavailable"
        explicitScopes.isNotEmpty() ->
            "no app HWC layer records in ${explicitScopes.size} display sections"
        else ->
            "no app HWC layer records; legacy unscoped single-section"
    }
    val availability = when {
        scopeUnavailableDetail != null ->
            SurfaceFlingerCompositionAvailability.PAIR_INVALID
        classificationAvailable ->
            SurfaceFlingerCompositionAvailability.AVAILABLE
        selectedScope?.appTokenObserved == true ->
            SurfaceFlingerCompositionAvailability.PAIR_INVALID
        else ->
            SurfaceFlingerCompositionAvailability.PAIR_UNAVAILABLE
    }
    val scopedSource = when {
        scopeUnavailableDetail != null || selectedScope == null -> source
        explicitScopes.isEmpty() -> "$source · display=unscoped"
        else -> "$source · display=${sanitizeSurfaceFlingerDisplayLabel(selectedScope.displayLabel)}"
    }
    return CompositionSnapshot(
        deviceLayers = selectedScope
            ?.deviceLayers
            ?.takeIf { classificationAvailable },
        clientLayers = selectedScope
            ?.clientLayers
            ?.takeIf { classificationAvailable },
        hwcMissedFrames = hwcMissed,
        gpuMissedFrames = gpuMissed,
        source = scopedSource,
        detail = detail,
        compositionAvailability = availability,
    )
}

private enum class SurfaceFlingerComposition {
    DEVICE,
    CLIENT,
}

private enum class SurfaceFlingerRecordFormat(
    val detailLabel: String,
) {
    AOSP_MINIDUMP("aosp-minidump"),
    STRUCTURED_BLOCK("structured-block"),
    LEGACY_SAME_LINE("legacy-same-line"),
}

private data class SurfaceFlingerTableRow(
    val composition: SurfaceFlingerComposition?,
)

/**
 * One independently delimited `Display ... HWC layers:` section. When a legacy vendor omits
 * display headers, the complete dump is represented by one implicit scope and [detail] identifies
 * that weaker provenance.
 */
private class SurfaceFlingerDisplayScope(
    val displayLabel: String?,
    private val displayState: String?,
) {
    var appTokenObserved: Boolean = false
        private set
    var appRecordCount: Int = 0
        private set
    var deviceLayers: Int = 0
        private set
    var clientLayers: Int = 0
        private set
    var classificationComplete: Boolean = true
        private set
    var mixedRecordFormats: Boolean = false
        private set

    private var recordFormat: SurfaceFlingerRecordFormat? = null
    private var structuredBlockOpen = false
    private var structuredBlockIsApp = false
    private var structuredBlockInvalid = false
    private var structuredBlockDevice = false
    private var structuredBlockClient = false
    private var tableOpen = false
    private var tableCompositionColumnIndex = INVALID_SURFACE_FLINGER_COLUMN_INDEX
    private var pendingTableName = false
    private var pendingTableNameIsApp = false
    private var finished = false

    fun accept(line: String) {
        check(!finished)
        val startsLayerBlock = SURFACE_FLINGER_LAYER_BLOCK_PATTERN.containsMatchIn(line)
        if (startsLayerBlock) {
            finishPendingTableName()
            finishStructuredBlock()
            tableOpen = false
            tableCompositionColumnIndex = INVALID_SURFACE_FLINGER_COLUMN_INDEX
            structuredBlockOpen = true
            structuredBlockIsApp = containsDpuLabToken(line)
            if (structuredBlockIsApp) {
                appTokenObserved = true
                observeFormat(SurfaceFlingerRecordFormat.STRUCTURED_BLOCK)
            }
            return
        }

        if (SURFACE_FLINGER_TABLE_HEADER_PATTERN.containsMatchIn(line)) {
            finishPendingTableName()
            finishStructuredBlock()
            tableOpen = true
            tableCompositionColumnIndex =
                findUniqueSurfaceFlingerCompositionColumnIndex(line)
            return
        }

        if (structuredBlockOpen) {
            if (structuredBlockIsApp) {
                observeStructuredClassification(line)
            }
            return
        }

        if (tableOpen) {
            acceptTableLine(line)
            return
        }

        acceptLegacyLine(line)
    }

    fun finish() {
        if (finished) return
        finishPendingTableName()
        finishStructuredBlock()
        finished = true
    }

    fun detail(unscoped: Boolean): String {
        val scope = if (unscoped) {
            "display=unscoped"
        } else {
            buildString {
                append("display=")
                append(displayLabel)
                if (displayState != null) {
                    append(" (")
                    append(displayState)
                    append(')')
                }
            }
        }
        val format = if (mixedRecordFormats) {
            "mixed"
        } else {
            recordFormat?.detailLabel ?: "unknown"
        }
        return buildString {
            append(scope)
            append("; ")
            append(appRecordCount)
            append(" app layers found; format=")
            append(format)
            if (unscoped) {
                append("; legacy unscoped single-section")
            }
        }
    }

    private fun acceptTableLine(line: String) {
        if (line.isBlank()) {
            finishPendingTableName()
            tableOpen = false
            tableCompositionColumnIndex = INVALID_SURFACE_FLINGER_COLUMN_INDEX
            return
        }
        if (isSurfaceFlingerTableSeparator(line)) return

        val row = parseSurfaceFlingerTableRow(
            line = line,
            compositionColumnIndex = tableCompositionColumnIndex,
        )
        if (row != null) {
            if (pendingTableNameIsApp) {
                recordAppLayer(
                    format = SurfaceFlingerRecordFormat.AOSP_MINIDUMP,
                    composition = row.composition,
                )
            } else if (!pendingTableName && containsDpuLabToken(line)) {
                // A row with no preceding name cannot be safely associated with an app layer.
                appTokenObserved = true
                recordAppLayer(
                    format = SurfaceFlingerRecordFormat.AOSP_MINIDUMP,
                    composition = null,
                )
            }
            pendingTableName = false
            pendingTableNameIsApp = false
            return
        }

        // AOSP prints one layer-name line followed by exactly one table row. A second name before
        // the row makes the first app record partial rather than silently dropping it.
        finishPendingTableName()
        pendingTableName = true
        pendingTableNameIsApp = containsDpuLabToken(line)
        if (pendingTableNameIsApp) {
            appTokenObserved = true
            observeFormat(SurfaceFlingerRecordFormat.AOSP_MINIDUMP)
        }
    }

    private fun acceptLegacyLine(line: String) {
        if (!containsDpuLabToken(line)) return
        appTokenObserved = true
        val parsed = if (
            SURFACE_FLINGER_EXPLICIT_COMPOSITION_PATTERN.containsMatchIn(line)
        ) {
            parseSurfaceFlingerExplicitComposition(line)
        } else {
            null
        }
        recordAppLayer(
            format = SurfaceFlingerRecordFormat.LEGACY_SAME_LINE,
            composition = parsed,
        )
    }

    private fun observeStructuredClassification(line: String) {
        SURFACE_FLINGER_EXPLICIT_COMPOSITION_PATTERN.findAll(line).forEach { match ->
            when (classifySurfaceFlingerComposition(match.groupValues[1])) {
                SurfaceFlingerComposition.DEVICE -> structuredBlockDevice = true
                SurfaceFlingerComposition.CLIENT -> structuredBlockClient = true
                null -> structuredBlockInvalid = true
            }
        }

        // Preserve fail-closed handling for vendor property rows which expose a second bare
        // classification. The layer-name row never reaches this path, so tokens in names cannot
        // become composition evidence.
        SURFACE_FLINGER_BARE_COMPOSITION_PATTERN.findAll(line).forEach { match ->
            when (classifySurfaceFlingerComposition(match.value)) {
                SurfaceFlingerComposition.DEVICE -> structuredBlockDevice = true
                SurfaceFlingerComposition.CLIENT -> structuredBlockClient = true
                null -> Unit
            }
        }
    }

    private fun finishStructuredBlock() {
        if (!structuredBlockOpen) return
        if (structuredBlockIsApp) {
            val composition = when {
                structuredBlockInvalid -> null
                structuredBlockDevice == structuredBlockClient -> null
                structuredBlockDevice -> SurfaceFlingerComposition.DEVICE
                else -> SurfaceFlingerComposition.CLIENT
            }
            recordAppLayer(
                format = SurfaceFlingerRecordFormat.STRUCTURED_BLOCK,
                composition = composition,
            )
        }
        structuredBlockOpen = false
        structuredBlockIsApp = false
        structuredBlockInvalid = false
        structuredBlockDevice = false
        structuredBlockClient = false
    }

    private fun finishPendingTableName() {
        if (pendingTableNameIsApp) {
            recordAppLayer(
                format = SurfaceFlingerRecordFormat.AOSP_MINIDUMP,
                composition = null,
            )
        }
        pendingTableName = false
        pendingTableNameIsApp = false
    }

    private fun recordAppLayer(
        format: SurfaceFlingerRecordFormat,
        composition: SurfaceFlingerComposition?,
    ) {
        appTokenObserved = true
        observeFormat(format)
        appRecordCount += 1
        when (composition) {
            SurfaceFlingerComposition.DEVICE -> deviceLayers += 1
            SurfaceFlingerComposition.CLIENT -> clientLayers += 1
            null -> classificationComplete = false
        }
    }

    private fun observeFormat(format: SurfaceFlingerRecordFormat) {
        val previous = recordFormat
        if (previous == null) {
            recordFormat = format
        } else if (previous != format) {
            mixedRecordFormats = true
        }
    }
}

private fun parseSurfaceFlingerTableRow(
    line: String,
    compositionColumnIndex: Int,
): SurfaceFlingerTableRow? {
    if (!SURFACE_FLINGER_TABLE_ROW_PREFIX_PATTERN.containsMatchIn(line)) return null
    return SurfaceFlingerTableRow(
        composition = classifySurfaceFlingerPipeField(
            line = line,
            targetColumnIndex = compositionColumnIndex,
        ),
    )
}

private fun parseSurfaceFlingerExplicitComposition(line: String): SurfaceFlingerComposition? {
    val matches = SURFACE_FLINGER_EXPLICIT_COMPOSITION_PATTERN.findAll(line).iterator()
    if (!matches.hasNext()) return null
    val match = matches.next()
    // A same-line legacy record has exactly one explicit composition property. Stop as soon as a
    // second property appears so a bounded dump cannot amplify into an unbounded MatchResult list.
    if (matches.hasNext()) return null
    val explicitComposition =
        classifySurfaceFlingerComposition(match.groupValues[1]) ?: return null
    var hasDevice = explicitComposition == SurfaceFlingerComposition.DEVICE
    var hasClient = explicitComposition == SurfaceFlingerComposition.CLIENT

    // Tokens before the first explicit property belong to the same-line layer name. Only a second
    // token after the final property can make the classification ambiguous.
    SURFACE_FLINGER_BARE_COMPOSITION_PATTERN
        .findAll(line, match.range.last + 1)
        .forEach { trailingMatch ->
        when (classifySurfaceFlingerComposition(trailingMatch.value)) {
            SurfaceFlingerComposition.DEVICE -> hasDevice = true
            SurfaceFlingerComposition.CLIENT -> hasClient = true
            null -> Unit
        }
        if (hasDevice && hasClient) return null
    }
    return when {
        hasDevice == hasClient -> null
        hasDevice -> SurfaceFlingerComposition.DEVICE
        else -> SurfaceFlingerComposition.CLIENT
    }
}

/**
 * Finds the sole pipe-delimited `Comp Type` header without splitting a potentially hostile line.
 *
 * A missing or duplicate column is invalid. The row parser then records the app layer as
 * unclassified instead of borrowing a DEVICE/CLIENT-looking token from another vendor column.
 */
private fun findUniqueSurfaceFlingerCompositionColumnIndex(line: String): Int {
    var fieldStart = 0
    var fieldIndex = 0
    var matchIndex = INVALID_SURFACE_FLINGER_COLUMN_INDEX
    var cursor = 0
    while (cursor <= line.length) {
        if (cursor == line.length || line[cursor] == '|') {
            if (
                surfaceFlingerPipeFieldEquals(
                    line = line,
                    startIndex = fieldStart,
                    endIndexExclusive = cursor,
                    expected = "Comp Type",
                )
            ) {
                if (matchIndex != INVALID_SURFACE_FLINGER_COLUMN_INDEX) {
                    return INVALID_SURFACE_FLINGER_COLUMN_INDEX
                }
                matchIndex = fieldIndex
            }
            fieldIndex += 1
            fieldStart = cursor + 1
        }
        cursor += 1
    }
    return matchIndex
}

private fun classifySurfaceFlingerPipeField(
    line: String,
    targetColumnIndex: Int,
): SurfaceFlingerComposition? {
    if (targetColumnIndex < 0) return null
    var fieldStart = 0
    var fieldIndex = 0
    var cursor = 0
    while (cursor <= line.length) {
        if (cursor == line.length || line[cursor] == '|') {
            if (fieldIndex == targetColumnIndex) {
                return classifySurfaceFlingerCompositionField(
                    line = line,
                    startIndex = fieldStart,
                    endIndexExclusive = cursor,
                )
            }
            fieldIndex += 1
            fieldStart = cursor + 1
        }
        cursor += 1
    }
    return null
}

private fun classifySurfaceFlingerCompositionField(
    line: String,
    startIndex: Int,
    endIndexExclusive: Int,
): SurfaceFlingerComposition? {
    var trimmedStart = startIndex.coerceIn(0, line.length)
    var trimmedEnd = endIndexExclusive.coerceIn(trimmedStart, line.length)
    while (trimmedStart < trimmedEnd && line[trimmedStart].isWhitespace()) {
        trimmedStart += 1
    }
    while (trimmedEnd > trimmedStart && line[trimmedEnd - 1].isWhitespace()) {
        trimmedEnd -= 1
    }
    return when {
        surfaceFlingerRegionEquals(line, trimmedStart, trimmedEnd, "CLIENT") ->
            SurfaceFlingerComposition.CLIENT
        SURFACE_FLINGER_DEVICE_COMPOSITION_TOKENS.any { token ->
            surfaceFlingerRegionEquals(line, trimmedStart, trimmedEnd, token)
        } -> SurfaceFlingerComposition.DEVICE
        else -> null
    }
}

private fun surfaceFlingerPipeFieldEquals(
    line: String,
    startIndex: Int,
    endIndexExclusive: Int,
    expected: String,
): Boolean {
    var trimmedStart = startIndex.coerceIn(0, line.length)
    var trimmedEnd = endIndexExclusive.coerceIn(trimmedStart, line.length)
    while (trimmedStart < trimmedEnd && line[trimmedStart].isWhitespace()) {
        trimmedStart += 1
    }
    while (trimmedEnd > trimmedStart && line[trimmedEnd - 1].isWhitespace()) {
        trimmedEnd -= 1
    }
    return surfaceFlingerRegionEquals(
        line = line,
        startIndex = trimmedStart,
        endIndexExclusive = trimmedEnd,
        expected = expected,
    )
}

private fun surfaceFlingerRegionEquals(
    line: String,
    startIndex: Int,
    endIndexExclusive: Int,
    expected: String,
): Boolean =
    endIndexExclusive - startIndex == expected.length &&
        line.regionMatches(
            thisOffset = startIndex,
            other = expected,
            otherOffset = 0,
            length = expected.length,
            ignoreCase = true,
        )

private fun classifySurfaceFlingerComposition(value: String): SurfaceFlingerComposition? =
    when (value.trim().uppercase()) {
        "CLIENT" -> SurfaceFlingerComposition.CLIENT
        "DEVICE",
        "SOLID_COLOR",
        "CURSOR",
        "SIDEBAND",
        "DISPLAY_DECORATION",
        "REFRESH_RATE_INDICATOR",
        -> SurfaceFlingerComposition.DEVICE
        else -> null
    }

private fun isSurfaceFlingerTableSeparator(line: String): Boolean {
    var hasDash = false
    line.forEach { character ->
        when {
            character == '-' -> hasDash = true
            character.isWhitespace() -> Unit
            else -> return false
        }
    }
    return hasDash
}

private fun sanitizeSurfaceFlingerDisplayLabel(label: String?): String {
    val source = label.orEmpty()
    val result = StringBuilder(minOf(source.length, SURFACE_FLINGER_DISPLAY_LABEL_MAX_CHARS))
    source.forEach { character ->
        if (result.length >= SURFACE_FLINGER_DISPLAY_LABEL_MAX_CHARS) return@forEach
        result.append(
            if (
                character in 'a'..'z' ||
                character in 'A'..'Z' ||
                character in '0'..'9' ||
                character == '.' ||
                character == '_' ||
                character == ':' ||
                character == '-'
            ) {
                character
            } else {
                '_'
            },
        )
    }
    return result.toString().ifBlank { "unknown" }
}

private fun containsDpuLabToken(line: String): Boolean =
    SURFACE_FLINGER_APP_TOKENS.any { token ->
        line.contains(token, ignoreCase = true)
    }

// Keep legacy tokens because package/vendor integration identifiers intentionally remain stable
// across the user-facing DPULayerTest rename.
private val SURFACE_FLINGER_APP_TOKENS =
    arrayOf("dpulayerlab", "DpuLab", "DPU Layer Lab", "DPULayerTest")
private val SURFACE_FLINGER_DISPLAY_HEADER_PATTERN = Regex(
    """^\s*Display\s+(.+?)(?:\s+\((active|inactive)\))?\s+HWC\s+layers:\s*$""",
    RegexOption.IGNORE_CASE,
)
private val SURFACE_FLINGER_DISPLAY_MARKER_PATTERN =
    Regex("""\bDisplay\b.*\bHWC\s+layers\b""", RegexOption.IGNORE_CASE)
private val SURFACE_FLINGER_LAYER_BLOCK_PATTERN =
    Regex("""^\s*\*\s+Layer\b""", RegexOption.IGNORE_CASE)
private val SURFACE_FLINGER_TABLE_HEADER_PATTERN =
    Regex("""(?:^|\|)\s*Comp\s+Type\s*(?:\||$)""", RegexOption.IGNORE_CASE)
private val SURFACE_FLINGER_TABLE_ROW_PREFIX_PATTERN = Regex(
    """^\s*(?:rel\s+)?-?\d+\s*\|""",
    RegexOption.IGNORE_CASE,
)
private val SURFACE_FLINGER_EXPLICIT_COMPOSITION_PATTERN = Regex(
    """\b(?:composition\s*type|compositionType|comp\s*type)\s*[:=]\s*([A-Z][A-Z0-9_]*)""",
    RegexOption.IGNORE_CASE,
)
private val SURFACE_FLINGER_BARE_COMPOSITION_PATTERN = Regex(
    """\b(?:DEVICE|CLIENT|SOLID_COLOR|CURSOR|SIDEBAND|DISPLAY_DECORATION|REFRESH_RATE_INDICATOR)\b""",
    RegexOption.IGNORE_CASE,
)
private val SURFACE_FLINGER_HWC_MISSED_PATTERN =
    Regex("""HWC missed frame count:\s*(\d+)""", RegexOption.IGNORE_CASE)
private val SURFACE_FLINGER_GPU_MISSED_PATTERN =
    Regex("""GPU missed frame count:\s*(\d+)""", RegexOption.IGNORE_CASE)
private const val SURFACE_FLINGER_DISPLAY_LABEL_MAX_CHARS = 64
private const val SURFACE_FLINGER_MAX_DISPLAY_SECTIONS = 16
private const val INVALID_SURFACE_FLINGER_COLUMN_INDEX = -1
private val SURFACE_FLINGER_DEVICE_COMPOSITION_TOKENS = arrayOf(
    "DEVICE",
    "SOLID_COLOR",
    "CURSOR",
    "SIDEBAND",
    "DISPLAY_DECORATION",
    "REFRESH_RATE_INDICATOR",
)
