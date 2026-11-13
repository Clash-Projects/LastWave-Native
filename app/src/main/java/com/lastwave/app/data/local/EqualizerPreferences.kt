package com.lastwave.app.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** The 15 standard ISO 2/3-octave center frequencies (Hz) the equalizer's
 *  band sliders map onto. Low end is bass body, mid range carries vocals,
 *  the top end is air and sparkle. */
val EQ_BAND_FREQS_HZ = intArrayOf(25, 40, 63, 100, 160, 250, 400, 630, 1000, 1600, 2500, 4000, 6300, 10000, 16000)
const val EQ_MAX_GAIN_DB = 8f

/** Compact frequency label under each band slider ("63", "1K", "16K"...). */
fun eqBandLabel(hz: Int): String = if (hz >= 1000) "${hz / 1000}K" else "$hz"

data class EqPreset(
    val name: String,
    /** Gain in dB per band, same order as [EQ_BAND_FREQS_HZ]. */
    val gainsDb: List<Float>,
)

/**
 * Curated, listening-tested 15-band curves. Values are deliberately kept in
 * the ±8 dB range — beyond that Android's audio fx chain starts clipping and
 * smearing transients, so these are the strongest settings that still sound
 * clean at high volume.
 */
object EqualizerPresets {
    const val CUSTOM_NAME = "Custom"

    val FLAT = EqPreset("Default", List(EQ_BAND_FREQS_HZ.size) { 0f })
    /**
     * Crystal-open audiophile reference curve for Studio Master Clarity.
     * Tight, controlled sub-bass depth, a deliberate low-mid dip that
     * removes congestion and mud, natural vocal body, strong presence lift
     * and a tall silky air shelf — the "window opened" spacious sound.
     */
    val STUDIO_MASTER = EqPreset(
        "Studio Master",
        listOf(
            2.6f, 2.8f, 2.2f, 0.6f, -1.8f,
            -2.6f, -1.2f, 0.0f, 1.2f, 2.4f,
            3.6f, 4.0f, 4.2f, 4.5f, 4.8f,
        ),
    )

    val ALL: List<EqPreset> = listOf(
        FLAT,
        STUDIO_MASTER,
        EqPreset("Acoustic", listOf(3.0f, 2.5f, 2.0f, 1.5f, 0.5f, 1.0f, 1.5f, 2.0f, 2.0f, 1.5f, 1.0f, 1.0f, 1.0f, 1.5f, 1.5f)),
        EqPreset("Vocal Clarity", listOf(-1.5f, -1.0f, -0.5f, 0f, 0.5f, 1.0f, 2.0f, 3.0f, 3.5f, 3.0f, 2.5f, 2.0f, 1.5f, 1.5f, 1.0f)),
        EqPreset("Classical", listOf(3.0f, 2.5f, 2.0f, 1.5f, 0.5f, 0f, 0f, 0f, 0.5f, 1.0f, 1.5f, 2.0f, 2.5f, 3.0f, 3.0f)),
        EqPreset("Jazz", listOf(2.5f, 2.0f, 1.5f, 1.0f, 0f, -0.5f, 0f, 0.5f, 1.0f, 1.5f, 2.0f, 2.0f, 2.5f, 2.5f, 2.5f)),
        EqPreset("Rock", listOf(4.0f, 3.5f, 3.0f, 1.5f, 0f, -1.0f, -1.0f, -0.5f, 0.5f, 1.5f, 2.5f, 3.0f, 3.5f, 4.0f, 4.0f)),
        EqPreset("Pop", listOf(-1.0f, -0.5f, 0.0f, 1.0f, 1.5f, 2.0f, 2.0f, 1.5f, 1.0f, 0.5f, 0.5f, 1.0f, 1.5f, 2.0f, 2.5f)),
        EqPreset("Treble Air", listOf(0f, 0f, 0f, -0.5f, -0.5f, -0.5f, 0f, 0.5f, 1.0f, 1.5f, 2.0f, 3.0f, 3.5f, 4.5f, 5.0f)),
        EqPreset("Deep Bass Clean", listOf(5.0f, 4.5f, 3.5f, 2.5f, 1.0f, 0f, -0.5f, -0.5f, 0f, 0f, 0f, 0f, 0.5f, 0.5f, 0.5f)),
        EqPreset("Electronic", listOf(4.5f, 4.0f, 3.0f, 2.0f, 0.5f, -0.5f, 0f, 1.0f, 2.0f, 2.5f, 2.5f, 2.5f, 3.0f, 3.5f, 4.0f)),
        EqPreset("R&B", listOf(5.0f, 4.5f, 3.5f, 2.0f, 1.0f, -0.5f, -1.0f, -0.5f, 0.5f, 1.5f, 2.0f, 2.5f, 3.0f, 3.0f, 3.0f)),
    )

