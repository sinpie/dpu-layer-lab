package com.example.dpulayerlab.model

import kotlin.math.roundToInt

/**
 * Orthogonal catalog facets. Multiple values inside one facet are OR-ed; different facets are
 * AND-ed. This lets an operator request combinations such as
 * "Transition × gradual × high-or-very-high × memory-or-GPU" without cloning presets.
 */
enum class ScenarioChangePattern(val label: String) {
    INSTANT("순간 STEP"),
    GRADUAL("느린 점진"),
    CYCLIC("반복/펄스"),
    STEADY("고정 유지"),
}

enum class ScenarioLoadBand(
    val label: String,
    private val scoreRange: IntRange,
) {
    LOW("낮음", 0..29),
    MEDIUM("보통", 30..49),
    HIGH("높음", 50..69),
    VERY_HIGH("매우 높음", 70..100),
    ;

    fun contains(score: Int): Boolean = score.coerceIn(0, 100) in scoreRange

    companion object {
        fun fromScore(score: Int): ScenarioLoadBand =
            entries.first { it.contains(score) }
    }
}

enum class ScenarioCondition(val label: String) {
    DISPLAY_ONLY("Display only"),
    MULTI_LAYER("Multi-layer"),
    CPU("CPU"),
    MEMORY("Memory"),
    GPU("GPU stress load"),
    NPU("NPU"),
    VIDEO("Video decoder"),
    RGB("RGB"),
    YUV("YUV 4:2:0"),
    P010("P010 10-bit"),
    SBWC("SBWC"),
    FOUR_K("4K"),
    EIGHT_K("8K"),
    TRANSFORM("Scroll/Zoom/Rotate"),
    HIGH_REFRESH("> 60 FPS/Hz"),
    DVFS("DVFS/저전력"),
}

data class ScenarioSelectionFilter(
    /** Empty means every category. */
    val categories: Set<ScenarioCategory> = emptySet(),
    /** Empty means every change pattern. */
    val patterns: Set<ScenarioChangePattern> = emptySet(),
    /** Empty means every estimated load band. */
    val loadBands: Set<ScenarioLoadBand> = emptySet(),
    /** Empty means every resource/condition. */
    val conditions: Set<ScenarioCondition> = emptySet(),
) {
    val isActive: Boolean
        get() = categories.isNotEmpty() ||
            patterns.isNotEmpty() ||
            loadBands.isNotEmpty() ||
            conditions.isNotEmpty()

    fun matches(scenario: ScenarioSpec): Boolean {
        if (categories.isNotEmpty() && scenario.category !in categories) return false
        if (
            patterns.isNotEmpty() &&
            ScenarioClassifier.changePatterns(scenario).none(patterns::contains)
        ) {
            return false
        }
        if (
            loadBands.isNotEmpty() &&
            ScenarioClassifier.loadBand(scenario) !in loadBands
        ) {
            return false
        }
        if (
            conditions.isNotEmpty() &&
            ScenarioClassifier.conditions(scenario).none(conditions::contains)
        ) {
            return false
        }
        return true
    }
}

object ScenarioClassifier {
    fun filter(
        scenarios: List<ScenarioSpec>,
        selection: ScenarioSelectionFilter,
    ): List<ScenarioSpec> =
        if (!selection.isActive) scenarios else scenarios.filter(selection::matches)

    fun intensityScore(scenario: ScenarioSpec): Int =
        scenario.phases.maxOfOrNull(::intensityScore) ?: 0

    fun loadBand(scenario: ScenarioSpec): ScenarioLoadBand =
        ScenarioLoadBand.fromScore(intensityScore(scenario))

    fun changePatterns(scenario: ScenarioSpec): Set<ScenarioChangePattern> {
        val result = linkedSetOf<ScenarioChangePattern>()
        val phases = scenario.phases
        val explicitInstant =
            phases.any {
                it.transition.mode == TransitionMode.PULSE_BURST ||
                    it.workloads.shape == LoadShape.PULSE
            } ||
                phases.zipWithNext().any { (from, to) ->
                    to.transition.mode == TransitionMode.STEP &&
                        controlVectorDiffers(from, to)
                } ||
                scenario.tags.any {
                    val tag = it.lowercase()
                    tag.contains("instant") ||
                        tag.contains("burst") ||
                        tag.contains("shock") ||
                        tag.contains("idle→burst")
                }
        if (explicitInstant) result += ScenarioChangePattern.INSTANT

        val gradual = phases.any {
            it.transition.mode in GRADUAL_TRANSITIONS ||
                it.workloads.shape == LoadShape.RAMP ||
                it.workloads.shape == LoadShape.SAW
        }
        if (gradual) result += ScenarioChangePattern.GRADUAL

        val cyclic = phases.any {
            it.transition.mode == TransitionMode.PULSE_BURST ||
                it.transition.mode == TransitionMode.TRIANGLE_WAVE ||
                it.workloads.shape in CYCLIC_LOAD_SHAPES
        }
        if (cyclic) result += ScenarioChangePattern.CYCLIC

        if (result.isEmpty()) result += ScenarioChangePattern.STEADY
        return result
    }

