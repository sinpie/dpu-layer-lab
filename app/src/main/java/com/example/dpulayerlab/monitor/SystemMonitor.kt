package com.example.dpulayerlab.monitor

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.HardwarePropertiesManager
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.view.Display
import com.example.dpulayerlab.engine.LoadManager
import com.example.dpulayerlab.model.Gauge
import com.example.dpulayerlab.model.MetricQuality
import com.example.dpulayerlab.model.SensorReading
import com.example.dpulayerlab.model.TelemetrySnapshot
import com.example.dpulayerlab.model.PixelRoute
import com.example.dpulayerlab.vendor.VendorBridge
import com.example.dpulayerlab.vendor.VendorShutdownResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class CompressionControlResult {
    APPLIED,
    NO_ADAPTER,
    REJECTED_OR_TIMEOUT,
}

data class CompressionControlOutcome(
    val result: CompressionControlResult,
    /** Binder registration that acknowledged this command. */
    val serviceSession: Long? = null,
)

class SystemMonitor(
    private val context: Context,
    private val frameTracker: FrameTracker,
    private val loadManager: LoadManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val activityManager = context.getSystemService(ActivityManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val hardwareProperties = context.getSystemService(HardwarePropertiesManager::class.java)
    private val kernelSensors = KernelSensorProvider(context)
    private val surfaceFlinger = SurfaceFlingerProbe(context)
    private val vendorBridge = VendorBridge.get(context)
    private var lastCpu: CpuTimes? = null
    private var lastHardwareCpu: CpuTimes? = null
    private var lastAppCpuMs = Process.getElapsedCpuTime()
    private var lastWallMs = SystemClock.elapsedRealtime()
    private var lastRateSampleMs: Long? = null
    private var lastComposition = CompositionSnapshot()
    private var compositionSampleCounter = 0
    @Volatile
    private var latestReadings: List<SensorReading> = emptyList()

    suspend fun sample(display: Display?): TelemetrySnapshot = withContext(ioDispatcher) {
        val now = SystemClock.elapsedRealtime()
        val hardwareCpuNow = readHardwareCpuTimes()
        val procCpuNow = if (hardwareCpuNow == null) readCpuTimes() else null
        val cpuPercent = if (hardwareCpuNow != null) {
            hardwareCpuNow.percentSince(lastHardwareCpu)
        } else {
            procCpuNow?.percentSince(lastCpu)
        }
        if (hardwareCpuNow != null) lastHardwareCpu = hardwareCpuNow
        if (procCpuNow != null) lastCpu = procCpuNow
        val cpuSource = if (hardwareCpuNow != null) "HardwarePropertiesManager" else "/proc/stat"
        val cpuQuality = if (hardwareCpuNow != null) MetricQuality.SYSTEM_SERVICE else MetricQuality.MEASURED

        val wallDelta = now - lastWallMs
        val appCpuNow = Process.getElapsedCpuTime()
        val appCpuDelta = appCpuNow - lastAppCpuMs
        val appCpu = if (wallDelta > 0L && appCpuDelta >= 0L) {
            (appCpuDelta * 100.0 / wallDelta)
                .takeIf(Double::isFinite)
                ?.coerceIn(0.0, Runtime.getRuntime().availableProcessors() * 100.0)
                ?.toFloat()
        } else {
            null
        }
        lastAppCpuMs = appCpuNow
        lastWallMs = now

        val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val memory = normalizedMemoryMetrics(
            totalBytes = memoryInfo.totalMem,
            availableBytes = memoryInfo.availMem,
            appPssKilobytes = Debug.getPss().toLong(),
        )

        val hz = display?.refreshRate?.takeIf { it.isFinite() && it > 0f }
        hz?.let(frameTracker::updateExpectedRefresh)

        compositionSampleCounter++
        if (compositionSampleCounter == 1 || compositionSampleCounter % 5 == 0) {
            lastComposition = surfaceFlinger.sample()
        }
        // Exact counters are intentionally sampled after the potentially slow SurfaceFlinger
        // probe. A caller waiting for a post-warm-up baseline can then start its first phase
        // immediately after this sample without charging probe latency to that phase.
        val vendor = vendorBridge.snapshot()
        // Registration continuity is local state and must remain observable when the remote
        // metrics call times out under load. Only a real disconnect/reconnect changes this ID.
        val currentVendorServiceSession = vendorBridge.currentServiceSession()
        val kernel = kernelSensors.sample()
        val vendorSource = vendor?.let {
            vendorServiceSource(it.apiVersion, it.serviceSession)
        }

        val vendorDpu = vendor?.dpuUtilization?.validUtilizationPercent()
        val vendorBus = vendor?.busUtilization?.validUtilizationPercent()
        latestReadings = buildList {
            vendor?.underrunCount?.takeIf { it >= 0L }?.let { underrunCount ->
                add(
                    SensorReading(
                        "vendor_underrun",
                        "DPU underrun",
                        underrunCount.toString(),
                        MetricQuality.HARDWARE_COUNTER,
                        checkNotNull(vendorSource),
                    ),
                )
            }
            vendorDpu?.let { dpu ->
                add(
                    SensorReading(
                        "vendor_dpu",
                        "DPU busy",
                        "$dpu%",
                        MetricQuality.HARDWARE_COUNTER,
                        checkNotNull(vendorSource),
                    ),
                )
            }
            lastComposition.hwcMissedFrames?.let { missed ->
                add(
                    SensorReading(
                        "sf_hwc_missed",
                        "HWC missed frames",
                        missed.toString(),
                        MetricQuality.PROXY,
                        lastComposition.source,
                    ),
                )
            }
            lastComposition.gpuMissedFrames?.let { missed ->
                add(
                    SensorReading(
                        "sf_gpu_missed",
                        "GPU missed frames",
                        missed.toString(),
                        MetricQuality.PROXY,
                        lastComposition.source,
                    ),
                )
            }
            addAll(kernel.readings)
        }

        // Read and reset both counters at the same point in every sample. The monitor loop can
        // slip while dumpsys/sysfs is slow, so rates must use this real interval instead of
        // assuming that every invocation represents exactly one second.
        val rateSampleNow = SystemClock.elapsedRealtime()
        val rateDeltaMs = lastRateSampleMs?.let { rateSampleNow - it }?.takeIf { it > 0L }
        val producedFrames = frameTracker.sampleProducedFrames()
        val generatedBytes = loadManager.sampleAndResetBandwidthBytes()
        lastRateSampleMs = rateSampleNow
        val producedFps = normalizedPerSecond(producedFrames, rateDeltaMs)
        val generatedGbps = normalizedGigabitsPerSecond(generatedBytes, rateDeltaMs)
        val missedFrames = frameTracker.totalMissedFrames()
        val thermalStatus = powerManager.currentThermalStatus
        val vendorDeviceLayers = vendor?.deviceLayers
        val vendorClientLayers = vendor?.clientLayers
        val hwcDeviceLayers = vendorDeviceLayers ?: lastComposition.deviceLayers
        val hwcClientLayers = vendorClientLayers ?: lastComposition.clientLayers
        val hwcDeviceLayersQuality = when {
            vendorDeviceLayers != null -> MetricQuality.HARDWARE_COUNTER
            lastComposition.deviceLayers != null -> MetricQuality.SYSTEM_SERVICE
            else -> MetricQuality.UNAVAILABLE
        }
        val hwcClientLayersQuality = when {
            vendorClientLayers != null -> MetricQuality.HARDWARE_COUNTER
            lastComposition.clientLayers != null -> MetricQuality.SYSTEM_SERVICE
            else -> MetricQuality.UNAVAILABLE
        }
        val hwcDeviceLayersSource = when {
            vendorDeviceLayers != null -> checkNotNull(vendorSource)
            lastComposition.deviceLayers != null -> lastComposition.source
            else -> ""
        }
        val hwcClientLayersSource = when {
            vendorClientLayers != null -> checkNotNull(vendorSource)
            lastComposition.clientLayers != null -> lastComposition.source
            else -> ""
        }

        TelemetrySnapshot(
            monotonicMs = now,
            cpu = if (cpuPercent != null) {
                Gauge(cpuPercent, "%", cpuQuality, cpuSource)
            } else {
                Gauge(source = cpuSource)
            },
            appCpu = appCpu?.let {
                Gauge(it, "%", MetricQuality.MEASURED, "Process.getElapsedCpuTime")
            } ?: Gauge(source = "Process.getElapsedCpuTime"),
            memoryUsed = memory.usedPercent?.let {
                Gauge(it, "%", MetricQuality.SYSTEM_SERVICE, "ActivityManager")
            } ?: Gauge(source = "ActivityManager"),
            memoryAvailable = memory.availableMb?.let {
                Gauge(it, " MB", MetricQuality.SYSTEM_SERVICE, "ActivityManager")
            } ?: Gauge(source = "ActivityManager"),
            appPss = memory.appPssMb?.let {
                Gauge(it, " MB", MetricQuality.MEASURED, "Debug.getPss")
            } ?: Gauge(source = "Debug.getPss"),
            displayHz = hz?.let {
                Gauge(it, " Hz", MetricQuality.SYSTEM_SERVICE, "Display.getRefreshRate")
            } ?: Gauge(source = "Display.getRefreshRate"),
            producedFps = producedFps?.let {
                Gauge(it, " fps", MetricQuality.MEASURED, "primary BufferQueue producer")
            } ?: Gauge(source = "primary BufferQueue producer · sample baseline pending"),
            missedFrames = missedFrames,
            suspectedUnderruns = missedFrames,
            suspectedUnderrunQuality = MetricQuality.PROXY,
            suspectedUnderrunSource = "FrameTracker · Choreographer deadline miss",
            exactUnderruns = vendor?.underrunCount?.takeIf { it >= 0L } ?: kernel.exactUnderruns,
            exactUnderrunSource = if (vendor?.underrunCount?.let { it >= 0L } == true) {
                vendorSource
            } else {
                kernel.exactUnderrunSource
            },
            exactUnderrunQuality = when {
                vendor?.underrunCount?.let { it >= 0L } == true ->
                    MetricQuality.HARDWARE_COUNTER
                kernel.exactUnderruns != null && !kernel.exactUnderrunSource.isNullOrBlank() ->
                    MetricQuality.KERNEL
                else -> MetricQuality.UNAVAILABLE
            },
            gpuBusy = kernel.gpuBusy,
            gpuFrequency = kernel.gpuFrequency,
            busBusy = vendorBus?.let {
                Gauge(it, "%", MetricQuality.HARDWARE_COUNTER, checkNotNull(vendorSource))
            } ?: kernel.busBusy,
            generatedBandwidth = generatedGbps?.let {
                Gauge(it, " Gbps", MetricQuality.MEASURED, "memory load generator")
            } ?: Gauge(source = "memory load generator · sample baseline pending"),
            dpuBusy = vendorDpu?.let {
                Gauge(it, "%", MetricQuality.HARDWARE_COUNTER, checkNotNull(vendorSource))
            } ?: kernel.dpuBusy,
            dpuFrequency = kernel.dpuFrequency,
            hwcDeviceLayers = hwcDeviceLayers,
            hwcDeviceLayersQuality = hwcDeviceLayersQuality,
            hwcDeviceLayersSource = hwcDeviceLayersSource,
            hwcClientLayers = hwcClientLayers,
            hwcClientLayersQuality = hwcClientLayersQuality,
            hwcClientLayersSource = hwcClientLayersSource,
            surfaceFlingerHwcMissed = lastComposition.hwcMissedFrames,
            surfaceFlingerGpuMissed = lastComposition.gpuMissedFrames,
            surfaceFlingerMissSource = if (
                lastComposition.hwcMissedFrames != null ||
                lastComposition.gpuMissedFrames != null
            ) {
                lastComposition.source
            } else {
                ""
            },
            thermalStatus = thermalStatus,
            thermalLabel = thermalLabel(thermalStatus),
            memoryLow = memoryInfo.lowMemory || loadManager.hasMemoryAllocationFailure(),
            powerSaveMode = powerManager.isPowerSaveMode,
            vendorServiceSession = currentVendorServiceSession,
            compressionState = when {
                vendor != null -> vendor.compressionState.ifBlank { "Unknown" }
                currentVendorServiceSession != null -> "Unavailable · snapshot timeout"
                else -> "Adapter 없음"
            },
            // Reuse the vendor transaction already completed at the start of this sample.
            // NPU status must not trigger a second snapshot or contend with control-to-zero.
            npuState = loadManager.npuStatus(vendor?.npuStatus),
        )
    }

    fun directSensorReadings(): List<SensorReading> = latestReadings

    fun hasDumpPermission(): Boolean = surfaceFlinger.hasDumpPermission()

    fun hasNpuAdapter(): Boolean = loadManager.hasNpuAdapter()

    fun hasSbwcAdapter(): Boolean = vendorBridge.supportsSbwc()

    fun isVendorCapabilityDiscoveryPending(): Boolean =
        vendorBridge.isCapabilityDiscoveryPending()

    fun setCompressionRoute(route: PixelRoute): CompressionControlOutcome {
        if (!vendorBridge.supportsSbwc()) {
            return CompressionControlOutcome(CompressionControlResult.NO_ADAPTER)
        }
        val vendorResult = vendorBridge.setCompressionRoute(route)
        return if (vendorResult.applied && vendorResult.serviceSession != null) {
            CompressionControlOutcome(
                result = CompressionControlResult.APPLIED,
                serviceSession = vendorResult.serviceSession,
            )
        } else {
            CompressionControlOutcome(CompressionControlResult.REJECTED_OR_TIMEOUT)
        }
    }

    fun close(
        resetCompression: Boolean = true,
    ): VendorShutdownResult = vendorBridge.closeWithResult(resetCompression)

    private fun readCpuTimes(): CpuTimes? = runCatching {
        File("/proc/stat").bufferedReader().use { reader ->
            parseProcStatCpuLine(reader.readLine())
        }
    }.getOrNull()

    private fun readHardwareCpuTimes(): CpuTimes? = runCatching {
        val rawUsages = hardwareProperties.cpuUsages
        val usages = rawUsages.filterNotNull()
        if (usages.isEmpty() || usages.size != rawUsages.size) return null
        if (usages.any { it.active < 0L || it.total <= 0L || it.active > it.total }) return null
        val active = usages.map { it.active }.checkedSum()
        val total = usages.map { it.total }.checkedSum()
        if (total <= 0) return null
        CpuTimes(
            idle = total - active,
            total = total,
            participantCount = usages.size,
        )
    }.getOrNull()

    private fun thermalLabel(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "정상"
        PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
        PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
        PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
        PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
        else -> "UNKNOWN"
    }

}

internal data class NormalizedMemoryMetrics(
    val usedPercent: Float?,
    val availableMb: Float?,
    val appPssMb: Float?,
)

internal fun normalizedMemoryMetrics(
    totalBytes: Long,
    availableBytes: Long,
    appPssKilobytes: Long,
): NormalizedMemoryMetrics {
    val validSystemMemory =
        totalBytes > 0L && availableBytes >= 0L && availableBytes <= totalBytes
    val availableMb = if (validSystemMemory) {
        (availableBytes.toDouble() / BYTES_PER_MEBIBYTE)
            .takeIf { it.isFinite() && it <= Float.MAX_VALUE }
            ?.toFloat()
    } else {
        null
    }
    val usedPercent = if (validSystemMemory) {
        ((totalBytes - availableBytes).toDouble() * 100.0 / totalBytes.toDouble())
            .takeIf { it.isFinite() && it in 0.0..100.0 }
            ?.toFloat()
    } else {
        null
    }
    val appPssMb = if (appPssKilobytes >= 0L) {
        (appPssKilobytes.toDouble() / KIBIBYTES_PER_MEBIBYTE)
            .takeIf { it.isFinite() && it <= Float.MAX_VALUE }
            ?.toFloat()
    } else {
        null
    }
    return NormalizedMemoryMetrics(
        usedPercent = usedPercent,
        availableMb = availableMb,
        appPssMb = appPssMb,
    )
}

internal fun vendorServiceSource(apiVersion: Int, serviceSession: Long): String =
    "IDpuLabVendorService v$apiVersion · session=$serviceSession"

internal data class CpuTimes(
    val idle: Long,
    val total: Long,
    val participantCount: Int = 0,
) {
    fun percentSince(previous: CpuTimes?): Float? {
        previous ?: return null
        if (!isValid() || !previous.isValid()) return null
        if (participantCount != previous.participantCount) return null
        if (total <= previous.total || idle < previous.idle) return null
        val totalDelta = total - previous.total
        val idleDelta = idle - previous.idle
        if (idleDelta > totalDelta) return null
        return ((totalDelta - idleDelta) * 100.0 / totalDelta)
            .takeIf(Double::isFinite)
            ?.toFloat()
    }

    private fun isValid(): Boolean =
        idle >= 0L && total > 0L && idle <= total && participantCount >= 0
}

/**
 * Parses the aggregate Linux CPU line without double-counting guest/guest_nice. Those fields are
 * already included in user/nice by the kernel ABI.
 */
internal fun parseProcStatCpuLine(line: String?): CpuTimes? = runCatching {
    val tokens = line
        ?.trim()
        ?.split(Regex("""\s+"""))
        ?: return null
    if (tokens.firstOrNull() != "cpu" || tokens.size < 6) return null
    val fields = tokens.drop(1).map(String::toLong)
    if (fields.any { it < 0L }) return null
    val idle = Math.addExact(fields[3], fields.getOrElse(4) { 0L })
    // user, nice, system, idle, iowait, irq, softirq, steal. guest values after this range
    // are accounting subsets rather than additional elapsed time.
    val total = fields.take(8).checkedSum()
    if (total <= 0L || idle > total) return null
    CpuTimes(idle = idle, total = total)
}.getOrNull()

internal fun normalizedPerSecond(count: Long, elapsedMs: Long?): Float? {
    if (count < 0L || elapsedMs == null || elapsedMs <= 0L) return null
    return (count.toDouble() * MILLIS_PER_SECOND / elapsedMs)
        .takeIf { it.isFinite() && it <= Float.MAX_VALUE }
        ?.toFloat()
}

internal fun normalizedGigabitsPerSecond(bytes: Long, elapsedMs: Long?): Float? {
    val bytesPerSecond = normalizedPerSecond(bytes, elapsedMs)?.toDouble() ?: return null
    return (bytesPerSecond * BITS_PER_BYTE / BITS_PER_GIGABIT)
        .takeIf { it.isFinite() && it <= Float.MAX_VALUE }
        ?.toFloat()
}

private fun Iterable<Long>.checkedSum(): Long {
    var result = 0L
    for (value in this) result = Math.addExact(result, value)
    return result
}

private const val MILLIS_PER_SECOND = 1_000.0
private const val BITS_PER_BYTE = 8.0
private const val BITS_PER_GIGABIT = 1_000_000_000.0
private const val BYTES_PER_MEBIBYTE = 1_048_576.0
private const val KIBIBYTES_PER_MEBIBYTE = 1_024.0
