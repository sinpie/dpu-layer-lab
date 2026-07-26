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
import com.example.dpulayerlab.model.HwcCompositionEvidenceAvailability
import com.example.dpulayerlab.model.MetricQuality
import com.example.dpulayerlab.model.SensorReading
import com.example.dpulayerlab.model.TelemetrySnapshot
import com.example.dpulayerlab.model.PixelRoute
import com.example.dpulayerlab.vendor.VendorBridge
import com.example.dpulayerlab.vendor.VendorCapabilityIsolationToken
import com.example.dpulayerlab.vendor.VendorShutdownResult
import com.example.dpulayerlab.vendor.validVendorFrequencyHz
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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

data class SystemMonitorShutdownResult(
    val localSampleLaneStopped: Boolean,
    val surfaceFlingerStopped: Boolean,
    val vendor: VendorShutdownResult,
)

internal data class SystemMonitorSampleRequest(
    val displayRefreshRateHz: Float?,
    val surfaceFlingerProbePolicy: SurfaceFlingerProbePolicy,
)

internal enum class SurfaceFlingerProbePolicy {
    /** Dashboard/idle diagnostics may refresh the bounded dump on its normal cadence. */
    PERIODIC,

    /** An active typed target accepts a fresh vendor pair but never starts another dump process. */
    TYPED_BOUNDARY,

    /** The process-session one-shot candidate may use one SurfaceFlinger fallback snapshot. */
    CALIBRATION_ONESHOT,

    /** Never spawn a diagnostic process in an untyped active load interval. */
    SUPPRESS_DURING_LOAD,
}

internal class SystemMonitorSampleException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

private data class SystemMonitorDependencies(
    val activityManager: ActivityManager,
    val powerManager: PowerManager,
    val hardwareProperties: HardwarePropertiesManager,
    val kernelSensors: KernelSensorProvider,
    val surfaceFlinger: SurfaceFlingerProbe,
    val vendorBridge: VendorBridge,
)

