package com.example.dpulayerlab.engine

import android.app.Activity
import android.app.ActivityManager
import android.os.Build
import android.os.PowerManager
import com.example.dpulayerlab.model.RenderSafetyLimits
import com.example.dpulayerlab.model.ScenarioSafetyPolicy
import com.example.dpulayerlab.util.currentDisplayCompat
import java.util.Locale
import kotlin.math.min

internal data class DeviceRenderSafetyInputs(
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val cpuCoreCount: Int,
    val isLowRamDevice: Boolean,
    val isLowMemory: Boolean,
    val isPowerSaveMode: Boolean,
    val isEmulator: Boolean,
)

internal data class DeviceBuildIdentity(
    val fingerprint: String,
    val model: String,
    val manufacturer: String,
    val brand: String,
    val device: String,
    val product: String,
    val hardware: String,
)

/**
 * Builds a conservative safety envelope from memory pressure and device characteristics.
 *
 * This is not a performance score. It is a hard guard against pathological BufferQueue residency,
 * runaway producer rates, and cross-load values that are unreasonable for the current device.
 */
object DeviceRenderSafety {
    private const val MIB = 1024L * 1024L
    private const val GIB = 1024L * MIB
    private const val MIN_CREDIBLE_TOTAL_RAM = 256L * MIB
    private const val MAX_CREDIBLE_TOTAL_RAM = 1024L * GIB
    private const val INVALID_TOTAL_RAM_FALLBACK = 1L * GIB
    private const val MIN_GRAPHICS_BUDGET = 1L * MIB

    private data class EnvelopeCap(
        val maxLayers: Int,
        val maxProducerFps: Float,
        val maxCpuLoad: Float,
        val maxMemoryLoad: Float,
        val maxGpuLoad: Float,
        val maxNpuLoad: Float,
    ) {
        fun cappedBy(other: EnvelopeCap) = EnvelopeCap(
            maxLayers = minOf(maxLayers, other.maxLayers),
            maxProducerFps = minOf(maxProducerFps, other.maxProducerFps),
            maxCpuLoad = minOf(maxCpuLoad, other.maxCpuLoad),
            maxMemoryLoad = minOf(maxMemoryLoad, other.maxMemoryLoad),
            maxGpuLoad = minOf(maxGpuLoad, other.maxGpuLoad),
            maxNpuLoad = minOf(maxNpuLoad, other.maxNpuLoad),
        )
    }

    private data class SanitizedMemory(
        val totalBytes: Long,
        val availableBytes: Long,
    )

    fun detect(activity: Activity): RenderSafetyLimits {
        val activityManager = activity.getSystemService(ActivityManager::class.java)
        val powerManager = activity.getSystemService(PowerManager::class.java)
        val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val heapFallback = activityManager.memoryClass.toLong() * MIB
        val totalRam = memory.totalMem.takeIf { it > 0L } ?: heapFallback * 4L
        val availableRam = memory.availMem.takeIf { it > 0L } ?: heapFallback
        val lowRam = activityManager.isLowRamDevice
        val constrainedPower = powerManager.isPowerSaveMode
        val lowMemory = memory.lowMemory

        val displayMode = activity.currentDisplayCompat()?.mode
        val displayWidth = displayMode?.physicalWidth
            ?.takeIf { it > 0 }
            ?: activity.resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val displayHeight = displayMode?.physicalHeight
            ?.takeIf { it > 0 }
            ?: activity.resources.displayMetrics.heightPixels.coerceAtLeast(1)

        return limitsFor(
            displayWidthPx = displayWidth,
            displayHeightPx = displayHeight,
            inputs = DeviceRenderSafetyInputs(
                totalRamBytes = totalRam,
                availableRamBytes = availableRam,
                cpuCoreCount = Runtime.getRuntime().availableProcessors(),
                isLowRamDevice = lowRam,
                isLowMemory = lowMemory,
                isPowerSaveMode = constrainedPower,
                isEmulator = isProbablyEmulator(
                    DeviceBuildIdentity(
                        fingerprint = Build.FINGERPRINT,
                        model = Build.MODEL,
                        manufacturer = Build.MANUFACTURER,
                        brand = Build.BRAND,
                        device = Build.DEVICE,
                        product = Build.PRODUCT,
                        hardware = Build.HARDWARE,
                    ),
                ),
            ),
        )
    }

