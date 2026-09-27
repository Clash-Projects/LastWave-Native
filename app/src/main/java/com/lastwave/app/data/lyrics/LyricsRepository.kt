package com.lastwave.app.data.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

fun isRtlText(text: CharSequence?): Boolean {
    if (text.isNullOrBlank()) return false
    var rtlCount = 0
    var ltrCount = 0
    var firstStrongRtl: Boolean? = null
    var i = 0
    while (i < text.length) {
        val codePoint = Character.codePointAt(text, i)
        when (Character.getDirectionality(codePoint)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> {
                if (firstStrongRtl == null) firstStrongRtl = true
                rtlCount++
            }
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> {
                if (firstStrongRtl == null) firstStrongRtl = false
                ltrCount++
            }
        }
        i += Character.charCount(codePoint)
    }
    return (firstStrongRtl == true) || (rtlCount > 0 && rtlCount >= ltrCount)
}

data class LyricSyllable(
    val timeMs: Long,
    val durationMs: Long,
    val text: String,
    val isBackground: Boolean = false,
    /** True when this fragment continues the previous token with no space
     *  (Apple Music `part` words like "with"+"drawals"). Views must not
     *  insert a visual separator before it. */
    val appendToPrevious: Boolean = false,
)

data class LyricLine(
    val timeMs: Long,
    val durationMs: Long = 0L,
    val text: String,
    val syllables: List<LyricSyllable> = emptyList(),
    val transliteration: String? = null,
    val transliterationSyllables: List<LyricSyllable> = emptyList(),
) {
    val hasSyllables: Boolean get() = syllables.isNotEmpty()
    val isRtl: Boolean get() = isRtlText(text) || syllables.any { isRtlText(it.text) }
}

sealed interface LyricsResult {
    data class Success(
        val lines: List<LyricLine>,
        val isSynced: Boolean,
        val isWordSynced: Boolean = false,
        val plainLyrics: String? = null,
        val isInstrumental: Boolean = false,
        val source: String? = null,
    ) : LyricsResult {
        val isRtl: Boolean get() = lines.any { it.isRtl } || isRtlText(plainLyrics)
    }

    data object Empty : LyricsResult
    data class Error(val message: String) : LyricsResult
}

