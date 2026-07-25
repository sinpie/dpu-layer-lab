package com.example.dpulayerlab.model

import java.math.BigInteger

data class RenderSafetyLimits(
    val displayWidthPx: Int,
    val displayHeightPx: Int,
    val maxLayers: Int,
    val maxProducerFps: Float,
    val maxPhaseDurationMs: Long,
    val maxScenarioDurationMs: Long,
    val maxGraphicsBytes: Long,
    val maxCpuLoad: Float,
    val maxMemoryLoad: Float,
    val maxGpuLoad: Float,
    val maxNpuLoad: Float,
    /** Power-save state captured by the same detection pass that produced these caps. */
    val powerSaveMode: Boolean = false,
)

data class ScenarioSafetyDecision(
    val effectiveScenario: ScenarioSpec?,
    val adjustments: List<String>,
    val rejectionReason: String?,
)

/**
 * Actual dimensions of the selected decoder output.
 *
 * A non-null instance means a media source was selected. Nullable dimensions deliberately
 * preserve that distinction so decoder-backed phases fail closed instead of falling back to the
 * requested phase size when track metadata is unavailable.
 */
data class SelectedDecoderBuffer(
    val widthPx: Int?,
    val heightPx: Int?,
)

internal fun PhaseSpec.requiresSelectedDecoderProducer(): Boolean =
    backend != LayerBackend.FLATTENED_TEXTURE &&
        pixelRoute.usesSelectedMediaDecoder() &&
        !(includeGlLayer && activeLayers == 1)

/**
 * Validates externally supplied scenarios and bounds their render/load cost before execution.
 *
 * Graphics memory is deliberately estimated more conservatively than traffic:
 * every producer is treated as RGBA8888 with three queued buffers. A flattened
 * backend has one display-sized producer, while the other backends have one
 * requested-size primary producer plus display-sized overlay producers.
 */
object ScenarioSafetyPolicy {
    const val MAX_PHASE_COUNT = 128
    const val HARD_MAX_LAYERS = 20
    const val HARD_MAX_PRODUCER_FPS = 120f
    const val HARD_MAX_DISPLAY_HZ = 240f
    const val MAX_ID_CHARS = 128
    const val MAX_LABEL_CHARS = 256
    const val MAX_DESCRIPTION_CHARS = 4_096
    const val MAX_METADATA_ITEMS = 64
    // DEVICE_ONLY needs one fresh probe; CLIENT_REQUIRED needs two distinct fresh samples.
    // Both include the 3 s first-buffer readiness bound, a possible 4 s in-flight periodic sample
    // mutex drain, and a post-probe control tick inside the active phase budget.
    const val MIN_DEVICE_ONLY_PHASE_DURATION_MS = 12_000L
    const val MIN_CLIENT_REQUIRED_PHASE_DURATION_MS = 16_000L

    fun minimumHwcExpectationPhaseDurationMs(
        expectation: HwcCompositionExpectation,
    ): Long = when (expectation) {
        HwcCompositionExpectation.NONE -> 0L
        HwcCompositionExpectation.DEVICE_ONLY -> MIN_DEVICE_ONLY_PHASE_DURATION_MS
        HwcCompositionExpectation.CLIENT_REQUIRED -> MIN_CLIENT_REQUIRED_PHASE_DURATION_MS
    }

    private const val RGBA_BYTES_PER_PIXEL = 4L
    // EGL requests a 16-bit depth buffer, but drivers may round the attachment up to 24/32 bits.
    private const val CONSERVATIVE_GL_DEPTH_BYTES_PER_PIXEL = 4L
    private const val BUFFER_COUNT = 3L
    private const val MAX_NORMALIZED_LOAD = 1f

