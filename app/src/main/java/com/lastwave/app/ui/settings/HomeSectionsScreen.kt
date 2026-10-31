package com.lastwave.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lastwave.app.R
import com.lastwave.app.data.local.SettingsPreferences
import com.lastwave.app.ui.common.ExpressiveHeader
import com.lastwave.app.ui.common.adaptiveContentWidth
import com.lastwave.app.ui.common.safeDrawingBottomPadding
import com.lastwave.app.ui.common.safeHorizontalContentPadding
import com.lastwave.app.ui.player.LocalMiniPlayerScrollClearance
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

val DEFAULT_YT_MUSIC_SECTIONS = listOf(
    "Fresh finds, old favorites",
    "Listen again",
    "Forgotten favorites",
    "Listen together",
    "Mixes to explore",
    "Favourite to revisit",
    "Album for you",
    "Cover and remixes",
    "Recaps",
    "Albums for you",
    "From the community",
    "Heard in shorts",
    "From your library",
    "Your daily discover",
    "Featured playlist for you",
    "Live performances",
    "Similar to",
    "Recommended music videos",
    "COMMUNITY PLAYLISTS",
    "Energy boosters",
    "Feel good",
    "Workout",
    "Relax",
    "Focus",
    "Commute",
    "Party",
    "Romance",
    "Sleep",
)

data class UniversalSectionItem(
    val id: String,
    val titleRes: Int? = null,
    val rawTitle: String? = null,
    val subtitleRes: Int? = null,
    val rawSubtitle: String? = null,
    val isYouTube: Boolean = false,
    val icon: ImageVector,
)

