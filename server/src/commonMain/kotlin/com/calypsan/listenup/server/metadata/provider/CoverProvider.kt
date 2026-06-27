package com.calypsan.listenup.server.metadata.provider

import com.calypsan.listenup.api.dto.CoverOptionSource
import com.calypsan.listenup.api.metadata.AudibleRegion
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.services.BookSummary

/**
 * One cover-art candidate from a [CoverProvider], before dimension probing.
 * [url] is the full (max-resolution where available) image URL; [sourceId] is
 * provenance — the Audible ASIN or iTunes collectionId.
 */
data class CoverCandidate(
    val url: String,
    val sourceId: String,
)

/**
 * A source of cover-art candidates for a book. Implementations are thin: they query
 * one catalog and return ready [CoverCandidate]s. The cross-cutting dimension probe
 * and failure-containment live in `CoverSearchService`, not here.
 *
 * Add a new cover source by implementing this interface and registering it in the
 * `List<CoverProvider>` built in `MetadataModule`; the list order is the display order.
 */
interface CoverProvider {
    /** Which catalog this provider represents — the display label + provenance tag. */
    val source: CoverOptionSource

    /**
     * Returns cover candidates for [book]. [region] is honored by Audible-like providers
     * and ignored by region-agnostic ones (iTunes). Returns an empty list on no match;
     * a typed [AppResult.Failure] only on a provider error.
     */
    suspend fun searchCovers(
        book: BookSummary,
        region: AudibleRegion?,
    ): AppResult<List<CoverCandidate>>
}
