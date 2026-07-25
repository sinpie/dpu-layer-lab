package com.example.dpulayerlab.engine

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadThreadStartTest {

    @Test
    fun startFailureReturnsOwnershipToCallerWithoutRunningThread() {
        val failure = IllegalStateException("start rejected")
        val thread = Thread({ error("must not run") }, "load-start-failure-test")

        val result = startLoadThread(thread) { throw failure }

        assertSame(failure, result)
        assertEquals(Thread.State.NEW, thread.state)
    }

    @Test
    fun loadRollbackFailureMergePromotesCleanupFatalWithoutReplacingItsIdentity() {
        val startFailure = IllegalStateException("start")
        val cleanupFatal = OutOfMemoryError("rollback")

        val terminal = mergeLoadFailurePreservingFatal(startFailure, cleanupFatal)

        assertSame(cleanupFatal, terminal)
        assertTrue(cleanupFatal.suppressed.any { it === startFailure })
    }

    @Test
    fun successfulStartReturnsOnlyAfterThreadOwnershipTransfers() {
        val ran = CountDownLatch(1)
        val thread = Thread({ ran.countDown() }, "load-start-success-test")

        assertNull(startLoadThread(thread))
        assertTrue(ran.await(1, TimeUnit.SECONDS))
        thread.join(1_000L)
        assertTrue(!thread.isAlive)
    }

    @Test
    fun constructionRollbackShutsDownIdleOwnedExecutor() {
        val executor = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        executor.execute { started.countDown() }
        assertTrue(started.await(1, TimeUnit.SECONDS))

        assertTrue(shutdownLoadExecutorAndAwait(executor, timeoutMs = 1_000L))
        assertTrue(executor.isTerminated)
    }

    @Test
    fun npuConstructionTransactionRollsBackAdapterOnAllocationFailure() {
        val adapter = RecordingNpuAdapter()
        val failure = OutOfMemoryError("state allocation")
        var thrown: OutOfMemoryError? = null

        try {
            constructWithNpuAdapterRollback<RecordingNpuAdapter, Unit>(adapter) {
                throw failure
            }
        } catch (error: OutOfMemoryError) {
            thrown = error
        }

        assertSame(failure, thrown)
        assertEquals(1, adapter.closeCalls)
    }

    @Test
    fun npuConstructionTransactionTransfersOwnershipOnlyAfterFactorySuccess() {
        val adapter = RecordingNpuAdapter()

        val result = constructWithNpuAdapterRollback(adapter) { "ready" }

        assertEquals("ready", result)
        assertEquals(0, adapter.closeCalls)
    }

    private class RecordingNpuAdapter : NpuWorkloadAdapter {
        var closeCalls = 0
            private set

        override fun isAvailable(): Boolean = false

        override fun setIntensity(intensity: Float) = Unit

        override fun releaseAndConfirm(): Boolean = true

        override fun status(): String = "test"

        override fun closeWithResult(): NpuShutdownResult {
            closeCalls += 1
            return NpuShutdownResult(
                releaseConfirmed = true,
                backendCloseConfirmed = true,
                mayRemainActive = false,
                detail = "test close",
            )
        }
    }
}
