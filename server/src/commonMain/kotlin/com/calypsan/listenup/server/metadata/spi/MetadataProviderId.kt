package com.calypsan.listenup.server.metadata.spi

import kotlin.jvm.JvmInline

/**
 * Stable identity of a metadata provider — the value the enrichment router names
 * a provider by in its priority chains.
 *
 * Server-internal: a provider id never crosses the RPC wire (the client picks
 * fields and domains, not provider implementations). The [value] is the operator-
 * facing config token used in `LISTENUP_ENRICHMENT_ORDER` / `_ROUTES`.
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

        /** Every built-in id the router recognizes from config tokens. */
        val known: List<MetadataProviderId> = listOf(AUDIBLE, ITUNES, AUDNEXUS)

        /**
         * The prefix that marks an operator-declared custom provider id — the token
         * `custom:<name>` a `LISTENUP_CUSTOM_PROVIDERS` entry and any route naming it
         * share. Kept lowercase so a route token and the config-derived id always match.
         */
        const val CUSTOM_PREFIX: String = "custom:"

        /**
         * The id for an operator-declared custom provider named [name] — `custom:<name>`,
         * with [name] trimmed and lowercased so config and route tokens resolve identically.
         */
        fun custom(name: String): MetadataProviderId = MetadataProviderId(CUSTOM_PREFIX + name.trim().lowercase())

        /**
         * Resolves a config token (case-insensitive) to an id, or `null` if unrecognized.
         *
         * A built-in token matches one of [known]; a `custom:<name>` token resolves to
         * that operator-declared provider's id (so a route can name a custom source), as
         * long as `<name>` is non-blank.
         */
        fun fromToken(token: String): MetadataProviderId? {
            val trimmed = token.trim()
            known.firstOrNull { it.value.equals(trimmed, ignoreCase = true) }?.let { return it }
            if (trimmed.startsWith(CUSTOM_PREFIX, ignoreCase = true)) {
                val name = trimmed.substring(CUSTOM_PREFIX.length).trim()
                if (name.isNotEmpty()) return custom(name)
            }
            return null
        }
    }
}
