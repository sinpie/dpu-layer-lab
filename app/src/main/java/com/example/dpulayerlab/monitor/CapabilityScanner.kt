package com.example.dpulayerlab.monitor

import android.app.Activity
import android.hardware.HardwareBuffer
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Display
import com.example.dpulayerlab.util.currentDisplayCompat
import java.util.Locale

data class CodecCapability(
    val name: String,
    val mime: String,
    val hardwareAccelerated: Boolean,
    val supports4k60: Boolean,
    val supports8k30: Boolean,
    val supports8k60: Boolean,
    val maxInstances: Int,
)

data class CapabilitySnapshot(
    val displayModes: List<String>,
    val codecs: List<CodecCapability>,
    val rgba4k: Boolean,
    val rgba8k: Boolean,
    val yuv4k: Boolean,
    val yuv8k: Boolean,
    val hasDumpPermission: Boolean,
    val hasNpuAdapter: Boolean,
    val sbwcAdapter: Boolean,
)

object CapabilityScanner {
    fun supportsCanvasBuffer(width: Int, height: Int, rgb565: Boolean): Boolean {
        if (width <= 0 || height <= 0) return false
        val format = if (rgb565) HardwareBuffer.RGB_565 else HardwareBuffer.RGBA_8888
        // Procedural layers use Surface.lockHardwareCanvas(), whose Skia producer renders through
        // the GPU. A CPU-write probe can reject a valid GPU-renderable BufferQueue (notably large
        // RGB565 buffers), so probe the usage that the producer and compositor actually need.
        val usage = HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                HardwareBuffer.USAGE_COMPOSER_OVERLAY
            } else {
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
            }
        return isHardwareBufferSupported(width, height, format, usage)
    }

    fun supportsHardwareVideoDecoder(
        mime: String,
        width: Int,
        height: Int,
        framesPerSecond: Float,
        requiredProfile: Int? = null,
        requiredLevel: Int? = null,
        bitRate: Int? = null,
    ): Boolean = findHardwareVideoDecoder(
        mime = mime,
        width = width,
        height = height,
        framesPerSecond = framesPerSecond,
        requiredProfile = requiredProfile,
        requiredLevel = requiredLevel,
        bitRate = bitRate,
    ) != null

    /**
     * Resolves the exact hardware decoder that the renderer must instantiate. Returning only a
     * Boolean here would allow MediaCodec's later default selection to choose a software codec or
     * a hardware codec that did not advertise the P010 source profile checked during preflight.
     */
    fun findHardwareVideoDecoder(
        mime: String,
        width: Int,
        height: Int,
        framesPerSecond: Float,
        requiredProfile: Int? = null,
        requiredLevel: Int? = null,
        bitRate: Int? = null,
    ): String? {
        val request = sanitizeVideoDecoderRequest(
            mime = mime,
            width = width,
            height = height,
            framesPerSecond = framesPerSecond,
            requiredProfile = requiredProfile,
            requiredLevel = requiredLevel,
            bitRate = bitRate,
        ) ?: return null
        return runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.firstNotNullOfOrNull { codec ->
                runCatching {
                    val advertisedMime = codec.supportedTypes.firstOrNull {
                        it.equals(request.mime, ignoreCase = true)
                    }
                    if (advertisedMime == null) {
                        null
                    } else {
                        val basicCandidate = decoderCandidateEligible(
                            isEncoder = codec.isEncoder,
                            isHardwareAccelerated = codec.isHardwareAccelerated,
                            isAlias = codec.isAlias,
                            requiresSecurePlayback = false,
                        )
                        if (!basicCandidate) {
                            null
                        } else {
                            val capabilities = codec.getCapabilitiesForType(advertisedMime)
                            codec.name.takeIf {
                                !capabilities.isFeatureRequired(
                                    MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback,
                                ) &&
                                    capabilities.isFormatSupported(
                                        request.toMediaFormat(advertisedMime),
                                    )
                            }
                        }
                    }
                }.getOrNull()
            }
        }.getOrNull()
    }

    fun scan(
        activity: Activity,
        hasDumpPermission: Boolean,
        hasNpuAdapter: Boolean,
        hasSbwcAdapter: Boolean,
    ): CapabilitySnapshot {
        val modes = activity.currentDisplayCompat()?.supportedModes.orEmpty()
            .sortedWith(
                compareBy<Display.Mode> {
                    it.physicalWidth.toLong() * it.physicalHeight.toLong()
                }.thenBy { it.refreshRate },
            )
            .map { "${it.physicalWidth}×${it.physicalHeight} @ %.1f Hz".format(it.refreshRate) }
            .distinct()
        val codecs = scanCodecs()
        val usage = HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            HardwareBuffer.USAGE_COMPOSER_OVERLAY
        } else {
            0L
        }
        val yuv4k = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            isHardwareBufferSupported(3840, 2160, HardwareBuffer.YCBCR_420_888, usage)
        } else {
            false
        }
        val yuv8k = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            isHardwareBufferSupported(7680, 4320, HardwareBuffer.YCBCR_420_888, usage)
        } else {
            false
        }
        return CapabilitySnapshot(
            displayModes = modes,
            codecs = codecs,
            rgba4k = isHardwareBufferSupported(3840, 2160, HardwareBuffer.RGBA_8888, usage),
            rgba8k = isHardwareBufferSupported(7680, 4320, HardwareBuffer.RGBA_8888, usage),
            yuv4k = yuv4k,
            yuv8k = yuv8k,
            hasDumpPermission = hasDumpPermission,
            hasNpuAdapter = hasNpuAdapter,
            sbwcAdapter = hasSbwcAdapter,
        )
    }

    private fun scanCodecs(): List<CodecCapability> {
        return runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                .asSequence()
                .filterNot { it.isEncoder }
                .flatMap { codec ->
                    codec.supportedTypes.asSequence()
                        .filter { it.startsWith("video/") }
                        .mapNotNull { mime ->
                            val capabilities = runCatching { codec.getCapabilitiesForType(mime) }.getOrNull()
                                ?: return@mapNotNull null
                            val video = capabilities.videoCapabilities ?: return@mapNotNull null
                            CodecCapability(
                                name = codec.name,
                                mime = mime,
                                hardwareAccelerated = codec.isHardwareAccelerated,
                                supports4k60 = video.safelySupports(3840, 2160, 60.0),
                                supports8k30 = video.safelySupports(7680, 4320, 30.0),
                                supports8k60 = video.safelySupports(7680, 4320, 60.0),
                                maxInstances = capabilities.maxSupportedInstances,
                            )
                        }
                }
                .filter { it.hardwareAccelerated || it.supports4k60 || it.supports8k30 }
                .sortedWith(
                    compareByDescending<CodecCapability> { it.supports8k60 }
                        .thenByDescending { it.supports4k60 }
                        .thenBy { it.mime },
                )
                .toList()
        }.getOrDefault(emptyList())
    }

    private fun isHardwareBufferSupported(width: Int, height: Int, format: Int, usage: Long): Boolean =
        runCatching { HardwareBuffer.isSupported(width, height, format, 1, usage) }.getOrDefault(false)

    private fun MediaCodecInfo.VideoCapabilities.safelySupports(
        width: Int,
        height: Int,
        framesPerSecond: Double,
    ): Boolean = runCatching {
        areSizeAndRateSupported(width, height, framesPerSecond)
    }.getOrDefault(false)
}

