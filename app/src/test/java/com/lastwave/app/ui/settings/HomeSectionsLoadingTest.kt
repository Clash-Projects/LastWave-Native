package com.lastwave.app.ui.settings

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.local.MiscSettings
import com.lastwave.app.data.local.SettingsPreferences
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class)
class HomeSectionsLoadingTest {
    private val initialSettings = CompletableDeferred<MiscSettings>()
    private val preferences = mockk<SettingsPreferences>(relaxed = true)
    private val savedSettings = MiscSettings(
        hiddenHomeSections = setOf("quick_picks"),
        ytMusicHiddenSections = setOf("Relax"),
        pinnedHomeSections = setOf("top_artists"),
        homeSectionsOrder = listOf("top_artists", "quick_picks"),
    )
    private lateinit var viewModel: HomeSectionsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { preferences.settings } returns flow { emit(initialSettings.await()) }
        viewModel = HomeSectionsViewModel(preferences)
    }

    @After
    fun tearDown() {
        viewModel.viewModelScope.cancel()
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun pendingPreferencesCannotBeOverwrittenBySectionActions() {
        val initialState = viewModel.uiState.value
        viewModel.toggleVisibility("top_artists", false)
        viewModel.togglePin("quick_picks")
        viewModel.moveSection(0, 2)
        viewModel.moveSectionLive(0, 2)
        viewModel.commitOrder()
        viewModel.showAll()
        viewModel.resetAll()

        assertThat(viewModel.uiState.value).isEqualTo(initialState)
        assertThat(viewModel.uiState.value.isLoaded).isFalse()
        coVerify(exactly = 0) { preferences.saveUniversalHomeSections(any(), any(), any()) }
        coVerify(exactly = 0) { preferences.resetUniversalHomeSections() }

        initialSettings.complete(savedSettings)

        val loaded = viewModel.uiState.value
        assertThat(loaded.isLoaded).isTrue()
        assertThat(loaded.hiddenSections).containsExactly("quick_picks", "yt:Relax")
        assertThat(loaded.pinnedSections).containsExactly("top_artists")
        assertThat(loaded.sections.take(2).map { it.id })
            .containsExactly("top_artists", "quick_picks").inOrder()
    }

    @Test
    fun loadedTogglesPreserveOtherVisibilityPinsAndOrder() {
        initialSettings.complete(savedSettings)
        val loaded = viewModel.uiState.value

        viewModel.toggleVisibility("quick_picks", true)
        viewModel.toggleVisibility("discover_new_music", false)

        val updated = viewModel.uiState.value
        assertThat(updated.hiddenSections).containsExactly("yt:Relax", "discover_new_music")
        assertThat(updated.pinnedSections).isEqualTo(loaded.pinnedSections)
        assertThat(updated.sections).isEqualTo(loaded.sections)
        coVerify {
            preferences.saveUniversalHomeSections(
                loaded.sections.map { it.id }, updated.hiddenSections, loaded.pinnedSections,
            )
        }
    }

    @Test
    fun loadedReorderPinShowAllAndResetStillWork() {
        initialSettings.complete(savedSettings)
        viewModel.moveSectionLive(1, 0)
        assertThat(viewModel.uiState.value.sections.first().id).isEqualTo("quick_picks")
        coVerify(exactly = 0) { preferences.saveUniversalHomeSections(any(), any(), any()) }

        viewModel.commitOrder()
        coVerify(exactly = 1) { preferences.saveUniversalHomeSections(any(), any(), any()) }

        viewModel.moveSection(0, 2)
        assertThat(viewModel.uiState.value.sections[2].id).isEqualTo("quick_picks")
        viewModel.togglePin("quick_picks")
        val pinned = viewModel.uiState.value
        assertThat(pinned.sections.first().id).isEqualTo("quick_picks")
        assertThat(pinned.pinnedSections).containsExactly("top_artists", "quick_picks")

        viewModel.showAll()
        assertThat(viewModel.uiState.value.hiddenSections).isEmpty()
        assertThat(viewModel.uiState.value.sections).isEqualTo(pinned.sections)
        assertThat(viewModel.uiState.value.pinnedSections).isEqualTo(pinned.pinnedSections)

        viewModel.resetAll()
        val reset = viewModel.uiState.value
        assertThat(reset.sections).isEqualTo(ALL_DEFAULT_UNIVERSAL_SECTIONS)
        assertThat(reset.hiddenSections).isEmpty()
        assertThat(reset.pinnedSections).isEmpty()
        assertThat(reset.isLoaded).isTrue()
        coVerify(exactly = 1) { preferences.resetUniversalHomeSections() }
    }
}
