package com.lastwave.app.data.localmusic

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.ContextCompat
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.generate.LocalAudioFormats
import com.lastwave.app.data.generate.isLocalFile
import com.lastwave.app.data.playlist.PlaylistRepository
import com.lastwave.app.playback.AudioHeaderInspector
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local music indexer combining:
 * - Multi-folder Storage Access Framework trees
 * - Optional device-wide MediaStore.Audio auto-discovery with smart filtering
 *   for ringtones, alarms, voice recordings, and short SFX clips
 * - Rich tag extraction (track/disc numbers, album artist, duration, embedded
 *   & folder artwork, and `.lrc` sidecar URIs for SAF & filesystem paths)
 * - Direct playback from device storage with automatic pruning of deleted files
 */
@Singleton
class LocalMusicLibrary @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playlistRepository: PlaylistRepository,
    private val offlineRepository: LocalOfflineRepository,
) {
    data class ImportReport(
        val added: Int = 0,
        val updated: Int = 0,
        val unchanged: Int = 0,
        val removed: Int = 0,
        /** Extension -> count of audio files this player cannot open. */
        val unsupported: Map<String, Int> = emptyMap(),
        val failed: Int = 0,
        val bytesCopied: Long = 0L,
        val unreachableFolders: Int = 0,
        val outOfSpace: Boolean = false,
    )

    sealed interface ImportState {
        data object Idle : ImportState
        data class Scanning(val filesFound: Int) : ImportState
        data class Copying(val done: Int, val total: Int, val currentName: String) : ImportState
        data class Finished(val report: ImportReport) : ImportState
    }

    private data class DiscoveredEntry(
        val audioUrl: String,
        val displayName: String,
        val filterPath: String = displayName,
        val docUri: Uri? = null,
        val treeUri: Uri? = null,
        val documentId: String? = null,
        val coverTreeUri: Uri? = null,
        val coverDocumentId: String? = null,
        val lrcUri: String? = null,
        val mediaStoreAlbumId: Long? = null,
        val prefillTitle: String? = null,
        val prefillArtist: String? = null,
        val prefillAlbum: String? = null,
        val prefillAlbumArtist: String? = null,
        val prefillTrackNumber: Int? = null,
        val prefillDiscNumber: Int? = null,
        val prefillDurationMs: Long? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var job: Job? = null
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val root: File get() = File(context.filesDir, DIR).apply { mkdirs() }
    private val artDir: File get() = File(root, ART_DIR).apply { mkdirs() }

    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = _state.asStateFlow()

    private val _folders = MutableStateFlow(loadFolders())
    val folders: StateFlow<List<Uri>> = _folders.asStateFlow()

    private val _trackFolders = MutableStateFlow<Map<String, String>>(loadStringMap(KEY_TRACK_FOLDERS))
    val trackFolders: StateFlow<Map<String, String>> = _trackFolders.asStateFlow()

    private val _trackLrcUris = MutableStateFlow<Map<String, String>>(loadStringMap(KEY_TRACK_LRC_URIS))
    val trackLrcUris: StateFlow<Map<String, String>> = _trackLrcUris.asStateFlow()

    private val _autoScanEnabled = MutableStateFlow(prefs.getBoolean(KEY_AUTO_SCAN, true))
    val autoScanEnabled: StateFlow<Boolean> = _autoScanEnabled.asStateFlow()

    private val _filterNonMusicAudio = MutableStateFlow(prefs.getBoolean(KEY_FILTER_NON_MUSIC, true))
    val filterNonMusicAudio: StateFlow<Boolean> = _filterNonMusicAudio.asStateFlow()

    init {
        if (_folders.value.isNotEmpty() || (_autoScanEnabled.value && hasAudioPermission())) {
            rescanAll()
        }
    }

    val isRunning: Boolean get() = job?.isActive == true

    fun hasAudioPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun setAutoScanEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_SCAN, enabled).apply()
        _autoScanEnabled.value = enabled
        rescanAll()
    }

    fun setFilterNonMusicAudio(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FILTER_NON_MUSIC, enabled).apply()
        _filterNonMusicAudio.value = enabled
        rescanAll()
    }

    /** Selected folders define the complete scan scope, even when device auto-scan is enabled. */
    fun addFolderAndImport(treeUri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.onFailure { Log.w(TAG, "No persistable grant for $treeUri; rescan may fail later", it) }
        val updated = (_folders.value.filterNot { it == treeUri } + treeUri)
        saveFolders(updated)
        rescanAll()
    }

    /** MediaStore is a fallback only when no folders have been selected. */
    fun rescanAll() {
        val folders = _folders.value
        val useMediaStore = LocalMusicFilters.shouldScanDevice(folders.size, _autoScanEnabled.value)
        launchImport(folders = folders, includeMediaStore = useMediaStore)
    }

    /** Removes a scan source without deleting its files or saved local likes. */
    fun forgetFolder(treeUri: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        saveFolders(_folders.value.filterNot { it == treeUri })
        rescanAll()
    }

    suspend fun removeTracks(urls: Collection<String>) {
        if (urls.isEmpty()) return
        val localPlaylist = playlistRepository.getLocalSongs()
        if (localPlaylist != null) {
            val removalSet = urls.toSet()
            playlistRepository.mergeLocalSongs(
                updates = emptyMap(),
                additions = emptyList(),
                removals = removalSet,
            )
        }
        offlineRepository.removeTracks(urls)
    }

    fun acknowledgeReport() {
        if (_state.value is ImportState.Finished) _state.value = ImportState.Idle
    }

    fun folderLabel(treeUri: Uri): String {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return treeUri.toString()
        val (volume, path) = docId.substringBefore(':') to docId.substringAfter(':', "")
        return when {
            path.isBlank() -> if (volume == "primary") "Internal storage" else volume
            volume == "primary" -> path
            else -> "$volume/$path"
        }
    }

    fun lrcUriFor(trackUrl: String?): String? {
        if (trackUrl.isNullOrBlank()) return null
        _trackLrcUris.value[trackUrl]?.takeIf { it.isNotBlank() }?.let { return it }
        LocalAudioFormats.importedPathOrNull(trackUrl)
            ?.takeIf { !it.startsWith("content://") }
            ?.let { fsPath ->
                val sidecar = LocalAudioFormats.lrcPathFor(fsPath)
                if (File(sidecar).isFile) return sidecar
            }
        return null
    }

    /** Resolves metadata for a single local file/URI opened via Android's "Open With" intent. */
    suspend fun inspectAudioUri(uri: Uri): GeneratedTrack {
        val displayName = resolveDisplayName(uri)
        val audioUrl = resolveSingleAudioUrl(uri)
        return readTrack(
            DiscoveredEntry(
                audioUrl = audioUrl,
                displayName = displayName,
                docUri = if (audioUrl.startsWith("content://")) Uri.parse(audioUrl) else null,
            )
        )
    }

    private fun launchImport(folders: List<Uri>, includeMediaStore: Boolean) {
        // A folder/filter change must not be discarded while an earlier scan is running.
        job?.cancel()
        job = scope.launch {
            mutex.withLock {
                _state.value = ImportState.Scanning(0)
                val report = try {
                    runImport(folders, includeMediaStore)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Local import failed", e)
                    ImportReport(failed = 1)
                }
                _state.value = ImportState.Finished(report)
            }
        }
    }

    private suspend fun runImport(
        folders: List<Uri>,
        includeMediaStore: Boolean,
    ): ImportReport {
        val entries = mutableListOf<DiscoveredEntry>()
        val unsupported = mutableMapOf<String, Int>()
        val unreachable = mutableSetOf<Uri>()
        val filterNonMusic = _filterNonMusicAudio.value

        for (tree in folders) {
            val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
            if (rootDocId == null || !walkSaf(tree, rootDocId, 0, entries, unsupported)) unreachable += tree
        }

        val mediaStoreComplete = if (includeMediaStore) {
            hasAudioPermission() && scanMediaStoreEntries(entries, unsupported)
        } else true

        val existingPlaylist = playlistRepository.ensureLocalSongs()
        val existingTracks = existingPlaylist.tracks
        val existingByUrl = existingTracks.associateBy { it.url }.toMutableMap()

        val legacyUrlToTrack = existingTracks.filter { it.url.contains("/$DIR/") }.associateBy { it.url }
        val existingByNameArtist = existingTracks.groupBy {
            "${it.name.trim().lowercase()}|${it.artist.trim().lowercase()}"
        }

        val updates = mutableMapOf<String, GeneratedTrack>()
        val additions = mutableListOf<GeneratedTrack>()
        val folderMapping = _trackFolders.value.toMutableMap()
        val lrcMapping = _trackLrcUris.value.toMutableMap()
        val processedUrls = mutableSetOf<String>()
        val rejectedUrls = mutableSetOf<String>()
        var unchanged = 0
        var failed = 0

        entries.forEachIndexed { index, entry ->
            currentCoroutineContext().ensureActive()
            _state.value = ImportState.Copying(index, entries.size, entry.displayName)
            try {
                val audioUrl = entry.audioUrl
                entry.treeUri?.let { folderMapping[audioUrl] = it.toString() }
                if (entry.lrcUri != null) {
                    lrcMapping[audioUrl] = entry.lrcUri
                } else {
                    LocalAudioFormats.importedPathOrNull(audioUrl)
                        ?.takeIf { !it.startsWith("content://") }
                        ?.let { fsPath ->
                            val sidecar = LocalAudioFormats.lrcPathFor(fsPath)
                            if (File(sidecar).isFile) lrcMapping[audioUrl] = sidecar
                        }
                }

                if (!processedUrls.add(audioUrl)) return@forEachIndexed

                val oldId = if (entry.treeUri != null && entry.documentId != null) {
                    LocalMusicNaming.stableId("${entry.treeUri}|${entry.documentId}")
                } else null
                val ext = LocalAudioFormats.extensionOf(entry.displayName)
                val oldCopiedPath = oldId?.let { File(root, "$it.$ext").absolutePath }
                val matchedLegacyTrack = oldCopiedPath?.let { legacyUrlToTrack[it] }
                val matchedDirect = existingByUrl[audioUrl]

                val fallbackMatches = if (matchedDirect == null && matchedLegacyTrack == null) {
                    val fallbackNames = LocalMusicNaming.fromFileName(entry.displayName)
                    existingByNameArtist["${fallbackNames.title.trim().lowercase()}|${(fallbackNames.artist ?: UNKNOWN_ARTIST).trim().lowercase()}"]
                        ?.filter { it.url.contains("/$DIR/") }
                } else null
                val matchedTrack = matchedDirect ?: matchedLegacyTrack ?: fallbackMatches?.firstOrNull()
                matchedTrack?.let { processedUrls += it.url }
                val freshTrack = if (matchedTrack == null) readTrack(entry) else null
                if (filterNonMusic && LocalMusicFilters.isNonMusicAudio(
                        filePathOrRelative = entry.filterPath,
                        durationMs = freshTrack?.durationMs ?: matchedTrack?.durationMs ?: entry.prefillDurationMs,
                    )
                ) {
                    rejectedUrls += audioUrl
                    matchedTrack?.let { rejectedUrls += it.url }
                    return@forEachIndexed
                }

                if (matchedTrack != null) {
                    val needsMetadataRefresh = matchedTrack.url != audioUrl ||
                        (matchedTrack.durationMs == null && (entry.prefillDurationMs ?: 0L) > 0L) ||
                        matchedTrack.trackNumber == null && entry.prefillTrackNumber != null ||
                        (matchedTrack.lrcUri == null && lrcMapping[audioUrl] != null)

                    if (needsMetadataRefresh) {
                        val enriched = if (matchedTrack.url != audioUrl) {
                            readTrack(entry).let { fresh ->
                                matchedTrack.copy(
                                    url = audioUrl,
                                    trackNumber = matchedTrack.trackNumber ?: fresh.trackNumber,
                                    discNumber = matchedTrack.discNumber ?: fresh.discNumber,
                                    albumArtist = matchedTrack.albumArtist ?: fresh.albumArtist,
                                    durationMs = matchedTrack.durationMs ?: fresh.durationMs,
                                    lrcUri = matchedTrack.lrcUri ?: lrcMapping[audioUrl] ?: fresh.lrcUri,
                                    artworkUrl = matchedTrack.artworkUrl ?: fresh.artworkUrl,
                                    album = matchedTrack.album ?: fresh.album,
                                )
                            }
                        } else {
                            matchedTrack.copy(
                                trackNumber = matchedTrack.trackNumber ?: entry.prefillTrackNumber,
                                discNumber = matchedTrack.discNumber ?: entry.prefillDiscNumber,
                                albumArtist = matchedTrack.albumArtist ?: entry.prefillAlbumArtist,
                                durationMs = matchedTrack.durationMs ?: entry.prefillDurationMs,
                                lrcUri = matchedTrack.lrcUri ?: lrcMapping[audioUrl],
                            )
                        }
                        updates[matchedTrack.url] = enriched
                        existingByUrl[audioUrl] = enriched
                    } else {
                        unchanged++
                    }
                } else {
                    val track = checkNotNull(freshTrack)
                    val withLrc = if (track.lrcUri == null && lrcMapping[audioUrl] != null) {
                        track.copy(lrcUri = lrcMapping[audioUrl])
                    } else track
                    additions += withLrc
                    existingByUrl[audioUrl] = withLrc
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't import ${entry.displayName}", e)
                failed++
            }
        }

        // Accessible files outside the selected scope (or rejected by the filter) must
        // leave the index too. A failed source preserves its previous entries for retry.
        val removals = existingTracks.filter { track ->
            track.isLocalFile() && (track.url in rejectedUrls ||
                (track.url !in processedUrls && mediaStoreComplete &&
                    unreachable.none { tree -> trackBelongsToTree(track.url, tree) }))
        }.mapTo(LinkedHashSet()) { it.url }

        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            if (updates.isNotEmpty() || additions.isNotEmpty() || removals.isNotEmpty()) {
                playlistRepository.mergeLocalSongs(updates, additions, removals)
            }
            // Deselecting a folder must not erase its likes or user-created playlists.
            val missingFiles = removals.filter { url ->
                (includeMediaStore || folders.any { trackBelongsToTree(url, it) }) && !isLocalUrlAccessible(url)
            }
            if (missingFiles.isNotEmpty()) offlineRepository.removeTracks(missingFiles)
            saveStringMap(KEY_TRACK_FOLDERS, folderMapping - removals) { _trackFolders.value = it }
            saveStringMap(KEY_TRACK_LRC_URIS, lrcMapping - removals) { _trackLrcUris.value = it }
        }
        _state.value = ImportState.Copying(entries.size, entries.size, "")

        cleanLegacyCopiedAudioFiles()

        return ImportReport(
            added = additions.size,
            updated = updates.size,
            unchanged = unchanged,
            removed = removals.size,
            unsupported = unsupported,
            failed = failed,
            bytesCopied = 0L,
            unreachableFolders = unreachable.size,
        )
    }

    /** Depth-first walk of a SAF tree. Returns false when the folder can't be listed. */
    private suspend fun walkSaf(
        tree: Uri,
        parentDocId: String,
        depth: Int,
        out: MutableList<DiscoveredEntry>,
        unsupported: MutableMap<String, Int>,
        relativePath: String = parentDocId.substringAfter(':', parentDocId),
    ): Boolean {
        currentCoroutineContext().ensureActive()
        if (depth > MAX_DEPTH) return false
        data class Child(val id: String, val name: String, val mime: String?, val size: Long)
        val children = mutableListOf<Child>()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val ok = runCatching {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    if (id != null) {
                        children += Child(
                            id = id,
                            name = c.getString(1).orEmpty(),
                            mime = c.getString(2),
                            size = if (c.isNull(3)) -1L else c.getLong(3),
                        )
                    }
                }
                true
            } ?: false
        }.getOrDefault(false)
        if (!ok) return false

        val files = children.filter { it.mime != DocumentsContract.Document.MIME_TYPE_DIR }
        val lrcByBase = files.filter { LocalAudioFormats.extensionOf(it.name) == "lrc" }
            .associateBy { it.name.substringBeforeLast('.').lowercase() }
        val folderCover = files.firstOrNull { file ->
            val base = file.name.substringBeforeLast('.').lowercase()
            val ext = LocalAudioFormats.extensionOf(file.name)
            base in COVER_NAMES && ext in IMAGE_EXTENSIONS
        }
        files.sortedBy { it.name.lowercase() }.forEach { file ->
            if (_filterNonMusicAudio.value && LocalMusicFilters.isNonMusicAudio("$relativePath/${file.name}", null)) {
                return@forEach
            }
            val ext = LocalAudioFormats.extensionOf(file.name)
            when {
                LocalAudioFormats.isSupported(file.name) -> {
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, file.id)
                    val audioUrl = resolveAudioPathOrUri(docUri, file.id)
                    val lrcId = lrcByBase[file.name.substringBeforeLast('.').lowercase()]?.id
                    val lrcUri = lrcId?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it).toString() }
                    out += DiscoveredEntry(
                        audioUrl = audioUrl,
                        displayName = file.name,
                        filterPath = "$relativePath/${file.name}",
                        docUri = docUri,
                        treeUri = tree,
                        documentId = file.id,
                        coverTreeUri = tree,
                        coverDocumentId = folderCover?.id,
                        lrcUri = lrcUri,
                    )
                }
                ext in LocalAudioFormats.UNSUPPORTED_AUDIO -> unsupported[ext] = (unsupported[ext] ?: 0) + 1
            }
        }
        _state.value = ImportState.Scanning(out.size)
        var complete = true
        children.filter { it.mime == DocumentsContract.Document.MIME_TYPE_DIR }
            .sortedBy { it.name.lowercase() }
            .forEach { dir ->
                if (!walkSaf(tree, dir.id, depth + 1, out, unsupported, "$relativePath/${dir.name}")) complete = false
            }
        return complete
    }

    private suspend fun scanMediaStoreEntries(
        out: MutableList<DiscoveredEntry>,
        unsupported: MutableMap<String, Int>,
    ): Boolean {
        currentCoroutineContext().ensureActive()
        val seenUrls = out.mapTo(HashSet()) { it.audioUrl }
        val filterNonMusic = _filterNonMusicAudio.value

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val cols = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.IS_MUSIC,
            MediaStore.Audio.Media.IS_ALARM,
            MediaStore.Audio.Media.IS_NOTIFICATION,
            MediaStore.Audio.Media.IS_RINGTONE,
            MediaStore.Audio.Media.IS_PODCAST,
            @Suppress("DEPRECATION") MediaStore.Audio.Media.DATA,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            cols += MediaStore.Audio.Media.RELATIVE_PATH
            cols += MediaStore.Audio.Media.IS_AUDIOBOOK
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            cols += MediaStore.Audio.Media.IS_RECORDING
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            cols += MediaStore.Audio.Media.ALBUM_ARTIST
            cols += MediaStore.Audio.Media.DISC_NUMBER
            cols += MediaStore.Audio.Media.CD_TRACK_NUMBER
        }

        return try {
            context.contentResolver.query(
                collection,
                cols.toTypedArray(),
                null,
                null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
            )?.use { c ->
                val idxId = c.getColumnIndex(MediaStore.Audio.Media._ID)
                val idxName = c.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                val idxTitle = c.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val idxArtist = c.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val idxAlbum = c.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                val idxTrack = c.getColumnIndex(MediaStore.Audio.Media.TRACK)
                val idxDuration = c.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val idxAlbumId = c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
                val idxIsMusic = c.getColumnIndex(MediaStore.Audio.Media.IS_MUSIC)
                val idxIsRecording = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    c.getColumnIndex(MediaStore.Audio.Media.IS_RECORDING)
                } else -1
                val idxIsAudiobook = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    c.getColumnIndex(MediaStore.Audio.Media.IS_AUDIOBOOK)
                } else -1
                val idxIsAlarm = c.getColumnIndex(MediaStore.Audio.Media.IS_ALARM)
                val idxIsNotif = c.getColumnIndex(MediaStore.Audio.Media.IS_NOTIFICATION)
                val idxIsRing = c.getColumnIndex(MediaStore.Audio.Media.IS_RINGTONE)
                val idxIsPodcast = c.getColumnIndex(MediaStore.Audio.Media.IS_PODCAST)
                @Suppress("DEPRECATION")
                val idxData = c.getColumnIndex(MediaStore.Audio.Media.DATA)
                val idxRelPath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    c.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                } else -1
                val idxAlbumArtist = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ARTIST)
                } else -1
                val idxDisc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    c.getColumnIndex(MediaStore.Audio.Media.DISC_NUMBER)
                } else -1
                val idxCdTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    c.getColumnIndex(MediaStore.Audio.Media.CD_TRACK_NUMBER)
                } else -1

                while (c.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val id = if (idxId >= 0) c.getLong(idxId) else continue
                    val dataPath = if (idxData >= 0) c.getString(idxData)?.takeIf { it.isNotBlank() } else null
                    val displayName = (if (idxName >= 0) c.getString(idxName) else null)
                        ?: dataPath?.substringAfterLast('/')
                        ?: continue
                    val relPath = if (idxRelPath >= 0) c.getString(idxRelPath) else null
                    val durationMs = if (idxDuration >= 0 && !c.isNull(idxDuration)) c.getLong(idxDuration) else null

                    val isAlarm = idxIsAlarm >= 0 && c.getInt(idxIsAlarm) != 0
                    val isNotif = idxIsNotif >= 0 && c.getInt(idxIsNotif) != 0
                    val isRingtone = idxIsRing >= 0 && c.getInt(idxIsRing) != 0
                    val isPodcast = idxIsPodcast >= 0 && c.getInt(idxIsPodcast) != 0

                    if (filterNonMusic && LocalMusicFilters.isNonMusicAudio(
                            filePathOrRelative = dataPath ?: "${relPath.orEmpty()}/$displayName",
                            durationMs = durationMs,
                            isMusic = if (idxIsMusic >= 0 && !c.isNull(idxIsMusic)) c.getInt(idxIsMusic) != 0 else null,
                            isRecording = idxIsRecording >= 0 && c.getInt(idxIsRecording) != 0,
                            isAudiobook = idxIsAudiobook >= 0 && c.getInt(idxIsAudiobook) != 0,
                            isAlarm = isAlarm,
                            isNotification = isNotif,
                            isRingtone = isRingtone,
                            isPodcast = isPodcast,
                        )
                    ) {
                        continue
                    }

                    val ext = LocalAudioFormats.extensionOf(displayName)
                    if (!LocalAudioFormats.isSupported(displayName)) {
                        if (ext in LocalAudioFormats.UNSUPPORTED_AUDIO) {
                            unsupported[ext] = (unsupported[ext] ?: 0) + 1
                        }
                        continue
                    }

                    val directFile = dataPath?.let(::File)?.takeIf { it.isFile && it.canRead() }
                    val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    val audioUrl = directFile?.absolutePath ?: contentUri.toString()
                    if (!seenUrls.add(audioUrl)) continue

                    val rawTrack = if (idxCdTrack >= 0) {
                        LocalMusicFilters.parseNumberPart(c.getString(idxCdTrack))
                    } else null
                    val fallbackTrackInt = if (idxTrack >= 0 && !c.isNull(idxTrack)) c.getInt(idxTrack) else 0
                    val (parsedDisc, parsedTrack) = LocalMusicFilters.splitPackedTrackDisc(
                        rawTrack = rawTrack ?: fallbackTrackInt.takeIf { it > 0 },
                        explicitDisc = if (idxDisc >= 0) LocalMusicFilters.parseNumberPart(c.getString(idxDisc)) else null,
                    )

                    val rawArtist = if (idxArtist >= 0) c.getString(idxArtist)?.takeIf { it.isNotBlank() && it != "<unknown>" } else null
                    val rawAlbum = if (idxAlbum >= 0) c.getString(idxAlbum)?.takeIf { it.isNotBlank() && it != "<unknown>" } else null
                    val rawAlbumArtist = if (idxAlbumArtist >= 0) c.getString(idxAlbumArtist)?.takeIf { it.isNotBlank() && it != "<unknown>" } else null
                    val rawTitle = if (idxTitle >= 0) c.getString(idxTitle)?.takeIf { it.isNotBlank() } else null
                    val albumId = if (idxAlbumId >= 0 && !c.isNull(idxAlbumId)) c.getLong(idxAlbumId) else null
                    val fsLrc = directFile?.absolutePath?.let(LocalAudioFormats::lrcPathFor)
                        ?.takeIf { File(it).isFile }

                    out += DiscoveredEntry(
                        audioUrl = audioUrl,
                        displayName = displayName,
                        filterPath = dataPath ?: "${relPath.orEmpty()}/$displayName",
                        docUri = contentUri,
                        lrcUri = fsLrc,
                        mediaStoreAlbumId = albumId,
                        prefillTitle = rawTitle,
                        prefillArtist = rawArtist,
                        prefillAlbum = rawAlbum,
                        prefillAlbumArtist = rawAlbumArtist,
                        prefillTrackNumber = parsedTrack,
                        prefillDiscNumber = parsedDisc,
                        prefillDurationMs = durationMs?.takeIf { it > 0L },
                    )
                    if (out.size % 25 == 0) _state.value = ImportState.Scanning(out.size)
                }
                true
            } ?: false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore audio query failed", e)
            false
        }
    }

    private fun trackBelongsToTree(trackUrl: String, tree: Uri): Boolean {
        if (_trackFolders.value[trackUrl] == tree.toString()) return true
        return runCatching {
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            val trackUri = Uri.parse(trackUrl)
            if (trackUri.scheme == "content" && trackUri.authority == tree.authority) {
                LocalMusicFilters.isWithinFolder(DocumentsContract.getDocumentId(trackUri), rootId)
            } else if (tree.authority == "com.android.externalstorage.documents") {
                val folderPath = when {
                    rootId.startsWith("primary:") -> "/storage/emulated/0/${rootId.substringAfter(':')}"
                    rootId.startsWith("raw:") -> rootId.substringAfter(':')
                    else -> "/storage/${rootId.substringBefore(':')}/${rootId.substringAfter(':')}"
                }
                LocalMusicFilters.isWithinFolder(trackUrl.removePrefix("file://"), folderPath)
            } else false
        }.getOrDefault(false)
    }

    private fun resolveAudioPathOrUri(docUri: Uri, docId: String): String {
        val decodedDocId = runCatching { Uri.decode(docId) }.getOrNull() ?: docId
        val candidateFile = when {
            docId.startsWith("primary:") || decodedDocId.startsWith("primary:") -> {
                val rel = if (docId.startsWith("primary:")) docId.removePrefix("primary:") else decodedDocId.removePrefix("primary:")
                File("/storage/emulated/0", rel)
            }
            docId.startsWith("raw:") || decodedDocId.startsWith("raw:") -> {
                val raw = if (docId.startsWith("raw:")) docId.removePrefix("raw:") else decodedDocId.removePrefix("raw:")
                File(raw)
            }
            docId.contains(':') -> {
                val volume = docId.substringBefore(':')
                val rel = docId.substringAfter(':')
                File("/storage/$volume", rel)
            }
            decodedDocId.contains(':') -> {
                val volume = decodedDocId.substringBefore(':')
                val rel = decodedDocId.substringAfter(':')
                File("/storage/$volume", rel)
            }
            else -> null
        }
        if (candidateFile != null && candidateFile.exists() && candidateFile.canRead()) {
            return candidateFile.absolutePath
        }
        return docUri.toString()
    }

    private fun resolveSingleAudioUrl(uri: Uri): String {
        if (uri.scheme == "file") {
            return uri.path?.takeIf { File(it).isFile && File(it).canRead() } ?: uri.toString()
        }
        runCatching {
            val docId = if (DocumentsContract.isDocumentUri(context, uri)) DocumentsContract.getDocumentId(uri) else null
            if (docId != null) {
                return resolveAudioPathOrUri(uri, docId)
            }
        }
        return uri.toString()
    }

    private fun resolveDisplayName(uri: Uri): String {
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(0)?.takeIf { it.isNotBlank() }?.let { return it }
                    }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "Audio Track"
    }

    private fun isLocalUrlAccessible(url: String): Boolean = try {
        if (url.startsWith("content://")) {
            context.contentResolver.openAssetFileDescriptor(Uri.parse(url), "r")?.use { true } ?: false
        } else {
            val f = File(url.removePrefix("file://"))
            f.isFile && f.canRead()
        }
    } catch (_: Exception) {
        false
    }

    private fun copyDocument(tree: Uri, documentId: String, dest: File) {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
        val tmp = File(dest.parentFile, dest.name + ".part")
        context.contentResolver.openInputStream(uri).use { input ->
            checkNotNull(input) { "Can't open $documentId" }
            tmp.outputStream().use { output -> input.copyTo(output, COPY_BUFFER) }
        }
        if (dest.exists()) dest.delete()
        check(tmp.renameTo(dest)) { "Can't finalize ${dest.name}" }
    }

    private fun readTrack(entry: DiscoveredEntry): GeneratedTrack {
        var title: String? = entry.prefillTitle
        var artist: String? = entry.prefillArtist
        var album: String? = entry.prefillAlbum
        var albumArtist: String? = entry.prefillAlbumArtist
        var trackNumber: Int? = entry.prefillTrackNumber
        var discNumber: Int? = entry.prefillDiscNumber
        var durationMs: Long? = entry.prefillDurationMs
        var artwork: String? = null

        val retriever = MediaMetadataRetriever()
        try {
            if (entry.audioUrl.startsWith("/")) {
                retriever.setDataSource(entry.audioUrl)
            } else {
                val docUri = entry.docUri ?: Uri.parse(entry.audioUrl)
                retriever.setDataSource(context, docUri)
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { title = it }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { artist = it }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)?.trim()?.takeIf { it.isNotEmpty() }
                ?.let {
                    albumArtist = it
                    if (artist.isNullOrBlank()) artist = it
                }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { album = it }
            val metaTrack = LocalMusicFilters.parseNumberPart(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
            )
            val metaDisc = LocalMusicFilters.parseNumberPart(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)
            )
            val (sDisc, sTrack) = LocalMusicFilters.splitPackedTrackDisc(metaTrack, metaDisc)
            if (sTrack != null) trackNumber = sTrack
            if (sDisc != null) discNumber = sDisc
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?.takeIf { it > 0L }?.let { durationMs = it }

            retriever.embeddedPicture?.takeIf { it.isNotEmpty() }?.let { bytes ->
                val art = File(artDir, LocalMusicNaming.stableId(bytes) + ".img")
                if (!art.exists()) art.writeBytes(bytes)
                artwork = Uri.fromFile(art).toString()
            }
        } catch (e: Exception) {
            Log.w(TAG, "No readable retriever tags in ${entry.displayName}", e)
        } finally {
            runCatching { retriever.release() }
        }

        if (durationMs == null) {
            durationMs = AudioHeaderInspector.inspect(context, entry.audioUrl)?.durationMs
        }

        // Folder artwork fallback: SAF cover document or filesystem sibling cover image
        if (artwork == null && entry.coverTreeUri != null && entry.coverDocumentId != null) {
            val art = File(artDir, LocalMusicNaming.stableId("${entry.coverTreeUri}|${entry.coverDocumentId}") + ".img")
            if (art.exists() || runCatching { copyDocument(entry.coverTreeUri, entry.coverDocumentId, art) }.isSuccess) {
                artwork = Uri.fromFile(art).toString()
            }
        }
        if (artwork == null && entry.audioUrl.startsWith("/")) {
            val parent = File(entry.audioUrl).parentFile
            val coverFile = parent?.listFiles()?.firstOrNull { f ->
                f.isFile && f.nameWithoutExtension.lowercase() in COVER_NAMES &&
                    LocalAudioFormats.extensionOf(f.name) in IMAGE_EXTENSIONS
            }
            if (coverFile != null && coverFile.canRead()) {
                val art = File(artDir, LocalMusicNaming.stableId(coverFile.absolutePath) + ".img")
                if (!art.exists()) runCatching { coverFile.copyTo(art, overwrite = false) }
                if (art.exists()) artwork = Uri.fromFile(art).toString()
            }
        }
        if (artwork == null && entry.mediaStoreAlbumId != null) {
            val msArtUri = ContentUris.withAppendedId(
                Uri.parse("content://media/external/audio/albumart"),
                entry.mediaStoreAlbumId,
            )
            runCatching {
                context.contentResolver.openInputStream(msArtUri)?.use { input ->
                    val art = File(artDir, LocalMusicNaming.stableId("album:${entry.mediaStoreAlbumId}") + ".img")
                    if (!art.exists()) art.outputStream().use { out -> input.copyTo(out) }
                    if (art.exists() && art.length() > 0L) artwork = Uri.fromFile(art).toString()
                }
            }
        }

        val fallback = LocalMusicNaming.fromFileName(entry.displayName)
        return GeneratedTrack(
            name = title ?: fallback.title,
            artist = artist ?: fallback.artist ?: UNKNOWN_ARTIST,
            album = album,
            artworkUrl = artwork,
            url = entry.audioUrl,
            trackNumber = trackNumber,
            discNumber = discNumber,
            albumArtist = albumArtist,
            durationMs = durationMs,
            lrcUri = entry.lrcUri,
        )
    }

    /** Deletes any legacy audio files copied into app storage by older versions. */
    fun cleanLegacyCopiedAudioFiles() {
        runCatching {
            root.listFiles()?.forEach { file ->
                if (file.isFile) {
                    val ext = LocalAudioFormats.extensionOf(file.name)
                    if (LocalAudioFormats.isSupported(file.name) || ext in LocalAudioFormats.UNSUPPORTED_AUDIO || file.name.endsWith(".part")) {
                        file.delete()
                    }
                }
            }
        }
    }

    fun cleanupOrphansAsync() {
        scope.launch {
            mutex.withLock {
                runCatching { cleanupOrphans() }.onFailure { Log.w(TAG, "Orphan cleanup failed", it) }
            }
        }
    }

    private suspend fun cleanupOrphans() {
        val playlists = playlistRepository.getAll()
        if (playlists.isEmpty()) return
        val tracks = playlists.flatMap { it.tracks }
        val audioUrls = tracks.filter { it.isLocalFile() }.mapTo(mutableSetOf()) { it.url }
        val art = tracks.mapNotNull { it.artworkUrl }
            .filter { it.startsWith("file://") }
            .mapTo(mutableSetOf()) { Uri.parse(it).lastPathSegment.orEmpty() }

        cleanLegacyCopiedAudioFiles()
        artDir.listFiles()?.forEach { file -> if (file.name !in art) file.delete() }
        saveStringMap(KEY_TRACK_FOLDERS, _trackFolders.value.filterKeys { it in audioUrls }) {
            _trackFolders.value = it
        }
        saveStringMap(KEY_TRACK_LRC_URIS, _trackLrcUris.value.filterKeys { it in audioUrls }) {
            _trackLrcUris.value = it
        }
    }

    /** Bytes used by cached covers. Audio files play straight from storage. */
    fun storageBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun getFolderForTrack(trackUrl: String): Uri? =
        _trackFolders.value[trackUrl]?.let(Uri::parse)

    private fun loadFolders(): List<Uri> =
        prefs.getString(KEY_FOLDERS, null)
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            ?.map(Uri::parse)
            .orEmpty()

    private fun saveFolders(folders: List<Uri>) {
        prefs.edit().putString(KEY_FOLDERS, folders.joinToString("\n")).apply()
        _folders.value = folders
    }

    private fun loadStringMap(key: String): Map<String, String> =
        prefs.getString(key, null)
            ?.lineSequence()
            ?.mapNotNull { line ->
                val idx = line.indexOf('\t')
                if (idx > 0) line.substring(0, idx) to line.substring(idx + 1) else null
            }
            ?.toMap()
            .orEmpty()

    private inline fun saveStringMap(
        key: String,
        map: Map<String, String>,
        publish: (Map<String, String>) -> Unit,
    ) {
        val serialized = map.entries.joinToString("\n") { "${it.key}\t${it.value}" }
        prefs.edit().putString(key, serialized).apply()
        publish(map)
    }

    companion object {
        private const val TAG = "LocalMusicLibrary"
        const val DIR = LocalAudioFormats.IMPORT_DIR
        private const val ART_DIR = "art"
        private const val PREFS = "local_music"
        private const val KEY_FOLDERS = "folders"
        private const val KEY_TRACK_FOLDERS = "track_folders"
        private const val KEY_TRACK_LRC_URIS = "track_lrc_uris"
        private const val KEY_AUTO_SCAN = "auto_scan_enabled"
        private const val KEY_FILTER_NON_MUSIC = "filter_non_music_audio"
        private const val MAX_DEPTH = 24
        private const val COPY_BUFFER = 256 * 1024
        const val UNKNOWN_ARTIST = "Unknown artist"
        private val COVER_NAMES = setOf("cover", "folder", "front", "album", "albumart")
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    }
}

