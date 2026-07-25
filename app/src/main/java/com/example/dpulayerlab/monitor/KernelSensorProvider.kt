package com.example.dpulayerlab.monitor

import android.content.Context
import com.example.dpulayerlab.BuildConfig
import com.example.dpulayerlab.model.Gauge
import com.example.dpulayerlab.model.MetricQuality
import com.example.dpulayerlab.model.SensorReading
import java.io.File
import java.util.concurrent.atomic.AtomicReference

data class KernelSensorSnapshot(
    val gpuBusy: Gauge = Gauge(),
    val gpuFrequency: Gauge = Gauge(),
    val busBusy: Gauge = Gauge(),
    val dpuBusy: Gauge = Gauge(),
    val dpuFrequency: Gauge = Gauge(),
    val exactUnderruns: Long? = null,
    val exactUnderrunSource: String? = null,
    val readings: List<SensorReading> = emptyList(),
)

/**
 * Read-only, allowlisted sysfs adapter. Broad filesystem access remains a vendor service concern.
 * Additional product paths can be listed as key=/absolute/path. The
 * /data/local/tmp/dpulayerlab-probes.conf convenience path is accepted only by DEBUG builds;
 * release products use the app-private or /vendor/etc configuration. SELinux and DAC still apply
 * even for a platform-signed APK.
 */
class KernelSensorProvider(private val context: Context) {
    private val customPaths = AtomicReference<Map<String, String>>(emptyMap())
    private var lastGpuBusyCounter: BusyCounterState? = null
    private var lastBusBusyCounter: BusyCounterState? = null
    private var lastDpuBusyCounter: BusyCounterState? = null

    init {
        reloadCustomPaths()
    }

    fun reloadCustomPaths() {
        val candidates = listOf(
            File(context.filesDir, "probe_paths.conf"),
            File("/vendor/etc/dpulayerlab/probe_paths.conf"),
        ) + if (BuildConfig.DEBUG) {
            listOf(File("/data/local/tmp/dpulayerlab-probes.conf"))
        } else {
            emptyList()
        }
        val values = linkedMapOf<String, String>()
        candidates.filter { it.isFile && it.canRead() }.forEach { file ->
            val fileLength = runCatching { file.length() }.getOrNull() ?: return@forEach
            if (fileLength < 0L || fileLength > MAX_CONFIG_BYTES) return@forEach
            val lines = readBoundedConfigLines(file) ?: return@forEach
            values.putAll(parseCustomProbeConfig(lines))
        }
        customPaths.set(values)
    }

