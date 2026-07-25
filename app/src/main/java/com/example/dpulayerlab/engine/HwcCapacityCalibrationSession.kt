package com.example.dpulayerlab.engine

/**
 * Display envelope for the process-session HWC capacity observation.
 *
 * Axis order is normalized so an orientation-only width/height swap keeps the same scope. The
 * observation is deliberately process-local: persisting it would make old boot/vendor/session
 * evidence look current after the hardware environment has changed.
 */
internal data class HwcCapacityCalibrationScope(
    val displayId: Int?,
    val shortEdgePx: Int,
    val longEdgePx: Int,
) {
    init {
        require(shortEdgePx > 0)
        require(longEdgePx >= shortEdgePx)
    }

    fun label(): String =
        "display=${displayId ?: "unknown"}; size=${shortEdgePx}x$longEdgePx"

    companion object {
        fun normalized(
            displayId: Int?,
            widthPx: Int,
            heightPx: Int,
        ): HwcCapacityCalibrationScope {
            val safeWidth = widthPx.coerceAtLeast(1)
            val safeHeight = heightPx.coerceAtLeast(1)
            return HwcCapacityCalibrationScope(
                displayId = displayId,
                shortEdgePx = minOf(safeWidth, safeHeight),
                longEdgePx = maxOf(safeWidth, safeHeight),
            )
        }
    }
}

internal data class HwcCapacityCalibrationClaim internal constructor(
    val scope: HwcCapacityCalibrationScope,
    internal val token: Long,
)

internal sealed interface HwcCapacityCalibrationAcquisition {
    data class Measure(
        val claim: HwcCapacityCalibrationClaim,
    ) : HwcCapacityCalibrationAcquisition

    data class Reuse(
        val result: HwcCapacityCalibrationResult,
    ) : HwcCapacityCalibrationAcquisition

    data class Busy(
        val activeScope: HwcCapacityCalibrationScope,
    ) : HwcCapacityCalibrationAcquisition
}

/**
 * A single-slot, in-memory session store.
 *
 * Only one attempt is retained for the life of the process. Moving to another physical display
 * projects the result as UNAVAILABLE rather than either reusing stale evidence or generating a
 * second 20-layer burst. A process restart is the explicit boundary for a new attempt. A claim
 * token prevents a cancelled/old controller from publishing over a newer owner.
 */
internal class HwcCapacityCalibrationSessionStore {
    private var currentScope: HwcCapacityCalibrationScope? = null
    private var completedResult: HwcCapacityCalibrationResult? = null
    private var activeClaim: HwcCapacityCalibrationClaim? = null
    private var activeCandidateLayers: Int? = null
    private var nextToken = 0L

    @Synchronized
    fun snapshot(scope: HwcCapacityCalibrationScope): HwcCapacityCalibrationResult? =
        completedResult?.let { result -> projectResult(scope, result) }

    @Synchronized
    fun acquire(scope: HwcCapacityCalibrationScope): HwcCapacityCalibrationAcquisition {
        val running = activeClaim
        if (running != null) {
            return HwcCapacityCalibrationAcquisition.Busy(running.scope)
        }
        completedResult?.let { result ->
            return HwcCapacityCalibrationAcquisition.Reuse(projectResult(scope, result))
        }
        if (currentScope == null) {
            currentScope = scope
        } else if (currentScope != scope) {
            val unavailable = scopeChangedResult(
                calibratedScope = checkNotNull(currentScope),
                requestedScope = scope,
                candidateLayers = null,
            )
            completedResult = unavailable
            return HwcCapacityCalibrationAcquisition.Reuse(unavailable)
        }
        nextToken = nextSessionCalibrationToken(nextToken)
        return HwcCapacityCalibrationAcquisition.Measure(
            HwcCapacityCalibrationClaim(
                scope = scope,
                token = nextToken,
            ).also {
                activeClaim = it
                activeCandidateLayers = null
            },
        )
    }

    /**
     * Records the safety-approved topology before any physical producer is started. Keeping it
     * beside the claim lets cancellation report the actual attempted topology instead of
     * fabricating the requested 20-layer value.
     */
    @Synchronized
    fun recordCandidate(
        claim: HwcCapacityCalibrationClaim,
        candidateLayers: Int,
    ): Boolean {
        require(candidateLayers in 1..HWC_CAPACITY_CALIBRATION_REQUESTED_LAYERS)
        if (activeClaim != claim || currentScope != claim.scope) return false
        val existing = activeCandidateLayers
        if (existing != null) return existing == candidateLayers
        activeCandidateLayers = candidateLayers
        return true
    }

