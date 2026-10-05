package com.calypsan.listenup.client.presentation.seriesdetail

import com.calypsan.listenup.domain.FinishedPolicy
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.PlaybackPosition
import com.calypsan.listenup.client.domain.model.Series
import com.calypsan.listenup.client.domain.model.SeriesLineage
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
 * Observes series data reactively via `observeSeriesWithBooks` and
 * `observeSeriesLineage` so the UI tracks sync-driven updates without re-loading. The screen supplies the
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
                        seriesRepository.observeSeriesLineage(id),
                        playbackPositionRepository.observeAll(),
                    ) { seriesWithBooks, lineage, positions ->
                        if (seriesWithBooks != null) {
                            buildReadyState(id, seriesWithBooks, lineage, positions)
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
        lineage: SeriesLineage,
        positions: Map<BookId, PlaybackPosition>,
    ): SeriesDetailUiState.Ready {
        // A series with sub-series shows its whole subtree, already in series order; a flat
        // series keeps its own books by sequence, exactly as before the hierarchy existed.
        val books =
            if (lineage.children.isEmpty()) seriesWithBooks.booksSortedBySequence() else lineage.subtreeBooks
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
                FinishedPolicy.isFinished(position?.positionMs ?: 0L, book.duration, position?.isFinished == true) -> {
                    finishedBookIds += book.id
                }

                fraction > 0f -> {
                    bookProgress[book.id] = fraction
                }
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
            ancestors = lineage.ancestors.map { SeriesCrumb(id = it.id.value, name = it.name) },
            childSeries =
                lineage.children.map { child ->
                    ChildSeriesUi(
                        id = child.series.id.value,
                        name = child.series.name,
                        coverPath = child.series.coverPath,
                        bookCount = child.bookIds.size,
                        finishedCount = child.bookIds.count { BookId(it) in finishedBookIds },
                    )
                },
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
        /** The series above this one, root first — the breadcrumb. Empty for a root. */
        val ancestors: List<SeriesCrumb> = emptyList(),
        /** The direct sub-series, in sibling order. Empty for a series with none. */
        val childSeries: List<ChildSeriesUi> = emptyList(),
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

/** One step of a series' breadcrumb. */
data class SeriesCrumb(
    val id: String,
    val name: String,
)

/**
 * One sub-series as the series page lists it.
 *
 * @property bookCount every book in the sub-series and its own sub-series.
 * @property finishedCount how many of those the user has finished.
 */
data class ChildSeriesUi(
    val id: String,
    val name: String,
    val coverPath: String?,
    val bookCount: Int,
    val finishedCount: Int,
)
