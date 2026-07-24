package com.example.dpulayerlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {
    @Test
    fun versionUsesReleaseTimestampAndOnlyDebugAddsVariantSuffix() {
        val expected = if (BuildConfig.DEBUG) {
            "20260724_111816-debug"
        } else {
            "20260724_111816"
        }
        assertEquals(expected, BuildConfig.VERSION_NAME)
        assertTrue(
            BuildConfig.VERSION_NAME.matches(
                Regex("""\d{8}_\d{6}(?:-debug)?"""),
            ),
        )
    }

    @Test
    fun versionCodeAdvancesPastThePreviousRelease() {
        assertTrue(BuildConfig.VERSION_CODE > 2)
    }
}
