package com.lastwave.app.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.data.model.AuthState
import com.lastwave.app.ui.auth.AuthViewModel
import com.lastwave.app.ui.auth.LoginScreen
import com.lastwave.app.ui.settings.SettingsScreen
import com.lastwave.app.ui.search.SearchScreen
import com.lastwave.app.ui.discover.DiscoverScreen
import com.lastwave.app.ui.genres.GenresScreen
import com.lastwave.app.ui.shell.MainShell
import com.lastwave.app.ui.common.PredictiveBackScreen
import com.lastwave.app.ui.common.ExpressiveLoadingIndicator
import com.lastwave.app.ui.common.ExpressiveMotion
import com.lastwave.app.ui.genres.GenreExplorer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.stateIn

/** Thin bridge so LastWaveNavHost (a plain composable, no direct Hilt
 *  singleton access) can observe GenreExplorer's pending genre and
 *  navigate — same reasoning as MainShellViewModel's bridge to
 *  MixLauncher. */
@HiltViewModel
class GenreExplorerNavBridge @Inject constructor(genreExplorer: GenreExplorer) : ViewModel() {
    val pendingGenre = genreExplorer.pendingGenre
}

@HiltViewModel
class MixLauncherNavBridge @Inject constructor(val mixLauncher: com.lastwave.app.ui.generate.MixLauncher) : ViewModel()

@HiltViewModel
class ArtistAlbumNavBridge @Inject constructor(val navigator: ArtistAlbumNavigator) : ViewModel()

@HiltViewModel
class AppRouteNavBridge @Inject constructor(val routeNavigator: AppRouteNavigator) : ViewModel()

/**
 * Splash / onboarding gate for the YouTube Music-first flow.
 *
 * Routes to MainShell when ANY onboarding signal is present:
 *  - a Last.fm session (legacy [AuthState.SignedIn]), OR
 *  - a YouTube Music connection (cookies captured via YtMusicAuthManager), OR
 *  - a previously-selected guest mode (account-free).
 * Otherwise routes to Login, which now shows only "Login with YouTube Music"
 * and "Continue as Guest".
 */
@HiltViewModel
class LaunchGateViewModel @Inject constructor(
    authRepository: com.lastwave.app.data.repository.AuthRepository,
    sessionPreferences: com.lastwave.app.data.local.SessionPreferences,
    ytAuthManager: com.lastwave.app.data.ytmusic.YtMusicAuthManager,
) : ViewModel() {
    sealed interface GateTarget {
        data object Loading : GateTarget
        data object Login : GateTarget
        data object MainShell : GateTarget
    }

    val gateTarget: kotlinx.coroutines.flow.StateFlow<GateTarget> =
        kotlinx.coroutines.flow.combine(
            authRepository.authState,
            sessionPreferences.session,
            sessionPreferences.guestMode,
            ytAuthManager.connection,
        ) { authState, session, guestMode, ytConnection ->
            // Wait for DataStore to load before deciding; otherwise every
            // cold start would flash Login.
            if (!session.isLoaded) {
                GateTarget.Loading
            } else if (authState is AuthState.Unknown) {
                GateTarget.Loading
            } else if (authState is AuthState.SignedIn) {
                GateTarget.MainShell
            } else if (guestMode) {
                GateTarget.MainShell
            } else if (ytConnection.isConnected) {
                GateTarget.MainShell
            } else {
                GateTarget.Login
            }
        }.stateIn(
            scope = viewModelScope,
            started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000),
            initialValue = GateTarget.Loading,
        )
}

private var lastPopTimeMs = 0L

/**
 * Safely pops the back stack to prevent blank black screens from consecutive back presses.
 * - Prevents popping the root screen (MainShell, Splash, Login).
 * - Prevents popping when there is no previous destination.
 * - Prevents stale clicks from exiting screens (fromEntry != currentBackStackEntry).
 * - Debounces rapid multi-taps (< 300ms) during transition animations.
 */
