package com.example.dpulayerlab.render

import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Immutable binding between the inspected media source and the exact hardware codec accepted by
 * preflight. The track fingerprint is intentionally carried into the renderer: a mutable
 * ContentProvider URI or adaptive stream must not replace the inspected source with a larger or
 * semantically different track after the graphics/capability budget was approved.
 */
data class VideoDecoderSelection(
    val mediaUri: Uri,
    val pinnedSource: PinnedMediaSource,
    val mime: String,
    val codecName: String,
    val expectedEncodedWidthPx: Int,
    val expectedEncodedHeightPx: Int,
    val expectedDeclaredMaxWidthPx: Int?,
    val expectedDeclaredMaxHeightPx: Int?,
    val expectedVisibleWidthPx: Int,
    val expectedVisibleHeightPx: Int,
    val expectedSourceFps: Float,
    val expectedProfile: Int?,
    val expectedLevel: Int?,
    val expectedBitRate: Int?,
    val expectedMaxInputSize: Int?,
    val expectedRotationDegrees: Int,
    val expectedCodecsString: String?,
    val codecConfigFingerprint: String,
    val maxCompressedSampleBytes: Int,
    val requiresVerifiedP010: Boolean,
) : AutoCloseable {
    init {
        require(pinnedSource.mediaUri == mediaUri) {
            "The pinned descriptor must belong to the inspected URI"
        }
        require(mime.startsWith("video/", ignoreCase = true)) { "A video MIME is required" }
        require(codecName.isNotBlank()) { "A concrete codec name is required" }
        require(expectedEncodedWidthPx > 0 && expectedEncodedHeightPx > 0) {
            "Encoded dimensions must be positive"
        }
        require(
            fixedVideoMaximumDimensionsMatch(
                encodedWidthPx = expectedEncodedWidthPx,
                encodedHeightPx = expectedEncodedHeightPx,
                declaredMaxWidthPx = expectedDeclaredMaxWidthPx,
                declaredMaxHeightPx = expectedDeclaredMaxHeightPx,
            )
        ) {
            "Adaptive/dynamic video dimensions are not accepted"
        }
        require(expectedVisibleWidthPx in 1..expectedEncodedWidthPx) {
            "Visible width must fit the encoded track"
        }
        require(expectedVisibleHeightPx in 1..expectedEncodedHeightPx) {
            "Visible height must fit the encoded track"
        }
        require(expectedSourceFps.isFinite() && expectedSourceFps > 0f) {
            "Source FPS must be finite and positive"
        }
        require(
            expectedCodecsString == null ||
                (
                    expectedCodecsString.isNotBlank() &&
                        expectedCodecsString.length <= MAX_CODECS_STRING_CHARS
                    )
        ) {
            "Codec string must be bounded and non-blank"
        }
        require(!requiresVerifiedP010 || expectedProfile != null) {
            "P010 requires an exact inspected profile"
        }
        require(expectedLevel == null || expectedLevel > 0) {
            "Codec level must be positive when present"
        }
        require(expectedBitRate == null || expectedBitRate > 0) {
            "Bit rate must be positive when present"
        }
        require(
            expectedMaxInputSize == null ||
                expectedMaxInputSize in 1..MAX_BOUND_COMPRESSED_SAMPLE_BYTES
        ) {
            "Maximum compressed input size exceeds the safety ceiling"
        }
        require(maxCompressedSampleBytes in 1..MAX_BOUND_COMPRESSED_SAMPLE_BYTES) {
            "Compressed sample safety limit exceeds the global ceiling"
        }
        require(
            expectedMaxInputSize == null ||
                expectedMaxInputSize <= maxCompressedSampleBytes
        ) {
            "Declared maximum input size exceeds the run safety limit"
        }
        require(expectedRotationDegrees in setOf(0, 90, 180, 270)) {
            "Video rotation must be 0, 90, 180, or 270 degrees"
        }
        require(
            codecConfigFingerprint.length == SHA_256_HEX_CHARS &&
                codecConfigFingerprint.all { it in '0'..'9' || it in 'a'..'f' }
        ) {
            "A lowercase SHA-256 codec-config fingerprint is required"
        }
        require(
            !requiresVerifiedP010 ||
                !mime.equals("video/x-vnd.on2.vp9", ignoreCase = true) ||
                expectedCodecsString != null
        ) {
            "VP9 P010 requires an exact codec string"
        }
        require(maxEncodedWidthPx != null && maxEncodedHeightPx != null) {
            "Encoded dimensions are too large to align safely"
        }
    }

    /**
     * Codec buffers commonly have an aligned stride/height. These ceilings are also used by the
     * preflight graphics estimate, so accepting ordinary codec padding cannot escape that budget.
     */
    val maxEncodedWidthPx: Int?
        get() = videoDimensionCeiling(expectedEncodedWidthPx)

    val maxEncodedHeightPx: Int?
        get() = videoDimensionCeiling(expectedEncodedHeightPx)

    internal fun openSourceDuplicate(): BoundedOwnedResource<AssetFileDescriptor> =
        pinnedSource.openDuplicate()

    fun closeWithResult(): Boolean = pinnedSource.closeWithResult()

    override fun close() {
        closeWithResult()
    }

    private companion object {
        const val MAX_CODECS_STRING_CHARS = 512
        const val SHA_256_HEX_CHARS = 64
    }
}

