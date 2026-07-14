package com.calypsan.listenup.api.metadata

import kotlinx.serialization.Serializable

/**
 * A single enrichable field on a book — the finest granularity the metadata
 * router addresses.
 *
 * Every field belongs to exactly one [MetadataDomain] ([domain]); the domain is
 * the default routing key, and a field may override its domain's provider order
 * for surgical control. The flagship case is [AUTHORS] vs [NARRATORS]: both live
 * in [MetadataDomain.CONTRIBUTORS], yet an operator can source authors from one
 * catalog and narrators from another because they are distinct fields.
 *
 * Rides the wire in step 2 (field-level enrichment selection), hence `:contract`
 * and `@Serializable`. [token] is the lowercase operator-facing config token used
 * in `LISTENUP_ENRICHMENT_ROUTES` field clauses (e.g. `description=audible`).
 */
@Serializable
enum class BookField(
    /** The domain this field defaults its provider order to. */
    val domain: MetadataDomain,
) {
    TITLE(MetadataDomain.BOOK_CORE),
    SUBTITLE(MetadataDomain.BOOK_CORE),
    DESCRIPTION(MetadataDomain.BOOK_CORE),
    PUBLISHER(MetadataDomain.BOOK_CORE),
    PUBLISH_YEAR(MetadataDomain.BOOK_CORE),
    LANGUAGE(MetadataDomain.BOOK_CORE),
    AUTHORS(MetadataDomain.CONTRIBUTORS),
    NARRATORS(MetadataDomain.CONTRIBUTORS),
    SERIES(MetadataDomain.SERIES),
    GENRES(MetadataDomain.GENRES),
    MOODS(MetadataDomain.GENRES),
    TAGS(MetadataDomain.GENRES),
    COVER(MetadataDomain.COVER),
    CHAPTERS(MetadataDomain.CHAPTERS),
    ;

    /** Lowercase config token — the field's name lowercased (`publish_year`, `authors`). */
    val token: String get() = name.lowercase()

    companion object {
        /** Resolves a config [token] (case-insensitive) to a field, or `null` if unrecognized. */
        fun fromToken(token: String): BookField? = entries.firstOrNull { it.token.equals(token, ignoreCase = true) }
    }
}
