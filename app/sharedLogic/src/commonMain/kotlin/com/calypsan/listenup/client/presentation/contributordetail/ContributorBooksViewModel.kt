package com.calypsan.listenup.client.presentation.contributordetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.repository.ContributorRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.util.calculateProgressMap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import com.calypsan.listenup.client.domain.repository.BookWithContributorRole
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/**
 * ViewModel for the Contributor Books screen (View All).
 *
 * Shows all books for a contributor in a specific role, grouped by series.
 * Series appear first (alphabetically), followed by standalone books.
 *
 * The screen supplies `(contributorId, role)` via [loadBooks]; the flow
 * pipeline uses `flatMapLatest` to swap upstream sources when the request
 * changes, and `.stateIn(WhileSubscribed)` so the pipeline is only hot
 * while the screen is observing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContributorBooksViewModel(
    private val contributorRepository: ContributorRepository,
    private val playbackPositionRepository: PlaybackPositionRepository,
) : ViewModel() {
    private data class LoadRequest(
        val contributorId: String,
        val role: String,
    )

    private val request = MutableStateFlow<LoadRequest?>(null)

    val state: StateFlow<ContributorBooksUiState> =
        request
            .flatMapLatest { req ->
                if (req == null) {
                    flowOf(ContributorBooksUiState.Idle)
                } else {
                    combine(
                        contributorRepository.observeById(req.contributorId).filterNotNull(),
                        contributorRepository.observeBooksForContributorRole(req.contributorId, req.role),
                    ) { contributor, booksWithRole ->
                        val ready: ContributorBooksUiState = buildReadyState(contributor.name, req.role, booksWithRole)
                        ready
                    }.onStart { emit(ContributorBooksUiState.Loading) }
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = ContributorBooksUiState.Idle,
            )

    /** Set the (contributor, role) pair to observe. Safe to call repeatedly. */
    fun loadBooks(
        contributorId: String,
        role: String,
    ) {
        request.value = LoadRequest(contributorId, role)
    }

    private suspend fun buildReadyState(
        contributorName: String,
        role: String,
        booksWithRole: List<BookWithContributorRole>,
    ): ContributorBooksUiState.Ready {
        val books = booksWithRole.map { it.book }
        val bookProgress = playbackPositionRepository.calculateProgressMap(books)

        val bookCreditedAs =
            booksWithRole
                .mapNotNull { bwr ->
                    val creditedAs = bwr.creditedAs
                    if (creditedAs != null && !creditedAs.equals(contributorName, ignoreCase = true)) {
                        bwr.book.id.value to creditedAs
                    } else {
                        null
                    }
                }.toMap()

        val seriesGroups =
            books
                .mapNotNull { b -> b.seriesName?.let { b to it } }
                .groupBy({ it.second }) { it.first }
                .map { (seriesName, seriesBooks) ->
                    SeriesGroup(
                        seriesName = seriesName,
                        books =
                            seriesBooks.sortedBy {
                                it.seriesSequence?.toFloatOrNull() ?: Float.MAX_VALUE
                            },
                    )
                }.sortedBy { it.seriesName }

        val standaloneBooks =
            books
                .filter { it.seriesName == null }
                .sortedBy { it.title }

        return ContributorBooksUiState.Ready(
            contributorName = contributorName,
            roleDisplayName = ContributorDetailViewModel.roleToDisplayName(role),
            seriesGroups = seriesGroups,
            standaloneBooks = standaloneBooks,
            bookProgress = bookProgress,
            bookCreditedAs = bookCreditedAs,
        )
    }
}

/**
 * UI state for the Contributor Books screen.
 */
sealed interface ContributorBooksUiState {
    /** No request yet (pre-[ContributorBooksViewModel.loadBooks]). */
    data object Idle : ContributorBooksUiState

    /** Upstream has not yet produced data for the requested (contributor, role). */
    data object Loading : ContributorBooksUiState

    /** Books loaded and grouped. */
    data class Ready(
        val contributorName: String,
        val roleDisplayName: String,
        val seriesGroups: List<SeriesGroup>,
        val standaloneBooks: List<BookListItem>,
        val bookProgress: Map<BookId, Float>,
        /** Maps bookId to creditedAs name when different from contributor's name. */
        val bookCreditedAs: Map<String, String>,
    ) : ContributorBooksUiState {
        val totalBooks: Int
            get() = seriesGroups.sumOf { it.books.size } + standaloneBooks.size

        val hasStandaloneBooks: Boolean
            get() = standaloneBooks.isNotEmpty()
    }

    /** Load failed. */
    data class Error(
        val message: String,
    ) : ContributorBooksUiState
}

/**
 * A group of books belonging to the same series.
 */
data class SeriesGroup(
    val seriesName: String,
    val books: List<BookListItem>,
)
