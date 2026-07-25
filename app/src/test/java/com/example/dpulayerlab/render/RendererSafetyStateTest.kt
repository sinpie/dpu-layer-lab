package com.example.dpulayerlab.render

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RendererSafetyStateTest {
    @Test
    fun liveProducerBlocksUntilItsThreadActuallyTerminates() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val producer = Thread(
            {
                started.countDown()
                release.await()
            },
            "RendererSafetyStateTest-producer",
        )
        producer.isDaemon = true
        producer.start()
        assertTrue(started.await(1, TimeUnit.SECONDS))

        try {
            RendererSafetyState.trackUnconfirmed(producer)
            assertTrue(RendererSafetyState.hasUnconfirmedTeardown())
        } finally {
            release.countDown()
            producer.join(1_000L)
        }
        assertFalse(producer.isAlive)
        assertFalse(RendererSafetyState.hasUnconfirmedTeardown())
    }

    @Test
    fun alreadyStoppedThreadDoesNotCreateAFalseLease() {
        val producer = Thread { Unit }
        producer.start()
        producer.join(1_000L)
        assertFalse(producer.isAlive)

        RendererSafetyState.trackUnconfirmed(producer)

        assertFalse(RendererSafetyState.hasUnconfirmedTeardown())
    }

    @Test
    fun nativeCleanupFailureRemainsStickyAfterWorkerExit() {
        RendererSafetyState.resetStickyCleanupFailureForTests()
        try {
            RendererSafetyState.markCleanupFailure("EGL cleanup", "eglTerminate=false")

            assertTrue(RendererSafetyState.hasUnconfirmedTeardown())
            assertNotNull(RendererSafetyState.cleanupFailureReason())
        } finally {
            RendererSafetyState.resetStickyCleanupFailureForTests()
        }
    }

    @Test
    fun unexpectedWatcherFailureIsStickyBeforeOriginalFailureIsRethrown() {
        RendererSafetyState.resetStickyCleanupFailureForTests()
        val failure = IllegalStateException("injected watcher failure")

        try {
            try {
                RendererSafetyState.throwUnexpectedWatcherFailure(failure)
            } catch (actual: IllegalStateException) {
                assertSame(failure, actual)
            }

            assertEquals(
                "renderer producer cleanup watcher failed",
                RendererSafetyState.cleanupFailureReason(),
            )
            assertTrue(RendererSafetyState.hasUnconfirmedTeardown())
        } finally {
            RendererSafetyState.resetStickyCleanupFailureForTests()
        }
    }

    @Test
    fun rendererThreadStartFailureIsConvertedWithoutThrowing() {
        val alreadyStarted = Thread { Unit }
        alreadyStarted.start()
        alreadyStarted.join(1_000L)
        var captured: Throwable? = null

        val started = startRendererThread(alreadyStarted) { captured = it }

        assertFalse(started)
        assertTrue(captured is IllegalThreadStateException)
    }

    @Test
    fun ordinaryStartRollbackFailureIsPropagatedWithStartFailureAsPrimary() {
        val alreadyStarted = Thread { Unit }
        alreadyStarted.start()
        alreadyStarted.join(1_000L)
        val rollbackFailure = IllegalStateException("injected rollback failure")
        var startFailure: Throwable? = null

        try {
            startRendererThread(alreadyStarted) { error ->
                startFailure = error
                throw rollbackFailure
            }
            fail("Expected start failure")
        } catch (actual: IllegalThreadStateException) {
            assertSame(startFailure, actual)
            assertEquals(1, actual.suppressed.size)
            assertSame(rollbackFailure, actual.suppressed.single())
        }
    }

    @Test
    fun lateOutOfMemoryDominatesStartFailureAfterStickyCleanupEvidenceIsRecorded() {
        RendererSafetyState.resetStickyCleanupFailureForTests()
        val alreadyStarted = Thread { Unit }
        alreadyStarted.start()
        alreadyStarted.join(1_000L)
        val fatal = OutOfMemoryError("injected late cleanup allocation failure")
        var startFailure: Throwable? = null

        try {
            try {
                startRendererThread(alreadyStarted) { error ->
                    startFailure = error
                    RendererSafetyState.markCleanupFailure(
                        component = "producer cleanup watcher",
                        detail = "start failed",
                    )
                    throw fatal
                }
                fail("Expected injected OutOfMemoryError")
            } catch (actual: OutOfMemoryError) {
                assertSame(fatal, actual)
                assertEquals(1, actual.suppressed.size)
                assertSame(startFailure, actual.suppressed.single())
            }

            assertTrue(startFailure is IllegalThreadStateException)
            assertEquals(
                "producer cleanup watcher: start failed",
                RendererSafetyState.cleanupFailureReason(),
            )
            assertTrue(RendererSafetyState.hasUnconfirmedTeardown())
        } finally {
            RendererSafetyState.resetStickyCleanupFailureForTests()
        }
    }

    @Test
    fun lifecycleStageRemovalTokenBlocksActivityOverlapUntilDisposeAck() {
        RendererSafetyState.resetStickyCleanupFailureForTests()
        val owner = RendererSafetyState.createLifecycleStageOwner()
        try {
            RendererSafetyState.markLifecycleStageRemovalPending(owner)
            assertTrue(RendererSafetyState.hasUnconfirmedTeardown())

            RendererSafetyState.markLifecycleStageRemoved(owner)
            assertFalse(RendererSafetyState.hasUnconfirmedTeardown())
        } finally {
            RendererSafetyState.markLifecycleStageRemoved(owner)
            RendererSafetyState.resetStickyCleanupFailureForTests()
        }
    }

    @Test
    fun lifecycleStageOwnersCannotClearEachOthersPendingLease() {
        RendererSafetyState.resetStickyCleanupFailureForTests()
        val firstOwner = RendererSafetyState.createLifecycleStageOwner()
        val secondOwner = RendererSafetyState.createLifecycleStageOwner()
        assertTrue(firstOwner != secondOwner)

        try {
            RendererSafetyState.markLifecycleStageRemovalPending(firstOwner)
            RendererSafetyState.markLifecycleStageRemovalPending(secondOwner)

            RendererSafetyState.markLifecycleStageRemoved(firstOwner)
            assertTrue(RendererSafetyState.hasUnconfirmedTeardown())

            RendererSafetyState.markLifecycleStageRemoved(secondOwner)
            assertFalse(RendererSafetyState.hasUnconfirmedTeardown())
        } finally {
            RendererSafetyState.markLifecycleStageRemoved(firstOwner)
            RendererSafetyState.markLifecycleStageRemoved(secondOwner)
            RendererSafetyState.resetStickyCleanupFailureForTests()
        }
    }

    @Test
    fun lifecycleStagePendingOwnerCapacityFailsClosed() {
        RendererSafetyState.resetStickyCleanupFailureForTests()
        val owners =
            LongArray(RendererSafetyState.MAX_PENDING_LIFECYCLE_STAGE_OWNERS + 1)
        var registeredOwners = 0

        try {
            repeat(RendererSafetyState.MAX_PENDING_LIFECYCLE_STAGE_OWNERS) { index ->
                val owner = RendererSafetyState.createLifecycleStageOwner()
                owners[index] = owner
                RendererSafetyState.markLifecycleStageRemovalPending(owner)
                registeredOwners++
            }
            val overflowOwner = RendererSafetyState.createLifecycleStageOwner()
            owners[registeredOwners] = overflowOwner

            try {
                RendererSafetyState.markLifecycleStageRemovalPending(overflowOwner)
                fail("Expected lifecycle owner capacity failure")
            } catch (_: IllegalStateException) {
                // The sticky fallback below is the externally meaningful fail-closed result.
            }

            assertEquals(
                "renderer lifecycle removal registration failed",
                RendererSafetyState.cleanupFailureReason(),
            )
            assertTrue(RendererSafetyState.hasUnconfirmedTeardown())
        } finally {
            var index = 0
            while (index <= registeredOwners && index < owners.size) {
                RendererSafetyState.markLifecycleStageRemoved(owners[index])
                index++
            }
            RendererSafetyState.resetStickyCleanupFailureForTests()
        }
    }

    @Test
    fun exhaustedLifecycleOwnerSpaceFailsClosedWithoutReusingAnIdentity() {
        RendererSafetyState.resetStickyCleanupFailureForTests()
        RendererSafetyState.setNextLifecycleStageOwnerForTests(Long.MAX_VALUE)

        try {
            try {
                RendererSafetyState.createLifecycleStageOwner()
                fail("Expected lifecycle owner exhaustion")
            } catch (_: IllegalStateException) {
                // The process-sticky reason is asserted below.
            }

            assertEquals(
                "renderer lifecycle owner allocation failed",
                RendererSafetyState.cleanupFailureReason(),
            )
            assertTrue(RendererSafetyState.hasUnconfirmedTeardown())
        } finally {
            RendererSafetyState.resetStickyCleanupFailureForTests()
        }
    }

    @Test
    fun rendererBuildFailureRollsBackEveryOwnedPrefix() {
        val rolledBack = mutableListOf<Int>()
        val failure = IllegalStateException("injected addView failure")

        try {
            buildRendererTransaction<Int, Unit>(
                resourceCapacity = 2,
                build = { register ->
                    register(1)
                    register(2)
                    throw failure
                },
                rollback = { owned -> rolledBack += owned },
            )
            fail("Expected injected failure")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }

        assertEquals(listOf(1, 2), rolledBack)
    }

    @Test
    fun rendererBuildOutOfMemoryRollsBackBeforeFatalRethrow() {
        val events = mutableListOf<String>()
        val failure = OutOfMemoryError("injected renderer allocation failure")

        try {
            buildRendererTransaction<String, Unit>(
                resourceCapacity = 1,
                build = { register ->
                    register("relay")
                    events += "throw"
                    throw failure
                },
                rollback = { owned ->
                    events += "rollback:${owned.joinToString()}"
                },
            )
            fail("Expected injected OutOfMemoryError")
        } catch (actual: OutOfMemoryError) {
            assertSame(failure, actual)
        }

        assertEquals(listOf("throw", "rollback:relay"), events)
    }

    @Test
    fun lateThreadDeathDominatesBuildFailureAndPreservesPendingLifecycleLease() {
        RendererSafetyState.resetStickyCleanupFailureForTests()
        val owner = RendererSafetyState.createLifecycleStageOwner()
        val buildFailure = IllegalStateException("injected view attachment failure")
        val fatal = ThreadDeath()

        try {
            try {
                buildRendererTransaction<String, Unit>(
                    resourceCapacity = 1,
                    build = { register ->
                        register("stage")
                        throw buildFailure
                    },
                    rollback = {
                        RendererSafetyState.markLifecycleStageRemovalPending(owner)
                        throw fatal
                    },
                )
                fail("Expected injected ThreadDeath")
            } catch (actual: ThreadDeath) {
                assertSame(fatal, actual)
                assertEquals(1, actual.suppressed.size)
                assertSame(buildFailure, actual.suppressed.single())
            }

            assertTrue(RendererSafetyState.hasUnconfirmedTeardown())
            RendererSafetyState.markLifecycleStageRemoved(owner)
            assertFalse(RendererSafetyState.hasUnconfirmedTeardown())
        } finally {
            RendererSafetyState.markLifecycleStageRemoved(owner)
            RendererSafetyState.resetStickyCleanupFailureForTests()
        }
    }

    @Test
    fun rendererBuildPreservesRollbackFailureAsSuppressedEvidence() {
        val failure = IllegalArgumentException("build")
        val rollbackFailure = IllegalStateException("rollback")

        try {
            buildRendererTransaction<String, Unit>(
                resourceCapacity = 1,
                build = { register ->
                    register("view")
                    throw failure
                },
                rollback = { throw rollbackFailure },
            )
            fail("Expected build failure")
        } catch (actual: IllegalArgumentException) {
            assertSame(failure, actual)
            assertEquals(1, actual.suppressed.size)
            assertSame(rollbackFailure, actual.suppressed.single())
        }
    }

    @Test
    fun rendererBuildCapacityFailureRollsBackTheOverflowCandidate() {
        val rolledBack = mutableListOf<String>()

        try {
            buildRendererTransaction<String, Unit>(
                resourceCapacity = 1,
                build = { register ->
                    register("first")
                    register("overflow")
                },
                rollback = { owned -> rolledBack += owned },
            )
            fail("Expected renderer transaction capacity failure")
        } catch (_: IllegalStateException) {
            // The complete rollback prefix is asserted below.
        }

        assertEquals(listOf("first", "overflow"), rolledBack)
    }

    @Test
    fun oversizedRendererBuildCapacityIsRejectedBeforeBuildStarts() {
        var buildStarted = false
        var rollbackStarted = false

        try {
            buildRendererTransaction<Any, Unit>(
                resourceCapacity = MAX_RENDERER_TRANSACTION_RESOURCE_CAPACITY + 1,
                build = {
                    buildStarted = true
                },
                rollback = {
                    rollbackStarted = true
                },
            )
            fail("Expected renderer transaction capacity validation failure")
        } catch (_: IllegalArgumentException) {
            // No resource transaction has started, so rollback must not run.
        }

        assertFalse(buildStarted)
        assertFalse(rollbackStarted)
    }

    @Test
    fun mapInsertionGapRevokesOnlyOwnedRelaysAndRetiresPrefixOnce() {
        val existingChild = Any()
        val firstNewChild = Any()
        val secondNewChild = Any()
        val disabled = mutableListOf<Any>()
        val retiredBatches = mutableListOf<List<Any>>()
        val insertionFailure = OutOfMemoryError("injected relay map insertion failure")
        val firstDetachFailure = IllegalStateException("injected first relay detach failure")

        try {
            buildRendererTransaction<Any, Unit>(
                resourceCapacity = 2,
                build = { register ->
                    // Production registers a RenderChild before the fallible producerRelays
                    // insertion/addView steps. Neither new child is assumed to be in that map.
                    register(firstNewChild)
                    register(secondNewChild)
                    throw insertionFailure
                },
                rollback = { owned ->
                    rollbackOwnedRendererChildren(
                        owned = owned,
                        disableRelay = { child ->
                            disabled += child
                            if (child === firstNewChild) throw firstDetachFailure
                        },
                        retireChildren = { retired ->
                            retiredBatches += retired.toList()
                        },
                    )
                },
            )
            fail("Expected injected OutOfMemoryError")
        } catch (actual: OutOfMemoryError) {
            assertSame(insertionFailure, actual)
            assertEquals(1, actual.suppressed.size)
            assertSame(firstDetachFailure, actual.suppressed.single())
        }

        assertEquals(listOf(firstNewChild, secondNewChild), disabled)
        assertEquals(listOf(listOf(firstNewChild, secondNewChild)), retiredBatches)
        assertFalse(disabled.any { it === existingChild })
        assertFalse(retiredBatches.flatten().any { it === existingChild })
    }
}
