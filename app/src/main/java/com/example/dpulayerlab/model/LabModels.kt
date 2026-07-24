package com.example.dpulayerlab.model

import android.os.Build
import kotlin.math.roundToInt

const val MIN_EFFECTIVE_LOAD = 0.001f

enum class ScenarioCategory(val label: String) {
    LAYER_HWC("Layer / HWC"),
    TRANSFORM("Transform"),
    VIDEO_FORMAT("Video / Format"),
    REFRESH("Refresh"),
    RESOURCE("Resource"),
    TRANSITION("Load Transition"),
    MIXED("Mixed"),
    ADAPTIVE("DVFS / Adaptive"),
    SOAK("Soak"),
}

enum class RiskLevel(val label: String) {
    LOW("낮음"),
    MEDIUM("보통"),
    HIGH("높음"),
}

enum class LayerBackend(val label: String) {
    INDEPENDENT_SURFACES("독립 Surface"),
    MIXED_SURFACE_TEXTURE("Surface + Texture"),
    FLATTENED_TEXTURE("GPU 단일 합성"),
}

enum class PixelRoute(val label: String, val detail: String) {
    RGB_8888("RGB 8888", "Canvas → RGBA BufferQueue"),
    RGB_565("RGB 565", "16-bit RGB BufferQueue"),
    YUV_420("YUV 420", "선택·검증된 영상의 hardware decoder-to-Surface"),
    P010("YUV P010", "10-bit decoder 콘텐츠 필요"),
    SBWC_AUTO("SBWC Auto", "벤더 allocator/codec이 선택할 때만 검증 가능"),
    SBWC_REQUIRED("SBWC Required", "벤더 adapter 없이는 실행 불가"),
}

/**
 * Routes whose primary producer uses the selected media's codec-to-Surface output.
 *
 * SBWC_REQUIRED still needs a separately verified vendor compression route. Including it here
 * only keeps the decoded content, dimensions, safety budget, and traffic attribution consistent
 * with YUV/SBWC A/B phases; it does not imply that platform signing can force compression.
 */
fun PixelRoute.usesSelectedMediaDecoder(): Boolean = when (this) {
    PixelRoute.YUV_420,
    PixelRoute.P010,
    PixelRoute.SBWC_AUTO,
    PixelRoute.SBWC_REQUIRED,
    -> true

    PixelRoute.RGB_8888,
    PixelRoute.RGB_565,
    -> false
}

enum class BufferSize(val label: String, val width: Int, val height: Int) {
    DISPLAY("Display", 0, 0),
    FHD("1080p", 1920, 1080),
    UHD_4K("4K", 3840, 2160),
    UHD_8K("8K", 7680, 4320),
}

enum class MotionSemantics(val changesPhysicalHwcZOrder: Boolean) {
    VIEW_TRANSFORM(false),
    VIEW_CLIENT_Z_ORDER_PROXY(false),
}

enum class MotionProfile(
    val label: String,
    val semantics: MotionSemantics = MotionSemantics.VIEW_TRANSFORM,
) {
    STATIC("Static"),
    SCROLL("Scroll"),
    ZOOM_PAN("Zoom + Pan"),
    ROTATE("Rotate"),
    PARALLAX("Parallax"),
    TRANSFORM_STORM("Storm"),
    Z_ORDER_SWAP(
        label = "View/client Z proxy",
        semantics = MotionSemantics.VIEW_CLIENT_Z_ORDER_PROXY,
    ),
}

enum class LoadShape(val label: String) {
    STEADY("Steady"),
    PULSE("Pulse"),
    RAMP("Ramp"),
    SAW("Saw"),
}

/**
 * Describes how a phase approaches and releases its target setpoint.
 *
 * This is intentionally separate from [LoadShape]. LoadShape is the legacy, per-generator
 * modulation ABI (including the vendor NPU wire contract), while this model is a bounded
 * phase-to-phase envelope that can also change layers, producer FPS, and requested refresh.
 */
