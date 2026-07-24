package com.calypsan.listenup.client.presentation.books

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.Collection
import com.calypsan.listenup.client.domain.model.Shelf
import com.calypsan.listenup.client.domain.repository.CollectionRepository
import com.calypsan.listenup.client.domain.repository.ShelfRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.domain.usecase.collection.AddBooksToCollectionUseCase
import com.calypsan.listenup.client.domain.usecase.collection.CreateCollectionUseCase
import com.calypsan.listenup.client.domain.usecase.shelf.AddBooksToShelfUseCase
import com.calypsan.listenup.client.domain.usecase.shelf.CreateShelfUseCase
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ShelfId
import com.calypsan.listenup.core.error.ErrorBus
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
private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L

/**
 * Reusable per-screen ViewModel for multi-select and bulk add-to-shelf / add-to-collection.
 *
 * Any books-bearing screen (Library, Home, Discover) can drive multi-select through this single
 * VM: it owns the [selectionMode] state directly (no shared manager) and exposes the same bulk
 * action surface for admins (collections) and all users (shelves).
 *
 * Selection state lives in a [MutableStateFlow] private to the VM; entering selection seeds it
 * with the long-pressed book, toggling adds/removes and auto-exits when the last book is
 * deselected. Successful bulk actions clear the selection and emit a one-shot [events] feedback
 * event; failures keep the selection and surface the typed error on the global [errorBus].
 */
