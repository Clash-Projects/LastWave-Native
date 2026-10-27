package com.lastwave.app.ui.feed

import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.discover.DiscoverRepository
import com.lastwave.app.data.feed.FeedData
import com.lastwave.app.data.feed.FeedRepository
import com.lastwave.app.data.generate.GenresRepository
import com.lastwave.app.data.local.SessionData
import com.lastwave.app.data.local.SessionPreferences
import com.lastwave.app.data.local.SettingsPreferences
import com.lastwave.app.data.music.HomeChip
import com.lastwave.app.data.music.HomePage
import com.lastwave.app.data.music.HomeShelf
import com.lastwave.app.data.music.HomeShelfItem
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.data.repository.HomeRepository
import com.lastwave.app.data.repository.RelatedGraphRepository
import com.lastwave.app.data.ytmusic.YtConnection
import com.lastwave.app.data.ytmusic.YtMusicAuthManager
import com.lastwave.app.playback.MusicPlayer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
@Config(manifest = Config.NONE)
class FeedViewModelChipsAndPaginationTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private val context: android.content.Context = mockk(relaxed = true)
    private val feedRepository: FeedRepository = mockk(relaxed = true)
    private val sessionPreferences: SessionPreferences = mockk(relaxed = true)
    private val settingsPreferences: SettingsPreferences = mockk(relaxed = true)
    private val musicPlayer: MusicPlayer = mockk(relaxed = true)
    private val innerTube: InnerTubeMusicApi = mockk(relaxed = true)
    private val relatedGraph: RelatedGraphRepository = mockk(relaxed = true)
    private val ytAuth: YtMusicAuthManager = mockk(relaxed = true)
    private val homeRepository: HomeRepository = mockk(relaxed = true)
    private val discoverRepository: DiscoverRepository = mockk(relaxed = true)
    private val genresRepository: GenresRepository = mockk(relaxed = true)

    private fun createShelf(title: String, videoId: String): HomeShelf {
        return HomeShelf(
            title = title,
            items = listOf(
                HomeShelfItem.Track(
                    YouTubeMusicTrack(
                        videoId = videoId,
                        title = "Song in $title",
                        artist = "Artist",
                    )
                )
            )
        )
    }

    private fun waitFor(timeoutMs: Long = 2000, condition: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!condition() && System.currentTimeMillis() - start < timeoutMs) {
            Thread.sleep(20)
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        coEvery { ytAuth.connection } returns MutableStateFlow(YtConnection())
        coEvery { ytAuth.awaitLoadedConnection() } returns YtConnection()
        coEvery { sessionPreferences.session } returns MutableStateFlow(SessionData(username = "test_user"))
        coEvery { settingsPreferences.settings } returns MutableStateFlow(mockk(relaxed = true))
        coEvery { context.getString(any()) } returns "An error occurred"
        coEvery { homeRepository.fetchStats() } returns Result.failure(Exception("no stats"))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): FeedViewModel {
        return FeedViewModel(
            context,
            feedRepository,
            sessionPreferences,
            settingsPreferences,
            musicPlayer,
            innerTube,
            relatedGraph,
            ytAuth,
            homeRepository,
            discoverRepository,
            genresRepository,
        )
    }

    @Test
    fun selectChip_togglesSelection_andLoadsChipShelves() {
        val chip = HomeChip(title = "Energize", params = "energize_params_1")
        val chipShelf = createShelf("High Energy Beats", "energy_vid_1")
        coEvery { innerTube.fetchHomeChip(chip) } returns HomePage(
            shelves = listOf(chipShelf),
            chips = emptyList(),
            continuationToken = "chip_token_2",
        )

        val vm = createViewModel()

        // 1. Select the chip
        vm.selectChip(chip)
        waitFor { !vm.uiState.value.isChipLoading }

        val stateAfterSelect = vm.uiState.value
        assertThat(stateAfterSelect.selectedChip).isEqualTo(chip)
        assertThat(stateAfterSelect.isChipActive).isTrue()
        assertThat(stateAfterSelect.chipShelves).containsExactly(chipShelf)
        assertThat(stateAfterSelect.chipContinuationToken).isEqualTo("chip_token_2")

        // 2. Tapping the same chip again deselects it and reverts to default view
        vm.selectChip(chip)
        val stateAfterDeselect = vm.uiState.value
        assertThat(stateAfterDeselect.selectedChip).isNull()
        assertThat(stateAfterDeselect.isChipActive).isFalse()
        assertThat(stateAfterDeselect.chipShelves).isEmpty()
        assertThat(stateAfterDeselect.chipContinuationToken).isNull()
    }

    @Test
    fun selectChip_failureRevertsToDefaultView_andShowsError() {
        val chip = HomeChip(title = "Relax", params = "relax_params_1")
        coEvery { innerTube.fetchHomeChip(chip) } throws RuntimeException("Network error")

        val vm = createViewModel()
        vm.selectChip(chip)
        waitFor { !vm.uiState.value.isChipLoading }

        val state = vm.uiState.value
        assertThat(state.selectedChip).isNull()
        assertThat(state.isChipActive).isFalse()
        assertThat(state.isChipLoading).isFalse()
        assertThat(state.error).isNotNull()
    }

    @Test
    fun loadMoreHomeShelves_appendsNewShelves_andUpdatesContinuationToken() {
        val page1Shelf = createShelf("Page 1 Shelf", "p1_vid")
        val page2Shelf = createShelf("Page 2 Shelf", "p2_vid")
        val initialFeedData = FeedData(
            nativeShelves = listOf(page1Shelf),
            homeContinuationToken = "page1_token",
        )
        coEvery { feedRepository.loadFeed(any(), any()) } returns initialFeedData
        coEvery { feedRepository.getCachedFeed() } returns initialFeedData
        coEvery { innerTube.fetchHomeContinuation("page1_token") } returns HomePage(
            shelves = listOf(page2Shelf),
            chips = emptyList(),
            continuationToken = "page2_token",
        )

        val vm = createViewModel()
        vm.loadFeed()
        waitFor { !vm.uiState.value.isLoading }

        // Initial state before load-more
        assertThat(vm.uiState.value.feedData.nativeShelves).containsExactly(page1Shelf)
        assertThat(vm.uiState.value.pagedShelves).isEmpty()

        // Trigger load more
        vm.loadMoreHomeShelves()
        waitFor { !vm.uiState.value.isLoadingMore }

        val stateAfter = vm.uiState.value
        assertThat(stateAfter.pagedShelves).containsExactly(page2Shelf)
        assertThat(stateAfter.defaultContinuationToken).isEqualTo("page2_token")
        assertThat(stateAfter.isLoadingMore).isFalse()
    }

    @Test
    fun loadMoreHomeShelves_skipsDuplicateShelves() {
        val page1Shelf = createShelf("Existing Shelf", "existing_vid_1")
        val duplicateShelf = createShelf("Existing Shelf", "existing_vid_1")
        val distinctShelf = createShelf("New Unique Shelf", "new_vid_2")

        val initialFeedData = FeedData(
            nativeShelves = listOf(page1Shelf),
            homeContinuationToken = "token_dup_test",
        )
        coEvery { feedRepository.loadFeed(any(), any()) } returns initialFeedData
        coEvery { feedRepository.getCachedFeed() } returns initialFeedData
        coEvery { innerTube.fetchHomeContinuation("token_dup_test") } returns HomePage(
            shelves = listOf(duplicateShelf, distinctShelf),
            chips = emptyList(),
            continuationToken = "next_token",
        )

        val vm = createViewModel()
        vm.loadFeed()
        waitFor { !vm.uiState.value.isLoading }

        vm.loadMoreHomeShelves()
        waitFor { !vm.uiState.value.isLoadingMore }

        // Only distinctShelf should be added to pagedShelves, duplicateShelf must be skipped
        assertThat(vm.uiState.value.pagedShelves).containsExactly(distinctShelf)
    }

    @Test
    fun loadMoreHomeShelves_doesNothingWhenTokenIsNull() {
        val initialFeedData = FeedData(
            nativeShelves = listOf(createShelf("Shelf A", "vid_a")),
            homeContinuationToken = null,
        )
        coEvery { feedRepository.loadFeed(any(), any()) } returns initialFeedData
        coEvery { feedRepository.getCachedFeed() } returns initialFeedData

        val vm = createViewModel()
        vm.loadFeed()
        waitFor { !vm.uiState.value.isLoading }

        vm.loadMoreHomeShelves()

        coVerify(exactly = 0) { innerTube.fetchHomeContinuation(any()) }
        assertThat(vm.uiState.value.isLoadingMore).isFalse()
        assertThat(vm.uiState.value.pagedShelves).isEmpty()
    }

    private fun createEpisodeShelf(title: String, videoId: String): HomeShelf {
        return HomeShelf(
            title = title,
            items = listOf(
                HomeShelfItem.PodcastEpisode(
                    videoId = videoId,
                    title = "Episode in $title",
                    showName = "Show name",
                    subtitle = "10K views",
                    durationText = "45:00",
                )
            )
        )
    }

    private fun createShowShelf(title: String, browseId: String): HomeShelf {
        return HomeShelf(
            title = title,
            items = listOf(
                HomeShelfItem.PodcastShow(
                    browseId = browseId,
                    title = "Show in $title",
                    author = "Host",
                )
            )
        )
    }

    @Test
    fun selectPodcastsChip_loadsAllShelves_andSetsBackgroundUrl() {
        val chip = HomeChip(title = "Podcasts", params = "podcast_params")
        val showsShelf = createShowShelf("Shows for you", "MPSP_show_1")
        val episodeShelf = createEpisodeShelf("Government", "ep_gov_1")
        val bgUrl = "https://example.com/podcast_hero.jpg"
        coEvery { innerTube.fetchHomeChip(chip) } returns HomePage(
            shelves = listOf(showsShelf, episodeShelf),
            chips = emptyList(),
            continuationToken = "podcast_cont_1",
            backgroundUrl = bgUrl,
        )

        val vm = createViewModel()
        vm.selectChip(chip)
        waitFor { !vm.uiState.value.isChipLoading }

        val state = vm.uiState.value
        assertThat(state.selectedChip).isEqualTo(chip)
        assertThat(state.isChipActive).isTrue()
        assertThat(state.chipShelves).containsExactly(showsShelf, episodeShelf).inOrder()
        assertThat(state.chipContinuationToken).isEqualTo("podcast_cont_1")
        assertThat(state.chipBackgroundUrl).isEqualTo(bgUrl)

        // Deselecting clears the backgroundUrl as well
        vm.selectChip(chip)
        val stateAfterDeselect = vm.uiState.value
        assertThat(stateAfterDeselect.selectedChip).isNull()
        assertThat(stateAfterDeselect.chipBackgroundUrl).isNull()
    }

    @Test
    fun loadMoreHomeShelves_inChipMode_appendsPage2Shelves_skippingDuplicates() {
        val chip = HomeChip(title = "Podcasts", params = "podcast_params")
        val showsShelf = createShowShelf("Shows for you", "MPSP_show_1")
        val govShelf = createEpisodeShelf("Government", "ep_gov_1")
        coEvery { innerTube.fetchHomeChip(chip) } returns HomePage(
            shelves = listOf(showsShelf, govShelf),
            chips = emptyList(),
            continuationToken = "podcast_cont_1",
            backgroundUrl = "https://example.com/hero.jpg",
        )

        val duplicateGovShelf = createEpisodeShelf("Government", "ep_gov_1")
        val filmsShelf = createEpisodeShelf("Films", "ep_film_1")
        coEvery { innerTube.fetchHomeContinuation("podcast_cont_1") } returns HomePage(
            shelves = listOf(duplicateGovShelf, filmsShelf),
            chips = emptyList(),
            continuationToken = "podcast_cont_2",
        )

        val vm = createViewModel()
        vm.selectChip(chip)
        waitFor { !vm.uiState.value.isChipLoading }

        // Initial chip shelves
        assertThat(vm.uiState.value.chipShelves).containsExactly(showsShelf, govShelf).inOrder()

        // Paginate in chip mode
        vm.loadMoreHomeShelves()
        waitFor { !vm.uiState.value.isLoadingMore }

        val stateAfter = vm.uiState.value
        assertThat(stateAfter.chipShelves).containsExactly(showsShelf, govShelf, filmsShelf).inOrder()
        assertThat(stateAfter.chipContinuationToken).isEqualTo("podcast_cont_2")
    }
}
