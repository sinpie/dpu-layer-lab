package com.example.dpulayerlab.engine

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import com.example.dpulayerlab.model.LoadShapeEvaluator
import com.example.dpulayerlab.model.LoadSetpoints
import com.example.dpulayerlab.model.LoadShape
import com.example.dpulayerlab.vendor.VendorBridge
import java.lang.reflect.Method
import java.util.IdentityHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Controllable, bounded load generators. They intentionally use duty cycles so every scenario can
 * include an observable "bus acquired → released" transition without leaving runaway workers.
 */
class LoadManager private constructor(
    dependencies: LoadManagerDependencies,
) : AutoCloseable {
    constructor(context: Context) : this(loadManagerDependencies(context))

    internal constructor(npuAdapter: NpuWorkloadAdapter) : this(
        LoadManagerDependencies(
            npuAdapter = npuAdapter,
            cpuWorkerCount = 0,
            memoryWorkerCount = 0,
            memoryWorkingSetBytes = 0,
            monotonicNowMs = { 0L },
            workerStarter = Thread::start,
        ),
    )

    internal constructor(
        npuAdapter: NpuWorkloadAdapter,
        cpuWorkerCount: Int,
        memoryWorkerCount: Int,
        memoryWorkingSetBytes: Int,
        workerStarter: (Thread) -> Unit,
    ) : this(
        LoadManagerDependencies(
            npuAdapter = npuAdapter,
            cpuWorkerCount = cpuWorkerCount,
            memoryWorkerCount = memoryWorkerCount,
            memoryWorkingSetBytes = memoryWorkingSetBytes,
            monotonicNowMs = { 0L },
            workerStarter = workerStarter,
        ),
    )

    private val npuAdapter = dependencies.npuAdapter
    private val cpuWorkerCount = dependencies.cpuWorkerCount
    private val memoryWorkerCount = dependencies.memoryWorkerCount
    private val memoryWorkingSetBytes = dependencies.memoryWorkingSetBytes
    private val monotonicNowMs = dependencies.monotonicNowMs
    private val workerStarter = dependencies.workerStarter
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val runtimeConfig = AtomicReference(
        RuntimeLoadConfig(
            setpoints = LoadSetpoints(),
            phaseStartedMs = monotonicNowMs(),
        ),
    )
    private val threads = ConcurrentLinkedQueue<Thread>()
    private val copiedBytes = AtomicLong(0)
    private val memoryAllocationFailed = AtomicBoolean(false)
    private val memoryBufferDropGeneration = AtomicLong(0L)
    private val shutdownResult = AtomicReference<LoadShutdownResult?>(null)
    private val localWorkerOwner = Any()
    private val localWorkerStateLock = Any()

    @Synchronized
    fun start(): Boolean {
        if (closed.get()) return false
        synchronized(localWorkerStateLock) {
            if (LoadSafetyState.localWorkerFailure() != null) return false
            if (running.get()) {
                val activeWorkers = threads.toList()
                return activeWorkers.size == cpuWorkerCount + memoryWorkerCount &&
                    activeWorkers.all(Thread::isAlive)
            }
        }
        if (!LoadSafetyState.tryAcquireLocalWorkerLease(localWorkerOwner)) return false
        val startAllowed = synchronized(localWorkerStateLock) {
            if (closed.get() || LoadSafetyState.localWorkerFailure() != null) {
                false
            } else {
                running.set(true)
                true
            }
        }
        if (!startAllowed) {
            LoadSafetyState.releaseLocalWorkerLeaseIfEmpty(localWorkerOwner)
            return false
        }
        memoryAllocationFailed.set(false)
        val pendingWorkers = try {
            ArrayList<Thread>(cpuWorkerCount + memoryWorkerCount).apply {
                repeat(cpuWorkerCount) { index ->
                    add(createWorker("DpuLab-CPU-$index") { cpuCycle(index) })
                }
                repeat(memoryWorkerCount) { index ->
                    add(createWorker("DpuLab-Memory-$index") { memoryCycle(index) })
                }
            }
        } catch (_: OutOfMemoryError) {
            memoryAllocationFailed.set(true)
            markLocalWorkersStopping()
            LoadSafetyState.releaseLocalWorkerLeaseIfEmpty(localWorkerOwner)
            return false
        } catch (_: Exception) {
            markLocalWorkersStopping()
            LoadSafetyState.releaseLocalWorkerLeaseIfEmpty(localWorkerOwner)
            return false
        }
        if (
            pendingWorkers.any {
                !LoadSafetyState.registerLocalWorker(localWorkerOwner, it)
            }
        ) {
            markLocalWorkersStopping()
            pendingWorkers.forEach {
                LoadSafetyState.recordLocalWorkerStopped(localWorkerOwner, it)
            }
            LoadSafetyState.releaseLocalWorkerLeaseIfEmpty(localWorkerOwner)
            return false
        }
        threads.addAll(pendingWorkers)
        return runCatching {
            pendingWorkers.forEach(workerStarter)
            // Test/product variants with no local workers must not retain the process-wide lease.
            LoadSafetyState.releaseLocalWorkerLeaseIfEmpty(localWorkerOwner)
            synchronized(localWorkerStateLock) {
                running.get() && LoadSafetyState.localWorkerFailure() == null
            }
        }.getOrElse { error ->
            if (error is OutOfMemoryError) memoryAllocationFailed.set(true)
            cleanupPartiallyStartedWorkers(pendingWorkers)
            false
        }
    }

    @Synchronized
    fun apply(newSetpoints: LoadSetpoints, restartProfile: Boolean = true) {
        if (closed.get()) return
        val normalized = newSetpoints.normalized()
        val previousConfig = runtimeConfig.get()
        val previous = previousConfig.setpoints
        val profileRestarted = restartProfile || normalized.shape != previous.shape
        runtimeConfig.set(
            RuntimeLoadConfig(
                setpoints = normalized,
                phaseStartedMs = if (profileRestarted) {
                    monotonicNowMs()
                } else {
                    previousConfig.phaseStartedMs
                },
            ),
        )
        if (
            profileRestarted ||
            normalized.cpu != previous.cpu ||
            normalized.memory != previous.memory
        ) {
            threads.forEach(LockSupport::unpark)
        }
        if (
            restartProfile ||
            normalized.shape != previous.shape ||
            normalized.npu != previous.npu
        ) {
            val composite = npuAdapter as? CompositeNpuWorkloadAdapter
            if (composite != null) {
                composite.applyLoad(
                    intensity = normalized.npu,
                    newShape = normalized.shape,
                    restartProfile = profileRestarted,
                )
            } else {
                npuAdapter.setIntensity(normalized.npu)
            }
        }
    }

    fun releaseLoads(dropMemoryBuffers: Boolean = false) {
        apply(LoadSetpoints())
        if (dropMemoryBuffers) {
            memoryBufferDropGeneration.incrementAndGet()
            threads.forEach(LockSupport::unpark)
        }
    }

    /**
     * Run-boundary release. Unlike [releaseLoads], this does not treat enqueueing NPU zero as
     * completion; every backend that may have been active must acknowledge an ordered zero.
     */
    @Synchronized
    fun releaseLoadsAndConfirm(dropMemoryBuffers: Boolean = false): Boolean {
        if (closed.get()) {
            return shutdownResult.get()?.npu?.releaseConfirmed == true
        }
        releaseLoads(dropMemoryBuffers)
        val confirmed = npuAdapter.releaseAndConfirm()
        // closeWithResult uses the same monitor. Therefore either this publication completes
        // before close can return its final result to the controller, or a close-first caller
        // observes closed=true and cannot overwrite the controller's final rescue publication.
        if (!closed.get()) {
            LoadSafetyState.recordNpuLoadIdle(confirmed)
        }
        return confirmed
    }

    fun sampleAndResetBandwidthBytes(): Long = copiedBytes.getAndSet(0)

    fun npuStatus(vendorSnapshotStatus: String? = null): String =
        (npuAdapter as? CompositeNpuWorkloadAdapter)
            ?.status(vendorSnapshotStatus)
            ?: npuAdapter.status()

    fun hasNpuAdapter(): Boolean = npuAdapter.isAvailable()

    fun hasMemoryAllocationFailure(): Boolean = memoryAllocationFailed.get()

    fun localWorkerFailure(): LocalWorkerFailure? = LoadSafetyState.localWorkerFailure()

    fun clearMemoryAllocationFailure() {
        memoryAllocationFailed.set(false)
        threads.forEach(LockSupport::unpark)
    }

    @Synchronized
    fun closeWithResult(): LoadShutdownResult {
        shutdownResult.get()?.let { return it }
        if (!closed.compareAndSet(false, true)) {
            return shutdownResult.get() ?: LoadShutdownResult(
                workersStopped = false,
                npu = NpuShutdownResult.unconfirmed("동시 종료 결과를 확인할 수 없음"),
            )
        }
        markLocalWorkersStopping()
        runtimeConfig.set(
            RuntimeLoadConfig(
                setpoints = LoadSetpoints(),
                phaseStartedMs = monotonicNowMs(),
            ),
        )
        val activeThreads = threads.toList()
        activeThreads.forEach { it.interrupt() }
        val npuResult = runCatching {
            npuAdapter.closeWithResult()
        }.getOrElse { error ->
            NpuShutdownResult.unconfirmed(
                "NPU adapter 종료 예외: ${error.javaClass.simpleName}",
            )
        }
        val joinDeadlineNanos = System.nanoTime() + WORKER_JOIN_TIMEOUT_NANOS
        activeThreads.forEach { thread ->
            val remainingNanos = joinDeadlineNanos - System.nanoTime()
            if (remainingNanos > 0L && thread.isAlive) {
                val millis = remainingNanos / NANOS_PER_MILLI
                val nanos = (remainingNanos % NANOS_PER_MILLI).toInt()
                try {
                    thread.join(millis, nanos)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@forEach
                }
            }
        }
        val workersStopped = activeThreads.none(Thread::isAlive)
        threads.clear()
        LoadSafetyState.releaseLocalWorkerLeaseIfEmpty(localWorkerOwner)
        return LoadShutdownResult(
            workersStopped = workersStopped,
            npu = npuResult,
        ).also(shutdownResult::set)
    }

    override fun close() {
        closeWithResult()
    }

    private fun createWorker(name: String, cycle: () -> Unit): Thread {
        lateinit var workerThread: Thread
        workerThread = Thread({
            try {
                while (running.get()) {
                    try {
                        cycle()
                    } catch (error: InterruptedException) {
                        // close()/partial-start cleanup publishes running=false before interrupt.
                        // Any interruption while the manager still owns a live run is external or
                        // otherwise unexpected and permanently invalidates workload fidelity.
                        stopLocalWorkersAfterUnexpectedFailure(name, error)
                        break
                    } catch (_: OutOfMemoryError) {
                        memoryAllocationFailed.set(true)
                        break
                    } catch (error: Exception) {
                        stopLocalWorkersAfterUnexpectedFailure(name, error)
                        break
                    } catch (error: Throwable) {
                        // AssertionError/LinkageError and other non-VM-safe failures must not
                        // make one workload silently disappear while the run continues. Record
                        // and stop siblings, then preserve fatal/Error semantics on this thread.
                        stopLocalWorkersAfterUnexpectedFailure(name, error)
                        throw error
                    }
                }
            } finally {
                threads.remove(workerThread)
                LoadSafetyState.recordLocalWorkerStopped(localWorkerOwner, workerThread)
            }
        }, name).apply {
            priority = Thread.NORM_PRIORITY
        }
        return workerThread
    }

    private fun markLocalWorkersStopping() {
        synchronized(localWorkerStateLock) {
            running.set(false)
        }
    }

    private fun cleanupPartiallyStartedWorkers(pendingWorkers: List<Thread>) {
        // Publish running=false before interrupting. A same-owner retry is denied by
        // LoadSafetyState while any registered worker remains, so an old interrupt handler can
        // never observe a new run's running=true and poison the permanent failure latch.
        markLocalWorkersStopping()
        pendingWorkers.forEach { thread ->
            when (thread.state) {
                Thread.State.NEW -> {
                    threads.remove(thread)
                    LoadSafetyState.recordLocalWorkerStopped(localWorkerOwner, thread)
                }
                else -> {
                    thread.interrupt()
                    LockSupport.unpark(thread)
                }
            }
        }
        val joinDeadlineNanos = System.nanoTime() + WORKER_JOIN_TIMEOUT_NANOS
        pendingWorkers.forEach { thread ->
            val remainingNanos = joinDeadlineNanos - System.nanoTime()
            if (remainingNanos > 0L && thread.isAlive) {
                val millis = remainingNanos / NANOS_PER_MILLI
                val nanos = (remainingNanos % NANOS_PER_MILLI).toInt()
                try {
                    thread.join(millis, nanos)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@forEach
                }
            }
        }
        LoadSafetyState.releaseLocalWorkerLeaseIfEmpty(localWorkerOwner)
    }

    private fun stopLocalWorkersAfterUnexpectedFailure(
        workerName: String,
        error: Throwable,
    ) {
        val failureRecorded = synchronized(localWorkerStateLock) {
            if (!running.get()) {
                false
            } else {
                LoadSafetyState.recordLocalWorkerFailure(workerName, error)
                running.set(false)
                true
            }
        }
        if (!failureRecorded) return
        val current = Thread.currentThread()
        threads.forEach { worker ->
            if (worker !== current) worker.interrupt()
            LockSupport.unpark(worker)
        }
    }

    private fun cpuCycle(workerIndex: Int) {
        val runtime = runtimeConfig.get()
        val config = runtime.setpoints
        val intensity = shapedIntensity(config.cpu, config.shape, runtime.phaseStartedMs)
        if (intensity <= 0.001f) {
            LockSupport.parkNanos(
                if (config.cpu <= 0.001f) {
                    IDLE_WORKER_PARK_NANOS
                } else {
                    WAVEFORM_CONTROL_PARK_NANOS
                },
            )
            throwIfUnexpectedWorkerInterrupt()
            return
        }
        val cycleStart = System.nanoTime()
        val busyDeadline = cycleStart + (CPU_PERIOD_NANOS * intensity).toLong()
        var x = 0.17 + workerIndex * 0.013
        var y = 0.73 + workerIndex * 0.021
        while (running.get() && System.nanoTime() < busyDeadline) {
            // Keep the batch small so low setpoints and ramp edges do not overshoot the
            // requested duty cycle by a long transcendental-function batch.
            repeat(CPU_BATCH_SIZE) { iteration ->
                x = sin(x * 1.000003 + iteration * 0.00007) + cos(y * 0.999997)
                y = cos(y + x * 0.00031) - sin(x * 0.71)
            }
        }
        blackHole = x + y
        parkUntil(cycleStart + CPU_PERIOD_NANOS)
    }

    private fun memoryCycle(workerIndex: Int) {
        val workingSet = memoryWorkingSetBytes
        var source: ByteArray? = null
        var destination: ByteArray? = null
        var cursor = workerIndex * COPY_BLOCK_BYTES
        var idleSinceMs = monotonicNowMs()
        var observedDropGeneration = memoryBufferDropGeneration.get()
        while (running.get()) {
            val requestedDropGeneration = memoryBufferDropGeneration.get()
            if (requestedDropGeneration != observedDropGeneration) {
                source = null
                destination = null
                observedDropGeneration = requestedDropGeneration
                idleSinceMs = monotonicNowMs()
            }
            val runtime = runtimeConfig.get()
            val config = runtime.setpoints
            val intensity = shapedIntensity(config.memory, config.shape, runtime.phaseStartedMs)
            if (intensity <= 0.001f) {
                if (monotonicNowMs() - idleSinceMs >= IDLE_BUFFER_RELEASE_MS) {
                    source = null
                    destination = null
                }
                LockSupport.parkNanos(
                    if (config.memory <= 0.001f) {
                        IDLE_WORKER_PARK_NANOS
                    } else {
                        WAVEFORM_CONTROL_PARK_NANOS
                    },
                )
                throwIfUnexpectedWorkerInterrupt()
                continue
            }
            idleSinceMs = monotonicNowMs()
            if (memoryAllocationFailed.get()) {
                source = null
                destination = null
                LockSupport.parkNanos(MEMORY_FAILURE_RETRY_PARK_NANOS)
                throwIfUnexpectedWorkerInterrupt()
                continue
            }
            if (source == null || destination == null) {
                try {
                    // ByteArray is already zero-filled. Touch one byte per page instead of
                    // invoking a Kotlin initializer for every byte; the load under test is the
                    // bounded copy loop, not object construction.
                    source = ByteArray(workingSet)
                    destination = ByteArray(workingSet)
                    var page = 0
                    while (page < workingSet) {
                        source[page] = ((page / PAGE_TOUCH_BYTES + workerIndex * 31) and 0xff).toByte()
                        page += PAGE_TOUCH_BYTES
                    }
                } catch (_: OutOfMemoryError) {
                    source = null
                    destination = null
                    memoryAllocationFailed.set(true)
                    continue
                }
            }
            val activeSource = source
            val activeDestination = destination
            val burstStart = System.nanoTime()
            val busyDeadline = burstStart + (MEMORY_PERIOD_NANOS * intensity).toLong()
            while (running.get() && System.nanoTime() < busyDeadline) {
                if (cursor + COPY_BLOCK_BYTES > workingSet) cursor = 0
                System.arraycopy(activeSource, cursor, activeDestination, cursor, COPY_BLOCK_BYTES)
                copiedBytes.addAndGet(COPY_BLOCK_BYTES.toLong() * 2L)
                cursor += COPY_BLOCK_BYTES
            }
            memoryFence = activeDestination[(cursor - 1).coerceAtLeast(0)]
            parkUntil(burstStart + MEMORY_PERIOD_NANOS)
        }
    }

    private fun parkUntil(deadlineNanos: Long) {
        while (running.get()) {
            val remaining = deadlineNanos - System.nanoTime()
            if (remaining <= 0L) return
            LockSupport.parkNanos(remaining.coerceAtMost(MAX_CONTROL_PARK_NANOS))
            throwIfUnexpectedWorkerInterrupt()
        }
    }

    private fun throwIfUnexpectedWorkerInterrupt() {
        val interrupted = Thread.interrupted()
        if (shouldTreatLocalWorkerInterruptAsFailure(interrupted, running.get())) {
            throw InterruptedException("local load worker interrupted while active")
        }
    }

    private fun shapedIntensity(base: Float, shape: LoadShape, phaseStartedMs: Long): Float {
        val elapsedMs = monotonicNowMs() - phaseStartedMs
        return LoadShapeEvaluator.intensityAt(base, shape, elapsedMs)
    }

    private data class RuntimeLoadConfig(
        val setpoints: LoadSetpoints,
        val phaseStartedMs: Long,
    )

    companion object {
        private const val MIB = 1024L * 1024L
        private const val COPY_BLOCK_BYTES = 256 * 1024
        private const val PAGE_TOUCH_BYTES = 4 * 1024
        private const val CPU_BATCH_SIZE = 64
        private const val CPU_PERIOD_NANOS = 12_000_000L
        private const val MEMORY_PERIOD_NANOS = 10_000_000L
        private const val MAX_CONTROL_PARK_NANOS = 50_000_000L
        private const val IDLE_WORKER_PARK_NANOS = 500_000_000L
        private const val WAVEFORM_CONTROL_PARK_NANOS = 25_000_000L
        private const val MEMORY_FAILURE_RETRY_PARK_NANOS = 1_000_000_000L
        private const val IDLE_BUFFER_RELEASE_MS = 5_000L
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val WORKER_JOIN_TIMEOUT_NANOS = 500_000_000L

        @Volatile
        private var blackHole = 0.0

        @Volatile
        private var memoryFence: Byte = 0
    }
}

