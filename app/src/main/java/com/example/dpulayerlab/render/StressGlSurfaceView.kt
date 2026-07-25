package com.example.dpulayerlab.render

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.example.dpulayerlab.model.LoadShape
import com.example.dpulayerlab.model.LoadShapeEvaluator
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
class StressGlSurfaceView(
    context: Context,
    complexity: Float,
    targetFps: Float,
    mediaOverlay: Boolean = false,
    private val captureFrameCommit: (() -> (() -> Unit)?)? = null,
    private val onTeardownFailure: (() -> Unit)? = null,
    private val onRuntimeFailure: ((String) -> Unit)? = null,
) : SurfaceView(context), SurfaceHolder.Callback {

    private val explicitlyReleased = AtomicBoolean(false)
    private val restartWhenStopped = AtomicBoolean(false)
    @Volatile
    private var glSession: GlSession? = null
    @Volatile
    private var targetFps = safeGlFps(targetFps)
    @Volatile
    private var baseLoad = complexity.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    @Volatile
    private var loadShape = LoadShape.STEADY
    @Volatile
    private var loadStartedMs = SystemClock.elapsedRealtime()
    @Volatile
    private var surfaceWidth = 1
    @Volatile
    private var surfaceHeight = 1
    private var recoveryStartedMs = 0L
    private var recoveryPending = false
    private val deferredRestart = Runnable(::continueDeferredRestart)

    init {
        holder.addCallback(this)
        holder.setFormat(android.graphics.PixelFormat.OPAQUE)
        setZOrderMediaOverlay(mediaOverlay)
    }

    fun setTargetFps(fps: Float) {
        val normalized = safeGlFps(fps)
        if (normalized == targetFps) return
        targetFps = normalized
        applyFrameRateHint()
        glSession?.let { session ->
            session.workload.targetFps = normalized
            LockSupport.unpark(session.thread)
        }
    }

    fun setLoad(load: Float, shape: LoadShape, restartProfile: Boolean = false) {
        val safeLoad = load.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        val loadChanged = safeLoad != baseLoad
        val shapeChanged = shape != loadShape
        baseLoad = safeLoad
        loadShape = shape
        if (restartProfile || shapeChanged) {
            loadStartedMs = SystemClock.elapsedRealtime()
        }
        if (restartProfile || loadChanged || shapeChanged) {
            glSession?.let { session ->
                session.workload.baseLoad = baseLoad
                session.workload.loadShape = loadShape
                session.workload.loadStartedMs = loadStartedMs
                LockSupport.unpark(session.thread)
            }
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (explicitlyReleased.get()) return
        surfaceWidth = holder.surfaceFrame.width().coerceAtLeast(1)
        surfaceHeight = holder.surfaceFrame.height().coerceAtLeast(1)
        applyFrameRateHint()
        glSession?.let { session ->
            requestStop(session)
            if (session.thread.isAlive) RendererSafetyState.trackUnconfirmed(session.thread)
        }
        scheduleDeferredRestart()
    }

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int,
    ) {
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)
        applyFrameRateHint()
        glSession?.let { session ->
            session.workload.width = surfaceWidth
            session.workload.height = surfaceHeight
            LockSupport.unpark(session.thread)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // Surface lifecycle callbacks must never wait for a stalled driver. Explicit phase
        // teardown performs the bounded join; spontaneous surface loss only revokes the lease.
        restartWhenStopped.set(false)
        cancelDeferredRestart()
        glSession?.let { session ->
            requestStop(session)
            if (session.thread.isAlive) RendererSafetyState.trackUnconfirmed(session.thread)
        }
    }

    fun requestStopLab() {
        explicitlyReleased.set(true)
        restartWhenStopped.set(false)
        cancelDeferredRestart()
        glSession?.let(::requestStop)
    }

    fun releaseLab(stopDeadlineNanos: Long = glStopDeadlineNanos()): Boolean =
        stopCurrentSession(stopDeadlineNanos, permanently = true)

    private fun startGlSession(windowSurface: Surface) {
        if (explicitlyReleased.get() || !windowSurface.isValid) {
            return
        }
        if (RendererSafetyState.hasUnconfirmedTeardown()) {
            scheduleDeferredRestart()
            return
        }
        val existing = glSession
        if (existing != null) {
            if (existing.thread.isAlive) {
                restartWhenStopped.set(true)
                requestStop(existing)
                RendererSafetyState.trackUnconfirmed(existing.thread)
                scheduleDeferredRestart()
                return
            }
            glSession = null
        }
        restartWhenStopped.set(false)
        val session = GlSession(
            workload = GlWorkloadState(
                targetFps = targetFps,
                baseLoad = baseLoad,
                loadShape = loadShape,
                loadStartedMs = loadStartedMs,
                width = surfaceWidth,
                height = surfaceHeight,
            ),
            uiCallbacks = GlUiCallbacks(
                onCompleted = { completed, runtimeFailure ->
                    val wasCurrent = glSession === completed
                    if (wasCurrent) glSession = null
                    if (
                        wasCurrent &&
                        runtimeFailure != null &&
                        !explicitlyReleased.get() &&
                        holder.surface.isValid
                    ) {
                        onRuntimeFailure?.invoke(runtimeFailure)
                    }
                    if (
                        restartWhenStopped.getAndSet(false) &&
                        !explicitlyReleased.get() &&
                        holder.surface.isValid
                    ) {
                        scheduleDeferredRestart()
                    }
                },
                onTeardownFailure = onTeardownFailure,
            ),
            captureFrameCommit = captureFrameCommit,
        )
        val thread = try {
            Thread(GlProducerWorker(windowSurface, session), "DpuLab-GLProducer")
        } catch (error: Throwable) {
            session.detachUiCallbacks()
            if (error is ThreadDeath || error is VirtualMachineError) throw error
            onRuntimeFailure?.invoke(
                "GL thread create ${error.javaClass.simpleName}",
            )
            return
        }
        session.thread = thread
        glSession = session
        val threadStarted = startRendererThread(thread) { error ->
            onRuntimeFailure?.invoke(
                "GL thread start ${error.javaClass.simpleName}",
            )
            session.detachUiCallbacks()
            session.running.set(false)
            if (glSession === session) glSession = null
            restartWhenStopped.set(false)
        }
        if (!threadStarted) {
            return
        }
    }

    private fun scheduleDeferredRestart() {
        if (explicitlyReleased.get() || !holder.surface.isValid) return
        if (!recoveryPending) {
            recoveryPending = true
            recoveryStartedMs = SystemClock.elapsedRealtime()
        }
        removeCallbacks(deferredRestart)
        post(deferredRestart)
    }

    private fun continueDeferredRestart() {
        if (!recoveryPending) return
        val existing = glSession
        if (existing != null && existing.thread.isAlive) {
            requestStop(existing)
            RendererSafetyState.trackUnconfirmed(existing.thread)
        } else if (existing != null) {
            glSession = null
        }
        when (
            producerRecoveryDecision(
                targetValid = !explicitlyReleased.get() && holder.surface.isValid,
                processLeaseActive = RendererSafetyState.hasUnconfirmedTeardown(),
                elapsedMs = SystemClock.elapsedRealtime() - recoveryStartedMs,
                timeoutMs = GL_PRODUCER_RECOVERY_TIMEOUT_MS,
            )
        ) {
            ProducerRecoveryDecision.CANCEL -> cancelDeferredRestart()
            ProducerRecoveryDecision.START -> {
                recoveryPending = false
                startGlSession(holder.surface)
            }
            ProducerRecoveryDecision.FAIL -> {
                recoveryPending = false
                restartWhenStopped.set(false)
                onTeardownFailure?.invoke()
            }
            ProducerRecoveryDecision.WAIT ->
                postDelayed(deferredRestart, GL_PRODUCER_RECOVERY_POLL_MS)
        }
    }

    private fun cancelDeferredRestart() {
        recoveryPending = false
        removeCallbacks(deferredRestart)
    }

    private fun stopCurrentSession(
        stopDeadlineNanos: Long,
        permanently: Boolean,
    ): Boolean {
        if (permanently) explicitlyReleased.set(true)
        if (permanently) restartWhenStopped.set(false)
        if (permanently) cancelDeferredRestart()
        val session = glSession ?: return true
        requestStop(session)
        val stopped = if (Thread.currentThread() === session.thread) {
            true
        } else {
            joinUntil(session.thread, stopDeadlineNanos)
        }
        if (stopped && glSession === session) glSession = null
        return stopped
    }

    private fun requestStop(session: GlSession) {
        // Detach every View/Activity callback before entering the bounded native EGL hand-off.
        // A slow driver thread then retains only Surface + scalar workload/session state.
        session.detachUiCallbacks()
        session.running.set(false)
        session.thread.interrupt()
        LockSupport.unpark(session.thread)
    }

    private fun applyFrameRateHint() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && holder.surface.isValid) {
            runCatching {
                holder.surface.setFrameRate(
                    targetFps,
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                )
            }
        }
    }

    private fun glStopDeadlineNanos(): Long =
        System.nanoTime() + TICKER_JOIN_TIMEOUT_NANOS

    companion object {
        private const val MAX_PARK_NANOS = 50_000_000L
        private const val MIN_YIELD_NANOS = 250_000L
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val TICKER_JOIN_TIMEOUT_NANOS = 100_000_000L
    }

    /**
     * Deliberately static/nested: a process-leased EGL thread must not own the SurfaceView. The
     * callback holder is atomically cleared before join, leaving only Surface and scalar state.
     */
    private class GlProducerWorker(
        private val windowSurface: Surface,
        private val session: GlSession,
    ) : Runnable {
        override fun run() {
            try {
                val runtimeFailure = runGlLoop()
                session.running.set(false)
                if (!session.postCompleted(runtimeFailure)) {
                    session.detachUiCallbacks()
                }
            } catch (fatal: Throwable) {
                // runGlLoop has already executed its native EGL finally block. Complete the
                // Activity-free session transaction before rethrowing fatal VM errors so a live
                // SurfaceView cannot retain a dead current session or its Controller callbacks.
                session.running.set(false)
                val completionPosted = try {
                    session.postCompleted(FATAL_GL_WORKER_FAILURE)
                } catch (_: Throwable) {
                    false
                }
                if (!completionPosted) session.detachUiCallbacks()
                try {
                    RendererSafetyState.markCleanupFailure(
                        component = "EGL worker fatal exit",
                        detail = fatal.javaClass.simpleName,
                    )
                } catch (_: Throwable) {
                    // Callback detachment and running=false above are the allocation-free
                    // ownership rollback. Preserve the original fatal throwable.
                }
                throw fatal
            }
        }

        private fun runGlLoop(): String? {
            val workload = session.workload
            val renderer = CubeRenderer(workload.baseLoad)
            var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
            var context: EGLContext = EGL14.EGL_NO_CONTEXT
            var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
            var runtimeFailure: String? = null
            var fatalFailure: Throwable? = null
            try {
                display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
                val versions = IntArray(2)
                check(EGL14.eglInitialize(display, versions, 0, versions, 1)) {
                    "EGL initialize failed"
                }
                val config = chooseEglConfig(display)
                context = EGL14.eglCreateContext(
                    display,
                    config,
                    EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                    0,
                )
                check(context != EGL14.EGL_NO_CONTEXT) { "EGL context creation failed" }
                eglSurface = EGL14.eglCreateWindowSurface(
                    display,
                    config,
                    windowSurface,
                    intArrayOf(EGL14.EGL_NONE),
                    0,
                )
                check(eglSurface != EGL14.EGL_NO_SURFACE) {
                    "EGL window surface creation failed"
                }
                check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
                    "EGL makeCurrent failed"
                }

                renderer.onSurfaceCreated()
                var appliedWidth = 0
                var appliedHeight = 0
                var nextFrameNanos = System.nanoTime()
                while (session.running.get() && windowSurface.isValid) {
                    val width = workload.width
                    val height = workload.height
                    if (width != appliedWidth || height != appliedHeight) {
                        renderer.onSurfaceChanged(width, height)
                        appliedWidth = width
                        appliedHeight = height
                    }
                    val now = System.nanoTime()
                    if (now < nextFrameNanos) {
                        LockSupport.parkNanos(
                            (nextFrameNanos - now).coerceAtMost(MAX_PARK_NANOS),
                        )
                        if (Thread.interrupted() && !session.running.get()) break
                        continue
                    }
                    renderer.setComplexity(
                        LoadShapeEvaluator.intensityAt(
                            base = workload.baseLoad,
                            shape = workload.loadShape,
                            elapsedMs = SystemClock.elapsedRealtime() - workload.loadStartedMs,
                        ),
                    )
                    val frameCommit = session.captureFrameCommit()
                    renderer.onDrawFrame()
                    if (!EGL14.eglSwapBuffers(display, eglSurface)) {
                        runtimeFailure =
                            "EGL swap failed (0x${EGL14.eglGetError().toString(16)})"
                        break
                    }
                    runCatching { frameCommit?.invoke() }

                    val interval =
                        (1_000_000_000L / safeGlFps(workload.targetFps)).toLong()
                    val completed = System.nanoTime()
                    nextFrameNanos = if (completed - nextFrameNanos >= interval) {
                        completed + MIN_YIELD_NANOS
                    } else {
                        nextFrameNanos + interval
                    }
                }
            } catch (error: Throwable) {
                if (error is ThreadDeath || error is VirtualMachineError) {
                    fatalFailure = error
                    throw error
                }
                runtimeFailure = glRuntimeFailureReason(error)
            } finally {
                var cleanupFailed = false
                var firstCleanupOperation: String? = null
                var firstCleanupError: Throwable? = null
                var cleanupFatalFailure: Throwable? = null
                if (display != EGL14.EGL_NO_DISPLAY) {
                    val clearedCurrent = try {
                        EGL14.eglMakeCurrent(
                            display,
                            EGL14.EGL_NO_SURFACE,
                            EGL14.EGL_NO_SURFACE,
                            EGL14.EGL_NO_CONTEXT,
                        )
                    } catch (error: Throwable) {
                        cleanupFailed = true
                        firstCleanupOperation = "eglMakeCurrent"
                        firstCleanupError = error
                        if (error is ThreadDeath || error is VirtualMachineError) {
                            cleanupFatalFailure = error
                        }
                        false
                    }
                    if (!clearedCurrent) {
                        cleanupFailed = true
                        if (firstCleanupOperation == null) firstCleanupOperation = "eglMakeCurrent"
                    }
                    if (eglSurface != EGL14.EGL_NO_SURFACE) {
                        val destroyed = try {
                            EGL14.eglDestroySurface(display, eglSurface)
                        } catch (error: Throwable) {
                            cleanupFailed = true
                            if (
                                cleanupFatalFailure == null &&
                                (error is ThreadDeath || error is VirtualMachineError)
                            ) {
                                cleanupFatalFailure = error
                            }
                            if (firstCleanupOperation == null) {
                                firstCleanupOperation = "eglDestroySurface"
                                firstCleanupError = error
                            }
                            false
                        }
                        if (!destroyed) {
                            cleanupFailed = true
                            if (firstCleanupOperation == null) {
                                firstCleanupOperation = "eglDestroySurface"
                            }
                        }
                    }
                    if (context != EGL14.EGL_NO_CONTEXT) {
                        val destroyed = try {
                            EGL14.eglDestroyContext(display, context)
                        } catch (error: Throwable) {
                            cleanupFailed = true
                            if (
                                cleanupFatalFailure == null &&
                                (error is ThreadDeath || error is VirtualMachineError)
                            ) {
                                cleanupFatalFailure = error
                            }
                            if (firstCleanupOperation == null) {
                                firstCleanupOperation = "eglDestroyContext"
                                firstCleanupError = error
                            }
                            false
                        }
                        if (!destroyed) {
                            cleanupFailed = true
                            if (firstCleanupOperation == null) {
                                firstCleanupOperation = "eglDestroyContext"
                            }
                        }
                    }
                    val terminated = try {
                        EGL14.eglTerminate(display)
                    } catch (error: Throwable) {
                        cleanupFailed = true
                        if (
                            cleanupFatalFailure == null &&
                            (error is ThreadDeath || error is VirtualMachineError)
                        ) {
                            cleanupFatalFailure = error
                        }
                        if (firstCleanupOperation == null) {
                            firstCleanupOperation = "eglTerminate"
                            firstCleanupError = error
                        }
                        false
                    }
                    if (!terminated) {
                        cleanupFailed = true
                        if (firstCleanupOperation == null) {
                            firstCleanupOperation = "eglTerminate"
                        }
                    }
                }
                if (cleanupFailed) {
                    runtimeFailure = runtimeFailure ?: "EGL native cleanup unconfirmed"
                    try {
                        val operation = firstCleanupOperation ?: "unknown"
                        val errorName = firstCleanupError?.javaClass?.simpleName
                        val detail = if (errorName == null) {
                            "$operation=false"
                        } else {
                            "$operation=$errorName"
                        }
                        RendererSafetyState.markCleanupFailure(
                            component = "EGL native cleanup",
                            detail = detail,
                        )
                    } catch (_: Throwable) {
                        // Native cleanup above is the required rollback. Diagnostics are best effort
                        // when the VM is already failing to allocate.
                    }
                    try {
                        session.dispatchTeardownFailure()
                    } catch (_: Throwable) {
                        // Preserve the original fatal failure after detaching native ownership.
                    }
                }
                val cleanupFatal = cleanupFatalFailure
                if (
                    fatalFailure == null &&
                    (cleanupFatal is ThreadDeath || cleanupFatal is VirtualMachineError)
                ) {
                    throw cleanupFatal
                }
            }
            return runtimeFailure.takeIf {
                session.running.get() && windowSurface.isValid
            }
        }

        private fun chooseEglConfig(display: EGLDisplay): EGLConfig {
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            val attributes = intArrayOf(
                EGL14.EGL_RED_SIZE,
                8,
                EGL14.EGL_GREEN_SIZE,
                8,
                EGL14.EGL_BLUE_SIZE,
                8,
                EGL14.EGL_ALPHA_SIZE,
                8,
                EGL14.EGL_DEPTH_SIZE,
                16,
                EGL14.EGL_RENDERABLE_TYPE,
                EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE,
            )
            check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0))
            check(count[0] > 0)
            return checkNotNull(configs[0])
        }

        private companion object {
            const val FATAL_GL_WORKER_FAILURE = "EGL/GL fatal worker exit"
        }
    }

    private class GlSession(
        val running: AtomicBoolean,
        val workload: GlWorkloadState,
        uiCallbacks: GlUiCallbacks,
        captureFrameCommit: (() -> (() -> Unit)?)?,
    ) {
        lateinit var thread: Thread
        private val callbacks = AtomicReference<GlUiCallbacks?>(uiCallbacks)
        private val frameCommitCaptureRef =
            AtomicReference<(() -> (() -> Unit)?)?>(captureFrameCommit)
        private val mainHandler = Handler(Looper.getMainLooper())

        constructor(
            workload: GlWorkloadState,
            uiCallbacks: GlUiCallbacks,
            captureFrameCommit: (() -> (() -> Unit)?)?,
        ) : this(
            running = AtomicBoolean(true),
            workload = workload,
            uiCallbacks = uiCallbacks,
            captureFrameCommit = captureFrameCommit,
        )

        fun detachUiCallbacks() {
            callbacks.set(null)
            frameCommitCaptureRef.set(null)
        }

        fun captureFrameCommit(): (() -> Unit)? = frameCommitCaptureRef.get()?.invoke()

        fun dispatchTeardownFailure() {
            mainHandler.post {
                callbacks.get()?.onTeardownFailure?.invoke()
            }
        }

        fun postCompleted(runtimeFailure: String?): Boolean =
            mainHandler.post {
                callbacks.get()?.onCompleted?.invoke(this, runtimeFailure)
            }
    }

    private data class GlUiCallbacks(
        val onCompleted: ((GlSession, String?) -> Unit)?,
        val onTeardownFailure: (() -> Unit)?,
    )

    private data class GlWorkloadState(
        @Volatile var targetFps: Float,
        @Volatile var baseLoad: Float,
        @Volatile var loadShape: LoadShape,
        @Volatile var loadStartedMs: Long,
        @Volatile var width: Int,
        @Volatile var height: Int,
    )
}

