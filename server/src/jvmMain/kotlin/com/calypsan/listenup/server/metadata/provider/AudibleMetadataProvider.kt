package com.calypsan.listenup.server.metadata.provider

import com.calypsan.listenup.api.dto.MetadataBook
import com.calypsan.listenup.api.dto.MetadataChapter
import com.calypsan.listenup.api.dto.MetadataChapters
import com.calypsan.listenup.api.dto.MetadataContributorRef
import com.calypsan.listenup.api.dto.MetadataSeriesRef
import com.calypsan.listenup.api.metadata.AudibleRegion
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.server.metadata.audible.AudibleBook
import com.calypsan.listenup.server.metadata.audible.AudibleChapter
import com.calypsan.listenup.server.metadata.audible.AudibleSearchResult
import com.calypsan.listenup.server.metadata.audible.AudibleSeriesEntry
import com.calypsan.listenup.server.metadata.audible.SearchParams
import com.calypsan.listenup.server.services.MetadataService
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * [MetadataProvider] backed by Audible via [MetadataService] (which adds TTL caching +
 * region-aware fallback). Owns the Audible→wire-DTO mapping that previously lived in
 * `MetadataLookupServiceImpl`.
 */
internal class AudibleMetadataProvider(
    private val metadataService: MetadataService,
) : MetadataProvider {
    override val source: MetadataSource = MetadataSource.AUDIBLE

    override suspend fun search(
        query: String,
        region: AudibleRegion?,
    ): AppResult<List<MetadataBook>> {
        logger.debug { "metadata lookup: source=AUDIBLE query='$query' region=${region?.name ?: "default+fallback"}" }
        val params = SearchParams(keywords = query)
        val result =
            if (region == null) {
                metadataService.searchWithFallback(params)
            } else {
                metadataService.search(region, params)
            }
        return result.map { hits ->
            hits.map { it.toMetadataBook() }.also { books ->
                logger.debug { "metadata lookup result: source=AUDIBLE matches=${books.size}" }
            }
        }
    }

    override suspend fun getBook(
        id: String,
        region: AudibleRegion,
        refresh: Boolean,
    ): AppResult<MetadataBook?> {
        logger.debug { "metadata lookup: source=AUDIBLE asin=$id region=${region.name} refresh=$refresh" }
        return metadataService.getBook(region, id, refresh = refresh).map { book ->
            book?.toMetadataBook().also { mapped ->
                logger.debug { "metadata lookup result: source=AUDIBLE asin=$id found=${mapped != null}" }
            }
        }
    }

    override suspend fun getChapters(
        id: String,
        region: AudibleRegion,
    ): AppResult<MetadataChapters?> {
        logger.debug { "metadata lookup: source=AUDIBLE chapters asin=$id region=${region.name}" }
        return metadataService.getBookChapters(region, id).map { chapters ->
            if (chapters.isEmpty()) {
                logger.debug { "metadata lookup result: source=AUDIBLE chapters asin=$id count=0" }
                null
            } else {
                MetadataChapters(chapters = chapters.map { it.toMetadataChapter() }).also { result ->
                    logger.debug { "metadata lookup result: source=AUDIBLE chapters asin=$id count=${result.chapters.size}" }
                }
            }
        }
    }
}

// ─── Audible → wire DTO mappers (moved from MetadataLookupServiceImpl) ─────────

internal fun AudibleSearchResult.toMetadataBook(): MetadataBook =
    MetadataBook(
        asin = asin,
        title = title,
        subtitle = subtitle.takeIf { it.isNotBlank() },
        description = null,
        publisher = null,
        releaseDate = releaseDate.takeIf { it.isNotBlank() },
        runtimeMinutes = runtimeMinutes.takeIf { it > 0 },
        language = null,
        authors = authors.map { MetadataContributorRef(asin = it.asin.takeIf { a -> a.isNotBlank() }, name = it.name) },
        narrators =
            narrators.map { MetadataContributorRef(asin = it.asin.takeIf { a -> a.isNotBlank() }, name = it.name) },
        series = emptyList(),
        genres = emptyList(),
        coverUrl = coverUrl.takeIf { it.isNotBlank() },
        coverUrlMaxSize = null,
    )

internal fun AudibleBook.toMetadataBook(): MetadataBook =
    MetadataBook(
        asin = asin,
        title = title,
        subtitle = subtitle.takeIf { it.isNotBlank() },
        description = description.takeIf { it.isNotBlank() },
        publisher = publisher.takeIf { it.isNotBlank() },
        releaseDate = releaseDate.takeIf { it.isNotBlank() },
        runtimeMinutes = runtimeMinutes.takeIf { it > 0 },
        language = language.takeIf { it.isNotBlank() },
        authors = authors.map { MetadataContributorRef(asin = it.asin.takeIf { a -> a.isNotBlank() }, name = it.name) },
        narrators =
            narrators.map { MetadataContributorRef(asin = it.asin.takeIf { a -> a.isNotBlank() }, name = it.name) },
        series = series.map { it.toMetadataSeriesRef() },
        genres = genres,
        coverUrl = coverUrl.takeIf { it.isNotBlank() },
        coverUrlMaxSize = null,
    )

internal fun AudibleSeriesEntry.toMetadataSeriesRef(): MetadataSeriesRef =
    MetadataSeriesRef(
        asin = asin.takeIf { it.isNotBlank() },
        title = name,
        sequence = position.takeIf { it.isNotBlank() },
    )

internal fun AudibleChapter.toMetadataChapter(): MetadataChapter =
    MetadataChapter(title = title, startMs = startMs, lengthMs = durationMs)
