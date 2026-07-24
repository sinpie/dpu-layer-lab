package com.example.dpulayerlab.vendor

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.example.dpulayerlab.model.LoadShape
import com.example.dpulayerlab.model.PixelRoute
import java.util.concurrent.ArrayBlockingQueue
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
    val deviceLayers: Int?,
    val clientLayers: Int?,
    val compressionState: String,
    val npuStatus: String,
)

data class VendorShutdownResult(
    val brokerWasConnected: Boolean,
    val npuStopConfirmed: Boolean,
    val compressionResetConfirmed: Boolean,
)

data class VendorCompressionControlResult(
    val applied: Boolean,
    /** Binder registration that acknowledged the route, or null when it was not acknowledged. */
    val serviceSession: Long?,
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

class VendorBridge private constructor(private val context: Context) : AutoCloseable {
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
    private val capabilityRetryAttempt = AtomicLong(0L)
    private val capabilityRetryRunnable = AtomicReference<Runnable?>(null)
    private val reconnectRunnable = Runnable {
        reconnectScheduled.set(false)
        if (!closed.get()) connect()
    }
    /**
     * A broken product Binder implementation may ignore interruption after a client timeout.
     * Bound the waiting queue so one stuck transaction cannot accumulate a task every telemetry
     * interval for the lifetime of the process.
     */
    private val telemetryExecutor = newRemoteExecutor("DpuLab-VendorTelemetry")
    private val controlExecutor = newRemoteExecutor("DpuLab-VendorControl")
    private val npuExecutor = newRemoteExecutor("DpuLab-VendorNpu")
    private fun newServiceConnection(generation: Long): ServiceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                // A callback proves registration even if it raced bindService() returning.
                if (!markBindingConnected(this, generation)) {
                    runCatching { context.unbindService(this) }
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

                val linked = runCatching { binder.linkToDeath(recipient, 0) }.isSuccess
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
        if (closed.get()) return
        if (service != null || bound.get() || !binding.compareAndSet(false, true)) return
        runCatching {
            val query = Intent(ACTION_VENDOR_SERVICE)
            val matches = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.queryIntentServices(
                    query,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_SYSTEM_ONLY.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.queryIntentServices(query, PackageManager.MATCH_SYSTEM_ONLY)
            }
            val eligible = matches.mapNotNull { it.serviceInfo }
                .filter { info ->
                    info.exported &&
                        info.permission == VENDOR_TELEMETRY_PERMISSION
                }
            // Binding to an arbitrary implementation makes telemetry and load control
            // nondeterministic. Product integration must expose exactly one trusted broker.
            val info = eligible.singleOrNull()
                ?.takeIf { hasSignatureVendorPermission() }
                ?: run {
                binding.set(false)
                return
            }
            val explicit = query.setComponent(ComponentName(info.packageName, info.name))
            val generation = bindingGeneration.incrementAndGet()
            val candidate = newServiceConnection(generation)
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
            val registered = runCatching {
                context.bindService(explicit, candidate, Context.BIND_AUTO_CREATE)
            }.getOrElse {
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
                    runCatching { context.unbindService(candidate) }
                    if (stillCurrent) {
                        abandonUnregisteredBinding(candidate, generation)
                    }
                }
            }
        }.onFailure {
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
            (
                binding.get() ||
                    reconnectScheduled.get() ||
                    (bound.get() && service == null) ||
                    (service != null && (npuSupported == null || sbwcSupported == null))
                )

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

    fun snapshot(): VendorSnapshot? {
        return callRemote<VendorSnapshot?>(
            executor = telemetryExecutor,
            fallback = null,
            timeoutMs = SNAPSHOT_TIMEOUT_MS,
        ) { remote, serviceSession ->
            val counts: IntArray = remote.compositionLayerCounts ?: intArrayOf()
            VendorSnapshot(
                apiVersion = remote.apiVersion,
                serviceSession = serviceSession,
                underrunCount = remote.dpuUnderrunCount.takeIf { it >= 0 },
                dpuUtilization = remote.dpuUtilizationPercent.validPercent(),
                busUtilization = remote.memoryBusUtilizationPercent.validPercent(),
                deviceLayers = counts.getOrNull(0)?.takeIf { it >= 0 },
                clientLayers = counts.getOrNull(1)?.takeIf { it >= 0 },
                compressionState = sanitizeVendorStatus(remote.lastCompressionState),
                npuStatus = sanitizeVendorStatus(remote.npuStatus),
            )
        }
    }

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
        val brokerWasConnected = service != null
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
        repeat(SHUTDOWN_RESET_ATTEMPTS) {
            if (npuStopAcknowledged && (!resetCompression || compressionResetConfirmed)) {
                return@repeat
            }
            val result = callRemote(
                executor = controlExecutor,
                fallback = RemoteResetResult(),
                timeoutMs = CONTROL_TIMEOUT_MS,
                allowClosed = true,
            ) { remote, _ ->
                val npuStopped = runCatching {
                    remote.stopNpuLoad()
                    true
                }.getOrDefault(false)
                val compressionReset = if (resetCompression) {
                    runCatching {
                        remote.setCompressionMode(COMPRESSION_LINEAR)
                    }.getOrDefault(false)
                } else {
                    false
                }
                RemoteResetResult(
                    npuStopConfirmed = npuStopped,
                    compressionResetConfirmed = compressionReset,
                )
            }
            npuStopAcknowledged = npuStopAcknowledged || result.npuStopConfirmed
            compressionResetConfirmed =
                compressionResetConfirmed || result.compressionResetConfirmed
        }
        unbindCurrentBinding()
        clearCurrentService()
        telemetryExecutor.shutdownNow()
        controlExecutor.shutdownNow()
        val auxiliaryLanesQuiesced = awaitExecutorTerminationTogether(
            executors = listOf(telemetryExecutor, controlExecutor),
            timeoutMs = REMOTE_LANE_QUIESCE_TIMEOUT_MS,
        )
        val safeToRestart = auxiliaryLanesQuiesced && npuExecutor.isTerminated
        restartSafe.set(safeToRestart)
        if (safeToRestart) {
            synchronized(Companion) {
                if (instance === this) instance = null
            }
        }
        return VendorShutdownResult(
            brokerWasConnected = brokerWasConnected,
            // A stop response is not final if an older client set(nonzero) transaction can still
            // complete afterward on the provider Binder pool.
            npuStopConfirmed = npuStopAcknowledged && npuLaneQuiesced,
            compressionResetConfirmed = compressionResetConfirmed,
        ).also(shutdownResult::set)
    }

    override fun close() {
        closeWithResult()
    }

    /**
     * Latest-wins control drain. A phase ramp can update its setpoint every 100 ms, while a
     * product Binder call may be slower. Keeping at most one pending command prevents an
     * unbounded executor queue and ensures release-to-zero supersedes stale ramp values.
     */
    private fun scheduleNpuDrain(): Boolean {
        if (closed.get()) return false
        if (!npuDrainScheduled.compareAndSet(false, true)) return true
        var accepted = runCatching {
            npuExecutor.execute(::drainNpuCommands)
        }.isSuccess
        if (!accepted && pendingNpuCommand.get()?.intensity == 0f && !closed.get()) {
            // STOP/release-to-zero is safety-significant. Evict one stale queued NPU task and
            // reserve that slot for the latest-wins drain.
            (npuExecutor.queue.poll() as? java.util.concurrent.Future<*>)?.cancel(true)
            npuExecutor.purge()
            accepted = runCatching {
                npuExecutor.execute(::drainNpuCommands)
            }.isSuccess
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
                    runCatching {
                        if (command.intensity <= 0f) {
                            target.remote.stopNpuLoad()
                        } else {
                            target.remote.setNpuLoad(
                                command.intensity,
                                command.shape.wireValue(),
                            )
                        }
                    }.isSuccess &&
                        isCurrentRemoteCallTarget(target, allowClosed = false)
                } finally {
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

    @Suppress("DEPRECATION")
    private fun hasSignatureVendorPermission(): Boolean = runCatching {
        val permission =
            context.packageManager.getPermissionInfo(VENDOR_TELEMETRY_PERMISSION, 0)
        permission.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE ==
            PermissionInfo.PROTECTION_SIGNATURE
    }.getOrDefault(false)

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
        val submitted = runCatching {
            telemetryExecutor.execute {
                if (closed.get()) return@execute
                if (
                    !isCurrentService(
                        connection = connection,
                        bindingGeneration = bindingGeneration,
                        binder = binder,
                        serviceGeneration = serviceGeneration,
                    )
                ) {
                    return@execute
                }
                // null means the query failed or was not needed. A returned false is a verified
                // capability result and must not be conflated with a Binder/provider exception.
                val discoveredNpu = if (queryTargets.queryNpu) {
                    runCatching { remote.isNpuLoadSupported }.getOrNull()
                } else {
                    null
                }
                val discoveredSbwc = if (queryTargets.querySbwc) {
                    runCatching { remote.isSbwcControlSupported }.getOrNull()
                } else {
                    null
                }
                val merged = synchronized(connectionLock) {
                    if (
                        closed.get() ||
                        !isCurrentBindingLocked(connection, bindingGeneration) ||
                        serviceBinder !== binder ||
                        this.serviceGeneration.get() != serviceGeneration
                    ) {
                        null
                    } else {
                        mergeCapabilityDiscovery(
                            currentNpu = npuSupported,
                            currentSbwc = sbwcSupported,
                            queriedNpu = discoveredNpu,
                            queriedSbwc = discoveredSbwc,
                        ).also { result ->
                            npuSupported = result.npuSupported
                            sbwcSupported = result.sbwcSupported
                        }
                    }
                } ?: return@execute
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
                if (merged.complete) {
                    // A Binder connection is not considered healthy until both capability
                    // queries have produced verified true/false results. Resetting on the raw
                    // connection callback would turn a permanently broken broker into a tight
                    // 250 ms reconnect loop.
                    reconnectAttempt.set(0L)
                    cancelCapabilityRetryForService(
                        connection = connection,
                        bindingGeneration = bindingGeneration,
                        binder = binder,
                        serviceGeneration = serviceGeneration,
                        resetAttempt = true,
                    )
                } else {
                    scheduleCapabilityRetry(
                        remote = remote,
                        connection = connection,
                        bindingGeneration = bindingGeneration,
                        binder = binder,
                        serviceGeneration = serviceGeneration,
                    )
                }
            }
        }.isSuccess
        if (!submitted) {
            scheduleCapabilityRetry(
                remote = remote,
                connection = connection,
                bindingGeneration = bindingGeneration,
                binder = binder,
                serviceGeneration = serviceGeneration,
            )
        }
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
                runCatching { context.unbindService(connection) }
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
        if (shouldScheduleReconnectAfterBindFailure(detachedCurrent, closed.get())) {
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
            runCatching { binder.unlinkToDeath(recipient, 0) }
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
                runCatching { context.unbindService(connection) }
            }
        }
    }

    private fun scheduleReconnect() {
        if (closed.get() || !reconnectScheduled.compareAndSet(false, true)) return
        val delayMs = reconnectDelayMs(reconnectAttempt.getAndIncrement())
        if (!mainHandler.postDelayed(reconnectRunnable, delayMs)) {
            reconnectScheduled.set(false)
        }
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
        if (!capabilityRetryRunnable.compareAndSet(null, retry)) return
        val attempt = capabilityRetryAttempt.getAndIncrement()
        if (!shouldScheduleCapabilityRetry(attempt)) {
            // Keep one low-rate recovery probe instead of leaving capability discovery permanently
            // pending with no runnable. Do not recycle a live registration here: an NPU workload
            // may be pinned to it while the unrelated SBWC query is temporarily failing.
            capabilityRetryAttempt.set(MAX_CAPABILITY_RETRY_ATTEMPTS)
            if (
                closed.get() ||
                !isCurrentService(connection, bindingGeneration, binder, serviceGeneration) ||
                !mainHandler.postDelayed(retry, STEADY_CAPABILITY_RETRY_DELAY_MS)
            ) {
                capabilityRetryRunnable.compareAndSet(retry, null)
            }
            return
        }
        if (
            closed.get() ||
            !isCurrentService(connection, bindingGeneration, binder, serviceGeneration)
        ) {
            capabilityRetryRunnable.compareAndSet(retry, null)
            return
        }
        if (!mainHandler.postDelayed(retry, capabilityRetryDelayMs(attempt))) {
            capabilityRetryRunnable.compareAndSet(retry, null)
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
        val future = runCatching {
            executor.submit<T> {
                val result = block(target.remote, target.serviceSession)
                check(isCurrentRemoteCallTarget(target, allowClosed)) {
                    "Vendor service changed while a remote result was in flight"
                }
                result
            }
        }.getOrNull()
            ?: return fallback
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            future.cancel(true)
            (future as? Runnable)?.let(executor::remove)
            executor.purge()
            fallback
        } catch (_: Exception) {
            future.cancel(true)
            (future as? Runnable)?.let(executor::remove)
            executor.purge()
            fallback
        }
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
                controlExecutor.isTerminated &&
                npuExecutor.isTerminated
        if (!terminatedLater) return false
        restartSafe.set(true)
        synchronized(Companion) {
            if (instance === this) instance = null
        }
        return true
    }

    companion object {
        const val ACTION_VENDOR_SERVICE = "com.example.dpulayerlab.VENDOR_TELEMETRY"
        const val VENDOR_TELEMETRY_PERMISSION =
            "com.example.dpulayerlab.permission.ACCESS_VENDOR_TELEMETRY"
        const val COMPRESSION_LINEAR = 0
        const val COMPRESSION_AUTO = 1
        const val COMPRESSION_SBWC_REQUIRED = 2
        const val CONTROL_TIMEOUT_MS = 500L
        const val SNAPSHOT_TIMEOUT_MS = 700L
        const val MAX_REMOTE_QUEUE_DEPTH = 8
        const val SHUTDOWN_RESET_ATTEMPTS = 2
        const val NPU_QUIESCE_TIMEOUT_MS = 500L
        const val NPU_RELEASE_TIMEOUT_MS = 500L
        const val MAX_NPU_APPLY_ACK_TIMEOUT_MS = 1_000L
        const val MAX_NPU_PENDING_TIMEOUT_MS = 2_000L
        const val REMOTE_LANE_QUIESCE_TIMEOUT_MS = 200L
        const val INITIAL_RECONNECT_DELAY_MS = 250L
        const val MAX_RECONNECT_DELAY_MS = 4_000L
        const val MAX_CAPABILITY_RETRY_ATTEMPTS = 4L
        const val INITIAL_CAPABILITY_RETRY_DELAY_MS = 100L
        const val MAX_CAPABILITY_RETRY_DELAY_MS = 800L
        const val STEADY_CAPABILITY_RETRY_DELAY_MS = 4_000L
        private const val NPU_ACK_POLL_NANOS = 2_000_000L

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
                            val created = VendorBridge(context.applicationContext)
                            created.connect()
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

        private fun Float.validPercent(): Float? =
            takeIf { it.isFinite() && it in 0f..100f }

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

    private data class TerminalBindingCleanup(
        val binder: IBinder?,
        val deathRecipient: IBinder.DeathRecipient?,
        val connection: ServiceConnection?,
        val shouldUnbind: Boolean,
        val capabilityRetry: Runnable?,
    )
}

internal const val MAX_VENDOR_STATUS_CHARS = 256
private const val MAX_VENDOR_STATUS_INSPECTION_FACTOR = 4L
private const val VENDOR_STATUS_TRUNCATION_MARKER = "\u2026"

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

internal fun unattributedVendorShutdownResult(): VendorShutdownResult =
    VendorShutdownResult(
        brokerWasConnected = false,
        npuStopConfirmed = false,
        compressionResetConfirmed = false,
    )

internal fun shouldScheduleReconnectAfterBindFailure(
    detachedCurrentBinding: Boolean,
    closed: Boolean,
): Boolean = detachedCurrentBinding && !closed

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