private class CubeRenderer(
    complexity: Float,
) {

    @Volatile
    private var complexity: Float =
        complexity.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f

    private var program = 0
    private var positionHandle = 0
    private var colorHandle = 0
    private var matrixHandle = 0
    private var matrixView = IntArray(1)
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val modelViewProjection = FloatArray(16)
    private val temp = FloatArray(16)
    private val started = System.nanoTime()

    private val vertexBuffer: FloatBuffer = floatBuffer(
        floatArrayOf(
            -1f, -1f, -1f, 1f, -1f, -1f, 1f, 1f, -1f, -1f, 1f, -1f,
            -1f, -1f, 1f, 1f, -1f, 1f, 1f, 1f, 1f, -1f, 1f, 1f,
        ),
    )
    private val colorBuffer: FloatBuffer = floatBuffer(
        floatArrayOf(
            0.18f, 0.91f, 0.76f, 1f, 0.25f, 0.50f, 1f, 1f,
            0.94f, 0.43f, 0.59f, 1f, 0.93f, 0.80f, 0.30f, 1f,
            0.42f, 0.92f, 0.45f, 1f, 0.68f, 0.38f, 0.95f, 1f,
            0.95f, 0.52f, 0.23f, 1f, 0.25f, 0.86f, 0.96f, 1f,
        ),
    )
    private val indexBuffer: ShortBuffer = shortBuffer(
        shortArrayOf(
            0, 1, 2, 0, 2, 3,
            4, 6, 5, 4, 7, 6,
            0, 4, 5, 0, 5, 1,
            3, 2, 6, 3, 6, 7,
            1, 5, 6, 1, 6, 2,
            0, 3, 7, 0, 7, 4,
        ),
    )

    fun setComplexity(value: Float) {
        complexity = value.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    }

    fun onSurfaceCreated() {
        GLES20.glClearColor(0.015f, 0.035f, 0.045f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        colorHandle = GLES20.glGetAttribLocation(program, "aColor")
        matrixHandle = GLES20.glGetUniformLocation(program, "uMvp")
        matrixView[0] = GLES20.glGetUniformLocation(program, "uComplexity")
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val ratio = width.toFloat() / height.coerceAtLeast(1)
        Matrix.perspectiveM(projection, 0, 48f, ratio, 1f, 30f)
        Matrix.setLookAtM(view, 0, 0f, 1.2f, 7.2f, 0f, 0f, 0f, 0f, 1f, 0f)
    }

    fun onDrawFrame() {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glUseProgram(program)
        vertexBuffer.position(0)
        colorBuffer.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glVertexAttribPointer(colorHandle, 4, GLES20.GL_FLOAT, false, 0, colorBuffer)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glEnableVertexAttribArray(colorHandle)
        val currentComplexity = complexity
        GLES20.glUniform1f(matrixView[0], currentComplexity)

        val time = (System.nanoTime() - started) / 1_000_000_000f
        val copies = (1 + currentComplexity * 13).roundToInt()
        repeat(copies) { index ->
            Matrix.setIdentityM(model, 0)
            val column = (index % 4) - 1.5f
            val row = (index / 4) - 1f
            val scale = if (copies == 1) 1.35f else 0.48f
            Matrix.translateM(model, 0, column * 1.55f, row * 1.45f, 0f)
            Matrix.scaleM(model, 0, scale, scale, scale)
            Matrix.rotateM(model, 0, time * (34f + index), 0.6f, 1f, 0.25f)
            Matrix.multiplyMM(temp, 0, view, 0, model, 0)
            Matrix.multiplyMM(modelViewProjection, 0, projection, 0, temp, 0)
            GLES20.glUniformMatrix4fv(matrixHandle, 1, false, modelViewProjection, 0)
            indexBuffer.position(0)
            GLES20.glDrawElements(
                GLES20.GL_TRIANGLES,
                indexBuffer.capacity(),
                GLES20.GL_UNSIGNED_SHORT,
                indexBuffer,
            )
        }
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        check(status[0] == GLES20.GL_TRUE) {
            "GL program link failed: ${GLES20.glGetProgramInfoLog(program)}"
        }
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) {
            "GL shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}"
        }
        return shader
    }

    companion object {
        private const val VERTEX_SHADER = """
            uniform mat4 uMvp;
            attribute vec4 aPosition;
            attribute vec4 aColor;
            varying vec4 vColor;
            varying vec3 vPos;
            void main() {
                vColor = aColor;
                vPos = aPosition.xyz;
                gl_Position = uMvp * aPosition;
            }
        """
        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform float uComplexity;
            varying vec4 vColor;
            varying vec3 vPos;
            void main() {
                vec3 color = vColor.rgb;
                if (uComplexity <= 0.001) {
                    gl_FragColor = vec4(color, 1.0);
                    return;
                }
                float energy = 0.0;
                float activeTerms = clamp(uComplexity, 0.0, 1.0) * 28.0;
                for (int i = 0; i < 28; ++i) {
                    float fi = float(i) + 1.0;
                    if (fi <= activeTerms) {
                        energy += sin(vPos.x * fi * 1.17 + vPos.y * fi * 0.73) * 0.008;
                    }
                }
                gl_FragColor = vec4(color + energy, 1.0);
            }
        """

        private fun floatBuffer(values: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply { put(values); position(0) }

        private fun shortBuffer(values: ShortArray): ShortBuffer =
            ByteBuffer.allocateDirect(values.size * 2)
                .order(ByteOrder.nativeOrder())
                .asShortBuffer()
                .apply { put(values); position(0) }
    }
}

private fun joinUntil(thread: Thread, deadlineNanos: Long): Boolean {
    if (!thread.isAlive) return true
    val remainingNanos = deadlineNanos - System.nanoTime()
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

private fun safeGlFps(fps: Float): Float =
    fps.takeIf { it.isFinite() }?.coerceIn(1f, 120f) ?: 60f

private fun glRuntimeFailureReason(error: Throwable): String {
    val type = error.javaClass.simpleName.takeIf(String::isNotBlank) ?: "Throwable"
    val detail = error.message
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.take(MAX_GL_RUNTIME_ERROR_DETAIL_CHARS)
    return buildString {
        append("EGL/GL ")
        append(type)
        detail?.let {
            append(": ")
            append(it)
        }
    }.take(MAX_GL_RUNTIME_FAILURE_CHARS)
}

private const val NANOS_PER_MILLI = 1_000_000L
private const val GL_PRODUCER_RECOVERY_TIMEOUT_MS = 5_000L
private const val GL_PRODUCER_RECOVERY_POLL_MS = 16L
private const val MAX_GL_RUNTIME_ERROR_DETAIL_CHARS = 160
private const val MAX_GL_RUNTIME_FAILURE_CHARS = 240
