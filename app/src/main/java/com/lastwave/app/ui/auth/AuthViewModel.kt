package com.lastwave.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lastwave.app.data.backup.BackupCheck
import com.lastwave.app.data.backup.BackupRepository
import com.lastwave.app.data.backup.RestoreResult
import com.lastwave.app.data.model.AuthState
import com.lastwave.app.data.repository.LastFmAuthCallbackCoordinator
import com.lastwave.app.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Completes Last.fm web authentication from its deep-link callback token. */
sealed interface WebAuthState {
    data object Idle : WebAuthState
    data class AwaitingApproval(val authUrl: String) : WebAuthState
    data object CompletingSignIn : WebAuthState
    data object RestoringBackup : WebAuthState
    data class Error(val message: String) : WebAuthState
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val authCallback: LastFmAuthCallbackCoordinator,
    private val backupRepository: BackupRepository,
    private val sessionPreferences: com.lastwave.app.data.local.SessionPreferences,
) : ViewModel() {

    val authState: StateFlow<AuthState> = authRepository.authState

    /**
     * Guest onboarding state. True once the user taps "Continue as Guest";
     * LaunchGate treats it as onboarded (skip Login) exactly like a YouTube
     * Music connection or a Last.fm session.
     */
    val isGuestMode: StateFlow<Boolean> = sessionPreferences.guestMode

    private val _webAuthState = MutableStateFlow<WebAuthState>(WebAuthState.Idle)
    val webAuthState: StateFlow<WebAuthState> = _webAuthState.asStateFlow()

    private var completingToken: String? = null
    private var pendingRestoreContent: String? = null

    init {
        viewModelScope.launch {
            authCallback.pendingToken.collect { token ->
                token ?: return@collect
                completeSignIn(token)
            }
        }
    }

    /** Opens Last.fm's callback-based web authorization flow. */
    fun beginSignIn() {
        pendingRestoreContent = null
        val url = authRepository.authUrl()
        if (url == null) {
            _webAuthState.value = WebAuthState.Error("Add your Last.fm API key in Settings → Integrations first")
            return
        }
        _webAuthState.value = WebAuthState.AwaitingApproval(url)
    }

    fun signInDirect(username: String) {
        if (username.isBlank()) return
        _webAuthState.value = WebAuthState.CompletingSignIn
        viewModelScope.launch {
            val res = authRepository.signInDirect(username)
            if (res.isSuccess) {
                _webAuthState.value = WebAuthState.Idle
            } else {
                _webAuthState.value = WebAuthState.Error(res.exceptionOrNull()?.message ?: "Sign in failed")
            }
        }
    }

    fun beginRestoreAndSignIn(content: String) {
        when (backupRepository.checkBackup(content)) {
            is BackupCheck.Valid -> {
                val url = authRepository.authUrl()
                if (url == null) {
                    _webAuthState.value = WebAuthState.Error("Add your Last.fm API key in Settings → Integrations first")
                    return
                }
                pendingRestoreContent = content
                _webAuthState.value = WebAuthState.AwaitingApproval(url)
            }
            BackupCheck.UnsupportedSchema -> {
                _webAuthState.value = WebAuthState.Error("This backup was created by a newer LastWave version")
            }
            BackupCheck.Invalid -> {
                _webAuthState.value = WebAuthState.Error("That file is not a valid LastWave backup")
            }
        }
    }

    /**
     * Onboarding restore path for the YouTube Music-first flow: restores the
     * backup immediately without requiring Last.fm web auth. The LaunchGate
     * then routes based on whatever session the backup itself contained
     * (YouTube cookies, Last.fm session, or guest flag).
     */
    fun restoreBackupOnly(content: String) {
        when (backupRepository.checkBackup(content)) {
            BackupCheck.UnsupportedSchema -> {
                _webAuthState.value = WebAuthState.Error("This backup was created by a newer LastWave version")
                return
            }
            BackupCheck.Invalid -> {
                _webAuthState.value = WebAuthState.Error("That file is not a valid LastWave backup")
                return
            }
            is BackupCheck.Valid -> Unit
        }
        _webAuthState.value = WebAuthState.RestoringBackup
        viewModelScope.launch {
            _webAuthState.value = when (
                val restoreResult = backupRepository.restore(
                    content = content,
                    preserveSignedInSession = false,
                )
            ) {
                is RestoreResult.Success -> WebAuthState.Idle
                RestoreResult.UnsupportedSchema -> WebAuthState.Error(
                    "This backup was created by a newer LastWave version",
                )
                RestoreResult.InvalidFile -> WebAuthState.Error(
                    "That file is not a valid LastWave backup",
                )
                is RestoreResult.Failed -> WebAuthState.Error(restoreResult.message)
            }
        }
    }

    /** Lifecycle fallback; the deep link is normally observed in [init]. */
    fun onReturnedFromBrowser() {
        authCallback.pendingToken.value?.let { token ->
            completeSignIn(token)
        }
    }

    private fun completeSignIn(token: String) {
        authCallback.consume(token)
        if (completingToken != null || authState.value is AuthState.SignedIn) return
        completingToken = token
        _webAuthState.value = WebAuthState.CompletingSignIn
        viewModelScope.launch {
            val signInResult = authRepository.completeWebAuth(token)
            if (signInResult.isSuccess) {
                val backup = pendingRestoreContent
                if (backup == null) {
                    _webAuthState.value = WebAuthState.Idle
                } else {
                    _webAuthState.value = WebAuthState.RestoringBackup
                    _webAuthState.value = when (
                        val restoreResult = backupRepository.restore(
                            content = backup,
                            preserveSignedInSession = true,
                        )
                    ) {
                        is RestoreResult.Success -> WebAuthState.Idle
                        RestoreResult.UnsupportedSchema -> WebAuthState.Error(
                            "This backup was created by a newer LastWave version",
                        )
                        RestoreResult.InvalidFile -> WebAuthState.Error(
                            "That file is not a valid LastWave backup",
                        )
                        is RestoreResult.Failed -> WebAuthState.Error(restoreResult.message)
                    }
                    pendingRestoreContent = null
                }
            } else {
                _webAuthState.value = WebAuthState.Error(
                    signInResult.exceptionOrNull()?.message ?: "Could not complete Last.fm sign-in",
                )
            }
            completingToken = null
        }
    }

    fun cancelSignIn() {
        pendingRestoreContent = null
        _webAuthState.value = WebAuthState.Idle
    }

    fun signOut() {
        viewModelScope.launch { authRepository.signOut() }
    }

    /**
     * Account-free onboarding: persist guest mode so the next cold start
     * skips Login entirely. Any later YouTube Music connect or Last.fm
     * sign-in clears it (see SessionPreferences.saveSession/setSignedIn and
     * YouTubeLoginViewModel).
     */
    fun continueAsGuest() {
        viewModelScope.launch {
            runCatching { sessionPreferences.enterGuestMode() }
        }
    }

    fun exitGuestMode() {
        viewModelScope.launch {
            runCatching { sessionPreferences.exitGuestMode() }
        }
    }

    fun dismissError() {
        pendingRestoreContent = null
        authRepository.clearError()
        _webAuthState.value = WebAuthState.Idle
    }
}
