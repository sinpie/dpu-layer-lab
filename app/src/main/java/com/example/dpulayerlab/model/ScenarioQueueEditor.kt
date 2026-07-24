package com.example.dpulayerlab.model

/**
 * Pure queue operations shared by the Compose catalog and unit tests.
 *
 * Duplicate IDs are intentional: a tester can build A/B/A without cloning a preset. Every
 * operation returns a new list only when the queue actually changes, which also avoids needless
 * Compose state invalidation when the hard plan limit has already been reached.
 */
object ScenarioQueueEditor {
    fun append(
        queue: List<String>,
        scenarioId: String,
        maximumItems: Int = ScenarioPlanPolicy.MAX_TOTAL_PLAN_RUNS,
    ): List<String> {
        if (scenarioId.isBlank() || maximumItems <= 0 || queue.size >= maximumItems) return queue
        return queue + scenarioId
    }

    /**
     * Appends the currently filtered catalog result in display order.
     *
     * Existing and incoming duplicates remain meaningful queue entries. Blank IDs are ignored,
     * and the operation stops exactly at the hard plan-item cap without allocating a large
     * intermediate list for hostile input.
     */
    fun appendAll(
        queue: List<String>,
        scenarioIds: Iterable<String>,
        maximumItems: Int = ScenarioPlanPolicy.MAX_TOTAL_PLAN_RUNS,
    ): List<String> {
        if (maximumItems <= 0 || queue.size >= maximumItems) return queue
        val result = ArrayList<String>(minOf(maximumItems, queue.size + 8))
        result.addAll(queue)
        for (scenarioId in scenarioIds) {
            if (result.size >= maximumItems) break
            if (scenarioId.isNotBlank()) result += scenarioId
        }
        return if (result.size == queue.size) queue else result
    }

    fun removeLast(queue: List<String>, scenarioId: String): List<String> {
        val index = queue.indexOfLast { it == scenarioId }
        return removeAt(queue, index)
    }

    fun removeAt(queue: List<String>, index: Int): List<String> {
        if (index !in queue.indices) return queue
        return ArrayList(queue).apply { removeAt(index) }
    }

    fun move(queue: List<String>, fromIndex: Int, toIndex: Int): List<String> {
        if (
            fromIndex !in queue.indices ||
            toIndex !in queue.indices ||
            fromIndex == toIndex
        ) {
            return queue
        }
        return ArrayList(queue).apply {
            val item = removeAt(fromIndex)
            add(toIndex, item)
        }
    }

    /**
     * Drops IDs that no longer exist after an app/catalog update.
     *
     * UI indexes must always refer to the same list that is rendered; retaining hidden unknown
     * entries would make clicking visible queue item #1 remove a different raw entry.
     */
    fun retainKnown(
        queue: List<String>,
        knownScenarioIds: Set<String>,
        maximumItems: Int = ScenarioPlanPolicy.MAX_TOTAL_PLAN_RUNS,
    ): List<String> {
        if (maximumItems <= 0) return if (queue.isEmpty()) queue else emptyList()
        if (
            queue.size <= maximumItems &&
            queue.all(knownScenarioIds::contains)
        ) {
            return queue
        }
        val result = ArrayList<String>(minOf(queue.size, maximumItems))
        for (scenarioId in queue) {
            if (result.size >= maximumItems) break
            if (scenarioId in knownScenarioIds) result += scenarioId
        }
        return result
    }

    /**
     * Restores catalog order without collapsing duplicate queue entries.
     *
     * Unknown IDs are retained at the end in their original relative order. The UI never creates
     * them, but retaining them makes this helper lossless if a catalog changes during state restore.
     */
    fun resetToCatalogOrder(
        queue: List<String>,
        catalogOrder: List<String>,
    ): List<String> {
        if (queue.size < 2) return queue
        val rank = LinkedHashMap<String, Int>(catalogOrder.size)
        catalogOrder.forEachIndexed { index, id -> rank.putIfAbsent(id, index) }
        val reordered = queue.withIndex()
            .sortedWith(
                compareBy<IndexedValue<String>> { rank[it.value] ?: Int.MAX_VALUE }
                    .thenBy { it.index },
            )
            .map { it.value }
        return if (reordered == queue) queue else reordered
    }
}
