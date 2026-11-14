package com.lastwave.app.ui.settings


import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Shape
import kotlinx.coroutines.delay
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

import com.lastwave.app.ui.player.LocalMiniPlayerScrollClearance
import com.lastwave.app.R
import com.lastwave.app.ui.common.safeDrawingBottomPadding
import com.lastwave.app.ui.common.safeHorizontalContentPadding



/**
 * Determines the target LazyColumn item index (section) for a searchable setting.
 * Every tab renders one LazyColumn item per section, in [SettingsTab.sectionOrder].
 */
internal fun getTargetSectionIndex(settingId: String?): Int {
    if (settingId == null) return 0
    val entry = SettingsSearchIndex.allEntries.firstOrNull { it.id == settingId } ?: return 0
    return entry.parentTab.sectionOrder.indexOf(entry.section).coerceAtLeast(0)
}

/**
 * Attaches a BringIntoViewRequester and renders an expressive pulsing highlight glow
 * with outer colored shadow halo, accent border, and auto-scroll when isHighlighted is true.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal fun Modifier.settingHighlightGlow(
    isHighlighted: Boolean,
    shape: Shape = CardOuterShape,
): Modifier = composed {
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val primaryColor = MaterialTheme.colorScheme.primary

    LaunchedEffect(isHighlighted) {
        if (isHighlighted) {
            delay(160L)
            bringIntoViewRequester.bringIntoView()
        }
    }

    if (!isHighlighted) {
        return@composed this.bringIntoViewRequester(bringIntoViewRequester)
    }

    val infiniteTransition = rememberInfiniteTransition(label = "settingHighlightGlow")
    val glowProgress by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(650, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glowProgress",
    )

    this
        .bringIntoViewRequester(bringIntoViewRequester)
        .shadow(
            elevation = (7 * glowProgress).dp,
            shape = shape,
            ambientColor = primaryColor.copy(alpha = 0.40f * glowProgress),
            spotColor = primaryColor.copy(alpha = 0.55f * glowProgress),
        )
        .border(
            width = 2.5.dp,
            color = primaryColor.copy(alpha = glowProgress),
            shape = shape,
        )
}


// ==========================================
// SETTINGS SEARCH COMPOSABLES
// ==========================================

/**
 * Builds an [AnnotatedString] from [text], highlighting ranges from [ranges]
 * with primary bold styling for search query matches.
 */
internal fun buildHighlightedText(
    text: String,
    ranges: List<IntRange>,
    highlightColor: Color,
): AnnotatedString {
    if (ranges.isEmpty() || text.isEmpty()) {
        return AnnotatedString(text)
    }
    return buildAnnotatedString {
        var lastIndex = 0
        for (range in ranges) {
            val start = range.first.coerceIn(0, text.length)
            val end = (range.last + 1).coerceIn(0, text.length)
            if (start > lastIndex) {
                append(text.substring(lastIndex, start))
            }
            if (end > start) {
                withStyle(SpanStyle(color = highlightColor, fontWeight = FontWeight.Bold)) {
                    append(text.substring(start, end))
                }
                lastIndex = end
            }
        }
        if (lastIndex < text.length) {
            append(text.substring(lastIndex))
        }
    }
}

@Composable
internal fun SettingsSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.focusRequester(focusRequester),
        shape = RoundedCornerShape(20.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        ),
        placeholder = {
            Text(
                stringResource(R.string.settings_search_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        },
        leadingIcon = {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.settings_search_clear),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
    )
}

@Composable
internal fun SettingsSearchResultsList(
    query: String,
    results: List<MatchResult>,
    onResultClick: (SettingsEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = 32.dp + LocalMiniPlayerScrollClearance.current + safeDrawingBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxSize().safeHorizontalContentPadding(),
    ) {
        if (results.isEmpty()) {
            item {
                SettingsSearchEmptyState(query = query)
            }
        } else {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${results.size} setting${if (results.size == 1) "" else "s"} found",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            items(results, key = { it.entry.id }) { result ->
                SearchResultCard(
                    result = result,
                    onClick = { onResultClick(result.entry) },
                )
            }
        }
    }
}

@Composable
internal fun SearchResultCard(
    result: MatchResult,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interactionSource)
    val highlightColor = MaterialTheme.colorScheme.primary

    val annotatedTitle = remember(result.entry.title, result.titleMatchedRanges, highlightColor) {
        buildHighlightedText(result.entry.title, result.titleMatchedRanges, highlightColor)
    }

    val annotatedSubtitle = remember(result.entry.subtitle, result.subtitleMatchedRanges, highlightColor) {
        buildHighlightedText(result.entry.subtitle, result.subtitleMatchedRanges, highlightColor)
    }

    Card(
        onClick = onClick,
        shape = CardOuterShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        interactionSource = interactionSource,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                Text(
                    text = "${stringResource(result.entry.parentTab.titleRes)} › ${result.entry.section}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBadge(
                    result.entry.icon,
                    result.entry.iconContainer(),
                    result.entry.iconTint(),
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = annotatedTitle,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = annotatedSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
internal fun SettingsSearchEmptyState(query: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp, horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.size(72.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.SearchOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(36.dp),
                    )
                }
            }
            Text(
                text = stringResource(R.string.settings_search_no_results, query),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.settings_search_no_results_sub),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
