package com.lastwave.app.data.scrobbler

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExtraScrobblersTest {
    private class FakeService(override val service: ExtraService) : ExtraScrobbler {
        val submitted = mutableListOf<Pair<Listen, ScrobblerAccount>>()
        val playing = mutableListOf<Pair<Listen, ScrobblerAccount>>()
        var accepts = true
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun submit(listen: Listen, account: ScrobblerAccount): Boolean {
            submitted += listen to account
            gate?.await()
            return accepts
        }

        override suspend fun nowPlaying(listen: Listen, account: ScrobblerAccount): Boolean {
            playing += listen to account
            return accepts
        }
    }

    private class Fixture(scope: TestScope) {
        val accounts = ExtraService.entries.associateWith { MutableStateFlow(ScrobblerAccount("token", "user")) }
        val listenBrainz = FakeService(ExtraService.LISTENBRAINZ)
        val libreFm = FakeService(ExtraService.LIBREFM)
        val prefs = mockk<ExtraScrobblerPreferences> {
            accounts.forEach { (service, flow) ->
                every { account(service) } returns flow
                coEvery { current(service) } answers { flow.value }
            }
        }
        val manager = ExtraScrobblers(prefs, listOf(listenBrainz, libreFm), scope.backgroundScope)
    }

    @Test fun stalledServiceDoesNotDelayOtherProvider() = runTest {
        val fixture = Fixture(this)
        fixture.listenBrainz.gate = CompletableDeferred()
        val listen = Listen("Artist", "Song", startedAtSec = 1_800_000_000)
        fixture.manager.submit(listen)
        runCurrent()
        assertThat(fixture.libreFm.submitted.map { it.first }).containsExactly(listen)
        fixture.listenBrainz.gate?.complete(Unit)
    }

    @Test fun failedListenRetriesWithoutDuplicatingSuccessfulProvider() = runTest {
        val fixture = Fixture(this)
        fixture.listenBrainz.accepts = false
        val listen = Listen("Artist", "Song", startedAtSec = 1_800_000_000)
        fixture.manager.submit(listen)
        runCurrent()
        fixture.listenBrainz.accepts = true
        fixture.manager.submit(listen)
        runCurrent()
        assertThat(fixture.listenBrainz.submitted.map { it.first }).containsExactly(listen, listen).inOrder()
        assertThat(fixture.libreFm.submitted.map { it.first }).containsExactly(listen)
    }

    @Test fun nowPlayingFlushesOfflineQueueAndOnlyDeduplicatesSuccess() = runTest {
        val fixture = Fixture(this)
        val listen = Listen("Artist", "Song", startedAtSec = 1_800_000_000)
        fixture.listenBrainz.accepts = false
        fixture.manager.submit(listen)
        fixture.manager.nowPlaying(listen)
        runCurrent()
        fixture.listenBrainz.accepts = true
        fixture.manager.nowPlaying(listen)
        runCurrent()
        fixture.manager.nowPlaying(listen)
        runCurrent()
        assertThat(fixture.listenBrainz.submitted.map { it.first }).containsExactly(listen, listen, listen).inOrder()
        assertThat(fixture.listenBrainz.playing).hasSize(2)
        assertThat(fixture.libreFm.submitted).hasSize(1)
        assertThat(fixture.libreFm.playing).hasSize(1)
    }

    @Test fun accountReplacementCannotReceiveOldPendingListens() = runTest {
        val fixture = Fixture(this)
        fixture.listenBrainz.accepts = false
        val oldListen = Listen("Artist", "Old", startedAtSec = 1_800_000_000)
        fixture.manager.submit(oldListen)
        runCurrent()
        val replacement = ScrobblerAccount("new-token", "new-user")
        fixture.accounts.getValue(ExtraService.LISTENBRAINZ).value = replacement
        fixture.listenBrainz.accepts = true
        val newListen = Listen("Artist", "New", startedAtSec = 1_800_000_300)
        fixture.manager.submit(newListen)
        runCurrent()
        assertThat(fixture.listenBrainz.submitted.last()).isEqualTo(newListen to replacement)
        assertThat(fixture.listenBrainz.submitted).hasSize(2)
    }

    @Test fun replayAndNewConnectionReannounceSameTrack() = runTest {
        val fixture = Fixture(this)
        fixture.accounts.getValue(ExtraService.LISTENBRAINZ).value = ScrobblerAccount()
        val listen = Listen("Artist", "Song")
        fixture.manager.nowPlaying(listen)
        runCurrent()
        fixture.accounts.getValue(ExtraService.LISTENBRAINZ).value = ScrobblerAccount("token", "user")
        fixture.manager.nowPlaying(listen)
        runCurrent()
        fixture.manager.resetNowPlaying()
        fixture.manager.nowPlaying(listen)
        runCurrent()
        assertThat(fixture.listenBrainz.playing).hasSize(2)
        assertThat(fixture.libreFm.playing).hasSize(2)
    }

    @Test fun offlineQueueKeepsOnlyLatestTwoHundredListens() = runTest {
        val fixture = Fixture(this)
        fixture.listenBrainz.accepts = false
        val listens = (0..200).map { Listen("Artist", "Song $it", startedAtSec = 1_800_000_000L + it) }
        listens.forEach(fixture.manager::submit)
        runCurrent()
        fixture.listenBrainz.submitted.clear()
        fixture.listenBrainz.accepts = true
        fixture.manager.nowPlaying(listens.last())
        runCurrent()
        assertThat(fixture.listenBrainz.submitted.map { it.first }).containsExactlyElementsIn(listens.drop(1)).inOrder()
    }
}
