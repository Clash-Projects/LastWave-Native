package com.lastwave.app.ui.player

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Share
import com.lastwave.app.ui.player.lyricsshare.ShareLyricsDialog
import com.lastwave.app.ui.player.lyricsshare.TrackInfo
import com.lastwave.app.ui.player.lyricsshare.buildLyricsSnapshot
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SyncDisabled
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.material3.Slider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import com.lastwave.app.R
import com.lastwave.app.ui.theme.LocalLiquidGlass
import com.lastwave.app.ui.theme.LiquidGlassPreset
import com.lastwave.app.ui.theme.LiquidGlassSurface
import com.lastwave.app.ui.theme.liquidGlassChrome
import com.lastwave.app.ui.theme.liquidGlassContainerColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.data.local.LyricsProvider
import com.lastwave.app.data.lyrics.LyricLine
import com.lastwave.app.data.lyrics.LyricSyllable
import com.lastwave.app.data.lyrics.LyricsTranslation
import com.lastwave.app.data.lyrics.VocalRole
import com.lastwave.app.data.lyrics.isRtlText
import com.lastwave.app.data.lyrics.providerForSource
import com.lastwave.app.data.lyrics.translationLanguageName
import com.lastwave.app.data.lyrics.translationWireTarget
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.MusicPlayerState
import com.lastwave.app.playback.PlaybackProgressState
import com.lastwave.app.ui.common.ExpressiveInlineLoadingIndicator
import com.lastwave.app.ui.common.ExpressiveMotion
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import androidx.lifecycle.repeatOnLifecycle
import java.util.Locale

/** Which secondary script the floating buttons currently draw under each
 *  line: none ([Original]), the pronunciation ([Romanized]) or a translation
 *  ([Translated]). At most one mode is shown at a time. */
internal enum class LyricsDisplayMode { Original, Romanized, Translated }

/** Line falloff by distance from the active row: alpha and soften radius.
 *  Past the end of the array the last value holds, so distance never needs a
 *  branch. The active line's own scale (1.0) lives with the row code. */
internal val LINE_FALLOFF_ALPHA = floatArrayOf(1f, 0.50f, 0.40f, 0.30f, 0.22f)

/** Inactive rows draw at this scale drop (0.97 at one row away and beyond). */
internal const val LYRIC_INACTIVE_SCALE_DROP = 0.03f

/** Shared focus glide between rows. */
internal const val LYRIC_FOCUS_MS = 420

/** Elastic trail: per-row settle, per-row stagger, and the whole clock. */
internal const val LYRIC_TRAIL_SETTLE_MS = 560f
internal const val LYRIC_TRAIL_STAGGER_MS = 38f
internal const val LYRIC_TRAIL_TOTAL_MS = 560 + 6 * 38

/** The sung row is anchored this far from the top of the viewport. */
internal const val LYRIC_ANCHOR_FRACTION = 0.18f

/** Spacing inside a stanza and the breath between stanzas. */
internal val LYRIC_LINE_GAP = 20.dp
internal val LYRIC_STANZA_GAP = 40.dp

/** Backing vocals: shift below the lead and strength relative to it. */
internal val LYRIC_BACKING_SHIFT = 7.5.dp
internal const val LYRIC_BACKING_EMPHASIS = 0.78f

/** Duet staging widths: one voice per side, unison rows a little wider. */
internal const val LYRIC_DUET_WIDTH = 0.75f
internal const val LYRIC_UNISON_WIDTH = 0.85f

/** Text alpha of rows that are not being sung (row alpha falls off on top). */
internal const val LYRIC_IDLE_TEXT_ALPHA = 0.85f

/** Unsung part of a lit word-synced row; matches an idle row one step away. */
internal const val LYRIC_LIT_BASE_ALPHA = 0.42f

/** How far a syllable rises while it is sung. */
internal val LYRIC_LIFT = 3.dp

/** Height of the instrumental-break dots row while it is open. */
internal val LYRIC_INTERLUDE_HEIGHT = 44.dp

/** Glide curve for the follow scroll and row scale: quick departure, long
 *  soft landing â€” closer to a platform sheet than to a linear scroll. */
internal val LYRIC_EASE = CubicBezierEasing(0.41f, 0f, 0.12f, 0.99f)

/** Follow-scroll duration: small corrections stay crisp, whole-verse jumps
 *  glide. Gap (px between the row and the focus point) picks the point inside
 *  the band; the band itself is what keeps motion calm. */
internal const val LYRIC_SCROLL_MIN_MS = 350f
internal const val LYRIC_SCROLL_MAX_MS = 500f

/** Sub-line reveal: slightly unhurried in, clipped and quick out. */
internal const val LYRIC_SUBLINE_OPEN_MS = 460
internal const val LYRIC_SUBLINE_CLOSE_MS = 260

/** Line-synced clock: longest gap it bridges with a timed wake before the
 *  next playhead sample (samples arrive every ~60ms while visible); the
 *  same 250ms cap the frame clock uses for extrapolation. */
internal const val LINE_BOUNDARY_WAKE_WINDOW_MS = 250L

/**
 * Smoothly melts the top and bottom edges of the lyrics viewport using a hardware-accelerated
 * offscreen alpha gradient mask (BlendMode.DstIn). Eliminates abrupt hard-edge clipping as
 * lyrics scroll down toward the bottom player controls.
 */
internal fun Modifier.lyricsFadingEdge(
    topFadeDp: androidx.compose.ui.unit.Dp = 36.dp,
    bottomFadeDp: androidx.compose.ui.unit.Dp = 110.dp,
): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val topFadePx = topFadeDp.toPx()
        val bottomFadePx = bottomFadeDp.toPx()
        val height = size.height
        if (height > topFadePx + bottomFadePx) {
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    (topFadePx / height) to Color.Black,
                    ((height - bottomFadePx) / height) to Color.Black,
                    1f to Color.Transparent,
                    startY = 0f,
                    endY = height,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
    }


/** Which side of the column a row is staged on, in reading direction. */
internal enum class LyricSide { Start, End, Center }


internal val LYRIC_FALLBACK_SHADOW = Shadow(
    color = Color.White.copy(alpha = 0.50f),
    blurRadius = 22f,
    offset = Offset.Zero,
)


/** Radius/alpha of the active line's glow, kept in one place. */
internal val LYRIC_GLOW_RADIUS = 8.dp
internal const val LYRIC_GLOW_ALPHA = 0.5f


/** Follow-scroll duration by gap: short nudges stay crisp, whole-verse jumps
 *  glide, bounded to the calm 350â€“500ms band. */
internal fun lyricScrollDurationMs(deltaPx: Float): Float =
    (LYRIC_SCROLL_MIN_MS + kotlin.math.abs(deltaPx) * 0.35f)
        .coerceAtMost(LYRIC_SCROLL_MAX_MS)


internal fun activeLineIndex(lines: List<LyricLine>, positionMs: Long): Int {
    var low = 0
    var high = lines.size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (lines[middle].timeMs <= positionMs) low = middle + 1 else high = middle
    }
    return low - 1
}

