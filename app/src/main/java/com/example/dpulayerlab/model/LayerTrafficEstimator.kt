package com.example.dpulayerlab.model

/**
 * A deterministic, linear-buffer traffic model for the buffers actually produced by the renderer.
 *
 * This is deliberately kept separate from hardware counters. It estimates one full-buffer read per
 * display refresh and one full-buffer write per producer frame. A selected decoder uses only the
 * metadata-verified [DecoderLinearReference]; the requested YUV/P010/SBWC route does not force a
 * MediaCodec Surface allocation. Canvas/TextureView visual proxies are RGBA buffers. Tiling,
 * destination scaling/crop/occlusion, cache effects, HWC client-target fallback, compression
 * metadata, and vendor-specific compression ratios are not knowable from the portable app surface
 * and therefore are not folded into the traffic number. [destinationFootprintScreenEquivalents]
 * separately describes the requested destination geometry; it is never presented as measured bus
 * traffic or used to reduce the conservative full-buffer source read/write estimate.
 */
data class LayerTrafficEstimate(
    val logicalLayerCount: Int,
    val producerLayerCount: Int,
    val bytesPerFrame: Double?,
    val dpuReadBytesPerSecond: Double?,
    val producerWriteBytesPerSecond: Double?,
    val scanoutFps: Float,
    val formatLabel: String,
    val resolutionLabel: String,
    val compressionRatioExcluded: Boolean,
    /**
     * Sum of base [LayerSizeProfile] areas before MotionProfile scaling, overlap, clipping,
     * rotation, or off-screen loss. [MotionProfile.CAPACITY_TILES] is the explicit exception:
     * its non-overlapping crop union is one screen and is reported as such.
     */
    val destinationFootprintScreenEquivalents: Double,
    /** Mean per-producer base size-profile area, expressed as a percentage of the display. */
    val destinationFootprintAveragePercent: Double,
    val destinationFootprintLabel: String,
)

/**
 * Linear byte reference inferred from selected media metadata, not from the requested route.
 *
 * MediaCodec Surface output allocation remains vendor-controlled. A null B/px means the portable
 * app cannot distinguish an 8-bit YUV420 output from another decoder format and must report N/A.
 */
data class DecoderLinearReference(
    val bytesPerPixel: Double?,
    val label: String,
    val source: String,
)

object LayerTrafficEstimator {
    private const val MAX_RENDERED_LAYERS = 20
    // 16 B/px covers an RGBA32F linear reference and prevents malformed public descriptors from
    // producing negative, non-finite, or effectively unbounded HUD traffic.
    private const val MAX_LINEAR_BYTES_PER_PIXEL = 16.0
    // Validate decoder dimensions against the largest accepted linear reference. This keeps a
    // malformed metadata pair from describing a single frame whose byte count cannot fit in the
    // signed Long domain used by allocation and traffic-budget code elsewhere in the app.
    private const val MAX_DECODER_PIXEL_COUNT = Long.MAX_VALUE / 16L