class BookMultiSelectViewModel(
    private val userRepository: UserRepository,
    private val collectionRepository: CollectionRepository,
    private val shelfRepository: ShelfRepository,
    private val addBooksToShelfUseCase: AddBooksToShelfUseCase,
    private val addBooksToCollectionUseCase: AddBooksToCollectionUseCase,
    private val createShelfUseCase: CreateShelfUseCase,
    private val createCollectionUseCase: CreateCollectionUseCase,
    private val errorBus: ErrorBus,
) : ViewModel() {
    // ═══════════════════════════════════════════════════════════════════════
    // SELECTION STATE (owned by this VM)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Current selection mode. [SelectionMode.None] when idle, [SelectionMode.Active] while the
     * user is multi-selecting.
     */
    val selectionMode: StateFlow<SelectionMode>
        field = MutableStateFlow<SelectionMode>(SelectionMode.None)

    // ═══════════════════════════════════════════════════════════════════════
    // ADMIN STATE
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Whether the current user is an admin. Only admins may add books to collections.
     */
    val isAdmin: StateFlow<Boolean> =
        userRepository
            .observeCurrentUser()
            .map { user -> user?.isAdmin == true }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
                initialValue = false,
            )

    /**
     * Observable list of collections for the collection picker (system collections excluded).
     * Only relevant for admins.
     */
    val collections: StateFlow<List<Collection>> =
        collectionRepository
            .observeCollections()
            .map { all -> all.filterNot { it.isSystem } }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
                initialValue = emptyList(),
            )

    // ═══════════════════════════════════════════════════════════════════════
    // SHELF STATE (all users)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Observable list of the current user's shelves for the shelf picker. Available to all users.
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
                started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
                initialValue = emptyList(),
            )

    // ═══════════════════════════════════════════════════════════════════════
    // OPERATION STATE
    // ═══════════════════════════════════════════════════════════════════════

    /** Whether an add-to-collection operation is in progress. */
    val isAddingToCollection: StateFlow<Boolean>
        field = MutableStateFlow(false)

    /** Whether an add-to-shelf operation is in progress. */
    val isAddingToShelf: StateFlow<Boolean>
        field = MutableStateFlow(false)

    // ═══════════════════════════════════════════════════════════════════════
    // EVENTS
    // ═══════════════════════════════════════════════════════════════════════

    private val eventsChannel = Channel<BookMultiSelectEvent>(Channel.BUFFERED)

    /** One-shot events for UI feedback (snackbars, toasts). */
    val events: Flow<BookMultiSelectEvent> = eventsChannel.receiveAsFlow()

    // ═══════════════════════════════════════════════════════════════════════
    // SELECTION ACTIONS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Enter selection mode with no books selected. Used by the native "Select" affordance, which
     * arms multi-select before any book is tapped; the user then taps books to build the selection.
     */
    fun enterSelectionMode() {
        selectionMode.value = SelectionMode.Active(selectedIds = emptySet())
        logger.debug { "Entered selection mode with no initial selection" }
    }

    /**
     * Enter selection mode with the given book as the initial selection.
     *
     * @param initialBookId The ID of the book that was long-pressed.
     */
    fun enterSelectionMode(initialBookId: String) {
        selectionMode.value = SelectionMode.Active(selectedIds = setOf(initialBookId))
        logger.debug { "Entered selection mode with book: $initialBookId" }
    }

    /**
     * Toggle the selection state of a book. Deselecting the last book exits selection mode.
     *
     * @param bookId The ID of the book to toggle.
     */
    fun toggleSelection(bookId: String) {
        val current = selectionMode.value
        if (current !is SelectionMode.Active) return

        val newSelectedIds =
            if (bookId in current.selectedIds) {
                current.selectedIds - bookId
            } else {
                current.selectedIds + bookId
            }

        selectionMode.value =
            if (newSelectedIds.isEmpty()) {
                logger.debug { "No books selected, exiting selection mode" }
                SelectionMode.None
            } else {
                SelectionMode.Active(selectedIds = newSelectedIds)
            }
    }

    /** Exit selection mode and clear all selections. */
    fun exitSelectionMode() {
        selectionMode.value = SelectionMode.None
        logger.debug { "Exited selection mode" }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // COLLECTION ACTIONS (admin only)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Add all selected books to the specified collection via [AddBooksToCollectionUseCase].
     * Clears the selection on success; surfaces the error on the [errorBus] and keeps the
     * selection on failure.
     *
     * @param collectionId The ID of the collection to add books to.
     */
    fun addSelectedToCollection(collectionId: String) {
        val selectedIds = currentSelectedIds()
        if (selectedIds.isEmpty()) return

        viewModelScope.launch {
            isAddingToCollection.value = true
            val bookIds = selectedIds.toList()

            when (val result = addBooksToCollectionUseCase(collectionId, bookIds)) {
                is AppResult.Success -> {
                    logger.info { "Added ${bookIds.size} books to collection $collectionId" }
                    eventsChannel.send(BookMultiSelectEvent.BooksAddedToCollection(bookIds.size))
                    clearSelection()
                }

                is AppResult.Failure -> {
                    logger.error { "Failed to add books to collection: ${result.message}" }
                    errorBus.emit(result.error)
                }
            }

            isAddingToCollection.value = false
        }
    }

    /**
     * Create a new collection and add all selected books to it. Creates the collection, then adds
     * the books. Clears the selection on success; surfaces the error on the [errorBus] and keeps
     * the selection on any failure. Admin only (the collection picker is admin-gated).
     *
     * @param name The name for the new collection.
     */
    fun createCollectionAndAddBooks(name: String) {
        val selectedIds = currentSelectedIds()
        if (selectedIds.isEmpty()) return

        viewModelScope.launch {
            isAddingToCollection.value = true
            val bookIds = selectedIds.toList()

            when (val createResult = createCollectionUseCase(name)) {
                is AppResult.Success -> {
                    val newCollection = createResult.data
                    logger.info { "Created collection '${newCollection.name}' with id ${newCollection.id}" }

                    when (val addResult = addBooksToCollectionUseCase(newCollection.id, bookIds)) {
                        is AppResult.Success -> {
                            logger.info { "Added ${bookIds.size} books to new collection ${newCollection.id}" }
                            eventsChannel.send(
                                BookMultiSelectEvent.CollectionCreatedAndBooksAdded(newCollection.name, bookIds.size),
                            )
                            clearSelection()
                        }

                        is AppResult.Failure -> {
                            logger.error { "Failed to add books to new collection: ${addResult.message}" }
                            errorBus.emit(addResult.error)
                        }
                    }
                }

                is AppResult.Failure -> {
                    logger.error { "Failed to create collection: ${createResult.message}" }
                    errorBus.emit(createResult.error)
                }
            }

            isAddingToCollection.value = false
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SHELF ACTIONS (all users)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Add all selected books to the specified shelf via [AddBooksToShelfUseCase].
     * Clears the selection on success; surfaces the error on the [errorBus] and keeps the
     * selection on failure.
     *
     * @param shelfId The ID of the shelf to add books to.
     */
    fun addSelectedToShelf(shelfId: String) {
        val selectedIds = currentSelectedIds()
        if (selectedIds.isEmpty()) return

        viewModelScope.launch {
            isAddingToShelf.value = true
            val bookIds = selectedIds.toList()

            when (val result = addBooksToShelfUseCase(ShelfId(shelfId), bookIds.map { BookId(it) })) {
                is AppResult.Success -> {
                    logger.info { "Added ${bookIds.size} books to shelf $shelfId" }
                    eventsChannel.send(BookMultiSelectEvent.BooksAddedToShelf(bookIds.size))
                    clearSelection()
                }

                is AppResult.Failure -> {
                    logger.error { "Failed to add books to shelf: ${result.message}" }
                    errorBus.emit(result.error)
                }
            }

            isAddingToShelf.value = false
        }
    }

    /**
     * Create a new shelf and add all selected books to it. Creates the shelf, then adds the books.
     * Clears the selection on success; surfaces the error on the [errorBus] and keeps the
     * selection on any failure.
     *
     * @param name The name for the new shelf.
     */
    fun createShelfAndAddBooks(name: String) {
        val selectedIds = currentSelectedIds()
        if (selectedIds.isEmpty()) return

        viewModelScope.launch {
            isAddingToShelf.value = true
            val bookIds = selectedIds.toList()

            when (val createResult = createShelfUseCase(name, null)) {
                is AppResult.Success -> {
                    val newShelf = createResult.data
                    logger.info { "Created shelf '${newShelf.name}' with id ${newShelf.id}" }

                    when (val addResult = addBooksToShelfUseCase(newShelf.id, bookIds.map { BookId(it) })) {
                        is AppResult.Success -> {
                            logger.info { "Added ${bookIds.size} books to new shelf ${newShelf.id}" }
                            eventsChannel.send(
                                BookMultiSelectEvent.ShelfCreatedAndBooksAdded(newShelf.name, bookIds.size),
                            )
                            clearSelection()
                        }

                        is AppResult.Failure -> {
                            logger.error { "Failed to add books to new shelf: ${addResult.message}" }
                            errorBus.emit(addResult.error)
                        }
                    }
                }

                is AppResult.Failure -> {
                    logger.error { "Failed to create shelf: ${createResult.message}" }
                    errorBus.emit(createResult.error)
                }
            }

            isAddingToShelf.value = false
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═══════════════════════════════════════════════════════════════════════

    private fun currentSelectedIds(): Set<String> =
        when (val mode = selectionMode.value) {
            is SelectionMode.None -> emptySet()
            is SelectionMode.Active -> mode.selectedIds
        }

    private fun clearSelection() {
        selectionMode.value = SelectionMode.None
        logger.debug { "Cleared selection after action" }
    }
}

/**
 * Selection mode state for multi-select functionality.
 */
sealed interface SelectionMode {
    /** No selection active - normal browsing behavior. */
    data object None : SelectionMode

    /** Multi-select mode is active with the given selected book IDs. */
    data class Active(
        val selectedIds: Set<String>,
    ) : SelectionMode
}

/**
 * One-shot success events emitted by [BookMultiSelectViewModel] for UI feedback (dismiss picker,
 * clear selection, show a confirmation). Failures are surfaced on the global [ErrorBus], not here.
 */
sealed interface BookMultiSelectEvent {
    /** Books were successfully added to a collection. */
    data class BooksAddedToCollection(
        val count: Int,
    ) : BookMultiSelectEvent

    /** Books were successfully added to a shelf. */
    data class BooksAddedToShelf(
        val count: Int,
    ) : BookMultiSelectEvent

    /** A new shelf was created and books were added to it. */
    data class ShelfCreatedAndBooksAdded(
        val shelfName: String,
        val bookCount: Int,
    ) : BookMultiSelectEvent

    /** A new collection was created and books were added to it. */
    data class CollectionCreatedAndBooksAdded(
        val collectionName: String,
        val bookCount: Int,
    ) : BookMultiSelectEvent
}
