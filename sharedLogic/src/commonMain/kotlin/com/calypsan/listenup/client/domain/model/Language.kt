package com.calypsan.listenup.client.domain.model

/**
 * ISO 639-1 language data for the language dropdown.
 *
 * Provides code-to-name mappings and a weighted list for UI display
 * where common languages appear first.
 */
object Language {
    /**
     * Common languages that appear at the top of the dropdown.
     * Ordered by likely usage frequency in audiobook collections.
     */
    val commonLanguages: List<String> =
        listOf(
            "en",
            "es",
            "fr",
            "de",
            "it",
            "pt",
            "nl",
            "ru",
            "ja",
            "zh",
            "ko",
            "sv",
            "no",
            "pl",
            "ar",
        )

    /**
     * Complete ISO 639-1 code to display name mapping.
     */
    private val codeToName: Map<String, String> =
        mapOf(
            "aa" to "Afar",
            "ab" to "Abkhazian",
            "ae" to "Avestan",
            "af" to "Afrikaans",
            "ak" to "Akan",
            "am" to "Amharic",
            "an" to "Aragonese",
            "ar" to "Arabic",
            "as" to "Assamese",
            "av" to "Avaric",
            "ay" to "Aymara",
            "az" to "Azerbaijani",
            "ba" to "Bashkir",
            "be" to "Belarusian",
            "bg" to "Bulgarian",
            "bh" to "Bihari",
            "bi" to "Bislama",
            "bm" to "Bambara",
            "bn" to "Bengali",
            "bo" to "Tibetan",
            "br" to "Breton",
            "bs" to "Bosnian",
            "ca" to "Catalan",
            "ce" to "Chechen",
            "ch" to "Chamorro",
            "co" to "Corsican",
            "cr" to "Cree",
            "cs" to "Czech",
            "cu" to "Church Slavic",
            "cv" to "Chuvash",
            "cy" to "Welsh",
            "da" to "Danish",
            "de" to "German",
            "dv" to "Divehi",
            "dz" to "Dzongkha",
            "ee" to "Ewe",
            "el" to "Greek",
            "en" to "English",
            "eo" to "Esperanto",
            "es" to "Spanish",
            "et" to "Estonian",
            "eu" to "Basque",
            "fa" to "Persian",
            "ff" to "Fulah",
            "fi" to "Finnish",
            "fj" to "Fijian",
            "fo" to "Faroese",
            "fr" to "French",
            "fy" to "Western Frisian",
            "ga" to "Irish",
            "gd" to "Scottish Gaelic",
            "gl" to "Galician",
            "gn" to "Guarani",
            "gu" to "Gujarati",
            "gv" to "Manx",
            "ha" to "Hausa",
            "he" to "Hebrew",
            "hi" to "Hindi",
            "ho" to "Hiri Motu",
            "hr" to "Croatian",
            "ht" to "Haitian Creole",
            "hu" to "Hungarian",
            "hy" to "Armenian",
            "hz" to "Herero",
            "ia" to "Interlingua",
            "id" to "Indonesian",
            "ie" to "Interlingue",
            "ig" to "Igbo",
            "ii" to "Sichuan Yi",
            "ik" to "Inupiaq",
            "io" to "Ido",
            "is" to "Icelandic",
            "it" to "Italian",
            "iu" to "Inuktitut",
            "ja" to "Japanese",
            "jv" to "Javanese",
            "ka" to "Georgian",
            "kg" to "Kongo",
            "ki" to "Kikuyu",
            "kj" to "Kuanyama",
            "kk" to "Kazakh",
            "kl" to "Kalaallisut",
            "km" to "Khmer",
            "kn" to "Kannada",
            "ko" to "Korean",
            "kr" to "Kanuri",
            "ks" to "Kashmiri",
            "ku" to "Kurdish",
            "kv" to "Komi",
            "kw" to "Cornish",
            "ky" to "Kyrgyz",
            "la" to "Latin",
            "lb" to "Luxembourgish",
            "lg" to "Ganda",
            "li" to "Limburgish",
            "ln" to "Lingala",
            "lo" to "Lao",
            "lt" to "Lithuanian",
            "lu" to "Luba-Katanga",
            "lv" to "Latvian",
            "mg" to "Malagasy",
            "mh" to "Marshallese",
            "mi" to "Maori",
            "mk" to "Macedonian",
            "ml" to "Malayalam",
            "mn" to "Mongolian",
            "mr" to "Marathi",
            "ms" to "Malay",
            "mt" to "Maltese",
            "my" to "Burmese",
            "na" to "Nauru",
            "nb" to "Norwegian Bokmal",
            "nd" to "North Ndebele",
            "ne" to "Nepali",
            "ng" to "Ndonga",
            "nl" to "Dutch",
            "nn" to "Norwegian Nynorsk",
            "no" to "Norwegian",
            "nr" to "South Ndebele",
            "nv" to "Navajo",
            "ny" to "Chichewa",
            "oc" to "Occitan",
            "oj" to "Ojibwa",
            "om" to "Oromo",
            "or" to "Oriya",
            "os" to "Ossetian",
            "pa" to "Punjabi",
            "pi" to "Pali",
            "pl" to "Polish",
            "ps" to "Pashto",
            "pt" to "Portuguese",
            "qu" to "Quechua",
            "rm" to "Romansh",
            "rn" to "Rundi",
            "ro" to "Romanian",
            "ru" to "Russian",
            "rw" to "Kinyarwanda",
            "sa" to "Sanskrit",
            "sc" to "Sardinian",
            "sd" to "Sindhi",
            "se" to "Northern Sami",
            "sg" to "Sango",
            "si" to "Sinhala",
            "sk" to "Slovak",
            "sl" to "Slovenian",
            "sm" to "Samoan",
            "sn" to "Shona",
            "so" to "Somali",
            "sq" to "Albanian",
            "sr" to "Serbian",
            "ss" to "Swati",
            "st" to "Southern Sotho",
            "su" to "Sundanese",
            "sv" to "Swedish",
            "sw" to "Swahili",
            "ta" to "Tamil",
            "te" to "Telugu",
            "tg" to "Tajik",
            "th" to "Thai",
            "ti" to "Tigrinya",
            "tk" to "Turkmen",
            "tl" to "Tagalog",
            "tn" to "Tswana",
            "to" to "Tonga",
            "tr" to "Turkish",
            "ts" to "Tsonga",
            "tt" to "Tatar",
            "tw" to "Twi",
            "ty" to "Tahitian",
            "ug" to "Uyghur",
            "uk" to "Ukrainian",
            "ur" to "Urdu",
            "uz" to "Uzbek",
            "ve" to "Venda",
            "vi" to "Vietnamese",
            "vo" to "Volapuk",
            "wa" to "Walloon",
            "wo" to "Wolof",
            "xh" to "Xhosa",
            "yi" to "Yiddish",
            "yo" to "Yoruba",
            "za" to "Zhuang",
            "zh" to "Chinese",
            "zu" to "Zulu",
        )

