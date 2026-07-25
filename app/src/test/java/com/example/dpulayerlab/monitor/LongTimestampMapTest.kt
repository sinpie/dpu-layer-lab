package com.example.dpulayerlab.monitor

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LongTimestampMapTest {
    @Test
    fun ninthAndSeventeenthEntriesGrowBothArraysThroughTheSameBoundaries() {
        val expansionSizes = mutableListOf<Int>()
        val map = LongTimestampMap(
            expansionAllocator = LongArrayExpansionAllocator { source, newSize ->
                expansionSizes += newSize
                source.copyOf(newSize)
            },
        )

        for (id in 1L..20L) {
            map.put(id, id * 10L)
        }

        assertEquals(listOf(16, 16, 32, 32), expansionSizes)
        assertArrayEquals((1L..20L).toList().toLongArray(), map.idsCopy())
        for (id in 1L..20L) {
            assertEquals(id * 10L, map.valueOr(id, -1L))
        }
    }

    @Test
    fun secondExpansionAllocationFailurePreservesThePreviousMapAtomically() {
        val failure = OutOfMemoryError("injected timestamp expansion OOME")
        var copyCall = 0
        val map = LongTimestampMap(
            expansionAllocator = LongArrayExpansionAllocator { source, newSize ->
                copyCall++
                if (copyCall == 2) throw failure
                source.copyOf(newSize)
            },
        )
        for (id in 1L..8L) {
            map.put(id, id * 100L)
        }

        var thrown: Throwable? = null
        try {
            map.put(9L, 900L)
        } catch (error: Throwable) {
            thrown = error
        }

        assertSame(failure, thrown)
        assertArrayEquals((1L..8L).toList().toLongArray(), map.idsCopy())
        assertFalse(map.contains(9L))
        for (id in 1L..8L) {
            assertEquals(id * 100L, map.valueOr(id, -1L))
        }

        // The failed tentative ID array was never committed, so a later healthy retry can grow
        // both arrays from the same eight-entry state.
        map.put(9L, 900L)
        assertTrue(map.contains(9L))
        assertEquals(900L, map.valueOr(9L, -1L))
    }

    @Test
    fun twentyProducerCalibrationCandidateRetainsEveryHeartbeat() {
        val gate = ProducerGenerationGate()
        val generation = gate.begin(nowMs = 0L)
        val producerIds = (1L..20L).toCollection(linkedSetOf())

        assertTrue(gate.expect(generation, producerIds, nowMs = 10L))
        assertTrue(gate.activate(generation, nowMs = 20L))
        producerIds.forEachIndexed { index, producerId ->
            assertTrue(
                gate.accept(
                    candidate = generation,
                    producerId = producerId,
                    nowMs = 100L + index,
                ),
            )
        }

        val readiness = gate.readiness(generation, nowMs = 200L)
        assertEquals(20, readiness.expectedCount)
        assertEquals(20, readiness.observedCount)
        assertEquals(20, readiness.everObservedCount)
        assertTrue(readiness.ready)
    }
}
