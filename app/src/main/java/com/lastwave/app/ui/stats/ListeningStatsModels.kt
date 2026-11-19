package com.lastwave.app.ui.stats

import androidx.compose.runtime.Immutable

enum class StatsTimeRange(val label: String) {
    WEEK("Week"),
    MONTH_TO_DATE("Month"),
    YEAR_TO_DATE("Year"),
    ALL_TIME("All Time"),
}

enum class TimelineMetric(val label: String) {
    LISTENING_TIME("Listening time"),
    PLAY_COUNT("Play count"),
    AVG_SESSION("Avg. session"),
}

enum class CategoryFilter(val label: String) {
    SONG("Song"),
    ALBUM("Album"),
    ARTIST("Artist"),
    GENRE("Genre"),
}

@Immutable
data class TimelineBar(
    val label: String,
    val value: Float,
    val valueLabel: String,
    val isPeak: Boolean = false,
)

@Immutable
data class RankedCategoryItem(
    val rank: Int,
    val title: String,
    val subtitle: String,
    val durationLabel: String,
    val progress: Float,
    val artworkUrl: String? = null,
)

@Immutable
data class ListeningHabitsData(
    val totalSessions: Int,
    val avgSessionLabel: String,
    val longestSessionLabel: String,
    val sessionsPerDay: String,
    val mostActiveDay: String,
    val peakTimelineSlot: String,
)

@Immutable
data class TrackConcentrationData(
    val top3SharePercent: Int,
    val avgPlaysPerTrack: String,
    val uniqueTracksCount: Int,
    val top1Percent: Int,
    val top1DurationLabel: String,
    val top2To3Percent: Int,
    val top2To3DurationLabel: String,
    val othersPercent: Int,
    val othersDurationLabel: String,
)

@Immutable
data class RankedTrackItem(
    val rank: Int,
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    val playCount: Int,
    val durationLabel: String,
    val progress: Float,
)

@Immutable
data class PersonalRecordsData(
    val bestDayPlays: Int,
    val bestDayDurationLabel: String,
    val bestDayDate: String,
    val bestWeekPlays: Int,
    val bestWeekDurationLabel: String,
    val bestMonthPlays: Int,
    val bestMonthDurationLabel: String,
    val longestStreakDays: Int,
)

@Immutable
data class WeeklyRhythmData(
    val weekdayAvgLabel: String,
    val weekendAvgLabel: String,
)

@Immutable
data class MonthComparisonData(
    val thisMonthLabel: String,
    val lastMonthLabel: String,
    val progress: Float,
)

@Immutable
data class HourlyRhythmBar(
    val hour: Int,
    val label: String,
    val count: Int,
    val fraction: Float,
    val isPeak: Boolean = false,
)

@Immutable
data class HeatmapDay(
    val dateString: String,
    val dayOfWeek: Int,
    val weekIndex: Int,
    val intensity: Int,
    val durationLabel: String,
)

@Immutable
data class ListeningStatsUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val selectedRange: StatsTimeRange = StatsTimeRange.ALL_TIME,
    val selectedTimelineMetric: TimelineMetric = TimelineMetric.LISTENING_TIME,
    val selectedCategory: CategoryFilter = CategoryFilter.SONG,
    val totalListeningLabel: String = "0m",
    val totalPlays: Int = 0,
    val timelineBars: List<TimelineBar> = emptyList(),
    val peakSegmentName: String = "",
    val peakSegmentDuration: String = "",
    val categoryItems: List<RankedCategoryItem> = emptyList(),
    val habits: ListeningHabitsData? = null,
    val concentration: TrackConcentrationData? = null,
    val tracksInRange: List<RankedTrackItem> = emptyList(),
    val records: PersonalRecordsData? = null,
    val weeklyRhythm: WeeklyRhythmData? = null,
    val monthComparison: MonthComparisonData? = null,
    val hourlyRhythm: List<HourlyRhythmBar> = emptyList(),
    val activityHeatmap: List<HeatmapDay> = emptyList(),
)
