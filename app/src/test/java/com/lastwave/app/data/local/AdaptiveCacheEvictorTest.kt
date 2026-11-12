package com.lastwave.app.data.local

import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.TreeSet
import java.util.concurrent.atomic.AtomicLong

class AdaptiveCacheEvictorTest {

    @Test
    fun reducingLimitEvictsOldestSpansFirst() {
        val fixture = cacheWithSpans(
            Span("oldest", 10L, touchedAt = 1L),
            Span("middle", 10L, touchedAt = 2L),
            Span("newest", 10L, touchedAt = 3L),
        )
        val evictor = AdaptiveCacheEvictor(initialMaxBytes = 100L)
        evictor.attach(fixture.cache)

        evictor.setMaxBytes(15L)

        assertEquals(10L, fixture.space.get())
        assertEquals(setOf("newest"), fixture.spans.filterValues { it.isNotEmpty() }.keys)
    }

    @Test
    fun knownIncomingFileLengthIsReservedBeforeWriting() {
        val fixture = cacheWithSpans(
            Span("first", 10L, touchedAt = 1L),
            Span("second", 10L, touchedAt = 2L),
        )
        val evictor = AdaptiveCacheEvictor(initialMaxBytes = 25L)
        evictor.attach(fixture.cache)

        evictor.onStartFile(fixture.cache, "next", 0L, 10L)

        assertEquals(10L, fixture.space.get())
        assertEquals(setOf("second"), fixture.spans.filterValues { it.isNotEmpty() }.keys)
    }

    private data class Span(val key: String, val bytes: Long, val touchedAt: Long)
    private data class CacheFixture(
        val cache: Cache,
        val spans: MutableMap<String, MutableList<CacheSpan>>,
        val space: AtomicLong,
    )

    private fun cacheWithSpans(vararg entries: Span): CacheFixture {
        val spans = entries.associate { entry ->
            entry.key to mutableListOf(
                CacheSpan(entry.key, 0L, entry.bytes, entry.touchedAt, File(entry.key)),
            )
        }.toMutableMap()
        val space = AtomicLong(entries.sumOf(Span::bytes))
        val cache = mockk<Cache>(relaxed = true)
        every { cache.keys } answers { spans.filterValues { it.isNotEmpty() }.keys }
        every { cache.cacheSpace } answers { space.get() }
        every { cache.getCachedSpans(any()) } answers {
            TreeSet(spans[firstArg<String>()].orEmpty())
        }
        every { cache.removeSpan(any()) } answers {
            val span = firstArg<CacheSpan>()
            val removed = spans[span.key]?.removeAll { it.position == span.position } == true
            if (removed) space.addAndGet(-span.length)
        }
        return CacheFixture(cache, spans, space)
    }
}
