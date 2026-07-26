package com.example.dpulayerlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {
    @Test
    fun releaseVersionUsesTimestampAndOnlyDebugAddsVariantSuffix() {
        val expected = if (BuildConfig.DEBUG) {
            "20260726_101046-debug"
        } else {
            "20260726_101046"
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
        assertEquals(8, BuildConfig.VERSION_CODE)
    }
}
