package com.example.dpulayerlab.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RendererContainerRememberOwnerTest {
    @Test
    fun abandonedCompositionNeverAcquiresRendererToken() {
        var attachCount = 0
        var disposeCount = 0
        val owner = RendererContainerRememberOwner(
            attach = {
                attachCount++
                1L
            },
            dispose = {
                disposeCount++
            },
        )

        owner.onAbandoned()
        owner.onForgotten()

        assertEquals(0, attachCount)
        assertEquals(0, disposeCount)
    }

    @Test
    fun committedOwnerDisposesMatchingTokenExactlyOnce() {
        val disposedTokens = mutableListOf<Long>()
        val owner = RendererContainerRememberOwner(
            attach = { 37L },
            dispose = { disposedTokens += it },
        )

        owner.onRemembered()
        owner.onForgotten()
        owner.onAbandoned()

        assertEquals(listOf(37L), disposedTokens)
    }

    @Test
    fun forgottenOwnerCanBeRememberedAgainWithANewToken() {
        var nextToken = 0L
        val disposedTokens = mutableListOf<Long>()
        val owner = RendererContainerRememberOwner(
            attach = {
                nextToken++
                nextToken
            },
            dispose = { disposedTokens += it },
        )

        owner.onRemembered()
        owner.onForgotten()
        owner.onRemembered()
        owner.onForgotten()

        assertEquals(listOf(1L, 2L), disposedTokens)
    }
}
