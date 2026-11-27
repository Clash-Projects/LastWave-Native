package com.lastwave.app.data.local.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

/**
 * Port of the original's `lw_playlists` localStorage array (playlist.js's
 * _plSave/_plLoad): one row per saved playlist. [tracksJson] holds the
 * track list serialized as JSON (name/artist/url/image/listeners/playcount/
 * match/album per entry) rather than a normalized child table — this
 * mirrors the original's `{ id, title, subtitle, mode, tracks, date }` shape
 * exactly and keeps read/write a single round trip, same as the original's
 * single localStorage key.
 *
 * [discoverSignature] is only set for mode == "discover" (Save As Playlist's
 * order-preserving track-set signature, used to detect "this exact Discover
 * feed is already saved" — see _discTrackSignature in the original).
 */
@Entity(tableName = "saved_playlists")
data class SavedPlaylistEntity(
    @PrimaryKey val id: Long,
    val title: String,
    val subtitle: String,
    val mode: String,
    val tracksJson: String,
    val createdAtMillis: Long,
    val discoverSignature: String? = null,
    val customCoverUri: String? = null,
    val isPinned: Boolean = false,
)

// Android's CursorWindow caps a single query row at ~2 MB, throwing
// SQLiteBlobTooBigException once a large playlist's tracksJson crosses that limit.
// Headers inline small playlists and read oversized ones via bounded SUBSTR chunks
// inside a single @Transaction snapshot so existing large rows recover seamlessly.
private const val TRACKS_CHUNK_CHARS = 250_000
private const val HEADER_COLUMNS =
    "id, title, subtitle, mode, " +
        "CASE WHEN LENGTH(tracksJson) <= 100000 THEN tracksJson ELSE NULL END AS inlineTracksJson, " +
        "COALESCE(LENGTH(tracksJson), 0) AS tracksLength, " +
        "createdAtMillis, discoverSignature, customCoverUri, isPinned"

data class SavedPlaylistHeaderRow(
    val id: Long,
    val title: String,
    val subtitle: String,
    val mode: String,
    val inlineTracksJson: String?,
    val tracksLength: Int,
    val createdAtMillis: Long,
    val discoverSignature: String? = null,
    val customCoverUri: String? = null,
    val isPinned: Boolean = false,
)

@Dao
interface SavedPlaylistDao {
    @Query("SELECT $HEADER_COLUMNS FROM saved_playlists ORDER BY createdAtMillis ASC")
    suspend fun getAllHeaders(): List<SavedPlaylistHeaderRow>

    @Query("SELECT $HEADER_COLUMNS FROM saved_playlists WHERE id = :id LIMIT 1")
    suspend fun getHeaderById(id: Long): SavedPlaylistHeaderRow?

    @Query("SELECT $HEADER_COLUMNS FROM saved_playlists WHERE mode = :mode ORDER BY createdAtMillis DESC, id ASC LIMIT 1")
    suspend fun getNewestHeaderByMode(mode: String): SavedPlaylistHeaderRow?

    @Query("SELECT SUBSTR(tracksJson, :offset, :length) FROM saved_playlists WHERE id = :id")
    suspend fun readTracksChunk(id: Long, offset: Int, length: Int): String?

    /** Newest-last, matching the original's storage order (it reverses for
     *  display in _plRenderSaved — done in the repository/UI layer instead). */
    @Transaction
    suspend fun getAll(): List<SavedPlaylistEntity> =
        getAllHeaders().map { hydrate(it) }

    @Transaction
    suspend fun getById(id: Long): SavedPlaylistEntity? =
        getHeaderById(id)?.let { hydrate(it) }

    /** Newest row of [mode]; same pick as the newest-first list, ties to the lower id. */
    @Transaction
    suspend fun getNewestByMode(mode: String): SavedPlaylistEntity? =
        getNewestHeaderByMode(mode)?.let { hydrate(it) }

    @Upsert
    suspend fun upsert(entity: SavedPlaylistEntity)

    @Query("UPDATE saved_playlists SET tracksJson = :tracksJson WHERE id = :id AND tracksJson = :expectedTracksJson")
    suspend fun updateTracksIfUnchanged(id: Long, expectedTracksJson: String, tracksJson: String): Int

    @Upsert
    suspend fun upsertAll(entities: List<SavedPlaylistEntity>)

    @Query("DELETE FROM saved_playlists WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM saved_playlists")
    suspend fun count(): Int

    @Query("SELECT id FROM saved_playlists WHERE mode NOT IN ('custom', 'liked', 'local') AND isPinned = 0 ORDER BY createdAtMillis DESC")
    suspend fun getUnpinnedGeneratedIds(): List<Long>

    /** Keeps pinned/generated playlists plus the newest [max] unpinned ones. */
    @Transaction
    suspend fun trimGeneratedToNewest(max: Int) {
        val ids = getUnpinnedGeneratedIds()
        if (ids.size > max) {
            ids.drop(max).forEach { deleteById(it) }
        }
    }

    @Query("DELETE FROM saved_playlists")
    suspend fun clear()

    @Transaction
    suspend fun replaceAll(entities: List<SavedPlaylistEntity>) {
        clear()
        upsertAll(entities)
    }
}

private suspend fun SavedPlaylistDao.hydrate(header: SavedPlaylistHeaderRow): SavedPlaylistEntity {
    val tracksJson = header.inlineTracksJson ?: buildString(header.tracksLength.coerceAtLeast(0)) {
        // SQLite LENGTH/SUBSTR index by Unicode code points, whereas Kotlin String.length
        // counts UTF-16 code units (surrogate-pair emojis count as 2), so advance by code points.
        var offset = 1
        while (offset <= header.tracksLength) {
            val chunk = readTracksChunk(header.id, offset, TRACKS_CHUNK_CHARS).orEmpty()
            if (chunk.isEmpty()) break
            append(chunk)
            val codePoints = Character.codePointCount(chunk, 0, chunk.length)
            if (codePoints <= 0) break
            offset += codePoints
        }
    }
    return SavedPlaylistEntity(
        id = header.id,
        title = header.title,
        subtitle = header.subtitle,
        mode = header.mode,
        tracksJson = tracksJson,
        createdAtMillis = header.createdAtMillis,
        discoverSignature = header.discoverSignature,
        customCoverUri = header.customCoverUri,
        isPinned = header.isPinned,
    )
}