/** Pure filtering and metadata parsing helpers (unit-tested). */
object LocalMusicFilters {
    private const val MIN_MUSIC_DURATION_MS = 30_000L
    private val NON_MUSIC_PATH_TOKENS = listOf(
        "/alarms/",
        "/notifications/",
        "/ringtones/",
        "/podcasts/",
        "/audiobooks/",
        "/voice recorder/",
        "/voicerecorder/",
        "/call recordings/",
        "/callrecordings/",
        "/soundrecorder/",
        "/recordings/",
        "whatsapp voice notes",
        "whatsapp audio",
        "/telegram audio/",
    )

    fun isNonMusicAudio(
        filePathOrRelative: String,
        durationMs: Long?,
        isAlarm: Boolean = false,
        isNotification: Boolean = false,
        isRingtone: Boolean = false,
        isPodcast: Boolean = false,
        isMusic: Boolean? = null,
        isRecording: Boolean = false,
        isAudiobook: Boolean = false,
    ): Boolean {
        if (isMusic == false || isAlarm || isNotification || isRingtone || isPodcast || isRecording || isAudiobook) return true
        val norm = "/" + filePathOrRelative.replace('\\', '/').lowercase().trimStart('/')
        if (NON_MUSIC_PATH_TOKENS.any { token -> norm.contains(token) }) return true
        val directories = norm.substringBeforeLast('/').split('/')
            .map { it.replace(PATH_SEPARATORS, "") }
        if (directories.any { it in RECORDING_FOLDERS }) return true
        if (RECORDING_FILE_NAME.containsMatchIn(norm.substringAfterLast('/'))) return true
        // Skip <30s SFX/voice clips only when duration was actually probed (>0);
        // keep duration == 0/null so DSD/ALAC containers aren't falsely rejected.
        if (durationMs != null && durationMs > 0L && durationMs < MIN_MUSIC_DURATION_MS) return true
        return false
    }

