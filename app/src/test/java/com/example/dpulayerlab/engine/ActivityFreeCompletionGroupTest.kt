package com.example.dpulayerlab.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ActivityFreeCompletionGroupTest {
    @Test
    fun sealedEmptyGroupCompletesSuccessfully() {
        val group = ActivityFreeCompletionGroup()

        val barrier = group.seal()

        assertTrue(barrier.isComplete())
        assertEquals(
            ActivityFreeCompletionOutcome(successful = true),
            group.outcome(),
        )
    }

    @Test
    fun droppedMonitorReferenceDoesNotOpenBarrierBeforeActualCompletion() {
        val group = ActivityFreeCompletionGroup()
        val registration = requireNotNull(group.registerStart())
        val workerStarted = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val workerExited = CountDownLatch(1)
        val monitorReference = AtomicReference<Thread?>()
        val worker = Thread(
            {
                workerStarted.countDown()
                try {
                    releaseWorker.await()
                } finally {
                    // Model Job.invokeOnCompletion: publish that the operation has actually
                    // finished before its Activity-free ticket signals the group.
                    workerExited.countDown()
                    registration.complete()
                }
            },
            "ActivityFreeCompletionGroupTest-monitor",
        ).apply { isDaemon = true }
        monitorReference.set(worker)
        worker.start()
        assertTrue(workerStarted.await(1, TimeUnit.SECONDS))

        // Model pause(): its monitorJob field is cleared while the dispatched work is still alive.
        monitorReference.set(null)
        val barrier = group.seal()

        assertNull(monitorReference.get())
        assertFalse(barrier.isComplete())
        assertNull(group.outcome())
        assertEquals(1, group.snapshot().pendingStarts)

        releaseWorker.countDown()
        assertTrue(awaitBarrier(barrier, 1_000L))
        assertTrue(workerExited.await(1, TimeUnit.SECONDS))
        worker.join(1_000L)
        assertFalse(worker.isAlive)
        assertEquals(
            ActivityFreeCompletionOutcome(successful = true),
            group.outcome(),
        )
    }

    @Test
    fun failureStillOpensBarrierButRemainsSeparateFromCompletion() {
        val group = ActivityFreeCompletionGroup()
        val registration = requireNotNull(group.registerStart())
        val barrier = group.seal()

        assertTrue(registration.fail("monitor sample terminated unexpectedly"))

        assertTrue(awaitBarrier(barrier, 1_000L))
        assertEquals(
            ActivityFreeCompletionOutcome(
                successful = false,
                failureReason = "monitor sample terminated unexpectedly",
            ),
            group.outcome(),
        )
    }

    @Test
    fun duplicateCompletionCannotReleaseAnotherPendingRegistration() {
        val group = ActivityFreeCompletionGroup()
        val first = requireNotNull(group.registerStart())
        val second = requireNotNull(group.registerStart())
        val barrier = group.seal()

        assertTrue(first.complete())
        assertFalse(first.complete())
        assertFalse(barrier.isComplete())
        assertEquals(1, group.snapshot().pendingStarts)

        assertTrue(second.complete())
        assertTrue(awaitBarrier(barrier, 1_000L))
        assertEquals(0, group.snapshot().pendingStarts)
        assertEquals(2L, group.snapshot().completedStarts)
    }

    @Test
    fun completionBeforeSealWaitsForSeal() {
        val group = ActivityFreeCompletionGroup()
        val registration = requireNotNull(group.registerStart())

        assertTrue(registration.complete())
        assertFalse(group.snapshot().terminal)
        assertNull(group.outcome())

        val barrier = group.seal()
        assertTrue(barrier.isComplete())
        assertTrue(requireNotNull(group.outcome()).successful)
    }

    @Test
    fun registrationAfterSealIsRejectedWithoutChangingTerminalOutcome() {
        val group = ActivityFreeCompletionGroup()
        val firstBarrier = group.seal()
        val firstOutcome = group.outcome()

        assertNull(group.registerStart())
        val secondBarrier = group.seal()

        assertTrue(firstBarrier === secondBarrier)
        assertEquals(firstOutcome, group.outcome())
        assertEquals(ActivityFreeCompletionOutcome(successful = true), group.outcome())
    }

    @Test
    fun concurrentPendingLimitIsBoundedAndReportedAsFailure() {
        val group = ActivityFreeCompletionGroup(maxPendingRegistrations = 2)
        val first = requireNotNull(group.registerStart())
        val second = requireNotNull(group.registerStart())

        assertNull(group.registerStart())
        assertEquals(2, group.snapshot().pendingStarts)
        val barrier = group.seal()
        assertFalse(barrier.isComplete())

        first.complete()
        second.complete()

        assertTrue(awaitBarrier(barrier, 1_000L))
        val outcome = requireNotNull(group.outcome())
        assertFalse(outcome.successful)
        assertTrue(outcome.failureReason.orEmpty().contains("limit exceeded"))
    }

    @Test
    fun sealAndFinalCompletionRaceAlwaysPublishesOneTerminalOutcome() {
        repeat(200) { iteration ->
            val group = ActivityFreeCompletionGroup()
            val registration = requireNotNull(group.registerStart())
            val start = CountDownLatch(1)
            val completionThread = Thread(
                {
                    start.await()
                    registration.complete()
                },
                "ActivityFreeCompletionGroupTest-race-$iteration",
            ).apply { isDaemon = true }
            completionThread.start()

            start.countDown()
            val barrier = group.seal()

            assertTrue(awaitBarrier(barrier, 1_000L))
            completionThread.join(1_000L)
            assertFalse(completionThread.isAlive)
            val snapshot = group.snapshot()
            assertTrue(snapshot.sealed)
            assertTrue(snapshot.terminal)
            assertEquals(0, snapshot.pendingStarts)
            assertEquals(1L, snapshot.registeredStarts)
            assertEquals(1L, snapshot.completedStarts)
            assertTrue(requireNotNull(group.outcome()).successful)
        }
    }

    @Test
    fun registrationAndSealRaceEitherRejectsOrTracksTheAcceptedStart() {
        repeat(200) { iteration ->
            val group = ActivityFreeCompletionGroup()
            val start = CountDownLatch(1)
            val registrationRef =
                AtomicReference<ActivityFreeCompletionRegistration?>()
            val registrationThread = Thread(
                {
                    start.await()
                    registrationRef.set(group.registerStart())
                },
                "ActivityFreeCompletionGroupTest-register-seal-$iteration",
            ).apply { isDaemon = true }
            registrationThread.start()

            start.countDown()
            val barrier = group.seal()
            registrationThread.join(1_000L)
            assertFalse(registrationThread.isAlive)

            val registration = registrationRef.get()
            if (registration == null) {
                assertTrue(barrier.isComplete())
            } else {
                assertFalse(barrier.isComplete())
                assertTrue(registration.complete())
                assertTrue(awaitBarrier(barrier, 1_000L))
            }
            val snapshot = group.snapshot()
            assertTrue(snapshot.sealed)
            assertTrue(snapshot.terminal)
            assertEquals(0, snapshot.pendingStarts)
            assertEquals(snapshot.registeredStarts, snapshot.completedStarts)
        }
    }

    @Test
    fun firstFailureWinsAndReasonIsBounded() {
        val group = ActivityFreeCompletionGroup()
        val first = requireNotNull(group.registerStart())
        val second = requireNotNull(group.registerStart())
        val barrier = group.seal()

        first.fail("x".repeat(500))
        second.fail("later failure")

        assertTrue(awaitBarrier(barrier, 1_000L))
        val outcome = requireNotNull(group.outcome())
        assertFalse(outcome.successful)
        assertEquals(240, outcome.failureReason?.length)
        assertTrue(outcome.failureReason.orEmpty().all { it == 'x' })
    }

    @Test
    fun transactionalCompletionWaitsForSetupCommitAndActualCompletion() {
        val group = ActivityFreeCompletionGroup()
        val completion = TransactionalCompletionRegistration(
            requireNotNull(group.registerStart()),
        )

        assertTrue(completion.completeOperation())
        assertEquals(1, group.snapshot().pendingStarts)
        assertTrue(completion.commit())
        assertEquals(0, group.snapshot().pendingStarts)
        assertFalse(completion.fail("late failure"))

        group.seal()
        assertEquals(
            ActivityFreeCompletionOutcome(successful = true),
            group.outcome(),
        )
    }

    @Test
    fun transactionalRollbackPublishesFailureOnlyAfterOperationActuallyExits() {
        val group = ActivityFreeCompletionGroup()
        val completion = TransactionalCompletionRegistration(
            requireNotNull(group.registerStart()),
        )
        val barrier = group.seal()

        assertTrue(completion.fail("sibling job startup failed"))
        assertFalse(barrier.isComplete())
        assertEquals(1, group.snapshot().pendingStarts)
        assertTrue(completion.completeOperation())

        assertTrue(barrier.isComplete())
        assertEquals(
            ActivityFreeCompletionOutcome(
                successful = false,
                failureReason = "sibling job startup failed",
            ),
            group.outcome(),
        )
        assertFalse(completion.completeOperation())
        assertFalse(completion.commit())
    }

    @Test
    fun operationWhichEndedBeforeRollbackStillPublishesSetupFailure() {
        val group = ActivityFreeCompletionGroup()
        val completion = TransactionalCompletionRegistration(
            requireNotNull(group.registerStart()),
        )

        completion.completeOperation()
        assertTrue(completion.fail("publish failed"))
        group.seal()

        assertEquals(
            ActivityFreeCompletionOutcome(
                successful = false,
                failureReason = "publish failed",
            ),
            group.outcome(),
        )
    }

    @Test
    fun committedOperationFailureIsNotReportedAsSuccessfulCompletion() {
        val group = ActivityFreeCompletionGroup()
        val completion = TransactionalCompletionRegistration(
            requireNotNull(group.registerStart()),
        )

        assertTrue(completion.commit())
        assertTrue(completion.completeOperation("watchdog Job failed unexpectedly"))
        group.seal()

        assertEquals(
            ActivityFreeCompletionOutcome(
                successful = false,
                failureReason = "watchdog Job failed unexpectedly",
            ),
            group.outcome(),
        )
    }

    private fun awaitBarrier(
        barrier: ControllerBackendCompletionBarrier,
        timeoutMs: Long,
    ): Boolean {
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        return barrier.awaitUntil(deadlineNanos)
    }
}
