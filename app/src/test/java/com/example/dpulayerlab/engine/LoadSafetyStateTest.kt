package com.example.dpulayerlab.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class LoadSafetyStateTest {
    @Before
    fun clearState() {
        LoadSafetyState.recordNpuLoadIdle(confirmed = true)
        LoadSafetyState.recordNpuBackendCleanup(confirmed = true)
    }

    @After
    fun restoreState() {
        LoadSafetyState.recordNpuLoadIdle(confirmed = true)
        LoadSafetyState.recordNpuBackendCleanup(confirmed = true)
    }

    @Test
    fun loadConfirmationCannotClearBackendCleanupFailure() {
        LoadSafetyState.recordNpuBackendCleanup(confirmed = false)
        LoadSafetyState.recordNpuLoadIdle(confirmed = false)

        LoadSafetyState.recordNpuLoadIdle(confirmed = true)

        assertFalse(LoadSafetyState.hasUnconfirmedNpuLoadIdle())
        assertTrue(LoadSafetyState.hasUnconfirmedNpuBackendCleanup())
        assertTrue(LoadSafetyState.hasUnconfirmedNpuCleanup())
    }

    @Test
    fun backendConfirmationCannotClearLoadIdleFailure() {
        LoadSafetyState.recordNpuLoadIdle(confirmed = false)
        LoadSafetyState.recordNpuBackendCleanup(confirmed = false)

        LoadSafetyState.recordNpuBackendCleanup(confirmed = true)

        assertTrue(LoadSafetyState.hasUnconfirmedNpuLoadIdle())
        assertFalse(LoadSafetyState.hasUnconfirmedNpuBackendCleanup())
        assertTrue(LoadSafetyState.hasUnconfirmedNpuCleanup())
    }

    @Test
    fun initializationCleanupFailureMarksBothStatesFailClosed() {
        LoadSafetyState.markNpuCleanupUnconfirmed()

        assertTrue(LoadSafetyState.hasUnconfirmedNpuLoadIdle())
        assertTrue(LoadSafetyState.hasUnconfirmedNpuBackendCleanup())
        assertTrue(LoadSafetyState.hasUnconfirmedNpuCleanup())
    }

    @Test
    fun knownIdleSurvivesFailedLaterCloseUntilAnotherNonZeroRequest() {
        val evidence = ReflectionKnownIdleState(initiallyKnownIdle = false)

        evidence.recordOrderedRelease(confirmed = true)
        evidence.recordOrderedRelease(confirmed = false)

        assertTrue(evidence.isKnownIdle)

        evidence.markNonZeroRequested()

        assertFalse(evidence.isKnownIdle)
        evidence.recordOrderedRelease(confirmed = false)
        assertFalse(evidence.isKnownIdle)
        evidence.recordOrderedRelease(confirmed = true)
        assertTrue(evidence.isKnownIdle)
    }

    @Test
    fun initializationCleanupStateControlsInitialIdleEvidence() {
        assertTrue(ReflectionKnownIdleState(initiallyKnownIdle = true).isKnownIdle)
        assertFalse(ReflectionKnownIdleState(initiallyKnownIdle = false).isKnownIdle)
    }

    @Test
    fun managerWithoutLocalWorkersDoesNotRetainProcessLease() {
        val manager = LoadManager(BlockingNpuAdapter(releaseResult = true))
        val otherOwner = Any()
        try {
            assertTrue(manager.start())
            assertTrue(LoadSafetyState.tryAcquireLocalWorkerLease(otherOwner))
        } finally {
            LoadSafetyState.releaseLocalWorkerLeaseIfEmpty(otherOwner)
            manager.closeWithResult()
        }
    }

    @Test
    fun permanentWorkerFailureLatchIsFirstWinsAndBoundsUntrustedDetails() {
        val latch = PermanentLocalWorkerFailureLatch()
        val first = latch.record(
            workerName = "worker-" + "x".repeat(100),
            error = IllegalStateException("first-line\nsecond-line-" + "y".repeat(500)),
        )
        val repeated = latch.record(
            workerName = "other-worker",
            error = IllegalArgumentException("later"),
        )

        assertEquals(first, repeated)
        assertEquals(first, latch.peek())
        assertTrue(first.workerName.length <= 64)
        assertEquals(IllegalStateException::class.java.name, first.exceptionClass)
        assertEquals("first-line", first.detail)
        assertFalse(first.summary().contains('\n'))
    }

    @Test
    fun permanentWorkerFailureLatchAlsoCapturesNonExceptionErrors() {
        val latch = PermanentLocalWorkerFailureLatch()

        val failure = latch.record("DpuLab-CPU-0", AssertionError("invariant"))

        assertEquals(AssertionError::class.java.name, failure.exceptionClass)
        assertEquals("invariant", failure.detail)
    }

    @Test
    fun onlyAnInterruptDuringAnActiveRunIsAWorkerFailure() {
        assertTrue(
            shouldTreatLocalWorkerInterruptAsFailure(
                interrupted = true,
                running = true,
            ),
        )
        assertFalse(
            shouldTreatLocalWorkerInterruptAsFailure(
                interrupted = true,
                running = false,
            ),
        )
        assertFalse(
            shouldTreatLocalWorkerInterruptAsFailure(
                interrupted = false,
                running = true,
            ),
        )
    }

    @Test
    fun localWorkerLeaseBlocksAnotherManagerUntilTheLastWorkerActuallyStops() {
        val firstOwner = Any()
        val secondOwner = Any()
        val workerStarted = CountDownLatch(1)
        val allowWorkerStop = CountDownLatch(1)
        val worker = Thread({
            try {
                workerStarted.countDown()
                allowWorkerStop.await()
            } finally {
                LoadSafetyState.recordLocalWorkerStopped(
                    firstOwner,
                    Thread.currentThread(),
                )
            }
        }, "LoadSafetyStateTest-worker")

        try {
            assertTrue(LoadSafetyState.tryAcquireLocalWorkerLease(firstOwner))
            assertTrue(LoadSafetyState.registerLocalWorker(firstOwner, worker))
            worker.start()
            assertTrue(workerStarted.await(1, TimeUnit.SECONDS))

            assertTrue(LoadSafetyState.hasForeignLocalWorkers(secondOwner))
            assertFalse(LoadSafetyState.tryAcquireLocalWorkerLease(firstOwner))
            assertFalse(LoadSafetyState.tryAcquireLocalWorkerLease(secondOwner))

            allowWorkerStop.countDown()
            worker.join(1_000)
            assertFalse(worker.isAlive)
            assertFalse(LoadSafetyState.hasLiveLocalWorkers())
            assertTrue(LoadSafetyState.tryAcquireLocalWorkerLease(secondOwner))
        } finally {
            allowWorkerStop.countDown()
            worker.interrupt()
            worker.join(1_000)
            LoadSafetyState.releaseLocalWorkerLeaseIfEmpty(firstOwner)
            LoadSafetyState.releaseLocalWorkerLeaseIfEmpty(secondOwner)
        }
    }

    @Test
    fun partialWorkerStartIsJoinedBeforeTheSameManagerCanRetry() {
        val startCalls = AtomicInteger(0)
        val manager = LoadManager(
            npuAdapter = BlockingNpuAdapter(releaseResult = true),
            cpuWorkerCount = 2,
            memoryWorkerCount = 0,
            memoryWorkingSetBytes = 0,
            workerStarter = { worker ->
                if (startCalls.incrementAndGet() == 2) {
                    throw IllegalStateException("synthetic second-worker start failure")
                }
                worker.start()
            },
        )

        try {
            assertFalse(manager.start())
            assertFalse(LoadSafetyState.hasLiveLocalWorkers())
            assertTrue(manager.start())
        } finally {
            val shutdown = manager.closeWithResult()
            assertTrue(shutdown.workersStopped)
        }
    }

    @Test
    fun fatalWorkerStartRollsBackOwnershipBeforeRethrow() {
        val fatal = ThreadDeath()
        val startCalls = AtomicInteger(0)
        val manager = LoadManager(
            npuAdapter = BlockingNpuAdapter(releaseResult = true),
            cpuWorkerCount = 1,
            memoryWorkerCount = 0,
            memoryWorkingSetBytes = 0,
            workerStarter = { worker ->
                if (startCalls.incrementAndGet() == 1) throw fatal
                worker.start()
            },
        )

        var thrown: ThreadDeath? = null
        try {
            manager.start()
        } catch (error: ThreadDeath) {
            thrown = error
        }

        try {
            assertTrue(thrown === fatal)
            assertFalse(LoadSafetyState.hasLiveLocalWorkers())
            assertTrue(manager.start())
        } finally {
            val shutdown = manager.closeWithResult()
            assertTrue(shutdown.workersStopped)
        }
    }

    @Test
    fun allocationPressureDuringPartialStartReclaimsWorkersBeforeRethrowingOriginalOom() {
        val fatal = OutOfMemoryError("synthetic worker-start allocation pressure")
        val startCalls = AtomicInteger(0)
        val manager = LoadManager(
            npuAdapter = BlockingNpuAdapter(releaseResult = true),
            cpuWorkerCount = 2,
            memoryWorkerCount = 0,
            memoryWorkingSetBytes = 0,
            workerStarter = { worker ->
                if (startCalls.incrementAndGet() == 2) {
                    throw fatal
                }
                worker.start()
            },
        )

        var thrown: OutOfMemoryError? = null
        try {
            try {
                manager.start()
            } catch (error: OutOfMemoryError) {
                thrown = error
            }
            assertTrue(thrown === fatal)
            assertTrue(manager.hasMemoryAllocationFailure())
            assertFalse(LoadSafetyState.hasLiveLocalWorkers())

            manager.clearMemoryAllocationFailure()
            assertTrue(manager.start())
        } finally {
            val shutdown = manager.closeWithResult()
            assertTrue(shutdown.workersStopped)
        }
    }

    @Test
    fun releaseFirstFailureIsSerializedBeforeCloseRescuePublication() {
        val releaseEntered = CountDownLatch(1)
        val allowReleaseReturn = CountDownLatch(1)
        val releaseFinished = CountDownLatch(1)
        val closeAttempted = CountDownLatch(1)
        val closeFinished = CountDownLatch(1)
        val closeResult = AtomicReference<LoadShutdownResult?>()
        val adapter = BlockingNpuAdapter(
            releaseEntered = releaseEntered,
            allowReleaseReturn = allowReleaseReturn,
            releaseResult = false,
        )
        val manager = LoadManager(adapter)
        val releaseThread = Thread({
            try {
                manager.releaseLoadsAndConfirm()
            } finally {
                releaseFinished.countDown()
            }
        }, "LoadSafetyStateTest-release")
        val closeThread = Thread({
            try {
                closeAttempted.countDown()
                closeResult.set(manager.closeWithResult())
            } finally {
                closeFinished.countDown()
            }
        }, "LoadSafetyStateTest-close")

        try {
            releaseThread.start()
            assertTrue(releaseEntered.await(1, TimeUnit.SECONDS))
            closeThread.start()
            assertTrue(closeAttempted.await(1, TimeUnit.SECONDS))

            // Both lifecycle calls share the LoadManager monitor: close cannot overtake the
            // delayed release publication and then be overwritten by its stale false result.
            assertFalse(closeFinished.await(100, TimeUnit.MILLISECONDS))
            allowReleaseReturn.countDown()
            assertTrue(releaseFinished.await(1, TimeUnit.SECONDS))
            assertTrue(closeFinished.await(1, TimeUnit.SECONDS))
            assertTrue(LoadSafetyState.hasUnconfirmedNpuLoadIdle())

            val shutdown = checkNotNull(closeResult.get())
            LoadSafetyState.recordNpuLoadIdle(shutdown.npu.releaseConfirmed)
            assertFalse(LoadSafetyState.hasUnconfirmedNpuLoadIdle())
        } finally {
            allowReleaseReturn.countDown()
            releaseThread.interrupt()
            closeThread.interrupt()
            releaseThread.join(1_000)
            closeThread.join(1_000)
            manager.closeWithResult()
        }
    }

    @Test
    fun closeFirstPreventsDelayedReleaseFailureFromRepublishingTheLatch() {
        val adapter = BlockingNpuAdapter(releaseResult = false)
        val manager = LoadManager(adapter)

        val shutdown = manager.closeWithResult()
        LoadSafetyState.recordNpuLoadIdle(shutdown.npu.releaseConfirmed)
        assertFalse(LoadSafetyState.hasUnconfirmedNpuLoadIdle())

        assertTrue(manager.releaseLoadsAndConfirm())
        assertEquals(0, adapter.releaseCalls.get())
        assertFalse(LoadSafetyState.hasUnconfirmedNpuLoadIdle())
    }

    private class BlockingNpuAdapter(
        private val releaseEntered: CountDownLatch? = null,
        private val allowReleaseReturn: CountDownLatch? = null,
        private val releaseResult: Boolean,
    ) : NpuWorkloadAdapter {
        val releaseCalls = AtomicInteger(0)

        override fun isAvailable(): Boolean = true

        override fun setIntensity(intensity: Float) = Unit

        override fun releaseAndConfirm(): Boolean {
            releaseCalls.incrementAndGet()
            releaseEntered?.countDown()
            allowReleaseReturn?.await(1, TimeUnit.SECONDS)
            return releaseResult
        }

        override fun status(): String = "test"

        override fun closeWithResult(): NpuShutdownResult = NpuShutdownResult(
            releaseConfirmed = true,
            backendCloseConfirmed = true,
            mayRemainActive = false,
            detail = "test close confirmed",
        )
    }
}
