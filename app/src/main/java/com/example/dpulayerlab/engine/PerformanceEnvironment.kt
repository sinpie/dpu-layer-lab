package com.example.dpulayerlab.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.example.dpulayerlab.vendor.VendorPerformanceSessionState
import java.util.concurrent.atomic.AtomicReference

/**
 * Fresh, producer-before-start power state. These checks prevent a nominal "high performance"
 * run from silently starting inside an already constrained device state.
 *
 * Thermal mitigation remains owned by Android/the kernel. Optional app-side SEVERE protection is
 * governed by an immutable per-plan [RuntimeProtectionPolicy]; CRITICAL remains a mandatory abort.
 */
internal data class PerformanceEnvironment(
    val powerSaveMode: Boolean,
    val interactive: Boolean,
    val deviceIdleMode: Boolean,
    val thermalStatus: Int,
)

/**
 * Optional app-side protection selected before a plan starts.
 *
 * This policy never controls the mandatory fail-safes (thermal CRITICAL, low-memory, local worker
 * failure, or power/display isolation loss). Keep the default false so a lab run can observe the
 * requested load through Android/kernel SEVERE throttling without the app silently reducing it.
 */
internal data class RuntimeProtectionPolicy(
    val severeThermalDeratingEnabled: Boolean = false,
)

internal fun runtimeProtectionPolicyDescription(policy: RuntimeProtectionPolicy): String =
    "appSevereDerate=${if (policy.severeThermalDeratingEnabled) "on" else "off"}; " +
        "mandatory=thermal-critical,low-memory,worker,power-display-integrity"

internal enum class PerformancePreflightFailure {
    POWER_SAVE_ACTIVE,
    DISPLAY_NOT_INTERACTIVE,
    DEVICE_IDLE_ACTIVE,
    THERMAL_ALREADY_SEVERE,
    THERMAL_CRITICAL,
}

internal fun performancePreflightFailure(
    environment: PerformanceEnvironment,
    protectionPolicy: RuntimeProtectionPolicy = RuntimeProtectionPolicy(),
): PerformancePreflightFailure? = when {
    environment.powerSaveMode ->
        PerformancePreflightFailure.POWER_SAVE_ACTIVE
    !environment.interactive ->
        PerformancePreflightFailure.DISPLAY_NOT_INTERACTIVE
    environment.deviceIdleMode ->
        PerformancePreflightFailure.DEVICE_IDLE_ACTIVE
    environment.thermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL ->
        PerformancePreflightFailure.THERMAL_CRITICAL
    protectionPolicy.severeThermalDeratingEnabled &&
        environment.thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE ->
        PerformancePreflightFailure.THERMAL_ALREADY_SEVERE
    else -> null
}

internal fun PerformancePreflightFailure.userMessage(): String = when (this) {
    PerformancePreflightFailure.POWER_SAVE_ACTIVE ->
        "Battery Saver 해제를 확인하지 못해 producer를 시작하지 않았습니다."
    PerformancePreflightFailure.DISPLAY_NOT_INTERACTIVE ->
        "화면/기기가 interactive 상태가 아니어서 producer를 시작하지 않았습니다."
    PerformancePreflightFailure.DEVICE_IDLE_ACTIVE ->
        "Doze/device-idle 상태가 남아 있어 producer를 시작하지 않았습니다."
    PerformancePreflightFailure.THERMAL_ALREADY_SEVERE ->
        "선제 thermal 보호가 켜져 있고 시작 전 상태가 SEVERE여서 producer를 시작하지 않았습니다."
    PerformancePreflightFailure.THERMAL_CRITICAL ->
        "시작 전 thermal 상태가 CRITICAL 이상이어서 필수 안전 중단했습니다."
}

internal fun shouldApplySevereThermalDerating(
    thermalStatus: Int,
    alreadyDerated: Boolean,
    protectionPolicy: RuntimeProtectionPolicy,
): Boolean =
    protectionPolicy.severeThermalDeratingEnabled &&
        !alreadyDerated &&
        thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE &&
        thermalStatus < PowerManager.THERMAL_STATUS_CRITICAL

internal enum class PerformanceIsolationStartMode {
    VENDOR_LEASE,
    APP_ONLY_MONITOR,
    REJECT,
}

/**
 * Separates policy acquisition/restore transients from the interval in which an observed power
 * constraint is test contamination. In particular, restoring an originally enabled Battery Saver
 * policy must not abort the just-finished run.
 */
internal enum class PerformanceIsolationLifecycle {
    IDLE,
    ACQUIRING,
    ACTIVE,
    RESTORING,
    FAILED,
}

internal fun shouldMonitorPerformanceIsolation(
    lifecycle: PerformanceIsolationLifecycle,
): Boolean = lifecycle == PerformanceIsolationLifecycle.ACTIVE

/**
 * A completed restore proves only that the global policy returned to its original value. It does
 * not make a renewal/health/service-session failure safe to reuse in the same process.
 */
