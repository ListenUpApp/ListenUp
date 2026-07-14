package com.calypsan.listenup.server.metadata.custom

import com.calypsan.listenup.api.metadata.MetadataDomain
import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId

private val logger = loggerFor<CustomProviderSpec>()

/**
 * One operator-declared custom metadata provider, parsed from a `LISTENUP_CUSTOM_PROVIDERS`
 * entry.
 *
 * A custom provider lets an operator point ListenUp at *their own* metadata HTTP endpoint —
 * a community character wiki, a private catalog, a scraper they host — and have it
 * participate in enrichment exactly like the built-in Audible/iTunes/Audnexus providers.
 * It is the extensibility seam and, because no built-in source has per-book characters, the
 * only way to fill the [MetadataDomain.CHARACTERS] slot today.
 *
 * [id] is `custom:<name>` (see [MetadataProviderId.custom]) — the token a route names it by
 * in `LISTENUP_ENRICHMENT_ROUTES` (e.g. `characters=custom:mysource`). [capabilities] are the
 * metadata domains this endpoint advertises; a domain the operator did *not* declare is an
 * immediate honest miss (no HTTP), so a lean endpoint never gets asked for what it can't serve.
 */
data class CustomProviderSpec(
    /** The provider id — `custom:<name>`, the token routes name it by. */
    val id: MetadataProviderId,
    /** The operator-chosen short name, lowercased. */
    val name: String,
    /** The endpoint base URL, trailing slash trimmed (paths are appended as `/book`, `/characters`, …). */
    val baseUrl: String,
    /** The metadata domains this endpoint advertises; undeclared domains are immediate misses. */
    val capabilities: Set<MetadataDomain>,
) {
    companion object {
        /**
         * The metadata domains a custom provider can serve over the [CustomMetadataClient]
         * JSON contract. A declared capability outside this set is dropped: the client has no
         * endpoint for contributors, chapters, or book search.
         */
        val SUPPORTED_CAPABILITIES: Set<MetadataDomain> =
            setOf(
                MetadataDomain.BOOK_CORE,
                MetadataDomain.CHARACTERS,
                MetadataDomain.COVER,
                MetadataDomain.GENRES,
                MetadataDomain.SERIES,
            )

        /**
         * Parses `LISTENUP_CUSTOM_PROVIDERS` into provider specs, never-strand.
         *
         * ### Format
         * Semicolon-separated entries, each `name=baseUrl` with an optional `|caps` suffix:
         *
         * ```
         * mysource=https://meta.example.com|characters,cover; catalog=https://x.y
         * ```
         *
         * - `name` becomes the id `custom:<name>` (lowercased); it is what a route names.
         * - `baseUrl` is the endpoint root; the client appends `/book`, `/characters`, `/cover`,
         *   `/genres`, `/series`.
         * - `|caps` is an optional comma-separated list of domain tokens
         *   (`core`, `characters`, `cover`, `genres`, `series`) the endpoint serves. **Omit it to
         *   advertise every [SUPPORTED_CAPABILITIES] domain** (the endpoint 404s what it lacks).
         *
         * ### Never-strand
         * A misconfigured env must never strand enrichment. An entry with no `=`, a blank name or
         * base URL, or a `|caps` clause that names no supported domain is logged once and skipped;
         * an unknown/unsupported cap token is dropped with a warning; a duplicate name keeps the
         * first and warns. Blank/absent input yields an empty list.
         */
        fun parse(raw: String?): List<CustomProviderSpec> {
            if (raw.isNullOrBlank()) return emptyList()
            val byId = LinkedHashMap<MetadataProviderId, CustomProviderSpec>()
            raw.split(";").forEach { entry ->
                parseEntry(entry.trim())?.let { spec ->
                    if (byId.containsKey(spec.id)) {
                        logger.warn {
                            "Duplicate custom provider '${spec.name}' — keeping the first, skipping the rest."
                        }
                    } else {
                        byId[spec.id] = spec
                    }
                }
            }
            return byId.values.toList()
        }

        private fun parseEntry(entry: String): CustomProviderSpec? {
            if (entry.isEmpty()) return null
            val eq = entry.indexOf('=')
            if (eq <= 0) {
                logger.warn { "Malformed custom provider '$entry' — skipping (expected 'name=baseUrl[|caps]')." }
                return null
            }
            val name = entry.substring(0, eq).trim().lowercase()
            val rest = entry.substring(eq + 1).trim()
            val pipe = rest.indexOf('|')
            val baseUrl = (if (pipe < 0) rest else rest.substring(0, pipe)).trim().trimEnd('/')
            if (name.isEmpty() || baseUrl.isEmpty()) {
                logger.warn { "Custom provider '$entry' has a blank name or base URL — skipping." }
                return null
            }
            val capabilities =
                if (pipe <
                    0
                ) {
                    SUPPORTED_CAPABILITIES
                } else {
                    parseCapabilities(name, rest.substring(pipe + 1))
                }
            if (capabilities.isEmpty()) {
                logger.warn { "Custom provider '$name' declares no supported capabilities — skipping." }
                return null
            }
            return CustomProviderSpec(MetadataProviderId.custom(name), name, baseUrl, capabilities)
        }

        private fun parseCapabilities(
            name: String,
            raw: String,
        ): Set<MetadataDomain> =
            raw
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { token ->
                    val domain = MetadataDomain.fromToken(token)
                    when {
                        domain == null -> {
                            logger.warn { "Custom provider '$name': unknown capability '$token' — dropping." }
                            null
                        }

                        domain !in SUPPORTED_CAPABILITIES -> {
                            logger.warn { "Custom provider '$name': capability '$token' is not supported — dropping." }
                            null
                        }

                        else -> {
                            domain
                        }
                    }
                }.toSet()
    }
}
