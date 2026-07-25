package com.example.dpulayerlab.render

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import androidx.core.graphics.withSave
import com.example.dpulayerlab.model.ABRUPT_LAYER_SIZE_PROFILE_STEPS
import com.example.dpulayerlab.model.BufferSize
import com.example.dpulayerlab.model.BufferPresentation
import com.example.dpulayerlab.model.GRADUAL_MID_MIN_FRACTION
import com.example.dpulayerlab.model.LayerBackend
import com.example.dpulayerlab.model.LayerSizeProfile
import com.example.dpulayerlab.model.LayerOrientation
import com.example.dpulayerlab.model.LoadShape
import com.example.dpulayerlab.model.LoadShapeEvaluator
import com.example.dpulayerlab.model.MotionProfile
import com.example.dpulayerlab.model.PhaseSpec
import com.example.dpulayerlab.model.PixelRoute
import com.example.dpulayerlab.model.coverageBitAt
import com.example.dpulayerlab.model.normalizedSizeForLayer
import com.example.dpulayerlab.model.usesSelectedMediaDecoder
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A stage made of actual BufferQueue-backed views. Each SurfaceView becomes a distinct
 * SurfaceFlinger layer; TextureView phases intentionally collapse content into client/GPU
 * composition. The UI does not claim a given layer is DEVICE-composed until the privileged
 * SurfaceFlinger probe confirms it.
 */
class LayerStageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs), Choreographer.FrameCallback {

    private var phase: PhaseSpec? = null
    private var selectedMediaUri: Uri? = null
    private var videoDecoderSelection: VideoDecoderSelection? = null
    private var topology: LayerTopology? = null
    private var startNanos = 0L
    private var lastTransformNanos = 0L
    private var layerSizeClockAnchorNanos = 0L
    private var layerSizeElapsedAtAnchorNanos = 0L
    private var layerSizeAuthoritativeElapsedMs = Long.MIN_VALUE
    private var lastAppliedLayerSizeFraction = -1f
    private var attached = false
    private var frameCallbackPosted = false
    private val animatedChildren = mutableListOf<View>()
    private val childCropBounds = IdentityHashMap<View, Rect>()
    private var producerFrameCallback: ProducerFrameCallback? = null
    private var expectedProducersCallback: ((Long, Set<Long>) -> Unit)? = null
    private var producerTopologyPendingCallback: ((Long) -> Unit)? = null
    private var layerGeometryRequestedCallback: ((Long, Long, Int) -> Unit)? = null
    private var layerGeometryAppliedCallback: ((Long, Long, Int, Int) -> Unit)? = null
    private var producerTeardownFailureCallback: ((Long) -> Unit)? = null
    private var producerRuntimeFailureCallback: ((Long, String) -> Unit)? = null
    private var stageRemovalCallback: ((Long, Boolean) -> Unit)? = null
    private val producerRelays = IdentityHashMap<View, ProducerFrameRelay>()
    private var nextProducerId = 0L
    private var producerGeneration = Long.MIN_VALUE
    private var requestedProducerControlRevision = 0L
    private var committedProducerControlRevision = 0L
    private var deferredTopologyApply: DeferredTopologyApply? = null
    private var failedTopologyGeneration = Long.MIN_VALUE
    private val expectedPublishSuppression = ExpectedPublicationSuppression()
    private var expectedPublicationMutationEpoch = 0L
    private var producerBindingsCommitted = false
    private var expectedTopologyDirty = true
    private var forceExpectedProducerRepublish = false
    private var layerGeometryRevision = 0L
    private var layerGeometryKeyGeneration = Long.MIN_VALUE
    private var layerGeometryKeyPhaseId: String? = null
    private var layerGeometryKeyProfileOrdinal = -1
    private var layerGeometryKeySample = -1
    private var layerGeometryKeyLayerCount = -1
    private var layerGeometryKeyWidth = -1
    private var layerGeometryKeyHeight = -1
    private var pendingLayerGeometryRevision = 0L
    private var pendingLayerGeometryProfileOrdinal = -1
    private var pendingLayerGeometryCoverageBit = 0
    private var pendingLayerGeometryAckFrames = 0
    private var lastPublishedExpectedGeneration = Long.MIN_VALUE
    private var lastPublishedProducerIds: Set<Long> = emptySet()
    private var lastPublishedExpectedCallback: ((Long, Set<Long>) -> Unit)? = null
    private var capacityGeometryReady = false
    private val viewIdentity: (View) -> View = { it }
    private val renderChildView: (RenderChild) -> View = { it.view }
    private val deferredTopologyApplyRunnable = Runnable(::continueDeferredTopologyApply)

    init {
        setBackgroundColor(Color.BLACK)
        clipChildren = false
        clipToPadding = false
    }

    fun configure(
        newPhase: PhaseSpec,
        selectedMedia: Uri?,
        selectedDecoder: VideoDecoderSelection?,
        newProducerGeneration: Long,
        newPhaseElapsedMs: Long,
        newProducerControlRevision: Long,
        onProducerFrame: ProducerFrameCallback? = null,
        onExpectedProducers: ((generation: Long, producerIds: Set<Long>) -> Unit)? = null,
        onProducerTopologyPending: ((generation: Long) -> Unit)? = null,
        onLayerGeometryRequested:
            ((generation: Long, revision: Long, profileOrdinal: Int) -> Unit)? = null,
        onLayerGeometryApplied:
            ((
                generation: Long,
                revision: Long,
                profileOrdinal: Int,
                coverageBit: Int,
            ) -> Unit)? = null,
        onProducerTeardownFailure: ((generation: Long) -> Unit)? = null,
        onProducerRuntimeFailure: ((generation: Long, reason: String) -> Unit)? = null,
        onStageRemoved: ((generation: Long, producersStopped: Boolean) -> Unit)? = null,
    ) {
        val producerCallbackChanged = producerFrameCallback !== onProducerFrame
        val expectedCallbackChanged = expectedProducersCallback !== onExpectedProducers
        val previousPhaseId = phase?.id
        val previousMotion = phase?.motion
        val previousLayerSizeProfile = phase?.layerSizeProfile
        val phaseChanged = previousPhaseId != newPhase.id
        val generationChanged = producerGeneration != newProducerGeneration
        val layerSizeProfileChanged = previousLayerSizeProfile != newPhase.layerSizeProfile
        if (
            !producerControlRevisionIsValid(
                requestedRevision = newProducerControlRevision,
                currentRevision = requestedProducerControlRevision,
                generationChanged = generationChanged,
            )
        ) {
            onProducerRuntimeFailure?.invoke(
                newProducerGeneration,
                "Renderer producer-control revision regressed",
            )
            return
        }
        val newTopology = topologyFor(newPhase, selectedMedia, selectedDecoder)
        val nowNanos = System.nanoTime()
        advanceExpectedPublicationMutationEpoch()

        // No stage/controller-visible state is changed before all hostile input is validated and
        // the immutable topology value is constructed successfully.
        producerFrameCallback = onProducerFrame
        expectedProducersCallback = onExpectedProducers
        producerTopologyPendingCallback = onProducerTopologyPending
        layerGeometryRequestedCallback = onLayerGeometryRequested
        layerGeometryAppliedCallback = onLayerGeometryApplied
        producerTeardownFailureCallback = onProducerTeardownFailure
        producerRuntimeFailureCallback = onProducerRuntimeFailure
        stageRemovalCallback = onStageRemoved
        if (generationChanged || producerCallbackChanged) {
            // A mixed old/new relay binding must never escape through configure's finally block.
            producerBindingsCommitted = false
        }
        if (generationChanged) {
            resetLayerGeometryTracking()
            requestedProducerControlRevision = 0L
            committedProducerControlRevision = 0L
        } else if (phaseChanged || layerSizeProfileChanged) {
            // A pending acknowledgment belongs to the previous semantic geometry identity.
            // Cancel it before applying the new phase/profile so it cannot be reported as proof
            // for a View transform which has already been replaced.
            invalidateLayerGeometryKey()
        }
        requestedProducerControlRevision = newProducerControlRevision
        syncLayerSizePhaseClock(
            phase = newPhase,
            authoritativeElapsedMs = newPhaseElapsedMs,
            anchorNanos = nowNanos,
            force = phaseChanged || generationChanged || layerSizeProfileChanged,
        )
        if (generationChanged || expectedCallbackChanged) expectedTopologyDirty = true
        if (generationChanged) {
            failedTopologyGeneration = Long.MIN_VALUE
            forceExpectedProducerRepublish = false
        }
        producerGeneration = newProducerGeneration
        phase = newPhase
        capacityGeometryReady = when {
            newPhase.motion != MotionProfile.CAPACITY_TILES -> true
            generationChanged || previousMotion != MotionProfile.CAPACITY_TILES -> false
            else -> capacityGeometryReady
        }
        selectedMediaUri = selectedMedia
        videoDecoderSelection = selectedDecoder
        val suppressionToken = expectedPublishSuppression.begin()
        try {
            val mutationCompleted = runFailClosedRendererMutation(
                mutation = {
                    if (failedTopologyGeneration == producerGeneration) return
            deferredTopologyApply?.let { pending ->
                if (pending.generation != producerGeneration) {
                    pending.deadlineMs = saturatingDeadline(
                        SystemClock.elapsedRealtime(),
                        PRODUCER_RECOVERY_TIMEOUT_MS,
                    )
                }
                pending.generation = producerGeneration
                producerTopologyPendingCallback?.invoke(producerGeneration)
                scheduleDeferredTopologyApply()
                return
            }

            val replaceVideoPrimary =
                generationChanged && primaryRequiresReplacement()
            when {
                newTopology == topology && !replaceVideoPrimary -> {
                    if (generationChanged || producerCallbackChanged) {
                        if (!updateProducerRelays()) return
                    }
                }
                canReconcileLayerCount(topology, newTopology) &&
                    !replaceVideoPrimary &&
                    !RendererSafetyState.hasUnconfirmedTeardown() -> {
                    producerBindingsCommitted = false
                    producerTopologyPendingCallback?.invoke(producerGeneration)
                    val reconciled = reconcileLayerCount(newPhase)
                    if (!reconciled) {
                        beginDeferredTopologyApply()
                        return
                    }
                    if (generationChanged || producerCallbackChanged) {
                        if (!updateProducerRelays()) return
                    } else {
                        producerBindingsCommitted = true
                    }
                }
                else -> {
                    producerTopologyPendingCallback?.invoke(producerGeneration)
                    if (
                        RendererSafetyState.hasUnconfirmedTeardown() ||
                        !rebuildLayers(newTopology)
                    ) {
                        // The latest desired phase/generation remains in the fields above. Old
                        // producers have already had their relays disabled and stop requested;
                        // retry only after the process-wide lease is clear.
                        beginDeferredTopologyApply()
                        return
                    }
                }
            }
            if (phaseChanged || generationChanged) {
                startNanos = nowNanos
                lastTransformNanos = 0L
            }
            updateRuntimeControls(
                newPhase,
                restartLoadProfile = phaseChanged || generationChanged,
            )
            // The topology is externally meaningful only after every child and its initial
            // runtime controls have been installed successfully.
            topology = newTopology
            if (newPhase.motion == MotionProfile.CAPACITY_TILES) {
                // Publish the non-occluded crop before a very fast producer can satisfy the
                // controller's all-first-buffer gate under the previous/full-overlap geometry.
                applyTransformsAt(newPhase, System.nanoTime())
                if (!capacityGeometryReady) {
                    producerTopologyPendingCallback?.invoke(producerGeneration)
                }
            } else {
                applyTransformsAt(newPhase, System.nanoTime())
            }
            if (!commitProducerControlRevision()) return
            scheduleFrame()
                },
                rollback = { failure -> rollbackRendererMutationIfNeeded(failure) },
            )
            if (!mutationCompleted) {
                producerRuntimeFailureCallback?.invoke(
                    producerGeneration,
                    "Renderer configure transaction failed",
                )
            }
        } finally {
            expectedPublishSuppression.end(suppressionToken)
            if (
                !expectedPublishSuppression.isSuppressed() &&
                deferredTopologyApply == null
            ) {
                publishExpectedProducers()
            }
        }
    }

    fun release(): Boolean {
        val releasedGeneration = producerGeneration
        val removalCallback = stageRemovalCallback
        var terminalFailure: Throwable? = null
        var stopped = false
        try {
            removeCallbacks(deferredTopologyApplyRunnable)
        } catch (failure: Throwable) {
            terminalFailure = mergeFailurePreservingFatal(terminalFailure, failure)
        }
        deferredTopologyApply = null
        try {
            Choreographer.getInstance().removeFrameCallback(this)
        } catch (failure: Throwable) {
            terminalFailure = mergeFailurePreservingFatal(terminalFailure, failure)
        }
        frameCallbackPosted = false
        try {
            stopped = removeAndReleaseChildren()
        } catch (failure: Throwable) {
            terminalFailure = mergeFailurePreservingFatal(terminalFailure, failure)
        }
        terminalFailure = clearChildReferencesBestEffort(terminalFailure)
        if (releasedGeneration != Long.MIN_VALUE) {
            try {
                removalCallback?.invoke(releasedGeneration, stopped)
            } catch (failure: Throwable) {
                terminalFailure = mergeFailurePreservingFatal(terminalFailure, failure)
            }
        }
        clearReleasedRendererState()
        terminalFailure?.let { throw it }
        return stopped
    }

    private fun clearReleasedRendererState() {
        expectedPublishSuppression.invalidate()
        advanceExpectedPublicationMutationEpoch()
        phase = null
        capacityGeometryReady = false
        selectedMediaUri = null
        videoDecoderSelection = null
        topology = null
        producerFrameCallback = null
        expectedProducersCallback = null
        producerTopologyPendingCallback = null
        layerGeometryRequestedCallback = null
        layerGeometryAppliedCallback = null
        producerTeardownFailureCallback = null
        producerRuntimeFailureCallback = null
        stageRemovalCallback = null
        producerGeneration = Long.MIN_VALUE
        requestedProducerControlRevision = 0L
        committedProducerControlRevision = 0L
        failedTopologyGeneration = Long.MIN_VALUE
        producerBindingsCommitted = false
        expectedTopologyDirty = true
        forceExpectedProducerRepublish = false
        lastPublishedExpectedGeneration = Long.MIN_VALUE
        lastPublishedProducerIds = emptySet()
        lastPublishedExpectedCallback = null
        resetLayerGeometryTracking()
    }

    private fun clearChildReferencesBestEffort(initialFailure: Throwable?): Throwable? {
        var terminalFailure = initialFailure
        try {
            producerRelays.values.forEach(ProducerFrameRelay::disable)
        } catch (failure: Throwable) {
            terminalFailure = mergeFailurePreservingFatal(terminalFailure, failure)
        }
        try {
            producerRelays.clear()
        } catch (failure: Throwable) {
            terminalFailure = mergeFailurePreservingFatal(terminalFailure, failure)
        }
        try {
            animatedChildren.clear()
        } catch (failure: Throwable) {
            terminalFailure = mergeFailurePreservingFatal(terminalFailure, failure)
        }
        try {
            childCropBounds.clear()
        } catch (failure: Throwable) {
            terminalFailure = mergeFailurePreservingFatal(terminalFailure, failure)
        }
        try {
            removeAllViews()
        } catch (failure: Throwable) {
            terminalFailure = mergeFailurePreservingFatal(terminalFailure, failure)
        }
        return terminalFailure
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        val current = phase
        if (
            current != null &&
            animatedChildren.isEmpty() &&
            failedTopologyGeneration != producerGeneration
        ) {
            advanceExpectedPublicationMutationEpoch()
            val mutationCompleted = runFailClosedRendererMutation(
                mutation = {
                    val desiredTopology = topologyFor(
                        current,
                        selectedMediaUri,
                        videoDecoderSelection,
                    )
                    producerTopologyPendingCallback?.invoke(producerGeneration)
                    if (
                        RendererSafetyState.hasUnconfirmedTeardown() ||
                        !rebuildLayers(desiredTopology)
                    ) {
                        beginDeferredTopologyApply()
                    } else {
                        if (current.motion == MotionProfile.CAPACITY_TILES) {
                            applyTransformsAt(current, System.nanoTime())
                            if (!capacityGeometryReady) {
                                producerTopologyPendingCallback?.invoke(producerGeneration)
                            }
                        } else {
                            applyTransformsAt(current, System.nanoTime())
                        }
                        if (commitProducerControlRevision()) {
                            publishExpectedProducers()
                        }
                    }
                },
                rollback = { failure -> rollbackRendererMutationIfNeeded(failure) },
            )
            if (!mutationCompleted) {
                producerRuntimeFailureCallback?.invoke(
                    producerGeneration,
                    "Renderer attach transaction failed",
                )
            }
        } else {
            val failedGeneration = producerGeneration
            val mutationCompleted = runFailClosedRendererMutation(
                mutation = ::scheduleFrame,
                rollback = { failure -> rollbackRendererMutationIfNeeded(failure) },
            )
            if (!mutationCompleted) {
                producerRuntimeFailureCallback?.invoke(
                    failedGeneration,
                    "Renderer attach frame scheduling failed",
                )
            }
        }
    }

    override fun onDetachedFromWindow() {
        attached = false
        val releasedGeneration = producerGeneration
        val removalCallback = stageRemovalCallback
        var terminalFailure: Throwable? = null
        var stopped = false
        try {
            removeCallbacks(deferredTopologyApplyRunnable)
        } catch (failure: Throwable) {
            terminalFailure = mergeFailurePreservingFatal(terminalFailure, failure)
        }
        deferredTopologyApply = null
        try {
            Choreographer.getInstance().removeFrameCallback(this)
        } catch (failure: Throwable) {
            terminalFailure = mergeFailurePreservingFatal(terminalFailure, failure)
        }
        frameCallbackPosted = false
        try {
            stopped = removeAndReleaseChildren()
        } catch (failure: Throwable) {
            terminalFailure = mergeFailurePreservingFatal(terminalFailure, failure)
        }
        terminalFailure = clearChildReferencesBestEffort(terminalFailure)
        if (releasedGeneration != Long.MIN_VALUE) {
            try {
                removalCallback?.invoke(releasedGeneration, stopped)
            } catch (failure: Throwable) {
                terminalFailure = mergeFailurePreservingFatal(terminalFailure, failure)
            }
        }
        clearReleasedRendererState()
        try {
            super.onDetachedFromWindow()
        } catch (failure: Throwable) {
            terminalFailure = mergeFailurePreservingFatal(terminalFailure, failure)
        }
        terminalFailure?.let { throw it }
    }

