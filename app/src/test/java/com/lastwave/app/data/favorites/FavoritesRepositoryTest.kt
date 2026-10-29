package com.lastwave.app.data.favorites

import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.favorites.FavoritesRepository.Companion.decode
import com.lastwave.app.data.favorites.FavoritesRepository.Companion.encode
import com.lastwave.app.data.favorites.FavoritesRepository.Companion.toggled
import org.junit.Test

class FavoritesRepositoryTest {

    private val album = FavoriteItem(title = "Discovery", artist = "Daft Punk", browseId = "MPREb_1")

    @Test
    fun toggleAddsNewestFirstThenRemoves() {
        val other = FavoriteItem(title = "Homework", artist = "Daft Punk")
        val added = toggled(toggled(emptyList(), other), album)
        assertThat(added.map { it.title }).containsExactly("Discovery", "Homework").inOrder()
        assertThat(toggled(added, album).map { it.title }).containsExactly("Homework")
    }

    @Test
    fun matchesByNameWhenBrowseIdArrivesLate() {
        val partial = FavoriteItem(title = " discovery", artist = "DAFT PUNK")
        assertThat(partial.matches(album)).isTrue()
        assertThat(toggled(listOf(partial), album)).isEmpty()
    }

    @Test
    fun differentAlbumsDoNotMatch() {
        assertThat(album.matches(FavoriteItem(title = "Discovery", artist = "Other"))).isFalse()
    }

    @Test
    fun encodeDecodeRoundTripAndBadInput() {
        assertThat(decode(encode(listOf(album)))).containsExactly(album)
        assertThat(decode(null)).isEmpty()
        assertThat(decode("not json")).isEmpty()
    }
}
