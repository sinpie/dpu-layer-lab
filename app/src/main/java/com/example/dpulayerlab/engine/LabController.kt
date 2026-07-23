package com.example.dpulayerlab.engine

import android.app.Activity
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.example.dpulayerlab.model.BufferSize
import com.example.dpulayerlab.model.LayerBackend
import com.example.dpulayerlab.model.LoadSetpoints
import com.example.dpulayerlab.model.PhaseSpec
import com.example.dpulayerlab.model.PixelRoute
import com.example.dpulayerlab.model.RenderSafetyLimits
import com.example.dpulayerlab.model.RunEvent
import com.example.dpulayerlab.model.RunProgress
import com.example.dpulayerlab.model.RunSummary
import com.example.dpulayerlab.model.RunVerdict
import com.example.dpulayerlab.model.RunnerStage
import com.example.dpulayerlab.model.ScenarioSafetyPolicy
import com.example.dpulayerlab.model.ScenarioSpec
import com.example.dpulayerlab.model.SensorReading
import com.example.dpulayerlab.model.TelemetrySnapshot
import com.example.dpulayerlab.monitor.FrameTracker
import com.example.dpulayerlab.monitor.CapabilityScanner
import com.example.dpulayerlab.monitor.SystemMonitor
import com.example.dpulayerlab.util.currentDisplayCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
import java.io.File
import kotlin.math.max
import kotlin.math.min