    fun evaluate(
        scenario: ScenarioSpec,
        limits: RenderSafetyLimits,
        selectedDecoderBuffer: SelectedDecoderBuffer? = null,
    ): ScenarioSafetyDecision {
        validateLimits(limits)?.let { return rejected(it) }
        validateScenario(scenario)?.let { return rejected(it) }

        val decoderPhase = scenario.phases.firstOrNull(PhaseSpec::requiresSelectedDecoderProducer)
        if (decoderPhase != null && selectedDecoderBuffer == null) {
            return rejected(
                "Phase '${decoderPhase.id}' requires a selected, metadata-verified decoder buffer",
            )
        }
        if (
            decoderPhase != null &&
            (
                selectedDecoderBuffer?.widthPx?.takeIf { it > 0 } == null ||
                    selectedDecoderBuffer.heightPx?.takeIf { it > 0 } == null
                )
        ) {
            return rejected(
                "Phase '${decoderPhase.id}' decoder dimensions must be complete and positive",
            )
        }

        if (limits.maxScenarioDurationMs < scenario.phases.size.toLong()) {
            return rejected(
                "Scenario duration limit must allow at least 1 ms for every phase",
            )
        }

        val layerLimit = minOf(limits.maxLayers, HARD_MAX_LAYERS)
        val fpsLimit = minOf(limits.maxProducerFps, HARD_MAX_PRODUCER_FPS)
        val workloadLimits = LoadSetpoints(
            cpu = minOf(limits.maxCpuLoad, MAX_NORMALIZED_LOAD),
            memory = minOf(limits.maxMemoryLoad, MAX_NORMALIZED_LOAD),
            gpu = minOf(limits.maxGpuLoad, MAX_NORMALIZED_LOAD),
            npu = minOf(limits.maxNpuLoad, MAX_NORMALIZED_LOAD),
        )
        val adjustments = mutableListOf<String>()
        val boundedPhases = ArrayList<PhaseSpec>(scenario.phases.size)

        for (phase in scenario.phases) {
            val layerCapped = minOf(phase.activeLayers, layerLimit)
            val budgetCapped = maximumLayersWithinGraphicsBudget(
                phase = phase,
                requestedLayers = layerCapped,
                limits = limits,
                selectedDecoderBuffer = selectedDecoderBuffer,
            )
            if (budgetCapped == 0) {
                return rejected(
                    "Phase '${phase.id}' needs more than the graphics memory budget for one producer",
                )
            }

            val producerFps = minOf(phase.producerFps, fpsLimit)
            val displayHz = minOf(phase.requestedDisplayHz, HARD_MAX_DISPLAY_HZ)
            val durationMs = minOf(phase.durationMs, limits.maxPhaseDurationMs)
            val workloads = capWorkloads(phase.workloads, workloadLimits)
            val transition = transitionForDuration(
                transition = phase.transition,
                originalDurationMs = phase.durationMs,
                effectiveDurationMs = durationMs,
            )
            val includeGlLayer =
                phase.includeGlLayer &&
                    phase.backend != LayerBackend.FLATTENED_TEXTURE &&
                    !(phase.activeLayers > 1 && budgetCapped == 1)
            if (phase.hwcCompositionExpectation != HwcCompositionExpectation.NONE) {
                val changedContractField = when {
                    budgetCapped != phase.activeLayers -> "layer topology"
                    producerFps != phase.producerFps -> "producer FPS"
                    displayHz != phase.requestedDisplayHz -> "display pacing"
                    includeGlLayer != phase.includeGlLayer -> "GL producer topology"
                    workloads.gpu != phase.workloads.gpu -> "GPU/GL pressure"
                    else -> null
                }
                if (changedContractField != null) {
                    return rejected(
                        "Phase '${phase.id}' HWC composition expectation cannot preserve its " +
                            "$changedContractField under the active safety limits",
                    )
                }
            }
            if (
                phase.includeGlLayer &&
                !includeGlLayer &&
                phase.backend != LayerBackend.FLATTENED_TEXTURE &&
                workloads.gpu > 0f
            ) {
                return rejected(
                    "Phase '${phase.id}' cannot fit the GL producer required by its GPU load",
                )
            }

            if (budgetCapped != phase.activeLayers) {
                adjustments +=
                    "Phase '${phase.id}': layers ${phase.activeLayers} -> $budgetCapped"
            }
            if (producerFps != phase.producerFps) {
                adjustments +=
                    "Phase '${phase.id}': producer FPS ${phase.producerFps} -> $producerFps"
            }
            if (displayHz != phase.requestedDisplayHz) {
                adjustments +=
                    "Phase '${phase.id}': display Hz ${phase.requestedDisplayHz} -> $displayHz"
            }
            if (durationMs != phase.durationMs) {
                adjustments +=
                    "Phase '${phase.id}': duration ${phase.durationMs} ms -> $durationMs ms"
            }
            if (workloads != phase.workloads) {
                adjustments += "Phase '${phase.id}': workloads capped to configured limits"
            }
            if (transition != phase.transition) {
                adjustments +=
                    "Phase '${phase.id}': transition parameters bounded for ${durationMs} ms"
            }
            if (includeGlLayer != phase.includeGlLayer) {
                adjustments += if (phase.backend == LayerBackend.FLATTENED_TEXTURE) {
                    "Phase '${phase.id}': ignored GL-tail marker removed; the flattened " +
                        "producer carries GPU work"
                } else {
                    "Phase '${phase.id}': GL tail removed to preserve the primary producer"
                }
            }

            boundedPhases += phase.copy(
                durationMs = durationMs,
                activeLayers = budgetCapped,
                producerFps = producerFps,
                requestedDisplayHz = displayHz,
                workloads = workloads,
                transition = transition,
                includeGlLayer = includeGlLayer,
            )
        }

        val durationBoundedPhases = if (
            totalDurationExceeds(boundedPhases, limits.maxScenarioDurationMs)
        ) {
            adjustments +=
                "Scenario duration capped to ${limits.maxScenarioDurationMs} ms"
            capTotalDuration(boundedPhases, limits.maxScenarioDurationMs)
        } else {
            boundedPhases
        }
        validateEffectiveTransitions(durationBoundedPhases)?.let { return rejected(it) }

        return ScenarioSafetyDecision(
            effectiveScenario = scenario.copy(phases = durationBoundedPhases),
            adjustments = adjustments,
            rejectionReason = null,
        )
    }

