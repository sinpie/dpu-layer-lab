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
    DPU_BURST("DPU 저→고 burst"),
    HWC_DEVICE_ONLY("DEVICE 유지 목표"),
    HWC_CLIENT_REQUIRED("CLIENT 전환 목표"),
    SMALL_LAYER_GEOMETRY("작은 layer"),
    MIXED_LAYER_GEOMETRY("Mixed layer size"),
    LAYER_SIZE_CHANGE("Layer 크기 전환"),
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
                    it.workloads.shape == LoadShape.PULSE ||
                    it.layerSizeProfile == LayerSizeProfile.ABRUPT_SMALL_FULL
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
                it.workloads.shape == LoadShape.SAW ||
                it.layerSizeProfile == LayerSizeProfile.GRADUAL_SMALL_TO_FULL
        }
        if (gradual) result += ScenarioChangePattern.GRADUAL

        val cyclic = phases.any {
            it.transition.mode == TransitionMode.PULSE_BURST ||
                it.transition.mode == TransitionMode.TRIANGLE_WAVE ||
                it.workloads.shape in CYCLIC_LOAD_SHAPES ||
                it.layerSizeProfile == LayerSizeProfile.ABRUPT_SMALL_FULL
        }
        if (cyclic) result += ScenarioChangePattern.CYCLIC

        if (result.isEmpty()) result += ScenarioChangePattern.STEADY
        return result
    }

    fun conditions(scenario: ScenarioSpec): Set<ScenarioCondition> {
        val result = linkedSetOf<ScenarioCondition>()
        val workloads = scenario.phases.map { it.workloads.normalized() }
        val hasCpu = workloads.any { it.cpu > MIN_EFFECTIVE_LOAD }
        val hasMemory = workloads.any { it.memory > MIN_EFFECTIVE_LOAD }
        val hasGpu = workloads.any { it.gpu > MIN_EFFECTIVE_LOAD }
        val hasNpu = workloads.any { it.npu > MIN_EFFECTIVE_LOAD }
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
        if (scenario.phases.zipWithNext().any(::isDpuLowToHighBurst)) {
            result += ScenarioCondition.DPU_BURST
        }
        if (
            scenario.phases.any {
                it.hwcCompositionExpectation == HwcCompositionExpectation.DEVICE_ONLY
            }
        ) {
            result += ScenarioCondition.HWC_DEVICE_ONLY
        }
        if (
            scenario.phases.any {
                it.hwcCompositionExpectation == HwcCompositionExpectation.CLIENT_REQUIRED
            }
        ) {
            result += ScenarioCondition.HWC_CLIENT_REQUIRED
        }
        if (
            scenario.phases.any {
                it.layerSizeProfile == LayerSizeProfile.SMALL_UNIFORM
            }
        ) {
            result += ScenarioCondition.SMALL_LAYER_GEOMETRY
        }
        if (
            scenario.phases.any {
                it.layerSizeProfile == LayerSizeProfile.MIXED_SIZES
            }
        ) {
            result += ScenarioCondition.MIXED_LAYER_GEOMETRY
        }
        if (
            scenario.phases.any {
                it.layerSizeProfile == LayerSizeProfile.GRADUAL_SMALL_TO_FULL ||
                    it.layerSizeProfile == LayerSizeProfile.ABRUPT_SMALL_FULL
            }
        ) {
            result += ScenarioCondition.LAYER_SIZE_CHANGE
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
        val visibleAreaFactor = representativeDestinationAreaFactor(phase)
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
                    // Source allocation/write pressure remains tied to the full producer buffer.
                    // Destination footprint is a separate, smaller composition-geometry signal.
                    resolutionFactor * 0.20f +
                    visibleAreaFactor * 0.05f +
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
            from.layerSizeProfile != to.layerSizeProfile ||
            from.includeGlLayer != to.includeGlLayer ||
            from.alphaOverlap != to.alphaOverlap

    private fun controlVectorDiffers(from: PhaseSpec, to: PhaseSpec): Boolean =
        topologyDiffers(from, to) ||
            from.producerFps != to.producerFps ||
            from.requestedDisplayHz != to.requestedDisplayHz ||
            from.motion != to.motion ||
            from.workloads.normalized() != to.workloads.normalized()

    /**
     * A catalog-selectable DPU burst is derived from typed control values rather than names/tags.
     * It deliberately describes only the requested display-pressure edge; it does not imply that
     * DPU clocks, DEVICE composition, or an underrun changed.
     */
    private fun isDpuLowToHighBurst(phases: Pair<PhaseSpec, PhaseSpec>): Boolean {
        val (from, to) = phases
        return to.transition.mode == TransitionMode.STEP &&
            from.activeLayers in 1..2 &&
            from.producerFps.finiteOrZero() in 1f..30f &&
            from.requestedDisplayHz.finiteOrZero() in 1f..60f &&
            to.activeLayers >= MIN_DPU_BURST_TARGET_LAYERS &&
            to.activeLayers - from.activeLayers >= MIN_DPU_BURST_LAYER_DELTA &&
            to.producerFps.finiteOrZero() >= 90f &&
            to.requestedDisplayHz.finiteOrZero() >= 90f
    }

    private fun unitRatio(value: Float, denominator: Float): Float =
        value.takeIf(Float::isFinite)
            ?.div(denominator)
            ?.coerceIn(0f, 1f)
            ?: 0f

    private fun Float.finiteOrZero(): Float = takeIf(Float::isFinite) ?: 0f

    internal fun representativeDestinationAreaFactor(phase: PhaseSpec): Float {
        val physicalLayerCount = if (phase.backend == LayerBackend.FLATTENED_TEXTURE) {
            1
        } else {
            phase.activeLayers.coerceIn(1, MAX_CLASSIFIED_LAYERS)
        }
        if (phase.motion == MotionProfile.CAPACITY_TILES) {
            return 1f / physicalLayerCount.toFloat()
        }
        return when (phase.layerSizeProfile) {
            LayerSizeProfile.GRADUAL_SMALL_TO_FULL -> {
                // Width and height scales are linear in phase time, so area is quadratic.
                // Simpson's rule is exact for that polynomial and remains tied to the canonical
                // profile evaluator if its endpoints change.
                val start = averageDestinationArea(
                    profile = phase.layerSizeProfile,
                    layerCount = physicalLayerCount,
                    phaseFraction = 0f,
                )
                val midpoint = averageDestinationArea(
                    profile = phase.layerSizeProfile,
                    layerCount = physicalLayerCount,
                    phaseFraction = 0.5f,
                )
                val end = averageDestinationArea(
                    profile = phase.layerSizeProfile,
                    layerCount = physicalLayerCount,
                    phaseFraction = 1f,
                )
                (start + 4f * midpoint + end) / 6f
            }

            LayerSizeProfile.ABRUPT_SMALL_FULL -> {
                // Every abrupt step occupies an equal fraction of the phase. Sampling each
                // midpoint avoids boundary ambiguity and remains correct if the step pattern is
                // changed while its bounded step count is preserved.
                var total = 0f
                var step = 0
                while (step < ABRUPT_LAYER_SIZE_PROFILE_STEPS) {
                    total += averageDestinationArea(
                        profile = phase.layerSizeProfile,
                        layerCount = physicalLayerCount,
                        phaseFraction =
                            (step + 0.5f) / ABRUPT_LAYER_SIZE_PROFILE_STEPS.toFloat(),
                    )
                    step++
                }
                total / ABRUPT_LAYER_SIZE_PROFILE_STEPS.toFloat()
            }

            LayerSizeProfile.FULL_SCREEN,
            LayerSizeProfile.SMALL_UNIFORM,
            LayerSizeProfile.MIXED_SIZES,
            -> averageDestinationArea(
                profile = phase.layerSizeProfile,
                layerCount = physicalLayerCount,
                phaseFraction = 0f,
            )
        }.takeIf(Float::isFinite)
            ?.coerceIn(0f, 1f)
            ?: 1f
    }

    private fun averageDestinationArea(
        profile: LayerSizeProfile,
        layerCount: Int,
        phaseFraction: Float,
    ): Float {
        var total = 0f
        var index = 0
        while (index < layerCount) {
            total += profile.normalizedSizeForLayer(
                layerIndex = index,
                layerCount = layerCount,
                phaseFraction = phaseFraction,
            ).areaScale
            index++
        }
        return (total / layerCount.toFloat())
            .takeIf(Float::isFinite)
            ?.coerceIn(0f, 1f)
            ?: 1f
    }

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

    private const val MIN_DPU_BURST_TARGET_LAYERS = 4
    private const val MIN_DPU_BURST_LAYER_DELTA = 3
    private const val MAX_CLASSIFIED_LAYERS = 20
}