class LabController(
    private val activity: Activity,
    private val requestDisplayMode: (Float) -> Unit,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
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
    private var highestExactUnderruns: Long? = null
    private var baselineSuspected = 0L
    private var thermalReduced = false
    private var cancellationReason: String? = null
    private var runFinalized = false
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
    var lastSummary by mutableStateOf<RunSummary?>(null)
        private set
    var lastReportFile by mutableStateOf<File?>(null)
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

    val isRunning: Boolean
        get() = progress.stage in ACTIVE_STAGES

    val hasDumpPermission: Boolean get() = systemMonitor.hasDumpPermission()
    val hasNpuAdapter: Boolean get() = systemMonitor.hasNpuAdapter()
    val hasSbwcAdapter: Boolean get() = systemMonitor.hasSbwcAdapter()

    fun start() {
        if (monitorJob?.isActive == true) {
            // FrameTracker.stop() is called from pause(); reset its baseline on every resume.
            frameTracker.start()
            return
        }
        loadManager.start()
        frameTracker.start()
        lastSuccessfulSampleMs = SystemClock.elapsedRealtime()
        monitorJob = scope.launch {
            while (isActive) {
                try {
                    val snapshot = systemMonitor.sample(activity.currentDisplayCompat())
                    lastSuccessfulSampleMs = SystemClock.elapsedRealtime()
                    acceptSnapshot(snapshot)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    errorMessage = "상태 계측 실패: ${error.javaClass.simpleName}"
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
        monitorJob?.cancel()
        watchdogJob?.cancel()
        monitorJob = null
        watchdogJob = null
        frameTracker.stop()
        releaseActiveLoads()
        requestDisplayMode(0f)
        setWakeState(false)
    }

    fun setMediaUri(uri: Uri?) {
        if (isRunning) {
            errorMessage = "테스트 중에는 미디어 소스를 변경할 수 없습니다."
            return
        }
        selectedMediaUri = uri
        selectedMediaWidthPx = null
        selectedMediaHeightPx = null
    }

    fun clearError() {
        errorMessage = null
    }

    fun startScenario(requestedScenario: ScenarioSpec) {
        if (runJob?.isActive == true || isRunning) return
        resetRunState()
        progress = RunProgress(
            stage = RunnerStage.PRECHECK,
            scenario = requestedScenario,
            statusText = "안전 예산과 capability 확인 중",
        )
        setWakeState(true)

        runJob = scope.launch {
            var scenarioForReport = requestedScenario
            try {
                safetyLimits = DeviceRenderSafety.detect(activity)
                val decision = ScenarioSafetyPolicy.evaluate(requestedScenario, safetyLimits)
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

                precheck(scenario)?.let { throw UnsupportedRunException(it) }
                val mediaForRun = selectedMediaUri
                val mediaInfo = if (mediaForRun != null) inspectMedia(mediaForRun) else null
                selectedMediaWidthPx = mediaInfo?.width
                selectedMediaHeightPx = mediaInfo?.height
                if (mediaForRun != null && mediaInfo?.readable != true) {
                    throw UnsupportedRunException("선택한 영상 URI를 읽을 수 없습니다.")
                }
                validateBufferCapabilities(scenario, mediaForRun != null)?.let {
                    throw UnsupportedRunException(it)
                }
                val mediaValidation = withContext(Dispatchers.Default) {
                    validateRequestedMedia(scenario, mediaInfo)
                }
                mediaValidation?.let {
                    throw UnsupportedRunException(it)
                }
                establishCounterBaseline()

                progress = RunProgress(
                    stage = RunnerStage.PRECHECK,
                    scenario = scenario,
                    statusText = "capability와 telemetry source 확인 중",
                )
                runEvents += event("PRECHECK", "scenario=${scenario.id}")
                if (mediaForRun == null && scenario.phases.any(::canUseVideoPrimary)) {
                    runEvents += event(
                        "PROXY_FALLBACK",
                        "로컬 영상이 없어 RGBA 기반 YUV/P010 visual proxy를 사용합니다.",
                    )
                } else if (mediaInfo != null) {
                    runEvents += event("MEDIA_SOURCE", mediaInfo.description)
                }
                delay(PRECHECK_DELAY_MS)

                progress = progress.copy(
                    stage = RunnerStage.WARMUP,
                    phaseIndex = 0,
                    phase = scenario.phases.firstOrNull()?.let(::applyPersistentSafety),
                    statusText = "surface warm-up",
                )
                loadManager.releaseLoads()
                delay(WARMUP_DELAY_MS)
                runScenarioPhases(scenario)

                progress = progress.copy(
                    stage = RunnerStage.COOLDOWN,
                    phase = scenario.phases.lastOrNull()?.copy(
                        activeLayers = 1,
                        producerFps = min(60f, safetyLimits.maxProducerFps),
                        requestedDisplayHz = 60f,
                        workloads = LoadSetpoints(),
                    ),
                    statusText = "부하 해제 및 counter 안정화",
                )
                releaseActiveLoads()
                requestDisplayMode(60f)
                delay(COOLDOWN_DELAY_MS)
                finalizeProtected(scenario, deriveVerdict())
            } catch (cancelled: CancellationException) {
                if (!runFinalized) {
                    progress = progress.copy(
                        stage = RunnerStage.ABORTED,
                        statusText = cancellationReason ?: "사용자가 중단함",
                    )
                    runEvents += event("ABORTED", cancellationReason ?: "User stop")
                    finalizeProtected(scenarioForReport, RunVerdict.ABORTED)
                }
            } catch (unsupported: UnsupportedRunException) {
                if (!runFinalized) {
                    progress = progress.copy(
                        stage = RunnerStage.UNSUPPORTED,
                        scenario = scenarioForReport,
                        statusText = unsupported.message.orEmpty(),
                    )
                    runEvents += event("UNSUPPORTED", unsupported.message.orEmpty())
                    finalizeProtected(scenarioForReport, RunVerdict.UNSUPPORTED)
                }
            } catch (error: Exception) {
                if (!runFinalized) {
                    errorMessage = "실행 실패: ${error.message ?: error.javaClass.simpleName}"
                    runEvents += event(
                        "ERROR",
                        error.stackTraceToString().take(MAX_EVENT_MESSAGE_CHARS),
                    )
                    finalizeProtected(scenarioForReport, RunVerdict.INCONCLUSIVE)
                }
            } finally {
                withContext(NonCancellable) {
                    releaseActiveLoads()
                    requestDisplayMode(0f)
                    setWakeState(false)
                }
                runJob = null
            }
        }
    }

    fun stopScenario() {
        stopScenario("사용자가 중단함")
    }

    fun stopScenario(reason: String) {
        if (runJob?.isActive != true) return
        cancellationReason = reason
        runJob?.cancel()
    }

    fun dismissResult() {
        if (!isRunning) progress = RunProgress()
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
        runJob?.cancel()
        pause()
        releaseActiveLoads()
        requestDisplayMode(0f)
        setWakeState(false)
        loadManager.close()
        systemMonitor.close()
        scope.cancel()
    }

    private fun resetRunState() {
        loadManager.clearMemoryAllocationFailure()
        lastSummary = null
        lastReportFile = null
        lastSafetyAdjustments = emptyList()
        runSamples.clear()
        runEvents.clear()
        thermalReduced = false
        cancellationReason = null
        runFinalized = false
        runStartMonotonicMs = SystemClock.elapsedRealtime()
        runStartEpochMs = System.currentTimeMillis()
        baselineExactUnderruns = null
        baselineExactSource = null
        highestExactUnderruns = null
        baselineSuspected = telemetry.suspectedUnderruns
    }

    private fun establishCounterBaseline() {
        baselineExactUnderruns = telemetry.exactUnderruns
        baselineExactSource = telemetry.exactUnderrunSource
        highestExactUnderruns = baselineExactUnderruns
        baselineSuspected = telemetry.suspectedUnderruns
        runEvents += event(
            "COUNTER_BASELINE",
            "exact=${baselineExactUnderruns ?: "N/A"}; " +
                "source=${baselineExactSource ?: "N/A"}; proxy=$baselineSuspected",
        )
    }

    private fun acceptSnapshot(snapshot: TelemetrySnapshot) {
        telemetry = snapshot
        telemetryHistory += snapshot
        while (telemetryHistory.size > MAX_TELEMETRY_HISTORY) telemetryHistory.removeAt(0)
        directSensors.clear()
        directSensors.addAll(systemMonitor.directSensorReadings())
        if (!isRunning) return

        if (runSamples.size < MAX_RUN_SAMPLES) {
            runSamples += snapshot.copy(
                monotonicMs = (snapshot.monotonicMs - runStartMonotonicMs).coerceAtLeast(0L),
            )
        }
        observeExactCounter(snapshot)
        enforceRuntimeSafety(snapshot)
    }

    private suspend fun runScenarioPhases(scenario: ScenarioSpec) {
        var scenarioElapsed = 0L
        var phaseIndex = 0
        while (phaseIndex < scenario.phases.size) {
            currentCoroutineContext().ensureActive()
            val requestedPhase = scenario.phases[phaseIndex]
            val phase = applyPersistentSafety(requestedPhase)
            val exactBefore = telemetry.exactUnderruns
            val exactSourceBefore = telemetry.exactUnderrunSource
            val proxyBefore = telemetry.suspectedUnderruns
            val producedBefore = frameTracker.totalProducedFrames()
            runEvents += event(
                "PHASE_START",
                "${phase.id}: ${phase.label}; ${phase.activeLayers}L @ ${phase.producerFps}fps",
            )
            configureCompression(phase)
            requestDisplayMode(phase.requestedDisplayHz)
            loadManager.apply(phase.workloads)
            val phaseStarted = SystemClock.elapsedRealtime()
            while (true) {
                currentCoroutineContext().ensureActive()
                val phaseElapsed = SystemClock.elapsedRealtime() - phaseStarted
                if (phaseElapsed >= phase.durationMs) break
                val runtimePhase = applyPersistentSafety(requestedPhase)
                progress = RunProgress(
                    stage = RunnerStage.RUNNING,
                    scenario = scenario,
                    phaseIndex = phaseIndex,
                    phase = runtimePhase,
                    elapsedMs = scenarioElapsed + phaseElapsed,
                    phaseElapsedMs = phaseElapsed,
                    statusText = runtimePhase.label,
                    controlLayerIncluded = true,
                )
                if (
                    phase.durationMs >= PRODUCER_STARTUP_GRACE_MS &&
                    phaseElapsed >= PRODUCER_STARTUP_GRACE_MS &&
                    frameTracker.totalProducedFrames() <= producedBefore
                ) {
                    throw UnsupportedRunException(
                        "Phase '${phase.id}' primary producer가 " +
                            "${PRODUCER_STARTUP_GRACE_MS}ms 내 frame을 만들지 못했습니다.",
                    )
                }
                delay(PROGRESS_INTERVAL_MS)
            }
            scenarioElapsed = saturatingAdd(scenarioElapsed, phase.durationMs)
            runEvents += event("PHASE_END", phase.id)
            val exactDelta = monotonicCounterDelta(
                baselineValue = exactBefore,
                baselineSource = exactSourceBefore,
                currentValue = telemetry.exactUnderruns,
                currentSource = telemetry.exactUnderrunSource,
            )
            val proxyDelta = monotonicCounterDelta(
                baselineValue = proxyBefore,
                baselineSource = PROXY_COUNTER_SOURCE,
                currentValue = telemetry.suspectedUnderruns,
                currentSource = PROXY_COUNTER_SOURCE,
            ) ?: 0L
            val proxyBoundaryThreshold = (
                phase.producerFps.toDouble() * (phase.durationMs / 1_000.0) * 0.02
                ).toLong().coerceAtLeast(3L)
            val adaptiveBoundary = scenario.id == "adaptive-underrun-hunt" &&
                phase.id.startsWith("hunt-") &&
                (
                    (exactDelta != null && exactDelta > 0) ||
                        (exactDelta == null && proxyDelta >= proxyBoundaryThreshold)
                    )
            if (adaptiveBoundary) {
                runEvents += event(
                    if (exactDelta != null) "EXACT_BOUNDARY_FOUND" else "PROXY_BOUNDARY_FOUND",
                    "phase=${phase.id}; layers=${phase.activeLayers}; " +
                        "exactDelta=$exactDelta; proxyDelta=$proxyDelta",
                )
                break
            }
            phaseIndex++
        }
    }

    private fun configureCompression(phase: PhaseSpec) {
        val accepted = systemMonitor.setCompressionRoute(phase.pixelRoute)
        if (phase.pixelRoute in setOf(PixelRoute.SBWC_AUTO, PixelRoute.SBWC_REQUIRED)) {
            runEvents += event(
                "COMPRESSION_ROUTE",
                "${phase.pixelRoute.name}; vendorAccepted=$accepted",
            )
        }
        if (phase.pixelRoute == PixelRoute.SBWC_REQUIRED && !accepted) {
            throw UnsupportedRunException(
                "SBWC REQUIRED 전환을 vendor service가 승인하지 않았습니다.",
            )
        }
    }

    private fun releaseActiveLoads() {
        loadManager.releaseLoads()
        runCatching { systemMonitor.setCompressionRoute(PixelRoute.RGB_8888) }
    }

    private fun precheck(scenario: ScenarioSpec): String? {
        if (
            loadManager.hasMemoryAllocationFailure() ||
            DeviceRenderSafety.isLowMemory(activity)
        ) {
            return "시스템 low-memory 상태에서는 새 테스트를 시작하지 않습니다."
        }
        if (scenario.phases.any { it.pixelRoute == PixelRoute.SBWC_REQUIRED } && !hasSbwcAdapter) {
            return "SBWC REQUIRED는 vendor gralloc adapter가 연결된 빌드에서만 실행할 수 있습니다."
        }
        if (scenario.phases.any { it.workloads.npu > 0f } && !hasNpuAdapter) {
            return "NPU workload adapter가 없습니다. vendor bridge 또는 NNAPI native backend를 연결하세요."
        }
        return null
    }

    private fun enforceRuntimeSafety(snapshot: TelemetrySnapshot) {
        if (snapshot.memoryLow) {
            abortForSafety(
                reason = "시스템 low-memory 신호로 안전 중단",
                eventType = "LOW_MEMORY_STOP",
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
            progress.phase?.let { current ->
                val reduced = applyPersistentSafety(current)
                progress = progress.copy(
                    phase = reduced,
                    statusText = "${reduced.label} · thermal derate",
                )
                loadManager.apply(reduced.workloads)
                requestDisplayMode(reduced.requestedDisplayHz)
            }
            runEvents += event("THERMAL_DERATE", "thermal=${snapshot.thermalLabel}; persistent=true")
            errorMessage = "열 상태 ${snapshot.thermalLabel}: 남은 테스트 부하를 지속 감속합니다."
        }
    }

    private fun applyPersistentSafety(phase: PhaseSpec): PhaseSpec {
        if (!thermalReduced) return phase
        return phase.copy(
            activeLayers = max(1, phase.activeLayers / 2),
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

    private fun abortForSafety(reason: String, eventType: String) {
        if (cancellationReason != null) return
        cancellationReason = reason
        runEvents += event(eventType, reason)
        errorMessage = "$reason. 테스트를 중단했습니다."
        runJob?.cancel()
    }

    private fun observeExactCounter(snapshot: TelemetrySnapshot) {
        val baseline = baselineExactUnderruns ?: return
        val source = baselineExactSource ?: return
        val current = snapshot.exactUnderruns ?: return
        if (snapshot.exactUnderrunSource != source || current < baseline) return
        highestExactUnderruns = max(highestExactUnderruns ?: baseline, current)
    }

    private fun deriveVerdict(): RunVerdict {
        val exactDelta = exactUnderrunDelta()
        val suspectedDelta = monotonicCounterDelta(
            baselineValue = baselineSuspected,
            baselineSource = PROXY_COUNTER_SOURCE,
            currentValue = telemetry.suspectedUnderruns,
            currentSource = PROXY_COUNTER_SOURCE,
        ) ?: 0L
        return when {
            exactDelta != null && exactDelta > 0 -> RunVerdict.UNDERRUN_DETECTED
            exactDelta != null && suspectedDelta == 0L -> RunVerdict.CLEAN
            suspectedDelta > 0 -> RunVerdict.SUSPECTED_PROXY
            else -> RunVerdict.INCONCLUSIVE
        }
    }

    private fun exactUnderrunDelta(): Long? = monotonicCounterDelta(
        baselineValue = baselineExactUnderruns,
        baselineSource = baselineExactSource,
        currentValue = highestExactUnderruns,
        currentSource = baselineExactSource,
    )

    private suspend fun finalizeRun(scenario: ScenarioSpec, verdict: RunVerdict) {
        val summary = RunSummary(
            scenario = scenario,
            startedEpochMs = runStartEpochMs,
            finishedEpochMs = System.currentTimeMillis(),
            verdict = verdict,
            exactUnderrunDelta = exactUnderrunDelta(),
            exactUnderrunSource = baselineExactSource,
            suspectedUnderrunDelta = monotonicCounterDelta(
                baselineValue = baselineSuspected,
                baselineSource = PROXY_COUNTER_SOURCE,
                currentValue = telemetry.suspectedUnderruns,
                currentSource = PROXY_COUNTER_SOURCE,
            ) ?: 0L,
            peakCpu = runSamples.mapNotNull { it.cpu.value?.takeIf(Float::isFinite) }.maxOrNull(),
            peakMemoryUsed = runSamples
                .mapNotNull { it.memoryUsed.value?.takeIf(Float::isFinite) }
                .maxOrNull(),
            peakGeneratedBandwidth = runSamples
                .mapNotNull { it.generatedBandwidth.value?.takeIf(Float::isFinite) }
                .maxOrNull(),
            events = runEvents.toList(),
            samples = runSamples.toList(),
        )
        lastSummary = summary
        progress = progress.copy(
            stage = when (verdict) {
                RunVerdict.ABORTED -> RunnerStage.ABORTED
                RunVerdict.UNSUPPORTED -> RunnerStage.UNSUPPORTED
                else -> RunnerStage.COMPLETE
            },
            scenario = scenario,
            elapsedMs = (SystemClock.elapsedRealtime() - runStartMonotonicMs).coerceAtLeast(0L),
            statusText = verdict.label,
        )
        lastReportFile = try {
            withContext(Dispatchers.IO) { ReportWriter.write(activity, summary) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            errorMessage = "보고서 저장 실패: ${error.message}"
            null
        }
    }

    private suspend fun finalizeProtected(scenario: ScenarioSpec, verdict: RunVerdict) {
        withContext(NonCancellable) {
            finalizeRun(scenario, verdict)
            runFinalized = true
        }
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

    private suspend fun inspectMedia(uri: Uri): MediaInfo = withContext(Dispatchers.IO) {
        val readable = runCatching {
            activity.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
        }.getOrDefault(false)
        val displayName = runCatching {
            activity.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "content URI"
        var width: Int? = null
        var height: Int? = null
        var mimeType: String? = null
        val metadata = runCatching {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(activity, uri)
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull()
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull()
                val fps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                    ?: activity.contentResolver.getType(uri)
                mimeType = mime
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                "$mime ${width ?: "?"}x${height ?: "?"} ${fps ?: "?"}fps " +
                    "duration=${duration ?: "?"}ms"
            }
        }.getOrElse { "metadata unavailable: ${it.javaClass.simpleName}" }
        MediaInfo(
            readable = readable,
            width = width,
            height = height,
            mime = mimeType,
            description = "$displayName · $metadata",
        )
    }

    private fun validateRequestedMedia(scenario: ScenarioSpec, media: MediaInfo?): String? {
        media ?: return null
        val decoderPhases = scenario.phases.filter(::canUseVideoPrimary)
        if (decoderPhases.isEmpty()) return null
        val width = media.width ?: return "영상 해상도 metadata를 확인할 수 없습니다."
        val height = media.height ?: return "영상 해상도 metadata를 확인할 수 없습니다."
        val longEdge = max(width, height)
        val shortEdge = min(width, height)
        val requiredSize = decoderPhases
            .map { it.bufferSize }
            .filter { it != BufferSize.DISPLAY }
            .maxByOrNull { it.width.toLong() * it.height.toLong() }
        if (
            requiredSize != null &&
            (longEdge < requiredSize.width || shortEdge < requiredSize.height)
        ) {
            return "선택 영상은 ${width}x${height}이며 phase 요구사항" +
                "(${requiredSize.width}x${requiredSize.height})에 미달합니다."
        }
        val mime = media.mime ?: return "영상 MIME metadata를 확인할 수 없습니다."
        val requiredFps = decoderPhases.maxOf { it.producerFps }
        if (!CapabilityScanner.supportsHardwareVideoDecoder(mime, width, height, requiredFps)) {
            return "$mime ${width}x${height} @ ${requiredFps}fps를 지원하는 " +
                "hardware decoder가 없습니다."
        }
        return null
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
            phase.pixelRoute in VIDEO_ROUTES &&
            !(phase.includeGlLayer && phase.activeLayers == 1)

    private data class MediaInfo(
        val readable: Boolean,
        val width: Int?,
        val height: Int?,
        val mime: String?,
        val description: String,
    )

    private class UnsupportedRunException(message: String) : Exception(message)

    private companion object {
        val ACTIVE_STAGES = setOf(
            RunnerStage.PRECHECK,
            RunnerStage.WARMUP,
            RunnerStage.RUNNING,
            RunnerStage.COOLDOWN,
        )
        val VIDEO_ROUTES = setOf(PixelRoute.YUV_420, PixelRoute.P010, PixelRoute.SBWC_AUTO)
        const val PROXY_COUNTER_SOURCE = "FrameTracker"
        const val MONITOR_INTERVAL_MS = 1_000L
        const val WATCHDOG_INTERVAL_MS = 500L
        const val MONITOR_STALE_TIMEOUT_MS = 5_000L
        const val PRECHECK_DELAY_MS = 700L
        const val WARMUP_DELAY_MS = 1_200L
        const val PROGRESS_INTERVAL_MS = 100L
        const val PRODUCER_STARTUP_GRACE_MS = 3_000L
        const val COOLDOWN_DELAY_MS = 2_000L
        const val MAX_TELEMETRY_HISTORY = 60
        const val MAX_RUN_SAMPLES = 3_600
        const val MAX_EVENT_MESSAGE_CHARS = 1_000
    }
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

private fun saturatingAdd(left: Long, right: Long): Long {
    if (right <= 0L) return left
    return if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
}
