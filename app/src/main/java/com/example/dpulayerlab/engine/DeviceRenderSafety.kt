package com.example.dpulayerlab.engine

import android.app.Activity
import android.app.ActivityManager
import android.os.PowerManager
import com.example.dpulayerlab.model.RenderSafetyLimits
import com.example.dpulayerlab.util.currentDisplayCompat
import kotlin.math.min

/**
 * Builds a conservative safety envelope from memory pressure and device class.
 *
 * This is not a performance score. It is a hard guard against pathological BufferQueue residency,
 * runaway producer rates, and cross-load values that are unreasonable for the current device.
 */
object DeviceRenderSafety {
    private const val MIB = 1024L * 1024L
    private const val GIB = 1024L * MIB

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

        val staticGraphicsBudget = if (lowRam) {
            (totalRam / 12L).coerceIn(96L * MIB, 192L * MIB)
        } else {
            (totalRam / 7L).coerceIn(256L * MIB, 768L * MIB)
        }
        // Do not impose a large floor here. Under real memory pressure even one 4K/8K
        // triple-buffered producer must be rejected instead of treating 96 MiB as available.
        val pressureDivisor = if (lowMemory) 8L else 3L
        val pressureAwareBudget = (availableRam / pressureDivisor).coerceAtLeast(1L * MIB)
        val graphicsBudget = min(staticGraphicsBudget, pressureAwareBudget)

        val maxLayers = when {
            lowMemory -> 2
            lowRam -> 6
            constrainedPower -> 8
            totalRam < 3L * GIB -> 10
            totalRam < 6L * GIB -> 16
            else -> 20
        }
        val maxProducerFps = when {
            lowMemory -> 30f
            lowRam || constrainedPower -> 60f
            else -> 120f
        }
        val maxLoad = when {
            lowMemory -> 0.25f
            lowRam -> 0.60f
            constrainedPower -> 0.70f
            else -> 1f
        }

        return RenderSafetyLimits(
            displayWidthPx = displayWidth,
            displayHeightPx = displayHeight,
            maxLayers = maxLayers,
            maxProducerFps = maxProducerFps,
            maxPhaseDurationMs = 10L * 60L * 1_000L,
            maxScenarioDurationMs = 30L * 60L * 1_000L,
            maxGraphicsBytes = graphicsBudget,
            maxCpuLoad = maxLoad,
            maxMemoryLoad = when {
                lowMemory -> 0f
                lowRam -> 0.50f
                else -> maxLoad
            },
            maxGpuLoad = maxLoad,
            maxNpuLoad = maxLoad,
        )
    }

    fun isLowMemory(activity: Activity): Boolean {
        val activityManager = activity.getSystemService(ActivityManager::class.java)
        return ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo).lowMemory
    }

    fun describe(limits: RenderSafetyLimits): String =
        "${limits.maxLayers}L · ${limits.maxProducerFps.toInt()}fps · " +
            "${limits.maxGraphicsBytes / MIB}MiB graphics budget"
}
