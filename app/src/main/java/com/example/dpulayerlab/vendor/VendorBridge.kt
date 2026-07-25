package com.example.dpulayerlab.vendor

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.example.dpulayerlab.model.LoadShape
import com.example.dpulayerlab.model.PixelRoute
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

data class VendorSnapshot(
    val apiVersion: Int,
    /** Changes whenever a Binder service registration is replaced or reconnected. */
    val serviceSession: Long,
    val underrunCount: Long?,
    val dpuUtilization: Float?,
    val busUtilization: Float?,
    val gpuUtilization: Float?,
    val gpuFrequencyHz: Long?,
    val dpuFrequencyHz: Long?,
    val deviceLayers: Int?,
    val clientLayers: Int?,
    val compressionState: String,
    val npuStatus: String,
)

internal data class VendorTelemetryV2Snapshot(
    val serviceSession: Long,
    val gpuUtilization: Float?,
    val gpuFrequencyHz: Long?,
    val dpuFrequencyHz: Long?,
)

data class VendorShutdownResult(
    val brokerWasConnected: Boolean,
    val npuStopConfirmed: Boolean,
    val compressionResetConfirmed: Boolean,
    val performanceRestoreConfirmed: Boolean,
)

data class VendorCompressionControlResult(
    val applied: Boolean,
    /** Binder registration that acknowledged the route, or null when it was not acknowledged. */
    val serviceSession: Long?,
)

enum class VendorPerformanceSessionState {
    ACTIVE,
    PENDING,
    RESTORED,
    UNAVAILABLE,
    FAILED,
}

/**
 * Client-owned identity for one API-v3 performance-policy lease.
 *
 * [commandVersion] is advanced for every begin/renew/health/end operation. Callers must replace
 * their old ticket with the ticket returned by a successful operation.
 */
data class VendorPerformanceSessionTicket(
    val sessionId: Long,
    val commandVersion: Long,
    val serviceSession: Long,
    val requestedControls: Int,
    val leaseDurationMs: Long,
)

data class VendorPerformanceSessionResult(
    val state: VendorPerformanceSessionState,
    val ticket: VendorPerformanceSessionTicket?,
    val detail: String,
)

internal enum class NpuControlCommandState {
    APPLIED,
    PENDING,
    FAILED,
}

internal data class NpuControlCommandHealth(
    val state: NpuControlCommandState,
    val detail: String,
)

internal data class NpuControlCommandTicket(
    val version: Long,
    val serviceSession: Long?,
    val submittedAtNanos: Long,
)

internal enum class VendorBrokerFailureCode {
    CONFIG_MISSING,
    CONFIG_INVALID,
    PERMISSION_MISSING,
    PERMISSION_NOT_SIGNATURE,
    PERMISSION_OWNER_MISMATCH,
    PERMISSION_NOT_GRANTED,
    PERMISSION_OWNER_SIGNER_UNTRUSTED,
    SERVICE_MISSING,
    SERVICE_CONTRACT_MISMATCH,
    SERVICE_NOT_SYSTEM,
    SERVICE_SIGNER_UNTRUSTED,
    DISCOVERY_FAILED,
    BIND_PERMISSION_DENIED,
}

internal data class VendorBrokerFailure(
    val code: VendorBrokerFailureCode,
    val detail: String,
    val retryable: Boolean = false,
)

internal enum class VendorBrokerBindingState {
    CONNECTED,
    PENDING,
    UNAVAILABLE,
}

internal data class VendorBrokerBindingAvailability(
    val state: VendorBrokerBindingState,
    val failure: VendorBrokerFailure? = null,
)

