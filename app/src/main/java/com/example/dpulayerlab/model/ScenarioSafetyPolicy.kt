package com.example.dpulayerlab.model

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
)

data class ScenarioSafetyDecision(
    val effectiveScenario: ScenarioSpec?,
    val adjustments: List<String>,
    val rejectionReason: String?,
)

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

    private const val RGBA_BYTES_PER_PIXEL = 4L
    private const val BUFFER_COUNT = 3L
    private const val MAX_NORMALIZED_LOAD = 1f

    fun evaluate(
        scenario: ScenarioSpec,
        limits: RenderSafetyLimits,
    ): ScenarioSafetyDecision {
        validateLimits(limits)?.let { return rejected(it) }
        validateScenario(scenario)?.let { return rejected(it) }

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

            boundedPhases += phase.copy(
                durationMs = durationMs,
                activeLayers = budgetCapped,
                producerFps = producerFps,
                requestedDisplayHz = displayHz,
                workloads = workloads,
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
    )

    private fun maximumLayersWithinGraphicsBudget(
        phase: PhaseSpec,
        requestedLayers: Int,
        limits: RenderSafetyLimits,
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
            if (independentLayersFit(phase, candidate, limits)) return candidate
        }
        return 0
    }

    private fun independentLayersFit(
        phase: PhaseSpec,
        layerCount: Int,
        limits: RenderSafetyLimits,
    ): Boolean {
        val primaryIsGl = phase.includeGlLayer && layerCount == 1
        val primaryWidth = if (primaryIsGl) {
            limits.displayWidthPx
        } else {
            phase.bufferSize.width.takeIf { it > 0 } ?: limits.displayWidthPx
        }
        val primaryHeight = if (primaryIsGl) {
            limits.displayHeightPx
        } else {
            phase.bufferSize.height.takeIf { it > 0 } ?: limits.displayHeightPx
        }
        val primaryBytes = productWithinBudget(
            budget = limits.maxGraphicsBytes,
            primaryWidth.toLong(),
            primaryHeight.toLong(),
            RGBA_BYTES_PER_PIXEL,
            BUFFER_COUNT,
        ) ?: return false

        val overlayCount = layerCount - 1L
        if (overlayCount == 0L) return true
        val remainingBudget = limits.maxGraphicsBytes - primaryBytes
        return productWithinBudget(
            budget = remainingBudget,
            limits.displayWidthPx.toLong(),
            limits.displayHeightPx.toLong(),
            RGBA_BYTES_PER_PIXEL,
            BUFFER_COUNT,
            overlayCount,
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
        var remaining = capMs
        return phases.mapIndexed { index, phase ->
            val phasesAfterThis = phases.size - index - 1L
            val maximumForThisPhase = remaining - phasesAfterThis
            val duration = minOf(phase.durationMs, maximumForThisPhase)
            remaining -= duration
            phase.copy(durationMs = duration)
        }
    }

    private fun rejected(reason: String) = ScenarioSafetyDecision(
        effectiveScenario = null,
        adjustments = emptyList(),
        rejectionReason = reason,
    )
}
