package com.calypsan.listenup.api.metadata

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The provider-neutral locale a metadata lookup runs in.
 *
 * This is the single locale type on the [com.calypsan.listenup.api.MetadataLookupService]
 * RPC signatures — it crosses the wire and is read by both client and server. Each server
 * provider maps it to its own regional vocabulary (Audible maps [region] to an
 * `AudibleRegion`, iTunes to a storefront country); the contract stays neutral so no single
 * catalog's region enum leaks into the general surface or onto the client export.
 *
 * [region] is a short lowercase market token (e.g. `us`, `uk`, `de`); [language] is an
 * optional BCP-47-style language hint a provider may honor. A provider that does not
 * recognize a locale falls back to its own default rather than failing — the never-strand
 * rule at the provider edge.
 */
@Serializable
data class MetadataLocale(
    /** Short lowercase market/region token, e.g. `us`, `uk`, `de`. Defaults to `us`. */
    @SerialName("region") val region: String = DEFAULT_REGION,
    /** Optional language hint (BCP-47-style) a provider may honor; `null` leaves it to the provider. */
    @SerialName("language") val language: String? = null,
) {
    init {
        require(region.isNotBlank()) { "MetadataLocale region cannot be blank" }
    }

    /** Human-readable label for region-selection UI — a country name, else the code upper-cased. */
    val displayName: String
        get() = DISPLAY_NAMES[region.lowercase()] ?: region.uppercase()

    companion object {
        private const val DEFAULT_REGION = "us"

        /** The default market when the caller has no library-specific locale. */
        val DEFAULT: MetadataLocale = MetadataLocale(DEFAULT_REGION)

        /** The markets offered in the region-selection UI, in display order. */
        val SUPPORTED: List<MetadataLocale> =
            listOf("us", "uk", "de", "fr", "au", "ca", "jp", "it", "in", "es").map { MetadataLocale(it) }

        private val DISPLAY_NAMES: Map<String, String> =
            mapOf(
                "us" to "United States",
                "uk" to "United Kingdom",
                "de" to "Germany",
                "fr" to "France",
                "au" to "Australia",
                "ca" to "Canada",
                "jp" to "Japan",
                "it" to "Italy",
                "in" to "India",
                "es" to "Spain",
            )
    }
}