class VendorBridge private constructor(
    private val context: Context,
    executorLanes: VendorExecutorLanes,
) : AutoCloseable {
    private val connectionLock = Any()
    @Volatile
    private var service: IDpuLabVendorService? = null
    @Volatile
    private var serviceBinder: IBinder? = null
    @Volatile
    private var deathRecipient: IBinder.DeathRecipient? = null
    private var activeConnection: ServiceConnection? = null
    private var activeBindingGeneration = 0L
    @Volatile
    private var npuSupported: Boolean? = null
    @Volatile
    private var sbwcSupported: Boolean? = null
    private val binding = AtomicBoolean(false)
    private val bound = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val bindingGeneration = AtomicLong(0L)
    private val serviceGeneration = AtomicLong(0L)
    private val npuCommandVersion = AtomicLong(0L)
    private val performanceCommandVersion = AtomicLong(0L)
    private val performanceClientToken: IBinder = Binder()
    private val desiredPerformanceCommand =
        AtomicReference<PerformanceCommand?>(null)
    private val pendingPerformanceCommand =
        AtomicReference<PerformanceCommand?>(null)
    private val activePerformanceTicket =
        AtomicReference<VendorPerformanceSessionTicket?>(null)
    private val activePerformanceAppliedVersion = AtomicLong(0L)
    private val performanceLeaseDeadlineNanos = AtomicLong(0L)
    private val npuCommandAcknowledgments = NpuCommandAcknowledgments()
    private val initialNpuCommandTicket =
        npuCommandAcknowledgments.recordPending(version = 0L, serviceSession = null)
    private val desiredNpuCommand = AtomicReference(
        NpuCommand(
            intensity = 0f,
            shape = LoadShape.STEADY,
            ticket = initialNpuCommandTicket,
        ),
    )
    private val pendingNpuCommand = AtomicReference<NpuCommand?>(null)
    private val npuDrainScheduled = AtomicBoolean(false)
    private val retriedCommandVersion = AtomicReference<Long?>(null)
    private val shutdownResult = AtomicReference<VendorShutdownResult?>(null)
    private val restartSafe = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val reconnectScheduled = AtomicBoolean(false)
    private val reconnectAttempt = AtomicLong(0L)
    private val permanentBrokerFailure = AtomicReference<VendorBrokerFailure?>(null)
    private val capabilityRetryAttempt = AtomicLong(0L)
    private val capabilityRetryRunnable = AtomicReference<Runnable?>(null)
    private val capabilityIsolationSequence = AtomicLong(0L)
    private val capabilityIsolationGate = VendorCapabilityIsolationGate()
    private val activeCapabilityQuery = AtomicReference<CapabilityQuery?>(null)
    private val deferredCapabilityRefreshScheduled = AtomicBoolean(false)
    private val reconnectRunnable = Runnable {
        reconnectScheduled.set(false)
        if (!closed.get()) connect()
    }
    private val deferredCapabilityRefreshRunnable = Runnable {
        deferredCapabilityRefreshScheduled.set(false)
        if (!closed.get()) refreshCurrentCapabilities()
    }
    /**
     * A broken product Binder implementation may ignore interruption after a client timeout.
     * Bound the waiting queue so one stuck transaction cannot accumulate a task every telemetry
     * interval for the lifetime of the process.
     */
    private val telemetryExecutor = executorLanes.telemetry
    /**
     * Optional API-v2 calls must not poison the v1/exact-counter lane if a faulty provider blocks
     * in a newly added transaction. No queue means a still-running v2 call drops only the current
     * optional extension instead of accumulating stale work.
     */
    private val telemetryV2Executor = executorLanes.telemetryV2
    /**
     * Capability getters are product Binder code and may ignore interruption. Keep their one
     * actual in-flight call on a no-backlog quarantine lane so a stuck getter cannot occupy the
     * exact v1 telemetry lane or accumulate one retry per Handler tick.
     */
    private val capabilityExecutor = executorLanes.capability
    private val controlExecutor = executorLanes.control
    private val npuExecutor = executorLanes.npu
    /**
     * Performance-policy mutations never share telemetry/NPU lanes. One running drain plus one
     * queued wake signal plus one atomic latest command gives bounded latest-wins behavior without
     * a stale renewal backlog.
     */
    private val performanceExecutor = executorLanes.performance
    private fun newServiceConnection(generation: Long): ServiceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                // A callback proves registration even if it raced bindService() returning.
                if (!markBindingConnected(this, generation)) {
                    try {
                        context.unbindService(this)
                    } catch (_: Exception) {
                        // The stale registration may already have been removed.
                    }
                    return
                }
                if (closed.get()) {
                    unbindCurrentBinding(expectedConnection = this, generation = generation)
                    return
                }
                val remote = IDpuLabVendorService.Stub.asInterface(binder)
                val recipient = IBinder.DeathRecipient {
                    handleTerminalBindingLoss(
                        expectedConnection = this,
                        bindingGeneration = generation,
                        expectedBinder = binder,
                    )
                }
                val registration = synchronized(connectionLock) {
                    if (!isCurrentBindingLocked(this, generation)) return
                    val previous = serviceBinder to deathRecipient
                    service = remote
                    serviceBinder = binder
                    deathRecipient = recipient
                    npuSupported = null
                    sbwcSupported = null
                    previous to serviceGeneration.incrementAndGet()
                }
                val previousRegistration = registration.first
                val serviceGeneration = registration.second
                unlinkDeathRecipient(previousRegistration.first, previousRegistration.second)

                val linked = try {
                    binder.linkToDeath(recipient, 0)
                    true
                } catch (_: Exception) {
                    false
                }
                if (
                    !linked ||
                    closed.get() ||
                    !isCurrentService(this, generation, binder, serviceGeneration)
                ) {
                    handleTerminalBindingLoss(
                        expectedConnection = this,
                        bindingGeneration = generation,
                        expectedBinder = binder,
                    )
                    return
                }
                cancelCapabilityRetry(resetAttempt = true)
                refreshCapabilities(
                    remote = remote,
                    connection = this,
                    bindingGeneration = generation,
                    binder = binder,
                    serviceGeneration = serviceGeneration,
                )
            }

            override fun onServiceDisconnected(name: ComponentName) {
                if (!clearCurrentService(this, generation)) return
                val desired = desiredNpuCommand.get()
                if (desired.intensity > 0f) {
                    npuCommandAcknowledgments.recordFailed(
                        desired.ticket,
                        "Vendor NPU service disconnected",
                    )
                }
                publishPendingNpuCommand(desired)
                binding.set(false)
                // This binding remains registered; Android will reconnect it automatically.
            }

            override fun onBindingDied(name: ComponentName) {
                handleTerminalBindingLoss(
                    expectedConnection = this,
                    bindingGeneration = generation,
                    expectedBinder = null,
                )
            }

            override fun onNullBinding(name: ComponentName) {
                // onNullBinding still represents a registered binding and must be explicitly
                // unbound before another bind attempt.
                markBindingConnected(this, generation)
                handleTerminalBindingLoss(
                    expectedConnection = this,
                    bindingGeneration = generation,
                    expectedBinder = null,
                )
            }
        }

    fun connect() {
        if (closed.get() || permanentBrokerFailure.get() != null) return
        if (service != null || bound.get() || !binding.compareAndSet(false, true)) return
        val broker = when (val resolution = resolveConfiguredVendorBroker(context)) {
            is VendorBrokerResolution.Trusted -> resolution
            is VendorBrokerResolution.Failed -> {
                binding.set(false)
                if (resolution.failure.retryable) {
                    scheduleReconnect()
                } else {
                    recordPermanentBrokerFailure(resolution.failure)
                }
                return
            }
        }
        val explicit = Intent(ACTION_VENDOR_SERVICE).setComponent(broker.component)
        var rollbackConnection: ServiceConnection? = null
        var rollbackGeneration = 0L
        try {
            val generation = bindingGeneration.incrementAndGet()
            val candidate = newServiceConnection(generation)
            rollbackConnection = candidate
            rollbackGeneration = generation
            val accepted = synchronized(connectionLock) {
                if (closed.get() || service != null || bound.get()) {
                    false
                } else {
                    activeConnection = candidate
                    activeBindingGeneration = generation
                    true
                }
            }
            if (!accepted) {
                binding.set(false)
                return
            }
            if (closed.get()) {
                unbindCurrentBinding(candidate, generation)
                return
            }
            val registered = try {
                context.bindService(explicit, candidate, Context.BIND_AUTO_CREATE)
            } catch (_: SecurityException) {
                handleFailedBinding(
                    connection = candidate,
                    generation = generation,
                    permanentFailure = VendorBrokerFailure(
                        code = VendorBrokerFailureCode.BIND_PERMISSION_DENIED,
                        detail =
                            "Configured vendor broker rejected the declared signature permission",
                    ),
                )
                return
            } catch (_: Exception) {
                handleFailedBinding(candidate, generation)
                return
            }
            if (!registered) {
                handleFailedBinding(candidate, generation)
            } else {
                val stillCurrent = synchronized(connectionLock) {
                    if (isCurrentBindingLocked(candidate, generation)) {
                        bound.set(true)
                        true
                    } else {
                        false
                    }
                }
                if (!stillCurrent || closed.get()) {
                    // A terminal callback or close may have detached it before bindService()
                    // returned. Balance a successful registration without reviving stale state.
                    try {
                        context.unbindService(candidate)
                    } catch (_: Exception) {
                        // It may have been unregistered by a concurrent terminal callback.
                    }
                    if (stillCurrent) {
                        abandonUnregisteredBinding(candidate, generation)
                    }
                }
            }
        } catch (error: Error) {
            rollbackConnection?.let { connection ->
                try {
                    context.unbindService(connection)
                } catch (cleanupFailure: Throwable) {
                    if (
                        cleanupFailure !is IllegalArgumentException &&
                        cleanupFailure !== error
                    ) {
                        try {
                            error.addSuppressed(cleanupFailure)
                        } catch (_: Throwable) {
                            // Preserve the original fatal error.
                        }
                    }
                }
                try {
                    abandonUnregisteredBinding(connection, rollbackGeneration)
                } catch (cleanupFailure: Throwable) {
                    if (cleanupFailure !== error) {
                        try {
                            error.addSuppressed(cleanupFailure)
                        } catch (_: Throwable) {
                            // Preserve the original fatal error.
                        }
                    }
                }
                Unit
            }
            binding.set(false)
            throw error
        } catch (_: Exception) {
            binding.set(false)
        }
    }

    fun isConnected(): Boolean = service != null

    /**
     * Process-local registration identity, independent of whether the latest remote telemetry
     * transaction completed. A snapshot timeout must not be mistaken for a Binder disconnect.
     */
    fun currentServiceSession(): Long? = synchronized(connectionLock) {
        if (closed.get() || service == null || serviceBinder == null) {
            null
        } else {
            serviceGeneration.get()
        }
    }

    fun supportsNpu(): Boolean = npuSupported == true

    fun supportsSbwc(): Boolean = sbwcSupported == true

    fun isCapabilityDiscoveryPending(): Boolean =
        !closed.get() &&
            permanentBrokerFailure.get() == null &&
            (
                binding.get() ||
                    reconnectScheduled.get() ||
                    activeCapabilityQuery.get() != null ||
                    deferredCapabilityRefreshScheduled.get() ||
                    (bound.get() && service == null) ||
                    (service != null && (npuSupported == null || sbwcSupported == null))
                )

    internal fun brokerBindingAvailability(): VendorBrokerBindingAvailability {
        permanentBrokerFailure.get()?.let { failure ->
            return VendorBrokerBindingAvailability(
                state = VendorBrokerBindingState.UNAVAILABLE,
                failure = failure,
            )
        }
        return VendorBrokerBindingAvailability(
            state = if (isConnected()) {
                VendorBrokerBindingState.CONNECTED
            } else {
                VendorBrokerBindingState.PENDING
            },
        )
    }

    fun setNpuLoad(intensity: Float, shape: LoadShape) {
        requestNpuLoad(intensity, shape)
    }

    internal fun requestNpuLoad(
        intensity: Float,
        shape: LoadShape,
    ): NpuControlCommandTicket? {
        if (closed.get()) return null
        val version = npuCommandVersion.incrementAndGet()
        val ticket = npuCommandAcknowledgments.recordPending(
            version = version,
            serviceSession = currentServiceSession(),
        )
        val command = NpuCommand(
            intensity = intensity.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f,
            shape = shape,
            ticket = ticket,
        )
        val latestDesired = publishDesiredNpuCommand(command)
        publishPendingNpuCommand(latestDesired)
        if (!scheduleNpuDrain() && latestDesired.ticket == ticket) {
            npuCommandAcknowledgments.recordFailed(
                ticket,
                "Vendor NPU control executor rejected command",
            )
        }
        return ticket
    }

    internal fun awaitNpuLoadApplied(
        ticket: NpuControlCommandTicket,
        timeoutMs: Long,
    ): NpuControlCommandHealth {
        val boundedTimeoutMs = timeoutMs.coerceIn(1L, MAX_NPU_APPLY_ACK_TIMEOUT_MS)
        val deadlineNanos = System.nanoTime() +
            TimeUnit.MILLISECONDS.toNanos(boundedTimeoutMs)
        while (true) {
            val health = npuControlHealth(ticket, boundedTimeoutMs)
            if (health.state != NpuControlCommandState.PENDING) return health
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0L) {
                return npuControlHealth(ticket, pendingTimeoutMs = 1L)
            }
            LockSupport.parkNanos(
                remainingNanos.coerceAtMost(NPU_ACK_POLL_NANOS),
            )
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt()
                npuCommandAcknowledgments.recordFailed(
                    ticket,
                    "Vendor NPU apply confirmation interrupted",
                )
                return npuControlHealth(ticket, pendingTimeoutMs = 1L)
            }
        }
    }

    internal fun npuControlHealth(
        ticket: NpuControlCommandTicket,
        pendingTimeoutMs: Long,
    ): NpuControlCommandHealth =
        npuCommandAcknowledgments.health(
            ticket = ticket,
            latestDesiredVersion = desiredNpuCommand.get().version,
            currentServiceSession = currentServiceSession(),
            pendingTimeoutMs = pendingTimeoutMs.coerceIn(
                1L,
                MAX_NPU_PENDING_TIMEOUT_MS,
            ),
        )

    /**
     * Ordered run-boundary zero. The confirmation task shares the NPU lane, so success means all
     * older client commands completed before this stop call returned.
     */
    fun releaseNpuLoadAndConfirm(): Boolean {
        if (closed.get() || !supportsNpu()) return false
        setNpuLoad(0f, LoadShape.STEADY)
        return callRemote(
            executor = npuExecutor,
            fallback = false,
            timeoutMs = NPU_RELEASE_TIMEOUT_MS,
        ) { remote, _ ->
            remote.stopNpuLoad()
            true
        }
    }

    fun setCompressionRoute(route: PixelRoute): VendorCompressionControlResult {
        val mode = when (route) {
            PixelRoute.SBWC_AUTO -> COMPRESSION_AUTO
            PixelRoute.SBWC_REQUIRED -> COMPRESSION_SBWC_REQUIRED
            else -> COMPRESSION_LINEAR
        }
        return callRemote(
            executor = controlExecutor,
            fallback = VendorCompressionControlResult(
                applied = false,
                serviceSession = null,
            ),
            timeoutMs = CONTROL_TIMEOUT_MS,
        ) { remote, serviceSession ->
            VendorCompressionControlResult(
                applied = remote.setCompressionMode(mode),
                serviceSession = serviceSession,
            )
        }
    }

    /**
     * Acquires the API-v3 battery-saver suppression lease. This is a bounded blocking API and
     * must be called away from the main thread.
     *
     * A failed or timed-out begin is immediately superseded by a higher-version end. The end stays
     * in the atomic latest-wins drain even when the original Binder transaction returns late.
     */
    @Synchronized
    fun acquirePerformanceSession(
        requestedControls: Int = PERFORMANCE_CONTROL_DISABLE_BATTERY_SAVER,
    ): VendorPerformanceSessionResult {
        // A closed bridge with a pending process latch is unsafe, not merely unavailable. Report
        // the exact retained ticket first so callers cannot downgrade it to APP_ONLY monitoring.
        processPerformanceRestoreLatch.snapshot()?.let { existing ->
            return performanceResult(
                state = VendorPerformanceSessionState.FAILED,
                ticket = existing,
                detail = "A previous performance session is not confirmed restored",
            )
        }
        if (closed.get()) {
            return performanceResult(
                state = VendorPerformanceSessionState.UNAVAILABLE,
                detail = "Vendor bridge is closed",
            )
        }
        if (!validPerformanceControlRequest(requestedControls)) {
            return performanceResult(
                state = VendorPerformanceSessionState.FAILED,
                detail = "Unsupported performance control request",
            )
        }
        val serviceSession = currentServiceSession()
            ?: return performanceResult(
                state = VendorPerformanceSessionState.UNAVAILABLE,
                detail = "Vendor performance service is unavailable",
            )
        val ticket = VendorPerformanceSessionTicket(
            sessionId = nextVendorPerformanceSessionId(),
            commandVersion = nextPerformanceCommandVersion(),
            serviceSession = serviceSession,
            requestedControls = requestedControls,
            leaseDurationMs = PERFORMANCE_SESSION_LEASE_MS,
        )
        if (!processPerformanceRestoreLatch.arm(ticket)) {
            return performanceResult(
                state = VendorPerformanceSessionState.FAILED,
                ticket = processPerformanceRestoreLatch.snapshot(),
                detail = "A performance restore acknowledgment is still pending",
            )
        }
        val command = PerformanceCommand(
            type = PerformanceCommandType.BEGIN,
            ticket = ticket,
            minimumAppliedVersion = ticket.commandVersion,
        )
        if (!publishPerformanceCommand(command, allowClosed = false)) {
            // A rejected wake-up is not proof that the command never ran: a previously-running
            // drain may have taken it concurrently. Clear the process latch only after atomically
            // withdrawing this exact command from the pending slot.
            if (withdrawUndispatchedPerformanceCommand(command)) {
                processPerformanceRestoreLatch.confirmNeverMutated(ticket)
            }
            return performanceResult(
                state = VendorPerformanceSessionState.FAILED,
                ticket = processPerformanceRestoreLatch.snapshot(),
                detail = "Vendor performance lane rejected begin",
            )
        }
        val beginResult = awaitPerformanceCommand(command)
        if (beginResult.state == VendorPerformanceSessionState.ACTIVE) return beginResult
        if (
            beginResult.state == VendorPerformanceSessionState.UNAVAILABLE &&
            processPerformanceRestoreLatch.snapshot() == null
        ) {
            return beginResult
        }

        val restore = endPerformanceSessionInternal(
            ticket = ticket,
            allowClosed = false,
            reason = "begin did not produce a timely active acknowledgment",
        )
        return if (restore.state == VendorPerformanceSessionState.RESTORED) {
            performanceResult(
                state = VendorPerformanceSessionState.FAILED,
                detail = "${beginResult.detail}; restore acknowledged",
            )
        } else {
            performanceResult(
                state = VendorPerformanceSessionState.FAILED,
                ticket = restore.ticket ?: ticket,
                detail = "${beginResult.detail}; ${restore.detail}",
            )
        }
    }

    /**
     * Renews an active 10-second lease. Controllers should invoke this every
     * [PERFORMANCE_SESSION_RENEW_INTERVAL_MS].
     */
    @Synchronized
    fun renewPerformanceSession(
        ticket: VendorPerformanceSessionTicket,
    ): VendorPerformanceSessionResult {
        val current = activePerformanceTicket.get()
        if (closed.get() || !ticketsMatchExactly(current, ticket)) {
            return performanceResult(
                state = VendorPerformanceSessionState.FAILED,
                ticket = processPerformanceRestoreLatch.snapshot(),
                detail = "Performance renewal ticket is stale or inactive",
            )
        }
        if (currentServiceSession() != ticket.serviceSession) {
            return performanceResult(
                state = VendorPerformanceSessionState.FAILED,
                ticket = ticket,
                detail = "Vendor service changed before performance renewal",
            )
        }
        if (
            isMonotonicDeadlineReached(
                nowNanos = System.nanoTime(),
                deadlineNanos = performanceLeaseDeadlineNanos.get(),
            )
        ) {
            return endPerformanceAfterFailedCommand(
                ticket = ticket,
                failureDetail = "Performance lease expired before renewal",
            )
        }
        val renewedTicket = ticket.copy(commandVersion = nextPerformanceCommandVersion())
        if (!processPerformanceRestoreLatch.advance(renewedTicket)) {
            return performanceResult(
                state = VendorPerformanceSessionState.FAILED,
                ticket = processPerformanceRestoreLatch.snapshot(),
                detail = "Performance restore latch rejected renewal",
            )
        }
        val command = PerformanceCommand(
            type = PerformanceCommandType.RENEW,
            ticket = renewedTicket,
            minimumAppliedVersion = renewedTicket.commandVersion,
        )
        if (!publishPerformanceCommand(command, allowClosed = false)) {
            return endPerformanceAfterFailedCommand(
                ticket = renewedTicket,
                failureDetail = "Vendor performance lane rejected renewal",
            )
        }
        val result = awaitPerformanceCommand(command)
        return if (result.state == VendorPerformanceSessionState.ACTIVE) {
            result
        } else {
            endPerformanceAfterFailedCommand(
                ticket = renewedTicket,
                failureDetail = result.detail,
            )
        }
    }

    /**
     * Checks provider-confirmed session state on the same serialized lane as begin/renew/end.
     * A local lease deadline is never promoted to ACTIVE without the v3 provider acknowledgment.
     */
    @Synchronized
    fun performanceSessionHealth(
        ticket: VendorPerformanceSessionTicket,
    ): VendorPerformanceSessionResult {
        val current = activePerformanceTicket.get()
        if (closed.get() || !ticketsMatchExactly(current, ticket)) {
            return performanceResult(
                state = VendorPerformanceSessionState.FAILED,
                ticket = processPerformanceRestoreLatch.snapshot(),
                detail = "Performance health ticket is stale or inactive",
            )
        }
        if (currentServiceSession() != ticket.serviceSession) {
            return performanceResult(
                state = VendorPerformanceSessionState.FAILED,
                ticket = ticket,
                detail = "Vendor service changed during performance session",
            )
        }
        val healthTicket = ticket.copy(commandVersion = nextPerformanceCommandVersion())
        if (!processPerformanceRestoreLatch.advance(healthTicket)) {
            return performanceResult(
                state = VendorPerformanceSessionState.FAILED,
                ticket = processPerformanceRestoreLatch.snapshot(),
                detail = "Performance restore latch rejected health query",
            )
        }
        val command = PerformanceCommand(
            type = PerformanceCommandType.HEALTH,
            ticket = healthTicket,
            minimumAppliedVersion = activePerformanceAppliedVersion.get(),
        )
        if (!publishPerformanceCommand(command, allowClosed = false)) {
            return endPerformanceAfterFailedCommand(
                ticket = healthTicket,
                failureDetail = "Vendor performance lane rejected health query",
            )
        }
        val result = awaitPerformanceCommand(command)
        return if (
            result.state == VendorPerformanceSessionState.ACTIVE ||
            result.state == VendorPerformanceSessionState.RESTORED
        ) {
            result
        } else {
            endPerformanceAfterFailedCommand(
                ticket = healthTicket,
                failureDetail = result.detail,
            )
        }
    }

    @Synchronized
    fun endPerformanceSession(
        ticket: VendorPerformanceSessionTicket,
    ): VendorPerformanceSessionResult =
        endPerformanceSessionInternal(
            ticket = ticket,
            allowClosed = false,
            reason = "controller requested release",
        )

    /**
     * Activity-free process proof used to reconcile a controller whose cancellation raced the
     * return of BEGIN/END. Null means there is no unconfirmed vendor policy mutation.
     */
    fun pendingPerformanceRestoreTicket(): VendorPerformanceSessionTicket? =
        processPerformanceRestoreLatch.snapshot()

    fun snapshot(includeExtendedTelemetry: Boolean = true): VendorSnapshot? {
        val deadlineNanos =
            System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SNAPSHOT_TIMEOUT_MS)
        val baseSnapshot = callRemote<VendorSnapshot?>(
            executor = telemetryExecutor,
            fallback = null,
            timeoutMs = SNAPSHOT_TIMEOUT_MS,
        ) { remote, serviceSession ->
            val apiVersion = remote.apiVersion
            val counts: IntArray = remote.compositionLayerCounts ?: intArrayOf()
            VendorSnapshot(
                apiVersion = apiVersion,
                serviceSession = serviceSession,
                underrunCount = remote.dpuUnderrunCount.takeIf { it >= 0 },
                dpuUtilization = validVendorUtilizationPercent(remote.dpuUtilizationPercent),
                busUtilization = validVendorUtilizationPercent(remote.memoryBusUtilizationPercent),
                gpuUtilization = null,
                gpuFrequencyHz = null,
                dpuFrequencyHz = null,
                deviceLayers = counts.getOrNull(0)?.takeIf { it >= 0 },
                clientLayers = counts.getOrNull(1)?.takeIf { it >= 0 },
                compressionState = sanitizeVendorStatus(remote.lastCompressionState),
                npuStatus = sanitizeVendorStatus(remote.npuStatus),
            )
        }
        baseSnapshot ?: return null

        val extendedSnapshot = if (
            shouldReadVendorTelemetryV2(
                apiVersion = baseSnapshot.apiVersion,
                includeExtendedTelemetry = includeExtendedTelemetry,
            )
        ) {
            val remainingTimeoutMs = remainingVendorTelemetryTimeoutMs(
                deadlineNanos = deadlineNanos,
                nowNanos = System.nanoTime(),
            )
            if (remainingTimeoutMs > 0L) {
                callRemote<VendorTelemetryV2Snapshot?>(
                    executor = telemetryV2Executor,
                    fallback = null,
                    timeoutMs = remainingTimeoutMs,
                    requiredServiceSession = baseSnapshot.serviceSession,
                ) { remote, serviceSession ->
                    readVendorTelemetryV2(
                        serviceSession = serviceSession,
                        gpuUtilizationReader = { remote.gpuUtilizationPercent },
                        gpuFrequencyReader = { remote.gpuFrequencyHz },
                        dpuFrequencyReader = { remote.dpuFrequencyHz },
                    )
                }
            } else {
                null
            }
        } else {
            null
        }

        val merged = mergeVendorTelemetryV2(baseSnapshot, extendedSnapshot)
        // Splitting the transactions creates a second observation window. Do not return the
        // already-collected v1 values if the service registration changed during that window.
        return merged.takeIf {
            currentServiceSession() == baseSnapshot.serviceSession
        }
    }

    /**
     * Non-closing barrier for the process-session calibration boundary.
     *
     * The caller must first suppress new SystemMonitor samples. A timed-out Binder transaction
     * may ignore Future cancellation, so an empty caller/sample lane is not enough proof that the
     * v1/v2 telemetry workers stopped touching the vendor service.
     */
    internal fun awaitTelemetryQuiescent(timeoutMs: Long): Boolean =
        awaitVendorTelemetryLanesIdle(
            executors = listOf(
                telemetryExecutor,
                telemetryV2Executor,
                capabilityExecutor,
            ),
            timeoutMs = timeoutMs,
            maxTimeoutMs = MAX_CALIBRATION_TELEMETRY_QUIESCE_TIMEOUT_MS,
        )

    /**
     * Closes capability-query admission before the controller drains telemetry for the process
     * one-shot HWC candidate. Work admitted just before this token is acquired is included in the
     * subsequent quiescence barrier; all later discovery/retry requests collapse into one deferred
     * refresh.
     */
    internal fun acquireCalibrationCapabilityIsolation():
        VendorCapabilityIsolationToken? {
        if (closed.get()) return null
        val token = VendorCapabilityIsolationToken(
            nextNonZeroSequence(capabilityIsolationSequence),
        )
        if (!capabilityIsolationGate.acquire(token)) return null
        if (closed.get()) {
            capabilityIsolationGate.release(token)
            return null
        }

        val pendingRetry = capabilityRetryRunnable.getAndSet(null)
        if (pendingRetry != null) {
            capabilityIsolationGate.deferRefresh()
            mainHandler.removeCallbacks(pendingRetry)
        }
        if (deferredCapabilityRefreshScheduled.compareAndSet(true, false)) {
            capabilityIsolationGate.deferRefresh()
            mainHandler.removeCallbacks(deferredCapabilityRefreshRunnable)
        }
        return token
    }

    /**
     * Releases only the exact token. A deferred refresh is posted to the Handler after release, so
     * no Binder task can be admitted while the calibration owner is still visible.
     */
    internal fun releaseCalibrationCapabilityIsolation(
        token: VendorCapabilityIsolationToken,
    ): Boolean {
        val release = capabilityIsolationGate.release(token)
        if (!release.released) return false
        if (!release.refreshDeferred || closed.get()) return true
        if (postDeferredCapabilityRefresh()) return true
        capabilityIsolationGate.deferRefresh()
        return false
    }

    private fun endPerformanceAfterFailedCommand(
        ticket: VendorPerformanceSessionTicket,
        failureDetail: String,
    ): VendorPerformanceSessionResult {
        val restore = endPerformanceSessionInternal(
            ticket = ticket,
            allowClosed = false,
            reason = failureDetail,
        )
        return performanceResult(
            state = VendorPerformanceSessionState.FAILED,
            ticket = restore.ticket,
            detail = if (restore.state == VendorPerformanceSessionState.RESTORED) {
                "$failureDetail; restore acknowledged"
            } else {
                "$failureDetail; ${restore.detail}"
            },
        )
    }

    private fun endPerformanceSessionInternal(
        ticket: VendorPerformanceSessionTicket,
        allowClosed: Boolean,
        reason: String,
    ): VendorPerformanceSessionResult {
        if (closed.get() && !allowClosed) {
            return performanceResult(
                state = VendorPerformanceSessionState.FAILED,
                ticket = processPerformanceRestoreLatch.snapshot(),
                detail = "Vendor bridge closed before performance restore",
            )
        }
        val latched = processPerformanceRestoreLatch.snapshot()
            ?: return performanceResult(
                state = VendorPerformanceSessionState.RESTORED,
                detail = "Performance policy is already confirmed restored",
            )
        if (!ticketsReferToSameSession(latched, ticket)) {
            return performanceResult(
                state = VendorPerformanceSessionState.FAILED,
                ticket = latched,
                detail = "Performance release ticket belongs to another session",
            )
        }
        val endTicket = latched.copy(commandVersion = nextPerformanceCommandVersion())
        if (!processPerformanceRestoreLatch.advance(endTicket)) {
            return performanceResult(
                state = VendorPerformanceSessionState.FAILED,
                ticket = processPerformanceRestoreLatch.snapshot(),
                detail = "Performance restore latch rejected release",
            )
        }
        activePerformanceTicket.set(null)
        activePerformanceAppliedVersion.set(0L)
        performanceLeaseDeadlineNanos.set(0L)
        val command = PerformanceCommand(
            type = PerformanceCommandType.END,
            ticket = endTicket,
            minimumAppliedVersion = endTicket.commandVersion,
            reason = reason,
        )
        if (!publishPerformanceCommand(command, allowClosed = allowClosed)) {
            return performanceResult(
                state = VendorPerformanceSessionState.FAILED,
                ticket = endTicket,
                detail = "Vendor performance lane rejected release",
            )
        }
        return awaitPerformanceCommand(command)
    }

    private fun publishPerformanceCommand(
        command: PerformanceCommand,
        allowClosed: Boolean,
    ): Boolean {
        if (closed.get() && !allowClosed && command.type != PerformanceCommandType.END) {
            return false
        }
        while (true) {
            val current = desiredPerformanceCommand.get()
            if (
                current != null &&
                !isNewerSequence(command.ticket.commandVersion, current.ticket.commandVersion)
            ) {
                command.complete(
                    performanceResult(
                        state = VendorPerformanceSessionState.FAILED,
                        ticket = command.ticket,
                        detail = "Performance command version is stale",
                    ),
                )
                return false
            }
            if (desiredPerformanceCommand.compareAndSet(current, command)) {
                current?.complete(
                    performanceResult(
                        state = VendorPerformanceSessionState.FAILED,
                        ticket = current.ticket,
                        detail = "Performance command was superseded",
                    ),
                )
                break
            }
        }
        while (true) {
            val pending = pendingPerformanceCommand.get()
            if (
                pending != null &&
                !isNewerSequence(command.ticket.commandVersion, pending.ticket.commandVersion)
            ) {
                return false
            }
            if (pendingPerformanceCommand.compareAndSet(pending, command)) {
                pending?.complete(
                    performanceResult(
                        state = VendorPerformanceSessionState.FAILED,
                        ticket = pending.ticket,
                        detail = "Performance command was replaced by a newer command",
                    ),
                )
                break
            }
        }
        return schedulePerformanceDrain(allowClosed)
    }

    private fun schedulePerformanceDrain(allowClosed: Boolean): Boolean {
        if (closed.get() && !allowClosed && pendingPerformanceCommand.get()?.type != PerformanceCommandType.END) {
            return false
        }
        if (performanceExecutor.isShutdown) return false
        return try {
            performanceExecutor.execute(::drainPerformanceCommands)
            true
        } catch (_: RejectedExecutionException) {
            // A rejection while the one-slot queue is occupied means a wake-up is already
            // guaranteed. The command itself was atomically replaced above, so no extra task is
            // needed and no stale command queue is created.
            !performanceExecutor.isShutdown && performanceExecutor.queue.isNotEmpty()
        }
    }

    private fun withdrawUndispatchedPerformanceCommand(
        command: PerformanceCommand,
    ): Boolean {
        if (!pendingPerformanceCommand.compareAndSet(command, null)) return false
        desiredPerformanceCommand.compareAndSet(command, null)
        command.complete(
            performanceResult(
                state = VendorPerformanceSessionState.FAILED,
                detail = "Performance command was withdrawn before dispatch",
            ),
        )
        return true
    }

    private fun drainPerformanceCommands() {
        while (true) {
            val command = pendingPerformanceCommand.getAndSet(null) ?: return
            applyPerformanceCommand(command)
        }
    }

    private fun applyPerformanceCommand(command: PerformanceCommand) {
        val target = synchronized(connectionLock) {
            val remote = service ?: return@synchronized null
            RemoteCallTarget(
                remote = remote,
                binder = serviceBinder,
                serviceSession = serviceGeneration.get(),
            )
        }
        if (target == null || target.serviceSession != command.ticket.serviceSession) {
            // No remote mutation was invoked. A BEGIN that lost its registration before dispatch
            // cannot have disabled Battery Saver, so do not poison the process-wide restore latch.
            // RENEW/HEALTH/END remain sticky because they refer to a session that may already have
            // been active in the previous provider registration.
            if (command.type == PerformanceCommandType.BEGIN) {
                processPerformanceRestoreLatch.confirmNeverMutated(command.ticket)
            }
            command.complete(
                performanceResult(
                    state = if (command.type == PerformanceCommandType.BEGIN) {
                        VendorPerformanceSessionState.UNAVAILABLE
                    } else {
                        VendorPerformanceSessionState.FAILED
                    },
                    ticket = if (command.type == PerformanceCommandType.BEGIN) {
                        null
                    } else {
                        command.ticket
                    },
                    detail =
                        "Vendor performance service session is unavailable or changed before " +
                            "the command could be dispatched",
                ),
            )
            return
        }

        var mutatingTransactionStarted = false
        val result = try {
            val apiVersion = target.remote.apiVersion
            if (!supportsVendorPerformanceSession(apiVersion)) {
                if (command.type == PerformanceCommandType.BEGIN) {
                    processPerformanceRestoreLatch.confirmNeverMutated(command.ticket)
                }
                performanceResult(
                    state = VendorPerformanceSessionState.UNAVAILABLE,
                    ticket = null,
                    detail = "Vendor service API v3 performance sessions are unavailable",
                )
            } else {
                when (command.type) {
                    PerformanceCommandType.BEGIN -> {
                        mutatingTransactionStarted = true
                        val acknowledgedVersion = target.remote.beginPerformanceSession(
                            performanceClientToken,
                            command.ticket.sessionId,
                            command.ticket.commandVersion,
                            command.ticket.requestedControls,
                            command.ticket.leaseDurationMs,
                        )
                        performanceMutationResult(
                            command = command,
                            acknowledgedVersion = acknowledgedVersion,
                            target = target,
                            successState = VendorPerformanceSessionState.ACTIVE,
                            successDetail = "Performance session acquired",
                        )
                    }

                    PerformanceCommandType.RENEW -> {
                        mutatingTransactionStarted = true
                        val acknowledgedVersion = target.remote.renewPerformanceSession(
                            performanceClientToken,
                            command.ticket.sessionId,
                            command.ticket.commandVersion,
                            command.ticket.leaseDurationMs,
                        )
                        performanceMutationResult(
                            command = command,
                            acknowledgedVersion = acknowledgedVersion,
                            target = target,
                            successState = VendorPerformanceSessionState.ACTIVE,
                            successDetail = "Performance session renewed",
                        )
                    }

                    PerformanceCommandType.HEALTH -> {
                        val state = target.remote.getPerformanceSessionState(
                            performanceClientToken,
                            command.ticket.sessionId,
                            command.minimumAppliedVersion,
                        )
                        when {
                            !isCurrentRemoteCallTarget(target, allowClosed = false) ->
                                performanceResult(
                                    state = VendorPerformanceSessionState.FAILED,
                                    ticket = command.ticket,
                                    detail = "Vendor service changed during health query",
                                )

                            state == PERFORMANCE_SESSION_REMOTE_ACTIVE ->
                                if (
                                    isMonotonicDeadlineReached(
                                        nowNanos = System.nanoTime(),
                                        deadlineNanos = performanceLeaseDeadlineNanos.get(),
                                    )
                                ) {
                                    performanceResult(
                                        state = VendorPerformanceSessionState.FAILED,
                                        ticket = command.ticket,
                                        detail =
                                            "Provider reports active after the local lease deadline",
                                    )
                                } else {
                                    performanceResult(
                                        state = VendorPerformanceSessionState.ACTIVE,
                                        ticket = command.ticket,
                                        detail = "Performance session is active",
                                    )
                                }

                            state == PERFORMANCE_SESSION_REMOTE_RESTORED -> {
                                if (
                                    processPerformanceRestoreLatch.confirmRestored(command.ticket)
                                ) {
                                    activePerformanceTicket.set(null)
                                    activePerformanceAppliedVersion.set(0L)
                                    performanceLeaseDeadlineNanos.set(0L)
                                    performanceResult(
                                        state = VendorPerformanceSessionState.RESTORED,
                                        detail =
                                            "Provider confirmed performance policy restored",
                                    )
                                } else {
                                    performanceResult(
                                        state = VendorPerformanceSessionState.FAILED,
                                        ticket = processPerformanceRestoreLatch.snapshot(),
                                        detail =
                                            "Inactive health response did not match latest command",
                                    )
                                }
                            }

                            else -> performanceResult(
                                state = VendorPerformanceSessionState.FAILED,
                                ticket = command.ticket,
                                detail = "Provider could not verify performance session health",
                            )
                        }
                    }

                    PerformanceCommandType.END -> {
                        mutatingTransactionStarted = true
                        val acknowledgedVersion = target.remote.endPerformanceSession(
                            performanceClientToken,
                            command.ticket.sessionId,
                            command.ticket.commandVersion,
                        )
                        performanceMutationResult(
                            command = command,
                            acknowledgedVersion = acknowledgedVersion,
                            target = target,
                            successState = VendorPerformanceSessionState.RESTORED,
                            successDetail = "Performance policy restore acknowledged",
                        )
                    }
                }
            }
        } catch (_: Exception) {
            if (
                command.type == PerformanceCommandType.BEGIN &&
                !mutatingTransactionStarted
            ) {
                processPerformanceRestoreLatch.confirmNeverMutated(command.ticket)
            }
            performanceResult(
                state = VendorPerformanceSessionState.FAILED,
                ticket = command.ticket,
                detail = if (mutatingTransactionStarted) {
                    "Vendor performance mutation failed with unknown applied state"
                } else {
                    "Vendor performance API query failed before mutation"
                },
            )
        }

        if (result.state == VendorPerformanceSessionState.ACTIVE) {
            activePerformanceTicket.set(command.ticket)
            if (command.type != PerformanceCommandType.HEALTH) {
                activePerformanceAppliedVersion.set(command.ticket.commandVersion)
                performanceLeaseDeadlineNanos.set(
                    monotonicDeadlineAfter(
                        System.nanoTime(),
                        TimeUnit.MILLISECONDS.toNanos(command.ticket.leaseDurationMs),
                    ),
                )
            }
        } else if (result.state == VendorPerformanceSessionState.RESTORED) {
            activePerformanceTicket.set(null)
            activePerformanceAppliedVersion.set(0L)
            performanceLeaseDeadlineNanos.set(0L)
        }
        val latest = desiredPerformanceCommand.get()
        if (
            latest != null &&
            latest.ticket.commandVersion != command.ticket.commandVersion
        ) {
            if (
                result.state == VendorPerformanceSessionState.ACTIVE &&
                activePerformanceTicket.compareAndSet(command.ticket, null)
            ) {
                activePerformanceAppliedVersion.set(0L)
                performanceLeaseDeadlineNanos.set(0L)
            }
            command.complete(
                performanceResult(
                    state = VendorPerformanceSessionState.FAILED,
                    ticket = command.ticket,
                    detail = "Performance command completed after being superseded",
                ),
            )
            return
        }
        command.complete(result)
    }

    private fun performanceMutationResult(
        command: PerformanceCommand,
        acknowledgedVersion: Long,
        target: RemoteCallTarget,
        successState: VendorPerformanceSessionState,
        successDetail: String,
    ): VendorPerformanceSessionResult {
        if (
            acknowledgedVersion != command.ticket.commandVersion ||
            !isCurrentRemoteCallTarget(
                target = target,
                allowClosed = command.type == PerformanceCommandType.END,
            )
        ) {
            return performanceResult(
                state = VendorPerformanceSessionState.FAILED,
                ticket = command.ticket,
                detail = "Vendor performance acknowledgment was stale or unbound",
            )
        }
        if (successState == VendorPerformanceSessionState.RESTORED) {
            if (!processPerformanceRestoreLatch.confirmRestoredByEnd(command.ticket)) {
                return performanceResult(
                    state = VendorPerformanceSessionState.FAILED,
                    ticket = processPerformanceRestoreLatch.snapshot(),
                    detail = "Restore acknowledgment did not match the active session",
                )
            }
            return performanceResult(
                state = VendorPerformanceSessionState.RESTORED,
                detail = successDetail,
            )
        }
        return performanceResult(
            state = successState,
            ticket = command.ticket,
            detail = successDetail,
        )
    }

    private fun awaitPerformanceCommand(
        command: PerformanceCommand,
        timeoutMs: Long = PERFORMANCE_COMMAND_ACK_TIMEOUT_MS,
    ): VendorPerformanceSessionResult {
        val deadlineNanos = monotonicDeadlineAfter(
            System.nanoTime(),
            TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceIn(1L, PERFORMANCE_MAX_WAIT_MS)),
        )
        while (true) {
            command.result.get()?.let { return it }
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0L) {
                return performanceResult(
                    state = VendorPerformanceSessionState.PENDING,
                    ticket = command.ticket,
                    detail = "Performance command acknowledgment timed out",
                )
            }
            LockSupport.parkNanos(
                remainingNanos.coerceAtMost(PERFORMANCE_ACK_POLL_NANOS),
            )
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt()
                return performanceResult(
                    state = VendorPerformanceSessionState.FAILED,
                    ticket = command.ticket,
                    detail = "Performance command wait was interrupted",
                )
            }
        }
    }

    private fun nextPerformanceCommandVersion(): Long =
        nextNonZeroSequence(performanceCommandVersion)

    @Synchronized
    fun closeWithResult(
        resetCompression: Boolean = true,
    ): VendorShutdownResult {
        // Shutdown acknowledgements are evidence for the caller that actually performed this
        // bridge cleanup. A quarantined closed singleton can be returned to a later controller
        // to avoid spawning another stuck Binder lane; never let that controller reuse an old
        // stop/reset acknowledgement as proof for its own backend.
        if (shutdownResult.get() != null) return unattributedVendorShutdownResult()
        if (!closed.compareAndSet(false, true)) {
            return unattributedVendorShutdownResult()
        }
        mainHandler.removeCallbacks(reconnectRunnable)
        reconnectScheduled.set(false)
        cancelCapabilityRetry(resetAttempt = false)
        if (deferredCapabilityRefreshScheduled.compareAndSet(true, false)) {
            mainHandler.removeCallbacks(deferredCapabilityRefreshRunnable)
        }
        activeCapabilityQuery.get()?.let { query ->
            query.expired.set(true)
            mainHandler.removeCallbacks(query.timeoutRunnable)
        }
        val brokerWasConnected = service != null
        processPerformanceRestoreLatch.snapshot()?.let { ticket ->
            endPerformanceSessionInternal(
                ticket = ticket,
                allowClosed = true,
                reason = "vendor bridge close",
            )
        }
        // Graceful shutdown lets an in-flight late begin return and consume the already-published
        // higher-version END from the same drain. Do not cancel/remove that safety command.
        performanceExecutor.shutdown()
        val performanceLaneQuiesced = try {
            performanceExecutor.awaitTermination(
                PERFORMANCE_CLOSE_QUIESCE_TIMEOUT_MS,
                TimeUnit.MILLISECONDS,
            )
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!performanceLaneQuiesced) {
            performanceExecutor.shutdownNow()
        }
        if (
            performanceExecutor.isTerminated &&
            processPerformanceRestoreLatch.snapshot() == null
        ) {
            clearRestoredPerformanceState()
        }
        val closeTicket = npuCommandAcknowledgments.recordPending(
            version = npuCommandVersion.incrementAndGet(),
            serviceSession = currentServiceSession(),
        )
        desiredNpuCommand.set(
            NpuCommand(
                intensity = 0f,
                shape = LoadShape.STEADY,
                ticket = closeTicket,
            ),
        )
        pendingNpuCommand.set(null)
        // Discard stale telemetry/NPU work. Safety control uses a dedicated lane so a healthy,
        // merely slow snapshot cannot prevent shutdown reset from even starting.
        telemetryExecutor.queue.clear()
        telemetryV2Executor.queue.clear()
        capabilityExecutor.queue.clear()
        npuExecutor.queue.clear()
        controlExecutor.queue.clear()
        npuExecutor.shutdownNow()
        val npuLaneQuiesced = try {
            npuExecutor.awaitTermination(NPU_QUIESCE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        var npuStopAcknowledged = false
        var compressionResetConfirmed = false
        var shutdownFatal: Error? = null
        repeat(SHUTDOWN_RESET_ATTEMPTS) {
            if (npuStopAcknowledged && (!resetCompression || compressionResetConfirmed)) {
                return@repeat
            }
            val result = try {
                callRemote(
                    executor = controlExecutor,
                    fallback = RemoteResetResult(),
                    timeoutMs = CONTROL_TIMEOUT_MS,
                    allowClosed = true,
                ) { remote, _ ->
                    val npuStopped = remoteValueOrNull {
                        remote.stopNpuLoad()
                        true
                    } ?: false
                    val compressionReset = if (resetCompression) {
                        remoteValueOrNull {
                            remote.setCompressionMode(COMPRESSION_LINEAR)
                        } ?: false
                    } else {
                        false
                    }
                    RemoteResetResult(
                        npuStopConfirmed = npuStopped,
                        compressionResetConfirmed = compressionReset,
                    )
                }
            } catch (error: Error) {
                val first = shutdownFatal
                if (first == null) {
                    shutdownFatal = error
                } else if (first !== error) {
                    try {
                        first.addSuppressed(error)
                    } catch (_: Throwable) {
                        // Preserve the first fatal while continuing bounded safety cleanup.
                    }
                }
                RemoteResetResult()
            }
            npuStopAcknowledged = npuStopAcknowledged || result.npuStopConfirmed
            compressionResetConfirmed =
                compressionResetConfirmed || result.compressionResetConfirmed
        }
        unbindCurrentBinding()
        clearCurrentService()
        telemetryExecutor.shutdownNow()
        telemetryV2Executor.shutdownNow()
        capabilityExecutor.shutdownNow()
        controlExecutor.shutdownNow()
        val auxiliaryLanesQuiesced = awaitExecutorTerminationTogether(
            executors = listOf(
                telemetryExecutor,
                telemetryV2Executor,
                capabilityExecutor,
                controlExecutor,
                performanceExecutor,
            ),
            timeoutMs = REMOTE_LANE_QUIESCE_TIMEOUT_MS,
        )
        // The synchronous wait may time out while the serialized late-BEGIN -> END chain still
        // completes during executor quiescence. The exact-version process latch is the final proof.
        val performanceRestoreConfirmed =
            processPerformanceRestoreLatch.snapshot() == null
        val safeToRestart =
            auxiliaryLanesQuiesced &&
                npuExecutor.isTerminated &&
                performanceExecutor.isTerminated &&
                performanceRestoreConfirmed
        restartSafe.set(safeToRestart)
        if (safeToRestart) {
            synchronized(Companion) {
                if (instance === this) instance = null
            }
        }
        val result = VendorShutdownResult(
            brokerWasConnected = brokerWasConnected,
            // A stop response is not final if an older client set(nonzero) transaction can still
            // complete afterward on the provider Binder pool.
            npuStopConfirmed = npuStopAcknowledged && npuLaneQuiesced,
            compressionResetConfirmed = compressionResetConfirmed,
            performanceRestoreConfirmed = performanceRestoreConfirmed,
        ).also(shutdownResult::set)
        shutdownFatal?.let { throw it }
        return result
    }

    override fun close() {
        closeWithResult()
    }

    /**
     * Waits for late Binder lanes to terminate and for the exact performance restore latch to
     * clear, then detaches this closed singleton. The wait is capped and parks between checks.
     */
    fun awaitRestartSafeAfterClose(timeoutMs: Long): Boolean {
        if (!closed.get()) return false
        return awaitBoundedCondition(
            timeoutMs = timeoutMs,
            maxTimeoutMs = MAX_RESTART_SAFE_WAIT_MS,
            pollNanos = RESTART_SAFE_POLL_NANOS,
            condition = ::canRestartAfterClose,
        )
    }

    /**
     * Latest-wins control drain. A phase ramp can update its setpoint every 100 ms, while a
     * product Binder call may be slower. Keeping at most one pending command prevents an
     * unbounded executor queue and ensures release-to-zero supersedes stale ramp values.
     */
    private fun scheduleNpuDrain(): Boolean {
        if (closed.get()) return false
        if (!npuDrainScheduled.compareAndSet(false, true)) return true
        var accepted = try {
            npuExecutor.execute(::drainNpuCommands)
            true
        } catch (_: RejectedExecutionException) {
            false
        }
        if (!accepted && pendingNpuCommand.get()?.intensity == 0f && !closed.get()) {
            // STOP/release-to-zero is safety-significant. Evict one stale queued NPU task and
            // reserve that slot for the latest-wins drain.
            (npuExecutor.queue.poll() as? java.util.concurrent.Future<*>)?.cancel(true)
            npuExecutor.purge()
            accepted = try {
                npuExecutor.execute(::drainNpuCommands)
                true
            } catch (_: RejectedExecutionException) {
                false
            }
        }
        if (!accepted) {
            npuDrainScheduled.set(false)
            // Keep the latest command so a later setpoint update can retry scheduling. In
            // particular, never erase a release-to-zero merely because telemetry filled the
            // bounded queue.
            pendingNpuCommand.get()?.let { pending ->
                npuCommandAcknowledgments.recordFailed(
                    pending.ticket,
                    "Vendor NPU control executor rejected command",
                )
            }
        }
        return accepted
    }

    private fun drainNpuCommands() {
        var failedCommandVersion: Long? = null
        var failedCommand: NpuCommand? = null
        var allowSameVersionRetry = false
        try {
            while (!closed.get()) {
                val target = synchronized(connectionLock) {
                    val remote = service ?: return@synchronized null
                    RemoteCallTarget(
                        remote = remote,
                        binder = serviceBinder,
                        serviceSession = serviceGeneration.get(),
                    )
                } ?: break
                if (npuSupported != true) {
                    pendingNpuCommand.set(null)
                    break
                }
                val command = pendingNpuCommand.getAndSet(null) ?: break
                if (command.ticket.serviceSession != target.serviceSession) {
                    npuCommandAcknowledgments.recordFailed(
                        command.ticket,
                        "Vendor NPU service session changed before apply",
                    )
                    continue
                }
                val inFlight = npuCommandAcknowledgments.recordStarted(command.ticket)
                val applied = try {
                    remoteValueOrNull {
                        if (command.intensity <= 0f) {
                            target.remote.stopNpuLoad()
                        } else {
                            target.remote.setNpuLoad(
                                command.intensity,
                                command.shape.wireValue(),
                            )
                        }
                        true
                    } == true && isCurrentRemoteCallTarget(target, allowClosed = false)
                } finally {
                    // Error is deliberately not caught: publish terminal ticket ownership first,
                    // then let the executor/process failure propagate to the lifecycle gate.
                    npuCommandAcknowledgments.recordFinished(inFlight)
                }
                if (applied) {
                    npuCommandAcknowledgments.recordApplied(command.ticket)
                } else {
                    // Preserve the newest desired setpoint for the next connection/setpoint
                    // event. A command posted while this Binder call was in flight, especially
                    // release-to-zero, must be drained immediately. Retry suppression applies
                    // only when the failed command is still the newest request.
                    val desired = desiredNpuCommand.get()
                    publishPendingNpuCommand(desired)
                    failedCommandVersion = command.version
                    failedCommand = command
                    // A vendor setter is idempotent. One bounded retry improves load fidelity for
                    // transient Binder/provider failures without creating a backlog or retry loop.
                    allowSameVersionRetry =
                        retriedCommandVersion.getAndSet(command.version) != command.version
                    break
                }
            }
        } finally {
            // Clear the ownership bit before re-reading pending. This ordering closes both
            // producer-before-clear and producer-after-clear lost-wakeup windows.
            npuDrainScheduled.set(false)
            val pending = pendingNpuCommand.get()
            val failedVersion = failedCommandVersion
            val retryEligible = shouldRetryPendingNpuCommand(
                failedVersion = failedVersion,
                pendingVersion = pending?.version,
                allowSameVersionRetry = allowSameVersionRetry,
            )
            if (
                retryEligible &&
                !closed.get() &&
                service != null &&
                npuSupported == true
            ) {
                if (!scheduleNpuDrain()) {
                    pending?.let {
                        npuCommandAcknowledgments.recordFailed(
                            it.ticket,
                            "Vendor NPU retry executor rejected command",
                        )
                    }
                }
            } else {
                failedCommand?.let { failed ->
                    npuCommandAcknowledgments.recordFailed(
                        failed.ticket,
                        "Vendor NPU setter failed after bounded retry",
                    )
                }
            }
        }
    }

    private fun refreshCapabilities(
        remote: IDpuLabVendorService,
        connection: ServiceConnection,
        bindingGeneration: Long,
        binder: IBinder,
        serviceGeneration: Long,
    ) {
        val queryTargets = synchronized(connectionLock) {
            if (
                closed.get() ||
                !isCurrentBindingLocked(connection, bindingGeneration) ||
                serviceBinder !== binder ||
                this.serviceGeneration.get() != serviceGeneration
            ) {
                null
            } else {
                CapabilityQueryTargets(
                    queryNpu = npuSupported == null,
                    querySbwc = sbwcSupported == null,
                )
            }
        } ?: return
        if (!queryTargets.queryNpu && !queryTargets.querySbwc) {
            cancelCapabilityRetryForService(
                connection = connection,
                bindingGeneration = bindingGeneration,
                binder = binder,
                serviceGeneration = serviceGeneration,
                resetAttempt = true,
            )
            return
        }
        val query = CapabilityQuery(
            remote = remote,
            connection = connection,
            bindingGeneration = bindingGeneration,
            binder = binder,
            serviceGeneration = serviceGeneration,
            targets = queryTargets,
            deadlineNanos = monotonicDeadlineAfter(
                System.nanoTime(),
                TimeUnit.MILLISECONDS.toNanos(CAPABILITY_QUERY_TIMEOUT_MS),
            ),
        )
        val admitted = capabilityIsolationGate.runIfOpenOrDefer {
            if (!activeCapabilityQuery.compareAndSet(null, query)) {
                return@runIfOpenOrDefer false
            }
            var accepted = false
            var admissionFailure: Throwable? = null
            try {
                if (
                    !mainHandler.postDelayed(
                        query.timeoutRunnable,
                        CAPABILITY_QUERY_TIMEOUT_MS,
                    )
                ) {
                    return@runIfOpenOrDefer false
                }
                capabilityExecutor.execute(query)
                accepted = true
                true
            } catch (error: Throwable) {
                admissionFailure = error
                false
            } finally {
                if (!accepted) {
                    var rollbackFailure: Throwable? = null
                    try {
                        mainHandler.removeCallbacks(query.timeoutRunnable)
                    } catch (error: Throwable) {
                        rollbackFailure = error
                    } finally {
                        activeCapabilityQuery.compareAndSet(query, null)
                    }
                    fatalCapabilityAdmissionFailure(
                        admissionFailure = admissionFailure,
                        rollbackFailure = rollbackFailure,
                    )?.let { throw it }
                }
            }
        }
        if (!admitted) {
            recoverDeferredCapabilityRefreshAfterAdmissionFailure(
                gate = capabilityIsolationGate,
                activeQueryPresent = activeCapabilityQuery.get() != null,
                scheduleRetry = {
                    postDeferredCapabilityRefresh(
                        delayMs = CAPABILITY_LANE_HANDOFF_RETRY_DELAY_MS,
                    )
                },
            )
        }
    }

    private fun executeCapabilityQuery(query: CapabilityQuery) {
        var fatal: Throwable? = null
        val outcome = try {
            if (
                closed.get() ||
                !isCurrentService(
                    connection = query.connection,
                    bindingGeneration = query.bindingGeneration,
                    binder = query.binder,
                    serviceGeneration = query.serviceGeneration,
                )
            ) {
                null
            } else {
                queryCapabilitiesBeforeDeadline(
                    queryNpu = query.targets.queryNpu,
                    querySbwc = query.targets.querySbwc,
                    deadlineNanos = query.deadlineNanos,
                    isExpired = {
                        query.expired.get() ||
                            closed.get() ||
                            !isCurrentService(
                                connection = query.connection,
                                bindingGeneration = query.bindingGeneration,
                                binder = query.binder,
                                serviceGeneration = query.serviceGeneration,
                            )
                    },
                    readNpu = { query.remote.isNpuLoadSupported },
                    readSbwc = { query.remote.isSbwcControlSupported },
                )
            }
        } catch (error: Throwable) {
            fatal = error
            null
        }
        try {
            completeCapabilityQuery(query, outcome)
        } finally {
            fatal?.let { throw it }
        }
    }

    private fun completeCapabilityQuery(
        query: CapabilityQuery,
        outcome: CapabilityQueryOutcome?,
    ) {
        mainHandler.removeCallbacks(query.timeoutRunnable)
        if (!activeCapabilityQuery.compareAndSet(query, null)) return

        var retryOriginalRegistration = false
        if (
            outcome != null &&
            outcome.completedWithinDeadline &&
            !query.expired.get()
        ) {
            var merged: CapabilityDiscoveryMerge? = null
            val applied = capabilityIsolationGate.runIfOpenOrDefer {
                merged = publishCapabilityDiscovery(query, outcome)
                merged != null
            }
            if (applied) {
                val accepted = checkNotNull(merged)
                if (accepted.complete) {
                    // A Binder connection is not considered healthy until both capability
                    // queries have produced verified true/false results. Resetting on the raw
                    // connection callback would turn a permanently broken broker into a tight
                    // 250 ms reconnect loop.
                    reconnectAttempt.set(0L)
                    cancelCapabilityRetryForService(
                        connection = query.connection,
                        bindingGeneration = query.bindingGeneration,
                        binder = query.binder,
                        serviceGeneration = query.serviceGeneration,
                        resetAttempt = true,
                    )
                } else {
                    retryOriginalRegistration = true
                }
            }
        } else if (
            !closed.get() &&
            isCurrentService(
                connection = query.connection,
                bindingGeneration = query.bindingGeneration,
                binder = query.binder,
                serviceGeneration = query.serviceGeneration,
            )
        ) {
            // The result deadline bounds acceptance, not the provider's process. The no-backlog
            // lane remains quarantined until this exact Binder call really returns, and only then
            // may one retry be scheduled.
            retryOriginalRegistration = true
        } else {
            capabilityIsolationGate.deferRefresh()
        }

        if (capabilityIsolationGate.consumeDeferredRefreshIfOpen()) {
            if (!postDeferredCapabilityRefresh()) {
                capabilityIsolationGate.deferRefresh()
            }
        } else if (retryOriginalRegistration) {
            scheduleCapabilityRetry(
                remote = query.remote,
                connection = query.connection,
                bindingGeneration = query.bindingGeneration,
                binder = query.binder,
                serviceGeneration = query.serviceGeneration,
            )
        }
    }

    private fun publishCapabilityDiscovery(
        query: CapabilityQuery,
        outcome: CapabilityQueryOutcome,
    ): CapabilityDiscoveryMerge? {
        val merged = synchronized(connectionLock) {
            if (
                closed.get() ||
                !isCurrentBindingLocked(query.connection, query.bindingGeneration) ||
                serviceBinder !== query.binder ||
                serviceGeneration.get() != query.serviceGeneration
            ) {
                null
            } else {
                mergeCapabilityDiscovery(
                    currentNpu = npuSupported,
                    currentSbwc = sbwcSupported,
                    queriedNpu = outcome.npuSupported,
                    queriedSbwc = outcome.sbwcSupported,
                ).also { result ->
                    npuSupported = result.npuSupported
                    sbwcSupported = result.sbwcSupported
                }
            }
        } ?: return null
        if (merged.npuBecameKnown) {
            if (merged.npuSupported == true) {
                replayDesiredNpuCommand()
                scheduleNpuDrain()
            } else {
                pendingNpuCommand.set(null)
                val desired = desiredNpuCommand.get()
                if (desired.intensity > 0f) {
                    npuCommandAcknowledgments.recordFailed(
                        desired.ticket,
                        "Vendor service reported NPU workload control unsupported",
                    )
                }
            }
        }
        return merged
    }

    private fun refreshCurrentCapabilities() {
        val target = synchronized(connectionLock) {
            val remote = service ?: return
            val connection = activeConnection ?: return
            val binder = serviceBinder ?: return
            CapabilityRefreshTarget(
                remote = remote,
                connection = connection,
                bindingGeneration = activeBindingGeneration,
                binder = binder,
                serviceGeneration = serviceGeneration.get(),
            )
        }
        refreshCapabilities(
            remote = target.remote,
            connection = target.connection,
            bindingGeneration = target.bindingGeneration,
            binder = target.binder,
            serviceGeneration = target.serviceGeneration,
        )
    }

    private fun postDeferredCapabilityRefresh(delayMs: Long = 0L): Boolean {
        if (closed.get()) return false
        if (!deferredCapabilityRefreshScheduled.compareAndSet(false, true)) return true
        val posted = if (delayMs <= 0L) {
            mainHandler.post(deferredCapabilityRefreshRunnable)
        } else {
            mainHandler.postDelayed(deferredCapabilityRefreshRunnable, delayMs)
        }
        if (posted) return true
        deferredCapabilityRefreshScheduled.set(false)
        return false
    }

    private fun handleTerminalBindingLoss(
        expectedConnection: ServiceConnection,
        bindingGeneration: Long,
        expectedBinder: IBinder?,
    ) {
        val cleanup = synchronized(connectionLock) {
            if (
                !isCurrentBindingLocked(expectedConnection, bindingGeneration) ||
                (expectedBinder != null && serviceBinder !== expectedBinder)
            ) {
                null
            } else {
                TerminalBindingCleanup(
                    binder = serviceBinder,
                    deathRecipient = deathRecipient,
                    connection = activeConnection,
                    shouldUnbind = bound.getAndSet(false),
                    capabilityRetry = capabilityRetryRunnable.getAndSet(null),
                ).also {
                    service = null
                    serviceBinder = null
                    deathRecipient = null
                    npuSupported = null
                    sbwcSupported = null
                    activeConnection = null
                    activeBindingGeneration = 0L
                    binding.set(false)
                    capabilityRetryAttempt.set(0L)
                }
            }
        } ?: return
        unlinkDeathRecipient(cleanup.binder, cleanup.deathRecipient)
        cleanup.capabilityRetry?.let(mainHandler::removeCallbacks)
        if (cleanup.shouldUnbind) {
            cleanup.connection?.let { connection ->
                try {
                    context.unbindService(connection)
                } catch (_: Exception) {
                    // A terminal framework callback may already have removed the registration.
                }
            }
        }
        val desired = desiredNpuCommand.get()
        if (desired.intensity > 0f) {
            npuCommandAcknowledgments.recordFailed(
                desired.ticket,
                "Vendor NPU Binder registration lost",
            )
        }
        publishPendingNpuCommand(desired)
        if (!closed.get()) scheduleReconnect()
    }

    private fun clearCurrentService(
        expectedConnection: ServiceConnection? = null,
        bindingGeneration: Long? = null,
        expectedBinder: IBinder? = null,
    ): Boolean {
        var cleared = false
        var binderToUnlink: IBinder? = null
        var recipientToUnlink: IBinder.DeathRecipient? = null
        var capabilityRetryToCancel: Runnable? = null
        synchronized(connectionLock) {
            val connectionMatches =
                expectedConnection == null ||
                    (
                        activeConnection === expectedConnection &&
                            activeBindingGeneration == bindingGeneration
                        )
            if (
                connectionMatches &&
                (expectedBinder == null || serviceBinder === expectedBinder)
            ) {
                binderToUnlink = serviceBinder
                recipientToUnlink = deathRecipient
                service = null
                serviceBinder = null
                deathRecipient = null
                npuSupported = null
                sbwcSupported = null
                capabilityRetryToCancel = capabilityRetryRunnable.getAndSet(null)
                capabilityRetryAttempt.set(0L)
                cleared = true
            }
        }
        unlinkDeathRecipient(binderToUnlink, recipientToUnlink)
        capabilityRetryToCancel?.let(mainHandler::removeCallbacks)
        return cleared
    }

    private fun markBindingConnected(
        connection: ServiceConnection,
        generation: Long,
    ): Boolean = synchronized(connectionLock) {
        if (!isCurrentBindingLocked(connection, generation)) {
            false
        } else {
            bound.set(true)
            binding.set(false)
            true
        }
    }

    private fun handleFailedBinding(
        connection: ServiceConnection,
        generation: Long,
        permanentFailure: VendorBrokerFailure? = null,
    ) {
        val detachedCurrent = abandonUnregisteredBinding(connection, generation)
        if (detachedCurrent) {
            val desired = desiredNpuCommand.get()
            if (desired.intensity > 0f) {
                npuCommandAcknowledgments.recordFailed(
                    desired.ticket,
                    "Vendor NPU service binding failed",
                )
            }
        }
        if (permanentFailure != null) {
            recordPermanentBrokerFailure(permanentFailure)
        } else if (
            shouldScheduleReconnectAfterBindFailure(
                detachedCurrentBinding = detachedCurrent,
                closed = closed.get(),
                permanentFailure = permanentBrokerFailure.get(),
            )
        ) {
            scheduleReconnect()
        }
    }

    private fun abandonUnregisteredBinding(
        connection: ServiceConnection,
        generation: Long,
    ): Boolean =
        synchronized(connectionLock) {
            if (isCurrentBindingLocked(connection, generation)) {
                activeConnection = null
                activeBindingGeneration = 0L
                bound.set(false)
                binding.set(false)
                true
            } else {
                false
            }
        }

    private fun unlinkDeathRecipient(
        binder: IBinder?,
        recipient: IBinder.DeathRecipient?,
    ) {
        if (binder != null && recipient != null) {
            try {
                binder.unlinkToDeath(recipient, 0)
            } catch (_: Exception) {
                // A dead or already-unlinked registration needs no further action.
            }
        }
    }

    private fun unbindCurrentBinding(
        expectedConnection: ServiceConnection? = null,
        generation: Long? = null,
    ) {
        var connectionToUnbind: ServiceConnection? = null
        var shouldUnbind = false
        synchronized(connectionLock) {
            val matches =
                expectedConnection == null ||
                    (
                        activeConnection === expectedConnection &&
                            activeBindingGeneration == generation
                        )
            if (matches) {
                connectionToUnbind = activeConnection
                activeConnection = null
                activeBindingGeneration = 0L
                shouldUnbind = bound.getAndSet(false)
                binding.set(false)
            }
        }
        if (shouldUnbind) {
            connectionToUnbind?.let { connection ->
                try {
                    context.unbindService(connection)
                } catch (_: Exception) {
                    // A concurrent framework terminal callback may have unregistered it.
                }
            }
        }
    }

    private fun scheduleReconnect() {
        if (
            closed.get() ||
            permanentBrokerFailure.get() != null ||
            !reconnectScheduled.compareAndSet(false, true)
        ) {
            return
        }
        val delayMs = reconnectDelayMs(reconnectAttempt.getAndIncrement())
        if (!mainHandler.postDelayed(reconnectRunnable, delayMs)) {
            reconnectScheduled.set(false)
        }
    }

    private fun recordPermanentBrokerFailure(failure: VendorBrokerFailure) {
        if (failure.retryable) return
        permanentBrokerFailure.compareAndSet(null, failure)
        if (reconnectScheduled.compareAndSet(true, false)) {
            mainHandler.removeCallbacks(reconnectRunnable)
        }
        binding.set(false)
    }

    private fun scheduleCapabilityRetry(
        remote: IDpuLabVendorService,
        connection: ServiceConnection,
        bindingGeneration: Long,
        binder: IBinder,
        serviceGeneration: Long,
    ) {
        if (
            closed.get() ||
            !isCurrentService(connection, bindingGeneration, binder, serviceGeneration)
        ) {
            return
        }
        capabilityIsolationGate.runIfOpenOrDefer schedule@{
            if (
                closed.get() ||
                !isCurrentService(connection, bindingGeneration, binder, serviceGeneration)
            ) {
                return@schedule true
            }
            val retry = object : Runnable {
                override fun run() {
                    if (!capabilityRetryRunnable.compareAndSet(this, null)) return
                    if (
                        closed.get() ||
                        !isCurrentService(
                            connection = connection,
                            bindingGeneration = bindingGeneration,
                            binder = binder,
                            serviceGeneration = serviceGeneration,
                        )
                    ) {
                        return
                    }
                    refreshCapabilities(
                        remote = remote,
                        connection = connection,
                        bindingGeneration = bindingGeneration,
                        binder = binder,
                        serviceGeneration = serviceGeneration,
                    )
                }
            }
            if (!capabilityRetryRunnable.compareAndSet(null, retry)) {
                return@schedule true
            }
            val attempt = capabilityRetryAttempt.getAndIncrement()
            val delayMs = if (shouldScheduleCapabilityRetry(attempt)) {
                capabilityRetryDelayMs(attempt)
            } else {
                // Keep one low-rate recovery probe instead of leaving capability discovery
                // permanently pending with no runnable. Do not recycle a live registration here:
                // an NPU workload may be pinned to it while unrelated SBWC discovery is failing.
                capabilityRetryAttempt.set(MAX_CAPABILITY_RETRY_ATTEMPTS)
                STEADY_CAPABILITY_RETRY_DELAY_MS
            }
            if (
                closed.get() ||
                !isCurrentService(connection, bindingGeneration, binder, serviceGeneration)
            ) {
                capabilityRetryRunnable.compareAndSet(retry, null)
                return@schedule true
            }
            val posted = mainHandler.postDelayed(retry, delayMs)
            if (!posted) capabilityRetryRunnable.compareAndSet(retry, null)
            posted
        }
    }

    private fun cancelCapabilityRetry(resetAttempt: Boolean) {
        capabilityRetryRunnable.getAndSet(null)?.let(mainHandler::removeCallbacks)
        if (resetAttempt) capabilityRetryAttempt.set(0L)
    }

    private fun cancelCapabilityRetryForService(
        connection: ServiceConnection,
        bindingGeneration: Long,
        binder: IBinder,
        serviceGeneration: Long,
        resetAttempt: Boolean,
    ) {
        val retry = synchronized(connectionLock) {
            if (
                closed.get() ||
                !isCurrentBindingLocked(connection, bindingGeneration) ||
                serviceBinder !== binder ||
                this.serviceGeneration.get() != serviceGeneration
            ) {
                null
            } else {
                capabilityRetryRunnable.getAndSet(null).also {
                    if (resetAttempt) capabilityRetryAttempt.set(0L)
                }
            }
        }
        retry?.let(mainHandler::removeCallbacks)
    }

    private fun isCurrentService(
        connection: ServiceConnection,
        bindingGeneration: Long,
        binder: IBinder,
        serviceGeneration: Long,
    ): Boolean =
        synchronized(connectionLock) {
            !closed.get() &&
                isCurrentBindingLocked(connection, bindingGeneration) &&
                serviceBinder === binder &&
                this.serviceGeneration.get() == serviceGeneration
        }

    private fun isCurrentBindingLocked(
        connection: ServiceConnection,
        generation: Long,
    ): Boolean =
        activeConnection === connection && activeBindingGeneration == generation

    private fun publishDesiredNpuCommand(command: NpuCommand): NpuCommand {
        while (true) {
            val current = desiredNpuCommand.get()
            if (!shouldPublishNpuCommand(current.version, command.version)) return current
            if (desiredNpuCommand.compareAndSet(current, command)) return command
        }
    }

    private fun publishPendingNpuCommand(command: NpuCommand) {
        while (true) {
            val current = pendingNpuCommand.get()
            if (!shouldPublishNpuCommand(current?.version, command.version)) return
            if (pendingNpuCommand.compareAndSet(current, command)) return
        }
    }

    private fun replayDesiredNpuCommand() {
        while (true) {
            val current = desiredNpuCommand.get()
            val replayTicket = npuCommandAcknowledgments.recordPending(
                version = npuCommandVersion.incrementAndGet(),
                serviceSession = currentServiceSession(),
            )
            val replay = current.copy(ticket = replayTicket)
            if (desiredNpuCommand.compareAndSet(current, replay)) {
                publishPendingNpuCommand(replay)
                return
            }
        }
    }

    private fun <T> callRemote(
        executor: ThreadPoolExecutor,
        fallback: T,
        timeoutMs: Long,
        allowClosed: Boolean = false,
        requiredServiceSession: Long? = null,
        block: (IDpuLabVendorService, Long) -> T,
    ): T {
        if (closed.get() && !allowClosed) return fallback
        val target = synchronized(connectionLock) {
            val remote = service ?: return fallback
            RemoteCallTarget(
                remote = remote,
                binder = serviceBinder,
                serviceSession = serviceGeneration.get(),
            )
        }
        if (
            requiredServiceSession != null &&
            target.serviceSession != requiredServiceSession
        ) {
            return fallback
        }
        val future = try {
            executor.submit<T> {
                val result = block(target.remote, target.serviceSession)
                check(isCurrentRemoteCallTarget(target, allowClosed)) {
                    "Vendor service changed while a remote result was in flight"
                }
                result
            }
        } catch (_: RejectedExecutionException) {
            return fallback
        }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            cleanupRemoteFuture(future, executor)
            fallback
        } catch (execution: ExecutionException) {
            fatalRemoteExecutionCause(execution)?.let { fatal ->
                rethrowRemoteFatal(fatal, future, executor)
            }
            cleanupRemoteFuture(future, executor)
            fallback
        } catch (_: Exception) {
            cleanupRemoteFuture(future, executor)
            fallback
        } catch (error: Error) {
            rethrowRemoteFatal(error, future, executor)
        }
    }

    private fun cleanupRemoteFuture(
        future: java.util.concurrent.Future<*>,
        executor: ThreadPoolExecutor,
    ) {
        future.cancel(true)
        (future as? Runnable)?.let(executor::remove)
        executor.purge()
    }

    private fun rethrowRemoteFatal(
        fatal: Error,
        future: java.util.concurrent.Future<*>,
        executor: ThreadPoolExecutor,
    ): Nothing {
        try {
            cleanupRemoteFuture(future, executor)
        } catch (cleanupFailure: Throwable) {
            if (cleanupFailure !== fatal) {
                try {
                    fatal.addSuppressed(cleanupFailure)
                } catch (_: Throwable) {
                    // Preserve the original fatal error even if suppression itself cannot allocate.
                }
            }
        }
        throw fatal
    }

    private fun isCurrentRemoteCallTarget(
        target: RemoteCallTarget,
        allowClosed: Boolean,
    ): Boolean = synchronized(connectionLock) {
        (allowClosed || !closed.get()) &&
            service === target.remote &&
            serviceBinder === target.binder &&
            serviceGeneration.get() == target.serviceSession
    }

    private fun awaitExecutorTerminationTogether(
        executors: List<ThreadPoolExecutor>,
        timeoutMs: Long,
    ): Boolean {
        val deadlineNanos = System.nanoTime() +
            TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0L))
        for (executor in executors) {
            if (executor.isTerminated) continue
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0L) return false
            try {
                if (!executor.awaitTermination(remainingNanos, TimeUnit.NANOSECONDS)) return false
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return true
    }

    private fun canRestartAfterClose(): Boolean {
        if (restartSafe.get()) return true
        val terminatedLater =
            closed.get() &&
                shutdownResult.get() != null &&
                telemetryExecutor.isTerminated &&
                telemetryV2Executor.isTerminated &&
                capabilityExecutor.isTerminated &&
                controlExecutor.isTerminated &&
                npuExecutor.isTerminated &&
                performanceExecutor.isTerminated &&
                processPerformanceRestoreLatch.snapshot() == null
        if (!terminatedLater) return false
        clearRestoredPerformanceState()
        restartSafe.set(true)
        synchronized(Companion) {
            if (instance === this) instance = null
        }
        return true
    }

    private fun clearRestoredPerformanceState() {
        desiredPerformanceCommand.set(null)
        pendingPerformanceCommand.set(null)
        activePerformanceTicket.set(null)
        activePerformanceAppliedVersion.set(0L)
        performanceLeaseDeadlineNanos.set(0L)
    }

    companion object {
        const val ACTION_VENDOR_SERVICE = "com.example.dpulayerlab.VENDOR_TELEMETRY"
        const val VENDOR_TELEMETRY_PERMISSION =
            "com.example.dpulayerlab.permission.ACCESS_VENDOR_TELEMETRY"
        const val COMPRESSION_LINEAR = 0
        const val COMPRESSION_AUTO = 1
        const val COMPRESSION_SBWC_REQUIRED = 2
        /** API-v3 control bit. Thermal/DVFS/frequency controls are intentionally not defined. */
        const val PERFORMANCE_CONTROL_DISABLE_BATTERY_SAVER = 1
        const val PERFORMANCE_SESSION_LEASE_MS = 10_000L
        const val PERFORMANCE_SESSION_RENEW_INTERVAL_MS = 2_000L
        const val PERFORMANCE_COMMAND_ACK_TIMEOUT_MS = 1_000L
        const val CONTROL_TIMEOUT_MS = 500L
        const val SNAPSHOT_TIMEOUT_MS = 700L
        const val MAX_REMOTE_QUEUE_DEPTH = 8
        const val SHUTDOWN_RESET_ATTEMPTS = 2
        const val NPU_QUIESCE_TIMEOUT_MS = 500L
        const val NPU_RELEASE_TIMEOUT_MS = 500L
        const val MAX_NPU_APPLY_ACK_TIMEOUT_MS = 1_000L
        const val MAX_NPU_PENDING_TIMEOUT_MS = 2_000L
        const val REMOTE_LANE_QUIESCE_TIMEOUT_MS = 200L
        const val CAPABILITY_QUERY_TIMEOUT_MS = 700L
        const val CAPABILITY_LANE_HANDOFF_RETRY_DELAY_MS = 25L
        const val INITIAL_RECONNECT_DELAY_MS = 250L
        const val MAX_RECONNECT_DELAY_MS = 4_000L
        const val MAX_CAPABILITY_RETRY_ATTEMPTS = 4L
        const val INITIAL_CAPABILITY_RETRY_DELAY_MS = 100L
        const val MAX_CAPABILITY_RETRY_DELAY_MS = 800L
        const val STEADY_CAPABILITY_RETRY_DELAY_MS = 4_000L
        private const val NPU_ACK_POLL_NANOS = 2_000_000L
        private const val PERFORMANCE_ACK_POLL_NANOS = 2_000_000L
        private const val PERFORMANCE_MAX_WAIT_MS = 2_000L
        private const val PERFORMANCE_CLOSE_QUIESCE_TIMEOUT_MS = 2_000L
        private const val PERFORMANCE_SESSION_REMOTE_RESTORED = 0
        private const val PERFORMANCE_SESSION_REMOTE_ACTIVE = 1
        const val MAX_RESTART_SAFE_WAIT_MS = 5_000L
        const val MAX_CALIBRATION_TELEMETRY_QUIESCE_TIMEOUT_MS = 5_000L
        private const val RESTART_SAFE_POLL_NANOS = 5_000_000L

        private fun newRemoteExecutor(threadName: String) = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(MAX_REMOTE_QUEUE_DEPTH),
            { runnable ->
                Thread(runnable, threadName).apply { isDaemon = true }
            },
            ThreadPoolExecutor.AbortPolicy(),
        )

        /**
         * Executor construction is transactional. A ThreadPoolExecutor allocates ownership state
         * before VendorBridge itself exists, so a later lane-construction failure must close every
         * earlier lane instead of losing their references in a failed constructor expression.
         */
        private fun create(context: Context): VendorBridge {
            val lanes = constructVendorExecutorLanes { kind ->
                when (kind) {
                    VendorExecutorLane.TELEMETRY ->
                        newRemoteExecutor("DpuLab-VendorTelemetry")
                    VendorExecutorLane.TELEMETRY_V2 ->
                        newNoBacklogRemoteExecutor("DpuLab-VendorTelemetryV2")
                    VendorExecutorLane.CAPABILITY ->
                        newNoBacklogRemoteExecutor("DpuLab-VendorCapability")
                    VendorExecutorLane.CONTROL ->
                        newRemoteExecutor("DpuLab-VendorControl")
                    VendorExecutorLane.NPU ->
                        newRemoteExecutor("DpuLab-VendorNpu")
                    VendorExecutorLane.PERFORMANCE ->
                        newSingleSignalRemoteExecutor("DpuLab-VendorPerformance")
                }
            }
            return try {
                VendorBridge(context.applicationContext, lanes)
            } catch (error: Throwable) {
                lanes.shutdownNow()
                throw error
            }
        }

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: VendorBridge? = null

        fun get(context: Context): VendorBridge {
            while (true) {
                val current = instance
                if (current != null) {
                    if (!current.closed.get()) return current
                    // closeWithResult is synchronized. Waiting outside the Companion monitor lets
                    // the closing instance clear itself without deadlocking a racing recreation.
                    current.closeWithResult()
                    if (!current.canRestartAfterClose()) return current
                    continue
                }
                val existingClosed = synchronized(this) {
                    val existing = instance
                    when {
                        existing == null -> {
                            val created = create(context.applicationContext)
                            try {
                                created.connect()
                            } catch (error: Throwable) {
                                runCatching {
                                    created.closeWithResult(resetCompression = false)
                                }.exceptionOrNull()?.let(error::addSuppressed)
                                throw error
                            }
                            instance = created
                            return created
                        }
                        !existing.closed.get() -> return existing
                        else -> existing
                    }
                }
                existingClosed.closeWithResult()
                if (!existingClosed.canRestartAfterClose()) return existingClosed
            }
        }

        // AIDL values are a product ABI; do not couple them to Kotlin enum ordering.
        private fun LoadShape.wireValue(): Int = when (this) {
            LoadShape.STEADY -> 0
            LoadShape.PULSE -> 1
            LoadShape.RAMP -> 2
            LoadShape.SAW -> 3
        }
    }

    private data class NpuCommand(
        val intensity: Float,
        val shape: LoadShape,
        val ticket: NpuControlCommandTicket,
    ) {
        val version: Long
            get() = ticket.version
    }

    private enum class PerformanceCommandType {
        BEGIN,
        RENEW,
        HEALTH,
        END,
    }

    private data class PerformanceCommand(
        val type: PerformanceCommandType,
        val ticket: VendorPerformanceSessionTicket,
        val minimumAppliedVersion: Long,
        val reason: String = "",
        val result: AtomicReference<VendorPerformanceSessionResult?> = AtomicReference(null),
    ) {
        fun complete(value: VendorPerformanceSessionResult) {
            result.compareAndSet(null, value)
        }
    }

    private data class RemoteResetResult(
        val npuStopConfirmed: Boolean = false,
        val compressionResetConfirmed: Boolean = false,
    )

    private data class RemoteCallTarget(
        val remote: IDpuLabVendorService,
        val binder: IBinder?,
        val serviceSession: Long,
    )

    private data class CapabilityQueryTargets(
        val queryNpu: Boolean,
        val querySbwc: Boolean,
    )

    private data class CapabilityRefreshTarget(
        val remote: IDpuLabVendorService,
        val connection: ServiceConnection,
        val bindingGeneration: Long,
        val binder: IBinder,
        val serviceGeneration: Long,
    )

    private inner class CapabilityQuery(
        val remote: IDpuLabVendorService,
        val connection: ServiceConnection,
        val bindingGeneration: Long,
        val binder: IBinder,
        val serviceGeneration: Long,
        val targets: CapabilityQueryTargets,
        val deadlineNanos: Long,
    ) : Runnable {
        val expired = AtomicBoolean(false)
        val timeoutRunnable = Runnable {
            if (activeCapabilityQuery.get() === this) expired.set(true)
        }

        override fun run() {
            executeCapabilityQuery(this)
        }
    }

    private data class TerminalBindingCleanup(
        val binder: IBinder?,
        val deathRecipient: IBinder.DeathRecipient?,
        val connection: ServiceConnection?,
        val shouldUnbind: Boolean,
        val capabilityRetry: Runnable?,
    )
}