    fun sample(): KernelSensorSnapshot {
        val readings = mutableListOf<SensorReading>()
        val paths = customPaths.get()

        val gpuBusy = readGpuBusyPercent(
            paths = paths,
            previous = lastGpuBusyCounter,
        )
        // A read gap invalidates a cumulative baseline. Reusing it after permissions, power, or
        // the selected source changed would turn a long unknown interval into a current sample.
        lastGpuBusyCounter = gpuBusy?.nextCounter

        val gpuFreq = readGpuFrequencyMhz(paths)
        val busBusy = readBusyPercent(
            explicit = paths["bus_busy"],
            candidates = listOf(
                "/sys/class/devfreq/17000010.devfreq_mif/load",
                "/sys/class/devfreq/dmc/load",
            ),
            previous = lastBusBusyCounter,
        )
        lastBusBusyCounter = busBusy?.nextCounter

        val dpuBusy = readBusyPercent(
            explicit = paths["dpu_busy"],
            candidates = listOf(
                "/sys/class/dpu/dpu0/utilization",
                "/sys/class/dpu/dpu0/busy_percent",
                "/sys/class/drm/card0/device/dpu_busy",
            ),
            previous = lastDpuBusyCounter,
        )
        lastDpuBusyCounter = dpuBusy?.nextCounter
        // There is no portable Android DPU/decon clock node and vendor units differ. Accept this
        // metric only through an explicitly configured, Hz-valued product path.
        val dpuFrequencyHz = readFirstLong(
            paths["dpu_frequency_hz"],
            emptyList(),
        )?.takeIf { (value, _) -> value.validDpuFrequencyHz() != null }
        val underruns = readFirstLong(
            paths["dpu_underrun"],
            listOf(
                "/sys/class/dpu/dpu0/underrun_count",
                "/sys/class/dpu/dpu0/underrun_cnt",
                "/sys/class/drm/card0/device/underrun_count",
            ),
        )?.takeIf { (value, _) -> value >= 0L }

        val gpuGauge = gpuBusy?.percent?.let { (value, path) ->
            readings += SensorReading("gpu_busy", "GPU busy", "$value%", MetricQuality.KERNEL, path)
            Gauge(value, "%", MetricQuality.KERNEL, path)
        } ?: Gauge(source = gpuBusyUnavailableSource(paths))
        val gpuFreqGauge = gpuFreq?.let { (mhz, path) ->
            readings += SensorReading("gpu_frequency", "GPU clock", "%.0f MHz".format(mhz), MetricQuality.KERNEL, path)
            Gauge(mhz, " MHz", MetricQuality.KERNEL, path)
        } ?: Gauge(source = gpuFrequencyUnavailableSource(paths))
        val busGauge = busBusy?.percent?.let { (value, path) ->
            readings += SensorReading("bus_busy", "Memory bus", "$value%", MetricQuality.KERNEL, path)
            Gauge(value, "%", MetricQuality.KERNEL, path)
        } ?: Gauge(
            source = unavailableExplicitProbeSource(
                label = "memory-bus busy",
                explicitPath = paths["bus_busy"],
                genericSource = BUS_BUSY_UNAVAILABLE_SOURCE,
            ),
        )
        val dpuGauge = dpuBusy?.percent?.let { (value, path) ->
            readings += SensorReading("dpu_busy", "DPU busy", "$value%", MetricQuality.KERNEL, path)
            Gauge(value, "%", MetricQuality.KERNEL, path)
        } ?: Gauge(
            source = unavailableExplicitProbeSource(
                label = "DPU busy",
                explicitPath = paths["dpu_busy"],
                genericSource = DPU_BUSY_UNAVAILABLE_SOURCE,
            ),
        )
        val dpuFrequencyGauge = dpuFrequencyHz?.let { (value, path) ->
            val mhz = value / 1_000_000f
            readings += SensorReading(
                "dpu_frequency",
                "DPU clock",
                "%.0f MHz".format(mhz),
                MetricQuality.KERNEL,
                path,
            )
            Gauge(mhz, " MHz", MetricQuality.KERNEL, path)
        } ?: Gauge(
            source = unavailableExplicitProbeSource(
                label = "DPU frequency Hz",
                explicitPath = paths["dpu_frequency_hz"],
                genericSource = DPU_FREQUENCY_UNAVAILABLE_SOURCE,
            ),
        )
        underruns?.let { (value, path) ->
            readings += SensorReading(
                "dpu_underrun",
                "DPU underrun",
                value.toString(),
                MetricQuality.KERNEL,
                path,
            )
        }

        return KernelSensorSnapshot(
            gpuBusy = gpuGauge,
            gpuFrequency = gpuFreqGauge,
            busBusy = busGauge,
            dpuBusy = dpuGauge,
            dpuFrequency = dpuFrequencyGauge,
            exactUnderruns = underruns?.first,
            exactUnderrunSource = underruns?.second,
            readings = readings,
        )
    }

    private fun readBusyPercent(
        explicit: String?,
        candidates: List<String>,
        previous: BusyCounterState?,
    ): BusySensorResult? {
        val paths = authoritativeProbePaths(explicit, candidates)
        paths.forEach { path ->
            val text = readProbeText(path) ?: return@forEach
            parseDirectUtilizationPercent(text)?.let { direct ->
                return BusySensorResult(
                    percent = direct to observedBusySource(
                        path,
                        BusyObservedEncoding.DIRECT_PERCENT,
                    ),
                    nextCounter = null,
                )
            }
            val cumulativeSource = observedBusySource(
                path,
                BusyObservedEncoding.CUMULATIVE_BUSY_TOTAL,
            )
            val parsed = parseBusyPercent(text, cumulativeSource, previous)
            if (parsed?.percent != null || parsed?.nextCounter != null) {
                return BusySensorResult(
                    percent = parsed.percent?.let { it to cumulativeSource },
                    nextCounter = parsed.nextCounter,
                )
            }
            if (!explicit.isNullOrBlank()) return null
        }
        return null
    }

