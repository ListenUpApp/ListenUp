package com.calypsan.listenup.server.metadata.spi

/**
 * A lightweight hit from a contributor-profile search — enough to disambiguate
 * and then fetch the full [ContributorMeta].
 *
 * [key] is the catalog's stable contributor id (the fetch key). Deliberately lean:
 * profile search only needs a name to show and a key to follow.
 */
data class ContributorHitMeta(
    /** Catalog contributor key — pass to [ContributorSource.getContributor]. */
    val key: String,
    /** Display name of the hit. */
    val name: String,
)

/**
 * A provider-neutral contributor *profile*.
 *
 * Kept lean on purpose: the only public source (Audnexus) exposes name, biography,
 * and a photo — no aliases, birthdate, or discography. Modelling only what a source
 * can actually deliver keeps this from becoming a mostly-null wish-list.
 */
data class ContributorMeta(
    /** Catalog contributor key. */
    val key: String,
    /** Display name. */
    val name: String,
    /** Biography / description, when the catalog has one. */
    val description: String? = null,
    /** Profile image URL, when the catalog has one. */
    val imageUrl: String? = null,
)