    override fun doFrame(frameTimeNanos: Long) {
        frameCallbackPosted = false
        val failedGeneration = producerGeneration
        val mutationCompleted = runFailClosedRendererMutation(
            mutation = {
                val current = phase
                if (
                    attached &&
                    current != null &&
                    topology != null &&
                    deferredTopologyApply == null
                ) {
                    acknowledgeLayerGeometryAfterFrame()
                    val safeFps = current.producerFps
                        .takeIf { it.isFinite() }
                        ?.coerceIn(1f, 120f)
                        ?: 60f
                    val phaseFraction = currentLayerSizePhaseFraction(
                        phase = current,
                        frameTimeNanos = frameTimeNanos,
                    )
                    val dynamicLayerSize = current.layerSizeProfile.changesOverTime
                    val transformInterval = layerTransformIntervalNanos(
                        producerFps = safeFps,
                        dynamicLayerSize = dynamicLayerSize,
                    )
                    if (shouldApplyLayerTransform(
                            elapsedSinceLastNanos = frameTimeNanos - lastTransformNanos,
                            transformIntervalNanos = transformInterval,
                            dynamicLayerSize = dynamicLayerSize,
                            phaseFraction = phaseFraction,
                            lastAppliedPhaseFraction = lastAppliedLayerSizeFraction,
                        )
                    ) {
                        applyTransformsAt(current, frameTimeNanos)
                        lastTransformNanos = frameTimeNanos
                    }
                    if (
                        (
                            current.motion != MotionProfile.STATIC &&
                                current.motion != MotionProfile.CAPACITY_TILES
                        ) ||
                        current.alphaOverlap ||
                        (
                            current.layerSizeProfile.changesOverTime &&
                                phaseFraction < 1f
                            ) ||
                        pendingLayerGeometryRevision > 0L
                    ) {
                        scheduleFrame()
                    }
                }
            },
            rollback = { failure -> rollbackRendererMutationIfNeeded(failure) },
        )
        if (!mutationCompleted) {
            producerRuntimeFailureCallback?.invoke(
                failedGeneration,
                "Renderer frame transaction failed",
            )
        }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width == oldWidth && height == oldHeight) return
        advanceExpectedPublicationMutationEpoch()
        val failedGeneration = producerGeneration
        val mutationCompleted = runFailClosedRendererMutation(
            mutation = {
                // The previous revision names the old stage dimensions and cannot be acknowledged.
                invalidateLayerGeometryKey()
                val current = phase
                if (current?.motion == MotionProfile.CAPACITY_TILES && topology != null) {
                    applyTransformsAt(current, System.nanoTime())
                    if (capacityGeometryReady) {
                        publishExpectedProducers()
                    } else {
                        producerTopologyPendingCallback?.invoke(producerGeneration)
                    }
                    scheduleFrame()
                } else if (
                    current != null &&
                    topology != null &&
                    deferredTopologyApply == null
                ) {
                    applyTransformsAt(current, System.nanoTime())
                    scheduleFrame()
                }
            },
            rollback = { failure -> rollbackRendererMutationIfNeeded(failure) },
        )
        if (!mutationCompleted) {
            producerRuntimeFailureCallback?.invoke(
                failedGeneration,
                "Renderer size transaction failed",
            )
        }
    }

    private fun scheduleFrame() {
        if (!attached || frameCallbackPosted || phase == null) return
        try {
            Choreographer.getInstance().postFrameCallback(this)
            frameCallbackPosted = true
        } catch (failure: Throwable) {
            frameCallbackPosted = false
            throw failure
        }
    }

    private fun advanceExpectedPublicationMutationEpoch() {
        expectedPublicationMutationEpoch =
            nextRendererMutationEpoch(expectedPublicationMutationEpoch)
    }

    private fun topologyFor(
        current: PhaseSpec,
        selectedMedia: Uri?,
        selectedDecoder: VideoDecoderSelection?,
    ): LayerTopology =
        LayerTopology(
            backend = current.backend,
            pixelRoute = current.pixelRoute,
            bufferSize = current.bufferSize,
            bufferPresentation = current.bufferPresentation,
            layerOrientation = current.layerOrientation,
            activeLayers = current.activeLayers,
            includeGlLayer = current.includeGlLayer,
            alphaOverlap = current.alphaOverlap,
            mediaUri = selectedMedia,
            videoDecoderSelection = selectedDecoder,
        )

    private fun rebuildLayers(): Boolean {
        val current = phase ?: return false
        return rebuildLayers(
            topologyFor(current, selectedMediaUri, videoDecoderSelection),
        )
    }

    private fun rebuildLayers(desiredTopology: LayerTopology): Boolean {
        producerBindingsCommitted = false
        expectedTopologyDirty = true
        invalidateLayerGeometryKey()
        if (!removeAndReleaseChildren()) {
            topology = null
            return false
        }
        if (RendererSafetyState.hasUnconfirmedTeardown()) {
            topology = null
            return false
        }
        val current = phase ?: return false
        val installed = installRenderChildren { install ->
            when (current.backend) {
                LayerBackend.FLATTENED_TEXTURE -> {
                    val relay = newProducerRelay(primary = true)
                    install(
                        RenderChild(
                            view = MultiLayerTextureView(
                                context = context,
                                logicalLayerCount = current.activeLayers,
                                targetFps = current.producerFps,
                                captureFrameCommit = relay::captureCallback,
                                onTeardownFailure = teardownFailureCallbackFor(relay),
                                onRuntimeFailure = runtimeFailureCallbackFor(relay),
                                onProducerLifecycleTransition =
                                    producerLifecycleCallbackFor(relay),
                            ),
                            relay = relay,
                        ),
                    )
                }

                LayerBackend.INDEPENDENT_SURFACES,
                LayerBackend.MIXED_SURFACE_TEXTURE,
                -> {
                    repeat(current.activeLayers.coerceIn(1, MAX_LAYERS)) { index ->
                        install(createLayer(current, index))
                    }
                }
            }
            updateRuntimeControls(current, restartLoadProfile = true)
        }
        if (!installed) {
            topology = null
            return false
        }
        startNanos = System.nanoTime()
        lastTransformNanos = 0L
        topology = desiredTopology
        producerBindingsCommitted = true
        scheduleFrame()
        return true
    }

    private fun createLayer(current: PhaseSpec, index: Int): RenderChild {
        val primary = index == 0
        val relay = newProducerRelay(primary)
        val view = if (current.includeGlLayer && index == current.activeLayers - 1) {
            StressGlSurfaceView(
                context = context,
                complexity = current.workloads.gpu,
                targetFps = current.producerFps,
                mediaOverlay = !primary,
                captureFrameCommit = relay::captureCallback,
                onTeardownFailure = teardownFailureCallbackFor(relay),
                onRuntimeFailure = runtimeFailureCallbackFor(relay),
                onProducerLifecycleTransition = producerLifecycleCallbackFor(relay),
            )
        } else {
            val selectedDecoder = videoDecoderSelection
            val selectedMedia = selectedMediaUri
            val wantsSelectedDecoder =
                primary &&
                    selectedMedia != null &&
                    current.pixelRoute.usesSelectedMediaDecoder()
            if (
                wantsSelectedDecoder &&
                selectedDecoder != null &&
                selectedDecoder.mediaUri == selectedMedia
            ) {
                VideoSurfaceView(
                    context = context,
                    selection = selectedDecoder,
                    targetFps = current.producerFps,
                    captureFrameCommit = relay::captureCallback,
                    onTeardownFailure = teardownFailureCallbackFor(relay),
                    onRuntimeFailure = runtimeFailureCallbackFor(relay),
                    onProducerLifecycleTransition = producerLifecycleCallbackFor(relay),
                )
            } else if (wantsSelectedDecoder) {
                // A selected source must never silently turn into the procedural YUV proxy if its
                // immutable hardware-decoder binding is missing or belongs to another URI.
                UnavailableDecoderSurfaceView(
                    context = context,
                    onUnavailable = {
                        runtimeFailureCallbackFor(relay).invoke(
                            "Selected decoder binding is unavailable",
                        )
                    },
                )
            } else {
                val useTexture =
                    current.backend == LayerBackend.MIXED_SURFACE_TEXTURE && index % 3 == 2
                if (useTexture) {
                    PatternTextureView(
                        context = context,
                        layerIndex = index,
                        targetFps = current.producerFps,
                        logicalYuv = current.pixelRoute in
                            setOf(PixelRoute.YUV_420, PixelRoute.P010),
                        captureFrameCommit = relay::captureCallback,
                        onTeardownFailure = teardownFailureCallbackFor(relay),
                        onRuntimeFailure = runtimeFailureCallbackFor(relay),
                        onProducerLifecycleTransition = producerLifecycleCallbackFor(relay),
                    )
                } else {
                    PatternSurfaceView(
                        context = context,
                        layerIndex = index,
                        targetFps = current.producerFps,
                        pixelRoute = current.pixelRoute,
                        bufferSize = if (primary) current.bufferSize else BufferSize.DISPLAY,
                        alphaSurface = current.alphaOverlap,
                        captureFrameCommit = relay::captureCallback,
                        onTeardownFailure = teardownFailureCallbackFor(relay),
                        onRuntimeFailure = runtimeFailureCallbackFor(relay),
                        onProducerLifecycleTransition = producerLifecycleCallbackFor(relay),
                    )
                }
            }
        }
        return RenderChild(view = view, relay = relay)
    }

    private fun addRenderChild(
        view: View,
        index: Int = animatedChildren.size,
    ) {
        childCropBounds[view] = Rect()
        val params = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        addView(view, index, params)
        animatedChildren.add(index, view)
    }

    /**
     * Owns each constructed child before addView() can synchronously invoke lifecycle code.
     * Any Throwable rolls the complete prefix back with one shared producer-drain deadline.
     */
    private fun installRenderChildren(
        build: (RenderChildInstaller) -> Unit,
    ): Boolean = try {
        buildRendererTransaction<RenderChild, Unit>(
            resourceCapacity = MAX_LAYERS,
            build = { register ->
                build(RenderChildInstaller(register))
            },
            rollback = { owned ->
                rollbackOwnedRendererChildren(
                    owned = owned,
                    disableRelay = { child ->
                        // Registration intentionally precedes the fallible map insertion/addView
                        // steps. Use the transaction-owned identity here: a child which never reached
                        // producerRelays must still detach its late native/frame callbacks.
                        child.relay.disable()
                    },
                    retireChildren = { registeredPrefix ->
                        // Do not allocate a mapped list after an OutOfMemoryError. Only the newly
                        // owned prefix is retired, so pre-existing topology cannot be released twice.
                        retireRenderChildren(registeredPrefix, renderChildView)
                    },
                )
            },
        )
        true
    } catch (error: Throwable) {
        topology = null
        expectedTopologyDirty = true
        if (error is ThreadDeath || error is VirtualMachineError) throw error
        producerRuntimeFailureCallback?.invoke(
            producerGeneration,
            buildRuntimeFailureReason("Renderer topology transaction", error),
        )
        false
    }

    private inner class RenderChildInstaller(
        private val register: (RenderChild) -> Unit,
    ) {
        operator fun invoke(
            child: RenderChild,
            index: Int = animatedChildren.size,
        ) {
            // Register first: producerRelays assignment, addView(), and its callbacks are all
            // fallible, and rollback must own this candidate before any of them execute.
            register(child)
            producerRelays[child.view] = child.relay
            addRenderChild(child.view, index)
        }
    }

    private fun retireRenderChildren(children: List<View>): Boolean =
        retireRenderChildren(children, viewIdentity)

    private fun retireCurrentRenderChildrenWithoutSnapshot(): Boolean =
        retireRenderChildren(
            children = animatedChildren,
            viewOf = viewIdentity,
            sourceIsAnimatedChildren = true,
        )

    private fun <T> retireRenderChildren(
        children: List<T>,
        viewOf: (T) -> View,
        sourceIsAnimatedChildren: Boolean = false,
    ): Boolean {
        if (children.isEmpty()) return true
        val childCount = children.size
        var allStopped = true
        var firstFatal: Throwable? = null
        var firstCleanupError: Throwable? = null
        var firstCleanupOperation: String? = null

        // Revoke all frame/failure callbacks first, then request every producer to stop before
        // joining any one of them. This preserves parallel drain inside the shared deadline.
        var index = 0
        while (index < childCount) {
            val child = viewOf(children[index])
            try {
                producerRelays[child]?.disable()
            } catch (error: Throwable) {
                allStopped = false
                if (firstCleanupError == null) {
                    firstCleanupError = error
                    firstCleanupOperation = "relay.disable"
                }
                if (
                    firstFatal == null &&
                    (error is ThreadDeath || error is VirtualMachineError)
                ) {
                    firstFatal = error
                }
            }
            index++
        }
        index = 0
        while (index < childCount) {
            val child = viewOf(children[index])
            try {
                requestStopRenderChild(child)
            } catch (error: Throwable) {
                allStopped = false
                if (firstCleanupError == null) {
                    firstCleanupError = error
                    firstCleanupOperation = "producer.requestStop"
                }
                if (
                    firstFatal == null &&
                    (error is ThreadDeath || error is VirtualMachineError)
                ) {
                    firstFatal = error
                }
            }
            index++
        }
        val stopDeadlineNanos = producerDrainDeadlineNanos()
        index = 0
        while (index < childCount) {
            val child = viewOf(children[index])
            try {
                if (!releaseRenderChild(child, stopDeadlineNanos)) allStopped = false
            } catch (error: Throwable) {
                allStopped = false
                if (firstCleanupError == null) {
                    firstCleanupError = error
                    firstCleanupOperation = "producer.release"
                }
                if (
                    firstFatal == null &&
                    (error is ThreadDeath || error is VirtualMachineError)
                ) {
                    firstFatal = error
                }
            }
            index++
        }
        index = 0
        while (index < childCount) {
            val child = viewOf(children[index])
            try {
                producerRelays.remove(child)?.disable()
            } catch (error: Throwable) {
                allStopped = false
                if (firstCleanupError == null) {
                    firstCleanupError = error
                    firstCleanupOperation = "relay.remove"
                }
                if (
                    firstFatal == null &&
                    (error is ThreadDeath || error is VirtualMachineError)
                ) {
                    firstFatal = error
                }
            }
            if (!sourceIsAnimatedChildren) animatedChildren.remove(child)
            childCropBounds.remove(child)
            try {
                if (child.parent === this) removeView(child)
            } catch (error: Throwable) {
                allStopped = false
                if (firstCleanupError == null) {
                    firstCleanupError = error
                    firstCleanupOperation = "view.remove"
                }
                if (
                    firstFatal == null &&
                    (error is ThreadDeath || error is VirtualMachineError)
                ) {
                    firstFatal = error
                }
            }
            index++
        }
        if (sourceIsAnimatedChildren) animatedChildren.clear()
        firstCleanupError?.let { cleanupError ->
            try {
                RendererSafetyState.markCleanupFailure(
                    component = "renderer child rollback",
                    detail = "$firstCleanupOperation=${cleanupError.javaClass.simpleName}",
                )
            } catch (reportError: Throwable) {
                if (
                    firstFatal == null &&
                    (reportError is ThreadDeath || reportError is VirtualMachineError)
                ) {
                    firstFatal = reportError
                }
            }
        }
        firstFatal?.let { throw it }
        return allStopped
    }

    private fun canReconcileLayerCount(
        previous: LayerTopology?,
        next: LayerTopology,
    ): Boolean = previous != null &&
        previous.activeLayers != next.activeLayers &&
        previous.includeGlLayer == next.includeGlLayer &&
        (
            !next.includeGlLayer ||
                (previous.activeLayers > 1 && next.activeLayers > 1)
            ) &&
        previous.backend == next.backend &&
        next.backend != LayerBackend.FLATTENED_TEXTURE &&
        previous.pixelRoute == next.pixelRoute &&
        previous.bufferSize == next.bufferSize &&
        previous.bufferPresentation == next.bufferPresentation &&
        previous.layerOrientation == next.layerOrientation &&
        previous.alphaOverlap == next.alphaOverlap &&
        previous.mediaUri == next.mediaUri &&
        previous.videoDecoderSelection == next.videoDecoderSelection

    private fun reconcileLayerCount(current: PhaseSpec): Boolean {
        expectedTopologyDirty = true
        // Layer count is part of the geometry revision identity. Do not let an in-flight
        // acknowledgment from the old physical child set survive reconciliation.
        invalidateLayerGeometryKey()
        val targetCount = current.activeLayers.coerceIn(1, MAX_LAYERS)
        val keepsGlTail = current.includeGlLayer
        if (keepsGlTail && animatedChildren.lastOrNull() !is StressGlSurfaceView) {
            return rebuildLayers()
        }
        val removedChildren = ArrayList<View>(
            (animatedChildren.size - targetCount).coerceAtLeast(0),
        )
        while (animatedChildren.size > targetCount) {
            val removeIndex = if (keepsGlTail) {
                animatedChildren.lastIndex - 1
            } else {
                animatedChildren.lastIndex
            }
            removedChildren += animatedChildren.removeAt(removeIndex)
        }
        if (removedChildren.isNotEmpty()) {
            if (!retireRenderChildren(removedChildren)) {
                return false
            }
        }
        if (animatedChildren.size < targetCount) {
            val installed = installRenderChildren { install ->
                while (animatedChildren.size < targetCount) {
                    val insertIndex = if (keepsGlTail) {
                        animatedChildren.lastIndex
                    } else {
                        animatedChildren.size
                    }
                    install(createLayer(current, insertIndex), insertIndex)
                }
                updateRuntimeControls(current, restartLoadProfile = false)
            }
            if (!installed) return false
        }
        return true
    }

    private fun removeAndReleaseChildren(): Boolean {
        producerBindingsCommitted = false
        val stopped = retireCurrentRenderChildrenWithoutSnapshot()
        // Also clear any constructor-created relay that never reached animatedChildren.
        producerRelays.values.forEach(ProducerFrameRelay::disable)
        producerRelays.clear()
        animatedChildren.clear()
        childCropBounds.clear()
        removeAllViews()
        return stopped
    }

    private fun replacePrimaryProducer(current: PhaseSpec): Boolean {
        expectedTopologyDirty = true
        invalidateLayerGeometryKey()
        if (current.backend == LayerBackend.FLATTENED_TEXTURE) {
            return rebuildLayers()
        }
        val oldPrimary = animatedChildren.firstOrNull() ?: run {
            return rebuildLayers()
        }
        producerRelays[oldPrimary]?.disable()
        requestStopRenderChild(oldPrimary)
        val stopped = releaseRenderChild(oldPrimary, producerDrainDeadlineNanos())
        producerRelays.remove(oldPrimary)
        animatedChildren.removeAt(0)
        removeView(oldPrimary)
        if (!stopped) {
            return false
        }
        return installRenderChildren { install ->
            install(createLayer(current, index = 0), 0)
            updateRuntimeControls(current, restartLoadProfile = false)
        }
    }

    private fun primaryRequiresReplacement(): Boolean =
        animatedChildren.firstOrNull() is VideoSurfaceView

    private fun newProducerRelay(primary: Boolean): ProducerFrameRelay =
        ProducerFrameRelay(
            producerId = allocateProducerId(),
            generation = producerGeneration,
            primary = primary,
            controlRevision = committedProducerControlRevision,
            callback = producerFrameCallback,
        )

    private fun allocateProducerId(): Long {
        nextProducerId = if (nextProducerId == Long.MAX_VALUE) 0L else nextProducerId + 1L
        return nextProducerId
    }

    private fun teardownFailureCallbackFor(relay: ProducerFrameRelay): () -> Unit = {
        relay.activeGenerationForFailure()?.let { generation ->
            producerTeardownFailureCallback?.invoke(generation)
        }
    }

    private fun runtimeFailureCallbackFor(relay: ProducerFrameRelay): (String) -> Unit = { reason ->
        relay.captureFailureDispatch()?.let { dispatch ->
            val boundedReason = reason
                .trim()
                .ifEmpty { "Producer runtime failure" }
                .take(MAX_RUNTIME_FAILURE_REASON_CHARS)
            check(post {
                if (relay.isFailureDispatchCurrent(dispatch)) {
                    producerRuntimeFailureCallback?.invoke(dispatch.generation, boundedReason)
                }
            }) {
                "Producer runtime-failure dispatch was rejected"
            }
        }
    }

    private fun producerLifecycleCallbackFor(relay: ProducerFrameRelay): () -> Unit = {
        relay.captureFailureDispatch()?.let { dispatch ->
            if (relay.isFailureDispatchCurrent(dispatch)) {
                handlePhysicalProducerLifecycleTransition(relay, dispatch)
            }
        }
    }

    /**
     * A framework Surface/BufferQueue may be destroyed and recreated without a controller
     * generation change. Invalidate every readiness/evidence source before the child starts its
     * replacement producer; frames emitted before the fresh geometry/expected-set publication are
     * consequently ignored by the controller's first-buffer gate.
     */
    private fun handlePhysicalProducerLifecycleTransition(
        relay: ProducerFrameRelay,
        dispatch: ProducerFailureDispatch,
    ) {
        if (
            !relay.isFailureDispatchCurrent(dispatch) ||
            dispatch.generation != producerGeneration ||
            topology == null ||
            deferredTopologyApply != null ||
            failedTopologyGeneration == dispatch.generation
        ) {
            return
        }
        val failedGeneration = dispatch.generation
        val mutationCompleted = runFailClosedRendererMutation(
            mutation = {
                check(
                    relay.isFailureDispatchCurrent(dispatch) &&
                        dispatch.generation == producerGeneration,
                ) {
                    "Physical producer lifecycle identity changed during invalidation"
                }
                advanceExpectedPublicationMutationEpoch()
                expectedTopologyDirty = true
                forceExpectedProducerRepublish = true
                invalidateLayerGeometryKey()
                producerTopologyPendingCallback?.invoke(dispatch.generation)
                phase?.let { current ->
                    applyTransformsAt(current, System.nanoTime())
                    scheduleFrame()
                }
            },
            rollback = { failure -> rollbackRendererMutationIfNeeded(failure) },
        )
        if (!mutationCompleted) {
            producerRuntimeFailureCallback?.invoke(
                failedGeneration,
                "Physical producer lifecycle transaction failed",
            )
        }
    }

    private fun updateProducerRelays(): Boolean {
        producerBindingsCommitted = false
        val transaction = try {
            ProducerRelayBindingTransaction.prepare(
                relays = producerRelays.values,
                generation = producerGeneration,
                controlRevision = committedProducerControlRevision,
                callback = producerFrameCallback,
            )
        } catch (failure: Throwable) {
            val terminal = rollbackProducerRelayPublication(
                transaction = null,
                primaryFailure = failure,
            )
            if (terminal is Error) throw terminal
            producerRuntimeFailureCallback?.invoke(
                producerGeneration,
                "Renderer producer-binding prepare failed",
            )
            return false
        }
        try {
            check(transaction.validateAll()) {
                "Producer relay binding changed during binding update prepare"
            }
            check(transaction.commitAll()) {
                "Producer relay binding changed during binding update commit"
            }
            producerBindingsCommitted = true
            return true
        } catch (failure: Throwable) {
            transaction.cancelPrepared()
            val terminal = rollbackProducerRelayPublication(
                transaction = transaction,
                primaryFailure = failure,
            )
            if (terminal is Error) throw terminal
            producerRuntimeFailureCallback?.invoke(
                producerGeneration,
                "Renderer producer-binding commit failed",
            )
            return false
        }
    }

    /**
     * Swaps immutable completion tokens only after all child controls and View transforms have
     * been applied. A worker that captured the previous token before this publication cannot
     * report the new revision after its native draw/swap returns.
     */
    private fun commitProducerControlRevision(): Boolean {
        val requested = requestedProducerControlRevision
        if (requested == committedProducerControlRevision) return true
        val transaction = try {
            ProducerControlRevisionTransaction.prepare(
                relays = producerRelays.values,
                controlRevision = requested,
            )
        } catch (failure: Throwable) {
            val terminal = rollbackProducerControlRevisionPublication(
                transaction = null,
                primaryFailure = failure,
            )
            if (terminal is Error) throw terminal
            producerRuntimeFailureCallback?.invoke(
                producerGeneration,
                "Renderer producer-control prepare failed",
            )
            return false
        }
        try {
            check(transaction.validateAll()) {
                "Producer relay binding changed during control revision prepare"
            }
            check(transaction.commitAll()) {
                "Producer relay binding changed during control revision commit"
            }
            committedProducerControlRevision = requested
            return true
        } catch (failure: Throwable) {
            transaction.cancelPrepared()
            val terminal = rollbackProducerControlRevisionPublication(
                transaction = transaction,
                primaryFailure = failure,
            )
            if (terminal is Error) throw terminal
            producerRuntimeFailureCallback?.invoke(
                producerGeneration,
                "Renderer producer-control commit failed",
            )
            return false
        }
    }

    /**
     * A partial no-fail commit can only be interrupted by a fatal asynchronous failure or a stale
     * binding. Revoke every relay before invalidating evidence, then stop all owned producers under
     * the existing shared deadline. Never revive an old token that may already have been disabled.
     */
    private fun rollbackProducerControlRevisionPublication(
        transaction: ProducerRelayRevocationTransaction?,
        primaryFailure: Throwable,
    ): Throwable = rollbackProducerRelayPublication(transaction, primaryFailure)

    private fun rollbackProducerRelayPublication(
        transaction: ProducerRelayRevocationTransaction?,
        primaryFailure: Throwable,
    ): Throwable {
        var cleanupFailure: Throwable? = null
        try {
            Choreographer.getInstance().removeFrameCallback(this)
        } catch (failure: Throwable) {
            cleanupFailure = mergeFailurePreservingFatal(cleanupFailure, failure)
        }
        frameCallbackPosted = false
        advanceExpectedPublicationMutationEpoch()
        try {
            if (transaction != null) {
                transaction.revokeAll()
            } else {
                producerRelays.values.forEach(ProducerFrameRelay::disable)
            }
        } catch (failure: Throwable) {
            cleanupFailure = mergeFailurePreservingFatal(cleanupFailure, failure)
        }
        val failedGeneration = producerGeneration
        failedTopologyGeneration = failedGeneration
        producerBindingsCommitted = false
        expectedTopologyDirty = true
        topology = null
        invalidateLayerGeometryKey()
        try {
            producerTopologyPendingCallback?.invoke(failedGeneration)
        } catch (failure: Throwable) {
            cleanupFailure = mergeFailurePreservingFatal(cleanupFailure, failure)
        }
        val stopped = try {
            retireCurrentRenderChildrenWithoutSnapshot()
        } catch (failure: Throwable) {
            cleanupFailure = mergeFailurePreservingFatal(cleanupFailure, failure)
            false
        }
        try {
            producerRelays.values.forEach(ProducerFrameRelay::disable)
            producerRelays.clear()
            animatedChildren.clear()
            childCropBounds.clear()
            removeAllViews()
        } catch (failure: Throwable) {
            cleanupFailure = mergeFailurePreservingFatal(cleanupFailure, failure)
        }
        if (!stopped) {
            try {
                producerTeardownFailureCallback?.invoke(failedGeneration)
            } catch (failure: Throwable) {
                cleanupFailure = mergeFailurePreservingFatal(cleanupFailure, failure)
            }
        }
        return cleanupFailure?.let { mergeFailurePreservingFatal(primaryFailure, it) }
            ?: primaryFailure
    }

    private fun rollbackRendererMutationIfNeeded(failure: Throwable): Throwable =
        if (failedTopologyGeneration == producerGeneration) {
            failure
        } else {
            rollbackProducerRelayPublication(
                transaction = null,
                primaryFailure = failure,
            )
        }

    private fun beginDeferredTopologyApply() {
        producerBindingsCommitted = false
        expectedTopologyDirty = true
        topology = null
        if (animatedChildren.isNotEmpty()) {
            // This is only the short hand-off budget. A live thread is registered in the
            // process-wide lease registry by its release implementation and is polled below.
            removeAndReleaseChildren()
        }
        val nowMs = SystemClock.elapsedRealtime()
        val existing = deferredTopologyApply
        deferredTopologyApply = if (existing == null) {
            DeferredTopologyApply(
                generation = producerGeneration,
                deadlineMs = saturatingDeadline(nowMs, PRODUCER_RECOVERY_TIMEOUT_MS),
            )
        } else {
            existing.apply { generation = producerGeneration }
        }
        producerTopologyPendingCallback?.invoke(producerGeneration)
        scheduleDeferredTopologyApply()
    }

    private fun scheduleDeferredTopologyApply() {
        removeCallbacks(deferredTopologyApplyRunnable)
        check(postDelayed(deferredTopologyApplyRunnable, PRODUCER_RECOVERY_POLL_MS)) {
            "Renderer deferred topology scheduling was rejected"
        }
    }

    private fun continueDeferredTopologyApply() {
        val pending = deferredTopologyApply ?: return
        if (phase == null || producerGeneration == Long.MIN_VALUE) {
            cancelDeferredTopologyApply()
            return
        }
        val nowMs = SystemClock.elapsedRealtime()
        if (RendererSafetyState.hasUnconfirmedTeardown()) {
            val failedGeneration = pending.generation
            val mutationCompleted = runFailClosedRendererMutation(
                mutation = {
                    if (nowMs >= pending.deadlineMs) {
                        deferredTopologyApply = null
                        failedTopologyGeneration = pending.generation
                        producerTeardownFailureCallback?.invoke(pending.generation)
                    } else {
                        scheduleDeferredTopologyApply()
                    }
                },
                rollback = { failure -> rollbackRendererMutationIfNeeded(failure) },
            )
            if (!mutationCompleted) {
                producerRuntimeFailureCallback?.invoke(
                    failedGeneration,
                    "Renderer deferred recovery scheduling failed",
                )
            }
            return
        }

        // The desired phase/media/generation fields are latest-wins. Commit the complete relay
        // set atomically from the gate's point of view by suppressing publication until every
        // child has been installed.
        deferredTopologyApply = null
        advanceExpectedPublicationMutationEpoch()
        val suppressionToken = expectedPublishSuppression.begin()
        var publishCommittedTopology = false
        val mutationCompleted = try {
            runFailClosedRendererMutation(
                mutation = {
                    val desiredTopology = topologyFor(
                        checkNotNull(phase),
                        selectedMediaUri,
                        videoDecoderSelection,
                    )
                    if (!rebuildLayers(desiredTopology)) {
                        deferredTopologyApply = pending.apply { generation = producerGeneration }
                        if (SystemClock.elapsedRealtime() >= pending.deadlineMs) {
                            deferredTopologyApply = null
                            failedTopologyGeneration = pending.generation
                            producerTeardownFailureCallback?.invoke(pending.generation)
                        } else {
                            scheduleDeferredTopologyApply()
                        }
                        return@runFailClosedRendererMutation
                    }
                    val current = phase
                    if (current?.motion == MotionProfile.CAPACITY_TILES) {
                        applyTransformsAt(current, System.nanoTime())
                        if (!capacityGeometryReady) {
                            producerTopologyPendingCallback?.invoke(producerGeneration)
                        }
                    } else if (current != null) {
                        applyTransformsAt(current, System.nanoTime())
                    }
                    publishCommittedTopology = commitProducerControlRevision()
                },
                rollback = { failure -> rollbackRendererMutationIfNeeded(failure) },
            )
        } finally {
            expectedPublishSuppression.end(suppressionToken)
        }
        if (!mutationCompleted) {
            producerRuntimeFailureCallback?.invoke(
                producerGeneration,
                "Renderer deferred recovery transaction failed",
            )
            return
        }
        if (publishCommittedTopology) {
            publishExpectedProducers()
        }
    }

    private fun cancelDeferredTopologyApply() {
        removeCallbacks(deferredTopologyApplyRunnable)
        deferredTopologyApply = null
    }

    private fun publishExpectedProducers(): Boolean {
        if (
            expectedPublishSuppression.isSuppressed() ||
            !producerBindingsCommitted ||
            deferredTopologyApply != null ||
            failedTopologyGeneration == producerGeneration ||
            (
                phase?.motion == MotionProfile.CAPACITY_TILES &&
                    !capacityGeometryReady
            ) ||
            (
                !expectedTopologyDirty &&
                    !forceExpectedProducerRepublish
                )
        ) {
            return false
        }
        val failedGeneration = producerGeneration
        val published = runFailClosedProducerPublication(
            publication = { publishExpectedProducersUnchecked() },
            rollback = { failure ->
                rollbackProducerRelayPublication(
                    transaction = null,
                    primaryFailure = failure,
                )
            },
        )
        if (!published) {
            producerRuntimeFailureCallback?.invoke(
                failedGeneration,
                "Renderer expected-producer publication failed",
            )
        }
        return published
    }

    private fun publishExpectedProducersUnchecked() {
        val relayIdentities =
            ArrayList<ExpectedProducerRelayIdentity>(producerRelays.size)
        val expected = buildSet {
            producerRelays.forEach { (view, relay) ->
                relayIdentities += ExpectedProducerRelayIdentity(
                    view = view,
                    relay = relay,
                    binding = relay.captureFailureDispatch(),
                )
                add(relay.producerId)
            }
        }
        if (expected.isEmpty()) return
        val callback = expectedProducersCallback ?: run {
            // A forced recovery publication is the only acknowledgment that clears the
            // controller/FrameTracker pending latch. Preserve it until a callback really runs.
            if (!forceExpectedProducerRepublish) expectedTopologyDirty = false
            return
        }
        val publication = ExpectedProducerPublicationSnapshot(
            mutationEpoch = expectedPublicationMutationEpoch,
            generation = producerGeneration,
            callback = callback,
            expectedProducerIds = expected,
            relayIdentities = relayIdentities,
        )
        val sameAsLastPublication =
            publication.generation == lastPublishedExpectedGeneration &&
                publication.expectedProducerIds == lastPublishedProducerIds &&
                callback === lastPublishedExpectedCallback
        if (
            !shouldPublishExpectedProducerSet(
                bindingsCommitted = producerBindingsCommitted,
                expectedTopologyDirty = expectedTopologyDirty,
                forceRepublish = forceExpectedProducerRepublish,
                sameAsLastPublication = sameAsLastPublication,
            )
        ) {
            expectedTopologyDirty = false
            return
        }
        callback.invoke(publication.generation, publication.expectedProducerIds)
        if (!isExpectedProducerPublicationCurrent(publication)) {
            // A synchronous callback may release or reconfigure the stage. The nested transaction
            // owns all current dirty/force/bookkeeping fields; never let this stale callback clear
            // or overwrite them after it returns.
            return
        }
        expectedTopologyDirty = false
        // Clear only after callback completion. If callback.invoke throws, the forced retry stays
        // armed and a later valid configure/size pass can re-issue the exact same expected set.
        forceExpectedProducerRepublish = false
        lastPublishedExpectedGeneration = publication.generation
        lastPublishedProducerIds = publication.expectedProducerIds
        lastPublishedExpectedCallback = callback
    }

    private fun isExpectedProducerPublicationCurrent(
        publication: ExpectedProducerPublicationSnapshot,
    ): Boolean {
        val eligible =
            producerBindingsCommitted &&
                !expectedPublishSuppression.isSuppressed() &&
                deferredTopologyApply == null &&
                failedTopologyGeneration != producerGeneration &&
                (
                    phase?.motion != MotionProfile.CAPACITY_TILES ||
                        capacityGeometryReady
                    )
        if (
            !expectedProducerPublicationCommitIsCurrent(
                capturedMutationEpoch = publication.mutationEpoch,
                currentMutationEpoch = expectedPublicationMutationEpoch,
                capturedGeneration = publication.generation,
                currentGeneration = producerGeneration,
                callbackIdentityMatches = expectedProducersCallback === publication.callback,
                relayIdentityMatches = producerRelays.size == publication.relayIdentities.size,
                publicationStillEligible = eligible,
            )
        ) {
            return false
        }
        publication.relayIdentities.forEach { identity ->
            if (
                producerRelays[identity.view] !== identity.relay ||
                identity.relay.captureFailureDispatch() !== identity.binding
            ) {
                return false
            }
        }
        return true
    }

    private fun requestStopRenderChild(child: View) {
        when (child) {
            is PatternSurfaceView -> child.requestStop()
            is PatternTextureView -> child.requestStop()
            is MultiLayerTextureView -> child.requestStop()
            is VideoSurfaceView -> child.requestStop()
            is StressGlSurfaceView -> child.requestStopLab()
            is UnavailableDecoderSurfaceView -> child.requestStop()
        }
    }

    private fun releaseRenderChild(child: View, stopDeadlineNanos: Long): Boolean =
        when (child) {
            is PatternSurfaceView -> child.release(stopDeadlineNanos)
            is PatternTextureView -> child.release(stopDeadlineNanos)
            is MultiLayerTextureView -> child.release(stopDeadlineNanos)
            is VideoSurfaceView -> child.release(stopDeadlineNanos)
            is StressGlSurfaceView -> child.releaseLab(stopDeadlineNanos)
            is UnavailableDecoderSurfaceView -> true
            else -> true
        }

    private fun updateRuntimeControls(current: PhaseSpec, restartLoadProfile: Boolean) {
        animatedChildren.forEach {
            when (it) {
                is PatternSurfaceView -> it.setTargetFps(current.producerFps)
                is PatternTextureView -> it.setTargetFps(current.producerFps)
                is MultiLayerTextureView -> {
                    it.setTargetFps(current.producerFps)
                    it.setGpuLoad(
                        current.workloads.gpu,
                        current.workloads.shape,
                        restartLoadProfile,
                    )
                }
                is VideoSurfaceView -> it.setFrameRateHint(current.producerFps)
                is StressGlSurfaceView -> {
                    it.setTargetFps(current.producerFps)
                    it.setLoad(
                        current.workloads.gpu,
                        current.workloads.shape,
                        restartLoadProfile,
                    )
                }
            }
        }
    }

    private fun applyTransformsAt(current: PhaseSpec, frameTimeNanos: Long) {
        if (width <= 0 || height <= 0) return
        val cropStageOverflow =
            current.bufferPresentation == BufferPresentation.PIXEL_1_TO_1_CROP
        if (clipChildren != cropStageOverflow) {
            clipChildren = cropStageOverflow
            clipToPadding = cropStageOverflow
        }
        val motionElapsedNanos = elapsedSince(startNanos, frameTimeNanos)
        val desiredPhaseFraction = currentLayerSizePhaseFraction(current, frameTimeNanos)
        val phaseFraction = layerSizeFractionForGeometryCommit(
            desiredPhaseFraction = desiredPhaseFraction,
            lastAppliedPhaseFraction = lastAppliedLayerSizeFraction,
            dynamicLayerSize = current.layerSizeProfile.changesOverTime,
            geometryRevisionPending = pendingLayerGeometryRevision > 0L,
        )
        animateTransforms(
            current = current,
            time = (motionElapsedNanos.toDouble() / NANOS_PER_SECOND).toFloat(),
            phaseFraction = phaseFraction,
        )
        if (
            current.motion != MotionProfile.CAPACITY_TILES ||
            capacityGeometryReady
        ) {
            lastAppliedLayerSizeFraction = phaseFraction
            requestLayerGeometryCommit(current, phaseFraction)
        }
    }

    private fun requestLayerGeometryCommit(
        current: PhaseSpec,
        phaseFraction: Float,
    ) {
        if (producerGeneration == Long.MIN_VALUE || animatedChildren.isEmpty()) return
        val profileOrdinal = current.layerSizeProfile.ordinal
        val sample = layerGeometrySampleKey(current.layerSizeProfile, phaseFraction)
        if (
            layerGeometryKeyGeneration == producerGeneration &&
            layerGeometryKeyPhaseId == current.id &&
            layerGeometryKeyProfileOrdinal == profileOrdinal &&
            layerGeometryKeySample == sample &&
            layerGeometryKeyLayerCount == animatedChildren.size &&
            layerGeometryKeyWidth == width &&
            layerGeometryKeyHeight == height
        ) {
            return
        }
        if (pendingLayerGeometryRevision > 0L) {
            // Dynamic size is held at lastAppliedLayerSizeFraction until this revision is
            // acknowledged. Keep the in-flight revision stable and treat the controller clock as
            // a single latest-wins desired slot; the newest fraction is recomputed after ACK.
            return
        }
        layerGeometryKeyGeneration = producerGeneration
        layerGeometryKeyPhaseId = current.id
        layerGeometryKeyProfileOrdinal = profileOrdinal
        layerGeometryKeySample = sample
        layerGeometryKeyLayerCount = animatedChildren.size
        layerGeometryKeyWidth = width
        layerGeometryKeyHeight = height
        layerGeometryRevision =
            if (layerGeometryRevision == Long.MAX_VALUE) 1L else layerGeometryRevision + 1L
        pendingLayerGeometryRevision = layerGeometryRevision
        pendingLayerGeometryProfileOrdinal = profileOrdinal
        pendingLayerGeometryCoverageBit =
            current.layerSizeProfile.coverageBitAt(phaseFraction)
        pendingLayerGeometryAckFrames = LAYER_GEOMETRY_ACK_FRAME_COUNT
        layerGeometryRequestedCallback?.invoke(
            producerGeneration,
            pendingLayerGeometryRevision,
            pendingLayerGeometryProfileOrdinal,
        )
        scheduleFrame()
    }

    private fun acknowledgeLayerGeometryAfterFrame() {
        if (pendingLayerGeometryRevision <= 0L) return
        if (pendingLayerGeometryAckFrames > 1) {
            pendingLayerGeometryAckFrames--
            return
        }
        layerGeometryAppliedCallback?.invoke(
            producerGeneration,
            pendingLayerGeometryRevision,
            pendingLayerGeometryProfileOrdinal,
            pendingLayerGeometryCoverageBit,
        )
        pendingLayerGeometryRevision = 0L
        pendingLayerGeometryProfileOrdinal = -1
        pendingLayerGeometryCoverageBit = 0
        pendingLayerGeometryAckFrames = 0
        if (forceExpectedProducerRepublish) {
            check(publishExpectedProducers()) {
                "Fresh expected-producer publication was not committed after geometry ACK"
            }
        }
    }

    private fun resetLayerGeometryTracking() {
        layerGeometryRevision = 0L
        invalidateLayerGeometryKey()
    }

    private fun invalidateLayerGeometryKey() {
        layerGeometryKeyGeneration = Long.MIN_VALUE
        layerGeometryKeyPhaseId = null
        layerGeometryKeyProfileOrdinal = -1
        layerGeometryKeySample = -1
        layerGeometryKeyLayerCount = -1
        layerGeometryKeyWidth = -1
        layerGeometryKeyHeight = -1
        pendingLayerGeometryRevision = 0L
        pendingLayerGeometryProfileOrdinal = -1
        pendingLayerGeometryCoverageBit = 0
        pendingLayerGeometryAckFrames = 0
    }

    private fun syncLayerSizePhaseClock(
        phase: PhaseSpec,
        authoritativeElapsedMs: Long,
        anchorNanos: Long,
        force: Boolean,
    ) {
        val boundedElapsedMs = authoritativeElapsedMs
            .coerceAtLeast(0L)
            .coerceAtMost(phase.durationMs.coerceAtLeast(0L))
        if (!force && boundedElapsedMs == layerSizeAuthoritativeElapsedMs) return
        if (force || boundedElapsedMs < layerSizeAuthoritativeElapsedMs) {
            lastAppliedLayerSizeFraction = -1f
        }
        layerSizeAuthoritativeElapsedMs = boundedElapsedMs
        layerSizeElapsedAtAnchorNanos = millisecondsToNanosSaturated(boundedElapsedMs)
        layerSizeClockAnchorNanos = anchorNanos.coerceAtLeast(0L)
    }

    private fun currentLayerSizePhaseFraction(
        phase: PhaseSpec,
        frameTimeNanos: Long,
    ): Float = normalizedLayerSizePhaseFraction(
        elapsedNanos = anchoredLayerSizeElapsedNanos(
            elapsedAtAnchorNanos = layerSizeElapsedAtAnchorNanos,
            anchorNanos = layerSizeClockAnchorNanos,
            frameTimeNanos = frameTimeNanos,
            durationMs = phase.durationMs,
        ),
        durationMs = phase.durationMs,
    )

    private fun animateTransforms(
        current: PhaseSpec,
        time: Float,
        phaseFraction: Float,
    ) {
        if (width == 0 || height == 0) return
        if (current.motion == MotionProfile.CAPACITY_TILES) {
            capacityGeometryReady = applyCapacityTileGeometryAndTrackRecovery()
            if (!capacityGeometryReady) {
                producerTopologyPendingCallback?.invoke(producerGeneration)
            }
            return
        }
        val layerCount = animatedChildren.size
        var index = 0
        while (index < layerCount) {
            val child = animatedChildren[index]
            if (current.motion != MotionProfile.Z_ORDER_SWAP) child.translationZ = 0f
            applyFullStageCrop(child)
            val phaseOffset = index * 0.73f
            val wave = sin(time * 1.35f + phaseOffset)
            val wave2 = cos(time * 0.83f + phaseOffset * 1.7f)
            val profileSize = current.layerSizeProfile.normalizedSizeForLayer(
                layerIndex = index,
                layerCount = layerCount,
                phaseFraction = phaseFraction,
            )
            val profileScaleX = profileSize.widthScale
            val profileScaleY = profileSize.heightScale
            val decoderSelection = if (
                index == 0 &&
                current.pixelRoute.usesSelectedMediaDecoder()
            ) {
                videoDecoderSelection
            } else {
                null
            }
            val decoderQuarterTurn =
                decoderSelection?.expectedRotationDegrees == 90 ||
                    decoderSelection?.expectedRotationDegrees == 270
            val sourceWidth = when {
                decoderSelection != null && decoderQuarterTurn ->
                    decoderSelection.expectedVisibleHeightPx
                decoderSelection != null -> decoderSelection.expectedVisibleWidthPx
                index == 0 && current.bufferSize != BufferSize.DISPLAY ->
                    current.bufferSize.width
                else -> width
            }
            val sourceHeight = when {
                decoderSelection != null && decoderQuarterTurn ->
                    decoderSelection.expectedVisibleWidthPx
                decoderSelection != null -> decoderSelection.expectedVisibleHeightPx
                index == 0 && current.bufferSize != BufferSize.DISPLAY ->
                    current.bufferSize.height
                else -> height
            }
            val presentationScale = bufferPresentationScalePacked(
                stageWidth = width,
                stageHeight = height,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                presentation = current.bufferPresentation,
                orientation = current.layerOrientation,
            )
            val baseScaleX = profileScaleX * packedPresentationScaleX(presentationScale)
            val baseScaleY = profileScaleY * packedPresentationScaleY(presentationScale)
            val baseRotation = current.layerOrientation.degrees
            val profileTranslationX = layerProfileBaseTranslationX(
                stageWidth = width,
                layerIndex = index,
                layerCount = layerCount,
                widthScale = profileScaleX,
            )
            val profileTranslationY = layerProfileBaseTranslationY(
                stageHeight = height,
                layerIndex = index,
                layerCount = layerCount,
                heightScale = profileScaleY,
            )

            when (current.motion) {
                MotionProfile.STATIC -> {
                    child.scaleX = baseScaleX
                    child.scaleY = baseScaleY
                    child.translationX = profileTranslationX
                    child.translationY = profileTranslationY
                    child.rotation = baseRotation
                }

                MotionProfile.CAPACITY_TILES -> Unit

                MotionProfile.SCROLL -> {
                    child.scaleX = baseScaleX
                    child.scaleY = baseScaleY
                    child.translationX = profileTranslationX +
                        ((time * (80 + index * 13)) % (width * 1.6f)) -
                        width * 0.8f
                    child.translationY =
                        profileTranslationY + wave2 * height * 0.24f
                    child.rotation = baseRotation
                }

                MotionProfile.ZOOM_PAN -> {
                    val motionScale = if (
                        current.bufferPresentation == BufferPresentation.FIT &&
                        current.layerOrientation == LayerOrientation.ROTATION_90
                    ) {
                        // Keep a fixed-90° fit phase inside its letterbox while still exercising
                        // bounded zoom-out/return work.
                        0.72f + (wave + 1f) * 0.14f
                    } else {
                        0.72f + (wave + 1f) * 0.28f
                    }
                    child.scaleX = baseScaleX * motionScale
                    child.scaleY = baseScaleY * motionScale
                    child.translationX =
                        profileTranslationX + wave2 * width * 0.22f
                    child.translationY =
                        profileTranslationY + wave * height * 0.18f
                    child.rotation = baseRotation
                }

                MotionProfile.ROTATE -> {
                    child.scaleX = baseScaleX
                    child.scaleY = baseScaleY
                    child.translationX =
                        profileTranslationX + wave2 * width * 0.2f
                    child.translationY =
                        profileTranslationY + wave * height * 0.16f
                    child.rotation =
                        baseRotation +
                            (time * (17f + index * 2.2f) + index * 29f) % 360f
                }

                MotionProfile.PARALLAX -> {
                    child.scaleX = baseScaleX
                    child.scaleY = baseScaleY
                    child.translationX = profileTranslationX +
                        sin(time * (0.55f + index * 0.025f) + phaseOffset) *
                        width * 0.34f
                    child.translationY = profileTranslationY +
                        cos(time * (0.43f + index * 0.02f) + phaseOffset) *
                        height * 0.28f
                    child.rotation =
                        baseRotation + if (current.alphaOverlap) wave * 8f else 0f
                }

                MotionProfile.TRANSFORM_STORM -> {
                    child.scaleX =
                        baseScaleX * (0.58f + (wave2 + 1f) * 0.34f)
                    child.scaleY =
                        baseScaleY * (0.68f + (wave + 1f) * 0.27f)
                    child.translationX =
                        profileTranslationX + wave * width * 0.37f
                    child.translationY =
                        profileTranslationY + wave2 * height * 0.31f
                    child.rotation =
                        baseRotation + (time * (21f + index * 3.3f)) % 360f
                }

                MotionProfile.Z_ORDER_SWAP -> {
                    child.scaleX = baseScaleX
                    child.scaleY = baseScaleY
                    child.translationX =
                        profileTranslationX + wave * width * 0.3f
                    child.translationY =
                        profileTranslationY + wave2 * height * 0.24f
                    child.rotation = baseRotation + wave * 12f
                    child.translationZ =
                        (((time * 1.5f).toInt() + index) % layerCount).toFloat()
                }
            }
            if (
                current.bufferPresentation == BufferPresentation.FIT &&
                current.layerOrientation == LayerOrientation.ROTATION_90 &&
                current.motion != MotionProfile.ROTATE &&
                current.motion != MotionProfile.TRANSFORM_STORM &&
                current.motion != MotionProfile.Z_ORDER_SWAP
            ) {
                // Fixed-quarter-turn FIT phases keep the complete transformed child inside the
                // stage. Motion is bounded by the current letterbox slack after size-profile and
                // zoom scales, so 2K/4K/8K comparisons do not accidentally become crop tests.
                child.translationX = clampFitTranslation(
                    requested = child.translationX,
                    stageExtent = width.toFloat(),
                    contentExtent = height.toFloat() * abs(child.scaleY),
                )
                child.translationY = clampFitTranslation(
                    requested = child.translationY,
                    stageExtent = height.toFloat(),
                    contentExtent = width.toFloat() * abs(child.scaleX),
                )
            }
            child.alpha = if (current.alphaOverlap) {
                (0.58f + 0.38f * ((wave + 1f) * 0.5f)).coerceIn(0.15f, 1f)
            } else {
                1f
            }
            index++
        }
    }

    private fun applyFullStageCrop(child: View) {
        val crop = childCropBounds[child] ?: return
        if (
            crop.left != 0 ||
            crop.top != 0 ||
            crop.right != width ||
            crop.bottom != height
        ) {
            crop.set(0, 0, width, height)
            child.clipBounds = crop
        }
    }

    /**
     * Installs the controller-only capacity crops as one bounded topology operation. Geometry that
     * cannot give every physical producer at least one visible pixel is left unpublished so the
     * controller records the one-shot calibration as unavailable instead of crashing or sampling
     * a partially occluded candidate.
     */
    private fun applyCapacityTileGeometryAndTrackRecovery(): Boolean {
        val geometryWasReady = capacityGeometryReady
        val geometryReady = applyCapacityTileTransforms()
        if (
            capacityGeometryRequiresForcedRepublish(
                geometryWasReady = geometryWasReady,
                geometryReady = geometryReady,
            )
        ) {
            expectedTopologyDirty = true
            forceExpectedProducerRepublish = true
        }
        return geometryReady
    }

    private fun applyCapacityTileTransforms(): Boolean {
        val layerCount = animatedChildren.size
        if (layerCount <= 0) return false
        if (capacityTileBounds(width, height, layerCount, layerCount - 1) == null) return false
        var index = 0
        while (index < layerCount) {
            val tile = capacityTileBounds(width, height, layerCount, index) ?: return false
            val child = animatedChildren[index]
            child.scaleX = 1f
            child.scaleY = 1f
            child.translationX = 0f
            child.translationY = 0f
            child.rotation = 0f
            val crop = childCropBounds[child] ?: return false
            crop.set(tile.left, tile.top, tile.right, tile.bottom)
            child.clipBounds = crop
            index++
        }
        return true
    }

    companion object {
        const val MAX_LAYERS = 20
        private const val MAX_RUNTIME_FAILURE_REASON_CHARS = 240
    }

    private data class LayerTopology(
        val backend: LayerBackend,
        val pixelRoute: PixelRoute,
        val bufferSize: BufferSize,
        val bufferPresentation: BufferPresentation,
        val layerOrientation: LayerOrientation,
        val activeLayers: Int,
        val includeGlLayer: Boolean,
        val alphaOverlap: Boolean,
        val mediaUri: Uri?,
        val videoDecoderSelection: VideoDecoderSelection?,
    )

    private data class ExpectedProducerRelayIdentity(
        val view: View,
        val relay: ProducerFrameRelay,
        val binding: ProducerFailureDispatch?,
    )

    private data class ExpectedProducerPublicationSnapshot(
        val mutationEpoch: Long,
        val generation: Long,
        val callback: (Long, Set<Long>) -> Unit,
        val expectedProducerIds: Set<Long>,
        val relayIdentities: List<ExpectedProducerRelayIdentity>,
    )

    private data class DeferredTopologyApply(
        var generation: Long,
        var deadlineMs: Long,
    )

    private data class RenderChild(
        val view: View,
        val relay: ProducerFrameRelay,
    )
}