internal data class VendorBrokerConfiguration(
    val servicePackage: String,
    val serviceClass: String,
    val permissionOwnerPackage: String,
    val permissionOwnerSignerSha256: Set<String>,
    val serviceSignerSha256: Set<String>,
) {
    val component: ComponentName
        get() = ComponentName(servicePackage, serviceClass)
}

internal data class VendorSignerEvidence(
    val currentSignerSha256: Set<String>,
    val signingHistorySha256: Set<String>,
    val multipleSigners: Boolean,
)

internal data class VendorBrokerTrustEvidence(
    val permissionPresent: Boolean,
    val permissionProtectionLevel: Int,
    val permissionOwnerPackage: String?,
    val selfPermissionGranted: Boolean,
    val permissionOwnerSigners: VendorSignerEvidence?,
    val servicePresent: Boolean,
    val servicePackage: String?,
    val serviceClass: String?,
    val serviceExported: Boolean,
    val serviceEnabled: Boolean,
    val servicePermission: String?,
    val serviceIsSystem: Boolean,
    val serviceSigners: VendorSignerEvidence?,
)

internal sealed interface VendorBrokerResolution {
    data class Trusted(
        val component: ComponentName,
    ) : VendorBrokerResolution

    data class Failed(
        val failure: VendorBrokerFailure,
    ) : VendorBrokerResolution
}

