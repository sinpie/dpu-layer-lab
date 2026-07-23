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
import com.example.dpulayerlab.model.MotionProfile
import com.example.dpulayerlab.model.PhaseSpec
import com.example.dpulayerlab.model.PixelRoute
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import kotlin.math.PI
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
    private var mediaUri: Uri? = null
    private var phaseSignature = ""
    private var startNanos = 0L
    private var lastTransformNanos = 0L
    private var attached = false
    private val animatedChildren = mutableListOf<View>()
    private var primaryFrameCallback: (() -> Unit)? = null

    init {
        setBackgroundColor(Color.BLACK)
        clipChildren = false
        clipToPadding = false
    }

    fun configure(
        newPhase: PhaseSpec,
        selectedMedia: Uri?,
        onPrimaryFrame: (() -> Unit)? = null,
    ) {
        primaryFrameCallback = onPrimaryFrame
        val signature = listOf(
            newPhase.backend,
            newPhase.pixelRoute,
            newPhase.bufferSize,
            newPhase.activeLayers,
            newPhase.includeGlLayer,
            newPhase.alphaOverlap,
            selectedMedia,
        ).joinToString("|")
        phase = newPhase
        mediaUri = selectedMedia
        if (signature != phaseSignature) {
            phaseSignature = signature
            rebuildLayers()
        } else {
            updateRuntimeControls(newPhase)
        }
    }

    fun release() {
        Choreographer.getInstance().removeFrameCallback(this)
        removeAndReleaseChildren()
        phase = null
        phaseSignature = ""
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDetachedFromWindow() {
        attached = false
        Choreographer.getInstance().removeFrameCallback(this)
        removeAndReleaseChildren()
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos: Long) {
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
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun rebuildLayers() {
        removeAndReleaseChildren()
        val current = phase ?: return
        when (current.backend) {
            LayerBackend.FLATTENED_TEXTURE -> {
                addRenderChild(
                    MultiLayerTextureView(
                        context = context,
                        logicalLayerCount = current.activeLayers,
                        targetFps = current.producerFps,
                        onFrame = primaryFrameCallback,
                    ),
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
        updateRuntimeControls(current)
    }

    private fun createLayer(current: PhaseSpec, index: Int): View {
        val primary = index == 0
        val callback = if (primary) primaryFrameCallback else null
        if (current.includeGlLayer && index == current.activeLayers - 1) {
            return StressGlSurfaceView(
                context = context,
                complexity = current.workloads.gpu,
                targetFps = current.producerFps,
                onFrame = callback,
            )
        }
        if (primary && mediaUri != null &&
            current.pixelRoute in setOf(PixelRoute.YUV_420, PixelRoute.P010, PixelRoute.SBWC_AUTO)
        ) {
            return VideoSurfaceView(context, mediaUri!!, current.producerFps, callback)
        }
        val useTexture = current.backend == LayerBackend.MIXED_SURFACE_TEXTURE && index % 3 == 2
        return if (useTexture) {
            PatternTextureView(
                context = context,
                layerIndex = index,
                targetFps = current.producerFps,
                logicalYuv = current.pixelRoute in setOf(PixelRoute.YUV_420, PixelRoute.P010),
                onFrame = callback,
            )
        } else {
            PatternSurfaceView(
                context = context,
                layerIndex = index,
                targetFps = current.producerFps,
                pixelRoute = current.pixelRoute,
                bufferSize = if (primary) current.bufferSize else BufferSize.DISPLAY,
                alphaSurface = current.alphaOverlap,
                onFrame = callback,
            )
        }
    }

    private fun addRenderChild(view: View) {
        val params = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        addView(view, params)
        animatedChildren += view
    }

    private fun removeAndReleaseChildren() {
        animatedChildren.forEach {
            when (it) {
                is PatternSurfaceView -> it.release()
                is PatternTextureView -> it.release()
                is MultiLayerTextureView -> it.release()
                is VideoSurfaceView -> it.release()
                is StressGlSurfaceView -> it.releaseLab()
            }
        }
        animatedChildren.clear()
        removeAllViews()
    }

    private fun updateRuntimeControls(current: PhaseSpec) {
        animatedChildren.forEach {
            when (it) {
                is PatternSurfaceView -> it.targetFps = current.producerFps
                is PatternTextureView -> it.targetFps = current.producerFps
                is MultiLayerTextureView -> {
                    it.targetFps = current.producerFps
                    it.setGpuLoad(current.workloads.gpu, current.workloads.shape)
                }
                is VideoSurfaceView -> it.setFrameRateHint(current.producerFps)
                is StressGlSurfaceView -> {
                    it.setTargetFps(current.producerFps)
                    it.setLoad(current.workloads.gpu, current.workloads.shape)
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
    }
}

@SuppressLint("ViewConstructor")
internal class PatternSurfaceView(
    context: Context,
    private val layerIndex: Int,
    targetFps: Float,
    private val pixelRoute: PixelRoute,
    private val bufferSize: BufferSize,
    alphaSurface: Boolean,
    private val onFrame: (() -> Unit)?,
) : SurfaceView(context), SurfaceHolder.Callback {

    @Volatile
    var targetFps: Float = targetFps
    private var loop: CanvasDrawingLoop? = null

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
        loop?.stop()
        applyFrameRate(holder.surface, targetFps)
        loop = CanvasDrawingLoop(
            surface = holder.surface,
            layerIndex = layerIndex,
            fpsProvider = { targetFps },
            logicalYuv = pixelRoute in setOf(PixelRoute.YUV_420, PixelRoute.P010),
            sbwcRequested = pixelRoute in setOf(PixelRoute.SBWC_AUTO, PixelRoute.SBWC_REQUIRED),
            logicalLayerCount = 1,
            onFrame = onFrame,
        ).also { it.start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        applyFrameRate(holder.surface, targetFps)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        release()
    }

    fun release() {
        loop?.stop()
        loop = null
    }
}

@SuppressLint("ViewConstructor")
internal class PatternTextureView(
    context: Context,
    private val layerIndex: Int,
    targetFps: Float,
    private val logicalYuv: Boolean,
    private val onFrame: (() -> Unit)?,
) : TextureView(context), TextureView.SurfaceTextureListener {

    @Volatile
    var targetFps: Float = targetFps
    private var loop: CanvasDrawingLoop? = null
    private var drawSurface: Surface? = null

    init {
        surfaceTextureListener = this
        isOpaque = false
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        release()
        drawSurface = Surface(texture).also { surface ->
            applyFrameRate(surface, targetFps)
            loop = CanvasDrawingLoop(
                surface = surface,
                layerIndex = layerIndex,
                fpsProvider = { targetFps },
                logicalYuv = logicalYuv,
                sbwcRequested = false,
                logicalLayerCount = 1,
                onFrame = onFrame,
            ).also { it.start() }
        }
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        release()
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    fun release() {
        loop?.stop()
        loop = null
        drawSurface?.release()
        drawSurface = null
    }
}

@SuppressLint("ViewConstructor")
internal class MultiLayerTextureView(
    context: Context,
    private val logicalLayerCount: Int,
    targetFps: Float,
    private val onFrame: (() -> Unit)?,
) : TextureView(context), TextureView.SurfaceTextureListener {

    @Volatile
    var targetFps: Float = targetFps
    @Volatile
    private var gpuLoad: Float = 0f
    @Volatile
    private var loadShape: LoadShape = LoadShape.STEADY
    @Volatile
    private var loadStartedMs: Long = SystemClock.elapsedRealtime()
    private var loop: CanvasDrawingLoop? = null
    private var drawSurface: Surface? = null

    init {
        surfaceTextureListener = this
        isOpaque = true
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        release()
        drawSurface = Surface(texture).also { surface ->
            applyFrameRate(surface, targetFps)
            loop = CanvasDrawingLoop(
                surface = surface,
                layerIndex = 0,
                fpsProvider = { targetFps },
                logicalYuv = false,
                sbwcRequested = false,
                logicalLayerCount = logicalLayerCount,
                complexityProvider = ::currentGpuLoad,
                onFrame = onFrame,
            ).also { it.start() }
        }
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    fun setGpuLoad(load: Float, shape: LoadShape) {
        val safeLoad = load.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        if (safeLoad != gpuLoad || shape != loadShape) {
            gpuLoad = safeLoad
            loadShape = shape
            loadStartedMs = SystemClock.elapsedRealtime()
        }
    }

    private fun currentGpuLoad(): Float = shapedLoad(
        base = gpuLoad,
        shape = loadShape,
        elapsedMs = SystemClock.elapsedRealtime() - loadStartedMs,
    )

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        release()
        return true
    }

    fun release() {
        loop?.stop()
        loop = null
        drawSurface?.release()
        drawSurface = null
    }
}

@SuppressLint("ViewConstructor")
internal class VideoSurfaceView(
    context: Context,
    private val uri: Uri,
    @Volatile private var targetFps: Float,
    private val onFrame: (() -> Unit)?,
) : SurfaceView(context), SurfaceHolder.Callback {

    @Volatile
    private var decoderSession: DecoderSession? = null
    private val callbackHandler = Handler(Looper.getMainLooper())

    init {
        holder.addCallback(this)
        holder.setFormat(PixelFormat.OPAQUE)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        applyFrameRate(holder.surface, targetFps)
        startDecoder(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        applyFrameRate(holder.surface, targetFps)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        release()
    }

    fun setFrameRateHint(fps: Float) {
        targetFps = fps
        if (holder.surface.isValid) applyFrameRate(holder.surface, fps)
    }

    private fun startDecoder(surface: Surface) {
        if (decoderSession != null) return
        val sessionRunning = AtomicBoolean(true)
        lateinit var session: DecoderSession
        val thread = Thread({
            var extractor: MediaExtractor? = null
            var codec: MediaCodec? = null
            try {
                extractor = MediaExtractor().apply {
                    setDataSource(context, uri, null)
                }
                var videoTrack = -1
                var format: MediaFormat? = null
                for (index in 0 until extractor.trackCount) {
                    val candidate = extractor.getTrackFormat(index)
                    val mime = candidate.getString(MediaFormat.KEY_MIME).orEmpty()
                    if (mime.startsWith("video/")) {
                        videoTrack = index
                        format = candidate
                        break
                    }
                }
                check(videoTrack >= 0 && format != null) { "No video track" }
                extractor.selectTrack(videoTrack)
                val width = format.getInteger(MediaFormat.KEY_WIDTH)
                val height = format.getInteger(MediaFormat.KEY_HEIGHT)
                if (width > 0 && height > 0) {
                    callbackHandler.post {
                        if (isCurrent(session)) holder.setFixedSize(width, height)
                    }
                }
                val mime = format.getString(MediaFormat.KEY_MIME)
                    ?: error("Video MIME unavailable")
                codec = MediaCodec.createDecoderByType(mime).apply {
                    setOnFrameRenderedListener(
                        { _, _, _ -> if (isCurrent(session)) onFrame?.invoke() },
                        callbackHandler,
                    )
                    configure(format, surface, null, 0)
                    start()
                }

                val outputInfo = MediaCodec.BufferInfo()
                var inputEos = false
                var playbackStartNanos = System.nanoTime()
                var queuedFrame = 0L
                while (sessionRunning.get() && surface.isValid && isCurrent(session)) {
                    if (!inputEos) {
                        val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                        if (inputIndex >= 0) {
                            val input = codec.getInputBuffer(inputIndex)
                            val size = if (input != null) extractor.readSampleData(input, 0) else -1
                            if (size < 0) {
                                codec.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputEos = true
                            } else {
                                codec.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    size,
                                    extractor.sampleTime.coerceAtLeast(0L),
                                    extractor.sampleFlags,
                                )
                                extractor.advance()
                            }
                        }
                    }

                    val outputIndex = codec.dequeueOutputBuffer(outputInfo, CODEC_TIMEOUT_US)
                    when {
                        outputIndex >= 0 -> {
                            val isEos = outputInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            if (outputInfo.size > 0 || !isEos) {
                                val interval = (1_000_000_000L / targetFps.coerceIn(1f, 240f)).toLong()
                                var renderAt = playbackStartNanos + queuedFrame * interval
                                val now = System.nanoTime()
                                if (renderAt < now - 250_000_000L) {
                                    playbackStartNanos = now
                                    queuedFrame = 0
                                    renderAt = now
                                }
                                codec.releaseOutputBuffer(outputIndex, renderAt)
                                queuedFrame++
                            } else {
                                codec.releaseOutputBuffer(outputIndex, false)
                            }
                            if (isEos && sessionRunning.get() && isCurrent(session)) {
                                codec.flush()
                                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                                inputEos = false
                                queuedFrame = 0
                                playbackStartNanos = System.nanoTime()
                            }
                        }

                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outputFormat = codec.outputFormat
                            val outWidth = outputFormat.getInteger(MediaFormat.KEY_WIDTH)
                            val outHeight = outputFormat.getInteger(MediaFormat.KEY_HEIGHT)
                            callbackHandler.post {
                                if (isCurrent(session)) holder.setFixedSize(outWidth, outHeight)
                            }
                        }
                    }
                }
            } catch (_: InterruptedException) {
                // Normal release path.
            } catch (_: Exception) {
                // Capability/result UI records that no frames were rendered.
            } finally {
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                runCatching { extractor?.release() }
                sessionRunning.set(false)
                if (decoderSession === session) decoderSession = null
            }
        }, "DpuLab-MediaCodec")
        session = DecoderSession(sessionRunning, thread)
        decoderSession = session
        thread.start()
    }

    fun release() {
        val session = decoderSession ?: return
        decoderSession = null
        session.running.set(false)
        session.thread.interrupt()
        if (Thread.currentThread() !== session.thread) {
            runCatching { session.thread.join(DECODER_JOIN_TIMEOUT_MS) }
        }
    }

    private fun isCurrent(session: DecoderSession): Boolean =
        decoderSession === session && session.running.get()

    private data class DecoderSession(
        val running: AtomicBoolean,
        val thread: Thread,
    )

    companion object {
        private const val CODEC_TIMEOUT_US = 10_000L
        private const val DECODER_JOIN_TIMEOUT_MS = 150L
    }
}

private class CanvasDrawingLoop(
    private val surface: Surface,
    private val layerIndex: Int,
    private val fpsProvider: () -> Float,
    private val logicalYuv: Boolean,
    private val sbwcRequested: Boolean,
    private val logicalLayerCount: Int,
    private val complexityProvider: () -> Float = { 0f },
    private val onFrame: (() -> Unit)?,
) : Runnable {

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private val painter = PatternPainter(layerIndex, logicalYuv, sbwcRequested)
    private val logicalPainters = List(logicalLayerCount.coerceIn(1, 24)) {
        PatternPainter(it, false, false)
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread(this, "DpuLab-Surface-$layerIndex").also { it.start() }
    }

    fun stop() {
        running.set(false)
        val activeThread = thread
        activeThread?.interrupt()
        if (activeThread != null && Thread.currentThread() !== activeThread) {
            runCatching { activeThread.join(DRAW_JOIN_TIMEOUT_MS) }
        }
        thread = null
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
            try {
                canvas = surface.lockHardwareCanvas()
                val elapsedSeconds = (System.nanoTime() - startedNanos) / 1_000_000_000f
                if (logicalLayerCount == 1) {
                    painter.draw(canvas, elapsedSeconds)
                } else {
                    canvas.drawColor(Color.rgb(6, 12, 17))
                    val complexity = complexityProvider()
                        .takeIf { it.isFinite() }
                        ?.coerceIn(0f, 1f)
                        ?: 0f
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
            } catch (_: Exception) {
                frameDrawn = false
            } finally {
                if (canvas != null) {
                    posted = runCatching { surface.unlockCanvasAndPost(canvas) }.isSuccess
                }
            }
            if (posted && frameDrawn) {
                consecutiveFailures = 0
                runCatching { onFrame?.invoke() }
            } else {
                consecutiveFailures++
            }
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                running.set(false)
                break
            }
            if (consecutiveFailures > 0) {
                val backoffMs = (16L shl (consecutiveFailures - 1).coerceAtMost(4))
                    .coerceAtMost(250L)
                SystemClock.sleep(backoffMs)
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
        private const val MAX_PARK_NANOS = 8_000_000L
        private const val MIN_YIELD_NANOS = 250_000L
        private const val MAX_CONSECUTIVE_FAILURES = 8
        private const val DRAW_JOIN_TIMEOUT_MS = 25L
    }
}

private class PatternPainter(
    private val index: Int,
    private val logicalYuv: Boolean,
    private val sbwcRequested: Boolean,
) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val hsv = FloatArray(3)
    private val labelRect = RectF()
    private val contentRect = RectF()

    fun draw(canvas: Canvas, elapsedSeconds: Float, clear: Boolean = true) {
        val w = canvas.width.toFloat().coerceAtLeast(1f)
        val h = canvas.height.toFloat().coerceAtLeast(1f)
        val t = elapsedSeconds
        val hue = (index * 43f + t * 26f) % 360f
        hsv[0] = hue
        hsv[1] = if (logicalYuv) 0.48f else 0.72f
        hsv[2] = 0.36f
        val base = Color.HSVToColor(hsv)
        if (clear) {
            if (Color.alpha(base) < 255) canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
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
                paint.color = Color.HSVToColor(hsv)
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
        canvas.drawText("LAYER ${index + 1}", w * 0.075f, h * 0.12f, paint)
        paint.textSize *= 0.55f
        paint.isFakeBoldText = false
        val route = when {
            sbwcRequested -> "SBWC request · verify via vendor"
            logicalYuv -> "YUV visual proxy"
            else -> "RGB BufferQueue"
        }
        canvas.drawText(route, w * 0.075f, h * 0.185f, paint)

        hsv[0] = (hue + 180f) % 360f
        hsv[1] = 0.45f
        hsv[2] = 1f
        stroke.color = Color.HSVToColor(hsv)
        val radius = w.coerceAtMost(h) * (0.13f + (sin(t + index) + 1f) * 0.04f)
        canvas.drawCircle(w * 0.72f, h * 0.66f, radius, stroke)
    }
}

internal fun applyFrameRate(surface: Surface, fps: Float) {
    if (surface.isValid) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                surface.setFrameRate(
                    fps.takeIf { it.isFinite() }?.coerceIn(1f, 120f) ?: 60f,
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                    Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS,
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                surface.setFrameRate(
                    fps.takeIf { it.isFinite() }?.coerceIn(1f, 120f) ?: 60f,
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                )
            }
        }
    }
}

private fun shapedLoad(base: Float, shape: LoadShape, elapsedMs: Long): Float {
    if (!base.isFinite() || base <= 0f) return 0f
    val seconds = elapsedMs.coerceAtLeast(0L) / 1_000f
    val factor = when (shape) {
        LoadShape.STEADY -> 1f
        LoadShape.PULSE -> if ((seconds.toInt() / 2) % 2 == 0) 1f else 0f
        LoadShape.RAMP -> ((seconds % 6f) / 6f).coerceIn(0f, 1f)
        LoadShape.SAW -> {
            val position = (seconds % 8f) / 8f
            if (position < 0.5f) position * 2f else (1f - position) * 2f
        }
    }
    return (base * factor).coerceIn(0f, 1f)
}
