package com.example.dpulayerlab.render

import com.example.dpulayerlab.model.AppProducerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ProducerFrameRelayTest {
    @Test
    fun producerKindRemainsBoundToPhysicalRelayAcrossGenerationRebind() {
        val relay = ProducerFrameRelay(
            producerId = 41L,
            generation = 1L,
            primary = true,
            kind = AppProducerKind.VIDEO_DECODER,
        ) { _, _, _, _ -> Unit }

        relay.update(2L) { _, _, _, _ -> Unit }

        assertEquals(AppProducerKind.VIDEO_DECODER, relay.kind)
        assertTrue(relay.primary)
    }

    @Test
    fun physicalProducerRelayPreservesIdentityAndPrimaryAttribution() {
        val events = mutableListOf<Triple<Long, Long, Boolean>>()
        val relay = ProducerFrameRelay(
            producerId = 42L,
            generation = 7L,
            primary = false,
        ) { generation, producerId, primary, _ ->
            events += Triple(generation, producerId, primary)
        }

        relay.emit()
        relay.update(8L) { generation, producerId, primary, _ ->
            events += Triple(generation, producerId, primary)
        }
        relay.emit()
        relay.disable()
        relay.emit()

        assertEquals(
            listOf(
                Triple(7L, 42L, false),
                Triple(8L, 42L, false),
            ),
            events,
        )
    }

    @Test
    fun frameCapturedBeforeGenerationRebindIsDetachedFromOldOwner() {
        val generations = mutableListOf<Long>()
        val relay = ProducerFrameRelay(
            producerId = 9L,
            generation = 100L,
            primary = false,
        ) { generation, _, _, _ -> generations += generation }

        val oldFrameCommit = relay.captureCallback()
        relay.update(101L) { generation, _, _, _ -> generations += generation }
        oldFrameCommit?.invoke()
        relay.captureCallback()?.invoke()

        assertEquals(listOf(101L), generations)
    }

    @Test
    fun controlRevisionSwapDropsInFlightOldFrameAndTagsOnlyPostApplyFrame() {
        val revisions = mutableListOf<Long>()
        val relay = ProducerFrameRelay(
            producerId = 5L,
            generation = 3L,
            primary = true,
        ) { _, _, _, controlRevision -> revisions += controlRevision }

        val stalePreApplyCommit = relay.captureCallback()
        relay.updateControlRevision(7L)
        stalePreApplyCommit?.invoke()
        relay.captureCallback()?.invoke()

        assertEquals(listOf(7L), revisions)
    }

    @Test
    fun bindingPrepareOomeBeforeCollectionOrMidRelayPreservesEveryOldIdentity() {
        val events = mutableListOf<Long>()
        val relays = (0 until 20).map { index ->
            ProducerFrameRelay(
                producerId = index.toLong(),
                generation = 1L,
                primary = index == 0,
            ) { generation, _, _, _ -> events += generation }
        }
        val oldTokens = relays.map(ProducerFrameRelay::captureCallback)
        val oldDispatches = relays.map(ProducerFrameRelay::captureFailureDispatch)
        val failures = listOf(
            0 to OutOfMemoryError("collection"),
            10 to OutOfMemoryError("dispatch-10"),
        )

        failures.forEach { (faultIndex, injected) ->
            val thrown = try {
                ProducerRelayBindingTransaction.prepare(
                    relays = relays,
                    generation = 2L,
                    controlRevision = 0L,
                    callback = ProducerFrameCallback { _, _, _, _ -> Unit },
                    beforeCollectionAllocation = {
                        if (faultIndex == 0) throw injected
                    },
                    beforeFailureDispatchAllocation = { index ->
                        if (faultIndex != 0 && index == faultIndex) throw injected
                    },
                )
                null
            } catch (failure: OutOfMemoryError) {
                failure
            }
            assertSame(injected, thrown)
            relays.forEachIndexed { index, relay ->
                assertSame(oldTokens[index], relay.captureCallback())
                assertSame(oldDispatches[index], relay.captureFailureDispatch())
            }
        }

        relays.forEach(ProducerFrameRelay::emit)
        assertEquals(List(20) { 1L }, events)
    }

    @Test
    fun partialBindingCommitThreadDeathRevokesOldReplacementAndDispatchIdentities() {
        val events = mutableListOf<Long>()
        val relays = (0 until 3).map { index ->
            ProducerFrameRelay(
                producerId = index.toLong(),
                generation = 1L,
                primary = index == 0,
            ) { generation, _, _, _ -> events += generation }
        }
        val oldTokens = relays.map(ProducerFrameRelay::captureCallback)
        val transaction = ProducerRelayBindingTransaction.prepare(
            relays = relays,
            generation = 2L,
            controlRevision = 0L,
            callback = ProducerFrameCallback { generation, _, _, _ -> events += generation },
        )
        val injected = ThreadDeath()
        val thrown = try {
            transaction.commitAll(beforeCommit = { index ->
                if (index == 1) throw injected
            })
            null
        } catch (failure: ThreadDeath) {
            failure
        }
        val currentTokens = relays.map(ProducerFrameRelay::captureCallback)
        transaction.cancelPrepared()
        transaction.revokeAll()

        oldTokens.forEach { it?.invoke() }
        currentTokens.forEach { it?.invoke() }
        relays.forEach(ProducerFrameRelay::emit)
        assertSame(injected, thrown)
        assertTrue(events.isEmpty())
        relays.forEach { relay ->
            assertNull(relay.captureCallback())
            assertNull(relay.captureFailureDispatch())
        }
    }

    @Test
    fun bindingFatalImmediatelyAfterTokenSwapIsRevokedWithoutChangingFatalIdentity() {
        val fatals = listOf<Error>(ThreadDeath(), OutOfMemoryError("binding-swap"))
        fatals.forEach { injected ->
            val events = mutableListOf<Long>()
            val relay = ProducerFrameRelay(
                producerId = 77L,
                generation = 1L,
                primary = true,
            ) { generation, _, _, _ -> events += generation }
            val oldToken = checkNotNull(relay.captureCallback())
            val transaction = ProducerRelayBindingTransaction.prepare(
                relays = listOf(relay),
                generation = 2L,
                controlRevision = 0L,
                callback = ProducerFrameCallback { generation, _, _, _ ->
                    events += generation
                },
            )
            val thrown = try {
                transaction.commitAll(afterTokenSwap = { throw injected })
                null
            } catch (failure: Error) {
                failure
            }
            val replacement = checkNotNull(relay.captureCallback())
            transaction.cancelPrepared()
            transaction.revokeAll()

            oldToken.invoke()
            replacement.invoke()
            relay.emit()
            assertSame(injected, thrown)
            assertTrue(events.isEmpty())
            assertNull(relay.captureCallback())
            assertNull(relay.captureFailureDispatch())
        }
    }

    @Test
    fun expectedSetAllocationAndCallbackFatalsRunRollbackAndPreserveIdentity() {
        val fatals = listOf<Error>(
            OutOfMemoryError("expected-set-allocation"),
            ThreadDeath(),
        )
        fatals.forEach { injected ->
            var rollbackCount = 0
            val thrown = try {
                runFailClosedProducerPublication(
                    publication = { throw injected },
                    rollback = { failure ->
                        rollbackCount++
                        failure
                    },
                )
                null
            } catch (failure: Error) {
                failure
            }
            assertSame(injected, thrown)
            assertEquals(1, rollbackCount)
        }

        var ordinaryRollback = 0
        assertFalse(
            runFailClosedProducerPublication(
                publication = { throw IllegalStateException("callback") },
                rollback = { failure ->
                    ordinaryRollback++
                    failure
                },
            ),
        )
        assertEquals(1, ordinaryRollback)
    }

    @Test
    fun controlAndTransformFatalsUseTheSameFailClosedRendererBoundary() {
        listOf("reconcile-snapshot", "runtime-control", "transform").forEach { stage ->
            listOf<Error>(
                OutOfMemoryError(stage),
                ThreadDeath(),
            ).forEach { injected ->
                var rollbackCount = 0
                val thrown = try {
                    runFailClosedRendererMutation(
                        mutation = { throw injected },
                        rollback = { failure ->
                            rollbackCount++
                            failure
                        },
                    )
                    null
                } catch (failure: Error) {
                    failure
                }
                assertSame(injected, thrown)
                assertEquals(1, rollbackCount)
            }
        }
    }

    @Test
    fun controlRevisionPrepareOomePreservesAllTwentyOldBindings() {
        val revisions = mutableListOf<Long>()
        val relays = (0 until 20).map { index ->
            ProducerFrameRelay(
                producerId = index.toLong(),
                generation = 5L,
                primary = index == 0,
            ) { _, _, _, revision -> revisions += revision }
        }
        val oldTokens = relays.map(ProducerFrameRelay::captureCallback)
        val injected = OutOfMemoryError("prepare-10")

        val thrown = try {
            ProducerControlRevisionTransaction.prepare(
                relays = relays,
                controlRevision = 9L,
                beforeTokenAllocation = { index ->
                    if (index == 10) throw injected
                },
            )
            null
        } catch (failure: OutOfMemoryError) {
            failure
        }

        assertSame(injected, thrown)
        relays.forEachIndexed { index, relay ->
            assertSame(oldTokens[index], relay.captureCallback())
            relay.emit()
        }
        assertEquals(List(20) { 0L }, revisions)
    }

    @Test
    fun stalePreparedControlRevisionCannotReviveRelayAfterRebind() {
        val events = mutableListOf<Pair<Long, Long>>()
        val relay = ProducerFrameRelay(
            producerId = 8L,
            generation = 1L,
            primary = true,
        ) { generation, _, _, revision -> events += generation to revision }
        val transaction = ProducerControlRevisionTransaction.prepare(
            relays = listOf(relay),
            controlRevision = 7L,
        )

        relay.update(generation = 2L, controlRevision = 0L) { generation, _, _, revision ->
            events += generation to revision
        }
        assertFalse(transaction.validateAll())
        assertFalse(transaction.commitAll())
        transaction.cancelPrepared()

        relay.emit()
        assertEquals(listOf(2L to 0L), events)
    }

    @Test
    fun partialCommitFailureCanRevokeEveryRelayWithoutRevivingOldTokens() {
        val relays = (0 until 3).map { index ->
            ProducerFrameRelay(
                producerId = index.toLong(),
                generation = 1L,
                primary = index == 0,
            ) { _, _, _, _ -> Unit }
        }
        val transaction = ProducerControlRevisionTransaction.prepare(relays, 3L)
        val injected = ThreadDeath()
        val thrown = try {
            transaction.commitAll { index ->
                if (index == 1) throw injected
            }
            null
        } catch (failure: ThreadDeath) {
            failure
        }
        transaction.cancelPrepared()
        transaction.revokeAll()

        assertSame(injected, thrown)
        relays.forEach { relay -> assertNull(relay.captureCallback()) }
    }

    @Test
    fun fatalImmediatelyAfterTokenSwapRevokesOrphanOldAndNewTokens() {
        val fatals = listOf<Error>(
            ThreadDeath(),
            OutOfMemoryError("after-swap"),
        )
        fatals.forEach { injected ->
            val events = mutableListOf<Long>()
            val relay = ProducerFrameRelay(
                producerId = 44L,
                generation = 2L,
                primary = true,
            ) { _, _, _, revision -> events += revision }
            val oldToken = checkNotNull(relay.captureCallback())
            val transaction = ProducerControlRevisionTransaction.prepare(
                relays = listOf(relay),
                controlRevision = 6L,
            )

            val thrown = try {
                transaction.commitAll(
                    afterTokenSwap = { throw injected },
                )
                null
            } catch (failure: Error) {
                failure
            }
            val replacementToken = checkNotNull(relay.captureCallback())
            transaction.cancelPrepared()
            transaction.revokeAll()

            oldToken.invoke()
            replacementToken.invoke()
            relay.emit()
            assertSame(injected, thrown)
            assertTrue(events.isEmpty())
            assertNull(relay.captureCallback())
        }
    }

    @Test
    fun decoderFrameCommitQueueBindsTimestampAndSupportsExactSubmissionRollback() {
        val events = mutableListOf<String>()
        val queue = DecoderFrameCommitQueue(capacity = 3)
        val old10 = { events += "old-10" }
        val twenty = { events += "20" }
        val new10 = { events += "new-10" }
        val wrongIdentity = { events += "wrong" }

        assertFalse(queue.offer(0L, 10L, old10))
        assertTrue(queue.offer(1L, 10L, old10))
        assertTrue(queue.offer(1L, 20L, twenty))
        assertTrue(queue.offer(1L, 10L, new10))
        assertFalse(queue.offer(1L, 30L) { events += "overflow" })

        // Render callbacks may be delivered with a different cadence; timestamp matching must not
        // relabel the first queued buffer with the latest producer-control token.
        assertTrue(queue.invoke(1L, 20L))
        // A failed releaseOutputBuffer rolls back only its exact submission identity. Reused PTS
        // from another source loop must remain queued.
        assertFalse(queue.remove(1L, 10L, wrongIdentity))
        assertTrue(queue.remove(1L, 10L, old10))
        assertTrue(queue.invoke(1L, 10L))

        assertEquals(listOf("20", "new-10"), events)
        assertFalse(queue.invoke(1L, 10L))

        assertTrue(queue.offer(1L, 40L) { events += "cleared" })
        queue.clear()
        assertTrue(queue.offer(2L, 40L) { events += "epoch-2" })
        // Models a pre-flush EVENT_FRAME_RENDERED delivered after clear and a new same-source PTS
        // offer. Its old listener epoch must not consume the new loop's token.
        assertFalse(queue.invoke(1L, 40L))
        assertTrue(queue.invoke(2L, 40L))
        assertEquals(listOf("20", "new-10", "epoch-2"), events)

        assertEquals(1L, nextDecoderFrameCallbackEpoch(0L))
        assertEquals(2L, nextDecoderFrameCallbackEpoch(1L))
        assertNull(nextDecoderFrameCallbackEpoch(-1L))
        assertNull(nextDecoderFrameCallbackEpoch(Long.MAX_VALUE))
    }

    @Test(timeout = 2_000L)
    fun decoderFrameCommitQueueClearDoesNotWaitForClaimedExternalCallback() {
        val queue = DecoderFrameCommitQueue(capacity = 1)
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val clearCompleted = CountDownLatch(1)
        assertTrue(
            queue.offer(1L, 10L) {
                callbackEntered.countDown()
                releaseCallback.await(1L, TimeUnit.SECONDS)
            },
        )
        val callbackThread = Thread {
            queue.invoke(1L, 10L)
        }
        val clearThread = Thread {
            queue.clear()
            clearCompleted.countDown()
        }
        try {
            callbackThread.start()
            assertTrue(callbackEntered.await(500L, TimeUnit.MILLISECONDS))
            clearThread.start()
            assertTrue(clearCompleted.await(500L, TimeUnit.MILLISECONDS))
        } finally {
            releaseCallback.countDown()
            callbackThread.join(500L)
            clearThread.join(500L)
        }
        assertFalse(callbackThread.isAlive)
        assertFalse(clearThread.isAlive)
    }

    @Test
    fun physicalLifecycleTeardownRunsEveryActionAndPromotesLaterFatal() {
        val pendingFailure = IllegalStateException("pending")
        val releaseFatal = OutOfMemoryError("release")
        val actions = mutableListOf<String>()

        val thrown = try {
            performProducerLifecycleTeardown(
                signalPending = {
                    actions += "pending"
                    throw pendingFailure
                },
                cancelPendingStart = {
                    actions += "cancel"
                },
                releaseProducer = {
                    actions += "release"
                    throw releaseFatal
                },
            )
            null
        } catch (failure: Throwable) {
            failure
        }

        assertSame(releaseFatal, thrown)
        assertEquals(listOf("pending", "cancel", "release"), actions)
        assertTrue(releaseFatal.suppressed.any { it === pendingFailure })
    }

    @Test
    fun suppressionInvalidationPreservesTransactionOwnedDepthUntilEveryFinallyRuns() {
        val suppression = ExpectedPublicationSuppression()
        val outer = suppression.begin()

        suppression.invalidate()
        val replacement = suppression.begin()

        assertTrue(suppression.end(replacement))
        assertTrue(suppression.isSuppressed())
        assertFalse(suppression.end(outer))
        assertFalse(suppression.isSuppressed())
    }

    @Test
    fun expectedPublicationBookkeepingRejectsEveryReentrantIdentityChange() {
        val valid = expectedProducerPublicationCommitIsCurrent(
            capturedMutationEpoch = 7L,
            currentMutationEpoch = 7L,
            capturedGeneration = 11L,
            currentGeneration = 11L,
            callbackIdentityMatches = true,
            relayIdentityMatches = true,
            publicationStillEligible = true,
        )
        assertTrue(valid)
        assertFalse(
            expectedProducerPublicationCommitIsCurrent(
                capturedMutationEpoch = 7L,
                currentMutationEpoch = 8L,
                capturedGeneration = 11L,
                currentGeneration = 11L,
                callbackIdentityMatches = true,
                relayIdentityMatches = true,
                publicationStillEligible = true,
            ),
        )
        assertFalse(
            expectedProducerPublicationCommitIsCurrent(
                capturedMutationEpoch = 7L,
                currentMutationEpoch = 7L,
                capturedGeneration = 11L,
                currentGeneration = 12L,
                callbackIdentityMatches = true,
                relayIdentityMatches = true,
                publicationStillEligible = true,
            ),
        )
        assertFalse(
            expectedProducerPublicationCommitIsCurrent(
                capturedMutationEpoch = 7L,
                currentMutationEpoch = 7L,
                capturedGeneration = 11L,
                currentGeneration = 11L,
                callbackIdentityMatches = false,
                relayIdentityMatches = true,
                publicationStillEligible = true,
            ),
        )
        assertFalse(
            expectedProducerPublicationCommitIsCurrent(
                capturedMutationEpoch = 7L,
                currentMutationEpoch = 7L,
                capturedGeneration = 11L,
                currentGeneration = 11L,
                callbackIdentityMatches = true,
                relayIdentityMatches = false,
                publicationStillEligible = true,
            ),
        )
    }

    @Test
    fun decoderStopRequestRunsEveryActionAndNeverSwallowsQuitFatal() {
        val detachFailure = IllegalStateException("detach")
        val quitFatal = ThreadDeath()
        val actions = mutableListOf<String>()

        val terminal = performDecoderStopRequestActions(
            detachUiCallbacks = {
                actions += "detach"
                throw detachFailure
            },
            stopDecoder = {
                actions += "stop"
            },
            closeFrameCallbacks = {
                actions += "close"
                throw quitFatal
            },
            interruptDecoder = {
                actions += "interrupt"
            },
        )

        assertSame(quitFatal, terminal)
        assertEquals(listOf("detach", "stop", "close", "interrupt"), actions)
        assertTrue(quitFatal.suppressed.any { it === detachFailure })
    }

    @Test
    fun decoderFrameCallbackCloseAttemptsQuitAfterEarlierFailure() {
        val removeFailure = IllegalStateException("remove")
        val quitFatal = OutOfMemoryError("quit")
        val actions = mutableListOf<String>()

        val terminal = performDecoderFrameCallbackCloseActions(
            closeGate = { actions += "gate" },
            clearFrameCommits = { actions += "clear" },
            removeQueuedCallbacks = {
                actions += "remove"
                throw removeFailure
            },
            requestQuit = {
                actions += "quit"
                throw quitFatal
            },
        )

        assertSame(quitFatal, terminal)
        assertEquals(listOf("gate", "clear", "remove", "quit"), actions)
        assertTrue(quitFatal.suppressed.any { it === removeFailure })
    }

    @Test
    fun decoderTeardownJoinsBothThreadsAfterStopAndFirstJoinFailures() {
        val stopFailure = IllegalStateException("stop")
        val decoderJoinFatal = OutOfMemoryError("decoder join")
        val actions = mutableListOf<String>()

        val outcome = performDecoderTeardown(
            requestStop = {
                actions += "stop"
                throw stopFailure
            },
            joinDecoder = {
                actions += "decoder"
                throw decoderJoinFatal
            },
            joinCallbacks = {
                actions += "callbacks"
                true
            },
        )

        assertEquals(listOf("stop", "decoder", "callbacks"), actions)
        assertFalse(outcome.fullyStopped)
        assertFalse(outcome.decoderStopped)
        assertTrue(outcome.callbacksStopped)
        assertSame(decoderJoinFatal, outcome.failure)
        assertTrue(decoderJoinFatal.suppressed.any { it === stopFailure })
    }

    @Test
    fun failedFinishedPostStillDetachesAndPreservesFatalPriority() {
        val postFailure = IllegalStateException("post")
        val detachFatal = OutOfMemoryError("detach")
        var detached = false

        val terminal = postDecoderFinishedOrDetach(
            postFinished = { throw postFailure },
            detachUiCallbacks = {
                detached = true
                throw detachFatal
            },
        )

        assertTrue(detached)
        assertSame(detachFatal, terminal)
        assertTrue(detachFatal.suppressed.any { it === postFailure })
    }

    @Test
    fun decoderCreationCleanupRunsEveryActionAndPreservesFatalPriority() {
        val original = OutOfMemoryError("decoder-create")
        val cleanupFatal = ThreadDeath()
        var joined = false
        val terminal = decoderCreationFailureAfterCleanup(
            primaryFailure = original,
            requestQuit = { throw cleanupFatal },
            joinCallbackThread = { joined = true },
        )
        assertSame(original, terminal)
        assertTrue(joined)

        val joinFatal = OutOfMemoryError("join")
        var quitAttempted = false
        val cleanupDominatesNormal = decoderCreationFailureAfterCleanup(
            primaryFailure = IllegalStateException("constructor"),
            requestQuit = {
                quitAttempted = true
                throw IllegalArgumentException("quit")
            },
            joinCallbackThread = { throw joinFatal },
        )
        assertTrue(quitAttempted)
        assertSame(joinFatal, cleanupDominatesNormal)
    }

    @Test
    fun failureMergeSeedsAnEmptyCleanupAccumulatorWithoutChangingIdentity() {
        val first = IllegalStateException("first")

        assertSame(first, mergeFailurePreservingFatal(null, first))
    }

    @Test
    fun failureMergeKeepsFirstOrdinaryFailureAndAttachesLaterEvidence() {
        val first = IllegalStateException("first")
        val later = IllegalArgumentException("later")

        val terminal = mergeFailurePreservingFatal(first, later)

        assertSame(first, terminal)
        assertTrue(terminal.suppressed.any { it === later })
    }

    @Test
    fun failureMergePromotesCleanupVmFatalAboveEarlierOrdinaryFailure() {
        val ordinary = IllegalStateException("primary")
        val fatal = OutOfMemoryError("cleanup")

        val terminal = mergeFailurePreservingFatal(ordinary, fatal)

        assertSame(fatal, terminal)
        assertTrue(terminal.suppressed.any { it === ordinary })
    }

    @Test
    fun failureMergeNeverLetsLaterFailureReplaceAnExistingVmFatal() {
        val firstFatal = ThreadDeath()
        val laterFatal = OutOfMemoryError("later")

        val terminal = mergeFailurePreservingFatal(firstFatal, laterFatal)

        assertSame(firstFatal, terminal)
        assertTrue(terminal.suppressed.any { it === laterFatal })
    }

    @Test
    fun captureReusesImmutableTokenUntilUpdateAndDisable() {
        val events = mutableListOf<Triple<Long, Long, Boolean>>()
        val relay = ProducerFrameRelay(
            producerId = 17L,
            generation = 200L,
            primary = true,
        ) { generation, producerId, primary, _ ->
            events += Triple(generation, producerId, primary)
        }

        val firstCapture = relay.captureCallback()
        assertSame(firstCapture, relay.captureCallback())

        relay.update(201L) { generation, producerId, primary, _ ->
            events += Triple(generation, producerId, primary)
        }
        val secondCapture = relay.captureCallback()
        assertNotSame(firstCapture, secondCapture)
        assertSame(secondCapture, relay.captureCallback())

        relay.disable()
        assertNull(relay.captureCallback())
        firstCapture?.invoke()
        secondCapture?.invoke()

        assertTrue(events.isEmpty())
    }

    @Test
    fun reusedProducerRebindsAndReplacedProducerDisables() {
        var first = 0
        var second = 0
        val relay = ProducerFrameRelay(
            producerId = 1L,
            generation = 1L,
            primary = true,
        ) { _, _, _, _ -> first++ }

        relay.emit()
        relay.update(generation = 2L) { _, _, _, _ -> second++ }
        relay.emit()
        relay.disable()
        relay.emit()

        assertEquals(1, first)
        assertEquals(1, second)
    }

    @Test
    fun queuedOldProducerEventCannotReachNewGenerationRelay() {
        var oldGeneration = 0
        var newGeneration = 0
        val oldRelay = ProducerFrameRelay(
            producerId = 1L,
            generation = 1L,
            primary = true,
        ) { _, _, _, _ -> oldGeneration++ }
        val newRelay = ProducerFrameRelay(
            producerId = 2L,
            generation = 2L,
            primary = true,
        ) { _, _, _, _ -> newGeneration++ }

        oldRelay.disable()
        // Models a MediaCodec callback that was queued before the producer was replaced.
        oldRelay.emit()
        newRelay.emit()

        assertEquals(0, oldGeneration)
        assertEquals(1, newGeneration)
    }

    @Test
    fun removedProducerLateTeardownCannotPoisonTheNextGeneration() {
        val failures = mutableListOf<Long>()
        val removedRelay = ProducerFrameRelay(
            producerId = 21L,
            generation = 4L,
            primary = false,
        ) { _, _, _, _ -> Unit }
        val reportRemovedFailure = {
            removedRelay.activeGenerationForFailure()?.let(failures::add)
        }

        removedRelay.disable()
        // Models a delayed SurfaceView.surfaceDestroyed callback after the stage has already
        // advanced to generation 5. A removed relay must not read or poison that mutable stage
        // generation.
        reportRemovedFailure()

        assertEquals(emptyList<Long>(), failures)

        val reusedRelay = ProducerFrameRelay(
            producerId = 22L,
            generation = 4L,
            primary = false,
        ) { _, _, _, _ -> Unit }
        reusedRelay.update(5L) { _, _, _, _ -> Unit }
        reusedRelay.activeGenerationForFailure()?.let(failures::add)

        assertEquals(listOf(5L), failures)
    }

    @Test
    fun queuedRuntimeFailureIsDroppedAfterDisableAndSameGenerationRebind() {
        val failures = mutableListOf<Long>()
        val relay = ProducerFrameRelay(
            producerId = 31L,
            generation = 9L,
            primary = false,
        ) { _, _, _, _ -> Unit }

        // The worker captures this identity before posting its runtime failure to main.
        val staleDispatch = checkNotNull(relay.captureFailureDispatch())
        relay.disable()
        // An in-phase topology update can reuse the same generation. Comparing only the number
        // would let the removed producer abort this replacement.
        relay.update(9L) { _, _, _, _ -> Unit }
        val currentDispatch = checkNotNull(relay.captureFailureDispatch())

        if (relay.isFailureDispatchCurrent(staleDispatch)) {
            failures += staleDispatch.generation
        }
        if (relay.isFailureDispatchCurrent(currentDispatch)) {
            failures += currentDispatch.generation
        }

        assertFalse(relay.isFailureDispatchCurrent(staleDispatch))
        assertTrue(relay.isFailureDispatchCurrent(currentDispatch))
        assertEquals(listOf(9L), failures)
    }

    @Test
    fun closedDecoderFrameGateDropsCallbacksQueuedBeforeTeardown() {
        val gate = DecoderFrameCallbackGate()

        assertTrue(gate.isOpen())
        gate.close()
        assertFalse(gate.isOpen())
    }

    @Test
    fun callbackQuitRequestedBeforeLooperPublicationAppliesExactlyOnce() {
        val quitTargets = mutableListOf<Any>()
        val handshake = DeferredQuitHandshake<Any>(quitTargets::add)
        val target = Any()

        handshake.request()
        assertNull(handshake.current())
        assertTrue(quitTargets.isEmpty())

        handshake.publish(target)
        handshake.request()

        assertSame(target, handshake.current())
        assertEquals(listOf(target), quitTargets)
    }

    @Test
    fun callbackQuitRequestedAfterLooperPublicationAppliesExactlyOnce() {
        val quitTargets = mutableListOf<Any>()
        val handshake = DeferredQuitHandshake<Any>(quitTargets::add)
        val target = Any()

        handshake.publish(target)
        assertSame(target, handshake.current())
        assertTrue(quitTargets.isEmpty())

        handshake.request()
        handshake.request()

        assertEquals(listOf(target), quitTargets)
    }
}
