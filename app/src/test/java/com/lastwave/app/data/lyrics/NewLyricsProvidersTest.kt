package com.lastwave.app.data.lyrics

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import org.junit.Test

class NewLyricsProvidersTest {

    private val client = OkHttpClient()

    // --- LyricsPlus ---

    @Test
    fun lyricsPlus_mergesSyllablesIntoWords() {
        val api = LyricsPlusLyricsApi(client)
        val words = api.mergeSyllables(
            listOf(
                LyricsPlusSyllable(time = 0, duration = 500, text = "e"),
                LyricsPlusSyllable(time = 500, duration = 500, text = "nough "),
                LyricsPlusSyllable(time = 1000, duration = 400, text = "for "),
                LyricsPlusSyllable(time = 1400, duration = 300, text = "you"),
            ),
        )
        assertThat(words.map { it.text }).containsExactly("enough", "for", "you").inOrder()
        assertThat(words[0].timeMs).isEqualTo(0)
        assertThat(words[0].durationMs).isEqualTo(1000)
        assertThat(words[1].timeMs).isEqualTo(1000)
    }

    @Test
    fun lyricsPlus_parsesWordAndLineShapes() {
        val api = LyricsPlusLyricsApi(client)
        val lines = api.parse(
            LyricsPlusResponse(
                lyrics = listOf(
                    LyricsPlusLine(
                        time = 1000,
                        duration = 2000,
                        text = "e nough",
                        syllabus = listOf(
                            LyricsPlusSyllable(time = 1000, duration = 500, text = "e"),
                            LyricsPlusSyllable(time = 1500, duration = 1500, text = "nough"),
                        ),
                    ),
                    LyricsPlusLine(time = 4000, duration = 1000, text = "  Only a line  "),
                    LyricsPlusLine(time = null, text = "no timing"),
                ),
            ),
        )
        assertThat(lines).hasSize(2)
        assertThat(lines[0].text).isEqualTo("enough")
        assertThat(lines[0].syllables).hasSize(1)
        assertThat(lines[0].syllables[0].text).isEqualTo("enough")
        assertThat(lines[1].text).isEqualTo("Only a line")
        assertThat(lines[1].syllables).isEmpty()
    }

    // --- Unison ---

    @Test
    fun unison_parsesLrcAndDropsPlain() {
        val api = UnisonLyricsApi(client)
        val lrc = "[00:01.00]First line\n[00:02.50]Second line"
        val lines = api.parse(lrc, "lrc", "linesync")
        assertThat(lines).isNotNull()
        assertThat(lines!!.map { it.text }).containsExactly("First line", "Second line").inOrder()
        assertThat(lines[1].timeMs).isEqualTo(2500)
        assertThat(api.parse(lrc, "lrc", "plain")).isNull()
    }

    @Test
    fun unison_parsesEnhancedLrcWordStamps() {
        val api = UnisonLyricsApi(client)
        val enhanced = "[00:01.00]<00:01.00>Hel<00:01.50>lo <00:02.00>world"
        val lines = api.parse(enhanced, "lrc", "wordsync")
        assertThat(lines).isNotNull()
        assertThat(lines!![0].hasSyllables).isTrue()
    }

    // --- Megalobiz ---

    @Test
    fun megalobiz_findsDownloadLink() {
        val api = MegalobizLyricsApi(client)
        val results = """<a href="/lrc/maker/download/12345?lrc_id=1&amp;x=2">Download</a>"""
        assertThat(api.lrcPathFromSearch(results))
            .isEqualTo("/lrc/maker/download/12345?lrc_id=1&amp;x=2")
    }

    @Test
    fun megalobiz_extractsAndParsesLrcBody() {
        val api = MegalobizLyricsApi(client)
        val page = """
            <html><body>
            <span id="lrc_123_456_details" class="lrc_details">[00:01.00]Hello<br>[00:02.00]World &amp; more</span>
            </body></html>
        """.trimIndent()
        val lines = api.lrcLinesFromPage(page)
        assertThat(lines).isNotNull()
        assertThat(lines!!.map { it.text }).containsExactly("Hello", "World & more").inOrder()
        assertThat(lines[1].timeMs).isEqualTo(2000)
    }

    // --- Genius ---

    @Test
    fun genius_extractsLyricsAndStripsArtifacts() {
        val api = GeniusLyricsApi(client)
        val html = """
            <html><body>
            <div data-lyrics-container="true">[Verse 1]<br>Roses are red<br>You might also like<br>Violets are blue</div>
            <div data-lyrics-container="true">[Chorus]<br>Sing it back<br></div>
            <div>12Embed</div>
            </body></html>
        """.trimIndent()
        val text = api.parseText(html)
        assertThat(text).isNotNull()
        assertThat(text!!).contains("Roses are red")
        assertThat(text).contains("Violets are blue")
        assertThat(text).doesNotContain("You might also like")
        assertThat(text).contains("[Chorus]")
    }

    @Test
    fun genius_returnsNullWithoutLyricsContainers() {
        val api = GeniusLyricsApi(client)
        assertThat(api.parseText("<html><body><p>nothing here</p></body></html>")).isNull()
    }

    @Test
    fun genius_stripArtifacts_removesTrailingEmbed() {
        val api = GeniusLyricsApi(client)
        assertThat(api.stripArtifacts("Line one\nLine two\n12Embed")).isEqualTo("Line one\nLine two")
    }
}
