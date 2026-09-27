package com.lastwave.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.download.TrackDownloadManager
import com.lastwave.app.data.local.db.DownloadedTrackDao
import com.lastwave.app.data.local.db.DownloadedTrackEntity
import com.lastwave.app.data.network.NetworkMonitor
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.PlayableTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private fun <T> Flow<T>.withDownloadsFallback(fallback: T): Flow<T> =
    catch { error ->
        android.util.Log.e("DownloadsViewModel", "Downloads storage unavailable", error)
        emit(fallback)
    }

fun DownloadedTrackEntity.toPlayableTrack(): PlayableTrack {
    val bestUrl = when {
        filePath.startsWith("/") && java.io.File(filePath).exists() -> filePath
        !mediaStoreUri.isNullOrBlank() -> mediaStoreUri
        else -> filePath
    }
    val mime = when {
        filePath.endsWith(".flac", ignoreCase = true) || formatBadge.contains("FLAC") -> "audio/flac"
        filePath.endsWith(".m4a", ignoreCase = true) || filePath.endsWith(".mp4", ignoreCase = true) || formatBadge.contains("M4A") -> "audio/mp4"
        filePath.endsWith(".opus", ignoreCase = true) || formatBadge.contains("OPUS") -> "audio/ogg"
        filePath.endsWith(".mp3", ignoreCase = true) || formatBadge.contains("MP3") -> "audio/mpeg"
        else -> "audio/flac"
    }
    return PlayableTrack(
        title = title,
        artist = artist,
        album = album.takeIf { it.isNotBlank() },
        artworkUrl = artworkUrl,
        playbackUrl = bestUrl,
        playbackMimeType = mime,
        durationMs = durationMs.takeIf { it > 0L },
    )
}

data class DownloadedArtist(
    val name: String,
    val tracks: List<DownloadedTrackEntity>,
    val albumCount: Int,
    val artworkUrl: String?,
    val totalSizeBytes: Long,
)