val ALL_DEFAULT_UNIVERSAL_SECTIONS: List<UniversalSectionItem> = listOf(
    UniversalSectionItem("hero_greeting", rawTitle = "Greeting header", rawSubtitle = "Daily greeting and current date", isYouTube = false, icon = Icons.Filled.LightMode),
    UniversalSectionItem("infinite_radio", rawTitle = "Infinite radio", rawSubtitle = "Start instant radio based on taste", isYouTube = false, icon = Icons.Filled.Radio),
    UniversalSectionItem("quick_tiles", titleRes = R.string.home_section_quick_tiles_title, subtitleRes = R.string.home_section_quick_tiles_sub, isYouTube = false, icon = Icons.Filled.Dashboard),
    UniversalSectionItem("taste_strip", titleRes = R.string.home_section_taste_strip_title, subtitleRes = R.string.home_section_taste_strip_sub, isYouTube = false, icon = Icons.Filled.Sell),
    UniversalSectionItem("discover_new_music", rawTitle = "Discover new music", rawSubtitle = "Rotates fresh songs on refresh", isYouTube = false, icon = Icons.Filled.Explore),
    UniversalSectionItem("quick_picks", titleRes = R.string.home_section_quick_picks_title, subtitleRes = R.string.home_section_quick_picks_sub, isYouTube = false, icon = Icons.Filled.Bolt),
    UniversalSectionItem("because_you_listen_to", titleRes = R.string.home_section_because_title, subtitleRes = R.string.home_section_because_sub, isYouTube = false, icon = Icons.Filled.History),
    UniversalSectionItem("fresh_finds", titleRes = R.string.home_section_fresh_title, subtitleRes = R.string.home_section_fresh_sub, isYouTube = false, icon = Icons.Filled.Whatshot),
    UniversalSectionItem("yt:Fresh finds, old favorites", rawTitle = "Fresh finds, old & favorites", rawSubtitle = "YouTube Music Section", isYouTube = true, icon = Icons.Filled.Whatshot),
    UniversalSectionItem("yt:Listen again", rawTitle = "Listen again", rawSubtitle = "YouTube Music Section", isYouTube = true, icon = Icons.Filled.Replay),
    UniversalSectionItem("jump_back_in", titleRes = R.string.home_section_jump_title, subtitleRes = R.string.home_section_jump_sub, isYouTube = false, icon = Icons.Filled.Replay),
    UniversalSectionItem("yt:Forgotten favorites", rawTitle = "Forgotten favorites", rawSubtitle = "YouTube Music Section", isYouTube = true, icon = Icons.Filled.Favorite),
    UniversalSectionItem("yt:Listen together", rawTitle = "Listen together", rawSubtitle = "YouTube Music Section", isYouTube = true, icon = Icons.Filled.Group),
    UniversalSectionItem("yt:Mixes to explore", rawTitle = "Mixes to explore", rawSubtitle = "YouTube Music Section", isYouTube = true, icon = Icons.Filled.Shuffle),
    UniversalSectionItem("spotlight_hero", titleRes = R.string.home_section_spotlight_title, subtitleRes = R.string.home_section_spotlight_sub, isYouTube = false, icon = Icons.Filled.Star),
    UniversalSectionItem("top_artists", titleRes = R.string.home_section_artists_title, subtitleRes = R.string.home_section_artists_sub, isYouTube = false, icon = Icons.Filled.People),
    UniversalSectionItem("yt:Favourite to revisit", rawTitle = "Favourite to revisit", rawSubtitle = "YouTube Music Section", isYouTube = true, icon = Icons.Filled.Favorite),
    UniversalSectionItem("heavy_rotation", titleRes = R.string.home_section_heavy_title, subtitleRes = R.string.home_section_heavy_sub, isYouTube = false, icon = Icons.Filled.Favorite),
    UniversalSectionItem("albums_in_rotation", titleRes = R.string.home_section_albums_title, subtitleRes = R.string.home_section_albums_sub, isYouTube = false, icon = Icons.Filled.Album),
    UniversalSectionItem("yt:Album for you", rawTitle = "Album for you", rawSubtitle = "YouTube Music Section", isYouTube = true, icon = Icons.Filled.Album),
    UniversalSectionItem("new_releases", titleRes = R.string.home_section_releases_title, subtitleRes = R.string.home_section_releases_sub, isYouTube = false, icon = Icons.Filled.NewReleases),
    UniversalSectionItem("yt:Cover and remixes", rawTitle = "Cover and remixes", rawSubtitle = "YouTube Music Section", isYouTube = true, icon = Icons.Filled.GraphicEq),
    UniversalSectionItem("yt:Recaps", rawTitle = "Recaps", rawSubtitle = "YouTube Music Section", isYouTube = true, icon = Icons.Filled.BarChart),
    UniversalSectionItem("yt:Albums for you", rawTitle = "Albums for you", rawSubtitle = "YouTube Music Section", isYouTube = true, icon = Icons.Filled.Album),
    UniversalSectionItem("trending_charts", titleRes = R.string.home_section_charts_title, subtitleRes = R.string.home_section_charts_sub, isYouTube = false, icon = Icons.Filled.TrendingUp),
    UniversalSectionItem("yt:From the community", rawTitle = "From the community", rawSubtitle = "YouTube Music Section", isYouTube = true, icon = Icons.Filled.Group),
    UniversalSectionItem("yt:Heard in shorts", rawTitle = "Heard in shorts", rawSubtitle = "YouTube Music Section", isYouTube = true, icon = Icons.Filled.SmartDisplay),
    UniversalSectionItem("yt:From your library", rawTitle = "From your library", rawSubtitle = "YouTube Music Section", isYouTube = true, icon = Icons.Filled.LibraryMusic),
    UniversalSectionItem("mixed_for_you", titleRes = R.string.home_section_mixes_title, subtitleRes = R.string.home_section_mixes_sub, isYouTube = false, icon = Icons.Filled.Shuffle),
    UniversalSectionItem("yt:Your daily discover", rawTitle = "Your daily discover", rawSubtitle = "YouTube Music Section", isYouTube = true, icon = Icons.Filled.AutoAwesome),
    UniversalSectionItem("yt:Featured playlist for you", rawTitle = "Featured playlist for you", rawSubtitle = "YouTube Music Section", isYouTube = true, icon = Icons.Filled.QueueMusic),
    UniversalSectionItem("yt:Live performances", rawTitle = "Live performances", rawSubtitle = "YouTube Music Section", isYouTube = true, icon = Icons.Filled.Headphones),
    UniversalSectionItem("friends_activity", titleRes = R.string.home_section_friends_title, subtitleRes = R.string.home_section_friends_sub, isYouTube = false, icon = Icons.Filled.Group),
    UniversalSectionItem("podcast_shelf", rawTitle = "Podcasts", rawSubtitle = "Popular podcast shows", isYouTube = false, icon = Icons.Filled.Mic),
    // Extra default YTM sections
    UniversalSectionItem("yt:Similar to", rawTitle = "Similar to", rawSubtitle = "YouTube Music Section", isYouTube = true, icon = Icons.Filled.Subscriptions),
    UniversalSectionItem("yt:Recommended music videos", rawTitle = "Recommended music videos", rawSubtitle = "YouTube Music Section", isYouTube = true, icon = Icons.Filled.SmartDisplay),
    UniversalSectionItem("yt:Energy boosters", rawTitle = "Energy boosters", rawSubtitle = "YouTube Music Section", isYouTube = true, icon = Icons.Filled.Bolt),
    UniversalSectionItem("yt:Feel good", rawTitle = "Feel good", rawSubtitle = "YouTube Music Section", isYouTube = true, icon = Icons.Filled.Favorite),
    UniversalSectionItem("yt:Workout", rawTitle = "Workout", rawSubtitle = "YouTube Music Section", isYouTube = true, icon = Icons.Filled.Whatshot),
    UniversalSectionItem("yt:Relax", rawTitle = "Relax", rawSubtitle = "YouTube Music Section", isYouTube = true, icon = Icons.Filled.Headphones),
    UniversalSectionItem("yt:Focus", rawTitle = "Focus", rawSubtitle = "YouTube Music Section", isYouTube = true, icon = Icons.Filled.GraphicEq),
    UniversalSectionItem("yt:Commute", rawTitle = "Commute", rawSubtitle = "YouTube Music Section", isYouTube = true, icon = Icons.Filled.Radio),
    UniversalSectionItem("yt:Party", rawTitle = "Party", rawSubtitle = "YouTube Music Section", isYouTube = true, icon = Icons.Filled.AutoAwesome),
    UniversalSectionItem("yt:Romance", rawTitle = "Romance", rawSubtitle = "YouTube Music Section", isYouTube = true, icon = Icons.Filled.Favorite),
    UniversalSectionItem("yt:Sleep", rawTitle = "Sleep", rawSubtitle = "YouTube Music Section", isYouTube = true, icon = Icons.Filled.Headphones),
)

