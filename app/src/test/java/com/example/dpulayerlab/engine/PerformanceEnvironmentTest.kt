package com.example.dpulayerlab.engine

import android.os.PowerManager
import com.example.dpulayerlab.vendor.VendorPerformanceSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceEnvironmentTest {
    @Test
    fun unconstrainedInteractiveEnvironmentPasses() {
        assertNull(
            performancePreflightFailure(
                environment(),
            ),
        )
    }

    @Test
    fun everyMandatoryPreexistingPowerConstraintFailsClosed() {
        assertEquals(
            PerformancePreflightFailure.POWER_SAVE_ACTIVE,
            performancePreflightFailure(environment(powerSaveMode = true)),
        )
        assertEquals(
            PerformancePreflightFailure.DISPLAY_NOT_INTERACTIVE,
            performancePreflightFailure(environment(interactive = false)),
        )
        assertEquals(
            PerformancePreflightFailure.DEVICE_IDLE_ACTIVE,
            performancePreflightFailure(environment(deviceIdleMode = true)),
        )
    }

    @Test
    fun severeProtectionDefaultsOffAndEnabledPolicyRejectsPreexistingSevere() {
        val severe = environment(thermalStatus = PowerManager.THERMAL_STATUS_SEVERE)
        assertNull(performancePreflightFailure(severe))
        assertEquals(
            PerformancePreflightFailure.THERMAL_ALREADY_SEVERE,
            performancePreflightFailure(
                environment = severe,
                protectionPolicy = RuntimeProtectionPolicy(
                    severeThermalDeratingEnabled = true,
                ),
            ),
        )
    }

    @Test
    fun criticalThermalCannotBeDisabledByOptionalPolicy() {
        assertEquals(
            PerformancePreflightFailure.THERMAL_CRITICAL,
            performancePreflightFailure(
                environment(thermalStatus = PowerManager.THERMAL_STATUS_CRITICAL),
            ),
        )
        assertEquals(
            PerformancePreflightFailure.THERMAL_CRITICAL,
            performancePreflightFailure(
                environment = environment(
                    thermalStatus = PowerManager.THERMAL_STATUS_EMERGENCY,
                ),
                protectionPolicy = RuntimeProtectionPolicy(
                    severeThermalDeratingEnabled = true,
                ),
            ),
        )
    }

    @Test
    fun runtimeSevereDeratingRequiresEnabledSnapshotAndNeverOwnsCritical() {
        val enabled = RuntimeProtectionPolicy(severeThermalDeratingEnabled = true)
        val disabled = RuntimeProtectionPolicy()

        assertFalse(
            shouldApplySevereThermalDerating(
                thermalStatus = PowerManager.THERMAL_STATUS_MODERATE,
                alreadyDerated = false,
                protectionPolicy = enabled,
            ),
        )
        assertFalse(
            shouldApplySevereThermalDerating(
                thermalStatus = PowerManager.THERMAL_STATUS_SEVERE,
                alreadyDerated = false,
                protectionPolicy = disabled,
            ),
        )
        assertTrue(
            shouldApplySevereThermalDerating(
                thermalStatus = PowerManager.THERMAL_STATUS_SEVERE,
                alreadyDerated = false,
                protectionPolicy = enabled,
            ),
        )
        assertFalse(
            shouldApplySevereThermalDerating(
                thermalStatus = PowerManager.THERMAL_STATUS_SEVERE,
                alreadyDerated = true,
                protectionPolicy = enabled,
            ),
        )
        assertFalse(
            shouldApplySevereThermalDerating(
                thermalStatus = PowerManager.THERMAL_STATUS_CRITICAL,
                alreadyDerated = false,
                protectionPolicy = enabled,
            ),
        )
        assertFalse(
            shouldApplySevereThermalDerating(
                thermalStatus = PowerManager.THERMAL_STATUS_EMERGENCY,
                alreadyDerated = false,
                protectionPolicy = enabled,
            ),
        )
    }

    @Test
    fun policyProvenanceSeparatesOptionalDerateFromMandatoryFailSafes() {
        val description = runtimeProtectionPolicyDescription(RuntimeProtectionPolicy())

        assertTrue(description.contains("appSevereDerate=off"))
        assertTrue(description.contains("thermal-critical"))
        assertTrue(description.contains("low-memory"))
        assertTrue(description.contains("power-display-integrity"))
    }

    @Test
    fun performanceIsolationStartRequiresUnambiguousBrokerResult() {
        assertEquals(
            PerformanceIsolationStartMode.VENDOR_LEASE,
            performanceIsolationStartMode(
                VendorPerformanceSessionState.ACTIVE,
                ticketPresent = true,
                originalBatterySaverActive = true,
                batterySaverActive = true,
            ),
        )
        assertEquals(
            PerformanceIsolationStartMode.APP_ONLY_MONITOR,
            performanceIsolationStartMode(
                VendorPerformanceSessionState.UNAVAILABLE,
                ticketPresent = false,
                originalBatterySaverActive = false,
                batterySaverActive = false,
            ),
        )
        assertEquals(
            PerformanceIsolationStartMode.REJECT,
            performanceIsolationStartMode(
                VendorPerformanceSessionState.ACTIVE,
                ticketPresent = false,
                originalBatterySaverActive = false,
                batterySaverActive = false,
            ),
        )
        assertEquals(
            PerformanceIsolationStartMode.REJECT,
            performanceIsolationStartMode(
                VendorPerformanceSessionState.UNAVAILABLE,
                ticketPresent = true,
                originalBatterySaverActive = false,
                batterySaverActive = false,
            ),
        )
        assertEquals(
            PerformanceIsolationStartMode.REJECT,
            performanceIsolationStartMode(
                VendorPerformanceSessionState.UNAVAILABLE,
                ticketPresent = false,
                originalBatterySaverActive = false,
                batterySaverActive = true,
            ),
        )
        assertEquals(
            PerformanceIsolationStartMode.REJECT,
            performanceIsolationStartMode(
                VendorPerformanceSessionState.UNAVAILABLE,
                ticketPresent = false,
                originalBatterySaverActive = true,
                batterySaverActive = false,
            ),
        )
    }

    @Test
    fun runtimeContinuityChecksDirectAndSampledPowerSignals() {
        assertEquals(
            PerformanceIsolationContinuityFailure.POWER_SAVE_ACTIVE,
            continuity(sampledPowerSaveMode = true),
        )
        assertEquals(
            PerformanceIsolationContinuityFailure.POWER_SAVE_ACTIVE,
            continuity(directPowerSaveMode = true),
        )
        assertEquals(
            PerformanceIsolationContinuityFailure.DISPLAY_NOT_INTERACTIVE,
            continuity(interactive = false),
        )
        assertEquals(
            PerformanceIsolationContinuityFailure.DEVICE_IDLE_ACTIVE,
            continuity(deviceIdleMode = true),
        )
    }

    @Test
    fun vendorContinuityIsRequiredOnlyForAnActiveLease() {
        assertEquals(
            PerformanceIsolationContinuityFailure.VENDOR_SESSION_CHANGED,
            continuity(expectedVendorSession = 7L, observedVendorSession = null),
        )
        assertEquals(
            PerformanceIsolationContinuityFailure.VENDOR_SESSION_CHANGED,
            continuity(expectedVendorSession = 7L, observedVendorSession = 8L),
        )
        assertNull(
            continuity(expectedVendorSession = 7L, observedVendorSession = 7L),
        )
        assertNull(
            continuity(expectedVendorSession = null, observedVendorSession = 8L),
        )
    }

    @Test
    fun onlyActiveIsolationIsMonitoredForContamination() {
        PerformanceIsolationLifecycle.entries.forEach { lifecycle ->
            assertEquals(
                lifecycle == PerformanceIsolationLifecycle.ACTIVE,
                shouldMonitorPerformanceIsolation(lifecycle),
            )
        }
    }

    @Test
    fun restoredPolicyDoesNotClearStickySessionIntegrityFailure() {
        assertFalse(
            performanceIsolationStartBlocked(
                isolationOwned = false,
                ticketPresent = false,
                renewalPresent = false,
                restoreConfirmed = true,
                sessionIntegrityConfirmed = true,
            ),
        )
        assertTrue(
            performanceIsolationStartBlocked(
                isolationOwned = false,
                ticketPresent = false,
                renewalPresent = false,
                restoreConfirmed = true,
                sessionIntegrityConfirmed = false,
            ),
        )
    }

    @Test
    fun acquisitionAcceptsOnlyActiveTicketOrUnavailableTicketlessBroker() {
        assertFalse(
            performanceIsolationAcquisitionCompromised(
                brokerState = VendorPerformanceSessionState.ACTIVE,
                ticketPresent = true,
            ),
        )
        assertFalse(
            performanceIsolationAcquisitionCompromised(
                brokerState = VendorPerformanceSessionState.UNAVAILABLE,
                ticketPresent = false,
            ),
        )
        assertTrue(
            performanceIsolationAcquisitionCompromised(
                brokerState = VendorPerformanceSessionState.ACTIVE,
                ticketPresent = false,
            ),
        )
        assertTrue(
            performanceIsolationAcquisitionCompromised(
                brokerState = VendorPerformanceSessionState.UNAVAILABLE,
                ticketPresent = true,
            ),
        )
        assertTrue(
            performanceIsolationAcquisitionCompromised(
                brokerState = VendorPerformanceSessionState.FAILED,
                ticketPresent = false,
            ),
        )
    }

    @Test
    fun lateDirectRestoreRetryRequiresKnownBaselineAndNoLiveOwner() {
        assertTrue(
            shouldRetryDirectPerformanceRestore(
                isolationOwned = false,
                ticketPresent = false,
                renewalPresent = false,
                restoreConfirmed = false,
                originalStateKnown = true,
            ),
        )
        assertFalse(
            shouldRetryDirectPerformanceRestore(
                isolationOwned = false,
                ticketPresent = false,
                renewalPresent = false,
                restoreConfirmed = false,
                originalStateKnown = false,
            ),
        )
        assertFalse(
            shouldRetryDirectPerformanceRestore(
                isolationOwned = true,
                ticketPresent = false,
                renewalPresent = false,
                restoreConfirmed = false,
                originalStateKnown = true,
            ),
        )
        assertFalse(
            shouldRetryDirectPerformanceRestore(
                isolationOwned = false,
                ticketPresent = true,
                renewalPresent = false,
                restoreConfirmed = false,
                originalStateKnown = true,
            ),
        )
        assertFalse(
            shouldRetryDirectPerformanceRestore(
                isolationOwned = false,
                ticketPresent = false,
                renewalPresent = true,
                restoreConfirmed = false,
                originalStateKnown = true,
            ),
        )
        assertFalse(
            shouldRetryDirectPerformanceRestore(
                isolationOwned = false,
                ticketPresent = false,
                renewalPresent = false,
                restoreConfirmed = true,
                originalStateKnown = true,
            ),
        )
    }

    @Test
    fun appOnlyCleanupDoesNotClaimOwnershipOfExternalPolicyChanges() {
        assertTrue(
            ticketlessPerformanceRestoreConfirmed(
                restoreAlreadyConfirmed = true,
                directOriginalStateMatched = false,
            ),
        )
        assertTrue(
            ticketlessPerformanceRestoreConfirmed(
                restoreAlreadyConfirmed = false,
                directOriginalStateMatched = true,
            ),
        )
        assertFalse(
            ticketlessPerformanceRestoreConfirmed(
                restoreAlreadyConfirmed = false,
                directOriginalStateMatched = false,
            ),
        )
    }

    @Test
    fun lateEndRequiresClearedProcessLatchStoppedRenewalAndOriginalPowerState() {
        assertTrue(
            latePerformanceRestoreConfirmed(
                processRestorePending = false,
                renewalRunning = false,
                restoreAlreadyConfirmed = false,
                originalPowerSaveMode = true,
                currentPowerSaveMode = true,
            ),
        )
        assertFalse(
            latePerformanceRestoreConfirmed(
                processRestorePending = true,
                renewalRunning = false,
                restoreAlreadyConfirmed = false,
                originalPowerSaveMode = true,
                currentPowerSaveMode = true,
            ),
        )
        assertFalse(
            latePerformanceRestoreConfirmed(
                processRestorePending = false,
                renewalRunning = true,
                restoreAlreadyConfirmed = false,
                originalPowerSaveMode = true,
                currentPowerSaveMode = true,
            ),
        )
        assertFalse(
            latePerformanceRestoreConfirmed(
                processRestorePending = false,
                renewalRunning = false,
                restoreAlreadyConfirmed = false,
                originalPowerSaveMode = true,
                currentPowerSaveMode = false,
            ),
        )
        assertFalse(
            latePerformanceRestoreConfirmed(
                processRestorePending = false,
                renewalRunning = false,
                restoreAlreadyConfirmed = false,
                originalPowerSaveMode = null,
                currentPowerSaveMode = false,
            ),
        )
    }

    @Test
    fun preconfirmedAppOnlyCleanupNeedsNoDirectPowerSample() {
        assertTrue(
            latePerformanceRestoreConfirmed(
                processRestorePending = false,
                renewalRunning = false,
                restoreAlreadyConfirmed = true,
                originalPowerSaveMode = null,
                currentPowerSaveMode = null,
            ),
        )
    }

    @Test
    fun detachedPowerCallbackCannotRetainOrDispatchControllerCallback() {
        val holder = PowerStateCallbackHolder()
        var dispatchCount = 0
        holder.attach { dispatchCount++ }

        holder.dispatch()
        holder.detach()
        holder.dispatch()

        assertEquals(1, dispatchCount)
    }

    @Test
    fun rendererContainerDisposalIsGenerationAware() {
        val tracker = RendererContainerLifecycleTracker()
        val old = tracker.attach()
        assertTrue(tracker.dispose(old))

        val current = tracker.attach()
        assertFalse(tracker.dispose(old))
        assertTrue(tracker.hasAttachedContainers())
        assertTrue(tracker.dispose(current))
        assertFalse(tracker.hasAttachedContainers())
    }

    @Test
    fun multipleRendererContainersRequireEveryDisposal() {
        val tracker = RendererContainerLifecycleTracker()
        val first = tracker.attach()
        val second = tracker.attach()

        assertFalse(tracker.dispose(first))
        assertEquals(1, tracker.attachedCount())
        assertTrue(tracker.dispose(second))
        assertEquals(0, tracker.attachedCount())
    }

    @Test
    fun absoluteRenewalDeadlineSkipsMissedPeriodsWithoutCatchUp() {
        assertEquals(
            2_000L,
            nextAbsoluteControlDeadlineMs(
                previousDeadlineMs = 2_000L,
                nowMs = 1_500L,
                periodMs = 2_000L,
            ),
        )
        assertEquals(
            8_000L,
            nextAbsoluteControlDeadlineMs(
                previousDeadlineMs = 2_000L,
                nowMs = 7_900L,
                periodMs = 2_000L,
            ),
        )
        assertEquals(
            10_000L,
            nextAbsoluteControlDeadlineMs(
                previousDeadlineMs = 8_000L,
                nowMs = 8_000L,
                periodMs = 2_000L,
            ),
        )
    }

    @Test
    fun performanceRestoreReportRecordsFailureAndOneLateRecovery() {
        assertEquals(
            PerformanceRestoreReportTransition.FAILED,
            performanceRestoreReportTransition(emptyList(), restored = false),
        )
        assertEquals(
            PerformanceRestoreReportTransition.NONE,
            performanceRestoreReportTransition(
                listOf(PERFORMANCE_RESTORE_FAILED_EVENT),
                restored = false,
            ),
        )
        assertEquals(
            PerformanceRestoreReportTransition.CONFIRMED_AFTER_RETRY,
            performanceRestoreReportTransition(
                listOf(PERFORMANCE_RESTORE_FAILED_EVENT),
                restored = true,
            ),
        )
        assertEquals(
            PerformanceRestoreReportTransition.NONE,
            performanceRestoreReportTransition(
                listOf(PERFORMANCE_RESTORE_CONFIRMED_EVENT),
                restored = true,
            ),
        )
    }

    private fun continuity(
        sampledPowerSaveMode: Boolean = false,
        directPowerSaveMode: Boolean = false,
        interactive: Boolean = true,
        deviceIdleMode: Boolean = false,
        expectedVendorSession: Long? = null,
        observedVendorSession: Long? = null,
    ) = performanceIsolationContinuityFailure(
        sampledPowerSaveMode = sampledPowerSaveMode,
        directPowerSaveMode = directPowerSaveMode,
        interactive = interactive,
        deviceIdleMode = deviceIdleMode,
        expectedVendorSession = expectedVendorSession,
        observedVendorSession = observedVendorSession,
    )

    private fun environment(
        powerSaveMode: Boolean = false,
        interactive: Boolean = true,
        deviceIdleMode: Boolean = false,
        thermalStatus: Int = PowerManager.THERMAL_STATUS_NONE,
    ) = PerformanceEnvironment(
        powerSaveMode = powerSaveMode,
        interactive = interactive,
        deviceIdleMode = deviceIdleMode,
        thermalStatus = thermalStatus,
    )
}