    private fun readGpuBusyPercent(
        paths: Map<String, String>,
        previous: BusyCounterState?,
    ): BusySensorResult? {
        val configured = configuredGpuBusyProbes(paths)
        val probes = selectGpuBusyProbes(configured, DEFAULT_GPU_BUSY_PROBES) ?: return null
        probes.forEach { probe ->
            val text = readProbeText(probe) ?: return@forEach
            val source = busyProbeSource(probe.path, probe.format)
            val parsed = when (probe.format) {
                GpuBusyProbeFormat.DIRECT_PERCENT -> BusySensorResult(
                    percent = parseDirectUtilizationPercent(text)?.let { it to source },
                    nextCounter = null,
                )
                GpuBusyProbeFormat.WINDOW_BUSY_TOTAL -> BusySensorResult(
                    percent = parseWindowBusyTotalPercent(text)?.let { it to source },
                    nextCounter = null,
                )
                GpuBusyProbeFormat.MTK_LOADING_BLOCKING_IDLE -> BusySensorResult(
                    percent = parseMtkGpuUtilizationPercent(text)?.let { it to source },
                    nextCounter = null,
                )
                GpuBusyProbeFormat.LEGACY_DIRECT_OR_CUMULATIVE -> {
                    parseDirectUtilizationPercent(text)?.let { direct ->
                        val observedSource = legacyObservedBusyProbeSource(
                            probe.path,
                            BusyObservedEncoding.DIRECT_PERCENT,
                        )
                        return BusySensorResult(
                            percent = direct to observedSource,
                            nextCounter = null,
                        )
                    }
                    val observedSource = legacyObservedBusyProbeSource(
                        probe.path,
                        BusyObservedEncoding.CUMULATIVE_BUSY_TOTAL,
                    )
                    val legacy = parseBusyPercent(text, observedSource, previous)
                    BusySensorResult(
                        percent = legacy?.percent?.let { it to observedSource },
                        nextCounter = legacy?.nextCounter,
                    )
                }
            }
            if (parsed.percent != null || parsed.nextCounter != null) return parsed
            if (configured.isNotEmpty()) return null
        }
        return null
    }

    private fun readFirstLong(explicit: String?, candidates: List<String>): Pair<Long, String>? {
        authoritativeProbePaths(explicit, candidates).forEach { path ->
            val text = readProbeText(path) ?: return@forEach
            val value = parseSingleLongToken(text)
            if (value != null) return value to path
            if (!explicit.isNullOrBlank()) return null
        }
        return null
    }

    private fun readGpuFrequencyMhz(paths: Map<String, String>): Pair<Float, String>? {
        val configured = configuredGpuFrequencyProbes(paths)
        val probes = selectGpuFrequencyProbes(
            configured,
            DEFAULT_GPU_FREQUENCY_PROBES,
        ) ?: return null
        probes.forEach { probe ->
            val text = readProbeText(probe) ?: return@forEach
            val rawValue = when (probe.format) {
                GpuFrequencyProbeFormat.SCALAR -> parseSingleLongToken(text)
                GpuFrequencyProbeFormat.INDEX_AND_FREQUENCY -> parseIndexedGpuFrequency(text)
            }
            val mhz = rawValue?.let { normalizeGpuFrequencyMhz(it, probe.unit) }
            if (mhz != null) {
                return mhz to frequencyProbeSource(probe.path, probe.unit, probe.format)
            }
            if (configured.isNotEmpty()) return null
        }
        return null
    }

    private fun readProbeText(probe: FileProbe): String? {
        return readProbeText(probe.path)
    }

