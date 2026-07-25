package com.example.dpulayerlab.engine

import android.content.Context
import com.example.dpulayerlab.BuildConfig
import com.example.dpulayerlab.model.DeviceIdentity
import com.example.dpulayerlab.model.MetricQuality
import com.example.dpulayerlab.model.RunSummary
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ReportWriter {
    @Synchronized
    fun write(context: Context, summary: RunSummary): File {
        // Reports contain build fingerprints and vendor telemetry. Keep them in credential-
        // encrypted internal storage on every supported API; sharing is only through FileProvider.
        val directory = File(context.filesDir, "reports")
        check(directory.isDirectory || directory.mkdirs()) {
            "Unable to create report directory: ${directory.absolutePath}"
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)
            .format(Date(summary.startedEpochMs))
        val baseName = "$REPORT_FILE_PREFIX$stamp-${safe(summary.scenario.id)}"
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
            // Publication is the required operation; retention is deliberately best-effort.
            // Serializing writers prevents one run from pruning another run while it is between
            // destination selection and publication.
            runCatching {
                pruneCompletedReports(
                    directory = directory,
                    protectedReport = destination,
                    keepCount = MAX_COMPLETED_REPORTS,
                )
            }
            return destination
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    internal fun toJson(
        summary: RunSummary,
        device: DeviceIdentity = DeviceIdentity(),
    ): String {
        return buildString {
            appendLine("{")
            appendLine("""  "schemaVersion": 2,""")
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
            appendLine(
                """  "exactUnderrunQuality": ${
                    quote(
                        reportProvenanceQuality(
                            available = summary.exactUnderrunDelta != null,
                            source = summary.exactUnderrunSource,
                            quality = summary.exactUnderrunQuality,
                        ).name,
                    )
                },""",
            )
            appendLine("""  "suspectedUnderrunDelta": ${summary.suspectedUnderrunDelta},""")
            val latest = summary.samples.lastOrNull()
            appendLine("""  "telemetrySources": {""")
            appendLine(
                """    "exactUnderrun": ${
                    exactCounterSourceJson(
                        latest?.exactUnderruns,
                        latest?.exactUnderrunSource,
                        latest?.exactUnderrunQuality,
                    )
                },""",
            )
            appendLine("""    "cpu": ${sourceJson(latest?.cpu)},""")
            appendLine("""    "appCpu": ${sourceJson(latest?.appCpu)},""")
            appendLine("""    "memoryUsed": ${sourceJson(latest?.memoryUsed)},""")
            appendLine("""    "memoryAvailable": ${sourceJson(latest?.memoryAvailable)},""")
            appendLine("""    "appPss": ${sourceJson(latest?.appPss)},""")
            appendLine("""    "display": ${sourceJson(latest?.displayHz)},""")
            appendLine("""    "producedFps": ${sourceJson(latest?.producedFps)},""")
            appendLine(
                """    "suspectedUnderrun": ${
                    provenanceJson(
                        available = latest != null,
                        source = latest?.suspectedUnderrunSource,
                        quality = latest?.suspectedUnderrunQuality,
                    )
                },""",
            )
            appendLine("""    "gpu": ${sourceJson(latest?.gpuBusy)},""")
            appendLine("""    "gpuFrequency": ${sourceJson(latest?.gpuFrequency)},""")
            appendLine("""    "memoryBus": ${sourceJson(latest?.busBusy)},""")
            appendLine("""    "dpu": ${sourceJson(latest?.dpuBusy)},""")
            appendLine("""    "dpuFrequency": ${sourceJson(latest?.dpuFrequency)},""")
            appendLine(
                """    "hwcDeviceLayers": ${
                    provenanceJson(
                        available = latest?.hwcDeviceLayers != null,
                        source = latest?.hwcDeviceLayersSource,
                        quality = latest?.hwcDeviceLayersQuality,
                    )
                },""",
            )
            appendLine(
                """    "hwcClientLayers": ${
                    provenanceJson(
                        available = latest?.hwcClientLayers != null,
                        source = latest?.hwcClientLayersSource,
                        quality = latest?.hwcClientLayersQuality,
                    )
                },""",
            )
            appendLine(
                """    "surfaceFlingerMiss": ${
                    provenanceJson(
                        available = latest?.surfaceFlingerHwcMissed != null ||
                            latest?.surfaceFlingerGpuMissed != null,
                        source = latest?.surfaceFlingerMissSource,
                        quality = MetricQuality.PROXY,
                    )
                },""",
            )
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
                append(""""producerFps": ${jsonNumber(phase.producerFps)}, """)
                append(""""requestedDisplayHz": ${jsonNumber(phase.requestedDisplayHz)}, """)
                append(""""backend": ${quote(phase.backend.name)}, """)
                append(""""pixelRoute": ${quote(phase.pixelRoute.name)}, """)
                append(""""bufferSize": ${quote(phase.bufferSize.name)}, """)
                append(""""motion": ${quote(phase.motion.name)}, """)
                append(""""motionSemantics": ${quote(phase.motion.semantics.name)}, """)
                append(
                    """"physicalHwcZOrderChange": """ +
                        "${phase.motion.semantics.changesPhysicalHwcZOrder}, ",
                )
                append(""""alphaOverlap": ${phase.alphaOverlap}, """)
                append(""""includeGlLayer": ${phase.includeGlLayer}, """)
                append(
                    """"hwcCompositionExpectation": """ +
                        "${quote(phase.hwcCompositionExpectation.name)}, ",
                )
                append(""""workloads": {""")
                append(""""cpu": ${jsonNumber(phase.workloads.cpu)}, """)
                append(""""memory": ${jsonNumber(phase.workloads.memory)}, """)
                append(""""gpu": ${jsonNumber(phase.workloads.gpu)}, """)
                append(""""npu": ${jsonNumber(phase.workloads.npu)}, """)
                append(""""shape": ${quote(phase.workloads.shape.name)}}, """)
                append(""""transition": {""")
                append(""""mode": ${quote(phase.transition.mode.name)}, """)
                append(""""durationMs": ${phase.transition.transitionDurationMs}, """)
                append(""""cycleMs": ${phase.transition.cycleMs}, """)
                append(""""stepCount": ${phase.transition.stepCount}, """)
                append(""""dutyCycle": ${jsonNumber(phase.transition.dutyCycle)}, """)
                append(""""floor": ${jsonNumber(phase.transition.floor)}}""")
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
                append(""""cpuQuality": ${quote(reportQuality(sample.cpu).name)}, """)
                append(""""cpuSource": ${quote(sample.cpu.source)}, """)
                append(""""appCpu": ${number(sample.appCpu.value)}, """)
                append(""""appCpuQuality": ${quote(reportQuality(sample.appCpu).name)}, """)
                append(""""appCpuSource": ${quote(sample.appCpu.source)}, """)
                append(""""memoryUsed": ${number(sample.memoryUsed.value)}, """)
                append(""""memoryUsedQuality": ${quote(reportQuality(sample.memoryUsed).name)}, """)
                append(""""memoryUsedSource": ${quote(sample.memoryUsed.source)}, """)
                append(""""memoryAvailableMb": ${number(sample.memoryAvailable.value)}, """)
                append(
                    """"memoryAvailableQuality": ${
                        quote(reportQuality(sample.memoryAvailable).name)
                    }, """,
                )
                append(""""memoryAvailableSource": ${quote(sample.memoryAvailable.source)}, """)
                append(""""appPssMb": ${number(sample.appPss.value)}, """)
                append(""""appPssQuality": ${quote(reportQuality(sample.appPss).name)}, """)
                append(""""appPssSource": ${quote(sample.appPss.source)}, """)
                append(""""displayHz": ${number(sample.displayHz.value)}, """)
                append(""""displayHzQuality": ${quote(reportQuality(sample.displayHz).name)}, """)
                append(""""displayHzSource": ${quote(sample.displayHz.source)}, """)
                append(""""producedFps": ${number(sample.producedFps.value)}, """)
                append(
                    """"producedFpsQuality": ${quote(reportQuality(sample.producedFps).name)}, """,
                )
                append(""""producedFpsSource": ${quote(sample.producedFps.source)}, """)
                append(""""missedFrames": ${sample.missedFrames}, """)
                append(
                    """"missedFramesQuality": ${
                        quote(
                            reportProvenanceQuality(
                                available = true,
                                source = sample.suspectedUnderrunSource,
                                quality = sample.suspectedUnderrunQuality,
                            ).name,
                        )
                    }, """,
                )
                append(
                    """"missedFramesSource": ${quote(sample.suspectedUnderrunSource)}, """,
                )
                append(""""suspectedUnderruns": ${sample.suspectedUnderruns}, """)
                append(
                    """"suspectedUnderrunQuality": ${
                        quote(
                            reportProvenanceQuality(
                                available = true,
                                source = sample.suspectedUnderrunSource,
                                quality = sample.suspectedUnderrunQuality,
                            ).name,
                        )
                    }, """,
                )
                append(
                    """"suspectedUnderrunSource": ${
                        quote(sample.suspectedUnderrunSource)
                    }, """,
                )
                append(""""exactUnderruns": ${sample.exactUnderruns ?: "null"}, """)
                append(""""exactUnderrunSource": ${quote(sample.exactUnderrunSource.orEmpty())}, """)
                append(
                    """"exactUnderrunQuality": ${
                        quote(
                            reportProvenanceQuality(
                                available = sample.exactUnderruns != null,
                                source = sample.exactUnderrunSource,
                                quality = sample.exactUnderrunQuality,
                            ).name,
                        )
                    }, """,
                )
                append(""""gpuBusy": ${number(sample.gpuBusy.value)}, """)
                append(""""gpuBusyQuality": ${quote(reportQuality(sample.gpuBusy).name)}, """)
                append(""""gpuBusySource": ${quote(sample.gpuBusy.source)}, """)
                append(""""gpuFrequencyMhz": ${number(sample.gpuFrequency.value)}, """)
                append(
                    """"gpuFrequencyQuality": ${quote(reportQuality(sample.gpuFrequency).name)}, """,
                )
                append(""""gpuFrequencySource": ${quote(sample.gpuFrequency.source)}, """)
                append(""""busBusy": ${number(sample.busBusy.value)}, """)
                append(""""busBusyQuality": ${quote(reportQuality(sample.busBusy).name)}, """)
                append(""""busBusySource": ${quote(sample.busBusy.source)}, """)
                append(""""dpuBusy": ${number(sample.dpuBusy.value)}, """)
                append(""""dpuBusyQuality": ${quote(reportQuality(sample.dpuBusy).name)}, """)
                append(""""dpuBusySource": ${quote(sample.dpuBusy.source)}, """)
                append(""""dpuFrequencyMhz": ${number(sample.dpuFrequency.value)}, """)
                append(
                    """"dpuFrequencyQuality": ${
                        quote(reportQuality(sample.dpuFrequency).name)
                    }, """,
                )
                append(""""dpuFrequencySource": ${quote(sample.dpuFrequency.source)}, """)
                append(""""hwcDeviceLayers": ${sample.hwcDeviceLayers ?: "null"}, """)
                append(
                    """"hwcDeviceLayersQuality": ${
                        quote(
                            reportProvenanceQuality(
                                available = sample.hwcDeviceLayers != null,
                                source = sample.hwcDeviceLayersSource,
                                quality = sample.hwcDeviceLayersQuality,
                            ).name,
                        )
                    }, """,
                )
                append(
                    """"hwcDeviceLayersSource": ${quote(sample.hwcDeviceLayersSource)}, """,
                )
                append(""""hwcClientLayers": ${sample.hwcClientLayers ?: "null"}, """)
                append(
                    """"hwcClientLayersQuality": ${
                        quote(
                            reportProvenanceQuality(
                                available = sample.hwcClientLayers != null,
                                source = sample.hwcClientLayersSource,
                                quality = sample.hwcClientLayersQuality,
                            ).name,
                        )
                    }, """,
                )
                append(
                    """"hwcClientLayersSource": ${quote(sample.hwcClientLayersSource)}, """,
                )
                append(
                    """"hwcCompositionEvidenceMonotonicMs": ${
                        sample.hwcCompositionEvidenceMonotonicMs ?: "null"
                    }, """,
                )
                append(
                    """"hwcCompositionEvidenceAgeMs": ${
                        sample.hwcCompositionEvidenceAgeMs ?: "null"
                    }, """,
                )
                append(""""surfaceFlingerHwcMissed": ${sample.surfaceFlingerHwcMissed ?: "null"}, """)
                append(""""surfaceFlingerGpuMissed": ${sample.surfaceFlingerGpuMissed ?: "null"}, """)
                append(
                    """"surfaceFlingerMissQuality": ${
                        quote(
                            reportProvenanceQuality(
                                available = sample.surfaceFlingerHwcMissed != null ||
                                    sample.surfaceFlingerGpuMissed != null,
                                source = sample.surfaceFlingerMissSource,
                                quality = MetricQuality.PROXY,
                            ).name,
                        )
                    }, """,
                )
                append(
                    """"surfaceFlingerMissSource": ${
                        quote(sample.surfaceFlingerMissSource)
                    }, """,
                )
                append(
                    """"surfaceFlingerEvidenceMonotonicMs": ${
                        sample.surfaceFlingerEvidenceMonotonicMs ?: "null"
                    }, """,
                )
                append(
                    """"surfaceFlingerEvidenceAgeMs": ${
                        sample.surfaceFlingerEvidenceAgeMs ?: "null"
                    }, """,
                )
                append(""""generatedBandwidthGbps": ${number(sample.generatedBandwidth.value)}, """)
                append(
                    """"generatedBandwidthQuality": ${
                        quote(reportQuality(sample.generatedBandwidth).name)
                    }, """,
                )
                append(
                    """"generatedBandwidthSource": ${
                        quote(sample.generatedBandwidth.source)
                    }, """,
                )
                append(""""thermalStatus": ${sample.thermalStatus}, """)
                append(""""thermal": ${quote(sample.thermalLabel)}, """)
                append(""""memoryLow": ${sample.memoryLow}, """)
                append(""""powerSaveMode": ${sample.powerSaveMode}, """)
                append(""""vendorServiceSession": ${sample.vendorServiceSession ?: "null"}, """)
                append(""""compressionState": ${quote(sample.compressionState)}, """)
                append(""""npuState": ${quote(sample.npuState)}""")
                append("}")
                appendLine(if (index == summary.samples.lastIndex) "" else ",")
            }
            appendLine("  ]")
            appendLine("}")
        }
    }

    private fun number(value: Float?): String = jsonNumber(value)

    private fun sourceJson(gauge: com.example.dpulayerlab.model.Gauge?): String {
        gauge ?: return """{"quality":"UNAVAILABLE","source":""}"""
        return """{"quality":${quote(reportQuality(gauge).name)},"source":${quote(gauge.source)}}"""
    }

    private fun exactCounterSourceJson(
        value: Long?,
        source: String?,
        quality: com.example.dpulayerlab.model.MetricQuality?,
    ): String = provenanceJson(
        available = value != null,
        source = source,
        quality = quality,
    )

    private fun provenanceJson(
        available: Boolean,
        source: String?,
        quality: MetricQuality?,
    ): String {
        val reportedQuality = reportProvenanceQuality(available, source, quality)
        return """{"quality":${quote(reportedQuality.name)},"source":${quote(source.orEmpty())}}"""
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

    private fun safe(value: String): String =
        value.replace(Regex("""[^A-Za-z0-9._-]"""), "_")
            .take(MAX_REPORT_ID_CHARS)
            .ifBlank { "scenario" }

    private fun uniqueDestination(directory: File, baseName: String): File {
        repeat(1_000) { suffix ->
            val name = if (suffix == 0) "$baseName.json" else "$baseName-$suffix.json"
            val candidate = File(directory, name)
            if (!candidate.exists()) return candidate
        }
        error("Too many report filename collisions for $baseName")
    }

    private fun pruneCompletedReports(
        directory: File,
        protectedReport: File,
        keepCount: Int,
    ) {
        val files = directory.listFiles()?.filter(File::isFile).orEmpty()
        val namesToDelete = selectCompletedReportsForDeletion(
            entries = files.map {
                ReportRetentionEntry(
                    name = it.name,
                    lastModifiedMs = it.lastModified(),
                )
            },
            protectedReportName = protectedReport.name,
            keepCount = keepCount,
        )
        files.forEach { file ->
            if (file.name in namesToDelete) {
                runCatching { file.delete() }
            }
        }
    }

    private const val MAX_REPORT_ID_CHARS = 80
    private const val MAX_COMPLETED_REPORTS = 200
}

internal data class ReportRetentionEntry(
    val name: String,
    val lastModifiedMs: Long,
)

/**
 * Selects only completed JSON reports beyond the newest [keepCount].
 *
 * The just-published report is always retained and counts toward the limit. Temporary `.part`
 * files and unrelated files stay outside retention so incomplete publication evidence is never
 * mistaken for a completed report.
 */
internal fun selectCompletedReportsForDeletion(
    entries: List<ReportRetentionEntry>,
    protectedReportName: String,
    keepCount: Int,
): Set<String> {
    require(keepCount > 0)
    val completed = entries
        .filter { isManagedCompletedReportName(it.name) }
        .distinctBy(ReportRetentionEntry::name)
    // Reserve the protected slot even if a transient directory listing omitted the just-renamed
    // file; otherwise retention could leave keepCount old entries plus the new report.
    val protectedCount = if (isManagedCompletedReportName(protectedReportName)) 1 else 0
    val retainedOthers = completed
        .asSequence()
        .filterNot { it.name == protectedReportName }
        .sortedWith(
            compareByDescending<ReportRetentionEntry> { it.lastModifiedMs }
                .thenByDescending { it.name },
        )
        .take(keepCount - protectedCount)
        .mapTo(HashSet(), ReportRetentionEntry::name)
    return completed
        .asSequence()
        .filterNot { it.name == protectedReportName || it.name in retainedOthers }
        .mapTo(LinkedHashSet(), ReportRetentionEntry::name)
}

internal fun isManagedCompletedReportName(name: String): Boolean =
    MANAGED_COMPLETED_REPORT_PATTERN.matches(name)

internal fun reportQuality(gauge: com.example.dpulayerlab.model.Gauge): MetricQuality =
    reportProvenanceQuality(
        available = gauge.value?.isFinite() == true,
        source = gauge.source,
        quality = gauge.quality,
    )

internal fun reportProvenanceQuality(
    available: Boolean,
    source: String?,
    quality: MetricQuality?,
): MetricQuality =
    if (available && !source.isNullOrBlank() && quality != null) {
        quality.takeIf { it != MetricQuality.UNAVAILABLE } ?: MetricQuality.UNAVAILABLE
    } else {
        MetricQuality.UNAVAILABLE
    }

internal fun jsonNumber(value: Float?): String =
    value?.takeIf(Float::isFinite)?.toString() ?: "null"

private const val REPORT_FILE_PREFIX = "dpu-layer-lab-"
private val MANAGED_COMPLETED_REPORT_PATTERN =
    Regex(
        """^${Regex.escape(REPORT_FILE_PREFIX)}\d{8}-\d{6}-\d{3}-""" +
            """[A-Za-z0-9._-]{1,80}(?:-\d{1,3})?\.json$""",
    )
