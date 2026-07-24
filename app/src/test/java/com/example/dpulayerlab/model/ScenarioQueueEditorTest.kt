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
    fun appendAllPreservesOrderDuplicatesAndStopsAtHardLimit() {
        assertEquals(
            listOf("a", "b", "b", "c"),
            ScenarioQueueEditor.appendAll(
                queue = listOf("a"),
                scenarioIds = listOf("", "b", "b", "c", "d"),
                maximumItems = 4,
            ),
        )
    }

    @Test
    fun appendAllReturnsOriginalWhenNothingCanBeAdded() {
        val queue = listOf("a", "b")

        assertSame(
            queue,
            ScenarioQueueEditor.appendAll(queue, listOf("", "  "), maximumItems = 4),
        )
        assertSame(
            queue,
            ScenarioQueueEditor.appendAll(queue, listOf("c"), maximumItems = 2),
        )
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
    fun moveTargetsExactDuplicatePositionAndIgnoresInvalidMoves() {
        val queue = listOf("a", "b", "a", "c")

        assertEquals(listOf("b", "a", "a", "c"), ScenarioQueueEditor.move(queue, 1, 0))
        assertEquals(listOf("a", "a", "c", "b"), ScenarioQueueEditor.move(queue, 1, 3))
        assertSame(queue, ScenarioQueueEditor.move(queue, -1, 0))
        assertSame(queue, ScenarioQueueEditor.move(queue, 0, queue.size))
        assertSame(queue, ScenarioQueueEditor.move(queue, 1, 1))
    }

    @Test
    fun restoredUnknownIdsAreRemovedBeforeUiIndexing() {
        val restored = listOf("removed", "a", "b", "a")

        assertEquals(
            listOf("a", "b", "a"),
            ScenarioQueueEditor.retainKnown(restored, setOf("a", "b")),
        )
        val alreadyValid = listOf("a", "b")
        assertSame(
            alreadyValid,
            ScenarioQueueEditor.retainKnown(alreadyValid, setOf("a", "b")),
        )
    }

    @Test
    fun restoredValidDuplicatesAreCappedAtTheHardPlanLimit() {
        val restored = List(55) { if (it % 2 == 0) "a" else "b" }

        assertEquals(
            restored.take(40),
            ScenarioQueueEditor.retainKnown(
                queue = restored,
                knownScenarioIds = setOf("a", "b"),
                maximumItems = 40,
            ),
        )
        assertEquals(
            emptyList<String>(),
            ScenarioQueueEditor.retainKnown(
                queue = restored,
                knownScenarioIds = setOf("a", "b"),
                maximumItems = 0,
            ),
        )
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
