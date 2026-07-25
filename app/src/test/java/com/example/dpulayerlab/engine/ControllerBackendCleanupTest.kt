package com.example.dpulayerlab.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class ControllerBackendCleanupTest {
    @Test
    fun unconfirmedTelemetryStopNeverClosesSampledDependencies() {
        val calls = mutableListOf<String>()

        val result = closeDependentBackendsAfterTelemetryStop(
            stopTelemetry = {
                calls += "stop-telemetry"
                false
            },
            closeDependents = {
                calls += "close-dependents"
                "closed"
            },
        )

        assertSame(DependentBackendCloseResult.Blocked, result)
        assertEquals(listOf("stop-telemetry"), calls)
    }

    @Test
    fun confirmedTelemetryStopClosesDependenciesAfterAcknowledgment() {
        val calls = mutableListOf<String>()

        val result = closeDependentBackendsAfterTelemetryStop(
            stopTelemetry = {
                calls += "stop-telemetry"
                true
            },
            closeDependents = {
                calls += "close-dependents"
                "closed"
            },
        )

        assertEquals(
            DependentBackendCloseResult.Closed("closed"),
            result,
        )
        assertEquals(listOf("stop-telemetry", "close-dependents"), calls)
    }

    @Test
    fun telemetryStopExceptionCannotFallThroughToDependentClose() {
        val calls = mutableListOf<String>()
        val failure = IllegalStateException("stop failed")

        val thrown = assertThrows(IllegalStateException::class.java) {
            closeDependentBackendsAfterTelemetryStop(
                stopTelemetry = {
                    calls += "stop-telemetry"
                    throw failure
                },
                closeDependents = {
                    calls += "close-dependents"
                },
            )
        }

        assertSame(failure, thrown)
        assertEquals(listOf("stop-telemetry"), calls)
    }
}