class SystemMonitor private constructor(
    dependencies: SystemMonitorDependencies,
    private val frameTracker: FrameTracker,
    private val loadManager: LoadManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val activityManager = dependencies.activityManager
    private val powerManager = dependencies.powerManager
    private val hardwareProperties = dependencies.hardwareProperties
    private val kernelSensors = dependencies.kernelSensors
    private val surfaceFlinger = dependencies.surfaceFlinger
    private val vendorBridge = dependencies.vendorBridge
    private var lastCpuBaseline: CpuCounterBaseline? = null
    private var lastAppCpuMs = Process.getElapsedCpuTime()
    private var lastWallMs = SystemClock.elapsedRealtime()
    private var lastRateSampleMs: Long? = null
    private var lastCompositionEvidence: CompositionEvidence? = null
    private var verifiedVendorCompositionSession: Long? = null
    private var compositionSamplesUntilProbe = 0
    @Volatile
    private var latestReadings: List<SensorReading> = emptyList()
    private val sampleContinuityLost = AtomicBoolean(false)
    private val sampleLane = SingleFlightInputProbeLane<SystemMonitorSampleRequest, TelemetrySnapshot>(
        threadName = "DpuLab-SystemMonitor",
        timeoutMs = SYSTEM_MONITOR_SAMPLE_TIMEOUT_MS,
        shutdownTimeoutMs = SAMPLE_SHUTDOWN_TIMEOUT_MS,
    ) { request, cancellation ->
        try {
            sampleOnWorker(request, cancellation)
        } catch (error: Throwable) {
            sampleContinuityLost.set(true)
            throw error
        } finally {
            if (cancellation.isCancellationRequested()) {
                sampleContinuityLost.set(true)
            }
        }
    }

    companion object {
        /**
         * Builds owned probe state transactionally. In particular, a failure while acquiring the
         * shared vendor bridge or constructing the sample lane cannot leak the already-acquired
         * SurfaceFlinger lane lease.
         *
         * [VendorBridge] is deliberately not registered in this rollback: it is a process
         * singleton already owned by [LoadManager]. The controller-level construction transaction
         * closes that owner if a later dependency fails.
         */
        fun create(
            context: Context,
            frameTracker: FrameTracker,
            loadManager: LoadManager,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): SystemMonitor {
            // Only the application Context crosses into process-owned diagnostic/Binder state.
            val appContext = context.applicationContext
            val activityManager = checkNotNull(
                appContext.getSystemService(ActivityManager::class.java),
            ) {
                "ActivityManager unavailable"
            }
            val powerManager = checkNotNull(
                appContext.getSystemService(PowerManager::class.java),
            ) {
                "PowerManager unavailable"
            }
            val hardwareProperties = checkNotNull(
                appContext.getSystemService(HardwarePropertiesManager::class.java),
            ) {
                "HardwarePropertiesManager unavailable"
            }
            val kernelSensors = KernelSensorProvider(appContext)
            // The local val becomes the owner immediately when construction returns. This avoids
            // allocating a rollback closure/list after acquiring the shared probe lane.
            val surfaceFlinger = SurfaceFlingerProbe(appContext)
            return try {
                val vendorBridge = VendorBridge.get(appContext)
                SystemMonitor(
                    dependencies = SystemMonitorDependencies(
                        activityManager = activityManager,
                        powerManager = powerManager,
                        hardwareProperties = hardwareProperties,
                        kernelSensors = kernelSensors,
                        surfaceFlinger = surfaceFlinger,
                        vendorBridge = vendorBridge,
                    ),
                    frameTracker = frameTracker,
                    loadManager = loadManager,
                    ioDispatcher = ioDispatcher,
                )
            } catch (error: Throwable) {
                try {
                    if (!surfaceFlinger.closeWithResult()) {
                        runCatching {
                            error.addSuppressed(
                                IllegalStateException(
                                    "SurfaceFlinger probe rollback was not confirmed",
                                ),
                            )
                        }
                    }
                } catch (cleanupError: Throwable) {
                    if (cleanupError !== error) {
                        runCatching { error.addSuppressed(cleanupError) }
                    }
                }
                throw error
            }
        }
    }

    internal suspend fun sample(
        display: Display?,
        surfaceFlingerProbePolicy: SurfaceFlingerProbePolicy =
            SurfaceFlingerProbePolicy.PERIODIC,
        sampleTimeoutMs: Long = SYSTEM_MONITOR_SAMPLE_TIMEOUT_MS,
    ): TelemetrySnapshot {
        require(sampleTimeoutMs > 0L)
        val boundedSampleTimeoutMs =
            minOf(sampleTimeoutMs, SYSTEM_MONITOR_SAMPLE_TIMEOUT_MS)
        // Capture only immutable scalar input before handing work to the process-owned lane. A
        // timed-out worker therefore cannot retain an Activity, Window, or Display object.
        val request = SystemMonitorSampleRequest(
            displayRefreshRateHz = display
                ?.refreshRate
                ?.takeIf { it.isFinite() && it > 0f },
            surfaceFlingerProbePolicy = surfaceFlingerProbePolicy,
        )
        val result = try {
            runInterruptible(ioDispatcher) {
                sampleLane.execute(request, boundedSampleTimeoutMs)
            }
        } catch (cancelled: CancellationException) {
            sampleContinuityLost.set(true)
            throw cancelled
        }
        return when (result) {
            is ProbeLaneResult.Completed -> result.value
            is ProbeLaneResult.Failed -> {
                sampleContinuityLost.set(true)
                throw SystemMonitorSampleException(
                    "telemetry sample failed: ${result.error.javaClass.simpleName}",
                    nonFatalTelemetryFailure(result.error),
                )
            }
            ProbeLaneResult.Busy -> sampleFailure("previous telemetry sample is still active")
            ProbeLaneResult.Closed -> sampleFailure("telemetry sample lane is closed")
            ProbeLaneResult.Cancelled -> sampleFailure("telemetry sample was cancelled")
            ProbeLaneResult.Interrupted -> sampleFailure("telemetry sample was interrupted")
            ProbeLaneResult.TimedOut -> sampleFailure(
                "telemetry sample exceeded ${boundedSampleTimeoutMs}ms",
            )
        }
    }

    /**
     * Non-closing actual-completion barrier used around the one-shot 20-layer calibration.
     *
     * Order matters: once the outer sample lane is idle it can no longer start a nested vendor or
     * SurfaceFlinger operation, so the two inner process lanes can then be drained without a
     * submit-after-check race.
     */
    internal suspend fun awaitCalibrationSampleQuiescent(
        timeoutMs: Long = SYSTEM_MONITOR_CALIBRATION_QUIESCE_TIMEOUT_MS,
    ): Boolean {
        require(timeoutMs >= 0L)
        val boundedTimeoutMs =
            minOf(timeoutMs, SYSTEM_MONITOR_CALIBRATION_QUIESCE_TIMEOUT_MS)
        return runInterruptible(ioDispatcher) {
            awaitCalibrationQuiescenceStages(
                timeoutMs = boundedTimeoutMs,
                awaitLocalSampleLane = sampleLane::awaitIdle,
                awaitSurfaceFlinger = surfaceFlinger::awaitQuiescent,
                awaitVendorTelemetry = vendorBridge::awaitTelemetryQuiescent,
            )
        }
    }

    internal fun acquireCalibrationCapabilityIsolation():
        VendorCapabilityIsolationToken? =
        vendorBridge.acquireCalibrationCapabilityIsolation()

    internal fun releaseCalibrationCapabilityIsolation(
        token: VendorCapabilityIsolationToken,
    ): Boolean =
        vendorBridge.releaseCalibrationCapabilityIsolation(token)

    private fun sampleFailure(message: String): Nothing {
        sampleContinuityLost.set(true)
        throw SystemMonitorSampleException(message)
    }

    private fun sampleOnWorker(
        request: SystemMonitorSampleRequest,
        cancellation: ProbeCancellation,
    ): TelemetrySnapshot {
        if (sampleContinuityLost.getAndSet(false)) {
            resetSampleBaselines()
        }
        ensureSampleActive(cancellation)
        val cpuIntervalStartedMs = SystemClock.elapsedRealtime()
        val hardwareCpuNow = readHardwareCpuTimes()
        val procCpuNow = if (hardwareCpuNow == null) readCpuTimes() else null
        val cpuCounterSource = if (hardwareCpuNow != null) {
            CpuCounterSource.HARDWARE_PROPERTIES
        } else {
            CpuCounterSource.PROC_STAT
        }
        val cpuInterval = evaluateCpuCounterInterval(
            source = cpuCounterSource,
            current = hardwareCpuNow ?: procCpuNow,
            previous = lastCpuBaseline,
        )
        lastCpuBaseline = cpuInterval.nextBaseline
        val cpuPercent = cpuInterval.percent
        val cpuSource = cpuCounterSource.provenance
        val cpuQuality = cpuCounterSource.quality

        val wallDelta = cpuIntervalStartedMs - lastWallMs
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
        lastWallMs = cpuIntervalStartedMs

        val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val memory = normalizedMemoryMetrics(
            totalBytes = memoryInfo.totalMem,
            availableBytes = memoryInfo.availMem,
            appPssKilobytes = Debug.getPss().toLong(),
        )

        ensureSampleActive(cancellation)
        val hz = request.displayRefreshRateHz
        hz?.let(frameTracker::updateExpectedRefresh)

        val compositionDecisionMonotonicMs = SystemClock.elapsedRealtime()
        val calibrationVendorPrefetched =
            request.surfaceFlingerProbePolicy == SurfaceFlingerProbePolicy.CALIBRATION_ONESHOT
        // Calibration is the only active-load policy allowed to spawn a SurfaceFlinger child.
        // Read the current vendor transaction first so a complete atomic pair can avoid that
        // perturbation. Keep this exact snapshot for the remainder of the sample; a null/partial
        // result must fall back to SurfaceFlinger, not trigger a second vendor request.
        val prefetchedCalibrationVendor =
            if (calibrationVendorPrefetched) {
                // Capacity needs only the atomic v1 D/C pair. Avoid the optional v2 transaction so
                // GPU/frequency telemetry cannot add another Binder worker under the 20L burst.
                vendorBridge.snapshot(includeExtendedTelemetry = false)
            } else {
                null
            }
        val prefetchedCalibrationVendorCompletedMonotonicMs =
            prefetchedCalibrationVendor?.let {
                completedEvidenceMonotonicMs(
                    sampleStartedMs = cpuIntervalStartedMs,
                    evidenceCompletedMs = SystemClock.elapsedRealtime(),
                )
            }
        ensureSampleActive(cancellation)
        val vendorSessionBeforeCompositionProbe = vendorBridge.currentServiceSession()
        val freshCalibrationVendorPairAvailable =
            calibrationVendorPrefetched &&
                nextVerifiedVendorCompositionSession(
                    snapshotServiceSession = prefetchedCalibrationVendor?.serviceSession,
                    currentServiceSession = vendorSessionBeforeCompositionProbe,
                    deviceLayers = prefetchedCalibrationVendor?.deviceLayers,
                    clientLayers = prefetchedCalibrationVendor?.clientLayers,
                ) != null
        val vendorCompositionPathReady = vendorCompositionPathCanReplaceSurfaceFlinger(
            verifiedVendorCompositionSession = verifiedVendorCompositionSession,
            currentVendorServiceSession = vendorSessionBeforeCompositionProbe,
        )
        val surfaceFlingerProbeRequested =
            shouldRunSurfaceFlingerCompositionProbe(
                policy = request.surfaceFlingerProbePolicy,
                samplesUntilProbe = compositionSamplesUntilProbe,
                evidence = lastCompositionEvidence,
                observedMonotonicMs = compositionDecisionMonotonicMs,
                maxAgeMs = SURFACE_FLINGER_EVIDENCE_MAX_AGE_MS,
                vendorCompositionPathReady = vendorCompositionPathReady,
                freshCalibrationVendorPairAvailable = freshCalibrationVendorPairAvailable,
            )
        val vendorTelemetryQuiescentForFallback =
            if (
                surfaceFlingerProbeRequested &&
                request.surfaceFlingerProbePolicy ==
                    SurfaceFlingerProbePolicy.CALIBRATION_ONESHOT
            ) {
                // A timed-out Binder Future may keep running after snapshot() returns null.
                // Never overlap that worker with a dumpsys child under the 20-layer candidate.
                vendorBridge.awaitTelemetryQuiescent(
                    CALIBRATION_VENDOR_FALLBACK_QUIESCE_TIMEOUT_MS,
                )
            } else {
                true
            }
        ensureSampleActive(cancellation)
        if (
            surfaceFlingerProbeRequested &&
            surfaceFlingerFallbackMayStart(
                policy = request.surfaceFlingerProbePolicy,
                vendorTelemetryQuiescent = vendorTelemetryQuiescentForFallback,
            )
        ) {
            val composition = surfaceFlinger.sample()
            lastCompositionEvidence = CompositionEvidence(
                snapshot = composition,
                completedMonotonicMs = SystemClock.elapsedRealtime(),
            )
            compositionSamplesUntilProbe = SURFACE_FLINGER_SAMPLE_EVERY_N_SNAPSHOTS - 1
        } else if (surfaceFlingerProbeRequested) {
            lastCompositionEvidence = CompositionEvidence(
                snapshot = CompositionSnapshot(
                    detail =
                        "SurfaceFlinger fallback suppressed because vendor telemetry " +
                            "worker completion was not confirmed",
                    compositionAvailability =
                        SurfaceFlingerCompositionAvailability.PROBE_FAILED,
                ),
                completedMonotonicMs = SystemClock.elapsedRealtime(),
            )
            compositionSamplesUntilProbe = 0
        } else if (
            shouldDiscardCachedSurfaceFlingerEvidence(
                request.surfaceFlingerProbePolicy,
            )
        ) {
            // A pre-run dump must not masquerade as evidence for an untyped load step. Preserve
            // the old sample in history, but project an explicit N/A until a fresh vendor pair or
            // an explicit typed-boundary dump exists.
            lastCompositionEvidence = null
            compositionSamplesUntilProbe = 0
        } else if (
            vendorCompositionPathReady ||
            freshCalibrationVendorPairAvailable
        ) {
            // Keep the fallback due. If the vendor pair disappears, or the active run ends, the
            // next eligible sample probes immediately instead of inheriting a hidden cadence.
            compositionSamplesUntilProbe = 0
        } else {
            compositionSamplesUntilProbe -= 1
        }
        ensureSampleActive(cancellation)
        // Non-calibration exact counters are intentionally sampled after a SurfaceFlinger probe.
        // A caller waiting for a pre-phase baseline can then start immediately after this sample.
        // Calibration already captured its one vendor transaction above so it can decide whether
        // the lower-overhead atomic pair makes a SurfaceFlinger child unnecessary.
        val vendor =
            if (calibrationVendorPrefetched) {
                prefetchedCalibrationVendor
            } else {
                vendorBridge.snapshot()
            }
        val vendorEvidenceCompletedMonotonicMs =
            if (calibrationVendorPrefetched) {
                prefetchedCalibrationVendorCompletedMonotonicMs
            } else {
                vendor?.let {
                    completedEvidenceMonotonicMs(
                        sampleStartedMs = cpuIntervalStartedMs,
                        evidenceCompletedMs = SystemClock.elapsedRealtime(),
                    )
                }
            }
        ensureSampleActive(cancellation)
        // Registration continuity is local state and must remain observable when the remote
        // metrics call times out under load. Only a real disconnect/reconnect changes this ID.
        val currentVendorServiceSession = vendorBridge.currentServiceSession()
        verifiedVendorCompositionSession = nextVerifiedVendorCompositionSession(
            snapshotServiceSession = vendor?.serviceSession,
            currentServiceSession = currentVendorServiceSession,
            deviceLayers = vendor?.deviceLayers,
            clientLayers = vendor?.clientLayers,
        )
        val kernel = kernelSensors.sample()
        ensureSampleActive(cancellation)
        val vendorSource = vendor?.let {
            vendorServiceSource(it.apiVersion, it.serviceSession)
        }

        val vendorDpu = vendor?.dpuUtilization?.validUtilizationPercent()
        val vendorBus = vendor?.busUtilization?.validUtilizationPercent()
        val vendorGpu = vendor?.gpuUtilization?.validUtilizationPercent()
        val vendorGpuFrequencyMhz = normalizedVendorFrequencyMhz(vendor?.gpuFrequencyHz)
        val vendorDpuFrequencyMhz = normalizedVendorFrequencyMhz(vendor?.dpuFrequencyHz)
        val vendorBrokerAvailability = vendorBridge.brokerBindingAvailability()

        // Read and reset both counters at the same point in every sample. The monitor loop can
        // slip while dumpsys/sysfs is slow, so rates must use this real interval instead of
        // assuming that every invocation represents exactly one second.
        ensureSampleActive(cancellation)
        val rateSampleNow = SystemClock.elapsedRealtime()
        val rateDeltaMs = lastRateSampleMs?.let { rateSampleNow - it }?.takeIf { it > 0L }
        val producedFrames = frameTracker.sampleProducedFrames()
        val generatedBytes = loadManager.sampleAndResetBandwidthBytes()
        lastRateSampleMs = rateSampleNow
        val producedFps = normalizedPerSecond(producedFrames, rateDeltaMs)
        val generatedGbps = normalizedGigabitsPerSecond(generatedBytes, rateDeltaMs)
        val missedFrames = frameTracker.totalMissedFrames()
        val thermalStatus = powerManager.currentThermalStatus
        ensureSampleActive(cancellation)
        val memoryLow = memoryInfo.lowMemory || loadManager.hasMemoryAllocationFailure()
        val npuState = loadManager.npuStatus(vendor?.npuStatus)
        val powerSaveMode = powerManager.isPowerSaveMode
        ensureSampleActive(cancellation)
        // CPU deltas use the interval-start clock above. Report/HUD ordering instead uses a
        // terminal evidence timestamp after every counter and state read completed.
        val evidenceMonotonicMs = completedEvidenceMonotonicMs(
            sampleStartedMs = cpuIntervalStartedMs,
            evidenceCompletedMs = SystemClock.elapsedRealtime(),
        )
        val compositionEvidence = projectCompositionEvidence(
            evidence = lastCompositionEvidence,
            observedMonotonicMs = evidenceMonotonicMs,
            maxAgeMs = SURFACE_FLINGER_EVIDENCE_MAX_AGE_MS,
        )
        val composition = compositionEvidence.snapshot
        val hwcCompositionEvidence = selectHwcCompositionEvidence(
            vendorDeviceLayers = vendor?.deviceLayers,
            vendorClientLayers = vendor?.clientLayers,
            vendorSource = vendorSource,
            vendorCompletedMonotonicMs = vendorEvidenceCompletedMonotonicMs,
            vendorSessionVerified =
                vendorCompositionPathCanReplaceSurfaceFlinger(
                    verifiedVendorCompositionSession =
                        verifiedVendorCompositionSession,
                    currentVendorServiceSession = currentVendorServiceSession,
                ),
            surfaceFlinger = compositionEvidence,
            observedMonotonicMs = evidenceMonotonicMs,
            maxAgeMs = HWC_COMPOSITION_EVIDENCE_MAX_AGE_MS,
        )
        val hwcCompositionEvidenceAvailability =
            classifyHwcCompositionEvidenceAvailability(
                policy = request.surfaceFlingerProbePolicy,
                selected = hwcCompositionEvidence,
                vendorDeviceLayers = vendor?.deviceLayers,
                vendorClientLayers = vendor?.clientLayers,
                vendorSource = vendorSource,
                vendorCompletedMonotonicMs = vendorEvidenceCompletedMonotonicMs,
                vendorSessionVerified =
                    vendorCompositionPathCanReplaceSurfaceFlinger(
                        verifiedVendorCompositionSession =
                            verifiedVendorCompositionSession,
                        currentVendorServiceSession = currentVendorServiceSession,
                    ),
                surfaceFlinger = compositionEvidence,
                observedMonotonicMs = evidenceMonotonicMs,
                maxAgeMs = HWC_COMPOSITION_EVIDENCE_MAX_AGE_MS,
            )
        latestReadings = buildList {
            vendorBrokerAvailability.failure?.let { failure ->
                add(
                    SensorReading(
                        "vendor_broker",
                        "Vendor broker",
                        "UNAVAILABLE · ${failure.code.name}",
                        MetricQuality.UNAVAILABLE,
                        failure.detail,
                    ),
                )
            }
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
            vendorGpu?.let { gpu ->
                add(
                    SensorReading(
                        "vendor_gpu",
                        "GPU busy",
                        "$gpu%",
                        MetricQuality.HARDWARE_COUNTER,
                        checkNotNull(vendorSource),
                    ),
                )
            }
            vendorGpuFrequencyMhz?.let { mhz ->
                add(
                    SensorReading(
                        "vendor_gpu_frequency",
                        "GPU clock",
                        "%.0f MHz".format(mhz),
                        MetricQuality.HARDWARE_COUNTER,
                        checkNotNull(vendorSource),
                    ),
                )
            }
            vendorDpuFrequencyMhz?.let { mhz ->
                add(
                    SensorReading(
                        "vendor_dpu_frequency",
                        "DPU clock",
                        "%.0f MHz".format(mhz),
                        MetricQuality.HARDWARE_COUNTER,
                        checkNotNull(vendorSource),
                    ),
                )
            }
            composition.hwcMissedFrames?.let { missed ->
                add(
                    SensorReading(
                        "sf_hwc_missed",
                        "HWC missed frames",
                        missed.toString(),
                        MetricQuality.PROXY,
                        composition.source,
                    ),
                )
            }
            composition.gpuMissedFrames?.let { missed ->
                add(
                    SensorReading(
                        "sf_gpu_missed",
                        "GPU missed frames",
                        missed.toString(),
                        MetricQuality.PROXY,
                        composition.source,
                    ),
                )
            }
            addAll(kernel.readings)
        }

        return TelemetrySnapshot(
            monotonicMs = evidenceMonotonicMs,
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
            gpuBusy = preferHardwareCounterGauge(
                value = vendorGpu,
                unit = "%",
                source = vendorSource,
                fallback = kernel.gpuBusy,
            ),
            gpuFrequency = preferHardwareCounterGauge(
                value = vendorGpuFrequencyMhz,
                unit = " MHz",
                source = vendorSource,
                fallback = kernel.gpuFrequency,
            ),
            busBusy = preferHardwareCounterGauge(
                value = vendorBus,
                unit = "%",
                source = vendorSource,
                fallback = kernel.busBusy,
            ),
            generatedBandwidth = generatedGbps?.let {
                Gauge(it, " Gbps", MetricQuality.MEASURED, "memory load generator")
            } ?: Gauge(source = "memory load generator · sample baseline pending"),
            dpuBusy = preferHardwareCounterGauge(
                value = vendorDpu,
                unit = "%",
                source = vendorSource,
                fallback = kernel.dpuBusy,
            ),
            dpuFrequency = preferHardwareCounterGauge(
                value = vendorDpuFrequencyMhz,
                unit = " MHz",
                source = vendorSource,
                fallback = kernel.dpuFrequency,
            ),
            hwcDeviceLayers = hwcCompositionEvidence.deviceLayers,
            hwcDeviceLayersQuality = hwcCompositionEvidence.quality,
            hwcDeviceLayersSource = hwcCompositionEvidence.source,
            hwcClientLayers = hwcCompositionEvidence.clientLayers,
            hwcClientLayersQuality = hwcCompositionEvidence.quality,
            hwcClientLayersSource = hwcCompositionEvidence.source,
            hwcCompositionEvidenceMonotonicMs =
                hwcCompositionEvidence.completedMonotonicMs,
            hwcCompositionEvidenceAgeMs = hwcCompositionEvidence.ageMs,
            hwcCompositionEvidenceAvailability = hwcCompositionEvidenceAvailability,
            surfaceFlingerHwcMissed = composition.hwcMissedFrames,
            surfaceFlingerGpuMissed = composition.gpuMissedFrames,
            surfaceFlingerMissSource = if (
                composition.hwcMissedFrames != null ||
                composition.gpuMissedFrames != null
            ) {
                composition.source
            } else {
                ""
            },
            surfaceFlingerEvidenceMonotonicMs =
                compositionEvidence.completedMonotonicMs,
            surfaceFlingerEvidenceAgeMs = compositionEvidence.ageMs,
            thermalStatus = thermalStatus,
            thermalLabel = thermalLabel(thermalStatus),
            memoryLow = memoryLow,
            powerSaveMode = powerSaveMode,
            vendorServiceSession = currentVendorServiceSession,
            compressionState = when {
                vendor != null -> vendor.compressionState.ifBlank { "Unknown" }
                currentVendorServiceSession != null -> "Unavailable · snapshot timeout"
                vendorBrokerAvailability.failure != null ->
                    "Unavailable · ${vendorBrokerAvailability.failure.code.name}"
                else -> "Adapter 없음"
            },
            // Reuse the vendor transaction already completed at the start of this sample.
            // NPU status must not trigger a second snapshot or contend with control-to-zero.
            npuState = npuState,
        )
    }

    private fun resetSampleBaselines() {
        lastCpuBaseline = null
        lastAppCpuMs = Process.getElapsedCpuTime()
        lastWallMs = SystemClock.elapsedRealtime()
        lastRateSampleMs = null
        lastCompositionEvidence = null
        verifiedVendorCompositionSession = null
        compositionSamplesUntilProbe = 0
        kernelSensors.resetCumulativeBaselines()
        // A detached/timed-out sample may have consumed part of these interval counters. Discard
        // the remainder so the next accepted value starts from one explicit fresh baseline.
        frameTracker.sampleProducedFrames()
        loadManager.sampleAndResetBandwidthBytes()
    }

    private fun ensureSampleActive(cancellation: ProbeCancellation) {
        if (cancellation.isCancellationRequested() || Thread.currentThread().isInterrupted) {
            throw InterruptedException("telemetry sample cancelled")
        }
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
    ): SystemMonitorShutdownResult {
        val localSampleLaneStopped = stopLocalSamplingForShutdown()
        // Cancel the process-owned diagnostic lane before tearing down vendor telemetry. Its
        // bounded close prevents a stuck dumpsys pipe from retaining this monitor indefinitely.
        val surfaceFlingerStopped = surfaceFlinger.closeWithResult()
        return SystemMonitorShutdownResult(
            localSampleLaneStopped = localSampleLaneStopped,
            surfaceFlingerStopped = surfaceFlingerStopped,
            vendor = vendorBridge.closeWithResult(resetCompression),
        )
    }

    /**
     * First shutdown stage. It prevents new samples, cancels the active request and waits for the
     * actual worker `finally`. The backend owner must call this before closing [loadManager], whose
     * counters and NPU status are read by the sample worker.
     */
    internal fun stopLocalSamplingForShutdown(): Boolean =
        sampleLane.closeWithResult()

    private fun readCpuTimes(): CpuTimes? =
        try {
            File("/proc/stat").bufferedReader().use { reader ->
                parseProcStatCpuLine(reader.readLine())
            }
        } catch (_: Exception) {
            null
        }

    private fun readHardwareCpuTimes(): CpuTimes? {
        return try {
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
        } catch (_: Exception) {
            null
        }
    }

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

/**
 * [SingleFlightInputProbeLane] publishes worker completion before returning a failed result.
 * Preserve that cleanup boundary, but never downgrade a fatal VM/process error to an ordinary
 * telemetry gap that the controller might continue past.
 */
internal fun nonFatalTelemetryFailure(error: Throwable): Throwable {
    if (error is Error) throw error
    return error
}

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

internal fun normalizedVendorFrequencyMhz(frequencyHz: Long?): Float? {
    val validHz = frequencyHz?.let(::validVendorFrequencyHz) ?: return null
    return (validHz.toDouble() / HZ_PER_MHZ)
        .takeIf { it.isFinite() && it <= Float.MAX_VALUE }
        ?.toFloat()
}

internal fun preferHardwareCounterGauge(
    value: Float?,
    unit: String,
    source: String?,
    fallback: Gauge,
): Gauge {
    val validValue = value?.takeIf(Float::isFinite) ?: return fallback
    val validSource = source?.takeIf(String::isNotBlank) ?: return fallback
    return Gauge(
        value = validValue,
        unit = unit,
        quality = MetricQuality.HARDWARE_COUNTER,
        source = validSource,
    )
}

/**
 * Small construction-only ownership stack. It is intentionally bounded and single-threaded:
 * callers register cleanup immediately after acquiring each resource, then commit exactly once.
 */
internal class BoundedConstructionRollback(
    private val maxActions: Int = MAX_CONSTRUCTION_ROLLBACK_ACTIONS,
) {
    private val actions = ArrayDeque<() -> Unit>()
    private var finished = false

    init {
        require(maxActions > 0) { "maxActions must be positive" }
    }

    fun own(action: () -> Unit) {
        check(!finished) { "construction rollback is already finished" }
        check(actions.size < maxActions) { "construction rollback action limit exceeded" }
        actions.addLast(action)
    }

    fun commit() {
        check(!finished) { "construction rollback is already finished" }
        finished = true
        actions.clear()
    }

    fun rollbackInto(primary: Throwable) {
        if (finished) return
        finished = true
        while (actions.isNotEmpty()) {
            try {
                actions.removeLast().invoke()
            } catch (cleanupError: Throwable) {
                if (cleanupError !== primary) {
                    runCatching { primary.addSuppressed(cleanupError) }
                }
            }
        }
    }
}

internal fun <T> constructWithBoundedRollback(
    maxActions: Int = MAX_CONSTRUCTION_ROLLBACK_ACTIONS,
    construction: (BoundedConstructionRollback) -> T,
): T {
    val rollback = BoundedConstructionRollback(maxActions)
    return try {
        construction(rollback).also {
            rollback.commit()
        }
    } catch (error: Throwable) {
        rollback.rollbackInto(error)
        throw error
    }
}

internal enum class CpuCounterSource(
    val provenance: String,
    val quality: MetricQuality,
) {
    HARDWARE_PROPERTIES(
        provenance = "HardwarePropertiesManager",
        quality = MetricQuality.SYSTEM_SERVICE,
    ),
    PROC_STAT(
        provenance = "/proc/stat",
        quality = MetricQuality.MEASURED,
    ),
}

internal data class CpuCounterBaseline(
    val source: CpuCounterSource,
    val times: CpuTimes,
)

internal data class CpuCounterInterval(
    val percent: Float?,
    val nextBaseline: CpuCounterBaseline?,
)

/**
 * CPU counter encodings have independent epochs. A source transition (including recovery from an
 * unavailable read) establishes a new baseline and deliberately emits N/A for that interval.
 */
internal fun evaluateCpuCounterInterval(
    source: CpuCounterSource,
    current: CpuTimes?,
    previous: CpuCounterBaseline?,
): CpuCounterInterval {
    val validCurrent = current?.takeIf(CpuTimes::isValid)
        ?: return CpuCounterInterval(percent = null, nextBaseline = null)
    val percent = if (previous?.source == source) {
        validCurrent.percentSince(previous.times)
    } else {
        null
    }
    return CpuCounterInterval(
        percent = percent,
        nextBaseline = CpuCounterBaseline(source, validCurrent),
    )
}

internal data class CompositionEvidence(
    val snapshot: CompositionSnapshot,
    val completedMonotonicMs: Long,
)

internal data class CompositionEvidenceProjection(
    val snapshot: CompositionSnapshot,
    val completedMonotonicMs: Long?,
    val ageMs: Long?,
)

internal data class HwcCompositionEvidenceProjection(
    val deviceLayers: Int? = null,
    val clientLayers: Int? = null,
    val quality: MetricQuality = MetricQuality.UNAVAILABLE,
    val source: String = "",
    val completedMonotonicMs: Long? = null,
    val ageMs: Long? = null,
)

/**
 * A complete pair from the still-current vendor service is a lower-overhead fresh composition
 * path. Avoid spawning `dumpsys SurfaceFlinger` in that case, including for a forced typed sample:
 * the vendor snapshot collected later in the same telemetry transaction is the fresh evidence.
 */
internal fun vendorCompositionPathCanReplaceSurfaceFlinger(
    verifiedVendorCompositionSession: Long?,
    currentVendorServiceSession: Long?,
): Boolean =
    verifiedVendorCompositionSession != null &&
        verifiedVendorCompositionSession >= 0L &&
        verifiedVendorCompositionSession == currentVendorServiceSession

/**
 * Capability is session-scoped and fail-closed. A timeout, partial pair, negative value, or Binder
 * replacement clears the shortcut so the next eligible request can use SurfaceFlinger fallback.
 */
internal fun nextVerifiedVendorCompositionSession(
    snapshotServiceSession: Long?,
    currentServiceSession: Long?,
    deviceLayers: Int?,
    clientLayers: Int?,
): Long? {
    val snapshotSession = snapshotServiceSession ?: return null
    if (snapshotSession < 0L) return null
    if (snapshotSession != currentServiceSession) return null
    if (deviceLayers == null || clientLayers == null) return null
    if (deviceLayers < 0 || clientLayers < 0) return null
    return snapshotSession
}

internal fun shouldRunSurfaceFlingerCompositionProbe(
    policy: SurfaceFlingerProbePolicy,
    samplesUntilProbe: Int,
    evidence: CompositionEvidence?,
    observedMonotonicMs: Long,
    maxAgeMs: Long,
    vendorCompositionPathReady: Boolean,
    freshCalibrationVendorPairAvailable: Boolean,
): Boolean {
    require(observedMonotonicMs >= 0L)
    require(maxAgeMs >= 0L)
    // Capacity evidence must be correlated with the just-published candidate topology. An earlier
    // vendor-capability cache cannot suppress the fallback; only the complete current-sample pair
    // captured immediately before this decision can do so.
    if (
        policy != SurfaceFlingerProbePolicy.CALIBRATION_ONESHOT &&
        vendorCompositionPathReady
    ) {
        return false
    }
    return when (policy) {
        SurfaceFlingerProbePolicy.CALIBRATION_ONESHOT ->
            !freshCalibrationVendorPairAvailable
        // Active typed phases may still obtain a fresh vendor snapshot later in this telemetry
        // transaction, but never spawn a SurfaceFlinger process after session calibration.
        SurfaceFlingerProbePolicy.TYPED_BOUNDARY -> false
        SurfaceFlingerProbePolicy.SUPPRESS_DURING_LOAD -> false
        SurfaceFlingerProbePolicy.PERIODIC ->
            shouldProbeComposition(
                forceCompositionProbe = false,
                samplesUntilProbe = samplesUntilProbe,
                evidence = evidence,
                observedMonotonicMs = observedMonotonicMs,
                maxAgeMs = maxAgeMs,
            )
    }
}

/**
 * Calibration may start dumpsys only after every timed-out vendor Future has actually completed.
 * Other policies collect vendor state after (or instead of) SurfaceFlinger and do not need this
 * ordering gate.
 */
internal fun surfaceFlingerFallbackMayStart(
    policy: SurfaceFlingerProbePolicy,
    vendorTelemetryQuiescent: Boolean,
): Boolean =
    policy != SurfaceFlingerProbePolicy.CALIBRATION_ONESHOT ||
        vendorTelemetryQuiescent

internal fun shouldDiscardCachedSurfaceFlingerEvidence(
    policy: SurfaceFlingerProbePolicy,
): Boolean =
    policy == SurfaceFlingerProbePolicy.TYPED_BOUNDARY ||
        policy == SurfaceFlingerProbePolicy.SUPPRESS_DURING_LOAD

internal fun shouldProbeComposition(
    forceCompositionProbe: Boolean,
    samplesUntilProbe: Int,
    evidence: CompositionEvidence?,
    observedMonotonicMs: Long,
    maxAgeMs: Long,
): Boolean {
    require(observedMonotonicMs >= 0L)
    require(maxAgeMs >= 0L)
    return forceCompositionProbe ||
        samplesUntilProbe <= 0 ||
        compositionEvidenceNeedsRefresh(
            evidence = evidence,
            observedMonotonicMs = observedMonotonicMs,
            maxAgeMs = maxAgeMs,
        )
}

internal fun compositionEvidenceNeedsRefresh(
    evidence: CompositionEvidence?,
    observedMonotonicMs: Long,
    maxAgeMs: Long,
): Boolean {
    require(observedMonotonicMs >= 0L)
    require(maxAgeMs >= 0L)
    val completedMonotonicMs = evidence?.completedMonotonicMs ?: return true
    if (completedMonotonicMs < 0L || observedMonotonicMs < completedMonotonicMs) return true
    return observedMonotonicMs - completedMonotonicMs > maxAgeMs
}

/**
 * Projects independently timestamped SurfaceFlinger evidence into a full telemetry snapshot.
 * Values older than [maxAgeMs] become an explicit N/A gap instead of being re-stamped with the
 * newer telemetry timestamp.
 */
internal fun projectCompositionEvidence(
    evidence: CompositionEvidence?,
    observedMonotonicMs: Long,
    maxAgeMs: Long,
): CompositionEvidenceProjection {
    require(observedMonotonicMs >= 0L)
    require(maxAgeMs >= 0L)
    val completedMonotonicMs = evidence
        ?.completedMonotonicMs
        ?.takeIf { it >= 0L }
    val ageMs = completedMonotonicMs
        ?.takeIf { observedMonotonicMs >= it }
        ?.let { observedMonotonicMs - it }
    val fresh = ageMs != null && ageMs <= maxAgeMs
    return CompositionEvidenceProjection(
        snapshot = if (fresh) {
            checkNotNull(evidence).snapshot
        } else {
            CompositionSnapshot(
                source = evidence?.snapshot?.source.orEmpty(),
                detail = "SurfaceFlinger evidence unavailable or stale",
                compositionAvailability =
                    if (ageMs != null && ageMs > maxAgeMs) {
                        SurfaceFlingerCompositionAvailability.STALE
                    } else {
                        evidence?.snapshot?.compositionAvailability
                            ?: SurfaceFlingerCompositionAvailability.UNKNOWN
                    },
            )
        },
        completedMonotonicMs = completedMonotonicMs,
        ageMs = ageMs,
    )
}

/**
 * Selects DEVICE/CLIENT as one atomic evidence pair. Mixing one vendor count with one
 * SurfaceFlinger count would combine different sampling boundaries and can fabricate a topology
 * that never existed in either source.
 */
internal fun selectHwcCompositionEvidence(
    vendorDeviceLayers: Int?,
    vendorClientLayers: Int?,
    vendorSource: String?,
    vendorCompletedMonotonicMs: Long?,
    vendorSessionVerified: Boolean,
    surfaceFlinger: CompositionEvidenceProjection,
    observedMonotonicMs: Long,
    maxAgeMs: Long,
): HwcCompositionEvidenceProjection {
    require(observedMonotonicMs >= 0L)
    require(maxAgeMs >= 0L)

    fun completePair(
        deviceLayers: Int?,
        clientLayers: Int?,
        source: String?,
        quality: MetricQuality,
        completedMonotonicMs: Long?,
    ): HwcCompositionEvidenceProjection? {
        val device = deviceLayers?.takeIf { it >= 0 } ?: return null
        val client = clientLayers?.takeIf { it >= 0 } ?: return null
        val provenance = source?.takeIf(String::isNotBlank) ?: return null
        if (quality == MetricQuality.UNAVAILABLE) return null
        val completed = completedMonotonicMs
            ?.takeIf { it >= 0L && it <= observedMonotonicMs }
            ?: return null
        val ageMs = observedMonotonicMs - completed
        if (ageMs > maxAgeMs) return null
        return HwcCompositionEvidenceProjection(
            deviceLayers = device,
            clientLayers = client,
            quality = quality,
            source = provenance,
            completedMonotonicMs = completed,
            ageMs = ageMs,
        )
    }

    val verifiedVendorPair = if (vendorSessionVerified) {
        completePair(
            deviceLayers = vendorDeviceLayers,
            clientLayers = vendorClientLayers,
            source = vendorSource,
            quality = MetricQuality.HARDWARE_COUNTER,
            completedMonotonicMs = vendorCompletedMonotonicMs,
        )
    } else {
        null
    }
    return verifiedVendorPair ?: completePair(
        deviceLayers = surfaceFlinger.snapshot.deviceLayers,
        clientLayers = surfaceFlinger.snapshot.clientLayers,
        source = surfaceFlinger.snapshot.source,
        quality = MetricQuality.SYSTEM_SERVICE,
        completedMonotonicMs = surfaceFlinger.completedMonotonicMs,
    ) ?: HwcCompositionEvidenceProjection()
}

private enum class VendorCompositionPairAvailability {
    AVAILABLE,
    UNAVAILABLE,
    INVALID,
    STALE,
}

/**
 * Produces one bounded reason code for the same evidence selection published in telemetry.
 *
 * Active-load policies intentionally give the vendor path precedence in the reason: SurfaceFlinger
 * is suppressed by policy there, so a missing DUMP permission was never the cause of that sample.
 * Idle/calibration paths instead expose the typed SurfaceFlinger failure that was actually
 * observed. No localized [CompositionSnapshot.detail] text is parsed.
 */
internal fun classifyHwcCompositionEvidenceAvailability(
    policy: SurfaceFlingerProbePolicy,
    selected: HwcCompositionEvidenceProjection,
    vendorDeviceLayers: Int?,
    vendorClientLayers: Int?,
    vendorSource: String?,
    vendorCompletedMonotonicMs: Long?,
    vendorSessionVerified: Boolean,
    surfaceFlinger: CompositionEvidenceProjection,
    observedMonotonicMs: Long,
    maxAgeMs: Long,
): HwcCompositionEvidenceAvailability {
    require(observedMonotonicMs >= 0L)
    require(maxAgeMs >= 0L)

    if (
        selected.deviceLayers?.let { it >= 0 } == true &&
        selected.clientLayers?.let { it >= 0 } == true &&
        selected.quality != MetricQuality.UNAVAILABLE &&
        selected.source.isNotBlank() &&
        selected.completedMonotonicMs?.let { it in 0L..observedMonotonicMs } == true &&
        selected.ageMs?.let { it in 0L..maxAgeMs } == true
    ) {
        return HwcCompositionEvidenceAvailability.AVAILABLE
    }

    val vendorPairAvailability = when {
        vendorDeviceLayers == null && vendorClientLayers == null ->
            VendorCompositionPairAvailability.UNAVAILABLE
        vendorDeviceLayers == null ||
            vendorClientLayers == null ||
            vendorDeviceLayers < 0 ||
            vendorClientLayers < 0 ||
            !vendorSessionVerified ||
            vendorSource.isNullOrBlank() ->
            VendorCompositionPairAvailability.INVALID
        vendorCompletedMonotonicMs == null ||
            vendorCompletedMonotonicMs < 0L ||
            vendorCompletedMonotonicMs > observedMonotonicMs ->
            VendorCompositionPairAvailability.INVALID
        observedMonotonicMs - vendorCompletedMonotonicMs > maxAgeMs ->
            VendorCompositionPairAvailability.STALE
        else -> VendorCompositionPairAvailability.AVAILABLE
    }

    if (
        policy == SurfaceFlingerProbePolicy.TYPED_BOUNDARY ||
        policy == SurfaceFlingerProbePolicy.SUPPRESS_DURING_LOAD
    ) {
        return when (vendorPairAvailability) {
            VendorCompositionPairAvailability.UNAVAILABLE ->
                HwcCompositionEvidenceAvailability.ACTIVE_RUN_VENDOR_PAIR_UNAVAILABLE
            VendorCompositionPairAvailability.STALE ->
                HwcCompositionEvidenceAvailability.ACTIVE_RUN_VENDOR_PAIR_STALE
            VendorCompositionPairAvailability.AVAILABLE,
            VendorCompositionPairAvailability.INVALID ->
                HwcCompositionEvidenceAvailability.ACTIVE_RUN_VENDOR_PAIR_INVALID
        }
    }

    return when (surfaceFlinger.snapshot.compositionAvailability) {
        SurfaceFlingerCompositionAvailability.DUMP_PERMISSION_UNAVAILABLE ->
            HwcCompositionEvidenceAvailability.DUMP_PERMISSION_UNAVAILABLE
        SurfaceFlingerCompositionAvailability.PAIR_UNAVAILABLE ->
            HwcCompositionEvidenceAvailability.SURFACE_FLINGER_PAIR_UNAVAILABLE
        SurfaceFlingerCompositionAvailability.PAIR_INVALID ->
            HwcCompositionEvidenceAvailability.SURFACE_FLINGER_PAIR_INVALID
        SurfaceFlingerCompositionAvailability.STALE ->
            HwcCompositionEvidenceAvailability.SURFACE_FLINGER_EVIDENCE_STALE
        SurfaceFlingerCompositionAvailability.PROBE_FAILED ->
            HwcCompositionEvidenceAvailability.SURFACE_FLINGER_PROBE_FAILED
        SurfaceFlingerCompositionAvailability.AVAILABLE ->
            HwcCompositionEvidenceAvailability.SURFACE_FLINGER_PAIR_INVALID
        SurfaceFlingerCompositionAvailability.UNKNOWN -> when {
            surfaceFlinger.ageMs?.let { it > maxAgeMs } == true ->
                HwcCompositionEvidenceAvailability.SURFACE_FLINGER_EVIDENCE_STALE
            surfaceFlinger.snapshot.deviceLayers != null ||
                surfaceFlinger.snapshot.clientLayers != null ->
                HwcCompositionEvidenceAvailability.SURFACE_FLINGER_PAIR_INVALID
            else -> HwcCompositionEvidenceAvailability.UNAVAILABLE
        }
    }
}

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

    internal fun isValid(): Boolean =
        idle >= 0L && total > 0L && idle <= total && participantCount >= 0
}