    /**
     * Publishes only a terminal attempt from the current owner. PENDING is controller/UI state and
     * must never become a reusable process-session result.
     */
    @Synchronized
    fun complete(
        claim: HwcCapacityCalibrationClaim,
        result: HwcCapacityCalibrationResult,
    ): Boolean {
        require(result.status != HwcCapacityCalibrationStatus.PENDING)
        if (activeClaim != claim || currentScope != claim.scope) return false
        val candidate = activeCandidateLayers
        require(
            result.status != HwcCapacityCalibrationStatus.OBSERVED_AT_CANDIDATE ||
                candidate != null,
        ) {
            "An observed calibration result requires a recorded physical candidate"
        }
        completedResult = result.copy(
            candidateLayers = candidate,
            calibrationDisplayId = claim.scope.displayId,
            calibrationDisplayShortEdgePx = claim.scope.shortEdgePx,
            calibrationDisplayLongEdgePx = claim.scope.longEdgePx,
        )
        activeClaim = null
        activeCandidateLayers = null
        return true
    }

    /**
     * Cancellation/failure becomes terminal UNAVAILABLE so a later START cannot repeat the
     * 20-layer burst. A stale owner cannot release a newer claim.
     */
    @Synchronized
    fun abandon(
        claim: HwcCapacityCalibrationClaim,
        reason: String,
    ): Boolean {
        if (activeClaim != claim) return false
        completedResult = HwcCapacityCalibrationResult(
            status = HwcCapacityCalibrationStatus.UNAVAILABLE,
            candidateLayers = activeCandidateLayers,
            calibrationDisplayId = claim.scope.displayId,
            calibrationDisplayShortEdgePx = claim.scope.shortEdgePx,
            calibrationDisplayLongEdgePx = claim.scope.longEdgePx,
            detail = "process-session one-shot ended before a reusable result: " +
                reason.ifBlank { "cancelled" },
        )
        activeClaim = null
        activeCandidateLayers = null
        return true
    }

    private fun projectResult(
        requestedScope: HwcCapacityCalibrationScope,
        result: HwcCapacityCalibrationResult,
    ): HwcCapacityCalibrationResult {
        val calibratedScope = currentScope ?: return result
        return if (calibratedScope == requestedScope) {
            result
        } else {
            scopeChangedResult(
                calibratedScope = calibratedScope,
                requestedScope = requestedScope,
                candidateLayers = result.candidateLayers,
            )
        }
    }
}

internal object ProcessHwcCapacityCalibrationSession {
    private val store = HwcCapacityCalibrationSessionStore()

    fun snapshot(scope: HwcCapacityCalibrationScope): HwcCapacityCalibrationResult? =
        store.snapshot(scope)

    fun acquire(scope: HwcCapacityCalibrationScope): HwcCapacityCalibrationAcquisition =
        store.acquire(scope)

    fun recordCandidate(
        claim: HwcCapacityCalibrationClaim,
        candidateLayers: Int,
    ): Boolean = store.recordCandidate(claim, candidateLayers)

    fun complete(
        claim: HwcCapacityCalibrationClaim,
        result: HwcCapacityCalibrationResult,
    ): Boolean = store.complete(claim, result)

    fun abandon(
        claim: HwcCapacityCalibrationClaim,
        reason: String,
    ): Boolean = store.abandon(claim, reason)
}

internal fun nextSessionCalibrationToken(previous: Long): Long =
    if (previous <= 0L || previous == Long.MAX_VALUE) 1L else previous + 1L

private fun scopeChangedResult(
    calibratedScope: HwcCapacityCalibrationScope,
    requestedScope: HwcCapacityCalibrationScope,
    candidateLayers: Int?,
): HwcCapacityCalibrationResult =
    HwcCapacityCalibrationResult(
        status = HwcCapacityCalibrationStatus.UNAVAILABLE,
        candidateLayers = candidateLayers,
        calibrationDisplayId = calibratedScope.displayId,
        calibrationDisplayShortEdgePx = calibratedScope.shortEdgePx,
        calibrationDisplayLongEdgePx = calibratedScope.longEdgePx,
        detail =
            "display envelope changed after the process-session one-shot " +
                "(${calibratedScope.label()} -> ${requestedScope.label()}); " +
                "restart the app process to measure the new display",
    )
