package com.calypsan.listenup.server.metadata.provider

import com.calypsan.listenup.api.dto.CoverOptionSource
import com.calypsan.listenup.api.metadata.AudibleRegion
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.server.metadata.itunes.ITunesCoverHit
import com.calypsan.listenup.server.services.BookSummary

/**
 * [CoverProvider] backed by iTunes multi-candidate cover search. Each hit's
 * max-resolution URL becomes a candidate; hits with a blank max URL are dropped.
 * iTunes is region-agnostic, so [region] is ignored. The [search] seam keeps this
 * unit-testable without a live `MetadataService`.
 */
class ITunesCoverProvider(
    private val search: suspend (title: String, author: String) -> AppResult<List<ITunesCoverHit>>,
) : CoverProvider {
    override val source: CoverOptionSource = CoverOptionSource.ITUNES

    override suspend fun searchCovers(
        book: BookSummary,
        region: AudibleRegion?,
    ): AppResult<List<CoverCandidate>> =
        search(book.title, book.author).map { hits ->
            hits
                .filter { it.maxSizeUrl.isNotBlank() }
                .map { CoverCandidate(url = it.maxSizeUrl, sourceId = it.sourceId) }
        }
}
