package com.example.dpulayerlab.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DpuLayerLabAppMathTest {
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
}
