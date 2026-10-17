package com.lastwave.app.ui.player.lyricsshare

import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lastwave.app.R

@Composable
fun SelectLyricsScreen(
    snapshot: LyricsSnapshot,
    selectedRange: IntRange,
    onSelectionChange: (IntRange) -> Unit,
    onContinue: () -> Unit,
    onClose: () -> Unit,
    primaryColor: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val listState = rememberLazyListState()

    // Auto-scroll so initial selection is in view
    LaunchedEffect(Unit) {
        if (!selectedRange.isEmpty()) {
            val targetIndex = (selectedRange.first - 1).coerceAtLeast(0)
            listState.animateScrollToItem(targetIndex)
        }
    }

    val selectedCount = if (selectedRange.isEmpty()) 0 else (selectedRange.last - selectedRange.first + 1)
    val maxLinesCapMessage = stringResource(R.string.lyshare_max_lines_reached)

    // Spotify-style vertical gradient: song palette color fading down into near-black
    val backgroundBrush = remember(primaryColor) {
        Brush.verticalGradient(
            colors = listOf(
                primaryColor.copy(alpha = 0.55f),
                Color(0xFF0F141A).copy(alpha = 0.92f),
                Color(0xFF090C0E),
            ),
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundBrush),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.lyshare_close_cd),
                        tint = Color.White,
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.lyshare_select_lyrics),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    Text(
                        text = if (selectedCount == 1) {
                            stringResource(R.string.lyshare_line_selected_single)
                        } else {
                            stringResource(R.string.lyshare_lines_selected, selectedCount)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.70f),
                    )
                }
            }

            // Lyrics List
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(snapshot.lines, key = { index, _ -> index }) { index, line ->
                    val isSelected = index in selectedRange
                    val rowBgColor by animateColorAsState(
                        targetValue = if (isSelected) Color.White.copy(alpha = 0.22f) else Color.Transparent,
                        animationSpec = tween(150),
                        label = "lyricSelectionBg",
                    )
                    val rowTextColor by animateColorAsState(
                        targetValue = if (isSelected) Color.White else Color.White.copy(alpha = 0.40f),
                        animationSpec = tween(150),
                        label = "lyricSelectionText",
                    )

                    val accessibilityLabel = if (isSelected) {
                        stringResource(R.string.lyshare_accessibility_line_selected, index + 1, snapshot.lines.size)
                    } else {
                        stringResource(R.string.lyshare_accessibility_line_unselected, index + 1, snapshot.lines.size)
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(rowBgColor)
                            .clickable {
                                val next = updateContiguousSelection(
                                    currentRange = selectedRange,
                                    tappedIndex = index,
                                    totalLines = snapshot.lines.size,
                                    maxLines = 5,
                                    onCapReached = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        Toast.makeText(context, maxLinesCapMessage, Toast.LENGTH_SHORT).show()
                                    },
                                )
                                onSelectionChange(next)
                            }
                            .semantics {
                                role = Role.Checkbox
                                selected = isSelected
                                contentDescription = "$accessibilityLabel: ${line.text}"
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(
                            text = line.text,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontSize = 18.sp,
                                lineHeight = 26.sp,
                            ),
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = rowTextColor,
                            textAlign = if (line.isRtl) TextAlign.End else TextAlign.Start,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(84.dp))
                }
            }
        }

        // Bottom Continue button (white pill)
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Button(
                onClick = onContinue,
                enabled = selectedCount > 0,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black,
                    disabledContainerColor = Color.White.copy(alpha = 0.25f),
                    disabledContentColor = Color.Black.copy(alpha = 0.40f),
                ),
                contentPadding = PaddingValues(horizontal = 36.dp, vertical = 14.dp),
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .height(52.dp),
            ) {
                Text(
                    text = stringResource(R.string.lyshare_continue),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