internal fun parseVendorBrokerConfiguration(
    lines: List<String>,
): VendorBrokerConfiguration? {
    if (
        lines.size > MAX_VENDOR_BROKER_CONFIG_LINES ||
        lines.any { it.length > MAX_VENDOR_BROKER_CONFIG_LINE_CHARS }
    ) {
        return null
    }
    val values = linkedMapOf<String, String>()
    for (raw in lines) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        if ('=' !in line) return null
        val (rawKey, rawValue) = line.split("=", limit = 2)
        val key = rawKey.trim()
        val value = rawValue.trim()
        if (
            key !in VENDOR_BROKER_CONFIG_KEYS ||
            value.isEmpty() ||
            values.put(key, value) != null
        ) {
            return null
        }
    }
    if (values.keys != VENDOR_BROKER_CONFIG_KEYS) return null
    val servicePackage = values.getValue("service_package")
    val serviceClass = values.getValue("service_class")
    val permissionOwnerPackage = values.getValue("permission_owner_package")
    if (
        !ANDROID_PACKAGE_NAME.matches(servicePackage) ||
        !ANDROID_CLASS_NAME.matches(serviceClass) ||
        !serviceClass.startsWith("$servicePackage.") ||
        !ANDROID_PACKAGE_NAME.matches(permissionOwnerPackage)
    ) {
        return null
    }
    val permissionOwnerDigests =
        parseSha256DigestSet(values.getValue("permission_owner_signer_sha256"))
            ?: return null
    val serviceDigests =
        parseSha256DigestSet(values.getValue("service_signer_sha256"))
            ?: return null
    return VendorBrokerConfiguration(
        servicePackage = servicePackage,
        serviceClass = serviceClass,
        permissionOwnerPackage = permissionOwnerPackage,
        permissionOwnerSignerSha256 = permissionOwnerDigests,
        serviceSignerSha256 = serviceDigests,
    )
}

