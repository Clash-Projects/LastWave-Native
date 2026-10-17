package com.lastwave.app.ui.player.lyricsshare

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lastwave.app.ui.common.EdgeToEdgeDialogWindow
import com.lastwave.app.util.FileExportHelper

private enum class ShareStep {
    Select,
    Preview,
}

@Composable
fun ShareLyricsDialog(
    snapshot: LyricsSnapshot,
    onDismiss: () -> Unit,
    fileExportHelper: FileExportHelper? = null,
    primaryColor: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val exportHelper = fileExportHelper ?: remember(context) { FileExportHelper(context.applicationContext) }
    val totalLines = snapshot.lines.size

    // Default 1..3 selection window
    val initialSelection = remember(snapshot) {
        computeDefaultSelection(totalLines, snapshot.initialActiveLineIndex)
    }

    var currentStepName by rememberSaveable { mutableStateOf(ShareStep.Select.name) }
    var selectedStart by rememberSaveable { mutableIntStateOf(initialSelection.first) }
    var selectedEnd by rememberSaveable { mutableIntStateOf(initialSelection.last) }
    var cardModeName by rememberSaveable { mutableStateOf(CardMode.Song.name) }

    val currentStep = if (currentStepName == ShareStep.Preview.name) ShareStep.Preview else ShareStep.Select
    val currentRange = if (selectedStart in 0 until totalLines && selectedEnd in 0 until totalLines && selectedStart <= selectedEnd) {
        selectedStart..selectedEnd
    } else {
        initialSelection
    }
    val currentMode = if (cardModeName == CardMode.Lyrics.name) CardMode.Lyrics else CardMode.Song

    // Resolve artwork asynchronously off main thread
    var artworkResolution by remember {
        mutableStateOf(
            ArtworkResolution(
                coverBitmap = null,
                blurredBitmap = ArtworkResolver.createBrandFallbackGradient(primaryColor.toArgb()),
                resolvedUrl = null,
            )
        )
    }

    LaunchedEffect(snapshot.trackInfo) {
        val result = ArtworkResolver.resolveArtwork(context, snapshot.trackInfo, primaryColor.toArgb())
        artworkResolution = result
    }

    // Extract swatches based on artwork & theme accent
    val swatches = remember(artworkResolution.coverBitmap, primaryColor) {
        ColorContrastHelper.extractSwatches(artworkResolution.coverBitmap, primaryColor.toArgb())
    }

    val defaultSwatchColor = remember(swatches) {
        swatches.firstOrNull()?.color ?: ColorContrastHelper.BRAND_LIME
    }

    var selectedSwatchColor by rememberSaveable { mutableIntStateOf(defaultSwatchColor) }

    // Keep swatch valid if palette updates
    LaunchedEffect(swatches) {
        if (swatches.none { it.color == selectedSwatchColor }) {
            selectedSwatchColor = defaultSwatchColor
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        EdgeToEdgeDialogWindow()

        BackHandler {
            if (currentStep == ShareStep.Preview) {
                currentStepName = ShareStep.Select.name
            } else {
                onDismiss()
            }
        }

        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            AnimatedContent(
                targetState = currentStep,
                transitionSpec = {
                    if (targetState == ShareStep.Preview) {
                        (slideInHorizontally(tween(250)) { it } + fadeIn(tween(200)))
                            .togetherWith(slideOutHorizontally(tween(250)) { -it / 3 } + fadeOut(tween(150)))
                    } else {
                        (slideInHorizontally(tween(250)) { -it / 3 } + fadeIn(tween(200)))
                            .togetherWith(slideOutHorizontally(tween(250)) { it } + fadeOut(tween(150)))
                    }
                },
                label = "shareStepTransition",
            ) { step ->
                when (step) {
                    ShareStep.Select -> {
                        SelectLyricsScreen(
                            snapshot = snapshot,
                            selectedRange = currentRange,
                            onSelectionChange = { newRange ->
                                selectedStart = newRange.first
                                selectedEnd = newRange.last
                            },
                            onContinue = {
                                currentStepName = ShareStep.Preview.name
                            },
                            onClose = onDismiss,
                            primaryColor = Color(selectedSwatchColor),
                        )
                    }

                    ShareStep.Preview -> {
                        SharePreviewScreen(
                            snapshot = snapshot,
                            selectedRange = currentRange,
                            artworkResolution = artworkResolution,
                            swatches = swatches,
                            selectedMode = currentMode,
                            onModeChange = { cardModeName = it.name },
                            selectedSwatchColor = selectedSwatchColor,
                            onSwatchChange = { selectedSwatchColor = it },
                            onBack = { currentStepName = ShareStep.Select.name },
                            onClose = onDismiss,
                            fileExportHelper = exportHelper,
                        )
                    }
                }
            }
        }
    }
}
