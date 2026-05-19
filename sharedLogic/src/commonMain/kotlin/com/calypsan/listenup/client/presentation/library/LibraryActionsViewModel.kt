package com.calypsan.listenup.client.presentation.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.core.Failure
import com.calypsan.listenup.core.Success
import com.calypsan.listenup.client.domain.model.Collection
import com.calypsan.listenup.client.domain.model.Shelf
import com.calypsan.listenup.client.domain.repository.CollectionRepository
import com.calypsan.listenup.client.domain.repository.ShelfRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.domain.usecase.collection.AddBooksToCollectionUseCase
import com.calypsan.listenup.client.domain.usecase.collection.RefreshCollectionsUseCase
import com.calypsan.listenup.client.domain.usecase.shelf.AddBooksToShelfUseCase
import com.calypsan.listenup.client.domain.usecase.shelf.CreateShelfUseCase
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * ViewModel for Library batch actions (collections and shelves).
 *
 * Handles all batch operations on selected books:
 * - Adding books to admin collections
 * - Adding books to user shelves
 * - Creating new shelves with selected books
 *
 * Observes [LibrarySelectionManager] for the current selection state,
 * which is shared with [LibraryViewModel].
 */
class LibraryActionsViewModel(
    private val selectionManager: LibrarySelectionManager,
    private val userRepository: UserRepository,
    private val collectionRepository: CollectionRepository,
    private val shelfRepository: ShelfRepository,
    private val addBooksToCollectionUseCase: AddBooksToCollectionUseCase,
    private val refreshCollectionsUseCase: RefreshCollectionsUseCase,
    private val addBooksToShelfUseCase: AddBooksToShelfUseCase,
    private val createShelfUseCase: CreateShelfUseCase,
) : ViewModel() {
    // ═══════════════════════════════════════════════════════════════════════
    // SELECTION STATE (observed from shared manager)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Current selection mode, observed from the shared [LibrarySelectionManager].
     */
    val selectionMode: StateFlow<SelectionMode> = selectionManager.selectionMode

    /**
     * Number of currently selected books.
     * Convenience property for UI display (e.g., "3 selected").
     */
    val selectedCount: StateFlow<Int> =
        selectionMode
            .map { mode ->
                when (mode) {
                    is SelectionMode.None -> 0
                    is SelectionMode.Active -> mode.selectedIds.size
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = 0,
            )

    // ═══════════════════════════════════════════════════════════════════════
    // ADMIN STATE
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Whether the current user is an admin (isRoot).
     * Only admins can use multi-select to add books to collections.
     */
    val isAdmin: StateFlow<Boolean> =
        userRepository
            .observeCurrentUser()
            .map { user -> user?.isAdmin == true }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = false,
            )

    /**
     * Observable list of collections for the collection picker.
     * Only relevant for admins.
     */
    val collections: StateFlow<List<Collection>> =
        collectionRepository
            .observeAll()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList(),
            )

    // ═══════════════════════════════════════════════════════════════════════
    // SHELF STATE (all users)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Observable list of the current user's shelves for the shelf picker.
     * Available to all users (not just admins).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val myShelves: StateFlow<List<Shelf>> =
        userRepository
            .observeCurrentUser()
            .flatMapLatest { user ->
                if (user != null) {
                    shelfRepository.observeMyShelves(user.id.value)
                } else {
                    flowOf(emptyList())
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList(),
            )

    // ═══════════════════════════════════════════════════════════════════════
    // OPERATION STATE
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Whether an add-to-collection operation is in progress.
     */
    val isAddingToCollection: StateFlow<Boolean>
        field = MutableStateFlow(false)

    /**
     * Whether an add-to-shelf operation is in progress.
     */
    val isAddingToShelf: StateFlow<Boolean>
        field = MutableStateFlow(false)

    // ═══════════════════════════════════════════════════════════════════════
    // EVENTS
    // ═══════════════════════════════════════════════════════════════════════

    private val eventsChannel = Channel<LibraryActionEvent>(Channel.BUFFERED)

    /**
     * One-time events for UI feedback (snackbars, toasts).
     */
    val events: Flow<LibraryActionEvent> = eventsChannel.receiveAsFlow()

    // ═══════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Called when selection mode is entered.
     * Refreshes collections for admins to ensure picker has up-to-date data.
     */
    fun onSelectionModeEntered() {
        if (isAdmin.value) {
            refreshCollections()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // COLLECTION ACTIONS (admin only)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Add all selected books to the specified collection.
     * Uses AddBooksToCollectionUseCase and emits success/error events.
     *
     * @param collectionId The ID of the collection to add books to
     */
    fun addSelectedToCollection(collectionId: String) {
        val selectedIds = selectionManager.getSelectedIds()
        if (selectedIds.isEmpty()) return

        viewModelScope.launch {
            isAddingToCollection.value = true
            val bookIds = selectedIds.toList()

            when (val result = addBooksToCollectionUseCase(collectionId, bookIds)) {
                is Success -> {
                    logger.info { "Added ${bookIds.size} books to collection $collectionId" }
                    eventsChannel.send(LibraryActionEvent.BooksAddedToCollection(bookIds.size))
                    selectionManager.clearAfterAction()
                }

                is Failure -> {
                    logger.error { "Failed to add books to collection: ${result.message}" }
                    eventsChannel.send(LibraryActionEvent.AddToCollectionFailed(result.message))
                }
            }

            isAddingToCollection.value = false
        }
    }

    /**
     * Refresh collections from the server.
     * Uses RefreshCollectionsUseCase to sync the local database.
     */
    private fun refreshCollections() {
        viewModelScope.launch {
            when (val result = refreshCollectionsUseCase()) {
                is Success -> {
                    logger.debug { "Collections refreshed from server" }
                }

                is Failure -> {
                    logger.warn { "Failed to refresh collections from server: ${result.message}" }
                    // Don't emit error - local data is still usable
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SHELF ACTIONS (all users)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Add all selected books to the specified shelf.
     * Uses AddBooksToShelfUseCase and emits success/error events.
     *
     * @param shelfId The ID of the shelf to add books to
     */
    fun addSelectedToShelf(shelfId: String) {
        val selectedIds = selectionManager.getSelectedIds()
        if (selectedIds.isEmpty()) return

        viewModelScope.launch {
            isAddingToShelf.value = true
            val bookIds = selectedIds.toList()

            when (val result = addBooksToShelfUseCase(shelfId, bookIds)) {
                is Success -> {
                    logger.info { "Added ${bookIds.size} books to shelf $shelfId" }
                    eventsChannel.send(LibraryActionEvent.BooksAddedToShelf(bookIds.size))
                    selectionManager.clearAfterAction()
                }

                is Failure -> {
                    logger.error { "Failed to add books to shelf: ${result.message}" }
                    eventsChannel.send(LibraryActionEvent.AddToShelfFailed(result.message))
                }
            }

            isAddingToShelf.value = false
        }
    }

    /**
     * Create a new shelf and add all selected books to it.
     * First creates the shelf via use case, then adds the books.
     *
     * @param name The name for the new shelf
     */
    fun createShelfAndAddBooks(name: String) {
        val selectedIds = selectionManager.getSelectedIds()
        if (selectedIds.isEmpty()) return

        viewModelScope.launch {
            isAddingToShelf.value = true
            val bookIds = selectedIds.toList()

            // Create the shelf
            when (val createResult = createShelfUseCase(name, null)) {
                is Success -> {
                    val newShelf = createResult.data
                    logger.info { "Created shelf '${newShelf.name}' with id ${newShelf.id}" }

                    // Add books to the new shelf
                    when (val addResult = addBooksToShelfUseCase(newShelf.id, bookIds)) {
                        is Success -> {
                            logger.info { "Added ${bookIds.size} books to new shelf ${newShelf.id}" }
                            eventsChannel.send(
                                LibraryActionEvent.ShelfCreatedAndBooksAdded(newShelf.name, bookIds.size),
                            )
                            selectionManager.clearAfterAction()
                        }

                        is Failure -> {
                            logger.error { "Failed to add books to new shelf: ${addResult.message}" }
                            eventsChannel.send(LibraryActionEvent.AddToShelfFailed(addResult.message))
                        }
                    }
                }

                is Failure -> {
                    logger.error { "Failed to create shelf: ${createResult.message}" }
                    eventsChannel.send(LibraryActionEvent.AddToShelfFailed(createResult.message))
                }
            }

            isAddingToShelf.value = false
        }
    }
}

/**
 * One-time events emitted by [LibraryActionsViewModel] for UI feedback.
 */
sealed interface LibraryActionEvent {
    /**
     * Books were successfully added to a collection.
     */
    data class BooksAddedToCollection(
        val count: Int,
    ) : LibraryActionEvent

    /**
     * Failed to add books to a collection.
     */
    data class AddToCollectionFailed(
        val message: String,
    ) : LibraryActionEvent

    /**
     * Books were successfully added to a shelf.
     */
    data class BooksAddedToShelf(
        val count: Int,
    ) : LibraryActionEvent

    /**
     * A new shelf was created and books were added to it.
     */
    data class ShelfCreatedAndBooksAdded(
        val shelfName: String,
        val bookCount: Int,
    ) : LibraryActionEvent

    /**
     * Failed to add books to a shelf.
     */
    data class AddToShelfFailed(
        val message: String,
    ) : LibraryActionEvent
}
