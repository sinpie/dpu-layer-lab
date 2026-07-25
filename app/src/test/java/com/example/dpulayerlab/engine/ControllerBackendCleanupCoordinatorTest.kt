package com.example.dpulayerlab.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

class ControllerBackendCleanupCoordinatorTest {
    @Test
    fun cleanupWaitsForBothActivityFreeBarriersAndRunsExactlyOnce() {
        ControllerBackendCleanupCoordinator(cleanupTimeoutMs = 1_000L).use { coordinator ->
            val owner = requireNotNull(coordinator.tryAcquireOwner())
            val runBarrier = ControllerBackendCompletionBarrier()
            val monitorBarrier = ControllerBackendCompletionBarrier()
            val calls = AtomicInteger()
            val cleanupThread = AtomicReference<Thread>()
            val callerThread = Thread.currentThread()

            assertEquals(
                ControllerBackendCleanupStart.STARTED,
                coordinator.beginCleanup(
                    owner,
                    runBarrier,
                    monitorBarrier,
                ) {
                    cleanupThread.set(Thread.currentThread())
                    calls.incrementAndGet()
                    ControllerBackendCleanupOutcome.confirmed()
                },
            )
            assertNull(coordinator.tryAcquireOwner())
            assertEquals(ControllerBackendCleanupPhase.CLEANING, coordinator.snapshot().phase)
            assertEquals(0, calls.get())

            runBarrier.complete()
            assertFalse(waitUntil(40L) { calls.get() != 0 })
            monitorBarrier.complete()

            assertTrue(
                waitUntil(1_000L) {
                    coordinator.snapshot().phase == ControllerBackendCleanupPhase.IDLE
                },
            )
            assertEquals(1, calls.get())
            assertNotEquals(callerThread, cleanupThread.get())
            val nextOwner = requireNotNull(coordinator.tryAcquireOwner())
            assertNotEquals(owner, nextOwner)
        }
    }

    @Test
    fun duplicateCloseCannotQueueOrInvokeAnotherCleanup() {
        ControllerBackendCleanupCoordinator(cleanupTimeoutMs = 1_000L).use { coordinator ->
            val owner = requireNotNull(coordinator.tryAcquireOwner())
            val runBarrier = ControllerBackendCompletionBarrier()
            val monitorBarrier = ControllerBackendCompletionBarrier()
            val calls = AtomicInteger()
            val operation = ControllerBackendCleanupOperation {
                calls.incrementAndGet()
                ControllerBackendCleanupOutcome.confirmed()
            }

            assertEquals(
                ControllerBackendCleanupStart.STARTED,
                coordinator.beginCleanup(owner, runBarrier, monitorBarrier, operation),
            )
            repeat(1_000) {
                assertEquals(
                    ControllerBackendCleanupStart.DUPLICATE,
                    coordinator.beginCleanup(owner, runBarrier, monitorBarrier, operation),
                )
            }
            runBarrier.complete()
            monitorBarrier.complete()

            assertTrue(
                waitUntil(1_000L) {
                    coordinator.snapshot().phase == ControllerBackendCleanupPhase.IDLE
                },
            )
            assertEquals(1, calls.get())
        }
    }

    @Test
    fun incompleteBarrierTimesOutWithoutCallingCleanupAndFailureIsSticky() {
        ControllerBackendCleanupCoordinator(cleanupTimeoutMs = 40L).use { coordinator ->
            val owner = requireNotNull(coordinator.tryAcquireOwner())
            val calls = AtomicInteger()

            assertEquals(
                ControllerBackendCleanupStart.STARTED,
                coordinator.beginCleanup(
                    owner,
                    ControllerBackendCompletionBarrier(),
                    ControllerBackendCompletionBarrier(initiallyComplete = true),
                ) {
                    calls.incrementAndGet()
                    ControllerBackendCleanupOutcome.confirmed()
                },
            )

            assertTrue(
                waitUntil(1_000L) {
                    coordinator.snapshot().phase == ControllerBackendCleanupPhase.FAILED
                },
            )
            assertEquals(0, calls.get())
            assertNull(coordinator.tryAcquireOwner())
            val failureReason = coordinator.snapshot().failureReason.orEmpty()
            assertTrue(
                failureReason.contains("deadline") ||
                    failureReason.contains("timed out"),
            )
        }
    }

