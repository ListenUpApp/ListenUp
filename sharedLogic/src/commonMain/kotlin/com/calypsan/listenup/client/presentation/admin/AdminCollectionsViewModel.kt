package com.calypsan.listenup.client.presentation.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.domain.model.Collection
import com.calypsan.listenup.client.domain.repository.CollectionRepository
import com.calypsan.listenup.client.domain.repository.LibraryRepository
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.error.ErrorBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * ViewModel for the admin collections list screen.
 *
 * Reads collections reactively from the local Room mirror via
 * [CollectionRepository.observeCollections] (the sync engine keeps it current).
 * Create and delete dispatch to the repository, which forwards to the
 * `CollectionService` RPC; the resulting firehose echo updates Room, so the list
 * refreshes itself — there is no manual refresh path.
 *
 * `create` needs a library id (the new contract is library-scoped); ListenUp's
 * admin model is single-library-per-server, so the id is sourced from the first
 * library observed via [LibraryRepository].
 */
class AdminCollectionsViewModel(
    private val collectionRepository: CollectionRepository,
    private val libraryRepository: LibraryRepository,
    private val errorBus: ErrorBus,
) : ViewModel() {
    val state: StateFlow<AdminCollectionsUiState>
        field = MutableStateFlow<AdminCollectionsUiState>(AdminCollectionsUiState.Loading)

    init {
        observeCollections()
    }

    /** Observe collections from the local Room mirror; transition Loading → Ready on the first emission. */
    private fun observeCollections() {
        viewModelScope.launch {
            try {
                collectionRepository.observeCollections().collect { collections ->
                    logger.debug { "Collections updated: ${collections.size}" }
                    state.update { current ->
                        if (current is AdminCollectionsUiState.Ready) {
                            current.copy(collections = collections)
                        } else {
                            AdminCollectionsUiState.Ready(collections = collections)
                        }
                    }
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Failed to observe collections" }
                state.value = AdminCollectionsUiState.Error(e.message ?: "Failed to load collections")
            }
        }
    }

    /** Create a new collection with the given [name] in the admin's library. */
    fun createCollection(name: String) {
        viewModelScope.launch {
            updateReady { it.copy(isCreating = true, error = null) }

            val libraryId = currentLibraryId()
            if (libraryId == null) {
                updateReady { it.copy(isCreating = false, error = "No library available") }
                return@launch
            }

            when (val result = collectionRepository.create(libraryId, name)) {
                is AppResult.Success -> {
                    updateReady { it.copy(isCreating = false, createSuccess = true) }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    updateReady { it.copy(isCreating = false, error = result.error.message) }
                }
            }
        }
    }

    /** Delete the collection identified by [collectionId]. */
    fun deleteCollection(collectionId: String) {
        viewModelScope.launch {
            updateReady { it.copy(deletingCollectionId = collectionId, error = null) }

            when (val result = collectionRepository.delete(collectionId)) {
                is AppResult.Success -> {
                    updateReady { it.copy(deletingCollectionId = null) }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    updateReady { it.copy(deletingCollectionId = null, error = result.error.message) }
                }
            }
        }
    }

    /** Clear the transient error state. */
    fun clearError() {
        updateReady { it.copy(error = null) }
    }

    /** Clear the create-success flag. */
    fun clearCreateSuccess() {
        updateReady { it.copy(createSuccess = false) }
    }

    private suspend fun currentLibraryId(): String? =
        libraryRepository
            .observeAll()
            .first()
            .firstOrNull()
            ?.id

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
 * - [Loading] before the first emission from `observeCollections()`.
 * - [Ready] once collections have loaded; carries collections, action overlays
 *   (`isCreating`, `deletingCollectionId`), a `createSuccess` flag driving the
 *   post-create snackbar, and a transient `error` for mutation failures.
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
