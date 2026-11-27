package com.lastwave.app.data.lyrics

import java.util.Locale

data class TranslationLanguage(
    val code: String,
    val fallbackName: String,
)

private val languagePairs = arrayOf(
    "af" to "Afrikaans",
    "sq" to "Albanian",
    "am" to "Amharic",
    "ar" to "Arabic",
    "hy" to "Armenian",
    "as" to "Assamese",
    "ay" to "Aymara",
    "az" to "Azerbaijani",
    "bm" to "Bambara",
    "eu" to "Basque",
    "be" to "Belarusian",
    "bn" to "Bengali",
    "bho" to "Bhojpuri",
    "bs" to "Bosnian",
    "bg" to "Bulgarian",
    "ca" to "Catalan",
    "ceb" to "Cebuano",
    "ny" to "Chichewa",
    "zh-CN" to "Chinese (Simplified)",
    "zh-TW" to "Chinese (Traditional)",
    "co" to "Corsican",
    "hr" to "Croatian",
    "cs" to "Czech",
    "da" to "Danish",
    "dv" to "Dhivehi",
    "doi" to "Dogri",
    "nl" to "Dutch",
    "en" to "English",
    "eo" to "Esperanto",
    "et" to "Estonian",
    "ee" to "Ewe",
    "tl" to "Filipino",
    "fi" to "Finnish",
    "fr" to "French",
    "fy" to "Frisian",
    "gl" to "Galician",
    "ka" to "Georgian",
    "de" to "German",
    "el" to "Greek",
    "gn" to "Guarani",
    "gu" to "Gujarati",
    "ht" to "Haitian Creole",
    "ha" to "Hausa",
    "haw" to "Hawaiian",
    "iw" to "Hebrew",
    "hi" to "Hindi",
    "hmn" to "Hmong",
    "hu" to "Hungarian",
    "is" to "Icelandic",
    "ig" to "Igbo",
    "ilo" to "Ilocano",
    "id" to "Indonesian",
    "ga" to "Irish",
    "it" to "Italian",
    "ja" to "Japanese",
    "jw" to "Javanese",
    "kn" to "Kannada",
    "kk" to "Kazakh",
    "km" to "Khmer",
    "rw" to "Kinyarwanda",
    "gom" to "Konkani",
    "ko" to "Korean",
    "kri" to "Krio",
    "ku" to "Kurdish (Kurmanji)",
    "ckb" to "Kurdish (Sorani)",
    "ky" to "Kyrgyz",
    "lo" to "Lao",
    "la" to "Latin",
    "lv" to "Latvian",
    "ln" to "Lingala",
    "lt" to "Lithuanian",
    "lg" to "Luganda",
    "lb" to "Luxembourgish",
    "mk" to "Macedonian",
    "mai" to "Maithili",
    "mg" to "Malagasy",
    "ms" to "Malay",
    "ml" to "Malayalam",
    "mt" to "Maltese",
    "mi" to "Maori",
    "mr" to "Marathi",
    "mni-Mtei" to "Meiteilon (Manipuri)",
    "lus" to "Mizo",
    "mn" to "Mongolian",
    "my" to "Myanmar (Burmese)",
    "ne" to "Nepali",
    "no" to "Norwegian",
    "or" to "Odia (Oriya)",
    "om" to "Oromo",
    "ps" to "Pashto",
    "fa" to "Persian",
    "pl" to "Polish",
    "pt" to "Portuguese",
    "pa" to "Punjabi",
    "qu" to "Quechua",
    "ro" to "Romanian",
    "ru" to "Russian",
    "sm" to "Samoan",
    "sa" to "Sanskrit",
    "gd" to "Scots Gaelic",
    "nso" to "Sepedi",
    "sr" to "Serbian",
    "st" to "Sesotho",
    "sn" to "Shona",
    "sd" to "Sindhi",
    "si" to "Sinhala",
    "sk" to "Slovak",
    "sl" to "Slovenian",
    "so" to "Somali",
    "es" to "Spanish",
    "su" to "Sundanese",
    "sw" to "Swahili",
    "sv" to "Swedish",
    "tg" to "Tajik",
    "ta" to "Tamil",
    "tt" to "Tatar",
    "te" to "Telugu",
    "th" to "Thai",
    "ti" to "Tigrinya",
    "ts" to "Tsonga",
    "tr" to "Turkish",
    "tk" to "Turkmen",
    "ak" to "Twi",
    "uk" to "Ukrainian",
    "ur" to "Urdu",
    "ug" to "Uyghur",
    "uz" to "Uzbek",
    "vi" to "Vietnamese",
    "cy" to "Welsh",
    "xh" to "Xhosa",
    "yi" to "Yiddish",
    "yo" to "Yoruba",
    "zu" to "Zulu",
)

val TRANSLATION_LANGUAGES: List<TranslationLanguage> =
    languagePairs.map { (tag, label) -> TranslationLanguage(tag, label) }

private val lookupByTag: Map<String, TranslationLanguage> =
    TRANSLATION_LANGUAGES.associateBy { item -> item.code.lowercase(Locale.ROOT) }

fun translationLanguageName(code: String, inLocale: Locale): String {
    val item = lookupByTag[code.lowercase(Locale.ROOT)]
    val localized = when {
        code.contains('-') -> ""
        else -> Locale.forLanguageTag(code).getDisplayName(inLocale)
    }
    val label = if (localized.isNotBlank() && !localized.equals(code, ignoreCase = true)) {
        localized
    } else {
        item?.fallbackName ?: code
    }
    return label.replaceFirstChar { ch ->
        if (ch.isLowerCase()) ch.titlecase(inLocale) else ch.toString()
    }
}

fun translationWireTarget(configured: String): String {
    val explicit = configured.trim()
    if (explicit.isNotEmpty()) return explicit

    val activeLocale = Locale.getDefault()
    val base = activeLocale.language.lowercase(Locale.ROOT)
    return when {
        base == "zh" -> when (activeLocale.country.uppercase(Locale.ROOT)) {
            "TW", "HK", "MO" -> "zh-TW"
            else -> "zh-CN"
        }
        base.isNotEmpty() -> base
        else -> "en"
    }
}
