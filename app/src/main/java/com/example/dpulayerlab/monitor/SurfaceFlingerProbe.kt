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

data class CompositionSnapshot(
    val deviceLayers: Int? = null,
    val clientLayers: Int? = null,
    val hwcMissedFrames: Long? = null,
    val gpuMissedFrames: Long? = null,
    val source: String = "",
    val detail: String = "",
)

class SurfaceFlingerProbe(context: Context) : AutoCloseable {
    // A timed-out worker can outlive the Activity. Never let that process-owned diagnostic lane
    // retain a Window or its complete View hierarchy.
    private val context = context.applicationContext
    private val laneLease = processProbeLanes.acquire()

    fun hasDumpPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED

    fun sample(): CompositionSnapshot {
        if (!hasDumpPermission()) return CompositionSnapshot(detail = "DUMP 권한 없음")
        if (!processChildren.isRestartSafe()) {
            return CompositionSnapshot(detail = "이전 SurfaceFlinger process 종료 확인 대기 중")
        }
        return when (val result = laneLease.execute()) {
            is ProbeLaneResult.Completed -> result.value
            ProbeLaneResult.Busy ->
                CompositionSnapshot(detail = "이전 SurfaceFlinger probe 종료 대기 중")
            ProbeLaneResult.Closed ->
                CompositionSnapshot(detail = "SurfaceFlinger probe 종료됨")
            ProbeLaneResult.Cancelled ->
                CompositionSnapshot(detail = "SurfaceFlinger probe 중단됨")
            ProbeLaneResult.Interrupted ->
                CompositionSnapshot(detail = "SurfaceFlinger probe 중단됨")
            ProbeLaneResult.TimedOut ->
                CompositionSnapshot(detail = "SurfaceFlinger 응답 시간 초과")
            is ProbeLaneResult.Failed ->
                CompositionSnapshot(
                    detail = "SurfaceFlinger probe 실패: ${result.error.javaClass.simpleName}",
                )
        }
    }

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
        val laneStopped = laneLease.closeWithResult()
        // Always inspect the child gate even when the lane stop failed. A process which exited
        // after the bounded lane wait can then release its process reference deterministically.
        val childStopped = processChildren.isRestartSafe()
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
            return try {
                if (
                    !cancellation.registerCleanup {
                        // Keep the waiting caller's cancellation path non-blocking. The worker
                        // owns stream close/wait in finally after destroy wakes the pipe reader.
                        if (process.isAlive) runCatching { process.destroyForcibly() }
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
                parseSurfaceFlingerDump(text)
            } finally {
                if (
                    !cleanupProbeProcess(
                        process = process,
                        waitMs = PROCESS_CLEANUP_WAIT_MS,
                        registry = processChildren,
                    )
                ) {
                    throw IllegalStateException(
                        "SurfaceFlinger process termination was not confirmed",
                    )
                }
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
        cleanupAction.getAndSet(null)?.let { action ->
            runCatching(action)
        }
    }
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
    operation: (ProbeCancellation) -> T,
) : AutoCloseable {
    private val delegate = SingleFlightInputProbeLane<Unit, T>(
        threadName = threadName,
        timeoutMs = timeoutMs,
        shutdownTimeoutMs = shutdownTimeoutMs,
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
            Thread(runnable, threadName).apply { isDaemon = true }
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
            task.finishWithoutRun()
            if (error is ThreadDeath) throw error
            return if (closed.get() || error is RejectedExecutionException) {
                if (closed.get()) ProbeLaneResult.Closed else ProbeLaneResult.Busy
            } else {
                ProbeLaneResult.Failed(error)
            }
        }

        return try {
            task.result.get(effectiveTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            task.cancel()
            ProbeLaneResult.TimedOut
        } catch (_: InterruptedException) {
            task.cancel()
            Thread.currentThread().interrupt()
            ProbeLaneResult.Interrupted
        } catch (error: ExecutionException) {
            ProbeLaneResult.Failed(error.cause ?: error)
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
        task?.cancel()
        // A close can race the executor hand-off after execute(task) accepted the Runnable but
        // before the worker called run(). shutdownNow returns that never-started task; publish its
        // actual completion explicitly or the single-flight gate would remain sticky forever.
        val neverStartedTasks = executor.shutdownNow()
        task?.let { pendingTask ->
            if (neverStartedTasks.any { it === pendingTask }) {
                pendingTask.finishWithoutRun()
            }
        }

        val taskStopped = task?.awaitActualCompletion(deadlineNanos) ?: !active.get()
        val executorStopped = awaitExecutorTermination(deadlineNanos)
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
            runner.set(Thread.currentThread())
            var outcome: ProbeLaneResult<T> = ProbeLaneResult.Cancelled
            var threadDeath: ThreadDeath? = null
            try {
                outcome = if (cancellation.isCancellationRequested()) {
                    ProbeLaneResult.Cancelled
                } else {
                    ProbeLaneResult.Completed(operation(input, cancellation))
                }
            } catch (error: Throwable) {
                if (error is ThreadDeath) threadDeath = error
                outcome = if (
                    cancellation.isCancellationRequested() ||
                    error is InterruptedException
                ) {
                    ProbeLaneResult.Cancelled
                } else {
                    ProbeLaneResult.Failed(error)
                }
            } finally {
                cancellation.cleanup()
                runner.set(null)
                publishActualCompletion()
                result.complete(outcome)
            }
            threadDeath?.let { throw it }
        }

        fun cancel() {
            // Publish cancellation before releasing a blocking resource. Otherwise that resource
            // can wake and race a stale successful result ahead of the close/timeout boundary.
            result.complete(ProbeLaneResult.Cancelled)
            cancellation.cancel()
            runner.get()?.interrupt()
        }

        fun finishWithoutRun() {
            cancel()
            publishActualCompletion()
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

        val stopped = target.closeWithResult()
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
    if (process.isAlive) {
        runCatching { process.destroyForcibly() }
    }
    runCatching { process.inputStream.close() }
    runCatching { process.errorStream.close() }
    runCatching { process.outputStream.close() }
    runCatching {
        process.waitFor(waitMs, TimeUnit.MILLISECONDS)
    }
    if (process.isAlive) {
        runCatching { process.destroyForcibly() }
        // A second wait is required after the final kill request. Without it the worker can
        // publish completion while the old dumpsys process still owns its pipe/native state.
        runCatching {
            process.waitFor(waitMs, TimeUnit.MILLISECONDS)
        }
    }
    return if (process.isAlive) {
        registry.record(process)
        // The process can exit between the final observation and registration. Re-read through
        // the registry so that race is accepted only when death is explicitly observed.
        registry.isRestartSafe()
    } else {
        registry.clear(process)
        true
    }
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
    var appBlockCount = 0
    var appBlockDeviceCount = 0
    var appBlockClientCount = 0
    var appBlockClassificationComplete = true
    var currentAppBlock = false
    var currentBlockHasDevice = false
    var currentBlockHasClient = false
    var fallbackAppLineCount = 0
    var fallbackDeviceCount = 0
    var fallbackClientCount = 0
    var fallbackClassificationComplete = true
    var hwcMissed: Long? = null
    var gpuMissed: Long? = null

    fun finishBlock() {
        if (!currentAppBlock) return
        appBlockCount += 1
        if (currentBlockHasDevice) appBlockDeviceCount += 1
        if (currentBlockHasClient) appBlockClientCount += 1
        if (currentBlockHasDevice == currentBlockHasClient) {
            // Neither classification means the vendor format is unknown; both means the block is
            // ambiguous. In either case zero/partial counts would be fabricated evidence.
            appBlockClassificationComplete = false
        }
    }

    // One bounded pass avoids split/filter/toList copies of a dump that may be almost 4 MiB.
    // The block counters retain the old "one count per matching * Layer block" semantics, while
    // fallback counters retain the legacy line-oriented parser for vendor formats without blocks.
    text.lineSequence().forEach { line ->
        val startsLayerBlock = line.startsWith("* Layer ")
        if (startsLayerBlock) {
            finishBlock()
            currentAppBlock = containsDpuLabToken(line)
            currentBlockHasDevice = false
            currentBlockHasClient = false
        }

        val appLine = containsDpuLabToken(line)
        if (appLine) {
            fallbackAppLineCount += 1
        }
        val hasDevice = SURFACE_FLINGER_DEVICE_PATTERN.containsMatchIn(line)
        val hasClient = SURFACE_FLINGER_CLIENT_PATTERN.containsMatchIn(line)
        if (currentAppBlock && !startsLayerBlock) {
            // DEVICE/CLIENT tokens in an app-controlled layer name are not composition evidence.
            // Structured block dumps must expose the classification in a following property row.
            currentBlockHasDevice = currentBlockHasDevice || hasDevice
            currentBlockHasClient = currentBlockHasClient || hasClient
        }
        if (appLine) {
            if (hasDevice) fallbackDeviceCount += 1
            if (hasClient) fallbackClientCount += 1
            if (hasDevice == hasClient) fallbackClassificationComplete = false
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
    finishBlock()

    val hasAppBlocks = appBlockCount > 0
    val device = if (hasAppBlocks) appBlockDeviceCount else fallbackDeviceCount
    val client = if (hasAppBlocks) appBlockClientCount else fallbackClientCount
    val matchedCount = if (hasAppBlocks) appBlockCount else fallbackAppLineCount
    val classificationComplete = if (hasAppBlocks) {
        appBlockClassificationComplete
    } else {
        fallbackClassificationComplete
    }
    return CompositionSnapshot(
        deviceLayers = device.takeIf { matchedCount > 0 && classificationComplete },
        clientLayers = client.takeIf { matchedCount > 0 && classificationComplete },
        hwcMissedFrames = hwcMissed,
        gpuMissedFrames = gpuMissed,
        source = source,
        detail = if (matchedCount > 0 && classificationComplete) {
            "$matchedCount app layers parsed"
        } else if (matchedCount > 0) {
            "$matchedCount app layers found; composition classification unavailable"
        } else {
            "앱 layer가 dump에 노출되지 않음"
        },
    )
}

private fun containsDpuLabToken(line: String): Boolean =
    SURFACE_FLINGER_APP_TOKENS.any { token ->
        line.contains(token, ignoreCase = true)
    }

// Keep legacy tokens because package/vendor integration identifiers intentionally remain stable
// across the user-facing DPULayerTest rename.
private val SURFACE_FLINGER_APP_TOKENS =
    arrayOf("dpulayerlab", "DpuLab", "DPU Layer Lab", "DPULayerTest")
private val SURFACE_FLINGER_DEVICE_PATTERN = Regex(
    """composition\s+type=(DEVICE|SOLID_COLOR|CURSOR|SIDEBAND)|\b(DEVICE|SOLID_COLOR|CURSOR|SIDEBAND)\b""",
    RegexOption.IGNORE_CASE,
)
private val SURFACE_FLINGER_CLIENT_PATTERN =
    Regex("""composition\s+type=CLIENT|\bCLIENT\b""", RegexOption.IGNORE_CASE)
private val SURFACE_FLINGER_HWC_MISSED_PATTERN =
    Regex("""HWC missed frame count:\s*(\d+)""", RegexOption.IGNORE_CASE)
private val SURFACE_FLINGER_GPU_MISSED_PATTERN =
    Regex("""GPU missed frame count:\s*(\d+)""", RegexOption.IGNORE_CASE)
