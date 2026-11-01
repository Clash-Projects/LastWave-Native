package com.lastwave.app.ui.moods

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lastwave.app.data.music.MoodTile
import com.lastwave.app.ui.feed.cards.CardTokens
import com.lastwave.app.ui.feed.cards.cardPressable

/** Home scrolls two rows together; full pages wrap the same tiles into the available width. */
@Composable
fun MoodMosaic(
    tiles: List<MoodTile>,
    onTileClick: (MoodTile) -> Unit,
    modifier: Modifier = Modifier,
    wrapVertically: Boolean = false,
) {
    if (tiles.isEmpty()) return
    val tileKeys = remember(tiles) {
        val occurrences = mutableMapOf<String, Int>()
        tiles.map { tile ->
            val occurrence = occurrences.getOrDefault(tile.key, 0)
            occurrences[tile.key] = occurrence + 1
            tile.key to occurrence
        }
    }
    if (wrapVertically) {
        BoxWithConstraints(
            modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)
                .semantics { isTraversalGroup = true },
        ) {
            val availableWidthDp = maxWidth.value.toInt().coerceAtLeast(MOOD_TILE_WIDE_WIDTH_DP)
            val pattern = remember(tiles.size, availableWidthDp) {
                moodMosaicVerticalPattern(tiles.size, availableWidthDp)
            }
            MoodMosaicTiles(tiles, tileKeys, pattern, onTileClick, Modifier.fillMaxWidth())
        }
    } else {
        val pattern = remember(tiles.size) { moodMosaicPattern(tiles.size) }
        Box(
            modifier = modifier.horizontalScroll(rememberScrollState())
                .semantics { isTraversalGroup = true }.padding(horizontal = 16.dp),
        ) {
            MoodMosaicTiles(tiles, tileKeys, pattern, onTileClick)
        }
    }
}

@Composable
private fun MoodMosaicTiles(
    tiles: List<MoodTile>,
    tileKeys: List<Pair<String, Int>>,
    pattern: List<MoodTilePlacement>,
    onTileClick: (MoodTile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val width = pattern.maxOf { it.startDp + it.widthDp }.dp
    val height = (pattern.last().topDp + MOOD_TILE_HEIGHT_DP).dp
    Box(modifier.width(width).height(height)) {
        pattern.forEach { placement ->
            val tile = tiles[placement.index]
            key(tileKeys[placement.index]) {
                MoodMosaicTile(
                    tile = tile,
                    onClick = { onTileClick(tile) },
                    modifier = Modifier
                        .offset(x = placement.startDp.dp, y = placement.topDp.dp)
                        .width(placement.widthDp.dp)
                        .height(MOOD_TILE_HEIGHT_DP.dp)
                        .semantics { traversalIndex = placement.index.toFloat() },
                )
            }
        }
    }
}

@Composable
private fun MoodMosaicTile(
    tile: MoodTile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = Color(tile.colorArgb)
    val tint = accent.copy(alpha = 0.38f).compositeOver(Color(0xFF171A20).copy(alpha = 0.94f))
    Surface(
        modifier = modifier.cardPressable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = tile.label },
        shape = RoundedCornerShape(CardTokens.coverCornerRadius),
        color = tint,
        contentColor = Color.White,
        border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.09f)),
    ) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.linearGradient(listOf(Color.White.copy(alpha = 0.035f), Color.Transparent)),
            ),
        ) {
            Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().width(3.dp).background(accent))
            Text(
                text = tile.label,
                modifier = Modifier.align(Alignment.BottomStart)
                    .padding(start = 9.dp, end = 7.dp, bottom = 8.dp)
                    .clearAndSetSemantics {},
                fontSize = CardTokens.titleFontSize,
                lineHeight = CardTokens.titleLineHeight,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