    @Test
    fun rejectedCleanupResultLeavesStickyFailure() {
        ControllerBackendCleanupCoordinator(cleanupTimeoutMs = 1_000L).use { coordinator ->
            val owner = requireNotNull(coordinator.tryAcquireOwner())
            val completed = ControllerBackendCompletionBarrier(initiallyComplete = true)

            assertEquals(
                ControllerBackendCleanupStart.STARTED,
                coordinator.beginCleanup(owner, completed, completed) {
                    ControllerBackendCleanupOutcome.failed("worker teardown unconfirmed")
                },
            )

            assertTrue(
                waitUntil(1_000L) {
                    coordinator.snapshot().phase == ControllerBackendCleanupPhase.FAILED
                },
            )
            assertEquals(
                "worker teardown unconfirmed",
                coordinator.snapshot().failureReason,
            )
            assertNull(coordinator.tryAcquireOwner())
            assertEquals(
                ControllerBackendCleanupStart.FAILED_STICKY,
                coordinator.beginCleanup(
                    owner,
                    completed,
                    completed,
                    ControllerBackendCleanupOperation {
                        ControllerBackendCleanupOutcome.confirmed()
                    },
                ),
            )
        }
    }

    @Test
    fun watchdogFailsClosedWhenCleanupIgnoresInterruption() {
        ControllerBackendCleanupCoordinator(cleanupTimeoutMs = 50L).use { coordinator ->
            val owner = requireNotNull(coordinator.tryAcquireOwner())
            val completed = ControllerBackendCompletionBarrier(initiallyComplete = true)
            val operationStarted = CountDownLatch(1)
            val releaseOperation = CountDownLatch(1)
            val operationExited = CountDownLatch(1)
            val calls = AtomicInteger()

            assertEquals(
                ControllerBackendCleanupStart.STARTED,
                coordinator.beginCleanup(owner, completed, completed) {
                    calls.incrementAndGet()
                    operationStarted.countDown()
                    try {
                        while (releaseOperation.count > 0L) {
                            try {
                                releaseOperation.await()
                            } catch (_: InterruptedException) {
                                // Model a vendor/native close which ignores interruption.
                            }
                        }
                        ControllerBackendCleanupOutcome.confirmed()
                    } finally {
                        operationExited.countDown()
                    }
                },
            )
            assertTrue(operationStarted.await(1, TimeUnit.SECONDS))
            assertTrue(
                waitUntil(1_000L) {
                    coordinator.snapshot().phase == ControllerBackendCleanupPhase.FAILED
                },
            )
            assertEquals(1, calls.get())
            assertNull(coordinator.tryAcquireOwner())

            releaseOperation.countDown()
            assertTrue(operationExited.await(1, TimeUnit.SECONDS))
        }
    }

    @Test
    fun staleOwnerCannotCloseCurrentBackend() {
        ControllerBackendCleanupCoordinator(cleanupTimeoutMs = 1_000L).use { coordinator ->
            val owner = requireNotNull(coordinator.tryAcquireOwner())
            val stale = ControllerBackendOwnerToken(owner.value + 1L)
            val completed = ControllerBackendCompletionBarrier(initiallyComplete = true)
            val calls = AtomicInteger()

            assertEquals(
                ControllerBackendCleanupStart.STALE_OWNER,
                coordinator.beginCleanup(stale, completed, completed) {
                    calls.incrementAndGet()
                    ControllerBackendCleanupOutcome.confirmed()
                },
            )
            assertEquals(0, calls.get())
            assertEquals(ControllerBackendCleanupPhase.ACTIVE, coordinator.snapshot().phase)
            assertNull(coordinator.tryAcquireOwner())
        }
    }

    @Test
    fun completionBarrierIsIdempotent() {
        val barrier = ControllerBackendCompletionBarrier()

        barrier.complete()
        barrier.complete()

        assertTrue(barrier.isComplete())
    }

    @Test
    fun constructorFailureMakesOwnerStickyAndRejectsStaleFailure() {
        ControllerBackendCleanupCoordinator(cleanupTimeoutMs = 1_000L).use { coordinator ->
            val owner = requireNotNull(coordinator.tryAcquireOwner())
            val stale = ControllerBackendOwnerToken(owner.value + 1L)

            assertFalse(coordinator.failOwner(stale, "stale constructor"))
            assertEquals(ControllerBackendCleanupPhase.ACTIVE, coordinator.snapshot().phase)
            assertTrue(coordinator.failOwner(owner, "backend constructor failed"))
            assertEquals(ControllerBackendCleanupPhase.FAILED, coordinator.snapshot().phase)
            assertEquals("backend constructor failed", coordinator.snapshot().failureReason)
            assertNull(coordinator.tryAcquireOwner())
            assertFalse(coordinator.failOwner(owner, "duplicate"))
        }
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadlineNanos) {
            if (condition()) return true
            LockSupport.parkNanos(500_000L)
        }
        return condition()
    }
}
