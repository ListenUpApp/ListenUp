package com.calypsan.listenup.api.metadata

import kotlinx.serialization.Serializable

/**
 * The coarse metadata concern a provider can satisfy — the KEY the enrichment
 * router prioritizes providers by.
 *
 * A domain groups the fine-grained [BookField]s that are always sourced together
 * (all book-core text fields, both contributor roles, every genre-family field).
 * The operator configures a provider precedence *per domain*
 * ([com.calypsan.listenup.server.metadata.spi.EnrichmentRoutes.domainOrder]); a
 * single [BookField] may then override its domain's order for finer control.
 *
 * Lives in `:contract` — not because the enum itself must cross the wire, but so
 * [BookField.domain] can reference it and a step-2 field-selection UI can group
 * fields by their domain without duplicating this vocabulary. [token] is the
 * stable operator-facing config token (`core`, not `book_core`).
 */
@Serializable
enum class MetadataDomain(
    /** Operator-facing config token used in `LISTENUP_ENRICHMENT_ROUTES` clauses. */
    val token: String,
) {
    BOOK_CORE("core"),
    CONTRIBUTORS("contributors"),
    CHAPTERS("chapters"),
    COVER("cover"),
    SERIES("series"),
    GENRES("genres"),
    CHARACTERS("characters"),
    ;

    companion object {
        /** Resolves a config [token] (case-insensitive) to a domain, or `null` if unrecognized. */
        fun fromToken(token: String): MetadataDomain? =
            entries.firstOrNull { it.token.equals(token, ignoreCase = true) }
    }
}
