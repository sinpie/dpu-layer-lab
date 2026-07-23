package com.example.dpulayerlab.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.StringReader

class SurfaceFlingerProbeTest {
    @Test
    fun boundedDumpReaderRejectsOversizedOutput() {
        assertEquals("abcd", readBoundedText(StringReader("abcd"), 4))
        assertThrows(IllegalStateException::class.java) {
            readBoundedText(StringReader("abcde"), 4)
        }
    }

    @Test
    fun parsesMultilineCompositionBlocks() {
        val dump = """
            Total missed frame count: 15
            HWC missed frame count: 11
            GPU missed frame count: 4
            Composition layers
            * Layer 0x1 (com.example.dpulayerlab/MainActivity#10)
                  geomLayerBounds=[0 0 100 100]
                  composition type=DEVICE (2)
            * Layer 0x2 (SurfaceView[com.example.dpulayerlab/MainActivity]#11)
                  geomLayerBounds=[0 0 100 100]
                  composition type=CLIENT (1)
            * Layer 0x3 (com.android.systemui#12)
                  composition type=DEVICE (2)
        """.trimIndent()

        val parsed = parseSurfaceFlingerDump(dump)

        assertEquals(1, parsed.deviceLayers)
        assertEquals(1, parsed.clientLayers)
        assertEquals(11L, parsed.hwcMissedFrames)
        assertEquals(4L, parsed.gpuMissedFrames)
    }

    @Test
    fun unavailableAppLayersRemainNull() {
        val parsed = parseSurfaceFlingerDump("HWC missed frame count: 8")
        assertNull(parsed.deviceLayers)
        assertNull(parsed.clientLayers)
        assertEquals(8L, parsed.hwcMissedFrames)
    }

    @Test
    fun overflowingMissedCountersRemainUnavailable() {
        val parsed = parseSurfaceFlingerDump(
            """
                HWC missed frame count: 9223372036854775808
                GPU missed frame count: 9223372036854775809
            """.trimIndent(),
        )

        assertNull(parsed.hwcMissedFrames)
        assertNull(parsed.gpuMissedFrames)
    }
}
