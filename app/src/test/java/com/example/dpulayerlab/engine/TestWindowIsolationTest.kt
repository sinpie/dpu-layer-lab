package com.example.dpulayerlab.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TestWindowIsolationTest {
    @Test
    fun initialVisibleInsetsAreAllowedUntilFirstHiddenAcknowledgment() {
        val requested = TestWindowIsolationState(
            token = 7L,
            phase = TestWindowIsolationPhase.REQUESTED,
        )

        val stillWaiting = observeSystemBars(
            requested,
            7L,
            statusBarVisible = true,
            navigationBarVisible = true,
        )
        assertFalse(stillWaiting.contaminationDetected)
        assertTrue(stillWaiting.state.phase == TestWindowIsolationPhase.REQUESTED)

        val confirmed = observeSystemBars(
            stillWaiting.state,
            7L,
            statusBarVisible = false,
            navigationBarVisible = false,
        )
        assertFalse(confirmed.contaminationDetected)
        assertTrue(confirmed.state.phase == TestWindowIsolationPhase.CONFIRMED)
    }

    @Test
    fun postConfirmationRevealIsReportedOnlyOnce() {
        val confirmed = TestWindowIsolationState(
            token = 11L,
            phase = TestWindowIsolationPhase.CONFIRMED,
        )

        val revealed = observeSystemBars(
            confirmed,
            11L,
            statusBarVisible = true,
            navigationBarVisible = false,
        )
        assertTrue(revealed.contaminationDetected)
        assertTrue(revealed.state.phase == TestWindowIsolationPhase.CONTAMINATED)

        val repeated = observeSystemBars(
            revealed.state,
            11L,
            statusBarVisible = true,
            navigationBarVisible = false,
        )
        assertFalse(repeated.contaminationDetected)
    }

    @Test
    fun focusLossAfterConfirmationContaminatesButStaleTokenDoesNot() {
        val confirmed = TestWindowIsolationState(
            token = 13L,
            phase = TestWindowIsolationPhase.CONFIRMED,
        )

        assertFalse(observeWindowFocusLoss(confirmed, 12L).contaminationDetected)
        assertTrue(observeWindowFocusLoss(confirmed, 13L).contaminationDetected)
    }

    @Test
    fun focusLossWhileInitialHideIsPendingCannotLaterStartAnUnfocusedRun() {
        val requested = TestWindowIsolationState(
            token = 14L,
            phase = TestWindowIsolationPhase.REQUESTED,
        )

        val lost = observeWindowFocusLoss(requested, 14L)
        assertTrue(lost.contaminationDetected)
        assertTrue(lost.state.phase == TestWindowIsolationPhase.CONTAMINATED)
        val lateHidden = observeSystemBars(
            lost.state,
            14L,
            statusBarVisible = false,
            navigationBarVisible = false,
        )
        assertFalse(lateHidden.restorationConfirmed)
        assertTrue(lateHidden.state.phase == TestWindowIsolationPhase.CONTAMINATED)
    }

    @Test
    fun lateInsetsFromReleasedOrReplacedSessionAreIgnored() {
        val idle = TestWindowIsolationState()
        assertFalse(
            observeSystemBars(
                idle,
                4L,
                statusBarVisible = true,
                navigationBarVisible = true,
            )
                .contaminationDetected,
        )

        val replacement = TestWindowIsolationState(
            token = 5L,
            phase = TestWindowIsolationPhase.CONFIRMED,
        )
        val stale = observeSystemBars(
            replacement,
            4L,
            statusBarVisible = true,
            navigationBarVisible = true,
        )
        assertFalse(stale.contaminationDetected)
        assertTrue(stale.state == replacement)
    }

    @Test
    fun showCommandSuccessRemainsRestoringUntilVisibleInsetsAcknowledgeIt() {
        val confirmed = TestWindowIsolationState(
            token = 21L,
            phase = TestWindowIsolationPhase.CONFIRMED,
        )

        val restoring = confirmed.copy(phase = TestWindowIsolationPhase.RESTORING)
        val afterCommand = isolationStateAfterRestoreCommandResult(
            current = restoring,
            token = 21L,
            succeeded = true,
        )
        assertTrue(afterCommand.token == 21L)
        assertTrue(afterCommand.phase == TestWindowIsolationPhase.RESTORING)

        val stillHidden = observeSystemBars(
            afterCommand,
            21L,
            statusBarVisible = false,
            navigationBarVisible = false,
        )
        assertFalse(stillHidden.restorationConfirmed)
        assertTrue(stillHidden.state.phase == TestWindowIsolationPhase.RESTORING)

        val restored = observeSystemBars(
            stillHidden.state,
            21L,
            statusBarVisible = true,
            navigationBarVisible = true,
        )
        assertTrue(restored.restorationConfirmed)
        assertTrue(restored.state == TestWindowIsolationState())
    }

    @Test
    fun failedRestoreKeepsTokenAndLaterMatchingInsetsCanCompleteIt() {
        val confirmed = TestWindowIsolationState(
            token = 22L,
            phase = TestWindowIsolationPhase.CONFIRMED,
        )
        val failed = isolationStateAfterRestoreCommandResult(
            current = confirmed.copy(phase = TestWindowIsolationPhase.RESTORING),
            token = 22L,
            succeeded = false,
        )
        assertTrue(failed.token == 22L)
        assertTrue(failed.phase == TestWindowIsolationPhase.RESTORE_FAILED)

        val observed = observeSystemBars(
            failed,
            22L,
            statusBarVisible = true,
            navigationBarVisible = true,
        )
        assertTrue(observed.restorationConfirmed)
        assertTrue(observed.state == TestWindowIsolationState())
    }

    @Test
    fun reentrantRestoreAcknowledgmentCannotBeOverwrittenByCommandCompletion() {
        val idleAfterInsets = TestWindowIsolationState()

        assertTrue(
            isolationStateAfterRestoreCommandResult(
                current = idleAfterInsets,
                token = 25L,
                succeeded = true,
            ) == idleAfterInsets,
        )
        assertTrue(
            isolationStateAfterRestoreCommandResult(
                current = idleAfterInsets,
                token = 25L,
                succeeded = false,
            ) == idleAfterInsets,
        )
    }

    @Test
    fun originallyAbsentNavigationBarDoesNotBlockRestoration() {
        val restoring = TestWindowIsolationState(
            token = 23L,
            phase = TestWindowIsolationPhase.RESTORING,
            restoreStatusBarVisible = true,
            restoreNavigationBarVisible = false,
        )

        val restored = observeSystemBars(
            restoring,
            23L,
            statusBarVisible = true,
            navigationBarVisible = false,
        )
        assertTrue(restored.restorationConfirmed)
    }

    @Test
    fun focusGainNeverRehidesWhileRestoringAndRetriesOnlyFailedRestore() {
        assertTrue(
            focusGainIsolationAction(TestWindowIsolationPhase.CONFIRMED) ==
                FocusGainIsolationAction.HIDE,
        )
        assertTrue(
            focusGainIsolationAction(TestWindowIsolationPhase.RESTORING) ==
                FocusGainIsolationAction.NONE,
        )
        assertTrue(
            focusGainIsolationAction(TestWindowIsolationPhase.RESTORE_FAILED) ==
                FocusGainIsolationAction.RETRY_RESTORE,
        )
    }

    @Test
    fun processLeaseIsExclusiveAndOnlyMatchingTokenCanReleaseIt() {
        val registry = TestWindowIsolationLeaseRegistry()
        val token = checkNotNull(registry.acquire())

        assertTrue(registry.owns(token))
        assertTrue(registry.hasActiveLease())
        assertTrue(registry.acquire() == null)
        assertFalse(registry.confirmReleased(token + 1L))
        assertTrue(registry.hasActiveLease())
        assertTrue(registry.confirmReleased(token))
        assertFalse(registry.hasActiveLease())
        assertTrue(checkNotNull(registry.acquire()) != token)
    }

    @Test
    fun foreignProcessLeaseSuppressesIdleSystemBarsUntilMatchingRelease() {
        val idle = TestWindowIsolationState()

        assertTrue(
            shouldHideIdleSystemBarsForForeignLease(
                state = idle,
                processLeaseActive = true,
            ),
        )
        assertFalse(
            shouldHideIdleSystemBarsForForeignLease(
                state = idle,
                processLeaseActive = false,
            ),
        )
        assertFalse(
            shouldShowIdleSystemBars(
                state = idle,
                processLeaseActive = true,
            ),
        )
        assertTrue(
            shouldShowIdleSystemBars(
                state = idle,
                processLeaseActive = false,
                releasedToken = 31L,
                locallyReleasedToken = null,
            ),
        )
        assertFalse(
            shouldShowIdleSystemBars(
                state = idle,
                processLeaseActive = false,
                releasedToken = 31L,
                locallyReleasedToken = 31L,
            ),
        )
    }

    @Test
    fun processLeaseReleaseListenerRunsOnlyForMatchingReleaseAndCanBeRemoved() {
        val registry = TestWindowIsolationLeaseRegistry()
        val released = mutableListOf<Long>()
        val listener: (Long) -> Unit = { token -> released += token }
        registry.addReleaseListener(listener)
        val first = checkNotNull(registry.acquire())

        assertFalse(registry.confirmReleased(first + 1L))
        assertTrue(released.isEmpty())
        assertTrue(registry.confirmReleased(first))
        assertTrue(released == listOf(first))

        registry.removeReleaseListener(listener)
        val second = checkNotNull(registry.acquire())
        assertTrue(registry.confirmReleased(second))
        assertTrue(released == listOf(first))
    }

    @Test
    fun foreignLeaseRestoreRequiresReleaseCommandAndMatchingFreshInsets() {
        assertFalse(
            foreignLeaseRestorationAcknowledged(
                processLeaseActive = true,
                restoreCommandIssued = true,
                statusBarVisible = true,
                navigationBarVisible = true,
                restoreStatusBarVisible = true,
                restoreNavigationBarVisible = true,
            ),
        )
        assertFalse(
            foreignLeaseRestorationAcknowledged(
                processLeaseActive = false,
                restoreCommandIssued = false,
                statusBarVisible = true,
                navigationBarVisible = true,
                restoreStatusBarVisible = true,
                restoreNavigationBarVisible = true,
            ),
        )
        assertFalse(
            foreignLeaseRestorationAcknowledged(
                processLeaseActive = false,
                restoreCommandIssued = true,
                statusBarVisible = true,
                navigationBarVisible = false,
                restoreStatusBarVisible = true,
                restoreNavigationBarVisible = true,
            ),
        )
        assertFalse(
            foreignLeaseRestorationAcknowledged(
                processLeaseActive = false,
                restoreCommandIssued = true,
                statusBarVisible = true,
                navigationBarVisible = true,
                restoreStatusBarVisible = null,
                restoreNavigationBarVisible = null,
            ),
        )
        assertTrue(
            foreignLeaseRestorationAcknowledged(
                processLeaseActive = false,
                restoreCommandIssued = true,
                statusBarVisible = true,
                navigationBarVisible = false,
                restoreStatusBarVisible = true,
                restoreNavigationBarVisible = false,
            ),
        )
    }

    @Test
    fun foreignLeaseRehideUsesABoundedAttemptBudget() {
        assertTrue(
            shouldAttemptForeignLeaseHide(
                processLeaseActive = true,
                allSystemBarsHidden = false,
                attemptCount = 0,
                maxAttempts = 4,
                verificationPending = false,
                failureReported = false,
            ),
        )
        assertFalse(
            shouldAttemptForeignLeaseHide(
                processLeaseActive = true,
                allSystemBarsHidden = false,
                attemptCount = 4,
                maxAttempts = 4,
                verificationPending = false,
                failureReported = false,
            ),
        )
        assertFalse(
            shouldAttemptForeignLeaseHide(
                processLeaseActive = true,
                allSystemBarsHidden = false,
                attemptCount = 1,
                maxAttempts = 4,
                verificationPending = true,
                failureReported = false,
            ),
        )
        assertFalse(
            shouldAttemptForeignLeaseHide(
                processLeaseActive = false,
                allSystemBarsHidden = false,
                attemptCount = 0,
                maxAttempts = 4,
                verificationPending = false,
                failureReported = false,
            ),
        )
        assertFalse(
            shouldAttemptForeignLeaseHide(
                processLeaseActive = true,
                allSystemBarsHidden = true,
                attemptCount = 0,
                maxAttempts = 4,
                verificationPending = false,
                failureReported = false,
            ),
        )
        assertFalse(
            shouldAttemptForeignLeaseHide(
                processLeaseActive = true,
                allSystemBarsHidden = false,
                attemptCount = 1,
                maxAttempts = 4,
                verificationPending = false,
                failureReported = true,
            ),
        )
    }

    @Test
    fun foreignMaskFailureIsDeliveredOnlyToTheActiveLeaseOwner() {
        val registry = TestWindowIsolationLeaseRegistry()
        val failures = mutableListOf<Pair<Long, String>>()
        val token = checkNotNull(
            registry.acquire { ownerToken, reason ->
                failures += ownerToken to reason
            },
        )

        assertTrue(registry.activeLeaseToken() == token)
        assertTrue(registry.reportOwnerFailure(token, "mask not confirmed"))
        assertTrue(failures == listOf(token to "mask not confirmed"))
        assertTrue(registry.confirmReleased(token))
        assertFalse(registry.reportOwnerFailure(token, "stale"))
        assertTrue(failures == listOf(token to "mask not confirmed"))
    }

    @Test
    fun stickyLeaseCanDetachDestroyedOwnerWithoutReleasingOwnership() {
        val registry = TestWindowIsolationLeaseRegistry()
        val failures = mutableListOf<String>()
        val listener: (Long, String) -> Unit = { _, reason -> failures += reason }
        val token = checkNotNull(registry.acquire(listener))

        assertFalse(
            registry.removeOwnerFailureListener(
                token,
                { _, _ -> },
            ),
        )
        assertTrue(registry.removeOwnerFailureListener(token, listener))
        assertTrue(registry.owns(token))
        assertFalse(registry.reportOwnerFailure(token, "must not retain destroyed owner"))
        assertTrue(failures.isEmpty())
        assertTrue(registry.confirmReleased(token))
    }

    @Test
    fun restoringOrFailedVisibilityDoesNotReportMeasurementContamination() {
        val failed = TestWindowIsolationState(
            token = 24L,
            phase = TestWindowIsolationPhase.RESTORE_FAILED,
        )
        assertFalse(
            observeSystemBars(
                failed,
                24L,
                statusBarVisible = true,
                navigationBarVisible = false,
            )
                .contaminationDetected,
        )
    }

    @Test
    fun unconfirmedRendererBarrierNeverInvokesWindowRelease() = runBlocking {
        var releaseCalls = 0

        val result = releaseIsolationAfterRendererBarrier(
            awaitRendererTeardown = { false },
            releaseIsolation = {
                releaseCalls += 1
                true
            },
        )

        assertTrue(
            result == BarrierGatedIsolationReleaseResult.RENDERER_UNCONFIRMED,
        )
        assertTrue(releaseCalls == 0)
    }

    @Test
    fun confirmedRendererBarrierPropagatesAcknowledgedWindowRelease() = runBlocking {
        var releaseCalls = 0

        val result = releaseIsolationAfterRendererBarrier(
            awaitRendererTeardown = { true },
            releaseIsolation = {
                releaseCalls += 1
                true
            },
        )

        assertTrue(result == BarrierGatedIsolationReleaseResult.RELEASED)
        assertTrue(releaseCalls == 1)
    }
}
