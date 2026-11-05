package com.lastwave.app.widget.wave

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.lastwave.app.data.repository.SongPlayStatsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WaveWidgetHistoryEntryPoint {
    fun songPlayStatsRepository(): SongPlayStatsRepository
}

/** Durable publisher shared by every Wave widget shape. */
object WaveWidgetUpdater {
    private const val TAG = "WaveWidgetUpdater"
    private const val ART_FILE_PREFIX = "wave_widget_art_"
    private const val ART_FILE_SUFFIX = ".jpg"
    private const val MAX_RAW_ART_SIDE = 384
    private const val RAW_ART_QUALITY = 90
    private const val PROGRESS_TICK_MS = 12_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val updateMutex = Mutex()
    private val tickerLock = Any()

    @Volatile
    private var tickerJob: Job? = null

    @Volatile
    private var playbackServiceActive = false

    /** Persist [state], optionally cache its raw art, and update placed Wave widgets. */
    suspend fun updateState(
        context: Context,
        state: WaveWidgetState,
        bitmap: Bitmap? = null,
    ) {
        val appContext = context.applicationContext
        withContext(Dispatchers.Default) {
            try {
                updateMutex.withLock {
                    val targets = existingTargets(appContext)
                    val previous = WaveWidgetState.read(appContext)
                    var prepared = prepareState(
                        context = appContext,
                        previous = previous,
                        incoming = state,
                        bitmap = bitmap,
                        cacheBitmap = targets.isNotEmpty(),
                    )
                    if (targets.any { it.kind == WaveWidgetKind.LIBRARY }) {
                        prepared = hydrateRecentHistory(appContext, prepared)
                    }
                    val persisted = withContext(Dispatchers.IO) {
                        WaveWidgetState.write(appContext, prepared)
                    }
                    if (!persisted) Log.w(TAG, "Wave widget state persistence failed")
                    if (persisted) cleanupRawArt(appContext, prepared.artworkPath)

                    if (targets.isNotEmpty()) {
                        val art = resolveArt(appContext, prepared, bitmap, targets)
                        updateTargets(appContext, targets, prepared, art)
                    }
                    syncTicker(appContext, prepared, targets.isNotEmpty())
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Wave widget state update failed", error)
            }
        }
    }

    /** Re-render every currently placed Wave widget from the one persisted snapshot. */
    suspend fun refresh(context: Context) {
        refreshInternal(context, null)
    }

    /** Re-render only requested ids that still belong to a placed Wave provider. */
    suspend fun refresh(context: Context, appWidgetIds: IntArray) {
        refreshInternal(context, appWidgetIds.copyOf())
    }

    private suspend fun refreshInternal(context: Context, requestedIds: IntArray?) {
        val appContext = context.applicationContext
        withContext(Dispatchers.Default) {
            try {
                updateMutex.withLock {
                    var state = WaveWidgetState.read(appContext)
                    // A process restart or reboot destroys the only playback
                    // session. Keep the durable queue metadata, but do not
                    // display a stale pause glyph or keep a progress ticker
                    // running until the service publishes a live state again.
                    if (state.isPlaying && !playbackServiceActive) {
                        state = state.copy(isPlaying = false, updatedAtEpochMs = System.currentTimeMillis())
                        withContext(Dispatchers.IO) { WaveWidgetState.write(appContext, state) }
                    }
                    val allTargets = existingTargets(appContext)
                    val targets = requestedIds?.toHashSet()?.let { requested ->
                        allTargets.filter { it.appWidgetId in requested }
                    } ?: allTargets
                    if (targets.any { it.kind == WaveWidgetKind.LIBRARY }) {
                        val hydrated = hydrateRecentHistory(appContext, state)
                        if (hydrated.recentTracks != state.recentTracks) {
                            state = hydrated
                            withContext(Dispatchers.IO) { WaveWidgetState.write(appContext, state) }
                        }
                    }
                    if (targets.isNotEmpty()) {
                        val art = resolveArt(appContext, state, null, targets)
                        updateTargets(appContext, targets, state, art)
                    }
                    syncTicker(appContext, state, allTargets.isNotEmpty())
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Wave widget refresh failed", error)
            }
        }
    }

    /** Safe entry point for receivers or other non-suspending callers. */
    fun requestRefresh(context: Context) {
        requestRefreshInternal(context, null)
    }

    fun requestRefresh(context: Context, appWidgetIds: IntArray) {
        requestRefreshInternal(context, appWidgetIds.copyOf())
    }

    private fun requestRefreshInternal(context: Context, appWidgetIds: IntArray?) {
        val appContext = context.applicationContext
        scope.launch {
            if (appWidgetIds == null) refresh(appContext) else refresh(appContext, appWidgetIds)
        }
    }

    fun widgetsExist(context: Context): Boolean = existingTargets(context.applicationContext).isNotEmpty()

    fun setPlaybackServiceActive(active: Boolean) {
        playbackServiceActive = active
        if (!active) stopTicker()
    }

    fun stopTicker() {
        val job = synchronized(tickerLock) {
            tickerJob.also { tickerJob = null }
        }
        job?.cancel()
    }

    suspend fun publish(
        context: Context,
        playerState: com.lastwave.app.playback.MusicPlayerState,
        isLiked: Boolean,
        artworkUri: String? = playerState.current?.artworkUrl,
        bitmap: Bitmap? = null,
        playlists: List<WavePlaylistShortcut> = WaveWidgetState.defaultShortcuts(),
    ) {
        updateState(
            context = context,
            state = WaveWidgetState.fromPlayer(
                playerState = playerState,
                isLiked = isLiked,
                artworkUri = artworkUri,
                playlists = playlists,
            ),
            bitmap = bitmap,
        )
    }

    private suspend fun prepareState(
        context: Context,
        previous: WaveWidgetState,
        incoming: WaveWidgetState,
        bitmap: Bitmap?,
        cacheBitmap: Boolean,
    ): WaveWidgetState {
        val withHistory = incoming.withRecentHistory(previous)
        if (!incoming.hasTrack) {
            return WaveWidgetState(
                playlists = incoming.playlists,
                recentTracks = withHistory.recentTracks,
                updatedAtEpochMs = incoming.updatedAtEpochMs.takeIf { it > 0L }
                    ?: System.currentTimeMillis(),
            )
        }

        val normalized = if (withHistory.updatedAtEpochMs > 0L) withHistory else {
            withHistory.copy(updatedAtEpochMs = System.currentTimeMillis())
        }
        val validBitmap = bitmap?.takeIf { !it.isRecycled }
        val sameArtwork = previous.trackId == normalized.trackId &&
            previous.artworkUri == normalized.artworkUri
        val incomingPath = normalized.artworkPath
            ?.takeIf(String::isNotBlank)
            ?.takeIf { File(it).isFile }
        val retainedPath = when {
            incomingPath != null && (sameArtwork || incomingPath != previous.artworkPath) -> incomingPath
            sameArtwork -> previous.artworkPath?.takeIf { File(it).isFile }
            else -> null
        }
        // Discrete play/mode/queue events often carry the exact same in-memory
        // bitmap. Keep the existing raw file for the same track/art identity;
        // write only when art changed or process-death recovery has no file.
        val shouldCacheBitmap = cacheBitmap && validBitmap != null &&
            (!sameArtwork || retainedPath == null)
        val cachedPath = if (shouldCacheBitmap) {
            cacheRawArt(context, requireNotNull(validBitmap))
        } else {
            null
        }
        return normalized.copy(artworkPath = cachedPath ?: retainedPath)
    }

    private suspend fun resolveArt(
        context: Context,
        state: WaveWidgetState,
        bitmap: Bitmap?,
        targets: List<Target>,
    ): WaveWidgetArt.Result {
        // WaveWidgetArt also guards its own IO, while this call site ensures a
        // service/main-thread publisher never performs decode/palette work inline.
        return withContext(Dispatchers.IO) {
            val art = if (state.hasTrack) WaveWidgetArt.resolve(
                context = context,
                trackId = state.trackId,
                artworkUri = state.artworkUri,
                artworkPath = state.artworkPath,
                bitmap = bitmap?.takeIf { !it.isRecycled },
            ) else WaveWidgetArt.Result(null, null, null)
            if (targets.any { it.kind == WaveWidgetKind.LIBRARY }) {
                art.copy(
                    recentArt = WaveWidgetArt.resolveRecent(context, state.recentTracks),
                    nextArt = WaveWidgetArt.resolveRecent(context, state.nextTracks),
                )
            } else {
                art
            }
        }
    }

    /** Populate a newly placed widget from real listening history, including older app sessions. */
    private suspend fun hydrateRecentHistory(context: Context, state: WaveWidgetState): WaveWidgetState =
        withContext(Dispatchers.IO) {
            try {
                val repository = EntryPointAccessors.fromApplication(
                    context.applicationContext, WaveWidgetHistoryEntryPoint::class.java,
                ).songPlayStatsRepository()
                val recorded = repository.recentlyPlayed(12).map { track ->
                    val videoId = track.videoId?.takeIf(String::isNotBlank)
                    WaveQueueTrack(
                        trackId = videoId?.let { "video:$it" }
                            ?: "song:${track.title.trim().lowercase()}|${track.artist.trim().lowercase()}",
                        queueIndex = -1,
                        title = track.title,
                        artist = track.artist,
                        artworkUri = track.artworkUrl,
                        videoId = videoId,
                    )
                }
                state.withRecordedHistory(recorded, includeCurrent = playbackServiceActive)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Wave recorded history unavailable", error)
                state
            }
        }

    private fun updateTargets(
        context: Context,
        targets: List<Target>,
        state: WaveWidgetState,
        art: WaveWidgetArt.Result,
    ) {
        val manager = AppWidgetManager.getInstance(context)
        targets.forEach { target ->
            runCatching {
                val views = WaveWidgetViews.build(
                    context = context,
                    appWidgetId = target.appWidgetId,
                    kind = target.kind,
                    state = state,
                    art = art,
                )
                manager.updateAppWidget(target.appWidgetId, views)
            }.onFailure { error ->
                Log.w(TAG, "Wave widget ${target.appWidgetId} update failed", error)
            }
        }
    }

    private fun existingTargets(context: Context): List<Target> = runCatching {
        val manager = AppWidgetManager.getInstance(context)
        WaveWidgetProviders.components(context)
            .flatMap { (kind, component) ->
                manager.getAppWidgetIds(component).map { appWidgetId -> Target(appWidgetId, kind) }
            }
            .distinctBy(Target::appWidgetId)
    }.onFailure { error ->
        Log.w(TAG, "Wave widget discovery failed", error)
    }.getOrDefault(emptyList())

    private fun syncTicker(context: Context, state: WaveWidgetState, hasWidgets: Boolean) {
        if (!hasWidgets || !state.hasTrack || !state.isPlaying) {
            stopTicker()
            return
        }
        synchronized(tickerLock) {
            if (tickerJob?.isActive == true) return
            val appContext = context.applicationContext
            val job = scope.launch {
                while (isActive) {
                    delay(PROGRESS_TICK_MS)
                    refresh(appContext)
                }
            }
            tickerJob = job
            job.invokeOnCompletion {
                synchronized(tickerLock) {
                    if (tickerJob === job) tickerJob = null
                }
            }
        }
    }

    private suspend fun cacheRawArt(context: Context, bitmap: Bitmap): String? =
        withContext(Dispatchers.IO) {
            val token = System.nanoTime().toString(36)
            val target = File(context.filesDir, "$ART_FILE_PREFIX$token$ART_FILE_SUFFIX")
            val pending = File(context.filesDir, "${target.name}.pending")
            var softwareCopy: Bitmap? = null
            var scaledCopy: Bitmap? = null
            try {
                val software = if (bitmap.config == Bitmap.Config.HARDWARE) {
                    bitmap.copy(Bitmap.Config.ARGB_8888, false)?.also { softwareCopy = it }
                        ?: return@withContext null
                } else {
                    bitmap
                }
                val largestSide = maxOf(software.width, software.height).coerceAtLeast(1)
                val cached = if (largestSide > MAX_RAW_ART_SIDE) {
                    val scale = MAX_RAW_ART_SIDE.toFloat() / largestSide.toFloat()
                    Bitmap.createScaledBitmap(
                        software,
                        (software.width * scale).toInt().coerceAtLeast(1),
                        (software.height * scale).toInt().coerceAtLeast(1),
                        true,
                    ).also { scaledCopy = it }
                } else {
                    software
                }
                FileOutputStream(pending).use { output ->
                    if (!cached.compress(Bitmap.CompressFormat.JPEG, RAW_ART_QUALITY, output)) {
                        throw IOException("Bitmap compression returned false")
                    }
                }
                if (!pending.renameTo(target)) {
                    pending.copyTo(target, overwrite = true)
                    pending.delete()
                }
                target.absolutePath
            } catch (error: Exception) {
                Log.w(TAG, "Wave raw artwork cache failed", error)
                pending.delete()
                target.delete()
                null
            } finally {
                scaledCopy?.takeIf { it !== bitmap && !it.isRecycled }?.recycle()
                softwareCopy?.takeIf { it !== scaledCopy && !it.isRecycled }?.recycle()
            }
        }

    private fun cleanupRawArt(context: Context, keepPath: String?) {
        val keep = keepPath?.let(::File)?.absolutePath
        context.filesDir.listFiles()?.forEach { file ->
            if (file.name.startsWith(ART_FILE_PREFIX) && file.absolutePath != keep) {
                runCatching { file.delete() }
            }
        }
    }

    private data class Target(
        val appWidgetId: Int,
        val kind: WaveWidgetKind,
    )
}
