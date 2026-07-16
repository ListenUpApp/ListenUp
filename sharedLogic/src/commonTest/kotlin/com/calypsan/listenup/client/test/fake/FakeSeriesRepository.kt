package com.calypsan.listenup.client.test.fake

import com.calypsan.listenup.client.domain.model.Series
import com.calypsan.listenup.client.domain.model.SeriesSearchResponse
import com.calypsan.listenup.client.domain.model.SeriesWithBooks
import com.calypsan.listenup.client.domain.repository.SeriesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory fake of [SeriesRepository]. Backed by a [MutableStateFlow] keyed by series id, so
 * `observeSeriesWithBooks` re-emits on every [setSeriesWithBooks] call.
 *
 * Only the surface exercised by presentation-layer tests today is meaningfully implemented;
 * the rest returns empty/no-op results.
 */
class FakeSeriesRepository(
    initialSeries: Map<String, SeriesWithBooks> = emptyMap(),
) : SeriesRepository {
    private val seriesWithBooksById = MutableStateFlow(initialSeries)

    override fun observeAll(): Flow<List<Series>> =
        seriesWithBooksById.asStateFlow().map { it.values.map { s -> s.series } }

    override fun observeById(id: String): Flow<Series?> = seriesWithBooksById.asStateFlow().map { it[id]?.series }

    override suspend fun getById(id: String): Series? = seriesWithBooksById.value[id]?.series

    override fun observeByBookId(bookId: String): Flow<Series?> =
        seriesWithBooksById.asStateFlow().map { map ->
            map.values.firstOrNull { swb -> swb.books.any { it.id.value == bookId } }?.series
        }

    override suspend fun getBookIdsForSeries(seriesId: String): List<String> =
        seriesWithBooksById.value[seriesId]
            ?.books
            ?.map { it.id.value }
            .orEmpty()

    override fun observeBookIdsForSeries(seriesId: String): Flow<List<String>> =
        seriesWithBooksById.asStateFlow().map { map -> map[seriesId]?.books?.map { it.id.value }.orEmpty() }

    override fun observeAllWithBooks(): Flow<List<SeriesWithBooks>> =
        seriesWithBooksById.asStateFlow().map { it.values.toList() }

    override fun observeSeriesWithBooks(seriesId: String): Flow<SeriesWithBooks?> =
        seriesWithBooksById.asStateFlow().map { it[seriesId] }

    override suspend fun searchSeries(
        query: String,
        limit: Int,
    ): SeriesSearchResponse = SeriesSearchResponse(series = emptyList(), isOfflineResult = false, tookMs = 0L)

    /** Test helper: set (or replace) [seriesId]'s series-with-books snapshot, emitting to all observers. */
    fun setSeriesWithBooks(
        seriesId: String,
        seriesWithBooks: SeriesWithBooks?,
    ) {
        seriesWithBooksById.value =
            if (seriesWithBooks == null) {
                seriesWithBooksById.value - seriesId
            } else {
                seriesWithBooksById.value + (seriesId to seriesWithBooks)
            }
    }
}
