package com.lastwave.app.ui.moods

import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.music.MoodTile
import java.net.URLDecoder
import org.junit.Test

class MoodsRoutesTest {
    @Test
    fun categoryArgumentsSurviveOneDecodeIncludingOpaqueParamsAndNonLatinLabels() {
        val tile = MoodTile(
            label = "आराम & Chill / focus?",
            colorArgb = 0xFFCC0000,
            browseId = "FEmusic_moods_and_genres_category",
            params = "opaque+/value==%2F&next=?",
        )
        val route = MoodsRoutes.category(tile)
        val arguments = route.substringAfter('?').split('&').associate { argument ->
            argument.substringBefore('=') to URLDecoder.decode(argument.substringAfter('='), "UTF-8")
        }

        assertThat(route.substringBefore('?')).isEqualTo("moods_category")
        assertThat(arguments).containsExactly("browseId", tile.browseId, "params", tile.params, "title", tile.label)
        assertThat(route).doesNotContain(" ")
    }
}
