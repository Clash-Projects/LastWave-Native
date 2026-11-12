package com.lastwave.app.ui.feed.cards

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest

/** Full-bleed daily song card. A caption is shown only when supplied by the response. */
@Composable
fun ShelfFeaturedCard(
    title: String,
    artist: String,
    artworkUrl: String?,
    caption: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onMenuClick: (() -> Unit)? = null,
    fallbackIcon: ImageVector = Icons.Filled.MusicNote,
    cardWidth: Dp = 330.dp,
    isVisible: Boolean = true,
) {
    val context = LocalContext.current
    val inspection = LocalInspectionMode.current
    var started by rememberSaveable(title, artworkUrl) { mutableStateOf(false) }
    var imageReady by remember(artworkUrl) { mutableStateOf(false) }
    val scale = remember(title, artworkUrl) { Animatable(if (started || inspection) 1f else 1.12f) }
    LaunchedEffect(isVisible, imageReady, title, artworkUrl) {
        if (started) {
            scale.snapTo(1f)
        } else if (isVisible && imageReady) {
            started = true
            if (inspection || !ValueAnimator.areAnimatorsEnabled()) scale.snapTo(1f)
            else scale.animateTo(1f, tween(6000, easing = LinearOutSlowInEasing))
        }
    }
    Box(
        modifier.width(cardWidth).aspectRatio(1f).clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .cardPressable(onClick = onClick, onLongClick = onLongClick)
            .semantics { contentDescription = "Play $title, $artist" },
    ) {
        Icon(fallbackIcon, null, Modifier.size(64.dp).align(Alignment.Center),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        AsyncImage(
            model = remember(artworkUrl, context) {
                ImageRequest.Builder(context).data(artworkUrl).size(544).crossfade(200).build()
            },
            contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            },
            onSuccess = { imageReady = true },
        )
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.5f).align(Alignment.TopCenter)
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.76f), Color.Transparent))))
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.46f).align(Alignment.BottomCenter)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)))))
        Row(Modifier.fillMaxWidth().align(Alignment.TopStart).padding(start = 16.dp, top = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(top = 6.dp)) {
                Text(title, fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold,
                    color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Text(artist, fontSize = 14.sp, lineHeight = 20.sp, color = Color.White.copy(alpha = 0.76f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (onMenuClick != null) IconButton(onClick = onMenuClick, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.MoreVert, "More options for $title", Modifier.size(23.dp), tint = Color.White)
            }
        }
        if (!caption.isNullOrBlank()) Text(
            caption, fontSize = 13.sp, lineHeight = 18.sp, fontStyle = FontStyle.Italic,
            color = Color.White.copy(alpha = 0.92f), maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
        )
    }
}
