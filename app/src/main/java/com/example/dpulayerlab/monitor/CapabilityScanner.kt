package com.example.dpulayerlab.monitor

import android.app.Activity
import android.hardware.HardwareBuffer
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import com.example.dpulayerlab.util.currentDisplayCompat

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
        val usage = HardwareBuffer.USAGE_CPU_WRITE_OFTEN or
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
    ): Boolean {
        if (
            !mime.startsWith("video/") ||
            width <= 0 ||
            height <= 0 ||
            !framesPerSecond.isFinite() ||
            framesPerSecond <= 0f
        ) {
            return false
        }
        return runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { codec ->
                !codec.isEncoder &&
                    codec.isHardwareAccelerated &&
                    codec.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                    runCatching {
                        codec.getCapabilitiesForType(mime).videoCapabilities
                            ?.areSizeAndRateSupported(
                                width,
                                height,
                                framesPerSecond.toDouble(),
                            ) == true
                    }.getOrDefault(false)
            }
        }.getOrDefault(false)
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