/**
 * Packs the pre-rotation View scales needed to preserve source pixels/aspect without allocating on
 * the frame path. FIT applies one uniform source-to-stage factor after accounting for a fixed 90°
 * orientation. PIXEL_1_TO_1_CROP keeps that factor at one and lets the stage clip overflow; safety
 * policy prevents a scaling profile/motion from changing that factor.
 */
internal fun bufferPresentationScalePacked(
    stageWidth: Int,
    stageHeight: Int,
    sourceWidth: Int,
    sourceHeight: Int,
    presentation: BufferPresentation,
    orientation: LayerOrientation,
): Long {
    if (stageWidth <= 0 || stageHeight <= 0 || sourceWidth <= 0 || sourceHeight <= 0) {
        return packPresentationScales(1f, 1f)
    }
    val orientedSourceWidth = if (orientation == LayerOrientation.ROTATION_90) {
        sourceHeight
    } else {
        sourceWidth
    }
    val orientedSourceHeight = if (orientation == LayerOrientation.ROTATION_90) {
        sourceWidth
    } else {
        sourceHeight
    }
    val sourceToDisplay = when (presentation) {
        BufferPresentation.FIT -> minOf(
            stageWidth.toDouble() / orientedSourceWidth.toDouble(),
            stageHeight.toDouble() / orientedSourceHeight.toDouble(),
        )

        BufferPresentation.PIXEL_1_TO_1_CROP -> 1.0
    }
    val scaleX =
        (sourceWidth.toDouble() * sourceToDisplay / stageWidth.toDouble()).toFloat()
    val scaleY =
        (sourceHeight.toDouble() * sourceToDisplay / stageHeight.toDouble()).toFloat()
    if (!scaleX.isFinite() || !scaleY.isFinite() || scaleX <= 0f || scaleY <= 0f) {
        return packPresentationScales(1f, 1f)
    }
    return packPresentationScales(scaleX, scaleY)
}

