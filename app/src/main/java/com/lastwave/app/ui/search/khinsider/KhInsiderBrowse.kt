package com.lastwave.app.ui.search.khinsider

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lastwave.app.R
import com.lastwave.app.data.khinsider.KhAlbumSummary
import com.lastwave.app.data.khinsider.KhBrowseList
import com.lastwave.app.data.khinsider.KhInsiderClient
import com.lastwave.app.data.khinsider.KhInsiderParser
import com.lastwave.app.data.playlist.SavedPlaylist
import com.lastwave.app.ui.common.ArtworkImage
import com.lastwave.app.ui.common.ExpressiveLoadingIndicator
import com.lastwave.app.ui.player.LocalMiniPlayerScrollClearance
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

// --- Browse view model -------------------------------------------------------

data class KhListUiState(
    val list: KhBrowseList? = null,
    val albums: List<KhAlbumSummary> = emptyList(),
    val page: Int = 0,
    val hasNext: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class KhInsiderBrowseViewModel @Inject constructor(
    private val client: KhInsiderClient,
) : ViewModel() {
    private val _list = MutableStateFlow(KhListUiState())
    val list: StateFlow<KhListUiState> = _list.asStateFlow()
    private val _platforms = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val platforms: StateFlow<List<Pair<String, String>>> = _platforms.asStateFlow()
    private var loadJob: Job? = null

    fun open(list: KhBrowseList) {
        if (_list.value.list?.id == list.id && (_list.value.albums.isNotEmpty() || _list.value.loading)) return
        loadJob?.cancel()
        _list.value = KhListUiState(list = list)
        loadMore()
    }

    fun retry() {
        val current = _list.value.list ?: return
        _list.value = KhListUiState(list = current)
        loadMore()
    }

    fun loadMore() {
        val current = _list.value
        val list = current.list ?: return
        if (current.loading) return
        if (current.page > 0 && !current.hasNext) return
        _list.value = current.copy(loading = true, error = null)
        loadJob = viewModelScope.launch {
            try {
                val next = client.albumList(list, current.page + 1)
                val seen = _list.value.albums.mapTo(mutableSetOf()) { it.id }
                _list.value = _list.value.copy(
                    albums = _list.value.albums + next.albums.filter { it.id !in seen },
                    page = next.page,
                    hasNext = next.hasNext && next.albums.isNotEmpty(),
                    loading = false,
                )
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                _list.value = _list.value.copy(loading = false, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun loadPlatforms() {
        if (_platforms.value.isNotEmpty()) return
        viewModelScope.launch { _platforms.value = client.platforms() }
    }
}

// --- Browse section (shown in Search when the game filter has no query) -------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KhInsiderBrowseSection(
    onOpenList: (KhBrowseList) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: KhInsiderBrowseViewModel = hiltViewModel(key = "khinsider_browse"),
) {
    var showAllPlatforms by remember { mutableStateOf(false) }
    var showAllYears by remember { mutableStateOf(false) }
    val currentYear = remember { Calendar.getInstance().get(Calendar.YEAR) }
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp, bottom = 24.dp + LocalMiniPlayerScrollClearance.current),
    ) {
        BrowseHeader(Icons.Filled.TrendingUp, stringResource(R.string.khinsider_browse_popular))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            KhInsiderParser.POPULAR_LISTS.forEach { list ->
                val label = popularLabel(list.id)?.let { stringResource(it) } ?: list.title
                BrowseChip(label) { onOpenList(list.copy(title = label)) }
            }
        }
        Spacer(Modifier.height(20.dp))
        BrowseHeader(Icons.Filled.VideogameAsset, stringResource(R.string.khinsider_browse_platforms))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            KhInsiderParser.MAIN_PLATFORMS.forEach { (slug, label) ->
                BrowseChip(label) { onOpenList(KhInsiderParser.platformList(slug, label)) }
            }
            BrowseChip(stringResource(R.string.khinsider_browse_all_platforms), emphasized = true) {
                viewModel.loadPlatforms()
                showAllPlatforms = true
            }
        }
        Spacer(Modifier.height(20.dp))
        BrowseHeader(Icons.Filled.CalendarMonth, stringResource(R.string.khinsider_browse_years))
        val years = (currentYear downTo if (showAllYears) 1975 else currentYear - 14).toList()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            years.forEach { year -> BrowseChip(year.toString()) { onOpenList(KhInsiderParser.yearList(year)) } }
            if (!showAllYears) {
                BrowseChip(stringResource(R.string.khinsider_browse_more_years), emphasized = true) { showAllYears = true }
            }
        }
    }

    if (showAllPlatforms) {
        val platforms by viewModel.platforms.collectAsStateWithLifecycle()
        AllPlatformsSheet(
            platforms = platforms,
            onPick = { (slug, label) ->
                showAllPlatforms = false
                onOpenList(KhInsiderParser.platformList(slug, label))
            },
            onDismiss = { showAllPlatforms = false },
        )
    }
}

