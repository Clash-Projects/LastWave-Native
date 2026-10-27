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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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

private fun HomeSection.icon(): ImageVector = when (this) {
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
}

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

    val ytMusicSectionsOrder: StateFlow<List<String>> = settingsPreferences.settings
        .map { it.ytMusicSectionsOrder }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setSectionVisible(section: HomeSection, visible: Boolean) {
        viewModelScope.launch {
            settingsPreferences.setHomeSectionVisible(section.id, visible)
        }
    }

    fun showAll() {
        viewModelScope.launch {
            settingsPreferences.showAllHomeSections()
        }
    }

    fun saveYtMusicSections(order: List<String>, hidden: Set<String>) {
        viewModelScope.launch {
            settingsPreferences.saveYtMusicSections(order, hidden)
        }
    }

    fun resetYtMusicSections() {
        viewModelScope.launch {
            settingsPreferences.resetYtMusicSections()
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HomeSectionsScreen(
    onBack: () -> Unit,
    viewModel: HomeSectionsViewModel = hiltViewModel(),
) {
    val hidden by viewModel.hiddenSections.collectAsStateWithLifecycle()
    val ytHidden by viewModel.ytMusicHiddenSections.collectAsStateWithLifecycle()
    val ytOrder by viewModel.ytMusicSectionsOrder.collectAsStateWithLifecycle()
    val visibleCount = HomeSection.entries.size - hidden.size
    var showYtMusicSheet by remember { mutableStateOf(false) }

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
                    HomeSection.entries.size,
                ),
                onBack = onBack,
                actions = {
                    TextButton(onClick = viewModel::showAll, enabled = hidden.isNotEmpty()) {
                        Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(com.lastwave.app.R.string.home_sections_show_all))
                    }
                },
            )

            Text(
                stringResource(com.lastwave.app.R.string.home_sections_desc),
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
                // Top Action: YouTube Music Selection
                item(key = "yt_music_selection_card") {
                    Card(
                        onClick = { showYtMusicSheet = true },
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(42.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Filled.Subscriptions,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "YouTube Music Selection",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                                Text(
                                    "Toggle on/off and rearrange YouTube Music sections",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
                                )
                            }
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForwardIos,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }

                items(HomeSection.entries, key = { it.id }) { section ->
                    val visible = section.id !in hidden
                    Card(
                        onClick = { viewModel.setSectionVisible(section, !visible) },
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.size(42.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        section.icon(),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    stringResource(section.titleRes),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    stringResource(section.subtitleRes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = visible,
                                onCheckedChange = { viewModel.setSectionVisible(section, it) },
                            )
                        }
                    }
                }
            }
        }

        if (showYtMusicSheet) {
            YouTubeMusicSectionsSheet(
                initialOrder = ytOrder,
                initialHidden = ytHidden,
                onSave = viewModel::saveYtMusicSections,
                onReset = viewModel::resetYtMusicSections,
                onDismiss = { showYtMusicSheet = false },
            )
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun YouTubeMusicSectionsSheet(
    initialOrder: List<String>,
    initialHidden: Set<String>,
    onSave: (List<String>, Set<String>) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val initialList = remember(initialOrder) {
        val merged = (initialOrder + DEFAULT_YT_MUSIC_SECTIONS).distinctBy { it.lowercase() }
        merged
    }
    var currentSections by remember(initialList) { mutableStateOf(initialList) }
    var currentHidden by remember(initialHidden) { mutableStateOf(initialHidden) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "YouTube Music Sections",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "${currentSections.size - currentHidden.size} of ${currentSections.size} sections enabled",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            currentSections = DEFAULT_YT_MUSIC_SECTIONS
                            currentHidden = emptySet()
                            onReset()
                        },
                    ) {
                        Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Reset")
                    }
                    FilledTonalButton(
                        onClick = {
                            onSave(currentSections, currentHidden)
                            onDismiss()
                        },
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Save")
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(currentSections, key = { _, s -> s }) { index, sectionName ->
                    val isVisible = sectionName !in currentHidden
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Reorder buttons (Move Up / Move Down with DragHandle)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = {
                                        if (index > 0) {
                                            val list = currentSections.toMutableList()
                                            val item = list.removeAt(index)
                                            list.add(index - 1, item)
                                            currentSections = list
                                        }
                                    },
                                    enabled = index > 0,
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.KeyboardArrowUp,
                                        contentDescription = "Move up",
                                        tint = if (index > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        if (index < currentSections.size - 1) {
                                            val list = currentSections.toMutableList()
                                            val item = list.removeAt(index)
                                            list.add(index + 1, item)
                                            currentSections = list
                                        }
                                    },
                                    enabled = index < currentSections.size - 1,
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.KeyboardArrowDown,
                                        contentDescription = "Move down",
                                        tint = if (index < currentSections.size - 1) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = sectionName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = isVisible,
                                onCheckedChange = { checked ->
                                    currentHidden = if (checked) currentHidden - sectionName else currentHidden + sectionName
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