enum class TransitionMode(val label: String, val description: String) {
    STEP(
        "Instant step",
        "목표 부하로 즉시 전환",
    ),
    LINEAR_RAMP(
        "Linear ramp",
        "이전 값에서 목표 값까지 선형 전환",
    ),
    STAIRCASE(
        "Staircase",
        "제한된 개수의 단계로 목표 값까지 전환",
    ),
    PULSE_BURST(
        "Pulse / burst",
        "이전 값과 목표 값을 duty cycle에 따라 반복",
    ),
    TRIANGLE_WAVE(
        "Triangle wave",
        "이전 값에서 목표 값까지 상승한 뒤 다시 하강",
    ),
    SOAK_RECOVERY(
        "Soak + recovery",
        "천천히 상승하고 목표 값을 유지한 뒤 이전 값으로 복구",
    ),
}

data class TransitionSpec(
    val mode: TransitionMode = TransitionMode.STEP,
    /**
     * Ramp/staircase duration. Zero means the whole phase for ramp/staircase and an automatic
     * 20% attack/release window for soak/recovery.
     */
    val transitionDurationMs: Long = 0L,
    /** Cycle length for pulse and triangle profiles. */
    val cycleMs: Long = DEFAULT_CYCLE_MS,
    /** Number of staircase levels including the initial and target plateaus. */
    val stepCount: Int = DEFAULT_STEP_COUNT,
    /** Fraction of a pulse cycle spent at the target. */
    val dutyCycle: Float = DEFAULT_DUTY_CYCLE,
    /** Minimum interpolation factor used by cyclic profiles. */
    val floor: Float = 0f,
) {
    /**
     * Returns an evaluator-safe copy. Runtime safety still validates the containing phase;
     * these bounds prevent malformed custom input from causing rapid reconfiguration or
     * non-finite setpoints.
     */
    fun boundedFor(phaseDurationMs: Long): TransitionSpec {
        val safeDuration = phaseDurationMs.coerceAtLeast(1L)
        val cyclicFloor = if (
            mode == TransitionMode.PULSE_BURST ||
            mode == TransitionMode.TRIANGLE_WAVE
        ) {
            floor.finiteUnitValue()
        } else {
            0f
        }
        return copy(
            transitionDurationMs = transitionDurationMs.coerceIn(0L, safeDuration),
            cycleMs = cycleMs.coerceIn(
                MIN_CYCLE_MS,
                safeDuration.coerceAtLeast(MIN_CYCLE_MS),
            ),
            stepCount = stepCount.coerceIn(MIN_STEP_COUNT, MAX_STEP_COUNT),
            dutyCycle = dutyCycle
                .takeIf(Float::isFinite)
                ?.coerceIn(MIN_DUTY_CYCLE, MAX_DUTY_CYCLE)
                ?: DEFAULT_DUTY_CYCLE,
            // A floor changes the zero-time origin. It is meaningful only for profiles that
            // intentionally cycle through a non-zero valley; every one-shot transition must
            // retain an exact origin sample before the first measured control tick.
            floor = cyclicFloor,
        )
    }

    fun summary(): String = when (mode) {
        TransitionMode.STEP -> mode.label
        TransitionMode.LINEAR_RAMP ->
            if (transitionDurationMs > 0L) {
                "${mode.label} · $transitionDurationMs ms"
            } else {
                "${mode.label} · phase-wide"
            }
        TransitionMode.STAIRCASE ->
            "${mode.label} · $stepCount levels"
        TransitionMode.PULSE_BURST ->
            "${mode.label} · $cycleMs ms · ${(dutyCycle.finiteUnitValue() * 100).roundToInt()}%"
        TransitionMode.TRIANGLE_WAVE ->
            "${mode.label} · $cycleMs ms"
        TransitionMode.SOAK_RECOVERY ->
            if (transitionDurationMs > 0L) {
                "${mode.label} · $transitionDurationMs ms edge"
            } else {
                "${mode.label} · auto edge"
            }
    }

    companion object {
        const val DEFAULT_CYCLE_MS = 4_000L
        const val DEFAULT_STEP_COUNT = 4
        const val DEFAULT_DUTY_CYCLE = 0.5f
        const val MIN_CYCLE_MS = 500L
        const val MIN_STEP_COUNT = 2
        const val MAX_STEP_COUNT = 20
        const val MIN_DUTY_CYCLE = 0.1f
        const val MAX_DUTY_CYCLE = 0.9f
    }
}

