package com.example.dpulayerlab.engine

import com.example.dpulayerlab.model.BufferSize
import com.example.dpulayerlab.model.LayerBackend
import com.example.dpulayerlab.model.LoadSetpoints
import com.example.dpulayerlab.model.MotionProfile
import com.example.dpulayerlab.model.PhaseSpec
import com.example.dpulayerlab.model.PixelRoute
import com.example.dpulayerlab.model.RiskLevel
import com.example.dpulayerlab.model.ScenarioCategory
import com.example.dpulayerlab.model.ScenarioSafetyPolicy
import com.example.dpulayerlab.model.ScenarioSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceRenderSafetyTest {
    @Test
    fun originalBatterySaverStateCannotBeHiddenByTemporarySuppression() {
        assertTrue(
            effectivePowerSaveConstraint(
                currentPowerSaveMode = false,
                originalPowerSaveMode = true,
            ),
        )
        assertTrue(
            effectivePowerSaveConstraint(
                currentPowerSaveMode = true,
                originalPowerSaveMode = false,
            ),
        )
        assertFalse(
            effectivePowerSaveConstraint(
                currentPowerSaveMode = false,
                originalPowerSaveMode = false,
            ),
        )
    }

    @Test
    fun twoGiBEmulatorIsCappedEvenWhenAndroidDoesNotMarkItLowRam() {
        val limits = limits(
            totalRamBytes = 2L * GIB,
            availableRamBytes = 1536L * MIB,
            cpuCoreCount = 8,
            isEmulator = true,
        )

        assertEquals(4, limits.maxLayers)
        assertEquals(60f, limits.maxProducerFps, 0f)
        assertEquals(0.40f, limits.maxCpuLoad, 0f)
        assertEquals(0.25f, limits.maxMemoryLoad, 0f)
        assertEquals(0.35f, limits.maxGpuLoad, 0f)
        assertEquals(0.40f, limits.maxNpuLoad, 0f)
        assertEquals(128L * MIB, limits.maxGraphicsBytes)
    }

    @Test
    fun twoGiBPhysicalDeviceUsesTheLowTierEnvelope() {
        val limits = limits(
            totalRamBytes = 2L * GIB,
            availableRamBytes = 1536L * MIB,
            cpuCoreCount = 8,
        )

        assertEquals(6, limits.maxLayers)
        assertEquals(60f, limits.maxProducerFps, 0f)
        assertEquals(0.50f, limits.maxCpuLoad, 0f)
        assertEquals(0.35f, limits.maxMemoryLoad, 0f)
        assertEquals(0.50f, limits.maxGpuLoad, 0f)
        assertEquals(0.50f, limits.maxNpuLoad, 0f)
        assertEquals((2L * GIB) / 12L, limits.maxGraphicsBytes)
    }

    @Test
    fun fourGiBPhysicalDeviceUsesTheMidTierEnvelope() {
        val limits = limits(
            totalRamBytes = 4L * GIB,
            availableRamBytes = 3L * GIB,
            cpuCoreCount = 8,
        )

        assertEquals(12, limits.maxLayers)
        assertEquals(90f, limits.maxProducerFps, 0f)
        assertEquals(0.75f, limits.maxCpuLoad, 0f)
        assertEquals(0.65f, limits.maxMemoryLoad, 0f)
        assertEquals(0.75f, limits.maxGpuLoad, 0f)
        assertEquals(0.75f, limits.maxNpuLoad, 0f)
    }

    @Test
    fun eightGiBPhysicalDeviceRetainsTheAbsoluteFlagshipCaps() {
        val limits = limits(
            totalRamBytes = 8L * GIB,
            availableRamBytes = 6L * GIB,
            cpuCoreCount = 8,
        )

        assertEquals(20, limits.maxLayers)
        assertEquals(120f, limits.maxProducerFps, 0f)
        assertEquals(1f, limits.maxCpuLoad, 0f)
        assertEquals(1f, limits.maxMemoryLoad, 0f)
        assertEquals(1f, limits.maxGpuLoad, 0f)
        assertEquals(1f, limits.maxNpuLoad, 0f)
        assertEquals(768L * MIB, limits.maxGraphicsBytes)
    }

    @Test
    fun exactlyThreeGiBCrossesTheStrictlyLessThanThreeGiBLowTierBoundary() {
        val justBelow = limits(
            totalRamBytes = 3L * GIB - 1L,
            availableRamBytes = 2L * GIB,
            cpuCoreCount = 8,
        )
        val exactlyThree = limits(
            totalRamBytes = 3L * GIB,
            availableRamBytes = 2L * GIB,
            cpuCoreCount = 8,
        )

        assertEquals(6, justBelow.maxLayers)
        assertEquals(60f, justBelow.maxProducerFps, 0f)
        assertEquals(12, exactlyThree.maxLayers)
        assertEquals(90f, exactlyThree.maxProducerFps, 0f)
    }

    @Test
    fun cpuCoreCountCapsAnOtherwiseFlagshipMemoryConfiguration() {
        val limits = limits(
            totalRamBytes = 8L * GIB,
            availableRamBytes = 6L * GIB,
            cpuCoreCount = 4,
        )

        assertEquals(6, limits.maxLayers)
        assertEquals(60f, limits.maxProducerFps, 0f)
        assertEquals(0.50f, limits.maxCpuLoad, 0f)
        assertEquals(0.35f, limits.maxMemoryLoad, 0f)
    }

    @Test
    fun androidLowRamFlagCapsAnOtherwiseFlagshipMemoryConfiguration() {
        val limits = limits(
            totalRamBytes = 8L * GIB,
            availableRamBytes = 6L * GIB,
            cpuCoreCount = 8,
            isLowRamDevice = true,
        )

        assertEquals(6, limits.maxLayers)
        assertEquals(60f, limits.maxProducerFps, 0f)
        assertEquals(0.50f, limits.maxCpuLoad, 0f)
        assertEquals(0.35f, limits.maxMemoryLoad, 0f)
    }

    @Test
    fun powerSaverAppliesAnIndependentCap() {
        val limits = limits(
            totalRamBytes = 8L * GIB,
            availableRamBytes = 6L * GIB,
            cpuCoreCount = 8,
            isPowerSaveMode = true,
        )

        assertEquals(8, limits.maxLayers)
        assertEquals(60f, limits.maxProducerFps, 0f)
        assertEquals(0.65f, limits.maxCpuLoad, 0f)
        assertEquals(0.50f, limits.maxMemoryLoad, 0f)
        assertEquals(0.65f, limits.maxGpuLoad, 0f)
        assertEquals(0.65f, limits.maxNpuLoad, 0f)
        assertTrue(limits.powerSaveMode)
    }

    @Test
    fun lowMemoryAppliesTheFailSafeEnvelopeAndAvailableMemoryBudget() {
        val limits = limits(
            totalRamBytes = 8L * GIB,
            availableRamBytes = 512L * MIB,
            cpuCoreCount = 8,
            isLowMemory = true,
        )

        assertEquals(2, limits.maxLayers)
        assertEquals(30f, limits.maxProducerFps, 0f)
        assertEquals(0.25f, limits.maxCpuLoad, 0f)
        assertEquals(0f, limits.maxMemoryLoad, 0f)
        assertEquals(0.25f, limits.maxGpuLoad, 0f)
        assertEquals(0.25f, limits.maxNpuLoad, 0f)
        assertEquals(64L * MIB, limits.maxGraphicsBytes)
    }

    @Test
    fun invalidInputsFailClosedToAUsableMinimalEnvelope() {
        val limits = DeviceRenderSafety.limitsFor(
            displayWidthPx = 0,
            displayHeightPx = Int.MIN_VALUE,
            inputs = DeviceRenderSafetyInputs(
                totalRamBytes = -1L,
                availableRamBytes = Long.MAX_VALUE,
                cpuCoreCount = 0,
                isLowRamDevice = false,
                isLowMemory = false,
                isPowerSaveMode = false,
                isEmulator = false,
            ),
        )

        assertEquals(1, limits.displayWidthPx)
        assertEquals(1, limits.displayHeightPx)
        assertEquals(4, limits.maxLayers)
        assertEquals(45f, limits.maxProducerFps, 0f)
        assertEquals(0.35f, limits.maxCpuLoad, 0f)
        assertEquals(0.25f, limits.maxMemoryLoad, 0f)
        assertEquals(1L * MIB, limits.maxGraphicsBytes)
    }

    @Test
    fun goldfishAndRanchuBuildsAreRecognizedWithoutGenericFingerprint() {
        assertTrue(
            DeviceRenderSafety.isProbablyEmulator(
                identity(hardware = "goldfish"),
            ),
        )
        assertTrue(
            DeviceRenderSafety.isProbablyEmulator(
                identity(hardware = "ranchu"),
            ),
        )
        assertFalse(
            DeviceRenderSafety.isProbablyEmulator(
                identity(
                    fingerprint = "vendor/device/device:16/release-keys",
                    model = "Physical Device",
                    manufacturer = "Vendor",
                    brand = "vendor",
                    device = "device",
                    product = "device_global",
                    hardware = "vendor-soc",
                ),
            ),
        )
    }

    @Test
    fun deviceEnvelopeClampsOnlyTheEffectiveScenarioAndRecordsAdjustments() {
        val originalPhase = PhaseSpec(
            id = "stress",
            label = "Stress",
            durationMs = 1_000L,
            activeLayers = 10,
            producerFps = 120f,
            requestedDisplayHz = 120f,
            backend = LayerBackend.INDEPENDENT_SURFACES,
            pixelRoute = PixelRoute.RGB_8888,
            bufferSize = BufferSize.DISPLAY,
            motion = MotionProfile.TRANSFORM_STORM,
            workloads = LoadSetpoints(cpu = 1f, memory = 1f, gpu = 1f, npu = 1f),
            includeGlLayer = true,
        )
        val scenario = ScenarioSpec(
            id = "stress-scenario",
            name = "Stress scenario",
            description = "Verifies device safety clamping",
            category = ScenarioCategory.MIXED,
            risk = RiskLevel.HIGH,
            tags = setOf("test"),
            phases = listOf(originalPhase),
        )

        val decision = ScenarioSafetyPolicy.evaluate(
            scenario = scenario,
            limits = limits(
                totalRamBytes = 2L * GIB,
                availableRamBytes = 1536L * MIB,
                cpuCoreCount = 8,
                isEmulator = true,
            ),
        )
        val effective = checkNotNull(decision.effectiveScenario).phases.single()

        assertEquals(10, scenario.phases.single().activeLayers)
        assertEquals(120f, scenario.phases.single().producerFps, 0f)
        // The GL tail reserves both RGBA color and depth triple buffers, so the graphics budget
        // is stricter than the raw device layer envelope.
        assertEquals(3, effective.activeLayers)
        assertEquals(60f, effective.producerFps, 0f)
        assertEquals(0.40f, effective.workloads.cpu, 0f)
        assertEquals(0.25f, effective.workloads.memory, 0f)
        assertEquals(0.35f, effective.workloads.gpu, 0f)
        assertEquals(0.40f, effective.workloads.npu, 0f)
        assertTrue(decision.adjustments.any { it.contains("layers 10 -> 3") })
        assertTrue(decision.adjustments.any { it.contains("producer FPS 120.0 -> 60.0") })
        assertTrue(decision.adjustments.any { it.contains("workloads capped") })
    }

    private fun limits(
        totalRamBytes: Long,
        availableRamBytes: Long,
        cpuCoreCount: Int,
        isLowRamDevice: Boolean = false,
        isLowMemory: Boolean = false,
        isPowerSaveMode: Boolean = false,
        isEmulator: Boolean = false,
    ) = DeviceRenderSafety.limitsFor(
        displayWidthPx = 1080,
        displayHeightPx = 2400,
        inputs = DeviceRenderSafetyInputs(
            totalRamBytes = totalRamBytes,
            availableRamBytes = availableRamBytes,
            cpuCoreCount = cpuCoreCount,
            isLowRamDevice = isLowRamDevice,
            isLowMemory = isLowMemory,
            isPowerSaveMode = isPowerSaveMode,
            isEmulator = isEmulator,
        ),
    )

    private fun identity(
        fingerprint: String = "vendor/device/device:16/release-keys",
        model: String = "Physical Device",
        manufacturer: String = "Vendor",
        brand: String = "vendor",
        device: String = "device",
        product: String = "device_global",
        hardware: String,
    ) = DeviceBuildIdentity(
        fingerprint = fingerprint,
        model = model,
        manufacturer = manufacturer,
        brand = brand,
        device = device,
        product = product,
        hardware = hardware,
    )

    private companion object {
        const val MIB = 1024L * 1024L
        const val GIB = 1024L * MIB
    }
}
