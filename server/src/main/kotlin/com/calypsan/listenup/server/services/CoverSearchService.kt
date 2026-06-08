package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.CoverOption
import com.calypsan.listenup.api.dto.CoverOptionSource
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.metadata.AudibleRegion
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.metadata.audible.AudibleSearchResult
import com.calypsan.listenup.server.metadata.itunes.ITunesCoverHit
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
 * Searches Audible + iTunes for cover candidates for a book, in parallel. Each source is
 * failure-contained: if one throws, its options are dropped (logged) and the other source's
 * options still return — never strand the operator for one provider being down. Dimensions are
 * probed per candidate; a probe miss degrades to 0×0 rather than dropping the cover. Audible
 * candidates are listed first (matches Go).
 *
 * The collaborators are function seams so the orchestration is unit-testable without a DB or HTTP;
 * the DI binding supplies the real implementations.
 */
class CoverSearchService(
    private val readBook: suspend (BookId) -> BookSummary?,
    private val audibleSearch: suspend (book: BookSummary, region: AudibleRegion?) -> AppResult<List<AudibleSearchResult>>,
    private val itunesSearch: suspend (title: String, author: String) -> AppResult<List<ITunesCoverHit>>,
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
            val audible = async { contained("audible") { audibleOptions(book, region) } }
            val itunes = async { contained("itunes") { itunesOptions(book) } }
            AppResult.Success(awaitAll(audible, itunes).flatten())
        }
    }

    private suspend fun audibleOptions(
        book: BookSummary,
        region: AudibleRegion?,
    ): List<CoverOption> =
        when (val r = audibleSearch(book, region)) {
            is AppResult.Failure -> throw SourceException(r.error)
            is AppResult.Success ->
                r.data.firstOrNull { it.coverUrl.isNotBlank() }?.let { hit ->
                    listOf(option(CoverOptionSource.AUDIBLE, hit.coverUrl, hit.asin))
                } ?: emptyList()
        }

    private suspend fun itunesOptions(book: BookSummary): List<CoverOption> =
        when (val r = itunesSearch(book.title, book.author)) {
            is AppResult.Failure -> throw SourceException(r.error)
            is AppResult.Success ->
                r.data
                    .filter { it.maxSizeUrl.isNotBlank() }
                    .map { option(CoverOptionSource.ITUNES, it.maxSizeUrl, it.sourceId) }
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
            log.warn { "cover search: $source source failed: ${e.error.code}" }
            emptyList()
        } catch (e: Exception) {
            log.warn(e) { "cover search: $source source threw" }
            emptyList()
        }

    private class SourceException(val error: AppError) : Exception()
}