@HiltViewModel
class HomeSectionsViewModel @Inject constructor(
    private val settingsPreferences: SettingsPreferences,
) : ViewModel() {
    data class UiState(
        val sections: List<UniversalSectionItem> = ALL_DEFAULT_UNIVERSAL_SECTIONS,
        val hiddenSections: Set<String> = emptySet(),
        val pinnedSections: Set<String> = emptySet(),
        val isLoaded: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val settings = settingsPreferences.settings.first()
            val hiddenInApp = settings.hiddenHomeSections
            val ytHidden = settings.ytMusicHiddenSections
            val storedOrder = settings.homeSectionsOrder
            val pinned = settings.pinnedHomeSections
            val allHidden = hiddenInApp + ytHidden.map { "yt:$it" }.toSet()

            val defaultList = ALL_DEFAULT_UNIVERSAL_SECTIONS
            val orderedList = if (storedOrder.isEmpty()) {
                defaultList
            } else {
                val itemMap = defaultList.associateBy { it.id }
                val list = storedOrder.mapNotNull { itemMap[it] }.toMutableList()
                for (defaultItem in defaultList) {
                    if (list.none { it.id == defaultItem.id }) {
                        list.add(defaultItem)
                    }
                }
                list
            }

            _uiState.value = UiState(
                sections = orderedList,
                hiddenSections = allHidden,
                pinnedSections = pinned,
                isLoaded = true,
            )
        }
    }

    fun moveSection(fromIndex: Int, toIndex: Int) {
        val current = _uiState.value
        val list = current.sections.toMutableList()
        if (fromIndex !in list.indices || toIndex !in list.indices) return
        val item = list.removeAt(fromIndex)
        list.add(toIndex, item)
        _uiState.update { it.copy(sections = list) }
        saveState(list, current.hiddenSections, current.pinnedSections)
    }

    fun toggleVisibility(id: String, visible: Boolean) {
        val current = _uiState.value
        val newHidden = if (visible) current.hiddenSections - id else current.hiddenSections + id
        _uiState.update { it.copy(hiddenSections = newHidden) }
        saveState(current.sections, newHidden, current.pinnedSections)
    }

    fun togglePin(id: String) {
        val current = _uiState.value
        val isCurrentlyPinned = id in current.pinnedSections
        val newPinned = if (isCurrentlyPinned) current.pinnedSections - id else current.pinnedSections + id
        val list = current.sections.toMutableList()
        if (!isCurrentlyPinned) {
            val idx = list.indexOfFirst { it.id == id }
            if (idx > 0) {
                val item = list.removeAt(idx)
                list.add(0, item)
            }
        }
        _uiState.update { it.copy(sections = list, pinnedSections = newPinned) }
        saveState(list, current.hiddenSections, newPinned)
    }

    fun showAll() {
        val current = _uiState.value
        _uiState.update { it.copy(hiddenSections = emptySet()) }
        saveState(current.sections, emptySet(), current.pinnedSections)
    }

    fun resetAll() {
        val defaultList = ALL_DEFAULT_UNIVERSAL_SECTIONS
        _uiState.value = UiState(
            sections = defaultList,
            hiddenSections = emptySet(),
            pinnedSections = emptySet(),
            isLoaded = true,
        )
        viewModelScope.launch {
            settingsPreferences.resetUniversalHomeSections()
        }
    }

    private fun saveState(sections: List<UniversalSectionItem>, hidden: Set<String>, pinned: Set<String>) {
        viewModelScope.launch {
            settingsPreferences.saveUniversalHomeSections(
                order = sections.map { it.id },
                hidden = hidden,
                pinned = pinned,
            )
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HomeSectionsScreen(
    onBack: () -> Unit,
    viewModel: HomeSectionsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val visibleCount = uiState.sections.size - uiState.hiddenSections.size

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .adaptiveContentWidth(maxWidth = 860.dp)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        ) {
            ExpressiveHeader(
                title = stringResource(R.string.settings_home_sections),
                subtitle = stringResource(
                    R.string.home_sections_visible,
                    visibleCount,
                    uiState.sections.size,
                ),
                onBack = onBack,
                actions = {
                    TextButton(onClick = {
                        viewModel.showAll()
                    }, enabled = uiState.hiddenSections.isNotEmpty()) {
                        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Show all")
                    }
                    TextButton(onClick = {
                        viewModel.resetAll()
                    }) {
                        Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Reset")
                    }
                },
            )

            Text(
                text = "Reorder sections up/down, pin favorites to top, and toggle visibility for both LastWave and YouTube Music shelves.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )

            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 4.dp,
                    bottom = 32.dp + LocalMiniPlayerScrollClearance.current + safeDrawingBottomPadding(),
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize().safeHorizontalContentPadding(),
            ) {
                itemsIndexed(uiState.sections, key = { _, item -> item.id }) { index, item ->
                    val isVisible = item.id !in uiState.hiddenSections
                    val isItemPinned = item.id in uiState.pinnedSections

                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isItemPinned) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Section Icon
                            Surface(
                                shape = CircleShape,
                                color = if (item.isYouTube) {
                                    MaterialTheme.colorScheme.tertiaryContainer
                                } else {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } ,
                                modifier = Modifier.size(40.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = item.icon,
                                        contentDescription = null,
                                        tint = if (item.isYouTube) {
                                            MaterialTheme.colorScheme.onTertiaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSecondaryContainer
                                        },
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }

                            Spacer(Modifier.width(12.dp))

                            // Title & Subtitle
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = item.titleRes?.let { stringResource(it) } ?: item.rawTitle.orEmpty(),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isItemPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    )
                                    if (item.isYouTube) {
                                        Spacer(Modifier.width(6.dp))
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f),
                                            modifier = Modifier.padding(vertical = 1.dp),
                                        ) {
                                            Text(
                                                text = "YT",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.tertiary,
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = item.subtitleRes?.let { stringResource(it) } ?: item.rawSubtitle.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            // Reorder: Up / Down buttons
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = {
                                        if (index > 0) {
                                            viewModel.moveSection(index, index - 1)
                                        }
                                    },
                                    enabled = index > 0,
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.KeyboardArrowUp,
                                        contentDescription = "Move Up",
                                        tint = if (index > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        if (index < uiState.sections.size - 1) {
                                            viewModel.moveSection(index, index + 1)
                                        }
                                    },
                                    enabled = index < uiState.sections.size - 1,
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.KeyboardArrowDown,
                                        contentDescription = "Move Down",
                                        tint = if (index < uiState.sections.size - 1) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                        modifier = Modifier.size(20.dp),
                                    )
                                }

                                // Pin Button
                                IconButton(
                                    onClick = {
                                        viewModel.togglePin(item.id)
                                    },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        imageVector = if (isItemPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                                        contentDescription = if (isItemPinned) "Unpin" else "Pin to top",
                                        tint = if (isItemPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }

                                Spacer(Modifier.width(4.dp))

                                // Visibility Switch
                                Switch(
                                    checked = isVisible,
                                    onCheckedChange = { checked ->
                                        viewModel.toggleVisibility(item.id, checked)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
