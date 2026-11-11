package com.lastwave.app.ui.home

import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.local.MiscSettings
import com.lastwave.app.data.local.SettingsPreferences
import com.lastwave.app.data.repository.HomeRepository
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.ui.generate.MixLauncher
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FriendProfilePinnedStateTest {
    private val settings = MutableStateFlow(MiscSettings(pinnedFriends = setOf("alice")))
    private val preferences = mockk<SettingsPreferences>()
    private lateinit var viewModel: FriendProfileViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { preferences.settings } returns settings
        coEvery { preferences.toggleFriendPinned(any()) } coAnswers {
            val username = firstArg<String>()
            val pinned = settings.value.pinnedFriends
            settings.value = settings.value.copy(pinnedFriends = if (username in pinned) pinned - username else pinned + username)
        }
        val repository = mockk<HomeRepository>(relaxed = true)
        coEvery { repository.fetchInitialData(any(), any()) } returns Result.failure(Exception("Offline"))
        coEvery { repository.fetchTopTracksForPeriod(any(), any(), any()) } returns Result.success(emptyList())
        coEvery { repository.fetchTopArtistsForPeriod(any(), any(), any()) } returns Result.success(emptyList())
        viewModel = FriendProfileViewModel(mockk(relaxed = true), repository, preferences, mockk<MusicPlayer>(relaxed = true), MixLauncher())
    }

    @After
    fun tearDown() {
        viewModel.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun openingAnAlreadyPinnedFriendUsesCurrentSettings() {
        viewModel.loadFriend("alice")
        assertThat(viewModel.uiState.value.isPinned).isTrue()
    }

    @Test
    fun settingsEmissionBeforeWriteReturnsDoesNotDoubleToggleIcon() {
        viewModel.loadFriend("bob")
        viewModel.togglePinned()
        assertThat(settings.value.pinnedFriends).contains("bob")
        assertThat(viewModel.uiState.value.isPinned).isTrue()
        viewModel.togglePinned()
        assertThat(settings.value.pinnedFriends).doesNotContain("bob")
        assertThat(viewModel.uiState.value.isPinned).isFalse()
    }
}