    private fun readProbeText(path: String): String? {
        val file = File(path)
        if (file.canRead() && file.isFile) {
            val value = runCatching {
                file.bufferedReader().use(::readBoundedFirstLine)
            }.getOrNull()
            if (!value.isNullOrBlank()) {
                return value.trim()
            }
        }
        return null
    }

    private fun readBoundedConfigLines(file: File): List<String>? = runCatching {
        val byteLimit = MAX_CONFIG_BYTES.toInt()
        val bytes = ByteArray(byteLimit + 1)
        var count = 0
        file.inputStream().buffered().use { input ->
            while (count < bytes.size) {
                val read = input.read(bytes, count, bytes.size - count)
                if (read < 0) break
                // The requested length is non-zero, so FileInputStream must either make
                // progress or report EOF. Fail closed on a contract-violating zero read instead
                // of spinning or carrying a warning-only retry counter.
                if (read == 0) return null
                count += read
            }
        }
        if (count > byteLimit) return null
        String(bytes, 0, count, Charsets.UTF_8).lineSequence().toList()
    }.getOrNull()

    private data class BusySensorResult(
        val percent: Pair<Float, String>?,
        val nextCounter: BusyCounterState?,
    )

    companion object {
        private const val MAX_CONFIG_BYTES = 64L * 1_024L
        internal val DEFAULT_GPU_BUSY_PROBES = listOf(
            // Qualcomm KGSL: prefer the direct percentage. "gpubusy" is a per-read
            // busy/total window, not a cumulative counter requiring a prior sample.
            GpuBusyProbe(
                "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
                GpuBusyProbeFormat.DIRECT_PERCENT,
            ),
            GpuBusyProbe(
                "/sys/class/kgsl/kgsl-3d0/gpubusy",
                GpuBusyProbeFormat.WINDOW_BUSY_TOTAL,
            ),
            // Samsung Xclipse is derived from AMD RDNA. Samsung's SGPU/amdgpu integration can
            // expose the standard DRM direct-percent ABI; card0 is the only portable fixed
            // candidate. Products using another DRM minor must opt in with gpu_busy_percent.
            GpuBusyProbe(
                "/sys/class/drm/card0/device/gpu_busy_percent",
                GpuBusyProbeFormat.DIRECT_PERCENT,
            ),
            // MediaTek's module parameter is a direct 0..100 loading percentage. GED
            // debugfs/hal formats remain available only through explicit typed config.
            GpuBusyProbe(
                "/sys/module/ged/parameters/gpu_loading",
                GpuBusyProbeFormat.DIRECT_PERCENT,
            ),
            // Legacy/non-Xclipse Exynos products may still use Mali. Do not scan address-named
            // platform directories because their ABI and access policy are BSP-specific.
            GpuBusyProbe(
                "/sys/class/misc/mali0/device/utilization",
                GpuBusyProbeFormat.DIRECT_PERCENT,
            ),
            // Compatibility node used by some Qualcomm/Samsung kernels. Keep it after the
            // architecture-specific ABIs so it cannot mask Xclipse DRM or MediaTek GED.
            GpuBusyProbe(
                "/sys/kernel/gpu/gpu_busy",
                GpuBusyProbeFormat.DIRECT_PERCENT,
            ),
        )
        internal val DEFAULT_GPU_FREQUENCY_PROBES = listOf(
            GpuFrequencyProbe(
                "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",
                ProbeFrequencyUnit.HZ,
                GpuFrequencyProbeFormat.SCALAR,
            ),
            GpuFrequencyProbe(
                "/sys/class/kgsl/kgsl-3d0/gpuclk",
                ProbeFrequencyUnit.HZ,
                GpuFrequencyProbeFormat.SCALAR,
            ),
            GpuFrequencyProbe(
                "/sys/class/kgsl/kgsl-3d0/clock_mhz",
                ProbeFrequencyUnit.MHZ,
                GpuFrequencyProbeFormat.SCALAR,
            ),
            // Fixed legacy Mali devfreq ABI. This is intentionally not used as evidence that a
            // modern Exynos device is Mali; Xclipse products should use vendor API v2 for clock.
            GpuFrequencyProbe(
                "/sys/class/misc/mali0/device/devfreq/devfreq0/cur_freq",
                ProbeFrequencyUnit.HZ,
                GpuFrequencyProbeFormat.SCALAR,
            ),
            // Qualcomm and Samsung GPU compatibility implementations report this node in MHz.
            GpuFrequencyProbe(
                "/sys/kernel/gpu/gpu_clock",
                ProbeFrequencyUnit.MHZ,
                GpuFrequencyProbeFormat.SCALAR,
            ),
            // MediaTek's historical "current_freqency" is intentionally not a default:
            // it is commonly exposed through debugfs/hal and must be opted into with
            // gpu_frequency_index_khz after product access policy is reviewed.
        )
    }
}