internal fun performanceIsolationStartBlocked(
    isolationOwned: Boolean,
    ticketPresent: Boolean,
    renewalPresent: Boolean,
    restoreConfirmed: Boolean,
    sessionIntegrityConfirmed: Boolean,
): Boolean =
    isolationOwned ||
        ticketPresent ||
        renewalPresent ||
        !restoreConfirmed ||
        !sessionIntegrityConfirmed

/**
 * BEGIN has only two trustworthy outcomes: an active ticketed lease or an unavailable, ticketless
 * broker that permits app-only monitoring. Every other typed result indicates a stale, late, or
 * otherwise unhealthy command/session and remains process-sticky even if a cleanup END succeeds.
 */
internal fun performanceIsolationAcquisitionCompromised(
    brokerState: VendorPerformanceSessionState,
    ticketPresent: Boolean,
): Boolean = when (brokerState) {
    VendorPerformanceSessionState.ACTIVE -> !ticketPresent
    VendorPerformanceSessionState.UNAVAILABLE -> ticketPresent
    VendorPerformanceSessionState.PENDING,
    VendorPerformanceSessionState.RESTORED,
    VendorPerformanceSessionState.FAILED -> true
}

/**
 * A provider END can be acknowledged before PowerManager reflects the restored global state.
 * Keep the producer-before-BEGIN baseline and permit a later bounded direct-state verification
 * even though no remote ticket or renewal owner remains.
 */
internal fun shouldRetryDirectPerformanceRestore(
    isolationOwned: Boolean,
    ticketPresent: Boolean,
    renewalPresent: Boolean,
    restoreConfirmed: Boolean,
    originalStateKnown: Boolean,
): Boolean =
    !isolationOwned &&
        !ticketPresent &&
        !renewalPresent &&
        !restoreConfirmed &&
        originalStateKnown

/**
 * APP_ONLY monitoring never mutates a global policy, so there is nothing for the app to restore
 * when an external actor changes Battery Saver during the run. Ambiguous/failed broker starts set
 * [restoreAlreadyConfirmed] false and still require an exact direct-state match.
 */
internal fun ticketlessPerformanceRestoreConfirmed(
    restoreAlreadyConfirmed: Boolean,
    directOriginalStateMatched: Boolean,
): Boolean = restoreAlreadyConfirmed || directOriginalStateMatched

/**
 * A timed-out END remains active on the Activity-free vendor lane. When its exact acknowledgment
 * later clears the process latch, the controller may release its stale local owner only after the
 * renewal Job has really terminated and PowerManager again matches the producer-before-BEGIN
 * baseline. This prevents both a permanent false-negative and a proof based on latch state alone.
 */
internal fun latePerformanceRestoreConfirmed(
    processRestorePending: Boolean,
    renewalRunning: Boolean,
    restoreAlreadyConfirmed: Boolean,
    originalPowerSaveMode: Boolean?,
    currentPowerSaveMode: Boolean?,
): Boolean =
    !processRestorePending &&
        !renewalRunning &&
        (
            restoreAlreadyConfirmed ||
                (
                    originalPowerSaveMode != null &&
                        currentPowerSaveMode == originalPowerSaveMode
                    )
            )

/**
 * Advances an absolute fixed-period deadline without scheduling busy catch-up iterations.
 */
internal fun nextAbsoluteControlDeadlineMs(
    previousDeadlineMs: Long,
    nowMs: Long,
    periodMs: Long,
): Long {
    require(periodMs > 0L)
    if (previousDeadlineMs > nowMs) return previousDeadlineMs
    val elapsed = (nowMs - previousDeadlineMs).coerceAtLeast(0L)
    val skippedPeriods = elapsed / periodMs + 1L
    val increment = if (skippedPeriods > Long.MAX_VALUE / periodMs) {
        Long.MAX_VALUE
    } else {
        skippedPeriods * periodMs
    }
    return if (increment == Long.MAX_VALUE || previousDeadlineMs > Long.MAX_VALUE - increment) {
        Long.MAX_VALUE
    } else {
        previousDeadlineMs + increment
    }
}

internal enum class PerformanceRestoreReportTransition {
    NONE,
    CONFIRMED,
    FAILED,
    CONFIRMED_AFTER_RETRY,
}

internal fun performanceRestoreReportTransition(
    existingEventTypes: Collection<String>,
    restored: Boolean,
): PerformanceRestoreReportTransition {
    val confirmed =
        PERFORMANCE_RESTORE_CONFIRMED_EVENT in existingEventTypes ||
            PERFORMANCE_RESTORE_CONFIRMED_AFTER_RETRY_EVENT in existingEventTypes
    val failed = PERFORMANCE_RESTORE_FAILED_EVENT in existingEventTypes
    return when {
        confirmed -> PerformanceRestoreReportTransition.NONE
        restored && failed ->
            PerformanceRestoreReportTransition.CONFIRMED_AFTER_RETRY
        restored -> PerformanceRestoreReportTransition.CONFIRMED
        failed -> PerformanceRestoreReportTransition.NONE
        else -> PerformanceRestoreReportTransition.FAILED
    }
}

