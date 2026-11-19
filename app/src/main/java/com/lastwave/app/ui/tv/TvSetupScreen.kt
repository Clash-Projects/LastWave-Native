package com.lastwave.app.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.R

/**
 * First-run setup, in the manner of BitChord's TvSetupScreen (Apple TV
 * onboarding): one decision per screen, a centred title and explanation with
 * the choices stacked underneath, and BACK stepping backwards. The wizard
 * asks whether the virtual mouse pointer should be used inside the sign-in
 * pages, then offers both the YouTube and Spotify logins. Everything is
 * skippable — "Skip setup" completes the wizard with defaults.
 */
private enum class TvSetupStep { WELCOME, SIGN_IN, DONE }

@Composable
fun TvSetupScreen(onComplete: () -> Unit) {
    var step by rememberSaveable { mutableStateOf(TvSetupStep.WELCOME) }
    var loginTarget by remember { mutableStateOf<TvLoginTarget?>(null) }

    BackHandler(enabled = step != TvSetupStep.WELCOME) {
        step = TvSetupStep.entries[step.ordinal - 1]
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                (slideInHorizontally(tween(durationMillis = 320)) { if (forward) it / 6 else -it / 6 } +
                    fadeIn(tween(durationMillis = 320)))
                    .togetherWith(
                        slideOutHorizontally(tween(durationMillis = 240)) { if (forward) -it / 6 else it / 6 } +
                            fadeOut(tween(durationMillis = 200)),
                    )
            },
            label = "tvSetupStep",
        ) { current ->
            when (current) {
                TvSetupStep.WELCOME -> TvSetupPage(
                    title = stringResource(R.string.tv_setup_welcome_title),
                    message = stringResource(R.string.tv_setup_welcome_body),
                    hero = {
                        // The original launcher mark: the dark bars sit on the
                        // logo's lime disc — on their own they are all but
                        // invisible against the dark TV background.
                        Box(
                            modifier = Modifier
                                .size(96.dp)
                                .clip(CircleShape)
                                .background(colorResource(R.color.ic_launcher_background))
                                .padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(
                                painter = painterResource(R.drawable.ic_launcher_logo),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    },
                ) {
                    TvSetupButton(stringResource(R.string.tv_setup_continue), initialFocus = true) {
                        step = TvSetupStep.SIGN_IN
                    }
                    TvSetupButton(stringResource(R.string.tv_setup_skip)) {
                        TvSettings.setSetupCompleted(true)
                        onComplete()
                    }
                }


                TvSetupStep.SIGN_IN -> TvSetupPage(
                    title = stringResource(R.string.tv_setup_signin_title),
                    message = stringResource(R.string.tv_setup_signin_body),
                ) {
                    TvSetupButton(stringResource(R.string.tv_account_youtube)) {
                        loginTarget = TvLoginTarget.YOUTUBE
                    }
                    TvSetupButton(stringResource(R.string.tv_account_spotify)) {
                        loginTarget = TvLoginTarget.SPOTIFY
                    }
                    TvSetupButton(stringResource(R.string.tv_setup_continue), initialFocus = true) {
                        step = TvSetupStep.DONE
                    }
                }

                TvSetupStep.DONE -> TvSetupPage(
                    title = stringResource(R.string.tv_setup_done_title),
                    message = stringResource(R.string.tv_setup_done_body),
                    hero = {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(72.dp),
                        )
                    },
                ) {
                    TvSetupButton(stringResource(R.string.tv_setup_start), initialFocus = true) {
                        TvSettings.setSetupCompleted(true)
                        onComplete()
                    }
                }
            }
        }

        loginTarget?.let { target ->
            TvLoginScreen(
                target = target,
                onFinished = {
                    loginTarget = null
                    step = TvSetupStep.DONE
                },
                onCancel = { loginTarget = null },
            )
        }
    }
}

/** The common frame of every setup screen. */
@Composable
private fun TvSetupPage(
    title: String,
    message: String,
    hero: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.weight(1f))
        hero?.invoke()
        Spacer(modifier = Modifier.height(18.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(28.dp))
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
        Spacer(modifier = Modifier.weight(1.4f))
    }
}

/** The wizard's primary action pill; grabs focus when [initialFocus] is set. */
@Composable
private fun TvSetupButton(text: String, initialFocus: Boolean = false, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    var isFocused by remember { mutableStateOf(false) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(initialFocus) {
        if (initialFocus) {
            withFrameNanos { }
            runCatching { fr.requestFocus() }
        }
    }
    Box(
        modifier = Modifier
            .focusRequester(fr)
            .tvLift(focused = isFocused, shape = RoundedCornerShape(22.dp), focusedScale = 1.05f, elevation = 6.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(
                if (isFocused) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                }
            )
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 30.dp, vertical = 14.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = if (isFocused) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

/** A two-state choice pill (On / Off), styled like the rail's selected pill. */
@Composable
private fun TvSetupChoice(
    label: String,
    selected: Boolean,
    initialFocus: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    var isFocused by remember { mutableStateOf(false) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(initialFocus) {
        if (initialFocus) {
            withFrameNanos { }
            runCatching { fr.requestFocus() }
        }
    }
    val pillColor by animateColorAsState(
        targetValue = when {
            isFocused -> MaterialTheme.colorScheme.secondaryContainer
            selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        },
        animationSpec = tween(durationMillis = 150),
        label = "tvSetupChoiceColor",
    )
    val labelColor by animateColorAsState(
        targetValue = when {
            isFocused -> MaterialTheme.colorScheme.onSecondaryContainer
            selected -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(durationMillis = 150),
        label = "tvSetupChoiceLabel",
    )
    Box(
        modifier = Modifier
            .focusRequester(fr)
            .tvLift(focused = isFocused, shape = RoundedCornerShape(20.dp), focusedScale = 1.06f, elevation = 5.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(pillColor)
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 26.dp, vertical = 12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = labelColor,
        )
    }
}