internal fun packedPresentationScaleX(packed: Long): Float =
    Float.fromBits((packed ushr 32).toInt())

internal fun packedPresentationScaleY(packed: Long): Float =
    Float.fromBits(packed.toInt())

private fun packPresentationScales(scaleX: Float, scaleY: Float): Long =
    (scaleX.toRawBits().toLong() shl 32) or
        (scaleY.toRawBits().toLong() and 0xffff_ffffL)

internal fun clampFitTranslation(
    requested: Float,
    stageExtent: Float,
    contentExtent: Float,
): Float {
    if (
        !requested.isFinite() ||
        !stageExtent.isFinite() ||
        !contentExtent.isFinite() ||
        stageExtent <= 0f ||
        contentExtent < 0f
    ) {
        return 0f
    }
    val slack = ((stageExtent - contentExtent) * 0.5f).coerceAtLeast(0f)
    return requested.coerceIn(-slack, slack)
}

private fun elapsedSince(startNanos: Long, frameTimeNanos: Long): Long {
    if (startNanos <= 0L || frameTimeNanos <= startNanos) return 0L
    val elapsed = frameTimeNanos - startNanos
    return if (elapsed >= 0L) elapsed else Long.MAX_VALUE
}

internal fun anchoredLayerSizeElapsedNanos(
    elapsedAtAnchorNanos: Long,
    anchorNanos: Long,
    frameTimeNanos: Long,
    durationMs: Long,
): Long {
    val durationNanos = millisecondsToNanosSaturated(durationMs.coerceAtLeast(0L))
    val safeBase = elapsedAtAnchorNanos.coerceIn(0L, durationNanos)
    val sinceAnchor = elapsedSince(anchorNanos, frameTimeNanos)
    val combined = if (safeBase > Long.MAX_VALUE - sinceAnchor) {
        Long.MAX_VALUE
    } else {
        safeBase + sinceAnchor
    }
    return combined.coerceAtMost(durationNanos)
}

internal fun layerGeometrySampleKey(
    profile: LayerSizeProfile,
    phaseFraction: Float,
): Int {
    val fraction = phaseFraction
        .takeIf(Float::isFinite)
        ?.coerceIn(0f, 1f)
        ?: 0f
    return when (profile) {
        LayerSizeProfile.GRADUAL_SMALL_TO_FULL -> when {
            fraction >= 1f -> GRADUAL_GEOMETRY_ENDPOINT_KEY
            fraction >= GRADUAL_MID_MIN_FRACTION -> GRADUAL_GEOMETRY_MID_KEY
            else -> GRADUAL_GEOMETRY_ORIGIN_KEY
        }
        LayerSizeProfile.ABRUPT_SMALL_FULL ->
            (fraction * ABRUPT_LAYER_SIZE_PROFILE_STEPS)
                .toInt()
                .coerceIn(0, ABRUPT_LAYER_SIZE_PROFILE_STEPS - 1)
        LayerSizeProfile.FULL_SCREEN,
        LayerSizeProfile.SMALL_UNIFORM,
        LayerSizeProfile.MIXED_SIZES,
        -> 0
    }
}

internal fun layerTransformIntervalNanos(
    producerFps: Float,
    dynamicLayerSize: Boolean,
): Long {
    val safeFps = producerFps
        .takeIf(Float::isFinite)
        ?.coerceIn(1f, 120f)
        ?: 60f
    val producerInterval = (NANOS_PER_SECOND / safeFps.toDouble()).toLong()
    return if (dynamicLayerSize) {
        minOf(producerInterval, LAYER_SIZE_TRANSFORM_CADENCE_NANOS)
    } else {
        producerInterval
    }
}

internal fun shouldApplyLayerTransform(
    elapsedSinceLastNanos: Long,
    transformIntervalNanos: Long,
    dynamicLayerSize: Boolean,
    phaseFraction: Float,
    lastAppliedPhaseFraction: Float,
): Boolean {
    val intervalDue =
        elapsedSinceLastNanos.coerceAtLeast(0L) >= transformIntervalNanos.coerceAtLeast(1L)
    val finalDynamicSampleDue =
        dynamicLayerSize &&
            phaseFraction.isFinite() &&
            phaseFraction >= 1f &&
            (!lastAppliedPhaseFraction.isFinite() || lastAppliedPhaseFraction < 1f)
    return intervalDue || finalDynamicSampleDue
}

/**
 * Holds an in-flight dynamic size revision stable for its two-frame traversal acknowledgment.
 *
 * The controller-owned clock keeps advancing while the revision is pending. Once acknowledged,
 * the next frame samples that clock directly, which gives us a bounded single-slot latest-wins
 * queue without allocating or replaying stale intermediate geometry.
 */
internal fun layerSizeFractionForGeometryCommit(
    desiredPhaseFraction: Float,
    lastAppliedPhaseFraction: Float,
    dynamicLayerSize: Boolean,
    geometryRevisionPending: Boolean,
): Float {
    val desired = desiredPhaseFraction
        .takeIf(Float::isFinite)
        ?.coerceIn(0f, 1f)
        ?: 0f
    if (!dynamicLayerSize || !geometryRevisionPending) return desired
    return lastAppliedPhaseFraction
        .takeIf(Float::isFinite)
        ?.takeIf { it >= 0f }
        ?.coerceIn(0f, 1f)
        ?: desired
}

private fun millisecondsToNanosSaturated(milliseconds: Long): Long {
    if (milliseconds <= 0L) return 0L
    return if (milliseconds > Long.MAX_VALUE / NANOS_PER_MILLI) {
        Long.MAX_VALUE
    } else {
        milliseconds * NANOS_PER_MILLI
    }
}

internal fun normalizedLayerSizePhaseFraction(
    elapsedNanos: Long,
    durationMs: Long,
): Float {
    if (durationMs <= 0L) return 1f
    if (elapsedNanos <= 0L) return 0f
    val fraction =
        elapsedNanos.toDouble() / (durationMs.toDouble() * NANOS_PER_MILLI.toDouble())
    return if (fraction.isFinite()) fraction.coerceIn(0.0, 1.0).toFloat() else 1f
}

/**
 * Keeps identical full-screen producers from becoming completely occluded while distributing
 * smaller footprints across the stage. The centered X stagger is bounded to eight pixels per
 * layer and then clamped so every scaled child keeps at least one visible pixel.
 */
internal fun layerProfileBaseTranslationX(
    stageWidth: Int,
    layerIndex: Int,
    layerCount: Int,
    widthScale: Float,
): Float {
    if (
        stageWidth <= 0 ||
        layerCount !in 1..LayerStageView.MAX_LAYERS ||
        layerIndex !in 0 until layerCount
    ) {
        return 0f
    }
    val safeScale = finiteLayerProfileScale(widthScale)
    val columns = minOf(PROFILE_GRID_COLUMNS, layerCount)
    val column = layerIndex % columns
    val normalizedCenter = (column + 0.5f) / columns - 0.5f
    val centeredOffset = normalizedCenter * stageWidth * (1f - safeScale)
    val staggerStep = if (layerCount <= 1 || stageWidth <= 1) {
        0f
    } else {
        minOf(
            MAX_PROFILE_STAGGER_PX,
            (stageWidth - 1).toFloat() / (layerCount - 1),
        )
    }
    val centeredStagger =
        staggerStep * (layerIndex - (layerCount - 1) / 2f)
    val maxVisibleTranslation =
        (stageWidth * (1f + safeScale) * 0.5f - 1f).coerceAtLeast(0f)
    return (centeredOffset + centeredStagger)
        .coerceIn(-maxVisibleTranslation, maxVisibleTranslation)
}

internal fun layerProfileBaseTranslationY(
    stageHeight: Int,
    layerIndex: Int,
    layerCount: Int,
    heightScale: Float,
): Float {
    if (
        stageHeight <= 0 ||
        layerCount !in 1..LayerStageView.MAX_LAYERS ||
        layerIndex !in 0 until layerCount
    ) {
        return 0f
    }
    val safeScale = finiteLayerProfileScale(heightScale)
    val columns = minOf(PROFILE_GRID_COLUMNS, layerCount)
    val rows = (layerCount + columns - 1) / columns
    val row = layerIndex / columns
    val normalizedCenter = (row + 0.5f) / rows - 0.5f
    return (normalizedCenter * stageHeight * (1f - safeScale))
        .coerceIn(-stageHeight.toFloat(), stageHeight.toFloat())
}

private fun finiteLayerProfileScale(value: Float): Float =
    if (value.isFinite()) value.coerceIn(MIN_RENDERED_PROFILE_SCALE, 1f) else 1f

internal data class CapacityTileBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

internal fun capacityGeometryRequiresForcedRepublish(
    geometryWasReady: Boolean,
    geometryReady: Boolean,
): Boolean = geometryWasReady && !geometryReady

/**
 * Normal identical publications are deduplicated. Geometry recovery is different: the controller
 * has already marked the same generation pending, so the identical expected set is its commit
 * acknowledgment and must be emitted once more.
 */
internal fun shouldPublishExpectedProducerSet(
    bindingsCommitted: Boolean,
    expectedTopologyDirty: Boolean,
    forceRepublish: Boolean,
    sameAsLastPublication: Boolean,
): Boolean =
    bindingsCommitted &&
        (expectedTopologyDirty || forceRepublish) &&
        (forceRepublish || !sameAsLastPublication)

internal class ExpectedPublicationSuppression {
    internal class Token internal constructor(
        internal val epoch: Long,
    )

    private var depth = 0
    private var epoch = 0L

    fun begin(): Token {
        check(depth < Int.MAX_VALUE) {
            "Expected-producer publication suppression depth exhausted"
        }
        depth++
        return Token(epoch)
    }

    /**
     * Ends exactly one transaction-owned suppression scope. A synchronous release invalidates the
     * token but deliberately leaves [depth] untouched, allowing every active finally block to
     * discharge its own scope without underflow.
     */
    fun end(token: Token): Boolean {
        check(depth > 0) {
            "Expected-producer publication suppression underflow"
        }
        depth--
        return token.epoch == epoch
    }

    fun invalidate() {
        epoch = nextRendererMutationEpoch(epoch)
    }

    fun isSuppressed(): Boolean = depth > 0
}

internal fun nextRendererMutationEpoch(current: Long): Long =
    if (current == Long.MAX_VALUE) 1L else current + 1L

internal fun expectedProducerPublicationCommitIsCurrent(
    capturedMutationEpoch: Long,
    currentMutationEpoch: Long,
    capturedGeneration: Long,
    currentGeneration: Long,
    callbackIdentityMatches: Boolean,
    relayIdentityMatches: Boolean,
    publicationStillEligible: Boolean,
): Boolean =
    capturedMutationEpoch == currentMutationEpoch &&
        capturedGeneration == currentGeneration &&
        callbackIdentityMatches &&
        relayIdentityMatches &&
        publicationStillEligible

internal fun producerControlRevisionIsValid(
    requestedRevision: Long,
    currentRevision: Long,
    generationChanged: Boolean,
): Boolean =
    requestedRevision >= 0L &&
        (generationChanged || requestedRevision >= currentRevision)

/**
 * Includes expected-set construction and the controller callback in one fail-closed boundary.
 * The rollback returns the authoritative terminal failure so fatal VM identity survives cleanup.
 */
internal inline fun runFailClosedProducerPublication(
    publication: () -> Unit,
    rollback: (Throwable) -> Throwable,
): Boolean = runFailClosedRendererMutation(publication, rollback)

/**
 * Retains the first failure for ordinary cleanup errors, but a fatal [Error] always becomes the
 * terminal identity even when it occurs after an ordinary failure. Suppression is diagnostic only:
 * failure to allocate suppressed state must never replace the selected terminal error.
 */
internal fun mergeFailurePreservingFatal(
    current: Throwable?,
    candidate: Throwable,
): Throwable {
    if (current == null) return candidate
    if (current === candidate) return current
    val terminal = if (current is Error || candidate !is Error) current else candidate
    val secondary = if (terminal === current) candidate else current
    try {
        terminal.addSuppressed(secondary)
    } catch (_: Throwable) {
        // Best effort only, including an already active OutOfMemoryError.
    }
    return terminal
}

/**
 * A physical Surface lifecycle signal and producer teardown are one ordered transaction. Even a
 * fatal pending callback cannot skip cancellation/release; the fatal identity is rethrown only
 * after all three best-effort actions have run.
 */
internal inline fun performProducerLifecycleTeardown(
    signalPending: () -> Unit,
    cancelPendingStart: () -> Unit,
    releaseProducer: () -> Boolean,
): Boolean {
    var terminalFailure: Throwable? = null
    var stopped = false
    try {
        signalPending()
    } catch (failure: Throwable) {
        terminalFailure = mergeFailurePreservingFatal(terminalFailure, failure)
    }
    try {
        cancelPendingStart()
    } catch (failure: Throwable) {
        terminalFailure = mergeFailurePreservingFatal(terminalFailure, failure)
    }
    try {
        stopped = releaseProducer()
    } catch (failure: Throwable) {
        terminalFailure = mergeFailurePreservingFatal(terminalFailure, failure)
    }
    terminalFailure?.let { throw it }
    return stopped
}

internal inline fun runFailClosedRendererMutation(
    mutation: () -> Unit,
    rollback: (Throwable) -> Throwable,
): Boolean = try {
    mutation()
    true
} catch (failure: Throwable) {
    val terminal = try {
        rollback(failure)
    } catch (cleanupFailure: Throwable) {
        mergeFailurePreservingFatal(failure, cleanupFailure)
    }
    if (terminal is Error) throw terminal
    false
}

/**
 * Partitions the visible stage into non-overlapping opaque crops. Every candidate Surface keeps
 * scale=1/rotation=0, while its unique crop prevents opaque higher-Z siblings from completely
 * occluding lower candidates before SurfaceFlinger composition classification.
 */
internal fun capacityTileBounds(
    width: Int,
    height: Int,
    layerCount: Int,
    layerIndex: Int,
): CapacityTileBounds? {
    if (width <= 0 || height <= 0) return null
    if (layerCount !in 1..LayerStageView.MAX_LAYERS) return null
    if (layerIndex !in 0 until layerCount) return null
    val preferredColumns = minOf(5, layerCount)
    val minimumColumnsForHeight =
        (layerCount + height.toLong() - 1L) / height.toLong()
    val columns = maxOf(preferredColumns.toLong(), minimumColumnsForHeight)
        .coerceAtMost(minOf(layerCount, width).toLong())
        .toInt()
    if (columns <= 0) return null
    val rows = (layerCount + columns - 1) / columns
    if (rows > height) return null
    val row = layerIndex / columns
    val firstIndexInRow = row * columns
    val columnsInRow = minOf(columns, layerCount - firstIndexInRow)
    if (columnsInRow <= 0 || columnsInRow > width) return null
    val column = layerIndex - firstIndexInRow
    // Partition each row independently. A partial final row must still span the full stage;
    // otherwise the HUD's explicit crop-union footprint and the rendered candidate diverge.
    val left = (width.toLong() * column / columnsInRow).toInt()
    val right = (width.toLong() * (column + 1L) / columnsInRow).toInt()
    val top = (height.toLong() * row / rows).toInt()
    val bottom = (height.toLong() * (row + 1L) / rows).toInt()
    return CapacityTileBounds(left, top, right, bottom)
        .takeIf { it.right > it.left && it.bottom > it.top }
}

/**
 * Revokes transaction-owned callback identities before releasing their renderer children.
 *
 * The child is registered before map insertion/addView, so rollback cannot use the map as its
 * ownership authority. Every direct relay is attempted, renderer retirement runs exactly once even
 * when one detach fails, and resources outside [owned] are never touched.
 */
internal inline fun <Child> rollbackOwnedRendererChildren(
    owned: List<Child>,
    disableRelay: (Child) -> Unit,
    retireChildren: (List<Child>) -> Unit,
) {
    var firstFailure: Throwable? = null
    var index = 0
    while (index < owned.size) {
        try {
            disableRelay(owned[index])
        } catch (error: Throwable) {
            firstFailure = mergeFailurePreservingFatal(firstFailure, error)
        }
        index++
    }
    try {
        retireChildren(owned)
    } catch (error: Throwable) {
        firstFailure = mergeFailurePreservingFatal(firstFailure, error)
    }
    firstFailure?.let { throw it }
}

/**
 * Gives every physical BufferQueue producer a stable identity. Generation, primary attribution,
 * and the applied renderer-control revision are immutable for a captured frame, while its callback
 * is detachable: a Canvas/EGL native call may remain blocked after teardown and its local completion
 * token must not retain the Activity/controller graph or report a frame for a producer that has
 * already been rebound.
 */
internal class ProducerFrameRelay(
    val producerId: Long,
    generation: Long,
    private val primary: Boolean,
    controlRevision: Long = 0L,
    callback: ProducerFrameCallback?,
) {
    @Volatile
    private var boundGeneration = generation

    @Volatile
    private var boundControlRevision = controlRevision.coerceAtLeast(0L)

    @Volatile
    private var boundCallback = callback

    @Volatile
    private var failureDispatch: ProducerFailureDispatch? =
        ProducerFailureDispatch(generation)

    @Volatile
    private var commitToken = callback?.let { capturedCallback ->
        ProducerCommitToken(
            generation = generation,
            producerId = producerId,
            primary = primary,
            controlRevision = boundControlRevision,
            callback = capturedCallback,
        )
    }

    fun update(
        generation: Long,
        controlRevision: Long = 0L,
        callback: ProducerFrameCallback?,
    ) {
        val prepared = prepareBindingUpdate(generation, controlRevision, callback)
        try {
            check(prepared.commit()) {
                "Producer relay binding changed before binding update commit"
            }
        } catch (failure: Throwable) {
            prepared.cancel()
            prepared.revokeOwner()
            throw failure
        }
    }

    fun updateControlRevision(controlRevision: Long) {
        val prepared = prepareControlRevision(controlRevision)
        try {
            check(prepared.commit()) {
                "Producer relay binding changed before control revision commit"
            }
        } catch (failure: Throwable) {
            prepared.cancel()
            prepared.revokeOwner()
            throw failure
        }
    }

    internal fun prepareBindingUpdate(
        generation: Long,
        controlRevision: Long,
        callback: ProducerFrameCallback?,
        beforeFailureDispatchAllocation: (() -> Unit)? = null,
        beforeTokenAllocation: (() -> Unit)? = null,
    ): PreparedBindingUpdate {
        val normalizedRevision = controlRevision.coerceAtLeast(0L)
        // A detached relay can be intentionally rebound by the owner transaction. Capturing the
        // nullable identity keeps that rebind atomic while still rejecting any intervening attach.
        val expectedFailureDispatch = failureDispatch
        val expectedToken = commitToken
        val expectedGeneration = boundGeneration
        val expectedRevision = boundControlRevision
        val expectedCallback = boundCallback
        var replacementToken: ProducerCommitToken? = null
        try {
            beforeFailureDispatchAllocation?.invoke()
            val replacementFailureDispatch = ProducerFailureDispatch(generation)
            if (callback != null) {
                beforeTokenAllocation?.invoke()
                replacementToken = ProducerCommitToken(
                    generation = generation,
                    producerId = producerId,
                    primary = primary,
                    controlRevision = normalizedRevision,
                    callback = callback,
                )
            }
            return PreparedBindingUpdate(
                owner = this,
                expectedFailureDispatch = expectedFailureDispatch,
                expectedToken = expectedToken,
                expectedGeneration = expectedGeneration,
                expectedRevision = expectedRevision,
                expectedCallback = expectedCallback,
                targetGeneration = generation,
                targetRevision = normalizedRevision,
                targetCallback = callback,
                replacementFailureDispatch = replacementFailureDispatch,
                replacementToken = replacementToken,
            )
        } catch (failure: Throwable) {
            replacementToken?.disable()
            throw failure
        }
    }

    internal fun prepareControlRevision(
        controlRevision: Long,
        beforeTokenAllocation: (() -> Unit)? = null,
    ): PreparedControlRevision {
        require(controlRevision >= boundControlRevision && controlRevision >= 0L) {
            "controlRevision must be non-negative and monotonic"
        }
        check(failureDispatch != null) { "Producer relay is detached" }
        val callback = boundCallback
        var replacement: ProducerCommitToken? = null
        try {
            if (controlRevision != boundControlRevision && callback != null) {
                beforeTokenAllocation?.invoke()
                replacement = ProducerCommitToken(
                    generation = boundGeneration,
                    producerId = producerId,
                    primary = primary,
                    controlRevision = controlRevision,
                    callback = callback,
                )
            }
            return PreparedControlRevision(
                owner = this,
                expectedFailureDispatch = failureDispatch,
                expectedToken = commitToken,
                expectedGeneration = boundGeneration,
                expectedRevision = boundControlRevision,
                expectedCallback = callback,
                targetRevision = controlRevision,
                replacement = replacement,
            )
        } catch (failure: Throwable) {
            replacement?.disable()
            throw failure
        }
    }

    fun disable() {
        failureDispatch = null
        boundCallback = null
        commitToken?.disable()
        commitToken = null
    }

    fun emit() {
        captureCallback()?.invoke()
    }

    fun captureCallback(): (() -> Unit)? = commitToken

    /**
     * Lifecycle teardown callbacks can be delivered long after a child was removed. Only an
     * actively attached producer may attribute such a failure, and it must use the generation to
     * which that producer relay is currently bound rather than LayerStageView's mutable generation.
     */
    fun activeGenerationForFailure(): Long? =
        failureDispatch?.generation

    /**
     * Runtime failure delivery crosses a main-queue boundary. Capture an immutable identity, not
     * just the generation: a relay may be disabled and rebound to the same generation while the
     * old callback is queued during an in-phase layer-count transition.
     */
    fun captureFailureDispatch(): ProducerFailureDispatch? = failureDispatch

    fun isFailureDispatchCurrent(candidate: ProducerFailureDispatch): Boolean =
        failureDispatch === candidate

    internal class PreparedBindingUpdate internal constructor(
        private val owner: ProducerFrameRelay,
        private val expectedFailureDispatch: ProducerFailureDispatch?,
        private val expectedToken: ProducerCommitToken?,
        private val expectedGeneration: Long,
        private val expectedRevision: Long,
        private val expectedCallback: ProducerFrameCallback?,
        private val targetGeneration: Long,
        private val targetRevision: Long,
        private val targetCallback: ProducerFrameCallback?,
        private val replacementFailureDispatch: ProducerFailureDispatch,
        private val replacementToken: ProducerCommitToken?,
    ) {
        private var resolved = false

        fun isCurrent(): Boolean =
            !resolved &&
                owner.failureDispatch === expectedFailureDispatch &&
                owner.commitToken === expectedToken &&
                owner.boundGeneration == expectedGeneration &&
                owner.boundControlRevision == expectedRevision &&
                owner.boundCallback === expectedCallback

        fun commit(afterTokenSwap: (() -> Unit)? = null): Boolean {
            if (!isCurrent()) return false
            owner.failureDispatch = replacementFailureDispatch
            owner.boundGeneration = targetGeneration
            owner.boundControlRevision = targetRevision
            owner.boundCallback = targetCallback
            owner.commitToken = replacementToken
            afterTokenSwap?.invoke()
            expectedToken?.disable()
            resolved = true
            return true
        }

        fun cancel() {
            if (resolved) return
            replacementToken?.disable()
            resolved = true
        }

        fun revokeOwner() {
            expectedToken?.disable()
            replacementToken?.disable()
            owner.commitToken?.disable()
            owner.disable()
        }
    }

    internal class PreparedControlRevision internal constructor(
        private val owner: ProducerFrameRelay,
        private val expectedFailureDispatch: ProducerFailureDispatch?,
        private val expectedToken: ProducerCommitToken?,
        private val expectedGeneration: Long,
        private val expectedRevision: Long,
        private val expectedCallback: ProducerFrameCallback?,
        private val targetRevision: Long,
        private val replacement: ProducerCommitToken?,
    ) {
        private var resolved = false

        fun isCurrent(): Boolean =
            !resolved &&
                owner.failureDispatch === expectedFailureDispatch &&
                owner.commitToken === expectedToken &&
                owner.boundGeneration == expectedGeneration &&
                owner.boundControlRevision == expectedRevision &&
                owner.boundCallback === expectedCallback

        /**
         * Allocation-free main-thread publication. A false result leaves the current binding
         * untouched; the caller must cancel this prepared replacement.
         */
        fun commit(afterTokenSwap: (() -> Unit)? = null): Boolean {
            if (!isCurrent()) return false
            if (targetRevision != expectedRevision) {
                owner.boundControlRevision = targetRevision
                owner.commitToken = replacement
                afterTokenSwap?.invoke()
                expectedToken?.disable()
            }
            resolved = true
            return true
        }

        fun cancel() {
            if (resolved) return
            replacement?.disable()
            resolved = true
        }

        fun revokeOwner() {
            // A fatal asynchronous failure can land after the replacement is published but before
            // the old token is disabled. The old token is no longer reachable through owner, so
            // rollback must explicitly revoke all three identities.
            expectedToken?.disable()
            replacement?.disable()
            owner.commitToken?.disable()
            owner.disable()
        }
    }

    internal class ProducerCommitToken(
        val generation: Long,
        val producerId: Long,
        val primary: Boolean,
        val controlRevision: Long,
        callback: ProducerFrameCallback,
    ) : () -> Unit {
        private val callback = AtomicReference<ProducerFrameCallback?>(callback)

        override fun invoke() {
            callback.get()?.onFrame(
                generation,
                producerId,
                primary,
                controlRevision,
            )
        }

        fun disable() {
            callback.set(null)
        }
    }

}