internal const val PERFORMANCE_RESTORE_CONFIRMED_EVENT =
    "PERFORMANCE_RESTORE_CONFIRMED"
internal const val PERFORMANCE_RESTORE_FAILED_EVENT =
    "PERFORMANCE_RESTORE_FAILED"
internal const val PERFORMANCE_RESTORE_CONFIRMED_AFTER_RETRY_EVENT =
    "PERFORMANCE_RESTORE_CONFIRMED_AFTER_RETRY"
internal const val PERFORMANCE_RESTORE_REPORT_WRITE_FAILED_EVENT =
    "PERFORMANCE_RESTORE_REPORT_WRITE_FAILED"

/**
 * A registered receiver may outlive its unregister call when a framework/vendor implementation
 * fails. The receiver owns only this Activity-free holder; detaching it removes the final
 * Controller/Activity reference even in that failure mode.
 */
internal class PowerStateCallbackHolder {
    private val callback = AtomicReference<(() -> Unit)?>(null)

    fun attach(newCallback: () -> Unit) {
        callback.set(newCallback)
    }

    fun detach() {
        callback.set(null)
    }

    fun dispatch() {
        callback.get()?.invoke()
    }
}

internal class PowerStateBroadcastReceiver(
    private val callbackHolder: PowerStateCallbackHolder,
) : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        callbackHolder.dispatch()
    }
}

/**
 * Generation-aware proof that every Compose renderer container has been disposed.
 *
 * Old disposal callbacks cannot accidentally acknowledge a newly attached container. The bound is
 * intentionally far above the single-container production topology while still turning a
 * composition bug into a fail-closed exception instead of unbounded retained bookkeeping.
 */
internal class RendererContainerLifecycleTracker(
    private val maxAttachedContainers: Int = 8,
) {
    private val activeTokens = LinkedHashSet<Long>()
    private var nextToken = 0L

    init {
        require(maxAttachedContainers in 1..64)
    }

    fun attach(): Long {
        check(activeTokens.size < maxAttachedContainers) {
            "renderer container tracking limit exceeded"
        }
        nextToken = if (nextToken == Long.MAX_VALUE) 1L else nextToken + 1L
        while (!activeTokens.add(nextToken)) {
            nextToken = if (nextToken == Long.MAX_VALUE) 1L else nextToken + 1L
        }
        return nextToken
    }

    /**
     * Returns true only when [token] was current and its removal emptied the tracked set.
     */
    fun dispose(token: Long): Boolean =
        activeTokens.remove(token) && activeTokens.isEmpty()

    fun hasAttachedContainers(): Boolean = activeTokens.isNotEmpty()

    internal fun attachedCount(): Int = activeTokens.size
}

/**
 * An unavailable broker is not itself fatal when the requested policy is already off. Any
 * ambiguous result carrying a ticket is rejected because it may represent an unconfirmed remote
 * mutation which must not be mistaken for app-only monitoring.
 */
internal fun performanceIsolationStartMode(
    brokerState: VendorPerformanceSessionState,
    ticketPresent: Boolean,
    originalBatterySaverActive: Boolean,
    batterySaverActive: Boolean,
): PerformanceIsolationStartMode = when {
    brokerState == VendorPerformanceSessionState.ACTIVE && ticketPresent ->
        PerformanceIsolationStartMode.VENDOR_LEASE
    brokerState == VendorPerformanceSessionState.UNAVAILABLE &&
        !ticketPresent &&
        !originalBatterySaverActive &&
        !batterySaverActive ->
        PerformanceIsolationStartMode.APP_ONLY_MONITOR
    else -> PerformanceIsolationStartMode.REJECT
}

internal enum class PerformanceIsolationContinuityFailure {
    POWER_SAVE_ACTIVE,
    DISPLAY_NOT_INTERACTIVE,
    DEVICE_IDLE_ACTIVE,
    VENDOR_SESSION_CHANGED,
}

internal fun performanceIsolationContinuityFailure(
    sampledPowerSaveMode: Boolean,
    directPowerSaveMode: Boolean,
    interactive: Boolean,
    deviceIdleMode: Boolean,
    expectedVendorSession: Long?,
    observedVendorSession: Long?,
): PerformanceIsolationContinuityFailure? = when {
    sampledPowerSaveMode || directPowerSaveMode ->
        PerformanceIsolationContinuityFailure.POWER_SAVE_ACTIVE
    !interactive ->
        PerformanceIsolationContinuityFailure.DISPLAY_NOT_INTERACTIVE
    deviceIdleMode ->
        PerformanceIsolationContinuityFailure.DEVICE_IDLE_ACTIVE
    expectedVendorSession != null && observedVendorSession != expectedVendorSession ->
        PerformanceIsolationContinuityFailure.VENDOR_SESSION_CHANGED
    else -> null
}
