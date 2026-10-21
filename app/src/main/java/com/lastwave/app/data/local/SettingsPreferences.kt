package com.lastwave.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

@Serializable
data class HttpAddonConfig(
    val id: String = java.util.UUID.randomUUID().toString(),
    val url: String = "",
    val name: String = "",
    val enabled: Boolean = true,
)


/** Preferred lyrics source. Apple Music (syllable-synced) is the default:
 *  it is tried first and the app falls through to the rest automatically
 *  when it returns nothing. AUTO keeps the legacy behavior (race the
 *  word-sync providers, LRCLIB line-sync fallback). Provider names are
 *  proper nouns — no localization needed. */
enum class LyricsProvider(val id: String, val title: String, val subtitle: String) {
    AUTO("auto", "Auto", "Fastest word-sync wins, LRCLIB fallback"),
    LASTWAVE("lastwave", "LastWave", "Syllable & line-synced Apple lyrics first"),
    LRC_RED("lrc_red", "lrc.red", "ISRC-indexed syllable TTML & LRC first"),
    APPLE_MUSIC("apple_music", "Apple Music", "Syllable-synced Apple Music lyrics first"),
    BETTER_LYRICS("better_lyrics", "BetterLyrics", "Word-synced lyrics first"),
    BETTER_LYRICS_PORTATO("better_lyrics_portato", "QQ Karaoke", "QQ Music karaoke word timings first"),
    KUGOU("kugou", "Kugou", "KRC word-synced lyrics first"),
    BINI_LYRICS("bini_lyrics", "Syllable-Sync", "Recording-matched syllable lyrics first"),
    SIMP_MUSIC("simp_music", "Video-Match", "Matched on the playing video first"),
    MUSIXMATCH("musixmatch", "Catalog", "Largest catalogue line-sync first"),
    LRCLIB("lrclib", "LRCLIB", "Line-synced community lyrics first"),
    LYRICS_PLUS("lyrics_plus", "LyricsPlus", "Syllable-synced on community mirrors first"),
    UNISON("unison", "Unison", "Community-submitted synced lyrics first"),
    YOUTUBE_CAPTIONS("youtube_captions", "YouTube Captions", "Timed captions from the playing video first"),
    YOUTUBE_MUSIC("youtube_music", "YouTube Music", "Plain lyrics from the video's Lyrics tab first"),
    MEGALOBIZ("megalobiz", "Megalobiz", "Community line-synced LRC first"),
    SPOTIFY("spotify", "Spotify", "Spotify Color Lyrics time-synced lyrics first"),
    GENIUS("genius", "Genius", "Plain-text web catalogue, tried last");

    val isWordProvider: Boolean get() =
        this == LASTWAVE || this == LRC_RED || this == APPLE_MUSIC || this == BETTER_LYRICS || this == KUGOU ||
            this == BINI_LYRICS || this == SIMP_MUSIC || this == MUSIXMATCH || this == SPOTIFY ||
            this == BETTER_LYRICS_PORTATO || this == LYRICS_PLUS || this == UNISON ||
            this == YOUTUBE_CAPTIONS || this == YOUTUBE_MUSIC || this == MEGALOBIZ || this == GENIUS

