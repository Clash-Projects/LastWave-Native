package com.lastwave.app.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Shuffle
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.lastwave.app.data.local.HomeSection
import com.lastwave.app.data.local.SettingsPreferences
import com.lastwave.app.ui.common.ExpressiveHeader
import com.lastwave.app.ui.common.adaptiveContentWidth
import com.lastwave.app.ui.common.safeDrawingBottomPadding
import com.lastwave.app.ui.common.safeHorizontalContentPadding
import com.lastwave.app.ui.player.LocalMiniPlayerScrollClearance
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

val DEFAULT_YT_MUSIC_SECTIONS = listOf(
    "Listen again",
    "Quick picks",
    "Mixed for you",
    "From your library",
    "Similar to",
    "Forgotten favorites",
    "Recommended music videos",
    "COMMUNITY PLAYLISTS",
    "Trending",
    "NEW RELEASES",
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

private fun HomeSection.toUniversalItem(): UniversalSectionItem = UniversalSectionItem(
    id = this.id,
    titleRes = this.titleRes,
    subtitleRes = this.subtitleRes,
    isYouTube = false,
    icon = when (this) {
        HomeSection.HERO -> Icons.Filled.Home
        HomeSection.QUICK_TILES -> Icons.Filled.Dashboard
        HomeSection.TASTE_STRIP -> Icons.Filled.Sell
        HomeSection.QUICK_PICKS -> Icons.Filled.Bolt
        HomeSection.BECAUSE_YOU_LISTEN_TO -> Icons.Filled.History
        HomeSection.FRESH_FINDS -> Icons.Filled.Whatshot
        HomeSection.JUMP_BACK_IN -> Icons.Filled.Replay
        HomeSection.MIXES -> Icons.Filled.Shuffle
        HomeSection.SPOTLIGHT -> Icons.Filled.Star
        HomeSection.TOP_ARTISTS -> Icons.Filled.People
        HomeSection.HEAVY_ROTATION -> Icons.Filled.Favorite
        HomeSection.ALBUMS -> Icons.Filled.Album
        HomeSection.CHARTS -> Icons.Filled.TrendingUp
        HomeSection.NEW_RELEASES -> Icons.Filled.NewReleases
        HomeSection.FRIENDS -> Icons.Filled.Group
    },
)

private fun String.toYtUniversalItem(): UniversalSectionItem = UniversalSectionItem(
    id = "yt:$this",
    rawTitle = this,
    rawSubtitle = "YouTube Music Section",
    isYouTube = true,
    icon = when (this.lowercase()) {
        "listen again" -> Icons.Filled.Replay
        "quick picks" -> Icons.Filled.Bolt
        "mixed for you" -> Icons.Filled.Shuffle
        "from your library" -> Icons.Filled.LibraryMusic
        "trending" -> Icons.Filled.TrendingUp
        "new releases" -> Icons.Filled.NewReleases
        "community playlists" -> Icons.Filled.Group
        else -> Icons.Filled.Subscriptions
    },
)

private val ALL_DEFAULT_UNIVERSAL_SECTIONS: List<UniversalSectionItem> by lazy {
    HomeSection.entries.map { it.toUniversalItem() } +
        DEFAULT_YT_MUSIC_SECTIONS.map { it.toYtUniversalItem() }
}

@HiltViewModel
class HomeSectionsViewModel @Inject constructor(
    private val settingsPreferences: SettingsPreferences,
) : ViewModel() {
    val hiddenSections: StateFlow<Set<String>> = settingsPreferences.settings
        .map { it.hiddenHomeSections }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val ytMusicHiddenSections: StateFlow<Set<String>> = settingsPreferences.settings
        .map { it.ytMusicHiddenSections }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val homeSectionsOrder: StateFlow<List<String>> = settingsPreferences.settings
        .map { it.homeSectionsOrder }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pinnedSections: StateFlow<Set<String>> = settingsPreferences.settings
        .map { it.pinnedHomeSections }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun saveUniversalHomeSections(order: List<String>, hidden: Set<String>, pinned: Set<String>) {
        viewModelScope.launch {
            settingsPreferences.saveUniversalHomeSections(order, hidden, pinned)
        }
    }

    fun togglePin(key: String) {
        viewModelScope.launch {
            settingsPreferences.togglePinHomeSection(key)
        }
    }

    fun showAll() {
        viewModelScope.launch {
            settingsPreferences.showAllHomeSections()
            settingsPreferences.resetYtMusicSections()
        }
    }

    fun resetAll() {
        viewModelScope.launch {
            settingsPreferences.resetUniversalHomeSections()
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HomeSectionsScreen(
    onBack: () -> Unit,
    viewModel: HomeSectionsViewModel = hiltViewModel(),
) {
    val hiddenInApp by viewModel.hiddenSections.collectAsStateWithLifecycle()
    val ytHidden by viewModel.ytMusicHiddenSections.collectAsStateWithLifecycle()
    val storedOrder by viewModel.homeSectionsOrder.collectAsStateWithLifecycle()
    val pinned by viewModel.pinnedSections.collectAsStateWithLifecycle()

    val initialSections = remember(storedOrder) {
        val defaultList = ALL_DEFAULT_UNIVERSAL_SECTIONS
        if (storedOrder.isEmpty()) {
            defaultList
        } else {
            val itemMap = defaultList.associateBy { it.id }
            val orderedList = storedOrder.mapNotNull { itemMap[it] }.toMutableList()
            for (defaultItem in defaultList) {
                if (orderedList.none { it.id == defaultItem.id }) {
                    orderedList.add(defaultItem)
                }
            }
            orderedList
        }
    }

    var currentSections by remember(initialSections) { mutableStateOf(initialSections) }

    val allHidden = remember(hiddenInApp, ytHidden) {
        hiddenInApp + ytHidden.map { "yt:$it" }.toSet()
    }
    var currentHidden by remember(allHidden) { mutableStateOf(allHidden) }
    var currentPinned by remember(pinned) { mutableStateOf(pinned) }

    LaunchedEffect(currentSections, currentHidden, currentPinned) {
        viewModel.saveUniversalHomeSections(
            order = currentSections.map { it.id },
            hidden = currentHidden,
            pinned = currentPinned,
        )
    }

    val visibleCount = currentSections.size - currentHidden.size

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
                title = stringResource(com.lastwave.app.R.string.settings_home_sections),
                subtitle = stringResource(
                    com.lastwave.app.R.string.home_sections_visible,
                    visibleCount,
                    currentSections.size,
                ),
                onBack = onBack,
                actions = {
                    TextButton(onClick = {
                        currentHidden = emptySet()
                        viewModel.showAll()
                    }, enabled = currentHidden.isNotEmpty()) {
                        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Show all")
                    }
                    TextButton(onClick = {
                        viewModel.resetAll()
                        currentSections = ALL_DEFAULT_UNIVERSAL_SECTIONS
                        currentHidden = emptySet()
                        currentPinned = emptySet()
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
                itemsIndexed(currentSections, key = { _, item -> item.id }) { index, item ->
                    val isVisible = item.id !in currentHidden
                    val isItemPinned = item.id in currentPinned

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
                                },
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
                                            val list = currentSections.toMutableList()
                                            val temp = list[index]
                                            list[index] = list[index - 1]
                                            list[index - 1] = temp
                                            currentSections = list
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
                                        if (index < currentSections.size - 1) {
                                            val list = currentSections.toMutableList()
                                            val temp = list[index]
                                            list[index] = list[index + 1]
                                            list[index + 1] = temp
                                            currentSections = list
                                        }
                                    },
                                    enabled = index < currentSections.size - 1,
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.KeyboardArrowDown,
                                        contentDescription = "Move Down",
                                        tint = if (index < currentSections.size - 1) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                        modifier = Modifier.size(20.dp),
                                    )
                                }

                                // Pin Button
                                IconButton(
                                    onClick = {
                                        val newPinned = if (isItemPinned) {
                                            currentPinned - item.id
                                        } else {
                                            currentPinned + item.id
                                        }
                                        currentPinned = newPinned
                                        if (!isItemPinned && index > 0) {
                                            // Pin item at the top
                                            val list = currentSections.toMutableList()
                                            list.removeAt(index)
                                            list.add(0, item)
                                            currentSections = list
                                        }
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
                                        currentHidden = if (checked) {
                                            currentHidden - item.id
                                        } else {
                                            currentHidden + item.id
                                        }
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
