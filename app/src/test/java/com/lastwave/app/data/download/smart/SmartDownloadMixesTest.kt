package com.lastwave.app.data.download.smart

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SmartDownloadMixesTest {
    private val previous = mapOf("LM" to listOf("0123456789C"), "RDTMAK5uy_old" to listOf("0123456789D"))

    @Test
    fun interleavingSharesTheSongLimitAcrossMixes() {
        val mixes = mapOf("LM" to listOf("a", "b", "c", "d"), "RDTMAK5uy_x" to listOf("x", "b", "y"))

        assertThat(mixes.interleaved().distinct().take(4)).containsExactly("a", "x", "b", "c").inOrder()
        assertThat(mixes.interleaved().distinct()).containsExactly("a", "x", "b", "c", "y", "d").inOrder()
    }

    @Test
    fun sameSnapshotKeepsSavedSongsEvenWhenLimitsChange() {
        val plan = SmartDownloadsPlan("account", 250, 0L, 0L, previous, emptyList())
        val downloaded = setOf("0123456789C", "0123456789D")

        assertThat(obsoleteRecommendations(plan, previous, downloaded)).isEmpty()
    }

    @Test
    fun rotationRemovesOnlySavedSongsDroppedFromTheSnapshot() {
        val plan = SmartDownloadsPlan("account", 10, 0L, 0L, previous, emptyList())
        val recommendations = mapOf("LM" to listOf("0123456789A", "0123456789C"))
        val downloaded = setOf("0123456789C", "0123456789D")

        assertThat(obsoleteRecommendations(plan, recommendations, downloaded)).containsExactly("0123456789D")
    }
}