    /**
     * Pure device-envelope calculation used by both Android detection and host-side boundary tests.
     *
     * Each signal contributes an independent cap. Combining caps with minima prevents a large RAM
     * value from hiding a small CPU topology, emulator graphics stack, power saving, or memory
     * pressure. Catalog scenarios are left untouched; [ScenarioSafetyPolicy] applies these limits
     * to an effective copy and records the resulting safety adjustments.
     */
    internal fun limitsFor(
        displayWidthPx: Int,
        displayHeightPx: Int,
        inputs: DeviceRenderSafetyInputs,
    ): RenderSafetyLimits {
        val memory = sanitizeMemory(inputs)
        val coreCount = inputs.cpuCoreCount.takeIf { it in 1..256 } ?: 1

        var cap = when {
            memory.totalBytes < 3L * GIB -> EnvelopeCap(
                maxLayers = 6,
                maxProducerFps = 60f,
                maxCpuLoad = 0.50f,
                maxMemoryLoad = 0.35f,
                maxGpuLoad = 0.50f,
                maxNpuLoad = 0.50f,
            )

            memory.totalBytes < 6L * GIB -> EnvelopeCap(
                maxLayers = 12,
                maxProducerFps = 90f,
                maxCpuLoad = 0.75f,
                maxMemoryLoad = 0.65f,
                maxGpuLoad = 0.75f,
                maxNpuLoad = 0.75f,
            )

            memory.totalBytes < 8L * GIB -> EnvelopeCap(
                maxLayers = 16,
                maxProducerFps = 120f,
                maxCpuLoad = 0.90f,
                maxMemoryLoad = 0.80f,
                maxGpuLoad = 0.90f,
                maxNpuLoad = 0.90f,
            )

            else -> EnvelopeCap(
                maxLayers = 20,
                maxProducerFps = 120f,
                maxCpuLoad = 1f,
                maxMemoryLoad = 1f,
                maxGpuLoad = 1f,
                maxNpuLoad = 1f,
            )
        }

        cap = cap.cappedBy(
            when {
                coreCount <= 2 -> EnvelopeCap(
                    maxLayers = 4,
                    maxProducerFps = 45f,
                    maxCpuLoad = 0.35f,
                    maxMemoryLoad = 0.25f,
                    maxGpuLoad = 0.35f,
                    maxNpuLoad = 0.35f,
                )

                coreCount <= 4 -> EnvelopeCap(
                    maxLayers = 6,
                    maxProducerFps = 60f,
                    maxCpuLoad = 0.50f,
                    maxMemoryLoad = 0.35f,
                    maxGpuLoad = 0.50f,
                    maxNpuLoad = 0.50f,
                )

                coreCount <= 6 -> EnvelopeCap(
                    maxLayers = 12,
                    maxProducerFps = 90f,
                    maxCpuLoad = 0.75f,
                    maxMemoryLoad = 0.65f,
                    maxGpuLoad = 0.75f,
                    maxNpuLoad = 0.75f,
                )

                else -> cap
            },
        )

        if (inputs.isLowRamDevice) {
            cap = cap.cappedBy(
                EnvelopeCap(
                    maxLayers = 6,
                    maxProducerFps = 60f,
                    maxCpuLoad = 0.50f,
                    maxMemoryLoad = 0.35f,
                    maxGpuLoad = 0.50f,
                    maxNpuLoad = 0.50f,
                ),
            )
        }
        if (inputs.isEmulator) {
            cap = cap.cappedBy(
                EnvelopeCap(
                    maxLayers = 4,
                    maxProducerFps = 60f,
                    maxCpuLoad = 0.40f,
                    maxMemoryLoad = 0.25f,
                    maxGpuLoad = 0.35f,
                    maxNpuLoad = 0.40f,
                ),
            )
        }
        if (inputs.isPowerSaveMode) {
            cap = cap.cappedBy(
                EnvelopeCap(
                    maxLayers = 8,
                    maxProducerFps = 60f,
                    maxCpuLoad = 0.65f,
                    maxMemoryLoad = 0.50f,
                    maxGpuLoad = 0.65f,
                    maxNpuLoad = 0.65f,
                ),
            )
        }
        if (inputs.isLowMemory) {
            cap = cap.cappedBy(
                EnvelopeCap(
                    maxLayers = 2,
                    maxProducerFps = 30f,
                    maxCpuLoad = 0.25f,
                    maxMemoryLoad = 0f,
                    maxGpuLoad = 0.25f,
                    maxNpuLoad = 0.25f,
                ),
            )
        }

        val staticGraphicsBudget = when {
            inputs.isEmulator ->
                (memory.totalBytes / 16L).coerceIn(48L * MIB, 128L * MIB)
            inputs.isLowRamDevice || memory.totalBytes < 3L * GIB ->
                (memory.totalBytes / 12L).coerceIn(64L * MIB, 192L * MIB)
            memory.totalBytes < 6L * GIB ->
                (memory.totalBytes / 9L).coerceIn(256L * MIB, 512L * MIB)
            memory.totalBytes < 8L * GIB ->
                (memory.totalBytes / 8L).coerceIn(384L * MIB, 640L * MIB)
            else ->
                (memory.totalBytes / 7L).coerceIn(256L * MIB, 768L * MIB)
        }
        // Do not impose a large floor here. Under real memory pressure even one 4K/8K
        // triple-buffered producer must be rejected instead of treating a tier floor as available.
        val pressureDivisor = when {
            inputs.isLowMemory -> 8L
            inputs.isPowerSaveMode -> 4L
            else -> 3L
        }
        val pressureAwareBudget =
            (memory.availableBytes / pressureDivisor).coerceAtLeast(MIN_GRAPHICS_BUDGET)
        val graphicsBudget = min(staticGraphicsBudget, pressureAwareBudget)

        return RenderSafetyLimits(
            displayWidthPx = displayWidthPx.coerceAtLeast(1),
            displayHeightPx = displayHeightPx.coerceAtLeast(1),
            maxLayers = cap.maxLayers,
            maxProducerFps = cap.maxProducerFps,
            maxPhaseDurationMs = 10L * 60L * 1_000L,
            maxScenarioDurationMs = 30L * 60L * 1_000L,
            maxGraphicsBytes = graphicsBudget,
            maxCpuLoad = cap.maxCpuLoad,
            maxMemoryLoad = cap.maxMemoryLoad,
            maxGpuLoad = cap.maxGpuLoad,
            maxNpuLoad = cap.maxNpuLoad,
            powerSaveMode = inputs.isPowerSaveMode,
        )
    }

