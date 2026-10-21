package com.lastwave.app.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.spotify.SpotifyAuthRepository
import com.lastwave.app.data.spotify.SpotifyPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SpotifyLoginViewModel @Inject constructor(
    private val spotifyPreferences: SpotifyPreferences,
    private val spotifyAuthRepository: SpotifyAuthRepository,
) : ViewModel() {

    val isConnected = spotifyPreferences.isConnected
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val spdc = spotifyPreferences.spdc
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** How a sign-in ended. */
    enum class Outcome {
        /** The session works. */
        CONNECTED,

        /**
         * The cookie was stored, but Spotify would not turn it into a token.
         *
         * Storing the cookie is not the same as having a session: the cookie is only
         * worth anything if Spotify will exchange it for a token, and it will not
         * always. Finding that out here is the difference between a sign-in that works
         * and one that only looks like it does.
         */
        SESSION_REFUSED,

        /** The cookie could not be stored. */
        FAILED,
    }

    /**
     * Persists the captured `sp_dc` cookie and checks that it is a session.
     *
     * The write has to land: the screen must only latch "connected" on success,
     * otherwise a failed write would leave the WebView latched and every later
     * navigation blocked. The exchange is checked as well, so a sign-in that cannot
     * be used says so instead of leaving every later lookup to fail quietly.
     */
    fun saveSpdc(value: String, onDone: (Outcome) -> Unit) {
        viewModelScope.launch {
            val saved = runCatching { spotifyPreferences.saveSpdc(value) }.isSuccess
            if (!saved) {
                onDone(Outcome.FAILED)
                return@launch
            }
            val usable = runCatching { spotifyAuthRepository.getPersonalToken() }
                .getOrNull()
                ?.isNotBlank() == true
            if (!usable) {
                Log.w(TAG, "The session cookie was stored but Spotify would not turn it into a token")
                spotifyPreferences.clearConnection()
                onDone(Outcome.SESSION_REFUSED)
                return@launch
            }
            onDone(Outcome.CONNECTED)
        }
    }

    fun clearConnection() {
        viewModelScope.launch {
            spotifyPreferences.clearConnection()
        }
    }

    private companion object {
        private const val TAG = "SpotifyLogin"
    }
}