    /**
     * Get display name for a language code.
     * Returns the code itself if unknown.
     */
    fun getDisplayName(code: String): String = codeToName[code.lowercase()] ?: code

    /**
     * Get all languages with common ones first, used for populating the dropdown.
     */
    fun getAllLanguages(): List<LanguageOption> {
        val common =
            commonLanguages.mapNotNull { code ->
                codeToName[code]?.let { name -> LanguageOption(code, name) }
            }
        val remaining =
            codeToName
                .filterKeys { it !in commonLanguages }
                .map { (code, name) -> LanguageOption(code, name) }
                .sortedBy { it.name } // Sort alphabetically by name
        return common + remaining
    }

    /**
     * Filter languages by search query (matches code or name).
     */
    fun filterLanguages(query: String): List<LanguageOption> {
        if (query.isBlank()) return getAllLanguages()
        val lowerQuery = query.lowercase()
        return getAllLanguages().filter { option ->
            option.code.contains(lowerQuery) || option.name.lowercase().contains(lowerQuery)
        }
    }

    /**
     * Check if a code is a valid ISO 639-1 language code.
     */
    fun isValidCode(code: String): Boolean = codeToName.containsKey(code.lowercase())
}

/**
 * One ISO 639-1 language entry rendered in pickers (code + display name).
 */
data class LanguageOption(
    val code: String,
    val name: String,
)
