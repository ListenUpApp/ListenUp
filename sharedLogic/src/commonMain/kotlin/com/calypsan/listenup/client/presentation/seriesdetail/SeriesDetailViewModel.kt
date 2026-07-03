package com.calypsan.listenup.client.presentation.seriesdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.PlaybackPosition
import com.calypsan.listenup.client.domain.model.Series
import com.calypsan.listenup.client.domain.model.SeriesWithBooks
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.domain.repository.SeriesRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * ViewModel for the Series Detail screen.
 *
 * Observes series data reactively via `observeSeriesWithBooks` so the UI
 * tracks sync-driven updates without re-loading. The screen supplies the
 * series id via [loadSeries]; the flow pipeline uses `flatMapLatest` to
 * swap the upstream when the id changes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SeriesDetailViewModel(
    private val seriesRepository: SeriesRepository,
    private val imageRepository: ImageRepository,
    private val playbackPositionRepository: PlaybackPositionRepository,
) : ViewModel() {
    private val seriesIdFlow = MutableStateFlow<String?>(null)

    val state: StateFlow<SeriesDetailUiState> =
        seriesIdFlow
            .flatMapLatest { id ->
                if (id == null) {
                    flowOf(SeriesDetailUiState.Idle)
                } else {
                    combine(
                        seriesRepository.observeSeriesWithBooks(id),
                        playbackPositionRepository.observeAll(),
                    ) { seriesWithBooks, positions ->
                        if (seriesWithBooks != null) {
                            buildReadyState(id, seriesWithBooks, positions)
                        } else {
                            SeriesDetailUiState.Error("Series not found")
                        }
                    }.onStart { emit(SeriesDetailUiState.Loading) }
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = SeriesDetailUiState.Idle,
            )

    /** Set the series to observe. Safe to call repeatedly with the same id. */
    fun loadSeries(seriesId: String) {
        seriesIdFlow.value = seriesId
    }

    /**
     * Builds the [SeriesDetailUiState.Ready] projection, folding live playback
     * [positions] into per-book progress, the finished set, and the resume target.
     */
    private fun buildReadyState(
        seriesId: String,
        seriesWithBooks: SeriesWithBooks,
        positions: Map<BookId, PlaybackPosition>,
    ): SeriesDetailUiState.Ready {
        val books = seriesWithBooks.booksSortedBySequence()
        val totalDuration = books.sumOf { it.duration }.milliseconds

        val finishedBookIds = mutableSetOf<BookId>()
        val bookProgress = mutableMapOf<BookId, Float>()
        books.forEach { book ->
            val position = positions[book.id]
            val fraction =
                if (position != null && book.duration > 0) {
                    (position.positionMs.toFloat() / book.duration).coerceIn(0f, 1f)
                } else {
                    0f
                }
            when {
                position?.isFinished == true || fraction >= FINISHED_THRESHOLD -> finishedBookIds += book.id
                fraction > 0f -> bookProgress[book.id] = fraction
            }
        }

        // Resume the first in-progress book; otherwise start the first unfinished
        // (unstarted) book; null when the whole series is finished.
        val resumeTarget =
            books.firstOrNull { bookProgress.containsKey(it.id) }?.id
                ?: books.firstOrNull { it.id !in finishedBookIds }?.id

        return SeriesDetailUiState.Ready(
            seriesId = seriesId,
            seriesName = seriesWithBooks.series.name,
            seriesDescription = seriesWithBooks.series.description,
            // Every author across the whole series, deduped by id in first-appearance order, so a
            // multi-author series (Wheel of Time) or anthology surfaces all of them — not just the
            // first book's first author.
            seriesAuthors =
                books
                    .flatMap { it.authors }
                    .distinctBy { it.id },
            seriesNarrator =
                books
                    .firstOrNull()
                    ?.narrators
                    ?.firstOrNull()
                    ?.name,
            coverPath = resolveCoverPath(seriesWithBooks.series, seriesId, books),
            featuredBookId = books.firstOrNull()?.id?.value,
            totalDuration = totalDuration,
            books = books,
            bookProgress = bookProgress,
            finishedBookIds = finishedBookIds,
            resumeTarget = resumeTarget,
        )
    }

    /**
     * Resolves the cover path to display on the series detail hero.
     *
     * Priority:
     * 1. Local disk file (fastest — already downloaded)
     * 2. Server-side canonical [Series.coverPath] (set when metadata was applied)
     * 3. First book's cover as a visual fallback
     */
    private fun resolveCoverPath(
        series: Series,
        seriesId: String,
        books: List<BookListItem>,
    ): String? {
        if (imageRepository.seriesCoverExists(seriesId)) {
            return imageRepository.getSeriesCoverPath(seriesId)
        }
        return series.coverPath ?: books.firstOrNull()?.coverPath
    }

    private companion object {
        /** Progress fraction at or above which a book counts as finished. */
        const val FINISHED_THRESHOLD = 0.99f
    }
}

/**
 * UI state for the Series Detail screen.
 */
sealed interface SeriesDetailUiState {
    /** No series selected (pre-[SeriesDetailViewModel.loadSeries]). */
    data object Idle : SeriesDetailUiState

    /** Upstream has not yet produced data for the selected series. */
    data object Loading : SeriesDetailUiState

    /** Series and books loaded. */
    data class Ready(
        val seriesId: String,
        val seriesName: String,
        val seriesDescription: String?,
        /**
         * Every author across the series' books, deduped by id in first-appearance order.
         * Empty when no book has an author. The UI shows a collapsed summary that opens a sheet
         * listing all of them.
         */
        val seriesAuthors: List<BookContributor>,
        /** Primary narrator for the series, derived from its books. Null when unknown. */
        val seriesNarrator: String?,
        val coverPath: String?,
        val featuredBookId: String?,
        val totalDuration: Duration,
        val books: List<BookListItem>,
        /** Per-book listening progress (0..1) for in-progress books only. */
        val bookProgress: Map<BookId, Float>,
        /** Books the user has finished. */
        val finishedBookIds: Set<BookId>,
        /** Book to resume/start via the "Continue" action; null when all are finished. */
        val resumeTarget: BookId?,
    ) : SeriesDetailUiState {
        /** Number of finished books, for the hero "X finished" stat. */
        val finishedCount: Int get() = finishedBookIds.size

        fun formatTotalDuration(): String = DurationFormatter.hoursMinutes(totalDuration)
    }

    /** Load failed. */
    data class Error(
        val message: String,
    ) : SeriesDetailUiState
}
