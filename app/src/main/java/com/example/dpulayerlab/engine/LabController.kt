package com.example.dpulayerlab.engine

import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.content.res.AssetFileDescriptor
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import com.example.dpulayerlab.model.BufferSize
import com.example.dpulayerlab.model.DecoderLinearReference
import com.example.dpulayerlab.model.Gauge
import com.example.dpulayerlab.model.HwcCompositionExpectation
import com.example.dpulayerlab.model.LayerBackend
import com.example.dpulayerlab.model.LayerSizeProfile
import com.example.dpulayerlab.model.LOAD_CONTROL_CADENCE_MS
import com.example.dpulayerlab.model.MIN_EFFECTIVE_LOAD
import com.example.dpulayerlab.model.LoadTransitionEvaluator
import com.example.dpulayerlab.model.hasGpuLoadProducer
import com.example.dpulayerlab.model.LoadSetpoints
import com.example.dpulayerlab.model.MetricQuality
import com.example.dpulayerlab.model.MotionProfile
import com.example.dpulayerlab.model.PlanProgress
import com.example.dpulayerlab.model.PlanRunResult
import com.example.dpulayerlab.model.PlanSource
import com.example.dpulayerlab.model.PlanState
import com.example.dpulayerlab.model.PhaseSpec
import com.example.dpulayerlab.model.PixelRoute
import com.example.dpulayerlab.model.RenderSafetyLimits
import com.example.dpulayerlab.model.RunEvent
import com.example.dpulayerlab.model.RunProgress
import com.example.dpulayerlab.model.RunSummary
import com.example.dpulayerlab.model.RunVerdict
import com.example.dpulayerlab.model.RunnerStage
import com.example.dpulayerlab.model.ScenarioSafetyPolicy
import com.example.dpulayerlab.model.ScenarioPlanPolicy
import com.example.dpulayerlab.model.ScenarioRunPlan
import com.example.dpulayerlab.model.ScenarioSpec
import com.example.dpulayerlab.model.SelectedDecoderBuffer
import com.example.dpulayerlab.model.SensorReading
import com.example.dpulayerlab.model.TelemetrySnapshot
import com.example.dpulayerlab.model.TransitionMode
import com.example.dpulayerlab.model.TransitionSample
import com.example.dpulayerlab.model.TransitionSegment
import com.example.dpulayerlab.model.TransitionSpec
import com.example.dpulayerlab.model.coverageBitAt
import com.example.dpulayerlab.model.requiresSelectedDecoderProducer
import com.example.dpulayerlab.model.requiredCoverageMask
import com.example.dpulayerlab.model.terminalReason
import com.example.dpulayerlab.monitor.FrameTracker
import com.example.dpulayerlab.monitor.CapabilityScanner
import com.example.dpulayerlab.monitor.CompressionControlResult
import com.example.dpulayerlab.monitor.HWC_COMPOSITION_EVIDENCE_MAX_AGE_MS
import com.example.dpulayerlab.monitor.ProducerReadiness
import com.example.dpulayerlab.monitor.SystemMonitor
import com.example.dpulayerlab.monitor.SurfaceFlingerProbePolicy
import com.example.dpulayerlab.monitor.SYSTEM_MONITOR_CALIBRATION_QUIESCE_TIMEOUT_MS
import com.example.dpulayerlab.monitor.SYSTEM_MONITOR_SAMPLE_TIMEOUT_MS
import com.example.dpulayerlab.render.RendererSafetyState
import com.example.dpulayerlab.render.MAX_BOUND_COMPRESSED_SAMPLE_BYTES
import com.example.dpulayerlab.render.MEDIA_KEY_CODECS_STRING_COMPAT
import com.example.dpulayerlab.render.MEDIA_KEY_CROP_BOTTOM_COMPAT
import com.example.dpulayerlab.render.MEDIA_KEY_CROP_LEFT_COMPAT
import com.example.dpulayerlab.render.MEDIA_KEY_CROP_RIGHT_COMPAT
import com.example.dpulayerlab.render.MEDIA_KEY_CROP_TOP_COMPAT
import com.example.dpulayerlab.render.PinnedMediaSource
import com.example.dpulayerlab.render.PinnedMediaCleanupState
import com.example.dpulayerlab.render.closePinnedMediaDescriptor
import com.example.dpulayerlab.render.constructWithOwnedCloseOnFailure
import com.example.dpulayerlab.render.VideoDecoderSelection
import com.example.dpulayerlab.render.boundedCodecConfigFingerprint
import com.example.dpulayerlab.render.exactMediaIntegerOrNull
import com.example.dpulayerlab.render.fixedVideoMaximumDimensionsMatch
import com.example.dpulayerlab.render.videoDimensionCeiling
import com.example.dpulayerlab.render.visibleVideoDimensions
import com.example.dpulayerlab.util.currentDisplayCompat
import com.example.dpulayerlab.vendor.VendorBridge
import com.example.dpulayerlab.vendor.VendorPerformanceSessionState
import com.example.dpulayerlab.vendor.VendorPerformanceSessionTicket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class LabControllerFrontendInputs(
    val appContext: android.content.Context,
    val mainHandler: Handler,
    val keepScreenOnInitially: Boolean,
    val powerManager: PowerManager,
    val initialSafetyLimits: RenderSafetyLimits,
)

internal data class LabControllerBackendResources(
    val loadManager: LoadManager,
    val frameTracker: FrameTracker,
    val systemMonitor: SystemMonitor,
    val vendorBridge: VendorBridge,
)

enum class HwcCapacityCalibrationStatus {
    PENDING,
    OBSERVED_AT_CANDIDATE,
    UNAVAILABLE,
}

internal const val HWC_CAPACITY_CALIBRATION_REQUESTED_LAYERS = 20
internal const val HWC_CAPACITY_CALIBRATION_TOTAL_TIMEOUT_MS = 6_000L
internal const val HWC_CAPACITY_CALIBRATION_PRODUCER_FPS = 30f
internal const val HWC_CAPACITY_CALIBRATION_DISPLAY_HZ = 60f

/**
 * Fixed, display-only topology used for the process-session one-shot observation.
 *
 * Keep this construction pure so the exact 20-layer contract cannot silently inherit workloads,
 * alpha, GL, decoder, or transition state from the first queued scenario.
 */
internal fun processSessionHwcCapacityCalibrationPhase(): PhaseSpec =
    PhaseSpec(
        id = "session-hwc-capacity-calibration",
        label = "App session HWC capacity one-shot",
        durationMs = HWC_CAPACITY_CALIBRATION_TOTAL_TIMEOUT_MS,
        activeLayers = HWC_CAPACITY_CALIBRATION_REQUESTED_LAYERS,
        producerFps = HWC_CAPACITY_CALIBRATION_PRODUCER_FPS,
        requestedDisplayHz = HWC_CAPACITY_CALIBRATION_DISPLAY_HZ,
        backend = LayerBackend.INDEPENDENT_SURFACES,
        pixelRoute = PixelRoute.RGB_8888,
        bufferSize = BufferSize.DISPLAY,
        motion = MotionProfile.CAPACITY_TILES,
        layerSizeProfile = LayerSizeProfile.FULL_SCREEN,
        workloads = LoadSetpoints(),
        alphaOverlap = false,
        includeGlLayer = false,
        transition = TransitionSpec(),
        hwcCompositionExpectation = HwcCompositionExpectation.NONE,
    )

data class HwcCapacityCalibrationResult(
    val status: HwcCapacityCalibrationStatus,
    val candidateLayers: Int? = null,
    val observedDeviceLayers: Int? = null,
    val observedClientLayers: Int? = null,
    val source: String = "",
    val quality: MetricQuality = MetricQuality.UNAVAILABLE,
    val evidenceMonotonicMs: Long? = null,
    val calibrationDisplayId: Int? = null,
    val calibrationDisplayShortEdgePx: Int? = null,
    val calibrationDisplayLongEdgePx: Int? = null,
    val detail: String = "",
) {
    fun eventDetail(): String =
        "status=${status.name}; requested=$HWC_CAPACITY_CALIBRATION_REQUESTED_LAYERS; " +
            "candidate=${candidateLayers ?: "N/A"}; " +
            "device=${observedDeviceLayers ?: "N/A"}; " +
            "client=${observedClientLayers ?: "N/A"}; " +
            "quality=${quality.name}; source=${source.ifBlank { "N/A" }}; " +
            "evidenceMs=${evidenceMonotonicMs ?: "N/A"}; " +
            "calibrationDisplay=${calibrationDisplayId ?: "unknown"}:" +
            "${calibrationDisplayShortEdgePx ?: "N/A"}x" +
            "${calibrationDisplayLongEdgePx ?: "N/A"}; " +
            "lifetime=process-session; " +
            "scope=observed-at-candidate-not-universal-max; detail=${detail.ifBlank { "N/A" }}"

    fun uiSummary(): String =
        when (status) {
            HwcCapacityCalibrationStatus.PENDING ->
                "HWC capacity · 앱 session 최초 1회 · 요청 20L · 측정 대기"
            HwcCapacityCalibrationStatus.OBSERVED_AT_CANDIDATE ->
                "HWC capacity · session 1회 · 요청 20L · " +
                    "실제 후보 ${candidateLayers ?: "N/A"}L에서 " +
                    "D${observedDeviceLayers ?: "N/A"}/C${observedClientLayers ?: "N/A"} · " +
                    "${quality.name}@${source.ifBlank { "N/A" }} · " +
                    capacityReuseGuidance(this).uiSummary()
            HwcCapacityCalibrationStatus.UNAVAILABLE ->
                "HWC capacity · session 1회 N/A · 요청 20L · " +
                    "실제 후보 ${candidateLayers ?: "N/A"}L · " +
                    "${quality.name}@${source.ifBlank { "N/A" }} · " +
                    detail.ifBlank { "fresh pair unavailable" }
        }
}

data class HwcCapacityReuseGuidance(
    val deviceCandidateCeiling: Int? = null,
    val clientPressureCandidate: Int? = null,
    val detail: String,
) {
    fun uiSummary(): String =
        "ref DEVICE≤${deviceCandidateCeiling ?: "N/A"}L / " +
            "CLIENT≥${clientPressureCandidate ?: "N/A"}L · topology별 재검증"
}

/**
 * Builds fallible Activity state first, then transfers a fully-owned backend bundle into the
 * controller. Any failure after VendorBridge creation performs bounded reverse cleanup while all
 * references are still available; MainActivity will keep the process owner failed if that cleanup
 * cannot be proved.
 */
internal fun createLabController(
    activity: Activity,
    requestDisplayMode: (Float) -> Boolean,
    testWindowIsolation: TestWindowIsolationPort,
    backendOwnerToken: ControllerBackendOwnerToken,
): LabController {
    val appContext = activity.applicationContext
    val frontendInputs = LabControllerFrontendInputs(
        appContext = appContext,
        mainHandler = Handler(Looper.getMainLooper()),
        keepScreenOnInitially =
            activity.window.attributes.flags and
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0,
        powerManager = checkNotNull(activity.getSystemService(PowerManager::class.java)) {
            "PowerManager unavailable"
        },
        initialSafetyLimits = DeviceRenderSafety.detect(activity),
    )

    // Acquire the singleton before constructing LoadManager, which also uses it internally. If
    // LoadManager construction fails after creating its reflection adapter, this outer owner can
    // still close the Binder lanes instead of losing the only bridge reference.
    val vendorBridge = VendorBridge.get(appContext)
    var loadManager: LoadManager? = null
    var systemMonitor: SystemMonitor? = null
    try {
        val ownedLoadManager = LoadManager(appContext)
        loadManager = ownedLoadManager
        val frameTracker = FrameTracker()
        val ownedSystemMonitor = SystemMonitor.create(
            context = appContext,
            frameTracker = frameTracker,
            loadManager = ownedLoadManager,
        )
        systemMonitor = ownedSystemMonitor
        return LabController(
            activity = activity,
            requestDisplayMode = requestDisplayMode,
            testWindowIsolation = testWindowIsolation,
            backendOwnerToken = backendOwnerToken,
            frontendInputs = frontendInputs,
            backendResources = LabControllerBackendResources(
                loadManager = ownedLoadManager,
                frameTracker = frameTracker,
                systemMonitor = ownedSystemMonitor,
                vendorBridge = vendorBridge,
            ),
        )
    } catch (error: Throwable) {
        val monitor = systemMonitor
        val sampleLaneStopped = monitor?.let { ownedMonitor ->
            runCatching {
                ownedMonitor.stopLocalSamplingForShutdown()
            }.getOrElse { cleanupError ->
                error.addSuppressed(cleanupError)
                false
            }
        } ?: true
        if (sampleLaneStopped) {
            loadManager?.let { ownedLoadManager ->
                runCatching {
                    val shutdown = ownedLoadManager.closeWithResult()
                    check(
                        shutdown.workersStopped &&
                            shutdown.npu.backendCloseConfirmed,
                    ) {
                        "LoadManager construction rollback did not stop every worker/backend"
                    }
                }.exceptionOrNull()?.let(error::addSuppressed)
            }
            if (monitor != null) {
                runCatching {
                    val shutdown = monitor.close(resetCompression = false)
                    check(
                        shutdown.localSampleLaneStopped &&
                            shutdown.surfaceFlingerStopped,
                    ) {
                        "SystemMonitor construction rollback was not confirmed"
                    }
                }.exceptionOrNull()?.let(error::addSuppressed)
            }
            // SystemMonitor normally owns this close. Repeat it defensively because an exception
            // in an earlier monitor rollback step must not skip Binder-lane shutdown.
            runCatching {
                vendorBridge.closeWithResult(resetCompression = false)
            }.exceptionOrNull()?.let(error::addSuppressed)
        } else {
            error.addSuppressed(
                IllegalStateException(
                    "SystemMonitor sample worker survived construction rollback; " +
                        "dependent backends were quarantined",
                ),
            )
        }
        throw error
    }
}

/**
 * Uses physical mode dimensions when available and normalizes axis order. This is evaluated again
 * at each START so a fold/external-display transition cannot reuse an observation from another
 * display envelope.
 */
internal fun currentHwcCapacityCalibrationScope(
    activity: Activity,
): HwcCapacityCalibrationScope {
    val display = activity.currentDisplayCompat()
    val mode = runCatching { display?.mode }.getOrNull()
    val widthPx = mode?.physicalWidth
        ?.takeIf { it > 0 }
        ?: activity.resources.displayMetrics.widthPixels.coerceAtLeast(1)
    val heightPx = mode?.physicalHeight
        ?.takeIf { it > 0 }
        ?: activity.resources.displayMetrics.heightPixels.coerceAtLeast(1)
    return HwcCapacityCalibrationScope.normalized(
        displayId = display?.displayId,
        widthPx = widthPx,
        heightPx = heightPx,
    )
}

class LabController internal constructor(
    private val activity: Activity,
    private val requestDisplayMode: (Float) -> Boolean,
    private val testWindowIsolation: TestWindowIsolationPort,
    private val backendOwnerToken: ControllerBackendOwnerToken,
    frontendInputs: LabControllerFrontendInputs,
    backendResources: LabControllerBackendResources,
) : AutoCloseable {
    private val appContext = frontendInputs.appContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** Survives [scope] cancellation long enough to acknowledge SystemUI restoration. */
    private val isolationCleanupScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = frontendInputs.mainHandler
    /** Preserve a host Activity/embedding environment that already owned the screen-on flag. */
    private val keepScreenOnInitially = frontendInputs.keepScreenOnInitially
    private val rendererLifecycleStageOwner =
        RendererSafetyState.createLifecycleStageOwner()
    private val powerManager = frontendInputs.powerManager
    private val loadManager = backendResources.loadManager
    val frameTracker = backendResources.frameTracker
    private val systemMonitor = backendResources.systemMonitor
    private val vendorBridge = backendResources.vendorBridge
    @Volatile
    private var monitorJob: Job? = null
    @Volatile
    private var watchdogJob: Job? = null
    @Volatile
    private var expectedMonitorStop: Job? = null
    @Volatile
    private var expectedWatchdogStop: Job? = null
    private val telemetryMonitoringRequested = AtomicBoolean(false)
    private val telemetryLifecycleIntegrityConfirmed = AtomicBoolean(true)
    private val telemetryRestartPosted = AtomicBoolean(false)
    private val telemetryRestartRunnable = Runnable {
        telemetryRestartPosted.set(false)
        if (
            telemetryMonitoringRequested.get() &&
            telemetryLifecycleIntegrityConfirmed.get() &&
            !closed &&
            monitorJob == null &&
            watchdogJob == null
        ) {
            start()
        }
    }
    @Volatile
    private var performanceRenewalJob: Job? = null
    @Volatile
    private var performanceSessionTicket: VendorPerformanceSessionTicket? = null
    @Volatile
    private var performanceIsolationOwned = false
    @Volatile
    private var performanceIsolationLifecycle = PerformanceIsolationLifecycle.IDLE
    private var performanceBaselinePowerSaveMode: Boolean? = null
    private val performancePolicyRestoreConfirmed = AtomicBoolean(true)
    /**
     * Exact END restoration cannot rehabilitate a stale/late command, failed renewal/health
     * acknowledgment, or changed vendor service session. Keep that evidence across plan resets and
     * hand it to the Activity-free process cleanup gate on close.
     */
    private val performanceSessionIntegrityConfirmed = AtomicBoolean(true)
    private var powerStateReceiverRegistered = false
    private val powerStateReceiverCleanupConfirmed = AtomicBoolean(true)
    private val powerStateCallbackHolder = PowerStateCallbackHolder()
    private val powerStateReceiver = PowerStateBroadcastReceiver(powerStateCallbackHolder)
    @Volatile
    private var runJob: Job? = null
    private val runSamples = mutableListOf<TelemetrySnapshot>()
    private val runEvents = mutableListOf<RunEvent>()
    private var runStartMonotonicMs = 0L
    private var runStartEpochMs = 0L
    private var baselineExactUnderruns: Long? = null
    private var baselineExactSource: String? = null
    private var baselineExactQuality = MetricQuality.UNAVAILABLE
    private var highestExactUnderruns: Long? = null
    private var exactCounterContinuous = false
    private var exactCounterContinuityEventRecorded = false
    private var exactCounterSamplesAfterBaseline = 0
    private var baselineSuspected = 0L
    private var producerRateShortfallReason: String? = null
    private var hwcCompositionCoverageFailureReason: String? = null
    private var hwcCompositionChainAnchor: HwcCompositionChainAnchor? = null
    private val activeHwcCompositionCoverage =
        AtomicReference<HwcCompositionCoverageTracker?>()
    /**
     * While a typed target needs forced evidence, periodic telemetry uses latest-wins tryLock/drop
     * semantics and cannot enqueue ahead of the next forced sample.
     */
    private val hwcCompositionProbePriorityGate =
        HwcCompositionProbePriorityGate()
    private val hwcCapacityCalibrationProbeOwner = Any()
    private var thermalReduced = false
    private var severeThermalPolicyObservationRecorded = false
    private var activeRuntimeProtectionPolicy = RuntimeProtectionPolicy()
    /** True after an accepted SBWC route until a linear/default reset is acknowledged. */
    private var compressionControlActive = false
    /** Binder registration that acknowledged the currently active non-linear route. */
    private var compressionControlSession: Long? = null
    private var cancellationReason: String? = null
    private var runFinalized = false
    private var lastPerformanceRestoreReportPersisted = false
    private var pendingRendererGeneration: Long? = null
    private val telemetrySampleMutex = Mutex()
    /** Serializes phase setpoints with ordered-zero/thermal control transactions. */
    private val loadControlMutex = Mutex()
    private val telemetrySampleGate = TelemetrySampleGenerationGate()
    private val backendUseCompletionGroup =
        ActivityFreeCompletionGroup(maxPendingRegistrations = 16)
    private val frontendCleanupConfirmed = AtomicBoolean(true)
    private val controllerCloseCleanupConfirmed =
        AtomicReference<Boolean?>(null)
    private var closeRendererCompletion: ActivityFreeCompletionRegistration? = null
    private var closeRendererTimeout: Runnable? = null
    private val rendererContainerLifecycle = RendererContainerLifecycleTracker()
    @Volatile
    private var activeProducerFrameBudget: AppliedProducerFrameBudget? = null
    @Volatile
    private var activePhaseClock: ActivePhaseClock? = null
    @Volatile
    private var runtimeControlPaused = false
    @Volatile
    private var producerRecoveryPaused = false
    private val topologyPendingBoundary =
        AtomicReference<ProducerTopologyPendingBoundary?>()
    private val producerTopologyStateLock = Any()
    @Volatile
    private var closed = false
    private var lastSuccessfulSampleMs = SystemClock.elapsedRealtime()
    @Volatile
    private var telemetrySampleInFlightDeadlineMs: Long? = null
    @Volatile
    private var telemetryWatchdogCalibrationPaused = false
    @Volatile
    private var telemetryWatchdogResumeGraceDeadlineMs: Long? = null
    @Volatile
    private var activeTestWindowIsolationToken: Long? = null
    private var isolationReleaseToken: Long? = null
    private var isolationReleaseDeferred: Deferred<Boolean>? = null
    private var isolationBarrierReleaseToken: Long? = null
    private var isolationBarrierReleaseDeferred: Deferred<Boolean>? = null

    var telemetry by mutableStateOf(TelemetrySnapshot())
        private set
    var progress by mutableStateOf(RunProgress())
        private set
    var selectedMediaUri by mutableStateOf<Uri?>(null)
        private set
    var selectedMediaWidthPx by mutableStateOf<Int?>(null)
        private set
    var selectedMediaHeightPx by mutableStateOf<Int?>(null)
        private set
    var selectedMediaLinearReference by mutableStateOf<DecoderLinearReference?>(null)
        private set
    var selectedVideoDecoder by mutableStateOf<VideoDecoderSelection?>(null)
        private set
    var lastSummary by mutableStateOf<RunSummary?>(null)
        private set
    var lastReportFile by mutableStateOf<File?>(null)
        private set
    var planProgress by mutableStateOf(PlanProgress())
        private set
    var screenAwake by mutableStateOf(false)
        private set
    var performanceIsolationStatus by mutableStateOf("대기")
        private set
    var hwcCapacityCalibration by mutableStateOf(
        ProcessHwcCapacityCalibrationSession.snapshot(
            currentHwcCapacityCalibrationScope(activity),
        ) ?: HwcCapacityCalibrationResult(HwcCapacityCalibrationStatus.PENDING),
    )
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var safetyLimits by mutableStateOf(frontendInputs.initialSafetyLimits)
        private set
    var lastSafetyAdjustments by mutableStateOf<List<String>>(emptyList())
        private set
    var severeThermalDeratingEnabled by mutableStateOf(false)
        private set
    val canConfigureRuntimeProtection: Boolean
        get() = !planStartBlocked(runJobPresent = runJob != null, isRunning = isRunning)
    val activeSevereThermalDeratingEnabled: Boolean
        get() = activeRuntimeProtectionPolicy.severeThermalDeratingEnabled
    val directSensors = mutableStateListOf<SensorReading>()
    val telemetryHistory = mutableStateListOf<TelemetrySnapshot>()
    private val mutablePlanResultHistory = mutableStateListOf<PlanRunResult>()
    val planResultHistory: List<PlanRunResult>
        get() = mutablePlanResultHistory

    val isRunning: Boolean
        get() = planProgress.active || progress.stage in ACTIVE_STAGES

    val hasDumpPermission: Boolean get() = systemMonitor.hasDumpPermission()
    val hasNpuAdapter: Boolean get() = systemMonitor.hasNpuAdapter()
    val hasSbwcAdapter: Boolean get() = systemMonitor.hasSbwcAdapter()

    fun refreshHwcCapacityCalibrationDisplayProjection() {
        if (closed) return
        hwcCapacityCalibration =
            ProcessHwcCapacityCalibrationSession.snapshot(
                currentHwcCapacityCalibrationScope(activity),
            ) ?: HwcCapacityCalibrationResult(HwcCapacityCalibrationStatus.PENDING)
    }

    fun start() {
        if (closed) return
        telemetryMonitoringRequested.set(true)
        registerPowerStateReceiver()
        val existingMonitor = monitorJob
        val existingWatchdog = watchdogJob
        when (
            telemetryPairStartDecision(
                monitorPresent = existingMonitor != null,
                monitorActive = existingMonitor?.isActive == true,
                watchdogPresent = existingWatchdog != null,
                watchdogActive = existingWatchdog?.isActive == true,
                lifecycleIntegrityConfirmed = telemetryLifecycleIntegrityConfirmed.get(),
            )
        ) {
            TelemetryPairStartDecision.REUSE_ACTIVE_PAIR -> {
                // FrameTracker.stop() is called from pause(); reset its baseline on every resume.
                frameTracker.start()
                return
            }
            TelemetryPairStartDecision.WAIT_FOR_TERMINATION -> {
                cancelTelemetryPairExpected(existingMonitor, existingWatchdog)
                frameTracker.stop()
                return
            }
            TelemetryPairStartDecision.REJECT_UNTRUSTED_LIFECYCLE -> {
                cancelTelemetryPairExpected(existingMonitor, existingWatchdog)
                frameTracker.stop()
                errorMessage =
                    "Telemetry worker lifecycle 실패가 확인되어 process 재시작 전 계측을 " +
                        "재사용할 수 없습니다."
                return
            }
            TelemetryPairStartDecision.START_NEW_PAIR -> Unit
        }
        if (!loadManager.start()) {
            errorMessage =
                "Local CPU/memory worker를 안전하게 준비할 수 없습니다. " +
                    "이전 worker 종료 또는 현재 worker 상태를 확인하세요."
        }
        frameTracker.start()
        lastSuccessfulSampleMs = SystemClock.elapsedRealtime()
        telemetryWatchdogCalibrationPaused = false
        telemetryWatchdogResumeGraceDeadlineMs = null
        val monitorCompletion = backendUseCompletionGroup.registerStart()
        val watchdogCompletion = backendUseCompletionGroup.registerStart()
        if (monitorCompletion == null || watchdogCompletion == null) {
            telemetryLifecycleIntegrityConfirmed.set(false)
            monitorCompletion?.fail("monitor/watchdog registration was incomplete")
            watchdogCompletion?.fail("monitor/watchdog registration was incomplete")
            errorMessage =
                "Backend lifecycle tracker를 등록할 수 없어 계측을 시작하지 않았습니다."
            frameTracker.stop()
            return
        }
        val monitorLifecycle = TransactionalCompletionRegistration(monitorCompletion)
        val watchdogLifecycle = TransactionalCompletionRegistration(watchdogCompletion)
        var newMonitorJob: Job? = null
        var newWatchdogJob: Job? = null
        var monitorCompletionAttached = false
        var watchdogCompletionAttached = false
        try {
            val createdMonitorJob = scope.launch(start = CoroutineStart.LAZY) {
                while (isActive) {
                    try {
                        collectPeriodicTelemetrySample()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        errorMessage = "상태 계측 실패: ${error.javaClass.simpleName}"
                        if (isRunning) {
                            invalidateExactCounter(
                                "periodic telemetry sample failed: ${error.javaClass.simpleName}",
                            )
                        }
                    }
                    delay(MONITOR_INTERVAL_MS)
                }
            }
            newMonitorJob = createdMonitorJob
            createdMonitorJob.invokeOnCompletion { cause ->
                onTelemetryWorkerCompletion(
                    operation = "monitor",
                    completedJob = createdMonitorJob,
                    monitorWorker = true,
                    cause = cause,
                    lifecycle = monitorLifecycle,
                )
            }
            monitorCompletionAttached = true

            val createdWatchdogJob = scope.launch(start = CoroutineStart.LAZY) {
                while (isActive) {
                    delay(WATCHDOG_INTERVAL_MS)
                    val watchdogNowMs = SystemClock.elapsedRealtime()
                    if (
                        isRunning &&
                        shouldAbortTelemetryWatchdog(
                            nowMs = watchdogNowMs,
                            lastSuccessfulSampleMs = lastSuccessfulSampleMs,
                            staleTimeoutMs = MONITOR_STALE_TIMEOUT_MS,
                            inFlightDeadlineMs = telemetrySampleInFlightDeadlineMs,
                            intentionallyPaused = telemetryWatchdogCalibrationPaused,
                            resumeGraceDeadlineMs =
                                telemetryWatchdogResumeGraceDeadlineMs,
                        ) &&
                        cancellationReason == null
                    ) {
                        abortForSafety(
                            reason =
                                "telemetry가 ${MONITOR_STALE_TIMEOUT_MS / 1_000}초 이상 응답하지 않음",
                            eventType = "MONITOR_WATCHDOG",
                        )
                    }
                }
            }
            newWatchdogJob = createdWatchdogJob
            createdWatchdogJob.invokeOnCompletion { cause ->
                onTelemetryWorkerCompletion(
                    operation = "watchdog",
                    completedJob = createdWatchdogJob,
                    monitorWorker = false,
                    cause = cause,
                    lifecycle = watchdogLifecycle,
                )
            }
            watchdogCompletionAttached = true

            monitorJob = createdMonitorJob
            watchdogJob = createdWatchdogJob
            check(createdMonitorJob.start()) { "monitor Job did not enter LAZY start" }
            check(createdWatchdogJob.start()) { "watchdog Job did not enter LAZY start" }
            check(monitorLifecycle.commit()) { "monitor lifecycle setup was already resolved" }
            check(watchdogLifecycle.commit()) { "watchdog lifecycle setup was already resolved" }
        } catch (error: Throwable) {
            telemetryLifecycleIntegrityConfirmed.set(false)
            val failureReason = if (isFatalTelemetryStartupFailure(error)) {
                "fatal monitor/watchdog startup failure"
            } else {
                "monitor/watchdog startup failed"
            }
            bestEffortCleanup(error) { monitorLifecycle.fail(failureReason) }
            bestEffortCleanup(error) { watchdogLifecycle.fail(failureReason) }
            newMonitorJob?.let { failedMonitor ->
                if (monitorCompletionAttached) expectedMonitorStop = failedMonitor
                bestEffortCleanup(error) { failedMonitor.cancel() }
            }
            newWatchdogJob?.let { failedWatchdog ->
                if (watchdogCompletionAttached) expectedWatchdogStop = failedWatchdog
                bestEffortCleanup(error) { failedWatchdog.cancel() }
            }
            if (!monitorCompletionAttached) {
                completeUnattachedStartupOperation(
                    job = newMonitorJob,
                    completion = monitorLifecycle,
                    primaryFailure = error,
                )
            }
            if (!watchdogCompletionAttached) {
                completeUnattachedStartupOperation(
                    job = newWatchdogJob,
                    completion = watchdogLifecycle,
                    primaryFailure = error,
                )
            }
            if (newMonitorJob?.isCompleted == true && monitorJob === newMonitorJob) {
                monitorJob = null
            }
            if (newWatchdogJob?.isCompleted == true && watchdogJob === newWatchdogJob) {
                watchdogJob = null
            }
            bestEffortCleanup(error) { frameTracker.stop() }
            if (isFatalTelemetryStartupFailure(error)) throw error
            errorMessage = "상태 계측 lifecycle 시작 실패: ${error.javaClass.simpleName}"
        }
    }

    fun pause() {
        telemetryMonitoringRequested.set(false)
        mainHandler.removeCallbacks(telemetryRestartRunnable)
        telemetryRestartPosted.set(false)
        val activeRun = shouldStopActivePlan(
            jobPresent = runJob?.isActive == true,
            isRunning = isRunning,
        )
        if (activeRun) {
            // Publish phase=null before cancelling even when onStop/automation already recorded a
            // reason. Otherwise a fast onStart can reattach the stale AndroidView producer set.
            progress = progressForControllerPause(progress, activeRun)
            stopScenario("Controller가 pause되어 안전 중단")
        }
        cancelTelemetryPairExpected(monitorJob, watchdogJob)
        frameTracker.stop()
        unregisterPowerStateReceiver()
        releaseGeneratedLoads(dropMemoryBuffers = true)
        resetDisplayModeSafely()
        setWakeStateSafely(false)
    }

    private fun cancelTelemetryPairExpected(
        monitor: Job?,
        watchdog: Job?,
    ) {
        monitor?.let { owner ->
            expectedMonitorStop = owner
            owner.cancel()
        }
        watchdog?.let { owner ->
            expectedWatchdogStop = owner
            owner.cancel()
        }
    }

    private fun onTelemetryWorkerCompletion(
        operation: String,
        completedJob: Job,
        monitorWorker: Boolean,
        cause: Throwable?,
        lifecycle: TransactionalCompletionRegistration,
    ) {
        val expectedStop = if (monitorWorker) {
            val expected = expectedMonitorStop === completedJob
            if (expected) expectedMonitorStop = null
            expected
        } else {
            val expected = expectedWatchdogStop === completedJob
            if (expected) expectedWatchdogStop = null
            expected
        }
        val operationFailure = unexpectedLongLivedWorkerCompletionReason(
            operation = operation,
            cause = cause,
            expectedStop = expectedStop,
        )
        if (operationFailure != null) {
            telemetryLifecycleIntegrityConfirmed.set(false)
        }
        val ownedCompletion = if (monitorWorker) {
            if (monitorJob === completedJob) {
                monitorJob = null
                true
            } else {
                false
            }
        } else {
            if (watchdogJob === completedJob) {
                watchdogJob = null
                true
            } else {
                false
            }
        }
        try {
            if (operationFailure != null && ownedCompletion) {
                // Keep the pair indivisible. A surviving watchdog without samples (or a sampler
                // without its watchdog) must never be reused or overwritten by a new generation.
                if (monitorWorker) {
                    watchdogJob?.let { sibling ->
                        expectedWatchdogStop = sibling
                        sibling.cancel()
                    }
                } else {
                    monitorJob?.let { sibling ->
                        expectedMonitorStop = sibling
                        sibling.cancel()
                    }
                }
                publishTelemetryWorkerFailure(operationFailure)
            }
        } finally {
            // Integrity and identity must be visible before resolving the Activity-free ticket:
            // this may wake process cleanup immediately when it was the final backend user.
            try {
                lifecycle.completeOperation(operationFailure)
            } finally {
                if (ownedCompletion) requestTelemetryRestartAfterTermination()
            }
        }
    }

    private fun publishTelemetryWorkerFailure(reason: String) {
        val bounded =
            "Telemetry worker 비정상 종료: $reason".take(MAX_EVENT_MESSAGE_CHARS)
        val abort = Runnable {
            errorMessage = bounded
            if (runJob?.isActive == true && isRunning) {
                abortForSafety(
                    reason = bounded,
                    eventType = "TELEMETRY_WORKER_FAILURE",
                )
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            abort.run()
        } else if (!mainHandler.post(abort)) {
            // The UI lane is unavailable, but cancellation itself is thread-safe and ensures the
            // NonCancellable run finalizer starts draining every producer/load owner.
            runJob?.cancel(CancellationException(bounded))
        }
    }

    private fun requestTelemetryRestartAfterTermination() {
        if (
            !telemetryMonitoringRequested.get() ||
            !telemetryLifecycleIntegrityConfirmed.get() ||
            closed ||
            monitorJob != null ||
            watchdogJob != null ||
            !telemetryRestartPosted.compareAndSet(false, true)
        ) {
            return
        }
        if (!mainHandler.post(telemetryRestartRunnable)) {
            telemetryRestartPosted.set(false)
            telemetryLifecycleIntegrityConfirmed.set(false)
        }
    }

    fun setMediaUri(uri: Uri?) {
        if (planStartBlocked(runJobPresent = runJob != null, isRunning = isRunning)) {
            errorMessage = "테스트 중에는 미디어 소스를 변경할 수 없습니다."
            return
        }
        closeSelectedVideoDecoder()
        selectedMediaUri = uri
        selectedMediaWidthPx = null
        selectedMediaHeightPx = null
        selectedMediaLinearReference = null
    }

    fun clearError() {
        errorMessage = null
    }

    /**
     * Configures the next plan only. An active/finalizing plan keeps its immutable start snapshot,
     * including when it was launched through the protected automation alias.
     */
    fun setSevereThermalDeratingEnabled(enabled: Boolean): Boolean {
        if (!canConfigureRuntimeProtection) {
            showError("실행 중인 plan의 thermal 보호 설정은 변경할 수 없습니다.")
            return false
        }
        severeThermalDeratingEnabled = enabled
        return true
    }

    fun showError(message: String) {
        errorMessage = message.take(MAX_EVENT_MESSAGE_CHARS)
    }

    fun startScenario(requestedScenario: ScenarioSpec) {
        startPlan(
            ScenarioRunPlan(
                scenarios = listOf(requestedScenario),
                source = PlanSource.SINGLE_SCENARIO,
            ),
        )
    }

    /**
     * Starts an immutable snapshot of [requestedPlan].
     *
     * Empty queues and excessive repeat/expanded-run counts are rejected. Duplicate scenarios
     * are deliberately preserved because queue order itself is part of the experiment.
     */
    fun startPlan(requestedPlan: ScenarioRunPlan): Boolean {
        if (closed) {
            showError("종료된 controller에서는 테스트를 시작할 수 없습니다.")
            return false
        }
        registerPowerStateReceiver()
        if (!powerStateReceiverRegistered) {
            val reason =
                "Battery Saver/device-idle 감시를 등록할 수 없어 테스트를 시작하지 않았습니다."
            planProgress = PlanProgress(
                state = PlanState.REJECTED,
                source = requestedPlan.source,
                repeatCount = requestedPlan.repeatCount.coerceAtLeast(0),
                queueSize = requestedPlan.scenarios.size,
                totalRuns = requestedPlan.totalRuns,
                statusText = reason,
                terminalReason = reason,
            )
            showError(reason)
            return false
        }
        // A cancelled Job remains the owner until its NonCancellable renderer/load/report
        // finalizer clears runJob. Starting in that gap would let the old finalizer reset the new
        // run's loads, display mode, and wake state.
        if (planStartBlocked(runJobPresent = runJob != null, isRunning = isRunning)) {
            showError("이미 실행 중인 테스트 plan이 있습니다.")
            return false
        }
        if (
            telemetryPairStartDecision(
                monitorPresent = monitorJob != null,
                monitorActive = monitorJob?.isActive == true,
                watchdogPresent = watchdogJob != null,
                watchdogActive = watchdogJob?.isActive == true,
                lifecycleIntegrityConfirmed = telemetryLifecycleIntegrityConfirmed.get(),
            ) != TelemetryPairStartDecision.REUSE_ACTIVE_PAIR
        ) {
            val reason = if (!telemetryLifecycleIntegrityConfirmed.get()) {
                "Telemetry worker lifecycle 실패가 있어 process 재시작 전 test plan을 " +
                    "시작할 수 없습니다."
            } else {
                "Telemetry monitor/watchdog pair가 모두 active 상태가 아니어서 test plan을 " +
                    "시작하지 않았습니다."
            }
            planProgress = PlanProgress(
                state = PlanState.REJECTED,
                source = requestedPlan.source,
                repeatCount = requestedPlan.repeatCount.coerceAtLeast(0),
                queueSize = requestedPlan.scenarios.size,
                totalRuns = requestedPlan.totalRuns,
                statusText = reason,
                terminalReason = reason.take(MAX_TERMINAL_REASON_CHARS),
            )
            showError(reason)
            return false
        }
        activeTestWindowIsolationToken?.let { staleToken ->
            if (!clearReleasedTestWindowIsolationToken(staleToken)) {
                // A retained token may mean the prior producer teardown timed out. Never issue
                // show() from a new START until that barrier is re-acknowledged.
                beginTestWindowIsolationReleaseAfterRendererTeardown(staleToken)
                showError(
                    "이전 SystemUI 복원이 확인되지 않아 새 test plan을 시작할 수 없습니다. " +
                        "복원 완료 후 다시 시도하고, 계속 실패하면 앱 process를 다시 시작하세요.",
                )
                return false
            }
        }
        if (hasUnconfirmedRendererCleanup()) {
            val reason =
                "이전 codec/GL/Canvas producer 종료가 아직 확인되지 않아 새 plan을 시작할 수 없습니다."
            planProgress = PlanProgress(
                state = PlanState.REJECTED,
                source = requestedPlan.source,
                repeatCount = requestedPlan.repeatCount.coerceAtLeast(0),
                queueSize = requestedPlan.scenarios.size,
                totalRuns = requestedPlan.totalRuns,
                statusText = reason,
                terminalReason = reason,
            )
            showError(reason)
            return false
        }
        if (LoadSafetyState.hasUnconfirmedNpuCleanup()) {
            val reason =
                "이전 NPU 제어/adapter cleanup이 미확인 상태여서 process 재시작 전 새 plan을 " +
                    "시작할 수 없습니다."
            planProgress = PlanProgress(
                state = PlanState.REJECTED,
                source = requestedPlan.source,
                repeatCount = requestedPlan.repeatCount.coerceAtLeast(0),
                queueSize = requestedPlan.scenarios.size,
                totalRuns = requestedPlan.totalRuns,
                statusText = reason,
                terminalReason = reason,
            )
            showError(reason)
            return false
        }
        if (PinnedMediaCleanupState.hasUnconfirmedCleanup()) {
            val reason =
                "이전 selected-media descriptor 종료가 확인되지 않아 새 plan을 시작할 수 " +
                    "없습니다. 앱 process를 완전히 종료하세요."
            planProgress = PlanProgress(
                state = PlanState.REJECTED,
                source = requestedPlan.source,
                repeatCount = requestedPlan.repeatCount.coerceAtLeast(0),
                queueSize = requestedPlan.scenarios.size,
                totalRuns = requestedPlan.totalRuns,
                statusText = reason,
                terminalReason = reason.take(MAX_TERMINAL_REASON_CHARS),
            )
            showError(reason)
            return false
        }
        reconcilePerformanceIsolationOwnership()
        if (
            performanceIsolationStartBlocked(
                isolationOwned = performanceIsolationOwned,
                ticketPresent = performanceSessionTicket != null,
                renewalPresent = performanceRenewalJob != null,
                restoreConfirmed = performancePolicyRestoreConfirmed.get(),
                sessionIntegrityConfirmed = performanceSessionIntegrityConfirmed.get(),
            )
        ) {
            val reason =
                "이전 성능 격리 session의 종료/원상복구가 확인되지 않아 새 plan을 " +
                    "시작할 수 없습니다. 앱 process를 다시 시작하세요."
            planProgress = PlanProgress(
                state = PlanState.REJECTED,
                source = requestedPlan.source,
                repeatCount = requestedPlan.repeatCount.coerceAtLeast(0),
                queueSize = requestedPlan.scenarios.size,
                totalRuns = requestedPlan.totalRuns,
                statusText = reason,
                terminalReason = reason.take(MAX_TERMINAL_REASON_CHARS),
            )
            showError(reason)
            return false
        }
        ScenarioPlanPolicy.validate(requestedPlan)?.let { reason ->
            planProgress = PlanProgress(
                state = PlanState.REJECTED,
                source = requestedPlan.source,
                repeatCount = requestedPlan.repeatCount.coerceAtLeast(0),
                queueSize = requestedPlan.scenarios.size,
                totalRuns = requestedPlan.totalRuns,
                statusText = reason,
                terminalReason = reason.take(MAX_TERMINAL_REASON_CHARS),
            )
            showError("테스트 plan 거부: $reason")
            return false
        }
        if (!loadManager.start()) {
            val reason =
                "Local CPU/memory worker 상태가 안전하지 않아 새 plan을 시작할 수 없습니다. " +
                    "이전 Activity worker가 남았거나 현재 worker가 조기 종료됐을 수 있습니다."
            planProgress = PlanProgress(
                state = PlanState.REJECTED,
                source = requestedPlan.source,
                repeatCount = requestedPlan.repeatCount.coerceAtLeast(0),
                queueSize = requestedPlan.scenarios.size,
                totalRuns = requestedPlan.totalRuns,
                statusText = reason,
                terminalReason = reason.take(MAX_TERMINAL_REASON_CHARS),
            )
            showError(reason)
            return false
        }
        val runtimeProtectionPolicySnapshot = RuntimeProtectionPolicy(
            severeThermalDeratingEnabled = severeThermalDeratingEnabled,
        )
        val plan = requestedPlan.copy(
            scenarios = requestedPlan.scenarios.map { scenario ->
                scenario.copy(
                    tags = scenario.tags.toSet(),
                    requirements = scenario.requirements.toSet(),
                    phases = scenario.phases.map { it.copy() },
                )
            },
        )

        resetPlanState()
        activeRuntimeProtectionPolicy = runtimeProtectionPolicySnapshot
        val firstScenario = plan.scenarios.first()
        if (!setWakeStateSafely(true)) {
            val reason = "화면 wake 상태를 설정할 수 없어 테스트를 시작하지 않았습니다."
            planProgress = PlanProgress(
                state = PlanState.REJECTED,
                source = plan.source,
                repeatCount = plan.repeatCount,
                queueSize = plan.scenarios.size,
                totalRuns = plan.totalRuns,
                statusText = reason,
                terminalReason = reason.take(MAX_TERMINAL_REASON_CHARS),
            )
            progress = RunProgress(statusText = reason)
            showError(reason)
            return false
        }
        val isolationRequest = runCatching {
            testWindowIsolation.request()
        }.getOrDefault(RequestResult.Rejected)
        if (isolationRequest == RequestResult.Rejected) {
            val reason =
                "status/navigation bar를 격리할 수 없습니다. 전체 화면 단일-window 모드에서 " +
                    "다시 실행하세요."
            setWakeStateSafely(false)
            planProgress = PlanProgress(
                state = PlanState.REJECTED,
                source = plan.source,
                repeatCount = plan.repeatCount,
                queueSize = plan.scenarios.size,
                totalRuns = plan.totalRuns,
                statusText = reason,
                terminalReason = reason.take(MAX_TERMINAL_REASON_CHARS),
            )
            progress = RunProgress(statusText = reason)
            showError(reason)
            return false
        }
        val isolationToken = when (isolationRequest) {
            is RequestResult.Acquired -> isolationRequest.token
            is RequestResult.CleanupRequired -> isolationRequest.token
            RequestResult.Rejected -> error("handled above")
        }
        activeTestWindowIsolationToken = isolationToken
        if (isolationRequest is RequestResult.CleanupRequired) {
            ensureTestWindowIsolationReleased(isolationToken)
            val reason =
                "SystemUI hide 요청이 실패해 test plan을 시작하지 않았습니다. " +
                    "원래 system bar 상태 복원을 확인한 뒤 다시 시도하세요."
            setWakeStateSafely(false)
            planProgress = PlanProgress(
                state = PlanState.REJECTED,
                source = plan.source,
                repeatCount = plan.repeatCount,
                queueSize = plan.scenarios.size,
                totalRuns = plan.totalRuns,
                statusText = reason,
                terminalReason = reason.take(MAX_TERMINAL_REASON_CHARS),
            )
            progress = RunProgress(statusText = reason)
            showError(reason)
            return false
        }
        planProgress = PlanProgress(
            state = PlanState.RUNNING,
            source = plan.source,
            repeatIndex = 0,
            repeatCount = plan.repeatCount,
            queueIndex = 0,
            queueSize = plan.scenarios.size,
            completedRuns = 0,
            totalRuns = plan.totalRuns,
            currentScenario = firstScenario,
            nextScenario = scenarioAtExpandedIndex(plan, 1),
            statusText = "Plan 시작 준비",
        )
        progress = RunProgress(
            stage = RunnerStage.PRECHECK,
            scenario = firstScenario,
            statusText = "안전 예산과 capability 확인 중",
            thermalDerated = thermalReduced,
        )

        val runRegistration = backendUseCompletionGroup.registerStart()
        if (runRegistration == null) {
            val reason =
                "Backend lifecycle tracker가 닫혀 test plan을 시작할 수 없습니다."
            ensureTestWindowIsolationReleased(isolationToken)
            setWakeStateSafely(false)
            planProgress = planProgress.copy(
                state = PlanState.REJECTED,
                currentScenario = null,
                nextScenario = null,
                statusText = reason,
                terminalReason = reason,
            )
            progress = RunProgress(statusText = reason)
            showError(reason)
            return false
        }
        val runLifecycle = TransactionalCompletionRegistration(runRegistration)
        lateinit var launchedJob: Job
        var constructedJob: Job? = null
        var runCompletionAttached = false
        try {
            launchedJob = scope.launch(start = CoroutineStart.LAZY) {
            var activeRunIndex = -1
            var activeRepeatIndex = -1
            var activeQueueIndex = -1
            try {
                if (!awaitTestWindowIsolation(isolationToken)) {
                    val reason =
                        "status/navigation bar 숨김 확인이 ${SYSTEM_UI_ISOLATION_TIMEOUT_MS}ms " +
                            "안에 완료되지 않아 test plan을 시작하지 않았습니다."
                    cancellationReason = reason
                    throw PlanAbortException(reason)
                }
                acquirePerformanceIsolationForPlan()
                startPerformanceSessionRenewal(launchedJob)
                ensureProcessSessionHwcCapacityCalibration(firstScenario)
                repeat(plan.repeatCount) { repeatIndex ->
                    plan.scenarios.forEachIndexed { queueIndex, scenario ->
                        currentCoroutineContext().ensureActive()
                        activeRunIndex = repeatIndex * plan.scenarios.size + queueIndex
                        activeRepeatIndex = repeatIndex
                        activeQueueIndex = queueIndex
                        planProgress = planProgress.copy(
                            state = PlanState.RUNNING,
                            repeatIndex = repeatIndex,
                            queueIndex = queueIndex,
                            currentScenario = scenario,
                            nextScenario = scenarioAtExpandedIndex(plan, activeRunIndex + 1),
                            currentRunFraction = 0f,
                            statusText = "반복 ${repeatIndex + 1}/${plan.repeatCount} · " +
                                "항목 ${queueIndex + 1}/${plan.scenarios.size}",
                        )
                        progress = RunProgress(
                            stage = RunnerStage.PRECHECK,
                            scenario = scenario,
                            statusText = "안전 예산과 capability 확인 중",
                            thermalDerated = thermalReduced,
                        )

                        val artifact = executeScenario(scenario)
                        mutablePlanResultHistory += artifact.toPlanResult(
                            runIndex = activeRunIndex,
                            repeatIndex = repeatIndex,
                            queueIndex = queueIndex,
                        )
                        // finalizeRun intentionally persists the report in NonCancellable. Honor
                        // a STOP that arrived during that barrier before starting another item or
                        // marking the last item as a completed plan.
                        currentCoroutineContext().ensureActive()
                        val progressDecision = planArtifactProgressDecision(
                            completedRuns = planProgress.completedRuns,
                            verdict = artifact.summary.verdict,
                        )
                        if (!progressDecision.shouldContinue) {
                            val reason = cancellationReason
                                ?: artifact.summary.terminalReason(MAX_TERMINAL_REASON_CHARS)
                                ?: artifact.summary.verdict.label
                            cancellationReason = reason
                            throw PlanAbortException(reason)
                        }
                        val completed = progressDecision.completedRuns
                        planProgress = planProgress.copy(
                            completedRuns = completed,
                            currentScenario = null,
                            nextScenario = scenarioAtExpandedIndex(plan, completed),
                            currentRunFraction = 0f,
                            statusText = "$completed/${plan.totalRuns} 항목 완료",
                        )
                        activeRunIndex = -1
                        activeRepeatIndex = -1
                        activeQueueIndex = -1
                    }
                }

                currentCoroutineContext().ensureActive()
                val performanceRestored =
                    releasePerformanceIsolationForPlan("plan completed")
                val restoreOutcomePersisted =
                    persistPerformanceRestoreOutcome(
                        restored = performanceRestored,
                        releaseReason = "plan completed",
                    )
                if (!performanceRestored) {
                    val reason =
                        "Battery Saver 원상복구 확인에 실패해 plan을 완료로 판정하지 않습니다."
                    cancellationReason = reason
                    throw PlanAbortException(reason)
                }
                if (!restoreOutcomePersisted) {
                    val reason =
                        "Battery Saver 복원 결과를 보고서에 안전하게 기록하지 못해 plan을 " +
                            "완료로 판정하지 않습니다."
                    cancellationReason = reason
                    throw PlanAbortException(reason)
                }
                val lastResult = mutablePlanResultHistory.last()
                planProgress = planProgress.copy(
                    state = PlanState.COMPLETE,
                    repeatIndex = plan.repeatCount - 1,
                    queueIndex = plan.scenarios.lastIndex,
                    completedRuns = plan.totalRuns,
                    currentScenario = null,
                    nextScenario = null,
                    currentRunFraction = 0f,
                    statusText = "Plan ${plan.totalRuns}개 항목 완료",
                    terminalReason = null,
                )
                progress = progress.copy(
                    stage = if (
                        plan.totalRuns == 1 &&
                        lastResult.verdict == RunVerdict.UNSUPPORTED
                    ) {
                        RunnerStage.UNSUPPORTED
                    } else {
                        RunnerStage.COMPLETE
                    },
                    scenario = lastResult.scenario,
                    targetPhase = null,
                    transitionFraction = 1f,
                    statusText = if (plan.totalRuns == 1) {
                        lastResult.verdict.label
                    } else {
                        "PLAN COMPLETE · ${plan.totalRuns}/${plan.totalRuns}"
                    },
                )
            } catch (cancelled: CancellationException) {
                if (
                    shouldAppendAbortedPlanResult(
                        activeRunIndex = activeRunIndex,
                        alreadyRecorded = mutablePlanResultHistory.any {
                            it.runIndex == activeRunIndex
                        },
                        verdict = lastSummary?.verdict,
                    )
                ) {
                    val summary = checkNotNull(lastSummary)
                    mutablePlanResultHistory += ScenarioRunArtifact(
                        summary = summary,
                        reportFile = lastReportFile,
                    ).toPlanResult(
                        runIndex = activeRunIndex,
                        repeatIndex = activeRepeatIndex,
                        queueIndex = activeQueueIndex,
                    )
                }
                val reason = cancellationReason
                    ?: cancelled.message
                    ?: "사용자가 중단함"
                planProgress = planProgress.copy(
                    state = PlanState.ABORTED,
                    currentScenario = null,
                    nextScenario = null,
                    currentRunFraction = 0f,
                    statusText = reason,
                    terminalReason = reason.take(MAX_TERMINAL_REASON_CHARS),
                )
                progress = progress.copy(
                    stage = RunnerStage.ABORTED,
                    targetPhase = null,
                    statusText = reason,
                )
            } catch (error: Exception) {
                val reason = "Plan runner 오류: ${error.message ?: error.javaClass.simpleName}"
                    .take(MAX_EVENT_MESSAGE_CHARS)
                cancellationReason = reason
                errorMessage = reason
                planProgress = planProgress.copy(
                    state = PlanState.ABORTED,
                    currentScenario = null,
                    nextScenario = null,
                    currentRunFraction = 0f,
                    statusText = reason,
                    terminalReason = reason.take(MAX_TERMINAL_REASON_CHARS),
                )
                progress = progress.copy(
                    stage = RunnerStage.ABORTED,
                    targetPhase = null,
                    statusText = reason,
                )
            } finally {
                try {
                    withContext(NonCancellable) {
                        var performanceRestored = false
                        try {
                            releaseActiveLoadsForRun()
                        } finally {
                            try {
                                resetDisplayModeSafely()
                            } finally {
                                try {
                                    performanceRestored =
                                        releasePerformanceIsolationForPlan("plan finalizer")
                                } finally {
                                    try {
                                        persistPerformanceRestoreOutcome(
                                            restored = performanceRestored,
                                            releaseReason = "plan finalizer",
                                        )
                                    } finally {
                                        try {
                                            setWakeStateSafely(false)
                                        } finally {
                                            releaseTestWindowIsolationAfterRendererTeardown(
                                                isolationToken,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    // Never leave startPlan permanently locked out if a vendor/window cleanup
                    // implementation itself is faulty. An older finalizer must never clear a
                    // newer published owner.
                    if (publishedJobOwnerMatches(runJob, launchedJob)) {
                        runJob = null
                    }
                }
            }
            }
            constructedJob = launchedJob
            // Dispatchers.Main.immediate may run a normal launch to completion before the
            // assignment below publishes its Job. Publish a lazy owner and its real completion
            // callback before permitting the body to execute.
            runJob = launchedJob
            launchedJob.invokeOnCompletion { cause ->
                try {
                    runLifecycle.completeOperation(
                        failureReason = unexpectedJobCompletionReason(
                            operation = "plan runner",
                            cause = cause,
                        ),
                    )
                } finally {
                    val clearOwner = Runnable {
                        if (publishedJobOwnerMatches(runJob, launchedJob)) {
                            runJob = null
                        }
                    }
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        clearOwner.run()
                    } else if (!mainHandler.post(clearOwner)) {
                        // The controller is already losing its main lane. The volatile owner may
                        // be cleared here only after actual Job completion; backend cleanup still
                        // uses the Activity-free completion ticket above as its authority.
                        if (publishedJobOwnerMatches(runJob, launchedJob)) {
                            runJob = null
                        }
                    }
                }
            }
            runCompletionAttached = true
            check(launchedJob.start()) { "plan runner Job did not enter LAZY start" }
            check(runLifecycle.commit()) { "plan runner lifecycle setup was already resolved" }
        } catch (error: Throwable) {
            val failureReason = if (isFatalControllerStartupFailure(error)) {
                "fatal plan runner startup failure"
            } else {
                "plan runner startup failed"
            }
            bestEffortCleanup(error) { runLifecycle.fail(failureReason) }
            bestEffortCleanup(error) { constructedJob?.cancel() }
            val failedJob = constructedJob
            if (!runCompletionAttached) {
                completeUnattachedStartupOperation(
                    job = failedJob,
                    completion = runLifecycle,
                    primaryFailure = error,
                    onTerminal = if (failedJob == null) {
                        null
                    } else {
                        {
                            if (publishedJobOwnerMatches(runJob, failedJob)) {
                                runJob = null
                            }
                        }
                    },
                )
            }
            if (
                failedJob != null &&
                failedJob.isCompleted &&
                publishedJobOwnerMatches(runJob, failedJob)
            ) {
                runJob = null
            }
            // No measured phase can be committed before this setup transaction completes. The
            // token and wake flag can therefore be rolled back immediately; both operations are
            // idempotent if a just-started Job also reaches its NonCancellable finalizer.
            bestEffortCleanup(error) { ensureTestWindowIsolationReleased(isolationToken) }
            bestEffortCleanup(error) { setWakeStateSafely(false) }
            if (isFatalControllerStartupFailure(error)) throw error
            val reason =
                "Plan runner lifecycle 시작 실패: ${error.javaClass.simpleName}"
                    .take(MAX_EVENT_MESSAGE_CHARS)
            cancellationReason = reason
            planProgress = planProgress.copy(
                state = PlanState.REJECTED,
                currentScenario = null,
                nextScenario = null,
                currentRunFraction = 0f,
                statusText = reason,
                terminalReason = reason.take(MAX_TERMINAL_REASON_CHARS),
            )
            progress = RunProgress(statusText = reason)
            showError(reason)
            return false
        }
        return true
    }

    fun stopScenario() {
        stopScenario("사용자가 중단함")
    }

    fun stopScenario(reason: String) {
        val job = runJob ?: return
        // COMPLETE/ABORTED can be published before the NonCancellable backend cleanup releases
        // the Job owner. A late STOP in that ownership-only window must not rewrite the terminal
        // UI/report or cancel the cleanup that is already driving every backend to idle.
        if (!shouldStopActivePlan(jobPresent = true, isRunning = isRunning)) return
        cancellationReason = preserveFirstCancellationReason(
            current = cancellationReason,
            requested = reason,
            fallback = "사용자가 중단함",
            maxChars = MAX_EVENT_MESSAGE_CHARS,
        )
        val terminalReason = cancellationReason.orEmpty()
        // Drop the AndroidView stage and controllable setpoints at the request boundary. Confirmed
        // NPU/SBWC cleanup remains in the existing NonCancellable finalizer.
        progress = progressForImmediateStop(progress, terminalReason)
        releaseGeneratedLoads(dropMemoryBuffers = true)
        requestDisplayModeSafely(60f, "stop display reduction")
        // cancel() is also valid for a lazy NEW job, so a STOP/close at the publication boundary
        // cannot leave a dormant owner that later starts.
        job.cancel()
    }

    fun invalidateSafetyEnvelope(reason: String) {
        if (!isRunning) return
        val boundedReason = reason
            .trim()
            .ifEmpty { "실행 중 device safety envelope가 변경됨" }
            .take(MAX_EVENT_MESSAGE_CHARS)
        abortForSafety(
            reason = boundedReason,
            eventType = "SAFETY_ENVELOPE_CHANGED",
        )
    }

    fun onTestWindowIsolationLost(token: Long, reason: String, eventType: String) {
        if (
            activeTestWindowIsolationToken != token ||
            runJob == null ||
            !isRunning
        ) {
            return
        }
        val boundedReason = reason
            .trim()
            .ifEmpty { "측정 중 SystemUI 격리가 해제됨" }
            .take(MAX_EVENT_MESSAGE_CHARS)
        val boundedEventType = eventType
            .takeIf { it == "SYSTEM_UI_REVEALED" || it == "WINDOW_FOCUS_LOST" }
            ?: "SYSTEM_UI_REVEALED"
        abortForSafety(
            reason = boundedReason,
            eventType = boundedEventType,
        )
    }

    fun onProducerTopologyPending(generation: Long) {
        val boundary = synchronized(producerTopologyStateLock) {
            if (!frameTracker.markProducerTopologyPending(generation)) return
            if (progress.producerGeneration != generation) return
            val boundaryMs = SystemClock.elapsedRealtime()
            val committedBoundary = checkNotNull(
                topologyPendingBoundary.updateAndGet { current ->
                    if (
                        current != null &&
                        current.generation == generation &&
                        current.monotonicMs <= boundaryMs
                    ) {
                        current
                    } else {
                        ProducerTopologyPendingBoundary(generation, boundaryMs)
                    }
                },
            )
            activeHwcCompositionCoverage.get()?.recordProbeFailure(
                "target producer topology entered recovery during HWC observation",
            )
            activePhaseClock?.pause(
                atMonotonicMs = committedBoundary.monotonicMs,
                owner = PhasePauseOwner.PRODUCER_RECOVERY,
            )
            committedBoundary
        }
        val frameBudget = activeProducerFrameBudget ?: return
        if (producerRecoveryPaused) return
        frameBudget.pauseAtPhysicalBoundary(
            atMonotonicMs = boundary.monotonicMs,
            totalFrames = frameTracker.totalPhysicalProducedFrames(),
            owner = PhasePauseOwner.PRODUCER_RECOVERY,
        )
        // Do not leave CPU/memory/NPU work active until the next 100 ms controller poll. The
        // callback is delivered on the View/main boundary, so zeroing here is both serialized and
        // the earliest truthful recovery point. The normal recovery loop confirms/resumes later.
        producerRecoveryPaused = true
        progress.phase?.let { activePhase ->
            progress = progress.copy(
                phase = rendererPreparationPhase(activePhase),
                statusText = "Physical producer 복구 대기 · 전체 부하 일시 해제",
            )
        }
        val loadsReleased = releaseGeneratedLoads()
        val displayReduced = requestDisplayModeSafely(
            min(60f, progress.phase?.requestedDisplayHz ?: 60f),
            "producer topology pending",
        )
        if (producerRecoverySafePointFailed(loadsReleased, displayReduced)) {
            abortForSafety(
                reason = "Producer recovery safe setpoint 적용을 확인할 수 없어 중단합니다.",
                eventType = "PRODUCER_RECOVERY_SAFEPOINT_FAILED",
            )
        }
    }

    fun onProducerRuntimeFailure(generation: Long, reason: String) {
        val boundedReason = reason
            .trim()
            .ifEmpty { "unknown producer failure" }
            .take(MAX_PRODUCER_RUNTIME_FAILURE_CHARS)
        if (!frameTracker.markProducerRuntimeFailure(generation, boundedReason)) return
        if (
            closed ||
            progress.producerGeneration != generation ||
            !isRunning
        ) {
            return
        }
        abortForSafety(
            reason = "Physical producer 실행 실패: $boundedReason",
            eventType = "PRODUCER_RUNTIME_FAILURE",
        )
    }

    @Suppress("UNUSED_PARAMETER")
    fun onRendererStageRemoved(generation: Long, stopped: Boolean) {
        // `stopped=false` means only that the producer missed the short UI hand-off deadline.
        // releaseRenderChild() has already registered every live thread in RendererSafetyState;
        // awaitRendererTeardownBarrier() polls that lease and marks failure only at the continuous
        // recovery deadline. Treating this callback as terminal would collapse 16 ms and 5 s.
        frameTracker.markProducerTeardownComplete(generation)
        // Lifecycle stop can race a just-published generation before AndroidView receives its
        // update. A detached single stage is proof that neither generation remains attached.
        val publishedGeneration = progress.producerGeneration
        if (publishedGeneration > 0L && publishedGeneration != generation) {
            frameTracker.markProducerTeardownComplete(publishedGeneration)
        }
        RendererSafetyState.markLifecycleStageRemoved(rendererLifecycleStageOwner)
    }

    /**
     * Compose container disposal is also the proof that an AndroidView which never reached its
     * factory cannot appear later. Native producer threads, if any, remain guarded by their
     * independent process-wide leases and teardown callbacks.
     */
    fun onRendererContainerAttached(): Long =
        rendererContainerLifecycle.attach()

    fun onRendererContainerDisposed(token: Long) {
        if (!rendererContainerLifecycle.dispose(token)) return
        RendererSafetyState.markLifecycleStageRemoved(rendererLifecycleStageOwner)
        closeRendererTimeout?.let(mainHandler::removeCallbacks)
        closeRendererTimeout = null
        closeRendererCompletion?.complete()
        closeRendererCompletion = null
    }

    fun dismissResult() {
        if (!isRunning) {
            progress = RunProgress()
            planProgress = PlanProgress()
        }
    }

    fun shareLastReport() {
        shareReport(lastReportFile?.absolutePath)
    }

    fun shareReport(reportPath: String?) {
        val file = resolveManagedReportFile(
            reportsDirectory = File(activity.filesDir, "reports"),
            reportPath = reportPath,
        )
        if (
            file == null ||
            !isPublishedReportForSharing(
                reportFile = file,
                lastReportFile = lastReportFile,
                planResultPaths = mutablePlanResultHistory.asSequence().map(PlanRunResult::reportPath),
            )
        ) {
            errorMessage = "공유할 수 있는 관리 대상 보고서 파일이 없습니다."
            return
        }
        runCatching {
            val uri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.files",
                file,
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(intent, "테스트 보고서 공유"))
        }.onFailure {
            errorMessage = "보고서 공유 실패: ${it.javaClass.simpleName}"
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
        val activeRunOwner = runJob
        val hasAttachedRendererContainer =
            rendererContainerLifecycle.hasAttachedContainers()
        if (hasAttachedRendererContainer) {
            // Compose disposal is asynchronous. This process-wide token prevents a newly created
            // Activity/controller from starting RGB producers before this stage acknowledges
            // removal; live worker threads remain protected by their separate leases.
            RendererSafetyState.markLifecycleStageRemovalPending(
                rendererLifecycleStageOwner,
            )
        }
        progress = progress.copy(
            phase = null,
            targetPhase = null,
            statusText = "Controller 종료 및 부하 해제",
        )
        cancellationReason = preserveFirstCancellationReason(
            current = cancellationReason,
            requested = "Controller closed",
            fallback = "Controller closed",
            maxChars = MAX_EVENT_MESSAGE_CHARS,
        )
        runJob?.cancel()
        pause()
        // An active run owns its pinned descriptor until the NonCancellable renderer finalizer
        // has stopped the codec. Closing it here would race MediaCodec on Activity destruction.
        if (activeRunOwner == null) closeSelectedVideoDecoder()

        // Activity destruction cannot prove that Compose/AndroidView/Surface teardown completed
        // synchronously. Never change the allocation route from this non-suspending lifecycle
        // path; the run finalizer remains live because backend shutdown waits for its completion.
        if (
            compressionControlActive ||
            CompressionSafetyState.hasUnconfirmedCompressionCleanup()
        ) {
            CompressionSafetyState.markNonLinearRouteMayBeActive()
        }

        val frontendCompletion = ActivityFreeCompletionGroup(
            maxPendingRegistrations = 2,
        )
        if (hasAttachedRendererContainer) {
            val rendererCompletion = frontendCompletion.registerStart()
            if (rendererCompletion == null) {
                frontendCleanupConfirmed.set(false)
            } else {
                closeRendererCompletion = rendererCompletion
                val timeout = Runnable {
                    if (
                        rendererCompletion.fail(
                            "renderer container disposal was not observed before timeout",
                        )
                    ) {
                        frontendCleanupConfirmed.set(false)
                    }
                    if (closeRendererCompletion === rendererCompletion) {
                        closeRendererCompletion = null
                    }
                    closeRendererTimeout = null
                }
                closeRendererTimeout = timeout
                if (!mainHandler.postDelayed(timeout, RENDERER_CONTAINER_DISPOSE_TIMEOUT_MS)) {
                    timeout.run()
                }
            }
        }

        val isolationToken = activeTestWindowIsolationToken
        if (isolationToken == null) {
            isolationCleanupScope.cancel()
        } else {
            val isolationCompletion = frontendCompletion.registerStart()
            if (isolationCompletion == null) {
                frontendCleanupConfirmed.set(false)
            } else {
                completeCloseIsolationAfterRun(
                    activeRunOwner = activeRunOwner,
                    registration = isolationCompletion,
                )
            }
        }

        val frontendBarrier = frontendCompletion.seal()
        val backendUsersBarrier = backendUseCompletionGroup.seal()
        val cleanupStart = ProcessControllerBackendCleanupCoordinator.beginCleanup(
            ownerToken = backendOwnerToken,
            runCompletion = frontendBarrier,
            monitorCompletion = backendUsersBarrier,
            operation = ControllerBackendCleanup(
                loadManager = loadManager,
                systemMonitor = systemMonitor,
                vendorBridge = vendorBridge,
                frontendCleanupConfirmed = frontendCleanupConfirmed,
                powerStateReceiverCleanupConfirmed = powerStateReceiverCleanupConfirmed,
                telemetryLifecycleIntegrityConfirmed =
                    telemetryLifecycleIntegrityConfirmed,
                performancePolicyRestoreConfirmed = performancePolicyRestoreConfirmed,
                performanceSessionIntegrityConfirmed =
                    performanceSessionIntegrityConfirmed,
                publishedResult = controllerCloseCleanupConfirmed,
            ),
        )
        if (cleanupStart != ControllerBackendCleanupStart.STARTED) {
            frontendCleanupConfirmed.set(false)
            controllerCloseCleanupConfirmed.set(false)
        }
        // Cancelling the Activity-owned scope is non-blocking. Registered completion tickets keep
        // backend cleanup behind the real run/monitor finalizers even though their fields are
        // cleared by pause().
        scope.cancel()
        } catch (error: Throwable) {
            // close() is the last Activity-owned opportunity to hand every backend to the
            // process coordinator. If any setup step escapes, make the owner sticky-failed before
            // the Activity drops this controller instead of allowing a second backend generation.
            frontendCleanupConfirmed.set(false)
            controllerCloseCleanupConfirmed.set(false)
            ProcessControllerBackendCleanupCoordinator.failOwner(
                backendOwnerToken,
                "controller close transaction failed: ${error.javaClass.simpleName}",
            )
            bestEffortCleanup(error) { scope.cancel() }
            if (isFatalControllerStartupFailure(error)) throw error
            errorMessage =
                "Controller 종료 transaction 실패: ${error.javaClass.simpleName}"
                    .take(MAX_EVENT_MESSAGE_CHARS)
        }
    }

    private fun completeCloseIsolationAfterRun(
        activeRunOwner: Job?,
        registration: ActivityFreeCompletionRegistration,
    ) {
        val beginRelease = Runnable {
            val retainedToken = activeTestWindowIsolationToken
            if (retainedToken == null) {
                registration.complete()
                isolationCleanupScope.cancel()
                return@Runnable
            }
            val release =
                beginTestWindowIsolationReleaseAfterRendererTeardown(retainedToken)
            isolationCleanupScope.launch {
                val restored = try {
                    release.await()
                } catch (_: CancellationException) {
                    false
                } catch (_: Exception) {
                    false
                }
                if (restored) {
                    registration.complete()
                } else {
                    frontendCleanupConfirmed.set(false)
                    registration.fail("SystemUI restoration was not acknowledged")
                }
                isolationCleanupScope.cancel()
            }
        }
        if (activeRunOwner == null) {
            beginRelease.run()
            return
        }
        activeRunOwner.invokeOnCompletion {
            if (!mainHandler.post(beginRelease)) {
                frontendCleanupConfirmed.set(false)
                registration.fail("main thread rejected post-finalizer SystemUI restoration")
                isolationCleanupScope.cancel()
            }
        }
    }

    private fun registerPowerStateReceiver() {
        powerStateCallbackHolder.attach(::handlePowerStateBroadcast)
        if (powerStateReceiverRegistered) {
            powerStateReceiverCleanupConfirmed.set(false)
            return
        }
        val registered = runCatching {
            ContextCompat.registerReceiver(
                appContext,
                powerStateReceiver,
                IntentFilter().apply {
                    addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                    addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            true
        }.getOrElse { error ->
            errorMessage =
                "Power 상태 broadcast 감시 등록 실패: ${error.javaClass.simpleName}"
                    .take(MAX_EVENT_MESSAGE_CHARS)
            false
        }
        powerStateReceiverRegistered = registered
        powerStateReceiverCleanupConfirmed.set(!registered)
        if (!registered) powerStateCallbackHolder.detach()
    }

    private fun unregisterPowerStateReceiver() {
        // Remove the Controller/Activity reference before touching the framework registration.
        // Even a broken unregister implementation can then retain only this Activity-free holder.
        powerStateCallbackHolder.detach()
        if (!powerStateReceiverRegistered) {
            powerStateReceiverCleanupConfirmed.set(true)
            return
        }
        var lastError: Throwable? = null
        var unregistered = false
        repeat(POWER_RECEIVER_UNREGISTER_ATTEMPTS) {
            if (unregistered) return@repeat
            unregistered = runCatching {
                appContext.unregisterReceiver(powerStateReceiver)
                true
            }.recoverCatching { error ->
                // A framework-side unregister which already won the race is equivalent to the
                // receiver no longer retaining this holder.
                if (error is IllegalArgumentException) true else throw error
            }.getOrElse { error ->
                lastError = error
                false
            }
        }
        if (unregistered) {
            powerStateReceiverRegistered = false
            powerStateReceiverCleanupConfirmed.set(true)
        } else {
            powerStateReceiverCleanupConfirmed.set(false)
            recordCleanupFailure(
                "power-state receiver unregister",
                lastError ?: IllegalStateException("unregister was not acknowledged"),
            )
        }
    }

    private fun handlePowerStateBroadcast() {
        if (
            !isRunning ||
            !shouldMonitorPerformanceIsolation(performanceIsolationLifecycle)
        ) {
            return
        }
        val state = runCatching {
            powerManager.isPowerSaveMode to powerManager.isDeviceIdleMode
        }.getOrElse { error ->
            abortForSafety(
                reason =
                    "실행 중 power 상태를 읽을 수 없음: " +
                        error.javaClass.simpleName,
                eventType = "PERFORMANCE_ENVIRONMENT_CHANGED",
            )
            return
        }
        when {
            state.first ->
                abortForSafety(
                    reason = "실행 중 Battery Saver가 활성화되어 성능 격리를 잃었습니다.",
                    eventType = "SAFETY_ENVELOPE_CHANGED",
                )
            state.second ->
                abortForSafety(
                    reason = "실행 중 device-idle 상태가 활성화됨",
                    eventType = "PERFORMANCE_ENVIRONMENT_CHANGED",
                )
        }
    }

    /**
     * Requests the only policy mutation allowed by the portable client: temporary Battery Saver
     * suppression through the API-v3 product broker. If no broker is present, a run is allowed
     * only when Battery Saver is already off; broadcasts and telemetry then enforce that state.
     */
    private suspend fun acquirePerformanceIsolationForPlan() {
        performanceIsolationLifecycle = PerformanceIsolationLifecycle.ACQUIRING
        performanceIsolationStatus = "설정 중 · Battery Saver"
        planProgress = planProgress.copy(statusText = "성능 저하 정책 격리 확인 중")
        progress = progress.copy(statusText = "Battery Saver 격리 확인 중")
        performanceBaselinePowerSaveMode = try {
            powerManager.isPowerSaveMode
        } catch (error: Throwable) {
            if (isFatalControllerStartupFailure(error)) throw error
            failPerformanceIsolation(
                "Battery Saver 원래 상태 확인 실패: ${error.javaClass.simpleName}",
            )
        }
        val result = try {
            // BEGIN may have mutated a global policy by the time the caller is cancelled. Complete
            // the bounded call and publish any returned ticket before observing cancellation so
            // the NonCancellable plan finalizer always has authoritative cleanup ownership.
            withContext(NonCancellable + Dispatchers.IO) {
                vendorBridge.acquirePerformanceSession()
            }
        } catch (error: Throwable) {
            performanceSessionIntegrityConfirmed.set(false)
            // A fatal failure can arrive after the broker accepted BEGIN but before its normal
            // result reached this coroutine. Publish the process latch into controller ownership
            // before propagating anything so the outer NonCancellable finalizer can still issue
            // the ordered END.
            vendorBridge.pendingPerformanceRestoreTicket()?.let { pendingTicket ->
                performanceSessionTicket = pendingTicket
                performanceIsolationOwned = true
                performancePolicyRestoreConfirmed.set(false)
            }
            if (isFatalControllerStartupFailure(error)) {
                val reason =
                    "성능 격리 broker fatal 실패: ${error.javaClass.simpleName}"
                        .take(MAX_EVENT_MESSAGE_CHARS)
                performanceIsolationLifecycle = PerformanceIsolationLifecycle.FAILED
                performanceIsolationStatus = "실패 · broker fatal"
                cancellationReason = reason
                errorMessage = reason
                throw error
            }
            failPerformanceIsolation(
                "성능 격리 broker 호출 실패: ${error.javaClass.simpleName}",
            )
        }
        if (
            performanceIsolationAcquisitionCompromised(
                brokerState = result.state,
                ticketPresent = result.ticket != null,
            )
        ) {
            performanceSessionIntegrityConfirmed.set(false)
        }
        result.ticket?.let { returnedTicket ->
            performanceSessionTicket = returnedTicket
            performanceIsolationOwned = true
            performancePolicyRestoreConfirmed.set(false)
        }

        val batterySaverActive = try {
            powerManager.isPowerSaveMode
        } catch (error: Throwable) {
            if (isFatalControllerStartupFailure(error)) {
                val reason =
                    "Battery Saver acknowledgment fatal 실패: ${error.javaClass.simpleName}"
                        .take(MAX_EVENT_MESSAGE_CHARS)
                performanceIsolationLifecycle = PerformanceIsolationLifecycle.FAILED
                performanceIsolationStatus = "실패 · Battery Saver 확인 fatal"
                cancellationReason = reason
                errorMessage = reason
                throw error
            }
            failPerformanceIsolation(
                "Battery Saver 상태 확인 실패: ${error.javaClass.simpleName}",
            )
        }
        when (
            performanceIsolationStartMode(
                brokerState = result.state,
                ticketPresent = result.ticket != null,
                originalBatterySaverActive =
                    performanceBaselinePowerSaveMode == true,
                batterySaverActive = batterySaverActive,
            )
        ) {
            PerformanceIsolationStartMode.VENDOR_LEASE -> {
                val ticket = checkNotNull(result.ticket)
                performanceSessionTicket = ticket
                performanceIsolationOwned = true
                performancePolicyRestoreConfirmed.set(false)
                if (!awaitBatterySaverOff()) {
                    performanceSessionIntegrityConfirmed.set(false)
                    releasePerformanceIsolationForPlan(
                        "Battery Saver disable acknowledgment mismatch",
                    )
                    failPerformanceIsolation(
                        "broker 승인 뒤에도 Battery Saver=off를 확인할 수 없습니다.",
                    )
                }
                performanceIsolationLifecycle = PerformanceIsolationLifecycle.ACTIVE
                performanceIsolationStatus =
                    "보호됨 · vendor lease S${ticket.serviceSession}"
            }
            PerformanceIsolationStartMode.APP_ONLY_MONITOR -> {
                performanceSessionTicket = null
                performanceIsolationOwned = true
                // No system policy was mutated. A later external Battery Saver change aborts the
                // run but is not an app-owned restore leak and must be revalidated by the next
                // plan instead of process-sticky cleanup.
                performancePolicyRestoreConfirmed.set(true)
                performanceIsolationLifecycle = PerformanceIsolationLifecycle.ACTIVE
                performanceIsolationStatus =
                    "APP_ONLY · Battery Saver=off 감시"
            }
            PerformanceIsolationStartMode.REJECT -> {
                // Even a no-ticket failure may represent a BEGIN which the bridge restored before
                // returning. Keep a local release owner so the finalizer verifies the direct
                // producer-before-BEGIN Battery Saver state instead of trusting wording alone.
                performanceIsolationOwned = true
                performancePolicyRestoreConfirmed.set(false)
                val policyDetail = when {
                    result.state == VendorPerformanceSessionState.UNAVAILABLE &&
                        batterySaverActive ->
                        "Battery Saver가 켜져 있고 API-v3 broker를 사용할 수 없음"
                    result.ticket != null &&
                        result.state != VendorPerformanceSessionState.ACTIVE ->
                        "remote 정책 변경/복원이 불명확한 ticket을 반환함"
                    else -> result.detail
                }
                failPerformanceIsolation("성능 격리 broker 승인 실패: $policyDetail")
            }
        }
    }

    private fun startPerformanceSessionRenewal(owner: Job) {
        if (performanceSessionTicket == null) return
        check(performanceRenewalJob == null) {
            "A performance renewal owner is already published"
        }
        val renewalRegistration = backendUseCompletionGroup.registerStart()
        if (renewalRegistration == null) {
            performanceSessionIntegrityConfirmed.set(false)
            performanceIsolationLifecycle = PerformanceIsolationLifecycle.FAILED
            performanceIsolationStatus = "실패 · renew lifecycle 등록"
            abortForSafety(
                reason = "성능 격리 renewal lifecycle을 추적할 수 없습니다.",
                eventType = "PERFORMANCE_ISOLATION_LOST",
            )
            return
        }
        val renewalLifecycle = TransactionalCompletionRegistration(renewalRegistration)
        val renewalFailure = AtomicReference<String?>()
        lateinit var renewalOwner: Job
        var constructedRenewal: Job? = null
        var renewalCompletionAttached = false
        try {
            renewalOwner = scope.launch(
                context = Dispatchers.Default,
                start = CoroutineStart.LAZY,
            ) {
                var nextDeadlineMs = saturatingAdd(
                    SystemClock.elapsedRealtime(),
                    VendorBridge.PERFORMANCE_SESSION_RENEW_INTERVAL_MS,
                )
                while (
                    isActive &&
                    runJob === owner &&
                    shouldMonitorPerformanceIsolation(performanceIsolationLifecycle)
                ) {
                    val waitMs =
                        (nextDeadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                    if (waitMs > 0L) delay(waitMs)
                    if (
                        !isActive ||
                        runJob !== owner ||
                        !shouldMonitorPerformanceIsolation(performanceIsolationLifecycle)
                    ) {
                        break
                    }
                    nextDeadlineMs = nextAbsoluteControlDeadlineMs(
                        previousDeadlineMs = nextDeadlineMs,
                        nowMs = SystemClock.elapsedRealtime(),
                        periodMs = VendorBridge.PERFORMANCE_SESSION_RENEW_INTERVAL_MS,
                    )
                    val current = performanceSessionTicket
                    if (current == null) {
                        val reason =
                            "성능 격리 lease ticket이 active renewal 중 사라졌습니다."
                        renewalFailure.compareAndSet(
                            null,
                            "performance renewal lost its active ticket",
                        )
                        postPerformanceIsolationFailure(
                            owner = owner,
                            status = "실패 · renew ticket 없음",
                            reason = reason,
                            eventType = "PERFORMANCE_ISOLATION_LOST",
                        )
                        break
                    }
                    val result = try {
                        withContext(Dispatchers.IO) {
                            vendorBridge.renewPerformanceSession(current)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        renewalFailure.compareAndSet(
                            null,
                            "performance renewal call failed: ${error.javaClass.simpleName}",
                        )
                        postPerformanceIsolationFailure(
                            owner = owner,
                            status = "실패 · renew ${error.javaClass.simpleName}",
                            reason =
                                "성능 격리 lease 갱신 호출 실패: " +
                                    error.javaClass.simpleName,
                            eventType = "PERFORMANCE_ISOLATION_LOST",
                        )
                        break
                    }
                    if (
                        !isActive ||
                        runJob !== owner ||
                        !shouldMonitorPerformanceIsolation(performanceIsolationLifecycle)
                    ) {
                        break
                    }
                    val renewed = result.ticket
                    if (
                        result.state == VendorPerformanceSessionState.ACTIVE &&
                        renewed != null &&
                        samePerformanceSession(current, renewed)
                    ) {
                        performanceSessionTicket = renewed
                        val saverOff = runCatching {
                            !powerManager.isPowerSaveMode
                        }.getOrDefault(false)
                        if (!saverOff) {
                            renewalFailure.compareAndSet(
                                null,
                                "performance renewal observed Battery Saver active",
                            )
                            postPerformanceIsolationFailure(
                                owner = owner,
                                status = "실패 · Battery Saver 재활성",
                                reason = "성능 격리 lease 중 Battery Saver가 활성화됨",
                                eventType = "SAFETY_ENVELOPE_CHANGED",
                            )
                            break
                        } else {
                            mainHandler.post {
                                if (
                                    runJob === owner &&
                                    owner.isActive &&
                                    shouldMonitorPerformanceIsolation(
                                        performanceIsolationLifecycle,
                                    )
                                ) {
                                    performanceIsolationStatus =
                                        "보호됨 · vendor lease S${renewed.serviceSession}"
                                }
                            }
                        }
                    } else {
                        if (
                            renewed != null &&
                            samePerformanceSession(current, renewed) &&
                            shouldMonitorPerformanceIsolation(performanceIsolationLifecycle)
                        ) {
                            // Keep the newest command version so the finalizer can issue an
                            // authoritative higher-version END after a failed renewal.
                            performanceSessionTicket = renewed
                        }
                        renewalFailure.compareAndSet(
                            null,
                            "performance renewal lease was rejected: ${result.state.name}",
                        )
                        postPerformanceIsolationFailure(
                            owner = owner,
                            status = "실패 · lease ${result.state.name}",
                            reason =
                                "성능 격리 lease를 유지할 수 없음: " +
                                    result.detail.take(MAX_EVENT_MESSAGE_CHARS),
                            eventType = "PERFORMANCE_ISOLATION_LOST",
                        )
                        break
                    }
                }
            }
            constructedRenewal = renewalOwner
            performanceRenewalJob = renewalOwner
            renewalOwner.invokeOnCompletion { cause ->
                val operationFailure =
                    renewalFailure.get()
                        ?: unexpectedJobCompletionReason(
                            operation = "performance renewal",
                            cause = cause,
                        )
                if (operationFailure != null) {
                    // Publish sticky evidence before resolving the completion ticket. The process
                    // cleanup coordinator may wake immediately when this is the final backend user.
                    performanceSessionIntegrityConfirmed.set(false)
                }
                try {
                    renewalLifecycle.completeOperation(operationFailure)
                } finally {
                    val ownedCompletion = performanceRenewalJob === renewalOwner
                    if (ownedCompletion) {
                        // This callback is the only authority that clears a published renewal
                        // owner: cancellation request or timeout alone is not terminal evidence.
                        performanceRenewalJob = null
                    }
                    if (
                        ownedCompletion &&
                        shouldFailPerformanceIsolationAfterRenewalCompletion(
                            operationFailure = operationFailure,
                            runOwnerMatches = runJob === owner,
                            runOwnerActive = owner.isActive,
                            isolationMonitoringExpected =
                                shouldMonitorPerformanceIsolation(
                                    performanceIsolationLifecycle,
                                ),
                        )
                    ) {
                        postPerformanceIsolationFailure(
                            owner = owner,
                            status = "실패 · renew worker 종료",
                            reason =
                                "성능 격리 renewal worker 비정상 종료: " +
                                    operationFailure.orEmpty(),
                            eventType = "PERFORMANCE_ISOLATION_LOST",
                        )
                    }
                }
            }
            renewalCompletionAttached = true
            check(renewalOwner.start()) { "performance renewal Job did not enter LAZY start" }
            check(renewalLifecycle.commit()) {
                "performance renewal lifecycle setup was already resolved"
            }
        } catch (error: Throwable) {
            performanceSessionIntegrityConfirmed.set(false)
            val failureReason = if (isFatalControllerStartupFailure(error)) {
                "fatal performance renewal startup failure"
            } else {
                "performance renewal startup failed: ${error.javaClass.simpleName}"
            }
            bestEffortCleanup(error) { renewalLifecycle.fail(failureReason) }
            bestEffortCleanup(error) { constructedRenewal?.cancel() }
            val failedRenewal = constructedRenewal
            if (!renewalCompletionAttached) {
                completeUnattachedStartupOperation(
                    job = failedRenewal,
                    completion = renewalLifecycle,
                    primaryFailure = error,
                    onTerminal = if (failedRenewal == null) {
                        null
                    } else {
                        {
                            if (performanceRenewalJob === failedRenewal) {
                                performanceRenewalJob = null
                            }
                        }
                    },
                )
            }
            if (
                failedRenewal?.isCompleted == true &&
                performanceRenewalJob === failedRenewal
            ) {
                performanceRenewalJob = null
            }
            performanceIsolationLifecycle = PerformanceIsolationLifecycle.FAILED
            performanceIsolationStatus = "실패 · renew Job 생성"
            if (isFatalControllerStartupFailure(error)) throw error
            abortForSafety(
                reason =
                    "성능 격리 renewal worker를 생성할 수 없습니다: " +
                        error.javaClass.simpleName,
                eventType = "PERFORMANCE_ISOLATION_LOST",
            )
            return
        }
    }

    /**
     * Renewal itself never waits for the main thread. A long render/UI stall therefore cannot
     * delay the next 2-second lease command. Failure state is published immediately through the
     * volatile lifecycle, while UI/event mutation is posted to main.
     */
    private fun postPerformanceIsolationFailure(
        owner: Job,
        status: String,
        reason: String,
        eventType: String,
    ) {
        performanceSessionIntegrityConfirmed.set(false)
        if (!shouldMonitorPerformanceIsolation(performanceIsolationLifecycle)) return
        performanceIsolationLifecycle = PerformanceIsolationLifecycle.FAILED
        val delivered = mainHandler.post {
            if (runJob === owner && owner.isActive) {
                performanceIsolationStatus = status.take(MAX_EVENT_MESSAGE_CHARS)
                abortForSafety(
                    reason = reason.take(MAX_EVENT_MESSAGE_CHARS),
                    eventType = eventType,
                )
            }
        }
        if (!delivered) {
            owner.cancel(CancellationException(reason.take(MAX_EVENT_MESSAGE_CHARS)))
        }
    }

    /**
     * Stops renewal before END. END is a higher command version on the same serialized vendor
     * lane, so a timed-out older renewal can never re-enable the temporary policy afterward.
     */
    private suspend fun releasePerformanceIsolationForPlan(reason: String): Boolean {
        if (
            shouldRetryDirectPerformanceRestore(
                isolationOwned = performanceIsolationOwned,
                ticketPresent = performanceSessionTicket != null,
                renewalPresent = performanceRenewalJob != null,
                restoreConfirmed = performancePolicyRestoreConfirmed.get(),
                originalStateKnown = performanceBaselinePowerSaveMode != null,
            )
        ) {
            performanceIsolationLifecycle = PerformanceIsolationLifecycle.RESTORING
            val restored =
                awaitOriginalBatterySaverState(performanceBaselinePowerSaveMode)
            if (restored) {
                performancePolicyRestoreConfirmed.set(true)
                performanceBaselinePowerSaveMode = null
                performanceIsolationLifecycle = PerformanceIsolationLifecycle.IDLE
                performanceIsolationStatus =
                    "복원 확인 · 지연된 Battery Saver 상태 반영"
                return true
            }
            performanceIsolationLifecycle = PerformanceIsolationLifecycle.FAILED
            performanceIsolationStatus = "복원 실패 · Battery Saver 원상태 불일치"
            return false
        }
        if (
            !performanceIsolationOwned &&
            performanceSessionTicket == null &&
            performanceRenewalJob == null &&
            performancePolicyRestoreConfirmed.get()
        ) {
            performanceIsolationLifecycle = PerformanceIsolationLifecycle.IDLE
            performanceBaselinePowerSaveMode = null
            return true
        }
        if (
            !performanceIsolationOwned &&
            performanceSessionTicket == null &&
            performanceRenewalJob == null
        ) {
            performanceIsolationLifecycle = PerformanceIsolationLifecycle.FAILED
            return false
        }
        val originalPowerSaveMode = performanceBaselinePowerSaveMode
        performanceIsolationLifecycle = PerformanceIsolationLifecycle.RESTORING
        val renewal = performanceRenewalJob
        var renewalStopped = true
        if (renewal != null) {
            renewal.cancel()
            renewalStopped = withTimeoutOrNull(PERFORMANCE_RENEWAL_JOIN_TIMEOUT_MS) {
                renewal.join()
                true
            } == true
            if (renewalStopped && performanceRenewalJob === renewal) {
                performanceRenewalJob = null
            }
            if (!renewalStopped) {
                performanceIsolationStatus = "복원 실패 · renew 종료 미확인"
            }
        }

        val ticket = performanceSessionTicket
        if (ticket == null) {
            val restoreWasAlreadyConfirmed =
                performancePolicyRestoreConfirmed.get()
            val directOriginalStateMatched = if (restoreWasAlreadyConfirmed) {
                false
            } else {
                awaitOriginalBatterySaverState(originalPowerSaveMode)
            }
            val originalStateRestored = ticketlessPerformanceRestoreConfirmed(
                restoreAlreadyConfirmed = restoreWasAlreadyConfirmed,
                directOriginalStateMatched = directOriginalStateMatched,
            )
            performanceIsolationOwned = false
            if (originalStateRestored) {
                performancePolicyRestoreConfirmed.set(true)
            }
            performanceIsolationLifecycle = if (renewalStopped && originalStateRestored) {
                PerformanceIsolationLifecycle.IDLE
            } else {
                PerformanceIsolationLifecycle.FAILED
            }
            if (renewalStopped && originalStateRestored) {
                performanceBaselinePowerSaveMode = null
                performanceIsolationStatus = if (restoreWasAlreadyConfirmed) {
                    "해제됨 · APP_ONLY 감시 종료"
                } else {
                    "복원 확인 · Battery Saver 정책"
                }
            } else if (!originalStateRestored) {
                performanceIsolationStatus = "복원 실패 · Battery Saver 원상태 불일치"
                errorMessage =
                    "성능 정책 원상복구 실패($reason): Battery Saver 원래 상태를 확인할 수 없음"
                        .take(MAX_EVENT_MESSAGE_CHARS)
            }
            return renewalStopped && originalStateRestored
        }
        val result = try {
            withContext(NonCancellable + Dispatchers.IO) {
                vendorBridge.endPerformanceSession(ticket)
            }
        } catch (error: Exception) {
            performanceIsolationStatus =
                "복원 실패 · ${error.javaClass.simpleName}"
            performanceIsolationLifecycle = PerformanceIsolationLifecycle.FAILED
            return false
        }
        return if (result.state == VendorPerformanceSessionState.RESTORED) {
            val originalStateRestored =
                awaitOriginalBatterySaverState(originalPowerSaveMode)
            performanceSessionTicket = null
            performanceIsolationOwned = false
            if (originalStateRestored) {
                performancePolicyRestoreConfirmed.set(true)
            }
            performanceIsolationLifecycle = if (renewalStopped && originalStateRestored) {
                PerformanceIsolationLifecycle.IDLE
            } else {
                PerformanceIsolationLifecycle.FAILED
            }
            performanceIsolationStatus = if (renewalStopped && originalStateRestored) {
                performanceBaselinePowerSaveMode = null
                "복원 확인 · Battery Saver 정책"
            } else if (!originalStateRestored) {
                errorMessage =
                    "성능 정책 원상복구 실패($reason): Battery Saver 원래 상태 불일치"
                        .take(MAX_EVENT_MESSAGE_CHARS)
                "복원 실패 · Battery Saver 원상태 불일치"
            } else {
                "복원 실패 · renew 종료 미확인"
            }
            renewalStopped && originalStateRestored
        } else {
            result.ticket?.takeIf { samePerformanceSession(ticket, it) }?.let {
                performanceSessionTicket = it
            }
            performanceIsolationStatus =
                "복원 실패 · ${result.state.name}"
            performanceIsolationLifecycle = PerformanceIsolationLifecycle.FAILED
            errorMessage =
                "성능 정책 원상복구 실패($reason): ${result.detail}"
                    .take(MAX_EVENT_MESSAGE_CHARS)
            false
        }
    }

    private suspend fun awaitOriginalBatterySaverState(expected: Boolean?): Boolean {
        if (expected == null) return false
        return withContext(NonCancellable) {
            val deadline = saturatingAdd(
                SystemClock.elapsedRealtime(),
                PERFORMANCE_POLICY_PROPAGATION_TIMEOUT_MS,
            )
            while (true) {
                val restored = runCatching {
                    powerManager.isPowerSaveMode == expected
                }.getOrDefault(false)
                if (restored) return@withContext true
                if (SystemClock.elapsedRealtime() >= deadline) return@withContext false
                delay(PERFORMANCE_POLICY_POLL_MS)
            }
            @Suppress("UNREACHABLE_CODE")
            false
        }
    }

    private suspend fun awaitBatterySaverOff(): Boolean {
        val deadline = saturatingAdd(
            SystemClock.elapsedRealtime(),
            PERFORMANCE_POLICY_PROPAGATION_TIMEOUT_MS,
        )
        while (currentCoroutineContext().isActive) {
            val saverOff = runCatching {
                !powerManager.isPowerSaveMode
            }.getOrElse {
                return false
            }
            if (saverOff) return true
            if (SystemClock.elapsedRealtime() >= deadline) return false
            delay(PERFORMANCE_POLICY_POLL_MS)
        }
        return false
    }

    private fun failPerformanceIsolation(reason: String): Nothing {
        val bounded = reason.take(MAX_EVENT_MESSAGE_CHARS)
        performanceIsolationLifecycle = PerformanceIsolationLifecycle.FAILED
        performanceIsolationStatus = "실패 · 시작 거부"
        cancellationReason = bounded
        errorMessage = bounded
        throw PlanAbortException(bounded)
    }

    private fun samePerformanceSession(
        first: VendorPerformanceSessionTicket,
        second: VendorPerformanceSessionTicket,
    ): Boolean =
        first.sessionId == second.sessionId &&
            first.serviceSession == second.serviceSession

    /**
     * A late exact END may clear the process latch after the controller timed out. Reconcile only
     * from that authoritative proof and a terminated renewal Job; never clear a live owner merely
     * because a local status string changed.
     */
    private fun reconcilePerformanceIsolationOwnership() {
        val pendingRestore = vendorBridge.pendingPerformanceRestoreTicket()
        if (pendingRestore != null) {
            performanceSessionTicket = pendingRestore
            performanceIsolationOwned = true
            performanceIsolationLifecycle = PerformanceIsolationLifecycle.FAILED
            return
        }
        val renewal = performanceRenewalJob
        val renewalRunning = renewal != null && !renewal.isCompleted
        val restoreAlreadyConfirmed = performancePolicyRestoreConfirmed.get()
        val currentPowerSaveMode = if (restoreAlreadyConfirmed) {
            null
        } else {
            runCatching { powerManager.isPowerSaveMode }.getOrNull()
        }
        if (
            !latePerformanceRestoreConfirmed(
                processRestorePending = false,
                renewalRunning = renewalRunning,
                restoreAlreadyConfirmed = restoreAlreadyConfirmed,
                originalPowerSaveMode = performanceBaselinePowerSaveMode,
                currentPowerSaveMode = currentPowerSaveMode,
            )
        ) {
            return
        }
        performancePolicyRestoreConfirmed.set(true)
        performanceRenewalJob = null
        performanceSessionTicket = null
        performanceIsolationOwned = false
        performanceBaselinePowerSaveMode = null
        performanceIsolationLifecycle = PerformanceIsolationLifecycle.IDLE
        if (performanceIsolationStatus.startsWith("복원 실패")) {
            performanceIsolationStatus = "복원 확인 · 지연된 END acknowledgment"
        }
    }

    private fun resetPlanState() {
        loadManager.clearMemoryAllocationFailure()
        mutablePlanResultHistory.clear()
        hwcCapacityCalibration =
            ProcessHwcCapacityCalibrationSession.snapshot(
                currentHwcCapacityCalibrationScope(activity),
            ) ?: HwcCapacityCalibrationResult(HwcCapacityCalibrationStatus.PENDING)
        thermalReduced = false
        cancellationReason = null
        errorMessage = null
        performanceBaselinePowerSaveMode = null
        resetScenarioRunState()
    }

    private fun resetScenarioRunState() {
        telemetrySampleGate.beginRun()
        closeSelectedVideoDecoder()
        lastSummary = null
        lastReportFile = null
        lastSafetyAdjustments = emptyList()
        runSamples.clear()
        runEvents.clear()
        runFinalized = false
        lastPerformanceRestoreReportPersisted = false
        runStartMonotonicMs = SystemClock.elapsedRealtime()
        runStartEpochMs = System.currentTimeMillis()
        baselineExactUnderruns = null
        baselineExactSource = null
        baselineExactQuality = MetricQuality.UNAVAILABLE
        highestExactUnderruns = null
        exactCounterContinuous = false
        exactCounterContinuityEventRecorded = false
        exactCounterSamplesAfterBaseline = 0
        baselineSuspected = telemetry.suspectedUnderruns
        producerRateShortfallReason = null
        hwcCompositionCoverageFailureReason = null
        hwcCompositionChainAnchor = null
        severeThermalPolicyObservationRecorded = false
        activeHwcCompositionCoverage.set(null)
        hwcCompositionProbePriorityGate.reset()
        activeProducerFrameBudget = null
        producerRecoveryPaused = false
    }

    private suspend fun ensureProcessSessionHwcCapacityCalibration(
        firstScenario: ScenarioSpec,
    ) {
        val calibrationScope = currentHwcCapacityCalibrationScope(activity)
        when (
            val acquisition =
                ProcessHwcCapacityCalibrationSession.acquire(calibrationScope)
        ) {
            is HwcCapacityCalibrationAcquisition.Reuse -> {
                hwcCapacityCalibration = acquisition.result
                return
            }
            is HwcCapacityCalibrationAcquisition.Busy -> {
                throw PlanAbortException(
                    "다른 controller가 HWC capacity session 계측을 완료하지 못했습니다 " +
                        "(${acquisition.activeScope.label()}). 중복 20-layer 부하를 막기 위해 " +
                        "현재 plan을 시작하지 않습니다.",
                )
            }
            is HwcCapacityCalibrationAcquisition.Measure -> {
                var published = false
                var calibrationPriorityAcquired = false
                var priorityReleaseFailure: String? = null
                try {
                    if (calibrationScope.displayId == null) {
                        hwcCapacityCalibration = HwcCapacityCalibrationResult(
                            status = HwcCapacityCalibrationStatus.UNAVAILABLE,
                            detail =
                                "current physical display identity unavailable; " +
                                    "20-layer one-shot was not started",
                        )
                    } else {
                        if (
                            !hwcCompositionProbePriorityGate.acquire(
                                hwcCapacityCalibrationProbeOwner,
                            )
                        ) {
                            throw PlanAbortException(
                                "다른 typed HWC 계측이 아직 활성 상태여서 process-session " +
                                    "20-layer 계측을 시작하지 않았습니다.",
                            )
                        }
                        calibrationPriorityAcquired = true
                        telemetryWatchdogResumeGraceDeadlineMs = null
                        telemetryWatchdogCalibrationPaused = true
                        if (!awaitCalibrationTelemetryIsolation()) {
                            failCalibrationTelemetryLifecycle(
                                "HWC capacity 계측 전 기존 telemetry/vendor/SF worker " +
                                    "종료를 확인하지 못했습니다.",
                            )
                        }
                        performProcessSessionHwcCapacityCalibration(
                            firstScenario = firstScenario,
                            claim = acquisition.claim,
                        )
                    }
                    val completedScope =
                        currentHwcCapacityCalibrationScope(activity)
                    if (completedScope != calibrationScope) {
                        throw PlanAbortException(
                            "HWC capacity session 계측 중 display envelope가 변경되어 " +
                                "결과를 재사용할 수 없습니다.",
                        )
                    }
                    check(
                        hwcCapacityCalibration.status !=
                            HwcCapacityCalibrationStatus.PENDING,
                    ) {
                        "HWC capacity session calibration returned without a terminal result"
                    }
                    if (
                        !ProcessHwcCapacityCalibrationSession.complete(
                            claim = acquisition.claim,
                            result = hwcCapacityCalibration,
                        )
                    ) {
                        throw PlanAbortException(
                            "HWC capacity session 계측 owner가 변경되어 결과를 게시하지 " +
                                "못했습니다.",
                        )
                    }
                    hwcCapacityCalibration = checkNotNull(
                        ProcessHwcCapacityCalibrationSession.snapshot(calibrationScope),
                    ) {
                        "Completed HWC capacity session result was not reusable"
                    }
                    published = true
                } finally {
                    if (calibrationPriorityAcquired) {
                        // Cancellation can arrive while the initial isolation barrier is waiting.
                        // Keep priority ownership until an uncancellable second barrier proves that
                        // no old local/SF/vendor task can escape into a later scenario.
                        val finalIsolationConfirmed = try {
                            withContext(NonCancellable) {
                                awaitCalibrationTelemetryIsolation()
                            }
                        } catch (error: Exception) {
                            false
                        }
                        if (!finalIsolationConfirmed) {
                            val failure =
                                "HWC capacity telemetry final isolation을 확인하지 못해 " +
                                    "process 재시작 전 후속 시나리오를 차단합니다."
                            telemetryLifecycleIntegrityConfirmed.set(false)
                            cancellationReason = cancellationReason ?: failure
                            errorMessage = failure
                            priorityReleaseFailure = priorityReleaseFailure ?: failure
                        }
                        telemetryWatchdogResumeGraceDeadlineMs =
                            saturatingAddNonNegative(
                                SystemClock.elapsedRealtime(),
                                MONITOR_STALE_TIMEOUT_MS,
                            )
                        telemetryWatchdogCalibrationPaused = false
                        if (
                            !hwcCompositionProbePriorityGate.release(
                                hwcCapacityCalibrationProbeOwner,
                            )
                        ) {
                            priorityReleaseFailure = priorityReleaseFailure ?:
                                "HWC capacity telemetry priority owner 해제를 확인하지 못했습니다."
                            telemetryLifecycleIntegrityConfirmed.set(false)
                            cancellationReason =
                                cancellationReason ?: priorityReleaseFailure
                        }
                    }
                    if (!published) {
                        ProcessHwcCapacityCalibrationSession.abandon(
                            claim = acquisition.claim,
                            reason = cancellationReason
                                ?: "measurement, teardown, or settle did not complete",
                        )
                        hwcCapacityCalibration =
                            ProcessHwcCapacityCalibrationSession.snapshot(
                                currentHwcCapacityCalibrationScope(activity),
                            )
                                ?: HwcCapacityCalibrationResult(
                                    status = HwcCapacityCalibrationStatus.UNAVAILABLE,
                                    detail =
                                        "process-session one-shot ended without a reusable result",
                                )
                    }
                }
                priorityReleaseFailure?.let { failure ->
                    throw PlanAbortException(failure)
                }
            }
        }
    }

    private suspend fun awaitCalibrationTelemetryIsolation(): Boolean {
        val startedMs = SystemClock.elapsedRealtime()
        return withTimeoutOrNull(
            HWC_CAPACITY_CALIBRATION_TELEMETRY_DRAIN_TIMEOUT_MS,
        ) {
            telemetrySampleMutex.withLock {
                val elapsedMs =
                    (SystemClock.elapsedRealtime() - startedMs).coerceAtLeast(0L)
                val remainingMs =
                    (HWC_CAPACITY_CALIBRATION_TELEMETRY_DRAIN_TIMEOUT_MS - elapsedMs)
                        .coerceAtLeast(0L)
                remainingMs > 0L &&
                    systemMonitor.awaitCalibrationSampleQuiescent(
                        minOf(
                            remainingMs,
                            SYSTEM_MONITOR_CALIBRATION_QUIESCE_TIMEOUT_MS,
                        ),
                    )
            }
        } == true
    }

    private fun failCalibrationTelemetryLifecycle(reason: String): Nothing {
        val boundedReason = reason.take(MAX_EVENT_MESSAGE_CHARS)
        telemetryLifecycleIntegrityConfirmed.set(false)
        cancellationReason = cancellationReason ?: boundedReason
        errorMessage = boundedReason
        throw PlanAbortException(boundedReason)
    }

    private suspend fun performProcessSessionHwcCapacityCalibration(
        firstScenario: ScenarioSpec,
        claim: HwcCapacityCalibrationClaim,
    ) {
        check(hwcCapacityCalibration.status == HwcCapacityCalibrationStatus.PENDING) {
            "Process-session HWC capacity calibration must execute at most once"
        }
        check(
            HWC_CAPACITY_CALIBRATION_REQUESTED_LAYERS <=
                ScenarioSafetyPolicy.HARD_MAX_LAYERS,
        ) {
            "HWC capacity calibration candidate exceeds renderer hard cap"
        }
        ensurePlanMemoryAvailable()
        verifyPerformanceEnvironmentBeforeProducer()
        safetyLimits = DeviceRenderSafety.detect(
            activity = activity,
            originalPowerSaveMode = performanceBaselinePowerSaveMode == true,
        )
        val requestedPhase = processSessionHwcCapacityCalibrationPhase()
        val calibrationScenario = firstScenario.copy(
            id = "session-hwc-capacity-calibration",
            name = "App session HWC capacity one-shot",
            description =
                "Safety-approved RGB candidate topology for one fresh composition snapshot",
            tags = emptySet(),
            requirements = emptySet(),
            phases = listOf(requestedPhase),
            isCustom = false,
        )
        val decision = ScenarioSafetyPolicy.evaluate(
            scenario = calibrationScenario,
            limits = safetyLimits,
            selectedDecoderBuffer = null,
        )
        val candidatePhase = decision.effectiveScenario?.phases?.singleOrNull()
        if (candidatePhase == null) {
            hwcCapacityCalibration = HwcCapacityCalibrationResult(
                status = HwcCapacityCalibrationStatus.UNAVAILABLE,
                detail = "safety policy rejected calibration candidate: " +
                    decision.rejectionReason.orEmpty(),
            )
            return
        }
        if (!confirmGeneratedLoadQuiesce()) {
            throw PlanAbortException(
                "HWC capacity session 계측 전 generated/NPU load zero를 확인하지 못했습니다.",
            )
        }
        requestDisplayModeOrAbort(
            HWC_CAPACITY_CALIBRATION_DISPLAY_HZ,
            "app-session HWC capacity calibration",
        )
        val deadlineMs = saturatingAdd(
            SystemClock.elapsedRealtime(),
            HWC_CAPACITY_CALIBRATION_TOTAL_TIMEOUT_MS,
        )
        val generation = beginTrackedProducerGeneration()
        if (
            !ProcessHwcCapacityCalibrationSession.recordCandidate(
                claim = claim,
                candidateLayers = candidatePhase.activeLayers,
            )
        ) {
            throw PlanAbortException(
                "HWC capacity session owner가 변경되어 physical candidate를 " +
                    "기록하지 못했습니다.",
            )
        }
        progress = RunProgress(
            stage = RunnerStage.WARMUP,
            scenario = firstScenario,
            phase = candidatePhase,
            targetPhase = candidatePhase,
            statusText =
                "앱 session 최초 1회 HWC capacity 관측 · " +
                    "${candidatePhase.activeLayers}L/20L 후보 준비",
            producerGeneration = generation,
        )
        var readinessAtSnapshot: ProducerReadiness? = null
        var unavailableReason: String? = null
        var generationActivated = false
        var teardownConfirmed: Boolean
        var nextDirectSafetyCheckMs = SystemClock.elapsedRealtime()
        try {
            while (readinessAtSnapshot == null && unavailableReason == null) {
                currentCoroutineContext().ensureActive()
                val nowMs = SystemClock.elapsedRealtime()
                if (nowMs >= nextDirectSafetyCheckMs) {
                    // Periodic vendor/SF/counter telemetry is intentionally isolated here, but
                    // critical thermal, power-envelope and low-memory safety remain live.
                    ensurePlanMemoryAvailable()
                    verifyPerformanceEnvironmentBeforeProducer(
                        recordSuccessfulObservation = false,
                    )
                    nextDirectSafetyCheckMs =
                        saturatingAdd(nowMs, LOAD_CONTROL_CADENCE_MS)
                }
                val readiness = frameTracker.producerReadiness(generation)
                val rendererCleanupPending = RendererSafetyState.hasUnconfirmedTeardown()
                readiness.runtimeFailureReason?.let { failure ->
                    throw PlanAbortException(
                        "HWC capacity calibration producer 실행 실패: $failure",
                    )
                }
                if (readiness.teardownFailed) {
                    throw PlanAbortException(
                        "HWC capacity calibration producer teardown/recovery가 실패했습니다.",
                    )
                }
                if (
                    hwcCapacityGenerationAction(
                        generationActivated = generationActivated,
                        topologyPublished = readiness.topologyPublished,
                        topologyPending = readiness.topologyPending,
                        expectedProducerCount = readiness.expectedCount,
                        candidateLayers = candidatePhase.activeLayers,
                        rendererCleanupPending = rendererCleanupPending,
                    ) == HwcCapacityGenerationAction.ACTIVATE &&
                    readiness.geometryReady &&
                    readiness.geometryAppliedProfileOrdinal ==
                    candidatePhase.layerSizeProfile.ordinal &&
                    frameTracker.activateProducerGeneration(generation)
                ) {
                    // activate() atomically clears every pre-activation observation. From this
                    // point onward the ready gate can only be satisfied by fresh first buffers.
                    generationActivated = true
                }
                if (
                    generationActivated &&
                    hwcCapacityCalibrationTopologyReady(
                        readiness = readiness,
                        candidateLayers = candidatePhase.activeLayers,
                        expectedProfileOrdinal =
                            candidatePhase.layerSizeProfile.ordinal,
                        rendererCleanupPending = rendererCleanupPending,
                    )
                ) {
                    readinessAtSnapshot = readiness
                    break
                }
                if (nowMs >= deadlineMs || readiness.topologyMissed) {
                    unavailableReason =
                        "candidate topology readiness unavailable; " +
                            "expected=${candidatePhase.activeLayers}, " +
                            "published=${readiness.expectedCount}, " +
                            "observed=${readiness.observedCount}, " +
                            "geometry=${readiness.geometryAppliedRevision}/" +
                            "${readiness.geometryRequestedRevision}, " +
                            "topologyMissed=${readiness.topologyMissed}"
                    break
                }
                progress = progress.copy(
                    expectedProducerCount = visibleExpectedProducerCount(
                        committedExpectedCount = readiness.expectedCount,
                        topologyPublished = readiness.topologyPublished,
                        topologyPending = readiness.topologyPending,
                        processLeaseActive = rendererCleanupPending,
                    ),
                    observedProducerCount = readiness.observedCount,
                )
                delay(RENDERER_TEARDOWN_POLL_MS)
            }

            if (readinessAtSnapshot != null) {
                val remainingMs = deadlineMs - SystemClock.elapsedRealtime()
                if (remainingMs < HWC_CAPACITY_CALIBRATION_STABILIZE_MS) {
                    readinessAtSnapshot = null
                    unavailableReason =
                        "candidate became ready without stabilization budget"
                } else {
                    // First-buffer means that every producer submitted at least once; it does not
                    // prove that SurfaceFlinger has latched and validated the last transaction.
                    // Keep the snapshot count at one while allowing several vsyncs to settle.
                    delay(HWC_CAPACITY_CALIBRATION_STABILIZE_MS)
                    currentCoroutineContext().ensureActive()
                    ensurePlanMemoryAvailable()
                    verifyPerformanceEnvironmentBeforeProducer()
                    val isolationToken = activeTestWindowIsolationToken
                    if (
                        isolationToken == null ||
                        !testWindowIsolation.isConfirmed(isolationToken)
                    ) {
                        throw PlanAbortException(
                            "HWC capacity calibration 안정화 중 SystemUI 격리가 해제되었습니다.",
                        )
                    }
                    val stabilized = frameTracker.producerReadiness(generation)
                    val topologyStillReady =
                        hwcCapacityCalibrationTopologyReady(
                            readiness = stabilized,
                            candidateLayers = candidatePhase.activeLayers,
                            expectedProfileOrdinal =
                                candidatePhase.layerSizeProfile.ordinal,
                            rendererCleanupPending =
                                RendererSafetyState.hasUnconfirmedTeardown(),
                        )
                    readinessAtSnapshot = stabilized.takeIf { topologyStillReady }
                    if (!topologyStillReady) {
                        unavailableReason =
                            "candidate topology changed during stabilization; " +
                                "expected=${candidatePhase.activeLayers}, " +
                                "published=${stabilized.expectedCount}, " +
                                "observed=${stabilized.observedCount}, " +
                                "geometry=${stabilized.geometryAppliedRevision}/" +
                                "${stabilized.geometryRequestedRevision}"
                    }
                }
            }

            val ready = readinessAtSnapshot
            if (ready == null) {
                hwcCapacityCalibration = HwcCapacityCalibrationResult(
                    status = HwcCapacityCalibrationStatus.UNAVAILABLE,
                    candidateLayers = candidatePhase.activeLayers,
                    detail = unavailableReason.orEmpty(),
                )
            } else {
                val calibrationSampleStartedMs = SystemClock.elapsedRealtime()
                val sampleBudgetMs = remainingHwcCapacityCalibrationBudgetMs(
                    deadlineMs = deadlineMs,
                    nowMs = calibrationSampleStartedMs,
                )
                var sampleFailure: String? = null
                val snapshot = if (sampleBudgetMs <= 0L) {
                    null
                } else {
                    withTimeoutOrNull(sampleBudgetMs) {
                        try {
                            collectPlanHwcCapacityCalibrationSample(deadlineMs)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            sampleFailure =
                                "single fresh composition snapshot failed: " +
                                    error.javaClass.simpleName
                            null
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                val postSampleIsolationToken = activeTestWindowIsolationToken
                val postSampleIsolationConfirmed =
                    postSampleIsolationToken != null &&
                        testWindowIsolation.isConfirmed(postSampleIsolationToken)
                val postSampleReadiness =
                    frameTracker.producerReadiness(generation)
                // The snapshot is complete. Stop publishing the 20-layer target before any
                // potentially slower validation so the absolute producer-active deadline is
                // enforced even at its boundary.
                progress = progress.copy(
                    phase = null,
                    targetPhase = null,
                    transitionFraction = 0f,
                    statusText = "HWC capacity snapshot 검증/teardown",
                )
                ensurePlanMemoryAvailable()
                verifyPerformanceEnvironmentBeforeProducer()
                if (!postSampleIsolationConfirmed) {
                    throw PlanAbortException(
                        "HWC capacity snapshot 중 SystemUI 격리가 해제되었습니다.",
                    )
                }
                val completedWithinDeadline =
                    remainingHwcCapacityCalibrationBudgetMs(
                        deadlineMs = deadlineMs,
                        nowMs = SystemClock.elapsedRealtime(),
                    ) > 0L
                val postSampleTopologyReady =
                    hwcCapacityCalibrationTopologyUnchanged(
                        before = ready,
                        after = postSampleReadiness,
                        candidateLayers = candidatePhase.activeLayers,
                        expectedProfileOrdinal =
                            candidatePhase.layerSizeProfile.ordinal,
                        rendererCleanupPending =
                            RendererSafetyState.hasUnconfirmedTeardown(),
                    )
                hwcCapacityCalibration = if (!completedWithinDeadline) {
                    HwcCapacityCalibrationResult(
                        status = HwcCapacityCalibrationStatus.UNAVAILABLE,
                        candidateLayers = candidatePhase.activeLayers,
                        detail =
                            "single fresh composition snapshot/validation exceeded the " +
                                "${HWC_CAPACITY_CALIBRATION_TOTAL_TIMEOUT_MS}ms " +
                                "total producer-active deadline",
                    )
                } else if (!postSampleTopologyReady) {
                    HwcCapacityCalibrationResult(
                        status = HwcCapacityCalibrationStatus.UNAVAILABLE,
                        candidateLayers = candidatePhase.activeLayers,
                        detail =
                            "candidate topology changed during composition snapshot; " +
                                "expected=${candidatePhase.activeLayers}, " +
                                "published=${postSampleReadiness.expectedCount}, " +
                                "observed=${postSampleReadiness.observedCount}, " +
                                "ready=${postSampleReadiness.ready}, " +
                                "pending=${postSampleReadiness.topologyPending}, " +
                                "missed=${postSampleReadiness.topologyMissed}, " +
                                "teardown=${postSampleReadiness.teardownCompleted}/" +
                                "${postSampleReadiness.teardownFailed}",
                    )
                } else if (snapshot == null) {
                    HwcCapacityCalibrationResult(
                        status = HwcCapacityCalibrationStatus.UNAVAILABLE,
                        candidateLayers = candidatePhase.activeLayers,
                        detail = sampleFailure
                            ?: "single fresh composition snapshot exceeded the " +
                                "${HWC_CAPACITY_CALIBRATION_TOTAL_TIMEOUT_MS}ms " +
                                "total producer-active deadline",
                    )
                } else {
                    hwcCapacityCalibrationResult(
                        candidateLayers = candidatePhase.activeLayers,
                        expectedProducerCount = postSampleReadiness.expectedCount,
                        observedProducerCount = postSampleReadiness.observedCount,
                        sampleStartedMonotonicMs = calibrationSampleStartedMs,
                        snapshot = snapshot,
                    )
                }
            }
        } finally {
            teardownConfirmed = withContext(NonCancellable) {
                progress = progress.copy(
                    phase = null,
                    targetPhase = null,
                    transitionFraction = 0f,
                    statusText = "HWC capacity calibration teardown/zero-load settle",
                )
                val loadsZero = confirmGeneratedLoadQuiesce()
                val rendererReleased = awaitRendererTeardownBarrier()
                if (rendererReleased) {
                    // Calibration is not a scenario sample. Consume every producer/generated
                    // traffic counter only after final producer teardown so its tail cannot leak
                    // into the first scenario's warm-up baseline or peaks.
                    frameTracker.sampleProducedFrames()
                    loadManager.sampleAndResetBandwidthBytes()
                }
                val telemetryQuiescent = awaitCalibrationTelemetryIsolation()
                if (!telemetryQuiescent) {
                    val reason =
                        "HWC capacity 계측 telemetry/vendor/SF worker 종료를 확인하지 " +
                            "못해 process 재시작 전 후속 시나리오를 차단합니다."
                    telemetryLifecycleIntegrityConfirmed.set(false)
                    cancellationReason = cancellationReason ?: reason
                    errorMessage = reason
                }
                val confirmed =
                    telemetryQuiescent && loadsZero && rendererReleased
                if (confirmed) {
                    delay(HWC_CAPACITY_CALIBRATION_SETTLE_MS)
                    if (cancellationReason == null) {
                        ensurePlanMemoryAvailable()
                        verifyPerformanceEnvironmentBeforeProducer(
                            recordSuccessfulObservation = false,
                        )
                    }
                }
                confirmed
            }
        }
        if (!teardownConfirmed) {
            throw PlanAbortException(
                "HWC capacity calibration producer/load teardown을 확인하지 못했습니다.",
            )
        }
    }

    private suspend fun executeScenario(requestedScenario: ScenarioSpec): ScenarioRunArtifact {
        resetScenarioRunState()
        var scenarioForReport = requestedScenario
        var cleanupConfirmed = false
        val pendingMediaSource = AtomicReference<PinnedMediaSource?>()
        try {
            runEvents += event(
                "SESSION_HWC_CAPACITY_CALIBRATION",
                hwcCapacityCalibration.eventDetail(),
            )
            runEvents += event(
                "SESSION_HWC_CAPACITY_REUSE_GUIDANCE",
                capacityReuseGuidance(hwcCapacityCalibration).let { guidance ->
                    "deviceCandidateCeiling=" +
                        "${guidance.deviceCandidateCeiling ?: "N/A"}; " +
                        "clientPressureCandidate=" +
                        "${guidance.clientPressureCandidate ?: "N/A"}; " +
                        guidance.detail
                },
            )
            val isolationToken = activeTestWindowIsolationToken
            if (
                isolationToken == null ||
                !testWindowIsolation.isConfirmed(isolationToken)
            ) {
                val reason = "측정 시작 전 SystemUI 격리 상태를 확인할 수 없습니다."
                cancellationReason = reason
                throw PlanAbortException(reason)
            }
            runEvents += event(
                "SYSTEM_UI_ISOLATED",
                "status/navigation bars hidden; token=$isolationToken",
            )
            runEvents += event(
                "PERFORMANCE_ISOLATION",
                performanceIsolationStatus.take(MAX_EVENT_MESSAGE_CHARS),
            )
            runEvents += event(
                "RUNTIME_PROTECTION_POLICY",
                runtimeProtectionPolicyDescription(activeRuntimeProtectionPolicy),
            )
            if (hasUnconfirmedRendererCleanup()) {
                val reason =
                    "이전 physical producer teardown이 미확인 상태여서 plan을 안전 중단합니다."
                cancellationReason = reason
                throw PlanAbortException(reason)
            }
            if (LoadSafetyState.hasUnconfirmedNpuCleanup()) {
                val reason = "이전 NPU cleanup 미확인 상태여서 plan을 안전 중단합니다."
                cancellationReason = reason
                throw PlanAbortException(reason)
            }
            if (MediaPreflightSafetyState.isActive()) {
                val reason =
                    "이전 media provider/parser preflight가 아직 반환되지 않아 plan을 안전 중단합니다."
                cancellationReason = reason
                throw PlanAbortException(reason)
            }
            runEvents += event(
                "PLAN_POSITION",
                "source=${planProgress.source.name}; " +
                    "run=${planProgress.completedRuns + 1}/${planProgress.totalRuns}; " +
                    "repeat=${planProgress.currentRepeat}/${planProgress.repeatCount}; " +
                    "queue=${planProgress.currentQueuePosition}/${planProgress.queueSize}",
            )
            if (thermalReduced) {
                runEvents += event(
                    "THERMAL_DERATE",
                    "persistent derating carried from an earlier plan item",
                )
            }
            ensurePlanMemoryAvailable()
            safetyLimits = DeviceRenderSafety.detect(
                activity = activity,
                originalPowerSaveMode = performanceBaselinePowerSaveMode == true,
            )
            val scenarioUsesSelectedDecoder =
                requestedScenario.phases.any(::canUseVideoPrimary)
            if (scenarioUsesSelectedDecoder && selectedMediaUri == null) {
                throw UnsupportedRunException(
                    "YUV/P010/SBWC decoder phase에는 사용자가 선택한 로컬 영상이 필요합니다.",
                )
            }
            val mediaForRun = selectedMediaUri.takeIf { scenarioUsesSelectedDecoder }
            val mediaInfo = if (mediaForRun != null) {
                inspectMedia(mediaForRun, pendingMediaSource)
            } else {
                null
            }
            selectedMediaWidthPx = mediaInfo?.width
            selectedMediaHeightPx = mediaInfo?.height
            selectedMediaLinearReference = mediaInfo?.decoderLinearReference
            if (mediaForRun != null && mediaInfo?.readable != true) {
                throw UnsupportedRunException("선택한 영상 URI를 읽을 수 없습니다.")
            }
            val selectedDecoderBuffer = if (mediaForRun != null) {
                SelectedDecoderBuffer(
                    // Include ordinary codec stride/height alignment in the conservative
                    // triple-buffer graphics budget used by ScenarioSafetyPolicy.
                    widthPx = mediaInfo?.width?.let(::videoDimensionCeiling),
                    heightPx = mediaInfo?.height?.let(::videoDimensionCeiling),
                )
            } else {
                null
            }
            val decision = ScenarioSafetyPolicy.evaluate(
                scenario = requestedScenario,
                limits = safetyLimits,
                selectedDecoderBuffer = selectedDecoderBuffer,
            )
            decision.rejectionReason?.let {
                throw UnsupportedRunException("안전 정책 거부: $it")
            }
            val scenario = checkNotNull(decision.effectiveScenario)
            scenarioForReport = scenario
            lastSafetyAdjustments = decision.adjustments
            runEvents += event(
                "SAFETY_ENVELOPE",
                DeviceRenderSafety.describe(safetyLimits),
            )
            decision.adjustments.forEach { adjustment ->
                runEvents += event("SAFETY_LIMIT", adjustment)
            }
            if (decision.adjustments.isNotEmpty()) {
                errorMessage = "기기 안전 한도에 맞춰 ${decision.adjustments.size}개 항목을 조정했습니다."
            }

            ensurePlanMemoryAvailable()
            awaitVendorCapabilityDiscovery(scenario)?.let {
                throw InconclusiveRunException(it)
            }
            recoverCompressionRouteBeforeRun()
            precheck(scenario)?.let { throw UnsupportedRunException(it) }
            validateBufferCapabilities(scenario, mediaForRun != null)?.let {
                throw UnsupportedRunException(it)
            }
            val mediaValidation = withContext(Dispatchers.Default) {
                validateRequestedMedia(scenario, mediaForRun, mediaInfo)
            }
            mediaValidation.error?.let {
                throw UnsupportedRunException(it)
            }
            selectedVideoDecoder = mediaValidation.decoderSelection
            selectedVideoDecoder?.let { selection ->
                check(pendingMediaSource.compareAndSet(selection.pinnedSource, null)) {
                    "Pinned media ownership transfer failed"
                }
            }
            progress = RunProgress(
                stage = RunnerStage.PRECHECK,
                scenario = scenario,
                statusText = "capability와 telemetry source 확인 중",
                thermalDerated = thermalReduced,
            )
            runEvents += event("PRECHECK", "scenario=${scenario.id}")
            if (mediaInfo != null) {
                val codecDetail = mediaValidation.decoderSelection?.let {
                    " · hardwareCodec=${it.codecName}"
                }.orEmpty()
                runEvents += event("MEDIA_SOURCE", mediaInfo.description + codecDetail)
            }
            delay(PRECHECK_DELAY_MS)
            verifyPerformanceEnvironmentBeforeProducer()

            val warmupProducerGeneration = beginTrackedProducerGeneration()
            progress = progress.copy(
                stage = RunnerStage.WARMUP,
                phaseIndex = 0,
                phase = scenario.phases.firstOrNull()
                    ?.let(::applyPersistentSafety)
                    ?.let(::safeWarmupPhaseFor),
                targetPhase = scenario.phases.firstOrNull()?.let(::applyPersistentSafety),
                transitionFraction = 0f,
                statusText = "surface warm-up",
                producerGeneration = warmupProducerGeneration,
            )
            loadManager.releaseLoads()
            if (scenarioNeedsMemoryPrewarm(scenario)) {
                progress = progress.copy(
                    statusText = "surface 및 DRAM working-set 사전 준비",
                )
                val prewarmed = withContext(Dispatchers.IO) {
                    loadManager.prewarmMemoryWorkingSet()
                }
                if (!prewarmed) {
                    val reason = if (loadManager.hasMemoryAllocationFailure()) {
                        "Memory workload working-set 사전 할당 실패로 전체 plan을 안전 중단합니다."
                    } else {
                        "Memory workload working-set 사전 준비를 확인하지 못해 전체 plan을 안전 중단합니다."
                    }
                    cancellationReason = reason
                    runEvents += event("MEMORY_PREWARM_FAILURE", reason)
                    throw PlanAbortException(reason)
                }
                // Page touches are deliberately outside measured traffic. Reset the generated-byte
                // window once more before the fresh telemetry/counter baseline.
                loadManager.releaseLoads()
                loadManager.sampleAndResetBandwidthBytes()
                runEvents += event(
                    "MEMORY_PREWARM",
                    "working-set allocation/page touch confirmed; measured-byte baseline reset",
                )
            }
            delay(WARMUP_DELAY_MS)
            // Attribute deltas to the actual scenario, not Surface creation, codec preparation,
            // or the Compose transition into the run screen.
            val baselineSample = try {
                collectTelemetrySample()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                throw InconclusiveRunException(
                    "warm-up 이후 fresh counter baseline을 얻지 못했습니다: " +
                        error.javaClass.simpleName,
                )
            }
            establishCounterBaseline(baselineSample)
            runScenarioPhases(scenario)

            // Preserve the last measured generation and publish phase=null first. This lets
            // Compose detach codec/EGL/Canvas producers before a vendor compression reset changes
            // their allocation route. A copied last phase is not a neutral cooldown: it can retain
            // an 8K decoder, GL tail, alpha/client composition, or SBWC-backed buffers.
            progress = progressForCooldownTeardown(progress)
            planProgress = planProgress.copy(currentRunFraction = 1f)
            if (!releaseActiveLoadsForRun()) {
                val reason =
                    "부하, physical producer 또는 compression 해제를 확인할 수 없어 " +
                        "plan을 안전 중단합니다."
                cancellationReason = reason
                throw PlanAbortException(reason)
            }
            cleanupConfirmed = true
            requestDisplayModeSafely(60f, "cooldown")
            delay(COOLDOWN_DELAY_MS)
            progress = progress.copy(
                statusText = "결과 보고서 저장 중",
            )
            // A normal verdict is derived only after the final physical producer has drained and
            // one serialized fresh counter sample has covered that teardown tail.
            return finalizeProtected(
                scenario = scenario,
                preselectedVerdict = null,
                pendingMediaSource = pendingMediaSource,
            )
        } catch (cancelled: CancellationException) {
            val initialReason = cancellationReason
                ?: cancelled.message
                ?: "사용자가 중단함"
            // A safety/user cancellation must drop controllable stress before potentially slow
            // JSON generation, flush, and fd.sync in the report path.
            progress = progress.copy(
                stage = RunnerStage.ABORTED,
                scenario = scenarioForReport,
                phase = null,
                targetPhase = null,
                statusText = initialReason,
            )
            val cleanupSucceeded = withContext(NonCancellable) {
                val released = releaseActiveLoadsForRun()
                resetDisplayModeSafely()
                setWakeStateSafely(false)
                released
            }
            cleanupConfirmed = cleanupSucceeded
            val reason = if (cleanupSucceeded) {
                initialReason
            } else {
                "$initialReason · 부하/compression 해제 미확인"
                    .take(MAX_EVENT_MESSAGE_CHARS)
            }
            cancellationReason = reason
            progress = progress.copy(
                stage = RunnerStage.ABORTED,
                scenario = scenarioForReport,
                phase = null,
                targetPhase = null,
                statusText = reason,
            )
            if (!runFinalized) {
                runEvents += event("ABORTED", reason)
                finalizeProtected(
                    scenarioForReport,
                    RunVerdict.ABORTED,
                    pendingMediaSource,
                )
            }
            throw cancelled
        } catch (unsupported: UnsupportedRunException) {
            val unsupportedReason = unsupported.message.orEmpty()
            runEvents += event("UNSUPPORTED", unsupportedReason)
            progress = progress.copy(
                stage = RunnerStage.UNSUPPORTED,
                scenario = scenarioForReport,
                phase = null,
                targetPhase = null,
                statusText = unsupportedReason,
            )
            val cleanupSucceeded = withContext(NonCancellable) {
                val released = releaseActiveLoadsForRun()
                resetDisplayModeSafely()
                released
            }
            cleanupConfirmed = cleanupSucceeded
            return if (cleanupSucceeded) {
                progress = RunProgress(
                    stage = RunnerStage.UNSUPPORTED,
                    scenario = scenarioForReport,
                    statusText = unsupportedReason,
                    thermalDerated = thermalReduced,
                )
                finalizeProtected(
                    scenarioForReport,
                    RunVerdict.UNSUPPORTED,
                    pendingMediaSource,
                )
            } else {
                val reason = "$unsupportedReason · 부하/compression 해제 미확인"
                    .take(MAX_EVENT_MESSAGE_CHARS)
                cancellationReason = reason
                progress = RunProgress(
                    stage = RunnerStage.ABORTED,
                    scenario = scenarioForReport,
                    statusText = reason,
                    thermalDerated = thermalReduced,
                )
                runEvents += event("ABORTED", reason)
                finalizeProtected(
                    scenarioForReport,
                    RunVerdict.ABORTED,
                    pendingMediaSource,
                )
            }
        } catch (inconclusive: InconclusiveRunException) {
            val reason = inconclusive.message.orEmpty()
            runEvents += event("INCONCLUSIVE", reason)
            progress = progress.copy(
                stage = RunnerStage.COMPLETE,
                scenario = scenarioForReport,
                phase = null,
                targetPhase = null,
                statusText = reason,
            )
            val cleanupSucceeded = withContext(NonCancellable) {
                val released = releaseActiveLoadsForRun()
                resetDisplayModeSafely()
                released
            }
            cleanupConfirmed = cleanupSucceeded
            return if (cleanupSucceeded) {
                finalizeProtected(
                    scenarioForReport,
                    RunVerdict.INCONCLUSIVE,
                    pendingMediaSource,
                )
            } else {
                val abortReason = "$reason · 부하/compression 해제 미확인"
                    .take(MAX_EVENT_MESSAGE_CHARS)
                cancellationReason = abortReason
                progress = RunProgress(
                    stage = RunnerStage.ABORTED,
                    scenario = scenarioForReport,
                    statusText = abortReason,
                    thermalDerated = thermalReduced,
                )
                runEvents += event("ABORTED", abortReason)
                finalizeProtected(
                    scenarioForReport,
                    RunVerdict.ABORTED,
                    pendingMediaSource,
                )
            }
        } catch (error: Exception) {
            errorMessage = "실행 실패: ${error.message ?: error.javaClass.simpleName}"
            runEvents += event(
                "ERROR",
                error.stackTraceToString().take(MAX_EVENT_MESSAGE_CHARS),
            )
            progress = progress.copy(
                stage = RunnerStage.ABORTED,
                scenario = scenarioForReport,
                phase = null,
                targetPhase = null,
                statusText = errorMessage.orEmpty(),
            )
            val cleanupSucceeded = withContext(NonCancellable) {
                val released = releaseActiveLoadsForRun()
                resetDisplayModeSafely()
                released
            }
            cleanupConfirmed = cleanupSucceeded
            val reason = if (cleanupSucceeded) {
                errorMessage.orEmpty()
            } else {
                "${errorMessage.orEmpty()} · 부하/compression 해제 미확인"
                    .take(MAX_EVENT_MESSAGE_CHARS)
            }
            cancellationReason = preserveFirstCancellationReason(
                current = cancellationReason,
                requested = reason,
                fallback = "예기치 않은 실행 오류",
                maxChars = MAX_EVENT_MESSAGE_CHARS,
            )
            progress = RunProgress(
                stage = RunnerStage.ABORTED,
                scenario = scenarioForReport,
                statusText = reason,
                thermalDerated = thermalReduced,
            )
            runEvents += event("ABORTED", reason)
            return finalizeProtected(
                scenarioForReport,
                unexpectedExceptionVerdict(),
                pendingMediaSource,
            )
        } finally {
            activeProducerFrameBudget = null
            activeHwcCompositionCoverage.set(null)
            // Scenario teardown is terminal: no typed phase may retain priority after any
            // cancellation, fatal error, or finalization failure that bypassed phase cleanup.
            hwcCompositionProbePriorityGate.reset()
            withContext(NonCancellable) {
                if (!cleanupConfirmed) releaseActiveLoadsForRun()
                resetDisplayModeSafely()
            }
            closeSelectedVideoDecoder()
            pendingMediaSource.getAndSet(null)?.closeWithResult()
        }
    }

    private fun closeSelectedVideoDecoder(): Boolean {
        val previous = selectedVideoDecoder
        selectedVideoDecoder = null
        return previous?.closeWithResult() ?: true
    }

    private fun establishCounterBaseline(snapshot: TelemetrySnapshot) {
        baselineExactUnderruns = snapshot.exactUnderruns
        baselineExactSource = snapshot.exactUnderrunSource
        baselineExactQuality = snapshot.exactUnderrunQuality
        highestExactUnderruns = baselineExactUnderruns
        exactCounterContinuous =
            baselineExactUnderruns != null &&
                !baselineExactSource.isNullOrBlank() &&
                baselineExactQuality in EXACT_COUNTER_QUALITIES
        baselineSuspected = snapshot.suspectedUnderruns
        runEvents += event(
                "COUNTER_BASELINE",
                "exact=${baselineExactUnderruns ?: "N/A"}; " +
                "quality=${baselineExactQuality.name}; " +
                "source=${baselineExactSource ?: "N/A"}; proxy=$baselineSuspected",
        )
    }

    private suspend fun collectTelemetrySample(
        forceCompositionProbe: Boolean = false,
    ): TelemetrySnapshot =
        telemetrySampleMutex.withLock {
            collectTelemetrySampleLocked(forceCompositionProbe)
        }

    private suspend fun collectPlanHwcCapacityCalibrationSample(
        deadlineMs: Long,
    ): TelemetrySnapshot =
        telemetrySampleMutex.withLock {
            val remainingMs = remainingHwcCapacityCalibrationBudgetMs(
                deadlineMs = deadlineMs,
                nowMs = SystemClock.elapsedRealtime(),
            )
            val laneTimeoutMs = hwcCapacityCalibrationSampleLaneTimeoutMs(
                remainingMs = remainingMs,
                hardTimeoutMs = SYSTEM_MONITOR_SAMPLE_TIMEOUT_MS,
                completionReserveMs =
                    HWC_CAPACITY_CALIBRATION_SAMPLE_RESERVE_MS,
            )
            check(laneTimeoutMs != null) {
                "HWC capacity sample has no remaining lane deadline"
            }
            collectTelemetrySampleLocked(
                forceCompositionProbe = false,
                surfaceFlingerProbePolicyOverride =
                    SurfaceFlingerProbePolicy.CALIBRATION_ONESHOT,
                systemMonitorSampleTimeoutMs = laneTimeoutMs,
            )
        }

    /**
     * Periodic HUD sampling is latest-wins. It never creates a mutex waiter, especially while a
     * typed HWC batch owns priority; forced/boundary samples themselves refresh safety telemetry
     * and exact-counter continuity.
     */
    private suspend fun collectPeriodicTelemetrySample(): TelemetrySnapshot? {
        if (!telemetrySampleMutex.tryLock()) return null
        return try {
            when (
                periodicTelemetryArbitration(
                    mutexAcquired = true,
                    typedHwcProbePriority =
                        hwcCompositionProbePriorityGate.isActive(),
                )
            ) {
                PeriodicTelemetryArbitration.SAMPLE ->
                    collectTelemetrySampleLocked(forceCompositionProbe = false)
                PeriodicTelemetryArbitration.DROP_BUSY,
                PeriodicTelemetryArbitration.DROP_TYPED_HWC_PRIORITY,
                -> null
            }
        } finally {
            telemetrySampleMutex.unlock()
        }
    }

    /** Caller must own [telemetrySampleMutex]. */
    private suspend fun collectTelemetrySampleLocked(
        forceCompositionProbe: Boolean,
        surfaceFlingerProbePolicyOverride: SurfaceFlingerProbePolicy? = null,
        systemMonitorSampleTimeoutMs: Long = SYSTEM_MONITOR_SAMPLE_TIMEOUT_MS,
    ): TelemetrySnapshot {
        require(
            systemMonitorSampleTimeoutMs in 1L..SYSTEM_MONITOR_SAMPLE_TIMEOUT_MS,
        )
        val generation = telemetrySampleGate.issue()
        val sampleStartedMs = SystemClock.elapsedRealtime()
        telemetrySampleInFlightDeadlineMs = telemetrySampleDeadlineMs(
            sampleStartedMs = sampleStartedMs,
            sampleTimeoutMs = systemMonitorSampleTimeoutMs,
            completionGraceMs = WATCHDOG_INTERVAL_MS,
        )
        val snapshot = try {
            systemMonitor.sample(
                display = activity.currentDisplayCompat(),
                // An app-spawned dumpsys process can perturb CPU/SF scheduling during the load
                // under test. Active runs use fresh vendor composition snapshots when available
                // and invoke SurfaceFlinger only at an explicit typed HWC observation boundary.
                surfaceFlingerProbePolicy =
                    surfaceFlingerProbePolicyOverride ?: surfaceFlingerProbePolicy(
                        forceCompositionProbe = forceCompositionProbe,
                        activeRun = isRunning,
                    ),
                sampleTimeoutMs = systemMonitorSampleTimeoutMs,
            )
        } finally {
            telemetrySampleInFlightDeadlineMs = null
        }
        lastSuccessfulSampleMs = SystemClock.elapsedRealtime()
        telemetryWatchdogResumeGraceDeadlineMs = null
        acceptSnapshot(
            generation = generation,
            snapshot = snapshot,
            sampleStartedMonotonicMs = sampleStartedMs,
        )
        return snapshot
    }

    private suspend fun acceptSnapshot(
        generation: Long,
        snapshot: TelemetrySnapshot,
        sampleStartedMonotonicMs: Long,
    ) {
        telemetry = snapshot
        telemetryHistory += snapshot
        while (telemetryHistory.size > MAX_TELEMETRY_HISTORY) telemetryHistory.removeAt(0)
        directSensors.clear()
        directSensors.addAll(systemMonitor.directSensorReadings())
        if (!isRunning) return
        // Safety observations stay conservative even if the sample was requested by an older run.
        enforceRuntimeSafety(snapshot)
        enforcePerformanceIsolationContinuity(snapshot)
        enforceCompressionSessionContinuity(snapshot)
        if (!telemetrySampleGate.belongsToCurrentRun(generation)) return

        activeHwcCompositionCoverage.get()?.observe(
            sampleStartedMonotonicMs = sampleStartedMonotonicMs,
            snapshot = snapshot,
        )
        if (runSamples.size < MAX_RUN_SAMPLES) {
            runSamples += snapshot.toRunRelativeTelemetry(runStartMonotonicMs)
        }
        observeExactCounter(snapshot)
    }

    private suspend fun collectAdaptiveBoundarySample(
        phaseId: String,
        edge: String,
    ): TelemetrySnapshot {
        val snapshot = try {
            collectTelemetrySample()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw InconclusiveRunException(
                "Adaptive phase '$phaseId' $edge boundary counter sample 실패: " +
                    error.javaClass.simpleName,
            )
        }
        runEvents += event(
            "ADAPTIVE_BOUNDARY_SAMPLE",
            "phase=$phaseId; edge=$edge; exact=${snapshot.exactUnderruns ?: "N/A"}; " +
                "quality=${snapshot.exactUnderrunQuality.name}; " +
                "source=${snapshot.exactUnderrunSource ?: "N/A"}; " +
                "proxy=${snapshot.suspectedUnderruns}",
        )
        return snapshot
    }

    private suspend fun runScenarioPhases(scenario: ScenarioSpec) {
        var scenarioElapsed = 0L
        var phaseIndex = 0
        var previousRawRuntime: PhaseSpec? = null
        val adaptiveRecoveryIndex = adaptiveRecoveryPhaseIndex(scenario)
        while (phaseIndex < scenario.phases.size) {
            currentCoroutineContext().ensureActive()
            val requestedPhase = scenario.phases[phaseIndex]
            var targetPhase = applyPersistentSafety(requestedPhase)
            val adaptiveCandidate = isAdaptiveBoundaryCandidate(
                scenarioId = scenario.id,
                phaseId = targetPhase.id,
            )
            val transitionOrigin = previousRawRuntime ?: neutralPhaseFor(requestedPhase)
            val initialTransition = LoadTransitionEvaluator.sampleAt(
                spec = requestedPhase.transition,
                elapsedMs = 0L,
                phaseDurationMs = requestedPhase.durationMs,
            )
            val initialRawRuntime = allocationRouteSafePhase(
                initial = LoadTransitionEvaluator.interpolate(
                    previous = transitionOrigin,
                    target = requestedPhase,
                    fraction = initialTransition.fraction,
                ),
                target = requestedPhase,
            )
            var initialRuntime = applyPersistentSafety(initialRawRuntime)
            val previousRendererPhase = progress.phase
            val allocationRouteChanges = rendererAllocationRouteChanges(
                active = previousRendererPhase,
                target = targetPhase,
            )
            // A compression/codec allocation route is a discrete boundary. Detach and confirm
            // the previous producer before touching the vendor route; otherwise an SBWC→RGB or
            // RGB→SBWC transition can reconfigure an allocator under live buffers. Keep the
            // prewarmed DRAM working set pinned because this is a phase boundary, not run cleanup.
            if (allocationRouteChanges) {
                progress = progress.copy(
                    phase = null,
                    targetPhase = null,
                    transitionFraction = 0f,
                    statusText = "Allocation route 전환 전 producer teardown 확인 중",
                )
                val loadsReleased = confirmGeneratedLoadQuiesce()
                val rendererReleased = awaitRendererTeardownBarrier()
                if (!loadsReleased || !rendererReleased) {
                    val reason =
                        "Phase '${targetPhase.id}' allocation route 전환 전 " +
                            "부하/producer 해제를 확인하지 못했습니다."
                    cancellationReason = reason
                    runEvents += event("ROUTE_TRANSITION_BLOCKED", reason)
                    throw PlanAbortException(reason)
                }
                configureCompression(targetPhase)
                runEvents += event(
                    "ROUTE_TRANSITION_READY",
                    "${previousRendererPhase?.pixelRoute?.name ?: "NONE"} → " +
                        targetPhase.pixelRoute.name,
                )
            } else {
                previousRendererPhase?.let {
                    progress = progress.copy(
                        phase = rendererPreparationPhase(it),
                        transitionFraction = 0f,
                        statusText = "이전 producer/cross-load quiesce",
                    )
                }
                if (!confirmGeneratedLoadQuiesce()) {
                    val reason =
                        "Phase '${targetPhase.id}' 시작 전 load/NPU zero 적용을 " +
                            "확인하지 못했습니다."
                    cancellationReason = reason
                    runEvents += event("PHASE_BOUNDARY_QUIESCE_FAILED", reason)
                    throw PlanAbortException(reason)
                }
            }
            requestDisplayModeOrAbort(
                min(60f, targetPhase.requestedDisplayHz),
                "phase '${targetPhase.id}' preparation",
            )
            val adaptiveBoundaryBefore = if (adaptiveCandidate) {
                collectAdaptiveBoundarySample(targetPhase.id, "before")
            } else {
                null
            }
            val adaptiveBoundaryContinuityBefore =
                adaptiveBoundaryBefore?.let { exactCounterContinuous } ?: false
            val producerGeneration = beginTrackedProducerGeneration()
            val preparationPhase = rendererPreparationPhase(initialRuntime)
            val topologyRequestedAtMs = SystemClock.elapsedRealtime()
            val topologyDeadlineMs = saturatingAdd(
                topologyRequestedAtMs,
                PRODUCER_RECOVERY_TIMEOUT_MS,
            )
            progress = RunProgress(
                stage = RunnerStage.RUNNING,
                scenario = scenario,
                phaseIndex = phaseIndex,
                phase = preparationPhase,
                elapsedMs = scenarioElapsed,
                phaseElapsedMs = 0L,
                statusText = "Physical producer topology 준비 중",
                controlLayerIncluded = true,
                targetPhase = targetPhase,
                transitionFraction = 0f,
                thermalDerated = thermalReduced,
                producerGeneration = producerGeneration,
            )

            // Topology publication, a fresh counter sample, and phase activation are deliberately
            // ordered. Neither preparation wall time nor a transient process lease consumes the
            // scenario duration or satisfies the first-buffer guard.
            var phaseBaseline: TelemetrySnapshot? = null
            while (phaseBaseline == null) {
                currentCoroutineContext().ensureActive()
                val nowMs = SystemClock.elapsedRealtime()
                val readiness = frameTracker.producerReadiness(producerGeneration)
                val processLeaseActive = RendererSafetyState.hasUnconfirmedTeardown()
                readiness.runtimeFailureReason?.let { failure ->
                    val reason = "Phase '${targetPhase.id}' producer 실행 실패: $failure"
                    cancellationReason = reason
                    throw PlanAbortException(reason)
                }
                if (readiness.teardownFailed) {
                    val reason =
                        "Phase '${targetPhase.id}' producer recovery deadline을 초과해 " +
                            "후속 queue 실행을 차단합니다."
                    cancellationReason = reason
                    throw PlanAbortException(reason)
                }
                if (
                    readiness.topologyPublished &&
                    !readiness.topologyPending &&
                    readiness.geometryReady &&
                    !processLeaseActive
                ) {
                    val freshBaseline = try {
                        collectTelemetrySample()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        throw InconclusiveRunException(
                            "Phase '${targetPhase.id}' activation 직전 fresh counter sample을 " +
                            "얻지 못했습니다: ${error.javaClass.simpleName}",
                        )
                    }
                    val refreshedTarget = applyPersistentSafety(requestedPhase)
                    val refreshedInitial = applyPersistentSafety(initialRawRuntime)
                    if (
                        refreshedTarget != targetPhase ||
                        refreshedInitial != initialRuntime
                    ) {
                        targetPhase = refreshedTarget
                        initialRuntime = refreshedInitial
                        progress = progress.copy(
                            phase = rendererPreparationPhase(initialRuntime),
                            targetPhase = targetPhase,
                            statusText = "Thermal derate topology 재준비 중",
                            thermalDerated = thermalReduced,
                        )
                        delay(RENDERER_TEARDOWN_POLL_MS)
                        continue
                    }
                    val afterSample = frameTracker.producerReadiness(producerGeneration)
                    val activationNowMs = SystemClock.elapsedRealtime()
                    if (
                        activationNowMs <= topologyDeadlineMs &&
                        afterSample.topologyPublished &&
                        !afterSample.topologyPending &&
                        afterSample.geometryReady &&
                        !RendererSafetyState.hasUnconfirmedTeardown()
                    ) {
                        // Discard only a preparation-era boundary. If a topology-pending callback
                        // races after this clear, the synchronized generation gate makes activation
                        // fail; if it races after activation, the boundary remains for the active
                        // phase recovery path.
                        topologyPendingBoundary.updateAndGet { boundary ->
                            boundary?.takeUnless { it.generation == producerGeneration }
                        }
                        if (frameTracker.activateProducerGeneration(producerGeneration)) {
                            if (
                                targetPhase.layerSizeProfile.changesOverTime &&
                                afterSample.geometryAppliedProfileOrdinal ==
                                LayerSizeProfile.SMALL_UNIFORM.ordinal
                            ) {
                                // Preparation is a post-frame-acknowledged 0.30×0.30 transform,
                                // exactly equal to both dynamic profiles' measured origin.
                                frameTracker.recordEquivalentLayerGeometryCoverage(
                                    generation = producerGeneration,
                                    profileOrdinal = targetPhase.layerSizeProfile.ordinal,
                                    coverageBit =
                                        targetPhase.layerSizeProfile.coverageBitAt(0f),
                                )
                            }
                            phaseBaseline = freshBaseline
                            break
                        }
                    }
                }
                if (nowMs >= topologyDeadlineMs) {
                    frameTracker.markProducerTeardownFailure(producerGeneration)
                    val reason =
                        "Phase '${targetPhase.id}' physical topology publish/recovery가 " +
                            "${PRODUCER_RECOVERY_TIMEOUT_MS}ms를 초과했습니다 " +
                            "(published=${readiness.topologyPublished}, " +
                            "pending=${readiness.topologyPending}, " +
                            "geometry=${readiness.geometryAppliedRevision}/" +
                            "${readiness.geometryRequestedRevision}, " +
                            "processLease=$processLeaseActive)."
                    cancellationReason = reason
                    runEvents += event("PRODUCER_RECOVERY_TIMEOUT", reason)
                    throw PlanAbortException(reason)
                }
                progress = progress.copy(
                    expectedProducerCount = visibleExpectedProducerCount(
                        committedExpectedCount = readiness.expectedCount,
                        topologyPublished = readiness.topologyPublished,
                        topologyPending = readiness.topologyPending,
                        processLeaseActive = processLeaseActive,
                    ),
                    observedProducerCount = readiness.observedCount,
                    statusText = buildString {
                        append("Physical producer topology 준비 중")
                        if (readiness.topologyPending || processLeaseActive) {
                            append(" · 이전 producer drain")
                        }
                        if (!readiness.geometryReady) {
                            append(" · layer geometry commit")
                        }
                    },
                )
                delay(RENDERER_TEARDOWN_POLL_MS)
            }

            val activationBaseline = checkNotNull(phaseBaseline)
            val phaseStarted = SystemClock.elapsedRealtime()
            val phaseClock = ActivePhaseClock(phaseStarted)
            activePhaseClock = phaseClock
            val hwcCompositionCoverage =
                targetPhase.hwcCompositionExpectation
                    .takeUnless { it == HwcCompositionExpectation.NONE }
                    ?.let { expectation ->
                        HwcCompositionCoverageTracker(expectation).also { tracker ->
                            if (
                                !hwcCompositionContractPreserved(
                                    requested = requestedPhase,
                                    effective = targetPhase,
                                )
                            ) {
                                tracker.recordContractFailure(
                                    "typed HWC target was changed by a persistent runtime " +
                                        "safety derate before phase activation",
                                )
                            }
                        }
                    }
            // Attribute exact/proxy deltas only after the requested topology has committed and a
            // fresh serialized sample has completed.
            val exactBefore = activationBaseline.exactUnderruns
            val exactSourceBefore = activationBaseline.exactUnderrunSource
            val exactQualityBefore = activationBaseline.exactUnderrunQuality
            val exactContinuityAtPhaseStart = exactCounterContinuous
            val proxyBefore = activationBaseline.suspectedUnderruns
            runEvents += event(
                "PHASE_START",
                "${targetPhase.id}: ${targetPhase.label}; " +
                    "${targetPhase.activeLayers}L @ ${targetPhase.producerFps}fps; " +
                    "transition=${targetPhase.transition.summary()}; " +
                    "hwcExpectation=${targetPhase.hwcCompositionExpectation.name}",
            )
            var lastRawRuntime = initialRawRuntime
            var firstControlTick = false
            var runtimeTargetPhase = targetPhase
            var targetThermalState = thermalReduced
            var lastAppliedDisplayHz: Float? = initialRuntime.requestedDisplayHz
            var lastAppliedWorkloads: LoadSetpoints? = initialRuntime.workloads
            var postReadyControlTickApplied = false
            var transitionStarted = false
            var positiveNpuAcknowledged = false
            var pendingControlCoverage: PendingControlCoverage? = null
            var hwcCompositionProbeResolved = false
            var forcedHwcCompositionProbeAttempts = 0
            val producerFrameBudget = AppliedProducerFrameBudget(
                phaseStartedMs = phaseStarted,
                phaseDurationMs = targetPhase.durationMs,
            )
            val committedProducerCountAtStart =
                frameTracker.producerReadiness(producerGeneration).expectedCount
                    .coerceIn(1, ScenarioSafetyPolicy.HARD_MAX_LAYERS)
            producerFrameBudget.observePhysicalFrames(
                totalFrames = frameTracker.totalPhysicalProducedFrames(),
                countAsActive = false,
            )
            producerFrameBudget.apply(
                atMonotonicMs = phaseStarted,
                producerFps = initialRuntime.producerFps,
                activeLayers = committedProducerCountAtStart,
            )
            activeProducerFrameBudget = producerFrameBudget
            val transitionCoverage = TransitionCoverageTracker(requestedPhase.transition)
            var nextControlTickAtMs = phaseStarted
            var recoveryPaused = false
            var recoveryObservationRestarted = false
            fun pauseForProducerRecovery(
                boundaryMs: Long,
                nowMs: Long,
                topologyPending: Boolean,
                processLeaseActive: Boolean,
            ) {
                if (recoveryPaused) return
                val boundedBoundaryMs = boundaryMs
                    .coerceAtLeast(phaseStarted)
                    .coerceAtMost(nowMs)
                // The renderer is about to publish a preparation profile. A control sample which
                // was waiting for the pre-recovery topology/profile can therefore never receive a
                // truthful acknowledgment. Drop it so the first post-recovery control tick can
                // republish the same frozen phase time instead of waiting behind an impossible
                // dynamic-profile match until the phase deadline.
                pendingControlCoverage =
                    discardPendingControlCoverageForProducerRecovery(pendingControlCoverage)
                recoveryPaused = true
                producerRecoveryPaused = true
                phaseClock.pause(
                    boundedBoundaryMs,
                    PhasePauseOwner.PRODUCER_RECOVERY,
                )
                producerFrameBudget.pause(
                    boundedBoundaryMs,
                    PhasePauseOwner.PRODUCER_RECOVERY,
                )
                progress.phase?.let { activePhase ->
                    progress = progress.copy(
                        phase = rendererPreparationPhase(activePhase),
                        statusText = "Physical producer 복구 대기 · 전체 부하 일시 해제",
                    )
                }
                loadManager.releaseLoads()
                requestDisplayModeOrAbort(
                    min(60f, targetPhase.requestedDisplayHz),
                    "phase '${targetPhase.id}' producer recovery",
                )
                runEvents += event(
                    "PRODUCER_RECOVERY_WAIT",
                    "phase=${targetPhase.id}; pending=$topologyPending; " +
                        "processLease=$processLeaseActive",
                )
            }
            suspend fun collectTargetHwcCompositionEvidence(
                boundaryMs: Long,
                expectedProducerCount: Int,
                expectedTopologyRevision: Long,
            ): Long? {
                val coverage = hwcCompositionCoverage ?: return boundaryMs
                currentCoroutineContext().ensureActive()
                fun preserveTypedContract(): Boolean {
                    val effectiveTarget = applyPersistentSafety(requestedPhase)
                    val preserved = hwcCompositionContractPreserved(
                        requested = requestedPhase,
                        effective = effectiveTarget,
                    )
                    if (!preserved) {
                        coverage.recordContractFailure(
                            "typed HWC target changed before/during forced observation: " +
                                hwcCompositionContractDeltaSummary(
                                    requested = requestedPhase,
                                    effective = effectiveTarget,
                                ),
                        )
                    }
                    return preserved
                }
                fun targetTopologyStableLocked(): Boolean {
                    val freshReadiness =
                        frameTracker.producerReadiness(producerGeneration)
                    val pendingBoundaryExists =
                        topologyPendingBoundary.get()?.generation == producerGeneration
                    return hwcCompositionTargetReadyForArm(
                        ready =
                            freshReadiness.ready &&
                                freshReadiness.geometryReady &&
                                freshReadiness.geometryAppliedProfileOrdinal ==
                                requestedPhase.layerSizeProfile.ordinal,
                        topologyPublished = freshReadiness.topologyPublished,
                        topologyPending = freshReadiness.topologyPending,
                        processLeaseActive =
                            RendererSafetyState.hasUnconfirmedTeardown(),
                        pendingBoundaryExists = pendingBoundaryExists,
                        topologyMissed = freshReadiness.topologyMissed,
                        teardownFailed = freshReadiness.teardownFailed,
                        teardownCompleted = freshReadiness.teardownCompleted,
                        runtimeFailurePresent =
                            freshReadiness.runtimeFailureReason != null,
                        expectedProducerCount = expectedProducerCount,
                        observedProducerCount = freshReadiness.observedCount,
                        expectedTopologyRevision = expectedTopologyRevision,
                        observedTopologyRevision =
                            freshReadiness.topologyRevision,
                    )
                }
                preserveTypedContract()
                val newlyArmed = synchronized(producerTopologyStateLock) {
                    if (!targetTopologyStableLocked()) {
                        return@synchronized null
                    }
                    val activated = if (coverage.targetActivated()) {
                        false
                    } else {
                        coverage.activateTarget(boundaryMs)
                    }
                    check(
                        hwcCompositionProbePriorityGate.acquire(coverage),
                    ) {
                        "Another typed HWC probe priority owner is still active"
                    }
                    activated
                } ?: return boundaryMs
                if (newlyArmed) {
                    runEvents += event(
                        "HWC_EXPECTATION_ARMED",
                        "phase=${targetPhase.id}; " +
                            "expectation=${targetPhase.hwcCompositionExpectation.name}; " +
                            "targetReadyRunMs=" +
                            "${monotonicTimestampRelativeToRun(
                                boundaryMs,
                                runStartMonotonicMs,
                            ) ?: "N/A"}",
                    )
                }
                fun resolveProbePriority(atMonotonicMs: Long): Long {
                    hwcCompositionProbeResolved = true
                    check(
                        hwcCompositionProbePriorityGate.release(coverage) ||
                            !hwcCompositionProbePriorityGate.isActive(),
                    ) {
                        "Typed HWC probe priority ownership changed before resolution"
                    }
                    return atMonotonicMs
                }
                if (hwcCompositionProbeResolved) {
                    return resolveProbePriority(boundaryMs)
                }
                when (coverage.probeAction(forcedHwcCompositionProbeAttempts)) {
                    HwcCompositionProbeAction.COMPLETE,
                    HwcCompositionProbeAction.EXHAUSTED,
                    -> {
                        return resolveProbePriority(boundaryMs)
                    }
                    HwcCompositionProbeAction.FORCE_SAMPLE -> Unit
                }
                if (!telemetrySampleMutex.tryLock()) return null
                try {
                    // Keep one serialized ownership across the bounded forced batch. A periodic
                    // waiter therefore cannot run between CLIENT sample 1 and sample 2. The target
                    // renderer/load/display stay active, so probe time remains in duration and
                    // producer-fidelity accounting.
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        preserveTypedContract()
                        val topologyStableBeforeProbe =
                            synchronized(producerTopologyStateLock) {
                                targetTopologyStableLocked()
                            }
                        if (!topologyStableBeforeProbe) {
                            coverage.recordProbeFailure(
                                "target producer topology/fresh heartbeat changed before forced " +
                                    "HWC composition observation",
                            )
                        }
                        when (coverage.probeAction(forcedHwcCompositionProbeAttempts)) {
                            HwcCompositionProbeAction.COMPLETE,
                            HwcCompositionProbeAction.EXHAUSTED,
                            -> return resolveProbePriority(SystemClock.elapsedRealtime())
                            HwcCompositionProbeAction.FORCE_SAMPLE -> Unit
                        }

                        // Starting only with a complete bounded sample window left prevents a slow
                        // SurfaceFlinger/Binder probe from silently extending the stress phase.
                        val probeStartedAtMs = SystemClock.elapsedRealtime()
                        val remainingActiveMs =
                            (targetPhase.durationMs - phaseClock.elapsedMs(probeStartedAtMs))
                                .coerceAtLeast(0L)
                        if (
                            !hwcCompositionProbeCanStart(
                                remainingActiveMs = remainingActiveMs,
                                sampleTimeoutMs = SYSTEM_MONITOR_SAMPLE_TIMEOUT_MS,
                                completionReserveMs = PROGRESS_INTERVAL_MS,
                            )
                        ) {
                            coverage.recordProbeFailure(
                                "insufficient active phase window for bounded HWC composition " +
                                    "probe; remainingMs=$remainingActiveMs",
                            )
                            return resolveProbePriority(probeStartedAtMs)
                        }
                        progress = progress.copy(
                            statusText =
                                "${targetPhase.label} · fresh HWC composition 확인 " +
                                    "${forcedHwcCompositionProbeAttempts + 1}/" +
                                    "${requiredHwcMatchingEvidenceCount(
                                        targetPhase.hwcCompositionExpectation,
                                    )}",
                        )
                        forcedHwcCompositionProbeAttempts++
                        runEvents += event(
                            "HWC_EXPECTATION_PROBE",
                            "phase=${targetPhase.id}; attempt=" +
                                "$forcedHwcCompositionProbeAttempts/" +
                                "${requiredHwcMatchingEvidenceCount(
                                    targetPhase.hwcCompositionExpectation,
                                )}",
                        )
                        try {
                            val probeBudgetMs = remainingActiveMs - PROGRESS_INTERVAL_MS
                            val completedWithinPhase = withTimeoutOrNull(probeBudgetMs) {
                                collectTelemetrySampleLocked(forceCompositionProbe = true)
                                true
                            } == true
                            if (!completedWithinPhase) {
                                coverage.recordProbeFailure(
                                    "forced HWC composition sample exceeded the active phase " +
                                        "deadline",
                                )
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            coverage.recordProbeFailure(
                                "forced HWC composition sample failed: " +
                                    error.javaClass.simpleName,
                            )
                        }
                        currentCoroutineContext().ensureActive()
                        preserveTypedContract()
                        val topologyStableAfterProbe =
                            synchronized(producerTopologyStateLock) {
                                targetTopologyStableLocked()
                            }
                        if (!topologyStableAfterProbe) {
                            coverage.recordProbeFailure(
                                "target producer topology/fresh heartbeat changed during forced " +
                                    "HWC composition observation",
                            )
                        }
                        when (coverage.probeAction(forcedHwcCompositionProbeAttempts)) {
                            HwcCompositionProbeAction.FORCE_SAMPLE -> continue
                            HwcCompositionProbeAction.COMPLETE,
                            HwcCompositionProbeAction.EXHAUSTED,
                            -> return resolveProbePriority(SystemClock.elapsedRealtime())
                        }
                    }
                } finally {
                    telemetrySampleMutex.unlock()
                }
            }
            check(activeHwcCompositionCoverage.compareAndSet(null, hwcCompositionCoverage)) {
                "Previous HWC composition coverage owner was not released"
            }
            try {
                val pendingBoundaryAtActivation =
                    topologyPendingBoundary.updateAndGet { boundary ->
                        boundary?.takeIf { it.generation == producerGeneration }
                    }
                if (pendingBoundaryAtActivation != null) {
                    producerFrameBudget.pauseAtPhysicalBoundary(
                        atMonotonicMs = pendingBoundaryAtActivation.monotonicMs,
                        totalFrames = frameTracker.totalPhysicalProducedFrames(),
                        owner = PhasePauseOwner.PRODUCER_RECOVERY,
                    )
                    phaseClock.pause(
                        atMonotonicMs = pendingBoundaryAtActivation.monotonicMs,
                        owner = PhasePauseOwner.PRODUCER_RECOVERY,
                    )
                    producerRecoveryPaused = true
                    loadManager.releaseLoads()
                    lastAppliedWorkloads = null
                    requestDisplayModeOrAbort(
                        min(60f, initialRuntime.requestedDisplayHz),
                        "phase '${targetPhase.id}' pending topology",
                    )
                    lastAppliedDisplayHz = null
                } else {
                    val initialNpuHealth = applyRuntimeWorkloads(
                        workloads = initialRuntime.workloads,
                        restartProfile = true,
                        requirePositiveNpuAcknowledgment =
                            initialRuntime.workloads.npu > MIN_EFFECTIVE_LOAD,
                    )
                    throwForNpuControlFailure(
                        phaseId = targetPhase.id,
                        operation = "initial apply",
                        health = initialNpuHealth,
                        positiveAcknowledgmentRequired =
                            initialRuntime.workloads.npu > MIN_EFFECTIVE_LOAD,
                    )
                    positiveNpuAcknowledged =
                        initialRuntime.workloads.npu > MIN_EFFECTIVE_LOAD
                    requestDisplayModeOrAbort(
                        initialRuntime.requestedDisplayHz,
                        "phase '${targetPhase.id}' initial target",
                    )
                }
                while (true) {
                currentCoroutineContext().ensureActive()
                if (abortForLocalWorkerFailure()) {
                    currentCoroutineContext().ensureActive()
                }
                if (runtimeControlPaused) {
                    producerFrameBudget.observePhysicalFrames(
                        totalFrames = frameTracker.totalPhysicalProducedFrames(),
                        countAsActive = false,
                    )
                    progress = progress.copy(
                        phase = progress.phase?.let(::rendererPreparationPhase),
                        statusText = "Safety control 적용 확인 중 · 전체 부하 일시 해제",
                    )
                    delay(RENDERER_TEARDOWN_POLL_MS)
                    continue
                }
                val nowMs = SystemClock.elapsedRealtime()
                val producerReadiness = frameTracker.producerReadiness(producerGeneration)
                producerReadiness.runtimeFailureReason?.let { failure ->
                    val reason = "Phase '${targetPhase.id}' producer 실행 실패: $failure"
                    cancellationReason = reason
                    throw PlanAbortException(reason)
                }
                if (producerReadiness.teardownFailed) {
                    val reason =
                        "Phase '${targetPhase.id}' producer teardown deadline을 초과해 " +
                            "후속 queue 실행을 차단합니다."
                    cancellationReason = reason
                    throw PlanAbortException(reason)
                }
                val processLeaseActive = RendererSafetyState.hasUnconfirmedTeardown()
                val producerRecoveryActive =
                    producerReadiness.topologyPending || processLeaseActive
                val pendingBoundaryMs = topologyPendingBoundary.get()
                    ?.takeIf { it.generation == producerGeneration }
                    ?.monotonicMs
                    ?.coerceAtMost(nowMs)
                if (!recoveryPaused && pendingBoundaryMs != null) {
                    pauseForProducerRecovery(
                        boundaryMs = pendingBoundaryMs,
                        nowMs = nowMs,
                        topologyPending = producerReadiness.topologyPending,
                        processLeaseActive = processLeaseActive,
                    )
                }
                if (producerRecoveryActive) {
                    recoveryObservationRestarted = false
                    producerFrameBudget.observePhysicalFrames(
                        totalFrames = frameTracker.totalPhysicalProducedFrames(),
                        // Frames up to the first observation of recovery belong to the same
                        // active interval that the expected-frame clock accounts through now.
                        countAsActive = !recoveryPaused,
                    )
                    if (!recoveryPaused) {
                        pauseForProducerRecovery(
                            boundaryMs = nowMs,
                            nowMs = nowMs,
                            topologyPending = producerReadiness.topologyPending,
                            processLeaseActive = processLeaseActive,
                        )
                    }
                    val currentPauseMs = phaseClock.currentPauseMs(
                        nowMs,
                        PhasePauseOwner.PRODUCER_RECOVERY,
                    )
                    if (
                        producerRecoveryDeadlineExceeded(
                            recoveryStillActive = true,
                            currentPauseMs = currentPauseMs,
                            timeoutMs = PRODUCER_RECOVERY_TIMEOUT_MS,
                        )
                    ) {
                        frameTracker.markProducerTeardownFailure(producerGeneration)
                        val reason =
                            "Phase '${targetPhase.id}' producer recovery 연속 대기가 " +
                                "${PRODUCER_RECOVERY_TIMEOUT_MS}ms를 초과했습니다."
                        cancellationReason = reason
                        runEvents += event("PRODUCER_RECOVERY_TIMEOUT", reason)
                        throw PlanAbortException(reason)
                    }
                    val frozenElapsed = phaseClock.elapsedMs(nowMs)
                    progress = progress.copy(
                        elapsedMs = scenarioElapsed + frozenElapsed,
                        phaseElapsedMs = frozenElapsed,
                        statusText = "Physical producer 복구 대기 · 부하 일시 해제",
                        expectedProducerCount = visibleExpectedProducerCount(
                            committedExpectedCount = producerReadiness.expectedCount,
                            topologyPublished = producerReadiness.topologyPublished,
                            topologyPending = producerReadiness.topologyPending,
                            processLeaseActive = processLeaseActive,
                        ),
                        observedProducerCount = producerReadiness.observedCount,
                    )
                    planProgress = planProgress.copy(
                        currentRunFraction = progress.overallFraction,
                    )
                    delay(RENDERER_TEARDOWN_POLL_MS)
                    continue
                }
                if (recoveryPaused) {
                    producerFrameBudget.observePhysicalFrames(
                        totalFrames = frameTracker.totalPhysicalProducedFrames(),
                        countAsActive = false,
                    )
                    if (
                        producerRecoveryDeadlineExceeded(
                            recoveryStillActive = false,
                            currentPauseMs = phaseClock.currentPauseMs(
                                nowMs,
                                PhasePauseOwner.PRODUCER_RECOVERY,
                            ),
                            timeoutMs = PRODUCER_RECOVERY_TIMEOUT_MS,
                        )
                    ) {
                        frameTracker.markProducerTeardownFailure(producerGeneration)
                        val reason =
                            "Phase '${targetPhase.id}' producer recovery가 bounded deadline 뒤에 " +
                                "완료됐습니다."
                        cancellationReason = reason
                        runEvents += event("PRODUCER_RECOVERY_TIMEOUT", reason)
                        throw PlanAbortException(reason)
                    }
                    if (!recoveryObservationRestarted) {
                        if (!confirmGeneratedLoadQuiesce()) {
                            val reason =
                                "Phase '${targetPhase.id}' producer 복구 후 load/NPU zero 적용을 " +
                                    "확인하지 못했습니다."
                            cancellationReason = reason
                            runEvents += event("PRODUCER_RECOVERY_SAFEPOINT_FAILED", reason)
                            throw PlanAbortException(reason)
                        }
                        val restartNowMs = SystemClock.elapsedRealtime()
                        val restartReadiness =
                            frameTracker.producerReadiness(producerGeneration)
                        restartReadiness.runtimeFailureReason?.let { failure ->
                            val reason =
                                "Phase '${targetPhase.id}' producer 실행 실패: $failure"
                            cancellationReason = reason
                            throw PlanAbortException(reason)
                        }
                        val restartLeaseActive =
                            RendererSafetyState.hasUnconfirmedTeardown()
                        if (restartReadiness.topologyPending || restartLeaseActive) {
                            delay(RENDERER_TEARDOWN_POLL_MS)
                            continue
                        }
                        if (!restartReadiness.geometryReady) {
                            delay(RENDERER_TEARDOWN_POLL_MS)
                            continue
                        }
                        if (
                            producerRecoveryDeadlineExceeded(
                                recoveryStillActive = false,
                                currentPauseMs = phaseClock.currentPauseMs(
                                    restartNowMs,
                                    PhasePauseOwner.PRODUCER_RECOVERY,
                                ),
                                timeoutMs = PRODUCER_RECOVERY_TIMEOUT_MS,
                            )
                        ) {
                            frameTracker.markProducerTeardownFailure(producerGeneration)
                            val reason =
                                "Phase '${targetPhase.id}' producer recovery가 load quiesce " +
                                    "확인 중 bounded deadline을 초과했습니다."
                            cancellationReason = reason
                            runEvents += event("PRODUCER_RECOVERY_TIMEOUT", reason)
                            throw PlanAbortException(reason)
                        }
                        topologyPendingBoundary.updateAndGet { boundary ->
                            boundary?.takeUnless { it.generation == producerGeneration }
                        }
                        if (!frameTracker.restartProducerObservation(producerGeneration)) {
                            val racedReadiness =
                                frameTracker.producerReadiness(producerGeneration)
                            if (
                                racedReadiness.topologyPending ||
                                RendererSafetyState.hasUnconfirmedTeardown()
                            ) {
                                delay(RENDERER_TEARDOWN_POLL_MS)
                                continue
                            }
                            val reason =
                                "Phase '${targetPhase.id}' producer topology가 복구 후 " +
                                    "유효하지 않습니다."
                            cancellationReason = reason
                            throw PlanAbortException(reason)
                        }
                        // Exclude every buffer posted during ordered-zero confirmation and reset
                        // the actual-frame baseline before waiting for fresh post-recovery output.
                        producerFrameBudget.observePhysicalFrames(
                            totalFrames = frameTracker.totalPhysicalProducedFrames(),
                            countAsActive = false,
                        )
                        recoveryObservationRestarted = true
                        progress = progress.copy(
                            statusText = "Physical producer fresh buffer 확인 중 · 부하 0",
                        )
                        delay(RENDERER_TEARDOWN_POLL_MS)
                        continue
                    }

                    val resumeNowMs = SystemClock.elapsedRealtime()
                    val resumeReadiness = frameTracker.producerReadiness(producerGeneration)
                    resumeReadiness.runtimeFailureReason?.let { failure ->
                        val reason = "Phase '${targetPhase.id}' producer 실행 실패: $failure"
                        cancellationReason = reason
                        throw PlanAbortException(reason)
                    }
                    val resumeLeaseActive = RendererSafetyState.hasUnconfirmedTeardown()
                    if (resumeReadiness.topologyPending || resumeLeaseActive) {
                        if (
                            producerRecoveryDeadlineExceeded(
                                recoveryStillActive = true,
                                currentPauseMs = phaseClock.currentPauseMs(
                                    resumeNowMs,
                                    PhasePauseOwner.PRODUCER_RECOVERY,
                                ),
                                timeoutMs = PRODUCER_RECOVERY_TIMEOUT_MS,
                            )
                        ) {
                            frameTracker.markProducerTeardownFailure(producerGeneration)
                            val reason =
                                "Phase '${targetPhase.id}' producer recovery 연속 대기가 " +
                                    "${PRODUCER_RECOVERY_TIMEOUT_MS}ms를 초과했습니다."
                            cancellationReason = reason
                            runEvents += event("PRODUCER_RECOVERY_TIMEOUT", reason)
                            throw PlanAbortException(reason)
                        }
                        delay(RENDERER_TEARDOWN_POLL_MS)
                        continue
                    }
                    if (
                        producerRecoveryDeadlineExceeded(
                            recoveryStillActive = !resumeReadiness.ready,
                            currentPauseMs = phaseClock.currentPauseMs(
                                resumeNowMs,
                                PhasePauseOwner.PRODUCER_RECOVERY,
                            ),
                            timeoutMs = PRODUCER_RECOVERY_TIMEOUT_MS,
                        )
                    ) {
                        frameTracker.markProducerTeardownFailure(producerGeneration)
                        val reason =
                            "Phase '${targetPhase.id}' producer fresh-buffer recovery가 " +
                                "${PRODUCER_RECOVERY_TIMEOUT_MS}ms를 초과했습니다."
                        cancellationReason = reason
                        runEvents += event("PRODUCER_RECOVERY_TIMEOUT", reason)
                        throw PlanAbortException(reason)
                    }
                    if (!resumeReadiness.ready) {
                        progress = progress.copy(
                            statusText =
                                "Physical producer fresh buffer 확인 중 · " +
                                    "${resumeReadiness.everObservedCount}/" +
                                    "${resumeReadiness.expectedCount}",
                            expectedProducerCount = visibleExpectedProducerCount(
                                committedExpectedCount = resumeReadiness.expectedCount,
                                topologyPublished = resumeReadiness.topologyPublished,
                                topologyPending = resumeReadiness.topologyPending,
                                processLeaseActive = false,
                            ),
                            observedProducerCount = resumeReadiness.observedCount,
                        )
                        delay(RENDERER_TEARDOWN_POLL_MS)
                        continue
                    }
                    producerFrameBudget.observePhysicalFrames(
                        totalFrames = frameTracker.totalPhysicalProducedFrames(),
                        countAsActive = false,
                    )
                    phaseClock.resume(
                        resumeNowMs,
                        PhasePauseOwner.PRODUCER_RECOVERY,
                    )
                    producerFrameBudget.resume(
                        resumeNowMs,
                        PhasePauseOwner.PRODUCER_RECOVERY,
                    )
                    nextControlTickAtMs = resumeNowMs
                    producerRecoveryPaused = false
                    recoveryPaused = false
                    recoveryObservationRestarted = false
                    firstControlTick = true
                    positiveNpuAcknowledged = false
                    lastAppliedDisplayHz = null
                    lastAppliedWorkloads = null
                    runEvents += event(
                        "PRODUCER_RECOVERY_RESUMED",
                        "phase=${targetPhase.id}; pausedMs=" +
                            "${phaseClock.totalPausedMs(resumeNowMs)}; loadQuiesce=confirmed",
                    )
                    continue
                }
                producerFrameBudget.observePhysicalFrames(
                    totalFrames = frameTracker.totalPhysicalProducedFrames(),
                    countAsActive = true,
                )
                val phaseElapsed = phaseClock.elapsedMs(nowMs)
                var compositionVerificationBoundaryHandled = false
                pendingControlCoverage?.let { pending ->
                    if (pending.isAcknowledgedBy(producerReadiness)) {
                        transitionCoverage.observe(
                            pending.sample,
                            pending.phaseElapsedMs,
                        )
                        pendingControlCoverage = null
                        postReadyControlTickApplied = true
                        if (
                            pending.sample.fraction >= 1f &&
                            hwcCompositionCoverage != null &&
                            !hwcCompositionProbeResolved
                        ) {
                            collectTargetHwcCompositionEvidence(
                                boundaryMs = SystemClock.elapsedRealtime(),
                                expectedProducerCount =
                                    pending.expectedProducerCount,
                                expectedTopologyRevision =
                                    pending.expectedTopologyRevision,
                            )?.let { completedAtMs ->
                                nextControlTickAtMs = completedAtMs
                                compositionVerificationBoundaryHandled = true
                            }
                        }
                    }
                }
                if (compositionVerificationBoundaryHandled) {
                    // The bounded probe can overlap a renderer callback or safety transaction.
                    // Refresh every readiness/control input before publishing another setpoint.
                    continue
                }
                if (producerReadiness.topologyMissed) {
                    throw InconclusiveRunException(
                        "Phase '${targetPhase.id}'의 peak physical layer topology가 " +
                            "buffer 게시 전에 축소됐습니다.",
                    )
                }
                if (
                    producerStartupTimedOut(
                        phaseDurationMs = targetPhase.durationMs,
                        elapsedMs = producerReadiness.unreadyForMs,
                        hasProducedFrame = producerReadiness.ready,
                        graceMs = PRODUCER_STARTUP_GRACE_MS,
                    )
                ) {
                    throw InconclusiveRunException(
                        "Phase '${targetPhase.id}' physical producer " +
                            "${producerReadiness.observedCount}/${producerReadiness.expectedCount}개만 " +
                            "${PRODUCER_STARTUP_GRACE_MS}ms 내 실제 buffer를 게시했습니다.",
                    )
                }
                if (
                    producerReadiness.ready &&
                    producerReadiness.oldestFrameAgeMs >= PRODUCER_STARTUP_GRACE_MS
                ) {
                    val reason =
                        "Phase '${targetPhase.id}' physical producer heartbeat가 " +
                            "${producerReadiness.oldestFrameAgeMs}ms 동안 멈춰 plan을 중단합니다."
                    cancellationReason = reason
                    throw PlanAbortException(reason)
                }
                if (pendingControlCoverage != null) {
                    if (phaseElapsed >= targetPhase.durationMs) {
                        throw InconclusiveRunException(
                            "Phase '${targetPhase.id}' control tick의 physical producer " +
                                "topology/buffer 적용이 phase deadline 안에 확인되지 않았습니다.",
                        )
                    }
                    delay(RENDERER_TEARDOWN_POLL_MS)
                    continue
                }
                if (phaseElapsed >= targetPhase.durationMs) {
                    if (!producerReadiness.ready) {
                        throw InconclusiveRunException(
                            "Phase '${targetPhase.id}' 종료 시 physical producer " +
                                "${producerReadiness.observedCount}/" +
                                "${producerReadiness.expectedCount}개만 확인됐습니다.",
                        )
                    }
                    if (!postReadyControlTickApplied) {
                        throw InconclusiveRunException(
                            "Phase '${targetPhase.id}' producer 준비 뒤 active control tick을 " +
                            "적용할 시간이 없었습니다.",
                        )
                    }
                    break
                }
                val transitionSample = LoadTransitionEvaluator.sampleAt(
                    spec = requestedPhase.transition,
                    // Do not replace the origin topology before it has posted at least one
                    // generation-scoped buffer. This removes the 1 ms STEP scheduling race that
                    // could otherwise mark the safely prepared origin as a missed topology.
                    elapsedMs = if (transitionStarted || producerReadiness.ready) {
                        phaseElapsed
                    } else {
                        0L
                    },
                    phaseDurationMs = requestedPhase.durationMs,
                )
                val interpolatedRuntimePhase = LoadTransitionEvaluator.interpolate(
                    previous = transitionOrigin,
                    target = requestedPhase,
                    fraction = transitionSample.fraction,
                )
                val rawRuntimePhase = allocationRouteSafePhase(
                    initial = layerSizeProfileForActiveTransition(
                        interpolated = interpolatedRuntimePhase,
                        target = requestedPhase,
                        transitionStarted =
                            transitionStarted || producerReadiness.ready,
                    ),
                    target = requestedPhase,
                )
                val runtimePhase = applyPersistentSafety(rawRuntimePhase)
                if (targetThermalState != thermalReduced) {
                    runtimeTargetPhase = applyPersistentSafety(requestedPhase)
                    targetThermalState = thermalReduced
                }
                lastRawRuntime = rawRuntimePhase
                producerFrameBudget.apply(
                    atMonotonicMs = nowMs,
                    producerFps = runtimePhase.producerFps,
                    // Charge only producers in the topology acknowledged by LayerStageView.
                    // Requested topology changes are not counted until the callback commits them.
                    activeLayers = producerReadiness.expectedCount,
                )
                if (
                    firstControlTick ||
                    lastAppliedDisplayHz == null ||
                    abs(runtimePhase.requestedDisplayHz - lastAppliedDisplayHz) >=
                    DISPLAY_REQUEST_EPSILON_HZ
                ) {
                    requestDisplayModeOrAbort(
                        runtimePhase.requestedDisplayHz,
                        "phase '${targetPhase.id}' runtime target",
                    )
                    lastAppliedDisplayHz = runtimePhase.requestedDisplayHz
                }
                if (firstControlTick || runtimePhase.workloads != lastAppliedWorkloads) {
                    val positiveAcknowledgmentRequired =
                        runtimePhase.workloads.npu > MIN_EFFECTIVE_LOAD &&
                            !positiveNpuAcknowledged
                    val npuHealth = applyRuntimeWorkloads(
                        workloads = runtimePhase.workloads,
                        restartProfile = firstControlTick,
                        requirePositiveNpuAcknowledgment =
                            positiveAcknowledgmentRequired,
                    )
                    throwForNpuControlFailure(
                        phaseId = targetPhase.id,
                        operation = "runtime apply",
                        health = npuHealth,
                        positiveAcknowledgmentRequired =
                            positiveAcknowledgmentRequired,
                    )
                    currentCoroutineContext().ensureActive()
                    if (runtimeControlPaused) {
                        // A thermal transaction won while the bounded NPU acknowledgment
                        // suspended this tick. Never republish the stale pre-derate phase.
                        continue
                    }
                    if (positiveAcknowledgmentRequired) positiveNpuAcknowledged = true
                    lastAppliedWorkloads = runtimePhase.workloads
                } else if (runtimePhase.workloads.npu > MIN_EFFECTIVE_LOAD) {
                    throwForNpuControlFailure(
                        phaseId = targetPhase.id,
                        operation = "runtime health",
                        health = loadManager.npuControlHealth(),
                        positiveAcknowledgmentRequired = false,
                    )
                }
                if (producerReadiness.ready) {
                    transitionStarted = true
                }
                firstControlTick = false
                val priorPublishedPhase = progress.phase
                progress = RunProgress(
                    stage = RunnerStage.RUNNING,
                    scenario = scenario,
                    phaseIndex = phaseIndex,
                    phase = runtimePhase,
                    elapsedMs = scenarioElapsed + phaseElapsed,
                    phaseElapsedMs = phaseElapsed,
                    statusText = buildString {
                        append(runtimePhase.label)
                        append(" · ")
                        append(transitionSample.segment.label)
                        if (thermalReduced) append(" · thermal derate")
                    },
                    controlLayerIncluded = true,
                    targetPhase = runtimeTargetPhase,
                    transitionFraction = transitionSample.fraction,
                    thermalDerated = thermalReduced,
                    producerGeneration = producerGeneration,
                    expectedProducerCount = visibleExpectedProducerCount(
                        committedExpectedCount = producerReadiness.expectedCount,
                        topologyPublished = producerReadiness.topologyPublished,
                        topologyPending = producerReadiness.topologyPending,
                        processLeaseActive = false,
                    ),
                    observedProducerCount = producerReadiness.observedCount,
                )
                pendingControlCoverage = PendingControlCoverage(
                    sample = transitionSample,
                    phaseElapsedMs = phaseElapsed,
                    expectedProducerCount = if (
                        runtimePhase.backend == LayerBackend.FLATTENED_TEXTURE
                    ) {
                        1
                    } else {
                        runtimePhase.activeLayers.coerceIn(
                            1,
                            ScenarioSafetyPolicy.HARD_MAX_LAYERS,
                        )
                    },
                    expectedTopologyRevision =
                        if (rendererTopologyChanged(priorPublishedPhase, runtimePhase)) {
                            if (producerReadiness.topologyRevision == Long.MAX_VALUE) {
                                1L
                            } else {
                                producerReadiness.topologyRevision + 1L
                            }
                        } else {
                            producerReadiness.topologyRevision
                        },
                    expectedLayerSizeProfileOrdinal = runtimePhase.layerSizeProfile.ordinal,
                )
                planProgress = planProgress.copy(
                    currentRunFraction = progress.overallFraction,
                )
                nextControlTickAtMs = saturatingAdd(
                    nextControlTickAtMs,
                    PROGRESS_INTERVAL_MS,
                )
                val afterControlMs = SystemClock.elapsedRealtime()
                val untilNextTickMs = nextControlTickAtMs - afterControlMs
                if (untilNextTickMs > 0L) {
                    delay(untilNextTickMs)
                } else {
                    // Do not busy-loop to "catch up": transition coverage below makes a skipped
                    // semantic level inconclusive, while the next period starts from real time.
                    nextControlTickAtMs = saturatingAdd(
                        afterControlMs,
                        PROGRESS_INTERVAL_MS,
                    )
                    delay(PROGRESS_INTERVAL_MS)
                }
                }
            } finally {
                producerRecoveryPaused = false
                if (activePhaseClock === phaseClock) {
                    activePhaseClock = null
                }
                hwcCompositionCoverage?.let { coverage ->
                    hwcCompositionProbePriorityGate.release(coverage)
                }
                activeHwcCompositionCoverage.compareAndSet(
                    hwcCompositionCoverage,
                    null,
                )
            }
            hwcCompositionCoverage?.result()?.let { coverage ->
                val chain = advanceHwcCompositionChain(
                    prior = hwcCompositionChainAnchor,
                    coverage = coverage,
                    requestedActiveLayers = requestedPhase.activeLayers,
                )
                hwcCompositionChainAnchor = chain.nextAnchor
                val detail =
                    "phase=${targetPhase.id}; " +
                        "${coverage.eventSummary(runStartMonotonicMs)}; " +
                        chain.eventSummary(runStartMonotonicMs)
                val combinedFailure = listOfNotNull(
                    coverage.failureReason,
                    chain.failureReason,
                ).joinToString("; ").takeIf { it.isNotEmpty() }
                if (combinedFailure == null) {
                    runEvents += event("HWC_EXPECTATION_VERIFIED", detail)
                } else {
                    val reason =
                        "Phase '${targetPhase.id}' HWC composition expectation " +
                            "미충족: $combinedFailure"
                    if (hwcCompositionCoverageFailureReason == null) {
                        hwcCompositionCoverageFailureReason =
                            reason.take(MAX_EVENT_MESSAGE_CHARS)
                    }
                    runEvents += event(
                        "HWC_EXPECTATION_FAILED",
                        "$detail; reason=$combinedFailure",
                    )
                }
            }
            val phaseControlCompletedAtMs = SystemClock.elapsedRealtime()
            producerFrameBudget.observePhysicalFrames(
                totalFrames = frameTracker.totalPhysicalProducedFrames(),
                countAsActive = true,
            )
            phaseClock.pause(
                phaseControlCompletedAtMs,
                PhasePauseOwner.PHASE_COMPLETE,
            )
            producerFrameBudget.pause(
                phaseControlCompletedAtMs,
                PhasePauseOwner.PHASE_COMPLETE,
            )
            val phaseEndWorkloads = if (thermalReduced) {
                applyPersistentSafety(lastRawRuntime).workloads
            } else {
                lastAppliedWorkloads
            }
            phaseEndWorkloads
                ?.takeIf { it.npu > MIN_EFFECTIVE_LOAD }
                ?.let { finalWorkloads ->
                    val finalNpuHealth = applyRuntimeWorkloads(
                        workloads = finalWorkloads,
                        restartProfile = false,
                        requirePositiveNpuAcknowledgment = true,
                    )
                    throwForNpuControlFailure(
                        phaseId = targetPhase.id,
                        operation = "phase-end confirmation",
                        health = finalNpuHealth,
                        positiveAcknowledgmentRequired = true,
                    )
                }
            transitionCoverage.failureReason()?.let { reason ->
                throw InconclusiveRunException(
                    "Phase '${targetPhase.id}' transition 의미를 보존하지 못했습니다: $reason",
                )
            }
            val geometryReadiness = awaitLayerGeometryCoverage(
                generation = producerGeneration,
                profile = targetPhase.layerSizeProfile,
            )
            val requiredGeometryCoverage =
                targetPhase.layerSizeProfile.requiredCoverageMask()
            val geometryDetail =
                "phase=${targetPhase.id}; profile=${targetPhase.layerSizeProfile.name}; " +
                    "revision=${geometryReadiness.geometryAppliedRevision}/" +
                    "${geometryReadiness.geometryRequestedRevision}; " +
                    "coverage=0x${geometryReadiness.geometryCoverageMask.toString(16)}; " +
                    "required=0x${requiredGeometryCoverage.toString(16)}"
            if (
                !layerGeometryCoverageSatisfied(
                    readiness = geometryReadiness,
                    profile = targetPhase.layerSizeProfile,
                )
            ) {
                runEvents += event("LAYER_SIZE_COVERAGE_MISSING", geometryDetail)
                throw InconclusiveRunException(
                    "Phase '${targetPhase.id}' layer-size geometry 적용 coverage가 " +
                        "불완전합니다: $geometryDetail",
                )
            }
            runEvents += event("LAYER_SIZE_COVERAGE", geometryDetail)
            val phaseFinishedAtMs = SystemClock.elapsedRealtime()
            producerFrameBudget.observePhysicalFrames(
                totalFrames = frameTracker.totalPhysicalProducedFrames(),
                countAsActive = false,
            )
            val appliedProducerFrames =
                producerFrameBudget.finish(atMonotonicMs = phaseFinishedAtMs)
            val appliedAggregateProducerFrames =
                producerFrameBudget.expectedAggregateFrames()
            val actualAggregateProducerFrames =
                producerFrameBudget.actualAggregateFrames()
            val producerRate = assessProducerRate(
                actualFrames = actualAggregateProducerFrames,
                expectedFrames = appliedAggregateProducerFrames,
            )
            if (producerRate.materialShortfall) {
                val reason =
                    "Phase '${targetPhase.id}' physical producer rate 부족: " +
                        "actual=${actualAggregateProducerFrames ?: "N/A"}, " +
                        "expected=${"%.1f".format(appliedAggregateProducerFrames)}, " +
                        "ratio=${producerRate.ratio?.let { "%.1f%%".format(it * 100.0) } ?: "N/A"}"
                if (producerRateShortfallReason == null) {
                    producerRateShortfallReason = reason.take(MAX_EVENT_MESSAGE_CHARS)
                }
                runEvents += event("PRODUCER_RATE_SHORTFALL", reason)
            }
            if (activeProducerFrameBudget === producerFrameBudget) {
                activeProducerFrameBudget = null
            }
            val adaptiveBoundaryAfter = if (adaptiveCandidate) {
                collectAdaptiveBoundarySample(targetPhase.id, "after")
            } else {
                null
            }
            val adaptiveBoundaryContinuityAfter =
                adaptiveBoundaryAfter?.let { exactCounterContinuous } ?: false
            previousRawRuntime = if (
                requestedPhase.transition.mode == TransitionMode.SOAK_RECOVERY
            ) {
                val terminalSample = LoadTransitionEvaluator.sampleAt(
                    spec = requestedPhase.transition,
                    elapsedMs = requestedPhase.durationMs,
                    phaseDurationMs = requestedPhase.durationMs,
                )
                allocationRouteSafePhase(
                    initial = layerSizeProfileForActiveTransition(
                        interpolated = LoadTransitionEvaluator.interpolate(
                            previous = transitionOrigin,
                            target = requestedPhase,
                            fraction = terminalSample.fraction,
                        ),
                        target = requestedPhase,
                        transitionStarted = true,
                    ),
                    target = requestedPhase,
                )
            } else {
                lastRawRuntime
            }
            scenarioElapsed = saturatingAdd(scenarioElapsed, targetPhase.durationMs)
            runEvents += event(
                "PHASE_END",
                "${targetPhase.id}; lastLayers=${progress.phase?.activeLayers ?: "N/A"}; " +
                    "factor=${"%.3f".format(progress.boundedTransitionFraction)}; " +
                    "appliedProducerFrames=${"%.1f".format(appliedProducerFrames)}; " +
                    "aggregateFrames=${actualAggregateProducerFrames ?: "N/A"}/" +
                    "${"%.1f".format(appliedAggregateProducerFrames)}",
            )
            val exactDelta = if (
                exactContinuityAtPhaseStart &&
                exactCounterContinuous &&
                exactQualityBefore in EXACT_COUNTER_QUALITIES &&
                telemetry.exactUnderrunQuality == exactQualityBefore
            ) {
                monotonicCounterDelta(
                    baselineValue = exactBefore,
                    baselineSource = exactSourceBefore,
                    currentValue = telemetry.exactUnderruns,
                    currentSource = telemetry.exactUnderrunSource,
                )
            } else {
                null
            }
            val phaseProxyDelta = monotonicCounterDelta(
                baselineValue = proxyBefore,
                baselineSource = PROXY_COUNTER_SOURCE,
                currentValue = telemetry.suspectedUnderruns,
                currentSource = PROXY_COUNTER_SOURCE,
            ) ?: 0L
            val boundaryEvidence = if (
                adaptiveBoundaryBefore != null &&
                adaptiveBoundaryAfter != null
            ) {
                adaptiveBoundaryEvidence(
                    before = adaptiveBoundaryBefore,
                    after = adaptiveBoundaryAfter,
                    exactContinuousBefore = adaptiveBoundaryContinuityBefore,
                    exactContinuousAfter = adaptiveBoundaryContinuityAfter,
                )
            } else {
                AdaptiveBoundaryEvidence(
                    exactDelta = exactDelta,
                    proxyDelta = phaseProxyDelta,
                )
            }
            val adaptiveExactDelta = boundaryEvidence.exactDelta
            val adaptiveProxyDelta = boundaryEvidence.proxyDelta ?: 0L
            val proxyBoundaryThreshold =
                adaptiveProxyBoundaryThreshold(appliedProducerFrames)
            val adaptiveBoundary = adaptiveCandidate &&
                (
                    (adaptiveExactDelta != null && adaptiveExactDelta > 0) ||
                        (
                            adaptiveExactDelta == null &&
                                adaptiveProxyDelta >= proxyBoundaryThreshold
                            )
                    )
            if (adaptiveBoundary) {
                runEvents += event(
                    if (adaptiveExactDelta != null) {
                        "EXACT_BOUNDARY_FOUND"
                    } else {
                        "PROXY_BOUNDARY_FOUND"
                    },
                    "phase=${targetPhase.id}; layers=${targetPhase.activeLayers}; " +
                        "exactDelta=$adaptiveExactDelta; proxyDelta=$adaptiveProxyDelta; " +
                        "proxyThreshold=$proxyBoundaryThreshold; " +
                        "appliedProducerFrames=${"%.1f".format(appliedProducerFrames)}",
                )
                if (adaptiveRecoveryIndex > phaseIndex) {
                    runEvents += event(
                        "ADAPTIVE_RECOVERY_SCHEDULED",
                        "boundaryPhase=${targetPhase.id}; " +
                            "recoveryPhase=${scenario.phases[adaptiveRecoveryIndex].id}",
                    )
                }
                phaseIndex = phaseIndexAfterAdaptiveBoundary(
                    currentPhaseIndex = phaseIndex,
                    recoveryPhaseIndex = adaptiveRecoveryIndex,
                )
            } else {
                phaseIndex++
            }
        }
    }

    private suspend fun awaitLayerGeometryCoverage(
        generation: Long,
        profile: LayerSizeProfile,
    ): com.example.dpulayerlab.monitor.ProducerReadiness {
        val deadlineMs = saturatingAdd(
            SystemClock.elapsedRealtime(),
            LAYER_GEOMETRY_COVERAGE_ACK_TIMEOUT_MS,
        )
        while (true) {
            currentCoroutineContext().ensureActive()
            val readiness = frameTracker.producerReadiness(generation)
            if (layerGeometryCoverageSatisfied(readiness, profile)) return readiness
            if (SystemClock.elapsedRealtime() >= deadlineMs) return readiness
            delay(RENDERER_TEARDOWN_POLL_MS)
        }
    }

    private suspend fun configureCompression(phase: PhaseSpec) {
        val activeBeforeAttempt = compressionControlActive
        // A prompt coroutine cancellation can discard the remote result even though the provider
        // applied the request. Publish non-linear attempts as active-or-unknown before leaving
        // Main; only a returned NO_ADAPTER can safely roll this attempt marker back.
        compressionControlActive = compressionStateAtAttemptStart(
            route = phase.pixelRoute,
            activeBefore = activeBeforeAttempt,
        )
        val outcome = withContext(Dispatchers.IO) {
            systemMonitor.setCompressionRoute(phase.pixelRoute)
        }
        val result = outcome.result
        runEvents += event(
            "COMPRESSION_ROUTE",
            "${phase.pixelRoute.name}; result=${result.name}; " +
                "session=${outcome.serviceSession ?: "N/A"}",
        )
        val decision = decideCompressionTransition(
            route = phase.pixelRoute,
            result = result,
            activeBefore = if (result == CompressionControlResult.NO_ADAPTER) {
                activeBeforeAttempt
            } else {
                compressionControlActive
            },
        )
        compressionControlActive = decision.activeAfter
        compressionControlSession = when {
            decision.activeAfter && result == CompressionControlResult.APPLIED ->
                outcome.serviceSession
            !decision.activeAfter -> null
            else -> compressionControlSession
        }
        when {
            decision.activeAfter ->
                CompressionSafetyState.markNonLinearRouteMayBeActive()
            result == CompressionControlResult.APPLIED ->
                CompressionSafetyState.recordLinearReset(confirmed = true)
        }
        when (decision.failure) {
            CompressionTransitionFailure.NONE -> Unit
            CompressionTransitionFailure.REQUIRED_ADAPTER_MISSING ->
                throw UnsupportedRunException(
                    "SBWC REQUIRED를 적용할 vendor compression adapter가 없습니다.",
                )
            CompressionTransitionFailure.ACTIVE_ROUTE_ADAPTER_LOST -> {
                val reason = "활성 SBWC route를 해제하기 전에 vendor adapter 연결이 사라졌습니다."
                cancellationReason = reason
                throw PlanAbortException(reason)
            }
            CompressionTransitionFailure.REJECTED_OR_TIMEOUT -> {
                val reason =
                    "${phase.pixelRoute.name} compression 전환이 거부되었거나 시간 초과되었습니다."
                cancellationReason = reason
                throw PlanAbortException(reason)
            }
        }
    }

    /**
     * The only run-path cleanup ordering. Every verdict/cancellation path must detach and confirm
     * physical producers before asking the vendor to return a possibly compressed allocation route
     * to linear/default. If renderer teardown is unconfirmed, compression remains sticky and the
     * next plan is fail-closed instead of resetting underneath an SBWC/codec/EGL producer.
     */
    private suspend fun releaseActiveLoadsForRun(): Boolean {
        controllerCloseCleanupConfirmed.get()?.let { return it }
        if (progress.phase != null || progress.targetPhase != null) {
            progress = progress.copy(
                phase = null,
                targetPhase = null,
                transitionFraction = 0f,
                statusText = "부하 해제 및 physical producer teardown 확인 중",
            )
        }
        val loadsReleased = releaseGeneratedLoadsForRun()
        val rendererReleased = awaitRendererTeardownBarrier()
        val compressionReleased =
            if (rendererReleased) releaseCompressionRouteForRun() else false
        return loadsReleased && rendererReleased && compressionReleased
    }

    private suspend fun releaseGeneratedLoadsForRun(): Boolean {
        controllerCloseCleanupConfirmed.get()?.let { return it }
        var released = confirmGeneratedLoadRelease()
        controllerCloseCleanupConfirmed.get()?.let { return it }
        if (!released) released = confirmGeneratedLoadRelease()
        return controllerCloseCleanupConfirmed.get() ?: released
    }

    private suspend fun releaseCompressionRouteForRun(): Boolean {
        controllerCloseCleanupConfirmed.get()?.let { return it }
        var released = resetCompressionRoute()
        controllerCloseCleanupConfirmed.get()?.let { return it }
        if (!released) released = resetCompressionRoute()
        return controllerCloseCleanupConfirmed.get() ?: released
    }

    private suspend fun resetCompressionRoute(): Boolean {
        val outcome = withContext(Dispatchers.IO) {
            runCatching { systemMonitor.setCompressionRoute(PixelRoute.RGB_8888) }
        }.getOrElse { error ->
            recordCleanupFailure("compression reset", error)
            return false
        }
        val result = outcome.result
        runEvents += event(
            "COMPRESSION_RESET",
            "RGB_8888; result=${result.name}; activeBefore=$compressionControlActive; " +
                "session=${outcome.serviceSession ?: "N/A"}",
        )
        val decision = decideCompressionTransition(
            route = PixelRoute.RGB_8888,
            result = result,
            activeBefore = compressionControlActive,
        )
        compressionControlActive = decision.activeAfter
        if (!decision.activeAfter) compressionControlSession = null
        when {
            decision.activeAfter ->
                CompressionSafetyState.markNonLinearRouteMayBeActive()
            result == CompressionControlResult.APPLIED ->
                CompressionSafetyState.recordLinearReset(confirmed = true)
        }
        return when (decision.failure) {
            CompressionTransitionFailure.NONE -> true
            CompressionTransitionFailure.ACTIVE_ROUTE_ADAPTER_LOST -> {
                recordCleanupFailure(
                    "compression reset",
                    IllegalStateException("active SBWC route lost its vendor adapter"),
                )
                false
            }
            CompressionTransitionFailure.REJECTED_OR_TIMEOUT -> {
                recordCleanupFailure(
                    "compression reset",
                    IllegalStateException("vendor rejected or timed out linear/default mode"),
                )
                false
            }
            CompressionTransitionFailure.REQUIRED_ADAPTER_MISSING -> false
        }
    }

    private fun releaseGeneratedLoads(dropMemoryBuffers: Boolean = false): Boolean =
        runCatching {
            loadManager.releaseLoads(dropMemoryBuffers = dropMemoryBuffers)
            true
        }.getOrElse { error ->
            recordCleanupFailure("load release", error)
            false
        }

    private suspend fun confirmGeneratedLoadRelease(): Boolean = loadControlMutex.withLock {
        confirmGeneratedLoadReleaseLocked()
    }

    private suspend fun confirmGeneratedLoadReleaseLocked(): Boolean {
        val result = withContext(Dispatchers.IO) {
            // A successful prewarm pins reusable DRAM buffers for measurement fidelity. Every
            // run-boundary cleanup must explicitly drop them; ordinary phase quiesce keeps them.
            runCatching { loadManager.releaseLoadsAndConfirm(dropMemoryBuffers = true) }
        }
        return result.getOrElse { error ->
            recordCleanupFailure("load/NPU confirmed release", error)
            false
        }
    }

    private suspend fun confirmGeneratedLoadQuiesce(): Boolean = loadControlMutex.withLock {
        confirmGeneratedLoadQuiesceLocked()
    }

    private suspend fun confirmGeneratedLoadQuiesceLocked(): Boolean {
        val result = withContext(Dispatchers.IO) {
            runCatching { loadManager.releaseLoadsAndConfirm(dropMemoryBuffers = false) }
        }
        return result.getOrElse { error ->
            runEvents += event(
                "LOAD_QUIESCE_FAILURE",
                "phase-boundary load/NPU release exception: ${error.javaClass.simpleName}",
            )
            false
        }
    }

    private suspend fun applyRuntimeWorkloads(
        workloads: LoadSetpoints,
        restartProfile: Boolean,
        requirePositiveNpuAcknowledgment: Boolean,
    ): NpuControlHealth = loadControlMutex.withLock {
        applyRuntimeWorkloadsLocked(
            workloads = workloads,
            restartProfile = restartProfile,
            requirePositiveNpuAcknowledgment = requirePositiveNpuAcknowledgment,
        )
    }

    private suspend fun applyRuntimeWorkloadsLocked(
        workloads: LoadSetpoints,
        restartProfile: Boolean,
        requirePositiveNpuAcknowledgment: Boolean,
    ): NpuControlHealth {
        val effective = workloads.normalizedForExecution()
        if (requirePositiveNpuAcknowledgment && effective.npu > MIN_EFFECTIVE_LOAD) {
            return withContext(Dispatchers.IO) {
                loadManager.applyAndConfirmNpu(
                    newSetpoints = effective,
                    restartProfile = restartProfile,
                )
            }
        }
        loadManager.apply(effective, restartProfile)
        return loadManager.npuControlHealth()
    }

    private fun throwForNpuControlFailure(
        phaseId: String,
        operation: String,
        health: NpuControlHealth,
        positiveAcknowledgmentRequired: Boolean,
    ) {
        val failed = when {
            positiveAcknowledgmentRequired -> health.state != NpuControlState.APPLIED
            else ->
                health.state == NpuControlState.FAILED ||
                    health.state == NpuControlState.UNAVAILABLE
        }
        if (!failed) return
        val reason =
            "Phase '$phaseId' NPU $operation 실패: " +
                health.detail.trim().ifEmpty { health.state.name }.take(240)
        cancellationReason = reason
        runEvents += event("NPU_WORKLOAD_APPLY_FAILED", reason)
        throw PlanAbortException(reason)
    }

    private fun resetDisplayModeSafely() {
        requestDisplayModeSafely(0f, "display reset")
    }

    private fun requestDisplayModeSafely(refreshHz: Float, operation: String): Boolean =
        runCatching {
            requestDisplayMode(refreshHz)
        }.getOrElse { error ->
            recordCleanupFailure(operation, error)
            false
        }

    private fun requestDisplayModeOrAbort(refreshHz: Float, operation: String) {
        val applied = runCatching {
            requestDisplayMode(refreshHz)
        }.getOrElse { error ->
            val reason =
                "$operation display mode 요청 예외: ${error.javaClass.simpleName}"
                    .take(MAX_EVENT_MESSAGE_CHARS)
            runEvents += event("DISPLAY_REQUEST_FAILED", reason)
            throw InconclusiveRunException(reason)
        }
        if (applied) return
        val reason =
            "$operation display mode 요청을 Window/display가 거부했습니다."
                .take(MAX_EVENT_MESSAGE_CHARS)
        runEvents += event("DISPLAY_REQUEST_FAILED", reason)
        throw InconclusiveRunException(reason)
    }

    private fun setWakeStateSafely(awake: Boolean): Boolean =
        runCatching {
            setWakeState(awake)
            true
        }.getOrElse { error ->
            recordCleanupFailure("wake flag=${if (awake) "on" else "off"}", error)
            false
        }

    private fun recordCleanupFailure(operation: String, error: Throwable) {
        val message = "$operation 실패: ${error.javaClass.simpleName}"
        errorMessage = message
        runCatching { runEvents += event("CLEANUP_ERROR", message) }
    }

    private fun precheck(scenario: ScenarioSpec): String? {
        if (scenario.phases.any { it.pixelRoute == PixelRoute.SBWC_REQUIRED } && !hasSbwcAdapter) {
            return "SBWC REQUIRED는 vendor gralloc adapter가 연결된 빌드에서만 실행할 수 있습니다."
        }
        if (scenario.phases.any { it.workloads.npu > 0f } && !hasNpuAdapter) {
            return "NPU workload adapter가 없습니다. vendor bridge 또는 NNAPI native backend를 연결하세요."
        }
        return null
    }

    private suspend fun awaitVendorCapabilityDiscovery(scenario: ScenarioSpec): String? {
        val needsSbwc =
            (
                scenario.phases.any { it.pixelRoute == PixelRoute.SBWC_REQUIRED } ||
                    CompressionSafetyState.hasUnconfirmedCompressionCleanup()
                ) &&
                !hasSbwcAdapter
        val needsNpu = scenario.phases.any { it.workloads.npu > 0f } && !hasNpuAdapter
        if ((!needsSbwc && !needsNpu) || !systemMonitor.isVendorCapabilityDiscoveryPending()) {
            return null
        }
        val startedMs = SystemClock.elapsedRealtime()
        while (
            systemMonitor.isVendorCapabilityDiscoveryPending() &&
            SystemClock.elapsedRealtime() - startedMs < VENDOR_CAPABILITY_WAIT_MS
        ) {
            currentCoroutineContext().ensureActive()
            delay(VENDOR_CAPABILITY_POLL_MS)
        }
        val waitedMs = SystemClock.elapsedRealtime() - startedMs
        runEvents += event(
            "VENDOR_CAPABILITY_WAIT",
            "waited=${waitedMs}ms; pending=" +
                systemMonitor.isVendorCapabilityDiscoveryPending(),
        )
        return if (systemMonitor.isVendorCapabilityDiscoveryPending()) {
            "Vendor capability가 ${VENDOR_CAPABILITY_WAIT_MS}ms 안에 준비되지 않아 " +
                "지원 여부를 판정할 수 없습니다."
        } else {
            null
        }
    }

    /**
     * A timed-out non-linear request may have completed in the vendor service after this Activity
     * stopped waiting. Recover that process-wide uncertainty before any producer or cross-load is
     * enabled; only an acknowledged linear/default command clears the latch.
     */
    private suspend fun recoverCompressionRouteBeforeRun() {
        if (!CompressionSafetyState.hasUnconfirmedCompressionCleanup()) return
        progress = progress.copy(statusText = "이전 compression route를 linear로 복구 확인 중")
        val outcome = withContext(Dispatchers.IO) {
            runCatching { systemMonitor.setCompressionRoute(PixelRoute.RGB_8888) }
                .getOrDefault(
                    com.example.dpulayerlab.monitor.CompressionControlOutcome(
                        CompressionControlResult.REJECTED_OR_TIMEOUT,
                    ),
                )
        }
        val result = outcome.result
        val confirmed = result == CompressionControlResult.APPLIED
        CompressionSafetyState.recordLinearReset(confirmed)
        runEvents += event(
            "COMPRESSION_RECOVERY",
            "process-wide linear reset; result=${result.name}; confirmed=$confirmed; " +
                "session=${outcome.serviceSession ?: "N/A"}",
        )
        if (confirmed) {
            compressionControlActive = false
            compressionControlSession = null
            return
        }
        val reason =
            "이전 SBWC/compression route의 linear reset을 확인하지 못해 plan을 안전 중단합니다."
        cancellationReason = reason
        throw PlanAbortException(reason)
    }

    private fun ensurePlanMemoryAvailable() {
        loadManager.localWorkerFailure()?.let { failure ->
            val reason = "Local load worker 영구 실패: ${failure.summary()}"
                .take(MAX_EVENT_MESSAGE_CHARS)
            cancellationReason = reason
            runEvents += event("LOCAL_WORKER_FAILURE", reason)
            throw PlanAbortException(reason)
        }
        when (
            memorySafetyFailure(
                workloadAllocationFailed = loadManager.hasMemoryAllocationFailure(),
                systemLowMemory = DeviceRenderSafety.isLowMemory(activity),
            )
        ) {
            null -> return
            MemorySafetyFailure.WORKLOAD_ALLOCATION -> {
                val reason =
                    "Memory workload working-set 할당 실패로 전체 plan을 안전 중단합니다."
                cancellationReason = reason
                runEvents += event("MEMORY_WORKLOAD_FAILURE", reason)
                throw PlanAbortException(reason)
            }
            MemorySafetyFailure.SYSTEM_LOW_MEMORY -> Unit
        }
        val reason = "시스템 low-memory 상태로 전체 plan을 안전 중단합니다."
        cancellationReason = reason
        runEvents += event("LOW_MEMORY_STOP", reason)
        throw PlanAbortException(reason)
    }

    private fun verifyPerformanceEnvironmentBeforeProducer(
        recordSuccessfulObservation: Boolean = true,
    ) {
        val environment = runCatching {
            PerformanceEnvironment(
                powerSaveMode = powerManager.isPowerSaveMode,
                interactive = powerManager.isInteractive,
                deviceIdleMode = powerManager.isDeviceIdleMode,
                thermalStatus = powerManager.currentThermalStatus,
            )
        }.getOrElse { error ->
            val reason =
                "시작 전 power/thermal 상태를 읽을 수 없습니다: ${error.javaClass.simpleName}"
                    .take(MAX_EVENT_MESSAGE_CHARS)
            runEvents += event("PERFORMANCE_PREFLIGHT_FAILED", reason)
            throw InconclusiveRunException(reason)
        }
        performancePreflightFailure(
            environment = environment,
            protectionPolicy = activeRuntimeProtectionPolicy,
        )?.let { failure ->
            val reason = failure.userMessage().take(MAX_EVENT_MESSAGE_CHARS)
            runEvents += event(
                "PERFORMANCE_PREFLIGHT_FAILED",
                    "$reason thermal=${environment.thermalStatus}; " +
                    "interactive=${environment.interactive}; " +
                    "idle=${environment.deviceIdleMode}",
            )
            throw InconclusiveRunException(reason)
        }
        if (recordSuccessfulObservation) {
            runEvents += event(
                "PERFORMANCE_PREFLIGHT",
                "Battery Saver=off; interactive=true; deviceIdle=false; " +
                    "thermal=${environment.thermalStatus}; " +
                    runtimeProtectionPolicyDescription(activeRuntimeProtectionPolicy),
            )
        }
        recordUnmitigatedSevereThermalObservation(environment.thermalStatus)
    }

    private suspend fun enforceRuntimeSafety(snapshot: TelemetrySnapshot) {
        if (abortForLocalWorkerFailure()) return
        when (
            memorySafetyFailure(
                workloadAllocationFailed = loadManager.hasMemoryAllocationFailure(),
                systemLowMemory = snapshot.memoryLow,
            )
        ) {
            MemorySafetyFailure.WORKLOAD_ALLOCATION -> {
                abortForSafety(
                    reason =
                        "Memory workload working-set 할당 실패로 요청한 DRAM traffic을 " +
                            "유지할 수 없습니다.",
                    eventType = "MEMORY_WORKLOAD_FAILURE",
                )
                return
            }
            MemorySafetyFailure.SYSTEM_LOW_MEMORY -> {
                abortForSafety(
                    reason = "시스템 low-memory 신호로 안전 중단",
                    eventType = "LOW_MEMORY_STOP",
                )
                return
            }
            null -> Unit
        }
        if (
            safetyEnvelopeInvalidatedByPowerSave(
                envelopePowerSaveMode = safetyLimits.powerSaveMode,
                currentPowerSaveMode = snapshot.powerSaveMode,
            )
        ) {
            abortForSafety(
                reason = "실행 중 Battery Saver가 활성화되어 기존 safety envelope를 폐기합니다.",
                eventType = "SAFETY_ENVELOPE_CHANGED",
            )
            return
        }
        if (snapshot.thermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL) {
            abortForSafety(
                reason = "열 상태 ${snapshot.thermalLabel}로 안전 중단",
                eventType = "THERMAL_STOP",
            )
            return
        }
        recordUnmitigatedSevereThermalObservation(snapshot.thermalStatus)
        if (
            shouldApplySevereThermalDerating(
                thermalStatus = snapshot.thermalStatus,
                alreadyDerated = thermalReduced,
                protectionPolicy = activeRuntimeProtectionPolicy,
            )
        ) {
            val derateOwner = runJob ?: return
            val derateGeneration = progress.producerGeneration
            val typedHwcTargetBeforeDerate = progress.targetPhase
            thermalReduced = true
            val typedHwcTargetAfterDerate =
                typedHwcTargetBeforeDerate?.let(::applyPersistentSafety)
            if (
                typedHwcTargetBeforeDerate != null &&
                typedHwcTargetAfterDerate != null &&
                !hwcCompositionContractPreserved(
                    requested = typedHwcTargetBeforeDerate,
                    effective = typedHwcTargetAfterDerate,
                )
            ) {
                activeHwcCompositionCoverage.get()?.recordContractFailure(
                    "typed HWC target changed during thermal derate: " +
                        hwcCompositionContractDeltaSummary(
                            requested = typedHwcTargetBeforeDerate,
                            effective = typedHwcTargetAfterDerate,
                        ),
                )
                runEvents += event(
                    "HWC_EXPECTATION_INVALIDATED",
                    "phase=${typedHwcTargetBeforeDerate.id}; thermal=${snapshot.thermalLabel}; " +
                        hwcCompositionContractDeltaSummary(
                            requested = typedHwcTargetBeforeDerate,
                            effective = typedHwcTargetAfterDerate,
                        ),
                )
            }
            val reduced = progress.phase?.let(::applyPersistentSafety)
            if (reduced != null) {
                val pauseStartedAtMs = SystemClock.elapsedRealtime()
                val thermalOwnsClockPause = !producerRecoveryPaused
                val thermalPhaseClock = activePhaseClock
                val thermalFrameBudget = activeProducerFrameBudget
                runtimeControlPaused = true
                if (thermalOwnsClockPause) {
                    thermalPhaseClock?.pause(
                        pauseStartedAtMs,
                        PhasePauseOwner.THERMAL_DERATE,
                    )
                    thermalFrameBudget?.pauseAtPhysicalBoundary(
                        atMonotonicMs = pauseStartedAtMs,
                        totalFrames = frameTracker.totalPhysicalProducedFrames(),
                        owner = PhasePauseOwner.THERMAL_DERATE,
                    )
                }
                progress = progress.copy(
                    phase = rendererPreparationPhase(reduced),
                    targetPhase = progress.targetPhase?.let(::applyPersistentSafety),
                    statusText = "${reduced.label} · thermal zero 확인 중",
                    thermalDerated = true,
                )
                var orderedZeroConfirmed: Boolean
                var workloadApplied = false
                var displayApplied = false
                var ownerStillActive: Boolean
                try {
                    loadControlMutex.withLock {
                        orderedZeroConfirmed = confirmGeneratedLoadQuiesceLocked()
                        ownerStillActive = thermalDerateOwnerStillActive(
                            owner = derateOwner,
                            generation = derateGeneration,
                        )
                        if (orderedZeroConfirmed && ownerStillActive) {
                            if (producerRecoveryPaused) {
                                // Recovery owns a stronger zero-load invariant. Its first positive
                                // post-recovery tick will use the same bounded NPU acknowledgment.
                                workloadApplied = true
                            } else {
                                val npuHealth = runCatching {
                                    applyRuntimeWorkloadsLocked(
                                        workloads = reduced.workloads,
                                        restartProfile = false,
                                        requirePositiveNpuAcknowledgment =
                                            reduced.workloads.npu > MIN_EFFECTIVE_LOAD,
                                    )
                                }.getOrElse { error ->
                                    runEvents += event(
                                        "THERMAL_DERATE_APPLY_ERROR",
                                        "workload derate 실패: " +
                                            error.javaClass.simpleName,
                                    )
                                    NpuControlHealth.failed(
                                        "thermal workload exception: " +
                                            error.javaClass.simpleName,
                                    )
                                }
                                workloadApplied = if (
                                    reduced.workloads.npu > MIN_EFFECTIVE_LOAD
                                ) {
                                    npuHealth.state == NpuControlState.APPLIED
                                } else {
                                    npuHealth.state != NpuControlState.FAILED &&
                                        npuHealth.state != NpuControlState.UNAVAILABLE
                                }
                                if (!workloadApplied) {
                                    runEvents += event(
                                        "THERMAL_DERATE_APPLY_ERROR",
                                        "NPU=${npuHealth.state}; " +
                                            npuHealth.detail.take(160),
                                    )
                                }
                            }
                        }
                        ownerStillActive = thermalDerateOwnerStillActive(
                            owner = derateOwner,
                            generation = derateGeneration,
                        )
                        if (!ownerStillActive && workloadApplied) {
                            // STOP/close may have won while the positive acknowledgment blocked.
                            // Reassert ordered zero and never resurrect the cancelled run.
                            workloadApplied = false
                            confirmGeneratedLoadQuiesceLocked()
                        }
                    }
                    if (ownerStillActive && workloadApplied) {
                        displayApplied = requestDisplayModeSafely(
                            reduced.requestedDisplayHz,
                            "thermal display derate",
                        )
                    }
                    ownerStillActive = thermalDerateOwnerStillActive(
                        owner = derateOwner,
                        generation = derateGeneration,
                    )
                    if (!ownerStillActive) {
                        loadControlMutex.withLock {
                            confirmGeneratedLoadQuiesceLocked()
                        }
                        resetDisplayModeSafely()
                        return
                    }
                    if (
                        thermalDerateActionFailed(
                            orderedZeroConfirmed = orderedZeroConfirmed,
                            workloadApplied = workloadApplied,
                            displayApplied = displayApplied,
                        )
                    ) {
                        abortForSafety(
                            reason =
                                "열 감속 ordered-zero/setpoint/display 적용을 확인할 수 없어 " +
                                    "안전 중단합니다.",
                            eventType = "THERMAL_DERATE_FAILED",
                        )
                        return
                    }
                } finally {
                    val resumeAtMs = SystemClock.elapsedRealtime()
                    if (
                        thermalOwnsClockPause &&
                        !producerRecoveryPaused &&
                        thermalDerateOwnerStillActive(
                            owner = derateOwner,
                            generation = derateGeneration,
                        )
                    ) {
                        thermalFrameBudget?.observePhysicalFrames(
                            totalFrames = frameTracker.totalPhysicalProducedFrames(),
                            countAsActive = false,
                        )
                        val committedProducerCount =
                            frameTracker.producerReadiness(derateGeneration)
                                .expectedCount
                                .coerceIn(1, ScenarioSafetyPolicy.HARD_MAX_LAYERS)
                        thermalFrameBudget?.apply(
                            atMonotonicMs = resumeAtMs,
                            producerFps = reduced.producerFps,
                            activeLayers = committedProducerCount,
                        )
                    }
                    if (thermalOwnsClockPause) {
                        thermalPhaseClock?.resume(
                            resumeAtMs,
                            PhasePauseOwner.THERMAL_DERATE,
                        )
                        thermalFrameBudget?.resume(
                            resumeAtMs,
                            PhasePauseOwner.THERMAL_DERATE,
                        )
                    }
                    runtimeControlPaused = false
                }
            }
            if (
                !thermalDerateOwnerStillActive(
                    owner = derateOwner,
                    generation = derateGeneration,
                )
            ) {
                return
            }
            progress = progress.copy(
                phase = reduced,
                targetPhase = progress.targetPhase?.let(::applyPersistentSafety),
                statusText = reduced?.let { "${it.label} · thermal derate" }
                    ?: "${progress.statusText} · thermal derate",
                thermalDerated = true,
            )
            runEvents += event("THERMAL_DERATE", "thermal=${snapshot.thermalLabel}; persistent=true")
            errorMessage = "열 상태 ${snapshot.thermalLabel}: 남은 테스트 부하를 지속 감속합니다."
        }
    }

    private fun recordUnmitigatedSevereThermalObservation(thermalStatus: Int) {
        if (
            severeThermalPolicyObservationRecorded ||
            activeRuntimeProtectionPolicy.severeThermalDeratingEnabled ||
            thermalStatus < PowerManager.THERMAL_STATUS_SEVERE ||
            thermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL
        ) {
            return
        }
        severeThermalPolicyObservationRecorded = true
        runEvents += event(
            "THERMAL_SEVERE_APP_DERATE_DISABLED",
            "thermal=$thermalStatus; requested load retained; " +
                "Android/kernel thermal mitigation remains authoritative",
        )
    }

    private fun thermalDerateOwnerStillActive(owner: Job, generation: Long): Boolean =
        runJob === owner &&
            owner.isActive &&
            cancellationReason == null &&
            isRunning &&
            progress.producerGeneration == generation

    private fun enforcePerformanceIsolationContinuity(snapshot: TelemetrySnapshot) {
        if (!shouldMonitorPerformanceIsolation(performanceIsolationLifecycle)) return
        val powerState = runCatching {
            Triple(
                powerManager.isPowerSaveMode,
                powerManager.isInteractive,
                powerManager.isDeviceIdleMode,
            )
        }.getOrElse { error ->
            abortForSafety(
                reason =
                    "실행 중 power 격리 상태 확인 실패: " +
                        error.javaClass.simpleName,
                eventType = "PERFORMANCE_ISOLATION_LOST",
            )
            return
        }
        val expectedSession = performanceSessionTicket?.serviceSession
        when (
            performanceIsolationContinuityFailure(
                sampledPowerSaveMode = snapshot.powerSaveMode,
                directPowerSaveMode = powerState.first,
                interactive = powerState.second,
                deviceIdleMode = powerState.third,
                expectedVendorSession = expectedSession,
                observedVendorSession = snapshot.vendorServiceSession,
            )
        ) {
            PerformanceIsolationContinuityFailure.POWER_SAVE_ACTIVE -> {
                abortForSafety(
                    reason = "실행 중 Battery Saver가 활성화되어 성능 격리를 잃었습니다.",
                    eventType = "SAFETY_ENVELOPE_CHANGED",
                )
                return
            }
            PerformanceIsolationContinuityFailure.DISPLAY_NOT_INTERACTIVE -> {
                abortForSafety(
                    reason = "실행 중 display가 non-interactive 상태로 전환됐습니다.",
                    eventType = "PERFORMANCE_ISOLATION_LOST",
                )
                return
            }
            PerformanceIsolationContinuityFailure.DEVICE_IDLE_ACTIVE -> {
                abortForSafety(
                    reason = "실행 중 device-idle 상태로 전환됐습니다.",
                    eventType = "PERFORMANCE_ISOLATION_LOST",
                )
                return
            }
            PerformanceIsolationContinuityFailure.VENDOR_SESSION_CHANGED -> {
                performanceSessionIntegrityConfirmed.set(false)
                performanceIsolationStatus = "실패 · vendor session 변경"
                abortForSafety(
                    reason =
                        "성능 격리 vendor session이 S${expectedSession ?: "N/A"} → " +
                            "S${snapshot.vendorServiceSession ?: "disconnected"}로 변경됐습니다.",
                    eventType = "PERFORMANCE_ISOLATION_LOST",
                )
                return
            }
            null -> Unit
        }
    }

    private fun enforceCompressionSessionContinuity(snapshot: TelemetrySnapshot) {
        val activeRoute = progress.phase?.pixelRoute ?: progress.targetPhase?.pixelRoute
        if (
            !compressionSessionContinuityLost(
                compressionControlActive = compressionControlActive,
                activeRoute = activeRoute,
                expectedSession = compressionControlSession,
                observedSession = snapshot.vendorServiceSession,
            )
        ) {
            return
        }
        CompressionSafetyState.markNonLinearRouteMayBeActive()
        val reason =
            "활성 ${activeRoute?.name ?: "SBWC"} compression route의 vendor session이 " +
                "${compressionControlSession ?: "N/A"} → " +
                "${snapshot.vendorServiceSession ?: "disconnected"}로 변경되어 " +
                "route 적용 상태를 검증할 수 없습니다."
        abortForSafety(reason, "COMPRESSION_SESSION_CHANGED")
    }

    private fun abortForLocalWorkerFailure(): Boolean {
        val failure = loadManager.localWorkerFailure() ?: return false
        abortForSafety(
            reason = "Local load worker 영구 실패: ${failure.summary()}",
            eventType = "LOCAL_WORKER_FAILURE",
        )
        return true
    }

    private fun applyPersistentSafety(phase: PhaseSpec): PhaseSpec {
        if (!thermalReduced) return phase
        val minimumLayers = if (phase.includeGlLayer && phase.activeLayers > 1) 2 else 1
        val reducedLayers = max(minimumLayers, phase.activeLayers / 2)
        return phase.copy(
            activeLayers = reducedLayers,
            producerFps = min(60f, phase.producerFps),
            requestedDisplayHz = min(60f, phase.requestedDisplayHz),
            workloads = phase.workloads.copy(
                cpu = phase.workloads.cpu * 0.35f,
                memory = phase.workloads.memory * 0.35f,
                gpu = phase.workloads.gpu * 0.50f,
                npu = phase.workloads.npu * 0.50f,
            ).normalizedForExecution(),
        )
    }

    private fun neutralPhaseFor(target: PhaseSpec): PhaseSpec =
        target.copy(
            activeLayers =
                if (target.includeGlLayer && target.activeLayers > 1) 2 else 1,
            producerFps = min(60f, target.producerFps),
            requestedDisplayHz = min(60f, target.requestedDisplayHz),
            layerSizeProfile = preparationLayerSizeProfile(target.layerSizeProfile),
            workloads = LoadSetpoints(shape = target.workloads.shape),
        )

    private fun abortForSafety(reason: String, eventType: String) {
        if (cancellationReason != null) return
        cancellationReason = reason
        runEvents += event(eventType, reason)
        errorMessage = "$reason. 테스트를 중단했습니다."
        // Publish producer removal and zero local/NPU setpoints at the observation point. The
        // run coroutine may currently be waiting in a bounded vendor call and will perform the
        // confirmed/retried cleanup when cancellation reaches it.
        progress = progress.copy(
            phase = null,
            targetPhase = null,
            statusText = reason,
        )
        // Every emergency abort is terminal for the current plan. Drop retained copy buffers too:
        // a codec/GL/worker failure can itself be memory-pressure related even when the low-memory
        // callback has not fired yet.
        releaseGeneratedLoads(dropMemoryBuffers = true)
        requestDisplayModeSafely(60f, "emergency display reduction")
        runJob?.cancel()
    }

    private fun observeExactCounter(snapshot: TelemetrySnapshot) {
        val baseline = baselineExactUnderruns ?: return
        val source = baselineExactSource ?: return
        if (!exactCounterContinuous) return
        val current = snapshot.exactUnderruns
        val highest = highestExactUnderruns ?: baseline
        if (
            current == null ||
            snapshot.exactUnderrunSource != source ||
            snapshot.exactUnderrunQuality != baselineExactQuality ||
            current < baseline ||
            current < highest
        ) {
            invalidateExactCounter(
                "exact counter source became unavailable, changed, or regressed",
            )
            return
        }
        exactCounterSamplesAfterBaseline++
        highestExactUnderruns = max(highestExactUnderruns ?: baseline, current)
    }

    private fun invalidateExactCounter(reason: String) {
        val wasContinuous = exactCounterContinuous
        exactCounterContinuous = false
        if (!wasContinuous || exactCounterContinuityEventRecorded) return
        exactCounterContinuityEventRecorded = true
        runEvents += event("EXACT_COUNTER_INVALIDATED", reason)
    }

    private fun deriveVerdict(): RunVerdict {
        val exactDelta = exactUnderrunDelta()
        val suspectedDelta = monotonicCounterDelta(
            baselineValue = baselineSuspected,
            baselineSource = PROXY_COUNTER_SOURCE,
            currentValue = telemetry.suspectedUnderruns,
            currentSource = PROXY_COUNTER_SOURCE,
        ) ?: 0L
        if (exactDelta != null && suspectedDelta > 0L) {
            runEvents += event(
                "PROXY_SIGNAL",
                "proxyDelta=$suspectedDelta retained as auxiliary evidence; " +
                    "verified exactDelta=$exactDelta controls the verdict",
            )
        }
        val evidenceVerdict = underrunVerdict(exactDelta, suspectedDelta)
        val compositionAdjustedVerdict = verdictWithHwcCompositionCoverage(
            evidenceVerdict = evidenceVerdict,
            coverageFailureReason = hwcCompositionCoverageFailureReason,
        )
        if (compositionAdjustedVerdict != evidenceVerdict) {
            runEvents += event(
                "INCONCLUSIVE",
                checkNotNull(hwcCompositionCoverageFailureReason),
            )
            return compositionAdjustedVerdict
        }
        if (exactDelta?.let { it > 0L } != true && producerRateShortfallReason != null) {
            runEvents += event(
                "INCONCLUSIVE",
                checkNotNull(producerRateShortfallReason),
            )
            return RunVerdict.INCONCLUSIVE
        }
        return evidenceVerdict
    }

    private fun exactUnderrunDelta(): Long? {
        return reliableExactCounterDelta(
            baselineValue = baselineExactUnderruns,
            baselineSource = baselineExactSource,
            highestValue = highestExactUnderruns,
            counterContinuous = exactCounterContinuous,
            samplesAfterBaseline = exactCounterSamplesAfterBaseline,
        )
    }

    private suspend fun finalizeRun(
        scenario: ScenarioSpec,
        verdict: RunVerdict,
    ): ScenarioRunArtifact {
        val exactDelta = exactUnderrunDelta()
        val exactProvenance = exactDeltaProvenance(
            exactDelta = exactDelta,
            baselineSource = baselineExactSource,
            baselineQuality = baselineExactQuality,
        )
        val peakCpu = consistentGaugePeak(
            samples = runSamples,
            selector = TelemetrySnapshot::cpu,
            validRange = 0f..100f,
        )
        val peakMemoryUsed = consistentGaugePeak(
            samples = runSamples,
            selector = TelemetrySnapshot::memoryUsed,
            validRange = 0f..100f,
        )
        val peakGeneratedBandwidth = consistentGaugePeak(
            samples = runSamples,
            selector = TelemetrySnapshot::generatedBandwidth,
            validRange = 0f..Float.MAX_VALUE,
        )
        listOf(
            "cpu" to peakCpu,
            "memoryUsed" to peakMemoryUsed,
            "generatedBandwidth" to peakGeneratedBandwidth,
        ).filter { (_, peak) -> peak.provenanceChanged }
            .forEach { (metric, _) ->
                runEvents += event(
                    "PEAK_PROVENANCE_CHANGED",
                    "metric=$metric; aggregate peak omitted because quality/source changed",
                )
            }
        val summary = RunSummary(
            scenario = scenario,
            startedEpochMs = runStartEpochMs,
            finishedEpochMs = System.currentTimeMillis(),
            verdict = verdict,
            exactUnderrunDelta = exactDelta,
            exactUnderrunSource = exactProvenance.source,
            exactUnderrunQuality = exactProvenance.quality,
            suspectedUnderrunDelta = monotonicCounterDelta(
                baselineValue = baselineSuspected,
                baselineSource = PROXY_COUNTER_SOURCE,
                currentValue = telemetry.suspectedUnderruns,
                currentSource = PROXY_COUNTER_SOURCE,
            ) ?: 0L,
            peakCpu = peakCpu.value,
            peakMemoryUsed = peakMemoryUsed.value,
            peakGeneratedBandwidth = peakGeneratedBandwidth.value,
            events = runEvents.toList(),
            samples = runSamples.toList(),
        )
        lastSummary = summary
        val reportFile = try {
            withContext(Dispatchers.IO) { ReportWriter.write(appContext, summary) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            errorMessage = "보고서 저장 실패: ${error.message}"
            null
        }
        lastReportFile = reportFile
        return ScenarioRunArtifact(summary, reportFile)
    }

    /**
     * Scenario JSON is initially published after the terminal counter sample. The plan-wide
     * performance lease ends later, so atomically publish a revised final scenario report that
     * contains the exact restore outcome. A failed first END followed by a successful finalizer
     * retry is preserved as two events rather than rewritten into a false first-pass success.
     */
    private suspend fun persistPerformanceRestoreOutcome(
        restored: Boolean,
        releaseReason: String,
    ): Boolean {
        val currentSummary = lastSummary ?: return true
        val transition = performanceRestoreReportTransition(
            existingEventTypes = currentSummary.events.map(RunEvent::type),
            restored = restored,
        )
        val updatedSummary = when (transition) {
            PerformanceRestoreReportTransition.NONE -> currentSummary
            PerformanceRestoreReportTransition.CONFIRMED ->
                currentSummary.copy(
                    finishedEpochMs = System.currentTimeMillis(),
                    events = currentSummary.events + RunEvent(
                        monotonicMs =
                            (SystemClock.elapsedRealtime() - runStartMonotonicMs)
                                .coerceAtLeast(0L),
                        type = PERFORMANCE_RESTORE_CONFIRMED_EVENT,
                        message =
                            "Performance isolation cleanup confirmed; any app-owned Battery " +
                                "Saver mutation was restored to its producer-before-BEGIN state; " +
                                "reason=$releaseReason",
                    ),
                )
            PerformanceRestoreReportTransition.FAILED ->
                currentSummary.copy(
                    finishedEpochMs = System.currentTimeMillis(),
                    verdict = RunVerdict.ABORTED,
                    events = currentSummary.events + RunEvent(
                        monotonicMs =
                            (SystemClock.elapsedRealtime() - runStartMonotonicMs)
                                .coerceAtLeast(0L),
                        type = PERFORMANCE_RESTORE_FAILED_EVENT,
                        message =
                            "Battery Saver policy restore or renewal shutdown was not confirmed; " +
                                "reason=$releaseReason",
                    ),
                )
            PerformanceRestoreReportTransition.CONFIRMED_AFTER_RETRY ->
                currentSummary.copy(
                    finishedEpochMs = System.currentTimeMillis(),
                    events = currentSummary.events + RunEvent(
                        monotonicMs =
                            (SystemClock.elapsedRealtime() - runStartMonotonicMs)
                                .coerceAtLeast(0L),
                        type = PERFORMANCE_RESTORE_CONFIRMED_AFTER_RETRY_EVENT,
                        message =
                            "A later finalizer END confirmed exact Battery Saver restoration; " +
                                "the run remains ABORTED because the first cleanup boundary failed",
                    ),
                )
        }
        if (transition == PerformanceRestoreReportTransition.FAILED) {
            val invalidation = invalidateEarlierPlanResultsForRestoreFailure(
                results = mutablePlanResultHistory,
                currentStartedEpochMs = currentSummary.startedEpochMs,
                currentScenarioId = currentSummary.scenario.id,
                terminalReason =
                    "Plan-wide Battery Saver 원상복구가 확인되지 않아 이전 개별 보고서를 " +
                        "무효화했습니다.",
            )
            invalidation.updatedResults.forEachIndexed { index, result ->
                mutablePlanResultHistory[index] = result
            }
            withContext(NonCancellable + Dispatchers.IO) {
                val reportsDirectory = File(appContext.filesDir, "reports")
                invalidation.invalidatedReportPaths.forEach { path ->
                    deleteManagedCompletedReportBestEffort(
                        reportsDirectory = reportsDirectory,
                        reportFile = File(path),
                    )
                }
            }
        }
        if (
            transition == PerformanceRestoreReportTransition.NONE &&
            lastPerformanceRestoreReportPersisted
        ) {
            return true
        }

        lastSummary = updatedSummary
        updateLatestPlanResultFromSummary(
            summary = updatedSummary,
            reportFile = lastReportFile,
        )
        val previousReport = lastReportFile
        val replacement = try {
            withContext(NonCancellable + Dispatchers.IO) {
                ReportWriter.write(appContext, updatedSummary)
            }
        } catch (error: Exception) {
            val publicationFailureSummary =
                markPerformanceRestoreReportPublicationFailed(
                    summary = updatedSummary,
                    finishedEpochMs = System.currentTimeMillis(),
                    monotonicMs =
                        (SystemClock.elapsedRealtime() - runStartMonotonicMs)
                            .coerceAtLeast(0L),
                    failureType = error.javaClass.simpleName,
                )
            lastPerformanceRestoreReportPersisted = false
            // The previous JSON predates the plan-wide performance-policy END. Once final
            // publication fails it must not remain reachable through either "last report" or
            // result history, otherwise a caller could share a CLEAN/PASSED artifact for an
            // ABORTED cleanup boundary.
            lastSummary = publicationFailureSummary
            lastReportFile = null
            updateLatestPlanResultFromSummary(
                summary = publicationFailureSummary,
                reportFile = null,
            )
            errorMessage =
                "성능 정책 복원 결과 보고서 저장 실패: ${error.javaClass.simpleName}"
                    .take(MAX_EVENT_MESSAGE_CHARS)
            withContext(NonCancellable + Dispatchers.IO) {
                deleteManagedCompletedReportBestEffort(
                    reportsDirectory = File(appContext.filesDir, "reports"),
                    reportFile = previousReport,
                )
            }
            return false
        }
        lastReportFile = replacement
        lastPerformanceRestoreReportPersisted = true
        updateLatestPlanResultFromSummary(
            summary = updatedSummary,
            reportFile = replacement,
        )
        if (previousReport != null && previousReport != replacement) {
            withContext(NonCancellable + Dispatchers.IO) {
                deleteManagedCompletedReportBestEffort(
                    reportsDirectory = File(appContext.filesDir, "reports"),
                    reportFile = previousReport,
                )
            }
        }
        return true
    }

    private fun updateLatestPlanResultFromSummary(
        summary: RunSummary,
        reportFile: File?,
    ) {
        val resultIndex = mutablePlanResultHistory.indexOfLast { result ->
            result.startedEpochMs == summary.startedEpochMs &&
                result.scenario.id == summary.scenario.id
        }
        if (resultIndex < 0) return
        val previous = mutablePlanResultHistory[resultIndex]
        mutablePlanResultHistory[resultIndex] = previous.copy(
            verdict = summary.verdict,
            finishedEpochMs = summary.finishedEpochMs,
            exactUnderrunDelta = summary.exactUnderrunDelta,
            suspectedUnderrunDelta = summary.suspectedUnderrunDelta,
            reportPath = reportFile?.absolutePath,
            terminalReason = summary.terminalReason(MAX_TERMINAL_REASON_CHARS),
        )
    }

    private suspend fun finalizeProtected(
        scenario: ScenarioSpec,
        preselectedVerdict: RunVerdict?,
        pendingMediaSource: AtomicReference<PinnedMediaSource?>,
    ): ScenarioRunArtifact = withContext(NonCancellable) {
        val rendererReleased = awaitRendererTeardownBarrier()
        val selectedDescriptorReleased = closeSelectedVideoDecoder()
        val pendingDescriptorReleased =
            pendingMediaSource.getAndSet(null)?.closeWithResult() ?: true
        val mediaDescriptorsReleased = mediaDescriptorCleanupConfirmed(
            selectedDescriptorReleased = selectedDescriptorReleased,
            pendingDescriptorReleased = pendingDescriptorReleased,
            processCleanupUnconfirmed = PinnedMediaCleanupState.hasUnconfirmedCleanup(),
        )
        if (!mediaDescriptorsReleased) {
            val detail = PinnedMediaCleanupState.detail() ?: "unknown close failure"
            runEvents += event(
                "PINNED_MEDIA_CLEANUP_FAILED",
                "selected-media descriptor cleanup was not confirmed: $detail",
            )
            cancellationReason = preserveFirstCancellationReason(
                current = cancellationReason,
                requested = "Selected-media descriptor 종료를 확인할 수 없습니다.",
                fallback = "Selected-media descriptor cleanup failed",
                maxChars = MAX_EVENT_MESSAGE_CHARS,
            )
        }
        if (shouldCollectFinalTelemetrySample(preselectedVerdict, rendererReleased)) {
            progress = progress.copy(statusText = "최종 counter 연속성 확인 중")
            try {
                val finalSample = collectTelemetrySample()
                runEvents += event(
                    "COUNTER_FINAL_SAMPLE",
                    "exact=${finalSample.exactUnderruns ?: "N/A"}; " +
                        "quality=${finalSample.exactUnderrunQuality.name}; " +
                        "source=${finalSample.exactUnderrunSource ?: "N/A"}; " +
                        "proxy=${finalSample.suspectedUnderruns}",
                )
            } catch (error: Exception) {
                invalidateExactCounter(
                    "final post-teardown counter sample failed: ${error.javaClass.simpleName}",
                )
                runEvents += event(
                    "COUNTER_FINAL_SAMPLE_FAILED",
                    "post-teardown telemetry failed: ${error.javaClass.simpleName}",
                )
            }
        }
        val derivedVerdict = preselectedVerdict ?: deriveVerdict()
        val effectiveVerdict = if (!mediaDescriptorsReleased) {
            RunVerdict.ABORTED
        } else {
            finalVerdictAfterTeardown(
                preselectedVerdict = preselectedVerdict,
                rendererReleased = rendererReleased,
                cancellationPresent = cancellationReason != null,
                derivedVerdict = derivedVerdict,
            )
        }
        if (!rendererReleased || !mediaDescriptorsReleased) {
            val teardownReason = if (!rendererReleased) {
                "Physical producer의 최종 teardown 확인에 실패해 후속 queue를 차단했습니다."
            } else {
                "Selected-media descriptor cleanup 확인에 실패해 후속 queue를 차단했습니다."
            }
            cancellationReason = listOfNotNull(cancellationReason, teardownReason)
                .distinct()
                .joinToString(" · ")
                .take(MAX_EVENT_MESSAGE_CHARS)
            progress = progress.copy(
                stage = RunnerStage.ABORTED,
                phase = null,
                targetPhase = null,
                statusText = cancellationReason.orEmpty(),
            )
        }
        val artifact = finalizeRun(scenario, effectiveVerdict)
        runFinalized = true
        artifact
    }

    private fun beginTrackedProducerGeneration(): Long =
        frameTracker.beginProducerGeneration().also { generation ->
            topologyPendingBoundary.set(null)
            pendingRendererGeneration = generation
        }

    /**
     * A clean bounded join is not enough by itself: Compose must also acknowledge that the final
     * AndroidView stage was removed. This prevents a late codec/EGL/Canvas teardown failure from
     * being reported as CLEAN and prevents the next queue item from overlapping that producer.
     */
    private suspend fun awaitRendererTeardownBarrier(): Boolean {
        val generation = pendingRendererGeneration
        if (generation == null) {
            val deadlineMs = saturatingAdd(
                SystemClock.elapsedRealtime(),
                RENDERER_TEARDOWN_ACK_TIMEOUT_MS,
            )
            while (true) {
                val leaseActive = RendererSafetyState.hasUnconfirmedTeardown()
                when (
                    rendererTeardownBarrierDecision(
                        generationPresent = false,
                        callbackFailure = false,
                        stageRemovalAcknowledged = false,
                        processLeaseActive = leaseActive,
                        deadlineReached = SystemClock.elapsedRealtime() >= deadlineMs,
                    )
                ) {
                    RendererTeardownBarrierDecision.SUCCESS -> return true
                    RendererTeardownBarrierDecision.FAIL -> {
                        runEvents += event(
                            "PRODUCER_TEARDOWN_UNCONFIRMED",
                            "process-wide producer lease remained without an active run generation",
                        )
                        return false
                    }
                    RendererTeardownBarrierDecision.WAIT ->
                        delay(RENDERER_TEARDOWN_POLL_MS)
                }
            }
        }
        val activeGeneration = checkNotNull(generation)
        if (progress.phase != null || progress.targetPhase != null) {
            progress = progress.copy(
                phase = null,
                targetPhase = null,
                transitionFraction = 0f,
                statusText = "Physical producer teardown 확인 중",
            )
        }

        val deadlineMs = saturatingAdd(
            SystemClock.elapsedRealtime(),
            RENDERER_TEARDOWN_ACK_TIMEOUT_MS,
        )
        while (true) {
            val readiness = frameTracker.producerReadiness(activeGeneration)
            val globalLeaseActive = RendererSafetyState.hasUnconfirmedTeardown()
            when (
                rendererTeardownBarrierDecision(
                    generationPresent = true,
                    callbackFailure = readiness.teardownFailed,
                    stageRemovalAcknowledged = readiness.teardownCompleted,
                    processLeaseActive = globalLeaseActive,
                    deadlineReached = SystemClock.elapsedRealtime() >= deadlineMs,
                )
            ) {
                RendererTeardownBarrierDecision.SUCCESS -> {
                    pendingRendererGeneration = null
                    runEvents += event(
                        "PRODUCER_TEARDOWN_CONFIRMED",
                        "generation=$activeGeneration; all physical producers stopped",
                    )
                    return true
                }
                RendererTeardownBarrierDecision.FAIL -> {
                    if (!readiness.teardownFailed) {
                        frameTracker.markProducerTeardownFailure(activeGeneration)
                    }
                    runEvents += event(
                        "PRODUCER_TEARDOWN_UNCONFIRMED",
                        "generation=$activeGeneration; callbackFailure=${readiness.teardownFailed}; " +
                            "stageAckMissing=${!readiness.teardownCompleted}; " +
                            "processLeaseActive=$globalLeaseActive",
                    )
                    return false
                }
                RendererTeardownBarrierDecision.WAIT ->
                    delay(RENDERER_TEARDOWN_POLL_MS)
            }
        }
    }

    /**
     * A previous timeout may become recoverable after its watched thread exits and the UI reports
     * stage removal. Until both facts are true, a new plan remains fail-closed.
     */
    private fun hasUnconfirmedRendererCleanup(): Boolean {
        val globalLeaseActive = RendererSafetyState.hasUnconfirmedTeardown()
        val generation = pendingRendererGeneration ?: return globalLeaseActive
        val teardownCompleted = frameTracker.producerReadiness(generation).teardownCompleted
        if (!globalLeaseActive && teardownCompleted) {
            pendingRendererGeneration = null
            return false
        }
        return true
    }

    private fun event(type: String, message: String) = RunEvent(
        monotonicMs = (SystemClock.elapsedRealtime() - runStartMonotonicMs).coerceAtLeast(0L),
        type = type,
        message = message.take(MAX_EVENT_MESSAGE_CHARS),
    )

    private suspend fun awaitTestWindowIsolation(token: Long): Boolean {
        val deadline = saturatingAdd(
            SystemClock.elapsedRealtime(),
            SYSTEM_UI_ISOLATION_TIMEOUT_MS,
        )
        while (currentCoroutineContext().isActive) {
            if (activeTestWindowIsolationToken != token) return false
            if (testWindowIsolation.isConfirmed(token)) return true
            if (SystemClock.elapsedRealtime() >= deadline) return false
            delay(SYSTEM_UI_ISOLATION_POLL_MS)
        }
        return false
    }

    private fun clearReleasedTestWindowIsolationToken(token: Long): Boolean {
        if (activeTestWindowIsolationToken != token) return false
        val restored = runCatching {
            testWindowIsolation.releaseStatus(token) == ReleaseStatus.RESTORED
        }.getOrDefault(false)
        if (restored && activeTestWindowIsolationToken == token) {
            activeTestWindowIsolationToken = null
        }
        return restored
    }

    private fun ensureTestWindowIsolationReleased(token: Long): Deferred<Boolean> {
        val existing = isolationReleaseDeferred
        if (
            isolationReleaseToken == token &&
            existing != null &&
            !existing.isCompleted
        ) {
            return existing
        }
        val deferred = isolationCleanupScope.async {
            if (activeTestWindowIsolationToken != token) return@async false
            val deadline = saturatingAdd(
                SystemClock.elapsedRealtime(),
                SYSTEM_UI_RESTORE_TIMEOUT_MS,
            )
            var attempts = 0
            var nextAttemptMs = 0L
            while (currentCoroutineContext().isActive) {
                if (clearReleasedTestWindowIsolationToken(token)) return@async true
                if (activeTestWindowIsolationToken != token) return@async false
                val now = SystemClock.elapsedRealtime()
                if (now >= deadline) break
                if (
                    attempts < SYSTEM_UI_RESTORE_MAX_ATTEMPTS &&
                    now >= nextAttemptMs
                ) {
                    runCatching {
                        testWindowIsolation.release(token)
                    }
                    attempts += 1
                    nextAttemptMs = saturatingAdd(
                        now,
                        SYSTEM_UI_RESTORE_RETRY_MS,
                    )
                    if (clearReleasedTestWindowIsolationToken(token)) return@async true
                }
                delay(SYSTEM_UI_ISOLATION_POLL_MS)
            }
            clearReleasedTestWindowIsolationToken(token)
        }
        isolationReleaseToken = token
        isolationReleaseDeferred = deferred
        return deferred
    }

    private fun beginTestWindowIsolationReleaseAfterRendererTeardown(
        token: Long,
    ): Deferred<Boolean> {
        val existing = isolationBarrierReleaseDeferred
        if (
            isolationBarrierReleaseToken == token &&
            existing != null &&
            !existing.isCompleted
        ) {
            return existing
        }
        return isolationCleanupScope.async {
            releaseTestWindowIsolationAfterRendererTeardown(token)
        }.also { deferred ->
            isolationBarrierReleaseToken = token
            isolationBarrierReleaseDeferred = deferred
        }
    }

    private fun closeIsolationCleanupScopeAfter(deferred: Deferred<Boolean>) {
        deferred.invokeOnCompletion {
            mainHandler.post {
                isolationCleanupScope.cancel()
            }
        }
    }

    private suspend fun releaseTestWindowIsolationAfterRendererTeardown(
        token: Long,
    ): Boolean {
        if (activeTestWindowIsolationToken != token) return false
        return when (
            releaseIsolationAfterRendererBarrier(
                awaitRendererTeardown = ::awaitRendererTeardownBarrier,
                releaseIsolation = { releaseTestWindowIsolation(token) },
            )
        ) {
            BarrierGatedIsolationReleaseResult.RELEASED -> true
            BarrierGatedIsolationReleaseResult.RELEASE_FAILED -> false
            BarrierGatedIsolationReleaseResult.RENDERER_UNCONFIRMED -> {
                errorMessage =
                    (
                        "Physical producer teardown이 확인되지 않아 SystemUI 복원을 보류했습니다. " +
                            "새 plan은 process 재시작 전 차단됩니다."
                        ).take(MAX_EVENT_MESSAGE_CHARS)
                false
            }
        }
    }

    private suspend fun releaseTestWindowIsolation(token: Long): Boolean {
        if (activeTestWindowIsolationToken != token) return false
        val released = runCatching {
            ensureTestWindowIsolationReleased(token).await()
        }.getOrDefault(false)
        if (!released) {
            errorMessage =
                "SystemUI 복원 확인 실패 · 새 plan은 process 재시작 전 차단됩니다."
                    .take(MAX_EVENT_MESSAGE_CHARS)
        }
        return released
    }

    private fun setWakeState(awake: Boolean) {
        if (awake) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            screenAwake = true
        } else {
            if (keepScreenOnInitially) {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            screenAwake = false
        }
    }

    private suspend fun openPinnedMediaSource(
        uri: Uri,
        preflightLease: MediaPreflightLease,
    ): PinnedMediaSource {
        val resolver = activity.applicationContext.contentResolver
        val completed = CountDownLatch(1)
        val cancellationSignal = CancellationSignal()
        val abandoned = AtomicBoolean(false)
        val opened = AtomicReference<AssetFileDescriptor?>()
        val failure = AtomicReference<Throwable?>()
        val workerLease = preflightLease.retain()
            ?: throw PlanAbortException("media preflight lease가 실행 전에 해제됐습니다.")
        val worker = try {
            Thread(
                {
                    var local: AssetFileDescriptor? = null
                    try {
                        local = resolver.openAssetFileDescriptor(uri, "r", cancellationSignal)
                        val openedDescriptor = local
                        if (openedDescriptor == null) {
                            failure.compareAndSet(
                                null,
                                IllegalArgumentException("Provider returned no descriptor"),
                            )
                        } else if (abandoned.get()) {
                            closePinnedMediaDescriptor(openedDescriptor)
                            local = null
                        } else {
                            opened.set(openedDescriptor)
                            local = null
                            if (abandoned.get()) {
                                opened.getAndSet(null)?.let(::closePinnedMediaDescriptor)
                            }
                        }
                    } catch (error: Throwable) {
                        if (error is ThreadDeath) throw error
                        failure.compareAndSet(null, error)
                    } finally {
                        try {
                            local?.let(::closePinnedMediaDescriptor)
                        } finally {
                            completed.countDown()
                            workerLease.close()
                        }
                    }
                },
                "DpuLab-MediaProviderOpen",
            ).apply {
                isDaemon = true
            }
        } catch (error: Throwable) {
            workerLease.close()
            if (error is ThreadDeath) throw error
            throw PlanAbortException(
                "media provider worker를 생성할 수 없습니다 (${error.javaClass.simpleName}).",
            )
        }
        try {
            worker.start()
        } catch (error: Throwable) {
            workerLease.close()
            if (error is ThreadDeath) throw error
            throw PlanAbortException(
                "media provider worker를 시작할 수 없습니다 (${error.javaClass.simpleName}).",
            )
        }
        fun abandonProviderOpen() {
            abandoned.set(true)
            try {
                opened.getAndSet(null)?.let(::closePinnedMediaDescriptor)
            } finally {
                try {
                    try {
                        cancellationSignal.cancel()
                    } catch (error: Throwable) {
                        if (error is ThreadDeath) throw error
                    }
                } finally {
                    worker.interrupt()
                }
            }
        }
        val completedInTime = try {
            awaitLatchCancellable(completed, MEDIA_PROVIDER_OPEN_TIMEOUT_MS)
        } catch (cancelled: CancellationException) {
            abandonProviderOpen()
            throw cancelled
        }
        if (!completedInTime) {
            abandonProviderOpen()
            throw PlanAbortException(
                "media provider가 ${MEDIA_PROVIDER_OPEN_TIMEOUT_MS}ms 안에 descriptor를 " +
                "반환하지 않아 안전 중단했습니다.",
            )
        }
        try {
            currentCoroutineContext().ensureActive()
        } catch (cancelled: CancellationException) {
            // Cancellation can arrive after the provider worker has published its descriptor but
            // before ownership is transferred below. Reclaim that completed result as well as a
            // still-running provider open.
            abandonProviderOpen()
            throw cancelled
        }
        failure.get()?.let { error ->
            throw UnsupportedRunException(
                "선택한 영상 descriptor를 열 수 없습니다 (${error.javaClass.simpleName}).",
            )
        }
        val descriptor = opened.getAndSet(null)
            ?: throw UnsupportedRunException("선택한 영상 descriptor가 비어 있습니다.")
        return try {
            constructWithOwnedCloseOnFailure(
                resource = descriptor,
                onCloseFailure = { cleanupError ->
                    PinnedMediaCleanupState.markUnconfirmed(
                        cleanupError.javaClass.simpleName.ifBlank {
                            cleanupError.javaClass.name
                        },
                    )
                },
            ) { ownedDescriptor ->
                PinnedMediaSource(uri, ownedDescriptor)
            }
        } catch (error: Exception) {
            throw UnsupportedRunException(
                "seek 가능한 고정 영상 descriptor가 필요합니다 (${error.javaClass.simpleName}).",
            )
        }
    }

    private suspend fun inspectPinnedTrackBounded(
        source: PinnedMediaSource,
        preflightLease: MediaPreflightLease,
    ): MediaTrackInspection {
        val completed = CountDownLatch(1)
        val result = AtomicReference<MediaTrackInspection?>()
        val failure = AtomicReference<Throwable?>()
        val workerLease = preflightLease.retain()
            ?: throw PlanAbortException("media preflight lease가 parser 실행 전에 해제됐습니다.")
        val worker = try {
            Thread(
                {
                    try {
                        result.set(inspectPinnedTrackOnCurrentThread(source))
                    } catch (error: Throwable) {
                        if (error is ThreadDeath) throw error
                        failure.compareAndSet(null, error)
                    } finally {
                        completed.countDown()
                        workerLease.close()
                    }
                },
                "DpuLab-MediaPreflight",
            ).apply {
                isDaemon = true
            }
        } catch (error: Throwable) {
            workerLease.close()
            if (error is ThreadDeath) throw error
            throw PlanAbortException(
                "media parser worker를 생성할 수 없습니다 (${error.javaClass.simpleName}).",
            )
        }
        try {
            worker.start()
        } catch (error: Throwable) {
            workerLease.close()
            if (error is ThreadDeath) throw error
            throw PlanAbortException(
                "media parser worker를 시작할 수 없습니다 (${error.javaClass.simpleName}).",
            )
        }

        fun abandonInspection() {
            worker.interrupt()
        }

        val completedInTime = try {
            awaitLatchCancellable(completed, MEDIA_INSPECTION_TIMEOUT_MS)
        } catch (cancelled: CancellationException) {
            abandonInspection()
            throw cancelled
        }
        if (!completedInTime) {
            abandonInspection()
            throw PlanAbortException(
                "media parser가 ${MEDIA_INSPECTION_TIMEOUT_MS}ms 안에 반환하지 않아 " +
                "안전 중단했습니다.",
            )
        }
        currentCoroutineContext().ensureActive()
        failure.get()?.let { error ->
            return MediaTrackInspection(
                detail = "track=${error.javaClass.simpleName.take(MAX_MEDIA_ERROR_TYPE_CHARS)}",
            )
        }
        return result.get()
            ?: MediaTrackInspection(detail = "track=empty result")
    }

    private fun inspectPinnedTrackOnCurrentThread(
        source: PinnedMediaSource,
    ): MediaTrackInspection {
        val extractor = MediaExtractor()
        try {
            source.openDuplicate().use { descriptor ->
                extractor.setDataSource(descriptor.resource)
            }
            val trackCount = extractor.trackCount
            require(trackCount in 1..MAX_MEDIA_TRACK_COUNT) {
                "Invalid or excessive media track count"
            }
            for (trackIndex in 0 until trackCount) {
                val format = extractor.getTrackFormat(trackIndex)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (!mime.startsWith("video/")) continue
                require(mime.length <= MAX_MEDIA_MIME_CHARS) { "Video MIME is too long" }
                val width = exactMediaIntegerOrNull(
                    format.numberOrNull(MediaFormat.KEY_WIDTH),
                )
                val height = exactMediaIntegerOrNull(
                    format.numberOrNull(MediaFormat.KEY_HEIGHT),
                )
                val declaredMaxWidth =
                    format.strictOptionalMediaIntegerOrNull(MediaFormat.KEY_MAX_WIDTH)
                val declaredMaxHeight =
                    format.strictOptionalMediaIntegerOrNull(MediaFormat.KEY_MAX_HEIGHT)
                val fixedMaximumDimensions = fixedVideoMaximumDimensionsMatch(
                    encodedWidthPx = width,
                    encodedHeightPx = height,
                    declaredMaxWidthPx = declaredMaxWidth,
                    declaredMaxHeightPx = declaredMaxHeight,
                )
                val visibleDimensions = visibleVideoDimensions(
                    encodedWidthPx = width,
                    encodedHeightPx = height,
                    cropLeft = format.strictOptionalMediaIntegerOrNull(
                MEDIA_KEY_CROP_LEFT_COMPAT,
                    ),
                    cropRight = format.strictOptionalMediaIntegerOrNull(
                MEDIA_KEY_CROP_RIGHT_COMPAT,
                    ),
                    cropTop = format.strictOptionalMediaIntegerOrNull(
                MEDIA_KEY_CROP_TOP_COMPAT,
                    ),
                    cropBottom = format.strictOptionalMediaIntegerOrNull(
                MEDIA_KEY_CROP_BOTTOM_COMPAT,
                    ),
                )
                val frameRate = format.numberOrNull(MediaFormat.KEY_FRAME_RATE)
                    ?.toFloat()
                    ?.takeIf { it.isFinite() && it > 0f }
                val profile = format.strictOptionalMediaIntegerOrNull(MediaFormat.KEY_PROFILE)
                val level = format.strictOptionalMediaIntegerOrNull(MediaFormat.KEY_LEVEL)
                val bitRate = format.strictOptionalMediaIntegerOrNull(MediaFormat.KEY_BIT_RATE)
                val maxInputSize =
                    format.strictOptionalMediaIntegerOrNull(MediaFormat.KEY_MAX_INPUT_SIZE)
                val rotationDegrees =
                    format.strictOptionalMediaIntegerOrNull(MediaFormat.KEY_ROTATION) ?: 0
                val rotationValid = rotationDegrees in setOf(0, 90, 180, 270)
                val rawCodecsString = if (
            format.containsKey(MEDIA_KEY_CODECS_STRING_COMPAT)
        ) {
            format.getString(MEDIA_KEY_CODECS_STRING_COMPAT)
                } else {
                    null
                }
                val codecStringValid =
                    rawCodecsString == null ||
                        (
                            rawCodecsString.isNotBlank() &&
                                rawCodecsString.length <= MAX_MEDIA_CODECS_STRING_CHARS
                            )
                val codecsString = rawCodecsString?.takeIf { codecStringValid }
                val codecConfigFingerprint = format.codecConfigFingerprintOrNull()
                val complete =
                    width != null &&
                        height != null &&
                        fixedMaximumDimensions &&
                        visibleDimensions != null &&
                        frameRate != null &&
                        (profile == null || profile > 0) &&
                        (level == null || level > 0) &&
                        (bitRate == null || bitRate > 0) &&
                        (
                            maxInputSize == null ||
                                maxInputSize in 1..MAX_BOUND_COMPRESSED_SAMPLE_BYTES
                            ) &&
                        rotationValid &&
                        codecStringValid &&
                        codecConfigFingerprint != null
                return MediaTrackInspection(
                    width = width,
                    height = height,
                    visibleWidth = visibleDimensions?.widthPx,
                    visibleHeight = visibleDimensions?.heightPx,
                    declaredMaxWidth = declaredMaxWidth,
                    declaredMaxHeight = declaredMaxHeight,
                    mime = mime,
                    frameRate = frameRate,
                    profile = profile,
                    level = level,
                    bitRate = bitRate,
                    maxInputSize = maxInputSize,
                    rotationDegrees = rotationDegrees,
                    codecsString = codecsString,
                    codecConfigFingerprint = codecConfigFingerprint,
                    trackFingerprintComplete = complete,
                    detail = if (complete) null else "track=incomplete fingerprint",
                )
            }
            return MediaTrackInspection(detail = "track=no video track")
        } finally {
            try {
                extractor.release()
            } catch (error: Throwable) {
                if (error is ThreadDeath) throw error
                PinnedMediaCleanupState.markUnconfirmed(
                    error.javaClass.simpleName.ifBlank { error.javaClass.name },
                )
                throw error
            }
        }
    }

    private suspend fun inspectMedia(
        uri: Uri,
        owner: AtomicReference<PinnedMediaSource?>,
    ): MediaInfo = withContext(Dispatchers.IO) {
        val preflightLease = MediaPreflightSafetyState.tryAcquire()
            ?: throw PlanAbortException(
                "다른 media provider/parser preflight가 실행 중이어서 decoder 실행을 차단합니다.",
            )
        try {
            val pinnedSource = openPinnedMediaSource(uri, preflightLease)
            check(owner.compareAndSet(null, pinnedSource)) {
                pinnedSource.close()
                "Another pinned media source is already owned"
            }
            try {
                val track = inspectPinnedTrackBounded(pinnedSource, preflightLease)
                // The descriptor itself is the authority. Avoid another provider Binder call for a
                // cosmetic display name after pinning; a hostile/remote provider could stall preflight.
                val displayName = uri.lastPathSegment
                    ?.take(MAX_MEDIA_DISPLAY_NAME_CHARS)
                    ?.takeIf(String::isNotBlank)
                    ?: "content URI"
                val decoderLinearReference = decoderLinearReferenceFor(
                    mime = track.mime,
                    profile = track.profile,
                    codecsString = track.codecsString,
                )
                val metadata = buildString {
                    append(track.mime ?: "mime?")
                    append(" ${track.width ?: "?"}x${track.height ?: "?"}")
                    if (track.declaredMaxWidth != null || track.declaredMaxHeight != null) {
                        append(
                            " max=${track.declaredMaxWidth ?: "?"}x" +
                                "${track.declaredMaxHeight ?: "?"}",
                        )
                    }
                    if (
                        track.visibleWidth != track.width ||
                        track.visibleHeight != track.height
                    ) {
                        append(
                            " visible=${track.visibleWidth ?: "?"}x" +
                                "${track.visibleHeight ?: "?"}",
                        )
                    }
                    append(" ${track.frameRate?.let { "%.2f".format(it) } ?: "?"}fps")
                    append(" profile=${track.profile ?: "?"}")
                    append(" level=${track.level ?: "?"}")
                    append(" bitrate=${track.bitRate ?: "?"}")
                    append(" maxInput=${track.maxInputSize ?: "?"}")
                    append(" rotation=${track.rotationDegrees}°")
                    append(" codecs=${track.codecsString ?: "?"}")
                    append(" · ${decoderLinearReference.label}")
                    append(" · referenceSource=${decoderLinearReference.source}")
                    track.detail?.let { append(" · $it") }
                }
                MediaInfo(
                    pinnedSource = pinnedSource,
                    readable = true,
                    width = track.width,
                    height = track.height,
                    visibleWidth = track.visibleWidth,
                    visibleHeight = track.visibleHeight,
                    declaredMaxWidth = track.declaredMaxWidth,
                    declaredMaxHeight = track.declaredMaxHeight,
                    mime = track.mime,
                    frameRate = track.frameRate,
                    profile = track.profile,
                    level = track.level,
                    bitRate = track.bitRate,
                    maxInputSize = track.maxInputSize,
                    rotationDegrees = track.rotationDegrees,
                    codecsString = track.codecsString,
                    codecConfigFingerprint = track.codecConfigFingerprint,
                    trackFingerprintComplete = track.trackFingerprintComplete,
                    decoderLinearReference = decoderLinearReference,
                    description = "$displayName · $metadata",
                )
            } catch (error: Exception) {
                owner.compareAndSet(pinnedSource, null)
                pinnedSource.close()
                throw error
            }
        } finally {
            preflightLease.close()
        }
    }

    private fun validateRequestedMedia(
        scenario: ScenarioSpec,
        mediaUri: Uri?,
        media: MediaInfo?,
    ): MediaValidationResult {
        val decoderPhases = scenario.phases.filter(::canUseVideoPrimary)
        if (decoderPhases.isEmpty()) return MediaValidationResult()
        val boundMediaUri = mediaUri
            ?: return MediaValidationResult(error = "검증할 영상 URI가 없습니다.")
        val boundMedia = media
            ?: return MediaValidationResult(error = "검증할 영상 metadata가 없습니다.")
        if (!boundMedia.trackFingerprintComplete) {
            return MediaValidationResult(
                error = "decoder 실행에 필요한 track 해상도/crop/FPS/codec-config fingerprint를 " +
                    "안전하게 확인할 수 없습니다.",
            )
        }
        val width = boundMedia.width
            ?: return MediaValidationResult(error = "영상 해상도 metadata를 확인할 수 없습니다.")
        val height = boundMedia.height
            ?: return MediaValidationResult(error = "영상 해상도 metadata를 확인할 수 없습니다.")
        val visibleWidth = boundMedia.visibleWidth
            ?: return MediaValidationResult(error = "영상 visible width를 확인할 수 없습니다.")
        val visibleHeight = boundMedia.visibleHeight
            ?: return MediaValidationResult(error = "영상 visible height를 확인할 수 없습니다.")
        if (
            !fixedVideoMaximumDimensionsMatch(
                encodedWidthPx = width,
                encodedHeightPx = height,
                declaredMaxWidthPx = boundMedia.declaredMaxWidth,
                declaredMaxHeightPx = boundMedia.declaredMaxHeight,
            )
        ) {
            return MediaValidationResult(
                error = "adaptive/dynamic-resolution 영상은 고정 graphics budget으로 " +
                    "안전하게 실행할 수 없습니다.",
            )
        }
        val longEdge = max(visibleWidth, visibleHeight)
        val shortEdge = min(visibleWidth, visibleHeight)
        val requiredSize = decoderPhases
            .map { it.bufferSize }
            .filter { it != BufferSize.DISPLAY }
            .maxByOrNull { it.width.toLong() * it.height.toLong() }
        if (
            requiredSize != null &&
            (longEdge < requiredSize.width || shortEdge < requiredSize.height)
        ) {
            return MediaValidationResult(
                error = "선택 영상 visible 영역은 ${visibleWidth}x${visibleHeight}이며 " +
                    "(encoded=${width}x$height) phase 요구사항" +
                    "(${requiredSize.width}x${requiredSize.height})에 미달합니다.",
            )
        }
        val mime = boundMedia.mime
            ?: return MediaValidationResult(error = "영상 MIME metadata를 확인할 수 없습니다.")
        val requiredFps = requiredDecoderSourceFps(scenario.phases)
            ?: return MediaValidationResult(
                error = "decoder phase의 reachable producer FPS를 계산할 수 없습니다.",
            )
        val sourceFps = boundMedia.frameRate
            ?: return MediaValidationResult(
                error = "영상 FPS metadata를 확인할 수 없어 decoder phase를 실행하지 않습니다.",
            )
        if (!selectedMediaFpsMeetsRequirement(sourceFps, requiredFps, MEDIA_FPS_TOLERANCE)) {
            return MediaValidationResult(
                error = "선택 영상은 ${"%.2f".format(sourceFps)}fps이며 phase 요청 " +
                    "${"%.2f".format(requiredFps)}fps에 미달합니다.",
            )
        }
        val decoderCapabilityFps = decoderCapabilityRate(sourceFps, requiredFps)
            ?: return MediaValidationResult(
                error = "영상 또는 phase FPS metadata가 유효하지 않습니다.",
            )
        val hasP010Phase = decoderPhases.any { it.pixelRoute == PixelRoute.P010 }
        if (
            hasP010Phase &&
            !isVerifiedP010VideoProfile(
                mime = mime,
                profile = boundMedia.profile,
                codecsString = boundMedia.codecsString,
            )
        ) {
            return MediaValidationResult(
                error = "P010 phase에는 extractor가 확인한 10-bit 4:2:0 영상이 필요합니다. " +
                    "VP9은 vp09 codec string의 bit-depth 10도 확인되어야 합니다.",
            )
        }
        val compressedSampleLimit = compressedSampleSafetyLimit(
            maxGraphicsBytes = safetyLimits.maxGraphicsBytes,
        ) ?: return MediaValidationResult(
            error = "기기의 decoder compressed-sample 안전 한도를 계산할 수 없습니다.",
        )
        if (
            boundMedia.maxInputSize != null &&
            boundMedia.maxInputSize > compressedSampleLimit
        ) {
            return MediaValidationResult(
                error = "영상 max-input-size ${boundMedia.maxInputSize}B가 현재 기기 안전 한도 " +
                    "${compressedSampleLimit}B를 초과합니다.",
            )
        }
        // The concrete input format is configured unchanged. Bind every advertised optional
        // profile/level/bitrate key, not only P010, to the chosen codec's format support query.
        val requiredProfile = boundMedia.profile
        val hardwareCodecName = CapabilityScanner.findHardwareVideoDecoder(
            mime = mime,
            width = width,
            height = height,
            // MediaCodec receives the selected track's original format. A 120 fps source used
            // by a 60 fps phase must therefore be assigned to a codec that advertises 120 fps,
            // even though output release is intentionally paced at the lower phase target.
            framesPerSecond = decoderCapabilityFps,
            requiredProfile = requiredProfile,
            requiredLevel = boundMedia.level,
            bitRate = boundMedia.bitRate,
        )
        if (hardwareCodecName == null) {
            val profileDetail = requiredProfile?.let { " profile=$it" }.orEmpty()
            return MediaValidationResult(
                error = "$mime ${width}x${height} @ ${decoderCapabilityFps}fps$profileDetail 를 " +
                    "지원하는 " +
                    "hardware decoder가 없습니다.",
            )
        }
        return MediaValidationResult(
            decoderSelection = VideoDecoderSelection(
                mediaUri = boundMediaUri,
                pinnedSource = boundMedia.pinnedSource,
                mime = mime,
                codecName = hardwareCodecName,
                expectedEncodedWidthPx = width,
                expectedEncodedHeightPx = height,
                expectedDeclaredMaxWidthPx = boundMedia.declaredMaxWidth,
                expectedDeclaredMaxHeightPx = boundMedia.declaredMaxHeight,
                expectedVisibleWidthPx = visibleWidth,
                expectedVisibleHeightPx = visibleHeight,
                expectedSourceFps = sourceFps,
                expectedProfile = boundMedia.profile,
                expectedLevel = boundMedia.level,
                expectedBitRate = boundMedia.bitRate,
                expectedMaxInputSize = boundMedia.maxInputSize,
                expectedRotationDegrees = boundMedia.rotationDegrees,
                expectedCodecsString = boundMedia.codecsString,
                codecConfigFingerprint = checkNotNull(boundMedia.codecConfigFingerprint),
                maxCompressedSampleBytes = compressedSampleLimit,
                requiresVerifiedP010 = hasP010Phase,
            ),
        )
    }

    private fun validateBufferCapabilities(
        scenario: ScenarioSpec,
        mediaSelected: Boolean,
    ): String? {
        for (phase in scenario.phases) {
            if (
                phase.backend == LayerBackend.FLATTENED_TEXTURE ||
                phase.bufferSize == BufferSize.DISPLAY ||
                (phase.includeGlLayer && phase.activeLayers == 1)
            ) {
                continue
            }
            val usesDecoder = mediaSelected && canUseVideoPrimary(phase)
            if (usesDecoder) continue
            val rgb565 = phase.pixelRoute == PixelRoute.RGB_565 && !phase.alphaOverlap
            if (
                !CapabilityScanner.supportsCanvasBuffer(
                    phase.bufferSize.width,
                    phase.bufferSize.height,
                    rgb565,
                )
            ) {
                val format = if (rgb565) "RGB565" else "RGBA8888"
                return "Phase '${phase.id}'의 ${phase.bufferSize.label} $format " +
                    "BufferQueue를 이 기기에서 지원하지 않습니다."
            }
        }
        return null
    }

    private fun canUseVideoPrimary(phase: PhaseSpec): Boolean =
        phase.requiresSelectedDecoderProducer()

    private fun ScenarioRunArtifact.toPlanResult(
        runIndex: Int,
        repeatIndex: Int,
        queueIndex: Int,
    ) = PlanRunResult(
        runIndex = runIndex,
        repeatIndex = repeatIndex,
        queueIndex = queueIndex,
        scenario = summary.scenario,
        verdict = summary.verdict,
        startedEpochMs = summary.startedEpochMs,
        finishedEpochMs = summary.finishedEpochMs,
        exactUnderrunDelta = summary.exactUnderrunDelta,
        suspectedUnderrunDelta = summary.suspectedUnderrunDelta,
        reportPath = reportFile?.absolutePath,
        terminalReason = summary.terminalReason(MAX_TERMINAL_REASON_CHARS),
    )

    private data class ScenarioRunArtifact(
        val summary: RunSummary,
        val reportFile: File?,
    )

    private data class MediaTrackInspection(
        val width: Int? = null,
        val height: Int? = null,
        val visibleWidth: Int? = null,
        val visibleHeight: Int? = null,
        val declaredMaxWidth: Int? = null,
        val declaredMaxHeight: Int? = null,
        val mime: String? = null,
        val frameRate: Float? = null,
        val profile: Int? = null,
        val level: Int? = null,
        val bitRate: Int? = null,
        val maxInputSize: Int? = null,
        val rotationDegrees: Int = 0,
        val codecsString: String? = null,
        val codecConfigFingerprint: String? = null,
        val trackFingerprintComplete: Boolean = false,
        val detail: String? = null,
    )

    private data class MediaInfo(
        val pinnedSource: PinnedMediaSource,
        val readable: Boolean,
        val width: Int?,
        val height: Int?,
        val visibleWidth: Int?,
        val visibleHeight: Int?,
        val declaredMaxWidth: Int?,
        val declaredMaxHeight: Int?,
        val mime: String?,
        val frameRate: Float?,
        val profile: Int?,
        val level: Int?,
        val bitRate: Int?,
        val maxInputSize: Int?,
        val rotationDegrees: Int,
        val codecsString: String?,
        val codecConfigFingerprint: String?,
        val trackFingerprintComplete: Boolean,
        val decoderLinearReference: DecoderLinearReference,
        val description: String,
    )

    private data class MediaValidationResult(
        val decoderSelection: VideoDecoderSelection? = null,
        val error: String? = null,
    )

    private class UnsupportedRunException(message: String) : Exception(message)

    private companion object {
        val ACTIVE_STAGES = setOf(
            RunnerStage.PRECHECK,
            RunnerStage.WARMUP,
            RunnerStage.RUNNING,
            RunnerStage.COOLDOWN,
        )
        const val PROXY_COUNTER_SOURCE = "FrameTracker"
        const val MONITOR_INTERVAL_MS = 1_000L
        const val WATCHDOG_INTERVAL_MS = 500L
        const val MONITOR_STALE_TIMEOUT_MS = 5_000L
        const val PRECHECK_DELAY_MS = 700L
        const val WARMUP_DELAY_MS = 1_200L
        const val HWC_CAPACITY_CALIBRATION_STABILIZE_MS = 100L
        const val HWC_CAPACITY_CALIBRATION_SAMPLE_RESERVE_MS = 50L
        const val HWC_CAPACITY_CALIBRATION_SETTLE_MS = 3_000L
        const val HWC_CAPACITY_CALIBRATION_TELEMETRY_DRAIN_TIMEOUT_MS = 7_500L
        const val SYSTEM_UI_ISOLATION_TIMEOUT_MS = 3_000L
        const val SYSTEM_UI_ISOLATION_POLL_MS = 16L
        const val SYSTEM_UI_RESTORE_TIMEOUT_MS = 3_000L
        const val SYSTEM_UI_RESTORE_RETRY_MS = 250L
        const val SYSTEM_UI_RESTORE_MAX_ATTEMPTS = 8
        const val RENDERER_CONTAINER_DISPOSE_TIMEOUT_MS = 6_000L
        const val PERFORMANCE_POLICY_PROPAGATION_TIMEOUT_MS = 1_000L
        const val PERFORMANCE_POLICY_POLL_MS = 50L
        const val PERFORMANCE_RENEWAL_JOIN_TIMEOUT_MS = 2_500L
        const val POWER_RECEIVER_UNREGISTER_ATTEMPTS = 2
        const val PROGRESS_INTERVAL_MS = LOAD_CONTROL_CADENCE_MS
        const val DISPLAY_REQUEST_EPSILON_HZ = 0.05f
        const val PRODUCER_STARTUP_GRACE_MS = 3_000L
        const val PRODUCER_RECOVERY_TIMEOUT_MS = 5_000L
        const val COOLDOWN_DELAY_MS = 2_000L
        const val RENDERER_TEARDOWN_ACK_TIMEOUT_MS = PRODUCER_RECOVERY_TIMEOUT_MS
        const val RENDERER_TEARDOWN_POLL_MS = 16L
        const val LAYER_GEOMETRY_COVERAGE_ACK_TIMEOUT_MS = 500L
        const val MAX_TELEMETRY_HISTORY = 60
        const val MAX_RUN_SAMPLES = 3_600
        const val MAX_EVENT_MESSAGE_CHARS = 1_000
        const val MAX_PRODUCER_RUNTIME_FAILURE_CHARS = 240
        const val MAX_TERMINAL_REASON_CHARS = 300
        const val MAX_MEDIA_DISPLAY_NAME_CHARS = 160
        const val MAX_MEDIA_ERROR_TYPE_CHARS = 80
        const val MAX_MEDIA_MIME_CHARS = 127
        const val MAX_MEDIA_CODECS_STRING_CHARS = 512
        const val MAX_MEDIA_TRACK_COUNT = 128
        const val MEDIA_FPS_TOLERANCE = 0.5f
        const val MEDIA_PROVIDER_OPEN_TIMEOUT_MS = 5_000L
        const val MEDIA_INSPECTION_TIMEOUT_MS = 10_000L
        const val VENDOR_CAPABILITY_WAIT_MS = 2_000L
        const val VENDOR_CAPABILITY_POLL_MS = 25L
        val EXACT_COUNTER_QUALITIES = setOf(
            MetricQuality.HARDWARE_COUNTER,
            MetricQuality.KERNEL,
        )
    }
}

private fun MediaFormat.numberOrNull(key: String): Number? =
    if (containsKey(key)) runCatching { getNumber(key) }.getOrNull() else null

private fun MediaFormat.mediaIntegerOrNull(key: String): Int? =
    exactMediaIntegerOrNull(numberOrNull(key))

private fun MediaFormat.strictOptionalMediaIntegerOrNull(key: String): Int? {
    if (!containsKey(key)) return null
    return mediaIntegerOrNull(key)
        ?: throw IllegalArgumentException("Invalid integer MediaFormat key: $key")
}

private fun MediaFormat.codecConfigFingerprintOrNull(): String? {
    val codecConfigKeys = keys
        .filter { it.startsWith("csd-") }
        .sorted()
    val entries = ArrayList<Pair<String, java.nio.ByteBuffer>>(codecConfigKeys.size)
    for (key in codecConfigKeys) {
        val buffer = runCatching { getByteBuffer(key) }.getOrNull() ?: return null
        entries += key to buffer
    }
    return boundedCodecConfigFingerprint(entries)
}

internal fun selectedMediaFpsMeetsRequirement(
    sourceFps: Float?,
    requiredFps: Float,
    toleranceFps: Float,
): Boolean {
    val source = sourceFps?.takeIf { it.isFinite() && it > 0f } ?: return false
    val required = requiredFps.takeIf { it.isFinite() && it > 0f } ?: return false
    val tolerance = toleranceFps.takeIf { it.isFinite() && it >= 0f } ?: 0f
    return source + tolerance >= required
}

internal fun decoderCapabilityRate(sourceFps: Float, requestedOutputFps: Float): Float? {
    val source = sourceFps.takeIf { it.isFinite() && it > 0f } ?: return null
    val requested = requestedOutputFps.takeIf { it.isFinite() && it > 0f } ?: return null
    return max(source, requested)
}

private const val MAX_COMPRESSED_SAMPLE_GRAPHICS_DIVISOR = 8L

/**
 * Reserve at most one eighth of the already device/available-memory-derived graphics envelope for
 * each compressed access unit. This leaves room for several codec input buffers and native codec
 * bookkeeping while retaining the absolute malformed-container ceiling.
 */
internal fun compressedSampleSafetyLimit(maxGraphicsBytes: Long): Int? {
    if (maxGraphicsBytes <= 0L) return null
    val deviceBound = maxGraphicsBytes / MAX_COMPRESSED_SAMPLE_GRAPHICS_DIVISOR
    return min(
        MAX_BOUND_COMPRESSED_SAMPLE_BYTES.toLong(),
        deviceBound,
    ).takeIf { it in 1L..Int.MAX_VALUE.toLong() }?.toInt()
}

internal fun decoderLinearReferenceFor(
    mime: String?,
    profile: Int?,
    codecsString: String? = null,
): DecoderLinearReference {
    val source =
        "MediaExtractor MIME/profile/codecs; MediaCodec Surface allocation is vendor-controlled"
    if (
        mime != null &&
        profile != null &&
        isVerifiedP010VideoProfile(mime, profile, codecsString)
    ) {
        return DecoderLinearReference(
            bytesPerPixel = 3.0,
            label = "10-bit 4:2:0 16-bit-plane linear reference · 3 B/px",
            source = source,
        )
    }
    if (isVerifiedEightBitYuv420Profile(mime, profile)) {
        return DecoderLinearReference(
            bytesPerPixel = 1.5,
            label = "YUV420 linear reference · 1.5 B/px",
            source = source,
        )
    }
    return DecoderLinearReference(
        bytesPerPixel = null,
        label = "decoder linear reference N/A",
        source = source,
    )
}

private fun isVerifiedEightBitYuv420Profile(mime: String?, profile: Int?): Boolean = when {
    mime.equals("video/avc", ignoreCase = true) -> when (profile) {
        MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
        MediaCodecInfo.CodecProfileLevel.AVCProfileMain,
        MediaCodecInfo.CodecProfileLevel.AVCProfileExtended,
        MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
        MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline,
        MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedHigh,
        -> true
        else -> false
    }
    mime.equals("video/hevc", ignoreCase = true) ->
        profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
    mime.equals("video/x-vnd.on2.vp9", ignoreCase = true) ->
        profile == MediaCodecInfo.CodecProfileLevel.VP9Profile0
    mime.equals("video/av01", ignoreCase = true) ->
        profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8
    // VP8 Surface decode is specified as 8-bit 4:2:0 and commonly omits KEY_PROFILE.
    mime.equals("video/x-vnd.on2.vp8", ignoreCase = true) -> true
    else -> false
}

internal fun isVerifiedTenBitVideoProfile(mime: String, profile: Int?): Boolean {
    profile ?: return false
    return when {
        mime.equals("video/hevc", ignoreCase = true) -> when (profile) {
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10,
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus,
            -> true
            else -> false
        }

        // VP9 profile 2 permits both 10-bit and 12-bit bitstreams. Android represents both
        // through the same profile constants, so profile alone cannot establish P010 semantics.
        mime.equals("video/x-vnd.on2.vp9", ignoreCase = true) -> false

        mime.equals("video/av01", ignoreCase = true) -> when (profile) {
            MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10,
            MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10,
            MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10Plus,
            -> true
            else -> false
        }

        mime.equals("video/avc", ignoreCase = true) ->
            profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10

        // KEY_PROFILE alone does not establish Dolby Vision's exact 10/12-bit layout, so neither
        // the P010 gate nor the linear-byte estimator may claim a P010-compatible allocation.
        mime.equals("video/dolby-vision", ignoreCase = true) -> false
        else -> false
    }
}

internal fun isVerifiedP010VideoProfile(
    mime: String,
    profile: Int?,
    codecsString: String?,
): Boolean {
    if (!mime.equals("video/x-vnd.on2.vp9", ignoreCase = true)) {
        return isVerifiedTenBitVideoProfile(mime, profile)
    }
    val verifiedProfile = when (profile) {
        MediaCodecInfo.CodecProfileLevel.VP9Profile2,
        MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR,
        MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR10Plus,
        -> true
        else -> false
    }
    return verifiedProfile && vp9BitDepthFromCodecsString(codecsString) == 10
}

/**
 * Reads the mandatory bitDepth field from a canonical VP9 RFC 6381 codec string.
 *
 * KEY_PROFILE cannot distinguish VP9 profile-2 10-bit from optional 12-bit content. Conflicting
 * VP9 entries, non-canonical profile fields, and oversized/malformed strings therefore return
 * null rather than guessing.
 */
internal fun vp9BitDepthFromCodecsString(codecsString: String?): Int? {
    val value = codecsString
        ?.takeIf { it.length in 1..MAX_VP9_CODECS_PARSE_CHARS }
        ?: return null
    var bitDepth: Int? = null
    value.split(',').forEach { codec ->
        val fields = codec.trim().split('.')
        if (fields.firstOrNull()?.equals("vp09", ignoreCase = true) != true) {
            return@forEach
        }
        if (fields.size != 4 && fields.size != 9) return null
        if (fields.drop(1).any { !it.isCanonicalVp9Field() }) return null
        if (fields[1] != "02" || fields[2] !in VALID_VP9_LEVEL_FIELDS) return null
        if (fields.size == 9) {
            if (fields[4] !in VALID_VP9_CHROMA_FIELDS) return null
            if (fields[8] != "00" && fields[8] != "01") return null
        }
        val parsed = fields[3]
            .toIntOrNull()
            ?.takeIf { it == 10 || it == 12 }
            ?: return null
        if (bitDepth != null && bitDepth != parsed) return null
        bitDepth = parsed
    }
    return bitDepth
}

private fun String.isCanonicalVp9Field(): Boolean =
    length == 2 && all { it in '0'..'9' }

internal fun underrunVerdict(
    exactDelta: Long?,
    suspectedProxyDelta: Long,
): RunVerdict = when {
    exactDelta != null && exactDelta > 0L -> RunVerdict.UNDERRUN_DETECTED
    exactDelta == 0L -> RunVerdict.CLEAN
    exactDelta != null -> RunVerdict.INCONCLUSIVE
    suspectedProxyDelta > 0L -> RunVerdict.SUSPECTED_PROXY
    else -> RunVerdict.INCONCLUSIVE
}

private const val MAX_VP9_CODECS_PARSE_CHARS = 512
private val VALID_VP9_LEVEL_FIELDS = setOf(
    "10",
    "11",
    "20",
    "21",
    "30",
    "31",
    "40",
    "41",
    "50",
    "51",
    "52",
    "60",
    "61",
    "62",
)
private val VALID_VP9_CHROMA_FIELDS = setOf("00", "01", "02", "03")

internal data class ExactDeltaProvenance(
    val source: String?,
    val quality: MetricQuality,
)

internal fun exactDeltaProvenance(
    exactDelta: Long?,
    baselineSource: String?,
    baselineQuality: MetricQuality,
): ExactDeltaProvenance {
    val valid = exactDelta != null &&
        exactDelta >= 0L &&
        !baselineSource.isNullOrBlank() &&
        baselineQuality in setOf(MetricQuality.HARDWARE_COUNTER, MetricQuality.KERNEL)
    return if (valid) {
        ExactDeltaProvenance(
            source = baselineSource,
            quality = baselineQuality,
        )
    } else {
        ExactDeltaProvenance(
            source = null,
            quality = MetricQuality.UNAVAILABLE,
        )
    }
}

internal fun shouldCollectFinalTelemetrySample(
    preselectedVerdict: RunVerdict?,
    rendererReleased: Boolean,
): Boolean = preselectedVerdict == null && rendererReleased

internal fun finalVerdictAfterTeardown(
    preselectedVerdict: RunVerdict?,
    rendererReleased: Boolean,
    cancellationPresent: Boolean,
    derivedVerdict: RunVerdict,
): RunVerdict = when {
    !rendererReleased || cancellationPresent -> RunVerdict.ABORTED
    preselectedVerdict != null -> preselectedVerdict
    else -> derivedVerdict
}

internal data class GaugePeak(
    val value: Float?,
    val provenanceChanged: Boolean,
)

internal fun consistentGaugePeak(
    samples: List<TelemetrySnapshot>,
    selector: (TelemetrySnapshot) -> Gauge,
    validRange: ClosedFloatingPointRange<Float>,
): GaugePeak {
    var peak: Float? = null
    var firstQuality: MetricQuality? = null
    var firstSource: String? = null
    for (sample in samples) {
        val gauge = selector(sample)
        val value = gauge.value
            ?.takeIf(Float::isFinite)
            ?.takeIf { it in validRange }
            ?: continue
        if (gauge.quality == MetricQuality.UNAVAILABLE || gauge.source.isBlank()) continue
        if (firstQuality == null) {
            firstQuality = gauge.quality
            firstSource = gauge.source
        } else if (gauge.quality != firstQuality || gauge.source != firstSource) {
            return GaugePeak(value = null, provenanceChanged = true)
        }
        peak = peak?.let { max(it, value) } ?: value
    }
    return GaugePeak(value = peak, provenanceChanged = false)
}

internal enum class CompressionTransitionFailure {
    NONE,
    REQUIRED_ADAPTER_MISSING,
    ACTIVE_ROUTE_ADAPTER_LOST,
    REJECTED_OR_TIMEOUT,
}

internal data class CompressionTransitionDecision(
    val activeAfter: Boolean,
    val failure: CompressionTransitionFailure,
)

internal fun compressionStateAtAttemptStart(
    route: PixelRoute,
    activeBefore: Boolean,
): Boolean =
    activeBefore || route == PixelRoute.SBWC_AUTO || route == PixelRoute.SBWC_REQUIRED

internal fun compressionSessionContinuityLost(
    compressionControlActive: Boolean,
    activeRoute: PixelRoute?,
    expectedSession: Long?,
    observedSession: Long?,
): Boolean =
    compressionControlActive &&
        activeRoute in setOf(PixelRoute.SBWC_AUTO, PixelRoute.SBWC_REQUIRED) &&
        (expectedSession == null || observedSession != expectedSession)

internal fun decideCompressionTransition(
    route: PixelRoute,
    result: CompressionControlResult,
    activeBefore: Boolean,
): CompressionTransitionDecision = when (result) {
    CompressionControlResult.APPLIED -> CompressionTransitionDecision(
        activeAfter = route in setOf(PixelRoute.SBWC_AUTO, PixelRoute.SBWC_REQUIRED),
        failure = CompressionTransitionFailure.NONE,
    )

    CompressionControlResult.NO_ADAPTER -> when {
        activeBefore -> CompressionTransitionDecision(
            activeAfter = true,
            failure = CompressionTransitionFailure.ACTIVE_ROUTE_ADAPTER_LOST,
        )
        route == PixelRoute.SBWC_REQUIRED -> CompressionTransitionDecision(
            activeAfter = false,
            failure = CompressionTransitionFailure.REQUIRED_ADAPTER_MISSING,
        )
        else -> CompressionTransitionDecision(
            activeAfter = false,
            failure = CompressionTransitionFailure.NONE,
        )
    }

    CompressionControlResult.REJECTED_OR_TIMEOUT -> CompressionTransitionDecision(
        // A timeout cannot prove that the remote command was not applied. Treat a failed
        // non-linear request as potentially active until a confirmed linear reset succeeds.
        activeAfter = activeBefore ||
            route == PixelRoute.SBWC_AUTO ||
            route == PixelRoute.SBWC_REQUIRED,
        failure = CompressionTransitionFailure.REJECTED_OR_TIMEOUT,
    )
}

internal fun monotonicCounterDelta(
    baselineValue: Long?,
    baselineSource: String?,
    currentValue: Long?,
    currentSource: String?,
): Long? {
    baselineValue ?: return null
    currentValue ?: return null
    if (baselineSource.isNullOrBlank() || baselineSource != currentSource) return null
    if (currentValue < baselineValue) return null
    return currentValue - baselineValue
}

internal fun reliableExactCounterDelta(
    baselineValue: Long?,
    baselineSource: String?,
    highestValue: Long?,
    counterContinuous: Boolean,
    samplesAfterBaseline: Int,
): Long? {
    val observed = monotonicCounterDelta(
        baselineValue = baselineValue,
        baselineSource = baselineSource,
        currentValue = highestValue,
        currentSource = baselineSource,
    ) ?: return null
    // A positive increase observed while the source was continuous remains evidence even if the
    // sensor disappears later. Zero is CLEAN only with a post-baseline sample and full continuity.
    return observed.takeIf {
        it > 0L || (counterContinuous && samplesAfterBaseline > 0)
    }
}

internal data class AdaptiveBoundaryEvidence(
    val exactDelta: Long?,
    val proxyDelta: Long?,
)

internal fun adaptiveBoundaryEvidence(
    before: TelemetrySnapshot,
    after: TelemetrySnapshot,
    exactContinuousBefore: Boolean,
    exactContinuousAfter: Boolean,
): AdaptiveBoundaryEvidence {
    val exactDelta = if (
        exactContinuousBefore &&
        exactContinuousAfter &&
        (
            before.exactUnderrunQuality == MetricQuality.HARDWARE_COUNTER ||
                before.exactUnderrunQuality == MetricQuality.KERNEL
            ) &&
        after.exactUnderrunQuality == before.exactUnderrunQuality
    ) {
        monotonicCounterDelta(
            baselineValue = before.exactUnderruns,
            baselineSource = before.exactUnderrunSource,
            currentValue = after.exactUnderruns,
            currentSource = after.exactUnderrunSource,
        )
    } else {
        null
    }
    val proxyDelta = if (
        before.suspectedUnderrunQuality == MetricQuality.PROXY &&
        after.suspectedUnderrunQuality == before.suspectedUnderrunQuality
    ) {
        monotonicCounterDelta(
            baselineValue = before.suspectedUnderruns,
            baselineSource = before.suspectedUnderrunSource,
            currentValue = after.suspectedUnderruns,
            currentSource = after.suspectedUnderrunSource,
        )
    } else {
        null
    }
    return AdaptiveBoundaryEvidence(exactDelta = exactDelta, proxyDelta = proxyDelta)
}

private fun saturatingAdd(left: Long, right: Long): Long {
    if (right <= 0L) return left
    return if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
}

internal fun isAdaptiveBoundaryCandidate(
    scenarioId: String,
    phaseId: String,
): Boolean =
    scenarioId == ADAPTIVE_HUNT_SCENARIO_ID &&
        phaseId.startsWith(ADAPTIVE_HUNT_PHASE_PREFIX) &&
        phaseId != ADAPTIVE_RECOVERY_PHASE_ID

internal fun adaptiveRecoveryPhaseIndex(scenario: ScenarioSpec): Int =
    if (scenario.id == ADAPTIVE_HUNT_SCENARIO_ID) {
        scenario.phases.indexOfFirst { it.id == ADAPTIVE_RECOVERY_PHASE_ID }
    } else {
        -1
    }

internal fun phaseIndexAfterAdaptiveBoundary(
    currentPhaseIndex: Int,
    recoveryPhaseIndex: Int,
): Int =
    if (recoveryPhaseIndex > currentPhaseIndex) {
        recoveryPhaseIndex
    } else {
        currentPhaseIndex + 1
    }

internal fun adaptiveProxyBoundaryThreshold(appliedProducerFrames: Double): Long {
    val safeFrames = appliedProducerFrames
        .takeIf { it.isFinite() && it >= 0.0 }
        ?: 0.0
    return (safeFrames * ADAPTIVE_PROXY_MISS_RATIO)
        .coerceAtMost(Long.MAX_VALUE.toDouble())
        .toLong()
        .coerceAtLeast(MIN_ADAPTIVE_PROXY_MISSES)
}

/**
 * Monotonic phase time that excludes bounded renderer recovery windows.
 *
 * The helper is intentionally pure and timestamp-driven so transition fraction, progress, phase
 * completion, and expected-frame accounting can share one testable notion of active time.
 */
internal enum class PhasePauseOwner {
    LEGACY,
    PRODUCER_RECOVERY,
    THERMAL_DERATE,
    PHASE_COMPLETE,
}

internal class ActivePhaseClock(startedAtMs: Long) {
    private val startedAtMs = startedAtMs.coerceAtLeast(0L)
    private var completedPauseMs = 0L
    private var unionPausedAtMs: Long? = null
    private val ownerPausedAtMs =
        LongArray(PhasePauseOwner.entries.size) { PAUSE_NOT_ACTIVE }

    fun pause(
        atMonotonicMs: Long,
        owner: PhasePauseOwner = PhasePauseOwner.LEGACY,
    ) {
        val ownerIndex = owner.ordinal
        if (ownerPausedAtMs[ownerIndex] != PAUSE_NOT_ACTIVE) return
        val boundedAtMs = atMonotonicMs.coerceAtLeast(startedAtMs)
        ownerPausedAtMs[ownerIndex] = boundedAtMs
        unionPausedAtMs = unionPausedAtMs
            ?.let { minOf(it, boundedAtMs) }
            ?: boundedAtMs
    }

    fun resume(
        atMonotonicMs: Long,
        owner: PhasePauseOwner = PhasePauseOwner.LEGACY,
    ) {
        val ownerIndex = owner.ordinal
        if (ownerPausedAtMs[ownerIndex] == PAUSE_NOT_ACTIVE) return
        ownerPausedAtMs[ownerIndex] = PAUSE_NOT_ACTIVE
        if (ownerPausedAtMs.any { it != PAUSE_NOT_ACTIVE }) return
        val pausedAt = unionPausedAtMs ?: return
        completedPauseMs = saturatingAdd(
            completedPauseMs,
            nonNegativeMonotonicDelta(
                laterMs = atMonotonicMs.coerceAtLeast(startedAtMs),
                earlierMs = pausedAt,
            ),
        )
        unionPausedAtMs = null
    }

    fun elapsedMs(atMonotonicMs: Long): Long {
        val wallElapsed = nonNegativeMonotonicDelta(
            laterMs = atMonotonicMs.coerceAtLeast(startedAtMs),
            earlierMs = startedAtMs,
        )
        return (wallElapsed - totalPausedMs(atMonotonicMs)).coerceAtLeast(0L)
    }

    fun totalPausedMs(atMonotonicMs: Long): Long {
        return saturatingAdd(completedPauseMs, currentPauseMs(atMonotonicMs))
    }

    fun isPaused(): Boolean = unionPausedAtMs != null

    fun currentPauseMs(
        atMonotonicMs: Long,
        owner: PhasePauseOwner? = null,
    ): Long {
        val pausedAt = if (owner == null) {
            unionPausedAtMs
        } else {
            ownerPausedAtMs[owner.ordinal].takeUnless { it == PAUSE_NOT_ACTIVE }
        }
        return pausedAt?.let { activePauseAtMs ->
            nonNegativeMonotonicDelta(
                laterMs = atMonotonicMs.coerceAtLeast(startedAtMs),
                earlierMs = activePauseAtMs,
            )
        } ?: 0L
    }

    private companion object {
        const val PAUSE_NOT_ACTIVE = Long.MIN_VALUE
    }
}

private fun nonNegativeMonotonicDelta(laterMs: Long, earlierMs: Long): Long =
    if (laterMs <= earlierMs) 0L else laterMs - earlierMs

internal fun producerRecoveryDeadlineExceeded(
    recoveryStillActive: Boolean,
    currentPauseMs: Long,
    timeoutMs: Long,
): Boolean {
    val boundedPauseMs = currentPauseMs.coerceAtLeast(0L)
    val boundedTimeoutMs = timeoutMs.coerceAtLeast(0L)
    return if (recoveryStillActive) {
        boundedPauseMs >= boundedTimeoutMs
    } else {
        boundedPauseMs > boundedTimeoutMs
    }
}

/**
 * Warm-up must never allocate codec/SBWC-labelled buffers before the matching vendor route has
 * been applied. It is intentionally a small portable RGB producer; the measured generation is
 * created only after the route-transition teardown barrier.
 */
internal fun safeWarmupPhaseFor(target: PhaseSpec): PhaseSpec =
    target.copy(
        activeLayers = 1,
        producerFps = min(60f, target.producerFps),
        requestedDisplayHz = min(60f, target.requestedDisplayHz),
        backend = LayerBackend.INDEPENDENT_SURFACES,
        pixelRoute = PixelRoute.RGB_8888,
        bufferSize = BufferSize.DISPLAY,
        motion = MotionProfile.STATIC,
        layerSizeProfile = LayerSizeProfile.FULL_SCREEN,
        workloads = LoadSetpoints(),
        alphaOverlap = false,
        includeGlLayer = false,
        hwcCompositionExpectation = HwcCompositionExpectation.NONE,
    )

internal fun rendererAllocationRouteChanges(
    active: PhaseSpec?,
    target: PhaseSpec,
): Boolean =
    (active?.pixelRoute ?: PixelRoute.RGB_8888) != target.pixelRoute

internal fun rendererTopologyChanged(active: PhaseSpec?, target: PhaseSpec): Boolean =
    active == null ||
        active.activeLayers != target.activeLayers ||
        active.backend != target.backend ||
        active.pixelRoute != target.pixelRoute ||
        active.bufferSize != target.bufferSize ||
        active.includeGlLayer != target.includeGlLayer ||
        active.alphaOverlap != target.alphaOverlap

/**
 * Pixel/compression routes cannot be interpolated under a live producer. Continuous fields can
 * still start at the prior values, but the preparation producer must use the already validated
 * target allocation topology after the old route has been detached. Destination size is not an
 * allocation field, so it stays at the measured origin until the active transition is armed.
 */
internal fun allocationRouteSafePhase(
    initial: PhaseSpec,
    target: PhaseSpec,
): PhaseSpec =
    if (initial.pixelRoute == target.pixelRoute) {
        initial
    } else {
        initial.copy(
            activeLayers = target.activeLayers,
            backend = target.backend,
            pixelRoute = target.pixelRoute,
            bufferSize = target.bufferSize,
            alphaOverlap = target.alphaOverlap,
            includeGlLayer = target.includeGlLayer,
            workloads = if (target.hasGpuLoadProducer()) {
                initial.workloads
            } else {
                initial.workloads.copy(gpu = 0f)
            },
        )
    }

/**
 * Discrete destination geometry keeps the measured origin for the first active sample, then stays
 * armed for the rest of the phase. Cyclic transition valleys may release continuous FPS/workload
 * values, but must not repeatedly replace/restart a dynamic layer-size waveform.
 */
internal fun layerSizeProfileForActiveTransition(
    interpolated: PhaseSpec,
    target: PhaseSpec,
    transitionStarted: Boolean,
): PhaseSpec =
    if (transitionStarted) {
        interpolated.copy(layerSizeProfile = target.layerSizeProfile)
    } else {
        interpolated
    }

internal data class PendingControlCoverage(
    val sample: TransitionSample,
    val phaseElapsedMs: Long,
    val expectedProducerCount: Int,
    val expectedTopologyRevision: Long,
    val expectedLayerSizeProfileOrdinal: Int,
) {
    fun isAcknowledgedBy(
        readiness: com.example.dpulayerlab.monitor.ProducerReadiness,
    ): Boolean =
        readiness.ready &&
            readiness.expectedCount == expectedProducerCount &&
            readiness.topologyRevision == expectedTopologyRevision &&
            readiness.geometryReady &&
            readiness.geometryAppliedProfileOrdinal == expectedLayerSizeProfileOrdinal
}

/**
 * Recovery replaces the renderer phase with a safe preparation profile. An acknowledgment for a
 * pre-recovery control sample can no longer prove that sample, even when the physical producer set
 * is unchanged. Returning null forces the controller to republish from its frozen active clock.
 */
internal fun discardPendingControlCoverageForProducerRecovery(
    @Suppress("UNUSED_PARAMETER") pending: PendingControlCoverage?,
): PendingControlCoverage? = null

internal fun layerGeometryCoverageSatisfied(
    readiness: com.example.dpulayerlab.monitor.ProducerReadiness,
    profile: LayerSizeProfile,
): Boolean {
    val requiredMask = profile.requiredCoverageMask()
    return readiness.topologyPublished &&
        !readiness.topologyPending &&
        !readiness.topologyMissed &&
        !readiness.teardownFailed &&
        !readiness.teardownCompleted &&
        readiness.ready &&
        readiness.expectedCount > 0 &&
        readiness.observedCount == readiness.expectedCount &&
        readiness.geometryReady &&
        readiness.geometryAppliedProfileOrdinal == profile.ordinal &&
        readiness.geometryCoverageMask and requiredMask == requiredMask
}

internal fun rendererPreparationPhase(initialRuntime: PhaseSpec): PhaseSpec =
    initialRuntime.copy(
        producerFps = min(60f, initialRuntime.producerFps),
        requestedDisplayHz = min(60f, initialRuntime.requestedDisplayHz),
        motion = MotionProfile.STATIC,
        layerSizeProfile = preparationLayerSizeProfile(initialRuntime.layerSizeProfile),
        workloads = LoadSetpoints(),
        hwcCompositionExpectation = HwcCompositionExpectation.NONE,
    )

/**
 * Topology preparation and recovery must not consume a dynamic layer-size waveform before the
 * measured phase clock starts. Both dynamic profiles begin at the same bounded small geometry.
 */
internal fun preparationLayerSizeProfile(profile: LayerSizeProfile): LayerSizeProfile =
    when (profile) {
        LayerSizeProfile.GRADUAL_SMALL_TO_FULL,
        LayerSizeProfile.ABRUPT_SMALL_FULL,
        -> LayerSizeProfile.SMALL_UNIFORM

        LayerSizeProfile.FULL_SCREEN,
        LayerSizeProfile.SMALL_UNIFORM,
        LayerSizeProfile.MIXED_SIZES,
        -> profile
    }

internal fun progressForControllerPause(
    current: RunProgress,
    activeRun: Boolean,
): RunProgress = if (activeRun) {
    progressForImmediateStop(current, "Controller가 pause되어 안전 중단")
} else {
    current
}

internal fun progressForCooldownTeardown(current: RunProgress): RunProgress = current.copy(
    stage = RunnerStage.COOLDOWN,
    phase = null,
    targetPhase = null,
    transitionFraction = 0f,
    statusText = "부하 해제 및 physical producer/counter 안정화",
)

internal enum class MemorySafetyFailure {
    WORKLOAD_ALLOCATION,
    SYSTEM_LOW_MEMORY,
}

/**
 * The workload failure wins when both signals arrive together: it is the direct evidence that the
 * requested DRAM traffic was not produced, while the system signal remains covered by the same
 * fail-closed abort path.
 */
internal fun memorySafetyFailure(
    workloadAllocationFailed: Boolean,
    systemLowMemory: Boolean,
): MemorySafetyFailure? = when {
    workloadAllocationFailed -> MemorySafetyFailure.WORKLOAD_ALLOCATION
    systemLowMemory -> MemorySafetyFailure.SYSTEM_LOW_MEMORY
    else -> null
}

internal fun scenarioNeedsMemoryPrewarm(scenario: ScenarioSpec): Boolean =
    scenario.phases.any { phase ->
        phase.workloads.memory
            .takeIf(Float::isFinite)
            ?.coerceIn(0f, 1f)
            ?.let { it > MIN_EFFECTIVE_LOAD }
            ?: false
    }

internal fun progressForImmediateStop(
    current: RunProgress,
    reason: String,
): RunProgress = current.copy(
    phase = null,
    targetPhase = null,
    transitionFraction = 0f,
    statusText = reason,
)

internal fun planStartBlocked(runJobPresent: Boolean, isRunning: Boolean): Boolean =
    runJobPresent || isRunning

internal fun publishedJobOwnerMatches(currentOwner: Any?, finishingOwner: Any): Boolean =
    currentOwner === finishingOwner

internal enum class RendererTeardownBarrierDecision {
    WAIT,
    SUCCESS,
    FAIL,
}

internal fun rendererTeardownBarrierDecision(
    generationPresent: Boolean,
    callbackFailure: Boolean,
    stageRemovalAcknowledged: Boolean,
    processLeaseActive: Boolean,
    deadlineReached: Boolean,
): RendererTeardownBarrierDecision = when {
    generationPresent && callbackFailure -> RendererTeardownBarrierDecision.FAIL
    (!generationPresent || stageRemovalAcknowledged) && !processLeaseActive ->
        RendererTeardownBarrierDecision.SUCCESS
    deadlineReached -> RendererTeardownBarrierDecision.FAIL
    else -> RendererTeardownBarrierDecision.WAIT
}

/**
 * Integrates the producer FPS that was actually applied during one phase.
 *
 * Calls are synchronized because a thermal callback can change the active rate between the
 * controller's 100 ms transition ticks. The phase duration bounds the integral even if a delayed
 * control tick arrives after the nominal end.
 */
internal class AppliedProducerFrameBudget(
    private val phaseStartedMs: Long,
    phaseDurationMs: Long,
) {
    private val boundedDurationMs = phaseDurationMs.coerceAtLeast(0L)
    private val activeClock = ActivePhaseClock(phaseStartedMs)
    private var accountedElapsedMs = 0L
    private var activeProducerFps = 0f
    private var activeLayerCount = 1
    private var expectedFrames = 0.0
    private var expectedAggregateFrames = 0.0
    private var lastPhysicalFrameTotal: Long? = null
    private var actualAggregateFrames = 0L
    private var physicalFrameCounterValid = true
    private var finished = false

    @Synchronized
    fun apply(
        atMonotonicMs: Long,
        producerFps: Float,
        activeLayers: Int = 1,
    ) {
        if (finished) return
        accountThrough(atMonotonicMs)
        activeProducerFps = producerFps
            .takeIf { it.isFinite() && it > 0f }
            ?.coerceAtMost(ScenarioSafetyPolicy.HARD_MAX_PRODUCER_FPS)
            ?: 0f
        activeLayerCount = activeLayers.coerceIn(1, ScenarioSafetyPolicy.HARD_MAX_LAYERS)
    }

    @Synchronized
    fun pause(
        atMonotonicMs: Long,
        owner: PhasePauseOwner = PhasePauseOwner.LEGACY,
    ) {
        if (finished) return
        accountThrough(atMonotonicMs)
        activeClock.pause(atMonotonicMs, owner)
    }

    @Synchronized
    fun resume(
        atMonotonicMs: Long,
        owner: PhasePauseOwner = PhasePauseOwner.LEGACY,
    ) {
        if (finished) return
        accountThrough(atMonotonicMs)
        activeClock.resume(atMonotonicMs, owner)
    }

    @Synchronized
    fun finish(atMonotonicMs: Long): Double {
        if (!finished) {
            accountThrough(atMonotonicMs)
            activeProducerFps = 0f
            activeLayerCount = 1
            finished = true
        }
        return expectedFrames
    }

    @Synchronized
    fun expectedAggregateFrames(): Double = expectedAggregateFrames

    @Synchronized
    fun observePhysicalFrames(totalFrames: Long, countAsActive: Boolean) {
        if (finished) return
        observePhysicalFramesLocked(totalFrames, countAsActive)
    }

    @Synchronized
    fun pauseAtPhysicalBoundary(
        atMonotonicMs: Long,
        totalFrames: Long,
        owner: PhasePauseOwner = PhasePauseOwner.LEGACY,
    ) {
        if (finished) return
        observePhysicalFramesLocked(
            totalFrames,
            countAsActive = !activeClock.isPaused(),
        )
        accountThrough(atMonotonicMs)
        activeClock.pause(atMonotonicMs, owner)
    }

    private fun observePhysicalFramesLocked(totalFrames: Long, countAsActive: Boolean) {
        val previous = lastPhysicalFrameTotal
        if (totalFrames < 0L || (previous != null && totalFrames < previous)) {
            physicalFrameCounterValid = false
            lastPhysicalFrameTotal = totalFrames.takeIf { it >= 0L }
            return
        }
        if (previous != null && countAsActive) {
            val delta = totalFrames - previous
            actualAggregateFrames = if (actualAggregateFrames > Long.MAX_VALUE - delta) {
                Long.MAX_VALUE
            } else {
                actualAggregateFrames + delta
            }
        }
        lastPhysicalFrameTotal = totalFrames
    }

    @Synchronized
    fun actualAggregateFrames(): Long? =
        actualAggregateFrames.takeIf {
            physicalFrameCounterValid && lastPhysicalFrameTotal != null
        }

    private fun accountThrough(atMonotonicMs: Long) {
        val elapsedMs = activeClock.elapsedMs(atMonotonicMs)
            .coerceAtMost(boundedDurationMs)
        if (elapsedMs <= accountedElapsedMs) return
        val intervalMs = elapsedMs - accountedElapsedMs
        val primaryFrames =
            activeProducerFps.toDouble() * intervalMs.toDouble() / 1_000.0
        expectedFrames += primaryFrames
        expectedAggregateFrames += primaryFrames * activeLayerCount.toDouble()
        accountedElapsedMs = elapsedMs
    }
}

internal data class ProducerRateAssessment(
    val ratio: Double?,
    val materialShortfall: Boolean,
)

private data class ProducerTopologyPendingBoundary(
    val generation: Long,
    val monotonicMs: Long,
)

internal fun assessProducerRate(
    actualFrames: Long?,
    expectedFrames: Double,
    minimumExpectedFrames: Double = 30.0,
    minimumRatio: Double = 0.70,
): ProducerRateAssessment {
    if (
        !expectedFrames.isFinite() ||
        expectedFrames < 0.0 ||
        !minimumExpectedFrames.isFinite() ||
        minimumExpectedFrames < 0.0 ||
        !minimumRatio.isFinite() ||
        minimumRatio !in 0.0..1.0
    ) {
        return ProducerRateAssessment(ratio = null, materialShortfall = true)
    }
    if (expectedFrames < minimumExpectedFrames) {
        return ProducerRateAssessment(ratio = null, materialShortfall = false)
    }
    if (actualFrames == null || actualFrames < 0L) {
        return ProducerRateAssessment(ratio = null, materialShortfall = true)
    }
    val ratio = if (expectedFrames > 0.0) {
        actualFrames.toDouble() / expectedFrames
    } else {
        null
    }
    return ProducerRateAssessment(
        ratio = ratio,
        materialShortfall =
            expectedFrames >= minimumExpectedFrames &&
                (ratio == null || ratio < minimumRatio),
    )
}

/**
 * A decoder topology can become active while continuous fields still start at the previous
 * phase's rate. Account for that reachable rate instead of validating only decoder endpoints.
 */
internal fun requiredDecoderSourceFps(phases: List<PhaseSpec>): Float? {
    var requiredFps: Float? = null
    phases.forEachIndexed { index, target ->
        if (!target.requiresSelectedDecoderProducer()) return@forEachIndexed
        requiredFps = maxOf(requiredFps ?: 0f, target.producerFps)
        val previous = phases.getOrNull(index - 1) ?: return@forEachIndexed
        if (previous.requiresSelectedDecoderProducer()) return@forEachIndexed
        val boundaryFps = if (target.transition.mode == TransitionMode.STEP) {
            min(60f, previous.producerFps)
        } else {
            previous.producerFps
        }
        requiredFps = maxOf(requiredFps ?: 0f, boundaryFps)
    }
    return requiredFps?.takeIf { it.isFinite() && it > 0f }
}

internal class TransitionCoverageTracker(
    private val spec: TransitionSpec,
) {
    private var sawTarget = false
    private var sawRampUpIntermediate = false
    private var sawRampDownIntermediate = false
    private var sawHold = false
    private var sawPulseOn = false
    private var sawPulseOff = false
    private var rampUpSampleCount = 0
    private var rampDownSampleCount = 0
    private var currentCycleIndex = Long.MIN_VALUE
    private var cycleSawFirstSegment = false
    private var cycleSawSecondSegment = false
    private var sawCompleteCycle = false
    private val staircaseLevels = BooleanArray(spec.stepCount.coerceIn(2, 64))

    fun observe(sample: TransitionSample, phaseElapsedMs: Long = 0L) {
        val fraction = sample.fraction.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: return
        if (fraction >= 1f) sawTarget = true
        when (sample.segment) {
            TransitionSegment.RAMP_UP -> {
                rampUpSampleCount++
                if (fraction > 0f && fraction < 1f) sawRampUpIntermediate = true
            }
            TransitionSegment.RAMP_DOWN -> {
                rampDownSampleCount++
                if (fraction > 0f && fraction < 1f) sawRampDownIntermediate = true
            }
            TransitionSegment.HOLD -> sawHold = true
            TransitionSegment.BURST_ON -> sawPulseOn = true
            TransitionSegment.BURST_OFF -> sawPulseOff = true
            TransitionSegment.TARGET -> sawTarget = true
            TransitionSegment.STEP_UP -> Unit
        }
        if (
            spec.mode == TransitionMode.PULSE_BURST ||
            spec.mode == TransitionMode.TRIANGLE_WAVE
        ) {
            val cycleMs = spec.cycleMs.coerceAtLeast(1L)
            val cycleIndex = phaseElapsedMs.coerceAtLeast(0L) / cycleMs
            if (cycleIndex != currentCycleIndex) {
                currentCycleIndex = cycleIndex
                cycleSawFirstSegment = false
                cycleSawSecondSegment = false
            }
            when (spec.mode) {
                TransitionMode.PULSE_BURST -> {
                    if (sample.segment == TransitionSegment.BURST_ON) {
                        cycleSawFirstSegment = true
                    }
                    if (sample.segment == TransitionSegment.BURST_OFF) {
                        cycleSawSecondSegment = true
                    }
                }
                TransitionMode.TRIANGLE_WAVE -> {
                    if (sample.segment == TransitionSegment.RAMP_UP) {
                        cycleSawFirstSegment = true
                    }
                    if (sample.segment == TransitionSegment.RAMP_DOWN) {
                        cycleSawSecondSegment = true
                    }
                }
                else -> Unit
            }
            if (cycleSawFirstSegment && cycleSawSecondSegment) sawCompleteCycle = true
        }
        if (spec.mode == TransitionMode.STAIRCASE) {
            val lastLevel = staircaseLevels.lastIndex
            val level = (fraction * lastLevel.toFloat())
                .roundToInt()
                .coerceIn(0, lastLevel)
            staircaseLevels[level] = true
        }
    }

    fun failureReason(): String? = when (spec.mode) {
        TransitionMode.STEP ->
            if (sawTarget) null else "STEP target tick 누락"
        TransitionMode.LINEAR_RAMP ->
            if (sawRampUpIntermediate) null else "linear ramp intermediate tick 누락"
        TransitionMode.STAIRCASE -> {
            val missing = staircaseLevels.indices.filterNot { staircaseLevels[it] }
            if (missing.isEmpty()) null else "staircase levels 누락: ${missing.joinToString()}"
        }
        TransitionMode.PULSE_BURST ->
            if (sawPulseOn && sawPulseOff && sawCompleteCycle) {
                null
            } else {
                "pulse 동일 cycle의 ON/OFF window 누락"
            }
        TransitionMode.TRIANGLE_WAVE ->
            if (
                sawRampUpIntermediate &&
                sawRampDownIntermediate &&
                sawCompleteCycle
            ) {
                null
            } else {
                "triangle 동일 cycle의 ramp-up/ramp-down tick 누락"
            }
        TransitionMode.SOAK_RECOVERY ->
            if (
                rampUpSampleCount >= 2 &&
                sawRampUpIntermediate &&
                sawHold &&
                rampDownSampleCount >= 2 &&
                sawRampDownIntermediate
            ) {
                null
            } else {
                "soak attack 2 tick/hold/recovery 2 tick 누락"
            }
    }
}

/**
 * Converts every monotonic timestamp persisted in a run sample onto the same run-relative axis.
 *
 * A valid cached observation captured before the run keeps a signed negative timestamp so
 * `sample.tMs - evidence.tMs == ageMs` remains true. A future/malformed observation is removed
 * atomically with its DEVICE/CLIENT or SurfaceFlinger values and provenance. A full sample that
 * completed before the run is projected to t=0 only as a defensive fallback and cannot retain
 * independent evidence.
 */
internal fun TelemetrySnapshot.toRunRelativeTelemetry(
    runStartMonotonicMs: Long,
): TelemetrySnapshot {
    val boundedRunStartMs = runStartMonotonicMs.coerceAtLeast(0L)
    val boundedSampleMs = monotonicMs.coerceAtLeast(0L)
    val sampleBelongsToRun = boundedSampleMs >= boundedRunStartMs
    fun relativeEvidence(timestampMs: Long?, ageMs: Long?): Long? {
        val timestamp = timestampMs ?: return null
        if (
            !sampleBelongsToRun ||
            timestamp < 0L ||
            timestamp > boundedSampleMs ||
            ageMs == null ||
            ageMs < 0L ||
            ageMs != boundedSampleMs - timestamp
        ) {
            return null
        }
        return timestamp - boundedRunStartMs
    }

    val relativeHwcEvidenceMs =
        relativeEvidence(
            hwcCompositionEvidenceMonotonicMs,
            hwcCompositionEvidenceAgeMs,
        )
    val relativeSurfaceFlingerEvidenceMs =
        relativeEvidence(
            surfaceFlingerEvidenceMonotonicMs,
            surfaceFlingerEvidenceAgeMs,
        )
    return copy(
        monotonicMs =
            (boundedSampleMs - boundedRunStartMs).coerceAtLeast(0L),
        hwcDeviceLayers = hwcDeviceLayers.takeIf {
            relativeHwcEvidenceMs != null
        },
        hwcDeviceLayersQuality = hwcDeviceLayersQuality.takeIf {
            relativeHwcEvidenceMs != null
        } ?: MetricQuality.UNAVAILABLE,
        hwcDeviceLayersSource = hwcDeviceLayersSource.takeIf {
            relativeHwcEvidenceMs != null
        }.orEmpty(),
        hwcClientLayers = hwcClientLayers.takeIf {
            relativeHwcEvidenceMs != null
        },
        hwcClientLayersQuality = hwcClientLayersQuality.takeIf {
            relativeHwcEvidenceMs != null
        } ?: MetricQuality.UNAVAILABLE,
        hwcClientLayersSource = hwcClientLayersSource.takeIf {
            relativeHwcEvidenceMs != null
        }.orEmpty(),
        hwcCompositionEvidenceMonotonicMs = relativeHwcEvidenceMs,
        hwcCompositionEvidenceAgeMs =
            hwcCompositionEvidenceAgeMs.takeIf {
                relativeHwcEvidenceMs != null
            },
        surfaceFlingerHwcMissed = surfaceFlingerHwcMissed.takeIf {
            relativeSurfaceFlingerEvidenceMs != null
        },
        surfaceFlingerGpuMissed = surfaceFlingerGpuMissed.takeIf {
            relativeSurfaceFlingerEvidenceMs != null
        },
        surfaceFlingerMissSource = surfaceFlingerMissSource.takeIf {
            relativeSurfaceFlingerEvidenceMs != null
        }.orEmpty(),
        surfaceFlingerEvidenceMonotonicMs =
            relativeSurfaceFlingerEvidenceMs,
        surfaceFlingerEvidenceAgeMs =
            surfaceFlingerEvidenceAgeMs.takeIf {
                relativeSurfaceFlingerEvidenceMs != null
            },
    )
}

internal fun monotonicTimestampRelativeToRun(
    timestampMs: Long?,
    runStartMonotonicMs: Long,
): Long? {
    val timestamp = timestampMs ?: return null
    val boundedRunStartMs = runStartMonotonicMs.coerceAtLeast(0L)
    if (timestamp < boundedRunStartMs || timestamp < 0L) return null
    return timestamp - boundedRunStartMs
}

internal fun hwcCompositionProbeCanStart(
    remainingActiveMs: Long,
    sampleTimeoutMs: Long,
    completionReserveMs: Long,
): Boolean {
    if (
        remainingActiveMs < 0L ||
        sampleTimeoutMs <= 0L ||
        completionReserveMs < 0L
    ) {
        return false
    }
    if (sampleTimeoutMs > Long.MAX_VALUE - completionReserveMs) return false
    return remainingActiveMs >= sampleTimeoutMs + completionReserveMs
}

internal enum class PeriodicTelemetryArbitration {
    SAMPLE,
    DROP_BUSY,
    DROP_TYPED_HWC_PRIORITY,
}

internal fun periodicTelemetryArbitration(
    mutexAcquired: Boolean,
    typedHwcProbePriority: Boolean,
): PeriodicTelemetryArbitration = when {
    !mutexAcquired -> PeriodicTelemetryArbitration.DROP_BUSY
    typedHwcProbePriority -> PeriodicTelemetryArbitration.DROP_TYPED_HWC_PRIORITY
    else -> PeriodicTelemetryArbitration.SAMPLE
}

internal fun surfaceFlingerProbePolicy(
    forceCompositionProbe: Boolean,
    activeRun: Boolean,
): SurfaceFlingerProbePolicy = when {
    forceCompositionProbe -> SurfaceFlingerProbePolicy.TYPED_BOUNDARY
    activeRun -> SurfaceFlingerProbePolicy.SUPPRESS_DURING_LOAD
    else -> SurfaceFlingerProbePolicy.PERIODIC
}

internal enum class HwcCapacityGenerationAction {
    WAIT,
    ACTIVATE,
    OBSERVE,
}

internal fun remainingHwcCapacityCalibrationBudgetMs(
    deadlineMs: Long,
    nowMs: Long,
): Long {
    if (deadlineMs < 0L || nowMs < 0L || nowMs >= deadlineMs) return 0L
    return deadlineMs - nowMs
}

internal fun hwcCapacityCalibrationSampleLaneTimeoutMs(
    remainingMs: Long,
    hardTimeoutMs: Long,
    completionReserveMs: Long,
): Long? {
    if (
        remainingMs <= 0L ||
        hardTimeoutMs <= 0L ||
        completionReserveMs < 0L ||
        remainingMs <= completionReserveMs
    ) {
        return null
    }
    return min(hardTimeoutMs, remainingMs - completionReserveMs)
        .takeIf { it > 0L }
}

/**
 * Keeps calibration activation behind the same committed-topology and cleanup barriers as a
 * measured phase. A failed ACTIVATE attempt remains WAIT/ACTIVATE eligible until the caller's
 * bounded deadline, while a successful activation is never issued a second time.
 */
internal fun hwcCapacityGenerationAction(
    generationActivated: Boolean,
    topologyPublished: Boolean,
    topologyPending: Boolean,
    expectedProducerCount: Int,
    candidateLayers: Int,
    rendererCleanupPending: Boolean,
): HwcCapacityGenerationAction = when {
    generationActivated -> HwcCapacityGenerationAction.OBSERVE
    candidateLayers <= 0 ||
        !topologyPublished ||
        topologyPending ||
        expectedProducerCount != candidateLayers ||
        rendererCleanupPending -> HwcCapacityGenerationAction.WAIT
    else -> HwcCapacityGenerationAction.ACTIVATE
}

internal fun hwcCapacityCalibrationTopologyReady(
    readiness: ProducerReadiness,
    candidateLayers: Int,
    expectedProfileOrdinal: Int,
    rendererCleanupPending: Boolean,
): Boolean =
    candidateLayers > 0 &&
        expectedProfileOrdinal >= 0 &&
        readiness.ready &&
        readiness.topologyPublished &&
        readiness.topologyPublishedAtMs != null &&
        readiness.topologyRevision > 0L &&
        !readiness.topologyPending &&
        !readiness.topologyMissed &&
        !readiness.teardownFailed &&
        !readiness.teardownCompleted &&
        readiness.runtimeFailureReason == null &&
        readiness.geometryReady &&
        readiness.geometryRequestedRevision > 0L &&
        readiness.geometryAppliedRevision ==
            readiness.geometryRequestedRevision &&
        readiness.geometryRequestedProfileOrdinal == expectedProfileOrdinal &&
        readiness.geometryAppliedProfileOrdinal == expectedProfileOrdinal &&
        readiness.expectedCount == candidateLayers &&
        readiness.observedCount == candidateLayers &&
        !rendererCleanupPending

internal fun hwcCapacityCalibrationTopologyUnchanged(
    before: ProducerReadiness,
    after: ProducerReadiness,
    candidateLayers: Int,
    expectedProfileOrdinal: Int,
    rendererCleanupPending: Boolean,
): Boolean =
    hwcCapacityCalibrationTopologyReady(
        readiness = before,
        candidateLayers = candidateLayers,
        expectedProfileOrdinal = expectedProfileOrdinal,
        rendererCleanupPending = false,
    ) &&
        hwcCapacityCalibrationTopologyReady(
            readiness = after,
            candidateLayers = candidateLayers,
            expectedProfileOrdinal = expectedProfileOrdinal,
            rendererCleanupPending = rendererCleanupPending,
        ) &&
        after.topologyRevision == before.topologyRevision &&
        after.topologyDiscontinuitySerial ==
            before.topologyDiscontinuitySerial &&
        after.topologyPublishedAtMs == before.topologyPublishedAtMs &&
        after.geometryRequestedRevision == before.geometryRequestedRevision &&
        after.geometryAppliedRevision == before.geometryAppliedRevision

internal fun hwcCapacityCalibrationResult(
    candidateLayers: Int,
    expectedProducerCount: Int,
    observedProducerCount: Int,
    sampleStartedMonotonicMs: Long,
    snapshot: TelemetrySnapshot,
): HwcCapacityCalibrationResult {
    require(candidateLayers > 0)
    require(sampleStartedMonotonicMs >= 0L)
    val topologyConfirmed =
        expectedProducerCount == candidateLayers &&
            observedProducerCount == candidateLayers
    val device = snapshot.hwcDeviceLayers
    val client = snapshot.hwcClientLayers
    val deviceSource = snapshot.hwcDeviceLayersSource.trim()
    val clientSource = snapshot.hwcClientLayersSource.trim()
    val quality = snapshot.hwcDeviceLayersQuality
    val evidenceMs = snapshot.hwcCompositionEvidenceMonotonicMs
    val evidenceAgeMs = snapshot.hwcCompositionEvidenceAgeMs
    val evidenceFresh =
            evidenceMs != null &&
            evidenceMs >= 0L &&
            evidenceMs >= sampleStartedMonotonicMs &&
            evidenceMs <= snapshot.monotonicMs &&
            evidenceAgeMs != null &&
            evidenceAgeMs == snapshot.monotonicMs - evidenceMs &&
            evidenceAgeMs in 0L..HWC_COMPOSITION_EVIDENCE_MAX_AGE_MS
    val pairComplete =
        device != null &&
            client != null &&
            device >= 0 &&
            client >= 0 &&
            quality == snapshot.hwcClientLayersQuality &&
            quality in HWC_COMPOSITION_QUALITIES &&
            deviceSource.isNotEmpty() &&
            deviceSource == clientSource
    return if (topologyConfirmed && evidenceFresh && pairComplete) {
        HwcCapacityCalibrationResult(
            status = HwcCapacityCalibrationStatus.OBSERVED_AT_CANDIDATE,
            candidateLayers = candidateLayers,
            observedDeviceLayers = device,
            observedClientLayers = client,
            source = deviceSource.take(MAX_HWC_EVENT_SOURCE_CHARS),
            quality = quality,
            evidenceMonotonicMs = evidenceMs,
            detail =
                "one fresh process-session snapshot; physical topology fully ready; " +
                    "not a universal hardware maximum",
        )
    } else {
        HwcCapacityCalibrationResult(
            status = HwcCapacityCalibrationStatus.UNAVAILABLE,
            candidateLayers = candidateLayers,
            detail =
                "one-shot evidence incomplete; expected=$expectedProducerCount; " +
                    "observed=$observedProducerCount; pairComplete=$pairComplete; " +
                    "fresh=$evidenceFresh",
        )
    }
}

/**
 * Reuses the one-shot result only as a topology-local boundary reference. It is deliberately not
 * fed into ScenarioSafetyPolicy: another pixel format, crop, transform, alpha or system-surface
 * set can have a different plane limit and still needs fresh vendor phase evidence.
 */
internal fun capacityReuseGuidance(
    calibration: HwcCapacityCalibrationResult,
): HwcCapacityReuseGuidance {
    val candidate = calibration.candidateLayers
    val device = calibration.observedDeviceLayers
    if (
        calibration.status != HwcCapacityCalibrationStatus.OBSERVED_AT_CANDIDATE ||
        candidate == null ||
        candidate <= 0 ||
        device == null ||
        device < 0
    ) {
        return HwcCapacityReuseGuidance(
            detail = "calibration unavailable; preserve catalog target and verify fresh vendor pair",
        )
    }
    val deviceCeiling = minOf(device, candidate)
    val clientCandidate = (deviceCeiling + 1)
        .takeIf { it in 1..candidate }
    return HwcCapacityReuseGuidance(
        deviceCandidateCeiling = deviceCeiling,
        clientPressureCandidate = clientCandidate,
        detail =
            "advisory only for matching opaque RGB/crop topology; never a universal safety cap",
    )
}

internal class HwcCompositionProbePriorityGate {
    private val owner = AtomicReference<Any?>()

    fun acquire(requestedOwner: Any): Boolean {
        val current = owner.get()
        return when {
            current === requestedOwner -> true
            current != null -> false
            else ->
                owner.compareAndSet(null, requestedOwner) ||
                    owner.get() === requestedOwner
        }
    }

    fun release(requestedOwner: Any): Boolean =
        owner.compareAndSet(requestedOwner, null)

    fun isActive(): Boolean = owner.get() != null

    fun reset() {
        owner.set(null)
    }
}

internal enum class HwcCompositionProbeAction {
    COMPLETE,
    FORCE_SAMPLE,
    EXHAUSTED,
}

internal fun hwcCompositionProbeAction(
    expectation: HwcCompositionExpectation,
    matchingEvidenceCount: Int,
    terminalFailure: Boolean,
    forcedAttempts: Int,
): HwcCompositionProbeAction {
    val requiredEvidence = requiredHwcMatchingEvidenceCount(expectation)
    if (
        terminalFailure ||
        requiredEvidence == 0 ||
        matchingEvidenceCount.coerceAtLeast(0) >= requiredEvidence
    ) {
        return HwcCompositionProbeAction.COMPLETE
    }
    return if (forcedAttempts.coerceAtLeast(0) >= requiredEvidence) {
        HwcCompositionProbeAction.EXHAUSTED
    } else {
        HwcCompositionProbeAction.FORCE_SAMPLE
    }
}

internal fun hwcCompositionContractPreserved(
    requested: PhaseSpec,
    effective: PhaseSpec,
): Boolean {
    if (requested.hwcCompositionExpectation == HwcCompositionExpectation.NONE) return true
    return requested.hwcCompositionExpectation == effective.hwcCompositionExpectation &&
        requested.activeLayers == effective.activeLayers &&
        requested.producerFps == effective.producerFps &&
        requested.requestedDisplayHz == effective.requestedDisplayHz &&
        requested.backend == effective.backend &&
        requested.pixelRoute == effective.pixelRoute &&
        requested.bufferSize == effective.bufferSize &&
        requested.motion == effective.motion &&
        requested.layerSizeProfile == effective.layerSizeProfile &&
        requested.workloads == effective.workloads &&
        requested.alphaOverlap == effective.alphaOverlap &&
        requested.includeGlLayer == effective.includeGlLayer
}

internal fun hwcCompositionContractDeltaSummary(
    requested: PhaseSpec,
    effective: PhaseSpec,
): String =
    "layers=${requested.activeLayers}→${effective.activeLayers}; " +
        "producerFps=${requested.producerFps}→${effective.producerFps}; " +
        "displayHz=${requested.requestedDisplayHz}→${effective.requestedDisplayHz}; " +
        "size=${requested.layerSizeProfile.name}→${effective.layerSizeProfile.name}; " +
        "gpu=${requested.workloads.gpu}→${effective.workloads.gpu}; " +
        "gl=${requested.includeGlLayer}→${effective.includeGlLayer}"

internal data class HwcCompositionCoverageResult(
    val expectation: HwcCompositionExpectation,
    val targetReadyAtMs: Long?,
    val freshEvidenceCount: Int,
    val matchingEvidenceCount: Int,
    val gapCount: Int,
    val lastDeviceLayers: Int?,
    val lastClientLayers: Int?,
    val lastQuality: MetricQuality?,
    val lastSource: String?,
    val lastEvidenceMonotonicMs: Long?,
    val validDeviceLayers: Int?,
    val validClientLayers: Int?,
    val validQuality: MetricQuality?,
    val validSource: String?,
    val validEvidenceMonotonicMs: Long?,
    val failureReason: String?,
) {
    val satisfied: Boolean
        get() = failureReason == null

    fun eventSummary(runStartMonotonicMs: Long): String =
        "expectation=${expectation.name}; " +
            "targetReadyRunMs=" +
            "${monotonicTimestampRelativeToRun(targetReadyAtMs, runStartMonotonicMs) ?: "N/A"}; " +
            "fresh=$freshEvidenceCount; matched=$matchingEvidenceCount; gaps=$gapCount; " +
            "lastDevice=${lastDeviceLayers ?: "N/A"}; " +
            "lastClient=${lastClientLayers ?: "N/A"}; " +
            "quality=${lastQuality?.name ?: "N/A"}; " +
            "source=${lastSource?.take(MAX_HWC_EVENT_SOURCE_CHARS) ?: "N/A"}; " +
            "evidenceRunMs=" +
            "${monotonicTimestampRelativeToRun(
                lastEvidenceMonotonicMs,
                runStartMonotonicMs,
            ) ?: "N/A"}; " +
            "validDevice=${validDeviceLayers ?: "N/A"}; " +
            "validClient=${validClientLayers ?: "N/A"}"
}

internal data class HwcCompositionChainAnchor(
    val expectation: HwcCompositionExpectation,
    val quality: MetricQuality,
    val source: String,
    val deviceLayers: Int,
    val clientLayers: Int,
    val evidenceMonotonicMs: Long,
    val requestedActiveLayers: Int,
)

internal data class HwcCompositionChainResult(
    val priorAnchor: HwcCompositionChainAnchor?,
    val currentAnchor: HwcCompositionChainAnchor?,
    val nextAnchor: HwcCompositionChainAnchor?,
    val failureReason: String?,
) {
    fun eventSummary(runStartMonotonicMs: Long): String {
        val prior = priorAnchor
        val current = currentAnchor
        val deviceDelta =
            if (prior != null && current != null) {
                current.deviceLayers - prior.deviceLayers
            } else {
                null
            }
        val clientDelta =
            if (prior != null && current != null) {
                current.clientLayers - prior.clientLayers
            } else {
                null
            }
        val requestedLayerDelta =
            if (prior != null && current != null) {
                current.requestedActiveLayers - prior.requestedActiveLayers
            } else {
                null
            }
        return "chainBaselineDevice=${prior?.deviceLayers ?: "N/A"}; " +
            "chainBaselineClient=${prior?.clientLayers ?: "N/A"}; " +
            "chainCurrentDevice=${current?.deviceLayers ?: "N/A"}; " +
            "chainCurrentClient=${current?.clientLayers ?: "N/A"}; " +
            "deviceDelta=${deviceDelta ?: "N/A"}; " +
            "clientDelta=${clientDelta ?: "N/A"}; " +
            "requestedLayerDelta=${requestedLayerDelta ?: "N/A"}; " +
            "chainEvidenceRunMs=" +
            "${monotonicTimestampRelativeToRun(
                current?.evidenceMonotonicMs,
                runStartMonotonicMs,
            ) ?: "N/A"}"
    }
}

/**
 * Links typed HWC phases into one causal, same-provenance observation chain.
 *
 * CLIENT_REQUIRED must increase CLIENT composition relative to a prior typed baseline. Consecutive
 * DEVICE_ONLY phases follow the requested layer-count edge: low→high must increase DEVICE count and
 * high→low must decrease it. A CLIENT_REQUIRED→DEVICE_ONLY release instead proves that CLIENT
 * composition fell to zero; DEVICE count may legitimately stay level or rise. A phase-local
 * expectation failure does not stop the chain from advancing with a valid pair, so later
 * burst/release phases still run and preserve their evidence.
 */
internal fun advanceHwcCompositionChain(
    prior: HwcCompositionChainAnchor?,
    coverage: HwcCompositionCoverageResult,
    requestedActiveLayers: Int,
): HwcCompositionChainResult {
    val validDeviceLayers = coverage.validDeviceLayers
    val validClientLayers = coverage.validClientLayers
    val validQuality = coverage.validQuality
    val validSource = coverage.validSource?.takeIf { it.isNotBlank() }
    val validEvidenceMonotonicMs = coverage.validEvidenceMonotonicMs
    val current = if (
        validDeviceLayers != null &&
        validClientLayers != null &&
        validQuality != null &&
        validSource != null &&
        validEvidenceMonotonicMs != null
    ) {
        HwcCompositionChainAnchor(
            expectation = coverage.expectation,
            quality = validQuality,
            source = validSource,
            deviceLayers = validDeviceLayers,
            clientLayers = validClientLayers,
            evidenceMonotonicMs = validEvidenceMonotonicMs,
            requestedActiveLayers = requestedActiveLayers.coerceIn(
                1,
                ScenarioSafetyPolicy.HARD_MAX_LAYERS,
            ),
        )
    } else {
        null
    }
    if (current == null) {
        return HwcCompositionChainResult(
            priorAnchor = prior,
            currentAnchor = null,
            nextAnchor = prior,
            failureReason = null,
        )
    }
    if (prior == null) {
        return HwcCompositionChainResult(
            priorAnchor = null,
            currentAnchor = current,
            nextAnchor = current,
            failureReason = if (
                coverage.expectation == HwcCompositionExpectation.CLIENT_REQUIRED
            ) {
                "CLIENT_REQUIRED has no prior typed HWC baseline"
            } else {
                null
            },
        )
    }
    if (current.quality != prior.quality || current.source != prior.source) {
        return HwcCompositionChainResult(
            priorAnchor = prior,
            currentAnchor = current,
            nextAnchor = prior,
            failureReason =
                "HWC composition quality/source changed across expectation phases",
        )
    }
    if (current.evidenceMonotonicMs <= prior.evidenceMonotonicMs) {
        return HwcCompositionChainResult(
            priorAnchor = prior,
            currentAnchor = current,
            nextAnchor = prior,
            failureReason =
                "HWC composition evidence did not advance across expectation phases",
        )
    }

    val requestedLayerDelta =
        current.requestedActiveLayers - prior.requestedActiveLayers
    val failureReason = when (coverage.expectation) {
        HwcCompositionExpectation.CLIENT_REQUIRED ->
            if (current.clientLayers <= prior.clientLayers) {
                "CLIENT_REQUIRED did not increase CLIENT count from the prior typed baseline; " +
                    "baseline=${prior.clientLayers} current=${current.clientLayers}"
            } else {
                null
            }
        HwcCompositionExpectation.DEVICE_ONLY ->
            if (prior.expectation == HwcCompositionExpectation.CLIENT_REQUIRED) {
                if (current.clientLayers >= prior.clientLayers) {
                    "CLIENT_REQUIRED→DEVICE_ONLY release did not reduce CLIENT count; " +
                        "baseline=${prior.clientLayers} current=${current.clientLayers}"
                } else {
                    null
                }
            } else {
                when {
                    requestedLayerDelta > 0 &&
                        current.deviceLayers <= prior.deviceLayers ->
                        "DEVICE_ONLY low→high edge did not increase DEVICE count; " +
                            "baseline=${prior.deviceLayers} current=${current.deviceLayers}"
                    requestedLayerDelta < 0 &&
                        current.deviceLayers >= prior.deviceLayers ->
                        "DEVICE_ONLY high→low edge did not decrease DEVICE count; " +
                            "baseline=${prior.deviceLayers} current=${current.deviceLayers}"
                    else -> null
                }
            }
        HwcCompositionExpectation.NONE -> null
    }
    return HwcCompositionChainResult(
        priorAnchor = prior,
        currentAnchor = current,
        nextAnchor = current,
        failureReason = failureReason,
    )
}

internal fun hwcCompositionTargetReadyForArm(
    ready: Boolean,
    topologyPublished: Boolean,
    topologyPending: Boolean,
    processLeaseActive: Boolean,
    pendingBoundaryExists: Boolean,
    topologyMissed: Boolean,
    teardownFailed: Boolean,
    teardownCompleted: Boolean,
    runtimeFailurePresent: Boolean,
    expectedProducerCount: Int,
    observedProducerCount: Int,
    expectedTopologyRevision: Long,
    observedTopologyRevision: Long,
): Boolean =
    ready &&
        topologyPublished &&
        !topologyPending &&
        !processLeaseActive &&
        !pendingBoundaryExists &&
        !topologyMissed &&
        !teardownFailed &&
        !teardownCompleted &&
        !runtimeFailurePresent &&
        expectedProducerCount > 0 &&
        observedProducerCount == expectedProducerCount &&
        expectedTopologyRevision >= 0L &&
        observedTopologyRevision == expectedTopologyRevision

/**
 * Verifies a phase's composition observation contract without treating topology as a guarantee.
 *
 * The controller arms this tracker only after the STEP target's producer topology and first
 * generation-scoped buffers are acknowledged. A sample that started before that boundary, cached
 * evidence captured before that sample, stale evidence, mixed DEVICE/CLIENT provenance, and
 * repeated evidence timestamps cannot satisfy coverage.
 */
internal class HwcCompositionCoverageTracker(
    private val expectation: HwcCompositionExpectation,
) {
    private data class EvidenceTuple(
        val deviceLayers: Int?,
        val clientLayers: Int?,
        val deviceQuality: MetricQuality,
        val clientQuality: MetricQuality,
        val deviceSource: String,
        val clientSource: String,
    )

    private var targetReadyAtMs: Long? = null
    private var lastEvidenceMonotonicMs: Long? = null
    private var lastEvidenceTuple: EvidenceTuple? = null
    private var stableQuality: MetricQuality? = null
    private var stableSource: String? = null
    private var freshEvidenceCount = 0
    private var matchingEvidenceCount = 0
    private var gapCount = 0
    private var lastDeviceLayers: Int? = null
    private var lastClientLayers: Int? = null
    private var lastObservedQuality: MetricQuality? = null
    private var lastObservedSource: String? = null
    private var lastValidDeviceLayers: Int? = null
    private var lastValidClientLayers: Int? = null
    private var lastValidEvidenceMonotonicMs: Long? = null
    private var firstFailureReason: String? = null

    init {
        require(expectation != HwcCompositionExpectation.NONE) {
            "NONE does not require a composition coverage tracker"
        }
    }

    @Synchronized
    fun activateTarget(targetReadyAtMs: Long): Boolean {
        if (this.targetReadyAtMs != null) return false
        this.targetReadyAtMs = targetReadyAtMs.coerceAtLeast(0L)
        return true
    }

    @Synchronized
    fun targetActivated(): Boolean = targetReadyAtMs != null

    @Synchronized
    fun hasSatisfiedEvidence(): Boolean =
        firstFailureReason == null &&
            matchingEvidenceCount >= requiredHwcMatchingEvidenceCount(expectation)

    @Synchronized
    fun probeAction(forcedAttempts: Int): HwcCompositionProbeAction =
        hwcCompositionProbeAction(
            expectation = expectation,
            matchingEvidenceCount = matchingEvidenceCount,
            terminalFailure = firstFailureReason != null,
            forcedAttempts = forcedAttempts,
        )

    @Synchronized
    fun hasTerminalFailure(): Boolean = firstFailureReason != null

    @Synchronized
    fun recordContractFailure(reason: String) {
        failOnce(reason.ifBlank { "typed HWC target contract changed at runtime" })
    }

    @Synchronized
    fun recordProbeFailure(reason: String) {
        if (targetReadyAtMs == null) return
        gapCount++
        failOnce(reason.ifBlank { "forced HWC composition sample failed" })
    }

    @Synchronized
    fun observe(
        sampleStartedMonotonicMs: Long,
        snapshot: TelemetrySnapshot,
    ) {
        val boundaryMs = targetReadyAtMs ?: return
        if (sampleStartedMonotonicMs < boundaryMs) return

        val evidenceMs = snapshot.hwcCompositionEvidenceMonotonicMs
        if (evidenceMs == null) {
            gapCount++
            failOnce("HWC composition evidence timestamp became unavailable")
            return
        }
        if (evidenceMs < 0L) {
            gapCount++
            failOnce("HWC composition evidence timestamp was negative")
            return
        }
        if (evidenceMs < boundaryMs) {
            // A pre-target SurfaceFlinger cache entry is neither evidence nor an active gap.
            return
        }

        val tuple = EvidenceTuple(
            deviceLayers = snapshot.hwcDeviceLayers,
            clientLayers = snapshot.hwcClientLayers,
            deviceQuality = snapshot.hwcDeviceLayersQuality,
            clientQuality = snapshot.hwcClientLayersQuality,
            deviceSource = snapshot.hwcDeviceLayersSource,
            clientSource = snapshot.hwcClientLayersSource,
        )
        val evidenceAgeMs = snapshot.hwcCompositionEvidenceAgeMs
        val computedAgeMs = if (snapshot.monotonicMs >= evidenceMs) {
            snapshot.monotonicMs - evidenceMs
        } else {
            null
        }
        if (
            evidenceAgeMs == null ||
            computedAgeMs == null ||
            evidenceAgeMs != computedAgeMs ||
            evidenceAgeMs !in 0L..HWC_COMPOSITION_EVIDENCE_MAX_AGE_MS
        ) {
            gapCount++
            failOnce("HWC composition evidence age was unavailable, inconsistent, or stale")
            return
        }
        val previousEvidenceMs = lastEvidenceMonotonicMs
        if (previousEvidenceMs != null && evidenceMs <= previousEvidenceMs) {
            if (evidenceMs < previousEvidenceMs) {
                gapCount++
                failOnce("HWC composition evidence timestamp regressed")
            } else if (lastEvidenceTuple != tuple) {
                gapCount++
                failOnce("HWC composition values changed under one evidence timestamp")
            }
            // Identical cached evidence is intentionally counted once.
            return
        }
        if (evidenceMs < sampleStartedMonotonicMs) {
            // This sample only projected a cache entry captured by an earlier request.
            return
        }

        lastEvidenceMonotonicMs = evidenceMs
        lastEvidenceTuple = tuple
        lastDeviceLayers = tuple.deviceLayers
        lastClientLayers = tuple.clientLayers
        lastObservedQuality = tuple.deviceQuality
            .takeIf { it == tuple.clientQuality }
        lastObservedSource = if (tuple.deviceSource == tuple.clientSource) {
            tuple.deviceSource.take(MAX_HWC_EVENT_SOURCE_CHARS)
        } else {
            tuple.deviceSource.take(MAX_HWC_EVENT_SOURCE_CHARS / 2) +
                "|" +
                tuple.clientSource.take(MAX_HWC_EVENT_SOURCE_CHARS / 2)
        }
        freshEvidenceCount++

        val device = tuple.deviceLayers
        val client = tuple.clientLayers
        val deviceSource = tuple.deviceSource.trim()
        val clientSource = tuple.clientSource.trim()
        val validQuality =
            tuple.deviceQuality == tuple.clientQuality &&
                tuple.deviceQuality in HWC_COMPOSITION_QUALITIES
        if (
            device == null ||
            client == null ||
            device < 0 ||
            client < 0 ||
            !validQuality ||
            deviceSource.isEmpty() ||
            deviceSource != clientSource
        ) {
            gapCount++
            failOnce(
                "HWC DEVICE/CLIENT pair was unavailable or had mixed provenance",
            )
            return
        }

        val priorQuality = stableQuality
        val priorSource = stableSource
        if (
            (priorQuality != null && priorQuality != tuple.deviceQuality) ||
            (priorSource != null && priorSource != deviceSource)
        ) {
            failOnce("HWC composition quality/source changed during the target window")
            return
        }
        stableQuality = tuple.deviceQuality
        stableSource = deviceSource
        lastValidDeviceLayers = device
        lastValidClientLayers = client
        lastValidEvidenceMonotonicMs = evidenceMs

        when (expectation) {
            HwcCompositionExpectation.DEVICE_ONLY -> {
                if (device > 0 && client == 0) {
                    matchingEvidenceCount++
                } else {
                    failOnce(
                        "DEVICE_ONLY requires device>0 and client==0; " +
                            "observed device=$device client=$client",
                    )
                }
            }
            HwcCompositionExpectation.CLIENT_REQUIRED -> {
                if (client > 0) matchingEvidenceCount++
            }
            HwcCompositionExpectation.NONE -> Unit
        }
    }

    @Synchronized
    fun result(): HwcCompositionCoverageResult {
        val requiredMatches = requiredHwcMatchingEvidenceCount(expectation)
        val finalFailure = firstFailureReason ?: when {
            targetReadyAtMs == null ->
                "target producer topology never reached an acknowledged STEP target"
            freshEvidenceCount == 0 ->
                "no fresh HWC composition evidence was observed after target readiness"
            matchingEvidenceCount < requiredMatches &&
                expectation == HwcCompositionExpectation.CLIENT_REQUIRED ->
                "CLIENT_REQUIRED requires $requiredMatches distinct fresh client>0 observations; " +
                    "observed=$matchingEvidenceCount"
            matchingEvidenceCount < requiredMatches ->
                "DEVICE_ONLY did not observe device>0 and client==0"
            else -> null
        }
        return HwcCompositionCoverageResult(
            expectation = expectation,
            targetReadyAtMs = targetReadyAtMs,
            freshEvidenceCount = freshEvidenceCount,
            matchingEvidenceCount = matchingEvidenceCount,
            gapCount = gapCount,
            lastDeviceLayers = lastDeviceLayers,
            lastClientLayers = lastClientLayers,
            lastQuality = lastObservedQuality,
            lastSource = lastObservedSource,
            lastEvidenceMonotonicMs = lastEvidenceMonotonicMs,
            validDeviceLayers = lastValidDeviceLayers,
            validClientLayers = lastValidClientLayers,
            validQuality = stableQuality,
            validSource = stableSource,
            validEvidenceMonotonicMs = lastValidEvidenceMonotonicMs,
            failureReason = finalFailure,
        )
    }

    private fun failOnce(reason: String) {
        if (firstFailureReason == null) {
            firstFailureReason = reason.take(MAX_HWC_FAILURE_REASON_CHARS)
        }
    }
}

internal fun requiredHwcMatchingEvidenceCount(
    expectation: HwcCompositionExpectation,
): Int = when (expectation) {
    HwcCompositionExpectation.CLIENT_REQUIRED -> 2
    HwcCompositionExpectation.DEVICE_ONLY -> 1
    HwcCompositionExpectation.NONE -> 0
}

internal fun verdictWithHwcCompositionCoverage(
    evidenceVerdict: RunVerdict,
    coverageFailureReason: String?,
): RunVerdict {
    if (coverageFailureReason.isNullOrBlank()) return evidenceVerdict
    return when (evidenceVerdict) {
        RunVerdict.UNDERRUN_DETECTED,
        RunVerdict.UNSUPPORTED,
        RunVerdict.ABORTED,
        -> evidenceVerdict
        RunVerdict.CLEAN,
        RunVerdict.SUSPECTED_PROXY,
        RunVerdict.INCONCLUSIVE,
        -> RunVerdict.INCONCLUSIVE
    }
}

private val HWC_COMPOSITION_QUALITIES = setOf(
    MetricQuality.HARDWARE_COUNTER,
    MetricQuality.SYSTEM_SERVICE,
)
private const val MAX_HWC_EVENT_SOURCE_CHARS = 256
private const val MAX_HWC_FAILURE_REASON_CHARS = 512

internal fun safetyEnvelopeInvalidatedByPowerSave(
    envelopePowerSaveMode: Boolean,
    currentPowerSaveMode: Boolean,
): Boolean = !envelopePowerSaveMode && currentPowerSaveMode

internal fun shouldStopActivePlan(jobPresent: Boolean, isRunning: Boolean): Boolean =
    jobPresent && isRunning

internal fun thermalDerateActionFailed(
    orderedZeroConfirmed: Boolean,
    workloadApplied: Boolean,
    displayApplied: Boolean,
): Boolean = !orderedZeroConfirmed || !workloadApplied || !displayApplied

internal fun producerRecoverySafePointFailed(
    loadsReleased: Boolean,
    displayReduced: Boolean,
): Boolean = !loadsReleased || !displayReduced

internal fun visibleExpectedProducerCount(
    committedExpectedCount: Int,
    topologyPublished: Boolean,
    topologyPending: Boolean,
    processLeaseActive: Boolean,
): Int = if (!topologyPublished || topologyPending || processLeaseActive) {
    0
} else {
    committedExpectedCount.coerceAtLeast(0)
}

/**
 * Waits for a blocking platform worker without pinning the caller's coroutine to await().
 * Cancellation returns after the next bounded poll; the worker's retained process lease remains
 * active until its own finally block actually runs.
 */
internal suspend fun awaitLatchCancellable(
    latch: CountDownLatch,
    timeoutMs: Long,
    pollMs: Long = MEDIA_PREFLIGHT_AWAIT_POLL_MS,
): Boolean {
    val boundedTimeoutMs = timeoutMs.coerceAtLeast(0L)
    val boundedPollMs = pollMs.coerceIn(1L, MEDIA_PREFLIGHT_AWAIT_MAX_POLL_MS)
    val startedNanos = System.nanoTime()
    val timeoutNanos = if (
        boundedTimeoutMs > Long.MAX_VALUE / NANOS_PER_MILLISECOND
    ) {
        Long.MAX_VALUE
    } else {
        boundedTimeoutMs * NANOS_PER_MILLISECOND
    }
    while (true) {
        if (latch.count == 0L) return true
        currentCoroutineContext().ensureActive()
        val elapsedNanos = (System.nanoTime() - startedNanos).coerceAtLeast(0L)
        if (elapsedNanos >= timeoutNanos) return latch.count == 0L
        val remainingNanos = timeoutNanos - elapsedNanos
        val remainingMs = (
            (remainingNanos / NANOS_PER_MILLISECOND) +
                if (remainingNanos % NANOS_PER_MILLISECOND == 0L) 0L else 1L
            ).coerceAtLeast(1L)
        delay(min(boundedPollMs, remainingMs))
    }
}

/**
 * Resolves only completed reports owned by this application. Plan history stores paths so earlier
 * loop items can be shared, but those paths must never broaden FileProvider access.
 */
internal fun resolveManagedReportFile(
    reportsDirectory: File,
    reportPath: String?,
): File? {
    val boundedPath = reportPath
        ?.takeIf { it.isNotBlank() && it.length <= MAX_SHARE_REPORT_PATH_CHARS }
        ?: return null
    val canonicalDirectory = runCatching { reportsDirectory.canonicalFile }.getOrNull()
        ?: return null
    val requestedFile = File(boundedPath)
    if (!requestedFile.isAbsolute) return null
    val canonicalFile = runCatching { requestedFile.canonicalFile }.getOrNull()
        ?: return null
    if (canonicalFile.parentFile != canonicalDirectory) return null
    if (!isManagedCompletedReportName(canonicalFile.name)) return null
    return canonicalFile.takeIf(File::isFile)
}

/**
 * Best-effort deletion for an obsolete publication. The same strict resolver used by report
 * sharing keeps a corrupted/injected path from deleting foreign files.
 */
internal fun deleteManagedCompletedReportBestEffort(
    reportsDirectory: File,
    reportFile: File?,
): Boolean = runCatching {
    val managed = resolveManagedReportFile(
        reportsDirectory = reportsDirectory,
        reportPath = reportFile?.absolutePath,
    ) ?: return@runCatching false
    managed.delete()
}.getOrDefault(false)

/**
 * Allows sharing only artifacts still published by current controller state. A stale Compose click
 * callback can retain an old PlanRunResult briefly after recomposition, so the managed-file check
 * alone is insufficient when best-effort deletion fails.
 */
internal fun isPublishedReportForSharing(
    reportFile: File,
    lastReportFile: File?,
    planResultPaths: Sequence<String?>,
): Boolean {
    val canonicalReportPath = runCatching { reportFile.canonicalPath }.getOrNull() ?: return false
    fun matches(candidatePath: String?): Boolean {
        val boundedPath = candidatePath
            ?.takeIf { it.isNotBlank() && it.length <= MAX_SHARE_REPORT_PATH_CHARS }
            ?: return false
        val candidate = File(boundedPath)
        if (!candidate.isAbsolute) return false
        return runCatching { candidate.canonicalPath == canonicalReportPath }.getOrDefault(false)
    }
    return matches(lastReportFile?.absolutePath) || planResultPaths.any(::matches)
}

internal data class PlanRestoreFailureInvalidation(
    val updatedResults: List<PlanRunResult>,
    val invalidatedReportPaths: List<String>,
)

/**
 * A successful END is not enough to publish a successful run when its revised report could not be
 * atomically written. Preserve any exact restore event, but make the durable summary terminally
 * ABORTED so a later finalizer retry cannot republish the pre-failure CLEAN/PASSED verdict.
 */
internal fun markPerformanceRestoreReportPublicationFailed(
    summary: RunSummary,
    finishedEpochMs: Long,
    monotonicMs: Long,
    failureType: String,
): RunSummary {
    val boundedFailure = failureType
        .trim()
        .ifEmpty { "Unknown" }
        .take(MAX_REPORT_PUBLICATION_FAILURE_TYPE_CHARS)
    val events = if (
        summary.events.any { it.type == PERFORMANCE_RESTORE_REPORT_WRITE_FAILED_EVENT }
    ) {
        summary.events
    } else {
        summary.events + RunEvent(
            monotonicMs = monotonicMs.coerceAtLeast(0L),
            type = PERFORMANCE_RESTORE_REPORT_WRITE_FAILED_EVENT,
            message =
                "Performance restore outcome report publication failed: $boundedFailure",
        )
    }
    return summary.copy(
        finishedEpochMs = finishedEpochMs.coerceAtLeast(summary.startedEpochMs),
        verdict = RunVerdict.ABORTED,
        events = events,
    )
}

/**
 * Reports written before a plan-wide performance lease ends cannot truthfully claim that cleanup
 * succeeded. If END/renewal cleanup fails, retain their compact UI rows as ABORTED evidence but
 * withdraw every earlier JSON publication. The current run is rewritten separately with the
 * detailed restore-failure event.
 */
internal fun invalidateEarlierPlanResultsForRestoreFailure(
    results: List<PlanRunResult>,
    currentStartedEpochMs: Long,
    currentScenarioId: String,
    terminalReason: String,
): PlanRestoreFailureInvalidation {
    val currentIndex = results.indexOfLast { result ->
        result.startedEpochMs == currentStartedEpochMs &&
            result.scenario.id == currentScenarioId
    }
    val invalidationEndExclusive = if (currentIndex >= 0) currentIndex else results.size
    val boundedReason = terminalReason.take(MAX_PLAN_RESTORE_FAILURE_REASON_CHARS)
    val invalidatedPaths = ArrayList<String>(invalidationEndExclusive)
    val updated = results.mapIndexed { index, result ->
        if (index >= invalidationEndExclusive) {
            result
        } else {
            result.reportPath?.let(invalidatedPaths::add)
            result.copy(
                verdict = RunVerdict.ABORTED,
                reportPath = null,
                terminalReason = boundedReason,
            )
        }
    }
    return PlanRestoreFailureInvalidation(
        updatedResults = updated,
        invalidatedReportPaths = invalidatedPaths,
    )
}

private const val MAX_SHARE_REPORT_PATH_CHARS = 4_096
private const val MAX_PLAN_RESTORE_FAILURE_REASON_CHARS = 300
private const val MAX_REPORT_PUBLICATION_FAILURE_TYPE_CHARS = 80
private const val MEDIA_PREFLIGHT_AWAIT_POLL_MS = 20L
private const val MEDIA_PREFLIGHT_AWAIT_MAX_POLL_MS = 50L
private const val NANOS_PER_MILLISECOND = 1_000_000L

internal class PlanAbortException(message: String) : CancellationException(message)

internal class InconclusiveRunException(message: String) : Exception(message)

/**
 * Serializes the complete descriptor-open + native-parser preflight across Activity recreation.
 *
 * Every daemon gets a retained hold before it starts. The root hold is released when the calling
 * preflight returns, while a timed-out worker keeps its own hold until its real `finally`. This
 * closes both the pre-timeout overlap race and the provider-to-parser hand-off gap.
 */
internal object MediaPreflightSafetyState {
    private val active = AtomicBoolean(false)

    fun tryAcquire(): MediaPreflightLease? {
        if (!active.compareAndSet(false, true)) return null
        return MediaPreflightLease {
            check(active.compareAndSet(true, false)) {
                "Media preflight lease released without an active owner"
            }
        }
    }

    fun isActive(): Boolean = active.get()
}

internal class MediaPreflightLease(
    private val onFullyReleased: () -> Unit,
) : AutoCloseable {
    private val holdCount = AtomicInteger(1)
    private val rootClosed = AtomicBoolean(false)

    fun retain(): AutoCloseable? {
        while (true) {
            val current = holdCount.get()
            if (current <= 0 || current == Int.MAX_VALUE) return null
            if (holdCount.compareAndSet(current, current + 1)) {
                return RetainedHold(this)
            }
        }
    }

    override fun close() {
        if (rootClosed.compareAndSet(false, true)) releaseHold()
    }

    private fun releaseHold() {
        val remaining = holdCount.decrementAndGet()
        check(remaining >= 0) { "Media preflight lease over-released" }
        if (remaining == 0) onFullyReleased()
    }

    private class RetainedHold(
        private val owner: MediaPreflightLease,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) owner.releaseHold()
        }
    }
}

/**
 * Process-wide evidence for a vendor compression mode that may outlive an Activity.
 *
 * A failed reset never clears prior uncertainty. A later controller may clear it only after its
 * own explicit linear/default command is acknowledged.
 */
internal object CompressionSafetyState {
    private val cleanupRequired = AtomicBoolean(false)

    fun markNonLinearRouteMayBeActive() {
        cleanupRequired.set(true)
    }

    fun recordLinearReset(confirmed: Boolean) {
        if (confirmed) cleanupRequired.set(false)
    }

    fun hasUnconfirmedCompressionCleanup(): Boolean = cleanupRequired.get()
}

internal fun preserveFirstCancellationReason(
    current: String?,
    requested: String?,
    fallback: String,
    maxChars: Int,
): String {
    require(maxChars > 0)
    current
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let { return it.take(maxChars) }
    return requested
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.take(maxChars)
        ?: fallback.trim().ifEmpty { "Cancelled" }.take(maxChars)
}

internal fun mediaDescriptorCleanupConfirmed(
    selectedDescriptorReleased: Boolean,
    pendingDescriptorReleased: Boolean,
    processCleanupUnconfirmed: Boolean,
): Boolean =
    selectedDescriptorReleased &&
        pendingDescriptorReleased &&
        !processCleanupUnconfirmed

internal class TelemetrySampleGenerationGate {
    private var lastIssued = 0L
    private var currentRunFloor = Long.MAX_VALUE

    @Synchronized
    fun issue(): Long {
        check(lastIssued < Long.MAX_VALUE) { "telemetry sample generation exhausted" }
        return ++lastIssued
    }

    /**
     * Excludes every request already issued, including one still executing on the IO dispatcher.
     */
    @Synchronized
    fun beginRun() {
        currentRunFloor =
            if (lastIssued == Long.MAX_VALUE) Long.MAX_VALUE else lastIssued + 1L
    }

    @Synchronized
    fun belongsToCurrentRun(generation: Long): Boolean =
        generation >= currentRunFloor
}

internal fun telemetrySampleDeadlineMs(
    sampleStartedMs: Long,
    sampleTimeoutMs: Long,
    completionGraceMs: Long,
): Long {
    require(sampleStartedMs >= 0L)
    require(sampleTimeoutMs > 0L)
    require(completionGraceMs >= 0L)
    return saturatingAddNonNegative(
        saturatingAddNonNegative(sampleStartedMs, sampleTimeoutMs),
        completionGraceMs,
    )
}

internal fun shouldAbortTelemetryWatchdog(
    nowMs: Long,
    lastSuccessfulSampleMs: Long,
    staleTimeoutMs: Long,
    inFlightDeadlineMs: Long?,
    intentionallyPaused: Boolean = false,
    resumeGraceDeadlineMs: Long? = null,
): Boolean {
    require(nowMs >= 0L)
    require(lastSuccessfulSampleMs >= 0L)
    require(staleTimeoutMs > 0L)
    require(inFlightDeadlineMs == null || inFlightDeadlineMs >= 0L)
    require(resumeGraceDeadlineMs == null || resumeGraceDeadlineMs >= 0L)
    if (intentionallyPaused) return false
    if (resumeGraceDeadlineMs != null && nowMs <= resumeGraceDeadlineMs) return false
    val staleDeadline = saturatingAddNonNegative(
        lastSuccessfulSampleMs,
        staleTimeoutMs,
    )
    if (nowMs <= staleDeadline) return false
    return inFlightDeadlineMs == null || nowMs > inFlightDeadlineMs
}

internal fun isFatalControllerStartupFailure(error: Throwable): Boolean =
    error is Error

internal fun isFatalTelemetryStartupFailure(error: Throwable): Boolean =
    isFatalControllerStartupFailure(error)

internal fun unexpectedJobCompletionReason(
    operation: String,
    cause: Throwable?,
): String? =
    if (cause != null && cause !is CancellationException) {
        "$operation Job failed unexpectedly: ${cause.javaClass.simpleName}"
    } else {
        null
    }

internal enum class TelemetryPairStartDecision {
    START_NEW_PAIR,
    REUSE_ACTIVE_PAIR,
    WAIT_FOR_TERMINATION,
    REJECT_UNTRUSTED_LIFECYCLE,
}

/**
 * Monitor and watchdog form one ownership generation. Never overwrite a surviving half or treat a
 * cancellation request as terminal evidence; replacement waits until both completion callbacks
 * have cleared their exact published identities.
 */
internal fun telemetryPairStartDecision(
    monitorPresent: Boolean,
    monitorActive: Boolean,
    watchdogPresent: Boolean,
    watchdogActive: Boolean,
    lifecycleIntegrityConfirmed: Boolean,
): TelemetryPairStartDecision {
    require(!monitorActive || monitorPresent)
    require(!watchdogActive || watchdogPresent)
    if (!lifecycleIntegrityConfirmed) {
        return TelemetryPairStartDecision.REJECT_UNTRUSTED_LIFECYCLE
    }
    if (!monitorPresent && !watchdogPresent) {
        return TelemetryPairStartDecision.START_NEW_PAIR
    }
    if (monitorActive && watchdogActive) {
        return TelemetryPairStartDecision.REUSE_ACTIVE_PAIR
    }
    return TelemetryPairStartDecision.WAIT_FOR_TERMINATION
}

internal fun unexpectedLongLivedWorkerCompletionReason(
    operation: String,
    cause: Throwable?,
    expectedStop: Boolean,
): String? = when {
    cause != null && cause !is CancellationException ->
        "$operation Job failed unexpectedly: ${cause.javaClass.simpleName}"
    expectedStop -> null
    cause is CancellationException ->
        "$operation Job was cancelled without an owning stop request"
    else -> "$operation Job exited while continuous monitoring was required"
}

internal fun shouldFailPerformanceIsolationAfterRenewalCompletion(
    operationFailure: String?,
    runOwnerMatches: Boolean,
    runOwnerActive: Boolean,
    isolationMonitoringExpected: Boolean,
): Boolean =
    operationFailure != null &&
        runOwnerMatches &&
        runOwnerActive &&
        isolationMonitoringExpected

private fun bestEffortCleanup(
    primaryFailure: Throwable,
    cleanup: () -> Unit,
) {
    try {
        cleanup()
    } catch (cleanupFailure: Throwable) {
        if (cleanupFailure !== primaryFailure) {
            try {
                primaryFailure.addSuppressed(cleanupFailure)
            } catch (_: Throwable) {
                // Preserve the original startup failure even when suppression is unavailable.
            }
        }
    }
}

/**
 * A completion callback should normally be attached immediately after LAZY Job construction. If
 * callback attachment itself fails, cancellation of an unstarted Job is usually synchronous; if
 * it is not, install one final Activity-free callback and leave the completion group pending when
 * even that cannot be established.
 */
private fun completeUnattachedStartupOperation(
    job: Job?,
    completion: TransactionalCompletionRegistration,
    primaryFailure: Throwable,
    onTerminal: (() -> Unit)? = null,
) {
    if (job == null || job.isCompleted) {
        bestEffortCleanup(primaryFailure) { completion.completeOperation() }
        if (job != null) bestEffortCleanup(primaryFailure) { onTerminal?.invoke() }
        return
    }
    bestEffortCleanup(primaryFailure) {
        job.invokeOnCompletion {
            completion.completeOperation()
            bestEffortCleanup(primaryFailure) { onTerminal?.invoke() }
        }
    }
}

private fun saturatingAddNonNegative(left: Long, right: Long): Long =
    if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

internal fun shouldAppendAbortedPlanResult(
    activeRunIndex: Int,
    alreadyRecorded: Boolean,
    verdict: RunVerdict?,
): Boolean =
    activeRunIndex >= 0 &&
        verdict == RunVerdict.ABORTED &&
        !alreadyRecorded

internal fun shouldContinuePlanAfter(verdict: RunVerdict): Boolean =
    verdict != RunVerdict.ABORTED

internal data class PlanArtifactProgressDecision(
    val shouldContinue: Boolean,
    val completedRuns: Int,
)

internal fun planArtifactProgressDecision(
    completedRuns: Int,
    verdict: RunVerdict,
): PlanArtifactProgressDecision {
    val shouldContinue = shouldContinuePlanAfter(verdict)
    return PlanArtifactProgressDecision(
        shouldContinue = shouldContinue,
        completedRuns = if (shouldContinue) {
            completedRuns.coerceAtLeast(0) + 1
        } else {
            completedRuns.coerceAtLeast(0)
        },
    )
}

internal fun unexpectedExceptionVerdict(): RunVerdict = RunVerdict.ABORTED

internal fun producerStartupTimedOut(
    phaseDurationMs: Long,
    elapsedMs: Long,
    hasProducedFrame: Boolean,
    graceMs: Long,
): Boolean =
    phaseDurationMs >= graceMs &&
        elapsedMs >= graceMs &&
        !hasProducedFrame

internal fun scenarioAtExpandedIndex(
    plan: ScenarioRunPlan,
    expandedIndex: Int,
): ScenarioSpec? {
    if (
        expandedIndex < 0 ||
        expandedIndex >= plan.totalRuns ||
        plan.scenarios.isEmpty()
    ) {
        return null
    }
    return plan.scenarios[expandedIndex % plan.scenarios.size]
}

private const val ADAPTIVE_HUNT_SCENARIO_ID = "adaptive-underrun-hunt"
private const val ADAPTIVE_HUNT_PHASE_PREFIX = "hunt-"
private const val ADAPTIVE_RECOVERY_PHASE_ID = "hunt-recover"
private const val ADAPTIVE_PROXY_MISS_RATIO = 0.02
private const val MIN_ADAPTIVE_PROXY_MISSES = 3L
