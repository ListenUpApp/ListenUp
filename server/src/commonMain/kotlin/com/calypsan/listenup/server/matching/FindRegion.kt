package com.calypsan.listenup.server.matching

import com.calypsan.listenup.api.dto.match.RegionOrigin
import com.calypsan.listenup.api.metadata.MetadataLocale

/** The store a Find searches, and where that came from. */
internal data class ResolvedRegion(
    val locale: MetadataLocale,
    val origin: RegionOrigin,
)

/** This search's pick, else the library's store, else the server's default. */
internal fun resolveFindRegion(
    override: MetadataLocale?,
    libraryRegion: String?,
): ResolvedRegion =
    when {
        override != null -> {
            ResolvedRegion(override, RegionOrigin.SEARCH_OVERRIDE)
        }

        !libraryRegion.isNullOrBlank() -> {
            ResolvedRegion(
                MetadataLocale(libraryRegion.trim().lowercase()),
                RegionOrigin.LIBRARY,
            )
        }

        else -> {
            ResolvedRegion(MetadataLocale.DEFAULT, RegionOrigin.SERVER_DEFAULT)
        }
    }

private const val MAX_SUGGESTED_STORES = 2
private const val UNITED_STATES = "us"

/** The stores, beside the United States, where a book in each language is most likely listed. */
private val STORES_BY_LANGUAGE: Map<String, List<String>> =
    mapOf(
        "en" to listOf("uk", "au", "ca"),
        "de" to listOf("de"),
        "fr" to listOf("fr", "ca"),
        "es" to listOf("es"),
        "it" to listOf("it"),
        "ja" to listOf("jp"),
        "hi" to listOf("in"),
    )

/** Language names and three-letter codes, as books and catalogues spell them. */
private val LANGUAGE_CODES: Map<String, String> =
    mapOf(
        "english" to "en",
        "eng" to "en",
        "german" to "de",
        "deutsch" to "de",
        "ger" to "de",
        "deu" to "de",
        "french" to "fr",
        "français" to "fr",
        "fre" to "fr",
        "fra" to "fr",
        "spanish" to "es",
        "español" to "es",
        "spa" to "es",
        "italian" to "it",
        "italiano" to "it",
        "ita" to "it",
        "japanese" to "ja",
        "jpn" to "ja",
        "hindi" to "hi",
        "hin" to "hi",
    )

/**
 * Up to two stores worth trying when [current] had nothing — worked out, never probed (probing ten stores
 * would burn the rate limit): the store of the book's existing link at [provider], then the United States,
 * then the stores for the book's language. The current store is never suggested.
 */
internal fun suggestStores(
    current: MetadataLocale,
    subject: FindSubject,
    provider: String,
): List<MetadataLocale> {
    val supported = MetadataLocale.SUPPORTED.map { it.region }.toSet()
    val here = current.region.lowercase()
    return buildList {
        subject.refs
            .firstOrNull { it.provider == provider }
            ?.region
            ?.let(::add)
        add(UNITED_STATES)
        addAll(STORES_BY_LANGUAGE[languageCode(subject.language)].orEmpty())
    }.map { it.lowercase() }
        .filter { it != here && it in supported }
        .distinct()
        .take(MAX_SUGGESTED_STORES)
        .map(::MetadataLocale)
}

private fun languageCode(language: String?): String? {
    val raw =
        language
            ?.trim()
            ?.lowercase()
            ?.substringBefore('-')
            ?.substringBefore('_')
            ?.takeIf { it.isNotEmpty() }
            ?: return null
    return LANGUAGE_CODES[raw] ?: raw.takeIf { it.length == 2 }
}