    fun conditions(scenario: ScenarioSpec): Set<ScenarioCondition> {
        val result = linkedSetOf<ScenarioCondition>()
        val workloads = scenario.phases.map { it.workloads.normalized() }
        val hasCpu = workloads.any { it.cpu > ACTIVE_LOAD_EPSILON }
        val hasMemory = workloads.any { it.memory > ACTIVE_LOAD_EPSILON }
        val hasGpu = workloads.any { it.gpu > ACTIVE_LOAD_EPSILON }
        val hasNpu = workloads.any { it.npu > ACTIVE_LOAD_EPSILON }
        if (!hasCpu && !hasMemory && !hasGpu && !hasNpu) {
            result += ScenarioCondition.DISPLAY_ONLY
        }
        if (scenario.phases.any { it.activeLayers > 1 }) {
            result += ScenarioCondition.MULTI_LAYER
        }
        if (hasCpu) result += ScenarioCondition.CPU
        if (hasMemory) result += ScenarioCondition.MEMORY
        if (hasGpu) result += ScenarioCondition.GPU
        if (hasNpu) result += ScenarioCondition.NPU
        if (
            scenario.phases.any {
                it.pixelRoute == PixelRoute.RGB_8888 ||
                    it.pixelRoute == PixelRoute.RGB_565
            }
        ) {
            result += ScenarioCondition.RGB
        }
        if (scenario.phases.any { it.pixelRoute == PixelRoute.YUV_420 }) {
            result += ScenarioCondition.YUV
        }
        if (scenario.phases.any { it.pixelRoute == PixelRoute.P010 }) {
            result += ScenarioCondition.P010
        }
        if (
            scenario.phases.any {
                it.pixelRoute == PixelRoute.SBWC_AUTO ||
                    it.pixelRoute == PixelRoute.SBWC_REQUIRED
            }
        ) {
            result += ScenarioCondition.SBWC
        }
        if (scenario.phases.any { it.bufferSize == BufferSize.UHD_4K }) {
            result += ScenarioCondition.FOUR_K
        }
        if (scenario.phases.any { it.bufferSize == BufferSize.UHD_8K }) {
            result += ScenarioCondition.EIGHT_K
        }
        if (scenario.phases.any { it.motion != MotionProfile.STATIC }) {
            result += ScenarioCondition.TRANSFORM
        }
        if (
            scenario.phases.any {
                it.producerFps.finiteOrZero() > 60f ||
                    it.requestedDisplayHz.finiteOrZero() > 60f
            }
        ) {
            result += ScenarioCondition.HIGH_REFRESH
        }
        if (scenario.phases.any { it.pixelRoute.usesSelectedMediaDecoder() }) {
            result += ScenarioCondition.VIDEO
        }
        if (
            scenario.tags.any {
                val tag = it.lowercase()
                tag.contains("dvfs") ||
                    tag.contains("clock") ||
                    tag.contains("boundary") ||
                    tag.contains("idle→burst")
            }
        ) {
            result += ScenarioCondition.DVFS
        }
        return result
    }

    fun intensityScore(phase: PhaseSpec): Int {
        val layerFactor = (phase.activeLayers.toFloat() / 20f).coerceIn(0f, 1f)
        val fpsFactor = unitRatio(phase.producerFps, 120f)
        val hzFactor = unitRatio(phase.requestedDisplayHz, 120f)
        val resolutionFactor = when (phase.bufferSize) {
            BufferSize.DISPLAY -> 0.35f
            BufferSize.FHD -> 0.45f
            BufferSize.UHD_4K -> 0.75f
            BufferSize.UHD_8K -> 1f
        }
        val crossLoadFactor = phase.workloads.normalized().let {
            maxOf(it.cpu, it.memory, it.gpu, it.npu)
        }
        val complexityFactor = maxOf(
            if (phase.backend == LayerBackend.INDEPENDENT_SURFACES) 0f else 0.7f,
            if (phase.motion == MotionProfile.STATIC) 0f else 0.55f,
            if (phase.alphaOverlap) 0.8f else 0f,
            if (phase.includeGlLayer) 1f else 0f,
        )
        return (
            (
                layerFactor * 0.25f +
                    fpsFactor * 0.15f +
                    hzFactor * 0.10f +
                    resolutionFactor * 0.25f +
                    crossLoadFactor * 0.20f +
                    complexityFactor * 0.05f
                ) * 100f
            ).roundToInt().coerceIn(0, 100)
    }

    private fun topologyDiffers(from: PhaseSpec, to: PhaseSpec): Boolean =
        from.activeLayers != to.activeLayers ||
            from.backend != to.backend ||
            from.pixelRoute != to.pixelRoute ||
            from.bufferSize != to.bufferSize ||
            from.includeGlLayer != to.includeGlLayer ||
            from.alphaOverlap != to.alphaOverlap

    private fun controlVectorDiffers(from: PhaseSpec, to: PhaseSpec): Boolean =
        topologyDiffers(from, to) ||
            from.producerFps != to.producerFps ||
            from.requestedDisplayHz != to.requestedDisplayHz ||
            from.motion != to.motion ||
            from.workloads.normalized() != to.workloads.normalized()

    private fun unitRatio(value: Float, denominator: Float): Float =
        value.takeIf(Float::isFinite)
            ?.div(denominator)
            ?.coerceIn(0f, 1f)
            ?: 0f

    private fun Float.finiteOrZero(): Float = takeIf(Float::isFinite) ?: 0f

    private val GRADUAL_TRANSITIONS = setOf(
        TransitionMode.LINEAR_RAMP,
        TransitionMode.STAIRCASE,
        TransitionMode.TRIANGLE_WAVE,
        TransitionMode.SOAK_RECOVERY,
    )
    private val CYCLIC_LOAD_SHAPES = setOf(
        LoadShape.PULSE,
        LoadShape.RAMP,
        LoadShape.SAW,
    )
    private const val ACTIVE_LOAD_EPSILON = 0.001f
}
