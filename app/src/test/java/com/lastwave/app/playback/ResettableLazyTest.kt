package com.lastwave.app.playback

import org.junit.Assert.*
import org.junit.Test

class ResettableLazyTest {
    @Test fun initializesOnlyWhenNeededAndReusesTheLiveResource() {
        var created = 0
        val resource = ResettableLazy { FakeDeck(++created) }
        assertFalse(resource.isInitialized())
        assertEquals(0, created)
        val first = resource.value
        assertTrue(resource.isInitialized())
        assertSame(first, resource.value)
        assertEquals(1, created)
    }

    @Test fun serviceRecreationNeverReturnsAReleasedDeck() {
        var generation = 0
        val resource = ResettableLazy { FakeDeck(++generation) }
        repeat(5) {
            val deck = resource.value
            assertEquals(it + 1, deck.generation)
            deck.prepare()
            deck.released = true
            resource.reset()
            assertFalse(resource.isInitialized())
            val recreated = resource.value
            assertNotSame(deck, recreated)
            recreated.prepare()
        }
    }

    @Test fun releasingAnUnusedOwnerDoesNotCreateAResource() {
        var created = 0
        val resource = ResettableLazy { FakeDeck(++created) }
        resource.reset()
        resource.reset()
        assertFalse(resource.isInitialized())
        assertEquals(0, created)
    }

    @Test fun aFailedInitializationCanBeRetried() {
        var attempts = 0
        val resource = ResettableLazy {
            if (++attempts == 1) throw IllegalStateException("creation failed")
            FakeDeck(attempts)
        }
        try {
            resource.value
            fail("Expected creation failure")
        } catch (_: IllegalStateException) { }
        assertFalse(resource.isInitialized())
        resource.value.prepare()
        assertEquals(2, attempts)
    }

    private class FakeDeck(val generation: Int, var released: Boolean = false) {
        fun prepare() { check(!released) { "Cannot prepare a released deck" } }
    }
}
