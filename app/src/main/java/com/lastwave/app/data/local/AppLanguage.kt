package com.lastwave.app.data.local

import java.util.Locale

/**
 * In-app language selection (Settings -> Language).
 *
 * The [tag] is what is persisted in DataStore and in the synchronous
 * SharedPreferences mirror (see [SYNC_FILE]) that `attachBaseContext`
 * reads before DataStore is available.
 *
 * Only fully-translated languages are listed here. To add a new language:
 * 1. Add an entry with its BCP-47 tag.
 * 2. Add res/values-<qualifier>/strings.xml with translations.
 * 3. Add the locale to res/xml/locales_config.xml.
 */
enum class AppLanguage(val tag: String) {
    SYSTEM("system"),
    ENGLISH("en"),
    TURKISH("tr"),
    CHINESE_SIMPLIFIED("zh-CN"),
    // Wave 1: largest Play Store music-app install bases.
    RUSSIAN("ru"),
    PORTUGUESE_BRAZIL("pt-BR"),
    SPANISH("es"),
    INDONESIAN("id"),
    HINDI("hi"),
    // Wave 2: audiophile/Hi-Res markets + MENA (Arabic is RTL).
    GERMAN("de"),
    FRENCH("fr"),
    ITALIAN("it"),
    JAPANESE("ja"),
    KOREAN("ko"),
    ARABIC("ar"),
    // Wave 3: Southeast Asia.
    VIETNAMESE("vi");

    companion object {
        /** Synchronous mirror read by `attachBaseContext` (DataStore is async). */
        const val SYNC_FILE = "lastwave_locale_prefs"
        const val SYNC_KEY = "app_language_tag"

        /** All user-selectable options in selector order. */
        val SELECTABLE: List<AppLanguage> = entries.toList()

        /**
         * Normalizes any stored/system tag to a supported [AppLanguage].
         * Never throws, never returns an untranslatable language:
         * - trims, lowercases, unifies '_'/'-' separators, ignores case
         * - legacy values: "zh-Hans" (stored by earlier builds) -> Simplified,
         *   bare "in" -> Indonesian
         * - base-language fallback: "de-AT" -> German, "pt" -> Portuguese (Brazil)
         * - unsupported (e.g. Traditional Chinese, Hebrew) -> SYSTEM rather
         *   than showing the wrong script to the reader
         */
        fun fromTag(raw: String?): AppLanguage {
            if (raw.isNullOrBlank()) return SYSTEM
            val cleaned = raw.trim().replace('_', '-')
            entries.firstOrNull { it.tag.equals(cleaned, ignoreCase = true) }
                ?.let { return it }
            when (cleaned.lowercase()) {
                "zh-hans", "zh-cn", "zh-sg" -> return CHINESE_SIMPLIFIED
                "in" -> return INDONESIAN
                "zh-hant", "zh-tw", "zh-hk", "zh-mo" -> return SYSTEM
                "iw", "he", "ji", "yi" -> return SYSTEM
            }
            val base = cleaned.substringBefore('-').lowercase()
            entries.firstOrNull { it.tag.lowercase() == base }
                ?.let { return it }
            if (base == "pt") return PORTUGUESE_BRAZIL
            return SYSTEM
        }
    }
}

/**
 * Native display name for the selector. Intentionally NOT localized:
 * a user looking for their language should always see its native name.
 */
fun AppLanguage.nativeDisplayName(): String = when (this) {
    AppLanguage.SYSTEM -> "System default"
    AppLanguage.ENGLISH -> "English"
    AppLanguage.TURKISH -> "Türkçe"
    AppLanguage.CHINESE_SIMPLIFIED -> "简体中文"
    AppLanguage.RUSSIAN -> "Русский"
    AppLanguage.PORTUGUESE_BRAZIL -> "Português (Brasil)"
    AppLanguage.SPANISH -> "Español"
    AppLanguage.INDONESIAN -> "Bahasa Indonesia"
    AppLanguage.HINDI -> "हिन्दी"
    AppLanguage.GERMAN -> "Deutsch"
    AppLanguage.FRENCH -> "Français"
    AppLanguage.ITALIAN -> "Italiano"
    AppLanguage.JAPANESE -> "日本語"
    AppLanguage.KOREAN -> "한국어"
    AppLanguage.ARABIC -> "العربية"
    AppLanguage.VIETNAMESE -> "Tiếng Việt"
}

/** Short subtitle shown under the selected language row. */
fun AppLanguage.selectorSubtitle(): String = when (this) {
    AppLanguage.SYSTEM -> "Follow device language"
    AppLanguage.ENGLISH -> "English"
    AppLanguage.TURKISH -> "Türkçe"
    AppLanguage.CHINESE_SIMPLIFIED -> "简体中文"
    AppLanguage.RUSSIAN -> "Русский"
    AppLanguage.PORTUGUESE_BRAZIL -> "Português (Brasil)"
    AppLanguage.SPANISH -> "Español"
    AppLanguage.INDONESIAN -> "Bahasa Indonesia"
    AppLanguage.HINDI -> "हिन्दी"
    AppLanguage.GERMAN -> "Deutsch"
    AppLanguage.FRENCH -> "Français"
    AppLanguage.ITALIAN -> "Italiano"
    AppLanguage.JAPANESE -> "日本語"
    AppLanguage.KOREAN -> "한국어"
    AppLanguage.ARABIC -> "العربية"
    AppLanguage.VIETNAMESE -> "Tiếng Việt"
}

/**
 * Locale for Configuration wrapping. Indonesian uses legacy "in" so it
 * matches res/values-in exactly (BCP-47 "id" does not match that qualifier
 * on all platform versions).
 */
fun AppLanguage.appLocale(): Locale = when (this) {
    AppLanguage.SYSTEM -> Locale.getDefault()
    AppLanguage.INDONESIAN -> Locale("in")
    else -> Locale.forLanguageTag(tag)
}
