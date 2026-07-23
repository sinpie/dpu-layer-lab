package com.example.dpulayerlab.render

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import com.example.dpulayerlab.model.LoadShape
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
class StressGlSurfaceView(
    context: Context,
    complexity: Float,
    targetFps: Float,
    onFrame: (() -> Unit)? = null,
) : GLSurfaceView(context) {

    private val labRenderer = CubeRenderer(complexity, onFrame)
    private val tickerRunning = AtomicBoolean(true)
    @Volatile
    private var targetFps = targetFps.takeIf { it.isFinite() }?.coerceIn(1f, 120f) ?: 60f
    @Volatile
    private var baseLoad = complexity.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    @Volatile
    private var loadShape = LoadShape.STEADY
    @Volatile
    private var loadStartedMs = SystemClock.elapsedRealtime()
    private val ticker = Thread({
        var nextFrame = System.nanoTime()
        while (tickerRunning.get()) {
            val now = System.nanoTime()
            if (now < nextFrame) {
                LockSupport.parkNanos((nextFrame - now).coerceAtMost(MAX_PARK_NANOS))
                continue
            }
            labRenderer.setComplexity(
                shapedLoad(
                    base = baseLoad,
                    shape = loadShape,
                    elapsedMs = SystemClock.elapsedRealtime() - loadStartedMs,
                ),
            )
            requestRender()
            val fps = targetFps.takeIf { it.isFinite() }?.coerceIn(1f, 120f) ?: 60f
            val interval = (1_000_000_000L / fps).toLong()
            val completed = System.nanoTime()
            nextFrame = if (completed - nextFrame >= interval) {
                completed + MIN_YIELD_NANOS
            } else {
                nextFrame + interval
            }
        }
    }, "DpuLab-GLTicker")

    init {
        setEGLContextClientVersion(2)
        // Phase transitions create and remove GL views frequently; retaining detached
        // contexts would accumulate driver-side resources across a long scenario.
        preserveEGLContextOnPause = false
        setRenderer(labRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        ticker.start()
    }

    fun setTargetFps(fps: Float) {
        targetFps = fps.takeIf { it.isFinite() }?.coerceIn(1f, 120f) ?: 60f
    }

    fun setLoad(load: Float, shape: LoadShape) {
        val safeLoad = load.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        if (safeLoad != baseLoad || shape != loadShape) {
            baseLoad = safeLoad
            loadShape = shape
            loadStartedMs = SystemClock.elapsedRealtime()
        }
    }

    fun releaseLab() {
        tickerRunning.set(false)
        ticker.interrupt()
        if (Thread.currentThread() !== ticker) {
            runCatching { ticker.join(TICKER_JOIN_TIMEOUT_MS) }
        }
        onPause()
    }

    companion object {
        private const val MAX_PARK_NANOS = 8_000_000L
        private const val MIN_YIELD_NANOS = 250_000L
        private const val TICKER_JOIN_TIMEOUT_MS = 100L
    }
}

private class CubeRenderer(
    complexity: Float,
    private val onFrame: (() -> Unit)?,
) : GLSurfaceView.Renderer {

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

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.015f, 0.035f, 0.045f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        colorHandle = GLES20.glGetAttribLocation(program, "aColor")
        matrixHandle = GLES20.glGetUniformLocation(program, "uMvp")
        matrixView[0] = GLES20.glGetUniformLocation(program, "uComplexity")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val ratio = width.toFloat() / height.coerceAtLeast(1)
        Matrix.perspectiveM(projection, 0, 48f, ratio, 1f, 30f)
        Matrix.setLookAtM(view, 0, 0f, 1.2f, 7.2f, 0f, 0f, 0f, 0f, 1f, 0f)
    }

    override fun onDrawFrame(gl: GL10?) {
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
        runCatching { onFrame?.invoke() }
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
                float energy = 0.0;
                for (int i = 0; i < 28; ++i) {
                    float fi = float(i) + 1.0;
                    energy += sin(vPos.x * fi * 1.17 + vPos.y * fi * 0.73) * 0.008 * uComplexity;
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
