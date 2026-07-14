package com.calypsan.listenup.server.metadata.spi

import kotlin.jvm.JvmInline

/**
 * Stable identity of a metadata provider — the value the enrichment router names
 * a provider by in its priority chains.
 *
 * Server-internal: a provider id never crosses the RPC wire (the client picks
 * fields and domains, not provider implementations). The [value] is the operator-
 * facing config token used in `LISTENUP_ENRICHMENT_ORDER` / `_ROUTES`.
 *
 * [LOCAL] is a reserved pseudo-provider: it does not fetch from any catalog. Its
 * presence in a domain's order means "the book's existing scan-derived value ranks
 * here" — the never-strand anchor that lets local metadata out-rank, or fall
 * behind, a remote catalog per the operator's precedence.
 */
@JvmInline
value class MetadataProviderId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "MetadataProviderId cannot be blank" }
    }

    override fun toString(): String = value

    companion object {
        /** The Audible catalog. */
        val AUDIBLE = MetadataProviderId("audible")

        /** The iTunes / Apple Books catalog. */
        val ITUNES = MetadataProviderId("itunes")

        /** The Audnexus aggregator (contributor profiles, chapters, genres). */
        val AUDNEXUS = MetadataProviderId("audnexus")

        /** Reserved pseudo-provider: the book's existing scan-derived value. */
        val LOCAL = MetadataProviderId("local")

        /** Every id the router recognizes from config tokens. */
        val known: List<MetadataProviderId> = listOf(AUDIBLE, ITUNES, AUDNEXUS, LOCAL)

        /** Resolves a config token (case-insensitive) to a known id, or `null` if unrecognized. */
        fun fromToken(token: String): MetadataProviderId? =
            known.firstOrNull { it.value.equals(token.trim(), ignoreCase = true) }
    }
}
