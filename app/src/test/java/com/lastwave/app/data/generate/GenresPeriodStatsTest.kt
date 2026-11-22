package com.lastwave.app.data.generate

import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.local.SessionData
import com.lastwave.app.data.local.SessionPreferences
import com.lastwave.app.data.repository.ViewingProfileState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class GenresPeriodStatsTest {
    private val generator = mockk<GenerateRepository>()
    private val preferences = mockk<SessionPreferences>()
    private val profiles = mockk<TasteProfileProvider>()
    private val session = MutableStateFlow(SessionData(username = "listener"))
    private val profile = TasteProfile(
        topArtistNames = setOf("All-time artist"), recentArtists = emptySet(), topTags = emptySet(),
        topTrackKeys = emptySet(), recentTrackKeys = emptySet(), topTracksRaw = emptyList(),
        recentTracksRaw = emptyList(), topArtistsRaw = listOf("All-time artist"), builtAtMillis = 0L,
        ytMusicFeedRaw = listOf(GeneratedTrack("Feed song", "Feed artist")),
    )
    private val repository by lazy {
        GenresRepository(mockk(relaxed = true), preferences, generator, profiles, ViewingProfileState())
    }

    private fun json(raw: String) = Json.parseToJsonElement(raw).jsonObject

    @Before
    fun setUp() {
        every { preferences.session } returns session
        coEvery { profiles.get(any()) } returns profile
        coEvery { generator.call(any()) } coAnswers {
            val params = firstArg<Map<String, String>>()
            when (params["method"]) {
                "user.gettoptags" -> json("""{"toptags":{"tag":[{"name":"rock","count":"100"},{"name":"metal","count":"80"},{"name":"pop","count":"60"}]}}""")
                "user.gettopartists" -> if (params["period"] == "7day") {
                    json("""{"topartists":{"artist":[{"name":"Week artist","playcount":"3"}]}}""")
                } else {
                    json("""{"topartists":{"artist":[{"name":"Month artist","playcount":"9"}]}}""")
                }
                "artist.gettoptags" -> when (params["artist"]) {
                    "Week artist" -> json("""{"toptags":{"tag":[{"name":"jazz"}]}}""")
                    "Month artist" -> json("""{"toptags":{"tag":[{"name":"classical"}]}}""")
                    "All-time artist" -> json("""{"toptags":{"tag":[{"name":"ambient"}]}}""")
                    else -> json("""{"toptags":{"tag":[{"name":"electronic"}]}}""")
                }
                "chart.gettoptags" -> json("""{"tags":{"tag":[{"name":"global pop","count":"50"}]}}""")
                else -> error("Unexpected request: $params")
            }
        }
    }

    @Test
    fun weekUsesDatedArtistsDespiteThreeAllTimeTagsAndProfileSeeds() = runTest {
        val result = repository.fetchGenreStats("7day")
        assertThat(result).containsExactly(GenreStat("jazz", 3, 1f))
        coVerify(exactly = 1) { generator.call(match { it["method"] == "user.gettopartists" && it["period"] == "7day" }) }
        coVerify(exactly = 0) { generator.call(match { it["method"] == "user.gettoptags" }) }
        coVerify(exactly = 0) { profiles.get(any()) }
        coVerify(exactly = 0) { generator.call(match { it["artist"] == "All-time artist" || it["artist"] == "Feed artist" }) }
    }

    @Test
    fun changingPeriodChangesTheArtistWeightsAndGenres() = runTest {
        assertThat(repository.fetchGenreStats("7day")).containsExactly(GenreStat("jazz", 3, 1f))
        assertThat(repository.fetchGenreStats("1month")).containsExactly(GenreStat("classical", 9, 1f))
    }

    @Test
    fun overallKeepsPersonalTagFastPath() = runTest {
        val result = repository.fetchGenreStats("overall")
        assertThat(result.map { it.name }).containsExactly("rock", "metal", "pop").inOrder()
        assertThat(result.first().count).isEqualTo(100L)
        coVerify(exactly = 0) { generator.call(match { it["method"] == "user.gettopartists" }) }
        coVerify(exactly = 0) { profiles.get(any()) }
    }

    @Test
    fun emptyPeriodDoesNotPretendProfileOrChartsAreListeningHistory() = runTest {
        coEvery { generator.call(match { it["method"] == "user.gettopartists" }) } returns
            json("""{"topartists":{"artist":[]}}""")
        assertThat(repository.fetchGenreStats("7day")).isEmpty()
        coVerify(exactly = 0) { profiles.get(any()) }
        coVerify(exactly = 0) { generator.call(match { it["method"] == "chart.gettoptags" }) }
    }

    @Test
    fun guestOverallKeepsUndatedProfileFallbackButWeekIsEmpty() = runTest {
        session.value = SessionData()
        assertThat(repository.fetchGenreStats("overall").map { it.name })
            .containsExactly("ambient", "electronic")
        assertThat(repository.fetchGenreStats("7day")).isEmpty()
        coVerify(exactly = 0) { generator.call(match { it["method"]?.startsWith("user.") == true }) }
    }

    @Test
    fun guestWithoutProfileSeedsKeepsOverallChartFallback() = runTest {
        session.value = SessionData()
        coEvery { profiles.get(any()) } returns profile.copy(topArtistsRaw = emptyList(), ytMusicFeedRaw = emptyList())
        assertThat(repository.fetchGenreStats("overall")).containsExactly(GenreStat("global pop", 50, 1f))
    }
}
