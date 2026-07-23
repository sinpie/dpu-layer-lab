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

    fun removeLast(queue: List<String>, scenarioId: String): List<String> {
        val index = queue.indexOfLast { it == scenarioId }
        return removeAt(queue, index)
    }

    fun removeAt(queue: List<String>, index: Int): List<String> {
        if (index !in queue.indices) return queue
        return ArrayList(queue).apply { removeAt(index) }
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