private data class LoadManagerDependencies(
    val npuAdapter: NpuWorkloadAdapter,
    val cpuWorkerCount: Int,
    val memoryWorkerCount: Int,
    val memoryWorkingSetBytes: Int,
    val monotonicNowMs: () -> Long,
    val workerStarter: (Thread) -> Unit,
)

private fun loadManagerDependencies(context: Context): LoadManagerDependencies {
    val activityManager = checkNotNull(
        context.getSystemService(ActivityManager::class.java),
    ) {
        "ActivityManager unavailable"
    }
    val processorCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    val memoryWorkerCount = if (activityManager.isLowRamDevice) {
        1
    } else {
        min(2, processorCount)
    }
    val mib = 1024L * 1024L
    val heapBytes = activityManager.memoryClass.toLong() * mib
    val totalArrayBudget = (heapBytes / 8L).coerceIn(8L * mib, 64L * mib)
    val workingSetBytes = (totalArrayBudget / (memoryWorkerCount * 2L))
        .coerceIn(4L * mib, 24L * mib)
        .toInt()
    return LoadManagerDependencies(
        npuAdapter = CompositeNpuWorkloadAdapter(context),
        cpuWorkerCount = (processorCount - 1).coerceIn(1, 8),
        memoryWorkerCount = memoryWorkerCount,
        memoryWorkingSetBytes = workingSetBytes,
        monotonicNowMs = SystemClock::elapsedRealtime,
        workerStarter = Thread::start,
    )
}

