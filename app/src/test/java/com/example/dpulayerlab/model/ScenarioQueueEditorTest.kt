package com.example.dpulayerlab.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ScenarioQueueEditorTest {
    @Test
    fun appendPreservesDuplicatesAndOrder() {
        val queue = ScenarioQueueEditor.append(listOf("a", "b"), "a")

        assertEquals(listOf("a", "b", "a"), queue)
    }

    @Test
    fun appendRejectsBlankAndHardLimitWithoutInvalidatingState() {
        val queue = listOf("a", "b")

        assertSame(queue, ScenarioQueueEditor.append(queue, "", maximumItems = 4))
        assertSame(queue, ScenarioQueueEditor.append(queue, "c", maximumItems = 2))
    }

    @Test
    fun removeLastRemovesOnlyOneMatchingOccurrence() {
        val queue = listOf("a", "b", "a", "c")

        assertEquals(
            listOf("a", "b", "c"),
            ScenarioQueueEditor.removeLast(queue, "a"),
        )
    }

    @Test
    fun removeAtTargetsExactQueuePositionAndIgnoresInvalidIndex() {
        val queue = listOf("a", "b", "a")

        assertEquals(listOf("a", "a"), ScenarioQueueEditor.removeAt(queue, 1))
        assertSame(queue, ScenarioQueueEditor.removeAt(queue, -1))
        assertSame(queue, ScenarioQueueEditor.removeAt(queue, queue.size))
    }

    @Test
    fun resetOrderRetainsDuplicateCountsAndUnknownRelativeOrder() {
        val queue = listOf("b", "unknown-2", "a", "b", "unknown-1", "c", "a")

        assertEquals(
            listOf("a", "a", "b", "b", "c", "unknown-2", "unknown-1"),
            ScenarioQueueEditor.resetToCatalogOrder(
                queue = queue,
                catalogOrder = listOf("a", "b", "c"),
            ),
        )
    }
}
