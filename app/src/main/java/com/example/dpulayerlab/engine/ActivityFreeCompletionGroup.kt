package com.example.dpulayerlab.engine

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Terminal state of an [ActivityFreeCompletionGroup].
 *
 * Barrier completion and operation success are deliberately separate. A failed operation still
 * opens the barrier after it actually exits, allowing backend cleanup to run and report a sticky
 * failure instead of leaking the backend while waiting for an impossible success.
 */
internal data class ActivityFreeCompletionOutcome(
    val successful: Boolean,
    val failureReason: String? = null,
)

internal data class ActivityFreeCompletionGroupSnapshot(
    val sealed: Boolean,
    val terminal: Boolean,
    val pendingStarts: Int,
    val registeredStarts: Long,
    val completedStarts: Long,
    val successful: Boolean,
    val failureReason: String? = null,
)

/**
 * Activity/coroutine-independent completion ticket.
 *
 * A Job or worker may retain this ticket in its completion callback. The group never retains that
 * Job, worker, callback, Activity, or Controller; it stores only bounded counters and the first
 * bounded failure reason.
 */
internal class ActivityFreeCompletionRegistration internal constructor(
    private val owner: ActivityFreeCompletionGroup,
    val id: Long,
) {
    private val completed = AtomicBoolean(false)

    fun complete(): Boolean = complete(successful = true)

    fun fail(reason: String): Boolean =
        complete(
            successful = false,
            failureReason = reason,
        )

    fun complete(
        successful: Boolean,
        failureReason: String = "",
    ): Boolean {
        if (!completed.compareAndSet(false, true)) return false
        owner.completeRegistration(
            successful = successful,
            failureReason = failureReason,
        )
        return true
    }
}

/**
 * Separates construction/start commit from actual operation completion.
 *
 * A LAZY Job may complete or be cancelled while a second sibling Job is still being constructed.
 * The registration is therefore published only after both facts are known: setup was committed
 * (or failed) and the owned operation actually reached its completion callback.
 */
internal class TransactionalCompletionRegistration(
    private val registration: ActivityFreeCompletionRegistration,
) {
    private val setupState = AtomicReference<TransactionalCompletionState>(
        TransactionalCompletionState.Pending,
    )
    private val operationCompleted = AtomicBoolean(false)
    private val operationFailure = AtomicReference<String?>(null)
    private val registrationPublished = AtomicBoolean(false)

    fun commit(): Boolean {
        if (
            !setupState.compareAndSet(
                TransactionalCompletionState.Pending,
                TransactionalCompletionState.Committed,
            )
        ) {
            return false
        }
        publishIfReady()
        return true
    }

    fun fail(reason: String): Boolean {
        if (
            !setupState.compareAndSet(
                TransactionalCompletionState.Pending,
                TransactionalCompletionState.Failed(reason),
            )
        ) {
            return false
        }
        publishIfReady()
        return true
    }

    fun completeOperation(failureReason: String? = null): Boolean {
        failureReason?.let { operationFailure.compareAndSet(null, it) }
        val changed = operationCompleted.compareAndSet(false, true)
        publishIfReady()
        return changed
    }

    private fun publishIfReady() {
        if (!operationCompleted.get()) return
        val state = setupState.get()
        if (state is TransactionalCompletionState.Pending) return
        if (!registrationPublished.compareAndSet(false, true)) return
        when (state) {
            TransactionalCompletionState.Pending -> Unit
            TransactionalCompletionState.Committed -> {
                val failureReason = operationFailure.get()
                if (failureReason == null) {
                    registration.complete()
                } else {
                    registration.fail(failureReason)
                }
            }
            is TransactionalCompletionState.Failed -> registration.fail(state.reason)
        }
    }
}

private sealed interface TransactionalCompletionState {
    data object Pending : TransactionalCompletionState
    data object Committed : TransactionalCompletionState
    data class Failed(val reason: String) : TransactionalCompletionState
}

/**
 * Ref-counted lifecycle barrier which remains valid after a caller drops its Job reference.
 *
 * Register each operation before starting it, attach the returned ticket to the operation's real
 * completion callback, then [seal] when no further starts are allowed. The returned barrier opens
 * only after sealing and after every accepted start has completed. At most
 * [maxPendingRegistrations] starts may be concurrently outstanding, keeping misuse bounded without
 * penalizing a long-lived controller whose earlier generations have already completed.
 */