internal interface FileProbe {
    val path: String
}

internal enum class GpuBusyProbeFormat {
    DIRECT_PERCENT,
    WINDOW_BUSY_TOTAL,
    MTK_LOADING_BLOCKING_IDLE,
    LEGACY_DIRECT_OR_CUMULATIVE,
}

internal enum class BusyObservedEncoding {
    DIRECT_PERCENT,
    CUMULATIVE_BUSY_TOTAL,
}

internal data class GpuBusyProbe(
    override val path: String,
    val format: GpuBusyProbeFormat,
) : FileProbe

internal enum class GpuFrequencyProbeFormat {
    SCALAR,
    INDEX_AND_FREQUENCY,
}

internal data class GpuFrequencyProbe(
    override val path: String,
    val unit: ProbeFrequencyUnit,
    val format: GpuFrequencyProbeFormat,
) : FileProbe

internal data class BusyCounterState(
    val busy: Long,
    val total: Long,
    val source: String,
)

internal data class BusyPercentParse(
    val percent: Float?,
    val nextCounter: BusyCounterState?,
)

internal fun parseBusyPercent(
    text: String,
    source: String,
    previous: BusyCounterState?,
): BusyPercentParse? {
    val scalar = text.trim()
    if (scalar.isEmpty()) return null
    val tokens = scalar.split(WHITESPACE)
    if (tokens.size !in 1..2 || tokens.any { !SIGNED_INTEGER_SCALAR.matches(it) }) return null
    val numbers = tokens.map { it.toLongOrNull() ?: return null }
    if (numbers.size == 1) {
        val percent = numbers.single().takeIf { it in 0L..100L }?.toFloat()
        return BusyPercentParse(percent = percent, nextCounter = null)
    }

    val busy = numbers[0]
    val total = numbers[1]
    if (busy < 0L || total < 0L || busy > total) return BusyPercentParse(null, null)
    val current = BusyCounterState(busy = busy, total = total, source = source)
    if (
        previous == null ||
        previous.source != source ||
        busy < previous.busy ||
        total < previous.total
    ) {
        return BusyPercentParse(percent = null, nextCounter = current)
    }

    val busyDelta = busy - previous.busy
    val totalDelta = total - previous.total
    val percent = if (totalDelta > 0L && busyDelta <= totalDelta) {
        (busyDelta.toDouble() * 100.0 / totalDelta)
            .takeIf { it.isFinite() && it in 0.0..100.0 }
            ?.toFloat()
    } else {
        null
    }
    return BusyPercentParse(percent = percent, nextCounter = current)
}

internal fun parseDirectUtilizationPercent(text: String): Float? {
    val match = DIRECT_PERCENT_LINE.matchEntire(text) ?: return null
    val value = match.groupValues[1].toDoubleOrNull()?.takeIf(Double::isFinite) ?: return null
    if (value < 0.0 || value > 100.0) return null
    return value.toFloat()
}

/**
 * Parses Qualcomm KGSL's gpubusy sample. The pair describes one already-bounded
 * measurement window, so it must be divided directly instead of differenced across polls.
 */
