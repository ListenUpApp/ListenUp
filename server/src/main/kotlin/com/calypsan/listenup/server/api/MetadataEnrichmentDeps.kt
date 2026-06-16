package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.metadata.AudibleRegion
import com.calypsan.listenup.api.result.AppResult
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
 * [productTagSource] returns the *typed* scrape result so the lookup can tell a genuine "no
 * topics" (`Success(emptyList())`) from a scrape that couldn't run at all (`Failure` — e.g. the
 * title isn't in the selected region) and surface the latter to the user. It never throws past
 * the boundary.
 */
internal data class MetadataEnrichmentDeps(
    val bookMoodWriter: BookMoodWriter,
    val bookTagWriter: BookTagWriter,
    val productTagSource: suspend (region: AudibleRegion, asin: String) -> AppResult<List<ProductTag>>,
)
