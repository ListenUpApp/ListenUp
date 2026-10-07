package com.calypsan.listenup.server.matching.review

import com.calypsan.listenup.api.dto.match.CoverCandidate
import com.calypsan.listenup.api.dto.match.CoverReview
import com.calypsan.listenup.api.dto.match.CurrentCover
import com.calypsan.listenup.api.dto.match.ImageChoice
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.FieldSourceKind
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.server.metadata.spi.CoverMeta
import com.calypsan.listenup.server.metadata.spi.EnrichmentRoutes
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import com.calypsan.listenup.server.metadata.spi.presentedAs
import com.calypsan.listenup.server.metadata.spi.toMetadataSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** One reviewed cover tile, and the provider that offered it. */
internal data class ReviewedCover(
    val candidate: CoverCandidate,
    val provider: MetadataProviderId,
)

/** The cover review and its tiles' providers. */
internal data class CoverReviewResult(
    val review: CoverReview,
    val covers: List<ReviewedCover>,
)

/**
 * The cover half of Review (spec, *The cover choice*). Tiles are every routed provider's covers — the largest
 * rendition of each — ordered by route, then by probed size; a URL two providers share is one tile. The default
 * is decision 6: keep a cover a person set by hand; otherwise the first tile from a source that found this
 * candidate, else the first tile; keep the current cover when there are no tiles.
 */
internal object CoverReviewer {
    suspend fun review(
        book: BookSyncPayload,
        covers: Map<MetadataProviderId, List<CoverMeta>>,
        routes: EnrichmentRoutes,
        candidateProviders: Set<String>,
        probe: suspend (String) -> Pair<Int, Int>?,
    ): CoverReviewResult {
        val unique =
            routes
                .orderFor(BookField.COVER)
                .flatMap { provider ->
                    covers[provider].orEmpty().map { provider to (it.maxSizeUrl?.takeIf(String::isNotBlank) ?: it.url) }
                }.filter { (_, url) -> url.isNotBlank() }
                .distinctBy { (_, url) -> url }
        val probed =
            coroutineScope {
                unique.map { (provider, url) -> async { Triple(provider, url, probe(url) ?: (0 to 0)) } }.awaitAll()
            }
        val order = routes.orderFor(BookField.COVER)
        val tiles =
            probed
                .sortedWith(
                    compareBy<Triple<MetadataProviderId, String, Pair<Int, Int>>> { order.indexOf(it.first) }
                        .thenByDescending { it.third.first.toLong() * it.third.second },
                ).map { (provider, url, size) ->
                    ReviewedCover(
                        CoverCandidate(
                            optionId = ReviewKeys.optionId(provider.presentedAs().value, "c:$url"),
                            source = provider.toMetadataSource(),
                            url = url,
                            width = size.first,
                            height = size.second,
                        ),
                        provider,
                    )
                }
        val setByHand = book.fieldProvenance[BookField.COVER]?.kind == FieldSourceKind.USER
        val default =
            if (tiles.isEmpty() || setByHand) {
                ImageChoice.KeepCurrent
            } else {
                val preferred =
                    tiles.firstOrNull { it.provider.presentedAs().value in candidateProviders } ?: tiles.first()
                ImageChoice.Candidate(preferred.candidate.optionId)
            }
        return CoverReviewResult(
            review =
                CoverReview(
                    current = book.cover?.let { CurrentCover(hash = it.hash, setByHand = setByHand) },
                    options = tiles.map { it.candidate },
                    defaultChoice = default,
                ),
            covers = tiles,
        )
    }
}
