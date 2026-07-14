package com.calypsan.listenup.server.metadata.custom

import com.calypsan.listenup.api.metadata.MetadataDomain
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.server.metadata.spi.BookCoreMeta
import com.calypsan.listenup.server.metadata.spi.BookCoreSource
import com.calypsan.listenup.server.metadata.spi.BookIdentity
import com.calypsan.listenup.server.metadata.spi.CharacterMeta
import com.calypsan.listenup.server.metadata.spi.CharacterSource
import com.calypsan.listenup.server.metadata.spi.CoverMeta
import com.calypsan.listenup.server.metadata.spi.CoverSource
import com.calypsan.listenup.server.metadata.spi.GenreMeta
import com.calypsan.listenup.server.metadata.spi.GenreSource
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import com.calypsan.listenup.server.metadata.spi.SeriesMeta
import com.calypsan.listenup.server.metadata.spi.SeriesSource

/**
 * An operator-declared custom metadata source, re-skinned onto the capability SPI — the
 * extensibility seam and the only way to fill the [MetadataDomain.CHARACTERS] slot today.
 *
 * One object per `LISTENUP_CUSTOM_PROVIDERS` entry ([spec]), registered in the
 * [com.calypsan.listenup.server.metadata.spi.MetadataProviderRegistry] alongside the built-ins
 * and routable by its `custom:<name>` id exactly like `audible`/`audnexus`/`itunes` (e.g.
 * `LISTENUP_ENRICHMENT_ROUTES=characters=custom:mysource`).
 *
 * It implements every capability the [CustomMetadataClient] JSON contract can serve, but each
 * method first checks [spec]'s declared [CustomProviderSpec.capabilities]: a domain the operator
 * did **not** declare is an immediate honest miss (`Success(null)`/empty, no HTTP), so a lean
 * endpoint is never asked for what it can't serve. A declared domain issues the request; a `404`
 * from the endpoint is likewise an honest catalog miss (see [CustomMetadataClient]).
 *
 * Orchestration only — the custom-JSON → neutral-meta mapping lives in the pure functions in
 * `CustomTypes`. Server-internal: provider ids never cross the RPC wire.
 */
internal class CustomHttpProvider(
    private val spec: CustomProviderSpec,
    private val client: CustomMetadataClient,
) : BookCoreSource,
    CharacterSource,
    CoverSource,
    GenreSource,
    SeriesSource {
    override val id: MetadataProviderId = spec.id

    private fun serves(domain: MetadataDomain): Boolean = domain in spec.capabilities

    override suspend fun getBookCore(
        book: BookIdentity,
        locale: MetadataLocale,
        refresh: Boolean,
    ): AppResult<BookCoreMeta?> {
        if (!serves(MetadataDomain.BOOK_CORE)) return AppResult.Success(null)
        return client.getBook(book.asin, book.title, book.primaryAuthor, locale.region).map { it?.toBookCoreMeta() }
    }

    override suspend fun getCharacters(
        book: BookIdentity,
        locale: MetadataLocale,
    ): AppResult<List<CharacterMeta>?> {
        if (!serves(MetadataDomain.CHARACTERS)) return AppResult.Success(null)
        return client.getCharacters(book.asin, book.title, locale.region).map { it?.toCharacterMetas() }
    }

    override suspend fun searchCovers(
        book: BookIdentity,
        locale: MetadataLocale,
    ): AppResult<List<CoverMeta>> {
        if (!serves(MetadataDomain.COVER)) return AppResult.Success(emptyList())
        return client.getCovers(book.asin, book.title, book.primaryAuthor, locale.region).map {
            it?.toCoverMetas()
                ?: emptyList()
        }
    }

    override suspend fun getGenres(
        book: BookIdentity,
        locale: MetadataLocale,
    ): AppResult<List<GenreMeta>?> {
        if (!serves(MetadataDomain.GENRES)) return AppResult.Success(null)
        return client.getGenres(book.asin, book.title, locale.region).map { it?.toGenreMetas() }
    }

    override suspend fun getSeries(
        book: BookIdentity,
        locale: MetadataLocale,
    ): AppResult<List<SeriesMeta>?> {
        if (!serves(MetadataDomain.SERIES)) return AppResult.Success(null)
        return client.getSeries(book.asin, book.title, locale.region).map { it?.toSeriesMetas() }
    }
}
