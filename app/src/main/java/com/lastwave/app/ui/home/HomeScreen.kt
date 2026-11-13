package com.lastwave.app.ui.home

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.lastwave.app.R
import com.lastwave.app.ui.common.ArtworkImage
import com.lastwave.app.ui.common.ExpressiveHeader
import com.lastwave.app.ui.common.GlassHeaderLayout
import com.lastwave.app.ui.common.HeaderActionIcon
import com.lastwave.app.ui.common.adaptiveContentWidth
import com.lastwave.app.ui.insights.ListeningInsightsDashboard
import com.lastwave.app.ui.navigation.ArtistAlbumNavigator
import com.lastwave.app.ui.shell.FloatingNavDefaults
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Native Statistics tab backed directly by the offline Listening Insights dashboard.
 */
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenDownloads: () -> Unit = {},
    artistAlbumNavigator: ArtistAlbumNavigator = hiltViewModel<ArtistAlbumNavBridgeHome>().navigator,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        GlassHeaderLayout(
            modifier = Modifier
                .fillMaxSize()
                .adaptiveContentWidth(maxWidth = 860.dp),
            header = { headerModifier, headerBackdrop ->
                ExpressiveHeader(
                    modifier = headerModifier,
                    backdrop = headerBackdrop,
                    title = "Statistics",
                    subtitle = stringResource(R.string.listening_insights_subtitle),
                    actions = {
                        HeaderActionIcon(Icons.Filled.Download, "Downloads", onOpenDownloads)
                        HeaderActionIcon(Icons.Filled.Search, "Search", onOpenSearch)
                        IconButton(onClick = onOpenSettings) {
                            ProfileAvatar(avatarUrl = null, modifier = Modifier.size(38.dp))
                        }
                    },
                )
            },
        ) { headerInset ->
            ListeningInsightsDashboard(
                onOpenArtist = { artistName ->
                    artistAlbumNavigator.openArtist(name = artistName, browseId = "")
                },
                topInset = headerInset,
                bottomPadding = FloatingNavDefaults.contentBottomPadding(),
            )
        }
    }
}

@Composable
fun ProfileAvatar(avatarUrl: String?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .border(1.dp, MaterialTheme.colorScheme.primary, CircleShape)
            .padding(2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (!avatarUrl.isNullOrBlank()) {
                ArtworkImage(
                    name = "profile",
                    artist = "avatar",
                    embeddedUrl = avatarUrl,
                    fallbackIcon = Icons.Filled.AccountCircle,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.AccountCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}

@HiltViewModel
class ArtistAlbumNavBridgeHome @Inject constructor(
    val navigator: ArtistAlbumNavigator,
) : ViewModel()