internal fun parseSha256DigestSet(value: String): Set<String>? {
    val tokens = value.split(',').map(String::trim)
    if (tokens.size !in 1..MAX_VENDOR_BROKER_TRUSTED_SIGNERS) return null
    if (tokens.any { !SHA256_HEX.matches(it) }) return null
    val normalized = tokens.map(String::uppercase).toSet()
    return normalized.takeIf { it.size == tokens.size }
}

/**
 * For a rotated single-signer package Android verifies the proof-of-rotation lineage, so any
 * explicitly configured lineage root is sufficient. A multiple-signer package has no rotation
 * lineage: every current signer must be explicitly trusted.
 */
internal fun vendorSignerEvidenceTrusted(
    evidence: VendorSignerEvidence?,
    trustedSha256: Set<String>,
): Boolean {
    evidence ?: return false
    if (trustedSha256.isEmpty() || evidence.currentSignerSha256.isEmpty()) return false
    return if (evidence.multipleSigners) {
        evidence.currentSignerSha256.all(trustedSha256::contains)
    } else {
        evidence.signingHistorySha256.any(trustedSha256::contains)
    }
}

@Suppress("DEPRECATION")
internal fun evaluateVendorBrokerTrust(
    configuration: VendorBrokerConfiguration,
    evidence: VendorBrokerTrustEvidence,
): VendorBrokerFailureCode? {
    if (!evidence.permissionPresent) return VendorBrokerFailureCode.PERMISSION_MISSING
    if (
        evidence.permissionProtectionLevel and PermissionInfo.PROTECTION_MASK_BASE !=
        PermissionInfo.PROTECTION_SIGNATURE
    ) {
        return VendorBrokerFailureCode.PERMISSION_NOT_SIGNATURE
    }
    if (evidence.permissionOwnerPackage != configuration.permissionOwnerPackage) {
        return VendorBrokerFailureCode.PERMISSION_OWNER_MISMATCH
    }
    if (!evidence.selfPermissionGranted) return VendorBrokerFailureCode.PERMISSION_NOT_GRANTED
    if (
        !vendorSignerEvidenceTrusted(
            evidence.permissionOwnerSigners,
            configuration.permissionOwnerSignerSha256,
        )
    ) {
        return VendorBrokerFailureCode.PERMISSION_OWNER_SIGNER_UNTRUSTED
    }
    if (!evidence.servicePresent) return VendorBrokerFailureCode.SERVICE_MISSING
    if (
        evidence.servicePackage != configuration.servicePackage ||
        evidence.serviceClass != configuration.serviceClass ||
        !evidence.serviceExported ||
        !evidence.serviceEnabled ||
        evidence.servicePermission != VendorBridge.VENDOR_TELEMETRY_PERMISSION
    ) {
        return VendorBrokerFailureCode.SERVICE_CONTRACT_MISMATCH
    }
    if (!evidence.serviceIsSystem) return VendorBrokerFailureCode.SERVICE_NOT_SYSTEM
    if (
        !vendorSignerEvidenceTrusted(
            evidence.serviceSigners,
            configuration.serviceSignerSha256,
        )
    ) {
        return VendorBrokerFailureCode.SERVICE_SIGNER_UNTRUSTED
    }
    return null
}

