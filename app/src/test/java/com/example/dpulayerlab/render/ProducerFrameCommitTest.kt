package com.example.dpulayerlab.render

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProducerFrameCommitTest {
    @Test
    fun normalCallbackFailureFailsClosedAndAllowsWorkerFinallyCleanup() {
        val failure = IllegalStateException("injected FrameTracker failure")
        var workerRunning = true
        var reported: Throwable? = null
        var reportCount = 0
        var cleanupCount = 0

        try {
            val committed = invokeProducerFrameCommitFailClosed(
                frameCommit = { throw failure },
                onFailure = { error ->
                    workerRunning = false
                    reported = error
                    reportCount++
                },
            )
            assertFalse(committed)
        } finally {
            // Mirrors the Canvas Surface/SurfaceTexture and EGL native finally barriers.
            cleanupCount++
        }

        assertFalse(workerRunning)
        assertSame(failure, reported)
        assertTrue(reportCount == 1)
        assertTrue(cleanupCount == 1)
    }

    @Test
    fun outOfMemoryIsReportedThenRethrownAfterEnteringCleanupPath() {
        val failure = OutOfMemoryError("injected frame callback OOME")
        var workerRunning = true
        var reported: Throwable? = null
        var cleaned = false
        val thrown = try {
            try {
                invokeProducerFrameCommitFailClosed(
                    frameCommit = { throw failure },
                    onFailure = { error ->
                        workerRunning = false
                        reported = error
                    },
                )
            } finally {
                cleaned = true
            }
        } catch (error: Throwable) {
            error
        }

        assertFalse(workerRunning)
        assertSame(failure, reported)
        assertSame(failure, thrown)
        assertTrue(cleaned)
    }

    @Test
    fun threadDeathIsNeverConvertedIntoARecoverableCallbackFailure() {
        val failure = ThreadDeath()
        var reported: Throwable? = null
        var cleaned = false
        val thrown = try {
            try {
                invokeProducerFrameCommitFailClosed(
                    frameCommit = { throw failure },
                    onFailure = { reported = it },
                )
            } finally {
                cleaned = true
            }
        } catch (error: Throwable) {
            error
        }

        assertSame(failure, reported)
        assertSame(failure, thrown)
        assertTrue(cleaned)
    }

    @Test
    fun canvasNativeOutOfMemoryWinsOverRuntimeNotificationFailureAfterCleanup() {
        val nativeFailure = OutOfMemoryError("injected lockHardwareCanvas OOME")
        val notificationFailure = IllegalStateException("injected runtime callback failure")
        var reportCount = 0
        var cleaned = false
        val thrown = try {
            try {
                reportProducerFailureFailClosed(nativeFailure) { reported ->
                    reportCount++
                    assertSame(nativeFailure, reported)
                    throw notificationFailure
                }
            } finally {
                // Mirrors CanvasDrawingLoop.run(): the worker's Surface/SurfaceTexture ownership
                // is released before the fatal VM error leaves the producer thread.
                cleaned = true
            }
        } catch (error: Throwable) {
            error
        }

        assertTrue(reportCount == 1)
        assertTrue(cleaned)
        assertSame(nativeFailure, thrown)
    }

    @Test
    fun canvasDrawFatalSurvivesBothFatalTextureCleanupAttempts() {
        val drawFailure = OutOfMemoryError("injected Canvas draw OOME")
        val surfaceReleaseFailure = OutOfMemoryError("injected Surface.release OOME")
        val textureReleaseFailure = ThreadDeath()
        var surfaceReleaseAttempted = false
        var textureReleaseAttempted = false
        val thrown = try {
            try {
                throw drawFailure
            } catch (error: Throwable) {
                val capturedSurfaceFatal = try {
                    surfaceReleaseAttempted = true
                    throw surfaceReleaseFailure
                } catch (cleanupError: Throwable) {
                    cleanupError
                }
                val capturedTextureFatal = try {
                    textureReleaseAttempted = true
                    throw textureReleaseFailure
                } catch (cleanupError: Throwable) {
                    cleanupError
                }
                throw checkNotNull(
                    fatalAfterProducerCleanup(
                        originalFatal = error,
                        surfaceCleanupFatal = capturedSurfaceFatal,
                        surfaceTextureCleanupFatal = capturedTextureFatal,
                    ),
                )
            }
        } catch (error: Throwable) {
            error
        }

        assertTrue(surfaceReleaseAttempted)
        assertTrue(textureReleaseAttempted)
        assertSame(drawFailure, thrown)
        assertTrue(drawFailure.suppressed.contains(surfaceReleaseFailure))
        assertTrue(drawFailure.suppressed.contains(textureReleaseFailure))
    }
}
