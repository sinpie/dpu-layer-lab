package com.example.dpulayerlab.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemMonitorConstructionTest {
    @Test
    fun successfulConstructionCommitsWithoutRunningRollbackActions() {
        val events = mutableListOf<String>()

        val result = constructWithBoundedRollback {
            it.own { events += "cleanup" }
            "ready"
        }

        assertEquals("ready", result)
        assertTrue(events.isEmpty())
    }

    @Test
    fun failedConstructionRollsBackEveryOwnedResourceInLifoOrder() {
        val events = mutableListOf<String>()
        val failure = IllegalStateException("construction")

        val thrown = assertThrows(IllegalStateException::class.java) {
            constructWithBoundedRollback<String> {
                it.own { events += "first" }
                it.own { events += "second" }
                throw failure
            }
        }

        assertSame(failure, thrown)
        assertEquals(listOf("second", "first"), events)
    }

    @Test
    fun rollbackFailureIsSuppressedAndDoesNotSkipOlderResources() {
        val events = mutableListOf<String>()
        val failure = IllegalStateException("construction")
        val cleanupFailure = IllegalStateException("cleanup")

        val thrown = assertThrows(IllegalStateException::class.java) {
            constructWithBoundedRollback<String> {
                it.own { events += "oldest" }
                it.own {
                    events += "failing"
                    throw cleanupFailure
                }
                it.own { events += "newest" }
                throw failure
            }
        }

        assertSame(failure, thrown)
        assertEquals(listOf("newest", "failing", "oldest"), events)
        assertEquals(1, thrown.suppressed.size)
        assertSame(cleanupFailure, thrown.suppressed.single())
    }

    @Test
    fun ownershipStackIsBoundedAndItsOverflowIsRolledBack() {
        val events = mutableListOf<String>()

        assertThrows(IllegalStateException::class.java) {
            constructWithBoundedRollback<Unit>(maxActions = 1) {
                it.own { events += "owned" }
                it.own { events += "unreachable" }
            }
        }

        assertEquals(listOf("owned"), events)
    }

    @Test
    fun telemetryFailurePreservesExpectedExceptionButRethrowsFatalError() {
        val expected = IllegalStateException("binder")
        assertSame(expected, nonFatalTelemetryFailure(expected))

        val fatal = OutOfMemoryError("telemetry allocation")
        val thrown = assertThrows(OutOfMemoryError::class.java) {
            nonFatalTelemetryFailure(fatal)
        }
        assertSame(fatal, thrown)
    }
}
