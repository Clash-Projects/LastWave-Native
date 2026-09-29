package com.lastwave.app.widget

import android.content.Context

data class WidgetSnapshot(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val sourceApp: String = "",
    val sourcePackage: String = "",
    val artPath: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val hasSession: Boolean = false,
    val progress: Float = 0f,
    val isFavorite: Boolean = false,
    val isShuffle: Boolean = false,
    val repeatMode: Int = 0, // 0: OFF, 1: ALL, 2: ONE
    val rotationAngle: Float = 0f,
    val containerColor: Long = 0xFF0C2922L,
    val primaryAccentColor: Long = 0xFF7EE7C4L,
    val onPrimaryColor: Long = 0xFF07251EL,
    val themeMode: String = "dynamic", // "dynamic", "app", "glass"
    // ── Light-mode surface colors ──────────────────────────────────────────────
    val leftSurfaceColor: Long = 0xFFB6F3C5L,
    val rightSurfaceColor: Long = 0xFF113426L,
    val onLeftColor: Long = 0xFF0C2D1BL,
    val onRightColor: Long = 0xFFB6F3C5L,
    val heartActiveColor: Long = 0xFFE11D48L,
    // ── Dark-mode surface colors ──────────────────────────────────────────────
    val leftSurfaceColorDark: Long = 0xFF1B4D30L,
    val rightSurfaceColorDark: Long = 0xFF0A1F15L,
    val onLeftColorDark: Long = 0xFFB6F3C5L,
    val onRightColorDark: Long = 0xFF7EE7C4L,
) {
    companion object {
        internal const val STORE = "lastwave_widget_now_playing"

        fun read(context: Context): WidgetSnapshot {
            val prefs = runCatching {
                context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            }.getOrNull() ?: return WidgetSnapshot()
            return WidgetSnapshot(
                title = prefs.getString("title", "").orEmpty(),
                artist = prefs.getString("artist", "").orEmpty(),
                album = prefs.getString("album", "").orEmpty(),
                sourceApp = prefs.getString("source_app", "").orEmpty(),
                sourcePackage = prefs.getString("source_package", "").orEmpty(),
                artPath = prefs.getString("art_path", null),
                isPlaying = prefs.getBoolean("is_playing", false),
                isBuffering = prefs.getBoolean("is_buffering", false),
                hasSession = prefs.getBoolean("has_session", false),
                progress = prefs.getFloat("progress", 0f).coerceIn(0f, 1f),
                isFavorite = prefs.getBoolean("is_favorite", false),
                isShuffle = prefs.getBoolean("is_shuffle", false),
                repeatMode = prefs.getInt("repeat_mode", 0),
                rotationAngle = prefs.getFloat("rotation_angle", 0f),
                containerColor = prefs.getLong("container_color", 0xFF0C2922L),
                primaryAccentColor = prefs.getLong("primary_accent_color", 0xFF7EE7C4L),
                onPrimaryColor = prefs.getLong("on_primary_color", 0xFF07251EL),
                themeMode = prefs.getString("theme_mode", "dynamic") ?: "dynamic",
                leftSurfaceColor = prefs.getLong("left_surface_color", 0xFFB6F3C5L),
                rightSurfaceColor = prefs.getLong("right_surface_color", 0xFF113426L),
                onLeftColor = prefs.getLong("on_left_color", 0xFF0C2D1BL),
                onRightColor = prefs.getLong("on_right_color", 0xFFB6F3C5L),
                heartActiveColor = prefs.getLong("heart_active_color", 0xFFE11D48L),
                leftSurfaceColorDark = prefs.getLong("left_surface_color_dark", 0xFF1B4D30L),
                rightSurfaceColorDark = prefs.getLong("right_surface_color_dark", 0xFF0A1F15L),
                onLeftColorDark = prefs.getLong("on_left_color_dark", 0xFFB6F3C5L),
                onRightColorDark = prefs.getLong("on_right_color_dark", 0xFF7EE7C4L),
            )
        }

        fun write(context: Context, value: WidgetSnapshot) {
            runCatching {
                context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
                    .putString("title", value.title)
                    .putString("artist", value.artist)
                    .putString("album", value.album)
                    .putString("source_app", value.sourceApp)
                    .putString("source_package", value.sourcePackage)
                    .putString("art_path", value.artPath)
                    .putBoolean("is_playing", value.isPlaying)
                    .putBoolean("is_buffering", value.isBuffering)
                    .putBoolean("has_session", value.hasSession)
                    .putFloat("progress", value.progress.coerceIn(0f, 1f))
                    .putBoolean("is_favorite", value.isFavorite)
                    .putBoolean("is_shuffle", value.isShuffle)
                    .putInt("repeat_mode", value.repeatMode)
                    .putFloat("rotation_angle", value.rotationAngle)
                    .putLong("container_color", value.containerColor)
                    .putLong("primary_accent_color", value.primaryAccentColor)
                    .putLong("on_primary_color", value.onPrimaryColor)
                    .putString("theme_mode", value.themeMode)
                    .putLong("left_surface_color", value.leftSurfaceColor)
                    .putLong("right_surface_color", value.rightSurfaceColor)
                    .putLong("on_left_color", value.onLeftColor)
                    .putLong("on_right_color", value.onRightColor)
                    .putLong("heart_active_color", value.heartActiveColor)
                    .putLong("left_surface_color_dark", value.leftSurfaceColorDark)
                    .putLong("right_surface_color_dark", value.rightSurfaceColorDark)
                    .putLong("on_left_color_dark", value.onLeftColorDark)
                    .putLong("on_right_color_dark", value.onRightColorDark)
                    .apply()
            }
        }

        fun fromPreferences(prefs: androidx.datastore.preferences.core.Preferences, context: Context): WidgetSnapshot {
            val title = prefs[WidgetPrefKeys.TITLE]
            if (title == null) {
                return read(context)
            }
            return WidgetSnapshot(
                title = title,
                artist = prefs[WidgetPrefKeys.ARTIST].orEmpty(),
                album = prefs[WidgetPrefKeys.ALBUM].orEmpty(),
                sourceApp = prefs[WidgetPrefKeys.SOURCE_APP].orEmpty(),
                sourcePackage = prefs[WidgetPrefKeys.SOURCE_PACKAGE].orEmpty(),
                artPath = prefs[WidgetPrefKeys.ART_PATH],
                isPlaying = prefs[WidgetPrefKeys.IS_PLAYING] ?: false,
                isBuffering = prefs[WidgetPrefKeys.IS_BUFFERING] ?: false,
                hasSession = prefs[WidgetPrefKeys.HAS_SESSION] ?: true,
                progress = prefs[WidgetPrefKeys.PROGRESS] ?: 0f,
                isFavorite = prefs[WidgetPrefKeys.IS_FAVORITE] ?: false,
                isShuffle = prefs[WidgetPrefKeys.IS_SHUFFLE] ?: false,
                repeatMode = prefs[WidgetPrefKeys.REPEAT_MODE] ?: 0,
                rotationAngle = prefs[WidgetPrefKeys.ROTATION_ANGLE] ?: 0f,
                containerColor = prefs[WidgetPrefKeys.CONTAINER_COLOR] ?: 0xFF0C2922L,
                primaryAccentColor = prefs[WidgetPrefKeys.PRIMARY_ACCENT_COLOR] ?: 0xFF7EE7C4L,
                onPrimaryColor = prefs[WidgetPrefKeys.ON_PRIMARY_COLOR] ?: 0xFF07251EL,
                themeMode = prefs[WidgetPrefKeys.THEME_MODE] ?: "dynamic",
                leftSurfaceColor = prefs[WidgetPrefKeys.LEFT_SURFACE] ?: 0xFFB6F3C5L,
                rightSurfaceColor = prefs[WidgetPrefKeys.RIGHT_SURFACE] ?: 0xFF113426L,
                onLeftColor = prefs[WidgetPrefKeys.ON_LEFT] ?: 0xFF0C2D1BL,
                onRightColor = prefs[WidgetPrefKeys.ON_RIGHT] ?: 0xFFB6F3C5L,
                heartActiveColor = prefs[WidgetPrefKeys.HEART_ACTIVE] ?: 0xFFE11D48L,
                leftSurfaceColorDark = prefs[WidgetPrefKeys.LEFT_SURFACE_DARK] ?: 0xFF1B4D30L,
                rightSurfaceColorDark = prefs[WidgetPrefKeys.RIGHT_SURFACE_DARK] ?: 0xFF0A1F15L,
                onLeftColorDark = prefs[WidgetPrefKeys.ON_LEFT_DARK] ?: 0xFFB6F3C5L,
                onRightColorDark = prefs[WidgetPrefKeys.ON_RIGHT_DARK] ?: 0xFF7EE7C4L,
            )
        }
    }

    fun writeToPreferences(prefs: androidx.datastore.preferences.core.MutablePreferences) {
        prefs[WidgetPrefKeys.TITLE] = title
        prefs[WidgetPrefKeys.ARTIST] = artist
        prefs[WidgetPrefKeys.ALBUM] = album
        prefs[WidgetPrefKeys.SOURCE_APP] = sourceApp
        prefs[WidgetPrefKeys.SOURCE_PACKAGE] = sourcePackage
        if (artPath != null) {
            prefs[WidgetPrefKeys.ART_PATH] = artPath
        } else {
            prefs.remove(WidgetPrefKeys.ART_PATH)
        }
        prefs[WidgetPrefKeys.IS_PLAYING] = isPlaying
        prefs[WidgetPrefKeys.IS_BUFFERING] = isBuffering
        prefs[WidgetPrefKeys.HAS_SESSION] = hasSession
        prefs[WidgetPrefKeys.PROGRESS] = progress
        prefs[WidgetPrefKeys.IS_FAVORITE] = isFavorite
        prefs[WidgetPrefKeys.IS_SHUFFLE] = isShuffle
        prefs[WidgetPrefKeys.REPEAT_MODE] = repeatMode
        prefs[WidgetPrefKeys.ROTATION_ANGLE] = rotationAngle
        prefs[WidgetPrefKeys.CONTAINER_COLOR] = containerColor
        prefs[WidgetPrefKeys.PRIMARY_ACCENT_COLOR] = primaryAccentColor
        prefs[WidgetPrefKeys.ON_PRIMARY_COLOR] = onPrimaryColor
        prefs[WidgetPrefKeys.THEME_MODE] = themeMode
        prefs[WidgetPrefKeys.LEFT_SURFACE] = leftSurfaceColor
        prefs[WidgetPrefKeys.RIGHT_SURFACE] = rightSurfaceColor
        prefs[WidgetPrefKeys.ON_LEFT] = onLeftColor
        prefs[WidgetPrefKeys.ON_RIGHT] = onRightColor
        prefs[WidgetPrefKeys.HEART_ACTIVE] = heartActiveColor
        prefs[WidgetPrefKeys.LEFT_SURFACE_DARK] = leftSurfaceColorDark
        prefs[WidgetPrefKeys.RIGHT_SURFACE_DARK] = rightSurfaceColorDark
        prefs[WidgetPrefKeys.ON_LEFT_DARK] = onLeftColorDark
        prefs[WidgetPrefKeys.ON_RIGHT_DARK] = onRightColorDark
    }
}