data class LoadSetpoints(
    val cpu: Float = 0f,
    val memory: Float = 0f,
    val gpu: Float = 0f,
    val npu: Float = 0f,
    val shape: LoadShape = LoadShape.STEADY,
) {
    fun normalized() = copy(
        cpu = cpu.finiteUnitValue(),
        memory = memory.finiteUnitValue(),
        gpu = gpu.finiteUnitValue(),
        npu = npu.finiteUnitValue(),
    )

    /**
     * Normalizes values at the last execution boundary and represents sub-observable duty cycles
     * as an explicit zero. This keeps renderer/HUD/report setpoints aligned with workers, which
     * deliberately idle at or below [MIN_EFFECTIVE_LOAD].
     */
    fun normalizedForExecution() = copy(
        cpu = cpu.finiteEffectiveLoad(),
        memory = memory.finiteEffectiveLoad(),
        gpu = gpu.finiteEffectiveLoad(),
        npu = npu.finiteEffectiveLoad(),
    )

    fun summary(): String {
        fun pct(value: Float) = (value.finiteUnitValue() * 100).roundToInt()
        return "CPU ${pct(cpu)}% · MEM ${pct(memory)}% · " +
            "GPU ${pct(gpu)}% · NPU ${pct(npu)}%"
    }
}

private fun Float.finiteUnitValue(): Float =
    takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f

private fun Float.finiteEffectiveLoad(): Float =
    finiteUnitValue().takeIf { it > MIN_EFFECTIVE_LOAD } ?: 0f

data class PhaseSpec(
    val id: String,
    val label: String,
    val durationMs: Long,
    val activeLayers: Int,
    val producerFps: Float,
    val requestedDisplayHz: Float,
    val backend: LayerBackend,
    val pixelRoute: PixelRoute,
    val bufferSize: BufferSize,
    val motion: MotionProfile,
    val workloads: LoadSetpoints = LoadSetpoints(),
    val alphaOverlap: Boolean = false,
    val includeGlLayer: Boolean = false,
    val transition: TransitionSpec = TransitionSpec(),
)

data class ScenarioSpec(
    val id: String,
    val name: String,
    val description: String,
    val category: ScenarioCategory,
    val risk: RiskLevel,
    val tags: Set<String>,
    val requirements: Set<String> = emptySet(),
    val phases: List<PhaseSpec>,
    val isCustom: Boolean = false,
) {
    val durationMs: Long
        get() {
            var total = 0L
            for (phase in phases) {
                if (phase.durationMs <= 0L) continue
                if (total > Long.MAX_VALUE - phase.durationMs) return Long.MAX_VALUE
                total += phase.durationMs
            }
            return total
        }
    val maxLayers: Int get() = phases.maxOfOrNull { it.activeLayers } ?: 0
    val maxHz: Float get() = phases.maxOfOrNull { it.requestedDisplayHz } ?: 0f
}

enum class PlanSource(val label: String) {
    SINGLE_SCENARIO("Single scenario"),
    USER_SELECTION("User selection"),
    EXTERNAL_INTENT("External intent"),
}

