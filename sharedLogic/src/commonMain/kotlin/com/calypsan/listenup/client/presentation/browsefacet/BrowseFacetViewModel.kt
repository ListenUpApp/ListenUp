package com.calypsan.listenup.client.presentation.browsefacet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.FacetKind
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.MoodRepository
import com.calypsan.listenup.client.domain.repository.TagRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/**
 * ViewModel for the facet-browse screen — every book carrying a given flat facet (a [FacetKind.Tag]
 * or a [FacetKind.Mood]).
 *
 * Books-by-facet is a **local Room query**, not an RPC: the `book_tags` / `book_moods` junctions are
 * synced into Room, so the facet name and its book set both come from offline-first observations.
 * The pipeline observes the facet (by id) and its junction book-ids, then reactively joins those
 * ids to [BookListItem] projections via [BookRepository.observeBookListItems] — so the grid tracks
 * SSE- and sync-driven changes without re-loading.
 *
 * One ViewModel serves both axes; the screen supplies the [FacetKind] and id via [load]. The flow
 * uses `flatMapLatest` to swap upstream sources when the request changes, and
 * `.stateIn(WhileSubscribed)` so the pipeline is only hot while the screen observes.
 *
 * Mirrors the reactive shape of
 * [com.calypsan.listenup.client.presentation.tagdetail.TagDetailViewModel] generalized over both
 * flat facets; genres keep their own hierarchy-aware
 * [com.calypsan.listenup.client.presentation.browsegenre.BrowseGenreViewModel].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowseFacetViewModel(
    private val tagRepository: TagRepository,
    private val moodRepository: MoodRepository,
    private val bookRepository: BookRepository,
) : ViewModel() {
    private val request = MutableStateFlow<FacetRequest?>(null)

    val state: StateFlow<BrowseFacetUiState> =
        request
            .flatMapLatest { req ->
                if (req == null) {
                    flowOf(BrowseFacetUiState.Loading)
                } else {
                    observeFacet(req).onStart { emit(BrowseFacetUiState.Loading) }
                }
            }.distinctUntilChanged()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
                initialValue = BrowseFacetUiState.Loading,
            )

    /** Set the facet to browse. Safe to call repeatedly with the same arguments. */
    fun load(
        kind: FacetKind,
        facetId: String,
    ) {
        request.value = FacetRequest(kind, facetId)
    }

    private fun observeFacet(req: FacetRequest): Flow<BrowseFacetUiState> {
        val nameFlow = facetNameFlow(req)
        val bookIdsFlow =
            when (req.kind) {
                FacetKind.Tag -> tagRepository.observeBookIdsForTag(req.facetId)
                FacetKind.Mood -> moodRepository.observeBookIdsForMood(req.facetId)
            }

        return nameFlow.flatMapLatest { name ->
            if (name == null) {
                flowOf(BrowseFacetUiState.NotFound(req.kind))
            } else {
                bookIdsFlow.flatMapLatest { bookIds ->
                    bookRepository.observeBookListItems(bookIds).map { books ->
                        BrowseFacetUiState.Ready(
                            kind = req.kind,
                            facetName = name,
                            books = books.sortedBy { it.title },
                        )
                    }
                }
            }
        }
    }

    /** Resolve the facet's display name reactively, or null when the facet is absent/tombstoned. */
    private fun facetNameFlow(req: FacetRequest): Flow<String?> =
        when (req.kind) {
            FacetKind.Tag -> tagRepository.observeById(req.facetId).map { it?.displayName() }
            FacetKind.Mood -> moodRepository.observeById(req.facetId).map { it?.displayName() }
        }

    /** The facet currently being browsed. */
    private data class FacetRequest(
        val kind: FacetKind,
        val facetId: String,
    )

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}

/** Sealed UiState for the facet-browse screen. */
sealed interface BrowseFacetUiState {
    /** Upstream has not yet produced data for the requested facet. */
    data object Loading : BrowseFacetUiState

    /** The facet and its book set have loaded. */
    data class Ready(
        val kind: FacetKind,
        val facetName: String,
        val books: List<BookListItem>,
    ) : BrowseFacetUiState {
        /** Number of books carrying this facet. */
        val bookCount: Int get() = books.size

        /** Combined runtime of every book carrying this facet, in milliseconds. */
        val totalDurationMs: Long get() = books.sumOf { it.duration }
    }

    /** The facet id resolved to no live row (absent or tombstoned). */
    data class NotFound(
        val kind: FacetKind,
    ) : BrowseFacetUiState
}