    fun estimate(
        phase: PhaseSpec,
        displayWidthPx: Int,
        displayHeightPx: Int,
        measuredDisplayHz: Float? = null,
        mediaSelected: Boolean = false,
        mediaWidthPx: Int? = null,
        mediaHeightPx: Int? = null,
        decoderLinearReference: DecoderLinearReference? = null,
        phaseFraction: Float = 0f,
    ): LayerTrafficEstimate {
        val verifiedDecoderLinearReference = decoderLinearReference?.takeIf { reference ->
            reference.bytesPerPixel?.let { bytesPerPixel ->
                bytesPerPixel.isFinite() &&
                    bytesPerPixel > 0.0 &&
                    bytesPerPixel <= MAX_LINEAR_BYTES_PER_PIXEL
            } == true
        }
        val decoderDimensions = validatedDecoderDimensions(mediaWidthPx, mediaHeightPx)
        val logicalLayers = phase.activeLayers.coerceIn(1, MAX_RENDERED_LAYERS)
        val displaySizeKnown = displayWidthPx > 0 && displayHeightPx > 0
        val scanoutFps = measuredDisplayHz
            ?.takeIf { it.isFinite() && it > 0f }
            ?: phase.requestedDisplayHz.takeIf { it.isFinite() && it > 0f }
            ?: 60f
        val producerFps = phase.producerFps.takeIf { it.isFinite() && it > 0f } ?: 1f
        val physicalProducerCount =
            if (phase.backend == LayerBackend.FLATTENED_TEXTURE) 1 else logicalLayers
        val footprint = destinationFootprint(
            phase = phase,
            physicalProducerCount = physicalProducerCount,
            phaseFraction = phaseFraction,
        )
        val footprintLabel = if (phase.motion == MotionProfile.CAPACITY_TILES) {
            "Capacity tiles · explicit crop union; size profile bypassed"
        } else {
            "${phase.layerSizeProfile.label} · base profile only; motion/overlap/crop excluded"
        }

        if (phase.backend == LayerBackend.FLATTENED_TEXTURE) {
            val frameBytes = if (displaySizeKnown) {
                displayWidthPx.toDouble() * displayHeightPx.toDouble() * 4.0
            } else {
                null
            }
            return LayerTrafficEstimate(
                logicalLayerCount = logicalLayers,
                producerLayerCount = 1,
                bytesPerFrame = frameBytes,
                dpuReadBytesPerSecond = frameBytes?.times(scanoutFps),
                producerWriteBytesPerSecond = frameBytes?.times(producerFps),
                scanoutFps = scanoutFps,
                formatLabel = "RGBA 8888 · 4 B/px (flattened)",
                resolutionLabel = if (displaySizeKnown) {
                    "${displayWidthPx}×${displayHeightPx} × 1 producer"
                } else {
                    "display size pending"
                },
                compressionRatioExcluded = false,
                destinationFootprintScreenEquivalents = footprint,
                destinationFootprintAveragePercent = footprint * 100.0,
                destinationFootprintLabel = footprintLabel,
            )
        }

        var producerFrameBytes = 0.0
        var directScanoutFrameBytes = 0.0
        var producerDimensionsKnown = true
        var producerFormatsKnown = true
        var hasTextureOutput = false
        val displayDimensions = ProducerDimensions(displayWidthPx, displayHeightPx)
        val requestedPrimaryDimensions =
            ProducerDimensions(phase.bufferSize.width, phase.bufferSize.height)

        repeat(logicalLayers) { index ->
            val isGlOutput = phase.includeGlLayer && index == logicalLayers - 1
            val usesDecoderOutput =
                index == 0 &&
                    mediaSelected &&
                    !isGlOutput &&
                    phase.pixelRoute.usesSelectedMediaDecoder()
            val primaryUsesRequestedSize =
                index == 0 &&
                    phase.bufferSize != BufferSize.DISPLAY &&
                    !isGlOutput &&
                    !usesDecoderOutput
            val dimensions = when {
                usesDecoderOutput -> decoderDimensions
                primaryUsesRequestedSize -> requestedPrimaryDimensions
                else -> displayDimensions
            }
            if (dimensions == null || dimensions.width <= 0 || dimensions.height <= 0) {
                producerDimensionsKnown = false
            } else {
                val isTextureOutput =
                    phase.backend == LayerBackend.MIXED_SURFACE_TEXTURE &&
                        index % 3 == 2 &&
                        !isGlOutput
                val bytesPerPixel = actualOutputBytesPerPixel(
                    phase = phase,
                    index = index,
                    isGlOutput = isGlOutput,
                    isTextureOutput = isTextureOutput,
                    mediaSelected = mediaSelected,
                    decoderLinearReference = verifiedDecoderLinearReference,
                )
                if (bytesPerPixel == null) {
                    producerFormatsKnown = false
                } else {
                    val layerBytes =
                        dimensions.width.toDouble() * dimensions.height.toDouble() * bytesPerPixel
                    producerFrameBytes += layerBytes
                    if (isTextureOutput) {
                        hasTextureOutput = true
                    } else {
                        directScanoutFrameBytes += layerBytes
                    }
                }
            }
        }

        val trafficKnown = producerDimensionsKnown && producerFormatsKnown
        val knownProducerBytes = producerFrameBytes.takeIf { trafficKnown }
        val mixedAppWindowCompositeBytes = if (hasTextureOutput && displaySizeKnown) {
            displayWidthPx.toDouble() * displayHeightPx.toDouble() * 4.0
        } else if (hasTextureOutput) {
            null
        } else {
            0.0
        }
        val knownDpuBytes = if (trafficKnown && mixedAppWindowCompositeBytes != null) {
            directScanoutFrameBytes + mixedAppWindowCompositeBytes
        } else {
            null
        }
        val formatLabel = actualFormatLabel(
            phase = phase,
            mediaSelected = mediaSelected,
            decoderLinearReference = verifiedDecoderLinearReference,
        )
        val decoderPrimaryActive =
            mediaSelected &&
                !(phase.includeGlLayer && logicalLayers == 1) &&
                phase.pixelRoute.usesSelectedMediaDecoder()
        val resolutionLabel = when {
            decoderPrimaryActive && decoderDimensions == null -> "decoder size N/A"
            !producerDimensionsKnown -> "display size pending"
            decoderPrimaryActive && decoderDimensions != null && logicalLayers == 1 ->
                "${decoderDimensions.width}×${decoderDimensions.height} decoder × 1 producer"
            decoderPrimaryActive && decoderDimensions != null ->
                "${decoderDimensions.width}×${decoderDimensions.height} decoder + " +
                    "${logicalLayers - 1} display"
            phase.bufferSize == BufferSize.DISPLAY ||
                (phase.includeGlLayer && logicalLayers == 1) ->
                "${displayWidthPx}×${displayHeightPx} × $logicalLayers producers"
            logicalLayers == 1 ->
                "${phase.bufferSize.width}×${phase.bufferSize.height} × 1 producer"
            else ->
                "${phase.bufferSize.width}×${phase.bufferSize.height} primary + " +
                    "${logicalLayers - 1} display"
        }

        return LayerTrafficEstimate(
            logicalLayerCount = logicalLayers,
            producerLayerCount = logicalLayers,
            bytesPerFrame = knownProducerBytes,
            dpuReadBytesPerSecond = knownDpuBytes?.times(scanoutFps),
            producerWriteBytesPerSecond = knownProducerBytes?.times(producerFps),
            scanoutFps = scanoutFps,
            formatLabel = formatLabel,
            resolutionLabel = resolutionLabel,
            compressionRatioExcluded = phase.pixelRoute in setOf(
                PixelRoute.SBWC_AUTO,
                PixelRoute.SBWC_REQUIRED,
            ),
            destinationFootprintScreenEquivalents = footprint,
            destinationFootprintAveragePercent =
                footprint * 100.0 / physicalProducerCount.toDouble(),
            destinationFootprintLabel = footprintLabel,
        )
    }

