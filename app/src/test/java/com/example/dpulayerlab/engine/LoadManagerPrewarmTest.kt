package com.example.dpulayerlab.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.dpulayerlab.model.LoadSetpoints
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class LoadManagerPrewarmTest {
    @Test
    fun everyWorkerAcknowledgesAndRepeatedPrewarmReusesItsBuffers() {
        val allocations = AtomicInteger(0)
        val manager = manager(
            memoryWorkerCount = 2,
            allocator = { size ->
                allocations.incrementAndGet()
                ByteArray(size)
            },
        )

        try {
            assertTrue(manager.start())

            assertTrue(manager.prewarmMemoryWorkingSet(timeoutMs = 1_000L))
            assertEquals(4, allocations.get())
            assertEquals(0L, manager.sampleAndResetBandwidthBytes())

            assertTrue(manager.prewarmMemoryWorkingSet(timeoutMs = 1_000L))
            assertEquals(4, allocations.get())
            assertEquals(0L, manager.sampleAndResetBandwidthBytes())
        } finally {
            assertTrue(manager.closeWithResult().workersStopped)
        }
    }

    @Test
    fun allocationFailureIsLatchedAndPrewarmFailsWithoutTraffic() {
        val manager = manager(
            memoryWorkerCount = 1,
            allocator = { throw OutOfMemoryError("synthetic allocation failure") },
        )

        try {
            assertTrue(manager.start())

            assertFalse(manager.prewarmMemoryWorkingSet(timeoutMs = 1_000L))
            assertTrue(manager.hasMemoryAllocationFailure())
            assertEquals(0L, manager.sampleAndResetBandwidthBytes())
            assertFalse(manager.prewarmMemoryWorkingSet(timeoutMs = 1_000L))
        } finally {
            assertTrue(manager.closeWithResult().workersStopped)
        }
    }

    @Test
    fun droppingBuffersCancelsInFlightPrewarmAndLaterRequestCanRecover() {
        val allocationEntered = CountDownLatch(1)
        val allowAllocation = CountDownLatch(1)
        val allocationCalls = AtomicInteger(0)
        val prewarmResult = AtomicBoolean(true)
        val manager = manager(
            memoryWorkerCount = 1,
            allocator = { size ->
                if (allocationCalls.incrementAndGet() == 1) {
                    allocationEntered.countDown()
                    allowAllocation.await(1, TimeUnit.SECONDS)
                }
                ByteArray(size)
            },
        )
        val requester = Thread({
            prewarmResult.set(manager.prewarmMemoryWorkingSet(timeoutMs = 1_000L))
        }, "LoadManagerPrewarmTest-requester")

        try {
            assertTrue(manager.start())
            requester.start()
            assertTrue(allocationEntered.await(1, TimeUnit.SECONDS))

            manager.releaseLoads(dropMemoryBuffers = true)
            requester.join(1_000L)
            assertFalse(requester.isAlive)
            assertFalse(prewarmResult.get())

            allowAllocation.countDown()
            assertTrue(manager.prewarmMemoryWorkingSet(timeoutMs = 1_000L))
            assertEquals(0L, manager.sampleAndResetBandwidthBytes())
        } finally {
            allowAllocation.countDown()
            requester.interrupt()
            requester.join(1_000L)
            assertTrue(manager.closeWithResult().workersStopped)
        }
    }

    @Test
    fun dropRacingSlowMeasuredAllocationCannotRepublishOrGenerateTraffic() {
        val allocationEntered = CountDownLatch(1)
        val allowAllocation = CountDownLatch(1)
        val allocationCalls = AtomicInteger(0)
        val manager = manager(
            memoryWorkerCount = 1,
            allocator = { size ->
                if (allocationCalls.incrementAndGet() == 1) {
                    allocationEntered.countDown()
                    allowAllocation.await(1, TimeUnit.SECONDS)
                }
                ByteArray(size)
            },
        )

        try {
            assertTrue(manager.start())
            manager.apply(LoadSetpoints(memory = 1f))
            assertTrue(allocationEntered.await(1, TimeUnit.SECONDS))

            manager.releaseLoads(dropMemoryBuffers = true)
            allowAllocation.countDown()
            Thread.sleep(50L)

            assertEquals(0L, manager.sampleAndResetBandwidthBytes())
            assertTrue(manager.prewarmMemoryWorkingSet(timeoutMs = 1_000L))
            assertEquals(
                "revoked measured pair must be discarded before prewarm",
                4,
                allocationCalls.get(),
            )
        } finally {
            allowAllocation.countDown()
            assertTrue(manager.closeWithResult().workersStopped)
        }
    }

    @Test
    fun prewarmRejectsNonPositiveTimeoutAndNonRunningManager() {
        val manager = manager(memoryWorkerCount = 1)

        assertFalse(manager.prewarmMemoryWorkingSet(timeoutMs = 1_000L))
        assertTrue(manager.start())
        assertFalse(manager.prewarmMemoryWorkingSet(timeoutMs = 0L))
        assertTrue(manager.closeWithResult().workersStopped)
        assertFalse(manager.prewarmMemoryWorkingSet(timeoutMs = 1_000L))
    }

    @Test
    fun prewarmedBuffersStayPinnedAcrossLongIdleUntilExplicitDrop() {
        val nowMs = AtomicLong(0L)
        val allocations = AtomicInteger(0)
        val manager = manager(
            memoryWorkerCount = 1,
            monotonicNowMs = nowMs::get,
            allocator = { size ->
                allocations.incrementAndGet()
                ByteArray(size)
            },
        )
        try {
            assertTrue(manager.start())
            assertTrue(manager.prewarmMemoryWorkingSet(timeoutMs = 1_000L))
            assertEquals(2, allocations.get())

            nowMs.set(10_000L)
            manager.releaseLoads()
            Thread.sleep(25L)
            assertTrue(manager.prewarmMemoryWorkingSet(timeoutMs = 1_000L))
            assertEquals("run-scoped pin must preserve both arrays", 2, allocations.get())

            manager.releaseLoads(dropMemoryBuffers = true)
            Thread.sleep(25L)
            assertTrue(manager.prewarmMemoryWorkingSet(timeoutMs = 1_000L))
            assertEquals("explicit drop must force a fresh pair", 4, allocations.get())
        } finally {
            assertTrue(manager.closeWithResult().workersStopped)
        }
    }

    private fun manager(
        memoryWorkerCount: Int,
        allocator: (Int) -> ByteArray = { ByteArray(it) },
        monotonicNowMs: () -> Long = { 0L },
    ) = LoadManager(
        npuAdapter = NoOpNpuAdapter,
        cpuWorkerCount = 0,
        memoryWorkerCount = memoryWorkerCount,
        memoryWorkingSetBytes = 512 * 1024,
        workerStarter = Thread::start,
        memoryBufferAllocator = allocator,
        monotonicNowMs = monotonicNowMs,
    )

    private object NoOpNpuAdapter : NpuWorkloadAdapter {
        override fun isAvailable(): Boolean = false

        override fun setIntensity(intensity: Float) = Unit

        override fun releaseAndConfirm(): Boolean = true

        override fun status(): String = "test"

        override fun closeWithResult() = NpuShutdownResult(
            releaseConfirmed = true,
            backendCloseConfirmed = true,
            mayRemainActive = false,
            detail = "test",
        )
    }
}
