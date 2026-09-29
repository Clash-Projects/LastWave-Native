package com.lastwave.app.widget

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import com.lastwave.app.MainActivity
import com.lastwave.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Material You Two-Tone Split Card with Heart (WIDGET_REF_03_TWOTONE_SPLIT).
 *
 * Archetype: 50/50 Dual-Zone Dynamic Tonal Surface.
 * - Left Pane: Dynamic Tonal Surface with Note Badge, Heart favorite button, Track Meta,
 *   wavy/straight playback line, and Three-Button Playback Deck (Prev, Play/Pause Center, Next).
 * - Right Pane: Deep Complementary Surface with Album Artwork clipped to RoundedCornerShape(26.dp).
 */
class TwoToneSplitWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val openNowPlayingKey = ActionParameters.Key<Boolean>(WidgetActions.EXTRA_OPEN_NOW_PLAYING)
        val openAppAction = actionStartActivity<MainActivity>(actionParametersOf(openNowPlayingKey to true))

        provideContent {
            val prefs = androidx.glance.currentState<androidx.datastore.preferences.core.Preferences>()
            val snapshot = androidx.compose.runtime.remember(prefs) {
                WidgetSnapshot.fromPreferences(prefs, context)
            }
            val hasContentToShow = snapshot.title.isNotBlank()

            // Resolve artwork bitmap with 0ms in-memory cache priority
            val artBitmap = androidx.compose.runtime.remember(snapshot.artPath, snapshot.title, snapshot.artist) {
                val songKey = "${snapshot.title}|${snapshot.artist}"
                AlbumArtBitmapCache.get(songKey)
                    ?: AlbumArtBitmapCache.get(snapshot.artPath.orEmpty())
                    ?: ActiveMediaSessionHolder.currentArtwork
                    ?: AlbumArtBitmapCache.getOrDecode(snapshot.artPath)
            }

            GlanceTheme {
                TwoToneCardContent(
                    snapshot = snapshot,
                    artBitmap = artBitmap,
                    hasContent = hasContentToShow,
                    openAppAction = openAppAction,
                )
            }
        }
    }

    @Composable
    private fun TwoToneCardContent(
        snapshot: WidgetSnapshot,
        artBitmap: android.graphics.Bitmap?,
        hasContent: Boolean,
        openAppAction: androidx.glance.action.Action,
    ) {
        val context = LocalContext.current
        val isDarkMode = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

        val leftBgColor = if (isDarkMode) Color(snapshot.leftSurfaceColorDark.toInt()) else Color(snapshot.leftSurfaceColor.toInt())
        val rightBgColor = if (isDarkMode) Color(snapshot.rightSurfaceColorDark.toInt()) else Color(snapshot.rightSurfaceColor.toInt())
        val onLeftTextColor = if (isDarkMode) Color(snapshot.onLeftColorDark.toInt()) else Color(snapshot.onLeftColor.toInt())
        val onRightAccentColor = if (isDarkMode) Color(snapshot.onRightColorDark.toInt()) else Color(snapshot.onRightColor.toInt())

        // Dynamic ColorProviders supporting Day/Night transitions seamlessly
        val leftBgProvider = ColorProvider(leftBgColor)
        val rightBgProvider = ColorProvider(rightBgColor)
        val onLeftTextProvider = ColorProvider(onLeftTextColor)
        val onLeftTextSubProvider = ColorProvider(onLeftTextColor.copy(alpha = 0.72f))
        val onRightAccentProvider = ColorProvider(onRightAccentColor)
        val heartColorProvider = if (snapshot.isFavorite) {
            ColorProvider(Color(snapshot.heartActiveColor.toInt()))
        } else {
            onLeftTextProvider
        }

        val hasSongContent = hasContent && snapshot.title.isNotBlank()
        val isAppInBackground = WidgetActions.isAppInBackground(context)

        // Like button is ONLY disabled when:
        // 1. On default screen with no song loaded (!hasSongContent)
        // 2. When the app is not running in background (!isAppInBackground),
        //    even if cached song details remain in the widget
        // In all other cases (app in background, song loaded, playing OR paused):
        // The like button IS ENABLED and interactive!
        val isLikeButtonEnabled = hasSongContent && isAppInBackground

        val displayTitle = if (hasContent) snapshot.title else "Ready to Play"
        val displaySubtitle = if (hasContent) {
            listOfNotNull(
                snapshot.artist.takeIf { it.isNotBlank() },
                snapshot.album.takeIf { it.isNotBlank() },
            ).joinToString(" • ").ifBlank { "LastWave" }
        } else {
            "Tap to explore music"
        }

        // Outer Card: 360dp x 195dp, RoundedCornerShape(38.dp)
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadius(38.dp),
        ) {
            Row(modifier = GlanceModifier.fillMaxSize()) {
                // ==================== LEFT PANE (50%) ====================
                Box(
                    modifier = GlanceModifier
                        .defaultWeight()
                        .fillMaxHeight()
                        .background(leftBgProvider),
                ) {
                    Column(
                        modifier = GlanceModifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        // Top Header Row: Animated Sound Wave Badge & Heart Chip
                        Row(
                            modifier = GlanceModifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Circular Sound Wave Badge
                            Box(
                                modifier = GlanceModifier
                                    .size(36.dp)
                                    .background(onLeftTextProvider)
                                    .cornerRadius(18.dp)
                                    .clickable(openAppAction),
                                contentAlignment = Alignment.Center,
                            ) {
                                val soundIcon = if (snapshot.isPlaying) Icons.Rounded.GraphicEq else Icons.Rounded.MusicNote
                                Image(
                                    provider = GlanceMaterialIcons.rememberVectorProvider(
                                        image = soundIcon,
                                        tint = leftBgColor,
                                        sizeDp = 18.dp,
                                    ),
                                    modifier = GlanceModifier.size(18.dp),
                                    contentDescription = if (snapshot.isPlaying) "Playing - Sound Wave" else "Music Note - Open LastWave",
                                )
                            }

                            Spacer(modifier = GlanceModifier.defaultWeight())

                            // Heart Button (placed on top row opposite Note badge)
                            val heartBoxModifier = GlanceModifier
                                .size(36.dp)
                                .background(
                                    when {
                                        isLikeButtonEnabled && snapshot.isFavorite -> Color(0x33E11D48)
                                        isLikeButtonEnabled -> Color(0x1A000000)
                                        else -> Color(0x0A000000)
                                    }
                                )
                                .cornerRadius(18.dp)
                                .let { mod ->
                                    if (isLikeButtonEnabled) {
                                        mod.clickable(actionRunCallback<FavoriteActionCallback>())
                                    } else {
                                        mod
                                    }
                                }

                            Box(
                                modifier = heartBoxModifier,
                                contentAlignment = Alignment.Center,
                            ) {
                                val heartIcon = if (snapshot.isFavorite && (isLikeButtonEnabled || hasContent)) {
                                    Icons.Rounded.Favorite
                                } else {
                                    Icons.Rounded.FavoriteBorder
                                }
                                val heartTint = if (isLikeButtonEnabled) {
                                    if (snapshot.isFavorite) Color(snapshot.heartActiveColor.toInt()) else onLeftTextColor
                                } else {
                                    onLeftTextColor.copy(alpha = 0.32f)
                                }
                                Image(
                                    provider = GlanceMaterialIcons.rememberVectorProvider(
                                        image = heartIcon,
                                        tint = heartTint,
                                        sizeDp = 20.dp,
                                    ),
                                    modifier = GlanceModifier.size(20.dp),
                                    contentDescription = when {
                                        !isLikeButtonEnabled && !hasSongContent -> "Favorites unavailable - Default screen"
                                        !isLikeButtonEnabled && !isAppInBackground -> "Favorites unavailable - App is not running in background"
                                        snapshot.isFavorite -> "Remove from favorites"
                                        else -> "Add to favorites"
                                    },
                                )
                            }
                        }

                        Spacer(modifier = GlanceModifier.defaultWeight())

                        // Center: Track Metadata (tappable to open player in app)
                        Column(
                            modifier = GlanceModifier
                                .fillMaxWidth()
                                .clickable(openAppAction),
                        ) {
                            Text(
                                text = displayTitle,
                                style = TextStyle(
                                    color = onLeftTextProvider,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                ),
                                maxLines = 1,
                            )
                            Spacer(modifier = GlanceModifier.height(2.dp))
                            Text(
                                text = displaySubtitle,
                                style = TextStyle(
                                    color = onLeftTextSubProvider,
                                    fontSize = 12.sp,
                                ),
                                maxLines = 1,
                            )
                        }

                        // Playback Sound Wave: Wavy line when playing, straight baseline when stopped/paused/empty
                        Spacer(modifier = GlanceModifier.height(4.dp))
                        Box(
                            modifier = GlanceModifier
                                .fillMaxWidth()
                                .height(14.dp)
                                .clickable(openAppAction),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            val waveDrawable = if (snapshot.isPlaying) {
                                R.drawable.ic_widget_wave_active
                            } else {
                                R.drawable.ic_widget_wave_idle
                            }
                            Image(
                                provider = ImageProvider(waveDrawable),
                                colorFilter = ColorFilter.tint(
                                    if (snapshot.isPlaying) onLeftTextProvider else onLeftTextSubProvider
                                ),
                                modifier = GlanceModifier.fillMaxWidth().height(14.dp),
                                contentScale = ContentScale.FillBounds,
                                contentDescription = if (snapshot.isPlaying) "Playing Wave" else "Stopped Baseline",
                            )
                        }
                        Spacer(modifier = GlanceModifier.height(4.dp))

                        // Bottom: Three-Button Playback Deck (Prev, Play/Pause Center, Next)
                        Row(
                            modifier = GlanceModifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Previous Button
                            Box(
                                modifier = GlanceModifier
                                    .size(38.dp)
                                    .background(Color(0x1F000000))
                                    .cornerRadius(19.dp)
                                    .clickable(actionRunCallback<PrevActionCallback>()),
                                contentAlignment = Alignment.Center,
                            ) {
                                Image(
                                    provider = GlanceMaterialIcons.rememberVectorProvider(
                                        image = Icons.Rounded.SkipPrevious,
                                        tint = onLeftTextColor,
                                        sizeDp = 18.dp,
                                    ),
                                    modifier = GlanceModifier.size(18.dp),
                                    contentDescription = "Previous Track",
                                )
                            }

                            Spacer(modifier = GlanceModifier.width(8.dp))

                            // Play/Pause Center Button (launches app to discover music if empty)
                            Box(
                                modifier = GlanceModifier
                                    .size(46.dp)
                                    .background(onLeftTextProvider)
                                    .cornerRadius(23.dp)
                                    .clickable(if (hasContent) actionRunCallback<ToggleActionCallback>() else openAppAction),
                                contentAlignment = Alignment.Center,
                            ) {
                                val playPauseIcon = if (snapshot.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow
                                Image(
                                    provider = GlanceMaterialIcons.rememberVectorProvider(
                                        image = playPauseIcon,
                                        tint = leftBgColor,
                                        sizeDp = 22.dp,
                                    ),
                                    modifier = GlanceModifier.size(22.dp),
                                    contentDescription = if (snapshot.isPlaying) "Pause" else "Play",
                                )
                            }

                            Spacer(modifier = GlanceModifier.width(8.dp))

                            // Next Button
                            Box(
                                modifier = GlanceModifier
                                    .size(38.dp)
                                    .background(Color(0x1F000000))
                                    .cornerRadius(19.dp)
                                    .clickable(actionRunCallback<NextActionCallback>()),
                                contentAlignment = Alignment.Center,
                            ) {
                                Image(
                                    provider = GlanceMaterialIcons.rememberVectorProvider(
                                        image = Icons.Rounded.SkipNext,
                                        tint = onLeftTextColor,
                                        sizeDp = 18.dp,
                                    ),
                                    modifier = GlanceModifier.size(18.dp),
                                    contentDescription = "Next Track",
                                )
                            }
                        }
                    }
                }

                // ==================== RIGHT PANE (50%) ====================
                // Artwork Container (tappable to open player in app)
                Box(
                    modifier = GlanceModifier
                        .defaultWeight()
                        .fillMaxHeight()
                        .background(rightBgProvider)
                        .padding(14.dp)
                        .clickable(openAppAction),
                    contentAlignment = Alignment.Center,
                ) {
                    if (artBitmap != null) {
                        Image(
                            provider = ImageProvider(artBitmap),
                            contentDescription = "Album Cover - Open LastWave",
                            contentScale = ContentScale.Crop,
                            modifier = GlanceModifier
                                .fillMaxSize()
                                .cornerRadius(26.dp),
                        )
                    } else {
                        Image(
                            provider = ImageProvider(R.drawable.widget_art_empty_hero),
                            contentDescription = "LastWave Music - Tap to open player",
                            contentScale = ContentScale.Crop,
                            modifier = GlanceModifier
                                .fillMaxSize()
                                .cornerRadius(26.dp),
                        )
                    }
                }
            }
        }
    }
}
