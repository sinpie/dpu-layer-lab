package com.example.dpulayerlab.model

import kotlin.math.floor
import kotlin.math.roundToInt

internal const val LOAD_CONTROL_CADENCE_MS = 100L

enum class TransitionSegment(val label: String) {
    TARGET("Target"),
    RAMP_UP("Ramp up"),
    HOLD("Hold"),
    RAMP_DOWN("Ramp down"),
    BURST_ON("Burst on"),
    BURST_OFF("Burst off"),
    STEP_UP("Step up"),
}

data class TransitionSample(
    val fraction: Float,
    val segment: TransitionSegment,
)

/**
 * Pure, allocation-light transition math shared by the controller and load generators.
 *
 * [fraction] is always finite and in 0..1. It interpolates from the previously effective phase
 * to the new phase target; this guarantees that every intermediate layer/FPS/load value remains
 * inside the already validated endpoint envelope.
 */
object LoadTransitionEvaluator {
    fun sampleAt(
        spec: TransitionSpec,
        elapsedMs: Long,
        phaseDurationMs: Long,
    ): TransitionSample {
        if (phaseDurationMs <= 0L) {
            return TransitionSample(1f, TransitionSegment.TARGET)
        }
        val safe = spec.boundedFor(phaseDurationMs)
        val elapsed = elapsedMs.coerceIn(0L, phaseDurationMs)
        val raw = when (safe.mode) {
            // The zero-time sample is the preflight origin. The first active control tick then
            // performs the actual step inside the measured counter window.
            TransitionMode.STEP -> if (elapsed == 0L) {
                RawSample(0f, TransitionSegment.STEP_UP)
            } else {
                RawSample(1f, TransitionSegment.TARGET)
            }

            TransitionMode.LINEAR_RAMP -> {
                val window = safe.transitionDurationMs
                    .takeIf { it > 0L }
                    ?: phaseDurationMs
                val value = unitRatio(elapsed, window)
                RawSample(
                    value,
                    if (value < 1f) TransitionSegment.RAMP_UP else TransitionSegment.HOLD,
                )
            }

            TransitionMode.STAIRCASE -> {
                val window = safe.transitionDurationMs
                    .takeIf { it > 0L }
                    ?: phaseDurationMs
                val progress = unitRatio(elapsed, window)
                // stepCount includes both 0 and 1. The final level begins before the window
                // ends, so a phase ending exactly at the window cannot skip the target plateau.
                val lastLevel = safe.stepCount - 1
                val level = floor(progress * safe.stepCount)
                    .toInt()
                    .coerceIn(0, lastLevel)
                val value = level.toFloat() / lastLevel.toFloat()
                RawSample(
                    value,
                    if (value < 1f) TransitionSegment.STEP_UP else TransitionSegment.HOLD,
                )
            }

            TransitionMode.PULSE_BURST -> {
                val position = cyclePosition(elapsed, safe.cycleMs)
                if (position < safe.dutyCycle) {
                    RawSample(1f, TransitionSegment.BURST_ON)
                } else {
                    RawSample(0f, TransitionSegment.BURST_OFF)
                }
            }

            TransitionMode.TRIANGLE_WAVE -> {
                val position = cyclePosition(elapsed, safe.cycleMs)
                if (position < 0.5f) {
                    RawSample(position * 2f, TransitionSegment.RAMP_UP)
                } else {
                    RawSample((1f - position) * 2f, TransitionSegment.RAMP_DOWN)
                }
            }

            TransitionMode.SOAK_RECOVERY -> soakRecoverySample(
                elapsedMs = elapsed,
                phaseDurationMs = phaseDurationMs,
                requestedEdgeMs = safe.transitionDurationMs,
            )
        }
        // boundedFor() clears floors from one-shot transitions. Keep this explicit here as a
        // second fail-safe because STEP/linear/stair/soak must always preserve their zero origin.
        val floor = when (safe.mode) {
            TransitionMode.PULSE_BURST,
            TransitionMode.TRIANGLE_WAVE,
            -> safe.floor

            TransitionMode.STEP,
            TransitionMode.LINEAR_RAMP,
            TransitionMode.STAIRCASE,
            TransitionMode.SOAK_RECOVERY,
            -> 0f
        }
        val fraction = (floor + (1f - floor) * raw.fraction)
            .takeIf(Float::isFinite)
            ?.coerceIn(0f, 1f)
            ?: 0f
        return TransitionSample(fraction, raw.segment)
    }

    fun factorAt(
        spec: TransitionSpec,
        elapsedMs: Long,
        phaseDurationMs: Long,
    ): Float = sampleAt(spec, elapsedMs, phaseDurationMs).fraction

    fun interpolate(
        previous: LoadSetpoints,
        target: LoadSetpoints,
        fraction: Float,
    ): LoadSetpoints {
        val safeFraction = fraction
            .takeIf(Float::isFinite)
            ?.coerceIn(0f, 1f)
            ?: 0f
        return target.copy(
            cpu = lerp(previous.cpu, target.cpu, safeFraction),
            memory = lerp(previous.memory, target.memory, safeFraction),
            gpu = lerp(previous.gpu, target.gpu, safeFraction),
            npu = lerp(previous.npu, target.npu, safeFraction),
        ).normalizedForExecution()
    }