/**
 * Owns the descriptor inspected during preflight. Every extractor gets a dup of this exact open
 * file description, so a mutable provider cannot substitute another object between preflight and
 * execution. Closing a duplicate never closes the master descriptor.
 */
class PinnedMediaSource internal constructor(
    val mediaUri: Uri,
    private val descriptor: AssetFileDescriptor,
) : AutoCloseable {
    private val lock = Any()
    private var closed = false
    private val closeState = BoundedCloseState(
        closer = { descriptor.close() },
    )

    init {
        check(!PinnedMediaCleanupState.hasUnconfirmedCleanup()) {
            "A previous pinned media descriptor cleanup is unconfirmed"
        }
        require(descriptor.startOffset >= 0L) { "Negative asset offset" }
        require(
            descriptor.declaredLength >= 0L || descriptor.startOffset == 0L
        ) {
            "Unknown-length assets must start at offset zero"
        }
        require(
            runCatching {
                val current = Os.lseek(
                    descriptor.fileDescriptor,
                    0L,
                    OsConstants.SEEK_CUR,
                )
                Os.lseek(descriptor.fileDescriptor, current, OsConstants.SEEK_SET)
                true
            }.getOrDefault(false)
        ) {
            "Streaming/pipe descriptors cannot be replayed safely"
        }
    }

    internal fun openDuplicate(): BoundedOwnedResource<AssetFileDescriptor> = synchronized(lock) {
        check(!closed) { "Pinned media source is closed" }
        check(!PinnedMediaCleanupState.hasUnconfirmedCleanup()) {
            "Pinned media descriptor cleanup is unconfirmed"
        }
        val duplicate = ParcelFileDescriptor.dup(descriptor.parcelFileDescriptor.fileDescriptor)
        constructWithOwnedCloseOnFailure(
            resource = duplicate,
            onCloseFailure = { error ->
                PinnedMediaCleanupState.markUnconfirmed(
                    error.javaClass.simpleName.ifBlank { error.javaClass.name },
                )
            },
        ) { ownedDuplicate ->
            trackedPinnedMediaDescriptor(
                AssetFileDescriptor(
                    ownedDuplicate,
                    descriptor.startOffset,
                    descriptor.declaredLength,
                ),
            )
        }
    }

    fun closeWithResult(): Boolean {
        var terminalFailure: Throwable? = null
        val result = synchronized(lock) {
            closed = true
            try {
                closeState.closeWithResult()
            } catch (error: Error) {
                terminalFailure = error
                // BoundedCloseState publishes its stable close evidence before rethrowing.
                closeState.closeWithResult()
            }
        }
        if (!result) {
            try {
                PinnedMediaCleanupState.markUnconfirmed(
                    closeState.lastFailureClass() ?: "unknown",
                )
            } catch (error: Throwable) {
                terminalFailure =
                    mergeFailurePreservingFatal(terminalFailure, error)
            }
        }
        terminalFailure?.let { throw it }
        return result
    }

    override fun close() {
        closeWithResult()
    }
}

/**
 * Keeps the first descriptor cleanup failure process-sticky. Continuing with another selected
 * video after a master FD could not be closed would hide a native/provider resource leak and can
 * accumulate one leaked descriptor per run.
 */
internal object PinnedMediaCleanupState {
    private val failureClass = AtomicReference<String?>(null)

    fun hasUnconfirmedCleanup(): Boolean = failureClass.get() != null