internal fun parseWindowBusyTotalPercent(text: String): Float? {
    val numbers = parseExactIntegerTokens(text, expectedCount = 2) ?: return null
    val busy = numbers[0]
    val total = numbers[1]
    if (busy == 0L && total == 0L) return 0f
    if (busy < 0L || total <= 0L || busy > total) return null
    return (busy.toDouble() * 100.0 / total.toDouble())
        .takeIf { it.isFinite() && it in 0.0..100.0 }
        ?.toFloat()
}

/**
 * MediaTek GED exposes three independent percentages in loading/blocking/idle order.
 * Loading is the GPU busy value. All fields are range-checked so a different node format
 * cannot silently look like a plausible percentage.
 */
internal fun parseMtkGpuUtilizationPercent(text: String): Float? {
    val numbers = parseExactIntegerTokens(text, expectedCount = 3) ?: return null
    if (numbers.any { it !in 0L..100L }) return null
    return numbers[0].toFloat()
}

/** Parses MediaTek GED's historical "<OPP index> <frequency kHz>" node. */
internal fun parseIndexedGpuFrequency(text: String): Long? {
    val numbers = parseExactIntegerTokens(text, expectedCount = 2) ?: return null
    val index = numbers[0]
    val frequency = numbers[1]
    if (index < 0L || frequency < 0L) return null
    return frequency
}

private fun parseExactIntegerTokens(text: String, expectedCount: Int): List<Long>? {
    val scalar = text.trim()
    if (scalar.isEmpty()) return null
    val tokens = scalar.split(WHITESPACE)
    if (tokens.size != expectedCount) return null
    if (tokens.any { !SIGNED_INTEGER_SCALAR.matches(it) }) return null
    return tokens.map { it.toLongOrNull() ?: return null }
}

internal fun parseSingleLongToken(text: String): Long? {
    val scalar = text.trim()
    if (!SIGNED_INTEGER_SCALAR.matches(scalar)) return null
    return scalar.toLongOrNull()
}

internal fun parseCustomProbeConfig(lines: List<String>): Map<String, String> {
    if (lines.size > MAX_CUSTOM_CONFIG_LINES || lines.any { it.length > MAX_CUSTOM_CONFIG_LINE_CHARS }) {
        return emptyMap()
    }
    return buildMap {
        lines.forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || "=" !in line) return@forEach
            val (rawKey, rawValue) = line.split("=", limit = 2)
            val key = rawKey.trim()
            val value = rawValue.trim()
            if (
                key in ALLOWED_PROBE_KEYS &&
                value.length in 1..MAX_CUSTOM_PATH_CHARS &&
                (value.startsWith("/sys/") || value.startsWith("/proc/")) &&
                value.split('/').none { it == ".." } &&
                '\u0000' !in value
            ) {
                put(key, value)
            }
        }
    }
}

internal fun Float.validUtilizationPercent(): Float? = takeIf { isFinite() && this in 0f..100f }

internal fun Long.validDpuFrequencyHz(): Long? = takeIf { this in 0L..MAX_DPU_FREQUENCY_HZ }

internal enum class ProbeFrequencyUnit {
    HZ,
    KHZ,
    MHZ,
}

internal data class ConfiguredGpuFrequencyProbe(
    val path: String,
    val unit: ProbeFrequencyUnit,
    val format: GpuFrequencyProbeFormat = GpuFrequencyProbeFormat.SCALAR,
)

internal fun ConfiguredGpuFrequencyProbe.asProbe(): GpuFrequencyProbe =
    GpuFrequencyProbe(path = path, unit = unit, format = format)

internal fun configuredGpuFrequencyProbes(
    paths: Map<String, String>,
): List<ConfiguredGpuFrequencyProbe> = GPU_FREQUENCY_CONFIG_KEYS.mapNotNull { definition ->
    val (key, unit, format) = definition
    paths[key]?.takeIf { it.isNotBlank() }?.let { path ->
        ConfiguredGpuFrequencyProbe(path = path, unit = unit, format = format)
    }
}

internal fun configuredGpuBusyProbes(paths: Map<String, String>): List<GpuBusyProbe> =
    GPU_BUSY_CONFIG_KEYS.mapNotNull { (key, format) ->
        paths[key]?.takeIf { it.isNotBlank() }?.let { path ->
            GpuBusyProbe(path = path, format = format)
        }
    }

