package com.example.dpulayerlab.engine

import com.example.dpulayerlab.monitor.SystemMonitor
import com.example.dpulayerlab.render.RendererSafetyState
import com.example.dpulayerlab.render.PinnedMediaCleanupState
import com.example.dpulayerlab.vendor.VendorBridge
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Activity-free backend cleanup payload. The process coordinator may retain this object until its
 * deadline without retaining an Activity, Window, Compose tree, controller, or coroutine Job.
 */
internal class ControllerBackendCleanup(
    private val loadManager: LoadManager,
    private val systemMonitor: SystemMonitor,
    private val vendorBridge: VendorBridge,
    private val frontendCleanupConfirmed: AtomicBoolean,
    private val powerStateReceiverCleanupConfirmed: AtomicBoolean,
    private val telemetryLifecycleIntegrityConfirmed: AtomicBoolean,
    private val performancePolicyRestoreConfirmed: AtomicBoolean,
    private val performanceSessionIntegrityConfirmed: AtomicBoolean,
    private val publishedResult: AtomicReference<Boolean?>,
) : ControllerBackendCleanupOperation {
    override fun cleanup(remainingBudgetMs: Long): ControllerBackendCleanupOutcome {
        val startedNanos = System.nanoTime()
        // A timed-out telemetry worker can outlive its caller. Close and join that Activity-free
        // lane before tearing down the LoadManager counters/NPU adapter it reads.
        val dependentShutdown = closeDependentBackendsAfterTelemetryStop(
            stopTelemetry = systemMonitor::stopLocalSamplingForShutdown,
        ) {
            val loadShutdown = loadManager.closeWithResult()
            // Lifecycle destruction is not proof that a physical producer disappeared. The normal
            // run finalizer owns compression reset after its renderer teardown barrier.
            val monitorShutdown = systemMonitor.close(resetCompression = false)
            loadShutdown to monitorShutdown
        }
        val closedDependencies = when (dependentShutdown) {
            DependentBackendCloseResult.Blocked -> {
                // The sample worker can still be executing LoadManager or VendorBridge code.
                // Closing either dependency here would create a use-after-close race. Preserve
                // both sticky safety latches and let the process cleanup coordinator block every
                // later owner.
                LoadSafetyState.recordNpuLoadIdle(confirmed = false)
                LoadSafetyState.recordNpuBackendCleanup(confirmed = false)
                publishedResult.set(false)
                return ControllerBackendCleanupOutcome.failed(
                    "unconfirmed: local telemetry sample lane before dependent teardown",
                )
            }
            is DependentBackendCloseResult.Closed -> dependentShutdown.value
        }
        val (loadShutdown, monitorShutdown) = closedDependencies
        val vendorShutdown = monitorShutdown.vendor

        val vendorStopRescued =
            loadShutdown.npu.backendCloseConfirmed &&
                vendorShutdown.brokerWasConnected &&
                vendorShutdown.npuStopConfirmed
        val npuReleaseConfirmed =
            loadShutdown.npu.releaseConfirmed || vendorStopRescued
        LoadSafetyState.recordNpuLoadIdle(npuReleaseConfirmed)
        LoadSafetyState.recordNpuBackendCleanup(
            loadShutdown.npu.backendCloseConfirmed,
        )

        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(
            (System.nanoTime() - startedNanos).coerceAtLeast(0L),
        )
        val restartBudgetMs = (remainingBudgetMs - elapsedMs).coerceAtLeast(0L)
        val vendorRestartSafe =
            restartBudgetMs > 0L &&
                vendorBridge.awaitRestartSafeAfterClose(restartBudgetMs)
        val rendererReleased = !RendererSafetyState.hasUnconfirmedTeardown()
        val selectedMediaReleased = !PinnedMediaCleanupState.hasUnconfirmedCleanup()
        val systemUiReleased =
            !ProcessTestWindowIsolationLeaseRegistry.hasActiveLease()
        val confirmed =
            frontendCleanupConfirmed.get() &&
                powerStateReceiverCleanupConfirmed.get() &&
                telemetryLifecycleIntegrityConfirmed.get() &&
                performancePolicyRestoreConfirmed.get() &&
                performanceSessionIntegrityConfirmed.get() &&
                loadShutdown.workersStopped &&
                npuReleaseConfirmed &&
                loadShutdown.npu.backendCloseConfirmed &&
                monitorShutdown.localSampleLaneStopped &&
                monitorShutdown.surfaceFlingerStopped &&
                vendorRestartSafe &&
                rendererReleased &&
                selectedMediaReleased &&
                systemUiReleased
        publishedResult.set(confirmed)
        if (confirmed) {
            return ControllerBackendCleanupOutcome.confirmed(
                "backend workers, probes, Binder lanes, renderer and SystemUI released",
            )
        }
        val failures = buildList {
            if (!frontendCleanupConfirmed.get()) add("frontend cleanup")
            if (!powerStateReceiverCleanupConfirmed.get()) add("power-state receiver")
            if (!telemetryLifecycleIntegrityConfirmed.get()) {
                add("telemetry lifecycle integrity")
            }
            if (!performancePolicyRestoreConfirmed.get()) add("performance policy restore")
            if (!performanceSessionIntegrityConfirmed.get()) {
                add("performance session integrity")
            }
            if (!loadShutdown.workersStopped) add("local workers")
            if (!npuReleaseConfirmed) add("NPU zero")
            if (!loadShutdown.npu.backendCloseConfirmed) add("NPU backend close")
            if (!monitorShutdown.localSampleLaneStopped) add("local telemetry sample lane")
            if (!monitorShutdown.surfaceFlingerStopped) add("SurfaceFlinger probe")
            if (!vendorRestartSafe) add("vendor Binder restart-safe")
            if (!rendererReleased) add("physical renderer")
            if (!selectedMediaReleased) add("selected-media descriptor")
            if (!systemUiReleased) add("SystemUI lease")
        }
        return ControllerBackendCleanupOutcome.failed(
            "unconfirmed: ${failures.joinToString()}",
        )
    }
}

/**
 * Enforces the ownership edge between telemetry and the mutable backends it samples. A negative
 * stop acknowledgment is terminal: callers must not invoke [closeDependents] afterward.
 */
internal sealed interface DependentBackendCloseResult<out T> {
    data object Blocked : DependentBackendCloseResult<Nothing>
    data class Closed<T>(val value: T) : DependentBackendCloseResult<T>
}

internal fun <T> closeDependentBackendsAfterTelemetryStop(
    stopTelemetry: () -> Boolean,
    closeDependents: () -> T,
): DependentBackendCloseResult<T> =
    if (!stopTelemetry()) {
        DependentBackendCloseResult.Blocked
    } else {
        DependentBackendCloseResult.Closed(closeDependents())
    }
