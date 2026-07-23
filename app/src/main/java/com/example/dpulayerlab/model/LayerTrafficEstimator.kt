package com.example.dpulayerlab.model

/**
 * A deterministic, linear-buffer traffic model for the buffers actually produced by the renderer.
 *
 * This is deliberately kept separate from hardware counters. It estimates one full-buffer read per
 * display refresh and one full-buffer write per producer frame. YUV/P010 bytes-per-pixel are only
 * used for a selected decoder source; Canvas/TextureView visual proxies are RGBA buffers. Tiling,
 * crop/occlusion, cache
 * effects, HWC client-target fallback, compression metadata, and vendor-specific compression ratios
 * are not knowable from the portable app surface and therefore are not folded into the number.
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
)

object LayerTrafficEstimator {
    private const val MAX_RENDERED_LAYERS = 20

    fun estimate(
        phase: PhaseSpec,
        displayWidthPx: Int,
        displayHeightPx: Int,
        measuredDisplayHz: Float? = null,
        mediaSelected: Boolean = false,
        mediaWidthPx: Int? = null,
        mediaHeightPx: Int? = null,
    ): LayerTrafficEstimate {
        val logicalLayers = phase.activeLayers.coerceIn(1, MAX_RENDERED_LAYERS)
        val displaySizeKnown = displayWidthPx > 0 && displayHeightPx > 0
        val scanoutFps = measuredDisplayHz
            ?.takeIf { it.isFinite() && it > 0f }
            ?: phase.requestedDisplayHz.takeIf { it.isFinite() && it > 0f }
            ?: 60f
        val producerFps = phase.producerFps.takeIf { it.isFinite() && it > 0f } ?: 1f

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
            )
        }

        var producerFrameBytes = 0.0
        var directScanoutFrameBytes = 0.0
        var producerSizesKnown = true
        var hasTextureOutput = false

        repeat(logicalLayers) { index ->
            val isGlOutput = phase.includeGlLayer && index == logicalLayers - 1
            val usesDecoderOutput =
                index == 0 &&
                    mediaSelected &&
                    !isGlOutput &&
                    phase.pixelRoute in setOf(
                        PixelRoute.YUV_420,
                        PixelRoute.P010,
                        PixelRoute.SBWC_AUTO,
                    )
            val primaryUsesRequestedSize =
                index == 0 &&
                    phase.bufferSize != BufferSize.DISPLAY &&
                    !isGlOutput &&
                    !usesDecoderOutput
            val width = when {
                usesDecoderOutput && mediaWidthPx != null && mediaWidthPx > 0 -> mediaWidthPx
                primaryUsesRequestedSize -> phase.bufferSize.width
                else -> displayWidthPx
            }
            val height = when {
                usesDecoderOutput && mediaHeightPx != null && mediaHeightPx > 0 -> mediaHeightPx
                primaryUsesRequestedSize -> phase.bufferSize.height
                else -> displayHeightPx
            }
            if (width <= 0 || height <= 0) {
                producerSizesKnown = false
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
                )
                val layerBytes = width.toDouble() * height.toDouble() * bytesPerPixel
                producerFrameBytes += layerBytes
                if (isTextureOutput) {
                    hasTextureOutput = true
                } else {
                    directScanoutFrameBytes += layerBytes
                }
            }
        }

        val knownProducerBytes = producerFrameBytes.takeIf { producerSizesKnown }
        val mixedClientTargetBytes = if (hasTextureOutput && displaySizeKnown) {
            displayWidthPx.toDouble() * displayHeightPx.toDouble() * 4.0
        } else if (hasTextureOutput) {
            null
        } else {
            0.0
        }
        val knownDpuBytes = if (producerSizesKnown && mixedClientTargetBytes != null) {
            directScanoutFrameBytes + mixedClientTargetBytes
        } else {
            null
        }
        val formatLabel = actualFormatLabel(phase, mediaSelected)
        val decoderMediaSizeKnown =
            mediaSelected &&
                !(phase.includeGlLayer && logicalLayers == 1) &&
                phase.pixelRoute in setOf(
                    PixelRoute.YUV_420,
                    PixelRoute.P010,
                    PixelRoute.SBWC_AUTO,
                ) &&
                mediaWidthPx != null &&
                mediaWidthPx > 0 &&
                mediaHeightPx != null &&
                mediaHeightPx > 0
        val resolutionLabel = when {
            !producerSizesKnown -> "display size pending"
            decoderMediaSizeKnown && logicalLayers == 1 ->
                "${mediaWidthPx}×${mediaHeightPx} decoder × 1 producer"
            decoderMediaSizeKnown ->
                "${mediaWidthPx}×${mediaHeightPx} decoder + " +
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
        )
    }

    private fun actualOutputBytesPerPixel(
        phase: PhaseSpec,
        index: Int,
        isGlOutput: Boolean,
        isTextureOutput: Boolean,
        mediaSelected: Boolean,
    ): Double = when {
        isGlOutput || isTextureOutput || phase.alphaOverlap -> 4.0
        index == 0 &&
            mediaSelected &&
            phase.pixelRoute in setOf(PixelRoute.YUV_420, PixelRoute.P010, PixelRoute.SBWC_AUTO) ->
            phase.pixelRoute.linearBytesPerPixel()
        phase.pixelRoute == PixelRoute.RGB_565 -> 2.0
        else -> 4.0
    }

    private fun actualFormatLabel(phase: PhaseSpec, mediaSelected: Boolean): String {
        val base = when {
            phase.alphaOverlap -> "RGBA 8888 alpha · 4 B/px"
            mediaSelected && phase.pixelRoute == PixelRoute.YUV_420 ->
                "decoder primary YUV420 1.5 B/px · overlays RGBA"
            mediaSelected && phase.pixelRoute == PixelRoute.P010 ->
                "decoder primary P010 3 B/px · overlays RGBA"
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
        return "$base$textureSuffix$glSuffix"
    }

    private fun PixelRoute.linearBytesPerPixel(): Double = when (this) {
        PixelRoute.RGB_8888 -> 4.0
        PixelRoute.RGB_565 -> 2.0
        PixelRoute.YUV_420 -> 1.5
        PixelRoute.P010 -> 3.0
        // The route does not encode an SBWC base format or vendor ratio. Use a 32-bit linear
        // reference and explicitly mark the result as excluding compression in the HUD.
        PixelRoute.SBWC_AUTO,
        PixelRoute.SBWC_REQUIRED,
        -> 4.0
    }

}
