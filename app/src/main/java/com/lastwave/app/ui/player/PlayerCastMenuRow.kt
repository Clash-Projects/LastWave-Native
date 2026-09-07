package com.lastwave.app.ui.player

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory
import com.lastwave.app.playback.MusicPlayer

@Composable
internal fun PlayerCastMenuRow(player: MusicPlayer) {
    val context = LocalContext.current
    val available = remember(player) { player.initializeCast() }
    var routeButton by remember { mutableStateOf<MediaRouteButton?>(null) }
    Row(
        Modifier.fillMaxWidth().clickable {
            if (available) routeButton?.performClick()
            else Toast.makeText(context, "Chromecast requires Google Play services", Toast.LENGTH_SHORT).show()
        }.padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val badge = Modifier.size(40.dp).clip(RoundedCornerShape(13.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
        if (available) {
            AndroidView(
                modifier = badge,
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
            Icon(Icons.Filled.Cast, null, badge.padding(10.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(16.dp))
        Text("Chromecast", style = MaterialTheme.typography.bodyLarge)
    }
}
