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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
        val totalMb = memoryInfo.totalMem / 1_048_576f
        val availableMb = memoryInfo.availMem / 1_048_576f
        val usedPercent = if (totalMb > 0f && availableMb >= 0f) {
            ((totalMb - availableMb) * 100f / totalMb).coerceIn(0f, 100f)
        } else {
            null
        }
        val appPssMb = Debug.getPss() / 1_024f

        val hz = display?.refreshRate?.takeIf { it.isFinite() && it > 0f }
        hz?.let(frameTracker::updateExpectedRefresh)
        val kernel = kernelSensors.sample()
        val vendor = vendorBridge.snapshot()

        compositionSampleCounter++
        if (compositionSampleCounter == 1 || compositionSampleCounter % 5 == 0) {
            lastComposition = surfaceFlinger.sample()
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
                        "IDpuLabVendorService v${vendor.apiVersion}",
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
                        "IDpuLabVendorService",
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
            memoryUsed = Gauge(usedPercent, "%", MetricQuality.SYSTEM_SERVICE, "ActivityManager"),
            memoryAvailable = Gauge(availableMb, " MB", MetricQuality.SYSTEM_SERVICE, "ActivityManager"),
            appPss = Gauge(appPssMb, " MB", MetricQuality.MEASURED, "Debug.getPss"),
            displayHz = hz?.let {
                Gauge(it, " Hz", MetricQuality.SYSTEM_SERVICE, "Display.getRefreshRate")
            } ?: Gauge(source = "Display.getRefreshRate"),
            producedFps = producedFps?.let {
                Gauge(it, " fps", MetricQuality.MEASURED, "primary BufferQueue producer")
            } ?: Gauge(source = "primary BufferQueue producer · sample baseline pending"),
            missedFrames = missedFrames,
            suspectedUnderruns = missedFrames,
            exactUnderruns = vendor?.underrunCount?.takeIf { it >= 0L } ?: kernel.exactUnderruns,
            exactUnderrunSource = if (vendor?.underrunCount?.let { it >= 0L } == true) {
                "IDpuLabVendorService v${vendor.apiVersion}"
            } else {
                kernel.exactUnderrunSource
            },
            gpuBusy = kernel.gpuBusy,
            gpuFrequency = kernel.gpuFrequency,
            busBusy = vendorBus?.let {
                Gauge(it, "%", MetricQuality.HARDWARE_COUNTER, "IDpuLabVendorService")
            } ?: kernel.busBusy,
            generatedBandwidth = generatedGbps?.let {
                Gauge(it, " Gbps", MetricQuality.MEASURED, "memory load generator")
            } ?: Gauge(source = "memory load generator · sample baseline pending"),
            dpuBusy = vendorDpu?.let {
                Gauge(it, "%", MetricQuality.HARDWARE_COUNTER, "IDpuLabVendorService")
            } ?: kernel.dpuBusy,
            hwcDeviceLayers = vendor?.deviceLayers ?: lastComposition.deviceLayers,
            hwcClientLayers = vendor?.clientLayers ?: lastComposition.clientLayers,
            surfaceFlingerHwcMissed = lastComposition.hwcMissedFrames,
            surfaceFlingerGpuMissed = lastComposition.gpuMissedFrames,
            thermalStatus = thermalStatus,
            thermalLabel = thermalLabel(thermalStatus),
            memoryLow = memoryInfo.lowMemory || loadManager.hasMemoryAllocationFailure(),
            npuState = loadManager.npuStatus(),
        )
    }

    fun directSensorReadings(): List<SensorReading> = latestReadings

    fun hasDumpPermission(): Boolean = surfaceFlinger.hasDumpPermission()

    fun hasNpuAdapter(): Boolean = loadManager.hasNpuAdapter()

    fun hasSbwcAdapter(): Boolean = vendorBridge.supportsSbwc()

    fun setCompressionRoute(route: PixelRoute): Boolean = vendorBridge.setCompressionRoute(route)

    fun close() {
        vendorBridge.close()
    }

    private fun readCpuTimes(): CpuTimes? = runCatching {
        val fields = File("/proc/stat").bufferedReader().use { it.readLine() }
            .trim()
            .split(Regex("""\s+"""))
            .drop(1)
            .map { it.toLong() }
        if (fields.size < 5) return null
        CpuTimes(
            idle = Math.addExact(fields[3], fields.getOrElse(4) { 0L }),
            total = fields.checkedSum(),
        )
    }.getOrNull()

    private fun readHardwareCpuTimes(): CpuTimes? = runCatching {
        val usages = hardwareProperties.cpuUsages.filterNotNull()
        if (usages.isEmpty()) return null
        if (usages.any { it.active < 0L || it.total <= 0L || it.active > it.total }) return null
        val active = usages.map { it.active }.checkedSum()
        val total = usages.map { it.total }.checkedSum()
        if (total <= 0) return null
        CpuTimes(idle = total - active, total = total)
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

internal data class CpuTimes(val idle: Long, val total: Long) {
    fun percentSince(previous: CpuTimes?): Float? {
        previous ?: return null
        if (!isValid() || !previous.isValid()) return null
        if (total <= previous.total || idle < previous.idle) return null
        val totalDelta = total - previous.total
        val idleDelta = idle - previous.idle
        if (idleDelta > totalDelta) return null
        return ((totalDelta - idleDelta) * 100.0 / totalDelta)
            .takeIf(Double::isFinite)
            ?.toFloat()
    }

    private fun isValid(): Boolean = idle >= 0L && total > 0L && idle <= total
}

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
