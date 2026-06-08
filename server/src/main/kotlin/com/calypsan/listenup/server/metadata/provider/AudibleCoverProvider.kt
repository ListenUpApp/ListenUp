package com.calypsan.listenup.server.metadata.provider

import com.calypsan.listenup.api.dto.CoverOptionSource
import com.calypsan.listenup.api.metadata.AudibleRegion
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.server.metadata.audible.AudibleSearchResult
import com.calypsan.listenup.server.services.BookSummary

/**
 * [CoverProvider] backed by Audible search. Emits the first result that carries a
 * non-blank cover URL as a single candidate (Audible search returns one canonical
 * cover per edition). The [search] seam keeps this unit-testable without a live
 * `MetadataService`; the DI binding supplies the real region-aware search.
 */
class AudibleCoverProvider(
    private val search: suspend (book: BookSummary, region: AudibleRegion?) -> AppResult<List<AudibleSearchResult>>,
) : CoverProvider {
    override val source: CoverOptionSource = CoverOptionSource.AUDIBLE

    override suspend fun searchCovers(
        book: BookSummary,
        region: AudibleRegion?,
    ): AppResult<List<CoverCandidate>> =
        search(book, region).map { results ->
            results
                .firstOrNull { it.coverUrl.isNotBlank() }
                ?.let { listOf(CoverCandidate(url = it.coverUrl, sourceId = it.asin)) }
                ?: emptyList()
        }
}