data class DownloadedAlbum(
    val title: String,
    val artist: String,
    val tracks: List<DownloadedTrackEntity>,
    val artworkUrl: String?,
    val totalSizeBytes: Long,
    val totalDurationMs: Long,
)

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadedTrackDao: DownloadedTrackDao,
    private val downloadManager: TrackDownloadManager,
    private val musicPlayer: MusicPlayer,
    private val settingsPreferences: com.lastwave.app.data.local.SettingsPreferences,
    private val networkMonitor: NetworkMonitor,
) : ViewModel() {

    val isOnline: StateFlow<Boolean> = networkMonitor.isOnline

    val downloadedTracks: StateFlow<List<DownloadedTrackEntity>> =
        downloadedTrackDao.getAll().withDownloadsFallback(emptyList()).stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList(),
        )

    // Counter strings ("15 ml listens", "Track 16", ...) must never surface as
    // artists/albums. They collapse to "Unknown Artist" so one corrupted album
    // can't fan out into N artists / N single-track albums in the UI.
    private fun displayArtist(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.isBlank() ||
            com.lastwave.app.util.ArtistHelper.isPlayCountOrStat(trimmed)
        ) {
            "Unknown Artist"
        } else {
            trimmed
        }
    }

    private fun isUsableAlbum(raw: String): Boolean {
        val trimmed = raw.trim()
        return trimmed.isNotBlank() &&
            !trimmed.equals("Singles", ignoreCase = true) &&
            !com.lastwave.app.util.ArtistHelper.isPlayCountOrStat(trimmed)
    }

    val downloadedArtists: StateFlow<List<DownloadedArtist>> = downloadedTracks.map { tracks ->
        tracks.groupBy { displayArtist(it.artist) }
            .map { (artistName, artistTracks) ->
                val uniqueAlbums = artistTracks.map { it.album.trim() }
                    .filter { isUsableAlbum(it) }
                    .map { it.lowercase() }
                    .distinct()
                val latestArtwork = artistTracks.firstOrNull { !it.artworkUrl.isNullOrBlank() }?.artworkUrl
                val totalSize = artistTracks.sumOf { it.fileSizeBytes }
                DownloadedArtist(
                    name = artistName,
                    tracks = artistTracks,
                    albumCount = uniqueAlbums.size,
                    artworkUrl = latestArtwork,
                    totalSizeBytes = totalSize,
                )
            }
            .sortedBy { it.name.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val downloadedAlbums: StateFlow<List<DownloadedAlbum>> = downloadedTracks.map { tracks ->
        val albumsList = mutableListOf<DownloadedAlbum>()
        val tracksWithAlbum = tracks.filter { isUsableAlbum(it.album) }
        val byTitle = tracksWithAlbum.groupBy { it.album.trim().lowercase() }
        for ((_, sameTitleTracks) in byTitle) {
            val artistClusters = mutableListOf<MutableList<DownloadedTrackEntity>>()
            for (track in sameTitleTracks) {
                // Stat artists all normalize to the same bucket so they never
                // split one album into N single-track albums.
                val normArtist = displayArtist(track.artist).lowercase()
                val cluster = artistClusters.firstOrNull { cluster ->
                    cluster.any { existing ->
                        val existingNorm = displayArtist(existing.artist).lowercase()
                        if (normArtist == "unknown artist" || existingNorm == "unknown artist") {
                            true
                        } else {
                            existingNorm == normArtist ||
                                existingNorm.contains(normArtist) ||
                                normArtist.contains(existingNorm)
                        }
                    }
                }
                if (cluster != null) {
                    cluster.add(track)
                } else {
                    artistClusters.add(mutableListOf(track))
                }
            }
            for (clusterTracks in artistClusters) {
                val albumTitle = clusterTracks.first().album.trim()
                val dominantArtist = clusterTracks.groupBy { displayArtist(it.artist) }
                    .maxByOrNull { it.value.size }?.key ?: displayArtist(clusterTracks.first().artist)
                val latestArtwork = clusterTracks.firstOrNull { !it.artworkUrl.isNullOrBlank() }?.artworkUrl
                val totalSize = clusterTracks.sumOf { it.fileSizeBytes }
                val totalDuration = clusterTracks.sumOf { it.durationMs }
                albumsList.add(
                    DownloadedAlbum(
                        title = albumTitle,
                        artist = dominantArtist,
                        tracks = clusterTracks,
                        artworkUrl = latestArtwork,
                        totalSizeBytes = totalSize,
                        totalDurationMs = totalDuration,
                    ),
                )
            }
        }
        albumsList.sortedBy { it.title.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val totalBytes: StateFlow<Long?> =
        downloadedTrackDao.totalBytes().withDownloadsFallback(0L).stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            0L,
        )

    val activeDownloads: StateFlow<Map<String, com.lastwave.app.data.download.DownloadProgress>> =
        downloadManager.downloads

    val downloadLyrics: StateFlow<Boolean> =
        settingsPreferences.settings
            .map { it.downloadLyrics }
            .withDownloadsFallback(true)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                true,
            )

    val downloadFolder: StateFlow<String> =
        settingsPreferences.settings
            .map { it.downloadFolder }
            .withDownloadsFallback(com.lastwave.app.data.local.DEFAULT_DOWNLOAD_FOLDER)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                com.lastwave.app.data.local.DEFAULT_DOWNLOAD_FOLDER,
            )

    fun setDownloadFolder(name: String) {
        launchDownloadAction("update download folder") {
            settingsPreferences.setDownloadFolder(name)
        }
    }

    /** Persisted SAF tree Uri for the custom download location
     *  (e.g. a folder on the SD card). Blank = default Music/<folder>. */
    val downloadTreeUri: StateFlow<String> =
        settingsPreferences.settings
            .map { it.downloadTreeUri }
            .withDownloadsFallback("")
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                "",
            )

    fun setDownloadTreeUri(uriString: String) {
        launchDownloadAction("update download location") {
            settingsPreferences.setDownloadTreeUri(uriString)
        }
    }

    fun clearDownloadTreeUri() {
        launchDownloadAction("reset download location") {
            com.lastwave.app.data.download.SafTreeFiles.releasePersistedAccess(
                context,
                downloadTreeUri.value,
            )
            settingsPreferences.setDownloadTreeUri("")
        }
    }

    /** Synchronous persisted-grant check for the custom location. */
    fun isCustomLocationUsableNow(): Boolean =
        com.lastwave.app.data.download.SafTreeFiles.hasPersistedAccess(
            context,
            downloadTreeUri.value,
        )

    /** Human-readable label ("SD card › Music/LastWave"), or null when the
     *  default location is active or the grant was lost. */
    fun describeCustomLocation(): String? =
        com.lastwave.app.data.download.SafTreeFiles.describeLocation(
            context,
            downloadTreeUri.value,
        )

    val downloadStructure: StateFlow<com.lastwave.app.data.local.DownloadFolderStructure> =
        settingsPreferences.settings
            .map { it.downloadStructure }
            .withDownloadsFallback(com.lastwave.app.data.local.DownloadFolderStructure.FLAT)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                com.lastwave.app.data.local.DownloadFolderStructure.FLAT,
            )

    val useAlbumArtistForFolders: StateFlow<Boolean> =
        settingsPreferences.settings
            .map { it.useAlbumArtistForFolders }
            .withDownloadsFallback(true)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val primaryArtistOnly: StateFlow<Boolean> =
        settingsPreferences.settings
            .map { it.primaryArtistOnly }
            .withDownloadsFallback(true)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setDownloadStructure(structure: com.lastwave.app.data.local.DownloadFolderStructure) {
        launchDownloadAction("update folder structure") {
            settingsPreferences.setDownloadStructure(structure)
        }
    }

    fun setUseAlbumArtistForFolders(enabled: Boolean) {
        launchDownloadAction("update album-artist folders") {
            settingsPreferences.setUseAlbumArtistForFolders(enabled)
        }
    }

    fun setPrimaryArtistOnly(enabled: Boolean) {
        launchDownloadAction("update primary-artist filter") {
            settingsPreferences.setPrimaryArtistOnly(enabled)
        }
    }

    init {
        launchDownloadAction("sync downloads from storage") {
            downloadManager.syncDownloadsFromStorage()
        }
    }

    fun setDownloadLyrics(enabled: Boolean) {
        launchDownloadAction("toggle download lyrics") {
            settingsPreferences.setDownloadLyrics(enabled)
        }
    }

    private fun launchDownloadAction(action: String, block: suspend () -> Unit) =
        viewModelScope.launch {
            try {
                block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                android.util.Log.e("DownloadsViewModel", "Failed to $action", error)
            } catch (error: LinkageError) {
                android.util.Log.e("DownloadsViewModel", "Unsupported platform action: $action", error)
            }
        }

    fun cancelDownload(key: String) {
        try {
            downloadManager.cancelDownload(key)
        } catch (error: Exception) {
            android.util.Log.e("DownloadsViewModel", "Failed to cancel download", error)
        } catch (error: LinkageError) {
            android.util.Log.e("DownloadsViewModel", "Download cancellation unsupported", error)
        }
    }

    fun cancelAllDownloads() {
        try {
            downloadManager.cancelAllDownloads()
        } catch (error: Exception) {
            android.util.Log.e("DownloadsViewModel", "Failed to cancel all downloads", error)
        } catch (error: LinkageError) {
            android.util.Log.e("DownloadsViewModel", "Download cancellation unsupported", error)
        }
    }

    fun deleteTrack(track: DownloadedTrackEntity) {
        launchDownloadAction("delete download") {
            downloadManager.deleteDownloadedTrack(track)
        }
    }

    fun deleteHistoryRecordOnly(track: DownloadedTrackEntity) {
        launchDownloadAction("delete download history") {
            downloadedTrackDao.delete(track)
        }
    }

    fun clearAll() {
        launchDownloadAction("clear downloads") {
            downloadManager.clearAllDownloads()
        }
    }

    fun clearHistoryOnly() {
        launchDownloadAction("clear download history") {
            downloadedTrackDao.clearAll()
        }
    }

    fun playTrack(track: DownloadedTrackEntity, queue: List<DownloadedTrackEntity>? = null) {
        val currentTracks = queue ?: downloadedTracks.value
        val playables = currentTracks.map { it.toPlayableTrack() }
        val startIndex = currentTracks.indexOfFirst { it.id == track.id }
            .takeIf { it >= 0 }
            ?: currentTracks.indexOfFirst {
                it.title.equals(track.title, ignoreCase = true) &&
                it.artist.equals(track.artist, ignoreCase = true)
            }.coerceAtLeast(0)

        try {
            if (playables.isNotEmpty()) {
                musicPlayer.playQueue(
                    tracks = playables,
                    startIndex = startIndex,
                    sourceLabel = "Downloads",
                )
            } else {
                musicPlayer.play(track.toPlayableTrack(), sourceLabel = "Downloads")
            }
        } catch (error: Exception) {
            android.util.Log.e("DownloadsViewModel", "Could not play download", error)
        } catch (error: LinkageError) {
            android.util.Log.e("DownloadsViewModel", "Playback unsupported on this device", error)
        }
    }

    fun playTracks(tracks: List<DownloadedTrackEntity>, startIndex: Int = 0, startShuffled: Boolean = false) {
        if (tracks.isEmpty()) return
        val playables = tracks.map { it.toPlayableTrack() }
        val actualStartIndex = if (startShuffled) playables.indices.random() else startIndex.coerceIn(0, playables.size - 1)
        try {
            musicPlayer.playQueue(
                tracks = playables,
                startIndex = actualStartIndex,
                sourceLabel = "Downloads",
                startShuffled = startShuffled,
            )
        } catch (error: Exception) {
            android.util.Log.e("DownloadsViewModel", "Could not play tracks queue", error)
        } catch (error: LinkageError) {
            android.util.Log.e("DownloadsViewModel", "Playback unsupported on this device", error)
        }
    }

    fun playNext(track: DownloadedTrackEntity) {
        try {
            musicPlayer.playNext(track.toPlayableTrack())
        } catch (error: Exception) {
            android.util.Log.e("DownloadsViewModel", "Could not add to play next", error)
        } catch (error: LinkageError) {
            android.util.Log.e("DownloadsViewModel", "Playback unsupported on this device", error)
        }
    }

    fun addToQueue(track: DownloadedTrackEntity) {
        try {
            musicPlayer.addToQueue(track.toPlayableTrack())
        } catch (error: Exception) {
            android.util.Log.e("DownloadsViewModel", "Could not add to queue", error)
        } catch (error: LinkageError) {
            android.util.Log.e("DownloadsViewModel", "Playback unsupported on this device", error)
        }
    }

    fun playAll(startShuffled: Boolean = false) {
        val currentTracks = downloadedTracks.value
        if (currentTracks.isEmpty()) return
        playTracks(currentTracks, startIndex = 0, startShuffled = startShuffled)
    }

    fun openInFileManager() {
        // Custom SAF location: hand its tree Uri to a file manager instead
        // of the default Music/<folder> path.
        val tree = downloadTreeUri.value
        if (tree.isNotBlank() &&
            com.lastwave.app.data.download.SafTreeFiles.hasPersistedAccess(context, tree)
        ) {
            try {
                val treeUri = Uri.parse(tree)
                val label = describeCustomLocation() ?: tree
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(treeUri, android.provider.DocumentsContract.Document.MIME_TYPE_DIR)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(
                    Intent.createChooser(intent, context.getString(com.lastwave.app.R.string.dl_open_location, label))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (e: Exception) {
                // No handler installed — stay put rather than opening the
                // wrong (default) folder.
            } catch (error: LinkageError) {
                // Some custom ROMs omit the expected storage activity.
            }
            return
        }
        try {
            val folder = com.lastwave.app.data.local.sanitizeDownloadFolderName(downloadFolder.value)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).path + "/$folder"), "resource/folder")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(Intent.createChooser(intent, context.getString(com.lastwave.app.R.string.dl_open_folder, folder)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            // Fallback
        } catch (error: LinkageError) {
            // Some custom ROMs omit the expected storage activity.
        }
    }
}
