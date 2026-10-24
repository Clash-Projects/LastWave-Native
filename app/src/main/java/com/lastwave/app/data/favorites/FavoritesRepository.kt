package com.lastwave.app.data.favorites

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** A favorited album or artist: a shortcut that reopens its detail page. */
@Serializable
data class FavoriteItem(
    val title: String,
    /** Album artist; empty for an artist favorite. */
    val artist: String = "",
    val browseId: String = "",
    val artworkUrl: String? = null,
    val addedAtMillis: Long = 0L,
) {
    /** Progressive page loads may fill browseId late, so name equality also counts. */
    fun matches(other: FavoriteItem): Boolean =
        (browseId.isNotBlank() && browseId == other.browseId) ||
            (title.trim().equals(other.title.trim(), ignoreCase = true) &&
                artist.trim().equals(other.artist.trim(), ignoreCase = true))
}

enum class FavoriteKind(val prefKey: String) { ALBUM("favorite_albums"), ARTIST("favorite_artists") }

/**
 * Favorite albums and artists, newest first, stored as JSON in the main
 * DataStore so [com.lastwave.app.data.backup.BackupRepository] carries them.
 */
@Singleton
class FavoritesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    fun favorites(kind: FavoriteKind): Flow<List<FavoriteItem>> =
        dataStore.data.map { decode(it[stringPreferencesKey(kind.prefKey)]) }

    suspend fun toggle(kind: FavoriteKind, item: FavoriteItem) {
        dataStore.edit { prefs ->
            val key = stringPreferencesKey(kind.prefKey)
            prefs[key] = encode(toggled(decode(prefs[key]), item))
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun decode(raw: String?): List<FavoriteItem> =
            raw?.let { runCatching { json.decodeFromString<List<FavoriteItem>>(it) }.getOrNull() }.orEmpty()

        fun encode(items: List<FavoriteItem>): String = json.encodeToString(items)

        fun toggled(items: List<FavoriteItem>, item: FavoriteItem): List<FavoriteItem> =
            if (items.any { it.matches(item) }) items.filterNot { it.matches(item) }
            else listOf(item.copy(addedAtMillis = System.currentTimeMillis())) + items
    }
}
