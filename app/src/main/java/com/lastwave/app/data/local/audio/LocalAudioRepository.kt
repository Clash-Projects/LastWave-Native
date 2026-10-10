package com.lastwave.app.data.local.audio

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.playback.PlayableTrack
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class LocalAudioTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val contentUri: String,
    val dataPath: String?,
    val albumId: Long?,
    val mimeType: String?,
    val dateAddedSec: Long,
)

fun LocalAudioTrack.toPlayableTrack(): PlayableTrack = PlayableTrack(
    title = title,
    artist = artist,
    album = album.takeIf { it.isNotBlank() && it != "<unknown>" },
    artworkUrl = albumId?.let { "content://media/external/audio/albumart/$it" },
    playbackUrl = contentUri,
    playbackMimeType = mimeType ?: "audio/*",
    durationMs = durationMs.takeIf { it > 0L },
)

fun LocalAudioTrack.toGeneratedTrack(): GeneratedTrack = GeneratedTrack(
    name = title,
    artist = artist,
    album = album.takeIf { it.isNotBlank() && it != "<unknown>" },
    artworkUrl = albumId?.let { "content://media/external/audio/albumart/$it" },
    url = contentUri,
)

@Singleton
class LocalAudioRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _tracks = MutableStateFlow<List<LocalAudioTrack>>(emptyList())
    val tracks: Flow<List<LocalAudioTrack>> = _tracks.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: Flow<Boolean> = _isLoading.asStateFlow()

    fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    suspend fun refresh(): List<LocalAudioTrack> = withContext(Dispatchers.IO) {
        if (!hasStoragePermission()) {
            _tracks.value = emptyList()
            return@withContext emptyList()
        }
        _isLoading.value = true
        try {
            val list = queryAudioInternal()
            _tracks.value = list
            list
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to query local audio files", e)
            emptyList()
        } finally {
            _isLoading.value = false
        }
    }

    private fun queryAudioInternal(): List<LocalAudioTrack> {
        val results = mutableListOf<LocalAudioTrack>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DATE_ADDED,
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 10000"
        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

        val cursor: Cursor? = context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            sortOrder,
        )

        cursor?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val dataCol = c.getColumnIndex(MediaStore.Audio.Media.DATA)
            val albumIdCol = c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
            val mimeCol = c.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
            val dateAddedCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)

            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val title = c.getString(titleCol).orEmpty().ifBlank { "Unknown Title" }
                val artist = c.getString(artistCol).orEmpty().ifBlank { "Unknown Artist" }
                val album = c.getString(albumCol).orEmpty()
                val durationMs = c.getLong(durationCol)
                val dataPath = if (dataCol >= 0) c.getString(dataCol) else null
                val albumId = if (albumIdCol >= 0 && !c.isNull(albumIdCol)) c.getLong(albumIdCol) else null
                val mimeType = if (mimeCol >= 0) c.getString(mimeCol) else null
                val dateAdded = c.getLong(dateAddedCol)

                val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString()

                results.add(
                    LocalAudioTrack(
                        id = id,
                        title = title,
                        artist = artist,
                        album = album,
                        durationMs = durationMs,
                        contentUri = contentUri,
                        dataPath = dataPath,
                        albumId = albumId,
                        mimeType = mimeType,
                        dateAddedSec = dateAdded,
                    ),
                )
            }
        }
        return results
    }

    private companion object {
        const val TAG = "LocalAudioRepository"
    }
}