/**
 * An explicit product probe is authoritative: no generic fallback is appended. Multiple typed
 * declarations are contradictory and therefore unavailable.
 */
internal fun selectGpuBusyProbes(
    configured: List<GpuBusyProbe>,
    defaults: List<GpuBusyProbe>,
): List<GpuBusyProbe>? = when (configured.size) {
    0 -> defaults
    1 -> configured
    else -> null
}

internal fun selectGpuFrequencyProbes(
    configured: List<ConfiguredGpuFrequencyProbe>,
    defaults: List<GpuFrequencyProbe>,
): List<GpuFrequencyProbe>? = when (configured.size) {
    0 -> defaults
    1 -> configured.map { it.asProbe() }
    else -> null
}

internal fun authoritativeProbePaths(
    explicit: String?,
    defaults: List<String>,
): List<String> =
    explicit?.takeIf { it.isNotBlank() }?.let(::listOf) ?: defaults

internal fun gpuBusyUnavailableSource(paths: Map<String, String>): String {
    val configured = configuredGpuBusyProbes(paths)
    return when (configured.size) {
        0 -> GPU_BUSY_UNAVAILABLE_SOURCE
        1 -> "${busyProbeSource(configured.single().path, configured.single().format)} " +
            "[unavailable-or-malformed]"
        else -> "conflicting GPU busy probes: " +
            configured.joinToString(" | ") { busyProbeSource(it.path, it.format) }
    }.take(MAX_PROBE_SOURCE_CHARS)
}

internal fun gpuFrequencyUnavailableSource(paths: Map<String, String>): String {
    val configured = configuredGpuFrequencyProbes(paths)
    return when (configured.size) {
        0 -> GPU_FREQUENCY_UNAVAILABLE_SOURCE
        1 -> configured.single().let {
            "${frequencyProbeSource(it.path, it.unit, it.format)} " +
                "[unavailable-or-malformed]"
        }
        else -> "conflicting GPU frequency probes: " +
            configured.joinToString(" | ") {
                frequencyProbeSource(it.path, it.unit, it.format)
            }
    }.take(MAX_PROBE_SOURCE_CHARS)
}

internal fun unavailableExplicitProbeSource(
    label: String,
    explicitPath: String?,
    genericSource: String,
): String =
    explicitPath
        ?.takeIf { it.isNotBlank() }
        ?.let { "$label $it [unavailable-or-malformed]" }
        ?.take(MAX_PROBE_SOURCE_CHARS)
        ?: genericSource

internal fun normalizeGpuFrequencyMhz(rawValue: Long, unit: ProbeFrequencyUnit): Float? {
    if (rawValue < 0L) return null
    val mhz = when (unit) {
        ProbeFrequencyUnit.HZ -> rawValue.toDouble() / HZ_PER_MHZ
        ProbeFrequencyUnit.KHZ -> rawValue.toDouble() / KHZ_PER_MHZ
        ProbeFrequencyUnit.MHZ -> rawValue.toDouble()
    }
    return mhz
        .takeIf { it.isFinite() && it in 0.0..MAX_PLAUSIBLE_GPU_FREQUENCY_MHZ }
        ?.toFloat()
}

internal fun busyProbeSource(path: String, format: GpuBusyProbeFormat): String =
    "$path [format=${format.name}]"

internal fun observedBusySource(path: String, encoding: BusyObservedEncoding): String =
    "$path [observed=${encoding.name}]"

internal fun legacyObservedBusyProbeSource(
    path: String,
    encoding: BusyObservedEncoding,
): String =
    "$path [format=${GpuBusyProbeFormat.LEGACY_DIRECT_OR_CUMULATIVE.name}," +
        "observed=${encoding.name}]"

internal fun frequencyProbeSource(
    path: String,
    unit: ProbeFrequencyUnit,
    format: GpuFrequencyProbeFormat = GpuFrequencyProbeFormat.SCALAR,
): String = "$path [input=${unit.name},format=${format.name}]"