    fun detail(): String? = failureClass.get()

    fun markUnconfirmed(failure: String) {
        failureClass.compareAndSet(null, failure.take(MAX_FAILURE_CLASS_CHARS))
    }

    private const val MAX_FAILURE_CLASS_CHARS = 96
}

/**
 * Owns a successfully constructed closeable until its bounded close is confirmed.
 *
 * [close] throws after publishing the sticky failure so Kotlin's `use` aborts the current
 * preflight/decoder path instead of allowing a leaked duplicate descriptor to look successful.
 * Callers that are already on a cancellation/finalization path can use [closeWithResult] to keep
 * the original terminal reason while still preserving the process-wide cleanup latch.
 */
internal class BoundedOwnedResource<T : AutoCloseable>(
    val resource: T,
    private val onTerminalCloseFailure: (String) -> Unit = {},
) : Closeable {
    private val closeState = BoundedCloseState(resource::close)
    private val failurePublished = AtomicBoolean(false)

    fun closeWithResult(): Boolean {
        var fatalFailure: Error? = null
        val confirmed = try {
            closeState.closeWithResult()
        } catch (error: Error) {
            fatalFailure = error
            closeState.closeWithResult()
        }
        if (!confirmed && failurePublished.compareAndSet(false, true)) {
            try {
                onTerminalCloseFailure(closeState.lastFailureClass() ?: "unknown")
            } catch (error: Throwable) {
                val merged = mergeFailurePreservingFatal(fatalFailure, error)
                if (merged is Error) {
                    fatalFailure = merged
                } else {
                    throw merged
                }
            }
        }
        fatalFailure?.let { throw it }
        return confirmed
    }

    override fun close() {
        check(closeWithResult()) {
            "Owned resource cleanup could not be confirmed " +
                "(${closeState.lastFailureClass() ?: "unknown"})"
        }
    }
}

internal fun trackedPinnedMediaDescriptor(
    descriptor: AssetFileDescriptor,
): BoundedOwnedResource<AssetFileDescriptor> =
    BoundedOwnedResource(descriptor, PinnedMediaCleanupState::markUnconfirmed)

internal fun closePinnedMediaDescriptor(descriptor: AssetFileDescriptor): Boolean =
    trackedPinnedMediaDescriptor(descriptor).closeWithResult()

/**
 * Close is attempted a small fixed number of times and the terminal result is stable for all
 * later callers. ParcelFileDescriptor.close() is idempotent; the second attempt covers transient
 * close failures without introducing an unbounded cleanup loop.
 */
internal class BoundedCloseState(
    private val closer: () -> Unit,
    private val maxAttempts: Int = DEFAULT_CLOSE_ATTEMPTS,
) {
    private val lock = Any()
    private var result: Boolean? = null
    private var failureClass: String? = null

    init {
        require(maxAttempts in 1..MAX_CLOSE_ATTEMPTS)
    }

    fun closeWithResult(): Boolean = synchronized(lock) {
        result?.let { return it }
        var confirmed = false
        var terminalFailure: Throwable? = null
        var attemptsRemaining = maxAttempts
        while (!confirmed && attemptsRemaining > 0) {
            attemptsRemaining -= 1
            try {
                closer()
                confirmed = true
            } catch (error: Throwable) {
                terminalFailure =
                    mergeFailurePreservingFatal(terminalFailure, error)
                try {
                    failureClass = error.javaClass.simpleName.ifBlank {
                        error.javaClass.name
                    }
                } catch (recordError: Throwable) {
                    terminalFailure =
                        mergeFailurePreservingFatal(terminalFailure, recordError)
                }
            }
        }
        result = confirmed
        terminalFailure?.let { failure ->
            if (failure is Error) throw failure
        }
        confirmed
    }

    fun lastFailureClass(): String? = synchronized(lock) { failureClass }

    private companion object {
        const val DEFAULT_CLOSE_ATTEMPTS = 2
        const val MAX_CLOSE_ATTEMPTS = 3
    }
}

/**
 * Transfers [resource] to [factory]. If construction fails before ownership transfer completes,
 * the resource is closed. Ordinary close failures are attached to the original exception; a
 * cleanup [Error] is promoted while retaining the original failure as suppressed evidence.
 */