    private val PATH_SEPARATORS = Regex("[ _-]")
    private val RECORDING_FOLDERS = setOf(
        "recording", "recordings", "recorder", "soundrecorder", "voicerecorder",
        "callrecording", "callrecordings", "callrecorder", "voicenotes", "voicememos",
    )
    private val RECORDING_FILE_NAME = Regex("^(?:ptt[-_]\\d|(?:call|voice)[-_ ](?:record(?:ing)?[-_ ])?\\d|rec(?:ording)?[-_ ]\\d)")

    fun shouldScanDevice(selectedFolderCount: Int, autoScanEnabled: Boolean): Boolean =
        selectedFolderCount == 0 && autoScanEnabled

    fun isWithinFolder(path: String, folder: String): Boolean {
        val normalizedPath = path.replace('\\', '/').trimEnd('/')
        val normalizedFolder = folder.replace('\\', '/').trimEnd('/')
        return normalizedFolder.isNotBlank() &&
            (normalizedPath == normalizedFolder || normalizedPath.startsWith(
                if (normalizedFolder.endsWith(':')) normalizedFolder else "$normalizedFolder/",
            ))
    }

    fun parseNumberPart(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val lead = raw.trim().substringBefore('/').substringBefore(' ').trim()
        return lead.toIntOrNull()?.takeIf { it > 0 }
    }

