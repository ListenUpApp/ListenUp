package com.calypsan.listenup.api.dto.match

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A book as one metadata catalogue knows it: which catalogue, and that catalogue's own key.
 *
 * Replaces the Audible-only `asin` as the identity matching speaks. [provider] is the provider's stable
 * id (`audible`, `hardcover`, `itunes`, `custom:<name>`); clients treat it as an opaque key and never
 * branch on it. [region] is the store the key was found in, for a provider that has stores.
 */
@Serializable
@SerialName("ExternalRef")
data class ExternalRef(
    @SerialName("provider") val provider: String,
    @SerialName("id") val id: String,
    @SerialName("region") val region: String? = null,
) {
    init {
        require(provider.isNotBlank()) { "ExternalRef provider cannot be blank" }
        require(id.isNotBlank()) { "ExternalRef id cannot be blank" }
    }

    /** Provider ids the server reconciles specially. */
    companion object {
        /** The Audible catalogue — the one provider whose key the legacy `asin` columns mirror. */
        const val AUDIBLE: String = "audible"
    }
}
