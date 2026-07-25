package com.example.dpulayerlab.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.Reader
import java.io.StringReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class SurfaceFlingerProbeTest {
    @Test
    fun boundedDumpReaderRejectsOversizedOutput() {
        assertEquals("abcd", readBoundedText(StringReader("abcd"), 4))
        assertThrows(IllegalStateException::class.java) {
            readBoundedText(StringReader("abcde"), 4)
        }
    }

    @Test
    fun boundedDumpReaderRejectsZeroProgressInsteadOfBusyLooping() {
        val zeroProgressReader = object : Reader() {
            override fun read(buffer: CharArray, offset: Int, length: Int): Int = 0

            override fun close() = Unit
        }

        assertThrows(IllegalStateException::class.java) {
            readBoundedText(zeroProgressReader, 4)
        }
    }

    @Test
    fun parsesMultilineCompositionBlocks() {
        val dump = """
            Total missed frame count: 15
            HWC missed frame count: 11
            GPU missed frame count: 4
            Composition layers
            * Layer 0x1 (com.example.dpulayerlab/MainActivity#10)
                  geomLayerBounds=[0 0 100 100]
                  composition type=DEVICE (2)
            * Layer 0x2 (SurfaceView[com.example.dpulayerlab/MainActivity]#11)
                  geomLayerBounds=[0 0 100 100]
                  composition type=CLIENT (1)
            * Layer 0x3 (com.android.systemui#12)
                  composition type=DEVICE (2)
        """.trimIndent()

        val parsed = parseSurfaceFlingerDump(dump)

        assertEquals(1, parsed.deviceLayers)
        assertEquals(1, parsed.clientLayers)
        assertEquals(11L, parsed.hwcMissedFrames)
        assertEquals(4L, parsed.gpuMissedFrames)
    }

    @Test
    fun unavailableAppLayersRemainNull() {
        val parsed = parseSurfaceFlingerDump("HWC missed frame count: 8")
        assertNull(parsed.deviceLayers)
        assertNull(parsed.clientLayers)
        assertEquals(8L, parsed.hwcMissedFrames)
    }

    @Test
    fun unclassifiedOrAmbiguousAppBlocksRemainUnavailableInsteadOfZeroOrPartial() {
        val unclassified = parseSurfaceFlingerDump(
            """
                * Layer 0x1 (DPULayerTest#10)
                      vendor composition mode=OVERLAY_UNKNOWN
            """.trimIndent(),
        )
        assertNull(unclassified.deviceLayers)
        assertNull(unclassified.clientLayers)

        val partial = parseSurfaceFlingerDump(
            """
                * Layer 0x1 (DPULayerTest#10)
                      composition type=DEVICE (2)
                * Layer 0x2 (SurfaceView[DPULayerTest]#11)
                      vendor composition mode=UNKNOWN
            """.trimIndent(),
        )
        assertNull(partial.deviceLayers)
        assertNull(partial.clientLayers)

        val ambiguous = parseSurfaceFlingerDump(
            """
                * Layer 0x1 (DPULayerTest#10)
                      composition type=DEVICE (2)
                      vendor note=CLIENT
            """.trimIndent(),
        )
        assertNull(ambiguous.deviceLayers)
        assertNull(ambiguous.clientLayers)
    }

    @Test
    fun deviceOrClientTokenInLayerNameIsNotCompositionEvidence() {
        val parsed = parseSurfaceFlingerDump(
            """
                * Layer 0x1 (DPULayerTest CLIENT workload#10)
                      vendor composition mode=UNKNOWN
            """.trimIndent(),
        )

        assertNull(parsed.deviceLayers)
        assertNull(parsed.clientLayers)
    }

    @Test
    fun fullyClassifiedClientOnlyTopologyPreservesMeasuredZeroDeviceLayers() {
        val parsed = parseSurfaceFlingerDump(
            """
                * Layer 0x1 (DPULayerTest#10)
                      composition type=CLIENT (1)
                * Layer 0x2 (SurfaceView[DPULayerTest]#11)
                      composition type=CLIENT (1)
            """.trimIndent(),
        )

        assertEquals(0, parsed.deviceLayers)
        assertEquals(2, parsed.clientLayers)
    }

    @Test
    fun legacyLineFallbackRequiresEveryAppLayerToBeUniquelyClassified() {
        val classified = parseSurfaceFlingerDump(
            """
                DPULayerTest layerA composition type=CLIENT
                DPULayerTest layerB composition type=DEVICE
            """.trimIndent(),
        )
        assertEquals(1, classified.deviceLayers)
        assertEquals(1, classified.clientLayers)

        val unclassified = parseSurfaceFlingerDump(
            """
                DPULayerTest layerA composition type=DEVICE
                DPULayerTest layerB vendor composition mode=UNKNOWN
            """.trimIndent(),
        )
        assertNull(unclassified.deviceLayers)
        assertNull(unclassified.clientLayers)

        val ambiguous = parseSurfaceFlingerDump(
            "DPULayerTest layerA composition type=DEVICE vendor note=CLIENT",
        )
        assertNull(ambiguous.deviceLayers)
        assertNull(ambiguous.clientLayers)
    }

    @Test
    fun parsesRenamedAppLabelWithoutPackageToken() {
        val parsed = parseSurfaceFlingerDump(
            """
                * Layer 0x1 (DPULayerTest#10)
                      composition type=DEVICE (2)
                * Layer 0x2 (SurfaceView[DPULayerTest]#11)
                      composition type=CLIENT (1)
            """.trimIndent(),
        )

        assertEquals(1, parsed.deviceLayers)
        assertEquals(1, parsed.clientLayers)
    }

    @Test
    fun overflowingMissedCountersRemainUnavailable() {
        val parsed = parseSurfaceFlingerDump(
            """
                HWC missed frame count: 9223372036854775808
                GPU missed frame count: 9223372036854775809
            """.trimIndent(),
        )

        assertNull(parsed.hwcMissedFrames)
        assertNull(parsed.gpuMissedFrames)
    }

    @Test
    fun singleFlightLaneRejectsConcurrentProbeWithoutQueueing() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val invocations = AtomicInteger()
        val lane = SingleFlightProbeLane(
            threadName = "SurfaceFlingerProbeTest-single",
            timeoutMs = 2_000L,
            shutdownTimeoutMs = 200L,
        ) {
            invocations.incrementAndGet()
            started.countDown()
            release.await()
            "done"
        }
        val firstResult = AtomicReference<ProbeLaneResult<String>>()
        val firstCaller = Thread {
            firstResult.set(lane.execute())
        }

        firstCaller.start()
        assertTrue(started.await(1, TimeUnit.SECONDS))
        assertEquals(ProbeLaneResult.Busy, lane.execute())
        assertEquals(1, invocations.get())

        release.countDown()
        firstCaller.join(1_000L)
        assertFalse(firstCaller.isAlive)
        assertEquals(ProbeLaneResult.Completed("done"), firstResult.get())
        assertTrue(lane.closeWithResult())
    }

    @Test
    fun inputLaneCapturesOnlyAcceptedRequestAndNeverBuildsBacklog() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val inputs = mutableListOf<Int>()
        val lane = SingleFlightInputProbeLane<Int, String>(
            threadName = "SurfaceFlingerProbeTest-input",
            timeoutMs = 2_000L,
            shutdownTimeoutMs = 200L,
        ) { input, _ ->
            inputs += input
            if (input == 1) {
                started.countDown()
                release.await()
            }
            "done-$input"
        }
        val firstResult = AtomicReference<ProbeLaneResult<String>>()
        val firstCaller = Thread {
            firstResult.set(lane.execute(1))
        }

        firstCaller.start()
        assertTrue(started.await(1, TimeUnit.SECONDS))
        assertEquals(ProbeLaneResult.Busy, lane.execute(2))
        assertEquals(listOf(1), inputs)

        release.countDown()
        firstCaller.join(1_000L)
        assertFalse(firstCaller.isAlive)
        assertEquals(ProbeLaneResult.Completed("done-1"), firstResult.get())
        assertEquals(ProbeLaneResult.Completed("done-3"), lane.execute(3))
        assertEquals(listOf(1, 3), inputs)
        assertTrue(lane.closeWithResult())
    }

    @Test
    fun inputLaneCallerDeadlineCanShortenButNotExtendTheHardTimeout() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val invocations = AtomicInteger()
        val lane = SingleFlightInputProbeLane<Int, String>(
            threadName = "SurfaceFlingerProbeTest-caller-deadline",
            timeoutMs = 2_000L,
            shutdownTimeoutMs = 500L,
        ) { _, cancellation ->
            invocations.incrementAndGet()
            cancellation.registerCleanup(release::countDown)
            started.countDown()
            release.await()
            "done"
        }

        val startedNanos = System.nanoTime()
        assertEquals(ProbeLaneResult.TimedOut, lane.execute(1, callerTimeoutMs = 40L))
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)
        assertTrue("caller timeout took ${elapsedMs}ms", elapsedMs < 1_000L)
        assertTrue(started.await(1, TimeUnit.SECONDS))
        assertTrue(waitUntil(1_000L) { !lane.isActive() })
        assertEquals(1, invocations.get())

        assertEquals(ProbeLaneResult.TimedOut, lane.execute(2, callerTimeoutMs = 0L))
        assertEquals(1, invocations.get())
        assertTrue(lane.closeWithResult())
    }

    @Test
    fun timeoutKeepsGateClosedUntilWorkerActuallyExits() {
        val release = CountDownLatch(1)
        val invocations = AtomicInteger()
        val lane = SingleFlightProbeLane(
            threadName = "SurfaceFlingerProbeTest-timeout",
            timeoutMs = 30L,
            shutdownTimeoutMs = 200L,
        ) {
            val invocation = invocations.incrementAndGet()
            if (invocation == 1) {
                while (release.count > 0L) {
                    try {
                        release.await()
                    } catch (_: InterruptedException) {
                        // Model a broken vendor stream which ignores interruption.
                    }
                }
            }
            "done-$invocation"
        }

        assertEquals(ProbeLaneResult.TimedOut, lane.execute())
        assertTrue(lane.isActive())
        assertEquals(ProbeLaneResult.Busy, lane.execute())
        assertEquals(1, invocations.get())

        release.countDown()
        assertTrue(waitUntil(1_000L) { !lane.isActive() })
        assertEquals(ProbeLaneResult.Completed("done-2"), lane.execute())
        assertTrue(lane.closeWithResult())
    }

    @Test
    fun nonClosingIdleBarrierWaitsForTheTimedOutWorkersActualFinally() {
        val release = CountDownLatch(1)
        val lane = SingleFlightInputProbeLane<Int, String>(
            threadName = "SurfaceFlingerProbeTest-idle-barrier",
            timeoutMs = 25L,
            shutdownTimeoutMs = 200L,
        ) { input, _ ->
            while (release.count > 0L) {
                try {
                    release.await()
                } catch (_: InterruptedException) {
                    // Model a Binder/native operation which ignores Future cancellation.
                }
            }
            "done-$input"
        }

        assertEquals(ProbeLaneResult.TimedOut, lane.execute(1))
        assertFalse(lane.awaitIdle(20L))
        assertEquals(ProbeLaneResult.Busy, lane.execute(2))

        release.countDown()
        assertTrue(lane.awaitIdle(1_000L))
        assertEquals(ProbeLaneResult.Completed("done-3"), lane.execute(3))
        assertTrue(lane.closeWithResult())
    }

    @Test
    fun closeCancelsActiveProbeAndWaitsForActualCompletion() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val lane = SingleFlightProbeLane(
            threadName = "SurfaceFlingerProbeTest-close",
            timeoutMs = 2_000L,
            shutdownTimeoutMs = 500L,
        ) { cancellation ->
            cancellation.registerCleanup(release::countDown)
            started.countDown()
            release.await()
            "done"
        }
        val sampleResult = AtomicReference<ProbeLaneResult<String>>()
        val caller = Thread {
            sampleResult.set(lane.execute())
        }

        caller.start()
        assertTrue(started.await(1, TimeUnit.SECONDS))
        assertTrue(lane.closeWithResult())
        caller.join(1_000L)

        assertFalse(caller.isAlive)
        assertEquals(ProbeLaneResult.Cancelled, sampleResult.get())
        assertFalse(lane.isActive())
        assertEquals(ProbeLaneResult.Closed, lane.execute())
    }

    @Test
    fun closeAtExecutorHandoffNeverLeavesTheActiveGateSticky() {
        repeat(32) { iteration ->
            val lane = SingleFlightProbeLane(
                threadName = "SurfaceFlingerProbeTest-handoff-$iteration",
                timeoutMs = 200L,
                shutdownTimeoutMs = 500L,
            ) { "done" }
            val caller = Thread { lane.execute() }

            caller.start()
            assertTrue(lane.closeWithResult())
            caller.join(1_000L)

            assertFalse(caller.isAlive)
            assertFalse(lane.isActive())
            assertEquals(ProbeLaneResult.Closed, lane.execute())
        }
    }

    @Test
    fun sharedRegistryUsesOneLaneAcrossOverlappingActivityOwners() {
        val factoryCalls = AtomicInteger()
        val registry = SharedProbeLaneRegistry {
            factoryCalls.incrementAndGet()
            SingleFlightProbeLane(
                threadName = "SurfaceFlingerProbeTest-shared",
                timeoutMs = 500L,
                shutdownTimeoutMs = 200L,
            ) { "ok" }
        }
        val first = registry.acquire()
        val second = registry.acquire()

        assertEquals(1, factoryCalls.get())
        assertEquals(ProbeLaneResult.Completed("ok"), first.execute())
        assertTrue(first.closeWithResult())
        assertEquals(ProbeLaneResult.Completed("ok"), second.execute())
        assertTrue(second.closeWithResult())

        val replacement = registry.acquire()
        assertEquals(2, factoryCalls.get())
        assertTrue(replacement.closeWithResult())
    }

    @Test
    fun failedSharedLaneCloseQuarantinesFutureOwnersWithoutNewWorker() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val factoryCalls = AtomicInteger()
        val registry = SharedProbeLaneRegistry {
            factoryCalls.incrementAndGet()
            SingleFlightProbeLane(
                threadName = "SurfaceFlingerProbeTest-quarantine",
                timeoutMs = 100L,
                shutdownTimeoutMs = 25L,
            ) {
                started.countDown()
                while (release.count > 0L) {
                    try {
                        release.await()
                    } catch (_: InterruptedException) {
                        // Model a vendor pipe that ignores cancellation.
                    }
                }
                "late"
            }
        }
        val first = registry.acquire()
        val firstResult = AtomicReference<ProbeLaneResult<String>>()
        val caller = Thread { firstResult.set(first.execute()) }

        caller.start()
        assertTrue(started.await(1L, TimeUnit.SECONDS))
        caller.join(1_000L)
        assertFalse(caller.isAlive)
        assertEquals(ProbeLaneResult.TimedOut, firstResult.get())
        assertFalse(first.closeWithResult())

        val quarantined = registry.acquire()
        assertEquals(1, factoryCalls.get())
        assertEquals(ProbeLaneResult.Closed, quarantined.execute())
        assertFalse(quarantined.closeWithResult())
        release.countDown()
        assertTrue(waitUntil(1_000L) { registry.hasRecoverableQuarantine() })
        // A repeated close must preserve the first unconfirmed result. It cannot reinterpret a
        // late worker exit as proof that the original owner observed a clean shutdown.
        assertFalse(first.closeWithResult())

        val recovered = registry.acquire()
        assertEquals(2, factoryCalls.get())
        assertTrue(recovered.closeWithResult())
    }

    @Test
    fun closeRemainsBoundedWhenWorkerIgnoresCancellation() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val lane = SingleFlightProbeLane(
            threadName = "SurfaceFlingerProbeTest-stuck-close",
            timeoutMs = 2_000L,
            shutdownTimeoutMs = 40L,
        ) {
            started.countDown()
            while (release.count > 0L) {
                try {
                    release.await()
                } catch (_: InterruptedException) {
                    // Model a native/vendor reader which ignores Java interruption.
                }
            }
            "done"
        }
        val sampleResult = AtomicReference<ProbeLaneResult<String>>()
        val caller = Thread {
            sampleResult.set(lane.execute())
        }

        caller.start()
        assertTrue(started.await(1, TimeUnit.SECONDS))
        val closeStartedNanos = System.nanoTime()
        assertFalse(lane.closeWithResult())
        val closeElapsedMs = TimeUnit.NANOSECONDS.toMillis(
            System.nanoTime() - closeStartedNanos,
        )

        assertTrue("close took ${closeElapsedMs}ms", closeElapsedMs < 1_000L)
        assertTrue(lane.isActive())
        assertEquals(ProbeLaneResult.Closed, lane.execute())
        release.countDown()
        caller.join(1_000L)
        assertFalse(caller.isAlive)
        assertEquals(ProbeLaneResult.Cancelled, sampleResult.get())
        assertTrue(waitUntil(1_000L) { !lane.isActive() })
        assertTrue(lane.closeWithResult())
    }

    @Test
    fun childCleanupWaitsAgainAfterFinalKillBeforePublishingSuccess() {
        val process = ControllableProcess(exitAfterWaitCall = 2)
        val registry = UnconfirmedProcessRegistry()

        assertTrue(
            cleanupProbeProcess(
                process = process,
                waitMs = 10L,
                registry = registry,
            ),
        )
        assertEquals(2, process.waitCalls.get())
        assertEquals(2, process.destroyCalls.get())
        assertTrue(registry.isRestartSafe())
    }

    @Test
    fun probeShutdownBudgetExceedsBothChildWaitsAndSaturatesSafely() {
        val budget = surfaceFlingerProbeShutdownBudgetMs(
            processCleanupWaitMs = 100L,
            completionMarginMs = 300L,
        )

        assertEquals(500L, budget)
        assertTrue(budget > 2L * 100L)
        assertEquals(
            Long.MAX_VALUE,
            surfaceFlingerProbeShutdownBudgetMs(
                processCleanupWaitMs = Long.MAX_VALUE,
                completionMarginMs = 1L,
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            surfaceFlingerProbeShutdownBudgetMs(
                processCleanupWaitMs = -1L,
                completionMarginMs = 1L,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            surfaceFlingerProbeShutdownBudgetMs(
                processCleanupWaitMs = 1L,
                completionMarginMs = 0L,
            )
        }
    }

    @Test
    fun childWhichSurvivesBothWaitsBlocksReuseUntilDeathIsObserved() {
        val process = ControllableProcess(exitAfterWaitCall = null)
        val registry = UnconfirmedProcessRegistry()

        assertFalse(
            cleanupProbeProcess(
                process = process,
                waitMs = 10L,
                registry = registry,
            ),
        )
        assertEquals(2, process.waitCalls.get())
        assertFalse(registry.isRestartSafe())

        process.markExited()
        assertTrue(registry.isRestartSafe())
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.yield()
        }
        return condition()
    }

    private class ControllableProcess(
        private val exitAfterWaitCall: Int?,
    ) : Process() {
        private val alive = AtomicBoolean(true)
        val waitCalls = AtomicInteger()
        val destroyCalls = AtomicInteger()
        private val input = ByteArrayInputStream(ByteArray(0))
        private val error = ByteArrayInputStream(ByteArray(0))
        private val output = ByteArrayOutputStream()

        override fun getOutputStream(): OutputStream = output

        override fun getInputStream(): InputStream = input

        override fun getErrorStream(): InputStream = error

        override fun waitFor(): Int {
            markExited()
            return 0
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            val call = waitCalls.incrementAndGet()
            if (exitAfterWaitCall != null && call >= exitAfterWaitCall) {
                markExited()
            }
            return !alive.get()
        }

        override fun exitValue(): Int {
            if (alive.get()) throw IllegalThreadStateException("still alive")
            return 0
        }

        override fun destroy() {
            destroyCalls.incrementAndGet()
        }

        override fun destroyForcibly(): Process {
            destroyCalls.incrementAndGet()
            return this
        }

        override fun isAlive(): Boolean = alive.get()

        fun markExited() {
            alive.set(false)
        }
    }
}