    /**
     * Unpacks MediaStore's `disc * 1000 + track` encoding when [explicitDisc] is missing.
     * Returns `(discNumber, trackNumber)`.
     */
    fun splitPackedTrackDisc(rawTrack: Int?, explicitDisc: Int?): Pair<Int?, Int?> {
        if (rawTrack == null || rawTrack <= 0) return explicitDisc to null
        if (explicitDisc == null && rawTrack >= 1000) {
            val disc = rawTrack / 1000
            val track = rawTrack % 1000
            return disc.takeIf { it > 0 } to track.takeIf { it > 0 }
        }
        return explicitDisc to rawTrack
    }
}

/** Pure helpers (unit-tested). */
object LocalMusicNaming {
    data class Names(val title: String, val artist: String?)

    /**
     * Fallback when a file has no tags: "01 - Artist - Title.flac" ->
     * (Title, Artist); "Artist - Title.mp3" -> (Title, Artist); else the
     * bare file name as title. A leading track number is dropped.
     */
    fun fromFileName(fileName: String): Names {
        val base = fileName.substringAfterLast('/').substringBeforeLast('.').trim()
        val withoutNumber = base.replace(LEADING_TRACK_NUMBER, "").trim().ifEmpty { base }
        val parts = withoutNumber.split(" - ").map { it.trim() }.filter { it.isNotEmpty() }
        return if (parts.size >= 2) {
            Names(title = parts.drop(1).joinToString(" - "), artist = parts.first())
        } else {
            Names(title = withoutNumber, artist = null)
        }
    }

    fun stableId(key: String): String = stableId(key.toByteArray(Charsets.UTF_8))

    fun stableId(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes)
            .joinToString("") { "%02x".format(it) }
            .take(24)

    private val LEADING_TRACK_NUMBER = Regex("^\\d{1,3}\\s*[-._)]?\\s+")
}
