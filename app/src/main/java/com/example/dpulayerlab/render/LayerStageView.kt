package com.example.dpulayerlab.render

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
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
import com.example.dpulayerlab.model.BufferSize
import com.example.dpulayerlab.model.LayerBackend
import com.example.dpulayerlab.model.LoadShape
import com.example.dpulayerlab.model.LoadShapeEvaluator
import com.example.dpulayerlab.model.MotionProfile
import com.example.dpulayerlab.model.PhaseSpec
import com.example.dpulayerlab.model.PixelRoute
import com.example.dpulayerlab.model.usesSelectedMediaDecoder
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import kotlin.math.PI
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
    private var attached = false
    private var frameCallbackPosted = false
    private val animatedChildren = mutableListOf<View>()
    private var producerFrameCallback: ProducerFrameCallback? = null
    private var expectedProducersCallback: ((Long, Set<Long>) -> Unit)? = null
    private var producerTopologyPendingCallback: ((Long) -> Unit)? = null
    private var producerTeardownFailureCallback: ((Long) -> Unit)? = null
    private var producerRuntimeFailureCallback: ((Long, String) -> Unit)? = null
    private var stageRemovalCallback: ((Long, Boolean) -> Unit)? = null
    private val producerRelays = IdentityHashMap<View, ProducerFrameRelay>()
    private var nextProducerId = 0L
    private var producerGeneration = Long.MIN_VALUE
    private var deferredTopologyApply: DeferredTopologyApply? = null
    private var failedTopologyGeneration = Long.MIN_VALUE
    private var expectedPublishSuppressed = false
    private var expectedTopologyDirty = true
    private var lastPublishedExpectedGeneration = Long.MIN_VALUE
    private var lastPublishedProducerIds: Set<Long> = emptySet()
    private var lastPublishedExpectedCallback: ((Long, Set<Long>) -> Unit)? = null
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
        onProducerFrame: ProducerFrameCallback? = null,
        onExpectedProducers: ((generation: Long, producerIds: Set<Long>) -> Unit)? = null,
        onProducerTopologyPending: ((generation: Long) -> Unit)? = null,
        onProducerTeardownFailure: ((generation: Long) -> Unit)? = null,
        onProducerRuntimeFailure: ((generation: Long, reason: String) -> Unit)? = null,
        onStageRemoved: ((generation: Long, producersStopped: Boolean) -> Unit)? = null,
    ) {
        val producerCallbackChanged = producerFrameCallback !== onProducerFrame
        val expectedCallbackChanged = expectedProducersCallback !== onExpectedProducers
        producerFrameCallback = onProducerFrame
        expectedProducersCallback = onExpectedProducers
        producerTopologyPendingCallback = onProducerTopologyPending
        producerTeardownFailureCallback = onProducerTeardownFailure
        producerRuntimeFailureCallback = onProducerRuntimeFailure
        stageRemovalCallback = onStageRemoved
        val previousPhaseId = phase?.id
        val newTopology = topologyFor(newPhase, selectedMedia, selectedDecoder)
        val phaseChanged = previousPhaseId != newPhase.id
        val generationChanged = producerGeneration != newProducerGeneration
        if (generationChanged || expectedCallbackChanged) expectedTopologyDirty = true
        if (generationChanged) failedTopologyGeneration = Long.MIN_VALUE
        producerGeneration = newProducerGeneration
        phase = newPhase
        selectedMediaUri = selectedMedia
        videoDecoderSelection = selectedDecoder
        expectedPublishSuppressed = true
        try {
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
                        updateProducerRelays()
                    }
                }
                canReconcileLayerCount(topology, newTopology) &&
                    !replaceVideoPrimary &&
                    !RendererSafetyState.hasUnconfirmedTeardown() -> {
                    producerTopologyPendingCallback?.invoke(producerGeneration)
                    if (!reconcileLayerCount(newPhase)) {
                        beginDeferredTopologyApply()
                        return
                    }
                    topology = newTopology
                    if (generationChanged || producerCallbackChanged) {
                        updateProducerRelays()
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
                startNanos = System.nanoTime()
                lastTransformNanos = 0L
            }
            updateRuntimeControls(
                newPhase,
                restartLoadProfile = phaseChanged || generationChanged,
            )
            scheduleFrame()
        } finally {
            expectedPublishSuppressed = false
            if (deferredTopologyApply == null) publishExpectedProducers()
        }
    }

    fun release(): Boolean {
        val releasedGeneration = producerGeneration
        val removalCallback = stageRemovalCallback
        cancelDeferredTopologyApply()
        Choreographer.getInstance().removeFrameCallback(this)
        frameCallbackPosted = false
        val stopped = removeAndReleaseChildren()
        if (releasedGeneration != Long.MIN_VALUE) {
            removalCallback?.invoke(releasedGeneration, stopped)
        }
        phase = null
        selectedMediaUri = null
        videoDecoderSelection = null
        topology = null
        producerFrameCallback = null
        expectedProducersCallback = null
        producerTopologyPendingCallback = null
        producerTeardownFailureCallback = null
        producerRuntimeFailureCallback = null
        stageRemovalCallback = null
        producerGeneration = Long.MIN_VALUE
        failedTopologyGeneration = Long.MIN_VALUE
        expectedTopologyDirty = true
        lastPublishedExpectedGeneration = Long.MIN_VALUE
        lastPublishedProducerIds = emptySet()
        lastPublishedExpectedCallback = null
        return stopped
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
                publishExpectedProducers()
            }
        } else {
            scheduleFrame()
        }
    }

    override fun onDetachedFromWindow() {
        attached = false
        cancelDeferredTopologyApply()
        Choreographer.getInstance().removeFrameCallback(this)
        frameCallbackPosted = false
        val stopped = removeAndReleaseChildren()
        if (producerGeneration != Long.MIN_VALUE) {
            stageRemovalCallback?.invoke(producerGeneration, stopped)
        }
        topology = null
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos: Long) {
        frameCallbackPosted = false
        val current = phase
        if (attached && current != null) {
            val safeFps = current.producerFps
                .takeIf { it.isFinite() }
                ?.coerceIn(1f, 120f)
                ?: 60f
            val interval = (1_000_000_000L / safeFps).toLong()
            if (frameTimeNanos - lastTransformNanos >= interval) {
                animateTransforms(current, (frameTimeNanos - startNanos) / 1_000_000_000f)
                lastTransformNanos = frameTimeNanos
            }
            if (current.motion != MotionProfile.STATIC || current.alphaOverlap) {
                scheduleFrame()
            }
        }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width != oldWidth || height != oldHeight) scheduleFrame()
    }

    private fun scheduleFrame() {
        if (!attached || frameCallbackPosted || phase == null) return
        frameCallbackPosted = true
        Choreographer.getInstance().postFrameCallback(this)
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
        expectedTopologyDirty = true
        if (!removeAndReleaseChildren()) {
            topology = null
            return false
        }
        val current = phase ?: return false
        when (current.backend) {
            LayerBackend.FLATTENED_TEXTURE -> {
                val relay = newProducerRelay(primary = true)
                val view =
                    MultiLayerTextureView(
                        context = context,
                        logicalLayerCount = current.activeLayers,
                        targetFps = current.producerFps,
                        captureFrameCommit = relay::captureCallback,
                        onTeardownFailure = teardownFailureCallbackFor(relay),
                        onRuntimeFailure = runtimeFailureCallbackFor(relay),
                    )
                producerRelays[view] = relay
                addRenderChild(
                    view,
                )
            }

            LayerBackend.INDEPENDENT_SURFACES,
            LayerBackend.MIXED_SURFACE_TEXTURE,
            -> {
                repeat(current.activeLayers.coerceIn(1, MAX_LAYERS)) { index ->
                    val view = createLayer(current, index)
                    addRenderChild(view)
                }
            }
        }
        startNanos = System.nanoTime()
        lastTransformNanos = 0L
        topology = desiredTopology
        updateRuntimeControls(current, restartLoadProfile = true)
        scheduleFrame()
        return true
    }

    private fun createLayer(current: PhaseSpec, index: Int): View {
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
                    onFrame = relay::emit,
                    onTeardownFailure = teardownFailureCallbackFor(relay),
                    onRuntimeFailure = runtimeFailureCallbackFor(relay),
                )
            } else if (wantsSelectedDecoder) {
                // A selected source must never silently turn into the procedural YUV proxy if its
                // immutable hardware-decoder binding is missing or belongs to another URI.
                SurfaceView(context).also { unavailable ->
                    unavailable.holder.setFormat(PixelFormat.OPAQUE)
                    unavailable.post {
                        if (producerRelays[unavailable] === relay) {
                            runtimeFailureCallbackFor(relay).invoke(
                                "Selected decoder binding is unavailable",
                            )
                        }
                    }
                }
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
                    )
                }
            }
        }
        producerRelays[view] = relay
        return view
    }

    private fun addRenderChild(
        view: View,
        index: Int = animatedChildren.size,
    ) {
        val params = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        addView(view, index, params)
        animatedChildren.add(index, view)
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
        previous.alphaOverlap == next.alphaOverlap &&
        previous.mediaUri == next.mediaUri &&
        previous.videoDecoderSelection == next.videoDecoderSelection

    private fun reconcileLayerCount(current: PhaseSpec): Boolean {
        expectedTopologyDirty = true
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
            removedChildren.forEach { child -> producerRelays[child]?.disable() }
            removedChildren.forEach(::requestStopRenderChild)
            val stopDeadlineNanos = producerDrainDeadlineNanos()
            val allStopped = removedChildren.fold(true) { stopped, child ->
                releaseRenderChild(child, stopDeadlineNanos) && stopped
            }
            removedChildren.forEach(producerRelays::remove)
            removedChildren.forEach(::removeView)
            if (!allStopped) {
                return false
            }
        }
        while (animatedChildren.size < targetCount) {
            val insertIndex = if (keepsGlTail) {
                animatedChildren.lastIndex
            } else {
                animatedChildren.size
            }
            addRenderChild(
                view = createLayer(current, insertIndex),
                index = insertIndex,
            )
        }
        publishExpectedProducers()
        return true
    }

    private fun removeAndReleaseChildren(): Boolean {
        // Stop all producers first so their final unlock/dequeue operations can drain in
        // parallel before Compose removes the SurfaceView/TextureView objects.
        producerRelays.values.forEach(ProducerFrameRelay::disable)
        val children = animatedChildren.toList()
        children.forEach(::requestStopRenderChild)
        val stopDeadlineNanos = producerDrainDeadlineNanos()
        val allStopped = children.fold(true) { stopped, child ->
            releaseRenderChild(child, stopDeadlineNanos) && stopped
        }
        producerRelays.clear()
        animatedChildren.clear()
        removeAllViews()
        return allStopped
    }

    private fun replacePrimaryProducer(current: PhaseSpec): Boolean {
        expectedTopologyDirty = true
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
        addRenderChild(createLayer(current, index = 0), index = 0)
        publishExpectedProducers()
        return true
    }

    private fun primaryRequiresReplacement(): Boolean =
        animatedChildren.firstOrNull() is VideoSurfaceView

    private fun newProducerRelay(primary: Boolean): ProducerFrameRelay =
        ProducerFrameRelay(
            producerId = allocateProducerId(),
            generation = producerGeneration,
            primary = primary,
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
        relay.activeGenerationForFailure()?.let { generation ->
            val boundedReason = reason
                .trim()
                .ifEmpty { "Producer runtime failure" }
                .take(MAX_RUNTIME_FAILURE_REASON_CHARS)
            post {
                producerRuntimeFailureCallback?.invoke(generation, boundedReason)
            }
        }
    }

    private fun updateProducerRelays() {
        producerRelays.values.forEach { relay ->
            relay.update(producerGeneration, producerFrameCallback)
        }
    }

    private fun beginDeferredTopologyApply() {
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
        postDelayed(deferredTopologyApplyRunnable, PRODUCER_RECOVERY_POLL_MS)
    }

    private fun continueDeferredTopologyApply() {
        val pending = deferredTopologyApply ?: return
        if (phase == null || producerGeneration == Long.MIN_VALUE) {
            cancelDeferredTopologyApply()
            return
        }
        val nowMs = SystemClock.elapsedRealtime()
        if (RendererSafetyState.hasUnconfirmedTeardown()) {
            if (nowMs >= pending.deadlineMs) {
                deferredTopologyApply = null
                failedTopologyGeneration = pending.generation
                producerTeardownFailureCallback?.invoke(pending.generation)
            } else {
                scheduleDeferredTopologyApply()
            }
            return
        }

        // The desired phase/media/generation fields are latest-wins. Commit the complete relay
        // set atomically from the gate's point of view by suppressing publication until every
        // child has been installed.
        deferredTopologyApply = null
        expectedPublishSuppressed = true
        val desiredTopology = topologyFor(
            checkNotNull(phase),
            selectedMediaUri,
            videoDecoderSelection,
        )
        val rebuilt = try {
            rebuildLayers(desiredTopology)
        } finally {
            expectedPublishSuppressed = false
        }
        if (!rebuilt) {
            deferredTopologyApply = pending.apply { generation = producerGeneration }
            if (SystemClock.elapsedRealtime() >= pending.deadlineMs) {
                deferredTopologyApply = null
                failedTopologyGeneration = pending.generation
                producerTeardownFailureCallback?.invoke(pending.generation)
            } else {
                scheduleDeferredTopologyApply()
            }
            return
        }
        updateProducerRelays()
        publishExpectedProducers()
    }

    private fun cancelDeferredTopologyApply() {
        removeCallbacks(deferredTopologyApplyRunnable)
        deferredTopologyApply = null
    }

    private fun publishExpectedProducers() {
        if (
            expectedPublishSuppressed ||
            deferredTopologyApply != null ||
            failedTopologyGeneration == producerGeneration ||
            !expectedTopologyDirty
        ) {
            return
        }
        val expected = buildSet {
            producerRelays.values.forEach { add(it.producerId) }
        }
        if (expected.isEmpty()) return
        val callback = expectedProducersCallback ?: run {
            expectedTopologyDirty = false
            return
        }
        if (
            producerGeneration == lastPublishedExpectedGeneration &&
            expected == lastPublishedProducerIds &&
            callback === lastPublishedExpectedCallback
        ) {
            expectedTopologyDirty = false
            return
        }
        callback.invoke(producerGeneration, expected)
        expectedTopologyDirty = false
        lastPublishedExpectedGeneration = producerGeneration
        lastPublishedProducerIds = expected
        lastPublishedExpectedCallback = callback
    }

    private fun requestStopRenderChild(child: View) {
        when (child) {
            is PatternSurfaceView -> child.requestStop()
            is PatternTextureView -> child.requestStop()
            is MultiLayerTextureView -> child.requestStop()
            is VideoSurfaceView -> child.requestStop()
            is StressGlSurfaceView -> child.requestStopLab()
        }
    }

    private fun releaseRenderChild(child: View, stopDeadlineNanos: Long): Boolean =
        when (child) {
            is PatternSurfaceView -> child.release(stopDeadlineNanos)
            is PatternTextureView -> child.release(stopDeadlineNanos)
            is MultiLayerTextureView -> child.release(stopDeadlineNanos)
            is VideoSurfaceView -> child.release(stopDeadlineNanos)
            is StressGlSurfaceView -> child.releaseLab(stopDeadlineNanos)
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

    private fun animateTransforms(current: PhaseSpec, time: Float) {
        if (width == 0 || height == 0) return
        animatedChildren.forEachIndexed { index, child ->
            if (current.motion != MotionProfile.Z_ORDER_SWAP) child.translationZ = 0f
            val phaseOffset = index * 0.73f
            val wave = sin(time * 1.35f + phaseOffset)
            val wave2 = cos(time * 0.83f + phaseOffset * 1.7f)
            val baseScale = when {
                animatedChildren.size <= 1 -> 1f
                animatedChildren.size <= 4 -> 0.72f
                else -> 0.48f + (index % 3) * 0.055f
            }

            when (current.motion) {
                MotionProfile.STATIC -> {
                    if (animatedChildren.size == 1) {
                        child.scaleX = 1f
                        child.scaleY = 1f
                        child.translationX = 0f
                        child.translationY = 0f
                    } else {
                        val columns = minOf(4, animatedChildren.size)
                        val rows = (animatedChildren.size + columns - 1) / columns
                        val column = index % columns
                        val row = index / columns
                        child.scaleX = baseScale
                        child.scaleY = baseScale
                        child.translationX = (column - (columns - 1) / 2f) * width * 0.17f
                        child.translationY = (row - (rows - 1) / 2f) * height * 0.18f
                    }
                    child.rotation = 0f
                }

                MotionProfile.SCROLL -> {
                    child.scaleX = baseScale
                    child.scaleY = baseScale
                    child.translationX = ((time * (80 + index * 13)) % (width * 1.6f)) - width * 0.8f
                    child.translationY = wave2 * height * 0.24f
                    child.rotation = 0f
                }

                MotionProfile.ZOOM_PAN -> {
                    val scale = baseScale * (0.72f + (wave + 1f) * 0.28f)
                    child.scaleX = scale
                    child.scaleY = scale
                    child.translationX = wave2 * width * 0.22f
                    child.translationY = wave * height * 0.18f
                    child.rotation = 0f
                }

                MotionProfile.ROTATE -> {
                    child.scaleX = baseScale
                    child.scaleY = baseScale
                    child.translationX = wave2 * width * 0.2f
                    child.translationY = wave * height * 0.16f
                    child.rotation = (time * (17f + index * 2.2f) + index * 29f) % 360f
                }

                MotionProfile.PARALLAX -> {
                    child.scaleX = baseScale
                    child.scaleY = baseScale
                    child.translationX = sin(time * (0.55f + index * 0.025f) + phaseOffset) * width * 0.34f
                    child.translationY = cos(time * (0.43f + index * 0.02f) + phaseOffset) * height * 0.28f
                    child.rotation = if (current.alphaOverlap) wave * 8f else 0f
                }

                MotionProfile.TRANSFORM_STORM -> {
                    val scale = baseScale * (0.58f + (wave2 + 1f) * 0.34f)
                    child.scaleX = scale
                    child.scaleY = baseScale * (0.68f + (wave + 1f) * 0.27f)
                    child.translationX = wave * width * 0.37f
                    child.translationY = wave2 * height * 0.31f
                    child.rotation = (time * (21f + index * 3.3f)) % 360f
                }

                MotionProfile.Z_ORDER_SWAP -> {
                    child.scaleX = baseScale
                    child.scaleY = baseScale
                    child.translationX = wave * width * 0.3f
                    child.translationY = wave2 * height * 0.24f
                    child.rotation = wave * 12f
                    child.translationZ = (((time * 1.5f).toInt() + index) % animatedChildren.size).toFloat()
                }
            }
            child.alpha = if (current.alphaOverlap) {
                (0.58f + 0.38f * ((wave + 1f) * 0.5f)).coerceIn(0.15f, 1f)
            } else {
                1f
            }
        }
    }

    companion object {
        const val MAX_LAYERS = 20
        private const val MAX_RUNTIME_FAILURE_REASON_CHARS = 240
    }

    private data class LayerTopology(
        val backend: LayerBackend,
        val pixelRoute: PixelRoute,
        val bufferSize: BufferSize,
        val activeLayers: Int,
        val includeGlLayer: Boolean,
        val alphaOverlap: Boolean,
        val mediaUri: Uri?,
        val videoDecoderSelection: VideoDecoderSelection?,
    )

    private data class DeferredTopologyApply(
        var generation: Long,
        var deadlineMs: Long,
    )
}

