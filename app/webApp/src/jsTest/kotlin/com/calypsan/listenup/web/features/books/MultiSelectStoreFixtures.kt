package com.calypsan.listenup.web.features.books

import com.calypsan.listenup.client.domain.model.Collection
import com.calypsan.listenup.client.domain.model.Shelf
import com.calypsan.listenup.client.presentation.books.BookMultiSelectEvent
import com.calypsan.listenup.client.presentation.books.SelectionMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow

/** A session over fixed values — the shape specs pass in place of the graph. */
@Suppress("LongParameterList")
fun fixedMultiSelect(
    selectionMode: SelectionMode = SelectionMode.None,
    isAdmin: Boolean = false,
    collections: List<Collection> = emptyList(),
    myShelves: List<Shelf> = emptyList(),
    isAddingToShelf: Boolean = false,
    isAddingToCollection: Boolean = false,
    events: Flow<BookMultiSelectEvent> = emptyFlow(),
    onEnter: () -> Unit = {},
    onToggle: (String) -> Unit = {},
    onExit: () -> Unit = {},
    onSelectAll: (List<String>) -> Unit = {},
    onDeselectAll: () -> Unit = {},
    onAddToShelf: (String) -> Unit = {},
    onCreateShelfAndAdd: (String) -> Unit = {},
    onAddToCollection: (String) -> Unit = {},
    onCreateCollectionAndAdd: (String) -> Unit = {},
): OpenMultiSelect =
    {
        MultiSelectSession(
            selectionMode = MutableStateFlow(selectionMode),
            adminAccess = MutableStateFlow(isAdmin),
            collections = MutableStateFlow(collections),
            myShelves = MutableStateFlow(myShelves),
            addingToShelf = MutableStateFlow(isAddingToShelf),
            addingToCollection = MutableStateFlow(isAddingToCollection),
            events = events,
            onEnter = onEnter,
            onToggle = onToggle,
            onExit = onExit,
            onSelectAll = onSelectAll,
            onDeselectAll = onDeselectAll,
            onAddToShelf = onAddToShelf,
            onCreateShelfAndAdd = onCreateShelfAndAdd,
            onAddToCollection = onAddToCollection,
            onCreateCollectionAndAdd = onCreateCollectionAndAdd,
            close = {},
        )
    }