internal interface ProducerRelayRevocationTransaction {
    fun revokeAll()
}

internal class ProducerRelayBindingTransaction private constructor(
    private val prepared: ArrayList<ProducerFrameRelay.PreparedBindingUpdate>,
) : ProducerRelayRevocationTransaction {
    fun validateAll(): Boolean {
        for (entry in prepared) {
            if (!entry.isCurrent()) return false
        }
        return true
    }

    fun commitAll(
        beforeCommit: ((Int) -> Unit)? = null,
        afterTokenSwap: ((Int) -> Unit)? = null,
    ): Boolean {
        for (index in prepared.indices) {
            beforeCommit?.invoke(index)
            if (
                !prepared[index].commit(
                    afterTokenSwap = afterTokenSwap?.let { hook ->
                        { hook(index) }
                    },
                )
            ) {
                return false
            }
        }
        return true
    }

    fun cancelPrepared() {
        for (entry in prepared) entry.cancel()
    }

    override fun revokeAll() {
        for (entry in prepared) entry.revokeOwner()
    }

    companion object {
        fun prepare(
            relays: Collection<ProducerFrameRelay>,
            generation: Long,
            controlRevision: Long,
            callback: ProducerFrameCallback?,
            beforeCollectionAllocation: (() -> Unit)? = null,
            beforeFailureDispatchAllocation: ((Int) -> Unit)? = null,
            beforeTokenAllocation: ((Int) -> Unit)? = null,
        ): ProducerRelayBindingTransaction {
            beforeCollectionAllocation?.invoke()
            val entries = ArrayList<ProducerFrameRelay.PreparedBindingUpdate>(relays.size)
            try {
                var index = 0
                for (relay in relays) {
                    val entry = relay.prepareBindingUpdate(
                        generation = generation,
                        controlRevision = controlRevision,
                        callback = callback,
                        beforeFailureDispatchAllocation =
                            beforeFailureDispatchAllocation?.let { hook ->
                                { hook(index) }
                            },
                        beforeTokenAllocation = beforeTokenAllocation?.let { hook ->
                            { hook(index) }
                        },
                    )
                    try {
                        entries.add(entry)
                    } catch (failure: Throwable) {
                        entry.cancel()
                        throw failure
                    }
                    index++
                }
                return ProducerRelayBindingTransaction(entries)
            } catch (failure: Throwable) {
                for (entry in entries) entry.cancel()
                throw failure
            }
        }
    }
}

internal class ProducerControlRevisionTransaction private constructor(
    private val prepared: ArrayList<ProducerFrameRelay.PreparedControlRevision>,
) : ProducerRelayRevocationTransaction {
    fun validateAll(): Boolean {
        for (entry in prepared) {
            if (!entry.isCurrent()) return false
        }
        return true
    }

    fun commitAll(
        beforeCommit: ((Int) -> Unit)? = null,
        afterTokenSwap: ((Int) -> Unit)? = null,
    ): Boolean {
        for (index in prepared.indices) {
            beforeCommit?.invoke(index)
            if (
                !prepared[index].commit(
                    afterTokenSwap = afterTokenSwap?.let { hook ->
                        { hook(index) }
                    },
                )
            ) {
                return false
            }
        }
        return true
    }

    fun cancelPrepared() {
        for (entry in prepared) entry.cancel()
    }

    override fun revokeAll() {
        for (entry in prepared) entry.revokeOwner()
    }

    companion object {
        fun prepare(
            relays: Collection<ProducerFrameRelay>,
            controlRevision: Long,
            beforeTokenAllocation: ((Int) -> Unit)? = null,
        ): ProducerControlRevisionTransaction {
            val entries = ArrayList<ProducerFrameRelay.PreparedControlRevision>(relays.size)
            try {
                var index = 0
                for (relay in relays) {
                    val entry = relay.prepareControlRevision(
                        controlRevision = controlRevision,
                        beforeTokenAllocation = beforeTokenAllocation?.let { hook ->
                            { hook(index) }
                        },
                    )
                    try {
                        entries.add(entry)
                    } catch (failure: Throwable) {
                        // ArrayList growth can fail after the replacement token was prepared.
                        // Revoke that not-yet-owned entry before the outer rollback handles entries
                        // which were already published into the transaction collection.
                        entry.cancel()
                        throw failure
                    }
                    index++
                }
                return ProducerControlRevisionTransaction(entries)
            } catch (failure: Throwable) {
                for (entry in entries) entry.cancel()
                throw failure
            }
        }
    }
}

internal class ProducerFailureDispatch internal constructor(
    val generation: Long,
)

/**
 * A decoder callback can already be queued when MediaCodec teardown starts. Closing this gate
 * makes those callbacks no-ops even if the platform races one last delivery with listener detach.
 */
internal class DecoderFrameCallbackGate {
    private val open = AtomicBoolean(true)

    /** Allocation-free check for the per-frame MediaCodec callback hot path. */
    fun isOpen(): Boolean = open.get()

    fun close() {
        open.set(false)
    }
}

/**
 * Fixed-capacity epoch/timestamp binding between MediaCodec output submission and OnFrameRendered.
 * Primitive/object arrays are allocated once per decoder session; offer/invoke/remove perform no
 * hot-path allocation and duplicate timestamps retain FIFO identity within one source loop.
 */
internal class DecoderFrameCommitQueue(capacity: Int) {
    private val epochs = LongArray(capacity.coerceAtLeast(1))
    private val presentationTimesUs = LongArray(epochs.size)
    private val commits = arrayOfNulls<() -> Unit>(presentationTimesUs.size)
    private var head = 0
    private var size = 0

    @Synchronized
    fun offer(
        epoch: Long,
        presentationTimeUs: Long,
        commit: () -> Unit,
    ): Boolean {
        if (epoch <= 0L || size >= commits.size) return false
        val index = (head + size) % commits.size
        epochs[index] = epoch
        presentationTimesUs[index] = presentationTimeUs
        commits[index] = commit
        size++
        return true
    }

    /**
     * Claims the first exact timestamp under the queue monitor, then invokes external producer code
     * after releasing it. EOS/STOP can therefore clear promptly even if a callback stalls; epoch
     * drain and relay detachment remain the bounded authority for an already claimed callback.
     */
    fun invoke(
        epoch: Long,
        presentationTimeUs: Long,
    ): Boolean {
        val commit = synchronized(this) {
            if (epoch <= 0L) return false
            val foundOffset = findOffset(epoch, presentationTimeUs) ?: return false
            removeAt(foundOffset)
        } ?: return false
        commit.invoke()
        return true
    }

    /**
     * Rolls back one output submission which failed before MediaCodec accepted it. Callback
     * identity is part of the match because looping content can reuse a presentation timestamp.
     */
    @Synchronized
    fun remove(
        epoch: Long,
        presentationTimeUs: Long,
        commit: () -> Unit,
    ): Boolean {
        if (epoch <= 0L) return false
        val foundOffset = findOffset(epoch, presentationTimeUs, commit) ?: return false
        removeAt(foundOffset)
        return true
    }

    private fun findOffset(
        epoch: Long,
        presentationTimeUs: Long,
        commit: (() -> Unit)? = null,
    ): Int? {
        var foundOffset = -1
        var offset = 0
        while (offset < size) {
            val index = (head + offset) % commits.size
            if (
                epochs[index] == epoch &&
                presentationTimesUs[index] == presentationTimeUs &&
                (commit == null || commits[index] === commit)
            ) {
                foundOffset = offset
                break
            }
            offset++
        }
        return foundOffset.takeIf { it >= 0 }
    }

    private fun removeAt(foundOffset: Int): (() -> Unit)? {
        val foundIndex = (head + foundOffset) % commits.size
        val result = commits[foundIndex]
        if (foundOffset == 0) {
            commits[head] = null
            head = (head + 1) % commits.size
            size--
            if (size == 0) head = 0
            return result
        }
        var offset = foundOffset
        while (offset < size - 1) {
            val to = (head + offset) % commits.size
            val from = (head + offset + 1) % commits.size
            epochs[to] = epochs[from]
            presentationTimesUs[to] = presentationTimesUs[from]
            commits[to] = commits[from]
            offset++
        }
        val tail = (head + size - 1) % commits.size
        commits[tail] = null
        size--
        if (size == 0) head = 0
        return result
    }

    @Synchronized
    fun clear() {
        var offset = 0
        while (offset < size) {
            commits[(head + offset) % commits.size] = null
            offset++
        }
        head = 0
        size = 0
    }
}

internal fun nextDecoderFrameCallbackEpoch(current: Long): Long? =
    if (current < 0L || current == Long.MAX_VALUE) null else current + 1L

private const val DECODER_CALLBACK_DRAIN_TIMEOUT_NANOS = 500_000_000L
private const val DECODER_CALLBACK_DRAIN_POLL_NANOS = 1_000_000L

private fun decoderCallbackDrainDeadlineNanos(nowNanos: Long = System.nanoTime()): Long =
    nowNanos + DECODER_CALLBACK_DRAIN_TIMEOUT_NANOS

/**
 * Reusable same-Looper drain marker. One decoder worker owns requests serially, so the Runnable and
 * primitive tickets are allocated once per session while each wait remains cancellation-aware and
 * bounded by an absolute deadline.
 */
internal class DecoderCallbackDrainBarrier {
    private val requestedTicket = AtomicLong(0L)
    private val completedTicket = AtomicLong(0L)
    private val completion = Runnable {
        completedTicket.set(requestedTicket.get())
    }

    fun await(
        handler: Handler,
        running: AtomicBoolean,
        absoluteDeadlineNanos: Long,
    ): Boolean {
        val current = requestedTicket.get()
        val ticket = nextDecoderFrameCallbackEpoch(current) ?: return false
        if (!requestedTicket.compareAndSet(current, ticket)) return false
        if (!handler.post(completion)) return false
        while (completedTicket.get() < ticket) {
            if (Thread.interrupted()) throw InterruptedException()
            if (!running.get()) return false
            val remainingNanos = absoluteDeadlineNanos - System.nanoTime()
            if (remainingNanos <= 0L) return false
            LockSupport.parkNanos(
                remainingNanos.coerceAtMost(DECODER_CALLBACK_DRAIN_POLL_NANOS),
            )
        }
        return true
    }
}

internal inline fun performDecoderStopRequestActions(
    detachUiCallbacks: () -> Unit,
    stopDecoder: () -> Unit,
    closeFrameCallbacks: () -> Unit,
    interruptDecoder: () -> Unit,
): Throwable? {
    var terminalFailure: Throwable? = null
    try {
        detachUiCallbacks()
    } catch (failure: Throwable) {
        terminalFailure = mergeFailurePreservingFatal(terminalFailure, failure)
    }
    try {
        stopDecoder()
    } catch (failure: Throwable) {
        terminalFailure = mergeFailurePreservingFatal(terminalFailure, failure)
    }
    try {
        closeFrameCallbacks()
    } catch (failure: Throwable) {
        terminalFailure = mergeFailurePreservingFatal(terminalFailure, failure)
    }
    try {
        interruptDecoder()
    } catch (failure: Throwable) {
        terminalFailure = mergeFailurePreservingFatal(terminalFailure, failure)
    }
    return terminalFailure
}

internal inline fun performDecoderFrameCallbackCloseActions(
    closeGate: () -> Unit,
    clearFrameCommits: () -> Unit,
    removeQueuedCallbacks: () -> Unit,
    requestQuit: () -> Unit,
): Throwable? {
    var terminalFailure: Throwable? = null
    try {
        closeGate()
    } catch (failure: Throwable) {
        terminalFailure = mergeFailurePreservingFatal(terminalFailure, failure)
    }
    try {
        clearFrameCommits()
    } catch (failure: Throwable) {
        terminalFailure = mergeFailurePreservingFatal(terminalFailure, failure)
    }
    try {
        removeQueuedCallbacks()
    } catch (failure: Throwable) {
        terminalFailure = mergeFailurePreservingFatal(terminalFailure, failure)
    }
    try {
        requestQuit()
    } catch (failure: Throwable) {
        terminalFailure = mergeFailurePreservingFatal(terminalFailure, failure)
    }
    return terminalFailure
}

internal inline fun performDecoderUiDetachActions(
    markDetached: () -> Unit,
    clearFrameCommitCapture: () -> Unit,
    clearFrameCommits: () -> Unit,
    clearUiCallbacks: () -> Unit,
): Throwable? {
    var terminalFailure: Throwable? = null
    try {
        markDetached()
    } catch (failure: Throwable) {
        terminalFailure = mergeFailurePreservingFatal(terminalFailure, failure)
    }
    try {
        clearFrameCommitCapture()
    } catch (failure: Throwable) {
        terminalFailure = mergeFailurePreservingFatal(terminalFailure, failure)
    }
    try {
        clearFrameCommits()
    } catch (failure: Throwable) {
        terminalFailure = mergeFailurePreservingFatal(terminalFailure, failure)
    }
    try {
        clearUiCallbacks()
    } catch (failure: Throwable) {
        terminalFailure = mergeFailurePreservingFatal(terminalFailure, failure)
    }
    return terminalFailure
}

internal data class DecoderTeardownOutcome(
    val decoderStopped: Boolean,
    val callbacksStopped: Boolean,
    val failure: Throwable?,
) {
    val fullyStopped: Boolean
        get() = decoderStopped && callbacksStopped
}

internal inline fun performDecoderTeardown(
    requestStop: () -> Unit,
    joinDecoder: () -> Boolean,
    joinCallbacks: () -> Boolean,
): DecoderTeardownOutcome {
    var terminalFailure: Throwable? = null
    var decoderStopped = false
    var callbacksStopped = false
    try {
        requestStop()
    } catch (failure: Throwable) {
        terminalFailure = mergeFailurePreservingFatal(terminalFailure, failure)
    }
    try {
        decoderStopped = joinDecoder()
    } catch (failure: Throwable) {
        terminalFailure = mergeFailurePreservingFatal(terminalFailure, failure)
    }
    try {
        callbacksStopped = joinCallbacks()
    } catch (failure: Throwable) {
        terminalFailure = mergeFailurePreservingFatal(terminalFailure, failure)
    }
    return DecoderTeardownOutcome(
        decoderStopped = decoderStopped,
        callbacksStopped = callbacksStopped,
        failure = terminalFailure,
    )
}

internal inline fun postDecoderFinishedOrDetach(
    postFinished: () -> Boolean,
    detachUiCallbacks: () -> Unit,
): Throwable? {
    var terminalFailure: Throwable? = null
    val posted = try {
        postFinished()
    } catch (failure: Throwable) {
        terminalFailure = mergeFailurePreservingFatal(terminalFailure, failure)
        false
    }
    if (!posted) {
        try {
            detachUiCallbacks()
        } catch (failure: Throwable) {
            terminalFailure = mergeFailurePreservingFatal(terminalFailure, failure)
        }
    }
    return terminalFailure
}

internal inline fun decoderCreationFailureAfterCleanup(
    primaryFailure: Throwable,
    requestQuit: () -> Unit,
    joinCallbackThread: () -> Unit,
): Throwable {
    var quitFailure: Throwable? = null
    var joinFailure: Throwable? = null
    try {
        requestQuit()
    } catch (failure: Throwable) {
        quitFailure = failure
    }
    try {
        joinCallbackThread()
    } catch (failure: Throwable) {
        joinFailure = failure
    }
    val terminal = when {
        primaryFailure is Error -> primaryFailure
        quitFailure is Error -> quitFailure
        joinFailure is Error -> joinFailure
        else -> primaryFailure
    }
    if (primaryFailure !== terminal) addSuppressedBestEffort(terminal, primaryFailure)
    quitFailure?.takeIf { it !== terminal }?.let { addSuppressedBestEffort(terminal, it) }
    joinFailure?.takeIf { it !== terminal }?.let { addSuppressedBestEffort(terminal, it) }
    return terminal
}

/**
 * Applies one teardown action after both request and resource publication have happened, in either
 * order. This avoids calling HandlerThread.quit(), whose implicit getLooper() can block main while
 * the callback thread is still being scheduled.
 */
internal class DeferredQuitHandshake<T : Any>(
    private val quit: (T) -> Unit,
) {
    private val requested = AtomicBoolean(false)
    private val target = AtomicReference<T?>()
    private val applied = AtomicBoolean(false)

    fun request() {
        requested.set(true)
        applyIfReady()
    }

    fun publish(value: T) {
        check(target.compareAndSet(null, value) || target.get() === value) {
            "Deferred quit target is already published"
        }
        applyIfReady()
    }

    fun current(): T? = target.get()

    private fun applyIfReady() {
        if (!requested.get()) return
        val published = target.get() ?: return
        if (applied.compareAndSet(false, true)) quit(published)
    }
}

/**
 * HandlerThread.getLooper() can wait without a deadline. The decoder worker uses this explicit
 * readiness signal so main-thread start never blocks and a scheduler/Looper startup failure has a
 * bounded fail-closed path.
 */
internal class BoundedCallbackHandlerThread(name: String) : HandlerThread(name) {
    private val readiness = CountDownLatch(1)
    private val deferredQuit = DeferredQuitHandshake<Looper> { looper -> looper.quit() }

    override fun onLooperPrepared() {
        Looper.myLooper()?.let(deferredQuit::publish)
        readiness.countDown()
    }

    override fun run() {
        try {
            super.run()
        } finally {
            readiness.countDown()
        }
    }

    fun awaitHandler(timeoutMs: Long): Handler? {
        if (timeoutMs <= 0L) return null
        val ready = try {
            readiness.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!ready) return null
        return deferredQuit.current()?.let(::Handler)
    }

    fun requestQuit() {
        deferredQuit.request()
    }
}

fun interface ProducerFrameCallback {
    fun onFrame(
        generation: Long,
        producerId: Long,
        primary: Boolean,
        controlRevision: Long,
    )
}

/**
 * Main-thread, non-blocking hand-off from an old Surface producer to its replacement. The
 * process lease is checked before every start, so a slow Canvas/codec/EGL teardown can never
 * overlap a new producer. Clearing the lease exactly at the deadline is treated as recovery.
 */
private class RendererLeaseStart(
    private val host: View,
    private val canStart: () -> Boolean,
    private val start: () -> Unit,
    private val onTimeout: () -> Unit,
) : Runnable {
    private var active = false
    private var startedMs = 0L

    fun request() {
        if (!active) {
            active = true
            startedMs = SystemClock.elapsedRealtime()
        }
        host.removeCallbacks(this)
        if (!host.post(this)) {
            active = false
            onTimeout()
        }
    }

    fun cancel() {
        active = false
        host.removeCallbacks(this)
    }

    override fun run() {
        if (!active) return
        when (
            producerRecoveryDecision(
                targetValid = canStart(),
                processLeaseActive = RendererSafetyState.hasUnconfirmedTeardown(),
                elapsedMs = SystemClock.elapsedRealtime() - startedMs,
                timeoutMs = PRODUCER_RECOVERY_TIMEOUT_MS,
            )
        ) {
            ProducerRecoveryDecision.CANCEL -> cancel()
            ProducerRecoveryDecision.START -> {
                active = false
                start()
            }
            ProducerRecoveryDecision.FAIL -> {
                active = false
                onTimeout()
            }
            ProducerRecoveryDecision.WAIT -> {
                if (!host.postDelayed(this, PRODUCER_RECOVERY_POLL_MS)) {
                    active = false
                    onTimeout()
                }
            }
        }
    }
}

@SuppressLint("ViewConstructor")
private class UnavailableDecoderSurfaceView(
    context: Context,
    onUnavailable: () -> Unit,
) : SurfaceView(context) {
    private val callback = AtomicReference<(() -> Unit)?>(onUnavailable)
    private val notifyUnavailable = Runnable {
        callback.getAndSet(null)?.invoke()
    }

    init {
        holder.setFormat(PixelFormat.OPAQUE)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(notifyUnavailable)
    }

    fun requestStop() {
        callback.set(null)
        removeCallbacks(notifyUnavailable)
    }
}

