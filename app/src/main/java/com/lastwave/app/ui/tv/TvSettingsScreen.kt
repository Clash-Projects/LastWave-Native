package com.lastwave.app.ui.tv

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lastwave.app.BuildConfig
import com.lastwave.app.R
import com.lastwave.app.data.local.SettingsPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The settings that matter on a TV, all backed by the shared stores. */
data class TvSettingsUiState(
    val crossfadeEnabled: Boolean = false,
    val crossfadeSeconds: Int = 5,
    val ytAccountName: String? = null,
    val spotifyConnected: Boolean = false,
)

@HiltViewModel
class TvSettingsViewModel @Inject constructor(
    private val settingsPreferences: SettingsPreferences,
    private val ytAuth: com.lastwave.app.data.ytmusic.YtMusicAuthManager,
    private val spotifyPreferences: com.lastwave.app.data.spotify.SpotifyPreferences,
) : ViewModel() {

    val uiState: StateFlow<TvSettingsUiState> = combine(
        settingsPreferences.settings,
        ytAuth.connection,
        spotifyPreferences.spdc,
    ) { misc, yt, spdc ->
        TvSettingsUiState(
            crossfadeEnabled = misc.crossfadeEnabled,
            crossfadeSeconds = misc.crossfadeSeconds,
            ytAccountName = if (yt.cookies.isEmpty()) {
                null
            } else {
                yt.accountName.ifBlank { "Google account" }
            },
            spotifyConnected = !spdc.isNullOrBlank(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TvSettingsUiState())

    fun setCrossfade(enabled: Boolean) = viewModelScope.launch {
        settingsPreferences.setCrossfadeEnabled(enabled)
    }

    fun setCrossfadeSeconds(seconds: Int) = viewModelScope.launch {
        settingsPreferences.setCrossfadeSeconds(seconds.coerceIn(1, 12))
    }

    fun signOutYouTube() = viewModelScope.launch {
        runCatching { ytAuth.signOut() }
    }

    fun signOutSpotify() = viewModelScope.launch {
        // Blank sp_dc reads as signed-out everywhere and invalidates the token.
        spotifyPreferences.saveSpdc("")
    }
}

@Composable
fun TvSettingsTab(viewModel: TvHomeViewModel = hiltViewModel()) {
    val settingsViewModel: TvSettingsViewModel = hiltViewModel()
    val ui by settingsViewModel.uiState.collectAsStateWithLifecycle()
    var loginTarget by remember { mutableStateOf<TvLoginTarget?>(null) }

    // Tab-entry focus: the first actionable account row grabs focus so the
    // remote is never dead on this pane (one entry requester is attached to
    // whichever of the YouTube sign-in / sign-out rows is currently present).
    val entry = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { entry.requestFocus() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 48.dp, end = 48.dp, top = 40.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "title") {
                Text(
                    text = stringResource(R.string.tv_tab_settings),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            item(key = "accounts-header") {
                TvSettingsSectionTitle(stringResource(R.string.tv_settings_accounts), Icons.Rounded.AccountCircle)
            }
            item(key = "account-youtube") {
                TvAccountRow(
                    title = stringResource(R.string.tv_account_youtube),
                    modifier = if (ui.ytAccountName == null) Modifier.focusRequester(entry) else Modifier,
                    status = ui.ytAccountName?.let {
                        stringResource(R.string.tv_account_signed_in_as, it)
                    } ?: stringResource(R.string.tv_account_not_signed_in),
                    connected = ui.ytAccountName != null,
                    icon = Icons.Rounded.MusicNote,
                    onClick = { loginTarget = TvLoginTarget.YOUTUBE },
                )
            }
            if (ui.ytAccountName != null) {
                item(key = "account-youtube-out") {
                    TvSignOutRow(
                        label = stringResource(R.string.tv_account_youtube),
                        onClick = settingsViewModel::signOutYouTube,
                        modifier = if (ui.ytAccountName != null) Modifier.focusRequester(entry) else Modifier,
                    )
                }
            }
            item(key = "account-spotify") {
                TvAccountRow(
                    title = stringResource(R.string.tv_account_spotify),
                    status = if (ui.spotifyConnected) {
                        stringResource(R.string.tv_account_signed_in)
                    } else {
                        stringResource(R.string.tv_account_not_signed_in)
                    },
                    connected = ui.spotifyConnected,
                    icon = Icons.Rounded.GraphicEq,
                    onClick = { loginTarget = TvLoginTarget.SPOTIFY },
                )
            }
            if (ui.spotifyConnected) {
                item(key = "account-spotify-out") {
                    TvSignOutRow(label = stringResource(R.string.tv_account_spotify), onClick = settingsViewModel::signOutSpotify)
                }
            }

            item(key = "playback-header") {
                TvSettingsSectionTitle(stringResource(R.string.tv_settings_playback), Icons.Rounded.Timer)
            }
            item(key = "crossfade") {
                TvSettingsRow(
                    icon = Icons.Rounded.Timer,
                    title = stringResource(R.string.tv_settings_crossfade),
                    subtitle = stringResource(R.string.tv_settings_crossfade_desc),
                    checked = ui.crossfadeEnabled,
                    onToggle = { settingsViewModel.setCrossfade(!ui.crossfadeEnabled) },
                )
            }
            if (ui.crossfadeEnabled) {
                item(key = "crossfade-seconds") {
                    TvStepperRow(
                        title = stringResource(R.string.tv_settings_crossfade_seconds, ui.crossfadeSeconds),
                        value = ui.crossfadeSeconds,
                        min = 1,
                        max = 12,
                        onChange = settingsViewModel::setCrossfadeSeconds,
                    )
                }
            }

            item(key = "about-header") {
                TvSettingsSectionTitle(stringResource(R.string.tv_settings_about), Icons.Rounded.Info)
            }
            item(key = "about-card") {
                TvAboutCard()
            }
            item(key = "remote-help") {
                TvRemoteTipsCard()
            }
        }

        loginTarget?.let { target ->
            TvLoginScreen(
                target = target,
                // The login screen persists the connection itself (the same
                // ViewModels the phone build uses), so closing is all the
                // caller does — the accounts rows react to the stores.
                onFinished = { loginTarget = null },
                onCancel = { loginTarget = null },
            )
        }
    }
}

/** Account line: before sign-in the whole row opens the login WebView; once
    connected the sign-in affordance disappears entirely — the card becomes
    display-only and a separate sign-out row logs the account out. */
@Composable
private fun TvAccountRow(
    title: String,
    status: String,
    connected: Boolean,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    var isFocused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .tvLift(focused = isFocused && !connected, shape = RoundedCornerShape(16.dp), focusedScale = 1.01f, elevation = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (isFocused && !connected) {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                }
            )
            .onFocusChanged { isFocused = it.isFocused }
            .then(
                // Signed-in cards take no action: OK must never reopen the
                // login flow for an account that is already connected.
                if (connected) Modifier else
                    Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)
            )
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = if (connected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (connected) {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        } else {
            Text(
                text = stringResource(R.string.tv_account_sign_in),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun TvSignOutRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    var isFocused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .tvLift(focused = isFocused, shape = RoundedCornerShape(16.dp), focusedScale = 1.01f, elevation = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (isFocused) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerLow)
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Logout,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp),
            )
        }
        Text(
            text = stringResource(R.string.tv_account_sign_out, label),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun TvSettingsSectionTitle(title: String, icon: ImageVector) {
    Row(
        modifier = Modifier.padding(top = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** A settings line: whole row is the D-pad target; OK activates the toggle. */
@Composable
private fun TvSettingsRow(
    icon: ImageVector?,
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    var isFocused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .tvLift(focused = isFocused, shape = RoundedCornerShape(16.dp), focusedScale = 1.01f, elevation = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (isFocused) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow)
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(interactionSource = interaction, indication = null, onClick = onToggle)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TvToggle(checked = checked)
    }
}

/** Visual-only switch (the row is the click target, so OK never double-fires). */
@Composable
private fun TvToggle(checked: Boolean) {
    val thumbStart by animateDpAsState(
        targetValue = if (checked) 33.dp else 3.dp,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f),
        label = "tvToggleThumb",
    )
    Box(
        modifier = Modifier
            .width(64.dp)
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (checked) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                }
            ),
    ) {
        Box(
            modifier = Modifier
                .padding(start = thumbStart)
                .size(30.dp)
                .clip(CircleShape)
                .background(
                    if (checked) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                .align(Alignment.CenterStart),
        )
    }
}

/** - value + stepper row for small numbers (crossfade seconds). */
@Composable
private fun TvStepperRow(
    title: String,
    value: Int,
    min: Int,
    max: Int,
    onChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        TvRoundKeyButton(
            icon = Icons.Rounded.Remove,
            contentDescription = "-",
            enabled = value > min,
            onClick = { onChange(value - 1) },
        )
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(40.dp),
        )
        TvRoundKeyButton(
            icon = Icons.Rounded.Add,
            contentDescription = "+",
            enabled = value < max,
            onClick = { onChange(value + 1) },
        )
    }
}

@Composable
private fun TvRoundKeyButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    var isFocused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(48.dp)
            .tvLift(
                focused = isFocused,
                shape = CircleShape,
                focusedScale = 1.1f,
                elevation = 8.dp,
                pressed = false,
            )
            .clip(CircleShape)
            .background(
                when {
                    !enabled -> MaterialTheme.colorScheme.surfaceContainerLow
                    isFocused -> MaterialTheme.colorScheme.surfaceContainerHighest
                    else -> MaterialTheme.colorScheme.surfaceContainerHigh
                }
            )
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun TvAboutCard() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Tv,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(32.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.tv_settings_about_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.tv_settings_about_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TvRemoteTipsCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.tv_settings_remote_tips),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.tv_settings_remote_tips_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