internal class ActivityFreeCompletionGroup(
    private val maxPendingRegistrations: Int = DEFAULT_MAX_PENDING_REGISTRATIONS,
) {
    private val lock = Any()
    private val terminalBarrier = ControllerBackendCompletionBarrier()
    private var sealed = false
    private var terminal = false
    private var pendingStarts = 0
    private var registeredStarts = 0L
    private var completedStarts = 0L
    private var nextRegistrationId = 0L
    private var failureReason: String? = null

    init {
        require(maxPendingRegistrations in 1..MAX_PENDING_REGISTRATIONS) {
            "maxPendingRegistrations must be in 1..$MAX_PENDING_REGISTRATIONS"
        }
    }

    /**
     * Registers one operation before it starts.
     *
     * Returns `null` after [seal], or when the concurrent pending limit is reached. Hitting the
     * pending limit records a failed outcome; callers must not start an unregistered operation.
     */
    fun registerStart(): ActivityFreeCompletionRegistration? = synchronized(lock) {
        if (sealed) return@synchronized null
        if (pendingStarts >= maxPendingRegistrations) {
            recordFailureLocked(
                "completion registration limit exceeded ($maxPendingRegistrations pending)",
            )
            return@synchronized null
        }
        nextRegistrationId = nextRegistrationId(nextRegistrationId)
        pendingStarts += 1
        registeredStarts = saturatingIncrement(registeredStarts)
        ActivityFreeCompletionRegistration(
            owner = this,
            id = nextRegistrationId,
        )
    }

    /**
     * Prevents new registrations and returns the stable terminal barrier.
     *
     * Sealing is idempotent. With no pending operation, the barrier opens before this method
     * returns.
     */
    fun seal(): ControllerBackendCompletionBarrier = synchronized(lock) {
        sealed = true
        if (markTerminalIfReadyLocked()) {
            // CountDownLatch signaling is non-blocking. Publish it while holding the state lock so
            // outcome()/snapshot() can never observe terminal=true before the barrier is open.
            terminalBarrier.complete()
        }
        terminalBarrier
    }

    /**
     * Returns a success/failure result only after the terminal barrier has opened.
     */
    fun outcome(): ActivityFreeCompletionOutcome? = synchronized(lock) {
        if (!terminal) return@synchronized null
        ActivityFreeCompletionOutcome(
            successful = failureReason == null,
            failureReason = failureReason,
        )
    }

    fun snapshot(): ActivityFreeCompletionGroupSnapshot = synchronized(lock) {
        ActivityFreeCompletionGroupSnapshot(
            sealed = sealed,
            terminal = terminal,
            pendingStarts = pendingStarts,
            registeredStarts = registeredStarts,
            completedStarts = completedStarts,
            successful = failureReason == null,
            failureReason = failureReason,
        )
    }

    internal fun completeRegistration(
        successful: Boolean,
        failureReason: String,
    ) = synchronized(lock) {
        check(pendingStarts > 0) {
            "completion registration accounting underflow"
        }
        pendingStarts -= 1
        completedStarts = saturatingIncrement(completedStarts)
        if (!successful) {
            recordFailureLocked(failureReason)
        }
        if (markTerminalIfReadyLocked()) {
            terminalBarrier.complete()
        }
    }

    /**
     * Returns true exactly once for the transition that must signal [terminalBarrier].
     */
    private fun markTerminalIfReadyLocked(): Boolean {
        if (terminal || !sealed || pendingStarts != 0) return false
        terminal = true
        return true
    }

    private fun recordFailureLocked(reason: String) {
        if (failureReason != null) return
        failureReason = boundedFailureReason(reason)
    }

    private companion object {
        const val DEFAULT_MAX_PENDING_REGISTRATIONS = 16
        const val MAX_PENDING_REGISTRATIONS = 1_024
        const val MAX_FAILURE_REASON_CHARS = 240

        fun nextRegistrationId(current: Long): Long =
            if (current == Long.MAX_VALUE) 1L else current + 1L

        fun saturatingIncrement(value: Long): Long =
            if (value == Long.MAX_VALUE) Long.MAX_VALUE else value + 1L

        fun boundedFailureReason(reason: String): String =
            reason
                .trim()
                .ifBlank { "activity-free operation failed" }
                .take(MAX_FAILURE_REASON_CHARS)
    }
}
