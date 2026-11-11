package com.lastwave.app.ui.moods

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.R
import com.lastwave.app.ui.common.ExpressiveHeader
import com.lastwave.app.ui.common.GlassHeaderLayout
import com.lastwave.app.ui.common.adaptiveContentWidth
import com.lastwave.app.ui.common.safeDrawingBottomPadding
import com.lastwave.app.ui.common.safeHorizontalContentPadding
import com.lastwave.app.ui.feed.HomeShelfCard
import com.lastwave.app.ui.feed.cards.CardTokens
import com.lastwave.app.ui.feed.cards.ShelfHeaderV2
import com.lastwave.app.ui.player.LocalMiniPlayerScrollClearance

@Composable
internal fun MoodsScreen(onBack: () -> Unit, viewModel: MoodsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        GlassHeaderLayout(
            Modifier.adaptiveContentWidth(maxWidth = 1000.dp).fillMaxSize(),
            header = { headerModifier, headerBackdrop ->
                ExpressiveHeader(modifier = headerModifier, backdrop = headerBackdrop, title = stringResource(R.string.moods_title), onBack = onBack)
            },
        ) { headerInset ->
        Column(Modifier.fillMaxSize()) {
            when {
                state.isLoading -> Box(Modifier.padding(top = headerInset)) { MoodsPageLoading() }
                state.groups.none { it.tiles.isNotEmpty() } -> Box(Modifier.padding(top = headerInset)) { MoodsLoadMessage(state.failed, viewModel::refresh) }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().safeHorizontalContentPadding(),
                    contentPadding = moodsPagePadding(headerInset),
                    verticalArrangement = Arrangement.spacedBy(CardTokens.sectionGap),
                ) {
                    itemsIndexed(state.groups, key = { index, group -> "$index:${group.title}" }) { _, group ->
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ShelfHeaderV2(title = group.title)
                            MoodMosaic(
                                tiles = group.tiles,
                                onTileClick = viewModel::openTile,
                                wrapVertically = true,
                                pastelGlass = true,
                            )
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
internal fun MoodsCategoryScreen(
    onBack: () -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onOpenAlbum: (title: String, artist: String, browseId: String) -> Unit,
    viewModel: MoodsCategoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val category = state.category
    val title = category?.title?.takeIf { it.isNotBlank() }
        ?: viewModel.fallbackTitle.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.moods_title)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        GlassHeaderLayout(
            Modifier.adaptiveContentWidth(maxWidth = 1000.dp).fillMaxSize(),
            header = { headerModifier, headerBackdrop ->
                ExpressiveHeader(modifier = headerModifier, backdrop = headerBackdrop, title = title, onBack = onBack)
            },
        ) { headerInset ->
        Column(Modifier.fillMaxSize()) {
            when {
                state.isLoading -> Box(Modifier.padding(top = headerInset)) { MoodsPageLoading() }
                category == null || category.shelves.isEmpty() -> Box(Modifier.padding(top = headerInset)) { MoodsLoadMessage(state.failed, viewModel::refresh) }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().safeHorizontalContentPadding(),
                    contentPadding = moodsPagePadding(headerInset),
                    verticalArrangement = Arrangement.spacedBy(CardTokens.sectionGap),
                ) {
                    itemsIndexed(category.shelves, key = { index, shelf -> "$index:${shelf.title}" }) { _, shelf ->
                        // Category parsing keeps playlist links only. Playback belongs to their existing destinations.
                        HomeShelfCard(
                            shelf = shelf,
                            onPlayTrack = { _, _ -> },
                            onPlayTracksQueue = { _, _, _ -> },
                            onShuffleTracksQueue = { _, _ -> },
                            onOpenFeedPlaylist = onOpenPlaylist,
                            onOpenAlbum = onOpenAlbum,
                            onTrackLongClick = {},
                            onPlayPlaylist = { onOpenPlaylist(it.id) },
                            onTrackMenu = {},
                        )
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun moodsPagePadding(topInset: androidx.compose.ui.unit.Dp) = PaddingValues(
    top = 16.dp + topInset,
    bottom = 24.dp + LocalMiniPlayerScrollClearance.current + safeDrawingBottomPadding(),
)

@Composable
private fun MoodsPageLoading() {
    val description = stringResource(R.string.moods_loading)
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.semantics { contentDescription = description })
    }
}
