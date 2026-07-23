package com.example.dpulayerlab.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LabControllerMathTest {
    @Test
    fun counterDeltaRequiresStableSourceAndMonotonicValue() {
        assertEquals(
            7L,
            monotonicCounterDelta(10L, "vendor", 17L, "vendor"),
        )
        assertNull(monotonicCounterDelta(10L, "vendor", 17L, "sysfs"))
        assertNull(monotonicCounterDelta(10L, "vendor", 9L, "vendor"))
        assertNull(monotonicCounterDelta(null, "vendor", 17L, "vendor"))
        assertNull(monotonicCounterDelta(10L, null, 17L, null))
    }
}