/**
 * Gives every physical BufferQueue producer a stable identity. The immutable state snapshot keeps
 * generation, primary attribution, and callback consistent when a frame races a Compose update.
 */
internal class ProducerFrameRelay(
    val producerId: Long,
    generation: Long,
    private val primary: Boolean,
    callback: ProducerFrameCallback?,
) {
    @Volatile
    private var activeGeneration = generation

    @Volatile
    private var commitToken = callback?.let { capturedCallback ->
        ProducerCommitToken(
            generation = generation,
            producerId = producerId,
            primary = primary,
            callback = capturedCallback,
        )
    }

    fun update(
        generation: Long,
        callback: ProducerFrameCallback?,
    ) {
        activeGeneration = generation
        commitToken = callback?.let { capturedCallback ->
            ProducerCommitToken(
                generation = generation,
                producerId = producerId,
                primary = primary,
                callback = capturedCallback,
            )
        }
    }

    fun disable() {
        activeGeneration = DISABLED_GENERATION
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
        activeGeneration.takeUnless { it == DISABLED_GENERATION }

    private class ProducerCommitToken(
        val generation: Long,
        val producerId: Long,
        val primary: Boolean,
        val callback: ProducerFrameCallback,
    ) : () -> Unit {
        override fun invoke() {
            callback.onFrame(generation, producerId, primary)
        }
    }

    private companion object {
        const val DISABLED_GENERATION = Long.MIN_VALUE
    }
}

fun interface ProducerFrameCallback {
    fun onFrame(generation: Long, producerId: Long, primary: Boolean)
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
        host.post(this)
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
            ProducerRecoveryDecision.WAIT ->
                host.postDelayed(this, PRODUCER_RECOVERY_POLL_MS)
        }
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
        release(System.nanoTime())
        applyFrameRate(holder.surface, targetFps)
        leaseStart.request()
    }

    private fun startDrawingLoop() {
        if (removalRequested.get() || !holder.surface.isValid || loop != null) return
        val candidate = CanvasDrawingLoop(
            surface = holder.surface,
            layerIndex = layerIndex,
            fpsProvider = { targetFps },
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
        leaseStart.cancel()
        release(System.nanoTime())
    }

    fun setTargetFps(fps: Float) {
        val normalized = safeProducerFps(fps)
        if (normalized == targetFps) return
        targetFps = normalized
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
) : TextureView(context), TextureView.SurfaceTextureListener {

    @Volatile
    private var targetFps: Float = safeProducerFps(targetFps)
    private val removalRequested = AtomicBoolean(false)
    private var loop: CanvasDrawingLoop? = null
    private var drawSurface: Surface? = null
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
        release(System.nanoTime())
        drawSurface = Surface(texture).also { applyFrameRate(it, targetFps) }
        leaseStart.request()
    }

    private fun startDrawingLoop() {
        val surface = drawSurface ?: return
        if (removalRequested.get() || !surface.isValid || loop != null) return
        val candidate = CanvasDrawingLoop(
            surface = surface,
            layerIndex = layerIndex,
            fpsProvider = { targetFps },
            logicalYuv = logicalYuv,
            sbwcRequested = false,
            logicalLayerCount = 1,
            captureFrameCommit = captureFrameCommit,
            onRuntimeFailure = onRuntimeFailure,
        )
        if (candidate.start()) loop = candidate
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        leaseStart.cancel()
        release(System.nanoTime())
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    fun setTargetFps(fps: Float) {
        val normalized = safeProducerFps(fps)
        if (normalized == targetFps) return
        targetFps = normalized
        drawSurface?.let { surface ->
            if (surface.isValid) applyFrameRate(surface, normalized)
        }
    }

    fun release(stopDeadlineNanos: Long = producerDrainDeadlineNanos()): Boolean {
        val stopped = loop?.stop(stopDeadlineNanos) ?: true
        loop = null
        drawSurface?.release()
        drawSurface = null
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
        release(System.nanoTime())
        drawSurface = Surface(texture).also { applyFrameRate(it, targetFps) }
        leaseStart.request()
    }

    private fun startDrawingLoop() {
        val surface = drawSurface ?: return
        if (removalRequested.get() || !surface.isValid || loop != null) return
        val candidate = CanvasDrawingLoop(
            surface = surface,
            layerIndex = 0,
            fpsProvider = { targetFps },
            logicalYuv = false,
            sbwcRequested = false,
            logicalLayerCount = logicalLayerCount,
            complexityProvider = ::currentGpuLoad,
            captureFrameCommit = captureFrameCommit,
            onRuntimeFailure = onRuntimeFailure,
        )
        if (candidate.start()) loop = candidate
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    fun setTargetFps(fps: Float) {
        val normalized = safeProducerFps(fps)
        if (normalized == targetFps) return
        targetFps = normalized
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
    }

    private fun currentGpuLoad(): Float = LoadShapeEvaluator.intensityAt(
        base = gpuLoad,
        shape = loadShape,
        elapsedMs = SystemClock.elapsedRealtime() - loadStartedMs,
    )

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        leaseStart.cancel()
        release(System.nanoTime())
        return true
    }

    fun release(stopDeadlineNanos: Long = producerDrainDeadlineNanos()): Boolean {
        val stopped = loop?.stop(stopDeadlineNanos) ?: true
        loop = null
        drawSurface?.release()
        drawSurface = null
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
    private val onFrame: (() -> Unit)?,
    private val onTeardownFailure: (() -> Unit)?,
    private val onRuntimeFailure: ((String) -> Unit)?,
) : SurfaceView(context), SurfaceHolder.Callback {

    @Volatile
    private var targetFps = safeProducerFps(targetFps)
    private val removalRequested = AtomicBoolean(false)
    @Volatile
    private var decoderSession: DecoderSession? = null
    private val callbackHandler = Handler(Looper.getMainLooper())
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
        release(System.nanoTime())
        applyFrameRate(holder.surface, targetFps)
        leaseStart.request()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        applyFrameRate(holder.surface, targetFps)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        leaseStart.cancel()
        release(System.nanoTime())
    }

    fun setFrameRateHint(fps: Float) {
        val normalized = safeProducerFps(fps)
        if (normalized == targetFps) {
            return
        }
        targetFps = normalized
        if (holder.surface.isValid) applyFrameRate(holder.surface, normalized)
    }

    private fun startDecoder(surface: Surface) {
        if (decoderSession != null) {
            return
        }
        val sessionRunning = AtomicBoolean(true)
        lateinit var session: DecoderSession
        val thread = Thread({
            var extractor: MediaExtractor? = null
            var codec: MediaCodec? = null
            try {
                ensureSetupActive(surface, session, sessionRunning)
                val activeExtractor = MediaExtractor()
                extractor = activeExtractor
                selection.openSourceDuplicate().use { source ->
                    activeExtractor.setDataSource(source)
                }
                ensureSetupActive(surface, session, sessionRunning)
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
                ensureSetupActive(surface, session, sessionRunning)
                val mime = format.getString(MediaFormat.KEY_MIME)
                    ?: error("Video MIME unavailable")
                check(mime.equals(selection.mime, ignoreCase = true)) {
                    "Video MIME changed after preflight"
                }
                val inputEncoded = format.encodedVideoDimensions()
                    ?: error("Video encoded dimensions unavailable")
                check(
                    inputEncoded.widthPx == selection.expectedEncodedWidthPx &&
                        inputEncoded.heightPx == selection.expectedEncodedHeightPx
                ) {
                    "Video encoded dimensions changed after preflight"
                }
                val runtimeDeclaredMaxWidth =
                    format.strictOptionalIntegerOrNull(MediaFormat.KEY_MAX_WIDTH)
                val runtimeDeclaredMaxHeight =
                    format.strictOptionalIntegerOrNull(MediaFormat.KEY_MAX_HEIGHT)
                check(
                    runtimeDeclaredMaxWidth == selection.expectedDeclaredMaxWidthPx &&
                        runtimeDeclaredMaxHeight == selection.expectedDeclaredMaxHeightPx &&
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
                    inputVisible.widthPx == selection.expectedVisibleWidthPx &&
                        inputVisible.heightPx == selection.expectedVisibleHeightPx
                ) {
                    "Video visible dimensions changed after preflight"
                }
                val runtimeFps = format.mediaNumberOrNull(MediaFormat.KEY_FRAME_RATE)
                    ?.toFloat()
                check(videoFrameRatesMatch(runtimeFps, selection.expectedSourceFps)) {
                    "Video FPS changed after preflight"
                }
                val runtimeProfile = format.strictOptionalIntegerOrNull(MediaFormat.KEY_PROFILE)
                check(runtimeProfile == selection.expectedProfile) {
                    "Video profile changed after preflight"
                }
                val runtimeLevel = format.strictOptionalIntegerOrNull(MediaFormat.KEY_LEVEL)
                check(runtimeLevel == selection.expectedLevel) {
                    "Video level changed after preflight"
                }
                val runtimeBitRate = format.strictOptionalIntegerOrNull(MediaFormat.KEY_BIT_RATE)
                check(runtimeBitRate == selection.expectedBitRate) {
                    "Video bitrate changed after preflight"
                }
                val runtimeMaxInputSize =
                    format.strictOptionalIntegerOrNull(MediaFormat.KEY_MAX_INPUT_SIZE)
                check(runtimeMaxInputSize == selection.expectedMaxInputSize) {
                    "Video maximum input size changed after preflight"
                }
                check(
                    runtimeMaxInputSize == null ||
                        runtimeMaxInputSize <= selection.maxCompressedSampleBytes
                ) {
                    "Video maximum input size exceeds the run safety limit"
                }
                val runtimeRotation =
                    format.strictOptionalIntegerOrNull(MediaFormat.KEY_ROTATION) ?: 0
                check(runtimeRotation == selection.expectedRotationDegrees) {
                    "Video rotation changed after preflight"
                }
                val runtimeCodecsString = format.boundedCodecsString()
                check(runtimeCodecsString == selection.expectedCodecsString) {
                    "Video codec string changed after preflight"
                }
                val runtimeCodecConfigFingerprint = format.codecConfigFingerprintOrNull()
                    ?: error("Video codec configuration is invalid")
                check(runtimeCodecConfigFingerprint == selection.codecConfigFingerprint) {
                    "Video codec configuration changed after preflight"
                }
                if (selection.requiresVerifiedP010) {
                    check(runtimeProfile != null) {
                        "P010 profile is no longer verifiable"
                    }
                }
                val maxEncodedWidth = selection.maxEncodedWidthPx
                    ?: error("Video max width unavailable")
                val maxEncodedHeight = selection.maxEncodedHeightPx
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
                callbackHandler.post {
                    if (isCurrent(session)) {
                        holder.setFixedSize(
                            presentationDimensions.widthPx,
                            presentationDimensions.heightPx,
                        )
                    }
                }
                val activeCodec = MediaCodec.createByCodecName(selection.codecName)
                codec = activeCodec
                ensureSetupActive(surface, session, sessionRunning)
                activeCodec.setOnFrameRenderedListener(
                    { _, _, _ -> if (isCurrent(session)) onFrame?.invoke() },
                    callbackHandler,
                )
                activeCodec.configure(format, surface, null, 0)
                ensureSetupActive(surface, session, sessionRunning)
                activeCodec.start()

                val outputInfo = MediaCodec.BufferInfo()
                var inputEos = false
                var nextRenderNanos = System.nanoTime()
                while (sessionRunning.get() && surface.isValid && isCurrent(session)) {
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
                                        selection.maxCompressedSampleBytes.toLong()
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
                            outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ||
                            outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED
                    when {
                        outputIndex >= 0 -> {
                            val isEos = outputInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            if (outputInfo.size > 0 || !isEos) {
                                val interval = (1_000_000_000L / safeProducerFps(targetFps)).toLong()
                                val now = System.nanoTime()
                                if (nextRenderNanos < now - MAX_PACING_LAG_NANOS) {
                                    nextRenderNanos = now
                                }
                                activeCodec.releaseOutputBuffer(outputIndex, nextRenderNanos)
                                nextRenderNanos += interval
                            } else {
                                activeCodec.releaseOutputBuffer(outputIndex, false)
                            }
                            if (isEos && sessionRunning.get() && isCurrent(session)) {
                                activeCodec.flush()
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
                                    selection.expectedVisibleWidthPx,
                                    selection.expectedVisibleHeightPx,
                                )
                            ) {
                                "Decoder output resolution changed after preflight"
                            }
                            callbackHandler.post {
                                if (isCurrent(session)) {
                                    holder.setFixedSize(
                                        presentationDimensions.widthPx,
                                        presentationDimensions.heightPx,
                                    )
                                }
                            }
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
                if (error is ThreadDeath) throw error
                if (
                    sessionRunning.get() &&
                    !removalRequested.get() &&
                    isCurrent(session)
                ) {
                    onRuntimeFailure?.invoke(
                        buildRuntimeFailureReason("MediaCodec", error),
                    )
                }
            } finally {
                val cleanupFailures = mutableListOf<String>()
                runCatching { codec?.stop() }
                codec?.let { activeCodec ->
                    runCatching { activeCodec.release() }
                        .onFailure {
                            cleanupFailures +=
                                "MediaCodec.release=${it.javaClass.simpleName}"
                        }
                }
                extractor?.let { activeExtractor ->
                    runCatching { activeExtractor.release() }
                        .onFailure {
                            cleanupFailures +=
                                "MediaExtractor.release=${it.javaClass.simpleName}"
                        }
                }
                if (cleanupFailures.isNotEmpty()) {
                    RendererSafetyState.markCleanupFailure(
                        component = "decoder native cleanup",
                        detail = cleanupFailures.joinToString(","),
                    )
                    callbackHandler.post { onTeardownFailure?.invoke() }
                }
                sessionRunning.set(false)
                if (decoderSession === session) decoderSession = null
            }
        }, "DpuLab-MediaCodec")
        session = DecoderSession(sessionRunning, thread)
        decoderSession = session
        val threadStarted = startRendererThread(thread) { error ->
            sessionRunning.set(false)
            if (decoderSession === session) decoderSession = null
            onRuntimeFailure?.invoke(
                buildRuntimeFailureReason("MediaCodec thread start", error),
            )
        }
        if (!threadStarted) {
            return
        }
    }

    private fun ensureSetupActive(
        surface: Surface,
        session: DecoderSession,
        sessionRunning: AtomicBoolean,
    ) {
        if (
            !sessionRunning.get() ||
            removalRequested.get() ||
            !surface.isValid ||
            !isCurrent(session)
        ) {
            throw InterruptedException()
        }
    }

    fun release(stopDeadlineNanos: Long = producerDrainDeadlineNanos()): Boolean {
        val session = decoderSession ?: return true
        decoderSession = null
        requestStop(session)
        return Thread.currentThread() === session.thread ||
            joinThreadUntil(session.thread, stopDeadlineNanos)
    }

    fun requestStop() {
        removalRequested.set(true)
        leaseStart.cancel()
        decoderSession?.let(::requestStop)
    }

    private fun requestStop(session: DecoderSession) {
        session.running.set(false)
        session.thread.interrupt()
    }

    private fun isCurrent(session: DecoderSession): Boolean =
        decoderSession === session && session.running.get()

    private data class DecoderSession(
        val running: AtomicBoolean,
        val thread: Thread,
    )

    companion object {
        private const val CODEC_TIMEOUT_US = 10_000L
        private const val MAX_PACING_LAG_NANOS = 250_000_000L
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

private fun MediaFormat.mediaNumberOrNull(key: String): Number? =
    if (containsKey(key)) runCatching { getNumber(key) }.getOrNull() else null

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
    val value = runCatching { getString(MEDIA_KEY_CODECS_STRING_COMPAT) }
        .getOrElse {
            throw IllegalArgumentException("Invalid codec string MediaFormat value", it)
        }
        ?: return null
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
        val buffer = runCatching { getByteBuffer(key) }.getOrNull() ?: return null
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
    private val fpsProvider: () -> Float,
    private val logicalYuv: Boolean,
    private val sbwcRequested: Boolean,
    private val logicalLayerCount: Int,
    private val translucentContent: Boolean = false,
    private val complexityProvider: () -> Float = { 0f },
    private val captureFrameCommit: (() -> (() -> Unit)?)?,
    private val onRuntimeFailure: ((String) -> Unit)?,
) : Runnable {

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
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
            onRuntimeFailure?.invoke(
                buildRuntimeFailureReason("Canvas thread start", error),
            )
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
        running.set(false)
        thread?.interrupt()
    }

    override fun run() {
        var nextFrame = System.nanoTime()
        val startedNanos = nextFrame
        var consecutiveFailures = 0
        while (running.get() && surface.isValid) {
            val fps = fpsProvider().takeIf { it.isFinite() }?.coerceIn(1f, 120f) ?: 60f
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
            val frameCommit = captureFrameCommit?.invoke()
            try {
                canvas = surface.lockHardwareCanvas()
                val elapsedSeconds = (System.nanoTime() - startedNanos) / 1_000_000_000f
                val complexity = complexityProvider()
                    .takeIf { it.isFinite() }
                    ?.coerceIn(0f, 1f)
                    ?: 0f
                if (logicalLayerCount == 1) {
                    painter.draw(canvas, elapsedSeconds)
                    repeat(flattenedSingleLayerExtraPasses(complexity)) { passIndex ->
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
                    val passes = logicalPainters.size + (complexity * 8f).roundToInt()
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
                if (error is ThreadDeath) throw error
                frameDrawn = false
                if (error !is Exception) terminalFailure = error
            } finally {
                if (canvas != null) {
                    try {
                        surface.unlockCanvasAndPost(canvas)
                        posted = true
                    } catch (error: Throwable) {
                        if (error is ThreadDeath) throw error
                        if (error !is Exception) terminalFailure = error
                    }
                }
            }
            val fatalFailure = terminalFailure
            if (fatalFailure != null) {
                if (running.get()) {
                    onRuntimeFailure?.invoke(
                        buildRuntimeFailureReason("Canvas", fatalFailure),
                    )
                }
                running.set(false)
                break
            }
            if (posted && frameDrawn) {
                consecutiveFailures = 0
                runCatching { frameCommit?.invoke() }
            } else {
                consecutiveFailures++
            }
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                if (running.get() && surface.isValid) {
                    onRuntimeFailure?.invoke(
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
        runCatching {
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

private const val NANOS_PER_MILLI = 1_000_000L
private const val PRODUCER_DRAIN_TIMEOUT_NANOS = 16_000_000L
private const val PRODUCER_RECOVERY_TIMEOUT_MS = 5_000L
private const val PRODUCER_RECOVERY_POLL_MS = 16L