data class ScenarioRunPlan(
    /**
     * Queue order is significant. Duplicate scenarios are intentionally preserved so A/B/A
     * sequences can be expressed without cloning scenario IDs.
     */
    val scenarios: List<ScenarioSpec>,
    val repeatCount: Int = 1,
    val source: PlanSource = PlanSource.USER_SELECTION,
) {
    val totalRuns: Int
        get() = (scenarios.size.toLong() * repeatCount.toLong())
            .coerceIn(0L, Int.MAX_VALUE.toLong())
            .toInt()

    /** Scenario duration only; precheck, warm-up, cooldown, and report I/O are excluded. */
    val estimatedDurationMs: Long
        get() {
            if (repeatCount <= 0) return 0L
            var queueDurationMs = 0L
            for (scenario in scenarios) {
                if (queueDurationMs > Long.MAX_VALUE - scenario.durationMs) {
                    return Long.MAX_VALUE
                }
                queueDurationMs += scenario.durationMs
            }
            return if (queueDurationMs > Long.MAX_VALUE / repeatCount) {
                Long.MAX_VALUE
            } else {
                queueDurationMs * repeatCount
            }
        }
}

object ScenarioPlanPolicy {
    const val MAX_REPEAT_COUNT = 10
    const val MAX_TOTAL_PLAN_RUNS = 40
    const val ALLOW_DUPLICATE_SCENARIOS = true

    fun maximumRepeatCount(queueSize: Int): Int {
        if (queueSize <= 0) return MAX_REPEAT_COUNT
        return minOf(
            MAX_REPEAT_COUNT,
            (MAX_TOTAL_PLAN_RUNS / queueSize).coerceAtLeast(1),
        )
    }

    fun normalizeRepeatCount(queueSize: Int, requested: Int): Int =
        requested.coerceIn(1, maximumRepeatCount(queueSize))

    fun validate(plan: ScenarioRunPlan): String? {
        if (plan.scenarios.isEmpty()) return "Plan scenario queue must not be empty"
        if (plan.repeatCount !in 1..MAX_REPEAT_COUNT) {
            return "Plan repeat count must be between 1 and $MAX_REPEAT_COUNT"
        }
        val totalRuns = plan.scenarios.size.toLong() * plan.repeatCount.toLong()
        if (totalRuns > MAX_TOTAL_PLAN_RUNS) {
            return "Plan has $totalRuns runs; maximum is $MAX_TOTAL_PLAN_RUNS"
        }
        return null
    }
}

enum class PlanState {
    IDLE,
    RUNNING,
    COMPLETE,
    ABORTED,
    REJECTED,
}

data class PlanProgress(
    val state: PlanState = PlanState.IDLE,
    val source: PlanSource = PlanSource.USER_SELECTION,
    /** Zero-based while active; -1 when no queue item is selected. */
    val repeatIndex: Int = -1,
    val repeatCount: Int = 0,
    /** Zero-based while active; -1 when no queue item is selected. */
    val queueIndex: Int = -1,
    val queueSize: Int = 0,
    /** Fully finalized queue items. An aborted in-flight item is not counted as completed. */
    val completedRuns: Int = 0,
    val totalRuns: Int = 0,
    val currentScenario: ScenarioSpec? = null,
    val nextScenario: ScenarioSpec? = null,
    val currentRunFraction: Float = 0f,
    val statusText: String = "대기 중",
    /** Typed, bounded reason for REJECTED/ABORTED plan outcomes. */
    val terminalReason: String? = null,
) {
    val active: Boolean get() = state == PlanState.RUNNING
    val currentRepeat: Int get() = if (repeatIndex >= 0) repeatIndex + 1 else 0
    val currentQueuePosition: Int get() = if (queueIndex >= 0) queueIndex + 1 else 0
    val boundedCurrentRunFraction: Float
        get() = currentRunFraction
            .takeIf(Float::isFinite)
            ?.coerceIn(0f, 1f)
            ?: 0f
    val overallFraction: Float
        get() {
            if (totalRuns <= 0) return 0f
            val inFlight = if (active && currentScenario != null) {
                boundedCurrentRunFraction
            } else {
                0f
            }
            return ((completedRuns.coerceAtLeast(0).toDouble() + inFlight) / totalRuns)
                .coerceIn(0.0, 1.0)
                .toFloat()
        }
}

/**
 * Compact plan history. Full telemetry remains in [RunSummary] for the latest run and in each
 * report file; retaining every sample for every repeated run would grow memory without bound.
 */
