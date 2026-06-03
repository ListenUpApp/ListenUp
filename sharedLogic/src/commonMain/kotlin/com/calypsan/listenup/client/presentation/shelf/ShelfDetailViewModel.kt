package com.calypsan.listenup.client.presentation.shelf

import com.calypsan.listenup.api.result.AppResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.domain.model.ShelfDetail
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.domain.usecase.shelf.LoadShelfDetailUseCase
import com.calypsan.listenup.client.domain.usecase.shelf.RemoveBookFromShelfUseCase
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    private val userRepository: UserRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<ShelfDetailUiState>(ShelfDetailUiState.Idle)
    val state: StateFlow<ShelfDetailUiState> = _state.asStateFlow()

    private val _snackbarMessages = Channel<String>(Channel.BUFFERED)
    val snackbarMessages: Flow<String> = _snackbarMessages.receiveAsFlow()

    private var currentShelfId: String? = null

    /** Load shelf detail from the server. Always re-fetches to ensure fresh data. */
    fun loadShelf(shelfId: String) {
        currentShelfId = shelfId

        viewModelScope.launch {
            _state.value = ShelfDetailUiState.Loading

            val currentUserId =
                userRepository
                    .observeCurrentUser()
                    .first()
                    ?.id
                    ?.value

            _state.value =
                when (val result = loadShelfDetailUseCase(shelfId)) {
                    is AppResult.Success -> {
                        val shelfDetail = result.data
                        logger.debug { "Loaded shelf detail: ${shelfDetail.name}" }
                        ShelfDetailUiState.Ready(
                            detail = shelfDetail,
                            isOwner = currentUserId == shelfDetail.owner.id,
                        )
                    }

                    is AppResult.Failure -> {
                        logger.error { "Failed to load shelf: $shelfId - ${result.message}" }
                        ShelfDetailUiState.Error(result.message)
                    }
                }
        }
    }

    /** Remove a book from the shelf and reload. */
    fun removeBook(bookId: String) {
        val shelfId = currentShelfId ?: return

        viewModelScope.launch {
            when (val result = removeBookFromShelfUseCase(shelfId, bookId)) {
                is AppResult.Success -> {
                    logger.info { "Removed book $bookId from shelf $shelfId" }
                    currentShelfId = null // Force reload
                    loadShelf(shelfId)
                }

                is AppResult.Failure -> {
                    logger.error { "Failed to remove book from shelf: ${result.message}" }
                    _snackbarMessages.trySend(result.message)
                }
            }
        }
    }

    /** Format duration in seconds to human-readable string. */
    fun formatDuration(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = seconds % 3600 / 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
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
