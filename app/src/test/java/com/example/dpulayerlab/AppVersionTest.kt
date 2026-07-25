package com.example.dpulayerlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {
    @Test
    fun versionUsesSourceCandidateTimestampAndOnlyDebugAddsVariantSuffix() {
        val expected = if (BuildConfig.DEBUG) {
            "20260725_095708-debug"
        } else {
            "20260725_095708"
        }
        assertEquals(expected, BuildConfig.VERSION_NAME)
        assertTrue(
            BuildConfig.VERSION_NAME.matches(
                Regex("""\d{8}_\d{6}(?:-debug)?"""),
            ),
        )
    }

    @Test
    fun versionCodeMatchesSourceCandidate() {
        assertEquals(5, BuildConfig.VERSION_CODE)
    }
}
