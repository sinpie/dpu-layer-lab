package com.example.dpulayerlab.model

/**
 * Allocation-free evaluator for the legacy per-generator waveform.
 *
 * Phase-to-phase transitions use [LoadTransitionEvaluator]. Keeping this evaluator separate
 * preserves the vendor-facing [LoadShape] ABI while ensuring CPU, memory and GPU generators use
 * exactly the same clock math.
 */
object LoadShapeEvaluator {
    fun intensityAt(base: Float, shape: LoadShape, elapsedMs: Long): Float {
        val safeBase = base
            .takeIf(Float::isFinite)
            ?.coerceIn(0f, 1f)
            ?: 0f
        if (safeBase <= 0f) return 0f
        return (safeBase * factorAt(shape, elapsedMs)).coerceIn(0f, 1f)
    }

    fun factorAt(shape: LoadShape, elapsedMs: Long): Float {
        val elapsed = elapsedMs.coerceAtLeast(0L)
        return when (shape) {
            LoadShape.STEADY -> 1f
            LoadShape.PULSE ->
                if (elapsed % PULSE_CYCLE_MS < PULSE_ON_MS) 1f else 0f
            LoadShape.RAMP ->
                (elapsed % RAMP_CYCLE_MS).toFloat() / RAMP_CYCLE_MS.toFloat()
            LoadShape.SAW -> {
                val position =
                    (elapsed % SAW_CYCLE_MS).toFloat() / SAW_CYCLE_MS.toFloat()
                if (position < 0.5f) position * 2f else (1f - position) * 2f
            }
        }
    }

    private const val PULSE_CYCLE_MS = 4_000L
    private const val PULSE_ON_MS = 2_000L
    private const val RAMP_CYCLE_MS = 6_000L
    private const val SAW_CYCLE_MS = 8_000L
}
