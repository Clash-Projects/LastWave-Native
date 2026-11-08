package com.lastwave.app.ui.player

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Keeps screen snackbars above the floating mini player when it is visible. */
@Composable
fun MiniPlayerAwareSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    SnackbarHost(
        hostState = hostState,
        modifier = modifier.padding(bottom = LocalMiniPlayerSnackbarClearance.current),
    )
}
