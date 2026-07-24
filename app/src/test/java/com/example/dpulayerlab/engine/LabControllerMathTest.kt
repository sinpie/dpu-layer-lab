package com.example.dpulayerlab.engine

import android.media.MediaCodecInfo
import com.example.dpulayerlab.model.BufferSize
import com.example.dpulayerlab.model.Gauge
import com.example.dpulayerlab.model.LayerBackend
import com.example.dpulayerlab.model.LoadTransitionEvaluator
import com.example.dpulayerlab.model.LoadSetpoints
import com.example.dpulayerlab.model.MetricQuality
import com.example.dpulayerlab.model.MotionProfile
import com.example.dpulayerlab.model.PixelRoute
import com.example.dpulayerlab.model.RunProgress
import com.example.dpulayerlab.model.RunVerdict
import com.example.dpulayerlab.model.RunnerStage
import com.example.dpulayerlab.model.ScenarioRunPlan
import com.example.dpulayerlab.model.TelemetrySnapshot
import com.example.dpulayerlab.monitor.CompressionControlResult
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabControllerMathTest {
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
        assertNull(vp9BitDepthFromCodecsString("vp09.03.10.10.03.09.16.09.01"))
        assertNull(vp9BitDepthFromCodecsString("vp09.02.10.08.01.09.16.09.01"))
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
                workloadApplied = true,
                displayApplied = true,
            ),
        )
        assertTrue(
            thermalDerateActionFailed(
                workloadApplied = false,
                displayApplied = true,
            ),
        )
        assertTrue(
            thermalDerateActionFailed(
                workloadApplied = true,
                displayApplied = false,
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
            alphaOverlap = true,
            includeGlLayer = true,
        )

        val warmup = safeWarmupPhaseFor(target)
        assertEquals(1, warmup.activeLayers)
        assertEquals(LayerBackend.INDEPENDENT_SURFACES, warmup.backend)
        assertEquals(PixelRoute.RGB_8888, warmup.pixelRoute)
        assertEquals(BufferSize.DISPLAY, warmup.bufferSize)
        assertFalse(warmup.alphaOverlap)
        assertFalse(warmup.includeGlLayer)
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
        assertEquals(linearTarget.activeLayers, safeReverse.activeLayers)
        assertEquals(sbwcOrigin.producerFps, safeReverse.producerFps, 0f)
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
}