private fun NavController.safePopBackStack(fromEntry: NavBackStackEntry? = null): Boolean {
    val now = android.os.SystemClock.elapsedRealtime()
    if (now - lastPopTimeMs < 300L) {
        return false
    }
    val current = currentDestination?.route
    if (current == null ||
        current == Screen.MainShell.route ||
        current == Screen.Splash.route ||
        current == Screen.Login.route
    ) {
        return false
    }
    if (fromEntry != null) {
        if (currentBackStackEntry != fromEntry) {
            return false
        }
        if (!fromEntry.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            return false
        }
    }
    if (previousBackStackEntry == null) {
        return false
    }
    val currentEntry = currentBackStackEntry
    if (currentEntry != null && !currentEntry.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
        return false
    }
    lastPopTimeMs = now
    return popBackStack()
}

@Composable
fun LastWaveNavHost(
    navController: NavHostController = rememberNavController(),
) {
    val appRouteBridge: AppRouteNavBridge = hiltViewModel()
    val pendingAppRoute by appRouteBridge.routeNavigator.pendingRoute.collectAsStateWithLifecycle()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route
    LaunchedEffect(pendingAppRoute, currentRoute) {
        val route = pendingAppRoute
        if (route != null && currentRoute != null &&
            currentRoute != Screen.Splash.route && currentRoute != Screen.Login.route
        ) {
            if (currentRoute != route) {
                navController.navigate(route) {
                    launchSingleTop = true
                }
            }
            appRouteBridge.routeNavigator.consumeRoute()
        }
    }
    // "Explore this genre" from any track's context menu, anywhere in the
    // app — Home, Discover, Playlist, Search — routes here since Genres
    // is a pushed destination on THIS nav controller and none of those
    // screens hold a reference to it. See GenreExplorer's doc comment.
    val genreExplorerBridge: GenreExplorerNavBridge = hiltViewModel()
    val pendingGenre by genreExplorerBridge.pendingGenre.collectAsStateWithLifecycle()
    LaunchedEffect(pendingGenre) {
        if (pendingGenre != null) {
            navController.navigate(Screen.Genres.route) {
                launchSingleTop = true
            }
        }
    }

    val navBridge: ArtistAlbumNavBridge = hiltViewModel()
    LaunchedEffect(Unit) {
        navBridge.navigator.events.collect { target ->
            // Drill-down destinations MUST push a fresh back-stack entry every
            // time: launchSingleTop would collapse Artist A -> Artist B (or
            // Album X -> Album Y) into the top entry since they share one
            // destination node, leaving taps on similar artists / more-by
            // albums visibly dead and the detail ViewModel showing stale data.
            // Rapid double-tap dedup already lives in ArtistAlbumNavigator.
            when (target) {
                is ArtistAlbumNavTarget.Artist -> {
                    navController.navigate(Screen.ArtistDetail.createRoute(target.name, target.browseId))
                }
                is ArtistAlbumNavTarget.Album -> {
                    navController.navigate(Screen.AlbumDetail.createRoute(target.title, target.artist, target.browseId))
                }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = Screen.Splash.route,
        enterTransition = { ExpressiveMotion.forwardEnter() },
        exitTransition = { ExpressiveMotion.forwardExit() },
        popEnterTransition = { ExpressiveMotion.backEnter() },
        popExitTransition = { ExpressiveMotion.backExit() },
    ) {

        // Resolves the persisted session BEFORE showing any interactive UI.
        // YouTube Music-first gate: MainShell when Last.fm signed in OR
        // YouTube Music connected OR guest mode was previously selected.
        // This keeps login persistent without flashing Login on cold start.
        composable(Screen.Splash.route) {
            val gateViewModel: LaunchGateViewModel = hiltViewModel()
            val gateTarget by gateViewModel.gateTarget.collectAsStateWithLifecycle()

            LaunchedEffect(gateTarget) {
                when (gateTarget) {
                    LaunchGateViewModel.GateTarget.MainShell -> navController.navigate(Screen.MainShell.route) {
                        popUpTo(Screen.Splash.route) { inclusive = true }
                    }
                    LaunchGateViewModel.GateTarget.Login -> navController.navigate(Screen.Login.route) {
                        popUpTo(Screen.Splash.route) { inclusive = true }
                    }
                    LaunchGateViewModel.GateTarget.Loading -> Unit // keep waiting
                }
            }

            LaunchGate()
        }

        composable(Screen.Login.route) {
            val authViewModel: AuthViewModel = hiltViewModel()
            val gateViewModel: LaunchGateViewModel = hiltViewModel()
            val gateTarget by gateViewModel.gateTarget.collectAsStateWithLifecycle()
            val webAuthState by authViewModel.webAuthState.collectAsStateWithLifecycle()

            // YouTube Music-first onboarding: any onboarded signal (Last.fm
            // session, YT connection, or guest flag) skips straight to
            // MainShell. YT login returns here via YouTubeLogin's onConnected
            // pop, then this effect immediately forwards to MainShell.
            LaunchedEffect(gateTarget, webAuthState) {
                if (gateTarget == LaunchGateViewModel.GateTarget.MainShell &&
                    webAuthState == com.lastwave.app.ui.auth.WebAuthState.Idle
                ) {
                    navController.navigate(Screen.MainShell.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                }
            }

            val restoreError = (webAuthState as? com.lastwave.app.ui.auth.WebAuthState.Error)?.message
            val restoring = webAuthState == com.lastwave.app.ui.auth.WebAuthState.RestoringBackup
            LoginScreen(
                onLoginWithYouTube = {
                    navController.navigate(Screen.YouTubeLogin.route)
                },
                onContinueAsGuest = {
                    authViewModel.continueAsGuest()
                },
                onRestoreBackupAndSignIn = authViewModel::restoreBackupOnly,
                onDismissError = authViewModel::dismissError,
                errorMessage = restoreError,
                isBusy = restoring,
                onOpenDownloads = {
                    navController.navigate(Screen.Downloads.route)
                },
            )
        }

        // Post-login container: Material3 bottom nav with Home/Generate/
        // Playlists as swipeable tabs. Settings, Search, and Discover are
        // NOT tabs — they're pushed screens reached from Home's top app bar
        // (profile icon / search icon / discover icon), on this same root
        // nav controller, so they can pop back cleanly and (on log out /
        // clear session) return all the way to Login.
        composable(Screen.MainShell.route) {
            MainShell(
                onOpenSettings = {
                    navController.navigate(Screen.Settings.route) { launchSingleTop = true }
                },
                onOpenSearch = { navController.navigate(Screen.Search.route) },
                onOpenDiscover = { navController.navigate(Screen.Discover.route) },
                onOpenGenres = { navController.navigate(Screen.Genres.route) },
                onOpenFriends = { navController.navigate(Screen.Friends.route) },
                onOpenFriendProfile = { username, displayName, avatarUrl ->
                    navController.navigate(Screen.FriendProfile.createRoute(username, displayName, avatarUrl))
                },
                onOpenPlaylist = { playlistId ->
                    navController.navigate(Screen.PlaylistDetail.createRoute(playlistId))
                },
                onOpenFeedPlaylist = { playlistId ->
                    navController.navigate(Screen.FeedPlaylistDetail.createRoute(playlistId))
                },
                onOpenGenerator = {
                    navController.navigate(Screen.Create.route) { launchSingleTop = true }
                },
                onOpenNewReleases = {
                    navController.navigate(Screen.NewReleases.route)
                },
                onOpenDownloads = {
                    navController.navigate(Screen.Downloads.route)
                },
                onOpenPodcasts = {
                    navController.navigate(Screen.Podcasts.route)
                },
                onOpenPodcastShow = { showId ->
                    navController.navigate(Screen.PodcastShow.createRoute(showId))
                },
                onOpenYouTubeLogin = {
                    navController.navigate(Screen.YouTubeLogin.route)
                },
                onReturnToHome = {
                    if (navController.currentDestination?.route != Screen.MainShell.route) {
                        navController.popBackStack(Screen.MainShell.route, inclusive = false)
                    } else {
                        false
                    }
                },
            )
        }

        composable(Screen.Create.route) { backStackEntry ->
            PredictiveBackScreen(onBack = { navController.safePopBackStack(backStackEntry) }) {
                com.lastwave.app.ui.generate.GenerateScreen(
                    onNavigateToPlaylist = { playlistId ->
                        navController.safePopBackStack(backStackEntry)
                        if (playlistId != null) {
                            navController.navigate(Screen.PlaylistDetail.createRoute(playlistId))
                        }
                    },
                    onBack = { navController.safePopBackStack(backStackEntry) },
                )
            }
        }

        // Friends is a real pushed screen (not a Dialog/ModalBottomSheet) —
        // both of those were tried first and both had the same underlying
        // problem: getting a Compose overlay to genuinely claim the full
        // real window (not just "as tall as its own content measured
        // itself to be", which is all either API reliably guarantees
        // without extra low-level workarounds) turned out to be fragile
        // enough, even after direct fixes, that it kept regressing. A
        // normal NavHost destination gets full-screen sizing for free —
        // every other pushed screen here (Settings, Search, Discover,
        // Genres) already renders correctly edge-to-edge —
        // so Friends now works exactly like those instead of being a
        // special case. It shares Home's own HomeViewModel (scoped to
        // MainShell's back stack entry) rather than getting a fresh one,
        // since the friends list / switch-profile state already lives
        // there and switching profile needs to affect the Home tab
        // underneath once you pop back.
        composable(Screen.Friends.route) { backStackEntry ->
            val parentEntry = remember(backStackEntry) {
                navController.getBackStackEntry(Screen.MainShell.route)
            }
            val homeViewModel: com.lastwave.app.ui.home.HomeViewModel = hiltViewModel(parentEntry)
            PredictiveBackScreen(onBack = { navController.safePopBackStack(backStackEntry) }) {
                com.lastwave.app.ui.home.FriendsScreen(
                    viewModel = homeViewModel,
                    onBack = { navController.safePopBackStack(backStackEntry) },
                    onOpenFriendProfile = { username, displayName, avatarUrl ->
                        navController.navigate(Screen.FriendProfile.createRoute(username, displayName, avatarUrl))
                    },
                )
            }
        }

        composable(
            route = Screen.FriendProfile.route,
            arguments = listOf(
                androidx.navigation.navArgument("username") { type = androidx.navigation.NavType.StringType },
                androidx.navigation.navArgument("displayName") {
                    type = androidx.navigation.NavType.StringType
                    defaultValue = ""
                },
                androidx.navigation.navArgument("avatarUrl") {
                    type = androidx.navigation.NavType.StringType
                    defaultValue = ""
                },
            ),
        ) { backStackEntry ->
            val username = backStackEntry.arguments?.getString("username").orEmpty()
            val displayName = backStackEntry.arguments?.getString("displayName")?.takeIf(String::isNotBlank)
            val avatarUrl = backStackEntry.arguments?.getString("avatarUrl")?.takeIf(String::isNotBlank)
            PredictiveBackScreen(onBack = { navController.safePopBackStack(backStackEntry) }) {
                com.lastwave.app.ui.home.FriendProfileScreen(
                    username = username,
                    initialDisplayName = displayName,
                    initialAvatarUrl = avatarUrl,
                    onBack = { navController.safePopBackStack(backStackEntry) },
                    onOpenArtist = { artistName ->
                        navController.navigate(Screen.ArtistDetail.createRoute(artistName))
                    },
                    onOpenAlbum = { albumTitle, artistName ->
                        navController.navigate(Screen.AlbumDetail.createRoute(albumTitle, artistName))
                    },
                )
            }
        }

        // Every pushed-on-top destination below is wrapped in
        // PredictiveBackScreen so Android's predictive back gesture (drag
        // in from the edge) scales/rounds/dims the screen in real time
        // instead of using a canned pop transition — see its doc comment
        // for exactly what it does and does not animate. The screen's own
        // onBack (its toolbar back button) still pops directly; the wrap
        // only adds the gesture-driven path, it doesn't replace the
        // button's.
        composable(Screen.Genres.route) { backStackEntry ->
            PredictiveBackScreen(onBack = { navController.safePopBackStack(backStackEntry) }) {
                GenresScreen(
                    onBack = { navController.safePopBackStack(backStackEntry) },
                    onNavigateToPlaylist = {
                        navController.safePopBackStack(backStackEntry)
                        // Playlist tab already re-reads on resume (PlaylistViewModel's
                        // LifecycleResumeEffect), so popping back to MainShell is
                        // sufficient — no separate "switch tab" signal is needed here
                        // since MainShell always shows Playlists as one of its tabs
                        // and the user lands back wherever they left MainShell.
                    },
                )
            }
        }

        composable(Screen.Settings.route) { backStackEntry ->
            PredictiveBackScreen(onBack = { navController.safePopBackStack(backStackEntry) }) {
                SettingsScreen(
                    onBack = { navController.safePopBackStack(backStackEntry) },
                    onLoggedOut = {
                        navController.navigate(Screen.Login.route) {
                            popUpTo(Screen.MainShell.route) { inclusive = true }
                        }
                    },
                    onOpenDownloads = { navController.navigate(Screen.Downloads.route) },
                    onOpenModules = { navController.navigate(Screen.ProviderModules.route) },
                    onOpenHomeSections = { navController.navigate(Screen.HomeSections.route) },
                    onOpenExcludedSongs = { navController.navigate(Screen.ExcludedSongs.route) },
                    onOpenYouTubeImport = { navController.navigate(Screen.YouTubeImport.route) },
                    onOpenYouTubeLogin = { navController.navigate(Screen.YouTubeLogin.route) },
                    onOpenSpotifyLogin = { navController.navigate(Screen.SpotifyLogin.route) },
                    onOpenSpotifyTokenImport = { navController.navigate(Screen.SpotifyTokenImport.route) },
                    onOpenExternalImport = { navController.navigate(Screen.ExternalPlaylistImport.route) },
                    onOpenJam = { navController.navigate(Screen.Jam.createRoute()) },
                )
            }
        }

      composable(Screen.SpotifyLogin.route) { backStackEntry ->
      PredictiveBackScreen(onBack = { navController.safePopBackStack(backStackEntry) }) {
      com.lastwave.app.ui.settings.SpotifyLoginScreen(
      onBack = { navController.safePopBackStack(backStackEntry) },
      onConnected = { navController.safePopBackStack(backStackEntry) },
      )
      }
      }

      composable(Screen.SpotifyTokenImport.route) { backStackEntry ->
      PredictiveBackScreen(onBack = { navController.safePopBackStack(backStackEntry) }) {
      com.lastwave.app.ui.settings.SpotifyLoginScreen(
      onBack = { navController.safePopBackStack(backStackEntry) },
      onConnected = { navController.safePopBackStack(backStackEntry) },
      openManualEntryInitially = true,
      )
      }
      }

        composable(
            route = Screen.Jam.route,
            arguments = listOf(
                androidx.navigation.navArgument("code") {
                    type = androidx.navigation.NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val code = backStackEntry.arguments?.getString("code")
            val jamViewModel: com.lastwave.app.ui.jam.LastWaveJamViewModel = hiltViewModel()
            androidx.compose.runtime.LaunchedEffect(code) {
                if (!code.isNullOrBlank()) {
                    jamViewModel.setPendingCode(code)
                }
            }
            com.lastwave.app.ui.jam.JamScreen(
                viewModel = jamViewModel,
                onBack = { navController.safePopBackStack(backStackEntry) },
            )
        }

        composable(Screen.YouTubeImport.route) { backStackEntry ->
            PredictiveBackScreen(onBack = { navController.safePopBackStack(backStackEntry) }) {
                com.lastwave.app.ui.settings.YouTubePlaylistImportScreen(
                    onBack = { navController.safePopBackStack(backStackEntry) },
                    onImportSuccess = {
                        navController.safePopBackStack(backStackEntry)
                    },
                )
            }
        }

        composable(Screen.YouTubeLogin.route) { backStackEntry ->
            PredictiveBackScreen(onBack = { navController.safePopBackStack(backStackEntry) }) {
                com.lastwave.app.ui.settings.YouTubeLoginScreen(
                    onBack = { navController.safePopBackStack(backStackEntry) },
                    onConnected = { navController.safePopBackStack(backStackEntry) },
                )
            }
        }

        composable(Screen.ExternalPlaylistImport.route) { backStackEntry ->
            PredictiveBackScreen(onBack = { navController.safePopBackStack(backStackEntry) }) {
                com.lastwave.app.ui.settings.ExternalPlaylistImportScreen(
                    onBack = { navController.safePopBackStack(backStackEntry) },
                    onImportSuccess = {
                        navController.safePopBackStack(backStackEntry)
                    },
                    onOpenSpotifyLogin = {
                        navController.navigate(Screen.SpotifyLogin.route)
                    },
                )
            }
        }

        composable(Screen.Downloads.route) { backStackEntry ->
            PredictiveBackScreen(onBack = { navController.safePopBackStack(backStackEntry) }) {
                com.lastwave.app.ui.settings.DownloadsScreen(onBack = { navController.safePopBackStack(backStackEntry) })
            }
        }

        composable(Screen.ProviderModules.route) { backStackEntry ->
            PredictiveBackScreen(onBack = { navController.safePopBackStack(backStackEntry) }) {
                com.lastwave.app.ui.settings.ModulesScreen(onBack = { navController.safePopBackStack(backStackEntry) })
            }
        }

        composable(Screen.HomeSections.route) { backStackEntry ->
            PredictiveBackScreen(onBack = { navController.safePopBackStack(backStackEntry) }) {
                com.lastwave.app.ui.settings.HomeSectionsScreen(onBack = { navController.safePopBackStack(backStackEntry) })
            }
        }

        composable(Screen.ExcludedSongs.route) { backStackEntry ->
            PredictiveBackScreen(onBack = { navController.safePopBackStack(backStackEntry) }) {
                com.lastwave.app.ui.settings.ExcludedSongsScreen(onBack = { navController.safePopBackStack(backStackEntry) })
            }
        }


        composable(Screen.Search.route) { backStackEntry ->
            PredictiveBackScreen(onBack = { navController.safePopBackStack(backStackEntry) }) {
                SearchScreen(
                    onBack = { navController.safePopBackStack(backStackEntry) },
                    onOpenArtist = { name, browseId ->
                        navController.navigate(Screen.ArtistDetail.createRoute(name, browseId))
                    },
                    onOpenAlbum = { title, artist, browseId ->
                        navController.navigate(Screen.AlbumDetail.createRoute(title, artist, browseId))
                    },
                    onOpenPlaylist = { playlistId ->
                        navController.navigate(Screen.FeedPlaylistDetail.createRoute(playlistId))
                    },
                )
            }
        }

        composable(Screen.Discover.route) { backStackEntry ->
            PredictiveBackScreen(onBack = { navController.safePopBackStack(backStackEntry) }) {
                DiscoverScreen(onBack = { navController.safePopBackStack(backStackEntry) })
            }
        }

        composable(Screen.NewReleases.route) { backStackEntry ->
            PredictiveBackScreen(onBack = { navController.safePopBackStack(backStackEntry) }) {
                com.lastwave.app.ui.newreleases.NewReleasesScreen(
                    onBack = { navController.safePopBackStack(backStackEntry) },
                )
            }
        }

        composable(Screen.Podcasts.route) { backStackEntry ->
            PredictiveBackScreen(onBack = { navController.safePopBackStack(backStackEntry) }) {
                com.lastwave.app.ui.podcast.PodcastsScreen(
                    onBack = { navController.safePopBackStack(backStackEntry) },
                    onOpenShow = { showId ->
                        navController.navigate(Screen.PodcastShow.createRoute(showId))
                    },
                )
            }
        }

        composable(
            route = Screen.PodcastShow.route,
            arguments = listOf(
                androidx.navigation.navArgument("showId") {
                    type = androidx.navigation.NavType.StringType
                },
            ),
        ) { backStackEntry ->
            val showId = backStackEntry.arguments?.getString("showId").orEmpty()
            PredictiveBackScreen(onBack = { navController.safePopBackStack(backStackEntry) }) {
                com.lastwave.app.ui.podcast.PodcastShowScreen(
                    showId = showId,
                    onBack = { navController.safePopBackStack(backStackEntry) },
                )
            }
        }

        composable(
            route = Screen.FeedPlaylistDetail.route,
            arguments = listOf(
                androidx.navigation.navArgument("playlistId") {
                    type = androidx.navigation.NavType.StringType
                },
            ),
        ) { backStackEntry ->
            val playlistId = backStackEntry.arguments?.getString("playlistId").orEmpty()
            PredictiveBackScreen(onBack = { navController.safePopBackStack(backStackEntry) }) {
                com.lastwave.app.ui.feed.FeedPlaylistDetailScreen(
                    playlistId = playlistId,
                    onBack = { navController.safePopBackStack(backStackEntry) },
                )
            }
        }

        composable(
            route = Screen.PlaylistDetail.route,
            arguments = listOf(
                androidx.navigation.navArgument("playlistId") {
                    type = androidx.navigation.NavType.LongType
                },
            ),
        ) { backStackEntry ->
            val playlistId = backStackEntry.arguments?.getLong("playlistId") ?: return@composable
            PredictiveBackScreen(onBack = { navController.safePopBackStack(backStackEntry) }) {
                com.lastwave.app.ui.playlist.PlaylistDetailScreen(
                    playlistId = playlistId,
                    onBack = { navController.safePopBackStack(backStackEntry) },
                    onOpenPlaylist = { newId ->
                        navController.navigate(Screen.PlaylistDetail.createRoute(newId))
                    },
                )
            }
        }

        composable(
            route = Screen.ArtistDetail.route,
            arguments = listOf(
                androidx.navigation.navArgument("artistName") { type = androidx.navigation.NavType.StringType },
                androidx.navigation.navArgument("browseId") {
                    type = androidx.navigation.NavType.StringType
                    defaultValue = ""
                },
            ),
        ) { backStackEntry ->
            val artistName = backStackEntry.arguments?.getString("artistName").orEmpty()
            val browseId = backStackEntry.arguments?.getString("browseId")?.takeIf(String::isNotBlank)
            PredictiveBackScreen(onBack = { navController.safePopBackStack(backStackEntry) }) {
                com.lastwave.app.ui.artist.ArtistDetailScreen(
                    artistName = artistName,
                    browseId = browseId,
                    onBack = { navController.safePopBackStack(backStackEntry) },
                    onOpenAlbum = { title, artist, id ->
                        navController.navigate(Screen.AlbumDetail.createRoute(title, artist, id))
                    },
                    onOpenArtist = { name, id ->
                        navController.navigate(Screen.ArtistDetail.createRoute(name, id))
                    },
                )
            }
        }

        composable(
            route = Screen.AlbumDetail.route,
            arguments = listOf(
                androidx.navigation.navArgument("albumTitle") { type = androidx.navigation.NavType.StringType },
                androidx.navigation.navArgument("artistName") {
                    type = androidx.navigation.NavType.StringType
                    defaultValue = ""
                },
                androidx.navigation.navArgument("browseId") {
                    type = androidx.navigation.NavType.StringType
                    defaultValue = ""
                },
            ),
        ) { backStackEntry ->
            val albumTitle = backStackEntry.arguments?.getString("albumTitle").orEmpty()
            val artistName = backStackEntry.arguments?.getString("artistName").orEmpty()
            val browseId = backStackEntry.arguments?.getString("browseId")?.takeIf(String::isNotBlank)
            PredictiveBackScreen(onBack = { navController.safePopBackStack(backStackEntry) }) {
                com.lastwave.app.ui.album.AlbumDetailScreen(
                    albumTitle = albumTitle,
                    artistName = artistName,
                    browseId = browseId,
                    onBack = { navController.safePopBackStack(backStackEntry) },
                    onOpenArtist = { name, id ->
                        navController.navigate(Screen.ArtistDetail.createRoute(name, id))
                    },
                    onOpenAlbum = { title, artist, id ->
                        navController.navigate(Screen.AlbumDetail.createRoute(title, artist, id))
                    },
                )
            }
        }
    }
}

@Composable
private fun LaunchGate() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                tonalElevation = 6.dp,
                shadowElevation = 10.dp,
                modifier = Modifier.size(88.dp),
            ) {
                androidx.compose.foundation.Image(
                    painter = painterResource(com.lastwave.app.R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(Modifier.height(18.dp))
            Text("LastWave", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(20.dp))
            ExpressiveLoadingIndicator(message = "Preparing your music")
        }
    }
}
