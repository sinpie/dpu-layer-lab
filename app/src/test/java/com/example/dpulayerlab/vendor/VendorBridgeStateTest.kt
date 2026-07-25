package com.example.dpulayerlab.vendor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class VendorBridgeStateTest {
    @Test
    fun performanceApiAndControlBitsAreStrictlyVersionGated() {
        assertFalse(supportsVendorPerformanceSession(Int.MIN_VALUE))
        assertFalse(supportsVendorPerformanceSession(2))
        assertTrue(supportsVendorPerformanceSession(VENDOR_PERFORMANCE_API_V3))
        assertTrue(supportsVendorPerformanceSession(Int.MAX_VALUE))

        assertTrue(
            validPerformanceControlRequest(
                VendorBridge.PERFORMANCE_CONTROL_DISABLE_BATTERY_SAVER,
            ),
        )
        assertFalse(validPerformanceControlRequest(0))
        assertFalse(validPerformanceControlRequest(2))
        assertFalse(validPerformanceControlRequest(Int.MAX_VALUE))
    }

    @Test
    fun higherVersionEndPreventsLateBeginFromClearingRestoreEvidence() {
        val latch = VendorPerformanceRestoreLatch()
        val begin = performanceTicket(
            commandVersion = 11L,
        )
        val renew = begin.copy(commandVersion = 12L)
        val health = begin.copy(commandVersion = 13L)
        val end = begin.copy(commandVersion = 14L)

        assertTrue(latch.arm(begin))
        assertTrue(latch.advance(renew))
        assertTrue(latch.advance(health))
        assertTrue(latch.advance(end))
        assertFalse(latch.confirmRestored(begin))
        assertFalse(latch.confirmRestored(renew))
        assertFalse(latch.confirmRestored(health))
        assertEquals(end, latch.snapshot())
        assertTrue(latch.confirmRestored(end))
        assertNull(latch.snapshot())
    }

    @Test
    fun exactOlderEndAcknowledgmentClearsSameSessionEndRetry() {
        val latch = VendorPerformanceRestoreLatch()
        val begin = performanceTicket(commandVersion = 15L)
        val firstEnd = begin.copy(commandVersion = 16L)
        val retryEnd = begin.copy(commandVersion = 17L)

        assertTrue(latch.arm(begin))
        assertTrue(latch.advance(firstEnd))
        assertTrue(latch.advance(retryEnd))
        assertTrue(latch.confirmRestoredByEnd(firstEnd))
        assertNull(latch.snapshot())
    }

    @Test
    fun olderEndAcknowledgmentRemainsValidAcrossSignedVersionWrap() {
        val latch = VendorPerformanceRestoreLatch()
        val firstEnd = performanceTicket(commandVersion = Long.MAX_VALUE)
        val retryEnd = firstEnd.copy(commandVersion = Long.MIN_VALUE)

        assertTrue(latch.arm(firstEnd))
        assertTrue(latch.advance(retryEnd))
        assertTrue(latch.confirmRestoredByEnd(firstEnd))
        assertNull(latch.snapshot())
    }

    @Test
    fun endAcknowledgmentCannotClearAnotherOrOlderSessionState() {
        val latch = VendorPerformanceRestoreLatch()
        val current = performanceTicket(commandVersion = 30L)

        assertTrue(latch.arm(current))
        assertFalse(
            latch.confirmRestoredByEnd(
                current.copy(
                    sessionId = current.sessionId + 1L,
                    commandVersion = 31L,
                ),
            ),
        )
        assertFalse(latch.confirmRestoredByEnd(current.copy(commandVersion = 31L)))
        assertEquals(current, latch.snapshot())
    }

    @Test
    fun performanceRestoreLatchRejectsOtherSessionAndStaleRenewal() {
        val latch = VendorPerformanceRestoreLatch()
        val initial = performanceTicket(commandVersion = 20L)

        assertTrue(latch.arm(initial))
        assertFalse(latch.arm(initial.copy(sessionId = initial.sessionId + 1L)))
        assertFalse(latch.advance(initial.copy(commandVersion = 19L)))
        assertFalse(
            latch.advance(
                initial.copy(
                    sessionId = initial.sessionId + 1L,
                    commandVersion = 21L,
                ),
            ),
        )
        assertEquals(initial, latch.snapshot())
        assertTrue(ticketsMatchExactly(initial, initial.copy()))
        assertFalse(
            ticketsMatchExactly(
                initial,
                initial.copy(leaseDurationMs = initial.leaseDurationMs + 1L),
            ),
        )
        assertFalse(
            ticketsMatchExactly(
                initial,
                initial.copy(requestedControls = 0),
            ),
        )
        assertTrue(latch.confirmRestored(initial))
    }

    @Test
    fun performanceSequenceNeverPublishesZeroAcrossSignedWrap() {
        val sequence = AtomicLong(Long.MAX_VALUE)

        assertEquals(Long.MIN_VALUE, nextNonZeroSequence(sequence))
        sequence.set(-1L)
        assertEquals(1L, nextNonZeroSequence(sequence))
        assertEquals(Long.MIN_VALUE + 7L, monotonicDeadlineAfter(Long.MAX_VALUE - 2L, 10L))
        assertFalse(
            isMonotonicDeadlineReached(
                nowNanos = Long.MAX_VALUE - 1L,
                deadlineNanos = Long.MIN_VALUE + 7L,
            ),
        )
        assertTrue(
            isMonotonicDeadlineReached(
                nowNanos = Long.MIN_VALUE + 7L,
                deadlineNanos = Long.MIN_VALUE + 7L,
            ),
        )
    }

    @Test
    fun beginFailureBeforeDispatchClearsQueuedHigherVersionEndForSameSession() {
        val latch = VendorPerformanceRestoreLatch()
        val begin = performanceTicket(commandVersion = 101L)
        val queuedEnd = begin.copy(commandVersion = 102L)

        assertTrue(latch.arm(begin))
        assertTrue(latch.advance(queuedEnd))
        assertTrue(latch.confirmNeverMutated(begin))
        assertNull(latch.snapshot())

        val otherSession = performanceTicket(
            commandVersion = 103L,
            sessionId = begin.sessionId + 1L,
        )
        assertTrue(latch.arm(otherSession))
        assertFalse(latch.confirmNeverMutated(begin))
        assertEquals(otherSession, latch.snapshot())
    }

    @Test
    fun performanceWakeExecutorBoundsSignalsWhileLatestCommandSlotChanges() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = newSingleSignalRemoteExecutor("VendorPerformanceWakeTest")
        try {
            executor.execute {
                started.countDown()
                release.await(2L, TimeUnit.SECONDS)
            }
            assertTrue(started.await(1L, TimeUnit.SECONDS))

            executor.execute {}
            var rejected = false
            try {
                executor.execute {}
            } catch (_: RejectedExecutionException) {
                rejected = true
            }
            assertTrue(rejected)
            assertEquals(1, executor.queue.size)
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(2L, TimeUnit.SECONDS))
        }
    }

    @Test
    fun partialVendorExecutorConstructionShutsDownEveryOwnedPrefix() {
        val created = mutableListOf<java.util.concurrent.ThreadPoolExecutor>()
        var failed = false

        try {
            constructVendorExecutorLanes { kind ->
                if (kind == VendorExecutorLane.CONTROL) {
                    throw IllegalStateException("synthetic control-lane construction failure")
                }
                newNoBacklogRemoteExecutor("VendorConstructionRollback-$kind")
                    .also(created::add)
            }
        } catch (_: IllegalStateException) {
            failed = true
        }

        assertTrue(failed)
        assertEquals(2, created.size)
        assertTrue(created.all { it.isShutdown })
    }

    @Test
    fun restartSafetyWaitParksAndObservesLateCompletion() {
        val nowNanos = AtomicLong(0L)
        val parkCount = AtomicLong(0L)
        val safe = AtomicLong(0L)

        val completed = awaitBoundedCondition(
            timeoutMs = 100L,
            maxTimeoutMs = 100L,
            pollNanos = TimeUnit.MILLISECONDS.toNanos(5L),
            monotonicNowNanos = nowNanos::get,
            parkNanos = { duration ->
                nowNanos.addAndGet(duration)
                if (parkCount.incrementAndGet() == 3L) safe.set(1L)
            },
            condition = { safe.get() == 1L },
        )

        assertTrue(completed)
        assertEquals(3L, parkCount.get())
    }

    @Test
    fun restartSafetyWaitCapsHostileTimeoutWithoutBusyLoop() {
        val nowNanos = AtomicLong(0L)
        val parkCount = AtomicLong(0L)

        val completed = awaitBoundedCondition(
            timeoutMs = Long.MAX_VALUE,
            maxTimeoutMs = 10L,
            pollNanos = TimeUnit.MILLISECONDS.toNanos(3L),
            monotonicNowNanos = nowNanos::get,
            parkNanos = { duration ->
                parkCount.incrementAndGet()
                nowNanos.addAndGet(duration)
            },
            condition = { false },
        )

        assertFalse(completed)
        assertEquals(TimeUnit.MILLISECONDS.toNanos(10L), nowNanos.get())
        assertEquals(4L, parkCount.get())
    }

    @Test
    fun extendedTelemetryGateNeverTreatsV1AsV2() {
        assertFalse(supportsVendorTelemetryV2(Int.MIN_VALUE))
        assertFalse(supportsVendorTelemetryV2(0))
        assertFalse(supportsVendorTelemetryV2(1))
        assertTrue(supportsVendorTelemetryV2(VENDOR_TELEMETRY_API_V2))
        assertTrue(supportsVendorTelemetryV2(Int.MAX_VALUE))
    }

    @Test
    fun vendorUtilizationValidationRejectsInvalidPercentages() {
        assertEquals(0f, validVendorUtilizationPercent(0f)!!, 0f)
        assertEquals(37.5f, validVendorUtilizationPercent(37.5f)!!, 0f)
        assertEquals(100f, validVendorUtilizationPercent(100f)!!, 0f)
        assertNull(validVendorUtilizationPercent(-0.01f))
        assertNull(validVendorUtilizationPercent(100.01f))
        assertNull(validVendorUtilizationPercent(Float.NaN))
        assertNull(validVendorUtilizationPercent(Float.POSITIVE_INFINITY))
    }

    @Test
    fun vendorFrequencyValidationUsesExplicitHzAndBoundedRange() {
        assertEquals(0L, validVendorFrequencyHz(0L))
        assertEquals(850_000_000L, validVendorFrequencyHz(850_000_000L))
        assertEquals(
            MAX_VENDOR_FREQUENCY_HZ,
            validVendorFrequencyHz(MAX_VENDOR_FREQUENCY_HZ),
        )
        assertNull(validVendorFrequencyHz(-1L))
        assertNull(validVendorFrequencyHz(MAX_VENDOR_FREQUENCY_HZ + 1L))
        assertNull(validVendorFrequencyHz(Long.MAX_VALUE))
    }

    @Test
    fun optionalV2GetterFailureKeepsOtherValidatedExtensionValues() {
        val extended = readVendorTelemetryV2(
            serviceSession = 17L,
            gpuUtilizationReader = {
                throw IllegalStateException("unsupported optional transaction")
            },
            gpuFrequencyReader = { 850_000_000L },
            dpuFrequencyReader = { 600_000_000L },
        )

        assertEquals(17L, extended.serviceSession)
        assertNull(extended.gpuUtilization)
        assertEquals(850_000_000L, extended.gpuFrequencyHz)
        assertEquals(600_000_000L, extended.dpuFrequencyHz)
    }

    @Test
    fun failedOrDifferentSessionV2ExtensionNeverReplacesV1Snapshot() {
        val base = vendorSnapshotForTelemetryTest(serviceSession = 21L)

        assertEquals(base, mergeVendorTelemetryV2(base, null))
        assertEquals(
            base,
            mergeVendorTelemetryV2(
                base,
                VendorTelemetryV2Snapshot(
                    serviceSession = 22L,
                    gpuUtilization = 75f,
                    gpuFrequencyHz = 900_000_000L,
                    dpuFrequencyHz = 700_000_000L,
                ),
            ),
        )

        val merged = mergeVendorTelemetryV2(
            base,
            VendorTelemetryV2Snapshot(
                serviceSession = 21L,
                gpuUtilization = 75f,
                gpuFrequencyHz = 900_000_000L,
                dpuFrequencyHz = 700_000_000L,
            ),
        )
        assertEquals(base.underrunCount, merged.underrunCount)
        assertEquals(base.dpuUtilization, merged.dpuUtilization)
        assertEquals(base.busUtilization, merged.busUtilization)
        assertEquals(75f, merged.gpuUtilization)
        assertEquals(900_000_000L, merged.gpuFrequencyHz)
        assertEquals(700_000_000L, merged.dpuFrequencyHz)
    }

    @Test
    fun optionalV2TimeoutUsesOnlyRemainingOriginalSnapshotBudget() {
        val startNanos = 10_000L
        val deadlineNanos = startNanos + TimeUnit.MILLISECONDS.toNanos(700L)

        assertEquals(
            700L,
            remainingVendorTelemetryTimeoutMs(deadlineNanos, startNanos),
        )
        assertEquals(
            1L,
            remainingVendorTelemetryTimeoutMs(
                deadlineNanos,
                deadlineNanos - TimeUnit.MILLISECONDS.toNanos(1L),
            ),
        )
        assertEquals(
            0L,
            remainingVendorTelemetryTimeoutMs(deadlineNanos, deadlineNanos),
        )
        assertEquals(
            0L,
            remainingVendorTelemetryTimeoutMs(deadlineNanos, deadlineNanos + 1L),
        )
    }

    @Test
    fun optionalV2ExecutorRejectsBacklogWhileAProviderCallIsStuck() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = newNoBacklogRemoteExecutor("VendorV2Test")
        try {
            executor.execute {
                started.countDown()
                release.await(2L, TimeUnit.SECONDS)
            }
            assertTrue(started.await(1L, TimeUnit.SECONDS))

            var rejected = false
            try {
                executor.execute {}
            } catch (_: RejectedExecutionException) {
                rejected = true
            }
            assertTrue(rejected)
            assertTrue(executor.queue.isEmpty())
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(2L, TimeUnit.SECONDS))
        }
    }

    @Test
    fun olderCompletionCannotAcknowledgeTheLatestRapidUpdate() {
        val nowNanos = AtomicLong(0L)
        val acknowledgments = NpuCommandAcknowledgments(nowNanos::get)
        val first = acknowledgments.recordPending(version = 1L, serviceSession = 7L)
        val second = acknowledgments.recordPending(version = 2L, serviceSession = 7L)

        acknowledgments.recordApplied(first)

        val latest = acknowledgments.health(
            ticket = second,
            latestDesiredVersion = 2L,
            currentServiceSession = 7L,
            pendingTimeoutMs = 1_000L,
        )
        val superseded = acknowledgments.health(
            ticket = first,
            latestDesiredVersion = 2L,
            currentServiceSession = 7L,
            pendingTimeoutMs = 1_000L,
        )

        assertEquals(NpuControlCommandState.PENDING, latest.state)
        assertEquals(NpuControlCommandState.FAILED, superseded.state)
    }

    @Test
    fun stuckOlderSetterFailsNewestPendingCommandDespiteRapidUpdates() {
        val nowNanos = AtomicLong(0L)
        val acknowledgments = NpuCommandAcknowledgments(nowNanos::get)
        val first = acknowledgments.recordPending(version = 10L, serviceSession = 4L)
        acknowledgments.recordStarted(first)
        val latest = acknowledgments.recordPending(version = 11L, serviceSession = 4L)
        nowNanos.set(TimeUnit.MILLISECONDS.toNanos(501L))

        val health = acknowledgments.health(
            ticket = latest,
            latestDesiredVersion = 11L,
            currentServiceSession = 4L,
            pendingTimeoutMs = 500L,
        )

        assertEquals(NpuControlCommandState.FAILED, health.state)
        assertTrue(health.detail.contains("timed out"))
    }

    @Test
    fun queuedCommandWithoutAcknowledgmentFailsAtBoundedDeadline() {
        val nowNanos = AtomicLong(0L)
        val acknowledgments = NpuCommandAcknowledgments(nowNanos::get)
        val ticket = acknowledgments.recordPending(version = 15L, serviceSession = 6L)
        nowNanos.set(TimeUnit.MILLISECONDS.toNanos(101L))

        val health = acknowledgments.health(
            ticket = ticket,
            latestDesiredVersion = 15L,
            currentServiceSession = 6L,
            pendingTimeoutMs = 100L,
        )

        assertEquals(NpuControlCommandState.FAILED, health.state)
        assertTrue(health.detail.contains("acknowledgment timed out"))
    }

    @Test
    fun disconnectAndTerminalFailureCannotBeClearedByLateSuccess() {
        val acknowledgments = NpuCommandAcknowledgments { 0L }
        val ticket = acknowledgments.recordPending(version = 21L, serviceSession = 8L)

        val disconnected = acknowledgments.health(
            ticket = ticket,
            latestDesiredVersion = 21L,
            currentServiceSession = null,
            pendingTimeoutMs = 1_000L,
        )
        acknowledgments.recordApplied(ticket)
        val lateSuccess = acknowledgments.health(
            ticket = ticket,
            latestDesiredVersion = 21L,
            currentServiceSession = 8L,
            pendingTimeoutMs = 1_000L,
        )

        assertEquals(NpuControlCommandState.FAILED, disconnected.state)
        assertEquals(NpuControlCommandState.FAILED, lateSuccess.state)
    }

    @Test
    fun newerDesiredOrReconnectReplayCannotBeOverwrittenByOlderCompletion() {
        assertTrue(shouldPublishNpuCommand(currentVersion = null, candidateVersion = 1L))
        assertTrue(shouldPublishNpuCommand(currentVersion = 7L, candidateVersion = 8L))
        assertFalse(shouldPublishNpuCommand(currentVersion = 8L, candidateVersion = 8L))
        assertFalse(shouldPublishNpuCommand(currentVersion = 9L, candidateVersion = 8L))
    }

    @Test
    fun commandOrderingSurvivesSignedLongWrap() {
        assertTrue(
            shouldPublishNpuCommand(
                currentVersion = Long.MAX_VALUE,
                candidateVersion = Long.MIN_VALUE,
            ),
        )
        assertTrue(isNewerSequence(Long.MIN_VALUE + 1L, Long.MIN_VALUE))
        assertFalse(isNewerSequence(Long.MAX_VALUE, Long.MIN_VALUE))
        assertFalse(isNewerSequence(7L, 7L))
    }

    @Test
    fun drainRetriesNewerReplayAndOnlyOneExplicitSameVersionCommand() {
        assertTrue(
            shouldRetryPendingNpuCommand(
                failedVersion = 10L,
                pendingVersion = 11L,
                allowSameVersionRetry = false,
            ),
        )
        assertTrue(
            shouldRetryPendingNpuCommand(
                failedVersion = 10L,
                pendingVersion = 10L,
                allowSameVersionRetry = true,
            ),
        )
        assertFalse(
            shouldRetryPendingNpuCommand(
                failedVersion = 10L,
                pendingVersion = 10L,
                allowSameVersionRetry = false,
            ),
        )
        assertFalse(
            shouldRetryPendingNpuCommand(
                failedVersion = 10L,
                pendingVersion = 9L,
                allowSameVersionRetry = true,
            ),
        )
        assertFalse(
            shouldRetryPendingNpuCommand(
                failedVersion = 10L,
                pendingVersion = null,
                allowSameVersionRetry = true,
            ),
        )
    }

    @Test
    fun reconnectBackoffIsBounded() {
        assertTrue(reconnectDelayMs(-1L) == 250L)
        assertTrue(reconnectDelayMs(0L) == 250L)
        assertTrue(reconnectDelayMs(1L) == 500L)
        assertTrue(reconnectDelayMs(4L) == 4_000L)
        assertTrue(reconnectDelayMs(Long.MAX_VALUE) == 4_000L)
    }

    @Test
    fun failedCurrentBindRetriesButStaleOrClosedBindDoesNot() {
        assertTrue(
            shouldScheduleReconnectAfterBindFailure(
                detachedCurrentBinding = true,
                closed = false,
            ),
        )
        assertFalse(
            shouldScheduleReconnectAfterBindFailure(
                detachedCurrentBinding = false,
                closed = false,
            ),
        )
        assertFalse(
            shouldScheduleReconnectAfterBindFailure(
                detachedCurrentBinding = true,
                closed = true,
            ),
        )
    }

    @Test
    fun capabilityQueryFailureRemainsUnknownWhileVerifiedFalseIsPreserved() {
        val failedNpuQuery = mergeCapabilityDiscovery(
            currentNpu = null,
            currentSbwc = null,
            queriedNpu = null,
            queriedSbwc = false,
        )
        assertNull(failedNpuQuery.npuSupported)
        assertEquals(false, failedNpuQuery.sbwcSupported)
        assertFalse(failedNpuQuery.npuBecameKnown)
        assertFalse(failedNpuQuery.complete)

        val verifiedUnsupported = mergeCapabilityDiscovery(
            currentNpu = null,
            currentSbwc = false,
            queriedNpu = false,
            queriedSbwc = null,
        )
        assertEquals(false, verifiedUnsupported.npuSupported)
        assertEquals(false, verifiedUnsupported.sbwcSupported)
        assertTrue(verifiedUnsupported.npuBecameKnown)
        assertTrue(verifiedUnsupported.complete)
    }

    @Test
    fun retryFailureCannotErasePreviouslyVerifiedCapability() {
        val verifiedSupported = mergeCapabilityDiscovery(
            currentNpu = true,
            currentSbwc = null,
            queriedNpu = null,
            queriedSbwc = true,
        )
        assertEquals(true, verifiedSupported.npuSupported)
        assertEquals(true, verifiedSupported.sbwcSupported)
        assertFalse(verifiedSupported.npuBecameKnown)
        assertTrue(verifiedSupported.complete)

        val verifiedUnsupported = mergeCapabilityDiscovery(
            currentNpu = false,
            currentSbwc = true,
            queriedNpu = true,
            queriedSbwc = false,
        )
        assertEquals(false, verifiedUnsupported.npuSupported)
        assertEquals(true, verifiedUnsupported.sbwcSupported)
        assertFalse(verifiedUnsupported.npuBecameKnown)
        assertTrue(verifiedUnsupported.complete)
    }

    @Test
    fun capabilityRetryBudgetAndBackoffAreBounded() {
        assertFalse(shouldScheduleCapabilityRetry(-1L))
        assertTrue(shouldScheduleCapabilityRetry(0L))
        assertTrue(shouldScheduleCapabilityRetry(3L))
        assertFalse(shouldScheduleCapabilityRetry(4L))
        assertFalse(shouldScheduleCapabilityRetry(Long.MAX_VALUE))

        assertEquals(100L, capabilityRetryDelayMs(-1L))
        assertEquals(100L, capabilityRetryDelayMs(0L))
        assertEquals(200L, capabilityRetryDelayMs(1L))
        assertEquals(800L, capabilityRetryDelayMs(3L))
        assertEquals(800L, capabilityRetryDelayMs(Long.MAX_VALUE))
    }

    @Test
    fun repeatedOrQuarantinedCloseCannotReusePriorShutdownProof() {
        val result = unattributedVendorShutdownResult()

        assertFalse(result.brokerWasConnected)
        assertFalse(result.npuStopConfirmed)
        assertFalse(result.compressionResetConfirmed)
        assertFalse(result.performanceRestoreConfirmed)
    }

    @Test
    fun vendorStatusIsBoundedAndSafeForHudAndReports() {
        assertEquals("", sanitizeVendorStatus(null))
        assertEquals(
            "NPU ready ok",
            sanitizeVendorStatus("  NPU\tready\nok\u0000  "),
        )
        assertEquals("normal-status", sanitizeVendorStatus("normal-status"))

        val truncated = sanitizeVendorStatus("x".repeat(MAX_VENDOR_STATUS_CHARS + 100))
        assertEquals(MAX_VENDOR_STATUS_CHARS, truncated.length)
        assertTrue(truncated.endsWith("\u2026"))
        assertFalse(truncated.any { Character.isISOControl(it) })
    }

    @Test
    fun vendorStatusHandlesUnicodeAndTinyBoundsWithoutSplittingSurrogates() {
        assertEquals("\u2026", sanitizeVendorStatus("\ud83d\ude80", maxChars = 1))
        assertEquals("\ud83d\ude80", sanitizeVendorStatus("\ud83d\ude80", maxChars = 2))
        val truncatedPair = sanitizeVendorStatus("\ud83d\ude80x", maxChars = 2)
        assertEquals("\u2026", truncatedPair)
        assertFalse(truncatedPair.any { Character.isSurrogate(it) })
        assertEquals("\ufffd", sanitizeVendorStatus("\ud800", maxChars = 1))
        assertEquals("safe text", sanitizeVendorStatus("safe\u202E text"))
    }
}

private fun performanceTicket(
    commandVersion: Long,
    sessionId: Long = 31L,
    serviceSession: Long = 7L,
) = VendorPerformanceSessionTicket(
    sessionId = sessionId,
    commandVersion = commandVersion,
    serviceSession = serviceSession,
    requestedControls = VendorBridge.PERFORMANCE_CONTROL_DISABLE_BATTERY_SAVER,
    leaseDurationMs = VendorBridge.PERFORMANCE_SESSION_LEASE_MS,
)

private fun vendorSnapshotForTelemetryTest(serviceSession: Long) = VendorSnapshot(
    apiVersion = VENDOR_TELEMETRY_API_V2,
    serviceSession = serviceSession,
    underrunCount = 41L,
    dpuUtilization = 52f,
    busUtilization = 63f,
    gpuUtilization = null,
    gpuFrequencyHz = null,
    dpuFrequencyHz = null,
    deviceLayers = 4,
    clientLayers = 1,
    compressionState = "linear",
    npuStatus = "idle",
)
