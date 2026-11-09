package com.lastwave.app.ui.tv

import android.media.AudioManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.ui.theme.LastWaveTheme
import com.lastwave.app.ui.theme.ThemeViewModel
import dagger.hilt.android.AndroidEntryPoint

/**
 * Leanback launcher entry (Android TV / Google TV / Fire TV). Deliberately
 * separate from the phone shell: the D-pad home renders [TvHomeRoot] with no
 * touch navigation stack behind it, while playback still runs through the
 * process-wide [com.lastwave.app.playback.MusicPlayer] and its media session.
 */
@AndroidEntryPoint
class TvHomeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Switches Theme.LastWave.Splash to its post-splash theme, as on phone.
        runCatching { installSplashScreen() }
        super.onCreate(savedInstanceState)
        // Volume keys must control music, matching the phone shell.
        volumeControlStream = AudioManager.STREAM_MUSIC
        setContent {
            val themeViewModel: ThemeViewModel = hiltViewModel()
            val themeState by themeViewModel.uiState.collectAsStateWithLifecycle()
            LastWaveTheme(themeState = themeState) {
                TvHomeRoot()
            }
        }
    }
}
