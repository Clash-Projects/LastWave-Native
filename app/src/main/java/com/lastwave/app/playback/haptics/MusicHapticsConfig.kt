package com.lastwave.app.playback.haptics

enum class MusicHapticsMode(val label: String, val subtitle: String) {
    WHOLE_SONG(
        label = "Whole Song",
        subtitle = "Full-range taps, bass kicks, and rhythmic textures"
    ),
    BASS_AND_BEATS(
        label = "Bass & Beats",
        subtitle = "Deep sub-bass drops and drum transients (40–130 Hz)"
    ),
    VOCALS_ONLY(
        label = "Vocals Only",
        subtitle = "Follows lead vocal phrasing and emphasis (300–3,200 Hz)"
    );

    companion object {
        fun fromName(name: String?): MusicHapticsMode =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: WHOLE_SONG
    }
}

enum class MusicHapticsIntensity(val label: String, val amplitudeScale: Float, val primitiveScale: Float) {
    LIGHT(label = "Light", amplitudeScale = 0.40f, primitiveScale = 0.35f),
    MEDIUM(label = "Medium", amplitudeScale = 0.70f, primitiveScale = 0.65f),
    STRONG(label = "Strong", amplitudeScale = 1.00f, primitiveScale = 1.00f);

    companion object {
        fun fromName(name: String?): MusicHapticsIntensity =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: MEDIUM
    }
}

enum class MusicHapticsEngineMode(val label: String) {
    AUTO("Automatic (Precision DSP)"),
    DSP_PRECISION("Precision DSP (Recommended)"),
    HARDWARE_NATIVE("Android Native HapticGenerator");

    companion object {
        fun fromName(name: String?): MusicHapticsEngineMode =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: AUTO
    }
}

data class MusicHapticsConfig(
    val enabled: Boolean = false,
    val mode: MusicHapticsMode = MusicHapticsMode.WHOLE_SONG,
    val intensity: MusicHapticsIntensity = MusicHapticsIntensity.MEDIUM,
    val engineMode: MusicHapticsEngineMode = MusicHapticsEngineMode.AUTO,
    val pauseOnBatterySaver: Boolean = true
)
