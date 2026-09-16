package com.lastwave.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

enum class LyricsUiVersion(val id: String, val title: String) {
    CLASSIC("classic", "Classic"),
    MODERN("modern", "Modern");

    companion object {
        fun fromId(id: String?): LyricsUiVersion =
            entries.firstOrNull { it.id == id } ?: MODERN
    }
}

enum class LyricsAnimation(val id: String, val title: String, val description: String) {
    APPLE_FLUID("apple_fluid", "Apple Fluid", "Smooth spring scaling with dynamic focal tracking"),
    KARAOKE_PULSE("karaoke_pulse", "Karaoke Pulse", "Rhythmic scale pop with energetic spring bounce"),
    KINETIC_SLIDE("kinetic_slide", "Kinetic Slide", "Active line glides smoothly from leading edge"),
    CINEMATIC_BLUR("cinematic_blur", "Cinematic Focus", "Soft background blur & vertical drift on past lines"),
    LOSSLESS_GLOW("lossless_glow", "Lossless Glow", "Vibrant gradient text with frosted glass reflection"),
    CARD_POP("card_pop", "Glass Elevation", "3D floating glass card lift with specular highlights"),
    APPLE_ZOOM("apple_zoom", "Dynamic Focus Zoom", "Expanded focal magnification with fluid spring push"),
    MINIMAL_WAVE("minimal_wave", "Minimal Clean", "Pure low-latency opacity transitions without distortion");

    companion object {
        fun fromId(id: String?): LyricsAnimation =
            entries.firstOrNull { it.id == id } ?: APPLE_FLUID
    }
}

data class MiscSettings(
    /** When on, the app's accent color follows the dominant color of the
     *  currently-scrobbling track's artwork (Home's "now playing" track),
     *  updating live as that track changes. Falls back to the user's
     *  regular selected accent whenever nothing is playing or artwork
     *  colors can't be extracted — see ThemeRepository.updateNowPlayingArtwork. */
    val dynamicNowPlayingEnabled: Boolean = false,
    /** "Use Application Font" — on: the bundled Google Sans Flex variable
     *  font (see ui/theme/Type.kt); off: the device's own system font.
     *  Defaults on so the app ships with its own identity out of the box. */
    val useCustomFont: Boolean = true,
    /** Last.fm usernames pinned to the top of Home's friend-switcher sheet
     *  (long-press a friend row to toggle). Order among pinned friends
     *  follows whatever order user.getfriends itself returns them in —
     *  just filtered to the front, not independently reorderable. */
    val pinnedFriends: Set<String> = emptySet(),
    /** When true, the player attempts to resolve and stream lossless / Hi-Res audio
     *  directly from lossless CDN when a high-confidence match exists. Falls back to YouTube Music. */
    val preferLosslessStreaming: Boolean = true,
    /** When true, the player also queries installed provider modules in
     *  parallel with the backend; a module hit plays instantly on miss. */
    val preferProviderModules: Boolean = true,
    /** Preferred quality preset for lossless streaming (27: 24/192, 7: 24/96, 6: 16/44.1, 5: 320k).
     *  If a track does not support the requested quality, the worker automatically selects the highest available. */
    val losslessQuality: Int = 27,
    /** Preferred quality preset for downloads (27: 24/192, 7: 24/96, 6: 16/44.1, 5: 320k, -1: YouTube Music). */
    val downloadQuality: Int = 27,
    /** Optional studio-clarity curve. On by default; Bit-Perfect disables it. */
    val isStudioMasterClarityEnabled: Boolean = true,
    /** When true, completely bypasses DSP, EQ, tone effects, and software volume ducking for bit-exact audio. */
    val isBitPerfectEnabled: Boolean = false,
    /** Lyrics UI layout version (Classic or Modern). */
    val lyricsUiVersion: LyricsUiVersion = LyricsUiVersion.MODERN,
    val wordByWordLyrics: Boolean = true,
    /** Experimental lyrics animation style (Settings -> Experimental -> Lyrics Animation). */
    val lyricsAnimation: LyricsAnimation = LyricsAnimation.APPLE_FLUID,
    /** Blend the end of one queued track into the beginning of the next. */
    val crossfadeEnabled: Boolean = false,
    /** Crossfade length in seconds; kept within the native settings slider range. */
    val crossfadeSeconds: Int = 5,
    /** When true (default), uses the multi-layer dynamic wavy seekbar.
     *  When false, uses the classic standard progress slider in the player tab. */
    val wavySeekbarEnabled: Boolean = true,
    /** When true (default), downloads fetch and save synced lyrics (.lrc companion files and embedded tags). */
    val downloadLyrics: Boolean = true,
    /** In-app language override tag: "system" (default), "en", "tr", "zh-Hans". */
    val appLanguageTag: String = AppLanguage.SYSTEM.tag,
    /** Subfolder name under public Music/ where downloads are saved.
     *  Default "LastWave" -> Music/LastWave. Scoped storage (API 29+)
     *  forbids arbitrary paths, so only the leaf folder name is configurable. */
    val downloadFolder: String = DEFAULT_DOWNLOAD_FOLDER,
    /** Subfolder layout under Music/<downloadFolder>/ (flat by default). */
    val downloadStructure: DownloadFolderStructure = DownloadFolderStructure.FLAT,
    /** When true, folder names use the album-artist tag when available (falls back to track artist). */
    val useAlbumArtistForFolders: Boolean = true,
    /** When true, folder names use only the primary artist (strips feat./collaborators). */
    val primaryArtistOnly: Boolean = true,
    /** Ids of Home tab sections the user hid ([HomeSection.id]).
     *  Empty = everything visible. Unknown ids are dropped on read. */
    val hiddenHomeSections: Set<String> = emptySet(),
)