    private fun validateLimits(limits: RenderSafetyLimits): String? = when {
        limits.displayWidthPx <= 0 || limits.displayHeightPx <= 0 ->
            "Display dimensions must be positive"
        limits.maxLayers <= 0 ->
            "Layer limit must be positive"
        !limits.maxProducerFps.isFinite() || limits.maxProducerFps <= 0f ->
            "Producer FPS limit must be finite and positive"
        limits.maxPhaseDurationMs <= 0L ->
            "Phase duration limit must be positive"
        limits.maxScenarioDurationMs <= 0L ->
            "Scenario duration limit must be positive"
        limits.maxGraphicsBytes <= 0L ->
            "Graphics memory budget must be positive"
        listOf(
            limits.maxCpuLoad,
            limits.maxMemoryLoad,
            limits.maxGpuLoad,
            limits.maxNpuLoad,
        ).any { !it.isFinite() || it < 0f } ->
            "Workload limits must be finite and non-negative"
        else -> null
    }

    private fun validateScenario(scenario: ScenarioSpec): String? {
        if (scenario.id.isBlank()) return "Scenario ID must not be blank"
        if (scenario.id.length > MAX_ID_CHARS) return "Scenario ID is too long"
        if (scenario.name.isBlank() || scenario.name.length > MAX_LABEL_CHARS) {
            return "Scenario name must be non-blank and at most $MAX_LABEL_CHARS characters"
        }
        if (scenario.description.length > MAX_DESCRIPTION_CHARS) {
            return "Scenario description is too long"
        }
        if (
            scenario.tags.size > MAX_METADATA_ITEMS ||
            scenario.requirements.size > MAX_METADATA_ITEMS ||
            (scenario.tags + scenario.requirements).any {
                it.isBlank() || it.length > MAX_LABEL_CHARS
            }
        ) {
            return "Scenario tags/requirements are invalid or excessive"
        }
        if (scenario.phases.isEmpty()) return "Scenario must contain at least one phase"
        if (scenario.phases.size > MAX_PHASE_COUNT) {
            return "Scenario has too many phases (maximum $MAX_PHASE_COUNT)"
        }

        val phaseIds = HashSet<String>(scenario.phases.size)
        for (phase in scenario.phases) {
            val normalizedId = phase.id.trim()
            if (normalizedId.isEmpty()) return "Phase ID must not be blank"
            if (phase.id.length > MAX_ID_CHARS) return "Phase ID is too long"
            if (phase.label.isBlank() || phase.label.length > MAX_LABEL_CHARS) {
                return "Phase '${phase.id}' label is invalid or too long"
            }
            if (!phaseIds.add(normalizedId)) {
                return "Duplicate phase ID '$normalizedId'"
            }
            if (phase.durationMs <= 0L) {
                return "Phase '${phase.id}' duration must be positive"
            }
            if (phase.activeLayers <= 0) {
                return "Phase '${phase.id}' layer count must be positive"
            }
            if (
                phase.backend == LayerBackend.FLATTENED_TEXTURE &&
                (
                    phase.pixelRoute != PixelRoute.RGB_8888 ||
                        phase.bufferSize != BufferSize.DISPLAY
                    )
            ) {
                return "Phase '${phase.id}' flattened backend is a display-sized RGB_8888 " +
                    "producer and cannot claim a decoder or explicit buffer size"
            }
            if (
                phase.backend != LayerBackend.FLATTENED_TEXTURE &&
                phase.activeLayers == 1 &&
                phase.includeGlLayer &&
                (
                    phase.pixelRoute.usesSelectedMediaDecoder() ||
                        phase.bufferSize != BufferSize.DISPLAY
                    )
            ) {
                return "Phase '${phase.id}' needs at least two layers to keep its " +
                    "dedicated primary and GL tail"
            }
            if (!phase.producerFps.isFinite() || phase.producerFps <= 0f) {
                return "Phase '${phase.id}' producer FPS must be finite and positive"
            }
            if (!phase.requestedDisplayHz.isFinite() || phase.requestedDisplayHz <= 0f) {
                return "Phase '${phase.id}' display Hz must be finite and positive"
            }
            val loadValues = with(phase.workloads) {
                listOf(cpu, memory, gpu, npu)
            }
            if (loadValues.any { !it.isFinite() }) {
                return "Phase '${phase.id}' workloads must be finite"
            }
            if (loadValues.any { it < 0f }) {
                return "Phase '${phase.id}' workloads must be non-negative"
            }
            if (loadValues.any { it > 0f && it <= MIN_EFFECTIVE_LOAD }) {
                return "Phase '${phase.id}' positive workloads must exceed " +
                    "$MIN_EFFECTIVE_LOAD to produce observable work"
            }
            if (
                phase.workloads.gpu > 0f &&
                phase.backend != LayerBackend.FLATTENED_TEXTURE &&
                !phase.includeGlLayer
            ) {
                return "Phase '${phase.id}' GPU load requires a flattened producer or GL layer"
            }
            with(phase.transition) {
                if (transitionDurationMs < 0L) {
                    return "Phase '${phase.id}' transition duration must be non-negative"
                }
                if (cycleMs <= 0L) {
                    return "Phase '${phase.id}' transition cycle must be positive"
                }
                if (stepCount <= 0) {
                    return "Phase '${phase.id}' transition step count must be positive"
                }
                if (!dutyCycle.isFinite() || !floor.isFinite()) {
                    return "Phase '${phase.id}' transition values must be finite"
                }
                val boundedFloor = floor.coerceIn(0f, 1f)
                val cyclic =
                    mode == TransitionMode.PULSE_BURST ||
                        mode == TransitionMode.TRIANGLE_WAVE
                if (!cyclic && boundedFloor != 0f) {
                    return "Phase '${phase.id}' transition floor is supported only for cyclic modes"
                }
                if (cyclic && boundedFloor >= 1f) {
                    return "Phase '${phase.id}' transition floor leaves no dynamic range"
                }
            }
            if (
                phase.hwcCompositionExpectation != HwcCompositionExpectation.NONE &&
                phase.transition.mode != TransitionMode.STEP
            ) {
                return "Phase '${phase.id}' HWC composition expectation requires a stable " +
                    "STEP target for fresh DEVICE/CLIENT evidence"
            }
            if (
                phase.hwcCompositionExpectation != HwcCompositionExpectation.NONE &&
                phase.layerSizeProfile.changesOverTime
            ) {
                return "Phase '${phase.id}' HWC composition expectation requires a stable " +
                    "layer-size profile for fresh DEVICE/CLIENT evidence"
            }
        }
        return null
    }

