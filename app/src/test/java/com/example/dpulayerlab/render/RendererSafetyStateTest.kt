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
    fun rendererBuildFailureRollsBackEveryOwnedPrefix() {
        val rolledBack = mutableListOf<Int>()
        val failure = IllegalStateException("injected addView failure")

        try {
            buildRendererTransaction<Int, Unit>(
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
    fun rendererBuildPreservesRollbackFailureAsSuppressedEvidence() {
        val failure = IllegalArgumentException("build")
        val rollbackFailure = IllegalStateException("rollback")

        try {
            buildRendererTransaction<String, Unit>(
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
