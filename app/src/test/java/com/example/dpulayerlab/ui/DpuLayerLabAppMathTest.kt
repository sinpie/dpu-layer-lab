package com.example.dpulayerlab.ui

import com.example.dpulayerlab.model.Gauge
import com.example.dpulayerlab.model.MetricQuality
import org.junit.Assert.assertEquals
import org.junit.Test

class DpuLayerLabAppMathTest {
    @Test
    fun visibleVersionKeepsReleaseTimestampAndVariantSuffix() {
        assertEquals(
            "BUILD 20260724_111816-debug",
            visibleAppVersion("20260724_111816-debug"),
        )
    }

    @Test
    fun visibleVersionIsBoundedAndNeverBlank() {
        assertEquals("BUILD N/A", visibleAppVersion("   "))
        assertEquals("BUILD ${"v".repeat(64)}", visibleAppVersion("v".repeat(128)))
    }

    @Test
    fun producerCountShowsPendingUntilExpectedTopologyIsCommitted() {
        assertEquals("0/\u2014P", producerCountDisplay(observed = 0, expected = 0))
        assertEquals("2/\u2014P", producerCountDisplay(observed = 2, expected = 0))
        assertEquals("2/4P", producerCountDisplay(observed = 2, expected = 4))
    }

    @Test
    fun producerCountDoesNotExposeInvalidNegativeCounts() {
        assertEquals("0/\u2014P", producerCountDisplay(observed = -1, expected = -1))
    }

    @Test
    fun gaugeHistoryConnectsOnlyMatchingProvenance() {
        val samples = listOf(
            Gauge(10f, "%", MetricQuality.HARDWARE_COUNTER, "vendor dpu"),
            Gauge(20f, "%", MetricQuality.HARDWARE_COUNTER, "vendor dpu"),
            Gauge(30f, "%", MetricQuality.KERNEL, "sysfs busy"),
            Gauge(40f, "%", MetricQuality.KERNEL, "sysfs busy"),
        )

        assertEquals(listOf(10f, 20f, null, 40f), segmentedGaugeHistory(samples))
    }

    @Test
    fun gaugeHistoryPreservesUnavailableGapAndTimeAxis() {
        val samples = listOf(
            Gauge(10f, "%", MetricQuality.SYSTEM_SERVICE, "cpu service"),
            Gauge(null, "%", MetricQuality.UNAVAILABLE, ""),
            Gauge(30f, "%", MetricQuality.SYSTEM_SERVICE, "cpu service"),
        )

        assertEquals(listOf(10f, null, 30f), segmentedGaugeHistory(samples))
    }

    @Test
    fun gaugeProvenanceLabelIsBoundedAndNeverInventsUnavailableSource() {
        assertEquals("N/A", gaugeProvenanceLabel(Gauge()))
        assertEquals(
            "HW · ${"x".repeat(18)}",
            gaugeProvenanceLabel(
                Gauge(
                    value = 1f,
                    quality = MetricQuality.HARDWARE_COUNTER,
                    source = "x".repeat(64),
                ),
            ),
        )
    }
}
