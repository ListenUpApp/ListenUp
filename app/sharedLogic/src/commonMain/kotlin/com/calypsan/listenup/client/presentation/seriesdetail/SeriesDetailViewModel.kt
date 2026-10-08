package com.calypsan.listenup.client.presentation.seriesdetail

import com.calypsan.listenup.api.dto.auth.Permission
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
import com.calypsan.listenup.client.domain.model.SeriesChild
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.client.domain.repository.SeriesEditRepository
import com.calypsan.listenup.client.domain.repository.PermissionsRepository
import com.calypsan.listenup.client.presentation.seriesedit.AddSubSeriesEvent
import com.calypsan.listenup.client.presentation.seriesedit.AddSubSeriesUiState
import com.calypsan.listenup.client.presentation.seriesedit.SubSeriesAdder
import com.calypsan.listenup.core.error.ErrorBus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.update
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
    private val permissionsRepository: PermissionsRepository,
    private val networkMonitor: NetworkMonitor,
    seriesEditRepository: SeriesEditRepository,
    errorBus: ErrorBus,
) : ViewModel() {
    private val seriesIdFlow = MutableStateFlow<String?>(null)

    /** The reader's own expand/collapse choices on this page — series id to expanded. */
    private val expandOverrides = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /** Whether the reader may change the hierarchy (Edit metadata), and whether the server can be reached to do it. */
    private val hierarchyAccess: Flow<Pair<Boolean, Boolean>> =
        combine(
            permissionsRepository.observeCan(Permission.EDIT_METADATA),
            networkMonitor.isOnlineFlow,
        ) { canEdit, online -> canEdit to online }

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
                        expandOverrides,
                        hierarchyAccess,
                    ) { seriesWithBooks, lineage, positions, overrides, (canEdit, online) ->
                        if (seriesWithBooks != null) {
                            buildReadyState(id, seriesWithBooks, lineage, positions, overrides)
                                .copy(canEditHierarchy = canEdit, isOnline = online)
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
        if (seriesIdFlow.value != seriesId) expandOverrides.value = emptyMap()
        seriesIdFlow.value = seriesId
    }

    /**
     * Fold or unfold the sub-series [seriesId]'s group of books. The reader's choice beats the
     * default (a finished sub-series starts folded) for as long as the page is open.
     */
    fun toggleSection(seriesId: String) {
        val current =
            (state.value as? SeriesDetailUiState.Ready)?.bookSections?.firstOrNull {
                it.seriesId == seriesId &&
                    it.isCollapsible
            }
        val expandedNow = current?.isCollapsed == false
        expandOverrides.update { it + (seriesId to !expandedNow) }
    }

    private val subSeries =
        SubSeriesAdder(
            scope = viewModelScope,
            errorBus = errorBus,
            hierarchy = seriesRepository.observeHierarchy(),
            parentId = { seriesIdFlow.value },
            createSeries = seriesEditRepository::createSeries,
            setParent = seriesEditRepository::setParent,
        )

    /** The "Add sub-series" sheet, opened from the page's tile (editors only). */
    val addSubSeries: StateFlow<AddSubSeriesUiState> = subSeries.state

    /** Handle the "Add sub-series" sheet's events. */
    fun onAddSubSeriesEvent(event: AddSubSeriesEvent) = subSeries.onEvent(event)

    /**
     * Builds the [SeriesDetailUiState.Ready] projection, folding live playback
     * [positions] into per-book progress, the finished set, and the resume target.
     */
    private fun buildReadyState(
        seriesId: String,
        seriesWithBooks: SeriesWithBooks,
        lineage: SeriesLineage,
        positions: Map<BookId, PlaybackPosition>,
        overrides: Map<String, Boolean>,
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

        val allSections =
            seriesBookSections(
                pageId = seriesId,
                pageName = seriesWithBooks.series.name,
                lineage = lineage,
                flatBooks = books,
                booksById = books.associateBy { it.id.value },
                finishedBookIds = finishedBookIds,
                // Every group open: the resume book is looked up across all of them, folded or not.
                expandOverrides = lineage.allSeriesIds().associateWith { true },
            )

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
            resumeBook =
                resumeTarget?.let { target ->
                    resumeBookUi(
                        target = target,
                        sections = allSections,
                        hasStarted = bookProgress.isNotEmpty() || finishedBookIds.isNotEmpty(),
                    )
                },
            ancestors = lineage.ancestors.map { SeriesCrumb(id = it.id.value, name = it.name) },
            childSeries =
                lineage.children.map { child ->
                    ChildSeriesUi(
                        id = child.series.id.value,
                        name = child.series.name,
                        coverPath =
                            child.series.coverPath
                                ?: child.bookIds.firstNotNullOfOrNull { id ->
                                    books.firstOrNull { it.id.value == id }?.coverPath
                                },
                        bookCount = child.bookIds.size,
                        finishedCount = child.bookIds.count { BookId(it) in finishedBookIds },
                        subSeriesCount = child.children.size,
                    )
                },
            bookSections =
                seriesBookSections(
                    pageId = seriesId,
                    pageName = seriesWithBooks.series.name,
                    lineage = lineage,
                    flatBooks = books,
                    booksById = books.associateBy { it.id.value },
                    finishedBookIds = finishedBookIds,
                    expandOverrides = overrides,
                ),
        )
    }

    /**
     * The resume book with the series it is listed under on this page, for the Continue button.
     * [hasStarted] is the whole page's: any book begun or finished anywhere in it.
     */
    private fun resumeBookUi(
        target: BookId,
        sections: List<SeriesBookSection>,
        hasStarted: Boolean,
    ): SeriesResumeUi? {
        val section = sections.firstOrNull { section -> section.books.any { it.id == target } } ?: return null
        val book = section.books.first { it.id == target }
        return SeriesResumeUi(
            bookId = target.value,
            title = book.title,
            seriesName = section.title,
            sequence = book.series.firstOrNull { it.seriesId == section.seriesId }?.sequenceLabel,
            hasStarted = hasStarted,
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
        /**
         * The book list's headings, in reading order. On a page with sub-series the books are grouped
         * under each sub-series and the page's own books come last; a flat page has one section,
         * shown without a heading.
         */
        val bookSections: List<SeriesBookSection> = emptyList(),
        /** The book Continue resumes, with the series it is listed under; null when all are finished. */
        val resumeBook: SeriesResumeUi? = null,
        /** Whether the reader may change the hierarchy (Edit metadata; admins always may). */
        val canEditHierarchy: Boolean = false,
        /** Whether the device has a network route; hierarchy changes need the server. */
        val isOnline: Boolean = true,
    ) : SeriesDetailUiState {
        /** True when the page lists sub-series — its books are grouped, and Continue names the book. */
        val isGrouped: Boolean get() = childSeries.isNotEmpty()

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
    /** How many series sit directly inside this one — the card's "2 series" hint. */
    val subSeriesCount: Int = 0,
) {
    /** Every book finished. */
    val isFinished: Boolean get() = bookCount > 0 && finishedCount == bookCount

    /** No book started or finished — "Not started". */
    val isNotStarted: Boolean get() = finishedCount == 0
}

/**
 * The book the Continue button resumes. On a page with sub-series the button names the book and
 * where it sits — "Continue The Hero of Ages", "Mistborn Era 1 · Book 3" — because "Continue Book 3"
 * would be ambiguous across four series.
 *
 * [hasStarted] is the one word every platform's button takes its verb from, so they cannot drift:
 * false reads en.json's `series.start_title` ("Start The Final Empire"), true `series.continue_title`
 * ("Continue The Hero of Ages"). The series line underneath is the same either way.
 *
 * @property sequence the book's number in [seriesName], formatted; null when unnumbered.
 * @property hasStarted true once any book on the page is begun or finished; false on an unstarted series.
 */
data class SeriesResumeUi(
    val bookId: String,
    val title: String,
    val seriesName: String,
    val sequence: String?,
    val hasStarted: Boolean,
)

/** Every series id below the page, for opening every group at once. */
private fun SeriesLineage.allSeriesIds(): List<String> {
    fun walk(children: List<SeriesChild>): List<String> =
        children.flatMap {
            listOf(it.series.id.value) +
                walk(it.children)
        }
    return walk(children)
}