    companion object {
        fun fromId(id: String?): LyricsProvider =
            entries.firstOrNull { it.id == id } ?: AUTO
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
     *  parallel with the addon; a module hit plays instantly on miss. */
    val preferProviderModules: Boolean = true,
    /** Preferred quality preset for lossless streaming (27: 24/192, 7: 24/96, 6: 16/44.1, 5: 320k).
     *  If a track does not support the requested quality, the worker automatically selects the highest available. */
    val losslessQuality: Int = 27,
    /** Preferred quality preset for downloads (27: 24/192, 7: 24/96, 6: 16/44.1, 5: 320k, -1: YouTube Music). */
    val downloadQuality: Int = 27,
    /** Optional studio-clarity curve. On by default; Bit-Perfect disables it. */
    val isStudioMasterClarityEnabled: Boolean = true,
    /** Clarity output preset index (0 Reference, 1 Speaker, 2 Headphone, 3 DAC). */
    val clarityPreset: Int = 0,
    /** When true, the clarity chain bypasses on multichannel/spatial sources. */
    val clarityAtmosBypass: Boolean = false,
    /** When true, completely bypasses DSP, EQ, tone effects, and software volume ducking for bit-exact audio. */
    val isBitPerfectEnabled: Boolean = false,
    /** System audio-effects mode (Settings -> Experimental). Off by default:
     *  when on, the app flattens its own DSP on mixer routes and publishes
     *  the audio session so external equalizer apps / OEM Dolby can process
     *  playback. Bypass routes (USB exclusive, bit-perfect) suspend it. */
    val systemEffectsMode: Boolean = false,
    val wordByWordLyrics: Boolean = true,
    /** Preferred lyrics source (Settings -> Experimental -> Lyrics Provider).
     *  AUTO races all providers (fastest valid word-sync wins); an explicit
     *  pick is tried first with the rest as fallback. */
    val lyricsProvider: LyricsProvider = LyricsProvider.AUTO,
    val lyricsOffsetMs: Long = 0L,
    /** Scale factor applied to lyrics font size across both modern and classic views.
     *  Default 1.0f (100%), clamped between 0.7f (70%) and 1.5f (150%). */
    val lyricsFontScale: Float = 1.0f,
    /** Blend the end of one queued track into the beginning of the next. */
    val crossfadeEnabled: Boolean = false,
    /** Crossfade length in seconds; kept within the native settings slider range. */
    val crossfadeSeconds: Int = 5,
    /** When true (default), uses the multi-layer dynamic wavy seekbar.
     *  When false, uses the classic standard progress slider in the player tab. */
    val wavySeekbarEnabled: Boolean = true,
    val rotatingBackgroundEnabled: Boolean = true,
    /** When true (default), animates fluid rotating artwork in the lyrics background.
     *  When false, uses static blurred artwork background in the lyrics view. */
    val lyricsRotatingBackgroundEnabled: Boolean = true,
    /** When true, keeps the screen from turning off when the user has the lyrics view open. */
    val keepLyricsScreenAwake: Boolean = false,
    /** Seconds of inactivity before the floating lyrics buttons (font size,
     *  fullscreen) fade out on the Lyrics screen; 0 = Never. Default 5. */
    val lyricsButtonsAutoHideSeconds: Int = 5,
    /** Language the lyric translate button turns songs into, spelled as the
     *  translation service expects ("fr", "ja", "zh-TW"…). Blank = follow the
     *  app language. */
    val translationLanguage: String = "",
    /** When true (default), downloads fetch and save synced lyrics (.lrc companion files and embedded tags). */
    val downloadLyrics: Boolean = true,
    /** Stable ids for the last sort selected on each Downloads tab. */
    val downloadSongSort: String = "recent",
    val downloadArtistSort: String = "name_az",
    val downloadAlbumSort: String = "title_az",
    /** In-app language override tag: "system" (default), "en", "tr", "zh-Hans". */
    val appLanguageTag: String = AppLanguage.SYSTEM.tag,
    /** Subfolder name under public Music/ where downloads are saved.
     *  Default "LastWave" -> Music/LastWave. Scoped storage (API 29+)
     *  forbids arbitrary paths, so only the leaf folder name is configurable. */
    val downloadFolder: String = DEFAULT_DOWNLOAD_FOLDER,
    /** User-chosen SAF tree Uri (ACTION_OPEN_DOCUMENT_TREE) for downloads,
     *  e.g. a folder on the SD card. Blank = default Music/<downloadFolder>.
     *  Persisted via takePersistableUriPermission so it survives reboots. */
    val downloadTreeUri: String = "",
    /** Subfolder layout under Music/<downloadFolder>/ (flat by default). */
    val downloadStructure: DownloadFolderStructure = DownloadFolderStructure.FLAT,
    /** When true, folder names use the album-artist tag when available (falls back to track artist). */
    val useAlbumArtistForFolders: Boolean = true,
    /** When true, folder names use only the primary artist (strips feat./collaborators). */
    val primaryArtistOnly: Boolean = true,
    /** User configured HTTP Addon URL (e.g. http://localhost:8787/a/<token>/). */
    val addonUrl: String = "",
    /** Display name discovered from the addon's manifest. */
    val addonName: String = "",
    /** When true, the addon is active for search, streaming, and downloads. */
    val addonEnabled: Boolean = true,
    /** Configured HTTP addons supporting multiple concurrent servers. */
    val httpAddons: List<HttpAddonConfig> = emptyList(),
    /** Ids of Home tab sections the user hid ([HomeSection.id]).
     *  Empty = everything visible. Unknown ids are dropped on read. */
    val hiddenHomeSections: Set<String> = emptySet(),
    /** When true, Animated Album Canvas motion video loops are displayed. */
    val canvasEnabled: Boolean = true,
    /** When true, portrait motion artwork is displayed edge-to-edge behind player controls. */
    val canvasFullBleed: Boolean = true,
    /** When true, canvas video loops may be fetched over cellular data. */
    val canvasOverCellular: Boolean = true,
    /** When true, anonymous listening telemetry connects to the LastWave Live Global Map. */
    val liveTelemetryEnabled: Boolean = true,
    /** When false, podcast shelves, screens and episode search stay hidden.
     *  Existing progress is preserved so re-enabling resumes where you left off. */
    val podcastsEnabled: Boolean = true,
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

/** Clamps the lyrics-buttons auto-hide delay: 0 keeps "Never", everything
 *  else is forced into the offered 2..10 second range, null = 5 s default. */
fun sanitizeLyricsButtonsAutoHideSeconds(raw: Int?): Int = when {
    raw == null -> 5
    raw == 0 -> 0
    else -> raw.coerceIn(2, 10)
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
        val CLARITY_PRESET = intPreferencesKey("lw_clarity_preset")
        val CLARITY_ATMOS_BYPASS = booleanPreferencesKey("lw_clarity_atmos_bypass")
        val BIT_PERFECT_ENABLED = booleanPreferencesKey("lw_bit_perfect_enabled")
        val WORD_BY_WORD_LYRICS = booleanPreferencesKey("lw_word_by_word_lyrics")
        val LYRICS_PROVIDER = stringPreferencesKey("lw_lyrics_provider")
        val LYRICS_OFFSET_MS = longPreferencesKey("lw_lyrics_offset_ms")
        val LYRICS_FONT_SCALE = floatPreferencesKey("lw_lyrics_font_scale")
        val CROSSFADE_ENABLED = booleanPreferencesKey("lw_crossfade_enabled")
        val CROSSFADE_SECONDS = intPreferencesKey("lw_crossfade_seconds")
        val WAVY_SEEKBAR_ENABLED = booleanPreferencesKey("lw_wavy_seekbar_enabled")
        val ROTATING_BACKGROUND_ENABLED = booleanPreferencesKey("lw_rotating_background_enabled")
        val LYRICS_ROTATING_BACKGROUND_ENABLED = booleanPreferencesKey("lw_lyrics_rotating_background_enabled")
        val KEEP_LYRICS_SCREEN_AWAKE = booleanPreferencesKey("lw_keep_lyrics_screen_awake")
        val LYRICS_BUTTONS_AUTO_HIDE_SECONDS = intPreferencesKey("lw_lyrics_buttons_auto_hide_seconds")
        val TRANSLATION_LANGUAGE = stringPreferencesKey("lw_translation_language")
        val DOWNLOAD_LYRICS = booleanPreferencesKey("lw_download_lyrics")
        val DOWNLOAD_SONG_SORT = stringPreferencesKey("lw_download_song_sort")
        val DOWNLOAD_ARTIST_SORT = stringPreferencesKey("lw_download_artist_sort")
        val DOWNLOAD_ALBUM_SORT = stringPreferencesKey("lw_download_album_sort")
        val APP_LANGUAGE = stringPreferencesKey("lw_app_language")
        val DOWNLOAD_FOLDER = stringPreferencesKey("lw_download_folder")
        val DOWNLOAD_TREE_URI = stringPreferencesKey("lw_download_tree_uri")
        val DOWNLOAD_STRUCTURE = stringPreferencesKey("lw_download_structure")
        val SYSTEM_EFFECTS_MODE = booleanPreferencesKey("lw_system_effects_mode")
        val USE_ALBUM_ARTIST_FOLDERS = booleanPreferencesKey("lw_use_album_artist_folders")
        val PRIMARY_ARTIST_ONLY = booleanPreferencesKey("lw_primary_artist_only")
        val HIDDEN_HOME_SECTIONS = stringSetPreferencesKey("lw_hidden_home_sections")
        val ADDON_URL = stringPreferencesKey("lw_addon_url")
        val ADDON_NAME = stringPreferencesKey("lw_addon_name")
        val ADDON_ENABLED = booleanPreferencesKey("lw_addon_enabled")
        val HTTP_ADDONS_JSON = stringPreferencesKey("lw_http_addons_json")
        val CANVAS_ENABLED = booleanPreferencesKey("lw_canvas_enabled")
        val CANVAS_FULL_BLEED = booleanPreferencesKey("lw_canvas_full_bleed")
        val CANVAS_OVER_CELLULAR = booleanPreferencesKey("lw_canvas_over_cellular")
        val LIVE_TELEMETRY_ENABLED = booleanPreferencesKey("lw_live_telemetry_enabled")
        val PODCASTS_ENABLED = booleanPreferencesKey("lw_podcasts_enabled")
    }

    val settings: Flow<MiscSettings> = dataStore.data
        .recoverPreferences("SettingsPreferences")
        .map { p ->
            val rawAddonsJson = p.readSafely(Keys.HTTP_ADDONS_JSON)
            val parsedAddons = if (!rawAddonsJson.isNullOrBlank()) {
                runCatching {
                    Json { ignoreUnknownKeys = true; isLenient = true }.decodeFromString<List<HttpAddonConfig>>(rawAddonsJson)
                }.getOrDefault(emptyList())
            } else emptyList()

            val legacyUrl = p.readSafely(Keys.ADDON_URL)?.trim().orEmpty()
            val legacyName = p.readSafely(Keys.ADDON_NAME)?.trim().orEmpty()
            val legacyEnabled = p.readSafely(Keys.ADDON_ENABLED) ?: true

            val resolvedAddons = when {
                parsedAddons.isNotEmpty() -> parsedAddons
                legacyUrl.isNotBlank() -> listOf(
                    HttpAddonConfig(
                        id = "default_legacy",
                        url = legacyUrl,
                        name = legacyName.ifBlank { "HTTP Addon" },
                        enabled = legacyEnabled
                    )
                )
                else -> emptyList()
            }
            val primaryAddon = resolvedAddons.firstOrNull { it.enabled } ?: resolvedAddons.firstOrNull()

            MiscSettings(
                dynamicNowPlayingEnabled = p.readSafely(Keys.DYNAMIC_NOW_PLAYING) ?: false,
                useCustomFont = p.readSafely(Keys.USE_CUSTOM_FONT) ?: true,
                pinnedFriends = p.readSafely(Keys.PINNED_FRIENDS) ?: emptySet(),
                preferLosslessStreaming = p.readSafely(Keys.PREFER_LOSSLESS_STREAMING) ?: true,
                preferProviderModules = p.readSafely(Keys.PREFER_PROVIDER_MODULES) ?: true,
                losslessQuality = p.readSafely(Keys.LOSSLESS_QUALITY)?.takeIf { it in LOSSLESS_QUALITIES } ?: 27,
                downloadQuality = p.readSafely(Keys.DOWNLOAD_QUALITY)?.takeIf { it in DOWNLOAD_QUALITIES } ?: 27,
                systemEffectsMode = p.readSafely(Keys.SYSTEM_EFFECTS_MODE) ?: false,
                isStudioMasterClarityEnabled = p.readSafely(Keys.MUSIC_ENHANCER) ?: true,
                clarityPreset = p.readSafely(Keys.CLARITY_PRESET)?.takeIf { it in 0..3 } ?: 0,
                clarityAtmosBypass = p.readSafely(Keys.CLARITY_ATMOS_BYPASS) ?: false,
                isBitPerfectEnabled = p.readSafely(Keys.BIT_PERFECT_ENABLED) ?: false,
                wordByWordLyrics = p.readSafely(Keys.WORD_BY_WORD_LYRICS) ?: true,
                lyricsProvider = LyricsProvider.fromId(p.readSafely(Keys.LYRICS_PROVIDER)),
                lyricsOffsetMs = (p.readSafely(Keys.LYRICS_OFFSET_MS) ?: 0L).coerceIn(-10000L, 10000L),
                lyricsFontScale = (p.readSafely(Keys.LYRICS_FONT_SCALE) ?: 1.0f).coerceIn(0.7f, 1.5f),
                crossfadeEnabled = p.readSafely(Keys.CROSSFADE_ENABLED) ?: false,
                crossfadeSeconds = (p.readSafely(Keys.CROSSFADE_SECONDS) ?: 5).coerceIn(1, 12),
                wavySeekbarEnabled = p.readSafely(Keys.WAVY_SEEKBAR_ENABLED) ?: true,
                rotatingBackgroundEnabled = p.readSafely(Keys.ROTATING_BACKGROUND_ENABLED) ?: true,
                lyricsRotatingBackgroundEnabled = p.readSafely(Keys.LYRICS_ROTATING_BACKGROUND_ENABLED) ?: true,
                keepLyricsScreenAwake = p.readSafely(Keys.KEEP_LYRICS_SCREEN_AWAKE) ?: false,
                lyricsButtonsAutoHideSeconds =
                    sanitizeLyricsButtonsAutoHideSeconds(p.readSafely(Keys.LYRICS_BUTTONS_AUTO_HIDE_SECONDS)),
                translationLanguage = p.readSafely(Keys.TRANSLATION_LANGUAGE)?.trim().orEmpty(),
                downloadLyrics = p.readSafely(Keys.DOWNLOAD_LYRICS) ?: true,
                downloadSongSort = p.readSafely(Keys.DOWNLOAD_SONG_SORT) ?: "recent",
                downloadArtistSort = p.readSafely(Keys.DOWNLOAD_ARTIST_SORT) ?: "name_az",
                downloadAlbumSort = p.readSafely(Keys.DOWNLOAD_ALBUM_SORT) ?: "title_az",
                appLanguageTag = AppLanguage.fromTag(p.readSafely(Keys.APP_LANGUAGE)).tag,
                downloadFolder = sanitizeDownloadFolderName(p.readSafely(Keys.DOWNLOAD_FOLDER)),
                downloadTreeUri = p.readSafely(Keys.DOWNLOAD_TREE_URI)?.takeIf { it.isNotBlank() }.orEmpty(),
                downloadStructure = DownloadFolderStructure.fromId(p.readSafely(Keys.DOWNLOAD_STRUCTURE)),
                useAlbumArtistForFolders = p.readSafely(Keys.USE_ALBUM_ARTIST_FOLDERS) ?: true,
                primaryArtistOnly = p.readSafely(Keys.PRIMARY_ARTIST_ONLY) ?: true,
                hiddenHomeSections = p.readSafely(Keys.HIDDEN_HOME_SECTIONS)
                    ?.filter { id -> HomeSection.entries.any { it.id == id } }?.toSet()
                    ?: emptySet(),
                addonUrl = primaryAddon?.url ?: legacyUrl,
                addonName = primaryAddon?.name ?: legacyName,
                addonEnabled = primaryAddon?.enabled ?: legacyEnabled,
                httpAddons = resolvedAddons,
                canvasEnabled = p.readSafely(Keys.CANVAS_ENABLED) ?: true,
                canvasFullBleed = p.readSafely(Keys.CANVAS_FULL_BLEED) ?: true,
                canvasOverCellular = p.readSafely(Keys.CANVAS_OVER_CELLULAR) ?: true,
                liveTelemetryEnabled = p.readSafely(Keys.LIVE_TELEMETRY_ENABLED) ?: true,
                podcastsEnabled = p.readSafely(Keys.PODCASTS_ENABLED) ?: true,
            )
        }

    val addonUrl: StateFlow<String?> = settings
        .map { it.addonUrl.takeIf { s -> s.isNotBlank() } }
        .stateIn(CoroutineScope(Dispatchers.IO + SupervisorJob()), SharingStarted.Eagerly, null)

    val addonName: StateFlow<String?> = settings
        .map { it.addonName.takeIf { s -> s.isNotBlank() } }
        .stateIn(CoroutineScope(Dispatchers.IO + SupervisorJob()), SharingStarted.Eagerly, null)

    val addonEnabled: StateFlow<Boolean> = settings
        .map { it.addonEnabled }
        .stateIn(CoroutineScope(Dispatchers.IO + SupervisorJob()), SharingStarted.Eagerly, true)

    val httpAddons: StateFlow<List<HttpAddonConfig>> = settings
        .map { it.httpAddons }
        .stateIn(CoroutineScope(Dispatchers.IO + SupervisorJob()), SharingStarted.Eagerly, emptyList())

    suspend fun addHttpAddon(url: String, name: String): HttpAddonConfig {
        val trimmedUrl = url.trim()
        val trimmedName = name.trim().ifBlank { "HTTP Addon" }
        val newEntry = HttpAddonConfig(
            id = java.util.UUID.randomUUID().toString(),
            url = trimmedUrl,
            name = trimmedName,
            enabled = true,
        )
        dataStore.edit { prefs ->
            val current = getCurrentAddons(prefs)
            val updated = current + newEntry
            prefs[Keys.HTTP_ADDONS_JSON] = Json.encodeToString(updated)
            syncLegacyAddonKeys(prefs, updated)
        }
        return newEntry
    }

    suspend fun updateHttpAddon(id: String, url: String, name: String?) {
        dataStore.edit { prefs ->
            val current = getCurrentAddons(prefs)
            val updated = current.map {
                if (it.id == id) {
                    it.copy(
                        url = url.trim(),
                        name = name?.trim()?.ifBlank { it.name } ?: it.name
                    )
                } else it
            }
            prefs[Keys.HTTP_ADDONS_JSON] = Json.encodeToString(updated)
            syncLegacyAddonKeys(prefs, updated)
        }
    }

    suspend fun toggleHttpAddon(id: String, enabled: Boolean) {
        dataStore.edit { prefs ->
            val current = getCurrentAddons(prefs)
            val updated = current.map {
                if (it.id == id) it.copy(enabled = enabled) else it
            }
            prefs[Keys.HTTP_ADDONS_JSON] = Json.encodeToString(updated)
            syncLegacyAddonKeys(prefs, updated)
        }
    }

    suspend fun removeHttpAddon(id: String) {
        dataStore.edit { prefs ->
            val current = getCurrentAddons(prefs)
            val updated = current.filterNot { it.id == id }
            prefs[Keys.HTTP_ADDONS_JSON] = Json.encodeToString(updated)
            syncLegacyAddonKeys(prefs, updated)
        }
    }

    private fun getCurrentAddons(prefs: Preferences): List<HttpAddonConfig> {
        val raw = prefs.readSafely(Keys.HTTP_ADDONS_JSON)
        if (!raw.isNullOrBlank()) {
            val parsed = runCatching {
                Json { ignoreUnknownKeys = true; isLenient = true }.decodeFromString<List<HttpAddonConfig>>(raw)
            }.getOrNull()
            if (!parsed.isNullOrEmpty()) return parsed
        }
        val legacyUrl = prefs.readSafely(Keys.ADDON_URL)?.trim().orEmpty()
        val legacyName = prefs.readSafely(Keys.ADDON_NAME)?.trim().orEmpty()
        val legacyEnabled = prefs.readSafely(Keys.ADDON_ENABLED) ?: true
        if (legacyUrl.isNotBlank()) {
            return listOf(
                HttpAddonConfig(
                    id = "default_legacy",
                    url = legacyUrl,
                    name = legacyName.ifBlank { "HTTP Addon" },
                    enabled = legacyEnabled
                )
            )
        }
        return emptyList()
    }

    private fun syncLegacyAddonKeys(prefs: androidx.datastore.preferences.core.MutablePreferences, list: List<HttpAddonConfig>) {
        val primary = list.firstOrNull { it.enabled } ?: list.firstOrNull()
        if (primary != null) {
            prefs[Keys.ADDON_URL] = primary.url
            prefs[Keys.ADDON_NAME] = primary.name
            prefs[Keys.ADDON_ENABLED] = primary.enabled
        } else {
            prefs.remove(Keys.ADDON_URL)
            prefs.remove(Keys.ADDON_NAME)
            prefs.remove(Keys.ADDON_ENABLED)
        }
    }

    suspend fun setAddonUrl(url: String?) {
        dataStore.edit { prefs ->
            val current = getCurrentAddons(prefs)
            val updated = if (url.isNullOrBlank()) {
                if (current.isNotEmpty()) current.drop(1) else emptyList()
            } else {
                if (current.isEmpty()) {
                    listOf(HttpAddonConfig(url = url.trim(), name = "HTTP Addon", enabled = true))
                } else {
                    listOf(current.first().copy(url = url.trim())) + current.drop(1)
                }
            }
            prefs[Keys.HTTP_ADDONS_JSON] = Json.encodeToString(updated)
            syncLegacyAddonKeys(prefs, updated)
        }
    }

    suspend fun setAddonName(name: String?) {
        dataStore.edit { prefs ->
            val current = getCurrentAddons(prefs)
            val updated = if (current.isNotEmpty()) {
                listOf(current.first().copy(name = name?.trim().orEmpty())) + current.drop(1)
            } else current
            prefs[Keys.HTTP_ADDONS_JSON] = Json.encodeToString(updated)
            syncLegacyAddonKeys(prefs, updated)
        }
    }

    suspend fun setAddonEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            val current = getCurrentAddons(prefs)
            val updated = if (current.isNotEmpty()) {
                listOf(current.first().copy(enabled = enabled)) + current.drop(1)
            } else current
            prefs[Keys.HTTP_ADDONS_JSON] = Json.encodeToString(updated)
            syncLegacyAddonKeys(prefs, updated)
        }
    }

    suspend fun setDynamicNowPlaying(enabled: Boolean) {
        dataStore.edit { it[Keys.DYNAMIC_NOW_PLAYING] = enabled }
    }

    suspend fun setUseCustomFont(enabled: Boolean) {
        dataStore.edit { it[Keys.USE_CUSTOM_FONT] = enabled }
    }

    suspend fun setPodcastsEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.PODCASTS_ENABLED] = enabled }
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

    suspend fun setSystemEffectsMode(enabled: Boolean) {
        dataStore.edit { it[Keys.SYSTEM_EFFECTS_MODE] = enabled }
    }

    suspend fun setStudioMasterClarity(enabled: Boolean) {
        dataStore.edit { it[Keys.MUSIC_ENHANCER] = enabled }
    }

    suspend fun setClarityPreset(preset: Int) {
        dataStore.edit { it[Keys.CLARITY_PRESET] = preset.coerceIn(0, 3) }
    }

    suspend fun setClarityAtmosBypass(enabled: Boolean) {
        dataStore.edit { it[Keys.CLARITY_ATMOS_BYPASS] = enabled }
    }

    suspend fun setBitPerfectEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.BIT_PERFECT_ENABLED] = enabled }
    }

    suspend fun setWordByWordLyrics(enabled: Boolean) {
        dataStore.edit { it[Keys.WORD_BY_WORD_LYRICS] = enabled }
    }

    suspend fun setLyricsProvider(provider: LyricsProvider) {
        dataStore.edit { it[Keys.LYRICS_PROVIDER] = provider.id }
    }

    suspend fun setLyricsOffsetMs(offsetMs: Long) {
        dataStore.edit { it[Keys.LYRICS_OFFSET_MS] = offsetMs.coerceIn(-10000L, 10000L) }
    }

    suspend fun setLyricsFontScale(scale: Float) {
        dataStore.edit { it[Keys.LYRICS_FONT_SCALE] = scale.coerceIn(0.7f, 1.5f) }
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

    suspend fun setRotatingBackgroundEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.ROTATING_BACKGROUND_ENABLED] = enabled }
    }

    suspend fun setLyricsRotatingBackgroundEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.LYRICS_ROTATING_BACKGROUND_ENABLED] = enabled }
    }

    suspend fun setKeepLyricsScreenAwake(enabled: Boolean) {
        dataStore.edit { it[Keys.KEEP_LYRICS_SCREEN_AWAKE] = enabled }
    }

    suspend fun setLyricsButtonsAutoHideSeconds(seconds: Int) {
        dataStore.edit {
            it[Keys.LYRICS_BUTTONS_AUTO_HIDE_SECONDS] = sanitizeLyricsButtonsAutoHideSeconds(seconds)
        }
    }

    suspend fun setTranslationLanguage(language: String) {
        dataStore.edit { it[Keys.TRANSLATION_LANGUAGE] = language.trim() }
    }

    suspend fun setDownloadLyrics(enabled: Boolean) {
        dataStore.edit { it[Keys.DOWNLOAD_LYRICS] = enabled }
    }

    suspend fun setDownloadSongSort(sortId: String) {
        dataStore.edit { it[Keys.DOWNLOAD_SONG_SORT] = sortId }
    }

    suspend fun setDownloadArtistSort(sortId: String) {
        dataStore.edit { it[Keys.DOWNLOAD_ARTIST_SORT] = sortId }
    }

    suspend fun setDownloadAlbumSort(sortId: String) {
        dataStore.edit { it[Keys.DOWNLOAD_ALBUM_SORT] = sortId }
    }

    suspend fun setDownloadFolder(name: String) {
        dataStore.edit { it[Keys.DOWNLOAD_FOLDER] = sanitizeDownloadFolderName(name) }
    }

    /** Persists the user-chosen SAF download folder. Blank clears the
     *  custom location (back to the default Music/<downloadFolder>). */
    suspend fun setDownloadTreeUri(uriString: String) {
        dataStore.edit { prefs ->
            if (uriString.isBlank()) prefs.remove(Keys.DOWNLOAD_TREE_URI)
            else prefs[Keys.DOWNLOAD_TREE_URI] = uriString.trim()
        }
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

    suspend fun setCanvasEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.CANVAS_ENABLED] = enabled }
    }

    suspend fun setCanvasFullBleed(enabled: Boolean) {
        dataStore.edit { it[Keys.CANVAS_FULL_BLEED] = enabled }
    }

    suspend fun setCanvasOverCellular(enabled: Boolean) {
        dataStore.edit { it[Keys.CANVAS_OVER_CELLULAR] = enabled }
    }

    suspend fun setLiveTelemetryEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.LIVE_TELEMETRY_ENABLED] = enabled }
    }

    private companion object {
        val LOSSLESS_QUALITIES = setOf(-1, 4, 5, 6, 7, 27, 28)
        val DOWNLOAD_QUALITIES = setOf(-1, 4, 5, 6, 7, 27, 28)
    }
}