private fun readBoundedFirstLine(reader: java.io.BufferedReader): String? {
    val result = StringBuilder()
    repeat(MAX_SENSOR_LINE_CHARS + 1) {
        when (val character = reader.read()) {
            -1 -> return result.toString()
            '\n'.code, '\r'.code -> return result.toString()
            else -> result.append(character.toChar())
        }
    }
    return null
}

private val SIGNED_INTEGER_SCALAR = Regex("""[+-]?\d+""")
private val WHITESPACE = Regex("""\s+""")
private val DIRECT_PERCENT_LINE = Regex(
    """^\s*([+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?)\s*%?\s*$""",
)
private val ALLOWED_PROBE_KEYS = setOf(
    "gpu_busy",
    "gpu_busy_percent",
    "gpu_busy_window",
    "gpu_busy_mtk_triplet",
    // Legacy gpu_frequency has one fixed contract: Hz. Typed keys are preferred.
    "gpu_frequency",
    "gpu_frequency_hz",
    "gpu_frequency_khz",
    "gpu_frequency_mhz",
    "gpu_frequency_index_khz",
    "bus_busy",
    "dpu_busy",
    "dpu_frequency_hz",
    "dpu_underrun",
)
private const val MAX_CUSTOM_CONFIG_LINES = 128
private const val MAX_CUSTOM_CONFIG_LINE_CHARS = 1_024
private const val MAX_CUSTOM_PATH_CHARS = 512
private const val MAX_PROBE_SOURCE_CHARS = 640
private const val MAX_SENSOR_LINE_CHARS = 4_096
private const val MAX_DPU_FREQUENCY_HZ = 20_000_000_000L
private const val MAX_PLAUSIBLE_GPU_FREQUENCY_MHZ = 20_000.0
private const val HZ_PER_MHZ = 1_000_000.0
private const val KHZ_PER_MHZ = 1_000.0
private const val GPU_BUSY_UNAVAILABLE_SOURCE =
    "validated kernel GPU utilization probe unavailable; vendor API v2 or typed probe required"
private const val GPU_FREQUENCY_UNAVAILABLE_SOURCE =
    "validated kernel GPU frequency probe unavailable; vendor API v2 or typed probe required"
private const val BUS_BUSY_UNAVAILABLE_SOURCE =
    "validated memory-bus utilization source unavailable; vendor adapter or typed probe required"
private const val DPU_BUSY_UNAVAILABLE_SOURCE =
    "validated DPU utilization source unavailable; product vendor adapter required"
private const val DPU_FREQUENCY_UNAVAILABLE_SOURCE =
    "validated DPU frequency source unavailable; vendor API v2 or typed Hz probe required"
private val GPU_BUSY_CONFIG_KEYS = listOf(
    "gpu_busy_percent" to GpuBusyProbeFormat.DIRECT_PERCENT,
    "gpu_busy_window" to GpuBusyProbeFormat.WINDOW_BUSY_TOTAL,
    "gpu_busy_mtk_triplet" to GpuBusyProbeFormat.MTK_LOADING_BLOCKING_IDLE,
    // Preserve the original product contract: a scalar is direct and a pair is cumulative.
    "gpu_busy" to GpuBusyProbeFormat.LEGACY_DIRECT_OR_CUMULATIVE,
)
private val GPU_FREQUENCY_CONFIG_KEYS = listOf(
    Triple("gpu_frequency_hz", ProbeFrequencyUnit.HZ, GpuFrequencyProbeFormat.SCALAR),
    Triple("gpu_frequency_khz", ProbeFrequencyUnit.KHZ, GpuFrequencyProbeFormat.SCALAR),
    Triple("gpu_frequency_mhz", ProbeFrequencyUnit.MHZ, GpuFrequencyProbeFormat.SCALAR),
    Triple(
        "gpu_frequency_index_khz",
        ProbeFrequencyUnit.KHZ,
        GpuFrequencyProbeFormat.INDEX_AND_FREQUENCY,
    ),
    Triple("gpu_frequency", ProbeFrequencyUnit.HZ, GpuFrequencyProbeFormat.SCALAR),
)
