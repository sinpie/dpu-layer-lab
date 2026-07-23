package com.example.dpulayerlab.model

import android.os.Build
import kotlin.math.roundToInt

enum class ScenarioCategory(val label: String) {
    LAYER_HWC("Layer / HWC"),
    TRANSFORM("Transform"),
    VIDEO_FORMAT("Video / Format"),
    REFRESH("Refresh"),
    RESOURCE("Resource"),
    MIXED("Mixed"),
    ADAPTIVE("Adaptive"),
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
    YUV_420("YUV 420", "선택 영상은 decoder-to-Surface, 없으면 YUV 패턴 proxy"),
    P010("YUV P010", "10-bit decoder 콘텐츠 필요"),
    SBWC_AUTO("SBWC Auto", "벤더 allocator/codec이 선택할 때만 검증 가능"),
    SBWC_REQUIRED("SBWC Required", "벤더 adapter 없이는 실행 불가"),
}

enum class BufferSize(val label: String, val width: Int, val height: Int) {
    DISPLAY("Display", 0, 0),
    FHD("1080p", 1920, 1080),
    UHD_4K("4K", 3840, 2160),
    UHD_8K("8K", 7680, 4320),
}

enum class MotionProfile(val label: String) {
    STATIC("Static"),
    SCROLL("Scroll"),
    ZOOM_PAN("Zoom + Pan"),
    ROTATE("Rotate"),
    PARALLAX("Parallax"),
    TRANSFORM_STORM("Storm"),
    Z_ORDER_SWAP("Z swap"),
}

enum class LoadShape(val label: String) {
    STEADY("Steady"),
    PULSE("Pulse"),
    RAMP("Ramp"),
    SAW("Saw"),
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

    fun summary(): String {
        fun pct(value: Float) = (value.finiteUnitValue() * 100).roundToInt()
        return "CPU ${pct(cpu)}% · MEM ${pct(memory)}% · " +
            "GPU ${pct(gpu)}% · NPU ${pct(npu)}%"
    }
}

private fun Float.finiteUnitValue(): Float =
    takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f

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
) {
    val overallFraction: Float
        get() {
            val spec = scenario ?: return 0f
            if (spec.durationMs == 0L) return 0f
            return (elapsedMs.toDouble() / spec.durationMs.toDouble())
                .coerceIn(0.0, 1.0)
                .toFloat()
        }
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
    val exactUnderruns: Long? = null,
    val exactUnderrunSource: String? = null,
    val gpuBusy: Gauge = Gauge(),
    val gpuFrequency: Gauge = Gauge(),
    val busBusy: Gauge = Gauge(),
    val generatedBandwidth: Gauge = Gauge(),
    val dpuBusy: Gauge = Gauge(),
    val hwcDeviceLayers: Int? = null,
    val hwcClientLayers: Int? = null,
    val surfaceFlingerHwcMissed: Long? = null,
    val surfaceFlingerGpuMissed: Long? = null,
    val thermalStatus: Int = 0,
    val thermalLabel: String = "정상",
    val memoryLow: Boolean = false,
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
    val suspectedUnderrunDelta: Long,
    val peakCpu: Float?,
    val peakMemoryUsed: Float?,
    val peakGeneratedBandwidth: Float?,
    val events: List<RunEvent>,
    val samples: List<TelemetrySnapshot>,
)

data class DeviceIdentity(
    val manufacturer: String = Build.MANUFACTURER,
    val model: String = Build.MODEL,
    val device: String = Build.DEVICE,
    val sdk: Int = Build.VERSION.SDK_INT,
    val release: String = Build.VERSION.RELEASE,
    val fingerprint: String = Build.FINGERPRINT,
)
