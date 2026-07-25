package com.example.dpulayerlab.engine

import com.example.dpulayerlab.model.LoadSetpoints
import com.example.dpulayerlab.model.LoadShape
import com.example.dpulayerlab.vendor.NpuControlCommandTicket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class LoadManagerNpuControlTest {
    @Test
    fun phaseBoundaryApplyWaitsForExactPositiveCommandAcknowledgment() {
        val adapter = TrackedNpuAdapter()
        val manager = LoadManager(adapter)
        try {
            val health = manager.applyAndConfirmNpu(
                newSetpoints = LoadSetpoints(npu = 0.6f),
                timeoutMs = 321L,
            )

            assertEquals(NpuControlState.APPLIED, health.state)
            assertTrue(health.applied)
            assertEquals(321L, adapter.lastAwaitTimeoutMs.get())
            assertEquals(
                NpuControlState.APPLIED,
                manager.npuControlHealth().state,
            )
        } finally {
            manager.closeWithResult()
        }
    }

    @Test
    fun priorAcknowledgmentCannotHideNewestPendingSetpoint() {
        val adapter = TrackedNpuAdapter(autoAcknowledge = false)
        val manager = LoadManager(adapter)
        try {
            manager.apply(LoadSetpoints(npu = 0.2f))
            val first = checkNotNull(adapter.latestRequest.get())
            adapter.setHealth(first, NpuControlState.APPLIED)

            manager.apply(LoadSetpoints(npu = 0.8f))
            val second = checkNotNull(adapter.latestRequest.get())
            assertFalse(first === second)
            adapter.setHealth(first, NpuControlState.APPLIED)

            assertEquals(
                NpuControlState.PENDING,
                manager.npuControlHealth().state,
            )
        } finally {
            manager.closeWithResult()
        }
    }

    @Test
    fun positiveBackendWithoutAcknowledgmentContractFailsClosed() {
        val manager = LoadManager(LegacyNpuAdapter)
        try {
            val health = manager.applyAndConfirmNpu(
                newSetpoints = LoadSetpoints(npu = 0.5f),
            )

            assertEquals(NpuControlState.FAILED, health.state)
            assertFalse(health.applied)
            assertEquals(
                NpuControlState.FAILED,
                manager.npuControlHealth().state,
            )
        } finally {
            manager.closeWithResult()
        }
    }

    @Test
    fun zeroSemanticSetpointWaitsForExactCommandAcknowledgment() {
        val adapter = TrackedNpuAdapter()
        val manager = LoadManager(adapter)
        try {
            val health = manager.applyAndConfirmNpu(
                newSetpoints = LoadSetpoints(npu = 0f),
                timeoutMs = 123L,
            )

            assertEquals(NpuControlState.APPLIED, health.state)
            assertTrue(health.applied)
            assertEquals(123L, adapter.lastAwaitTimeoutMs.get())
            assertEquals(0f, adapter.lastAwaitedRequest.get()?.intensity)
            // Steady-state polling remains idle; only the semantic edge requires exact APPLIED.
            assertEquals(NpuControlState.IDLE, manager.npuControlHealth().state)
        } finally {
            manager.closeWithResult()
        }
    }

    @Test
    fun pulseZeroEdgeRejectsStaleAckAfterNewerPositiveReentry() {
        val zeroAwaitEntered = CountDownLatch(1)
        val allowZeroAwaitReturn = CountDownLatch(1)
        val workerFinished = CountDownLatch(1)
        val adapter = TrackedNpuAdapter(
            awaitEntered = zeroAwaitEntered,
            allowAwaitReturn = allowZeroAwaitReturn,
        )
        val manager = LoadManager(adapter)
        val health = AtomicReference<NpuControlHealth?>()
        val failure = AtomicReference<Throwable?>()
        val worker = Thread({
            try {
                health.set(
                    manager.applyAndConfirmNpu(
                        newSetpoints = LoadSetpoints(npu = 0f),
                        restartProfile = false,
                    ),
                )
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                workerFinished.countDown()
            }
        }, "pulse-zero-edge-ack")
        try {
            manager.apply(LoadSetpoints(npu = 0.8f))
            worker.start()
            assertTrue(zeroAwaitEntered.await(2L, TimeUnit.SECONDS))
            val zeroRequest = checkNotNull(adapter.lastAwaitedRequest.get())
            assertEquals(0f, zeroRequest.intensity)

            // A following pulse attack supersedes the zero ticket while its acknowledgment is
            // blocked. Even an APPLIED result for the stale ticket must not credit the valley.
            manager.apply(
                newSetpoints = LoadSetpoints(npu = 0.6f),
                restartProfile = false,
            )
            allowZeroAwaitReturn.countDown()

            assertTrue(workerFinished.await(2L, TimeUnit.SECONDS))
            assertEquals(null, failure.get())
            assertEquals(NpuControlState.FAILED, health.get()?.state)
            assertTrue(health.get()?.detail?.contains("changed") == true)
            assertEquals(0.6f, adapter.latestRequest.get()?.intensity)
        } finally {
            allowZeroAwaitReturn.countDown()
            worker.join(2_000L)
            manager.closeWithResult()
        }
    }

    @Test
    fun pulseZeroEdgeFailsClosedWhenBackendDropsItsTicket() {
        val adapter = TrackedNpuAdapter(dropZeroRequest = true)
        val manager = LoadManager(adapter)
        try {
            assertEquals(
                NpuControlState.APPLIED,
                manager.applyAndConfirmNpu(LoadSetpoints(npu = 0.7f)).state,
            )
            val awaitCountBeforeZero = adapter.awaitCallCount.get()

            val health = manager.applyAndConfirmNpu(
                newSetpoints = LoadSetpoints(npu = 0f),
                restartProfile = false,
            )

            assertEquals(NpuControlState.FAILED, health.state)
            assertFalse(health.applied)
            assertTrue(health.detail.contains("acknowledgment"))
            assertEquals(awaitCountBeforeZero, adapter.awaitCallCount.get())
            assertEquals(NpuControlState.IDLE, manager.npuControlHealth().state)
        } finally {
            manager.closeWithResult()
        }
    }

    @Test
    fun trianglePositiveReentryTimeoutFailsClosed() {
        val adapter = TrackedNpuAdapter(
            positiveRequestState = NpuControlState.PENDING,
            pendingAwaitFailsAsTimeout = true,
        )
        val manager = LoadManager(adapter)
        try {
            assertEquals(
                NpuControlState.APPLIED,
                manager.applyAndConfirmNpu(LoadSetpoints(npu = 0f)).state,
            )

            val health = manager.applyAndConfirmNpu(
                newSetpoints = LoadSetpoints(npu = 0.4f),
                restartProfile = false,
                timeoutMs = 17L,
            )

            assertEquals(NpuControlState.FAILED, health.state)
            assertFalse(health.applied)
            assertTrue(health.detail.contains("timed out"))
            assertEquals(17L, adapter.lastAwaitTimeoutMs.get())
            assertEquals(0.4f, adapter.lastAwaitedRequest.get()?.intensity)
        } finally {
            manager.closeWithResult()
        }
    }

    @Test
    fun semanticZeroPublishesFreshTicketAfterConfirmedReleaseSupersedesManagerRequest() {
        val adapter = TrackedNpuAdapter(
            releasePublishesNewZero = true,
            rejectSupersededAwait = true,
        )
        val manager = LoadManager(adapter)
        try {
            assertEquals(
                NpuControlState.APPLIED,
                manager.applyAndConfirmNpu(LoadSetpoints(npu = 0.7f)).state,
            )
            assertTrue(manager.releaseLoadsAndConfirm())
            val releaseTicket = checkNotNull(adapter.releasePublishedRequest.get())
            assertEquals(releaseTicket, adapter.latestRequest.get())

            val health = manager.applyAndConfirmNpu(
                newSetpoints = LoadSetpoints(npu = 0f),
                restartProfile = false,
            )
            val semanticTicket = checkNotNull(adapter.lastAwaitedRequest.get())

            assertEquals(NpuControlState.APPLIED, health.state)
            assertTrue(semanticTicket.version > releaseTicket.version)
            assertEquals(0f, semanticTicket.intensity)
            assertEquals(semanticTicket, adapter.latestRequest.get())
        } finally {
            manager.closeWithResult()
        }
    }

    @Test
    fun topologyZeroBeforeLatePositivePublicationInvalidatesCapturedEpoch() {
        val adapter = TrackedNpuAdapter()
        val manager = LoadManager(adapter)
        val workerReady = CountDownLatch(1)
        val allowLatePositive = CountDownLatch(1)
        val workerFinished = CountDownLatch(1)
        val health = AtomicReference<NpuControlHealth?>()
        val failure = AtomicReference<Throwable?>()
        val capturedEpoch = manager.npuCommandEpoch()
        val worker = Thread({
            try {
                workerReady.countDown()
                check(allowLatePositive.await(2L, TimeUnit.SECONDS))
                health.set(
                    manager.applyAndConfirmNpu(
                        newSetpoints = LoadSetpoints(npu = 0.7f),
                        expectedCommandEpoch = capturedEpoch,
                    ),
                )
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                workerFinished.countDown()
            }
        }, "late-positive-publication")
        try {
            worker.start()
            assertTrue(workerReady.await(2L, TimeUnit.SECONDS))

            // Mirrors onProducerTopologyPending(): zero and epoch invalidation complete before the
            // previously captured positive apply is allowed to publish its request.
            manager.releaseLoads()
            allowLatePositive.countDown()

            assertTrue(workerFinished.await(2L, TimeUnit.SECONDS))
            assertEquals(null, failure.get())
            assertEquals(NpuControlState.FAILED, health.get()?.state)
            assertEquals(0L, adapter.positiveRequestCount.get())
            assertEquals(0f, adapter.lastRequestedIntensity.get())
        } finally {
            allowLatePositive.countDown()
            worker.join(2_000L)
            manager.closeWithResult()
        }
    }

    @Test
    fun acknowledgedPositiveCannotCommitAfterTopologyZeroWinsBeforeContinuation() {
        val awaitEntered = CountDownLatch(1)
        val allowAcknowledgment = CountDownLatch(1)
        val acknowledgmentReturned = CountDownLatch(1)
        val allowControllerCommit = CountDownLatch(1)
        val workerFinished = CountDownLatch(1)
        val adapter = TrackedNpuAdapter(
            awaitEntered = awaitEntered,
            allowAwaitReturn = allowAcknowledgment,
        )
        val manager = LoadManager(adapter)
        val runOwner = Any()
        val commandOwner = RuntimeLoadCommandOwner(
            commandEpoch = manager.npuCommandEpoch(),
            runOwner = runOwner,
            producerGeneration = 41L,
        )
        val health = AtomicReference<NpuControlHealth?>()
        val commitAllowed = AtomicBoolean(true)
        val highDisplayPublished = AtomicBoolean(false)
        val orderedZeroConfirmed = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>()
        val worker = Thread({
            try {
                health.set(
                    manager.applyAndConfirmNpu(
                        newSetpoints = LoadSetpoints(npu = 0.8f),
                        expectedCommandEpoch = commandOwner.commandEpoch,
                    ),
                )
                acknowledgmentReturned.countDown()
                check(allowControllerCommit.await(2L, TimeUnit.SECONDS))
                val allowed = runtimeLoadCommandOwnerStillActive(
                    owner = commandOwner,
                    currentCommandEpoch = manager.npuCommandEpoch(),
                    currentRunOwner = runOwner,
                    runOwnerActive = true,
                    currentProducerGeneration = commandOwner.producerGeneration,
                    producerRecoveryPaused = true,
                )
                commitAllowed.set(allowed)
                if (allowed) {
                    highDisplayPublished.set(true)
                } else {
                    orderedZeroConfirmed.set(manager.releaseLoadsAndConfirm())
                }
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                workerFinished.countDown()
            }
        }, "ack-before-controller-continuation")
        try {
            worker.start()
            assertTrue(awaitEntered.await(2L, TimeUnit.SECONDS))
            allowAcknowledgment.countDown()
            assertTrue(acknowledgmentReturned.await(2L, TimeUnit.SECONDS))
            assertEquals(NpuControlState.APPLIED, health.get()?.state)

            // The adapter acknowledgment and LoadManager identity check already succeeded, but
            // Main has not committed display/progress yet. The topology callback wins this gap.
            manager.releaseLoads()
            allowControllerCommit.countDown()

            assertTrue(workerFinished.await(2L, TimeUnit.SECONDS))
            assertEquals(null, failure.get())
            assertFalse(commitAllowed.get())
            assertFalse(highDisplayPublished.get())
            assertTrue(orderedZeroConfirmed.get())
            assertEquals(0f, adapter.lastRequestedIntensity.get())
        } finally {
            allowAcknowledgment.countDown()
            allowControllerCommit.countDown()
            worker.join(2_000L)
            manager.closeWithResult()
        }
    }

    @Test
    fun runtimeLoadCommitRequiresExactRunOwnerGenerationAndRecoveryState() {
        val runOwner = Any()
        val owner = RuntimeLoadCommandOwner(
            commandEpoch = 7L,
            runOwner = runOwner,
            producerGeneration = 11L,
        )

        assertTrue(
            runtimeLoadCommandOwnerStillActive(
                owner = owner,
                currentCommandEpoch = 7L,
                currentRunOwner = runOwner,
                runOwnerActive = true,
                currentProducerGeneration = 11L,
                producerRecoveryPaused = false,
            ),
        )
        assertFalse(
            runtimeLoadCommandOwnerStillActive(
                owner = owner,
                currentCommandEpoch = 7L,
                currentRunOwner = Any(),
                runOwnerActive = true,
                currentProducerGeneration = 11L,
                producerRecoveryPaused = false,
            ),
        )
        assertFalse(
            runtimeLoadCommandOwnerStillActive(
                owner = owner,
                currentCommandEpoch = 7L,
                currentRunOwner = runOwner,
                runOwnerActive = false,
                currentProducerGeneration = 11L,
                producerRecoveryPaused = false,
            ),
        )
        assertFalse(
            runtimeLoadCommandOwnerStillActive(
                owner = owner,
                currentCommandEpoch = 7L,
                currentRunOwner = runOwner,
                runOwnerActive = true,
                currentProducerGeneration = 12L,
                producerRecoveryPaused = false,
            ),
        )
        assertFalse(
            runtimeLoadCommandOwnerStillActive(
                owner = owner,
                currentCommandEpoch = 7L,
                currentRunOwner = runOwner,
                runOwnerActive = true,
                currentProducerGeneration = 11L,
                producerRecoveryPaused = true,
            ),
        )
    }

    @Test
    fun reflectionMailboxRejectsStaleWaveformAfterVersionedZeroPublication() {
        val positiveTicket = reflectionTicket(version = 1L)
        val zeroTicket = reflectionTicket(version = 2L)
        val mailbox = ReflectionNpuCommandMailbox(positiveTicket)
        val staleSnapshotReady = CountDownLatch(1)
        val allowStalePublication = CountDownLatch(1)
        val stalePublicationAccepted = AtomicBoolean(true)
        val workerFailure = AtomicReference<Throwable?>()
        val worker = Thread({
            try {
                staleSnapshotReady.countDown()
                check(allowStalePublication.await(2L, TimeUnit.SECONDS))
                stalePublicationAccepted.set(
                    mailbox.publishIfCurrent(
                        ticket = positiveTicket,
                        intensity = 0.8f,
                        force = true,
                        epsilon = 0.005f,
                    ),
                )
            } catch (error: Throwable) {
                workerFailure.set(error)
            }
        }, "stale-reflection-waveform")

        try {
            worker.start()
            assertTrue(staleSnapshotReady.await(2L, TimeUnit.SECONDS))
            mailbox.replaceDesired(zeroTicket)
            assertTrue(
                mailbox.publishIfCurrent(
                    ticket = zeroTicket,
                    intensity = 0f,
                    force = true,
                    epsilon = 0.005f,
                ),
            )
            allowStalePublication.countDown()
            worker.join(2_000L)

            assertFalse(worker.isAlive)
            assertEquals(null, workerFailure.get())
            assertFalse(stalePublicationAccepted.get())
            val queued = checkNotNull(mailbox.takePending())
            assertEquals(zeroTicket, queued.ticket)
            assertEquals(0f, queued.intensity)
            assertTrue(mailbox.isCurrent(zeroTicket))
            assertFalse(mailbox.isCurrent(positiveTicket))
        } finally {
            allowStalePublication.countDown()
            worker.join(2_000L)
        }
    }

    @Test
    fun reflectionMailboxDoesNotSuppressSameIntensityForNewerTicket() {
        val first = reflectionTicket(version = 11L)
        val second = reflectionTicket(version = 12L)
        val mailbox = ReflectionNpuCommandMailbox(first)
        assertTrue(
            mailbox.publishIfCurrent(
                ticket = first,
                intensity = 0.4f,
                force = false,
                epsilon = 0.005f,
            ),
        )
        assertEquals(first, checkNotNull(mailbox.takePending()).ticket)

        mailbox.replaceDesired(second)
        assertTrue(
            mailbox.publishIfCurrent(
                ticket = second,
                intensity = 0.4f,
                force = false,
                epsilon = 0.005f,
            ),
        )

        assertEquals(second, checkNotNull(mailbox.takePending()).ticket)
    }

    private fun reflectionTicket(version: Long) = NpuControlCommandTicket(
        version = version,
        serviceSession = 1L,
        submittedAtNanos = version,
    )

    private class TrackedNpuAdapter(
        private val autoAcknowledge: Boolean = true,
        private val awaitEntered: CountDownLatch? = null,
        private val allowAwaitReturn: CountDownLatch? = null,
        private val dropZeroRequest: Boolean = false,
        private val positiveRequestState: NpuControlState? = null,
        private val pendingAwaitFailsAsTimeout: Boolean = false,
        private val releasePublishesNewZero: Boolean = false,
        private val rejectSupersededAwait: Boolean = false,
    ) : NpuWorkloadAdapter {
        private val version = AtomicLong(0L)
        private val healthByRequest =
            ConcurrentHashMap<TestNpuControlRequest, NpuControlHealth>()
        val latestRequest = AtomicReference<TestNpuControlRequest?>()
        val lastAwaitedRequest = AtomicReference<TestNpuControlRequest?>()
        val lastAwaitTimeoutMs = AtomicLong(-1L)
        val awaitCallCount = AtomicLong(0L)
        val positiveRequestCount = AtomicLong(0L)
        val lastRequestedIntensity = AtomicReference(0f)
        val releasePublishedRequest = AtomicReference<TestNpuControlRequest?>()

        override fun isAvailable(): Boolean = true

        override fun setIntensity(intensity: Float) = Unit

        override fun requestLoad(
            intensity: Float,
            shape: LoadShape,
            restartProfile: Boolean,
        ): NpuControlRequest? {
            lastRequestedIntensity.set(intensity)
            if (intensity > 0f) positiveRequestCount.incrementAndGet()
            if (dropZeroRequest && intensity <= 0f) {
                latestRequest.set(null)
                return null
            }
            val request = TestNpuControlRequest(
                version = version.incrementAndGet(),
                intensity = intensity,
            )
            latestRequest.set(request)
            healthByRequest[request] = NpuControlHealth(
                state = if (intensity > 0f && positiveRequestState != null) {
                    positiveRequestState
                } else if (autoAcknowledge) {
                    NpuControlState.APPLIED
                } else {
                    NpuControlState.PENDING
                },
                detail = "test",
            )
            return request
        }

        override fun awaitControl(
            request: NpuControlRequest,
            timeoutMs: Long,
        ): NpuControlHealth {
            awaitCallCount.incrementAndGet()
            lastAwaitTimeoutMs.set(timeoutMs)
            val tracked = request as? TestNpuControlRequest
                ?: return NpuControlHealth.failed("wrong test request type")
            lastAwaitedRequest.set(tracked)
            if (rejectSupersededAwait && latestRequest.get() !== tracked) {
                return NpuControlHealth.failed("test request was superseded")
            }
            awaitEntered?.countDown()
            if (allowAwaitReturn != null && !allowAwaitReturn.await(2L, TimeUnit.SECONDS)) {
                return NpuControlHealth.failed("test acknowledgment latch timed out")
            }
            val health =
                healthByRequest[tracked] ?: return NpuControlHealth.failed("missing test request")
            return if (pendingAwaitFailsAsTimeout && health.state == NpuControlState.PENDING) {
                NpuControlHealth.failed("test acknowledgment timed out after ${timeoutMs}ms")
            } else {
                health
            }
        }

        override fun controlHealth(
            request: NpuControlRequest,
            pendingTimeoutMs: Long,
        ): NpuControlHealth {
            val tracked = request as? TestNpuControlRequest
                ?: return NpuControlHealth.failed("wrong test request type")
            return healthByRequest[tracked] ?: NpuControlHealth.failed("missing test request")
        }

        fun setHealth(request: TestNpuControlRequest, state: NpuControlState) {
            healthByRequest[request] = NpuControlHealth(state, "test")
        }

        override fun releaseAndConfirm(): Boolean {
            if (releasePublishesNewZero) {
                releasePublishedRequest.set(
                    requestLoad(
                        intensity = 0f,
                        shape = LoadShape.STEADY,
                        restartProfile = false,
                    ) as? TestNpuControlRequest,
                )
            }
            return true
        }

        override fun status(): String = "test"

        override fun closeWithResult(): NpuShutdownResult = NpuShutdownResult(
            releaseConfirmed = true,
            backendCloseConfirmed = true,
            mayRemainActive = false,
            detail = "test",
        )
    }

    private data class TestNpuControlRequest(
        val version: Long,
        val intensity: Float,
    ) : NpuControlRequest

    private object LegacyNpuAdapter : NpuWorkloadAdapter {
        override fun isAvailable(): Boolean = true

        override fun setIntensity(intensity: Float) = Unit

        override fun releaseAndConfirm(): Boolean = true

        override fun status(): String = "legacy"

        override fun closeWithResult(): NpuShutdownResult = NpuShutdownResult(
            releaseConfirmed = true,
            backendCloseConfirmed = true,
            mayRemainActive = false,
            detail = "legacy",
        )
    }
}