    fun byName(name: String): EqPreset? = ALL.firstOrNull { it.name.equals(name, ignoreCase = true) }
}

data class EqualizerSettings(
    val enabled: Boolean = false,
    val presetName: String = EqualizerPresets.FLAT.name,
    /** Per-band gain in dB, index-aligned with [EQ_BAND_FREQS_HZ]. */
    val gainsDb: List<Float> = EqualizerPresets.FLAT.gainsDb,
    /** The listener's own named presets (at most [MAX_USER_EQ_PRESETS]), shared by every output. */
    val userPresets: List<EqPreset> = emptyList(),
) {
    fun isUserPreset(name: String): Boolean = userPresets.any { it.name.equals(name, ignoreCase = true) }
}

const val MAX_USER_EQ_PRESETS = 20
const val MAX_EQ_PRESET_NAME_LENGTH = 30

/** Why a preset name was refused, or null when it can be used. */
fun eqPresetNameProblem(name: String, settings: EqualizerSettings, renaming: String? = null): String? {
    val clean = cleanEqPresetName(name)
    return when {
        clean.isEmpty() -> "Enter a name"
        clean.length > MAX_EQ_PRESET_NAME_LENGTH -> "Keep it under $MAX_EQ_PRESET_NAME_LENGTH characters"
        clean.equals(EqualizerPresets.CUSTOM_NAME, ignoreCase = true) ||
            EqualizerPresets.byName(clean) != null -> "That name belongs to a built-in preset"
        settings.userPresets.any {
            it.name.equals(clean, ignoreCase = true) && !it.name.equals(renaming, ignoreCase = true)
        } -> "You already have a preset with that name"
        else -> null
    }
}

/** Names are stored one per line, tab-separated from their gains. */
fun cleanEqPresetName(name: String): String = name.replace(Regex("[\\t\\r\\n]"), " ").trim()

/** The next free "Custom N" name, for the save dialog's suggestion. */
fun nextEqPresetName(settings: EqualizerSettings): String =
    generateSequence(1) { it + 1 }
        .map { "Custom $it" }
        .first { candidate -> !settings.isUserPreset(candidate) }