/** Toggleable sections of the Home tab (see FeedScreen). Hero greeting and
 *  footer are structural; everything listed here can be hidden by the user.
 *  Titles are string resources so HomeSectionsScreen follows the app language. */
enum class HomeSection(val id: String, val titleRes: Int, val subtitleRes: Int) {
    HERO("hero", com.lastwave.app.R.string.home_section_hero_title, com.lastwave.app.R.string.home_section_hero_sub),
    QUICK_TILES("quick_tiles", com.lastwave.app.R.string.home_section_quick_tiles_title, com.lastwave.app.R.string.home_section_quick_tiles_sub),
    TASTE_STRIP("taste_strip", com.lastwave.app.R.string.home_section_taste_strip_title, com.lastwave.app.R.string.home_section_taste_strip_sub),
    QUICK_PICKS("quick_picks", com.lastwave.app.R.string.home_section_quick_picks_title, com.lastwave.app.R.string.home_section_quick_picks_sub),
    BECAUSE_YOU_LISTEN_TO("because_you_listen_to", com.lastwave.app.R.string.home_section_because_title, com.lastwave.app.R.string.home_section_because_sub),
    FRESH_FINDS("fresh_finds", com.lastwave.app.R.string.home_section_fresh_title, com.lastwave.app.R.string.home_section_fresh_sub),
    JUMP_BACK_IN("jump_back_in", com.lastwave.app.R.string.home_section_jump_title, com.lastwave.app.R.string.home_section_jump_sub),
    MIXES("mixed_for_you", com.lastwave.app.R.string.home_section_mixes_title, com.lastwave.app.R.string.home_section_mixes_sub),
    SPOTLIGHT("spotlight_hero", com.lastwave.app.R.string.home_section_spotlight_title, com.lastwave.app.R.string.home_section_spotlight_sub),
    TOP_ARTISTS("top_artists", com.lastwave.app.R.string.home_section_artists_title, com.lastwave.app.R.string.home_section_artists_sub),
    HEAVY_ROTATION("heavy_rotation", com.lastwave.app.R.string.home_section_heavy_title, com.lastwave.app.R.string.home_section_heavy_sub),
    ALBUMS("albums_in_rotation", com.lastwave.app.R.string.home_section_albums_title, com.lastwave.app.R.string.home_section_albums_sub),
    CHARTS("trending_charts", com.lastwave.app.R.string.home_section_charts_title, com.lastwave.app.R.string.home_section_charts_sub),
    NEW_RELEASES("new_releases", com.lastwave.app.R.string.home_section_releases_title, com.lastwave.app.R.string.home_section_releases_sub),
    FRIENDS("friends_activity", com.lastwave.app.R.string.home_section_friends_title, com.lastwave.app.R.string.home_section_friends_sub);

    companion object {
        fun fromId(id: String?): HomeSection? =
            entries.firstOrNull { it.id == id }
    }
}

/** Subfolder layout for downloaded tracks under Music/<downloadFolder>/.
 *  Titles are string resources; [example] stays raw (filesystem paths are universal). */