    internal fun isProbablyEmulator(identity: DeviceBuildIdentity): Boolean {
        fun String.normalized() = lowercase(Locale.ROOT)

        val fingerprint = identity.fingerprint.normalized()
        val model = identity.model.normalized()
        val manufacturer = identity.manufacturer.normalized()
        val brand = identity.brand.normalized()
        val device = identity.device.normalized()
        val product = identity.product.normalized()
        val hardware = identity.hardware.normalized()

        return hardware.contains("goldfish") ||
            hardware.contains("ranchu") ||
            hardware.contains("cuttlefish") ||
            hardware.contains("vbox86") ||
            fingerprint.startsWith("generic") ||
            fingerprint.contains("sdk_gphone") ||
            fingerprint.contains("emulator") ||
            model.contains("google_sdk") ||
            model.contains("android sdk built for") ||
            model.contains("emulator") ||
            manufacturer.contains("genymotion") ||
            (brand.startsWith("generic") && device.startsWith("generic")) ||
            product.startsWith("sdk_") ||
            product.contains("emulator") ||
            product.contains("simulator")
    }

    private fun sanitizeMemory(inputs: DeviceRenderSafetyInputs): SanitizedMemory {
        val totalIsCredible =
            inputs.totalRamBytes in MIN_CREDIBLE_TOTAL_RAM..MAX_CREDIBLE_TOTAL_RAM
        val totalBytes = if (totalIsCredible) {
            inputs.totalRamBytes
        } else {
            INVALID_TOTAL_RAM_FALLBACK
        }
        val availableBytes = if (totalIsCredible && inputs.availableRamBytes > 0L) {
            min(inputs.availableRamBytes, totalBytes)
        } else {
            MIN_GRAPHICS_BUDGET
        }
        return SanitizedMemory(
            totalBytes = totalBytes,
            availableBytes = availableBytes,
        )
    }

    fun isLowMemory(activity: Activity): Boolean {
        val activityManager = activity.getSystemService(ActivityManager::class.java)
        return ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo).lowMemory
    }

    fun describe(limits: RenderSafetyLimits): String =
        "${limits.maxLayers}L · ${limits.maxProducerFps.toInt()}fps · " +
            "${limits.maxGraphicsBytes / MIB}MiB graphics budget" +
            if (limits.powerSaveMode) " · power-save cap" else ""
}
