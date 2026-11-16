package com.lastwave.app.ui.player

import com.lastwave.app.data.lyrics.providerForSource

/**
 * Shortest song intro that gets the signature card. Below this the card could
 * not fade in, be read and fold away before the first line lands.
 */
internal const val LYRICS_INTRO_MIN_MS = 1_500L

/**
 * Whether the intro card should take the stage when it would appear at
 * [positionMs]. Lyrics arriving late in the intro, or a seek close to the
 * first line, leave too little time and would only flash the card.
 */
internal fun shouldShowSignatureIntro(introEndMs: Long, positionMs: Long): Boolean =
    introEndMs - positionMs >= LYRICS_INTRO_MIN_MS

/**
 * Name to credit for a lyrics source string ("Apple Music (Line-Sync)" ->
 * "Apple Music"), or null when there is no provider to credit: files saved
 * on the device no longer say who wrote them.
 */
internal fun lyricsProviderLabel(source: String?): String? {
    if (source.isNullOrBlank()) return null
    providerForSource(source)?.let { return it.title }
    val lower = source.lowercase()
    if (lower.startsWith("local") || lower.startsWith("downloaded")) return null
    return source.substringBefore(" (").trim().ifBlank { null }
}
