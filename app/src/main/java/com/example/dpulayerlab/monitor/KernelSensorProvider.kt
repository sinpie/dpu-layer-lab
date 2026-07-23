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

        val gpuBusy = readBusyPercent(
            explicit = paths["gpu_busy"],
            candidates = listOf(
                "/sys/class/kgsl/kgsl-3d0/gpubusy",
                "/sys/class/misc/mali0/device/utilization",
                "/sys/class/misc/mali0/device/gpu_busy",
            ),
            previous = lastGpuBusyCounter,
        )
        if (gpuBusy != null) lastGpuBusyCounter = gpuBusy.nextCounter

        val gpuFreq = readGpuFrequencyMhz(paths)
        val busBusy = readBusyPercent(
            explicit = paths["bus_busy"],
            candidates = listOf(
                "/sys/class/devfreq/17000010.devfreq_mif/load",
                "/sys/class/devfreq/dmc/load",
            ),
            previous = lastBusBusyCounter,
        )
        if (busBusy != null) lastBusBusyCounter = busBusy.nextCounter

        val dpuBusy = readBusyPercent(
            explicit = paths["dpu_busy"],
            candidates = listOf(
                "/sys/class/dpu/dpu0/utilization",
                "/sys/class/dpu/dpu0/busy_percent",
                "/sys/class/drm/card0/device/dpu_busy",
            ),
            previous = lastDpuBusyCounter,
        )
        if (dpuBusy != null) lastDpuBusyCounter = dpuBusy.nextCounter
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
        } ?: Gauge()
        val gpuFreqGauge = gpuFreq?.let { (mhz, path) ->
            readings += SensorReading("gpu_frequency", "GPU clock", "%.0f MHz".format(mhz), MetricQuality.KERNEL, path)
            Gauge(mhz, " MHz", MetricQuality.KERNEL, path)
        } ?: Gauge()
        val busGauge = busBusy?.percent?.let { (value, path) ->
            readings += SensorReading("bus_busy", "Memory bus", "$value%", MetricQuality.KERNEL, path)
            Gauge(value, "%", MetricQuality.KERNEL, path)
        } ?: Gauge()
        val dpuGauge = dpuBusy?.percent?.let { (value, path) ->
            readings += SensorReading("dpu_busy", "DPU busy", "$value%", MetricQuality.KERNEL, path)
            Gauge(value, "%", MetricQuality.KERNEL, path)
        } ?: Gauge()
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
        } ?: Gauge()
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
        val result = readFirstText(explicit, candidates) ?: return null
        parseDirectUtilizationPercent(result.first)?.let { direct ->
            return BusySensorResult(
                percent = direct to result.second,
                nextCounter = null,
            )
        }
        val parsed = parseBusyPercent(result.first, result.second, previous) ?: return null
        return BusySensorResult(
            percent = parsed.percent?.let { it to result.second },
            nextCounter = parsed.nextCounter,
        )
    }

    private fun readFirstLong(explicit: String?, candidates: List<String>): Pair<Long, String>? {
        val result = readFirstText(explicit, candidates) ?: return null
        val value = parseSingleLongToken(result.first) ?: return null
        return value to result.second
    }

    private fun readGpuFrequencyMhz(paths: Map<String, String>): Pair<Float, String>? {
        val configured = configuredGpuFrequencyProbes(paths)
        val raw = when (configured.size) {
            0 -> {
                val reading = readFirstLong(
                    explicit = null,
                    candidates = DEFAULT_GPU_FREQUENCY_HZ_PATHS,
                ) ?: return null
                Triple(reading.first, reading.second, ProbeFrequencyUnit.HZ)
            }
            1 -> {
                val probe = configured.single()
                val reading = readFirstLong(
                    explicit = probe.path,
                    candidates = emptyList(),
                ) ?: return null
                Triple(reading.first, reading.second, probe.unit)
            }
            // Multiple unit declarations are contradictory even when they point at one path.
            // Guessing here would silently publish a plausible but wrong clock.
            else -> return null
        }
        val mhz = normalizeGpuFrequencyMhz(raw.first, raw.third) ?: return null
        return mhz to frequencyProbeSource(raw.second, raw.third)
    }

    private fun readFirstText(explicit: String?, candidates: List<String>): Pair<String, String>? {
        val paths = buildList {
            if (!explicit.isNullOrBlank()) add(explicit)
            addAll(candidates)
        }
        paths.forEach { path ->
            val file = File(path)
            if (file.canRead() && file.isFile) {
                val value = runCatching {
                    file.bufferedReader().use(::readBoundedFirstLine)
                }.getOrNull()
                if (!value.isNullOrBlank()) return value.trim() to path
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

    private companion object {
        const val MAX_CONFIG_BYTES = 64L * 1_024L
        val DEFAULT_GPU_FREQUENCY_HZ_PATHS = listOf(
            "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",
            "/sys/class/kgsl/kgsl-3d0/gpuclk",
            "/sys/class/misc/mali0/device/devfreq/devfreq0/cur_freq",
        )
    }
}

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
    val tokens = INTEGER_TOKEN.findAll(text).take(3).map { it.value }.toList()
    if (tokens.isEmpty() || tokens.size > 2) return null
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
    val tokens = FLOAT_TOKEN.findAll(text).take(2).map { it.value }.toList()
    if (tokens.size != 1) return null
    val value = tokens.single().toDoubleOrNull()?.takeIf(Double::isFinite) ?: return null
    if (value < 0.0 || value > 100.0) return null
    return value.toFloat()
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
)

internal fun configuredGpuFrequencyProbes(
    paths: Map<String, String>,
): List<ConfiguredGpuFrequencyProbe> = GPU_FREQUENCY_CONFIG_KEYS.mapNotNull { (key, unit) ->
    paths[key]?.takeIf { it.isNotBlank() }?.let { path ->
        ConfiguredGpuFrequencyProbe(path = path, unit = unit)
    }
}

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

internal fun frequencyProbeSource(path: String, unit: ProbeFrequencyUnit): String =
    "$path [input=${unit.name}]"

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

private val INTEGER_TOKEN = Regex("""(?<![\d.])-?\d+(?![\d.])""")
private val SIGNED_INTEGER_SCALAR = Regex("""[+-]?\d+""")
private val FLOAT_TOKEN = Regex("""(?<![\w.])-?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?(?![\w.])""")
private val ALLOWED_PROBE_KEYS = setOf(
    "gpu_busy",
    // Legacy gpu_frequency has one fixed contract: Hz. Typed keys are preferred.
    "gpu_frequency",
    "gpu_frequency_hz",
    "gpu_frequency_khz",
    "gpu_frequency_mhz",
    "bus_busy",
    "dpu_busy",
    "dpu_frequency_hz",
    "dpu_underrun",
)
private const val MAX_CUSTOM_CONFIG_LINES = 128
private const val MAX_CUSTOM_CONFIG_LINE_CHARS = 1_024
private const val MAX_CUSTOM_PATH_CHARS = 512
private const val MAX_SENSOR_LINE_CHARS = 4_096
private const val MAX_DPU_FREQUENCY_HZ = 20_000_000_000L
private const val MAX_PLAUSIBLE_GPU_FREQUENCY_MHZ = 20_000.0
private const val HZ_PER_MHZ = 1_000_000.0
private const val KHZ_PER_MHZ = 1_000.0
private val GPU_FREQUENCY_CONFIG_KEYS = listOf(
    "gpu_frequency_hz" to ProbeFrequencyUnit.HZ,
    "gpu_frequency_khz" to ProbeFrequencyUnit.KHZ,
    "gpu_frequency_mhz" to ProbeFrequencyUnit.MHZ,
    "gpu_frequency" to ProbeFrequencyUnit.HZ,
)
