package com.example.dpulayerlab.engine

import com.example.dpulayerlab.nextIsolationToken

/**
 * Window-level isolation used while a test plan owns the display.
 *
 * A hide request is asynchronous on Android, so callers must wait for [isConfirmed] before
 * publishing a physical producer. The token prevents a late plan finalizer from restoring bars
 * owned by a newer plan.
 */
interface TestWindowIsolationPort {
    /**
     * Any returned token is owned by the caller until an acknowledged [ReleaseStatus.RESTORED].
     * [RequestResult.CleanupRequired] means that hiding failed after ownership was acquired, so
     * the caller must still drive the bounded restoration handshake.
     */
    fun request(): RequestResult

    fun isConfirmed(token: Long): Boolean

    /** Starts or retries restoration. A successful command remains [ReleaseStatus.PENDING]. */
    fun release(token: Long): ReleaseStatus

    /** Returns the latest acknowledged restoration state without issuing a Window command. */
    fun releaseStatus(token: Long): ReleaseStatus
}

sealed interface RequestResult {
    data class Acquired(val token: Long) : RequestResult

    data class CleanupRequired(val token: Long) : RequestResult

    data object Rejected : RequestResult
}

enum class ReleaseStatus {
    RESTORED,
    PENDING,
    FAILED,
    STALE,
}

internal enum class TestWindowIsolationPhase {
    IDLE,
    REQUESTED,
    CONFIRMED,
    CONTAMINATED,
    RESTORING,
    RESTORE_FAILED,
}

internal data class TestWindowIsolationState(
    val token: Long? = null,
    val phase: TestWindowIsolationPhase = TestWindowIsolationPhase.IDLE,
    val restoreStatusBarVisible: Boolean = true,
    val restoreNavigationBarVisible: Boolean = true,
)

internal data class TestWindowIsolationObservation(
    val state: TestWindowIsolationState,
    val contaminationDetected: Boolean,
    val restorationConfirmed: Boolean = false,
)

/**
 * Pure reducer shared by the Activity's Insets listener and local boundary tests.
 *
 * Visible bars are expected while the first asynchronous hide is pending. Once both status and
 * navigation bars have been observed hidden, any later visibility is measurement contamination.
 */
internal fun observeSystemBars(
    state: TestWindowIsolationState,
    token: Long,
    statusBarVisible: Boolean,
    navigationBarVisible: Boolean,
): TestWindowIsolationObservation {
    if (state.token != token || state.phase == TestWindowIsolationPhase.IDLE) {
        return TestWindowIsolationObservation(state, contaminationDetected = false)
    }
    val allSystemBarsHidden = !statusBarVisible && !navigationBarVisible
    val originalVisibilityRestored =
        statusBarVisible == state.restoreStatusBarVisible &&
            navigationBarVisible == state.restoreNavigationBarVisible
    return when {
        state.phase in RESTORATION_PHASES && originalVisibilityRestored ->
            TestWindowIsolationObservation(
                state = TestWindowIsolationState(),
                contaminationDetected = false,
                restorationConfirmed = true,
            )
        allSystemBarsHidden && state.phase == TestWindowIsolationPhase.REQUESTED ->
            TestWindowIsolationObservation(
                state.copy(phase = TestWindowIsolationPhase.CONFIRMED),
                contaminationDetected = false,
            )
        !allSystemBarsHidden && state.phase == TestWindowIsolationPhase.CONFIRMED ->
            TestWindowIsolationObservation(
                state.copy(phase = TestWindowIsolationPhase.CONTAMINATED),
                contaminationDetected = true,
            )
        else -> TestWindowIsolationObservation(state, contaminationDetected = false)
    }
}

internal fun observeWindowFocusLoss(
    state: TestWindowIsolationState,
    token: Long,
): TestWindowIsolationObservation {
    if (
        state.token != token ||
        (
            state.phase != TestWindowIsolationPhase.REQUESTED &&
                state.phase != TestWindowIsolationPhase.CONFIRMED
        )
    ) {
        return TestWindowIsolationObservation(state, contaminationDetected = false)
    }
    return TestWindowIsolationObservation(
        state.copy(phase = TestWindowIsolationPhase.CONTAMINATED),
        contaminationDetected = true,
    )
}

internal fun isolationStateAfterRestoreCommandResult(
    current: TestWindowIsolationState,
    token: Long,
    succeeded: Boolean,
): TestWindowIsolationState {
    if (
        succeeded ||
        current.token != token ||
        current.phase != TestWindowIsolationPhase.RESTORING
    ) {
        return current
    }
    return current.copy(phase = TestWindowIsolationPhase.RESTORE_FAILED)
}

internal enum class FocusGainIsolationAction {
    HIDE,
    RETRY_RESTORE,
    NONE,
}

internal fun focusGainIsolationAction(
    phase: TestWindowIsolationPhase,
): FocusGainIsolationAction = when (phase) {
    TestWindowIsolationPhase.REQUESTED,
    TestWindowIsolationPhase.CONFIRMED,
    TestWindowIsolationPhase.CONTAMINATED,
    -> FocusGainIsolationAction.HIDE
    TestWindowIsolationPhase.RESTORE_FAILED -> FocusGainIsolationAction.RETRY_RESTORE
    TestWindowIsolationPhase.IDLE,
    TestWindowIsolationPhase.RESTORING,
    -> FocusGainIsolationAction.NONE
}

