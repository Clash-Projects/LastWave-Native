package com.lastwave.app.data.playlist

import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.YouTubeMusicTrack
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvPlaylistImporterTest {
    private val api = mockk<InnerTubeMusicApi>()
    private val importer = CsvPlaylistImporter(api)

    private fun track(
        id: String,
        title: String,
        artist: String,
        album: String? = null,
    ) = YouTubeMusicTrack(id, title, artist, album)

    @Test
    fun rejectsDifferentArtistsVersionsAndMissingMetadata() {
        val source = CsvRawTrack("Song", "Artist")
        val exact = track("abcdefghijk", "Song", "Artist")
        assertTrue(importer.isExactMatch(source, exact))
        listOf("Song Live", "Song (Remastered)", "Song Remix", "Song Part 2").forEach {
            assertFalse(importer.isExactMatch(source, exact.copy(title = it)))
        }
        assertFalse(importer.isExactMatch(source, exact.copy(artist = "Artist Tribute")))
        assertFalse(importer.isExactMatch(source.copy(artist = ""), exact))
        assertFalse(importer.isExactMatch(source.copy(album = "Original"), exact.copy(album = "Compilation")))
        assertFalse(importer.isExactMatch(source.copy(title = "Song (Live)"), exact))
    }

    @Test
    fun preservesNonLatinIdentity() {
        val source = CsvRawTrack("तुम ही हो", "अरिजीत सिंह")
        val target = track("abcdefghijk", source.title, source.artist)
        assertTrue(importer.isExactMatch(source, target))
        assertFalse(importer.isExactMatch(source, target.copy(title = "केसरिया")))
    }

    @Test
    fun readsQuotedMultilineCsvWithoutLosingQuotes() {
        val rows = importer.parseTracks("Track,Artist\n\"Song, \"\"One\"\"\nLive\",Artist", "songs.csv")
        assertEquals(listOf(CsvRawTrack("Song, \"One\"\nLive", "Artist")), rows)
    }

    @Test
    fun readsSpotifyAndTabSeparatedHeadersWithoutGuessingMissingColumns() {
        val csv = "Track URI;Track Name;Artist URI(s);Artist Name(s);Album Name\nspotify:track:1;Song;spotify:artist:1;Artist;Album"
        assertEquals(CsvRawTrack("Song", "Artist", "Album"), importer.parseTracks(csv, "songs.csv").single())
        assertEquals(CsvRawTrack("Song", "Artist"), importer.parseTracks("Title\tArtist\nSong\tArtist", "songs.tsv").single())
        assertEquals("", importer.parseTracks("Title,Album\nSong,Album", "songs.csv").single().artist)
    }

    @Test
    fun readsTextAndM3uMetadataWithoutStrippingVersions() {
        val text = "Artist, Guest - Song (Live)\nAmbiguous title"
        val rows = importer.parseTracks(text, "songs.txt")
        assertEquals(CsvRawTrack("Song (Live)", "Artist, Guest"), rows.first())
        assertEquals("", rows.last().artist)
        val m3u = "#EXTM3U\n#EXTINF:-1,Artist - Song (Live)\n/path/song.mp3\n#EXTINF:-1,Other - Song 2\n/path/song2.mp3"
        assertEquals(listOf(CsvRawTrack("Song (Live)", "Artist"), CsvRawTrack("Song 2", "Other")), importer.parseTracks(m3u, "songs.m3u"))
        val metadataOnlyM3u = "#EXTM3U\n#EXTINF:-1,Artist - Song\n\n#EXTINF:-1,Other - Song 2\n"
        assertEquals(listOf(CsvRawTrack("Song", "Artist"), CsvRawTrack("Song 2", "Other")), importer.parseTracks(metadataOnlyM3u, "songs.m3u"))
    }

    @Test
    fun skipsUnmatchedRowsAndPinsVerifiedVideoId() = runBlocking {
        coEvery { api.searchSongs(any(), any(), any()) } returns listOf(
            track("abcdefghijk", "Song", "Artist Tribute"),
            track("12345678901", "Song", "Artist"),
        )
        val result = importer.parseAndMatchCsv("Track,Artist\nSong,Artist\nMissing,Artist".byteInputStream(), "songs.csv")
        assertEquals(2, result.totalRows)
        assertEquals(1, result.matchedCount)
        assertEquals("https://music.youtube.com/watch?v=12345678901", result.tracks.single().url)
    }

    /**
     * A single unclosed quote used to throw out of the parser, so one bad row
     * discarded every song in the file. Every row before it must survive.
     */
    @Test
    fun recoversRowsAfterAnUnclosedQuoteInsteadOfFailingTheFile() {
        val rows = importer.parseTracks("Track,Artist\nSong,Artist\nSecond,Other\n\"Unclosed,Artist", "songs.csv")
        assertEquals(
            listOf(
                CsvRawTrack("Song", "Artist"),
                CsvRawTrack("Second", "Other"),
                CsvRawTrack("Unclosed,Artist", ""),
            ),
            rows,
        )
    }

    /**
     * The same quote in the middle of a file swallows every following line, so a
     * whole playlist can come back as one row. A real multi-line quoted field
     * continues with fragments, not with whole rows, and must not be split.
     */
    @Test
    fun splitsRowsCollapsedByAMidFileUnclosedQuote() {
        val collapsed = "Track,Artist\n\"Song,Artist\nSecond,Other\nThird,Artist"
        assertEquals(
            listOf(
                CsvRawTrack("Song", "Artist"),
                CsvRawTrack("Second", "Other"),
                CsvRawTrack("Third", "Artist"),
            ),
            importer.parseTracks(collapsed, "songs.csv"),
        )

        val genuineMultiline = "Track,Artist\n\"Song, \"\"One\"\"\nLive\",Artist\nSecond,Other"
        assertEquals(
            listOf(
                CsvRawTrack("Song, \"One\"\nLive", "Artist"),
                CsvRawTrack("Second", "Other"),
            ),
            importer.parseTracks(genuineMultiline, "songs.csv"),
        )
    }

    /**
     * A comma inside a tab-separated field used to tie with the real delimiter
     * and hand the file to the comma parser, shifting every column.
     */
    @Test
    fun keepsTabDelimiterWhenAFieldContainsAComma() {
        val rows = importer.parseTracks("Title\tArtist\nSong, Pt 2\tArtist", "songs.tsv")
        assertEquals(listOf(CsvRawTrack("Song, Pt 2", "Artist")), rows)
    }

    /**
     * A bare 11-character album or artist name matches YouTube's id shape. The
     * old scan treated any such token as a video id, importing whatever that
     * "id" happened to be under the row's own title.
     */
    @Test
    fun doesNotTreatABareIdShapedTokenAsALink() {
        val rows = importer.parseTracks("Track,Artist,Album\nSong,Artist,abcdefghijk", "songs.csv")
        assertEquals(CsvRawTrack("Song", "Artist", "abcdefghijk"), rows.single())
        assertNull(rows.single().videoId)
    }

    /**
     * A single-column file has no header to find, so treating its first line as
     * one just swallowed a real song.
     */
    @Test
    fun doesNotSwallowTheFirstRowOfASingleColumnFileAsAHeader() {
        assertEquals(
            listOf(CsvRawTrack("Song", ""), CsvRawTrack("Another", "")),
            importer.parseTracks("Song\nAnother", "songs.csv"),
        )
        assertEquals(
            listOf(CsvRawTrack("Track", ""), CsvRawTrack("Song", "")),
            importer.parseTracks("Track\nSong", "songs.csv"),
        )
    }

    /**
     * Link rows are identified by a bare video id rather than a full URL:
     * `youtubeVideoIdOrNull` needs `android.net.Uri` to parse a URL, and under
     * `unitTests.isReturnDefaultValues` that returns null on the JVM, so URL
     * rows cannot be exercised here. A bare id short-circuits before Uri and
     * still covers the verification logic below.
     */
    @Test
    fun dropsALinkThatDoesNotDescribeTheRowSong() = runBlocking {
        coEvery { api.fetchSongDetails("12345678901") } returns track("12345678901", "Someone Else Entirely", "Other Artist")
        coEvery { api.searchSongs(any(), any(), any()) } returns emptyList()

        val result = importer.parseAndMatchCsv(
            "Track,Artist,URL\nSong,Artist,12345678901".byteInputStream(),
            "songs.csv",
        )
        assertEquals(1, result.totalRows)
        assertEquals(0, result.matchedCount)
        assertTrue(result.tracks.isEmpty())
    }

    @Test
    fun acceptsALinkOnlyRowAndUsesTheFetchedTitle() = runBlocking {
        coEvery { api.fetchSongDetails("12345678901") } returns track("12345678901", "Real Song", "Real Artist")
        coEvery { api.searchSongs(any(), any(), any()) } returns emptyList()

        val result = importer.parseAndMatchCsv("URL\n12345678901".byteInputStream(), "songs.csv")
        assertEquals(1, result.matchedCount)
        assertEquals("Real Song", result.tracks.single().name)
        assertEquals("Real Artist", result.tracks.single().artist)
        assertEquals("https://music.youtube.com/watch?v=12345678901", result.tracks.single().url)
    }

    @Test
    fun acceptsALinkWhoseTitleMatchesTheRow() = runBlocking {
        coEvery { api.fetchSongDetails("12345678901") } returns track("12345678901", "Song (Official Audio)", "Artist - Topic")
        coEvery { api.searchSongs(any(), any(), any()) } returns emptyList()

        val result = importer.parseAndMatchCsv(
            "Track,Artist,URL\nSong,Artist,12345678901".byteInputStream(),
            "songs.csv",
        )
        assertEquals(1, result.matchedCount)
        assertEquals("https://music.youtube.com/watch?v=12345678901", result.tracks.single().url)
    }

    /**
     * A near-miss that happens to be ranked first must not stand in for the
     * requested song, however similar it looks.
     */
    @Test
    fun rejectsANearMissEvenWhenSearchRanksItFirst() = runBlocking {
        coEvery { api.fetchSongDetails(any()) } returns null
        coEvery { api.searchSongs(any(), any(), any()) } returns
            listOf(track("12345678901", "Song (Live)", "Artist"))

        val result = importer.parseAndMatchCsv("Track,Artist\nSong,Artist".byteInputStream(), "songs.csv")
        assertEquals(1, result.totalRows)
        assertEquals(0, result.matchedCount)
    }

    /**
     * The old fallback accepted a single fuzzy pick. Now every candidate in the
     * result list is validated, so the right song is found even when the wrong
     * one is ranked above it.
     */
    @Test
    fun findsTheRightSongBelowANearMissInTheSameResults() = runBlocking {
        coEvery { api.fetchSongDetails(any()) } returns null
        coEvery { api.searchSongs(any(), any(), any()) } returns listOf(
            track("aaaaaaaaaaa", "Song (Live)", "Artist"),
            track("bbbbbbbbbbb", "Song", "Artist Tribute"),
            track("12345678901", "Song", "Artist"),
        )
        val result = importer.parseAndMatchCsv("Track,Artist\nSong,Artist".byteInputStream(), "songs.csv")
        assertEquals(1, result.matchedCount)
        assertEquals("https://music.youtube.com/watch?v=12345678901", result.tracks.single().url)
    }

    /**
     * A song the first query buries is still reachable through the later query
     * shapes, instead of being lost with a single search per row.
     */
    @Test
    fun fallsBackToAnotherQueryShapeWhenTheFirstFindsNothing() = runBlocking {
        val requested = mutableListOf<String>()
        coEvery { api.fetchSongDetails(any()) } returns null
        coEvery { api.searchSongs(any(), any(), any()) } answers {
            val query = firstArg<String>()
            requested += query
            if (query.startsWith("Song (Live)")) emptyList() else listOf(track("12345678901", "Song (Live)", "Artist"))
        }
        val result = importer.parseAndMatchCsv("Track,Artist\nSong (Live),Artist".byteInputStream(), "songs.csv")
        assertEquals(1, result.matchedCount)
        assertTrue("expected a widened query, saw $requested", requested.size > 1)
        assertTrue(requested.first().startsWith("Song (Live)"))
    }

    /**
     * A plain list of song names with no artist column used to match nothing at
     * all, because the artist check could never be satisfied.
     */
    @Test
    fun matchesTitleOnlyRowsFromAPlainListOfNames() = runBlocking {
        coEvery { api.fetchSongDetails(any()) } returns null
        coEvery { api.searchSongs(any(), any(), any()) } returns listOf(
            track("12345678901", "Song", "Some Artist"),
            track("abcdefghijk", "Another", "Some Other Artist"),
        )
        val result = importer.parseAndMatchCsv("Song\nAnother".byteInputStream(), "songs.txt")
        assertEquals(2, result.totalRows)
        assertEquals(2, result.matchedCount)
    }

    /**
     * searchSongs folds transport failures into an empty list, so an empty pass
     * can be a timeout rather than "no such song". The row must be retried.
     */
    @Test
    fun retriesEmptySearchesBeforeGivingUpOnARow() = runBlocking {
        var searches = 0
        coEvery { api.fetchSongDetails(any()) } returns null
        coEvery { api.searchSongs(any(), any(), any()) } answers {
            searches++
            if (searches < 5) emptyList() else listOf(track("12345678901", "Song", "Artist"))
        }
        val result = importer.parseAndMatchCsv("Track,Artist\nSong,Artist".byteInputStream(), "songs.csv")
        assertEquals(1, result.matchedCount)
        assertTrue("expected the row to be retried, saw $searches searches", searches >= 5)
    }

    @Test
    fun retriesADeadLinkFetchBeforeFallingBackToSearch() = runBlocking {
        var fetches = 0
        coEvery { api.fetchSongDetails("12345678901") } answers {
            fetches++
            if (fetches == 1) null else track("12345678901", "Song", "Artist")
        }
        coEvery { api.searchSongs(any(), any(), any()) } returns emptyList()
        val result = importer.parseAndMatchCsv(
            "Track,Artist,URL\nSong,Artist,12345678901".byteInputStream(),
            "songs.csv",
        )
        assertEquals(1, result.matchedCount)
        assertTrue("expected the link fetch to be retried, saw $fetches", fetches > 1)
    }

    @Test
    fun collapsesRepeatedRowsAndDuplicateResolutions() = runBlocking {
        coEvery { api.fetchSongDetails(any()) } returns null
        coEvery { api.searchSongs(any(), any(), any()) } returns listOf(track("12345678901", "Song", "Artist"))
        val result = importer.parseAndMatchCsv(
            "Track,Artist\nSong,Artist\nSong,Artist\nsong , ARTIST".byteInputStream(),
            "songs.csv",
        )
        assertEquals(1, result.totalRows)
        assertEquals(1, result.matchedCount)
    }
}
