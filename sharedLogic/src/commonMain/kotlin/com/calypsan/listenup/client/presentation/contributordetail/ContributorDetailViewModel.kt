package com.calypsan.listenup.client.presentation.contributordetail

import com.calypsan.listenup.api.result.AppResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.Contributor
import com.calypsan.listenup.client.domain.model.ContributorRole
import com.calypsan.listenup.client.domain.model.RoleWithBookCount
import com.calypsan.listenup.client.domain.model.SeriesWithBooks
import com.calypsan.listenup.client.domain.repository.BookWithContributorRole
import com.calypsan.listenup.client.domain.repository.ContributorRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.domain.repository.SeriesRepository
import com.calypsan.listenup.client.domain.usecase.contributor.DeleteContributorUseCase
import com.calypsan.listenup.client.util.calculateProgressMap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * ViewModel for the Contributor Detail screen.
 *
 * Observes contributor info and their per-role book previews via
 * `combine(...).stateIn(WhileSubscribed)`. The delete flow runs imperatively
 * and surfaces its state via a private [DeleteOverlay] combined into the
 * main pipeline — success emits a `NavAction.Deleted` through a nav Channel,
 * failure projects into `Ready.deleteError` for snackbar rendering.
 *
 * N+1 query on per-role book previews — known issue; do not fix here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContributorDetailViewModel(
    private val contributorRepository: ContributorRepository,
    private val playbackPositionRepository: PlaybackPositionRepository,
    private val seriesRepository: SeriesRepository,
    private val deleteContributorUseCase: DeleteContributorUseCase,
) : ViewModel() {
    private val contributorIdFlow = MutableStateFlow<String?>(null)
    private val deleteOverlay = MutableStateFlow<DeleteOverlay>(DeleteOverlay.None)

    private sealed interface DeleteOverlay {
        data object None : DeleteOverlay

        data object Deleting : DeleteOverlay

        /** Last delete attempt failed; [message] surfaces in the UI until dismissed. */
        data class Failed(
            val message: String,
        ) : DeleteOverlay
    }

    private val dataState: Flow<ContributorDetailUiState> =
        contributorIdFlow.flatMapLatest { id ->
            if (id == null) {
                flowOf(ContributorDetailUiState.Idle)
            } else {
                observeReadyState(id).onStart { emit(ContributorDetailUiState.Loading) }
            }
        }

    /**
     * Fully reactive Ready-state pipeline: the contributor, its per-role book lists, and the derived
     * series are all LIVE Room subscriptions. Removing this contributor from a book updates the
     * `book_contributors` mirror, which re-emits on [ContributorRepository.observeBooksForContributorRole]
     * and refreshes the list here — no re-navigation required (Finding #21). Mirrors the healthy
     * `ContributorBooksViewModel`; the earlier one-shot `.first()` snapshots were the stale-read bug.
     */
    private fun observeReadyState(contributorId: String): Flow<ContributorDetailUiState> =
        combine(
            contributorRepository.observeById(contributorId).filterNotNull(),
            contributorRepository.observeRolesWithCountForContributor(contributorId),
        ) { contributor, rolesWithCount -> contributor to rolesWithCount }
            .flatMapLatest { (contributor, rolesWithCount) ->
                observeRoleBooks(contributorId, rolesWithCount).flatMapLatest { roleBooks ->
                    observeSeries(roleBooks).map { series ->
                        buildReadyState(contributor, roleBooks, series)
                    }
                }
            }

    /** Live per-role book lists, one live subscription per role, combined into a single emission. */
    private fun observeRoleBooks(
        contributorId: String,
        rolesWithCount: List<RoleWithBookCount>,
    ): Flow<List<RoleBooks>> {
        if (rolesWithCount.isEmpty()) return flowOf(emptyList())
        val perRole =
            rolesWithCount.map { roleWithCount ->
                contributorRepository
                    .observeBooksForContributorRole(contributorId, roleWithCount.role)
                    .map { books -> RoleBooks(roleWithCount, books) }
            }
        return combine(perRole) { it.toList() }
    }

    /** Live series subscriptions derived from the current [roleBooks], ordered by contributor presence. */
    private fun observeSeries(roleBooks: List<RoleBooks>): Flow<List<SeriesWithBooks>> {
        val distinctBooks = roleBooks.flatMap { it.booksWithRole }.map { it.book }.distinctBy { it.id }
        val seriesIds = distinctBooks.flatMap { it.series }.map { it.seriesId }.distinct()
        if (seriesIds.isEmpty()) return flowOf(emptyList())
        val flows = seriesIds.map { seriesRepository.observeSeriesWithBooks(it) }
        return combine(flows) { emitted ->
            emitted
                .filterNotNull()
                .sortedWith(
                    compareByDescending<SeriesWithBooks> { sb ->
                        distinctBooks.count { b -> b.series.any { it.seriesId == sb.series.id.value } }
                    }.thenBy { it.series.name },
                )
        }
    }

    /** A role paired with its currently-observed books. */
    private data class RoleBooks(
        val roleWithCount: RoleWithBookCount,
        val booksWithRole: List<BookWithContributorRole>,
    )

    val state: StateFlow<ContributorDetailUiState> =
        combine(dataState, deleteOverlay) { data, overlay ->
            if (data is ContributorDetailUiState.Ready) {
                data.copy(
                    isDeleting = overlay is DeleteOverlay.Deleting,
                    deleteError = (overlay as? DeleteOverlay.Failed)?.message,
                )
            } else {
                data
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ContributorDetailUiState.Idle,
        )

    private val _navActions = Channel<ContributorDetailNavAction>(Channel.BUFFERED)
    val navActions: Flow<ContributorDetailNavAction> = _navActions.receiveAsFlow()

    /** Set the contributor to observe. Safe to call repeatedly with the same id. */
    fun loadContributor(contributorId: String) {
        contributorIdFlow.value = contributorId
    }

    /** Confirm deletion of the currently-loaded contributor. */
    fun confirmDelete() {
        val contributorId = contributorIdFlow.value ?: return
        viewModelScope.launch {
            deleteOverlay.value = DeleteOverlay.Deleting

            when (val result = deleteContributorUseCase(contributorId)) {
                is AppResult.Success -> {
                    deleteOverlay.value = DeleteOverlay.None
                    _navActions.trySend(ContributorDetailNavAction.Deleted)
                }

                is AppResult.Failure -> {
                    deleteOverlay.value = DeleteOverlay.Failed(result.message)
                }
            }
        }
    }

    /** Dismiss a delete error shown in the Ready state. */
    fun dismissDeleteError() {
        deleteOverlay.update { if (it is DeleteOverlay.Failed) DeleteOverlay.None else it }
    }

    private suspend fun buildReadyState(
        contributor: Contributor,
        roleBooks: List<RoleBooks>,
        series: List<SeriesWithBooks>,
    ): ContributorDetailUiState.Ready {
        val allCreditedAs = mutableMapOf<String, String>()
        val roleBooksFull = mutableListOf<List<BookListItem>>()

        val roleSections =
            roleBooks.map { (roleWithCount, booksWithRole) ->
                val books = booksWithRole.map { it.book }
                booksWithRole.forEach { bwr ->
                    val creditedAs = bwr.creditedAs
                    if (creditedAs != null && !creditedAs.equals(contributor.name, ignoreCase = true)) {
                        allCreditedAs[bwr.book.id.value] = creditedAs
                    }
                }
                roleBooksFull += books
                RoleSection(
                    role = roleWithCount.role,
                    displayName = roleToDisplayName(roleWithCount.role),
                    bookCount = roleWithCount.bookCount,
                    previewBooks = books.take(PREVIEW_BOOK_COUNT),
                )
            }

        val distinctBooks = roleBooksFull.flatten().distinctBy { it.id }
        val totalDuration = distinctBooks.sumOf { it.duration }.milliseconds

        val allPreviewBooks = roleSections.flatMap { it.previewBooks }
        val bookProgress = playbackPositionRepository.calculateProgressMap(allPreviewBooks)

        return ContributorDetailUiState.Ready(
            contributor = contributor,
            roleSections = roleSections,
            bookProgress = bookProgress,
            bookCreditedAs = allCreditedAs,
            series = series,
            bookCount = distinctBooks.size,
            totalDuration = totalDuration,
            isDeleting = false,
            deleteError = null,
        )
    }

    companion object {
        /** Number of books to show in the horizontal preview. */
        private const val PREVIEW_BOOK_COUNT = 10

        /** Threshold for showing "View All" button. */
        const val VIEW_ALL_THRESHOLD = 6

        /** Convert a role string to a user-friendly display name. */
        fun roleToDisplayName(role: String): String =
            when (role.lowercase()) {
                ContributorRole.AUTHOR.apiValue -> "Written By"
                ContributorRole.NARRATOR.apiValue -> "Narrated By"
                ContributorRole.TRANSLATOR.apiValue -> "Translated By"
                ContributorRole.EDITOR.apiValue -> "Edited By"
                else -> role.replaceFirstChar { it.uppercase() }
            }
    }
}

/**
 * UI state for the Contributor Detail screen.
 */
sealed interface ContributorDetailUiState {
    /** No contributor selected (pre-[ContributorDetailViewModel.loadContributor]). */
    data object Idle : ContributorDetailUiState

    /** Upstream has not yet produced data for the selected contributor. */
    data object Loading : ContributorDetailUiState

    /** Contributor loaded with role sections and per-book progress. */
    data class Ready(
        val contributor: Contributor,
        val roleSections: List<RoleSection>,
        val bookProgress: Map<BookId, Float>,
        /** Maps bookId to creditedAs name when different from contributor's name. */
        val bookCreditedAs: Map<String, String>,
        /** Series this contributor's books belong to (full series, for the grid). */
        val series: List<SeriesWithBooks>,
        /** Distinct book count across all roles (a book counts once even if author+narrator). */
        val bookCount: Int,
        /** Total duration of the distinct books, for the "Hours" stat. */
        val totalDuration: Duration,
        /** True while a delete is in flight. Screen shows an overlay spinner. */
        val isDeleting: Boolean,
        /** Non-null when the last delete attempt failed. Screen shows a snackbar. */
        val deleteError: String?,
    ) : ContributorDetailUiState {
        /** Formats the total duration as "${hours}h ${minutes}m" or "${minutes}m". */
        fun formatTotalDuration(): String = DurationFormatter.hoursMinutes(totalDuration)
    }

    /** Load failed. */
    data class Error(
        val message: String,
    ) : ContributorDetailUiState
}

/** Navigation events emitted by [ContributorDetailViewModel]. */
sealed interface ContributorDetailNavAction {
    /** Contributor was deleted — the screen should pop back. */
    data object Deleted : ContributorDetailNavAction
}

/**
 * A section displaying books for a specific role.
 */
data class RoleSection(
    val role: String,
    val displayName: String,
    val bookCount: Int,
    val previewBooks: List<BookListItem>,
) {
    /** Whether to show "View All" button. */
    val showViewAll: Boolean
        get() = bookCount > ContributorDetailViewModel.VIEW_ALL_THRESHOLD
}
