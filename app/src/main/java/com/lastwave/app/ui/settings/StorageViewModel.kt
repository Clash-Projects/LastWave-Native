package com.lastwave.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.Coil
import coil.ImageLoaderFactory
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import com.lastwave.app.data.download.TrackDownloadManager
import com.lastwave.app.data.local.CacheStoragePolicy
import com.lastwave.app.data.local.SettingsPreferences
import com.lastwave.app.data.local.db.DownloadedTrackDao
import com.lastwave.app.playback.MusicPlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class StorageScreenState(
    val artworkCacheBytes: Long = 0L,
    val streamedAudioCacheBytes: Long = 0L,
    val downloadBytes: Long = 0L,
    val downloadCount: Int = 0,
    val cacheLimitMb: Int = CacheStoragePolicy.DEFAULT_LIMIT_MB,
    val lastCacheClearAtEpochMs: Long = 0L,
    val lastCacheClearFreedBytes: Long = 0L,
) {
    val cacheBytes: Long get() = artworkCacheBytes + streamedAudioCacheBytes
}

@HiltViewModel
@OptIn(ExperimentalCoilApi::class)
class StorageViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicPlayer: MusicPlayer,
    private val settingsPreferences: SettingsPreferences,
    private val downloadedTrackDao: DownloadedTrackDao,
    private val downloadManager: TrackDownloadManager,
) : ViewModel() {

    private data class CacheUsage(val artworkBytes: Long = 0L, val audioBytes: Long = 0L)

    private val cacheUsage = MutableStateFlow(CacheUsage())
    private val _events = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val events = _events.asSharedFlow()

    val state: StateFlow<StorageScreenState> = combine(
        cacheUsage,
        downloadedTrackDao.totalBytes(),
        downloadedTrackDao.count(),
        settingsPreferences.storageCacheLimitMb,
        settingsPreferences.storageCacheClearHistory,
    ) { usage, downloadBytes, downloadCount, cacheLimitMb, clearHistory ->
        StorageScreenState(
            artworkCacheBytes = usage.artworkBytes,
            streamedAudioCacheBytes = usage.audioBytes,
            downloadBytes = downloadBytes ?: 0L,
            downloadCount = downloadCount,
            cacheLimitMb = cacheLimitMb,
            lastCacheClearAtEpochMs = clearHistory.clearedAtEpochMs,
            lastCacheClearFreedBytes = clearHistory.freedBytes,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StorageScreenState())

    fun refreshCacheUsage() {
        viewModelScope.launch(Dispatchers.IO) {
            val artworkBytes = runCatching { context.imageLoader.diskCache?.size ?: 0L }.getOrDefault(0L)
            CacheStoragePolicy.setArtworkCacheBytes(artworkBytes)
            cacheUsage.value = CacheUsage(
                artworkBytes = artworkBytes,
                audioBytes = musicPlayer.playbackCacheSpaceBytes(),
            )
        }
    }

    fun setCacheLimit(limitMb: Int) {
        viewModelScope.launch {
            val safeLimit = limitMb.coerceIn(CacheStoragePolicy.MIN_LIMIT_MB, CacheStoragePolicy.MAX_LIMIT_MB)
            settingsPreferences.setStorageCacheLimitMb(safeLimit)
            withContext(Dispatchers.IO) {
                val application = context.applicationContext as? ImageLoaderFactory
                if (application != null) {
                    val oldImageLoader = context.imageLoader
                    oldImageLoader.shutdown()
                    Coil.reset()
                    Coil.setImageLoader(application.newImageLoader())
                }
            }
            refreshCacheUsage()
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val imageCache = context.imageLoader.diskCache
                    val beforeBytes = (runCatching { imageCache?.size ?: 0L }.getOrDefault(0L) +
                        runCatching { musicPlayer.playbackCacheSpaceBytes() }.getOrDefault(0L))
                    musicPlayer.clearPlaybackCache()
                    imageCache?.clear()
                    val artworkBytes = runCatching { imageCache?.size ?: 0L }.getOrDefault(0L)
                    CacheStoragePolicy.setArtworkCacheBytes(artworkBytes)
                    val afterBytes = artworkBytes + runCatching { musicPlayer.playbackCacheSpaceBytes() }.getOrDefault(0L)
                    settingsPreferences.recordStorageCacheClear(
                        clearedAtEpochMs = System.currentTimeMillis(),
                        freedBytes = (beforeBytes - afterBytes).coerceAtLeast(0L),
                    )
                }
            }.onSuccess {
                refreshCacheUsage()
                _events.emit(com.lastwave.app.R.string.storage_cache_cleared)
            }.onFailure {
                _events.emit(com.lastwave.app.R.string.storage_operation_failed)
            }
        }
    }

    fun deleteDownloadedMusic() {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { downloadManager.clearAllDownloads() }
            }.onSuccess {
                _events.emit(com.lastwave.app.R.string.storage_downloads_deleted)
            }.onFailure {
                _events.emit(com.lastwave.app.R.string.storage_operation_failed)
            }
        }
    }
}
