package com.lastwave.app.data.lyrics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LyricsSanitizerTest {

    @Test
    fun suppressesCreditAndMetadataRows() {
        listOf(
            "Written by: John Doe",
            "Lyrics provided by Musixmatch",
            "Synced by someone",
            "[ar: Someone]",
            "Composer: Someone",
            "作词 : 某人",
            "纯音乐，请欣赏",
            "[Chorus]",
            "(Verse 2: Artist)",
            "Genius",
            "© 2024 Some Label",
        ).forEach { assertThat(LyricsSanitizer.shouldSuppress(it)).isTrue() }
    }

    @Test
    fun keepsSungLines() {
        listOf(
            "I wrote this song for you",
            "Written in the stars",
            "You're a genius, baby",
            "Music is my life",
            "(ooh, yeah)",
            "♪",
            "",
        ).forEach { assertThat(LyricsSanitizer.shouldSuppress(it)).isFalse() }
    }

    @Test
    fun sanitize_dropsCreditsWithoutTouchingTiming() {
        val lines = listOf(
            LyricLine(timeMs = 0L, durationMs = 1000L, text = "Lyrics provided by Someone"),
            LyricLine(timeMs = 1000L, durationMs = 2000L, text = "Hello there"),
        )
        val out = LyricsSanitizer.sanitize(lines)
        assertThat(out).containsExactly(lines[1])
    }

    @Test
    fun sanitize_turnsSpeakerLabelsIntoRoles() {
        val out = LyricsSanitizer.sanitize(
            listOf(
                LyricLine(timeMs = 0L, text = "Singer 1: Hello there"),
                LyricLine(timeMs = 1000L, text = "Singer 2: Hi back"),
                LyricLine(timeMs = 2000L, text = "Both: Together now"),
                LyricLine(timeMs = 3000L, text = "and on"),
            ),
        )
        assertThat(out.map { it.text }).containsExactly("Hello there", "Hi back", "Together now", "and on").inOrder()
        assertThat(out.map { it.role }).containsExactly(
            VocalRole.LEAD_LEFT,
            VocalRole.DUET_RIGHT,
            VocalRole.UNISON_CENTER,
            VocalRole.UNISON_CENTER,
        ).inOrder()
    }

    @Test
    fun sanitize_stripsLabelSyllablesKeepingWordTiming() {
        val line = LyricLine(
            timeMs = 0L,
            durationMs = 2000L,
            text = "Singer 2: Hi you",
            syllables = listOf(
                LyricSyllable(0L, 100L, "Singer"),
                LyricSyllable(100L, 100L, "2:"),
                LyricSyllable(500L, 400L, "Hi"),
                LyricSyllable(900L, 400L, "you"),
            ),
        )
        val out = LyricsSanitizer.sanitize(listOf(line)).single()
        assertThat(out.text).isEqualTo("Hi you")
        assertThat(out.role).isEqualTo(VocalRole.DUET_RIGHT)
        assertThat(out.syllables).containsExactly(line.syllables[2], line.syllables[3]).inOrder()
    }

    @Test
    fun sanitize_leavesSingleColonWordsAlone() {
        val lines = listOf(
            LyricLine(timeMs = 0L, text = "Baby: come home"),
            LyricLine(timeMs = 1000L, text = "Baby: stay close"),
            LyricLine(timeMs = 2000L, text = "Baby: hold on"),
        )
        assertThat(LyricsSanitizer.sanitize(lines)).isEqualTo(lines)
    }

    @Test
    fun safeLineText_collapsesWhitespaceAndRejectsLeakage() {
        assertThat(LyricsSanitizer.safeLineText("Hello,   world", listOf("Hello", "world"))).isEqualTo("Hello, world")
        assertThat(LyricsSanitizer.safeLineText("Hello world (ooh)", listOf("Hello", "world"))).isEqualTo("Hello world")
        assertThat(LyricsSanitizer.safeLineText(null, listOf("Hello", "world"))).isEqualTo("Hello world")
    }

    @Test
    fun sanitizePlain_rendersUntimedHtmlParagraphsAsLyrics() {
        val html = "<div><p>लोगों को लगता है सुलझा हूँ</p><p>कितने सवालों में उलझा हूँ</p></div>" +
            "<div><p>मिलनी वो मंज़िल तो बाकी है</p><p>रस्ते पे हूँ, ये ही काफी है</p></div>"
        assertThat(LyricsSanitizer.sanitizePlain(html)).isEqualTo(
            "लोगों को लगता है सुलझा हूँ\nकितने सवालों में उलझा हूँ\n\n" +
                "मिलनी वो मंज़िल तो बाकी है\nरस्ते पे हूँ, ये ही काफी है",
        )
    }

    @Test
    fun sanitizePlain_decodesHtmlEntitiesAndLineBreaks() {
        val html = "&lt;p&gt;You &amp;amp; I&lt;br/&gt;&lt;em&gt;Stay&lt;/em&gt;&lt;/p&gt;"
        assertThat(LyricsSanitizer.sanitizePlain(html)).isEqualTo("You & I\nStay")
    }

    @Test
    fun sanitizePlain_keepsOrdinaryLyricsExactlyAsAuthored() {
        val plain = "  You <3 me &amp; I > you\r\n\r\n[00:12.34] Stay  close  "
        assertThat(LyricsSanitizer.sanitizePlain(plain)).isEqualTo(plain)
    }

    @Test
    fun sanitizePlain_stillSuppressesCreditsInsideMarkup() {
        assertThat(LyricsSanitizer.sanitizePlain("<p>Lyrics provided by Musixmatch</p><p>Hello</p>"))
            .isEqualTo("Hello")
    }
}
