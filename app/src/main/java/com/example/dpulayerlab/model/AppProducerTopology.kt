package com.example.dpulayerlab.model

import java.util.Collections

/**
 * App-owned BufferQueue producer role. This describes what DPULayerTest created; it is not a
 * per-layer HWC DEVICE/CLIENT assignment.
 */
enum class AppProducerKind(
    val shortLabel: String,
    val displayLabel: String,
) {
    VIDEO_DECODER("V", "Video decoder"),
    CANVAS_SURFACE("S", "Canvas Surface"),
    CANVAS_TEXTURE("T", "Canvas Texture"),
    GPU_GL("G", "GPU GL"),
    FLATTENED_CANVAS("F", "Flattened Canvas"),
    UNKNOWN("?", "Unknown"),
}

/**
 * Immutable descriptor published only after the renderer topology transaction commits.
 */
data class AppProducerDescriptor(
    val producerId: Long,
    val layerIndex: Int,
    val kind: AppProducerKind,
    val primary: Boolean,
) {
    init {
        require(producerId >= 0L) { "producerId must be non-negative" }
        require(layerIndex >= 0) { "layerIndex must be non-negative" }
    }
}

class AppProducerTopology(
    val generation: Long,
    producers: List<AppProducerDescriptor>,
) {
    /**
     * Snapshot the renderer-owned descriptors before publishing them across controller/UI
     * boundaries. A caller-owned MutableList must not be able to change a committed topology.
     */
    val producers: List<AppProducerDescriptor> =
        Collections.unmodifiableList(ArrayList(producers))

    init {
        require(generation > 0L) { "generation must be positive" }
        require(this.producers.isNotEmpty()) { "producer topology must not be empty" }
        require(this.producers.size <= MAX_APP_PRODUCER_COUNT) {
            "producer topology exceeds the hard layer cap"
        }
        require(
            this.producers.map { it.producerId }.distinct().size == this.producers.size,
        ) {
            "producer IDs must be unique"
        }
        require(
            this.producers.indices.all { index -> this.producers[index].layerIndex == index },
        ) {
            "producer descriptors must be ordered by layer index"
        }
        require(
            this.producers.first().primary && this.producers.drop(1).none { it.primary },
        ) {
            "only layer zero may be the primary producer"
        }
    }

    fun copy(
        generation: Long = this.generation,
        producers: List<AppProducerDescriptor> = this.producers,
    ): AppProducerTopology = AppProducerTopology(
        generation = generation,
        producers = producers,
    )

    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is AppProducerTopology &&
                    generation == other.generation &&
                    producers == other.producers
                )

    override fun hashCode(): Int = 31 * generation.hashCode() + producers.hashCode()

    override fun toString(): String =
        "AppProducerTopology(generation=$generation, producers=$producers)"
}

/**
 * Planned renderer roles using the exact branch precedence in LayerStageView.createLayer().
 */
fun plannedAppProducerKinds(phase: PhaseSpec): List<AppProducerKind> {
    if (phase.backend == LayerBackend.FLATTENED_TEXTURE) {
        return listOf(AppProducerKind.FLATTENED_CANVAS)
    }
    val count = phase.activeLayers.coerceIn(1, MAX_APP_PRODUCER_COUNT)
    return List(count) { layerIndex ->
        when {
            phase.includeGlLayer && layerIndex == count - 1 ->
                AppProducerKind.GPU_GL
            layerIndex == 0 && phase.pixelRoute.usesSelectedMediaDecoder() ->
                AppProducerKind.VIDEO_DECODER
            phase.backend == LayerBackend.MIXED_SURFACE_TEXTURE && layerIndex % 3 == 2 ->
                AppProducerKind.CANVAS_TEXTURE
            else ->
                AppProducerKind.CANVAS_SURFACE
        }
    }
}

const val MAX_APP_PRODUCER_COUNT = 20
