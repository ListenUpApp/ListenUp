package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.CoverOption
import com.calypsan.listenup.api.dto.CoverOptionSource
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.metadata.AudibleRegion
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.metadata.provider.CoverProvider
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

private val log = KotlinLogging.logger {}

/** A book's title + primary author, used to query the cover catalogs. */
data class BookSummary(
    val title: String,
    val author: String,
)

/**
 * Searches all configured [CoverProvider]s for cover candidates for a book, in parallel.
 * Each provider is failure-contained: if one throws or returns a typed failure, its options
 * are dropped (logged) and the other providers' options still return — never strand the
 * operator for one provider being down. Dimensions are probed per candidate; a probe miss
 * degrades to 0×0 rather than dropping the cover. Options are emitted in provider-list order
 * (the list is built Audible-first in `MetadataModule`).
 *
 * [readBook] and [probeDimensions] are function seams so the orchestration is unit-testable
 * without a DB or HTTP; the DI binding supplies the real implementations.
 */
class CoverSearchService(
    private val readBook: suspend (BookId) -> BookSummary?,
    private val providers: List<CoverProvider>,
    private val probeDimensions: suspend (String) -> Pair<Int, Int>?,
) {
    suspend fun searchCovers(
        bookId: BookId,
        region: AudibleRegion?,
    ): AppResult<List<CoverOption>> {
        val book =
            readBook(bookId)
                ?: return AppResult.Failure(MetadataError.NotFound(debugInfo = "book ${bookId.value} not found"))

        return coroutineScope {
            val deferred =
                providers.map { provider ->
                    async { contained(provider.source.name) { providerOptions(provider, book, region) } }
                }
            AppResult.Success(deferred.awaitAll().flatten())
        }
    }

    private suspend fun providerOptions(
        provider: CoverProvider,
        book: BookSummary,
        region: AudibleRegion?,
    ): List<CoverOption> {
        log.debug { "cover search: source=${provider.source.name} title='${book.title}' author='${book.author}'" }
        return when (val r = provider.searchCovers(book, region)) {
            is AppResult.Failure -> throw SourceException(r.error)
            is AppResult.Success ->
                r.data.map { option(provider.source, it.url, it.sourceId) }.also { opts ->
                    log.debug { "cover search result: source=${provider.source.name} candidates=${opts.size}" }
                }
        }
    }

    private suspend fun option(
        source: CoverOptionSource,
        url: String,
        sourceId: String,
    ): CoverOption {
        val (w, h) = probeDimensions(url) ?: (0 to 0)
        return CoverOption(source = source, url = url, width = w, height = h, sourceId = sourceId)
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

    private class SourceException(
        val error: AppError,
    ) : Exception()
}
