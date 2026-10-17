package com.lastwave.app.ui.player.lyricsshare

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lastwave.app.R
import com.lastwave.app.ui.common.ExpressiveInlineLoadingIndicator
import com.lastwave.app.util.FileExportHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SharePreviewScreen(
    snapshot: LyricsSnapshot,
    selectedRange: IntRange,
    artworkResolution: ArtworkResolution,
    swatches: List<ColorSwatch>,
    selectedMode: CardMode,
    onModeChange: (CardMode) -> Unit,
    selectedSwatchColor: Int,
    onSwatchChange: (Int) -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
    fileExportHelper: FileExportHelper,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val selectedLines = remember(snapshot.lines, selectedRange) {
        if (selectedRange.isEmpty()) emptyList()
        else snapshot.lines.slice(selectedRange.first..selectedRange.last)
    }

    val isCoverSelected = selectedSwatchColor == ColorContrastHelper.SWATCH_COVER_ART && selectedMode == CardMode.Lyrics

    // Build specification for renderer
    val spec = remember(
        selectedMode,
        selectedLines,
        snapshot.trackInfo,
        artworkResolution,
        selectedSwatchColor,
        isCoverSelected,
    ) {
        val derived = ColorContrastHelper.deriveTextColors(selectedSwatchColor, isCoverScrim = isCoverSelected)
        val hasRtl = selectedLines.any { it.isRtl }
        ShareCardSpec(
            mode = selectedMode,
            lines = selectedLines,
            title = snapshot.trackInfo.title,
            artist = snapshot.trackInfo.artist,
            coverBitmap = artworkResolution.coverBitmap,
            blurredBackgroundBitmap = artworkResolution.blurredBitmap,
            swatchColor = selectedSwatchColor,
            derivedColors = derived,
            isCoverBackground = isCoverSelected,
            isRtl = hasRtl,
            targetWidth = ShareCardRenderer.BASE_WIDTH,
            scaleFactor = 0.50f, // Scaled for fast live preview
        )
    }

    // Auto-revert cover swatch to color swatch if user switches to Song mode
    LaunchedEffect(selectedMode) {
        if (selectedMode == CardMode.Song && selectedSwatchColor == ColorContrastHelper.SWATCH_COVER_ART) {
            val fallback = swatches.firstOrNull { !it.isCoverArt }?.color ?: ColorContrastHelper.BRAND_LIME
            onSwatchChange(fallback)
        }
    }

    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isPreviewRendering by remember { mutableStateOf(true) }
    var renderJob by remember { mutableStateOf<Job?>(null) }

    // Re-render preview with ~100ms debounce when spec changes
    LaunchedEffect(spec) {
        renderJob?.cancel()
        renderJob = launch {
            isPreviewRendering = true
            delay(100L)
            val bmp = ShareCardRenderer.renderCard(context, spec)
            previewBitmap = bmp
            isPreviewRendering = false
        }
    }

    // Export states
    var isExporting by remember { mutableStateOf(false) }

    val savedToGalleryMsg = stringResource(R.string.lyshare_saved_to_gallery)
    val saveFailedMsg = stringResource(R.string.lyshare_save_failed)
    val shareFailedMsg = stringResource(R.string.lyshare_share_failed)

    fun onShareClick() {
        if (isExporting) return
        isExporting = true
        scope.launch {
            try {
                // Render full 1080px scale bitmap for export
                val fullSpec = spec.copy(scaleFactor = 1.0f)
                val fullBitmap = ShareCardRenderer.renderCard(context, fullSpec)
                val res = fileExportHelper.shareImage(context, fullBitmap, snapshot.trackInfo.title)
                if (res.isFailure) {
                    Toast.makeText(context, shareFailedMsg, Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                Toast.makeText(context, shareFailedMsg, Toast.LENGTH_SHORT).show()
            } finally {
                isExporting = false
            }
        }
    }

    fun onSaveClick() {
        if (isExporting) return
        isExporting = true
        scope.launch {
            try {
                val fullSpec = spec.copy(scaleFactor = 1.0f)
                val fullBitmap = ShareCardRenderer.renderCard(context, fullSpec)
                val res = fileExportHelper.saveImageToGallery(fullBitmap, snapshot.trackInfo.title)
                if (res.isSuccess) {
                    Toast.makeText(context, savedToGalleryMsg, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, saveFailedMsg, Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                Toast.makeText(context, saveFailedMsg, Toast.LENGTH_SHORT).show()
            } finally {
                isExporting = false
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF090C0E)),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.lyshare_back_cd),
                        tint = Color.White,
                    )
                }

                Text(
                    text = stringResource(R.string.lyshare_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )

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
            }

            // Live Card Preview Area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = previewBitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = stringResource(R.string.lyshare_title),
                        modifier = Modifier
                            .fillMaxHeight(0.96f)
                            .shadow(16.dp, RoundedCornerShape(20.dp), clip = false)
                            .clip(RoundedCornerShape(20.dp)),
                    )
                } else {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.size(36.dp),
                    )
                }

                // Loading overlay while re-rendering on mode/color changes
                androidx.compose.animation.AnimatedVisibility(
                    visible = isPreviewRendering && previewBitmap != null,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        ExpressiveInlineLoadingIndicator(size = 20.dp, color = Color.White)
                    }
                }
            }

            // Segmented Card Mode Toggle ("Song" | "Lyrics")
            Row(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 8.dp)
                    .background(Color.White.copy(alpha = 0.12f), CircleShape)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                CardModeTab(
                    label = stringResource(R.string.lyshare_mode_song),
                    selected = selectedMode == CardMode.Song,
                    onClick = { onModeChange(CardMode.Song) },
                )
                CardModeTab(
                    label = stringResource(R.string.lyshare_mode_lyrics),
                    selected = selectedMode == CardMode.Lyrics,
                    onClick = { onModeChange(CardMode.Lyrics) },
                )
            }

            val displaySwatches = remember(swatches, selectedMode, artworkResolution.coverBitmap) {
                if (selectedMode == CardMode.Lyrics && artworkResolution.coverBitmap != null) {
                    listOf(
                        ColorSwatch(
                            id = "cover_art",
                            nameRes = R.string.lyshare_color_cover,
                            color = ColorContrastHelper.SWATCH_COVER_ART,
                            isCoverArt = true,
                        )
                    ) + swatches.filter { !it.isCoverArt }
                } else {
                    swatches.filter { !it.isCoverArt }
                }
            }

            // YT Music Style Color Swatches Picker (with Cover swatch as first item in Lyrics mode)
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
                    .semantics { contentDescription = context.getString(R.string.lyshare_color_picker_cd) },
                contentPadding = PaddingValues(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(displaySwatches, key = { it.id }) { swatch ->
                    val isSelected = swatch.color == selectedSwatchColor
                    val swatchName = stringResource(swatch.nameRes)

                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .then(
                                if (swatch.isCoverArt) {
                                    Modifier
                                } else {
                                    Modifier.background(Color(swatch.color))
                                }
                            )
                            .then(
                                if (isSelected) {
                                    Modifier.border(BorderStroke(3.dp, Color.White), CircleShape)
                                } else {
                                    Modifier.border(BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)), CircleShape)
                                }
                            )
                            .clickable { onSwatchChange(swatch.color) }
                            .semantics {
                                role = Role.RadioButton
                                selected = isSelected
                                contentDescription = swatchName
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (swatch.isCoverArt && artworkResolution.coverBitmap != null) {
                            Image(
                                bitmap = artworkResolution.coverBitmap.asImageBitmap(),
                                contentDescription = null,
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape),
                            )
                        }
                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(Color.White, CircleShape),
                            )
                        }
                    }
                }
            }

            // Bottom Actions: Secondary Save + Primary White Pill Share
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Secondary Save button
                IconButton(
                    onClick = { onSaveClick() },
                    enabled = !isExporting,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.14f)),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = stringResource(R.string.lyshare_action_save),
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }

                // Primary Share button
                Button(
                    onClick = { onShareClick() },
                    enabled = !isExporting,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black,
                        disabledContainerColor = Color.White.copy(alpha = 0.35f),
                        disabledContentColor = Color.Black.copy(alpha = 0.50f),
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                ) {
                    if (isExporting) {
                        ExpressiveInlineLoadingIndicator(size = 20.dp, color = Color.Black)
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Share,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.lyshare_action_share),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CardModeTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg by animateColorAsState(
        targetValue = if (selected) Color.White else Color.Transparent,
        animationSpec = androidx.compose.animation.core.tween(150),
        label = "modeTabBg",
    )
    val fg by animateColorAsState(
        targetValue = if (selected) Color.Black else Color.White.copy(alpha = 0.70f),
        animationSpec = androidx.compose.animation.core.tween(150),
        label = "modeTabFg",
    )

    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 14.sp),
            fontWeight = FontWeight.Bold,
            color = fg,
        )
    }
}