    private fun capWorkloads(
        workloads: LoadSetpoints,
        limits: LoadSetpoints,
    ): LoadSetpoints = workloads.copy(
        cpu = workloads.cpu.coerceIn(0f, limits.cpu),
        memory = workloads.memory.coerceIn(0f, limits.memory),
        gpu = workloads.gpu.coerceIn(0f, limits.gpu),
        npu = workloads.npu.coerceIn(0f, limits.npu),
    ).normalizedForExecution()

    private fun maximumLayersWithinGraphicsBudget(
        phase: PhaseSpec,
        requestedLayers: Int,
        limits: RenderSafetyLimits,
        selectedDecoderBuffer: SelectedDecoderBuffer?,
    ): Int {
        if (phase.backend == LayerBackend.FLATTENED_TEXTURE) {
            return if (
                productWithinBudget(
                    budget = limits.maxGraphicsBytes,
                    limits.displayWidthPx.toLong(),
                    limits.displayHeightPx.toLong(),
                    RGBA_BYTES_PER_PIXEL,
                    BUFFER_COUNT,
                ) != null
            ) {
                requestedLayers
            } else {
                0
            }
        }

        for (candidate in requestedLayers downTo 1) {
            if (
                independentLayersFit(
                    phase = phase,
                    layerCount = candidate,
                    limits = limits,
                    selectedDecoderBuffer = selectedDecoderBuffer,
                )
            ) {
                return candidate
            }
        }
        return 0
    }

