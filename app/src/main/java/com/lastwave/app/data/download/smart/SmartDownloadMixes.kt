package com.lastwave.app.data.download.smart

import com.lastwave.app.data.music.HomeShelfItem
import com.lastwave.app.data.music.InnerTubeMusicApi

// YouTube's offline/auto_offline answers WEB_REMIX with timing only, even with Smart
// downloads on in YouTube Music, and app clients reject cookie auth. The personal mixes
// that Smart downloads saves (Liked Music, Supermix, Replay/Discover mixes) are on Home.
internal suspend fun InnerTubeMusicApi.fetchPersonalMixIds(): List<String> {
    val ids = linkedSetOf<String>()
    var page = fetchHomePage()
    repeat(HOME_PAGE_LIMIT) {
        page.shelves.asSequence().flatMap { it.items }.filterIsInstance<HomeShelfItem.Playlist>()
            .map { it.playlist.id.removePrefix("VL") }
            .filterTo(ids) { it == "LM" || it.startsWith("RDTMAK5uy_") }
        page = fetchHomeContinuation(page.continuationToken ?: return ids.toList())
    }
    return ids.toList()
}

private const val HOME_PAGE_LIMIT = 6

/** Round-robin across groups so one large playlist cannot take the whole song limit. */
internal fun <T> Map<String, List<T>>.interleaved(): List<T> {
    val groups = values.map { it.iterator() }
    return buildList {
        while (groups.any { it.hasNext() }) groups.forEach { if (it.hasNext()) add(it.next()) }
    }
}
