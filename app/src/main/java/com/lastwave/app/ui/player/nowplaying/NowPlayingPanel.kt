package com.lastwave.app.ui.player.nowplaying

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.lastwave.app.R
import com.lastwave.app.data.local.SeekbarStyle
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.MusicPlayerState
import com.lastwave.app.playback.PlayableTrack
import com.lastwave.app.playback.PlaybackProgressState
import com.lastwave.app.playback.SignalPathReport
import com.lastwave.app.ui.common.ExpressiveInlineLoadingIndicator
import com.lastwave.app.ui.common.playingMarquee
import com.lastwave.app.ui.player.AnimatedPlayPauseIcon
import com.lastwave.app.ui.player.AppleMusicQualityBadge
import com.lastwave.app.ui.player.PlayerLikeButton
import com.lastwave.app.ui.player.SeekBar
import com.lastwave.app.ui.player.automix.AutoMixPopupState
import com.lastwave.app.ui.player.hapticClick
import com.lastwave.app.ui.theme.LiquidGlassPreset
import com.lastwave.app.ui.theme.liquidGlassChrome
import com.lastwave.app.ui.theme.liquidGlassContainerColor
import com.lastwave.app.util.ArtistHelper
import kotlinx.coroutines.flow.StateFlow

/**
 * The new player's controls: title, seek bar, quality badge, one transport row
 * and one action row. Reuses the classic seek bar and badge as they are.
 */
@Composable
internal fun NowPlayingPanel(
    track: PlayableTrack,
    state: MusicPlayerState,
    player: MusicPlayer,
    progressState: StateFlow<PlaybackProgressState>,
    seekbarStyle: SeekbarStyle,
    wavySeekbarEnabled: Boolean,
    skin: PlayerSkin,
    colors: PlayerSkinColors,
    scheme: ColorScheme,
    signalPath: SignalPathReport,
    autoMixUi: AutoMixPopupState,
    onOpenSignalPath: () -> Unit,
    isLiked: Boolean,
    onToggleLiked: () -> Unit,
    onOpenArtist: (String) -> Unit,
    marquee: Boolean,
    onOpenLyrics: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenAudioControls: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // White-on-veil seek bar and badge belong to glass; Material lets them
    // read the song scheme the screen provides.
    val translucent = skin == PlayerSkin.Glass
    val isCustomAudio = state.speed != 1f || state.pitch != 1f
    val actions = listOf(
        PlayerAction(
            label = stringResource(R.string.now_playing_action_queue),
            icon = Icons.AutoMirrored.Filled.QueueMusic,
            onClick = onOpenQueue,
        ),
        PlayerAction(
            label = stringResource(R.string.now_playing_action_lyrics),
            icon = Icons.Filled.Lyrics,
            onClick = onOpenLyrics,
        ),
        PlayerAction(
            label = stringResource(R.string.now_playing_action_audio),
            icon = if (isCustomAudio) Icons.Filled.Tune else Icons.Filled.GraphicEq,
            onClick = onOpenAudioControls,
            active = isCustomAudio,
        ),
    )

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        TitleRow(
            track = track,
            colors = colors,
            marquee = marquee,
            isLiked = isLiked,
            onToggleLiked = onToggleLiked,
            onOpenArtist = onOpenArtist,
        )
        Spacer(Modifier.height(12.dp))
        SeekBar(
            progressState = progressState,
            isPlaying = state.isPlaying,
            trackKey = track.videoId ?: "${track.artist}|${track.title}",
            seekbarStyle = seekbarStyle,
            wavyEnabled = wavySeekbarEnabled,
            onSeek = player::seekTo,
            isTranslucent = translucent,
            fallbackDurationMs = track.durationMs ?: state.durationMs,
            primaryColor = colors.accent,
            secondaryColor = scheme.secondary,
            tertiaryColor = scheme.tertiary,
            telemetry = state.deckBlendTelemetry,
        )
        AppleMusicQualityBadge(
            state = state,
            signalPath = signalPath,
            isTranslucent = translucent,
            autoMixUi = autoMixUi,
            progressState = progressState,
            onClick = onOpenSignalPath,
        )
        Spacer(Modifier.height(16.dp))
        TransportRow(state = state, player = player, skin = skin, colors = colors)
        Spacer(Modifier.height(24.dp))
        ActionRow(skin = skin, colors = colors, actions = actions)
    }
}