@Singleton
class LyricsRepository @Inject constructor(
    private val betterLyricsApi: BetterLyricsApi,
    private val kugouApi: KugouLyricsApi,
    private val lrclibApi: LrclibLyricsApi,
    private val appleMusicApi: AppleMusicLyricsApi,
    private val biniApi: BiniLyricsApi,
    private val simpMusicApi: SimpMusicLyricsApi,
    private val musixmatchApi: MusixmatchLyricsApi,
    private val settingsPreferences: com.lastwave.app.data.local.SettingsPreferences,
    private val downloadedTrackDao: dagger.Lazy<com.lastwave.app.data.local.db.DownloadedTrackDao>,
) {
    private val cache = ConcurrentHashMap<String, LyricsResult>()
    private val isrcCache = object : LinkedHashMap<String, String>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, String>): Boolean = size > 128
    }

    private fun rememberIsrc(videoId: String?, isrc: String?) {
        if (videoId.isNullOrBlank() || isrc.isNullOrBlank()) return
        synchronized(isrcCache) { isrcCache[videoId] = isrc }
    }

    suspend fun getLyrics(
        title: String,
        artist: String,
        album: String? = null,
        durationSeconds: Int? = null,
        forceRefresh: Boolean = false,
        wordByWord: Boolean = true,
        videoId: String? = null,
        isrc: String? = null,
        onPartialResult: suspend (LyricsResult.Success) -> Unit = {},
    ): LyricsResult = withContext(Dispatchers.Default) {
        // User-chosen provider order: tried first, automatic fallback to the
        // rest when it returns nothing. Part of the cache key so switching
        // providers never serves the previous provider's result.
        val preferred = runCatching {
            settingsPreferences.settings.first().lyricsProvider
        }.getOrDefault(com.lastwave.app.data.local.LyricsProvider.AUTO)
        val effectiveVideoId = videoId?.trim()?.takeIf { it.isNotBlank() }
        val cacheKey = "${effectiveVideoId ?: ""}|${artist.trim().lowercase()}|${title.trim().lowercase()}|${album?.trim()?.lowercase()}|$durationSeconds|$wordByWord|${preferred.id}"
        // Bounded: a long session must not grow this map without limit.
        // Eviction is coarse (oldest-first is untracked); a miss re-fetches
        // in milliseconds, so dropping hot entries only costs one fetch.
        if (cache.size > 320) {
            runCatching { cache.keys.take(64).forEach { cache.remove(it) } }
        }
        if (!forceRefresh) {
            cache[cacheKey]?.takeIf {
                !wordByWord || (it is LyricsResult.Success && (it.isWordSynced || it.isInstrumental))
            }?.let { return@withContext it }
        }

        var localLyrics: LyricsResult.Success? = null
        run {
            // 0. LOCAL OFFLINE: Check if this track is downloaded with embedded or saved lyrics.
            // "Artist - Title" and "Title (feat. X)" variants must still hit the
            // same row, otherwise a correct offline copy is skipped and a
            // wrong online hit is served instead.
            val localTrack = runCatching {
                val dao = downloadedTrackDao.get()
                dao.findByTitleAndArtist(title, artist)
                    ?: dao.findByTrackKey("${artist.lowercase()}_${title.lowercase()}")
                    ?: run {
                        val ct = LrclibLyricsApi.cleanTrackTitle(title)
                        val ca = LrclibLyricsApi.cleanArtistName(artist)
                        if (ct != title || ca != artist) dao.findByTitleAndArtist(ct, ca) else null
                    }
            }.getOrNull()
            if (localTrack != null && (localTrack.hasLyrics || !localTrack.plainLyrics.isNullOrBlank() || !localTrack.syncedLyrics.isNullOrBlank() || !localTrack.lrcFilePath.isNullOrBlank())) {
                var synced = localTrack.syncedLyrics
                if (synced.isNullOrBlank() && !localTrack.lrcFilePath.isNullOrBlank()) {
                    val lrcFile = java.io.File(localTrack.lrcFilePath)
                    if (lrcFile.exists() && lrcFile.length() > 0) {
                        synced = runCatching { lrcFile.readText() }.getOrNull()
                    }
                }
                if (!synced.isNullOrBlank()) {
                    val lines = parseLrc(synced)
                    if (lines.isNotEmpty()) {
                        val result = LyricsResult.Success(
                            lines = lines,
                            isSynced = true,
                            isWordSynced = lines.any { it.hasSyllables },
                            plainLyrics = localTrack.plainLyrics,
                            isInstrumental = false,
                            source = "Downloaded Lyrics (LRC)",
                        )
                        if (!wordByWord) {
                            cache[cacheKey] = result
                            return@withContext result
                        }
                        localLyrics = result
                        onPartialResult(result)
                        return@run
                    }
                }
                val plain = localTrack.plainLyrics
                if (!plain.isNullOrBlank()) {
                    val result = LyricsResult.Success(
                        lines = emptyList(),
                        isSynced = false,
                        isWordSynced = false,
                        plainLyrics = plain.trim(),
                        isInstrumental = false,
                        source = "Downloaded Lyrics (Plain)",
                    )
                    if (!wordByWord) {
                        cache[cacheKey] = result
                        return@withContext result
                    }
                    localLyrics = result
                    onPartialResult(result)
                }
            }
        }

        // Resolve the recording once so every name-based provider can
        // match the same cut. Bounded: a slow lookup must not hold the
        // panel empty, and a miss only falls back to fuzzy matching.
        val knownIsrc = isrc?.takeIf { it.isNotBlank() }
            ?: effectiveVideoId?.let { synchronized(isrcCache) { isrcCache[it] } }
        var recordingIsrc: String? = knownIsrc
        var biniHit: BiniHit? = null
        if (recordingIsrc.isNullOrBlank() && wordByWord) {
            biniHit = withTimeoutOrNull(IDENTIFY_TIMEOUT_MS) {
                runCatching {
                    biniApi.identify(
                        forSearchTitle(title),
                        forSearchArtist(artist),
                        durationSeconds,
                        album,
                        null,
                    )
                }.getOrNull()
            }
            biniHit?.isrc?.takeIf { !it.isNullOrBlank() }?.let {
                recordingIsrc = it
                rememberIsrc(effectiveVideoId, it)
            }
        }

        // LRCLIB chosen explicitly: try it before the word providers so
        // the preference is honored. Word-sync found later still wins;
        // its line-sync result outranks every other fallback below.
        var lrclibAttempted = false
        var preferredFallback: LyricsResult.Success? = null
        if (preferred == com.lastwave.app.data.local.LyricsProvider.LRCLIB) {
            lrclibAttempted = true
            val first = fetchFromLrclib(title, artist, album, durationSeconds)
            if (first != null) {
                if (first.isWordSynced || first.isInstrumental) {
                    cache[cacheKey] = first
                    return@withContext first
                }
                preferredFallback = first
                onPartialResult(first)
            }
        }

        if (wordByWord) {
            // Preferred provider goes first with a bounded head start. Only
            // word-sync (or instrumental) short-circuits; its line-sync
            // result is stashed as the top fallback so a word-synced hit
            // from anywhere else still wins.
            if (preferred.isWordProvider) {
                val single = withTimeoutOrNull(PREFERRED_HEAD_START_MS) {
                    fetchPreferredWord(
                        preferred = preferred,
                        title = title,
                        artist = artist,
                        album = album,
                        durationSeconds = durationSeconds,
                        videoId = effectiveVideoId,
                        isrc = recordingIsrc,
                        biniHit = biniHit,
                    )
                }
                if (single != null) {
                    if (single.isWordSynced || single.isInstrumental) {
                        cache[cacheKey] = single
                        return@withContext single
                    }
                    preferredFallback = single
                    onPartialResult(single)
                }
            }
            // Bounded end-to-end: one slow-drip provider must never hold the
            // panel hostage. Whatever validated fallback exists at the
            // deadline still flows through the normal fallback chain below.
            var lineFallback: LyricsResult.Success? = null
            val wordResult = withTimeoutOrNull(RACE_TOTAL_MS) {
                coroutineScope {
                    val requests = mutableListOf(
                    async<LyricsResult.Success?> {
                        fetchWordFromAppleMusic(title, artist, album, durationSeconds)
                    },
                    async<LyricsResult.Success?> {
                        fetchWordFromBini(title, artist, album, durationSeconds, recordingIsrc, biniHit, effectiveVideoId)
                    },
                    async<LyricsResult.Success?> {
                        fetchWordFromBetterLyrics(title, artist, album, durationSeconds)
                    },
                    async<LyricsResult.Success?> {
                        fetchWordFromKugou(title, artist, durationSeconds)
                    },
                    async<LyricsResult.Success?> {
                        fetchWordFromSimpMusic(effectiveVideoId, durationSeconds)
                    },
                )
                try {
                    while (requests.isNotEmpty()) {
                        val (request, result) = select {
                            requests.forEach { request ->
                                request.onAwait { request to it }
                            }
                        }
                        requests.remove(request)
                        if (result?.isWordSynced == true) {
                            // Fastest word-sync wins — but only if its
                            // timeline plausibly fits this recording. A
                            // wrong-cut hit keeps racing as a line fallback
                            // instead of locking in broken sync.
                            if (result.isInstrumental || plausibleDuration(result.lines, durationSeconds)) {
                                return@coroutineScope result
                            }
                        }
                        // Line-sync fallback must also fit the recording: an
                        // unchecked wrong-cut timeline is exactly how plain
                        // line-by-line sync breaks.
                        if (result != null && lineFallback == null &&
                            (result.isInstrumental || plausibleDuration(result.lines, durationSeconds))
                        ) {
                            lineFallback = result
                            onPartialResult(result)
                        }
                    }
                    lineFallback
                } finally {
                    requests.forEach { it.cancel() }
                }
            }
            }
            if (wordResult?.isWordSynced == true) {
                cache[cacheKey] = wordResult
                return@withContext wordResult
            }
            // Explicit choice outranks any other line-sync source.
            preferredFallback?.let {
                cache[cacheKey] = it
                return@withContext it
            }
            // Full completion returns the fallback through wordResult; a
            // timed-out race leaves it in the outer var — either way the
            // validated partial still counts instead of dropping to Empty.
            (wordResult ?: lineFallback)?.let { settled ->
                cache[cacheKey] = settled
                return@withContext settled
            }
            // Extra line-sync catalogue: biggest database, tried after the
            // word race so a timed hit from anywhere above still wins.
            fetchWordFromMusixmatch(title, artist, durationSeconds)?.let { mxm ->
                cache[cacheKey] = mxm
                return@withContext mxm
            }
        }
        localLyrics?.let {
            if (it.isWordSynced || !wordByWord) return@withContext it
            // A local line-sync copy is kept as the floor: online line-sync
            // below may still beat it, otherwise it is returned at the end.
        }

        // Fall back to LRCLIB line-by-line sync (skipped when it was
        // already tried as the preferred provider above).
        if (!lrclibAttempted) {
            fetchFromLrclib(title, artist, album, durationSeconds)?.let { result ->
                // A local synced copy outranks a remote plain-only hit:
                // keep scrolling lines over static text.
                if (result.isSynced && localLyrics != null) {
                    cache[cacheKey] = result
                    return@withContext result
                }
                if (!result.isSynced && localLyrics?.isSynced == true) {
                    // fall through to localLyrics below
                } else {
                    cache[cacheKey] = result
                    return@withContext result
                }
            }
        }
        localLyrics?.let {
            cache[cacheKey] = it
            return@withContext it
        }
        preferredFallback?.let {
            cache[cacheKey] = it
            return@withContext it
        }

        // 4. FALLBACK: If all fail, return Empty (no lyrics)
        LyricsResult.Empty
    }

    /** LRCLIB attempt shared by the preferred-first path and the fallback
     *  below. Returns null when LRCLIB has nothing usable. */
    private suspend fun fetchFromLrclib(
        title: String,
        artist: String,
        album: String?,
        durationSeconds: Int?,
    ): LyricsResult.Success? {
        val lrclibRecord = try {
            lrclibApi.fetchLyrics(title, artist, album, durationSeconds)
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        } ?: return null

        if (lrclibRecord.instrumental == true) {
            return LyricsResult.Success(
                lines = emptyList(),
                isSynced = false,
                isWordSynced = false,
                plainLyrics = null,
                isInstrumental = true,
                source = "LRCLIB (Instrumental)",
            )
        }

        val synced = lrclibRecord.syncedLyrics
        if (!synced.isNullOrBlank()) {
            val lines = parseLrc(synced)
            if (lines.isNotEmpty()) {
                val wordSynced = lines.any { it.hasSyllables }
                return LyricsResult.Success(
                    lines = lines,
                    isSynced = true,
                    isWordSynced = wordSynced,
                    plainLyrics = lrclibRecord.plainLyrics,
                    isInstrumental = false,
                    source = if (wordSynced) "LRCLIB (Word-Sync)" else "LRCLIB (Line-Sync)",
                )
            }
        }

        val plain = lrclibRecord.plainLyrics
        if (!plain.isNullOrBlank()) {
            return LyricsResult.Success(
                lines = emptyList(),
                isSynced = false,
                isWordSynced = false,
                plainLyrics = plain.trim(),
                isInstrumental = false,
                source = "LRCLIB (Plain)",
            )
        }
        return null
    }

    /** Single preferred word-provider attempt (null = fall through to the
     *  automatic race). Same mapping as the race entries below. */
    private suspend fun fetchPreferredWord(
        preferred: com.lastwave.app.data.local.LyricsProvider,
        title: String,
        artist: String,
        album: String?,
        durationSeconds: Int?,
        videoId: String?,
        isrc: String?,
        biniHit: BiniHit?,
    ): LyricsResult.Success? = when (preferred) {
        com.lastwave.app.data.local.LyricsProvider.APPLE_MUSIC ->
            fetchWordFromAppleMusic(title, artist, album, durationSeconds)
        com.lastwave.app.data.local.LyricsProvider.BETTER_LYRICS ->
            fetchWordFromBetterLyrics(title, artist, album, durationSeconds)
        com.lastwave.app.data.local.LyricsProvider.KUGOU ->
            fetchWordFromKugou(title, artist, durationSeconds)
        com.lastwave.app.data.local.LyricsProvider.SIMP_MUSIC ->
            fetchWordFromSimpMusic(videoId, durationSeconds)
        com.lastwave.app.data.local.LyricsProvider.MUSIXMATCH ->
            fetchWordFromMusixmatch(title, artist, durationSeconds)
        com.lastwave.app.data.local.LyricsProvider.BINI_LYRICS ->
            fetchWordFromBini(title, artist, album, durationSeconds, isrc, biniHit, videoId)
        else -> null
    }

    private suspend fun fetchWordFromAppleMusic(
        title: String,
        artist: String,
        album: String?,
        durationSeconds: Int?,
    ): LyricsResult.Success? {
        return try {
            appleMusicApi.fetchLyrics(title, artist, album, durationSeconds)
                // Like every other fuzzy provider: a same-title wrong-cut
                // timeline must not win the race on speed alone.
                ?.takeIf { it.isInstrumental || plausibleDuration(it.lines, durationSeconds) }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchWordFromBini(
        title: String,
        artist: String,
        album: String?,
        durationSeconds: Int?,
        isrc: String?,
        hit: BiniHit?,
        videoId: String?,
    ): LyricsResult.Success? {
        try {
            val resolvedHit = hit ?: runCatching {
                biniApi.identify(forSearchTitle(title), forSearchArtist(artist), durationSeconds, album, isrc)
            }.getOrNull() ?: return null
            val (foundIsrc, lines) = biniApi.fetchLinesFor(resolvedHit) ?: return null
            rememberIsrc(videoId, foundIsrc ?: resolvedHit.isrc)
            if (lines.isEmpty()) return null
            if (!plausibleDuration(lines, durationSeconds)) return null
            val hasWordTiming = lines.any { it.hasSyllables }
            return LyricsResult.Success(
                lines = lines,
                isSynced = true,
                isWordSynced = hasWordTiming,
                plainLyrics = lines.joinToString("\n") { it.text },
                isInstrumental = false,
                source = if (hasWordTiming) "Syllable-Sync (Word-Sync)" else "Syllable-Sync (Line-Sync)",
            )
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (_: Exception) {
        }
        return null
    }

    private suspend fun fetchWordFromSimpMusic(
        videoId: String?,
        durationSeconds: Int?,
    ): LyricsResult.Success? {
        try {
            val lines = simpMusicApi.fetchLyrics(videoId, durationSeconds)
            if (!lines.isNullOrEmpty()) {
                if (!plausibleDuration(lines, durationSeconds)) return null
                val hasWordTiming = lines.any { it.hasSyllables }
                return LyricsResult.Success(
                    lines = lines,
                    isSynced = true,
                    isWordSynced = hasWordTiming,
                    plainLyrics = lines.joinToString("\n") { it.text },
                    isInstrumental = false,
                    source = if (hasWordTiming) "Video-Match (Word-Sync)" else "Video-Match (Line-Sync)",
                )
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (_: Exception) {
        }
        return null
    }

    private suspend fun fetchWordFromMusixmatch(
        title: String,
        artist: String,
        durationSeconds: Int?,
    ): LyricsResult.Success? {
        try {
            val lines = musixmatchApi.fetchLyrics(title, artist, durationSeconds)
            if (!lines.isNullOrEmpty()) {
                // Server fuzzy-matches with no usable candidate identity:
                // reject wrong-cut timelines before they poison line-sync.
                if (!plausibleDuration(lines, durationSeconds)) return null
                return LyricsResult.Success(
                    lines = lines,
                    isSynced = true,
                    isWordSynced = lines.any { it.hasSyllables },
                    plainLyrics = lines.joinToString("\n") { it.text },
                    isInstrumental = false,
                    source = "Catalog (Line-Sync)",
                )
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (_: Exception) {
        }
        return null
    }

    private suspend fun fetchWordFromBetterLyrics(
        title: String,
        artist: String,
        album: String? = null,
        durationSeconds: Int? = null,
    ): LyricsResult.Success? {
        try {
            val betterLines = betterLyricsApi.fetchWordLyrics(
                forSearchTitle(title),
                forSearchArtist(artist),
                durationSeconds,
                album,
            ) ?: betterLyricsApi.fetchWordLyrics(title, artist, durationSeconds, album)
            if (!betterLines.isNullOrEmpty()) {
                // TTML carries no candidate identity: duration plausibility only.
                if (!plausibleDuration(betterLines, durationSeconds)) return null
                val hasWordTiming = betterLines.any { it.hasSyllables }
                return LyricsResult.Success(
                    lines = betterLines,
                    isSynced = true,
                    isWordSynced = hasWordTiming,
                    plainLyrics = betterLines.joinToString("\n") { it.text },
                    isInstrumental = false,
                    source = if (hasWordTiming) "BetterLyrics (Word-Sync)" else "BetterLyrics (Line-Sync)",
                )
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (_: Exception) {
        }
        return null
    }

    private suspend fun fetchWordFromKugou(
        title: String,
        artist: String,
        durationSeconds: Int?,
    ): LyricsResult.Success? {
        try {
            val kugouLines = kugouApi.fetchWordLyrics(title, artist, durationSeconds)
            if (!kugouLines.isNullOrEmpty()) {
                if (!plausibleDuration(kugouLines, durationSeconds)) return null
                val hasWordTiming = kugouLines.any { it.hasSyllables }
                return LyricsResult.Success(
                    lines = kugouLines,
                    isSynced = true,
                    isWordSynced = hasWordTiming,
                    plainLyrics = kugouLines.joinToString("\n") { it.text },
                    isInstrumental = false,
                    source = "Kugou KRC (Word-Sync)",
                )
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (_: Exception) {
        }
        return null
    }

    companion object {
        /**
         * Edge-to-edge timing normalization: overlapping rows are the reason
         * two lines light up at once around a boundary or a highlight jumps.
         * Only shrinks overlaps — exact data passes through untouched:
         * exact-duplicate stamps collapse, a line ending past the next
         * line's start is pulled back to it, and every syllable is capped
         * at the next syllable's start and its own line end. Idempotent.
         */
        fun normalizeLyricTiming(lines: List<LyricLine>): List<LyricLine> {
            if (lines.isEmpty()) return lines
            val sorted = lines.sortedBy { it.timeMs }
            // Exact duplicates (same stamp, same words) keep the first copy.
            val deduped = mutableListOf<LyricLine>()
            sorted.forEach { line ->
                val prev = deduped.lastOrNull()
                if (prev != null && prev.timeMs == line.timeMs &&
                    prev.text.trim() == line.text.trim() &&
                    prev.syllables.size == line.syllables.size
                ) {
                    return@forEach
                }
                deduped += line
            }
            return deduped.mapIndexed { index, line ->
                val nextStart = deduped.getOrNull(index + 1)?.timeMs
                var duration = line.durationMs
                if (duration > 0 && nextStart != null && nextStart > line.timeMs &&
                    line.timeMs + duration > nextStart
                ) {
                    duration = nextStart - line.timeMs
                }
                val lineEnd = if (duration > 0) line.timeMs + duration else {
                    line.syllables.maxOfOrNull { it.timeMs + it.durationMs }
                        ?: line.transliterationSyllables.maxOfOrNull { it.timeMs + it.durationMs }
                        ?: line.timeMs
                }
                fun clampSyllables(units: List<LyricSyllable>): List<LyricSyllable> {
                    if (units.size < 2 && (units.isEmpty() || units.first().timeMs + units.first().durationMs <= lineEnd)) {
                        return units
                    }
                    val ordered = units.sortedBy { it.timeMs }
                    return ordered.mapIndexed { si, syl ->
                        val nextSylStart = ordered.getOrNull(si + 1)?.timeMs
                        var end = syl.timeMs + syl.durationMs
                        if (nextSylStart != null && nextSylStart > syl.timeMs && end > nextSylStart) {
                            end = nextSylStart
                        }
                        if (lineEnd > syl.timeMs && end > lineEnd) end = lineEnd
                        if (end < syl.timeMs) end = syl.timeMs
                        if (end - syl.timeMs != syl.durationMs) syl.copy(durationMs = end - syl.timeMs) else syl
                    }
                }
                val syllables = clampSyllables(line.syllables)
                val translit = clampSyllables(line.transliterationSyllables)
                if (duration != line.durationMs || syllables !== line.syllables || translit !== line.transliterationSyllables) {
                    line.copy(durationMs = duration, syllables = syllables, transliterationSyllables = translit)
                } else line
            }
        }

        /**
         * Duration plausibility for providers whose responses carry no
         * candidate identity (BetterLyrics): the server
         * fuzzy-matches, so a same-title wrong-artist hit is otherwise
         * undetectable client-side. The lyric timeline must roughly fit the
         * track: reject timelines running 45s+ past the end, or covering
         * under half of a track while missing 90s+ (wrong shorter song).
         * Lenient by design — outros/skits legitimately end early.
         */
        fun plausibleDuration(lines: List<LyricLine>, durationSeconds: Int?): Boolean {
            if (durationSeconds == null || durationSeconds <= 0) return true
            if (lines.size < 3) return true
            val expectedMs = durationSeconds * 1000L
            if (expectedMs < 60_000L) return true
            val endMs = lines.maxOfOrNull { line ->
                val sylEnd = line.syllables.maxOfOrNull { it.timeMs + it.durationMs } ?: 0L
                maxOf(line.timeMs + line.durationMs, sylEnd, line.timeMs)
            } ?: return true
            if (endMs <= 0L) return true
            if (endMs > expectedMs + 45_000L) return false
            if (endMs < expectedMs * 0.5 && expectedMs - endMs > 90_000L) return false
            return true
        }

        private val TIMESTAMP_REGEX = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{2,3}))?\]""")
        private val WORD_STAMP_REGEX = Regex("""<(\d{1,3}):(\d{2})[.:](\d{2,3})>""")
        private val OFFSET_REGEX = Regex("""\[offset:\s*([+-]?\d+)\s*\]""", RegexOption.IGNORE_CASE)
        /** Head start for the preferred provider before the automatic race
         *  takes over: bounds hangs, typical hits resolve well inside it. */
        private const val PREFERRED_HEAD_START_MS = 4_000L
        /** Hard ceiling for the whole provider race: slower than any single
         *  healthy round-trip, faster than socket worst cases. A slow-drip
         *  provider can delay the final fallback, never deny it. */
        private const val RACE_TOTAL_MS = 12_000L
        private const val IDENTIFY_TIMEOUT_MS = 2_500L

        private val SEARCH_WHITESPACE = Regex("""\s+""")
        private val SEARCH_CREDITS = listOf(
            Regex("""\s*[(\[]\s*(feat|ft|featuring|with)\b[^)\]]*[)\]]""", RegexOption.IGNORE_CASE),
            Regex("""\s+(feat|ft|featuring)\.?\s+.*$""", RegexOption.IGNORE_CASE),
            Regex(
                """\s*[(\[]\s*(official\s*)?(music\s*)?""" +
                    """(video|audio|visuali[sz]er|lyrics?\s*video|lyrics?|m/?v|hd|hq|4k|full\s*song)""" +
                    """\s*[)\]]""",
                RegexOption.IGNORE_CASE,
            ),
            Regex("""\s*[(\[]\s*official\s*[)\]]""", RegexOption.IGNORE_CASE),
        )

        fun forSearchTitle(raw: String): String {
            var name = raw
            SEARCH_CREDITS.forEach { pattern -> name = pattern.replace(name, " ") }
            return name.replace(SEARCH_WHITESPACE, " ").trim().trimEnd(',', '-', '–', '—').trim()
                .ifBlank { raw.trim() }
        }

        fun forSearchArtist(raw: String): String =
            raw.removeSuffix(" - Topic").trim().ifBlank { raw.trim() }

        fun decodeEntities(raw: String): String {
            if ('&' !in raw) return raw
            var out = raw
            out = out.replace(Regex("&#x([0-9a-fA-F]+);")) {
                runCatching { it.groupValues[1].toInt(16).toChar().toString() }.getOrDefault(it.value)
            }
            out = out.replace(Regex("&#(\\d+);")) {
                runCatching { it.groupValues[1].toInt().toChar().toString() }.getOrDefault(it.value)
            }
            out = out.replace("&nbsp;", " ")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
            return out
        }

        private fun stampToMs(minutes: Long, seconds: Long, fractionStr: String): Long {
            val fractionMs = when (fractionStr.length) {
                2 -> (fractionStr.toLongOrNull() ?: 0L) * 10
                3 -> fractionStr.toLongOrNull() ?: 0L
                1 -> (fractionStr.toLongOrNull() ?: 0L) * 100
                else -> 0L
            }
            return (minutes * 60 * 1000) + (seconds * 1000) + fractionMs
        }

        private fun parseWordRuns(body: String, lineStartMs: Long, lineEndMs: Long?): List<LyricSyllable> {
            val marks = WORD_STAMP_REGEX.findAll(body).toList()
            if (marks.isEmpty()) return emptyList()
            data class Run(val startMs: Long, val text: String)
            val runs = marks.mapIndexed { index, mark ->
                val until = marks.getOrNull(index + 1)?.range?.first ?: body.length
                val startMs = stampToMs(
                    mark.groupValues[1].toLongOrNull() ?: 0L,
                    mark.groupValues[2].toLongOrNull() ?: 0L,
                    mark.groupValues[3],
                )
                Run(startMs, body.substring(mark.range.last + 1, until))
            }
            val words = mutableListOf<LyricSyllable>()
            runs.forEachIndexed { index, run ->
                val text = decodeEntities(run.text).trim()
                if (text.isBlank()) return@forEachIndexed
                val endMs = runs.getOrNull(index + 1)?.startMs
                    ?: lineEndMs
                    ?: (run.startMs + 800L)
                words += LyricSyllable(
                    timeMs = run.startMs.coerceAtLeast(0L),
                    durationMs = (endMs - run.startMs).coerceAtLeast(0L),
                    text = text,
                )
            }
            return words
        }

        fun parseLrc(lrcContent: String): List<LyricLine> {
            data class Raw(val timeMs: Long, val body: String)
            val raws = mutableListOf<Raw>()
            var offsetMs = 0L

            for (line in lrcContent.lines()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue

                val offsetMatch = OFFSET_REGEX.find(trimmed)
                if (offsetMatch != null) {
                    offsetMs = offsetMatch.groupValues[1].toLongOrNull() ?: 0L
                    continue
                }

                val matches = TIMESTAMP_REGEX.findAll(trimmed).toList()
                if (matches.isEmpty()) continue

                val body = trimmed.replace(TIMESTAMP_REGEX, "")
                for (match in matches) {
                    val totalMs = stampToMs(
                        match.groupValues[1].toLongOrNull() ?: 0L,
                        match.groupValues[2].toLongOrNull() ?: 0L,
                        match.groupValues.getOrNull(3).orEmpty(),
                    ) + offsetMs
                    raws += Raw(totalMs.coerceAtLeast(0L), body)
                }
            }
            raws.sortBy { it.timeMs }

            val result = mutableListOf<LyricLine>()
            raws.forEachIndexed { index, raw ->
                val nextStart = raws.getOrNull(index + 1)?.timeMs
                val cleanText = decodeEntities(raw.body.replace(WORD_STAMP_REGEX, "").trim())
                val words = parseWordRuns(raw.body, raw.timeMs, nextStart)
                if (words.isNotEmpty()) {
                    val lineStart = minOf(raw.timeMs, words.first().timeMs)
                    val joined = words.joinToString(" ") { it.text }
                    // Keep the author spacing when the plain body carries
                    // punctuation the word join would rewrite.
                    val text = if (cleanText.isNotBlank() && cleanText.length >= joined.length) {
                        cleanText
                    } else joined
                    result += LyricLine(
                        timeMs = lineStart,
                        durationMs = ((nextStart ?: (words.maxOf { it.timeMs + it.durationMs })) - lineStart).coerceAtLeast(0L),
                        text = text,
                        syllables = words,
                    )
                } else {
                    result += LyricLine(
                        timeMs = raw.timeMs,
                        durationMs = if (nextStart != null && nextStart > raw.timeMs) nextStart - raw.timeMs else 0L,
                        text = cleanText,
                        syllables = emptyList(),
                    )
                }
            }

            return result.sortedBy { it.timeMs }
        }

        fun parseEnhancedLrc(lrcContent: String): List<LyricLine> {
            val parsed = parseLrc(lrcContent)
            return parsed.takeIf { lines -> lines.any { it.hasSyllables } } ?: emptyList()
        }
    }
}
