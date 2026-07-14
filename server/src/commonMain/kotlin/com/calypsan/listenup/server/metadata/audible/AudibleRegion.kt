package com.calypsan.listenup.server.metadata.audible

import com.calypsan.listenup.api.metadata.MetadataLocale

/**
 * Audible regional storefronts.
 *
 * A server-internal Audible detail — it never crosses the RPC wire. The general contract
 * speaks the provider-neutral [MetadataLocale]; the Audible adapter maps that locale to a
 * region at its edge via [toAudibleRegion]. The [code] is the stable short token used in
 * config, URL params, and cache keys; [displayName] is a human-readable label.
 *
 * Region routing details (API hostname, locale cookies) live alongside in
 * `AudibleRegionConfig`.
 */
enum class AudibleRegion(
    /** Operator-facing short code used in config, URL params, and cache keys. */
    val code: String,
    /** Human-readable name for logs and diagnostics. */
    val displayName: String,
) {
    US(code = "us", displayName = "United States"),
    UK(code = "uk", displayName = "United Kingdom"),
    DE(code = "de", displayName = "Germany"),
    FR(code = "fr", displayName = "France"),
    AU(code = "au", displayName = "Australia"),
    CA(code = "ca", displayName = "Canada"),
    JP(code = "jp", displayName = "Japan"),
    IT(code = "it", displayName = "Italy"),
    IN(code = "in", displayName = "India"),
    ES(code = "es", displayName = "Spain"),
    ;

    companion object {
        /** Returns the region whose [code] matches, case-insensitively, or `null`. */
        fun fromCodeOrNull(code: String): AudibleRegion? = entries.firstOrNull { it.code == code.lowercase() }
    }
}

/**
 * Maps a provider-neutral [MetadataLocale] to the Audible storefront it queries.
 *
 * A recognized [MetadataLocale.region] resolves to that storefront; an unrecognized one falls
 * back to [AudibleRegion.US] — the never-strand rule at the Audible provider edge.
 */
fun MetadataLocale.toAudibleRegion(): AudibleRegion = AudibleRegion.fromCodeOrNull(region) ?: AudibleRegion.US