private fun popularLabel(id: String): Int? = when (id) {
    "top40" -> R.string.khinsider_list_top40
    "top6m" -> R.string.khinsider_list_top6m
    "topnew" -> R.string.khinsider_list_topnew
    "favorites" -> R.string.khinsider_list_favorites
    "alltime" -> R.string.khinsider_list_alltime
    else -> null
}

@Composable
private fun BrowseHeader(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BrowseChip(label: String, emphasized: Boolean = false, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = if (emphasized) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
            labelColor = if (emphasized) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurface,
        ),
        shape = RoundedCornerShape(12.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AllPlatformsSheet(
    platforms: List<Pair<String, String>>,
    onPick: (Pair<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var filter by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                singleLine = true,
                label = { Text(stringResource(R.string.khinsider_platform_filter)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            if (platforms.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { ExpressiveLoadingIndicator() }
            } else {
                val shown = platforms.filter { it.second.contains(filter.trim(), ignoreCase = true) }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(shown, key = { it.first }) { platform ->
                        Text(
                            platform.second,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth().clickable { onPick(platform) }.padding(vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

// --- Album list overlay ------------------------------------------------------

@Composable
fun KhInsiderListOverlay(
    list: KhBrowseList,
    onOpenAlbum: (KhAlbumSummary) -> Unit,
    onDismiss: () -> Unit,
    viewModel: KhInsiderBrowseViewModel = hiltViewModel(key = "khinsider_list"),
) {
    BackHandler(onBack = onDismiss)
    LaunchedEffect(list.id) { viewModel.open(list) }
    val state by viewModel.list.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 6
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { nearEnd }.distinctUntilChanged().filter { it }.collect { viewModel.loadMore() }
    }
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
        ) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.khinsider_back))
                }
                Text(
                    list.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            when {
                state.albums.isEmpty() && state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ExpressiveLoadingIndicator()
                }
                state.albums.isEmpty() && state.error != null -> Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.khinsider_error, state.error.orEmpty()), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = viewModel::retry) { Text(stringResource(R.string.khinsider_retry)) }
                }
                else -> LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = 24.dp + LocalMiniPlayerScrollClearance.current),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(state.albums, key = { _, a -> a.id }) { _, album ->
                        KhAlbumRow(album) { onOpenAlbum(album) }
                    }
                    if (state.loading) {
                        item(key = "loading") {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { ExpressiveLoadingIndicator() }
                        }
                    } else if (state.error != null) {
                        item(key = "error") {
                            TextButton(onClick = viewModel::loadMore, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.khinsider_retry))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KhAlbumRow(album: KhAlbumSummary, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkImage(
            name = album.name,
            artist = album.name,
            embeddedUrl = album.thumbnailUrl,
            fallbackIcon = Icons.Filled.SportsEsports,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)),
        )
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(album.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val subtitle = listOfNotNull(album.platform, album.type, album.year).filter(String::isNotBlank).joinToString(" · ")
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// --- Playlist picker for a whole album ---------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KhPlaylistPickerSheet(
    playlists: List<SavedPlaylist>,
    onPick: (SavedPlaylist) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.khinsider_add_album),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            if (creating) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.khinsider_new_playlist)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { creating = false }) { Text(stringResource(R.string.common_cancel)) }
                    TextButton(onClick = { onCreate(name.trim()) }, enabled = name.isNotBlank()) {
                        Text(stringResource(R.string.khinsider_create))
                    }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().clickable { creating = true }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.khinsider_new_playlist), color = MaterialTheme.colorScheme.primary)
                }
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                items(playlists, key = { it.id }) { playlist ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(playlist) }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(playlist.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                stringResource(R.string.khinsider_track_count, playlist.tracks.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
