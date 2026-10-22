package com.lastwave.app.ui.playlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lastwave.app.R
import com.lastwave.app.data.favorites.FavoriteItem
import com.lastwave.app.data.favorites.FavoriteKind
import com.lastwave.app.data.favorites.FavoritesRepository
import com.lastwave.app.ui.common.ArtworkImage
import com.lastwave.app.ui.navigation.ArtistAlbumNavigator
import com.lastwave.app.ui.shell.FloatingNavDefaults
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Which list the Library screen shows. */
enum class LibraryFilter(val labelRes: Int) {
    PLAYLISTS(R.string.lib_filter_playlists),
    ALBUMS(R.string.lib_filter_albums),
    ARTISTS(R.string.lib_filter_artists),
}

@HiltViewModel
class LibraryFavoritesViewModel @Inject constructor(
    private val favoritesRepository: FavoritesRepository,
    val navigator: ArtistAlbumNavigator,
) : ViewModel() {
    fun favorites(kind: FavoriteKind) = favoritesRepository.favorites(kind)

    fun remove(kind: FavoriteKind, item: FavoriteItem) {
        viewModelScope.launch { favoritesRepository.toggle(kind, item) }
    }
}

@Composable
internal fun FavoritesList(
    kind: FavoriteKind,
    viewModel: LibraryFavoritesViewModel = hiltViewModel(),
) {
    val items by remember(kind) { viewModel.favorites(kind) }.collectAsStateWithLifecycle(initialValue = emptyList())
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                stringResource(if (kind == FavoriteKind.ALBUM) R.string.fav_albums_empty else R.string.fav_artists_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = FloatingNavDefaults.contentBottomPadding()),
        verticalArrangement = Arrangement.spacedBy(com.lastwave.app.ui.common.GroupGap),
        modifier = Modifier.fillMaxWidth(),
    ) {
        itemsIndexed(items, key = { _, item -> "${item.browseId}|${item.title}|${item.artist}" }) { index, item ->
            Surface(
                onClick = {
                    if (kind == FavoriteKind.ALBUM) viewModel.navigator.openAlbum(item.title, item.artist, item.browseId)
                    else viewModel.navigator.openArtist(item.title, item.browseId)
                },
                shape = com.lastwave.app.ui.common.groupShape(com.lastwave.app.ui.common.groupPositionFor(index, items.size)),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    ArtworkImage(
                        name = item.title,
                        artist = item.artist,
                        embeddedUrl = item.artworkUrl,
                        fallbackIcon = if (kind == FavoriteKind.ALBUM) Icons.Filled.Album else Icons.Filled.Person,
                        modifier = Modifier.size(60.dp).clip(if (kind == FavoriteKind.ALBUM) RoundedCornerShape(14.dp) else CircleShape),
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (item.artist.isNotBlank()) {
                            Text(
                                item.artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    IconButton(onClick = { viewModel.remove(kind, item) }) {
                        Icon(
                            Icons.Filled.Favorite,
                            contentDescription = stringResource(R.string.fav_remove_cd),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}
