package com.lastwave.app.data.queue

import android.content.Context
import com.lastwave.app.playback.PlayableTrack
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class PreviewQueue(
    val id: String,
    val title: String,
    val timestamp: Long,
    val tracks: List<PlayableTrack>,
    val artworkUrl: String? = null,
)

@Singleton
class PreviewQueueRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val applicationScope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val file by lazy { File(context.filesDir, "preview_queues.json") }

    private val _previewQueues = MutableStateFlow<List<PreviewQueue>>(emptyList())
    val previewQueues: StateFlow<List<PreviewQueue>> = _previewQueues.asStateFlow()

    init {
        applicationScope.launch(Dispatchers.IO) {
            loadFromDisk()
        }
    }

    private suspend fun loadFromDisk() = mutex.withLock {
        runCatching {
            if (file.exists()) {
                val text = file.readText()
                val list = json.decodeFromString<List<PreviewQueue>>(text)
                _previewQueues.value = list
            }
        }.onFailure {
            android.util.Log.w("PreviewQueueRepo", "Failed to load preview queues", it)
        }
    }

    fun recordQueue(tracks: List<PlayableTrack>, sourceLabel: String = "Queue") {
        if (tracks.size < 2) return
        val snapshotTracks = tracks.take(30)
        val firstTrack = snapshotTracks.firstOrNull() ?: return
        val title = if (sourceLabel.isNotBlank() && sourceLabel != "LastWave" && sourceLabel != "Home") {
            sourceLabel
        } else {
            "${firstTrack.title} & more"
        }
        val artwork = snapshotTracks.firstNotNullOfOrNull { it.artworkUrl?.takeIf(String::isNotBlank) }
        val newEntry = PreviewQueue(
            id = System.currentTimeMillis().toString(),
            title = title,
            timestamp = System.currentTimeMillis(),
            tracks = snapshotTracks,
            artworkUrl = artwork,
        )

        applicationScope.launch(Dispatchers.IO) {
            mutex.withLock {
                val current = _previewQueues.value.toMutableList()
                val topFirstVideoId = current.firstOrNull()?.tracks?.firstOrNull()?.let { it.videoId ?: (it.title + it.artist) }
                val newFirstVideoId = firstTrack.videoId ?: (firstTrack.title + firstTrack.artist)
                if (topFirstVideoId == newFirstVideoId) {
                    current.removeAt(0)
                }
                current.add(0, newEntry)
                val trimmed = current.take(8)
                _previewQueues.value = trimmed
                runCatching {
                    file.writeText(json.encodeToString(trimmed))
                }
            }
        }
    }
}