    private fun independentLayersFit(
        phase: PhaseSpec,
        layerCount: Int,
        limits: RenderSafetyLimits,
        selectedDecoderBuffer: SelectedDecoderBuffer?,
    ): Boolean {
        // A multi-layer phase with a GL tail must not silently turn into a different GL-only
        // experiment when safety clamps it to one layer. Its primary producer is preserved and
        // the tail is removed in the effective phase.
        val primaryIsGl = phase.includeGlLayer && phase.activeLayers == 1
        val primaryUsesDecoder =
            selectedDecoderBuffer != null &&
                !primaryIsGl &&
                phase.pixelRoute.usesSelectedMediaDecoder()
        val primaryWidth = when {
            primaryIsGl -> limits.displayWidthPx
            primaryUsesDecoder ->
                selectedDecoderBuffer?.widthPx?.takeIf { it > 0 } ?: return false
            else -> phase.bufferSize.width.takeIf { it > 0 } ?: limits.displayWidthPx
        }
        val primaryHeight = when {
            primaryIsGl -> limits.displayHeightPx
            primaryUsesDecoder ->
                selectedDecoderBuffer?.heightPx?.takeIf { it > 0 } ?: return false
            else -> phase.bufferSize.height.takeIf { it > 0 } ?: limits.displayHeightPx
        }
        val primaryBytes = productWithinBudget(
            budget = limits.maxGraphicsBytes,
            primaryWidth.toLong(),
            primaryHeight.toLong(),
            RGBA_BYTES_PER_PIXEL,
            BUFFER_COUNT,
        ) ?: return false
        val primaryDepthBytes = if (primaryIsGl) {
            productWithinBudget(
                budget = limits.maxGraphicsBytes - primaryBytes,
                primaryWidth.toLong(),
                primaryHeight.toLong(),
                CONSERVATIVE_GL_DEPTH_BYTES_PER_PIXEL,
                BUFFER_COUNT,
            ) ?: return false
        } else {
            0L
        }

        val overlayCount = layerCount - 1L
        if (overlayCount == 0L) return true
        val remainingBudget = limits.maxGraphicsBytes - primaryBytes - primaryDepthBytes
        val overlayBytes = productWithinBudget(
            budget = remainingBudget,
            limits.displayWidthPx.toLong(),
            limits.displayHeightPx.toLong(),
            RGBA_BYTES_PER_PIXEL,
            BUFFER_COUNT,
            overlayCount,
        ) ?: return false
        val glTailPresent =
            phase.includeGlLayer &&
                phase.activeLayers > 1 &&
                layerCount > 1
        if (!glTailPresent) return true
        return productWithinBudget(
            budget = remainingBudget - overlayBytes,
            limits.displayWidthPx.toLong(),
            limits.displayHeightPx.toLong(),
            CONSERVATIVE_GL_DEPTH_BYTES_PER_PIXEL,
            BUFFER_COUNT,
        ) != null
    }

