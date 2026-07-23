package com.example.dpulayerlab.engine

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import com.example.dpulayerlab.model.LoadSetpoints
import com.example.dpulayerlab.model.LoadShape
import com.example.dpulayerlab.vendor.VendorBridge
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Controllable, bounded load generators. They intentionally use duty cycles so every scenario can
 * include an observable "bus acquired → released" transition without leaving runaway workers.
 */
class LoadManager(context: Context) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val setpoints = AtomicReference(LoadSetpoints())
    private val phaseStartedMs = AtomicLong(SystemClock.elapsedRealtime())
    private val threads = ConcurrentLinkedQueue<Thread>()
    private val copiedBytes = AtomicLong(0)
    private val memoryAllocationFailed = AtomicBoolean(false)
    private val npuAdapter: NpuWorkloadAdapter = CompositeNpuWorkloadAdapter(context)
    private val activityManager = context.getSystemService(ActivityManager::class.java)
    private val memoryWorkerCount = if (activityManager.isLowRamDevice) {
        1
    } else {
        min(2, Runtime.getRuntime().availableProcessors().coerceAtLeast(1))
    }
    private val memoryWorkingSetBytes = run {
        val heapBytes = activityManager.memoryClass.toLong() * 1024L * 1024L
        val totalArrayBudget = (heapBytes / 8L).coerceIn(8L * MIB, 64L * MIB)
        (totalArrayBudget / (memoryWorkerCount * 2L))
            .coerceIn(4L * MIB, 24L * MIB)
            .toInt()
    }

    fun start() {
        if (closed.get()) return
        if (!running.compareAndSet(false, true)) return
        memoryAllocationFailed.set(false)
        val cpuWorkers = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 8)
        repeat(cpuWorkers) { index ->
            worker("DpuLab-CPU-$index") { cpuCycle(index) }
        }
        repeat(memoryWorkerCount) { index ->
            worker("DpuLab-Memory-$index") { memoryCycle(index) }
        }
    }

    fun apply(newSetpoints: LoadSetpoints) {
        if (closed.get()) return
        val normalized = newSetpoints.normalized()
        setpoints.set(normalized)
        phaseStartedMs.set(SystemClock.elapsedRealtime())
        (npuAdapter as? CompositeNpuWorkloadAdapter)?.setShape(normalized.shape)
        npuAdapter.setIntensity(normalized.npu)
    }

    fun releaseLoads() {
        apply(LoadSetpoints())
    }

    fun sampleAndResetBandwidthBytes(): Long = copiedBytes.getAndSet(0)

    fun npuStatus(): String = npuAdapter.status()

    fun hasNpuAdapter(): Boolean = npuAdapter.isAvailable()

    fun hasMemoryAllocationFailure(): Boolean = memoryAllocationFailed.get()

    fun clearMemoryAllocationFailure() {
        memoryAllocationFailed.set(false)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        running.set(false)
        setpoints.set(LoadSetpoints())
        npuAdapter.close()
        val activeThreads = threads.toList()
        activeThreads.forEach { it.interrupt() }
        activeThreads.forEach { thread ->
            runCatching { thread.join(WORKER_JOIN_TIMEOUT_MS) }
        }
        threads.clear()
    }

    private fun worker(name: String, cycle: () -> Unit) {
        Thread({
            while (running.get()) {
                try {
                    cycle()
                } catch (_: InterruptedException) {
                    break
                } catch (_: OutOfMemoryError) {
                    memoryAllocationFailed.set(true)
                    break
                } catch (_: Exception) {
                    SystemClock.sleep(20)
                }
            }
        }, name).apply {
            priority = Thread.NORM_PRIORITY
            start()
            threads += this
        }
    }

    private fun cpuCycle(workerIndex: Int) {
        val config = setpoints.get()
        val intensity = shapedIntensity(config.cpu, config.shape)
        if (intensity <= 0.001f) {
            SystemClock.sleep(24)
            return
        }
        val cycleStart = System.nanoTime()
        val busyNanos = (12_000_000L * intensity).toLong().coerceAtLeast(150_000L)
        var x = 0.17 + workerIndex * 0.013
        var y = 0.73 + workerIndex * 0.021
        while (running.get() && System.nanoTime() - cycleStart < busyNanos) {
            repeat(512) { iteration ->
                x = sin(x * 1.000003 + iteration * 0.00007) + cos(y * 0.999997)
                y = cos(y + x * 0.00031) - sin(x * 0.71)
            }
        }
        blackHole = x + y
        val idleMs = (12f * (1f - intensity)).toLong()
        if (idleMs > 0) SystemClock.sleep(idleMs)
    }

    private fun memoryCycle(workerIndex: Int) {
        val workingSet = memoryWorkingSetBytes
        var source: ByteArray? = null
        var destination: ByteArray? = null
        var cursor = workerIndex * COPY_BLOCK_BYTES
        var idleSinceMs = SystemClock.elapsedRealtime()
        while (running.get()) {
            val config = setpoints.get()
            val intensity = shapedIntensity(config.memory, config.shape)
            if (intensity <= 0.001f) {
                if (SystemClock.elapsedRealtime() - idleSinceMs >= IDLE_BUFFER_RELEASE_MS) {
                    source = null
                    destination = null
                }
                SystemClock.sleep(28)
                continue
            }
            idleSinceMs = SystemClock.elapsedRealtime()
            if (memoryAllocationFailed.get()) {
                SystemClock.sleep(1_000)
                continue
            }
            if (source == null || destination == null) {
                try {
                    source = ByteArray(workingSet) {
                        ((it + workerIndex * 31) and 0xff).toByte()
                    }
                    destination = ByteArray(workingSet)
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
            val busyNanos = (10_000_000L * intensity).toLong().coerceAtLeast(180_000L)
            while (running.get() && System.nanoTime() - burstStart < busyNanos) {
                if (cursor + COPY_BLOCK_BYTES > workingSet) cursor = 0
                System.arraycopy(activeSource, cursor, activeDestination, cursor, COPY_BLOCK_BYTES)
                copiedBytes.addAndGet(COPY_BLOCK_BYTES.toLong() * 2L)
                cursor += COPY_BLOCK_BYTES
            }
            memoryFence = activeDestination[(cursor - 1).coerceAtLeast(0)]
            val idleMs = (10f * (1f - intensity)).toLong()
            if (idleMs > 0) SystemClock.sleep(idleMs)
        }
    }

    private fun shapedIntensity(base: Float, shape: LoadShape): Float {
        if (base <= 0f) return 0f
        val seconds = (SystemClock.elapsedRealtime() - phaseStartedMs.get()) / 1_000f
        val factor = when (shape) {
            LoadShape.STEADY -> 1f
            LoadShape.PULSE -> if ((seconds.toInt() / 2) % 2 == 0) 1f else 0f
            LoadShape.RAMP -> ((seconds % 6f) / 6f).coerceIn(0f, 1f)
            LoadShape.SAW -> {
                val position = (seconds % 8f) / 8f
                if (position < 0.5f) position * 2f else (1f - position) * 2f
            }
        }
        return (base * factor).coerceIn(0f, 1f)
    }

    companion object {
        private const val MIB = 1024L * 1024L
        private const val COPY_BLOCK_BYTES = 1024 * 1024
        private const val IDLE_BUFFER_RELEASE_MS = 5_000L
        private const val WORKER_JOIN_TIMEOUT_MS = 250L

        @Volatile
        private var blackHole = 0.0

        @Volatile
        private var memoryFence: Byte = 0
    }
}

/**
 * Product teams can ship a class named `com.vendor.dpulayerlab.NpuStressAdapter` in the system
 * image. Expected methods: constructor(Context), setIntensity(Float), status(): String, close().
 * This keeps the portable APK honest: no NPU claim is made when a vendor/NNAPI backend is absent.
 */
interface NpuWorkloadAdapter : AutoCloseable {
    fun isAvailable(): Boolean
    fun setIntensity(intensity: Float)
    fun status(): String
}

private class ReflectionNpuWorkloadAdapter(context: Context) : NpuWorkloadAdapter {
    private val instance: Any?
    private val setIntensityMethod: java.lang.reflect.Method?
    private val statusMethod: java.lang.reflect.Method?
    private val closeMethod: java.lang.reflect.Method?

    init {
        var adapter: Any? = null
        var setMethod: java.lang.reflect.Method? = null
        var getStatus: java.lang.reflect.Method? = null
        var close: java.lang.reflect.Method? = null
        var constructedCandidate: Any? = null
        runCatching {
            val type = Class.forName("com.vendor.dpulayerlab.NpuStressAdapter")
            val candidate = type.getConstructor(Context::class.java)
                .newInstance(context.applicationContext)
            constructedCandidate = candidate
            val candidateSetMethod = type.getMethod("setIntensity", java.lang.Float.TYPE)
            val candidateStatusMethod = type.getMethod("status")
            val candidateCloseMethod = type.getMethod("close")
            adapter = candidate
            setMethod = candidateSetMethod
            getStatus = candidateStatusMethod
            close = candidateCloseMethod
        }
        if (adapter == null) {
            runCatching { (constructedCandidate as? AutoCloseable)?.close() }
        }
        instance = adapter
        setIntensityMethod = setMethod
        statusMethod = getStatus
        closeMethod = close
    }

    override fun isAvailable(): Boolean = instance != null

    override fun setIntensity(intensity: Float) {
        runCatching { setIntensityMethod?.invoke(instance, intensity.coerceIn(0f, 1f)) }
    }

    override fun status(): String =
        if (instance == null) {
            "NPU adapter 없음"
        } else {
            runCatching { statusMethod?.invoke(instance)?.toString() }
                .getOrNull()
                ?: "NPU adapter 연결됨"
        }

    override fun close() {
        runCatching { closeMethod?.invoke(instance) }
    }
}

private class CompositeNpuWorkloadAdapter(context: Context) : NpuWorkloadAdapter {
    private val vendorBridge = VendorBridge.get(context)
    private val reflection = ReflectionNpuWorkloadAdapter(context)
    private var shape: LoadShape = LoadShape.STEADY

    override fun isAvailable(): Boolean = vendorBridge.supportsNpu() || reflection.isAvailable()

    fun setShape(newShape: LoadShape) {
        shape = newShape
    }

    override fun setIntensity(intensity: Float) {
        if (vendorBridge.supportsNpu()) {
            vendorBridge.setNpuLoad(intensity, shape)
        } else {
            reflection.setIntensity(intensity)
        }
    }

    override fun status(): String {
        val snapshot = vendorBridge.snapshot()
        return when {
            vendorBridge.supportsNpu() -> snapshot?.npuStatus?.ifBlank { "Vendor NPU 연결됨" } ?: "Vendor NPU 연결됨"
            reflection.isAvailable() -> reflection.status()
            else -> "NPU adapter 없음"
        }
    }

    override fun close() {
        vendorBridge.setNpuLoad(0f, LoadShape.STEADY)
        reflection.close()
    }
}