internal inline fun <T : AutoCloseable, R> constructWithOwnedCloseOnFailure(
    resource: T,
    onCloseFailure: (Throwable) -> Unit = {},
    factory: (T) -> R,
): R {
    try {
        return factory(resource)
    } catch (error: Throwable) {
        var terminal: Throwable = error
        try {
            resource.close()
        } catch (cleanupError: Throwable) {
            terminal = mergeFailurePreservingFatal(terminal, cleanupError)
            try {
                onCloseFailure(cleanupError)
            } catch (reportError: Throwable) {
                terminal = mergeFailurePreservingFatal(terminal, reportError)
            }
        }
        throw terminal
    }
}

internal data class VideoDimensions(
    val widthPx: Int,
    val heightPx: Int,
)

/**
 * Converts an untrusted Number without the wrapping/truncation of Number.toInt().
 */
internal fun exactMediaIntegerOrNull(value: Number?): Int? {
    val numeric = value?.toDouble() ?: return null
    if (
        !numeric.isFinite() ||
        numeric < Int.MIN_VALUE.toDouble() ||
        numeric > Int.MAX_VALUE.toDouble() ||
        numeric % 1.0 != 0.0
    ) {
        return null
    }
    return numeric.toInt()
}

/**
 * Conservative allocation ceiling for codec stride/height padding.
 */
internal fun videoDimensionCeiling(
    value: Int,
    alignmentPx: Int = VIDEO_BUFFER_ALIGNMENT_PX,
): Int? {
    if (value <= 0 || alignmentPx <= 0) return null
    val rounded = ((value.toLong() + alignmentPx - 1L) / alignmentPx) * alignmentPx
    return rounded.takeIf { it in 1L..Int.MAX_VALUE.toLong() }?.toInt()
}

/**
 * The lab uses a fixed-resolution decoder budget. Missing max keys are accepted as a fixed track;
 * when a container advertises them, both must exactly match the encoded dimensions. Larger,
 * smaller, or partial adaptive declarations are rejected before MediaCodec allocation.
 */
internal fun fixedVideoMaximumDimensionsMatch(
    encodedWidthPx: Int?,
    encodedHeightPx: Int?,
    declaredMaxWidthPx: Int?,
    declaredMaxHeightPx: Int?,
): Boolean {
    val width = encodedWidthPx?.takeIf { it > 0 } ?: return false
    val height = encodedHeightPx?.takeIf { it > 0 } ?: return false
    return when {
        declaredMaxWidthPx == null && declaredMaxHeightPx == null -> true
        declaredMaxWidthPx == null || declaredMaxHeightPx == null -> false
        else -> declaredMaxWidthPx == width && declaredMaxHeightPx == height
    }
}

/**
 * MediaFormat crop right/bottom are inclusive. Android applies the horizontal and vertical crop
 * pairs independently; a missing pair means that axis occupies the full encoded frame. A lone
 * key within either pair or an out-of-range coordinate is rejected.
 */
internal fun visibleVideoDimensions(
    encodedWidthPx: Int?,
    encodedHeightPx: Int?,
    cropLeft: Int? = null,
    cropRight: Int? = null,
    cropTop: Int? = null,
    cropBottom: Int? = null,
): VideoDimensions? {
    val width = encodedWidthPx?.takeIf { it > 0 } ?: return null
    val height = encodedHeightPx?.takeIf { it > 0 } ?: return null

    val left: Int
    val right: Int
    when {
        cropLeft == null && cropRight == null -> {
            left = 0
            right = width - 1
        }
        cropLeft != null && cropRight != null -> {
            left = cropLeft
            right = cropRight
        }
        else -> return null
    }
    val top: Int
    val bottom: Int
    when {
        cropTop == null && cropBottom == null -> {
            top = 0
            bottom = height - 1
        }
        cropTop != null && cropBottom != null -> {
            top = cropTop
            bottom = cropBottom
        }
        else -> return null
    }
    if (
        left < 0 ||
        top < 0 ||
        right < left ||
        bottom < top ||
        right >= width ||
        bottom >= height
    ) {
        return null
    }
    val visibleWidth = right.toLong() - left.toLong() + 1L
    val visibleHeight = bottom.toLong() - top.toLong() + 1L
    if (
        visibleWidth !in 1L..Int.MAX_VALUE.toLong() ||
        visibleHeight !in 1L..Int.MAX_VALUE.toLong()
    ) {
        return null
    }
    return VideoDimensions(visibleWidth.toInt(), visibleHeight.toInt())
}

