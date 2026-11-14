package com.lastwave.app.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BubbleChart
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.SpeakerPhone
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.SwitchAccount
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Categorization of different setting entry interaction types.
 */
enum class EntryType {
    ACTION,
    TOGGLE,
    SLIDER,
    THEME_SELECTOR,
    ACCENT_PICKER,
    CUSTOM,
}

/**
 * A searchable settings entry indexed for fuzzy search.
 *
 * @property id Unique stable identifier for this setting (e.g. "audio.equalizer")
 * @property title Primary human-readable title of the setting
 * @property subtitle Descriptive explanation or default state of the setting
 * @property keywords List of curated keywords, synonyms, and technical terms to match against
 * @property icon The icon vector representing this setting
 * @property iconContainer Composable color provider for the icon background
 * @property iconTint Composable color provider for the icon foreground
 * @property parentTab The [SettingsTab] where this setting is located
 * @property section The section header within the parent tab (e.g. "Output")
 * @property type The interaction type of the setting
 */
data class SettingsEntry(
    val id: String,
    val title: String,
    val subtitle: String,
    val keywords: List<String>,
    val icon: ImageVector,
    val iconContainer: @Composable () -> Color,
    val iconTint: @Composable () -> Color,
    val parentTab: SettingsTab,
    val section: String,
    val type: EntryType = EntryType.ACTION,
)

/** The section headers of each tab, in the order the tab renders them.
 *  Search jumps to a section by its index, so this list is the one place the
 *  UI order and the search index have to agree. */
internal val SettingsTab.sectionOrder: List<String>
    get() = when (this) {
        SettingsTab.PLAYBACK -> listOf("Streaming", "Sound", "Output", "Playback")
        SettingsTab.NOW_PLAYING -> listOf("Player", "Canvas", "Lyrics")
        SettingsTab.APPEARANCE -> listOf("Theme", "Accent", "Icon & text", "Layout")
        SettingsTab.LIBRARY -> listOf("Home", "Content", "Imports")
        SettingsTab.DOWNLOADS -> listOf("Files", "Quality")
        SettingsTab.CONNECTIONS -> listOf("YouTube Music", "Spotify", "Last.fm", "Social")
        SettingsTab.SYSTEM -> listOf("Language", "Background playback", "Backup & restore", "Data")
        SettingsTab.ABOUT -> listOf("Support")
    }

private fun entry(
    id: String,
    title: String,
    subtitle: String,
    tab: SettingsTab,
    section: String,
    icon: ImageVector,
    keywords: String,
    type: EntryType = EntryType.ACTION,
) = SettingsEntry(
    id = id,
    title = title,
    subtitle = subtitle,
    keywords = keywords.split(',').map { it.trim() }.filter { it.isNotEmpty() },
    icon = icon,
    iconContainer = { MaterialTheme.colorScheme.primaryContainer },
    iconTint = { MaterialTheme.colorScheme.onPrimaryContainer },
    parentTab = tab,
    section = section,
    type = type,
)

/**
 * Static registry of all searchable settings entries across all tabs.
 * Keep ids stable: search highlights and scroll targets key on them.
 */
object SettingsSearchIndex {

