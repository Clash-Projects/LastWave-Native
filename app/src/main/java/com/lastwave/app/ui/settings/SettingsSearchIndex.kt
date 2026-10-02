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
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.SwitchAccount
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Visibility
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
 * @property id Unique stable identifier for this setting (e.g. "audio.streaming_quality")
 * @property title Primary human-readable title of the setting
 * @property subtitle Descriptive explanation or default state of the setting
 * @property keywords List of curated keywords, synonyms, and technical terms to match against
 * @property icon The icon vector representing this setting
 * @property iconContainer Composable color provider for the icon background
 * @property iconTint Composable color provider for the icon foreground
 * @property parentTab The [SettingsTab] where this setting is located
 * @property section The section header within the parent tab (e.g. "Output & Loudness")
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

/**
 * Static registry of all searchable settings entries across all tabs.
 */
object SettingsSearchIndex {

    val allEntries: List<SettingsEntry> by lazy {
        buildList {
            // ==========================================
            // TAB: AUDIO & PLAYBACK
            // ==========================================
            add(
                SettingsEntry(
                    id = "audio.streaming_quality",
                    title = "Streaming Quality",
                    subtitle = "Lossless, Hi-Res, FLAC, AAC, MP3, 24-bit / 192 kHz, bit depth & sample rate",
                    keywords = listOf(
                        "quality", "bitrate", "flac", "lossless", "hires", "hi-res", "dolby", "atmos",
                        "mp3", "aac", "opus", "streaming", "audio quality", "sound quality", "192khz",
                        "24-bit", "stream", "format"
                    ),
                    icon = Icons.Filled.HighQuality,
                    iconContainer = { MaterialTheme.colorScheme.primaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onPrimaryContainer },
                    parentTab = SettingsTab.AUDIO,
                    section = "Audio & Playback",
                    type = EntryType.ACTION,
                )
            )
            add(
                SettingsEntry(
                    id = "audio.download_quality",
                    title = "Download Quality",
                    subtitle = "Offline download format & bitrate, Lossless FLAC or YouTube stream",
                    keywords = listOf(
                        "download", "offline", "storage", "bitrate", "flac", "lossless", "cache",
                        "download quality", "save songs", "music downloads"
                    ),
                    icon = Icons.Filled.CloudDownload,
                    iconContainer = { MaterialTheme.colorScheme.secondaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onSecondaryContainer },
                    parentTab = SettingsTab.AUDIO,
                    section = "Audio & Playback",
                    type = EntryType.ACTION,
                )
            )
            add(
                SettingsEntry(
                    id = "audio.dolby_atmos",
                    title = "Dolby Atmos / Spatial Audio",
                    subtitle = "Direct multi-channel spatial audio playback or standard stereo lossless",
                    keywords = listOf(
                        "dolby", "atmos", "spatial", "spatial audio", "surround", "3d audio",
                        "multichannel", "multi-channel", "immersive", "binaural", "headphone"
                    ),
                    icon = Icons.Filled.GraphicEq,
                    iconContainer = { MaterialTheme.colorScheme.tertiaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onTertiaryContainer },
                    parentTab = SettingsTab.AUDIO,
                    section = "Audio & Playback",
                    type = EntryType.TOGGLE,
                )
            )
            add(
                SettingsEntry(
                    id = "audio.bit_perfect",
                    title = "Bit-Perfect Mode",
                    subtitle = "Bypass Android mixer & DSP; stream bit-exact audio to USB DAC",
                    keywords = listOf(
                        "bit-perfect", "bitperfect", "direct output", "bypass mixer", "dac", "usb dac",
                        "audiophile", "lossless", "exclusive", "sample rate match", "native playback",
                        "dsp bypass", "hi-res direct"
                    ),
                    icon = Icons.Filled.Tune,
                    iconContainer = { MaterialTheme.colorScheme.primaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onPrimaryContainer },
                    parentTab = SettingsTab.AUDIO,
                    section = "Audio & Playback",
                    type = EntryType.TOGGLE,
                )
            )
            add(
                SettingsEntry(
                    id = "audio.crossfade",
                    title = "Crossfade",
                    subtitle = "Blend the end of a track smoothly into the next one",
                    keywords = listOf(
                        "crossfade", "fade", "transition", "overlap", "gapless", "mix tracks",
                        "smooth playback", "dj fade"
                    ),
                    icon = Icons.Filled.GraphicEq,
                    iconContainer = { MaterialTheme.colorScheme.tertiaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onTertiaryContainer },
                    parentTab = SettingsTab.AUDIO,
                    section = "Audio & Playback",
                    type = EntryType.TOGGLE,
                )
            )
            add(
                SettingsEntry(
                    id = "audio.crossfade_duration",
                    title = "Crossfade Duration",
                    subtitle = "Adjust crossfade duration slider in seconds between songs",
                    keywords = listOf(
                        "crossfade seconds", "crossfade duration", "fade length", "crossfade time",
                        "slider", "seconds", "transition time"
                    ),
                    icon = Icons.Filled.Timer,
                    iconContainer = { MaterialTheme.colorScheme.tertiaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onTertiaryContainer },
                    parentTab = SettingsTab.AUDIO,
                    section = "Audio & Playback",
                    type = EntryType.SLIDER,
                )
            )
            add(
                SettingsEntry(
                    id = "audio.download_lyrics",
                    title = "Download Lyrics",
                    subtitle = "Save .lrc companion files & embed lyrics in downloaded tracks",
                    keywords = listOf(
                        "lyrics", "lrc", "download lyrics", "embed lyrics", "karaoke offline",
                        "save lyrics", "text", "song words"
                    ),
                    icon = Icons.Filled.Lyrics,
                    iconContainer = { MaterialTheme.colorScheme.secondaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onSecondaryContainer },
                    parentTab = SettingsTab.AUDIO,
                    section = "Audio & Playback",
                    type = EntryType.TOGGLE,
                )
            )
            add(
                SettingsEntry(
                    id = "audio.battery_optimization",
                    title = "Background Playback (Battery Optimization)",
                    subtitle = "Exempt app from device battery restrictions, sleeping apps, and Samsung Device Care",
                    keywords = listOf(
                        "battery", "background", "sleep", "sleeping apps", "device care", "power saving",
                        "keep alive", "doze", "stop playing", "playback killed"
                    ),
                    icon = Icons.Filled.Bolt,
                    iconContainer = { MaterialTheme.colorScheme.errorContainer },
                    iconTint = { MaterialTheme.colorScheme.onErrorContainer },
                    parentTab = SettingsTab.AUDIO,
                    section = "Audio & Playback",
                    type = EntryType.ACTION,
                )
            )
            add(
                SettingsEntry(
                    id = "audio.usb_exclusive",
                    title = "USB Exclusive Output",
                    subtitle = "Direct usbdevfs communication with external USB DACs (Android 10+)",
                    keywords = listOf(
                        "usb", "dac", "exclusive", "usb exclusive", "usbdevfs", "external dac",
                        "hardware volume", "headphone amp", "dongle dac"
                    ),
                    icon = Icons.Filled.Usb,
                    iconContainer = { MaterialTheme.colorScheme.primaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onPrimaryContainer },
                    parentTab = SettingsTab.AUDIO,
                    section = "Output & Loudness",
                    type = EntryType.TOGGLE,
                )
            )
            add(
                SettingsEntry(
                    id = "audio.loudness_normalization",
                    title = "Loudness Normalization",
                    subtitle = "ReplayGain volume leveling: Track (-14 LUFS), Album dynamics, or Off",
                    keywords = listOf(
                        "loudness", "normalization", "volume", "replaygain", "lufs", "track volume",
                        "leveling", "equal volume", "album loudness", "gain"
                    ),
                    icon = Icons.Filled.VolumeUp,
                    iconContainer = { MaterialTheme.colorScheme.secondaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onSecondaryContainer },
                    parentTab = SettingsTab.AUDIO,
                    section = "Output & Loudness",
                    type = EntryType.ACTION,
                )
            )
            add(
                SettingsEntry(
                    id = "audio.equalizer",
                    title = "Equalizer",
                    subtitle = "Shape sound across 15 frequency bands with custom curves and presets",
                    keywords = listOf(
                        "equalizer", "eq", "15-band", "presets", "bass", "treble", "midrange",
                        "bass boost", "sound profile", "frequencies", "audio shape"
                    ),
                    icon = Icons.Filled.GraphicEq,
                    iconContainer = { MaterialTheme.colorScheme.tertiaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onTertiaryContainer },
                    parentTab = SettingsTab.AUDIO,
                    section = "Output & Loudness",
                    type = EntryType.ACTION,
                )
            )
            add(
                SettingsEntry(
                    id = "audio.studio_clarity",
                    title = "Studio Master Clarity",
                    subtitle = "Crystal-clear open sound, airy detail, and deep clean stereo separation",
                    keywords = listOf(
                        "clarity", "studio master", "master clarity", "separation", "detail", "crisp",
                        "highs", "vocal clarity", "soundstage", "dsp clarity"
                    ),
                    icon = Icons.Filled.Waves,
                    iconContainer = { MaterialTheme.colorScheme.secondaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onSecondaryContainer },
                    parentTab = SettingsTab.AUDIO,
                    section = "Output & Loudness",
                    type = EntryType.TOGGLE,
                )
            )
            add(
                SettingsEntry(
                    id = "audio.clarity_preset",
                    title = "Clarity Output Preset",
                    subtitle = "Select clarity sound signature: Audiophile, Warm, Bright, or Vocal",
                    keywords = listOf(
                        "clarity preset", "preset", "sound signature", "warm", "bright", "audiophile",
                        "tuning", "vocal preset"
                    ),
                    icon = Icons.Filled.Tune,
                    iconContainer = { MaterialTheme.colorScheme.tertiaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onTertiaryContainer },
                    parentTab = SettingsTab.AUDIO,
                    section = "Output & Loudness",
                    type = EntryType.ACTION,
                )
            )
            add(
                SettingsEntry(
                    id = "audio.clarity_spatial_bypass",
                    title = "Clarity Spatial Bypass",
                    subtitle = "Automatically bypass clarity processing on multichannel and spatial audio sources",
                    keywords = listOf(
                        "clarity bypass", "spatial bypass", "atmos bypass", "multichannel bypass",
                        "dsp bypass", "disable clarity on spatial"
                    ),
                    icon = Icons.Filled.GraphicEq,
                    iconContainer = { MaterialTheme.colorScheme.secondaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onSecondaryContainer },
                    parentTab = SettingsTab.AUDIO,
                    section = "Output & Loudness",
                    type = EntryType.TOGGLE,
                )
            )

            // ==========================================
            // TAB: APPEARANCE & VISUALS
            // ==========================================
            add(
                SettingsEntry(
                    id = "appearance.theme_mode",
                    title = "Theme Mode",
                    subtitle = "Select System default, Light theme, or Dark theme",
                    keywords = listOf(
                        "theme", "dark mode", "light mode", "system theme", "night mode", "day mode",
                        "appearance", "color scheme", "style"
                    ),
                    icon = Icons.Filled.Contrast,
                    iconContainer = { MaterialTheme.colorScheme.primaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onPrimaryContainer },
                    parentTab = SettingsTab.APPEARANCE,
                    section = "Appearance",
                    type = EntryType.THEME_SELECTOR,
                )
            )
            add(
                SettingsEntry(
                    id = "appearance.amoled",
                    title = "Pure Black AMOLED",
                    subtitle = "Pitch black surfaces for OLED and AMOLED screens to save battery",
                    keywords = listOf(
                        "amoled", "oled", "pure black", "pitch black", "true black", "battery saver",
                        "dark mode", "contrast"
                    ),
                    icon = Icons.Filled.Contrast,
                    iconContainer = { MaterialTheme.colorScheme.tertiaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onTertiaryContainer },
                    parentTab = SettingsTab.APPEARANCE,
                    section = "Appearance",
                    type = EntryType.TOGGLE,
                )
            )
            add(
                SettingsEntry(
                    id = "appearance.dynamic_color",
                    title = "Dynamic Color (Material You)",
                    subtitle = "Match UI accent colors to wallpaper and system palette",
                    keywords = listOf(
                        "dynamic color", "material you", "monet", "wallpaper color", "system colors",
                        "adaptive color", "android 12 color"
                    ),
                    icon = Icons.Filled.Palette,
                    iconContainer = { MaterialTheme.colorScheme.primaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onPrimaryContainer },
                    parentTab = SettingsTab.APPEARANCE,
                    section = "Appearance",
                    type = EntryType.TOGGLE,
                )
            )
            add(
                SettingsEntry(
                    id = "appearance.liquid_glass",
                    title = "Liquid Glass",
                    subtitle = "Dynamic translucent frosted glass backgrounds and blur effects",
                    keywords = listOf(
                        "liquid glass", "glassmorphism", "blur", "frosted glass", "translucent",
                        "acrylic", "transparency", "visual effect"
                    ),
                    icon = Icons.Filled.BubbleChart,
                    iconContainer = { MaterialTheme.colorScheme.primaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onPrimaryContainer },
                    parentTab = SettingsTab.APPEARANCE,
                    section = "Appearance",
                    type = EntryType.TOGGLE,
                )
            )
            add(
                SettingsEntry(
                    id = "appearance.dynamic_now_playing",
                    title = "Dynamic Now Playing Screen",
                    subtitle = "Adaptive color background on the now playing screen driven by album art",
                    keywords = listOf(
                        "dynamic now playing", "player screen", "adaptive background", "album art color",
                        "gradient player", "now playing theme", "cover art color"
                    ),
                    icon = Icons.Filled.Album,
                    iconContainer = { MaterialTheme.colorScheme.tertiaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onTertiaryContainer },
                    parentTab = SettingsTab.APPEARANCE,
                    section = "Appearance",
                    type = EntryType.TOGGLE,
                )
            )
            add(
                SettingsEntry(
                    id = "appearance.app_font",
                    title = "App Font",
                    subtitle = "Toggle between system font and custom typography",
                    keywords = listOf(
                        "font", "typeface", "typography", "text style", "custom font", "app font",
                        "lettering", "outfit font"
                    ),
                    icon = Icons.Filled.TextFields,
                    iconContainer = { MaterialTheme.colorScheme.secondaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onSecondaryContainer },
                    parentTab = SettingsTab.APPEARANCE,
                    section = "Appearance",
                    type = EntryType.TOGGLE,
                )
            )
            add(
                SettingsEntry(
                    id = "appearance.home_sections",
                    title = "Home Screen Sections",
                    subtitle = "Reorder, show, or hide sections displayed on your Home screen",
                    keywords = listOf(
                        "home", "home sections", "feed", "customize home", "reorder home",
                        "layout", "hide sections", "shelves", "front page"
                    ),
                    icon = Icons.Filled.Dashboard,
                    iconContainer = { MaterialTheme.colorScheme.primaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onPrimaryContainer },
                    parentTab = SettingsTab.APPEARANCE,
                    section = "Appearance",
                    type = EntryType.ACTION,
                )
            )
            add(
                SettingsEntry(
                    id = "appearance.accent_preset",
                    title = "Accent Color & Palette",
                    subtitle = "Pick presets, monochrome palette, or custom color wheel",
                    keywords = listOf(
                        "accent", "color", "palette", "monochrome", "color wheel", "hex",
                        "custom color", "theme color", "tint"
                    ),
                    icon = Icons.Filled.Palette,
                    iconContainer = { MaterialTheme.colorScheme.primaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onPrimaryContainer },
                    parentTab = SettingsTab.APPEARANCE,
                    section = "Accent Color",
                    type = EntryType.ACCENT_PICKER,
                )
            )
            add(
                SettingsEntry(
                    id = "appearance.lyrics_animation",
                    title = "Lyrics Animation Style",
                    subtitle = "Karaoke glow, sliding, fade, bounce, or Modern full-screen UI",
                    keywords = listOf(
                        "lyrics animation", "karaoke", "lyrics style", "modern lyrics", "lyrics ui",
                        "smooth scroll", "glow lyrics", "lyrics presentation"
                    ),
                    icon = Icons.Filled.Lyrics,
                    iconContainer = { MaterialTheme.colorScheme.tertiaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onTertiaryContainer },
                    parentTab = SettingsTab.APPEARANCE,
                    section = "Experimental & Features",
                    type = EntryType.ACTION,
                )
            )
            add(
                SettingsEntry(
                    id = "appearance.wavy_seekbar",
                    title = "Fluid Wavy Seekbar",
                    subtitle = "Multi-layer fluid wavy progress slider for track playback",
                    keywords = listOf(
                        "wavy seekbar", "wave slider", "progress bar", "fluid seekbar", "waveform",
                        "scrubber", "timeline", "squiggly line"
                    ),
                    icon = Icons.Filled.Waves,
                    iconContainer = { MaterialTheme.colorScheme.secondaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onSecondaryContainer },
                    parentTab = SettingsTab.APPEARANCE,
                    section = "Experimental & Features",
                    type = EntryType.TOGGLE,
                )
            )
            add(
                SettingsEntry(
                    id = "appearance.rotating_background",
                    title = "Rotating Background",
                    subtitle = "Animate fluid rotating artwork in player and lyrics view (disable for static background)",
                    keywords = listOf(
                        "rotating background", "rotating artwork", "fluid background", "static background",
                        "disable rotation", "android 11", "spinning artwork", "player background",
                        "lyrics background", "album rotation", "static artwork"
                    ),
                    icon = Icons.Filled.RestartAlt,
                    iconContainer = { MaterialTheme.colorScheme.primaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onPrimaryContainer },
                    parentTab = SettingsTab.APPEARANCE,
                    section = "Experimental & Features",
                    type = EntryType.TOGGLE,
                )
            )
            add(
                SettingsEntry(
                    id = "appearance.lyrics_provider",
                    title = "Lyrics Provider",
                    subtitle = "Choose preferred synced lyrics provider (LRCLIB, Musixmatch, etc.)",
                    keywords = listOf(
                        "lyrics provider", "lrclib", "musixmatch", "synced lyrics source",
                        "lyrics engine", "lyrics fetcher"
                    ),
                    icon = Icons.Filled.Lyrics,
                    iconContainer = { MaterialTheme.colorScheme.tertiaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onTertiaryContainer },
                    parentTab = SettingsTab.APPEARANCE,
                    section = "Experimental & Features",
                    type = EntryType.ACTION,
                )
            )
            add(
                SettingsEntry(
                    id = "appearance.lyrics_offset",
                    title = "Lyrics Sync Offset",
                    subtitle = "Adjust timing offset in milliseconds to match lyrics with vocals",
                    keywords = listOf(
                        "lyrics offset", "lyrics sync", "delay lyrics", "early lyrics", "ms offset",
                        "timing", "sync adjustment", "vocals match"
                    ),
                    icon = Icons.Filled.Timer,
                    iconContainer = { MaterialTheme.colorScheme.secondaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onSecondaryContainer },
                    parentTab = SettingsTab.APPEARANCE,
                    section = "Experimental & Features",
                    type = EntryType.ACTION,
                )
            )
            add(
                SettingsEntry(
                    id = "appearance.lyrics_size",
                    title = "Lyrics Text Size",
                    subtitle = "Scale and resize lyrics font size across the player and full-screen views",
                    keywords = listOf(
                        "lyrics size", "lyrics font size", "font size", "text size", "resize lyrics",
                        "bigger lyrics", "smaller lyrics", "lyrics scale", "lyrics zoom"
                    ),
                    icon = Icons.Filled.FormatSize,
                    iconContainer = { MaterialTheme.colorScheme.primaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onPrimaryContainer },
                    parentTab = SettingsTab.APPEARANCE,
                    section = "Experimental & Features",
                    type = EntryType.ACTION,
                )
            )
            add(
                SettingsEntry(
                    id = "appearance.system_audio_effects",
                    title = "System Audio Effects",
                    subtitle = "Allow external equalizers (Dolby Atmos, Wavelet) to process playback",
                    keywords = listOf(
                        "system audio effects", "wavelet", "dolby system", "external eq", "audio fx",
                        "dsp apps", "system equalizer", "sound enhancer"
                    ),
                    icon = Icons.Filled.VolumeUp,
                    iconContainer = { MaterialTheme.colorScheme.tertiaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onTertiaryContainer },
                    parentTab = SettingsTab.APPEARANCE,
                    section = "Experimental & Features",
                    type = EntryType.TOGGLE,
                )
            )
            add(
                SettingsEntry(
                    id = "appearance.canvas_enabled",
                    title = "Canvas Video Backgrounds",
                    subtitle = "Looping visual canvas videos during playback like Spotify Canvas",
                    keywords = listOf(
                        "canvas", "video", "looping video", "spotify canvas", "video background",
                        "visualizer", "moving artwork"
                    ),
                    icon = Icons.Filled.SmartDisplay,
                    iconContainer = { MaterialTheme.colorScheme.primaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onPrimaryContainer },
                    parentTab = SettingsTab.APPEARANCE,
                    section = "Canvas",
                    type = EntryType.TOGGLE,
                )
            )
            add(
                SettingsEntry(
                    id = "appearance.canvas_full_bleed",
                    title = "Canvas Full Bleed",
                    subtitle = "Expand canvas video to fill entire screen behind controls",
                    keywords = listOf(
                        "canvas full bleed", "full screen canvas", "edge to edge", "immersion",
                        "canvas size", "fit screen"
                    ),
                    icon = Icons.Filled.Visibility,
                    iconContainer = { MaterialTheme.colorScheme.secondaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onSecondaryContainer },
                    parentTab = SettingsTab.APPEARANCE,
                    section = "Canvas",
                    type = EntryType.TOGGLE,
                )
            )
            add(
                SettingsEntry(
                    id = "appearance.canvas_cellular",
                    title = "Canvas on Cellular Data",
                    subtitle = "Allow canvas video streaming over mobile and cellular networks",
                    keywords = listOf(
                        "canvas cellular", "mobile data", "canvas data saver", "lte", "5g",
                        "stream canvas mobile", "data usage"
                    ),
                    icon = Icons.Filled.CloudDownload,
                    iconContainer = { MaterialTheme.colorScheme.tertiaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onTertiaryContainer },
                    parentTab = SettingsTab.APPEARANCE,
                    section = "Canvas",
                    type = EntryType.TOGGLE,
                )
            )

            // ==========================================
            // TAB: YOUTUBE & SYNC
            // ==========================================
            add(
                SettingsEntry(
                    id = "youtube.account",
                    title = "YouTube Account Connection",
                    subtitle = "Connect or manage your Google / YouTube Music account",
                    keywords = listOf(
                        "youtube", "google account", "yt music", "login", "sign in", "oauth",
                        "connect youtube", "disconnect youtube", "account"
                    ),
                    icon = Icons.Filled.CloudSync,
                    iconContainer = { MaterialTheme.colorScheme.primaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onPrimaryContainer },
                    parentTab = SettingsTab.YOUTUBE,
                    section = "YouTube & Sync",
                    type = EntryType.ACTION,
                )
            )
            add(
                SettingsEntry(
                    id = "youtube.sync",
                    title = "24/7 Library Sync",
                    subtitle = "Keep playlists and favorites continuously synced with YouTube Music",
                    keywords = listOf(
                        "youtube sync", "library sync", "cloud sync", "auto sync", "mirror playlists",
                        "24/7 sync", "background sync", "sync interval"
                    ),
                    icon = Icons.Filled.CloudSync,
                    iconContainer = { MaterialTheme.colorScheme.secondaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onSecondaryContainer },
                    parentTab = SettingsTab.YOUTUBE,
                    section = "YouTube & Sync",
                    type = EntryType.TOGGLE,
                )
            )
            add(
                SettingsEntry(
                    id = "youtube.channel",
                    title = "YouTube Channel",
                    subtitle = "Switch between personal and brand channels linked to your account",
                    keywords = listOf(
                        "youtube channel", "brand account", "channel selector", "switch channel",
                        "yt profile", "sub channel"
                    ),
                    icon = Icons.Filled.SwitchAccount,
                    iconContainer = { MaterialTheme.colorScheme.secondaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onSecondaryContainer },
                    parentTab = SettingsTab.YOUTUBE,
                    section = "YouTube & Sync",
                    type = EntryType.ACTION,
                )
            )
            add(
                SettingsEntry(
                    id = "youtube.select_playlists",
                    title = "Select Playlists to Sync",
                    subtitle = "Choose specific playlists to mirror with your YouTube Music account",
                    keywords = listOf(
                        "sync playlists", "playlist selection", "choose playlists", "mirror playlists",
                        "synced playlists", "filter sync"
                    ),
                    icon = Icons.Filled.FormatListBulleted,
                    iconContainer = { MaterialTheme.colorScheme.tertiaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onTertiaryContainer },
                    parentTab = SettingsTab.YOUTUBE,
                    section = "YouTube & Sync",
                    type = EntryType.ACTION,
                )
            )
            add(
                SettingsEntry(
                    id = "youtube.shown_playlists",
                    title = "Show / Hide Account Playlists",
                    subtitle = "Control which playlists from your YouTube account appear in LastWave",
                    keywords = listOf(
                        "show playlists", "hide playlists", "visibility", "account playlists",
                        "library visibility", "filter playlists"
                    ),
                    icon = Icons.Filled.Visibility,
                    iconContainer = { MaterialTheme.colorScheme.secondaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onSecondaryContainer },
                    parentTab = SettingsTab.YOUTUBE,
                    section = "YouTube & Sync",
                    type = EntryType.ACTION,
                )
            )
            add(
                SettingsEntry(
                    id = "youtube.import",
                    title = "Import from YouTube Music",
                    subtitle = "Search, browse, or paste playlist links & IDs to import into library",
                    keywords = listOf(
                        "import youtube", "import playlist", "paste link", "yt import", "copy playlist",
                        "transfer youtube", "make local"
                    ),
                    icon = Icons.Filled.QueueMusic,
                    iconContainer = { MaterialTheme.colorScheme.primaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onPrimaryContainer },
                    parentTab = SettingsTab.YOUTUBE,
                    section = "YouTube & Sync",
                    type = EntryType.ACTION,
                )
            )
            add(
                SettingsEntry(
                    id = "youtube.history",
                    title = "YouTube Music Listening History",
                    subtitle = "Record plays and scrobbles in your official YouTube Music history",
                    keywords = listOf(
                        "youtube history", "watch history", "yt history sync", "scrobble to youtube",
                        "listening history", "sync plays"
                    ),
                    icon = Icons.Filled.History,
                    iconContainer = { MaterialTheme.colorScheme.tertiaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onTertiaryContainer },
                    parentTab = SettingsTab.YOUTUBE,
                    section = "YouTube & Sync",
                    type = EntryType.TOGGLE,
                )
            )

            // ==========================================
            // TAB: LAST.FM
            // ==========================================
            add(
                SettingsEntry(
                    id = "lastfm.connect",
                    title = "Last.fm Account Connection",
                    subtitle = "Connect Last.fm account for global scrobbling, loved tracks, and listening stats",
                    keywords = listOf(
                        "lastfm", "last.fm", "scrobble", "scrobbling", "login lastfm", "connect lastfm",
                        "auth", "stats", "loved tracks", "profile"
                    ),
                    icon = Icons.Filled.Album,
                    iconContainer = { MaterialTheme.colorScheme.primaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onPrimaryContainer },
                    parentTab = SettingsTab.LAST_FM,
                    section = "Integrations / Scrobbling",
                    type = EntryType.ACTION,
                )
            )
            add(
                SettingsEntry(
                    id = "lastfm.api_credentials",
                    title = "Last.fm Custom API Credentials",
                    subtitle = "Configure custom Last.fm API key and secret to bypass shared rate limits",
                    keywords = listOf(
                        "lastfm api key", "custom api", "api secret", "developer key", "credentials",
                        "rate limit", "last.fm token"
                    ),
                    icon = Icons.Filled.Code,
                    iconContainer = { MaterialTheme.colorScheme.secondaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onSecondaryContainer },
                    parentTab = SettingsTab.LAST_FM,
                    section = "Integrations / Scrobbling",
                    type = EntryType.ACTION,
                )
            )
            add(
                SettingsEntry(
                    id = "discord.presence",
                    title = "Discord Rich Presence",
                    subtitle = "Show the playing track, quality and cover on your Discord profile",
                    keywords = listOf(
                        "discord", "rich presence", "discord rpc", "now playing discord",
                        "discord status", "listening to", "presence", "show what im playing"
                    ),
                    icon = Icons.Filled.Public,
                    iconContainer = { MaterialTheme.colorScheme.secondaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onSecondaryContainer },
                    parentTab = SettingsTab.LAST_FM,
                    section = "Integrations / Scrobbling",
                    type = EntryType.TOGGLE,
                )
            )

            // ==========================================
            // TAB: NOTIFICATION SCROBBLER
            // ==========================================


            // ==========================================
            // TAB: LIBRARY & CONTENT
            // ==========================================
            add(
                SettingsEntry(
                    id = "library.home_sections",
                    title = "Home Layout & Sections",
                    subtitle = "Configure visible feeds, shelves, and quick access on the Home tab",
                    keywords = listOf(
                        "home layout", "home sections", "feed", "organize home", "shelves",
                        "customize library", "home screen layout"
                    ),
                    icon = Icons.Filled.Dashboard,
                    iconContainer = { MaterialTheme.colorScheme.primaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onPrimaryContainer },
                    parentTab = SettingsTab.LIBRARY,
                    section = "Home & Organization",
                    type = EntryType.ACTION,
                )
            )
            add(
                SettingsEntry(
                    id = "library.excluded_songs",
                    title = "Recommendation Exclusions",
                    subtitle = "Manage songs excluded from recommendations and smart mixes",
                    keywords = listOf(
                        "excluded songs", "recommendations", "blocked tracks", "hidden songs",
                        "don't recommend", "exclude from mixes", "reset exclusions"
                    ),
                    icon = Icons.Filled.RestartAlt,
                    iconContainer = { MaterialTheme.colorScheme.secondaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onSecondaryContainer },
                    parentTab = SettingsTab.LIBRARY,
                    section = "Home & Organization",
                    type = EntryType.ACTION,
                )
            )
            add(
                SettingsEntry(
                    id = "library.import_yt",
                    title = "Import from YouTube Music",
                    subtitle = "Search, browse, or paste playlist links & IDs to import into library",
                    keywords = listOf(
                        "import youtube", "import playlist", "yt music import", "paste playlist url",
                        "transfer youtube playlists"
                    ),
                    icon = Icons.Filled.QueueMusic,
                    iconContainer = { MaterialTheme.colorScheme.primaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onPrimaryContainer },
                    parentTab = SettingsTab.LIBRARY,
                    section = "Imports & Addons",
                    type = EntryType.ACTION,
                )
            )
            add(
                SettingsEntry(
                    id = "library.import_external",
                    title = "Import from Spotify / Apple Music",
                    subtitle = "Paste a public playlist URL from Spotify or Apple Music to transfer tracks",
                    keywords = listOf(
                        "import spotify", "import apple music", "transfer playlist", "spotify to lastwave",
                        "apple to lastwave", "playlist converter", "public link"
                    ),
                    icon = Icons.Filled.QueueMusic,
                    iconContainer = { MaterialTheme.colorScheme.secondaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onSecondaryContainer },
                    parentTab = SettingsTab.LIBRARY,
                    section = "Imports & Addons",
                    type = EntryType.ACTION,
                )
            )
            add(
                SettingsEntry(
                    id = "library.import_file",
                    title = "Import Playlists from File (CSV / M3U)",
                    subtitle = "Import playlist files, CSV listening logs, or M3U/M3U8 playlists from storage",
                    keywords = listOf(
                        "import file", "csv", "m3u", "m3u8", "import local", "playlist file",
                        "local file import", "open playlist"
                    ),
                    icon = Icons.Filled.FileDownload,
                    iconContainer = { MaterialTheme.colorScheme.tertiaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onTertiaryContainer },
                    parentTab = SettingsTab.LIBRARY,
                    section = "Imports & Addons",
                    type = EntryType.ACTION,
                )
            )
            add(
                SettingsEntry(
                    id = "library.modules",
                    title = "Extensions & Modules",
                    subtitle = "Browse and configure modular plugins and extra features",
                    keywords = listOf(
                        "modules", "extensions", "plugins", "addons", "modularity", "extra features"
                    ),
                    icon = Icons.Filled.Extension,
                    iconContainer = { MaterialTheme.colorScheme.secondaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onSecondaryContainer },
                    parentTab = SettingsTab.LIBRARY,
                    section = "Imports & Addons",
                    type = EntryType.ACTION,
                )
            )

            // ==========================================
            // TAB: DATA & STORAGE
            // ==========================================
            add(
                SettingsEntry(
                    id = "backup.create",
                    title = "Backup Settings & Playlists",
                    subtitle = "Export your local playlists, credentials, and app preferences to a JSON file",
                    keywords = listOf(
                        "backup", "export data", "save backup", "export json", "backup playlists",
                        "backup settings", "save configuration"
                    ),
                    icon = Icons.Filled.Backup,
                    iconContainer = { MaterialTheme.colorScheme.primaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onPrimaryContainer },
                    parentTab = SettingsTab.DATA_BACKUP,
                    section = "Backup & Restore",
                    type = EntryType.ACTION,
                )
            )
            add(
                SettingsEntry(
                    id = "backup.restore",
                    title = "Restore from Backup",
                    subtitle = "Import a previously saved LastWave backup file to restore settings & playlists",
                    keywords = listOf(
                        "restore", "import backup", "recover data", "load backup", "restore playlists",
                        "restore settings", "json restore"
                    ),
                    icon = Icons.Filled.CloudDownload,
                    iconContainer = { MaterialTheme.colorScheme.primaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onPrimaryContainer },
                    parentTab = SettingsTab.DATA_BACKUP,
                    section = "Backup & Restore",
                    type = EntryType.ACTION,
                )
            )
            add(
                SettingsEntry(
                    id = "backup.clear_all",
                    title = "Reset App & Clear All Data",
                    subtitle = "Remove credentials, playlists, cached audio, and reset all preferences",
                    keywords = listOf(
                        "clear all", "reset", "erase", "wipe data", "delete everything", "factory reset",
                        "danger", "remove data", "clear cache"
                    ),
                    icon = Icons.Filled.Delete,
                    iconContainer = { MaterialTheme.colorScheme.errorContainer },
                    iconTint = { MaterialTheme.colorScheme.onErrorContainer },
                    parentTab = SettingsTab.DATA_BACKUP,
                    section = "Data Management",
                    type = EntryType.ACTION,
                )
            )

            // ==========================================
            // TAB: ABOUT & SYSTEM
            // ==========================================
            add(
                SettingsEntry(
                    id = "about.language",
                    title = "App Language",
                    subtitle = "Change app language (English, Spanish, Russian, and more)",
                    keywords = listOf(
                        "language", "locale", "translation", "english", "spanish", "russian",
                        "idioma", "sprache", "change language"
                    ),
                    icon = Icons.Filled.Language,
                    iconContainer = { MaterialTheme.colorScheme.primaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onPrimaryContainer },
                    parentTab = SettingsTab.ABOUT,
                    section = "Language",
                    type = EntryType.ACTION,
                )
            )
            add(
                SettingsEntry(
                    id = "about.telegram_support",
                    title = "Telegram Updates & Support",
                    subtitle = "Join @clashprojects on Telegram for app news, beta builds, and support",
                    keywords = listOf(
                        "telegram", "support", "community", "chat", "clashprojects", "help",
                        "updates channel", "news"
                    ),
                    icon = Icons.AutoMirrored.Filled.Send,
                    iconContainer = { MaterialTheme.colorScheme.primaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onPrimaryContainer },
                    parentTab = SettingsTab.ABOUT,
                    section = "About & Community",
                    type = EntryType.ACTION,
                )
            )
            add(
                SettingsEntry(
                    id = "about.discord_support",
                    title = "Discord Support",
                    subtitle = "Join our Discord community server for support and feedback",
                    keywords = listOf(
                        "discord", "community", "chat", "support", "feedback", "server", "discussion"
                    ),
                    icon = Icons.Filled.Group,
                    iconContainer = { MaterialTheme.colorScheme.tertiaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onTertiaryContainer },
                    parentTab = SettingsTab.ABOUT,
                    section = "About & Community",
                    type = EntryType.ACTION,
                )
            )
            add(
                SettingsEntry(
                    id = "about.more_apps",
                    title = "More Apps from Clash Projects",
                    subtitle = "Join @MaterialYouApp on Telegram to discover other applications",
                    keywords = listOf(
                        "more apps", "material you app", "other apps", "clash projects", "developer"
                    ),
                    icon = Icons.Filled.AutoAwesome,
                    iconContainer = { MaterialTheme.colorScheme.primaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onPrimaryContainer },
                    parentTab = SettingsTab.ABOUT,
                    section = "About & Community",
                    type = EntryType.ACTION,
                )
            )
            add(
                SettingsEntry(
                    id = "about.diagnostics",
                    title = "Export Diagnostics & Logs",
                    subtitle = "Export diagnostic report, crash logs, and hardware device info",
                    keywords = listOf(
                        "diagnostics", "logs", "crash logs", "debug info", "report issue", "bug report",
                        "troubleshooting", "system info"
                    ),
                    icon = Icons.Filled.Code,
                    iconContainer = { MaterialTheme.colorScheme.secondaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onSecondaryContainer },
                    parentTab = SettingsTab.ABOUT,
                    section = "About & Community",
                    type = EntryType.ACTION,
                )
            )
            add(
                SettingsEntry(
                    id = "about.check_updates",
                    title = "Check for Updates",
                    subtitle = "Check GitHub releases for the latest version and changelog",
                    keywords = listOf(
                        "update", "check updates", "new version", "github releases", "changelog",
                        "download update", "latest release", "upgrade"
                    ),
                    icon = Icons.Filled.CloudDownload,
                    iconContainer = { MaterialTheme.colorScheme.primaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onPrimaryContainer },
                    parentTab = SettingsTab.ABOUT,
                    section = "About & Community",
                    type = EntryType.ACTION,
                )
            )
            add(
                SettingsEntry(
                    id = "about.source_code",
                    title = "Open Source Code",
                    subtitle = "View repository at github.com/Clash-Projects/LastWave-native",
                    keywords = listOf(
                        "source code", "github", "open source", "repository", "gpl", "license",
                        "contribute", "git"
                    ),
                    icon = Icons.Filled.Code,
                    iconContainer = { MaterialTheme.colorScheme.secondaryContainer },
                    iconTint = { MaterialTheme.colorScheme.onSecondaryContainer },
                    parentTab = SettingsTab.ABOUT,
                    section = "About & Community",
                    type = EntryType.ACTION,
                )
            )
        }
    }
}
