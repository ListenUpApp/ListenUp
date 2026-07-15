package com.calypsan.listenup.server.metadata.spi

import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.MetadataDomain
import com.calypsan.listenup.server.logging.loggerFor

private val logger = loggerFor<EnrichmentRoutes>()

/**
 * The operator-configured provider precedence for metadata enrichment.
 *
 * Two layers, coarse to fine:
 * - [domainOrder] — the provider chain the router walks for a whole
 *   [MetadataDomain]. Total over every domain, so [orderFor] can resolve any field.
 * - [fieldOverrides] — a per-[BookField] chain that supersedes the field's domain
 *   order. This is what lets [BookField.AUTHORS] and [BookField.NARRATORS] — same
 *   domain — take different provider chains.
 *
 * The router asks [orderFor] for a field's chain and walks it first-non-empty. An
 * empty chain (e.g. [MetadataDomain.CHARACTERS] by default) means "no provider" —
 * the honest empty slot, not a failure.
 *
 * Built from code defaults ([DEFAULT]) and, when set, the `LISTENUP_ENRICHMENT_ORDER`
 * / `LISTENUP_ENRICHMENT_ROUTES` environment variables via [parse].
 */
data class EnrichmentRoutes(
    /** Provider precedence per domain — total over [MetadataDomain]. */
    val domainOrder: Map<MetadataDomain, List<MetadataProviderId>>,
    /** Per-field overrides that beat the field's [domainOrder] entry. */
    val fieldOverrides: Map<BookField, List<MetadataProviderId>>,
) {
    /**
     * The provider precedence for [field]: its explicit override when present, else
     * its domain's order. Never fails — [domainOrder] is total over every domain.
     */
    fun orderFor(field: BookField): List<MetadataProviderId> =
        fieldOverrides[field] ?: domainOrder.getValue(field.domain)

    /**
     * Every provider the router might consult for [domain]: its domain order plus any
     * per-field override that names a provider for one of the domain's fields. The
     * coordinator intersects its capability fan-out with this set so a provider the
     * operator never routed to [domain] is not queried at all — no wasted fetch, no
     * privacy/rate-limit surprise (e.g. a cover-only catalog isn't hit for genres).
     */
    fun providersFor(domain: MetadataDomain): Set<MetadataProviderId> =
        buildSet {
            addAll(domainOrder.getValue(domain))
            fieldOverrides.forEach { (field, providers) -> if (field.domain == domain) addAll(providers) }
        }

    /**
     * The `custom:<name>` provider ids these routes name that are *not* in [knownIds] — routes that
     * resolve to nothing because no matching provider was declared in `LISTENUP_CUSTOM_PROVIDERS`
     * (typically a typo). Returned distinct and in first-seen order so a caller can warn once per id.
     * Built-in ids are never reported (they always resolve). [parse] can't catch these on its own —
     * a `custom:<name>` token is syntactically valid before the provider registry exists.
     */
    fun unresolvedCustomProviders(knownIds: Set<MetadataProviderId>): List<MetadataProviderId> =
        (domainOrder.values.flatten() + fieldOverrides.values.flatten())
            .asSequence()
            .filter { it.value.startsWith(MetadataProviderId.CUSTOM_PREFIX) }
            .filterNot { it in knownIds }
            .distinct()
            .toList()

    companion object {
        /**
         * The approved code defaults — the enrichment order with no env configured.
         * Total over every [MetadataDomain]; [MetadataDomain.CHARACTERS] is empty (no
         * source exists).
         */
        val DEFAULT_DOMAIN_ORDER: Map<MetadataDomain, List<MetadataProviderId>> =
            mapOf(
                MetadataDomain.BOOK_CORE to listOf(MetadataProviderId.AUDIBLE, MetadataProviderId.AUDNEXUS),
                MetadataDomain.CONTRIBUTORS to listOf(MetadataProviderId.AUDNEXUS, MetadataProviderId.AUDIBLE),
                MetadataDomain.CHAPTERS to listOf(MetadataProviderId.AUDNEXUS, MetadataProviderId.AUDIBLE),
                MetadataDomain.COVER to listOf(MetadataProviderId.AUDIBLE, MetadataProviderId.ITUNES),
                MetadataDomain.SERIES to listOf(MetadataProviderId.AUDIBLE, MetadataProviderId.AUDNEXUS),
                MetadataDomain.GENRES to listOf(MetadataProviderId.AUDIBLE, MetadataProviderId.AUDNEXUS),
                MetadataDomain.CHARACTERS to emptyList(),
            )

        /** The routes with no env configured — code defaults, no field overrides. */
        val DEFAULT = EnrichmentRoutes(DEFAULT_DOMAIN_ORDER, emptyMap())

        /**
         * Parses the enrichment configuration, never-strand.
         *
         * [order] mirrors `LISTENUP_ENRICHMENT_ORDER` (e.g. `audible,audnexus,itunes`)
         * — a global baseline applied to every domain when set and non-empty.
         * [routes] mirrors `LISTENUP_ENRICHMENT_ROUTES` (e.g.
         * `contributors=audnexus,audible; chapters=local,audnexus,audible; description=audible`)
         * — semicolon-separated `key=provider,provider` clauses. A key is a domain
         * token OR a field token; a domain token wins the collision (`series`, `cover`,
         * `chapters` are domains), and a field clause supersedes its domain at
         * [orderFor] time.
         *
         * Resolution precedence per domain: a domain clause in [routes] > [order]'s
         * global baseline > the code default. Field clauses populate [fieldOverrides].
         *
         * Malformed input never throws: an unknown provider token is dropped, a
         * clause with no `=` / no valid providers / an unknown key is logged once and
         * skipped, and a blank value behaves like unset. A misconfigured env must
         * never strand enrichment.
         */
        fun parse(
            order: String?,
            routes: String?,
        ): EnrichmentRoutes {
            val resolved = DEFAULT_DOMAIN_ORDER.toMutableMap()

            parseProviderList(order)?.let { globalBaseline ->
                MetadataDomain.entries.forEach { domain -> resolved[domain] = globalBaseline }
            }

            val fieldOverrides = mutableMapOf<BookField, List<MetadataProviderId>>()
            parseClauses(routes).forEach { (key, providers) ->
                val domain = MetadataDomain.fromToken(key)
                if (domain != null) {
                    resolved[domain] = providers
                    return@forEach
                }
                val field = BookField.fromToken(key)
                if (field != null) {
                    fieldOverrides[field] = providers
                    return@forEach
                }
                logger.warn { "Unknown enrichment route key '$key' — skipping (not a domain or field token)." }
            }

            return EnrichmentRoutes(resolved.toMap(), fieldOverrides.toMap())
        }

        /**
         * Parses a comma-separated provider token list into ids, dropping unknown
         * tokens with a warning. Returns `null` when [raw] is blank or yields no valid
         * id (so the caller treats it as unset).
         */
        private fun parseProviderList(raw: String?): List<MetadataProviderId>? {
            if (raw.isNullOrBlank()) return null
            val ids =
                raw
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .mapNotNull { token ->
                        MetadataProviderId.fromToken(token).also {
                            if (it == null) logger.warn { "Unknown metadata provider '$token' — skipping." }
                        }
                    }
            return ids.ifEmpty { null }
        }

        /**
         * Splits a `LISTENUP_ENRICHMENT_ROUTES` value into `(key, providers)` clauses,
         * skipping any malformed clause with a warning. Keys are lowercased for
         * case-insensitive token matching.
         */
        private fun parseClauses(raw: String?): List<Pair<String, List<MetadataProviderId>>> {
            if (raw.isNullOrBlank()) return emptyList()
            return raw.split(";").mapNotNull { clause ->
                val trimmed = clause.trim()
                if (trimmed.isEmpty()) return@mapNotNull null
                val eq = trimmed.indexOf('=')
                if (eq <= 0) {
                    logger.warn {
                        "Malformed enrichment route clause '$trimmed' — skipping (expected 'key=provider,...')."
                    }
                    return@mapNotNull null
                }
                val key = trimmed.substring(0, eq).trim().lowercase()
                val providers = parseProviderList(trimmed.substring(eq + 1))
                if (providers == null) {
                    logger.warn { "Enrichment route '$key' has no valid providers — skipping." }
                    return@mapNotNull null
                }
                key to providers
            }
        }
    }
}
