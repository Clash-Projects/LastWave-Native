package com.lastwave.app.data.playlist

import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.generate.youtubeVideoIdOrNull
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.YouTubeMusicTrack
import java.io.InputStream
import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

private const val TAG = "CsvImport"

data class CsvRawTrack(
    val title: String,
    val artist: String,
    val album: String? = null,
    val videoId: String? = null,
)

data class CsvImportResult(
    val suggestedTitle: String,
    val totalRows: Int,
    val matchedCount: Int,
    val tracks: List<GeneratedTrack>,
)

@Singleton
class CsvPlaylistImporter @Inject constructor(
    private val innerTube: InnerTubeMusicApi,
) {
    suspend fun parseAndMatchCsv(
        inputStream: InputStream,
        filename: String = "Imported Playlist",
    ): CsvImportResult = withContext(Dispatchers.IO) {
        val rawTracks = parseTracks(decodeText(inputStream), filename)
        // Exported playlists repeat rows often. Collapsing them up front keeps
        // the request budget spent on rows that can actually resolve.
        val rows = rawTracks.distinctBy(::rowIdentity)
        val limiter = Semaphore(SEARCH_CONCURRENCY)
        val matched = coroutineScope {
            rows.map { raw ->
                async {
                    limiter.withPermit {
                        try {
                            matchRow(raw)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (error: Exception) {
                            android.util.Log.w(TAG, "match failed for \"${raw.title}\"", error)
                            null
                        }
                    }
                }
            }.awaitAll()
        }.filterNotNull().dedupeByVideoId()

        CsvImportResult(
            suggestedTitle = filename.substringBeforeLast('.').replace(Regex("[_-]+"), " ").trim().ifBlank { "Imported Playlist" },
            totalRows = rows.size,
            matchedCount = matched.size,
            tracks = matched,
        )
    }

    /**
     * Resolves one file row to a playable track, or null when the row cannot be
     * verified. A row is only ever accepted when the title and artist it
     * carries agree with the track that comes back, so a stale link or a fuzzy
     * near-miss drops the row instead of quietly importing the wrong song.
     */
    private suspend fun matchRow(raw: CsvRawTrack): GeneratedTrack? {
        // A link in the file is a claim, not proof. Check that it resolves to
        // the id we asked for and that it actually describes the row's song.
        val linked = raw.videoId?.let { fetchLinkedTrack(it) }
        if (linked != null && linkMatchesRow(raw, linked)) return trackFrom(raw, linked)
        if (linked != null) {
            android.util.Log.w(
                TAG,
                "link for \"${raw.title}\" points at \"${linked.title}\" by ${linked.artist}; resolving by search instead",
            )
        }
        if (raw.title.isBlank()) return null
        return searchMatch(raw)?.let { trackFrom(raw, it) }
    }

    /**
     * Resolves a pasted link, retrying like the search path does.
     * [InnerTubeMusicApi.fetchSongDetails] reports transport failures as null,
     * so one attempt cannot tell a dead link from a dropped connection.
     */
    private suspend fun fetchLinkedTrack(videoId: String): YouTubeMusicTrack? {
        repeat(LINK_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(SEARCH_RETRY_BACKOFF_MS * attempt)
            val details = runCatching { innerTube.fetchSongDetails(videoId) }.getOrNull()
            if (details != null && details.videoId == videoId) return details
        }
        return null
    }

    /**
     * A link is only checked where the row actually makes a claim: blank or
     * placeholder fields cannot falsify anything, so they are skipped. Album is
     * deliberately not enforced, because album names drift far more often
     * between a library export and a single upload than titles do.
     */
    internal fun linkMatchesRow(source: CsvRawTrack, target: YouTubeMusicTrack): Boolean {
        if (!VIDEO_ID.matches(target.videoId)) return false
        // Nothing was claimed beyond the link itself, so there is nothing to
        // contradict: this row asked for exactly this video.
        if (source.title.isBlank()) return true
        if (source.artist.isBlank() || source.artist.isUnknownArtistLabel()) {
            return sameText(source.title, target.title, allowSafeVideoLabel = true)
        }
        if (!sameText(source.title, target.title, allowSafeVideoLabel = true)) return false
        return sameArtist(source.artist, target.artist.removeSuffix(" - Topic"))
    }

    /**
     * Resolves a row by search. [InnerTubeMusicApi.searchSongs] folds transport
     * failures into an empty list, so an empty pass is indistinguishable from a
     * rate limit or a timeout: retry a bounded number of times with a short
     * backoff before giving the row up.
     */
    private suspend fun searchMatch(raw: CsvRawTrack): YouTubeMusicTrack? {
        val cleanArtist = raw.artist.takeUnless { it.isUnknownArtistLabel() }.orEmpty()
        repeat(SEARCH_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(SEARCH_RETRY_BACKOFF_MS * attempt)
            verifiedSearch(raw, cleanArtist)?.let { return it }
        }
        return null
    }

    /**
     * One search pass. Each query variant is walked in full and validated
     * before the next one is issued, so:
     *
     *  - a row that resolves on the first query still costs exactly one search,
     *    and
     *  - a song buried anywhere in the result list is still reachable, because
     *    every candidate is offered to [matchesRow] instead of just the top hit.
     *
     * Nothing here accepts a similarity score. The previous fallback took a
     * single fuzzy pick from [InnerTubeMusicApi.findBestMatchOrNull], which
     * settles for a 60%-similar title, so live cuts, remixes and covers were
     * imported in place of the requested song.
     */
    private suspend fun verifiedSearch(raw: CsvRawTrack, cleanArtist: String): YouTubeMusicTrack? {
        for (query in queryVariants(raw, cleanArtist)) {
            val candidates = innerTube.searchSongs(
                query = query,
                limit = SEARCH_RESULT_LIMIT,
                prefetchStreams = false,
            )
            candidates.firstOrNull { matchesRow(raw, it) }?.let { return it }
        }
        return null
    }

    /**
     * Query shapes in order of precision. Bracketed noise is dropped for the
     * retry shapes only: the match itself is still validated against the row's
     * original title, so this widens what search can surface without widening
     * what counts as correct.
     */
    private fun queryVariants(raw: CsvRawTrack, cleanArtist: String): List<String> {
        if (raw.title.isBlank()) return emptyList()
        val title = raw.title.trim()
        val baseTitle = title.replace(NOISE_SUFFIX, "").trim().ifBlank { title }
        return buildList<String> {
            if (cleanArtist.isNotBlank()) {
                add("$title $cleanArtist")
                add("$baseTitle $cleanArtist")
                add("$cleanArtist $title")
            }
            add(title)
            add(baseTitle)
        }.map(String::trim).distinct()
    }

    private fun matchesRow(raw: CsvRawTrack, target: YouTubeMusicTrack): Boolean {
        if (!VIDEO_ID.matches(target.videoId)) return false
        if (raw.title.isBlank()) return false
        // A row that names an artist is held to title *and* artist. A
        // title-only row (a plain list of song names, which used to match
        // nothing at all) can only be held to its title, but that title must
        // still match exactly rather than approximately.
        if (raw.artist.isBlank()) {
            return sameText(raw.title, target.title, allowSafeVideoLabel = true)
        }
        // Album is cleared: names drift far more often between a library export
        // and a single upload than titles do, and a drifted album is not
        // evidence of a different song.
        return isExactMatch(raw.copy(album = null), target)
    }

    private fun trackFrom(raw: CsvRawTrack, target: YouTubeMusicTrack): GeneratedTrack = GeneratedTrack(
        name = raw.title.ifBlank { target.title },
        artist = raw.artist.takeUnless { it.isUnknownArtistLabel() }.orEmpty().ifBlank { target.artist },
        album = raw.album ?: target.album,
        artworkUrl = target.artworkUrl
            ?: target.videoId.takeIf { VIDEO_ID.matches(it) }?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" },
        url = "https://music.youtube.com/watch?v=${target.videoId}",
    )

    internal fun isExactMatch(source: CsvRawTrack, target: YouTubeMusicTrack): Boolean {
        if (!VIDEO_ID.matches(target.videoId)) return false
        if (source.videoId != null && source.videoId != target.videoId) return false
        if (source.videoId == null && (source.title.isBlank() || source.artist.isBlank())) return false
        if (source.artist.isUnknownArtistLabel()) return false
        if (source.title.isNotBlank() && !sameText(source.title, target.title, allowSafeVideoLabel = true)) return false
        if (source.artist.isNotBlank() && !sameArtist(source.artist, target.artist.removeSuffix(" - Topic"))) return false
        if (!source.album.isNullOrBlank() && !sameText(source.album, target.album.orEmpty())) return false
        return true
    }

    private fun sameText(source: String, target: String, allowSafeVideoLabel: Boolean = false): Boolean {
        val normalized = normalize(source)
        val targetText = if (allowSafeVideoLabel) stripSafeVideoLabel(target) else target
        return normalized.isNotBlank() && normalized == normalize(targetText)
    }

    private fun sameArtist(source: String, target: String): Boolean {
        if (sameText(source, target)) return true
        val primaryTarget = target
            .split(Regex("(?i)\\s*(?:,|&|feat\\.?|ft\\.?|featuring)\\s*"), limit = 2)
            .first()
            .removeSuffix(" - Topic")
            .trim()
        return sameText(source, primaryTarget)
    }

    private fun stripSafeVideoLabel(title: String): String = title
        .replace(
            Regex("(?i)\\s*[\\[(](official\\s*(audio|video)|music\\s*video|lyric\\s*video|audio|video|hd|hq|4k)[\\])]"),
            "",
        )
        .trim()

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
            .replace(Regex("['’]"), "")
            .replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ").trim()

    private fun String.isUnknownArtistLabel(): Boolean =
        isBlank() || normalize(this) in UNKNOWN_ARTIST_LABELS

    private fun rowIdentity(track: CsvRawTrack): String =
        track.videoId?.let { "id:$it" } ?: "text:${normalize(track.title)}|${normalize(track.artist)}"

    private fun List<GeneratedTrack>.dedupeByVideoId(): List<GeneratedTrack> {
        if (size < 2) return this
        val seen = HashSet<String>(size)
        return filter { track ->
            val id = track.youtubeVideoIdOrNull()
            id == null || seen.add(id)
        }
    }

    private fun decodeText(inputStream: InputStream): String {
        val bytes = inputStream.readBytes()
        val charset = when {
            bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> Charsets.UTF_16LE
            bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        return String(bytes, charset).removePrefix("\uFEFF")
    }

    internal fun parseTracks(text: String, filename: String): List<CsvRawTrack> {
        val lines = text.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        if (lines.isEmpty()) return emptyList()
        if (filename.endsWith(".m3u", true) || filename.endsWith(".m3u8", true) || lines.first().equals("#EXTM3U", true)) {
            return parseM3u(text.lineSequence().map(String::trim).toList())
        }
        if (filename.endsWith(".txt", true) && lines.none { '\t' in it } &&
            lines.first().split(',', ';').map(::normalize).all { it in TITLE_HEADERS || it in ARTIST_HEADERS || it in URL_HEADERS }.not()) {
            return lines.map(::parseTextTrack)
        }
        val delimiter = detectDelimiter(text)
        val records = repairCollapsedRows(parseRecords(text, delimiter), delimiter)
        val first = records.firstOrNull() ?: return emptyList()
        val headers = first.map(::normalize)
        val titleIndex = headers.indexOfFirst { it in TITLE_HEADERS }
        val artistIndex = headers.indexOfFirst { it in ARTIST_HEADERS }
        val albumIndex = headers.indexOfFirst { it in ALBUM_HEADERS }
        val urlIndex = headers.indexOfFirst { it in URL_HEADERS }
        // Only a multi-column first row is treated as a header. In a
        // single-column file that guess is indistinguishable from data, and
        // taking it swallows a real song: a list of names starting with
        // "Song", "Name" or "Track" used to lose its first entry.
        val hasHeader = first.size >= 2 &&
            (titleIndex >= 0 || artistIndex >= 0 || albumIndex >= 0 || urlIndex >= 0)
        if (first.size == 1 && !hasHeader) return lines.filterNot { it.startsWith('#') }.map(::parseTextTrack)
        val titleColumn = if (titleIndex >= 0) {
            titleIndex
        } else if (hasHeader) {
            (0 until first.size).firstOrNull { it != artistIndex && it != albumIndex && it != urlIndex } ?: 0
        } else 0
        val artistColumn = if (artistIndex >= 0) {
            artistIndex
        } else if (hasHeader) {
            // No artist header: never guess another column (e.g. the album
            // cell) as the artist. -1 reads as empty via getOrNull.
            -1
        } else 1
        return records.drop(if (hasHeader) 1 else 0).mapNotNull { row ->
            if (hasHeader && row.map(::normalize) == headers) return@mapNotNull null
            val titleCell = row.getOrNull(titleColumn).orEmpty().trim()
            val artist = row.getOrNull(artistColumn).orEmpty().trim()
            val rawVideoId = if (urlIndex >= 0) row.getOrNull(urlIndex)?.trim()?.let(::youtubeId) else null
            // Only a full URL counts as a link when it turns up in some other
            // column. A bare 11-character token is far more likely to be an
            // album or an artist than a video id, and treating it as one
            // silently imports whatever that "id" happens to point at.
            val videoId = rawVideoId ?: row.firstNotNullOfOrNull { cell ->
                val trimmed = cell.trim()
                if (trimmed.startsWith("https://", true) || trimmed.startsWith("http://", true)) {
                    youtubeId(trimmed)
                } else {
                    null
                }
            }
            // A link-only row must not carry its own URL through as the title.
            val title = titleCell.takeUnless { videoId != null && youtubeId(it.trim()) == videoId }
            if (title.isNullOrBlank() && videoId == null) return@mapNotNull null
            CsvRawTrack(title.orEmpty(), artist, row.getOrNull(albumIndex)?.trim()?.takeIf(String::isNotBlank), videoId)
        }
    }

    /**
     * Splits rows that a stray unclosed quote collapsed together.
     *
     * An unclosed quote swallows every following line into a single field, so a
     * 300-song file can come back as one row. The two causes are told apart by
     * the shape of the swallowed lines: a genuinely multi-line quoted field
     * (quoted lyrics, a wrapped title) continues with fragments that are not
     * rows in their own right, while an unclosed quote continues with lines
     * that each carry the same field count as a normal row. Only the second
     * shape is split, so real multi-line fields survive untouched.
     */
    private fun repairCollapsedRows(records: List<List<String>>, delimiter: Char): List<List<String>> {
        val modal = records.groupingBy { it.size }.eachCount().maxByOrNull { it.value }?.key ?: return records
        if (modal < 2) return records
        val repaired = ArrayList<List<String>>(records.size)
        for (record in records) {
            val collapsed = record.indexOfFirst { '\n' in it }
            if (collapsed < 0) {
                repaired += record
                continue
            }
            val lines = record[collapsed].split('\n')
            val swallowed = lines.drop(1)
            val looksLikeUnclosedQuote = swallowed.isNotEmpty() && swallowed.all { line ->
                cellsOf(line, delimiter).size == modal
            }
            if (!looksLikeUnclosedQuote) {
                repaired += record
                continue
            }
            repaired += record.take(collapsed) + cellsOf(lines.first(), delimiter)
            swallowed.forEach { line -> repaired += cellsOf(line, delimiter) }
        }
        return repaired
    }

    private fun cellsOf(line: String, delimiter: Char): List<String> =
        parseRecords(line, delimiter).firstOrNull() ?: listOf(line.trim())

    /**
     * Picks the delimiter that actually splits the file into consistent
     * multi-column rows. Counting delimiters alone is not enough: a comma
     * hiding inside a tab-separated artist field ties with the real delimiter,
     * and the tie used to be handed to the comma parser, shifting every column
     * and importing a differently-named song for every row.
     */
    private fun detectDelimiter(text: String): Char {
        var best = DELIMITER_CANDIDATES.first()
        var bestScore = Int.MIN_VALUE
        for (candidate in DELIMITER_CANDIDATES) {
            val rows = parseRecords(text, candidate).take(DELIMITER_SAMPLE_ROWS)
            if (rows.isEmpty()) continue
            val modal = rows.groupingBy { it.size }.eachCount()
                .filterKeys { it >= 2 }
                .maxByOrNull { it.value }
                ?: continue
            val score = modal.value * DELIMITER_CONSISTENCY_WEIGHT +
                rows.sumOf { (it.size - 1).coerceAtLeast(0) }
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }
        return best
    }

    private fun parseTextTrack(line: String): CsvRawTrack {
        val trimmed = line.trim()
        if (trimmed.isBlank()) return CsvRawTrack("", "")
        val videoId = youtubeId(trimmed)
        if (videoId != null) {
            return CsvRawTrack("", "", videoId = videoId)
        }
        // Strip leading track numbering: "1. ", "01. ", "1) ", "[1] ", "1 - "
        val cleaned = trimmed.replace(Regex("^\\s*(?:\\[?\\d+[.)\\]]|\\d+\\s*[-–—])\\s*"), "").trim()
        if (cleaned.isBlank()) return CsvRawTrack("", "")

        // Check for " by " separator
        val byMatch = Regex("(?i)\\s+by\\s+").find(cleaned)
        if (byMatch != null) {
            val title = cleaned.substring(0, byMatch.range.first).trim()
            val artist = cleaned.substring(byMatch.range.last + 1).trim()
            if (title.isNotBlank()) return CsvRawTrack(title = title, artist = artist)
        }

        val separator = Regex("\\s+[-–—|:]\\s+").find(cleaned)
            ?: return CsvRawTrack(cleaned, "")
        return CsvRawTrack(
            title = cleaned.substring(separator.range.last + 1).trim(),
            artist = cleaned.substring(separator.range.first).trim(),
        )
    }

    private fun parseM3u(lines: List<String>): List<CsvRawTrack> {
        val tracks = mutableListOf<CsvRawTrack>()
        var pending: CsvRawTrack? = null
        for (line in lines) {
            when {
                line.startsWith("#EXTINF:", true) -> {
                    pending?.let { tracks += it }
                    val info = line.substringAfter(':')
                    val artist = Regex("""artist="([^"]+)""", RegexOption.IGNORE_CASE).find(info)?.groupValues?.get(1)
                    val title = Regex("""title="([^"]+)""", RegexOption.IGNORE_CASE).find(info)?.groupValues?.get(1)
                    pending = if (artist != null && title != null) CsvRawTrack(title, artist)
                    else parseTextTrack(info.substringAfter(',', ""))
                }
                line.isBlank() -> Unit
                line.startsWith('#') -> Unit
                else -> {
                    val videoId = youtubeId(line)
                    val track = pending ?: if (videoId != null) CsvRawTrack("", "", videoId = videoId)
                    else parseTextTrack(line.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.'))
                    tracks += track.copy(videoId = videoId)
                    pending = null
                }
            }
        }
        pending?.let { tracks += it }
        return tracks
    }

    private fun youtubeId(value: String): String? {
        if (!VIDEO_ID.matches(value) && !value.startsWith("https://", true) && !value.startsWith("http://", true)) return null
        return GeneratedTrack(name = "", artist = "", artworkUrl = null, url = value).youtubeVideoIdOrNull()
    }

    private fun parseRecords(text: String, delimiter: Char): List<List<String>> {
        val records = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        fun finishField() {
            row += field.toString().trim()
            field.setLength(0)
        }
        fun finishRow() {
            finishField()
            if (row.any(String::isNotBlank)) records += row.toList()
            row.clear()
        }
        while (index < text.length) {
            val char = text[index]
            when {
                char == '"' && quoted && text.getOrNull(index + 1) == '"' -> {
                    field.append('"')
                    index++
                }
                char == '"' && (quoted || field.isBlank()) -> quoted = !quoted
                char == delimiter && !quoted -> finishField()
                (char == '\n' || char == '\r') && !quoted -> {
                    finishRow()
                    if (char == '\r' && text.getOrNull(index + 1) == '\n') index++
                }
                else -> field.append(char)
            }
            index++
        }
        // A hand-edited or truncated file can leave a quote open. Treating end
        // of input as the closing quote keeps every row before it importable
        // instead of discarding the whole playlist.
        if (quoted || field.isNotEmpty() || row.isNotEmpty()) finishRow()
        return records
    }

    private companion object {
        val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

        /** InnerTube rate-limits bursts, so keep fewer rows in flight than a
         *  plain map/awaitAll would and let the retry pass cover the fallout. */
        const val SEARCH_CONCURRENCY = 4
        const val SEARCH_ATTEMPTS = 3
        const val SEARCH_RETRY_BACKOFF_MS = 400L
        const val SEARCH_RESULT_LIMIT = 30
        const val LINK_ATTEMPTS = 3

        /** Trailing "..." noise stripped from retry queries only. */
        val NOISE_SUFFIX = Regex(
            "\\s*[\\[(](?:official\\s*(?:audio|video)|music\\s*video|lyric\\s*video|audio|video|hd|hq|4k|" +
                "remaster(?:ed)?|live|deluxe|explicit)\\b[^\\])]*[\\])]\\s*$",
            RegexOption.IGNORE_CASE,
        )

        val DELIMITER_CANDIDATES = listOf(',', ';', '\t')
        const val DELIMITER_SAMPLE_ROWS = 8
        /** Outweighs the raw delimiter count so a consistent split always beats
         *  a delimiter that merely appears more often. */
        const val DELIMITER_CONSISTENCY_WEIGHT = 1000

        val UNKNOWN_ARTIST_LABELS = setOf("unknown", "unknown artist")

        val TITLE_HEADERS = setOf(
            "track name", "trackname", "title", "song", "name", "track", "song name", "songname",
            "song title", "track title", "video title", "item", "item name", "headline", "music",
        )
        val ARTIST_HEADERS = setOf(
            "artist name s", "artist names", "artist s", "artist", "artists", "artist name",
            "artistname", "track artist", "track artists", "performer", "performers", "author",
            "creator", "singer", "band", "by", "channel", "uploader",
        )
        val ALBUM_HEADERS = setOf(
            "album name", "albumname", "album", "albums", "release", "collection", "record",
        )
        val URL_HEADERS = setOf(
            "url", "uri", "track url", "track uri", "youtube url", "video id", "videoid",
            "link", "track link", "spotify uri", "spotify url", "youtube link", "video url",
        )
    }
}
