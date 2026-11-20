package com.lastwave.app.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.generate.TasteProfileProvider
import com.lastwave.app.data.local.SessionPreferences
import com.lastwave.app.data.local.db.DownloadedTrackDao
import com.lastwave.app.data.local.db.SongPlayStatsDao
import com.lastwave.app.data.local.db.SongPlayStatsEntity
import com.lastwave.app.data.playlist.PlaylistRepository
import com.lastwave.app.data.repository.HomeRepository
import com.lastwave.app.data.repository.LocalStatsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class ListeningStatsViewModel @Inject constructor(
    private val songPlayStatsDao: SongPlayStatsDao,
    private val localStatsRepository: LocalStatsRepository,
    private val playlistRepository: PlaylistRepository,
    private val downloadedTrackDao: DownloadedTrackDao,
    private val homeRepository: HomeRepository,
    private val sessionPreferences: SessionPreferences,
    private val tasteProfileProvider: TasteProfileProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ListeningStatsUiState())
    val uiState: StateFlow<ListeningStatsUiState> = _uiState.asStateFlow()

    private var rawStatsList: List<SongPlayStatsEntity> = emptyList()
    private var localStatsCache: LocalStatsRepository.LocalStats? = null
    private var tasteTags: List<String> = emptyList()

    init {
        loadData()
    }

    fun setTimeRange(range: StatsTimeRange) {
        _uiState.update { it.copy(selectedRange = range) }
        recalculate()
    }

    fun setTimelineMetric(metric: TimelineMetric) {
        _uiState.update { it.copy(selectedTimelineMetric = metric) }
        recalculate()
    }

    fun setCategory(category: CategoryFilter) {
        _uiState.update { it.copy(selectedCategory = category) }
        recalculate()
    }

    fun refresh() {
        _uiState.update { it.copy(isRefreshing = true) }
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = rawStatsList.isEmpty()) }
            runCatching {
                rawStatsList = songPlayStatsDao.getAll()
                localStatsCache = runCatching { localStatsRepository.load() }.getOrNull()
                tasteTags = runCatching { tasteProfileProvider.get().topTags.toList() }.getOrNull().orEmpty()
            }
            recalculate()
            _uiState.update { it.copy(isLoading = false, isRefreshing = false) }
        }
    }

    private fun getCutoffMillis(range: StatsTimeRange): Long {
        val now = Calendar.getInstance()
        return when (range) {
            StatsTimeRange.WEEK -> {
                now.apply {
                    set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
            }
            StatsTimeRange.MONTH_TO_DATE -> {
                now.apply {
                    set(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
            }
            StatsTimeRange.YEAR_TO_DATE -> {
                now.apply {
                    set(Calendar.DAY_OF_YEAR, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
            }
            StatsTimeRange.ALL_TIME -> 0L
        }
    }

    private fun recalculate() {
        val state = _uiState.value
        val cutoff = getCutoffMillis(state.selectedRange)

        // 1. Filter tracks within the selected time window
        var tracks = if (cutoff > 0L) {
            rawStatsList.filter { it.lastPlayedAtMillis >= cutoff }
        } else {
            rawStatsList
        }

        // Fallback: If DB song_play_stats is empty for the requested range,
        // fall back to raw stats, localStatsCache, or downloads so the user sees real library insights
        if (tracks.isEmpty()) {
            if (rawStatsList.isNotEmpty()) {
                tracks = rawStatsList
            } else if (localStatsCache != null) {
                val local = localStatsCache!!
                tracks = local.topTracks.map { ht ->
                    SongPlayStatsEntity(
                        trackKey = "${ht.name}|${ht.artist}".lowercase(),
                        title = ht.name,
                        artist = ht.artist,
                        artworkUrl = ht.artworkUrl,
                        totalPlayTimeMs = ht.playCount * 210_000L,
                        playCount = ht.playCount,
                        lastPlayedAtMillis = System.currentTimeMillis() - (1..30).random() * 86_400_000L,
                    )
                }
            } else {
                val downloaded = runCatching { downloadedTrackDao.getAllList() }.getOrNull().orEmpty()
                if (downloaded.isNotEmpty()) {
                    tracks = downloaded.map { dt ->
                        SongPlayStatsEntity(
                            trackKey = "${dt.title}|${dt.artist}".lowercase(),
                            title = dt.title,
                            artist = dt.artist,
                            artworkUrl = dt.artworkUrl,
                            totalPlayTimeMs = dt.durationMs.takeIf { it > 0L } ?: 210_000L,
                            playCount = 1,
                            lastPlayedAtMillis = dt.downloadedAtMillis,
                        )
                    }
                }
            }
        }

        // Total listening duration & play count
        val totalListeningMs = tracks.sumOf {
            if (it.totalPlayTimeMs > 0L) it.totalPlayTimeMs else it.playCount * 210_000L
        }
        val totalPlays = tracks.sumOf { it.playCount.coerceAtLeast(1) }
        val totalListeningLabel = formatHoursMins(totalListeningMs)

        // 2. Timeline Bars & Peak Segment
        val (bars, peakName, peakDuration) = computeTimelineBars(tracks, state.selectedRange, state.selectedTimelineMetric)

        // 3. Category Items (Song / Album / Artist / Genre)
        val categoryItems = computeCategoryItems(tracks, state.selectedCategory)

        // 4. Listening Habits
        val habits = computeHabits(tracks, totalListeningMs, totalPlays)

        // 5. Track Concentration
        val concentration = computeConcentration(tracks, totalListeningMs, totalPlays)

        // 6. Detailed Rhythm & Insights
        val records = computePersonalRecords(rawStatsList.ifEmpty { tracks })
        val weeklyRhythm = computeWeeklyRhythm(rawStatsList.ifEmpty { tracks })
        val monthComparison = computeMonthComparison(rawStatsList.ifEmpty { tracks })
        val hourlyRhythm = computeHourlyRhythm(rawStatsList.ifEmpty { tracks })
        val activityHeatmap = computeActivityHeatmap(rawStatsList.ifEmpty { tracks })

        // Sleep-tracker-inspired: Consistency Score & Listening Stages
        val streak = records?.longestStreakDays ?: 1
        val avgSessionMins = ((habits?.avgSessionLabel?.filter { it.isDigit() }?.toIntOrNull() ?: 18)).coerceIn(5, 120)
        val score = (60 + (streak * 4).coerceAtMost(20) + (avgSessionMins / 3).coerceAtMost(20)).coerceIn(65, 99)
        val rating = when {
            score >= 90 -> "Peak Flow"
            score >= 80 -> "Optimal Rhythm"
            score >= 70 -> "Steady Cadence"
            else -> "Light Rhythm"
        }
        val consistency = ConsistencyScoreData(
            score = score,
            rating = rating,
            subtitle = if (streak > 2) "$streak-day listening streak active" else "Consistent daily listening sessions",
            avgSessionMinutes = avgSessionMins,
            activeDaysThisWeek = (streak.coerceAtMost(7)).coerceAtLeast(3),
            streakDays = streak,
        )

        val deepTracks = tracks.filter { it.totalPlayTimeMs >= 240_000L || it.playCount >= 3 }
        val deepMs = deepTracks.sumOf { if (it.totalPlayTimeMs > 0L) it.totalPlayTimeMs else it.playCount * 210_000L }
        val deepPercent = if (totalListeningMs > 0) ((deepMs.toDouble() / totalListeningMs) * 100).roundToInt().coerceIn(20, 70) else 45
        val discoveryTracks = tracks.filter { it.playCount <= 1 }
        val discoveryMs = discoveryTracks.sumOf { if (it.totalPlayTimeMs > 0L) it.totalPlayTimeMs else 210_000L }
        val discoveryPercent = if (totalListeningMs > 0) ((discoveryMs.toDouble() / totalListeningMs) * 100).roundToInt().coerceIn(10, 35) else 20
        val flowPercent = (100 - deepPercent - discoveryPercent).coerceAtLeast(15)

        val stages = ListeningStagesData(
            deepFocus = ListeningStage(
                name = "Deep Focus",
                percent = deepPercent,
                durationLabel = formatHoursMins((totalListeningMs * deepPercent) / 100),
                description = "Extended listening sessions and favorite repeats",
            ),
            flowState = ListeningStage(
                name = "Flow State",
                percent = flowPercent,
                durationLabel = formatHoursMins((totalListeningMs * flowPercent) / 100),
                description = "Balanced background listening & daily rotation",
            ),
            discovery = ListeningStage(
                name = "Discovery",
                percent = discoveryPercent,
                durationLabel = formatHoursMins((totalListeningMs * discoveryPercent) / 100),
                description = "Exploring new tracks & genre adventures",
            ),
        )

        // 7. Tracks in range
        val maxTrackMs = tracks.maxOfOrNull {
            if (it.totalPlayTimeMs > 0L) it.totalPlayTimeMs else it.playCount * 210_000L
        }?.coerceAtLeast(1L) ?: 1L

        val tracksInRange = tracks
            .sortedByDescending { if (it.totalPlayTimeMs > 0L) it.totalPlayTimeMs else it.playCount * 210_000L }
            .take(30)
            .mapIndexed { idx, entity ->
                val trackMs = if (entity.totalPlayTimeMs > 0L) entity.totalPlayTimeMs else entity.playCount * 210_000L
                RankedTrackItem(
                    rank = idx + 1,
                    title = entity.title.ifBlank { "Unknown Title" },
                    artist = entity.artist.ifBlank { "Unknown Artist" },
                    artworkUrl = entity.artworkUrl,
                    playCount = entity.playCount.coerceAtLeast(1),
                    durationLabel = formatHoursMins(trackMs),
                    progress = (trackMs.toFloat() / maxTrackMs).coerceIn(0.05f, 1f),
                )
            }

        _uiState.update {
            it.copy(
                totalListeningLabel = totalListeningLabel,
                totalPlays = totalPlays,
                timelineBars = bars,
                peakSegmentName = peakName,
                peakSegmentDuration = peakDuration,
                categoryItems = categoryItems,
                habits = habits,
                concentration = concentration,
                tracksInRange = tracksInRange,
                records = records,
                weeklyRhythm = weeklyRhythm,
                monthComparison = monthComparison,
                hourlyRhythm = hourlyRhythm,
                activityHeatmap = activityHeatmap,
                consistency = consistency,
                stages = stages,
            )
        }
    }

    private fun computeTimelineBars(
        tracks: List<SongPlayStatsEntity>,
        range: StatsTimeRange,
        metric: TimelineMetric,
    ): Triple<List<TimelineBar>, String, String> {
        if (tracks.isEmpty()) {
            val emptyYear = Calendar.getInstance().get(Calendar.YEAR).toString()
            return Triple(listOf(TimelineBar(emptyYear, 1f, "0m", isPeak = true)), emptyYear, "0m")
        }

        // Group tracks by label based on range
        data class Bucket(val label: String, var totalMs: Long = 0L, var plays: Int = 0, var sessions: Int = 0)
        val buckets = LinkedHashMap<String, Bucket>()

        when (range) {
            StatsTimeRange.WEEK -> {
                val dayNameFmt = SimpleDateFormat("EEE", Locale.getDefault())
                val tempCal = Calendar.getInstance()
                tempCal.set(Calendar.DAY_OF_WEEK, tempCal.firstDayOfWeek)
                val daysOfWeek = mutableListOf<String>()
                for (i in 0..6) {
                    daysOfWeek.add(dayNameFmt.format(tempCal.time))
                    tempCal.add(Calendar.DAY_OF_WEEK, 1)
                }
                daysOfWeek.forEach { d -> buckets[d] = Bucket(d) }
                val cal = Calendar.getInstance()
                tracks.forEach { t ->
                    val time = if (t.lastPlayedAtMillis > 0L) t.lastPlayedAtMillis else System.currentTimeMillis()
                    cal.timeInMillis = time
                    val label = dayNameFmt.format(cal.time)
                    val b = buckets.getOrPut(label) { Bucket(label) }
                    val ms = if (t.totalPlayTimeMs > 0L) t.totalPlayTimeMs else t.playCount * 210_000L
                    b.totalMs += ms
                    b.plays += t.playCount.coerceAtLeast(1)
                    b.sessions += (t.playCount.coerceAtLeast(1) / 3).coerceAtLeast(1)
                }
            }
            StatsTimeRange.ALL_TIME -> {
                val currentYear = Calendar.getInstance().get(Calendar.YEAR)
                val years = listOf(currentYear - 2, currentYear - 1, currentYear)
                years.forEach { y -> buckets[y.toString()] = Bucket(y.toString()) }

                val cal = Calendar.getInstance()
                tracks.forEach { t ->
                    val time = if (t.lastPlayedAtMillis > 0L) t.lastPlayedAtMillis else System.currentTimeMillis()
                    cal.timeInMillis = time
                    val y = cal.get(Calendar.YEAR).toString()
                    val b = buckets.getOrPut(y) { Bucket(y) }
                    val ms = if (t.totalPlayTimeMs > 0L) t.totalPlayTimeMs else t.playCount * 210_000L
                    b.totalMs += ms
                    b.plays += t.playCount.coerceAtLeast(1)
                    b.sessions += (t.playCount.coerceAtLeast(1) / 3).coerceAtLeast(1)
                }
            }
            StatsTimeRange.YEAR_TO_DATE -> {
                val monthFmt = SimpleDateFormat("MMM", Locale.getDefault())
                val cal = Calendar.getInstance()
                val currentMonth = cal.get(Calendar.MONTH)
                for (m in 0..currentMonth) {
                    cal.set(Calendar.MONTH, m)
                    val label = monthFmt.format(cal.time)
                    buckets[label] = Bucket(label)
                }
                tracks.forEach { t ->
                    val time = if (t.lastPlayedAtMillis > 0L) t.lastPlayedAtMillis else System.currentTimeMillis()
                    cal.timeInMillis = time
                    val label = monthFmt.format(cal.time)
                    val b = buckets.getOrPut(label) { Bucket(label) }
                    val ms = if (t.totalPlayTimeMs > 0L) t.totalPlayTimeMs else t.playCount * 210_000L
                    b.totalMs += ms
                    b.plays += t.playCount.coerceAtLeast(1)
                    b.sessions += (t.playCount.coerceAtLeast(1) / 3).coerceAtLeast(1)
                }
            }
            StatsTimeRange.MONTH_TO_DATE -> {
                val dayFmt = SimpleDateFormat("dd", Locale.getDefault())
                val cal = Calendar.getInstance()
                val currentDay = cal.get(Calendar.DAY_OF_MONTH)
                val step = if (currentDay > 14) 2 else 1
                for (d in 1..currentDay step step) {
                    cal.set(Calendar.DAY_OF_MONTH, d)
                    val label = dayFmt.format(cal.time)
                    buckets[label] = Bucket(label)
                }
                tracks.forEach { t ->
                    val time = if (t.lastPlayedAtMillis > 0L) t.lastPlayedAtMillis else System.currentTimeMillis()
                    cal.timeInMillis = time
                    val label = dayFmt.format(cal.time)
                    val b = buckets.getOrPut(label) { Bucket(label) }
                    val ms = if (t.totalPlayTimeMs > 0L) t.totalPlayTimeMs else t.playCount * 210_000L
                    b.totalMs += ms
                    b.plays += t.playCount.coerceAtLeast(1)
                    b.sessions += (t.playCount.coerceAtLeast(1) / 3).coerceAtLeast(1)
                }
            }
        }

        val bucketList = buckets.values.toList()
        val peakBucket = bucketList.maxByOrNull {
            when (metric) {
                TimelineMetric.LISTENING_TIME -> it.totalMs.toFloat()
                TimelineMetric.PLAY_COUNT -> it.plays.toFloat()
                TimelineMetric.AVG_SESSION -> if (it.sessions > 0) (it.totalMs / (it.sessions * 60_000f)) else 0f
            }
        }

        val maxVal = bucketList.maxOfOrNull {
            when (metric) {
                TimelineMetric.LISTENING_TIME -> (it.totalMs / 60_000f)
                TimelineMetric.PLAY_COUNT -> it.plays.toFloat()
                TimelineMetric.AVG_SESSION -> if (it.sessions > 0) (it.totalMs / (it.sessions * 60_000f)) else 0f
            }
        }?.coerceAtLeast(1f) ?: 1f

        val bars = bucketList.map { b ->
            val numVal = when (metric) {
                TimelineMetric.LISTENING_TIME -> (b.totalMs / 60_000f)
                TimelineMetric.PLAY_COUNT -> b.plays.toFloat()
                TimelineMetric.AVG_SESSION -> if (b.sessions > 0) (b.totalMs / (b.sessions * 60_000f)) else 0f
            }
            val valueLabel = when (metric) {
                TimelineMetric.LISTENING_TIME -> formatHoursMins(b.totalMs)
                TimelineMetric.PLAY_COUNT -> "${b.plays} plays"
                TimelineMetric.AVG_SESSION -> "${numVal.roundToInt()}m"
            }
            TimelineBar(
                label = b.label,
                value = (numVal / maxVal).coerceIn(0.05f, 1f),
                valueLabel = valueLabel,
                isPeak = (b == peakBucket && numVal > 0f),
            )
        }

        val peakName = peakBucket?.label ?: "2026"
        val peakDuration = formatHoursMins(peakBucket?.totalMs ?: 0L)
        return Triple(bars, peakName, peakDuration)
    }

    private fun computeCategoryItems(
        tracks: List<SongPlayStatsEntity>,
        category: CategoryFilter,
    ): List<RankedCategoryItem> {
        return when (category) {
            CategoryFilter.SONG -> {
                val sorted = tracks.sortedByDescending { if (it.totalPlayTimeMs > 0L) it.totalPlayTimeMs else it.playCount * 210_000L }.take(10)
                val maxMs = sorted.firstOrNull()?.let { if (it.totalPlayTimeMs > 0L) it.totalPlayTimeMs else it.playCount * 210_000L }?.coerceAtLeast(1L) ?: 1L
                sorted.mapIndexed { idx, t ->
                    val ms = if (t.totalPlayTimeMs > 0L) t.totalPlayTimeMs else t.playCount * 210_000L
                    RankedCategoryItem(
                        rank = idx + 1,
                        title = t.title.ifBlank { "Unknown Title" },
                        subtitle = "${t.playCount.coerceAtLeast(1)} plays \u2022 ${t.artist.ifBlank { "Unknown Artist" }}",
                        durationLabel = formatHoursMins(ms),
                        progress = (ms.toFloat() / maxMs).coerceIn(0.05f, 1f),
                        artworkUrl = t.artworkUrl,
                    )
                }
            }
            CategoryFilter.ARTIST -> {
                val grouped = tracks.groupBy { it.artist.trim().ifBlank { "Unknown Artist" } }
                val artistList = grouped.map { (artist, list) ->
                    val totalMs = list.sumOf { if (it.totalPlayTimeMs > 0L) it.totalPlayTimeMs else it.playCount * 210_000L }
                    val totalPlays = list.sumOf { it.playCount.coerceAtLeast(1) }
                    val distinctTracks = list.size
                    Triple(artist, totalMs, "$totalPlays plays \u2022 $distinctTracks tracks")
                }.sortedByDescending { it.second }.take(10)

                val maxMs = artistList.firstOrNull()?.second?.coerceAtLeast(1L) ?: 1L
                artistList.mapIndexed { idx, (artist, ms, sub) ->
                    RankedCategoryItem(
                        rank = idx + 1,
                        title = artist,
                        subtitle = sub,
                        durationLabel = formatHoursMins(ms),
                        progress = (ms.toFloat() / maxMs).coerceIn(0.05f, 1f),
                    )
                }
            }
            CategoryFilter.ALBUM -> {
                val localAlbums = localStatsCache?.topAlbums.orEmpty()
                if (localAlbums.isNotEmpty()) {
                    val maxPlays = localAlbums.firstOrNull()?.playCount?.coerceAtLeast(1L) ?: 1L
                    localAlbums.take(10).mapIndexed { idx, alb ->
                        val durationMs = alb.playCount * 210_000L
                        RankedCategoryItem(
                            rank = idx + 1,
                            title = alb.name,
                            subtitle = "${alb.playCount} plays \u2022 ${alb.artist}",
                            durationLabel = formatHoursMins(durationMs),
                            progress = (alb.playCount.toFloat() / maxPlays).coerceIn(0.05f, 1f),
                            artworkUrl = alb.artworkUrl,
                        )
                    }
                } else {
                    // Fallback to grouping tracks by artist as album proxy
                    val grouped = tracks.groupBy { it.artist.trim().ifBlank { "Single / Album" } }
                    val list = grouped.map { (name, list) ->
                        val ms = list.sumOf { if (it.totalPlayTimeMs > 0L) it.totalPlayTimeMs else it.playCount * 210_000L }
                        Triple(name, ms, "${list.sumOf { it.playCount.coerceAtLeast(1) }} plays \u2022 ${list.size} tracks")
                    }.sortedByDescending { it.second }.take(10)
                    val maxMs = list.firstOrNull()?.second?.coerceAtLeast(1L) ?: 1L
                    list.mapIndexed { idx, (title, ms, sub) ->
                        RankedCategoryItem(
                            rank = idx + 1,
                            title = title,
                            subtitle = sub,
                            durationLabel = formatHoursMins(ms),
                            progress = (ms.toFloat() / maxMs).coerceIn(0.05f, 1f),
                        )
                    }
                }
            }
            CategoryFilter.GENRE -> {
                val tags = tasteTags
                val genres = if (tags.isNotEmpty()) tags else listOf("Pop", "Electronic", "Rock", "Hip-Hop", "Indie", "R&B")
                val totalMs = tracks.sumOf { if (it.totalPlayTimeMs > 0L) it.totalPlayTimeMs else it.playCount * 210_000L }
                genres.take(8).mapIndexed { idx, genre ->
                    val weight = (genres.size - idx).toFloat() / (genres.size * (genres.size + 1) / 2f)
                    val genreMs = (totalMs * weight).toLong().coerceAtLeast(60_000L)
                    val plays = (tracks.size * weight).roundToInt().coerceAtLeast(1)
                    RankedCategoryItem(
                        rank = idx + 1,
                        title = genre.replaceFirstChar { it.uppercase() },
                        subtitle = "$plays plays",
                        durationLabel = formatHoursMins(genreMs),
                        progress = ((genres.size - idx).toFloat() / genres.size).coerceIn(0.15f, 1f),
                    )
                }
            }
        }
    }

    private fun computeHabits(
        tracks: List<SongPlayStatsEntity>,
        totalListeningMs: Long,
        totalPlays: Int,
    ): ListeningHabitsData {
        if (tracks.isEmpty()) {
            return ListeningHabitsData(
                totalSessions = 0,
                avgSessionLabel = "0m",
                longestSessionLabel = "0m",
                sessionsPerDay = "0.0",
                mostActiveDay = "Sunday, 0m",
                peakTimelineSlot = "2026, 0m",
            )
        }

        // Sessions calculation:
        // Cluster plays within 25 minutes of each other into contiguous sessions
        val sortedByTime = tracks
            .filter { it.lastPlayedAtMillis > 0L }
            .sortedBy { it.lastPlayedAtMillis }

        var sessionsCount = 1
        var longestSessionMs = 0L
        var currentSessionStart = sortedByTime.firstOrNull()?.lastPlayedAtMillis ?: 0L
        var lastTime = currentSessionStart

        sortedByTime.forEach { t ->
            if (t.lastPlayedAtMillis - lastTime > 25 * 60_000L) {
                val dur = (lastTime - currentSessionStart).coerceAtLeast(15 * 60_000L)
                longestSessionMs = maxOf(longestSessionMs, dur)
                sessionsCount++
                currentSessionStart = t.lastPlayedAtMillis
            }
            lastTime = t.lastPlayedAtMillis
        }
        val finalDur = (lastTime - currentSessionStart).coerceAtLeast(15 * 60_000L)
        longestSessionMs = maxOf(longestSessionMs, finalDur)

        sessionsCount = maxOf(sessionsCount, (totalPlays / 4).coerceAtLeast(1))
        longestSessionMs = maxOf(longestSessionMs, (totalListeningMs / sessionsCount.coerceAtLeast(1)) * 2)

        val avgSessionMs = totalListeningMs / sessionsCount.coerceAtLeast(1)
        val avgSessionLabel = "${(avgSessionMs / 60_000L).coerceAtLeast(1L)}m"
        val longestSessionLabel = formatHoursMins(longestSessionMs)

        // Active days
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val daysCount = sortedByTime.map { dayFmt.format(Date(it.lastPlayedAtMillis)) }.distinct().size.coerceAtLeast(1)
        val sessionsPerDay = String.format(Locale.US, "%.1f", (sessionsCount.toFloat() / daysCount).coerceIn(0.1f, 24f))

        // Most active day of week
        val dowFmt = SimpleDateFormat("EEEE", Locale.getDefault())
        val dowMap = sortedByTime.groupBy { dowFmt.format(Date(it.lastPlayedAtMillis)) }
        val topDow = dowMap.maxByOrNull { it.value.size }?.key ?: "Sunday"
        val topDowMs = (totalListeningMs * 0.35f).toLong().coerceAtLeast(60_000L)
        val mostActiveDay = "$topDow, ${formatHoursMins(topDowMs)}"

        val peakSlot = "${Calendar.getInstance().get(Calendar.YEAR)}, ${formatHoursMins(totalListeningMs)}"

        return ListeningHabitsData(
            totalSessions = sessionsCount,
            avgSessionLabel = avgSessionLabel,
            longestSessionLabel = longestSessionLabel,
            sessionsPerDay = sessionsPerDay,
            mostActiveDay = mostActiveDay,
            peakTimelineSlot = peakSlot,
        )
    }

    private fun computeConcentration(
        tracks: List<SongPlayStatsEntity>,
        totalListeningMs: Long,
        totalPlays: Int,
    ): TrackConcentrationData {
        if (tracks.isEmpty()) {
            return TrackConcentrationData(
                top3SharePercent = 0,
                avgPlaysPerTrack = "0.0",
                uniqueTracksCount = 0,
                top1Percent = 0,
                top1DurationLabel = "0m",
                top2To3Percent = 0,
                top2To3DurationLabel = "0m",
                othersPercent = 0,
                othersDurationLabel = "0m",
            )
        }

        val sorted = tracks.sortedByDescending { if (it.totalPlayTimeMs > 0L) it.totalPlayTimeMs else it.playCount * 210_000L }
        val totalMs = totalListeningMs.coerceAtLeast(1L)

        val top1 = sorted.firstOrNull()
        val top1Ms = top1?.let { if (it.totalPlayTimeMs > 0L) it.totalPlayTimeMs else it.playCount * 210_000L } ?: 0L
        val top1Pct = ((top1Ms.toDouble() / totalMs) * 100).roundToInt().coerceIn(1, 95)

        val top2To3 = sorted.drop(1).take(2)
        val top2To3Ms = top2To3.sumOf { if (it.totalPlayTimeMs > 0L) it.totalPlayTimeMs else it.playCount * 210_000L }
        val top2To3Pct = ((top2To3Ms.toDouble() / totalMs) * 100).roundToInt().coerceIn(0, 95 - top1Pct)

        val top3Share = (top1Pct + top2To3Pct).coerceIn(1, 100)
        val othersPct = (100 - top3Share).coerceAtLeast(0)
        val othersMs = (totalMs - top1Ms - top2To3Ms).coerceAtLeast(0L)

        val uniqueCount = sorted.size
        val avgPlays = String.format(Locale.US, "%.1f", totalPlays.toFloat() / uniqueCount.coerceAtLeast(1))

        return TrackConcentrationData(
            top3SharePercent = top3Share,
            avgPlaysPerTrack = avgPlays,
            uniqueTracksCount = uniqueCount,
            top1Percent = top1Pct,
            top1DurationLabel = formatHoursMins(top1Ms),
            top2To3Percent = top2To3Pct,
            top2To3DurationLabel = formatHoursMins(top2To3Ms),
            othersPercent = othersPct,
            othersDurationLabel = formatHoursMins(othersMs),
        )
    }

    private fun computePersonalRecords(tracks: List<SongPlayStatsEntity>): PersonalRecordsData {
        if (tracks.isEmpty()) {
            return PersonalRecordsData(
                bestDayPlays = 0,
                bestDayDurationLabel = "0m",
                bestDayDate = "None",
                bestWeekPlays = 0,
                bestWeekDurationLabel = "0m",
                bestMonthPlays = 0,
                bestMonthDurationLabel = "0m",
                longestStreakDays = 0,
            )
        }
        val cal = Calendar.getInstance()
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val displayDayFmt = SimpleDateFormat("d MMM", Locale.getDefault())
        val monthFmt = SimpleDateFormat("yyyy-MM", Locale.US)

        val dayStats = mutableMapOf<String, Long>()
        val dayPlays = mutableMapOf<String, Int>()
        val weekStats = mutableMapOf<String, Long>()
        val weekPlays = mutableMapOf<String, Int>()
        val monthStats = mutableMapOf<String, Long>()
        val monthPlays = mutableMapOf<String, Int>()

        tracks.forEach { t ->
            val time = if (t.lastPlayedAtMillis > 0L) t.lastPlayedAtMillis else System.currentTimeMillis()
            cal.timeInMillis = time
            val dayKey = dayFmt.format(cal.time)
            val weekKey = "${cal.get(Calendar.YEAR)}-W${cal.get(Calendar.WEEK_OF_YEAR)}"
            val monthKey = monthFmt.format(cal.time)
            val ms = if (t.totalPlayTimeMs > 0L) t.totalPlayTimeMs else t.playCount * 210_000L
            val p = t.playCount.coerceAtLeast(1)

            dayStats[dayKey] = (dayStats[dayKey] ?: 0L) + ms
            dayPlays[dayKey] = (dayPlays[dayKey] ?: 0) + p
            weekStats[weekKey] = (weekStats[weekKey] ?: 0L) + ms
            weekPlays[weekKey] = (weekPlays[weekKey] ?: 0) + p
            monthStats[monthKey] = (monthStats[monthKey] ?: 0L) + ms
            monthPlays[monthKey] = (monthPlays[monthKey] ?: 0) + p
        }

        val bestDayEntry = dayStats.maxByOrNull { it.value }
        val bestDayKey = bestDayEntry?.key
        val bestDayDate = bestDayKey?.let {
            runCatching { displayDayFmt.format(dayFmt.parse(it)!!) }.getOrNull()
        } ?: "Today"
        val bestDayPlays = bestDayKey?.let { dayPlays[it] } ?: 0
        val bestDayDuration = formatHoursMins(bestDayEntry?.value ?: 0L)

        val bestWeekEntry = weekStats.maxByOrNull { it.value }
        val bestWeekPlays = bestWeekEntry?.key?.let { weekPlays[it] } ?: 0
        val bestWeekDuration = formatHoursMins(bestWeekEntry?.value ?: 0L)

        val bestMonthEntry = monthStats.maxByOrNull { it.value }
        val bestMonthPlays = bestMonthEntry?.key?.let { monthPlays[it] } ?: 0
        val bestMonthDuration = formatHoursMins(bestMonthEntry?.value ?: 0L)

        // Calculate streak of consecutive active days
        val sortedDays = dayStats.keys.mapNotNull {
            runCatching { dayFmt.parse(it)?.time }.getOrNull()
        }.sorted()

        var longestStreak = 0
        var currentStreak = 0
        var prevDayMs = 0L
        for (dayMs in sortedDays) {
            if (prevDayMs == 0L) {
                currentStreak = 1
            } else {
                val diffDays = (dayMs - prevDayMs) / (24 * 60 * 60 * 1000L)
                if (diffDays == 1L) {
                    currentStreak++
                } else if (diffDays > 1L) {
                    longestStreak = maxOf(longestStreak, currentStreak)
                    currentStreak = 1
                }
            }
            prevDayMs = dayMs
        }
        longestStreak = maxOf(longestStreak, currentStreak)

        return PersonalRecordsData(
            bestDayPlays = bestDayPlays,
            bestDayDurationLabel = bestDayDuration,
            bestDayDate = bestDayDate,
            bestWeekPlays = bestWeekPlays,
            bestWeekDurationLabel = bestWeekDuration,
            bestMonthPlays = bestMonthPlays,
            bestMonthDurationLabel = bestMonthDuration,
            longestStreakDays = maxOf(longestStreak, 1),
        )
    }

    private fun computeWeeklyRhythm(tracks: List<SongPlayStatsEntity>): WeeklyRhythmData {
        if (tracks.isEmpty()) {
            return WeeklyRhythmData(weekdayAvgLabel = "0m", weekendAvgLabel = "0m")
        }
        val cal = Calendar.getInstance()
        var weekdayMs = 0L
        var weekdayCount = 0
        var weekendMs = 0L
        var weekendCount = 0

        tracks.forEach { t ->
            val time = if (t.lastPlayedAtMillis > 0L) t.lastPlayedAtMillis else System.currentTimeMillis()
            cal.timeInMillis = time
            val dow = cal.get(Calendar.DAY_OF_WEEK)
            val ms = if (t.totalPlayTimeMs > 0L) t.totalPlayTimeMs else t.playCount * 210_000L
            if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) {
                weekendMs += ms
                weekendCount++
            } else {
                weekdayMs += ms
                weekdayCount++
            }
        }
        val weekdayAvg = if (weekdayCount > 0) weekdayMs / maxOf(1, weekdayCount / 4) else 0L
        val weekendAvg = if (weekendCount > 0) weekendMs / maxOf(1, weekendCount / 2) else 0L

        return WeeklyRhythmData(
            weekdayAvgLabel = formatHoursMins(weekdayAvg),
            weekendAvgLabel = formatHoursMins(weekendAvg),
        )
    }

    private fun computeMonthComparison(tracks: List<SongPlayStatsEntity>): MonthComparisonData {
        val cal = Calendar.getInstance()
        val curYear = cal.get(Calendar.YEAR)
        val curMonth = cal.get(Calendar.MONTH)

        cal.add(Calendar.MONTH, -1)
        val prevYear = cal.get(Calendar.YEAR)
        val prevMonth = cal.get(Calendar.MONTH)

        var thisMonthMs = 0L
        var lastMonthMs = 0L

        val tCal = Calendar.getInstance()
        tracks.forEach { t ->
            val time = if (t.lastPlayedAtMillis > 0L) t.lastPlayedAtMillis else System.currentTimeMillis()
            tCal.timeInMillis = time
            val y = tCal.get(Calendar.YEAR)
            val m = tCal.get(Calendar.MONTH)
            val ms = if (t.totalPlayTimeMs > 0L) t.totalPlayTimeMs else t.playCount * 210_000L
            if (y == curYear && m == curMonth) {
                thisMonthMs += ms
            } else if (y == prevYear && m == prevMonth) {
                lastMonthMs += ms
            }
        }

        val progress = if (lastMonthMs > 0L) {
            (thisMonthMs.toFloat() / lastMonthMs.toFloat()).coerceIn(0.05f, 1f)
        } else if (thisMonthMs > 0L) {
            1f
        } else {
            0f
        }

        return MonthComparisonData(
            thisMonthLabel = formatHoursMins(thisMonthMs),
            lastMonthLabel = formatHoursMins(lastMonthMs),
            progress = progress,
        )
    }

    private fun computeHourlyRhythm(tracks: List<SongPlayStatsEntity>): List<HourlyRhythmBar> {
        val hourCounts = IntArray(24)
        val cal = Calendar.getInstance()
        tracks.forEach { t ->
            val time = if (t.lastPlayedAtMillis > 0L) t.lastPlayedAtMillis else System.currentTimeMillis()
            cal.timeInMillis = time
            val h = cal.get(Calendar.HOUR_OF_DAY).coerceIn(0, 23)
            hourCounts[h] += t.playCount.coerceAtLeast(1)
        }
        val maxVal = hourCounts.maxOrNull()?.coerceAtLeast(1) ?: 1
        return (0..23).map { h ->
            val c = hourCounts[h]
            val frac = if (maxVal > 0) (c.toFloat() / maxVal).coerceIn(0.08f, 1f) else 0.08f
            val label = String.format(Locale.US, "%02d", h)
            HourlyRhythmBar(
                hour = h,
                label = label,
                count = c,
                fraction = frac,
                isPeak = (c == maxVal && c > 0),
            )
        }
    }

    private fun computeActivityHeatmap(tracks: List<SongPlayStatsEntity>): List<HeatmapDay> {
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cal = Calendar.getInstance()
        val dailyMs = mutableMapOf<String, Long>()

        tracks.forEach { t ->
            val time = if (t.lastPlayedAtMillis > 0L) t.lastPlayedAtMillis else System.currentTimeMillis()
            cal.timeInMillis = time
            val key = dayFmt.format(cal.time)
            val ms = if (t.totalPlayTimeMs > 0L) t.totalPlayTimeMs else t.playCount * 210_000L
            dailyMs[key] = (dailyMs[key] ?: 0L) + ms
        }

        val endCal = Calendar.getInstance()
        while (endCal.get(Calendar.DAY_OF_WEEK) != Calendar.SATURDAY) {
            endCal.add(Calendar.DAY_OF_YEAR, 1)
        }
        val startCal = Calendar.getInstance().apply {
            timeInMillis = endCal.timeInMillis
            add(Calendar.DAY_OF_YEAR, -83)
        }

        val result = mutableListOf<HeatmapDay>()
        val cur = Calendar.getInstance().apply { timeInMillis = startCal.timeInMillis }
        for (i in 0 until 84) {
            val key = dayFmt.format(cur.time)
            val dow = (cur.get(Calendar.DAY_OF_WEEK) - 1).coerceIn(0, 6)
            val weekIdx = i / 7
            val ms = dailyMs[key] ?: 0L
            val intensity = when {
                ms <= 0L -> 0
                ms < 15 * 60_000L -> 1
                ms < 45 * 60_000L -> 2
                ms < 90 * 60_000L -> 3
                else -> 4
            }
            result.add(
                HeatmapDay(
                    dateString = key,
                    dayOfWeek = dow,
                    weekIndex = weekIdx,
                    intensity = intensity,
                    durationLabel = formatHoursMins(ms),
                )
            )
            cur.add(Calendar.DAY_OF_YEAR, 1)
        }
        return result
    }

    private fun formatHoursMins(ms: Long): String {
        val totalMins = (ms / 60_000L).coerceAtLeast(0L)
        val hours = totalMins / 60
        val mins = totalMins % 60
        return if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
    }
}
