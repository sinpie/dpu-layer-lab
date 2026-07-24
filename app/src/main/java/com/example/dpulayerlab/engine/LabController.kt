package com.example.dpulayerlab.engine

import android.app.Activity
import android.content.Intent
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
import com.example.dpulayerlab.model.BufferSize
import com.example.dpulayerlab.model.DecoderLinearReference
import com.example.dpulayerlab.model.Gauge
import com.example.dpulayerlab.model.LayerBackend
import com.example.dpulayerlab.model.LOAD_CONTROL_CADENCE_MS
import com.example.dpulayerlab.model.LoadTransitionEvaluator
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
import com.example.dpulayerlab.model.usesSelectedMediaDecoder
import com.example.dpulayerlab.model.terminalReason
import com.example.dpulayerlab.monitor.FrameTracker
import com.example.dpulayerlab.monitor.CapabilityScanner
import com.example.dpulayerlab.monitor.CompressionControlResult
import com.example.dpulayerlab.monitor.SystemMonitor
import com.example.dpulayerlab.render.RendererSafetyState
import com.example.dpulayerlab.render.MAX_BOUND_COMPRESSED_SAMPLE_BYTES
import com.example.dpulayerlab.render.MEDIA_KEY_CODECS_STRING_COMPAT
import com.example.dpulayerlab.render.MEDIA_KEY_CROP_BOTTOM_COMPAT
import com.example.dpulayerlab.render.MEDIA_KEY_CROP_LEFT_COMPAT
import com.example.dpulayerlab.render.MEDIA_KEY_CROP_RIGHT_COMPAT
import com.example.dpulayerlab.render.MEDIA_KEY_CROP_TOP_COMPAT
import com.example.dpulayerlab.render.PinnedMediaSource
import com.example.dpulayerlab.render.VideoDecoderSelection
import com.example.dpulayerlab.render.boundedCodecConfigFingerprint
import com.example.dpulayerlab.render.exactMediaIntegerOrNull
import com.example.dpulayerlab.render.fixedVideoMaximumDimensionsMatch
import com.example.dpulayerlab.render.videoDimensionCeiling
import com.example.dpulayerlab.render.visibleVideoDimensions
import com.example.dpulayerlab.util.currentDisplayCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class LabController(
    private val activity: Activity,
    private val requestDisplayMode: (Float) -> Unit,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val loadManager = LoadManager(activity)
    val frameTracker = FrameTracker()
    private val systemMonitor = SystemMonitor(activity, frameTracker, loadManager)
    private var monitorJob: Job? = null
    private var watchdogJob: Job? = null
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
    private var thermalReduced = false
    /** True after an accepted SBWC route until a linear/default reset is acknowledged. */
    private var compressionControlActive = false
    /** Binder registration that acknowledged the currently active non-linear route. */
    private var compressionControlSession: Long? = null
    private var cancellationReason: String? = null
    private var runFinalized = false
    private var pendingRendererGeneration: Long? = null
    private val telemetrySampleMutex = Mutex()
    private val telemetrySampleGate = TelemetrySampleGenerationGate()
    private var controllerCloseCleanupConfirmed: Boolean? = null
    @Volatile
    private var activeProducerFrameBudget: AppliedProducerFrameBudget? = null
    @Volatile
    private var producerRecoveryPaused = false
    private val topologyPendingBoundary =
        AtomicReference<ProducerTopologyPendingBoundary?>()
    private var closed = false
    private var lastSuccessfulSampleMs = SystemClock.elapsedRealtime()

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
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var safetyLimits by mutableStateOf(DeviceRenderSafety.detect(activity))
        private set
    var lastSafetyAdjustments by mutableStateOf<List<String>>(emptyList())
        private set
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

    fun start() {
        if (closed) return
        if (monitorJob?.isActive == true) {
            // FrameTracker.stop() is called from pause(); reset its baseline on every resume.
            frameTracker.start()
            return
        }
        if (!loadManager.start()) {
            errorMessage =
                "Local CPU/memory worker를 안전하게 준비할 수 없습니다. " +
                    "이전 worker 종료 또는 현재 worker 상태를 확인하세요."
        }
        frameTracker.start()
        lastSuccessfulSampleMs = SystemClock.elapsedRealtime()
        monitorJob = scope.launch {
            while (isActive) {
                try {
                    collectTelemetrySample()
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
        watchdogJob = scope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                if (
                    isRunning &&
                    SystemClock.elapsedRealtime() - lastSuccessfulSampleMs > MONITOR_STALE_TIMEOUT_MS &&
                    cancellationReason == null
                ) {
                    abortForSafety(
                        reason = "telemetry가 ${MONITOR_STALE_TIMEOUT_MS / 1_000}초 이상 응답하지 않음",
                        eventType = "MONITOR_WATCHDOG",
                    )
                }
            }
        }
    }

    fun pause() {
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
        monitorJob?.cancel()
        watchdogJob?.cancel()
        monitorJob = null
        watchdogJob = null
        frameTracker.stop()
        releaseGeneratedLoads(dropMemoryBuffers = true)
        resetDisplayModeSafely()
        setWakeStateSafely(false)
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
        // A cancelled Job remains the owner until its NonCancellable renderer/load/report
        // finalizer clears runJob. Starting in that gap would let the old finalizer reset the new
        // run's loads, display mode, and wake state.
        if (planStartBlocked(runJobPresent = runJob != null, isRunning = isRunning)) {
            showError("이미 실행 중인 테스트 plan이 있습니다.")
            return false
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

        lateinit var launchedJob: Job
        launchedJob = scope.launch(start = CoroutineStart.LAZY) {
            var activeRunIndex = -1
            var activeRepeatIndex = -1
            var activeQueueIndex = -1
            try {
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
                        releaseActiveLoadsForRun()
                        resetDisplayModeSafely()
                        setWakeStateSafely(false)
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
        // Dispatchers.Main.immediate may run a normal launch to completion before the assignment
        // above publishes its Job. Publish a lazy owner first, then permit its body to execute.
        runJob = launchedJob
        launchedJob.invokeOnCompletion {
            val clearOwner = Runnable {
                if (publishedJobOwnerMatches(runJob, launchedJob)) {
                    runJob = null
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                clearOwner.run()
            } else {
                mainHandler.post(clearOwner)
            }
        }
        launchedJob.start()
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

    fun onProducerTopologyPending(generation: Long) {
        if (!frameTracker.markProducerTopologyPending(generation)) return
        if (progress.producerGeneration != generation) return
        val boundaryMs = SystemClock.elapsedRealtime()
        val boundary = checkNotNull(
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
            }
        )
        val frameBudget = activeProducerFrameBudget ?: return
        if (producerRecoveryPaused) return
        frameBudget.pauseAtPhysicalBoundary(
            atMonotonicMs = boundary.monotonicMs,
            totalFrames = frameTracker.totalPhysicalProducedFrames(),
        )
        // Do not leave CPU/memory/NPU work active until the next 100 ms controller poll. The
        // callback is delivered on the View/main boundary, so zeroing here is both serialized and
        // the earliest truthful recovery point. The normal recovery loop confirms/resumes later.
        producerRecoveryPaused = true
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

    fun dismissResult() {
        if (!isRunning) {
            progress = RunProgress()
            planProgress = PlanProgress()
        }
    }

    fun shareLastReport() {
        val file = lastReportFile ?: return
        if (!file.isFile) {
            errorMessage = "공유할 보고서 파일이 없습니다."
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
        val hadPublishedProducer =
            progress.phase != null ||
                progress.targetPhase != null ||
                RendererSafetyState.hasUnconfirmedTeardown()
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
        closeSelectedVideoDecoder()
        val loadShutdown = loadManager.closeWithResult()
        // Activity destruction cannot prove that Compose/AndroidView/Surface teardown completed
        // synchronously. Never change the allocation route from this non-suspending lifecycle
        // path; the normal run finalizer is the only path that may reset compression after its
        // explicit renderer barrier. A later controller recovers a sticky non-linear route.
        val rendererReleasedForClose =
            !hadPublishedProducer && !RendererSafetyState.hasUnconfirmedTeardown()
        val shutdown = systemMonitor.close(
            resetCompression = false,
        )
        val compressionCleanupRequired =
            compressionControlActive ||
                CompressionSafetyState.hasUnconfirmedCompressionCleanup()
        if (compressionCleanupRequired) {
            CompressionSafetyState.markNonLinearRouteMayBeActive()
            recordCleanupFailure(
                "Activity 종료 compression 보류",
                IllegalStateException(
                    "lifecycle close cannot prove producer teardown; " +
                        "linear reset was intentionally deferred",
                ),
            )
        }
        if (!loadShutdown.workersStopped) {
            recordCleanupFailure(
                "Activity 종료 local load worker stop",
                IllegalStateException("one or more load workers exceeded the shutdown deadline"),
            )
        }
        val vendorStopRescued =
            loadShutdown.npu.backendCloseConfirmed &&
                shutdown.brokerWasConnected &&
                shutdown.npuStopConfirmed
        val npuLoadReleaseConfirmed =
            loadShutdown.npu.releaseConfirmed ||
                vendorStopRescued
        if (!npuLoadReleaseConfirmed) {
            recordCleanupFailure(
                "Activity 종료 NPU stop",
                IllegalStateException(loadShutdown.npu.detail),
            )
        }
        if (!loadShutdown.npu.backendCloseConfirmed) {
            recordCleanupFailure(
                "Activity 종료 reflection NPU backend close",
                IllegalStateException(loadShutdown.npu.detail),
            )
        }
        LoadSafetyState.recordNpuLoadIdle(npuLoadReleaseConfirmed)
        LoadSafetyState.recordNpuBackendCleanup(loadShutdown.npu.backendCloseConfirmed)
        controllerCloseCleanupConfirmed =
            loadShutdown.workersStopped &&
                npuLoadReleaseConfirmed &&
                loadShutdown.npu.backendCloseConfirmed &&
                rendererReleasedForClose &&
                !CompressionSafetyState.hasUnconfirmedCompressionCleanup()
        scope.cancel()
    }

    private fun resetPlanState() {
        loadManager.clearMemoryAllocationFailure()
        mutablePlanResultHistory.clear()
        thermalReduced = false
        cancellationReason = null
        errorMessage = null
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
        activeProducerFrameBudget = null
        producerRecoveryPaused = false
    }

    private suspend fun executeScenario(requestedScenario: ScenarioSpec): ScenarioRunArtifact {
        resetScenarioRunState()
        var scenarioForReport = requestedScenario
        var cleanupConfirmed = false
        val pendingMediaSource = AtomicReference<PinnedMediaSource?>()
        try {
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
            safetyLimits = DeviceRenderSafety.detect(activity)
            val scenarioUsesSelectedDecoder =
                requestedScenario.phases.any(::canUseVideoPrimary)
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
            if (mediaForRun == null && scenario.phases.any(::canUseVideoPrimary)) {
                runEvents += event(
                    "PROXY_FALLBACK",
                    "로컬 영상이 없어 RGBA 기반 YUV/P010 visual proxy를 사용합니다.",
                )
            } else if (mediaInfo != null) {
                val codecDetail = mediaValidation.decoderSelection?.let {
                    " · hardwareCodec=${it.codecName}"
                }.orEmpty()
                runEvents += event("MEDIA_SOURCE", mediaInfo.description + codecDetail)
            }
            delay(PRECHECK_DELAY_MS)

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
            return finalizeProtected(scenario, preselectedVerdict = null)
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
                finalizeProtected(scenarioForReport, RunVerdict.ABORTED)
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
                finalizeProtected(scenarioForReport, RunVerdict.UNSUPPORTED)
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
                finalizeProtected(scenarioForReport, RunVerdict.ABORTED)
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
                finalizeProtected(scenarioForReport, RunVerdict.INCONCLUSIVE)
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
                finalizeProtected(scenarioForReport, RunVerdict.ABORTED)
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
            return finalizeProtected(scenarioForReport, unexpectedExceptionVerdict())
        } finally {
            activeProducerFrameBudget = null
            withContext(NonCancellable) {
                if (!cleanupConfirmed) releaseActiveLoadsForRun()
                resetDisplayModeSafely()
            }
            closeSelectedVideoDecoder()
            pendingMediaSource.getAndSet(null)?.close()
        }
    }

    private fun closeSelectedVideoDecoder() {
        val previous = selectedVideoDecoder
        selectedVideoDecoder = null
        previous?.close()
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

    private suspend fun collectTelemetrySample(): TelemetrySnapshot =
        telemetrySampleMutex.withLock {
            val generation = telemetrySampleGate.issue()
            val snapshot = systemMonitor.sample(activity.currentDisplayCompat())
            lastSuccessfulSampleMs = SystemClock.elapsedRealtime()
            acceptSnapshot(generation, snapshot)
            snapshot
        }

    private fun acceptSnapshot(generation: Long, snapshot: TelemetrySnapshot) {
        telemetry = snapshot
        telemetryHistory += snapshot
        while (telemetryHistory.size > MAX_TELEMETRY_HISTORY) telemetryHistory.removeAt(0)
        directSensors.clear()
        directSensors.addAll(systemMonitor.directSensorReadings())
        if (!isRunning) return
        // Safety observations stay conservative even if the sample was requested by an older run.
        enforceCompressionSessionContinuity(snapshot)
        enforceRuntimeSafety(snapshot)
        if (!telemetrySampleGate.belongsToCurrentRun(generation)) return

        if (runSamples.size < MAX_RUN_SAMPLES) {
            runSamples += snapshot.copy(
                monotonicMs = (snapshot.monotonicMs - runStartMonotonicMs).coerceAtLeast(0L),
            )
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
                loadManager.releaseLoads()
            }
            requestDisplayMode(min(60f, targetPhase.requestedDisplayHz))
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
                        !RendererSafetyState.hasUnconfirmedTeardown() &&
                        frameTracker.activateProducerGeneration(producerGeneration)
                    ) {
                        phaseBaseline = freshBaseline
                        break
                    }
                }
                if (nowMs >= topologyDeadlineMs) {
                    frameTracker.markProducerTeardownFailure(producerGeneration)
                    val reason =
                        "Phase '${targetPhase.id}' physical topology publish/recovery가 " +
                            "${PRODUCER_RECOVERY_TIMEOUT_MS}ms를 초과했습니다 " +
                            "(published=${readiness.topologyPublished}, " +
                            "pending=${readiness.topologyPending}, " +
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
                    },
                )
                delay(RENDERER_TEARDOWN_POLL_MS)
            }

            val activationBaseline = checkNotNull(phaseBaseline)
            val phaseStarted = SystemClock.elapsedRealtime()
            val phaseClock = ActivePhaseClock(phaseStarted)
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
                    "transition=${targetPhase.transition.summary()}",
            )
            var lastRawRuntime = initialRawRuntime
            var firstControlTick = false
            var runtimeTargetPhase = targetPhase
            var targetThermalState = thermalReduced
            var lastAppliedDisplayHz: Float? = initialRuntime.requestedDisplayHz
            var lastAppliedWorkloads: LoadSetpoints? = initialRuntime.workloads
            var postReadyControlTickApplied = false
            var transitionStarted = false
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
            val pendingBoundaryAtActivation = topologyPendingBoundary.updateAndGet { boundary ->
                boundary?.takeIf {
                    it.generation == producerGeneration &&
                        it.monotonicMs >= phaseStarted
                }
            }
            if (pendingBoundaryAtActivation != null) {
                producerFrameBudget.pauseAtPhysicalBoundary(
                    atMonotonicMs = pendingBoundaryAtActivation.monotonicMs,
                    totalFrames = frameTracker.totalPhysicalProducedFrames(),
                )
                producerRecoveryPaused = true
                loadManager.releaseLoads()
                lastAppliedWorkloads = null
                requestDisplayMode(min(60f, initialRuntime.requestedDisplayHz))
                lastAppliedDisplayHz = null
            } else {
                loadManager.apply(initialRuntime.workloads, restartProfile = true)
                requestDisplayMode(initialRuntime.requestedDisplayHz)
            }
            var recoveryPaused = false
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
                recoveryPaused = true
                producerRecoveryPaused = true
                phaseClock.pause(boundedBoundaryMs)
                producerFrameBudget.pause(boundedBoundaryMs)
                loadManager.releaseLoads()
                requestDisplayMode(min(60f, targetPhase.requestedDisplayHz))
                runEvents += event(
                    "PRODUCER_RECOVERY_WAIT",
                    "phase=${targetPhase.id}; pending=$topologyPending; " +
                        "processLease=$processLeaseActive",
                )
            }
            try {
                while (true) {
                currentCoroutineContext().ensureActive()
                if (abortForLocalWorkerFailure()) {
                    currentCoroutineContext().ensureActive()
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
                    val currentPauseMs = phaseClock.currentPauseMs(nowMs)
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
                    // Lease-clear wins at the exact timeout boundary. Resume at the same active
                    // transition fraction and require fresh post-recovery buffers from every ID.
                    if (
                        producerRecoveryDeadlineExceeded(
                            recoveryStillActive = false,
                            currentPauseMs = phaseClock.currentPauseMs(nowMs),
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
                    phaseClock.resume(nowMs)
                    producerFrameBudget.resume(nowMs)
                    producerRecoveryPaused = false
                    recoveryPaused = false
                    topologyPendingBoundary.updateAndGet { boundary ->
                        boundary?.takeUnless { it.generation == producerGeneration }
                    }
                    if (!frameTracker.restartProducerObservation(producerGeneration)) {
                        val reason =
                            "Phase '${targetPhase.id}' producer topology가 복구 후 유효하지 않습니다."
                        cancellationReason = reason
                        throw PlanAbortException(reason)
                    }
                    firstControlTick = true
                    lastAppliedDisplayHz = null
                    lastAppliedWorkloads = null
                    runEvents += event(
                        "PRODUCER_RECOVERY_RESUMED",
                        "phase=${targetPhase.id}; pausedMs=${phaseClock.totalPausedMs(nowMs)}",
                    )
                    continue
                }
                producerFrameBudget.observePhysicalFrames(
                    totalFrames = frameTracker.totalPhysicalProducedFrames(),
                    countAsActive = true,
                )
                val phaseElapsed = phaseClock.elapsedMs(nowMs)
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
                val rawRuntimePhase = allocationRouteSafePhase(
                    initial = LoadTransitionEvaluator.interpolate(
                        previous = transitionOrigin,
                        target = requestedPhase,
                        fraction = transitionSample.fraction,
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
                    requestDisplayMode(runtimePhase.requestedDisplayHz)
                    lastAppliedDisplayHz = runtimePhase.requestedDisplayHz
                }
                if (firstControlTick || runtimePhase.workloads != lastAppliedWorkloads) {
                    loadManager.apply(
                        runtimePhase.workloads,
                        restartProfile = firstControlTick,
                    )
                    lastAppliedWorkloads = runtimePhase.workloads
                }
                if (producerReadiness.ready) {
                    transitionStarted = true
                    postReadyControlTickApplied = true
                }
                firstControlTick = false
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
                planProgress = planProgress.copy(
                    currentRunFraction = progress.overallFraction,
                )
                delay(PROGRESS_INTERVAL_MS)
                }
            } finally {
                producerRecoveryPaused = false
            }
            val phaseFinishedAtMs = SystemClock.elapsedRealtime()
            producerFrameBudget.observePhysicalFrames(
                totalFrames = frameTracker.totalPhysicalProducedFrames(),
                countAsActive = true,
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
            previousRawRuntime = lastRawRuntime
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
        if (closed) return controllerCloseCleanupConfirmed == true
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
        if (closed) return controllerCloseCleanupConfirmed == true
        var released = confirmGeneratedLoadRelease()
        if (closed) return controllerCloseCleanupConfirmed == true
        if (!released) released = confirmGeneratedLoadRelease()
        return if (closed) controllerCloseCleanupConfirmed == true else released
    }

    private suspend fun releaseCompressionRouteForRun(): Boolean {
        if (closed) return controllerCloseCleanupConfirmed == true
        var released = resetCompressionRoute()
        if (closed) return controllerCloseCleanupConfirmed == true
        if (!released) released = resetCompressionRoute()
        return if (closed) controllerCloseCleanupConfirmed == true else released
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

    private suspend fun confirmGeneratedLoadRelease(): Boolean {
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

    private suspend fun confirmGeneratedLoadQuiesce(): Boolean {
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

    private fun resetDisplayModeSafely() {
        requestDisplayModeSafely(0f, "display reset")
    }

    private fun requestDisplayModeSafely(refreshHz: Float, operation: String): Boolean =
        runCatching {
            requestDisplayMode(refreshHz)
            true
        }.getOrElse { error ->
            recordCleanupFailure(operation, error)
            false
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

    private fun enforceRuntimeSafety(snapshot: TelemetrySnapshot) {
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
        if (!thermalReduced && snapshot.thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
            thermalReduced = true
            val reduced = progress.phase?.let(::applyPersistentSafety)
            if (!producerRecoveryPaused && reduced != null) {
                val workloadApplied = runCatching {
                    val committedProducerCount =
                        frameTracker.producerReadiness(progress.producerGeneration)
                            .expectedCount
                            .coerceIn(1, ScenarioSafetyPolicy.HARD_MAX_LAYERS)
                    activeProducerFrameBudget?.apply(
                        atMonotonicMs = SystemClock.elapsedRealtime(),
                        producerFps = reduced.producerFps,
                        activeLayers = committedProducerCount,
                    )
                    loadManager.apply(reduced.workloads, restartProfile = false)
                    true
                }.getOrElse { error ->
                    runEvents += event(
                        "THERMAL_DERATE_APPLY_ERROR",
                        "workload derate 실패: ${error.javaClass.simpleName}",
                    )
                    false
                }
                val displayApplied = workloadApplied &&
                    requestDisplayModeSafely(
                        reduced.requestedDisplayHz,
                        "thermal display derate",
                    )
                if (thermalDerateActionFailed(workloadApplied, displayApplied)) {
                    abortForSafety(
                        reason = "열 감속 setpoint/display 적용을 확인할 수 없어 안전 중단합니다.",
                        eventType = "THERMAL_DERATE_FAILED",
                    )
                    return
                }
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
            ).normalized(),
        )
    }

    private fun neutralPhaseFor(target: PhaseSpec): PhaseSpec =
        target.copy(
            activeLayers =
                if (target.includeGlLayer && target.activeLayers > 1) 2 else 1,
            producerFps = min(60f, target.producerFps),
            requestedDisplayHz = min(60f, target.requestedDisplayHz),
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
        if (exactDelta?.let { it > 0L } != true && producerRateShortfallReason != null) {
            runEvents += event(
                "INCONCLUSIVE",
                checkNotNull(producerRateShortfallReason),
            )
            return RunVerdict.INCONCLUSIVE
        }
        return underrunVerdict(exactDelta, suspectedDelta)
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
            withContext(Dispatchers.IO) { ReportWriter.write(activity, summary) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            errorMessage = "보고서 저장 실패: ${error.message}"
            null
        }
        lastReportFile = reportFile
        return ScenarioRunArtifact(summary, reportFile)
    }

    private suspend fun finalizeProtected(
        scenario: ScenarioSpec,
        preselectedVerdict: RunVerdict?,
    ): ScenarioRunArtifact = withContext(NonCancellable) {
        val rendererReleased = awaitRendererTeardownBarrier()
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
        val effectiveVerdict = finalVerdictAfterTeardown(
            preselectedVerdict = preselectedVerdict,
            rendererReleased = rendererReleased,
            cancellationPresent = cancellationReason != null,
            derivedVerdict = derivedVerdict,
        )
        if (!rendererReleased) {
            val teardownReason =
                "Physical producer의 최종 teardown 확인에 실패해 후속 queue를 차단했습니다."
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

    private fun setWakeState(awake: Boolean) {
        screenAwake = awake
        if (awake) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun openPinnedMediaSource(
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
                        if (local == null) {
                            failure.compareAndSet(
                                null,
                                IllegalArgumentException("Provider returned no descriptor"),
                            )
                        } else if (abandoned.get()) {
                            local.close()
                            local = null
                        } else {
                            opened.set(local)
                            local = null
                            if (abandoned.get()) {
                                opened.getAndSet(null)?.close()
                            }
                        }
                    } catch (error: Throwable) {
                        if (error is ThreadDeath) throw error
                        failure.compareAndSet(null, error)
                    } finally {
                        runCatching { local?.close() }
                        completed.countDown()
                        workerLease.close()
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
            opened.getAndSet(null)?.close()
            runCatching { cancellationSignal.cancel() }
            worker.interrupt()
            try {
                completed.await(MEDIA_PROVIDER_CANCEL_GRACE_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        val completedInTime = try {
            completed.await(MEDIA_PROVIDER_OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            abandonProviderOpen()
            Thread.currentThread().interrupt()
            throw CancellationException("media provider open interrupted").also {
                it.initCause(interrupted)
            }
        }
        if (!completedInTime) {
            abandonProviderOpen()
            throw PlanAbortException(
                "media provider가 ${MEDIA_PROVIDER_OPEN_TIMEOUT_MS}ms 안에 descriptor를 " +
                    "반환하지 않아 안전 중단했습니다.",
            )
        }
        failure.get()?.let { error ->
            throw UnsupportedRunException(
                "선택한 영상 descriptor를 열 수 없습니다 (${error.javaClass.simpleName}).",
            )
        }
        val descriptor = opened.getAndSet(null)
            ?: throw UnsupportedRunException("선택한 영상 descriptor가 비어 있습니다.")
        return try {
            PinnedMediaSource(uri, descriptor)
        } catch (error: Exception) {
            runCatching { descriptor.close() }
            throw UnsupportedRunException(
                "seek 가능한 고정 영상 descriptor가 필요합니다 (${error.javaClass.simpleName}).",
            )
        }
    }

    private fun inspectPinnedTrackBounded(
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
            try {
                completed.await(MEDIA_INSPECTION_CANCEL_GRACE_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        val completedInTime = try {
            completed.await(MEDIA_INSPECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            abandonInspection()
            Thread.currentThread().interrupt()
            throw CancellationException("media inspection interrupted").also {
                it.initCause(interrupted)
            }
        }
        if (!completedInTime) {
            abandonInspection()
            throw PlanAbortException(
                "media parser가 ${MEDIA_INSPECTION_TIMEOUT_MS}ms 안에 반환하지 않아 " +
                    "안전 중단했습니다.",
            )
        }
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
                extractor.setDataSource(descriptor)
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
            extractor.release()
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
        media ?: return MediaValidationResult()
        val decoderPhases = scenario.phases.filter(::canUseVideoPrimary)
        if (decoderPhases.isEmpty()) return MediaValidationResult()
        val boundMediaUri = mediaUri
            ?: return MediaValidationResult(error = "검증할 영상 URI가 없습니다.")
        if (!media.trackFingerprintComplete) {
            return MediaValidationResult(
                error = "decoder 실행에 필요한 track 해상도/crop/FPS/codec-config fingerprint를 " +
                    "안전하게 확인할 수 없습니다.",
            )
        }
        val width = media.width
            ?: return MediaValidationResult(error = "영상 해상도 metadata를 확인할 수 없습니다.")
        val height = media.height
            ?: return MediaValidationResult(error = "영상 해상도 metadata를 확인할 수 없습니다.")
        val visibleWidth = media.visibleWidth
            ?: return MediaValidationResult(error = "영상 visible width를 확인할 수 없습니다.")
        val visibleHeight = media.visibleHeight
            ?: return MediaValidationResult(error = "영상 visible height를 확인할 수 없습니다.")
        if (
            !fixedVideoMaximumDimensionsMatch(
                encodedWidthPx = width,
                encodedHeightPx = height,
                declaredMaxWidthPx = media.declaredMaxWidth,
                declaredMaxHeightPx = media.declaredMaxHeight,
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
        val mime = media.mime
            ?: return MediaValidationResult(error = "영상 MIME metadata를 확인할 수 없습니다.")
        val requiredFps = decoderPhases.maxOf { it.producerFps }
        val sourceFps = media.frameRate
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
                profile = media.profile,
                codecsString = media.codecsString,
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
            media.maxInputSize != null &&
            media.maxInputSize > compressedSampleLimit
        ) {
            return MediaValidationResult(
                error = "영상 max-input-size ${media.maxInputSize}B가 현재 기기 안전 한도 " +
                    "${compressedSampleLimit}B를 초과합니다.",
            )
        }
        // The concrete input format is configured unchanged. Bind every advertised optional
        // profile/level/bitrate key, not only P010, to the chosen codec's format support query.
        val requiredProfile = media.profile
        val hardwareCodecName = CapabilityScanner.findHardwareVideoDecoder(
            mime = mime,
            width = width,
            height = height,
            // MediaCodec receives the selected track's original format. A 120 fps source used
            // by a 60 fps phase must therefore be assigned to a codec that advertises 120 fps,
            // even though output release is intentionally paced at the lower phase target.
            framesPerSecond = decoderCapabilityFps,
            requiredProfile = requiredProfile,
            requiredLevel = media.level,
            bitRate = media.bitRate,
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
                pinnedSource = media.pinnedSource,
                mime = mime,
                codecName = hardwareCodecName,
                expectedEncodedWidthPx = width,
                expectedEncodedHeightPx = height,
                expectedDeclaredMaxWidthPx = media.declaredMaxWidth,
                expectedDeclaredMaxHeightPx = media.declaredMaxHeight,
                expectedVisibleWidthPx = visibleWidth,
                expectedVisibleHeightPx = visibleHeight,
                expectedSourceFps = sourceFps,
                expectedProfile = media.profile,
                expectedLevel = media.level,
                expectedBitRate = media.bitRate,
                expectedMaxInputSize = media.maxInputSize,
                expectedRotationDegrees = media.rotationDegrees,
                expectedCodecsString = media.codecsString,
                codecConfigFingerprint = checkNotNull(media.codecConfigFingerprint),
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
        phase.backend != LayerBackend.FLATTENED_TEXTURE &&
            phase.pixelRoute.usesSelectedMediaDecoder() &&
            !(phase.includeGlLayer && phase.activeLayers == 1)

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
        const val PROGRESS_INTERVAL_MS = LOAD_CONTROL_CADENCE_MS
        const val DISPLAY_REQUEST_EPSILON_HZ = 0.05f
        const val PRODUCER_STARTUP_GRACE_MS = 3_000L
        const val PRODUCER_RECOVERY_TIMEOUT_MS = 5_000L
        const val COOLDOWN_DELAY_MS = 2_000L
        const val RENDERER_TEARDOWN_ACK_TIMEOUT_MS = PRODUCER_RECOVERY_TIMEOUT_MS
        const val RENDERER_TEARDOWN_POLL_MS = 16L
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
        const val MEDIA_PROVIDER_CANCEL_GRACE_MS = 100L
        const val MEDIA_INSPECTION_TIMEOUT_MS = 10_000L
        const val MEDIA_INSPECTION_CANCEL_GRACE_MS = 100L
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
        if (fields.size < 4 || fields[1] != "02") return null
        val parsed = fields[3]
            .takeIf { it.length == 2 && it.all(Char::isDigit) }
            ?.toIntOrNull()
            ?.takeIf { it == 10 || it == 12 }
            ?: return null
        if (bitDepth != null && bitDepth != parsed) return null
        bitDepth = parsed
    }
    return bitDepth
}

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
internal class ActivePhaseClock(startedAtMs: Long) {
    private val startedAtMs = startedAtMs.coerceAtLeast(0L)
    private var completedPauseMs = 0L
    private var pausedAtMs: Long? = null

    fun pause(atMonotonicMs: Long) {
        if (pausedAtMs != null) return
        pausedAtMs = atMonotonicMs.coerceAtLeast(startedAtMs)
    }

    fun resume(atMonotonicMs: Long) {
        val pausedAt = pausedAtMs ?: return
        completedPauseMs = saturatingAdd(
            completedPauseMs,
            nonNegativeMonotonicDelta(
                laterMs = atMonotonicMs.coerceAtLeast(startedAtMs),
                earlierMs = pausedAt,
            ),
        )
        pausedAtMs = null
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

    fun currentPauseMs(atMonotonicMs: Long): Long = pausedAtMs?.let { pausedAt ->
        nonNegativeMonotonicDelta(
            laterMs = atMonotonicMs.coerceAtLeast(startedAtMs),
            earlierMs = pausedAt,
        )
    } ?: 0L
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
        workloads = LoadSetpoints(),
        alphaOverlap = false,
        includeGlLayer = false,
    )

internal fun rendererAllocationRouteChanges(
    active: PhaseSpec?,
    target: PhaseSpec,
): Boolean =
    (active?.pixelRoute ?: PixelRoute.RGB_8888) != target.pixelRoute

/**
 * Pixel/compression routes cannot be interpolated under a live producer. Continuous fields can
 * still start at the prior values, but the preparation producer must use the already validated
 * target allocation topology after the old route has been detached.
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
        )
    }

internal fun rendererPreparationPhase(initialRuntime: PhaseSpec): PhaseSpec =
    initialRuntime.copy(
        producerFps = min(60f, initialRuntime.producerFps),
        requestedDisplayHz = min(60f, initialRuntime.requestedDisplayHz),
        motion = MotionProfile.STATIC,
        workloads = LoadSetpoints(),
    )

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
            ?.let { it > MEMORY_PREWARM_EPSILON }
            ?: false
    }

private const val MEMORY_PREWARM_EPSILON = 0.001f

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
    fun pause(atMonotonicMs: Long) {
        if (finished) return
        accountThrough(atMonotonicMs)
        activeClock.pause(atMonotonicMs)
    }

    @Synchronized
    fun resume(atMonotonicMs: Long) {
        if (finished) return
        accountThrough(atMonotonicMs)
        activeClock.resume(atMonotonicMs)
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
    fun pauseAtPhysicalBoundary(atMonotonicMs: Long, totalFrames: Long) {
        if (finished) return
        observePhysicalFramesLocked(totalFrames, countAsActive = true)
        accountThrough(atMonotonicMs)
        activeClock.pause(atMonotonicMs)
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
        actualFrames == null ||
        actualFrames < 0L ||
        !expectedFrames.isFinite() ||
        expectedFrames < 0.0 ||
        !minimumExpectedFrames.isFinite() ||
        minimumExpectedFrames < 0.0 ||
        !minimumRatio.isFinite() ||
        minimumRatio !in 0.0..1.0
    ) {
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

internal fun safetyEnvelopeInvalidatedByPowerSave(
    envelopePowerSaveMode: Boolean,
    currentPowerSaveMode: Boolean,
): Boolean = !envelopePowerSaveMode && currentPowerSaveMode

internal fun shouldStopActivePlan(jobPresent: Boolean, isRunning: Boolean): Boolean =
    jobPresent && isRunning

internal fun thermalDerateActionFailed(
    workloadApplied: Boolean,
    displayApplied: Boolean,
): Boolean = !workloadApplied || !displayApplied

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
