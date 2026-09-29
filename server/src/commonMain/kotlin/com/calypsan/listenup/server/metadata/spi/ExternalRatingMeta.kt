package com.calypsan.listenup.server.metadata.spi

/**
 * A catalog's rating of one book, as [RatingSource] reports it: [average] 0–5 over [count]
 * ratings, from the storefront [region] that actually answered — the same short lowercase
 * token [com.calypsan.listenup.api.metadata.MetadataLocale.region] uses (`us`, `ca`, `uk`, …).
 * For a region-locked catalog like Audible, the answering store may differ from the one the
 * caller asked for; `null` for sources without storefronts (Hardcover).
 */
data class ExternalRatingMeta(
    val average: Double,
    val count: Int,
    val region: String? = null,
)
