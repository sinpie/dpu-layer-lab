package com.example.dpulayerlab.vendor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class VendorBridgeStateTest {
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
