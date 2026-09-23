package com.lastwave.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.ytmusic.YtMusicAuthManager
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.ytmusic.YtMusicSyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class YouTubeLoginUiState(
    val verifying: Boolean = false,
    val connectedName: String? = null,
    val errorMessage: String? = null,
)

@HiltViewModel
class YouTubeLoginViewModel @Inject constructor(
    private val ytAuthManager: YtMusicAuthManager,
    private val innerTube: InnerTubeMusicApi,
    private val syncManager: YtMusicSyncManager,
    private val sessionPreferences: com.lastwave.app.data.local.SessionPreferences,
    private val ytMusicPreferences: com.lastwave.app.data.ytmusic.YtMusicPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(YouTubeLoginUiState())
    val uiState: StateFlow<YouTubeLoginUiState> = _uiState.asStateFlow()

    /**
     * Persists the cookies captured from the sign-in WebView, then verifies
     * the session with an authenticated browse (authoritative — no HTML
     * scraping) before reporting success. Atomic: the previous session is
     * restored when verification fails, so a bad paste can never disconnect
     * a working account. Sync kicks off immediately so the user sees their
     * playlists appear right away.
     */
    fun attemptConnect(rawCookieHeader: String?) {
        if (_uiState.value.verifying) return
        val cookies = rawCookieHeader.orEmpty()
        val hasSapisid = listOf("__Secure-3PAPISID=", "SAPISID=", "APISID=").any { it in cookies }
        val hasLoginInfo = "LOGIN_INFO=" in cookies
        if (!hasSapisid || !hasLoginInfo) {
            _uiState.update {
                it.copy(errorMessage = "Sign-in incomplete — finish signing in, then tap \"I'm signed in\".")
            }
            return
        }

        _uiState.update { it.copy(verifying = true, errorMessage = null) }
        viewModelScope.launch {
            val previous = ytAuthManager.connection.value
            try {
                ytAuthManager.connect(rawCookieHeader ?: return@launch, "", null, null)
                // A successful YouTube Music login ends guest mode: the
                // LaunchGate then routes on the YT connection itself.
                runCatching { sessionPreferences.exitGuestMode() }
                // Verify the session is really authenticated before reporting
                // success (desktop `verifyConnection()` parity: an
                // authenticated browse, not just cookie presence).
                val info = runCatching { innerTube.fetchAccountInfo() }.getOrNull()
                    ?: throw java.io.IOException("YouTube rejected the session — sign in again.")
                val displayName = info.accountName.ifBlank { "Google account" }
                ytAuthManager.updateAccountIdentity(info.accountName, info.channelHandle, info.photoUrl)
                _uiState.update { it.copy(verifying = false, connectedName = displayName) }
                viewModelScope.launch {
                    runCatching { syncManager.syncNow("connected") }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                runCatching {
                    if (previous.isConnected) {
                        ytMusicPreferences.saveConnection(
                            previous.cookies,
                            previous.accountName,
                            previous.channelHandle,
                            previous.photoUrl,
                            onBehalfOfUser = previous.onBehalfOfUser,
                            authUserIndex = previous.authUserIndex,
                            pageId = previous.pageId,
                        )
                    } else {
                        ytAuthManager.signOut()
                    }
                }
                _uiState.update {
                    it.copy(
                        verifying = false,
                        errorMessage = "Couldn't finish connecting: ${e.localizedMessage ?: e.message}",
                    )
                }
            } catch (error: LinkageError) {
                _uiState.update {
                    it.copy(
                        verifying = false,
                        errorMessage = "YouTube Music sign-in isn't supported by this ROM.",
                    )
                }
            }
        }
    }

    fun dismissError() = _uiState.update { it.copy(errorMessage = null) }
}