data class PlanRunResult(
    /** Zero-based position in the expanded repeated plan. */
    val runIndex: Int,
    val repeatIndex: Int,
    val queueIndex: Int,
    val scenario: ScenarioSpec,
    val verdict: RunVerdict,
    val startedEpochMs: Long,
    val finishedEpochMs: Long,
    val exactUnderrunDelta: Long?,
    val suspectedUnderrunDelta: Long,
    val reportPath: String?,
    /** Bounded, user-facing reason for terminal outcomes such as UNSUPPORTED or ABORTED. */
    val terminalReason: String? = null,
) {
    val runNumber: Int get() = runIndex + 1
}

enum class RunnerStage {
    IDLE,
    PRECHECK,
    WARMUP,
    RUNNING,
    COOLDOWN,
    COMPLETE,
    ABORTED,
    UNSUPPORTED,
}

data class RunProgress(
    val stage: RunnerStage = RunnerStage.IDLE,
    val scenario: ScenarioSpec? = null,
    val phaseIndex: Int = -1,
    val phase: PhaseSpec? = null,
    val elapsedMs: Long = 0,
    val phaseElapsedMs: Long = 0,
    val statusText: String = "대기 중",
    val controlLayerIncluded: Boolean = true,
    /** Target before applying a phase transition or persistent runtime derating. */
    val targetPhase: PhaseSpec? = null,
    /** Current interpolation factor. Cyclic transitions may move in both directions. */
    val transitionFraction: Float = 1f,
    /** Persistent plan-level thermal derating state; never infer this from localized UI text. */
    val thermalDerated: Boolean = false,
    /** Token that binds every physical producer callback to the currently displayed phase. */
    val producerGeneration: Long = 0L,
    /** Physical BufferQueue producers expected/observed within the live health window. */
    val expectedProducerCount: Int = 0,
    val observedProducerCount: Int = 0,
) {
    val overallFraction: Float
        get() {
            val spec = scenario ?: return 0f
            if (spec.durationMs == 0L) return 0f
            return (elapsedMs.toDouble() / spec.durationMs.toDouble())
                .coerceIn(0.0, 1.0)
                .toFloat()
        }

    val boundedTransitionFraction: Float
        get() = transitionFraction
            .takeIf(Float::isFinite)
            ?.coerceIn(0f, 1f)
            ?: 0f

    val displayedTargetPhase: PhaseSpec?
        get() = targetPhase ?: phase
}

enum class MetricQuality(val label: String) {
    HARDWARE_COUNTER("HW counter"),
    KERNEL("Kernel"),
    SYSTEM_SERVICE("System"),
    MEASURED("Measured"),
    ESTIMATED("Estimated"),
    PROXY("Proxy"),
    UNAVAILABLE("Unavailable"),
}

data class Gauge(
    val value: Float? = null,
    val unit: String = "",
    val quality: MetricQuality = MetricQuality.UNAVAILABLE,
    val source: String = "",
) {
    fun display(decimals: Int = 0): String {
        val current = value?.takeIf(Float::isFinite) ?: return "N/A"
        val safeDecimals = decimals.coerceIn(0, 3)
        return if (safeDecimals == 0) {
            "${current.roundToInt()}$unit"
        } else {
            "%.${safeDecimals}f%s".format(current, unit)
        }
    }
}