internal fun sameVideoDimensions(
    firstWidthPx: Int,
    firstHeightPx: Int,
    secondWidthPx: Int,
    secondHeightPx: Int,
): Boolean {
    if (
        firstWidthPx <= 0 ||
        firstHeightPx <= 0 ||
        secondWidthPx <= 0 ||
        secondHeightPx <= 0
    ) {
        return false
    }
    return minOf(firstWidthPx, firstHeightPx) == minOf(secondWidthPx, secondHeightPx) &&
        maxOf(firstWidthPx, firstHeightPx) == maxOf(secondWidthPx, secondHeightPx)
}

internal fun videoDimensionsFitWithin(
    widthPx: Int,
    heightPx: Int,
    ceilingWidthPx: Int,
    ceilingHeightPx: Int,
): Boolean {
    if (
        widthPx <= 0 ||
        heightPx <= 0 ||
        ceilingWidthPx <= 0 ||
        ceilingHeightPx <= 0
    ) {
        return false
    }
    return minOf(widthPx, heightPx) <= minOf(ceilingWidthPx, ceilingHeightPx) &&
        maxOf(widthPx, heightPx) <= maxOf(ceilingWidthPx, ceilingHeightPx)
}

internal fun videoFrameRatesMatch(
    first: Float?,
    second: Float,
    tolerance: Float = 0.5f,
): Boolean =
    first != null &&
        first.isFinite() &&
        first > 0f &&
        second.isFinite() &&
        second > 0f &&
        tolerance.isFinite() &&
        tolerance >= 0f &&
        kotlin.math.abs(first - second) <= tolerance

internal fun presentationVideoDimensions(
    dimensions: VideoDimensions,
    rotationDegrees: Int,
): VideoDimensions? = when (rotationDegrees) {
    0, 180 -> dimensions
    90, 270 -> VideoDimensions(dimensions.heightPx, dimensions.widthPx)
    else -> null
}

/**
 * Hashes bounded codec-specific data without changing the source ByteBuffer positions.
 *
 * CSD is small for supported video codecs. Rejecting exceptional entry counts/sizes prevents an
 * untrusted container from turning MediaCodec.configure() into an unbounded native allocation.
 */
internal fun boundedCodecConfigFingerprint(
    entries: List<Pair<String, ByteBuffer>>,
): String? {
    if (entries.size > MAX_CODEC_CONFIG_ENTRIES) return null
    val sorted = entries.sortedBy { it.first }
    if (sorted.map { it.first }.distinct().size != sorted.size) return null
    var totalBytes = 0L
    val digest = runCatching { MessageDigest.getInstance("SHA-256") }.getOrNull()
        ?: return null
    digest.update(CODEC_CONFIG_FINGERPRINT_VERSION)
    for ((key, source) in sorted) {
        if (
            !key.startsWith("csd-") ||
            key.length !in 5..MAX_CODEC_CONFIG_KEY_CHARS
        ) {
            return null
        }
        val keyBytes = key.toByteArray(StandardCharsets.UTF_8)
        val duplicate = runCatching { source.asReadOnlyBuffer() }.getOrNull()
            ?: return null
        val size = duplicate.remaining()
        if (size < 0 || size > MAX_CODEC_CONFIG_ENTRY_BYTES) return null
        totalBytes += size.toLong()
        if (totalBytes > MAX_CODEC_CONFIG_TOTAL_BYTES) return null
        digest.update(intBytes(keyBytes.size))
        digest.update(keyBytes)
        digest.update(intBytes(size))
        digest.update(duplicate)
    }
    return digest.digest().joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
}

private fun intBytes(value: Int): ByteArray =
    byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

internal const val MAX_BOUND_COMPRESSED_SAMPLE_BYTES = 32 * 1024 * 1024
private const val MAX_CODEC_CONFIG_ENTRIES = 16
private const val MAX_CODEC_CONFIG_KEY_CHARS = 64
private const val MAX_CODEC_CONFIG_ENTRY_BYTES = 1024 * 1024
private const val MAX_CODEC_CONFIG_TOTAL_BYTES = 4L * 1024L * 1024L
private val CODEC_CONFIG_FINGERPRINT_VERSION =
    "DPU-LAYER-LAB-CSD-V1".toByteArray(StandardCharsets.US_ASCII)
private const val VIDEO_BUFFER_ALIGNMENT_PX = 64