@Singleton
class EqualizerPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private object Keys {
        val ENABLED = booleanPreferencesKey("lw_eq_enabled")
        val PRESET_NAME = stringPreferencesKey("lw_eq_preset")
        val GAINS_DB = stringPreferencesKey("lw_eq_gains")
        /** "name\tg1,g2,...\n" per user preset, in the order they were created. */
        val USER_PRESETS = stringPreferencesKey("lw_eq_user_presets")
    }

    val settings: Flow<EqualizerSettings> = dataStore.data
        .recoverPreferences("EqualizerPreferences")
        .map { p ->
            val storedGains = decodeGains(p.readSafely(Keys.GAINS_DB))
            val userPresets = decodeUserPresets(p.readSafely(Keys.USER_PRESETS))
            // The stored name is trusted as-is: every writer of GAINS_DB also
            // updates PRESET_NAME (Custom on manual band edits), so the pair can't
            // drift apart. Unknown names fall back to Default.
            val resolvedName = p.readSafely(Keys.PRESET_NAME)
                ?.let { name ->
                    val known = name == EqualizerPresets.CUSTOM_NAME ||
                        EqualizerPresets.byName(name) != null ||
                        userPresets.any { it.name == name }
                    if (known) name else null
                }
                ?: EqualizerPresets.FLAT.name
            val gains = storedGains
                ?: EqualizerPresets.byName(resolvedName)?.gainsDb
                ?: userPresets.firstOrNull { it.name == resolvedName }?.gainsDb
                ?: EqualizerPresets.FLAT.gainsDb
            EqualizerSettings(
                enabled = p.readSafely(Keys.ENABLED) ?: false,
                presetName = resolvedName,
                gainsDb = gains,
                userPresets = userPresets,
            )
        }

    suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.ENABLED] = enabled }
    }

    suspend fun applyPreset(preset: EqPreset) {
        dataStore.edit {
            it[Keys.PRESET_NAME] = preset.name
            it[Keys.GAINS_DB] = encodeGains(preset.gainsDb)
            it[Keys.ENABLED] = true
        }
    }

    /**
     * A manual band edit. On one of the listener's own presets it is saved
     * straight into that preset; on a built-in one the curve becomes Custom.
     */
    suspend fun setBandGain(bandIndex: Int, gainDb: Float) {
        if (bandIndex !in EQ_BAND_FREQS_HZ.indices) return
        dataStore.edit {
            val current = decodeGains(it.readSafely(Keys.GAINS_DB)) ?: EqualizerPresets.FLAT.gainsDb
            val safeGain = if (gainDb.isFinite()) gainDb.coerceIn(-EQ_MAX_GAIN_DB, EQ_MAX_GAIN_DB) else 0f
            val next = current.toMutableList().also { list -> list[bandIndex] = safeGain }
            val activeName = it.readSafely(Keys.PRESET_NAME)
            val userPresets = decodeUserPresets(it.readSafely(Keys.USER_PRESETS))
            if (activeName != null && userPresets.any { preset -> preset.name == activeName }) {
                it[Keys.USER_PRESETS] = encodeUserPresets(
                    userPresets.map { preset -> if (preset.name == activeName) preset.copy(gainsDb = next) else preset },
                )
            } else {
                it[Keys.PRESET_NAME] = EqualizerPresets.CUSTOM_NAME
            }
            it[Keys.GAINS_DB] = encodeGains(next)
        }
    }

    /**
     * Saves the current curve as a new named preset and makes it active.
     * Returns false when the name is taken/invalid or the limit is reached.
     */
    suspend fun saveUserPreset(name: String): Boolean {
        var saved = false
        dataStore.edit {
            val clean = cleanEqPresetName(name)
            val userPresets = decodeUserPresets(it.readSafely(Keys.USER_PRESETS))
            val snapshot = EqualizerSettings(userPresets = userPresets)
            if (userPresets.size >= MAX_USER_EQ_PRESETS || eqPresetNameProblem(clean, snapshot) != null) return@edit
            val gains = decodeGains(it.readSafely(Keys.GAINS_DB)) ?: EqualizerPresets.FLAT.gainsDb
            it[Keys.USER_PRESETS] = encodeUserPresets(userPresets + EqPreset(clean, gains))
            it[Keys.PRESET_NAME] = clean
            it[Keys.GAINS_DB] = encodeGains(gains)
            saved = true
        }
        return saved
    }

    suspend fun renameUserPreset(oldName: String, newName: String): Boolean {
        var renamed = false
        dataStore.edit {
            val clean = cleanEqPresetName(newName)
            val userPresets = decodeUserPresets(it.readSafely(Keys.USER_PRESETS))
            if (userPresets.none { preset -> preset.name == oldName }) return@edit
            val snapshot = EqualizerSettings(userPresets = userPresets)
            if (eqPresetNameProblem(clean, snapshot, renaming = oldName) != null) return@edit
            it[Keys.USER_PRESETS] = encodeUserPresets(
                userPresets.map { preset -> if (preset.name == oldName) preset.copy(name = clean) else preset },
            )
            if (it.readSafely(Keys.PRESET_NAME) == oldName) it[Keys.PRESET_NAME] = clean
            renamed = true
        }
        return renamed
    }

    /** Deleting the active preset keeps its curve playing, as Custom. */
    suspend fun deleteUserPreset(name: String) {
        dataStore.edit {
            val userPresets = decodeUserPresets(it.readSafely(Keys.USER_PRESETS))
            it[Keys.USER_PRESETS] = encodeUserPresets(userPresets.filterNot { preset -> preset.name == name })
            if (it.readSafely(Keys.PRESET_NAME) == name) it[Keys.PRESET_NAME] = EqualizerPresets.CUSTOM_NAME
        }
    }

    suspend fun reset() {
        applyPreset(EqualizerPresets.FLAT)
    }

    private fun encodeGains(gains: List<Float>): String =
        gains.joinToString(",") { gain ->
            "%.1f".format(java.util.Locale.ROOT, if (gain.isFinite()) gain.coerceIn(-EQ_MAX_GAIN_DB, EQ_MAX_GAIN_DB) else 0f)
        }

    private fun decodeGains(raw: String?): List<Float>? =
        raw?.split(',')?.mapNotNull(String::toFloatOrNull)
            ?.takeIf { it.size == EQ_BAND_FREQS_HZ.size }
            ?.map { gain -> if (gain.isFinite()) gain.coerceIn(-EQ_MAX_GAIN_DB, EQ_MAX_GAIN_DB) else 0f }

    private fun encodeUserPresets(presets: List<EqPreset>): String =
        presets.take(MAX_USER_EQ_PRESETS).joinToString("\n") { "${cleanEqPresetName(it.name)}\t${encodeGains(it.gainsDb)}" }

    /** Malformed lines are skipped, never allowed to drop the rest. */
    private fun decodeUserPresets(raw: String?): List<EqPreset> =
        raw.orEmpty().lineSequence()
            .mapNotNull { line ->
                val name = line.substringBefore('\t', missingDelimiterValue = "").trim()
                val gains = decodeGains(line.substringAfter('\t', missingDelimiterValue = ""))
                if (name.isEmpty() || gains == null) null else EqPreset(name, gains)
            }
            .distinctBy { it.name.lowercase() }
            .take(MAX_USER_EQ_PRESETS)
            .toList()
}
