package com.example.dpulayerlab.engine

import com.example.dpulayerlab.model.DeviceIdentity
import com.example.dpulayerlab.model.Gauge
import com.example.dpulayerlab.model.MetricQuality
import com.example.dpulayerlab.model.RunSummary
import com.example.dpulayerlab.model.RunVerdict
import com.example.dpulayerlab.model.TelemetrySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportWriterMathTest {
    @Test
    fun nonFiniteFloatsNeverProduceInvalidJsonTokens() {
        assertEquals("null", jsonNumber(null))
        assertEquals("null", jsonNumber(Float.NaN))
        assertEquals("null", jsonNumber(Float.POSITIVE_INFINITY))
        assertEquals("null", jsonNumber(Float.NEGATIVE_INFINITY))
        assertEquals("1.25", jsonNumber(1.25f))
    }

    @Test
    fun retentionKeepsProtectedAndNewestCompletedReportsOnly() {
        val entries = listOf(
            ReportRetentionEntry("dpu-layer-lab-20260724-010101-001-old.json", lastModifiedMs = 10L),
            ReportRetentionEntry(
                "dpu-layer-lab-20260724-010102-001-middle.json",
                lastModifiedMs = 20L,
            ),
            // A skewed timestamp must never allow retention to remove the file just published.
            ReportRetentionEntry(
                "dpu-layer-lab-20260724-010103-001-just-written.json",
                lastModifiedMs = 1L,
            ),
            ReportRetentionEntry("dpu-layer-lab-20260724-010104-001-new.json", lastModifiedMs = 30L),
            ReportRetentionEntry("crashed.json.part", lastModifiedMs = 40L),
            ReportRetentionEntry("notes.txt", lastModifiedMs = 50L),
            ReportRetentionEntry("notes.json", lastModifiedMs = 0L),
        )

        assertEquals(
            setOf(
                "dpu-layer-lab-20260724-010101-001-old.json",
                "dpu-layer-lab-20260724-010102-001-middle.json",
            ),
            selectCompletedReportsForDeletion(
                entries = entries,
                protectedReportName =
                    "dpu-layer-lab-20260724-010103-001-just-written.json",
                keepCount = 2,
            ),
        )
    }

    @Test
    fun retentionOrderingIsDeterministicAndTemporaryFilesAreUntouched() {
        val entries = listOf(
            ReportRetentionEntry("dpu-layer-lab-20260724-010101-001-a.json", lastModifiedMs = 10L),
            ReportRetentionEntry("dpu-layer-lab-20260724-010101-001-b.json", lastModifiedMs = 10L),
            ReportRetentionEntry("pending.json.part", lastModifiedMs = Long.MAX_VALUE),
        )

        assertEquals(
            setOf("dpu-layer-lab-20260724-010101-001-a.json"),
            selectCompletedReportsForDeletion(
                entries = entries,
                protectedReportName = "dpu-layer-lab-20260724-010101-001-b.json",
                keepCount = 1,
            ),
        )
    }

    @Test
    fun retentionRecognizesOnlyAppOwnedCompletedReportNames() {
        assertTrue(
            isManagedCompletedReportName(
                "dpu-layer-lab-20260724-010101-001-transform-2.json",
            ),
        )
        assertFalse(isManagedCompletedReportName("notes.json"))
        assertFalse(
            isManagedCompletedReportName(
                "dpu-layer-lab-20260724-010101-001-transform.json.part",
            ),
        )
        assertFalse(
            isManagedCompletedReportName(
                "dpu-layer-lab-20260724-010101-001-../notes.json",
            ),
        )
    }

    @Test
    fun retentionReservesTheJustPublishedSlotWhenDirectoryListingOmitsIt() {
        val first = "dpu-layer-lab-20260724-010101-001-first.json"
        val second = "dpu-layer-lab-20260724-010102-001-second.json"
        val omittedProtected = "dpu-layer-lab-20260724-010103-001-protected.json"

        assertEquals(
            setOf(first),
            selectCompletedReportsForDeletion(
                entries = listOf(
                    ReportRetentionEntry(first, lastModifiedMs = 1L),
                    ReportRetentionEntry(second, lastModifiedMs = 2L),
                ),
                protectedReportName = omittedProtected,
                keepCount = 2,
            ),
        )
    }

    @Test
    fun reportQualityCannotClaimUnavailableOrNonFiniteGaugeAsMeasured() {
        assertEquals(
            MetricQuality.UNAVAILABLE,
            reportQuality(Gauge(null, "%", MetricQuality.MEASURED, "counter")),
        )
        assertEquals(
            MetricQuality.UNAVAILABLE,
            reportQuality(Gauge(Float.NaN, "%", MetricQuality.MEASURED, "counter")),
        )
        assertEquals(
            MetricQuality.UNAVAILABLE,
            reportQuality(Gauge(1f, "%", MetricQuality.MEASURED, "")),
        )
        assertEquals(
            MetricQuality.MEASURED,
            reportQuality(Gauge(1f, "%", MetricQuality.MEASURED, "counter")),
        )
    }

    @Test
    fun sampleProvenanceSurvivesSourceChangesAndNonFiniteValues() {
        val vendorSample = TelemetrySnapshot(
            monotonicMs = 10L,
            cpu = Gauge(Float.NaN, "%", MetricQuality.MEASURED, "/proc/stat"),
            appCpu = Gauge(18f, "%", MetricQuality.MEASURED, "Process CPU clock"),
            memoryUsed = Gauge(42f, "%", MetricQuality.SYSTEM_SERVICE, "ActivityManager"),
            memoryAvailable = Gauge(2_048f, " MB", MetricQuality.SYSTEM_SERVICE, "ActivityManager"),
            appPss = Gauge(128f, " MB", MetricQuality.MEASURED, "Debug.getPss"),
            displayHz = Gauge(120f, " Hz", MetricQuality.SYSTEM_SERVICE, "Display"),
            producedFps = Gauge(118f, " fps", MetricQuality.MEASURED, "BufferQueue"),
            missedFrames = 3L,
            suspectedUnderruns = 3L,
            suspectedUnderrunQuality = MetricQuality.PROXY,
            suspectedUnderrunSource = "FrameTracker test proxy",
            gpuBusy = Gauge(61f, "%", MetricQuality.KERNEL, "/sys/gpu_busy"),
            gpuFrequency = Gauge(900f, " MHz", MetricQuality.KERNEL, "/sys/gpu_clock"),
            busBusy = Gauge(72f, "%", MetricQuality.HARDWARE_COUNTER, "vendor bus"),
            dpuBusy = Gauge(
                81f,
                "%",
                MetricQuality.HARDWARE_COUNTER,
                "IDpuLabVendorService",
            ),
            dpuFrequency = Gauge(600f, " MHz", MetricQuality.KERNEL, "/sys/dpu_clock"),
            hwcDeviceLayers = 6,
            hwcDeviceLayersQuality = MetricQuality.HARDWARE_COUNTER,
            hwcDeviceLayersSource = "IDpuLabVendorService",
            hwcClientLayers = 2,
            hwcClientLayersQuality = MetricQuality.SYSTEM_SERVICE,
            hwcClientLayersSource = "SurfaceFlinger",
            surfaceFlingerHwcMissed = 1L,
            surfaceFlingerMissSource = "SurfaceFlinger latency",
            generatedBandwidth = Gauge(
                8.5f,
                " Gbps",
                MetricQuality.MEASURED,
                "memory load generator",
            ),
            powerSaveMode = true,
        )
        val kernelSample = vendorSample.copy(
            monotonicMs = 20L,
            cpu = Gauge(55f, "%", MetricQuality.SYSTEM_SERVICE, "HardwarePropertiesManager"),
            dpuBusy = Gauge(47f, "%", MetricQuality.KERNEL, "/sys/class/dpu/busy"),
            hwcDeviceLayers = 5,
            hwcDeviceLayersQuality = MetricQuality.SYSTEM_SERVICE,
            hwcDeviceLayersSource = "SurfaceFlinger",
        )
        val summary = RunSummary(
            scenario = ScenarioCatalog.presets.first(),
            startedEpochMs = 1_000L,
            finishedEpochMs = 2_000L,
            verdict = RunVerdict.SUSPECTED_PROXY,
            exactUnderrunDelta = null,
            exactUnderrunSource = null,
            exactUnderrunQuality = MetricQuality.UNAVAILABLE,
            suspectedUnderrunDelta = 3L,
            peakCpu = 55f,
            peakMemoryUsed = 42f,
            peakGeneratedBandwidth = 8.5f,
            events = emptyList(),
            samples = listOf(vendorSample, kernelSample),
        )

        val json = ReportWriter.toJson(summary, TEST_DEVICE)

        assertFalse(json.contains("NaN"))
        assertFalse(json.contains("Infinity"))
        assertTrue(json.contains(""""cpu": null"""))
        assertTrue(json.contains(""""cpuQuality": "UNAVAILABLE""""))
        assertTrue(json.contains(""""appCpuSource": "Process CPU clock""""))
        assertTrue(json.contains(""""memoryUsedSource": "ActivityManager""""))
        assertTrue(json.contains(""""memoryAvailableSource": "ActivityManager""""))
        assertTrue(json.contains(""""appPssSource": "Debug.getPss""""))
        assertTrue(json.contains(""""producedFpsSource": "BufferQueue""""))
        assertTrue(json.contains(""""missedFramesQuality": "PROXY""""))
        assertTrue(json.contains(""""missedFramesSource": "FrameTracker test proxy""""))
        assertTrue(json.contains(""""suspectedUnderrunQuality": "PROXY""""))
        assertTrue(json.contains(""""suspectedUnderrunSource": "FrameTracker test proxy""""))
        assertTrue(json.contains(""""dpuBusyQuality": "HARDWARE_COUNTER""""))
        assertTrue(json.contains(""""dpuBusySource": "IDpuLabVendorService""""))
        assertTrue(json.contains(""""dpuBusyQuality": "KERNEL""""))
        assertTrue(json.contains(""""dpuBusySource": "/sys/class/dpu/busy""""))
        assertTrue(json.contains(""""hwcDeviceLayersSource": "IDpuLabVendorService""""))
        assertTrue(json.contains(""""hwcClientLayersSource": "SurfaceFlinger""""))
        assertTrue(json.contains(""""powerSaveMode": true"""))
        assertTrue(
            json.contains(
                """"dpu": {"quality":"KERNEL","source":"/sys/class/dpu/busy"}""",
            ),
        )
        assertTrue(
            json.indexOf(""""dpuBusySource": "IDpuLabVendorService"""") <
                json.indexOf(""""dpuBusySource": "/sys/class/dpu/busy""""),
        )
    }

    private companion object {
        val TEST_DEVICE = DeviceIdentity(
            manufacturer = "test",
            model = "test",
            device = "test",
            sdk = 36,
            release = "test",
            fingerprint = "test",
        )
    }
}