/**
 * Product teams can ship a class named `com.vendor.dpulayerlab.NpuStressAdapter` in the system
 * image. Expected methods: constructor(Context), setIntensity(Float), status(): String, close().
 * This keeps the portable APK honest: no NPU claim is made when a vendor/NNAPI backend is absent.
 */
interface NpuWorkloadAdapter : AutoCloseable {
    fun isAvailable(): Boolean
    fun setIntensity(intensity: Float)
    fun releaseAndConfirm(): Boolean
    fun status(): String
    fun closeWithResult(): NpuShutdownResult

    override fun close() {
        closeWithResult()
    }
}

data class LoadShutdownResult(
    val workersStopped: Boolean,
    val npu: NpuShutdownResult,
)

data class NpuShutdownResult(
    val releaseConfirmed: Boolean,
    val backendCloseConfirmed: Boolean,
    val mayRemainActive: Boolean,
    val detail: String,
) {
    companion object {
        fun unconfirmed(detail: String) = NpuShutdownResult(
            releaseConfirmed = false,
            backendCloseConfirmed = false,
            mayRemainActive = true,
            detail = detail,
        )
    }
}

data class LocalWorkerFailure(
    val workerName: String,
    val exceptionClass: String,
    val detail: String,
) {
    fun summary(): String = "$workerName · $exceptionClass: $detail"
}

