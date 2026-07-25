package com.example.dpulayerlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {
    @Test
    fun releaseVersionUsesTimestampAndOnlyDebugAddsVariantSuffix() {
        val expected = if (BuildConfig.DEBUG) {
            "20260725_232013-debug"
        } else {
            "20260725_232013"
        }
        assertEquals(expected, BuildConfig.VERSION_NAME)
        assertTrue(
            BuildConfig.VERSION_NAME.matches(
                Regex("""\d{8}_\d{6}(?:-debug)?"""),
            ),
        )
    }

    @Test
    fun versionCodeMatchesRelease() {
        assertEquals(7, BuildConfig.VERSION_CODE)
    }
}
