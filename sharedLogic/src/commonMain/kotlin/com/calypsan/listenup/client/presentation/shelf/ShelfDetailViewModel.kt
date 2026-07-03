package com.calypsan.listenup.client.presentation.shelf

import com.calypsan.listenup.api.result.AppResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.domain.model.ShelfDetail
import com.calypsan.listenup.client.domain.usecase.shelf.LoadShelfDetailUseCase
import com.calypsan.listenup.client.domain.usecase.shelf.RemoveBookFromShelfUseCase
import com.calypsan.listenup.client.domain.usecase.shelf.ReorderShelfBooksUseCase
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ShelfId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * ViewModel for the Shelf Detail screen.
 *
 * Fetches shelf detail from the server and manages a sealed [ShelfDetailUiState].
 * Command-driven — the load pipeline is a one-shot suspend call per shelf id,
 * not an upstream Flow observation, so state is produced via [MutableStateFlow].
 */
class ShelfDetailViewModel(
    private val loadShelfDetailUseCase: LoadShelfDetailUseCase,
    private val removeBookFromShelfUseCase: RemoveBookFromShelfUseCase,
    private val reorderShelfBooksUseCase: ReorderShelfBooksUseCase,
) : ViewModel() {
    val state: StateFlow<ShelfDetailUiState>
        field = MutableStateFlow<ShelfDetailUiState>(ShelfDetailUiState.Idle)

    private val _snackbarMessages = Channel<String>(Channel.BUFFERED)
    val snackbarMessages: Flow<String> = _snackbarMessages.receiveAsFlow()

    private var currentShelfId: ShelfId? = null

    /** Load shelf detail from the server. Always re-fetches to ensure fresh data. */
    fun loadShelf(shelfId: String) {
        val id = ShelfId(shelfId)
        currentShelfId = id

        viewModelScope.launch {
            state.value = ShelfDetailUiState.Loading

            state.value =
                when (val result = loadShelfDetailUseCase(id)) {
                    is AppResult.Success -> {
                        val shelfDetail = result.data
                        logger.debug { "Loaded shelf detail: ${shelfDetail.name}" }
                        ShelfDetailUiState.Ready(
                            detail = shelfDetail,
                            isOwner = shelfDetail.isOwner,
                        )
                    }

                    is AppResult.Failure -> {
                        logger.error { "Failed to load shelf: $shelfId - ${result.message}" }
                        ShelfDetailUiState.Error(result.message)
                    }
                }
        }
    }

    /**
     * Reorder the shelf's books to [orderedBookIds] and reload on success.
     * Owner-only — the screen gates this on [ShelfDetailUiState.Ready.isOwner].
     */
    fun reorderBooks(orderedBookIds: List<String>) {
        val shelfId = currentShelfId ?: return

        viewModelScope.launch {
            when (val result = reorderShelfBooksUseCase(shelfId, orderedBookIds.map { BookId(it) })) {
                is AppResult.Success -> {
                    logger.info { "Reordered books in shelf $shelfId" }
                    currentShelfId = null // Force reload
                    loadShelf(shelfId.value)
                }

                is AppResult.Failure -> {
                    logger.error { "Failed to reorder shelf books: ${result.message}" }
                    _snackbarMessages.trySend(result.message)
                }
            }
        }
    }

    /** Remove a book from the shelf and reload. */
    fun removeBook(bookId: String) {
        val shelfId = currentShelfId ?: return

        viewModelScope.launch {
            when (val result = removeBookFromShelfUseCase(shelfId, BookId(bookId))) {
                is AppResult.Success -> {
                    logger.info { "Removed book $bookId from shelf $shelfId" }
                    currentShelfId = null // Force reload
                    loadShelf(shelfId.value)
                }

                is AppResult.Failure -> {
                    logger.error { "Failed to remove book from shelf: ${result.message}" }
                    _snackbarMessages.trySend(result.message)
                }
            }
        }
    }
}

/**
 * UI state for the Shelf Detail screen.
 *
 * Sealed hierarchy — the screen is in exactly one of these states.
 */
sealed interface ShelfDetailUiState {
    /** Pre-load initial state. */
    data object Idle : ShelfDetailUiState

    /** Fetch in progress. */
    data object Loading : ShelfDetailUiState

    /** Shelf loaded successfully. */
    data class Ready(
        val detail: ShelfDetail,
        val isOwner: Boolean,
    ) : ShelfDetailUiState

    /** Load or mutation failed. */
    data class Error(
        val message: String,
    ) : ShelfDetailUiState
}