    /**
     * Multiplies only while the exact result is no larger than [budget].
     * Returning null on the division pre-check makes overflow indistinguishable
     * from an over-budget result, which is the safe outcome for both cases.
     */
    private fun productWithinBudget(
        budget: Long,
        vararg factors: Long,
    ): Long? {
        if (budget < 0L || factors.any { it < 0L }) return null
        var product = 1L
        for (factor in factors) {
            if (factor == 0L) return 0L
            if (product > budget / factor) return null
            product *= factor
        }
        return product
    }

    private fun totalDurationExceeds(
        phases: List<PhaseSpec>,
        capMs: Long,
    ): Boolean {
        var remaining = capMs
        for (phase in phases) {
            if (phase.durationMs > remaining) return true
            remaining -= phase.durationMs
        }
        return false
    }

    private fun capTotalDuration(
        phases: List<PhaseSpec>,
        capMs: Long,
    ): List<PhaseSpec> {
        val phaseCount = phases.size
        val distributableMs = capMs - phaseCount.toLong()
        if (distributableMs == 0L) {
            return phases.map { phase ->
                phase.copy(
                    durationMs = 1L,
                    transition = transitionForDuration(
                        transition = phase.transition,
                        originalDurationMs = phase.durationMs,
                        effectiveDurationMs = 1L,
                    ),
                )
            }
        }

        // Reserve 1 ms for every phase, then divide the remaining budget in proportion to
        // each phase's remaining duration. BigInteger keeps hostile Long.MAX_VALUE inputs from
        // overflowing and the largest-remainder pass makes the final sum exactly capMs.
        val weights = phases.map { BigInteger.valueOf(it.durationMs - 1L) }
        val totalWeight = weights.fold(BigInteger.ZERO, BigInteger::add)
        check(totalWeight.signum() > 0)
        val distributable = BigInteger.valueOf(distributableMs)
        val extras = LongArray(phaseCount)
        val remainders = Array(phaseCount) { BigInteger.ZERO }
        var allocatedMs = phaseCount.toLong()
        weights.forEachIndexed { index, weight ->
            val division = weight.multiply(distributable).divideAndRemainder(totalWeight)
            extras[index] = checkedNonNegativeLong(division[0])
            remainders[index] = division[1]
            allocatedMs += extras[index]
        }
        var leftoverMs = capMs - allocatedMs
        val remainderOrder = phases.indices.sortedWith(
            compareByDescending<Int> { remainders[it] }.thenBy { it },
        )
        var remainderIndex = 0
        while (leftoverMs > 0L) {
            extras[remainderOrder[remainderIndex]]++
            leftoverMs--
            remainderIndex++
        }

        return phases.mapIndexed { index, phase ->
            val durationMs = 1L + extras[index]
            phase.copy(
                durationMs = durationMs,
                transition = transitionForDuration(
                    transition = phase.transition,
                    originalDurationMs = phase.durationMs,
                    effectiveDurationMs = durationMs,
                ),
            )
        }
    }

