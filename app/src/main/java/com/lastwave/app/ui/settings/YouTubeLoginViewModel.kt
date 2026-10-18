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
     * Connects with pasted cookies (raw header, Netscape cookies.txt, or JSON array).
     * Normalizes and validates via [com.lastwave.app.data.ytmusic.CookieNormalizer], then connects.
     */
    fun attemptCookieConnect(rawText: String) {
        if (_uiState.value.verifying) return
        val normalized = try {
            com.lastwave.app.data.ytmusic.CookieNormalizer.normalize(rawText)
        } catch (e: Exception) {
            _uiState.update {
                it.copy(errorMessage = e.message ?: "Invalid cookies. Make sure SAPISID is present.")
            }
            return
        }
        attemptConnect(normalized)
    }

    /**
     * Persists the cookies captured from the sign-in WebView or cookie login and reports
     * success as soon as they carry a real YouTube session (4.1.0 behavior).
     * Resolving the account name via InnerTube is best-effort enrichment —
     * a slow or unavailable account_menu must never reject a sign-in whose
     * cookies are valid. Sync kicks off immediately so the user sees their
     * playlists appear right away.
     */
    fun attemptConnect(rawCookieHeader: String?) {
        if (_uiState.value.verifying) return
        val raw = rawCookieHeader.orEmpty()
        val cookies = runCatching {
            com.lastwave.app.data.ytmusic.CookieNormalizer.normalize(raw)
        }.getOrDefault(raw)
        val hasSapisid = listOf("__Secure-3PAPISID=", "SAPISID=", "APISID=").any { it in cookies }
        if (!hasSapisid) {
            _uiState.update {
                it.copy(errorMessage = "Sign-in incomplete — finish signing in, then tap \"I'm signed in\".")
            }
            return
        }

        _uiState.update { it.copy(verifying = true, errorMessage = null) }
        viewModelScope.launch {
            val previous = ytAuthManager.connection.value
            try {
                ytAuthManager.connect(cookies, "", null, null)
                // Slow devices: the DataStore collector can re-emit the old
                // (disconnected) value after the optimistic update, making
                // fetchAccountInfo() bail instantly. Wait until memory matches
                // what was just persisted.
                kotlinx.coroutines.withTimeoutOrNull(4_000L) { ytAuthManager.awaitLoadedConnection() }
                // A successful YouTube Music login ends guest mode: the
                // LaunchGate then routes on the YT connection itself.
                runCatching { sessionPreferences.exitGuestMode() }
                // Resolve the account name for the connected badge. Retried
                // with backoff: right after sign-in Google is still finishing
                // its cookie redirect chain and the WebView may not have
                // flushed the final cookies yet, so re-read cookies between
                // attempts instead of hammering instantly. Failure to resolve
                // the name does NOT fail the connection.
                var info: com.lastwave.app.data.music.YtAccountInfo? = null
                for ((attempt, waitMs) in longArrayOf(0L, 1_200L, 2_500L, 4_000L).withIndex()) {
                    if (waitMs > 0L) kotlinx.coroutines.delay(waitMs)
                    if (attempt > 0) {
                        runCatching { android.webkit.CookieManager.getInstance().flush() }
                        if (ytAuthManager.refreshCookiesFromCookieManager()) {
                            kotlinx.coroutines.withTimeoutOrNull(2_000L) { ytAuthManager.awaitLoadedConnection() }
                        }
                    }
                    info = runCatching { innerTube.fetchAccountInfo() }.getOrNull()
                    if (info != null) break
                }
                // Best-effort identity (4.1.0 behavior): the session is already
                // persisted, so an account_menu miss only means the display
                // name falls back — it never rejects the sign-in.
                val displayName = info?.accountName?.takeIf { it.isNotBlank() } ?: "Google account"
                if (info != null) {
                    ytAuthManager.updateAccountIdentity(info.accountName, info.channelHandle, info.photoUrl)
                }
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
