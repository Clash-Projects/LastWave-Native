package com.lastwave.app.ui.moods

import com.lastwave.app.data.music.MoodTile
import java.net.URLEncoder

internal object MoodsRoutes {
    const val GROUPS = "moods_and_genres"
    const val CATEGORY = "moods_category?browseId={browseId}&params={params}&title={title}"

    fun category(tile: MoodTile): String =
        "moods_category?browseId=${encode(tile.browseId)}&params=${encode(tile.params)}&title=${encode(tile.label)}"

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