data class TelemetrySnapshot(
    val monotonicMs: Long = 0,
    val cpu: Gauge = Gauge(),
    val appCpu: Gauge = Gauge(),
    val memoryUsed: Gauge = Gauge(),
    val memoryAvailable: Gauge = Gauge(),
    val appPss: Gauge = Gauge(),
    val displayHz: Gauge = Gauge(),
    val producedFps: Gauge = Gauge(),
    val missedFrames: Long = 0,
    val suspectedUnderruns: Long = 0,
    val suspectedUnderrunQuality: MetricQuality = MetricQuality.PROXY,
    val suspectedUnderrunSource: String = "FrameTracker · Choreographer deadline miss",
    val exactUnderruns: Long? = null,
    val exactUnderrunSource: String? = null,
    val exactUnderrunQuality: MetricQuality = MetricQuality.UNAVAILABLE,
    val gpuBusy: Gauge = Gauge(),
    val gpuFrequency: Gauge = Gauge(),
    val busBusy: Gauge = Gauge(),
    val generatedBandwidth: Gauge = Gauge(),
    val dpuBusy: Gauge = Gauge(),
    /** Optional product-provided DPU/decon clock. N/A when no validated source is configured. */
    val dpuFrequency: Gauge = Gauge(),
    val hwcDeviceLayers: Int? = null,
    val hwcDeviceLayersQuality: MetricQuality = MetricQuality.UNAVAILABLE,
    val hwcDeviceLayersSource: String = "",
    val hwcClientLayers: Int? = null,
    val hwcClientLayersQuality: MetricQuality = MetricQuality.UNAVAILABLE,
    val hwcClientLayersSource: String = "",
    val surfaceFlingerHwcMissed: Long? = null,
    val surfaceFlingerGpuMissed: Long? = null,
    val surfaceFlingerMissSource: String = "",
    val thermalStatus: Int = 0,
    val thermalLabel: String = "정상",
    val memoryLow: Boolean = false,
    val powerSaveMode: Boolean = false,
    /** Current process-local vendor Binder registration, independent of snapshot success. */
    val vendorServiceSession: Long? = null,
    /** Bounded, sanitized provider-reported compression state. */
    val compressionState: String = "Adapter 없음",
    val npuState: String = "Adapter 없음",
)

data class SensorReading(
    val key: String,
    val label: String,
    val value: String,
    val quality: MetricQuality,
    val source: String,
)

enum class RunVerdict(val label: String) {
    CLEAN("CLEAN"),
    UNDERRUN_DETECTED("UNDERRUN DETECTED"),
    SUSPECTED_PROXY("SUSPECTED / PROXY"),
    INCONCLUSIVE("INCONCLUSIVE"),
    UNSUPPORTED("UNSUPPORTED"),
    ABORTED("ABORTED"),
}

data class RunEvent(
    val monotonicMs: Long,
    val type: String,
    val message: String,
)

data class RunSummary(
    val scenario: ScenarioSpec,
    val startedEpochMs: Long,
    val finishedEpochMs: Long,
    val verdict: RunVerdict,
    val exactUnderrunDelta: Long?,
    val exactUnderrunSource: String?,
    val exactUnderrunQuality: MetricQuality,
    val suspectedUnderrunDelta: Long,
    val peakCpu: Float?,
    val peakMemoryUsed: Float?,
    val peakGeneratedBandwidth: Float?,
    val events: List<RunEvent>,
    val samples: List<TelemetrySnapshot>,
)

private val TERMINAL_RUN_EVENT_TYPES = setOf(
    "UNSUPPORTED",
    "INCONCLUSIVE",
    "ABORTED",
    "ERROR",
    "PRODUCER_TEARDOWN_UNCONFIRMED",
)

/**
 * One terminal-event interpretation is shared by compact plan results and the latest-result UI.
 */
fun RunSummary.terminalReason(maxChars: Int = 300): String? {
    return terminalRunReason(events, maxChars)
}

internal fun terminalRunReason(events: List<RunEvent>, maxChars: Int = 300): String? {
    require(maxChars > 0)
    return events.lastOrNull { it.type in TERMINAL_RUN_EVENT_TYPES }
        ?.message
        ?.lineSequence()
        ?.firstOrNull()
        ?.take(maxChars)
}

data class DeviceIdentity(
    val manufacturer: String = Build.MANUFACTURER,
    val model: String = Build.MODEL,
    val device: String = Build.DEVICE,
    val sdk: Int = Build.VERSION.SDK_INT,
    val release: String = Build.VERSION.RELEASE,
    val fingerprint: String = Build.FINGERPRINT,
)
