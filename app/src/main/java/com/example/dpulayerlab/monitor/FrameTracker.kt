package com.example.dpulayerlab.monitor

import android.view.Choreographer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Portable proxy only. Missed UI vsync deadlines are never labelled as exact DPU underruns.
 */
class FrameTracker : Choreographer.FrameCallback {
    private val running = AtomicBoolean(false)
    private val produced = AtomicLong(0)
    private val totalProduced = AtomicLong(0)
    private val missed = AtomicLong(0)
    private val deadlineLock = Any()

    // Accessed by both the Choreographer thread and the monitor's IO dispatcher.
    // Keep both fields under one lock so a refresh-rate change and its baseline reset are atomic.
    private var lastFrameNanos = 0L

    private var expectedRefreshHz = 60f

    fun start() {
        resetDeadlineBaseline()
        if (!running.compareAndSet(false, true)) return
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() {
        running.set(false)
        Choreographer.getInstance().removeFrameCallback(this)
        resetDeadlineBaseline()
    }

    fun onPrimaryBufferProduced() {
        produced.incrementAndGet()
        totalProduced.incrementAndGet()
    }

    fun updateExpectedRefresh(hz: Float) {
        if (!hz.isFinite() || hz <= 1f || hz > MAX_REASONABLE_REFRESH_HZ) return
        synchronized(deadlineLock) {
            if (abs(expectedRefreshHz - hz) >= REFRESH_CHANGE_EPSILON_HZ) {
                expectedRefreshHz = hz
                lastFrameNanos = 0L
            }
        }
    }

    fun sampleProducedFrames(): Long = produced.getAndSet(0)

    fun totalProducedFrames(): Long = totalProduced.get()

    fun totalMissedFrames(): Long = missed.get()

    override fun doFrame(frameTimeNanos: Long) {
        if (!running.get()) return
        val lost = synchronized(deadlineLock) {
            val previousFrameNanos = lastFrameNanos
            lastFrameNanos = frameTimeNanos
            if (previousFrameNanos <= 0L || frameTimeNanos <= previousFrameNanos) {
                0L
            } else {
                val expected = NANOS_PER_SECOND / expectedRefreshHz.toDouble()
                val delta = (frameTimeNanos - previousFrameNanos).toDouble()
                if (delta > expected * MISSED_DEADLINE_FACTOR) {
                    (delta / expected).roundToLong().minus(1).coerceAtLeast(1L)
                } else {
                    0L
                }
            }
        }
        if (lost > 0L) missed.addAndGet(lost)
        if (running.get()) Choreographer.getInstance().postFrameCallback(this)
    }

    private fun resetDeadlineBaseline() {
        synchronized(deadlineLock) {
            lastFrameNanos = 0L
        }
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val MISSED_DEADLINE_FACTOR = 1.55
        const val REFRESH_CHANGE_EPSILON_HZ = 0.05f
        const val MAX_REASONABLE_REFRESH_HZ = 1_000f
    }
}