enum class DownloadFolderStructure(val id: String, val titleRes: Int, val example: String, val shortLabelRes: Int) {
    FLAT("flat", com.lastwave.app.R.string.dl_struct_flat_title, "Music/<dir>/song.flac", com.lastwave.app.R.string.dl_struct_flat_short),
    ARTIST_ALBUM("artist_album", com.lastwave.app.R.string.dl_struct_artist_album_title, "Artist/Album/", com.lastwave.app.R.string.dl_struct_artist_album_short),
    ARTIST_YEAR_ALBUM("artist_year_album", com.lastwave.app.R.string.dl_struct_artist_year_album_title, "Artist/[2025] Album/", com.lastwave.app.R.string.dl_struct_artist_year_album_short),
    ALBUM_ONLY("album_only", com.lastwave.app.R.string.dl_struct_album_only_title, "Album/", com.lastwave.app.R.string.dl_struct_album_only_short),
    YEAR_ALBUM("year_album", com.lastwave.app.R.string.dl_struct_year_album_title, "[2025] Album/", com.lastwave.app.R.string.dl_struct_year_album_short),
    ARTIST_ALBUM_SINGLES("artist_album_singles", com.lastwave.app.R.string.dl_struct_artist_album_singles_title, "Artist/Album/ and Artist/Singles/", com.lastwave.app.R.string.dl_struct_artist_album_singles_short),
    ARTIST_ALBUM_SINGLES_FLAT("artist_album_singles_flat", com.lastwave.app.R.string.dl_struct_artist_album_singles_flat_title, "Artist/Album/ and Artist/song.flac", com.lastwave.app.R.string.dl_struct_artist_album_singles_flat_short);

    companion object {
        fun fromId(id: String?): DownloadFolderStructure =
            entries.firstOrNull { it.id == id } ?: FLAT
    }
}

/** Default subfolder under Music/ for offline tracks (Music/LastWave). */
const val DEFAULT_DOWNLOAD_FOLDER = "LastWave"

/** Sanitize a user-entered download folder to a safe single path segment:
 *  no separators, no traversal, max 40 chars, fallback to default. */
fun sanitizeDownloadFolderName(raw: String?): String {
    var name = raw?.trim().orEmpty()
    // Prevent path traversal: keep only the last segment.
    name = name.split('/', '\\').lastOrNull()?.trim().orEmpty()
    name = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().trim('.', '_').trim()
    if (name.length > 40) name = name.take(40).trim()
    if (name.isBlank() || name == "." || name == "..") return DEFAULT_DOWNLOAD_FOLDER
    return name
}

/** Small dedicated prefs object for settings that don't fit ThemePreferences
 *  or SessionPreferences semantically — shares the app's single DataStore. */
