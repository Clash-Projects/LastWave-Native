package com.lastwave.app.ui.tv

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.lastwave.app.ui.album.AlbumDetailScreen
import com.lastwave.app.ui.artist.ArtistDetailScreen
import com.lastwave.app.ui.navigation.Screen
import com.lastwave.app.ui.playlist.PlaylistDetailScreen
import com.lastwave.app.ui.playlist.PlaylistScreen
import com.lastwave.app.ui.settings.DownloadsScreen
import com.lastwave.app.ui.settings.SettingsScreen

@Composable
fun TvNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route,
        modifier = modifier,
        enterTransition = { fadeIn(animationSpec = tween(280)) },
        exitTransition = { fadeOut(animationSpec = tween(200)) },
        popEnterTransition = { fadeIn(animationSpec = tween(280)) },
        popExitTransition = { fadeOut(animationSpec = tween(200)) },
    ) {
        composable(Screen.Home.route) {
            TvHomeScreen(
                onOpenArtist = { name ->
                    navController.navigate(Screen.ArtistDetail.createRoute(name))
                },
                onOpenAlbum = { title, artist ->
                    navController.navigate(Screen.AlbumDetail.createRoute(title, artist))
                },
            )
        }

        composable(Screen.Search.route) {
            TvSearchScreen(
                onOpenArtist = { name ->
                    navController.navigate(Screen.ArtistDetail.createRoute(name))
                },
                onOpenAlbum = { title, artist ->
                    navController.navigate(Screen.AlbumDetail.createRoute(title, artist))
                },
            )
        }

        composable(Screen.Playlist.route) {
            PlaylistScreen(
                onOpenPlaylist = { id ->
                    navController.navigate(Screen.PlaylistDetail.createRoute(id))
                },
            )
        }

        composable(Screen.Downloads.route) {
            DownloadsScreen(
                onBack = { navController.popBackStack() },
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Screen.ArtistDetail.route,
            arguments = listOf(
                navArgument("artistName") { type = NavType.StringType },
                navArgument("browseId") {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) { backStackEntry ->
            val artistName = backStackEntry.arguments?.getString("artistName").orEmpty()
            val browseId = backStackEntry.arguments?.getString("browseId")?.takeIf(String::isNotBlank)
            ArtistDetailScreen(
                artistName = artistName,
                browseId = browseId,
                onBack = { navController.popBackStack() },
                onOpenAlbum = { title, artist, id ->
                    navController.navigate(Screen.AlbumDetail.createRoute(title, artist, id))
                },
                onOpenArtist = { name, id ->
                    navController.navigate(Screen.ArtistDetail.createRoute(name, id))
                },
            )
        }

        composable(
            route = Screen.AlbumDetail.route,
            arguments = listOf(
                navArgument("albumTitle") { type = NavType.StringType },
                navArgument("artistName") {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument("browseId") {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) { backStackEntry ->
            val albumTitle = backStackEntry.arguments?.getString("albumTitle").orEmpty()
            val artistName = backStackEntry.arguments?.getString("artistName").orEmpty()
            val browseId = backStackEntry.arguments?.getString("browseId")?.takeIf(String::isNotBlank)
            AlbumDetailScreen(
                albumTitle = albumTitle,
                artistName = artistName,
                browseId = browseId,
                onBack = { navController.popBackStack() },
                onOpenArtist = { name, bId ->
                    navController.navigate(Screen.ArtistDetail.createRoute(name, bId))
                },
            )
        }

        composable(
            route = Screen.PlaylistDetail.route,
            arguments = listOf(
                navArgument("playlistId") { type = NavType.LongType },
            ),
        ) { backStackEntry ->
            val playlistId = backStackEntry.arguments?.getLong("playlistId") ?: 0L
            PlaylistDetailScreen(
                playlistId = playlistId,
                onBack = { navController.popBackStack() },
                onOpenPlaylist = { newId ->
                    navController.navigate(Screen.PlaylistDetail.createRoute(newId))
                },
            )
        }
    }
}
