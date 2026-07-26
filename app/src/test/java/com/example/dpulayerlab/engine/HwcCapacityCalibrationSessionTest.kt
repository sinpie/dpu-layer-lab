package com.example.dpulayerlab.engine

import com.example.dpulayerlab.model.MetricQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HwcCapacityCalibrationSessionTest {
    private val innerDisplay =
        HwcCapacityCalibrationScope.normalized(
            displayId = 0,
            widthPx = 3120,
            heightPx = 1440,
        )

    @Test
    fun completedObservationIsReusedAcrossEveryLaterPlanInTheSameProcessSession() {
        val store = HwcCapacityCalibrationSessionStore()
        val first = store.acquire(innerDisplay) as HwcCapacityCalibrationAcquisition.Measure
        val observed = observedResult()

        assertTrue(
            store.recordCandidate(
                first.claim,
                HWC_CAPACITY_CALIBRATION_REQUESTED_LAYERS,
            ),
        )
        assertTrue(store.complete(first.claim, observed))
        repeat(4) {
            assertEquals(
                HwcCapacityCalibrationAcquisition.Reuse(observed),
                store.acquire(innerDisplay),
            )
        }
        assertEquals(observed, store.snapshot(innerDisplay))
    }

    @Test
    fun unavailableAttemptIsAlsoTerminalAndDoesNotCreateRepeatedTwentyLayerLoad() {
        val store = HwcCapacityCalibrationSessionStore()
        val first = store.acquire(innerDisplay) as HwcCapacityCalibrationAcquisition.Measure
        val unavailable = HwcCapacityCalibrationResult(
            status = HwcCapacityCalibrationStatus.UNAVAILABLE,
            candidateLayers = HWC_CAPACITY_CALIBRATION_REQUESTED_LAYERS,
            detail = "fresh atomic pair unavailable",
        )

        assertTrue(
            store.recordCandidate(
                first.claim,
                HWC_CAPACITY_CALIBRATION_REQUESTED_LAYERS,
            ),
        )
        assertTrue(store.complete(first.claim, unavailable))
        val unavailableReuse =
            store.acquire(innerDisplay) as HwcCapacityCalibrationAcquisition.Reuse
        assertEquals(HwcCapacityCalibrationStatus.UNAVAILABLE, unavailableReuse.result.status)
        assertEquals(20, unavailableReuse.result.candidateLayers)

        val cancelledStore = HwcCapacityCalibrationSessionStore()
        val cancelled =
            cancelledStore.acquire(innerDisplay) as HwcCapacityCalibrationAcquisition.Measure
        assertTrue(cancelledStore.recordCandidate(cancelled.claim, 12))
        assertTrue(cancelledStore.abandon(cancelled.claim, "user STOP"))
        val cancelledReuse =
            cancelledStore.acquire(innerDisplay) as HwcCapacityCalibrationAcquisition.Reuse
        assertEquals(HwcCapacityCalibrationStatus.UNAVAILABLE, cancelledReuse.result.status)
        assertEquals(12, cancelledReuse.result.candidateLayers)
        assertTrue(cancelledReuse.result.detail.contains("user STOP"))

        val neverRenderedStore = HwcCapacityCalibrationSessionStore()
        val neverRendered =
            neverRenderedStore.acquire(innerDisplay) as HwcCapacityCalibrationAcquisition.Measure
        assertTrue(neverRenderedStore.abandon(neverRendered.claim, "display unavailable"))
        val neverRenderedReuse =
            neverRenderedStore.acquire(innerDisplay) as HwcCapacityCalibrationAcquisition.Reuse
        assertNull(neverRenderedReuse.result.candidateLayers)
        assertTrue(neverRenderedReuse.result.eventDetail().contains("candidate=N/A"))
        assertTrue(
            neverRenderedReuse.result.uiSummary()
                .contains("요청 20L · 실제 producer 후보 N/A"),
        )
    }

    @Test
    fun orientationSwapReusesButPhysicalDisplayChangeInvalidatesWithoutASecondBurst() {
        val store = HwcCapacityCalibrationSessionStore()
        val first = store.acquire(innerDisplay) as HwcCapacityCalibrationAcquisition.Measure
        assertTrue(store.recordCandidate(first.claim, 20))
        assertTrue(store.complete(first.claim, observedResult()))

        val sameAfterAxisSwap =
            HwcCapacityCalibrationScope.normalized(0, widthPx = 1440, heightPx = 3120)
        assertTrue(
            store.acquire(sameAfterAxisSwap) is HwcCapacityCalibrationAcquisition.Reuse,
        )

        val coverDisplay =
            HwcCapacityCalibrationScope.normalized(1, widthPx = 904, heightPx = 2316)
        val changed =
            (store.acquire(coverDisplay) as HwcCapacityCalibrationAcquisition.Reuse).result
        assertEquals(HwcCapacityCalibrationStatus.UNAVAILABLE, changed.status)
        assertTrue(changed.detail.contains("restart the app process"))
        assertNull(changed.observedDeviceLayers)
    }

    @Test
    fun activeOwnerBlocksASecondScopeAndStaleClaimCannotPublishOrAbandon() {
        val store = HwcCapacityCalibrationSessionStore()
        val first = store.acquire(innerDisplay) as HwcCapacityCalibrationAcquisition.Measure
        val externalDisplay =
            HwcCapacityCalibrationScope.normalized(2, widthPx = 3840, heightPx = 2160)

        assertEquals(
            HwcCapacityCalibrationAcquisition.Busy(innerDisplay),
            store.acquire(externalDisplay),
        )
        assertTrue(store.abandon(first.claim, "cancelled by user"))
        val second =
            store.acquire(externalDisplay) as HwcCapacityCalibrationAcquisition.Reuse
        assertFalse(store.complete(first.claim, observedResult()))
        assertFalse(store.abandon(first.claim, "stale"))
        assertEquals(HwcCapacityCalibrationStatus.UNAVAILABLE, second.result.status)
        assertTrue(second.result.detail.contains("display envelope changed"))
    }

    @Test
    fun storeOwnsCandidateAndDisplayProvenanceInsteadOfTrustingControllerPayload() {
        val store = HwcCapacityCalibrationSessionStore()
        val first = store.acquire(innerDisplay) as HwcCapacityCalibrationAcquisition.Measure
        assertTrue(store.recordCandidate(first.claim, 12))

        assertTrue(
            store.complete(
                first.claim,
                observedResult().copy(
                    candidateLayers = 20,
                    calibrationDisplayId = 99,
                    calibrationDisplayShortEdgePx = 1,
                    calibrationDisplayLongEdgePx = 2,
                ),
            ),
        )

        val stored = checkNotNull(store.snapshot(innerDisplay))
        assertEquals(12, stored.candidateLayers)
        assertEquals(innerDisplay.displayId, stored.calibrationDisplayId)
        assertEquals(innerDisplay.shortEdgePx, stored.calibrationDisplayShortEdgePx)
        assertEquals(innerDisplay.longEdgePx, stored.calibrationDisplayLongEdgePx)
        assertTrue(stored.eventDetail().contains("requested=20; candidate=12"))
        assertTrue(stored.uiSummary().contains("요청 20L · 실제 producer 후보 12P"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun pendingUiStateCannotBePublishedAsAReusableAttempt() {
        val store = HwcCapacityCalibrationSessionStore()
        val claim =
            (store.acquire(innerDisplay) as HwcCapacityCalibrationAcquisition.Measure).claim
        store.complete(
            claim,
            HwcCapacityCalibrationResult(HwcCapacityCalibrationStatus.PENDING),
        )
    }

    @Test
    fun tokenAndCandidateBoundariesRemainExplicit() {
        assertEquals(1L, nextSessionCalibrationToken(0L))
        assertEquals(42L, nextSessionCalibrationToken(41L))
        assertEquals(1L, nextSessionCalibrationToken(Long.MAX_VALUE))
        assertEquals(20, HWC_CAPACITY_CALIBRATION_REQUESTED_LAYERS)
    }

    private fun observedResult() =
        HwcCapacityCalibrationResult(
            status = HwcCapacityCalibrationStatus.OBSERVED_AT_CANDIDATE,
            candidateLayers = HWC_CAPACITY_CALIBRATION_REQUESTED_LAYERS,
            observedDeviceLayers = 8,
            observedClientLayers = 12,
            source = "vendor:test",
            quality = MetricQuality.HARDWARE_COUNTER,
            evidenceMonotonicMs = 123L,
            calibrationDisplayId = innerDisplay.displayId,
            calibrationDisplayShortEdgePx = innerDisplay.shortEdgePx,
            calibrationDisplayLongEdgePx = innerDisplay.longEdgePx,
        )
}