internal class PermanentLocalWorkerFailureLatch {
    private val failure = AtomicReference<LocalWorkerFailure?>(null)

    fun record(workerName: String, error: Throwable): LocalWorkerFailure {
        val candidate = LocalWorkerFailure(
            workerName = boundedFailureField(workerName, MAX_WORKER_NAME_CHARS, "local-worker"),
            exceptionClass = boundedFailureField(
                error.javaClass.name,
                MAX_EXCEPTION_CLASS_CHARS,
                "java.lang.Exception",
            ),
            detail = boundedFailureField(
                runCatching { error.message }.getOrNull().orEmpty(),
                MAX_EXCEPTION_DETAIL_CHARS,
                "상세 메시지 없음",
            ),
        )
        failure.compareAndSet(null, candidate)
        return checkNotNull(failure.get())
    }

    fun peek(): LocalWorkerFailure? = failure.get()

    private fun boundedFailureField(
        value: String,
        maxChars: Int,
        fallback: String,
    ): String =
        value.lineSequence()
            .firstOrNull()
            ?.trim()
            ?.take(maxChars)
            ?.ifBlank { fallback }
            ?: fallback

    private companion object {
        const val MAX_WORKER_NAME_CHARS = 64
        const val MAX_EXCEPTION_CLASS_CHARS = 160
        const val MAX_EXCEPTION_DETAIL_CHARS = 200
    }
}

internal fun shouldTreatLocalWorkerInterruptAsFailure(
    interrupted: Boolean,
    running: Boolean,
): Boolean = interrupted && running

/**
 * Process-wide fail-closed latch. A reflection/vendor call that ignored its deadline may outlive an
 * Activity, so no subsequent load test is allowed until an ordered cleanup is confirmed.
 */
object LoadSafetyState {
    private val unconfirmedState = AtomicInteger(0)
    private val permanentLocalWorkerFailure = PermanentLocalWorkerFailureLatch()
    private val localWorkerLock = Any()
    private val localWorkerOwners = IdentityHashMap<Thread, Any>()
    private var localWorkerLeaseOwner: Any? = null

    /**
     * Compatibility entry point for callers that have one result proving both properties.
     * Run-boundary zero confirmation must use [recordNpuLoadIdle] instead.
     */
    fun recordNpuCleanup(confirmed: Boolean) {
        updateState(ALL_UNCONFIRMED, confirmed)
    }

    fun recordNpuLoadIdle(confirmed: Boolean) {
        updateState(LOAD_IDLE_UNCONFIRMED, confirmed)
    }

    fun recordNpuBackendCleanup(confirmed: Boolean) {
        updateState(BACKEND_CLEANUP_UNCONFIRMED, confirmed)
    }

    fun markNpuCleanupUnconfirmed() {
        updateState(ALL_UNCONFIRMED, confirmed = false)
    }

    fun hasUnconfirmedNpuLoadIdle(): Boolean =
        unconfirmedState.get() and LOAD_IDLE_UNCONFIRMED != 0

    fun hasUnconfirmedNpuBackendCleanup(): Boolean =
        unconfirmedState.get() and BACKEND_CLEANUP_UNCONFIRMED != 0

    fun hasUnconfirmedNpuCleanup(): Boolean = unconfirmedState.get() != 0

