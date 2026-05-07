package com.calypsan.listenup.client.presentation.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.core.error.ErrorBus
import com.calypsan.listenup.client.domain.model.Collection
import com.calypsan.listenup.client.domain.repository.CollectionRepository
import com.calypsan.listenup.client.domain.usecase.collection.CreateCollectionUseCase
import com.calypsan.listenup.client.domain.usecase.collection.DeleteCollectionUseCase
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * ViewModel for the admin collections list screen.
 *
 * Manages the list of collections, including create and delete operations.
 * Observes local Room database for real-time updates from SSE events.
 *
 * Delegates mutation operations to use cases:
 * - [CreateCollectionUseCase]: Creates new collections with validation
 * - [DeleteCollectionUseCase]: Deletes collections
 */
class AdminCollectionsViewModel(
    private val collectionRepository: CollectionRepository,
    private val createCollectionUseCase: CreateCollectionUseCase,
    private val deleteCollectionUseCase: DeleteCollectionUseCase,
    private val errorBus: ErrorBus,
) : ViewModel() {
    val state: StateFlow<AdminCollectionsUiState>
        field = MutableStateFlow<AdminCollectionsUiState>(AdminCollectionsUiState.Loading)

    init {
        observeCollections()
        refreshCollections()
    }

    /**
     * Observe collections from local database.
     * Updates are pushed via SSE events and processed by SSEEventProcessor.
     */
    private fun observeCollections() {
        viewModelScope.launch {
            try {
                collectionRepository.observeAll().collect { collections ->
                    logger.debug { "Collections updated: ${collections.size}" }
                    state.update { current ->
                        if (current is AdminCollectionsUiState.Ready) {
                            current.copy(collections = collections)
                        } else {
                            // First emission (from Loading) or recovering from Error:
                            // transition to Ready with fresh data and default UI fields.
                            AdminCollectionsUiState.Ready(collections = collections)
                        }
                    }
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Failed to observe collections" }
                state.value =
                    AdminCollectionsUiState.Error(e.message ?: "Failed to load collections")
            }
        }
    }

    /**
     * Refresh collections from the server API.
     * This syncs the local database with the latest server state including book counts.
     */
    fun refreshCollections() {
        viewModelScope.launch {
            when (val result = collectionRepository.refreshFromServer()) {
                is Success -> {
                    logger.debug { "Refreshed collections from server" }
                }

                is Failure -> {
                    errorBus.emit(result.error)
                    logger.warn { "Failed to refresh collections from server: ${result.message}" }
                    // Don't update error state - local data is still usable
                }
            }
        }
    }

    /**
     * Create a new collection with the given name.
     *
     * Delegates to [CreateCollectionUseCase] for validation and persistence.
     */
    fun createCollection(name: String) {
        viewModelScope.launch {
            updateReady { it.copy(isCreating = true, error = null) }

            when (val result = createCollectionUseCase(name)) {
                is Success -> {
                    updateReady { it.copy(isCreating = false, createSuccess = true) }
                }

                is Failure -> {
                    errorBus.emit(result.error)
                    updateReady {
                        it.copy(isCreating = false, error = result.message)
                    }
                }
            }
        }
    }

    /**
     * Delete a collection by ID.
     *
     * Delegates to [DeleteCollectionUseCase].
     */
    fun deleteCollection(collectionId: String) {
        viewModelScope.launch {
            updateReady { it.copy(deletingCollectionId = collectionId, error = null) }

            when (val result = deleteCollectionUseCase(collectionId)) {
                is Success -> {
                    updateReady { it.copy(deletingCollectionId = null) }
                }

                is Failure -> {
                    errorBus.emit(result.error)
                    updateReady {
                        it.copy(deletingCollectionId = null, error = result.message)
                    }
                }
            }
        }
    }

    /**
     * Clear the error state.
     */
    fun clearError() {
        updateReady { it.copy(error = null) }
    }

    /**
     * Clear the create success flag.
     */
    fun clearCreateSuccess() {
        updateReady { it.copy(createSuccess = false) }
    }

    /**
     * Apply [transform] to state only if it is currently [AdminCollectionsUiState.Ready].
     * No-ops when state is [AdminCollectionsUiState.Loading] or [AdminCollectionsUiState.Error].
     */
    private fun updateReady(transform: (AdminCollectionsUiState.Ready) -> AdminCollectionsUiState.Ready) {
        state.update { current ->
            if (current is AdminCollectionsUiState.Ready) transform(current) else current
        }
    }
}

/**
 * UI state for the admin collections list screen.
 *
 * Sealed hierarchy:
 * - [Loading] before the first emission from `observeAll()`.
 * - [Ready] once collections have loaded; carries collections, action overlays
 *   (`isCreating`, `deletingCollectionId`), a `createSuccess` flag driving the
 *   post-create snackbar, and a transient `error` for mutation failures
 *   surfaced in a snackbar.
 * - [Error] if the observe pipeline fails (terminal until the flow recovers).
 */
sealed interface AdminCollectionsUiState {
    data object Loading : AdminCollectionsUiState

    /** Collections have loaded; carries the list, action overlays, success flag, and a transient `error`. */
    data class Ready(
        val collections: List<Collection> = emptyList(),
        val isCreating: Boolean = false,
        val createSuccess: Boolean = false,
        val deletingCollectionId: String? = null,
        val error: String? = null,
    ) : AdminCollectionsUiState

    /** Terminal state when the observe pipeline fails. */
    data class Error(
        val message: String,
    ) : AdminCollectionsUiState
}
