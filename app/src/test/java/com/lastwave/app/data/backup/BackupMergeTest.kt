package com.lastwave.app.data.backup

import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.generate.StoredTrack
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test

/** Same cases as LastWave Desktop's backup_sync_test.dart. */
class BackupMergeTest {

    private fun t(name: String, url: String = "") = StoredTrack(name = name, artist = "A", url = url)

    private fun pl(
        id: Long,
        tracks: List<StoredTrack>,
        title: String = "P",
        mode: String = "custom",
        pinned: Boolean = false,
    ) = BackupPlaylistSnapshot(
        id = id,
        title = title,
        subtitle = "",
        mode = mode,
        tracksJson = Json.encodeToString(tracks),
        createdAtMillis = id,
        isPinned = pinned,
    )

    private fun file(
        playlists: List<BackupPlaylistSnapshot>,
        exclusions: List<String> = emptyList(),
        schema: Int = 8,
    ) = BackupFile(
        schemaVersion = schema,
        createdAt = 0,
        appVersion = "test",
        prefs = BackupPrefsSnapshot(),
        playlists = playlists,
        recommendationExclusions = exclusions.map { BackupRecommendationExclusionSnapshot(it, 0) },
    )

    private fun names(p: BackupPlaylistSnapshot) = BackupMerge.tracksOf(p).map { it.name }

    @Test
    fun `first sync keeps everything - file order, then local additions`() {
        val m = BackupMerge.merge(
            base = null,
            local = file(listOf(pl(1, listOf(t("a"), t("c"))), pl(2, listOf(t("x"))))),
            remote = file(listOf(pl(1, listOf(t("a"), t("b"))), pl(3, listOf(t("y"))))),
        )
        assertThat(m.playlists.map { it.id }).containsExactly(1L, 3L, 2L).inOrder()
        assertThat(names(m.playlists.first())).containsExactly("a", "b", "c").inOrder()
    }

    @Test
    fun `a track removed here and one added there both apply`() {
        val m = BackupMerge.merge(
            base = file(listOf(pl(1, listOf(t("a"), t("b"))))),
            local = file(listOf(pl(1, listOf(t("a"))))),
            remote = file(listOf(pl(1, listOf(t("a"), t("b"), t("c"))))),
        )
        assertThat(names(m.playlists.single())).containsExactly("a", "c").inOrder()
    }

    @Test
    fun `a track removed there stays removed`() {
        val m = BackupMerge.merge(
            base = file(listOf(pl(1, listOf(t("a"), t("b"))))),
            local = file(listOf(pl(1, listOf(t("a"), t("b"))))),
            remote = file(listOf(pl(1, listOf(t("a"))))),
        )
        assertThat(names(m.playlists.single())).containsExactly("a")
    }

    @Test
    fun `deleted playlists go, unless the other side edited them`() {
        val base = file(listOf(pl(1, listOf(t("a"))), pl(2, listOf(t("b")))))
        val m = BackupMerge.merge(
            base = base,
            local = file(listOf(pl(2, listOf(t("b"), t("c"))))),
            remote = file(listOf(pl(1, listOf(t("a"))))),
        )
        assertThat(m.playlists.map { it.id }).containsExactly(2L)

        val edited = BackupMerge.merge(
            base = base,
            local = file(listOf(pl(2, listOf(t("b"))))),
            remote = file(listOf(pl(1, listOf(t("a"), t("z"))), pl(2, listOf(t("b"))))),
        )
        assertThat(edited.playlists.map { it.id }).containsExactly(1L, 2L).inOrder()
    }

    @Test
    fun `renames - ours wins when we changed it, theirs otherwise`() {
        val m = BackupMerge.merge(
            base = file(listOf(pl(1, emptyList(), title = "Old"), pl(2, emptyList(), title = "Old"))),
            local = file(listOf(pl(1, emptyList(), title = "Mine"), pl(2, emptyList(), title = "Old", pinned = true))),
            remote = file(listOf(pl(1, emptyList(), title = "Theirs"), pl(2, emptyList(), title = "Theirs"))),
        )
        assertThat(m.playlists[0].title).isEqualTo("Mine")
        assertThat(m.playlists[1].title).isEqualTo("Theirs")
        assertThat(m.playlists[1].isPinned).isTrue()
    }

    @Test
    fun `Liked Songs match by mode whatever their ids`() {
        val m = BackupMerge.merge(
            base = null,
            local = file(listOf(pl(10, listOf(t("a")), mode = "liked"))),
            remote = file(listOf(pl(20, listOf(t("b")), mode = "liked"))),
        )
        assertThat(m.playlists.single().id).isEqualTo(20L)
        assertThat(names(m.playlists.single())).containsExactly("b", "a").inOrder()
    }

    @Test
    fun `tracks this device cannot hold are never read as deleted`() {
        val phoneSong = t("mine", "/storage/emulated/0/Music/mine.flac")
        val m = BackupMerge.merge(
            base = file(listOf(pl(1, listOf(t("a"), phoneSong)))),
            local = file(listOf(pl(1, listOf(t("a"), t("b"))))),
            remote = file(listOf(pl(1, listOf(t("a"), phoneSong)))),
            holds = { !it.url.orEmpty().startsWith("/") },
        )
        assertThat(names(m.playlists.single())).containsExactly("a", "mine", "b").inOrder()
    }

    @Test
    fun `exclusions merge the same way`() {
        val m = BackupMerge.merge(
            base = file(emptyList(), listOf("a|x", "b|x")),
            local = file(emptyList(), listOf("b|x", "c|x")),
            remote = file(emptyList(), listOf("a|x", "b|x", "d|x")),
        )
        assertThat(m.exclusions.map { it.trackKey }).containsExactly("b|x", "d|x", "c|x").inOrder()
    }

    @Test
    fun `a schema 6 file keeps the exclusions made here`() {
        val m = BackupMerge.merge(
            base = file(emptyList(), listOf("a|x")),
            local = file(emptyList(), listOf("a|x", "b|x")),
            remote = file(emptyList(), schema = 6),
        )
        assertThat(m.exclusions.map { it.trackKey }).containsExactly("a|x", "b|x")
    }
}
