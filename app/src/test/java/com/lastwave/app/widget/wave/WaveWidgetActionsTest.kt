package com.lastwave.app.widget.wave

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class WaveWidgetActionsTest {
    @Before
    fun resetDebouncer() = WaveActionDebouncer.clear()

    @Test
    fun rapidLikeTap_isDebouncedButDifferentActionsRemainIndependent() {
        assertThat(WaveActionDebouncer.shouldAccept(WaveWidgetActions.ACTION_LIKE, null, 1_000)).isTrue()
        assertThat(WaveActionDebouncer.shouldAccept(WaveWidgetActions.ACTION_LIKE, null, 1_100)).isFalse()
        assertThat(WaveActionDebouncer.shouldAccept(WaveWidgetActions.ACTION_NEXT, null, 1_100)).isTrue()
        assertThat(WaveActionDebouncer.shouldAccept(WaveWidgetActions.ACTION_LIKE, null, 1_500)).isTrue()
    }

    @Test
    fun deadSession_isRestoredBeforeTransportAction() = runTest {
        val calls = mutableListOf<String>()
        val target = fakeTarget(
            hasSession = false,
            calls = calls,
        )

        WaveActionExecutor(target).execute(WaveActionRequest(WaveWidgetActions.ACTION_TOGGLE))

        assertThat(calls).containsExactly("restore", "toggle").inOrder()
    }

    @Test
    fun deadSession_isRestoredBeforeLikeAction() = runTest {
        val calls = mutableListOf<String>()
        val target = fakeTarget(
            hasSession = false,
            calls = calls,
        )

        WaveActionExecutor(target).execute(WaveActionRequest(WaveWidgetActions.ACTION_LIKE))

        assertThat(calls).containsExactly("restore", "like").inOrder()
    }

    @Test
    fun optimisticLike_revertsOnFailureAndSettlesConfirmedState() {
        val original = WaveWidgetState(trackId = "1", title = "Song", isLiked = false)
        val optimistic = WaveLikeOptimism.optimistic(original)

        assertThat(optimistic.isLiked).isTrue()
        assertThat(WaveLikeOptimism.settle(original, optimistic, null)).isEqualTo(original)
        assertThat(WaveLikeOptimism.settle(original, optimistic, true).isLiked).isTrue()
        assertThat(WaveLikeOptimism.settle(original, optimistic, false).isLiked).isFalse()
    }

    private fun fakeTarget(hasSession: Boolean, calls: MutableList<String>): WaveActionTarget =
        object : WaveActionTarget {
            var restored = hasSession
            override fun hasRestoredSession() = restored
            override fun restoreSession() { calls += "restore"; restored = true }
            override suspend fun toggle() { calls += "toggle" }
            override suspend fun next() { calls += "next" }
            override suspend fun previous() { calls += "previous" }
            override suspend fun like() { calls += "like" }
            override suspend fun shuffle() { calls += "shuffle" }
            override suspend fun repeat() { calls += "repeat" }
            override suspend fun jump(queueIndex: Int) { calls += "jump:$queueIndex" }
            override suspend fun playlist(id: String) { calls += "playlist:$id" }
        }
}