    private fun destinationFootprint(
        phase: PhaseSpec,
        physicalProducerCount: Int,
        phaseFraction: Float,
    ): Double {
        val count = physicalProducerCount.coerceIn(1, MAX_RENDERED_LAYERS)
        if (phase.motion == MotionProfile.CAPACITY_TILES) return 1.0
        var total = 0.0
        var index = 0
        while (index < count) {
            total += phase.layerSizeProfile
                .normalizedSizeForLayer(
                    layerIndex = index,
                    layerCount = count,
                    phaseFraction = phaseFraction,
                )
                .areaScale
                .toDouble()
            index++
        }
        return total
            .takeIf(Double::isFinite)
            ?.coerceIn(0.0, count.toDouble())
            ?: count.toDouble()
    }

    private fun actualOutputBytesPerPixel(
        phase: PhaseSpec,
        index: Int,
        isGlOutput: Boolean,
        isTextureOutput: Boolean,
        mediaSelected: Boolean,
        decoderLinearReference: DecoderLinearReference?,
    ): Double? = when {
        !isGlOutput &&
            index == 0 &&
            mediaSelected &&
            phase.pixelRoute.usesSelectedMediaDecoder() ->
            decoderLinearReference?.bytesPerPixel
        isGlOutput || isTextureOutput || phase.alphaOverlap -> 4.0
        phase.pixelRoute == PixelRoute.RGB_565 -> 2.0
        else -> 4.0
    }

    private fun validatedDecoderDimensions(
        widthPx: Int?,
        heightPx: Int?,
    ): ProducerDimensions? {
        if (widthPx == null || heightPx == null || widthPx <= 0 || heightPx <= 0) return null
        // Int dimensions are widened before multiplication. The explicit upper bound also keeps
        // the validation correct if the accepted descriptor B/px ceiling is applied later.
        val pixelCount = widthPx.toLong() * heightPx.toLong()
        if (pixelCount <= 0L || pixelCount > MAX_DECODER_PIXEL_COUNT) return null
        return ProducerDimensions(widthPx, heightPx)
    }

    private fun actualFormatLabel(
        phase: PhaseSpec,
        mediaSelected: Boolean,
        decoderLinearReference: DecoderLinearReference?,
    ): String {
        val decoderPrimaryActive =
            mediaSelected &&
                !(phase.includeGlLayer && phase.activeLayers == 1) &&
                phase.pixelRoute.usesSelectedMediaDecoder()
        val base = when {
            decoderPrimaryActive -> buildString {
                append("decoder primary ")
                append(decoderLinearReference?.label ?: "linear reference N/A")
                append(" · route ")
                append(phase.pixelRoute.name)
                append(" does not force Surface format")
                if (
                    phase.pixelRoute == PixelRoute.SBWC_AUTO ||
                    phase.pixelRoute == PixelRoute.SBWC_REQUIRED
                ) {
                    append(" · compression excluded")
                }
                append(" · overlays RGBA")
            }
            phase.alphaOverlap -> "RGBA 8888 alpha · 4 B/px"
            phase.pixelRoute in setOf(PixelRoute.YUV_420, PixelRoute.P010) ->
                "RGBA 8888 visual proxy · 4 B/px"
            phase.pixelRoute == PixelRoute.RGB_565 -> "RGB 565 · 2 B/px"
            else -> "${phase.pixelRoute.label} · 4 B/px linear reference"
        }
        val textureSuffix = if (phase.backend == LayerBackend.MIXED_SURFACE_TEXTURE) {
            " · Texture=4 B/px"
        } else {
            ""
        }
        val glSuffix = if (phase.includeGlLayer) " · GL=4 B/px" else ""
        val alphaSuffix = if (decoderPrimaryActive && phase.alphaOverlap) " · Surface alpha" else ""
        return "$base$textureSuffix$glSuffix$alphaSuffix"
    }

}

private data class ProducerDimensions(
    val width: Int,
    val height: Int,
)
