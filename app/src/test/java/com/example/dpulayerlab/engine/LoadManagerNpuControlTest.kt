package com.example.dpulayerlab.engine

import com.example.dpulayerlab.model.LoadSetpoints
import com.example.dpulayerlab.model.LoadShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class LoadManagerNpuControlTest {
    @Test
    fun phaseBoundaryApplyWaitsForExactPositiveCommandAcknowledgment() {
        val adapter = TrackedNpuAdapter()
        val manager = LoadManager(adapter)
        try {
            val health = manager.applyAndConfirmNpu(
                newSetpoints = LoadSetpoints(npu = 0.6f),
                timeoutMs = 321L,
            )

            assertEquals(NpuControlState.APPLIED, health.state)
            assertTrue(health.applied)
            assertEquals(321L, adapter.lastAwaitTimeoutMs.get())
            assertEquals(
                NpuControlState.APPLIED,
                manager.npuControlHealth().state,
            )
        } finally {
            manager.closeWithResult()
        }
    }

    @Test
    fun priorAcknowledgmentCannotHideNewestPendingSetpoint() {
        val adapter = TrackedNpuAdapter(autoAcknowledge = false)
        val manager = LoadManager(adapter)
        try {
            manager.apply(LoadSetpoints(npu = 0.2f))
            val first = checkNotNull(adapter.latestRequest.get())
            adapter.setHealth(first, NpuControlState.APPLIED)

            manager.apply(LoadSetpoints(npu = 0.8f))
            val second = checkNotNull(adapter.latestRequest.get())
            assertFalse(first === second)
            adapter.setHealth(first, NpuControlState.APPLIED)

            assertEquals(
                NpuControlState.PENDING,
                manager.npuControlHealth().state,
            )
        } finally {
            manager.closeWithResult()
        }
    }

    @Test
    fun positiveBackendWithoutAcknowledgmentContractFailsClosed() {
        val manager = LoadManager(LegacyNpuAdapter)
        try {
            val health = manager.applyAndConfirmNpu(
                newSetpoints = LoadSetpoints(npu = 0.5f),
            )

            assertEquals(NpuControlState.FAILED, health.state)
            assertFalse(health.applied)
            assertEquals(
                NpuControlState.FAILED,
                manager.npuControlHealth().state,
            )
        } finally {
            manager.closeWithResult()
        }
    }

    @Test
    fun zeroSetpointNeedsNoPositiveApplyAcknowledgment() {
        val adapter = TrackedNpuAdapter()
        val manager = LoadManager(adapter)
        try {
            val health = manager.applyAndConfirmNpu(LoadSetpoints(npu = 0f))

            assertEquals(NpuControlState.IDLE, health.state)
            assertTrue(health.applied)
            assertEquals(NpuControlState.IDLE, manager.npuControlHealth().state)
        } finally {
            manager.closeWithResult()
        }
    }

    private class TrackedNpuAdapter(
        private val autoAcknowledge: Boolean = true,
    ) : NpuWorkloadAdapter {
        private val version = AtomicLong(0L)
        private val healthByRequest =
            ConcurrentHashMap<TestNpuControlRequest, NpuControlHealth>()
        val latestRequest = AtomicReference<TestNpuControlRequest?>()
        val lastAwaitTimeoutMs = AtomicLong(-1L)

        override fun isAvailable(): Boolean = true

        override fun setIntensity(intensity: Float) = Unit

        override fun requestLoad(
            intensity: Float,
            shape: LoadShape,
            restartProfile: Boolean,
        ): NpuControlRequest {
            val request = TestNpuControlRequest(version.incrementAndGet())
            latestRequest.set(request)
            healthByRequest[request] = NpuControlHealth(
                state = if (autoAcknowledge) {
                    NpuControlState.APPLIED
                } else {
                    NpuControlState.PENDING
                },
                detail = "test",
            )
            return request
        }

        override fun awaitControl(
            request: NpuControlRequest,
            timeoutMs: Long,
        ): NpuControlHealth {
            lastAwaitTimeoutMs.set(timeoutMs)
            val tracked = request as? TestNpuControlRequest
                ?: return NpuControlHealth.failed("wrong test request type")
            return healthByRequest[tracked] ?: NpuControlHealth.failed("missing test request")
        }

        override fun controlHealth(
            request: NpuControlRequest,
            pendingTimeoutMs: Long,
        ): NpuControlHealth {
            val tracked = request as? TestNpuControlRequest
                ?: return NpuControlHealth.failed("wrong test request type")
            return healthByRequest[tracked] ?: NpuControlHealth.failed("missing test request")
        }

        fun setHealth(request: TestNpuControlRequest, state: NpuControlState) {
            healthByRequest[request] = NpuControlHealth(state, "test")
        }

        override fun releaseAndConfirm(): Boolean = true

        override fun status(): String = "test"

        override fun closeWithResult(): NpuShutdownResult = NpuShutdownResult(
            releaseConfirmed = true,
            backendCloseConfirmed = true,
            mayRemainActive = false,
            detail = "test",
        )
    }

    private data class TestNpuControlRequest(
        val version: Long,
    ) : NpuControlRequest

    private object LegacyNpuAdapter : NpuWorkloadAdapter {
        override fun isAvailable(): Boolean = true

        override fun setIntensity(intensity: Float) = Unit

        override fun releaseAndConfirm(): Boolean = true

        override fun status(): String = "legacy"

        override fun closeWithResult(): NpuShutdownResult = NpuShutdownResult(
            releaseConfirmed = true,
            backendCloseConfirmed = true,
            mayRemainActive = false,
            detail = "legacy",
        )
    }
}
