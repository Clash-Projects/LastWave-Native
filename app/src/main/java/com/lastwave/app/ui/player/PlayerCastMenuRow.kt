package com.lastwave.app.ui.player

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory
import com.lastwave.app.R
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.ui.common.GroupIconBadgeShape
import com.lastwave.app.ui.common.expressivePressScale

@Composable
internal fun PlayerCastMenuRow(player: MusicPlayer, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val context = LocalContext.current
    val available = remember(player) { player.initializeCast() }
    var routeButton by remember { mutableStateOf<MediaRouteButton?>(null) }
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .expressivePressScale(interactionSource, targetScale = 0.98f)
            .clip(MaterialTheme.shapes.medium)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = ripple(),
                onClick = hapticClick {
                    if (available) routeButton?.performClick()
                    else Toast.makeText(context, context.getString(R.string.cast_unavailable), Toast.LENGTH_SHORT).show()
                },
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(GroupIconBadgeShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            if (available) {
                AndroidView(
                    modifier = Modifier.size(24.dp),
                    update = { it.isEnabled = enabled },
                    factory = { viewContext ->
                        MediaRouteButton(viewContext).apply {
                            background = null
                            contentDescription = "Chromecast"
                            CastButtonFactory.setUpMediaRouteButton(viewContext, this)
                            routeButton = this
                        }
                    },
                )
            } else {
                Icon(
                    Icons.Outlined.Cast,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            stringResource(R.string.menu_cast_short),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
