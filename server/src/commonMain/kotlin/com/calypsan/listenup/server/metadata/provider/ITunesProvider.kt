package com.calypsan.listenup.server.metadata.provider

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.server.metadata.itunes.ITunesApi
import com.calypsan.listenup.server.metadata.itunes.ITunesCoverHit
import com.calypsan.listenup.server.metadata.spi.BookFindSource
import com.calypsan.listenup.server.metadata.spi.BookIdentity
import com.calypsan.listenup.server.metadata.spi.CoverMeta
import com.calypsan.listenup.server.metadata.spi.CoverSource
import com.calypsan.listenup.server.metadata.spi.FindAnswer
import com.calypsan.listenup.server.metadata.spi.FindLookup
import com.calypsan.listenup.server.metadata.spi.FindRole
import com.calypsan.listenup.server.metadata.spi.FindStep
import com.calypsan.listenup.server.metadata.spi.FoundBook
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId

/**
 * The iTunes / Apple Books catalog re-skinned onto the metadata SPI as a [CoverSource].
 *
 * iTunes is covers-only in ListenUp — Audible is authoritative for every textual field —
 * so this implements exactly one capability. Each hit's original artwork URL and its
 * high-resolution rendition map to [CoverMeta.url] / [CoverMeta.maxSizeUrl]; the cover
 * fan-out in `CoverSearchService` prefers the max rendition. Hits with no high-res URL are
 * dropped. iTunes is region-agnostic, so [locale] is ignored.
 *
 * In Find (matching redesign) it is an *attaching* [BookFindSource]: it never identifies a book; its hits
 * join the candidates Audible or Hardcover found, as one more cover and one more "found in".
 *
 * Thin orchestration over [ITunesApi]; the max-resolution URL substitution and audiobook
 * filtering already live in the iTunes client. Server-internal.
 */
internal class ITunesProvider(
    private val itunes: ITunesApi,
) : CoverSource,
    BookFindSource {
    override val id: MetadataProviderId = MetadataProviderId.ITUNES

    override val findRole: FindRole = FindRole.ATTACHES

    override suspend fun searchCovers(
        book: BookIdentity,
        locale: MetadataLocale,
    ): AppResult<List<CoverMeta>> =
        itunes.searchCovers(book.title, book.primaryAuthor.orEmpty()).map { hits -> hits.toCoverMetas() }

    /**
     * Searches what Find searches — title and author, or the person's query. A hit with no iTunes id can't be
     * referenced, and one with no title can't be matched to a candidate, so both are dropped.
     */
    override suspend fun findBooks(
        lookup: FindLookup,
        locale: MetadataLocale,
    ): AppResult<FindAnswer> =
        itunes.searchCovers(lookup.text, "").map { hits ->
            FindAnswer(
                books =
                    hits
                        .filter { it.sourceId.isNotBlank() && it.title.isNotBlank() && it.maxSizeUrl.isNotBlank() }
                        .map {
                            FoundBook(
                                key = it.sourceId,
                                title = it.title,
                                authors = listOfNotNull(it.author.takeIf(String::isNotBlank)),
                                coverUrl = it.maxSizeUrl,
                            )
                        },
                steps = setOf(FindStep.TEXT),
            )
        }
}

/** Maps iTunes cover hits to [CoverMeta]s, dropping any without a high-resolution rendition. */
internal fun List<ITunesCoverHit>.toCoverMetas(): List<CoverMeta> =
    filter { it.maxSizeUrl.isNotBlank() }
        .map { CoverMeta(url = it.coverUrl, maxSizeUrl = it.maxSizeUrl, sourceKey = it.sourceId) }
