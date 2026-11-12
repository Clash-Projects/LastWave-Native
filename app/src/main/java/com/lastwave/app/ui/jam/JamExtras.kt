package com.lastwave.app.ui.jam

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lastwave.app.data.jam.ChatMessagePayload
import com.lastwave.app.data.jam.ChatReplyPayload
import com.lastwave.app.data.jam.JamNotice
import com.lastwave.app.data.jam.JamStations
import com.lastwave.app.data.jam.ParticipantPayload
import com.lastwave.app.data.jam.TrackInfoPayload
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/** Wall-clock time shown to the user for "try again at ...". */
fun formatRetryClock(ms: Long): String =
    SimpleDateFormat("h:mm:ss a", Locale.getDefault()).format(Date(ms))

/**
 * Shows a server rejection (cooldown, host rules) without leaving the room.
 * Dismisses itself once the cooldown has passed.
 */
@Composable
fun JamNoticeBanner(
    notice: JamNotice?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (notice == null) return
    LaunchedEffect(notice) {
        val wait = if (notice.retryAtMs > 0L) {
            (notice.retryAtMs - System.currentTimeMillis() + 500L).coerceAtLeast(1500L)
        } else {
            6000L
        }
        delay(wait)
        onDismiss()
    }
    val text = if (notice.retryAtMs > 0L) {
        "One pause or change per minute. Try again at ${formatRetryClock(notice.retryAtMs)}."
    } else {
        notice.message
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) {
                Text("OK", color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** The always-active rooms (India, International) with one-tap join buttons. */
@Composable
fun JamStationsList(
    accent: Color,
    onAccent: Color,
    enabled: Boolean,
    onJoin: (String) -> Unit,
    counts: Map<String, Int> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "ALWAYS-ON ROOMS",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        JamStations.forEach { station ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(station.title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(
                        text = counts[station.code]?.let { "$it listening · ${station.code}" }
                            ?: "${station.blurb} · ${station.code}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = { onJoin(station.code) },
                    enabled = enabled,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = onAccent),
                ) {
                    Text("Join", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** Quoted original message shown inside a reply bubble. */
@Composable
fun ReplyQuote(reply: ChatReplyPayload, accent: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .padding(bottom = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.10f)),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(34.dp)
                .background(accent),
        )
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                text = reply.userName,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = accent,
            )
            Text(
                text = reply.text,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Bar above the chat input showing which message is being replied to. */
@Composable
fun ReplyPreviewBar(
    message: ChatMessagePayload,
    accent: Color,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(accent.copy(alpha = 0.10f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(40.dp)
                .background(accent),
        )
        Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 4.dp)) {
            Text(
                text = "Replying to ${message.userName}",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = accent,
            )
            Text(
                text = message.text,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onCancel, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "Cancel reply", modifier = Modifier.size(16.dp))
        }
    }
}

/**
 * "Make host" / "Remove host" action for the main host. Renders nothing when
 * the action does not apply (not main host, self, or the two-host limit is
 * already reached).
 */
@Composable
fun HostActionButton(
    participant: ParticipantPayload,
    myUserId: String,
    isMainHost: Boolean,
    coHostId: String,
    accent: Color,
    onGrant: (String) -> Unit,
    onRevoke: (String) -> Unit,
    modifier: Modifier = Modifier,
    isRadio: Boolean = false,
    onMakeAdmin: (String) -> Unit = {},
) {
    if (!isMainHost || participant.userId == myUserId) return
    if (isRadio) {
        // Live stations have one admin and no co-hosts.
        TextButton(onClick = { onMakeAdmin(participant.userId) }, modifier = modifier) {
            Text("Make admin", fontSize = 12.sp, color = accent, fontWeight = FontWeight.Bold)
        }
        return
    }
    when {
        participant.isCoHost -> TextButton(onClick = { onRevoke(participant.userId) }, modifier = modifier) {
            Text("Remove host", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        }
        coHostId.isEmpty() -> TextButton(onClick = { onGrant(participant.userId) }, modifier = modifier) {
            Text("Make host", fontSize = 12.sp, color = accent, fontWeight = FontWeight.Bold)
        }
    }
}

/** Label for a participant's role badge. */
fun roleBadge(p: ParticipantPayload, isRadio: Boolean = false): String =
    if (isRadio) "ADMIN" else if (p.isCoHost) "CO-HOST" else "HOST"

/** Read-only view of the admin's upcoming queue. */
@Composable
fun JamUpNext(
    queue: List<TrackInfoPayload>,
    isAdmin: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    maxItems: Int = 5,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = if (isAdmin) "UP NEXT · YOUR QUEUE IS SHARED" else "UP NEXT · ADMIN QUEUE",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (queue.isEmpty()) {
            Text("Nothing queued.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            queue.take(maxItems).forEachIndexed { i, t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", fontSize = 12.sp, color = accent, fontWeight = FontWeight.Bold, modifier = Modifier.width(22.dp))
                    Text(
                        text = if (t.artist.isBlank()) t.title else "${t.title} · ${t.artist}",
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (queue.size > maxItems) {
                Text("+${queue.size - maxItems} more", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Small line shown while pause/skip are locked in a public room. */
@Composable
fun JamCooldownHint(cooldownUntilMs: Long, modifier: Modifier = Modifier) {
    if (cooldownUntilMs <= 0L) return
    Text(
        text = "Pause & skip locked until ${formatRetryClock(cooldownUntilMs)}",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
