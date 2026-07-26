package com.example.dpulayerlab.engine

import android.media.MediaCodecInfo
import com.example.dpulayerlab.model.BufferPresentation
import com.example.dpulayerlab.model.BufferSize
import com.example.dpulayerlab.model.Gauge
import com.example.dpulayerlab.model.HwcCompositionExpectation
import com.example.dpulayerlab.model.LayerBackend
import com.example.dpulayerlab.model.LayerOrientation
import com.example.dpulayerlab.model.LayerSizeProfile
import com.example.dpulayerlab.model.LoadTransitionEvaluator
import com.example.dpulayerlab.model.LoadSetpoints
import com.example.dpulayerlab.model.MetricQuality
import com.example.dpulayerlab.model.MotionProfile
import com.example.dpulayerlab.model.PhaseSpec
import com.example.dpulayerlab.model.PixelRoute
import com.example.dpulayerlab.model.PlanRunResult
import com.example.dpulayerlab.model.PlanProgress
import com.example.dpulayerlab.model.PlanSource
import com.example.dpulayerlab.model.PlanState
import com.example.dpulayerlab.model.RunProgress
import com.example.dpulayerlab.model.RunEvent
import com.example.dpulayerlab.model.RunSummary
import com.example.dpulayerlab.model.RunVerdict
import com.example.dpulayerlab.model.RunnerStage
import com.example.dpulayerlab.model.ScenarioRunPlan
import com.example.dpulayerlab.model.ScenarioSafetyPolicy
import com.example.dpulayerlab.model.TelemetrySnapshot
import com.example.dpulayerlab.model.TransitionMode
import com.example.dpulayerlab.model.TransitionSample
import com.example.dpulayerlab.model.TransitionSegment
import com.example.dpulayerlab.model.TransitionSpec
import com.example.dpulayerlab.model.requiredCoverageMask
import com.example.dpulayerlab.model.terminalReason
import com.example.dpulayerlab.monitor.CompressionControlResult
import com.example.dpulayerlab.monitor.ProducerReadiness
import com.example.dpulayerlab.monitor.SurfaceFlingerProbePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LabControllerMathTest {
    @Test
    fun planPositionEventPreservesTheRequestedDurationMultiplier() {
        val progress = PlanProgress(
            state = PlanState.RUNNING,
            source = PlanSource.USER_SELECTION,
            repeatIndex = 1,
            repeatCount = 3,
            durationMultiplier = 100,
            queueIndex = 2,
            queueSize = 4,
            completedRuns = 6,
            totalRuns = 12,
        )

        assertEquals(
            "source=USER_SELECTION; run=7/12; repeat=2/3; queue=3/4; " +
                "durationMultiplier=100",
            planPositionEventMessage(progress),
        )
    }

    @Test
    fun staleSnackbarConsumeCannotClearANewerAtomicErrorNotice() {
        val old = ErrorNotice(
            id = 1,
            message = "Battery Saver active",
            recoveryAction = ErrorRecoveryAction.OPEN_BATTERY_SAVER_SETTINGS,
        )
        val newer = ErrorNotice(id = 2, message = "Settings could not be opened")

        assertNull(errorNoticeAfterConsume(old, old.id))
        assertSame(newer, errorNoticeAfterConsume(newer, old.id))
        assertNull(errorNoticeAfterConsume(newer, null))
    }

    @Test
    fun processSessionCalibrationTopologyIsExactlyTwentyDisplayOnlyLayers() {
        val phase = processSessionHwcCapacityCalibrationPhase()

        assertEquals("session-hwc-capacity-calibration", phase.id)
        assertEquals(HWC_CAPACITY_CALIBRATION_TOTAL_TIMEOUT_MS, phase.durationMs)
        assertEquals(HWC_CAPACITY_CALIBRATION_REQUESTED_LAYERS, phase.activeLayers)
        assertEquals(HWC_CAPACITY_CALIBRATION_PRODUCER_FPS, phase.producerFps)
        assertEquals(HWC_CAPACITY_CALIBRATION_DISPLAY_HZ, phase.requestedDisplayHz)
        assertEquals(LayerBackend.INDEPENDENT_SURFACES, phase.backend)
        assertEquals(PixelRoute.RGB_8888, phase.pixelRoute)
        assertEquals(BufferSize.DISPLAY, phase.bufferSize)
        assertEquals(MotionProfile.CAPACITY_TILES, phase.motion)
        assertEquals(LayerSizeProfile.FULL_SCREEN, phase.layerSizeProfile)
        assertEquals(LoadSetpoints(), phase.workloads)
        assertFalse(phase.alphaOverlap)
        assertFalse(phase.includeGlLayer)
        assertEquals(TransitionSpec(), phase.transition)
        assertEquals(HwcCompositionExpectation.NONE, phase.hwcCompositionExpectation)
    }

    @Test
    fun scenarioWarmupRemainsOneLayerAfterSeparateSessionCalibration() {
        val target = PhaseSpec(
            id = "maximum-client-target",
            label = "Maximum client target",
            durationMs = 16_000L,
            activeLayers = ScenarioSafetyPolicy.HARD_MAX_LAYERS,
            producerFps = ScenarioSafetyPolicy.HARD_MAX_PRODUCER_FPS,
            requestedDisplayHz = 120f,
            backend = LayerBackend.MIXED_SURFACE_TEXTURE,
            pixelRoute = PixelRoute.RGB_8888,
            bufferSize = BufferSize.DISPLAY,
            motion = MotionProfile.TRANSFORM_STORM,
            layerSizeProfile = LayerSizeProfile.ABRUPT_SMALL_FULL,
            alphaOverlap = true,
            includeGlLayer = true,
            workloads = LoadSetpoints(cpu = 1f, memory = 1f, gpu = 1f, npu = 1f),
            hwcCompositionExpectation = HwcCompositionExpectation.CLIENT_REQUIRED,
        )

        val warmup = safeWarmupPhaseFor(target)

        assertEquals(1, warmup.activeLayers)
        assertEquals(LayerBackend.INDEPENDENT_SURFACES, warmup.backend)
        assertEquals(PixelRoute.RGB_8888, warmup.pixelRoute)
        assertEquals(BufferSize.DISPLAY, warmup.bufferSize)
        assertEquals(LayerSizeProfile.FULL_SCREEN, warmup.layerSizeProfile)
        assertEquals(LoadSetpoints(), warmup.workloads)
        assertFalse(warmup.alphaOverlap)
        assertFalse(warmup.includeGlLayer)
        assertEquals(HwcCompositionExpectation.NONE, warmup.hwcCompositionExpectation)
    }

    @Test
    fun capacityCalibrationUsesOneAbsoluteProducerActiveDeadline() {
        assertEquals(
            6_000L,
            remainingHwcCapacityCalibrationBudgetMs(
                deadlineMs = 10_000L,
                nowMs = 4_000L,
            ),
        )
        assertEquals(
            1L,
            remainingHwcCapacityCalibrationBudgetMs(
                deadlineMs = 10_000L,
                nowMs = 9_999L,
            ),
        )
        assertEquals(
            0L,
            remainingHwcCapacityCalibrationBudgetMs(
                deadlineMs = 10_000L,
                nowMs = 10_000L,
            ),
        )
        assertEquals(
            0L,
            remainingHwcCapacityCalibrationBudgetMs(
                deadlineMs = Long.MAX_VALUE,
                nowMs = Long.MAX_VALUE,
            ),
        )
        assertEquals(
            0L,
            remainingHwcCapacityCalibrationBudgetMs(
                deadlineMs = -1L,
                nowMs = 0L,
            ),
        )
        assertEquals(
            4_000L,
            hwcCapacityCalibrationSampleLaneTimeoutMs(
                remainingMs = 6_000L,
                hardTimeoutMs = 4_000L,
                completionReserveMs = 50L,
            ),
        )
        assertEquals(
            950L,
            hwcCapacityCalibrationSampleLaneTimeoutMs(
                remainingMs = 1_000L,
                hardTimeoutMs = 4_000L,
                completionReserveMs = 50L,
            ),
        )
        assertNull(
            hwcCapacityCalibrationSampleLaneTimeoutMs(
                remainingMs = 50L,
                hardTimeoutMs = 4_000L,
                completionReserveMs = 50L,
            ),
        )
        assertNull(
            hwcCapacityCalibrationSampleLaneTimeoutMs(
                remainingMs = Long.MAX_VALUE,
                hardTimeoutMs = 0L,
                completionReserveMs = 50L,
            ),
        )
        assertEquals(
            16L,
            hwcCapacityCalibrationWaitMs(
                remainingMs = 1_000L,
                requestedWaitMs = 16L,
                completionReserveMs = 150L,
            ),
        )
        assertNull(
            hwcCapacityCalibrationWaitMs(
                remainingMs = 151L,
                requestedWaitMs = 16L,
                completionReserveMs = 150L,
            ),
        )
        assertEquals(
            1L,
            hwcCapacityCalibrationWaitMs(
                remainingMs = 152L,
                requestedWaitMs = 16L,
                completionReserveMs = 150L,
            ),
        )
        assertNull(
            hwcCapacityCalibrationWaitMs(
                remainingMs = 150L,
                requestedWaitMs = 16L,
                completionReserveMs = 150L,
            ),
        )
        assertEquals(
            100L,
            hwcCapacityCalibrationWaitMs(
                remainingMs = 151L,
                requestedWaitMs = 100L,
                completionReserveMs = 50L,
            ),
        )
        assertEquals(
            99L,
            hwcCapacityCalibrationWaitMs(
                remainingMs = 150L,
                requestedWaitMs = 100L,
                completionReserveMs = 50L,
            ),
        )
    }

    @Test
    fun capacityCalibrationSettleIsSkippedForStopOrFailedTeardown() {
        assertTrue(
            shouldRunHwcCapacityCalibrationSettle(
                teardownConfirmed = true,
                cancellationRequested = false,
            ),
        )
        assertFalse(
            shouldRunHwcCapacityCalibrationSettle(
                teardownConfirmed = true,
                cancellationRequested = true,
            ),
        )
        assertFalse(
            shouldRunHwcCapacityCalibrationSettle(
                teardownConfirmed = false,
                cancellationRequested = false,
            ),
        )
    }

    @Test
    fun activeRunUsesVendorOnlyTypedPolicyAndSuppressesUnforcedProbe() {
        assertEquals(
            SurfaceFlingerProbePolicy.PERIODIC,
            surfaceFlingerProbePolicy(
                forceCompositionProbe = false,
                activeRun = false,
            ),
        )
        assertEquals(
            SurfaceFlingerProbePolicy.SUPPRESS_DURING_LOAD,
            surfaceFlingerProbePolicy(
                forceCompositionProbe = false,
                activeRun = true,
            ),
        )
        assertEquals(
            SurfaceFlingerProbePolicy.TYPED_BOUNDARY,
            surfaceFlingerProbePolicy(
                forceCompositionProbe = true,
                activeRun = true,
            ),
        )
    }

    @Test
    fun capacityGenerationActivatesOnlyAfterCommittedTopologyAndOnlyOnce() {
        assertEquals(
            HwcCapacityGenerationAction.WAIT,
            hwcCapacityGenerationAction(
                generationActivated = false,
                topologyPublished = false,
                topologyPending = false,
                expectedProducerCount = 20,
                candidateLayers = 20,
                rendererCleanupPending = false,
            ),
        )
        assertEquals(
            HwcCapacityGenerationAction.WAIT,
            hwcCapacityGenerationAction(
                generationActivated = false,
                topologyPublished = true,
                topologyPending = true,
                expectedProducerCount = 20,
                candidateLayers = 20,
                rendererCleanupPending = false,
            ),
        )
        assertEquals(
            HwcCapacityGenerationAction.WAIT,
            hwcCapacityGenerationAction(
                generationActivated = false,
                topologyPublished = true,
                topologyPending = false,
                expectedProducerCount = 19,
                candidateLayers = 20,
                rendererCleanupPending = false,
            ),
        )
        assertEquals(
            HwcCapacityGenerationAction.WAIT,
            hwcCapacityGenerationAction(
                generationActivated = false,
                topologyPublished = true,
                topologyPending = false,
                expectedProducerCount = 20,
                candidateLayers = 20,
                rendererCleanupPending = true,
            ),
        )
        assertEquals(
            HwcCapacityGenerationAction.ACTIVATE,
            hwcCapacityGenerationAction(
                generationActivated = false,
                topologyPublished = true,
                topologyPending = false,
                expectedProducerCount = 20,
                candidateLayers = 20,
                rendererCleanupPending = false,
            ),
        )
        // A failed atomic activation attempt leaves the local state false, so the same committed
        // topology remains retryable until the caller's bounded deadline.
        assertEquals(
            HwcCapacityGenerationAction.ACTIVATE,
            hwcCapacityGenerationAction(
                generationActivated = false,
                topologyPublished = true,
                topologyPending = false,
                expectedProducerCount = 20,
                candidateLayers = 20,
                rendererCleanupPending = false,
            ),
        )
        assertEquals(
            HwcCapacityGenerationAction.OBSERVE,
            hwcCapacityGenerationAction(
                generationActivated = true,
                topologyPublished = false,
                topologyPending = true,
                expectedProducerCount = 0,
                candidateLayers = 20,
                rendererCleanupPending = true,
            ),
        )
    }

    @Test
    fun capacitySnapshotRequiresTheSameHealthyTopologyAfterSampling() {
        val profile = LayerSizeProfile.FULL_SCREEN.ordinal
        val ready = ProducerReadiness(
            expectedCount = 20,
            observedCount = 20,
            everObservedCount = 20,
            ready = true,
            topologyPublished = true,
            topologyPending = false,
            topologyPublishedAtMs = 10L,
            topologyRevision = 1L,
            geometryRequestedRevision = 4L,
            geometryAppliedRevision = 4L,
            geometryRequestedProfileOrdinal = profile,
            geometryAppliedProfileOrdinal = profile,
            geometryReady = true,
        )

        assertTrue(
            hwcCapacityCalibrationTopologyReady(
                readiness = ready,
                candidateLayers = 20,
                expectedProfileOrdinal = profile,
                rendererCleanupPending = false,
            ),
        )
        listOf(
            ready.copy(observedCount = 19),
            ready.copy(topologyPending = true),
            ready.copy(topologyMissed = true),
            ready.copy(teardownCompleted = true),
            ready.copy(teardownFailed = true),
            ready.copy(runtimeFailureReason = "producer failed"),
            ready.copy(geometryAppliedRevision = 3L),
            ready.copy(geometryAppliedProfileOrdinal = profile + 1),
        ).forEach { invalid ->
            assertFalse(
                hwcCapacityCalibrationTopologyReady(
                    readiness = invalid,
                    candidateLayers = 20,
                    expectedProfileOrdinal = profile,
                    rendererCleanupPending = false,
                ),
            )
        }
        assertFalse(
            hwcCapacityCalibrationTopologyReady(
                readiness = ready,
                candidateLayers = 20,
                expectedProfileOrdinal = profile,
                rendererCleanupPending = true,
            ),
        )
        assertTrue(
            hwcCapacityCalibrationTopologyUnchanged(
                before = ready,
                after = ready,
                candidateLayers = 20,
                expectedProfileOrdinal = profile,
                rendererCleanupPending = false,
            ),
        )
        assertFalse(
            hwcCapacityCalibrationTopologyUnchanged(
                before = ready,
                after = ready.copy(topologyRevision = ready.topologyRevision + 1L),
                candidateLayers = 20,
                expectedProfileOrdinal = profile,
                rendererCleanupPending = false,
            ),
        )
        assertFalse(
            hwcCapacityCalibrationTopologyUnchanged(
                before = ready,
                after = ready.copy(
                    topologyDiscontinuitySerial =
                        ready.topologyDiscontinuitySerial + 1L,
                ),
                candidateLayers = 20,
                expectedProfileOrdinal = profile,
                rendererCleanupPending = false,
            ),
        )
        assertFalse(
            hwcCapacityCalibrationTopologyUnchanged(
                before = ready,
                after = ready.copy(
                    geometryRequestedRevision = 5L,
                    geometryAppliedRevision = 5L,
                ),
                candidateLayers = 20,
                expectedProfileOrdinal = profile,
                rendererCleanupPending = false,
            ),
        )
    }

    @Test
    fun capacityCalibrationRequiresReadyCandidateAndFreshAtomicPair() {
        val valid = hwcCapacityCalibrationResult(
            candidateLayers = 20,
            expectedProducerCount = 20,
            observedProducerCount = 20,
            sampleStartedMonotonicMs = 100L,
            snapshot = hwcSnapshot(
                sampleMonotonicMs = 130L,
                evidenceMonotonicMs = 120L,
                deviceLayers = 8,
                clientLayers = 12,
            ),
        )
        assertEquals(
            HwcCapacityCalibrationStatus.OBSERVED_AT_CANDIDATE,
            valid.status,
        )
        assertEquals(20, valid.candidateLayers)
        assertEquals(8, valid.observedDeviceLayers)
        assertEquals(12, valid.observedClientLayers)
        assertTrue(valid.detail.contains("not a universal"))

        val preCalibrationCache = hwcCapacityCalibrationResult(
            candidateLayers = 20,
            expectedProducerCount = 20,
            observedProducerCount = 20,
            sampleStartedMonotonicMs = 121L,
            snapshot = hwcSnapshot(
                sampleMonotonicMs = 130L,
                evidenceMonotonicMs = 120L,
                deviceLayers = 8,
                clientLayers = 12,
            ),
        )
        assertEquals(
            HwcCapacityCalibrationStatus.UNAVAILABLE,
            preCalibrationCache.status,
        )
        assertNull(preCalibrationCache.observedDeviceLayers)

        val incompletePair = hwcCapacityCalibrationResult(
            candidateLayers = 12,
            expectedProducerCount = 12,
            observedProducerCount = 12,
            sampleStartedMonotonicMs = 100L,
            snapshot = hwcSnapshot(
                sampleMonotonicMs = 130L,
                evidenceMonotonicMs = 120L,
                deviceLayers = 0,
                clientLayers = 12,
            ).copy(
                hwcClientLayers = null,
                hwcClientLayersSource = "",
            ),
        )
        assertEquals(HwcCapacityCalibrationStatus.UNAVAILABLE, incompletePair.status)
        assertNull(incompletePair.observedDeviceLayers)
        assertNull(incompletePair.observedClientLayers)

        val topologyShortfall = hwcCapacityCalibrationResult(
            candidateLayers = 20,
            expectedProducerCount = 20,
            observedProducerCount = 19,
            sampleStartedMonotonicMs = 100L,
            snapshot = hwcSnapshot(
                sampleMonotonicMs = 130L,
                evidenceMonotonicMs = 120L,
                deviceLayers = 8,
                clientLayers = 11,
            ),
        )
        assertEquals(HwcCapacityCalibrationStatus.UNAVAILABLE, topologyShortfall.status)
    }

    @Test
    fun capacityReuseIsAdvisoryAndUnavailableNeverFabricatesBoundary() {
        val observed = HwcCapacityCalibrationResult(
            status = HwcCapacityCalibrationStatus.OBSERVED_AT_CANDIDATE,
            candidateLayers = 20,
            observedDeviceLayers = 8,
            observedClientLayers = 12,
            source = "vendor:test",
            quality = MetricQuality.HARDWARE_COUNTER,
        )

        val guidance = capacityReuseGuidance(observed)
        assertEquals(20, guidance.candidateProducerCount)
        assertEquals(8, guidance.observedAppDeviceLayers)
        assertEquals(12, guidance.observedAppClientLayers)
        assertTrue(guidance.detail.contains("no producer ceiling is inferred"))
        assertTrue(guidance.uiSummary().contains("workload DEVICE ceiling N/A"))
        assertTrue(observed.uiSummary().contains("HARDWARE_COUNTER@vendor:test"))
        assertTrue(observed.eventDetail().contains("requested=20; candidate=20"))
        assertTrue(observed.eventDetail().contains("lifetime=process-session"))
        assertTrue(observed.eventDetail().contains("control-root-not-corrected"))

        val unavailable = capacityReuseGuidance(
            HwcCapacityCalibrationResult(
                status = HwcCapacityCalibrationStatus.UNAVAILABLE,
                candidateLayers = 20,
                detail = "no fresh pair",
            ),
        )
        assertEquals(20, unavailable.candidateProducerCount)
        assertNull(unavailable.observedAppDeviceLayers)
        assertNull(unavailable.observedAppClientLayers)
    }

    @Test
    fun mediaPreflightLatchWaitIsCancellableAndCompletionWinsAtZeroTimeout() =
        runBlocking {
            val completed = CountDownLatch(0)
            assertTrue(awaitLatchCancellable(completed, timeoutMs = 0L))

            val blocked = CountDownLatch(1)
            val job = launch {
                awaitLatchCancellable(
                    latch = blocked,
                    timeoutMs = 60_000L,
                    pollMs = 10L,
                )
            }
            yield()
            val cancelStarted = System.nanoTime()
            job.cancelAndJoin()
            val cancelElapsedMs = (System.nanoTime() - cancelStarted) / 1_000_000L

            assertTrue("cancellation took ${cancelElapsedMs}ms", cancelElapsedMs < 500L)
            assertTrue(blocked.count == 1L)
        }

    @Test
    fun enteringPowerSaveInvalidatesOnlyAnUnconstrainedRunEnvelope() {
        assertTrue(
            safetyEnvelopeInvalidatedByPowerSave(
                envelopePowerSaveMode = false,
                currentPowerSaveMode = true,
            ),
        )
        assertFalse(
            safetyEnvelopeInvalidatedByPowerSave(
                envelopePowerSaveMode = true,
                currentPowerSaveMode = true,
            ),
        )
        assertFalse(
            safetyEnvelopeInvalidatedByPowerSave(
                envelopePowerSaveMode = false,
                currentPowerSaveMode = false,
            ),
        )
    }

    @Test
    fun mediaPreflightLeaseSpansWorkerLifetimeAndRejectsOverlap() {
        assertFalse(MediaPreflightSafetyState.isActive())
        val root = checkNotNull(MediaPreflightSafetyState.tryAcquire())
        val worker = checkNotNull(root.retain())
        try {
            assertTrue(MediaPreflightSafetyState.isActive())
            assertNull(MediaPreflightSafetyState.tryAcquire())

            root.close()
            assertTrue(MediaPreflightSafetyState.isActive())
            assertNull(MediaPreflightSafetyState.tryAcquire())
        } finally {
            worker.close()
            root.close()
        }
        assertFalse(MediaPreflightSafetyState.isActive())
        val next = checkNotNull(MediaPreflightSafetyState.tryAcquire())
        try {
            assertTrue(MediaPreflightSafetyState.isActive())
        } finally {
            next.close()
        }
        assertFalse(MediaPreflightSafetyState.isActive())
    }

    @Test
    fun compressionCleanupLatchRequiresAcknowledgedLinearReset() {
        try {
            CompressionSafetyState.recordLinearReset(confirmed = true)
            assertFalse(CompressionSafetyState.hasUnconfirmedCompressionCleanup())

            CompressionSafetyState.markNonLinearRouteMayBeActive()
            CompressionSafetyState.recordLinearReset(confirmed = false)
            assertTrue(CompressionSafetyState.hasUnconfirmedCompressionCleanup())

            CompressionSafetyState.recordLinearReset(confirmed = true)
            assertFalse(CompressionSafetyState.hasUnconfirmedCompressionCleanup())
        } finally {
            CompressionSafetyState.recordLinearReset(confirmed = true)
        }
    }

    @Test
    fun activeCompressionRouteIsBoundToItsAcknowledgingVendorSession() {
        assertFalse(
            compressionSessionContinuityLost(
                compressionControlActive = false,
                activeRoute = PixelRoute.SBWC_REQUIRED,
                expectedSession = 4L,
                observedSession = 5L,
            ),
        )
        assertFalse(
            compressionSessionContinuityLost(
                compressionControlActive = true,
                activeRoute = PixelRoute.RGB_8888,
                expectedSession = 4L,
                observedSession = 5L,
            ),
        )
        assertFalse(
            compressionSessionContinuityLost(
                compressionControlActive = true,
                activeRoute = PixelRoute.SBWC_AUTO,
                expectedSession = 4L,
                observedSession = 4L,
            ),
        )
        assertTrue(
            compressionSessionContinuityLost(
                compressionControlActive = true,
                activeRoute = PixelRoute.SBWC_AUTO,
                expectedSession = 4L,
                observedSession = null,
            ),
        )
        assertTrue(
            compressionSessionContinuityLost(
                compressionControlActive = true,
                activeRoute = PixelRoute.SBWC_REQUIRED,
                expectedSession = 4L,
                observedSession = 5L,
            ),
        )
        assertTrue(
            compressionSessionContinuityLost(
                compressionControlActive = true,
                activeRoute = PixelRoute.SBWC_REQUIRED,
                expectedSession = null,
                observedSession = 5L,
            ),
        )
    }

    @Test
    fun producerStartupDeadlineIncludesExactDurationAndStalledJump() {
        assertTrue(
            producerStartupTimedOut(
                phaseDurationMs = 3_000L,
                elapsedMs = 3_000L,
                hasProducedFrame = false,
                graceMs = 3_000L,
            ),
        )
        assertTrue(
            producerStartupTimedOut(
                phaseDurationMs = 10_000L,
                elapsedMs = 12_000L,
                hasProducedFrame = false,
                graceMs = 3_000L,
            ),
        )
        assertTrue(
            !producerStartupTimedOut(
                phaseDurationMs = 3_000L,
                elapsedMs = 3_000L,
                hasProducedFrame = true,
                graceMs = 3_000L,
            ),
        )
    }

    @Test
    fun compressionStateMachineFailsClosedAfterSbwc() {
        assertTrue(
            compressionStateAtAttemptStart(
                route = PixelRoute.SBWC_AUTO,
                activeBefore = false,
            ),
        )
        assertTrue(
            !compressionStateAtAttemptStart(
                route = PixelRoute.RGB_8888,
                activeBefore = false,
            ),
        )
        val sbwcApplied = decideCompressionTransition(
            route = PixelRoute.SBWC_REQUIRED,
            result = CompressionControlResult.APPLIED,
            activeBefore = false,
        )
        assertTrue(sbwcApplied.activeAfter)
        assertEquals(CompressionTransitionFailure.NONE, sbwcApplied.failure)

        val requiredAdapterMissing = decideCompressionTransition(
            route = PixelRoute.SBWC_REQUIRED,
            result = CompressionControlResult.NO_ADAPTER,
            activeBefore = false,
        )
        assertTrue(!requiredAdapterMissing.activeAfter)
        assertEquals(
            CompressionTransitionFailure.REQUIRED_ADAPTER_MISSING,
            requiredAdapterMissing.failure,
        )

        val adapterLost = decideCompressionTransition(
            route = PixelRoute.RGB_8888,
            result = CompressionControlResult.NO_ADAPTER,
            activeBefore = sbwcApplied.activeAfter,
        )
        assertTrue(adapterLost.activeAfter)
        assertEquals(
            CompressionTransitionFailure.ACTIVE_ROUTE_ADAPTER_LOST,
            adapterLost.failure,
        )

        val resetRejected = decideCompressionTransition(
            route = PixelRoute.RGB_8888,
            result = CompressionControlResult.REJECTED_OR_TIMEOUT,
            activeBefore = true,
        )
        assertTrue(resetRejected.activeAfter)
        assertEquals(
            CompressionTransitionFailure.REJECTED_OR_TIMEOUT,
            resetRejected.failure,
        )
    }

    @Test
    fun portableLinearRouteDoesNotRequireCompressionAdapter() {
        val decision = decideCompressionTransition(
            route = PixelRoute.RGB_8888,
            result = CompressionControlResult.NO_ADAPTER,
            activeBefore = false,
        )
        assertTrue(!decision.activeAfter)
        assertEquals(CompressionTransitionFailure.NONE, decision.failure)
    }

    @Test
    fun timedOutSbwcRequestRemainsPotentiallyActiveUntilConfirmedReset() {
        val timedOut = decideCompressionTransition(
            route = PixelRoute.SBWC_AUTO,
            result = CompressionControlResult.REJECTED_OR_TIMEOUT,
            activeBefore = false,
        )
        assertTrue(timedOut.activeAfter)
        assertEquals(
            CompressionTransitionFailure.REJECTED_OR_TIMEOUT,
            timedOut.failure,
        )

        val adapterLostDuringReset = decideCompressionTransition(
            route = PixelRoute.RGB_8888,
            result = CompressionControlResult.NO_ADAPTER,
            activeBefore = timedOut.activeAfter,
        )
        assertTrue(adapterLostDuringReset.activeAfter)
        assertEquals(
            CompressionTransitionFailure.ACTIVE_ROUTE_ADAPTER_LOST,
            adapterLostDuringReset.failure,
        )
    }

    @Test
    fun p010ValidationAcceptsOnlyVerifiedTenBitProfiles() {
        assertTrue(
            isVerifiedP010VideoProfile(
                "video/hevc",
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
                null,
            ),
        )
        assertTrue(
            isVerifiedP010VideoProfile(
                "video/x-vnd.on2.vp9",
                MediaCodecInfo.CodecProfileLevel.VP9Profile2,
                "vp09.02.51.10.01.09.16.09.01",
            ),
        )
        assertFalse(
            isVerifiedP010VideoProfile(
                "video/x-vnd.on2.vp9",
                MediaCodecInfo.CodecProfileLevel.VP9Profile2,
                "vp09.02.51.12.01.09.16.09.01",
            ),
        )
        assertFalse(
            isVerifiedP010VideoProfile(
                "video/x-vnd.on2.vp9",
                MediaCodecInfo.CodecProfileLevel.VP9Profile2,
                null,
            ),
        )
        assertFalse(
            isVerifiedP010VideoProfile(
                "video/x-vnd.on2.vp9",
                MediaCodecInfo.CodecProfileLevel.VP9Profile3,
                "vp09.03.51.10.03.09.16.09.01",
            ),
        )
        assertTrue(
            !isVerifiedP010VideoProfile(
                "video/hevc",
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain,
                null,
            ),
        )
        assertTrue(!isVerifiedP010VideoProfile("video/hevc", null, null))
        assertTrue(
            !isVerifiedP010VideoProfile(
                "video/unknown",
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
                null,
            ),
        )
    }

    @Test
    fun vp9CodecStringBitDepthParsingFailsClosed() {
        assertEquals(
            10,
            vp9BitDepthFromCodecsString("vp09.02.10.10.01.09.16.09.01"),
        )
        assertEquals(
            12,
            vp9BitDepthFromCodecsString("vp09.02.10.12.01.09.16.09.01"),
        )
        assertEquals(
            10,
            vp9BitDepthFromCodecsString(
                "mp4a.40.2, vp09.02.10.10.01.09.16.09.01",
            ),
        )
        assertNull(vp9BitDepthFromCodecsString("vp09.02.10"))
        assertNull(vp9BitDepthFromCodecsString("vp09.02.BAD.10"))
        assertNull(vp9BitDepthFromCodecsString("vp09.02.99.10"))
        assertNull(vp9BitDepthFromCodecsString("vp09.03.10.10.03.09.16.09.01"))
        assertNull(vp9BitDepthFromCodecsString("vp09.02.10.08.01.09.16.09.01"))
        assertNull(vp9BitDepthFromCodecsString("vp09.02.10.10.04.09.16.09.01"))
        assertNull(vp9BitDepthFromCodecsString("vp09.02.10.10.01.09.16.09.02"))
        assertNull(vp9BitDepthFromCodecsString("vp09.02.10.10.01.09.16.09.01.00"))
        assertNull(
            vp9BitDepthFromCodecsString(
                "vp09.02.10.10.01, vp09.02.10.12.01",
            ),
        )
        assertNull(vp9BitDepthFromCodecsString("x".repeat(513)))
    }

    @Test
    fun counterDeltaRequiresStableSourceAndMonotonicValue() {
        assertEquals(
            7L,
            monotonicCounterDelta(10L, "vendor", 17L, "vendor"),
        )
        assertNull(monotonicCounterDelta(10L, "vendor", 17L, "sysfs"))
        assertNull(monotonicCounterDelta(10L, "vendor", 9L, "vendor"))
        assertNull(monotonicCounterDelta(null, "vendor", 17L, "vendor"))
        assertNull(monotonicCounterDelta(10L, null, 17L, null))
    }

    @Test
    fun zeroExactDeltaRequiresContinuousPostBaselineSampling() {
        assertNull(
            reliableExactCounterDelta(10L, "kernel", 10L, true, 0),
        )
        assertNull(
            reliableExactCounterDelta(10L, "kernel", 10L, false, 2),
        )
        assertEquals(
            0L,
            reliableExactCounterDelta(10L, "kernel", 10L, true, 1),
        )
        // A monotonic increase already observed remains evidence after a later source loss.
        assertEquals(
            2L,
            reliableExactCounterDelta(10L, "kernel", 12L, false, 1),
        )
    }

    @Test
    fun adaptiveBoundaryJumpsToRecoveryAndRecoveryIsNotACandidate() {
        val scenario = checkNotNull(ScenarioCatalog.byId("adaptive-underrun-hunt"))
        val recoveryIndex = adaptiveRecoveryPhaseIndex(scenario)
        val firstHuntIndex = scenario.phases.indexOfFirst { it.id == "hunt-0" }

        assertEquals(scenario.phases.lastIndex, recoveryIndex)
        assertTrue(
            isAdaptiveBoundaryCandidate(
                scenarioId = scenario.id,
                phaseId = scenario.phases[firstHuntIndex].id,
            ),
        )
        assertTrue(
            !isAdaptiveBoundaryCandidate(
                scenarioId = scenario.id,
                phaseId = scenario.phases[recoveryIndex].id,
            ),
        )
        assertEquals(
            recoveryIndex,
            phaseIndexAfterAdaptiveBoundary(firstHuntIndex, recoveryIndex),
        )
    }

    @Test
    fun adaptiveProxyThresholdIntegratesMidPhaseThermalDerate() {
        val frameBudget = AppliedProducerFrameBudget(
            phaseStartedMs = 1_000L,
            phaseDurationMs = 7_000L,
        )
        frameBudget.apply(atMonotonicMs = 1_000L, producerFps = 120f)
        frameBudget.apply(atMonotonicMs = 1_100L, producerFps = 60f)

        val appliedFrames = frameBudget.finish(atMonotonicMs = 8_500L)

        assertEquals(426.0, appliedFrames, 0.001)
        assertEquals(8L, adaptiveProxyBoundaryThreshold(appliedFrames))
        assertTrue(9L >= adaptiveProxyBoundaryThreshold(appliedFrames))
        // Finishing after the nominal deadline is bounded and idempotent.
        assertEquals(appliedFrames, frameBudget.finish(Long.MAX_VALUE), 0.0)
    }

    @Test
    fun aggregateProducerBudgetTracksDynamicLayerCountAndDetectsMaterialShortfall() {
        val frameBudget = AppliedProducerFrameBudget(
            phaseStartedMs = 1_000L,
            phaseDurationMs = 1_000L,
        )
        frameBudget.apply(
            atMonotonicMs = 1_000L,
            producerFps = 60f,
            activeLayers = 2,
        )
        frameBudget.apply(
            atMonotonicMs = 1_500L,
            producerFps = 120f,
            activeLayers = 4,
        )

        assertEquals(90.0, frameBudget.finish(atMonotonicMs = 2_000L), 0.001)
        assertEquals(300.0, frameBudget.expectedAggregateFrames(), 0.001)
        assertFalse(assessProducerRate(actualFrames = 210L, expectedFrames = 300.0).materialShortfall)
        assertTrue(assessProducerRate(actualFrames = 209L, expectedFrames = 300.0).materialShortfall)
        // Tiny phases do not have enough observations for a stable rate verdict.
        assertFalse(assessProducerRate(actualFrames = 0L, expectedFrames = 29.9).materialShortfall)
        assertFalse(
            assessProducerRate(actualFrames = null, expectedFrames = 29.9).materialShortfall,
        )
        assertFalse(
            assessProducerRate(actualFrames = -1L, expectedFrames = 29.9).materialShortfall,
        )
        assertTrue(
            assessProducerRate(actualFrames = null, expectedFrames = 30.0).materialShortfall,
        )
        assertTrue(
            assessProducerRate(
                actualFrames = 1L,
                expectedFrames = Double.NaN,
            ).materialShortfall,
        )
    }

    @Test
    fun aggregateProducerObservationDiscardsRecoveryFramesOnTheSameActiveClock() {
        val frameBudget = AppliedProducerFrameBudget(
            phaseStartedMs = 1_000L,
            phaseDurationMs = 600L,
        )
        frameBudget.observePhysicalFrames(totalFrames = 100L, countAsActive = false)
        frameBudget.apply(
            atMonotonicMs = 1_000L,
            producerFps = 60f,
            activeLayers = 2,
        )
        frameBudget.pauseAtPhysicalBoundary(
            atMonotonicMs = 1_100L,
            totalFrames = 112L,
        )
        // These frames were posted while a process-wide teardown lease paused the phase.
        frameBudget.observePhysicalFrames(totalFrames = 200L, countAsActive = false)
        frameBudget.resume(atMonotonicMs = 1_600L)
        frameBudget.observePhysicalFrames(totalFrames = 260L, countAsActive = true)

        assertEquals(36.0, frameBudget.finish(atMonotonicMs = 2_100L), 0.001)
        assertEquals(72.0, frameBudget.expectedAggregateFrames(), 0.001)
        assertEquals(72L, frameBudget.actualAggregateFrames())

        val regressed = AppliedProducerFrameBudget(phaseStartedMs = 0L, phaseDurationMs = 1_000L)
        regressed.observePhysicalFrames(totalFrames = 10L, countAsActive = false)
        regressed.observePhysicalFrames(totalFrames = 9L, countAsActive = true)
        assertNull(regressed.actualAggregateFrames())
        assertTrue(
            assessProducerRate(
                actualFrames = regressed.actualAggregateFrames(),
                expectedFrames = 60.0,
            ).materialShortfall,
        )
    }

    @Test
    fun terminalEndpointHoldFramesCannotInflateProducerFidelityRatio() {
        val frameBudget = AppliedProducerFrameBudget(
            phaseStartedMs = 0L,
            phaseDurationMs = 1_000L,
        )
        frameBudget.observePhysicalFrames(totalFrames = 100L, countAsActive = false)
        frameBudget.apply(
            atMonotonicMs = 0L,
            producerFps = 60f,
            activeLayers = 2,
        )
        frameBudget.observePhysicalFrames(totalFrames = 220L, countAsActive = true)
        frameBudget.sealNominalActualWindow(
            atMonotonicMs = 1_000L,
            totalFrames = 220L,
        )

        // These frames prove the terminal control publication, but belong to its bounded hold.
        frameBudget.observePhysicalFrames(totalFrames = 820L, countAsActive = true)
        frameBudget.sealNominalActualWindow(
            atMonotonicMs = 4_000L,
            totalFrames = 900L,
        )
        frameBudget.finish(atMonotonicMs = 4_000L)

        assertEquals(120.0, frameBudget.expectedAggregateFrames(), 0.001)
        assertEquals(120L, frameBudget.actualAggregateFrames())
        assertEquals(
            1.0,
            assessProducerRate(
                actualFrames = frameBudget.actualAggregateFrames(),
                expectedFrames = frameBudget.expectedAggregateFrames(),
            ).ratio!!,
            0.001,
        )
    }

    @Test
    fun delayedEndpointTickUsesOneObservedBoundaryForActualAndExpectedFidelity() {
        val frameBudget = AppliedProducerFrameBudget(
            phaseStartedMs = 0L,
            phaseDurationMs = 1_000L,
        )
        frameBudget.observePhysicalFrames(totalFrames = 0L, countAsActive = false)
        frameBudget.apply(
            atMonotonicMs = 0L,
            producerFps = 60f,
            activeLayers = 2,
        )

        // The controller did not run at the nominal 1 s boundary. The aggregate counter cannot
        // separate the first second from these three seconds of pre-endpoint apply delay.
        frameBudget.sealNominalActualWindow(
            atMonotonicMs = 4_000L,
            totalFrames = 480L,
            useObservedExpectedBoundary = true,
        )
        // Endpoint apply and its acknowledgment hold must change neither side after the seal.
        frameBudget.apply(
            atMonotonicMs = 4_000L,
            producerFps = 120f,
            activeLayers = 2,
        )
        frameBudget.observePhysicalFrames(totalFrames = 960L, countAsActive = true)
        frameBudget.finish(atMonotonicMs = 6_000L)

        assertEquals(480.0, frameBudget.expectedAggregateFrames(), 0.001)
        assertEquals(480L, frameBudget.actualAggregateFrames())
        assertEquals(
            1.0,
            assessProducerRate(
                actualFrames = frameBudget.actualAggregateFrames(),
                expectedFrames = frameBudget.expectedAggregateFrames(),
            ).ratio!!,
            0.001,
        )
    }

    @Test
    fun activePhaseClockFreezesDuringRepeatedRecoveryEpisodes() {
        val clock = ActivePhaseClock(startedAtMs = 1_000L)
        assertEquals(100L, clock.elapsedMs(1_100L))

        clock.pause(1_100L)
        assertEquals(100L, clock.elapsedMs(1_600L))
        assertEquals(500L, clock.currentPauseMs(1_600L))
        clock.resume(1_600L)
        assertEquals(600L, clock.elapsedMs(2_100L))

        clock.pause(2_100L)
        assertEquals(600L, clock.elapsedMs(4_400L))
        clock.resume(4_400L)
        assertEquals(2_800L, clock.totalPausedMs(4_400L))
        assertEquals(0L, clock.currentPauseMs(4_400L))
        assertEquals(1_100L, clock.elapsedMs(4_900L))
    }

    @Test
    fun overlappingThermalAndProducerRecoveryPauseOwnersCannotResumeEachOther() {
        val clock = ActivePhaseClock(startedAtMs = 1_000L)
        clock.pause(1_100L, PhasePauseOwner.THERMAL_DERATE)
        clock.pause(1_200L, PhasePauseOwner.PRODUCER_RECOVERY)

        clock.resume(1_300L, PhasePauseOwner.THERMAL_DERATE)
        assertEquals(100L, clock.elapsedMs(1_500L))
        assertEquals(
            300L,
            clock.currentPauseMs(1_500L, PhasePauseOwner.PRODUCER_RECOVERY),
        )

        clock.resume(1_600L, PhasePauseOwner.PRODUCER_RECOVERY)
        assertEquals(500L, clock.totalPausedMs(1_600L))
        assertEquals(200L, clock.elapsedMs(1_700L))

        val budget = AppliedProducerFrameBudget(
            phaseStartedMs = 1_000L,
            phaseDurationMs = 1_000L,
        )
        budget.apply(1_000L, producerFps = 60f, activeLayers = 1)
        budget.pause(1_100L, PhasePauseOwner.THERMAL_DERATE)
        budget.pause(1_200L, PhasePauseOwner.PRODUCER_RECOVERY)
        budget.resume(1_300L, PhasePauseOwner.THERMAL_DERATE)
        budget.resume(1_600L, PhasePauseOwner.PRODUCER_RECOVERY)
        assertEquals(12.0, budget.finish(1_700L), 0.001)
    }

    @Test
    fun hwcProbeNeedsAFullBoundedWindowAndDoesNotCreateHiddenPhaseTime() {
        assertFalse(
            hwcCompositionProbeCanStart(
                remainingActiveMs = 4_099L,
                sampleTimeoutMs = 4_000L,
                completionReserveMs = 100L,
            ),
        )
        assertTrue(
            hwcCompositionProbeCanStart(
                remainingActiveMs = 4_100L,
                sampleTimeoutMs = 4_000L,
                completionReserveMs = 100L,
            ),
        )
        assertFalse(
            hwcCompositionProbeCanStart(
                remainingActiveMs = Long.MAX_VALUE,
                sampleTimeoutMs = Long.MAX_VALUE,
                completionReserveMs = 1L,
            ),
        )

        // Probe work keeps the target active, so the ordinary phase clock/frame budget integrates
        // the entire observation interval instead of pausing it as unreported time.
        val clock = ActivePhaseClock(startedAtMs = 1_000L)
        val budget = AppliedProducerFrameBudget(
            phaseStartedMs = 1_000L,
            phaseDurationMs = 4_000L,
        )
        budget.apply(1_000L, producerFps = 60f, activeLayers = 4)
        assertEquals(4_000L, clock.elapsedMs(5_000L))
        assertEquals(240.0, budget.finish(5_000L), 0.001)
        assertEquals(960.0, budget.expectedAggregateFrames(), 0.001)
    }

    @Test
    fun clientHwcProbeForcesTwoBoundedAttemptsUnlessEvidenceCompletesEarlier() {
        assertEquals(
            HwcCompositionProbeAction.FORCE_SAMPLE,
            hwcCompositionProbeAction(
                expectation = HwcCompositionExpectation.CLIENT_REQUIRED,
                matchingEvidenceCount = 0,
                terminalFailure = false,
                forcedAttempts = 0,
            ),
        )
        assertEquals(
            HwcCompositionProbeAction.FORCE_SAMPLE,
            hwcCompositionProbeAction(
                expectation = HwcCompositionExpectation.CLIENT_REQUIRED,
                matchingEvidenceCount = 1,
                terminalFailure = false,
                forcedAttempts = 1,
            ),
        )
        assertEquals(
            HwcCompositionProbeAction.COMPLETE,
            hwcCompositionProbeAction(
                expectation = HwcCompositionExpectation.CLIENT_REQUIRED,
                matchingEvidenceCount = 2,
                terminalFailure = false,
                forcedAttempts = 1,
            ),
        )
        assertEquals(
            HwcCompositionProbeAction.EXHAUSTED,
            hwcCompositionProbeAction(
                expectation = HwcCompositionExpectation.CLIENT_REQUIRED,
                matchingEvidenceCount = 1,
                terminalFailure = false,
                forcedAttempts = 2,
            ),
        )
        assertEquals(
            HwcCompositionProbeAction.COMPLETE,
            hwcCompositionProbeAction(
                expectation = HwcCompositionExpectation.CLIENT_REQUIRED,
                matchingEvidenceCount = 0,
                terminalFailure = true,
                forcedAttempts = 0,
            ),
        )
        assertEquals(
            HwcCompositionProbeAction.EXHAUSTED,
            hwcCompositionProbeAction(
                expectation = HwcCompositionExpectation.DEVICE_ONLY,
                matchingEvidenceCount = 0,
                terminalFailure = false,
                forcedAttempts = 1,
            ),
        )
    }

    @Test
    fun typedHwcPriorityDropsPeriodicWaitersAndReleasesOnlyItsOwner() {
        assertEquals(
            PeriodicTelemetryArbitration.SAMPLE,
            periodicTelemetryArbitration(
                mutexAcquired = true,
                typedHwcProbePriority = false,
            ),
        )
        assertEquals(
            PeriodicTelemetryArbitration.DROP_BUSY,
            periodicTelemetryArbitration(
                mutexAcquired = false,
                typedHwcProbePriority = false,
            ),
        )
        assertEquals(
            PeriodicTelemetryArbitration.DROP_TYPED_HWC_PRIORITY,
            periodicTelemetryArbitration(
                mutexAcquired = true,
                typedHwcProbePriority = true,
            ),
        )

        val gate = HwcCompositionProbePriorityGate()
        val firstOwner = Any()
        val foreignOwner = Any()
        assertTrue(gate.acquire(firstOwner))
        assertTrue(gate.acquire(firstOwner))
        assertTrue(gate.isActive())
        assertFalse(gate.acquire(foreignOwner))
        assertFalse(gate.release(foreignOwner))
        assertTrue(gate.isActive())
        assertTrue(gate.release(firstOwner))
        assertFalse(gate.isActive())

        assertTrue(gate.acquire(firstOwner))
        gate.reset()
        assertFalse(gate.isActive())
    }

    @Test
    fun producerRecoveryDeadlineAllowsClearAtExactBoundaryOnly() {
        assertFalse(
            producerRecoveryDeadlineExceeded(
                recoveryStillActive = false,
                currentPauseMs = 5_000L,
                timeoutMs = 5_000L,
            ),
        )
        assertTrue(
            producerRecoveryDeadlineExceeded(
                recoveryStillActive = true,
                currentPauseMs = 5_000L,
                timeoutMs = 5_000L,
            ),
        )
        assertTrue(
            producerRecoveryDeadlineExceeded(
                recoveryStillActive = false,
                currentPauseMs = 5_001L,
                timeoutMs = 5_000L,
            ),
        )
    }

    @Test
    fun stopOnlyMutatesAnActivelyRunningPlanNotItsCleanupOwnershipWindow() {
        assertTrue(shouldStopActivePlan(jobPresent = true, isRunning = true))
        assertFalse(shouldStopActivePlan(jobPresent = true, isRunning = false))
        assertFalse(shouldStopActivePlan(jobPresent = false, isRunning = true))
        assertFalse(shouldStopActivePlan(jobPresent = false, isRunning = false))
    }

    @Test
    fun thermalDerateFailsClosedWhenEitherRuntimeActionIsUnconfirmed() {
        assertFalse(
            thermalDerateActionFailed(
                orderedZeroConfirmed = true,
                workloadApplied = true,
                displayApplied = true,
            ),
        )
        assertTrue(
            thermalDerateActionFailed(
                orderedZeroConfirmed = true,
                workloadApplied = false,
                displayApplied = true,
            ),
        )
        assertTrue(
            thermalDerateActionFailed(
                orderedZeroConfirmed = true,
                workloadApplied = true,
                displayApplied = false,
            ),
        )
        assertTrue(
            thermalDerateActionFailed(
                orderedZeroConfirmed = false,
                workloadApplied = true,
                displayApplied = true,
            ),
        )
    }

    @Test
    fun hudExpectedProducerCountIsHiddenUntilCommittedTopologyIsUsable() {
        assertEquals(
            0,
            visibleExpectedProducerCount(
                committedExpectedCount = 0,
                topologyPublished = false,
                topologyPending = false,
                processLeaseActive = false,
            ),
        )
        assertEquals(
            0,
            visibleExpectedProducerCount(
                committedExpectedCount = 4,
                topologyPublished = true,
                topologyPending = true,
                processLeaseActive = false,
            ),
        )
        assertEquals(
            0,
            visibleExpectedProducerCount(
                committedExpectedCount = 4,
                topologyPublished = true,
                topologyPending = false,
                processLeaseActive = true,
            ),
        )
        assertEquals(
            4,
            visibleExpectedProducerCount(
                committedExpectedCount = 4,
                topologyPublished = true,
                topologyPending = false,
                processLeaseActive = false,
            ),
        )
    }

    @Test
    fun topologyPendingImmediatelyHidesExpectedPhysicalCountWithoutRewritingObservedCount() {
        val pending = progressForProducerTopologyPending(
            RunProgress(
                expectedProducerCount = 4,
                observedProducerCount = 2,
            ),
        )

        assertEquals(0, pending.expectedProducerCount)
        assertEquals(2, pending.observedProducerCount)
    }

    @Test
    fun producerRecoveryCallbackFailsClosedIfEitherSafeActionIsUnconfirmed() {
        assertFalse(
            producerRecoverySafePointFailed(
                loadsReleased = true,
                displayReduced = true,
            ),
        )
        assertTrue(
            producerRecoverySafePointFailed(
                loadsReleased = false,
                displayReduced = true,
            ),
        )
        assertTrue(
            producerRecoverySafePointFailed(
                loadsReleased = true,
                displayReduced = false,
            ),
        )
    }

    @Test
    fun appliedFrameBudgetExcludesDrainAndThermalChangeDuringPause() {
        val frameBudget = AppliedProducerFrameBudget(
            phaseStartedMs = 1_000L,
            phaseDurationMs = 600L,
        )
        frameBudget.apply(atMonotonicMs = 1_000L, producerFps = 120f)
        frameBudget.pause(atMonotonicMs = 1_100L)
        // Models a SEVERE thermal callback while the controller keeps cross-load at zero.
        frameBudget.apply(atMonotonicMs = 1_300L, producerFps = 60f)
        frameBudget.resume(atMonotonicMs = 1_600L)

        assertEquals(42.0, frameBudget.finish(atMonotonicMs = 2_100L), 0.001)
    }

    @Test
    fun preparationUsesInitialTransitionTopologyAtSafeZeroLoad() {
        val phases = checkNotNull(ScenarioCatalog.byId("gradual-load-transitions")).phases
        val base = phases.first { it.id == "gt-base" }
        val rampUp = phases.first { it.id == "gt-ramp-up" }
        val rampDown = phases.first { it.id == "gt-ramp-down" }

        val upInitial = LoadTransitionEvaluator.interpolate(
            previous = base,
            target = rampUp,
            fraction = LoadTransitionEvaluator.factorAt(
                rampUp.transition,
                elapsedMs = 0L,
                phaseDurationMs = rampUp.durationMs,
            ),
        )
        val upPreparation = rendererPreparationPhase(upInitial)
        assertEquals(base.activeLayers, upPreparation.activeLayers)
        assertEquals(60f, upPreparation.producerFps, 0f)
        assertEquals(60f, upPreparation.requestedDisplayHz, 0f)
        assertEquals(MotionProfile.STATIC, upPreparation.motion)
        assertEquals(0f, upPreparation.workloads.cpu, 0f)
        assertEquals(0f, upPreparation.workloads.memory, 0f)
        assertEquals(0f, upPreparation.workloads.gpu, 0f)
        assertEquals(0f, upPreparation.workloads.npu, 0f)

        val downInitial = LoadTransitionEvaluator.interpolate(
            previous = rampUp,
            target = rampDown,
            fraction = 0f,
        )
        assertEquals(
            rampUp.activeLayers,
            rendererPreparationPhase(downInitial).activeLayers,
        )
    }

    @Test
    fun warmupIsPortableRgbAndRouteChangeUsesTargetAllocationTopology() {
        val source = checkNotNull(ScenarioCatalog.byId("sbwc-matrix")).phases
            .first { it.id == "sbwc" }
        val target = source.copy(
            activeLayers = 8,
            backend = LayerBackend.MIXED_SURFACE_TEXTURE,
            pixelRoute = PixelRoute.SBWC_REQUIRED,
            bufferSize = BufferSize.UHD_4K,
            layerSizeProfile = LayerSizeProfile.MIXED_SIZES,
            alphaOverlap = true,
            includeGlLayer = true,
            hwcCompositionExpectation = HwcCompositionExpectation.CLIENT_REQUIRED,
        )

        val warmup = safeWarmupPhaseFor(target)
        assertEquals(1, warmup.activeLayers)
        assertEquals(LayerBackend.INDEPENDENT_SURFACES, warmup.backend)
        assertEquals(PixelRoute.RGB_8888, warmup.pixelRoute)
        assertEquals(BufferSize.DISPLAY, warmup.bufferSize)
        assertEquals(LayerSizeProfile.FULL_SCREEN, warmup.layerSizeProfile)
        assertFalse(warmup.alphaOverlap)
        assertFalse(warmup.includeGlLayer)
        assertEquals(HwcCompositionExpectation.NONE, warmup.hwcCompositionExpectation)
        assertEquals(
            HwcCompositionExpectation.NONE,
            rendererPreparationPhase(target).hwcCompositionExpectation,
        )
        assertTrue(rendererAllocationRouteChanges(warmup, target))
        assertFalse(rendererAllocationRouteChanges(null, warmup))

        val priorRuntime = warmup.copy(
            activeLayers = 3,
            producerFps = 30f,
            workloads = LoadSetpoints(cpu = 0.4f),
        )
        val safeInitial = allocationRouteSafePhase(priorRuntime, target)
        assertEquals(target.activeLayers, safeInitial.activeLayers)
        assertEquals(target.backend, safeInitial.backend)
        assertEquals(target.pixelRoute, safeInitial.pixelRoute)
        assertEquals(target.bufferSize, safeInitial.bufferSize)
        assertEquals(priorRuntime.layerSizeProfile, safeInitial.layerSizeProfile)
        assertEquals(target.alphaOverlap, safeInitial.alphaOverlap)
        assertEquals(target.includeGlLayer, safeInitial.includeGlLayer)
        assertEquals(priorRuntime.producerFps, safeInitial.producerFps, 0f)
        assertEquals(priorRuntime.workloads, safeInitial.workloads)

        // Every active tick, including the measured fraction-zero origin, must retain the
        // allocation topology matching the vendor route that was already applied.
        listOf(0f, 0.25f, 1f).forEach { fraction ->
            val interpolated = LoadTransitionEvaluator.interpolate(
                previous = priorRuntime,
                target = target,
                fraction = fraction,
            )
            val safeRuntime = allocationRouteSafePhase(interpolated, target)
            assertEquals(target.activeLayers, safeRuntime.activeLayers)
            assertEquals(target.backend, safeRuntime.backend)
            assertEquals(target.pixelRoute, safeRuntime.pixelRoute)
            assertEquals(target.bufferSize, safeRuntime.bufferSize)
            assertEquals(interpolated.layerSizeProfile, safeRuntime.layerSizeProfile)
            assertEquals(target.alphaOverlap, safeRuntime.alphaOverlap)
            assertEquals(target.includeGlLayer, safeRuntime.includeGlLayer)
            assertEquals(interpolated.producerFps, safeRuntime.producerFps, 0f)
            assertEquals(interpolated.workloads, safeRuntime.workloads)
        }

        val sbwcOrigin = target
        val linearTarget = warmup.copy(
            activeLayers = 5,
            backend = LayerBackend.MIXED_SURFACE_TEXTURE,
            bufferSize = BufferSize.FHD,
            producerFps = 72f,
        )
        val reverseOrigin = LoadTransitionEvaluator.interpolate(
            previous = sbwcOrigin,
            target = linearTarget,
            fraction = 0f,
        )
        val safeReverse = allocationRouteSafePhase(reverseOrigin, linearTarget)
        assertEquals(PixelRoute.RGB_8888, safeReverse.pixelRoute)
        assertEquals(linearTarget.backend, safeReverse.backend)
        assertEquals(linearTarget.bufferSize, safeReverse.bufferSize)
        assertEquals(reverseOrigin.layerSizeProfile, safeReverse.layerSizeProfile)
        assertEquals(linearTarget.activeLayers, safeReverse.activeLayers)
        assertEquals(sbwcOrigin.producerFps, safeReverse.producerFps, 0f)

        val gpuOrigin = sbwcOrigin.copy(
            includeGlLayer = true,
            workloads = LoadSetpoints(gpu = 0.8f),
        )
        val noGlLinearTarget = linearTarget.copy(
            includeGlLayer = false,
            workloads = LoadSetpoints(),
        )
        val safeGpuRelease = allocationRouteSafePhase(
            initial = LoadTransitionEvaluator.interpolate(
                previous = gpuOrigin,
                target = noGlLinearTarget,
                fraction = 0f,
            ),
            target = noGlLinearTarget,
        )
        assertFalse(safeGpuRelease.includeGlLayer)
        assertEquals(0f, safeGpuRelease.workloads.gpu, 0f)
    }

    @Test
    fun projectionAndOrientationKeepMeasuredOriginUntilDiscreteTargetStarts() {
        val origin = checkNotNull(ScenarioCatalog.byId("baseline-display-modes"))
            .phases.first()
            .copy(
                bufferSize = BufferSize.UHD_8K,
                bufferPresentation = BufferPresentation.FIT,
                layerOrientation = LayerOrientation.ROTATION_0,
                motion = MotionProfile.STATIC,
                layerSizeProfile = LayerSizeProfile.FULL_SCREEN,
            )
        val target = origin.copy(
            id = "projected-target",
            pixelRoute = PixelRoute.SBWC_REQUIRED,
            bufferPresentation = BufferPresentation.PIXEL_1_TO_1_CROP,
            layerOrientation = LayerOrientation.ROTATION_90,
        )

        val fractionZero = LoadTransitionEvaluator.interpolate(origin, target, 0f)
        val preparedZero = allocationRouteSafePhase(fractionZero, target)
        assertEquals(target.pixelRoute, preparedZero.pixelRoute)
        assertEquals(origin.bufferPresentation, preparedZero.bufferPresentation)
        assertEquals(origin.layerOrientation, preparedZero.layerOrientation)

        val fractionPositive = LoadTransitionEvaluator.interpolate(origin, target, 0.01f)
        val preparedPositive = allocationRouteSafePhase(fractionPositive, target)
        assertEquals(target.bufferPresentation, preparedPositive.bufferPresentation)
        assertEquals(target.layerOrientation, preparedPositive.layerOrientation)

        assertTrue(rendererTopologyChanged(origin, target))
        assertTrue(
            rendererTopologyChanged(
                origin,
                origin.copy(layerOrientation = LayerOrientation.ROTATION_90),
            ),
        )
        val typedOrigin = origin.copy(
            hwcCompositionExpectation = HwcCompositionExpectation.DEVICE_ONLY,
        )
        assertFalse(
            hwcCompositionContractPreserved(
                typedOrigin,
                typedOrigin.copy(
                    bufferPresentation = BufferPresentation.PIXEL_1_TO_1_CROP,
                ),
            ),
        )
        assertFalse(
            hwcCompositionContractPreserved(
                typedOrigin,
                typedOrigin.copy(layerOrientation = LayerOrientation.ROTATION_90),
            ),
        )
    }

    @Test
    fun warmupBaselineRequiresFreshProducerGeometryAndNoCleanupLease() {
        val profile = LayerSizeProfile.FULL_SCREEN.ordinal
        val ready = ProducerReadiness(
            expectedCount = 1,
            observedCount = 1,
            everObservedCount = 1,
            ready = true,
            topologyPublished = true,
            topologyPending = false,
            geometryRequestedRevision = 2L,
            geometryAppliedRevision = 2L,
            geometryRequestedProfileOrdinal = profile,
            geometryAppliedProfileOrdinal = profile,
            geometryReady = true,
        )

        assertTrue(
            warmupProducerReadyForBaseline(
                readiness = ready,
                expectedProfileOrdinal = profile,
                processLeaseActive = false,
            ),
        )
        listOf(
            ready.copy(ready = false),
            ready.copy(observedCount = 0),
            ready.copy(topologyPublished = false),
            ready.copy(topologyPending = true),
            ready.copy(topologyMissed = true),
            ready.copy(teardownCompleted = true),
            ready.copy(teardownFailed = true),
            ready.copy(runtimeFailureReason = "failed"),
            ready.copy(geometryReady = false),
            ready.copy(geometryAppliedProfileOrdinal = profile + 1),
        ).forEach { invalid ->
            assertFalse(
                warmupProducerReadyForBaseline(
                    readiness = invalid,
                    expectedProfileOrdinal = profile,
                    processLeaseActive = false,
                ),
            )
        }
        assertFalse(
            warmupProducerReadyForBaseline(
                readiness = ready,
                expectedProfileOrdinal = profile,
                processLeaseActive = true,
            ),
        )

        val boundary = checkNotNull(
            captureWarmupBaselineProducerBoundary(
                readiness = ready,
                expectedProfileOrdinal = profile,
                processLeaseActive = false,
            ),
        )
        assertTrue(
            warmupBaselineProducerBoundaryUnchanged(
                expected = boundary,
                readiness = ready,
                expectedProfileOrdinal = profile,
                processLeaseActive = false,
            ),
        )
        assertFalse(
            warmupBaselineProducerBoundaryUnchanged(
                expected = boundary,
                readiness = ready.copy(topologyDiscontinuitySerial = 1L),
                expectedProfileOrdinal = profile,
                processLeaseActive = false,
            ),
        )
        assertFalse(
            warmupBaselineProducerBoundaryUnchanged(
                expected = boundary,
                readiness = ready.copy(topologyRevision = 1L),
                expectedProfileOrdinal = profile,
                processLeaseActive = false,
            ),
        )
        assertFalse(
            warmupBaselineProducerBoundaryUnchanged(
                expected = boundary,
                readiness = ready.copy(geometryAppliedRevision = 3L),
                expectedProfileOrdinal = profile,
                processLeaseActive = false,
            ),
        )
    }

    @Test
    fun warmupReadinessCannotSucceedAfterItsDeadline() {
        assertFalse(
            warmupReadyWindowOpen(
                nowMs = 1_199L,
                minimumReadyMs = 1_200L,
                deadlineMs = 5_000L,
            ),
        )
        assertTrue(
            warmupReadyWindowOpen(
                nowMs = 1_200L,
                minimumReadyMs = 1_200L,
                deadlineMs = 5_000L,
            ),
        )
        assertTrue(
            warmupReadyWindowOpen(
                nowMs = 5_000L,
                minimumReadyMs = 1_200L,
                deadlineMs = 5_000L,
            ),
        )
        assertFalse(
            warmupReadyWindowOpen(
                nowMs = 5_001L,
                minimumReadyMs = 1_200L,
                deadlineMs = 5_000L,
            ),
        )
    }

    @Test
    fun activeLayerSizeProfileArmsOnceAndStaysPinnedAcrossCyclicValleys() {
        val origin = checkNotNull(ScenarioCatalog.byId("baseline-display-modes"))
            .phases.first()
            .copy(layerSizeProfile = LayerSizeProfile.FULL_SCREEN)
        val target = origin.copy(
            id = "dynamic-target",
            layerSizeProfile = LayerSizeProfile.ABRUPT_SMALL_FULL,
        )
        val valley = LoadTransitionEvaluator.interpolate(
            previous = origin,
            target = target,
            fraction = 0f,
        )
        val peak = LoadTransitionEvaluator.interpolate(
            previous = origin,
            target = target,
            fraction = 1f,
        )

        assertEquals(
            LayerSizeProfile.FULL_SCREEN,
            layerSizeProfileForActiveTransition(
                interpolated = valley,
                target = target,
                transitionStarted = false,
            ).layerSizeProfile,
        )
        listOf(valley, peak, valley).forEach { cyclicSample ->
            assertEquals(
                LayerSizeProfile.ABRUPT_SMALL_FULL,
                layerSizeProfileForActiveTransition(
                    interpolated = cyclicSample,
                    target = target,
                    transitionStarted = true,
                ).layerSizeProfile,
            )
        }
    }

    @Test
    fun producerRecoveryDiscardsPendingDynamicGeometryBeforePreparationCanBlockRepublish() {
        val dynamicProfile = LayerSizeProfile.ABRUPT_SMALL_FULL
        val pending = PendingControlCoverage(
            sample = TransitionSample(0.5f, TransitionSegment.HOLD),
            phaseElapsedMs = 6_000L,
            expectedProducerCount = 8,
            expectedTopologyRevision = 3L,
            expectedLayerSizeProfileOrdinal = dynamicProfile.ordinal,
        )
        val acknowledgedTarget =
            com.example.dpulayerlab.monitor.ProducerReadiness(
                expectedCount = 8,
                ready = true,
                topologyRevision = 3L,
                geometryRequestedRevision = 9L,
                geometryAppliedRevision = 9L,
                geometryRequestedProfileOrdinal = dynamicProfile.ordinal,
                geometryAppliedProfileOrdinal = dynamicProfile.ordinal,
                geometryReady = true,
            )
        val recoveryPreparation = acknowledgedTarget.copy(
            geometryRequestedRevision = 10L,
            geometryAppliedRevision = 10L,
            geometryRequestedProfileOrdinal = LayerSizeProfile.SMALL_UNIFORM.ordinal,
            geometryAppliedProfileOrdinal = LayerSizeProfile.SMALL_UNIFORM.ordinal,
        )

        assertTrue(pending.isAcknowledgedBy(acknowledgedTarget))
        assertFalse(pending.isAcknowledgedBy(recoveryPreparation))
        assertNull(discardPendingControlCoverageForProducerRecovery(pending))
    }

    @Test
    fun endpointPendingCoverageRequiresExactAllProducerControlRevision() {
        val profile = LayerSizeProfile.FULL_SCREEN
        val pending = PendingControlCoverage(
            sample = TransitionSample(1f, TransitionSegment.HOLD),
            phaseElapsedMs = 1_000L,
            expectedProducerCount = 2,
            expectedTopologyRevision = 4L,
            expectedLayerSizeProfileOrdinal = profile.ordinal,
            expectedProducerControlRevision = 9L,
        )
        val base = com.example.dpulayerlab.monitor.ProducerReadiness(
            expectedCount = 2,
            ready = true,
            topologyRevision = 4L,
            geometryRequestedRevision = 5L,
            geometryAppliedRevision = 5L,
            geometryRequestedProfileOrdinal = profile.ordinal,
            geometryAppliedProfileOrdinal = profile.ordinal,
            geometryReady = true,
        )

        assertFalse(pending.isAcknowledgedBy(base))
        assertFalse(
            pending.isAcknowledgedBy(
                base.copy(
                    producerControlAppliedCount = 1,
                    producerControlAppliedRevision = 9L,
                    producerControlReady = false,
                ),
            ),
        )
        assertFalse(
            pending.isAcknowledgedBy(
                base.copy(
                    producerControlAppliedCount = 2,
                    producerControlAppliedRevision = 8L,
                    producerControlReady = true,
                ),
            ),
        )
        assertTrue(
            pending.isAcknowledgedBy(
                base.copy(
                    producerControlAppliedCount = 2,
                    producerControlAppliedRevision = 9L,
                    producerControlReady = true,
                ),
            ),
        )
    }

    @Test
    fun layerGeometryCoverageFailsClosedOnPendingWrongProfileOrMissingSteps() {
        val profile = LayerSizeProfile.GRADUAL_SMALL_TO_FULL
        val complete = com.example.dpulayerlab.monitor.ProducerReadiness(
            expectedCount = 4,
            observedCount = 4,
            ready = true,
            topologyPublished = true,
            geometryRequestedRevision = 4L,
            geometryAppliedRevision = 4L,
            geometryRequestedProfileOrdinal = profile.ordinal,
            geometryAppliedProfileOrdinal = profile.ordinal,
            geometryCoverageMask = profile.requiredCoverageMask(),
            geometryReady = true,
        )

        assertTrue(layerGeometryCoverageSatisfied(complete, profile))
        assertFalse(
            layerGeometryCoverageSatisfied(
                complete.copy(
                    geometryAppliedRevision = 3L,
                    geometryReady = false,
                ),
                profile,
            ),
        )
        assertFalse(
            layerGeometryCoverageSatisfied(
                complete.copy(
                    geometryAppliedProfileOrdinal = LayerSizeProfile.SMALL_UNIFORM.ordinal,
                ),
                profile,
            ),
        )
        assertFalse(
            layerGeometryCoverageSatisfied(
                complete.copy(topologyPending = true),
                profile,
            ),
        )
        assertFalse(
            layerGeometryCoverageSatisfied(
                complete.copy(observedCount = complete.expectedCount - 1),
                profile,
            ),
        )
        assertFalse(
            layerGeometryCoverageSatisfied(
                complete.copy(ready = false),
                profile,
            ),
        )
        assertFalse(
            layerGeometryCoverageSatisfied(
                complete.copy(topologyMissed = true),
                profile,
            ),
        )
        assertFalse(
            layerGeometryCoverageSatisfied(
                complete.copy(teardownFailed = true),
                profile,
            ),
        )
        assertFalse(
            layerGeometryCoverageSatisfied(
                complete.copy(teardownCompleted = true),
                profile,
            ),
        )
        assertFalse(
            layerGeometryCoverageSatisfied(
                complete.copy(geometryCoverageMask = 0b011),
                profile,
            ),
        )
    }

    @Test
    fun rendererPreparationFreezesDynamicLayerGeometryAtSmallOrigin() {
        listOf(
            LayerSizeProfile.GRADUAL_SMALL_TO_FULL,
            LayerSizeProfile.ABRUPT_SMALL_FULL,
        ).forEach { dynamicProfile ->
            val dynamic = ScenarioCatalog.presets.first().phases.first().copy(
                id = "dynamic",
                layerSizeProfile = dynamicProfile,
                motion = MotionProfile.TRANSFORM_STORM,
            )

            assertEquals(
                LayerSizeProfile.SMALL_UNIFORM,
                preparationLayerSizeProfile(dynamicProfile),
            )
            assertEquals(
                LayerSizeProfile.SMALL_UNIFORM,
                rendererPreparationPhase(dynamic).layerSizeProfile,
            )
        }
        listOf(
            LayerSizeProfile.FULL_SCREEN,
            LayerSizeProfile.SMALL_UNIFORM,
            LayerSizeProfile.MIXED_SIZES,
        ).forEach { staticProfile ->
            assertEquals(staticProfile, preparationLayerSizeProfile(staticProfile))
        }
    }

    @Test
    fun controllerPauseAlwaysDetachesActiveRenderStage() {
        val scenario = checkNotNull(ScenarioCatalog.byId("instant-burst-transitions"))
        val phase = scenario.phases.first()
        val active = RunProgress(
            scenario = scenario,
            phase = phase,
            targetPhase = phase,
            transitionFraction = 1f,
        )

        val paused = progressForControllerPause(active, activeRun = true)

        assertNull(paused.phase)
        assertNull(paused.targetPhase)
        assertEquals(0f, paused.transitionFraction, 0f)
        assertTrue(progressForControllerPause(active, activeRun = false) === active)

        val stopped = progressForImmediateStop(active, "external stop")
        assertNull(stopped.phase)
        assertNull(stopped.targetPhase)
        assertEquals("external stop", stopped.statusText)
    }

    @Test
    fun normalCooldownDetachesLastProducerWithoutChangingItsTrackedGeneration() {
        val scenario = checkNotNull(ScenarioCatalog.byId("instant-burst-transitions"))
        val phase = scenario.phases.first().copy(
            pixelRoute = PixelRoute.SBWC_AUTO,
            includeGlLayer = true,
        )
        val active = RunProgress(
            stage = RunnerStage.RUNNING,
            scenario = scenario,
            phase = phase,
            targetPhase = phase,
            transitionFraction = 1f,
            producerGeneration = 42L,
        )

        val cooldown = progressForCooldownTeardown(active)

        assertEquals(RunnerStage.COOLDOWN, cooldown.stage)
        assertNull(cooldown.phase)
        assertNull(cooldown.targetPhase)
        assertEquals(0f, cooldown.transitionFraction, 0f)
        assertEquals(42L, cooldown.producerGeneration)
        assertTrue(cooldown.statusText.contains("physical producer"))
    }

    @Test
    fun memoryWorkloadFailureIsFailClosedEvenWithoutSystemLowMemory() {
        assertEquals(
            MemorySafetyFailure.WORKLOAD_ALLOCATION,
            memorySafetyFailure(
                workloadAllocationFailed = true,
                systemLowMemory = false,
            ),
        )
        assertEquals(
            MemorySafetyFailure.WORKLOAD_ALLOCATION,
            memorySafetyFailure(
                workloadAllocationFailed = true,
                systemLowMemory = true,
            ),
        )
        assertEquals(
            MemorySafetyFailure.SYSTEM_LOW_MEMORY,
            memorySafetyFailure(
                workloadAllocationFailed = false,
                systemLowMemory = true,
            ),
        )
        assertNull(
            memorySafetyFailure(
                workloadAllocationFailed = false,
                systemLowMemory = false,
            ),
        )
    }

    @Test
    fun onlyScenariosWithFinitePositiveMemoryLoadNeedPrewarm() {
        val base = checkNotNull(ScenarioCatalog.byId("baseline-display-modes"))
        assertTrue(!scenarioNeedsMemoryPrewarm(base))

        val memoryPhase = base.phases.first().copy(
            workloads = LoadSetpoints(memory = 0.4f),
        )
        assertTrue(scenarioNeedsMemoryPrewarm(base.copy(phases = listOf(memoryPhase))))
        assertTrue(
            !scenarioNeedsMemoryPrewarm(
                base.copy(
                    phases = listOf(
                        memoryPhase.copy(
                            workloads = LoadSetpoints(memory = Float.NaN),
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun cancelledJobStillBlocksStartUntilItsFinalizerClearsOwnership() {
        assertTrue(planStartBlocked(runJobPresent = true, isRunning = false))
        assertTrue(planStartBlocked(runJobPresent = false, isRunning = true))
        assertFalse(planStartBlocked(runJobPresent = false, isRunning = false))
    }

    @Test
    fun completedJobClearsOnlyItsOwnPublishedOwnership() {
        val finishingOwner = Any()
        val replacementOwner = Any()

        assertTrue(publishedJobOwnerMatches(finishingOwner, finishingOwner))
        assertFalse(publishedJobOwnerMatches(replacementOwner, finishingOwner))
        assertFalse(publishedJobOwnerMatches(null, finishingOwner))
    }

    @Test
    fun rendererBarrierPollsTransientLeaseAndRequiresStageAck() {
        assertEquals(
            RendererTeardownBarrierDecision.WAIT,
            rendererTeardownBarrierDecision(
                generationPresent = true,
                callbackFailure = false,
                stageRemovalAcknowledged = true,
                processLeaseActive = true,
                deadlineReached = false,
            ),
        )
        assertEquals(
            RendererTeardownBarrierDecision.SUCCESS,
            rendererTeardownBarrierDecision(
                generationPresent = true,
                callbackFailure = false,
                stageRemovalAcknowledged = true,
                processLeaseActive = false,
                deadlineReached = false,
            ),
        )
        assertEquals(
            RendererTeardownBarrierDecision.WAIT,
            rendererTeardownBarrierDecision(
                generationPresent = true,
                callbackFailure = false,
                stageRemovalAcknowledged = false,
                processLeaseActive = false,
                deadlineReached = false,
            ),
        )
        assertEquals(
            RendererTeardownBarrierDecision.FAIL,
            rendererTeardownBarrierDecision(
                generationPresent = true,
                callbackFailure = false,
                stageRemovalAcknowledged = false,
                processLeaseActive = false,
                deadlineReached = true,
            ),
        )
        assertEquals(
            RendererTeardownBarrierDecision.FAIL,
            rendererTeardownBarrierDecision(
                generationPresent = true,
                callbackFailure = true,
                stageRemovalAcknowledged = true,
                processLeaseActive = false,
                deadlineReached = false,
            ),
        )
        assertEquals(
            RendererTeardownBarrierDecision.WAIT,
            rendererTeardownBarrierDecision(
                generationPresent = false,
                callbackFailure = false,
                stageRemovalAcknowledged = false,
                processLeaseActive = true,
                deadlineReached = false,
            ),
        )
        assertEquals(
            RendererTeardownBarrierDecision.SUCCESS,
            rendererTeardownBarrierDecision(
                generationPresent = false,
                callbackFailure = false,
                stageRemovalAcknowledged = false,
                processLeaseActive = false,
                deadlineReached = false,
            ),
        )
    }

    @Test
    fun anyPinnedMediaCleanupUncertaintyOverridesSuccessfulMasterCloses() {
        assertTrue(
            mediaDescriptorCleanupConfirmed(
                selectedDescriptorReleased = true,
                pendingDescriptorReleased = true,
                processCleanupUnconfirmed = false,
            ),
        )
        assertFalse(
            mediaDescriptorCleanupConfirmed(
                selectedDescriptorReleased = true,
                pendingDescriptorReleased = true,
                processCleanupUnconfirmed = true,
            ),
        )
        assertFalse(
            mediaDescriptorCleanupConfirmed(
                selectedDescriptorReleased = false,
                pendingDescriptorReleased = true,
                processCleanupUnconfirmed = false,
            ),
        )
    }

    @Test
    fun adaptiveBoundaryIncludesSetupAndTailCounterIncrements() {
        val before = TelemetrySnapshot(
            exactUnderruns = 10L,
            exactUnderrunSource = "vendor/dpu0",
            exactUnderrunQuality = MetricQuality.KERNEL,
            suspectedUnderruns = 20L,
        )
        // An activation-only baseline of exact=11/proxy=21 would see only the tail +1.
        val after = before.copy(
            exactUnderruns = 12L,
            suspectedUnderruns = 22L,
        )

        assertEquals(
            AdaptiveBoundaryEvidence(exactDelta = 2L, proxyDelta = 2L),
            adaptiveBoundaryEvidence(
                before = before,
                after = after,
                exactContinuousBefore = true,
                exactContinuousAfter = true,
            ),
        )
    }

    @Test
    fun adaptiveBoundaryRejectsExactResetOrProvenanceChange() {
        val before = TelemetrySnapshot(
            exactUnderruns = 10L,
            exactUnderrunSource = "vendor/dpu0",
            exactUnderrunQuality = MetricQuality.KERNEL,
            suspectedUnderruns = 7L,
        )
        val reset = before.copy(exactUnderruns = 2L, suspectedUnderruns = 8L)
        assertEquals(
            AdaptiveBoundaryEvidence(exactDelta = null, proxyDelta = 1L),
            adaptiveBoundaryEvidence(before, reset, true, true),
        )

        val sourceChanged = before.copy(
            exactUnderruns = 12L,
            exactUnderrunSource = "vendor/dpu1",
        )
        assertNull(
            adaptiveBoundaryEvidence(before, sourceChanged, true, true).exactDelta,
        )
        assertNull(
            adaptiveBoundaryEvidence(before, before.copy(exactUnderruns = 12L), true, false)
                .exactDelta,
        )
    }

    @Test
    fun decoderLinearReferenceRequiresVerifiedBitDepthProfile() {
        val avc = decoderLinearReferenceFor(
            mime = "video/avc",
            profile = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
        )
        assertEquals(1.5, avc.bytesPerPixel!!, 0.0)

        val hevc10 = decoderLinearReferenceFor(
            mime = "video/hevc",
            profile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
        )
        assertEquals(3.0, hevc10.bytesPerPixel!!, 0.0)

        val vp9TenBit = decoderLinearReferenceFor(
            mime = "video/x-vnd.on2.vp9",
            profile = MediaCodecInfo.CodecProfileLevel.VP9Profile2,
            codecsString = "vp09.02.51.10.01.09.16.09.01",
        )
        assertEquals(3.0, vp9TenBit.bytesPerPixel!!, 0.0)
        assertNull(
            decoderLinearReferenceFor(
                mime = "video/x-vnd.on2.vp9",
                profile = MediaCodecInfo.CodecProfileLevel.VP9Profile2,
                codecsString = "vp09.02.51.12.01.09.16.09.01",
            ).bytesPerPixel,
        )
        assertNull(
            decoderLinearReferenceFor(
                mime = "video/x-vnd.on2.vp9",
                profile = MediaCodecInfo.CodecProfileLevel.VP9Profile2,
            ).bytesPerPixel,
        )

        assertNull(decoderLinearReferenceFor("video/hevc", null).bytesPerPixel)
        assertEquals(
            3.0,
            decoderLinearReferenceFor(
                mime = "video/avc",
                profile = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10,
            ).bytesPerPixel!!,
            0.0,
        )
        assertNull(
            decoderLinearReferenceFor(
                mime = "video/dolby-vision",
                profile = 1,
            ).bytesPerPixel,
        )
    }

    @Test
    fun exactUnderrunEvidenceTakesPriorityOverFrameDeadlineProxy() {
        assertEquals(RunVerdict.CLEAN, underrunVerdict(exactDelta = 0L, suspectedProxyDelta = 7L))
        assertEquals(
            RunVerdict.UNDERRUN_DETECTED,
            underrunVerdict(exactDelta = 2L, suspectedProxyDelta = 0L),
        )
        assertEquals(
            RunVerdict.SUSPECTED_PROXY,
            underrunVerdict(exactDelta = null, suspectedProxyDelta = 3L),
        )
        assertEquals(
            RunVerdict.INCONCLUSIVE,
            underrunVerdict(exactDelta = null, suspectedProxyDelta = 0L),
        )
        assertEquals(
            RunVerdict.INCONCLUSIVE,
            underrunVerdict(exactDelta = -1L, suspectedProxyDelta = 3L),
        )
    }

    @Test
    fun normalVerdictRequiresPostTeardownSampleAndCancellationCannotReportClean() {
        assertTrue(
            shouldCollectFinalTelemetrySample(
                preselectedVerdict = null,
                rendererReleased = true,
            ),
        )
        assertFalse(
            shouldCollectFinalTelemetrySample(
                preselectedVerdict = RunVerdict.ABORTED,
                rendererReleased = true,
            ),
        )
        assertFalse(
            shouldCollectFinalTelemetrySample(
                preselectedVerdict = null,
                rendererReleased = false,
            ),
        )
        assertEquals(
            RunVerdict.CLEAN,
            finalVerdictAfterTeardown(
                preselectedVerdict = null,
                rendererReleased = true,
                cancellationPresent = false,
                derivedVerdict = RunVerdict.CLEAN,
            ),
        )
        assertEquals(
            RunVerdict.ABORTED,
            finalVerdictAfterTeardown(
                preselectedVerdict = null,
                rendererReleased = true,
                cancellationPresent = true,
                derivedVerdict = RunVerdict.CLEAN,
            ),
        )
        assertEquals(
            RunVerdict.ABORTED,
            finalVerdictAfterTeardown(
                preselectedVerdict = null,
                rendererReleased = false,
                cancellationPresent = false,
                derivedVerdict = RunVerdict.CLEAN,
            ),
        )
    }

    @Test
    fun exactDeltaProvenanceIsUnavailableWithoutReliableDelta() {
        assertEquals(
            ExactDeltaProvenance(null, MetricQuality.UNAVAILABLE),
            exactDeltaProvenance(
                exactDelta = null,
                baselineSource = "/sys/dpu/underrun",
                baselineQuality = MetricQuality.KERNEL,
            ),
        )
        assertEquals(
            ExactDeltaProvenance("/sys/dpu/underrun", MetricQuality.KERNEL),
            exactDeltaProvenance(
                exactDelta = 0L,
                baselineSource = "/sys/dpu/underrun",
                baselineQuality = MetricQuality.KERNEL,
            ),
        )
        assertEquals(
            ExactDeltaProvenance(null, MetricQuality.UNAVAILABLE),
            exactDeltaProvenance(
                exactDelta = 1L,
                baselineSource = "",
                baselineQuality = MetricQuality.KERNEL,
            ),
        )
        assertEquals(
            ExactDeltaProvenance(null, MetricQuality.UNAVAILABLE),
            exactDeltaProvenance(
                exactDelta = -1L,
                baselineSource = "/sys/dpu/underrun",
                baselineQuality = MetricQuality.KERNEL,
            ),
        )
    }

    @Test
    fun gaugePeakRequiresStableValidProvenance() {
        val vendor = TelemetrySnapshot(
            dpuBusy = Gauge(
                value = 72f,
                unit = "%",
                quality = MetricQuality.HARDWARE_COUNTER,
                source = "vendor-session-1",
            ),
        )
        val sameVendor = vendor.copy(dpuBusy = vendor.dpuBusy.copy(value = 85f))
        assertEquals(
            GaugePeak(value = 85f, provenanceChanged = false),
            consistentGaugePeak(
                samples = listOf(vendor, sameVendor),
                selector = TelemetrySnapshot::dpuBusy,
                validRange = 0f..100f,
            ),
        )

        val kernel = vendor.copy(
            dpuBusy = Gauge(
                value = 90f,
                unit = "%",
                quality = MetricQuality.KERNEL,
                source = "/sys/dpu/busy",
            ),
        )
        assertEquals(
            GaugePeak(value = null, provenanceChanged = true),
            consistentGaugePeak(
                samples = listOf(vendor, kernel),
                selector = TelemetrySnapshot::dpuBusy,
                validRange = 0f..100f,
            ),
        )

        val malformed = vendor.copy(
            dpuBusy = Gauge(
                value = Float.NaN,
                unit = "%",
                quality = MetricQuality.HARDWARE_COUNTER,
                source = "vendor-session-1",
            ),
        )
        assertEquals(
            GaugePeak(value = 72f, provenanceChanged = false),
            consistentGaugePeak(
                samples = listOf(malformed, vendor),
                selector = TelemetrySnapshot::dpuBusy,
                validRange = 0f..100f,
            ),
        )
        assertEquals(
            GaugePeak(value = null, provenanceChanged = false),
            consistentGaugePeak(
                samples = listOf(vendor.copy(dpuBusy = vendor.dpuBusy.copy(value = 101f))),
                selector = TelemetrySnapshot::dpuBusy,
                validRange = 0f..100f,
            ),
        )
    }

    @Test
    fun decoderMediaFpsIsFailClosedWithToleranceAtBoundary() {
        assertFalse(
            selectedMediaFpsMeetsRequirement(
                sourceFps = null,
                requiredFps = 30f,
                toleranceFps = 0.5f,
            ),
        )
        assertTrue(
            selectedMediaFpsMeetsRequirement(
                sourceFps = 29.97f,
                requiredFps = 30f,
                toleranceFps = 0.5f,
            ),
        )
        assertFalse(
            selectedMediaFpsMeetsRequirement(
                sourceFps = 30f,
                requiredFps = 60f,
                toleranceFps = 0.5f,
            ),
        )
    }

    @Test
    fun decoderCapabilityUsesTheHigherOfSourceAndRequestedRates() {
        assertEquals(120f, decoderCapabilityRate(120f, 60f))
        assertEquals(60f, decoderCapabilityRate(59.94f, 60f))
        assertNull(decoderCapabilityRate(Float.NaN, 60f))
        assertNull(decoderCapabilityRate(60f, 0f))
    }

    @Test
    fun safetyPlanAbortCannotBeHandledAsGenericInconclusiveError() {
        assertTrue(
            CancellationException::class.java.isAssignableFrom(
                PlanAbortException::class.java,
            ),
        )
    }

    @Test
    fun cancelledRunAddsAtMostOneAbortedHistoryEntry() {
        assertTrue(
            shouldAppendAbortedPlanResult(
                activeRunIndex = 3,
                alreadyRecorded = false,
                verdict = RunVerdict.ABORTED,
            ),
        )
        assertTrue(
            !shouldAppendAbortedPlanResult(
                activeRunIndex = 3,
                alreadyRecorded = true,
                verdict = RunVerdict.ABORTED,
            ),
        )
        assertTrue(
            !shouldAppendAbortedPlanResult(
                activeRunIndex = -1,
                alreadyRecorded = false,
                verdict = RunVerdict.ABORTED,
            ),
        )
        assertTrue(
            !shouldAppendAbortedPlanResult(
                activeRunIndex = 3,
                alreadyRecorded = false,
                verdict = RunVerdict.INCONCLUSIVE,
            ),
        )
    }

    @Test
    fun onlyAbortedVerdictStopsThePlanQueue() {
        assertTrue(shouldContinuePlanAfter(RunVerdict.UNSUPPORTED))
        assertTrue(shouldContinuePlanAfter(RunVerdict.INCONCLUSIVE))
        assertTrue(shouldContinuePlanAfter(RunVerdict.CLEAN))
        assertTrue(!shouldContinuePlanAfter(RunVerdict.ABORTED))
    }

    @Test
    fun unexpectedExecutionExceptionAbortsTheRemainingPlanQueue() {
        val verdict = unexpectedExceptionVerdict()

        assertEquals(RunVerdict.ABORTED, verdict)
        assertTrue(!shouldContinuePlanAfter(verdict))
    }

    @Test
    fun abortedArtifactIsRecordedWithoutIncreasingCompletedRunCount() {
        val aborted = planArtifactProgressDecision(
            completedRuns = 2,
            verdict = RunVerdict.ABORTED,
        )
        val inconclusive = planArtifactProgressDecision(
            completedRuns = 2,
            verdict = RunVerdict.INCONCLUSIVE,
        )

        assertTrue(!aborted.shouldContinue)
        assertEquals(2, aborted.completedRuns)
        assertTrue(inconclusive.shouldContinue)
        assertEquals(3, inconclusive.completedRuns)
    }

    @Test
    fun expandedPlanPreservesQueueOrderAcrossRepeats() {
        val first = ScenarioCatalog.presets[0]
        val second = ScenarioCatalog.presets[1]
        val plan = ScenarioRunPlan(
            scenarios = listOf(first, second),
            repeatCount = 2,
        )

        assertEquals(
            listOf(first.id, second.id, first.id, second.id),
            (0 until plan.totalRuns).map {
                scenarioAtExpandedIndex(plan, it)?.id
            },
        )
        assertNull(scenarioAtExpandedIndex(plan, -1))
        assertNull(scenarioAtExpandedIndex(plan, plan.totalRuns))
    }

    @Test
    fun telemetryGenerationRejectsARequestIssuedBeforeTheNewRunBoundary() {
        val gate = TelemetrySampleGenerationGate()
        gate.beginRun()
        val firstRunSample = gate.issue()
        assertTrue(gate.belongsToCurrentRun(firstRunSample))

        // The request exists before reset but may still be blocked in Binder/sysfs IO.
        val inFlightPreviousRunSample = gate.issue()
        gate.beginRun()
        val freshCurrentRunSample = gate.issue()

        assertTrue(!gate.belongsToCurrentRun(inFlightPreviousRunSample))
        assertTrue(gate.belongsToCurrentRun(freshCurrentRunSample))
    }

    @Test
    fun telemetryWatchdogProtectsOnlyTheBoundedInFlightWindow() {
        val inFlightDeadline = telemetrySampleDeadlineMs(
            sampleStartedMs = 6_000L,
            sampleTimeoutMs = 4_000L,
            completionGraceMs = 500L,
        )
        assertEquals(10_500L, inFlightDeadline)

        // The last success is stale, but the accepted sample still owns its bounded deadline.
        assertFalse(
            shouldAbortTelemetryWatchdog(
                nowMs = 10_000L,
                lastSuccessfulSampleMs = 4_000L,
                staleTimeoutMs = 5_000L,
                inFlightDeadlineMs = inFlightDeadline,
            ),
        )
        assertFalse(
            shouldAbortTelemetryWatchdog(
                nowMs = inFlightDeadline,
                lastSuccessfulSampleMs = 4_000L,
                staleTimeoutMs = 5_000L,
                inFlightDeadlineMs = inFlightDeadline,
            ),
        )
        assertTrue(
            shouldAbortTelemetryWatchdog(
                nowMs = inFlightDeadline + 1L,
                lastSuccessfulSampleMs = 4_000L,
                staleTimeoutMs = 5_000L,
                inFlightDeadlineMs = inFlightDeadline,
            ),
        )
    }

    @Test
    fun telemetryWatchdogStillAbortsStaleMonitorWithoutAnInFlightOwner() {
        assertFalse(
            shouldAbortTelemetryWatchdog(
                nowMs = 6_000L,
                lastSuccessfulSampleMs = 1_000L,
                staleTimeoutMs = 5_000L,
                inFlightDeadlineMs = null,
            ),
        )
        assertTrue(
            shouldAbortTelemetryWatchdog(
                nowMs = 6_001L,
                lastSuccessfulSampleMs = 1_000L,
                staleTimeoutMs = 5_000L,
                inFlightDeadlineMs = null,
            ),
        )
        assertEquals(
            Long.MAX_VALUE,
            telemetrySampleDeadlineMs(
                sampleStartedMs = Long.MAX_VALUE - 1L,
                sampleTimeoutMs = 4_000L,
                completionGraceMs = 500L,
            ),
        )
    }

    @Test
    fun telemetryWatchdogPauseAndResumeGraceDoNotFabricateASuccessTimestamp() {
        assertFalse(
            shouldAbortTelemetryWatchdog(
                nowMs = 20_000L,
                lastSuccessfulSampleMs = 1_000L,
                staleTimeoutMs = 5_000L,
                inFlightDeadlineMs = null,
                intentionallyPaused = true,
                resumeGraceDeadlineMs = null,
            ),
        )
        assertFalse(
            shouldAbortTelemetryWatchdog(
                nowMs = 25_000L,
                lastSuccessfulSampleMs = 1_000L,
                staleTimeoutMs = 5_000L,
                inFlightDeadlineMs = null,
                intentionallyPaused = false,
                resumeGraceDeadlineMs = 25_000L,
            ),
        )
        assertTrue(
            shouldAbortTelemetryWatchdog(
                nowMs = 25_001L,
                lastSuccessfulSampleMs = 1_000L,
                staleTimeoutMs = 5_000L,
                inFlightDeadlineMs = null,
                intentionallyPaused = false,
                resumeGraceDeadlineMs = 25_000L,
            ),
        )
    }

    @Test
    fun fatalTelemetryStartupFailuresAreRethrownAfterRollback() {
        assertTrue(isFatalTelemetryStartupFailure(OutOfMemoryError("oom")))
        assertTrue(isFatalTelemetryStartupFailure(ThreadDeath()))
        assertTrue(isFatalTelemetryStartupFailure(AssertionError("fatal")))
        assertTrue(isFatalControllerStartupFailure(OutOfMemoryError("oom")))
        assertTrue(
            shouldRethrowRuntimeWorkloadApplyFailure(
                CancellationException("STOP"),
            ),
        )
        assertTrue(
            shouldRethrowRuntimeWorkloadApplyFailure(
                OutOfMemoryError("thermal apply"),
            ),
        )
        assertFalse(
            shouldRethrowRuntimeWorkloadApplyFailure(
                IllegalStateException("recoverable adapter failure"),
            ),
        )
        assertFalse(
            isFatalTelemetryStartupFailure(
                IllegalStateException("recoverable startup failure"),
            ),
        )
        assertFalse(
            isFatalControllerStartupFailure(
                IllegalStateException("recoverable startup failure"),
            ),
        )
    }

    @Test
    fun controllerCleanupFailureMergePromotesFatalAndPreservesIdentity() {
        val ordinary = IllegalStateException("ordinary cleanup")
        val fatal = OutOfMemoryError("fatal cleanup")

        val terminal = mergeControllerFailurePreservingFatal(ordinary, fatal)

        assertSame(fatal, terminal)
        assertTrue(fatal.suppressed.any { it === ordinary })
        val later = IllegalArgumentException("later cleanup")
        assertSame(fatal, mergeControllerFailurePreservingFatal(terminal, later))
        assertTrue(fatal.suppressed.any { it === later })
    }

    @Test
    fun boundedControllerWorkerRelaysExactFatalToOwningCoroutine() {
        val fatal = ThreadDeath()
        var thrown: Throwable? = null

        try {
            controllerWorkerFailureOrThrow(fatal)
        } catch (error: Throwable) {
            thrown = error
        }

        assertSame(fatal, thrown)
        val ordinary = IllegalStateException("worker")
        assertSame(ordinary, controllerWorkerFailureOrThrow(ordinary))
        assertNull(controllerWorkerFailureOrThrow(null))
    }

    @Test
    fun unexpectedJobCompletionDistinguishesCancellationFromBackendFailure() {
        assertNull(unexpectedJobCompletionReason("plan", null))
        assertNull(
            unexpectedJobCompletionReason(
                "plan",
                CancellationException("expected stop"),
            ),
        )
        assertEquals(
            "performance renewal Job failed unexpectedly: OutOfMemoryError",
            unexpectedJobCompletionReason(
                "performance renewal",
                OutOfMemoryError("oom"),
            ),
        )
    }

    @Test
    fun telemetryPairNeverReusesOrOverwritesOnlyOneLiveWorker() {
        assertEquals(
            TelemetryPairStartDecision.START_NEW_PAIR,
            telemetryPairStartDecision(
                monitorPresent = false,
                monitorActive = false,
                watchdogPresent = false,
                watchdogActive = false,
                lifecycleIntegrityConfirmed = true,
            ),
        )
        assertEquals(
            TelemetryPairStartDecision.REUSE_ACTIVE_PAIR,
            telemetryPairStartDecision(
                monitorPresent = true,
                monitorActive = true,
                watchdogPresent = true,
                watchdogActive = true,
                lifecycleIntegrityConfirmed = true,
            ),
        )
        assertEquals(
            TelemetryPairStartDecision.WAIT_FOR_TERMINATION,
            telemetryPairStartDecision(
                monitorPresent = true,
                monitorActive = true,
                watchdogPresent = false,
                watchdogActive = false,
                lifecycleIntegrityConfirmed = true,
            ),
        )
        assertEquals(
            TelemetryPairStartDecision.WAIT_FOR_TERMINATION,
            telemetryPairStartDecision(
                monitorPresent = true,
                monitorActive = false,
                watchdogPresent = true,
                watchdogActive = false,
                lifecycleIntegrityConfirmed = true,
            ),
        )
        assertEquals(
            TelemetryPairStartDecision.REJECT_UNTRUSTED_LIFECYCLE,
            telemetryPairStartDecision(
                monitorPresent = true,
                monitorActive = true,
                watchdogPresent = true,
                watchdogActive = true,
                lifecycleIntegrityConfirmed = false,
            ),
        )
    }

    @Test
    fun longLivedTelemetryWorkersFailClosedOnUnownedExit() {
        assertNull(
            unexpectedLongLivedWorkerCompletionReason(
                operation = "monitor",
                cause = CancellationException("pause"),
                expectedStop = true,
            ),
        )
        assertEquals(
            "watchdog Job was cancelled without an owning stop request",
            unexpectedLongLivedWorkerCompletionReason(
                operation = "watchdog",
                cause = CancellationException("external"),
                expectedStop = false,
            ),
        )
        assertEquals(
            "monitor Job exited while continuous monitoring was required",
            unexpectedLongLivedWorkerCompletionReason(
                operation = "monitor",
                cause = null,
                expectedStop = false,
            ),
        )
        assertEquals(
            "watchdog Job failed unexpectedly: OutOfMemoryError",
            unexpectedLongLivedWorkerCompletionReason(
                operation = "watchdog",
                cause = OutOfMemoryError("oom"),
                expectedStop = true,
            ),
        )
    }

    @Test
    fun renewalFailureOnlyAbortsTheMatchingActiveRunAndNeverAStaleOwner() {
        assertTrue(
            shouldFailPerformanceIsolationAfterRenewalCompletion(
                operationFailure = "renew worker failed",
                runOwnerMatches = true,
                runOwnerActive = true,
                isolationMonitoringExpected = true,
            ),
        )
        assertFalse(
            shouldFailPerformanceIsolationAfterRenewalCompletion(
                operationFailure = "stale renew worker failed",
                runOwnerMatches = false,
                runOwnerActive = true,
                isolationMonitoringExpected = true,
            ),
        )
        assertFalse(
            shouldFailPerformanceIsolationAfterRenewalCompletion(
                operationFailure = null,
                runOwnerMatches = true,
                runOwnerActive = true,
                isolationMonitoringExpected = true,
            ),
        )
    }

    @Test
    fun firstCancellationReasonPreservesSafetyButRecordsAStandaloneUserStop() {
        val thermalReason = "thermal CRITICAL 안전 중단"
        assertEquals(
            thermalReason,
            preserveFirstCancellationReason(
                current = thermalReason,
                requested = "앱이 백그라운드로 전환되어 안전 중단",
                fallback = "사용자가 중단함",
                maxChars = 1_000,
            ),
        )
        assertEquals(
            "사용자가 중단함",
            preserveFirstCancellationReason(
                current = null,
                requested = "  사용자가 중단함  ",
                fallback = "fallback",
                maxChars = 1_000,
            ),
        )
    }

    @Test
    fun compressedSampleLimitTracksDeviceBudgetAndAbsoluteCeiling() {
        assertNull(compressedSampleSafetyLimit(0L))
        assertEquals(
            6 * 1024 * 1024,
            compressedSampleSafetyLimit(48L * 1024L * 1024L),
        )
        assertEquals(
            32 * 1024 * 1024,
            compressedSampleSafetyLimit(1024L * 1024L * 1024L),
        )
        assertEquals(1, compressedSampleSafetyLimit(8L))
        assertNull(compressedSampleSafetyLimit(7L))
    }

    @Test
    fun managedReportResolverRejectsTraversalForeignAndMissingFiles() {
        val root = Files.createTempDirectory("dpu-report-test").toFile()
        try {
            val reports = File(root, "reports").apply { mkdirs() }
            val managed = File(
                reports,
                "dpu-layer-lab-20260724-111816-123-scenario.json",
            ).apply { writeText("{}") }
            val foreign = File(reports, "foreign.json").apply { writeText("{}") }
            val outside = File(root, managed.name).apply { writeText("{}") }

            assertEquals(managed.canonicalFile, resolveManagedReportFile(reports, managed.path))
            assertNull(resolveManagedReportFile(reports, foreign.path))
            assertNull(resolveManagedReportFile(reports, outside.path))
            assertNull(
                resolveManagedReportFile(
                    reports,
                    File(
                        reports,
                        "dpu-layer-lab-20260724-111816-123-missing.json",
                    ).path,
                ),
            )
            assertNull(resolveManagedReportFile(reports, managed.name))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun obsoleteReportCleanupDeletesOnlyManagedCompletedArtifact() {
        val root = Files.createTempDirectory("dpu-report-cleanup-test").toFile()
        try {
            val reports = File(root, "reports").apply { mkdirs() }
            val managed = File(
                reports,
                "dpu-layer-lab-20260724-111816-124-scenario.json",
            ).apply { writeText("{}") }
            val foreignInReports = File(reports, "foreign.json").apply { writeText("{}") }
            val outside = File(root, managed.name).apply { writeText("{}") }
            val resolvedManaged = checkNotNull(
                resolveManagedReportFile(reports, managed.absolutePath),
            )
            assertTrue(
                isPublishedReportForSharing(
                    reportFile = resolvedManaged,
                    lastReportFile = managed,
                    planResultPaths = emptySequence(),
                ),
            )
            assertTrue(
                isPublishedReportForSharing(
                    reportFile = resolvedManaged,
                    lastReportFile = null,
                    planResultPaths = sequenceOf(managed.absolutePath),
                ),
            )
            assertFalse(
                isPublishedReportForSharing(
                    reportFile = resolvedManaged,
                    lastReportFile = null,
                    planResultPaths = emptySequence(),
                ),
            )

            assertFalse(
                deleteManagedCompletedReportBestEffort(
                    reportsDirectory = reports,
                    reportFile = foreignInReports,
                ),
            )
            assertTrue(foreignInReports.isFile)
            assertFalse(
                deleteManagedCompletedReportBestEffort(
                    reportsDirectory = reports,
                    reportFile = outside,
                ),
            )
            assertTrue(outside.isFile)

            assertTrue(
                deleteManagedCompletedReportBestEffort(
                    reportsDirectory = reports,
                    reportFile = managed,
                ),
            )
            assertFalse(managed.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun planRestoreFailureWithdrawsEveryEarlierReportButKeepsCurrentForRewrite() {
        val first = ScenarioCatalog.presets[0]
        val current = ScenarioCatalog.presets[1]
        val results = listOf(
            PlanRunResult(
                runIndex = 0,
                repeatIndex = 0,
                queueIndex = 0,
                scenario = first,
                verdict = RunVerdict.CLEAN,
                startedEpochMs = 100L,
                finishedEpochMs = 200L,
                exactUnderrunDelta = 0L,
                suspectedUnderrunDelta = 0L,
                reportPath = "/reports/first.json",
            ),
            PlanRunResult(
                runIndex = 1,
                repeatIndex = 0,
                queueIndex = 1,
                scenario = current,
                verdict = RunVerdict.CLEAN,
                startedEpochMs = 300L,
                finishedEpochMs = 400L,
                exactUnderrunDelta = 0L,
                suspectedUnderrunDelta = 0L,
                reportPath = "/reports/current.json",
            ),
        )

        val invalidation = invalidateEarlierPlanResultsForRestoreFailure(
            results = results,
            currentStartedEpochMs = 300L,
            currentScenarioId = current.id,
            terminalReason = "restore failed",
        )

        assertEquals(listOf("/reports/first.json"), invalidation.invalidatedReportPaths)
        assertEquals(RunVerdict.ABORTED, invalidation.updatedResults[0].verdict)
        assertNull(invalidation.updatedResults[0].reportPath)
        assertEquals("restore failed", invalidation.updatedResults[0].terminalReason)
        assertEquals(results[1], invalidation.updatedResults[1])
    }

    @Test
    fun missingCurrentResultFailsClosedByWithdrawingAllPublishedReports() {
        val scenario = ScenarioCatalog.presets[0]
        val result = PlanRunResult(
            runIndex = 0,
            repeatIndex = 0,
            queueIndex = 0,
            scenario = scenario,
            verdict = RunVerdict.CLEAN,
            startedEpochMs = 100L,
            finishedEpochMs = 200L,
            exactUnderrunDelta = 0L,
            suspectedUnderrunDelta = 0L,
            reportPath = "/reports/only.json",
        )

        val invalidation = invalidateEarlierPlanResultsForRestoreFailure(
            results = listOf(result),
            currentStartedEpochMs = 999L,
            currentScenarioId = "missing",
            terminalReason = "restore failed",
        )

        assertEquals(listOf("/reports/only.json"), invalidation.invalidatedReportPaths)
        assertEquals(RunVerdict.ABORTED, invalidation.updatedResults.single().verdict)
        assertNull(invalidation.updatedResults.single().reportPath)
    }

    @Test
    fun restoreReportPublicationFailureCannotBeRetriedAsClean() {
        val summary = RunSummary(
            scenario = ScenarioCatalog.presets.first(),
            startedEpochMs = 100L,
            finishedEpochMs = 200L,
            verdict = RunVerdict.CLEAN,
            exactUnderrunDelta = 0L,
            exactUnderrunSource = "test",
            exactUnderrunQuality = MetricQuality.HARDWARE_COUNTER,
            suspectedUnderrunDelta = 0L,
            peakCpu = null,
            peakMemoryUsed = null,
            peakGeneratedBandwidth = null,
            events = listOf(
                RunEvent(
                    monotonicMs = 50L,
                    type = PERFORMANCE_RESTORE_CONFIRMED_EVENT,
                    message = "restore confirmed",
                ),
            ),
            samples = emptyList(),
        )

        val failed = markPerformanceRestoreReportPublicationFailed(
            summary = summary,
            finishedEpochMs = 300L,
            monotonicMs = 75L,
            failureType = "IOException",
        )
        val retried = markPerformanceRestoreReportPublicationFailed(
            summary = failed,
            finishedEpochMs = 400L,
            monotonicMs = 90L,
            failureType = "IOException",
        )

        assertEquals(RunVerdict.ABORTED, failed.verdict)
        assertEquals(
            1,
            retried.events.count {
                it.type == PERFORMANCE_RESTORE_REPORT_WRITE_FAILED_EVENT
            },
        )
        assertEquals(
            "Performance restore outcome report publication failed: IOException",
            retried.terminalReason(),
        )
    }

    @Test
    fun decoderSourceRateIncludesReachableNonDecoderBoundaryRate() {
        val rgb120 = decoderRatePhase(
            id = "rgb-120",
            route = PixelRoute.RGB_8888,
            fps = 120f,
        )
        val yuv30Ramp = decoderRatePhase(
            id = "yuv-30-ramp",
            route = PixelRoute.YUV_420,
            fps = 30f,
            transition = TransitionSpec(
                mode = TransitionMode.LINEAR_RAMP,
                transitionDurationMs = 1_000L,
            ),
        )
        val yuv30Step = yuv30Ramp.copy(
            id = "yuv-30-step",
            transition = TransitionSpec(mode = TransitionMode.STEP),
        )

        assertEquals(120f, requiredDecoderSourceFps(listOf(rgb120, yuv30Ramp)))
        assertEquals(60f, requiredDecoderSourceFps(listOf(rgb120, yuv30Step)))
        assertEquals(
            60f,
            requiredDecoderSourceFps(
                listOf(
                    decoderRatePhase("yuv-30", PixelRoute.YUV_420, 30f),
                    decoderRatePhase("p010-60", PixelRoute.P010, 60f),
                ),
            ),
        )
        assertNull(requiredDecoderSourceFps(listOf(rgb120)))
    }

    @Test
    fun transitionCoverageRejectsSkippedStaircaseLevelWithoutCatchUpLoop() {
        val complete = TransitionCoverageTracker(
            TransitionSpec(mode = TransitionMode.STAIRCASE, stepCount = 4),
        )
        listOf(0f, 1f / 3f, 2f / 3f, 1f).forEach { fraction ->
            complete.observe(
                TransitionSample(fraction, TransitionSegment.STEP_UP),
            )
        }
        assertNull(complete.failureReason())

        val skipped = TransitionCoverageTracker(
            TransitionSpec(mode = TransitionMode.STAIRCASE, stepCount = 4),
        )
        listOf(0f, 2f / 3f, 1f).forEach { fraction ->
            skipped.observe(
                TransitionSample(fraction, TransitionSegment.STEP_UP),
            )
        }
        assertTrue(skipped.failureReason()!!.contains("1"))
    }

    @Test
    fun wholePhaseLinearRampPublishesOneTerminalEndpointWithBoundedAcknowledgment() {
        val nowMs = AtomicLong(1_000L)
        val spec = TransitionSpec(
            mode = TransitionMode.LINEAR_RAMP,
            transitionDurationMs = 0L,
        )
        val gate = TerminalLinearRampEndpointGate(
            acknowledgmentTimeoutMs = 3_000L,
            monotonicNowMs = nowMs::get,
        )

        assertFalse(
            gate.shouldPublish(
                spec = spec,
                phaseElapsedMs = 199L,
                phaseDurationMs = 200L,
            ),
        )
        assertTrue(
            gate.shouldPublish(
                spec = spec,
                phaseElapsedMs = 200L,
                phaseDurationMs = 200L,
            ),
        )
        val endpoint = LoadTransitionEvaluator.sampleAt(
            spec = spec,
            elapsedMs = 200L,
            phaseDurationMs = 200L,
        )
        assertEquals(1f, endpoint.fraction)

        assertEquals(1L, gate.markPublished())
        assertTrue(gate.published)
        assertFalse(
            gate.shouldPublish(
                spec = spec,
                phaseElapsedMs = 300L,
                phaseDurationMs = 200L,
            ),
        )
        nowMs.set(3_999L)
        assertFalse(gate.acknowledgmentTimedOut())
        nowMs.set(4_000L)
        assertTrue(gate.acknowledgmentTimedOut())

        val shorterWindow = TerminalLinearRampEndpointGate(
            acknowledgmentTimeoutMs = 3_000L,
            monotonicNowMs = nowMs::get,
        )
        assertFalse(
            shorterWindow.shouldPublish(
                spec = spec.copy(transitionDurationMs = 100L),
                phaseElapsedMs = 200L,
                phaseDurationMs = 200L,
            ),
        )
    }

    @Test
    fun terminalLinearRampEndpointRearmsWithANewRevisionAfterProducerRecovery() {
        val nowMs = AtomicLong(1_000L)
        val spec = TransitionSpec(
            mode = TransitionMode.LINEAR_RAMP,
            transitionDurationMs = 0L,
        )
        val gate = TerminalLinearRampEndpointGate(
            acknowledgmentTimeoutMs = 3_000L,
            monotonicNowMs = nowMs::get,
        )

        assertTrue(gate.shouldPublish(spec, phaseElapsedMs = 200L, phaseDurationMs = 200L))
        val staleRevision = gate.markPublished()
        assertFalse(gate.rearmAfterProducerRecovery(staleRevision + 1L))
        assertTrue(gate.published)

        assertTrue(gate.rearmAfterProducerRecovery(staleRevision))
        assertFalse(gate.published)
        assertTrue(gate.shouldPublish(spec, phaseElapsedMs = 200L, phaseDurationMs = 200L))

        nowMs.set(1_500L)
        val recoveredRevision = gate.markPublished()
        assertEquals(staleRevision + 1L, recoveredRevision)
        assertFalse(gate.acknowledgmentTimedOut())
        nowMs.set(4_500L)
        assertTrue(gate.acknowledgmentTimedOut())
    }

    @Test
    fun linearRampCoverageRequiresBothIntermediateAndAcknowledgedTarget() {
        val coverage = TransitionCoverageTracker(
            TransitionSpec(mode = TransitionMode.LINEAR_RAMP),
        )
        coverage.observe(
            TransitionSample(0.5f, TransitionSegment.RAMP_UP),
            phaseElapsedMs = 100L,
        )
        assertTrue(coverage.failureReason()!!.contains("target"))

        coverage.observe(
            TransitionSample(1f, TransitionSegment.HOLD),
            phaseElapsedMs = 200L,
        )
        assertNull(coverage.failureReason())

        val targetOnly = TransitionCoverageTracker(
            TransitionSpec(mode = TransitionMode.LINEAR_RAMP),
        )
        targetOnly.observe(
            TransitionSample(1f, TransitionSegment.HOLD),
            phaseElapsedMs = 200L,
        )
        assertTrue(targetOnly.failureReason()!!.contains("intermediate"))
    }

    @Test
    fun pulseNpuZeroAndPositiveEdgesEachRequireFreshAcknowledgment() {
        val spec = TransitionSpec(
            mode = TransitionMode.PULSE_BURST,
            cycleMs = 1_000L,
            dutyCycle = 0.5f,
        )
        val origin = LoadSetpoints(npu = 0f)
        val target = LoadSetpoints(npu = 0.8f)
        var previousNpu: Float? = origin.npu
        var positiveAcknowledged = false

        fun decisionAt(elapsedMs: Long): NpuSemanticEdgeDecision {
            val sample = LoadTransitionEvaluator.sampleAt(
                spec = spec,
                elapsedMs = elapsedMs,
                phaseDurationMs = 2_000L,
            )
            val current = LoadTransitionEvaluator.interpolate(
                previous = origin,
                target = target,
                fraction = sample.fraction,
            ).npu
            return npuSemanticEdgeDecision(
                previousNpu = previousNpu,
                currentNpu = current,
                positiveAcknowledged = positiveAcknowledged,
            ).also { decision ->
                // Simulate the controller commit that is allowed only after APPLIED.
                previousNpu = current
                positiveAcknowledged = decision.positiveAcknowledgedAfterApply
            }
        }

        val firstOn = decisionAt(0L)
        assertEquals(NpuSemanticEdge.ZERO_TO_POSITIVE, firstOn.edge)
        assertTrue(firstOn.acknowledgmentRequired)
        assertTrue(firstOn.positiveAcknowledgedAfterApply)

        val off = decisionAt(500L)
        assertEquals(NpuSemanticEdge.POSITIVE_TO_ZERO, off.edge)
        assertTrue(off.acknowledgmentRequired)
        assertFalse(off.positiveAcknowledgedAfterApply)

        val secondOn = decisionAt(1_000L)
        assertEquals(NpuSemanticEdge.ZERO_TO_POSITIVE, secondOn.edge)
        assertTrue(secondOn.acknowledgmentRequired)
        assertTrue(secondOn.positiveAcknowledgedAfterApply)
    }

    @Test
    fun triangleNpuInteriorCheckpointsStayLatestWinsButValleyEdgesRequireAck() {
        val spec = TransitionSpec(
            mode = TransitionMode.TRIANGLE_WAVE,
            cycleMs = 1_000L,
        )
        val origin = LoadSetpoints(npu = 0f)
        val target = LoadSetpoints(npu = 0.8f)
        var previousNpu: Float? = origin.npu
        var positiveAcknowledged = false

        fun decisionAt(elapsedMs: Long): NpuSemanticEdgeDecision {
            val sample = LoadTransitionEvaluator.sampleAt(
                spec = spec,
                elapsedMs = elapsedMs,
                phaseDurationMs = 2_000L,
            )
            val current = LoadTransitionEvaluator.interpolate(
                previous = origin,
                target = target,
                fraction = sample.fraction,
            ).npu
            return npuSemanticEdgeDecision(
                previousNpu = previousNpu,
                currentNpu = current,
                positiveAcknowledged = positiveAcknowledged,
            ).also { decision ->
                previousNpu = current
                positiveAcknowledged = decision.positiveAcknowledgedAfterApply
            }
        }

        val originSample = decisionAt(0L)
        assertEquals(NpuSemanticEdge.NONE, originSample.edge)
        assertFalse(originSample.acknowledgmentRequired)

        val firstAttack = decisionAt(100L)
        assertEquals(NpuSemanticEdge.ZERO_TO_POSITIVE, firstAttack.edge)
        assertTrue(firstAttack.acknowledgmentRequired)

        listOf(200L, 500L, 900L).forEach { elapsedMs ->
            val interior = decisionAt(elapsedMs)
            assertEquals(NpuSemanticEdge.NONE, interior.edge)
            assertFalse(interior.acknowledgmentRequired)
            assertTrue(interior.positiveAcknowledgedAfterApply)
        }

        val valley = decisionAt(1_000L)
        assertEquals(NpuSemanticEdge.POSITIVE_TO_ZERO, valley.edge)
        assertTrue(valley.acknowledgmentRequired)
        assertFalse(valley.positiveAcknowledgedAfterApply)

        val reattack = decisionAt(1_100L)
        assertEquals(NpuSemanticEdge.ZERO_TO_POSITIVE, reattack.edge)
        assertTrue(reattack.acknowledgmentRequired)
    }

    @Test
    fun triangleNpuFullCycleZeroBoundarySurvivesJitterAndRequiresFreshReattack() {
        val spec = TransitionSpec(
            mode = TransitionMode.TRIANGLE_WAVE,
            cycleMs = 1_000L,
        )

        val boundary = triangleNpuZeroBoundaryDecision(
            spec = spec,
            previousPhaseElapsedMs = 999L,
            currentPhaseElapsedMs = 1_001L,
            phaseDurationMs = 2_000L,
            originNpu = 0f,
            targetNpu = 0.8f,
        )

        assertEquals(1L, boundary.crossedZeroBoundaries)
        assertTrue(boundary.zeroAcknowledgmentRequired)
        assertTrue(boundary.positiveReattackAcknowledgmentRequired)
        assertFalse(boundary.terminalBoundary)

        val zero = npuSemanticEdgeDecision(
            previousNpu = 0.2f,
            currentNpu = 0f,
            positiveAcknowledged = true,
        )
        assertEquals(NpuSemanticEdge.POSITIVE_TO_ZERO, zero.edge)
        assertTrue(zero.acknowledgmentRequired)
        assertFalse(zero.positiveAcknowledgedAfterApply)
        val reattack = npuSemanticEdgeDecision(
            previousNpu = 0f,
            currentNpu = 0.01f,
            positiveAcknowledged = zero.positiveAcknowledgedAfterApply,
        )
        assertEquals(NpuSemanticEdge.ZERO_TO_POSITIVE, reattack.edge)
        assertTrue(reattack.acknowledgmentRequired)
    }

    @Test
    fun triangleNpuTerminalFullCycleBoundaryRequiresZeroWithoutPositiveReattack() {
        val boundary = triangleNpuZeroBoundaryDecision(
            spec = TransitionSpec(
                mode = TransitionMode.TRIANGLE_WAVE,
                cycleMs = 1_000L,
            ),
            previousPhaseElapsedMs = 900L,
            // A late callback is clamped to the exact duration/cycle boundary.
            currentPhaseElapsedMs = 1_017L,
            phaseDurationMs = 1_000L,
            originNpu = 0f,
            targetNpu = 0.8f,
        )

        assertEquals(1L, boundary.crossedZeroBoundaries)
        assertTrue(boundary.zeroAcknowledgmentRequired)
        assertFalse(boundary.positiveReattackAcknowledgmentRequired)
        assertTrue(boundary.terminalBoundary)
    }

    @Test
    fun triangleNpuBoundaryReportsMultipleSkippedZerosForFailClosedDecision() {
        val boundary = triangleNpuZeroBoundaryDecision(
            spec = TransitionSpec(
                mode = TransitionMode.TRIANGLE_WAVE,
                cycleMs = 1_000L,
            ),
            previousPhaseElapsedMs = 999L,
            currentPhaseElapsedMs = 3_001L,
            phaseDurationMs = 4_000L,
            originNpu = 0f,
            targetNpu = 0.8f,
        )

        // The controller confirms only the newest exact zero, then treats this count as
        // inconclusive. It must never enqueue three historical zero/re-attack pairs.
        assertEquals(3L, boundary.crossedZeroBoundaries)
        assertTrue(boundary.zeroAcknowledgmentRequired)
        assertTrue(boundary.positiveReattackAcknowledgmentRequired)
        assertFalse(boundary.terminalBoundary)
    }

    @Test
    fun invertedTriangleHalfCycleZeroBoundarySurvivesJitterAndRequiresFreshReattack() {
        val spec = TransitionSpec(
            mode = TransitionMode.TRIANGLE_WAVE,
            cycleMs = 500L,
        )

        val boundary = triangleNpuZeroBoundaryDecision(
            spec = spec,
            previousPhaseElapsedMs = 249L,
            currentPhaseElapsedMs = 251L,
            phaseDurationMs = 1_000L,
            originNpu = 0.8f,
            targetNpu = 0f,
        )

        assertEquals(1L, boundary.crossedZeroBoundaries)
        assertTrue(boundary.zeroAcknowledgmentRequired)
        assertTrue(boundary.positiveReattackAcknowledgmentRequired)
        assertFalse(boundary.terminalBoundary)
    }

    @Test
    fun invertedTriangleExactHalfCycleZeroDefersReattackUntilFollowingPositiveTick() {
        val boundary = triangleNpuZeroBoundaryDecision(
            spec = TransitionSpec(
                mode = TransitionMode.TRIANGLE_WAVE,
                cycleMs = 500L,
            ),
            previousPhaseElapsedMs = 249L,
            currentPhaseElapsedMs = 250L,
            phaseDurationMs = 1_000L,
            originNpu = 0.8f,
            targetNpu = 0f,
        )

        assertEquals(1L, boundary.crossedZeroBoundaries)
        assertTrue(boundary.zeroAcknowledgmentRequired)
        assertFalse(boundary.positiveReattackAcknowledgmentRequired)
        assertFalse(boundary.terminalBoundary)
    }

    @Test
    fun invertedTriangleTerminalHalfCycleEndsWithExactZeroOnly() {
        val boundary = triangleNpuZeroBoundaryDecision(
            spec = TransitionSpec(
                mode = TransitionMode.TRIANGLE_WAVE,
                cycleMs = 500L,
            ),
            previousPhaseElapsedMs = 700L,
            currentPhaseElapsedMs = 767L,
            phaseDurationMs = 750L,
            originNpu = 0.8f,
            targetNpu = 0f,
        )

        assertEquals(1L, boundary.crossedZeroBoundaries)
        assertTrue(boundary.zeroAcknowledgmentRequired)
        assertFalse(boundary.positiveReattackAcknowledgmentRequired)
        assertTrue(boundary.terminalBoundary)
    }

    @Test
    fun invertedTriangleMultipleSkippedHalfCycleZerosFailClosedWithoutBacklog() {
        val boundary = triangleNpuZeroBoundaryDecision(
            spec = TransitionSpec(
                mode = TransitionMode.TRIANGLE_WAVE,
                cycleMs = 500L,
            ),
            previousPhaseElapsedMs = 249L,
            currentPhaseElapsedMs = 1_251L,
            phaseDurationMs = 1_500L,
            originNpu = 0.8f,
            targetNpu = 0f,
        )

        assertEquals(3L, boundary.crossedZeroBoundaries)
        assertTrue(boundary.zeroAcknowledgmentRequired)
        assertTrue(boundary.positiveReattackAcknowledgmentRequired)
        assertFalse(boundary.terminalBoundary)
    }

    @Test
    fun triangleNpuBoundaryIgnoresNonZeroFloorOriginAndNonCrossingSamples() {
        val base = TransitionSpec(
            mode = TransitionMode.TRIANGLE_WAVE,
            cycleMs = 1_000L,
        )
        val nonZeroFloor = triangleNpuZeroBoundaryDecision(
            spec = base.copy(floor = 0.2f),
            previousPhaseElapsedMs = 999L,
            currentPhaseElapsedMs = 1_001L,
            phaseDurationMs = 2_000L,
            originNpu = 0f,
            targetNpu = 0.8f,
        )
        assertEquals(0L, nonZeroFloor.crossedZeroBoundaries)
        assertFalse(nonZeroFloor.zeroAcknowledgmentRequired)
        assertFalse(nonZeroFloor.positiveReattackAcknowledgmentRequired)

        val nonZeroOrigin = triangleNpuZeroBoundaryDecision(
            spec = base,
            previousPhaseElapsedMs = 999L,
            currentPhaseElapsedMs = 1_001L,
            phaseDurationMs = 2_000L,
            originNpu = 0.2f,
            targetNpu = 0.8f,
        )
        assertEquals(0L, nonZeroOrigin.crossedZeroBoundaries)
        assertFalse(nonZeroOrigin.zeroAcknowledgmentRequired)
        assertFalse(nonZeroOrigin.positiveReattackAcknowledgmentRequired)

        val noCrossing = triangleNpuZeroBoundaryDecision(
            spec = base,
            previousPhaseElapsedMs = 900L,
            currentPhaseElapsedMs = 999L,
            phaseDurationMs = 2_000L,
            originNpu = 0f,
            targetNpu = 0.8f,
        )
        assertEquals(0L, noCrossing.crossedZeroBoundaries)
        assertFalse(noCrossing.zeroAcknowledgmentRequired)
        assertFalse(noCrossing.positiveReattackAcknowledgmentRequired)
        assertFalse(noCrossing.terminalBoundary)
    }

    @Test
    fun transitionCoverageRequiresBothPulseWindowsAndAllSoakSegments() {
        val pulse = TransitionCoverageTracker(
            TransitionSpec(mode = TransitionMode.PULSE_BURST),
        )
        pulse.observe(TransitionSample(1f, TransitionSegment.BURST_ON), phaseElapsedMs = 0L)
        assertTrue(pulse.failureReason()!!.contains("OFF"))
        pulse.observe(TransitionSample(0f, TransitionSegment.BURST_OFF), phaseElapsedMs = 2_500L)
        assertNull(pulse.failureReason())

        val soak = TransitionCoverageTracker(
            TransitionSpec(mode = TransitionMode.SOAK_RECOVERY),
        )
        soak.observe(TransitionSample(0f, TransitionSegment.RAMP_UP))
        soak.observe(TransitionSample(0.5f, TransitionSegment.RAMP_UP))
        soak.observe(TransitionSample(1f, TransitionSegment.HOLD))
        soak.observe(TransitionSample(0.5f, TransitionSegment.RAMP_DOWN))
        soak.observe(TransitionSample(0f, TransitionSegment.RAMP_DOWN))
        assertNull(soak.failureReason())
    }

    @Test
    fun transitionCoverageDoesNotCombineSegmentsFromDifferentCycles() {
        val pulse = TransitionCoverageTracker(
            TransitionSpec(mode = TransitionMode.PULSE_BURST, cycleMs = 1_000L),
        )
        pulse.observe(
            TransitionSample(1f, TransitionSegment.BURST_ON),
            phaseElapsedMs = 100L,
        )
        pulse.observe(
            TransitionSample(0f, TransitionSegment.BURST_OFF),
            phaseElapsedMs = 1_700L,
        )

        assertTrue(pulse.failureReason()!!.contains("동일 cycle"))
    }

    @Test
    fun reportTelemetryUsesOneRunRelativeMonotonicAxis() {
        val relative = TelemetrySnapshot(
            monotonicMs = 1_200L,
            hwcCompositionEvidenceMonotonicMs = 1_100L,
            hwcCompositionEvidenceAgeMs = 100L,
            surfaceFlingerEvidenceMonotonicMs = 1_150L,
            surfaceFlingerEvidenceAgeMs = 50L,
        ).toRunRelativeTelemetry(runStartMonotonicMs = 1_000L)

        assertEquals(200L, relative.monotonicMs)
        assertEquals(100L, relative.hwcCompositionEvidenceMonotonicMs)
        assertEquals(100L, relative.hwcCompositionEvidenceAgeMs)
        assertEquals(150L, relative.surfaceFlingerEvidenceMonotonicMs)
        assertEquals(50L, relative.surfaceFlingerEvidenceAgeMs)

        val invalidEvidence = TelemetrySnapshot(
            monotonicMs = 1_200L,
            hwcDeviceLayers = 2,
            hwcDeviceLayersQuality = MetricQuality.SYSTEM_SERVICE,
            hwcDeviceLayersSource = "SurfaceFlinger",
            hwcClientLayers = 0,
            hwcClientLayersQuality = MetricQuality.SYSTEM_SERVICE,
            hwcClientLayersSource = "SurfaceFlinger",
            hwcCompositionEvidenceMonotonicMs = 999L,
            hwcCompositionEvidenceAgeMs = 201L,
            surfaceFlingerHwcMissed = 3L,
            surfaceFlingerGpuMissed = 4L,
            surfaceFlingerMissSource = "SurfaceFlinger",
            surfaceFlingerEvidenceMonotonicMs = 1_201L,
            surfaceFlingerEvidenceAgeMs = 0L,
        ).toRunRelativeTelemetry(runStartMonotonicMs = 1_000L)
        // A valid pre-run cache keeps a signed timestamp and its atomic pair.
        assertEquals(-1L, invalidEvidence.hwcCompositionEvidenceMonotonicMs)
        assertEquals(201L, invalidEvidence.hwcCompositionEvidenceAgeMs)
        assertEquals(2, invalidEvidence.hwcDeviceLayers)
        assertEquals(MetricQuality.SYSTEM_SERVICE, invalidEvidence.hwcDeviceLayersQuality)
        // A future timestamp clears all related values/provenance atomically.
        assertNull(invalidEvidence.surfaceFlingerEvidenceMonotonicMs)
        assertNull(invalidEvidence.surfaceFlingerEvidenceAgeMs)
        assertNull(invalidEvidence.surfaceFlingerHwcMissed)
        assertNull(invalidEvidence.surfaceFlingerGpuMissed)
        assertEquals("", invalidEvidence.surfaceFlingerMissSource)

        val preRunSample = TelemetrySnapshot(
            monotonicMs = 900L,
            hwcDeviceLayers = 1,
            hwcDeviceLayersQuality = MetricQuality.SYSTEM_SERVICE,
            hwcDeviceLayersSource = "SurfaceFlinger",
            hwcClientLayers = 0,
            hwcClientLayersQuality = MetricQuality.SYSTEM_SERVICE,
            hwcClientLayersSource = "SurfaceFlinger",
            hwcCompositionEvidenceMonotonicMs = 900L,
            hwcCompositionEvidenceAgeMs = 0L,
        ).toRunRelativeTelemetry(runStartMonotonicMs = 1_000L)
        assertEquals(0L, preRunSample.monotonicMs)
        assertNull(preRunSample.hwcCompositionEvidenceMonotonicMs)
        assertNull(preRunSample.hwcCompositionEvidenceAgeMs)
        assertNull(preRunSample.hwcDeviceLayers)
        assertNull(preRunSample.hwcClientLayers)
        assertEquals(MetricQuality.UNAVAILABLE, preRunSample.hwcDeviceLayersQuality)
        assertEquals("", preRunSample.hwcDeviceLayersSource)

        val inconsistentAge = TelemetrySnapshot(
            monotonicMs = 1_200L,
            hwcDeviceLayers = 1,
            hwcDeviceLayersQuality = MetricQuality.SYSTEM_SERVICE,
            hwcDeviceLayersSource = "SurfaceFlinger",
            hwcClientLayers = 0,
            hwcClientLayersQuality = MetricQuality.SYSTEM_SERVICE,
            hwcClientLayersSource = "SurfaceFlinger",
            hwcCompositionEvidenceMonotonicMs = 1_100L,
            hwcCompositionEvidenceAgeMs = 99L,
        ).toRunRelativeTelemetry(runStartMonotonicMs = 1_000L)
        assertNull(inconsistentAge.hwcCompositionEvidenceMonotonicMs)
        assertNull(inconsistentAge.hwcDeviceLayers)
        assertEquals(MetricQuality.UNAVAILABLE, inconsistentAge.hwcDeviceLayersQuality)
        assertEquals("", inconsistentAge.hwcDeviceLayersSource)
    }

    @Test
    fun hwcTargetArmRequiresOneFreshStableProducerTopologySnapshot() {
        fun ready(
            topologyPending: Boolean = false,
            processLeaseActive: Boolean = false,
            pendingBoundaryExists: Boolean = false,
            topologyMissed: Boolean = false,
            teardownFailed: Boolean = false,
            teardownCompleted: Boolean = false,
            observedProducerCount: Int = 4,
            observedTopologyRevision: Long = 9L,
        ) = hwcCompositionTargetReadyForArm(
            ready = true,
            topologyPublished = true,
            topologyPending = topologyPending,
            processLeaseActive = processLeaseActive,
            pendingBoundaryExists = pendingBoundaryExists,
            topologyMissed = topologyMissed,
            teardownFailed = teardownFailed,
            teardownCompleted = teardownCompleted,
            runtimeFailurePresent = false,
            expectedProducerCount = 4,
            observedProducerCount = observedProducerCount,
            expectedTopologyRevision = 9L,
            observedTopologyRevision = observedTopologyRevision,
        )

        assertTrue(ready())
        assertFalse(ready(topologyPending = true))
        assertFalse(ready(processLeaseActive = true))
        assertFalse(ready(pendingBoundaryExists = true))
        assertFalse(ready(topologyMissed = true))
        assertFalse(ready(teardownFailed = true))
        assertFalse(ready(teardownCompleted = true))
        assertFalse(ready(observedProducerCount = 3))
        assertFalse(ready(observedTopologyRevision = 8L))
    }

    @Test
    fun runtimeThermalClampInvalidatesTypedHwcContractBeforeOrDuringTarget() {
        val requested = ScenarioCatalog.presets.first().phases.first().copy(
            activeLayers = 20,
            producerFps = 120f,
            requestedDisplayHz = 120f,
            workloads = LoadSetpoints(gpu = 0.8f),
            includeGlLayer = true,
            hwcCompositionExpectation = HwcCompositionExpectation.CLIENT_REQUIRED,
        )
        val reduced = requested.copy(
            activeLayers = 10,
            producerFps = 60f,
            requestedDisplayHz = 60f,
            workloads = requested.workloads.copy(gpu = 0.4f),
        )
        assertTrue(hwcCompositionContractPreserved(requested, requested))
        assertFalse(
            hwcCompositionContractPreserved(
                requested,
                requested.copy(layerSizeProfile = LayerSizeProfile.SMALL_UNIFORM),
            ),
        )
        assertFalse(hwcCompositionContractPreserved(requested, reduced))
        assertTrue(
            hwcCompositionContractPreserved(
                requested.copy(
                    hwcCompositionExpectation = HwcCompositionExpectation.NONE,
                ),
                reduced.copy(
                    hwcCompositionExpectation = HwcCompositionExpectation.NONE,
                ),
            ),
        )
        assertTrue(
            hwcCompositionContractDeltaSummary(requested, reduced)
                .contains("layers=20→10"),
        )

        val tracker = HwcCompositionCoverageTracker(
            HwcCompositionExpectation.CLIENT_REQUIRED,
        )
        tracker.recordContractFailure("typed HWC target changed during thermal derate")
        tracker.activateTarget(100L)
        repeat(2) { index ->
            val evidenceMs = 110L + index * 20L
            tracker.observe(
                sampleStartedMonotonicMs = evidenceMs,
                snapshot = hwcSnapshot(
                    sampleMonotonicMs = evidenceMs,
                    evidenceMonotonicMs = evidenceMs,
                    deviceLayers = 2,
                    clientLayers = 2,
                ),
            )
        }
        val result = tracker.result()
        assertFalse(result.satisfied)
        assertTrue(result.failureReason!!.contains("thermal derate"))
        // Reduced-target observations remain reportable auxiliary evidence.
        assertEquals(2, result.matchingEvidenceCount)
        assertEquals(2, result.validClientLayers)
        val chain = advanceHwcCompositionChain(
            prior = null,
            coverage = result,
            // Directional history keeps the declared contract, not the thermal-effective 10L.
            requestedActiveLayers = requested.activeLayers,
        )
        assertEquals(20, chain.nextAnchor!!.requestedActiveLayers)
    }

    @Test
    fun hwcDeviceOnlyCoverageStartsAfterTargetReadyAndCountsCachedEvidenceOnce() {
        val tracker = HwcCompositionCoverageTracker(
            HwcCompositionExpectation.DEVICE_ONLY,
        )
        assertTrue(tracker.activateTarget(100L))
        assertFalse(tracker.activateTarget(101L))

        // Even though the dump completed later, a request issued before target readiness cannot
        // classify the new target.
        tracker.observe(
            sampleStartedMonotonicMs = 90L,
            snapshot = hwcSnapshot(
                sampleMonotonicMs = 110L,
                evidenceMonotonicMs = 110L,
                deviceLayers = 4,
                clientLayers = 0,
            ),
        )
        tracker.observe(
            sampleStartedMonotonicMs = 120L,
            snapshot = hwcSnapshot(
                sampleMonotonicMs = 130L,
                evidenceMonotonicMs = 130L,
                deviceLayers = 4,
                clientLayers = 0,
            ),
        )
        // A later full telemetry sample may project the same independently timestamped evidence.
        tracker.observe(
            sampleStartedMonotonicMs = 140L,
            snapshot = hwcSnapshot(
                sampleMonotonicMs = 150L,
                evidenceMonotonicMs = 130L,
                deviceLayers = 4,
                clientLayers = 0,
            ),
        )

        val result = tracker.result()
        assertTrue(result.satisfied)
        assertEquals(1, result.freshEvidenceCount)
        assertEquals(1, result.matchingEvidenceCount)
    }

    @Test
    fun hwcDeviceOnlyCoverageFailsOnAnyClientCompositionObservation() {
        val tracker = HwcCompositionCoverageTracker(
            HwcCompositionExpectation.DEVICE_ONLY,
        )
        tracker.activateTarget(100L)
        tracker.observe(
            sampleStartedMonotonicMs = 100L,
            snapshot = hwcSnapshot(
                sampleMonotonicMs = 110L,
                evidenceMonotonicMs = 110L,
                deviceLayers = 3,
                clientLayers = 1,
            ),
        )
        tracker.observe(
            sampleStartedMonotonicMs = 120L,
            snapshot = hwcSnapshot(
                sampleMonotonicMs = 130L,
                evidenceMonotonicMs = 130L,
                deviceLayers = 4,
                clientLayers = 0,
            ),
        )

        val result = tracker.result()
        assertFalse(result.satisfied)
        assertTrue(result.failureReason!!.contains("client==0"))
        assertEquals(1, result.matchingEvidenceCount)
    }

    @Test
    fun hwcClientCoverageRequiresClientAndStablePairProvenance() {
        val missingClient = HwcCompositionCoverageTracker(
            HwcCompositionExpectation.CLIENT_REQUIRED,
        )
        missingClient.activateTarget(100L)
        missingClient.observe(
            sampleStartedMonotonicMs = 100L,
            snapshot = hwcSnapshot(
                sampleMonotonicMs = 110L,
                evidenceMonotonicMs = 110L,
                deviceLayers = 4,
                clientLayers = 0,
            ),
        )
        assertTrue(missingClient.result().failureReason!!.contains("client>0"))

        val changedSource = HwcCompositionCoverageTracker(
            HwcCompositionExpectation.CLIENT_REQUIRED,
        )
        changedSource.activateTarget(100L)
        changedSource.observe(
            sampleStartedMonotonicMs = 100L,
            snapshot = hwcSnapshot(
                sampleMonotonicMs = 110L,
                evidenceMonotonicMs = 110L,
                deviceLayers = 2,
                clientLayers = 2,
            ),
        )
        changedSource.observe(
            sampleStartedMonotonicMs = 120L,
            snapshot = hwcSnapshot(
                sampleMonotonicMs = 130L,
                evidenceMonotonicMs = 130L,
                deviceLayers = 2,
                clientLayers = 2,
                quality = MetricQuality.HARDWARE_COUNTER,
                source = "vendor-session-1",
            ),
        )
        assertTrue(changedSource.result().failureReason!!.contains("changed"))
    }

    @Test
    fun hwcClientCoverageNeedsTwoDistinctFreshEvidenceTimestamps() {
        val tracker = HwcCompositionCoverageTracker(
            HwcCompositionExpectation.CLIENT_REQUIRED,
        )
        tracker.activateTarget(1_100L)
        tracker.observe(
            sampleStartedMonotonicMs = 1_100L,
            snapshot = hwcSnapshot(
                sampleMonotonicMs = 1_200L,
                evidenceMonotonicMs = 1_200L,
                deviceLayers = 2,
                clientLayers = 3,
            ),
        )
        assertFalse(tracker.hasSatisfiedEvidence())
        assertTrue(tracker.result().failureReason!!.contains("requires 2"))

        // Reprojecting a cache entry is not a second observation.
        tracker.observe(
            sampleStartedMonotonicMs = 1_250L,
            snapshot = hwcSnapshot(
                sampleMonotonicMs = 1_300L,
                evidenceMonotonicMs = 1_200L,
                deviceLayers = 2,
                clientLayers = 3,
            ),
        )
        assertFalse(tracker.hasSatisfiedEvidence())

        tracker.observe(
            sampleStartedMonotonicMs = 1_350L,
            snapshot = hwcSnapshot(
                sampleMonotonicMs = 1_400L,
                evidenceMonotonicMs = 1_400L,
                deviceLayers = 2,
                clientLayers = 3,
            ),
        )
        val result = tracker.result()
        assertTrue(result.satisfied)
        assertEquals(2, result.matchingEvidenceCount)
        val event = result.eventSummary(runStartMonotonicMs = 1_000L)
        assertTrue(event.contains("targetReadyRunMs=100"))
        assertTrue(event.contains("evidenceRunMs=400"))
        assertFalse(event.contains("targetReadyAtMs"))
    }

    @Test
    fun hwcCompositionChainRequiresSameProvenanceAndDirectionalDeltas() {
        fun deviceCoverage(
            device: Int,
            evidenceMs: Long,
            source: String = "SurfaceFlinger",
        ): HwcCompositionCoverageResult {
            val tracker = HwcCompositionCoverageTracker(
                HwcCompositionExpectation.DEVICE_ONLY,
            )
            tracker.activateTarget(evidenceMs - 10L)
            tracker.observe(
                sampleStartedMonotonicMs = evidenceMs - 10L,
                snapshot = hwcSnapshot(
                    sampleMonotonicMs = evidenceMs,
                    evidenceMonotonicMs = evidenceMs,
                    deviceLayers = device,
                    clientLayers = 0,
                    source = source,
                ),
            )
            return tracker.result()
        }

        val baseline = advanceHwcCompositionChain(
            prior = null,
            coverage = deviceCoverage(device = 1, evidenceMs = 1_100L),
            requestedActiveLayers = 1,
        )
        assertNull(baseline.failureReason)
        val high = advanceHwcCompositionChain(
            prior = baseline.nextAnchor,
            coverage = deviceCoverage(device = 4, evidenceMs = 1_200L),
            requestedActiveLayers = 4,
        )
        assertNull(high.failureReason)
        val release = advanceHwcCompositionChain(
            prior = high.nextAnchor,
            coverage = deviceCoverage(device = 1, evidenceMs = 1_300L),
            requestedActiveLayers = 1,
        )
        assertNull(release.failureReason)

        val wrongDirection = advanceHwcCompositionChain(
            prior = release.nextAnchor,
            coverage = deviceCoverage(device = 1, evidenceMs = 1_400L),
            requestedActiveLayers = 4,
        )
        assertTrue(wrongDirection.failureReason!!.contains("did not increase"))

        val changedSource = advanceHwcCompositionChain(
            prior = release.nextAnchor,
            coverage = deviceCoverage(
                device = 4,
                evidenceMs = 1_400L,
                source = "vendor-hwc",
            ),
            requestedActiveLayers = 4,
        )
        assertTrue(changedSource.failureReason!!.contains("changed across"))
        assertEquals(release.nextAnchor, changedSource.nextAnchor)
    }

    @Test
    fun hwcClientChainRequiresAnEarlierBaselineAndClientCountIncrease() {
        fun clientCoverage(
            client: Int,
            firstEvidenceMs: Long,
        ): HwcCompositionCoverageResult {
            val tracker = HwcCompositionCoverageTracker(
                HwcCompositionExpectation.CLIENT_REQUIRED,
            )
            tracker.activateTarget(firstEvidenceMs - 10L)
            repeat(2) { index ->
                val evidenceMs = firstEvidenceMs + index * 100L
                tracker.observe(
                    sampleStartedMonotonicMs = evidenceMs - 10L,
                    snapshot = hwcSnapshot(
                        sampleMonotonicMs = evidenceMs,
                        evidenceMonotonicMs = evidenceMs,
                        deviceLayers = 2,
                        clientLayers = client,
                    ),
                )
            }
            return tracker.result()
        }

        val withoutBaseline = advanceHwcCompositionChain(
            prior = null,
            coverage = clientCoverage(client = 2, firstEvidenceMs = 1_100L),
            requestedActiveLayers = 20,
        )
        assertTrue(withoutBaseline.failureReason!!.contains("no prior"))

        val baseline = HwcCompositionChainAnchor(
            expectation = HwcCompositionExpectation.DEVICE_ONLY,
            quality = MetricQuality.SYSTEM_SERVICE,
            source = "SurfaceFlinger",
            deviceLayers = 1,
            clientLayers = 0,
            evidenceMonotonicMs = 1_000L,
            requestedActiveLayers = 1,
        )
        val increased = advanceHwcCompositionChain(
            prior = baseline,
            coverage = clientCoverage(client = 2, firstEvidenceMs = 1_100L),
            requestedActiveLayers = 20,
        )
        assertNull(increased.failureReason)

        val releaseTracker = HwcCompositionCoverageTracker(
            HwcCompositionExpectation.DEVICE_ONLY,
        )
        releaseTracker.activateTarget(1_300L)
        releaseTracker.observe(
            sampleStartedMonotonicMs = 1_300L,
            snapshot = hwcSnapshot(
                sampleMonotonicMs = 1_400L,
                evidenceMonotonicMs = 1_400L,
                // A normal CLIENT→DEVICE recovery can keep the same DEVICE count while CLIENT
                // falls to zero; it must not be judged as a failed high→low DEVICE sweep.
                deviceLayers = 2,
                clientLayers = 0,
            ),
        )
        val released = advanceHwcCompositionChain(
            prior = increased.nextAnchor,
            coverage = releaseTracker.result(),
            requestedActiveLayers = 1,
        )
        assertNull(released.failureReason)

        val nonIncreasingBaseline = baseline.copy(
            clientLayers = 2,
        )
        val nonIncreasing = advanceHwcCompositionChain(
            prior = nonIncreasingBaseline,
            coverage = clientCoverage(client = 2, firstEvidenceMs = 1_100L),
            requestedActiveLayers = 20,
        )
        assertTrue(nonIncreasing.failureReason!!.contains("did not increase"))
    }

    @Test
    fun hwcCoverageRejectsActiveGapStaleAgeAndMixedPairSources() {
        val gap = HwcCompositionCoverageTracker(
            HwcCompositionExpectation.CLIENT_REQUIRED,
        )
        gap.activateTarget(100L)
        gap.observe(
            sampleStartedMonotonicMs = 100L,
            snapshot = TelemetrySnapshot(monotonicMs = 110L),
        )
        assertTrue(gap.result().failureReason!!.contains("timestamp"))

        val stale = HwcCompositionCoverageTracker(
            HwcCompositionExpectation.CLIENT_REQUIRED,
        )
        stale.activateTarget(100L)
        stale.observe(
            sampleStartedMonotonicMs = 100L,
            snapshot = hwcSnapshot(
                sampleMonotonicMs = 3_000L,
                evidenceMonotonicMs = 110L,
                deviceLayers = 2,
                clientLayers = 1,
            ),
        )
        assertTrue(stale.result().failureReason!!.contains("stale"))

        val cachedThenStale = HwcCompositionCoverageTracker(
            HwcCompositionExpectation.DEVICE_ONLY,
        )
        cachedThenStale.activateTarget(100L)
        cachedThenStale.observe(
            sampleStartedMonotonicMs = 100L,
            snapshot = hwcSnapshot(
                sampleMonotonicMs = 110L,
                evidenceMonotonicMs = 110L,
                deviceLayers = 2,
                clientLayers = 0,
            ),
        )
        cachedThenStale.observe(
            sampleStartedMonotonicMs = 3_000L,
            snapshot = hwcSnapshot(
                sampleMonotonicMs = 3_000L,
                evidenceMonotonicMs = 110L,
                deviceLayers = 2,
                clientLayers = 0,
            ),
        )
        assertTrue(cachedThenStale.result().failureReason!!.contains("stale"))

        val mixed = HwcCompositionCoverageTracker(
            HwcCompositionExpectation.CLIENT_REQUIRED,
        )
        mixed.activateTarget(100L)
        mixed.observe(
            sampleStartedMonotonicMs = 100L,
            snapshot = hwcSnapshot(
                sampleMonotonicMs = 110L,
                evidenceMonotonicMs = 110L,
                deviceLayers = 2,
                clientLayers = 1,
            ).copy(hwcClientLayersSource = "different-source"),
        )
        assertTrue(mixed.result().failureReason!!.contains("mixed provenance"))
    }

    @Test
    fun exactUnderrunVerdictOutranksMissingHwcCoverage() {
        assertEquals(
            RunVerdict.UNDERRUN_DETECTED,
            verdictWithHwcCompositionCoverage(
                evidenceVerdict = RunVerdict.UNDERRUN_DETECTED,
                coverageFailureReason = "missing DEVICE-only coverage",
            ),
        )
        assertEquals(
            RunVerdict.INCONCLUSIVE,
            verdictWithHwcCompositionCoverage(
                evidenceVerdict = RunVerdict.CLEAN,
                coverageFailureReason = "missing DEVICE-only coverage",
            ),
        )
        assertEquals(
            RunVerdict.SUSPECTED_PROXY,
            verdictWithHwcCompositionCoverage(
                evidenceVerdict = RunVerdict.SUSPECTED_PROXY,
                coverageFailureReason = null,
            ),
        )
    }

    private fun hwcSnapshot(
        sampleMonotonicMs: Long,
        evidenceMonotonicMs: Long,
        deviceLayers: Int,
        clientLayers: Int,
        quality: MetricQuality = MetricQuality.SYSTEM_SERVICE,
        source: String = "SurfaceFlinger",
    ) = TelemetrySnapshot(
        monotonicMs = sampleMonotonicMs,
        hwcDeviceLayers = deviceLayers,
        hwcDeviceLayersQuality = quality,
        hwcDeviceLayersSource = source,
        hwcClientLayers = clientLayers,
        hwcClientLayersQuality = quality,
        hwcClientLayersSource = source,
        hwcCompositionEvidenceMonotonicMs = evidenceMonotonicMs,
        hwcCompositionEvidenceAgeMs =
            (sampleMonotonicMs - evidenceMonotonicMs).coerceAtLeast(0L),
    )

    private fun decoderRatePhase(
        id: String,
        route: PixelRoute,
        fps: Float,
        transition: TransitionSpec = TransitionSpec(),
    ) = com.example.dpulayerlab.model.PhaseSpec(
        id = id,
        label = id,
        durationMs = 5_000L,
        activeLayers = 1,
        producerFps = fps,
        requestedDisplayHz = 60f,
        backend = LayerBackend.INDEPENDENT_SURFACES,
        pixelRoute = route,
        bufferSize = BufferSize.DISPLAY,
        motion = MotionProfile.STATIC,
        transition = transition,
    )
}
