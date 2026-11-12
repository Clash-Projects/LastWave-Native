package com.lastwave.app.data.music

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistPreviewLoaderTest {
    @Test fun samePlaylistSharesPendingAndFinishedResult() = runTest {
        var calls = 0
        val result = CompletableDeferred<PlaylistPreview?>()
        val loader = PlaylistPreviewLoader(this) { calls++; result.await() }
        val first = loader.load("VLPL123")
        assertThat(loader.load("PL123")).isSameInstanceAs(first)
        runCurrent()
        assertThat(calls).isEqualTo(1)
        assertThat(first.value).isEqualTo(PlaylistPreviewState.Loading)
        result.complete(PlaylistPreview())
        runCurrent()
        assertThat(first.value).isInstanceOf(PlaylistPreviewState.Ready::class.java)
        assertThat(loader.load("PL123")).isSameInstanceAs(first)
        assertThat(calls).isEqualTo(1)
    }

    @Test fun atMostTwoRequestsRunAndWaitingRequestsFinish() = runTest {
        var active = 0
        var peak = 0
        val gates = List(5) { CompletableDeferred<Unit>() }
        val started = mutableListOf<Int>()
        val loader = PlaylistPreviewLoader(this) { id ->
            val index = id.toInt()
            started += index
            active++
            peak = maxOf(peak, active)
            try { gates[index].await(); PlaylistPreview() } finally { active-- }
        }
        val states = (0..4).map { loader.load(it.toString()) }
        runCurrent()
        assertThat(started).containsExactly(0, 1).inOrder()
        gates[0].complete(Unit)
        runCurrent()
        assertThat(started).containsExactly(0, 1, 2).inOrder()
        gates.forEach { it.complete(Unit) }
        runCurrent()
        assertThat(peak).isEqualTo(2)
        assertThat(states.all { it.value is PlaylistPreviewState.Ready }).isTrue()
    }

    @Test fun failedAndEmptyResponsesAreCachedAndReleasePermits() = runTest {
        val calls = mutableListOf<String>()
        val loader = PlaylistPreviewLoader(this) { id ->
            calls += id
            when (id) { "failed" -> throw IOException("offline"); "empty" -> null; else -> PlaylistPreview() }
        }
        val failed = loader.load("failed")
        val empty = loader.load("empty")
        val good = loader.load("good")
        runCurrent()
        assertThat(failed.value).isEqualTo(PlaylistPreviewState.Failed)
        assertThat(empty.value).isEqualTo(PlaylistPreviewState.Failed)
        assertThat(good.value).isInstanceOf(PlaylistPreviewState.Ready::class.java)
        loader.load("failed")
        loader.load("empty")
        runCurrent()
        assertThat(calls).containsExactly("failed", "empty", "good")
    }

    @Test fun blankIdsNeverFetch() = runTest {
        var calls = 0
        val loader = PlaylistPreviewLoader(this) { calls++; PlaylistPreview() }
        assertThat(loader.load(" ").value).isEqualTo(PlaylistPreviewState.Failed)
        runCurrent()
        assertThat(calls).isEqualTo(0)
    }
}