@Composable
private fun TitleRow(
    track: PlayableTrack,
    colors: PlayerSkinColors,
    marquee: Boolean,
    isLiked: Boolean,
    onToggleLiked: () -> Unit,
    onOpenArtist: (String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.headlineSmall.copy(
                    letterSpacing = (-0.35).sp,
                    fontWeight = FontWeight.ExtraBold,
                ),
                color = colors.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .playingMarquee(marquee),
            )
            Spacer(Modifier.height(4.dp))
            ArtistLine(rawArtist = track.artist, color = colors.accent, marquee = marquee, onOpenArtist = onOpenArtist)
        }
        Spacer(Modifier.width(12.dp))
        PlayerLikeButton(
            liked = isLiked,
            onClick = hapticClick(
                if (isLiked) HapticFeedbackType.ToggleOff else HapticFeedbackType.Confirm,
                onToggleLiked,
            ),
            size = 48.dp,
        )
    }
}

@Composable
private fun ArtistLine(
    rawArtist: String,
    color: Color,
    marquee: Boolean,
    onOpenArtist: (String) -> Unit,
) {
    val artists = remember(rawArtist) { ArtistHelper.splitArtists(rawArtist) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .playingMarquee(marquee),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        artists.forEachIndexed { index, name ->
            Text(
                text = if (index < artists.lastIndex) "$name, " else name,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = color,
                maxLines = 1,
                modifier = Modifier.clickable(
                    interactionSource = null,
                    indication = null,
                    role = Role.Button,
                    onClick = hapticClick { onOpenArtist(name) },
                ),
            )
        }
    }
}

@Composable
private fun TransportRow(
    state: MusicPlayerState,
    player: MusicPlayer,
    skin: PlayerSkin,
    colors: PlayerSkinColors,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToggleButton(
            skin = skin,
            colors = colors,
            active = state.shuffleEnabled,
            icon = Icons.Filled.Shuffle,
            description = stringResource(
                if (state.shuffleEnabled) R.string.now_playing_shuffle_on else R.string.now_playing_shuffle_off,
            ),
            onClick = player::toggleShuffle,
        )
        SkinButton(
            skin = skin,
            onClick = player::previous,
            container = colors.container,
            contentColor = colors.content,
            modifier = Modifier.size(56.dp),
        ) {
            Icon(Icons.Filled.SkipPrevious, stringResource(R.string.now_playing_previous), Modifier.size(30.dp))
        }
        PlayButton(state = state, player = player, skin = skin, colors = colors)
        SkinButton(
            skin = skin,
            onClick = player::next,
            container = colors.container,
            contentColor = colors.content,
            modifier = Modifier.size(56.dp),
        ) {
            Icon(Icons.Filled.SkipNext, stringResource(R.string.now_playing_next), Modifier.size(30.dp))
        }
        ToggleButton(
            skin = skin,
            colors = colors,
            active = state.repeatMode != Player.REPEAT_MODE_OFF,
            icon = if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
            description = stringResource(
                when (state.repeatMode) {
                    Player.REPEAT_MODE_ONE -> R.string.now_playing_repeat_one
                    Player.REPEAT_MODE_ALL -> R.string.now_playing_repeat_all
                    else -> R.string.now_playing_repeat_off
                },
            ),
            onClick = player::cycleRepeatMode,
            // Repeat one is the last stop before off.
            haptic = if (state.repeatMode == Player.REPEAT_MODE_ONE) {
                HapticFeedbackType.ToggleOff
            } else {
                HapticFeedbackType.ToggleOn
            },
        )
    }
}

