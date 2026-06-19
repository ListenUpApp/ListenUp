package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.metadata.AudibleRegion
import com.calypsan.listenup.server.metadata.audible.ProductTag
import com.calypsan.listenup.server.services.BookMoodWriter
import com.calypsan.listenup.server.services.BookTagWriter

/**
 * Cohesive bundle of the Audible-enrichment collaborators used by [BookMetadataApplier].
 *
 * Groups the three dependencies that together drive the post-match mood/trope enrichment —
 * the add-only [BookMoodWriter] and [BookTagWriter] junction writers, and the
 * [productTagSource] that scrapes the matched ASIN's Audible product topic-tags — into a
 * single injected value so neither [BookMetadataApplier] nor [MetadataLookupServiceImpl]
 * carries three loose params (the latter is already at the constructor-param budget).
 *
 * [productTagSource] is best-effort by contract: it returns an empty list (never throws past
 * the boundary) on a scrape miss or transport failure, mirroring the wider best-effort shape
 * of the enrichment step.
 */
internal data class MetadataEnrichmentDeps(
    val bookMoodWriter: BookMoodWriter,
    val bookTagWriter: BookTagWriter,
    val productTagSource: suspend (region: AudibleRegion, asin: String) -> List<ProductTag>,
)