    val allEntries: List<SettingsEntry> by lazy {
        buildList {
            // ---- Playback -------------------------------------------------
            add(entry("audio.wifi_streaming_quality", "Wi-Fi Streaming Quality",
                "Choose audio quality while connected to Wi-Fi", SettingsTab.PLAYBACK, "Streaming",
                Icons.Filled.HighQuality,
                "quality, bitrate, flac, lossless, hires, hi-res, dolby, atmos, mp3, aac, opus, streaming, audio quality, sound quality, wifi, wireless"))
            add(entry("audio.cellular_streaming_quality", "Cellular Streaming Quality",
                "Choose audio quality while using mobile data", SettingsTab.PLAYBACK, "Streaming",
                Icons.Filled.HighQuality,
                "quality, bitrate, streaming, cellular, mobile data, data usage, audio, auto, adaptive"))
            add(entry("audio.auto_upgrade_hi_res", "Playback Start",
                "Fast start, best quality first, or never upgrade", SettingsTab.PLAYBACK, "Streaming",
                Icons.Filled.HighQuality,
                "auto upgrade, hi-res, hires, lossless, flac, upgrade, streaming, quality, start, direct, best quality"))
            add(entry("playback.modules", "Provider Modules & Add-ons",
                "External streaming providers and HTTP add-ons queried during playback", SettingsTab.PLAYBACK, "Streaming",
                Icons.Filled.Extension,
                "modules, extensions, plugins, addons, add-ons, http addon, provider, streaming source, lwp"))
            add(entry("audio.equalizer", "Equalizer",
                "Shape sound across 15 frequency bands with custom curves and presets", SettingsTab.PLAYBACK, "Sound",
                Icons.Filled.GraphicEq,
                "equalizer, eq, 15-band, presets, bass, treble, midrange, bass boost, sound profile, frequencies"))
            add(entry("audio.autoeq", "AutoEQ",
                "Headphone correction from the AutoEQ database, parametric EQ, per-device profiles", SettingsTab.PLAYBACK, "Sound",
                Icons.Filled.Headphones,
                "autoeq, auto eq, headphone, iem, harman, parametric, peq, correction, oratory1990, crinacle, target"))
            add(entry("audio.studio_clarity", "Studio Master Clarity",
                "Crystal-clear open sound, airy detail, and deep clean stereo separation", SettingsTab.PLAYBACK, "Sound",
                Icons.Filled.Waves,
                "clarity, studio master, separation, detail, crisp, highs, vocal clarity, soundstage, dsp"))
            add(entry("audio.show_autoeq_in_volume_panel", "Show AutoEQ in Volume Panel",
                "Show the AutoEQ and parametric EQ shortcut in the Now Playing audio controls sheet", SettingsTab.PLAYBACK, "Sound",
                Icons.Filled.Tune,
                "autoeq, volume panel, audio controls, player sheet, shortcut, equalizer button"))
            add(entry("audio.bit_perfect", "Bit-Perfect Mode",
                "Bypass Android mixer & DSP; stream bit-exact audio to a USB DAC", SettingsTab.PLAYBACK, "Output",
                Icons.Filled.Tune,
                "bit-perfect, bitperfect, direct output, bypass mixer, dac, usb dac, audiophile, lossless, exclusive, sample rate"))
            add(entry("audio.usb_exclusive", "USB Exclusive Output",
                "Direct usbdevfs communication with external USB DACs (Android 10+)", SettingsTab.PLAYBACK, "Output",
                Icons.Filled.Usb,
                "usb, dac, exclusive, usb exclusive, usbdevfs, external dac, hardware volume, headphone amp, dongle"))
            add(entry("audio.internal_direct", "Internal Direct Output",
                "Bypass the Android mixer on the speaker / jack without a USB DAC", SettingsTab.PLAYBACK, "Output",
                Icons.Filled.SpeakerPhone,
                "direct, mixer, bypass, hires direct, aaudio, mmap, exclusive, speaker, jack, internal"))
            add(entry("audio.force_software_volume", "Software Volume (USB DAC)",
                "Scale volume in the app instead of the DAC's hardware volume", SettingsTab.PLAYBACK, "Output",
                Icons.Filled.VolumeUp,
                "volume, software volume, hardware volume, dac"))
            add(entry("audio.loudness_normalization", "Loudness Normalization",
                "ReplayGain volume leveling: Track (-14 LUFS), Album dynamics, or Off", SettingsTab.PLAYBACK, "Output",
                Icons.Filled.VolumeUp,
                "loudness, normalization, volume, replaygain, lufs, track volume, leveling, equal volume, gain"))
            add(entry("audio.system_audio_effects", "System Audio Effects",
                "Allow external equalizers (Dolby Atmos, Wavelet) to process playback", SettingsTab.PLAYBACK, "Output",
                Icons.Filled.VolumeUp,
                "system audio effects, wavelet, dolby system, external eq, audio fx, dsp apps, sound enhancer"))
            add(entry("audio.automix", "AutoMix",
                "Beat-matched, key-locked seamless dual-deck mixing", SettingsTab.PLAYBACK, "Playback",
                Icons.Filled.Bolt,
                "autodj, automix, camelot, beat match, dj blend, transition, crossfade, phrase loop"))
            add(entry("audio.crossfade", "Crossfade",
                "Blend the end of a track smoothly into the next one", SettingsTab.PLAYBACK, "Playback",
                Icons.Filled.GraphicEq,
                "crossfade, fade, transition, overlap, gapless, mix tracks, smooth playback, dj fade"))
            add(entry("audio.crossfade_duration", "Crossfade Duration",
                "Adjust crossfade duration in seconds between songs", SettingsTab.PLAYBACK, "Playback",
                Icons.Filled.Timer,
                "crossfade seconds, crossfade duration, fade length, crossfade time, slider, transition time",
                EntryType.SLIDER))
            add(entry("audio.autoplay_similar", "Autoplay Similar Songs",
                "Keep playing similar songs when the queue ends", SettingsTab.PLAYBACK, "Playback",
                Icons.Filled.GraphicEq,
                "autoplay, radio, similar songs, endless, queue, keep playing, recommendations"))
            add(entry("audio.music_haptics", "Music Haptics",
                "Physical rhythm synchronization via the phone's haptic motor", SettingsTab.PLAYBACK, "Playback",
                Icons.Filled.Vibration,
                "haptics, vibration, rhythm, tactile, haptic motor, feel the music, bass vibration"))

            // ---- Now Playing & Lyrics -------------------------------------
            add(entry("appearance.dynamic_now_playing", "Dynamic Now Playing Screen",
                "Adaptive color background on the now playing screen driven by album art", SettingsTab.NOW_PLAYING, "Player",
                Icons.Filled.Album,
                "dynamic now playing, player screen, adaptive background, album art color, gradient player, cover art"))
            add(entry("appearance.button_now_playing", "Button Now Playing",
                "Floating now playing button instead of the classic bottom card", SettingsTab.NOW_PLAYING, "Player",
                Icons.Filled.PlayCircle,
                "button now playing, floating player, mini player, now playing card, player style"))
            add(entry("appearance.wavy_seekbar", "Fluid Wavy Seekbar",
                "Multi-layer fluid wavy progress slider for track playback", SettingsTab.NOW_PLAYING, "Player",
                Icons.Filled.Waves,
                "wavy seekbar, wave slider, progress bar, fluid seekbar, waveform, scrubber, timeline"))
            add(entry("appearance.rotating_background", "Rotating Background",
                "Animate fluid rotating artwork in the player (disable for static background)", SettingsTab.NOW_PLAYING, "Player",
                Icons.Filled.RestartAlt,
                "rotating background, rotating artwork, fluid background, static background, spinning artwork, player background"))
            add(entry("appearance.music_video", "Music Video",
                "Watch the exact-sync music video of the playing song from the player menu", SettingsTab.NOW_PLAYING, "Player",
                Icons.Filled.SmartDisplay,
                "video, music video, watch video, mv, synced video, youtube video, fullscreen video, now playing video"))
            add(entry("appearance.canvas_enabled", "Canvas Video Backgrounds",
                "Looping visual canvas videos during playback", SettingsTab.NOW_PLAYING, "Canvas",
                Icons.Filled.SmartDisplay,
                "canvas, video, looping video, video background, visualizer, moving artwork"))
            add(entry("appearance.canvas_full_bleed", "Canvas Full Bleed",
                "Expand canvas video to fill the entire screen behind controls", SettingsTab.NOW_PLAYING, "Canvas",
                Icons.Filled.Visibility,
                "canvas full bleed, full screen canvas, edge to edge, immersion, canvas size, fit screen"))
            add(entry("appearance.canvas_gradient_blur", "Gradient Blur on Canvas",
                "Blend the canvas bottom into ambient background gradients", SettingsTab.NOW_PLAYING, "Canvas",
                Icons.Filled.AutoAwesome,
                "canvas gradient blur, canvas blur, gradient blur, hero canvas, ambient blur, edge to edge"))
            add(entry("appearance.canvas_cellular", "Canvas on Cellular Data",
                "Allow canvas video streaming over mobile and cellular networks", SettingsTab.NOW_PLAYING, "Canvas",
                Icons.Filled.CloudDownload,
                "canvas cellular, mobile data, canvas data saver, lte, 5g, stream canvas mobile, data usage"))
            add(entry("spotify.canvas", "Spotify Canvas",
                "Fetch and play Spotify's looping visual videos in the background", SettingsTab.NOW_PLAYING, "Canvas",
                Icons.Filled.PlayCircle,
                "spotify canvas, canvas, video loop, looping video, moving background, spotify"))
            add(entry("spotify.prefer_canvas", "Prefer Spotify Canvas",
                "Ask Spotify first, before Apple Music, Tidal and community artwork", SettingsTab.NOW_PLAYING, "Canvas",
                Icons.Filled.AutoAwesome,
                "spotify canvas, prefer, priority, first, apple music, tidal, artwork provider"))
            add(entry("appearance.lyrics_provider", "Lyrics Provider",
                "Choose the preferred synced lyrics provider", SettingsTab.NOW_PLAYING, "Lyrics",
                Icons.Filled.Lyrics,
                "lyrics provider, lrclib, musixmatch, apple music, spotify lyrics, synced lyrics, lyrics source"))
            add(entry("appearance.lyrics_offset", "Lyrics Sync Offset",
                "Adjust the timing offset in milliseconds to match the vocals", SettingsTab.NOW_PLAYING, "Lyrics",
                Icons.Filled.Timer,
                "lyrics offset, lyrics sync, delay lyrics, early lyrics, ms offset, timing, sync adjustment"))
            add(entry("appearance.lyrics_size", "Lyrics Text Size",
                "Scale the lyrics font size across player and full-screen views", SettingsTab.NOW_PLAYING, "Lyrics",
                Icons.Filled.FormatSize,
                "lyrics size, lyrics font size, font size, text size, resize lyrics, bigger lyrics, smaller lyrics"))
            add(entry("appearance.word_by_word_lyrics", "Word-by-Word Lyrics",
                "Real-time syllable sync and karaoke highlight instead of line-by-line", SettingsTab.NOW_PLAYING, "Lyrics",
                Icons.Filled.Lyrics,
                "word by word, karaoke, syllable sync, lyrics animation, lyrics style, highlight"))
            add(entry("appearance.lyrics_autohide", "Hide Lyrics Buttons",
                "Idle time before the floating lyrics buttons fade out (2-10 s or Never)", SettingsTab.NOW_PLAYING, "Lyrics",
                Icons.Filled.VisibilityOff,
                "hide lyrics buttons, auto hide, idle, fade out, lyrics controls, fullscreen button, timeout"))
            add(entry("appearance.translation_language", "Translation Language",
                "Language the lyrics translate button turns songs into", SettingsTab.NOW_PLAYING, "Lyrics",
                Icons.Filled.Translate,
                "translation language, translate lyrics, lyrics language, romanization, translate to"))
            add(entry("appearance.lyrics_rotating_background", "Lyrics Background Motion",
                "Animate fluid rotating artwork in the lyrics view", SettingsTab.NOW_PLAYING, "Lyrics",
                Icons.Filled.RestartAlt,
                "lyrics background motion, lyrics rotating background, lyrics fluid background, lyrics motion, static background"))
            add(entry("audio.keep_lyrics_screen_awake", "Keep Screen Awake on Lyrics",
                "Prevent the screen from turning off while viewing lyrics", SettingsTab.NOW_PLAYING, "Lyrics",
                Icons.Filled.Lyrics,
                "keep screen awake, keep screen on, lyrics sleep, screen timeout, display awake, lyrics screen"))

            // ---- Appearance ----------------------------------------------
            add(entry("appearance.theme_mode", "Theme Mode",
                "Select System default, Light theme, or Dark theme", SettingsTab.APPEARANCE, "Theme",
                Icons.Filled.Contrast,
                "theme, dark mode, light mode, system theme, night mode, day mode, appearance, color scheme",
                EntryType.THEME_SELECTOR))
            add(entry("appearance.lastwave_theme", "LastWave Signature Theme",
                "Bespoke logo palette with Electric Lime and Obsidian Teal", SettingsTab.APPEARANCE, "Theme",
                Icons.Filled.AutoAwesome,
                "lastwave theme, brand theme, special theme, logo theme, lime, teal, obsidian, brand colors"))
            add(entry("appearance.amoled", "Pure Black AMOLED",
                "Pitch black surfaces for OLED and AMOLED screens", SettingsTab.APPEARANCE, "Theme",
                Icons.Filled.Contrast,
                "amoled, oled, pure black, pitch black, true black, battery saver, dark mode, contrast"))
            add(entry("appearance.dynamic_color", "Dynamic Color (Material You)",
                "Match UI accent colors to the wallpaper and system palette", SettingsTab.APPEARANCE, "Theme",
                Icons.Filled.Palette,
                "dynamic color, material you, monet, wallpaper color, system colors, adaptive color"))
            add(entry("appearance.liquid_glass", "Liquid Glass",
                "Dynamic translucent frosted glass backgrounds and blur effects", SettingsTab.APPEARANCE, "Theme",
                Icons.Filled.BubbleChart,
                "liquid glass, glassmorphism, blur, frosted glass, translucent, acrylic, transparency"))
            add(entry("appearance.accent_preset", "Accent Color & Palette",
                "Pick presets, a monochrome palette, or a custom color wheel", SettingsTab.APPEARANCE, "Accent",
                Icons.Filled.Palette,
                "accent, color, palette, monochrome, color wheel, hex, custom color, theme color, tint",
                EntryType.ACCENT_PICKER))
            add(entry("appearance.app_icon", "App Icon",
                "Choose your home screen and in-app logo", SettingsTab.APPEARANCE, "Icon & text",
                Icons.Filled.Apps,
                "app icon, logo, launcher, icon, theme, home screen icon"))
            add(entry("appearance.app_font", "App Font",
                "Toggle between the system font and the bundled custom typography", SettingsTab.APPEARANCE, "Icon & text",
                Icons.Filled.TextFields,
                "font, typeface, typography, text style, custom font, app font, lettering"))
            add(entry("appearance.bold_font", "Bold Font",
                "Make all text thicker, lyrics included", SettingsTab.APPEARANCE, "Icon & text",
                Icons.Filled.FormatBold,
                "bold, font weight, thick text, heavier text, typography"))
            add(entry("appearance.md3_nav_bar", "Material 3 Navigation Bar",
                "Compact floating dock with the label beside the selected icon", SettingsTab.APPEARANCE, "Layout",
                Icons.Filled.Apps,
                "md3, material 3, navigation bar, bottom bar, nav bar, dock, floating dock"))
            add(entry("appearance.collapsing_nav_bar", "Collapsing Navigation Bar",
                "Shrink the nav bar into an icon pill while scrolling", SettingsTab.APPEARANCE, "Layout",
                Icons.Filled.Apps,
                "collapsing nav bar, shrink nav, icon pill, scrolling, dock"))
            add(entry("appearance.hide_nav_tabs", "Hide Navigation Tabs",
                "Hide tab labels and show icons only", SettingsTab.APPEARANCE, "Layout",
                Icons.Filled.VisibilityOff,
                "hide nav tabs, labels, icons only, bottom navigation, tabs"))
            add(entry("appearance.ui_haptics", "Haptic Feedback",
                "Vibrate on taps, buttons, toggles and gestures", SettingsTab.APPEARANCE, "Layout",
                Icons.Filled.Vibration,
                "haptic, haptics, vibration, vibrate, touch feedback, tap feedback, buzz, tactile"))

            // ---- Library -------------------------------------------------
            add(entry("library.home_sections", "Home Screen Sections",
                "Reorder, show, or hide sections displayed on your Home screen", SettingsTab.LIBRARY, "Home",
                Icons.Filled.Dashboard,
                "home, home sections, feed, customize home, reorder home, layout, hide sections, shelves"))
            add(entry("library.podcasts", "Podcasts",
                "Show or hide podcast shelves, show pages and episode search", SettingsTab.LIBRARY, "Content",
                Icons.Filled.Mic,
                "podcast, podcasts, shows, episodes, hide podcasts, episode search"))
            add(entry("library.recommendations", "Recommendation Provider",
                "Seed recommendations from YouTube Music or Spotify", SettingsTab.LIBRARY, "Content",
                Icons.Filled.AutoAwesome,
                "recommendation, recommendations, algorithm, provider, radio, discover, mix, spotify, youtube"))
            add(entry("library.excluded_songs", "Recommendation Exclusions",
                "Manage songs excluded from recommendations and smart mixes", SettingsTab.LIBRARY, "Content",
                Icons.Filled.RestartAlt,
                "excluded songs, recommendations, blocked tracks, hidden songs, don't recommend, reset exclusions"))
            add(entry("library.import_yt", "Import from YouTube Music",
                "Search, browse, or paste playlist links and IDs to import into the library", SettingsTab.LIBRARY, "Imports",
                Icons.Filled.QueueMusic,
                "import youtube, import playlist, yt import, paste link, copy playlist, transfer youtube"))
            add(entry("library.import_external", "Import from Spotify / Apple Music",
                "Paste a public playlist URL to transfer its tracks", SettingsTab.LIBRARY, "Imports",
                Icons.Filled.QueueMusic,
                "import spotify, import apple music, transfer playlist, playlist converter, public link"))
            add(entry("library.import_file", "Import Playlist from File",
                "Import CSV listening logs or M3U / M3U8 playlists from storage", SettingsTab.LIBRARY, "Imports",
                Icons.Filled.FileDownload,
                "import file, csv, m3u, m3u8, import local, playlist file, open playlist"))

            // ---- Downloads -----------------------------------------------
            add(entry("downloads.manage", "Downloads",
                "Open the download library, folder options and offline organisation", SettingsTab.DOWNLOADS, "Files",
                Icons.Filled.CloudDownload,
                "downloads, offline music, downloaded songs, download folder, manage downloads"))
            add(entry("downloads.storage", "Storage",
                "View cached audio and artwork, and manage offline music", SettingsTab.DOWNLOADS, "Files",
                Icons.Filled.FolderOpen,
                "storage, cache, cached media, clear cache, disk usage, offline music"))
            add(entry("downloads.quality", "Download Quality",
                "Offline download format and bitrate: lossless FLAC or YouTube stream", SettingsTab.DOWNLOADS, "Quality",
                Icons.Filled.CloudDownload,
                "download, offline, storage, bitrate, flac, lossless, cache, download quality, save songs"))
            add(entry("downloads.lyrics", "Download Synced Lyrics",
                "Save .lrc companion files and embed lyrics in downloaded tracks", SettingsTab.DOWNLOADS, "Quality",
                Icons.Filled.Lyrics,
                "lyrics, lrc, download lyrics, embed lyrics, karaoke offline, save lyrics"))

            // ---- Connections ---------------------------------------------
            add(entry("youtube.account", "YouTube Music Account",
                "Connect or manage your Google / YouTube Music account", SettingsTab.CONNECTIONS, "YouTube Music",
                Icons.Filled.CloudSync,
                "youtube, google account, yt music, login, sign in, oauth, connect youtube, disconnect, account"))
            add(entry("youtube.cookie_login", "YouTube Music Cookie Login",
                "Sign in with exported session cookies", SettingsTab.CONNECTIONS, "YouTube Music",
                Icons.Filled.Key,
                "cookie, cookies, sapisid, session, login, youtube, paste cookies"))
            add(entry("youtube.sync", "Two-way Playlist Sync",
                "Keep playlists and favorites continuously synced with YouTube Music", SettingsTab.CONNECTIONS, "YouTube Music",
                Icons.Filled.CloudSync,
                "youtube sync, library sync, cloud sync, auto sync, mirror playlists, 24/7 sync, background sync"))
            add(entry("youtube.channel", "YouTube Channel",
                "Switch between personal and brand channels linked to your account", SettingsTab.CONNECTIONS, "YouTube Music",
                Icons.Filled.SwitchAccount,
                "youtube channel, brand account, channel selector, switch channel, yt profile"))
            add(entry("youtube.select_playlists", "Select Playlists to Sync",
                "Choose specific playlists to mirror with your YouTube Music account", SettingsTab.CONNECTIONS, "YouTube Music",
                Icons.Filled.FormatListBulleted,
                "sync playlists, playlist selection, choose playlists, mirror playlists, synced playlists"))
            add(entry("youtube.shown_playlists", "Show / Hide Account Playlists",
                "Control which playlists from your YouTube account appear in LastWave", SettingsTab.CONNECTIONS, "YouTube Music",
                Icons.Filled.Visibility,
                "show playlists, hide playlists, visibility, account playlists, library visibility"))
            add(entry("youtube.history", "YouTube Music Listening History",
                "Record plays and scrobbles in your official YouTube Music history", SettingsTab.CONNECTIONS, "YouTube Music",
                Icons.Filled.History,
                "youtube history, watch history, yt history sync, scrobble to youtube, listening history"))
            add(entry("spotify.account", "Spotify Account",
                "Connect or manage your Spotify session", SettingsTab.CONNECTIONS, "Spotify",
                Icons.Filled.PlayCircle,
                "spotify, spotify account, connect spotify, sp_dc, spotify login, disconnect spotify, logout"))
            add(entry("spotify.token", "Import sp_dc Token",
                "Paste a session cookie from your browser instead of signing in here", SettingsTab.CONNECTIONS, "Spotify",
                Icons.Filled.Key,
                "sp_dc, cookie, token, paste, import, session, spotify login"))
            add(entry("spotify.live_playlists", "Spotify Playlists in Library",
                "Show your Spotify playlists and Liked Songs in the Playlists library", SettingsTab.CONNECTIONS, "Spotify",
                Icons.Filled.CloudSync,
                "spotify playlists, liked songs, library sync, spotify library, live playlists"))
            add(entry("lastfm.connect", "Last.fm Account",
                "Connect Last.fm for global scrobbling, loved tracks and listening stats", SettingsTab.CONNECTIONS, "Last.fm",
                Icons.Filled.Album,
                "lastfm, last.fm, scrobble, scrobbling, login lastfm, connect lastfm, auth, stats, loved tracks"))
            add(entry("lastfm.api_credentials", "Last.fm API Credentials",
                "Configure a custom Last.fm API key and secret to bypass shared rate limits", SettingsTab.CONNECTIONS, "Last.fm",
                Icons.Filled.Code,
                "lastfm api key, custom api, api secret, developer key, credentials, rate limit"))
            add(entry("discord.presence", "Discord Rich Presence",
                "Show the playing track, quality and cover on your Discord profile", SettingsTab.CONNECTIONS, "Social",
                Icons.Filled.Public,
                "discord, rich presence, discord rpc, now playing discord, discord status, presence"))
            add(entry("social.jam", "LastWave Jam",
                "Listen together in real time, host or join with a code", SettingsTab.CONNECTIONS, "Social",
                Icons.Filled.Group,
                "jam, listen together, group session, party, room code, host, join, collaborative"))

            // ---- System --------------------------------------------------
            add(entry("system.language", "App Language",
                "Change the app language", SettingsTab.SYSTEM, "Language",
                Icons.Filled.Language,
                "language, locale, translation, english, spanish, russian, idioma, sprache, change language"))
            add(entry("system.stop_on_recents", "Stop When Swiped Away",
                "Stop music when LastWave is removed from Recents", SettingsTab.SYSTEM, "Background playback",
                Icons.Filled.StopCircle,
                "recents, swipe away, stop playback, background playback, continue playing, task removed, close app"))
            add(entry("system.battery_optimization", "Battery Optimization",
                "Exempt the app from battery restrictions, sleeping apps and Device Care", SettingsTab.SYSTEM, "Background playback",
                Icons.Filled.Bolt,
                "battery, background, sleep, sleeping apps, device care, power saving, keep alive, doze"))
            add(entry("backup.create", "Backup Settings & Playlists",
                "Export playlists, credentials and preferences to a JSON file", SettingsTab.SYSTEM, "Backup & restore",
                Icons.Filled.Backup,
                "backup, export data, save backup, export json, backup playlists, backup settings"))
            add(entry("backup.restore", "Restore from Backup",
                "Import a previously saved LastWave backup file", SettingsTab.SYSTEM, "Backup & restore",
                Icons.Filled.CloudDownload,
                "restore, import backup, recover data, load backup, restore playlists, json restore"))
            add(entry("backup.clear_all", "Reset App & Clear All Data",
                "Remove credentials, playlists, cached audio and reset all preferences", SettingsTab.SYSTEM, "Data",
                Icons.Filled.Delete,
                "clear all, reset, erase, wipe data, delete everything, factory reset, danger"))

            // ---- About ---------------------------------------------------
            add(entry("about.check_updates", "Check for Updates",
                "Check GitHub releases for the latest version and changelog", SettingsTab.ABOUT, "Support",
                Icons.Filled.CloudDownload,
                "update, check updates, new version, github releases, changelog, download update, upgrade"))
            add(entry("about.telegram_support", "Telegram Updates & Support",
                "Join @clashprojects on Telegram for news, beta builds and support", SettingsTab.ABOUT, "Support",
                Icons.AutoMirrored.Filled.Send,
                "telegram, support, community, chat, clashprojects, help, updates channel, news"))
            add(entry("about.discord_support", "Discord Support",
                "Join the Discord community server for support and feedback", SettingsTab.ABOUT, "Support",
                Icons.Filled.Group,
                "discord, community, chat, support, feedback, server, discussion"))
            add(entry("about.more_apps", "More Apps from Clash Projects",
                "Discover other applications from the same developer", SettingsTab.ABOUT, "Support",
                Icons.Filled.AutoAwesome,
                "more apps, material you app, other apps, clash projects, developer"))
            add(entry("about.diagnostics", "Export Diagnostics & Logs",
                "Export a diagnostic report, crash logs and hardware device info", SettingsTab.ABOUT, "Support",
                Icons.Filled.Code,
                "diagnostics, logs, crash logs, debug info, report issue, bug report, troubleshooting"))
            add(entry("about.source_code", "Open Source Code",
                "View the repository at github.com/Clash-Projects/LastWave-native", SettingsTab.ABOUT, "Support",
                Icons.Filled.Code,
                "source code, github, open source, repository, gpl, license, contribute, git"))
            add(entry("about.desktop_app", "Download Desktop App",
                "View the repository at github.com/Clash-Projects/LastWave-Desktop", SettingsTab.ABOUT, "Support",
                Icons.Filled.Download,
                "desktop, pc, windows, mac, linux, electron, github, clash-projects, download"))
        }
    }
}