    /**
     * Shrinks transition timing with its containing phase.
     *
     * Simply clipping an 8 s ramp to a phase reduced from 10 s to 5 s turns the ramp into a
     * phase-wide transition and removes the intended target plateau. Proportional scaling keeps
     * the attack/hold/release shape and cyclic repetition count as close as the safety cap allows.
     */
    private fun transitionForDuration(
        transition: TransitionSpec,
        originalDurationMs: Long,
        effectiveDurationMs: Long,
    ): TransitionSpec {
        val original = transition.boundedFor(originalDurationMs)
        val durationReduced = effectiveDurationMs < originalDurationMs
        val scalesTransitionWindow = when (original.mode) {
            TransitionMode.LINEAR_RAMP,
            TransitionMode.STAIRCASE,
            TransitionMode.SOAK_RECOVERY,
            -> true

            TransitionMode.STEP,
            TransitionMode.PULSE_BURST,
            TransitionMode.TRIANGLE_WAVE,
            -> false
        }
        val scaledTransitionDuration = if (
            durationReduced &&
            original.transitionDurationMs > 0L &&
            scalesTransitionWindow
        ) {
            scalePositiveDuration(
                value = original.transitionDurationMs,
                originalDurationMs = originalDurationMs,
                effectiveDurationMs = effectiveDurationMs,
            )
        } else {
            original.transitionDurationMs
        }
        val scalesCycle = when (original.mode) {
            TransitionMode.PULSE_BURST,
            TransitionMode.TRIANGLE_WAVE,
            -> true

            TransitionMode.STEP,
            TransitionMode.LINEAR_RAMP,
            TransitionMode.STAIRCASE,
            TransitionMode.SOAK_RECOVERY,
            -> false
        }
        val scaledCycleMs = if (durationReduced && scalesCycle) {
            scalePositiveDuration(
                value = original.cycleMs,
                originalDurationMs = originalDurationMs,
                effectiveDurationMs = effectiveDurationMs,
            )
        } else {
            original.cycleMs
        }
        var effective = original.copy(
            transitionDurationMs = scaledTransitionDuration,
            cycleMs = scaledCycleMs,
        ).boundedFor(effectiveDurationMs)

        if (
            effective.mode == TransitionMode.SOAK_RECOVERY &&
            effective.transitionDurationMs > 0L &&
            effectiveDurationMs > LOAD_CONTROL_CADENCE_MS
        ) {
            // Keep at least one full controller tick between attack and release. The evaluator
            // still handles malformed direct calls, while safety-vetted scenarios retain an
            // observable high-load plateau.
            val maximumEdgeMs =
                (effectiveDurationMs - LOAD_CONTROL_CADENCE_MS) / 2L
            effective = effective.copy(
                transitionDurationMs = minOf(
                    effective.transitionDurationMs,
                    maximumEdgeMs,
                ),
            )
        }
        return effective
    }

    private fun scalePositiveDuration(
        value: Long,
        originalDurationMs: Long,
        effectiveDurationMs: Long,
    ): Long {
        if (value <= 0L) return 0L
        return BigInteger.valueOf(value)
            .multiply(BigInteger.valueOf(effectiveDurationMs))
            .divide(BigInteger.valueOf(originalDurationMs))
            .let(::checkedNonNegativeLong)
            .coerceAtLeast(1L)
    }

    private fun checkedNonNegativeLong(value: BigInteger): Long {
        check(value.signum() >= 0 && value <= LONG_MAX_BIG_INTEGER) {
            "Duration allocation escaped the non-negative Long range"
        }
        return value.toLong()
    }