    fun recordLocalWorkerFailure(
        workerName: String,
        error: Throwable,
    ): LocalWorkerFailure = permanentLocalWorkerFailure.record(workerName, error)

    fun localWorkerFailure(): LocalWorkerFailure? = permanentLocalWorkerFailure.peek()

    /**
     * Claims the one process-wide local CPU/memory worker lease. A different manager cannot start
     * until every worker from the current owner has reached its terminal path.
     */
    fun tryAcquireLocalWorkerLease(owner: Any): Boolean = synchronized(localWorkerLock) {
        pruneTerminatedLocalWorkers()
        when {
            localWorkerLeaseOwner === owner ->
                localWorkerOwners.values.none { it === owner }
            localWorkerLeaseOwner != null -> false
            else -> {
                localWorkerLeaseOwner = owner
                true
            }
        }
    }

    fun registerLocalWorker(owner: Any, thread: Thread): Boolean =
        synchronized(localWorkerLock) {
            pruneTerminatedLocalWorkers()
            if (localWorkerLeaseOwner !== owner) return@synchronized false
            localWorkerOwners[thread] = owner
            true
        }

    fun recordLocalWorkerStopped(owner: Any, thread: Thread) {
        synchronized(localWorkerLock) {
            if (localWorkerOwners[thread] === owner) {
                localWorkerOwners.remove(thread)
            }
            releaseLeaseIfOwnerHasNoWorkers(owner)
        }
    }

    fun releaseLocalWorkerLeaseIfEmpty(owner: Any) {
        synchronized(localWorkerLock) {
            pruneTerminatedLocalWorkers()
            releaseLeaseIfOwnerHasNoWorkers(owner)
        }
    }

    fun hasForeignLocalWorkers(owner: Any): Boolean = synchronized(localWorkerLock) {
        pruneTerminatedLocalWorkers()
        localWorkerLeaseOwner != null && localWorkerLeaseOwner !== owner
    }

    fun hasLiveLocalWorkers(): Boolean = synchronized(localWorkerLock) {
        pruneTerminatedLocalWorkers()
        localWorkerOwners.keys.any(Thread::isAlive)
    }

    private fun updateState(mask: Int, confirmed: Boolean) {
        while (true) {
            val current = unconfirmedState.get()
            val updated = if (confirmed) {
                current and mask.inv()
            } else {
                current or mask
            }
            if (current == updated || unconfirmedState.compareAndSet(current, updated)) return
        }
    }

    private fun pruneTerminatedLocalWorkers() {
        val iterator = localWorkerOwners.entries.iterator()
        var removedTerminatedWorker = false
        while (iterator.hasNext()) {
            if (iterator.next().key.state == Thread.State.TERMINATED) {
                iterator.remove()
                removedTerminatedWorker = true
            }
        }
        val owner = localWorkerLeaseOwner
        if (removedTerminatedWorker && owner != null) {
            releaseLeaseIfOwnerHasNoWorkers(owner)
        }
    }

    private fun releaseLeaseIfOwnerHasNoWorkers(owner: Any) {
        if (
            localWorkerLeaseOwner === owner &&
            localWorkerOwners.values.none { it === owner }
        ) {
            localWorkerLeaseOwner = null
        }
    }

    private const val LOAD_IDLE_UNCONFIRMED = 1
    private const val BACKEND_CLEANUP_UNCONFIRMED = 1 shl 1
    private const val ALL_UNCONFIRMED =
        LOAD_IDLE_UNCONFIRMED or BACKEND_CLEANUP_UNCONFIRMED
}

/**
 * Per-reflection-backend load evidence. A failed later close must not erase an ordered zero that
 * already completed; only a subsequent non-zero request makes the backend potentially active.
 */
internal class ReflectionKnownIdleState(initiallyKnownIdle: Boolean) {
    var isKnownIdle: Boolean = initiallyKnownIdle
        private set

    fun markNonZeroRequested() {
        isKnownIdle = false
    }

    fun recordOrderedRelease(confirmed: Boolean) {
        if (confirmed) isKnownIdle = true
    }
}

private class ReflectionNpuWorkloadAdapter(context: Context) : NpuWorkloadAdapter {
    private val instance: Any?
    private val setIntensityMethod: Method?
    private val closeMethod: Method?
    private val initializationCleanupUnknown: Boolean
    private val cachedStatus = AtomicReference("NPU adapter 초기화 중")
    private val pendingIntensity = AtomicReference<Float?>(null)
    private val requestedLoad = AtomicReference(
        ReflectionLoadConfig(
            baseIntensity = 0f,
            shape = LoadShape.STEADY,
            startedMs = SystemClock.elapsedRealtime(),
        ),
    )
    private val lastQueuedIntensity = AtomicReference<Float?>(null)
    private val drainScheduled = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val shutdownResult = AtomicReference<NpuShutdownResult?>(null)
    /**
     * Reflection control stays on a bounded daemon lane, but Java cannot forcibly stop a vendor
     * setIntensity() implementation that ignores interruption. Product adapters must therefore
     * enforce their own timeout or run out-of-process behind a Binder/service lease that drops
     * NPU load when the client dies or the lease expires.
     */
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(REFLECTION_QUEUE_DEPTH),
        { runnable ->
            Thread(runnable, "DpuLab-NpuAdapter").apply { isDaemon = true }
        },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private val waveformThread: Thread?

    init {
        val initialization = initializeBackend(context.applicationContext)
        val backend = initialization.backend
        instance = backend?.instance
        setIntensityMethod = backend?.setIntensityMethod
        closeMethod = backend?.closeMethod
        initializationCleanupUnknown = initialization.cleanupUnknown
        if (initializationCleanupUnknown) {
            LoadSafetyState.markNpuCleanupUnconfirmed()
        }
        cachedStatus.set(initialization.status)
        waveformThread = if (backend != null) {
            Thread(::runWaveformControl, "DpuLab-NpuWaveform").apply {
                isDaemon = true
                start()
            }
        } else {
            null
        }
    }

    override fun isAvailable(): Boolean = instance != null && !closed.get()

    fun isInitiallyKnownIdle(): Boolean = !initializationCleanupUnknown

    override fun setIntensity(intensity: Float) {
        val current = requestedLoad.get()
        setLoad(
            intensity = intensity,
            shape = current.shape,
            restartProfile = false,
        )
    }

    /**
     * Telemetry reads must never enqueue vendor work or wait behind a stuck control call.
     * The cache is updated by initialization and completed control commands.
     */
    override fun status(): String {
        val terminal = shutdownResult.get()
        if (terminal != null) return "NPU adapter 종료 · ${terminal.detail}"
        return if (closed.get()) "NPU adapter 종료 중" else cachedStatus.get()
    }