@Suppress("DEPRECATION")
private fun resolveConfiguredVendorBroker(context: Context): VendorBrokerResolution {
    val configuration = when (val loaded = loadVendorBrokerConfiguration()) {
        is VendorBrokerConfigurationLoad.Loaded -> loaded.configuration
        is VendorBrokerConfigurationLoad.Failed -> {
            return VendorBrokerResolution.Failed(loaded.failure)
        }
    }
    val packageManager = context.packageManager
    return try {
        val permissionInfo = try {
            @Suppress("DEPRECATION")
            packageManager.getPermissionInfo(VendorBridge.VENDOR_TELEMETRY_PERMISSION, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        val component = configuration.component
        val serviceInfo = try {
            @Suppress("DEPRECATION")
            packageManager.getServiceInfo(component, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        val permissionOwnerSigners = permissionInfo?.packageName?.let { packageName ->
            readPackageSignerEvidence(packageManager, packageName)
        }
        val serviceSigners = serviceInfo?.packageName?.let { packageName ->
            readPackageSignerEvidence(packageManager, packageName)
        }
        val appInfo = serviceInfo?.applicationInfo
        val serviceIsSystem = appInfo?.let {
            it.flags and (
                ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
                ) != 0
        } == true
        val evidence = VendorBrokerTrustEvidence(
            permissionPresent = permissionInfo != null,
            permissionProtectionLevel = permissionInfo?.protectionLevel ?: 0,
            permissionOwnerPackage = permissionInfo?.packageName,
            selfPermissionGranted =
                context.checkSelfPermission(VendorBridge.VENDOR_TELEMETRY_PERMISSION) ==
                PackageManager.PERMISSION_GRANTED,
            permissionOwnerSigners = permissionOwnerSigners,
            servicePresent = serviceInfo != null,
            servicePackage = serviceInfo?.packageName,
            serviceClass = serviceInfo?.name,
            serviceExported = serviceInfo?.exported == true,
            serviceEnabled = serviceInfo?.enabled == true && appInfo?.enabled == true,
            servicePermission = serviceInfo?.permission,
            serviceIsSystem = serviceIsSystem,
            serviceSigners = serviceSigners,
        )
        val failureCode = evaluateVendorBrokerTrust(configuration, evidence)
        if (failureCode == null) {
            VendorBrokerResolution.Trusted(component)
        } else {
            VendorBrokerResolution.Failed(
                VendorBrokerFailure(
                    code = failureCode,
                    detail = vendorBrokerFailureDetail(failureCode),
                ),
            )
        }
    } catch (_: SecurityException) {
        VendorBrokerResolution.Failed(
            VendorBrokerFailure(
                code = VendorBrokerFailureCode.PERMISSION_NOT_GRANTED,
                detail = "PackageManager denied vendor broker trust inspection",
            ),
        )
    } catch (error: Exception) {
        VendorBrokerResolution.Failed(
            VendorBrokerFailure(
                code = VendorBrokerFailureCode.DISCOVERY_FAILED,
                detail = "Vendor broker trust inspection failed: ${error.javaClass.simpleName}",
                retryable = true,
            ),
        )
    }
}

private sealed interface VendorBrokerConfigurationLoad {
    data class Loaded(
        val configuration: VendorBrokerConfiguration,
    ) : VendorBrokerConfigurationLoad

    data class Failed(
        val failure: VendorBrokerFailure,
    ) : VendorBrokerConfigurationLoad
}

private fun loadVendorBrokerConfiguration(): VendorBrokerConfigurationLoad {
    val file = File(PRODUCT_VENDOR_BROKER_CONFIG_PATH)
    if (!file.exists()) {
        return VendorBrokerConfigurationLoad.Failed(
            VendorBrokerFailure(
                code = VendorBrokerFailureCode.CONFIG_MISSING,
                detail = "$PRODUCT_VENDOR_BROKER_CONFIG_PATH is not installed",
            ),
        )
    }
    return try {
        val canonical = file.canonicalFile
        val acceptedCanonicalRoots = listOf(
            "/product/etc/dpulayerlab/",
            "/system/product/etc/dpulayerlab/",
        )
        if (
            acceptedCanonicalRoots.none { canonical.path.startsWith(it) } ||
            !canonical.isFile ||
            !canonical.canRead() ||
            canonical.length() !in 1..MAX_VENDOR_BROKER_CONFIG_BYTES
        ) {
            VendorBrokerConfigurationLoad.Failed(
                VendorBrokerFailure(
                    code = VendorBrokerFailureCode.CONFIG_INVALID,
                    detail = "Vendor broker config is not a bounded readable product file",
                ),
            )
        } else {
            val bytes = ByteArray(MAX_VENDOR_BROKER_CONFIG_BYTES.toInt() + 1)
            var count = 0
            canonical.inputStream().buffered().use { input ->
                while (count < bytes.size) {
                    val read = input.read(bytes, count, bytes.size - count)
                    if (read < 0) break
                    if (read == 0) {
                        return VendorBrokerConfigurationLoad.Failed(
                            VendorBrokerFailure(
                                code = VendorBrokerFailureCode.CONFIG_INVALID,
                                detail = "Vendor broker config read made no progress",
                            ),
                        )
                    }
                    count += read
                }
            }
            val configuration = if (count <= MAX_VENDOR_BROKER_CONFIG_BYTES) {
                parseVendorBrokerConfiguration(
                    String(bytes, 0, count, Charsets.UTF_8).lineSequence().toList(),
                )
            } else {
                null
            }
            configuration?.let(VendorBrokerConfigurationLoad::Loaded)
                ?: VendorBrokerConfigurationLoad.Failed(
                    VendorBrokerFailure(
                        code = VendorBrokerFailureCode.CONFIG_INVALID,
                        detail = "Vendor broker config is malformed or incomplete",
                    ),
                )
        }
    } catch (error: Exception) {
        VendorBrokerConfigurationLoad.Failed(
            VendorBrokerFailure(
                code = VendorBrokerFailureCode.CONFIG_INVALID,
                detail = "Vendor broker config cannot be verified: ${error.javaClass.simpleName}",
            ),
        )
    }
}

private fun readPackageSignerEvidence(
    packageManager: PackageManager,
    packageName: String,
): VendorSignerEvidence? {
    @Suppress("DEPRECATION")
    val packageInfo = try {
        packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    } catch (_: PackageManager.NameNotFoundException) {
        return null
    }
    val signingInfo = packageInfo.signingInfo ?: return null
    val current = signingInfo.apkContentsSigners
        ?.mapTo(linkedSetOf()) { signature -> sha256Hex(signature.toByteArray()) }
        .orEmpty()
    if (current.isEmpty()) return null
    val multipleSigners = signingInfo.hasMultipleSigners()
    val history = if (multipleSigners) {
        current
    } else {
        signingInfo.signingCertificateHistory
            ?.mapTo(linkedSetOf()) { signature -> sha256Hex(signature.toByteArray()) }
            ?.takeIf(Set<String>::isNotEmpty)
            ?: current
    }
    return VendorSignerEvidence(
        currentSignerSha256 = current,
        signingHistorySha256 = history,
        multipleSigners = multipleSigners,
    )
}

private fun sha256Hex(value: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString(separator = "") { byte -> "%02X".format(byte) }

private fun vendorBrokerFailureDetail(code: VendorBrokerFailureCode): String = when (code) {
    VendorBrokerFailureCode.CONFIG_MISSING -> "Vendor broker product config is missing"
    VendorBrokerFailureCode.CONFIG_INVALID -> "Vendor broker product config is invalid"
    VendorBrokerFailureCode.PERMISSION_MISSING -> "Vendor broker permission is not declared"
    VendorBrokerFailureCode.PERMISSION_NOT_SIGNATURE ->
        "Vendor broker permission is not signature protected"
    VendorBrokerFailureCode.PERMISSION_OWNER_MISMATCH ->
        "Vendor broker permission owner does not match product config"
    VendorBrokerFailureCode.PERMISSION_NOT_GRANTED ->
        "DPULayerTest does not hold the configured vendor broker permission"
    VendorBrokerFailureCode.PERMISSION_OWNER_SIGNER_UNTRUSTED ->
        "Vendor broker permission-owner signer is outside the configured trust root"
    VendorBrokerFailureCode.SERVICE_MISSING -> "Configured vendor broker component is missing"
    VendorBrokerFailureCode.SERVICE_CONTRACT_MISMATCH ->
        "Configured vendor broker component violates the exported/permission contract"
    VendorBrokerFailureCode.SERVICE_NOT_SYSTEM ->
        "Configured vendor broker component is not a system package"
    VendorBrokerFailureCode.SERVICE_SIGNER_UNTRUSTED ->
        "Configured vendor broker signer is outside the configured trust root"
    VendorBrokerFailureCode.DISCOVERY_FAILED -> "Vendor broker trust inspection failed"
    VendorBrokerFailureCode.BIND_PERMISSION_DENIED ->
        "Configured vendor broker rejected the client permission"
}

internal const val MAX_VENDOR_STATUS_CHARS = 256
internal const val VENDOR_TELEMETRY_API_V2 = 2
internal const val VENDOR_PERFORMANCE_API_V3 = 3
internal const val MAX_VENDOR_FREQUENCY_HZ = 20_000_000_000L
internal const val PRODUCT_VENDOR_BROKER_CONFIG_PATH =
    "/product/etc/dpulayerlab/vendor_broker.conf"
private const val MAX_VENDOR_STATUS_INSPECTION_FACTOR = 4L
private const val VENDOR_STATUS_TRUNCATION_MARKER = "\u2026"
private const val MAX_VENDOR_BROKER_CONFIG_BYTES = 16L * 1_024L
private const val MAX_VENDOR_BROKER_CONFIG_LINES = 32
private const val MAX_VENDOR_BROKER_CONFIG_LINE_CHARS = 1_024
private const val MAX_VENDOR_BROKER_TRUSTED_SIGNERS = 8
private val VENDOR_BROKER_CONFIG_KEYS = setOf(
    "service_package",
    "service_class",
    "permission_owner_package",
    "permission_owner_signer_sha256",
    "service_signer_sha256",
)
private val ANDROID_PACKAGE_NAME =
    Regex("""[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*""")
private val ANDROID_CLASS_NAME =
    Regex("""[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_$]*)+""")
private val SHA256_HEX = Regex("""(?i)[0-9a-f]{64}""")
private val vendorPerformanceSessionIds = AtomicLong(0L)
private val processPerformanceRestoreLatch = VendorPerformanceRestoreLatch()

internal class VendorCapabilityIsolationToken internal constructor(
    internal val sequence: Long,
)

internal data class VendorCapabilityIsolationRelease(
    val released: Boolean,
    val refreshDeferred: Boolean,
)

/**
 * Admission gate shared by direct discovery, delayed retries, and service callbacks. The action is
 * executed while holding the same monitor used by acquire(), so a task is either submitted before
 * the calibration token (and then drained) or deferred until after release—never between them.
 */
internal class VendorCapabilityIsolationGate {
    private val lock = Any()
    private var owner: VendorCapabilityIsolationToken? = null
    private var refreshDeferred = false

    fun acquire(token: VendorCapabilityIsolationToken): Boolean = synchronized(lock) {
        if (owner != null) {
            false
        } else {
            owner = token
            true
        }
    }

    fun release(token: VendorCapabilityIsolationToken): VendorCapabilityIsolationRelease =
        synchronized(lock) {
            if (owner !== token) {
                VendorCapabilityIsolationRelease(
                    released = false,
                    refreshDeferred = false,
                )
            } else {
                owner = null
                VendorCapabilityIsolationRelease(
                    released = true,
                    refreshDeferred = refreshDeferred,
                ).also {
                    refreshDeferred = false
                }
            }
        }

    fun runIfOpenOrDefer(action: () -> Boolean): Boolean = synchronized(lock) {
        if (owner != null) {
            refreshDeferred = true
            false
        } else {
            action().also { admitted ->
                if (!admitted) refreshDeferred = true
            }
        }
    }

    fun deferRefresh() {
        synchronized(lock) {
            refreshDeferred = true
        }
    }

    fun consumeDeferredRefreshIfOpen(): Boolean = synchronized(lock) {
        if (owner != null || !refreshDeferred) {
            false
        } else {
            refreshDeferred = false
            true
        }
    }

    internal fun isIsolated(): Boolean = synchronized(lock) { owner != null }
}

internal data class CapabilityQueryOutcome(
    val npuSupported: Boolean?,
    val sbwcSupported: Boolean?,
    val completedWithinDeadline: Boolean,
)

/**
 * Applies one total deadline to both capability getters. A provider may ignore interruption; in
 * that case the owning no-backlog executor remains quarantined, but a late return is never
 * published and the second getter is not started after the deadline.
 */
internal fun queryCapabilitiesBeforeDeadline(
    queryNpu: Boolean,
    querySbwc: Boolean,
    deadlineNanos: Long,
    isExpired: () -> Boolean = { false },
    monotonicNowNanos: () -> Long = System::nanoTime,
    readNpu: () -> Boolean,
    readSbwc: () -> Boolean,
): CapabilityQueryOutcome {
    fun deadlineExpired(): Boolean =
        isExpired() ||
            isMonotonicDeadlineReached(
                nowNanos = monotonicNowNanos(),
                deadlineNanos = deadlineNanos,
            )

    if (deadlineExpired()) {
        return CapabilityQueryOutcome(null, null, completedWithinDeadline = false)
    }
    val npuSupported = if (queryNpu) {
        try {
            readNpu()
        } catch (_: Exception) {
            null
        }
    } else {
        null
    }
    if (deadlineExpired()) {
        return CapabilityQueryOutcome(null, null, completedWithinDeadline = false)
    }
    val sbwcSupported = if (querySbwc) {
        try {
            readSbwc()
        } catch (_: Exception) {
            null
        }
    } else {
        null
    }
    if (deadlineExpired()) {
        return CapabilityQueryOutcome(null, null, completedWithinDeadline = false)
    }
    return CapabilityQueryOutcome(
        npuSupported = npuSupported,
        sbwcSupported = sbwcSupported,
        completedWithinDeadline = true,
    )
}

/**
 * Recovers the no-backlog executor's narrow hand-off race. The old task clears its active query
 * just before returning from Runnable.run(); a Handler refresh can therefore see no logical owner
 * while the SynchronousQueue worker still rejects a new task. Keep exactly one delayed refresh
 * instead of leaving discovery permanently pending.
 */
internal fun recoverDeferredCapabilityRefreshAfterAdmissionFailure(
    gate: VendorCapabilityIsolationGate,
    activeQueryPresent: Boolean,
    scheduleRetry: () -> Boolean,
): Boolean {
    if (activeQueryPresent || !gate.consumeDeferredRefreshIfOpen()) return false
    if (scheduleRetry()) return true
    gate.deferRefresh()
    return false
}

internal fun fatalCapabilityAdmissionFailure(
    admissionFailure: Throwable?,
    rollbackFailure: Throwable?,
): Error? {
    val fatal = (admissionFailure as? Error) ?: (rollbackFailure as? Error) ?: return null
    if (admissionFailure != null && admissionFailure !== fatal) {
        runCatching { fatal.addSuppressed(admissionFailure) }
    }
    if (rollbackFailure != null && rollbackFailure !== fatal) {
        runCatching { fatal.addSuppressed(rollbackFailure) }
    }
    return fatal
}

internal fun supportsVendorTelemetryV2(apiVersion: Int): Boolean =
    apiVersion >= VENDOR_TELEMETRY_API_V2

internal fun shouldReadVendorTelemetryV2(
    apiVersion: Int,
    includeExtendedTelemetry: Boolean,
): Boolean =
    includeExtendedTelemetry &&
        supportsVendorTelemetryV2(apiVersion)

internal fun supportsVendorPerformanceSession(apiVersion: Int): Boolean =
    apiVersion >= VENDOR_PERFORMANCE_API_V3

internal fun validPerformanceControlRequest(requestedControls: Int): Boolean =
    requestedControls == VendorBridge.PERFORMANCE_CONTROL_DISABLE_BATTERY_SAVER

internal fun ticketsReferToSameSession(
    first: VendorPerformanceSessionTicket,
    second: VendorPerformanceSessionTicket,
): Boolean =
    first.sessionId == second.sessionId &&
        first.serviceSession == second.serviceSession

internal fun ticketsMatchExactly(
    first: VendorPerformanceSessionTicket?,
    second: VendorPerformanceSessionTicket,
): Boolean = first == second

internal fun performanceResult(
    state: VendorPerformanceSessionState,
    ticket: VendorPerformanceSessionTicket? = null,
    detail: String,
): VendorPerformanceSessionResult =
    VendorPerformanceSessionResult(
        state = state,
        ticket = ticket,
        detail = detail,
    )

internal fun nextNonZeroSequence(sequence: AtomicLong): Long {
    while (true) {
        val current = sequence.get()
        var candidate = current + 1L
        if (candidate == 0L) candidate = 1L
        if (sequence.compareAndSet(current, candidate)) return candidate
    }
}

internal fun nextVendorPerformanceSessionId(): Long =
    nextNonZeroSequence(vendorPerformanceSessionIds)

internal fun monotonicDeadlineAfter(origin: Long, delta: Long): Long =
    origin + delta.coerceAtLeast(0L)

internal fun isMonotonicDeadlineReached(
    nowNanos: Long,
    deadlineNanos: Long,
): Boolean = nowNanos - deadlineNanos >= 0L

internal fun awaitBoundedCondition(
    timeoutMs: Long,
    maxTimeoutMs: Long,
    pollNanos: Long,
    monotonicNowNanos: () -> Long = System::nanoTime,
    parkNanos: (Long) -> Unit = { duration -> LockSupport.parkNanos(duration) },
    condition: () -> Boolean,
): Boolean {
    require(maxTimeoutMs >= 0L) { "maxTimeoutMs must not be negative" }
    require(pollNanos > 0L) { "pollNanos must be positive" }
    if (condition()) return true
    val boundedTimeoutMs = timeoutMs.coerceIn(0L, maxTimeoutMs)
    val deadlineNanos = monotonicDeadlineAfter(
        monotonicNowNanos(),
        TimeUnit.MILLISECONDS.toNanos(boundedTimeoutMs),
    )
    while (true) {
        val remainingNanos = deadlineNanos - monotonicNowNanos()
        if (remainingNanos <= 0L) return condition()
        parkNanos(remainingNanos.coerceAtMost(pollNanos))
        if (Thread.interrupted()) {
            Thread.currentThread().interrupt()
            return false
        }
        if (condition()) return true
    }
}

private const val VENDOR_TELEMETRY_IDLE_POLL_NANOS = 1_000_000L

internal fun awaitVendorTelemetryLanesIdle(
    executors: List<ThreadPoolExecutor>,
    timeoutMs: Long,
    maxTimeoutMs: Long,
): Boolean {
    require(executors.isNotEmpty())
    require(maxTimeoutMs >= 0L)
    val boundedTimeoutMs = timeoutMs.coerceIn(0L, maxTimeoutMs)
    val deadlineNanos = monotonicDeadlineAfter(
        System.nanoTime(),
        TimeUnit.MILLISECONDS.toNanos(boundedTimeoutMs),
    )
    for (executor in executors) {
        if (!awaitExecutorSentinel(executor, deadlineNanos)) return false
    }
    return true
}

/**
 * A completed sentinel is an exact FIFO proof for every task accepted before it. Retrying submit
 * also handles the v2 SynchronousQueue without relying on ThreadPoolExecutor.activeCount, which is
 * explicitly approximate.
 */
private fun awaitExecutorSentinel(
    executor: ThreadPoolExecutor,
    deadlineNanos: Long,
): Boolean {
    while (true) {
        if (executor.isShutdown) return false
        val sentinel = try {
            executor.submit<Boolean> { true }
        } catch (_: RejectedExecutionException) {
            null
        }
        if (sentinel == null) {
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0L) return false
            LockSupport.parkNanos(
                remainingNanos.coerceAtMost(VENDOR_TELEMETRY_IDLE_POLL_NANOS),
            )
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt()
                return false
            }
            continue
        }
        val remainingNanos = deadlineNanos - System.nanoTime()
        if (remainingNanos <= 0L) {
            sentinel.cancel(true)
            (sentinel as? Runnable)?.let(executor::remove)
            executor.purge()
            return false
        }
        return try {
            sentinel.get(remainingNanos, TimeUnit.NANOSECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            sentinel.cancel(true)
            (sentinel as? Runnable)?.let(executor::remove)
            executor.purge()
            false
        } catch (_: Exception) {
            sentinel.cancel(true)
            (sentinel as? Runnable)?.let(executor::remove)
            executor.purge()
            false
        }
    }
}

/**
 * Small process-wide contamination latch. A provider mutation is considered possibly active from
 * before BEGIN submission until the exact newest END (or serialized inactive health query) is
 * acknowledged. It intentionally contains no Context/Binder references.
 */
internal class VendorPerformanceRestoreLatch {
    private val pending = AtomicReference<VendorPerformanceSessionTicket?>(null)

    fun arm(ticket: VendorPerformanceSessionTicket): Boolean =
        pending.compareAndSet(null, ticket)

    fun advance(ticket: VendorPerformanceSessionTicket): Boolean {
        while (true) {
            val current = pending.get() ?: return false
            if (
                !ticketsReferToSameSession(current, ticket) ||
                !isNewerSequence(ticket.commandVersion, current.commandVersion)
            ) {
                return false
            }
            if (pending.compareAndSet(current, ticket)) return true
        }
    }

    fun confirmRestored(ticket: VendorPerformanceSessionTicket): Boolean {
        while (true) {
            val current = pending.get() ?: return true
            if (
                !ticketsReferToSameSession(current, ticket) ||
                current.commandVersion != ticket.commandVersion
            ) {
                return false
            }
            if (pending.compareAndSet(current, null)) return true
        }
    }

    /**
     * Accepts an exact provider acknowledgment for an END which was already in flight when a
     * higher-version END retry advanced the local latch. Once an END is published, active session
     * ownership has been cleared and the synchronized API can only append another same-session
     * END; no newer BEGIN/RENEW mutation can be hidden behind this proof.
     */
    fun confirmRestoredByEnd(ticket: VendorPerformanceSessionTicket): Boolean {
        while (true) {
            val current = pending.get() ?: return true
            if (
                !ticketsReferToSameSession(current, ticket) ||
                (
                    current.commandVersion != ticket.commandVersion &&
                        !isNewerSequence(current.commandVersion, ticket.commandVersion)
                    )
            ) {
                return false
            }
            if (pending.compareAndSet(current, null)) return true
        }
    }

    /**
     * Clears the whole same-session chain when BEGIN is proven not to have reached the provider.
     * A timeout may already have advanced the latch to a queued END; that END exists only to
     * invalidate the unconfirmed BEGIN and must not leave a false process-wide contamination.
     */
    fun confirmNeverMutated(beginTicket: VendorPerformanceSessionTicket): Boolean {
        while (true) {
            val current = pending.get() ?: return true
            if (!ticketsReferToSameSession(current, beginTicket)) return false
            if (pending.compareAndSet(current, null)) return true
        }
    }

    fun snapshot(): VendorPerformanceSessionTicket? = pending.get()
}

internal fun validVendorUtilizationPercent(value: Float): Float? =
    value.takeIf { it.isFinite() && it in 0f..100f }

internal fun validVendorFrequencyHz(value: Long): Long? =
    value.takeIf { it in 0L..MAX_VENDOR_FREQUENCY_HZ }

internal inline fun <T> remoteValueOrNull(block: () -> T): T? =
    try {
        block()
    } catch (_: Exception) {
        null
    }

internal fun fatalRemoteExecutionCause(failure: Throwable): Error? = when (failure) {
    is Error -> failure
    is ExecutionException -> failure.cause as? Error
    else -> null
}

internal fun readVendorTelemetryV2(
    serviceSession: Long,
    gpuUtilizationReader: () -> Float,
    gpuFrequencyReader: () -> Long,
    dpuFrequencyReader: () -> Long,
): VendorTelemetryV2Snapshot = VendorTelemetryV2Snapshot(
    serviceSession = serviceSession,
    gpuUtilization = readOptionalVendorTelemetry(gpuUtilizationReader)
        ?.let(::validVendorUtilizationPercent),
    gpuFrequencyHz = readOptionalVendorTelemetry(gpuFrequencyReader)
        ?.let(::validVendorFrequencyHz),
    dpuFrequencyHz = readOptionalVendorTelemetry(dpuFrequencyReader)
        ?.let(::validVendorFrequencyHz),
)

internal fun mergeVendorTelemetryV2(
    base: VendorSnapshot,
    extended: VendorTelemetryV2Snapshot?,
): VendorSnapshot {
    if (extended == null || extended.serviceSession != base.serviceSession) return base
    return base.copy(
        gpuUtilization = extended.gpuUtilization,
        gpuFrequencyHz = extended.gpuFrequencyHz,
        dpuFrequencyHz = extended.dpuFrequencyHz,
    )
}

internal fun remainingVendorTelemetryTimeoutMs(
    deadlineNanos: Long,
    nowNanos: Long,
): Long {
    val remainingNanos = deadlineNanos - nowNanos
    if (remainingNanos <= 0L) return 0L
    // Use a floor so the optional extension never increases the original snapshot deadline.
    return TimeUnit.NANOSECONDS.toMillis(remainingNanos)
}

private inline fun <T> readOptionalVendorTelemetry(reader: () -> T): T? {
    if (Thread.currentThread().isInterrupted) throw InterruptedException()
    return try {
        reader()
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        throw interrupted
    } catch (_: Exception) {
        null
    }
}

/**
 * Treat Binder-provided status text as untrusted telemetry. Keeping the normalized copy small
 * prevents one broker response from being retained in every telemetry sample or expanding a
 * report/HUD indefinitely. The inspection window is also bounded so pathological whitespace-only
 * input cannot consume unbounded CPU before it is truncated.
 */
internal fun sanitizeVendorStatus(
    value: String?,
    maxChars: Int = MAX_VENDOR_STATUS_CHARS,
): String {
    require(maxChars > 0) { "maxChars must be positive" }
    val source = value.orEmpty()
    if (source.isEmpty()) return ""

    val inspectionLimit = minOf(
        source.length,
        (maxChars.toLong() * MAX_VENDOR_STATUS_INSPECTION_FACTOR)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt(),
    )
    val normalized = StringBuilder(minOf(maxChars, inspectionLimit))
    var index = 0
    while (index < inspectionLimit && normalized.length < maxChars) {
        val first = source[index]
        val hasValidPair = Character.isHighSurrogate(first) &&
            index + 1 < source.length &&
            Character.isLowSurrogate(source[index + 1])
        val codePoint = when {
            hasValidPair -> Character.toCodePoint(first, source[index + 1])
            Character.isSurrogate(first) -> 0xfffd
            else -> first.code
        }
        val consumed = if (hasValidPair) 2 else 1
        val type = Character.getType(codePoint)
        val normalizeToSpace = Character.isWhitespace(codePoint) ||
            Character.isISOControl(codePoint) ||
            type == Character.FORMAT.toInt()

        if (normalizeToSpace) {
            if (normalized.isNotEmpty() && normalized.last() != ' ') {
                normalized.append(' ')
            }
        } else {
            val requiredChars = Character.charCount(codePoint)
            if (normalized.length + requiredChars > maxChars) break
            normalized.appendCodePoint(codePoint)
        }
        index += consumed
    }

    while (normalized.isNotEmpty() && normalized.last() == ' ') {
        normalized.setLength(normalized.length - 1)
    }
    if (index < source.length) {
        if (maxChars == 1) return VENDOR_STATUS_TRUNCATION_MARKER
        if (normalized.length >= maxChars) {
            var markerBoundary = maxChars - 1
            if (
                markerBoundary > 0 &&
                markerBoundary < normalized.length &&
                Character.isHighSurrogate(normalized[markerBoundary - 1]) &&
                Character.isLowSurrogate(normalized[markerBoundary])
            ) {
                markerBoundary -= 1
            }
            normalized.setLength(markerBoundary)
        }
        while (normalized.isNotEmpty() && normalized.last() == ' ') {
            normalized.setLength(normalized.length - 1)
        }
        normalized.append(VENDOR_STATUS_TRUNCATION_MARKER)
    }
    return normalized.toString()
}

internal fun shouldPublishNpuCommand(
    currentVersion: Long?,
    candidateVersion: Long,
): Boolean = currentVersion == null || isNewerSequence(candidateVersion, currentVersion)

internal fun shouldRetryPendingNpuCommand(
    failedVersion: Long?,
    pendingVersion: Long?,
    allowSameVersionRetry: Boolean,
): Boolean {
    pendingVersion ?: return false
    failedVersion ?: return true
    return isNewerSequence(pendingVersion, failedVersion) ||
        (allowSameVersionRetry && pendingVersion == failedVersion)
}

/**
 * Serial-number comparison for an incrementing signed Long. Subtraction intentionally wraps so
 * Long.MAX_VALUE -> Long.MIN_VALUE remains the next command rather than freezing latest-wins.
 */
internal fun isNewerSequence(candidate: Long, current: Long): Boolean =
    candidate != current && candidate - current > 0L

/**
 * Versioned apply evidence shared by the Binder and reflection NPU backends. A later request
 * replaces the observable record, and completion of an older call is therefore unable to make
 * the latest request look applied. The in-flight timestamp belongs to the serialized control
 * lane, so a stream of newer 100 ms setpoints cannot hide one setter that stopped returning.
 */
internal class NpuCommandAcknowledgments(
    private val monotonicNowNanos: () -> Long = System::nanoTime,
) {
    private val latest = AtomicReference<NpuCommandRecord?>(null)
    private val inFlight = AtomicReference<NpuInFlightCommand?>(null)

    fun recordPending(
        version: Long,
        serviceSession: Long?,
    ): NpuControlCommandTicket {
        val ticket = NpuControlCommandTicket(
            version = version,
            serviceSession = serviceSession,
            submittedAtNanos = monotonicNowNanos(),
        )
        while (true) {
            val current = latest.get()
            if (
                current != null &&
                !shouldPublishNpuCommand(current.ticket.version, ticket.version)
            ) {
                return ticket
            }
            val candidate = NpuCommandRecord(
                ticket = ticket,
                state = NpuControlCommandState.PENDING,
                detail = "NPU apply acknowledgment pending",
            )
            if (latest.compareAndSet(current, candidate)) return ticket
        }
    }

    fun recordStarted(ticket: NpuControlCommandTicket): NpuInFlightCommand {
        val command = NpuInFlightCommand(
            ticket = ticket,
            startedAtNanos = monotonicNowNanos(),
        )
        inFlight.set(command)
        return command
    }

    fun recordFinished(command: NpuInFlightCommand) {
        inFlight.compareAndSet(command, null)
    }

    fun recordApplied(ticket: NpuControlCommandTicket) {
        updateMatching(
            ticket = ticket,
            state = NpuControlCommandState.APPLIED,
            detail = "NPU apply acknowledged",
        )
    }

    fun recordFailed(ticket: NpuControlCommandTicket, detail: String) {
        updateMatching(
            ticket = ticket,
            state = NpuControlCommandState.FAILED,
            detail = detail,
        )
    }

    fun health(
        ticket: NpuControlCommandTicket,
        latestDesiredVersion: Long,
        currentServiceSession: Long?,
        pendingTimeoutMs: Long,
    ): NpuControlCommandHealth {
        if (latestDesiredVersion != ticket.version) {
            return NpuControlCommandHealth(
                state = NpuControlCommandState.FAILED,
                detail = "NPU setpoint was superseded before confirmation",
            )
        }
        if (
            ticket.serviceSession == null ||
            currentServiceSession != ticket.serviceSession
        ) {
            recordFailed(ticket, "NPU control service session is unavailable or changed")
        }
        val boundedTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(
            pendingTimeoutMs.coerceIn(1L, MAX_TRACKED_NPU_TIMEOUT_MS),
        )
        val nowNanos = monotonicNowNanos()
        val active = inFlight.get()
        if (
            active != null &&
            elapsedNanos(nowNanos, active.startedAtNanos) >= boundedTimeoutNanos
        ) {
            recordFailed(
                ticket,
                "NPU control lane timed out while applying version ${active.ticket.version}",
            )
        }
        var record = latest.get()
        if (record?.ticket?.version != ticket.version) {
            return NpuControlCommandHealth(
                state = NpuControlCommandState.FAILED,
                detail = "NPU apply evidence is unavailable for the latest setpoint",
            )
        }
        if (
            record.state == NpuControlCommandState.PENDING &&
            elapsedNanos(nowNanos, ticket.submittedAtNanos) >= boundedTimeoutNanos
        ) {
            recordFailed(ticket, "NPU apply acknowledgment timed out")
            record = latest.get()
        }
        return NpuControlCommandHealth(
            state = record?.state ?: NpuControlCommandState.FAILED,
            detail = record?.detail ?: "NPU apply evidence is unavailable",
        )
    }

    private fun updateMatching(
        ticket: NpuControlCommandTicket,
        state: NpuControlCommandState,
        detail: String,
    ) {
        while (true) {
            val current = latest.get() ?: return
            if (current.ticket.version != ticket.version) return
            if (current.state == NpuControlCommandState.FAILED) return
            val candidate = NpuCommandRecord(
                ticket = current.ticket,
                state = state,
                detail = detail,
            )
            if (latest.compareAndSet(current, candidate)) return
        }
    }

    internal data class NpuInFlightCommand(
        val ticket: NpuControlCommandTicket,
        val startedAtNanos: Long,
    )

    private data class NpuCommandRecord(
        val ticket: NpuControlCommandTicket,
        val state: NpuControlCommandState,
        val detail: String,
    )

    private companion object {
        const val MAX_TRACKED_NPU_TIMEOUT_MS = 60_000L

        fun elapsedNanos(nowNanos: Long, startedAtNanos: Long): Long =
            (nowNanos - startedAtNanos).coerceAtLeast(0L)
    }
}

internal fun reconnectDelayMs(attempt: Long): Long {
    val shift = attempt.coerceIn(0L, 4L).toInt()
    return (VendorBridge.INITIAL_RECONNECT_DELAY_MS shl shift)
        .coerceAtMost(VendorBridge.MAX_RECONNECT_DELAY_MS)
}

internal enum class VendorExecutorLane {
    TELEMETRY,
    TELEMETRY_V2,
    CAPABILITY,
    CONTROL,
    NPU,
    PERFORMANCE,
}

/**
 * Fully constructed executor ownership passed into VendorBridge. This keeps partially-created
 * lanes outside the bridge constructor until every lane exists and gives the caller one bounded
 * rollback handle.
 */
internal data class VendorExecutorLanes(
    val telemetry: ThreadPoolExecutor,
    val telemetryV2: ThreadPoolExecutor,
    val capability: ThreadPoolExecutor,
    val control: ThreadPoolExecutor,
    val npu: ThreadPoolExecutor,
    val performance: ThreadPoolExecutor,
) {
    fun shutdownNow() {
        // Keep rollback allocation-free so constructor OOM cannot prevent an already-created
        // executor prefix from being shut down.
        shutdownExecutorBestEffort(performance)
        shutdownExecutorBestEffort(npu)
        shutdownExecutorBestEffort(control)
        shutdownExecutorBestEffort(capability)
        shutdownExecutorBestEffort(telemetryV2)
        shutdownExecutorBestEffort(telemetry)
    }
}

/**
 * Creates all lanes or shuts down every successfully-created prefix in reverse ownership order.
 */
internal fun constructVendorExecutorLanes(
    factory: (VendorExecutorLane) -> ThreadPoolExecutor,
): VendorExecutorLanes {
    var telemetry: ThreadPoolExecutor? = null
    var telemetryV2: ThreadPoolExecutor? = null
    var capability: ThreadPoolExecutor? = null
    var control: ThreadPoolExecutor? = null
    var npu: ThreadPoolExecutor? = null
    var performance: ThreadPoolExecutor? = null
    return try {
        val ownedTelemetry =
            factory(VendorExecutorLane.TELEMETRY).also { telemetry = it }
        val ownedTelemetryV2 =
            factory(VendorExecutorLane.TELEMETRY_V2).also { telemetryV2 = it }
        val ownedCapability =
            factory(VendorExecutorLane.CAPABILITY).also { capability = it }
        val ownedControl =
            factory(VendorExecutorLane.CONTROL).also { control = it }
        val ownedNpu =
            factory(VendorExecutorLane.NPU).also { npu = it }
        val ownedPerformance =
            factory(VendorExecutorLane.PERFORMANCE).also { performance = it }
        VendorExecutorLanes(
            telemetry = ownedTelemetry,
            telemetryV2 = ownedTelemetryV2,
            capability = ownedCapability,
            control = ownedControl,
            npu = ownedNpu,
            performance = ownedPerformance,
        )
    } catch (error: Throwable) {
        // Fixed slots avoid the "factory succeeded, tracking-list add OOM" orphan window.
        shutdownExecutorBestEffort(performance)
        shutdownExecutorBestEffort(npu)
        shutdownExecutorBestEffort(control)
        shutdownExecutorBestEffort(capability)
        shutdownExecutorBestEffort(telemetryV2)
        shutdownExecutorBestEffort(telemetry)
        throw error
    }
}

private fun shutdownExecutorBestEffort(executor: ThreadPoolExecutor?) {
    if (executor == null) return
    try {
        executor.shutdownNow()
    } catch (_: Throwable) {
        // Preserve the construction/close failure that initiated rollback. The owner will remain
        // fail-closed if executor termination cannot subsequently be confirmed.
    }
}

internal fun newNoBacklogRemoteExecutor(threadName: String) = ThreadPoolExecutor(
    1,
    1,
    0L,
    TimeUnit.MILLISECONDS,
    SynchronousQueue(),
    { runnable ->
        Thread(runnable, threadName).apply { isDaemon = true }
    },
    ThreadPoolExecutor.AbortPolicy(),
)

/**
 * Executor queue holds only a drain wake-up, never a Binder command. Actual commands live in one
 * atomic latest-wins slot, so a stuck provider can retain at most the running command, one latest
 * command, and one constant-size wake signal.
 */
internal fun newSingleSignalRemoteExecutor(threadName: String) = ThreadPoolExecutor(
    1,
    1,
    0L,
    TimeUnit.MILLISECONDS,
    ArrayBlockingQueue(1),
    { runnable ->
        Thread(runnable, threadName).apply { isDaemon = true }
    },
    ThreadPoolExecutor.AbortPolicy(),
)

internal fun unattributedVendorShutdownResult(): VendorShutdownResult =
    VendorShutdownResult(
        brokerWasConnected = false,
        npuStopConfirmed = false,
        compressionResetConfirmed = false,
        performanceRestoreConfirmed = false,
    )

internal fun shouldScheduleReconnectAfterBindFailure(
    detachedCurrentBinding: Boolean,
    closed: Boolean,
    permanentFailure: VendorBrokerFailure? = null,
): Boolean = detachedCurrentBinding && !closed && permanentFailure == null

internal data class CapabilityDiscoveryMerge(
    val npuSupported: Boolean?,
    val sbwcSupported: Boolean?,
    val npuBecameKnown: Boolean,
    val complete: Boolean,
)

internal fun mergeCapabilityDiscovery(
    currentNpu: Boolean?,
    currentSbwc: Boolean?,
    queriedNpu: Boolean?,
    queriedSbwc: Boolean?,
): CapabilityDiscoveryMerge {
    val mergedNpu = currentNpu ?: queriedNpu
    val mergedSbwc = currentSbwc ?: queriedSbwc
    return CapabilityDiscoveryMerge(
        npuSupported = mergedNpu,
        sbwcSupported = mergedSbwc,
        npuBecameKnown = currentNpu == null && mergedNpu != null,
        complete = mergedNpu != null && mergedSbwc != null,
    )
}

internal fun shouldScheduleCapabilityRetry(attempt: Long): Boolean =
    attempt in 0 until VendorBridge.MAX_CAPABILITY_RETRY_ATTEMPTS

internal fun capabilityRetryDelayMs(attempt: Long): Long {
    val maxShift = (VendorBridge.MAX_CAPABILITY_RETRY_ATTEMPTS - 1L)
        .coerceAtLeast(0L)
    val shift = attempt.coerceIn(0L, maxShift).toInt()
    return (VendorBridge.INITIAL_CAPABILITY_RETRY_DELAY_MS shl shift)
        .coerceAtMost(VendorBridge.MAX_CAPABILITY_RETRY_DELAY_MS)
}
