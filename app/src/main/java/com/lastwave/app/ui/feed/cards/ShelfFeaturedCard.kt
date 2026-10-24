/*
 * FeaturedCard: big square cover with gradient scrim, title+artist at top,
 * 3-dot menu top-right, optional italic caption at the bottom.
 * Used for YTM's big featured cards ("Your daily discover").
 */
package com.lastwave.app.ui.feed.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lastwave.app.ui.common.ArtworkImage

/**
 * Large featured square card: full-bleed cover, dark gradient scrim at the
 * bottom, title + artist overlaid at the top, a 3-dot affordance at the top
 * right, and an optional italic caption (YTM "Because you liked X") at the
 * bottom. Sized to snap in a horizontal pager-style row.
 */
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
    cardWidth: Dp = 200.dp,
) {
    Box(
        modifier = modifier
            .width(cardWidth)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(22.dp))
            .cardPressable(onClick = onClick, onLongClick = onLongClick),
    ) {
        CardArtwork(
            title = title,
            subtitle = artist,
            artworkUrl = artworkUrl,
            fallbackIcon = fallbackIcon,
            modifier = Modifier.fillMaxSize(),
            cornerRadius = 22.dp,
        )

        // Bottom gradient scrim for text legibility.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(cardWidth * 0.55f)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.72f),
                    )
                )
        )
        // Top gradient for the title/menu row.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(cardWidth * 0.4f)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.55f),
                        1f to Color.Transparent,
                    )
                )
        )

        // Title + artist at top-left, 3-dot at top-right.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .padding(start = 14.dp, top = 12.dp, end = 4.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 40.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 17.sp,
                        lineHeight = 20.sp,
                    ),
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (artist.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = artist,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = Color.White.copy(alpha = 0.88f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (onMenuClick != null) {
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.42f),
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    IconButton(onClick = onMenuClick, modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                }
            }
        }

        // Optional italic caption at the bottom.
        if (!caption.isNullOrBlank()) {
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.sp,
                    fontStyle = FontStyle.Italic,
                ),
                color = Color.White.copy(alpha = 0.92f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
            )
        }
    }
}
