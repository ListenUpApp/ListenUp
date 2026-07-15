package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.CoverOption
import com.calypsan.listenup.api.dto.CoverOptionSource
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.metadata.spi.BookIdentity
import com.calypsan.listenup.server.metadata.spi.CoverMeta
import com.calypsan.listenup.server.metadata.spi.CoverSource
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import com.calypsan.listenup.server.metadata.spi.MetadataProviderRegistry
import com.calypsan.listenup.server.logging.loggerFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

private val log = loggerFor<CoverSearchService>()

/** A book's title + primary author, used to query the cover catalogs. */
data class BookSummary(
    val title: String,
    val author: String,
)

/**
 * Searches every registered [CoverSource] for cover candidates for a book, in parallel.
 * Providers are discovered through [MetadataProviderRegistry.capable] in registration order
 * (Audible first, iTunes second), so options are emitted Audible-first with no hand-wired list.
 *
 * Each provider is failure-contained: if one throws or returns a typed failure, its options
 * are dropped (logged) and the other providers' options still return — never strand the
 * operator for one provider being down. Dimensions are probed per candidate; a probe miss
 * degrades to 0×0 rather than dropping the cover. Audible and iTunes carry a first-class
 * [CoverOptionSource]; every other cover source surfaces as [CoverOptionSource.OTHER] so its
 * candidates still reach the picker.
 *
 * [readBook] and [probeDimensions] are function seams so the orchestration is unit-testable
 * without a DB or HTTP; the DI binding supplies the real implementations.
 */
class CoverSearchService(
    private val readBook: suspend (BookId) -> BookSummary?,
    private val registry: MetadataProviderRegistry,
    private val probeDimensions: suspend (String) -> Pair<Int, Int>?,
) {
    suspend fun searchCovers(
        bookId: BookId,
        region: MetadataLocale?,
    ): AppResult<List<CoverOption>> {
        val book =
            readBook(bookId)
                ?: return AppResult.Failure(MetadataError.NotFound(debugInfo = "book ${bookId.value} not found"))

        val locale = region ?: MetadataLocale.DEFAULT
        val identity = BookIdentity(title = book.title, primaryAuthor = book.author.takeIf { it.isNotBlank() })

        return coroutineScope {
            val deferred =
                registry.capable<CoverSource>().map { provider ->
                    async { contained(provider.id.value) { providerOptions(provider, identity, locale) } }
                }
            AppResult.Success(deferred.awaitAll().flatten())
        }
    }

    private suspend fun providerOptions(
        provider: CoverSource,
        book: BookIdentity,
        locale: MetadataLocale,
    ): List<CoverOption> {
        val source = coverOptionSourceFor(provider.id)
        log.debug { "cover search: source=${source.name} title='${book.title}' author='${book.primaryAuthor}'" }
        return when (val r = provider.searchCovers(book, locale)) {
            is AppResult.Failure -> {
                throw SourceException(r.error)
            }

            is AppResult.Success -> {
                r.data.map { option(source, it) }.also { opts ->
                    log.debug { "cover search result: source=${source.name} candidates=${opts.size}" }
                }
            }
        }
    }

    private suspend fun option(
        source: CoverOptionSource,
        cover: CoverMeta,
    ): CoverOption {
        val url = cover.maxSizeUrl ?: cover.url
        val (w, h) = probeDimensions(url) ?: (0 to 0)
        return CoverOption(source = source, url = url, width = w, height = h, sourceId = cover.sourceKey)
    }

    private suspend fun contained(
        source: String,
        block: suspend () -> List<CoverOption>,
    ): List<CoverOption> =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SourceException) {
            log.warn { "cover search: $source source failed (${e.error.code}: ${e.error.debugInfo}) — skipping" }
            emptyList()
        } catch (e: Exception) {
            log.warn(e) { "cover search: $source source threw" }
            emptyList()
        }

    /**
     * Maps a provider id to its client-facing [CoverOptionSource]. Audible and iTunes get a first-class
     * label; every other cover source (Audnexus, custom providers) surfaces as [CoverOptionSource.OTHER]
     * so its candidates reach the picker instead of being silently dropped.
     */
    private fun coverOptionSourceFor(id: MetadataProviderId): CoverOptionSource =
        when (id) {
            MetadataProviderId.AUDIBLE -> CoverOptionSource.AUDIBLE
            MetadataProviderId.ITUNES -> CoverOptionSource.ITUNES
            else -> CoverOptionSource.OTHER
        }

    private class SourceException(
        val error: AppError,
    ) : Exception()
}