/**
 * Parses the aggregate Linux CPU line without double-counting guest/guest_nice. Those fields are
 * already included in user/nice by the kernel ABI.
 */
internal fun parseProcStatCpuLine(line: String?): CpuTimes? {
    return try {
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
    } catch (_: Exception) {
        null
    }
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
private const val BYTES_PER_MEBIBYTE = 1_048_576.0
private const val KIBIBYTES_PER_MEBIBYTE = 1_024.0
private const val HZ_PER_MHZ = 1_000_000.0
internal const val SYSTEM_MONITOR_SAMPLE_TIMEOUT_MS = 4_000L
internal const val SYSTEM_MONITOR_CALIBRATION_QUIESCE_TIMEOUT_MS = 3_000L
internal const val CALIBRATION_VENDOR_FALLBACK_QUIESCE_TIMEOUT_MS = 500L
internal const val HWC_COMPOSITION_EVIDENCE_MAX_AGE_MS = 2_500L
internal const val SURFACE_FLINGER_EVIDENCE_MAX_AGE_MS =
    HWC_COMPOSITION_EVIDENCE_MAX_AGE_MS
private const val SURFACE_FLINGER_SAMPLE_EVERY_N_SNAPSHOTS = 3
private const val SAMPLE_SHUTDOWN_TIMEOUT_MS = 1_500L
private const val MAX_CONSTRUCTION_ROLLBACK_ACTIONS = 8

internal fun awaitCalibrationQuiescenceStages(
    timeoutMs: Long,
    monotonicNowNanos: () -> Long = System::nanoTime,
    awaitLocalSampleLane: (Long) -> Boolean,
    awaitSurfaceFlinger: (Long) -> Boolean,
    awaitVendorTelemetry: (Long) -> Boolean,
): Boolean {
    require(timeoutMs >= 0L)
    val deadlineNanos =
        monotonicNowNanos() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    fun remainingMs(): Long =
        (deadlineNanos - monotonicNowNanos())
            .takeIf { it > 0L }
            ?.let { TimeUnit.NANOSECONDS.toMillis(it) }
            ?: 0L
    return awaitLocalSampleLane(remainingMs()) &&
        awaitSurfaceFlinger(remainingMs()) &&
        awaitVendorTelemetry(remainingMs())
}

internal fun completedEvidenceMonotonicMs(
    sampleStartedMs: Long,
    evidenceCompletedMs: Long,
): Long {
    require(sampleStartedMs >= 0L)
    require(evidenceCompletedMs >= 0L)
    return maxOf(sampleStartedMs, evidenceCompletedMs)
}