@Composable
private fun PlayButton(
    state: MusicPlayerState,
    player: MusicPlayer,
    skin: PlayerSkin,
    colors: PlayerSkinColors,
) {
    // Glass has no solid fill to carry the song colour, so a soft glow does it
    // while playing. Material's filled button already shows it.
    val glowAlpha by animateFloatAsState(
        targetValue = if (skin == PlayerSkin.Glass && state.isPlaying) 0.4f else 0f,
        animationSpec = tween(600),
        label = "playGlow",
    )
    Box(
        Modifier
            .size(72.dp)
            .drawBehind {
                if (glowAlpha > 0f) {
                    val radius = size.minDimension * 0.85f
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(colors.accent.copy(alpha = glowAlpha), Color.Transparent),
                            center = center,
                            radius = radius,
                        ),
                        radius = radius,
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        SkinButton(
            skin = skin,
            onClick = player::togglePlayPause,
            container = colors.playContainer,
            contentColor = colors.onPlay,
            modifier = Modifier.fillMaxSize(),
            glassPreset = LiquidGlassPreset.FloatingControls,
            haptic = if (state.isPlaying) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn,
        ) {
            if (state.isBuffering) {
                ExpressiveInlineLoadingIndicator(
                    size = 30.dp,
                    color = LocalContentColor.current,
                    strokeWidth = 3.dp,
                )
            } else {
                AnimatedPlayPauseIcon(state.isPlaying, Modifier.size(34.dp))
            }
        }
    }
}

@Composable
private fun ToggleButton(
    skin: PlayerSkin,
    colors: PlayerSkinColors,
    active: Boolean,
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    haptic: HapticFeedbackType = if (active) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn,
) {
    // Material keeps an idle toggle bare so it recedes; glass keeps its lens.
    val idle = if (skin == PlayerSkin.Glass) colors.container else Color.Transparent
    val container by animateColorAsState(if (active) colors.activeContainer else idle, label = "toggleContainer")
    val content by animateColorAsState(if (active) colors.onActive else colors.contentMuted, label = "toggleContent")
    SkinButton(
        skin = skin,
        onClick = onClick,
        container = container,
        contentColor = content,
        haptic = haptic,
        modifier = Modifier
            .size(48.dp)
            .semantics { selected = active },
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(22.dp))
    }
}

private class PlayerAction(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
    /** Lit while a non-default state applies, e.g. custom speed or pitch. */
    val active: Boolean = false,
)

@Composable
private fun ActionRow(
    skin: PlayerSkin,
    colors: PlayerSkinColors,
    actions: List<PlayerAction>,
) {
    val glass = skin == PlayerSkin.Glass
    val shape = if (glass) CircleShape else RoundedCornerShape(20.dp)
    // Same structure as LiquidGlassSurface, minus its whole-surface click.
    Surface(
        shape = shape,
        color = if (glass) liquidGlassContainerColor(colors.container) else colors.container,
        contentColor = colors.content,
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .then(if (glass) Modifier.liquidGlassChrome(shape, true, LiquidGlassPreset.PlayerControls) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            actions.forEach { action ->
                ActionItem(action = action, colors = colors, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ActionItem(
    action: PlayerAction,
    colors: PlayerSkinColors,
    modifier: Modifier,
) {
    val indicator by animateColorAsState(
        if (action.active) colors.activeContainer else Color.Transparent,
        label = "actionIndicator",
    )
    val tint by animateColorAsState(
        if (action.active) colors.onActive else colors.content,
        label = "actionTint",
    )
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clickable(role = Role.Button, onClick = hapticClick(action = action.onClick)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(width = 52.dp, height = 28.dp)
                .background(indicator, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(action.icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(2.dp))
        Text(
            action.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = colors.content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
