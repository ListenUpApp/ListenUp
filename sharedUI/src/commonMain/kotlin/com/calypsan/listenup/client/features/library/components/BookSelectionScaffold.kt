package com.calypsan.listenup.client.features.library.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.components.LocalSnackbarHostState
import com.calypsan.listenup.client.features.library.CollectionPickerSheet
import com.calypsan.listenup.client.features.library.ShelfPickerSheet
import com.calypsan.listenup.client.presentation.books.BookMultiSelectEvent
import com.calypsan.listenup.client.presentation.books.BookMultiSelectViewModel
import com.calypsan.listenup.client.presentation.books.SelectionMode
import kotlinx.coroutines.launch

/**
 * Reusable multi-select overlay for any books-bearing screen (Library, Home, Discover).
 *
 * Drives the entire selection affordance from a single [BookMultiSelectViewModel]:
 * - a top-anchored [SelectionToolbar] that animates in while selection is active;
 * - the shelf / collection picker sheets (collection picker admin-gated as defense-in-depth);
 * - one-shot success feedback (snackbar + auto-dismiss of the matching picker).
 *
 * Failures are surfaced on the global ErrorBus by the VM, so this scaffold only reacts to the
 * success events ([BookMultiSelectEvent]). Place it as the last child of a fill-size container so
 * its top-anchored toolbar overlays the screen content; the picker sheets render as modals.
 *
 * @param multiSelect The per-screen multi-select ViewModel that owns selection + bulk actions.
 * @param modifier Modifier applied to the overlay container.
 */
@Composable
fun BookSelectionScaffold(
    multiSelect: BookMultiSelectViewModel,
    modifier: Modifier = Modifier,
) {
    val selectionMode by multiSelect.selectionMode.collectAsStateWithLifecycle()
    val isAdmin by multiSelect.isAdmin.collectAsStateWithLifecycle()
    val collections by multiSelect.collections.collectAsStateWithLifecycle()
    val myShelves by multiSelect.myShelves.collectAsStateWithLifecycle()
    val isAddingToShelf by multiSelect.isAddingToShelf.collectAsStateWithLifecycle()
    val isAddingToCollection by multiSelect.isAddingToCollection.collectAsStateWithLifecycle()

    val isInSelectionMode = selectionMode is SelectionMode.Active
    val selectedCount = (selectionMode as? SelectionMode.Active)?.selectedIds?.size ?: 0

    var showShelfPicker by rememberSaveable { mutableStateOf(false) }
    var showCollectionPicker by rememberSaveable { mutableStateOf(false) }

    // Success feedback: dismiss the matching picker and show a confirmation snackbar.
    BookSelectionFeedback(
        multiSelect = multiSelect,
        onShelfActionHandled = { showShelfPicker = false },
        onCollectionActionHandled = { showCollectionPicker = false },
    )

    // Selection toolbar (overlays at top of the host container).
    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = isInSelectionMode,
            enter = slideInVertically { -it },
            exit = slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            SelectionToolbar(
                selectedCount = selectedCount,
                onAddToShelf = { showShelfPicker = true },
                onAddToCollection =
                    if (isAdmin) {
                        { showCollectionPicker = true }
                    } else {
                        null
                    },
                onClose = multiSelect::exitSelectionMode,
            )
        }
    }

    // Collection picker sheet. Collections are admin-managed — gate the picker
    // presentation on isAdmin as defense-in-depth. The multi-select flow adds existing
    // collections only; inline create lives on the Book Detail picker (canCreate = false here).
    if (showCollectionPicker && isAdmin) {
        CollectionPickerSheet(
            collections = collections,
            selectedBookCount = selectedCount,
            onCollectionSelected = { collectionId ->
                multiSelect.addSelectedToCollection(collectionId)
            },
            onCreateAndAddToCollection = {},
            onDismiss = { showCollectionPicker = false },
            isLoading = isAddingToCollection,
        )
    }

    // Shelf picker sheet.
    if (showShelfPicker) {
        ShelfPickerSheet(
            shelves = myShelves,
            selectedBookCount = selectedCount,
            onShelfSelected = { shelfId ->
                multiSelect.addSelectedToShelf(shelfId)
            },
            onCreateAndAddToShelf = { name ->
                multiSelect.createShelfAndAddBooks(name)
            },
            onDismiss = { showShelfPicker = false },
            isLoading = isAddingToShelf,
        )
    }
}

/**
 * Collects [BookMultiSelectViewModel.events] and surfaces snackbar feedback for collection/shelf
 * adds. On a successful add it dismisses the matching picker via [onCollectionActionHandled] /
 * [onShelfActionHandled]. Snackbars launch on a dedicated scope so they outlive the collect.
 * Failures never reach here — the VM routes them to the global ErrorBus.
 */
@Composable
private fun BookSelectionFeedback(
    multiSelect: BookMultiSelectViewModel,
    onShelfActionHandled: () -> Unit,
    onCollectionActionHandled: () -> Unit,
) {
    val snackbarHostState = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        multiSelect.events.collect { event ->
            when (event) {
                is BookMultiSelectEvent.BooksAddedToCollection -> {
                    onCollectionActionHandled()
                    val message =
                        if (event.count == 1) {
                            "1 book added to collection"
                        } else {
                            "${event.count} books added to collection"
                        }
                    scope.launch { snackbarHostState.showSnackbar(message) }
                }

                is BookMultiSelectEvent.BooksAddedToShelf -> {
                    onShelfActionHandled()
                    val message =
                        if (event.count == 1) "1 book added to shelf" else "${event.count} books added to shelf"
                    scope.launch { snackbarHostState.showSnackbar(message) }
                }

                is BookMultiSelectEvent.ShelfCreatedAndBooksAdded -> {
                    onShelfActionHandled()
                    val bookText = if (event.bookCount == 1) "1 book" else "${event.bookCount} books"
                    scope.launch { snackbarHostState.showSnackbar("Created \"${event.shelfName}\" with $bookText") }
                }

                is BookMultiSelectEvent.CollectionCreatedAndBooksAdded -> {
                    onCollectionActionHandled()
                    val bookText = if (event.bookCount == 1) "1 book" else "${event.bookCount} books"
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            "Created \"${event.collectionName}\" with $bookText",
                        )
                    }
                }
            }
        }
    }
}
