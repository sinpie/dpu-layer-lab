package com.example.dpulayerlab.engine

import android.content.Context
import com.example.dpulayerlab.BuildConfig
import com.example.dpulayerlab.model.DeviceIdentity
import com.example.dpulayerlab.model.RunSummary
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ReportWriter {
    fun write(context: Context, summary: RunSummary): File {
        val directory = File(context.getExternalFilesDir(null) ?: context.filesDir, "reports")
        check(directory.isDirectory || directory.mkdirs()) {
            "Unable to create report directory: ${directory.absolutePath}"
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)
            .format(Date(summary.startedEpochMs))
        val baseName = "$stamp-${safe(summary.scenario.id)}"
        val destination = uniqueDestination(directory, baseName)
        val temporary = File.createTempFile("$baseName-", ".json.part", directory)
        try {
            FileOutputStream(temporary).use { output ->
                val writer = output.writer(Charsets.UTF_8)
                writer.write(toJson(summary))
                writer.flush()
                output.fd.sync()
            }
            check(temporary.renameTo(destination)) {
                "Unable to atomically publish report: ${destination.absolutePath}"
            }
            return destination
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    internal fun toJson(summary: RunSummary): String {
        val device = DeviceIdentity()
        return buildString {
            appendLine("{")
            appendLine("""  "schemaVersion": 1,""")
            appendLine("""  "appVersion": ${quote(BuildConfig.VERSION_NAME)},""")
            appendLine("""  "scenarioId": ${quote(summary.scenario.id)},""")
            appendLine("""  "scenarioName": ${quote(summary.scenario.name)},""")
            appendLine("""  "verdict": ${quote(summary.verdict.name)},""")
            appendLine("""  "startedEpochMs": ${summary.startedEpochMs},""")
            appendLine("""  "finishedEpochMs": ${summary.finishedEpochMs},""")
            appendLine("""  "controlLayerIncluded": true,""")
            appendLine("""  "device": {""")
            appendLine("""    "manufacturer": ${quote(device.manufacturer)},""")
            appendLine("""    "model": ${quote(device.model)},""")
            appendLine("""    "device": ${quote(device.device)},""")
            appendLine("""    "sdk": ${device.sdk},""")
            appendLine("""    "release": ${quote(device.release)},""")
            appendLine("""    "fingerprint": ${quote(device.fingerprint)}""")
            appendLine("  },")
            appendLine("""  "exactUnderrunDelta": ${summary.exactUnderrunDelta ?: "null"},""")
            appendLine("""  "exactUnderrunSource": ${quote(summary.exactUnderrunSource.orEmpty())},""")
            appendLine("""  "suspectedUnderrunDelta": ${summary.suspectedUnderrunDelta},""")
            val latest = summary.samples.lastOrNull()
            appendLine("""  "telemetrySources": {""")
            appendLine(
                """    "exactUnderrun": ${
                    exactCounterSourceJson(
                        latest?.exactUnderruns,
                        latest?.exactUnderrunSource,
                    )
                },""",
            )
            appendLine("""    "cpu": ${sourceJson(latest?.cpu)},""")
            appendLine("""    "display": ${sourceJson(latest?.displayHz)},""")
            appendLine("""    "gpu": ${sourceJson(latest?.gpuBusy)},""")
            appendLine("""    "memoryBus": ${sourceJson(latest?.busBusy)},""")
            appendLine("""    "dpu": ${sourceJson(latest?.dpuBusy)},""")
            appendLine("""    "generatedBandwidth": ${sourceJson(latest?.generatedBandwidth)}""")
            appendLine("  },")
            appendLine("""  "peaks": {""")
            appendLine("""    "cpuPercent": ${number(summary.peakCpu)},""")
            appendLine("""    "memoryUsedPercent": ${number(summary.peakMemoryUsed)},""")
            appendLine("""    "generatedBandwidthGbps": ${number(summary.peakGeneratedBandwidth)}""")
            appendLine("  },")
            appendLine("""  "phases": [""")
            summary.scenario.phases.forEachIndexed { index, phase ->
                append("    {")
                append(""""id": ${quote(phase.id)}, """)
                append(""""durationMs": ${phase.durationMs}, """)
                append(""""layers": ${phase.activeLayers}, """)
                append(""""producerFps": ${phase.producerFps}, """)
                append(""""requestedDisplayHz": ${phase.requestedDisplayHz}, """)
                append(""""backend": ${quote(phase.backend.name)}, """)
                append(""""pixelRoute": ${quote(phase.pixelRoute.name)}, """)
                append(""""bufferSize": ${quote(phase.bufferSize.name)}""")
                append("}")
                appendLine(if (index == summary.scenario.phases.lastIndex) "" else ",")
            }
            appendLine("  ],")
            appendLine("""  "events": [""")
            summary.events.forEachIndexed { index, event ->
                append(
                    """    {"tMs": ${event.monotonicMs}, "type": ${quote(event.type)}, "message": ${quote(event.message)}}""",
                )
                appendLine(if (index == summary.events.lastIndex) "" else ",")
            }
            appendLine("  ],")
            appendLine("""  "samples": [""")
            summary.samples.forEachIndexed { index, sample ->
                append("    {")
                append(""""tMs": ${sample.monotonicMs}, """)
                append(""""cpu": ${number(sample.cpu.value)}, """)
                append(""""appCpu": ${number(sample.appCpu.value)}, """)
                append(""""memoryUsed": ${number(sample.memoryUsed.value)}, """)
                append(""""memoryAvailableMb": ${number(sample.memoryAvailable.value)}, """)
                append(""""appPssMb": ${number(sample.appPss.value)}, """)
                append(""""displayHz": ${number(sample.displayHz.value)}, """)
                append(""""producedFps": ${number(sample.producedFps.value)}, """)
                append(""""missedFrames": ${sample.missedFrames}, """)
                append(""""exactUnderruns": ${sample.exactUnderruns ?: "null"}, """)
                append(""""exactUnderrunSource": ${quote(sample.exactUnderrunSource.orEmpty())}, """)
                append(""""gpuBusy": ${number(sample.gpuBusy.value)}, """)
                append(""""gpuFrequencyMhz": ${number(sample.gpuFrequency.value)}, """)
                append(""""busBusy": ${number(sample.busBusy.value)}, """)
                append(""""dpuBusy": ${number(sample.dpuBusy.value)}, """)
                append(""""hwcDeviceLayers": ${sample.hwcDeviceLayers ?: "null"}, """)
                append(""""hwcClientLayers": ${sample.hwcClientLayers ?: "null"}, """)
                append(""""surfaceFlingerHwcMissed": ${sample.surfaceFlingerHwcMissed ?: "null"}, """)
                append(""""surfaceFlingerGpuMissed": ${sample.surfaceFlingerGpuMissed ?: "null"}, """)
                append(""""generatedBandwidthGbps": ${number(sample.generatedBandwidth.value)}, """)
                append(""""thermalStatus": ${sample.thermalStatus}, """)
                append(""""thermal": ${quote(sample.thermalLabel)}, """)
                append(""""memoryLow": ${sample.memoryLow}, """)
                append(""""npuState": ${quote(sample.npuState)}""")
                append("}")
                appendLine(if (index == summary.samples.lastIndex) "" else ",")
            }
            appendLine("  ]")
            appendLine("}")
        }
    }

    private fun number(value: Float?): String = value?.takeIf { it.isFinite() }?.toString() ?: "null"

    private fun sourceJson(gauge: com.example.dpulayerlab.model.Gauge?): String {
        gauge ?: return """{"quality":"UNAVAILABLE","source":""}"""
        return """{"quality":${quote(gauge.quality.name)},"source":${quote(gauge.source)}}"""
    }

    private fun exactCounterSourceJson(value: Long?, source: String?): String {
        val quality = if (value != null && !source.isNullOrBlank()) {
            com.example.dpulayerlab.model.MetricQuality.HARDWARE_COUNTER.name
        } else {
            com.example.dpulayerlab.model.MetricQuality.UNAVAILABLE.name
        }
        return """{"quality":${quote(quality)},"source":${quote(source.orEmpty())}}"""
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }

    private fun safe(value: String): String = value.replace(Regex("""[^A-Za-z0-9._-]"""), "_")

    private fun uniqueDestination(directory: File, baseName: String): File {
        repeat(1_000) { suffix ->
            val name = if (suffix == 0) "$baseName.json" else "$baseName-$suffix.json"
            val candidate = File(directory, name)
            if (!candidate.exists()) return candidate
        }
        error("Too many report filename collisions for $baseName")
    }
}