internal data class SanitizedVideoDecoderRequest(
    val mime: String,
    val width: Int,
    val height: Int,
    val framesPerSecond: Float,
    val requiredProfile: Int?,
    val requiredLevel: Int?,
    val bitRate: Int?,
) {
    fun toMediaFormat(advertisedMime: String): MediaFormat =
        MediaFormat.createVideoFormat(advertisedMime, width, height).apply {
            setFloat(MediaFormat.KEY_FRAME_RATE, framesPerSecond)
            requiredProfile?.let { setInteger(MediaFormat.KEY_PROFILE, it) }
            requiredLevel?.let { setInteger(MediaFormat.KEY_LEVEL, it) }
            bitRate?.let { setInteger(MediaFormat.KEY_BIT_RATE, it) }
        }
}

internal fun sanitizeVideoDecoderRequest(
    mime: String,
    width: Int,
    height: Int,
    framesPerSecond: Float,
    requiredProfile: Int?,
    requiredLevel: Int?,
    bitRate: Int?,
): SanitizedVideoDecoderRequest? {
    if (mime.length !in 1..MAX_CODEC_MIME_LENGTH) return null
    val canonicalMime = mime.lowercase(Locale.ROOT)
    if (
        canonicalMime.length !in 1..MAX_CODEC_MIME_LENGTH ||
        !VIDEO_MIME_PATTERN.matches(canonicalMime) ||
        width !in 1..MAX_CODEC_DIMENSION ||
        height !in 1..MAX_CODEC_DIMENSION ||
        width.toLong() * height.toLong() > MAX_CODEC_PIXEL_COUNT ||
        !framesPerSecond.isFinite() ||
        framesPerSecond <= 0f ||
        framesPerSecond > MAX_CODEC_FRAME_RATE ||
        requiredProfile != null && requiredProfile <= 0 ||
        requiredLevel != null && (requiredProfile == null || requiredLevel <= 0) ||
        bitRate != null && bitRate <= 0
    ) {
        return null
    }
    return SanitizedVideoDecoderRequest(
        mime = canonicalMime,
        width = width,
        height = height,
        framesPerSecond = framesPerSecond,
        requiredProfile = requiredProfile,
        requiredLevel = requiredLevel,
        bitRate = bitRate,
    )
}

internal fun decoderCandidateEligible(
    isEncoder: Boolean,
    isHardwareAccelerated: Boolean,
    isAlias: Boolean,
    requiresSecurePlayback: Boolean,
): Boolean = !isEncoder && isHardwareAccelerated && !isAlias && !requiresSecurePlayback

private const val MAX_CODEC_MIME_LENGTH = 127
private const val MAX_CODEC_DIMENSION = 16_384
private const val MAX_CODEC_PIXEL_COUNT = 268_435_456L
private const val MAX_CODEC_FRAME_RATE = 1_000f
private val VIDEO_MIME_PATTERN = Regex("""video/[a-z0-9][a-z0-9.+-]*""")