    fun interpolate(
        previous: PhaseSpec?,
        target: PhaseSpec,
        fraction: Float,
    ): PhaseSpec {
        if (previous == null) {
            return target.copy(activeLayers = target.activeLayers.coerceAtLeast(1))
        }
        val safeFraction = fraction
            .takeIf(Float::isFinite)
            ?.coerceIn(0f, 1f)
            ?: 0f
        val topologyMatches =
            previous.backend == target.backend &&
                previous.pixelRoute == target.pixelRoute &&
                previous.bufferSize == target.bufferSize &&
                previous.includeGlLayer == target.includeGlLayer &&
                previous.alphaOverlap == target.alphaOverlap
        if (safeFraction <= 0f) {
            // Preserve the complete, already safety-vetted origin topology until the measured
            // transition begins. Keeping only its layer count would create invalid combinations
            // such as a flattened backend with an explicit decoder buffer.
            return target.copy(
                activeLayers = previous.activeLayers.coerceAtLeast(1),
                producerFps = previous.producerFps,
                requestedDisplayHz = previous.requestedDisplayHz,
                backend = previous.backend,
                pixelRoute = previous.pixelRoute,
                bufferSize = previous.bufferSize,
                motion = previous.motion,
                layerSizeProfile = previous.layerSizeProfile,
                workloads = previous.workloads,
                alphaOverlap = previous.alphaOverlap,
                includeGlLayer = previous.includeGlLayer,
            )
        }
        val activeLayers = if (topologyMatches) {
            lerp(
                previous.activeLayers.toFloat(),
                target.activeLayers.toFloat(),
                safeFraction,
            ).roundToInt().coerceAtLeast(1)
        } else {
            target.activeLayers.coerceAtLeast(1)
        }
        val interpolatedWorkloads = interpolate(
            previous.workloads,
            target.workloads,
            safeFraction,
        ).let { workloads ->
            // Once the target topology is selected, never claim GPU work without a real GL or
            // flattened producer. Removing a GL tail therefore releases GPU load discretely
            // instead of silently dropping an interpolated positive setpoint.
            if (target.hasGpuLoadProducer()) workloads else workloads.copy(gpu = 0f)
        }
        return target.copy(
            // A different topology has only been memory-validated at the target layer count.
            // Reusing the previous count could transiently exceed the target graphics budget.
            activeLayers = activeLayers,
            producerFps = lerp(previous.producerFps, target.producerFps, safeFraction),
            requestedDisplayHz = lerp(
                previous.requestedDisplayHz,
                target.requestedDisplayHz,
                safeFraction,
            ),
            workloads = interpolatedWorkloads,
        )
    }

    private fun soakRecoverySample(
        elapsedMs: Long,
        phaseDurationMs: Long,
        requestedEdgeMs: Long,
    ): RawSample {
        if (phaseDurationMs < 2L) {
            return RawSample(1f, TransitionSegment.HOLD)
        }
        val maximumEdge = phaseDurationMs / 2L
        val edgeMs = (
            requestedEdgeMs.takeIf { it > 0L }
                ?: (phaseDurationMs / 5L).coerceAtLeast(1L)
            ).coerceIn(1L, maximumEdge)
        val releaseStartedMs = phaseDurationMs - edgeMs
        return when {
            elapsedMs < edgeMs ->
                RawSample(
                    unitRatio(elapsedMs, edgeMs),
                    TransitionSegment.RAMP_UP,
                )

            elapsedMs < releaseStartedMs ->
                RawSample(1f, TransitionSegment.HOLD)

            else ->
                RawSample(
                    1f - unitRatio(elapsedMs - releaseStartedMs, edgeMs),
                    TransitionSegment.RAMP_DOWN,
                )
        }
    }

    private fun cyclePosition(elapsedMs: Long, cycleMs: Long): Float =
        (elapsedMs % cycleMs).toDouble()
            .div(cycleMs.toDouble())
            .toFloat()
            .coerceIn(0f, 1f)

    private fun unitRatio(value: Long, total: Long): Float {
        if (total <= 0L) return 1f
        return (value.toDouble() / total.toDouble())
            .coerceIn(0.0, 1.0)
            .toFloat()
    }

    private fun lerp(start: Float, end: Float, fraction: Float): Float {
        val safeStart = start.takeIf(Float::isFinite) ?: 0f
        val safeEnd = end.takeIf(Float::isFinite) ?: 0f
        if (fraction <= 0f) return safeStart
        if (fraction >= 1f) return safeEnd
        // The conventional start + (end - start) * fraction overflows when two otherwise
        // finite endpoints have opposite, very large magnitudes. A weighted sum stays inside
        // the finite endpoint envelope for the validated 0..1 fraction.
        return safeStart * (1f - fraction) + safeEnd * fraction
    }

    private data class RawSample(
        val fraction: Float,
        val segment: TransitionSegment,
    )
}

internal fun PhaseSpec.hasGpuLoadProducer(): Boolean =
    backend == LayerBackend.FLATTENED_TEXTURE || includeGlLayer