    override fun releaseAndConfirm(): Boolean {
        if (instance == null) return true
        if (closed.get()) return false
        setLoad(0f, LoadShape.STEADY, restartProfile = true)
        val releaseFuture = runCatching {
            executor.submit<Boolean> {
                val applied = runCatching {
                    checkNotNull(setIntensityMethod).invoke(instance, 0f)
                }.isSuccess
                if (applied && !closed.get()) {
                    cachedStatus.set("NPU adapter 연결됨 · release 0% 확인")
                }
                applied
            }
        }.getOrNull() ?: return false
        return try {
            releaseFuture.get(REFLECTION_RELEASE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            releaseFuture.cancel(true)
            (releaseFuture as? Runnable)?.let(executor::remove)
            executor.purge()
            false
        } catch (_: Exception) {
            releaseFuture.cancel(true)
            (releaseFuture as? Runnable)?.let(executor::remove)
            executor.purge()
            false
        }
    }

    override fun closeWithResult(): NpuShutdownResult {
        shutdownResult.get()?.let { return it }
        if (!closed.compareAndSet(false, true)) {
            return shutdownResult.get()
                ?: NpuShutdownResult.unconfirmed("Reflection NPU 동시 종료 결과 미확인")
        }
        cachedStatus.set("NPU adapter 종료 중")
        requestedLoad.set(
            ReflectionLoadConfig(
                baseIntensity = 0f,
                shape = LoadShape.STEADY,
                startedMs = SystemClock.elapsedRealtime(),
            ),
        )
        waveformThread?.interrupt()
        LockSupport.unpark(waveformThread)
        try {
            waveformThread?.join(REFLECTION_WAVEFORM_JOIN_TIMEOUT_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        pendingIntensity.set(null)
        executor.queue.clear()
        if (instance == null) {
            executor.shutdownNow()
            val result = if (initializationCleanupUnknown) {
                NpuShutdownResult.unconfirmed(
                    "Reflection NPU 초기화/후보 cleanup 완료를 확인할 수 없음",
                )
            } else {
                NpuShutdownResult(
                    releaseConfirmed = true,
                    backendCloseConfirmed = true,
                    mayRemainActive = false,
                    detail = "Reflection NPU adapter 없음",
                )
            }
            cachedStatus.set(
                if (result.mayRemainActive) {
                    "NPU adapter 종료 요청됨 · 초기화 cleanup 미확인"
                } else {
                    "NPU adapter 종료됨"
                },
            )
            return result.also(shutdownResult::set)
        }
        val closeFuture = runCatching {
            executor.submit<ReflectionCloseOutcome> {
                val zeroApplied = runCatching {
                    checkNotNull(setIntensityMethod).invoke(instance, 0f)
                }.isSuccess
                val adapterClosed = runCatching {
                    checkNotNull(closeMethod).invoke(instance)
                }.isSuccess
                ReflectionCloseOutcome(
                    zeroConfirmed = zeroApplied,
                    closeConfirmed = adapterClosed,
                )
            }
        }.getOrNull()
        val closeOutcome = if (closeFuture != null) {
            executor.shutdown()
            try {
                closeFuture.get(REFLECTION_CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                closeFuture.cancel(true)
                executor.shutdownNow()
                ReflectionCloseOutcome()
            } catch (_: Exception) {
                closeFuture.cancel(true)
                executor.shutdownNow()
                ReflectionCloseOutcome()
            }
        } else {
            executor.shutdownNow()
            ReflectionCloseOutcome()
        }
        val loadReleased = closeOutcome.zeroConfirmed || closeOutcome.closeConfirmed
        cachedStatus.set(
            if (loadReleased && closeOutcome.closeConfirmed) {
                "NPU adapter 종료됨"
            } else if (loadReleased) {
                "NPU load 해제 확인 · adapter close 미확인"
            } else {
                "NPU adapter 종료 요청됨 · release 미확인"
            },
        )
        return NpuShutdownResult(
            releaseConfirmed = loadReleased,
            backendCloseConfirmed = closeOutcome.closeConfirmed,
            mayRemainActive = !loadReleased,
            detail =
                "Reflection NPU zero=${closeOutcome.zeroConfirmed}; " +
                    "close=${closeOutcome.closeConfirmed}",
        ).also(shutdownResult::set)
    }

    fun setLoad(intensity: Float, shape: LoadShape, restartProfile: Boolean) {
        if (instance == null || closed.get()) return
        val previous = requestedLoad.get()
        val safeIntensity = intensity.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
        val profileRestarted = restartProfile || previous.shape != shape
        requestedLoad.set(
            ReflectionLoadConfig(
                baseIntensity = safeIntensity,
                shape = shape,
                startedMs = if (profileRestarted) {
                    SystemClock.elapsedRealtime()
                } else {
                    previous.startedMs
                },
            ),
        )
        queueCurrentWaveformIntensity(
            force = profileRestarted || safeIntensity <= 0f,
        )
        LockSupport.unpark(waveformThread)
    }

    private fun runWaveformControl() {
        while (!closed.get()) {
            queueCurrentWaveformIntensity(force = false)
            val load = requestedLoad.get()
            LockSupport.parkNanos(
                if (load.baseIntensity > 0.001f && load.shape != LoadShape.STEADY) {
                    NPU_WAVEFORM_TICK_NANOS
                } else {
                    IDLE_NPU_WAVEFORM_PARK_NANOS
                },
            )
            if (Thread.interrupted() && closed.get()) return
        }
    }

    private fun queueCurrentWaveformIntensity(force: Boolean) {
        if (closed.get()) return
        val load = requestedLoad.get()
        val value = LoadShapeEvaluator.intensityAt(
            base = load.baseIntensity,
            shape = load.shape,
            elapsedMs = SystemClock.elapsedRealtime() - load.startedMs,
        )
        val previous = lastQueuedIntensity.get()
        if (force || previous == null || kotlin.math.abs(previous - value) >= NPU_UPDATE_EPSILON) {
            lastQueuedIntensity.set(value)
            pendingIntensity.set(value)
        }
        // If a bounded executor rejected the previous submission, retry even when the waveform
        // value itself did not change.
        if (pendingIntensity.get() != null) scheduleDrain()
    }

    private fun scheduleDrain() {
        if (closed.get() || !drainScheduled.compareAndSet(false, true)) return
        val accepted = runCatching {
            executor.execute {
                try {
                    while (!closed.get()) {
                        val intensity = pendingIntensity.getAndSet(null) ?: break
                        val percent = (intensity * 100f).toInt().coerceIn(0, 100)
                        if (!closed.get()) {
                            cachedStatus.set("NPU adapter 제어 적용 중 · $percent%")
                        }
                        runCatching {
                            checkNotNull(setIntensityMethod).invoke(instance, intensity)
                        }.onSuccess {
                            if (!closed.get()) {
                                cachedStatus.set("NPU adapter 연결됨 · 최근 제어 $percent%")
                            }
                        }.onFailure { error ->
                            if (!closed.get()) {
                                cachedStatus.set(
                                    "NPU adapter 제어 실패: ${error.javaClass.simpleName}",
                                )
                            }
                        }
                    }
                } finally {
                    drainScheduled.set(false)
                    if (!closed.get() && pendingIntensity.get() != null) scheduleDrain()
                }
            }
        }.isSuccess
        if (!accepted) {
            drainScheduled.set(false)
            // Preserve latest-wins state; the waveform thread or next setpoint update retries.
        }
    }

    private companion object {
        const val REFLECTION_QUEUE_DEPTH = 4
        const val REFLECTION_INIT_TIMEOUT_MS = 200L
        const val REFLECTION_RELEASE_TIMEOUT_MS = 300L
        const val REFLECTION_CLOSE_TIMEOUT_MS = 300L
        const val REFLECTION_WAVEFORM_JOIN_TIMEOUT_MS = 100L
        const val NPU_WAVEFORM_TICK_NANOS = 50_000_000L
        const val IDLE_NPU_WAVEFORM_PARK_NANOS = 500_000_000L
        const val NPU_UPDATE_EPSILON = 0.005f
        const val ADAPTER_CLASS_NAME = "com.vendor.dpulayerlab.NpuStressAdapter"
        val initializationDisabled = AtomicBoolean(false)

        fun initializeBackend(context: Context): ReflectionInitialization {
            if (LoadSafetyState.hasUnconfirmedNpuCleanup()) {
                return ReflectionInitialization(
                    backend = null,
                    status = "NPU adapter 비활성 · 이전 cleanup 미확인",
                    cleanupUnknown = true,
                )
            }
            if (initializationDisabled.get()) {
                return ReflectionInitialization(
                    backend = null,
                    status = "NPU adapter 초기화 비활성 · 이전 constructor timeout",
                    cleanupUnknown = true,
                )
            }
            val abandoned = AtomicBoolean(false)
            val candidateRef = AtomicReference<Any?>(null)
            val closeMethodRef = AtomicReference<Method?>(null)
            val cleanupUnknown = AtomicBoolean(false)
            val task = FutureTask<ReflectionBackend?> {
                var candidate: Any? = null
                var candidateCloseMethod: Method? = null
                var constructionAttempted = false
                try {
                    val type = Class.forName(ADAPTER_CLASS_NAME)
                    val constructor = type.getConstructor(Context::class.java)
                    constructionAttempted = true
                    val constructedCandidate =
                        constructor.newInstance(context)
                    candidate = constructedCandidate
                    candidateRef.set(constructedCandidate)
                    val candidateSetMethod =
                        type.getMethod("setIntensity", java.lang.Float.TYPE)
                    // Validate the documented adapter ABI once, but do not call status() from
                    // periodic telemetry. Runtime state is maintained in cachedStatus instead.
                    type.getMethod("status")
                    candidateCloseMethod = type.getMethod("close")
                    closeMethodRef.set(candidateCloseMethod)
                    val backend = ReflectionBackend(
                        instance = constructedCandidate,
                        setIntensityMethod = candidateSetMethod,
                        closeMethod = candidateCloseMethod,
                    )
                    if (abandoned.get()) {
                        if (!closeCandidateIfOwned(
                            candidateRef = candidateRef,
                            candidate = constructedCandidate,
                            closeMethod = candidateCloseMethod,
                        )) {
                            cleanupUnknown.set(true)
                        }
                        null
                    } else {
                        backend
                    }
                } catch (_: Throwable) {
                    val ownedCandidate = candidate
                    if (ownedCandidate != null) {
                        if (!closeCandidateIfOwned(
                            candidateRef = candidateRef,
                            candidate = ownedCandidate,
                            closeMethod = candidateCloseMethod,
                        )) {
                            cleanupUnknown.set(true)
                        }
                    } else if (constructionAttempted) {
                        // A constructor can allocate accelerator resources before throwing without
                        // returning an object on which close() can be invoked.
                        cleanupUnknown.set(true)
                    }
                    null
                }
            }
            Thread(task, "DpuLab-NpuInit").apply {
                isDaemon = true
                start()
            }

            return try {
                val backend = task.get(REFLECTION_INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                if (backend != null && candidateRef.compareAndSet(backend.instance, null)) {
                    ReflectionInitialization(backend, "NPU adapter 연결됨")
                } else {
                    backend?.let {
                        closeCandidateAsync(it.instance, it.closeMethod)
                    }
                    ReflectionInitialization(
                        backend = null,
                        status = "NPU adapter 없음",
                        cleanupUnknown = backend != null || cleanupUnknown.get(),
                    )
                }
            } catch (_: TimeoutException) {
                // Java cannot terminate a constructor that ignores interruption. Disable further
                // attempts for this process so Activity recreation cannot accumulate one stuck
                // daemon thread and captured Context per instance.
                initializationDisabled.set(true)
                abandonInitialization(
                    abandoned = abandoned,
                    task = task,
                    candidateRef = candidateRef,
                    closeMethod = closeMethodRef.get(),
                )
                ReflectionInitialization(
                    backend = null,
                    status = "NPU adapter 초기화 시간 초과",
                    cleanupUnknown = true,
                )
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                abandonInitialization(
                    abandoned = abandoned,
                    task = task,
                    candidateRef = candidateRef,
                    closeMethod = closeMethodRef.get(),
                )
                ReflectionInitialization(
                    backend = null,
                    status = "NPU adapter 초기화 중단됨",
                    cleanupUnknown = true,
                )
            } catch (_: Exception) {
                abandonInitialization(
                    abandoned = abandoned,
                    task = task,
                    candidateRef = candidateRef,
                    closeMethod = closeMethodRef.get(),
                )
                ReflectionInitialization(
                    backend = null,
                    status = "NPU adapter 없음",
                    cleanupUnknown = true,
                )
            }
        }

        private fun abandonInitialization(
            abandoned: AtomicBoolean,
            task: FutureTask<ReflectionBackend?>,
            candidateRef: AtomicReference<Any?>,
            closeMethod: Method?,
        ) {
            abandoned.set(true)
            task.cancel(true)
            candidateRef.getAndSet(null)?.let { candidate ->
                closeCandidateAsync(candidate, closeMethod)
            }
        }

        private fun closeCandidateIfOwned(
            candidateRef: AtomicReference<Any?>,
            candidate: Any,
            closeMethod: Method?,
        ): Boolean =
            candidateRef.compareAndSet(candidate, null) &&
                closeCandidate(candidate, closeMethod)

        private fun closeCandidateAsync(candidate: Any, closeMethod: Method?) {
            Thread(
                { closeCandidate(candidate, closeMethod) },
                "DpuLab-NpuInitCleanup",
            ).apply {
                isDaemon = true
                start()
            }
        }

        private fun closeCandidate(candidate: Any, preferredMethod: Method?): Boolean {
            val method = preferredMethod ?: runCatching {
                candidate.javaClass.getMethod("close")
            }.getOrNull()
            return if (method != null) {
                runCatching { method.invoke(candidate) }.isSuccess
            } else {
                val closeable = candidate as? AutoCloseable ?: return false
                runCatching { closeable.close() }.isSuccess
            }
        }
    }

    private data class ReflectionLoadConfig(
        val baseIntensity: Float,
        val shape: LoadShape,
        val startedMs: Long,
    )

    private data class ReflectionCloseOutcome(
        val zeroConfirmed: Boolean = false,
        val closeConfirmed: Boolean = false,
    )

    private data class ReflectionBackend(
        val instance: Any,
        val setIntensityMethod: Method,
        val closeMethod: Method,
    )

    private data class ReflectionInitialization(
        val backend: ReflectionBackend?,
        val status: String,
        val cleanupUnknown: Boolean = false,
    )
}

private class CompositeNpuWorkloadAdapter(context: Context) : NpuWorkloadAdapter {
    private val vendorBridge = VendorBridge.get(context)
    private val reflection = ReflectionNpuWorkloadAdapter(context)
    private val closed = AtomicBoolean(false)
    private val shutdownResult = AtomicReference<NpuShutdownResult?>(null)
    private var shape: LoadShape = LoadShape.STEADY
    @Volatile
    private var usingVendorBackend = false
    private var vendorMayBeActive = false
    private var reflectionMayBeActive = false
    private val reflectionKnownIdle =
        ReflectionKnownIdleState(initiallyKnownIdle = reflection.isInitiallyKnownIdle())

    override fun isAvailable(): Boolean =
        !closed.get() && (vendorBridge.supportsNpu() || reflection.isAvailable())

    @Synchronized
    fun applyLoad(intensity: Float, newShape: LoadShape, restartProfile: Boolean) {
        if (closed.get()) return
        shape = newShape
        val backendPinned = vendorMayBeActive || reflectionMayBeActive
        val useVendor = if (backendPinned) {
            usingVendorBackend
        } else {
            vendorBridge.supportsNpu()
        }
        if (useVendor != usingVendorBackend) {
            if (useVendor) {
                reflection.setLoad(0f, LoadShape.STEADY, restartProfile = true)
            } else {
                vendorBridge.setNpuLoad(0f, LoadShape.STEADY)
            }
            usingVendorBackend = useVendor
        }
        if (useVendor) {
            if (intensity > 0f) vendorMayBeActive = true
            vendorBridge.setNpuLoad(intensity, newShape)
        } else {
            if (intensity > 0f && reflection.isAvailable()) {
                reflectionMayBeActive = true
                reflectionKnownIdle.markNonZeroRequested()
            }
            reflection.setLoad(intensity, newShape, restartProfile)
        }
    }

    override fun setIntensity(intensity: Float) {
        applyLoad(intensity, shape, restartProfile = false)
    }

    @Synchronized
    override fun releaseAndConfirm(): Boolean {
        if (closed.get()) return shutdownResult.get()?.releaseConfirmed == true
        val reflectionConfirmed = when {
            reflectionKnownIdle.isKnownIdle -> true
            reflectionMayBeActive -> reflection.releaseAndConfirm()
            else -> false
        }
        val vendorConfirmed =
            !vendorMayBeActive || vendorBridge.releaseNpuLoadAndConfirm()
        reflectionKnownIdle.recordOrderedRelease(reflectionConfirmed)
        if (reflectionKnownIdle.isKnownIdle) reflectionMayBeActive = false
        if (vendorConfirmed) vendorMayBeActive = false
        return reflectionConfirmed && vendorConfirmed
    }

    @Synchronized
    fun status(vendorSnapshotStatus: String?): String {
        val backendPinned = vendorMayBeActive || reflectionMayBeActive
        return if (backendPinned && usingVendorBackend) {
            if (vendorBridge.supportsNpu()) {
                vendorSnapshotStatus?.ifBlank { "Vendor NPU 연결됨" } ?: "Vendor NPU 연결됨"
            } else {
                "Vendor NPU workload · 재연결/해제 확인 대기"
            }
        } else if (!backendPinned && vendorBridge.supportsNpu()) {
            vendorSnapshotStatus?.ifBlank { "Vendor NPU 연결됨" } ?: "Vendor NPU 연결됨"
        } else {
            reflection.status()
        }
    }

    override fun status(): String = status(vendorSnapshotStatus = null)

    @Synchronized
    override fun closeWithResult(): NpuShutdownResult {
        shutdownResult.get()?.let { return it }
        val released = releaseAndConfirm()
        closed.set(true)
        val reflectionResult = reflection.closeWithResult()
        reflectionKnownIdle.recordOrderedRelease(reflectionResult.releaseConfirmed)
        if (reflectionKnownIdle.isKnownIdle) {
            reflectionMayBeActive = false
        }
        val mayRemainActive =
            vendorMayBeActive ||
                !reflectionKnownIdle.isKnownIdle
        usingVendorBackend = false
        return NpuShutdownResult(
            releaseConfirmed = !vendorMayBeActive &&
                reflectionKnownIdle.isKnownIdle,
            backendCloseConfirmed = reflectionResult.backendCloseConfirmed,
            mayRemainActive = mayRemainActive,
            detail = buildString {
                append(
                    if (released) {
                        "pre-close backend zero 확인"
                    } else {
                        "pre-close backend zero 일부 미확인"
                    },
                )
                append("; ")
                append(reflectionResult.detail)
            },
        ).also(shutdownResult::set)
    }
}
