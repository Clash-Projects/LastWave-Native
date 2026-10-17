package com.lastwave.app.ui.player.lyricsshare

import com.google.common.truth.Truth.assertThat
import com.lastwave.app.util.FileExportHelper
import io.mockk.mockk
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CacheHygieneTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val helper = FileExportHelper(mockk(relaxed = true))

    @Test
    fun cleanLyricsCardCache_purgesFilesOlderThan24Hours() {
        val cacheDir = tempFolder.newFolder("cache")
        val lyricsDir = File(cacheDir, "shared/lyrics_cards").apply { mkdirs() }

        val oldFile = File(lyricsDir, "lyrics_old.png").apply {
            createNewFile()
            // Set last modified to 25 hours ago
            setLastModified(System.currentTimeMillis() - 25 * 60 * 60 * 1000L)
        }

        val freshFile = File(lyricsDir, "lyrics_fresh.png").apply {
            createNewFile()
            setLastModified(System.currentTimeMillis())
        }

        helper.cleanLyricsCardCache(cacheDir)

        assertThat(oldFile.exists()).isFalse()
        assertThat(freshFile.exists()).isTrue()
    }

    @Test
    fun cleanLyricsCardCache_enforcesTenFileCap_evictingOldestFirst() {
        val cacheDir = tempFolder.newFolder("cache")
        val lyricsDir = File(cacheDir, "shared/lyrics_cards").apply { mkdirs() }

        val now = System.currentTimeMillis()
        val files = (1..15).map { i ->
            File(lyricsDir, "lyrics_$i.png").apply {
                createNewFile()
                setLastModified(now - (16 - i) * 1000L) // file 1 is oldest, 15 is newest
            }
        }

        helper.cleanLyricsCardCache(cacheDir)

        val remaining = lyricsDir.listFiles() ?: emptyArray()
        assertThat(remaining).hasLength(10)

        // Oldest 5 (1..5) should have been deleted
        for (i in 1..5) {
            assertThat(File(lyricsDir, "lyrics_$i.png").exists()).isFalse()
        }
        // Newest 10 (6..15) should remain
        for (i in 6..15) {
            assertThat(File(lyricsDir, "lyrics_$i.png").exists()).isTrue()
        }
    }

    @Test
    fun sanitizeFilename_stripsSpecialCharactersAndEnforcesFallback() {
        assertThat(helper.sanitizeFilename("Song / Title: With * Illegal? <Chars> |")).isEqualTo("Song _ Title_ With _ Illegal_ _Chars_ _")
        assertThat(helper.sanitizeFilename("")).isEqualTo("playlist")
        assertThat(helper.sanitizeFilename("   ")).isEqualTo("playlist")
        assertThat(helper.sanitizeFilename("Clean Song Title")).isEqualTo("Clean Song Title")
    }
}