@SuppressLint("ViewConstructor")
internal class PatternSurfaceView(
    context: Context,
    private val layerIndex: Int,
    targetFps: Float,
    private val pixelRoute: PixelRoute,
    private val bufferSize: BufferSize,
    private val alphaSurface: Boolean,
    private val captureFrameCommit: (() -> (() -> Unit)?)?,
    private val onTeardownFailure: (() -> Unit)?,
    private val onRuntimeFailure: ((String) -> Unit)?,
    private val onProducerLifecycleTransition: (() -> Unit)?,
) : SurfaceView(context), SurfaceHolder.Callback {

    @Volatile
    private var targetFps: Float = safeProducerFps(targetFps)
    private val removalRequested = AtomicBoolean(false)
    private var loop: CanvasDrawingLoop? = null
    private val leaseStart = RendererLeaseStart(
        host = this,
        canStart = {
            !removalRequested.get() && holder.surface.isValid
        },
        start = ::startDrawingLoop,
        onTimeout = { onTeardownFailure?.invoke() },
    )

    init {
        holder.addCallback(this)
        holder.setFormat(
            when {
                alphaSurface -> PixelFormat.TRANSLUCENT
                pixelRoute == PixelRoute.RGB_565 -> PixelFormat.RGB_565
                else -> PixelFormat.RGBA_8888
            },
        )
        if (bufferSize != BufferSize.DISPLAY) {
            holder.setFixedSize(bufferSize.width, bufferSize.height)
        }
        setZOrderMediaOverlay(layerIndex > 0)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (removalRequested.get()) return
        // Do not spend the old 150 ms aggregate join budget on the UI thread. A live old loop is
        // registered as a process lease and this same Surface is restarted when it actually exits.
        performProducerLifecycleTeardown(
            signalPending = { onProducerLifecycleTransition?.invoke() },
            cancelPendingStart = leaseStart::cancel,
            releaseProducer = { release(System.nanoTime()) },
        )
        if (removalRequested.get()) return
        applyFrameRate(holder.surface, targetFps)
        leaseStart.request()
    }

    private fun startDrawingLoop() {
        if (removalRequested.get() || !holder.surface.isValid || loop != null) return
        val candidate = CanvasDrawingLoop(
            surface = holder.surface,
            layerIndex = layerIndex,
            initialTargetFps = targetFps,
            logicalYuv = pixelRoute in setOf(PixelRoute.YUV_420, PixelRoute.P010),
            sbwcRequested = pixelRoute in setOf(PixelRoute.SBWC_AUTO, PixelRoute.SBWC_REQUIRED),
            logicalLayerCount = 1,
            translucentContent = alphaSurface,
            captureFrameCommit = captureFrameCommit,
            onRuntimeFailure = onRuntimeFailure,
        )
        if (candidate.start()) loop = candidate
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        applyFrameRate(holder.surface, targetFps)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        performProducerLifecycleTeardown(
            signalPending = { onProducerLifecycleTransition?.invoke() },
            cancelPendingStart = leaseStart::cancel,
            releaseProducer = { release(System.nanoTime()) },
        )
    }

    fun setTargetFps(fps: Float) {
        val normalized = safeProducerFps(fps)
        if (normalized == targetFps) return
        targetFps = normalized
        loop?.setTargetFps(normalized)
        if (holder.surface.isValid) applyFrameRate(holder.surface, normalized)
    }

    fun release(stopDeadlineNanos: Long = producerDrainDeadlineNanos()): Boolean {
        val stopped = loop?.stop(stopDeadlineNanos) ?: true
        loop = null
        return stopped
    }

    fun requestStop() {
        removalRequested.set(true)
        leaseStart.cancel()
        loop?.requestStop()
    }
}