@Singleton
class SettingsPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @ApplicationContext private val appContext: Context,
) {
    private object Keys {
        val DYNAMIC_NOW_PLAYING = booleanPreferencesKey("lw_dynamic_now_playing")
        val USE_CUSTOM_FONT = booleanPreferencesKey("lw_use_custom_font")
        val PINNED_FRIENDS = stringSetPreferencesKey("lw_pinned_friends")
        val PREFER_LOSSLESS_STREAMING = booleanPreferencesKey("lw_prefer_lossless_streaming")
        val PREFER_PROVIDER_MODULES = booleanPreferencesKey("lw_prefer_provider_modules")
        val LOSSLESS_QUALITY = intPreferencesKey("lw_lossless_quality")
        val DOWNLOAD_QUALITY = intPreferencesKey("lw_download_quality")
        val MUSIC_ENHANCER = booleanPreferencesKey("lw_music_enhancer")
        val BIT_PERFECT_ENABLED = booleanPreferencesKey("lw_bit_perfect_enabled")
        val LYRICS_UI_VERSION = stringPreferencesKey("lw_lyrics_ui_version")
        val WORD_BY_WORD_LYRICS = booleanPreferencesKey("lw_word_by_word_lyrics")
        val LYRICS_ANIMATION = stringPreferencesKey("lw_lyrics_animation")
        val CROSSFADE_ENABLED = booleanPreferencesKey("lw_crossfade_enabled")
        val CROSSFADE_SECONDS = intPreferencesKey("lw_crossfade_seconds")
        val WAVY_SEEKBAR_ENABLED = booleanPreferencesKey("lw_wavy_seekbar_enabled")
        val DOWNLOAD_LYRICS = booleanPreferencesKey("lw_download_lyrics")
        val APP_LANGUAGE = stringPreferencesKey("lw_app_language")
        val DOWNLOAD_FOLDER = stringPreferencesKey("lw_download_folder")
        val DOWNLOAD_STRUCTURE = stringPreferencesKey("lw_download_structure")
        val USE_ALBUM_ARTIST_FOLDERS = booleanPreferencesKey("lw_use_album_artist_folders")
        val PRIMARY_ARTIST_ONLY = booleanPreferencesKey("lw_primary_artist_only")
        val HIDDEN_HOME_SECTIONS = stringSetPreferencesKey("lw_hidden_home_sections")
    }

    val settings: Flow<MiscSettings> = dataStore.data
        .recoverPreferences("SettingsPreferences")
        .map { p ->
            MiscSettings(
                dynamicNowPlayingEnabled = p.readSafely(Keys.DYNAMIC_NOW_PLAYING) ?: false,
                useCustomFont = p.readSafely(Keys.USE_CUSTOM_FONT) ?: true,
                pinnedFriends = p.readSafely(Keys.PINNED_FRIENDS) ?: emptySet(),
                preferLosslessStreaming = p.readSafely(Keys.PREFER_LOSSLESS_STREAMING) ?: true,
                preferProviderModules = p.readSafely(Keys.PREFER_PROVIDER_MODULES) ?: true,
                losslessQuality = p.readSafely(Keys.LOSSLESS_QUALITY)?.takeIf { it in LOSSLESS_QUALITIES } ?: 27,
                downloadQuality = p.readSafely(Keys.DOWNLOAD_QUALITY)?.takeIf { it in DOWNLOAD_QUALITIES } ?: 27,
                isStudioMasterClarityEnabled = p.readSafely(Keys.MUSIC_ENHANCER) ?: true,
                isBitPerfectEnabled = p.readSafely(Keys.BIT_PERFECT_ENABLED) ?: false,
                lyricsUiVersion = LyricsUiVersion.fromId(p.readSafely(Keys.LYRICS_UI_VERSION)),
                wordByWordLyrics = p.readSafely(Keys.WORD_BY_WORD_LYRICS) ?: true,
                lyricsAnimation = LyricsAnimation.fromId(p.readSafely(Keys.LYRICS_ANIMATION)),
                crossfadeEnabled = p.readSafely(Keys.CROSSFADE_ENABLED) ?: false,
                crossfadeSeconds = (p.readSafely(Keys.CROSSFADE_SECONDS) ?: 5).coerceIn(1, 12),
                wavySeekbarEnabled = p.readSafely(Keys.WAVY_SEEKBAR_ENABLED) ?: true,
                downloadLyrics = p.readSafely(Keys.DOWNLOAD_LYRICS) ?: true,
                appLanguageTag = AppLanguage.fromTag(p.readSafely(Keys.APP_LANGUAGE)).tag,
                downloadFolder = sanitizeDownloadFolderName(p.readSafely(Keys.DOWNLOAD_FOLDER)),
                downloadStructure = DownloadFolderStructure.fromId(p.readSafely(Keys.DOWNLOAD_STRUCTURE)),
                useAlbumArtistForFolders = p.readSafely(Keys.USE_ALBUM_ARTIST_FOLDERS) ?: true,
                primaryArtistOnly = p.readSafely(Keys.PRIMARY_ARTIST_ONLY) ?: true,
                hiddenHomeSections = p.readSafely(Keys.HIDDEN_HOME_SECTIONS)
                    ?.filter { id -> HomeSection.entries.any { it.id == id } }?.toSet()
                    ?: emptySet(),
            )
        }

    suspend fun setDynamicNowPlaying(enabled: Boolean) {
        dataStore.edit { it[Keys.DYNAMIC_NOW_PLAYING] = enabled }
    }

    suspend fun setUseCustomFont(enabled: Boolean) {
        dataStore.edit { it[Keys.USE_CUSTOM_FONT] = enabled }
    }

    suspend fun setPreferLosslessStreaming(enabled: Boolean) {
        dataStore.edit {
            it[Keys.PREFER_LOSSLESS_STREAMING] = enabled
        }
    }

    suspend fun setPreferProviderModules(enabled: Boolean) {
        dataStore.edit {
            it[Keys.PREFER_PROVIDER_MODULES] = enabled
        }
    }

    suspend fun setLosslessQuality(quality: Int) {
        dataStore.edit {
            val q = quality.takeIf { it in LOSSLESS_QUALITIES } ?: 27
            it[Keys.LOSSLESS_QUALITY] = q
        }
    }

    suspend fun setDownloadQuality(quality: Int) {
        dataStore.edit {
            val q = quality.takeIf { it in DOWNLOAD_QUALITIES } ?: 27
            it[Keys.DOWNLOAD_QUALITY] = q
        }
    }

    suspend fun setStudioMasterClarity(enabled: Boolean) {
        dataStore.edit { it[Keys.MUSIC_ENHANCER] = enabled }
    }

    suspend fun setLyricsUiVersion(version: LyricsUiVersion) {
        dataStore.edit { it[Keys.LYRICS_UI_VERSION] = version.id }
    }

    suspend fun setBitPerfectEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.BIT_PERFECT_ENABLED] = enabled }
    }

    suspend fun setWordByWordLyrics(enabled: Boolean) {
        dataStore.edit { it[Keys.WORD_BY_WORD_LYRICS] = enabled }
    }

    suspend fun setLyricsAnimation(animation: LyricsAnimation) {
        dataStore.edit { it[Keys.LYRICS_ANIMATION] = animation.id }
    }

    suspend fun setCrossfadeEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.CROSSFADE_ENABLED] = enabled }
    }

    suspend fun setCrossfadeSeconds(seconds: Int) {
        dataStore.edit { it[Keys.CROSSFADE_SECONDS] = seconds.coerceIn(1, 12) }
    }

    suspend fun setWavySeekbarEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.WAVY_SEEKBAR_ENABLED] = enabled }
    }

    suspend fun setDownloadLyrics(enabled: Boolean) {
        dataStore.edit { it[Keys.DOWNLOAD_LYRICS] = enabled }
    }

    suspend fun setDownloadFolder(name: String) {
        dataStore.edit { it[Keys.DOWNLOAD_FOLDER] = sanitizeDownloadFolderName(name) }
    }

    suspend fun setDownloadStructure(structure: DownloadFolderStructure) {
        dataStore.edit { it[Keys.DOWNLOAD_STRUCTURE] = structure.id }
    }

    suspend fun setUseAlbumArtistForFolders(enabled: Boolean) {
        dataStore.edit { it[Keys.USE_ALBUM_ARTIST_FOLDERS] = enabled }
    }

    suspend fun setPrimaryArtistOnly(enabled: Boolean) {
        dataStore.edit { it[Keys.PRIMARY_ARTIST_ONLY] = enabled }
    }

    suspend fun setHomeSectionVisible(id: String, visible: Boolean) {
        if (HomeSection.entries.none { it.id == id }) return
        dataStore.edit { prefs ->
            val current = prefs.readSafely(Keys.HIDDEN_HOME_SECTIONS) ?: emptySet()
            prefs[Keys.HIDDEN_HOME_SECTIONS] = if (visible) current - id else current + id
        }
    }

    suspend fun showAllHomeSections() {
        dataStore.edit { it.remove(Keys.HIDDEN_HOME_SECTIONS) }
    }

    /**
     * Synchronous mirror for `attachBaseContext`, which runs before DataStore
     * can deliver a value. commit() on IO: immune to process-kill races and
     * never blocks the UI thread.
     */
    fun readLanguageTagSync(): String =
        runCatching {
            appContext.getSharedPreferences(AppLanguage.SYNC_FILE, Context.MODE_PRIVATE)
                .getString(AppLanguage.SYNC_KEY, AppLanguage.SYSTEM.tag)
        }.getOrNull().let { AppLanguage.fromTag(it).tag }

    suspend fun setAppLanguage(language: AppLanguage) {
        dataStore.edit { it[Keys.APP_LANGUAGE] = language.tag }
        withContext(Dispatchers.IO) {
            runCatching {
                appContext.getSharedPreferences(AppLanguage.SYNC_FILE, Context.MODE_PRIVATE)
                    .edit()
                    .putString(AppLanguage.SYNC_KEY, language.tag)
                    .commit()
            }
        }
    }

    suspend fun toggleFriendPinned(username: String) {
        dataStore.edit { prefs ->
            val current = prefs.readSafely(Keys.PINNED_FRIENDS) ?: emptySet()
            prefs[Keys.PINNED_FRIENDS] = if (username in current) current - username else current + username
        }
    }

    private companion object {
        val LOSSLESS_QUALITIES = setOf(-1, 5, 6, 7, 27)
        val DOWNLOAD_QUALITIES = setOf(-1, 5, 6, 7, 27)
    }
}