internal fun shouldShowIdleSystemBars(
    state: TestWindowIsolationState,
    processLeaseActive: Boolean,
    releasedToken: Long? = null,
    locallyReleasedToken: Long? = null,
): Boolean =
    state.phase == TestWindowIsolationPhase.IDLE &&
        !processLeaseActive &&
        (releasedToken == null || releasedToken != locallyReleasedToken)

internal fun shouldHideIdleSystemBarsForForeignLease(
    state: TestWindowIsolationState,
    processLeaseActive: Boolean,
): Boolean =
    state.phase == TestWindowIsolationPhase.IDLE && processLeaseActive

internal fun foreignLeaseRestorationAcknowledged(
    processLeaseActive: Boolean,
    restoreCommandIssued: Boolean,
    statusBarVisible: Boolean,
    navigationBarVisible: Boolean,
    restoreStatusBarVisible: Boolean?,
    restoreNavigationBarVisible: Boolean?,
): Boolean =
    !processLeaseActive &&
        restoreCommandIssued &&
        restoreStatusBarVisible != null &&
        restoreNavigationBarVisible != null &&
        statusBarVisible == restoreStatusBarVisible &&
        navigationBarVisible == restoreNavigationBarVisible

internal fun shouldAttemptForeignLeaseHide(
    processLeaseActive: Boolean,
    allSystemBarsHidden: Boolean,
    attemptCount: Int,
    maxAttempts: Int,
    verificationPending: Boolean,
    failureReported: Boolean,
): Boolean =
    processLeaseActive &&
        !allSystemBarsHidden &&
        attemptCount in 0 until maxAttempts &&
        !verificationPending &&
        !failureReported

internal enum class BarrierGatedIsolationReleaseResult {
    RELEASED,
    RENDERER_UNCONFIRMED,
    RELEASE_FAILED,
}

internal suspend fun releaseIsolationAfterRendererBarrier(
    awaitRendererTeardown: suspend () -> Boolean,
    releaseIsolation: suspend () -> Boolean,
): BarrierGatedIsolationReleaseResult {
    if (!awaitRendererTeardown()) {
        return BarrierGatedIsolationReleaseResult.RENDERER_UNCONFIRMED
    }
    return if (releaseIsolation()) {
        BarrierGatedIsolationReleaseResult.RELEASED
    } else {
        BarrierGatedIsolationReleaseResult.RELEASE_FAILED
    }
}

/**
 * Process-wide ownership prevents a recreated Activity from starting a new measured Window while
 * an older Window has not acknowledged restoration. A missing acknowledgment intentionally keeps
 * the lease sticky until the process restarts.
 */
internal class TestWindowIsolationLeaseRegistry {
    private var nextToken = 0L
    private var activeToken: Long? = null
    private var ownerFailureListener: ((Long, String) -> Unit)? = null
    private val releaseListeners = LinkedHashSet<(Long) -> Unit>()

    @Synchronized
    fun acquire(
        failureListener: ((Long, String) -> Unit)? = null,
    ): Long? {
        if (activeToken != null) return null
        nextToken = nextIsolationToken(nextToken)
        return nextToken.also {
            activeToken = it
            ownerFailureListener = failureListener
        }
    }

    fun confirmReleased(token: Long): Boolean {
        val listeners = synchronized(this) {
            if (activeToken != token) return false
            activeToken = null
            ownerFailureListener = null
            releaseListeners.toList()
        }
        listeners.forEach { listener ->
            runCatching { listener(token) }
        }
        return true
    }

    @Synchronized
    fun owns(token: Long): Boolean = activeToken == token

    @Synchronized
    fun hasActiveLease(): Boolean = activeToken != null

    @Synchronized
    fun activeLeaseToken(): Long? = activeToken

    @Synchronized
    fun removeOwnerFailureListener(
        token: Long,
        listener: (Long, String) -> Unit,
    ): Boolean {
        if (activeToken != token || ownerFailureListener !== listener) return false
        ownerFailureListener = null
        return true
    }

    fun reportOwnerFailure(token: Long, reason: String): Boolean {
        val target = synchronized(this) {
            if (activeToken != token) return false
            ownerFailureListener
        }
        val listener = target ?: return false
        return runCatching {
            listener(token, reason)
            true
        }.getOrDefault(false)
    }

    @Synchronized
    fun addReleaseListener(listener: (Long) -> Unit) {
        releaseListeners += listener
    }

    @Synchronized
    fun removeReleaseListener(listener: (Long) -> Unit) {
        releaseListeners -= listener
    }
}

internal val ProcessTestWindowIsolationLeaseRegistry =
    TestWindowIsolationLeaseRegistry()

private val RESTORATION_PHASES = setOf(
    TestWindowIsolationPhase.RESTORING,
    TestWindowIsolationPhase.RESTORE_FAILED,
)
