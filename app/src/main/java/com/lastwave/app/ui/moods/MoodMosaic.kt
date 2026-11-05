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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.lastwave.app.data.music.MoodTile
import com.lastwave.app.ui.feed.cards.CardTokens
import com.lastwave.app.ui.feed.cards.cardPressable

/** Home scrolls three rows together; full pages pack the same shapes into their viewport. */
@Composable
fun MoodMosaic(
    tiles: List<MoodTile>,
    onTileClick: (MoodTile) -> Unit,
    modifier: Modifier = Modifier,
    wrapVertically: Boolean = false,
    pastelGlass: Boolean = false,
) {
    if (tiles.isEmpty()) return
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.titleSmall.copy(
        color = Color.White,
        fontSize = CardTokens.titleFontSize,
        lineHeight = CardTokens.titleLineHeight,
        fontWeight = FontWeight.Medium,
    )
    val minimumCellSize = remember(tiles, textMeasurer, labelStyle, density) {
        moodTileMinimumSize(tiles.map { it.label }, textMeasurer, labelStyle, density)
    }
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
            val availableWidthDp = maxWidth.value.toInt().coerceAtLeast(1)
            // A very narrow viewport can become narrower than our usual text measurement.
            val cellHeight = remember(availableWidthDp, minimumCellSize, tiles, textMeasurer, labelStyle, density) {
                maxOf(minimumCellSize, moodTileMinimumSize(
                    tiles.map { it.label }, textMeasurer, labelStyle, density,
                    textWidthDp = minOf(MOOD_MIN_CELL_DP, availableWidthDp) - MOOD_TILE_LABEL_PADDING_DP * 2,
                ))
            }
            val pattern = remember(tiles.size, availableWidthDp, cellHeight) {
                moodMosaicVerticalPattern(tiles.size, availableWidthDp, cellHeight)
            }
            MoodMosaicTiles(tiles, tileKeys, pattern, labelStyle, onTileClick, pastelGlass, Modifier.fillMaxWidth())
        }
    } else {
        val pattern = remember(tiles.size, minimumCellSize) {
            moodMosaicPattern(tiles.size, minimumCellSize, minimumCellSize)
        }
        Box(
            modifier = modifier.horizontalScroll(rememberScrollState())
                .semantics { isTraversalGroup = true }.padding(horizontal = 16.dp),
        ) {
            MoodMosaicTiles(tiles, tileKeys, pattern, labelStyle, onTileClick, pastelGlass)
        }
    }
}

@Composable
private fun MoodMosaicTiles(
    tiles: List<MoodTile>,
    tileKeys: List<Pair<String, Int>>,
    pattern: List<MoodTilePlacement>,
    labelStyle: TextStyle,
    onTileClick: (MoodTile) -> Unit,
    pastelGlass: Boolean,
    modifier: Modifier = Modifier,
) {
    val width = pattern.maxOf { it.startDp + it.widthDp }.dp
    val height = pattern.maxOf { it.topDp + it.heightDp }.dp
    Box(modifier.width(width).height(height)) {
        pattern.forEach { placement ->
            val tile = tiles[placement.index]
            key(tileKeys[placement.index]) {
                MoodMosaicTile(
                    tile = tile,
                    labelStyle = labelStyle,
                    pastelGlass = pastelGlass,
                    onClick = { onTileClick(tile) },
                    modifier = Modifier
                        .offset(x = placement.startDp.dp, y = placement.topDp.dp)
                        .width(placement.widthDp.dp)
                        .height(placement.heightDp.dp)
                        .semantics { traversalIndex = placement.index.toFloat() },
                )
            }
        }
    }
}

@Composable
private fun MoodMosaicTile(
    tile: MoodTile,
    labelStyle: TextStyle,
    pastelGlass: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colours = remember(tile.colorArgb, tile.key, pastelGlass) {
        if (pastelGlass) homeMoodTileColors(tile.colorArgb, tile.key)
        else moodTileColors(tile.colorArgb, tile.key)
    }
    Surface(
        modifier = modifier.cardPressable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = tile.label },
        shape = RoundedCornerShape(CardTokens.coverCornerRadius),
        color = if (pastelGlass) Color.Transparent else colours.surfaceBottom,
        contentColor = colours.content,
        border = BorderStroke(0.5.dp, if (pastelGlass) Color.White.copy(alpha = 0.32f)
            else colours.accent.copy(alpha = 0.24f)),
    ) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(colours.surfaceTop, colours.surfaceBottom)),
            ).drawBehind {
                // Keep the glass highlight subtle, including when a long label wraps upward.
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            if (pastelGlass) Color.White.copy(alpha = 0.14f) else colours.accent.copy(alpha = 0.06f),
                            Color.Transparent,
                        ),
                        center = Offset(size.width * 0.8f, 0f),
                        radius = size.width.coerceAtLeast(size.height) * 0.75f,
                    ),
                    size = Size(size.width, size.height * 0.35f),
                )
            },
        ) {
            Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().width(3.dp).background(colours.accent))
            Text(
                text = tile.label,
                modifier = Modifier.align(Alignment.BottomStart)
                    .padding(MOOD_TILE_LABEL_PADDING_DP.dp)
                    .clearAndSetSemantics {},
                style = labelStyle.copy(color = colours.content),
            )
        }
    }
}
