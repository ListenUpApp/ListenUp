package com.calypsan.listenup.client.presentation.browsegenre

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.domain.model.Genre
import com.calypsan.listenup.client.domain.repository.GenreRepository
import com.calypsan.listenup.client.presentation.error.userMessageFor
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.GenreId
import com.calypsan.listenup.core.error.ErrorBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}
private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L

/**
 * ViewModel for the Browse-by-Genre screen.
 *
 * Tree (categories list) comes from a Room observation on [GenreRepository.observeAll].
 * Selecting a genre fetches that genre's book ids via [GenreRepository.browseBooks]
 * (RPC) with an `includeDescendants` toggle that widens the fetch to the
 * genre's subtree using the materialized-path prefix match on the server.
 * The book-id list is then resolved to book aggregates by the screen via the
 * existing Room book observers — this ViewModel holds only the ids.
 *
 * Failures on the book fetch route through [ErrorBus] for the global snackbar
 * and surface as a transient `error` string on [BrowseGenreUiState.Ready] so the
 * screen can show inline messaging.
 */
class BrowseGenreViewModel(
    private val genreRepository: GenreRepository,
    private val errorBus: ErrorBus,
) : ViewModel() {
    private val local = MutableStateFlow(LocalState())

    val state: StateFlow<BrowseGenreUiState> =
        combine(
            genreRepository
                .observeAll()
                .map<List<Genre>, BrowseGenreUiState> { genres ->
                    BrowseGenreUiState.Ready(genres = genres)
                }.catch { e ->
                    if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                    logger.error(e) { "Failed to observe genres for browse" }
                    emit(BrowseGenreUiState.Error(e.message ?: "Failed to load genres"))
                },
            local,
        ) { upstream, l ->
            if (upstream is BrowseGenreUiState.Ready) {
                upstream.copy(
                    selectedGenreId = l.selectedGenreId,
                    books = l.books,
                    includeDescendants = l.includeDescendants,
                    isFetchingBooks = l.isFetchingBooks,
                    error = l.error,
                )
            } else {
                upstream
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
            initialValue = BrowseGenreUiState.Loading,
        )

    /** Select a genre and load its books (current `includeDescendants` setting). */
    fun selectGenre(genreId: GenreId) {
        local.update { it.copy(selectedGenreId = genreId, error = null) }
        loadBooks(genreId)
    }

    /** Toggle subtree inclusion. Re-fetches the currently selected genre's books. */
    fun toggleIncludeDescendants() {
        val next = !local.value.includeDescendants
        local.update { it.copy(includeDescendants = next) }
        local.value.selectedGenreId?.let(::loadBooks)
    }

    /** Clear the transient inline error. */
    fun clearError() {
        local.update { it.copy(error = null) }
    }

    private fun loadBooks(genreId: GenreId) {
        viewModelScope.launch {
            local.update { it.copy(isFetchingBooks = true) }
            val result =
                genreRepository.browseBooks(
                    genreId = genreId,
                    includeDescendants = local.value.includeDescendants,
                )
            when (result) {
                is AppResult.Success -> {
                    local.update { it.copy(books = result.data, isFetchingBooks = false) }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to fetch books for genre: ${result.error.message}" }
                    local.update {
                        it.copy(
                            isFetchingBooks = false,
                            error = userMessageFor(result.error),
                        )
                    }
                }
            }
        }
    }

    /** Action-mutated fields combined with the Room-observed genre tree. */
    private data class LocalState(
        val selectedGenreId: GenreId? = null,
        val books: List<BookId> = emptyList(),
        val includeDescendants: Boolean = false,
        val isFetchingBooks: Boolean = false,
        val error: String? = null,
    )
}

/** Sealed UiState for the Browse-by-Genre screen. */
sealed interface BrowseGenreUiState {
    data object Loading : BrowseGenreUiState

    /**
     * Genres have loaded; carries the tree, optional book-selection state, and
     * an `includeDescendants` toggle that scopes the per-genre book fetch.
     */
    data class Ready(
        val genres: List<Genre> = emptyList(),
        val selectedGenreId: GenreId? = null,
        val books: List<BookId> = emptyList(),
        val includeDescendants: Boolean = false,
        val isFetchingBooks: Boolean = false,
        val error: String? = null,
    ) : BrowseGenreUiState

    /** Terminal state when the observe pipeline fails. */
    data class Error(
        val message: String,
    ) : BrowseGenreUiState
}