object WidgetPrefKeys {
    val TITLE = androidx.datastore.preferences.core.stringPreferencesKey("title")
    val ARTIST = androidx.datastore.preferences.core.stringPreferencesKey("artist")
    val ALBUM = androidx.datastore.preferences.core.stringPreferencesKey("album")
    val SOURCE_APP = androidx.datastore.preferences.core.stringPreferencesKey("source_app")
    val SOURCE_PACKAGE = androidx.datastore.preferences.core.stringPreferencesKey("source_package")
    val ART_PATH = androidx.datastore.preferences.core.stringPreferencesKey("art_path")
    val IS_PLAYING = androidx.datastore.preferences.core.booleanPreferencesKey("is_playing")
    val IS_BUFFERING = androidx.datastore.preferences.core.booleanPreferencesKey("is_buffering")
    val HAS_SESSION = androidx.datastore.preferences.core.booleanPreferencesKey("has_session")
    val PROGRESS = androidx.datastore.preferences.core.floatPreferencesKey("progress")
    val IS_FAVORITE = androidx.datastore.preferences.core.booleanPreferencesKey("is_favorite")
    val IS_SHUFFLE = androidx.datastore.preferences.core.booleanPreferencesKey("is_shuffle")
    val REPEAT_MODE = androidx.datastore.preferences.core.intPreferencesKey("repeat_mode")
    val ROTATION_ANGLE = androidx.datastore.preferences.core.floatPreferencesKey("rotation_angle")
    val CONTAINER_COLOR = androidx.datastore.preferences.core.longPreferencesKey("container_color")
    val PRIMARY_ACCENT_COLOR = androidx.datastore.preferences.core.longPreferencesKey("primary_accent_color")
    val ON_PRIMARY_COLOR = androidx.datastore.preferences.core.longPreferencesKey("on_primary_color")
    val THEME_MODE = androidx.datastore.preferences.core.stringPreferencesKey("theme_mode")
    val LEFT_SURFACE = androidx.datastore.preferences.core.longPreferencesKey("left_surface_color")
    val RIGHT_SURFACE = androidx.datastore.preferences.core.longPreferencesKey("right_surface_color")
    val ON_LEFT = androidx.datastore.preferences.core.longPreferencesKey("on_left_color")
    val ON_RIGHT = androidx.datastore.preferences.core.longPreferencesKey("on_right_color")
    val HEART_ACTIVE = androidx.datastore.preferences.core.longPreferencesKey("heart_active_color")
    val LEFT_SURFACE_DARK = androidx.datastore.preferences.core.longPreferencesKey("left_surface_color_dark")
    val RIGHT_SURFACE_DARK = androidx.datastore.preferences.core.longPreferencesKey("right_surface_color_dark")
    val ON_LEFT_DARK = androidx.datastore.preferences.core.longPreferencesKey("on_left_color_dark")
    val ON_RIGHT_DARK = androidx.datastore.preferences.core.longPreferencesKey("on_right_color_dark")
}

typealias NowPlayingWidgetSnapshot = WidgetSnapshot
