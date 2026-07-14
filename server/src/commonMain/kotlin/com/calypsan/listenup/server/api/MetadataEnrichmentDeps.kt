package com.calypsan.listenup.server.api

import com.calypsan.listenup.server.services.BookMoodWriter
import com.calypsan.listenup.server.services.BookTagWriter

/**
 * Cohesive bundle of the mood/trope-enrichment writers used by [BookMetadataApplier].
 *
 * Groups the add-only [BookMoodWriter] and [BookTagWriter] junction writers — which reconcile a
 * book's moods/tropes to the user's apply selection — into a single injected value so
 * [BookMetadataApplier] doesn't carry two loose params.
 */
internal data class MetadataEnrichmentDeps(
    val bookMoodWriter: BookMoodWriter,
    val bookTagWriter: BookTagWriter,
)
