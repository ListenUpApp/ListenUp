package com.calypsan.listenup.client.domain.usecase.series

import com.calypsan.listenup.client.domain.model.NextInSeries
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.InboxRepository
import com.calypsan.listenup.client.domain.repository.SeriesRepository
import com.calypsan.listenup.client.core.formatSeriesSequence
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * The next book in [bookId]'s first series, or null: the one derivation Home and Now Playing share (Home P-R4,
 * player spec §3.7). A held next book counts as none (S11): Play next book must always be playable. Room only,
 * so it answers offline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObserveNextInSeriesUseCase(
    private val bookRepository: BookRepository,
    private val seriesRepository: SeriesRepository,
    private val inboxRepository: InboxRepository,
) {
    operator fun invoke(bookId: String): Flow<NextInSeries?> =
        bookRepository
            .observeBookDetail(bookId)
            .map { it?.series?.firstOrNull()?.seriesId }
            .distinctUntilChanged()
            .flatMapLatest { seriesId ->
                if (seriesId == null) {
                    flowOf(null)
                } else {
                    combine(
                        seriesRepository.observeSeriesWithBooks(seriesId),
                        inboxRepository.observeHeldBookIds(),
                    ) { series, held ->
                        val next = series?.bookAfter(bookId) ?: return@combine null
                        if (next.id in held) return@combine null
                        NextInSeries(
                            bookId = next.id.value,
                            title = next.title,
                            coverPath = next.coverPath,
                            coverHash = next.coverHash,
                            narratorNames = next.narrators.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.name },
                            seriesName = series.series.name,
                            sequenceLabel = series.sequenceFor(next.id.value)?.let(::formatSeriesSequence),
                            durationMs = next.duration,
                        )
                    }
                }
            }.distinctUntilChanged()
}
