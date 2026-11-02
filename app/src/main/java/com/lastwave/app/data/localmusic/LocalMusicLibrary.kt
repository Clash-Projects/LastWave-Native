package com.lastwave.app.data.localmusic

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.generate.LocalAudioFormats
import com.lastwave.app.data.generate.isLocalFile
import com.lastwave.app.data.playlist.PlaylistRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Feature "Local songs": indexes audio files from folders the user picks
 * (Storage Access Framework tree) and lists them in the built-in "Local songs" playlist.
 *
 * - Direct playback: plays straight from device storage without copying audio files,
 *   preventing duplicate storage consumption on the phone.
 * - Incremental and deduplicated: each track is identified by its persistent
 *   storage path / URI. Re-scanning only adds genuinely new files and never
 *   duplicates existing songs.
 * - Embedded covers and folder covers (cover/folder/front.jpg|png) are cached
 *   as small thumbnails for fast UI display.
 */
@Singleton
class LocalMusicLibrary @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playlistRepository: PlaylistRepository,
) {
    data class ImportReport(
        val added: Int = 0,
        val updated: Int = 0,
        val unchanged: Int = 0,
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

    private data class AudioEntry(
        val treeUri: Uri,
        val documentId: String,
        val displayName: String,
        val size: Long,
        val lrcDocumentId: String?,
        val coverDocumentId: String?,
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

    private val _trackFolders = MutableStateFlow<Map<String, String>>(loadTrackFolders())
    val trackFolders: StateFlow<Map<String, String>> = _trackFolders.asStateFlow()

    init {
        if (_folders.value.isNotEmpty() && (_trackFolders.value.isEmpty() || _trackFolders.value.keys.any { it.contains("/$DIR/") })) {
            rescanAll()
        }
    }

    val isRunning: Boolean get() = job?.isActive == true

    /** Persists read access to [treeUri], remembers it and imports it. */
    fun addFolderAndImport(treeUri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.onFailure { Log.w(TAG, "No persistable grant for $treeUri; rescan may fail later", it) }
        val updated = (_folders.value.filterNot { it == treeUri } + treeUri)
        saveFolders(updated)
        launchImport(listOf(treeUri))
    }

    /** Re-imports every remembered folder (new files are added, changed ones refreshed). */
    fun rescanAll() {
        launchImport(_folders.value)
    }

    /** Forgets a folder (releases the grant). Already-copied songs stay in "Local songs". */
    fun forgetFolder(treeUri: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val uriStr = treeUri.toString()
        saveFolders(_folders.value.filterNot { it == treeUri })
        saveTrackFolders(_trackFolders.value.filterValues { it != uriStr })
    }

    fun acknowledgeReport() {
        if (_state.value is ImportState.Finished) _state.value = ImportState.Idle
    }

    fun folderLabel(treeUri: Uri): String {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return treeUri.toString()
        // "primary:Music/Albums" -> "Music/Albums"; SD cards keep their volume id.
        val (volume, path) = docId.substringBefore(':') to docId.substringAfter(':', "")
        return when {
            path.isBlank() -> if (volume == "primary") "Internal storage" else volume
            volume == "primary" -> path
            else -> "$volume/$path"
        }
    }

    private fun launchImport(folders: List<Uri>) {
        if (folders.isEmpty() || isRunning) return
        job = scope.launch {
            mutex.withLock {
                _state.value = ImportState.Scanning(0)
                val report = try {
                    runImport(folders)
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

    private suspend fun runImport(folders: List<Uri>): ImportReport {
        val entries = mutableListOf<AudioEntry>()
        val unsupported = mutableMapOf<String, Int>()
        var unreachable = 0
        for (tree in folders) {
            val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
            if (rootDocId == null || !walk(tree, rootDocId, 0, entries, unsupported)) unreachable++
        }

        val existingPlaylist = playlistRepository.ensureLocalSongs()
        val existingTracks = existingPlaylist.tracks
        val existingByUrl = existingTracks.associateBy { it.url }.toMutableMap()

        // Index legacy internal paths for seamless migration of previously copied songs
        val legacyUrlToTrack = existingTracks.filter { it.url.contains("/$DIR/") }.associateBy { it.url }
        val existingByNameArtist = existingTracks.groupBy {
            "${it.name.trim().lowercase()}|${it.artist.trim().lowercase()}"
        }

        val updates = mutableMapOf<String, GeneratedTrack>()
        val additions = mutableListOf<GeneratedTrack>()
        val folderMapping = _trackFolders.value.toMutableMap()
        val processedUrls = mutableSetOf<String>()
        var unchanged = 0
        var failed = 0

        entries.forEachIndexed { index, entry ->
            currentCoroutineContext().ensureActive()
            _state.value = ImportState.Copying(index, entries.size, entry.displayName)
            try {
                val audioUrl = resolveAudioPathOrUri(entry)
                folderMapping[audioUrl] = entry.treeUri.toString()

                if (!processedUrls.add(audioUrl)) {
                    return@forEachIndexed
                }

                // Check for legacy copied path
                val oldId = LocalMusicNaming.stableId("${entry.treeUri}|${entry.documentId}")
                val ext = LocalAudioFormats.extensionOf(entry.displayName)
                val oldCopiedPath = File(root, "$oldId.$ext").absolutePath
                val matchedLegacyTrack = legacyUrlToTrack[oldCopiedPath]

                // Direct match by current URL
                val matchedDirect = existingByUrl[audioUrl]

                // Fallback match by (name, artist) if track was previously imported under legacy path
                val fallbackMatches = if (matchedDirect == null && matchedLegacyTrack == null) {
                    val fallbackNames = LocalMusicNaming.fromFileName(entry.displayName)
                    existingByNameArtist["${fallbackNames.title.trim().lowercase()}|${(fallbackNames.artist ?: UNKNOWN_ARTIST).trim().lowercase()}"]
                        ?.filter { it.url.contains("/$DIR/") }
                } else null
                val matchedByFallback = fallbackMatches?.firstOrNull()

                val matchedTrack = matchedDirect ?: matchedLegacyTrack ?: matchedByFallback

                if (matchedTrack != null) {
                    if (matchedTrack.url != audioUrl) {
                        // Migrate existing track from legacy copied path to direct storage URL
                        val updated = matchedTrack.copy(url = audioUrl)
                        updates[matchedTrack.url] = updated
                        existingByUrl[audioUrl] = updated
                    } else {
                        unchanged++
                    }
                } else {
                    // Newly discovered song: read metadata directly from device storage (NO AUDIO COPYING)
                    val track = readTrack(audioUrl, entry)
                    additions += track
                    existingByUrl[audioUrl] = track
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't import ${entry.displayName}", e)
                failed++
            }
        }

        saveTrackFolders(folderMapping)
        _state.value = ImportState.Copying(entries.size, entries.size, "")

        if (updates.isNotEmpty() || additions.isNotEmpty()) {
            playlistRepository.mergeLocalSongs(updates, additions)
        }

        // Clean up legacy copied audio files to free storage
        cleanLegacyCopiedAudioFiles()

        return ImportReport(
            added = additions.size,
            updated = updates.size,
            unchanged = unchanged,
            unsupported = unsupported,
            failed = failed,
            bytesCopied = 0L,
            unreachableFolders = unreachable,
        )
    }

    /** Depth-first walk of a SAF tree. Returns false when the folder can't be listed. */
    private suspend fun walk(
        tree: Uri,
        parentDocId: String,
        depth: Int,
        out: MutableList<AudioEntry>,
        unsupported: MutableMap<String, Int>,
    ): Boolean {
        currentCoroutineContext().ensureActive()
        if (depth > MAX_DEPTH) return true
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
            val ext = LocalAudioFormats.extensionOf(file.name)
            when {
                LocalAudioFormats.isSupported(file.name) -> out += AudioEntry(
                    treeUri = tree,
                    documentId = file.id,
                    displayName = file.name,
                    size = file.size,
                    lrcDocumentId = lrcByBase[file.name.substringBeforeLast('.').lowercase()]?.id,
                    coverDocumentId = folderCover?.id,
                )
                ext in LocalAudioFormats.UNSUPPORTED_AUDIO -> unsupported[ext] = (unsupported[ext] ?: 0) + 1
            }
        }
        _state.value = ImportState.Scanning(out.size)
        children.filter { it.mime == DocumentsContract.Document.MIME_TYPE_DIR }
            .sortedBy { it.name.lowercase() }
            .forEach { dir -> walk(tree, dir.id, depth + 1, out, unsupported) }
        return true
    }

    /**
     * Resolves an entry to a direct filesystem path on the device when accessible,
     * or to a persistent SAF document URI otherwise. Audio files are played directly
     * from device storage without copying.
     */
    private fun resolveAudioPathOrUri(entry: AudioEntry): String {
        val docId = entry.documentId
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
        return DocumentsContract.buildDocumentUriUsingTree(entry.treeUri, entry.documentId).toString()
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

    private fun readTrack(audioUrl: String, entry: AudioEntry): GeneratedTrack {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var artwork: String? = null
        val retriever = MediaMetadataRetriever()
        try {
            if (audioUrl.startsWith("/")) {
                retriever.setDataSource(audioUrl)
            } else {
                val docUri = DocumentsContract.buildDocumentUriUsingTree(entry.treeUri, entry.documentId)
                retriever.setDataSource(context, docUri)
            }
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim()?.takeIf { it.isNotEmpty() }
            artist = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST))
                ?.trim()?.takeIf { it.isNotEmpty() }
            album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.trim()?.takeIf { it.isNotEmpty() }
            retriever.embeddedPicture?.takeIf { it.isNotEmpty() }?.let { bytes ->
                val art = File(artDir, LocalMusicNaming.stableId(bytes) + ".img")
                if (!art.exists()) art.writeBytes(bytes)
                artwork = Uri.fromFile(art).toString()
            }
        } catch (e: Exception) {
            Log.w(TAG, "No readable tags in ${entry.displayName}", e)
        } finally {
            runCatching { retriever.release() }
        }
        if (artwork == null && entry.coverDocumentId != null) {
            val art = File(artDir, LocalMusicNaming.stableId("${entry.treeUri}|${entry.coverDocumentId}") + ".img")
            if (art.exists() || runCatching { copyDocument(entry.treeUri, entry.coverDocumentId, art) }.isSuccess) {
                artwork = Uri.fromFile(art).toString()
            }
        }
        val fallback = LocalMusicNaming.fromFileName(entry.displayName)
        return GeneratedTrack(
            name = title ?: fallback.title,
            artist = artist ?: fallback.artist ?: UNKNOWN_ARTIST,
            album = album,
            artworkUrl = artwork,
            url = audioUrl,
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

    /**
     * Cleans up unreferenced artwork and legacy copied files.
     */
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
        saveTrackFolders(_trackFolders.value.filterKeys { it in audioUrls })
    }

    /** Bytes used by cached covers and lyrics. Audio files are played directly from device storage. */
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

    private fun loadTrackFolders(): Map<String, String> =
        prefs.getString(KEY_TRACK_FOLDERS, null)
            ?.lineSequence()
            ?.mapNotNull { line ->
                val idx = line.indexOf('\t')
                if (idx > 0) line.substring(0, idx) to line.substring(idx + 1) else null
            }
            ?.toMap()
            .orEmpty()

    private fun saveTrackFolders(map: Map<String, String>) {
        val serialized = map.entries.joinToString("\n") { "${it.key}\t${it.value}" }
        prefs.edit().putString(KEY_TRACK_FOLDERS, serialized).apply()
        _trackFolders.value = map
    }

    companion object {
        private const val TAG = "LocalMusicLibrary"
        const val DIR = LocalAudioFormats.IMPORT_DIR
        private const val ART_DIR = "art"
        private const val PREFS = "local_music"
        private const val KEY_FOLDERS = "folders"
        private const val KEY_TRACK_FOLDERS = "track_folders"
        private const val MAX_DEPTH = 24
        private const val COPY_BUFFER = 256 * 1024
        private const val SPACE_MARGIN_BYTES = 200L * 1024 * 1024
        const val UNKNOWN_ARTIST = "Unknown artist"
        private val COVER_NAMES = setOf("cover", "folder", "front", "album", "albumart")
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
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
