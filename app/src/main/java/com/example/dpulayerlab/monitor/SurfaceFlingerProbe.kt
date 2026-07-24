package com.example.dpulayerlab.monitor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

data class CompositionSnapshot(
    val deviceLayers: Int? = null,
    val clientLayers: Int? = null,
    val hwcMissedFrames: Long? = null,
    val gpuMissedFrames: Long? = null,
    val source: String = "",
    val detail: String = "",
)

class SurfaceFlingerProbe(private val context: Context) {
    fun hasDumpPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED

    fun sample(): CompositionSnapshot {
        if (!hasDumpPermission()) return CompositionSnapshot(detail = "DUMP 권한 없음")
        var process: Process? = null
        var readFuture: CompletableFuture<String>? = null
        return try {
            val startedProcess = ProcessBuilder("/system/bin/dumpsys", "SurfaceFlinger", "--hwclayers")
                .redirectErrorStream(true)
                .start()
            process = startedProcess
            readFuture = CompletableFuture.supplyAsync {
                startedProcess.inputStream.bufferedReader().use {
                    readBoundedText(it, MAX_DUMP_CHARS)
                }
            }
            val text = readFuture.get(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            parseSurfaceFlingerDump(text)
        } catch (_: TimeoutException) {
            CompositionSnapshot(detail = "SurfaceFlinger 응답 시간 초과")
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            CompositionSnapshot(detail = "SurfaceFlinger probe 중단됨")
        } catch (error: Exception) {
            CompositionSnapshot(detail = "SurfaceFlinger probe 실패: ${error.javaClass.simpleName}")
        } finally {
            cleanupProcess(process, readFuture)
        }
    }

    private fun cleanupProcess(
        process: Process?,
        readFuture: CompletableFuture<String>?,
    ) {
        process?.let { runningProcess ->
            if (runningProcess.isAlive) {
                runCatching { runningProcess.destroyForcibly() }
            }
            runCatching { runningProcess.inputStream.close() }
            runCatching { runningProcess.errorStream.close() }
            runCatching { runningProcess.outputStream.close() }
        }
        readFuture?.cancel(true)
        process?.let { runningProcess ->
            runCatching { runningProcess.waitFor(PROCESS_CLEANUP_WAIT_MS, TimeUnit.MILLISECONDS) }
            if (runningProcess.isAlive) {
                runCatching { runningProcess.destroyForcibly() }
            }
        }
    }

    private companion object {
        const val PROBE_TIMEOUT_MS = 800L
        const val PROCESS_CLEANUP_WAIT_MS = 100L
        const val MAX_DUMP_CHARS = 4 * 1_024 * 1_024
    }
}

internal fun readBoundedText(reader: java.io.Reader, maxChars: Int): String {
    require(maxChars > 0)
    val result = StringBuilder(minOf(maxChars, 16 * 1_024))
    val buffer = CharArray(8 * 1_024)
    while (true) {
        val read = reader.read(buffer)
        if (read < 0) return result.toString()
        if (read == 0) continue
        if (result.length > maxChars - read) {
            throw IllegalStateException("SurfaceFlinger dump exceeds $maxChars characters")
        }
        result.append(buffer, 0, read)
    }
}

internal fun parseSurfaceFlingerDump(text: String): CompositionSnapshot {
    val source = "dumpsys SurfaceFlinger --hwclayers"
    // Keep legacy tokens because package/vendor integration identifiers intentionally remain
    // stable across the user-facing DPULayerTest rename.
    val appTokens = listOf("dpulayerlab", "DpuLab", "DPU Layer Lab", "DPULayerTest")
    val blocks = text.split(Regex("""(?m)(?=^\* Layer )"""))
    val appBlocks = blocks.filter { block ->
        val header = block.lineSequence().firstOrNull().orEmpty()
        appTokens.any { header.contains(it, ignoreCase = true) }
    }
    val appLines = text.lineSequence().filter { line ->
        appTokens.any { line.contains(it, ignoreCase = true) }
    }.toList()
    val devicePattern = Regex(
        """composition\s+type=(DEVICE|SOLID_COLOR|CURSOR|SIDEBAND)|\b(DEVICE|SOLID_COLOR|CURSOR|SIDEBAND)\b""",
        RegexOption.IGNORE_CASE,
    )
    val clientPattern = Regex("""composition\s+type=CLIENT|\bCLIENT\b""", RegexOption.IGNORE_CASE)
    val device = if (appBlocks.isNotEmpty()) {
        appBlocks.count(devicePattern::containsMatchIn)
    } else {
        appLines.count(devicePattern::containsMatchIn)
    }
    val client = if (appBlocks.isNotEmpty()) {
        appBlocks.count(clientPattern::containsMatchIn)
    } else {
        appLines.count(clientPattern::containsMatchIn)
    }
    val hwcMissed = Regex("""HWC missed frame count:\s*(\d+)""", RegexOption.IGNORE_CASE)
        .find(text)?.groupValues?.getOrNull(1)?.toLongOrNull()
    val gpuMissed = Regex("""GPU missed frame count:\s*(\d+)""", RegexOption.IGNORE_CASE)
        .find(text)?.groupValues?.getOrNull(1)?.toLongOrNull()
    val matchedCount = if (appBlocks.isNotEmpty()) appBlocks.size else appLines.size
    return CompositionSnapshot(
        deviceLayers = device.takeIf { matchedCount > 0 },
        clientLayers = client.takeIf { matchedCount > 0 },
        hwcMissedFrames = hwcMissed,
        gpuMissedFrames = gpuMissed,
        source = source,
        detail = if (matchedCount > 0) {
            "$matchedCount app layers parsed"
        } else {
            "앱 layer가 dump에 노출되지 않음"
        },
    )
}