@SuppressLint("ViewConstructor")
internal class PatternTextureView(
    context: Context,
    private val layerIndex: Int,
    targetFps: Float,
    private val logicalYuv: Boolean,
    private val captureFrameCommit: (() -> (() -> Unit)?)?,
    private val onTeardownFailure: (() -> Unit)?,
    private val onRuntimeFailure: ((String) -> Unit)?,
    private val onProducerLifecycleTransition: (() -> Unit)?,
) : TextureView(context), TextureView.SurfaceTextureListener {

    @Volatile
    private var targetFps: Float = safeProducerFps(targetFps)
    private val removalRequested = AtomicBoolean(false)
    private var loop: CanvasDrawingLoop? = null
    private var drawSurface: Surface? = null
    private var drawSurfaceTexture: SurfaceTexture? = null
    private var surfaceTextureOwnedByProducer = false
    private val leaseStart = RendererLeaseStart(
        host = this,
        canStart = {
            !removalRequested.get() && drawSurface?.isValid == true && isAvailable
        },
        start = ::startDrawingLoop,
        onTimeout = { onTeardownFailure?.invoke() },
    )

    init {
        surfaceTextureListener = this
        isOpaque = false
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        if (removalRequested.get()) return
        performProducerLifecycleTeardown(
            signalPending = { onProducerLifecycleTransition?.invoke() },
            cancelPendingStart = leaseStart::cancel,
            releaseProducer = { release(System.nanoTime()) },
        )
        if (removalRequested.get()) return
        drawSurfaceTexture = texture
        drawSurface = Surface(texture).also { applyFrameRate(it, targetFps) }
        leaseStart.request()
    }

    private fun startDrawingLoop() {
        val surface = drawSurface ?: return
        val texture = drawSurfaceTexture ?: return
        if (removalRequested.get() || !surface.isValid || loop != null) return
        val candidate = CanvasDrawingLoop(
            surface = surface,
            layerIndex = layerIndex,
            initialTargetFps = targetFps,
            logicalYuv = logicalYuv,
            sbwcRequested = false,
            logicalLayerCount = 1,
            releaseSurfaceOnExit = true,
            ownedSurfaceTexture = texture,
            captureFrameCommit = captureFrameCommit,
            onRuntimeFailure = onRuntimeFailure,
        )
        if (candidate.start()) {
            surfaceTextureOwnedByProducer = true
            loop = candidate
        }
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        val producerOwnsTexture = surfaceTextureOwnedByProducer
        performProducerLifecycleTeardown(
            signalPending = { onProducerLifecycleTransition?.invoke() },
            cancelPendingStart = leaseStart::cancel,
            releaseProducer = { release(System.nanoTime()) },
        )
        if (producerOwnsTexture) surfaceTextureOwnedByProducer = false
        return shouldFrameworkReleaseTextureSurface(
            producerOwnsTexture = producerOwnsTexture,
        )
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    fun setTargetFps(fps: Float) {
        val normalized = safeProducerFps(fps)
        if (normalized == targetFps) return
        targetFps = normalized
        loop?.setTargetFps(normalized)
        drawSurface?.let { surface ->
            if (surface.isValid) applyFrameRate(surface, normalized)
        }
    }

    fun release(stopDeadlineNanos: Long = producerDrainDeadlineNanos()): Boolean {
        val startedLoop = loop
        val stopped = startedLoop?.stop(stopDeadlineNanos) ?: true
        loop = null
        if (shouldViewReleaseTextureSurface(startedLoopPresent = startedLoop != null)) {
            drawSurface?.release()
        }
        drawSurface = null
        drawSurfaceTexture = null
        return stopped
    }

    fun requestStop() {
        removalRequested.set(true)
        leaseStart.cancel()
        loop?.requestStop()
    }
}

@SuppressLint("ViewConstructor")
internal class MultiLayerTextureView(
    context: Context,
    private val logicalLayerCount: Int,
    targetFps: Float,
    private val captureFrameCommit: (() -> (() -> Unit)?)?,
    private val onTeardownFailure: (() -> Unit)?,
    private val onRuntimeFailure: ((String) -> Unit)?,
    private val onProducerLifecycleTransition: (() -> Unit)?,
) : TextureView(context), TextureView.SurfaceTextureListener {

    @Volatile
    private var targetFps: Float = safeProducerFps(targetFps)
    private val removalRequested = AtomicBoolean(false)
    @Volatile
    private var gpuLoad: Float = 0f
    @Volatile
    private var loadShape: LoadShape = LoadShape.STEADY
    @Volatile
    private var loadStartedMs: Long = SystemClock.elapsedRealtime()
    private var loop: CanvasDrawingLoop? = null
    private var drawSurface: Surface? = null
    private var drawSurfaceTexture: SurfaceTexture? = null
    private var surfaceTextureOwnedByProducer = false
    private val leaseStart = RendererLeaseStart(
        host = this,
        canStart = {
            !removalRequested.get() && drawSurface?.isValid == true && isAvailable
        },
        start = ::startDrawingLoop,
        onTimeout = { onTeardownFailure?.invoke() },
    )

    init {
        surfaceTextureListener = this
        isOpaque = true
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        if (removalRequested.get()) return
        performProducerLifecycleTeardown(
            signalPending = { onProducerLifecycleTransition?.invoke() },
            cancelPendingStart = leaseStart::cancel,
            releaseProducer = { release(System.nanoTime()) },
        )
        if (removalRequested.get()) return
        drawSurfaceTexture = texture
        drawSurface = Surface(texture).also { applyFrameRate(it, targetFps) }
        leaseStart.request()
    }

    private fun startDrawingLoop() {
        val surface = drawSurface ?: return
        val texture = drawSurfaceTexture ?: return
        if (removalRequested.get() || !surface.isValid || loop != null) return
        val candidate = CanvasDrawingLoop(
            surface = surface,
            layerIndex = 0,
            initialTargetFps = targetFps,
            logicalYuv = false,
            sbwcRequested = false,
            logicalLayerCount = logicalLayerCount,
            releaseSurfaceOnExit = true,
            ownedSurfaceTexture = texture,
            initialComplexity = gpuLoad,
            initialLoadShape = loadShape,
            initialLoadStartedMs = loadStartedMs,
            captureFrameCommit = captureFrameCommit,
            onRuntimeFailure = onRuntimeFailure,
        )
        if (candidate.start()) {
            surfaceTextureOwnedByProducer = true
            loop = candidate
        }
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    fun setTargetFps(fps: Float) {
        val normalized = safeProducerFps(fps)
        if (normalized == targetFps) return
        targetFps = normalized
        loop?.setTargetFps(normalized)
        drawSurface?.let { surface ->
            if (surface.isValid) applyFrameRate(surface, normalized)
        }
    }

    fun setGpuLoad(load: Float, shape: LoadShape, restartProfile: Boolean = false) {
        val safeLoad = load.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        val shapeChanged = shape != loadShape
        gpuLoad = safeLoad
        loadShape = shape
        if (restartProfile || shapeChanged) {
            loadStartedMs = SystemClock.elapsedRealtime()
        }
        loop?.setLoad(
            load = safeLoad,
            shape = shape,
            loadStartedMs = loadStartedMs,
        )
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        val producerOwnsTexture = surfaceTextureOwnedByProducer
        performProducerLifecycleTeardown(
            signalPending = { onProducerLifecycleTransition?.invoke() },
            cancelPendingStart = leaseStart::cancel,
            releaseProducer = { release(System.nanoTime()) },
        )
        if (producerOwnsTexture) surfaceTextureOwnedByProducer = false
        return shouldFrameworkReleaseTextureSurface(
            producerOwnsTexture = producerOwnsTexture,
        )
    }

    fun release(stopDeadlineNanos: Long = producerDrainDeadlineNanos()): Boolean {
        val startedLoop = loop
        val stopped = startedLoop?.stop(stopDeadlineNanos) ?: true
        loop = null
        if (shouldViewReleaseTextureSurface(startedLoopPresent = startedLoop != null)) {
            drawSurface?.release()
        }
        drawSurface = null
        drawSurfaceTexture = null
        return stopped
    }

    fun requestStop() {
        removalRequested.set(true)
        leaseStart.cancel()
        loop?.requestStop()
    }
}

@SuppressLint("ViewConstructor")
internal class VideoSurfaceView(
    context: Context,
    private val selection: VideoDecoderSelection,
    targetFps: Float,
    private val captureFrameCommit: (() -> (() -> Unit)?)?,
    private val onTeardownFailure: (() -> Unit)?,
    private val onRuntimeFailure: ((String) -> Unit)?,
    private val onProducerLifecycleTransition: (() -> Unit)?,
) : SurfaceView(context), SurfaceHolder.Callback {

    @Volatile
    private var targetFps = safeProducerFps(targetFps)
    private val removalRequested = AtomicBoolean(false)
    @Volatile
    private var decoderSession: DecoderSession? = null
    private val leaseStart = RendererLeaseStart(
        host = this,
        canStart = {
            !removalRequested.get() && holder.surface.isValid
        },
        start = {
            if (decoderSession == null && holder.surface.isValid) {
                startDecoder(holder.surface)
            }
        },
        onTimeout = { onTeardownFailure?.invoke() },
    )

    init {
        holder.addCallback(this)
        holder.setFormat(PixelFormat.OPAQUE)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (removalRequested.get()) return
        performProducerLifecycleTeardown(
            signalPending = { onProducerLifecycleTransition?.invoke() },
            cancelPendingStart = leaseStart::cancel,
            releaseProducer = { release(System.nanoTime()) },
        )
        if (removalRequested.get()) return
        applyFrameRate(holder.surface, targetFps)
        leaseStart.request()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        applyFrameRate(holder.surface, targetFps)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        performProducerLifecycleTeardown(
            signalPending = { onProducerLifecycleTransition?.invoke() },
            cancelPendingStart = leaseStart::cancel,
            releaseProducer = { release(System.nanoTime()) },
        )
    }

    fun setFrameRateHint(fps: Float) {
        val normalized = safeProducerFps(fps)
        if (normalized == targetFps) {
            return
        }
        targetFps = normalized
        decoderSession?.targetFps = normalized
        if (holder.surface.isValid) applyFrameRate(holder.surface, normalized)
    }

    private fun startDecoder(surface: Surface) {
        if (decoderSession != null) {
            return
        }
        val sessionRunning = AtomicBoolean(true)
        val frameCallbackGate = DecoderFrameCallbackGate()
        val frameCallbackThread = try {
            BoundedCallbackHandlerThread("DpuLab-MediaCodecCallback").apply {
                isDaemon = true
            }
        } catch (error: Throwable) {
            frameCallbackGate.close()
            if (error is ThreadDeath || error is VirtualMachineError) throw error
            onRuntimeFailure?.invoke(
                buildRuntimeFailureReason("MediaCodec callback thread create", error),
            )
            return
        }
        val callbackThreadStarted = startRendererThread(frameCallbackThread) { error ->
            sessionRunning.set(false)
            frameCallbackGate.close()
            onRuntimeFailure?.invoke(
                buildRuntimeFailureReason("MediaCodec callback thread start", error),
            )
        }
        if (!callbackThreadStarted) return
        val decoderSelection = selection
        lateinit var session: DecoderSession
        val decoderRunnable = Runnable {
            var extractor: MediaExtractor? = null
            var codec: MediaCodec? = null
            var fatalFailure: Throwable? = null
            try {
                val frameCallbackHandler =
                    session.frameCallbackThread.awaitHandler(
                        CALLBACK_HANDLER_READY_TIMEOUT_MS,
                    ) ?: throw IllegalStateException(
                        "MediaCodec callback Looper readiness timed out",
                    )
                session.frameCallbackHandler.set(frameCallbackHandler)
                session.ensureSetupActive(surface)
                val activeExtractor = MediaExtractor()
                extractor = activeExtractor
                decoderSelection.openSourceDuplicate().use { source ->
                    activeExtractor.setDataSource(source.resource)
                }
                session.ensureSetupActive(surface)
                var videoTrack = -1
                var format: MediaFormat? = null
                for (index in 0 until activeExtractor.trackCount) {
                    val candidate = activeExtractor.getTrackFormat(index)
                    val mime = candidate.getString(MediaFormat.KEY_MIME).orEmpty()
                    if (mime.startsWith("video/")) {
                        videoTrack = index
                        format = candidate
                        break
                    }
                }
                check(videoTrack >= 0 && format != null) { "No video track" }
                activeExtractor.selectTrack(videoTrack)
                session.ensureSetupActive(surface)
                val mime = format.getString(MediaFormat.KEY_MIME)
                    ?: error("Video MIME unavailable")
                check(mime.equals(decoderSelection.mime, ignoreCase = true)) {
                    "Video MIME changed after preflight"
                }
                val inputEncoded = format.encodedVideoDimensions()
                    ?: error("Video encoded dimensions unavailable")
                check(
                    inputEncoded.widthPx == decoderSelection.expectedEncodedWidthPx &&
                        inputEncoded.heightPx == decoderSelection.expectedEncodedHeightPx
                ) {
                    "Video encoded dimensions changed after preflight"
                }
                val runtimeDeclaredMaxWidth =
                    format.strictOptionalIntegerOrNull(MediaFormat.KEY_MAX_WIDTH)
                val runtimeDeclaredMaxHeight =
                    format.strictOptionalIntegerOrNull(MediaFormat.KEY_MAX_HEIGHT)
                check(
                    runtimeDeclaredMaxWidth == decoderSelection.expectedDeclaredMaxWidthPx &&
                        runtimeDeclaredMaxHeight == decoderSelection.expectedDeclaredMaxHeightPx &&
                        fixedVideoMaximumDimensionsMatch(
                            encodedWidthPx = inputEncoded.widthPx,
                            encodedHeightPx = inputEncoded.heightPx,
                            declaredMaxWidthPx = runtimeDeclaredMaxWidth,
                            declaredMaxHeightPx = runtimeDeclaredMaxHeight,
                        )
                ) {
                    "Video adaptive maximum dimensions changed after preflight"
                }
                val inputVisible = format.visibleVideoDimensionsOrNull()
                    ?: error("Video crop/visible dimensions unavailable")
                check(
                    inputVisible.widthPx == decoderSelection.expectedVisibleWidthPx &&
                        inputVisible.heightPx == decoderSelection.expectedVisibleHeightPx
                ) {
                    "Video visible dimensions changed after preflight"
                }
                val runtimeFps = format.mediaNumberOrNull(MediaFormat.KEY_FRAME_RATE)
                    ?.toFloat()
                check(videoFrameRatesMatch(runtimeFps, decoderSelection.expectedSourceFps)) {
                    "Video FPS changed after preflight"
                }
                val runtimeProfile = format.strictOptionalIntegerOrNull(MediaFormat.KEY_PROFILE)
                check(runtimeProfile == decoderSelection.expectedProfile) {
                    "Video profile changed after preflight"
                }
                val runtimeLevel = format.strictOptionalIntegerOrNull(MediaFormat.KEY_LEVEL)
                check(runtimeLevel == decoderSelection.expectedLevel) {
                    "Video level changed after preflight"
                }
                val runtimeBitRate = format.strictOptionalIntegerOrNull(MediaFormat.KEY_BIT_RATE)
                check(runtimeBitRate == decoderSelection.expectedBitRate) {
                    "Video bitrate changed after preflight"
                }
                val runtimeMaxInputSize =
                    format.strictOptionalIntegerOrNull(MediaFormat.KEY_MAX_INPUT_SIZE)
                check(runtimeMaxInputSize == decoderSelection.expectedMaxInputSize) {
                    "Video maximum input size changed after preflight"
                }
                check(
                    runtimeMaxInputSize == null ||
                        runtimeMaxInputSize <= decoderSelection.maxCompressedSampleBytes
                ) {
                    "Video maximum input size exceeds the run safety limit"
                }
                val runtimeRotation =
                    format.strictOptionalIntegerOrNull(MediaFormat.KEY_ROTATION) ?: 0
                check(runtimeRotation == decoderSelection.expectedRotationDegrees) {
                    "Video rotation changed after preflight"
                }
                val runtimeCodecsString = format.boundedCodecsString()
                check(runtimeCodecsString == decoderSelection.expectedCodecsString) {
                    "Video codec string changed after preflight"
                }
                val runtimeCodecConfigFingerprint = format.codecConfigFingerprintOrNull()
                    ?: error("Video codec configuration is invalid")
                check(runtimeCodecConfigFingerprint == decoderSelection.codecConfigFingerprint) {
                    "Video codec configuration changed after preflight"
                }
                if (decoderSelection.requiresVerifiedP010) {
                    check(runtimeProfile != null) {
                        "P010 profile is no longer verifiable"
                    }
                }
                val maxEncodedWidth = decoderSelection.maxEncodedWidthPx
                    ?: error("Video max width unavailable")
                val maxEncodedHeight = decoderSelection.maxEncodedHeightPx
                    ?: error("Video max height unavailable")
                val presentationDimensions = presentationVideoDimensions(
                    inputVisible,
                    runtimeRotation,
                ) ?: error("Video rotation is invalid")
                // This run intentionally accepts only a fixed-resolution track. KEY_MAX_* enables
                // adaptive playback and is merely an allocation hint, not a hard ceiling, so even
                // an exact source pair is removed before configure. The immutable input fingerprint
                // and output-format ceiling remain the fail-closed guards.
                format.removeKey(MediaFormat.KEY_MAX_WIDTH)
                format.removeKey(MediaFormat.KEY_MAX_HEIGHT)
                session.postFixedSize(
                    presentationDimensions.widthPx,
                    presentationDimensions.heightPx,
                )
                val activeCodec = MediaCodec.createByCodecName(decoderSelection.codecName)
                codec = activeCodec
                session.ensureSetupActive(surface)
                var frameCallbackEpoch = session.beginFrameCallbackEpoch()
                session.installFrameRenderedListener(
                    codec = activeCodec,
                    handler = frameCallbackHandler,
                    epoch = frameCallbackEpoch,
                )
                activeCodec.configure(format, surface, null, 0)
                session.ensureSetupActive(surface)
                activeCodec.start()

                val outputInfo = MediaCodec.BufferInfo()
                var inputEos = false
                var nextRenderNanos = System.nanoTime()
                while (session.isActive() && surface.isValid) {
                    var inputProgress = false
                    if (!inputEos) {
                        val inputIndex = activeCodec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                        if (inputIndex >= 0) {
                            inputProgress = true
                            val declaredSampleSize = activeExtractor.sampleSize
                            if (declaredSampleSize < 0L) {
                                activeCodec.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputEos = true
                            } else {
                                val codecInputFlags = mediaCodecInputFlags(
                                    activeExtractor.sampleFlags,
                                )
                                check(
                                    declaredSampleSize <=
                                        decoderSelection.maxCompressedSampleBytes.toLong()
                                ) {
                                    "Compressed video sample exceeds the run safety limit"
                                }
                                val input = activeCodec.getInputBuffer(inputIndex)
                                    ?: error("Decoder input buffer unavailable")
                                check(declaredSampleSize <= input.remaining().toLong()) {
                                    "Compressed video sample exceeds decoder input capacity"
                                }
                                val size = activeExtractor.readSampleData(input, 0)
                                check(
                                    size >= 0 &&
                                        size.toLong() == declaredSampleSize
                                ) {
                                    "Compressed video sample size changed while reading"
                                }
                                activeCodec.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    size,
                                    activeExtractor.sampleTime.coerceAtLeast(0L),
                                    codecInputFlags,
                                )
                                activeExtractor.advance()
                            }
                        }
                    }

                    val outputIndex = activeCodec.dequeueOutputBuffer(
                        outputInfo,
                        CODEC_TIMEOUT_US,
                    )
                    val outputProgress =
                        outputIndex >= 0 ||
                            outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
                    when {
                        outputIndex >= 0 -> {
                            val isEos = outputInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            if (outputInfo.size > 0 || !isEos) {
                                val frameCommit = session.captureFrameCommit()
                                val interval =
                                    (1_000_000_000L / safeProducerFps(session.targetFps)).toLong()
                                val now = System.nanoTime()
                                if (nextRenderNanos < now - MAX_PACING_LAG_NANOS) {
                                    nextRenderNanos = now
                                }
                                val presentationTimeUs = outputInfo.presentationTimeUs
                                check(
                                    frameCommit == null ||
                                        session.offerFrameCommit(
                                            epoch = frameCallbackEpoch,
                                            presentationTimeUs = presentationTimeUs,
                                            commit = frameCommit,
                                        )
                                ) {
                                    "MediaCodec frame-commit queue capacity exceeded"
                                }
                                try {
                                    activeCodec.releaseOutputBuffer(outputIndex, nextRenderNanos)
                                } catch (failure: Throwable) {
                                    if (frameCommit != null) {
                                        session.removeFrameCommit(
                                            epoch = frameCallbackEpoch,
                                            presentationTimeUs = presentationTimeUs,
                                            commit = frameCommit,
                                        )
                                    }
                                    throw failure
                                }
                                nextRenderNanos += interval
                            } else {
                                activeCodec.releaseOutputBuffer(outputIndex, false)
                            }
                            if (isEos && session.isActive()) {
                                val callbackDrainDeadlineNanos =
                                    decoderCallbackDrainDeadlineNanos()
                                check(
                                    session.disableAndDrainFrameRenderedListener(
                                        codec = activeCodec,
                                        handler = frameCallbackHandler,
                                        absoluteDeadlineNanos = callbackDrainDeadlineNanos,
                                    )
                                ) {
                                    "MediaCodec pre-flush frame callback drain timed out"
                                }
                                activeCodec.flush()
                                // MediaCodec's EventHandler resolves the current listener only when
                                // a queued message runs. Purge and drain once more while the native
                                // listener is still disabled so a pre-flush message cannot inherit
                                // the next epoch's listener and consume its same-PTS token.
                                check(
                                    session.disableAndDrainFrameRenderedListener(
                                        codec = activeCodec,
                                        handler = frameCallbackHandler,
                                        absoluteDeadlineNanos = callbackDrainDeadlineNanos,
                                    )
                                ) {
                                    "MediaCodec post-flush frame callback drain timed out"
                                }
                                frameCallbackEpoch = session.beginFrameCallbackEpoch()
                                session.installFrameRenderedListener(
                                    codec = activeCodec,
                                    handler = frameCallbackHandler,
                                    epoch = frameCallbackEpoch,
                                )
                                activeExtractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                                inputEos = false
                                nextRenderNanos = System.nanoTime()
                            }
                        }

                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outputFormat = activeCodec.outputFormat
                            val outputEncoded = outputFormat.encodedVideoDimensions()
                                ?: error("Decoder output dimensions unavailable")
                            check(
                                videoDimensionsFitWithin(
                                    widthPx = outputEncoded.widthPx,
                                    heightPx = outputEncoded.heightPx,
                                    ceilingWidthPx = maxEncodedWidth,
                                    ceilingHeightPx = maxEncodedHeight,
                                )
                            ) {
                                "Decoder output exceeds the preflight allocation ceiling"
                            }
                            val outputVisible = outputFormat.visibleVideoDimensionsOrNull()
                                ?: error("Decoder output crop is invalid")
                            check(
                                sameVideoDimensions(
                                    outputVisible.widthPx,
                                    outputVisible.heightPx,
                                    decoderSelection.expectedVisibleWidthPx,
                                    decoderSelection.expectedVisibleHeightPx,
                                )
                            ) {
                                "Decoder output resolution changed after preflight"
                            }
                            session.postFixedSize(
                                presentationDimensions.widthPx,
                                presentationDimensions.heightPx,
                            )
                        }
                    }
                    val parkNanos = codecNoProgressParkNanos(
                        inputProgress = inputProgress,
                        outputProgress = outputProgress,
                    )
                    if (parkNanos > 0L) {
                        LockSupport.parkNanos(parkNanos)
                    }
                }
            } catch (_: InterruptedException) {
                // Normal release path.
            } catch (error: Throwable) {
                if (error is ThreadDeath || error is VirtualMachineError) {
                    fatalFailure = error
                    throw error
                }
                if (
                    session.isActive()
                ) {
                    try {
                        session.dispatchRuntimeFailure(
                            buildRuntimeFailureReason("MediaCodec", error),
                        )
                    } catch (notificationFailure: Throwable) {
                        if (
                            notificationFailure is ThreadDeath ||
                            notificationFailure is VirtualMachineError
                        ) {
                            fatalFailure = notificationFailure
                        }
                        throw notificationFailure
                    }
                }
            } finally {
                // Keep the native cleanup prefix allocation-free. This finally block also runs
                // while an OutOfMemoryError is propagating; allocating an evidence collection here
                // could mask the original fatal error and skip codec/extractor release entirely.
                var extractorReleaseFailure: Throwable? = null
                var listenerDetachFailure: Throwable? = null
                var codecReleaseFailure: Throwable? = null
                var cleanupFatalFailure: Throwable? = null
                // Invalidate the allocation-free callback fast path before making any potentially
                // blocking platform cleanup call. MediaCodec may already have queued a rendered
                // callback, but it can no longer escape this session after the gate is closed.
                try {
                    session.frameCallbackGate.close()
                } catch (error: Throwable) {
                    if (error is ThreadDeath || error is VirtualMachineError) {
                        cleanupFatalFailure = error
                    }
                }
                try {
                    session.clearFrameCommits()
                } catch (error: Throwable) {
                    if (
                        cleanupFatalFailure == null &&
                        (error is ThreadDeath || error is VirtualMachineError)
                    ) {
                        cleanupFatalFailure = error
                    }
                }
                try {
                    session.frameCallbackHandler.get()?.removeCallbacksAndMessages(null)
                } catch (error: Throwable) {
                    if (
                        cleanupFatalFailure == null &&
                        (error is ThreadDeath || error is VirtualMachineError)
                    ) {
                        cleanupFatalFailure = error
                    }
                }
                // MediaExtractor owns the duplicated selected-media descriptor. It is independent
                // from codec shutdown, so release it first instead of retaining the FD while a
                // vendor codec stop/release call is slow.
                val activeExtractor = extractor
                if (activeExtractor != null) {
                    try {
                        activeExtractor.release()
                    } catch (error: Throwable) {
                        extractorReleaseFailure = error
                        if (
                            cleanupFatalFailure == null &&
                            (error is ThreadDeath || error is VirtualMachineError)
                        ) {
                            cleanupFatalFailure = error
                        }
                    }
                }
                val activeCodec = codec
                if (activeCodec != null) {
                    val callbackHandler = session.frameCallbackHandler.get()
                    if (callbackHandler != null) {
                        try {
                            activeCodec.setOnFrameRenderedListener(
                                null,
                                callbackHandler,
                            )
                        } catch (error: Throwable) {
                            listenerDetachFailure = error
                            if (
                                cleanupFatalFailure == null &&
                                (error is ThreadDeath || error is VirtualMachineError)
                            ) {
                                cleanupFatalFailure = error
                            }
                        }
                    }
                }
                try {
                    session.frameCallbackThread.requestQuit()
                } catch (error: Throwable) {
                    if (
                        cleanupFatalFailure == null &&
                        (error is ThreadDeath || error is VirtualMachineError)
                    ) {
                        cleanupFatalFailure = error
                    }
                }
                if (activeCodec != null) {
                    try {
                        activeCodec.stop()
                    } catch (error: Throwable) {
                        if (
                            cleanupFatalFailure == null &&
                            (error is ThreadDeath || error is VirtualMachineError)
                        ) {
                            cleanupFatalFailure = error
                        }
                    }
                    try {
                        activeCodec.release()
                    } catch (error: Throwable) {
                        codecReleaseFailure = error
                        if (
                            cleanupFatalFailure == null &&
                            (error is ThreadDeath || error is VirtualMachineError)
                        ) {
                            cleanupFatalFailure = error
                        }
                    }
                }
                val callbackStoppedWithinHandoff = try {
                    joinThreadUntil(
                        session.frameCallbackThread,
                        producerDrainDeadlineNanos(),
                    )
                } catch (error: Throwable) {
                    if (
                        cleanupFatalFailure == null &&
                        (error is ThreadDeath || error is VirtualMachineError)
                    ) {
                        cleanupFatalFailure = error
                    }
                    false
                }
                extractorReleaseFailure?.let { failure ->
                    try {
                        PinnedMediaCleanupState.markUnconfirmed(
                            failure.javaClass.simpleName.ifBlank {
                                failure.javaClass.name
                            },
                        )
                    } catch (error: Throwable) {
                        // Native ownership has already been released/attempted. Preserve a fatal
                        // worker error instead of masking it with evidence formatting.
                        if (
                            cleanupFatalFailure == null &&
                            (error is ThreadDeath || error is VirtualMachineError)
                        ) {
                            cleanupFatalFailure = error
                        }
                    }
                }
                val nativeCleanupFailed =
                    extractorReleaseFailure != null || codecReleaseFailure != null
                if (nativeCleanupFailed) {
                    val evidenceFailure =
                        extractorReleaseFailure ?: codecReleaseFailure ?: listenerDetachFailure
                    try {
                        RendererSafetyState.markCleanupFailure(
                            component = "decoder native cleanup",
                            detail =
                                evidenceFailure?.javaClass?.simpleName
                                    ?: "release failed",
                        )
                    } catch (error: Throwable) {
                        // Teardown callback below remains a second fail-closed signal.
                        if (
                            cleanupFatalFailure == null &&
                            (error is ThreadDeath || error is VirtualMachineError)
                        ) {
                            cleanupFatalFailure = error
                        }
                    }
                }
                // Missing the 16 ms UI hand-off is transient: joinThreadUntil() has already
                // registered the live callback thread in RendererSafetyState, and the controller's
                // five-second recovery barrier owns the terminal decision. Only a confirmed native
                // cleanup failure is terminal at this point.
                if (
                    decoderTeardownRequiresTerminalSignal(
                        callbackStoppedWithinHandoff = callbackStoppedWithinHandoff,
                        nativeCleanupFailed = nativeCleanupFailed,
                    )
                ) {
                    try {
                        session.dispatchTeardownFailure()
                    } catch (error: Throwable) {
                        // Do not replace an in-flight fatal worker error.
                        if (
                            cleanupFatalFailure == null &&
                            (error is ThreadDeath || error is VirtualMachineError)
                        ) {
                            cleanupFatalFailure = error
                        }
                    }
                }
                sessionRunning.set(false)
                val finishFailure = postDecoderFinishedOrDetach(
                    postFinished = session::postFinished,
                    detachUiCallbacks = session::detachUiCallbacks,
                )
                if (
                    finishFailure is ThreadDeath ||
                    finishFailure is VirtualMachineError
                ) {
                    cleanupFatalFailure =
                        mergeFailurePreservingFatal(cleanupFatalFailure, finishFailure)
                }
                val cleanupFatal = cleanupFatalFailure
                if (
                    fatalFailure == null &&
                    (cleanupFatal is ThreadDeath || cleanupFatal is VirtualMachineError)
                ) {
                    throw cleanupFatal
                }
            }
        }
        val thread = try {
            Thread(decoderRunnable, "DpuLab-MediaCodec")
        } catch (error: Throwable) {
            sessionRunning.set(false)
            frameCallbackGate.close()
            val terminal = decoderCreationFailureAfterCleanup(
                primaryFailure = error,
                requestQuit = { frameCallbackThread.requestQuit() },
                joinCallbackThread = {
                    joinThreadUntil(
                        frameCallbackThread,
                        producerDrainDeadlineNanos(),
                    )
                },
            )
            // A live callback thread is process-leased by joinThreadUntil(). The five-second
            // recovery boundary, not this short hand-off, decides whether teardown is terminal.
            if (terminal is Error) throw terminal
            onRuntimeFailure?.invoke(
                buildRuntimeFailureReason("MediaCodec thread create", terminal),
            )
            return
        }
        session = try {
            DecoderSession(
                running = sessionRunning,
                thread = thread,
                frameCallbackThread = frameCallbackThread,
                frameCallbackHandler = AtomicReference(null),
                frameCallbackGate = frameCallbackGate,
                initialTargetFps = targetFps,
                captureFrameCommit = captureFrameCommit,
                uiCallbacks = DecoderUiCallbacks(
                    onFixedSize = { width, height ->
                        if (decoderSession === session) holder.setFixedSize(width, height)
                    },
                    onRuntimeFailure = onRuntimeFailure,
                    onTeardownFailure = onTeardownFailure,
                    onFinished = { finished ->
                        if (
                            decoderSession === finished &&
                            !finished.thread.isAlive &&
                            !finished.frameCallbackThread.isAlive
                        ) {
                            decoderSession = null
                        }
                    },
                ),
            )
        } catch (error: Throwable) {
            // The callback HandlerThread is already running at this point while the decoder
            // Thread has not started. Roll that owned prefix back before propagating OOM/fatal
            // errors, otherwise a constructor failure leaves a Looper retaining the process.
            sessionRunning.set(false)
            frameCallbackGate.close()
            val terminal = decoderCreationFailureAfterCleanup(
                primaryFailure = error,
                requestQuit = { frameCallbackThread.requestQuit() },
                joinCallbackThread = {
                    joinThreadUntil(
                        frameCallbackThread,
                        producerDrainDeadlineNanos(),
                    )
                },
            )
            if (terminal is Error) throw terminal
            onRuntimeFailure?.invoke(
                buildRuntimeFailureReason("MediaCodec session create", terminal),
            )
            return
        }
        decoderSession = session
        val threadStarted = startRendererThread(thread) { error ->
            var terminalFailure: Throwable? = null
            try {
                session.dispatchRuntimeFailure(
                    buildRuntimeFailureReason("MediaCodec thread start", error),
                )
            } catch (failure: Throwable) {
                terminalFailure = mergeFailurePreservingFatal(terminalFailure, failure)
            }
            val teardown = teardownDecoderSession(
                session = session,
                stopDeadlineNanos = producerDrainDeadlineNanos(),
            )
            teardown.failure?.let { failure ->
                terminalFailure = mergeFailurePreservingFatal(terminalFailure, failure)
            }
            if (teardown.fullyStopped && decoderSession === session) {
                decoderSession = null
            }
            terminalFailure?.let { throw it }
        }
        if (!threadStarted) {
            return
        }
    }

    fun release(stopDeadlineNanos: Long = producerDrainDeadlineNanos()): Boolean {
        val session = decoderSession ?: return true
        val teardown = teardownDecoderSession(session, stopDeadlineNanos)
        if (teardown.fullyStopped && decoderSession === session) {
            decoderSession = null
        }
        teardown.failure?.let { throw it }
        return teardown.fullyStopped
    }

    fun requestStop() {
        var terminalFailure: Throwable? = null
        try {
            removalRequested.set(true)
        } catch (failure: Throwable) {
            terminalFailure = mergeFailurePreservingFatal(terminalFailure, failure)
        }
        try {
            leaseStart.cancel()
        } catch (failure: Throwable) {
            terminalFailure = mergeFailurePreservingFatal(terminalFailure, failure)
        }
        val session = decoderSession
        if (session != null) {
            try {
                requestStop(session)
            } catch (failure: Throwable) {
                terminalFailure = mergeFailurePreservingFatal(terminalFailure, failure)
            }
        }
        terminalFailure?.let { throw it }
    }

    private fun requestStop(session: DecoderSession) {
        val terminal = performDecoderStopRequestActions(
            detachUiCallbacks = session::detachUiCallbacks,
            stopDecoder = { session.running.set(false) },
            closeFrameCallbacks = session::closeFrameCallbacks,
            interruptDecoder = session.thread::interrupt,
        )
        terminal?.let { throw it }
    }

    private fun teardownDecoderSession(
        session: DecoderSession,
        stopDeadlineNanos: Long,
    ): DecoderTeardownOutcome =
        performDecoderTeardown(
            requestStop = { requestStop(session) },
            joinDecoder = {
                Thread.currentThread() === session.thread ||
                    joinThreadUntil(session.thread, stopDeadlineNanos)
            },
            joinCallbacks = {
                Thread.currentThread() === session.frameCallbackThread ||
                    joinThreadUntil(session.frameCallbackThread, stopDeadlineNanos)
            },
        )

    private class DecoderSession(
        val running: AtomicBoolean,
        val thread: Thread,
        val frameCallbackThread: BoundedCallbackHandlerThread,
        val frameCallbackHandler: AtomicReference<Handler?>,
        val frameCallbackGate: DecoderFrameCallbackGate,
        initialTargetFps: Float,
        captureFrameCommit: (() -> (() -> Unit)?)?,
        uiCallbacks: DecoderUiCallbacks,
    ) {
        @Volatile
        var targetFps: Float = safeProducerFps(initialTargetFps)

        private val attachedToView = AtomicBoolean(true)
        private val uiCallbacks = AtomicReference<DecoderUiCallbacks?>(uiCallbacks)
        private val frameCommitCapture =
            AtomicReference<(() -> (() -> Unit)?)?>(captureFrameCommit)
        private val frameCommits = DecoderFrameCommitQueue(MAX_DECODER_FRAME_COMMITS)
        private val frameCallbackDrainBarrier = DecoderCallbackDrainBarrier()
        private val uiHandler = Handler(Looper.getMainLooper())
        @Volatile
        private var frameCallbackEpoch = 0L

        fun isActive(): Boolean = running.get() && attachedToView.get()

        fun ensureSetupActive(surface: Surface) {
            if (!isActive() || !surface.isValid) throw InterruptedException()
        }

        fun captureFrameCommit(): (() -> Unit)? = frameCommitCapture.get()?.invoke()

        @Synchronized
        fun beginFrameCallbackEpoch(): Long {
            frameCommits.clear()
            val next = nextDecoderFrameCallbackEpoch(frameCallbackEpoch)
                ?: error("MediaCodec frame callback epoch exhausted or invalid")
            frameCallbackEpoch = next
            return next
        }

        fun installFrameRenderedListener(
            codec: MediaCodec,
            handler: Handler,
            epoch: Long,
        ) {
            check(epoch > 0L && epoch == frameCallbackEpoch) {
                "MediaCodec frame callback epoch is stale or invalid"
            }
            codec.setOnFrameRenderedListener(
                { _, presentationTimeUs, _ ->
                    if (frameCallbackGate.isOpen() && isActive()) {
                        emitFrame(epoch, presentationTimeUs)
                    }
                },
                handler,
            )
        }

        fun disableAndDrainFrameRenderedListener(
            codec: MediaCodec,
            handler: Handler,
            absoluteDeadlineNanos: Long,
        ): Boolean {
            codec.setOnFrameRenderedListener(null, handler)
            return frameCallbackDrainBarrier.await(
                handler = handler,
                running = running,
                absoluteDeadlineNanos = absoluteDeadlineNanos,
            )
        }

        fun offerFrameCommit(
            epoch: Long,
            presentationTimeUs: Long,
            commit: () -> Unit,
        ): Boolean =
            epoch > 0L &&
                epoch == frameCallbackEpoch &&
                frameCommits.offer(epoch, presentationTimeUs, commit)

        fun removeFrameCommit(
            epoch: Long,
            presentationTimeUs: Long,
            commit: () -> Unit,
        ): Boolean = frameCommits.remove(epoch, presentationTimeUs, commit)

        fun clearFrameCommits() {
            frameCommits.clear()
        }

        fun emitFrame(
            epoch: Long,
            presentationTimeUs: Long,
        ) {
            try {
                frameCommits.invoke(epoch, presentationTimeUs)
            } catch (failure: Throwable) {
                running.set(false)
                reportProducerFailureFailClosed(failure) { reported ->
                    dispatchRuntimeFailure(
                        buildRuntimeFailureReason("MediaCodec frame callback", reported),
                    )
                }
            }
        }

        fun postFixedSize(width: Int, height: Int) {
            uiHandler.post {
                uiCallbacks.get()?.onFixedSize?.invoke(width, height)
            }
        }

        fun dispatchRuntimeFailure(reason: String) {
            uiCallbacks.get()?.onRuntimeFailure?.invoke(reason)
        }

        fun dispatchTeardownFailure() {
            // postFinished() atomically detaches the session immediately afterward. Capture this
            // terminal callback now so that detachment cannot erase an already confirmed native
            // cleanup failure before the main-queue Runnable executes.
            val callback = uiCallbacks.get()?.onTeardownFailure
            uiHandler.post {
                callback?.invoke()
            }
        }

        fun postFinished(): Boolean {
            attachedToView.set(false)
            frameCommitCapture.set(null)
            frameCommits.clear()
            val callback = uiCallbacks.getAndSet(null)?.onFinished
            return uiHandler.post {
                callback?.invoke(this)
            }
        }

        fun detachUiCallbacks() {
            // Pending main-queue Runnables retain this Activity-free session only and re-read the
            // callback reference when they execute; clearing it breaks the View/Activity path.
            val terminal = performDecoderUiDetachActions(
                markDetached = { attachedToView.set(false) },
                clearFrameCommitCapture = { frameCommitCapture.set(null) },
                clearFrameCommits = frameCommits::clear,
                clearUiCallbacks = { uiCallbacks.set(null) },
            )
            terminal?.let { throw it }
        }

        fun closeFrameCallbacks() {
            val terminal = performDecoderFrameCallbackCloseActions(
                closeGate = frameCallbackGate::close,
                clearFrameCommits = frameCommits::clear,
                removeQueuedCallbacks = {
                    frameCallbackHandler.get()?.removeCallbacksAndMessages(null)
                },
                requestQuit = frameCallbackThread::requestQuit,
            )
            terminal?.let { throw it }
        }
    }

    private data class DecoderUiCallbacks(
        val onFixedSize: ((Int, Int) -> Unit)?,
        val onRuntimeFailure: ((String) -> Unit)?,
        val onTeardownFailure: (() -> Unit)?,
        val onFinished: ((DecoderSession) -> Unit)?,
    )

    companion object {
        private const val CODEC_TIMEOUT_US = 10_000L
        private const val MAX_PACING_LAG_NANOS = 250_000_000L
        private const val CALLBACK_HANDLER_READY_TIMEOUT_MS = 1_000L
        private const val MAX_DECODER_FRAME_COMMITS = 64
    }
}

/**
 * MediaExtractor and MediaCodec use different bit assignments after the sync/key-frame bit.
 * Passing extractor flags through verbatim can turn an encrypted sample into CODEC_CONFIG or a
 * partial sample into EOS. Secure input is deliberately unsupported by this deterministic lab,
 * so encrypted and unknown flags fail closed before any bytes are queued.
 */
internal fun mediaCodecInputFlags(sampleFlags: Int): Int {
    require(sampleFlags >= 0) { "Negative extractor sample flags" }
    val knownExtractorFlags =
        MediaExtractor.SAMPLE_FLAG_SYNC or
            MediaExtractor.SAMPLE_FLAG_ENCRYPTED or
            MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME
    require(sampleFlags and knownExtractorFlags.inv() == 0) {
        "Unknown extractor sample flags"
    }
    require(sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) {
        "Encrypted media samples require secure input and are unsupported"
    }
    var codecFlags = 0
    if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
        codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
    }
    if (sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) {
        codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
    }
    return codecFlags
}

/**
 * A synchronous codec may return TRY_AGAIN immediately on both ports despite the dequeue timeout.
 * Yield only in that no-progress case so a stalled decoder cannot spin at a full CPU core while
 * successful input/output work and format changes retain their original pacing.
 */
internal fun codecNoProgressParkNanos(
    inputProgress: Boolean,
    outputProgress: Boolean,
    requestedParkNanos: Long = DEFAULT_CODEC_NO_PROGRESS_PARK_NANOS,
): Long {
    if (inputProgress || outputProgress) return 0L
    return requestedParkNanos.coerceIn(
        MIN_CODEC_NO_PROGRESS_PARK_NANOS,
        MAX_CODEC_NO_PROGRESS_PARK_NANOS,
    )
}

private const val MIN_CODEC_NO_PROGRESS_PARK_NANOS = 250_000L
private const val DEFAULT_CODEC_NO_PROGRESS_PARK_NANOS = 500_000L
private const val MAX_CODEC_NO_PROGRESS_PARK_NANOS = 1_000_000L

private fun MediaFormat.mediaNumberOrNull(key: String): Number? {
    if (!containsKey(key)) return null
    return try {
        getNumber(key)
    } catch (_: Exception) {
        null
    }
}

private fun MediaFormat.mediaIntegerOrNull(key: String): Int? =
    exactMediaIntegerOrNull(mediaNumberOrNull(key))

