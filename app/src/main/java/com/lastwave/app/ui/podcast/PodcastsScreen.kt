package com.lastwave.app.ui.podcast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.R
import com.lastwave.app.data.podcast.PodcastEpisode
import com.lastwave.app.data.podcast.PodcastShelf
import com.lastwave.app.ui.common.ExpressiveHeader
import com.lastwave.app.ui.common.HeaderActionIcon
import com.lastwave.app.ui.common.adaptiveContentWidth
import com.lastwave.app.ui.common.groupPositionFor
import com.lastwave.app.ui.common.safeDrawingBottomPadding
import com.lastwave.app.ui.common.safeHorizontalContentPadding
import com.lastwave.app.ui.player.LocalMiniPlayerScrollClearance
import com.lastwave.app.ui.player.LocalMusicPlayer

@Composable
fun PodcastsScreen(
    onBack: () -> Unit = {},
    onOpenShow: (String) -> Unit = {},
    viewModel: PodcastsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val musicPlayer = LocalMusicPlayer.current
    val playbackState by musicPlayer.chromeState.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .adaptiveContentWidth(maxWidth = 860.dp),
    ) {
        ExpressiveHeader(
            title = stringResource(R.string.podcasts_title),
            subtitle = stringResource(R.string.podcasts_subtitle),
            onBack = onBack,
            actions = {
                HeaderActionIcon(Icons.Filled.Refresh, "Refresh", viewModel::refresh)
            },
        )

        if (!state.enabled) {
            PodcastsDisabledState()
        } else {
            PodcastsContent(
                state = state,
                playbackVideoId = playbackState.current?.videoId,
                isPlaying = playbackState.isPlaying,
                onQueryChange = viewModel::onQueryChange,
                onClearQuery = viewModel::clearQuery,
                onRetry = { viewModel.load(forceRefresh = true) },
                onOpenShow = onOpenShow,
                onPlayEpisode = { episode -> viewModel.playEpisode(episode, state.searchResults) },
                onPlayShelfEpisode = { shelf, episode ->
                    viewModel.playEpisode(episode, shelf.episodes)
                },
            )
        }
    }
}

@Composable
private fun PodcastsDisabledState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.Mic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(44.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.podcasts_empty),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.podcasts_empty_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PodcastsContent(
    state: PodcastsUiState,
    playbackVideoId: String?,
    isPlaying: Boolean,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onRetry: () -> Unit,
    onOpenShow: (String) -> Unit,
    onPlayEpisode: (PodcastEpisode) -> Unit,
    onPlayShelfEpisode: (PodcastShelf, PodcastEpisode) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().safeHorizontalContentPadding(),
        contentPadding = PaddingValues(
            top = 10.dp,
            bottom = 24.dp + LocalMiniPlayerScrollClearance.current + safeDrawingBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item(key = "podcast_search") {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.podcasts_search_hint)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = onClearQuery) {
                            Icon(Icons.Filled.SearchOff, contentDescription = null)
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }

        if (state.query.isNotBlank()) {
            if (state.isSearching) {
                item(key = "podcast_searching") {
                    Text(
                        stringResource(R.string.podcasts_searching),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else if (state.searchResults.isEmpty()) {
                item(key = "podcast_search_empty") {
                    Text(
                        stringResource(R.string.podcasts_search_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                itemsIndexed(
                    items = state.searchResults,
                    key = { index, episode -> "search:${episode.videoId}:$index" },
                ) { index, episode ->
                    PodcastEpisodeRow(
                        episode = episode,
                        position = groupPositionFor(index, state.searchResults.size),
                        isPlaying = isPlaying && playbackVideoId == episode.videoId,
                        progress = null,
                        onClick = { onPlayEpisode(episode) },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        } else if (state.isLoading && state.shelves.isEmpty()) {
            item(key = "podcast_loading") {
                Text(
                    stringResource(R.string.podcasts_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else if (state.error != null && state.shelves.isEmpty()) {
            item(key = "podcast_error") {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.podcasts_load_fail),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        state.error.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.ly_retry))
                    }
                }
            }
        } else if (state.shelves.isEmpty()) {
            item(key = "podcast_empty") {
                Column(
                    Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Filled.Mic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(44.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.podcasts_empty),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.podcasts_empty_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            items(state.shelves, key = { it.title }) { shelf ->
                PodcastShelfSection(
                    shelf = shelf,
                    onOpenShow = onOpenShow,
                    onPlayEpisode = { episode -> onPlayShelfEpisode(shelf, episode) },
                )
            }
        }
    }
}

@Composable
private fun PodcastShelfSection(
    shelf: PodcastShelf,
    onOpenShow: (String) -> Unit,
    onPlayEpisode: (PodcastEpisode) -> Unit,
) {
    Column(Modifier.padding(vertical = 6.dp)) {
        if (shelf.title.isNotBlank()) {
            Text(
                shelf.title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        if (shelf.shows.isNotEmpty()) {
            PodcastShowRow(shows = shelf.shows, onShowClick = { onOpenShow(it.showId) })
        }
        if (shelf.episodes.isNotEmpty()) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                shelf.episodes.forEachIndexed { index, episode ->
                    PodcastEpisodeRow(
                        episode = episode,
                        position = groupPositionFor(index, shelf.episodes.size),
                        isPlaying = false,
                        progress = null,
                        onClick = { onPlayEpisode(episode) },
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}
