package com.example.dpulayerlab.render

import android.media.MediaCodec
import android.media.MediaExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MediaSampleFlagsTest {
    @Test
    fun clearExtractorFlagsMapToCodecFlagsWithoutBitAliasing() {
        assertEquals(0, mediaCodecInputFlags(0))
        assertEquals(
            MediaCodec.BUFFER_FLAG_KEY_FRAME,
            mediaCodecInputFlags(MediaExtractor.SAMPLE_FLAG_SYNC),
        )
        assertEquals(
            MediaCodec.BUFFER_FLAG_PARTIAL_FRAME,
            mediaCodecInputFlags(MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME),
        )
        assertEquals(
            MediaCodec.BUFFER_FLAG_KEY_FRAME or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME,
            mediaCodecInputFlags(
                MediaExtractor.SAMPLE_FLAG_SYNC or
                    MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME,
            ),
        )
    }

    @Test
    fun encryptedAndUnknownExtractorFlagsFailClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            mediaCodecInputFlags(MediaExtractor.SAMPLE_FLAG_ENCRYPTED)
        }
        assertThrows(IllegalArgumentException::class.java) {
            mediaCodecInputFlags(1 shl 20)
        }
        assertThrows(IllegalArgumentException::class.java) {
            mediaCodecInputFlags(-1)
        }
    }
}