private fun MediaFormat.strictOptionalIntegerOrNull(key: String): Int? {
    if (!containsKey(key)) return null
    return mediaIntegerOrNull(key)
        ?: throw IllegalArgumentException("Invalid integer MediaFormat key: $key")
}

private fun MediaFormat.encodedVideoDimensions(): VideoDimensions? =
    visibleVideoDimensions(
        encodedWidthPx = mediaIntegerOrNull(MediaFormat.KEY_WIDTH),
        encodedHeightPx = mediaIntegerOrNull(MediaFormat.KEY_HEIGHT),
    )

private fun MediaFormat.visibleVideoDimensionsOrNull(): VideoDimensions? =
    visibleVideoDimensions(
        encodedWidthPx = mediaIntegerOrNull(MediaFormat.KEY_WIDTH),
        encodedHeightPx = mediaIntegerOrNull(MediaFormat.KEY_HEIGHT),
        cropLeft = strictOptionalIntegerOrNull(MEDIA_KEY_CROP_LEFT_COMPAT),
        cropRight = strictOptionalIntegerOrNull(MEDIA_KEY_CROP_RIGHT_COMPAT),
        cropTop = strictOptionalIntegerOrNull(MEDIA_KEY_CROP_TOP_COMPAT),
        cropBottom = strictOptionalIntegerOrNull(MEDIA_KEY_CROP_BOTTOM_COMPAT),
    )

private fun MediaFormat.boundedCodecsString(): String? {
    if (!containsKey(MEDIA_KEY_CODECS_STRING_COMPAT)) return null
    val value = try {
        getString(MEDIA_KEY_CODECS_STRING_COMPAT)
    } catch (error: Exception) {
        throw IllegalArgumentException("Invalid codec string MediaFormat value", error)
    } ?: return null
    require(value.isNotBlank() && value.length <= MAX_RUNTIME_CODECS_STRING_CHARS) {
        "Invalid codec string MediaFormat value"
    }
    return value
}

private fun MediaFormat.codecConfigFingerprintOrNull(): String? {
    val codecConfigKeys = keys
        .filter { it.startsWith("csd-") }
        .sorted()
    val entries = ArrayList<Pair<String, java.nio.ByteBuffer>>(codecConfigKeys.size)
    for (key in codecConfigKeys) {
        val buffer = try {
            getByteBuffer(key)
        } catch (_: Exception) {
            null
        } ?: return null
        entries += key to buffer
    }
    return boundedCodecConfigFingerprint(entries)
}

private fun buildRuntimeFailureReason(prefix: String, error: Throwable): String {
    val type = error.javaClass.simpleName.takeIf(String::isNotBlank) ?: "Throwable"
    val detail = error.message
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.take(MAX_RUNTIME_ERROR_DETAIL_CHARS)
    return buildString {
        append(prefix)
        append(' ')
        append(type)
        detail?.let {
            append(": ")
            append(it)
        }
    }.take(MAX_RUNTIME_FAILURE_REASON_CHARS)
}

private const val MAX_RUNTIME_CODECS_STRING_CHARS = 512
private const val MAX_RUNTIME_ERROR_DETAIL_CHARS = 160
private const val MAX_RUNTIME_FAILURE_REASON_CHARS = 240
private const val MAX_FLATTENED_SINGLE_LAYER_EXTRA_PASSES = 8

/**
 * Runs the post-to-BufferQueue completion callback shared by Canvas and EGL producers.
 *
 * A normal callback failure is returned as `false` only after the caller's fail-closed handler has
 * run. Fatal VM failures are reported through the same handler and then rethrown so the enclosing
 * producer `finally` can release its native resources without disguising the process failure.
 * This function is inline so the frame hot path does not allocate an adapter lambda.
 */
internal inline fun invokeProducerFrameCommitFailClosed(
    noinline frameCommit: (() -> Unit)?,
    onFailure: (Throwable) -> Unit,
): Boolean {
    try {
        frameCommit?.invoke()
        return true
    } catch (failure: Throwable) {
        return reportProducerFailureFailClosed(failure, onFailure)
    }
}

/**
 * Reports one producer failure without allowing a secondary notification failure to replace a
 * fatal VM error. Callers revoke their worker before entering this function and keep native
 * ownership cleanup in an enclosing `finally`.
 */
internal inline fun reportProducerFailureFailClosed(
    failure: Throwable,
    onFailure: (Throwable) -> Unit,
): Boolean {
    var notificationFailure: Throwable? = null
    try {
        onFailure(failure)
    } catch (error: Throwable) {
        notificationFailure = error
    }
    if (failure is ThreadDeath || failure is VirtualMachineError) throw failure
    notificationFailure?.let { throw it }
    return false
}

/**
 * Preserves a fatal producer failure across the two Texture cleanup operations. Cleanup failures
 * remain attached evidence, while a cleanup-only fatal is still fail-closed.
 */
internal fun fatalAfterProducerCleanup(
    originalFatal: Throwable?,
    surfaceCleanupFatal: Throwable?,
    surfaceTextureCleanupFatal: Throwable?,
): Throwable? {
    val terminal = originalFatal ?: surfaceCleanupFatal ?: surfaceTextureCleanupFatal ?: return null
    if (surfaceCleanupFatal != null && surfaceCleanupFatal !== terminal) {
        addSuppressedBestEffort(terminal, surfaceCleanupFatal)
    }
    if (surfaceTextureCleanupFatal != null && surfaceTextureCleanupFatal !== terminal) {
        addSuppressedBestEffort(terminal, surfaceTextureCleanupFatal)
    }
    return terminal
}

@PublishedApi
internal fun addSuppressedBestEffort(primary: Throwable, secondary: Throwable) {
    if (primary === secondary) return
    try {
        primary.addSuppressed(secondary)
    } catch (_: Throwable) {
        // Preserve the primary fatal identity even when the VM cannot allocate suppressed state.
    }
}

private fun cleanupFatalOrNull(
    cleanupError: Throwable,
    reportError: Throwable?,
): Throwable? {
    val cleanupFatal = cleanupError.takeIf {
        it is ThreadDeath || it is VirtualMachineError
    }
    val reportFatal = reportError?.takeIf {
        it is ThreadDeath || it is VirtualMachineError
    }
    if (cleanupFatal != null && reportFatal != null) {
        addSuppressedBestEffort(cleanupFatal, reportFatal)
    }
    return cleanupFatal ?: reportFatal
}

internal fun flattenedSingleLayerExtraPasses(complexity: Float): Int {
    val normalized = complexity
        .takeIf(Float::isFinite)
        ?.coerceIn(0f, 1f)
        ?: 0f
    return if (normalized <= 0f) {
        0
    } else {
        ceil(normalized * MAX_FLATTENED_SINGLE_LAYER_EXTRA_PASSES)
            .toInt()
            .coerceIn(1, MAX_FLATTENED_SINGLE_LAYER_EXTRA_PASSES)
    }
}

private class CanvasDrawingLoop(
    private val surface: Surface,
    private val layerIndex: Int,
    initialTargetFps: Float,
    private val logicalYuv: Boolean,
    private val sbwcRequested: Boolean,
    private val logicalLayerCount: Int,
    private val translucentContent: Boolean = false,
    private val releaseSurfaceOnExit: Boolean = false,
    private val ownedSurfaceTexture: SurfaceTexture? = null,
    initialComplexity: Float = 0f,
    initialLoadShape: LoadShape = LoadShape.STEADY,
    initialLoadStartedMs: Long = SystemClock.elapsedRealtime(),
    captureFrameCommit: (() -> (() -> Unit)?)?,
    onRuntimeFailure: ((String) -> Unit)?,
) : Runnable {

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    @Volatile
    private var targetFps = safeProducerFps(initialTargetFps)
    @Volatile
    private var complexity =
        initialComplexity.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
    @Volatile
    private var loadShape = initialLoadShape
    @Volatile
    private var loadStartedMs = initialLoadStartedMs
    private val captureFrameCommit = AtomicReference(captureFrameCommit)
    private val runtimeFailureCallback = AtomicReference(onRuntimeFailure)
    private val painter = PatternPainter(
        index = layerIndex,
        logicalYuv = logicalYuv,
        sbwcRequested = sbwcRequested,
        translucentContent = translucentContent,
    )
    private val logicalPainters = List(logicalLayerCount.coerceIn(1, 24)) {
        PatternPainter(it, false, false, false)
    }

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        val candidate = Thread(this, "DpuLab-Surface-$layerIndex")
        thread = candidate
        return startRendererThread(candidate) { error ->
            running.set(false)
            thread = null
            runtimeFailureCallback.getAndSet(null)?.invoke(
                buildRuntimeFailureReason("Canvas thread start", error),
            )
            captureFrameCommit.set(null)
        }
    }

    fun stop(stopDeadlineNanos: Long = producerDrainDeadlineNanos()): Boolean {
        requestStop()
        val activeThread = thread
        val stopped = activeThread == null ||
            Thread.currentThread() === activeThread ||
            joinThreadUntil(activeThread, stopDeadlineNanos)
        if (stopped) thread = null
        return stopped
    }

    fun requestStop() {
        // Break every path back to LayerStageView/Activity before the bounded join. If the native
        // lock/unlock call outlives the hand-off, the process lease retains only this scalar
        // session, its Surface, and immutable painters.
        captureFrameCommit.set(null)
        runtimeFailureCallback.set(null)
        running.set(false)
        thread?.interrupt()
    }

    fun setTargetFps(fps: Float) {
        targetFps = safeProducerFps(fps)
        thread?.let(LockSupport::unpark)
    }

    fun setLoad(load: Float, shape: LoadShape, loadStartedMs: Long) {
        complexity = load.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
        loadShape = shape
        this.loadStartedMs = loadStartedMs
        thread?.let(LockSupport::unpark)
    }

    override fun run() {
        var originalFatal: Throwable? = null
        try {
            try {
                runDrawingLoop()
            } catch (error: Throwable) {
                if (
                    releaseSurfaceOnExit &&
                    (error is ThreadDeath || error is VirtualMachineError)
                ) {
                    originalFatal = error
                } else {
                    throw error
                }
            }
        } finally {
            if (releaseSurfaceOnExit) {
                var surfaceCleanupFatal: Throwable? = null
                try {
                    surface.release()
                } catch (error: Throwable) {
                    var reportError: Throwable? = null
                    try {
                        RendererSafetyState.markCleanupFailure(
                            component = "Texture Canvas Surface",
                            detail = error.javaClass.simpleName,
                        )
                    } catch (failure: Throwable) {
                        reportError = failure
                    }
                    surfaceCleanupFatal = cleanupFatalOrNull(error, reportError)
                }
                var surfaceTextureCleanupFatal: Throwable? = null
                try {
                    ownedSurfaceTexture?.release()
                } catch (error: Throwable) {
                    var reportError: Throwable? = null
                    try {
                        RendererSafetyState.markCleanupFailure(
                            component = "Texture Canvas SurfaceTexture",
                            detail = error.javaClass.simpleName,
                        )
                    } catch (failure: Throwable) {
                        reportError = failure
                    }
                    surfaceTextureCleanupFatal = cleanupFatalOrNull(error, reportError)
                }
                fatalAfterProducerCleanup(
                    originalFatal = originalFatal,
                    surfaceCleanupFatal = surfaceCleanupFatal,
                    surfaceTextureCleanupFatal = surfaceTextureCleanupFatal,
                )?.let { terminal ->
                    throw terminal
                }
            }
        }
    }

    private fun runDrawingLoop() {
        var nextFrame = System.nanoTime()
        val startedNanos = nextFrame
        var consecutiveFailures = 0
        while (running.get() && surface.isValid) {
            val fps = targetFps
            val interval = (1_000_000_000L / fps).toLong()
            val now = System.nanoTime()
            if (now < nextFrame) {
                LockSupport.parkNanos((nextFrame - now).coerceAtMost(MAX_PARK_NANOS))
                continue
            }
            var canvas: Canvas? = null
            var frameDrawn: Boolean
            var posted = false
            var terminalFailure: Throwable? = null
            val frameCommit = captureFrameCommit.get()?.invoke()
            try {
                canvas = surface.lockHardwareCanvas()
                val elapsedSeconds = (System.nanoTime() - startedNanos) / 1_000_000_000f
                val frameComplexity = LoadShapeEvaluator.intensityAt(
                    base = complexity,
                    shape = loadShape,
                    elapsedMs = SystemClock.elapsedRealtime() - loadStartedMs,
                )
                if (logicalLayerCount == 1) {
                    painter.draw(canvas, elapsedSeconds)
                    repeat(flattenedSingleLayerExtraPasses(frameComplexity)) { passIndex ->
                        canvas.withSave {
                            val pass = passIndex + 1
                            val w = width.toFloat()
                            val h = height.toFloat()
                            translate(
                                sin(elapsedSeconds * 0.8f + pass) * w * 0.025f,
                                cos(elapsedSeconds * 0.7f + pass) * h * 0.025f,
                            )
                            rotate(
                                sin(elapsedSeconds * 0.6f + pass * 0.5f) *
                                    (1.5f + pass * 0.2f),
                                w * 0.5f,
                                h * 0.5f,
                            )
                            val passScale = 1f - (pass % 3) * 0.012f
                            scale(passScale, passScale, w * 0.5f, h * 0.5f)
                            painter.draw(this, elapsedSeconds + pass * 0.013f, clear = false)
                        }
                    }
                } else {
                    canvas.drawColor(Color.rgb(6, 12, 17))
                    val passes = logicalPainters.size + (frameComplexity * 8f).roundToInt()
                    repeat(passes) { passIndex ->
                        val logicalIndex = passIndex % logicalPainters.size
                        canvas.withSave {
                            val w = width.toFloat()
                            val h = height.toFloat()
                            val t = elapsedSeconds
                            val scale = 0.42f + (logicalIndex % 4) * 0.06f
                            translate(
                                w * 0.5f + sin(t * 0.9f + logicalIndex) * w * 0.32f,
                                h * 0.5f + cos(t * 0.7f + logicalIndex * 0.8f) * h * 0.32f,
                            )
                            rotate((t * (13 + logicalIndex) + logicalIndex * 31) % 360)
                            scale(scale, scale)
                            translate(-w * 0.5f, -h * 0.5f)
                            logicalPainters[logicalIndex].draw(
                                this,
                                elapsedSeconds,
                                clear = false,
                            )
                        }
                    }
                }
                frameDrawn = true
            } catch (error: Throwable) {
                frameDrawn = false
                if (error !is Exception) terminalFailure = error
            } finally {
                if (canvas != null) {
                    try {
                        surface.unlockCanvasAndPost(canvas)
                        posted = true
                    } catch (error: Throwable) {
                        if (error !is Exception && terminalFailure == null) {
                            terminalFailure = error
                        }
                    }
                }
            }
            val fatalFailure = terminalFailure
            if (fatalFailure != null) {
                running.set(false)
                reportProducerFailureFailClosed(fatalFailure) { failure ->
                    runtimeFailureCallback.get()?.invoke(
                        buildRuntimeFailureReason("Canvas", failure),
                    )
                }
                break
            }
            if (posted && frameDrawn) {
                consecutiveFailures = 0
                val committed = invokeProducerFrameCommitFailClosed(frameCommit) { failure ->
                    // Revoke the worker before a potentially fallible UI/runtime notification.
                    // The enclosing run() finally remains the native Texture ownership barrier.
                    running.set(false)
                    runtimeFailureCallback.get()?.invoke(
                        buildRuntimeFailureReason("Canvas frame callback", failure),
                    )
                }
                if (!committed) break
            } else {
                consecutiveFailures++
            }
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                if (running.get() && surface.isValid) {
                    runtimeFailureCallback.get()?.invoke(
                        "Canvas producer failed $consecutiveFailures consecutive frames",
                    )
                }
                running.set(false)
                break
            }
            if (consecutiveFailures > 0 && running.get()) {
                val backoffMs = (16L shl (consecutiveFailures - 1).coerceAtMost(4))
                    .coerceAtMost(250L)
                // SystemClock.sleep deliberately completes the whole delay after interrupt.
                // A producer being removed must instead wake inside the shared teardown budget.
                LockSupport.parkNanos(backoffMs * NANOS_PER_MILLI)
                if (Thread.interrupted() && !running.get()) break
            }
            val completed = System.nanoTime()
            nextFrame = if (completed - nextFrame >= interval) {
                completed + MIN_YIELD_NANOS
            } else {
                nextFrame + interval
            }
        }
    }

    companion object {
        private const val MAX_PARK_NANOS = 50_000_000L
        private const val MIN_YIELD_NANOS = 250_000L
        private const val MAX_CONSECUTIVE_FAILURES = 8
    }
}

private class PatternPainter(
    private val index: Int,
    private val logicalYuv: Boolean,
    private val sbwcRequested: Boolean,
    private val translucentContent: Boolean,
) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val hsv = FloatArray(3)
    private val labelRect = RectF()
    private val contentRect = RectF()
    private val layerLabel = "LAYER ${index + 1}"
    private val routeLabel = when {
        sbwcRequested -> "SBWC request · verify via vendor"
        logicalYuv -> "YUV visual proxy"
        else -> "RGB BufferQueue"
    }

    fun draw(canvas: Canvas, elapsedSeconds: Float, clear: Boolean = true) {
        val w = canvas.width.toFloat().coerceAtLeast(1f)
        val h = canvas.height.toFloat().coerceAtLeast(1f)
        val t = elapsedSeconds
        val hue = (index * 43f + t * 26f) % 360f
        hsv[0] = hue
        hsv[1] = if (logicalYuv) 0.48f else 0.72f
        hsv[2] = 0.36f
        val opaqueBase = Color.HSVToColor(hsv)
        val base = if (translucentContent) {
            Color.argb(
                TRANSLUCENT_BASE_ALPHA,
                Color.red(opaqueBase),
                Color.green(opaqueBase),
                Color.blue(opaqueBase),
            )
        } else {
            opaqueBase
        }
        if (clear) {
            if (translucentContent) {
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            }
            canvas.drawColor(base)
        } else {
            paint.color = Color.argb(205, Color.red(base), Color.green(base), Color.blue(base))
            contentRect.set(0f, 0f, w, h)
            canvas.drawRoundRect(contentRect, 36f, 36f, paint)
        }

        val tile = (w.coerceAtMost(h) / 8f).coerceAtLeast(28f)
        var y = -tile
        var row = 0
        while (y < h + tile) {
            var x = -tile
            var column = 0
            while (x < w + tile) {
                val movingX = x + ((t * (25 + index * 3)) % tile)
                val luma = if ((row + column + index) % 2 == 0) 0.92f else 0.46f
                hsv[0] = (hue + row * 8 + column * 5) % 360
                hsv[1] = if (logicalYuv) 0.26f else 0.68f
                hsv[2] = luma
                val opaqueTile = Color.HSVToColor(hsv)
                paint.color = if (translucentContent) {
                    Color.argb(
                        TRANSLUCENT_TILE_ALPHA,
                        Color.red(opaqueTile),
                        Color.green(opaqueTile),
                        Color.blue(opaqueTile),
                    )
                } else {
                    opaqueTile
                }
                canvas.drawRect(movingX, y, movingX + tile * 0.72f, y + tile * 0.72f, paint)
                x += tile
                column++
            }
            y += tile
            row++
        }

        paint.color = Color.argb(220, 4, 13, 17)
        labelRect.set(w * 0.04f, h * 0.05f, w * 0.53f, h * 0.22f)
        canvas.drawRoundRect(labelRect, 18f, 18f, paint)
        paint.color = Color.WHITE
        paint.textSize = (w.coerceAtMost(h) * 0.055f).coerceIn(18f, 56f)
        paint.isFakeBoldText = true
        canvas.drawText(layerLabel, w * 0.075f, h * 0.12f, paint)
        paint.textSize *= 0.55f
        paint.isFakeBoldText = false
        canvas.drawText(routeLabel, w * 0.075f, h * 0.185f, paint)

        hsv[0] = (hue + 180f) % 360f
        hsv[1] = 0.45f
        hsv[2] = 1f
        stroke.color = Color.HSVToColor(hsv)
        val radius = w.coerceAtMost(h) * (0.13f + (sin(t + index) + 1f) * 0.04f)
        canvas.drawCircle(w * 0.72f, h * 0.66f, radius, stroke)
    }

    private companion object {
        const val TRANSLUCENT_BASE_ALPHA = 176
        const val TRANSLUCENT_TILE_ALPHA = 196
    }
}

internal fun applyFrameRate(surface: Surface, fps: Float) {
    if (surface.isValid) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                surface.setFrameRate(
                    safeProducerFps(fps),
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                    Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS,
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                surface.setFrameRate(
                    safeProducerFps(fps),
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                )
            }
        } catch (_: Exception) {
            // A device may reject a non-critical hint. VM-fatal Errors must still escape.
        }
    }
}

private fun safeProducerFps(fps: Float): Float =
    fps.takeIf { it.isFinite() }?.coerceIn(1f, 120f) ?: 60f

private fun producerDrainDeadlineNanos(): Long =
    System.nanoTime() + PRODUCER_DRAIN_TIMEOUT_NANOS

private fun saturatingDeadline(nowMs: Long, timeoutMs: Long): Long =
    if (nowMs > Long.MAX_VALUE - timeoutMs) Long.MAX_VALUE else nowMs + timeoutMs

private fun joinThreadUntil(thread: Thread, deadlineNanos: Long): Boolean {
    val remainingNanos = deadlineNanos - System.nanoTime()
    if (!thread.isAlive) return true
    if (remainingNanos <= 0L) {
        RendererSafetyState.trackUnconfirmed(thread)
        return false
    }
    val millis = remainingNanos / NANOS_PER_MILLI
    val nanos = (remainingNanos % NANOS_PER_MILLI).toInt()
    try {
        thread.join(millis, nanos)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
    }
    val stopped = !thread.isAlive
    if (!stopped) RendererSafetyState.trackUnconfirmed(thread)
    return stopped
}

/**
 * A short hand-off timeout is represented by RendererSafetyState's live-thread lease. It becomes
 * terminal only if that lease survives the independent recovery deadline; this immediate signal
 * is reserved for a confirmed native cleanup failure.
 */
internal fun decoderTeardownRequiresTerminalSignal(
    callbackStoppedWithinHandoff: Boolean,
    nativeCleanupFailed: Boolean,
): Boolean = when {
    nativeCleanupFailed -> true
    callbackStoppedWithinHandoff -> false
    else -> false
}

/**
 * Once a Canvas loop starts it owns the TextureView Surface wrapper until its real thread finally.
 * This remains true after a 16 ms hand-off timeout; releasing from the View in that window races a
 * native lock/unlock still using the same wrapper.
 */
internal fun shouldViewReleaseTextureSurface(startedLoopPresent: Boolean): Boolean =
    !startedLoopPresent

/**
 * A started Texture Canvas producer owns both the Surface wrapper and its SurfaceTexture. Returning
 * true here would let TextureView release the native buffer producer before a delayed worker exits.
 */
internal fun shouldFrameworkReleaseTextureSurface(producerOwnsTexture: Boolean): Boolean =
    !producerOwnsTexture

private const val NANOS_PER_MILLI = 1_000_000L
private const val NANOS_PER_SECOND = 1_000_000_000.0
private const val LAYER_SIZE_TRANSFORM_CADENCE_NANOS = 100L * NANOS_PER_MILLI
private const val LAYER_GEOMETRY_ACK_FRAME_COUNT = 2
private const val GRADUAL_GEOMETRY_ORIGIN_KEY = 0
private const val GRADUAL_GEOMETRY_MID_KEY = 1
private const val GRADUAL_GEOMETRY_ENDPOINT_KEY = 2
private const val PRODUCER_DRAIN_TIMEOUT_NANOS = 16_000_000L
private const val PRODUCER_RECOVERY_TIMEOUT_MS = 5_000L
private const val PRODUCER_RECOVERY_POLL_MS = 16L
private const val PROFILE_GRID_COLUMNS = 4
private const val MAX_PROFILE_STAGGER_PX = 8f
private const val MIN_RENDERED_PROFILE_SCALE = 0.25f
