package com.lastwave.app.data.download.smart

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import com.lastwave.app.data.download.TrackDownloadManager
import com.lastwave.app.data.download.TrackDownloadManager.DeleteDownloadResult
import com.lastwave.app.data.download.deleteDownloadedTrackImpl
import com.lastwave.app.data.download.isHiddenFromHistory
import com.lastwave.app.data.local.db.DownloadedTrackDao
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.InnerTubeMusicApi.Companion.VIDEO_ID_PATTERN
import com.lastwave.app.data.network.NetworkMonitor
import com.lastwave.app.data.ytmusic.YtConnection
import com.lastwave.app.data.ytmusic.YtMusicAuthManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmartDownloadsSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: SmartDownloadsPreferences,
    private val auth: YtMusicAuthManager,
    private val innerTube: dagger.Lazy<InnerTubeMusicApi>,
    private val downloadManager: dagger.Lazy<TrackDownloadManager>,
    private val downloadedTracks: dagger.Lazy<DownloadedTrackDao>,
    private val networkMonitor: dagger.Lazy<NetworkMonitor>,
) {
    private val syncLock = Mutex()
    private val workManager by lazy { WorkManager.getInstance(context) }

    suspend fun observeSettings() {
        auth.awaitLoadedConnection()
        var initial = true
        combine(preferences.settings, auth.connection) { settings, account ->
            settings.takeIf { it.enabled && account.isConnected }?.let { it to account.downloadAccount() }
        }.distinctUntilChanged().collect { config ->
            if (config != null) {
                // An empty plan was parked until the next check-in; retry it now.
                val keep = initial && preferences.readPlan()?.playlists?.isNotEmpty() == true
                schedule(0, if (keep) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE)
            } else if (!initial) {
                workManager.cancelUniqueWork(WORK_NAME).await()
            }
            initial = false
        }
    }

    internal suspend fun sync(): Boolean = withContext(Dispatchers.IO) {
        syncLock.withLock {
            val settings = preferences.settings.first()
            val account = auth.awaitLoadedConnection()
            if (!settings.enabled || !account.isConnected) return@withLock true
            val accountId = account.downloadAccount()
            val now = System.currentTimeMillis()
            val battery = batteryLevel()
            if (battery < 0.2f || !allowsNetwork(settings)) return@withLock false
            val dao = downloadedTracks.get()
            val manager = downloadManager.get()
            if (manager.downloadsWiping) return@withLock false
            val oldPlan = preferences.readPlan()?.takeIf { it.account == accountId }
            val (tracks, missing) = dao.getSmartDownloads().partition { File(it.filePath).isFile }
            for (track in missing) dao.delete(track)
            val recordedPaths = tracks.mapTo(mutableSetOf()) { it.filePath }
            // A killed process can leave an audio file before Room commits its row.
            manager.smartDownloadDirectory.listFiles().orEmpty().forEach { file ->
                if (file.path !in recordedPaths && !manager.isHiddenFromHistory("", file.path)) file.delete()
            }
            val savedIds = tracks.mapNotNull { it.smartDownloadVideoId }.toSet()
            val api = innerTube.get()
            val storageLimit = settings.storageLimitBytes
            var plan = if (oldPlan == null || oldPlan.playlists.isEmpty() || oldPlan.songLimit != settings.songLimit ||
                oldPlan.storageLimitGiB != settings.storageLimitGiB || now >= oldPlan.checkInAt
            ) {
                val mixes = onAllowedNetwork(settings) {
                    val ids = api.fetchPersonalMixIds()
                    // Round-robin only takes about songLimit / mixes from each, so paging
                    // every mix (they page forever) to the full limit wasted requests.
                    // shortcut: twice the fair share; raise it if short mixes leave the limit unfilled.
                    val perMix = (settings.songLimit * 2 / ids.size.coerceAtLeast(1)).coerceIn(MIN_TRACKS_PER_MIX, settings.songLimit)
                    // A mix is a sample, so a partial first page still counts.
                    ids.associateWith { id ->
                        api.fetchPlaylist(id, maxTracks = perMix)?.tracks
                            ?.filter { VIDEO_ID_PATTERN.matches(it.videoId) }.orEmpty()
                    }.filterValues { it.isNotEmpty() }
                } ?: return@withLock false
                val playlists = mixes.mapValues { (_, songs) -> songs.map { it.videoId } }
                    .ifEmpty { oldPlan?.playlists.orEmpty() }
                SmartDownloadsPlan(
                    account = accountId,
                    songLimit = settings.songLimit,
                    checkInAt = now + CHECK_IN_MS,
                    runUntil = now + RUN_WINDOW_MS,
                    playlists = playlists,
                    pending = mixes.interleaved().distinctBy { it.videoId }.take(settings.songLimit)
                        .filterNot { it.videoId in savedIds },
                    storageLimitGiB = settings.storageLimitGiB,
                    obsoleteVideoIds = obsoleteRecommendations(oldPlan, playlists, savedIds),
                ).also { preferences.savePlan(it, settings) }
            } else {
                oldPlan
            }

            if (!stillEnabled(settings, accountId)) return@withLock true
            val obsolete = plan.obsoleteVideoIds.intersect(savedIds).toMutableSet()
            for (track in tracks.filter { it.smartDownloadVideoId in obsolete }) {
                if (manager.downloadsWiping || !stillEnabled(settings, accountId)) return@withLock false
                if (manager.deleteDownloadedTrackImpl(track) == DeleteDownloadResult.Deleted) {
                    obsolete.remove(track.smartDownloadVideoId)
                }
            }

            val pending = ArrayDeque(plan.pending)
            val deadline = (plan.runUntil - System.currentTimeMillis()).coerceIn(0, RUN_BUDGET_MS)
            withTimeoutOrNull(deadline) {
                while (pending.isNotEmpty()) {
                    if (!allowsNetwork(settings) || batteryLevel() < 0.2f || manager.downloadsWiping) break
                    if (!stillEnabled(settings, accountId)) return@withTimeoutOrNull
                    val current = dao.getSmartDownloads()
                    val song = pending.first()
                    if (current.any { it.smartDownloadVideoId == song.videoId && File(it.filePath).isFile }) {
                        pending.removeFirst()
                        continue
                    }
                    // Orphan files were deleted above and downloads run one at a time,
                    // so the rows account for every byte without listing the directory.
                    val remainingBytes = minOf(
                        storageLimit - current.sumOf { it.fileSizeBytes },
                        context.noBackupFilesDir.usableSpace - STORAGE_RESERVE,
                    )
                    if (current.size >= settings.songLimit || remainingBytes <= 0) {
                        pending.clear()
                        break
                    }
                    onAllowedNetwork(settings) {
                        if (stillEnabled(settings, accountId)) manager.downloadAutomatically(song, remainingBytes)
                    } ?: break
                    pending.removeFirst()
                }
            }
            if (!stillEnabled(settings, accountId)) return@withLock true
            val mayContinue = pending.isNotEmpty() && System.currentTimeMillis() < plan.runUntil
            plan = plan.copy(
                pending = if (mayContinue) pending.toList() else emptyList(),
                obsoleteVideoIds = obsolete,
            )
            preferences.savePlan(plan, settings)
            val delay = (plan.checkInAt - System.currentTimeMillis()).coerceAtLeast(0)
            schedule(if (mayContinue) delay.coerceAtMost(CONTINUATION_DELAY_MS) else delay, ExistingWorkPolicy.APPEND_OR_REPLACE)
            true
        }
    }

    private suspend fun stillEnabled(settings: SmartDownloadsSettings, accountId: String): Boolean =
        preferences.settings.first() == settings &&
            auth.connection.value.let { it.isConnected && it.downloadAccount() == accountId }

    private fun batteryLevel(): Float {
        val battery = context.getSystemService(BatteryManager::class.java)
        if (battery == null || battery.isCharging) return 1f
        val percent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (percent in 0..100) percent / 100f else 1f
    }

    private fun allowsNetwork(settings: SmartDownloadsSettings): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
            (!settings.wifiOnly || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
    }

    private suspend fun <T : Any> onAllowedNetwork(
        settings: SmartDownloadsSettings,
        block: suspend () -> T,
    ): T? = coroutineScope {
        if (!allowsNetwork(settings)) return@coroutineScope null
        val request = async(start = CoroutineStart.LAZY) { block() }
        val routeWatch = launch(start = CoroutineStart.UNDISPATCHED) {
            networkMonitor.get().activeRoute.first { route ->
                (!route.isValidated || (settings.wifiOnly && !route.isWifi)) && !allowsNetwork(settings)
            }
            request.cancel(NetworkChanged())
        }
        try {
            request.await()
        } catch (_: NetworkChanged) {
            currentCoroutineContext().ensureActive()
            null
        } finally {
            routeWatch.cancel()
        }
    }

    private class NetworkChanged : CancellationException("Smart downloads lost their network")

    private suspend fun schedule(delay: Long, policy: ExistingWorkPolicy) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresStorageNotLow(true)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = OneTimeWorkRequestBuilder<SmartDownloadsWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 5, TimeUnit.MINUTES)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniqueWork(WORK_NAME, policy, request).await()
    }

    private fun YtConnection.downloadAccount(): String {
        val channel = listOf(cookies["SID"] ?: auth.sapisid(this), pageId, onBehalfOfUser, authUserIndex ?: 0)
        return MessageDigest.getInstance("SHA-256").digest(channel.joinToString("|").toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val WORK_NAME = "lastwave-smart-downloads"
        const val STORAGE_RESERVE = 512L * 1024 * 1024
        const val CHECK_IN_MS = 26L * 60 * 60 * 1000
        const val RUN_WINDOW_MS = 4L * 60 * 60 * 1000
        const val MIN_TRACKS_PER_MIX = 25
        const val RUN_BUDGET_MS = 7L * 60 * 1000
        const val CONTINUATION_DELAY_MS = 60_000L
    }
}

// Only a change in server recommendations can schedule a rotation. Song and
// storage limits gate new downloads, so changing them cannot evict saved music.
internal fun obsoleteRecommendations(
    previous: SmartDownloadsPlan?,
    recommendations: Map<String, List<String>>,
    savedIds: Set<String>,
): Set<String> {
    if (recommendations.isEmpty() || recommendations == previous?.playlists) {
        return previous?.obsoleteVideoIds.orEmpty()
    }
    return savedIds - recommendations.values.flatten().toSet()
}

class SmartDownloadsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val sync = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java).smartDownloadsSync()
        return try {
            if (sync.sync()) Result.success() else Result.retry()
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            val reason = (error as? InnerTubeMusicApi.InnerTubeHttpException)?.responseCode
                ?.let { "HTTP $it" } ?: error.javaClass.simpleName
            Log.w("SmartDownloads", "Automatic downloads will retry ($reason)")
            Result.retry()
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun smartDownloadsSync(): SmartDownloadsSync
    }
}
