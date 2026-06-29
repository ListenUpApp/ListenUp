package com.calypsan.listenup.api.metadata

import kotlinx.serialization.Serializable

/**
 * Audible regional storefronts supported by the metadata service.
 *
 * This enum is part of the contract layer because it appears in
 * [com.calypsan.listenup.api.MetadataLookupService] method signatures and
 * therefore crosses the RPC wire. The [code] is the stable serialized value;
 * [displayName] is the user-facing label for region-selection UI.
 *
 * Server-specific routing details (API hostname, locale cookies) live in
 * `:server`'s `AudibleRegionConfig` extension to keep the contract lean.
 */
@Serializable
enum class AudibleRegion(
    /** Operator-facing short code used in config, URL params, and wire serialization. */
    val code: String,
    /** Human-readable name for UI region-selection widgets. */
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
