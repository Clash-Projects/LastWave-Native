package com.lastwave.app.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.data.search.SearchResultItem
import com.lastwave.app.data.search.SearchTab
import com.lastwave.app.ui.search.SearchStatus
import com.lastwave.app.ui.search.SearchViewModel
import com.lastwave.app.ui.tv.components.TvMusicCard
import com.lastwave.app.ui.tv.focus.PositionFocusedItemInLazyLayout
import com.lastwave.app.ui.tv.focus.tvFocusHighlight

@Composable
fun TvSearchScreen(
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
    onOpenArtist: (String) -> Unit = {},
    onOpenAlbum: (String, String) -> Unit = { _, _ -> },
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val openPlayer = LocalTvOpenPlayer.current
    val searchFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        searchFocusRequester.requestFocus()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = 28.dp),
    ) {
        // Search Input Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = uiState.query,
                onValueChange = { viewModel.setQuery(it, autoSearch = false) },
                placeholder = {
                    Text(
                        text = "Search songs, artists, albums (press Enter to search)...",
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                trailingIcon = {
                    if (uiState.query.isNotBlank()) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Clear search",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clickable { viewModel.setQuery("") }
                                .padding(8.dp),
                        )
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    focusManager.clearFocus()
                    viewModel.executeSearch(uiState.query)
                }),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(searchFocusRequester)
                    .tvFocusHighlight(scale = 1.02f, shape = RoundedCornerShape(16.dp)),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Live Suggestions row if user is typing and suggestions are available
        if (uiState.suggestions.isNotEmpty() && uiState.status != SearchStatus.RESULTS) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 48.dp),
            ) {
                Text(
                    text = "Suggestions",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(uiState.suggestions) { suggestion ->
                        TvSuggestionChip(
                            label = suggestion,
                            onClick = {
                                focusManager.clearFocus()
                                viewModel.executeSearch(suggestion)
                            },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        // Tabs Row (Tracks, Artists, Albums, Playlists)
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .focusGroup()
                .focusRestorer(),
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(SearchTab.entries) { tab ->
                val isSelected = uiState.tab == tab
                TvSearchTabChip(
                    label = tab.name.lowercase().replaceFirstChar { it.uppercase() },
                    isSelected = isSelected,
                    onClick = { viewModel.setTab(tab) },
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Content Area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            when (uiState.status) {
                SearchStatus.LOADING -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }

                SearchStatus.RESULTS -> {
                    if (uiState.results.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "No results found for \"${uiState.query}\"",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        val chunkedResults = remember(uiState.results) { uiState.results.chunked(5) }
                        PositionFocusedItemInLazyLayout(
                            parentFraction = 0.3f,
                            childFraction = 0.3f,
                        ) {
                            LazyColumn(
                                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .focusRestorer(),
                            ) {
                                items(
                                    count = chunkedResults.size,
                                    key = { index -> "search_row_$index" },
                                ) { rowIndex ->
                                    val rowResults = chunkedResults[rowIndex]
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    ) {
                                        rowResults.forEach { item ->
                                            TvMusicCard(
                                                title = item.name,
                                                subtitle = item.artist ?: item.subtitle,
                                                imageUrl = item.artworkUrl,
                                                isCircle = uiState.tab == SearchTab.ARTISTS,
                                                onClick = {
                                                    when (uiState.tab) {
                                                        SearchTab.TRACKS -> {
                                                            viewModel.playResult(item)
                                                            openPlayer()
                                                        }
                                                        SearchTab.ARTISTS -> onOpenArtist(item.name)
                                                        SearchTab.ALBUMS -> onOpenAlbum(item.name, item.artist.orEmpty())
                                                        else -> {
                                                            viewModel.playResult(item)
                                                            openPlayer()
                                                        }
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

                SearchStatus.IDLE, SearchStatus.EMPTY -> {
                    // Show Suggestions list & Recent Searches
                    PositionFocusedItemInLazyLayout(
                        parentFraction = 0.3f,
                        childFraction = 0.3f,
                    ) {
                        LazyColumn(
                            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .focusRestorer(),
                        ) {
                            if (uiState.suggestions.isNotEmpty()) {
                                item {
                                    Text(
                                        text = "Matching suggestions",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(bottom = 12.dp),
                                    )
                                }
                                items(uiState.suggestions) { suggestion ->
                                    TvRecentSearchItem(
                                        query = suggestion,
                                        isSuggestion = true,
                                        onClick = {
                                            focusManager.clearFocus()
                                            viewModel.executeSearch(suggestion)
                                        },
                                    )
                                }
                                item {
                                    Spacer(modifier = Modifier.height(20.dp))
                                }
                            }

                            if (uiState.recentSearches.isNotEmpty()) {
                                item {
                                    Text(
                                        text = "Recent Searches",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(bottom = 12.dp),
                                    )
                                }
                                items(uiState.recentSearches) { query ->
                                    TvRecentSearchItem(
                                        query = query,
                                        isSuggestion = false,
                                        onClick = {
                                            focusManager.clearFocus()
                                            viewModel.executeSearch(query)
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
}

@Composable
private fun TvSuggestionChip(
    label: String,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (isFocused) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
            .tvFocusHighlight(scale = 1.08f, shape = RoundedCornerShape(20.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.TrendingUp,
            contentDescription = null,
            tint = if (isFocused) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = if (isFocused) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun TvSearchTabChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Surface(
        color = when {
            isFocused -> MaterialTheme.colorScheme.primaryContainer
            isSelected -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .tvFocusHighlight(scale = 1.08f, shape = RoundedCornerShape(20.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = when {
                isFocused -> MaterialTheme.colorScheme.onPrimaryContainer
                isSelected -> MaterialTheme.colorScheme.onPrimary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun TvRecentSearchItem(
    query: String,
    isSuggestion: Boolean = false,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isFocused) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .tvFocusHighlight(scale = 1.02f, shape = RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Icon(
            imageVector = if (isSuggestion) Icons.Default.Search else Icons.Default.History,
            contentDescription = null,
            tint = if (isFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = query,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}