    private fun validateEffectiveTransitions(phases: List<PhaseSpec>): String? {
        for ((index, phase) in phases.withIndex()) {
            val previous = phases.getOrNull(index - 1)
            val minimumHwcDurationMs =
                minimumHwcExpectationPhaseDurationMs(phase.hwcCompositionExpectation)
            if (phase.durationMs < minimumHwcDurationMs) {
                return "Phase '${phase.id}' is too short for " +
                    "${phase.hwcCompositionExpectation.name} bounded fresh HWC evidence and " +
                    "a post-target observation window (minimum ${minimumHwcDurationMs}ms)"
            }
            val minimumLayerSizeProfileDurationMs = when (phase.layerSizeProfile) {
                LayerSizeProfile.GRADUAL_SMALL_TO_FULL -> LOAD_CONTROL_CADENCE_MS * 2L
                LayerSizeProfile.ABRUPT_SMALL_FULL ->
                    LOAD_CONTROL_CADENCE_MS * ABRUPT_LAYER_SIZE_PROFILE_STEPS.toLong()

                LayerSizeProfile.FULL_SCREEN,
                LayerSizeProfile.SMALL_UNIFORM,
                LayerSizeProfile.MIXED_SIZES,
                -> 0L
            }
            if (phase.durationMs < minimumLayerSizeProfileDurationMs) {
                return "Phase '${phase.id}' is too short to preserve the " +
                    "${phase.layerSizeProfile.name} layer-size waveform at the bounded " +
                    "observation cadence (minimum ${minimumLayerSizeProfileDurationMs}ms)"
            }
            val cyclic =
                phase.transition.mode == TransitionMode.PULSE_BURST ||
                    phase.transition.mode == TransitionMode.TRIANGLE_WAVE
            if (
                previous != null &&
                cyclic &&
                rendererTopologyDiffers(previous, phase)
            ) {
                return "Phase '${phase.id}' cyclic transition cannot alternate physical " +
                    "producer topologies"
            }
            if (
                previous != null &&
                phase.transition.mode != TransitionMode.STEP &&
                previous.workloads.gpu > 0f &&
                !phase.hasGpuLoadProducer()
            ) {
                return "Phase '${phase.id}' gradual transition cannot remove the physical GPU " +
                    "load producer; use STEP or retain a GL/flattened producer"
            }
            when (phase.transition.mode) {
                TransitionMode.PULSE_BURST -> {
                    if (phase.durationMs < phase.transition.cycleMs) {
                        return "Phase '${phase.id}' is too short for one bounded transition cycle"
                    }
                    val cycleMs = phase.transition.cycleMs.toDouble()
                    val onWindowMs = cycleMs * phase.transition.dutyCycle.toDouble()
                    val offWindowMs =
                        cycleMs * (1.0 - phase.transition.dutyCycle.toDouble())
                    if (
                        !onWindowMs.isFinite() ||
                        !offWindowMs.isFinite() ||
                        onWindowMs < LOAD_CONTROL_CADENCE_MS.toDouble() ||
                        offWindowMs < LOAD_CONTROL_CADENCE_MS.toDouble()
                    ) {
                        return "Phase '${phase.id}' pulse ON/OFF windows are shorter than the " +
                            "control cadence"
                    }
                }

                TransitionMode.TRIANGLE_WAVE -> if (
                    phase.durationMs < phase.transition.cycleMs
                ) {
                    return "Phase '${phase.id}' is too short for one bounded transition cycle"
                }

                TransitionMode.SOAK_RECOVERY -> {
                    val edgeMs = phase.transition.transitionDurationMs
                        .takeIf { it > 0L }
                        ?: (phase.durationMs / 5L).coerceAtLeast(1L)
                    val holdMs = (phase.durationMs - edgeMs - edgeMs).coerceAtLeast(0L)
                    if (
                        edgeMs < LOAD_CONTROL_CADENCE_MS * 2L ||
                        holdMs < LOAD_CONTROL_CADENCE_MS
                    ) {
                        return "Phase '${phase.id}' has no observable gradual attack, hold, " +
                            "and recovery windows at the control cadence"
                    }
                }

                TransitionMode.STEP -> if (
                    phase.durationMs < LOAD_CONTROL_CADENCE_MS * 2L
                ) {
                    return "Phase '${phase.id}' is too short to apply its target after baseline"
                }

                TransitionMode.LINEAR_RAMP -> {
                    val windowMs = phase.transition.transitionDurationMs
                        .takeIf { it > 0L }
                        ?: phase.durationMs
                    if (windowMs < LOAD_CONTROL_CADENCE_MS * 2L) {
                        return "Phase '${phase.id}' ramp window is too short for an observable " +
                            "intermediate control tick"
                    }
                }

                TransitionMode.STAIRCASE -> {
                    val windowMs = phase.transition.transitionDurationMs
                        .takeIf { it > 0L }
                        ?: phase.durationMs
                    val requiredDurationMs =
                        phase.transition.stepCount.toLong() * LOAD_CONTROL_CADENCE_MS
                    if (windowMs < requiredDurationMs) {
                        return "Phase '${phase.id}' staircase window is too short to observe " +
                            "${phase.transition.stepCount} staircase levels"
                    }
                }
            }
        }
        return null
    }

    private fun rendererTopologyDiffers(first: PhaseSpec, second: PhaseSpec): Boolean =
        first.activeLayers != second.activeLayers ||
            first.backend != second.backend ||
            first.pixelRoute != second.pixelRoute ||
            first.bufferSize != second.bufferSize ||
            first.includeGlLayer != second.includeGlLayer ||
            first.alphaOverlap != second.alphaOverlap

    private fun rejected(reason: String) = ScenarioSafetyDecision(
        effectiveScenario = null,
        adjustments = emptyList(),
        rejectionReason = reason,
    )

    private val LONG_MAX_BIG_INTEGER: BigInteger = BigInteger.valueOf(Long.MAX_VALUE)
}
